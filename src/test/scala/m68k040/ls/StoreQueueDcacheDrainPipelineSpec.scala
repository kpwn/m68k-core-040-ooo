package m68k040.ls

import m68k040.{M68kParams, M68kSim, VerilatorTest}
import m68k040.cache._
import m68k040.core.ParamPlugin
import m68k040.isa.Size
import m68k040.mmu.DIdentityTranslationPlugin
import m68k040.services.DTranslationService
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.database.Database
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}

/** Test-only visibility/throttle seam between the real StoreQueue and DcachePlugin.
  *
  * Both production endpoints are Streams.  `boundaryReady` adds deliberate test
  * backpressure without buffering or weakening the real valid/ready contract.
  */
class SqDcacheDrainBoundaryPlugin(sq: StoreQueue) extends FiberPlugin {
  val logic = during build new Area {
    val ds = host[DcacheService]
    val xlate = host[DTranslationService]

    val boundaryReady = in Bool ()
    val boundary = Stream(DStoreCmd())
    boundary.valid := sq.io.drain.valid
    boundary.payload := sq.io.drain.payload
    boundary.ready := boundaryReady && ds.store.ready
    sq.io.drain.ready := boundary.ready

    ds.store.valid   := boundary.valid && boundaryReady
    ds.store.payload := boundary.payload
    sq.io.drainAck   := ds.storeAck
    sq.io.drainErr   := ds.storeErr

    val accepted = master(Flow(DStoreCmd()))
    accepted.valid   := sq.io.drain.fire
    accepted.payload := boundary.payload
    val ack = out Bool ()
    val err = out Bool ()
    val streamValid = out Bool ()
    val streamReady = out Bool ()
    val streamPayload = out(DStoreCmd())
    ack         := ds.storeAck
    err         := ds.storeErr
    streamValid := boundary.valid
    streamReady := boundary.ready
    streamPayload := boundary.payload

    // Ordinary resolved loads warm and inspect the same real D-cache arrays.
    val loadCmdIn  = slave(Stream(DLoadCmd()))
    val loadRspOut = master(Flow(DLoadRsp()))
    ds.loadCmd << loadCmdIn
    loadRspOut << ds.loadRsp

    ds.loadProbe.valid             := False
    ds.loadProbe.payload.vaddr     := 0
    ds.loadProbe.payload.token     := 0
    ds.loadProbe.payload.resolved  := False
    ds.loadProbe.payload.paddr     := 0
    ds.loadProbe.payload.size      := Size.LONG
    ds.loadProbe.payload.cacheMode := CacheMode.INHIBITED
    ds.loadProbe.payload.needsLine := False
    ds.loadProbeResolve.valid             := False
    ds.loadProbeResolve.payload.token     := 0
    ds.loadProbeResolve.payload.paddr     := 0
    ds.loadProbeResolve.payload.cacheMode := CacheMode.INHIBITED
    ds.loadProbeCancel.valid         := False
    ds.loadProbeCancel.payload.token := 0
    ds.loadProbeCancel.payload.all   := False
    ds.maintCmd.valid              := False
    ds.maintCmd.payload.push       := False
    ds.maintCmd.payload.invalidate := False
    ds.maintCmd.payload.scope      := 0
    ds.maintCmd.payload.sel        := 0
    ds.maintCmd.payload.addr       := 0

    // Dcache consumes resolved PA commands, not the standalone translation port.
    xlate.req.valid              := False
    xlate.req.payload.vpn        := 0
    xlate.req.payload.supervisor := False
    xlate.req.payload.write      := False
    xlate.req.payload.token      := 0
    xlate.rsp.ready              := True
  }
}

/** Contract tests for a future pipelined COPYBACK-hit SQ drain.
  *
  * These tests are intentionally red against the acknowledgement-gated producer at
  * commit 9f2aead: compilation is the current gate.  Once drain presentation walks
  * ahead of acknowledgement, they require exact descriptor association rather than
  * merely observing some cache activity.
  */
class StoreQueueDcacheDrainPipelineSpec extends AnyFunSuite {
  class Dut extends Component {
    val sq = new StoreQueue(8)

