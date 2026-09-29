package m68k040.fuzz

import m68k040.VerilatorTest
import m68k040.sim.{AxiMemModelConfig, L2LatencyModel, L2Sweeps}
import org.scalatest.funsuite.AnyFunSuite

/** D4 directed tests: a cache-inhibited access is a FULL memory barrier in both directions
  * (`ShippingCoreConfig.inhibitedFullBarrier`, env `CPU_INHIBITED_FULL_BARRIER=1`).
  *
  * Every scenario runs through the ordinary corpus runner with a PASSIVE
  * `InhibitedBarrierMonitor` attached, so the verdict is read off the D-side AXI master and
  * the D-cache command boundary -- never off the gates the RTL change added.
  *
  * ==THE TWO ARMS, and why the OFF arm asserts something too==
  * The flag is read at elaboration, so each arm is its own Verilator build (run this suite
  * once with the variable set and once without).
  *   - barrier ON : zero violations of every kind, the program passes, AND the RTL's own
  *                  sim-only counters prove the specific mechanism the scenario targets
  *                  FIRED (a barrier that never engaged proves nothing).
  *   - barrier OFF: the scenarios written to provoke a violation MUST observe one. That is
  *                  the permanent proof that each check can fail -- the OFF build is the
  *                  "broken RTL" control, measured on every run rather than once by hand.
  *                  If a future change makes the OFF core structurally safe for a scenario,
  *                  this fails loudly and the scenario must be re-aimed, not deleted.
  *
  * ==SCENARIOS (the task's deadlock list is covered by D, E and by the lock-step IRQ storm)==
  *   A  inhibited STORE vs table-walker traffic (real 3-level walks, slow B): BEFORE/AFTER
  *      on the bus -- the walker's refills and U/M write-backs are the D-side traffic that
  *      does NOT come from an older LS op, so the pre-D4 gates never saw it.
  *   B  inhibited LOAD at the head right behind an older load that triggered a walk.
  *   C  younger cacheable loads directly behind an inhibited load (the AFTER half at the
  *      command boundary: the pre-D4 core shadow-captured them inside the D-cache).
  *   D  multi-uop macros whose inhibited access is NOT the first uop (RMW, mem-to-mem both
  *      ways, MOVEM both ways, a misaligned device long) -- the `firstOfInstr` vs `p0.last`
  *      trap family; checked for values AND for zero violations.
  *   E  an exception from an OLDER op (divide-by-zero trap + RTE) and mispredicted branches
  *      around an inhibited RMW (a wrong-path inhibited op must never launch; a flush while
  *      pending must release the barrier). Device read/write COUNTS are exact. */
class InhibitedFullBarrierSpec extends AnyFunSuite {
  private val barrierOn = m68k040.top.ShippingCoreConfig.inhibitedFullBarrier
  private val Timeout   = 400000L

  private val passTail =
    """
      |    lea     0xFFFF0000, %a6
      |    move.l  #0xC0FFEE00, %d0
      |    move.l  %d0, (%a6)
      |_halt:
      |    bra     _halt
      |_fail:
      |    lea     0xFFFF0000, %a6
      |    move.l  %d0, (%a6)
      |    bra     _halt
      |""".stripMargin

  private val dramSlowStores = L2Sweeps.storeSlow                       // B ~60, R ~60
  private val dramFast = AxiMemModelConfig(latency = L2LatencyModel(enabled = true, dramCycles = 20))

  sealed trait Posture
  case object MmuWalk  extends Posture   // real walks, sentinel block inhibited/serialized
  case object Copyback extends Posture   // DTT0 low copyback, DTT1 high inhibited

  private final case class Scenario(
    name: String, src: String, posture: Posture, dcfg: AxiMemModelConfig,
    extraBlocks: Set[Long] = Set.empty,
    /** OFF arm: this count must be > 0 (the check can fail). None = no OFF expectation. */
    offMustSee: Option[(String, InhibitedBarrierMonitor => Int)] = None,
    /** ON arm: the targeted mechanism must have fired. */
    onFired: InhibitedBarrierMonitor => Option[String] = _ => None,
    extra: InhibitedBarrierMonitor => Option[String] = _ => None)

