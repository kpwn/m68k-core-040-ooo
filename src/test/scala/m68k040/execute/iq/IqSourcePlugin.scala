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
      u.cluster := m68k040.isa.Cluster.INT
      u.size    := m68k040.isa.Size.LONG
      u.imm          := 0
      u.isBranch     := False
      u.cond         := 0
      u.branchDisp   := 0
      u.unimplemented := False
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
      c
    }

    iq.push.valid       := pushValid
    iq.pushSlot1Valid   := slot1Valid
    iq.flushPort        := flush
    iq.push.payload(0)  := mkSlot(s0)
    iq.push.payload(1)  := mkSlot(s1)
    pushReady           := iq.push.ready
  }
}
