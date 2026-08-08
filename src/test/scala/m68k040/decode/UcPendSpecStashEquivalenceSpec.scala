package m68k040.decode

import m68k040.{M68kParams, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.mmu.IdentityTranslationPlugin
import m68k040.cache.{IcachePlugin, IcacheSim}
import m68k040.frontend.{DecodePacket, FetchAlignPlugin}
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

import scala.util.Random

/** FMax "Lever U1" equivalence gate — the load-bearing correctness proof for
  * `DecodeStage.scala`'s `ucPendSpecReg`
  * (`docs/superpowers/specs/2026-08-08-fmax-leveru1-ucpendpkt-reuse-design.md`,
  * `docs/superpowers/plans/2026-08-08-fmax-leveru1-ucpendpkt-reuse-plan.md`).
  *
  * The lever replaces
  * {{{
  *   val ucPendSpec = OperationDecoder.decode(ucPendPkt.words(0))   // OLD: a 2nd decoder cone
  * }}}
  * with a read of a register captured, in the SAME `when` arms that write `ucPendPkt`, from
  * `fed.payload.specs(1).spec` — a value the frontend already computed and registered one
  * stage earlier. The claim is that the two are **provably identical, bit for bit**, not
  * merely similar. That claim has exactly two halves, and this spec proves both:
  *
  *  1. **Value identity** (`test 1`, exhaustive over the whole 16-bit opword domain, matching
  *     `OperationDecoderSpec`'s / `PredecodeSimpleLenSpec`'s established precedent):
  *     `OperationDecoder.decode(w)` === `MicroOpAssembler.computeOffload(pkt).spec` for every
  *     one of the 65536 opwords `w`, with the packet's remaining 9 extension words randomized
  *     per opword (so the sweep also pins the independence of `.spec` from the packet tail).
  *     `fed.payload.specs(1).spec` IS `computeOffload(packets(1)).spec` carried through the
  *     same `fedIn -> fed` PipeStage as `packets(1)` itself, so this is the exact function
  *     pair the lever swaps.
  *
  *  2. **Capture-site coverage** (`test 2`): the OLD expression is re-instantiated *in the
  *     testbench* (`UcPendStashProbePlugin`, test-only — it never enters the shipping netlist)
  *     on the very same `ucPendPkt.words(0)` register the deleted cone read, and compared
  *     LIVE, every cycle, against the new `ucPendSpecReg`, while a real
  *     IcachePlugin+FetchAlignPlugin+DecodeStage frontend runs instruction streams that drive
  *     the `ucPendPkt` stash through all four of its writer arms:
  *       - `:1916` normal fed-consume arm  ({normal, microcoded} fetch pair)
  *       - `:1980` `movemEnterSlot0` arm    ({MOVEM,  microcoded} fetch pair)
  *       - `:2094` `ucEnterSlot0` arm       ({microcoded, microcoded} fetch pair)
  *       - `:2139` `movepEnterSlot0` arm    ({MOVEP,  microcoded} fetch pair)
  *     A miss at ANY writer site (a stash written without its paired spec capture) shows up
  *     immediately as a mismatch, because the old cone tracks `ucPendPkt` unconditionally.
  *
  * Half 1 alone would not catch a forgotten writer site; half 2 alone would not be exhaustive
  * over the opword domain. Together they cover the whole claim.
  */
class UcPendSpecStashEquivalenceSpec extends AnyFunSuite {

  // ── Half 1: exhaustive value identity ────────────────────────────────────────────────
  /** `mismatch` = (OLD re-decode of the opword) =/= (the offload spec the stash captures). */
  class SpecIdentityDut extends Component {
    val words    = in(Vec(Bits(16 bits), 10))
    val mismatch = out Bool ()

    val pkt = DecodePacket()
    pkt.valid      := True
    pkt.pc         := 0
    pkt.words      := words
    pkt.wordCount  := 10
    pkt.simple     := True
    pkt.lenWords   := 1
    pkt.complex    := False
    pkt.fault      := False
    pkt.faultAtc   := False
    pkt.predTaken  := False
    pkt.predTarget := 0
    pkt.phtValid   := False
    pkt.phtIndex   := 0

    // LHS: exactly the expression Lever U1 deleted from DecodeStage.scala:886.
    val oldSpec = OperationDecoder.decode(words(0))
    // RHS: exactly the value the stash now captures (DecodeStage.scala:88 computes this on
    // the pre-register packet; the stash reads it out of the `fed` register).
    val newSpec = MicroOpAssembler.computeOffload(pkt).spec

    mismatch := oldSpec.asBits =/= newSpec.asBits
  }

  test("Lever U1 value identity: decode(opword) === computeOffload(pkt).spec, all 65536 opwords", VerilatorTest) {
    SimConfig.withVerilator.compile(new SpecIdentityDut).doSim { dut =>
      val rnd = new Random(0x5eed1)
      var bad = 0
      var firstBad = -1
      for (w <- 0 until 65536) {
        dut.words(0) #= w
        // Randomize the packet tail: `.spec` must be a pure function of words(0) alone.
        for (i <- 1 until 10) dut.words(i) #= rnd.nextInt(65536)
        sleep(1)
        if (dut.mismatch.toBoolean) { bad += 1; if (firstBad < 0) firstBad = w }
      }
      assert(bad == 0, f"$bad%d/65536 opwords disagree between the OLD re-decode and the " +
        f"stashed offload spec (first at opword 0x$firstBad%04x)")
    }
  }

  // ── Half 2: live capture-site coverage ───────────────────────────────────────────────
  /** TEST-ONLY probe: re-instantiates the OLD `OperationDecoder.decode(ucPendPkt.words(0))`
    * cone and compares it live against the new `ucPendSpecReg`. Lives entirely in the test
    * sources, so the shipping netlist keeps the LUT saving. */
  class UcPendStashProbePlugin(dec: DecodeStage) extends FiberPlugin {
    // NOTE: everything below reads `dec.logic.<sig>` INLINE (no `val d = dec.logic` local).
    // Binding the Handle to a local would make the plugin's private `dec` escape through the
    // Area's inferred structural type (`-language:reflectiveCalls` refinement), a compile error.
    val logic = during build new Area {
      // The OLD expression, on the very same register the deleted cone read.
      val oldSpec = OperationDecoder.decode(dec.logic.ucPendPkt.words(0))

      val mismatch = out(Bool())
      mismatch := dec.logic.ucPendValid && (oldSpec.asBits =/= dec.logic.ucPendSpecReg.asBits)

      val pendValid = out(Bool()); pendValid := dec.logic.ucPendValid
      // Which writer arm produced the stash currently held: sampled one cycle late (the arm
      // conditions are combinational in the cycle the register is written, `ucPendValid`
      // becomes true the cycle after).
      val fromMovemArm = out(Bool()); fromMovemArm := RegNext(dec.logic.movemEnterSlot0) init (False)
      val fromUcArm    = out(Bool()); fromUcArm    := RegNext(dec.logic.ucEnterSlot0)    init (False)
      val fromMovepArm = out(Bool()); fromMovepArm := RegNext(dec.logic.movepEnterSlot0) init (False)
    }
  }

  class FrontendDut extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val ic    = new IcachePlugin
    val fa    = new FetchAlignPlugin
    val dec   = new DecodeStage
    val sink  = new UopSinkPlugin
    val probe = new UcPendStashProbePlugin(dec)
    db.on {
      host.asHostOf(Seq[FiberPlugin](
        new ParamPlugin(M68kParams()), new IdentityTranslationPlugin, ic, fa, dec, sink, probe))
    }
  }

  // Opwords used below (all 68k integer ISA, all routed to the DecodeStage microcode engine
  // by OperationDecoder's `microcoded` marker except NOP/MOVEM/MOVEP which drive the arms):
  private val NOP      = 0x4E71
  private val ABCD_M   = 0xC109  // ABCD -(A1),-(A0)      microcoded (BCD memory form)
  private val SBCD_M   = 0x8109  // SBCD -(A1),-(A0)      microcoded
  private val ADDX_M   = 0xD189  // ADDX.L -(A1),-(A0)    microcoded
  private val SUBX_M   = 0x9189  // SUBX.L -(A1),-(A0)    microcoded
  private val MOVEM_L  = 0x4CD8  // MOVEM.L (A0)+,<mask>  (+ mask word)   -> MOVEM FSM
  private val MOVEM_MK = 0x0003
  private val MOVEP_L  = 0x0149  // MOVEP.L d16(A1),D0    (+ disp word)   -> MOVEP FSM
  private val MOVEP_D  = 0x0000

  /** word-offset -> program. Each program is padded with NOPs by the image builder. */
  private val programs: Seq[(String, Int, Seq[Int])] = Seq(
    // arm A (normal fed-consume): slot0 normal, slot1 microcoded
    ("normal+ucode", 0x000,
      Seq(NOP, ABCD_M, NOP, SBCD_M, NOP, ADDX_M, NOP, SUBX_M, NOP, ABCD_M)),
    // arm B (movemEnterSlot0): slot0 MOVEM, slot1 microcoded
    ("movem+ucode", 0x100,
      Seq(MOVEM_L, MOVEM_MK, ABCD_M, NOP, MOVEM_L, MOVEM_MK, SBCD_M, NOP,
          MOVEM_L, MOVEM_MK, ADDX_M, NOP)),
    // arm C (ucEnterSlot0): slot0 microcoded, slot1 microcoded
    ("ucode+ucode", 0x200,
      Seq(ABCD_M, SBCD_M, NOP, NOP, ADDX_M, SUBX_M, NOP, NOP, ABCD_M, ADDX_M)),
    // arm D (movepEnterSlot0): slot0 MOVEP, slot1 microcoded
    ("movep+ucode", 0x300,
      Seq(MOVEP_L, MOVEP_D, ABCD_M, NOP, MOVEP_L, MOVEP_D, SBCD_M, NOP,
          MOVEP_L, MOVEP_D, ADDX_M, NOP))
  )

  test("Lever U1 capture coverage: stashed spec === OLD re-decode, live, over all four writer arms", VerilatorTest) {
    // One NOP-filled image; each program overwrites its own window. NOP padding keeps the
    // frontend's read-ahead (64-byte I-cache lines) off undecodable zero-fill.
    val imageWords = Array.fill(0x800)(NOP)
    programs.foreach { case (_, off, body) => body.zipWithIndex.foreach { case (w, i) => imageWords(off + i) = w } }

    SimConfig.withVerilator.compile(new FrontendDut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      val base = 0x8000L
      IcacheSim.attachMemoryWithWords(dut.ic.logic.axi, cd, base, imageWords.toSeq)
      dut.sink.logic.uopsOut.ready #= true
      dut.fa.logic.resume.valid    #= false
      dut.fa.logic.redirect.valid  #= false
      cd.waitSampling(5)

      // per-arm stash-event counters, keyed by the arm flags sampled at the pend rising edge
      var nNormal, nMovem, nUc, nMovep = 0
      var checkedCycles = 0

      for ((name, off, _) <- programs) {
        dut.fa.logic.redirect.valid   #= true
        dut.fa.logic.redirect.payload #= base + 2 * off
        cd.waitSampling()
        dut.fa.logic.redirect.valid #= false

        var prevPend = false
        var localEvents = 0
        for (_ <- 0 until 400) {
          cd.waitSampling()
          checkedCycles += 1
          assert(!dut.probe.logic.mismatch.toBoolean,
            s"[$name] ucPendSpecReg DISAGREES with the OLD OperationDecoder.decode(ucPendPkt.words(0)) " +
              s"while ucPendValid — the Lever U1 stash is NOT identical to the re-decode it replaced")
          val pend = dut.probe.logic.pendValid.toBoolean
          if (pend && !prevPend) {
            localEvents += 1
            if (dut.probe.logic.fromMovemArm.toBoolean) nMovem += 1
            else if (dut.probe.logic.fromUcArm.toBoolean) nUc += 1
            else if (dut.probe.logic.fromMovepArm.toBoolean) nMovep += 1
            else nNormal += 1
          }
          prevPend = pend
        }
        assert(localEvents > 0, s"[$name] never produced a ucPendPkt stash — the program does not " +
          s"exercise what it claims to; the equivalence check is vacuous for this arm")
      }

      info(s"stash events by writer arm: normal=$nNormal movem=$nMovem ucode=$nUc movep=$nMovep " +
        s"(checked over $checkedCycles cycles)")
      assert(nNormal > 0, "the normal fed-consume writer arm (:1916) was never exercised")
      assert(nMovem  > 0, "the movemEnterSlot0 writer arm (:1980) was never exercised")
      assert(nUc     > 0, "the ucEnterSlot0 writer arm (:2094) was never exercised")
      assert(nMovep  > 0, "the movepEnterSlot0 writer arm (:2139) was never exercised")
    }
  }
}
