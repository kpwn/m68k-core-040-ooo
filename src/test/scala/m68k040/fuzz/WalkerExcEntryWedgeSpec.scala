package m68k040.fuzz

import m68k040.VerilatorTest
import m68k040.oracle.ProgramAssembler
import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._

/** A directed 68040 program that builds GENUINE 3-level page tables with ordinary
  * store instructions and then runs exceptions with both TLBs deliberately cold.
  *
  * THE POSTURE, AND WHY IT IS NEW.  Every pre-existing regression posture is
  * structurally incapable of exercising the table walker at all:
  *
  *  - `CachePosture.AsWritten` leaves `CACR = 0` / `TC.E = 0`, so both TLBs are identity
  *    pass-throughs and no walk is ever started.
  *  - `CachePosture.ForceCacheableCopyback` maps the whole low 2 GiB through ITT0/DTT0
  *    and the high 2 GiB through DTT1.  A TTR hit resolves BEFORE the TLB, so again no
  *    walk ever happens (recorded verbatim in
  *    `docs/superpowers/specs/2026-09-04-walker-dcache-passthrough-implementation-report.md`
  *    §1, trap 2).
  *  - `WalkerDescriptorCoherencySpec` does walk, but on a 4-plugin cluster DUT with no
  *    ROB, no `ExceptionUnit` and no frontend -- it cannot take an exception at all.
  *
  * So `2db5bd3` ("route MMU table walks through L1D"), whose boot wedge at `0x40806b68`
  * cost three ~1.5 h board cycles, had no simulation reachable from any existing suite.
  *
  * What this posture does differently:
  *
  *  - `ITT0 = ITT1 = 0`.  The INSTRUCTION side has NO transparent translation at all, so
  *    every fetch to a cold ITLB entry runs a real 3-level ITLB walk.  Handlers sit on
  *    their own 4 KiB code pages so an exception REDIRECT always walks.
  *  - `DTT0 = 0x0000E020` -- base 0x00 / mask 0x00, i.e. exact match on `VA[31:24] == 0`,
  *    E=1, S=11, CM=01 COPYBACK.  Covers ONLY the 16 MiB block holding the page tables,
  *    the vector table and the stack; deliberately NOT the data working set.
  *  - `DTT1 = 0xFF00E060` -- the sentinel block, inhibited, so the harness sees
  *    completion over AXI.
  *  - The data working set is at `0x60000000` (the sim AXI model only decodes top nibbles
  *    0/4/5/6, so a `0x2...` working set would DECERR instead of walking), covered by NO TTR, so every access to it
  *    runs a real DTLB walk.  Leaf descriptors are written U=0/M=0, so each first touch
  *    also issues a walker U/M writeback STORE through the D-cache.
  *  - `CACR = 0x80008000` (DE|IE), `TC = 0x00008000` (E=1, 4 KiB pages).
  *
  * `PFLUSHA` in the loop and in both handlers keeps the TLBs cold, so walks are in
  * flight across `E_DRAIN`..`E_REDIR` (exception entry) and `R_DRAIN`..`R_REDIR` (RTE) --
  * the window the A-line boot wedge sits in.
  */
object TableStressProgram {

  val Root = 0x00200000L   // 128-entry root table
  val PtrC = 0x00201000L   // root[48] -> VA 0x60000000..0x61FFFFFF
  val PgtD = 0x00202000L   // PtrC[0]  -> VA 0x60000000..0x6003FFFF  (data working set)
  val PtrB = 0x00203000L   // root[32] -> VA 0x40000000..0x41FFFFFF
  val PgtC = 0x00204000L   // PtrB[32] -> VA 0x40800000..0x4083FFFF  (code)
  val DataVa = 0x60000000L

