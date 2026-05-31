package m68k040.cache

import spinal.core._
import spinal.lib._
import org.scalatest.funsuite.AnyFunSuite

class IcacheTypesSpec extends AnyFunSuite {
  test("fetch/translation bundles have the spec widths") {
    SpinalConfig().generateVerilog(new Component {
      val cmd = slave(Stream(FetchCmd()))
      val rsp = master(Flow(FetchRsp()))
      cmd.ready := True
      rsp.valid := cmd.valid
      rsp.payload.assignDontCare()
      assert(cmd.payload.pc.getWidth == 32)
      assert(rsp.payload.data.getWidth == 64)
      val treq = TranslationReq(); val trsp = TranslationRsp()
      assert(treq.vpn.getWidth == 20 && trsp.ppn.getWidth == 20)
    })
  }
}
