package m68k040.cache

import m68k040.{M68kParams, VerilatorTest}
import m68k040.mmu.IdentityTranslationPlugin
import m68k040.services.TranslationService
import m68k040.core.ParamPlugin
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** Design spec §12.1 oracle 4: the Unified Fetch Array's inline predecode field is
  * bit-identical to what the (still-present, shadow) `predMem` array holds for the same
  * (way, set, beat).
  *
  * This is the STRONG form of the oracle: rather than re-implementing
  * `PredecodeWord.classify` in the testbench and comparing against that, it compares the
  * new array against the array it replaces, on the real fill path. Any disagreement is
  * an M1 bug by construction, because `predMem`'s content is the pre-restructure
  * behaviour the whole suite already pins.
  *
  * TWO INDEPENDENT HALVES, deliberately:
  *
  *  1. IN-RTL, every cycle: `dbgUfaPredMatch` (IcachePlugin.scala, S1 stage) compares
  *     what the unified array READS BACK for the beat S1 is delivering against the beat
  *     slice of the shadow `predMem` entry captured for the same way/set. This runs on
  *     every hit and every post-fill REPLAY, across this and every other suite that
  *     instantiates IcachePlugin -- it is the evidence Task 6 (M1b) leans on when it
  *     deletes `predMem`.
  *  2. POST-HOC array sweep: for every VALID (way, set, beat), `IcacheArrayProbe.wayPred`
  *     (which reads the UNIFIED array as of M1a) must equal the corresponding slice of
  *     `predMem`'s whole-line entry. This is the check that a write reached the right
  *     address at all, including for lines nothing has read back yet.
  *
  * Task 6 deletes `predMem` and `dbgUfaPredMatch`; both halves of this oracle are
  * consumed with it (they cannot outlive the shadow they compare against), which is why
  * Task 6 replaces this file's content with read-path tests rather than editing it.
  */
class IcacheUnifiedArraySpec extends AnyFunSuite {