  /** @param iterations      how many (A-line + TRAP) rounds to run
    * @param pflushInHandler whether the handlers also flush, i.e. whether an ITLB walk
    *                        is forced on the RTE-side redirect as well as the entry one
    * @param dirtyTables     install DTT0 + `CACR.DE` BEFORE building the page tables, so
    *                        the tables sit DIRTY in the copyback L1D while the walker
    *                        reads them.  This is the state real boot code leaves them in
    *                        and the exact coherency case `2db5bd3` exists to fix, so it
    *                        exercises the new walker->L1D path far harder than building
    *                        the tables with the cache off.
    * @param cpush           emit `cpusha %bc` in the loop.  This is the ONLY thing that
    *                        makes `quiesceHold` actually assert (S_DRAIN / S_APPLY /
    *                        S_MAINTWAIT), i.e. the mechanism under investigation.
    * @param irqs            unmask interrupts and install an autovector-30 handler on
    *                        its own code page, so the harness can inject ASYNCHRONOUS
    *                        exception entries that land at arbitrary points inside a
    *                        walk -- unlike A-line/TRAP, which can only fire at commit.
    * @param sledPages       size, in 4 KiB pages, of a RUNAWAY SLED planted immediately
    *                        after each trapping instruction -- the structural ingredient
    *                        a small directed program cannot otherwise have.  `030651f`'s
    *                        claimed mechanism is that during `E_DRAIN` the frontend is
    *                        NOT quiesced and runs away down the wrong path; every page it
    *                        runs into is an ITLB miss, and after `2db5bd3` every ITLB
    *                        miss is a table walk that re-arms exactly the terms
    *                        `dcQuiesced` waits on.  A one-page program's runaway never
    *                        leaves its page, so it cannot exhibit that at all. */
  def src(iterations: Int, pflushInHandler: Boolean = true,
          dirtyTables: Boolean = false, cpush: Boolean = false,
          irqs: Boolean = false, sledPages: Int = 0,
          leafWritethrough: Boolean = false,
          storeBurst: Int = 0, storeGroups: Int = 24,
          noWalk: Boolean = false): String = {
    require(sledPages == 0 || sledPages >= 2, "a sled needs at least two pages to hop")
    require(storeGroups < 60, "the data leaf table only maps 64 pages")
    // Leaf CM field (descriptor bits [6:5]). WRITETHROUGH stores are the ones the
    // D-cache pipelines and counts in `storeOutstanding` (COPYBACK is stage-credit
    // based and never gates on it), so a writethrough working set is what actually
    // drives `LsEuPlugin.coreStOutstanding` towards its 3-bit wrap point.
    val leafCmHex = if (leafWritethrough) "01" else "21"
    // STORE-PORT STRESS. Each group first saturates the D-cache store pipeline with
    // `storeBurst` writethrough stores on distinct 64 B lines INSIDE an already-mapped
    // page (so none of them waits on a walk), and then touches a FRESH page, whose
    // DTLB miss starts a walk whose U/M writeback needs the very store port those
    // stores are occupying. That is the drain-to-zero hand-over `stGrantOk` guards
    // with `coreStOutstanding === 0`.
    val storeStress =
      if (storeBurst == 0) ""
      else {
        val sb = new StringBuilder
        sb ++= "    | ---- store-port stress: saturate the store pipe, then walk ----\n"
        sb ++= "    move.l  #DATA_VA, %a2\n"
        for (_ <- 0 until storeGroups) {
          for (b <- 0 until storeBurst) sb ++= s"    move.l  %d7, ${b * 0x40}(%a2)\n"
          sb ++= "    lea     0x1000(%a2), %a2\n"
          sb ++= "    move.l  (%a2), %d0\n"
        }
        sb.toString
      }
    // Re-arm U=0/M=0 on every data leaf each iteration, so EVERY pass produces a fresh
    // walker U/M writeback store instead of only the very first one (without this the
    // whole run contains ~11 walker stores and the store direction is barely tested).
    val reArmUm =
      if (storeBurst == 0) ""
      else s"""    lea     PGTD, %a0
    move.l  #(DATA_VA+0x$leafCmHex), %d1
    bsr     _fill64
"""

    // The runaway sled: `sledPages` hops, one 4 KiB page apart, looping forever. Sized
    // ABOVE the 32-entry ITLB so every hop misses on every pass and every miss is a
    // real table walk. Never architecturally executed -- both handlers rewrite the
    // stacked PC past the `bra` that enters it.
    val sled =
      if (sledPages == 0) ""
      else (0 until sledPages).map { i =>
        val j = (i + 1) % sledPages
        // The wrap-around hop is ~192 KB backwards, past `braw`'s +-32 KB range, so it
        // has to be the 32-bit-displacement form explicitly.
        val op = if (j < i) "bral" else "bra "
        s"    .balign 4096\n_rw$i:\n    $op    _rw$j\n"
      }.mkString("\n    | ---- runaway sled ----\n", "", "")
    val sledEntry = if (sledPages == 0) "" else "    bra     _rw0\n"
    val handlerFlush = if (pflushInHandler) "    pflusha\n" else ""
    val loopCpush    = if (cpush) "    cpusha  %bc\n" else ""
    // The `noWalk` CONTROL. Byte-identical instruction stream, byte-identical memory
    // model, same TC.E=1 -- but the TTRs cover everything, and a TTR hit resolves
    // BEFORE the TLB, so not one table walk happens. Any behaviour that survives this
    // substitution is not the walker's.
    val dtt0Val = if (noWalk) "0x007FE000" else "0x0000E020"
    val itt0Val = if (noWalk) "0x4000C000" else "0x00000000"
    val ttrBlock =
      s"""    move.l  #$dtt0Val, %d0
    movec   %d0, %dtt0
    move.l  #0xFF00E060, %d0               | DTT1: 0xFF......, inhibited (sentinel)
    movec   %d0, %dtt1
    move.l  #$itt0Val, %d0
    movec   %d0, %itt0
    moveq   #0, %d0
    movec   %d0, %itt1
    move.l  #0x80008000, %d0               | CACR: DE | IE
    movec   %d0, %cacr
"""
    val ttrEarly = if (dirtyTables) ttrBlock else ""
    val ttrLate  = if (dirtyTables) "" else ttrBlock
    val irqVec   = if (irqs) "    move.l  #_irq_handler, VBR_BASE+120  | autovector 30 (level 6)\n" else ""
    val irqUnmask= if (irqs) "    move.w  #0x2000, %sr               | unmask interrupts\n" else ""
    val irqHandler = if (irqs)
      """
    .balign 4096
_irq_handler:
    rte
""" else ""
    s"""
    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000
    .equ VBR_BASE,  0x00100000
    .equ ROOT,      0x00200000
    .equ PTRC,      0x00201000
    .equ PGTD,      0x00202000
    .equ PTRB,      0x00203000
    .equ PGTC,      0x00204000
    .equ DATA_VA,   0x60000000

_start:
    lea     0x00030000, %a7
$ttrEarly
    | ---- zero ROOT / PTRC / PTRB (128 longwords each). The D-side sim memory is
    | ---- 0xFF-filled, and 0xFFFFFFFF decodes as a RESIDENT table descriptor
    | ---- pointing at garbage, so an unzeroed table is not merely untidy.
    lea     ROOT, %a0
    bsr     _z128
    lea     PTRC, %a0
    bsr     _z128
    lea     PTRB, %a0
    bsr     _z128

    | ---- level-1 / level-2 links (UDT = 10 resident, base is bits [31:4]) ----
    move.l  #(PTRC+2), ROOT+192         | root[48] -> PTRC   (VA 0x60000000)
    move.l  #(PTRB+2), ROOT+128         | root[32] -> PTRB   (VA 0x40000000)
    move.l  #(PGTD+2), PTRC             | PTRC[0]  -> PGTD   (VA 0x60000000)
    move.l  #(PGTC+2), PTRB+128         | PTRB[32] -> PGTC   (VA 0x40800000)

    | ---- leaf tables: 64 entries, PDT=01 resident, CM=01 copyback, U=0 M=0 so the
    | ---- FIRST touch of every page also runs a walker U/M writeback store.
    lea     PGTD, %a0
    move.l  #(DATA_VA+0x$leafCmHex), %d1
    bsr     _fill64
    lea     PGTC, %a0
    move.l  #0x40800021, %d1
    bsr     _fill64

    | ---- vector table ----
    move.l  #VBR_BASE, %d0
    movec   %d0, %vbr
    lea     VBR_BASE, %a1
    move.l  #_panic, %d0
    move.l  #64, %d2
_vt:
    move.l  %d0, (%a1)+
    subq.l  #1, %d2
    bne     _vt
    move.l  #_aline_handler, VBR_BASE+40    | vec 10  A-line
    move.l  #_trap0_handler, VBR_BASE+128   | vec 32  TRAP #0
$irqVec
    | ---- restricted TTR posture: the INSTRUCTION side gets no TTR at all ----
$ttrLate    move.l  #ROOT, %d0
    movec   %d0, %urp
    movec   %d0, %srp
    move.l  #0x00008000, %d0               | TC: E=1, 4 KiB pages
    movec   %d0, %tc
$irqUnmask
    | ==== from here every fetch may run an ITLB walk and every DATA_VA access a
    | ==== DTLB walk. Nothing below is covered by a transparent translation.

    move.l  #$iterations, %d7
_loop:
    pflusha
$loopCpush$reArmUm$storeStress    move.l  #DATA_VA, %a2
    move.l  (%a2), %d0                     | DTLB walk + U writeback
    lea     0x1000(%a2), %a2
    move.l  %d0, (%a2)                     | DTLB walk + U/M writeback (store)
    lea     0x1000(%a2), %a2
    move.l  (%a2), %d1
    lea     0x1000(%a2), %a2
    move.l  %d1, (%a2)
_aline_site:
    .short  0xa05d                         | A-line trap with walks in flight
${sledEntry}_after_aline:
    lea     0x1000(%a2), %a2
    move.l  (%a2), %d2
    pflusha
    lea     0x1000(%a2), %a2
    move.l  %d2, (%a2)
    trap    #0
${sledEntry}_after_trap:
    subq.l  #1, %d7
    bne     _loop

_pass:
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, PASS_SENT
_hp:
    bra     _hp

_panic:
    move.l  #0xDEAD9999, %d7
_fail:
    move.l  %d7, PASS_SENT
_hf:
    bra     _hf

    .align 2
_z128:
    move.l  #128, %d0
_z128l:
    move.l  #0, (%a0)+
    subq.l  #1, %d0
    bne     _z128l
    rts

    .align 2
_fill64:
    move.l  #64, %d0
_f64l:
    move.l  %d1, (%a0)+
    add.l   #0x1000, %d1
    subq.l  #1, %d0
    bne     _f64l
    rts

    | ---- handlers live on their OWN 4 KiB code pages, so every exception redirect
    | ---- takes a cold ITLB miss and runs a real walk (this is the boot shape:
    | ---- A-line -> dispatch on a different page).
    .balign 4096
_aline_handler:
    move.l  #_after_aline, 2(%a7)          | resume past the A-line word
$handlerFlush    move.l  0x60010000, %d3                | DTLB walk inside the handler
    move.l  %d3, 0x60011000                | walker U/M store inside the handler
    rte

    .balign 4096
_trap0_handler:
    move.l  #_after_trap, 2(%a7)
$handlerFlush    move.l  0x60012000, %d4
    rte
$irqHandler$sled"""
  }
}

