package m68k040.exception

import m68k040.{M68kParams, M68kSim, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.cache.{CacheMode, DcachePlugin, DcacheService, DLoadToken}
import m68k040.mmu.DIdentityTranslationPlugin
import m68k040.decode.DecOp
import m68k040.rename.RenamedUop
import m68k040.rob.{RobPlugin, RenameUopSourcePlugin, RobAllocDriverPlugin, RenameCommitSinkPlugin, CommitTraceSinkPlugin}
import m68k040.isa.{Cluster, Size}
import m68k040.ls.BehavioralMemAgent
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** Regression for a real, currently-live silent-corruption bug found as a byproduct
  * of unrelated design-review work on the walker/DcacheService passthrough spec.
  *
  * ROOT CAUSE. `ExceptionUnit`'s `dcLoadRsp` is wired STRAIGHT off `DcachePlugin`'s
  * single, UNTAGGED `loadRsp` Flow (see `FullCoreSynth`: `exc.dcLoadRsp.valid :=
  * dc.loadRsp.valid`) -- the very same broadcast `LsEuPlugin`'s ordinary load pipe
  * also watches. Nothing arbitrates between the two consumers on any given cycle;
  * each independently decides for itself whether an arriving response is its own.
  * `LsEuPlugin` uses "poison-and-drain": a flushed load already accepted by the
  * D-cache before the flush lands keeps draining and its response is still claimed
  * (and discarded) by `LsEuPlugin`'s own in-order aligned queue -- but that
  * consumption is NOT exclusive. `ExceptionUnit`'s `E_DRAIN`/`R_DRAIN` states
  * (frame/vector-fetch and RTE-pop entry) used to wait ONLY on `sqDrained` before
  * grabbing the D-cache load port for their OWN held command (`ldoValidReg`) --
  * never on any load-side quiesce condition. `DcachePlugin`'s `REPLAY` state
  * re-launches a filled read AND transitions back to `IDLE` in the SAME cycle, so
  * `loadCmdPort.ready` (state===IDLE-gated, no `ldS1Valid`/`ldS2Valid` awareness)
  * rises ONE CYCLE BEFORE that flushed load's own response actually lands. If
  * `ExceptionUnit`'s own held command fires into that exact window, its very next
  * FSM state (`E_VECWAIT`/`R_SRWAIT`/...) samples `dcLoadRsp.valid` COMPLETELY
  * UNQUALIFIED and silently captures the stale, unrelated data as if it were its
  * own genuine response.
  *
  * WHY THIS SPEC TARGETS THE RTE PATH (`R_DRAIN`/`R_SRREQ`/`R_SRWAIT`) RATHER THAN
  * ENTRY'S VECTOR FETCH. Both are gated by the identical fix (`E_DRAIN`/`R_DRAIN`
  * both now wait on `sqDrained && dcQuiesced`). The ENTRY path was tried first, but
  * its own overhead before it ever tries a load (`E_DRAIN` -> a FOUR-WORD frame
  * PUSH, each word a real write-through D-cache store waiting on its own AXI B
  * response, -> `E_VECREQ`) is -- empirically, across every memory-latency
  * configuration tried -- always substantially LONGER than a single poisoning
  * load's own miss-to-response window, and grows in lockstep with any attempt to
  * slow that poisoning load down (both ultimately bottleneck on the same AXI
  * model), so the two windows never overlap. RTE has no such gap: `R_DRAIN` has
  * nothing to push, and `R_SRREQ` -- the FIRST load of the pop -- is reached only
  * a couple of cycles after RTE retires. That short, close-to-fixed overhead is
  * exactly what a poisoning load's own ordinary miss window can still be
  * outstanding against with a small, realistic lead time -- and it exercises the
  * exact same wiring seam (`exc.dcLoadCmd`/`exc.dcLoadRsp`) and the exact same fix.
  *
  * This spec reproduces that RTE instance of the race end-to-end against the REAL
  * `RobPlugin`/`ExceptionUnit`/`DcachePlugin`, with a purpose-built second
  * "requester" standing in for `LsEuPlugin`'s own poisoned, in-flight load --
  * driven directly onto the SAME physical `loadCmd`/`loadRsp` port `ExceptionUnit`
  * shares with the real LS EU in production, which is the actual seam this bug
  * lives on. The requester's load is accepted STRICTLY BEFORE the RTE retires
  * (mirroring the real constraint: `LsEuPlugin`'s `alignedSendValid` term
  * `!excActive` means any load that ends up poisoned-and-draining must already
  * have been accepted before `excActive` rises, which happens essentially at
  * retire time). Exact single-cycle alignment against the memory model's
  * randomized AXI ready timing cannot be hand-computed, so (mirroring this file's
  * `DcacheDrainRefillRaceSpec` sweep idiom for the identical class of problem) the
  * lead time between "inject the poisoning load" and "retire the RTE" is swept
  * across a range; the assertion must hold for EVERY offset in it. */
