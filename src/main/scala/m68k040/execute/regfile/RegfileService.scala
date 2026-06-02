package m68k040.execute.regfile

import spinal.core._

case class RegfileSpec(name: String, dataWidth: Int, depth: Int) {
  def addressWidth: Int = log2Up(depth)
}
object RegfileSpec {
  val Int  = RegfileSpec("int",  32, 48)
  val Nzvc = RegfileSpec("nzvc", 4,  16)
  val X    = RegfileSpec("x",    1,  16)
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
