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

  /** @param iterations how many (A-line + TRAP) rounds to run
    * @param pflushInHandler whether the handlers also flush, i.e. whether an ITLB walk
    *        is forced on the RTE-side redirect as well as the entry-side one */
  def src(iterations: Int, pflushInHandler: Boolean = true): String = {
    val handlerFlush = if (pflushInHandler) "    pflusha\n" else ""
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
    move.l  #(DATA_VA+0x21), %d1
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

    | ---- restricted TTR posture: the INSTRUCTION side gets no TTR at all ----
    moveq   #0, %d0
    movec   %d0, %itt0
    movec   %d0, %itt1
    move.l  #0x0000E020, %d0               | DTT0: VA[31:24]==0x00 only, copyback
    movec   %d0, %dtt0
    move.l  #0xFF00E060, %d0               | DTT1: 0xFF......, inhibited (sentinel)
    movec   %d0, %dtt1
    move.l  #ROOT, %d0
    movec   %d0, %urp
    movec   %d0, %srp
    move.l  #0x80008000, %d0               | CACR: DE | IE
    movec   %d0, %cacr
    move.l  #0x00008000, %d0               | TC: E=1, 4 KiB pages
    movec   %d0, %tc

    | ==== from here every fetch may run an ITLB walk and every DATA_VA access a
    | ==== DTLB walk. Nothing below is covered by a transparent translation.

    move.l  #$iterations, %d7
_loop:
    pflusha
    move.l  #DATA_VA, %a2
    move.l  (%a2), %d0                     | DTLB walk + U writeback
    lea     0x1000(%a2), %a2
    move.l  %d0, (%a2)                     | DTLB walk + U/M writeback (store)
    lea     0x1000(%a2), %a2
    move.l  (%a2), %d1
    lea     0x1000(%a2), %a2
    move.l  %d1, (%a2)
_aline_site:
    .short  0xa05d                         | A-line trap with walks in flight
_after_aline:
    lea     0x1000(%a2), %a2
    move.l  (%a2), %d2
    pflusha
    lea     0x1000(%a2), %a2
    move.l  %d2, (%a2)
    trap    #0
_after_trap:
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
"""
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
  wedgeDump: String
) {
  def report(): Unit = {
    println(f"[walkexc] sentinel=0x$sentinel%08x cycles=$cycles retired=$retired " +
      f"lastPc=0x$lastCommitPc%08x")
    println(f"[walkexc] itlbWalkCmds=$itlbWalkCmds dtlbWalkCmds=$dtlbWalkCmds " +
      f"walkStores=$walkStores excEntries=$excEntries excWithWalkGrant=$excWithWalkGrant")
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

  def run(src: String, timeoutCycles: Long = 400000L, simSeed: Int = 1,
          trace: Boolean = false): WalkExcResult = {
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
      val iAgent = FuzzDut.attachProgramWithBusErrors(dut.icache.logic.axi, cd, loadAddr, image.bytes)
      val dsideMem = new m68k040.sim.ConstFillSparseMemory(0xff.toByte)
      val dmem = new m68k040.ls.BehavioralMemAgent(dut.dcache.logic.axi, cd,
                                                   sharedMem = dsideMem, injectBusErrors = true)
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
      var cyc = 0L
      var lastRetireCyc = 0L

      cd.onSamplings {
        cyc += 1
        if (dut.itlb.walkLoadCmd.valid.toBoolean && dut.itlb.walkLoadCmd.ready.toBoolean) itlbWalkCmds += 1
        if (dut.dtlb.walkLoadCmd.valid.toBoolean && dut.dtlb.walkLoadCmd.ready.toBoolean) dtlbWalkCmds += 1
        if ((dut.itlb.walkStore.valid.toBoolean && dut.itlb.walkStore.ready.toBoolean) ||
            (dut.dtlb.walkStore.valid.toBoolean && dut.dtlb.walkStore.ready.toBoolean)) walkStores += 1
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
                          cyc - lastRetireCyc, wedgeDump)
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
