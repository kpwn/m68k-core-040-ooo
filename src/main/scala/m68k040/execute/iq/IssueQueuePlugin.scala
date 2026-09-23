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
/** Bit indices of the per-slot PER-SOURCE dynamic-wait vector (`slot.dynWait`).
  *
  * ONE BIT PER (SOURCE OPERAND x DYNAMIC PRODUCER CLASS). This replaces the seven
  * per-CLASS `*Wait` bits (lsWait / cplxWait / aluSlowWait / lsNzvcWait / cplxNzvcWait /
  * cplxFpWait / cplxFpccWait) that used to cover ALL of a slot's sources with a single
  * flop each.
  *
  * WHY: a per-class bit cannot tell WHICH of its sources is still outstanding, so
  * clearing it on the first matching wakeup would release a consumer whose OTHER source
  * of the same class is still in flight (a silent stale-PRF read, not a hang). The old
  * code paid for that with a `*Remaining` guard evaluated PER SLOT, PER CYCLE: for every
  * source it re-indexed the class's `*Busy` bitmap (50-deep for the int classes, 16-deep
  * for the flag/FP ones) and re-compared against the live wakeup payload. That is
  * 9 x 50:1 + 4 x 16:1 dynamic-index muxes PER SLOT, x16 slots -- measured at 2,960 LUT
  * and 1,343 MUXF7/F8 in the post-route netlist (the fanin cone of the 112 wait flops
  * that is reachable from the `*Busy` registers).
  *
  * With one bit per source the guard is STRUCTURAL: each bit is set at push from its own
  * source's busy lookup (on the push path, x2, where that lookup already happened) and
  * cleared by a bare tag compare against its own class's wakeup. No slot ever reads a
  * `*Busy` bitmap again. A slot is dynamically ready exactly when the whole vector is 0,
  * which is the same cycle the old `*Remaining==0` condition was satisfied -- see the
  * equivalence argument on `dynWaitClear` below. Issue order and wakeup latency are
  * unchanged; this is an area/congestion transform, not a scheduling change.
  *
  * The class split is DELIBERATELY KEPT (16 bits, not 8). Merging LS_A/CPLX_A/SLOW_A into
  * one "srcA is waiting" bit would be legal under the one-in-flight-producer-per-physreg
  * invariant this file already relies on, and would cost 8 fewer flops per slot -- but it
  * would convert any future violation of that invariant from a HANG into a SILENT
  * WRONG-VALUE issue (a cross-class wakeup carrying a recycled tag would release a
  * consumer whose real producer is still running). Flops are the cheap resource here
  * (24.5% used vs 78.8% LUT); that trade is not worth taking.
  */
object DynWait {
  // int sources (psrcA / psrcB / psrcC), one bit per dynamic int producer class
  val LS_A        = 0   // cleared by lsWakeup
  val LS_B        = 1
  val LS_C        = 2
  val CPLX_A      = 3   // cleared by cplxWakeup
  val CPLX_B      = 4
  val CPLX_C      = 5
  val SLOW_A      = 6   // cleared by aluSlowWakeup.pdst
  val SLOW_B      = 7
  val SLOW_C      = 8
  // NZVC source (pNzvcSrc), one bit per dynamic NZVC producer class
  val LS_NZVC     = 9   // cleared by lsNzvcWakeup
  val CPLX_NZVC   = 10  // cleared by cplxNzvcWakeup
  val SLOW_NZVC   = 11  // cleared by aluSlowWakeup.pNzvcDst
  // X source (pXSrc): only the slow ALU produces X dynamically
  val SLOW_X      = 12  // cleared by aluSlowWakeup.pXDst
  // FP-data sources (pFpSrcA / pFpSrcB) and FPCC source (pFpccSrc): CPLX only
  val FP_A        = 13  // cleared by cplxFpWakeup
  val FP_B        = 14
  val FPCC        = 15  // cleared by cplxFpccWakeup
  val width       = 16

  /** The old per-class views, for sim-only debug taps and the equivalence assertions. */
  val lsBits       = Seq(LS_A, LS_B, LS_C)
  val cplxBits     = Seq(CPLX_A, CPLX_B, CPLX_C)
  val aluSlowBits  = Seq(SLOW_A, SLOW_B, SLOW_C, SLOW_NZVC, SLOW_X)
  val lsNzvcBits   = Seq(LS_NZVC)
  val cplxNzvcBits = Seq(CPLX_NZVC)
  val cplxFpBits   = Seq(FP_A, FP_B)
  val cplxFpccBits = Seq(FPCC)
}

