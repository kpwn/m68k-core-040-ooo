package m68k040.cache

import m68k040.VerilatorTest
import m68k040.mmu.IdentityTranslationPlugin
import m68k040.services.TranslationService
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

class IdentityTranslationSpec extends AnyFunSuite {
  class Dut extends Component {
    val io = new Bundle {
      val vpn = in UInt(20 bits)
      val ppn = out UInt(20 bits)
      val cacheable = out Bool()
    }
    val db = new Database
    val host = db on (new PluginHost)
    val xlate = new IdentityTranslationPlugin
    db.on { host.asHostOf(Seq[FiberPlugin](xlate)) }
    val svc = host[TranslationService]
    svc.req.vpn := io.vpn
    svc.req.supervisor := False
    io.ppn := svc.rsp.ppn
    io.cacheable := (svc.rsp.cacheMode === CacheMode.WRITETHROUGH)
  }

  test("identity translation: ppn==vpn, cacheable, no fault", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      for (v <- Seq(0x00040L, 0xABCDEL, 0xFFFFFL)) {
        dut.io.vpn #= v
        sleep(1)
        assert(dut.io.ppn.toLong == v, s"ppn should equal vpn $v")
        assert(dut.io.cacheable.toBoolean, "stub is cacheable")
      }
    }
  }
}