class ExceptionUnitStaleLoadRspRaceSpec extends AnyFunSuite {

  /** Wires `ExceptionUnit`'s D-cache ports to a real `DcachePlugin`, exactly like
    * `RteSpec`'s `ExcDcacheWiring` -- plus a second, test-driven "poison load"
    * requester arbitrated onto the SAME physical `loadCmd`/`loadRsp` port,
    * standing in for `LsEuPlugin`'s own in-flight, soon-to-be-flushed load. */
  class RaceWiring extends FiberPlugin {
    val logic = during build new Area {
      val robP  = host[RobPlugin]
      val dc    = host[DcacheService]
      val xlate = host[m68k040.services.DTranslationService]
      val exc   = robP.logic.exc

      xlate.req.valid              := False
      xlate.req.payload.vpn        := U(0, 20 bits)
      xlate.req.payload.supervisor := False
      xlate.req.payload.write      := False
      xlate.req.payload.token      := U(0, 8 bits)
      xlate.rsp.ready              := True

      dc.store.valid    := exc.dcStore.valid
      dc.store.payload  := exc.dcStore.payload
      exc.dcStore.ready := dc.store.ready
      exc.dcStoreAck    := dc.storeAck

      // CRITICAL: real wiring, mirroring FullCoreSynth's
      // `exc.dcQuiesced := dc.maintQuiesced`. `ExceptionUnit`'s own default
      // (`dcQuiesced := True`) exists so a DUT that never wires it still
      // elaborates -- but that default would make the fix under test INERT here.
      exc.dcQuiesced := dc.maintQuiesced

      // ---- the "poison load" requester (LsEuPlugin surrogate) ----
      val injValid = in(Bool())
      val injVaddr = in(UInt(32 bits))
      val injSize  = in(Size())
      val injCmode = in(CacheMode())
      val injReady = out(Bool())

      dc.loadCmd.valid             := injValid || exc.dcLoadCmd.valid
      dc.loadCmd.payload.vaddr     := Mux(injValid, injVaddr, exc.dcLoadCmd.payload.vaddr)
      dc.loadCmd.payload.paddr     := Mux(injValid, injVaddr, exc.dcLoadCmd.payload.paddr)
      dc.loadCmd.payload.size      := Mux(injValid, injSize,  exc.dcLoadCmd.payload.size)
      dc.loadCmd.payload.cacheMode := Mux(injValid, injCmode, exc.dcLoadCmd.payload.cacheMode)
      dc.loadCmd.payload.token     := Mux(injValid,
        U(0x40, DLoadToken.Width bits), exc.dcLoadCmd.payload.token)
      injReady            := dc.loadCmd.ready && injValid
      exc.dcLoadCmd.ready := dc.loadCmd.ready && !injValid

      // Broadcast, exactly like the real (buggy) wiring: BOTH consumers see every
      // response, un-arbitrated. This is the actual mechanism under test, not a
      // simplification of it.
      exc.dcLoadRsp.valid   := dc.loadRsp.valid
      exc.dcLoadRsp.payload := dc.loadRsp.payload
      exc.dcLoadBusy        := dc.loadBusy

      // Witness (diagnostic only, not asserted on): did the exception unit's own
      // held load command fire while the D-cache had NOT actually settled? This is
      // the exact unsafe window the bug lives in -- reachable pre-fix, structurally
      // unreachable post-fix (`R_DRAIN` cannot leave without `dcQuiesced`).
      val raceWindowHit = out(Bool())
      raceWindowHit := exc.dcLoadCmd.fire && !dc.maintQuiesced
    }
  }

  class Dut extends Component {
    val db    = new Database
    val host  = db on (new PluginHost)
    val rsrc  = new RenameUopSourcePlugin
    val drv   = new RobAllocDriverPlugin
    val rob   = new RobPlugin
    val csink = new RenameCommitSinkPlugin
    val tsink = new CommitTraceSinkPlugin
    val dtlb  = new DIdentityTranslationPlugin
    val dcache = new DcachePlugin()
    val wire  = new RaceWiring
    db.on { host.asHostOf(Seq[FiberPlugin](
      new ParamPlugin(M68kParams()), rsrc, drv, rob, csink, tsink, dtlb, dcache, wire)) }
  }