/** Per-run observation of the walker/exception interaction. */
final case class WalkExcResult(
  sentinel: Long,
  cycles: Long,
  retired: Long,
  lastCommitPc: Long,
  itlbWalkCmds: Long,
  dtlbWalkCmds: Long,
  walkStores: Long,
  excEntries: Long,
  excWithWalkGrant: Long,
  stalledCycles: Long,
  sledWalks: Long,
  maxEDrainRun: Long,
  itlbRspFault: Long,
  dtlbRspFault: Long,
  dcDiag: String,
  badAr: Long,
  badAw: Long,
  badArLog: String,
  multiHit: Long,
  maxDcStore: Int,
  maxLsStore: Int,
  cyclesAtEight: Long,
  wedgeDump: String
) {
  def report(): Unit = {
    println(f"[walkexc] sentinel=0x$sentinel%08x cycles=$cycles retired=$retired " +
      f"lastPc=0x$lastCommitPc%08x")
    println(f"[walkexc] itlbWalkCmds=$itlbWalkCmds dtlbWalkCmds=$dtlbWalkCmds " +
      f"walkStores=$walkStores excEntries=$excEntries excWithWalkGrant=$excWithWalkGrant")
    println(f"[walkexc] sledWalks=$sledWalks maxEDrainRun=$maxEDrainRun " +
      f"itlbRspFault=$itlbRspFault dtlbRspFault=$dtlbRspFault dcDiag=$dcDiag")
    println(f"[walkexc] MISTRANSLATED AXI: badAr=$badAr badAw=$badAw multiHit=$multiHit")
    println(f"[walkexc] storeOutstanding: dcMax=$maxDcStore lsMirrorMax=$maxLsStore " +
      f"cyclesAt8=$cyclesAtEight  (the 3-bit mirror wraps at 8)")
    if (badArLog.nonEmpty) print(badArLog)
    if (wedgeDump.nonEmpty) println(wedgeDump)
  }
}

object WalkExcHarness {
  val SentinelAddr: Long = 0xFFFF0000L
  val PassWord: Long = 0xC0FFEE00L
  private var runIdx = 0

  /** How many consecutive cycles with NO retirement counts as a wedge. Generous: a
    * cold-TLB walk chain plus an AXI refill is a few hundred cycles at worst. */
  val StallLimit = 20000L

