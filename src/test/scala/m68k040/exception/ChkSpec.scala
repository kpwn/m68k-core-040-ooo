package m68k040.exception

import m68k040.{M68kSim, VerilatorTest}
import m68k040.execute.{DivEuPlugin, DivEuService}
import m68k040.execute.iq.IqContext
import m68k040.execute.regfile.{IntRegFileService, NzvcRegFileService,
  RegFilePluginInt, RegFilePluginNzvc, RegFileWritePort}
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** CHK (vector 6): execute-time bound-check trap on the DivEu (CPLX cluster). The
  * decoder emits a CHK µop reading Dn (srcA) + the bound (srcB/EA). At execute the
  * DivEu compares: Dn<0 (N=1) or Dn>bound (N=0) -> euFault{vector 6}; else a no-op
  * completion. This unit test drives the DivEu directly and observes the euFault +
  * the N-flag observation. Validates the GENERALIZED euFault (carries the vector). */
class ChkSpec extends AnyFunSuite {
  class Src extends FiberPlugin {
    var wA, wB: RegFileWritePort = null
    during setup {
      wA = host[IntRegFileService].newWrite(latency = 1, sharingKey = "tA")
      wB = host[IntRegFileService].newWrite(latency = 1, sharingKey = "tB")
    }
    val logic = during build new Area {
      val eu = host[DivEuService]
      val iValid  = in Bool ()
      val iRobId  = in UInt (m68k040.Global.ROB_ID_W_DEFAULT bits)
      val iPsrcA  = in UInt (6 bits)
      val iPsrcB  = in UInt (6 bits)
      val iWord   = in Bool ()   // size: word vs long

      val ctx = IqContext(); val uop = ctx.uop
      uop.valid := False; uop.cluster := m68k040.isa.Cluster.CPLX
      uop.op := m68k040.decode.DecOp.CHK
      uop.size := Mux(iWord, m68k040.isa.Size.WORD, m68k040.isa.Size.LONG)
      uop.useImm := False; uop.imm := 0; uop.unimplemented := False
      uop.dstArch := 0
      uop.psrcA := iPsrcA; uop.psrcAValid := True
      uop.psrcB := iPsrcB; uop.psrcBValid := True
      uop.psrcC := 0; uop.psrcCValid := False
      uop.pdst := 0; uop.pdstValid := False; uop.pdstOld := 0
      uop.pNzvcSrc := 0; uop.readsNzvc := False
      uop.pNzvcDst := 1; uop.writesNzvc := True; uop.pNzvcOld := 0
      uop.readsX := False; uop.pXSrc := 0; uop.pXDst := 0; uop.writesX := False; uop.pXOld := 0
      // FP-domain fields: the CPLX EU now also carries the FP writeback lane and reads these
      // on every issued uop, so they must be DRIVEN (not left floating) even though no uop in
      // this spec is an FP uop. Inert values -- op is never DecOp.FPU here.
      uop.pFpSrcA := 0; uop.psrcAFpValid := False
      uop.pFpSrcB := 0; uop.psrcBFpValid := False
      uop.pFpDst := 0; uop.pFpDstValid := False; uop.pFpOld := 0; uop.fpDstArch := 0
      uop.pFpccSrc := 0; uop.readsFpcc := False
      uop.pFpccDst := 0; uop.writesFpcc := False; uop.pFpccOld := 0
      uop.fpuOp := 0; uop.fpSrcKind := m68k040.decode.FpSrcKind.FPREG
      uop.fpSrcFmt := 0;
      uop.faulted := False; uop.faultVector := 0; uop.isRte := False
      uop.sswInstr := False
      uop.isBranch := False; uop.cond := 0; uop.branchDisp := 0
      uop.isCondTrap := False; uop.faultUsesNextPc := True; uop.isScc := False; uop.isDbcc := False
      uop.divSigned := False; uop.div64 := False; uop.divIsRem := False
      uop.isChk2 := False
      uop.firstOfInstr := True
      uop.predTaken := False; uop.predTarget := 0
      uop.pc := 0x2000; uop.nextPc := 0x2002
      ctx.robId := iRobId
      eu.issue.valid := iValid; eu.issue.payload := ctx

      val wAValid = in Bool (); val wAAddr = in UInt (6 bits); val wAData = in Bits (32 bits)
      val wBValid = in Bool (); val wBAddr = in UInt (6 bits); val wBData = in Bits (32 bits)
      wA.valid := wAValid; wA.address := wAAddr; wA.data := wAData
      wB.valid := wBValid; wB.address := wBAddr; wB.data := wBData

      val cValid  = out Bool ();        cValid  := eu.completion.valid
      val cRob    = out UInt (m68k040.Global.ROB_ID_W_DEFAULT bits);  cRob    := eu.completion.payload
      val iReady  = out Bool ();        iReady  := eu.issue.ready
      val fValid  = out Bool ();        fValid  := eu.euFault.valid
      val fRob    = out UInt (m68k040.Global.ROB_ID_W_DEFAULT bits);  fRob    := eu.euFault.payload.robId
      val fVec    = out UInt (8 bits);  fVec    := eu.euFault.payload.vector
      val nObs    = out Bool ();        nObs    := host[DivEuPlugin].logic.chkNObs
      val wbNzvcWrite = out Bool ();    wbNzvcWrite := host[DivEuPlugin].logic.wbObs.valid && host[DivEuPlugin].logic.wbObs.nzvcWrite
      val nzWakeValid = out Bool ();    nzWakeValid := eu.wakeupNzvc.valid
    }
  }
  class Dut extends Component {
    val db = new Database; val host = db on (new PluginHost)
    val rfInt  = new RegFilePluginInt
    val rfNzvc = new RegFilePluginNzvc
    val eu  = new DivEuPlugin
    val src = new Src
    // The CPLX EU now also carries the FP writeback lane, so its FP-data/FPCC physical
    // files must be present for it to elaborate. Unused by this spec.
    val rfFp   = new m68k040.execute.regfile.RegFilePluginFp
    val rfFpcc = new m68k040.execute.regfile.RegFilePluginFpcc
    db.on { host.asHostOf(Seq[FiberPlugin](new m68k040.core.ParamPlugin(m68k040.M68kParams()), rfInt, rfNzvc, rfFp, rfFpcc, eu, new m68k040.execute.FpImmTableStub, src)) }
  }

