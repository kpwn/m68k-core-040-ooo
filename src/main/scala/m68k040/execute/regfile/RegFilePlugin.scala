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
    // Merge write requests sharing a key into one physical write port; within a
    // group the highest-priority valid request wins (one-hot).
    val phys = writeReq.groupBy(_.key).values.toSeq.map { grp =>
      val sorted = grp.sortBy(-_.priority)
      val bus    = RegFileWritePort(spec.addressWidth, spec.dataWidth)
      val valids = sorted.map(_.port.valid)
      // first (highest-priority) valid, as a one-hot over `sorted`
      val anyValid = valids.reduce(_ || _)
      // priority-select address/data: fold from lowest to highest priority so highest wins
      bus.valid   := anyValid
      bus.address := sorted.foldRight(U(0, spec.addressWidth bits)) { case (r, acc) => Mux(r.port.valid, r.port.address, acc) }
      bus.data    := sorted.foldRight(B(0, spec.dataWidth bits))    { case (r, acc) => Mux(r.port.valid, r.port.data, acc) }
      bus
    }

    val ram = Mem(Bits(spec.dataWidth bits), spec.depth)

    // init-zero boot sweep: write 0 to every address through physical write 0
    // before normal operation (no fetch happens until the first redirect).
    // Counter counts 0..depth (inclusive); init writes addresses 0..depth-1
    // only (gated by initDone) so no out-of-range write occurs.
    val initCounter = Reg(UInt(log2Up(spec.depth + 1) bits)) init 0
    val initDone    = initCounter === U(spec.depth)
    when(!initDone) { initCounter := initCounter + 1 }

    for ((w, i) <- phys.zipWithIndex) {
      if (i == 0) {
        ram.write(
          address = Mux(initDone, w.address, initCounter.resized),
          data    = Mux(initDone, w.data, B(0, spec.dataWidth bits)),
          enable  = !initDone || w.valid)
      } else {
        ram.write(w.address, w.data, enable = w.valid && initDone)
      }
    }

    for ((r, noByp) <- reads) {
      val rfData = ram.readAsync(r.addr)
      if (noByp || bypasses.isEmpty) {
        r.data := rfData
      } else {
        // a bypass hit on the read address overrides RF data
        r.data := bypasses.foldLeft(rfData) { case (acc, b) =>
          Mux(b.valid && b.address === r.addr, b.data, acc)
        }
      }
    }
  }
}

class RegFilePluginInt  extends RegFilePlugin(RegfileSpec.Int)  with IntRegFileService
class RegFilePluginNzvc extends RegFilePlugin(RegfileSpec.Nzvc) with NzvcRegFileService
class RegFilePluginX    extends RegFilePlugin(RegfileSpec.X)    with XRegFileService