  class Dut(xlateFactory: => FiberPlugin with TranslationService = new IdentityTranslationPlugin)
      extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val param  = new ParamPlugin(M68kParams())
    val xlate  = xlateFactory
    val icache = new IcachePlugin
    val probe  = new FetchProbePlugin
    db.on { host.asHostOf(Seq[FiberPlugin](param, xlate, icache, probe)) }
  }

  // MUST be a `def`, not a `lazy val` (this exact mistake cost a 20-minute hang during
  // Task 5): `Dut` is an INNER class, so the compiler emits a `Dut$lzycompute` accessor
  // guarded by a monitor on the enclosing spec instance. SpinalHDL elaborates the
  // component on its own fiber JVM thread, which then blocks trying to take that monitor
  // while the initiating thread is still inside the `compiled` lazy-val initialiser --
  // a hard deadlock inside PhaseCreateComponent, with no output and no timeout.
  // IcacheOrderOracleSpec uses a `def` for the same reason; the SpinalSim netlist cache
  // (simWorkspace/.cache) means the second compile of identical RTL is nearly free.
  private def compiled = SimConfig.withVerilator.compile(new Dut())

  test("oracle 4: the unified array's inline predecode matches predMem for every filled beat",
       VerilatorTest) {
    compiled.doSim("oracle4-ufa-equiv") { dut =>
      dut.clockDomain.forkStimulus(10)
      IcacheSim.attachMemory(dut.icache.logic.axi, dut.clockDomain, 0x1000, 0x8000)
      // `runOrderedStream`'s documented caller precondition (IcacheOrderOracleSpec):
      // cmdIn.valid/.pc and invalidateAll are undriven top-level IO and are X/random
      // per seed until poked, with no intervening waitSampling before this point.
      dut.probe.logic.cmdIn.valid      #= false
      dut.probe.logic.cmdIn.payload.pc #= 0
      dut.icache.logic.invalidateAll   #= false
      dut.clockDomain.waitSampling(5)

      // Continuously monitor the in-RTL shadow-equivalence signal. It is self-qualifying
      // (True on every cycle that has nothing to compare), so it must be high on EVERY
      // cycle, unconditionally.
      var mismatches = 0
      fork {
        while (true) {
          dut.clockDomain.waitSampling()
          if (!dut.icache.logic.dbgUfaPredMatch.toBoolean) {
            mismatches += 1
            simFailure("ORACLE 4 VIOLATED (in-RTL): on an S1 delivery cycle the unified " +
                       "array's inline predecode field read back different content from " +
                       "the shadow predMem entry for the same (way, set, beat)")
          }
        }
      }

      // 256 distinct lines across all 64 sets, cycling every way several times over.
      val lines = (0 until 256).map(i => 0x1000L + i * 64)
      IcacheOrderOracle.runOrderedStream(
        cmdValid = dut.probe.logic.cmdIn.valid, cmdReady = dut.probe.logic.cmdIn.ready,
        cmdPc    = dut.probe.logic.cmdIn.payload.pc,
        rspValid = dut.probe.logic.rspOut.valid, rspPc = dut.probe.logic.rspOut.payload.pc,
        cd = dut.clockDomain, addrs = lines, timeoutCycles = 120000)
      dut.clockDomain.waitSampling(200)
      assert(mismatches == 0, s"$mismatches in-RTL unified-array predecode mismatches")

      // Post-hoc array sweep: for every VALID (way, set), the unified array's inline
      // predecode field must equal the shadow predMem's corresponding beat slice.
      val predBits = dut.icache.logic.PRED_BITS_PER_BEAT
      val mask     = (BigInt(1) << predBits) - 1
      var checked  = 0
      var nonZero  = 0
      for (w <- 0 until 4; s <- 0 until 64 if IcacheArrayProbe.wayValid(dut.icache, w, s)) {
        for (b <- 0 until 2) {
          val fromUnified = IcacheArrayProbe.wayPred(dut.icache, w, s, b)
          val fromShadow  = (dut.icache.logic.predMem(w).getBigInt(s) >> (predBits * b)) & mask
          assert(fromUnified == fromShadow,
            f"ORACLE 4 VIOLATED (array sweep): way=$w set=$s beat=$b unified=0x" +
            f"${fromUnified.toString(16)} shadow=0x${fromShadow.toString(16)}")
          checked += 1
          if (fromUnified != 0) nonZero += 1
        }
      }
      assert(checked >= 64,
        s"only $checked (way,set,beat) triples were valid -- the stimulus did not " +
        s"actually populate the cache, so this oracle proved nothing")
      // Guard against the degenerate pass in which every entry compared is 0 == 0.
      assert(nonZero >= checked / 2,
        s"only $nonZero of $checked compared predecode entries were non-zero -- an " +
        s"all-zero comparison proves nothing about the unified array's write path")
    }
  }

  test("oracle 4b: the unified array's DATA half is unchanged and still feeds correct fetches",
       VerilatorTest) {
    compiled.doSim("oracle4b-ufa-data") { dut =>
      dut.clockDomain.forkStimulus(10)
      IcacheSim.attachMemory(dut.icache.logic.axi, dut.clockDomain, 0x1000, 0x8000)
      dut.probe.logic.cmdIn.valid      #= false
      dut.probe.logic.cmdIn.payload.pc #= 0
      dut.icache.logic.invalidateAll   #= false
      dut.clockDomain.waitSampling(5)

      // Widening dataMem 256 -> UFA_W bits must not disturb the instruction bytes: the
      // sweep below re-derives every resident line's 64 bytes from the unified array's
      // low 256 bits per beat and compares against the backing-memory image, so a
      // mis-ordered `beatPred ## beatSrc` concatenation or an off-by-one write address
      // shows up as a data mismatch rather than only as a predecode one.
      val lines = (0 until 64).map(i => 0x1000L + i * 64)
      IcacheOrderOracle.runOrderedStream(
        cmdValid = dut.probe.logic.cmdIn.valid, cmdReady = dut.probe.logic.cmdIn.ready,
        cmdPc    = dut.probe.logic.cmdIn.payload.pc,
        rspValid = dut.probe.logic.rspOut.valid, rspPc = dut.probe.logic.rspOut.payload.pc,
        cd = dut.clockDomain, addrs = lines, timeoutCycles = 60000)
      dut.clockDomain.waitSampling(200)

      var checked = 0
      for (w <- 0 until 4; s <- 0 until 64 if IcacheArrayProbe.wayValid(dut.icache, w, s)) {
        val tag  = IcacheArrayProbe.wayTag(dut.icache, w, s)
        val line = (tag.toLong << 12) | (s.toLong << 6)
        for (b <- 0 until 2) {
          val got = IcacheArrayProbe.wayData(dut.icache, w, s, b)
          // Rebuild the expected 256-bit beat from the same generator the fetch path is
          // checked against elsewhere in this suite (IcacheSim.window64, 8 bytes each).
          var exp = BigInt(0)
          for (win <- 3 to 0 by -1) exp = (exp << 64) | IcacheSim.window64(line + b * 32 + win * 8)
          assert(got == exp,
            f"UFA data half CORRUPTED: way=$w set=$s beat=$b line=0x$line%x%n" +
            f"  got 0x${got.toString(16)}%n  exp 0x${exp.toString(16)}")
          checked += 1
        }
      }
      assert(checked >= 64, s"only $checked (way,set,beat) triples were valid")
    }
  }
}
