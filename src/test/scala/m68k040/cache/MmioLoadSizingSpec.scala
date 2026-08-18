package m68k040.cache

import m68k040.{M68kParams, M68kSim, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.isa.Size
import m68k040.mmu.DIdentityTranslationPlugin
import m68k040.sim.{AxiMemModel, AxiMemModelConfig}
import m68k040.socket.MmioCover
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** Spec section 13, "MMIO sizing", the load half. The 48-case D30 proof lives in
  * `m68k040.socket.MmioCoverSpec` (untagged, `test-fast`); THIS suite proves the real
  * DcachePlugin emits exactly what that proof describes.
  *
  * Tagged VerilatorTest to match `DcacheSpec`, whose DUT this mirrors: `make test-fast`
  * excludes it, `make test-verilator` and `make test` run it. Task 14's gate runs both. */
class MmioLoadSizingSpec extends AnyFunSuite {

  class Dut extends Component {
    val db     = new Database
    val host   = db on (new PluginHost)
    val param  = new ParamPlugin(M68kParams())
    val xlate  = new DIdentityTranslationPlugin
    val dcache = new DcachePlugin()
    val probe  = new DcacheProbePlugin
    db.on { host.asHostOf(Seq[FiberPlugin](param, xlate, dcache, probe)) }
  }

  lazy val compiled = M68kSim().withVerilator.compile(new Dut)

  /** Every AR the D-cache presented, as (address, AxSIZE), in issue order. */
  private def watchAr(dut: Dut): scala.collection.mutable.ArrayBuffer[(Long, Int)] = {
    val log = scala.collection.mutable.ArrayBuffer[(Long, Int)]()
    val axi = dut.dcache.logic.axi
    fork {
      while (true) {
        dut.clockDomain.waitSampling()
        if (axi.ar.valid.toBoolean && axi.ar.ready.toBoolean)
          log += ((axi.ar.payload.addr.toLong, axi.ar.payload.size.toInt))
      }
    }
    log
  }

  /** SpinalHDL sim-poke gotcha (also documented in `DcacheSpec.initDut`/
    * `initDutErrInject`): an unpoked testbench-driven input field is NOT guaranteed
    * 0 across seeds/runs. Every one of these ports is left unpoked by this suite
    * until `load()`'s own first call (which only pokes `loadCmdIn` itself, and only
    * pokes `loadProbeIn`/`loadProbeCancelIn` AFTER the command is already accepted),
    * so without this ALL FIVE can power up with a stray `valid` and garbage payload
    * during the initial settle window, before this suite's own first explicit
    * command.
    *
    * Both failure shapes were observed concretely, on the real prescribed test
    * code, tracing real bus activity (not a debug-harness artifact -- confirmed by
    * reproducing under two different seeds with two different garbage signatures):
    *  - `storeIn`/`maintCmdIn` garbage (seed=4): a phantom store descriptor
    *    reaches the WT-beat AXI driver (`DcachePlugin.scala`'s `stAwDone`/
    *    `stAddrReg` site) with a garbage address, a spurious AW/B(DECERR) at t=6/7
    *    tripping the `diagFaultExpected` guard on a transaction this suite never
    *    issued.
    *  - `loadCmdIn` (+`loadProbeIn`/`loadProbeCancelIn`) garbage (seed=1): a
    *    phantom load descriptor with a garbage `cacheMode`/`size`/`vaddr`/`paddr`
    *    is ACCEPTED as a real command (nothing gated `loadCmdIn.valid` idle before
    *    this), runs a real miss->REFILL->REPLAY sequence, and issues a real AR at
    *    a garbage address whose response THEN wins the race against the suite's
    *    own first real `load()` call's `waitSamplingWhere(loadRspOut.valid)` --
    *    seed=1 gave a spurious `BYTE off=0` data mismatch, seed=42 gave a spurious
    *    extra sub-transaction; two different garbage signatures, same root cause.
    *
    * Not part of the feature under test; pinning every one of these ports idle is
    * required for every test in this file to be deterministic across seeds,
    * mirroring `DcacheSpec.initDut`'s own established, exhaustive pattern for this
    * same probe plugin (which pins all five for exactly this reason). */
  private def quiesceUnusedPorts(dut: Dut): Unit = {
    dut.probe.logic.loadCmdIn.valid #= false
    dut.probe.logic.loadProbeIn.valid #= false
    dut.probe.logic.loadProbeCancelIn.valid #= false
    dut.probe.logic.loadProbeCancelIn.payload.all #= false
    dut.probe.logic.storeIn.valid #= false
    dut.probe.logic.maintCmdIn.valid #= false
    dut.probe.logic.maintCmdIn.payload.push #= false
    dut.probe.logic.maintCmdIn.payload.invalidate #= false
    dut.probe.logic.maintCmdIn.payload.scope #= 0
    dut.probe.logic.maintCmdIn.payload.sel #= 0
    dut.probe.logic.maintCmdIn.payload.addr #= 0
  }

  private def load(dut: Dut, vaddr: Long, size: SpinalEnumElement[Size.type]): BigInt = {
    val p = dut.probe.logic
    p.loadCmdIn.valid #= true
    p.loadCmdIn.payload.vaddr #= vaddr
    p.loadCmdIn.payload.paddr #= vaddr
    p.loadCmdIn.payload.size  #= size
    p.loadCmdIn.payload.cacheMode #= CacheMode.INHIBITED
    p.loadCmdIn.payload.token #= 0
    dut.clockDomain.waitSamplingWhere(p.loadCmdIn.ready.toBoolean && p.loadCmdIn.valid.toBoolean)
    p.loadCmdIn.valid #= false
    p.loadProbeIn.valid #= false
    p.loadProbeCancelIn.valid #= false
    p.loadProbeCancelIn.payload.all #= false
    dut.clockDomain.waitSamplingWhere(p.loadRspOut.valid.toBoolean)
    p.loadRspOut.payload.data.toBigInt
  }

  private val BASE = 0x0400_0000L      // inside AxiMemModel.decoded, so no injected DECERR

  test("D30: every INHIBITED load emits an exact naturally-aligned cover", VerilatorTest) {
    compiled.doSim("mmio-load-cover", seed = 1) { dut =>
      dut.clockDomain.forkStimulus(10)
      quiesceUnusedPorts(dut)
      val mem = AxiMemModel.attachFull(dut.dcache.logic.axi, dut.clockDomain,
        AxiMemModelConfig(crossbarSingleOutstanding = true, checkIdUnique = false))
      for (i <- 0 until 4096) mem.pokeByte(BASE + i, ((i * 5 + 0x23) & 0xff))
      dut.clockDomain.waitSampling(8)
      val log = watchAr(dut)
      for (off <- 0 until 16; (sz, nm, n) <- Seq((Size.BYTE, "BYTE", 1),
                                                 (Size.WORD, "WORD", 2),
                                                 (Size.LONG, "LONG", 4))) {
        // Each case gets its own line so `off` is the line offset, and its own 16-byte
        // line so a previous case's transactions cannot be confused with this one's.
        val lineBase = BASE + 0x100L * (off * 3 + n)
        for (i <- 0 until 32) mem.pokeByte(lineBase + i, ((lineBase + i) * 5 + 0x23).toInt & 0xff)
        log.clear()
        val got = load(dut, lineBase + off, sz)
        dut.clockDomain.waitSampling(8)

        val want = MmioCover.model(off, math.min(off + n, 16))
        assert(log.length == want.length,
          f"$nm at off=$off: emitted ${log.length} sub-transactions ${log.toList}, " +
          f"the cover says ${want.length} ($want)")
        assert(log.length <= MmioCover.MAX_SUBS, s"more than ${MmioCover.MAX_SUBS} sub-transactions")
        for (((gotAddr, gotSz), (wantOff, wantBytes)) <- log.zip(want)) {
          assert(gotAddr == lineBase + wantOff,
            f"$nm off=$off: address 0x$gotAddr%08X, wanted 0x${lineBase + wantOff}%08X")
          assert((1 << gotSz) == wantBytes,
            s"$nm off=$off at $wantOff: AxSIZE $gotSz (${1 << gotSz} B), wanted $wantBytes B")
          // AXI's own alignment rule, and spec section 13's hard check.
          assert(gotAddr % (1 << gotSz) == 0,
            f"$nm off=$off: 0x$gotAddr%08X is not naturally aligned for AxSIZE $gotSz")
          // Nothing outside the architectural access.
          val lo = wantOff; val hi = wantOff + wantBytes
          assert(lo >= off && hi <= math.min(off + n, 16),
            s"$nm off=$off: sub-range [$lo,$hi) leaves the access")
        }
        // And the assembled value is still right -- CLAMPED exactly like `want` above:
        // LsEuPlugin.scala:759 always splits a cross-line/cross-page access into two
        // separate commands before DcachePlugin ever sees either half, so a single
        // command whose [off, off+n) genuinely crosses this 16-byte line (off=13/14/15
        // LONG, off=15 WORD) is not a shape DcachePlugin is designed to answer with a
        // full n-byte value -- only a shape it must not let escape its own line, which
        // the sub-range assertion above already proves. The spilled byte(s) beyond
        // `clampEnd` belong to the NEXT physical line and were correctly never fetched,
        // so they drop out of this comparison; every byte that DOES belong to the
        // access is still checked exactly as before.
        val clampEnd = math.min(off + n, 16)
        val clampedN = clampEnd - off
        val exp = (0 until clampedN).foldLeft(BigInt(0))((a, i) =>
          (a << 8) | BigInt(((lineBase + off + i) * 5 + 0x23).toInt & 0xff))
        val gotClamped = got >> (8 * (n - clampedN))
        assert(gotClamped == exp,
          f"$nm off=$off: data 0x$got%08X (top $clampedN of $n bytes = 0x$gotClamped%08X), " +
          f"wanted 0x$exp%08X")
      }
    }
  }

  test("D25: an off=12..15 access never emits an address outside its own line", VerilatorTest) {
    // The C1 regression, directed and specifically adversarial (spec section 13's D25 bullet).
    compiled.doSim("mmio-load-clamp", seed = 2) { dut =>
      dut.clockDomain.forkStimulus(10)
      quiesceUnusedPorts(dut)
      val mem = AxiMemModel.attachFull(dut.dcache.logic.axi, dut.clockDomain,
        AxiMemModelConfig(crossbarSingleOutstanding = true, checkIdUnique = false))
      for (i <- 0 until 8192) mem.pokeByte(BASE + i, ((i * 7 + 1) & 0xff))
      dut.clockDomain.waitSampling(8)
      val log = watchAr(dut)
      // Place the line at the LAST line of a 4 KiB page, so an escaping transaction would
      // land in the next physical page -- the case the spec says would otherwise be
      // introduced by the fix rather than found by it.
      val lineBase = BASE + 0x1000L - 16
      for (off <- 12 until 16; (sz, n) <- Seq((Size.WORD, 2), (Size.LONG, 4))) {
        log.clear()
        load(dut, lineBase + off, sz)
        dut.clockDomain.waitSampling(8)
        assert(log.nonEmpty, s"no transaction for off=$off n=$n")
        for ((a, s) <- log) {
          assert(a >= lineBase && a < lineBase + 16,
            f"off=$off n=$n emitted 0x$a%08X, outside the line [0x$lineBase%08X, +16)")
          assert(a + (1 << s) <= lineBase + 16,
            f"off=$off n=$n: a ${1 << s}-byte transfer at 0x$a%08X runs past the line")
          assert(a % (1 << s) == 0, f"0x$a%08X not aligned for AxSIZE $s")
        }
      }
    }
  }

  test("the CACHEABLE path is unchanged: one 16-byte AR at the line base", VerilatorTest) {
    compiled.doSim("cacheable-unchanged", seed = 3) { dut =>
      dut.clockDomain.forkStimulus(10)
      quiesceUnusedPorts(dut)
      val mem = AxiMemModel.attachFull(dut.dcache.logic.axi, dut.clockDomain,
        AxiMemModelConfig(crossbarSingleOutstanding = true, checkIdUnique = false))
      for (i <- 0 until 4096) mem.pokeByte(BASE + i, ((i * 3 + 9) & 0xff))
      dut.clockDomain.waitSampling(8)
      val log = watchAr(dut)
      val p = dut.probe.logic
      for (off <- Seq(0, 1, 5, 13)) {
        log.clear()
        val addr = BASE + 0x800L + off
        p.loadCmdIn.valid #= true
        p.loadCmdIn.payload.vaddr #= addr
        p.loadCmdIn.payload.paddr #= addr
        p.loadCmdIn.payload.size  #= Size.WORD
        p.loadCmdIn.payload.cacheMode #= CacheMode.WRITETHROUGH
        p.loadCmdIn.payload.token #= 0
        dut.clockDomain.waitSamplingWhere(p.loadCmdIn.ready.toBoolean && p.loadCmdIn.valid.toBoolean)
        p.loadCmdIn.valid #= false
        dut.clockDomain.waitSamplingWhere(p.loadRspOut.valid.toBoolean)
        dut.clockDomain.waitSampling(8)
        if (log.nonEmpty) {                       // a hit emits nothing at all
          assert(log.length == 1, s"cacheable off=$off emitted ${log.length} transactions")
          assert(log.head._2 == 4, s"cacheable AxSIZE ${log.head._2}, wanted 4")
          assert(log.head._1 % 16 == 0, "cacheable AR not at the 16-byte line base")
        }
      }
    }
  }

  test("a non-OKAY response on ANY sub-transaction raises the existing fault", VerilatorTest) {
    compiled.doSim("mmio-load-fault", seed = 4) { dut =>
      dut.clockDomain.forkStimulus(10)
      quiesceUnusedPorts(dut)
      // injectBusErrors DECERRs anything outside AxiMemModel.decoded (tasks #189/#211).
      val mem = AxiMemModel.attachFull(dut.dcache.logic.axi, dut.clockDomain,
        AxiMemModelConfig(crossbarSingleOutstanding = true, checkIdUnique = false,
                          injectBusErrors = true))
      dut.clockDomain.waitSampling(8)
      val p = dut.probe.logic
      val bad = 0x9000_0005L                       // top nibble 9 -> not decoded -> DECERR
      p.loadCmdIn.valid #= true
      p.loadCmdIn.payload.vaddr #= bad
      p.loadCmdIn.payload.paddr #= bad
      p.loadCmdIn.payload.size  #= Size.WORD       // off=5 -> TWO sub-transactions
      p.loadCmdIn.payload.cacheMode #= CacheMode.INHIBITED
      p.loadCmdIn.payload.token #= 0
      dut.clockDomain.waitSamplingWhere(p.loadCmdIn.ready.toBoolean && p.loadCmdIn.valid.toBoolean)
      p.loadCmdIn.valid #= false
      dut.clockDomain.waitSamplingWhere(p.loadRspOut.valid.toBoolean)
      assert(p.loadRspOut.payload.fault.toBoolean,
        "a DECERR on a split INHIBITED load did not raise busFaultResp")
    }
  }
}
