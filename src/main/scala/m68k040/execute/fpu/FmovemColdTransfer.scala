package m68k040.execute.fpu

import spinal.core._
import spinal.lib._

case class FmovemColdTransferRequest() extends Bundle {
  val address = UInt(32 bits)
  val store = Bool()
  val value = Bits(80 bits)
}

case class FmovemColdByteCommand() extends Bundle {
  val address = UInt(32 bits) // virtual; caller translates every access as needed
  val store = Bool()
  val data = Bits(8 bits)
}

case class FmovemColdByteResponse() extends Bundle {
  val data = Bits(8 bits)
  val fault = Bool()
}

case class FmovemColdTransferResult() extends Bundle {
  val value = Bits(80 bits)
  val fault = Bool()
  val faultAddress = UInt(32 bits) // exact failed byte's virtual address
  val completedBytes = UInt(4 bits)
}

/** Cold backend 96-bit memory image transfer, one acknowledged byte at a time.
  * No translation bypass, FP conversion, PRF write or architectural An update.
  * The serialized owner supplies a translated precise-memory adapter and decides
  * architectural commit/restart semantics. In particular, fault completion does
  * NOT imply that earlier store bytes can be rolled back.
  */
class FmovemColdTransfer extends Component {
  val io = new Bundle {
    val request = slave(Stream(FmovemColdTransferRequest()))
    val command = master(Stream(FmovemColdByteCommand()))
    val response = slave(Flow(FmovemColdByteResponse()))
    val result = master(Stream(FmovemColdTransferResult()))
  }
  object State extends SpinalEnum { val IDLE, ISSUE, WAIT, DONE = newElement() }
  val state = RegInit(State.IDLE)
  val address = Reg(UInt(32 bits)) init 0
  val store = RegInit(False)
  val image = Reg(Bits(96 bits)) init 0
  val byteIndex = Reg(UInt(4 bits)) init 0
  val completed = Reg(UInt(4 bits)) init 0
  val fault = RegInit(False)
  val faultAddress = Reg(UInt(32 bits)) init 0
  val bytes = image.subdivideIn(8 bits)

  io.request.ready := state === State.IDLE
  io.command.valid := state === State.ISSUE
  io.command.payload.address := address
  io.command.payload.store := store
  io.command.payload.data := bytes(U(11,4 bits) - byteIndex)
  io.result.valid := state === State.DONE
  io.result.payload.value := image(95 downto 80) ## image(63 downto 0)
  io.result.payload.fault := fault
  io.result.payload.faultAddress := faultAddress
  io.result.payload.completedBytes := completed

  when(io.request.fire) {
    address := io.request.payload.address
    store := io.request.payload.store
    image := Mux(io.request.payload.store,
      io.request.payload.value(79 downto 64) ## B(0,16 bits) ## io.request.payload.value(63 downto 0),
      B(0,96 bits))
    byteIndex := 0
    completed := 0
    fault := False
    faultAddress := 0
    state := State.ISSUE
  }
  when(io.command.fire) { state := State.WAIT }
  when(io.response.valid) {
    assert(state === State.WAIT, "FMOVEM byte response without an outstanding command")
    when(state === State.WAIT) {
      when(io.response.payload.fault) {
        fault := True
        faultAddress := address
        state := State.DONE
      } otherwise {
        when(!store) {
          for (i <- 0 until 12) {
            when(byteIndex === i) {
              image(95-i*8 downto 88-i*8) := io.response.payload.data
            }
          }
        }
        completed := completed + 1
        when(byteIndex === 11) {
          state := State.DONE
        } otherwise {
          byteIndex := byteIndex + 1
          address := address + 1
          state := State.ISSUE
        }
      }
    }
  }
  when(io.result.fire) { state := State.IDLE }
}
