package m68k040.cache

import spinal.core._
import spinal.lib.misc.database.Database
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}

/** Minimal host wrapper that elaborates a set of plugins (the I-cache + its
  * dependencies). Used for the integration elaboration smoke; the real core
  * shell is m68k040.core.M68kCore. */
case class IcacheTop(plugins: Seq[FiberPlugin]) extends Component {
  setDefinitionName("IcacheTop")
  val database = new Database
  val host     = database on (new PluginHost)
  database.on {
    host.asHostOf(plugins)
  }
}
