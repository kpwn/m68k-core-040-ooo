package m68k040.mmu

import m68k040.services.MmuControlService
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.FiberPlugin

/** The single 68040 MMU control owner: holds `mmuEnable` (TC enable) and the
  * separate `urp`/`srp` root pointers, and publishes them via `MmuControlService`
  * for BOTH the ITLB and the DTLB to read. The 68040 has ONE MMU — I and D
  * translation share enable + roots; each TLB Muxes urp-vs-srp itself per-access.
  *
  *  - `mmuEnable` is RegInit(False): MMU-disabled is the power-on default, so every
  *    existing MMU-off test / lock-step is pure identity (unchanged).
  *  - All three regs are sim-pokeable (`logic.mmuEnable`/`logic.urp`/`logic.srp`,
  *    simPublic) for directed tests.
  *
  * Task #131 ATTEMPTED commit-time MOVEC write ports (`setEnable`/`setUrp`/`setSrp`,
  * a Flow driven from ExceptionUnit's S_APPLY case, mirroring SystemState.setVbr/
  * setUsp) — REVERTED 2026-07-16 after it was found to break sim-poke persistence
  * on `mmuEnable` for EVERY MMU-enabled lock-step test (a real, confirmed
  * regression: the previously-passing "page fault (non-resident)" test started
  * failing with mmuEnable reading False despite an explicit `#= true` poke, the
  * moment a `when(setEnable.valid){mmuEnable := setEnable.payload}` conditional
  * writer existed — even though `setEnable.valid` defaults False via allowOverride
  * and the structurally-IDENTICAL pattern works fine for SystemState's ss.isp/ss.msp
  * (also poked directly, also conditionally written, many passing tests). The
  * difference is suspected to be that `ss` is instantiated INLINE in the same
  * plugin/scope that drives it (`new SystemState` inside RobPlugin), whereas
  * `mmuCtrl` is a cross-plugin FiberPlugin/host reference (`host.get[MmuControlService]`)
  * — ExceptionUnit assigning into a Flow that lives in a DIFFERENT plugin's `logic`
  * Area apparently does not interact with sim pokes the same way. NOT root-caused —
  * needs a fresh, well-rested SpinalHDL-semantics investigation, not a repeat of
  * this pattern. See [[mmu-movec-urp-srp]] memory. The urp/srp SPLIT + per-access
  * SRP-vs-URP selection (DtlbPlugin/ItlbPlugin) and the MOVEC READ-side cases
  * (ExceptionUnit's Rc->Rn direction) are SAFE and kept — only the WRITE path
  * (real supervisor code programming URP/SRP/TCR via MOVEC) is reverted; those
  * Rc values are back to the RAZ/WI default case, exactly like before task #131. */
class MmuControlPlugin extends FiberPlugin with MmuControlService {
  var _mmuEnable: Bool = null
  var _urp:       UInt = null
  var _srp:       UInt = null

  override def mmuEnable: Bool = _mmuEnable
  override def urp:       UInt = _urp
  override def srp:       UInt = _srp

  val logic = during build new Area {
    // The control regs. RegInit/Reg-init so a standalone DUT (no external driver)
    // elaborates with no UNASSIGNED REGISTER; sim pokes override.
    val mmuEnable = RegInit(False); mmuEnable.simPublic()
    val urp = Reg(UInt(32 bits)) init 0; urp.simPublic()
    val srp = Reg(UInt(32 bits)) init 0; srp.simPublic()
    _mmuEnable = mmuEnable
    _urp = urp
    _srp = srp
  }
}