    val allocIn   = slave(Flow(SqAlloc()))
    val commitIn  = slave(Flow(UInt(6 bits)))
    val commitBIn = slave(Flow(UInt(6 bits)))
    val flushIn   = in Bool ()
    val robHeadIn = in UInt (6 bits)
    val robHeadValidIn = in Bool ()
    val irqPendingIn = in Bool ()
    val fwdRobIn  = in UInt (6 bits)
    val fwdAddrIn = in UInt (32 bits)
    val fwdSizeIn = in(Size())
    val fwdHitOut   = out Bool ()
    val fwdStallOut = out Bool ()
    val fwdDataOut  = out Bits (32 bits)
    val emptyOut = out Bool ()
    val fullOut  = out Bool ()
    val sqCompValidOut = out Bool ()
    val sqCompRobOut   = out UInt (6 bits)

    sq.io.alloc.valid   := allocIn.valid
    sq.io.alloc.payload := allocIn.payload
    sq.io.commit.valid   := commitIn.valid
    sq.io.commit.payload := commitIn.payload
    sq.io.commitB.valid   := commitBIn.valid
    sq.io.commitB.payload := commitBIn.payload
    sq.io.flush := flushIn
    sq.io.robHeadIn := robHeadIn
    sq.io.robHeadValidIn := robHeadValidIn
    sq.io.irqPreemptPendingIn := irqPendingIn
    sq.io.fwd.query.robId := fwdRobIn
    sq.io.fwd.query.paddr := fwdAddrIn
    sq.io.fwd.query.size  := fwdSizeIn
    sq.io.fwd.query.inhibited := False
    fwdHitOut   := sq.io.fwd.rsp.hit
    fwdStallOut := sq.io.fwd.rsp.stall
    fwdDataOut  := sq.io.fwd.rsp.data
    emptyOut := sq.io.empty
    fullOut  := sq.io.full
    sqCompValidOut := sq.io.sqCompletion.valid
    sqCompRobOut   := sq.io.sqCompletion.payload

    val db = new Database
    val host = db on (new PluginHost)
    val param  = new ParamPlugin(M68kParams())
    val xlate  = new DIdentityTranslationPlugin
    val dcache = new DcachePlugin()
    val bridge = new SqDcacheDrainBoundaryPlugin(sq)
    db.on { host.asHostOf(Seq[FiberPlugin](param, xlate, dcache, bridge)) }
  }

  private case class StoreReq(
      robId: Int,
      paddr: Long,
      data: BigInt,
      size: SpinalEnumElement[Size.type] = Size.LONG,
      mode: SpinalEnumElement[CacheMode.type] = CacheMode.COPYBACK,
      precise: Boolean = false,
      useStrbA: Boolean = false,
      nbytesA: Int = 4,
      validB: Boolean = false,
      paddrB: Long = 0,
      modeB: SpinalEnumElement[CacheMode.type] = CacheMode.COPYBACK,
      nbytesB: Int = 0)

  private case class Desc(paddr: Long, data: BigInt, size: String,
                          useStrb: Boolean, strb: Int, lineData: BigInt,
                          mode: String, precise: Boolean)

  // ---- Scala-host reference model of DcacheByteLane's store-split math ----------
  // (cache/DcacheTypes.scala storeStrbA/storeDataA/storeStrbB/storeDataB). Task #252
  // removed the SqAlloc/SQ-side strb/lineData fields -- StoreQueue now derives them
  // combinationally at drain time from (paddr low nibble, size, data), so a directed
  // test that wants to assert the drain payload's exact strb/lineData content must
  // predict them the same way the RTL does, not poke arbitrary independent values.
  private def sizeNBytes(size: SpinalEnumElement[Size.type]): Int = size match {
    case Size.BYTE => 1
    case Size.WORD => 2
    case _         => 4
  }
  // Big-endian access byte k (k=0 = MSB) of `data`, mirroring DcacheByteLane.valueByte.
  private def valueByte(size: SpinalEnumElement[Size.type], data: BigInt, k: Int): BigInt = {
    val shift = size match {
      case Size.BYTE => 0
      case Size.WORD => if (k == 0) 8 else 0
      case _         => (3 - k) * 8   // LONG
    }
    (data >> shift) & 0xff
  }
  // (strb, lineData) for the low line (highHalf=false, positions < 16) or the high
  // line (highHalf=true, positions >= 16, re-based to 0), given the SAME line-offset
  // `off` LsEuPlugin's `stOff` (= slot A's own paddr low nibble) used for both slots.
  private def splitStrbLine(off: Int, size: SpinalEnumElement[Size.type], data: BigInt,
                            highHalf: Boolean): (Int, BigInt) = {
    var strb = 0
    var line = BigInt(0)
    val nBytes = sizeNBytes(size)
    for (k <- 0 until 4) {
      val pos = off + k
      val active = (k < nBytes) && (if (highHalf) pos >= 16 else pos < 16)
      if (active) {
        val lpos = if (highHalf) pos - 16 else pos
        strb |= (1 << lpos)
        line |= (valueByte(size, data, k) << (lpos * 8))
      }
    }
    (strb, line)
  }
  private case class Event(cycle: Int, desc: Desc)
  private case class Trace(accepts: Vector[Event], acks: Vector[Event],
                           peakOutstanding: Int, arCount: Int, awCount: Int,
                           wCount: Int, missCycles: Vector[Int],
                           sqCompletions: Vector[(Int, Int)],
                           flushCycles: Vector[Int], flushFwdChecks: Int,
                           fullPipeCycles: Vector[Int], turnoverCycles: Vector[Int],
                           sameLineBypassCycles: Vector[Int],
                           missHadWaitingSource: Boolean,
                           missAcceptedSameCycle: Boolean)

