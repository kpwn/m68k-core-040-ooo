package m68k040.ls

import m68k040.execute.LsEuService
import m68k040.execute.iq.IqContext
import m68k040.execute.regfile.{IntRegFileService, RegFileReadPort, RegFileWritePort}
import m68k040.isa.{Cluster, MemOp, Size}
import spinal.core._
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin

/** Drives LsEu.issue from IO + provides an int PRF read port (observe load dst)
  * and a backdoor int PRF write port (seed the base register). */
class LsEuSourcePlugin extends FiberPlugin {
  var obsInt: RegFileReadPort = null
  var seedW:  RegFileWritePort = null

  during setup {
    obsInt = host[IntRegFileService].newRead(forceNoBypass = true)
    seedW  = host[IntRegFileService].newWrite(latency = 1)
  }

  val logic = during build new Area {
    val eu = host[LsEuService]

    // issue inputs
    val iValid   = in Bool ()
    val iMemOp   = in(MemOp())
    val iSize    = in(Size())
    val iPsrcA   = in UInt (6 bits); val iPsrcAValid = in Bool ()  // base
    val iPsrcB   = in UInt (6 bits); val iPsrcBValid = in Bool ()  // store data
    val iImm     = in Bits (32 bits)                               // displacement
    val iPdst    = in UInt (6 bits); val iPdstValid  = in Bool ()  // load dst
    val iRobId   = in UInt (6 bits)
    val iStkPush = in Bool ()                                      // stack-push store
    val iLeaAddr = in Bool ()                                      // LEA (no translate/no mem access)
    val iReady   = out Bool ()

    val ctx = IqContext()
    val uop = ctx.uop
    uop.valid        := False
    uop.pc           := U(0)
    uop.nextPc       := U(0)
    uop.cluster      := Cluster.LS
    uop.memOp        := iMemOp
    uop.op           := m68k040.decode.DecOp.MOVE
    uop.isBranch     := False
    uop.ibranch      := False
    uop.stkPush      := iStkPush
    uop.cond         := B(0)
    uop.branchDisp   := B(0)
    uop.unimplemented:= False
    uop.dstArch      := U(0)
    uop.pdstOld      := U(0)
    uop.size         := iSize
    uop.useImm       := True
    uop.imm          := iImm
    uop.psrcA        := iPsrcA; uop.psrcAValid := iPsrcAValid
    uop.psrcB        := iPsrcB; uop.psrcBValid := iPsrcBValid
    uop.psrcC := 0; uop.psrcCValid := False
    uop.pdst         := iPdst;  uop.pdstValid  := iPdstValid
    uop.writesNzvc   := False;  uop.pNzvcDst := U(0)
    uop.writesX      := False;  uop.pXDst    := U(0)
    uop.pNzvcSrc     := U(0);   uop.readsNzvc := False;  uop.pNzvcOld := U(0)
    uop.pXSrc        := U(0);   uop.readsX    := False;  uop.pXOld    := U(0)
    // Fields added by later slices (auto-EA / RTR CCR-restore / MOVEM crack markers).
    // This directed LS source exercises plain aligned load/store (no -(An)/(An)+,
    // no CCR-restore, no MOVEM crack), so default them inert.
    uop.eaAuto       := m68k040.decode.EaAuto.NONE
    uop.eaDelta      := U(0)
    uop.ccrRestore   := False
    uop.isMovea      := False
    uop.divIsRem     := False
    // Fields added by later slices that this directed LS source does not exercise —
    // default them inert so the RenamedUop is fully driven (no no-driver/latch). Track A
    // added indexLong/indexScale (indexed-EA); Track D added sysOp/sysKind/sysReadDir
    // (commit-time system ops). The remaining flag/branch/fault/shift/bcd fields are
    // likewise inert for a plain aligned load/store.
    uop.indexLong    := False; uop.indexScale := U(0)
    uop.sysOp        := False; uop.sysKind := m68k040.decode.SysKind.NONE; uop.sysReadDir := False
    // LEA address-generate, MOVE-from-CCR/SR, and the lock-step macro-commit marker
    // are likewise not exercised by this plain aligned load/store source — inert.
    uop.leaAddr      := iLeaAddr
    uop.movesAliasStore := False
    uop.fromCcr      := False; uop.fromSr := False; uop.needsSupervisor := False
    uop.keepCommit   := False
    uop.toCcr        := False
    uop.anInc        := U(0)
    uop.divSigned    := False; uop.div64 := False
    uop.shiftOp      := B(0); uop.shiftDir := False
    uop.bcdSub       := False; uop.bitOp := B(0); uop.extByte := False
    uop.isScc        := False; uop.isDbcc := False
    uop.firstOfInstr := True
    uop.faulted      := False; uop.faultVector := U(0); uop.faultUsesNextPc := False
    uop.faultAddr    := U(0); uop.sswInstr := False
    uop.isRte        := False; uop.isCondTrap := False
    uop.predTaken    := False; uop.predTarget := 0
    ctx.robId := iRobId
    eu.issue.valid   := iValid
    eu.issue.payload := ctx
    iReady := eu.issue.ready

    // completion observation
    val cValid = out Bool (); val cRob = out UInt (6 bits)
    cValid := eu.completion.valid
    cRob   := eu.completion.payload

    // MMU access-fault completion observation (Task 1).
    val fValid = out Bool ();        fValid := eu.faultCompletion.valid
    val fRob   = out UInt (6 bits);  fRob   := eu.faultCompletion.payload.robId
    val fAddr  = out UInt (32 bits); fAddr  := eu.faultCompletion.payload.faultAddr
    val fWrite = out Bool ();        fWrite := eu.faultCompletion.payload.write
    val fSize  = out UInt (2 bits);  fSize  := eu.faultCompletion.payload.sizeBits
    val fSuper = out Bool ();        fSuper := eu.faultCompletion.payload.supervisor
    val fAtc   = out Bool ();        fAtc   := eu.faultCompletion.payload.atc

    // ROB-side SQ commit / flush (sim-driven)
    val iSqCommitValid = in Bool (); val iSqCommitRob = in UInt (6 bits)
    val iSqFlush       = in Bool ()
    eu.sqCommit.valid   := iSqCommitValid
    eu.sqCommit.payload := iSqCommitRob
    eu.sqFlush          := iSqFlush

    // backdoor PRF seed (base register)
    val seedValid = in Bool (); val seedAddr = in UInt (6 bits); val seedData = in Bits (32 bits)
    seedW.valid   := seedValid
    seedW.address := seedAddr
    seedW.data    := seedData

    // PRF observation (load dst)
    val obsIntAddr = in UInt (6 bits); obsInt.addr := obsIntAddr
    val obsIntData = out Bits (32 bits); obsIntData := obsInt.data
  }
}
