package m68k040.core

import m68k040.Global
import spinal.core._
import spinal.lib.misc.plugin.FiberPlugin

/** Placeholder plugin proving a plugin can block on a Database key during build
  * and emit real hardware. Deleted once the first real frontend plugin lands. */
// TODO(canary): delete this plugin when the first real frontend plugin lands.
class HelloPlugin extends FiberPlugin {
  val logic = during build new Area {
    val depth   = Global.ROB_DEPTH.get          // blocks until ParamPlugin sets it
    val counter = Reg(UInt(log2Up(depth) bits)) init (0)
    counter := counter + 1
  }
}
