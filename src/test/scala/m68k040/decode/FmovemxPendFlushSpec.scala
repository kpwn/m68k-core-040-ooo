package m68k040.decode

import m68k040.{M68kParams, VerilatorTest}
import m68k040.cache.{IcachePlugin, IcacheSim}
import m68k040.core.ParamPlugin
import m68k040.frontend.FetchAlignPlugin
import m68k040.mmu.IdentityTranslationPlugin
import m68k040.services.DecodeUopService
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.database.Database
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}

/** Flush-ordering gate for the slot-1 macro STASH registers in `DecodeStage`.
  *
  * `DecodeStage` keeps five "a slot-1 macro was deferred" markers: `stashValid`,
  * `movemPendValid`, `ucPendValid`, `movepPendValid` and `fmovemxPendValid`. Each is SET
  * from inside several textually-late `when` arms (the normal fed-consume arm plus the
  * four FSM-entry blocks), and `fed.valid` is still HIGH on a `pipeFlush` cycle
  * (`PipeStage` clears its `valid` register AT the edge) — so every one of those arms can
  * fire on the very cycle the frontend is being squashed.
  *
  * In SpinalHDL the LAST `:=` in source order wins, so a marker is only really cleared by
  * a flush when its `when(pipeFlush){ := False }` is textually AFTER every setter. That is
  * exactly what the "A1 FIX" block at the end of the Area does — for four of the five
  * markers. `fmovemxPendValid` (added later, for the slot-1 FMOVEM.X entry) is NOT in that
  * block; its only flush-clear sits next to its declaration, textually BEFORE all five of
  * its setters.
  *
  * Consequence: a flush landing on the cycle a slot-1 FMOVEM.X is stashed leaves
  * `fmovemxPendValid` SET with the wrong-path packet still in `fmovemxPendPkt`. The next
  * cycle `fmovemxBegin` fires from that pend (every other FSM flag WAS cleared by the
  * flush) and the FMOVEM.X macro is emitted into the CORRECT-path instruction stream — a
  * phantom commit of a squashed instruction (real memory traffic / FP register writes at a
  * wrong-path EA, carrying a wrong-path pc/nextPc on the kept uop).
  *
  * The gate is posture-free and does not depend on hitting one exact cycle: it runs a real
  * IcachePlugin+FetchAlignPlugin+DecodeStage frontend over a stream that repeatedly puts
  * the macro in slot 1, pulses `pipeFlush` for single cycles on a fixed schedule that
  * walks every phase, and after EVERY flush cycle asserts that all five markers are clear.
  * The MOVEM image is the control (that marker IS in the A1 FIX block); the FMOVEM.X image
  * is the experiment. Coincidence counters make non-vacuity explicit.
  */
class FmovemxPendFlushSpec extends AnyFunSuite {

  /** Test-only: drives `DecodeStage.pipeFlush` from a port and exports the five slot-1
    * stash markers plus the detectors the experiment targets. */
  class FlushProbePlugin(dec: DecodeStage) extends FiberPlugin {
    val logic = during build new Area {
      val flush = in(Bool())

      // detectors (combinational, true on the cycle the stash arm would fire)
      val slot1IsFmovemx = out(Bool()); slot1IsFmovemx := dec.logic.slot1IsFmovemx
      val slot1IsMovem   = out(Bool()); slot1IsMovem   := dec.logic.slot1IsMovem

      // the five deferred-slot1 markers
      val fmovemxPend = out(Bool()); fmovemxPend := dec.logic.fmovemxPendValid
      val movemPend   = out(Bool()); movemPend   := dec.logic.movemPendValid
      val ucPend      = out(Bool()); ucPend      := dec.logic.ucPendValid
      val movepPend   = out(Bool()); movepPend   := dec.logic.movepPendValid
      val stashV      = out(Bool()); stashV      := dec.logic.stashValid

      // FSM activity, to show the phantom macro actually starts from the surviving pend
      val fmovemxActive = out(Bool()); fmovemxActive := dec.logic.fmovemxActive
      val fmovemxBegin  = out(Bool()); fmovemxBegin  := dec.logic.fmovemxBegin
      val flushEcho     = out(Bool()); flushEcho     := dec.logic.pipeFlush

      // Drive pipeFlush LAST: `dec.logic.*` above forces DecodeStage's own build block to
      // complete first, so this `:=` is appended AFTER DecodeStage's `pipeFlush := False`
      // default and therefore wins (SpinalHDL resolves multiple drivers by statement
      // order). Assigning it before touching `dec.logic` silently loses to that default.
      dec.logic.pipeFlush := flush
    }
  }

