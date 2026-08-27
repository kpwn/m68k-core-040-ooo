package m68k040.core

import spinal.core._
import spinal.core.fiber.Fiber
import spinal.lib.misc.database.Database
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}

/** Top-level core shell. Owns the Database blackboard and the PluginHost; all
  * behavior lives in the hosted plugins (spec invariant #3).
  *
  * @param exposeDebugPorts 2026-08-27 boot-investigation ILA taps (default
  *   false, zero cost to every existing consumer): RobPlugin's internal
  *   interrupt-recognition signals (`normalIrqGate`/`flushing`/`excIdle`/
  *   `iplActive`/`branchRedirect`/`p0.first`/`preciseDrainBusyIn`/
  *   `inhibitedLoadBusyIn`/`interruptPending`/head PC) are plain internal
  *   wires of a plugin HOSTED BY this component -- reading them from a
  *   PARENT component (e.g. M68kSocketTop) is a genuine SpinalHDL hierarchy
  *   violation (directionless signal, not a port), confirmed empirically.
  *   The fix is structural, not `simPublic()` (which only affects
  *   simulation visibility, not synthesis-time cross-component readability):
  *   a small wiring Area built HERE, inside M68kCore's own scope (so the
  *   `host[RobPlugin]` read below never crosses a component boundary),
  *   whose ONLY job is to re-drive these values onto real `out` ports of
  *   THIS component -- which a parent CAN legally read (SpinalHDL rule:
  *   "a port of a direct child component"). */
class M68kCore(val plugins: Seq[FiberPlugin], exposeDebugPorts: Boolean = false) extends Component {
  setDefinitionName("M68kCore")
  private val database = new Database
  private val host = database on (new PluginHost)
  database.on {
    host.asHostOf(plugins)
  }

  val dbg040 = if (exposeDebugPorts) new Area {
    val normalIrqGate      = out Bool ()
    val flushing            = out Bool ()
    val excIdle             = out Bool ()
    val iplActive           = out Bool ()
    val branchRedirect      = out Bool ()
    val p0First             = out Bool ()
    val preciseDrainBusyIn  = out Bool ()
    val inhibitedLoadBusyIn = out Bool ()
    val interruptPending    = out Bool ()
    val headPc              = out UInt (32 bits)
    Fiber.build {
      val rob = host[m68k040.rob.RobPlugin]
      normalIrqGate      := rob.logic.normalIrqGate
      flushing            := rob.logic.flushing
      excIdle             := rob.logic.excIdle
      iplActive           := rob.logic.iplActive
      branchRedirect      := rob.logic.branchRedirect
      p0First             := rob.logic.p0.first
      preciseDrainBusyIn  := rob.logic.preciseDrainBusyIn
      inhibitedLoadBusyIn := rob.logic.inhibitedLoadBusyIn
      interruptPending    := rob.logic.interruptPending
      headPc              := rob.logic.p0.pc
    }
  } else null
}
