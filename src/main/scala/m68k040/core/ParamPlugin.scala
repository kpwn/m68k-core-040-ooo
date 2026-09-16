package m68k040.core

import m68k040.{Global, M68kParams}
import spinal.lib.misc.plugin.FiberPlugin

/** Publishes the M68kParams sizing into the Database during the setup phase so
  * every other plugin can read it during build. Sole producer of Global sizing keys. */
class ParamPlugin(p: M68kParams) extends FiberPlugin {
  val logic = during setup {
    // THE ROB-depth guard — the exact analogue of `IssueQueuePlugin`'s int-pool require,
    // for the exact same failure mode. Most robId-carrying bundles (`IqContext`,
    // `SqAlloc`, `LsFault`, `BranchCompletion`, `CcrCompletion`, `UmWriteAlloc`, the
    // `StoreQueue`/`UmWriteQueue` io) are elaborated OUTSIDE any plugin host and so are
    // sized from the plain constant `Global.ROB_ID_W_DEFAULT`, not from this key. A
    // `robDepth` passed here that disagrees with that constant would give the ROB ring
    // one robId width and those payloads another — an ALIASING robId, i.e. a completion
    // or a store commit landing on the WRONG ROB entry, with no fault and no elaboration
    // error. Change the depth at `Global.ROB_DEPTH_DEFAULT`; `M68kParams.robDepth`
    // defaults to it, so there is exactly one number.
    require(p.robDepth == Global.ROB_DEPTH_DEFAULT,
      s"ROB depth disagreement: M68kParams.robDepth=${p.robDepth} but " +
      s"Global.ROB_DEPTH_DEFAULT=${Global.ROB_DEPTH_DEFAULT} (what every robId bundle " +
      "elaborated outside a plugin host is sized from). These MUST be equal -- set the " +
      "depth at Global.ROB_DEPTH_DEFAULT.")
    Global.ROB_DEPTH.set(p.robDepth)
    Global.PHYS_INT_REGS.set(p.physInt)
    Global.PHYS_NZVC_REGS.set(p.physNzvc)
    Global.PHYS_X_REGS.set(p.physX)
    Global.DECODE_WIDTH.set(p.decodeWidth)
    Global.RETIRE_WIDTH.set(p.retireWidth)
    Global.L1I_KB.set(p.l1iKb)
    Global.L1I_WAYS.set(p.l1iWays)
    Global.L1I_LINE_BYTES.set(p.l1iLineBytes)
    Global.BTB_ENTRIES.set(p.btbEntries)
    Global.RAS_ENTRIES.set(p.rasEntries)
    Global.GHR_BITS.set(p.gshareHistory)
    Global.PHT_ENTRIES.set(p.gshareEntries)
  }
}