class IssueQueuePlugin(val earlyStoreAddress: Boolean = false,
                       val earlyAutoStoreAddress: Boolean = false,
                       val loadBypassUnreadyLoad: Boolean = false) extends FiberPlugin
    with IssueQueueService with m68k040.services.LateStoreDataService {
  require(!earlyAutoStoreAddress || earlyStoreAddress)
  private var lateStorePorts: Option[m68k040.services.LateStoreDataPorts] = None
  override def lateStoreData = lateStorePorts
  val slotCount = 16
  val wayCount  = 2
  val lineCount = 8 // slotCount / wayCount

  var pushPort      : Stream[Vec[IqContext]] = null
  var pushSlot1Port : Bool                   = null
  var issuePorts    : Vec[Stream[IqContext]] = null
  var aluFastAcceptNextPorts: Vec[Bool]       = null
  var flushSignal   : Bool                   = null
  var lsWakeupPort  : Flow[UInt]             = null
  var lsNzvcWakeupPort: Flow[UInt]           = null
  var cplxWakeupPort: Flow[UInt]             = null
  var cplxNzvcWakeupPort: Flow[UInt]         = null
  var aluSlowWakeupPorts: Vec[Flow[AluSlowWakeup]] = null
  var cplxFpWakeupPort  : Flow[UInt]         = null
  var cplxFpccWakeupPort: Flow[UInt]         = null

  override def push: Stream[Vec[IqContext]]  = pushPort
  override def pushSlot1Valid: Bool          = pushSlot1Port
  override def issue: Vec[Stream[IqContext]] = issuePorts
  override def aluFastAcceptNext: Vec[Bool]   = aluFastAcceptNextPorts
  override def flushPort: Bool               = flushSignal
  override def lsWakeup: Flow[UInt]          = lsWakeupPort
  override def lsNzvcWakeup: Flow[UInt]      = lsNzvcWakeupPort
  override def cplxWakeup: Flow[UInt]        = cplxWakeupPort
  override def cplxNzvcWakeup: Flow[UInt]    = cplxNzvcWakeupPort
  override def aluSlowWakeup: Vec[Flow[AluSlowWakeup]] = aluSlowWakeupPorts
  override def cplxFpWakeup: Flow[UInt]   = cplxFpWakeupPort
  override def cplxFpccWakeup: Flow[UInt] = cplxFpccWakeupPort

  during setup {
    if (earlyStoreAddress) lateStorePorts = Some(m68k040.services.LateStoreDataPorts())
    pushPort      = Stream(Vec(IqContext(), wayCount))
    pushSlot1Port = Bool()
    // 5 issue ports: 0,1 = ALU (non-branch, non-LS, non-CPLX), 2 = branch, 3 = LS,
    // 4 = CPLX (DivEu: CHK + DIV).
    issuePorts    = Vec.fill(5)(Stream(IqContext()))
    aluFastAcceptNextPorts = Vec.fill(2)(Bool())
    flushSignal   = Bool()
    lsWakeupPort  = Flow(UInt(6 bits))   // carries a completed-load pdst
    lsNzvcWakeupPort = Flow(UInt(4 bits)) // carries a completed NZVC-writing-store pNzvcDst
    cplxWakeupPort= Flow(UInt(6 bits))   // carries a completed-DIV pdst
    cplxNzvcWakeupPort = Flow(UInt(4 bits)) // carries a completed CPLX flag-writer's pNzvcDst (task #167)
    // ONE slow-shift wakeup per ALU EU (2): each carries a completed shift's int+NZVC+X.
    aluSlowWakeupPorts = Vec.fill(2)(Flow(AluSlowWakeup()))
    cplxFpWakeupPort   = Flow(UInt(4 bits)) // a completed FP op's pFpDst   (4-bit FP tag)
    cplxFpccWakeupPort = Flow(UInt(4 bits)) // a completed FP op's pFpccDst (4-bit FPCC tag)
  }

  val logic = during build new Area {
    // Dynamic-wakeup input: default-driven idle (allowOverride) so the IQ
    // elaborates standalone; the LS-EU wiring OVERRIDES it. Concrete zero payload
    // (not assignDontCare) so a sim poke reaches the consumer.
    lsWakeupPort.valid.allowOverride;   lsWakeupPort.valid   := False
    lsWakeupPort.payload.allowOverride; lsWakeupPort.payload := U(0, 6 bits)
    lsWakeupPort.simPublic()
    lsNzvcWakeupPort.valid.allowOverride;   lsNzvcWakeupPort.valid   := False
    lsNzvcWakeupPort.payload.allowOverride; lsNzvcWakeupPort.payload := U(0, 4 bits)
    lsNzvcWakeupPort.simPublic()
    cplxWakeupPort.valid.allowOverride;   cplxWakeupPort.valid   := False
    cplxWakeupPort.payload.allowOverride; cplxWakeupPort.payload := U(0, 6 bits)
    cplxWakeupPort.simPublic()
    cplxNzvcWakeupPort.valid.allowOverride;   cplxNzvcWakeupPort.valid   := False
    cplxNzvcWakeupPort.payload.allowOverride; cplxNzvcWakeupPort.payload := U(0, 4 bits)
    cplxNzvcWakeupPort.simPublic()
    aluSlowWakeupPorts.foreach { p =>
      p.valid.allowOverride; p.valid := False
      p.payload.allowOverride
      p.payload.pdst := U(0, 6 bits); p.payload.pdstValid := False
      p.payload.pNzvcDst := U(0, 4 bits); p.payload.nzvcValid := False
      p.payload.pXDst := U(0, 4 bits); p.payload.xValid := False
      p.simPublic()
    }
    cplxFpWakeupPort.valid.allowOverride;   cplxFpWakeupPort.valid   := False
    cplxFpWakeupPort.payload.allowOverride; cplxFpWakeupPort.payload := U(0, 4 bits)
    cplxFpWakeupPort.simPublic()
    cplxFpccWakeupPort.valid.allowOverride;   cplxFpccWakeupPort.valid   := False
    cplxFpccWakeupPort.payload.allowOverride; cplxFpccWakeupPort.payload := U(0, 4 bits)
    cplxFpccWakeupPort.simPublic()
    // Fail-safe default: an omitted integration wire may stall fast ALU selection but
    // must never re-open the select-time wakeup corruption window.  Standalone tests
    // explicitly override these bits from their source harness.
    aluFastAcceptNextPorts.foreach { p => p.allowOverride; p := False; p.simPublic() }

    // ---- Slot array (priority = line*wayCount + way; 0 = oldest) ----
    val lines = for (line <- 0 until lineCount) yield new Area {
      val ways = for (way <- 0 until wayCount) yield new Area {
        val priority = line * wayCount + way
        val fire     = Bool()                         // this slot is being issued this cycle
        val sel      = Reg(Bool()) init False          // occupied
        val selComb  = CombInit(sel)                   // sel after issue this cycle
        val triggers = Reg(Bits((priority + 1) bits)) init 0
        // NARROW per-slot record (see IqHot's doc comment in IqContext.scala). The wide
        // dispatch payload that used to sit here (`Reg(IqContext())`, 418 surviving FF
        // per slot in the routed netlist) now lives in the `coldWay0/coldWay1` Mems,
        // addressed by `hot.robId`; only the 7-bit {robId, coldWay} address rides the
        // compacting slot array and the select cone.
        val hot      = Reg(IqHot())
        // Stored slow-class bit.  Keep opcode decoding out of the timing-sensitive
        // select cone; shift this bit with the slot during compaction.
        val isAluSlow = Reg(Bool()) init False
        // ---- PER-SOURCE dynamic-wait vector (see the `DynWait` object above) ----
        // One REGISTERED bit per (source operand x dynamic producer class): set at push
        // from that source's own `*Busy` lookup, cleared by a bare tag compare against
        // that class's wakeup payload. Replaces the seven per-CLASS `*Wait` bits and,
        // with them, the per-slot `*Remaining` / `*StillDep` re-evaluation that had to
        // re-index the 50-deep / 16-deep `*Busy` bitmaps for every source, every cycle,
        // in all 16 slots.
        //
        // `dynWaitNext` is the combinational NEXT value (default: hold). EVERY maintenance
        // site below -- compaction shift, push insert, wakeup clear -- writes the WIRE, in
        // the same order the old code wrote the registers, so last-assignment-wins keeps
        // the original precedence (compaction first, wakeup clear overriding it).
        val dynWait     = Reg(Bits(DynWait.width bits)) init 0
        val dynWaitNext = Bits(DynWait.width bits)
        dynWaitNext := dynWait
        dynWait     := dynWaitNext
        // Registered OR-reduction of the SAME next value, so `dynWaitAny` is bit-exactly
        // `dynWait.orR` in every cycle (including reset) while costing the ready cone ONE
        // flop input instead of a 16-wide OR. This keeps the select cone -- the hottest
        // contested logic in the machine -- no wider than it was with seven separate bits.
        val dynWaitAny  = Reg(Bool()) init False
        dynWaitAny  := dynWaitNext.orR
        // default: hold (overridden by compaction-shift and wakeup-clear below).
        // triggers==0 (static lat1) AND no outstanding dynamic source -> ready.
        val ready    = sel && (if (priority == 0) True else triggers(priority - 1 downto 0) === 0) &&
                       !dynWaitAny

        // ---- Sim-only per-CLASS views of `dynWait` ----------------------------------
        // The fuzz / lockstep traces and PortedTestRunner's cplxNzvcWait watchdog read
        // `slot.lsWait` / `.cplxWait` / `.cplxNzvcWait` by name. Keep those names as
        // OR-reductions of the matching `dynWait` bits so the existing instrumentation
        // keeps working unchanged -- elaborated ONLY under GenerationFlags.simulation
        // (like the `fsCtx` cold-split shadow below), so a synth/GenVerilog build has
        // no such logic at all and these vals are null there.
        // NO setName here: an explicit name is ABSOLUTE in SpinalHDL, so naming these
        // "lsWait" collided across all 16 slots ("Reserved name lsWait is not free").
        // Leave them to the Area's val-name reflection, which scopes them per slot.
        private def dynView(bits: Seq[Int]): Bool = GenerationFlags.simulation {
          val b = bits.map(dynWait(_)).reduceLeft(_ || _)
          b.simPublic(); b
        }
        val lsWait       = dynView(DynWait.lsBits)
        val cplxWait     = dynView(DynWait.cplxBits)
        val aluSlowWait  = dynView(DynWait.aluSlowBits)
        val lsNzvcWait   = dynView(DynWait.lsNzvcBits)
        val cplxNzvcWait = dynView(DynWait.cplxNzvcBits)
        val cplxFpWait   = dynView(DynWait.cplxFpBits)
        val cplxFpccWait = dynView(DynWait.cplxFpccBits)

        when(fire) { selComb := False }
        sel := selComb
      }
    }
    val slots = lines.flatMap(_.ways) // index == priority
    GenerationFlags.simulation {
      // Independent old formulas, evaluated from the stored raw fields. This
      // checks classification through insertion, compaction and slot reuse,
      // not merely that assignFrom agrees with another call to itself.
      for (s <- slots) {
        val h = s.hot
        val oldLs = h.cluster === m68k040.isa.Cluster.LS &&
          (h.memOp =/= m68k040.isa.MemOp.NONE || h.leaAddr)
        val oldCplx = h.cluster === m68k040.isa.Cluster.CPLX
        val oldBException = h.op === m68k040.decode.DecOp.PACK ||
          h.op === m68k040.decode.DecOp.UNPK || h.op === m68k040.decode.DecOp.BITFIELD ||
          h.op === m68k040.decode.DecOp.BFRESOLVE
        val oldBRead = h.psrcBValid && (!h.useImm || oldLs || oldBException)
        when(s.sel) {
          assert(h.isLsClass === oldLs, "IQ hot predicate: LS class mismatch")
          assert(h.isCplxClass === oldCplx, "IQ hot predicate: CPLX class mismatch")
          assert(h.srcBRead === oldBRead, "IQ hot predicate: B register-read mismatch")
        }
      }
    }
    // ---- debug-only observability (task #139 finding #1 investigation) ----
    // Zero synth impact (sim tap only, not referenced by any RTL logic).
    // (Every field these taps expose is in the HOT record, so the split costs no
    // observability -- the ad-hoc slot traces past investigations relied on still work.)
    slots.foreach { s => s.sel.simPublic(); s.hot.robId.simPublic(); s.hot.op.simPublic()
      s.hot.cluster.simPublic(); s.hot.memOp.simPublic(); s.hot.leaAddr.simPublic()
      s.ready.simPublic()
      // The per-class `s.lsWait`/`s.cplxWait`/`s.cplxNzvcWait` taps are now sim-only
      // OR-views of `dynWait`, published at their point of definition in the slot Area.
      s.dynWait.simPublic(); s.dynWaitAny.simPublic()
      s.triggers.simPublic()
      s.isAluSlow.simPublic()
      s.hot.psrcA.simPublic(); s.hot.psrcAValid.simPublic()
      s.hot.psrcB.simPublic(); s.hot.psrcBValid.simPublic()
      s.hot.pXSrc.simPublic(); s.hot.readsX.simPublic()
      s.hot.pNzvcSrc.simPublic(); s.hot.readsNzvc.simPublic()
      s.hot.psrcC.simPublic(); s.hot.psrcCValid.simPublic() }
    flushSignal.simPublic()

    // ---- COLD PAYLOAD STORE ----------------------------------------------------------
    // The dispatch-only half of the µop: written ONCE at push, read ONCE at issue, never
    // examined by any IQ logic in between. In the old structure it was carried in the
    // compacting slot array, which meant it was (a) 16 x ~342 FF of shift register and
    // (b) the payload of five 16:1 `MuxOH` select trees. Both costs are structural, not
    // synthesis artifacts: Vivado can and does prune the per-port payload bits an EU
    // never reads (the five m2sPipe stages measured 128/128/245/104/209 FF, not 5 x 418),
    // but it CANNOT prune the slot array, because every slot's field feeds the next
    // slot's field down the compaction chain for as long as ANY of the five ports reads it.
    //
    // ADDRESSING: `robId`. It is already in every IqContext, it is unique across all
    // in-flight µops, and a ROB entry cannot be freed while its µop is still sitting
    // un-issued in the IQ (`head` advances only on retire, and this µop has not even
    // executed). `allocReadySig` holds `count <= depth-2 = 62`, so `tail` can never lap
    // `head` and re-issue a live robId. That is the SAME ring invariant the ROB already
    // relies on everywhere -- this introduces no new one, and needs no free list.
    //
    // BANKING: by PUSH WAY, deliberately NOT by robId parity. Parity banking would be
    // slightly cheaper (2 x 32 deep instead of 2 x 64) but only works because
    // `robId1 == robId0 + 1` makes the two pushed ids opposite-parity -- a property of
    // DispatchPlugin's wiring to `RobPlugin.robId0/robId1` (= `tail` / `tail+1`) that the
    // IQ has no way to enforce and that its own standalone test harness does not respect.
    // Banking by way needs NOTHING beyond robId uniqueness: way 0 always writes bank 0 at
    // its own robId and way 1 always writes bank 1 at its own, so each Mem has exactly ONE
    // write port (hitting MultiPortWritesSymplifier's `writes.size <= 1` early return --
    // the same plain-distributed-RAM shape as the ROB's faultDynMem/nextPcMem, with no LVT
    // or XOR bank machinery), and two live µops can never collide on an address because
    // their robIds differ.
    //
    // NO RAW HAZARD: a slot's `sel` is set by the SAME `when(pushPort.fire)` block that
    // writes the Mem, so it is a register write visible at C+1. The earliest a pushed µop
    // can be selected is therefore C+1, one full cycle after its row was written. The
    // readAsync never races its own write, in either write-first or read-first semantics.
    //
    // STABLE ACROSS BACK-PRESSURE: the read address is `pipedPorts(k).payload.robId`, held
    // in the m2sPipe register for as long as a back-pressuring EU (LS/CPLX) holds the
    // handshake. The row cannot be overwritten during that hold for exactly the reason
    // above -- the µop has not completed, so its ROB entry is live and its robId is not
    // re-allocatable. On a flush the ROB does recycle ids (`tail := head`), but a flush
    // also clears every slot and force-invalidates the pipe output (`issuePorts(k).valid
    // := piped.valid && !flushSignal`), so no consumer can observe a recycled row.
    val coldWay0 = Mem(RenamedUop(), m68k040.Global.ROB_DEPTH_DEFAULT) // robId-addressed
    val coldWay1 = Mem(RenamedUop(), m68k040.Global.ROB_DEPTH_DEFAULT) // robId-addressed
    coldWay0.addAttribute("ram_style", "distributed") // reads must be async; BRAM cannot serve
    coldWay1.addAttribute("ram_style", "distributed")
    /** The one and only cold read: 2:1 over the two single-write-port banks. */
    def coldRead(h: IqHot): RenamedUop =
      Mux(h.coldWay, coldWay1.readAsync(h.robId), coldWay0.readAsync(h.robId))

    val slotIdxW = log2Up(slotCount) // 4 bits

    // The compacting slot array moves; producer-position entries do not. One
    // shared line epoch replaces a decrement of every scoreboard row on push.
    // See docs/iq-static-position.md for the modular-position invariant.
    require(wayCount == 2 && slotCount == (1 << slotIdxW))
    val positionEpoch = Reg(UInt(log2Up(lineCount) bits)) init 0
    val nextPositionEpoch = positionEpoch + 1
    when(pushPort.fire) { positionEpoch := nextPositionEpoch }
    def slotAtEpoch(position: UInt, epoch: UInt): UInt =
      ((position(slotIdxW - 1 downto 1) - epoch).asBits ## position(0)).asUInt

    // ---- Scoreboards (one per reg class).
    // RELIED-UPON INVARIANT: at most ONE in-flight producer per physical register
    // pre-commit. Rename allocates a unique pdst for every writer and does not
    // reuse a physreg until the prior mapping commits, so a physreg has at most
    // one un-issued producer in the queue at a time. Both `physToPosition` (a single
    // producer slot per physreg) and the push-before-issue-clear ordering within
    // a cycle (push sets busy[p] then issue may clear busy of an OLDER mapping)
    // are correct ONLY under this invariant. If a physreg could have two in-flight
    // producers, physToPosition would alias and a dependent could track the wrong one.
    // busy[p] => the producer occupies slotAtEpoch(physToPosition[p], positionEpoch).
    // Only insertion writes positions; compaction changes the shared epoch.
    class Scoreboard(depth: Int) extends Area {
      val busy       = Reg(Bits(depth bits)) init 0
      val physToPosition = Reg(Vec(UInt(slotIdxW bits), depth))
      val legacyPhysToSlot = GenerationFlags.simulation {
        Reg(Vec(UInt(slotIdxW bits), depth))
      }
      GenerationFlags.simulation {
        for (p <- 0 until depth) {
          when(pushPort.fire) {
            legacyPhysToSlot(p) := (legacyPhysToSlot(p) - wayCount).resize(slotIdxW)
          }
          when(busy(p)) {
            assert(slotAtEpoch(physToPosition(p), positionEpoch) === legacyPhysToSlot(p),
              "IQ stationary position differs from legacy compacted slot")
          }
        }
      }
      def insert(physreg: UInt, way: Int): Unit = {
        // (14 + way) + 2*(E+1) == 2*E + way modulo 16.
        physToPosition(physreg) := (positionEpoch.asBits ## B(way, 1 bits)).asUInt
        GenerationFlags.simulation {
          legacyPhysToSlot(physreg) := slotCount - wayCount + way
        }
      }
    }
    // Int scoreboard width = the physical int register count (parametric; was a
    // hardcoded 48 — became 50 when the 2 temp arch regs T0/T1 widened the int
    // pool, so a temp's pdst could index past 48 and alias/over-run the bitmaps).
    val physIntN = m68k040.Global.PHYS_INT_REGS.get
    // The ONE place where the configured pool size meets the plain constant every
    // structural site derives from (RenameStage's freelist, RegfileSpec.Int.depth). If a
    // config ever sets physInt to something else, these bitmaps and the PRF would still be
    // built for the constant while rename handed out ids from the config -- the exact
    // silent-corruption/frozen-machine class documented on Global.PHYS_INT_REGS_DEFAULT.
    // Fail the BUILD instead of the board.
    require(physIntN == m68k040.Global.PHYS_INT_REGS_DEFAULT &&
            physIntN == m68k040.execute.regfile.RegfileSpec.Int.depth,
      s"int physical-register pool size disagreement: Global.PHYS_INT_REGS=$physIntN, " +
      s"Global.PHYS_INT_REGS_DEFAULT=${m68k040.Global.PHYS_INT_REGS_DEFAULT} (what " +
      s"RenameStage's intFree allocates from), RegfileSpec.Int.depth=" +
      s"${m68k040.execute.regfile.RegfileSpec.Int.depth} (the int PRF Mem). All three MUST " +
      s"be equal -- see Global.PHYS_INT_REGS_DEFAULT.")
    val sbInt  = new Scoreboard(physIntN) // int physregs
    val sbNzvc = new Scoreboard(16) // NZVC flag physregs (width 4)
    sbNzvc.busy.simPublic()  // debug-only (task #141)
    val sbX    = new Scoreboard(16) // X flag physregs (width 4)
    sbX.busy.simPublic(); sbX.physToPosition.simPublic()
    sbInt.busy.simPublic(); sbInt.physToPosition.simPublic(); positionEpoch.simPublic()
    // FP-DATA / FPCC static scoreboards. Both rename classes have 16 physical entries
    // (spec Decisions 4+6), so both are 4-bit-tag/16-deep, exactly like sbNzvc/sbX.
    // NOTE (see this task's scope note): today EVERY FP producer is CPLX and therefore
    // routes to the dynamic cplxFp*/cplxFpcc* bitmaps below, so these two are no-ops --
    // they exist so the push-side routing chain is TOTAL for the non-CPLX FP producers
    // Task 11 adds (FRESTORE's FP-PRF write) and any future non-CPLX FP producer (e.g. an
    // FMOVEM FP-data-register-list implementation -- still unowned by any task in this plan).
    val sbFp   = new Scoreboard(16)
    val sbFpcc = new Scoreboard(16)
    sbFp.busy.simPublic(); sbFpcc.busy.simPublic()  // debug-only

    // ---- MANDATORY same-cycle bypass for the C+1 scoreboard clear (task #219, Fix 2) ----
    // The issue-time `sb*.busy` clear is RETIMED to C+1 (it decodes off the registered
    // m2sPipe payload instead of the live OHMasking/16:1 MuxOH select cone -- 12 of the
    // top-100 worst routed endpoints in `synth/archive/866437c_fmax_fanout_fix_decode_fetch/`
    // were `DcachePlugin_logic_fsm_stateReg -> IssueQueuePlugin_logic_sb{Int,X,Fp}_busy`).
    //
    // THIS BYPASS IS LOAD-BEARING FOR CORRECTNESS, NOT AN OPTIMIZATION. Without it the
    // retime introduces a genuine, silent DEADLOCK class:
    //
    //   C   : producer P (a latency-1 sb* producer) wins selection and fires. `events`
    //         carries its slot, so any trigger referencing it clears. Its slot is freed.
    //   C+1 : the clear of `sb*.busy(P.dst)` is only NOW being written (visible at C+2),
    //         so the raw register still reads BUSY. A consumer uop pushed at C+1 would
    //         latch a static trigger at P's post-compaction slot -- but P's slot is
    //         gone and `events` at C+1 no longer carries it, so that trigger's ONLY
    //         clearing event has already passed. The consumer waits forever.
    //
    // `sb*BusyEff = busy & ~sb*Clr` (where `sb*Clr` is the one-hot decode of THIS cycle's
    // retimed clear) makes push-time `trigInit` see the producer as already-done, which is
    // architecturally correct: P issued at C, so its latency-1 result is available at C+1,
    // and a uop pushed at C+1 can be selected no earlier than C+2 and reads at C+3 --
    // strictly LATER than the pre-retime behaviour it replaces, never earlier.
    //
    // `sb*Clr` are combinational, defaulted to 0 here and driven only by the retimed clear
    // loop near the bottom of this file (declared here purely so `trigInit`, which is
    // elaborated earlier, can read the bypassed value).
    val sbIntClr  = Bits(physIntN bits); sbIntClr  := 0
    val sbNzvcClr = Bits(16 bits);       sbNzvcClr := 0
    val sbXClr    = Bits(16 bits);       sbXClr    := 0
    val sbFpClr   = Bits(16 bits);       sbFpClr   := 0
    val sbFpccClr = Bits(16 bits);       sbFpccClr := 0
    val sbIntBusyEff  = sbInt.busy  & ~sbIntClr
    val sbNzvcBusyEff = sbNzvc.busy & ~sbNzvcClr
    val sbXBusyEff    = sbX.busy    & ~sbXClr
    val sbFpBusyEff   = sbFp.busy   & ~sbFpClr
    val sbFpccBusyEff = sbFpcc.busy & ~sbFpccClr

    // ---- Dynamic-completion (variant A) LS scoreboard ----
    // lsBusy[p] => int physreg p is produced by an in-flight LS LOAD that has NOT
    // yet completed. A consumer reading such a physreg is held NOT-ready until the
    // LS EU broadcasts lsWakeup(p). This is SEPARATE from the static slot-trigger
    // mechanism (which assumes latency-1): LS producers are tracked ONLY here, not
    // in sbInt, so trigInit never sets a static (auto-clearing) trigger for them.
    val lsBusy = Reg(Bits(physIntN bits)) init 0
    lsBusy.simPublic()  // debug-only (task #139 CMP2/CHK2 hang investigation)
    // lsNzvcBusy[p] => NZVC physreg p is produced by an in-flight (not-yet-completed) LS
    // op that writes NZVC (a MOVE-to-memory store / RTR CCR-restore). A flag-reader of p
    // is held NOT-ready until lsNzvcWakeup(p). SEPARATE from sbNzvc (static latency-1): an
    // LS NZVC producer issues on the LS port and generates NO static ALU/branch event, so
    // a static sbNzvc trigger on it would never clear (it would hang the reader).
    val lsNzvcBusy = Reg(Bits(16 bits)) init 0
    lsNzvcBusy.simPublic()  // debug-only (task #139 seed=3 CCR investigation)
    // cplxBusy[p] => int physreg p is produced by an in-flight (not-yet-completed)
    // multi-cycle DIV. A consumer reading it is held NOT-ready until cplxWakeup(p).
    // Same dynamic-completion mechanism as lsBusy, separate bitmap + wakeup port.
    val cplxBusy = Reg(Bits(physIntN bits)) init 0
    // cplxNzvcBusy[p] => NZVC physreg p is produced by an in-flight (not-yet-completed)
    // CPLX (DivEu) op that writes flags (DIV/MUL/CHK/CMP2/CHK2). A flag-reader of p is
    // held NOT-ready until cplxNzvcWakeup(p). Task #167: SEPARATE from cplxBusy (int dst)
    // and from sbNzvc (static lat1) — a CPLX flag-writer must NOT use the static scoreboard
    // (its busy bit is force-cleared at ISSUE time by the task #141 clear loop below, long
    // before a multi-cycle DIV/MUL's real flag writeback lands, exposing a stale-flag read
    // by any immediately-following Bcc/flag-consumer).
    val cplxNzvcBusy = Reg(Bits(16 bits)) init 0
    cplxNzvcBusy.simPublic()  // debug-only (task #167 IQ-NZVC trace)
    // SLOW-ALU dynamic-completion scoreboards: one per reg class the
    // shift writes (int dst + NZVC + X). A consumer reading any of these is held NOT-
    // ready until the matching aluSlowWakeup. SEPARATE from the static sbInt/sbNzvc/sbX
    // (latency-1) so a slow producer gets NO static trigger (it wakes at S3).
    val aluSlowIntBusy  = Reg(Bits(physIntN bits)) init 0
    val aluSlowNzvcBusy = Reg(Bits(16 bits)) init 0
    val aluSlowXBusy    = Reg(Bits(16 bits)) init 0
    lateStorePorts.foreach { p =>
      p.queryReady := !(lsBusy | cplxBusy | aluSlowIntBusy)(p.queryTag)
      val lsWoke = lsWakeupPort.valid && lsWakeupPort.payload === p.queryTag
      val cplxWoke = cplxWakeupPort.valid && cplxWakeupPort.payload === p.queryTag
      // The consumer registers this promise before reading. LS may announce a
      // next-cycle write; CPLX is already writing. Slow ALU wakes TWO cycles
      // before its write, so retain the registered-clear delay for that class.
      p.queryReadyNext := (!lsBusy(p.queryTag) || lsWoke) &&
        (!cplxBusy(p.queryTag) || cplxWoke) && !aluSlowIntBusy(p.queryTag)
      p.queryTag.simPublic(); p.queryReady.simPublic(); p.queryReadyNext.simPublic()
    }

    // cplxFpBusy[p] => FP physreg p is produced by an in-flight (not-yet-completed) CPLX
    // FP op. A reader of p is held NOT-ready until cplxFpWakeup(p). SEPARATE from cplxBusy
    // (int physregs, 6-bit, different id space) and from sbFp (static latency-1, wrong for
    // a variable-latency FP EU -- see cplxFpWakeup's doc comment in IqContext.scala).
    val cplxFpBusy   = Reg(Bits(16 bits)) init 0
    // cplxFpccBusy[p] => FPCC physreg p is produced by an in-flight CPLX FP op. Every
    // HW-native FP op writes FPCC, so this bitmap is set strictly more often than cplxFpBusy.
    val cplxFpccBusy = Reg(Bits(16 bits)) init 0
    cplxFpBusy.simPublic(); cplxFpccBusy.simPublic()  // debug-only

    // LS class predicate: cluster == LS and a real memory op OR a LEA address-generate
    // (leaAddr, memOp NONE — it rides the LS-EU AGU to compute the EA address with no
    // memory access).
    def isLs(u: IqHot): Bool = u.isLsClass
    // CPLX (DivEu) class predicate: cluster == CPLX (CHK + DIV).
    def isCplx(u: IqHot): Bool = u.isCplxClass
    // A CPLX *producer* with dynamic latency = a DIV that writes a physreg. CHK writes
    // nothing (no producer). Only such producers populate cplxBusy / drive cplxWakeup.
    def isCplxProducer(u: IqHot): Bool = isCplx(u) && u.pdstValid
    // A CPLX op that writes flags = a dynamic (variable-latency) NZVC producer (task
    // #166). Includes CHK/CMP2/CHK2 (no int dst at all) as well as DIV/MUL (which may
    // ALSO be an isCplxProducer int-dst producer at the same time — the two bitmaps are
    // independent, exactly like isLsNzvcProducer/isLs below).
    def isCplxNzvcProducer(u: IqHot): Bool = isCplx(u) && u.writesNzvc
    // A CPLX op that writes an FP register = a dynamic (variable-latency) FP producer.
    // FCMP/FTST are excluded here (pFpDstValid=False) but ARE FPCC producers below.
    def isCplxFpProducer(u: IqHot): Bool   = isCplx(u) && u.pFpDstValid
    // A CPLX op that writes FPCC = a dynamic FPCC producer (every HW-native FP op).
    def isCplxFpccProducer(u: IqHot): Bool = isCplx(u) && u.writesFpcc

    // SLOW-ALU producer: line-E SHIFT on the four-stage ALU-EU path. Tracked in the
    // aluSlow* bitmaps (dynamic wakeup), NOT the static scoreboards. A shift writes
    // int + NZVC + (X for non-rotate).
    //
    // BITFIELD USED TO BE IN THIS CLASS and must NOT come back: it now runs on the CPLX
    // cluster, so its dependents are tracked by cplxBusy/cplxNzvcBusy and woken by
    // cplxWakeup/cplxNzvcWakeup. Listing it here as well would park a consumer on an
    // aluSlowWait bit that NOTHING can ever clear -- no ALU EU broadcasts a bit-field
    // slowWakeup any more -- i.e. a silent HANG, not a stale read. The converse (leaving
    // a BITFIELD uop on Cluster.INT while narrowing this) is the symmetric silent bug:
    // the static scoreboards would then treat it as a FAST lat-1 producer.
    //
    // The narrowing lives in `DecOp.isAluSlow`, which this flag is precomputed from on
    // the PUSH path -- deliberately NOT re-derived from `op` here. The IQ select cone is
    // the hottest contested logic in the machine; it reads one flop instead of decoding
    // an op, and that is what keeps `op` removable from IqHot later.
    def isAluSlowProducer(u: IqHot): Bool = u.isAluSlow

    // An LS op that writes NZVC = a dynamic (variable-latency) NZVC producer (a
    // MOVE-to-memory store / RTR CCR-restore). Tracked in lsNzvcBusy (dynamic), NOT
    // sbNzvc (static lat1) — its NZVC completes at the LS pipeline depth, not lat1.
    def isLsNzvcProducer(u: IqHot): Bool = isLs(u) && u.writesNzvc

    // Is srcB a REAL register operand (vs an immediate)? For ALU µops `useImm`
    // means srcB carries an immediate (no register read). For LS µops `imm` is the
    // ADDRESS DISPLACEMENT and srcB is the STORE DATA register — both are live, so
    // useImm must NOT suppress the srcB (data) dependency. Conflating the two would
    // let a store issue before its data producer retired (reading a stale PRF).
    // PACK/UNPK are a second exception: they set useImm=True (adj16 as imm) but ALSO
    // have psrcB = Dy (a real register read). The EU reads rdB.data (s1RdB) directly,
    // bypassing the useImm mux, so psrcB IS a live data dependency.
    // BFINS is a third exception: useImm=True (offset/width packed in imm) but psrcB =
    // Dn2 (the insert source) is a LIVE register read. This stays true now that BITFIELD
    // runs on the CPLX cluster: DivEu's bit-field lane captures the RAW `rdB.data` for
    // exactly this reason (its `s0B` is the useImm mux, which would otherwise substitute
    // the packed offset/width immediate for Dn2). `srcBIsReg` also feeds `cplxDepInit`,
    // so dropping this arm would let a BFINS issue AHEAD of the producer of its insert
    // source. BFRESOLVE is a fourth exception: useImm=True (static offset/width + Do/Dw
    // in imm) but psrcB = width-Dn (Dw form) is likewise live (srcAValid=Do already gates
    // psrcA the normal way).
    //
    // Precompute the entire answer once in IqHot.assignFrom. The stored hot
    // record then needs one live bit instead of repeatedly decoding these
    // qualifiers at every queue slot, including its dynamic-wakeup cone.
    def srcBIsReg(u: IqHot): Bool = u.srcBRead

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
    // The internal select streams now carry only the NARROW record. The wide dispatch
    // payload is fetched from the cold Mem on the far side of the registered stage, so
    // the 16:1 select cone (and the m2sPipe that terminates it) is ~7x narrower.
    val selPorts = Vec.fill(5)(Stream(IqHot()))
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
    val aluReady = B(slots.map(s => s.ready && !s.hot.isBranch && !isLs(s.hot) && !isCplx(s.hot)))
    val brReady  = B(slots.map(s => s.ready &&  s.hot.isBranch))
    val lsFullyReady = B(slots.map(s => s.ready && isLs(s.hot)))
    val storeAddressReady = if (earlyStoreAddress) {
      val dataMask = (BigInt(1) << DynWait.LS_B) | (BigInt(1) << DynWait.CPLX_B) |
        (BigInt(1) << DynWait.SLOW_B)
      B(slots.zipWithIndex.map { case (s, i) =>
        s.sel && s.hot.canEarlyStoreData &&
          (if (i == 0) True else s.triggers(i - 1 downto 0) === 0) &&
          !(s.dynWait & ~B(dataMask, DynWait.width bits)).orR
      })
    } else B(0, slotCount bits)
    val lsReady = lsFullyReady | storeAddressReady
    val cplxReady= B(slots.map(s => s.ready &&  isCplx(s.hot)))
    val hots = Vec(slots.map(_.hot))

    val aluSlowSlots = B(slots.map(_.isAluSlow))
    val allAluCandidates = B((BigInt(1) << slotCount) - 1, slotCount bits)
    val cand0 = aluReady & Mux(aluFastAcceptNextPorts(0), allAluCandidates, aluSlowSlots)
    val cand1 = aluReady & Mux(aluFastAcceptNextPorts(1), allAluCandidates, aluSlowSlots)
    val oh0 = OHMasking.first(cand0)
    val oh1 = OHMasking.first(cand1 & ~oh0)
    val ohB = OHMasking.first(brReady)
    // ---- LS issue is IN PROGRAM ORDER (no MOB / no load-store disambiguation) ----
    // The L1D is write-no-allocate and stores are visible only at commit (SQ drain),
    // so a load disambiguates against older stores ONLY via the SQ-forward — which can
    // see a store ONLY once that store has executed and ALLOCATED its address into the
    // SQ. If a YOUNGER load were allowed to issue ahead of an OLDER store to the same
    // address (because the load's address is ready while the store still waits on its
    // data producer, e.g. the ALU result of a load-op-store RMW), the load would query
    // the SQ before that store allocated, MISS the forward, and read STALE memory (the
    // older store's value never reaches it) — silently corrupting a dependent store's
    // data (the RMW write-back). The architectural regs still match (the load feeds an
    // unchecked T0/T1 temp), so only final memory is wrong: the "dropped store" race.
    //
    // Fix: select the OLDEST LS slot, and only when it is ready. A younger LS µop is
    // NOT issued while an older LS µop is still unready — guaranteeing every older
    // store has allocated into the SQ before any younger load (or store) to the same
    // address disambiguates. Single LS EU + single SQ-drain port make in-order LS
    // issue the natural (and previously assumed) discipline; this just enforces it.
    // OCCUPIED LS slots only (an empty slot's context is garbage and must NOT be
    // mistaken for the oldest LS — that would block real LS issue forever -> deadlock).
    val lsPresent = B(slots.map(s => s.sel && isLs(s.hot)))
    val ohLoldest = OHMasking.first(lsPresent)         // oldest occupied LS slot (ready or not)
    // ---- LS port (3): REGISTERED slot ready via a one-entry skid (2026-09-14) ----
    // See the port-3 pipe in the registered-issue-stage block below for the full
    // design. `lsSkidValid` is the ONLY thing `selPorts(3).ready` reads, so the LS
    // slot's `fire` -- and through it the per-slot `triggers`/scoreboard/compaction
    // clock enables of all 16 slots -- is a function of a flop, never of the LS EU's
    // `issuePort.ready` cone. While the skid holds a uop the LS select is masked:
    // nothing may be selected (the slot's ready is low anyway, this keeps the payload
    // OR in the pipe exclusive) and the skid's own uop is what the pipe forwards.
    val lsSkidValid = RegInit(False)
    lsSkidValid.simPublic()
    // ---- OPTIONAL: let a LOAD pass an older UNREADY LOAD (loadBypassUnreadyLoad) ----
    // The oldest-occupied-LS-only rule above exists for ONE hazard, stated in its own
    // comment: an older STORE must have allocated into the SQ before a younger access to
    // the same address disambiguates. That argument binds only when the blocking op is a
    // STORE. Two LOADS have no hazard between them at all -- no address comparison, no
    // disambiguation, nothing to get wrong -- so a ready load waiting behind an unready
    // load is pure lost overlap.
    //
    // MEASURED on the calibrated Dhrystone kernel (IPC_MEM=l2, board profile): a younger
    // READY load sits behind an older unready LS op for 22,999 of 27,617 cycles, and of
    // the 23,006 blocked cycles only 1,535 have a STORE at the head. So 93% of the
    // blockage is load-behind-load, which this relaxes.
    //
    // WHAT IS NOT RELAXED. A STORE still may not pass ANY older unready LS op: past a
    // store it would be WAW, and past a load it would break the anti-dependence (the
    // load must read the old value). So stores keep the original rule exactly, and only
    // loads gain the bypass -- over older LOADS only.
    val ohLrelaxed = if (!loadBypassUnreadyLoad) ohLoldest else {
      val lsStoreUnready = Vec(slots.map(s =>
        s.sel && isLs(s.hot) && (s.hot.memOp === m68k040.isa.MemOp.STORE) && !s.ready))
      val lsAnyUnready = Vec(slots.map(s => s.sel && isLs(s.hot) && !s.ready))
      // EXCLUSIVE prefix: "some strictly-older slot holds an unready store / LS op".
      val olderUnreadyStore = Vec(Bool(), slotCount)
      val olderUnreadyLs    = Vec(Bool(), slotCount)
      olderUnreadyStore(0) := False
      olderUnreadyLs(0)    := False
      for (i <- 1 until slotCount) {
        olderUnreadyStore(i) := olderUnreadyStore(i - 1) || lsStoreUnready(i - 1)
        olderUnreadyLs(i)    := olderUnreadyLs(i - 1)    || lsAnyUnready(i - 1)
      }
      val eligible = B((0 until slotCount).map { i =>
        val s = slots(i)
        val isStore = s.hot.memOp === m68k040.isa.MemOp.STORE
        s.sel && isLs(s.hot) && Mux(isStore, !olderUnreadyLs(i), !olderUnreadyStore(i))
      })
      OHMasking.first(eligible & lsReady)
    }
    val ohL = (if (loadBypassUnreadyLoad) ohLrelaxed
               else ohLoldest & lsReady) & B(slotCount bits, default -> !lsSkidValid)
    val lsSelectedLateData = (ohL & ~lsFullyReady).orR
    // ---- DIVIDE-FAMILY issue is IN PROGRAM ORDER (DIV / DIVREM only) ----------
    // DIV.L's remainder does not travel on a renamed physical register: the DIV µop
    // writes only the quotient and hands the remainder to its trailing DIVREM crack µop
    // through DivEuPlugin's stash, which the DIVREM moves into Dr. `ohC` picks the
    // oldest READY CPLX slot, not the oldest slot, and a DIVREM has no dependence on
    // its DIV's quotient (Dr and Dq are different registers in every remainder-producing
    // form) -- so it goes ready FIRST whenever the DIV is still waiting on its
    // dividend/divisor producer, e.g. `move.l %d4,%d5 ; divsl.l %d2,%d6:%d5`, the
    // MC68040 ROM's `SCalcsPointer` shape. When the stash was a single anonymous global
    // latch that meant the DIVREM moved a DIFFERENT division's remainder into Dr:
    // measured on real hardware (BUG_calibration_word_misplaced_0d00.md Part 116/117),
    // 2 of 14 remainders wrong, quotients correct 14/14, two divides with IDENTICAL
    // operands returning different remainders, and a boot hang downstream of it.
    //
    // DivEuPlugin's stash is now robId-keyed with a per-robId valid bit (the structure
    // the multiplier's high product already used), so a mis-ordered DIVREM can no longer
    // read another divide's value -- it would find its OWN entry invalid. This mask
    // supplies the other half: it makes that case UNREACHABLE, so the DIVREM never has
    // to wait and needs no pending queue. Mirroring the LS port's in-order discipline
    // immediately above, but restricted to the divide family: only the OLDEST occupied
    // DIV/DIVREM slot may be selected, so a DIVREM cannot overtake its own DIV and no
    // younger DIV can slip between a DIV and its DIVREM. Everything else on the CPLX
    // port (MUL/MULHI, CHK/CMP2/CHK2, the whole FP family) keeps full out-of-order
    // selection, and divides were already serialized against each other by the EU's
    // single-outstanding iterative lane, so the IPC cost is nil.
    //
    // Deadlock-freedom is the LS port's argument verbatim: the oldest occupied
    // divide-family slot is never blocked by this mask, so the family always drains.
    // OCCUPIED slots only -- an empty slot's `hot.op` is stale and must not be mistaken
    // for the oldest divide.
    val divFamPresent = B(slots.map(s => s.sel &&
      s.hot.isDivFam))
    val divFamOldest  = OHMasking.first(divFamPresent)
    val divFamBlocked = divFamPresent & ~divFamOldest  // every divide-family slot but the oldest
    val ohC = OHMasking.first(cplxReady & ~divFamBlocked)

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
    // The static latency-1 wakeup fires at SELECT time, so a selected ALU producer
    // must leave the registered stage exactly one cycle later.  A slow candidate is
    // always accepted; a fast candidate is eligible only when fastAcceptNext says
    // the EU will have no S3 collision next cycle.  The stored per-slot class keeps
    // this guarantee out of the post-MuxOH opcode cone and also lets the sibling port
    // take a skipped fast uop instead of creating a head-of-line bubble.
    /** One-hot payload select WITHOUT SpinalHDL's encode-then-decode (2026-09-13, FMax).
      *
      * `MuxOH(oh, hots)` does not lower to a one-hot mux. SpinalHDL emits
      * `OHToUInt(oh)` -- four index bits, each an OR tree over the 16 slot bits --
      * and then a 16-way `case` on that index. That is ~4 LUT levels, and it sits in
      * the TAIL of the select cone, i.e. directly on the arc the 200 MHz build reports
      * as `lines_0_ways_0_sel_reg -> selPorts_1_rData_*` (19 logic levels).
      *
      * An explicit AND-OR reduce is ~2 levels: a LUT6 absorbs three (data, select)
      * pairs, so 16 slots collapse in one level plus one OR, and the AND fuses into
      * the first level rather than costing an encode and a decode. Same idiom the
      * straddle tables and this file's own `events` already use.
      *
      * IDENTICAL WHERE IT MATTERS, and the one place it differs is unobservable.
      * With exactly one bit set the two forms are equal by construction. With NO bit
      * set `MuxOH` returned element 0 (`OHToUInt(0) == 0`) while this returns all
      * zeroes -- but the payload is qualified by `selPorts(k).valid = oh.orR`, and the
      * downstream `m2sPipe` data register only loads on `fire`, so a payload produced
      * while `oh === 0` is never captured by anything.
      *
      * NOT a retime: no register moves, no cycle is added anywhere. The wakeup-select
      * loop proper (ready -> cand -> OHMasking.first -> fire -> triggers) is untouched;
      * this only shortens the combinational tail hanging off it.
      */
    def ohSelect(oh: Bits): IqHot = {
      val r = IqHot()
      r.assignFromBits(
        hots.zip(oh.asBools).map { case (h, sel) => h.asBits.andMask(sel) }
            .reduceBalancedTree(_ | _))
      r
    }

    selPorts(0).valid   := oh0.orR && !flushSignal
    selPorts(0).payload := ohSelect(oh0)
    selPorts(1).valid   := oh1.orR && !flushSignal
    selPorts(1).payload := ohSelect(oh1)
    selPorts(2).valid   := ohB.orR && !flushSignal
    selPorts(2).payload := ohSelect(ohB)
    selPorts(3).valid   := ohL.orR && !flushSignal
    selPorts(3).payload := ohSelect(ohL)
    selPorts(4).valid   := ohC.orR && !flushSignal
    selPorts(4).payload := ohSelect(ohC)

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
    // Task #139 fix: `m2sPipe(flush = flushSignal)` clears the pipe's internal valid
    // REGISTER on a flush, but that clear only takes effect the FOLLOWING cycle (a
    // Reg update, like any other) -- it does NOT combinationally suppress the pipe's
    // OUTPUT on the SAME cycle the flush pulse fires. If a slot won selection and
    // entered this registered stage on the cycle BEFORE a flush (legitimately, per
    // the pre-flush `!flushSignal` gate on `selPorts` above), its payload is already
    // latched and its `.valid` output still reads True on the flush cycle itself,
    // and if the downstream EU also happens to be `.ready` that exact cycle, the
    // handshake FIRES -- a genuine wrong-path issue reaching the EU on the flush
    // cycle, contradicting this file's own stated intent ("a squashed selection
    // never reaches the EU"). Root-caused via cycle-accurate trace (task #139
    // finding #1, fuzz-campaign-divergence-2026-07-16 memory): a wrong-path STORE
    // issued to the LS EU on the EXACT SAME cycle as a branch-mispredict flush,
    // later allocating a permanently-orphaned entry into the StoreQueue that blocks
    // head-of-line drain and hangs the pipeline. Explicitly AND `!flushSignal` into
    // the FINAL output valid (on top of what m2sPipe's own `flush` already does) so
    // a same-cycle race can never present a stale, pre-flush payload as valid.
    //
    // COLD-PAYLOAD READ POINT. This is the ONE place the wide dispatch record is
    // materialised, and it is on the FAR side of the registered stage: the read address
    // is `piped.payload.robId`, a flop output, so the arc is reg -> LUTRAM -> EU rather
    // than the select cone -> 418-bit MuxOH -> reg it replaces. The external
    // `issuePorts` contract is BIT-IDENTICAL to before (still `Stream[IqContext]`), so
    // no EU plugin changes at all.
    //
    // PARALLELISM IS PRESERVED EXACTLY. Each of the 5 ports has its OWN independent
    // `readAsync` on BOTH banks, so all five can read five DIFFERENT rows in the same
    // cycle -- there is no arbitration, no shared read port, and no added latency on any
    // port. Nothing here serialises anything that was previously concurrent: the ports
    // still pick independently (`oh0/oh1/ohB/ohL/ohC` are unchanged), still fire
    // independently, and now simply carry a 7-bit address instead of a 418-bit payload
    // between the pick and the read. The `readAsync` is combinational, so a µop is still
    // presented to its EU on exactly the same cycle it always was.
    //
    // ═══ LS PORT (3): a REGISTERED slot ready (2026-09-14, routing-congestion plan item
    // 6, half (b)) ═══════════════════════════════════════════════════════════════════
    //
    // For ports 0/1/2/4 the m2sPipe with `collapsBubble = false` passes the EU's ready
    // STRAIGHT THROUGH: `selPorts(k).ready := issuePorts(k).ready`. For the LS EU that
    // ready is the deepest cone in the machine -- `s1Ready <- tReady <- tCanLeave <-
    // normalReqArm <- {xlate.req.ready, the D-cache's probe admission, the completion
    // arbitration ...}` -- and it landed, in ONE cycle, on `selPorts(3).fire`, hence on
    // `slot.fire` / `events` / `issued` and the triggers/scoreboard/compaction clock
    // enables of all 16 slots (3,336 of the 3,856 failing endpoints on the routed
    // 200 MHz build; the D-cache half of that chain is cut in DcachePlugin, this is the
    // IQ half, and it cuts EVERY source of `issuePort.ready`, not only the D-cache one).
    //
    // DESIGN: a one-entry skid (`lsSkidValid`/`lsSkidHot`) in front of the same
    // registered issue stage the other ports have. Occupancy credit, not a delayed
    // valid: `selPorts(3).ready := !lsSkidValid`, a plain flop. When the slot fires and
    // the EU is ready the uop passes straight into the issue register as before (hit
    // path neutral, no added cycle); when the EU is NOT ready the uop parks in the skid,
    // the slot ready drops NEXT cycle, and the skid drains into the issue register the
    // first cycle the EU is ready. The EU's ready still exists -- as the load enable of
    // the issue register and the skid's drain, i.e. ~2x`IqHot` flops, exactly the
    // endpoint set it drove before through the m2sPipe register -- but it no longer
    // reaches the slot array.
    //
    // ORDER AND THROUGHPUT. The skid is strictly older than any selectable slot (it was
    // selected first), and the select is masked while it holds (`ohL` AND `!lsSkidValid`),
    // so LS issue stays in program order: skid, then oldest ready slot. Throughput is
    // that of any 2-deep elastic buffer: a 1-cycle EU stall costs the IQ nothing (the
    // uop it would have refused is absorbed and drains on the recovery cycle), and a
    // longer stall parks exactly one uop and holds the port -- the slot the old design
    // would have held is now freed one cycle earlier, never later.
    //
    // WHY `collapsBubble = false` IS STILL RIGHT FOR THE OTHER PORTS, AND WHY THIS
    // DOES NOT REOPEN ITS HAZARD. That flag's comment above warns that freeing a slot
    // before the EU accepts "grows effective queue depth past slotCount". That IS what
    // a skid does, by one uop, and it is harmless here because every consumer of an LS
    // slot's fire is order-insensitive to WHEN the EU takes the uop: LS results are
    // dynamic-wakeup only (`lsBusy`/`lsNzvcBusy`, cleared at COMPLETION, never a static
    // latency-1 `events` trigger -- an LS pdst never enters `sbInt`, see the push-time
    // `push0IsLs` arm), `count`/`readyReg` only need the slot to be truly empty, and a
    // flush squashes the skid exactly as it squashes the issue register. The one thing
    // that DID key off "the issue register holds the payload that fired last cycle" is
    // the retimed C+1 scoreboard clear (`sbClearFire`); with a skid in front that is no
    // longer true for port 3, so its clear decodes `lsSkidHot`, which by construction
    // holds the LAST FIRED payload whether it parked or passed through.
    //
    // FLUSH. `flushSignal` (the ROB's doFlush pulse OR excActive) clears both the skid
    // and the issue register (later assignment wins over the same-cycle loads), and the
    // issue-port valid is additionally gated by `!flushSignal` exactly as for the other
    // ports (task #139: a payload latched the cycle before a flush must not fire on the
    // flush cycle). `selPorts(3).valid` is already `... && !flushSignal`, so nothing
    // enters the skid on a flush cycle either.
    val lsSkidHot   = Reg(IqHot())
    val lsIssValid  = RegInit(False)
    val lsIssHot    = Reg(IqHot())
    val lsPiped     = Stream(IqHot())
    lsPiped.valid   := lsIssValid
    lsPiped.payload := lsIssHot
    selPorts(3).ready := !lsSkidValid
    // Forward source into the issue register: the skid when it holds (the select is
    // masked then, so `selPorts(3).payload` reads all-zero -- `ohSelect` of an empty
    // one-hot), else the live selection. Folded as an OR so the skid term joins the
    // existing AND-OR reduce rather than adding a mux level behind it.
    val lsFwdValid = selPorts(3).valid || lsSkidValid
    val lsFwdHot   = IqHot()
    lsFwdHot.assignFromBits(selPorts(3).payload.asBits | lsSkidHot.asBits.andMask(lsSkidValid))
    when(lsPiped.ready) {                       // m2sPipe(collapsBubble = false) load rule
      lsIssValid := lsFwdValid
      lsIssHot   := lsFwdHot
      lsSkidValid := False                      // whatever the skid held has moved on
    }
    when(selPorts(3).fire) {
      lsSkidHot := selPorts(3).payload          // always: the last fired payload (sbClear)
      when(!lsPiped.ready) { lsSkidValid := True }
    }
    when(flushSignal) {
      lsSkidValid := False
      lsIssValid  := False
    }
    lateStorePorts.foreach { p =>
      val skidPending = RegInit(False)
      val issuePending = RegInit(False)
      when(lsPiped.ready) {
        issuePending := Mux(lsSkidValid, skidPending, lsSelectedLateData)
      }
      when(selPorts(3).fire) { skidPending := lsSelectedLateData }
      when(flushSignal) { skidPending := False; issuePending := False }
      p.issuePending := issuePending
    }
    GenerationFlags.simulation {
      // The two forward sources are exclusive by the `ohL` mask; a violation here would
      // OR two payloads into the issue register.
      assert(!(lsSkidValid && selPorts(3).valid),
        "IssueQueuePlugin: LS select fired while the port-3 skid holds a uop", FAILURE)
      // LIVENESS (2026-09-15): the skid drains the first cycle the LS EU is ready or a
      // flush lands; a uop parked in it for 20000 cycles means the EU's front never
      // freed -- a no-forward-progress bug, named at the IQ rather than as a timeout.
      val skidHeldCycles = Reg(UInt(16 bits)) init 0
      when(lsSkidValid) { skidHeldCycles := skidHeldCycles + 1 } otherwise { skidHeldCycles := 0 }
      assert(skidHeldCycles < U(20000, 16 bits),
        "IssueQueuePlugin: the LS port-3 skid has held a uop for 20000 cycles -- the LS EU " +
          "never accepted it; no forward progress on LS issue",
        FAILURE)
    }

    val pipedPorts = Seq.tabulate(5) { k =>
      val piped = if (k == 3) lsPiped
                  else selPorts(k).m2sPipe(collapsBubble = false, flush = flushSignal)
      issuePorts(k).valid       := piped.valid && !flushSignal
      issuePorts(k).payload.uop := coldRead(piped.payload)
      issuePorts(k).payload.robId := piped.payload.robId
      piped.ready               := issuePorts(k).ready
      piped
    }
    // FMax (task #219, Fix 2): the static-scoreboard busy-clear is retimed to C+1 and
    // decodes off THESE ALREADY-REGISTERED payloads instead of the live OHMasking/16:1
    // MuxOH select cone. `sbClearFire(k)` is exactly `RegNext(selPorts(k).fire)` (one flop
    // per port). `pipedPorts(k).payload` is the m2sPipe data register, which SpinalHDL
    // loads on `self.ready` -- and `selPorts(k).fire` implies `selPorts(k).ready` -- so on
    // the cycle `sbClearFire(k)` is high, `pipedPorts(k).payload` is guaranteed to be the
    // exact payload that fired the cycle before, whether or not the EU back-pressures
    // (back-pressure only makes the m2sPipe HOLD it). See the clear loop near the bottom
    // of this file, and the mandatory `sb*BusyEff` bypass declared with the scoreboards.
    val sbClearFire = Seq.tabulate(5)(k => RegNext(selPorts(k).fire) init False)

    // Free chosen slots when their SELECT port fires (i.e. when the uop moves into
    // the registered issue stage).  The ALU candidate masks guarantee next-cycle
    // acceptance; LS/CPLX may back-pressure and hold their slot.
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
    //
    // The BRANCH port (port 2) is ALSO a static-latency-1 producer NOW: an RTS/RTR
    // ibranch writes A7 (the postincremented SP) via the branch EU's int write port
    // (same pipeline depth as the ALU EUs). A consumer of that A7 (e.g. `rts` followed
    // by an A7-relative access) carries a static trigger on the branch slot; without
    // including ohB here that trigger would never clear (the branch fires on port 2,
    // not 0/1) and the consumer would hang. A plain branch (no int pdst) generates a
    // harmless no-consumer event. The branch EU's An bypass covers the same-cycle read.
    //
    // Task #141 fix (2nd half): LS (port 3) and CPLX (port 4) MUST be included too, for
    // the same reason as ohB above. Per the push-time logic just above, a static sb*
    // scoreboard position (and its decoded slot trigger) CAN be recorded for a producer
    // that fires on port 3 or 4: LS's own int/NZVC dsts always route to the dynamic
    // lsBusy/lsNzvcBusy bitmaps instead (so ohL is a harmless no-op event source in
    // practice today), but CPLX's NZVC dst (e.g. CMP2/CHK2) has no dynamic-tracker
    // equivalent and goes through plain static sbNzvc -- so a dependent's trigger CAN
    // reference a CPLX-fired slot, and without ohC here that trigger would never clear,
    // hanging the dependent forever (exactly the seed-62 CMP2/CHK2-then-ADDX repro; see
    // fuzz-campaign-divergence-2026-07-16 memory). Included symmetrically with the
    // busy-clear loop above, which now also covers all 5 ports.
    val events = oh0.andMask(selPorts(0).fire) | oh1.andMask(selPorts(1).fire) |
                 ohB.andMask(selPorts(2).fire) | ohL.andMask(selPorts(3).fire) |
                 ohC.andMask(selPorts(4).fire)

    // ---- Depend-on-READ trigger init for the two newly-pushed slots ----
    // Slot0 lands at priority `slot0Prio` (lines.last.ways(0)), slot1 at
    // `slot1Prio` (== slot0Prio+1). A producer dependency is ALWAYS on an older
    // (lower-index) slot, so it fits in the dependent's trigger width.
    //
    // A push always coincides with a compaction shift. Decode stationary producer
    // positions using the NEXT epoch, since freshly-written triggers take effect
    // after that shift. Intra-push (slot1 reads slot0's dst)
    // references slot0's final position (slot0Prio) directly, no shift offset.
    val slot0Prio = (lineCount - 1) * wayCount     // 14
    val slot1Prio = slot0Prio + 1                  // 15

    // Build the trigger Bits for a pushed slot of the given priority width.
    def trigInit(uop: IqHot, width: Int): Bits = {
      val t = B(0, width bits)
      def dep(busy: Bits, positions: Vec[UInt], physreg: UInt, reads: Bool): Unit = {
        val producerSlot = slotAtEpoch(positions(physreg), nextPositionEpoch)
        when(reads && busy(physreg)) {
          // bit index < this slot's priority (older), so in range.
          t(producerSlot) := True
        }
      }
      // `sb*BusyEff`, NOT the raw `sb*.busy`: the retimed (C+1) scoreboard clear MUST be
      // bypassed in here or a uop pushed exactly one cycle after its producer's issue
      // latches a trigger that can never clear -> deadlock. See the bypass declaration.
      dep(sbIntBusyEff,  sbInt.physToPosition,  uop.psrcA, uop.psrcAValid)
      dep(sbIntBusyEff,  sbInt.physToPosition,  uop.psrcB, srcBIsReg(uop))
      dep(sbIntBusyEff,  sbInt.physToPosition,  uop.psrcC, uop.psrcCValid)
      dep(sbNzvcBusyEff, sbNzvc.physToPosition, uop.pNzvcSrc, uop.readsNzvc)
      dep(sbXBusyEff,    sbX.physToPosition,    uop.pXSrc, uop.readsX)
      dep(sbFpBusyEff,   sbFp.physToPosition,   uop.pFpSrcA,  uop.psrcAFpValid)
      dep(sbFpBusyEff,   sbFp.physToPosition,   uop.pFpSrcB,  uop.psrcBFpValid)
      dep(sbFpccBusyEff, sbFpcc.physToPosition, uop.pFpccSrc, uop.readsFpcc)
      t
    }

    // The push-side projections. EVERY push-time predicate and dependency computation in
    // this file reads only hot fields -- which is not a coincidence but the definition of
    // the split: the push side and the slot side run the SAME `trigInit` / `*DepInit` /
    // `is*Producer` code, so anything one needs the other needs. Projecting here (rather
    // than keeping a second RenamedUop-typed copy of each helper) is what makes that
    // sharing structural instead of a duplicated pair that could drift.
    val pushUop0 = pushPort.payload(0).uop      // wide record: cold-Mem write data + sim taps only
    val pushUop1 = pushPort.payload(1).uop
    val pushHot0 = IqHot(); pushHot0.assignFrom(pushPort.payload(0), way = False, earlyAutoStoreAddress = earlyAutoStoreAddress)
    val pushHot1 = IqHot(); pushHot1.assignFrom(pushPort.payload(1), way = True, earlyAutoStoreAddress = earlyAutoStoreAddress)
    val push0IsAluSlow = isAluSlowProducer(pushHot0)
    val push1IsAluSlow = isAluSlowProducer(pushHot1)
    // debug-only (task #141 X-flag/scoreboard leak investigation)
    pushPort.valid.simPublic(); pushSlot1Port.simPublic()
    pushPort.payload(0).robId.simPublic(); pushUop0.pc.simPublic()
    pushUop0.writesX.simPublic(); pushUop0.writesNzvc.simPublic(); pushUop0.pdstValid.simPublic()
    pushUop0.pXDst.simPublic(); pushUop0.pdst.simPublic(); pushUop0.pNzvcDst.simPublic()
    pushPort.payload(1).robId.simPublic(); pushUop1.pc.simPublic()
    pushUop1.writesX.simPublic(); pushUop1.writesNzvc.simPublic(); pushUop1.pdstValid.simPublic()
    pushUop1.pXDst.simPublic(); pushUop1.pdst.simPublic(); pushUop1.pNzvcDst.simPublic()
    // debug-only (task #144): op + srcB physical register at IQ-push time, keyed
    // directly by the same robId/pc already tapped above (avoids needing to
    // correlate rename-cycle timing to a robId separately).
    pushUop0.op.simPublic(); pushUop0.psrcB.simPublic(); pushUop0.psrcBValid.simPublic()
    pushUop1.op.simPublic(); pushUop1.psrcB.simPublic(); pushUop1.psrcBValid.simPublic()
    val trig0 = trigInit(pushHot0, slot0Prio + 1)
    val trig1 = trigInit(pushHot1, slot1Prio + 1)

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
    // PER SOURCE (DynWait.LS_A / LS_B / LS_C), deliberately NOT OR-ed together: each
    // source's verdict lands in its own slot bit, which is what makes the old per-slot
    // `lsStillDep` re-evaluation (an lsBusy index mux per source, in all 16 slots, every
    // cycle) unnecessary -- the multi-LS-source guard is now structural.
    def lsDepInit(uop: IqHot): Seq[Bool] = Seq(
      uop.psrcAValid && stillBusy(uop.psrcA),
      srcBIsReg(uop)  && stillBusy(uop.psrcB),
      uop.psrcCValid && stillBusy(uop.psrcC))
    val lsDep0 = lsDepInit(pushHot0)
    val lsDep1Base = lsDepInit(pushHot1)
    // Intra-push: slot1 reads slot0's dst and slot0 is an LS INT producer -> slot1 waits
    // (dynamic lsWait). An LS int producer = a LOAD (-> a reg) OR a stkPush STORE (whose
    // int dst is the predecremented A7, e.g. LINK's push). BOTH complete via the LS port
    // + broadcast lsWakeup (compWakes covers load AND stkPush), so BOTH are lsBusy-tracked
    // (push0IsLs = isLs, store-inclusive) and BOTH must suppress the static int trigger.
    val s0IsLsIntProd = isLs(pushHot0) && pushHot0.pdstValid
    val lsDep1 = Seq(
      lsDep1Base(0) || (s0IsLsIntProd && pushHot1.psrcAValid && (pushHot1.psrcA === pushHot0.pdst)),
      lsDep1Base(1) || (s0IsLsIntProd && srcBIsReg(pushHot1) && (pushHot1.psrcB === pushHot0.pdst)),
      lsDep1Base(2) || (s0IsLsIntProd && pushHot1.psrcCValid && (pushHot1.psrcC === pushHot0.pdst)))

    // Push-time LS-NZVC dependency: does this uop READ an NZVC physreg produced by an
    // in-flight LS NZVC writer (a MOVE-to-memory store)? Same same-cycle-wakeup guard as
    // lsDep (the store may complete the exact push cycle -> read lsNzvcBusy minus a
    // matching lsNzvcWakeup). Intra-push (slot1 reads slot0's NZVC) handled below.
    def stillNzvcBusy(p: UInt): Bool =
      lsNzvcBusy(p) && !(lsNzvcWakeupPort.valid && lsNzvcWakeupPort.payload === p)
    def lsNzvcDepInit(uop: IqHot): Bool = uop.readsNzvc && stillNzvcBusy(uop.pNzvcSrc)
    val lsNzvcDep0 = lsNzvcDepInit(pushHot0)
    val lsNzvcDep1Base = lsNzvcDepInit(pushHot1)
    // Intra-push: slot1 reads slot0's NZVC and slot0 is an LS NZVC producer -> slot1 waits.
    val s0IsLsNzvc = isLsNzvcProducer(pushHot0)
    val lsNzvcDep1 = lsNzvcDep1Base ||
      (s0IsLsNzvc && pushHot1.readsNzvc && (pushHot1.pNzvcSrc === pushHot0.pNzvcDst))

    // Push-time CPLX (DivEu) dependency: same mechanism as LS but on cplxBusy /
    // cplxWakeup. A consumer of an in-flight DIV result latches cplxWait.
    def stillCplxBusy(p: UInt): Bool =
      cplxBusy(p) && !(cplxWakeupPort.valid && cplxWakeupPort.payload === p)
    // Per source (DynWait.CPLX_A / CPLX_B / CPLX_C) -- same rationale as lsDepInit.
    def cplxDepInit(uop: IqHot): Seq[Bool] = Seq(
      uop.psrcAValid && stillCplxBusy(uop.psrcA),
      srcBIsReg(uop)  && stillCplxBusy(uop.psrcB),
      uop.psrcCValid && stillCplxBusy(uop.psrcC))
    val cplxDep0 = cplxDepInit(pushHot0)
    val cplxDep1Base = cplxDepInit(pushHot1)
    // Intra-push: slot1 reads slot0's dst and slot0 is a DIV producer -> slot1 waits.
    val s0IsCplxProd = isCplxProducer(pushHot0)
    val cplxDep1 = Seq(
      cplxDep1Base(0) || (s0IsCplxProd && pushHot1.psrcAValid && (pushHot1.psrcA === pushHot0.pdst)),
      cplxDep1Base(1) || (s0IsCplxProd && srcBIsReg(pushHot1) && (pushHot1.psrcB === pushHot0.pdst)),
      cplxDep1Base(2) || (s0IsCplxProd && pushHot1.psrcCValid && (pushHot1.psrcC === pushHot0.pdst)))

    // Push-time CPLX-NZVC (DivEu flag-writer) dependency (task #167): same mechanism as
    // lsNzvcDep but on cplxNzvcBusy / cplxNzvcWakeup. A flag-reader of an in-flight CPLX
    // op (DIV/MUL/CHK/CMP2/CHK2) latches cplxNzvcWait instead of the static sbNzvc trigger.
    def stillCplxNzvcBusy(p: UInt): Bool =
      cplxNzvcBusy(p) && !(cplxNzvcWakeupPort.valid && cplxNzvcWakeupPort.payload === p)
    def cplxNzvcDepInit(uop: IqHot): Bool = uop.readsNzvc && stillCplxNzvcBusy(uop.pNzvcSrc)
    val cplxNzvcDep0 = cplxNzvcDepInit(pushHot0)
    val cplxNzvcDep1Base = cplxNzvcDepInit(pushHot1)
    // Intra-push: slot1 reads slot0's NZVC and slot0 is a CPLX flag-writer -> slot1 waits.
    val s0IsCplxNzvc = isCplxNzvcProducer(pushHot0)
    val cplxNzvcDep1 = cplxNzvcDep1Base ||
      (s0IsCplxNzvc && pushHot1.readsNzvc && (pushHot1.pNzvcSrc === pushHot0.pNzvcDst))

    // Push-time CPLX FP-DATA dependency: same mechanism as cplxDep but on cplxFpBusy /
    // cplxFpWakeup, over the TWO FP sources. `stillCplxFpBusy` excludes a producer that
    // completes THIS exact cycle (the lost-wakeup race lsDep's own comment documents).
    def stillCplxFpBusy(p: UInt): Bool =
      cplxFpBusy(p) && !(cplxFpWakeupPort.valid && cplxFpWakeupPort.payload === p)
    // Per source (DynWait.FP_A / FP_B): this is the pair the old single `cplxFpWait` bit
    // had to cover with the `cplxFpRemaining` guard (IqFpSpec's dyadic-FADD case).
    def cplxFpDepInit(uop: IqHot): Seq[Bool] = Seq(
      uop.psrcAFpValid && stillCplxFpBusy(uop.pFpSrcA),
      uop.psrcBFpValid && stillCplxFpBusy(uop.pFpSrcB))
    val cplxFpDep0 = cplxFpDepInit(pushHot0)
    val cplxFpDep1Base = cplxFpDepInit(pushHot1)
    val s0IsCplxFp = isCplxFpProducer(pushHot0)
    val cplxFpDep1 = Seq(
      cplxFpDep1Base(0) || (s0IsCplxFp && pushHot1.psrcAFpValid && (pushHot1.pFpSrcA === pushHot0.pFpDst)),
      cplxFpDep1Base(1) || (s0IsCplxFp && pushHot1.psrcBFpValid && (pushHot1.pFpSrcB === pushHot0.pFpDst)))

    // Push-time CPLX FPCC dependency: single source, so no multi-source guard is needed
    // (mirrors cplxNzvcDep exactly).
    def stillCplxFpccBusy(p: UInt): Bool =
      cplxFpccBusy(p) && !(cplxFpccWakeupPort.valid && cplxFpccWakeupPort.payload === p)
    def cplxFpccDepInit(uop: IqHot): Bool = uop.readsFpcc && stillCplxFpccBusy(uop.pFpccSrc)
    val cplxFpccDep0 = cplxFpccDepInit(pushHot0)
    val cplxFpccDep1Base = cplxFpccDepInit(pushHot1)
    val s0IsCplxFpcc = isCplxFpccProducer(pushHot0)
    val cplxFpccDep1 = cplxFpccDep1Base ||
      (s0IsCplxFpcc && pushHot1.readsFpcc && (pushHot1.pFpccSrc === pushHot0.pFpccDst))

    // Push-time SLOW-ALU (shift) dependency: same mechanism as LS/CPLX but across the
    // shift's THREE output classes (int / NZVC / X) and BOTH ALU-EU wakeup ports. A
    // consumer reading any in-flight shift output latches aluSlowWait. `still*` guards
    // the same-cycle wakeup race (a wakeup this cycle clears the busy this cycle).
    def slowWokeInt(p: UInt): Bool  = aluSlowWakeupPorts.map(w => w.valid && w.payload.pdstValid && w.payload.pdst === p).orR
    def slowWokeNzvc(p: UInt): Bool = aluSlowWakeupPorts.map(w => w.valid && w.payload.nzvcValid && w.payload.pNzvcDst === p).orR
    def slowWokeX(p: UInt): Bool    = aluSlowWakeupPorts.map(w => w.valid && w.payload.xValid && w.payload.pXDst === p).orR
    def stillAluSlowInt(p: UInt): Bool  = aluSlowIntBusy(p)  && !slowWokeInt(p)
    def stillAluSlowNzvc(p: UInt): Bool = aluSlowNzvcBusy(p) && !slowWokeNzvc(p)
    def stillAluSlowX(p: UInt): Bool    = aluSlowXBusy(p)    && !slowWokeX(p)
    // Per source, in DynWait order SLOW_A / SLOW_B / SLOW_C / SLOW_NZVC / SLOW_X. The
    // shift writes three reg classes at once, so the old single `aluSlowWait` bit needed
    // the widest `*Remaining` guard of all (three 50:1 int lookups plus two 16:1 flag
    // lookups per slot); splitting by source removes it outright.
    def aluSlowDepInit(uop: IqHot): Seq[Bool] = Seq(
      uop.psrcAValid && stillAluSlowInt(uop.psrcA),
      srcBIsReg(uop)  && stillAluSlowInt(uop.psrcB),
      uop.psrcCValid && stillAluSlowInt(uop.psrcC),
      uop.readsNzvc   && stillAluSlowNzvc(uop.pNzvcSrc),
      uop.readsX      && stillAluSlowX(uop.pXSrc))
    val aluSlowDep0 = aluSlowDepInit(pushHot0)
    val aluSlowDep1Base = aluSlowDepInit(pushHot1)
    // Intra-push: slot1 reads slot0's (a shift's) int/NZVC/X dst -> slot1 waits.
    val s0IsAluSlowProd = isAluSlowProducer(pushHot0)
    val aluSlowDep1 = Seq(
      aluSlowDep1Base(0) || (s0IsAluSlowProd && pushHot0.pdstValid && pushHot1.psrcAValid && (pushHot1.psrcA === pushHot0.pdst)),
      aluSlowDep1Base(1) || (s0IsAluSlowProd && pushHot0.pdstValid && srcBIsReg(pushHot1) && (pushHot1.psrcB === pushHot0.pdst)),
      aluSlowDep1Base(2) || (s0IsAluSlowProd && pushHot0.pdstValid && pushHot1.psrcCValid && (pushHot1.psrcC === pushHot0.pdst)),
      aluSlowDep1Base(3) || (s0IsAluSlowProd && pushHot0.writesNzvc && pushHot1.readsNzvc && (pushHot1.pNzvcSrc === pushHot0.pNzvcDst)),
      aluSlowDep1Base(4) || (s0IsAluSlowProd && pushHot0.writesX    && pushHot1.readsX    && (pushHot1.pXSrc === pushHot0.pXDst)))
    // ---- Assemble the two pushed slots' per-source dynamic-wait vectors -------------
    // One bit per (source x dynamic class), in DynWait order. This is the ONLY place a
    // `*Busy` bitmap is indexed on behalf of a NEW slot -- twice per cycle (way 0 / way 1)
    // instead of the 16 per-slot re-evaluations the `*Remaining` guards used to do.
    def dynVec(ls: Seq[Bool], cplx: Seq[Bool], slow: Seq[Bool],
               lsNzvc: Bool, cplxNzvc: Bool, fp: Seq[Bool], fpcc: Bool): Bits = {
      val d = Bits(DynWait.width bits)
      d(DynWait.LS_A)      := ls(0)
      d(DynWait.LS_B)      := ls(1)
      d(DynWait.LS_C)      := ls(2)
      d(DynWait.CPLX_A)    := cplx(0)
      d(DynWait.CPLX_B)    := cplx(1)
      d(DynWait.CPLX_C)    := cplx(2)
      d(DynWait.SLOW_A)    := slow(0)
      d(DynWait.SLOW_B)    := slow(1)
      d(DynWait.SLOW_C)    := slow(2)
      d(DynWait.LS_NZVC)   := lsNzvc
      d(DynWait.CPLX_NZVC) := cplxNzvc
      d(DynWait.SLOW_NZVC) := slow(3)
      d(DynWait.SLOW_X)    := slow(4)
      d(DynWait.FP_A)      := fp(0)
      d(DynWait.FP_B)      := fp(1)
      d(DynWait.FPCC)      := fpcc
      d
    }
    val dynDep0 = dynVec(lsDep0, cplxDep0, aluSlowDep0, lsNzvcDep0, cplxNzvcDep0, cplxFpDep0, cplxFpccDep0)
    val dynDep1 = dynVec(lsDep1, cplxDep1, aluSlowDep1, lsNzvcDep1, cplxNzvcDep1, cplxFpDep1, cplxFpccDep1)

    // Intra-push: slot1 reads a physreg that slot0 (pushed same cycle) writes.
    // slot0 ends at slot0Prio; set slot1's trigger bit there.
    //
    // The STATIC (latency-1) int trigger must be SUPPRESSED when slot0 is an LS
    // load: an LS load is variable-latency and produces NO static issue-event
    // (`events` covers only the ALU ports), so a static trigger bit on it would
    // never clear and hang the consumer. That dependency is instead carried by
    // `lsDep1` (the dynamic lsWait, cleared by lsWakeup). This is exactly the
    // cracked LOAD->temp + op-reading-temp pair when both land in one push group.
    // Suppress the static trigger for an LS load, a DIV producer, OR a slow-ALU (shift)
    // producer (all latency>1, tracked dynamically via lsWait/cplxWait/aluSlowWait, not
    // static latency-1 triggers). For a shift this covers ALL THREE classes (int + NZVC
    // + X), since the shift writes all of them at S3 (the aluSlowDep1 carries them).
    // LS-int producer (a LOAD or a stkPush STORE's predecremented-A7 side-effect) issues on
    // the LS port (no static int event) -> its int dep is carried dynamically (lsWait), not
    // the static scoreboard [feat/link-unlk: broadened from LOAD-only s0IsLsLoad].
    val s0WritesInt  = pushHot0.pdstValid && !s0IsLsIntProd && !s0IsCplxProd && !s0IsAluSlowProd
    // Likewise suppress the static NZVC trigger for an LS NZVC producer (a MOVE-to-mem
    // store): it issues on the LS port (no static event) -> its NZVC dep is carried by
    // lsNzvcDep1 [feat/bit-ops: the latent LS-NZVC deadlock fix]. Task #167: also suppress
    // it for a CPLX flag-writer (DIV/MUL/CHK/CMP2/CHK2) -> carried by cplxNzvcDep1 instead
    // (a static latency-1 trigger is wrong for a variable-latency CPLX producer).
    val s0WritesNzvc = pushHot0.writesNzvc && !s0IsAluSlowProd && !s0IsLsNzvc && !s0IsCplxNzvc
    val s0WritesX    = pushHot0.writesX    && !s0IsAluSlowProd
    when(s0WritesInt  && pushHot1.psrcAValid && pushHot1.psrcA === pushHot0.pdst)              { trig1(slot0Prio) := True }
    when(s0WritesInt  && srcBIsReg(pushHot1) && pushHot1.psrcB === pushHot0.pdst) { trig1(slot0Prio) := True }
    when(s0WritesInt  && pushHot1.psrcCValid && pushHot1.psrcC === pushHot0.pdst) { trig1(slot0Prio) := True }
    when(s0WritesNzvc && pushHot1.readsNzvc && pushHot1.pNzvcSrc === pushHot0.pNzvcDst)        { trig1(slot0Prio) := True }
    when(s0WritesX    && pushHot1.readsX    && pushHot1.pXSrc === pushHot0.pXDst)              { trig1(slot0Prio) := True }
    // A CPLX FP producer is variable-latency -> its intra-push dependency is carried by
    // cplxFpDep1 (dynamic), NOT a static latency-1 trigger, which would never clear.
    val s0WritesFp   = pushHot0.pFpDstValid && !s0IsCplxFp
    val s0WritesFpcc = pushHot0.writesFpcc  && !s0IsCplxFpcc
    when(s0WritesFp   && pushHot1.psrcAFpValid && pushHot1.pFpSrcA === pushHot0.pFpDst)   { trig1(slot0Prio) := True }
    when(s0WritesFp   && pushHot1.psrcBFpValid && pushHot1.pFpSrcB === pushHot0.pFpDst)   { trig1(slot0Prio) := True }
    when(s0WritesFpcc && pushHot1.readsFpcc    && pushHot1.pFpccSrc === pushHot0.pFpccDst){ trig1(slot0Prio) := True }

    // ---- Compaction on push.fire (shift toward index 0, insert at last line) ----
    when(pushPort.fire) {
      for (lineId <- 0 to lineCount - 2) {
        for (way <- 0 until wayCount) {
          val wSrc = lines(lineId + 1).ways(way)
          val wDst = lines(lineId).ways(way)
          wDst.hot      := wSrc.hot
          wDst.isAluSlow := wSrc.isAluSlow
          wDst.triggers := (wSrc.triggers >> wayCount).resized
          wDst.sel      := wSrc.selComb
          // The whole per-source dynamic-wait vector shifts with the slot, exactly as the
          // seven per-class bits used to. Source = the REGISTER (`wSrc.dynWait`), matching
          // the old `wDst.lsWait := wSrc.lsWait`; the wakeup-clear block below then
          // overrides individual bits of the DESTINATION wire, same precedence as before.
          wDst.dynWaitNext := wSrc.dynWait
        }
      }
      // New uops into the last line. Only the NARROW record lands in the slot; the wide
      // half is written to the cold Mem below, in this same `pushPort.fire` cycle.
      val wDst0 = lines.last.ways(0)
      val wDst1 = lines.last.ways(1)
      wDst0.hot      := pushHot0
      wDst0.isAluSlow := push0IsAluSlow
      wDst0.triggers := trig0
      wDst0.sel      := True
      wDst0.dynWaitNext := dynDep0
      wDst1.hot      := pushHot1
      wDst1.isAluSlow := push1IsAluSlow
      wDst1.triggers := trig1
      wDst1.sel      := pushSlot1Port
      wDst1.dynWaitNext := dynDep1
    }

    // ---- Cold-payload writes (the wide half of the same push) ----------------------
    // ONE write port per bank, by construction: way 0 only ever writes bank 0 and way 1
    // only ever writes bank 1, each at its own robId. That is what keeps these as plain
    // distributed RAMs (MultiPortWritesSymplifier's `writes.size <= 1` early return) with
    // no LVT/XOR bank machinery, and it is why the banking is by WAY rather than by robId
    // parity -- see the coldWay0/coldWay1 declaration for the full argument.
    //
    // Written under exactly the same conditions as the slot's `sel`: way 0 on every
    // `pushPort.fire`, way 1 only when `pushSlot1Port` also holds. A slot whose `sel` is
    // never set therefore never has its row read, so an unwritten row is unreachable.
    coldWay0.write(
      address = pushPort.payload(0).robId,
      data    = pushUop0,
      enable  = pushPort.fire)
    coldWay1.write(
      address = pushPort.payload(1).robId,
      data    = pushUop1,
      enable  = pushPort.fire && pushSlot1Port)

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

    // ---- DYNAMIC WAKEUP: clear the matching PER-SOURCE wait bits -------------------
    // This one block replaces the seven per-class clear blocks (lsWakeMatch,
    // lsNzvcWakeMatch, cplxWakeMatch, cplxNzvcWakeMatch, cplxFpWakeMatch,
    // cplxFpccWakeMatch, aluSlowWakeMatch) and, with them, every `*Remaining` /
    // `lsStillDep` re-evaluation.
    //
    // WHAT WENT AWAY AND WHY IT IS SAFE. A per-CLASS bit covered every source of that
    // class at once, so the old code could not clear it on the first matching wakeup --
    // a second source of the same class might still be in flight (a stale-PRF read, not
    // a hang). It therefore re-derived, PER SLOT and PER CYCLE, whether any source of
    // that class was still busy: `lsStillDep` / `cplxRemaining` / `cplxFpRemaining` /
    // `aluSlowRemaining`, each indexing the class's `*Busy` bitmap (50-deep for the int
    // classes, 16-deep for the flag/FP ones) once per source. With one bit per source
    // that question is answered by the bit vector itself, so the guard is deleted
    // outright: a bit clears on ITS OWN source's wakeup and nothing else.
    //
    // CYCLE-EXACT EQUIVALENCE (this is an area transform, not a scheduling change).
    // Fix a slot and a class K.
    //   - At push, old K = OR over K's sources of "source is K-in-flight"; new K_s is
    //     that same per-source term, from the same `still*` helpers. So old K is set iff
    //     some new K_s is set.
    //   - Old K cleared in the first cycle C where (a) a K wakeup matched one of the
    //     slot's sources and (b) no K source was still K-busy after C's wakeup. (b) holds
    //     exactly when every K source's producer has completed by C, i.e. when every
    //     new K_s has cleared by C. Conversely if every K_s has cleared by C then the
    //     last of them cleared on its own wakeup at some C' <= C, and at C' condition (a)
    //     held (that source matched) and (b) held (no K source outstanding after it) --
    //     so old K cleared at C' too. The two therefore go to zero in the SAME cycle, and
    //     `dynWaitAny` (the registered OR of the whole vector) reaches zero in the same
    //     cycle the old seven-way AND of `!*Wait` did. Issue order, age ordering and
    //     wakeup latency are untouched.
    //   - A source cannot acquire a NEW in-flight producer after its consumer is pushed
    //     (rename hands the next writer a different physreg), which is what makes the
    //     push-time snapshot and the old live `*Remaining` read agree in the first place.
    //
    // SHIFT DISCIPLINE unchanged: on a compaction cycle the matched slot moves down by
    // wayCount, so the clear is applied to slot i-wayCount; this block sits AFTER the
    // compaction block so it overrides the shifted value, exactly as before.
    //
    // The clears are pure TAG COMPARES against the wakeup payloads -- the same comparator
    // set the old `*WakeMatch` terms already built (12 six-bit and 9 four-bit compares per
    // slot), so the comparator cost is a wash and the `*Busy` index muxes are pure saving.
    def slowIntWoke(p: UInt): Bool =
      aluSlowWakeupPorts.map(w => w.valid && w.payload.pdstValid && (p === w.payload.pdst)).orR
    def slowNzvcWoke(p: UInt): Bool =
      aluSlowWakeupPorts.map(w => w.valid && w.payload.nzvcValid && (p === w.payload.pNzvcDst)).orR
    def slowXWoke(p: UInt): Bool =
      aluSlowWakeupPorts.map(w => w.valid && w.payload.xValid && (p === w.payload.pXDst)).orR

    /** Per-slot clear mask: bit b set => that source's producer completed this cycle.
      * Gated on `s.sel` exactly as every old `*WakeMatch` term was. */
    val dynClear = Vec(slots.map { s =>
      val u = s.hot
      val c = Bits(DynWait.width bits)
      val lsW   = lsWakeupPort.valid
      val cpW   = cplxWakeupPort.valid
      c(DynWait.LS_A)      := lsW && u.psrcAValid && (u.psrcA === lsWakeupPort.payload)
      c(DynWait.LS_B)      := lsW && srcBIsReg(u) && (u.psrcB === lsWakeupPort.payload)
      c(DynWait.LS_C)      := lsW && u.psrcCValid && (u.psrcC === lsWakeupPort.payload)
      c(DynWait.CPLX_A)    := cpW && u.psrcAValid && (u.psrcA === cplxWakeupPort.payload)
      c(DynWait.CPLX_B)    := cpW && srcBIsReg(u) && (u.psrcB === cplxWakeupPort.payload)
      c(DynWait.CPLX_C)    := cpW && u.psrcCValid && (u.psrcC === cplxWakeupPort.payload)
      c(DynWait.SLOW_A)    := u.psrcAValid && slowIntWoke(u.psrcA)
      c(DynWait.SLOW_B)    := srcBIsReg(u) && slowIntWoke(u.psrcB)
      c(DynWait.SLOW_C)    := u.psrcCValid && slowIntWoke(u.psrcC)
      c(DynWait.LS_NZVC)   := lsNzvcWakeupPort.valid && u.readsNzvc &&
                              (u.pNzvcSrc === lsNzvcWakeupPort.payload)
      c(DynWait.CPLX_NZVC) := cplxNzvcWakeupPort.valid && u.readsNzvc &&
                              (u.pNzvcSrc === cplxNzvcWakeupPort.payload)
      c(DynWait.SLOW_NZVC) := u.readsNzvc && slowNzvcWoke(u.pNzvcSrc)
      c(DynWait.SLOW_X)    := u.readsX    && slowXWoke(u.pXSrc)
      c(DynWait.FP_A)      := cplxFpWakeupPort.valid && u.psrcAFpValid &&
                              (u.pFpSrcA === cplxFpWakeupPort.payload)
      c(DynWait.FP_B)      := cplxFpWakeupPort.valid && u.psrcBFpValid &&
                              (u.pFpSrcB === cplxFpWakeupPort.payload)
      c(DynWait.FPCC)      := cplxFpccWakeupPort.valid && u.readsFpcc &&
                              (u.pFpccSrc === cplxFpccWakeupPort.payload)
      c.andMask(s.sel)
    })
    // Per-bit conditional clears (NOT `next := next & ~clear`): reading a combinational
    // signal one is also driving is a loop in SpinalHDL, and the `when(...) { := False }`
    // form is the same last-assignment-wins override the old per-class blocks used.
    for (i <- 0 until slotCount; b <- 0 until DynWait.width) {
      when(dynClear(i)(b)) {
        // On a non-compaction cycle clear slot i; on compaction, slot i-wayCount (the two
        // oldest slots are discarded by the shift, so i < wayCount has no destination).
        when(pushPort.fire) { if (i >= wayCount) slots(i - wayCount).dynWaitNext(b) := False }
          .otherwise        { slots(i).dynWaitNext(b) := False }
      }
    }

    // ---- Scoreboard maintenance ----
    // Compaction advances positionEpoch only. Each position row is written
    // solely when a new producer for that physical register is inserted.
    // An LS LOAD producer is tracked in lsBusy (dynamic wakeup), NOT sbInt (static
    // latency-1). All other int producers go in sbInt as before.
    val push0IsLs = isLs(pushHot0)
    val push1IsLs = isLs(pushHot1)
    val push0IsCplxProd = isCplxProducer(pushHot0)
    val push1IsCplxProd = isCplxProducer(pushHot1)
    // A slow-ALU (shift) producer's int + NZVC + X dsts go in the aluSlow* bitmaps
    // (dynamic S3 wakeup), NOT the static sb* scoreboards.
    // An LS NZVC producer (MOVE-to-mem store) goes in lsNzvcBusy (dynamic wakeup), NOT
    // sbNzvc (static lat1) — it issues on the LS port and produces no static event.
    val push0IsLsNzvc = isLsNzvcProducer(pushHot0)
    val push1IsLsNzvc = isLsNzvcProducer(pushHot1)
    push0IsLsNzvc.simPublic(); push1IsLsNzvc.simPublic()  // debug-only (task #139 seed=3 CCR investigation)
    // A CPLX flag-writer (DIV/MUL/CHK/CMP2/CHK2) goes in cplxNzvcBusy (dynamic wakeup),
    // NOT sbNzvc (static lat1) — task #167: it is variable-latency (a multi-cycle DIV/MUL
    // or a near-immediate CHK2), and the static scoreboard's busy bit is force-cleared at
    // ISSUE time (task #141's clear loop below), not real completion.
    val push0IsCplxNzvc = isCplxNzvcProducer(pushHot0)
    val push1IsCplxNzvc = isCplxNzvcProducer(pushHot1)
    // A CPLX FP-data / FPCC producer goes in cplxFpBusy / cplxFpccBusy (dynamic wakeup),
    // NOT sbFp/sbFpcc (static lat1) — same variable-latency reasoning as CPLX-NZVC above.
    val push0IsCplxFp   = isCplxFpProducer(pushHot0);   val push1IsCplxFp   = isCplxFpProducer(pushHot1)
    val push0IsCplxFpcc = isCplxFpccProducer(pushHot0); val push1IsCplxFpcc = isCplxFpccProducer(pushHot1)
    when(pushPort.fire) {
      when(pushHot0.pdstValid) {
        when(push0IsLs)       { lsBusy(pushHot0.pdst) := True }
          .elsewhen(push0IsCplxProd) { cplxBusy(pushHot0.pdst) := True }
          .elsewhen(push0IsAluSlow)  { aluSlowIntBusy(pushHot0.pdst) := True }
          .otherwise    { sbInt.busy(pushHot0.pdst) := True; sbInt.insert(pushHot0.pdst, 0) }
      }
      when(pushHot0.writesNzvc) {
        when(push0IsAluSlow) { aluSlowNzvcBusy(pushHot0.pNzvcDst) := True }
          .elsewhen(push0IsLsNzvc) { lsNzvcBusy(pushHot0.pNzvcDst) := True }
          .elsewhen(push0IsCplxNzvc) { cplxNzvcBusy(pushHot0.pNzvcDst) := True }
          .otherwise { sbNzvc.busy(pushHot0.pNzvcDst) := True; sbNzvc.insert(pushHot0.pNzvcDst, 0) }
      }
      when(pushHot0.writesX) {
        when(push0IsAluSlow) { aluSlowXBusy(pushHot0.pXDst) := True }
          .otherwise { sbX.busy(pushHot0.pXDst) := True; sbX.insert(pushHot0.pXDst, 0) }
      }
      when(pushHot0.pFpDstValid) {
        when(push0IsCplxFp) { cplxFpBusy(pushHot0.pFpDst) := True }
          .otherwise { sbFp.busy(pushHot0.pFpDst) := True; sbFp.insert(pushHot0.pFpDst, 0) }
      }
      when(pushHot0.writesFpcc) {
        when(push0IsCplxFpcc) { cplxFpccBusy(pushHot0.pFpccDst) := True }
          .otherwise { sbFpcc.busy(pushHot0.pFpccDst) := True; sbFpcc.insert(pushHot0.pFpccDst, 0) }
      }
      when(pushSlot1Port) {
        when(pushHot1.pdstValid) {
          when(push1IsLs)       { lsBusy(pushHot1.pdst) := True }
            .elsewhen(push1IsCplxProd) { cplxBusy(pushHot1.pdst) := True }
            .elsewhen(push1IsAluSlow)  { aluSlowIntBusy(pushHot1.pdst) := True }
            .otherwise    { sbInt.busy(pushHot1.pdst) := True; sbInt.insert(pushHot1.pdst, 1) }
        }
        when(pushHot1.writesNzvc) {
          when(push1IsAluSlow) { aluSlowNzvcBusy(pushHot1.pNzvcDst) := True }
            .elsewhen(push1IsLsNzvc) { lsNzvcBusy(pushHot1.pNzvcDst) := True }
            .elsewhen(push1IsCplxNzvc) { cplxNzvcBusy(pushHot1.pNzvcDst) := True }
            .otherwise { sbNzvc.busy(pushHot1.pNzvcDst) := True; sbNzvc.insert(pushHot1.pNzvcDst, 1) }
        }
        when(pushHot1.writesX) {
          when(push1IsAluSlow) { aluSlowXBusy(pushHot1.pXDst) := True }
            .otherwise { sbX.busy(pushHot1.pXDst) := True; sbX.insert(pushHot1.pXDst, 1) }
        }
        when(pushHot1.pFpDstValid) {
          when(push1IsCplxFp) { cplxFpBusy(pushHot1.pFpDst) := True }
            .otherwise { sbFp.busy(pushHot1.pFpDst) := True; sbFp.insert(pushHot1.pFpDst, 1) }
        }
        when(pushHot1.writesFpcc) {
          when(push1IsCplxFpcc) { cplxFpccBusy(pushHot1.pFpccDst) := True }
            .otherwise { sbFpcc.busy(pushHot1.pFpccDst) := True; sbFpcc.insert(pushHot1.pFpccDst, 1) }
        }
      }
    }
    // On issue, clear busy for the issued producer's dst(s) so later pushes do not
    // depend on an already-result-available producer. A SLOW (shift) producer is in the
    // aluSlow* bitmaps, NOT sb*, so its sb*-clear here is a harmless no-op; aluSlow* is
    // cleared at the S3 wakeup (below), NOT at issue.
    //
    // Task #141 fix: this loop MUST cover every issue port (selPorts has 5: ALU0/ALU1/
    // Branch/LS/CPLX), not just `wayCount` (=2, the DISPATCH width -- an unrelated
    // constant that happened to also be 2, masking the bug for a long time). A static
    // (sbInt/sbNzvc/sbX) producer that fires on port 2/3/4 never had its busy bit
    // cleared here, so any later consumer's trigger referencing that slot could latch
    // permanently (the trigger only clears via THIS SAME clearing event, which never
    // came). LS's own int/NZVC producers are unaffected in practice (they route through
    // the separate `lsBusy`/`lsNzvcBusy` dynamic bitmaps instead, never touching sb* in
    // the first place — confirmed harmless no-op for that case), and the branch port
    // already had a hand-patched partial fix below (now redundant, left in place as a
    // harmless idempotent duplicate). The concretely CONFIRMED victim: CMP2/CHK2 (CPLX,
    // port 4) writes NZVC via the plain static `sbNzvc` path (no CPLX-specific dynamic
    // NZVC tracker exists, unlike its int dst which correctly uses `cplxBusy`) -- its
    // busy bit never cleared on issue, permanently blocking any later NZVC reader (e.g.
    // a following ADDX/ADD/SUB reading the flags CMP2/CHK2 just wrote). Root-caused via
    // live trace, task #139/#141, seed 62 (see fuzz-campaign-divergence-2026-07-16
    // memory); repro: repros/fuzz-seed62-cmp2-hang.s.
    //
    // FMAX (netlist-grounded, 2026-08-08): this gate is deliberately `fire` ONLY --
    // it must NOT re-test `isAluSlowProducer(ctx.uop)`. A previous `&& !slowFire`
    // term here was measured (post-route, `xcku5p-ffvb676-2` @4.000ns, checkpoint
    // `3cba17f`) to consume 5 of the 6 inputs of this gate's LUT6 --
    //   when_IssueQueuePlugin_l936_4 = selPorts_4_fire && !(op===SHIFT || op===BITFIELD)
    // -- which is the SINGLE mechanism that dragged the 6-bit `op` field's MuxOH out
    // of the select cone and INTO the scoreboard-clear cone, making this family the
    // design's WNS holder (9 of the 10 worst paths, -1.699ns). A `set_disable_timing`
    // what-if on exactly that arc measured the family at -1.518ns. Zero latency, zero
    // IPC, strictly less logic. (Grounding report: "FMax grounding: IssueQueuePlugin
    // sb{Int,X,Nzvc}_busy", §2.1/§3/§8 Fix 1.)
    //
    // WHY THE TERM IS DEAD WEIGHT (re-derived from the push side above, NOT taken on
    // faith): a slow-ALU producer NEVER sets ANY sb* bit, because every push-side
    // branch that can write sb* tests `isAluSlow` FIRST and routes to the aluSlow*
    // bitmaps instead --
    //   pdstValid  (:876-881): isLs -> isCplxProd -> isAluSlow -> otherwise sbInt
    //   writesNzvc (:882-887): isAluSlow FIRST -> ... -> otherwise sbNzvc
    //   writesX    (:888-891): isAluSlow FIRST -> otherwise sbX
    // (and slot1's identical chains at :892-909; these six sites plus the flush at
    // the bottom are the ONLY writers of sb*.busy in the design). So for a slow
    // producer the clear below targets a bit its own push left at 0, and under the
    // ALREADY-RELIED-UPON one-producer-per-physreg invariant (:148-159) no OTHER
    // in-flight producer owns that bit either -- the clear is a genuine no-op. This
    // introduces NO new invariant: it leans on exactly the one `physToPosition` and the
    // push/clear intra-cycle ordering already require.
    //
    // Consistency check (the decisive one): the LS, CPLX-int, LS-NZVC and CPLX-NZVC
    // classes ALSO route away from sb* on push, and this loop has ALWAYS cleared sb*
    // for them with NO guard at all (see the paragraph above, ":922-924"). If the
    // "clearing a bit my own push did not set is a harmless no-op" argument were
    // unsound, the design would already be broken for those four far more common
    // classes. `!slowFire` was the lone asymmetric guard; dropping it makes SHIFT
    // consistent with them rather than special. (The measured gate quoted above still
    // names BITFIELD because that is what the netlist held at the time; BITFIELD has
    // since moved to the CPLX cluster, which only shrinks the cone further.)
    //
    // FMAX (task #219, Fix 2 -- netlist-grounded against
    // `synth/archive/866437c_fmax_fanout_fix_decode_fetch/fullcore_slack_matrix.rpt`,
    // Design State: Routed): this loop used to read `selPorts(k).payload`, i.e. the LIVE
    // `MuxOH(oh_k, contexts)` 16:1 select cone, and `selPorts(k).fire`, whose `ready` term
    // is the cross-plugin D-cache -> LS-EU -> IQ ready chain. That made
    // `DcachePlugin_logic_fsm_stateReg -> IssueQueuePlugin_logic_sb{Int,X,Fp}_busy_reg[*]/D`
    // 12 of the 100 worst routed endpoints (-1.263 .. -1.229ns). RETIMED to C+1: decode the
    // clear off `pipedPorts(k).payload` (the m2sPipe register the same fire loaded) gated by
    // `sbClearFire(k) == RegNext(selPorts(k).fire)`. The live select cone now terminates at
    // one flop per port instead of driving the whole 5-port x 5-scoreboard decode tree.
    //
    // Zero architectural change, because of the MANDATORY `sb*BusyEff` bypass declared with
    // the scoreboards: the C+1 clear is folded back into push-time `trigInit` in the SAME
    // cycle it is being written, so a uop pushed one cycle after its producer's issue sees
    // the producer as done rather than latching a trigger on a slot that has already been
    // freed and can never fire `events` again. WITHOUT that bypass this retime is a silent
    // deadlock (mutation-proven, task #219).
    //
    // Position in the file is deliberately unchanged (still AFTER the push-recording
    // block), so the intra-cycle write ordering versus a same-cycle push is exactly what it
    // was. Under the one-producer-per-physreg invariant no push at C+1 can target a physreg
    // whose producer issued at C anyway: that producer has not even completed yet, so its
    // pdst cannot have been freed and re-allocated.
    //
    // COLD-SPLIT NOTE: `pipedPorts(k).payload` is now the NARROW record, and every dst
    // field this loop decodes is deliberately kept in it (see IqHot). So this clear still
    // reads a plain m2sPipe FLOP, exactly as task #219 left it -- the cold Mem's LUTRAM
    // read is NOT in this cone. Routing the dsts through the Mem instead would have put a
    // distributed-RAM level back into the `sb*_busy` path this fix exists to shorten.
    // PORT 3 (2026-09-14): with the registered-ready skid in front of the LS issue
    // register, `pipedPorts(3).payload` is no longer guaranteed to be the payload that
    // fired last cycle (a parked uop sits in the skid while the register still holds the
    // older one). `lsSkidHot` is written on EVERY port-3 fire, parked or passed-through,
    // so it is exactly "the payload that fired last cycle" on every `sbClearFire(3)`
    // cycle. Still a plain flop; the sb*_busy cone is unchanged.
    for (k <- selPorts.indices) {
      val ctx = if (k == 3) lsSkidHot else pipedPorts(k).payload
      when(sbClearFire(k)) {
        when(ctx.pdstValid)   { sbInt.busy(ctx.pdst)      := False; sbIntClr(ctx.pdst)      := True }
        when(ctx.writesNzvc)  { sbNzvc.busy(ctx.pNzvcDst) := False; sbNzvcClr(ctx.pNzvcDst) := True }
        when(ctx.writesX)     { sbX.busy(ctx.pXDst)       := False; sbXClr(ctx.pXDst)       := True }
        when(ctx.pFpDstValid) { sbFp.busy(ctx.pFpDst)     := False; sbFpClr(ctx.pFpDst)     := True }
        when(ctx.writesFpcc)  { sbFpcc.busy(ctx.pFpccDst) := False; sbFpccClr(ctx.pFpccDst) := True }
      }
    }
    // The BRANCH port (port 2) also clears its int pdst busy: an RTS/RTR ibranch is a
    // latency-1 int producer (A7) in sbInt, so a later push must not record a static
    // trigger on its already-issued (result-available) slot. (A branch writes no flags.)
    // NOTE: redundant with the widened loop above (now covers port 2 too); left in
    // place as a harmless idempotent duplicate rather than risk touching more lines
    // than necessary for this fix.
    // (Retimed to C+1 with the loop above -- keeping it on the LIVE select cone would put
    // the branch port's copy of the exact arc back into the netlist.)
    {
      val bctx = pipedPorts(2).payload
      when(sbClearFire(2) && bctx.pdstValid) {
        sbInt.busy(bctx.pdst) := False; sbIntClr(bctx.pdst) := True
      }
    }

    // ---- Dynamic LS wakeup: clear lsBusy for the completed load's pdst ----
    // Placed AFTER the push-recording so a same-cycle re-allocation of that physreg
    // (a new LS load pushed onto the just-freed pdst) wins (stays busy).
    when(lsWakeupPort.valid) { lsBusy(lsWakeupPort.payload) := False }
    when(pushPort.fire) {
      when(pushHot0.pdstValid && push0IsLs) { lsBusy(pushHot0.pdst) := True }
      when(pushSlot1Port && pushHot1.pdstValid && push1IsLs) { lsBusy(pushHot1.pdst) := True }
    }
    // ---- Dynamic LS-NZVC wakeup: clear lsNzvcBusy for the completed store's pNzvcDst.
    // Same priority discipline as lsBusy (a same-cycle re-allocation by a fresh push wins).
    when(lsNzvcWakeupPort.valid) { lsNzvcBusy(lsNzvcWakeupPort.payload) := False }
    when(pushPort.fire) {
      when(pushHot0.writesNzvc && push0IsLsNzvc) { lsNzvcBusy(pushHot0.pNzvcDst) := True }
      when(pushSlot1Port && pushHot1.writesNzvc && push1IsLsNzvc) { lsNzvcBusy(pushHot1.pNzvcDst) := True }
    }
    // ---- Dynamic CPLX (DivEu) wakeup: clear cplxBusy for the completed DIV's pdst ----
    // Same priority discipline as lsBusy (a same-cycle re-allocation wins).
    when(cplxWakeupPort.valid) { cplxBusy(cplxWakeupPort.payload) := False }
    when(pushPort.fire) {
      when(pushHot0.pdstValid && push0IsCplxProd) { cplxBusy(pushHot0.pdst) := True }
      when(pushSlot1Port && pushHot1.pdstValid && push1IsCplxProd) { cplxBusy(pushHot1.pdst) := True }
    }
    // ---- Dynamic CPLX-NZVC wakeup (task #167): clear cplxNzvcBusy for the completed
    // flag-writer's pNzvcDst. Same priority discipline (a same-cycle re-allocation wins).
    when(cplxNzvcWakeupPort.valid) { cplxNzvcBusy(cplxNzvcWakeupPort.payload) := False }
    when(pushPort.fire) {
      when(pushHot0.writesNzvc && push0IsCplxNzvc) { cplxNzvcBusy(pushHot0.pNzvcDst) := True }
      when(pushSlot1Port && pushHot1.writesNzvc && push1IsCplxNzvc) { cplxNzvcBusy(pushHot1.pNzvcDst) := True }
    }
    // ---- Dynamic CPLX FP-DATA / FPCC wakeup: clear cplxFpBusy / cplxFpccBusy for the
    // completed FP op's dst(s). Same priority discipline (a same-cycle re-allocation by a
    // freshly pushed FP producer wins). ----
    when(cplxFpWakeupPort.valid) { cplxFpBusy(cplxFpWakeupPort.payload) := False }
    when(pushPort.fire) {
      when(pushHot0.pFpDstValid && push0IsCplxFp) { cplxFpBusy(pushHot0.pFpDst) := True }
      when(pushSlot1Port && pushHot1.pFpDstValid && push1IsCplxFp) { cplxFpBusy(pushHot1.pFpDst) := True }
    }
    when(cplxFpccWakeupPort.valid) { cplxFpccBusy(cplxFpccWakeupPort.payload) := False }
    when(pushPort.fire) {
      when(pushHot0.writesFpcc && push0IsCplxFpcc) { cplxFpccBusy(pushHot0.pFpccDst) := True }
      when(pushSlot1Port && pushHot1.writesFpcc && push1IsCplxFpcc) { cplxFpccBusy(pushHot1.pFpccDst) := True }
    }
    // ---- Dynamic SLOW-ALU (shift) wakeup: clear the aluSlow* bitmaps for the completed
    // shift's int/NZVC/X dsts. Same priority discipline (a same-cycle re-allocation by a
    // newly-pushed shift wins). ----
    aluSlowWakeupPorts.foreach { aw =>
      when(aw.valid) {
        when(aw.payload.pdstValid) { aluSlowIntBusy(aw.payload.pdst)  := False }
        when(aw.payload.nzvcValid) { aluSlowNzvcBusy(aw.payload.pNzvcDst) := False }
        when(aw.payload.xValid)    { aluSlowXBusy(aw.payload.pXDst)    := False }
      }
    }
    when(pushPort.fire) {
      when(pushHot0.pdstValid  && push0IsAluSlow) { aluSlowIntBusy(pushHot0.pdst)  := True }
      when(pushHot0.writesNzvc && push0IsAluSlow) { aluSlowNzvcBusy(pushHot0.pNzvcDst) := True }
      when(pushHot0.writesX    && push0IsAluSlow) { aluSlowXBusy(pushHot0.pXDst)    := True }
      when(pushSlot1Port) {
        when(pushHot1.pdstValid  && push1IsAluSlow) { aluSlowIntBusy(pushHot1.pdst)  := True }
        when(pushHot1.writesNzvc && push1IsAluSlow) { aluSlowNzvcBusy(pushHot1.pNzvcDst) := True }
        when(pushHot1.writesX    && push1IsAluSlow) { aluSlowXBusy(pushHot1.pXDst)    := True }
      }
    }

    count := count + pushed - issued

    // Next-cycle push.ready: if compacting this cycle, line 1 becomes line 0 next
    // cycle (use line1Ready); else line0Ready. line0/line1 emptiness is sampled
    // combinationally from sel BEFORE this cycle's compaction writes take effect.
    readyReg := Mux(pushPort.fire, line1Ready, line0Ready)

    // ---- TRIPWIRE: the cold split must be INVISIBLE ----------------------------------
    // A `GenerationFlags.simulation` shadow reproduces the DELETED structure bit for bit
    // -- the whole `IqContext` carried in the compacting slot array, same uniform shift,
    // same insert at the last line -- and asserts the new derivation against it at the two
    // places a divergence could actually hurt:
    //
    //   (1) EVERY OCCUPIED SLOT, EVERY CYCLE: the narrow record must be exactly the
    //       projection of the wide shadow. Catches any compaction/insert asymmetry
    //       between the two structures.
    //   (2) EVERY DISPATCHED µop: the cold Mem read delivered to the EU must equal what
    //       the deleted 16:1 `MuxOH` over the full context would have delivered. This is
    //       the one that matters -- it is the direct, continuous check on Mem ADDRESSING,
    //       on the bank-by-way scheme, on the no-RAW claim (a row read the cycle after it
    //       was written), and on the row-stability claim across EU back-pressure (a held
    //       m2sPipe re-reads the same address for as many cycles as the EU stalls, and the
    //       shadow holds the value it captured, so a row overwritten underneath a stalled
    //       port fires this assert).
    //
    // Zero synthesis cost, and active in every simulation this project runs -- so the
    // aliasing argument is checked continuously by the whole regression corpus, not only
    // by the directed tests. Like the ROB's `fs*` shadow, these vals are null in a
    // synth/GenVerilog build and must never be referenced outside a simulation block.
    val fsCtx = GenerationFlags.simulation { Vec.fill(slotCount)(Reg(IqContext())) }
    val fsWay = GenerationFlags.simulation { Vec.fill(slotCount)(RegInit(False)) }
    GenerationFlags.simulation {
      when(pushPort.fire) {
        for (lineId <- 0 to lineCount - 2; way <- 0 until wayCount) {
          val src = (lineId + 1) * wayCount + way
          val dst = lineId * wayCount + way
          fsCtx(dst) := fsCtx(src); fsWay(dst) := fsWay(src)
        }
        fsCtx(slot0Prio) := pushPort.payload(0); fsWay(slot0Prio) := False
        fsCtx(slot1Prio) := pushPort.payload(1); fsWay(slot1Prio) := True
      }
      for (i <- 0 until slotCount) {
        val proj = IqHot(); proj.assignFrom(fsCtx(i), fsWay(i), earlyAutoStoreAddress)
        when(slots(i).sel) {
          assert(slots(i).hot === proj,
            s"IQ cold-split: slot $i narrow record diverged from the full-context shadow")
        }
      }
      // `rData` in SpinalHDL's m2sPipe loads on `self.ready`, so a shadow register with
      // the SAME enable mirrors it cycle for cycle, back-pressure included.
      val ohOf = Seq(oh0, oh1, ohB, ohL, ohC)
      for (k <- 0 until 5) {
        // PORT 3 (2026-09-14): the LS port has a one-entry skid in front of its issue
        // register (see `lsSkidValid`), so its shadow mirrors that structure: a skid
        // shadow captured on every port-3 fire (exactly as `lsSkidHot` is), and an issue
        // shadow loaded on `lsPiped.ready` from the skid shadow while the skid holds,
        // else from the live select -- the same source rule the real pipe applies.
        val fsPiped = if (k == 3) {
          val fsSkid = RegNextWhen(MuxOH(ohL, fsCtx), selPorts(3).fire)
          RegNextWhen(Mux(lsSkidValid, fsSkid, MuxOH(ohL, fsCtx)), lsPiped.ready)
        } else RegNextWhen(MuxOH(ohOf(k), fsCtx), selPorts(k).ready)
        when(pipedPorts(k).valid) {
          assert(issuePorts(k).payload === fsPiped,
            s"IQ cold-split: port $k dispatch payload diverged from the full-context shadow")
        }
      }
    }

    // ---- Flush ----
    when(flushSignal) {
      lines.foreach(_.ways.foreach { w =>
        w.sel      := False
        // Trigger bits are payload of the occupied slot, not occupancy state.
        // All consumers qualify them with sel, and both insertion and compaction
        // overwrite the complete mask alongside that slot's new sel/hot record.
        // Clearing them here only broadcasts ROB flush into every triangular
        // dependency-mask flop. Keep the same-cycle issue gates and scoreboard
        // clears below: those DO carry architectural/producer ownership.
      })
      count := 0
      sbInt.busy  := 0
      sbNzvc.busy := 0
      sbX.busy    := 0
      sbFp.busy   := 0
      sbFpcc.busy := 0
      lsBusy      := 0
      lsNzvcBusy  := 0
      cplxBusy    := 0
      cplxNzvcBusy := 0
      cplxFpBusy   := 0
      cplxFpccBusy := 0
      aluSlowIntBusy  := 0
      aluSlowNzvcBusy := 0
      aluSlowXBusy    := 0
      // After flush line 0 is empty next cycle, so push.ready may re-assert.
      readyReg := True
    }
  }
}
