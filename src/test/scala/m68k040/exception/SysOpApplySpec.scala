package m68k040.exception

import m68k040.M68kSim
import m68k040.decode.SysKind
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import org.scalatest.funsuite.AnyFunSuite

/** Directed whitebox coverage of the ExceptionUnit's S_APPLY commit-time sysOp
  * dispatch (`switch(sysCapKind)`) — the exact switch that Task P5.2's `SysKind.CINV`
  * insertion silently re-pointed.
  *
  * BACKGROUND (the bug this spec exists to prevent recurring): the S_APPLY switch used
  * to dispatch on HAND-WRITTEN ordinal literals (`is(U(7, 4 bits))` etc). Inserting
  * CINV between CPUSH and PFLUSHA in the `SysKind` enum shifted every later element up
  * by one, so with no edit to the switch at all:
  *   - CINV     (new ordinal 7) hit the OLD PFLUSHA arm -> pulsed a REAL full TLB flush,
  *     directly contradicting P5.2's "CINV is a pure architectural no-op for now",
  *   - PFLUSHA  (new ordinal 8) hit the OLD PTEST arm   -> wrote garbage to MMUSR and
  *     performed NO TLB flush (regressing task #136),
  *   - PTEST    (new ordinal 9) matched NOTHING          -> its MMUSR write vanished
  *     (regressing task #198).
  * Nothing in the suite caught it: OperationDecoderSpec stops at the decode layer, and
  * DtlbSpec's flush test pokes `dtlb.flushAll` directly, bypassing this switch entirely.
  *
  * The DUT below is deliberately minimal — a bare `ExceptionUnit` with its sysOp port
  * group driven directly — so each test isolates ONE sysKind's S_APPLY effect. Crucially
  * the DUT feeds `sysKind` exactly the way RobPlugin does
  * (`sysKindStore(h0).asBits.asUInt.resize(4)`) from an ENUM-typed input, so the tests
  * name kinds symbolically and stay correct across any future enum reordering — they
  * assert on EFFECTS, never on ordinals.
  *
  * Covered effects:
  *   - PTEST   -> MMUSR := (An & 0xFFFFF000) | 1, and NO TLB flush.
  *   - PFLUSHA -> a 1-cycle `sysFlushAllValid` pulse (the real TLB flush, through THIS
  *     switch rather than DtlbSpec's direct `flushAll` poke), and NO MMUSR write.
  *   - CINV    -> NOTHING (no flush, no MMUSR write, no SR change) but still serializes
  *     + redirects to sysNextPc.
  *   - CPUSH   -> the same deliberate no-op (guards the arm just below the insertion).
  *   - MOVE_TO_SR -> SR system-byte write still lands (proves the arms BELOW the
  *     insertion point were not disturbed either).
  */
class SysOpApplySpec extends AnyFunSuite {

  /** Minimal MmuControlService with REAL MMUSR storage (MmuControlPlugin itself is a
    * FiberPlugin and this DUT is a plain Component). Everything except MMUSR mirrors
    * RobPlugin's own throwaway idle stub. */
  class TestMmuCtrl extends Area with m68k040.services.MmuControlService {
    val mmusrReg = Reg(UInt(32 bits)) init 0
    val setMmusrPort = Flow(UInt(32 bits))
    setMmusrPort.valid.allowOverride;   setMmusrPort.valid := False
    setMmusrPort.payload.allowOverride; setMmusrPort.payload := U(0, 32 bits)
    when(setMmusrPort.valid) { mmusrReg := setMmusrPort.payload }
    // sticky "an MMUSR write happened at all" flag (a write of the SAME value would be
    // invisible in mmusrReg alone).
    val mmusrWriteSeen = RegInit(False)
    when(setMmusrPort.valid) { mmusrWriteSeen := True }

