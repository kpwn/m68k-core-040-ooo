package m68k040.execute.regfile

import spinal.core._
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin
import scala.collection.mutable.ArrayBuffer

class RegFilePlugin(val spec: RegfileSpec) extends FiberPlugin with RegfileService {
  private case class WriteReq(port: RegFileWritePort, latency: Int, key: Any, priority: Int)
  private val reads    = ArrayBuffer[(RegFileReadPort, Boolean)]()
  private val writeReq = ArrayBuffer[WriteReq]()
  private val bypasses = ArrayBuffer[RegFileBypassPort]()

  override def newRead(forceNoBypass: Boolean = false): RegFileReadPort = {
    val p = RegFileReadPort(spec.addressWidth, spec.dataWidth)
    reads += ((p, forceNoBypass)); p
  }
  override def newWrite(latency: Int = 1, sharingKey: Any = null, priority: Int = 0): RegFileWritePort = {
    val p = RegFileWritePort(spec.addressWidth, spec.dataWidth)
    writeReq += WriteReq(p, latency, if (sharingKey == null) new Object else sharingKey, priority); p
  }
  override def newBypass(): RegFileBypassPort = {
    val p = RegFileBypassPort(spec.addressWidth, spec.dataWidth)
    bypasses += p; p
  }

  val logic = during build new Area {
    assert(writeReq.nonEmpty, s"RegFile ${spec.name}: at least one write port required (for init)")
    val phys = writeReq.map(_.port).toSeq
    val ram = Mem(Bits(spec.dataWidth bits), spec.depth)
    for (w <- phys) ram.write(w.address, w.data, enable = w.valid)
    for ((r, _) <- reads) r.data := ram.readAsync(r.addr)
  }
}
