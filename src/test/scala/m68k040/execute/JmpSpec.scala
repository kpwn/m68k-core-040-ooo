package m68k040.execute

import m68k040.{M68kSim, VerilatorTest}
import m68k040.execute.iq.IqContext
import m68k040.execute.regfile.{IntRegFileService, NzvcRegFileService,
  RegFilePluginInt, RegFilePluginNzvc, RegFileWritePort}
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** Drives an INDIRECT (ibranch) branch into the BranchEu: target = psrcA + imm,
  * unconditional redirect. Owns an int write port to preload psrcA and an NZVC
  * write port (the EU still reads NZVC even for an unconditional ibranch). */
class IbranchSourcePlugin extends FiberPlugin {
  var intW: RegFileWritePort = null
  var nzW:  RegFileWritePort = null

  during setup {
    intW = host[IntRegFileService].newWrite(latency = 1)
    nzW  = host[NzvcRegFileService].newWrite(latency = 1)
  }

  val logic = during build new Area {
    m68k040.Global.PHYS_INT_REGS.set(48)
    val eu = host[BranchEuService]

    val iValid     = in Bool ()
    val iPc        = in UInt (32 bits)
    val iImm       = in Bits (32 bits)
    val iPsrcA     = in UInt (6 bits)
    val iPsrcAValid= in Bool ()
    val iRobId     = in UInt (6 bits)

    val ctx = IqContext()
    val uop = ctx.uop
    uop.valid        := False
    uop.cluster      := m68k040.isa.Cluster.INT
    uop.op           := m68k040.decode.DecOp.BRANCH
    uop.size         := m68k040.isa.Size.LONG
    uop.memOp        := m68k040.isa.MemOp.NONE
    uop.useImm       := True
    uop.imm          := iImm
    uop.unimplemented:= False
    uop.anInc := 0; uop.stkPush := False; uop.ccrRestore := False
    uop.dstArch      := 0
    uop.psrcA        := iPsrcA; uop.psrcAValid := iPsrcAValid
    uop.psrcB        := 0; uop.psrcBValid := False
    uop.psrcC        := 0; uop.psrcCValid := False
    uop.pdst         := 0; uop.pdstValid  := False; uop.pdstOld := 0
    uop.pNzvcDst     := 0; uop.writesNzvc := False; uop.pNzvcOld := 0
    uop.readsX       := False; uop.pXSrc := 0
    uop.pXDst        := 0; uop.writesX := False; uop.pXOld := 0
    uop.isBranch     := True
    uop.ibranch      := True
    uop.cond         := 0           // unconditional (T); ibranch ignores cond
    uop.pc           := iPc
    uop.branchDisp   := 0
    uop.pNzvcSrc     := 0
    uop.readsNzvc    := False
    uop.isCondTrap   := False; uop.isScc := False; uop.isDbcc := False
    uop.faulted      := False; uop.faultVector := 0; uop.faultUsesNextPc := False
    uop.faultAddr    := 0; uop.sswInstr := False; uop.isRte := False
    uop.divSigned := False; uop.div64 := False; uop.divIsRem := False
    uop.isChk2 := False
    uop.firstOfInstr := True
    uop.predTaken    := False; uop.predTarget := 0
    uop.nextPc       := iPc + 2
    ctx.robId        := iRobId

    eu.issue.valid   := iValid
    eu.issue.payload := ctx

    val wValid = in Bool ()
    val wAddr  = in UInt (6 bits)
    val wData  = in Bits (32 bits)
    intW.valid   := wValid
    intW.address := wAddr
    intW.data    := wData
    nzW.valid := False; nzW.address := 0; nzW.data := 0

    val cValid      = out Bool ();        cValid      := eu.completion.valid
    val cRob        = out UInt (6 bits);  cRob        := eu.completion.payload.robId
    val cMispredict = out Bool ();        cMispredict := eu.completion.payload.mispredict
    val cNextPc     = out UInt (32 bits); cNextPc     := eu.completion.payload.nextPc
  }
}

class JmpSpec extends AnyFunSuite {
  class Dut extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val rfInt  = new RegFilePluginInt
    val rfNzvc = new RegFilePluginNzvc
    val eu     = new BranchEuPlugin
    val src    = new IbranchSourcePlugin
    db.on { host.asHostOf(Seq[FiberPlugin](rfInt, rfNzvc, eu, src)) }
  }

  test("ibranch: target = psrcA + imm, unconditional redirect", VerilatorTest) {
    M68kSim().compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      val s = dut.src.logic
      s.iValid #= false; s.wValid #= false; s.wAddr #= 0; s.wData #= 0
      s.iPc #= 0; s.iImm #= 0; s.iPsrcA #= 0; s.iPsrcAValid #= false; s.iRobId #= 0
      cd.waitSampling(80)

      var rob = 0
      var failures = List.empty[String]

      // Cases: (psrcAValid, base in reg, imm) -> expect target = base + imm.
      // base=0 case models (xxx).L / (d16,PC) (assembler folds, psrcAValid=false).
      val cases = Seq(
        (true,  0x40800100L, 0x00000000L),  // (An)
        (true,  0x40800100L, 0x00000010L),  // (d16,An) positive disp
        (true,  0x40800100L, 0xFFFFFFF0L),  // (d16,An) negative disp (-16)
        (false, 0L,          0x40802000L),  // (xxx).L absolute
        (false, 0L,          0x40800050L)   // (d16,PC) pre-folded
      )

      for (((avalid, base, imm), idx) <- cases.zipWithIndex) {
        val pAddr = (idx % 40) + 1
        if (avalid) {
          s.wValid #= true; s.wAddr #= pAddr; s.wData #= BigInt(base)
          cd.waitSampling(); s.wValid #= false; cd.waitSampling(2)
        }
        s.iValid #= true
        s.iPc #= 0x1000L + idx * 0x100L
        s.iImm #= BigInt(imm)
        s.iPsrcA #= (if (avalid) pAddr else 0)
        s.iPsrcAValid #= avalid
        s.iRobId #= rob
        cd.waitSampling()
        s.iValid #= false

        var saw = false; var gotMis = false; var gotNext = BigInt(0)
        for (_ <- 0 until 4 if !saw) {
          if (s.cValid.toBoolean && s.cRob.toInt == rob) {
            saw = true; gotMis = s.cMispredict.toBoolean; gotNext = s.cNextPc.toBigInt
          }
          cd.waitSampling()
        }
        val expBase = if (avalid) base else 0L
        val expNext = (BigInt(expBase) + BigInt(imm)) & BigInt("FFFFFFFF", 16)
        if (!saw) failures ::= s"case=$idx rob=$rob: NO completion"
        else {
          if (!gotMis) failures ::= s"case=$idx: ibranch must redirect (mispredict=true)"
          if (gotNext != expNext) failures ::= f"case=$idx: nextPc=0x$gotNext%x expected 0x$expNext%x"
        }
        rob = (rob + 1) & 0x3F
      }
      assert(failures.isEmpty, s"ibranch mismatches:\n${failures.reverse.mkString("\n")}")
    }
  }
}