    override def mmuEnable = False
    override def pageSize8K = False
    override def urp  = U(0, 32 bits)
    override def srp  = U(0, 32 bits)
    override def itt0 = U(0, 32 bits)
    override def itt1 = U(0, 32 bits)
    override def dtt0 = U(0, 32 bits)
    override def dtt1 = U(0, 32 bits)
    override def mmusr = mmusrReg
    override def setEnable = { val f = Flow(Bool());        f.valid := False; f.payload := False;          f }
    override def setPageSize = { val f = Flow(Bool());      f.valid := False; f.payload := False;          f }
    override def setUrp    = { val f = Flow(UInt(32 bits)); f.valid := False; f.payload := U(0, 32 bits); f }
    override def setSrp    = { val f = Flow(UInt(32 bits)); f.valid := False; f.payload := U(0, 32 bits); f }
    override def setItt0   = { val f = Flow(UInt(32 bits)); f.valid := False; f.payload := U(0, 32 bits); f }
    override def setItt1   = { val f = Flow(UInt(32 bits)); f.valid := False; f.payload := U(0, 32 bits); f }
    override def setDtt0   = { val f = Flow(UInt(32 bits)); f.valid := False; f.payload := U(0, 32 bits); f }
    override def setDtt1   = { val f = Flow(UInt(32 bits)); f.valid := False; f.payload := U(0, 32 bits); f }
    override def setMmusr  = setMmusrPort
  }

  val NEXT_PC = 0x00001040L

  class Dut extends Component {
    val ss  = new SystemState
    val mmu = new TestMmuCtrl

    // sysOp drive ports (the ROB's commit-side interface to the FSM).
    val sysTriggerIn = in Bool ()
    val sysKindIn    = in(SysKind())      // ENUM-typed, exactly like RobPlugin's store
    val sysValIn     = in Bits (32 bits)
    // Task P5.4: the two quiesce preconditions the new S_DRAIN state waits on.
    // FullCoreSynth drives these from `lsEu.sqEmptySig` / `dc.maintQuiesced`; here
    // they are directly controllable so a test can prove S_APPLY genuinely blocks.
    val sqDrainedIn  = in Bool ()
    val dcQuiescedIn = in Bool ()
    // Task P5.5: the CPUSH/CINV opword fields ride in on the MOVEC `sysRc` port
    // ([3:2]=scope, [1:0]=cache selector), and `maintDoneIn` is the D-cache
    // maintenance walk's completion handshake. Both directly controllable so a test
    // can hold the FSM in S_MAINTWAIT for an arbitrary number of cycles (a real
    // BC-selector CPUSH ALL walk is 1500+) and observe what does/doesn't pulse
    // meanwhile.
    val sysRcIn      = in UInt (12 bits)
    val maintDoneIn  = in Bool ()

    val exc = new ExceptionUnit(
      ss = ss, mmuCtrl = mmu,
      entryTrigger = False, entryVector = U(0, 8 bits), entryPc = U(0, 32 bits),
      rteTrigger = False, rtePc = U(0, 32 bits),
      committedCcr = U(0, 5 bits),
      sysTrigger = sysTriggerIn,
      // IDENTICAL conversion to RobPlugin.scala's `sysKind = sysKindStore(h0).asBits
      // .asUInt.resize(4)` — so this DUT exercises the real encoding path, not a
      // test-local re-derivation of it.
      sysKind    = sysKindIn.asBits.asUInt.resize(4),
      sysVal     = sysValIn,
      sysRc      = sysRcIn,
      sysPc      = U(NEXT_PC - 2, 32 bits),
      sysNextPc  = U(NEXT_PC, 32 bits))

    exc.sqDrained   := sqDrainedIn
    exc.dcQuiesced  := dcQuiescedIn
    exc.maintDoneIn := maintDoneIn

    // ── observation ports ──
    val mmusrOut       = out UInt (32 bits); mmusrOut       := mmu.mmusrReg
    val mmusrWriteSeen = out Bool ();        mmusrWriteSeen := mmu.mmusrWriteSeen
    val srSysOut       = out UInt (8 bits);  srSysOut       := ss.srSys
    val uspOut         = out UInt (32 bits); uspOut         := ss.usp
    val excActive      = out Bool ();        excActive      := exc.active

