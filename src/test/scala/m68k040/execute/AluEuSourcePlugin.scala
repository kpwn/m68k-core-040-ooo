package m68k040.execute

import m68k040.execute.iq.IqContext
import m68k040.execute.regfile.{IntRegFileService, NzvcRegFileService, XRegFileService}
import m68k040.decode.DecOp
import m68k040.isa.Size
import spinal.core._
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin

/** Drives AluEu.issue from IO; provides int+NZVC+X PRF read ports to observe results. */
class AluEuSourcePlugin extends FiberPlugin {
  import m68k040.execute.regfile.{RegFileReadPort}
  var obsInt: RegFileReadPort = null
  var obsNzvc: RegFileReadPort = null
  var obsX: RegFileReadPort = null

  during setup {
    obsInt  = host[IntRegFileService].newRead(forceNoBypass = true)
    obsNzvc = host[NzvcRegFileService].newRead(forceNoBypass = true)
    obsX    = host[XRegFileService].newRead(forceNoBypass = true)
  }

  val logic = during build new Area {
    val eu = host[AluEuService]
    // Default the committed-SR-system-byte input (the standalone ALU DUTs never run a
    // MOVE-from-SR op, so 0 is harmless). The full-core DUT wires it from exc.ss.srSys.
    host[m68k040.execute.AluEuPlugin].srSysIn := U(0, 8 bits)

    // issue inputs (sim-driven)
    val iValid    = in Bool ()
    val iOp       = in(DecOp())
    val iSize     = in(Size())
    val iUseImm   = in Bool ()
    val iImm      = in Bits (32 bits)
    val iPsrcA    = in UInt (6 bits); val iPsrcAValid = in Bool ()
    val iPsrcB    = in UInt (6 bits); val iPsrcBValid = in Bool ()
    val iPdst     = in UInt (6 bits); val iPdstValid  = in Bool ()
    val iWritesNz = in Bool ();       val iPNzvcDst   = in UInt (4 bits)
    val iWritesX  = in Bool ();       val iPXDst      = in UInt (4 bits)
    val iRobId    = in UInt (6 bits)
    val iReady    = out Bool ()
    // shift / CCR-RMW / flag-source controls (slow path)
    val iShiftOp  = in Bits (2 bits); val iShiftDir = in Bool ()
    val iToCcr    = in Bool ()
    val iReadsNz  = in Bool ();        val iPNzvcSrc  = in UInt (4 bits)
    val iReadsX   = in Bool ();        val iPXSrc     = in UInt (4 bits)

    val ctx = IqContext()
    val uop = ctx.uop
    // drive EVERY RenamedUop field (no latch); defaults for fields not exercised
    uop.valid        := False
    uop.pc           := U(0)
    uop.cluster      := m68k040.isa.Cluster.INT
    uop.isBranch     := False
    uop.cond         := B(0)
    uop.branchDisp   := B(0)
    uop.unimplemented:= False
    uop.ibranch := False; uop.anInc := 0; uop.stkPush := False; uop.ccrRestore := False
    uop.dstArch      := U(0)
    uop.pdstOld      := U(0)
    uop.pNzvcSrc     := iPNzvcSrc; uop.readsNzvc := iReadsNz
    uop.pNzvcOld     := U(0)
    uop.pXSrc        := iPXSrc; uop.readsX     := iReadsX
    uop.pXOld        := U(0)
    uop.op        := iOp
    uop.size      := iSize
    uop.useImm    := iUseImm
    uop.imm       := iImm
    uop.psrcA     := iPsrcA; uop.psrcAValid := iPsrcAValid
    uop.psrcB     := iPsrcB; uop.psrcBValid := iPsrcBValid
    uop.psrcC := 0; uop.psrcCValid := False
    uop.pdst      := iPdst;  uop.pdstValid  := iPdstValid
    uop.writesNzvc := iWritesNz; uop.pNzvcDst := iPNzvcDst
    uop.writesX    := iWritesX;  uop.pXDst    := iPXDst
    uop.shiftOp    := iShiftOp;  uop.shiftDir := iShiftDir
    uop.bcdSub     := False
    uop.toCcr      := iToCcr
    // remaining RenamedUop fields (defaults; not exercised by the ALU EU directly).
    uop.nextPc       := U(0)
    uop.memOp        := m68k040.isa.MemOp.NONE
    uop.faulted      := False; uop.faultVector := U(0); uop.isRte := False
    uop.faultUsesNextPc := False
    uop.isCondTrap   := False
    uop.faultAddr    := U(0); uop.sswInstr := False
    uop.divSigned    := False; uop.div64 := False; uop.divIsRem := False
    uop.isChk2 := False
    uop.extByte      := False
    uop.isMovea      := False
    uop.isScc        := False; uop.isDbcc := False
    uop.bitOp        := 0
    uop.indexLong    := False; uop.indexScale := 0
    uop.leaAddr      := False; uop.fromCcr := False; uop.fromSr := False
    uop.needsSupervisor := False; uop.keepCommit := False
    uop.firstOfInstr := False
    uop.predTaken    := False; uop.predTarget := 0
    // Fields added to RenamedUop after this stub was written (bit-field/system-op/EA-auto/
    // gshare carry). The ALU EU does not consume them, but every bundle field needs a
    // driver (else PhaseCheck_noLatchNoOverride aborts elaboration). Safe defaults.
    uop.eaAuto       := m68k040.decode.EaAuto.NONE; uop.eaDelta := 0
    uop.bfOp         := 0; uop.bfDynamic := False; uop.bfMem := False; uop.bfStoreForm := 0
    uop.sysOp        := False; uop.sysKind := m68k040.decode.SysKind.NONE; uop.sysReadDir := False
    uop.phtValid     := False; uop.phtIndex := 0
    ctx.robId := iRobId
    eu.issue.valid   := iValid
    eu.issue.payload := ctx
    iReady := eu.issue.ready

    // completion observation
    val cValid = out Bool (); val cRob = out UInt (6 bits)
    cValid := eu.completion.valid
    cRob   := eu.completion.payload

    // PRF observation reads
    val obsIntAddr  = in UInt (6 bits); obsInt.addr := obsIntAddr
    val obsIntData  = out Bits (32 bits); obsIntData := obsInt.data
    val obsNzvcAddr = in UInt (4 bits); obsNzvc.addr := obsNzvcAddr
    val obsNzvcData = out Bits (4 bits); obsNzvcData := obsNzvc.data
    val obsXAddr    = in UInt (4 bits); obsX.addr := obsXAddr
    val obsXData    = out Bits (1 bits); obsXData := obsX.data
  }
}