  /** @param irqPeriod when > 0, inject a level-6 autovector interrupt roughly every
    *        `irqPeriod` cycles (jittered by `simSeed`) once the program has switched the
    *        MMU on.  A synchronous trap can only fire at commit; an interrupt can land
    *        at an ARBITRARY point inside a walk, which is the interleaving a directed
    *        program cannot reach on its own. */
  /** @param memCfg AXI memory-model configuration for BOTH the I and D sides.  The
    *        default is the zero-latency model every other harness uses, which makes a
    *        3-level walk finish almost immediately and therefore almost never leaves a
    *        walk in flight when anything else happens.  `AxiMemModel.l2DramSlow` /
    *        `crossbarSingleOutstanding` reproduce the real SoC's memory behaviour, which
    *        widens that window by an order of magnitude. */
  def run(src: String, timeoutCycles: Long = 400000L, simSeed: Int = 1,
          trace: Boolean = false, irqPeriod: Int = 0,
          memCfg: m68k040.sim.AxiMemModelConfig = m68k040.sim.AxiMemModelConfig()): WalkExcResult = {
    val image = ProgramAssembler.assemble(src, PortedTestRunner.loadAddr) match {
      case Right(i)  => i
      case Left(err) => throw new AssertionError(s"assemble failed: ${err.reason}")
    }
    runIdx += 1
    var out: WalkExcResult = null
    PortedTestRunner.compiled.doSim(s"walkexc_$runIdx", simSeed) { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      val loadAddr = PortedTestRunner.loadAddr

      // Memory wiring is byte-identical to PortedTestRunner's (same I-side swap
      // convention, same 0xFF-filled shared D-side image, same SMC write mirror).
      val iAgent = m68k040.sim.AxiMemModel.attachProgramIFetch(
        dut.icache.logic.axi, cd, loadAddr, image.bytes,
        cfg = memCfg.copy(injectBusErrors = true))
      val dsideMem = new m68k040.sim.ConstFillSparseMemory(0xff.toByte)
      val dmem = new m68k040.ls.BehavioralMemAgent(dut.dcache.logic.axi, cd,
                                                   sharedMem = dsideMem, injectBusErrors = true,
                                                   dcfg = memCfg)
      dmem.setByteWriteObserver((addr, byte) => iAgent.mem.write(addr, byte))
      for (i <- image.bytes.indices) dmem.mem.write(loadAddr + i, image.bytes(i).toByte)
      for (i <- 0 until 4) dmem.mem.write(SentinelAddr + i, 0.toByte)

      dut.ctrl.logic.mmuEnable #= false
      dut.ctrl.logic.urp #= 0
      dut.ctrl.logic.srp #= 0
      dut.intCtrl.logic.iplIn #= 0
      dut.intCtrl.logic.iackAvec #= false
      dut.intCtrl.logic.iackVector #= 0
      dut.fa.logic.redirect.valid #= false
      dut.fa.logic.resume.valid #= false
      dut.rob.logic.flush.valid #= false
      dut.icache.logic.invalidateAll #= false
      dut.wire.logic.seedValid #= false; dut.wire.logic.seedAddr #= 0; dut.wire.logic.seedData #= 0
      cd.waitSampling(2)
      dut.icache.logic.invalidateAll #= true
      cd.waitSampling(); dut.icache.logic.invalidateAll #= false
      cd.waitSampling(80)

      dut.rob.logic.exc.ss.isp #= 0x00030000L
      dut.rob.logic.exc.ss.usp #= 0L
      dut.wire.logic.seedValid #= true
      dut.wire.logic.seedAddr #= 15
      dut.wire.logic.seedData #= BigInt(0x00030000L)
      cd.waitSampling(2)
      dut.wire.logic.seedValid #= false
      cd.waitSampling()

      dut.fa.logic.redirect.valid #= true
      dut.fa.logic.redirect.payload #= loadAddr
      cd.waitSampling()
      dut.fa.logic.redirect.valid #= false

      // ── observation ────────────────────────────────────────────────────────────
      val exc = dut.rob.logic.exc
      val ls  = dut.lsEu.logic
      var itlbWalkCmds = 0L
      var dtlbWalkCmds = 0L
      var walkStores   = 0L
      var excEntries   = 0L
      var excWithWalkGrant = 0L
      var retired = 0L
      var lastPc = 0L
      var prevExcActive = false
      var tracePend = 0
      val ring = scala.collection.mutable.ArrayBuffer.empty[String]
      var prevXrspValid = false
      var multiHit = 0L
      var maxDcStoreOutstanding = 0
      var maxLsStoreOutstanding = 0
      var dcStoreOutstandingAt8 = 0L
      var badAr = 0L
      var badAw = 0L
      var badArLog = ""
      var itlbRspFault = 0L
      var dtlbRspFault = 0L
      var firstFaultCyc = 0L
      var sledWalks = 0L
      var eDrainRun = 0L
      var maxEDrainRun = 0L
      var irqHold = 0
      var irqsInjected = 0L
      var nextIrqCyc = 0L
      val rnd = new scala.util.Random(simSeed)
      var cyc = 0L
      var lastRetireCyc = 0L

      cd.onSamplings {
        cyc += 1
        if (dut.itlb.walkLoadCmd.valid.toBoolean && dut.itlb.walkLoadCmd.ready.toBoolean) {
          itlbWalkCmds += 1
          // A LEAF descriptor read for a code page with pageIdx >= 4 can only come from
          // the runaway sled (page 0 is the program, 1..3 are the handlers). This is the
          // direct measurement of whether the frontend really "runs away down the wrong
          // path" during E_DRAIN, which is 030651f's central claim.
          val pa = dut.itlb.walkLoadCmd.payload.paddr.toLong & 0xffffffffL
          if (pa >= (TableStressProgram.PgtC + 16) && pa < (TableStressProgram.PgtC + 256))
            sledWalks += 1
        }
        if (dut.dtlb.walkLoadCmd.valid.toBoolean && dut.dtlb.walkLoadCmd.ready.toBoolean) dtlbWalkCmds += 1
        if ((dut.itlb.walkStore.valid.toBoolean && dut.itlb.walkStore.ready.toBoolean) ||
            (dut.dtlb.walkStore.valid.toBoolean && dut.dtlb.walkStore.ready.toBoolean)) walkStores += 1
        // A descriptor read that came back FAULTED. After 2db5bd3 this terminates the
        // walk and is reported as NON_RESIDENT, i.e. it becomes an architectural access
        // fault. The pre-existing dedicated walker AXI port never checked `rresp` at all,
        // so this is a NEW way for a walk to fail.
        if (dut.itlb.walkLoadRsp.valid.toBoolean && dut.itlb.walkLoadRsp.payload.fault.toBoolean) {
          itlbRspFault += 1
          if (firstFaultCyc == 0L) firstFaultCyc = cyc
        }
        if (dut.dtlb.walkLoadRsp.valid.toBoolean && dut.dtlb.walkLoadRsp.payload.fault.toBoolean) {
          dtlbRspFault += 1
          if (firstFaultCyc == 0L) firstFaultCyc = cyc
          if (trace && dtlbRspFault <= 4)
            println(f"[walkexc] cyc=$cyc%7d DTLB DESCRIPTOR READ FAULTED, descAddr=0x" +
              f"${dut.dtlb.walkLoadCmd.payload.paddr.toLong}%08x " +
              f"dcDiagFault=${dut.dcache.logic.diagFaultValid.toBoolean} " +
              f"dcDiagAddr=0x${dut.dcache.logic.diagFaultAddr.toLong}%08x " +
              f"dcDiagKind=${dut.dcache.logic.diagFaultKind.toInt} " +
              f"ldOwner=${dut.lsEu.logic.ldOwner.toInt} " +
              f"quiesceHold=${exc.quiesceHoldOut.toBoolean} " +
              f"maintWalking=${dut.dcache.logic.maintWalkingDbg.toBoolean}")
        }
        // `LsEuPlugin.coreStOutstanding` is 3 bits; `DcachePlugin.storeOutstanding` is 4
        // and asserts `<= 8`. The EIGHTH outstanding core store therefore wraps the
        // mirror 7 -> 0 and spuriously satisfies `stGrantOk`'s `coreStOutstanding === 0`
        // drain-to-zero term with eight core stores still in flight. Measure whether
        // this posture actually reaches that point rather than arguing about it.
        val dcSo = dut.dcache.logic.storeOutstanding.toInt
        val lsSo = dut.lsEu.logic.coreStOutstanding.toInt
        if (dcSo > maxDcStoreOutstanding) maxDcStoreOutstanding = dcSo
        if (lsSo > maxLsStoreOutstanding) maxLsStoreOutstanding = lsSo
        if (dcSo >= 8) dcStoreOutstandingAt8 += 1
        // Every walker->TLB fill, and every non-one-hot lookup.
        if (dut.dtlb.logic.dbgFillFire.toBoolean) {
          ring += f"    cyc=$cyc%7d FILL vpn=0x${dut.dtlb.logic.dbgFillVpn.toLong}%05x " +
            f"ppn=0x${dut.dtlb.logic.dbgFillPpn.toLong}%05x " +
            f"walkVpn=0x${dut.dtlb.logic.dbgWalkVpn.toLong}%05x " +
            f"walkerPpn=0x${dut.dtlb.logic.dbgWalkerRspPpn.toLong}%05x"
          if (ring.size > 90) ring.remove(0)
        }
        val hc = dut.dtlb.logic.tlb.dbgHitCount.toInt
        if (hc > 1) {
          multiHit += 1
          if (multiHit <= 3) {
            ring += f"    cyc=$cyc%7d !!! DTLB MULTI-HIT: $hc ways matched vpn=0x" +
              f"${dut.dtlb.logic.dbgReqVpn.toLong}%05x -- MuxOH select is not one-hot"
            if (ring.size > 90) ring.remove(0)
          }
        }
        // Which of the DTLB's four response classes answered each translation request.
        if (dut.dtlb.logic.dbgReqFire.toBoolean) {
          val cls =
            if (dut.dtlb.logic.dbgTtHit.toBoolean) "TTR"
            else if (!dut.dtlb.logic.dbgMmuEnable.toBoolean) "MMUOFF"
            else if (dut.dtlb.logic.dbgTlbHit.toBoolean && !dut.dtlb.logic.dbgNeedsMRefresh.toBoolean) "TLBHIT"
            else "MISS->WALK"
          ring += f"    cyc=$cyc%7d XREQ vpn=0x${dut.dtlb.logic.dbgReqVpn.toLong}%05x $cls%-10s " +
            f"entryPpn=0x${dut.dtlb.logic.dbgTlbEntryPpn.toLong}%05x"
          if (ring.size > 90) ring.remove(0)
        }
        if (dut.dtlb.logic.rspValid.toBoolean && !prevXrspValid) {
          ring += f"    cyc=$cyc%7d XRSP ppn=0x${dut.dtlb.logic.rspPayload.ppn.toLong}%05x " +
            f"fault=${dut.dtlb.logic.rspPayload.fault.toBoolean} " +
            f"tok=${dut.dtlb.logic.rspPayload.token.toInt}"
          if (ring.size > 90) ring.remove(0)
        }
        prevXrspValid = dut.dtlb.logic.rspValid.toBoolean
        // Ring log of the shared D-cache LOAD port: every command accepted and every
        // response returned, with the ownership tag the arbiter attributed it to. A
        // mistranslation whose descriptor read never faulted can only come from the
        // walker having decoded the WRONG DATA, and this is where that is visible.
        if (dut.dcache.logic.loadCmdPort.valid.toBoolean && dut.dcache.logic.loadCmdPort.ready.toBoolean) {
          ring += f"    cyc=$cyc%7d CMD paddr=0x${dut.dcache.logic.loadCmdPort.payload.paddr.toLong}%08x " +
            f"tok=${dut.dcache.logic.loadCmdPort.payload.token.toInt}%3d ldOwner=${dut.lsEu.logic.ldOwner.toInt}"
          if (ring.size > 60) ring.remove(0)
        }
        if (dut.dcache.logic.loadRspPort.valid.toBoolean) {
          ring += f"    cyc=$cyc%7d RSP data=0x${dut.dcache.logic.loadRspPort.payload.data.toBigInt}%08x " +
            f"fault=${dut.dcache.logic.loadRspPort.payload.fault.toBoolean} " +
            f"rspTag=${dut.lsEu.logic.ldRspTag.toInt} ldOwner=${dut.lsEu.logic.ldOwner.toInt}"
          if (ring.size > 60) ring.remove(0)
        }
        // Every PHYSICAL address the D-cache actually puts on AXI. Everything this
        // program can legally touch is in one of four windows; anything else is a
        // MISTRANSLATION, and (because the sim model DECERRs an undecoded address) is
        // also the only way this program can produce a vector-2 access fault without
        // the MMU reporting a fault of its own.
        def legal(a: Long): Boolean =
          (a < 0x01000000L) || (a >= 0x40800000L && a < 0x40840000L) ||
          (a >= 0x60000000L && a < 0x60040000L) || ((a >>> 16) == 0xffffL)
        if (dut.dcache.logic.axi.ar.valid.toBoolean && dut.dcache.logic.axi.ar.ready.toBoolean) {
          val a = dut.dcache.logic.axi.ar.payload.addr.toLong & 0xffffffffL
          if (!legal(a)) {
            badAr += 1
            if (badAr == 1L) {
              badArLog += f"    AR  0x$a%08x @cyc $cyc ldOwner=${dut.lsEu.logic.ldOwner.toInt}%n"
              badArLog += "    --- D-cache LOAD-port history leading up to it ---\n"
              badArLog += ring.mkString("\n") + "\n"
            }
          }
        }
        if (dut.dcache.logic.axi.aw.valid.toBoolean && dut.dcache.logic.axi.aw.ready.toBoolean) {
          val a = dut.dcache.logic.axi.aw.payload.addr.toLong & 0xffffffffL
          if (!legal(a)) {
            badAw += 1
            if (badAw <= 6) badArLog += f"    AW  0x$a%08x @cyc $cyc stOwner=${dut.lsEu.logic.stOwner.toInt}%n"
          }
        }
        // How long the entry drain actually lasts. If this stays at 1-2 cycles the
        // E_DRAIN livelock mechanism is not being stressed at all, whatever else the
        // run contains.
        if (exc.dbgFsmIsEDrain.toBoolean) {
          eDrainRun += 1
          if (eDrainRun > maxEDrainRun) maxEDrainRun = eDrainRun
        } else eDrainRun = 0
        val a = exc.active.toBoolean
        // `curVec`/`curPc` latch on the trigger edge; read them a cycle later so the
        // trace never prints the PREVIOUS episode's captured state (this cost one
        // confusing run showing a stale vec/PC pair).
        if (tracePend > 0) {
          tracePend -= 1
          if (tracePend == 0 && trace && excEntries <= 40)
            println(f"[walkexc] cyc=$cyc%7d ENTRY vec=${exc.curVec.toInt}%3d " +
              f"pc=0x${exc.curPc.toLong}%08x dtlbFault=${dut.dtlb.logic.faultSeen.toBoolean} " +
              f"dtlbFaultVa=0x${dut.dtlb.logic.faultVa.toLong}%08x " +
              f"itlbFault=${dut.itlb.logic.faultSeen.toBoolean}")
        }
        if (a && !prevExcActive) { excEntries += 1; tracePend = 2 }
        if (a && (ls.ldOwner.toInt != 0 || ls.stOwner.toInt != 0)) excWithWalkGrant += 1
        prevExcActive = a
        for (k <- 0 until 2) {
          val c = dut.rob.logic.commitObs(k)
          if (c.fire.toBoolean) { retired += 1; lastPc = c.pc.toLong & 0xffffffffL; lastRetireCyc = cyc }
        }
        // Asynchronous interrupt injection. Held for a few cycles (the IPL input is a
        // level, not a pulse) and only once the program has actually enabled the MMU --
        // before that the vector table is not built yet and an interrupt would panic.
        if (irqPeriod > 0) {
          if (dut.ctrl.logic.mmuEnable.toBoolean) {
            if (irqHold > 0) {
              irqHold -= 1
              if (irqHold == 0) dut.intCtrl.logic.iplIn #= 0
            } else if (cyc >= nextIrqCyc) {
              dut.intCtrl.logic.iackAvec #= true
              dut.intCtrl.logic.iplIn #= 6
              irqHold = 4
              irqsInjected += 1
              nextIrqCyc = cyc + irqPeriod / 2 + rnd.nextInt(irqPeriod)
            }
          }
        }
      }

      def dump(tag: String): String = {
        val sb = new StringBuilder
        def b(n: String, v: Boolean) = sb ++= f"  $n%-22s = $v\n"
        def i(n: String, v: Long)    = sb ++= f"  $n%-22s = $v\n"
        sb ++= s"[walkexc] ==== $tag at cycle $cyc ====\n"
        i("retired", retired); sb ++= f"  lastCommitPc           = 0x$lastPc%08x\n"
        b("exc.active", exc.active.toBoolean)
        sb ++= f"  exc.curVec             = ${exc.curVec.toInt}\n"
        sb ++= f"  exc.curPc              = 0x${exc.curPc.toLong}%08x\n"
        b("fsm.IDLE", exc.dbgFsmIsIdle.toBoolean)
        b("fsm.E_DRAIN", exc.dbgFsmIsEDrain.toBoolean)
        b("fsm.E_STORE", exc.dbgFsmIsEStore.toBoolean)
        b("fsm.E_STWAIT", exc.dbgFsmIsEStWait.toBoolean)
        b("fsm.E_VECREQ", exc.dbgFsmIsEVecReq.toBoolean)
        b("fsm.E_VECWAIT", exc.dbgFsmIsEVecWait.toBoolean)
        b("fsm.E_REDIR", exc.dbgFsmIsERedir.toBoolean)
        b("fsm.S_DRAIN", exc.dbgFsmIsSDrain.toBoolean)
        b("fsm.S_APPLY", exc.dbgFsmIsSApply.toBoolean)
        b("fsm.S_MAINTWAIT", exc.dbgFsmIsSMaintWait.toBoolean)
        b("fsm.S_REDIR", exc.dbgFsmIsSRedir.toBoolean)
        b("sqDrained", exc.dbgSqDrained.toBoolean)
        b("dcQuiesced", exc.dbgDcQuiesced.toBoolean)
        b("quiesceHoldOut", exc.quiesceHoldOut.toBoolean)
        i("ldOwner", ls.ldOwner.toInt.toLong)
        i("stOwner", ls.stOwner.toInt.toLong)
        b("walkerPortWedge", ls.walkerPortWedge.toBoolean)
        i("coreStOutstanding", ls.coreStOutstanding.toInt.toLong)
        b("excLoadOutstanding", ls.excLoadOutstanding.toBoolean)
        b("excStoreOutstanding", ls.excStoreOutstanding.toBoolean)
        b("dc.dcIdleForMaint", dut.dcache.logic.dcIdleForMaint.toBoolean)
        b("itlb.missPending", dut.itlb.logic.missPending.toBoolean)
        b("dtlb.missPending", dut.dtlb.logic.missPending.toBoolean)
        b("itlb.walkLoadCmd.v", dut.itlb.walkLoadCmd.valid.toBoolean)
        b("itlb.walkLoadCmd.r", dut.itlb.walkLoadCmd.ready.toBoolean)
        b("dtlb.walkLoadCmd.v", dut.dtlb.walkLoadCmd.valid.toBoolean)
        b("dtlb.walkLoadCmd.r", dut.dtlb.walkLoadCmd.ready.toBoolean)
        b("itlb.walkStore.v", dut.itlb.walkStore.valid.toBoolean)
        b("itlb.walkStore.r", dut.itlb.walkStore.ready.toBoolean)
        b("dtlb.walkStore.v", dut.dtlb.walkStore.valid.toBoolean)
        b("dtlb.walkStore.r", dut.dtlb.walkStore.ready.toBoolean)
        b("itlb.faultSeen", dut.itlb.logic.faultSeen.toBoolean)
        b("dtlb.faultSeen", dut.dtlb.logic.faultSeen.toBoolean)
        i("itlbWalkCmds", itlbWalkCmds); i("dtlbWalkCmds", dtlbWalkCmds)
        i("walkStores", walkStores); i("excEntries", excEntries)
        i("irqsInjected", irqsInjected); i("sledWalks", sledWalks)
        i("itlbRspFault", itlbRspFault); i("dtlbRspFault", dtlbRspFault)
        b("dc.diagFaultValid", dut.dcache.logic.diagFaultValid.toBoolean)
        sb ++= f"  dc.diagFaultAddr       = 0x${dut.dcache.logic.diagFaultAddr.toLong}%08x\n"
        i("dc.diagFaultKind", dut.dcache.logic.diagFaultKind.toInt.toLong)
        i("dc.diagFaultResp", dut.dcache.logic.diagFaultResp.toInt.toLong)
        i("maxEDrainRun", maxEDrainRun)
        sb.toString
      }

      var word = 0L
      var wedged = false
      var wedgeDump = ""
      while (word == 0 && !wedged && cyc < timeoutCycles) {
        cd.waitSampling()
        val b0 = dmem.mem.read(SentinelAddr).toLong & 0xffL
        val b1 = dmem.mem.read(SentinelAddr + 1).toLong & 0xffL
        val b2 = dmem.mem.read(SentinelAddr + 2).toLong & 0xffL
        val b3 = dmem.mem.read(SentinelAddr + 3).toLong & 0xffL
        word = (b0 << 24) | (b1 << 16) | (b2 << 8) | b3
        if (word == 0 && retired > 0 && (cyc - lastRetireCyc) > StallLimit) {
          wedged = true
          wedgeDump = dump(s"WEDGE: no retirement for ${cyc - lastRetireCyc} cycles")
        }
      }
      if (!wedged && word == 0) wedgeDump = dump("TIMEOUT (no sentinel)")

      out = WalkExcResult(word, cyc, retired, lastPc, itlbWalkCmds, dtlbWalkCmds,
                          walkStores, excEntries, excWithWalkGrant,
                          cyc - lastRetireCyc, sledWalks, maxEDrainRun,
                          itlbRspFault, dtlbRspFault,
                          f"valid=${dut.dcache.logic.diagFaultValid.toBoolean} " +
                          f"addr=0x${dut.dcache.logic.diagFaultAddr.toLong}%08x " +
                          f"kind=${dut.dcache.logic.diagFaultKind.toInt} " +
                          f"resp=${dut.dcache.logic.diagFaultResp.toInt}",
                          badAr, badAw, badArLog, multiHit,
                          maxDcStoreOutstanding, maxLsStoreOutstanding,
                          dcStoreOutstandingAt8, wedgeDump)
    }
    out
  }
}