    // sticky flush-pulse observation + a pulse COUNT (a spurious extra pulse must fail).
    val flushSeen  = out Bool ()
    val flushCount = out UInt (8 bits)
    val flushSeenR  = RegInit(False)
    val flushCountR = Reg(UInt(8 bits)) init 0
    when(exc.sysFlushAllValid) { flushSeenR := True; flushCountR := flushCountR + 1 }
    flushSeen  := flushSeenR
    flushCount := flushCountR

    // Task P5.5 cache-maintenance observation: pulse COUNTS for the D-side command
    // (`maintCmdOut.valid`, issued in S_APPLY) and the I-side full invalidate
    // (`icMaintPulse`, issued on S_MAINTWAIT's completion exit). The counts are what
    // make the ORDERING testable: the D command must be out and the I invalidate must
    // still be at zero for the whole duration of the walk.
    val maintCmdCount = out UInt (8 bits)
    val icMaintCount  = out UInt (8 bits)
    val maintCmdCountR = Reg(UInt(8 bits)) init 0
    val icMaintCountR  = Reg(UInt(8 bits)) init 0
    when(exc.maintCmdOut.valid) { maintCmdCountR := maintCmdCountR + 1 }
    when(exc.icMaintPulse)      { icMaintCountR  := icMaintCountR + 1 }
    maintCmdCount := maintCmdCountR
    icMaintCount  := icMaintCountR

    // sticky redirect observation (every sysOp must serialize + redirect to sysNextPc).
    val redirSeen = out Bool ()
    val redirPc   = out UInt (32 bits)
    val redirSeenR = RegInit(False)
    val redirPcR   = Reg(UInt(32 bits)) init 0
    when(exc.redirectValid) { redirSeenR := True; redirPcR := exc.redirectPc }
    redirSeen := redirSeenR
    redirPc   := redirPcR
  }

  /** Pulse one sysOp through IDLE -> S_APPLY -> S_REDIR and settle. */
  def applySysOp(dut: Dut, kind: SpinalEnumElement[SysKind.type], value: Long): Unit = {
    val cd = dut.clockDomain
    dut.sysKindIn #= kind
    dut.sysValIn  #= BigInt(value)
    dut.sysTriggerIn #= true
    cd.waitSampling()          // IDLE latches the context, goes to S_APPLY
    dut.sysTriggerIn #= false
    cd.waitSampling(6)         // S_APPLY (effect) -> S_REDIR (obs/redirect) -> IDLE
    assert(!dut.excActive.toBoolean, "FSM did not return to IDLE after the sysOp")
  }

  def init(dut: Dut): Unit = {
    dut.sysTriggerIn #= false
    dut.sysKindIn    #= SysKind.NONE
    dut.sysValIn     #= 0
    // Quiesced by default -- the normal steady state, so every pre-existing test
    // below sees the same behaviour it always did (plus one S_DRAIN cycle).
    dut.sqDrainedIn  #= true
    dut.dcQuiescedIn #= true
    // Task P5.5 defaults: no CPUSH/CINV fields, and the maintenance walk reports done
    // immediately (S_MAINTWAIT costs one cycle) -- so every pre-existing test below is
    // unaffected.
    dut.sysRcIn      #= 0
    dut.maintDoneIn  #= true
    dut.clockDomain.waitSampling(4)
  }

