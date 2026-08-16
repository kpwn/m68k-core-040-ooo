package m68k040.execute

import m68k040.VerilatorTest
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.{PluginHost, FiberPlugin}
import spinal.core.fiber.Fiber
import org.scalatest.funsuite.AnyFunSuite

/** Unit coverage for the non-renamed FP control state (Task 9).
  *
  * The AEXC truth table below is the load-bearing part: it is the ONE bit-level fact in
  * this task that the FMOVE encoding sources do not cover, and it is transcribed from the
  * MC68040 User's Manual section 9.2.3.4 ("Accrued Exception (AEXC) Byte", pages 9-5/9-6)
  * -- see `FpuControlPlugin.aexcOf`'s doc comment for the verbatim equations, and for the
  * BSUN correction this verification actually caught. */
class FpuControlPluginSpec extends AnyFunSuite {
  class Dut extends Component {
    val p = new FpuControlPlugin()
    // Force the plugin's Fiber `logic` Area to elaborate inside this Component.
    val host = new PluginHost
    host.asHostOf(Seq[FiberPlugin](p))
    // Anchor the state to a top-level output so nothing is pruned before the sim can
    // observe it (the simPublic Regs are readable either way, but an all-dangling
    // Component is a degenerate netlist).
    val fpsrOut  = out(UInt(32 bits))
    val fpcrOut  = out(UInt(32 bits))
    val fpiarOut = out(UInt(32 bits))
    Fiber build {
      fpsrOut  := p.logic.fpsr
      fpcrOut  := p.logic.fpcr
      fpiarOut := p.logic.fpiar
    }
  }
  private lazy val compiled = SimConfig.withVerilator.compile(new Dut)

