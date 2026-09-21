package m68k040.execute

import m68k040.{M68kSim, VerilatorTest}
import m68k040.execute.regfile.{RegFilePluginInt, RegFilePluginNzvc, RegFilePluginX}
import m68k040.decode.DecOp
import m68k040.isa.Size
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** Fast/slow ALU split: fast ops (ADD) complete at S1 with bypass; SHIFT completes at
  * S3 of the S1/S1a/S2/S3 pipe.  The slow datapath is fixed latency but accepts
  * independent operations every cycle.  (BITFIELD used to share this pipe and is what
  * made it six stages deep; it now runs on the CPLX cluster's own bit-field lane.) */
class AluFastSlowSpec extends AnyFunSuite {
  class Dut extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val rfInt  = new RegFilePluginInt
    val rfNzvc = new RegFilePluginNzvc
    val rfX    = new RegFilePluginX
    val eu     = new AluEuPlugin
    val src    = new AluEuSourcePlugin
    db.on { host.asHostOf(Seq[FiberPlugin](rfInt, rfNzvc, rfX, eu, src)) }
  }

  def initPorts(d: Dut): Unit = {
    val s = d.src.logic
    s.iFlush #= false
    s.iShiftOp #= 0; s.iShiftDir #= false; s.iToCcr #= false
    s.iReadsNz #= false; s.iPNzvcSrc #= 0; s.iReadsX #= false; s.iPXSrc #= 0
  }
  def idle(d: Dut): Unit = { d.src.logic.iValid #= false }

  def issueMoveq(d: Dut, imm: Long, pdst: Int, robId: Int): Unit = {
    val s = d.src.logic
    s.iValid #= true; s.iOp #= DecOp.MOVE; s.iSize #= Size.LONG
    s.iUseImm #= true; s.iImm #= BigInt(imm & 0xffffffffL)
    s.iPsrcAValid #= false; s.iPsrcBValid #= false
    s.iPdst #= pdst; s.iPdstValid #= true
    s.iWritesNz #= true; s.iPNzvcDst #= (pdst & 0xf)
    s.iWritesX #= false; s.iPXDst #= 0
    s.iShiftOp #= 0; s.iShiftDir #= false; s.iToCcr #= false
    s.iReadsNz #= false; s.iReadsX #= false
    s.iRobId #= robId
  }
  def issueAdd(d: Dut, pa: Int, pb: Int, pdst: Int, robId: Int): Unit = {
    val s = d.src.logic
    s.iValid #= true; s.iOp #= DecOp.ADD; s.iSize #= Size.LONG
    s.iUseImm #= false
    s.iPsrcA #= pa; s.iPsrcAValid #= true
    s.iPsrcB #= pb; s.iPsrcBValid #= true
    s.iPdst #= pdst; s.iPdstValid #= true
    s.iWritesNz #= true; s.iPNzvcDst #= (pdst & 0xf)
    s.iWritesX #= true; s.iPXDst #= (pdst & 0xf)
    s.iShiftOp #= 0; s.iShiftDir #= false; s.iToCcr #= false
    s.iReadsNz #= false; s.iReadsX #= false
    s.iRobId #= robId
  }
  // SHIFT (line-E) immediate form: data = Dr (psrcA), count = imm[5:0].
  // shiftOp 1 = LS, dir true = left -> LSL.
  def issueLslImm(d: Dut, pa: Int, count: Int, pdst: Int, robId: Int): Unit = {
    val s = d.src.logic
    s.iValid #= true; s.iOp #= DecOp.SHIFT; s.iSize #= Size.LONG
    s.iUseImm #= true; s.iImm #= BigInt(count & 0x3f)
    s.iPsrcA #= pa; s.iPsrcAValid #= true
    s.iPsrcBValid #= false
    s.iPdst #= pdst; s.iPdstValid #= true
    s.iWritesNz #= true; s.iPNzvcDst #= (pdst & 0xf)
    s.iWritesX #= true; s.iPXDst #= (pdst & 0xf)
    s.iShiftOp #= 1; s.iShiftDir #= true; s.iToCcr #= false
    s.iReadsNz #= false; s.iReadsX #= false
    s.iRobId #= robId
  }

  // Issue exactly ONE cycle (drive at the current edge, then deassert) and count the
  // number of cycles from the issue edge until completion(robId) is observed. Returns
  // that observed harness latency, or -1 if the supplied window expires.
  // `drive` pokes the issue inputs for this single cycle.
  def latencyOf(d: Dut, robId: Int, window: Int)(drive: => Unit): Int = {
    val cd = d.clockDomain
    drive
    cd.waitSampling()      // issue accepted on this edge (T); now at T (post-edge)
    idle(d)
    var lat = -1
    var i = 1
    while (i <= window && lat < 0) {
      // after `i` edges past issue we are at T+i; sample the registered completion.
      if (d.src.logic.cValid.toBoolean && d.src.logic.cRob.toInt == robId) lat = i
      cd.waitSampling(); i += 1
    }
    lat
  }

  test("fast ADD completes at latency-1 (S1)", VerilatorTest) {
    M68kSim().compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      initPorts(dut); idle(dut)
      dut.src.logic.obsIntAddr #= 0; dut.src.logic.obsNzvcAddr #= 0
      cd.waitSampling(80)
      issueMoveq(dut, 10, pdst = 0, robId = 0); cd.waitSampling()
      issueMoveq(dut, 5,  pdst = 1, robId = 1); cd.waitSampling()
      idle(dut); cd.waitSampling(6)
      val lat = latencyOf(dut, 7, window = 6) { issueAdd(dut, pa = 0, pb = 1, pdst = 2, robId = 7) }
      assert(lat == 2, s"fast ADD completion latency=$lat (expected 2 = arch lat1; the harness counts one extra pre-capture edge)")
      cd.waitSampling(4)
      dut.src.logic.obsIntAddr #= 2; sleep(1)
      assert(dut.src.logic.obsIntData.toBigInt == 15, s"R2 got ${dut.src.logic.obsIntData.toBigInt}")
    }
  }

  test("slow SHIFT (LSL #1) result correct, completes at four-stage S3", VerilatorTest) {
    M68kSim().compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      initPorts(dut); idle(dut)
      dut.src.logic.obsIntAddr #= 0; dut.src.logic.obsNzvcAddr #= 0
      cd.waitSampling(80)
      issueMoveq(dut, 0x21, pdst = 3, robId = 0); cd.waitSampling()  // R3 = 0x21
      idle(dut); cd.waitSampling(6)
      val lat = latencyOf(dut, 9, window = 10) { issueLslImm(dut, pa = 3, count = 1, pdst = 4, robId = 9) }
      // The pipe is S1/S1a/S2/S3 = FOUR stages, every one of them doing real shifter
      // work. It was six (S1/S1a/S1a2/S1b/S2/S3) while BITFIELD shared the path: S1a2
      // and S1b were bit-field cuts that SHIFT only rode through as pass-throughs, to
      // stay lat-matched. Bit-field now lives on the CPLX cluster, so they are gone.
      // This harness samples once before the capture edge and therefore reports 5.
      assert(lat == 5, s"slow SHIFT completion latency=$lat (expected 5 = four stages + harness pre-capture sample)")
      cd.waitSampling(4)
      dut.src.logic.obsIntAddr #= 4; sleep(1)
      assert(dut.src.logic.obsIntData.toBigInt == 0x42,
        s"R4 (LSL.L #1 of 0x21) got 0x${dut.src.logic.obsIntData.toBigInt.toString(16)} (expected 0x42)")
    }
  }

  test("slow SHIFT writeback lands at S3 (PRF holds old dst until then)", VerilatorTest) {
    // The slow producer must NOT write the PRF / bypass its S1/S2 partial. Reading the
    // dst forceNoBypass while the shift is in S1 still observes the OLD value; only the
      // final S3 write updates it. Verifies the result + the no-early-write property.
    M68kSim().compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      initPorts(dut); idle(dut)
      dut.src.logic.obsIntAddr #= 0; dut.src.logic.obsNzvcAddr #= 0
      cd.waitSampling(80)
      issueMoveq(dut, 0x11, pdst = 5, robId = 0); cd.waitSampling()  // R5 = 0x11 (the shift src)
      issueMoveq(dut, 0xDD, pdst = 6, robId = 1); cd.waitSampling()  // R6 = 0xDD (old dst marker)
      idle(dut); cd.waitSampling(6)
      // LSL.L #2, R5 -> R6 (=0x44). issue at T.
      issueLslImm(dut, pa = 5, count = 2, pdst = 6, robId = 11); cd.waitSampling()
      idle(dut)
      // at T+1 the shift is in S1 (partial). Reading R6 forceNoBypass observes the PRF,
      // which must still hold 0xDD (no early/partial write).
      dut.src.logic.obsIntAddr #= 6; sleep(1)
      assert(dut.src.logic.obsIntData.toBigInt == 0xDD,
        s"R6 at S1 of the shift must still be the OLD 0xDD (no early write), got 0x${dut.src.logic.obsIntData.toBigInt.toString(16)}")
      cd.waitSampling(6)
      dut.src.logic.obsIntAddr #= 6; sleep(1)
      assert(dut.src.logic.obsIntData.toBigInt == 0x44,
        s"R6 (LSL.L #2 of 0x11) got 0x${dut.src.logic.obsIntData.toBigInt.toString(16)} (expected 0x44 at final S3)")
    }
  }

  test("six independent SHIFTs are accepted and complete on consecutive cycles", VerilatorTest) {
    M68kSim().compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      initPorts(dut); idle(dut)
      dut.src.logic.obsIntAddr #= 0; dut.src.logic.obsNzvcAddr #= 0; dut.src.logic.obsXAddr #= 0
      cd.waitSampling(80)
      issueMoveq(dut, 1, pdst = 3, robId = 1); cd.waitSampling()
      idle(dut); cd.waitSampling(6)

      val completed = scala.collection.mutable.ArrayBuffer.empty[(Int, Int)]
      var cycle = 0
      def sampleCompletion(): Unit = {
        if (dut.src.logic.cValid.toBoolean)
          completed += ((dut.src.logic.cRob.toInt, cycle))
      }

      var acceptedWhileS2Reserved = false
      for (i <- 0 until 6) {
        issueLslImm(dut, pa = 3, count = i + 1, pdst = 10 + i, robId = 20 + i)
        sleep(1)
        assert(dut.src.logic.iReady.toBoolean, s"slow issue $i was not accepted at II=1")
        acceptedWhileS2Reserved = acceptedWhileS2Reserved || dut.eu.logic.s2Valid.toBoolean
        cd.waitSampling(); sleep(1); cycle += 1; sampleCompletion()
      }
      assert(acceptedWhileS2Reserved, "burst never exercised SLOW acceptance while S2 was occupied")
      idle(dut)
      while (completed.size < 6 && cycle < 20) {
        cd.waitSampling(); sleep(1); cycle += 1; sampleCompletion()
      }

      assert(completed.map(_._1).toSeq == (20 until 26),
        s"slow completion ROB sequence was ${completed.map(_._1).mkString(",")}")
      assert(completed.map(_._2).sliding(2).forall(p => p.length < 2 || p(1) == p(0) + 1),
        s"slow completions were not consecutive: ${completed.mkString(",")}")
      // Completion and PRF write request coincide; the registered RAM write lands
      // one edge later.  Wait explicitly so the last result is not sampled early.
      cd.waitSampling(2); sleep(1)
      for (i <- 0 until 6) {
        dut.src.logic.obsIntAddr #= 10 + i; sleep(1)
        assert(dut.src.logic.obsIntData.toBigInt == (BigInt(1) << (i + 1)),
          s"SHIFT $i result was 0x${dut.src.logic.obsIntData.toBigInt.toString(16)}")
      }
    }
  }

  test("fast reservation blocks exactly the S2 collision cycle", VerilatorTest) {
    M68kSim().compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      initPorts(dut); idle(dut)
      dut.src.logic.obsIntAddr #= 0; dut.src.logic.obsNzvcAddr #= 0; dut.src.logic.obsXAddr #= 0
      cd.waitSampling(80)
      issueMoveq(dut, 3, pdst = 1, robId = 1); cd.waitSampling()
      issueMoveq(dut, 4, pdst = 2, robId = 2); cd.waitSampling()
      idle(dut); cd.waitSampling(6)

      issueLslImm(dut, pa = 1, count = 1, pdst = 8, robId = 30)
      cd.waitSampling(); idle(dut); sleep(1)
      while (!dut.eu.logic.s2Valid.toBoolean) { cd.waitSampling(); sleep(1) }

      issueAdd(dut, pa = 1, pb = 2, pdst = 9, robId = 31)
      sleep(1)
      assert(!dut.src.logic.iReady.toBoolean, "FAST must be blocked while S2 reserves next S3")
      cd.waitSampling(); sleep(1)
      assert(dut.src.logic.cValid.toBoolean && dut.src.logic.cRob.toInt == 30,
        "the slow operation must complete on the reservation cycle")
      assert(!dut.eu.logic.fastFire.toBoolean && dut.eu.logic.s3Valid.toBoolean,
        "reservation cycle did not isolate slow S3 writeback")
      assert(dut.src.logic.iReady.toBoolean, "FAST must be accepted immediately after the one collision cycle")
      cd.waitSampling(); sleep(1)
      idle(dut)
      assert(dut.src.logic.cValid.toBoolean && dut.src.logic.cRob.toInt == 31,
        "the held FAST operation did not complete after its single blocked cycle")
      assert(dut.eu.logic.fastFire.toBoolean && !dut.eu.logic.s3Valid.toBoolean,
        "fast completion did not occupy its exclusive writeback cycle")
    }
  }

  test("flush kills every in-flight slow completion, wakeup, and stale write", VerilatorTest) {
    M68kSim().compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      initPorts(dut); idle(dut)
      dut.src.logic.obsIntAddr #= 0; dut.src.logic.obsNzvcAddr #= 0; dut.src.logic.obsXAddr #= 0
      cd.waitSampling(80)
      issueMoveq(dut, 1, pdst = 3, robId = 1); cd.waitSampling()
      for (i <- 0 until 4) { issueMoveq(dut, 0x40 + i, pdst = 20 + i, robId = 2 + i); cd.waitSampling() }
      idle(dut); cd.waitSampling(6)

      for (i <- 0 until 4) {
        // ROB IDs are five bits; keep killed IDs distinct from the setup/reuse IDs.
        issueLslImm(dut, pa = 3, count = i + 1, pdst = 20 + i, robId = 8 + i)
        sleep(1); assert(dut.src.logic.iReady.toBoolean)
        cd.waitSampling(); sleep(1)
      }
      // Advance until the oldest killed token is live in final S3. Assert flush
      // in that exact cycle, while the three younger tokens remain dense behind
      // it, so output gating and every valid-stage kill are both exercised.
      idle(dut)
      var waitedForS3 = 0
      while (!dut.eu.logic.s3Valid.toBoolean && waitedForS3 < 8) {
        cd.waitSampling(); sleep(1); waitedForS3 += 1
      }
      assert(dut.eu.logic.s3Valid.toBoolean, "directed flush never reached a live S3 token")
      dut.src.logic.iFlush #= true; sleep(1)
      assert(!dut.src.logic.iReady.toBoolean)
      assert(!dut.src.logic.cValid.toBoolean && !dut.src.logic.slowWakeValid.toBoolean)
      cd.waitSampling(); sleep(1)
      dut.src.logic.iFlush #= false

      // Immediately reuse one destination.  No killed slow result may overwrite it.
      issueMoveq(dut, 0xA5, pdst = 20, robId = 12); cd.waitSampling(); sleep(1); idle(dut)
      for (_ <- 0 until 10) {
        assert(!dut.src.logic.slowWakeValid.toBoolean, "stale slow wakeup escaped after flush")
        assert(!(dut.src.logic.cValid.toBoolean && (8 until 12).contains(dut.src.logic.cRob.toInt)),
          s"stale slow completion escaped after flush: rob=${dut.src.logic.cRob.toInt}")
        cd.waitSampling(); sleep(1)
      }
      dut.src.logic.obsIntAddr #= 20; sleep(1)
      assert(dut.src.logic.obsIntData.toBigInt == 0xA5,
        s"killed slow write overwrote reused physreg: 0x${dut.src.logic.obsIntData.toBigInt.toString(16)}")
    }
  }
}
