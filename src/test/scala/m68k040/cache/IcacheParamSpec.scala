package m68k040.cache

import m68k040.{Global, M68kParams}
import m68k040.core.ParamPlugin
import spinal.core._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

class IcacheParamSpec extends AnyFunSuite {
  test("ParamPlugin publishes L1I geometry keys") {
    SpinalConfig().generateVerilog(new Component {
      val db = new Database
      val host = db on (new PluginHost)
      val probe = out(Bool())
      val reader = new FiberPlugin {
        val logic = during build new Area {
          probe := Bool(Global.L1I_KB.get == 16 && Global.L1I_WAYS.get == 4 &&
                        Global.L1I_LINE_BYTES.get == 64)
        }
      }
      db.on { host.asHostOf(Seq[FiberPlugin](new ParamPlugin(M68kParams()), reader)) }
    })
  }
}
