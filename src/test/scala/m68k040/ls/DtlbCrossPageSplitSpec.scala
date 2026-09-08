package m68k040.ls

import m68k040.{M68kParams, M68kSim, VerilatorTest}
import m68k040.cache.{CacheMode, DcachePlugin, DcacheService}
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
import spinal.lib.misc.database.Database
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}

/** Passive, test-only view of the real D-side translation Stream. */
class DtlbCrossPageTracePlugin extends FiberPlugin {
  val logic = during build new Area {
    val xlate = host[DTranslationService]
    val dcache = host[DcacheService]

    val reqFire  = out Bool ()
    val reqVpn   = out UInt (20 bits)
    val reqToken = out UInt (8 bits)
    val reqWrite = out Bool ()
    reqFire  := xlate.req.fire
    reqVpn   := xlate.req.payload.vpn
    reqToken := xlate.req.payload.token
    reqWrite := xlate.req.payload.write

    val rspFire      = out Bool ()
    val rspPpn       = out UInt (20 bits)
    val rspToken     = out UInt (8 bits)
    val rspFault     = out Bool ()
    val rspCacheMode = out(CacheMode())
    rspFire      := xlate.rsp.fire
    rspPpn       := xlate.rsp.payload.ppn
    rspToken     := xlate.rsp.payload.token
    rspFault     := xlate.rsp.payload.fault
    rspCacheMode := xlate.rsp.payload.cacheMode

    val cancelValid = out Bool ()
    val cancelAll   = out Bool ()
    val cancelToken = out UInt (8 bits)
    cancelValid := dcache.loadProbeCancel.valid
    cancelAll   := dcache.loadProbeCancel.payload.all
    cancelToken := dcache.loadProbeCancel.payload.token
  }
}

/** End-to-end cross-page coverage using the real DTLB and table walker.
  *
  * The identity-translator split tests cannot distinguish an accidentally reused
  * slot-A PPN from slot B's translation.  These cases map consecutive virtual pages
  * to deliberately nonadjacent physical pages and check both translation phases by
  * their full token.  The final case also requires each D-cache command to retain
  * its own page's cache mode; this intentionally exposes a split descriptor which
  * carries only one mode for both halves.
  */
