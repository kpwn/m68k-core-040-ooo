package m68k040.lockstep

import m68k040.VerilatorTest
import spinal.core._
import spinal.core.sim._
import spinal.lib.sim.SparseMemory
import org.scalatest.funsuite.AnyFunSuite

/** ADDRESS-SPACE battery: the three places this core confused "which address space"
  * with "who is running", plus the instruction-cache enable that had no reader.
  *
  * Sibling of `Addr32LockStepSpec` (which covers "which address BITS"); the helpers
  * come from the same `ExecuteLockStepSpec` harness.
  *
  *  1. MOVES honours SFC / DFC. On a 68040 the function code selects the ADDRESS
  *     SPACE, which is the entire point of the instruction -- supervisor code uses
  *     MOVES to read and write USER space. This core stored SFC/DFC (round-tripping
  *     them through MOVEC) and then translated every MOVES by the live architectural
  *     S bit, so the access used the wrong root pointer and was protection-checked
  *     against the wrong privilege.
  *  2. The ATC tags FC2. A consequence of (1) that has to land with it: with a
  *     VPN-only tag, one virtual page can hold only one translation, so the first
  *     space to fill it answers the other one too.
  *  3. 8 KB pages use the whole ATC. `tlbKey` masked VPN bit 0 to zero while
  *     `Tlb.bankOf` IS bit 0, so bank 1 of both TLBs was unreachable: 16 of 32
  *     entries, on a board that runs 8 KB pages with neither TTR over main memory.
  *  4. CACR.IE turns the instruction cache off. It had no reader anywhere.
  */
class AddrSpaceLockStepSpec extends AnyFunSuite {

  private val h = new ExecuteLockStepSpec

  // ── page tables ──────────────────────────────────────────────────────────────
  // The supervisor tree is the harness's own (`h.MMU_ROOT` / `h.MMU_PTRT`); the user
  // tree is a SECOND, independent one so URP =/= SRP for real, exactly like the live
  // board (SRP = 0x03FFFA00, URP = 0). A shared tree would make every one of these
  // tests vacuous.
  private val U_ROOT = 0x00090000L
  private val U_PTRT = 0x00091000L
  private val U_PAGT = 0x00092000L

  private def poke32BE(dmem: m68k040.ls.BehavioralMemAgent, a: Long, w: Long): Unit =
    for (i <- 0 until 4) dmem.pokeByte(a + i, ((w >> (8 * (3 - i))) & 0xff).toInt)

  /** One 4 KB mapping into an arbitrary tree. `leafFlags` ORs into the page
    * descriptor: 0x1 = resident, 0x80 = supervisor-only, 0x4 = write-protected. */
  private def map4k(dmem: m68k040.ls.BehavioralMemAgent, root: Long, ptrt: Long,
                    pagt: Long, va: Long, pa: Long, leafFlags: Long = 0x1L): Unit = {
    poke32BE(dmem, root + ((va >> 25) & 0x7f) * 4, (ptrt & 0xfffffff0L) | 0x3L)
    poke32BE(dmem, ptrt + ((va >> 18) & 0x7f) * 4, (pagt & 0xfffffff0L) | 0x3L)
    poke32BE(dmem, pagt + ((va >> 12) & 0x3f) * 4, (pa & 0xfffff000L) | leafFlags)
  }

  /** One 8 KB mapping (TCR.P = 1): root VA[31:25], pointer VA[24:18], page VA[17:13]. */
  private def map8k(dmem: m68k040.ls.BehavioralMemAgent, root: Long, ptrt: Long,
                    pagt: Long, va: Long, pa: Long, leafFlags: Long = 0x1L): Unit = {
    poke32BE(dmem, root + ((va >> 25) & 0x7f) * 4, (ptrt & 0xfffffff0L) | 0x3L)
    poke32BE(dmem, ptrt + ((va >> 18) & 0x7f) * 4, (pagt & 0xfffffff0L) | 0x3L)
    poke32BE(dmem, pagt + ((va >> 13) & 0x1f) * 4, (pa & 0xffffe000L) | leafFlags)
  }

  /** The boot poke sequence every bespoke full-core sim in this repo repeats. */
  private def boot(dut: ExecuteLockStepSpec#FullCoreDut, cd: ClockDomain, loadAddr: Long,
                   mmuOn: Boolean, page8K: Boolean, urp: Long, srp: Long,
                   cacr: Long, sp: Long, dtt0: Long = 0L): Unit = {
    dut.fa.logic.redirect.valid #= false
    dut.fa.logic.resume.valid   #= false
    dut.rob.logic.flush.valid   #= false
    dut.icache.logic.invalidateAll #= false
    dut.wire.logic.seedValid #= false; dut.wire.logic.seedAddr #= 0; dut.wire.logic.seedData #= 0
    dut.intCtrl.logic.iplIn #= 0; dut.intCtrl.logic.iackAvec #= true; dut.intCtrl.logic.iackVector #= 0
    cd.waitSampling(2)
    dut.icache.logic.invalidateAll #= true
    cd.waitSampling(); dut.icache.logic.invalidateAll #= false
    cd.waitSampling(80)
    dut.ctrl.logic.mmuEnable  #= mmuOn
    dut.ctrl.logic.pageSize8K #= page8K
    dut.ctrl.logic.urp #= urp
    dut.ctrl.logic.srp #= srp
    // Poked HERE, before the redirect that starts the program -- not after `boot`
    // returns. The guest's very first data access (staging its vector table) already
    // needs the transparent mapping, and it is only a handful of instructions in.
    dut.ctrl.logic.dtt0 #= dtt0
    dut.rob.logic.exc.ss.isp  #= sp
    dut.rob.logic.exc.ss.cacr #= cacr
    dut.wire.logic.seedValid #= true
    dut.wire.logic.seedAddr  #= 15
    dut.wire.logic.seedData  #= BigInt(sp)
    cd.waitSampling(2); dut.wire.logic.seedValid #= false; cd.waitSampling()
    dut.fa.logic.redirect.valid #= true; dut.fa.logic.redirect.payload #= loadAddr
    cd.waitSampling(); dut.fa.logic.redirect.valid #= false
  }

