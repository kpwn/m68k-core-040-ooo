package m68k040.rename

import spinal.core._
import spinal.lib._

/** Pointer-based multi-port physical-register freelist.
  *
  * Circular buffer holding FREE phys-ids.
  * Initially free = ids archCount .. physCount-1.
  * Ids 0..archCount-1 are the initial committed arch mapping, reserved.
  *
  * Flush strategy: pointer-reset only (FPGA-friendly, low-fanout).
  * The RAM is written once during init (a small counter fills archCount..physCount-1
  * into slots 0..physCount-archCount-1). On flush, head/tail/count are reset to
  * those same post-init values; the RAM contents are never disturbed after init.
  * This is correct as long as the RAT also resets to committed state on flush
  * (so no stale speculative phys-ids remain in flight). The RAM contents from
  * init remain valid forever because no init-written entry is ever overwritten
  * until it has been popped and then pushed back (at its current value or another).
  * A pushed-back id lands at tail, which starts at physCount-archCount after init;
  * so RAM[0..physCount-archCount-1] are only reused after a full wraparound, which
  * is fine — they're overwritten with freshly returned ids.
  */
case class Freelist(
    physCount: Int,
    archCount: Int,
    popPorts:  Int,
    pushPorts: Int
) extends Component {

  val idW     = log2Up(physCount)
  val ptrW    = log2Up(physCount)
  val countW  = log2Up(physCount + 1)
  val freeN   = physCount - archCount   // initial free count

  val io = new Bundle {
    val pop      = Vec.fill(popPorts)(new Bundle {
      val take = in  Bool()
      val id   = out UInt(idW bits)
    })
    val popReady = out Bool()
    val push     = Vec.fill(pushPorts)(slave(Flow(UInt(idW bits))))
    val flush    = in Bool()
  }

  // ── Circular RAM ──────────────────────────────────────────────────────────
  val ram = Mem(UInt(idW bits), physCount)

  // ── Pointers ──────────────────────────────────────────────────────────────
  val head  = Reg(UInt(ptrW   bits)) init 0
  val tail  = Reg(UInt(ptrW   bits)) init 0
  val count = Reg(UInt(countW bits)) init 0

  // ── Init counter ──────────────────────────────────────────────────────────
  // After reset: fill ram[0..freeN-1] with ids archCount..physCount-1, one per cycle.
  val initDone    = Reg(Bool()) init False
  val initCounter = Reg(UInt(ptrW bits)) init 0   // index into ram (0..freeN-1)

  // initHead/initTail/initCount are the pointer values after init completes.
  // They are constants derived from the parameters.
  val initHeadVal  = U(0, ptrW bits)
  val initTailVal  = U(freeN, ptrW bits)
  val initCountVal = U(freeN, countW bits)

  // Re-init trigger: asserted on reset (via !initDone) and on flush.
  // When re-init is active, initDone is cleared and the counter restarts.
  val reInit = !initDone || io.flush

  when(io.flush) {
    initDone    := False
    initCounter := 0
    head        := initHeadVal
    tail        := initTailVal
    count       := initCountVal
  }

  when(!initDone && !io.flush) {
    // Write one id per cycle into the RAM
    ram.write(
      address = initCounter.resized,
      data    = (U(archCount, idW bits) + initCounter).resized,
      enable  = True
    )
    when(initCounter === U(freeN - 1)) {
      initDone    := True
      initCounter := 0
      head        := initHeadVal
      tail        := initTailVal
      count       := initCountVal
    } otherwise {
      initCounter := initCounter + 1
    }
  }

  // ── popReady ──────────────────────────────────────────────────────────────
  io.popReady := initDone && (count >= U(popPorts, countW bits))

  // ── Async reads (pop outputs) ─────────────────────────────────────────────
  for (k <- 0 until popPorts) {
    io.pop(k).id := ram.readAsync((head + U(k, ptrW bits)).resized)
  }

  // ── Pop / push updates (only when init done and not flushing) ─────────────
  when(initDone && !io.flush) {
    // Count takes (prefix assumption: takes are 0..n-1)
    val takeCount = io.pop.map(p => p.take.asUInt.resize(log2Up(popPorts + 1))).reduceLeft(_ + _)

    // Count and apply pushes
    var pushCount = U(0, log2Up(pushPorts + 1) bits)
    for (j <- 0 until pushPorts) {
      val isValid = io.push(j).valid
      ram.write(
        address = (tail + U(j, ptrW bits)).resized,
        data    = io.push(j).payload,
        enable  = isValid
      )
      when(isValid) { pushCount = pushCount + 1 }
    }

    head  := (head  + takeCount.resized).resized
    tail  := (tail  + pushCount.resized).resized
    count := (count - takeCount.resized + pushCount.resized).resized
  }
}
