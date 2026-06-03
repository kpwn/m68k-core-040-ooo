package m68k040.ls

import m68k040.VerilatorTest
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.amba4.axi.Axi4
import spinal.lib.bus.amba4.axi.sim.Axi4Master
import org.scalatest.funsuite.AnyFunSuite

class BehavioralMemSpec extends AnyFunSuite {

  // Passthrough Dut: a slave port the testbench's Axi4Master drives, wired straight
  // to a master port the BehavioralMemAgent serves. This lets us exercise the
  // behavioral memory standalone over a real Axi4 handshake.
  class Dut extends Component {
    val sAxi = slave(Axi4(BehavioralMem.axiConfig))
    val mAxi = master(Axi4(BehavioralMem.axiConfig))
    sAxi >> mAxi
  }

  test("write a 128-bit word with full strobe then read it back", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      val mem    = new BehavioralMemAgent(dut.mAxi, cd)
      val master = Axi4Master(dut.sAxi, cd)
      cd.waitSampling(4)

      val addr = 0x100
      // 128-bit value, little-endian bytes 0..15
      val bytes = (0 until 16).map(i => (0xA0 + i).toByte).toList
      master.write(addr, bytes) // default size = maxSize (16 bytes/beat), full strobe
      cd.waitSampling(4)
      // image must hold the bytes immediately after the write completes
      for (i <- 0 until 16) assert(mem.peekByte(addr + i) == (0xA0 + i), s"post-write byte $i = ${mem.peekByte(addr+i)}")

      val rd = master.read(addr, 16)
      assert(rd == bytes, s"readback mismatch: got $rd expected $bytes")
      // And the image itself holds the bytes.
      for (i <- 0 until 16) assert(mem.peekByte(addr + i) == (0xA0 + i), s"byte $i")
      cd.waitSampling(4)
    }
  }

  test("partial strobe only updates the enabled bytes", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      val mem    = new BehavioralMemAgent(dut.mAxi, cd)
      val master = Axi4Master(dut.sAxi, cd)
      cd.waitSampling(4)

      val addr = 0x200
      // preload all 16 bytes to 0x11
      mem.poke128(addr, (0 until 16).foldLeft(BigInt(0))((a, i) => a | (BigInt(0x11) << (8 * i))))
      // Write 12 bytes starting at addr+4. The Axi4Master left-pads to the 16-byte
      // bus word: strobe covers bytes 4..15, leaving 0..3 untouched (partial strobe).
      val payload = (0 until 12).map(i => (0x40 + i).toByte).toList
      master.write(addr + 4, payload)
      cd.waitSampling(4)

      assert(mem.peekByte(addr + 0) == 0x11, "untouched byte 0")
      assert(mem.peekByte(addr + 1) == 0x11, "untouched byte 1")
      assert(mem.peekByte(addr + 2) == 0x11, "untouched byte 2")
      assert(mem.peekByte(addr + 3) == 0x11, "untouched byte 3")
      for (i <- 0 until 12) assert(mem.peekByte(addr + 4 + i) == (0x40 + i), s"written byte ${4 + i}")
      cd.waitSampling(4)
    }
  }
}