  private def modeName(m: SpinalEnumElement[CacheMode.type]): String = m.toString

  // Slot A/B strb+lineData are ALWAYS the derived value regardless of `useStrbA`
  // (StoreQueue computes them unconditionally at drain -- a non-split store's strb
  // is simply don't-care downstream since DcachePlugin only consumes it when
  // useStrb is set, but the raw drain payload this test captures carries the real
  // derived bits either way). Both slots derive off slot A's own paddr low nibble.
  private def descA(s: StoreReq): Desc = {
    val (strb, lineData) = splitStrbLine((s.paddr & 0xF).toInt, s.size, s.data, highHalf = false)
    Desc(s.paddr, s.data, s.size.toString, s.useStrbA, strb, lineData,
         modeName(s.mode), s.precise)
  }

  private def descB(s: StoreReq): Desc = {
    val (strb, lineData) = splitStrbLine((s.paddr & 0xF).toInt, s.size, s.data, highHalf = true)
    Desc(s.paddrB, BigInt(0), Size.LONG.toString, useStrb = true,
         strb, lineData, modeName(s.modeB), s.precise)
  }

  private def payloadDesc(p: DStoreCmd): Desc = {
    Desc(p.paddr.toLong & 0xffffffffL, p.data.toBigInt, p.size.toEnum.toString,
         p.useStrb.toBoolean, p.strb.toInt, p.lineData.toBigInt,
         p.cacheMode.toEnum.toString, p.precise.toBoolean)
  }

  private def acceptedDesc(dut: Dut): Desc =
    payloadDesc(dut.bridge.logic.accepted.payload)

  private def initDut(dut: Dut): (ClockDomain, BehavioralMemAgent) = {
    val cd = dut.clockDomain
    cd.forkStimulus(10)
    val mem = new BehavioralMemAgent(dut.dcache.logic.axi, cd)
    dut.allocIn.valid #= false
    dut.commitIn.valid #= false
    dut.commitBIn.valid #= false
    dut.flushIn #= false
    dut.robHeadIn #= 0
    dut.robHeadValidIn #= false
    dut.irqPendingIn #= false
    dut.fwdRobIn #= 63
    dut.fwdAddrIn #= 0
    dut.fwdSizeIn #= Size.BYTE
    dut.bridge.logic.boundaryReady #= true
    dut.bridge.logic.loadCmdIn.valid #= false
    dut.bridge.logic.loadCmdIn.payload.vaddr #= 0
    dut.bridge.logic.loadCmdIn.payload.paddr #= 0
    dut.bridge.logic.loadCmdIn.payload.size #= Size.LONG
    dut.bridge.logic.loadCmdIn.payload.cacheMode #= CacheMode.COPYBACK
    dut.bridge.logic.loadCmdIn.payload.token #= 0
    cd.waitSampling(6)
    (cd, mem)
  }

  private def preloadZero(mem: BehavioralMemAgent, line: Long): Unit =
    for (i <- 0 until 16) mem.pokeByte((line & ~0xfL) + i, 0)

