package m68k040.debug

import m68k040.{M68kParams, M68kSim, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.cache.{CacheMode, DcachePlugin, DcacheProbePlugin, FetchProbePlugin, IcachePlugin, IcacheSim}
import m68k040.decode.{DecOp, SysKind}
import m68k040.isa.{Cluster, Size}
import m68k040.mmu.{DIdentityTranslationPlugin, DtlbPlugin, DtlbProbePlugin, IdentityTranslationPlugin,
                    ItlbPlugin, ItlbProbePlugin, MmuControlPlugin}
import m68k040.rename.RenamedUop
import m68k040.rob.{RenameCommitSinkPlugin, RenameUopSourcePlugin, RobAllocDriverPlugin, RobPlugin}
import m68k040.sim.{DcacheClientMemAgent, WalkerDcacheSimIo}
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.database.Database
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.sim.SparseMemory
import org.scalatest.funsuite.AnyFunSuite

/** PROVES THE WINDOWED PERFORMANCE COUNTERS ARE NOT DEAD PROBES.
  *
  * Two of the registers this suite covers -- `OFF_MISPRED_COUNT` and `OFF_FLUSH_COUNT`
  * -- were DECLARED at Stage 1 and never driven. The regmap said so out loud ("zero
  * until a real producer exists") and they sat there for months reading a confident
  * zero. They are the fifth and sixth members of a family that already includes
  * `pc_live`, `exc_count`, `OFF_CYCLE_LO` (declared, never served, so a healthy 200 MHz
  * core reported a dead clock) and `wedge-status`, which the host REPL now refuses
  * outright because all-zero renders as "everything is IDLE" and on 2026-09-05 exactly
  * that fabricated line was used to RULE OUT a root cause.
  *
  * A performance counter is a worse offender than most, because ZERO IS A PLAUSIBLE
  * READING. "No mispredicts in this window" is a sentence a real machine can produce.
  * So nothing here is assumed:
  *
  *   1. CLEAN ZERO after a clear -- no reset garbage, no aliasing.
  *   2. A DISTINCT seeded value per counter, written into the actual register and read
  *      back through the REAL `dbg_axi` mux. Distinct per counter, so "reads back the
  *      right number" cannot be satisfied by accident.
  *   3. NO NEIGHBOUR ALIASING: with exactly one counter seeded, every other reads zero.
  *   4. THE CLEAR ACTUALLY CLEARS. Tested, never assumed: the sibling multi-hot work
  *      found its clear arm had landed in a READ-region `switch(arAddr)`, where it
  *      elaborated cleanly, read back perfectly and silently did nothing.
  *   5. THE FREEZE ACTUALLY FREEZES, and RUN actually resumes -- the freeze is what
  *      makes the 64-bit LO/HI pairs atomic, so if it does not work the pairs tear and
  *      the host has no way to tell.
  *   6. EACH PRODUCER IS EXERCISED FOR REAL against the REAL peer plugin -- a retiring
  *      ROB, a missing D-cache load, a missing I-cache fetch, a genuine three-level
  *      table walk -- and the counter is checked against an INDEPENDENT count of the
  *      same events (AXI AR fires, descriptor reads, OFF_INST_LO). A counter that
  *      merely moves is not proof; a counter that agrees with a second measurement is.
  *
  * Anything this suite does NOT exercise is reported as "wired, not measured" rather
  * than quietly claimed.
  */
class PerfCounterSpec extends AnyFunSuite {

  import DebugRegMap._

  // ── The whole windowed set, in one place ──────────────────────────────────────
  // (offset, human name, accessor into the CsrArea, is-64-bit-low-word)
  private val Counters32: Seq[(Int, String)] = Seq(
    OFF_MISPRED_COUNT     -> "mispred",
    OFF_FLUSH_COUNT       -> "flush",
    OFF_PERF_BRANCH       -> "branch",
    OFF_PERF_DC_MISS      -> "dc_miss",
    OFF_PERF_IC_MISS      -> "ic_miss",
    OFF_PERF_DTLB_WALK    -> "dtlb_walk",
    OFF_PERF_ITLB_WALK    -> "itlb_walk",
    OFF_PERF_STALL_RETIRE -> "stall_retire",
    OFF_PERF_STALL_DC     -> "stall_dc",
    OFF_PERF_STALL_WALK   -> "stall_walk").map { case (o, n) => (o, n) }

  private val AllWords: Seq[(Int, String)] =
    Counters32 ++ Seq(
      OFF_PERF_CYCLE_LO -> "cycle_lo", OFF_PERF_CYCLE_HI -> "cycle_hi",
      OFF_PERF_INST_LO  -> "inst_lo",  OFF_PERF_INST_HI  -> "inst_hi")

  // OFF_PERF_CTL encodings, mirrored from the .def so the test states them
  // independently of the RTL rather than importing a shared constant.
  private val CtlClearRun  = 0x3L
  private val CtlFreeze    = 0x0L
  private val CtlRun       = 0x2L
  private val CtlClearHold = 0x1L

  private def u(v: Long): Long = v & 0xFFFFFFFFL

  private def readAll(b: DbgAxiLite, cd: ClockDomain): Map[String, Long] =
    AllWords.map { case (off, nm) => nm -> u(DbgAxiDriver.read(b, cd, off)) }.toMap

  private def assertAllZero(b: DbgAxiLite, cd: ClockDomain, why: String): Unit =
    for ((off, nm) <- AllWords) {
      val v = u(DbgAxiDriver.read(b, cd, off))
      assert(v == 0L, f"$nm (offset 0x$off%05x) read 0x$v%08x, expected 0 -- $why")
    }

  /** Decode OFF_PERF_CTL. */
  private case class Ctl(raw: Long) {
    def run: Boolean = ((raw >> 1) & 1L) == 1L
    def nCounters: Int = ((raw >> 8) & 0xFFL).toInt
    def rob: Boolean = ((raw >> 16) & 1L) == 1L
    def dcache: Boolean = ((raw >> 17) & 1L) == 1L
    def icache: Boolean = ((raw >> 18) & 1L) == 1L
    def dtlb: Boolean = ((raw >> 19) & 1L) == 1L
    def itlb: Boolean = ((raw >> 20) & 1L) == 1L
    override def toString =
      f"ctl=0x$raw%08x run=$run counters=$nCounters producers{rob=$rob dc=$dcache ic=$icache dtlb=$dtlb itlb=$itlb}"
  }
  private def ctl(b: DbgAxiLite, cd: ClockDomain): Ctl =
    Ctl(u(DbgAxiDriver.read(b, cd, OFF_PERF_CTL)))

  private val PerfFeatureBit: Int = DebugRegMap.features.find(_._1 == "perf_counters").get._2

  /** `perf_counters` means "EVERY advertised performance counter has a real producer",
    * so the plugin withholds it unless ALL FIVE producer families are present. None of
    * the DUTs below hosts all five, so the bit must be LOW in every one of them -- which
    * is what proves the gate is wired rather than stuck high. Each individual producer's
    * presence bit IS proven true, in the DUT that hosts it. */
  private def assertPerfFeatureWithheld(b: DbgAxiLite, cd: ClockDomain, dutName: String): Unit = {
    val f = u(DbgAxiDriver.read(b, cd, OFF_FEATURES))
    assert(((f >> PerfFeatureBit) & 1L) == 0L,
      f"$dutName advertises perf_counters (OFF_FEATURES=0x$f%08x) but does not host every " +
      f"producer plugin. A capability bit that is stuck high is worse than one that is " +
      f"never set: it invites the host to trust a counter nobody drives.")
  }

  // ══════════════════════════════════════════════════════════════════════════════
  // 1. CSR PLUMBING: seeding, aliasing, clear, freeze -- with NO producers at all.
  //    This DUT hosts no ROB, no caches and no TLBs, so every producer-fed counter
  //    MUST stay at exactly zero while the cycle counter runs. That is the
  //    "clean zero when absent" case, and it is also the case in which a broken mux
  //    arm would be invisible -- hence the seeding.
  // ══════════════════════════════════════════════════════════════════════════════
  test("windowed perf CSRs: seed, alias, clear and freeze over the real dbg_axi path") {
    M68kSim().compile(new DebugCtrlDut(stageArg = 2, withCommitStubArg = true))
      .doSim("perf_csr_plumbing", 1) { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      val b = dut.axi
      DbgAxiDriver.idle(b)
      dut.dbg.logic.initDoneSeen #= false
      cd.waitSampling(30)

      val csr = dut.dbg.logic.csr

      // ---- the control word describes the block ----------------------------
      val c0 = ctl(b, cd)
      println(s"[perf-csr] $c0")
      assertPerfFeatureWithheld(b, cd, "the producerless standalone fixture")
      assert(c0.nCounters == 12,
        s"OFF_PERF_CTL[15:8] says ${c0.nCounters} implemented counters, expected 12. " +
        "The host uses this to decide whether the block exists at all; a wrong value " +
        "makes it either refuse a real block or print a table for an absent one.")
      assert(c0.run, "RUN must be 1 out of debug POR so a build behaves like the " +
        "free-running counters until a host deliberately takes a window")
      assert(!c0.rob && !c0.dcache && !c0.icache && !c0.dtlb && !c0.itlb,
        s"this DUT hosts NO producer plugins, but $c0 claims some are present. The " +
        "presence bitmap is the only thing standing between a zero that was measured " +
        "and a zero from a counter nobody drives.")

      // ---- 4 + 5. CLEAR and FREEZE, together --------------------------------
      DbgAxiDriver.write(b, cd, OFF_PERF_CTL, CtlClearHold)
      cd.waitSampling(10)
      assert(!ctl(b, cd).run, "writing OFF_PERF_CTL bit1=0 must FREEZE; it did not, so " +
        "every 64-bit LO/HI read below could tear and the window has no defined end")
      assertAllZero(b, cd, "a CLEAR+FREEZE was just written")

      // Frozen really means frozen: the cycle counter is the one lane that needs no
      // producer, so if the freeze is a no-op THIS is where it shows.
      val frozenCycle = u(DbgAxiDriver.read(b, cd, OFF_PERF_CYCLE_LO))
      cd.waitSampling(200)
      val frozenCycle2 = u(DbgAxiDriver.read(b, cd, OFF_PERF_CYCLE_LO))
      assert(frozenCycle == 0L && frozenCycle2 == 0L,
        s"OFF_PERF_CYCLE_LO moved $frozenCycle -> $frozenCycle2 across 200 clocks while " +
        "FROZEN. The RUN gate does not reach this counter, so a 'frozen' read pair is " +
        "not atomic after all and the freeze buys nothing.")

      // ---- 2 + 3. seed ONE counter at a time, distinct values, no aliasing ---
      // Seeding is legal here precisely because the counters are frozen: nothing can
      // overwrite the seed between the poke and the read.
      val seeds: Seq[(String, Long, Long => Unit)] = Seq(
        ("mispred",      0x11110001L, v => csr.perfMispred     #= BigInt(v)),
        ("flush",        0x22220002L, v => csr.perfFlush       #= BigInt(v)),
        ("branch",       0x33330003L, v => csr.perfBranch      #= BigInt(v)),
        ("dc_miss",      0x44440004L, v => csr.perfDcMiss      #= BigInt(v)),
        ("ic_miss",      0x55550005L, v => csr.perfIcMiss      #= BigInt(v)),
        ("dtlb_walk",    0x66660006L, v => csr.perfDtlbWalk    #= BigInt(v)),
        ("itlb_walk",    0x77770007L, v => csr.perfItlbWalk    #= BigInt(v)),
        ("stall_retire", 0x88880008L, v => csr.perfStallRetire #= BigInt(v)),
        ("stall_dc",     0x99990009L, v => csr.perfStallDc     #= BigInt(v)),
        ("stall_walk",   0xAAAA000AL, v => csr.perfStallWalk   #= BigInt(v)))
      val seedOffsets = Counters32.map(_._1)

      for (((nm, seed, poke), idx) <- seeds.zipWithIndex) {
        poke(seed)
        cd.waitSampling(4)
        val got = u(DbgAxiDriver.read(b, cd, seedOffsets(idx)))
        println(f"[perf-csr] $nm%-13s seeded=0x$seed%08x read=0x$got%08x")
        assert(got == seed,
          f"$nm: read 0x$got%08x, seeded 0x$seed%08x. The mux arm does not reach the " +
          f"intended register -- and this counter would then read a confident number " +
          f"that belongs to something else, or a confident zero.")
        for (((onm, _, _), oidx) <- seeds.zipWithIndex if oidx != idx) {
          val ov = u(DbgAxiDriver.read(b, cd, seedOffsets(oidx)))
          assert(ov == 0L,
            f"ALIASING: seeding $nm made $onm read 0x$ov%08x. Two offsets share a " +
            f"source, so a reading would name the wrong event.")
        }
        // Clear between seeds, and check the clear on the way through.
        DbgAxiDriver.write(b, cd, OFF_PERF_CTL, CtlClearHold)
        cd.waitSampling(6)
        val after = u(DbgAxiDriver.read(b, cd, seedOffsets(idx)))
        assert(after == 0L,
          f"$nm: still 0x$after%08x after a CLEAR write. A counter that cannot be " +
          f"zeroed cannot bound a window, which is the entire request.")
      }

      // The 64-bit pair, seeded as a full 64-bit value so LO/HI cannot be swapped.
      csr.perfCycle #= BigInt("0123456789ABCDEF", 16)
      csr.perfInst  #= BigInt("FEDCBA9876543210", 16)
      cd.waitSampling(4)
      assert(u(DbgAxiDriver.read(b, cd, OFF_PERF_CYCLE_LO)) == 0x89ABCDEFL, "cycle LO word")
      assert(u(DbgAxiDriver.read(b, cd, OFF_PERF_CYCLE_HI)) == 0x01234567L, "cycle HI word")
      assert(u(DbgAxiDriver.read(b, cd, OFF_PERF_INST_LO))  == 0x76543210L, "inst LO word")
      assert(u(DbgAxiDriver.read(b, cd, OFF_PERF_INST_HI))  == 0xFEDCBA98L, "inst HI word")
      DbgAxiDriver.write(b, cd, OFF_PERF_CTL, CtlClearHold)
      cd.waitSampling(6)
      assertAllZero(b, cd, "a CLEAR after seeding the 64-bit pairs")

      // ---- RUN resumes, and ONLY the producerless lanes stay at zero ---------
      DbgAxiDriver.write(b, cd, OFF_PERF_CTL, CtlRun)
      assert(ctl(b, cd).run, "writing bit1=1 must resume")
      cd.waitSampling(300)
      DbgAxiDriver.write(b, cd, OFF_PERF_CTL, CtlFreeze)
      cd.waitSampling(4)
      val ran = readAll(b, cd)
      println(s"[perf-csr] after 300 running clocks: cycle_lo=${ran("cycle_lo")}")
      assert(ran("cycle_lo") > 200L && ran("cycle_lo") < 1000L,
        s"OFF_PERF_CYCLE_LO = ${ran("cycle_lo")} after ~300 running clocks; expected a " +
        "few hundred. Either RUN does not resume, or this lane is not counting the clock.")
      assert(ran("cycle_hi") == 0L, "no wrap expected in 300 cycles")
      for ((off, nm) <- Counters32) {
        assert(ran(nm) == 0L,
          s"$nm = ${ran(nm)} on a DUT with NO producer plugin for it. A counter with no " +
          "producer must read a clean zero, not drift -- otherwise every zero elsewhere " +
          "is uninterpretable.")
      }
      assert(ran("inst_lo") == 0L && ran("inst_hi") == 0L,
        "no ROB in this DUT, so no macro can retire")
    }
  }

  // ══════════════════════════════════════════════════════════════════════════════
  // 2. ROB PRODUCERS, against the REAL RobPlugin: retired macros, mispredicts,
  //    flushes, retire stall.
  // ══════════════════════════════════════════════════════════════════════════════
  class RobPerfDut extends Component {
    val db = new Database
    val host = db on (new PluginHost)
    val rsrc = new RenameUopSourcePlugin
    val alloc = new RobAllocDriverPlugin
    val rob = new RobPlugin(detailedPerf = true)
    val commit = new RenameCommitSinkPlugin
    val dbg = new DebugCtrlPlugin(buildId = BigInt(0x50450001L), porCycles = 4, stage = 2,
      detailedPerf = true)
    db.on {
      host.asHostOf(Seq[FiberPlugin](new ParamPlugin(M68kParams()), rsrc, alloc, rob, commit, dbg))
    }
    def axi: DbgAxiLite = dbg.logic.dbgAxi
  }

  /** `RobPluginSpec.pokeRu`, copied because it is a private method of that suite. */
  private def pokeRu(u: RenamedUop, pc: Long = 0, dstArch: Int = 0,
                     pdst: Int = 0, pdstValid: Boolean = false, pdstOld: Int = 0,
                     isBranch: Boolean = false): Unit = {
    u.valid #= true
    u.pc #= pc
    u.lenWords #= 1
    u.faultUsesNextPc #= false
    u.op #= DecOp.MOVE
    u.cluster #= Cluster.INT
    u.size #= Size.LONG
    u.useImm #= false; u.imm #= 0
    u.isBranch #= isBranch; u.cond #= 0
    u.unimplemented #= false
    u.dstArch #= dstArch
    u.psrcA #= 0; u.psrcAValid #= false
    u.psrcB #= 0; u.psrcBValid #= false
    u.pdst #= pdst; u.pdstValid #= pdstValid; u.pdstOld #= pdstOld
    u.pNzvcSrc #= 0; u.readsNzvc #= false
    u.pNzvcDst #= 0; u.writesNzvc #= false; u.pNzvcOld #= 0
    u.pXSrc #= 0; u.readsX #= false
    u.pXDst #= 0; u.writesX #= false; u.pXOld #= 0
    u.faulted #= false; u.faultVector #= 0; u.isRte #= false
    u.sysOp #= false; u.sysKind #= SysKind.NONE; u.sysReadDir #= false
    u.needsSupervisor #= false
    u.firstOfInstr #= true
    u.lastOfInstr #= true
    u.debugBreakValid #= false
    u.debugBreakSlot #= 0
  }

  test("ROB perf producers are live: retired macros, mispredicts, flushes, retire stall",
       VerilatorTest) {
    M68kSim().compile(new RobPerfDut).doSim("perf_rob_producers", 1) { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      val b = dut.axi
      DbgAxiDriver.idle(b)
      dut.dbg.logic.initDoneSeen #= false
      dut.rsrc.logic.src.valid #= false
      dut.rsrc.logic.u1v #= false
      dut.rob.logic.completion(0).valid #= false
      dut.rob.logic.completion(1).valid #= false
      dut.rob.logic.flush.valid #= false
      dut.rob.logic.branchCompletion.valid #= false
      cd.waitSampling(30)

      val c = ctl(b, cd)
      println(s"[perf-rob] $c")
      assertPerfFeatureWithheld(b, cd, "the ROB-only fixture")
      assert(c.rob, s"the ROB producer presence bit must be set on a DUT that hosts the " +
        s"real RobPlugin and its DebugHistoryService; got $c")
      assert(!c.dcache && !c.icache && !c.dtlb && !c.itlb,
        s"no caches or TLBs in this DUT, but $c claims otherwise")

      // ---- window 1: two plain macros retire -------------------------------
      DbgAxiDriver.write(b, cd, OFF_PERF_CTL, CtlClearRun)
      cd.waitSampling(4)
      val instBefore = u(DbgAxiDriver.read(b, cd, OFF_INST_LO))

      pokeRu(dut.rsrc.logic.src.payload(0), pc = 0x100, dstArch = 3, pdst = 20, pdstValid = true, pdstOld = 3)
      pokeRu(dut.rsrc.logic.src.payload(1), pc = 0x200, dstArch = 5, pdst = 21, pdstValid = true, pdstOld = 5)
      dut.rsrc.logic.src.valid #= true
      dut.rsrc.logic.u1v #= true
      cd.waitSamplingWhere(dut.rsrc.logic.src.ready.toBoolean)
      dut.rsrc.logic.src.valid #= false
      dut.rsrc.logic.u1v #= false
      cd.waitSampling()
      // Deliberately leave them incomplete for a while: those are RETIRE-STALL cycles
      // (ROB non-empty, nothing retiring) and they are what OFF_PERF_STALL_RETIRE is for.
      cd.waitSampling(40)
      dut.rob.logic.completion(0).valid #= true
      dut.rob.logic.completion(0).payload #= 1
      cd.waitSampling()
      dut.rob.logic.completion(0).payload #= 0
      cd.waitSampling()
      dut.rob.logic.completion(0).valid #= false
      var guard = 0
      while (dut.rob.logic.count.toInt != 0 && guard < 200) { cd.waitSampling(); guard += 1 }
      assert(dut.rob.logic.count.toInt == 0, "the two uops must retire")
      cd.waitSampling(10)

      DbgAxiDriver.write(b, cd, OFF_PERF_CTL, CtlFreeze)
      cd.waitSampling(4)
      val w1 = readAll(b, cd)
      assert(u(DbgAxiDriver.read(b, cd, OFF_PERF_DETAIL_CAP)) == 0xd1011700L)
      val detail = (0 until 23).map(n => u(DbgAxiDriver.read(b, cd, OFF_PERF_DETAIL_BASE + 4*n)))
      assert(detail.take(12).sum == w1("cycle_lo"), "ROB partition covers every measured cycle")
      assert(detail(1) + 2*detail(2) == 2, "two uops retired")
      assert(detail(13) + 2*detail(14) == 2, "two macro boundaries retired")
      assert(detail.slice(3, 12).sum == w1("stall_retire"), "same ROB nonempty/no-retire window")
      val instAfter = u(DbgAxiDriver.read(b, cd, OFF_INST_LO))
      val freeDelta = instAfter - instBefore
      println(s"[perf-rob] window1 inst=${w1("inst_lo")} (free-running delta $freeDelta) " +
              s"cycles=${w1("cycle_lo")} stall_retire=${w1("stall_retire")} " +
              s"mispred=${w1("mispred")} flush=${w1("flush")}")

      // AGREEMENT WITH AN INDEPENDENT COUNT. The windowed lane is fed from
      // `macroRetirePc`; OFF_INST_LO is fed from `RobPlugin.macroCount`. They are
      // different signals with different owners, so equality is a real cross-check and
      // not a tautology.
      assert(w1("inst_lo") == 2L,
        s"windowed retired-macro count is ${w1("inst_lo")}, expected 2")
      assert(freeDelta == 2L,
        s"free-running OFF_INST_LO moved by $freeDelta, expected 2 -- the two " +
        "measurements must agree or one of them is fabricating")
      assert(w1("inst_hi") == 0L)
      assert(w1("cycle_lo") > 40L,
        s"window cycle count ${w1("cycle_lo")} is impossibly small for this sequence")
      assert(w1("stall_retire") >= 40L,
        s"OFF_PERF_STALL_RETIRE = ${w1("stall_retire")}; the two uops were held " +
        "incomplete for 40 clocks with the ROB non-empty, so at least that many cycles " +
        "must be attributed to a retire stall. A smaller number means the lane is not " +
        "watching the ROB.")
      assert(w1("stall_retire") <= w1("cycle_lo"),
        "a stall-cycle counter cannot exceed the window it is measured in")
      assert(w1("mispred") == 0L && w1("flush") == 0L,
        s"no branch and no flush in window 1, but mispred=${w1("mispred")} " +
        s"flush=${w1("flush")} -- these lanes are counting something else")
      assert(w1("dc_miss") == 0L && w1("ic_miss") == 0L &&
             w1("dtlb_walk") == 0L && w1("itlb_walk") == 0L && w1("stall_dc") == 0L &&
             w1("stall_walk") == 0L,
        "counters whose producer plugin is absent must stay at a clean zero")

      // ---- window 2: a mispredicting branch --------------------------------
      DbgAxiDriver.write(b, cd, OFF_PERF_CTL, CtlClearRun)
      cd.waitSampling(4)
      assertAllZeroExceptCycle(b, cd)

      pokeRu(dut.rsrc.logic.src.payload(0), pc = 0x1000, isBranch = true)
      // THE ROB ID IS NOT ZERO HERE. Window 1 already allocated robIds 0 and 1, and the
      // ring does not rewind on retire -- `tail` is 2. Hardcoding 0, as the standalone
      // RobPluginSpec test legitimately can because it allocates nothing first,
      // completes a DIFFERENT entry and the branch never retires: the whole window then
      // reads zero and looks exactly like "the counters are dead".
      val branchRobId = dut.rob.logic.tail.toInt
      dut.rsrc.logic.src.valid #= true
      dut.rsrc.logic.u1v #= false
      cd.waitSamplingWhere(dut.rsrc.logic.src.ready.toBoolean)
      dut.rsrc.logic.src.valid #= false
      cd.waitSampling()
      // `branchCompletion` both completes the uop AND records the mispredict. `isBranch`
      // additionally sets `btbIsBranchStore`, which is what makes the retired-branch
      // lane (OFF_PERF_BRANCH) fire -- that lane is fed from the BTB-update Flow.
      dut.rob.logic.branchCompletion.valid #= true
      dut.rob.logic.branchCompletion.payload.robId #= branchRobId
      dut.rob.logic.branchCompletion.payload.mispredict #= true
      dut.rob.logic.branchCompletion.payload.isBranch #= true
      dut.rob.logic.branchCompletion.payload.nextPc #= 0xBEEF
      cd.waitSampling()
      dut.rob.logic.branchCompletion.valid #= false
      guard = 0
      while (dut.rob.logic.count.toInt != 0 && guard < 200) { cd.waitSampling(); guard += 1 }
      assert(dut.rob.logic.count.toInt == 0,
        s"the mispredicting branch (robId $branchRobId) never retired, so this window " +
        "measures nothing and every counter below would read a meaningless zero")
      cd.waitSampling(20)
      DbgAxiDriver.write(b, cd, OFF_PERF_CTL, CtlFreeze)
      cd.waitSampling(4)
      val w2 = readAll(b, cd)
      println(s"[perf-rob] window2 mispred=${w2("mispred")} flush=${w2("flush")} " +
              s"branch=${w2("branch")} inst=${w2("inst_lo")}")
      assert(w2("mispred") == 1L,
        s"OFF_MISPRED_COUNT = ${w2("mispred")} after exactly one mispredicting branch " +
        "retired. This register read zero for months because nothing drove it; if it " +
        "still does, nothing has changed.")
      assert(w2("flush") == 1L,
        s"OFF_FLUSH_COUNT = ${w2("flush")} after exactly one global flush")
      assert(w2("branch") == 1L,
        s"OFF_PERF_BRANCH = ${w2("branch")} after one BTB-eligible branch retired")

      // The CLEAR between the two windows really separated them: window 2 did not
      // inherit window 1's two macros.
      assert(w2("inst_lo") == 1L,
        s"window 2 saw ${w2("inst_lo")} retired macros; expected exactly the one branch. " +
        "A larger number means the CLEAR between windows did not take, and every " +
        "'precise path' measurement would be contaminated by everything before it.")
    }
  }

  private def assertAllZeroExceptCycle(b: DbgAxiLite, cd: ClockDomain): Unit =
    for ((off, nm) <- AllWords if !nm.startsWith("cycle")) {
      val v = u(DbgAxiDriver.read(b, cd, off))
      assert(v == 0L, f"$nm read 0x$v%08x immediately after a CLEAR+RUN")
    }

  // ══════════════════════════════════════════════════════════════════════════════
  // 3. CACHE PRODUCERS, against the REAL D-cache and I-cache.
  // ══════════════════════════════════════════════════════════════════════════════
  class CachePerfDut extends Component {
    val db = new Database
    val host = db on (new PluginHost)
    val ctrl = new MmuControlPlugin()
    val dXlate = new DIdentityTranslationPlugin
    val iXlate = new IdentityTranslationPlugin
    val dcache = new DcachePlugin()
    val icache = new IcachePlugin
    val dProbe = new DcacheProbePlugin
    val iProbe = new FetchProbePlugin
    val dbg = new DebugCtrlPlugin(buildId = BigInt(0x50450002L), porCycles = 4, stage = 2)
    db.on {
      host.asHostOf(Seq[FiberPlugin](new ParamPlugin(M68kParams()), ctrl, dXlate, iXlate,
        dcache, icache, dProbe, iProbe, dbg))
    }
    def axi: DbgAxiLite = dbg.logic.dbgAxi
  }

  test("cache perf producers are live: D-cache load misses and I-cache demand misses",
       VerilatorTest) {
    M68kSim().compile(new CachePerfDut).doSim("perf_cache_producers", 1) { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      // The I-cache's speculative prefetcher would fill lines we never asked for, and
      // OFF_PERF_IC_MISS deliberately counts DEMAND misses only. Disabling it makes the
      // expected count exact instead of "at least".  `prefetchEnable` is RegInit(True),
      // so a single poke is overwritten by reset -- re-poke from a non-blocking fork.
      fork { for (_ <- 0 until 8) { dut.icache.logic.prefetchEnable #= false; cd.waitSampling() } }
      IcacheSim.attachMemory(dut.icache.logic.axi, cd, 0L, 0x10000)
      val dmem = new m68k040.ls.BehavioralMemAgent(dut.dcache.logic.axi, cd)
      val b = dut.axi
      DbgAxiDriver.idle(b)
      dut.dbg.logic.initDoneSeen #= false
      dut.dProbe.logic.loadCmdIn.valid #= false
      dut.dProbe.logic.loadProbeIn.valid #= false
      dut.dProbe.logic.loadProbeCancelIn.valid #= false
      dut.dProbe.logic.loadProbeCancelIn.payload.all #= false
      dut.dProbe.logic.storeIn.valid #= false
      dut.dProbe.logic.maintCmdIn.valid #= false
      dut.dProbe.logic.maintCmdIn.payload.push #= false
      dut.dProbe.logic.maintCmdIn.payload.invalidate #= false
      dut.dProbe.logic.maintCmdIn.payload.scope #= 0
      dut.dProbe.logic.maintCmdIn.payload.sel #= 0
      dut.dProbe.logic.maintCmdIn.payload.addr #= 0
      dut.iProbe.logic.cmdIn.valid #= false
      dut.iProbe.logic.cmdIn.payload.pc #= 0
      dut.icache.logic.invalidateAll #= false
      cd.waitSampling(4)
      cd.waitSamplingWhere(!dut.dcache.logic.resetSweepBusy.toBoolean)

      // Independent event counts, from the AXI side: a cold miss is an AR fire.
      var dAr = 0; var iAr = 0
      fork {
        while (true) {
          cd.waitSampling()
          if (dut.dcache.logic.axi.ar.valid.toBoolean && dut.dcache.logic.axi.ar.ready.toBoolean) dAr += 1
          if (dut.icache.logic.axi.ar.valid.toBoolean && dut.icache.logic.axi.ar.ready.toBoolean) iAr += 1
        }
      }

      def dLoad(vaddr: Long): Unit = {
        val p = dut.dProbe.logic
        p.loadCmdIn.valid #= true
        p.loadCmdIn.payload.vaddr #= vaddr
        p.loadCmdIn.payload.paddr #= vaddr
        p.loadCmdIn.payload.size #= Size.LONG
        p.loadCmdIn.payload.cacheMode #= CacheMode.WRITETHROUGH
        p.loadCmdIn.payload.token #= 0
        p.loadCmdIn.payload.lineOnly #= false
        cd.waitSamplingWhere(p.loadCmdIn.ready.toBoolean && p.loadCmdIn.valid.toBoolean)
        p.loadCmdIn.valid #= false
        cd.waitSamplingWhere(p.loadRspOut.valid.toBoolean)
      }
      def iFetch(pc: Long): Unit = {
        val p = dut.iProbe.logic
        p.cmdIn.valid #= true
        p.cmdIn.payload.pc #= pc
        cd.waitSamplingWhere(p.cmdIn.ready.toBoolean && p.cmdIn.valid.toBoolean)
        p.cmdIn.valid #= false
        cd.waitSamplingWhere(p.rspOut.valid.toBoolean)
      }

      val c = ctl(b, cd)
      println(s"[perf-cache] $c")
      assertPerfFeatureWithheld(b, cd, "the cache-only fixture")
      assert(c.dcache && c.icache,
        s"D-cache and I-cache producer presence bits must be set on this DUT; got $c")
      assert(!c.rob && !c.dtlb && !c.itlb, s"no ROB/TLBs in this DUT, but $c claims some")

      // ---- window: four cold D loads in four distinct lines, three cold fetches ----
      DbgAxiDriver.write(b, cd, OFF_PERF_CTL, CtlClearRun)
      cd.waitSampling(4)
      dAr = 0; iAr = 0
      val dAddrs = Seq(0x2000L, 0x2400L, 0x2800L, 0x2C00L)
      for (a <- dAddrs) { for (i <- 0 until 16) dmem.pokeByte(a + i, ((a + i) & 0xff).toInt); dLoad(a) }
      val icAddrs = Seq(0x3000L, 0x3400L, 0x3800L)
      for (a <- icAddrs) iFetch(a)
      cd.waitSampling(40)
      DbgAxiDriver.write(b, cd, OFF_PERF_CTL, CtlFreeze)
      cd.waitSampling(4)
      val w = readAll(b, cd)
      println(s"[perf-cache] dc_miss=${w("dc_miss")} (AXI AR fires: $dAr)  " +
              s"ic_miss=${w("ic_miss")} (AXI AR fires: $iAr)  stall_dc=${w("stall_dc")}  " +
              s"cycles=${w("cycle_lo")}")

      assert(w("dc_miss") == dAddrs.length.toLong,
        s"OFF_PERF_DC_MISS = ${w("dc_miss")} after ${dAddrs.length} cold " +
        s"loads in ${dAddrs.length} distinct lines. The D-cache issued $dAr AXI read " +
        "bursts over the same window; a counter that disagrees with the bus is measuring " +
        "the wrong thing.")
      assert(dAr >= dAddrs.length,
        s"sanity: the D-cache should have issued at least ${dAddrs.length} AR bursts, saw $dAr")
      assert(w("ic_miss") == icAddrs.length.toLong,
        s"OFF_PERF_IC_MISS = ${w("ic_miss")} after ${icAddrs.length} cold demand fetches " +
        s"with the prefetcher off (I-side AR fires: $iAr)")
      assert(w("stall_dc") > 0L,
        s"OFF_PERF_STALL_DC = 0 across a window containing ${dAddrs.length} refills. The " +
        "D-cache is busy for the whole of every refill, so zero means the lane is dead.")
      assert(w("stall_dc") <= w("cycle_lo"), "busy cycles cannot exceed window cycles")
      assert(w("mispred") == 0L && w("flush") == 0L && w("inst_lo") == 0L &&
             w("dtlb_walk") == 0L && w("itlb_walk") == 0L && w("stall_walk") == 0L,
        "counters with no producer in this DUT must read a clean zero")

      // ---- the SECOND window of the same addresses must be a HIT, i.e. no misses ----
      // This is the negative control the whole suite turns on: a counter that only ever
      // goes up is indistinguishable from a free-running one.
      DbgAxiDriver.write(b, cd, OFF_PERF_CTL, CtlClearRun)
      cd.waitSampling(4)
      for (a <- dAddrs) dLoad(a)
      cd.waitSampling(20)
      DbgAxiDriver.write(b, cd, OFF_PERF_CTL, CtlFreeze)
      cd.waitSampling(4)
      val hits = u(DbgAxiDriver.read(b, cd, OFF_PERF_DC_MISS))
      println(s"[perf-cache] warm re-read of the same 4 lines: dc_miss=$hits")
      assert(hits == 0L,
        s"OFF_PERF_DC_MISS = $hits when re-reading four lines that are already resident. " +
        "The lane is counting accesses, not misses.")
    }
  }

  // ══════════════════════════════════════════════════════════════════════════════
  // 4. TLB PRODUCERS: real three-level table walks on both walkers.
  // ══════════════════════════════════════════════════════════════════════════════
  class TlbPerfDut extends Component {
    val db = new Database
    val host = db on (new PluginHost)
    val ctrl = new MmuControlPlugin()
    val dtlb = new DtlbPlugin()
    val itlb = new ItlbPlugin()
    val dProbe = new DtlbProbePlugin
    val iProbe = new ItlbProbePlugin
    val dWalk = new WalkerDcacheSimIo(dtlb, "dtlbWalk")
    val iWalk = new WalkerDcacheSimIo(itlb, "itlbWalk")
    val dbg = new DebugCtrlPlugin(buildId = BigInt(0x50450003L), porCycles = 4, stage = 2)
    db.on {
      host.asHostOf(Seq[FiberPlugin](new ParamPlugin(M68kParams()), ctrl, dtlb, itlb,
        dProbe, iProbe, dWalk, iWalk, dbg))
    }
    def axi: DbgAxiLite = dbg.logic.dbgAxi
  }

  private val ROOT = 0x10000L
  private val PTRT = 0x11000L
  private val PAGT = 0x12000L
  private def rootIdx(va: Long): Int = ((va >> 25) & 0x7f).toInt
  private def ptrIdx(va: Long): Int  = ((va >> 18) & 0x7f).toInt
  private def pageIdx(va: Long): Int = ((va >> 12) & 0x3f).toInt
  private def vpnOf(va: Long): Long  = (va >> 12) & 0xfffff
  private def pokeWordBE(mem: DcacheClientMemAgent, addr: Long, w: Long): Unit =
    for (i <- 0 until 4) mem.pokeByte(addr + i, ((w >> (8 * (3 - i))) & 0xff).toInt)
  private def buildTable(mem: DcacheClientMemAgent, va: Long, ppn: Long): Unit = {
    pokeWordBE(mem, ROOT + rootIdx(va) * 4, (PTRT & 0xfffffff0L) | 0x3L)
    pokeWordBE(mem, PTRT + ptrIdx(va) * 4,  (PAGT & 0xfffffff0L) | 0x3L)
    pokeWordBE(mem, PAGT + pageIdx(va) * 4, ((ppn << 12) & 0xfffff000L) | 0x1L)
  }

  test("TLB perf producers are live: DTLB and ITLB table walks", VerilatorTest) {
    M68kSim().compile(new TlbPerfDut).doSim("perf_tlb_producers", 1) { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      val image = SparseMemory()
      val dmem = new DcacheClientMemAgent(dut.dWalk, cd, image)
      val imem = new DcacheClientMemAgent(dut.iWalk, cd, image)
      val b = dut.axi
      DbgAxiDriver.idle(b)
      dut.dbg.logic.initDoneSeen #= false
      dut.dProbe.logic.reqIn.valid #= false
      dut.dProbe.logic.reqIn.vpn #= 0
      dut.dProbe.logic.reqIn.write #= false
      dut.dProbe.logic.reqIn.supervisor #= false
      dut.iProbe.logic.reqIn.valid #= false
      dut.iProbe.logic.reqIn.vpn #= 0
      dut.iProbe.logic.reqIn.write #= false
      dut.iProbe.logic.reqIn.supervisor #= false
      dut.iProbe.logic.accessRobId #= 0
      dut.iProbe.logic.commitValid #= false
      dut.iProbe.logic.commitId #= 0
      dut.iProbe.logic.flush #= false
      dut.iProbe.logic.pflusha #= false
      cd.waitSampling(4)

      dut.ctrl.logic.mmuEnable #= true
      dut.ctrl.logic.pageSize8K #= false
      dut.ctrl.logic.urp #= ROOT
      dut.ctrl.logic.srp #= ROOT
      dut.ctrl.logic.itt0 #= 0; dut.ctrl.logic.itt1 #= 0
      dut.ctrl.logic.dtt0 #= 0; dut.ctrl.logic.dtt1 #= 0
      cd.waitSampling(4)

      val vaD = Seq(0x00802000L, 0x00902000L)
      val vaI = Seq(0x00A04000L)
      for ((va, i) <- vaD.zipWithIndex) buildTable(dmem, va, 0xABCD0L + i)
      for ((va, i) <- vaI.zipWithIndex) buildTable(imem, va, 0x33330L + i)

      val c = ctl(b, cd)
      println(s"[perf-tlb] $c")
      assertPerfFeatureWithheld(b, cd, "the TLB-only fixture")
      assert(c.dtlb && c.itlb, s"both TLB producer presence bits must be set; got $c")
      assert(!c.rob && !c.dcache && !c.icache, s"no ROB/caches in this DUT, but $c claims some")

      DbgAxiDriver.write(b, cd, OFF_PERF_CTL, CtlClearRun)
      cd.waitSampling(4)
      val dLoadsBefore = dmem.loadCount
      val iLoadsBefore = imem.loadCount

      def dLookup(va: Long): Unit = {
        dut.dProbe.logic.reqIn.valid #= true
        dut.dProbe.logic.reqIn.vpn #= vpnOf(va)
        dut.dProbe.logic.reqIn.supervisor #= false
        cd.waitSampling()
        var g = 0
        while (!dut.dProbe.logic.rspOut.ready.toBoolean && g < 400) { cd.waitSampling(); g += 1 }
        sleep(1)
        assert(dut.dProbe.logic.rspOut.ready.toBoolean, f"DTLB lookup of 0x$va%x never completed")
        assert(!dut.dProbe.logic.rspOut.fault.toBoolean, f"DTLB lookup of 0x$va%x faulted")
        dut.dProbe.logic.reqIn.valid #= false
        cd.waitSampling(3)
      }
      def iLookup(va: Long): Unit = {
        dut.iProbe.logic.reqIn.valid #= true
        dut.iProbe.logic.reqIn.vpn #= vpnOf(va)
        dut.iProbe.logic.reqIn.supervisor #= false
        cd.waitSampling()
        var g = 0
        while (!dut.iProbe.logic.rspOut.ready.toBoolean && g < 400) { cd.waitSampling(); g += 1 }
        sleep(1)
        assert(dut.iProbe.logic.rspOut.ready.toBoolean, f"ITLB lookup of 0x$va%x never completed")
        assert(!dut.iProbe.logic.rspOut.fault.toBoolean, f"ITLB lookup of 0x$va%x faulted")
        dut.iProbe.logic.reqIn.valid #= false
        cd.waitSampling(3)
      }

      vaD.foreach(dLookup)
      vaI.foreach(iLookup)
      cd.waitSampling(20)
      DbgAxiDriver.write(b, cd, OFF_PERF_CTL, CtlFreeze)
      cd.waitSampling(4)
      val w = readAll(b, cd)
      val dDesc = dmem.loadCount - dLoadsBefore
      val iDesc = imem.loadCount - iLoadsBefore
      println(s"[perf-tlb] dtlb_walk=${w("dtlb_walk")} (descriptor reads: $dDesc)  " +
              s"itlb_walk=${w("itlb_walk")} (descriptor reads: $iDesc)  " +
              s"stall_walk=${w("stall_walk")}  cycles=${w("cycle_lo")}")

      // AGREEMENT WITH AN INDEPENDENT COUNT: a 4 KB three-level walk is exactly three
      // descriptor reads, so the descriptor-read count divided by three is the number of
      // walks, measured on the other side of the walker from the counter.
      assert(w("dtlb_walk") == vaD.length.toLong,
        s"OFF_PERF_DTLB_WALK = ${w("dtlb_walk")} after ${vaD.length} cold DTLB lookups " +
        s"($dDesc descriptor reads seen on the walker's own port)")
      assert(dDesc == 3L * vaD.length,
        s"sanity: expected ${3 * vaD.length} descriptor reads for ${vaD.length} " +
        s"three-level walks, saw $dDesc")
      assert(w("itlb_walk") == vaI.length.toLong,
        s"OFF_PERF_ITLB_WALK = ${w("itlb_walk")} after ${vaI.length} cold ITLB lookups " +
        s"($iDesc descriptor reads)")
      assert(w("stall_walk") > 0L,
        "OFF_PERF_STALL_WALK = 0 across a window containing three real table walks")
      assert(w("stall_walk") <= w("cycle_lo"), "walk-busy cycles cannot exceed the window")
      assert(w("mispred") == 0L && w("flush") == 0L && w("dc_miss") == 0L &&
             w("ic_miss") == 0L && w("inst_lo") == 0L && w("stall_dc") == 0L,
        "counters with no producer in this DUT must read a clean zero")

      // ---- NEGATIVE CONTROL: the same VPNs now HIT the TLB, so no walk starts ----
      DbgAxiDriver.write(b, cd, OFF_PERF_CTL, CtlClearRun)
      cd.waitSampling(4)
      vaD.foreach(dLookup)
      vaI.foreach(iLookup)
      cd.waitSampling(20)
      DbgAxiDriver.write(b, cd, OFF_PERF_CTL, CtlFreeze)
      cd.waitSampling(4)
      val dW2 = u(DbgAxiDriver.read(b, cd, OFF_PERF_DTLB_WALK))
      val iW2 = u(DbgAxiDriver.read(b, cd, OFF_PERF_ITLB_WALK))
      println(s"[perf-tlb] warm re-lookup: dtlb_walk=$dW2 itlb_walk=$iW2")
      assert(dW2 == 0L && iW2 == 0L,
        s"warm TLB lookups started $dW2 DTLB / $iW2 ITLB walks. These lanes are counting " +
        "lookups, not walks.")
    }
  }

  // ══════════════════════════════════════════════════════════════════════════════
  // 5. THE POSITIVE DIRECTION of the capability bit. Every test above proves the
  //    `perf_counters` gate is not stuck HIGH; this one proves it is not stuck LOW.
  //    All five producer families in one host, wired the way the shipped core wires
  //    them (the caches translate through the real TLBs, not through an identity
  //    stub), so the bit's precondition -- "every advertised counter has a real
  //    producer" -- is actually satisfied.
  // ══════════════════════════════════════════════════════════════════════════════
  class AllProducersDut extends Component {
    val db = new Database
    val host = db on (new PluginHost)
    val ctrl = new MmuControlPlugin()
    val dtlb = new DtlbPlugin()
    val itlb = new ItlbPlugin()
    val dcache = new DcachePlugin()
    val icache = new IcachePlugin
    val dProbe = new DcacheProbePlugin
    val iProbe = new FetchProbePlugin
    // NO DtlbProbePlugin / ItlbProbePlugin here: in this host the CACHES are the TLBs'
    // clients, exactly as in the shipped core. Adding the probe plugins as well makes
    // two drivers for each translation request and SpinalHDL refuses in
    // PhaseCheck_noLatchNoOverride. That is the correct refusal -- the probes exist so a
    // TLB can be exercised WITHOUT a cache, not alongside one.
    val dWalk = new WalkerDcacheSimIo(dtlb, "dtlbWalk")
    val iWalk = new WalkerDcacheSimIo(itlb, "itlbWalk")
    val rsrc = new RenameUopSourcePlugin
    val alloc = new RobAllocDriverPlugin
    val rob = new RobPlugin
    val commit = new RenameCommitSinkPlugin
    val dbg = new DebugCtrlPlugin(buildId = BigInt(0x50450004L), porCycles = 4, stage = 2)
    db.on {
      host.asHostOf(Seq[FiberPlugin](new ParamPlugin(M68kParams()), ctrl, dtlb, itlb,
        dcache, icache, dProbe, iProbe, dWalk, iWalk,
        rsrc, alloc, rob, commit, dbg))
    }
    def axi: DbgAxiLite = dbg.logic.dbgAxi
  }

  test("with every producer present the perf_counters capability bit IS advertised",
       VerilatorTest) {
    M68kSim().compile(new AllProducersDut).doSim("perf_all_producers", 1) { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      IcacheSim.attachMemory(dut.icache.logic.axi, cd, 0L, 0x1000)
      new m68k040.ls.BehavioralMemAgent(dut.dcache.logic.axi, cd)
      val image = SparseMemory()
      new DcacheClientMemAgent(dut.dWalk, cd, image)
      new DcacheClientMemAgent(dut.iWalk, cd, image)
      val b = dut.axi
      DbgAxiDriver.idle(b)
      dut.dbg.logic.initDoneSeen #= false
      dut.dProbe.logic.loadCmdIn.valid #= false
      dut.dProbe.logic.loadProbeIn.valid #= false
      dut.dProbe.logic.loadProbeCancelIn.valid #= false
      dut.dProbe.logic.loadProbeCancelIn.payload.all #= false
      dut.dProbe.logic.storeIn.valid #= false
      dut.dProbe.logic.maintCmdIn.valid #= false
      dut.dProbe.logic.maintCmdIn.payload.push #= false
      dut.dProbe.logic.maintCmdIn.payload.invalidate #= false
      dut.dProbe.logic.maintCmdIn.payload.scope #= 0
      dut.dProbe.logic.maintCmdIn.payload.sel #= 0
      dut.dProbe.logic.maintCmdIn.payload.addr #= 0
      dut.iProbe.logic.cmdIn.valid #= false
      dut.iProbe.logic.cmdIn.payload.pc #= 0
      dut.icache.logic.invalidateAll #= false
      dut.rsrc.logic.src.valid #= false
      dut.rsrc.logic.u1v #= false
      dut.rob.logic.completion(0).valid #= false
      dut.rob.logic.completion(1).valid #= false
      dut.rob.logic.flush.valid #= false
      dut.rob.logic.branchCompletion.valid #= false
      cd.waitSampling(40)

      val c = ctl(b, cd)
      println(s"[perf-all] $c")
      assert(c.rob && c.dcache && c.icache && c.dtlb && c.itlb,
        s"every producer presence bit must be set on a host that contains all five: $c")
      val f = u(DbgAxiDriver.read(b, cd, OFF_FEATURES))
      println(f"[perf-all] OFF_FEATURES=0x$f%08x perf_counters=${(f >> PerfFeatureBit) & 1L}")
      assert(((f >> PerfFeatureBit) & 1L) == 1L,
        f"OFF_FEATURES=0x$f%08x withholds perf_counters on a host that HAS every " +
        f"producer. A capability bit stuck low is a build that can never truthfully " +
        f"advertise the counters it really implements.")
    }
  }
}
