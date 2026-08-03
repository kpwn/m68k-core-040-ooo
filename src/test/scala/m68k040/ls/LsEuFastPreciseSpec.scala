package m68k040.ls

import m68k040.{M68kParams, M68kSim, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.cache.DcachePlugin
import m68k040.execute.LsEuPlugin
import m68k040.execute.regfile.{RegFilePluginInt, RegFilePluginNzvc, RegFilePluginX}
import m68k040.isa.{MemOp, Size}
import m68k040.mmu.{DtlbPlugin, MmuControlPlugin}
import m68k040.services.CacheControlService
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** Test-only CacheControlService provider: a directly sim-pokeable `dcacheEnabled`
  * bit. The real hardware source is RobPlugin's CACR.DE (bit 31) — dragging in the
  * whole ROB just to flip one control bit for an LS-EU-only directed test isn't
  * worth it, so this stub plugin publishes the SAME service interface with a plain
  * RegInit(False) (matches the real architectural reset default: CACR resets to 0,
  * D-cache disabled) that the test can poke directly, mirroring MmuControlPlugin's
  * own sim-poke discipline. */
class CacheControlStubPlugin extends FiberPlugin with CacheControlService {
  var _dcacheEnabled: Bool = null
  override def dcacheEnabled: Bool = _dcacheEnabled
  val logic = during build new Area {
    // Sim-poked control register (held; the sim pokes it). Self-assign so it has a
    // driver (no UNASSIGNED REGISTER), mirroring DFaultingTranslationPlugin's
    // established pattern for this exact situation.
    val dcacheEnabled = RegInit(False); dcacheEnabled.simPublic(); dcacheEnabled := dcacheEnabled
    _dcacheEnabled = dcacheEnabled
  }
}

/** Task P2.5 post-review fix: a minimal glue plugin mirroring FullCoreSynth's
  * `BackendWiringPlugin` pattern (see FullCoreSynth.scala's `lsEu.robHeadIn :=
  * rob.logic.h0` block). `LsEuPlugin.robHeadIn`/`robHeadValidIn`/`sqCompletionPort`
  * are bare plugin-level pass-through fields (populated during `setup`, driven/read
  * in `logic`'s `during build`), not proper `in()`/`out()` IO nested in an Area. In
  * a standalone LS-only DUT (no RobPlugin providing a real HDL-level override),
  * `robHeadIn`'s only driver is its own idle default (`robHeadIn := U(0,6 bits)`) --
  * Verilog generation constant-folds it away entirely, so a raw sim poke on it
  * (even simPublic-tagged) cannot work. Likewise `sqCompletionPort`, having no
  * consumer at all in a standalone DUT, gets pruned despite simPublic(). This
  * plugin gives both a genuine IO-backed detour: real `in()` ports drive
  * `robHeadIn`/`robHeadValidIn` (a real HDL-level override, exactly like
  * FullCoreSynth's own wiring), and real `out()` ports mirror `sqCompletionPort`
  * for observation -- both are then real, freely sim-pokeable/readable signals. */
class TbPreciseDrainWirePlugin(eu: LsEuPlugin) extends FiberPlugin {
  val logic = during build new Area {
    val iRobHeadIn      = in UInt (6 bits)
    val iRobHeadValidIn = in Bool ()
    eu.robHeadIn      := iRobHeadIn
    eu.robHeadValidIn := iRobHeadValidIn

    val oSqCompValid   = out Bool ()
    val oSqCompPayload = out UInt (6 bits)
    oSqCompValid   := eu.sqCompletionPort.valid
    oSqCompPayload := eu.sqCompletionPort.payload
  }
}

/** Task P2.2 directed tests: LsEuPlugin's fast-vs-precise store classification
  * (`fastStore := mmuEnable && cacheable(s2Cmode) && CACR.DE`) and the resulting
  * CONDITIONAL `captureCompletion` at the SQ-alloc point.
  *
  *  - MMU-off store: fastStore is False regardless of cache mode (DtlbPlugin's own
  *    MMU-disabled response is always WRITETHROUGH+ready, so cacheability alone
  *    would otherwise look "fast" — mmuEnable is the deciding factor here). The
  *    store must still allocate into the SQ (exactly as before) but must NOT drive
  *    completionPort that cycle — precise=True, ROB completion deferred to the SQ's
  *    at-head drain (not yet built; Task P2.4).
  *  - MMU-on, WRITETHROUGH-page (CM=00, the page-table default), DE=1: fastStore is
  *    True — the store allocates AND completes the same cycle as alloc, exactly like
  *    today (pre-Task-P2.1 behavior), precise=False.
  *
  * Real MmuControlPlugin + DtlbPlugin (not the identity stub) are needed to exercise
  * the MMU-on case, mirroring DtlbSpec's own DUT/table-build helpers. */
class LsEuFastPreciseSpec extends AnyFunSuite {
  class Dut extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val param     = new ParamPlugin(M68kParams())
    val rfInt     = new RegFilePluginInt
    val rfNzvc    = new RegFilePluginNzvc
    val rfX       = new RegFilePluginX
    val ctrl      = new MmuControlPlugin
    val dtlb      = new DtlbPlugin
    val dcache    = new DcachePlugin
    val cacheCtrl = new CacheControlStubPlugin
    val eu        = new LsEuPlugin
    val src       = new LsEuSourcePlugin
    val wire      = new TbPreciseDrainWirePlugin(eu)
    db.on { host.asHostOf(Seq[FiberPlugin](param, rfInt, rfNzvc, rfX, ctrl, dtlb, dcache, cacheCtrl, eu, src, wire)) }
  }

  def simConfig = M68kSim().withVerilator

  val ROOT = 0x10000L
  val PTRT = 0x11000L
  val PAGT = 0x12000L

  // Mirrors DtlbSpec's helpers (big-endian descriptor words; CM bits left 0 =>
  // WRITETHROUGH, the page-table default).
  def pokeWordLE(mem: BehavioralMemAgent, addr: Long, w: Long): Unit =
    for (i <- 0 until 4) mem.pokeByte(addr + i, ((w >> (8 * (3 - i))) & 0xff).toInt)
  def rootIdx(va: Long): Int = ((va >> 25) & 0x7f).toInt
  def ptrIdx(va: Long): Int  = ((va >> 18) & 0x7f).toInt
  def pageIdx(va: Long): Int = ((va >> 12) & 0x3f).toInt

  def buildResidentWritethroughPage(mem: BehavioralMemAgent, va: Long, ppn: Long): Unit = {
    pokeWordLE(mem, ROOT + rootIdx(va) * 4, (PTRT & 0xfffffff0L) | 0x3L)
    pokeWordLE(mem, PTRT + ptrIdx(va) * 4, (PAGT & 0xfffffff0L) | 0x3L)
    val pd = ((ppn << 12) & 0xfffff000L) | 0x1L   // resident, CM=00 (WRITETHROUGH), no write-protect
    pokeWordLE(mem, PAGT + pageIdx(va) * 4, pd)
  }

  // Task P5.6: a COPYBACK page (CM=01, page-descriptor bit5 set per
  // IcacheTypes.CacheMode.decode's bits[6:5] encoding — mirrors
  // buildResidentWritethroughPage exactly, differing only in the CM bits) used to
  // prove DE=0 overrides the page's OWN attribute down to INHIBITED.
  def buildResidentCopybackPage(mem: BehavioralMemAgent, va: Long, ppn: Long): Unit = {
    pokeWordLE(mem, ROOT + rootIdx(va) * 4, (PTRT & 0xfffffff0L) | 0x3L)
    pokeWordLE(mem, PTRT + ptrIdx(va) * 4, (PAGT & 0xfffffff0L) | 0x3L)
    val pd = ((ppn << 12) & 0xfffff000L) | 0x20L | 0x1L   // resident, CM=01 (COPYBACK), no write-protect
    pokeWordLE(mem, PAGT + pageIdx(va) * 4, pd)
  }

  def seed(dut: Dut, cd: ClockDomain, preg: Int, value: Long): Unit = {
    dut.src.logic.seedValid #= true; dut.src.logic.seedAddr #= preg; dut.src.logic.seedData #= BigInt(value & 0xffffffffL)
    cd.waitSampling()
    dut.src.logic.seedValid #= false
    cd.waitSampling(2)
  }

  def initDut(dut: Dut): (ClockDomain, BehavioralMemAgent, BehavioralMemAgent) = {
    val cd = dut.clockDomain; cd.forkStimulus(10)
    val mem   = new BehavioralMemAgent(dut.dcache.logic.axi, cd)
    val ptmem = new BehavioralMemAgent(dut.dtlb.walkerAxi, cd)
    val s = dut.src.logic
    s.iValid #= false; s.iSqCommitValid #= false; s.iSqFlush #= false
    s.iStkPush #= false; s.iLeaAddr #= false
    s.seedValid #= false; s.obsIntAddr #= 0; s.iPsrcAValid #= false; s.iPsrcBValid #= false
    dut.ctrl.logic.mmuEnable #= false
    dut.ctrl.logic.urp #= 0; dut.ctrl.logic.srp #= 0
    dut.cacheCtrl.logic.dcacheEnabled #= false
    dut.wire.logic.iRobHeadIn #= 0; dut.wire.logic.iRobHeadValidIn #= false
    cd.waitSampling(80) // PRF init sweep
    (cd, mem, ptmem)
  }

  def issueStore(dut: Dut, cd: ClockDomain, basePreg: Int, disp: Long, dataPreg: Int, size: SpinalEnumElement[Size.type], robId: Int): Unit = {
    val s = dut.src.logic
    s.iValid #= true; s.iMemOp #= MemOp.STORE; s.iSize #= size
    s.iPsrcA #= basePreg; s.iPsrcAValid #= true
    s.iPsrcB #= dataPreg; s.iPsrcBValid #= true
    s.iImm #= BigInt(disp & 0xffffffffL)
    s.iPdstValid #= false; s.iPdst #= 0; s.iRobId #= robId
    cd.waitSamplingWhere(s.iReady.toBoolean)
    s.iValid #= false
  }

  /** LEA address-generate: completes deterministically (NO translate, NO cache/AXI
    * access at all -- see LsEuPlugin's `IDLE.whenIsActive` `when(u1.leaAddr)` arm) a
    * FIXED 2 cycles after issue is accepted (capture at accept+1, ready-for-next-issue
    * at accept+3). Used as the "live op" in the `liveCompletionFires` collision test
    * below: its captureCompletion timing has zero dependency on the randomized AXI
    * write-ready timing that governs a precise store's real background drain, so a
    * continuous back-to-back LEA train gives a fully deterministic (for a fixed sim
    * seed), period-3 stream of `liveCompletionFires` pulses to collide against. */
  def issueLea(dut: Dut, cd: ClockDomain, basePreg: Int, robId: Int): Unit = {
    val s = dut.src.logic
    s.iValid #= true; s.iMemOp #= MemOp.LOAD; s.iSize #= Size.LONG
    s.iPsrcA #= basePreg; s.iPsrcAValid #= true
    s.iPsrcB #= 0; s.iPsrcBValid #= false
    s.iImm #= 0
    s.iPdstValid #= false; s.iPdst #= 0; s.iRobId #= robId
    s.iLeaAddr #= true
    cd.waitSamplingWhere(s.iReady.toBoolean)
    s.iValid #= false
    s.iLeaAddr #= false
  }

  /** Poll for the SQ alloc pulse. Returns (entryIdx, completedNextCycle) — the SQ
    * ring index the store was written to (`sq.tail` read the SAME cycle
    * `sq.io.alloc.valid` fires — a plain combinational Flow.valid, immediately
    * observable — before it increments) and whether completionPort fired for
    * `robId` the FOLLOWING cycle. `captureCompletion` writes `compValid` (a
    * RegInit(False) that drives completionPort) — a REGISTERED assignment, so a
    * capture decided the same FSM cycle `sq.io.alloc.valid` is asserted is only
    * OBSERVABLE on completionPort one clock edge later (pre-existing pipeline
    * latency, unrelated to Task P2.2 — see LsEuPlugin's own "compValid is a
    * single-cycle pulse" comment). */
  def waitAlloc(dut: Dut, cd: ClockDomain, robId: Int, maxCycles: Int = 200): (Int, Boolean) = {
    var idx = -1
    var completedNextCycle = false
    var n = 0
    while (idx < 0 && n < maxCycles) {
      if (dut.eu.logic.sq.io.alloc.valid.toBoolean) {
        idx = dut.eu.logic.sq.tail.toInt
        cd.waitSampling()
        completedNextCycle = dut.src.logic.cValid.toBoolean && dut.src.logic.cRob.toInt == robId
      } else {
        cd.waitSampling()
      }
      n += 1
    }
    (idx, completedNextCycle)
  }

  test("MMU-off store allocates precise=True and withholds completion at alloc", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val (cd, mem, ptmem) = initDut(dut)
      val base = 0x1000L
      seed(dut, cd, preg = 10, value = base)
      seed(dut, cd, preg = 11, value = 0xDEADBEEFL)
      // MMU stays disabled (initDut default) — mmuEnable=False alone must force
      // fastStore=False regardless of cache mode (DtlbPlugin's MMU-off response is
      // always WRITETHROUGH+ready).
      issueStore(dut, cd, basePreg = 10, disp = 0, dataPreg = 11, Size.LONG, robId = 5)
      val (idx, completedNextCycle) = waitAlloc(dut, cd, robId = 5)
      assert(idx >= 0, "MMU-off store must still allocate into the SQ")
      assert(!completedNextCycle, "MMU-off (precise-path) store must NOT drive completionPort right after allocating")
      cd.waitSampling(2)
      assert(dut.eu.logic.sq.robIds(idx).toInt == 5, "sanity: allocated entry belongs to this store")
      assert(dut.eu.logic.sq.precises(idx).toBoolean, "MMU-off store must classify precise=True")
      // Known intermediate state (Task P2.2): no drain trigger exists yet, so this
      // precise-path store's ROB completion never arrives. Confirm it STAYS withheld
      // over a further bounded window (not just the alloc cycle) — the expected
      // "hangs, doesn't crash" regression shape, not a full hang (bounded here).
      var completedLater = false
      for (_ <- 0 until 40) {
        if (dut.src.logic.cValid.toBoolean && dut.src.logic.cRob.toInt == 5) completedLater = true
        cd.waitSampling()
      }
      assert(!completedLater, "precise-path store completion must stay withheld (no drain trigger built yet — Task P2.4)")
    }
  }

  test("MMU-on WRITETHROUGH-page store with DE=1 allocates precise=False and completes at alloc, as before", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val (cd, mem, ptmem) = initDut(dut)
      val base = 0x2000L
      val vpn  = (base >> 12) & 0xfffff
      buildResidentWritethroughPage(ptmem, base, ppn = vpn)   // identity PPN=VPN
      dut.ctrl.logic.mmuEnable #= true
      dut.ctrl.logic.urp #= ROOT
      dut.ctrl.logic.srp #= ROOT
      dut.cacheCtrl.logic.dcacheEnabled #= true
      seed(dut, cd, preg = 10, value = base)
      seed(dut, cd, preg = 11, value = 0xCAFEBABEL)
      issueStore(dut, cd, basePreg = 10, disp = 0, dataPreg = 11, Size.LONG, robId = 6)
      val (idx, completedNextCycle) = waitAlloc(dut, cd, robId = 6)
      assert(idx >= 0, "store must allocate into the SQ")
      assert(completedNextCycle, "fast-path store must drive completionPort right after allocating (unchanged from before P2.2)")
      cd.waitSampling(2)
      assert(dut.eu.logic.sq.robIds(idx).toInt == 6, "sanity: allocated entry belongs to this store")
      assert(!dut.eu.logic.sq.precises(idx).toBoolean, "MMU-on WRITETHROUGH DE=1 store must classify precise=False")
    }
  }

  // Task P5.6: CACR.DE=0 must mean literally fully-uncached, matching real 68040
  // silicon -- effectiveMode = DE ? pageMode : INHIBITED for EVERY data access,
  // regardless of the page's own attribute. This is the direct counterpart of the
  // DE=1 test immediately above: same MMU-on setup, but the page is COPYBACK (not
  // WRITETHROUGH) and DE is poked to 0. If DE=0 correctly overrides the page's own
  // COPYBACK classification, s2Cmode must read INHIBITED and the store must
  // classify precise=True (exactly like a genuinely architecturally-INHIBITED page
  // would) -- NOT precise=False just because the page itself is cacheable.
  test("MMU-on COPYBACK-page store with DE=0 classifies precise=True and s2Cmode=INHIBITED (DE overrides page attribute)", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val (cd, mem, ptmem) = initDut(dut)
      val base = 0x3000L
      val vpn  = (base >> 12) & 0xfffff
      buildResidentCopybackPage(ptmem, base, ppn = vpn)   // identity PPN=VPN
      dut.ctrl.logic.mmuEnable #= true
      dut.ctrl.logic.urp #= ROOT
      dut.ctrl.logic.srp #= ROOT
      dut.cacheCtrl.logic.dcacheEnabled #= false   // DE=0: literally fully uncached
      seed(dut, cd, preg = 10, value = base)
      seed(dut, cd, preg = 11, value = 0xFACEFEEDL)
      issueStore(dut, cd, basePreg = 10, disp = 0, dataPreg = 11, Size.LONG, robId = 7)
      val (idx, completedNextCycle) = waitAlloc(dut, cd, robId = 7)
      assert(idx >= 0, "store must allocate into the SQ")
      assert(!completedNextCycle, "DE=0 (precise-path) store must NOT drive completionPort right after allocating")
      cd.waitSampling(2)
      assert(dut.eu.logic.sq.robIds(idx).toInt == 7, "sanity: allocated entry belongs to this store")
      assert(dut.eu.logic.sq.precises(idx).toBoolean, "MMU-on COPYBACK DE=0 store must classify precise=True -- DE=0 overrides the page's own COPYBACK attribute")
      assert(dut.eu.logic.s2Cmode.toEnum == m68k040.cache.CacheMode.INHIBITED,
        s"DE=0 must fold into s2Cmode as INHIBITED regardless of the page's own COPYBACK attribute, got ${dut.eu.logic.s2Cmode.toEnum}")
    }
  }

  // ── Task P2.5 post-review fix: `liveCompletionFires` same-cycle collision ────────
  // (LsEuPlugin.scala's deferred-completion replay: `deferCompletion`/`pendMem`/
  // `pendReady`/`pendApply`/`liveCompletionFires`). If the live EU pipe's own
  // `captureCompletion`/`captureFault` and a pending replay entry BOTH want the
  // shared `comp*` stage on the SAME cycle, the replay must yield and retry the NEXT
  // available cycle -- never drop the entry.
  //
  // This directed test engineers that exact collision: it drives the SQ's real
  // precise-path at-head drain (`robHeadIn`/`robHeadValidIn`, normally supplied by
  // RobPlugin -- see the `simPublic()` taps added to those pass-throughs for exactly
  // this purpose) for a full BATCH of precise stores, WHILE a continuous,
  // fully-deterministic LEA train (LEA never translates / never touches the D-cache
  // or AXI at all -- see `issueLea`'s doc comment: a fixed period-3 `liveCompletionFires`
  // pulse train) runs concurrently through the same single-outstanding EU pipe. A
  // precise store's drain-confirm cycle (`sq.io.sqCompletion`) is timed by the
  // D-cache write path's randomized AXI-ready handshake (`BehavioralMemAgent`'s
  // `StreamReadyRandomizer`s) -- running a whole batch (one per SQ slot, 8 independent
  // draws) with the LEA train active throughout reliably produces at least one
  // exact-cycle collision for a FIXED sim seed. Pinning the seed makes the entire run
  // (including every AXI-ready draw) bit-for-bit reproducible, so this is
  // deterministic on every future run, not flaky -- `collisionSeen` is asserted
  // explicitly so a future change that accidentally stops exercising the collision
  // path fails loudly instead of silently passing a vacuous test.
  test("liveCompletionFires collision: a same-cycle live capture defers (never drops) the pending SQ-drain replay", VerilatorTest) {
    simConfig.compile(new Dut).doSim(2) { dut =>
      val (cd, mem, ptmem) = initDut(dut)
      val base = 0x5000L
      seed(dut, cd, preg = 10, value = base)          // store base
      seed(dut, cd, preg = 11, value = 0xC001C0DEL)   // store data
      seed(dut, cd, preg = 12, value = 0x9000L)       // LEA base (unrelated address space)

      val numStores   = 8   // == the SQ's own depth: maximizes independent drain-latency draws
      val storeRobIds = (0 until numStores).map(20 + _)

      // Phase 1: allocate every precise store UP FRONT (robHeadValidIn stays False --
      // no drain trigger yet), one at a time through the single-outstanding EU pipe.
      for ((robId, i) <- storeRobIds.zipWithIndex) {
        issueStore(dut, cd, basePreg = 10, disp = i * 4, dataPreg = 11, Size.LONG, robId = robId)
      }
      // A few extra cycles of margin: `iReady` returning for store N only guarantees
      // store N-1 has already allocated, not that store N itself has (its own
      // translate/resolve still needs a couple more cycles) -- wait for it to settle.
      cd.waitSampling(10)
      assert(dut.eu.logic.sq.io.full.toBoolean, s"all $numStores stores must be resident (SQ full) before draining starts")

      // Phase 2: start the SQ's real background drain (auto-track whichever entry is
      // CURRENTLY at the ring head -- entries drain strictly in order) AND a
      // continuous LEA train through the SAME EU pipe, concurrently, via forks.
      val doneFlag = new java.util.concurrent.atomic.AtomicBoolean(false)
      fork {
        while (!doneFlag.get()) {
          val h = dut.eu.logic.sq.head.toInt
          dut.wire.logic.iRobHeadIn      #= dut.eu.logic.sq.robIds(h).toInt
          dut.wire.logic.iRobHeadValidIn #= dut.eu.logic.sq.valids(h).toBoolean
          cd.waitSampling()
        }
      }
      fork {
        var i = 0
        while (!doneFlag.get()) {
          issueLea(dut, cd, basePreg = 12, robId = 40 + (i % 8))
          i += 1
        }
      }

      // Observe: every precise store's sqCompletionPort pulse (must land EXACTLY
      // once each, never dropped/duplicated even across a collision), and whether a
      // genuine liveCompletionFires collision with a wants-to-apply pending entry was
      // observed (the interesting path this test exists to exercise).
      val seenCounts = scala.collection.mutable.Map[Int, Int]().withDefaultValue(0)
      var collisionSeen = false
      var n = 0
      val maxCycles = 4000
      while (seenCounts.keySet.size < numStores && n < maxCycles) {
        val wantsApply =
          (dut.eu.logic.pendApply.toInt == dut.eu.logic.pendReady.toInt && dut.eu.logic.sq.io.sqCompletion.valid.toBoolean) ||
          (dut.eu.logic.pendApply.toInt != dut.eu.logic.pendReady.toInt)
        if (wantsApply && dut.eu.logic.liveCompletionFires.toBoolean) collisionSeen = true
        if (dut.wire.logic.oSqCompValid.toBoolean) {
          val rid = dut.wire.logic.oSqCompPayload.toInt
          seenCounts(rid) += 1
        }
        cd.waitSampling()
        n += 1
      }
      doneFlag.set(true)
      cd.waitSampling(4)

      assert(n < maxCycles, s"timed out waiting for all $numStores precise-store completions (only saw ${seenCounts.keySet.size})")
      assert(collisionSeen,
        "test failed to engineer a liveCompletionFires collision with a wants-to-apply pending entry -- " +
        "adjust numStores/seed (this assertion is intentional: it proves the interesting retry path was " +
        "actually exercised, not merely inferred)")
      assert(storeRobIds.forall(seenCounts.contains),
        s"every store's robId must be observed via sqCompletionPort, got ${seenCounts.keySet}")
      assert(storeRobIds.forall(r => seenCounts(r) == 1),
        "every precise store's sqCompletionPort pulse must fire EXACTLY once (no drop, no duplicate) " +
        s"even across a liveCompletionFires collision, got $seenCounts")
    }
  }

  // ── P2.7 ROOT-CAUSE regression test: `pendMem` and the SQ ring are a lock-step PAIR.
  // A flush landing on the EXACT cycle a precise store's alloc arm fires must drop BOTH
  // the SQ alloc (StoreQueue's own `when(io.alloc.valid && !io.flush)`) AND the pendMem
  // push -- otherwise the two streams desynchronize by one FOREVER and, from then on,
  // every precise store's drain replays the WRONG pendMem entry: ROB completion port 4
  // fires for a stale robId while the real store's robId never completes (ROB head
  // parks -> HANG), and the stale entry's An-auto/A7/NZVC writeback lands with the
  // wrong data (WRONG ANSWER). This is what hung 15 ported-corpus tests.
  //
  // The pre-fix code believed the separate `when(sqFlushSig) { pendPush :=
  // pendReadyAfterThisCycle }` rollback covered this. It cannot: that rollback is a
  // PLAIN component statement while the push lives in a `StateMachine` state body, and
  // SpinalHDL elaborates StateMachine bodies from a pre-pop task -- AFTER every plain
  // statement -- so the FSM's `pendPush := pendPush + 1` is emitted last and silently
  // overrides the rollback (visible directly in the generated Verilog). `poisoned`
  // cannot cover it either: it is a Reg SET BY this same flush pulse, so it only reads
  // True from the following cycle on.
  //
  // Reverting the `when(!sqFlushSig)` gate in `deferCompletion` makes BOTH assertions
  // below fail (the pointer-skew one immediately, then the wrong-robId one) -- confirmed
  // by revert-and-rerun.
  test("P2.7: a flush on a precise store's alloc cycle drops the pendMem push in lock-step with the SQ alloc", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val (cd, mem, ptmem) = initDut(dut)
      seed(dut, cd, preg = 10, value = 0x6000L)       // store base
      seed(dut, cd, preg = 11, value = 0xFEEDFACEL)   // store data

      // MMU off + DE=0 (initDut defaults) => every store is a PRECISE store.
      assert(dut.eu.logic.pendPush.toInt == dut.eu.logic.pendReady.toInt,
        "precondition: pend FIFO starts balanced")

      // Sweep the flush's cycle offset relative to the store's issue handshake so one
      // iteration provably lands the flush on the EXACT cycle the store's alloc arm
      // fires -- the only offset that reaches the bug. (Offsets before it hit the
      // multi-cycle `poisoned` path; offsets after it hit the multi-cycle `pendPush :=
      // pendReadyAfterThisCycle` rollback. Both of those were already correct; it is
      // only the coincidence cycle that the rollback structurally cannot reach.)
      // A sweep rather than a hand-tuned constant because the LS EU's issue->alloc
      // latency is an implementation detail that may legitimately change: the loop
      // ASSERTS that the coincidence was actually observed, so it can never pass
      // vacuously if that latency moves. NOTE the one-cycle sim offset: a `#=` poke
      // issued right after `waitSampling()` becomes visible to the design on the
      // FOLLOWING cycle, so the coincidence is checked after the poke's own
      // `waitSampling`, not before it.
      var coincidenceOffsets = List.empty[Int]
      var skewOffsets        = List.empty[Int]
      for (k <- 0 until 10) {
        issueStore(dut, cd, basePreg = 10, disp = 0, dataPreg = 11, Size.LONG, robId = 5)
        if (k > 0) cd.waitSampling(k)
        dut.src.logic.iSqFlush #= true
        cd.waitSampling()
        // The flush is in effect for THIS cycle. If the alloc arm is firing in the same
        // cycle, this iteration is the engineered coincidence.
        if (dut.eu.logic.sq.io.alloc.valid.toBoolean && dut.eu.logic.sq.io.flush.toBoolean) {
          coincidenceOffsets ::= k
        }
        dut.src.logic.iSqFlush #= false
        cd.waitSampling(10)
        // Whatever the offset, a flush squashes this (uncommitted, precise) store --
        // either by dropping its alloc or by squashing the entry it just made -- so no
        // SQ entry may survive, and pendMem MUST be back in lock-step with it.
        assert(!dut.eu.logic.sq.valids.exists(_.toBoolean),
          s"offset k=$k: the flush must leave NO resident SQ entry")
        if (dut.eu.logic.pendPush.toInt != dut.eu.logic.pendReady.toInt) skewOffsets ::= k
      }
      assert(coincidenceOffsets.nonEmpty,
        "the sweep never landed a flush on the store's actual SQ-alloc cycle -- this test would " +
        "pass vacuously; widen the offset range (the LS EU's issue->alloc latency must have moved)")
      assert(skewOffsets.isEmpty,
        s"P2.7 FIX: a flush coincident with a precise store's SQ alloc (observed at offsets " +
        s"$coincidenceOffsets) dropped the SQ alloc but NOT the pendMem push, leaving pendPush " +
        s"skewed from pendReady at offsets $skewOffsets. pendMem and the SQ ring are a lock-step " +
        "pair; a skew here mis-attributes EVERY later precise store's completion to the wrong " +
        "robId, permanently (ROB head parks -> HANG; stale writeback -> WRONG ANSWER)")

      // End-to-end consequence check: the NEXT precise store must have its OWN robId
      // replayed out sqCompletionPort. With the skew, its drain would replay a squashed
      // (robId 5) pendMem entry instead.
      issueStore(dut, cd, basePreg = 10, disp = 8, dataPreg = 11, Size.LONG, robId = 7)
      var m = 0
      while (!dut.eu.logic.sq.valids.exists(_.toBoolean) && m < 200) { cd.waitSampling(); m += 1 }
      assert(m < 200, "store B never became resident in the SQ")
      // Park the ROB head on store B's robId so its at-head precise drain launches.
      val h = dut.eu.logic.sq.head.toInt
      dut.wire.logic.iRobHeadIn      #= dut.eu.logic.sq.robIds(h).toInt
      dut.wire.logic.iRobHeadValidIn #= true

      var seenRid = -1
      var k = 0
      while (seenRid < 0 && k < 400) {
        if (dut.wire.logic.oSqCompValid.toBoolean) seenRid = dut.wire.logic.oSqCompPayload.toInt
        cd.waitSampling(); k += 1
      }
      assert(seenRid >= 0, "store B's precise at-head drain never produced a completion")
      assert(seenRid == 7,
        s"P2.7 FIX: store B's drain must complete its OWN robId 7, got $seenRid -- a value of 5 " +
        "is the flushed store A's stale pendMem entry being replayed under B's drain (the exact " +
        "mis-attributed completion that hung 15 ported tests)")
    }
  }
}
