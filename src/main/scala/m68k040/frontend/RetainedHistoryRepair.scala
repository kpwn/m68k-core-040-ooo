package m68k040.frontend

import spinal.core._

/** Bounded suffix of prediction events from the frontend kept across Tier 2.
  * No retirement state or PHT counter is owned here. */
class RetainedHistoryRepair(width: Int) extends Component {
  require(width >= 2)
  val io = new Bundle {
    val start = in Bool()
    val flush = in Bool()
    val keep = in Bool()
    val repair = in Bool()
    val invalidate = in Bool()
    val shift = in Bool()
    val direction = in Bool()
    val arch = in UInt(width bits)
    val value = out UInt(width bits)
    val preserved = out Bool()
  }
  val active = RegInit(False)
  val suffix = RegInit(U(0, width bits))
  val count = RegInit(U(0, log2Up(width + 1) bits))
  val keepPending = RegNext(io.flush && io.keep) init False
  io.preserved := active && keepPending && !io.start && !io.invalidate
  val rebased = ((io.arch |<< count).resize(width) | suffix)
  io.value := io.arch
  when(io.preserved) {
    io.value := rebased
    when(io.shift) { io.value := (rebased(width - 2 downto 0) ## io.direction).asUInt }
  }
  when(active && io.shift) {
    suffix := (suffix(width - 2 downto 0) ## io.direction).asUInt
    when(count < width) { count := count + 1 }
  }
  when(io.repair || (io.flush && !io.keep)) { active := False }
  // A replacement Tier-1 redirect discards the earlier suffix, even if it
  // coincides with a previous repair. No old-path shift belongs to the new one.
  when(io.start) {
    active := True
    suffix := 0
    count := 0
  }
  when(io.invalidate) {
    active := False
    suffix := 0
    count := 0
  }
}
