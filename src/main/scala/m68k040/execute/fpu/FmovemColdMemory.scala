package m68k040.execute.fpu

import m68k040.cache.{CacheMode, DTranslationCmd, DTranslationRsp}
import spinal.core._
import spinal.lib._

case class FmovemColdPhysicalByte() extends Bundle {
  val vaddr = UInt(32 bits)
  val paddr = UInt(32 bits)
  val store = Bool()
  val data = Bits(8 bits)
  val cacheMode = CacheMode()
}

/** One outstanding translated byte transaction. The serialized owner must drain
  * older traffic before use and route precise memory acknowledgements here.
  * No architectural state is changed by this adapter. There is deliberately no
  * cancellation that could discard an already accepted store acknowledgement.
  */
class FmovemColdMemory(token: Int = 0x100) extends Component {
  val io = new Bundle {
    val command = slave(Stream(FmovemColdByteCommand()))
    val supervisor = in Bool()
    val pageSize8K = in Bool()
    val cacheEnabled = in Bool()
    val translation = master(Stream(DTranslationCmd()))
    val translated = slave(Flow(DTranslationRsp()))
    val memory = master(Stream(FmovemColdPhysicalByte()))
    val memoryResponse = slave(Flow(FmovemColdByteResponse()))
    val response = master(Flow(FmovemColdByteResponse()))
    val translationFault = out Bool()
  }
  object State extends SpinalEnum { val IDLE, XLATE, XWAIT, MEMORY, MWAIT, DONE = newElement() }
  val state = RegInit(State.IDLE)
  val command = Reg(FmovemColdByteCommand())
  val supervisor = Reg(Bool())
  val page8K = Reg(Bool())
  val cacheEnabled = Reg(Bool())
  val paddr = Reg(UInt(32 bits))
  val cacheMode = Reg(CacheMode())
  val fault = RegInit(False)
  val atc = RegInit(False)
  val data = Reg(Bits(8 bits)) init 0

  io.command.ready := state === State.IDLE
  io.translation.valid := state === State.XLATE
  io.translation.payload.vpn := command.address(31 downto 12)
  io.translation.payload.supervisor := supervisor
  io.translation.payload.write := command.store
  io.translation.payload.token := token
  io.memory.valid := state === State.MEMORY
  io.memory.payload.vaddr := command.address
  io.memory.payload.paddr := paddr
  io.memory.payload.store := command.store
  io.memory.payload.data := command.data
  io.memory.payload.cacheMode := cacheMode
  io.response.valid := state === State.DONE
  io.response.payload.data := data
  io.response.payload.fault := fault
  io.translationFault := atc

  when(io.command.fire) {
    command := io.command.payload
    supervisor := io.supervisor
    page8K := io.pageSize8K
    cacheEnabled := io.cacheEnabled
    fault := False
    atc := False
    data := 0
    state := State.XLATE
  }
  when(io.translation.fire) { state := State.XWAIT }
  when(io.translated.valid && io.translated.payload.token === token) {
    assert(state === State.XWAIT, "FMOVEM translation response without an outstanding request")
    when(state === State.XWAIT) {
      when(io.translated.payload.fault) {
        fault := True
        atc := True
        state := State.DONE
      } otherwise {
        paddr := Mux(page8K,
          (io.translated.payload.ppn(19 downto 1) ## command.address(12 downto 0)).asUInt,
          (io.translated.payload.ppn ## command.address(11 downto 0)).asUInt)
        cacheMode := Mux(cacheEnabled, io.translated.payload.cacheMode, CacheMode.INHIBITED)
        state := State.MEMORY
      }
    }
  }
  when(io.memory.fire) { state := State.MWAIT }
  when(io.memoryResponse.valid) {
    assert(state === State.MWAIT, "FMOVEM memory response without an outstanding request")
    when(state === State.MWAIT) {
      fault := io.memoryResponse.payload.fault
      data := io.memoryResponse.payload.data
      state := State.DONE
    }
  }
  when(state === State.DONE) { state := State.IDLE }
}