class DtlbCrossPageSplitSpec extends AnyFunSuite {
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
    val trace     = new DtlbCrossPageTracePlugin
    db.on { host.asHostOf(Seq[FiberPlugin](
      param, rfInt, rfNzvc, rfX, ctrl, dtlb, dcache, cacheCtrl, eu, src, wire, trace)) }
  }

  private val Root = 0x00010000L
  private val Ptrt = 0x00011000L
  private val Pagt = 0x00012000L

  private def rootIdx(va: Long): Int = ((va >>> 25) & 0x7f).toInt
  private def ptrIdx(va: Long): Int  = ((va >>> 18) & 0x7f).toInt
  private def pageIdx(va: Long): Int = ((va >>> 12) & 0x3f).toInt

  private def pokeDescriptor(mem: AxiMemModel, addr: Long, word: Long): Unit =
    for (i <- 0 until 4)
      mem.pokeByte(addr + i, ((word >>> (8 * (3 - i))) & 0xff).toInt)

  private def buildPage(mem: AxiMemModel, va: Long, ppn: Long,
                        resident: Boolean = true, modeBits: Int = 0): Unit = {
    require((modeBits & ~0x3) == 0)
    pokeDescriptor(mem, Root + rootIdx(va) * 4, (Ptrt & 0xfffffff0L) | 0x3L)
    pokeDescriptor(mem, Ptrt + ptrIdx(va) * 4, (Pagt & 0xfffffff0L) | 0x3L)
    val residentBit = if (resident) 1L else 0L
    pokeDescriptor(mem, Pagt + pageIdx(va) * 4,
      ((ppn << 12) & 0xfffff000L) | ((modeBits.toLong & 0x3L) << 5) | residentBit)
  }

  private case class Req(vpn: Long, token: Int, write: Boolean)
  private case class Rsp(ppn: Long, token: Int, fault: Boolean, mode: String)
  private case class Cmd(vaddr: Long, paddr: Long, token: Int, mode: String)
  private case class Fault(robId: Int, addr: Long, write: Boolean,
                           size: Int, supervisor: Boolean, atc: Boolean)
  /** `cmds` holds only the LS pipe's own D-cache commands. Table-walk descriptor reads
    * are D-cache load commands too now, so they are separated out into `walkCmds` by
    * their reserved token (`DLoadToken.WALK_ITLB`/`WALK_DTLB`) -- that count is the
    * direct replacement for the walker-AXI `totalAr` these tests used to assert on.
    * `dcacheAr` likewise counts only AXI reads OUTSIDE the page-table region, so it keeps
    * meaning "how many times did the DATA lines refill". */
  private case class Run(reqs: Vector[Req], rsps: Vector[Rsp], cmds: Vector[Cmd],
                         completions: Vector[Int], faults: Vector[Fault],
                         cancelTokens: Vector[Int], dcacheAr: Int, walkCmds: Int)

  private def initDut(dut: Dut): (ClockDomain, AxiMemModel, AxiMemModel) = {
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

  private def seedPreg(dut: Dut, cd: ClockDomain, preg: Int, data: Long): Unit = {
    val s = dut.src.logic
    s.seedValid #= true
    s.seedAddr #= preg
    s.seedData #= BigInt(data & 0xffffffffL)
    cd.waitSampling()
    s.seedValid #= false
    cd.waitSampling(2)
  }

  private def readPreg(dut: Dut, preg: Int): BigInt = {
    dut.src.logic.obsIntAddr #= preg
    sleep(1)
    dut.src.logic.obsIntData.toBigInt
  }

  private def startLoad(dut: Dut, basePreg: Int, pdst: Int, robId: Int): Unit = {
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

  /** Observe from before issue through twenty quiet tail cycles.  Every sequence is
    * collected from a real handshake (or a one-cycle completion/fault Flow), so a
    * request that was never accepted cannot satisfy any association assertion. */
  private def runLoad(dut: Dut, cd: ClockDomain, basePreg: Int, pdst: Int,
                      robId: Int, expectFault: Boolean): Run = {
    startLoad(dut, basePreg, pdst, robId)
    var issued = false
    var reqs = Vector.empty[Req]
    var rsps = Vector.empty[Rsp]
    var cmds = Vector.empty[Cmd]
    var completions = Vector.empty[Int]
    var faults = Vector.empty[Fault]
    var cancelTokens = Vector.empty[Int]
    var dcacheAr = 0
    var walkCmds = 0
    var terminalAt = -1
    var cycle = 0

    while ((terminalAt < 0 || cycle - terminalAt < 20) && cycle < 700) {
      sleep(1)
      val s = dut.src.logic
      val issueFire = s.iValid.toBoolean && s.iReady.toBoolean
      if (issueFire) issued = true
      if (dut.trace.logic.reqFire.toBoolean)
        reqs :+= Req(dut.trace.logic.reqVpn.toLong,
                     dut.trace.logic.reqToken.toInt,
                     dut.trace.logic.reqWrite.toBoolean)
      if (dut.trace.logic.rspFire.toBoolean)
        rsps :+= Rsp(dut.trace.logic.rspPpn.toLong,
                     dut.trace.logic.rspToken.toInt,
                     dut.trace.logic.rspFault.toBoolean,
                     dut.trace.logic.rspCacheMode.toEnum.toString)
      if (dut.dcache.logic.loadCmdPort.valid.toBoolean &&
          dut.dcache.logic.loadCmdPort.ready.toBoolean) {
        val cmd = dut.dcache.logic.loadCmdPort.payload
        val tok = cmd.token.toInt
        if (tok == m68k040.cache.DLoadToken.WALK_ITLB ||
            tok == m68k040.cache.DLoadToken.WALK_DTLB) walkCmds += 1
        else
          cmds :+= Cmd(cmd.vaddr.toLong & 0xffffffffL,
                       cmd.paddr.toLong & 0xffffffffL,
                       tok,
                       cmd.cacheMode.toEnum.toString)
      }
      if (s.cValid.toBoolean && s.cRob.toInt == robId)
        completions :+= s.cRob.toInt
      if (s.fValid.toBoolean)
        faults :+= Fault(s.fRob.toInt, s.fAddr.toLong & 0xffffffffL,
                         s.fWrite.toBoolean, s.fSize.toInt,
                         s.fSuper.toBoolean, s.fAtc.toBoolean)
      if (dut.trace.logic.cancelValid.toBoolean && !dut.trace.logic.cancelAll.toBoolean)
        cancelTokens :+= dut.trace.logic.cancelToken.toInt
      if (dut.dcache.logic.axi.ar.valid.toBoolean &&
          dut.dcache.logic.axi.ar.ready.toBoolean) {
        // Page-table traffic now shares this AXI port, so exclude the descriptor region
        // to keep `dcacheAr` meaning what it meant before: DATA line refills.
        val a = dut.dcache.logic.axi.ar.payload.addr.toLong & 0xffffffffL
        if (a < Root || a >= Pagt + 0x1000L) dcacheAr += 1
      }

      val terminal = completions.nonEmpty && (!expectFault || faults.nonEmpty)
      if (terminal && terminalAt < 0) terminalAt = cycle
      cd.waitSampling()
      if (issueFire) s.iValid #= false
      cycle += 1
    }

    assert(issued, s"rob=$robId load was never accepted")
    assert(terminalAt >= 0, s"rob=$robId did not reach its expected terminal event")
    Run(reqs, rsps, cmds, completions, faults, cancelTokens, dcacheAr, walkCmds)
  }

  private def prepareSplit(dut: Dut, cd: ClockDomain, pageMem: AxiMemModel,
                           va: Long, ppnA: Long, ppnB: Long,
                           residentB: Boolean, modeA: Int, modeB: Int): Unit = {
    require((va & 0xfffL) == 0xffeL)
    require(ppnB != ppnA && ppnB != ppnA + 1,
      "the test requires visibly nonadjacent physical pages")
    buildPage(pageMem, va, ppnA, resident = true, modeBits = modeA)
    buildPage(pageMem, (va & ~0xfffL) + 0x1000L, ppnB,
              resident = residentB, modeBits = modeB)
    dut.ctrl.logic.mmuEnable #= true
    dut.ctrl.logic.urp #= Root
    dut.ctrl.logic.srp #= Root
    dut.cacheCtrl.logic.dcacheEnabled #= true
    cd.waitSampling(2)
  }

  private def expectedReqs(va: Long, robId: Int): Vector[Req] = {
    val vaB = (va & ~0xfffL) + 0x1000L
    Vector(Req((va >>> 12) & 0xfffffL, robId, write = false),
           Req((vaB >>> 12) & 0xfffffL, 0x40 | robId, write = false))
  }

  private def expectedData(lineA: Seq[Int], lineB: Seq[Int]): BigInt =
    Seq(lineA(14), lineA(15), lineB(0), lineB(1))
      .foldLeft(BigInt(0))((acc, b) => (acc << 8) | BigInt(b & 0xff))

  private lazy val compiled = M68kSim().withVerilator.compile(new Dut)

  test("real DTLB split uses nonadjacent PPNs and exact phase tokens", VerilatorTest) {
    compiled.doSim("nonadjacentPpns") { dut =>
      val (cd, dataMem, pageMem) = initDut(dut)
      val va    = 0x0040affeL
      val vaB   = 0x0040b000L
      val ppnA  = 0x0120aL
      val ppnB  = 0x0230bL
      val paA   = (ppnA << 12) | 0xffeL
      val paB   = ppnB << 12
      val lineA = (0 until 16).map(i => (0x31 + i * 9) & 0xff)
      val lineB = (0 until 16).map(i => (0xe7 - i * 11) & 0xff)
      prepareSplit(dut, cd, pageMem, va, ppnA, ppnB,
                   residentB = true, modeA = 0, modeB = 0)
      lineA.indices.foreach(i => dataMem.pokeByte((paA & ~0xfL) + i, lineA(i)))
      lineB.indices.foreach(i => dataMem.pokeByte(paB + i, lineB(i)))
      seedPreg(dut, cd, preg = 10, data = va)

      val run = runLoad(dut, cd, basePreg = 10, pdst = 20, robId = 7, expectFault = false)
      assert(run.reqs == expectedReqs(va, 7), s"split DTLB requests: ${run.reqs}")
      assert(run.rsps == Vector(
        Rsp(ppnA, 7, fault = false, CacheMode.WRITETHROUGH.toString),
        Rsp(ppnB, 0x40 | 7, fault = false, CacheMode.WRITETHROUGH.toString)),
        s"split DTLB responses: ${run.rsps}")
      assert(run.cmds == Vector(
        Cmd(va, paA, 7, CacheMode.WRITETHROUGH.toString),
        Cmd(vaB, paB, 0x40 | 7, CacheMode.WRITETHROUGH.toString)),
        s"split cache commands: ${run.cmds}")
      assert(run.completions == Vector(7), s"split completion count/order: ${run.completions}")
      assert(run.faults.isEmpty, s"resident split unexpectedly faulted: ${run.faults}")
      assert(run.walkCmds == 6,
        s"both cold DTLB halves must perform full walks (3 descriptor reads each), " +
        s"walk load commands=${run.walkCmds}")
      assert(run.dcacheAr == 2, s"both cold physical lines must refill once, AR=${run.dcacheAr}")
      assert(readPreg(dut, 20) == expectedData(lineA, lineB),
        f"assembled split data=0x${readPreg(dut, 20)}%08x expected=0x${expectedData(lineA, lineB)}%08x")
    }
  }

  test("slot-B translation fault reports addrB and has no cache or PRF side effect", VerilatorTest) {
    compiled.doSim("slotBFault") { dut =>
      val (cd, _, pageMem) = initDut(dut)
      val va       = 0x0040affeL
      val vaB      = 0x0040b000L
      val ppnA     = 0x0120aL
      val ppnB     = 0x0230bL
      val robId    = 11
      val pdst     = 21
      val sentinel = 0x5aa55aa5L
      prepareSplit(dut, cd, pageMem, va, ppnA, ppnB,
                   residentB = false, modeA = 0, modeB = 0)
      seedPreg(dut, cd, preg = 10, data = va)
      seedPreg(dut, cd, preg = pdst, data = sentinel)

      val run = runLoad(dut, cd, basePreg = 10, pdst = pdst,
                        robId = robId, expectFault = true)
      assert(run.reqs == expectedReqs(va, robId), s"fault split DTLB requests: ${run.reqs}")
      assert(run.rsps.map(r => (r.token, r.fault)) ==
             Vector((robId, false), (0x40 | robId, true)),
        s"fault split response phase association: ${run.rsps}")
      assert(run.rsps.head.ppn == ppnA,
        f"slot A PPN=0x${run.rsps.head.ppn}%x expected=0x$ppnA%x")
      assert(run.completions == Vector(robId),
        s"faulting split completion count/order: ${run.completions}")
      assert(run.faults == Vector(Fault(robId, vaB, write = false,
                                       size = 2, supervisor = false, atc = true)),
        s"slot-B precise ATC fault payload: ${run.faults}")
      assert(run.cmds.isEmpty,
        s"translation must finish both halves before cache launch; leaked commands: ${run.cmds}")
      assert(run.dcacheAr == 0,
        s"slot-B ATC fault must not cause physical cache traffic, AR=${run.dcacheAr}")
      assert(run.cancelTokens == Vector(robId),
        s"fault must cancel slot A's speculative VIPT probe exactly once: ${run.cancelTokens}")
      assert(run.walkCmds == 6,
        s"slot A and faulting slot B must each perform a real walk, " +
        s"walk load commands=${run.walkCmds}")
      assert(readPreg(dut, pdst) == BigInt(sentinel),
        "slot-B translation fault must not write its destination PRF")
    }
  }

  test("each split cache command retains its translated page cache mode", VerilatorTest) {
    compiled.doSim("distinctCacheModes") { dut =>
      val (cd, dataMem, pageMem) = initDut(dut)
      val va    = 0x0040affeL
      val vaB   = 0x0040b000L
      val ppnA  = 0x0120aL
      val ppnB  = 0x0230bL
      val paA   = (ppnA << 12) | 0xffeL
      val paB   = ppnB << 12
      val lineA = (0 until 16).map(i => (0x83 + i * 3) & 0xff)
      val lineB = (0 until 16).map(i => (0x19 + i * 13) & 0xff)

      // CM=01 is COPYBACK; CM=10 is INHIBITED.  Slot B must not inherit A's
      // cacheability merely because both physical addresses share one split entry.
      prepareSplit(dut, cd, pageMem, va, ppnA, ppnB,
                   residentB = true, modeA = 1, modeB = 2)
      lineA.indices.foreach(i => dataMem.pokeByte((paA & ~0xfL) + i, lineA(i)))
      lineB.indices.foreach(i => dataMem.pokeByte(paB + i, lineB(i)))
      seedPreg(dut, cd, preg = 10, data = va)

      // Slot B is cache-INHIBITED, which makes the whole split access `p4Inhibited`
      // (LsEuPlugin: `cmode === INHIBITED || (twoAccess && cmodeB === INHIBITED)`), and
      // since f5f9fe13 an inhibited load is precise: it launches only when it is the ROB
      // head. Park the head on it, exactly as the real ROB would once rob 12 retired.
      dut.wire.logic.iRobHeadIn #= 13; dut.wire.logic.iRobHeadValidIn #= true

      val run = runLoad(dut, cd, basePreg = 10, pdst = 22, robId = 13, expectFault = false)
      assert(run.reqs == expectedReqs(va, 13), s"mode split DTLB requests: ${run.reqs}")
      assert(run.rsps.map(r => (r.token, r.mode)) == Vector(
        (13, CacheMode.COPYBACK.toString),
        (0x40 | 13, CacheMode.INHIBITED.toString)),
        s"DTLB did not return distinct per-page modes: ${run.rsps}")
      assert(run.cmds == Vector(
        Cmd(va, paA, 13, CacheMode.COPYBACK.toString),
        Cmd(vaB, paB, 0x40 | 13, CacheMode.INHIBITED.toString)),
        s"split cache commands lost per-half cache mode: ${run.cmds}")
      assert(run.completions == Vector(13), s"mode split completion: ${run.completions}")
      assert(run.faults.isEmpty, s"mode split unexpectedly faulted: ${run.faults}")
      assert(readPreg(dut, 22) == expectedData(lineA, lineB),
        "distinct-mode split must still assemble bytes from both nonadjacent PPNs")
    }
  }
}