  class Dut extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val ic   = new IcachePlugin
    val fa   = new FetchAlignPlugin
    val dec  = new DecodeStage
    val sink = new UopSinkPlugin
    val probe = new FlushProbePlugin(dec)
    db.on {
      host.asHostOf(Seq[FiberPlugin](
        new ParamPlugin(M68kParams()), new IdentityTranslationPlugin,
        ic, fa, dec, sink, probe))
    }
  }

  private val NOP       = 0x4e71
  // F210 D080 = FMOVEM.X (A0),FP0 (load direction, static list) — per this repo's own
  // fpu_fmovem_x_an_indirect.s, the single most common FMOVEM.X EA in the Q700 ROM.
  private val FMOVEMX_0 = 0xf210
  private val FMOVEMX_1 = 0xd080
  // MOVEM.L (A0)+,<mask> — the A1-FIX-covered sibling marker, used as the control.
  private val MOVEM_0   = 0x4cd8
  private val MOVEM_1   = 0x0003

  /** NOP (1 word -> slot0) followed by the 2-word macro (-> slot1), repeated on an
    * 8-word pitch so the macro is always followed by plain instructions. */
  private def writePairs(img: Array[Int], wordOff: Int, m0: Int, m1: Int, copies: Int): Unit =
    for (k <- 0 until copies) {
      val off = wordOff + k * 8
      img(off + 0) = NOP
      img(off + 1) = m0
      img(off + 2) = m1
    }

  private case class Result(flushes: Int, coincidences: Int, violations: Int,
                            firstViolationCycle: Int, phantomStarts: Int, detectCycles: Int)

  test("a pipeFlush on the slot-1 stash cycle must clear every deferred-macro marker",
       VerilatorTest) {
    // ONE image, ONE memory attach (two forked AXI responders on the same bus corrupt
    // each other's responses). The FMOVEM.X region and the MOVEM region live at different
    // offsets of the same image.
    val img = Array.fill(0x1000)(NOP)
    val FX_OFF = 0x000
    val MV_OFF = 0x400
    writePairs(img, FX_OFF, FMOVEMX_0, FMOVEMX_1, 64)
    writePairs(img, MV_OFF, MOVEM_0, MOVEM_1, 64)

    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      val base = 0x8000L

      dut.sink.logic.uopsOut.ready #= true
      dut.fa.logic.resume.valid    #= false
      dut.fa.logic.redirect.valid  #= false
      dut.probe.logic.flush        #= false
      IcacheSim.attachMemoryWithWords(dut.ic.logic.axi, cd, base, img.toSeq)
      cd.waitSampling(5)

      // Sanity: the test's flush port must actually reach DecodeStage.pipeFlush. A drive
      // from a sibling plugin only wins if it is appended AFTER DecodeStage's own
      // `pipeFlush := False` default (SpinalHDL resolves multiple drivers by statement
      // order), which is why the probe reads `dec.logic.*` first.
      dut.probe.logic.flush #= true
      sleep(1)
      assert(dut.probe.logic.flushEcho.toBoolean,
        "the test's flush port does not reach DecodeStage.pipeFlush (drive order)")
      dut.probe.logic.flush #= false
      sleep(1)
      assert(!dut.probe.logic.flushEcho.toBoolean)
      cd.waitSampling(2)

      /** Run from `startPc` for `cycles`, pulsing `pipeFlush` for exactly the cycles on
        * which the macro is sitting in slot 1 of `fed` (i.e. the cycles the slot-1 stash
        * arm can fire). One cycle later, the deferred-slot1 marker MUST be clear: a flush
        * squashes `fed`, so nothing it was carrying may stay pending. */
      def run(startPc: Long, detect: () => Boolean, marker: () => Boolean,
              cycles: Int): Result = {
        dut.probe.logic.flush #= false
        dut.fa.logic.redirect.valid   #= true
        dut.fa.logic.redirect.payload #= startPc
        cd.waitSampling()
        dut.fa.logic.redirect.valid #= false

        // `detectCycles` separates "the flush never landed on a stash cycle" from "the
        // stash never happened at all" in the reported non-vacuity numbers.
        var flushes, coincidences, violations, phantomStarts, detectCycles = 0
        var firstViolationCycle = -1
        var flushedLastCycle = false
        for (c <- 0 until cycles) {
          cd.waitSampling()
          sleep(1)                    // let this cycle's combinational values settle
          if (flushedLastCycle) {
            // exactly one cycle after a flush edge: the marker must be clear
            if (marker()) {
              violations += 1
              if (firstViolationCycle < 0) firstViolationCycle = c
              if (dut.probe.logic.fmovemxActive.toBoolean ||
                  dut.probe.logic.fmovemxBegin.toBoolean) phantomStarts += 1
            }
          }
          val d = detect()
          if (d) detectCycles += 1
          // flush exactly on the stash-arm cycles (warm-up excluded)
          val doFlush = d && c > 15
          dut.probe.logic.flush #= doFlush
          if (doFlush) { flushes += 1; coincidences += 1 }
          flushedLastCycle = doFlush
        }
        dut.probe.logic.flush #= false
        Result(flushes, coincidences, violations, firstViolationCycle, phantomStarts,
               detectCycles)
      }

      // ── control: movemPendValid (covered by the A1 FIX late-clear block) ──────────
      val mv = run(base + 2 * MV_OFF,
        () => dut.probe.logic.slot1IsMovem.toBoolean,
        () => dut.probe.logic.movemPend.toBoolean,
        cycles = 1200)
      info(s"MOVEM control : slot1-in-fed cycles=${mv.detectCycles} flushes=${mv.flushes} " +
           s"coincidences=${mv.coincidences} survivingPend=${mv.violations}")
      assert(mv.coincidences > 0,
        "vacuous control: no pipeFlush ever landed on a cycle with a slot-1 MOVEM in `fed`")
      assert(mv.violations == 0,
        s"control broke: movemPendValid survived ${mv.violations} coincident pipeFlush " +
        "cycles — the A1 FIX late-clear block is no longer authoritative")

      // ── experiment: fmovemxPendValid (NOT in the A1 FIX block) ───────────────────
      val fx = run(base + 2 * FX_OFF,
        () => dut.probe.logic.slot1IsFmovemx.toBoolean,
        () => dut.probe.logic.fmovemxPend.toBoolean,
        cycles = 1200)
      info(s"FMOVEM.X exp  : slot1-in-fed cycles=${fx.detectCycles} flushes=${fx.flushes} " +
           s"coincidences=${fx.coincidences} " +
           s"survivingPend=${fx.violations} phantomMacroStarts=${fx.phantomStarts} " +
           s"firstAtCycle=${fx.firstViolationCycle}")
      assert(fx.coincidences > 0,
        "vacuous experiment: no pipeFlush ever landed on a cycle with a slot-1 FMOVEM.X " +
        "in `fed`, so nothing was tested")
      assert(fx.violations == 0,
        s"fmovemxPendValid SURVIVED ${fx.violations} coincident pipeFlush cycles " +
        s"(first at cycle ${fx.firstViolationCycle}; the macro FSM then started from it " +
        s"${fx.phantomStarts} time(s)). The squashed slot-1 FMOVEM.X packet is still " +
        "pending and is emitted into the correct-path stream — a phantom commit. Its " +
        "only flush-clear is textually BEFORE all five of its setters, and it is missing " +
        "from the authoritative late `when(pipeFlush)` block that covers stashValid / " +
        "movemPendValid / ucPendValid / movepPendValid.")
    }
  }
}
