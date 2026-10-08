package m68k040.top

import m68k040.frontend.RasPlugin
import m68k040.rob.RobPlugin
import spinal.core._

/** The RAS recovery wiring (checkpoint save/restore, `branchRepair`, `branchSnapshot`),
  * in ONE place so `BackendWiringPlugin` and every sim harness that re-states it
  * (CoreBenchHarness, FuzzDut, ExecuteLockStepSpec) drive the identical network.
  *
  * With `branchSnapshot` OFF this is, term for term, the expression every site used to
  * write by hand:
  *   checkpointRestore = (doFlush && !feSuppress) || earlyFire || ftqMismatch
  *   repairValid       = earlyFire
  *
  * With it ON, a Tier-1 redirect whose branch carried a snapshot (`snapHit`) applies the
  * snapshot INSTEAD of the ROB-drain checkpoint and instead of the repair delta (the
  * snapshot already contains the branch's own push/pop). A Tier-1 redirect without one
  * (tag 0: a slot1 branch behind a slot1 training record, a fault packet, ...) falls back
  * to exactly today's restore + repair. A same-cycle `ftqMismatch` is younger than the
  * branch (wrong-path re-framing) and yields to the snapshot; a same-cycle real Tier-2
  * flush is older and wins (RasPlugin places `checkpointRestore` last).
  *
  * Timing: every term is a register Q (`doFlush` = `doFlushReg`, `earlySuppressFe`,
  * `earlyFire`, `ftqMismatch`, `rasSnapReg.valid`), so the restore enable stays a single
  * LUT of flops in front of the long ROB->frontend route, as before. */
object RasRecoveryWiring {
  def apply(ras: RasPlugin, rob: RobPlugin, doFlush: Bool, feSuppress: Bool,
            earlyFire: Bool, ftqMismatch: Bool): Unit = {
    require(rob.rasBranchRepair == ras.branchRepair,
      "RobPlugin.rasBranchRepair and RasPlugin.branchRepair must agree " +
      s"(rob=${rob.rasBranchRepair}, ras=${ras.branchRepair})")
    require(rob.logic.rasSnapOn == ras.branchSnapshot,
      s"RAS branchSnapshot: ROB store (${rob.logic.rasSnapOn}) and RasPlugin (${ras.branchSnapshot}) must agree")
    // checkpointSave is an ARM whose copy lands the cycle after, and the RAS itself gates
    // it with !checkpointRestore, so this driver is a BARE REGISTER OUTPUT (see RasPlugin).
    ras.logic.checkpointSave := rob.logic.countIsZero
    if (!ras.branchSnapshot) {
      ras.logic.checkpointRestore := (doFlush && !feSuppress) || earlyFire || ftqMismatch
      if (rob.rasBranchRepair) {
        ras.logic.repairValid := earlyFire
        ras.logic.repairKind  := rob.logic.rasRepairKind
        ras.logic.repairData  := rob.logic.rasRepairData
      }
    } else {
      val snap    = rob.logic.rasSnapReg
      val snapHit = earlyFire && snap.valid
      ras.logic.checkpointRestore := (doFlush && !feSuppress) || (earlyFire && !snap.valid) ||
                                     (ftqMismatch && !snapHit)
      ras.logic.snapApply := snapHit
      ras.logic.snapSp    := snap.sp
      ras.logic.snapCount := snap.count
      ras.logic.snapTos   := snap.tos
      if (rob.rasBranchRepair) {
        ras.logic.repairValid := earlyFire && !snap.valid
        ras.logic.repairKind  := rob.logic.rasRepairKind
        ras.logic.repairData  := rob.logic.rasRepairData
      }
    }
  }
}