  private def load(dut: Dut, cd: ClockDomain, addr: Long,
                   size: SpinalEnumElement[Size.type] = Size.LONG): BigInt = {
    val p = dut.bridge.logic.loadCmdIn
    p.valid #= true
    p.payload.vaddr #= addr
    p.payload.paddr #= addr
    p.payload.size #= size
    p.payload.cacheMode #= CacheMode.COPYBACK
    p.payload.token #= ((addr >>> 4) & 0xff).toInt
    cd.waitSamplingWhere(p.valid.toBoolean && p.ready.toBoolean)
    p.valid #= false
    cd.waitSamplingWhere(dut.bridge.logic.loadRspOut.valid.toBoolean)
    dut.bridge.logic.loadRspOut.payload.data.toBigInt
  }

  private def warm(dut: Dut, cd: ClockDomain, mem: BehavioralMemAgent,
                   lines: Seq[Long]): Unit = {
    lines.map(_ & ~0xfL).distinct.foreach { line =>
      preloadZero(mem, line)
      assert(load(dut, cd, line) == 0, f"warm line 0x$line%08x was not zero")
    }
    cd.waitSampling(3)
  }

  private def alloc(dut: Dut, cd: ClockDomain, s: StoreReq): Unit = {
    val a = dut.allocIn
    a.valid #= true
    a.payload.robId #= s.robId
    a.payload.paddr #= s.paddr
    a.payload.vaddr #= s.paddr
    a.payload.data #= s.data
    a.payload.size #= s.size
    a.payload.nbytesA #= s.nbytesA
    a.payload.useStrbA #= s.useStrbA
    a.payload.validB #= s.validB
    a.payload.paddrB #= s.paddrB
    a.payload.vaddrB #= s.paddrB
    a.payload.nbytesB #= s.nbytesB
    a.payload.cacheMode #= s.mode
    a.payload.cacheModeB #= s.modeB
    a.payload.supervisor #= false
    a.payload.precise #= s.precise
    cd.waitSampling()
    a.valid #= false
  }

  private def commit(dut: Dut, cd: ClockDomain, robId: Int): Unit = {
    dut.commitIn.valid #= true
    dut.commitIn.payload #= robId
    cd.waitSampling()
    dut.commitIn.valid #= false
  }

  /** Mark every fast entry except the head committed without starting a drain. */
  private def commitTailFirst(dut: Dut, cd: ClockDomain, stores: Seq[StoreReq]): Unit =
    stores.tail.filterNot(_.precise).reverse.foreach(s => commit(dut, cd, s.robId))

  private def armHeadCommit(dut: Dut, robId: Int): Unit = {
    dut.commitIn.valid #= true
    dut.commitIn.payload #= robId
  }

