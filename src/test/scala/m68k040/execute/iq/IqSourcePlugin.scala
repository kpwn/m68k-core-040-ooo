package m68k040.execute.iq

import spinal.core._
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin

/** Test-only source: drives the IssueQueue push port from top-level IO.
  * All RenamedUop fields are exposed per push-slot so Task 2 can reuse this,
  * but Task 1 only needs robId (everything else defaults independent/ready). */
class IqSourcePlugin extends FiberPlugin {
  val logic = during build new Area {
    val iq = host[IssueQueueService]

    val pushValid = in Bool ()
    val slot1Valid = in Bool ()
    val flush = in Bool ()
    val pushReady = out Bool ()

    // Per-slot driving signals (k = 0, 1).
    case class SlotIo() {
      val robId      = in UInt (6 bits)
      val cluster    = in(m68k040.isa.Cluster())
      val memOp      = in(m68k040.isa.MemOp())
      val pdst       = in UInt (6 bits); val pdstValid  = in Bool ()
      val psrcA      = in UInt (6 bits); val psrcAValid = in Bool ()
      val psrcB      = in UInt (6 bits); val psrcBValid = in Bool ()
      val useImm     = in Bool ()
      val readsNzvc  = in Bool (); val writesNzvc = in Bool ()
      val pNzvcSrc   = in UInt (4 bits); val pNzvcDst = in UInt (4 bits)
      val readsX     = in Bool (); val writesX = in Bool ()
      val pXSrc      = in UInt (4 bits); val pXDst = in UInt (4 bits)
    }
    val s0 = SlotIo()
    val s1 = SlotIo()

    def mkSlot(io: SlotIo): IqContext = {
      val c = IqContext()
      c.robId := io.robId
      val u = c.uop
      // Safe defaults for unused fields so the bundle is fully driven.
      u.valid        := True
      u.pc           := 0
      u.op      := m68k040.decode.DecOp.MOVE
      u.cluster := io.cluster
      u.memOp   := io.memOp
      u.size    := m68k040.isa.Size.LONG
      u.imm          := 0
      u.isBranch     := False
      u.cond         := 0
      u.branchDisp   := 0
      u.unimplemented := False
      u.ibranch := False; u.anInc := 0; u.stkPush := False; u.ccrRestore := False
      u.dstArch      := 0
      u.pdstOld      := 0
      u.pNzvcOld     := 0
      u.pXOld        := 0
      // Wired fields.
      u.useImm       := io.useImm
      u.pdst         := io.pdst;     u.pdstValid  := io.pdstValid
      u.psrcA        := io.psrcA;    u.psrcAValid := io.psrcAValid
      u.psrcB        := io.psrcB;    u.psrcBValid := io.psrcBValid
      u.readsNzvc    := io.readsNzvc;  u.writesNzvc := io.writesNzvc
      u.pNzvcSrc     := io.pNzvcSrc;   u.pNzvcDst   := io.pNzvcDst
      u.readsX       := io.readsX;     u.writesX    := io.writesX
      u.pXSrc        := io.pXSrc;      u.pXDst      := io.pXDst
      // Third source (DIV.L 64/32) + CPLX/div control + precise-fault fields: safe
      // defaults (these IQ tests don't exercise DIV/CHK/faults).
      u.psrcC        := 0; u.psrcCValid := False
      u.divSigned    := False; u.div64 := False; u.divIsRem := False
      u.nextPc       := 0
      u.faulted      := False; u.faultVector := 0; u.faultUsesNextPc := False
      u.isRte        := False; u.isTrapv := False
      u.faultAddr    := 0; u.sswInstr := False
      u.firstOfInstr := True
      c
    }

    iq.push.valid       := pushValid
    iq.pushSlot1Valid   := slot1Valid
    iq.flushPort        := flush
    iq.push.payload(0)  := mkSlot(s0)
    iq.push.payload(1)  := mkSlot(s1)
    pushReady           := iq.push.ready

    // Dynamic LS wakeup (sim-driven; overrides the IQ's idle default).
    val lsWakeupValid = in Bool (); val lsWakeupPdst = in UInt (6 bits)
    iq.lsWakeup.valid   := lsWakeupValid
    iq.lsWakeup.payload := lsWakeupPdst
  }
}
