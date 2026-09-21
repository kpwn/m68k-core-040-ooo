package m68k040.execute

import m68k040.VerilatorTest
import m68k040.decode.DecOp
import m68k040.isa.Size
import m68k040.execute.regfile.{RegFilePluginInt, RegFilePluginNzvc, RegFilePluginX}
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.database.Database
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}

class BcdNarrowSpec extends AnyFunSuite {
  class CheckedAlu extends AluEuPlugin {
    val check = during build new Area {
      val d = logic
      val srcA, srcB = in Bits(32 bits)
      val x, oldZ, subtract, nbcd = in Bool()
      // Test-only S1 injection. Arithmetic and final result/flag muxes remain the
      // actual production cone, rather than a copied narrow implementation.
      d.s1Src1.allowOverride; d.s1Src1 := srcA
      d.s1Src2.allowOverride; d.s1Src2 := srcB
      d.s1Nzvc.allowOverride; d.s1Nzvc := False ## oldZ ## B(0, 2 bits)
      d.s1X.allowOverride; d.s1X := x
      d.u1.op.allowOverride; d.u1.op := Mux(nbcd, DecOp.NBCD, DecOp.BCD)
      d.u1.bcdSub.allowOverride; d.u1.bcdSub := subtract
      val observed = d.mergedResult ## d.finalNzvc ## d.finalX
      observed.simPublic()
    }
  }
  class Dut extends Component {
    val db = new Database
    val host = db on new PluginHost
    val eu = new CheckedAlu
    val src = new AluEuSourcePlugin
    db.on { host.asHostOf(Seq[FiberPlugin](new RegFilePluginInt,
      new RegFilePluginNzvc, new RegFilePluginX, eu, src)) }
  }

  // Original unsigned-32 sequence, including the asymmetric N/V masking order.
  private def reference(dx: Int, dy: Int, x: Int, oldZ: Int, sub: Boolean): (Int, Int, Boolean) = {
    val mask = 0xffffffffL
    var r = ((dx & 15).toLong + (if (sub) -(dy & 15) - x else (dy & 15) + x)) & mask
    val vraw = (~r) & mask
    if (r > 9) r = (r + (if (sub) -6 else 6)) & mask
    r = (r + (dx & 240) + (if (sub) -(dy & 240) else (dy & 240))) & mask
    val carry = r > 0x99
    if (carry) r = (r + (if (sub) 0xa0 else -0xa0)) & mask
    if (sub) r &= 255
    val n = ((r >> 7) & 1).toInt
    val v = (((vraw & r) >> 7) & 1).toInt
    val byte = (r & 255).toInt
    val z = if (oldZ != 0 && byte == 0) 1 else 0
    (byte, (n << 3) | (z << 2) | (v << 1) | (if (carry) 1 else 0), carry)
  }

  test("actual BCD cone matches unsigned32 for all byte pairs, X, Z and NBCD", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      val s = dut.src.logic
      s.iValid #= false; s.iFlush #= false
      s.iOp #= DecOp.BCD; s.iSize #= Size.BYTE
      s.iUseImm #= false; s.iImm #= 0
      s.iPsrcA #= 0; s.iPsrcAValid #= true
      s.iPsrcB #= 0; s.iPsrcBValid #= true
      s.iPdst #= 1; s.iPdstValid #= true
      s.iWritesNz #= true; s.iPNzvcDst #= 1
      s.iWritesX #= true; s.iPXDst #= 1; s.iRobId #= 0
      s.iShiftOp #= 0; s.iShiftDir #= false; s.iToCcr #= false
      s.iReadsNz #= true; s.iPNzvcSrc #= 0
      s.iReadsX #= true; s.iPXSrc #= 0
      s.obsIntAddr #= 0; s.obsNzvcAddr #= 0
      s.obsXAddr #= 0
      val p = dut.eu.check
      p.srcA #= 0; p.srcB #= 0; p.x #= false; p.oldZ #= false
      p.subtract #= false; p.nbcd #= false
      cd.waitSampling(80)
      var checked = 0
      def check(dx: Int, dy: Int, x: Int, z: Int, subtract: Boolean, nbcd: Boolean): Unit = {
        val upper = ((dx.toLong * 0x13579L) ^ (dy.toLong * 0x2468L) ^ 0xa55a0000L) & 0xffffff00L
        p.srcA #= BigInt(upper | dx)
        p.srcB #= BigInt(0x5ac30000L | dy)
        p.x #= (x != 0); p.oldZ #= (z != 0)
        p.subtract #= subtract; p.nbcd #= nbcd
        cd.waitSampling(); sleep(1)
        val (byte, flags, carry) = reference(if (nbcd) 0 else dx, dy, x, z, subtract)
        val expected = (BigInt(upper | byte) << 5) | BigInt((flags << 1) | (if (carry) 1 else 0))
        val actual = p.observed.toBigInt
        assert(actual == expected,
          s"dx=$dx dy=$dy X=$x Z=$z sub=$subtract nbcd=$nbcd actual=$actual expected=$expected")
        checked += 1
      }
      for (dx <- 0 until 256; dy <- 0 until 256; x <- 0 to 1; z <- 0 to 1; sub <- Seq(false, true))
        check(dx, dy, x, z, sub, nbcd = false)
      for (dy <- 0 until 256; x <- 0 to 1; z <- 0 to 1)
        check(dy, dy, x, z, subtract = true, nbcd = true)
      assert(checked == 525312)
      println(s"BCD_ACTUAL_CONE_PASS checked=$checked addsub=524288 nbcd=1024 full_flags_and_upper_merge=true")
    }
  }
}
