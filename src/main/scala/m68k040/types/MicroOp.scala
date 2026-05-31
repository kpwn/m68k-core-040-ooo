package m68k040.types

import m68k040.M68kParams
import m68k040.isa.{Cluster, MemOp, Size}
import spinal.core._

/** Internal scheduling unit (spec 4.4). NEVER commits architecturally — produces
  * only internal state. The instruction (RobEntry) is the architectural unit. */
case class MicroOp(p: M68kParams) extends Bundle {
  val robId        = UInt(p.robIdWidth bits)
  val uopIdx       = UInt(2 bits)            // up to 4 µops/instruction
  val lastUop      = Bool()
  val cluster      = Cluster()
  val opClass      = Bits(8 bits)            // decoded op selector (refined per cluster later)
  val staticLatency= UInt(4 bits)            // ready_cycle = issue_cycle + staticLatency

  // integer source/dest physical registers
  val psrc0        = UInt(p.physIntIdWidth bits)
  val psrc1        = UInt(p.physIntIdWidth bits)
  val src0Ready    = Bool()
  val src1Ready    = Bool()
  val pdst         = UInt(p.physIntIdWidth bits)
  val pdstValid    = Bool()
  val archDst      = UInt(log2Up(m68k040.isa.Isa.ARCH_INT_REGS) bits)

  // split-renamed flags (spec 4.6): NZVC and X independent
  val pNzvcSrc     = UInt(p.physNzvcIdWidth bits)
  val pXSrc        = UInt(p.physXIdWidth bits)
  val readsNzvc    = Bool()
  val readsX       = Bool()
  val pNzvcDst     = UInt(p.physNzvcIdWidth bits)
  val pXDst        = UInt(p.physXIdWidth bits)
  val writesNzvc   = Bool()
  val writesX      = Bool()

  val size         = Size()
  val imm          = Bits(32 bits)

  // memory (spec 4.4 / 7.1.1): up to two accesses for misaligned line/page crossing
  val memOp        = MemOp()
  val twoAccess    = Bool()
  val sqPtr0       = UInt(p.sqPtrWidth bits)
  val sqPtr1       = UInt(p.sqPtrWidth bits)

  val mayTrap      = Bool()
}
