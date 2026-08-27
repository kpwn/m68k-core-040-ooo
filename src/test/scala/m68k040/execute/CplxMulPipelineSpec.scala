package m68k040.execute

import m68k040.{M68kSim, VerilatorTest}
import m68k040.decode.{DecOp, EaAuto, SysKind}
import m68k040.execute.iq.IqContext
import m68k040.execute.regfile.{
  IntRegFileService,
  NzvcRegFileService,
  RegFilePluginInt,
  RegFilePluginNzvc,
  RegFileReadPort,
  RegFileWritePort
}
import m68k040.isa.{Cluster, MemOp, Size}
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.database.Database
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}

import scala.collection.mutable.ArrayBuffer

/** Directed test of the complete CPLX multiply path, rather than MulCore alone.
  *
  * The source obeys Stream semantics: every payload is held until an edge on which
  * valid && ready.  Operands are preloaded into the real integer PRF.  Results are
  * checked at the shared completion/writeback/wakeup boundary and then read back
  * from the PRFs.  This catches descriptor/product skew, FIFO loss or duplication,
  * early MULHI consumption, and stale post-flush effects.
  */
class CplxMulPipelineSpec extends AnyFunSuite {

  private val Mask32 = (BigInt(1) << 32) - 1
  private val Mask64 = (BigInt(1) << 64) - 1

  private case class Req(
      rob: Int,
      pdst: Int,
      pNzvcDst: Int,
      dstArch: Int,
      psrcA: Int,
      psrcB: Int,
      a: BigInt,
      b: BigInt,
      signed: Boolean,
      size: SpinalEnumElement[Size.type],
      is64: Boolean = false,
      tail: Boolean = false)

  private case class Expected(
      rob: Int,
      pdst: Int,
      pNzvcDst: Int,
      dstArch: Int,
      data: BigInt,
      nzvc: Int,
      nzvcWrite: Boolean,
      tail: Boolean)

  private case class Seen(
      cycle: Int,
      rob: Int,
      pdst: Int,
      dstArch: Int,
      data: BigInt,
      nzvc: Int,
      nzvcWrite: Boolean,
      tail: Boolean)

  /** Test-only source/sink around the real PRFs and DivEu service. */
  class Src extends FiberPlugin {
    var preloadA: RegFileWritePort = null
    var preloadB: RegFileWritePort = null
    var readInt: RegFileReadPort = null
    var readNzvc: RegFileReadPort = null

    during setup {
      preloadA = host[IntRegFileService].newWrite(latency = 1, sharingKey = "cplxMulTestA")
      preloadB = host[IntRegFileService].newWrite(latency = 1, sharingKey = "cplxMulTestB")
      readInt = host[IntRegFileService].newRead(forceNoBypass = true)
      readNzvc = host[NzvcRegFileService].newRead(forceNoBypass = true)
    }