class WalkerExcEntryWedgeSpec extends AnyFunSuite {

  test("posture non-vacuity: real ITLB and DTLB walks occur in a full-core run", VerilatorTest) {
    val r = WalkExcHarness.run(TableStressProgram.src(4), timeoutCycles = 300000L, simSeed = 1,
                               trace = true)
    r.report()
    assert(r.retired > 0, "the core never retired anything -- the program never started")
    assert(r.itlbWalkCmds > 0, "VACUOUS: no ITLB descriptor read ever issued")
    assert(r.dtlbWalkCmds > 0, "VACUOUS: no DTLB descriptor read ever issued")
    assert(r.walkStores > 0, "VACUOUS: no walker U/M writeback store ever issued")
    assert(r.excEntries > 0, "VACUOUS: no exception entry ever taken")
  }

  /** The escalation matrix.  Each row adds ONE ingredient, so a wedge names its own
    * cause instead of needing a bisect afterwards.  `cpush` is the important one: it is
    * the only ingredient that makes `quiesceHold` assert at all. */
  private val zeroLat = m68k040.sim.AxiMemModelConfig()
  // The real SoC's memory: a 5/60-cycle L2 in front of DRAM, and a crossbar that allows
  // exactly ONE outstanding read per master port. Both widen the "walk still in flight"
  // window by an order of magnitude relative to the zero-latency default.
  private val slowMem = m68k040.sim.AxiMemModelConfig(
    latency = m68k040.sim.L2LatencyModel(enabled = true, dramCycles = 60))
  private val slowMemXbar = slowMem.copy(crossbarSingleOutstanding = true)