  private def runTrace(dut: Dut, cd: ClockDomain, expectedAcks: Int,
                       headRob: Int, maxCycles: Int = 700,
                       flushAtOutstanding: Int = 0,
                       reqByAddr: Map[Long, StoreReq] = Map.empty): Trace = {
    val outstanding = scala.collection.mutable.Queue.empty[Desc]
    var accepts = Vector.empty[Event]
    var acks = Vector.empty[Event]
    var peak = 0
    var arCount = 0
    var awCount = 0
    var wCount = 0
    var missCycles = Vector.empty[Int]
    var sqComps = Vector.empty[(Int, Int)]
    var flushCycles = Vector.empty[Int]
    var fwdChecks = 0
    var fullPipeCycles = Vector.empty[Int]
    var turnoverCycles = Vector.empty[Int]
    var sameLineBypassCycles = Vector.empty[Int]
    var missHadWaitingSource = false
    var missAcceptedSameCycle = false
    var flushed = false
    var flushHigh = false
    var commitHigh = true
    // COPYBACK-hit acks are untagged at the architectural boundary.  Recover their
    // actual identity from the cache's S2 payload one cycle before cbHitAckReg, then
    // prove that the physical local ack matches the oldest accepted descriptor.
    // Merely dequeuing on every ack would make an in-order scoreboard tautological
    // and miss a younger-hit/older-miss completion inversion.
    var localAckIdentity = Option.empty[Desc]
    var doneAt = -1
    var cycle = 0
    armHeadCommit(dut, headRob)

    while ((doneAt < 0 || cycle - doneAt < 10) && cycle < maxCycles) {
      if (flushAtOutstanding > 0 && outstanding.nonEmpty) {
        val d = outstanding.front
        val req = reqByAddr(d.paddr)
        dut.fwdRobIn #= 63
        dut.fwdAddrIn #= req.paddr
        dut.fwdSizeIn #= req.size
      } else {
        dut.fwdAddrIn #= 0
        dut.fwdSizeIn #= Size.BYTE
      }

      sleep(1)
      // The query above describes descriptors that were already outstanding at the
      // start of this cycle.  Check it before enqueueing a same-cycle acceptance;
      // otherwise the first accepted descriptor would be compared against the
      // deliberately idle address-0 query and create a false failure.
      if (flushAtOutstanding > 0 && outstanding.nonEmpty) {
        val d = outstanding.front
        val req = reqByAddr(d.paddr)
        assert(dut.fwdHitOut.toBoolean,
          f"cycle=$cycle accepted committed store 0x${d.paddr}%08x stopped forwarding " +
          s"before its ack; ack=${dut.bridge.logic.ack.toBoolean} flush=${dut.flushIn.toBoolean} " +
          s"accepts=$accepts acks=$acks outstanding=$outstanding")
        assert(dut.fwdDataOut.toBigInt == req.data,
          f"forward data for 0x${d.paddr}%08x changed while in flight")
        fwdChecks += 1
      }
      val acceptedNow = dut.bridge.logic.accepted.valid.toBoolean
      val ackNow = dut.bridge.logic.ack.toBoolean
      if (acceptedNow) {
        val d = acceptedDesc(dut)
        accepts :+= Event(cycle, d)
        outstanding.enqueue(d)
        peak = scala.math.max(peak, outstanding.size)
      }
      if (ackNow) {
        assert(outstanding.nonEmpty, s"cycle $cycle: D-cache ack without an accepted descriptor")
        localAckIdentity.foreach { actual =>
          assert(outstanding.front == actual,
            s"cycle $cycle: local COPYBACK ack belonged to $actual, but FIFO head was ${outstanding.front}")
        }
        acks :+= Event(cycle, outstanding.dequeue())
      }
      val s2LocalHit = dut.dcache.logic.stS2Valid.toBoolean &&
        dut.dcache.logic.stS2Payload.cacheMode.toEnum == CacheMode.COPYBACK &&
        dut.dcache.logic.stS2HitVec.exists(_.toBoolean)
      localAckIdentity =
        if (s2LocalHit) Some(payloadDesc(dut.dcache.logic.stS2Payload)) else None
      if (dut.dcache.logic.axi.ar.valid.toBoolean && dut.dcache.logic.axi.ar.ready.toBoolean)
        arCount += 1
      if (dut.dcache.logic.axi.aw.valid.toBoolean && dut.dcache.logic.axi.aw.ready.toBoolean)
        awCount += 1
      if (dut.dcache.logic.axi.w.valid.toBoolean && dut.dcache.logic.axi.w.ready.toBoolean)
        wCount += 1
      if (dut.dcache.logic.storeMissDiscovered.toBoolean) {
        missCycles :+= cycle
        missHadWaitingSource = missHadWaitingSource || dut.bridge.logic.streamValid.toBoolean
        missAcceptedSameCycle = missAcceptedSameCycle || acceptedNow
      }
      if (dut.dcache.logic.s0Valid.toBoolean && dut.dcache.logic.stS1Valid.toBoolean &&
          dut.dcache.logic.stS2Valid.toBoolean && dut.dcache.logic.stS3Valid.toBoolean)
        fullPipeCycles :+= cycle
      if (acceptedNow && ackNow) turnoverCycles :+= cycle
      if (dut.dcache.logic.stS2UsesS3Line.toBoolean) sameLineBypassCycles :+= cycle
      if (dut.sqCompValidOut.toBoolean)
        sqComps :+= ((cycle, dut.sqCompRobOut.toInt))
      if (dut.flushIn.toBoolean) flushCycles :+= cycle

      if (acks.size == expectedAcks && doneAt < 0) doneAt = cycle
      cd.waitSampling()

      if (commitHigh) {
        dut.commitIn.valid #= false
        commitHigh = false
      }
      if (flushHigh) {
        dut.flushIn #= false
        flushHigh = false
      }
      if (flushAtOutstanding > 0 && !flushed &&
          outstanding.size >= flushAtOutstanding && acks.isEmpty) {
        dut.flushIn #= true
        flushHigh = true
        flushed = true
      }
      cycle += 1
    }

    assert(doneAt >= 0,
      s"saw ${accepts.size} accepts/${acks.size} acks, expected $expectedAcks; accepts=$accepts acks=$acks")
    assert(outstanding.isEmpty, s"unacknowledged accepted descriptors: $outstanding")
    Trace(accepts, acks, peak, arCount, awCount, wCount, missCycles,
          sqComps, flushCycles, fwdChecks, fullPipeCycles, turnoverCycles,
          sameLineBypassCycles, missHadWaitingSource, missAcceptedSameCycle)
  }

