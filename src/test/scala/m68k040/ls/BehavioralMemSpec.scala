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

  test("write-before-ack: a read issued right after the B ack sees the written bytes", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      val mem    = new BehavioralMemAgent(dut.mAxi, cd)
      val master = Axi4Master(dut.sAxi, cd)
      cd.waitSampling(4)

      // Stale (pre-write) image. A racy harness that applied the bytes AFTER B could
      // let a refill read landing between B and the apply observe THIS value.
      val stale = (0 until 16).map(_ => 0x11.toByte).toList

      // Repeat over several addresses / spacings so the (formerly) decoupled apply-vs-B
      // race has many chances to manifest. The blocking `write` returns AFTER B is
      // observed; the very next `read` must therefore see the new bytes, never stale.
      for (k <- 0 until 16) {
        val addr  = 0x100 + k * 0x40
        mem.poke128(addr, stale.zipWithIndex.foldLeft(BigInt(0)) { case (a, (b, i)) =>
          a | (BigInt(b.toInt & 0xff) << (8 * i))
        })
        val fresh = (0 until 16).map(i => (0x40 + k + i).toByte).toList
        master.write(addr, fresh) // blocking: returns after the AXI-B ack
        val rd = master.read(addr, 16) // immediately after B — must see `fresh`
        assert(rd == fresh, s"iter $k addr=$addr%#x: read-after-B saw $rd, expected $fresh (stale=$stale)")
      }
      cd.waitSampling(4)
    }
  }

  test("write-before-ack: bytes are in the backing image by the B-completion callback", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      val mem    = new BehavioralMemAgent(dut.mAxi, cd)
      val master = Axi4Master(dut.sAxi, cd)
      cd.waitSampling(4)

      // Tighter probe: the write-completion (B) callback fires when the master observes
      // the AXI-B ack. The read agent serves refill reads straight out of `mem`, so the
      // invariant "a refill read of an acked address sees the written bytes" reduces to
      // "the bytes are already in `mem` when B is observed". Assert that directly from
      // the B callback (a blocking AXI read cannot run inside a sim callback). With the
      // decoupled-StreamMonitor apply this could observe stale; the write-before-ack fix
      // applies the bytes strictly before the B closure is driven.
      for (k <- 0 until 8) {
        val addr  = 0x800 + k * 0x40
        mem.poke128(addr, (0 until 16).foldLeft(BigInt(0))((a, _) => a)) // zero the line
        val fresh = (0 until 16).map(i => (0x70 + k + i).toByte).toList
        val done  = new java.util.concurrent.atomic.AtomicBoolean(false)
        master.writeCB(addr, fresh) {
          for (i <- 0 until 16)
            assert(mem.peekByte(addr + i) == ((0x70 + k + i) & 0xff),
              s"iter $k addr=$addr%#x byte $i: B-callback saw ${mem.peekByte(addr + i)}, expected ${(0x70 + k + i) & 0xff}")
          done.set(true)
        }
        cd.waitSamplingWhere(done.get())
      }
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
