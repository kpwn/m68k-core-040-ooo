package m68k040.core

import m68k040.{Global, M68kParams}
import spinal.lib.misc.plugin.FiberPlugin

/** Publishes the M68kParams sizing into the Database during the setup phase so
  * every other plugin can read it during build. Sole producer of Global sizing keys. */
class ParamPlugin(p: M68kParams) extends FiberPlugin {
  val logic = during setup {
    Global.ROB_DEPTH.set(p.robDepth)
    Global.PHYS_INT_REGS.set(p.physInt)
    Global.PHYS_NZVC_REGS.set(p.physNzvc)
    Global.PHYS_X_REGS.set(p.physX)
    Global.DECODE_WIDTH.set(p.decodeWidth)
    Global.RETIRE_WIDTH.set(p.retireWidth)
    Global.L1I_KB.set(p.l1iKb)
    Global.L1I_WAYS.set(p.l1iWays)
    Global.L1I_LINE_BYTES.set(p.l1iLineBytes)
  }
}