  private def consecutive(events: Seq[Event], count: Int): Boolean =
    events.size == count && events.map(_.cycle).sliding(2).forall {
      case Seq(a, b) => b == a + 1
    }

  private lazy val compiled = M68kSim().withVerilator.compile(new Dut)

  test("drain descriptor is stable and accepted exactly once across Stream backpressure", VerilatorTest) {
    compiled.doSim("drainBoundaryBackpressure") { dut =>
      val (cd, mem) = initDut(dut)
      val store = StoreReq(robId = 6, paddr = 0x1800L, data = BigInt("01234567", 16))
      warm(dut, cd, mem, Seq(store.paddr))
      alloc(dut, cd, store)
      dut.bridge.logic.boundaryReady #= false
      armHeadCommit(dut, store.robId)
      cd.waitSampling()
      dut.commitIn.valid #= false
      cd.waitSamplingWhere(dut.bridge.logic.streamValid.toBoolean)

      val held = payloadDesc(dut.bridge.logic.streamPayload)
      assert(held == descA(store), s"wrong descriptor entered the elastic boundary: $held")
      for (_ <- 0 until 4) {
        sleep(1)
        assert(dut.bridge.logic.streamValid.toBoolean, "valid dropped under backpressure")
        assert(!dut.bridge.logic.accepted.valid.toBoolean, "descriptor accepted without ready")
        assert(!dut.bridge.logic.ack.toBoolean, "cache acknowledged an unaccepted descriptor")
        assert(payloadDesc(dut.bridge.logic.streamPayload) == held,
          "descriptor payload changed under backpressure")
        cd.waitSampling()
      }

      dut.bridge.logic.boundaryReady #= true
      val tr = runTrace(dut, cd, expectedAcks = 1, headRob = store.robId)
      assert(tr.accepts.map(_.desc) == Vector(held), s"descriptor was not accepted exactly once: ${tr.accepts}")
      assert(tr.acks.map(_.desc) == Vector(held), s"descriptor did not receive exactly one ack: ${tr.acks}")
    }
  }

  test("warm COPYBACK drains accept and acknowledge unique descriptors at II1", VerilatorTest) {
    compiled.doSim("copybackIi1") { dut =>
      val (cd, mem) = initDut(dut)
      val stores = (0 until 8).map { i =>
        StoreReq(robId = 8 + i, paddr = 0x2000L + i * 0x10L,
                 data = BigInt(0x41000000L + i * 0x010101L))
      }
      warm(dut, cd, mem, stores.map(_.paddr))
      stores.foreach(alloc(dut, cd, _))
      commitTailFirst(dut, cd, stores)
      val tr = runTrace(dut, cd, stores.size, stores.head.robId)
      val expected = stores.map(descA).toVector

      assert(tr.accepts.map(_.desc) == expected, s"accept association/order: ${tr.accepts}")
      assert(tr.acks.map(_.desc) == expected, s"ack association/order: ${tr.acks}")
      assert(consecutive(tr.accepts, stores.size), s"COPYBACK accepts are not II1: ${tr.accepts}")
      assert(consecutive(tr.acks, stores.size), s"COPYBACK local acks are not II1: ${tr.acks}")
      assert(tr.peakOutstanding >= 4,
        s"test was vacuous: peak accepted-but-unacked depth=${tr.peakOutstanding}")
      assert(tr.fullPipeCycles.nonEmpty,
        s"S0/S1/S2/S3 were never simultaneously occupied: $tr")
      assert(tr.turnoverCycles.nonEmpty,
        s"no cycle accepted and acknowledged COPYBACK descriptors together: $tr")
      assert(dut.sq.sendPtr.toInt == 0 && dut.sq.head.toInt == 0,
        s"eight-entry drain did not wrap send/head cursors cleanly: " +
        s"send=${dut.sq.sendPtr.toInt} head=${dut.sq.head.toInt}")
      assert(tr.awCount == 0 && tr.wCount == 0,
        s"COPYBACK hits leaked AXI writes: aw=${tr.awCount} w=${tr.wCount}")
      stores.foreach { s =>
        assert(load(dut, cd, s.paddr) == s.data,
          f"cache data at 0x${s.paddr}%08x did not match its unique descriptor")
      }
    }
  }

