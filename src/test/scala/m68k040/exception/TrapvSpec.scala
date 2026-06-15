package m68k040.exception

import m68k040.{M68kSim, VerilatorTest}
import m68k040.execute.{BranchEuPlugin, BranchEuService}
import m68k040.execute.iq.IqContext
import m68k040.execute.regfile.{NzvcRegFileService, RegFilePluginNzvc, RegFilePluginInt, RegFileWritePort}
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** TRAPV (0x4E76): execute-time CONDITIONAL trap. The decoder emits a branch-class
  * cond-trap µop (isCondTrap, cond=9=VS, readsNzvc). At execute the branch EU reads
  * NZVC; if V=1 it drives a trapvFault completion (vector 7 implied, faultPc = nextPc)
  * so the ROB marks the entry faulted; if V=0 the µop completes as a no-op and retires.
  *
  * This unit test drives the branch EU directly (the model for the V read) and
  * observes the trapvFault completion. */
class TrapvSpec extends AnyFunSuite {
  class Src extends FiberPlugin {
    var nzW: RegFileWritePort = null
    during setup { nzW = host[NzvcRegFileService].newWrite(latency = 1) }
    val logic = during build new Area {
      val eu = host[BranchEuService]
      val iValid    = in Bool ()
      val iPNzvcSrc = in UInt (4 bits)
      val iRobId    = in UInt (6 bits)
      val iNextPc   = in UInt (32 bits)

      val ctx = IqContext(); val uop = ctx.uop
      // Default-drive every uop field (the bundle has grown many fields — ibranch/anInc/
      // stkPush/ccrRestore/div*/shift*/isMovea/toCcr/... — since this stub was written);
      // the TRAPV path reads only the branch/NZVC fields, explicitly set below.
      uop.assignDontCare()
      uop.valid := False; uop.cluster := m68k040.isa.Cluster.INT
      uop.op := m68k040.decode.DecOp.ILLEGAL; uop.size := m68k040.isa.Size.WORD
      uop.useImm := False; uop.imm := 0; uop.unimplemented := False
      uop.dstArch := 0; uop.psrcA := 0; uop.psrcAValid := False
      uop.psrcB := 0; uop.psrcBValid := False
      uop.psrcC := 0; uop.psrcCValid := False
      uop.pdst := 0; uop.pdstValid := False; uop.pdstOld := 0
      uop.pNzvcDst := 0; uop.writesNzvc := False; uop.pNzvcOld := 0
      uop.readsX := False; uop.pXSrc := 0; uop.pXDst := 0; uop.writesX := False; uop.pXOld := 0
      uop.faulted := False; uop.faultVector := 0; uop.isRte := False
      uop.faultAddr := 0; uop.sswInstr := False
      // TRAPV cond-trap µop: a branch-class uop reading NZVC, marked isCondTrap.
      // cond = 9 (VS): the EU evaluates taken=v -> trapvFault if V=1. Redirect is
      // suppressed by the `isCondTrap` gate regardless of `taken`.
      uop.isBranch := True; uop.cond := 9; uop.branchDisp := 0
      uop.isCondTrap := True; uop.isScc := False; uop.isDbcc := False
      // branch-EU control fields the TRAPV path must NOT trigger (call/return + line-5):
      uop.ibranch := False; uop.anInc := 0; uop.stkPush := False; uop.ccrRestore := False
      uop.pc := 0x2000; uop.nextPc := iNextPc; uop.faultUsesNextPc := True
      uop.pNzvcSrc := iPNzvcSrc; uop.readsNzvc := True
      ctx.robId := iRobId
      eu.issue.valid := iValid; eu.issue.payload := ctx

      val wValid = in Bool (); val wAddr = in UInt (4 bits); val wData = in Bits (4 bits)
      nzW.valid := wValid; nzW.address := wAddr; nzW.data := wData

      // Branch EU completion (the entry must complete so it can retire) + the
      // trapv-fault output (vector 7 if V).
      val cValid   = out Bool ();        cValid   := eu.completion.valid
      val cRob     = out UInt (6 bits);  cRob     := eu.completion.payload.robId
      val cMis     = out Bool ();        cMis     := eu.completion.payload.mispredict
      val cNextPc  = out UInt (32 bits); cNextPc  := eu.completion.payload.nextPc
      val tvValid  = out Bool ();        tvValid  := eu.trapvFault.valid
      val tvRob    = out UInt (6 bits);  tvRob    := eu.trapvFault.payload.robId
    }
  }
  class Dut extends Component {
    val db = new Database; val host = db on (new PluginHost)
    val rfInt  = new RegFilePluginInt   // branch-EU ibranch (call/return) reads IntRegFileService
    val rfNzvc = new RegFilePluginNzvc
    val eu  = new BranchEuPlugin
    val src = new Src
    db.on { host.asHostOf(Seq[FiberPlugin](rfInt, rfNzvc, eu, src)) }
  }

  def runOne(vSet: Boolean): (Boolean, Boolean, Boolean) = {
    var sawComplete = false; var sawTrapv = false; var mis = false
    M68kSim().compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10); val s = dut.src.logic
      s.iValid #= false; s.wValid #= false; s.wAddr #= 0; s.wData #= 0
      s.iPNzvcSrc #= 0; s.iRobId #= 5; s.iNextPc #= 0x3000
      cd.waitSampling(80)
      // V=bit1. Preload addr 1 with V set/clear.
      s.wValid #= true; s.wAddr #= 1; s.wData #= (if (vSet) 0x2 else 0x0)
      cd.waitSampling(); s.wValid #= false; cd.waitSampling(2)
      s.iValid #= true; s.iPNzvcSrc #= 1; s.iRobId #= 5
      cd.waitSampling(); s.iValid #= false
      for (_ <- 0 until 5) {
        if (dut.src.logic.cValid.toBoolean && dut.src.logic.cRob.toInt == 5) {
          sawComplete = true; mis = dut.src.logic.cMis.toBoolean
        }
        if (dut.src.logic.tvValid.toBoolean && dut.src.logic.tvRob.toInt == 5) {
          sawTrapv = true
        }
        cd.waitSampling()
      }
    }
    (sawComplete, sawTrapv, mis)
  }

  test("TRAPV with V=1 -> trapvFault fires, entry completes, not a mispredict", VerilatorTest) {
    val (complete, trapv, mis) = runOne(vSet = true)
    assert(complete, "TRAPV entry must complete so it can retire")
    assert(trapv, "TRAPV with V=1 must drive a trapvFault completion")
    assert(!mis, "TRAPV is not a branch redirect (mispredict must be false)")
  }
  test("TRAPV with V=0 -> no trapvFault, entry completes (retires as no-op)", VerilatorTest) {
    val (complete, trapv, mis) = runOne(vSet = false)
    assert(complete, "TRAPV entry must complete")
    assert(!trapv, "TRAPV with V=0 must NOT fault")
    assert(!mis, "TRAPV is not a branch redirect")
  }
}