  private def run(sc: Scenario): Unit = {
    var mon: InhibitedBarrierMonitor = null
    val probe = new PostureProbe
    val posture = sc.posture match {
      case Copyback => CachePosture.ForceCacheableCopyback
      case MmuWalk =>
        val img = m68k040.oracle.ProgramAssembler.assemble(sc.src, PortedTestRunner.loadAddr)
          .fold(e => fail(s"assemble: ${e.reason}"), _.bytes.length)
        CachePosture.ForceMmuWalkCopyback(
          MmuWalkDriver.buildFor(MmuWalkDriver.seedBlocks(img) ++ sc.extraBlocks, pages8K = false))
    }
    val outcome = PortedTestRunner.run(sc.name, sc.src, Timeout, cachePosture = posture,
      probe = probe, dcfg = sc.dcfg,
      onDut = d => mon = new InhibitedBarrierMonitor(d, s"${sc.name} barrier=$barrierOn").attach())
    info(mon.summary)
    println(mon.summary + mon.details(4))
    if (sc.posture == MmuWalk) info(s"posture: ${probe.summary}")
    assert(outcome == PortedPass, s"${sc.name}: $outcome\n${mon.summary}${mon.details()}")
    if (sc.posture == MmuWalk)
      assert(probe.dtlbWalkStarts > 0 && probe.walkStores > 0,
        s"VACUOUS: the MMU posture did not walk / write back U,M -- ${probe.summary}")
    assert(mon.inhibReads + mon.inhibWrites > 0, s"VACUOUS: no inhibited bus transaction -- ${mon.summary}")
    sc.extra(mon).foreach(m => fail(s"${sc.name}: $m\n${mon.summary}"))
    if (barrierOn) {
      assert(mon.rtlPresent, "barrier flag is on but the RTL counters are absent")
      assert(mon.violations == 0,
        s"${sc.name}: BARRIER VIOLATED with the barrier ON\n${mon.summary}${mon.details(6)}")
      sc.onFired(mon).foreach(m => fail(s"${sc.name}: mechanism did not fire: $m\n${mon.summary}"))
    } else {
      assert(!mon.rtlPresent)
      sc.offMustSee.foreach { case (what, f) =>
        assert(f(mon) > 0,
          s"${sc.name}: SENSITIVITY LOST -- the pre-D4 core no longer shows a $what violation " +
          s"here, so this scenario can no longer prove the check fails on broken RTL. " +
          s"Re-aim it.\n${mon.summary}")
      }
    }
  }

  // ── A ───────────────────────────────────────────────────────────────────────────────
  private val srcA =
    """    .text
      |_start:
      |    lea     0xFFFF0100, %a0          | device: sentinel block, CM = inhibited/serialized
      |    lea     0x00200000, %a1          | 16 cold pages, 16 KiB apart: each one walks
      |    moveq   #15, %d7
      |    moveq   #0, %d6
      |_la:
      |    move.l  %d7, (%a0)               | inhibited store, drains precisely at the head
      |    move.l  (%a1), %d1               | younger load, cold page -> DTLB walk + U/M store
      |    add.l   %d1, %d6
      |    lea     0x4000(%a1), %a1
      |    dbf     %d7, _la
      |    move.l  #0xBAD0000A, %d0
      |    tst.l   (%a0)                    | last device write was 0
      |    bne     _fail
      |""".stripMargin + passTail

  test("D4 A: inhibited store vs table-walker traffic (bus BEFORE/AFTER)", VerilatorTest) {
    run(Scenario("d4_a_store_vs_walker", srcA, MmuWalk, dramSlowStores,
      extraBlocks = Set(m68k040.fuzz.MmuWalkPosture.blockOf(0x00200000L)),
      offMustSee = Some(("bus-level", m => m.busBefore.size + m.busAfter.size)),
      onFired = m =>
        if (m.rtlStoreLaunches < 16) Some(s"storeLaunches=${m.rtlStoreLaunches} < 16")
        else if (m.rtlWalkerFenced == 0) Some("the walker fence never held a walker")
        else None))
  }