  private val matrix = Seq(
    ("base",                       false, false, false, 0,   zeroLat),
    ("dirtyTables",                true,  false, false, 0,   zeroLat),
    ("cpush",                      false, true,  false, 0,   zeroLat),
    ("dirtyTables+cpush",          true,  true,  false, 0,   zeroLat),
    ("irq",                        false, false, true,  700, zeroLat),
    ("dirtyTables+cpush+irq",      true,  true,  true,  700, zeroLat),
    ("dirtyTables+cpush+fastIrq",  true,  true,  true,  180, zeroLat),
    ("slowMem",                    true,  false, false, 0,   slowMem),
    ("slowMem+cpush",              true,  true,  false, 0,   slowMem),
    ("slowMem+cpush+irq",          true,  true,  true,  700, slowMem),
    ("slowMemXbar+cpush+irq",      true,  true,  true,  700, slowMemXbar)
  )

  for ((name, dirty, cp, irq, period, mem) <- matrix) {
    test(s"walk/exception stress [$name] makes forward progress", VerilatorTest) {
      val r = WalkExcHarness.run(
        TableStressProgram.src(16, dirtyTables = dirty, cpush = cp, irqs = irq),
        timeoutCycles = 900000L, simSeed = 1, irqPeriod = period, memCfg = mem)
      println(s"[walkexc] --- $name ---")
      r.report()
      assert(r.itlbWalkCmds > 0 && r.dtlbWalkCmds > 0,
        s"VACUOUS [$name]: no real table walk occurred")
      assert(r.sentinel == WalkExcHarness.PassWord,
        s"[$name] did not pass: sentinel=0x${r.sentinel.toHexString} " +
        s"retired=${r.retired} lastPc=0x${r.lastCommitPc.toHexString}")
    }
  }

