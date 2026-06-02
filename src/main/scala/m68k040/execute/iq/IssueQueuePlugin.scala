package m68k040.execute.iq

import m68k040.rename.RenamedUop
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

    val slotIdxW = log2Up(slotCount) // 4 bits

    // ---- Scoreboards (one per reg class).
    // RELIED-UPON INVARIANT: at most ONE in-flight producer per physical register
    // pre-commit. Rename allocates a unique pdst for every writer and does not
    // reuse a physreg until the prior mapping commits, so a physreg has at most
    // one un-issued producer in the queue at a time. Both `physToSlot` (a single
    // producer slot per physreg) and the push-before-issue-clear ordering within
    // a cycle (push sets busy[p] then issue may clear busy of an OLDER mapping)
    // are correct ONLY under this invariant. If a physreg could have two in-flight
    // producers, physToSlot would alias and a dependent could track the wrong one.
    // Scheme (b): store the producer's
    // CURRENT slot index per physreg; shift stored indices by wayCount on every
    // compaction (slots march toward 0). busy[p] => physreg p has an in-flight
    // producer occupying slot physToSlot[p]. ----
    class Scoreboard(depth: Int) extends Area {
      val busy       = Reg(Bits(depth bits)) init 0
      val physToSlot = Reg(Vec(UInt(slotIdxW bits), depth))
    }
    val sbInt  = new Scoreboard(48) // int physregs (width 6)
    val sbNzvc = new Scoreboard(16) // NZVC flag physregs (width 4)
    val sbX    = new Scoreboard(16) // X flag physregs (width 4)

    // ---- Occupancy / back-pressure ----
    // Back-pressure is gated on LINE 0 BEING EMPTY, not on a count proxy.
    //
    // Compaction (the `when(push.fire)` block below) is an UNCONDITIONAL uniform
    // shift: every line copies from the line above and line 0 (slots 0,1) is
    // DISCARDED. That is only safe if line 0 is empty when a push fires. A count
    // proxy (count <= slotCount-wayCount) is NOT sufficient: in OoO operation an
    // older slot can be STALLED (waiting on a trigger) in line 0 while younger
    // ready slots issue from higher lines, leaving a hole. count could then drain
    // below the threshold with line 0 still occupied by the oldest uop, and the
    // next push would silently discard it (ROB desync). We therefore gate on the
    // actual emptiness of line 0.
    //
    // Following NaxRiscv IssueQueue (frontend/IssueQueue.scala:133-138), push.ready
    // is a REGISTERED next-cycle predicate: if we compact this cycle (push.fire),
    // then next cycle line 1 becomes line 0, so use line1Ready; otherwise line0Ready.
    // This design does NOT use a trigger keepalive bit (a stalled slot still has
    // sel===True, only ready===False), so "empty" is simply !sel.
    //
    // No combinational loop: push.fire = push.valid && push.ready, and push.ready
    // is driven purely by the registered readyReg, so push.ready does not depend
    // combinationally on push.fire.
    val count = Reg(UInt(log2Up(slotCount + 1) bits)) init 0 // instrumentation only
    // selComb = sel after this cycle's issue, i.e. the slot's NEXT-cycle occupancy
    // (absent compaction). Sampling selComb (not sel) lets a line that empties via
    // issue THIS cycle re-open push.ready next cycle.
    val line0Ready = lines(0).ways.map(w => !w.selComb).reduce(_ && _)
    val line1Ready = lines(1).ways.map(w => !w.selComb).reduce(_ && _)
    val readyReg   = RegInit(False)
    pushPort.ready := readyReg

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

    // ---- Static-latency-1 wakeup events ----
    // events(j) == slot j issued (fired) this cycle. A slot j that fires is a
    // producer whose result becomes available next cycle; dependents carry a
    // trigger bit at index j which we clear (combinationally into the trigger
    // reg's next value) so they become ready next cycle (back-to-back, lat 1).
    val events = oh0.andMask(issuePorts(0).fire) | oh1.andMask(issuePorts(1).fire)

    // ---- Depend-on-READ trigger init for the two newly-pushed slots ----
    // Slot0 lands at priority `slot0Prio` (lines.last.ways(0)), slot1 at
    // `slot1Prio` (== slot0Prio+1). A producer dependency is ALWAYS on an older
    // (lower-index) slot, so it fits in the dependent's trigger width.
    //
    // A push always coincides with a compaction shift (slots march down by
    // wayCount). The scoreboard holds the producer's CURRENT (pre-shift) slot;
    // next cycle (when our freshly-written triggers take effect) the producer
    // sits at slot-wayCount, so a dependency on an existing producer references
    // bit (producerSlot - wayCount). Intra-push (slot1 reads slot0's dst)
    // references slot0's final position (slot0Prio) directly, no shift offset.
    val slot0Prio = (lineCount - 1) * wayCount     // 14
    val slot1Prio = slot0Prio + 1                  // 15

    // Build the trigger Bits for a pushed slot of the given priority width.
    def trigInit(uop: RenamedUop, width: Int): Bits = {
      val t = B(0, width bits)
      def dep(busy: Bits, physToSlot: Vec[UInt], physreg: UInt, reads: Bool): Unit = {
        val producerSlot = (physToSlot(physreg) - wayCount).resize(slotIdxW)
        when(reads && busy(physreg)) {
          // bit index < this slot's priority (older), so in range.
          t(producerSlot) := True
        }
      }
      dep(sbInt.busy,  sbInt.physToSlot,  uop.psrcA, uop.psrcAValid)
      dep(sbInt.busy,  sbInt.physToSlot,  uop.psrcB, uop.psrcBValid && !uop.useImm)
      dep(sbNzvc.busy, sbNzvc.physToSlot, uop.pNzvcSrc, uop.readsNzvc)
      dep(sbX.busy,    sbX.physToSlot,    uop.pXSrc, uop.readsX)
      t
    }

    val pushUop0 = pushPort.payload(0).uop
    val pushUop1 = pushPort.payload(1).uop
    val trig0 = trigInit(pushUop0, slot0Prio + 1)
    val trig1 = trigInit(pushUop1, slot1Prio + 1)
    // Intra-push: slot1 reads a physreg that slot0 (pushed same cycle) writes.
    // slot0 ends at slot0Prio; set slot1's trigger bit there.
    val s0WritesInt  = pushUop0.pdstValid
    val s0WritesNzvc = pushUop0.writesNzvc
    val s0WritesX    = pushUop0.writesX
    when(s0WritesInt  && pushUop1.psrcAValid && pushUop1.psrcA === pushUop0.pdst)              { trig1(slot0Prio) := True }
    when(s0WritesInt  && pushUop1.psrcBValid && !pushUop1.useImm && pushUop1.psrcB === pushUop0.pdst) { trig1(slot0Prio) := True }
    when(s0WritesNzvc && pushUop1.readsNzvc && pushUop1.pNzvcSrc === pushUop0.pNzvcDst)        { trig1(slot0Prio) := True }
    when(s0WritesX    && pushUop1.readsX    && pushUop1.pXSrc === pushUop0.pXDst)              { trig1(slot0Prio) := True }

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
      wDst0.triggers := trig0
      wDst0.sel      := True
      wDst1.context  := wSrc1
      wDst1.triggers := trig1
      wDst1.sel      := pushSlot1Port
    }

    // ---- Apply wakeup events (clears trigger bits). MUST come after the
    // compaction block so it overrides the shifted trigger value. On a
    // compaction cycle every slot (and its triggers) shifts down by wayCount,
    // so an event at producer-slot j must be applied at j-wayCount: NaxRiscv's
    // moved = !moveIt ? events | (events >> wayCount). ----
    val eventsMoved = Mux(pushPort.fire, events |>> wayCount, events)
    for (j <- 0 until slotCount) {
      when(eventsMoved(j)) {
        slots.filter(_.priority >= j).foreach(s => s.triggers(j) := False)
      }
    }

    // ---- Scoreboard maintenance ----
    // Compaction shifts every still-busy producer's stored slot down by wayCount.
    when(pushPort.fire) {
      def shift(sb: Scoreboard): Unit = {
        for (p <- 0 until sb.physToSlot.length) {
          sb.physToSlot(p) := (sb.physToSlot(p) - wayCount).resize(slotIdxW)
        }
      }
      shift(sbInt); shift(sbNzvc); shift(sbX)
    }
    // On push, record each newly-pushed producer's dst -> its landing slot + busy.
    // (Written after the shift above so the fresh slot wins for that physreg.)
    when(pushPort.fire) {
      when(pushUop0.pdstValid)  { sbInt.busy(pushUop0.pdst)   := True; sbInt.physToSlot(pushUop0.pdst)   := slot0Prio }
      when(pushUop0.writesNzvc) { sbNzvc.busy(pushUop0.pNzvcDst) := True; sbNzvc.physToSlot(pushUop0.pNzvcDst) := slot0Prio }
      when(pushUop0.writesX)    { sbX.busy(pushUop0.pXDst)     := True; sbX.physToSlot(pushUop0.pXDst)     := slot0Prio }
      when(pushSlot1Port) {
        when(pushUop1.pdstValid)  { sbInt.busy(pushUop1.pdst)   := True; sbInt.physToSlot(pushUop1.pdst)   := slot1Prio }
        when(pushUop1.writesNzvc) { sbNzvc.busy(pushUop1.pNzvcDst) := True; sbNzvc.physToSlot(pushUop1.pNzvcDst) := slot1Prio }
        when(pushUop1.writesX)    { sbX.busy(pushUop1.pXDst)     := True; sbX.physToSlot(pushUop1.pXDst)     := slot1Prio }
      }
    }
    // On issue, clear busy for the issued producer's dst(s) so later pushes do
    // not depend on an already-issued (latency-1, result-available) producer.
    for (k <- 0 until wayCount) {
      val ctx = issuePorts(k).payload
      when(issuePorts(k).fire) {
        when(ctx.uop.pdstValid)  { sbInt.busy(ctx.uop.pdst)     := False }
        when(ctx.uop.writesNzvc) { sbNzvc.busy(ctx.uop.pNzvcDst) := False }
        when(ctx.uop.writesX)    { sbX.busy(ctx.uop.pXDst)       := False }
      }
    }

    count := count + pushed - issued

    // Next-cycle push.ready: if compacting this cycle, line 1 becomes line 0 next
    // cycle (use line1Ready); else line0Ready. line0/line1 emptiness is sampled
    // combinationally from sel BEFORE this cycle's compaction writes take effect.
    readyReg := Mux(pushPort.fire, line1Ready, line0Ready)

    // ---- Flush ----
    when(flushSignal) {
      lines.foreach(_.ways.foreach { w =>
        w.sel      := False
        w.triggers := 0
      })
      count := 0
      sbInt.busy  := 0
      sbNzvc.busy := 0
      sbX.busy    := 0
      // After flush line 0 is empty next cycle, so push.ready may re-assert.
      readyReg := True
    }
  }
}
