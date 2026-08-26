package m68k040.ls

import m68k040.{M68kParams, M68kSim, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.cache.DcachePlugin
import m68k040.execute.LsEuPlugin
import m68k040.execute.regfile.{RegFilePluginInt, RegFilePluginNzvc, RegFilePluginX}
import m68k040.isa.{MemOp, Size}
import m68k040.mmu.{DtlbPlugin, MmuControlPlugin}
import m68k040.sim.{AxiMemModel, AxiMemModelConfig}
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** Directed regression for the split-load ring redesign (task: close the P3
  * ring-drain-then-serialize gap -- see the design comment on `AlignedLoadCtx` /
  * `alignedEnqSplit` in `LsEuPlugin.scala`).
  *
  * Reuses `LsEuFastPreciseSpec`'s proven DUT shape (real `DtlbPlugin` +
  * `MmuControlPlugin` + `CacheControlStubPlugin` + `TbPreciseDrainWirePlugin`) rather
  * than `LsEuCrossSpec`'s DUT, which was found to already fail on an unmodified,
  * clean checkout of this task's base commit (confirmed via `git stash` before any
  * of this task's changes) -- a pre-existing, unrelated harness issue, not something
  * this task introduced or is in scope to fix.
  *
  * Proves:
  *   (a) a split load's ring push does NOT require the ring empty -- it enqueues
  *       while OTHER unrelated loads are still resident (the actual ring-drain fix).
  *   (b) the merged result is architecturally correct at LONG offsets 13/14/15 and
  *       WORD offset 15 (every offset where a load crosses a 16-byte line).
  *   (c) a bus fault on EITHER half completes the instruction as a fault with the
  *       correct faulting address, and a fault on slot A provably never issues a
  *       second (slot B) bus transaction -- direct proof of the abort-cancel logic.
  *   (d) an INHIBITED split load keeps the ROB's inhibited-load busy signal
  *       asserted continuously across BOTH sub-accesses (never glitches low after
  *       just slot A) -- the exact race the concurrent `435e9efb` preemption fix
  *       depends on.
  */