  def pokeRu(u: RenamedUop, valid: Boolean = true, pc: Long = 0, isRte: Boolean = false): Unit = {
    u.valid #= valid
    u.pc #= pc; u.nextPc #= pc + 2; u.faultUsesNextPc #= false
    u.op #= DecOp.MOVE; u.cluster #= Cluster.INT; u.size #= Size.LONG
    u.useImm #= false; u.imm #= 0
    u.isBranch #= false; u.cond #= 0; u.branchDisp #= 0
    u.unimplemented #= false
    u.dstArch #= 0
    u.psrcA #= 0; u.psrcAValid #= false
    u.psrcB #= 0; u.psrcBValid #= false
    u.pdst #= 0; u.pdstValid #= false; u.pdstOld #= 0
    u.pNzvcSrc #= 0; u.readsNzvc #= false
    u.pNzvcDst #= 0; u.writesNzvc #= false; u.pNzvcOld #= 0
    u.pXSrc #= 0; u.readsX #= false
    u.pXDst #= 0; u.writesX #= false; u.pXOld #= 0
    u.faulted #= false; u.faultVector #= 0; u.isRte #= isRte
    u.debugBreakValid #= false; u.debugBreakSlot #= 0
  }

  def simConfig = M68kSim().withVerilator

  lazy val compiled = simConfig.compile(new Dut)

  private val ssp0    = 0x00100000L
  // RTE returns to user mode (S=0) at a distinct, recognizable PC -- the frame's
  // REAL, correct contents, preloaded distinctly from the poisoning line's own
  // data so the two can never be confused.
  private val retSr   = 0x0004
  private val retPc   = 0x40001234L
  private val poisonAddr = 0x00090000L
  // The poisoned SR word this bug would splice into `popSr` if it strikes: bits
  // [15:8] would land in the CCR/undefined byte and bit 5 (S) would come from
  // poisonWord's bit 5 -- 0xBEEF has S=1 (supervisor), the OPPOSITE of the real
  // frame's S=0, so if the corrupted value is used to decide the post-RTE S bit
  // this is unmistakable in the observed architectural state, not just numerically
  // different.
  private val poisonWord = 0xDEADBEEFL

  /** Runs the whole scenario once for a given inject-lead-time `delay` (in cycles
    * BEFORE the RTE retires). Returns the observed post-RTE PC (the architectural
    * effect of whatever `ExceptionUnit` captured as `R_SRREQ`'s own response) and
    * whether the unsafe window (`raceWindowHit`) was ever observed. */
  case class RunResult(pc: Long, sr: Long, raceWindowHit: Boolean)

