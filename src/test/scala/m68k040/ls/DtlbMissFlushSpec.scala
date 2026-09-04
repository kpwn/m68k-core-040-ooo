package m68k040.ls

import m68k040.{M68kParams, M68kSim, VerilatorTest}
import m68k040.cache.{DTranslationCmd, DTranslationRsp, DcachePlugin, DcacheService}
import m68k040.core.ParamPlugin
import m68k040.execute.LsEuPlugin
import m68k040.execute.regfile.{RegFilePluginInt, RegFilePluginNzvc, RegFilePluginX}
import m68k040.isa.{MemOp, Size}
import m68k040.mmu.{DtlbPlugin, MmuControlPlugin}
import m68k040.services.DTranslationService
import m68k040.sim.AxiMemModel
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.database.Database
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}

private object DtlbMissTestPages {
  val Root = 0x00010000L
  val Ptrt = 0x00011000L
  val Pagt = 0x00012000L

  private def rootIdx(va: Long): Int = ((va >>> 25) & 0x7f).toInt
  private def ptrIdx(va: Long): Int  = ((va >>> 18) & 0x7f).toInt
  private def pageIdx(va: Long): Int = ((va >>> 12) & 0x3f).toInt

  private def pokeDescriptor(mem: m68k040.sim.SimMem.ByteMem, addr: Long, word: Long): Unit =
    for (i <- 0 until 4)
      mem.pokeByte(addr + i, ((word >>> (8 * (3 - i))) & 0xff).toInt)

  /** U is pre-set so these protocol tests cannot stall on an unrelated deferred
    * U/M-queue credit. */
  def buildResidentPage(mem: m68k040.sim.SimMem.ByteMem, va: Long, ppn: Long,
                        copyback: Boolean = true): Unit = {
    pokeDescriptor(mem, Root + rootIdx(va) * 4, (Ptrt & 0xfffffff0L) | 0x3L)
    pokeDescriptor(mem, Ptrt + ptrIdx(va) * 4, (Pagt & 0xfffffff0L) | 0x3L)
    val cacheMode = if (copyback) 0x20L else 0L
    pokeDescriptor(mem, Pagt + pageIdx(va) * 4,
      ((ppn << 12) & 0xfffff000L) | cacheMode | 0x8L | 0x1L)
  }

  def vpn(va: Long): Long = (va >>> 12) & 0xfffffL
  def physical(va: Long, ppn: Long): Long = (ppn << 12) | (va & 0xfffL)
  def word(image: Seq[Int], offset: Int = 0): BigInt =
    image.slice(offset, offset + 4).foldLeft(BigInt(0))((acc, b) => (acc << 8) | BigInt(b))
}

/** Direct Stream shell used only by the clean-miss serialization test. */
class DtlbMissStreamProbePlugin extends FiberPlugin {
  val logic = during build new Area {
    val xlate = host[DTranslationService]
    val reqIn  = slave(Stream(DTranslationCmd()))
    val rspOut = master(Stream(DTranslationRsp()))
    xlate.req << reqIn
    rspOut << xlate.rsp
  }
}

/** Mirrors the real FullCoreSynth backend-flush fanout: one squash pulse reaches
  * both the LSU generation counter and the DTLB speculative U/M state. */
class DtlbBackendFlushMirrorPlugin(eu: LsEuPlugin, dtlb: DtlbPlugin) extends FiberPlugin {
  val logic = during build new Area {
    dtlb.umFlush := eu.sqFlush
    val observed = out Bool ()
    observed := dtlb.umFlush
    val intWValid = out Bool ()
    val intWAddr  = out UInt (6 bits)
    val intWData  = out Bits (32 bits)
    intWValid := eu.intW.valid
    intWAddr  := eu.intW.address
    intWData  := eu.intW.data
  }
}

/** Passive, test-only visibility for D-cache sideband Flows which are functional
  * internal nets but intentionally are not synthesis-wide simPublic signals. */
