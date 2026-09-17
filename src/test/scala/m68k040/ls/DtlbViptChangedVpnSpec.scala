package m68k040.ls

import m68k040.{M68kParams, M68kSim, VerilatorTest}
import m68k040.cache.{CacheMode, DcachePlugin}
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

/** Test-only passive view of the tagged D-side translation Streams.  Looking at
  * `fire`, rather than treating `ready` as a response-valid bit, makes the cadence
  * and association checks below exercise the real elastic protocol. */
class DTranslationTracePlugin extends FiberPlugin {
  val logic = during build new Area {
    val xlate = host[DTranslationService]

    val reqValid = out Bool ()
    val reqReady = out Bool ()
    val reqFire  = out Bool ()
    val reqVpn   = out UInt (20 bits)
    val reqToken = out UInt (m68k040.cache.DTranslationToken.Width bits)
    reqValid := xlate.req.valid
    reqReady := xlate.req.ready
    reqFire  := xlate.req.fire
    reqVpn   := xlate.req.payload.vpn
    reqToken := xlate.req.payload.token

    val rspValid     = out Bool ()
    val rspReady     = out Bool ()
    val rspFire      = out Bool ()
    val rspPpn       = out UInt (20 bits)
    val rspToken     = out UInt (m68k040.cache.DTranslationToken.Width bits)
    val rspFault     = out Bool ()
    val rspCacheMode = out(CacheMode())
    rspValid     := xlate.rsp.valid
    rspReady     := xlate.rsp.ready
    rspFire      := xlate.rsp.fire
    rspPpn       := xlate.rsp.payload.ppn
    rspToken     := xlate.rsp.payload.token
    rspFault     := xlate.rsp.payload.fault
    rspCacheMode := xlate.rsp.payload.cacheMode
  }
}

/** Changed-VPN resident-hit gate for the real DTLB + LS-EU + VIPT D-cache path.
  *
  * Two pages deliberately differ in VPN, PPN, TLB bank, cache mode, virtual set,
  * and data.  After both translations and L1 lines are warm, eight alternating
  * loads must traverse every hot-path boundary at II=1.  The request/response and
  * cache tokens are checked exactly, so accepting traffic is not enough: a stale
  * registered translation cannot attach to the next ROB id.  A final non-resident
  * access checks that the tagged fault response still completes precisely without
  * a cache command or destination write. */