  /** The runaway-sled rows: the frontend's wrong-path fetch during `E_DRAIN` crosses
    * 48 pages in a loop -- more than the 32-entry ITLB holds -- so every hop misses and
    * every miss is a table walk.  This is the closest structural match to the boot ROM
    * that a directed program can be, and it is the exact mechanism `030651f` claims. */
  private val sledMatrix = Seq(
    ("sled",                       true,  false, false, 0,   zeroLat),
    ("sled+cpush",                 true,  true,  false, 0,   zeroLat),
    ("sled+cpush+irq",             true,  true,  true,  700, zeroLat),
    ("sled+slowMem",               true,  false, false, 0,   slowMem),
    ("sled+slowMem+cpush+irq",     true,  true,  true,  700, slowMem),
    ("sled+slowMemXbar+cpush+irq", true,  true,  true,  700, slowMemXbar)
  )

  /** STORE-PORT stress.  `stGrantOk` hands the D-cache store port to a walker only on
    * `coreStOutstanding === 0` -- a drain-to-zero hand-over.  `DcachePlugin`'s own
    * `storeOutstanding` is 4 bits and asserts `<= 8`, while `LsEuPlugin`'s mirror is 3
    * bits, so the EIGHTH outstanding core store wraps the mirror to 0 and satisfies the
    * hand-over with eight core stores still in flight.  These rows drive the store pipe
    * to that point while a walker U/M writeback is asking for the same port. */
  //                        name                    wt    mem          burst irq  cpush noWalk
  private val storeMatrix = Seq(
    ("stores-copyback",        false, zeroLat,     12, true,  true,  false),
    ("stores-wt",              true,  zeroLat,     12, true,  true,  false),
    ("stores-wt+slowMem",      true,  slowMem,     12, true,  true,  false),
    ("stores-wt+slowMem-deep", true,  slowMem,     20, true,  true,  false),
    ("stores-wt+xbar-deep",    true,  slowMemXbar, 20, true,  true,  false),
    // isolation of the slowMem failure
    ("stores-wt+slowMem-noirq",   true, slowMem, 12, false, true,  false),
    ("stores-wt+slowMem-nocpush", true, slowMem, 12, true,  false, false),
    ("stores-wt+slowMem-bare",    true, slowMem, 12, false, false, false),
    // THE CONTROL: identical program, identical memory model, TC.E still on, but the
    // TTRs cover everything so not one table walk happens.
    ("stores-wt+slowMem-NOWALK",  true, slowMem, 12, true,  true,  true),
    ("stores-wt+slowMem-NOWALK-bare", true, slowMem, 12, false, false, true)
  )

