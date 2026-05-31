package m68k040.frontend

import m68k040.VerilatorTest
import m68k040.cache.ChunkPredecode
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import org.scalatest.funsuite.AnyFunSuite

class AlignerSpec extends AnyFunSuite {
  class Dut extends Component {
    val headPc = in UInt(32 bits)
    val words  = in Vec(Bits(16 bits), Aligner.WINDOW)
    val preds  = in Vec(ChunkPredecode(), Aligner.WINDOW)
    val avail  = in UInt(4 bits)
    val res    = out(Aligner.Result())
    res := Aligner.align(headPc, words, preds, avail)
  }
  def setAll(dut: Dut, simple: Boolean, len: Int): Unit =
    for (i <- 0 until Aligner.WINDOW) { dut.words(i) #= 0; dut.preds(i).simple #= simple; dut.preds(i).lenWords #= len }
  def setPred(dut: Dut, i: Int, simple: Boolean, len: Int): Unit = { dut.preds(i).simple #= simple; dut.preds(i).lenWords #= len }

  test("two adjacent 1-word simple ops -> 2-wide", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      dut.headPc #= 0x1000; setAll(dut, simple=true, len=1); dut.avail #= 10; sleep(1)
      assert(dut.res.slot0Valid.toBoolean && dut.res.slot1Valid.toBoolean)
      assert(dut.res.slot0.lenWords.toInt == 1 && dut.res.slot1.lenWords.toInt == 1)
      assert(dut.res.slot1.pc.toLong == 0x1002)
      assert(dut.res.shiftWords.toInt == 2)
      assert(!dut.res.stall.toBoolean && !dut.res.complex.toBoolean)
    }
  }
  test("3-word simple then 1-word simple -> 2-wide, slot1 pc=+6, shift=4", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      dut.headPc #= 0x2000; setAll(dut, simple=true, len=1); setPred(dut, 0, simple=true, len=3); dut.avail #= 10; sleep(1)
      assert(dut.res.slot0Valid.toBoolean && dut.res.slot1Valid.toBoolean)
      assert(dut.res.slot0.lenWords.toInt == 3)
      assert(dut.res.slot1.pc.toLong == 0x2006 && dut.res.slot1.lenWords.toInt == 1)
      assert(dut.res.shiftWords.toInt == 4)
    }
  }
  test("simple head + complex second -> 1-wide, shift=L0", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      dut.headPc #= 0x3000; setAll(dut, simple=true, len=1); setPred(dut, 1, simple=false, len=0); dut.avail #= 10; sleep(1)
      assert(dut.res.slot0Valid.toBoolean && !dut.res.slot1Valid.toBoolean)
      assert(dut.res.shiftWords.toInt == 1 && !dut.res.complex.toBoolean)
    }
  }
  test("complex head -> 1-wide complex packet, stall, shift=0", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      dut.headPc #= 0x4000; setAll(dut, simple=true, len=1); setPred(dut, 0, simple=false, len=0); dut.avail #= 10; sleep(1)
      assert(dut.res.slot0Valid.toBoolean && dut.res.slot0.complex.toBoolean)
      assert(!dut.res.slot1Valid.toBoolean && dut.res.complex.toBoolean && dut.res.stall.toBoolean)
      assert(dut.res.shiftWords.toInt == 0)
    }
  }
  test("insufficient bytes for head -> stall, slot0 invalid", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      dut.headPc #= 0x5000; setAll(dut, simple=true, len=3); dut.avail #= 2; sleep(1)
      assert(!dut.res.slot0Valid.toBoolean && dut.res.stall.toBoolean && dut.res.shiftWords.toInt == 0)
    }
  }
}
