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

trait RegfileService {
  def spec: RegfileSpec
  def newRead(forceNoBypass: Boolean = false): RegFileReadPort
  def newWrite(latency: Int = 1, sharingKey: Any = null, priority: Int = 0): RegFileWritePort
  def newBypass(): RegFileBypassPort
}
