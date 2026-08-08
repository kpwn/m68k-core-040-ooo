package m68k040.decode

import m68k040.{M68kParams, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.mmu.IdentityTranslationPlugin
import m68k040.cache.{IcachePlugin, IcacheSim}
import m68k040.frontend.FetchAlignPlugin
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

import scala.util.Random

/** FMax "Frontend Lever C" no-behaviour-change gate
  * (`docs/superpowers/specs/2026-08-08-fmax-frontend-leverc-register-split-design.md`,
  * `.../plans/2026-08-08-fmax-frontend-leverc-register-split-plan.md`).
  *
  * Lever C inserts a NEW pipeline register (`raw`) between the Aligner's combinational
  * packet output and `MicroOpAssembler.computeOffload`, so the offload is now computed on
  * a REGISTERED copy of the packet one cycle later instead of on the live aligner output:
  * {{{
  *   before:  [ibuf+Aligner] -> computeOffload -> REG(fed: packets+specs) -> assemble
  *   after:   [ibuf+Aligner] -> REG(raw: packets) -> computeOffload -> REG(fed: packets+specs) -> assemble
  * }}}
  * The design spec's binding claim is that this is a PURE LATENCY change: `computeOffload`
  * is a pure function of `pkt.words` alone, applied to bit-identical bits one cycle later.
  *
  * The one way that claim could fail in the RTL — and the only new failure mode the lever
  * introduces — is a MISALIGNMENT: `fed.payload.specs(i)` describing a DIFFERENT instruction
  * group than the `fed.payload.packets(i)` it ships alongside (e.g. if `packets` were read
  * from `raw` downstream instead of being re-registered into `fed`, or if the two registers
  * were captured under different enables). Every consumer in `DecodeStage` — `assemble`,
  * `slot1Spec0`, and Lever U1's `ucPendSpecReg` identity proof — depends on that pairing.
  *
  * This spec proves the pairing holds LIVE, every cycle, on a real
  * IcachePlugin + FetchAlignPlugin + DecodeStage frontend: a TEST-ONLY probe recomputes
  * `MicroOpAssembler.computeOffload(fed.payload.packets(i))` from the packet actually
  * present in `fed` and compares the WHOLE `Offload` (OpSpec + srcEa + dstEa), both slots,
  * bit for bit, against the `specs` the pipeline carried. A one-cycle misalignment anywhere
  * — steady state, under backpressure, or across a `pipeFlush` — shows up immediately.
  *
  * Programs are the same four stash-driving fetch patterns Lever U1's own spec uses (they
  * exercise the µcode/MOVEM/MOVEP hold paths, i.e. the cases where `fed` is held for many
  * cycles while the frontend backs up into the new `raw` stage), plus redirects between
  * them so the flush path is covered too.
  */
class FedSpecsPacketPairingSpec extends AnyFunSuite {

  /** TEST-ONLY probe: recompute the offload from the packet riding in `fed` and compare. */
  class FedPairingProbePlugin(dec: DecodeStage) extends FiberPlugin {
    // Everything reads `dec.logic.<sig>` INLINE — binding the Handle to a local would make
    // the plugin's private `dec` escape through the Area's inferred structural type.
    val logic = during build new Area {
      val recomputed0 = MicroOpAssembler.computeOffload(dec.logic.fed.payload.packets(0))
      val recomputed1 = MicroOpAssembler.computeOffload(dec.logic.fed.payload.packets(1))

      val mismatch0 = out(Bool())
      val mismatch1 = out(Bool())
      mismatch0 := dec.logic.fed.valid &&
        (recomputed0.asBits =/= dec.logic.fed.payload.specs(0).asBits)
      mismatch1 := dec.logic.fed.valid && dec.logic.fed.payload.slot1Valid &&
        (recomputed1.asBits =/= dec.logic.fed.payload.specs(1).asBits)

      val fedValid = out(Bool()); fedValid := dec.logic.fed.valid
      val opword0  = out(Bits(16 bits)); opword0 := dec.logic.fed.payload.packets(0).words(0)
    }
  }

  class FrontendDut extends Component {
    val db    = new Database
    val host  = db on (new PluginHost)
    val ic    = new IcachePlugin
    val fa    = new FetchAlignPlugin
    val dec   = new DecodeStage
    val sink  = new UopSinkPlugin
    val probe = new FedPairingProbePlugin(dec)
    db.on {
      host.asHostOf(Seq[FiberPlugin](
        new ParamPlugin(M68kParams()), new IdentityTranslationPlugin, ic, fa, dec, sink, probe))
    }
  }

  private val NOP      = 0x4E71
  private val ABCD_M   = 0xC109  // microcoded (BCD memory form)
  private val SBCD_M   = 0x8109
  private val ADDX_M   = 0xD189
  private val SUBX_M   = 0x9189
  private val MOVEM_L  = 0x4CD8
  private val MOVEM_MK = 0x0003
  private val MOVEP_L  = 0x0149
  private val MOVEP_D  = 0x0000
  private val ADD_DD   = 0xD200  // ADD.B D0,D1   — plain 1-word ALU, full-rate slot filler
  private val MOVE_L   = 0x2200  // MOVE.L D0,D1
  private val ADDI_L   = 0x0680  // ADDI.L #imm,D0 (+2 imm words) — exercises srcEa.imm
  private val ADDI_H   = 0x1234
  private val ADDI_LO  = 0x5678

  private val programs: Seq[(String, Int, Seq[Int])] = Seq(
    ("normal+ucode", 0x000,
      Seq(NOP, ABCD_M, NOP, SBCD_M, NOP, ADDX_M, NOP, SUBX_M, NOP, ABCD_M)),
    ("movem+ucode", 0x100,
      Seq(MOVEM_L, MOVEM_MK, ABCD_M, NOP, MOVEM_L, MOVEM_MK, SBCD_M, NOP,
          MOVEM_L, MOVEM_MK, ADDX_M, NOP)),
    ("ucode+ucode", 0x200,
      Seq(ABCD_M, SBCD_M, NOP, NOP, ADDX_M, SUBX_M, NOP, NOP, ABCD_M, ADDX_M)),
    ("movep+ucode", 0x300,
      Seq(MOVEP_L, MOVEP_D, ABCD_M, NOP, MOVEP_L, MOVEP_D, SBCD_M, NOP,
          MOVEP_L, MOVEP_D, ADDX_M, NOP)),
    // Full-rate 2-wide simple stream + multi-word immediates: the steady-state case where
    // BOTH slots are valid every cycle and `specs`/`packets` must stay paired at full rate.
    ("dual-simple", 0x400,
      Seq.fill(8)(Seq(ADD_DD, MOVE_L, ADDI_L, ADDI_H, ADDI_LO)).flatten)
  )

  test("Lever C: fed.specs(i) === computeOffload(fed.packets(i)), live, both slots", VerilatorTest) {
    val imageWords = Array.fill(0x800)(NOP)
    programs.foreach { case (_, off, body) =>
      body.zipWithIndex.foreach { case (w, i) => imageWords(off + i) = w } }

    SimConfig.withVerilator.compile(new FrontendDut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      val base = 0x8000L
      IcacheSim.attachMemoryWithWords(dut.ic.logic.axi, cd, base, imageWords.toSeq)
      dut.fa.logic.resume.valid   #= false
      dut.fa.logic.redirect.valid #= false
      cd.waitSampling(5)

      val rnd = new Random(0xC0FFEE)
      var checkedCycles = 0
      val opwordsSeen   = scala.collection.mutable.Set[Int]()

      for ((name, off, _) <- programs) {
        dut.fa.logic.redirect.valid   #= true
        dut.fa.logic.redirect.payload #= base + 2 * off
        cd.waitSampling()
        dut.fa.logic.redirect.valid #= false

        for (_ <- 0 until 500) {
          // Randomly stall the µop sink: backpressure holds `fed`, backs the group up into
          // the NEW `raw` stage, and exercises the chained-ready path this lever adds.
          dut.sink.logic.uopsOut.ready #= rnd.nextInt(4) != 0
          cd.waitSampling()
          assert(!dut.probe.logic.mismatch0.toBoolean,
            s"[$name] slot0: fed.payload.specs(0) DISAGREES with computeOffload(fed.payload.packets(0)) " +
              "— the Lever C register split has MISALIGNED the offload from its own packet")
          assert(!dut.probe.logic.mismatch1.toBoolean,
            s"[$name] slot1: fed.payload.specs(1) DISAGREES with computeOffload(fed.payload.packets(1)) " +
              "— the Lever C register split has MISALIGNED the offload from its own packet")
          if (dut.probe.logic.fedValid.toBoolean) {
            checkedCycles += 1
            opwordsSeen += (dut.probe.logic.opword0.toInt & 0xffff)
          }
        }
      }
      dut.sink.logic.uopsOut.ready #= true

      info(s"pairing checked on $checkedCycles fed-valid cycles, ${opwordsSeen.size} distinct slot-0 opwords")
      // Non-vacuity: the check must actually have observed live traffic, not an idle pipe.
      assert(checkedCycles > 200,
        s"only $checkedCycles fed-valid cycles observed — the pairing check is near-vacuous")
      assert(opwordsSeen.size >= 5,
        s"only ${opwordsSeen.size} distinct slot-0 opwords observed — coverage too narrow to be meaningful")
    }
  }
}