class DtlbViptChangedVpnSpec extends AnyFunSuite {
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
                        resident: Boolean, copyback: Boolean): Unit = {
    pokeDescriptor(mem, Root + rootIdx(va) * 4, (Ptrt & 0xfffffff0L) | 0x3L)
    pokeDescriptor(mem, Ptrt + ptrIdx(va) * 4, (Pagt & 0xfffffff0L) | 0x3L)
    val cm       = if (copyback) 0x20L else 0L
    val residentBit = if (resident) 1L else 0L
    pokeDescriptor(mem, Pagt + pageIdx(va) * 4,
      ((ppn << 12) & 0xfffff000L) | cm | residentBit)
  }

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
    cd.waitSampling(80) // integer PRF reset sweep
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

  private def driveLoad(dut: Dut, basePreg: Int, displacement: Int,
                        pdst: Int, robId: Int): Unit = {
    val s = dut.src.logic
    s.iValid #= true
    s.iMemOp #= MemOp.LOAD
    s.iSize #= Size.LONG
    s.iPsrcA #= basePreg
    s.iPsrcAValid #= true
    s.iPsrcB #= 0
    s.iPsrcBValid #= false
    s.iImm #= BigInt(displacement & 0xffffffffL)
    s.iPdst #= pdst
    s.iPdstValid #= true
    s.iRobId #= robId
    s.iStkPush #= false
    s.iLeaAddr #= false
  }

  private def issueLoad(dut: Dut, cd: ClockDomain, basePreg: Int,
                        displacement: Int, pdst: Int, robId: Int): Unit = {
    driveLoad(dut, basePreg, displacement, pdst, robId)
    cd.waitSamplingWhere(dut.src.logic.iReady.toBoolean)
    dut.src.logic.iValid #= false
  }

  private def waitNormalCompletion(dut: Dut, cd: ClockDomain,
                                   robId: Int, maxCycles: Int = 400): Unit = {
    var seen = false
    var cycles = 0
    while (!seen && cycles < maxCycles) {
      sleep(1)
      if (dut.src.logic.cValid.toBoolean && dut.src.logic.cRob.toInt == robId) {
        assert(!dut.src.logic.fValid.toBoolean,
          s"resident warm-up rob=$robId completed as a translation fault")
        seen = true
      }
      if (!seen) cd.waitSampling()
      cycles += 1
    }
    assert(seen, s"resident warm-up rob=$robId did not complete")
  }

  private def readPreg(dut: Dut, preg: Int): BigInt = {
    dut.src.logic.obsIntAddr #= preg
    sleep(1)
    dut.src.logic.obsIntData.toBigInt
  }

  private def word(image: Seq[Int], offset: Int): BigInt =
    image.slice(offset, offset + 4).foldLeft(BigInt(0))((acc, b) => (acc << 8) | BigInt(b))

  private def consecutive(xs: Seq[Int], count: Int): Boolean =
    xs.size == count && xs.sliding(2).forall { case Seq(a, b) => b == a + 1 }

  test("resident changed-VPN DTLB and VIPT hits retain exact tokens at II=1", VerilatorTest) {
    M68kSim().withVerilator.compile(new Dut).doSim { dut =>
      val (cd, dataMem, pageMem) = initDut(dut)

      // Different low VPN bits select different DTLB banks.  Different virtual-set
      // offsets prevent a cache-set collision, and nonidentity PPNs make stale PA
      // association immediately visible in both loadCmd and returned data.
      val vaLineA = 0x0040A040L
      val vaLineB = 0x0080B080L
      val ppnA    = 0x0120AL
      val ppnB    = 0x0230BL
      val paLineA = (ppnA << 12) | (vaLineA & 0xfffL)
      val paLineB = (ppnB << 12) | (vaLineB & 0xfffL)
      val faultVa = 0x00C0C0C0L
      val imageA  = (0 until 16).map(i => (0x11 + i * 7) & 0xff)
      val imageB  = (0 until 16).map(i => (0xe0 - i * 5) & 0xff)

      buildPage(pageMem, vaLineA, ppnA, resident = true, copyback = true)
      buildPage(pageMem, vaLineB, ppnB, resident = true, copyback = false)
      buildPage(pageMem, faultVa, ppn = 0, resident = false, copyback = false)
      imageA.indices.foreach(i => dataMem.pokeByte(paLineA + i, imageA(i)))
      imageB.indices.foreach(i => dataMem.pokeByte(paLineB + i, imageB(i)))

      dut.ctrl.logic.mmuEnable #= true
      dut.ctrl.logic.urp #= Root
      dut.ctrl.logic.srp #= Root
      dut.cacheCtrl.logic.dcacheEnabled #= true
      seed(dut, cd, preg = 10, data = vaLineA)
      seed(dut, cd, preg = 11, data = vaLineB)

      // Fill both DTLB entries and both L1 lines before the measured interval.
      issueLoad(dut, cd, basePreg = 10, displacement = 0, pdst = 20, robId = 8)
      waitNormalCompletion(dut, cd, robId = 8)
      issueLoad(dut, cd, basePreg = 11, displacement = 0, pdst = 21, robId = 9)
      waitNormalCompletion(dut, cd, robId = 9)
      cd.waitSampling(6)
      assert(readPreg(dut, 20) == word(imageA, 0), "page A warm-up data/translation")
      assert(readPreg(dut, 21) == word(imageB, 0), "page B warm-up data/translation")

      case class Load(basePreg: Int, va: Long, pa: Long, ppn: Long,
                      cacheMode: String, pdst: Int, robId: Int, expected: BigInt)
      val loads = (0 until 8).map { i =>
        val useA = (i & 1) == 0
        val off  = (i / 2) * 4
        if (useA)
          Load(10, vaLineA + off, paLineA + off, ppnA, CacheMode.COPYBACK.toString,
               pdst = 24 + i, robId = 16 + i, expected = word(imageA, off))
        else
          Load(11, vaLineB + off, paLineB + off, ppnB, CacheMode.WRITETHROUGH.toString,
               pdst = 24 + i, robId = 16 + i, expected = word(imageB, off))
      }
      val expectedRobs   = loads.map(_.robId)
      val expectedTokens = expectedRobs // initial epoch=0, ordinary split phase=0
      val expectedVpns   = loads.map(l => (l.va >>> 12) & 0xfffffL)

      val issueCycles     = scala.collection.mutable.ArrayBuffer.empty[Int]
      val reqCycles       = scala.collection.mutable.ArrayBuffer.empty[Int]
      val reqVpns         = scala.collection.mutable.ArrayBuffer.empty[Long]
      val reqTokens       = scala.collection.mutable.ArrayBuffer.empty[Int]
      val rspCycles       = scala.collection.mutable.ArrayBuffer.empty[Int]
      val rspPpns         = scala.collection.mutable.ArrayBuffer.empty[Long]
      val rspTokens       = scala.collection.mutable.ArrayBuffer.empty[Int]
      val rspFaults       = scala.collection.mutable.ArrayBuffer.empty[Boolean]
      val rspCacheModes   = scala.collection.mutable.ArrayBuffer.empty[String]
      val probeCycles     = scala.collection.mutable.ArrayBuffer.empty[Int]
      val probeVpns       = scala.collection.mutable.ArrayBuffer.empty[Long]
      val probeTokens     = scala.collection.mutable.ArrayBuffer.empty[Int]
      val parallelCycles  = scala.collection.mutable.ArrayBuffer.empty[Int]
      val enqCycles       = scala.collection.mutable.ArrayBuffer.empty[Int]
      val cmdCycles       = scala.collection.mutable.ArrayBuffer.empty[Int]
      val cmdVas          = scala.collection.mutable.ArrayBuffer.empty[Long]
      val cmdPas          = scala.collection.mutable.ArrayBuffer.empty[Long]
      val cmdTokens       = scala.collection.mutable.ArrayBuffer.empty[Int]
      val earlyUseCycles  = scala.collection.mutable.ArrayBuffer.empty[Int]
      val completionCycles = scala.collection.mutable.ArrayBuffer.empty[Int]
      val completionRobs   = scala.collection.mutable.ArrayBuffer.empty[Int]
      val residentFaults   = scala.collection.mutable.ArrayBuffer.empty[(Int, Long)]

      // The walker's descriptor reads are D-cache load commands now, so "did a walk
      // happen" is counted by their reserved token instead of by walker-AXI AR beats.
      var walkCmds = 0
      driveLoad(dut, loads.head.basePreg,
        (loads.head.va - vaLineA).toInt, loads.head.pdst, loads.head.robId)
      var nextIssue = 0
      var cycle = 0
      while (completionRobs.size < loads.size && cycle < 400) {
        sleep(1)
        val s = dut.src.logic
        val issueFire = s.iValid.toBoolean && s.iReady.toBoolean
        if (issueFire) issueCycles += cycle
        if (dut.trace.logic.reqFire.toBoolean) {
          reqCycles += cycle
          reqVpns += dut.trace.logic.reqVpn.toLong
          reqTokens += dut.trace.logic.reqToken.toInt
        }
        if (dut.trace.logic.rspFire.toBoolean) {
          rspCycles += cycle
          rspPpns += dut.trace.logic.rspPpn.toLong
          rspTokens += dut.trace.logic.rspToken.toInt
          rspFaults += dut.trace.logic.rspFault.toBoolean
          rspCacheModes += dut.trace.logic.rspCacheMode.toEnum.toString
        }
        val probeFire = dut.dcache.logic.loadProbePort.valid.toBoolean &&
                        dut.dcache.logic.loadProbePort.ready.toBoolean
        if (probeFire) {
          probeCycles += cycle
          probeVpns += (dut.dcache.logic.loadProbePort.payload.vaddr.toLong >>> 12) & 0xfffffL
          probeTokens += dut.dcache.logic.loadProbePort.payload.token.toInt
        }
        if (dut.eu.logic.parallelViptLaunch.toBoolean) parallelCycles += cycle
        if (dut.eu.logic.alignedEnq.toBoolean) enqCycles += cycle
        val cmdFire = dut.dcache.logic.loadCmdPort.valid.toBoolean &&
                      dut.dcache.logic.loadCmdPort.ready.toBoolean
        if (cmdFire) {
          val tok = dut.dcache.logic.loadCmdPort.payload.token.toInt
          if (tok == m68k040.cache.DLoadToken.WALK_ITLB ||
              tok == m68k040.cache.DLoadToken.WALK_DTLB) {
            walkCmds += 1
          } else {
            cmdCycles += cycle
            cmdVas += dut.dcache.logic.loadCmdPort.payload.vaddr.toLong & 0xffffffffL
            cmdPas += dut.dcache.logic.loadCmdPort.payload.paddr.toLong & 0xffffffffL
            cmdTokens += tok
            if (dut.dcache.logic.useEarlyProbe.toBoolean) earlyUseCycles += cycle
          }
        }
        if (s.fValid.toBoolean)
          residentFaults += ((s.fRob.toInt, s.fAddr.toLong & 0xffffffffL))
        if (s.cValid.toBoolean && expectedRobs.contains(s.cRob.toInt)) {
          completionCycles += cycle
          completionRobs += s.cRob.toInt
        }

        cd.waitSampling()
        cycle += 1
        if (issueFire) {
          nextIssue += 1
          if (nextIssue < loads.size) {
            val n = loads(nextIssue)
            val line = if (n.basePreg == 10) vaLineA else vaLineB
            driveLoad(dut, n.basePreg, (n.va - line).toInt, n.pdst, n.robId)
          } else {
            s.iValid #= false
          }
        }
      }

      // Establish non-vacuity and correctness before checking cadence.  On the old
      // held-VPN response scheme these all pass, then the final cadence assertion
      // reports the real every-other-cycle bubbles instead of hiding them.
      assert(issueCycles.size == loads.size, s"accepted only ${issueCycles.size}/${loads.size}: $issueCycles")
      assert(reqVpns.toSeq == expectedVpns,
        s"DTLB command VPN association: got=$reqVpns expected=$expectedVpns")
      assert(reqTokens.toSeq == expectedTokens,
        s"DTLB command tokens: got=$reqTokens expected=$expectedTokens")
      assert(rspPpns.toSeq == loads.map(_.ppn),
        s"DTLB response PPN association: got=$rspPpns expected=${loads.map(_.ppn)}")
      assert(rspTokens.toSeq == expectedTokens,
        s"DTLB response tokens: got=$rspTokens expected=$expectedTokens")
      assert(!rspFaults.contains(true), s"resident translations faulted: $rspFaults")
      assert(rspCacheModes.toSeq == loads.map(_.cacheMode),
        s"DTLB cache-mode association: got=$rspCacheModes expected=${loads.map(_.cacheMode)}")
      assert(probeVpns.toSeq == expectedVpns,
        s"VIPT probe VPN association: got=$probeVpns expected=$expectedVpns")
      assert(probeTokens.toSeq == expectedTokens,
        s"VIPT probe tokens: got=$probeTokens expected=$expectedTokens")
      assert(cmdVas.toSeq == loads.map(_.va),
        s"resolved cache-command VA association: got=$cmdVas expected=${loads.map(_.va)}")
      assert(cmdPas.toSeq == loads.map(_.pa),
        s"resolved cache-command PA association: got=$cmdPas expected=${loads.map(_.pa)}")
      assert(cmdTokens.toSeq == expectedTokens,
        s"resolved cache-command tokens: got=$cmdTokens expected=$expectedTokens")
      assert(completionRobs.toSeq == expectedRobs,
        s"untagged L1 responses completed out of order: got=$completionRobs expected=$expectedRobs")
      assert(residentFaults.isEmpty, s"resident burst produced faults: $residentFaults")
      assert(walkCmds == 0,
        s"measured accesses were not resident DTLB hits: $walkCmds table-walk descriptor " +
        s"reads were issued during the measured window")
      cd.waitSampling(3)
      loads.foreach { l =>
        val got = readPreg(dut, l.pdst)
        assert(got == l.expected,
          f"rob=${l.robId}%d pdst=${l.pdst}%d data=0x$got%08X expected=0x${l.expected}%08X")
      }

      // The fault case uses a sentinel destination to prove that a tagged ATC
      // fault completes the ROB entry but cannot produce a cache command/writeback.
      // Distinct from every id used above (8, 9 and 16..23) -- the top of the space.
      val faultRob = m68k040.TestRobIds.highBlock(1).head
      val faultPdst = 40
      val sentinel = 0x5aa55aa5L
      seed(dut, cd, preg = 12, data = faultVa)
      seed(dut, cd, preg = faultPdst, data = sentinel)
      driveLoad(dut, basePreg = 12, displacement = 0, pdst = faultPdst, robId = faultRob)
      var faultIssued = false
      var faultReqs = Vector.empty[(Long, Int)]
      var faultRsps = Vector.empty[(Int, Boolean)]
      var faultCompletions = 0
      var faultReports = Vector.empty[(Int, Long, Boolean, Int, Boolean, Boolean)]
      var faultCacheCmds = 0
      var faultCycles = 0
      while ((faultCompletions == 0 || faultReports.isEmpty || faultRsps.isEmpty) && faultCycles < 400) {
        sleep(1)
        val s = dut.src.logic
        val issueFire = s.iValid.toBoolean && s.iReady.toBoolean
        if (issueFire) faultIssued = true
        if (dut.trace.logic.reqFire.toBoolean)
          faultReqs :+= ((dut.trace.logic.reqVpn.toLong, dut.trace.logic.reqToken.toInt))
        if (dut.trace.logic.rspFire.toBoolean)
          faultRsps :+= ((dut.trace.logic.rspToken.toInt, dut.trace.logic.rspFault.toBoolean))
        if (s.cValid.toBoolean && s.cRob.toInt == faultRob) faultCompletions += 1
        if (s.fValid.toBoolean)
          faultReports :+= ((s.fRob.toInt, s.fAddr.toLong & 0xffffffffL,
                             s.fWrite.toBoolean, s.fSize.toInt,
                             s.fSuper.toBoolean, s.fAtc.toBoolean))
        if (dut.dcache.logic.loadCmdPort.valid.toBoolean &&
            dut.dcache.logic.loadCmdPort.ready.toBoolean) {
          // Table-walk descriptor reads are D-cache load commands now and are NOT the
          // thing this assertion is about ("did a FAULTING ACCESS leak a command into
          // L1D"). Exclude them by their reserved token; a faulting access still walks.
          val tok = dut.dcache.logic.loadCmdPort.payload.token.toInt
          if (tok != m68k040.cache.DLoadToken.WALK_ITLB &&
              tok != m68k040.cache.DLoadToken.WALK_DTLB) faultCacheCmds += 1
        }
        cd.waitSampling()
        if (issueFire) s.iValid #= false
        faultCycles += 1
      }
      cd.waitSampling(3)
      assert(faultIssued, "non-resident directed access was never accepted")
      assert(faultReqs == Vector(((faultVa >>> 12) & 0xfffffL, faultRob)),
        s"fault request token/VPN association: $faultReqs")
      assert(faultRsps == Vector((faultRob, true)),
        s"fault response must retain token and fault bit: $faultRsps")
      assert(faultCompletions == 1, s"faulting ROB completion count=$faultCompletions")
      assert(faultReports == Vector((faultRob, faultVa, false, 2, false, true)),
        s"precise ATC fault payload mismatch: $faultReports")
      assert(faultCacheCmds == 0, s"translation fault leaked $faultCacheCmds command(s) into L1D")
      assert(readPreg(dut, faultPdst) == BigInt(sentinel),
        "translation fault must not write its destination PRF")

      val count = loads.size
      val cadenceOk = consecutive(issueCycles.toSeq, count) &&
        consecutive(reqCycles.toSeq, count) &&
        consecutive(rspCycles.toSeq, count) &&
        consecutive(probeCycles.toSeq, count) &&
        consecutive(parallelCycles.toSeq, count) &&
        consecutive(enqCycles.toSeq, count) &&
        consecutive(cmdCycles.toSeq, count) &&
        consecutive(earlyUseCycles.toSeq, count) &&
        consecutive(completionCycles.toSeq, count) &&
        probeCycles.toSeq == reqCycles.toSeq &&
        parallelCycles.toSeq == reqCycles.toSeq &&
        earlyUseCycles.toSeq == cmdCycles.toSeq &&
        rspCycles.zip(reqCycles).forall { case (rsp, req) => rsp == req + 1 }
      assert(cadenceOk,
        s"changed-VPN resident path must be II=1 with a one-cycle tagged DTLB response; " +
        s"issue=$issueCycles req=$reqCycles rsp=$rspCycles probe=$probeCycles " +
        s"parallel=$parallelCycles enq=$enqCycles cmd=$cmdCycles " +
        s"earlyUse=$earlyUseCycles completion=$completionCycles")
    }
  }
}