  private def assemble(tag: String, src: String, loadAddr: Long): m68k040.oracle.ProgramAssembler.Image =
    m68k040.oracle.ProgramAssembler.assemble(src, loadAddr) match {
      case Right(i)  => i
      case Left(err) => fail(s"[$tag] assemble failed: ${err.reason}")
    }

  // ═══════════════════════════════════════════════════════════════════════════════
  // 1. MOVES TRANSLATES BY SFC / DFC, NOT BY THE LIVE S BIT
  // ═══════════════════════════════════════════════════════════════════════════════
  //
  // SFC and DFC were stored (`SystemState.scala`) and read back by MOVEC
  // (`ExceptionUnit.scala`), and then nothing consumed them: the DTLB request's
  // supervisor bit came from the live architectural S bit
  // (`LsEuPlugin.scala`, `dst.supervisor := privCtrl.supervisor`), so a MOVES issued
  // by supervisor code walked SRP and was protection-checked as supervisor no matter
  // what the programmed function code said.
  //
  // ONE virtual page, mapped in BOTH trees to DIFFERENT physical pages. With SFC =
  // DFC = 1 (user data) a MOVES must reach the USER page and an ordinary access must
  // still reach the SUPERVISOR one, in the same instruction stream.
  //
  // Fail-before: D3 reads 0x11111111 (the supervisor page) instead of 0x5A5A5A5A,
  // and the MOVES store lands on the supervisor page.
  //
  // This also pins the ATC's new FC2 tag bit: D4 and D6 are ordinary supervisor loads
  // of the SAME virtual address the MOVES just translated. With a VPN-only ATC tag
  // they would hit the user-space entry the MOVES filled and read the user page.
  test("moves: SFC/DFC select the address space (URP vs SRP), both directions", VerilatorTest) {
    val loadAddr = m68k040.oracle.ProgramAssembler.DefaultLoadAddress
    val DataVa   = 0x01FF6000L
    val PaSup    = 0x00700000L      // what the SUPERVISOR tree maps DataVa to
    val PaUsr    = 0x00710000L      // what the USER tree maps DataVa to
    val SupWord  = 0x11111111L
    val UsrWord  = 0x5A5A5A5AL
    val StoreW   = 0xC0DE0001L

    val src = Seq(
      "moveq #1,%d0",
      "movec %d0,%sfc",                       // SFC = 1 : user data space
      "movec %d0,%dfc",                       // DFC = 1 : user data space
      f"move.l #0x$DataVa%x,%%a0",
      "moves.l (%a0),%d3",                    // MUST read the USER page
      "move.l (%a0),%d4",                     // ordinary supervisor read -> SUPERVISOR page
      f"move.l #0x$StoreW%x,%%d5",
      "moves.l %d5,(%a0)",                    // MUST write the USER page
      "move.l (%a0),%d6",                     // supervisor page must be UNCHANGED
      "stop: bra stop").mkString(" ; ")
    val image = assemble("moves-fc", src, loadAddr)

    var d3, d4, d6 = -1L
    var usrMem, supMem = -1L
    h.compiledDut.doSim(s"moves-fc-${System.nanoTime()}") { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      h.attachProgram(dut.icache.logic.axi, cd, loadAddr, image.bytes)
      val dmem = new m68k040.ls.BehavioralMemAgent(dut.dcache.logic.axi, cd)

      // Supervisor tree: identity-mapped code + DataVa -> PaSup.
      h.buildMmuTable(dmem, DataVa, PaSup >> 12)
      // User tree: DataVa -> PaUsr, and nothing else. An instruction fetch is a
      // supervisor access, so the user tree never needs the code pages.
      map4k(dmem, U_ROOT, U_PTRT, U_PAGT, DataVa, PaUsr)

      poke32BE(dmem, PaSup, SupWord)
      poke32BE(dmem, PaUsr, UsrWord)

      boot(dut, cd, loadAddr, mmuOn = true, page8K = false,
           urp = U_ROOT, srp = h.MMU_ROOT, cacr = 0x80008000L, sp = 0x00120000L)
      cd.waitSampling(40000)

      val probe = new ArchStateProbe(dut.ren, dut.rfInt, dut.rfNzvc, dut.rfX)
      d3 = probe.readArch(3); d4 = probe.readArch(4); d6 = probe.readArch(6)
      def rd(a: Long): Long =
        (0 until 4).foldLeft(0L)((acc, i) => (acc << 8) | (dmem.peekByte(a + i) & 0xffL))
      usrMem = rd(PaUsr); supMem = rd(PaSup)
    }

    // Not vacuous first: if the ordinary supervisor load did not read the supervisor
    // page, the two trees are not set up the way this test believes.
    assert(d4 == SupWord,
      f"VACUOUS or ATC ALIASING: the ordinary supervisor `move.l (%%a0),%%d4` read " +
        f"0x$d4%08x, expected 0x$SupWord%08x. Either the supervisor tree is not mapped " +
        "as this test assumes, or the ATC answered a supervisor access out of the " +
        "USER-space entry the preceding MOVES filled (a VPN-only tag with no FC2 bit).")
    assert(d3 == UsrWord,
      f"MOVES IGNORED SFC: `moves.l (%%a0),%%d3` with SFC=1 read 0x$d3%08x, expected the " +
        f"USER page's 0x$UsrWord%08x. Reading 0x$SupWord%08x means the DTLB request's " +
        "supervisor bit still came from the live S bit, so the walk used SRP.")
    assert(usrMem == StoreW,
      f"MOVES IGNORED DFC: `moves.l %%d5,(%%a0)` with DFC=1 did not land on the USER page " +
        f"(PA 0x$PaUsr%08x reads 0x$usrMem%08x, expected 0x$StoreW%08x)")
    assert(supMem == SupWord,
      f"MOVES IGNORED DFC: the user-space store CORRUPTED the supervisor page " +
        f"(PA 0x$PaSup%08x reads 0x$supMem%08x, expected 0x$SupWord%08x)")
    assert(d6 == SupWord,
      f"the supervisor-space read after the MOVES store returned 0x$d6%08x, expected " +
        f"0x$SupWord%08x")
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // 2. THE PROTECTION CHECK USES THE FUNCTION CODE
  // ═══════════════════════════════════════════════════════════════════════════════
  //
  // The other half of the same defect, and the one with a visible architectural
  // symptom: a MOVES carrying a USER function code that touches a SUPERVISOR-ONLY
  // page must take an access fault. Translating by the live S bit means supervisor
  // code never faults, so an OS using MOVES to validate a user pointer -- which is
  // what MOVES is FOR -- silently accepts pointers into supervisor-only pages.
  //
  // The target page is mapped in BOTH trees: supervisor-only (descriptor S=1) in the
  // user tree, unrestricted in the supervisor tree. So the ordinary load succeeds and
  // only the MOVES faults, which separates "the function code chose the wrong tree"
  // from "the page is simply unreachable".
  test("moves: a user-function-code access to a supervisor-only page faults", VerilatorTest) {
    val loadAddr  = m68k040.oracle.ProgramAssembler.DefaultLoadAddress
    val SysVa     = 0x01FF6000L     // stack + vector table (supervisor tree)
    val SysPa     = 0x00700000L
    val TgtVa     = 0x01FF4000L
    val TgtPaSup  = 0x00720000L     // supervisor tree: unrestricted
    val TgtPaUsr  = 0x00730000L     // user tree: SUPERVISOR-ONLY (descriptor S bit)
    val SupWord   = 0x22222222L
    val StackVa   = SysVa + 0xF00
    val VecSlotVa = SysVa + 2 * 4   // vector 2, access fault

    val src = Seq(
      f"move.l #0x$SysVa%x,%%d0", "movec %d0,%vbr",
      f"move.l #0x$StackVa%x,%%a7",
      "move.l #handler,%d1",
      f"move.l %%d1,0x$VecSlotVa%x",
      "moveq #1,%d0", "movec %d0,%sfc",       // SFC = 1 : user data space
      f"move.l #0x$TgtVa%x,%%a0",
      "moveq #0,%d3", "moveq #0,%d6",
      "move.l (%a0),%d4",                     // supervisor path: MUST succeed
      "moves.l (%a0),%d5",                    // user FC on a supervisor-only page: MUST fault
      "moveq #99,%d6",                        // must NOT run
      "bra stop",
      "handler: moveq #77,%d3", "bra stop",
      "stop: bra stop").mkString(" ; ")
    val image = assemble("moves-prot", src, loadAddr)

    var d3, d4, d6 = -1L
    h.compiledDut.doSim(s"moves-prot-${System.nanoTime()}") { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      h.attachProgram(dut.icache.logic.axi, cd, loadAddr, image.bytes)
      val dmem = new m68k040.ls.BehavioralMemAgent(dut.dcache.logic.axi, cd)

      h.buildMmuTable(dmem, SysVa, SysPa >> 12)                       // code + sys page
      map4k(dmem, h.MMU_ROOT, h.MMU_PTRT, h.MMU_PAGT, TgtVa, TgtPaSup)          // sup: open
      map4k(dmem, U_ROOT, U_PTRT, U_PAGT, TgtVa, TgtPaUsr, 0x80L | 0x1L)        // usr: S=1
      poke32BE(dmem, TgtPaSup, SupWord)

      boot(dut, cd, loadAddr, mmuOn = true, page8K = false,
           urp = U_ROOT, srp = h.MMU_ROOT, cacr = 0x80008000L, sp = 0x00120000L)
      cd.waitSampling(40000)

      val probe = new ArchStateProbe(dut.ren, dut.rfInt, dut.rfNzvc, dut.rfX)
      d3 = probe.readArch(3); d4 = probe.readArch(4); d6 = probe.readArch(6)
    }

    assert(d4 == SupWord,
      f"VACUOUS: the ordinary supervisor `move.l (%%a0),%%d4` read 0x$d4%08x, expected " +
        f"0x$SupWord%08x -- the target page is not reachable in SUPERVISOR space at all, " +
        "so a fault on the MOVES would prove nothing about the function code.")
    assert(d3 == 77L,
      f"MISSING FAULT: `moves.l (%%a0),%%d5` with SFC=1 (user) touched a page the USER " +
        f"tree marks SUPERVISOR-ONLY and did NOT take an access fault (D3 = 0x$d3%08x, " +
        "expected 77). The protection check used the live S bit instead of FC2.")
    assert(d6 == 0L,
      f"the instruction after the faulting MOVES executed (D6 = 0x$d6%08x, expected 0) -- " +
        "the fault was not precise")
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // 3. AND 4. THE ATC IS FULL SIZE IN 8 KB MODE
  // ═══════════════════════════════════════════════════════════════════════════════
  //
  // `Tlb` is 32 entries = 2 banks x 4 ways x 4 sets, decomposed bank = key[0],
  // set = key[2:1], tag = key[19:3]. In 8 KB mode `tlbKey` used to MASK key[0] to
  // zero (VA[12] is an in-page offset bit at 8 KB, so it must not distinguish pages)
  // -- which also made `bankOf` constant 0. Bank 1 was never looked up and never
  // filled: 16 of 32 entries, in exactly the configuration the board runs (TC =
  // 0x0000C000) with neither TTR covering main memory, so every RAM and ROM access
  // walks the tables.
  //
  // Shifting instead of masking keeps the key injective over the 19 meaningful VPN
  // bits and gives bank = VA[13], set = VA[15:14]. 32 CONSECUTIVE 8 KB pages
  // therefore cover all 8 (bank,set) groups exactly 4 times -- filling every way of
  // every set, which is what makes an exact census meaningful rather than a
  // statistical one.
  //
  // Fail-before, both tests: 16 entries, ALL of them in bank 0.

  /** Census of a TLB's valid bits, as (bank 0 occupancy, bank 1 occupancy). */
  private def census(tlb: m68k040.mmu.Tlb): (Int, Int) = {
    def count(b: Int): Int = {
      val bank = tlb.valids(b)
      var n = 0
      for (w <- 0 until bank.length; sIdx <- 0 until bank(w).length)
        if (bank(w)(sIdx).toBoolean) n += 1
      n
    }
    (count(0), count(1))
  }

  test("8k: the DTLB fills all 32 entries, both banks", VerilatorTest) {
    val loadAddr = m68k040.oracle.ProgramAssembler.DefaultLoadAddress
    val DataBase = 0x02000000L      // 256 KB-aligned: 32 x 8 KB is exactly one leaf table
    val PaBase   = 0x00A00000L
    val NPages   = 32

    // 32 stores, one per 8 KB page, walking a0 forward. A loop keeps the image small
    // enough to sit in one code page.
    val src = Seq(
      f"move.l #0x$DataBase%x,%%a0",
      "moveq #0,%d1",
      "loop: move.l %d1,(%a0)",
      "lea 0x2000(%a0),%a0",
      "addq.l #1,%d1",
      f"cmpi.l #$NPages,%%d1",
      "bne loop",
      "stop: bra stop").mkString(" ; ")
    val image = assemble("dtlb-8k", src, loadAddr)

    var b0, b1 = -1
    var lastPageWritten = -1L
    h.compiledDut.doSim(s"dtlb-8k-${System.nanoTime()}") { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      h.attachProgram(dut.icache.logic.axi, cd, loadAddr, image.bytes)
      val dmem = new m68k040.ls.BehavioralMemAgent(dut.dcache.logic.axi, cd)

      // Code: identity, 4 x 8 KB pages, its own leaf table.
      for (i <- 0 until 4)
        map8k(dmem, h.MMU_ROOT, h.MMU_PTRT, h.MMU_PAGT2,
              loadAddr + i * 0x2000L, loadAddr + i * 0x2000L)
      // Data: 32 consecutive 8 KB pages, non-identity, one leaf table.
      for (i <- 0 until NPages)
        map8k(dmem, h.MMU_ROOT, h.MMU_PTRT, h.MMU_PAGT,
              DataBase + i * 0x2000L, PaBase + i * 0x2000L)

      boot(dut, cd, loadAddr, mmuOn = true, page8K = true,
           urp = h.MMU_ROOT, srp = h.MMU_ROOT, cacr = 0x80008000L, sp = 0x00120000L)
      cd.waitSampling(60000)

      val (c0, c1) = census(dut.dtlb.logic.tlb)
      b0 = c0; b1 = c1
      val a = PaBase + (NPages - 1) * 0x2000L
      lastPageWritten = (0 until 4).foldLeft(0L)((acc, i) => (acc << 8) | (dmem.peekByte(a + i) & 0xffL))
    }

    // Not vacuous: the program really did touch all 32 pages.
    assert(lastPageWritten == (NPages - 1).toLong,
      f"VACUOUS: the loop never reached the 32nd page (its physical page reads " +
        f"0x$lastPageWritten%08x, expected 0x${NPages - 1}%08x) -- the census below " +
        "would be measuring a program that stopped early, not the ATC geometry.")
    assert(b1 > 0,
      f"HALF-SIZE ATC: the DTLB filled $b0 entries in bank 0 and NONE in bank 1 after " +
        "32 distinct 8 KB pages. `tlbKey` is masking VPN bit 0 to zero and " +
        "`Tlb.bankOf` IS VPN bit 0, so bank 1 is unreachable.")
    assert(b0 == 16 && b1 == 16,
      f"the DTLB holds $b0 + $b1 = ${b0 + b1} entries after 32 consecutive 8 KB pages; " +
        "expected 16 + 16 = 32 (4 pages land in each of the 8 (bank,set) groups, " +
        "filling every way)")
  }

  test("8k: the ITLB fills all 32 entries, both banks", VerilatorTest) {
    val loadAddr = m68k040.oracle.ProgramAssembler.DefaultLoadAddress   // 256 KB-aligned
    val NPages   = 32
    def pageVa(i: Int): Long = loadAddr + i.toLong * 0x2000L

    // Page 0 is the assembled program; pages 1..31 hold a hand-poked `jmp` chain, so
    // the fetch stream visits 32 distinct 8 KB pages and nothing else. The last page
    // spins on the run-ahead guard opword rather than returning, which keeps the
    // census free of any address arithmetic over the image.
    val src = Seq(
      f"jmp 0x${pageVa(1)}%x").mkString(" ; ")
    val image = assemble("itlb-8k", src, loadAddr)

    var b0, b1 = -1
    var pcPage = -1L
    h.compiledDut.doSim(s"itlb-8k-${System.nanoTime()}") { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)

      val mem = SparseMemory()
      // Fill the whole 32-page window with `bra .` FIRST so a prefetch that runs off
      // the end of any thunk lands on a fence, never on SparseMemory's PRNG fill.
      m68k040.sim.AxiMemModel.fillIFetchRunAheadGuard(mem, loadAddr, (NPages * 0x2000) / 2)
      m68k040.sim.AxiMemModel.loadProgramIFetch(mem, loadAddr, image.bytes)
      for (i <- 1 until NPages - 1) {
        // jmp abs.l -> the next page (0x4EF9 + 32-bit target)
        val tgt = pageVa(i + 1)
        val bytes = Seq(0x4E, 0xF9,
                        ((tgt >> 24) & 0xff).toInt, ((tgt >> 16) & 0xff).toInt,
                        ((tgt >> 8) & 0xff).toInt, (tgt & 0xff).toInt)
        for ((b, k) <- bytes.zipWithIndex) mem.write(pageVa(i) + k, b.toByte)
      }
      // page 31 keeps the `bra .` fence already written there.
      m68k040.sim.AxiMemModel.attachReadOnly(dut.icache.logic.axi, cd, sharedMem = mem)
      val dmem = new m68k040.ls.BehavioralMemAgent(dut.dcache.logic.axi, cd)

      for (i <- 0 until NPages)
        map8k(dmem, h.MMU_ROOT, h.MMU_PTRT, h.MMU_PAGT, pageVa(i), pageVa(i))

      boot(dut, cd, loadAddr, mmuOn = true, page8K = true,
           urp = h.MMU_ROOT, srp = h.MMU_ROOT, cacr = 0x80008000L, sp = 0x00120000L)
      cd.waitSampling(80000)

      val (c0, c1) = census(dut.itlb.logic.tlb)
      b0 = c0; b1 = c1
      pcPage = (dut.rob.logic.debugLivePcReg.toLong & 0xffffffffL) & 0xffffe000L
    }

    // Not vacuous: the chain really did walk to the last page.
    assert(pcPage == pageVa(NPages - 1),
      f"VACUOUS: the jmp chain ended at page 0x$pcPage%08x, not the 32nd page " +
        f"0x${pageVa(NPages - 1)}%08x -- fewer than 32 code pages were fetched, so the " +
        "census below is not measuring what it claims.")
    assert(b1 > 0,
      f"HALF-SIZE ATC: the ITLB filled $b0 entries in bank 0 and NONE in bank 1 after " +
        "fetching from 32 distinct 8 KB pages -- the I-side twin of the DTLB defect.")
    assert(b0 == 16 && b1 == 16,
      f"the ITLB holds $b0 + $b1 = ${b0 + b1} entries after 32 consecutive 8 KB code " +
        "pages; expected 16 + 16 = 32")
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // 5. CACR.IE TURNS THE INSTRUCTION CACHE OFF
  // ═══════════════════════════════════════════════════════════════════════════════
  //
  // Only CACR bit 31 (DE) was ever read. Bit 15 (IE) had NO reader anywhere in the
  // core, so the instruction cache was unconditionally enabled and could not be
  // turned off -- software that clears IE and relies on that instead of an explicit
  // invalidate goes on executing stale instruction bytes. Silent wrong CODE, with no
  // fault anywhere, and a difference from the v1 core that boots this machine.
  //
  // ONE program proves both directions, which is what makes it honest:
  //   * D6 -- the pre-image ran at all (not vacuous);
  //   * D7 -- with IE still SET, the rewritten bytes are NOT seen. This is the
  //     control: it proves the I-cache genuinely held that line, so the third jsr is
  //     testing the enable bit and not a line that had simply been evicted;
  //   * D3 -- with IE CLEARED, the rewritten bytes execute.
  //
  // The D-cache is deliberately OFF (CACR = IE only). With DE = 0 the LS EU stamps
  // every access INHIBITED, so the store that rewrites the routine goes straight to
  // AXI and no CPUSH is needed to make it visible to the fetch side -- which keeps
  // this test about IE alone rather than about cache-flush ordering.
  //
  // `CodeVa` is chosen so its I-cache set (PA[11:6] = 0x08) is not one the main
  // program's own lines occupy, so nothing between the first and third jsr can evict
  // it. Both cache ports are backed by ONE memory.
  private val StaleWord = 0x76114E75L    // moveq #0x11,%d3 ; rts
  private val FreshWord = 0x765A4E75L    // moveq #0x5A,%d3 ; rts

  test("cacr: clearing IE stops the I-cache serving stale instruction bytes", VerilatorTest) {
    val loadAddr = m68k040.oracle.ProgramAssembler.DefaultLoadAddress
    val CodeVa   = 0x00007200L
    val Sp       = 0x00120000L

    val src = Seq(
      f"move.l #0x$Sp%x,%%a7",
      f"move.l #0x$CodeVa%x,%%a0",
      "jsr (%a0)",                              // runs the PRE-IMAGE  -> d3 = 0x11
      "move.l %d3,%d6",                         // witness: the pre-image really ran
      f"move.l #0x$FreshWord%08x,(%%a0)",       // rewrite it (DE=0 -> straight to memory)
      "jsr (%a0)",                              // IE still SET -> must run the STALE line
      "move.l %d3,%d7",                         // witness: the I-cache held it
      "moveq #0,%d0",
      "movec %d0,%cacr",                        // CLEAR IE (DE was already 0)
      "jsr (%a0)",                              // must now run the FRESH bytes
      "stop: bra stop").mkString(" ; ")
    val image = assemble("cacr-ie", src, loadAddr)

    var d3, d6, d7 = -1L
    h.compiledDut.doSim(s"cacr-ie-${System.nanoTime()}") { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)

      val mem = SparseMemory()
      m68k040.sim.AxiMemModel.loadProgramIFetch(mem, loadAddr, image.bytes)
      m68k040.sim.AxiMemModel.fillIFetchRunAheadGuard(
        mem, loadAddr + image.bytes.length, m68k040.sim.AxiMemModel.LockStepRunAheadGuardWords)
      for (i <- 0 until 4) mem.write(CodeVa + i, ((StaleWord >> (8 * (3 - i))) & 0xff).toByte)
      m68k040.sim.AxiMemModel.fillIFetchRunAheadGuard(mem, CodeVa + 4, 1024)

      m68k040.sim.AxiMemModel.attachReadOnly(dut.icache.logic.axi, cd, sharedMem = mem)
      new m68k040.ls.BehavioralMemAgent(dut.dcache.logic.axi, cd, sharedMem = mem)

      // IE only: the instruction cache ON, the data cache OFF.
      boot(dut, cd, loadAddr, mmuOn = false, page8K = false,
           urp = 0L, srp = 0L, cacr = 0x00008000L, sp = Sp)
      cd.waitSampling(30000)

      val probe = new ArchStateProbe(dut.ren, dut.rfInt, dut.rfNzvc, dut.rfX)
      d3 = probe.readArch(3); d6 = probe.readArch(6); d7 = probe.readArch(7)
    }

    assert(d6 == 0x11L,
      f"VACUOUS: the PRE-IMAGE routine at 0x$CodeVa%08x never ran (D6 = 0x$d6%08x, " +
        "expected 0x11) -- the shared-memory attach or the boot sequence is wrong, " +
        "not the cache.")
    assert(d7 == 0x11L,
      f"VACUOUS CONTROL: with CACR.IE still SET the rewritten bytes were already " +
        f"visible (D7 = 0x$d7%08x, expected the stale 0x11). The I-cache did not hold " +
        f"the line across the rewrite -- probably evicted -- so the third jsr would " +
        "prove nothing about the enable bit. Move CodeVa to a different I-cache set.")
    assert(d3 == 0x5AL,
      f"CACR.IE HAS NO EFFECT: after `movec #0,%%cacr` cleared IE, the fetch STILL " +
        f"served the stale cached bytes (D3 = 0x$d3%08x, expected 0x5A). The " +
        "instruction cache is unconditionally enabled -- CACR bit 15 has no reader.")
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // 6. EXCEPTIONS MUST STILL WORK WHEN TRANSLATION IS NOT PAGED
  // ═══════════════════════════════════════════════════════════════════════════════
  //
  // 2026-09-09 REGRESSION HUNT. The preceding slice made the exception sequencer
  // translate its frame pushes, RTE frame pops and vector fetches through the DTLB
  // instead of assuming PA = VA (ExceptionUnit's `X_REQ`/`X_WAIT`). That is right for
  // a paged machine -- but the Quadra runs its ENTIRE early boot with TC.E = 0, and
  // it takes exceptions the whole way. If the translation port fails to answer, or
  // answers `fault`, when paging is off, then every early-boot exception breaks in a
  // way it did not before: `X_WAIT` waits forever, or the entry becomes the
  // `E_DBLFAULT` processor halt.
  //
  // The board evidence that prompted these: a build carrying that slice sat in a ROM
  // diagnostic loop with SysError 99, in 24-BIT addressing mode -- the configuration
  // the previous build booted 8/8. A failure in the WORKING configuration is worse
  // than an unfixed failure in the broken one.
  //
  // There are exactly three ways an address resolves on a 68040, and the exception
  // path has to survive all three. These test them in order:
  //   (a) paging DISABLED  -- DtlbPlugin's `!mmuEnable` identity arm;
  //   (b) paging turned ON MID-STREAM -- both arms in one instruction stream, which
  //       also pins the sequencer's one-entry translation cache (`excVpnValid` /
  //       `excLastVpn`) being invalidated across the TC write;
  //   (c) a TRANSPARENT TRANSLATION REGISTER -- resolved with no table walk at all,
  //       the arm that runs before the tables even exist.

  test("exc: an exception with the MMU DISABLED still frames, vectors and returns", VerilatorTest) {
    val loadAddr = m68k040.oracle.ProgramAssembler.DefaultLoadAddress
    val SysVa    = 0x00300000L          // MMU off -> PA = VA
    val StackVa  = SysVa + 0xF00
    val FrameVa  = StackVa - 8
    val VecSlot  = SysVa + 10 * 4

    val src = Seq(
      f"move.l #0x$SysVa%x,%%d0", "movec %d0,%vbr",
      f"move.l #0x$StackVa%x,%%a7",
      "move.l #handler,%d1",
      f"move.l %%d1,0x$VecSlot%x",
      "moveq #0,%d3", "moveq #0,%d5",
      ".short 0xa9c9",
      "moveq #11,%d5",
      "bra stop",
      "handler: moveq #33,%d3", "rte",
      "stop: bra stop").mkString(" ; ")
    val image = assemble("exc-mmu-off", src, loadAddr)

    var d3, d5 = -1L
    var fmtWord = -1
    h.compiledDut.doSim(s"exc-mmu-off-${System.nanoTime()}") { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      h.attachProgram(dut.icache.logic.axi, cd, loadAddr, image.bytes)
      val dmem = new m68k040.ls.BehavioralMemAgent(dut.dcache.logic.axi, cd)
      // No page tables at all -- there is nothing to walk and nothing that should try.
      boot(dut, cd, loadAddr, mmuOn = false, page8K = false,
           urp = 0L, srp = 0L, cacr = 0x80008000L, sp = 0x00120000L)
      cd.waitSampling(40000)
      val probe = new ArchStateProbe(dut.ren, dut.rfInt, dut.rfNzvc, dut.rfX)
      d3 = probe.readArch(3); d5 = probe.readArch(5)
      fmtWord = (dmem.peekByte(FrameVa + 6) << 8) | dmem.peekByte(FrameVa + 7)
    }

    assert(d3 == 33L,
      f"MMU-OFF EXCEPTION BROKEN: the handler never ran (D3 = 0x$d3%08x, expected 33). " +
        "With TC.E = 0 the exception sequencer's DTLB request must be answered by the " +
        "identity arm; if it is never answered X_WAIT hangs, and if it answers `fault` " +
        "the entry becomes an E_DBLFAULT halt. This is the whole of early boot.")
    assert(fmtWord == 0x0028,
      f"MMU-OFF FRAME PUSH went to the wrong address: the format/vector word at " +
        f"0x${FrameVa + 6}%08x reads 0x$fmtWord%04x, expected 0x0028 (vector 10)")
    assert(d5 == 11L,
      f"MMU-OFF RTE did not resume after the trap (D5 = 0x$d5%08x, expected 11)")
  }

  test("exc: exceptions either side of the MOVEC that ENABLES paging", VerilatorTest) {
    val loadAddr  = m68k040.oracle.ProgramAssembler.DefaultLoadAddress
    val OffSysVa  = 0x00300000L         // used while TC.E = 0, so PA = VA
    val OffStack  = OffSysVa + 0xF00
    val OnSysVa   = 0x01FF6000L         // used after TC.E = 1, mapped NON-identity...
    val OnSysPa   = 0x00700000L         // ...to here
    val OnStack   = OnSysVa + 0xF00
    val OnFramePa = (OnSysPa + 0xF00) - 8
    val OnFrameVa = OnStack - 8         // where an UNtranslated push would land

    // Both vector tables are written while paging is still off, so the second one is
    // written at its PHYSICAL address -- exactly how firmware stages a table it will
    // only start using once it turns the MMU on.
    val src = Seq(
      f"move.l #0x$OffSysVa%x,%%d0", "movec %d0,%vbr",
      f"move.l #0x$OffStack%x,%%a7",
      "move.l #h1,%d1", f"move.l %%d1,0x${OffSysVa + 40}%x",
      "move.l #h2,%d1", f"move.l %%d1,0x${OnSysPa + 40}%x",
      "moveq #0,%d3", "moveq #0,%d4", "moveq #0,%d5", "moveq #0,%d6",
      ".short 0xa9c9",                                   // trap 1: paging OFF
      "moveq #11,%d5",
      "move.l #0x8000,%d0", "movec %d0,%tc",             // TC.E = 1, 4 KB pages
      f"move.l #0x$OnSysVa%x,%%d0", "movec %d0,%vbr",
      f"move.l #0x$OnStack%x,%%a7",
      ".short 0xa9c9",                                   // trap 2: paging ON
      "moveq #22,%d6",
      "bra stop",
      "h1: moveq #33,%d3", "rte",
      "h2: moveq #44,%d4", "rte",
      "stop: bra stop").mkString(" ; ")
    val image = assemble("exc-mmu-enable", src, loadAddr)

    var d3, d4, d5, d6 = -1L
    var fmtOn = -1
    var vaGuardIntact = true
    h.compiledDut.doSim(s"exc-mmu-enable-${System.nanoTime()}") { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      h.attachProgram(dut.icache.logic.axi, cd, loadAddr, image.bytes)
      val dmem = new m68k040.ls.BehavioralMemAgent(dut.dcache.logic.axi, cd)
      // Code identity-mapped (the I side translates too once TC.E is set) + the
      // post-enable system page mapped NON-identity.
      h.buildMmuTable(dmem, OnSysVa, OnSysPa >> 12)
      // Also map the pre-enable region identity, so nothing that lingers there faults.
      // MMU_PAGT, not MMU_PAGT2: `buildMmuTable` fills MMU_PAGT2 entries 0..7 with the
      // eight identity-mapped CODE pages, and 0x00300000's page index is 0 -- the same
      // slot 0x40800000 occupies. Writing it there would silently re-point the first
      // code page at 0x00300000. MMU_PAGT's only other occupant is OnSysVa at index
      // 0x36, so index 0 is free.
      h.mapPage(dmem, OffSysVa, OffSysVa >> 12, h.MMU_PAGT)
      for (i <- 0 until 8) dmem.pokeByte(OnFrameVa + i, 0xEE)   // untranslated destination

      boot(dut, cd, loadAddr, mmuOn = false, page8K = false,
           urp = h.MMU_ROOT, srp = h.MMU_ROOT, cacr = 0x80008000L, sp = 0x00120000L)
      cd.waitSampling(60000)
      val probe = new ArchStateProbe(dut.ren, dut.rfInt, dut.rfNzvc, dut.rfX)
      d3 = probe.readArch(3); d4 = probe.readArch(4)
      d5 = probe.readArch(5); d6 = probe.readArch(6)
      fmtOn = (dmem.peekByte(OnFramePa + 6) << 8) | dmem.peekByte(OnFramePa + 7)
      vaGuardIntact = (0 until 8).forall(i => dmem.peekByte(OnFrameVa + i) == 0xEE)
    }

    assert(d3 == 33L && d5 == 11L,
      f"the PAGING-OFF exception broke (D3 = 0x$d3%08x expected 33, D5 = 0x$d5%08x " +
        "expected 11) -- see the MMU-DISABLED test above")
    assert(d4 == 44L,
      f"the PAGING-ON exception never reached its handler (D4 = 0x$d4%08x, expected 44). " +
        "The sequencer caches ONE translation (`excVpnValid`/`excLastVpn`); if that " +
        "cache is not invalidated when TC changes, the second entry reuses the first " +
        "episode's PPN -- which was an identity one taken while paging was off.")
    assert(fmtOn == 0x0028,
      f"the PAGING-ON frame is not at its TRANSLATED address 0x${OnFramePa + 6}%08x " +
        f"(reads 0x$fmtOn%04x, expected 0x0028)")
    assert(vaGuardIntact,
      f"the PAGING-ON frame landed at the UNtranslated address 0x$OnFrameVa%08x -- " +
        "the push used PA = VA even though paging was on")
    assert(d6 == 22L,
      f"the PAGING-ON RTE did not resume after the trap (D6 = 0x$d6%08x, expected 22)")
  }

  test("exc: an exception whose stack and vectors resolve only via DTT0", VerilatorTest) {
    val loadAddr = m68k040.oracle.ProgramAssembler.DefaultLoadAddress
    val SysVa    = 0x01FF6000L        // top byte 0x01 -- covered by DTT0 below...
    val StackVa  = SysVa + 0xF00      // ...and DELIBERATELY absent from the page tables
    val FrameVa  = StackVa - 8
    val VecSlot  = SysVa + 10 * 4
    // base = 0x01, mask = 0x00 (exact top-byte match), E = bit15, S = bit14 (either
    // privilege), CM = 00 (cacheable writethrough). Same shape as the board's own
    // DTT0 = 0xF900C060, which is how the ROM marks MMIO before paging exists.
    val Dtt0     = 0x0100C000L

    val src = Seq(
      f"move.l #0x$SysVa%x,%%d0", "movec %d0,%vbr",
      f"move.l #0x$StackVa%x,%%a7",
      "move.l #handler,%d1",
      f"move.l %%d1,0x$VecSlot%x",
      "moveq #0,%d3", "moveq #0,%d5",
      ".short 0xa9c9",
      "moveq #11,%d5",
      "bra stop",
      "handler: moveq #33,%d3", "rte",
      "stop: bra stop").mkString(" ; ")
    val image = assemble("exc-ttr", src, loadAddr)

    var d3, d5 = -1L
    var fmtWord = -1
    h.compiledDut.doSim(s"exc-ttr-${System.nanoTime()}") { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      h.attachProgram(dut.icache.logic.axi, cd, loadAddr, image.bytes)
      val dmem = new m68k040.ls.BehavioralMemAgent(dut.dcache.logic.axi, cd)
      // Code identity-mapped; the data page argument is a THROWAWAY far from SysVa,
      // so the vector table and stack have NO page-table entry of any kind. Only the
      // transparent-translation register can resolve them -- if the exception path
      // ignored TTRs the walk would fault NON_RESIDENT and halt on a double fault.
      h.buildMmuTable(dmem, 0x00300000L, 0x00300L)

      boot(dut, cd, loadAddr, mmuOn = true, page8K = false,
           urp = h.MMU_ROOT, srp = h.MMU_ROOT, cacr = 0x80008000L, sp = 0x00120000L,
           dtt0 = Dtt0)
      cd.waitSampling(40000)
      val probe = new ArchStateProbe(dut.ren, dut.rfInt, dut.rfNzvc, dut.rfX)
      d3 = probe.readArch(3); d5 = probe.readArch(5)
      fmtWord = (dmem.peekByte(FrameVa + 6) << 8) | dmem.peekByte(FrameVa + 7)
    }

    assert(d3 == 33L,
      f"TTR-COVERED EXCEPTION BROKEN: the handler never ran (D3 = 0x$d3%08x, expected " +
        "33). The stack and vector table have no page-table entry at all, so DTT0 is " +
        "the only thing that can resolve them -- this is the arm that runs before the " +
        "tables exist.")
    assert(fmtWord == 0x0028,
      f"TTR-COVERED FRAME PUSH went to the wrong address: the format/vector word at " +
        f"0x${FrameVa + 6}%08x reads 0x$fmtWord%04x, expected 0x0028")
    assert(d5 == 11L, f"TTR-COVERED RTE did not resume (D5 = 0x$d5%08x, expected 11)")
  }
}
