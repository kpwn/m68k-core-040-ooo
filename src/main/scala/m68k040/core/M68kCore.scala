package m68k040.core

import spinal.core._
import spinal.lib.misc.database.Database
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}

/** Top-level core shell. Owns the Database blackboard and the PluginHost; all
  * behavior lives in the hosted plugins (spec invariant #3). */
class M68kCore(val plugins: Seq[FiberPlugin]) extends Component {
  setDefinitionName("M68kCore")
  val database = new Database
  val host = database on (new PluginHost)
  database.on {
    host.asHostOf(plugins)
  }
}