class DcacheMissTracePlugin extends FiberPlugin {
  val logic = during build new Area {
    val dcache = host[DcacheService]
    val resolveValid = out Bool ()
    val resolvePaddr = out UInt (32 bits)
    val resolveToken = out UInt (8 bits)
    resolveValid := dcache.loadProbeResolve.valid
    resolvePaddr := dcache.loadProbeResolve.payload.paddr
    resolveToken := dcache.loadProbeResolve.payload.token
    val cancelValid = out Bool ()
    val cancelAll   = out Bool ()
    cancelValid := dcache.loadProbeCancel.valid
    cancelAll   := dcache.loadProbeCancel.payload.all
  }
}

/** The one-entry response slot and one walker must serialize a clean miss without
  * duplicating it or losing the resident request held immediately behind it. */
class DtlbCleanMissSerializationSpec extends AnyFunSuite {
  class Dut extends Component {
    val db = new Database
    val host = db on (new PluginHost)
    val ctrl = new MmuControlPlugin
    val dtlb = new DtlbPlugin()
    val probe = new DtlbMissStreamProbePlugin
    val walkPort = new m68k040.sim.WalkerDcacheSimIo(dtlb, "dtlbWalk")
    db.on { host.asHostOf(Seq[FiberPlugin](
      new ParamPlugin(M68kParams()), ctrl, dtlb, probe, walkPort)) }
    // No DcachePlugin in this DUT: expose the walker's DcacheService client port pair
    // as DUT IO and let `DcacheClientMemAgent` answer it (see WalkerDcacheSimIo).
  }

  private def init(dut: Dut): (ClockDomain, m68k040.sim.DcacheClientMemAgent) = {
    val cd = dut.clockDomain
    cd.forkStimulus(10)
    val mem = new m68k040.sim.DcacheClientMemAgent(dut.walkPort, cd)
    val req = dut.probe.logic.reqIn
    req.valid #= false
    req.payload.vpn #= 0
    req.payload.token #= 0
    req.payload.write #= false
    req.payload.supervisor #= false
    dut.probe.logic.rspOut.ready #= false
    dut.ctrl.logic.mmuEnable #= false
    dut.ctrl.logic.urp #= 0
    dut.ctrl.logic.srp #= 0
    cd.waitSampling(4)
    (cd, mem)
  }

  private def request(dut: Dut, cd: ClockDomain, vpn: Long, token: Int): Unit = {
    val req = dut.probe.logic.reqIn
    req.valid #= true
    req.payload.vpn #= vpn
    req.payload.token #= token
    req.payload.write #= false
    req.payload.supervisor #= false
    var fired = false
    var guard = 0
    while (!fired && guard < 400) {
      sleep(1)
      fired = req.ready.toBoolean
      cd.waitSampling()
      guard += 1
    }
    req.valid #= false
    assert(fired, s"request token=0x$token%x never fired")
  }

  private def response(dut: Dut, cd: ClockDomain): (Int, Long, Boolean) = {
    val rsp = dut.probe.logic.rspOut
    rsp.ready #= false
    var guard = 0
    while (!rsp.valid.toBoolean && guard < 400) { cd.waitSampling(); guard += 1 }
    sleep(1)
    assert(rsp.valid.toBoolean, "translation response timed out")
    val got = (rsp.payload.token.toInt, rsp.payload.ppn.toLong, rsp.payload.fault.toBoolean)
    rsp.ready #= true
    cd.waitSampling()
    rsp.ready #= false
    got
  }

