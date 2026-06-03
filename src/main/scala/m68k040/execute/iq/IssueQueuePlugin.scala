package m68k040.execute.iq

import m68k040.rename.RenamedUop
import spinal.core._
import spinal.core.sim._
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
  var lsWakeupPort  : Flow[UInt]             = null

  override def push: Stream[Vec[IqContext]]  = pushPort
  override def pushSlot1Valid: Bool          = pushSlot1Port
  override def issue: Vec[Stream[IqContext]] = issuePorts
  override def flushPort: Bool               = flushSignal
  override def lsWakeup: Flow[UInt]          = lsWakeupPort

  during setup {
    pushPort      = Stream(Vec(IqContext(), wayCount))
    pushSlot1Port = Bool()
    // 4 issue ports: 0,1 = ALU (non-branch, non-LS), 2 = branch, 3 = LS.
    issuePorts    = Vec.fill(4)(Stream(IqContext()))
    flushSignal   = Bool()
    lsWakeupPort  = Flow(UInt(6 bits))   // carries a completed-load pdst
  }

  val logic = during build new Area {
    // Dynamic-wakeup input: default-driven idle (allowOverride) so the IQ
    // elaborates standalone; the LS-EU wiring OVERRIDES it. Concrete zero payload
    // (not assignDontCare) so a sim poke reaches the consumer.
    lsWakeupPort.valid.allowOverride;   lsWakeupPort.valid   := False
    lsWakeupPort.payload.allowOverride; lsWakeupPort.payload := U(0, 6 bits)
    lsWakeupPort.simPublic()

    // ---- Slot array (priority = line*wayCount + way; 0 = oldest) ----
    val lines = for (line <- 0 until lineCount) yield new Area {
      val ways = for (way <- 0 until wayCount) yield new Area {
        val priority = line * wayCount + way
        val fire     = Bool()                         // this slot is being issued this cycle
        val sel      = Reg(Bool()) init False          // occupied
        val selComb  = CombInit(sel)                   // sel after issue this cycle
        val triggers = Reg(Bits((priority + 1) bits)) init 0
        val context  = Reg(IqContext())
        // Dynamic LS dependency: a REGISTERED per-slot bit (like `triggers`), set at
        // push if a source physreg is produced by an in-flight LS load, cleared on
        // the matching lsWakeup. Keeping it a single registered bit (vs reading the
        // 48-wide lsBusy in the ready cone) keeps the lsBusy->ready->select->PRF-read
        // path off the FMax-critical S0 arc (variant-A FMax fix). `lsDepNext` (a wire)
        // holds the combinational next value; the compaction/maintenance below writes
        // the reg from it (default keep).
        val lsWait     = Reg(Bool()) init False
        // default: hold (overridden by compaction-shift and lsWakeup-clear below).
        // triggers==0 (static latency-1) AND no pending LS source -> ready.
        val ready    = sel && (if (priority == 0) True else triggers(priority - 1 downto 0) === 0) && !lsWait

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

    // ---- Dynamic-completion (variant A) LS scoreboard ----
    // lsBusy[p] => int physreg p is produced by an in-flight LS LOAD that has NOT
    // yet completed. A consumer reading such a physreg is held NOT-ready until the
    // LS EU broadcasts lsWakeup(p). This is SEPARATE from the static slot-trigger
    // mechanism (which assumes latency-1): LS producers are tracked ONLY here, not
    // in sbInt, so trigInit never sets a static (auto-clearing) trigger for them.
    val lsBusy = Reg(Bits(48 bits)) init 0

    // LS class predicate: cluster == LS and a real memory op.
    def isLs(u: RenamedUop): Bool = (u.cluster === m68k040.isa.Cluster.LS) && (u.memOp =/= m68k040.isa.MemOp.NONE)

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

    // ---- Select: age-ordered, CLASS-filtered (lowest-index-first one-hot) ----
    // ALU ports (0,1) select non-branch ready slots; branch port (2) selects
    // branch-class ready slots (class = context.uop.isBranch). The classes are
    // disjoint, so a slot is selected by at most one port.
    // Classes are disjoint: ALU = non-branch & non-LS; branch = isBranch; LS = isLs.
    val aluReady = B(slots.map(s => s.ready && !s.context.uop.isBranch && !isLs(s.context.uop)))
    val brReady  = B(slots.map(s => s.ready &&  s.context.uop.isBranch))
    val lsReady  = B(slots.map(s => s.ready &&  isLs(s.context.uop)))
    val contexts = Vec(slots.map(_.context))

    val oh0 = OHMasking.first(aluReady)
    val oh1 = OHMasking.first(aluReady & ~oh0)
    val ohB = OHMasking.first(brReady)
    val ohL = OHMasking.first(lsReady)

    // Suppress ALL issue on a flush cycle. flushSignal (= the ROB's registered
    // doFlush pulse, or a test flush) clears every slot's `sel` for NEXT cycle, but
    // the select above reads the CURRENT (combinational) sel — so without this gate
    // a wrong-path slot still issues on the flush cycle, its completion/writeback
    // arrives 1-2 cycles later carrying a now-reused robId, and corrupts the
    // commit/whitebox join. Gating issue on !flushSignal is the correct squash
    // behavior (a single AND on the registered pulse, not a broadcast).
    issuePorts(0).valid   := oh0.orR && !flushSignal
    issuePorts(0).payload := MuxOH(oh0, contexts)
    issuePorts(1).valid   := oh1.orR && !flushSignal
    issuePorts(1).payload := MuxOH(oh1, contexts)
    issuePorts(2).valid   := ohB.orR && !flushSignal
    issuePorts(2).payload := MuxOH(ohB, contexts)
    issuePorts(3).valid   := ohL.orR && !flushSignal
    issuePorts(3).payload := MuxOH(ohL, contexts)

    // Free chosen slots when their issue port fires.
    for ((slot, i) <- slots.zipWithIndex) {
      slot.fire := (issuePorts(0).fire && oh0(i)) ||
                   (issuePorts(1).fire && oh1(i)) ||
                   (issuePorts(2).fire && ohB(i)) ||
                   (issuePorts(3).fire && ohL(i))
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

    // Push-time LS dependency: does this uop read a physreg produced by an
    // in-flight (not-yet-completed) LS load? (Intra-push slot1<-slot0 LS handled
    // below.) Reads lsBusy at push (off the issue/PRF-read critical path).
    def lsDepInit(uop: RenamedUop): Bool =
      (uop.psrcAValid && lsBusy(uop.psrcA)) || (uop.psrcBValid && !uop.useImm && lsBusy(uop.psrcB))
    val lsDep0 = lsDepInit(pushUop0)
    val lsDep1Base = lsDepInit(pushUop1)
    // Intra-push: slot1 reads slot0's dst and slot0 is an LS load -> slot1 waits.
    val s0IsLsLoad = isLs(pushUop0) && (pushUop0.memOp === m68k040.isa.MemOp.LOAD) && pushUop0.pdstValid
    val lsDep1 = lsDep1Base ||
      (s0IsLsLoad && pushUop1.psrcAValid && (pushUop1.psrcA === pushUop0.pdst)) ||
      (s0IsLsLoad && pushUop1.psrcBValid && !pushUop1.useImm && (pushUop1.psrcB === pushUop0.pdst))
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
          wDst.lsWait   := wSrc.lsWait     // LS dependency shifts with the slot
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
      wDst0.lsWait   := lsDep0
      wDst1.context  := wSrc1
      wDst1.triggers := trig1
      wDst1.sel      := pushSlot1Port
      wDst1.lsWait   := lsDep1
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

    // ---- LS dynamic wakeup: clear lsWait for slots reading the woken pdst. ----
    // Mirrors the trigger/eventsMoved shift discipline: on a compaction cycle the
    // matched slot moves down by wayCount, so the clear is applied to slot i-wayCount.
    // Placed AFTER the compaction block so it overrides the shifted lsWait value.
    val lsWakeMatch = Vec(slots.map { s =>
      val u = s.context.uop
      lsWakeupPort.valid && s.sel &&
        ((u.psrcAValid && (u.psrcA === lsWakeupPort.payload)) ||
         (u.psrcBValid && !u.useImm && (u.psrcB === lsWakeupPort.payload)))
    })
    for (i <- 0 until slotCount) {
      when(lsWakeMatch(i)) {
        // on a non-compaction cycle, clear slot i; on compaction, clear slot i-wayCount.
        when(pushPort.fire) { if (i >= wayCount) slots(i - wayCount).lsWait := False }
          .otherwise        { slots(i).lsWait := False }
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
    // An LS LOAD producer is tracked in lsBusy (dynamic wakeup), NOT sbInt (static
    // latency-1). All other int producers go in sbInt as before.
    val push0IsLs = isLs(pushUop0)
    val push1IsLs = isLs(pushUop1)
    when(pushPort.fire) {
      when(pushUop0.pdstValid) {
        when(push0IsLs) { lsBusy(pushUop0.pdst) := True }
          .otherwise    { sbInt.busy(pushUop0.pdst) := True; sbInt.physToSlot(pushUop0.pdst) := slot0Prio }
      }
      when(pushUop0.writesNzvc) { sbNzvc.busy(pushUop0.pNzvcDst) := True; sbNzvc.physToSlot(pushUop0.pNzvcDst) := slot0Prio }
      when(pushUop0.writesX)    { sbX.busy(pushUop0.pXDst)     := True; sbX.physToSlot(pushUop0.pXDst)     := slot0Prio }
      when(pushSlot1Port) {
        when(pushUop1.pdstValid) {
          when(push1IsLs) { lsBusy(pushUop1.pdst) := True }
            .otherwise    { sbInt.busy(pushUop1.pdst) := True; sbInt.physToSlot(pushUop1.pdst) := slot1Prio }
        }
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

    // ---- Dynamic LS wakeup: clear lsBusy for the completed load's pdst ----
    // Placed AFTER the push-recording so a same-cycle re-allocation of that physreg
    // (a new LS load pushed onto the just-freed pdst) wins (stays busy).
    when(lsWakeupPort.valid) { lsBusy(lsWakeupPort.payload) := False }
    when(pushPort.fire) {
      when(pushUop0.pdstValid && push0IsLs) { lsBusy(pushUop0.pdst) := True }
      when(pushSlot1Port && pushUop1.pdstValid && push1IsLs) { lsBusy(pushUop1.pdst) := True }
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
      lsBusy      := 0
      // After flush line 0 is empty next cycle, so push.ready may re-assert.
      readyReg := True
    }
  }
}