  test("FpuControlPlugin: FPCR/FPSR/FPIAR round-trip through their write Flows", VerilatorTest) {
    compiled.doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      val l = dut.p.logic
      l.setFpcr.valid #= false; l.setFpsr.valid #= false
      l.setFpiar.valid #= false; l.orFpsrExc.valid #= false
      dut.clockDomain.waitSampling(2)
      assert(l.fpcr.toBigInt == 0 && l.fpsr.toBigInt == 0 && l.fpiar.toBigInt == 0,
        "reset state must be all-zero (RN, extended precision, all traps disabled) -- also the " +
        "state a null-frame FRESTORE returns to (Task 11)")

      l.setFpcr.valid #= true; l.setFpcr.payload #= BigInt("00000030", 16)  // RND=11 (RP)
      dut.clockDomain.waitSampling(); l.setFpcr.valid #= false
      dut.clockDomain.waitSampling()
      assert(l.fpcr.toBigInt == 0x30, s"FPCR write, got ${l.fpcr.toBigInt.toString(16)}")

      l.setFpiar.valid #= true; l.setFpiar.payload #= BigInt("0000ABCD", 16)
      dut.clockDomain.waitSampling(); l.setFpiar.valid #= false
      dut.clockDomain.waitSampling()
      assert(l.fpiar.toBigInt == 0xABCD, "FPIAR write")
      // The three registers are independent: writing FPIAR must not have disturbed FPCR.
      assert(l.fpcr.toBigInt == 0x30 && l.fpsr.toBigInt == 0)
    }
  }

  test("FpuControlPlugin: an FPSR write MASKS OFF bits [27:24] (FPCC is renamed elsewhere)", VerilatorTest) {
    compiled.doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      val l = dut.p.logic
      l.setFpcr.valid #= false; l.setFpsr.valid #= false
      l.setFpiar.valid #= false; l.orFpsrExc.valid #= false
      dut.clockDomain.waitSampling(2)
      l.setFpsr.valid #= true; l.setFpsr.payload #= BigInt("0F000000", 16)  // all 4 FPCC bits
      dut.clockDomain.waitSampling(); l.setFpsr.valid #= false
      dut.clockDomain.waitSampling()
      assert(l.fpsr.toBigInt == 0,
        s"FPSR[27:24] must not be stored here -- FPCC lives in the renamed FPCC PRF; got ${l.fpsr.toBigInt.toString(16)}")
      l.setFpsr.valid #= true; l.setFpsr.payload #= BigInt("12345678", 16)
      dut.clockDomain.waitSampling(); l.setFpsr.valid #= false
      dut.clockDomain.waitSampling()
      // 0x12345678 with bits [27:24] (the SECOND nibble) cleared is 0x10345678.
      // (The task brief's draft of this assertion expected 0x12045678, which clears
      // [23:20] instead -- an off-by-one-nibble in the brief, caught by running it.)
      assert(l.fpsr.toBigInt == BigInt("10345678", 16),
        s"only [27:24] masked, everything else kept; got ${l.fpsr.toBigInt.toString(16)}")
    }
  }

  test("FpuControlPlugin: orFpsrExc ORs into EXC[15:8] and folds AEXC[7:0] (MC68040 UM 9.2.3.4)", VerilatorTest) {
    compiled.doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      val l = dut.p.logic
      l.setFpcr.valid #= false; l.setFpsr.valid #= false
      l.setFpiar.valid #= false; l.orFpsrExc.valid #= false
      dut.clockDomain.waitSampling(2)

      def clearFpsr(): Unit = {
        l.setFpsr.valid #= true; l.setFpsr.payload #= 0
        dut.clockDomain.waitSampling(); l.setFpsr.valid #= false
        dut.clockDomain.waitSampling()
        assert(l.fpsr.toBigInt == 0)
      }
      /** Pulse the EU port with `exc` and return (EXC byte, AEXC byte) afterwards. */
      def pulse(exc: Int): (Int, Int) = {
        l.orFpsrExc.valid #= true; l.orFpsrExc.payload #= exc
        dut.clockDomain.waitSampling(); l.orFpsrExc.valid #= false
        dut.clockDomain.waitSampling()
        val v = l.fpsr.toBigInt
        (((v >> 8) & 0xFF).toInt, (v & 0xFF).toInt)
      }

      // EXC field bit positions (UM Figure 9-5, FPSR[15:8]):
      //   7 BSUN, 6 SNAN, 5 OPERR, 4 OVFL, 3 UNFL, 2 DZ, 1 INEX2, 0 INEX1
      // AEXC field bit positions (UM Figure 9-6, FPSR[7:0]):
      //   7 IOP, 6 OVFL, 5 UNFL, 4 DZ, 3 INEX, 2:0 reserved
      val BSUN = 0x80; val SNAN = 0x40; val OPERR = 0x20; val OVFL = 0x10
      val UNFL = 0x08; val DZ = 0x04; val INEX2 = 0x02; val INEX1 = 0x01
      val A_IOP = 0x80; val A_OVFL = 0x40; val A_UNFL = 0x20; val A_DZ = 0x10; val A_INEX = 0x08

      // ── the UM's five equations, one directed case each ──────────────────────
      // IOP = SNAN V OPERR
      clearFpsr(); assert(pulse(SNAN)  == ((SNAN, A_IOP)),  "AEXC.IOP from SNAN")
      clearFpsr(); assert(pulse(OPERR) == ((OPERR, A_IOP)), "AEXC.IOP from OPERR")
      // ...and BSUN is NOT part of it. This is the correction Step 12's primary-source
      // read caught: the task brief's draft fold had IOP = BSUN | SNAN | OPERR, which the
      // UM's equation table contradicts. A trap-disabled FBEQ-on-NaN sets EXC.BSUN and
      // must NOT permanently poison the sticky accrued invalid-operation bit.
      clearFpsr(); assert(pulse(BSUN)  == ((BSUN, 0)),
        "AEXC.IOP must NOT accrue from BSUN (MC68040 UM 9.2.3.4: IOP = IOP V (SNAN V OPERR))")
      // OVFL = OVFL, and INEX = ... V OVFL, so an overflow sets BOTH.
      clearFpsr(); assert(pulse(OVFL)  == ((OVFL, A_OVFL | A_INEX)), "AEXC.OVFL and AEXC.INEX from OVFL")
      // UNFL = UNFL ^ INEX2 -- an underflow that rounded EXACTLY does not accrue.
      clearFpsr(); assert(pulse(UNFL)  == ((UNFL, 0)),
        "AEXC.UNFL must require UNFL AND INEX2 -- an exact underflow does not accrue")
      clearFpsr(); assert(pulse(UNFL | INEX2) == ((UNFL | INEX2, A_UNFL | A_INEX)), "AEXC.UNFL | AEXC.INEX")
      // DZ = DZ
      clearFpsr(); assert(pulse(DZ)    == ((DZ, A_DZ)), "AEXC.DZ from DZ")
      // INEX = INEX1 V INEX2 V OVFL
      clearFpsr(); assert(pulse(INEX1) == ((INEX1, A_INEX)), "AEXC.INEX from INEX1")
      clearFpsr(); assert(pulse(INEX2) == ((INEX2, A_INEX)), "AEXC.INEX from INEX2")
      // AEXC's low 3 bits are reserved and must always read 0.
      clearFpsr(); assert((pulse(0xFF)._2 & 0x07) == 0, "AEXC[2:0] are reserved and must stay 0")

      // ── STICKINESS: both bytes accumulate, they are never replaced ────────────
      clearFpsr()
      assert(pulse(DZ)    == ((DZ, A_DZ)))
      assert(pulse(INEX1) == ((DZ | INEX1, A_DZ | A_INEX)), "EXC and AEXC are both sticky (OR-accumulate)")

      // ── an architectural FMOVE-to-FPSR WINS a same-cycle collision ────────────
      // The UM's own note (9.2.3.4, page 9-6) is that FMOVE/FMOVEM do NOT accrue; the
      // writer ordering here makes an explicit FPSR write the sole authority for that
      // cycle rather than being OR-ed into. (They cannot actually collide -- the FMOVE
      // write happens at a serializing commit with the EU flushed -- so this is
      // defence-in-depth, pinned so a future reorder of the two `when`s fails loudly.)
      l.setFpsr.valid #= true; l.setFpsr.payload #= 0
      l.orFpsrExc.valid #= true; l.orFpsrExc.payload #= 0xFF
      dut.clockDomain.waitSampling()
      l.setFpsr.valid #= false; l.orFpsrExc.valid #= false
      dut.clockDomain.waitSampling()
      assert(l.fpsr.toBigInt == 0,
        s"an architectural FPSR write must not be polluted by a same-cycle EU accrual; got ${l.fpsr.toBigInt.toString(16)}")
    }
  }
}
