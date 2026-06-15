package m68k040.execute

import m68k040.{M68kSim, VerilatorTest}
import m68k040.execute.iq.IqContext
import m68k040.execute.regfile.{IntRegFileService, NzvcRegFileService,
  RegFilePluginInt, RegFilePluginNzvc, RegFileReadPort, RegFileWritePort}
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** DIVU.W/DIVS.W directed test on the DivEu: drive a DIV.W µop (dividend Dn in
  * psrcA, 16-bit divisor in psrcB), observe the packed result {rem16:q16}, the NZVC,
  * the overflow (V), and the DIV0 euFault (vector 5). Reads the int PRF back through a
  * dedicated read port. */
class DivWSpec extends AnyFunSuite {
  class Src extends FiberPlugin {
    var wA, wB: RegFileWritePort = null
    var rdRes: RegFileReadPort = null
    during setup {
      wA = host[IntRegFileService].newWrite(latency = 1, sharingKey = "tA")
      wB = host[IntRegFileService].newWrite(latency = 1, sharingKey = "tB")
      rdRes = host[IntRegFileService].newRead()
    }
    val logic = during build new Area {
      val eu = host[DivEuService]
      val iValid  = in Bool ()
      val iRobId  = in UInt (6 bits)
      val iSigned = in Bool ()
      val iPdst   = in UInt (6 bits)

      val ctx = IqContext(); val uop = ctx.uop
      uop.valid := False; uop.cluster := m68k040.isa.Cluster.CPLX
      uop.op := m68k040.decode.DecOp.DIV
      uop.size := m68k040.isa.Size.WORD
      uop.useImm := False; uop.imm := 0; uop.unimplemented := False
      uop.dstArch := 0
      uop.psrcA := 3; uop.psrcAValid := True       // dividend Dn (phys 3)
      uop.psrcB := 4; uop.psrcBValid := True       // 16-bit divisor (phys 4)
      uop.psrcC := 0; uop.psrcCValid := False
      uop.pdst := iPdst; uop.pdstValid := True; uop.pdstOld := 0
      uop.pNzvcSrc := 0; uop.readsNzvc := False
      uop.pNzvcDst := 1; uop.writesNzvc := True; uop.pNzvcOld := 0
      uop.readsX := False; uop.pXSrc := 0; uop.pXDst := 0; uop.writesX := False; uop.pXOld := 0
      uop.faulted := False; uop.faultVector := 0; uop.isRte := False
      uop.faultAddr := 0; uop.sswInstr := False
      uop.isBranch := False; uop.cond := 0; uop.branchDisp := 0
      uop.isCondTrap := False; uop.faultUsesNextPc := True; uop.isScc := False; uop.isDbcc := False
      uop.divSigned := iSigned; uop.div64 := False; uop.divIsRem := False
      uop.isChk2 := False
      uop.firstOfInstr := True
      uop.pc := 0x2000; uop.nextPc := 0x2002
      ctx.robId := iRobId
      eu.issue.valid := iValid; eu.issue.payload := ctx

      val wAValid = in Bool (); val wAAddr = in UInt (6 bits); val wAData = in Bits (32 bits)
      val wBValid = in Bool (); val wBAddr = in UInt (6 bits); val wBData = in Bits (32 bits)
      wA.valid := wAValid; wA.address := wAAddr; wA.data := wAData
      wB.valid := wBValid; wB.address := wBAddr; wB.data := wBData

      val rdAddr = in UInt (6 bits); rdRes.addr := rdAddr
      val rdData = out Bits (32 bits); rdData := rdRes.data

      val cValid  = out Bool ();        cValid  := eu.completion.valid
      val cRob    = out UInt (6 bits);  cRob    := eu.completion.payload
      val fValid  = out Bool ();        fValid  := eu.euFault.valid
      val fVec    = out UInt (8 bits);  fVec    := eu.euFault.payload.vector
      val wbV     = out Bool ();        wbV     := host[DivEuPlugin].logic.wbObs.valid
      val wbNzvc  = out Bits (4 bits);  wbNzvc  := host[DivEuPlugin].logic.wbObs.nzvc
      val wbIntW  = out Bool ();        wbIntW  := host[DivEuPlugin].logic.wbObs.intWrite
    }
  }
  class Dut extends Component {
    val db = new Database; val host = db on (new PluginHost)
    val rfInt  = new RegFilePluginInt
    val rfNzvc = new RegFilePluginNzvc
    val eu  = new DivEuPlugin
    val src = new Src
    db.on { host.asHostOf(Seq[FiberPlugin](rfInt, rfNzvc, eu, src)) }
  }