class LsEuSplitRingSpec extends AnyFunSuite {
  class Dut extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val param     = new ParamPlugin(M68kParams())
    val rfInt     = new RegFilePluginInt
    val rfNzvc    = new RegFilePluginNzvc
    val rfX       = new RegFilePluginX
    val ctrl      = new MmuControlPlugin
    val dtlb      = new DtlbPlugin()
    val dcache    = new DcachePlugin()
    val cacheCtrl = new CacheControlStubPlugin
    val eu        = new LsEuPlugin
    val src       = new LsEuSourcePlugin
    val wire      = new TbPreciseDrainWirePlugin(eu)
    db.on { host.asHostOf(Seq[FiberPlugin](param, rfInt, rfNzvc, rfX, ctrl, dtlb, dcache, cacheCtrl, eu, src, wire)) }
  }

  def simConfig = M68kSim().withVerilator
  def memByte(addr: Long): Int = ((addr * 7 + 0x11) & 0xff).toInt

  def expected(base: Long, size: Int): BigInt =
    (0 until size).foldLeft(BigInt(0))((acc, i) => (acc << 8) | BigInt(memByte(base + i)))

  def seed(dut: Dut, cd: ClockDomain, preg: Int, value: Long): Unit = {
    dut.src.logic.seedValid #= true; dut.src.logic.seedAddr #= preg
    dut.src.logic.seedData #= BigInt(value & 0xffffffffL)
    cd.waitSampling()
    dut.src.logic.seedValid #= false
    cd.waitSampling(2)
  }

  def initDut(dut: Dut, dataMemCfg: AxiMemModelConfig = AxiMemModelConfig(),
              dcacheEnabled: Boolean = true): (ClockDomain, AxiMemModel) = {
    val cd = dut.clockDomain; cd.forkStimulus(10)
    val mem = AxiMemModel.attachFull(dut.dcache.logic.axi, cd, dataMemCfg)
    val s = dut.src.logic
    s.iValid #= false; s.iSqCommitValid #= false; s.iSqFlush #= false
    s.iStkPush #= false; s.iLeaAddr #= false
    s.seedValid #= false; s.obsIntAddr #= 0; s.iPsrcAValid #= false; s.iPsrcBValid #= false
    dut.ctrl.logic.mmuEnable #= false
    dut.ctrl.logic.urp #= 0; dut.ctrl.logic.srp #= 0
    // Mirrors LsEuFastPreciseSpec exactly: dcacheEnabled stays FALSE through the
    // reset invalidation sweep below and is only flipped on AFTER it settles.
    dut.cacheCtrl.logic.dcacheEnabled #= false
    dut.wire.logic.iRobHeadIn #= 0; dut.wire.logic.iRobHeadValidIn #= false
    cd.waitSampling(80) // PRF init sweep
    // Reset invalidation sweep (see LsEuFastPreciseSpec's identical wait / c6e3ad4).
    cd.waitSamplingWhere(!dut.dcache.logic.resetSweepBusy.toBoolean)
    dut.cacheCtrl.logic.dcacheEnabled #= dcacheEnabled
    (cd, mem)
  }

  def preload(mem: AxiMemModel, base: Long, n: Int): Unit =
    for (i <- 0 until n) mem.pokeByte(base + i, memByte(base + i))

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

  def waitCompletion(dut: Dut, cd: ClockDomain, robId: Int, maxCycles: Int = 200): Boolean = {
    var saw = false; var n = 0
    while (!saw && n < maxCycles) {
      if (dut.src.logic.cValid.toBoolean && dut.src.logic.cRob.toInt == robId) saw = true
      cd.waitSampling(); n += 1
    }
    saw
  }

  def readInt(dut: Dut, pdst: Int): BigInt = {
    dut.src.logic.obsIntAddr #= pdst; sleep(1)
    dut.src.logic.obsIntData.toBigInt
  }

  test("split load enqueues into the ring WITHOUT waiting for it to drain empty", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val (cd, mem) = initDut(dut)
      val ordBase1 = 0x1000L; val ordBase2 = 0x1100L
      val splitBase = 0x2000L + 14   // LONG spans 0x200E..0x2011 (cross-line)
      preload(mem, 0x1000L, 16); preload(mem, 0x1100L, 16); preload(mem, 0x2000L, 32)
      seed(dut, cd, preg = 10, value = ordBase1)
      seed(dut, cd, preg = 11, value = ordBase2)
      seed(dut, cd, preg = 12, value = splitBase)

      // Sample `alignedCount` (BEFORE this cycle's update) and `alignedEnqSplit`
      // every cycle so we can see the exact ring occupancy on the cycle the split
      // pair is actually pushed.
      var sawSplitEnqWithNonemptyRing = false
      var maxCountAtSplitEnq = 0
      val tracking = new java.util.concurrent.atomic.AtomicBoolean(true)
      fork {
        while (tracking.get()) {
          if (dut.eu.logic.alignedEnqSplit.toBoolean) {
            val c = dut.eu.logic.alignedCount.toInt
            if (c > 0) sawSplitEnqWithNonemptyRing = true
            maxCountAtSplitEnq = math.max(maxCountAtSplitEnq, c)
          }
          cd.waitSampling()
        }
      }

      // Issue TWO ordinary aligned loads first (neither is waited-on), so the ring
      // is holding outstanding entries when the split load is issued right behind
      // them -- under the OLD design this split load could not even LAUNCH (it
      // required `alignedEmpty`) until both of these fully drained.
      issueLoad(dut, cd, basePreg = 10, disp = 0, Size.LONG, pdst = 20, robId = 1)
      issueLoad(dut, cd, basePreg = 11, disp = 0, Size.LONG, pdst = 21, robId = 2)
      issueLoad(dut, cd, basePreg = 12, disp = 0, Size.LONG, pdst = 22, robId = 3)

      assert(waitCompletion(dut, cd, robId = 1), "ordinary load 1 completes")
      assert(waitCompletion(dut, cd, robId = 2), "ordinary load 2 completes")
      assert(waitCompletion(dut, cd, robId = 3), "split load completes")
      tracking.set(false)
      cd.waitSampling(2)

      assert(sawSplitEnqWithNonemptyRing,
        "the split pair's ring push must be observed on a cycle where the ring already " +
        "holds an unrelated entry -- otherwise the ring-drain-wait regressed")
      assert(maxCountAtSplitEnq > 0,
        s"expected alignedCount > 0 at the split push cycle, got $maxCountAtSplitEnq " +
        "(a value of 0 would mean the old alignedEmpty-gated behavior is still in effect)")

      cd.waitSampling(4)
      assert(readInt(dut, 22) == expected(splitBase, 4),
        s"split load merged result: got ${readInt(dut, 22).toString(16)} exp ${expected(splitBase, 4).toString(16)}")
    }
  }

  test("split load merge is correct at every LONG/WORD cross-line offset", VerilatorTest) {
    val cases = Seq(
      ("LONG@13", 0x3000L + 13, Size.LONG, 4),
      ("LONG@14", 0x3000L + 14, Size.LONG, 4),
      ("LONG@15", 0x3000L + 15, Size.LONG, 4),
      ("WORD@15", 0x3000L + 15, Size.WORD, 2),
    )
    val compiled = simConfig.compile(new Dut)
    for ((label, addr, size, nbytes) <- cases) {
      compiled.doSim(label) { dut =>
        val (cd, mem) = initDut(dut)
        preload(mem, 0x3000L, 32)
        seed(dut, cd, preg = 10, value = addr)
        issueLoad(dut, cd, basePreg = 10, disp = 0, size, pdst = 20, robId = 4)
        assert(waitCompletion(dut, cd, robId = 4), s"$label completes")
        cd.waitSampling(4)
        val got = readInt(dut, 20)
        val exp = expected(addr, nbytes)
        assert(got == exp, s"$label: got ${got.toString(16)} exp ${exp.toString(16)}")
      }
    }
  }

  test("split load bus fault on either half completes as a fault; a slot-A fault never issues slot B's read", VerilatorTest) {
    val compiled = simConfig.compile(new Dut)

    def run(label: String, addr: Long, expectedFaultAddr: Long, expectedAr: Int): Unit = {
      compiled.doSim(label) { dut =>
        val (cd, mem) = initDut(dut, AxiMemModelConfig(injectBusErrors = true))
        preload(mem, addr & ~0xFL, 32)
        seed(dut, cd, preg = 10, value = addr)

        var arCount = 0
        val tracking = new java.util.concurrent.atomic.AtomicBoolean(true)
        fork {
          while (tracking.get()) {
            cd.waitSampling()
            if (dut.dcache.logic.axi.ar.valid.toBoolean && dut.dcache.logic.axi.ar.ready.toBoolean) arCount += 1
          }
        }

        issueLoad(dut, cd, basePreg = 10, disp = 0, Size.LONG, pdst = 23, robId = 6)
        var sawFault = false; var faultAddr = 0L; var faultAtc = true
        // A fault completes via `fValid`, not `cValid` -- poll both explicitly
        // (mirrors LsEuCrossSpec's own fault test).
        var n = 0; var sawCompletion = false
        while ((!sawFault || !sawCompletion) && n < 400) {
          if (dut.src.logic.fValid.toBoolean) {
            sawFault = true
            faultAddr = dut.src.logic.fAddr.toLong & 0xffffffffL
            faultAtc = dut.src.logic.fAtc.toBoolean
            assert(dut.src.logic.fRob.toInt == 6, s"$label fault robId")
          }
          if (dut.src.logic.cValid.toBoolean && dut.src.logic.cRob.toInt == 6) sawCompletion = true
          if (!sawFault || !sawCompletion) cd.waitSampling()
          n += 1
        }
        tracking.set(false)
        cd.waitSampling(4)
        assert(sawFault && sawCompletion, s"$label must fault and complete (fault=$sawFault comp=$sawCompletion)")
        assert(faultAddr == expectedFaultAddr,
          f"$label faultAddr=0x$faultAddr%08x expected 0x$expectedFaultAddr%08x")
        assert(!faultAtc, s"$label is a physical AXI fault, not an ATC fault")
        assert(arCount == expectedAr, s"$label refill count: got $arCount expected $expectedAr")
        assert(readInt(dut, 23) == 0, s"$label faulted split load must not write its destination")
      }
    }

    // Same decode-boundary trick as LsEuCrossSpec: top nibble 0x3 undecoded, 0x4
    // decoded; top nibble 0x0 decoded, 0x1 undecoded.
    run("splitFaultA (slot A faults, slot B never launches)", 0x3ffffffeL, 0x3ffffffeL, expectedAr = 1)
    run("splitFaultB (slot A ok, slot B faults)", 0x0ffffffeL, 0x10000000L, expectedAr = 2)
  }

  test("INHIBITED split load: busy signal stays asserted across BOTH sub-accesses", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      // dcacheEnabled=false (DE=0) folds EVERY access to INHIBITED regardless of
      // the (MMU-off, identity) page's own attribute -- see LsEuPlugin's
      // `txEffectiveCmode` / the class doc on this file.
      val (cd, mem) = initDut(dut, dcacheEnabled = false)
      val addr = 0x4000L + 14   // cross-line, INHIBITED
      preload(mem, 0x4000L, 32)
      seed(dut, cd, preg = 10, value = addr)
      // An INHIBITED load only launches once it is the ROB head (p4LaunchOk) --
      // drive the wired robHead port so this test's single instruction is
      // recognized as the head throughout (mirrors LsEuFastPreciseSpec's own
      // inhibited-load tests).
      dut.wire.logic.iRobHeadIn #= 6; dut.wire.logic.iRobHeadValidIn #= true

      issueLoad(dut, cd, basePreg = 10, disp = 0, Size.LONG, pdst = 24, robId = 6)

      var sawBusy = false
      var everWentFalseBeforeCompletion = false
      var completed = false
      var n = 0
      while (!completed && n < 400) {
        val busy = dut.eu.logic.loadBusyReg.toBoolean
        if (busy) sawBusy = true
        if (sawBusy && !busy) everWentFalseBeforeCompletion = true
        if (dut.src.logic.cValid.toBoolean && dut.src.logic.cRob.toInt == 6) completed = true
        else cd.waitSampling()
        n += 1
      }
      assert(sawBusy, "inhibited split load must assert loadBusyReg at some point")
      assert(!everWentFalseBeforeCompletion,
        "loadBusyReg must not clear between slot A's response and the instruction's own " +
        "terminal completion -- a premature clear reopens the interrupt/trace preemption " +
        "race task 435e9efb closed")
      cd.waitSampling(4)
      assert(readInt(dut, 24) == expected(addr, 4),
        s"inhibited split load merged result: got ${readInt(dut, 24).toString(16)} exp ${expected(addr, 4).toString(16)}")
    }
  }

  /** REGRESSION (hardware-confirmed deadlock, 2026-08-26): a split pair followed by a
    * BURST of ordinary loads must not wedge the send-side hold.
    *
    * `alignedSendHeld` used to ask "is the slot physically behind me still valid?" as a
    * proxy for "is my own slot A still pending?". Once slot A pops, its INDEX is free,
    * and with the ring full `alignedPushPtr === alignedRspPtr`, so the very push that
    * `alignedCanEnq`'s `|| alignedRspFire` term permits on the pop cycle lands a NEW,
    * unrelated ordinary descriptor on slot A's index -- re-asserting the proxy and
    * holding slot B forever. Sends are in order (blocked at slot B) and responses are in
    * order (blocked on the unsent slot B), so the ring wedges FULL and the core stops
    * retiring entirely.
    *
    * This test builds that state directly: one split load, then eight ordinary loads
    * issued back to back with no waiting, which keeps the ring saturated across slot A's
    * response. On the broken RTL nothing ever completes past the split; on the fixed RTL
    * all nine complete with correct data.
    *
    * `ExecuteLockStepSpec`'s "MOVEM.L round trip, base N mod 4" matrix covers the same
    * defect end to end (a MOVEM.L off a non-longword-aligned base generates exactly this
    * split/ordinary mixture); this one pins the mechanism at the ring itself so a future
    * regression is localised immediately instead of surfacing as "the core stopped".
    */
  test("split load + ordinary-load burst: a ring wrap onto slot A's index must not wedge the send-hold", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val (cd, mem) = initDut(dut)
      val nOrdinary = 8
      val splitBase = 0x2000L + 14        // LONG spans 0x200E..0x2011 -> cross-line -> split pair
      preload(mem, 0x2000L, 32)
      val ordBases = (0 until nOrdinary).map(i => 0x4000L + i * 0x100L)
      ordBases.foreach(b => preload(mem, b, 16))
      seed(dut, cd, preg = 10, value = splitBase)
      ordBases.zipWithIndex.foreach { case (b, i) => seed(dut, cd, preg = 11 + i, value = b) }

      var completed = Set.empty[Int]
      val watching = new java.util.concurrent.atomic.AtomicBoolean(true)
      fork {
        while (watching.get()) {
          if (dut.src.logic.cValid.toBoolean) completed += dut.src.logic.cRob.toInt
          cd.waitSampling()
        }
      }
      // The issuer runs in its OWN thread on purpose: `issueLoad` blocks on
      // `waitSamplingWhere(iReady)`, so on the broken RTL it would block forever and the
      // test would HANG instead of failing with a usable diagnosis.
      fork {
        issueLoad(dut, cd, basePreg = 10, disp = 0, Size.LONG, pdst = 20, robId = 1)
        for (i <- 0 until nOrdinary)
          issueLoad(dut, cd, basePreg = 11 + i, disp = 0, Size.LONG, pdst = 21 + i, robId = 2 + i)
      }

      val expectAll = nOrdinary + 1
      var n = 0
      while (completed.size < expectAll && n < 3000) { cd.waitSampling(); n += 1 }
      watching.set(false)
      cd.waitSampling(2)

      val l = dut.eu.logic
      val ring = (0 until 4).map(i =>
        s"[$i] v=${l.alignedValid(i).toBoolean} sent=${l.alignedSent(i).toBoolean} " +
        s"two=${l.alignedMem(i).twoAccess.toBoolean} second=${l.alignedMem(i).splitSecond.toBoolean}").mkString(" ")
      assert(completed.size == expectAll,
        s"only ${completed.size}/$expectAll loads completed in $n cycles (missing " +
        s"${(1 to expectAll).filterNot(completed).mkString(",")}) -- the aligned ring wedged. " +
        s"push=${l.alignedPushPtr.toInt} send=${l.alignedSendPtr.toInt} rsp=${l.alignedRspPtr.toInt} " +
        s"count=${l.alignedCount.toInt} sendHeld=${l.alignedSendHeld.toBoolean} " +
        s"sendValid=${l.alignedSendValid.toBoolean} ring: $ring")

      cd.waitSampling(4)
      assert(readInt(dut, 20) == expected(splitBase, 4),
        s"split load merged result: got ${readInt(dut, 20).toString(16)} exp ${expected(splitBase, 4).toString(16)}")
      ordBases.zipWithIndex.foreach { case (b, i) =>
        assert(readInt(dut, 21 + i) == expected(b, 4),
          f"ordinary load $i (0x$b%x): got ${readInt(dut, 21 + i).toString(16)} exp ${expected(b, 4).toString(16)}")
      }
    }
  }

  /** CAS2-style STRESS: two independent split (misaligned, line-crossing) loads from
    * the SAME instruction, issued back-to-back with no wait between them.
    *
    * CAS2's own microcode (`Microcode.scala` CAS2_ENTRY rows d0/d1) issues exactly this
    * shape unconditionally: `LOAD.sz (Rn1) -> T0` immediately followed by
    * `LOAD.sz (Rn2) -> T1`, with no dependency between them, before either compare runs
    * (see the class doc on CAS2's 10-uop crack). If Rn1 and Rn2 are BOTH misaligned to a
    * cross-line offset, each load consumes 2 of the aligned ring's 4 slots -- so the two
    * loads together can fully exhaust the ring's entire depth with TWO CONCURRENT split
    * pairs resident at once. This is a stress condition the other tests in this file
    * never reach (they only ever have one split pair in flight, alongside at most
    * ordinary single-slot entries).
    *
    * The ring's own class-level design comment (`splitMergeLine`, `LsEuPlugin.scala`)
    * argues this is safe: the single shared `splitMergeLine` register that carries slot
    * A's raw line to slot B's merge can only ever be live for ONE pair at a time, because
    * `alignedSendPtr`/`alignedRspPtr` are single pointers that walk the ring in STRICT
    * index order on both the send side and the response side -- so pair 2's slot A cannot
    * even be SENT until pair 1's slot B has been sent (send order can't skip a held
    * entry), and pair 2's slot A response cannot arrive before pair 1's slot B response
    * (responses are strict FIFO matching send order). This test builds the two-pair-
    * resident state directly and proves BOTH operands merge to the correct, distinct
    * value -- i.e. no aliasing between the two concurrent pairs' merge state, and no
    * pointer confusion between their ring slots.
    */
  test("two concurrent CAS2-style split-load pairs fully exhaust the ring without cross-corrupting operands", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val (cd, mem) = initDut(dut)
      // Two distinct 16-byte lines, far apart (not aliasing), each individually
      // misaligned to the exact LONG cross-line offset the other tests in this file
      // already proved split-eligible (base+14 -> spans ...E..11).
      val rn1Base = 0x5000L + 14   // "Rn1" operand address: spans 0x500E..0x5011
      val rn2Base = 0x9000L + 14   // "Rn2" operand address: spans 0x900E..0x9011 (different line entirely)
      preload(mem, 0x5000L, 32); preload(mem, 0x9000L, 32)
      seed(dut, cd, preg = 10, value = rn1Base)   // stands in for CAS2's Rn1
      seed(dut, cd, preg = 11, value = rn2Base)   // stands in for CAS2's Rn2

      // Track ring occupancy every cycle so we can PROVE (not just assume) that both
      // split pairs were simultaneously resident -- all 4 slots valid, all 4 flagged
      // twoAccess, 2 of them slot-A (splitSecond=false) and 2 slot-B (splitSecond=true)
      // -- at some point, rather than trusting the instruction sequence "should" do it.
      var sawFullRingBothPairs = false
      var maxValidCount = 0
      val tracking = new java.util.concurrent.atomic.AtomicBoolean(true)
      fork {
        while (tracking.get()) {
          val l = dut.eu.logic
          val validFlags = (0 until 4).map(i => l.alignedValid(i).toBoolean)
          val twoAccFlags = (0 until 4).map(i => l.alignedMem(i).twoAccess.toBoolean)
          val splitSecondFlags = (0 until 4).map(i => l.alignedMem(i).splitSecond.toBoolean)
          val nValid = validFlags.count(identity)
          maxValidCount = math.max(maxValidCount, nValid)
          val allValidAreSplit = validFlags.zip(twoAccFlags).forall { case (v, t) => !v || t }
          val nSlotA = validFlags.zip(splitSecondFlags).count { case (v, s) => v && !s }
          val nSlotB = validFlags.zip(splitSecondFlags).count { case (v, s) => v && s }
          if (nValid == 4 && allValidAreSplit && nSlotA == 2 && nSlotB == 2) {
            sawFullRingBothPairs = true
          }
          cd.waitSampling()
        }
      }

      // Issue Rn1's load then Rn2's load back-to-back (no wait between, mirroring
      // CAS2's unconditional d0/d1 microcode issue order) -- distinct pdst AND robId
      // so a wrong-operand corruption (wrong value landing on the wrong destination,
      // or a stale/aliased merge line) is directly observable.
      issueLoad(dut, cd, basePreg = 10, disp = 0, Size.LONG, pdst = 30, robId = 1)  // "T0" <- (Rn1)
      issueLoad(dut, cd, basePreg = 11, disp = 0, Size.LONG, pdst = 31, robId = 2)  // "T1" <- (Rn2)

      assert(waitCompletion(dut, cd, robId = 1), "Rn1 split load completes")
      assert(waitCompletion(dut, cd, robId = 2), "Rn2 split load completes")
      tracking.set(false)
      cd.waitSampling(4)

      assert(maxValidCount == 4,
        s"expected the ring to reach full occupancy (4 valid entries), got max=$maxValidCount " +
        "-- the two split pairs never actually overlapped, so this test did not exercise the " +
        "intended concurrent-pairs stress condition")
      assert(sawFullRingBothPairs,
        "expected a cycle where all 4 ring slots were valid, all flagged twoAccess, with " +
        "exactly 2 slot-A and 2 slot-B entries -- i.e. BOTH split pairs concurrently resident " +
        "-- but never observed it; the two loads did not truly overlap in the ring")

      val gotRn1 = readInt(dut, 30)
      val gotRn2 = readInt(dut, 31)
      val expRn1 = expected(rn1Base, 4)
      val expRn2 = expected(rn2Base, 4)
      assert(gotRn1 == expRn1,
        s"Rn1 operand: got ${gotRn1.toString(16)} exp ${expRn1.toString(16)} " +
        s"(exp-Rn2 was ${expRn2.toString(16)} -- a match against THAT would mean operand " +
        "aliasing between the two concurrent split pairs)")
      assert(gotRn2 == expRn2,
        s"Rn2 operand: got ${gotRn2.toString(16)} exp ${expRn2.toString(16)} " +
        s"(exp-Rn1 was ${expRn1.toString(16)} -- a match against THAT would mean operand " +
        "aliasing between the two concurrent split pairs)")
    }
  }
}
