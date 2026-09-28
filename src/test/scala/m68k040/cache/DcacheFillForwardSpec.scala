package m68k040.cache

import m68k040.{M68kParams, M68kSim, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.isa.Size
import m68k040.ls.BehavioralMemAgent
import m68k040.mmu.{DIdentityTranslationPlugin, MmuControlPlugin}
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** SLICE D1.2 -- FILL-FORWARD, measured as an EXACT CYCLE COUNT.
  *
  * The implementation plan's own acceptance criterion for this task is
  * `test("fill-forward: miss response latency dropped by exactly 2 cycles")`, and it
  * says why the number and not a percentage: "assert the exact new number, never an
  * inequality. If the measured saving is not exactly 2, stop and find out why."
  *
  * The two cycles are named in `DcachePlugin.fillForward`: today REFILL allocates on
  * the R-beat cycle N, REPLAY re-launches an array READ at N+1, S1 resolves the
  * guaranteed hit at N+2 and S2 responds at N+3. The line's bytes were already in
  * `missLine` at N, so the read-back is pure overhead.
  *
  * This spec builds the SAME `Dut` twice, differing only in the flag, and compares.
  * It does not hard-code either arm's absolute latency: an unrelated pipeline change
  * that moves both arms equally should not fail this test, whereas one that erodes
  * the saving must. The absolute numbers are still printed and separately asserted
  * against the shape the file documents, so a change that moves BOTH arms is visible
  * rather than silently absorbed.
  */
class DcacheFillForwardSpec extends AnyFunSuite {

  class Dut(fillForward: Boolean) extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val param   = new ParamPlugin(M68kParams())
    val xlate   = new DIdentityTranslationPlugin
    // `sectored = false` PINNED EXPLICITLY, per `ShippingCoreConfig`'s own rule that a
    // test must vary what it varies BY NAME and never by diverging from the shipping
    // values. This spec asserts EXACT cycle counts (OFF = 3, ON = 1) derived from a
    // SINGLE-BEAT refill: at a 16-byte line the one R beat is the whole line, so
    // "R beat -> response" is the post-data overhead and nothing else. Under slice
    // `D3-BURST` a line miss is a 4-beat burst and the first R beat is up to three beats
    // before the demanded sector's, so the same interval measures the burst as well
    // (observed: 18 cycles, not 3). That is not a regression in fill-forward -- it is a
    // different quantity. Sectored fill-forward correctness has its own coverage in
    // `DcacheSectorSpec` ("returns the DEMANDED sector's bytes, from every one of the
    // four beat positions"), which is the property that actually matters there.
    val dcache  = new DcachePlugin(fillForward = fillForward, sectored = false)
    val probe   = new DcacheProbePlugin
    val mmuCtrl = new MmuControlPlugin
    db.on { host.asHostOf(Seq[FiberPlugin](param, xlate, dcache, probe, mmuCtrl)) }
  }

  private def simConfig = M68kSim().withVerilator

  private def memByte(addr: Long): Int = ((addr * 5 + 0x23) & 0xff).toInt
  private def expected(base: Long, size: Int): BigInt =
    (0 until size).foldLeft(BigInt(0))((acc, i) => (acc << 8) | BigInt(memByte(base + i)))

  private def initDut(dut: Dut): (ClockDomain, BehavioralMemAgent) = {
    val cd = dut.clockDomain
    cd.forkStimulus(period = 10)
    val mem = new BehavioralMemAgent(dut.dcache.logic.axi, cd)
    dut.probe.logic.loadCmdIn.valid #= false
    dut.probe.logic.loadProbeIn.valid #= false
    dut.probe.logic.loadProbeCancelIn.valid #= false
    dut.probe.logic.loadProbeCancelIn.payload.all #= false
    dut.probe.logic.storeIn.valid #= false
    dut.probe.logic.maintCmdIn.valid #= false
    dut.probe.logic.maintCmdIn.payload.push #= false
    dut.probe.logic.maintCmdIn.payload.invalidate #= false
    dut.probe.logic.maintCmdIn.payload.scope #= 0
    dut.probe.logic.maintCmdIn.payload.sel #= 0
    dut.probe.logic.maintCmdIn.payload.addr #= 0
    cd.waitSampling(4)
    cd.waitSamplingWhere(!dut.dcache.logic.resetSweepBusy.toBoolean)
    (cd, mem)
  }

  /** POST-DATA OVERHEAD: cycles from the refill's R beat being consumed to
    * `loadRsp.valid`, plus the returned datum and the accept-relative response cycle.
    *
    * ⚠ WHY THE R BEAT AND NOT THE ACCEPT. Accept-to-response was measured first and
    * JITTERED BY ONE CYCLE between otherwise identical runs of the same arm (6 and 7).
    * That jitter is entirely upstream of the thing under test: `BehavioralMemAgent`
    * runs in a forked sim thread, so when its R beat lands relative to the DUT's AR is
    * sensitive to intra-timestep thread ordering -- the gotcha `DcacheSpec.loadTimed`'s
    * own doc comment records ("a forked sim thread, so reading it from this thread is
    * sensitive to intra-timestep thread ordering ... neither of which says anything
    * about the D-cache's timing"). An exact-cycle test built on an accept-relative
    * number would be flaky in a way that looks like a real regression.
    *
    * The R-beat-relative interval is what THIS code determines, and it is fully
    * deterministic: it is the "~3 fixed cycles of post-data overhead" the
    * implementation plan names, measured. */
  private def missLatency(dut: Dut, cd: ClockDomain, vaddr: Long,
                          size: SpinalEnumElement[Size.type] = Size.LONG,
                          budget: Int = 200): (Int, BigInt, Int) = {
    dut.probe.logic.loadCmdIn.valid #= true
    dut.probe.logic.loadCmdIn.payload.vaddr #= vaddr
    dut.probe.logic.loadCmdIn.payload.paddr #= vaddr   // identity translation here
    dut.probe.logic.loadCmdIn.payload.size #= size
    dut.probe.logic.loadCmdIn.payload.cacheMode #= CacheMode.WRITETHROUGH
    cd.waitSamplingWhere(dut.probe.logic.loadCmdIn.ready.toBoolean &&
                         dut.probe.logic.loadCmdIn.valid.toBoolean)
    dut.probe.logic.loadCmdIn.valid #= false
    var rspAt = -1
    var rBeatAt = -1
    var sawMiss = false
    var n = 0
    val trace = scala.collection.mutable.ArrayBuffer.empty[String]
    while (rspAt < 0 && n <= budget) {
      if (dut.dcache.logic.ldS1Valid.toBoolean && !dut.dcache.logic.ldS1Hit.toBoolean)
        sawMiss = true
      val ev = Seq(
        if (dut.dcache.logic.ldS1Valid.toBoolean) "S1" else "",
        if (dut.dcache.logic.ldS2Valid.toBoolean) "S2" else "",
        if (dut.dcache.logic.dbgFsmRefill.toBoolean) "RF" else "",
        if (dut.dcache.logic.dbgFsmReplay.toBoolean) "RP" else "",
        if (dut.dcache.logic.axi.ar.valid.toBoolean) "AR" else "",
        if (dut.dcache.logic.axi.r.valid.toBoolean && dut.dcache.logic.axi.r.ready.toBoolean) "Rb" else "",
        // `fillFwdResp` is NOT read here on purpose. With the flag OFF it is a `False`
        // LITERAL rather than a signal (so the flag-OFF netlist carries no dead wire --
        // see `DcachePlugin.missMultiHot`), and this spec builds BOTH arms, so reading it
        // raises a SimError in the OFF arm. `**RSP**` is the marker the test asserts on
        // anyway; which mechanism produced it is decided by the arm, not observed.
        if (dut.probe.logic.loadRspOut.valid.toBoolean) "**RSP**" else "").filter(_.nonEmpty)
      if (rBeatAt < 0 && dut.dcache.logic.axi.r.valid.toBoolean &&
          dut.dcache.logic.axi.r.ready.toBoolean) rBeatAt = n
      if (ev.nonEmpty) trace += s"$n:${ev.mkString("+")}"
      if (dut.probe.logic.loadRspOut.valid.toBoolean) rspAt = n
      if (rspAt < 0) { cd.waitSampling(); n += 1 }
    }
    println(s"[d1.2-trace] ${trace.mkString(" ")}")
    assert(rspAt >= 0, s"no load response within $budget cycles for vaddr=0x${vaddr.toHexString}")
    assert(sawMiss, f"the load at 0x$vaddr%x was supposed to MISS; no S1 miss decision was seen")
    assert(rBeatAt >= 0, "no refill R beat was consumed -- this was not a refill")
    (rspAt - rBeatAt, dut.probe.logic.loadRspOut.payload.data.toBigInt, rspAt)
  }

  /** One cold miss per arm, on the same address, with the same memory image. */
  private def coldMiss(fillForward: Boolean): (Int, BigInt, Int) = {
    var out: (Int, BigInt, Int) = (-1, BigInt(-1), -1)
    simConfig.compile(new Dut(fillForward)).doSim { dut =>
      val (cd, mem) = initDut(dut)
      val base = 0x9B00L
      for (i <- 0 until 64) mem.pokeByte(base + i, memByte(base + i))
      out = missLatency(dut, cd, base)
      cd.waitSampling(8)
    }
    out
  }

  test("fill-forward: miss response latency dropped by exactly 2 cycles", VerilatorTest) {
    val (offAt, offData, offAbs) = coldMiss(fillForward = false)
    val (onAt,  onData,  onAbs)   = coldMiss(fillForward = true)
    println(f"[d1.2] cold cacheable load miss, POST-DATA overhead (R beat -> loadRsp): " +
            f"fillForward=OFF $offAt%d cyc, ON $onAt%d cyc, saving ${offAt - onAt}%d " +
            f"(accept-relative, for reference only and one-cycle jittery: $offAbs%d / $onAbs%d)")
    assert(offData == expected(0x9B00L, 4),
      f"OFF arm returned 0x$offData%x, want 0x${expected(0x9B00L, 4)}%x -- the baseline " +
      f"arm must still be correct or the comparison means nothing")
    assert(onData == expected(0x9B00L, 4),
      f"ON arm returned 0x$onData%x, want 0x${expected(0x9B00L, 4)}%x -- fill-forward " +
      f"extracts from `missLine` with `missOff`/`missSize`, exactly as `inhibitedResp` " +
      f"does; a wrong datum here means that extraction is mis-indexed, NOT that the " +
      f"timing is wrong")
    // The two absolute numbers are asserted as well as the delta, because a change
    // that moved BOTH arms equally would leave the delta intact while changing the
    // machine -- and because each is derivable from the code by hand:
    //   OFF = 3: REPLAY relaunches the array read at R+1, S1 resolves the guaranteed
    //            hit at R+2, S2 responds at R+3. (This is the implementation plan's
    //            "~3 fixed cycles of post-data overhead", measured exactly.)
    //   ON  = 1: REPLAY pulses `fillFwdResp` at R+1 and the response mux extracts from
    //            `missLine` the same cycle.
    assert(offAt == 3,
      s"the BASELINE post-data overhead must be 3 cycles (REPLAY relaunch, S1 resolve, " +
      s"S2 respond); measured $offAt. If this moved, the response pipeline changed and " +
      s"the saving below must be RE-DERIVED from the code, not adjusted to fit.")
    assert(onAt == 1,
      s"fill-forward must respond on the cycle REPLAY is entered (R+1); measured $onAt")
    assert(offAt - onAt == 2,
      s"fill-forward must remove EXACTLY 2 cycles. Measured OFF=$offAt ON=$onAt, saving " +
      s"${offAt - onAt}. A saving of 0 means the flag did not reach the plugin.")
  }

  test("fill-forward: a SECOND access to the filled line still hits the array", VerilatorTest) {
    // The point of this test: fill-forward answers from `missLine` WITHOUT re-reading
    // the array, so it would still "work" if REFILL's allocate write were broken. That
    // would be a silent correctness hole -- every miss right, every subsequent hit
    // wrong. Reading a DIFFERENT offset of the same line back through the ordinary
    // S1/S2 hit path is what proves the line was really installed.
    simConfig.compile(new Dut(fillForward = true)).doSim { dut =>
      val (cd, mem) = initDut(dut)
      val base = 0x9C00L
      for (i <- 0 until 64) mem.pokeByte(base + i, memByte(base + i))
      val (missAt, missData, _) = missLatency(dut, cd, base)
      assert(missData == expected(base, 4), f"fill-forward datum at the line base")
      cd.waitSampling(6)
      // A different LONG of the SAME 16-byte line: must be a hit, out of the array.
      dut.probe.logic.loadCmdIn.valid #= true
      dut.probe.logic.loadCmdIn.payload.vaddr #= base + 8
      dut.probe.logic.loadCmdIn.payload.paddr #= base + 8
      dut.probe.logic.loadCmdIn.payload.size #= Size.LONG
      dut.probe.logic.loadCmdIn.payload.cacheMode #= CacheMode.WRITETHROUGH
      cd.waitSamplingWhere(dut.probe.logic.loadCmdIn.ready.toBoolean)
      dut.probe.logic.loadCmdIn.valid #= false
      var sawMiss = false
      var n = 0
      while (!dut.probe.logic.loadRspOut.valid.toBoolean && n < 100) {
        if (dut.dcache.logic.ldS1Valid.toBoolean && !dut.dcache.logic.ldS1Hit.toBoolean)
          sawMiss = true
        cd.waitSampling(); n += 1
      }
      assert(n < 100, "no response to the second access")
      assert(!sawMiss,
        "the second access to the SAME line MISSED -- fill-forward answered the first " +
        "miss from `missLine` but REFILL's allocate write did not install the line. " +
        "That is the silent-correctness hole this test exists for.")
      assert(dut.probe.logic.loadRspOut.payload.data.toBigInt == expected(base + 8, 4),
        "the array-resident hit after a fill-forwarded miss must carry the right bytes")
      cd.waitSampling(4)
      println(f"[d1.2] fill-forwarded miss at $missAt%d cyc, then an array hit on the " +
              f"same line at $n%d cyc -- the line WAS installed")
    }
  }
}
