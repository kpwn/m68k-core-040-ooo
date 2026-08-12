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

/** Directed gate for the Unified Fetch Array's READ path (implementation plan Task 6,
  * M1b) plus the surviving half of design spec §12.1 oracle 4.
  *
  * ── WHAT USED TO BE HERE, AND WHY IT IS GONE ────────────────────────────────────
  * Task 5 (M1a) wrote the unified array in parallel with the pre-existing whole-line
  * predecode array and proved bit-for-bit equivalence two ways: an IN-RTL per-cycle
  * read-back monitor (`dbgUfaPredMatch`) and a POST-HOC array sweep comparing
  * `IcacheArrayProbe.wayPred` against that shadow array. Task 6 deletes the shadow
  * array outright, so BOTH of those checks are consumed with it -- nothing else in the
  * design independently produces the "shadow" value any more, and a comparison written
  * against the surviving array would be comparing the array with ITSELF. The plan says
  * this in as many words ("Do not leave a test that compares the array against itself"),
  * and it offers `PerBeatPredecodeEquivSpec`'s reference classifier as a replacement
  * shadow if one is exposed. It is NOT: that spec is a pure-Scala model of the
  * (word-index, validity-flag) TUPLES presented to `classify`, not a classifier; and the
  * one real Scala classifier in the tree, `PredecodeRef`, deliberately always assumes a
  * BRIEF extension word, so it legitimately disagrees with the RTL wherever the fetched
  * image happens to encode a full-format EA -- it cannot serve as an array-content
  * oracle over arbitrary memory. So the plan's stated fallback is taken: the
  * shadow-comparison test is deleted rather than rewritten.
  *
  * ── WHAT STILL COVERS THE DELETED CHECK'S GROUND ────────────────────────────────
  * The deleted sweep's real value was proving each predecode write landed at the RIGHT
  * ADDRESS, including for lines nothing has read back. That value is retained in full by
  * oracle 4b below, because as of M1 the predecode and the data are ONE write, of ONE
  * concatenated entry, at ONE address: `lineMem(w).write(missSet ## commitBeat,
  * beatPred ## beatSrc)`. A wrong write address, a swapped beat half, or a mis-ordered
  * concatenation therefore corrupts the DATA half too, and 4b re-derives every resident
  * beat's 256 data bits from the backing-memory image -- a genuinely independent oracle
  * that no restructure of the array can make tautological.
  *
  * What 4b cannot see is the S1->rsp READ path, which is exactly what M1b changed
  * (way-mux + a one-bit-narrower window select, plus a new explicit fault mask). The two
  * M1b tests below pin that, and the fault test is written so it CANNOT pass vacuously.
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

  test("M1b: a hit's response predecode comes from the unified array and matches the array content",
       VerilatorTest) {
    compiled.doSim("m1b-read-path") { dut =>
      dut.clockDomain.forkStimulus(10)
      IcacheSim.attachMemory(dut.icache.logic.axi, dut.clockDomain, 0x1000, 0x2000)
      dut.probe.logic.cmdIn.valid      #= false
      dut.probe.logic.cmdIn.payload.pc #= 0
      dut.icache.logic.invalidateAll   #= false
      dut.clockDomain.waitSampling(5)

      // Fill line 0x1000, then RE-fetch every 8-byte window in it as a HIT and check the
      // response's 4 predecode chunks against the array content for that window. This is
      // the whole S1->rsp read path M1b rewrote: the registered way-mux, the beat that
      // pc(5) already selected when the array was addressed, and the pc(4:3) window
      // select that replaced the old whole-line pc(5:3).
      val base = 0x1000L
      IcacheFetchDriver.fetchAndWait(dut.icache, dut.probe, dut.clockDomain, base)
      dut.clockDomain.waitSampling(10)

      val set = ((base >> 6) & 63).toInt
      val way = (0 until 4).find(w => IcacheArrayProbe.wayValid(dut.icache, w, set))
        .getOrElse(fail(f"line 0x$base%x did not become resident in set $set"))
      val chunkBits = dut.icache.logic.PRED_BITS_PER_WORD
      val chunkMask = (BigInt(1) << chunkBits) - 1

      var nonZero = 0
      for (win <- 0 until 8) {
        val pc   = base + win * 8
        val beat = win / 4          // pc(5): windows 0-3 in beat 0, 4-7 in beat 1
        val lane = win % 4          // pc(4:3)
        val rsp  = IcacheFetchDriver.fetchAndWait(dut.icache, dut.probe, dut.clockDomain, pc)

        assert(!rsp.fault, f"pc=0x$pc%x faulted unexpectedly: ${rsp.describe}")
        assert(rsp.pc == BigInt(pc), f"pc=0x$pc%x got a response for 0x${rsp.pc.toString(16)}")
        val beatPred = IcacheArrayProbe.wayPred(dut.icache, way, set, beat)
        for (k <- 0 until 4) {
          val expect = (beatPred >> (chunkBits * (lane * 4 + k))) & chunkMask
          val got    = rsp.pred(k)
          assert(got == expect,
            f"pc=0x$pc%x chunk $k: rsp predecode 0x${got.toString(16)} != unified array " +
            f"0x${expect.toString(16)} (way=$way set=$set beat=$beat lane=$lane)")
          if (expect != 0) nonZero += 1
        }
      }
      // Guard against the degenerate pass in which every comparison was 0 == 0 (which a
      // read path stuck at zero would also satisfy).
      assert(nonZero >= 16,
        s"only $nonZero of 32 compared predecode chunks were non-zero -- an all-zero " +
        s"comparison proves nothing about the read path")
    }
  }

  test("M1b: a translation fault delivers ZEROED predecode (the old capture bank's placeholder)",
       VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut(new ICacheModeTranslationPlugin))
      .doSim("m1b-fault-mask") { dut =>
      val xlate = dut.xlate.asInstanceOf[ICacheModeTranslationPlugin]
      dut.clockDomain.forkStimulus(10)
      IcacheSim.attachMemory(dut.icache.logic.axi, dut.clockDomain, 0x1000, 0x2000)
      dut.probe.logic.cmdIn.valid      #= false
      dut.probe.logic.cmdIn.payload.pc #= 0
      dut.icache.logic.invalidateAll   #= false
      xlate.logic.forceFault           #= false
      dut.clockDomain.waitSampling(5)

      // Make line 0x1000 RESIDENT with real (non-zero) predecode first...
      val base = 0x1000L
      val set  = ((base >> 6) & 63).toInt
      IcacheFetchDriver.fetchAndWait(dut.icache, dut.probe, dut.clockDomain, base)
      dut.clockDomain.waitSampling(10)
      assert(IcacheArrayProbe.wayValid(dut.icache, 0, set),
        f"line 0x$base%x did not land in WAY 0 of set $set; the fault path forces " +
        s"s1Way := 0, so a resident line in any other way would let this test pass " +
        s"vacuously (way 0 would simply be empty and read back as zero)")
      assert(IcacheArrayProbe.wayPred(dut.icache, 0, set, 0) != 0,
        "the resident line's predecode is all-zero, so this test cannot distinguish " +
        "'fault masked it' from 'it was already zero'")

      // ...then fault the SAME address, and require zeroed predecode anyway. Before M1b
      // this came from the S1 capture bank being written all-zero on the fault path; now
      // it must come from the explicit S1 fault mask (spec §5.1, 'Fault placeholder').
      // Without that mask the speculatively-armed array read -- which is issued from
      // page-invariant virtual bits and so still targets set 0 / beat 0 -- would deliver
      // way 0's real, non-zero predecode straight out to the response.
      xlate.logic.forceFault #= true
      dut.clockDomain.waitSampling(2)
      val rsp = IcacheFetchDriver.fetchAndWait(dut.icache, dut.probe, dut.clockDomain, base)
      xlate.logic.forceFault #= false
      assert(rsp.fault, s"the fetch did not fault: ${rsp.describe}")
      for (k <- 0 until 4)
        assert(rsp.pred(k) == 0,
          s"faulting fetch delivered NON-ZERO predecode chunk $k = 0x${rsp.pred(k).toString(16)}; " +
          s"the zeroed-predecode placeholder was lost when the S1 capture bank was deleted " +
          s"(${rsp.describe})")
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
      // shows up as a data mismatch. Because predecode and data are a SINGLE write of a
      // SINGLE entry at a SINGLE address, this also carries the write-addressing half of
      // the (now deleted) shadow oracle 4 -- see this file's header.
      // Fix 2 (Task 5 review): hit both beats of every line. Each 64-byte line's low beat
      // is at offset 0 and its high beat at +32; a purely 64-byte-aligned stimulus never
      // sets s1Pc(5), leaving the high-beat path unexercised.
      val lines = (0 until 64).flatMap(i => Seq(0x1000L + i * 64, 0x1000L + i * 64 + 32))
      IcacheOrderOracle.runOrderedStream(
        cmdValid = dut.probe.logic.cmdIn.valid, cmdReady = dut.probe.logic.cmdIn.ready,
        cmdPc    = dut.probe.logic.cmdIn.payload.pc,
        rspValid = dut.probe.logic.rspOut.valid, rspPc = dut.probe.logic.rspOut.payload.pc,
        cd = dut.clockDomain, addrs = lines, timeoutCycles = 120000)
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
