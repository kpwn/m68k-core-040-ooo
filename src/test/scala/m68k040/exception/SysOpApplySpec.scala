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
    override def urp  = U(0, 32 bits)
    override def srp  = U(0, 32 bits)
    override def itt0 = U(0, 32 bits)
    override def itt1 = U(0, 32 bits)
    override def dtt0 = U(0, 32 bits)
    override def dtt1 = U(0, 32 bits)
    override def mmusr = mmusrReg
    override def setEnable = { val f = Flow(Bool());        f.valid := False; f.payload := False;          f }
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
      sysPc      = U(NEXT_PC - 2, 32 bits),
      sysNextPc  = U(NEXT_PC, 32 bits))

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
}
