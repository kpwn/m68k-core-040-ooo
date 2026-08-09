package m68k040.ls

import m68k040.{M68kParams, M68kSim, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.cache.DcachePlugin
import m68k040.execute.LsEuPlugin
import m68k040.execute.regfile.{RegFilePluginInt, RegFilePluginNzvc, RegFilePluginX}
import m68k040.isa.{MemOp, Size}
import m68k040.mmu.DIdentityTranslationPlugin
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** Directed test of the LS-EU two-access load sequencing + cross-line merge (Task 3).
  *
  * Preloads two adjacent 16-byte lines in behavioral memory, issues a misaligned
  * load spanning the boundary, and checks the merged value in the PRF. Verifies
  * the aligned fast path is unchanged (single access). */
class LsEuCrossSpec extends AnyFunSuite {
  class Dut extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val param  = new ParamPlugin(M68kParams())
    val rfInt  = new RegFilePluginInt
    val rfNzvc = new RegFilePluginNzvc   // LS EU now writes NZVC for MOVE-to-memory
    val rfX    = new RegFilePluginX     // LS EU now writes X for RTR CCR-restore
    val xlate  = new DIdentityTranslationPlugin
    val dcache = new DcachePlugin
    val eu     = new LsEuPlugin
    val src    = new LsEuSourcePlugin
    db.on { host.asHostOf(Seq[FiberPlugin](param, rfInt, rfNzvc, rfX, xlate, dcache, eu, src)) }
  }

  def simConfig = M68kSim().withVerilator
  def memByte(addr: Long): Int = ((addr * 7 + 0x11) & 0xff).toInt

  /** Big-endian value of `size` bytes starting at `base`. */
  def expected(base: Long, size: Int): BigInt =
    (0 until size).foldLeft(BigInt(0))((acc, i) => (acc << 8) | BigInt(memByte(base + i)))

  def seed(dut: Dut, cd: ClockDomain, preg: Int, value: Long): Unit = {
    dut.src.logic.seedValid #= true; dut.src.logic.seedAddr #= preg
    dut.src.logic.seedData #= BigInt(value & 0xffffffffL)
    cd.waitSampling()
    dut.src.logic.seedValid #= false
    cd.waitSampling(2)
  }

  def initDut(dut: Dut, injectBusErrors: Boolean = false): (ClockDomain, BehavioralMemAgent) = {
    val cd = dut.clockDomain; cd.forkStimulus(10)
    val mem = new BehavioralMemAgent(dut.dcache.logic.axi, cd,
                                     injectBusErrors = injectBusErrors)
    val s = dut.src.logic
    s.iValid #= false; s.iSqCommitValid #= false; s.iSqFlush #= false
    s.seedValid #= false; s.obsIntAddr #= 0; s.iPsrcAValid #= false; s.iPsrcBValid #= false
    s.iLeaAddr #= false   // MUST default (same class as iStkPush below): an undriven
                          // leaAddr makes EVERY load take the LEA address-generate path,
                          // writing the EA to the dst instead of the loaded data. Verilator
                          // randomizes it per seed -> this spec failed ~1-in-3 runs.
    s.iStkPush #= false   // MUST default: an undriven stkPush makes a load PREDECREMENT,
                          // writing (base - size) to the dst instead of the loaded data.
    cd.waitSampling(80)
    (cd, mem)
  }

  def issueLoad(dut: Dut, cd: ClockDomain, basePreg: Int, disp: Long,
                size: SpinalEnumElement[Size.type], pdst: Int, robId: Int): Unit = {
    val s = dut.src.logic
    s.iValid #= true; s.iMemOp #= MemOp.LOAD; s.iSize #= size
    s.iPsrcA #= basePreg; s.iPsrcAValid #= true; s.iPsrcBValid #= false
    s.iImm #= BigInt(disp & 0xffffffffL)
    s.iPdst #= pdst; s.iPdstValid #= true; s.iRobId #= robId
    cd.waitSamplingWhere(s.iReady.toBoolean)
    s.iValid #= false
  }

  def waitCompletion(dut: Dut, cd: ClockDomain, robId: Int, maxCycles: Int = 80): Boolean = {
    var saw = false; var n = 0
    while (!saw && n < maxCycles) {
      if (dut.src.logic.cValid.toBoolean && dut.src.logic.cRob.toInt == robId) saw = true
      cd.waitSampling(); n += 1
    }
    saw
  }

  def preload(mem: BehavioralMemAgent, base: Long, n: Int): Unit =
    for (i <- 0 until n) mem.pokeByte(base + i, memByte(base + i))

  /** One sampled cycle of the slot-A -> slot-B translation handover. */
  case class Sample(bArm: Boolean, stale: Boolean, mtch: Boolean)

  /** Run a split load and record {xlateBArm, reqStale, reqMatch} every cycle from the
    * issue handshake until completion. NON-PORTABLE (reads LsEuPlugin internals). */
  def traceSplit(dut: Dut, cd: ClockDomain, robId: Int, maxCycles: Int = 200): Seq[Sample] = {
    val buf = scala.collection.mutable.ArrayBuffer[Sample]()
    var n = 0; var done = false
    while (n < maxCycles && !done) {
      if (dut.src.logic.cValid.toBoolean && dut.src.logic.cRob.toInt == robId) done = true
      buf += Sample(dut.eu.logic.xlateBArm.toBoolean,
                    dut.eu.logic.reqStale.toBoolean,
                    dut.eu.logic.reqMatch.toBoolean)
      cd.waitSampling(); n += 1
    }
    assert(done, s"split access robId=$robId never completed within $maxCycles cycles")
    buf.toSeq
  }

  /** Assert the freshness-flag waveform across the slot-A -> slot-B handover:
    * `xlateBArm` rises at some cycle T; `reqStale` must read True at T (the cycle
    * `xlateVaddr` first presents s1AddrB while `reqReg` still holds slot A's request)
    * and at NO other cycle of this access; `reqMatch` must therefore be False at T and
    * True at T+1, when XLATE_B consumes slot B's genuinely re-captured translation. */
  def assertHandoverWaveform(tr: Seq[Sample], label: String): Unit = {
    def bits(f: Sample => Boolean) = tr.map(s => if (f(s)) 1 else 0).mkString
    val wave = s"(bArm=${bits(_.bArm)}, stale=${bits(_.stale)}, match=${bits(_.mtch)})"
    val rise = tr.indexWhere(_.bArm)
    assert(rise > 0, s"$label: xlateBArm never rose -- not a split access? $wave")
    val fall = tr.indexWhere(!_.bArm, rise)
    assert(fall > rise, s"$label: xlateBArm never cleared -- XLATE_B livelock? $wave")

    // (1) The binding property: on the cycle xlateVaddr first presents s1AddrB while
    // reqReg still holds slot A's request, the flag MUST read stale and reqMatch False,
    // so XLATE_B cannot consume slot A's response as slot B's.
    assert(tr(rise).stale && !tr(rise).mtch,
      s"$label: reqStale must be True / reqMatch False on the xlateBArm-rise cycle " +
      s"($rise) -- otherwise XLATE_B consumes slot A's stale translation. $wave")

    // (2) It must be EXACTLY one cycle: the request re-captures unconditionally, so a
    // longer stall would be a livelock, not a settle.
    assert(!tr(rise + 1).stale && tr(rise + 1).mtch,
      s"$label: reqStale must clear and reqMatch re-assert exactly one cycle later " +
      s"(${rise + 1}). $wave")
    assert(fall == rise + 2,
      s"$label: XLATE_B must consume at ${rise + 1} and leave at ${rise + 2}; " +
      s"xlateBArm actually cleared at $fall. $wave")

    // (3) Closure: reqStale reads True on EXACTLY the three cycles it should, and
    // nowhere else. Cycle `rise` and `fall` come from `xlateBArmSwitched` (both edges
    // are tracked -- the falling one is a deliberate, harmless over-approximation: the
    // FSM has left XLATE_B by then and nothing reads reqMatch again for this access).
    // The one remaining stale cycle is the RegNext(issuePort.fire) settle right after
    // the issue handshake this trace starts on.
    val staleCycles = tr.indices.filter(tr(_).stale)
    val fireSettle  = staleCycles.filter(_ < rise)
    assert(fireSettle.size == 1 && fireSettle.head <= 2,
      s"$label: expected exactly one pre-handover stale cycle (the issuePort.fire " +
      s"settle); got $fireSettle. $wave")
    assert(staleCycles == Seq(fireSettle.head, rise, fall),
      s"$label: reqStale must read True on exactly {fire-settle, xlateBArm rise, " +
      s"xlateBArm fall} = ${Seq(fireSettle.head, rise, fall)}; got $staleCycles. $wave")
  }

  test("aligned long load unchanged (fast path, single access)", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val (cd, mem) = initDut(dut)
      val base = 0x1000L
      preload(mem, base, 16)
      seed(dut, cd, preg = 10, value = base)
      issueLoad(dut, cd, basePreg = 10, disp = 0, Size.LONG, pdst = 20, robId = 5)
      assert(waitCompletion(dut, cd, robId = 5), "aligned load completes")
      cd.waitSampling(4)
      dut.src.logic.obsIntAddr #= 20; sleep(1)
      assert(dut.src.logic.obsIntData.toBigInt == expected(base, 4),
        s"aligned ${dut.src.logic.obsIntData.toBigInt.toString(16)} exp ${expected(base, 4).toString(16)}")
    }
  }

  test("misaligned long load crossing a 16-byte line (offset 14)", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val (cd, mem) = initDut(dut)
      val lineA = 0x2000L
      preload(mem, lineA, 32)         // both lines 0x2000 and 0x2010
      val addr = lineA + 14           // long spans 0x200E..0x2011 (lines 0x2000 / 0x2010)
      seed(dut, cd, preg = 10, value = addr)
      issueLoad(dut, cd, basePreg = 10, disp = 0, Size.LONG, pdst = 21, robId = 6)
      assert(waitCompletion(dut, cd, robId = 6), "cross-line load completes")
      cd.waitSampling(4)
      dut.src.logic.obsIntAddr #= 21; sleep(1)
      assert(dut.src.logic.obsIntData.toBigInt == expected(addr, 4),
        s"cross-line ${dut.src.logic.obsIntData.toBigInt.toString(16)} exp ${expected(addr, 4).toString(16)}")
    }
  }

  test("split load reports a physical bus fault from either half and never writes a result", VerilatorTest) {
    val compiled = simConfig.compile(new Dut)

    def run(label: String, addr: Long, expectedFaultAddr: Long, expectedAr: Int): Unit = {
      compiled.doSim(label) { dut =>
        val (cd, mem) = initDut(dut, injectBusErrors = true)
        // These boundary addresses deliberately cross between the test model's
        // decoded and unmapped top-nibble regions:
        //   0x3ffffffe: slot A unmapped, slot B (0x40000000) decoded
        //   0x0ffffffe: slot A decoded, slot B (0x10000000) unmapped
        preload(mem, addr & ~0xFL, 32)
        seed(dut, cd, preg = 10, value = addr)

        var arCount = 0
        fork {
          while (true) {
            cd.waitSampling()
            if (dut.dcache.logic.axi.ar.valid.toBoolean &&
                dut.dcache.logic.axi.ar.ready.toBoolean) arCount += 1
          }
        }

        issueLoad(dut, cd, basePreg = 10, disp = 0, Size.LONG, pdst = 22, robId = 12)
        var sawFault = false
        var sawCompletion = false
        var faultAddr = 0L
        var faultAtc = true
        var n = 0
        while ((!sawFault || !sawCompletion) && n < 200) {
          if (dut.src.logic.fValid.toBoolean) {
            sawFault = true
            faultAddr = dut.src.logic.fAddr.toLong & 0xffffffffL
            faultAtc = dut.src.logic.fAtc.toBoolean
            assert(dut.src.logic.fRob.toInt == 12, s"$label fault robId")
          }
          if (dut.src.logic.cValid.toBoolean && dut.src.logic.cRob.toInt == 12)
            sawCompletion = true
          if (!sawFault || !sawCompletion) cd.waitSampling()
          n += 1
        }
        cd.waitSampling(4)
        assert(sawFault && sawCompletion, s"$label must fault and complete")
        assert(faultAddr == expectedFaultAddr,
          f"$label faultAddr=0x$faultAddr%08x expected 0x$expectedFaultAddr%08x")
        assert(!faultAtc, s"$label is a physical AXI fault, not an ATC fault")
        assert(arCount == expectedAr,
          s"$label refill count: got $arCount expected $expectedAr")
        dut.src.logic.obsIntAddr #= 22; sleep(1)
        assert(dut.src.logic.obsIntData.toBigInt == 0,
          s"$label faulted split load must not write its destination")
      }
    }

    run("splitFaultA", 0x3ffffffeL, 0x3ffffffeL, expectedAr = 1)
    run("splitFaultB", 0x0ffffffeL, 0x10000000L, expectedAr = 2)
  }

  test("page-crossing word load (identity xlate, two accesses)", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val (cd, mem) = initDut(dut)
      val addr = 0x2FFFL              // word spans 0x2FFF..0x3000 (page boundary)
      preload(mem, 0x2FF0L, 16)
      preload(mem, 0x3000L, 16)
      seed(dut, cd, preg = 10, value = addr)
      issueLoad(dut, cd, basePreg = 10, disp = 0, Size.WORD, pdst = 22, robId = 7)
      assert(waitCompletion(dut, cd, robId = 7), "page-cross load completes")
      cd.waitSampling(4)
      dut.src.logic.obsIntAddr #= 22; sleep(1)
      assert(dut.src.logic.obsIntData.toBigInt == expected(addr, 2),
        s"page-cross ${dut.src.logic.obsIntData.toBigInt.toString(16)} exp ${expected(addr, 2).toString(16)}")
    }
  }

  // ───────────────────────────────────────────────────────────────────────────
  // FMax "Lever A" (2026-08-08): `reqMatch` is no longer a 20-bit VPN value
  // compare but a 1-bit event-driven freshness flag. These tests pin the two
  // scenarios the design spec calls out as binding.
  //
  // Both scenarios are split accesses, because the slot-A -> slot-B handover is
  // the ONLY place a stale `reqReg` can be presented while the FSM is still
  // consuming `reqMatch`: `issuePort.ready` is provably False for the whole
  // window between "a translation becomes pending" and "it resolves", so no NEW
  // access can supersede an in-flight one.
  //
  //   - CROSS-LINE (0x200E): slot A (0x200E) and slot B (0x2010) share a page,
  //     so their VPNs COINCIDE. This is the design spec's "coincidental VPN
  //     match" case. MEASURED, not assumed -- the pre-Lever-A commit was checked
  //     out in a git worktree and instrumented with the same probes:
  //
  //                                     xlateBArm   reqMatch    XLATE_B dwell
  //       pre-Lever-A  cross-LINE       0001000     0011111     1 cycle
  //       pre-Lever-A  cross-PAGE       0001100     0010101     2 cycles
  //       post-Lever-A cross-LINE       0001100     0010111     2 cycles
  //       post-Lever-A cross-PAGE       0001100     0010111     2 cycles
  //
  //     i.e. the old bit-exact compare really did consume slot B ONE CYCLE
  //     EARLIER when the VPNs coincided (reqMatch already True on the very first
  //     XLATE_B cycle, off slot A's still-registered but numerically identical
  //     vpn); the freshness flag always pays the settle. Where the VPNs genuinely
  //     differ the two are identical, because the old compare stalled there too.
  //     The new code is therefore never faster than the old -- it never consumes a
  //     translation the old code would have rejected, only ever the reverse.
  //   - CROSS-PAGE (0x2FFF): slot A (page 0x2) and slot B (page 0x3) have
  //     DIFFERENT VPNs, so the old compare stalled here too. Old and new agree
  //     cycle-for-cycle. Under identity translation ppn==vpn, so consuming slot
  //     A's stale response for slot B would yield paddr 0x2000 instead of
  //     0x3000 -- i.e. this test is a live regression guard against exactly the
  //     "split-access second half not translated" bug fixed by commit d22a949.
  //     (It is what caught the design spec's own proposed expression, which put
  //     the `xlateBArm` term behind an extra RegNext and therefore left reqStale
  //     False on the one cycle that matters. See the RTL comment.)

  test("Lever A: reqStale/reqMatch waveform across a CROSS-LINE (same-VPN) slot-A->B handover", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val (cd, mem) = initDut(dut)
      val addr = 0x2000L + 14
      preload(mem, 0x2000L, 32)
      seed(dut, cd, preg = 10, value = addr)
      issueLoad(dut, cd, basePreg = 10, disp = 0, Size.LONG, pdst = 23, robId = 8)
      val tr = traceSplit(dut, cd, robId = 8)
      assertHandoverWaveform(tr, "cross-line (coincident VPN)")
      cd.waitSampling(4)
      dut.src.logic.obsIntAddr #= 23; sleep(1)
      assert(dut.src.logic.obsIntData.toBigInt == expected(addr, 4),
        s"cross-line data ${dut.src.logic.obsIntData.toBigInt.toString(16)} exp ${expected(addr, 4).toString(16)}")
    }
  }

  test("Lever A: reqStale/reqMatch waveform across a CROSS-PAGE (differing-VPN) slot-A->B handover", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val (cd, mem) = initDut(dut)
      val addr = 0x2FFFL
      preload(mem, 0x2FF0L, 16); preload(mem, 0x3000L, 16)
      seed(dut, cd, preg = 10, value = addr)
      issueLoad(dut, cd, basePreg = 10, disp = 0, Size.WORD, pdst = 24, robId = 9)
      val tr = traceSplit(dut, cd, robId = 9)
      assertHandoverWaveform(tr, "cross-page (differing VPN)")
      cd.waitSampling(4)
      dut.src.logic.obsIntAddr #= 24; sleep(1)
      // The binding correctness property: slot B's ppn must come from slot B's OWN
      // translation. Under identity xlate a stale slot-A consume yields 0x2000's
      // bytes here, which are preloaded to DIFFERENT values than 0x3000's.
      assert(dut.src.logic.obsIntData.toBigInt == expected(addr, 2),
        s"page-cross data ${dut.src.logic.obsIntData.toBigInt.toString(16)} exp ${expected(addr, 2).toString(16)} " +
        "-- a mismatch here means slot B consumed slot A's translation (stale-consume bug)")
    }
  }

  // NOTE on why there is no absolute issue->completion cycle-count test here: the
  // AXI memory model (`AxiMemModel`) drives its channels through
  // `StreamReadyRandomizer`/`simRandom`, so end-to-end completion counts vary run to
  // run (measured 18 and 23 cycles for the SAME cross-line load on the SAME commit).
  // The XLATE_B dwell asserted above is deterministic because the whole slot-A ->
  // slot-B translation handover happens BEFORE any cache line fill is launched, so no
  // AXI traffic has occurred yet.
}
