package m68k040.execute.iq

import spinal.core._
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin

/** 2-wide, 16-slot compacting issue queue (Task 1: every pushed uop is
  * immediately ready; triggers field is kept but always 0).
  *
  * Modeled on NaxRiscv's IssueQueue: slot 0 = oldest. On push.fire the table
  * compacts toward index 0 (line i <- line i+1) and the new uops are inserted
  * at the LAST line. With push.ready gated on count <= slotCount-2 the last
  * line is always free at the moment of insertion, so no occupied slot is lost.
  *
  * Select uses two age-ordered ports: port0 takes the lowest-index ready slot,
  * port1 the next lowest. A slot freed by a port this cycle is observed as
  * empty (selComb) by a simultaneous compaction shift, exactly as NaxRiscv does.
  */
class IssueQueuePlugin extends FiberPlugin with IssueQueueService {
  val slotCount = 16
  val wayCount  = 2
  val lineCount = 8 // slotCount / wayCount

  var pushPort      : Stream[Vec[IqContext]] = null
  var pushSlot1Port : Bool                   = null
  var issuePorts    : Vec[Stream[IqContext]] = null
  var flushSignal   : Bool                   = null

  override def push: Stream[Vec[IqContext]]  = pushPort
  override def pushSlot1Valid: Bool          = pushSlot1Port
  override def issue: Vec[Stream[IqContext]] = issuePorts
  override def flushPort: Bool               = flushSignal

  during setup {
    pushPort      = Stream(Vec(IqContext(), wayCount))
    pushSlot1Port = Bool()
    issuePorts    = Vec.fill(wayCount)(Stream(IqContext()))
    flushSignal   = Bool()
  }

  val logic = during build new Area {
    // ---- Slot array (priority = line*wayCount + way; 0 = oldest) ----
    val lines = for (line <- 0 until lineCount) yield new Area {
      val ways = for (way <- 0 until wayCount) yield new Area {
        val priority = line * wayCount + way
        val fire     = Bool()                         // this slot is being issued this cycle
        val sel      = Reg(Bool()) init False          // occupied
        val selComb  = CombInit(sel)                   // sel after issue this cycle
        val triggers = Reg(Bits((priority + 1) bits)) init 0
        val context  = Reg(IqContext())
        // Task 1: triggers always 0 -> ready == occupied.
        val ready    = sel && (if (priority == 0) True else triggers(priority - 1 downto 0) === 0)

        when(fire) { selComb := False }
        sel := selComb
      }
    }
    val slots = lines.flatMap(_.ways) // index == priority

    // ---- Occupancy / back-pressure ----
    val count = Reg(UInt(log2Up(slotCount + 1) bits)) init 0
    pushPort.ready := count <= (slotCount - wayCount)

    val pushed = UInt(log2Up(wayCount + 1) bits)
    pushed := 0
    when(pushPort.fire) { pushed := Mux(pushSlot1Port, U(2), U(1)) }
    val issued = CountOne(issuePorts.map(_.fire))

    // ---- Select: two age-ordered ports (lowest-index-first one-hot) ----
    val validReady = B(slots.map(_.ready))            // bit i == slot i ready
    val contexts   = Vec(slots.map(_.context))

    val oh0 = OHMasking.first(validReady)
    val oh1 = OHMasking.first(validReady & ~oh0)

    issuePorts(0).valid   := oh0.orR
    issuePorts(0).payload := MuxOH(oh0, contexts)
    issuePorts(1).valid   := oh1.orR
    issuePorts(1).payload := MuxOH(oh1, contexts)

    // Free chosen slots when their issue port fires.
    for ((slot, i) <- slots.zipWithIndex) {
      slot.fire := (issuePorts(0).fire && oh0(i)) || (issuePorts(1).fire && oh1(i))
    }

    // ---- Compaction on push.fire (shift toward index 0, insert at last line) ----
    when(pushPort.fire) {
      for (lineId <- 0 to lineCount - 2) {
        for (way <- 0 until wayCount) {
          val wSrc = lines(lineId + 1).ways(way)
          val wDst = lines(lineId).ways(way)
          wDst.context  := wSrc.context
          wDst.triggers := (wSrc.triggers >> wayCount).resized
          wDst.sel      := wSrc.selComb
        }
      }
      // New uops into the last line.
      val wSrc0 = pushPort.payload(0)
      val wSrc1 = pushPort.payload(1)
      val wDst0 = lines.last.ways(0)
      val wDst1 = lines.last.ways(1)
      wDst0.context  := wSrc0
      wDst0.triggers := 0
      wDst0.sel      := True
      wDst1.context  := wSrc1
      wDst1.triggers := 0
      wDst1.sel      := pushSlot1Port
    }

    count := count + pushed - issued

    // ---- Flush ----
    when(flushSignal) {
      lines.foreach(_.ways.foreach { w =>
        w.sel      := False
        w.triggers := 0
      })
      count := 0
    }
  }
}
