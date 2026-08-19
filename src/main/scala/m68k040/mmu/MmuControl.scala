package m68k040.mmu

import m68k040.services.MmuControlService
import spinal.core._
import spinal.core.sim._
import spinal.lib.Flow
import spinal.lib.misc.plugin.FiberPlugin

/** The single 68040 MMU control owner: holds `mmuEnable` (TC enable), the separate
  * `urp`/`srp` root pointers, and the four transparent-translation registers
  * (`itt0`/`itt1`/`dtt0`/`dtt1`), and publishes them via `MmuControlService` for
  * BOTH the ITLB and the DTLB to read. The 68040 has ONE MMU — I and D translation
  * share enable + roots; each TLB Muxes urp-vs-srp itself per-access, and each side
  * consults its OWN pair of TT registers (I: itt0/itt1, D: dtt0/dtt1) to bypass the
  * walker entirely for a matching region (see [[TtMatch]]).
  *
  *  - `mmuEnable` is RegInit(False): MMU-disabled is the power-on default, so every
  *    existing MMU-off test / lock-step is pure identity (unchanged).
  *  - All regs are sim-pokeable (`logic.mmuEnable`/`logic.urp`/`logic.srp`/...,
  *    simPublic) for directed tests, AND commit-time MOVEC-writable (task #194,
  *    below) — an additional driver, not a replacement; direct pokes still work
  *    whenever the write Flow's `valid` is False (the default).
  *
  * Task #131 ATTEMPTED commit-time MOVEC write ports for mmuEnable/urp/srp and
  * REVERTED them after what was diagnosed (at the time) as a single, unexplained
  * sim-poke-persistence regression. Task #194 (2026-07-21) revisited this and found
  * it was actually TWO SEPARATE real issues, not one mystery — reconciling them here
  * since the original single-cause diagnosis (and this project's git history) doesn't
  * describe either precisely:
  *
  *  1. `top/FullCoreSynth.scala` had a registered-input override (`mmuEnableIn`/
  *     `urpIn`/`srpIn`, driving `mmuCtrl.mmuEnable` UNCONDITIONALLY from a top-level
  *     port) — a genuine two-driver conflict with an internal conditional writer.
  *     This ONLY affected that one synth-target DUT, not the test harnesses. Task
  *     #194 removed it (no longer needed or safe now that MOVEC gives mmuEnable/urp/
  *     srp a real, primary-IO-reachable driver through decode/rename/dispatch/commit).
  *
  *  2. SEPARATELY, the actual test harnesses (`ExecuteLockStepSpec`'s `FullCoreDut`,
  *     `PortedTestRunner`'s `FuzzCoreDut`) poke `dut.ctrl.logic.mmuEnable`/`urp`/`srp`
  *     directly with no competing driver — the SAME pattern already proven safe for
  *     every other simPublic committed register in this codebase (`ss.cacr`/
  *     `ss.itt0`/etc). But several `ExecuteLockStepSpec` tests were poking THESE
  *     specific registers very early, DURING the DUT's ~82-cycle reset/init-sweep
  *     window — harmless while the register was permanently RAZ/WI (nothing to
  *     reset-clobber), but once a real conditional writer exists, an early poke is
  *     just an ordinary register write that reset then overwrites, same as poking
  *     any other Reg too early. Task #194 fixed this by moving those pokes past the
  *     init-sweep wait — the SAME timing precedent already used for `ss.isp`/`msp`/
  *     `usp` in every other passing lock-step test.
  *
  * Both fixes were independently verified via `ExecuteLockStepSpec`'s own full
  * 394-test suite (byte-identical fail-list before/after, git-stash A/B) and the
  * full 763-test ported corpus (see task #194's commit messages / report). */
class MmuControlPlugin extends FiberPlugin with MmuControlService {
  var _mmuEnable: Bool = null
  var _pageSize8K: Bool = null
  var _urp:       UInt = null
  var _srp:       UInt = null
  var _itt0:      UInt = null
  var _itt1:      UInt = null
  var _dtt0:      UInt = null
  var _dtt1:      UInt = null
  var _mmusr:     UInt = null

  var _setEnable: Flow[Bool] = null
  var _setPageSize: Flow[Bool] = null
  var _setUrp:    Flow[UInt] = null
  var _setSrp:    Flow[UInt] = null
  var _setItt0:   Flow[UInt] = null
  var _setItt1:   Flow[UInt] = null
  var _setDtt0:   Flow[UInt] = null
  var _setDtt1:   Flow[UInt] = null
  var _setMmusr:  Flow[UInt] = null

  override def mmuEnable: Bool = _mmuEnable
  override def pageSize8K: Bool = _pageSize8K
  override def urp:       UInt = _urp
  override def srp:       UInt = _srp
  override def itt0:      UInt = _itt0
  override def itt1:      UInt = _itt1
  override def dtt0:      UInt = _dtt0
  override def dtt1:      UInt = _dtt1
  override def mmusr:     UInt = _mmusr