  def runOnce(name: String, delay: Int): RunResult = {
    var result: RunResult = null
    compiled.doSim(name, 1) { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      val dmem = new BehavioralMemAgent(dut.dcache.logic.axi, cd)

      dut.rsrc.logic.src.valid #= false
      dut.rsrc.logic.u1v #= false
      dut.rob.logic.flush.valid #= false
      for (c <- dut.rob.logic.completion) { c.valid #= false; c.payload #= 0 }
      dut.rob.logic.branchCompletion.valid #= false
      pokeRu(dut.rsrc.logic.src.payload(0), valid = false)
      pokeRu(dut.rsrc.logic.src.payload(1), valid = false)
      dut.wire.logic.injValid #= false
      dut.wire.logic.injVaddr #= 0
      dut.wire.logic.injSize #= Size.LONG
      dut.wire.logic.injCmode #= CacheMode.WRITETHROUGH
      cd.waitSampling(3)

      dut.rob.logic.exc.ss.isp #= ssp0
      dut.rob.logic.exc.ss.usp #= 0L
      def pokeBE16(a: Long, w: Int): Unit = { dmem.pokeByte(a, (w >> 8) & 0xff); dmem.pokeByte(a + 1, w & 0xff) }
      def pokeBE32(a: Long, w: Long): Unit =
        for (i <- 0 until 4) dmem.pokeByte(a + i, ((w >> (8 * (3 - i))) & 0xff).toInt)
      // The real, correct format-$0 frame at SSP: [0]=SR, [2]=PChi, [4]=PClo, [6]=fmt.
      pokeBE16(ssp0 + 0, retSr)
      pokeBE16(ssp0 + 2, ((retPc >> 16) & 0xffff).toInt)
      pokeBE16(ssp0 + 4, (retPc & 0xffff).toInt)
      pokeBE16(ssp0 + 6, 0x0010)
      // The poisoning load's OWN cold line -- deliberately far from the real frame
      // (a different page entirely) so there is no possibility of accidental
      // overlap corrupting the positive-control scenario itself.
      pokeBE32(poisonAddr, poisonWord)
      cd.waitSampling(2)

      var raceWindowHit = false
      val monitor = fork {
        while (true) {
          cd.waitSampling()
          if (dut.wire.logic.raceWindowHit.toBoolean) raceWindowHit = true
        }
      }

      // Fire the poisoning load FIRST, then retire the RTE `delay` cycles LATER --
      // exactly the real ordering constraint: `LsEuPlugin`'s own `!excActive` gate
      // on new sends means any load that will end up poisoned-and-draining MUST
      // already have been accepted by the D-cache strictly BEFORE `excActive`
      // rises (which happens essentially at retire time). It is a cold miss
      // (nothing has been loaded yet), so it drives the D-cache through its
      // ordinary IDLE -> REFILL -> REPLAY sequence, exactly like a load
      // `LsEuPlugin` had already sent and is now draining-and-poisoning after
      // `excActive` rose in the real system.
      dut.wire.logic.injVaddr #= poisonAddr
      dut.wire.logic.injSize #= Size.LONG
      dut.wire.logic.injCmode #= CacheMode.WRITETHROUGH
      dut.wire.logic.injValid #= true
      cd.waitSamplingWhere(dut.wire.logic.injReady.toBoolean)
      dut.wire.logic.injValid #= false
      cd.waitSampling(delay)

      pokeRu(dut.rsrc.logic.src.payload(0), pc = 0x40000020L, isRte = true)
      dut.rsrc.logic.src.valid #= true
      dut.rsrc.logic.u1v #= false
      cd.waitSamplingWhere(dut.rsrc.logic.src.ready.toBoolean)
      dut.rsrc.logic.src.valid #= false
      cd.waitSampling()
      dut.rob.logic.completion(0).valid #= true
      dut.rob.logic.completion(0).payload #= 0
      cd.waitSampling()
      dut.rob.logic.completion(0).valid #= false

      var n = 0; var redirected = false
      while (!redirected && n < 500) {
        if (dut.rob.logic.doFlushReg.toBoolean) redirected = true
        n += 1; cd.waitSampling()
      }
      assert(redirected, s"RTE never redirected (delay=$delay)")
      val pc = dut.rob.logic.flushPcReg.toLong & 0xffffffffL
      cd.waitSampling(5)
      // The architectural effect of the popped SR: S (bit5) selects which stack
      // bank is live post-RTE. Read as a plain 16-bit value with the system byte
      // in the high half so a corrupted low-order poison word (S=1, supervisor)
      // is trivially distinguishable from the real frame's S=0 (user).
      val sr = dut.rob.logic.exc.ss.srSys.toLong & 0xff

      result = RunResult(pc, sr, raceWindowHit)
    }
    result
  }

  test("RTE's SR-pop load must not consume a flushed, in-flight LS load's stale " +
       "D-cache response (R_DRAIN must wait for real load-side quiescence, " +
       "mirroring the store side's sqDrained)", VerilatorTest) {
    val offsets = 0 until 20
    // Real frame: PC=retPc, system byte (SR[15:8], incl. S at bit5) = 0x00 (user).
    val expectedSysByte = (retSr >> 8) & 0xff
    val failures = scala.collection.mutable.ArrayBuffer.empty[(Int, Long, Long)]
    var anyRaceWindow = false
    for (delay <- offsets) {
      val r = runOnce(s"rteStaleLoadRsp_$delay", delay)
      if (r.raceWindowHit) anyRaceWindow = true
      if (r.pc != retPc || r.sr != expectedSysByte) failures += ((delay, r.pc, r.sr))
    }
    if (failures.nonEmpty) {
      val detail = failures.map { case (d, pc, sr) =>
        f"delay=$d -> pc=0x$pc%08x sysByte=0x$sr%02x"
      }.mkString("; ")
      fail(f"RTE popped a corrupted frame for ${failures.size}/${offsets.size} offsets " +
        f"(expected pc=0x$retPc%08x sysByte=0x$expectedSysByte%02x): $detail")
    }
    // Non-vacuity note (not asserted): `anyRaceWindow` records whether any offset
    // ever fired the exception unit's own load command while the D-cache was not
    // genuinely quiescent. Pre-fix this is expected true (and correlates with the
    // failures above); post-fix it is expected structurally false, since `R_DRAIN`
    // cannot leave without `dcQuiesced`. Left as a diagnostic rather than a hard
    // assertion so this test asserts exactly one thing -- correctness of the
    // architectural effect -- regardless of which internal signal proves it.
    ()
  }
}
