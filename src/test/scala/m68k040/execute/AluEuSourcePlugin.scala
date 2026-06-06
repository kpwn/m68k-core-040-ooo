package m68k040.execute

import m68k040.execute.iq.IqContext
import m68k040.execute.regfile.{IntRegFileService, NzvcRegFileService}
import m68k040.decode.DecOp
import m68k040.isa.Size
import spinal.core._
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin

/** Drives AluEu.issue from IO; provides int+NZVC PRF read ports to observe results. */
class AluEuSourcePlugin extends FiberPlugin {
  import m68k040.execute.regfile.{RegFileReadPort}
  var obsInt: RegFileReadPort = null
  var obsNzvc: RegFileReadPort = null

  during setup {
    obsInt  = host[IntRegFileService].newRead(forceNoBypass = true)
    obsNzvc = host[NzvcRegFileService].newRead(forceNoBypass = true)
  }

  val logic = during build new Area {
    val eu = host[AluEuService]

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
    uop.dstArch      := U(0)
    uop.pdstOld      := U(0)
    uop.pNzvcSrc     := U(0); uop.readsNzvc := False
    uop.pNzvcOld     := U(0)
    uop.pXSrc        := U(0); uop.readsX     := False
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
  }
}