    val logic = during build new Area {
      val eu = host[DivEuService]
      val divEu = host[DivEuPlugin]

      val iValid = in Bool()
      val iOp = in(DecOp())
      val iSize = in(Size())
      val iSigned = in Bool()
      val iIs64 = in Bool()
      val iRob = in UInt(6 bits)
      val iPsrcA = in UInt(6 bits)
      val iPsrcB = in UInt(6 bits)
      val iPdst = in UInt(6 bits)
      val iPNzvcDst = in UInt(4 bits)
      val iDstArch = in UInt(5 bits)
      val iWritesNzvc = in Bool()
      val iFlush = in Bool()

      val ctx = IqContext()
      val uop = ctx.uop

      // Drive every RenamedUop field.  The test intentionally avoids don't-care
      // payloads: a newly-consumed field must fail deterministically, not turn this
      // into a netlist-dependent test.
      uop.valid := iValid
      uop.pc := U(0x2000, 32 bits)
      uop.nextPc := U(0x2002, 32 bits)
      uop.op := iOp
      uop.cluster := Cluster.CPLX
      uop.size := iSize
      uop.memOp := MemOp.NONE
      uop.useImm := False
      uop.imm := 0
      uop.isBranch := False
      uop.cond := 0
      uop.branchDisp := 0
      uop.ibranch := False
      uop.anInc := 0
      uop.stkPush := False
      uop.eaAuto := EaAuto.NONE
      uop.eaDelta := 0
      uop.ccrRestore := False
      uop.toCcr := False
      uop.unimplemented := False
      uop.faulted := False
      uop.faultVector := 0
      uop.isRte := False
      uop.faultUsesNextPc := False
      uop.isCondTrap := False
      uop.sswInstr := False
      uop.faultAtc := True
      uop.divSigned := iSigned
      uop.div64 := iIs64
      uop.divIsRem := False
      uop.isChk2 := False
      uop.shiftOp := 0
      uop.shiftDir := False
      uop.bcdSub := False
      uop.bitOp := 0
      uop.bfOp := 0
      uop.bfDynamic := False
      uop.bfMem := False
      uop.bfStoreForm := 0
      uop.extByte := False
      uop.isMovea := False
      uop.isScc := False
      uop.isDbcc := False
      uop.indexLong := False
      uop.indexScale := 0
      uop.leaAddr := False
      uop.movesAliasStore := False
      uop.fromCcr := False
      uop.fromSr := False
      uop.needsSupervisor := False
      uop.keepCommit := False
      uop.sysOp := False
      uop.sysKind := SysKind.NONE
      uop.sysReadDir := False
      uop.predTaken := False
      uop.predTarget := 0
      uop.phtValid := False
      uop.phtIndex := 0
      uop.casForm := 0
      uop.firstOfInstr := !((iOp === DecOp.MULHI))

      uop.dstArch := iDstArch
      uop.psrcA := iPsrcA
      uop.psrcAValid := (iOp === DecOp.MUL) || (iOp === DecOp.DIV)
      uop.psrcB := iPsrcB
      uop.psrcBValid := (iOp === DecOp.MUL) || (iOp === DecOp.DIV)
      uop.psrcC := 0
      uop.psrcCValid := False
      uop.pdst := iPdst
      uop.pdstValid := True
      uop.pdstOld := 0
      uop.pNzvcSrc := 0
      uop.readsNzvc := False
      uop.pNzvcDst := iPNzvcDst
      uop.writesNzvc := iWritesNzvc
      uop.pNzvcOld := 0
      uop.pXSrc := 0
      uop.readsX := False
      uop.pXDst := 0
      uop.writesX := False
      uop.pXOld := 0
      // FP-domain fields: the CPLX EU now also carries the FP writeback lane and reads these
      // on every issued uop, so they must be DRIVEN (not left floating) even though no uop in
      // this spec is an FP uop. Inert values -- op is never DecOp.FPU here.
      uop.pFpSrcA := 0; uop.psrcAFpValid := False
      uop.pFpSrcB := 0; uop.psrcBFpValid := False
      uop.pFpDst := 0; uop.pFpDstValid := False; uop.pFpOld := 0; uop.fpDstArch := 0
      uop.pFpccSrc := 0; uop.readsFpcc := False
      uop.pFpccDst := 0; uop.writesFpcc := False; uop.pFpccOld := 0
      uop.fpuOp := 0; uop.fpSrcKind := m68k040.decode.FpSrcKind.FPREG
      uop.fpSrcFmt := 0; uop.fpWideImm := B(0, 80 bits)

      ctx.robId := iRob
      eu.issue.valid := iValid
      eu.issue.payload := ctx
      eu.cplxFlush := iFlush

      val iReady = out Bool()
      val iFire = out Bool()
      iReady := eu.issue.ready
      iFire := eu.issue.fire

      val preAValid = in Bool()
      val preAAddr = in UInt(6 bits)
      val preAData = in Bits(32 bits)
      val preBValid = in Bool()
      val preBAddr = in UInt(6 bits)
      val preBData = in Bits(32 bits)
      preloadA.valid := preAValid
      preloadA.address := preAAddr
      preloadA.data := preAData
      preloadB.valid := preBValid
      preloadB.address := preBAddr
      preloadB.data := preBData

      val readIntAddr = in UInt(6 bits)
      val readIntData = out Bits(32 bits)
      readInt.addr := readIntAddr
      readIntData := readInt.data
      val readNzvcAddr = in UInt(4 bits)
      val readNzvcData = out Bits(4 bits)
      readNzvc.addr := readNzvcAddr
      readNzvcData := readNzvc.data

      val cValid = out Bool()
      val cRob = out UInt(6 bits)
      cValid := eu.completion.valid
      cRob := eu.completion.payload

      val wakeValid = out Bool()
      val wakePdst = out UInt(6 bits)
      wakeValid := eu.wakeup.valid
      wakePdst := eu.wakeup.payload
      val nzWakeValid = out Bool()
      val nzWakePdst = out UInt(4 bits)
      nzWakeValid := eu.wakeupNzvc.valid
      nzWakePdst := eu.wakeupNzvc.payload

      val faultValid = out Bool()
      faultValid := eu.euFault.valid

      // Sim-only non-vacuity witness: a held legacy result and a queued MUL result
      // were simultaneously eligible at the shared one-result arbiter.
      val arbCollision = out Bool()
      arbCollision := divEu.logic.arbCollisionObs

      val wbValid = out Bool()
      val wbRob = out UInt(6 bits)
      val wbDstArch = out UInt(5 bits)
      val wbResult = out Bits(32 bits)
      val wbIntWrite = out Bool()
      val wbNzvc = out Bits(4 bits)
      val wbNzvcWrite = out Bool()
      val wbTail = out Bool()
      wbValid := divEu.logic.wbObs.valid
      wbRob := divEu.logic.wbObs.robId
      wbDstArch := divEu.logic.wbObs.dstArch
      wbResult := divEu.logic.wbObs.result
      wbIntWrite := divEu.logic.wbObs.intWrite
      wbNzvc := divEu.logic.wbObs.nzvc
      wbNzvcWrite := divEu.logic.wbObs.nzvcWrite
      wbTail := divEu.logic.wbObs.divRem
    }
  }