  /** Returns (result32, nzvc, intWrite, faultVec). result valid only if no fault. */
  def runDivW(dn: Long, divisor: Int, signed: Boolean): (Long, Int, Boolean, Int) = {
    var result = 0L; var nzvc = 0; var intW = false; var fvec = -1
    M68kSim().withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10); val s = dut.src.logic
      s.iValid #= false; s.iRobId #= 7; s.iSigned #= signed; s.iPdst #= 20
      s.wAValid #= false; s.wBValid #= false; s.wAAddr #= 0; s.wBAddr #= 0; s.wAData #= 0; s.wBData #= 0
      s.rdAddr #= 20
      cd.waitSampling(80)
      s.wAValid #= true; s.wAAddr #= 3; s.wAData #= (dn & 0xffffffffL)
      s.wBValid #= true; s.wBAddr #= 4; s.wBData #= (divisor.toLong & 0xffff)
      cd.waitSampling(); s.wAValid #= false; s.wBValid #= false
      cd.waitSampling(3)
      s.iValid #= true; s.iRobId #= 7
      cd.waitSampling(); s.iValid #= false
      var guard = 0
      var captured = false
      while (guard < 80 && !captured) {
        if (dut.src.logic.cValid.toBoolean && dut.src.logic.cRob.toInt == 7) {
          // completion cycle: capture wbObs nzvc + intWrite
          nzvc = dut.src.logic.wbNzvc.toInt
          intW = dut.src.logic.wbIntW.toBoolean
          captured = true
        }
        if (dut.src.logic.fValid.toBoolean) fvec = dut.src.logic.fVec.toInt
        cd.waitSampling(); guard += 1
      }
      cd.waitSampling(3)
      result = dut.src.logic.rdData.toLong & 0xffffffffL
    }
    (result, nzvc, intW, fvec)
  }

  test("DIVU.W normal: 100 / 7 -> q=14 r=2", VerilatorTest) {
    val (res, nzvc, intW, fvec) = runDivW(100, 7, signed = false)
    assert(fvec == -1, "no fault expected")
    assert(intW, "result must be written")
    assert((res & 0xffff) == 14, f"quotient=${res & 0xffff} expected 14 (res=$res%08x)")
    assert(((res >> 16) & 0xffff) == 2, f"remainder=${(res >> 16) & 0xffff} expected 2")
    assert((nzvc & 0x2) == 0, "V must be 0 (no overflow)")
  }

  test("DIVU.W overflow: 0xFFFFFFFF / 1 -> V=1, no write", VerilatorTest) {
    val (_, nzvc, intW, fvec) = runDivW(0xFFFFFFFFL, 1, signed = false)
    assert(fvec == -1, "overflow is NOT a trap")
    assert(!intW, "overflow must NOT write the result")
    assert((nzvc & 0x2) != 0, "V must be 1 on overflow")
  }

  test("DIVU.W DIV0: divisor 0 -> euFault vector 5", VerilatorTest) {
    val (_, _, intW, fvec) = runDivW(100, 0, signed = false)
    assert(fvec == 5, s"DIV0 must raise euFault vector 5, got $fvec")
    assert(!intW, "DIV0 must NOT write the result")
  }

  test("DIVS.W negative: -100 / 7 -> q=-14 r=-2", VerilatorTest) {
    val (res, _, intW, fvec) = runDivW(0xFFFFFF9CL, 7, signed = true) // -100 in 32 bits
    assert(fvec == -1 && intW)
    val q = (res & 0xffff).toShort.toInt
    val r = ((res >> 16) & 0xffff).toShort.toInt
    assert(q == -14, s"signed quotient=$q expected -14")
    assert(r == -2, s"signed remainder=$r expected -2 (dividend sign)")
  }

  test("DIVS.W overflow: -2^31 / 1 -> V=1, no write", VerilatorTest) {
    val (_, nzvc, intW, fvec) = runDivW(0x80000000L, 1, signed = true)
    assert(fvec == -1 && !intW, "signed .W overflow: V set, no write")
    assert((nzvc & 0x2) != 0, "V must be 1")
  }
}
