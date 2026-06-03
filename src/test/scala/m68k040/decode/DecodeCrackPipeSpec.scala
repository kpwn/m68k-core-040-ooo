package m68k040.decode

import m68k040.{M68kParams, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.mmu.IdentityTranslationPlugin
import m68k040.cache.{IcachePlugin, IcacheSim}
import m68k040.frontend.FetchAlignPlugin
import m68k040.isa.MemOp
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** Task 5: the µop expansion queue sits between DecodeStage and rename. A packet
  * that cracks to 2 µops (a memSimple load) must appear, in program order, on the
  * rename-facing stream across consecutive cycles. */
class DecodeCrackPipeSpec extends AnyFunSuite {
  class Dut extends Component {
    val db = new Database
    val host = db on (new PluginHost)
    val ic = new IcachePlugin
    val fa = new FetchAlignPlugin
    val dec = new DecodeStage
    val sink = new UopSinkPlugin
    db.on { host.asHostOf(Seq[FiberPlugin](
      new ParamPlugin(M68kParams()), new IdentityTranslationPlugin, ic, fa, dec, sink)) }
  }

  test("memSimple load cracks to load+op in program order on the rename stream", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      val base = 0x8000L
      // ADD.L (A0),D1 (0xD290) ; MOVEQ #5,D2 (0x7405)
      // Expected µop order: LOAD(dst=T0=16), ADD(srcB=16,dst=1), MOVEQ(dst=2,imm=5)
      IcacheSim.attachMemoryWithWords(dut.ic.logic.axi, cd, base, Seq(0xD290, 0x7405, 0x4E71, 0x4E71))
      dut.sink.logic.uopsOut.ready #= false
      dut.fa.logic.resume.valid #= false
      dut.fa.logic.redirect.valid #= true; dut.fa.logic.redirect.payload #= base
      cd.waitSampling(); dut.fa.logic.redirect.valid #= false
      dut.sink.logic.uopsOut.ready #= true

      // Collect the first 3 µops in order (memOp, dstReg, srcBReg).
      case class U(memOp: String, dst: Int, srcB: Int, srcBValid: Boolean, imm: Long)
      val got = scala.collection.mutable.ArrayBuffer[U]()
      def snap(i: Int): U = {
        val p = dut.sink.logic.uopsOut.payload(i)
        U(p.memOp.toEnum.toString, p.dstReg.toInt, p.srcBReg.toInt, p.srcBValid.toBoolean, p.imm.toLong & 0xffffffffL)
      }
      var guard = 0
      while (got.size < 3 && guard < 300) {
        if (dut.sink.logic.uopsOut.valid.toBoolean && dut.sink.logic.uopsOut.ready.toBoolean) {
          got += snap(0)
          if (dut.sink.logic.u1v.toBoolean) got += snap(1)
        }
        cd.waitSampling(); sleep(1); guard += 1
      }
      assert(got.size >= 3, s"only got ${got.size} µops: $got")
      val seq = got.take(3)
      // µop0 = LOAD into T0
      assert(seq(0).memOp == MemOp.LOAD.toString && seq(0).dst == 16, s"µop0 should be LOAD->T0: ${seq(0)}")
      // µop1 = ADD reading T0 (srcB=16), dst D1
      assert(seq(1).memOp == MemOp.NONE.toString && seq(1).srcBValid && seq(1).srcB == 16 && seq(1).dst == 1,
        s"µop1 should be ADD srcB=T0 dst=D1: ${seq(1)}")
      // µop2 = MOVEQ #5,D2
      assert(seq(2).dst == 2 && seq(2).imm == 5, s"µop2 should be MOVEQ #5,D2: ${seq(2)}")
    }
  }
}