  test("PTEST at S_APPLY writes MMUSR = (An & 0xFFFFF000) | 1 (and flushes no TLB)") {
    M68kSim().compile(new Dut).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      init(dut)
      // An = 0x12345ABC -> MMUSR = 0x12345000 | 1 (page-aligned PA, R bit set).
      applySysOp(dut, SysKind.PTEST, 0x12345abcL)
      assert(dut.mmusrWriteSeen.toBoolean, "PTEST performed NO MMUSR write at all")
      assert(dut.mmusrOut.toLong == 0x12345001L,
        f"MMUSR=0x${dut.mmusrOut.toLong}%08x, expected 0x12345001")
      assert(!dut.flushSeen.toBoolean, "PTEST must not pulse the TLB flushAll")
      assert(dut.redirSeen.toBoolean && dut.redirPc.toLong == NEXT_PC,
        f"PTEST must serialize + redirect to nextPc (seen=${dut.redirSeen.toBoolean}, pc=0x${dut.redirPc.toLong}%x)")
    }
  }

  test("PFLUSHA at S_APPLY pulses sysFlushAllValid exactly once (and writes no MMUSR)") {
    M68kSim().compile(new Dut).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      init(dut)
      applySysOp(dut, SysKind.PFLUSHA, 0xdeadbeefL)
      assert(dut.flushSeen.toBoolean, "PFLUSHA performed NO TLB flush")
      assert(dut.flushCount.toInt == 1, s"expected exactly 1 flush pulse, got ${dut.flushCount.toInt}")
      assert(!dut.mmusrWriteSeen.toBoolean, "PFLUSHA must not write MMUSR")
      assert(dut.mmusrOut.toLong == 0, f"MMUSR clobbered to 0x${dut.mmusrOut.toLong}%08x by PFLUSHA")
      assert(dut.redirSeen.toBoolean && dut.redirPc.toLong == NEXT_PC, "PFLUSHA must serialize + redirect")
    }
  }

  test("CINV at S_APPLY is a pure no-op (no TLB flush, no MMUSR write, no SR change)") {
    M68kSim().compile(new Dut).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      init(dut)
      val sr0  = dut.srSysOut.toInt
      val usp0 = dut.uspOut.toLong
      applySysOp(dut, SysKind.CINV, 0x12345abcL)
      assert(!dut.flushSeen.toBoolean,
        "CINV spuriously pulsed the TLB flushAll (the exact P5.2 regression)")
      assert(!dut.mmusrWriteSeen.toBoolean, "CINV must not write MMUSR")
      assert(dut.mmusrOut.toLong == 0, f"MMUSR clobbered to 0x${dut.mmusrOut.toLong}%08x by CINV")
      assert(dut.srSysOut.toInt == sr0, "CINV must not change the SR system byte")
      assert(dut.uspOut.toLong == usp0, "CINV must not change USP")
      // ...but it must still SERIALIZE and advance the PC, like RESET.
      assert(dut.redirSeen.toBoolean && dut.redirPc.toLong == NEXT_PC,
        "CINV must still serialize + redirect to nextPc")
    }
  }

  test("CPUSH at S_APPLY is a pure no-op (no TLB flush, no MMUSR write, no SR change)") {
    M68kSim().compile(new Dut).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      init(dut)
      val sr0  = dut.srSysOut.toInt
      applySysOp(dut, SysKind.CPUSH, 0x12345abcL)
      assert(!dut.flushSeen.toBoolean, "CPUSH must not pulse the TLB flushAll")
      assert(!dut.mmusrWriteSeen.toBoolean, "CPUSH must not write MMUSR")
      assert(dut.srSysOut.toInt == sr0, "CPUSH must not change the SR system byte")
      assert(dut.redirSeen.toBoolean && dut.redirPc.toLong == NEXT_PC,
        "CPUSH must still serialize + redirect to nextPc")
    }
  }

  test("MOVE-to-SR at S_APPLY still writes the SR system byte (arms below CINV intact)") {
    M68kSim().compile(new Dut).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      init(dut)
      // sysVal[15:8] = 0x25 -> srSys := 0x25 (S=1, I=5).
      applySysOp(dut, SysKind.MOVE_TO_SR, 0x00002518L)
      assert(dut.srSysOut.toInt == 0x25, f"srSys=0x${dut.srSysOut.toInt}%02x, expected 0x25")
      assert(!dut.flushSeen.toBoolean, "MOVE-to-SR must not pulse the TLB flushAll")
      assert(!dut.mmusrWriteSeen.toBoolean, "MOVE-to-SR must not write MMUSR")
    }
  }

  test("MOVE-USP (An -> USP) at S_APPLY still writes the USP bank") {
    M68kSim().compile(new Dut).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      init(dut)
      applySysOp(dut, SysKind.MOVE_USP, 0x00087650L)
      assert(dut.uspOut.toLong == 0x00087650L, f"USP=0x${dut.uspOut.toLong}%08x, expected 0x00087650")
      assert(!dut.flushSeen.toBoolean, "MOVE-USP must not pulse the TLB flushAll")
      assert(!dut.mmusrWriteSeen.toBoolean, "MOVE-USP must not write MMUSR")
    }
  }

  /** Task P5.4 REGRESSION -- the commit-time sysOp path must quiesce before S_APPLY.
    *
    * THE BUG THIS PINS (a confirmed real gap, not a hypothetical): `sysTrigger` used to
    * go straight from IDLE to `S_APPLY` the very next cycle, with NO drain wait --
    * unlike exception entry (`E_DRAIN`) and RTE (`R_DRAIN`), both of which explicitly
    * wait on `sqDrained` because, in E_DRAIN's own words, they must "wait for older
    * committed stores to fully drain before we use the store port".
    *
    * That was harmless for every sysOp that existed before CPUSH/CINV (MOVEC / STOP /
    * PFLUSHA / PTEST / RESET / MOVE-to-SR / MOVE-USP touch no D-cache state at all).
    * CPUSH/CINV are the first sysOps to hand a cache-maintenance walk the D-cache's
    * shared array read port and its AXI write channels -- resources an OLDER,
    * ALREADY-COMMITTED store still draining out of the StoreQueue (commit and drain
    * are decoupled by design), or a refill/dirty-victim eviction accepted before the
    * flush landed, can still own for many cycles after `excActive` rises. `excActive`
    * only stops the LS EU issuing anything NEW.
    *
    * PFLUSHA is used as the probe rather than CPUSH/CINV precisely because it has a
    * loud, unambiguous, single-pulse S_APPLY side effect (`sysFlushAllValid`) to
    * observe -- CPUSH/CINV are still architectural no-ops at this task. What is under
    * test is the SHARED sysTrigger->S_APPLY path itself, which all of them take.
    *
    * Delete the `S_DRAIN` state (restore `goto(S_APPLY)` in the sysTrigger arm) and
    * the FIRST assertion below fails immediately: the flush fires while the
    * StoreQueue is still reported draining. */
  test("a commit-time sysOp blocks in S_DRAIN until the SQ drains AND the D-cache quiesces (Task P5.4)") {
    M68kSim().compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      init(dut)

      // An older committed store is still draining out of the StoreQueue.
      dut.sqDrainedIn  #= false
      dut.dcQuiescedIn #= true
      dut.sysKindIn    #= SysKind.PFLUSHA
      dut.sysValIn     #= 0
      dut.sysTriggerIn #= true
      cd.waitSampling()                 // IDLE latches the context
      dut.sysTriggerIn #= false

      cd.waitSampling(20)
      assert(dut.excActive.toBoolean,
        "the FSM left the sysOp path entirely while the SQ was still draining -- it must park in S_DRAIN")
      assert(!dut.flushSeen.toBoolean,
        "S_APPLY FIRED while an older committed store was still draining out of the StoreQueue -- " +
        "this is the P5.4 safety gap: for CPUSH/CINV this is the cache-maintenance walk taking the " +
        "D-cache array port + AXI write channels out from under an in-flight store drain")

      // SQ now drained, but the D-cache datapath itself is still busy (a refill /
      // dirty-victim eviction accepted before the flush landed).
      dut.sqDrainedIn  #= true
      dut.dcQuiescedIn #= false
      cd.waitSampling(20)
      assert(dut.excActive.toBoolean, "the FSM must still be parked in S_DRAIN")
      assert(!dut.flushSeen.toBoolean,
        "S_APPLY FIRED while the D-cache load/refill FSM was still mid-transaction -- " +
        "an in-flight refill/eviction owns the same shared array read port and AXI write channels " +
        "the maintenance walk would take")

      // Both preconditions met: the sysOp now applies, exactly once, and completes.
      dut.dcQuiescedIn #= true
      cd.waitSampling(8)
      assert(dut.flushSeen.toBoolean, "S_APPLY never fired even after both quiesce conditions held")
      assert(dut.flushCount.toInt == 1,
        s"expected exactly 1 flush pulse, got ${dut.flushCount.toInt} -- S_DRAIN must not re-trigger S_APPLY")
      assert(dut.redirSeen.toBoolean && dut.redirPc.toLong == NEXT_PC,
        "the sysOp must still serialize + redirect to nextPc after the drain wait")
      assert(!dut.excActive.toBoolean, "the FSM did not return to IDLE after the sysOp")
    }
  }

  /** Task P5.5 FOLLOW-UP REGRESSION -- the I-cache invalidate must fire AFTER the
    * D-cache maintenance walk completes, not when it is kicked off.
    *
    * THE BUG THIS PINS (a real, reviewed finding, not a hypothetical): the CPUSH/CINV
    * S_APPLY arms used to assert `icMaintPulse` in the SAME cycle they issued
    * `maintCmdOut`. For a BC-selector CPUSH ALL the D-side writeback walk then runs for
    * 1500+ more cycles in S_MAINTWAIT -- and NOTHING gates instruction fetch on the
    * exception FSM being active (`FetchAlignPlugin`'s `ic.cmd.valid` has no `excActive`
    * term). So for the canonical self-modifying-code sequence (patch code, CPUSH, fall
    * into the modified code) the I-cache would be cleared FIRST and could then refill
    * lines straight back from memory the D-side had not yet written back -- silently
    * re-caching stale code for exactly the lines the CPUSH exists to make coherent.
    * The architecturally correct order is D-push-completes THEN I-invalidate.
    *
    * Move the `icMaintPulse := True` back into the S_APPLY arms and the middle
    * assertion below fails immediately: the count is already 1 while the walk is still
    * running. */
  test("CPUSH BC: icMaintPulse fires only when the D-cache walk COMPLETES, not when it starts (Task P5.5 follow-up)") {
    M68kSim().compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      init(dut)

      // The maintenance walk is LONG and not done yet.
      dut.maintDoneIn #= false
      // sysRc = 0b1111 : scope=11 (All), cache selector=11 (BC -> both caches).
      dut.sysRcIn      #= 0xF
      dut.sysKindIn    #= SysKind.CPUSH
      dut.sysValIn     #= 0
      dut.sysTriggerIn #= true
      cd.waitSampling()                 // IDLE latches the context
      dut.sysTriggerIn #= false

      cd.waitSampling(40)
      // The D-side command IS out (exactly one Flow pulse -- a held command would
      // re-trigger DcachePlugin's `IDLE.when(maintCmdPort.valid)` walk entry).
      assert(dut.maintCmdCount.toInt == 1,
        s"expected exactly 1 maintCmdOut pulse, got ${dut.maintCmdCount.toInt}")
      // ...and the FSM is parked waiting for it.
      assert(dut.excActive.toBoolean, "the FSM must park in S_MAINTWAIT until the walk reports done")
      assert(!dut.redirSeen.toBoolean, "the sysOp redirected before the maintenance walk finished")
      // THE FIX: the I-cache invalidate has NOT fired yet. If it had, instruction fetch
      // could re-cache PRE-writeback bytes for the remaining ~1500 walk cycles.
      assert(dut.icMaintCount.toInt == 0,
        s"icMaintPulse fired ${dut.icMaintCount.toInt} time(s) WHILE the D-cache writeback walk was still " +
        "running -- an I-fetch in that window can refill stale, not-yet-written-back code")

      // Walk completes -> the I-invalidate fires on the S_MAINTWAIT->S_REDIR transition,
      // i.e. promptly and STRICTLY BEFORE the sysOp's redirect (which is S_REDIR's own
      // job, the following cycle). Poll cycle-by-cycle rather than asserting an exact
      // absolute cycle so the test does not encode the one-delta offset between a sim
      // poke and the DUT observing it.
      dut.maintDoneIn #= true
      var icCycle    = -1
      var redirCycle = -1
      for (c <- 0 until 8) {
        cd.waitSampling()
        if (icCycle    < 0 && dut.icMaintCount.toInt >= 1) icCycle    = c
        if (redirCycle < 0 && dut.redirSeen.toBoolean)     redirCycle = c
      }
      assert(icCycle >= 0,
        "icMaintPulse never fired even after the maintenance walk reported done")
      assert(icCycle <= 2,
        s"icMaintPulse fired $icCycle cycles after the walk completed -- it must fire on the " +
        "S_MAINTWAIT exit transition, not somewhere later")
      assert(redirCycle >= 0, "the sysOp never redirected")
      assert(icCycle < redirCycle,
        s"the I-cache invalidate (cycle $icCycle) must land BEFORE the sysOp's redirect " +
        s"(cycle $redirCycle) -- fetch resumes at the redirect")

      cd.waitSampling(6)
      assert(dut.icMaintCount.toInt == 1,
        s"expected exactly 1 icMaintPulse for one CPUSH, got ${dut.icMaintCount.toInt}")
      assert(dut.redirSeen.toBoolean && dut.redirPc.toLong == NEXT_PC,
        "CPUSH must still serialize + redirect to nextPc after the maintenance walk")
      assert(!dut.excActive.toBoolean, "the FSM did not return to IDLE after the CPUSH")
    }
  }

  /** Guards the cache-selector bit slice at its NEW site (S_MAINTWAIT re-derives
    * `cacheSel` from the `sysCapRc` Reg): a DC-only selector must still leave the
    * I-cache completely alone, while the D-side walk runs exactly as before. */
  test("CPUSH DC-only selector still issues the D-side walk and never pulses icMaintPulse") {
    M68kSim().compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      init(dut)
      // sysRc = 0b0101 : scope=01 (Line), cache selector=01 (DC only).
      dut.sysRcIn #= 0x5
      applySysOp(dut, SysKind.CPUSH, 0x12345abcL)
      assert(dut.maintCmdCount.toInt == 1,
        s"expected exactly 1 maintCmdOut pulse, got ${dut.maintCmdCount.toInt}")
      assert(dut.icMaintCount.toInt == 0,
        "a DC-only CPUSH must not invalidate the I-cache (or, via the same signal, the BTB)")
      assert(dut.redirSeen.toBoolean && dut.redirPc.toLong == NEXT_PC,
        "CPUSH must still serialize + redirect to nextPc")
    }
  }

  /** The CINV arm re-derives the selector at the same S_MAINTWAIT exit, so cover its
    * IC-only encoding too (the plain "CINV IC" an SMC sequence actually issues). */
  test("CINV IC-only selector pulses icMaintPulse exactly once, at walk completion") {
    M68kSim().compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      init(dut)
      // sysRc = 0b1110 : scope=11 (All), cache selector=10 (IC only).
      dut.sysRcIn #= 0xE
      applySysOp(dut, SysKind.CINV, 0x12345abcL)
      assert(dut.icMaintCount.toInt == 1,
        s"expected exactly 1 icMaintPulse for one CINV IC, got ${dut.icMaintCount.toInt}")
      assert(!dut.flushSeen.toBoolean,
        "CINV spuriously pulsed the TLB flushAll (the P5.2 regression)")
      assert(dut.redirSeen.toBoolean && dut.redirPc.toLong == NEXT_PC,
        "CINV must still serialize + redirect to nextPc")
    }
  }
}