  class Dut extends Component {
    val db = new Database
    val host = db on (new PluginHost)
    val rfInt = new RegFilePluginInt
    val rfNzvc = new RegFilePluginNzvc
    val eu = new DivEuPlugin
    val src = new Src
    // The CPLX EU now also carries the FP writeback lane, so its FP-data/FPCC physical
    // files must be present for it to elaborate. Unused by this spec.
    val rfFp   = new m68k040.execute.regfile.RegFilePluginFp
    val rfFpcc = new m68k040.execute.regfile.RegFilePluginFpcc
    db.on { host.asHostOf(Seq[FiberPlugin](rfInt, rfNzvc, rfFp, rfFpcc, eu, src)) }
  }

  private def signedValue(value: BigInt, bits: Int): BigInt = {
    val mask = (BigInt(1) << bits) - 1
    val raw = value & mask
    if (raw.testBit(bits - 1)) raw - (BigInt(1) << bits) else raw
  }

  private def fullProduct(req: Req): BigInt = {
    val bits = if (req.size == Size.WORD) 16 else 32
    val a = if (req.signed) signedValue(req.a, bits) else req.a & ((BigInt(1) << bits) - 1)
    val b = if (req.signed) signedValue(req.b, bits) else req.b & ((BigInt(1) << bits) - 1)
    a * b
  }

  private def expected(req: Req): Expected = {
    val product = fullProduct(req)
    val full64 = product & Mask64
    val lo = full64 & Mask32
    val hi = (full64 >> 32) & Mask32
    val n = if (req.is64) hi.testBit(31) else lo.testBit(31)
    val z = if (req.is64) full64 == 0 else lo == 0
    val v = if (req.size == Size.WORD || req.is64) false
    else if (req.signed) product < -(BigInt(1) << 31) || product > ((BigInt(1) << 31) - 1)
    else product > Mask32
    val nzvc = (if (n) 8 else 0) | (if (z) 4 else 0) | (if (v) 2 else 0)
    Expected(req.rob, req.pdst, req.pNzvcDst, req.dstArch,
      if (req.tail) hi else lo,
      if (req.tail) 0 else nzvc,
      nzvcWrite = !req.tail,
      tail = req.tail)
  }

