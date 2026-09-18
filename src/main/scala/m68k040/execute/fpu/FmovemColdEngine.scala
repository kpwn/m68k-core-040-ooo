package m68k040.execute.fpu

import spinal.core._
import spinal.lib._

case class FmovemColdLoadedRegister() extends Bundle {
  val fpReg = UInt(3 bits)
  val value = Bits(80 bits)
}

/** Backend transfer engine, without architectural-state ownership.
  * The commit-serialized caller provides a stable committed FP read view,
  * translates/executes precise byte transactions, and consumes loaded values.
  * `loaded` is a staging handshake, NOT a command to commit a PRF write. The
  * caller must implement the architectural fault/restart policy separately.
  * All prior byte transactions are acknowledged before a result is delivered.
  */
class FmovemColdEngine extends Component {
  val io = new Bundle {
    val request = slave(Stream(FmovemColdRequest()))
    val fpReadReg = out UInt(3 bits)
    val fpReadValue = in Bits(80 bits)
    val loaded = master(Stream(FmovemColdLoadedRegister()))
    val command = master(Stream(FmovemColdByteCommand()))
    val response = slave(Flow(FmovemColdByteResponse()))
    val result = master(Stream(FmovemColdResult()))
    // Unlike result.faultAddress (element base), this is the exact failing byte.
    val byteFaultAddress = out UInt(32 bits)
  }
  val walk = new FmovemColdWalk
  val transfer = new FmovemColdTransfer
  walk.io.request << io.request
  io.result << walk.io.result
  io.command << transfer.io.command
  transfer.io.response << io.response

  io.fpReadReg := walk.io.element.payload.fpReg
  transfer.io.request.valid := walk.io.element.valid
  walk.io.element.ready := transfer.io.request.ready
  transfer.io.request.payload.address := walk.io.element.payload.address
  transfer.io.request.payload.store := walk.io.element.payload.store
  transfer.io.request.payload.value := io.fpReadValue
  val activeReg = Reg(UInt(3 bits)) init 0
  val activeStore = RegInit(False)
  val failedByte = Reg(UInt(32 bits)) init 0
  io.byteFaultAddress := failedByte
  when(io.request.fire) { failedByte := 0 }
  when(transfer.io.request.fire) {
    activeReg := walk.io.element.payload.fpReg
    activeStore := walk.io.element.payload.store
  }
  io.loaded.valid := transfer.io.result.valid && !transfer.io.result.payload.fault && !activeStore
  io.loaded.payload.fpReg := activeReg
  io.loaded.payload.value := transfer.io.result.payload.value
  transfer.io.result.ready := transfer.io.result.payload.fault || activeStore || io.loaded.ready
  walk.io.completion.valid := transfer.io.result.fire
  walk.io.completion.payload := transfer.io.result.payload.fault
  when(transfer.io.result.fire && transfer.io.result.payload.fault) {
    failedByte := transfer.io.result.payload.faultAddress
  }
}
