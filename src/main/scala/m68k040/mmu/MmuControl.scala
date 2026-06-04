package m68k040.mmu

import m68k040.services.MmuControlService
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.FiberPlugin

/** The single 68040 MMU control owner: holds `mmuEnable` (TC enable) and `rootPtr`
  * (URP/SRP) and publishes them via `MmuControlService` for BOTH the ITLB and the
  * DTLB to read. The 68040 has ONE MMU — I and D translation share enable + root.
  *
  *  - `mmuEnable` is RegInit(False): MMU-disabled is the power-on default, so every
  *    existing MMU-off test / lock-step is pure identity (unchanged).
  *  - Both regs are sim-poked (and, later, MOVEC-driven). They are exposed on
  *    `logic.mmuEnable` / `logic.rootPtr` (simPublic) for the directed tests, and
  *    drivable from a registered synth-top input via `setControl` so OOC synth can
  *    NOT const-fold them (honest gate). */
class MmuControlPlugin extends FiberPlugin with MmuControlService {
  var _mmuEnable: Bool = null
  var _rootPtr:   UInt = null

  override def mmuEnable: Bool = _mmuEnable
  override def rootPtr:   UInt = _rootPtr

  val logic = during build new Area {
    // The control regs. RegInit/Reg-init so a standalone DUT (no external driver)
    // elaborates with no UNASSIGNED REGISTER; sim pokes / setControl override.
    val mmuEnable = RegInit(False); mmuEnable.simPublic()
    val rootPtr   = Reg(UInt(32 bits)) init 0; rootPtr.simPublic()
    _mmuEnable = mmuEnable
    _rootPtr   = rootPtr
  }
}