  test("CPLX MUL is II=1, keeps overlapping L64 products associated, and kills flushed work", VerilatorTest) {
    M68kSim().withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      val s = dut.src.logic
      cd.forkStimulus(10)

      s.iValid #= false
      s.iOp #= DecOp.MUL
      s.iSize #= Size.LONG
      s.iSigned #= false
      s.iIs64 #= false
      s.iRob #= 0
      s.iPsrcA #= 0
      s.iPsrcB #= 0
      s.iPdst #= 0
      s.iPNzvcDst #= 0
      s.iDstArch #= 0
      s.iWritesNzvc #= true
      s.iFlush #= false
      s.preAValid #= false
      s.preAAddr #= 0
      s.preAData #= 0
      s.preBValid #= false
      s.preBAddr #= 0
      s.preBData #= 0
      s.readIntAddr #= 0
      s.readNzvcAddr #= 0

      var cycle = 0
      val seen = ArrayBuffer[Seen]()
      var sawArbCollision = false

      def sample(): Unit = {
        if (s.arbCollision.toBoolean) sawArbCollision = true
        assert(!s.faultValid.toBoolean, s"unexpected CPLX fault at cycle $cycle")
        assert(s.cValid.toBoolean == s.wbValid.toBoolean,
          s"completion/wb validity split at cycle $cycle")
        if (s.cValid.toBoolean) {
          assert(s.cRob.toInt == s.wbRob.toInt,
            s"completion rob=${s.cRob.toInt} != wb rob=${s.wbRob.toInt} at cycle $cycle")
          assert(s.wbIntWrite.toBoolean, s"MUL completion rob=${s.cRob.toInt} did not write int PRF")
          assert(s.wakeValid.toBoolean,
            s"MUL completion rob=${s.cRob.toInt} did not broadcast its int wakeup")
          if (s.wbNzvcWrite.toBoolean) {
            assert(s.nzWakeValid.toBoolean,
              s"flag-writing MUL rob=${s.cRob.toInt} did not broadcast NZVC wakeup")
          } else {
            assert(!s.nzWakeValid.toBoolean,
              s"flagless MULHI rob=${s.cRob.toInt} broadcast an NZVC wakeup")
          }
          seen += Seen(
            cycle = cycle,
            rob = s.cRob.toInt,
            pdst = s.wakePdst.toInt,
            dstArch = s.wbDstArch.toInt,
            data = s.wbResult.toBigInt & Mask32,
            nzvc = s.wbNzvc.toInt,
            nzvcWrite = s.wbNzvcWrite.toBoolean,
            tail = s.wbTail.toBoolean)
        } else {
          assert(!s.wakeValid.toBoolean && !s.nzWakeValid.toBoolean,
            s"orphan CPLX wakeup at cycle $cycle")
        }
      }

      def tick(): Unit = {
        cd.waitSampling()
        cycle += 1
        sample()
      }

      // Let both physical files complete their reset-time zero sweeps.
      for (_ <- 0 until 80) tick()

      def preload(reqs: Seq[Req]): Unit = {
        reqs.filterNot(_.tail).foreach { r =>
          s.preAValid #= true
          s.preAAddr #= r.psrcA
          s.preAData #= r.a & Mask32
          s.preBValid #= true
          s.preBAddr #= r.psrcB
          s.preBData #= r.b & Mask32
          tick()
        }
        s.preAValid #= false
        s.preBValid #= false
        tick()
      }

      def poke(r: Req): Unit = {
        s.iOp #= (if (r.tail) DecOp.MULHI else DecOp.MUL)
        s.iSize #= r.size
        s.iSigned #= r.signed
        s.iIs64 #= r.is64
        s.iRob #= r.rob
        s.iPsrcA #= r.psrcA
        s.iPsrcB #= r.psrcB
        s.iPdst #= r.pdst
        s.iPNzvcDst #= r.pNzvcDst
        s.iDstArch #= r.dstArch
        s.iWritesNzvc #= !r.tail
      }

      /** Issue one request per edge, with no deassertion of valid between entries. */
      def issueDense(reqs: Seq[Req], label: String): Seq[Int] = {
        require(reqs.nonEmpty)
        val fireCycles = ArrayBuffer[Int]()
        poke(reqs.head)
        s.iValid #= true
        sleep(1)
        for ((r, index) <- reqs.zipWithIndex) {
          assert(s.iReady.toBoolean && s.iFire.toBoolean,
            s"$label lost II=1 before request $index rob=${r.rob} at cycle $cycle")
          tick()
          fireCycles += cycle
          if (index + 1 < reqs.length) {
            poke(reqs(index + 1))
            sleep(1)
          }
        }
        s.iValid #= false
        fireCycles.toSeq
      }

      def drainTo(totalSeen: Int, budget: Int, label: String): Unit = {
        var guard = 0
        while (seen.size < totalSeen && guard < budget) {
          tick()
          guard += 1
        }
        assert(seen.size == totalSeen,
          s"$label observed ${seen.size}/$totalSeen completions after $guard drain cycles")
      }

      def checkBatch(reqs: Seq[Req], from: Int, label: String, consecutive: Boolean): Unit = {
        val expectedBatch = reqs.map(expected)
        val got = seen.slice(from, from + reqs.length).toSeq
        assert(got.length == expectedBatch.length,
          s"$label got ${got.length}/${expectedBatch.length} completions")
        assert(got.map(_.rob) == expectedBatch.map(_.rob),
          s"$label completion order/association mismatch: got=${got.map(_.rob)} expected=${expectedBatch.map(_.rob)}")
        assert(got.map(_.rob).distinct.length == got.length,
          s"$label duplicated a ROB completion: ${got.map(_.rob)}")
        for ((g, e) <- got.zip(expectedBatch)) {
          assert(g.pdst == e.pdst,
            s"$label rob=${e.rob} wake pdst=${g.pdst}, expected ${e.pdst}")
          assert(g.dstArch == e.dstArch,
            s"$label rob=${e.rob} dstArch=${g.dstArch}, expected ${e.dstArch}")
          assert(g.data == e.data,
            f"$label rob=${e.rob} data=0x${g.data}%08x, expected 0x${e.data}%08x")
          assert(g.nzvcWrite == e.nzvcWrite,
            s"$label rob=${e.rob} nzvcWrite=${g.nzvcWrite}, expected ${e.nzvcWrite}")
          assert(g.nzvc == e.nzvc,
            f"$label rob=${e.rob} NZVC=0x${g.nzvc}%x, expected 0x${e.nzvc}%x")
          assert(g.tail == e.tail,
            s"$label rob=${e.rob} crackTail=${g.tail}, expected ${e.tail}")
        }
        if (consecutive) {
          assert(got.map(_.cycle).sliding(2).forall {
            case Seq(a, b) => b == a + 1
            case _ => true
          }, s"$label completions were not consecutive: ${got.map(_.cycle)}")
        }

        // The direct observation above is not enough: prove the real PRF writes landed.
        tick()
        for ((r, e) <- reqs.zip(expectedBatch)) {
          s.readIntAddr #= r.pdst
          sleep(1)
          assert((s.readIntData.toBigInt & Mask32) == e.data,
            f"$label rob=${e.rob} PRF[${r.pdst}]=0x${s.readIntData.toBigInt}%08x, expected 0x${e.data}%08x")
          if (e.nzvcWrite) {
            s.readNzvcAddr #= r.pNzvcDst
            sleep(1)
            assert(s.readNzvcData.toInt == e.nzvc,
              f"$label rob=${e.rob} NZVC-PRF[${r.pNzvcDst}]=0x${s.readNzvcData.toInt}%x, expected 0x${e.nzvc}%x")
          }
        }
      }

      // More than the FIFO depth: this proves accept-last credit turnover, not just
      // multiple values resident in MulCore. The mix exercises .W sign extension and
      // signed/unsigned .L32 overflow flagging.
      val dense = Seq(
        Req(4, 24, 1, 0, 0, 1, 0, 0xffff, signed = false, Size.WORD),
        Req(5, 25, 2, 1, 2, 3, 0xffff, 2, signed = true, Size.WORD),
        Req(6, 26, 3, 2, 4, 5, 0xffff, 0xffff, signed = false, Size.WORD),
        Req(7, 27, 4, 3, 6, 7, 0x8000, 2, signed = true, Size.WORD),
        Req(8, 28, 5, 4, 8, 9, 1, 2, signed = false, Size.LONG),
        Req(9, 29, 6, 5, 10, 11, 0xffffffffL, 2, signed = false, Size.LONG),
        Req(10, 30, 7, 6, 12, 13, 0xffffffffL, 0xffffffffL, signed = true, Size.LONG),
        Req(11, 31, 8, 7, 14, 15, 0x80000000L, 0xffffffffL, signed = true, Size.LONG),
        Req(12, 32, 9, 8, 16, 17, 0x10000, 0x10000, signed = false, Size.LONG),
        Req(13, 33, 10, 9, 18, 19, 0x7fffffffL, 2, signed = true, Size.LONG),
        Req(14, 34, 11, 10, 20, 21, 0x1234, 0x5678, signed = false, Size.WORD),
        Req(15, 35, 12, 11, 22, 23, 0xdeadbeefL, 0xcafebabeL, signed = false, Size.LONG))
      preload(dense)
      val denseFrom = seen.size
      val denseFireCycles = issueDense(dense, "dense MUL")
      assert(denseFireCycles.sliding(2).forall {
        case Seq(a, b) => b == a + 1
        case _ => true
      }, s"dense MUL issue fires were not consecutive: $denseFireCycles")
      drainTo(denseFrom + dense.length, 80, "dense MUL")
      checkBatch(dense, denseFrom, "dense MUL", consecutive = true)
      val denseDone = seen.size
      for (_ <- 0 until 3) tick()
      assert(seen.size == denseDone, "dense MUL emitted an extra completion")

      // Two .L64 pairs overlap.  Pair A deliberately wraps main ROB 63 -> tail 0;
      // pair B uses main ROB 1 -> tail 2, so both modulo association and the removal
      // of the old global high-product latch are exercised.
      val mainA = Req(63, 36, 13, 2, 0, 1,
        0xffffffffL, 0xffffffffL, signed = false, Size.LONG, is64 = true)
      val tailA = Req(0, 37, 0, 3, 0, 0,
        mainA.a, mainA.b, mainA.signed, Size.LONG, is64 = true, tail = true)
      val mainB = Req(1, 38, 14, 4, 2, 3,
        0x80000000L, 2, signed = true, Size.LONG, is64 = true)
      val tailB = Req(2, 39, 0, 5, 0, 0,
        mainB.a, mainB.b, mainB.signed, Size.LONG, is64 = true, tail = true)
      val pairs = Seq(mainA, tailA, mainB, tailB)
      preload(pairs)
      val pairFrom = seen.size
      issueDense(pairs, "overlapping L64")
      drainTo(pairFrom + pairs.length, 80, "overlapping L64")
      checkBatch(pairs, pairFrom, "overlapping L64", consecutive = false)
      val pairDone = seen.size
      for (_ <- 0 until 3) tick()
      assert(seen.size == pairDone, "overlapping L64 emitted an extra completion")

      // Start an iterative divide, then place a dense MUL burst across its fixed
      // completion window.  The sim-only witness proves this is a real two-source
      // collision, not merely a test that happened to run both engines at different
      // times.  Every Flow result still has to emerge once because there is no ROB
      // backpressure with which to recover a dropped completion.
      val collisionMuls = Seq.tabulate(8) { i =>
        Req(40 + i, 24 + i, 1 + i, 8 + i, 2 * i, 2 * i + 1,
          BigInt(0x10101 + i * 0x111), BigInt(3 + i),
          signed = (i & 1) != 0, Size.LONG)
      }
      preload(collisionMuls)
      s.preAValid #= true; s.preAAddr #= 20; s.preAData #= 100
      s.preBValid #= true; s.preBAddr #= 21; s.preBData #= 7
      tick()
      s.preAValid #= false; s.preBValid #= false
      tick()

      s.iOp #= DecOp.DIV
      s.iSize #= Size.WORD
      s.iSigned #= false
      s.iIs64 #= false
      s.iRob #= 39
      s.iPsrcA #= 20
      s.iPsrcB #= 21
      s.iPdst #= 40
      s.iPNzvcDst #= 15
      s.iDstArch #= 7
      s.iWritesNzvc #= true
      s.iValid #= true
      sleep(1)
      assert(s.iReady.toBoolean && s.iFire.toBoolean, "collision DIV was not accepted")
      tick()
      s.iValid #= false
      val collisionFrom = seen.size

      // DIV launch wrapper + 64 restoring steps puts its held result in this
      // neighborhood. Keep the first product's fixed-latency arrival centered on
      // that same absolute cycle as MulCore grows; the hard witness below still
      // requires both arbiter sources to be valid together.
      val collisionLaunchDelay = 62 - MulCore.Latency
      require(collisionLaunchDelay > 0)
      for (_ <- 0 until collisionLaunchDelay) tick()
      issueDense(collisionMuls, "MUL while DIV active")
      drainTo(collisionFrom + 1 + collisionMuls.length, 100, "DIV/MUL collision")
      assert(sawArbCollision,
        "DIV/MUL test never created simultaneous legacy and MUL arbiter sources")

      val collisionSeen = seen.slice(collisionFrom, collisionFrom + 1 + collisionMuls.length).toSeq
      val collisionExpected = collisionMuls.map(expected) :+ Expected(
        rob = 39, pdst = 40, pNzvcDst = 15, dstArch = 7,
        data = BigInt(0x0002000eL), nzvc = 0, nzvcWrite = true, tail = false)
      assert(collisionSeen.map(_.rob).distinct.length == collisionExpected.length,
        s"DIV/MUL collision duplicated a completion: ${collisionSeen.map(_.rob)}")
      assert(collisionSeen.map(_.rob).toSet == collisionExpected.map(_.rob).toSet,
        s"DIV/MUL collision lost/misrouted a completion: got=${collisionSeen.map(_.rob)}")
      val expectedByRob = collisionExpected.map(e => e.rob -> e).toMap
      collisionSeen.foreach { g =>
        val e = expectedByRob(g.rob)
        assert(g.pdst == e.pdst && g.dstArch == e.dstArch,
          s"DIV/MUL collision routing mismatch for rob=${g.rob}")
        assert(g.data == e.data,
          f"DIV/MUL collision rob=${g.rob} data=0x${g.data}%08x expected=0x${e.data}%08x")
        assert(g.nzvcWrite == e.nzvcWrite && g.nzvc == e.nzvc,
          s"DIV/MUL collision flag mismatch for rob=${g.rob}")
      }
      val collisionDone = seen.size
      for (_ <- 0 until 4) tick()
      assert(seen.size == collisionDone, "DIV/MUL collision emitted an extra completion")

      // Fill the active pipeline/result path with distinct wrong values, flush, then
      // immediately reuse the same ROB IDs and destinations.  No pre-flush product,
      // flag write, or wakeup may appear after the reuse point.
      val wrong = dense.take(8).zipWithIndex.map { case (r, i) =>
        r.copy(rob = 20 + i, pdst = 24 + i, pNzvcDst = 1 + i,
          a = BigInt(0x11110000L + i), b = BigInt(0x101 + i))
      }
      preload(wrong)
      issueDense(wrong, "pre-flush MUL")
      s.iFlush #= true
      tick()
      s.iFlush #= false
      val afterFlush = seen.size

      val reused = wrong.zipWithIndex.map { case (r, i) =>
        r.copy(a = BigInt(0xfedcba98L - i), b = BigInt(3 + i),
          signed = (i & 1) != 0, size = if ((i & 2) == 0) Size.LONG else Size.WORD)
      }
      preload(reused)
      issueDense(reused, "post-flush reused MUL")
      drainTo(afterFlush + reused.length, 80, "post-flush reused MUL")
      checkBatch(reused, afterFlush, "post-flush reused MUL", consecutive = true)
      val reuseDone = seen.size
      for (_ <- 0 until 8) tick()
      assert(seen.size == reuseDone,
        "a killed pre-flush MUL completed after ROB/destination reuse")
    }
  }
}