  test("held direct result serializes one cold walk then restarts a resident hit", VerilatorTest) {
    M68kSim().withVerilator.compile(new Dut).doSim { dut =>
      val (cd, pageMem) = init(dut)
      import DtlbMissTestPages._

      val heldVa = 0x00411040L
      val missVa = 0x00822080L
      val hitVa  = 0x00c330c0L
      val heldPpn = 0x11111L
      val missPpn = 0x22222L
      val hitPpn  = 0x33333L
      buildResidentPage(pageMem, heldVa, heldPpn)
      buildResidentPage(pageMem, missVa, missPpn)
      buildResidentPage(pageMem, hitVa, hitPpn)
      dut.ctrl.logic.mmuEnable #= true
      dut.ctrl.logic.urp #= Root
      dut.ctrl.logic.srp #= Root
      cd.waitSampling(2)

      // Warm only the two direct-hit pages. The middle page remains a genuine cold
      // TLB miss when the measured sequence starts.
      request(dut, cd, vpn(heldVa), token = 1)
      assert(response(dut, cd) == ((1, heldPpn, false)))
      request(dut, cd, vpn(hitVa), token = 2)
      assert(response(dut, cd) == ((2, hitPpn, false)))
      cd.waitSampling(3)
      // The walker is a D-cache client now; `loadCount` counts its descriptor reads,
      // one per read, exactly as one AR per read before.
      val walkCmdsBefore = pageMem.loadCount

      val req = dut.probe.logic.reqIn
      val rsp = dut.probe.logic.rspOut
      val reqEvents = scala.collection.mutable.ArrayBuffer.empty[(Int, Int, Long)]
      val rspEvents = scala.collection.mutable.ArrayBuffer.empty[(Int, Int, Long, Boolean)]
      var cycle = 0
      def tick(): (Boolean, Boolean) = {
        sleep(1)
        val reqFire = req.valid.toBoolean && req.ready.toBoolean
        val rspFire = rsp.valid.toBoolean && rsp.ready.toBoolean
        if (reqFire) reqEvents += ((cycle, req.payload.token.toInt, req.payload.vpn.toLong))
        if (rspFire) rspEvents += ((cycle, rsp.payload.token.toInt,
          rsp.payload.ppn.toLong, rsp.payload.fault.toBoolean))
        cd.waitSampling()
        cycle += 1
        (reqFire, rspFire)
      }
      def drive(vpnValue: Long, token: Int): Unit = {
        req.valid #= true
        req.payload.vpn #= vpnValue
        req.payload.token #= token
        req.payload.write #= false
        req.payload.supervisor #= false
      }

      val heldToken = 0x31
      val missToken = 0x32
      val hitToken  = 0x33
      rsp.ready #= false
      drive(vpn(heldVa), heldToken)
      var fired = false
      while (!fired && cycle < 40) { val e = tick(); fired = e._1 }
      assert(fired, "resident A request did not enter")
      req.valid #= false
      while (!rsp.valid.toBoolean && cycle < 50) tick()
      sleep(1)
      assert(rsp.valid.toBoolean && rsp.payload.token.toInt == heldToken &&
             rsp.payload.ppn.toLong == heldPpn && !rsp.payload.fault.toBoolean,
        "held direct response A must remain stable")

      // B cannot enter while A occupies the response slot.
      drive(vpn(missVa), missToken)
      for (_ <- 0 until 3) {
        sleep(1)
        assert(!req.ready.toBoolean, "cold miss B entered behind an unconsumed A")
        assert(rsp.valid.toBoolean && rsp.payload.token.toInt == heldToken &&
               rsp.payload.ppn.toLong == heldPpn, "held A changed under backpressure")
        tick()
      }

      // Pop A and accept B on one edge, then hold resident C behind the active walk.
      rsp.ready #= true
      sleep(1)
      assert(req.ready.toBoolean && req.valid.toBoolean && rsp.valid.toBoolean,
        "A-pop/B-push accept-last edge")
      val popPush = tick()
      assert(popPush == ((true, true)))
      drive(vpn(hitVa), hitToken)
      var blockedBehindMiss = 0
      fired = false
      while (!fired && cycle < 400) {
        sleep(1)
        if (!req.ready.toBoolean) blockedBehindMiss += 1
        val e = tick()
        fired = e._1
      }
      assert(fired, "resident C never restarted after B's walk")
      req.valid #= false
      while (rspEvents.size < 3 && cycle < 420) tick()

      assert(reqEvents.map(e => (e._2, e._3)).toSeq == Seq(
        (heldToken, vpn(heldVa)), (missToken, vpn(missVa)), (hitToken, vpn(hitVa))),
        s"request association/order: $reqEvents")
      assert(rspEvents.map(e => (e._2, e._3, e._4)).toSeq == Seq(
        (heldToken, heldPpn, false), (missToken, missPpn, false), (hitToken, hitPpn, false)),
        s"response association/order: $rspEvents")
      assert(blockedBehindMiss > 0, "younger resident C was not actually held behind the walk")
      assert(pageMem.loadCount - walkCmdsBefore == 3,
        s"exactly one three-level clean walk expected, descriptor reads=" +
        s"${pageMem.loadCount - walkCmdsBefore}")
      val missRspCycle = rspEvents.find(_._2 == missToken).get._1
      val hitReqCycle  = reqEvents.find(_._2 == hitToken).get._1
      val hitRspCycle  = rspEvents.find(_._2 == hitToken).get._1
      assert(hitReqCycle == missRspCycle,
        s"resident C must restart on B response turnover: Creq=$hitReqCycle Brsp=$missRspCycle")
      assert(hitRspCycle == hitReqCycle + 1,
        s"resident C must return one cycle after restart: req=$hitReqCycle rsp=$hitRspCycle")
    }
  }
}

