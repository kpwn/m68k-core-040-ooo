package m68k040.socket

import m68k040.M68kSim
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** Spec section 13, "Byte order", third and fourth bullets, and the structural D23/D29
  * checks that simulation cannot reach (those are in tools/socket/check_socket_netlist.py).
  *
  * The whole-core socket top is far too large to elaborate in `test-fast`, so what this
  * suite proves is the CONNECTION LAYER: that the permutation is applied to exactly
  * w.data / w.strb / r.data and to nothing else, on a stand-in that has the same shape as
  * the real one. The real one's port surface is proved structurally, on the generated
  * Verilog, in Task 13 Step 4. */
class SocketTopByteOrderSpec extends AnyFunSuite {

  /** Exactly `M68kSocketTop`'s connection layer, over a stub core-side bundle. If the real
    * top's connect method changes, this DUT must be changed with it -- which is the point. */
  class ConnDut extends Component {
    val coreW    = in  Bits (128 bits)
    val coreStrb = in  Bits (16 bits)
    val sockR    = in  Bits (128 bits)
    val coreAddr = in  UInt (32 bits)
    val sockW    = out Bits (128 bits)
    val sockStrb = out Bits (16 bits)
    val coreR    = out Bits (128 bits)
    val sockAddr = out UInt (32 bits)
    sockW    := SocketByteOrder.permuteData(coreW)
    sockStrb := SocketByteOrder.permuteStrb(coreStrb)
    coreR    := SocketByteOrder.permuteData(sockR)
    sockAddr := coreAddr            // NEVER permuted -- D3
  }

  test("a byte written at address A is the byte a socket model reads at address A") {
    SimConfig.compile(new ConnDut).doSim("roundtrip", seed = 1) { dut =>
      dut.coreAddr #= 0
      for (o <- 0 until 16; v <- Seq(0x01, 0x7F, 0xA5, 0xFF)) {
        // Core side: a store of byte value `v` at line offset `o`.
        val coreBytes = (0 until 16).map(i => if (i == o) v else 0)
        dut.coreW    #= coreBytes.zipWithIndex.foldLeft(BigInt(0)) {
                          case (a, (b, i)) => a | (BigInt(b) << (8 * i)) }
        dut.coreStrb #= BigInt(1) << o
        sleep(1)
        // Socket side: a model in the SoC's convention -- the byte at offset 4W+j lives at
        // bit 32W + 24 - 8j, and its strobe bit is 4W + (3-j).
        val w = o / 4; val j = o % 4
        val sockIdx = 4 * w + (3 - j)
        val gotByte = ((dut.sockW.toBigInt >> (8 * sockIdx)) & 0xff).toInt
        assert(gotByte == v, f"offset $o: socket byte $sockIdx is 0x$gotByte%02X, wanted 0x$v%02X")
        assert(dut.sockStrb.toBigInt == (BigInt(1) << sockIdx),
          f"offset $o: socket strobe 0x${dut.sockStrb.toBigInt}%04X, wanted bit $sockIdx")
        // And the read direction restores it -- the involution, end to end.
        dut.sockR #= dut.sockW.toBigInt
        sleep(1)
        assert(dut.coreR.toBigInt == dut.coreW.toBigInt,
          "the read permutation did not restore the core's convention")
      }
    }
  }

  test("D3: the address is never permuted") {
    SimConfig.compile(new ConnDut).doSim("addr-untouched", seed = 2) { dut =>
      dut.coreW #= 0; dut.coreStrb #= 0; dut.sockR #= 0
      for (a <- Seq(0L, 1L, 0x1234_5678L, 0xFFFF_FFF0L)) {
        dut.coreAddr #= a
        sleep(1)
        assert(dut.sockAddr.toLong == a,
          f"address 0x$a%08X came out as 0x${dut.sockAddr.toLong}%08X -- permuting an " +
          f"address is a bug (spec 2.3)")
      }
    }
  }
}