  test("split halves stay ordered and a following same-line store merges without loss", VerilatorTest) {
    compiled.doSim("splitSameLine") { dut =>
      val (cd, mem) = initDut(dut)
      val lineA = 0x3000L
      val lineB = 0x3010L
      warm(dut, cd, mem, Seq(lineA, lineB))
      // data bytes (MSB-first, LONG): 0xaa,0xbb spill into slot A (line-offsets 14,15);
      // 0xcc,0xdd spill into slot B (offsets 0,1 of the next line) -- the SAME split
      // this test previously injected as independent strbA/lineDataA/strbB/lineDataB
      // constants, now produced by the real derivation (task #252) from one `data` word.
      val split = StoreReq(
        robId = 20, paddr = lineA + 14, data = BigInt("aabbccdd", 16), useStrbA = true,
        nbytesA = 2, validB = true, paddrB = lineB, nbytesB = 2)
      val sameLine = StoreReq(robId = 21, paddr = lineB + 4,
                              data = BigInt("11223344", 16))
      val stores = Seq(split, sameLine)
      stores.foreach(alloc(dut, cd, _))
      commitTailFirst(dut, cd, stores)
      dut.fwdRobIn #= 63
      dut.fwdAddrIn #= lineB
      dut.fwdSizeIn #= Size.WORD
      val tr = runTrace(dut, cd, expectedAcks = 3, headRob = split.robId)
      val expected = Vector(descA(split), descB(split), descA(sameLine))

      assert(tr.accepts.map(_.desc) == expected, s"split accept order: ${tr.accepts}")
      assert(tr.acks.map(_.desc) == expected, s"split ack order: ${tr.acks}")
      val ackA = tr.acks.head.cycle
      assert(tr.acks(1).cycle >= ackA,
        "slot B acknowledged before slot A")
      assert(dut.emptyOut.toBoolean, "split entry must pop once, only after slot B ack")
      assert(load(dut, cd, lineA + 14, Size.WORD) == BigInt("aabb", 16),
        "slot A bytes were lost")
      assert(load(dut, cd, lineB, Size.WORD) == BigInt("ccdd", 16),
        "slot B bytes were lost by the following same-line RMW")
      assert(load(dut, cd, lineB + 4) == BigInt("11223344", 16),
        "following same-line descriptor did not merge")
      assert(tr.sameLineBypassCycles.nonEmpty,
        "same-line data passed without exercising the required S3-to-S2 bypass")
    }
  }

  test("COPYBACK miss and WRITETHROUGH descriptors form ordered barriers", VerilatorTest) {
    compiled.doSim("missWtBarriers") { dut =>
      val (cd, mem) = initDut(dut)
      val hitA  = StoreReq(30, 0x4000L, BigInt("a0a1a2a3", 16))
      val missB = StoreReq(31, 0x5100L, BigInt("b0b1b2b3", 16))
      val hitC  = StoreReq(32, 0x4020L, BigInt("c0c1c2c3", 16))
      val hitD  = StoreReq(33, 0x4030L, BigInt("d0d1d2d3", 16))
      val hitE  = StoreReq(34, 0x4040L, BigInt("e0e1e2e3", 16))
      val wtF   = StoreReq(35, 0x4050L, BigInt("f0f1f2f3", 16), mode = CacheMode.WRITETHROUGH)
      val hitG  = StoreReq(36, 0x4060L, BigInt("01020304", 16))
      val stores = Seq(hitA, missB, hitC, hitD, hitE, wtF, hitG)
      warm(dut, cd, mem, Seq(hitA.paddr, hitC.paddr, hitD.paddr, hitE.paddr,
                             wtF.paddr, hitG.paddr))
      preloadZero(mem, missB.paddr)
      stores.foreach(alloc(dut, cd, _))
      commitTailFirst(dut, cd, stores)
      val tr = runTrace(dut, cd, stores.size, stores.head.robId)
      val expected = stores.map(descA).toVector

      assert(tr.accepts.map(_.desc) == expected, s"barrier accept order: ${tr.accepts}")
      assert(tr.acks.map(_.desc) == expected, s"barrier ack order: ${tr.acks}")
      assert(tr.missCycles.nonEmpty, "cold COPYBACK descriptor never exercised the miss path")
      val missAckCycle = tr.acks.find(_.desc == descA(missB)).get.cycle
      val missDetected = tr.missCycles.head
      assert(tr.missHadWaitingSource,
        "no younger descriptor was held valid on the COPYBACK-miss discovery cycle")
      assert(!tr.missAcceptedSameCycle,
        "a younger descriptor fired on the COPYBACK-miss discovery cycle")
      assert(!tr.accepts.exists(e => e.cycle >= missDetected && e.cycle < missAckCycle),
        s"new descriptor accepted while miss barrier was active: accepts=${tr.accepts} miss=$missDetected..$missAckCycle")
      val wtAckCycle = tr.acks.find(_.desc == descA(wtF)).get.cycle
      val postWtAccept = tr.accepts.find(_.desc == descA(hitG)).get.cycle
      assert(postWtAccept >= wtAckCycle,
        s"younger COPYBACK accepted at $postWtAccept before WT ack at $wtAckCycle")
      assert(tr.arCount >= 1, "COPYBACK miss performed no refill read")
      assert(tr.awCount >= 1 && tr.wCount >= 1, "WRITETHROUGH barrier performed no AXI write")
    }
  }