/** Full LSU/DTLB/VIPT regression for a backend squash during an active walk. */
class DtlbFlushReuseSpec extends AnyFunSuite {
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
    val trace     = new DTranslationTracePlugin
    val flushWire = new DtlbBackendFlushMirrorPlugin(eu, dtlb)
    val cacheTrace = new DcacheMissTracePlugin
    db.on { host.asHostOf(Seq[FiberPlugin](param, rfInt, rfNzvc, rfX, ctrl, dtlb,
      dcache, cacheCtrl, eu, src, wire, trace, flushWire, cacheTrace)) }
  }

  private def init(dut: Dut): (ClockDomain, AxiMemModel, AxiMemModel) = {
    val cd = dut.clockDomain
    cd.forkStimulus(10)
    val dataMem = AxiMemModel.attachFull(dut.dcache.logic.axi, cd)
    // The DTLB walker now reaches memory through the D-cache, so the page table must
    // live in the D-SIDE memory rather than a private walker image. Aliasing the old
    // name onto it keeps every buildPage/pokeDescriptor call site below unchanged.
    val pageMem = dataMem
    val s = dut.src.logic
    s.iValid #= false
    s.iMemOp #= MemOp.LOAD
    s.iSize #= Size.LONG
    s.iPsrcA #= 0; s.iPsrcAValid #= false
    s.iPsrcB #= 0; s.iPsrcBValid #= false
    s.iImm #= 0
    s.iPdst #= 0; s.iPdstValid #= false
    s.iRobId #= 0
    s.iStkPush #= false
    s.iLeaAddr #= false
    s.iSqCommitValid #= false
    s.iSqCommitRob #= 0
    s.iSqFlush #= false
    s.seedValid #= false
    s.seedAddr #= 0
    s.seedData #= 0
    s.obsIntAddr #= 0
    dut.ctrl.logic.mmuEnable #= false
    dut.ctrl.logic.urp #= 0
    dut.ctrl.logic.srp #= 0
    dut.cacheCtrl.logic.dcacheEnabled #= false
    dut.wire.logic.iRobHeadIn #= 0
    dut.wire.logic.iRobHeadValidIn #= false
    cd.waitSampling(80)
    (cd, dataMem, pageMem)
  }

  private def seed(dut: Dut, cd: ClockDomain, preg: Int, data: Long): Unit = {
    val s = dut.src.logic
    s.seedValid #= true
    s.seedAddr #= preg
    s.seedData #= BigInt(data & 0xffffffffL)
    cd.waitSampling()
    s.seedValid #= false
    cd.waitSampling(2)
  }

  private def driveLoad(dut: Dut, basePreg: Int, pdst: Int, robId: Int): Unit = {
    val s = dut.src.logic
    s.iValid #= true
    s.iMemOp #= MemOp.LOAD
    s.iSize #= Size.LONG
    s.iPsrcA #= basePreg
    s.iPsrcAValid #= true
    s.iPsrcB #= 0
    s.iPsrcBValid #= false
    s.iImm #= 0
    s.iPdst #= pdst
    s.iPdstValid #= true
    s.iRobId #= robId
    s.iStkPush #= false
    s.iLeaAddr #= false
  }

  private def issueAndWait(dut: Dut, cd: ClockDomain,
                           basePreg: Int, pdst: Int, robId: Int): Unit = {
    driveLoad(dut, basePreg, pdst, robId)
    cd.waitSamplingWhere(dut.src.logic.iReady.toBoolean)
    dut.src.logic.iValid #= false
    var done = false
    var guard = 0
    while (!done && guard < 500) {
      sleep(1)
      done = dut.src.logic.cValid.toBoolean && dut.src.logic.cRob.toInt == robId
      if (!done) cd.waitSampling()
      guard += 1
    }
    assert(done, s"warm-up rob=$robId did not complete")
  }

  private def readPreg(dut: Dut, preg: Int): BigInt = {
    dut.src.logic.obsIntAddr #= preg
    sleep(1)
    dut.src.logic.obsIntData.toBigInt
  }

  test("active-walk flush poisons old epoch before ROB and pdst reuse", VerilatorTest) {
    M68kSim().withVerilator.compile(new Dut).doSim { dut =>
      val (cd, dataMem, pageMem) = init(dut)
      import DtlbMissTestPages._

      val oldVa = 0x00a26040L
      val newVa = 0x00611080L
      val oldPpn = 0x42226L
      val newPpn = 0x31111L
      val oldPa = physical(oldVa, oldPpn)
      val newPa = physical(newVa, newPpn)
      val oldImage = (0 until 16).map(i => (0x20 + i * 3) & 0xff)
      val newImage = (0 until 16).map(i => (0xd0 - i * 7) & 0xff)
      buildResidentPage(pageMem, oldVa, oldPpn)
      buildResidentPage(pageMem, newVa, newPpn)
      oldImage.indices.foreach(i => dataMem.pokeByte(oldPa + i, oldImage(i)))
      newImage.indices.foreach(i => dataMem.pokeByte(newPa + i, newImage(i)))

      dut.ctrl.logic.mmuEnable #= true
      dut.ctrl.logic.urp #= Root
      dut.ctrl.logic.srp #= Root
      dut.cacheCtrl.logic.dcacheEnabled #= true
      seed(dut, cd, preg = 10, data = oldVa)
      seed(dut, cd, preg = 11, data = newVa)

      // Only the reuse target is warm in both the TLB and L1D. The killed access is
      // a true cold walk and must never reach a resolved cache command.
      issueAndWait(dut, cd, basePreg = 11, pdst = 20, robId = 4)
      cd.waitSampling(5)
      assert(readPreg(dut, 20) == word(newImage), "reuse target warm-up data")

      val robId = 22
      val pdst = 30
      val sentinel = 0x5aa55aa5L
      seed(dut, cd, pdst, sentinel)
      // Walker descriptor reads are D-cache load commands now; `walkerArCycles` below
      // counts them by owner, which is the same one-per-descriptor-read cadence.
      dataMem.stats.reset()

      val issueCycles = scala.collection.mutable.ArrayBuffer.empty[Int]
      val reqEvents = scala.collection.mutable.ArrayBuffer.empty[(Int, Long, Int)]
      val rspEvents = scala.collection.mutable.ArrayBuffer.empty[(Int, Long, Int, Boolean)]
      val probeEvents = scala.collection.mutable.ArrayBuffer.empty[(Int, Long, Int)]
      val resolveEvents = scala.collection.mutable.ArrayBuffer.empty[(Int, Long, Int)]
      val cancelAllCycles = scala.collection.mutable.ArrayBuffer.empty[Int]
      val walkerCancelAllCycles = scala.collection.mutable.ArrayBuffer.empty[Int]
      var dataArOutsideTables = 0
      val cmdEvents = scala.collection.mutable.ArrayBuffer.empty[(Int, Long, Long, Int, Boolean)]
      val completions = scala.collection.mutable.ArrayBuffer.empty[(Int, Int)]
      val intWrites = scala.collection.mutable.ArrayBuffer.empty[(Int, Int, BigInt)]
      val wbEvents = scala.collection.mutable.ArrayBuffer.empty[(Int, Int, BigInt, Boolean)]
      val faults = scala.collection.mutable.ArrayBuffer.empty[(Int, Long)]
      val walkerArCycles = scala.collection.mutable.ArrayBuffer.empty[Int]
      var cycle = 0

      def tick(): Boolean = {
        sleep(1)
        val s = dut.src.logic
        val issueFire = s.iValid.toBoolean && s.iReady.toBoolean
        if (issueFire) issueCycles += cycle
        if (dut.trace.logic.reqFire.toBoolean)
          reqEvents += ((cycle, dut.trace.logic.reqVpn.toLong, dut.trace.logic.reqToken.toInt))
        if (dut.trace.logic.rspFire.toBoolean)
          rspEvents += ((cycle, dut.trace.logic.rspPpn.toLong,
            dut.trace.logic.rspToken.toInt, dut.trace.logic.rspFault.toBoolean))
        if (dut.dcache.logic.loadProbePort.valid.toBoolean &&
            dut.dcache.logic.loadProbePort.ready.toBoolean)
          probeEvents += ((cycle,
            dut.dcache.logic.loadProbePort.payload.vaddr.toLong & 0xffffffffL,
            dut.dcache.logic.loadProbePort.payload.token.toInt))
        if (dut.cacheTrace.logic.resolveValid.toBoolean)
          resolveEvents += ((cycle,
            dut.cacheTrace.logic.resolvePaddr.toLong & 0xffffffffL,
            dut.cacheTrace.logic.resolveToken.toInt))
        // Qualified by the D-cache load-port OWNER. Handing that port to a table walker
        // now also asserts `probeCancelAll` -- mandatory, and the reason is a real
        // deadlock, not tidiness: a resident early-VIPT probe token blocks any load
        // command that does not own it, so a walker command behind four resident tokens
        // would never be accepted and neither side would advance. Those hand-over pulses
        // are a genuine, expected new source of cancel-all (one per descriptor read of a
        // cold walk) and are NOT what this assertion is about, which is "the SQUASH
        // produced exactly one".
        if (dut.cacheTrace.logic.cancelValid.toBoolean &&
            dut.cacheTrace.logic.cancelAll.toBoolean) {
          if (dut.eu.logic.ldOwner.toInt == 0) cancelAllCycles += cycle
          else walkerCancelAllCycles += cycle
        }
        // `cmdEvents` is the LS pipe's own command stream -- the thing this test's
        // "killed translation leaked into cache command stream" assertion is about.
        // Table-walk descriptor reads share this port now and are counted separately in
        // `walkerArCycles` below, so they are excluded here by their reserved token.
        if (dut.dcache.logic.loadCmdPort.valid.toBoolean &&
            dut.dcache.logic.loadCmdPort.ready.toBoolean &&
            dut.dcache.logic.loadCmdPort.payload.token.toInt !=
              m68k040.cache.DLoadToken.WALK_DTLB &&
            dut.dcache.logic.loadCmdPort.payload.token.toInt !=
              m68k040.cache.DLoadToken.WALK_ITLB)
          cmdEvents += ((cycle,
            dut.dcache.logic.loadCmdPort.payload.vaddr.toLong & 0xffffffffL,
            dut.dcache.logic.loadCmdPort.payload.paddr.toLong & 0xffffffffL,
            dut.dcache.logic.loadCmdPort.payload.token.toInt,
            dut.dcache.logic.useEarlyProbe.toBoolean))
        if (s.cValid.toBoolean) completions += ((cycle, s.cRob.toInt))
        if (dut.flushWire.logic.intWValid.toBoolean)
          intWrites += ((cycle, dut.flushWire.logic.intWAddr.toInt,
            dut.flushWire.logic.intWData.toBigInt))
        if (dut.eu.logic.wbObs.valid.toBoolean)
          wbEvents += ((cycle, dut.eu.logic.wbObs.robId.toInt,
            dut.eu.logic.wbObs.result.toBigInt, dut.eu.logic.wbObs.intWrite.toBoolean))
        if (s.fValid.toBoolean)
          faults += ((s.fRob.toInt, s.fAddr.toLong & 0xffffffffL))
        // The walker's descriptor reads are D-cache LOAD COMMANDS now, one per read,
        // exactly as one AR per read before -- identified by the walker's reserved token
        // rather than by an arbiter-internal signal, so this does not depend on any
        // `simPublic` that is not already part of the D-cache's own public payload.
        if (dut.dcache.logic.loadCmdPort.valid.toBoolean &&
            dut.dcache.logic.loadCmdPort.ready.toBoolean &&
            dut.dcache.logic.loadCmdPort.payload.token.toInt ==
              m68k040.cache.DLoadToken.WALK_DTLB)
          walkerArCycles += cycle
        if (dut.dcache.logic.axi.ar.valid.toBoolean &&
            dut.dcache.logic.axi.ar.ready.toBoolean) {
          val a = dut.dcache.logic.axi.ar.payload.addr.toLong & 0xffffffffL
          if (a < DtlbMissTestPages.Root || a >= DtlbMissTestPages.Pagt + 0x1000L)
            dataArOutsideTables += 1
        }
        cd.waitSampling()
        cycle += 1
        issueFire
      }

      // Old epoch request: let the walk become observably active, then squash it.
      driveLoad(dut, basePreg = 10, pdst = pdst, robId = robId)
      var oldIssue = false
      while (!oldIssue && cycle < 80) oldIssue = tick()
      assert(oldIssue, "old-epoch load was not accepted")
      dut.src.logic.iValid #= false
      while ((reqEvents.isEmpty || walkerArCycles.isEmpty) && cycle < 160) tick()
      assert(reqEvents.nonEmpty && reqEvents.head._3 == robId,
        s"old translation command missing/wrong: $reqEvents")
      assert(walkerArCycles.nonEmpty && dut.dtlb.logic.missPending.toBoolean,
        "flush must be timed inside the active cold walk")

      val flushCycle = cycle
      dut.src.logic.iSqFlush #= true
      sleep(1)
      assert(dut.flushWire.logic.observed.toBoolean, "DTLB did not receive backend flush")
      tick()
      dut.src.logic.iSqFlush #= false
      assert(cancelAllCycles.contains(flushCycle),
        s"LSU flush did not cancel the old VIPT token: $cancelAllCycles")
      assert(readPreg(dut, pdst) == BigInt(sentinel),
        "killed load wrote its destination on the flush edge")

      // Reuse the exact ROB id and physical destination immediately. It may queue in
      // P1/P2, but its epoch-1 translation cannot attach to the old walk response.
      driveLoad(dut, basePreg = 11, pdst = pdst, robId = robId)
      var newIssue = false
      while (!newIssue && cycle < 240) newIssue = tick()
      assert(newIssue, "new-epoch reused load was not accepted")
      dut.src.logic.iValid #= false
      while ((completions.isEmpty || intWrites.isEmpty || wbEvents.isEmpty ||
              cmdEvents.isEmpty || rspEvents.size < 2) && cycle < 600) tick()
      for (_ <- 0 until 20) tick() // catch any late stale side effect

      val oldToken = robId
      val newToken = 0x80 | robId
      assert(issueCycles.size == 2, s"exactly old+reused issues expected: $issueCycles")
      assert(reqEvents.map(e => (e._2, e._3)).toSeq == Seq(
        (vpn(oldVa), oldToken), (vpn(newVa), newToken)),
        s"epoch-tagged request association: $reqEvents")
      assert(rspEvents.map(e => (e._2, e._3, e._4)).toSeq == Seq(
        (oldPpn, oldToken, false), (newPpn, newToken, false)),
        s"old response must drain and new response must remain distinct: $rspEvents")
      assert(probeEvents.map(e => (e._2, e._3)).toSeq == Seq(
        (oldVa, robId), (newVa, robId)),
        s"VIPT ROB-token reuse/cancel sequence: $probeEvents")
      assert(cancelAllCycles == Seq(flushCycle),
        s"exactly one cancel-all pulse expected on squash: $cancelAllCycles")
      // Non-vacuity for the qualification just above: the walker hand-overs really do
      // pulse cancel-all, so the split is measuring something rather than hiding it.
      assert(walkerCancelAllCycles.nonEmpty,
        "expected the walker's load-port hand-overs to assert cancel-all as well")
      assert(resolveEvents.map(e => (e._2, e._3)).toSeq == Seq((newPa, robId)),
        s"stale response produced a probe resolve, or new resolve missing: $resolveEvents")
      assert(cmdEvents.map(e => (e._2, e._3, e._4)).toSeq == Seq((newVa, newPa, robId)),
        s"killed translation leaked into cache command stream: $cmdEvents")
      assert(cmdEvents.forall(_._5), s"reused resident hit did not consume VIPT result: $cmdEvents")
      assert(completions.size == 1 && completions.head._2 == robId,
        s"stale/reused completion count or ROB mismatch: $completions")
      assert(intWrites.map(e => (e._2, e._3)).toSeq == Seq((pdst, word(newImage))),
        s"stale/reused integer write stream: $intWrites")
      assert(wbEvents.map(e => (e._2, e._3, e._4)).toSeq ==
        Seq((robId, word(newImage), true)), s"stale/reused wbObs stream: $wbEvents")
      assert(faults.isEmpty, s"flush/reuse path raised faults: $faults")
      assert(walkerArCycles.size == 3,
        s"exactly one old-epoch three-level walk expected: descriptor reads=$walkerArCycles")
      // Descriptor reads now share this AXI port, so the region check is what preserves
      // this assertion's meaning: "neither the old access nor the reused L1 hit reached
      // memory". Page-table traffic is expected here and is counted separately above.
      assert(dataArOutsideTables == 0,
        s"old access or reused L1 hit unexpectedly reached data AXI: AR=$dataArOutsideTables")
      val oldReqCycle = reqEvents.find(_._3 == oldToken).get._1
      val oldRspCycle = rspEvents.find(_._3 == oldToken).get._1
      val newReqCycle = reqEvents.find(_._3 == newToken).get._1
      val newRspCycle = rspEvents.find(_._3 == newToken).get._1
      assert(oldReqCycle < flushCycle && flushCycle < oldRspCycle,
        s"flush was not inside the old walk: req=$oldReqCycle flush=$flushCycle rsp=$oldRspCycle")
      assert(newReqCycle >= oldRspCycle,
        s"new generation entered before stale response drained: newReq=$newReqCycle oldRsp=$oldRspCycle")
      assert(newRspCycle == newReqCycle + 1,
        s"resident reused response latency: req=$newReqCycle rsp=$newRspCycle")
      assert(completions.head._1 > cmdEvents.head._1,
        s"completion preceded the only legal cache command: comp=$completions cmd=$cmdEvents")
      assert(readPreg(dut, pdst) == word(newImage),
        "only the new-epoch load may update the reused physical destination")
    }
  }
}
