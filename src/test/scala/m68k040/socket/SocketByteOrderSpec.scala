package m68k040.socket

import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** Spec section 2.2 (D2) and section 13 "Byte order", first and last bullets.
  *
  * The transform is: reverse the four bytes inside each 32-bit lane, reverse each
  * 4-bit strobe nibble, LEAVE LANE ORDER ALONE. It is NOT a full 128-bit byte
  * reverse and NOT a word-order reversal. Spec section 2.3: "Applying it twice
  * would be a no-op that looks like a fix" -- so involution is tested explicitly,
  * and so is the property that distinguishes it from a full reverse (a value that
  * is symmetric under one but not the other).
  *
  * Deliberately UNTAGGED so `make test-fast` runs it: build.sbt's `fastTest`
  * excludes m68k040.VerilatorTest / SlowTest / BoardTest. */
class SocketByteOrderSpec extends AnyFunSuite {

  /** Tiny DUT: hardware permutation of one beat, both widths the socket uses. */
  class ByteOrderDut(w: Int) extends Component {
    val din  = in  Bits (w bits)
    val sin  = in  Bits (w / 8 bits)
    val dout = out Bits (w bits)
    val sout = out Bits (w / 8 bits)
    dout := SocketByteOrder.permuteData(din)
    sout := SocketByteOrder.permuteStrb(sin)
  }

  /** Byte i of a BigInt beat, little-endian byte indexing (byte i = bits [8i+7:8i]),
    * which is the core's own convention (DcacheTypes.scala:169-171). */
  private def byteOf(v: BigInt, i: Int): Int = ((v >> (8 * i)) & 0xff).toInt
  private def fromBytes(bs: Seq[Int]): BigInt =
    bs.zipWithIndex.foldLeft(BigInt(0)) { case (a, (b, i)) => a | (BigInt(b & 0xff) << (8 * i)) }

  test("the Scala model is a per-32-bit-lane byte reversal, not a full reverse") {
    // 8 bytes = 2 lanes. Lane order must be preserved.
    val in  = Seq(0x00, 0x01, 0x02, 0x03, 0x10, 0x11, 0x12, 0x13)
    val out = SocketByteOrder.modelData(in)
    assert(out == Seq(0x03, 0x02, 0x01, 0x00, 0x13, 0x12, 0x11, 0x10),
      s"got $out")
    // A FULL reverse would give 0x13,0x12,...,0x00 -- lane order swapped. Assert
    // explicitly that we are NOT that, because the two agree on many inputs.
    assert(out != in.reverse, "permutation must not be a full byte reverse")
  }

  test("the Scala model is an involution at both socket widths") {
    for (nBytes <- Seq(16, 32)) {
      val in = (0 until nBytes).map(i => (i * 37 + 11) & 0xff)
      assert(SocketByteOrder.modelData(SocketByteOrder.modelData(in)) == in,
        s"not an involution at $nBytes bytes")
      val s = (0 until nBytes).map(i => ((i * 5) & 3) == 0)
      assert(SocketByteOrder.modelStrb(SocketByteOrder.modelStrb(s)) == s,
        s"strobe permutation not an involution at $nBytes bits")
    }
  }

  test("the strobe permutation reverses each nibble and preserves popcount") {
    val s = Seq(true, false, false, false, false, true, false, false)
    assert(SocketByteOrder.modelStrb(s) ==
      Seq(false, false, false, true, false, false, true, false), "nibble reversal wrong")
    val r = SocketByteOrder.modelStrb(s)
    assert(r.count(identity) == s.count(identity), "popcount not preserved")
  }

  test("hardware matches the Scala model exhaustively over single-byte positions, 128b") {
    SimConfig.compile(new ByteOrderDut(128)).doSim("perm128", seed = 1) { dut =>
      for (pos <- 0 until 16) {
        val bytes = (0 until 16).map(i => if (i == pos) 0xA5 else 0x00)
        dut.din #= fromBytes(bytes)
        dut.sin #= BigInt(1) << pos
        sleep(1)
        val gotD = (0 until 16).map(i => byteOf(dut.dout.toBigInt, i))
        assert(gotD == SocketByteOrder.modelData(bytes),
          s"data mismatch at byte $pos: hw=$gotD model=${SocketByteOrder.modelData(bytes)}")
        val sIn  = (0 until 16).map(_ == pos)
        val gotS = (0 until 16).map(i => ((dut.sout.toBigInt >> i) & 1) == 1)
        assert(gotS == SocketByteOrder.modelStrb(sIn),
          s"strobe mismatch at bit $pos: hw=$gotS")
      }
    }
  }

  test("hardware matches the Scala model on random beats, 256b (axi_i width)") {
    SimConfig.compile(new ByteOrderDut(256)).doSim("perm256", seed = 2) { dut =>
      val rnd = new scala.util.Random(7)
      for (_ <- 0 until 64) {
        val bytes = (0 until 32).map(_ => rnd.nextInt(256))
        dut.din #= fromBytes(bytes)
        dut.sin #= BigInt(0)
        sleep(1)
        val got = (0 until 32).map(i => byteOf(dut.dout.toBigInt, i))
        assert(got == SocketByteOrder.modelData(bytes), s"256b mismatch: $got")
      }
    }
  }

  test("hardware permutation is an involution (apply twice == identity)") {
    SimConfig.compile(new ByteOrderDut(128)).doSim("involution", seed = 3) { dut =>
      val rnd = new scala.util.Random(11)
      for (_ <- 0 until 32) {
        val bytes = (0 until 16).map(_ => rnd.nextInt(256))
        dut.din #= fromBytes(bytes)
        sleep(1)
        val once = (0 until 16).map(i => byteOf(dut.dout.toBigInt, i))
        dut.din #= fromBytes(once)
        sleep(1)
        val twice = (0 until 16).map(i => byteOf(dut.dout.toBigInt, i))
        assert(twice == bytes, s"not an involution in hardware: $twice vs $bytes")
      }
    }
  }

  test("a socket-convention word read back at its own address is the byte we wrote") {
    // Spec section 13: "A byte written at address A through axi_d is the byte a
    // socket-convention model reads at address A." Address-to-lane relationship,
    // stated arithmetically: core byte offset o = 4W + j must land at socket bit
    // 32W + 24 - 8j, i.e. socket byte index 4W + (3 - j).
    for (o <- 0 until 16) {
      val bytes = (0 until 16).map(i => if (i == o) 0x5A else 0x00)
      val out   = SocketByteOrder.modelData(bytes)
      val w = o / 4; val j = o % 4
      assert(out(4 * w + (3 - j)) == 0x5A,
        s"core byte offset $o did not land at socket index ${4 * w + (3 - j)}")
      assert(out.count(_ == 0x5A) == 1, "the byte was duplicated")
    }
  }
}