  test("a precise descriptor serializes younger drains and completes on its own ack", VerilatorTest) {
    compiled.doSim("preciseBarrier") { dut =>
      val (cd, mem) = initDut(dut)
      val fastA = StoreReq(40, 0x6000L, BigInt("10111213", 16))
      val preciseB = StoreReq(41, 0x6010L, BigInt("20212223", 16), precise = true)
      val fastC = StoreReq(42, 0x6020L, BigInt("30313233", 16))
      val stores = Seq(fastA, preciseB, fastC)
      warm(dut, cd, mem, stores.map(_.paddr))
      stores.foreach(alloc(dut, cd, _))
      commit(dut, cd, fastC.robId)
      dut.robHeadIn #= preciseB.robId
      dut.robHeadValidIn #= true
      val tr = runTrace(dut, cd, expectedAcks = stores.size, headRob = fastA.robId)
      val expected = stores.map(descA).toVector

      assert(tr.accepts.map(_.desc) == expected, s"precise accept order: ${tr.accepts}")
      assert(tr.acks.map(_.desc) == expected, s"precise ack order: ${tr.acks}")
      val preciseAck = tr.acks.find(_.desc == descA(preciseB)).get.cycle
      val youngerAccept = tr.accepts.find(_.desc == descA(fastC)).get.cycle
      assert(youngerAccept >= preciseAck,
        s"younger drain accepted at $youngerAccept before precise ack at $preciseAck")
      assert(tr.sqCompletions.map(_._2) == Vector(preciseB.robId),
        s"precise completion must occur exactly once on its terminal ack: ${tr.sqCompletions}")
    }
  }

  test("flush preserves committed accepted descriptors until their in-order acks", VerilatorTest) {
    compiled.doSim("flushCommittedInFlight") { dut =>
      val (cd, mem) = initDut(dut)
      val committed = (0 until 3).map { i =>
        StoreReq(robId = 48 + i, paddr = 0x7000L + i * 0x10L,
                 data = BigInt(0x51525354L + i * 0x1010101L))
      }
      val speculative = StoreReq(55, 0x7100L, BigInt("deadbeef", 16))
      val stores = committed :+ speculative
      warm(dut, cd, mem, stores.map(_.paddr))
      stores.foreach(alloc(dut, cd, _))
      committed.tail.reverse.foreach(s => commit(dut, cd, s.robId))
      val reqByAddr = committed.map(s => s.paddr -> s).toMap
      val tr = runTrace(dut, cd, expectedAcks = committed.size,
        headRob = committed.head.robId, flushAtOutstanding = 2, reqByAddr = reqByAddr)
      val expected = committed.map(descA).toVector

      assert(tr.flushCycles.nonEmpty,
        s"flush trigger was vacuous; peak accepted-but-unacked=${tr.peakOutstanding}")
      assert(tr.accepts.map(_.desc) == expected,
        s"flush accepted a speculative descriptor or lost a committed one: ${tr.accepts}")
      assert(tr.acks.map(_.desc) == expected,
        s"committed in-flight acks changed order across flush: ${tr.acks}")
      assert(tr.peakOutstanding >= 2, "flush did not overlap multiple accepted descriptors")
      assert(tr.flushFwdChecks >= 2,
        "committed accepted entries were not checked forwarding-visible through flush")
      assert(dut.emptyOut.toBoolean, "committed descriptors did not drain after flush")
      assert(load(dut, cd, speculative.paddr) == 0,
        "flushed speculative tail reached the cache")
    }
  }
}
