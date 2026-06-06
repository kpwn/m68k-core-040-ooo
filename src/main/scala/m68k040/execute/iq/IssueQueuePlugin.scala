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
  var cplxWakeupPort: Flow[UInt]             = null

  override def push: Stream[Vec[IqContext]]  = pushPort
  override def pushSlot1Valid: Bool          = pushSlot1Port
  override def issue: Vec[Stream[IqContext]] = issuePorts
  override def flushPort: Bool               = flushSignal
  override def lsWakeup: Flow[UInt]          = lsWakeupPort
  override def cplxWakeup: Flow[UInt]        = cplxWakeupPort

  during setup {
    pushPort      = Stream(Vec(IqContext(), wayCount))
    pushSlot1Port = Bool()
    // 5 issue ports: 0,1 = ALU (non-branch, non-LS, non-CPLX), 2 = branch, 3 = LS,
    // 4 = CPLX (DivEu: CHK + DIV).
    issuePorts    = Vec.fill(5)(Stream(IqContext()))
    flushSignal   = Bool()
    lsWakeupPort  = Flow(UInt(6 bits))   // carries a completed-load pdst
    cplxWakeupPort= Flow(UInt(6 bits))   // carries a completed-DIV pdst
  }

  val logic = during build new Area {
    // Dynamic-wakeup input: default-driven idle (allowOverride) so the IQ
    // elaborates standalone; the LS-EU wiring OVERRIDES it. Concrete zero payload
    // (not assignDontCare) so a sim poke reaches the consumer.
    lsWakeupPort.valid.allowOverride;   lsWakeupPort.valid   := False
    lsWakeupPort.payload.allowOverride; lsWakeupPort.payload := U(0, 6 bits)
    lsWakeupPort.simPublic()
    cplxWakeupPort.valid.allowOverride;   cplxWakeupPort.valid   := False
    cplxWakeupPort.payload.allowOverride; cplxWakeupPort.payload := U(0, 6 bits)
    cplxWakeupPort.simPublic()

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
        // CPLX (DivEu) dynamic dependency: identical mechanism to lsWait, but cleared
        // by cplxWakeup (a completing multi-cycle DIV). A consumer of a DIV result
        // waits here (DIV is variable-latency; no static issue-event).
        val cplxWait   = Reg(Bool()) init False
        // default: hold (overridden by compaction-shift and wakeup-clear below).
        // triggers==0 (static latency-1) AND no pending LS / CPLX source -> ready.
        val ready    = sel && (if (priority == 0) True else triggers(priority - 1 downto 0) === 0) && !lsWait && !cplxWait

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
    // Int scoreboard width = the physical int register count (parametric; was a
    // hardcoded 48 — became 50 when the 2 temp arch regs T0/T1 widened the int
    // pool, so a temp's pdst could index past 48 and alias/over-run the bitmaps).
    val physIntN = m68k040.Global.PHYS_INT_REGS.get
    val sbInt  = new Scoreboard(physIntN) // int physregs
    val sbNzvc = new Scoreboard(16) // NZVC flag physregs (width 4)
    val sbX    = new Scoreboard(16) // X flag physregs (width 4)

    // ---- Dynamic-completion (variant A) LS scoreboard ----
    // lsBusy[p] => int physreg p is produced by an in-flight LS LOAD that has NOT
    // yet completed. A consumer reading such a physreg is held NOT-ready until the
    // LS EU broadcasts lsWakeup(p). This is SEPARATE from the static slot-trigger
    // mechanism (which assumes latency-1): LS producers are tracked ONLY here, not
    // in sbInt, so trigInit never sets a static (auto-clearing) trigger for them.
    val lsBusy = Reg(Bits(physIntN bits)) init 0
    // cplxBusy[p] => int physreg p is produced by an in-flight (not-yet-completed)
    // multi-cycle DIV. A consumer reading it is held NOT-ready until cplxWakeup(p).
    // Same dynamic-completion mechanism as lsBusy, separate bitmap + wakeup port.
    val cplxBusy = Reg(Bits(physIntN bits)) init 0

    // LS class predicate: cluster == LS and a real memory op.
    def isLs(u: RenamedUop): Bool = (u.cluster === m68k040.isa.Cluster.LS) && (u.memOp =/= m68k040.isa.MemOp.NONE)
    // CPLX (DivEu) class predicate: cluster == CPLX (CHK + DIV).
    def isCplx(u: RenamedUop): Bool = u.cluster === m68k040.isa.Cluster.CPLX
    // A CPLX *producer* with dynamic latency = a DIV that writes a physreg. CHK writes
    // nothing (no producer). Only such producers populate cplxBusy / drive cplxWakeup.
    def isCplxProducer(u: RenamedUop): Bool = isCplx(u) && u.pdstValid

    // Is srcB a REAL register operand (vs an immediate)? For ALU µops `useImm`
    // means srcB carries an immediate (no register read). For LS µops `imm` is the
    // ADDRESS DISPLACEMENT and srcB is the STORE DATA register — both are live, so
    // useImm must NOT suppress the srcB (data) dependency. Conflating the two would
    // let a store issue before its data producer retired (reading a stale PRF).
    def srcBIsReg(u: RenamedUop): Bool = u.psrcBValid && (!u.useImm || isLs(u))

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
    // Internal combinational select streams (the registered issue stage feeds the
    // external `issuePorts` from these; see the select section below). Declared here
    // so `issued` (count instrumentation) can reference selPorts.fire.
    val selPorts = Vec.fill(5)(Stream(IqContext()))
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
    // `issued` (count instrumentation) must key off the SELECT ports, since a slot
    // is freed (count decremented) when it moves into the registered issue stage.
    val issued = CountOne(selPorts.map(_.fire))

    // ---- Select: age-ordered, CLASS-filtered (lowest-index-first one-hot) ----
    // ALU ports (0,1) select non-branch ready slots; branch port (2) selects
    // branch-class ready slots (class = context.uop.isBranch). The classes are
    // disjoint, so a slot is selected by at most one port.
    // Classes are disjoint: ALU = non-branch & non-LS & non-CPLX; branch = isBranch;
    // LS = isLs; CPLX = isCplx (CHK + DIV -> DivEu).
    val aluReady = B(slots.map(s => s.ready && !s.context.uop.isBranch && !isLs(s.context.uop) && !isCplx(s.context.uop)))
    val brReady  = B(slots.map(s => s.ready &&  s.context.uop.isBranch))
    val lsReady  = B(slots.map(s => s.ready &&  isLs(s.context.uop)))
    val cplxReady= B(slots.map(s => s.ready &&  isCplx(s.context.uop)))
    val contexts = Vec(slots.map(_.context))

    val oh0 = OHMasking.first(aluReady)
    val oh1 = OHMasking.first(aluReady & ~oh0)
    val ohB = OHMasking.first(brReady)
    val ohL = OHMasking.first(lsReady)
    val ohC = OHMasking.first(cplxReady)

    // ---- FMax: REGISTERED issue->operand-read boundary ----
    // The combinational select (above) + MuxOH(contexts) feeding each EU's S0
    // RegFile read-address was the FMax-critical arc (select->OHMasking->MuxOH->
    // uop.psrcA->PRF read-addr->read->S0). We cut it with a registered stage on
    // every issue port: `selPorts` are the INTERNAL combinational select streams
    // (all IQ bookkeeping — fire/events/scoreboard/wakeup — keys off THESE, at
    // select time); the EXTERNAL `issuePorts` are `selPorts` piped through a
    // registered M2S stage (m2sPipe). The EU therefore reads the PRF off a
    // REGISTERED address, not the select cone — splitting the arc into
    //   select -> MuxOH -> selPort payload reg   (short)
    //   reg -> PRF read-addr -> read -> S0        (short)
    // at the cost of ONE extra issue->execute cycle.
    //
    // WAKEUP RETIMING (correctness): the static-latency-1 wakeup is derived from
    // `events` at SELECT time (below), and the EU pipeline shifts uniformly by +1
    // (every EU gained the same stage), so a producer selected at cycle C still
    // executes/bypasses exactly when a dependent — woken at C, selected at C+1,
    // reading at C+2 — performs its read. The relative producer/consumer timing is
    // PRESERVED, so no change to the events/scoreboard bookkeeping is needed; it is
    // all expressed relative to select time. The LS dynamic-completion wakeup is
    // late-bound (driven by actual completion) and self-consistent at any depth.
    // (`selPorts` itself is declared earlier so `issued` can reference it.)

    // Suppress ALL issue on a flush cycle. flushSignal (= the ROB's registered
    // doFlush pulse, or a test flush) clears every slot's `sel` for NEXT cycle, but
    // the select above reads the CURRENT (combinational) sel — so without this gate
    // a wrong-path slot still issues on the flush cycle, its completion/writeback
    // arrives 1-2 cycles later carrying a now-reused robId, and corrupts the
    // commit/whitebox join. Gating issue on !flushSignal is the correct squash
    // behavior (a single AND on the registered pulse, not a broadcast).
    selPorts(0).valid   := oh0.orR && !flushSignal
    selPorts(0).payload := MuxOH(oh0, contexts)
    selPorts(1).valid   := oh1.orR && !flushSignal
    selPorts(1).payload := MuxOH(oh1, contexts)
    selPorts(2).valid   := ohB.orR && !flushSignal
    selPorts(2).payload := MuxOH(ohB, contexts)
    selPorts(3).valid   := ohL.orR && !flushSignal
    selPorts(3).payload := MuxOH(ohL, contexts)
    selPorts(4).valid   := ohC.orR && !flushSignal
    selPorts(4).payload := MuxOH(ohC, contexts)

    // Registered issue stage: drop the in-flight registered uop on a flush (it is
    // wrong-path), exactly as the slots are squashed. `flush` clears the pipe's
    // valid reg so a squashed selection never reaches the EU.
    //
    // collapsBubble = FALSE is REQUIRED for correctness, not just FMax: with
    // collapsBubble=true the pipe presents `self.ready` whenever its register is
    // empty (a 1-deep skid), which would free an IQ slot BEFORE the EU actually
    // accepts the uop — growing effective queue depth past slotCount and letting a
    // not-ready EU (e.g. a held port, or LS busy) drain a slot it should hold. With
    // collapsBubble=false, `self.ready := m2sPipe.ready` is driven purely by the
    // downstream EU, so a slot is freed EXACTLY when the EU would have accepted it
    // (original backpressure/capacity semantics), with one cycle of register
    // latency added on the forward (payload) path — which is exactly the arc we are
    // cutting. For the always-ready ALU/branch EUs this drains every cycle (no
    // bubble); for the LS EU it back-pressures into the IQ as before.
    for (k <- 0 until 5) {
      issuePorts(k) << selPorts(k).m2sPipe(collapsBubble = false, flush = flushSignal)
    }

    // Free chosen slots when their SELECT port fires (i.e. when the uop moves into
    // the registered issue stage). For ALU/branch the EU is always ready so the
    // pipe always accepts (fire == valid); for LS the pipe back-pressures when the
    // EU is busy, holding the slot — so no issued uop is ever lost.
    for ((slot, i) <- slots.zipWithIndex) {
      slot.fire := (selPorts(0).fire && oh0(i)) ||
                   (selPorts(1).fire && oh1(i)) ||
                   (selPorts(2).fire && ohB(i)) ||
                   (selPorts(3).fire && ohL(i)) ||
                   (selPorts(4).fire && ohC(i))
    }

    // ---- Static-latency-1 wakeup events ----
    // events(j) == slot j issued (fired) this cycle. A slot j that fires is a
    // producer whose result becomes available next cycle; dependents carry a
    // trigger bit at index j which we clear (combinationally into the trigger
    // reg's next value) so they become ready next cycle (back-to-back, lat 1).
    val events = oh0.andMask(selPorts(0).fire) | oh1.andMask(selPorts(1).fire)

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
      dep(sbInt.busy,  sbInt.physToSlot,  uop.psrcB, srcBIsReg(uop))
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
    //
    // SAME-CYCLE WAKEUP: a physreg `p` is only still-in-flight if it is lsBusy AND
    // the load is NOT completing THIS cycle. Without the `!wokeThisCycle` guard a
    // consumer dispatched the exact cycle its producing load broadcasts lsWakeup
    // would latch lsWait=True (reading the not-yet-cleared lsBusy) yet never see
    // the wakeup pulse (already past) -> a lost wakeup that hangs the consumer.
    // This is the back-to-back case the decode-matrix cracker creates (LOAD->temp
    // then the op reading the temp, dispatched within 1-2 cycles).
    def stillBusy(p: UInt): Bool =
      lsBusy(p) && !(lsWakeupPort.valid && lsWakeupPort.payload === p)
    def lsDepInit(uop: RenamedUop): Bool =
      (uop.psrcAValid && stillBusy(uop.psrcA)) || (srcBIsReg(uop) && stillBusy(uop.psrcB))
    val lsDep0 = lsDepInit(pushUop0)
    val lsDep1Base = lsDepInit(pushUop1)
    // Intra-push: slot1 reads slot0's dst and slot0 is an LS load -> slot1 waits.
    val s0IsLsLoad = isLs(pushUop0) && (pushUop0.memOp === m68k040.isa.MemOp.LOAD) && pushUop0.pdstValid
    val lsDep1 = lsDep1Base ||
      (s0IsLsLoad && pushUop1.psrcAValid && (pushUop1.psrcA === pushUop0.pdst)) ||
      (s0IsLsLoad && srcBIsReg(pushUop1) && (pushUop1.psrcB === pushUop0.pdst))

    // Push-time CPLX (DivEu) dependency: same mechanism as LS but on cplxBusy /
    // cplxWakeup. A consumer of an in-flight DIV result latches cplxWait.
    def stillCplxBusy(p: UInt): Bool =
      cplxBusy(p) && !(cplxWakeupPort.valid && cplxWakeupPort.payload === p)
    def cplxDepInit(uop: RenamedUop): Bool =
      (uop.psrcAValid && stillCplxBusy(uop.psrcA)) || (srcBIsReg(uop) && stillCplxBusy(uop.psrcB))
    val cplxDep0 = cplxDepInit(pushUop0)
    val cplxDep1Base = cplxDepInit(pushUop1)
    // Intra-push: slot1 reads slot0's dst and slot0 is a DIV producer -> slot1 waits.
    val s0IsCplxProd = isCplxProducer(pushUop0)
    val cplxDep1 = cplxDep1Base ||
      (s0IsCplxProd && pushUop1.psrcAValid && (pushUop1.psrcA === pushUop0.pdst)) ||
      (s0IsCplxProd && srcBIsReg(pushUop1) && (pushUop1.psrcB === pushUop0.pdst))
    // Intra-push: slot1 reads a physreg that slot0 (pushed same cycle) writes.
    // slot0 ends at slot0Prio; set slot1's trigger bit there.
    //
    // The STATIC (latency-1) int trigger must be SUPPRESSED when slot0 is an LS
    // load: an LS load is variable-latency and produces NO static issue-event
    // (`events` covers only the ALU ports), so a static trigger bit on it would
    // never clear and hang the consumer. That dependency is instead carried by
    // `lsDep1` (the dynamic lsWait, cleared by lsWakeup). This is exactly the
    // cracked LOAD->temp + op-reading-temp pair when both land in one push group.
    // Suppress the static int trigger for an LS load OR a DIV producer (both are
    // variable-latency, tracked dynamically via lsWait/cplxWait, not static triggers).
    val s0WritesInt  = pushUop0.pdstValid && !s0IsLsLoad && !s0IsCplxProd
    val s0WritesNzvc = pushUop0.writesNzvc
    val s0WritesX    = pushUop0.writesX
    when(s0WritesInt  && pushUop1.psrcAValid && pushUop1.psrcA === pushUop0.pdst)              { trig1(slot0Prio) := True }
    when(s0WritesInt  && srcBIsReg(pushUop1) && pushUop1.psrcB === pushUop0.pdst) { trig1(slot0Prio) := True }
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
          wDst.cplxWait := wSrc.cplxWait   // CPLX (DIV) dependency shifts with the slot
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
      wDst0.cplxWait := cplxDep0
      wDst1.context  := wSrc1
      wDst1.triggers := trig1
      wDst1.sel      := pushSlot1Port
      wDst1.lsWait   := lsDep1
      wDst1.cplxWait := cplxDep1
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
         (srcBIsReg(u) && (u.psrcB === lsWakeupPort.payload)))
    })
    for (i <- 0 until slotCount) {
      when(lsWakeMatch(i)) {
        // on a non-compaction cycle, clear slot i; on compaction, clear slot i-wayCount.
        when(pushPort.fire) { if (i >= wayCount) slots(i - wayCount).lsWait := False }
          .otherwise        { slots(i).lsWait := False }
      }
    }

    // ---- CPLX (DivEu) dynamic wakeup: clear cplxWait for slots reading the woken
    // pdst (identical discipline to lsWakeMatch). ----
    val cplxWakeMatch = Vec(slots.map { s =>
      val u = s.context.uop
      cplxWakeupPort.valid && s.sel &&
        ((u.psrcAValid && (u.psrcA === cplxWakeupPort.payload)) ||
         (srcBIsReg(u) && (u.psrcB === cplxWakeupPort.payload)))
    })
    for (i <- 0 until slotCount) {
      when(cplxWakeMatch(i)) {
        when(pushPort.fire) { if (i >= wayCount) slots(i - wayCount).cplxWait := False }
          .otherwise        { slots(i).cplxWait := False }
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
    val push0IsCplxProd = isCplxProducer(pushUop0)
    val push1IsCplxProd = isCplxProducer(pushUop1)
    when(pushPort.fire) {
      when(pushUop0.pdstValid) {
        when(push0IsLs)       { lsBusy(pushUop0.pdst) := True }
          .elsewhen(push0IsCplxProd) { cplxBusy(pushUop0.pdst) := True }
          .otherwise    { sbInt.busy(pushUop0.pdst) := True; sbInt.physToSlot(pushUop0.pdst) := slot0Prio }
      }
      when(pushUop0.writesNzvc) { sbNzvc.busy(pushUop0.pNzvcDst) := True; sbNzvc.physToSlot(pushUop0.pNzvcDst) := slot0Prio }
      when(pushUop0.writesX)    { sbX.busy(pushUop0.pXDst)     := True; sbX.physToSlot(pushUop0.pXDst)     := slot0Prio }
      when(pushSlot1Port) {
        when(pushUop1.pdstValid) {
          when(push1IsLs)       { lsBusy(pushUop1.pdst) := True }
            .elsewhen(push1IsCplxProd) { cplxBusy(pushUop1.pdst) := True }
            .otherwise    { sbInt.busy(pushUop1.pdst) := True; sbInt.physToSlot(pushUop1.pdst) := slot1Prio }
        }
        when(pushUop1.writesNzvc) { sbNzvc.busy(pushUop1.pNzvcDst) := True; sbNzvc.physToSlot(pushUop1.pNzvcDst) := slot1Prio }
        when(pushUop1.writesX)    { sbX.busy(pushUop1.pXDst)     := True; sbX.physToSlot(pushUop1.pXDst)     := slot1Prio }
      }
    }
    // On issue, clear busy for the issued producer's dst(s) so later pushes do
    // not depend on an already-issued (latency-1, result-available) producer.
    for (k <- 0 until wayCount) {
      val ctx = selPorts(k).payload
      when(selPorts(k).fire) {
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
    // ---- Dynamic CPLX (DivEu) wakeup: clear cplxBusy for the completed DIV's pdst ----
    // Same priority discipline as lsBusy (a same-cycle re-allocation wins).
    when(cplxWakeupPort.valid) { cplxBusy(cplxWakeupPort.payload) := False }
    when(pushPort.fire) {
      when(pushUop0.pdstValid && push0IsCplxProd) { cplxBusy(pushUop0.pdst) := True }
      when(pushSlot1Port && pushUop1.pdstValid && push1IsCplxProd) { cplxBusy(pushUop1.pdst) := True }
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
      cplxBusy    := 0
      // After flush line 0 is empty next cycle, so push.ready may re-assert.
      readyReg := True
    }
  }
}
