package m68k040.debug

import m68k040.execute.regfile._
import m68k040.services.CommittedMapService
import spinal.core._
import spinal.lib.misc.plugin.FiberPlugin
import scala.collection.mutable.ArrayBuffer

/** Test-only integer PRF with the same setup-time port-allocation contract as the real
  * RegFilePlugin. Values are pokeable so AXI tests can prove committed-map selection. */
class DebugIntRfStubPlugin extends FiberPlugin with IntRegFileService {
  override val spec: RegfileSpec = RegfileSpec.Int
  private val reads = ArrayBuffer[RegFileReadPort]()
  override def newRead(forceNoBypass: Boolean = false): RegFileReadPort = {
    val p = RegFileReadPort(spec.addressWidth, spec.dataWidth); reads += p; p
  }
  override def newWrite(latency: Int = 1, sharingKey: Any = null, priority: Int = 0) =
    RegFileWritePort(spec.addressWidth, spec.dataWidth)
  override def newBypass() = RegFileBypassPort(spec.addressWidth, spec.dataWidth)

  val logic = during build new Area {
    val values = in(Vec.fill(spec.depth)(Bits(spec.dataWidth bits)))
    reads.foreach(r => r.data := values(r.addr))
  }
}

/** Test-only committed map producer. Non-identity pokes prove that debug reads never
  * assume architectural index equals physical index. */
class DebugCommittedMapStubPlugin extends FiberPlugin with CommittedMapService {
  val logic = during build new Area {
    val intMap = in(Vec.fill(20)(UInt(6 bits)))
    val nzvcMap = in(UInt(4 bits))
    val xMap = in(UInt(4 bits))
  }
  override def intPhys: Vec[UInt] = logic.intMap
  override def nzvcPhys: UInt = logic.nzvcMap
  override def xPhys: UInt = logic.xMap
}
