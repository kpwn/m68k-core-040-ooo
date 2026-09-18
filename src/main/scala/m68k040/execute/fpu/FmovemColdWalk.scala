package m68k040.execute.fpu

import m68k040.decode.RegListWalk
import spinal.core._
import spinal.lib._

case class FmovemColdRequest() extends Bundle {
  val mask = Bits(32 bits) // Dn value; only low eight bits participate
  val address = UInt(32 bits)
  val store = Bool()
  val predecrement = Bool()
  val postincrement = Bool()
}

case class FmovemColdElement() extends Bundle {
  val fpReg = UInt(3 bits)
  val address = UInt(32 bits)
  val store = Bool()
}

case class FmovemColdResult() extends Bundle {
  val fault = Bool()
  val illegal = Bool()
  val completed = UInt(4 bits)
  val nextAddress = UInt(32 bits)
  // Start of the failed register image, NOT the failing byte's virtual address.
  // The memory adapter must retain the latter separately for the access frame.
  val faultAddress = UInt(32 bits)
}

/** Backend-only register walk. The caller owns the precise memory transaction,
  * FP data, architectural updates and access-error frame. One element means a
  * complete 12-byte extended value, not an individual bus beat. A successful
  * response consumes one mask bit; a fault stops without advancing that element.
  * No decode feedback or PRF access is involved. Not yet wired into the core.
  *
  * M68000PRM5-85..5-88: control/postincrement walk FP0->FP7, increasing
  * addresses; predecrement stores walk FP7->FP0, decreasing addresses.
  */
class FmovemColdWalk extends Component {
  val io = new Bundle {
    val request = slave(Stream(FmovemColdRequest()))
    val element = master(Stream(FmovemColdElement()))
    val completion = slave(Flow(Bool())) // true = this element faulted
    val result = master(Stream(FmovemColdResult()))
  }
  object State extends SpinalEnum { val IDLE, ISSUE, WAIT, DONE = newElement() }
  val state = RegInit(State.IDLE)
  val mask = Reg(Bits(8 bits)) init 0
  val cursor = Reg(UInt(32 bits)) init 0
  val pre = RegInit(False)
  val store = RegInit(False)
  val count = Reg(UInt(4 bits)) init 0
  val fault = RegInit(False)
  val illegal = RegInit(False)
  val faultAddress = Reg(UInt(32 bits)) init 0
  val (bit, remaining) = RegListWalk.extractHighest1(mask)
  val elementAddress = Mux(pre, (cursor - U(12,32 bits)).resize(32), cursor)

  io.request.ready := state === State.IDLE
  io.element.valid := state === State.ISSUE
  io.element.fpReg := Mux(pre, bit.resize(3), U(7,3 bits) - bit.resize(3))
  io.element.address := elementAddress
  io.element.store := store
  io.result.valid := state === State.DONE
  io.result.fault := fault
  io.result.illegal := illegal
  io.result.completed := count
  io.result.nextAddress := cursor
  io.result.faultAddress := faultAddress

  when(io.request.fire) {
    mask := io.request.mask(7 downto 0)
    cursor := io.request.address
    pre := io.request.predecrement
    store := io.request.store
    count := 0
    fault := False
    faultAddress := 0
    val badMode = (io.request.predecrement && !io.request.store) ||
      (io.request.postincrement && io.request.store) ||
      (io.request.predecrement && io.request.postincrement)
    illegal := badMode
    state := Mux(badMode || io.request.mask(7 downto 0) === 0, State.DONE, State.ISSUE)
  }
  when(io.element.fire) { state := State.WAIT }
  // A response is required after acceptance, not in the acceptance cycle. This
  // explicit contract matches a registered cold-memory adapter and avoids a
  // response/ready combinational path. The element remains stable while waiting.
  when(io.completion.valid) {
    assert(state === State.WAIT, "FMOVEM response without an outstanding element")
    when(state === State.WAIT) {
      when(io.completion.payload) {
        fault := True
        faultAddress := elementAddress
        state := State.DONE
      } otherwise {
        count := count + 1
        mask := remaining
        cursor := Mux(pre, elementAddress, (cursor + U(12,32 bits)).resize(32))
        state := Mux(remaining === 0, State.DONE, State.ISSUE)
      }
    }
  }
  when(io.result.fire) { state := State.IDLE }
}