  for ((name, wt, mem, burst, irq, cp, noWalk) <- storeMatrix) {
    test(s"walk/exception stress [$name] makes forward progress", VerilatorTest) {
      val r = WalkExcHarness.run(
        TableStressProgram.src(6, dirtyTables = true, cpush = cp, irqs = irq,
                               leafWritethrough = wt, storeBurst = burst,
                               storeGroups = 24, noWalk = noWalk),
        timeoutCycles = 900000L, simSeed = 1, irqPeriod = if (irq) 700 else 0,
        memCfg = mem, trace = sys.env.contains("WALKEXC_TRACE"))
      println(s"[walkexc] --- $name ---")
      r.report()
      if (!noWalk) assert(r.walkStores > 50,
        s"VACUOUS [$name]: only ${r.walkStores} walker U/M stores -- the store " +
        "direction is not actually being stressed")
      else assert(r.dtlbWalkCmds == 0 && r.itlbWalkCmds == 0,
        s"CONTROL BROKEN [$name]: the no-walk control still walked " +
        s"(itlb=${r.itlbWalkCmds} dtlb=${r.dtlbWalkCmds})")
      assert(r.sentinel == WalkExcHarness.PassWord,
        s"[$name] did not pass: sentinel=0x${r.sentinel.toHexString} " +
        s"retired=${r.retired} lastPc=0x${r.lastCommitPc.toHexString}")
    }
  }

  /** Determinism / robustness of the mistranslation reproducer. */
  test("mistranslation reproducer is stable across sim seeds", VerilatorTest) {
    val results = (1 to 6).map { seed =>
      val r = WalkExcHarness.run(
        TableStressProgram.src(6, dirtyTables = true, cpush = true, irqs = true,
                               leafWritethrough = true, storeBurst = 12, storeGroups = 24),
        timeoutCycles = 900000L, simSeed = seed, irqPeriod = 700, memCfg = slowMem)
      println(f"[walkexc] seed=$seed sentinel=0x${r.sentinel}%08x badAr=${r.badAr} " +
        f"badAw=${r.badAw} retired=${r.retired} cycles=${r.cycles}")
      (seed, r)
    }
    val bad = results.count(_._2.badAr > 0)
    println(s"[walkexc] mistranslation seen in $bad of 6 seeds")
    assert(results.forall(_._2.badAr == 0),
      s"MISTRANSLATION in $bad of 6 seeds: " +
      results.filter(_._2.badAr > 0).map(t => s"seed ${t._1}").mkString(", "))
  }

  for ((name, dirty, cp, irq, period, mem) <- sledMatrix) {
    test(s"walk/exception stress [$name] makes forward progress", VerilatorTest) {
      val r = WalkExcHarness.run(
        TableStressProgram.src(12, dirtyTables = dirty, cpush = cp, irqs = irq,
                               sledPages = 48),
        timeoutCycles = 900000L, simSeed = 1, irqPeriod = period, memCfg = mem)
      println(s"[walkexc] --- $name ---")
      r.report()
      assert(r.itlbWalkCmds > 0 && r.dtlbWalkCmds > 0,
        s"VACUOUS [$name]: no real table walk occurred")
      assert(r.sentinel == WalkExcHarness.PassWord,
        s"[$name] did not pass: sentinel=0x${r.sentinel.toHexString} " +
        s"retired=${r.retired} lastPc=0x${r.lastCommitPc.toHexString}")
    }
  }

  test("exception entry with table walks in flight makes forward progress", VerilatorTest) {
    val r = WalkExcHarness.run(TableStressProgram.src(24), timeoutCycles = 600000L, simSeed = 1)
    r.report()
    assert(r.excWithWalkGrant > 0,
      "VACUOUS: no exception-sequencer episode ever overlapped a held walker grant")
    assert(r.sentinel == WalkExcHarness.PassWord,
      s"program did not pass: sentinel=0x${r.sentinel.toHexString} " +
      s"retired=${r.retired} lastPc=0x${r.lastCommitPc.toHexString}")
  }
}
