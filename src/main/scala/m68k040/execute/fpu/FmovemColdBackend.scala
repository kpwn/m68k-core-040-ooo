package m68k040.execute.fpu

import m68k040.cache.{DTranslationCmd, DTranslationRsp}
import spinal.core._
import spinal.lib._

/** Commit-owner-facing engine. Captures instruction context once, translates
  * every byte, and exposes loaded values for architectural staging by the owner.
  * Admission, squash/drain, register lifetime, and exception entry belong to the
  * serialized CPU owner, not this component.
  */
class FmovemColdBackend extends Component {
  val io = new Bundle {
    val request = slave(Stream(FmovemColdRequest()))
    val supervisor = in Bool()
    val pageSize8K = in Bool()
    val cacheEnabled = in Bool()
    val fpReadReg = out UInt(3 bits)
    val fpReadValue = in Bits(80 bits)
    val loaded = master(Stream(FmovemColdLoadedRegister()))
    val translation = master(Stream(DTranslationCmd()))
    val translated = slave(Flow(DTranslationRsp()))
    val memory = master(Stream(FmovemColdPhysicalByte()))
    val memoryResponse = slave(Flow(FmovemColdByteResponse()))
    val result = master(Stream(FmovemColdResult()))
    val byteFaultAddress = out UInt(32 bits)
    val translationFault = out Bool()
  }
  val engine = new FmovemColdEngine
  val memory = new FmovemColdMemory
  val supervisor = Reg(Bool())
  val page8K = Reg(Bool())
  val cacheEnabled = Reg(Bool())
  val translationFault = RegInit(False)
  when(io.request.fire) {
    supervisor := io.supervisor
    page8K := io.pageSize8K
    cacheEnabled := io.cacheEnabled
    translationFault := False
  }
  engine.io.request << io.request
  io.fpReadReg := engine.io.fpReadReg
  engine.io.fpReadValue := io.fpReadValue
  io.loaded << engine.io.loaded
  io.result << engine.io.result
  io.byteFaultAddress := engine.io.byteFaultAddress
  io.translationFault := translationFault
  memory.io.command << engine.io.command
  engine.io.response << memory.io.response
  memory.io.supervisor := supervisor
  memory.io.pageSize8K := page8K
  memory.io.cacheEnabled := cacheEnabled
  io.translation << memory.io.translation
  memory.io.translated << io.translated
  io.memory << memory.io.memory
  memory.io.memoryResponse << io.memoryResponse
  when(memory.io.response.valid && memory.io.response.payload.fault) {
    translationFault := memory.io.translationFault
  }
}