  // ── B ───────────────────────────────────────────────────────────────────────────────
  private val srcB =
    """    .text
      |_start:
      |    lea     0xFFFF0100, %a0
      |    lea     0x00200000, %a1
      |    move.l  #0x12345678, (%a0)
      |    moveq   #15, %d7
      |_lb:
      |    move.l  (%a1), %d1               | OLDER load: cold page -> walk + U/M write-back
      |    move.l  (%a0), %d0               | inhibited load, launches at the head
      |    move.l  4(%a1), %d2              | younger, same (now warm) page
      |    lea     0x4000(%a1), %a1
      |    dbf     %d7, _lb
      |    cmp.l   #0x12345678, %d0
      |    beq     _okb
      |    bra     _fail
      |_okb:
      |""".stripMargin + passTail

  test("D4 B: inhibited load at the head behind walker traffic (bus BEFORE)", VerilatorTest) {
    run(Scenario("d4_b_load_vs_walker", srcB, MmuWalk, dramSlowStores,
      extraBlocks = Set(m68k040.fuzz.MmuWalkPosture.blockOf(0x00200000L)),
      offMustSee = Some(("bus- or command-level", m => m.violations)),
      onFired = m =>
        if (m.rtlLoadLaunches < 16) Some(s"loadLaunches=${m.rtlLoadLaunches} < 16")
        else if (m.rtlQuietWait + m.rtlWalkerFenced == 0)
          Some("neither the quiesce wait nor the walker fence ever engaged")
        else None))
  }

  // ── C ───────────────────────────────────────────────────────────────────────────────
  private val srcC =
    """    .text
      |_start:
      |    lea     0xFFFF0100, %a0
      |    lea     0x00001000, %a2
      |    move.l  #0x11112222, (%a2)
      |    move.l  #0x33334444, 4(%a2)
      |    move.l  #0x55556666, (%a0)
      |    moveq   #0, %d4
      |    moveq   #31, %d7
      |_lc:
      |    move.l  (%a0), %d0               | inhibited load
      |    move.l  (%a2), %d2               | younger cacheable loads (warm, L1 hits)
      |    move.l  4(%a2), %d3
      |    add.l   %d0, %d4
      |    dbf     %d7, _lc
      |    move.l  #0xBAD0000C, %d0
      |    cmp.l   #0x11112222, %d2
      |    bne     _fail
      |    cmp.l   #0x33334444, %d3
      |    bne     _fail
      |""".stripMargin + passTail

  test("D4 C: younger loads behind an inhibited load (command-level AFTER)", VerilatorTest) {
    run(Scenario("d4_c_younger_after_load", srcC, Copyback, dramFast,
      offMustSee = Some(("command-level AFTER", m => m.cmdAfter.size)),
      onFired = m =>
        if (m.rtlLoadLaunches < 32) Some(s"loadLaunches=${m.rtlLoadLaunches} < 32")
        else if (m.rtlYoungerHeld == 0) Some("no younger op was ever held behind an inhibited load")
        else None))
  }