  /** Returns (complete, fault, vector, nFlag, architectural-NZVC observation,
    * physical-NZVC wakeup). A faulting CHK keeps its flags in ccrObs for stacking,
    * but does not wake the rolled-back renamed destination. */
  def runOne(dn: Long, bound: Long, word: Boolean): (Boolean, Boolean, Int, Boolean, Boolean, Boolean) = {
    var sawComplete = false; var sawFault = false; var vec = -1; var nAtIssue = false
    var sawNzvcObs = false; var sawNzvcWake = false
    M68kSim().compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10); val s = dut.src.logic
      s.iValid #= false; s.iRobId #= 7; s.iPsrcA #= 3; s.iPsrcB #= 4; s.iWord #= word
      s.wAValid #= false; s.wBValid #= false; s.wAAddr #= 0; s.wBAddr #= 0; s.wAData #= 0; s.wBData #= 0
      cd.waitSampling(80)   // let the int RF init-zero sweep (depth+1 cycles) finish
      // Preload phys reg 3 = Dn, phys reg 4 = bound.
      s.wAValid #= true; s.wAAddr #= 3; s.wAData #= (dn & 0xffffffffL)
      s.wBValid #= true; s.wBAddr #= 4; s.wBData #= (bound & 0xffffffffL)
      cd.waitSampling(); s.wAValid #= false; s.wBValid #= false
      cd.waitSampling(3)
      s.iValid #= true; s.iPsrcA #= 3; s.iPsrcB #= 4; s.iRobId #= 7
      var issueGuard = 0
      while (!s.iReady.toBoolean && issueGuard < 20) { cd.waitSampling(); issueGuard += 1 }
      assert(s.iReady.toBoolean, "CHK source never observed issue.ready")
      cd.waitSampling()
      if (dut.src.logic.nObs.toBoolean) nAtIssue = true
      s.iValid #= false
      for (_ <- 0 until 6) {
        if (dut.src.logic.cValid.toBoolean && dut.src.logic.cRob.toInt == 7) sawComplete = true
        if (dut.src.logic.fValid.toBoolean && dut.src.logic.fRob.toInt == 7) { sawFault = true; vec = dut.src.logic.fVec.toInt }
        if (dut.src.logic.nObs.toBoolean) nAtIssue = true
        if (dut.src.logic.wbNzvcWrite.toBoolean) sawNzvcObs = true
        if (dut.src.logic.nzWakeValid.toBoolean) sawNzvcWake = true
        cd.waitSampling()
      }
    }
    (sawComplete, sawFault, vec, nAtIssue, sawNzvcObs, sawNzvcWake)
  }

  test("CHK.W in-bounds (0 <= Dn <= bound) -> no trap, completes", VerilatorTest) {
    val (complete, fault, _, _, nzvcObs, nzvcWake) = runOne(dn = 5, bound = 10, word = true)
    assert(complete, "in-bounds CHK must complete (retire as no-op)")
    assert(!fault, "in-bounds CHK must NOT fault")
    assert(nzvcObs && nzvcWake, "live CHK must observe and wake its NZVC destination")
  }

  test("CHK.W Dn<0 -> trap vector 6, N=1", VerilatorTest) {
    val (complete, fault, vec, n, nzvcObs, nzvcWake) = runOne(dn = 0xfffffffbL, bound = 10, word = true) // -5
    assert(fault, "Dn<0 must trap")
    assert(vec == 6, s"CHK trap vector must be 6, got $vec")
    assert(n, "Dn<0 must set N=1")
    assert(complete, "the faulting CHK must also complete so it can retire")
    assert(nzvcObs && !nzvcWake, "faulting CHK flags must be stacked, not wake a rolled-back NZVC pdst")
  }

  test("CHK.W Dn>bound -> trap vector 6, N=0", VerilatorTest) {
    val (_, fault, vec, n, nzvcObs, nzvcWake) = runOne(dn = 20, bound = 10, word = true)
    assert(fault, "Dn>bound must trap")
    assert(vec == 6, s"CHK trap vector must be 6, got $vec")
    assert(!n, "Dn>bound must set N=0")
    assert(nzvcObs && !nzvcWake)
  }

  test("CHK.L in-bounds with large 32-bit bound -> no trap", VerilatorTest) {
    val (complete, fault, _, _, nzvcObs, nzvcWake) = runOne(dn = 0x12345, bound = 0x7fffffffL, word = false)
    assert(complete && !fault, "CHK.L in-bounds must complete without trapping")
    assert(nzvcObs && nzvcWake)
  }

  test("CHK.L Dn>bound (32-bit) -> trap vector 6, N=0", VerilatorTest) {
    val (_, fault, vec, n, nzvcObs, nzvcWake) = runOne(dn = 0x40000000L, bound = 0x1000, word = false)
    assert(fault && vec == 6 && !n, "CHK.L Dn>bound must trap vec6 with N=0")
    assert(nzvcObs && !nzvcWake)
  }
}