  override def setEnable: Flow[Bool] = _setEnable
  override def setPageSize: Flow[Bool] = _setPageSize
  override def setUrp:    Flow[UInt] = _setUrp
  override def setSrp:    Flow[UInt] = _setSrp
  override def setItt0:   Flow[UInt] = _setItt0
  override def setItt1:   Flow[UInt] = _setItt1
  override def setDtt0:   Flow[UInt] = _setDtt0
  override def setDtt1:   Flow[UInt] = _setDtt1
  override def setMmusr:  Flow[UInt] = _setMmusr

  val logic = during build new Area {
    // The control regs. RegInit/Reg-init so a standalone DUT (no external driver)
    // elaborates with no UNASSIGNED REGISTER; sim pokes override.
    val mmuEnable = RegInit(False); mmuEnable.simPublic()
    // TCR.P (task #195): real MC68040 page-size bit (TCR bit 14). RegInit(False) =
    // 4KB pages, matching the architectural TCR-undefined-out-of-reset case treated
    // as the pre-#195 hardcoded behavior — every existing MMU test stays bit-for-bit
    // identical until software explicitly writes TCR.P=1 via MOVEC.
    val pageSize8K = RegInit(False); pageSize8K.simPublic()
    val urp = Reg(UInt(32 bits)) init 0; urp.simPublic()
    val srp = Reg(UInt(32 bits)) init 0; srp.simPublic()
    val itt0 = Reg(UInt(32 bits)) init 0; itt0.simPublic()
    val itt1 = Reg(UInt(32 bits)) init 0; itt1.simPublic()
    val dtt0 = Reg(UInt(32 bits)) init 0; dtt0.simPublic()
    val dtt1 = Reg(UInt(32 bits)) init 0; dtt1.simPublic()
    // MMUSR (task #198): PTEST's result register. No sim-poke precedent needed (nothing
    // pokes it directly today), but simPublic for consistency/debuggability like every
    // other committed reg here.
    val mmusr = Reg(UInt(32 bits)) init 0; mmusr.simPublic()

    // ── commit-time write ports (mirrors SystemState's setVbr/setUsp/setCacr/setItt0
    // pattern exactly: Flow, allowOverride, default-idle, a single `when` writer). ──
    val setEnable = Flow(Bool())
    val setPageSize = Flow(Bool())
    val setUrp    = Flow(UInt(32 bits))
    val setSrp    = Flow(UInt(32 bits))
    val setItt0   = Flow(UInt(32 bits))
    val setItt1   = Flow(UInt(32 bits))
    val setDtt0   = Flow(UInt(32 bits))
    val setDtt1   = Flow(UInt(32 bits))
    val setMmusr  = Flow(UInt(32 bits))
    setEnable.valid.allowOverride; setEnable.valid := False; setEnable.payload.allowOverride; setEnable.payload := False
    setEnable.valid.simPublic(); setEnable.payload.simPublic()
    setPageSize.valid.allowOverride; setPageSize.valid := False; setPageSize.payload.allowOverride; setPageSize.payload := False
    setPageSize.valid.simPublic(); setPageSize.payload.simPublic()
    setUrp.valid.simPublic()
    setUrp.valid.allowOverride;    setUrp.valid := False;    setUrp.payload.allowOverride;    setUrp.payload := U(0, 32 bits)
    setSrp.valid.allowOverride;    setSrp.valid := False;    setSrp.payload.allowOverride;    setSrp.payload := U(0, 32 bits)
    setItt0.valid.allowOverride;   setItt0.valid := False;   setItt0.payload.allowOverride;   setItt0.payload := U(0, 32 bits)
    setItt1.valid.allowOverride;   setItt1.valid := False;   setItt1.payload.allowOverride;   setItt1.payload := U(0, 32 bits)
    setDtt0.valid.allowOverride;   setDtt0.valid := False;   setDtt0.payload.allowOverride;   setDtt0.payload := U(0, 32 bits)
    setDtt1.valid.allowOverride;   setDtt1.valid := False;   setDtt1.payload.allowOverride;   setDtt1.payload := U(0, 32 bits)
    setMmusr.valid.allowOverride;  setMmusr.valid := False;  setMmusr.payload.allowOverride;  setMmusr.payload := U(0, 32 bits)

    when(setEnable.valid) { mmuEnable := setEnable.payload }
    when(setPageSize.valid) { pageSize8K := setPageSize.payload }
    when(setUrp.valid)    { urp  := setUrp.payload }
    when(setSrp.valid)    { srp  := setSrp.payload }
    when(setItt0.valid)   { itt0 := setItt0.payload }
    when(setItt1.valid)   { itt1 := setItt1.payload }
    when(setDtt0.valid)   { dtt0 := setDtt0.payload }
    when(setDtt1.valid)   { dtt1 := setDtt1.payload }
    when(setMmusr.valid)  { mmusr := setMmusr.payload }

    _mmuEnable = mmuEnable
    _pageSize8K = pageSize8K
    _urp = urp
    _srp = srp
    _itt0 = itt0
    _itt1 = itt1
    _dtt0 = dtt0
    _dtt1 = dtt1
    _mmusr = mmusr
    _setEnable = setEnable
    _setPageSize = setPageSize
    _setUrp = setUrp
    _setSrp = setSrp
    _setItt0 = setItt0
    _setItt1 = setItt1
    _setDtt0 = setDtt0
    _setDtt1 = setDtt1
    _setMmusr = setMmusr
  }
}