  // ── D ───────────────────────────────────────────────────────────────────────────────
  private val srcD =
    """    .text
      |_start:
      |    lea     0xFFFF0100, %a0
      |    lea     0x00001000, %a2
      |    move.l  #5, (%a0)
      |    move.l  #7, (%a2)
      |    moveq   #3, %d1
      |    add.l   %d1, (%a0)               | inhibited RMW: load, ALU, store in ONE macro -> 8
      |    move.l  (%a2), 4(%a0)            | cacheable load -> inhibited store (2nd uop)
      |    move.l  (%a0), 8(%a2)            | inhibited load (1st uop) -> cacheable store
      |    moveq   #1, %d0
      |    moveq   #2, %d1
      |    moveq   #3, %d2
      |    moveq   #4, %d3
      |    movem.l %d0-%d3, 16(%a0)         | four inhibited stores, one macro
      |    movem.l 16(%a0), %d4-%d7         | four inhibited loads, one macro
      |    move.l  4(%a0), 12(%a0)          | inhibited load -> inhibited store
      |    add.l   (%a0), %d7               | inhibited load feeding an ALU op -> 4 + 8 = 12
      |    move.l  #0xA1B2C3D4, 46(%a0)     | MISALIGNED device long across a 16 B line
      |    move.l  46(%a0), %d3
      |    move.l  #0xBAD000D1, %d0
      |    cmp.l   #8, (%a0)
      |    bne     _fail
      |    move.l  #0xBAD000D2, %d0
      |    cmp.l   #7, 4(%a0)
      |    bne     _fail
      |    move.l  #0xBAD000D3, %d0
      |    cmp.l   #8, 8(%a2)
      |    bne     _fail
      |    move.l  #0xBAD000D4, %d0
      |    cmp.l   #7, 12(%a0)
      |    bne     _fail
      |    move.l  #0xBAD000D5, %d0
      |    cmp.l   #1, %d4
      |    bne     _fail
      |    cmp.l   #2, %d5
      |    bne     _fail
      |    cmp.l   #3, %d6
      |    bne     _fail
      |    cmp.l   #12, %d7
      |    bne     _fail
      |    move.l  #0xBAD000D6, %d0
      |    cmp.l   #0xA1B2C3D4, %d3
      |    bne     _fail
      |""".stripMargin + passTail

  test("D4 D: multi-uop macros whose inhibited access is not the first uop", VerilatorTest) {
    run(Scenario("d4_d_macros", srcD, Copyback, dramFast,
      onFired = m =>
        if (m.rtlLoadLaunches == 0 || m.rtlStoreLaunches == 0)
          Some(s"loadLaunches=${m.rtlLoadLaunches} storeLaunches=${m.rtlStoreLaunches}")
        else None))
  }

  // ── E ───────────────────────────────────────────────────────────────────────────────
  private val DevE = 0xFFFF0200L
  private val srcE =
    """    .text
      |_start:
      |    move.l  #_divz, 0x14             | vector 5: integer divide by zero
      |    lea     0xFFFF0200, %a0
      |    move.l  #0, (%a0)                | pure store (no read)
      |    moveq   #0, %d5
      |    moveq   #19, %d7
      |_le:
      |    move.l  %d7, %d1
      |    andi.l  #3, %d1
      |    beq.s   _lskip                   | taken when d7 % 4 == 0: 5 of 20, mispredicts
      |    addq.l  #1, (%a0)                | inhibited RMW -- must NEVER launch on a wrong path
      |_lskip:
      |    btst    #0, %d7
      |    beq.s   _lnodiv
      |    moveq   #0, %d2
      |    divu.w  %d2, %d1                 | odd d7: trap (OLDER op's exception) -> RTE
      |_lnodiv:
      |    move.l  (%a0), %d3               | inhibited load right after the trap returns
      |    dbf     %d7, _le
      |    move.l  #0xBAD000E1, %d0
      |    cmp.l   #15, (%a0)
      |    bne     _fail
      |    move.l  #0xBAD000E2, %d0
      |    cmp.l   #10, %d5
      |    bne     _fail
      |""".stripMargin + passTail +
    """_divz:
      |    addq.l  #1, %d5
      |    rte
      |""".stripMargin

  test("D4 E: exception from an older op, and mispredicts around an inhibited RMW", VerilatorTest) {
    run(Scenario("d4_e_exc_and_flush", srcE, Copyback, dramFast,
      onFired = m => if (m.rtlLoadLaunches < 36) Some(s"loadLaunches=${m.rtlLoadLaunches} < 36") else None,
      // EXACT device traffic: 15 RMW reads + 20 plain reads + 1 final compare = 36 reads;
      // 1 init + 15 RMW writes = 16 writes. More = a wrong-path or replayed device access.
      extra = m => {
        val r = m.devReadAddrs.count(_ == DevE); val w = m.devWriteAddrs.count(_ == DevE)
        if (r != 36 || w != 16) Some(f"device 0x$DevE%x: reads=$r (want 36) writes=$w (want 16)")
        else None
      }))
  }
}
