package m68k040.execute.regfile

import spinal.core._

case class RegfileSpec(name: String, dataWidth: Int, depth: Int) {
  def addressWidth: Int = log2Up(depth)
}
object RegfileSpec {
  // depth MUST cover the full physical-int pool the rename freelist allocates
  // (Freelist physCount = 50; the IQ scoreboards use Global.PHYS_INT_REGS = 50). It was
  // left at 48 when the 2 EA-cracking temp arch regs (T0/T1) widened the pool to 50, so
  // the PRF backing Mem under-ran: ids 48/49 — handed out only under heavy register
  // pressure (a >8-destination load burst exhausts the lower ids first) — addressed PAST
  // the 48-entry Mem, so the producing write never landed and the dependent read returned
  // an uninitialized (per-seed-random) value. addressWidth is log2Up(50)=6 = log2Up(48),
  // so all port widths are unchanged; only the Mem entry count grows (48 -> 50).
  val Int  = RegfileSpec("int",  32, 54)
  val Nzvc = RegfileSpec("nzvc", 4,  16)
  val X    = RegfileSpec("x",    1,  16)
  val Fp   = RegfileSpec("fp",   80, 16)
  // FPCC {NaN,I,Z,N} value storage for the FPCC rename class (RenameStage's
  // fpccRat/fpccFree: 16 physical entries, 4-bit tags). Shape and role are the exact
  // NZVC analogue: `Nzvc` above is the physical value store behind nzvcRat/nzvcFree, and
  // this is the same thing one condition-code group over. The internal bit layout is
  // FpResult.fpcc's {NaN(3), I(2), Z(1), N(0)}; the architectural FPSR[27:24] = {N,Z,I,NaN}
  // presentation reversal belongs to the FPSR task, not to this storage.
  val Fpcc = RegfileSpec("fpcc", 4,  16)
}

case class RegFileReadPort(addressWidth: Int, dataWidth: Int) extends Bundle {
  val addr = UInt(addressWidth bits)   // consumer drives
  val data = Bits(dataWidth bits)      // RF drives
}
case class RegFileWritePort(addressWidth: Int, dataWidth: Int) extends Bundle {
  val valid   = Bool()                 // consumer drives
  val address = UInt(addressWidth bits)
  val data    = Bits(dataWidth bits)
}
case class RegFileBypassPort(addressWidth: Int, dataWidth: Int) extends Bundle {
  val valid   = Bool()
  val address = UInt(addressWidth bits)
  val data    = Bits(dataWidth bits)
}

/** Register-file allocation service.
  *
  * Consumers MUST call `newRead`/`newWrite`/`newBypass` during their own `during setup`
  * phase (the file wires the allocated ports in `during build`, after all setups run).
  *
  * Writers that do NOT share a `sharingKey` become DISTINCT physical write ports and
  * MUST target distinct physical registers in any given cycle — two physical write
  * ports writing the same address in the same cycle corrupt silently. See the
  * precondition note in RegFilePlugin (the XOR/LVT multi-write lowering requirement).
  */
trait RegfileService {
  def spec: RegfileSpec
  def newRead(forceNoBypass: Boolean = false): RegFileReadPort
  def newWrite(latency: Int = 1, sharingKey: Any = null, priority: Int = 0): RegFileWritePort
  def newBypass(): RegFileBypassPort
}

trait IntRegFileService  extends RegfileService
trait NzvcRegFileService extends RegfileService
trait XRegFileService    extends RegfileService
trait FpRegFileService   extends RegfileService
trait FpccRegFileService extends RegfileService
