package m68k040.cache

import m68k040.{M68kSim, VerilatorTest}
import m68k040.isa.Size
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** Directed test of the byte-lane split-merge (Task 2).
  *
  * A misaligned access at line offset `off` with `size` bytes that crosses the
  * 16-byte boundary draws low bytes from line A (offsets off..15) and the
  * remaining high bytes from line B (offsets 0..). The big-endian load merge
  * gathers them MSB-first; the store split produces per-line strobe+data. */
class ByteLaneSplitSpec extends AnyFunSuite {

  /** Tiny combinational DUT exposing the new byte-lane cross helpers. */
  class Dut extends Component {
    val lineA = in Bits (128 bits)
    val lineB = in Bits (128 bits)
    val off   = in UInt (4 bits)
    val size  = in(Size())
    val data  = in Bits (32 bits)

    val merged = out Bits (32 bits)
    val dataA  = out Bits (128 bits)
    val strbA  = out Bits (16 bits)
    val dataB  = out Bits (128 bits)
    val strbB  = out Bits (16 bits)

    merged := DcacheByteLane.extractCross(lineA, lineB, off, size)
    dataA  := DcacheByteLane.storeDataA(off, size, data)
    strbA  := DcacheByteLane.storeStrbA(off, size)
    dataB  := DcacheByteLane.storeDataB(off, size, data)
    strbB  := DcacheByteLane.storeStrbB(off, size)
  }

  // Model: byte at line offset i lives in bits [i*8 +: 8].
  def byteOf(line: BigInt, i: Int): Int = ((line >> (8 * i)) & 0xff).toInt

  def line(bytes: Seq[Int]): BigInt =
    bytes.zipWithIndex.foldLeft(BigInt(0)) { case (acc, (b, i)) => acc | (BigInt(b & 0xff) << (8 * i)) }

  test("long load at offset 14 merges 2 bytes from A + 2 from B (big-endian)", VerilatorTest) {
    M68kSim().withVerilator.compile(new Dut).doSim { dut =>
      // line A bytes: index = value; line B bytes: index + 0x80.
      val aBytes = (0 until 16).map(i => i)
      val bBytes = (0 until 16).map(i => i + 0x80)
      dut.lineA #= line(aBytes); dut.lineB #= line(bBytes)
      dut.off #= 14; dut.size #= Size.LONG; dut.data #= 0
      sleep(1)
      // big-endian long: byte at off(14)=MSB, then off15, then B0, B1.
      val exp = (BigInt(aBytes(14)) << 24) | (BigInt(aBytes(15)) << 16) |
                (BigInt(bBytes(0)) << 8)  |  BigInt(bBytes(1))
      assert(dut.merged.toBigInt == exp, s"merged ${dut.merged.toBigInt.toString(16)} exp ${exp.toString(16)}")
    }
  }

  test("word load at offset 15 merges 1 byte from A + 1 from B", VerilatorTest) {
    M68kSim().withVerilator.compile(new Dut).doSim { dut =>
      val aBytes = (0 until 16).map(i => i)
      val bBytes = (0 until 16).map(i => i + 0x80)
      dut.lineA #= line(aBytes); dut.lineB #= line(bBytes)
      dut.off #= 15; dut.size #= Size.WORD; dut.data #= 0
      sleep(1)
      val exp = (BigInt(aBytes(15)) << 8) | BigInt(bBytes(0))
      assert(dut.merged.toBigInt == exp, s"merged ${dut.merged.toBigInt.toString(16)} exp ${exp.toString(16)}")
    }
  }

  test("aligned long at offset 0 -> all bytes in A, strobeB = 0", VerilatorTest) {
    M68kSim().withVerilator.compile(new Dut).doSim { dut =>
      val aBytes = (0 until 16).map(i => i)
      dut.lineA #= line(aBytes); dut.lineB #= 0
      dut.off #= 0; dut.size #= Size.LONG; dut.data #= BigInt(0xAABBCCDDL)
      sleep(1)
      assert(dut.strbB.toBigInt == 0, "aligned: no slot-B strobe")
      assert(dut.strbA.toBigInt == BigInt("000F", 16), s"strobeA low 4 bytes, got ${dut.strbA.toBigInt.toString(16)}")
      // dataA bytes 0..3 = AA BB CC DD (big-endian: byte 0 = MSB)
      val da = dut.dataA.toBigInt
      assert(byteOf(da, 0) == 0xAA && byteOf(da, 1) == 0xBB && byteOf(da, 2) == 0xCC && byteOf(da, 3) == 0xDD,
        s"dataA ${da.toString(16)}")
    }
  }

  test("store split: long at offset 14 -> 2 bytes A, 2 bytes B", VerilatorTest) {
    M68kSim().withVerilator.compile(new Dut).doSim { dut =>
      dut.lineA #= 0; dut.lineB #= 0
      dut.off #= 14; dut.size #= Size.LONG; dut.data #= BigInt(0x11223344L)
      sleep(1)
      // strobeA: bytes 14,15 ; strobeB: bytes 0,1
      assert(dut.strbA.toBigInt == BigInt("C000", 16), s"strbA ${dut.strbA.toBigInt.toString(16)}")
      assert(dut.strbB.toBigInt == BigInt("0003", 16), s"strbB ${dut.strbB.toBigInt.toString(16)}")
      val da = dut.dataA.toBigInt; val db = dut.dataB.toBigInt
      // big-endian: byte at off(14)=0x11 (MSB), off15=0x22, then B0=0x33, B1=0x44
      assert(byteOf(da, 14) == 0x11 && byteOf(da, 15) == 0x22, s"dataA ${da.toString(16)}")
      assert(byteOf(db, 0) == 0x33 && byteOf(db, 1) == 0x44, s"dataB ${db.toString(16)}")
    }
  }

  test("store split: aligned word at offset 4 -> strobeB = 0", VerilatorTest) {
    M68kSim().withVerilator.compile(new Dut).doSim { dut =>
      dut.lineA #= 0; dut.lineB #= 0
      dut.off #= 4; dut.size #= Size.WORD; dut.data #= BigInt(0xBEEFL)
      sleep(1)
      assert(dut.strbB.toBigInt == 0, "aligned word: no slot-B strobe")
      assert(dut.strbA.toBigInt == BigInt("0030", 16), s"strbA bytes 4,5 -> 0x30, got ${dut.strbA.toBigInt.toString(16)}")
      val da = dut.dataA.toBigInt
      assert(byteOf(da, 4) == 0xBE && byteOf(da, 5) == 0xEF, s"dataA ${da.toString(16)}")
    }
  }

  // ───────────────────────────────────────────────────────────────────────────
  // The `DcacheByteLane.extract` LINE-WRAP TRIPWIRE (2026-09-09).
  //
  // `extract` indexes the line with a 4-bit offset, so `off + 1/2/3` WRAP: a WORD at
  // offset 15 returns {byte[15], byte[0]} of the SAME line, and a LONG at 13/14/15
  // returns 1/2/3 bytes of the line's own head. That is silent wrong data, and it has
  // already produced two real defects (the RTE frame-word pops and the FRESTORE header
  // read, both of which reach `dcache.loadCmd` directly and so bypass the LS EU's AGU
  // cross-line splitter). The synthesised behaviour is unchanged; a
  // `GenerationFlags.simulation` assertion now makes the case a LOUD failure instead.
  //
  // These four tests are the tripwire's own fail-before/pass-after evidence: two
  // non-wrapping / exempt cases that must stay silent, and two wrapping cases that must
  // kill the simulation. `M68kSim()` (not bare `SimConfig`) is required -- only its
  // `.includeSimulation` puts `GenerationFlags.simulation` blocks into the netlist at all.
  // ───────────────────────────────────────────────────────────────────────────
  class WrapDut extends Component {
    val line  = in Bits (128 bits)
    val off   = in UInt (4 bits)
    val size  = in(Size())
    val used  = in Bool ()
    val value = out Bits (32 bits)
    value := DcacheByteLane.extract(line, off, size, used)
  }

  private val wrapLine = line((0 until 16).map(i => 0xA0 + i))

  /** Drive one {off, size, dataUsed} case for several clock edges. SpinalHDL emits a
    * `GenerationFlags.simulation` assert as a CLOCKED check (and skips time 0), so a
    * purely combinational `sleep(1)` would never evaluate it. */
  private def driveWrap(off: Int, size: Size.E, used: Boolean)(check: WrapDut => Unit): Unit =
    M68kSim().withVerilator.compile(new WrapDut).doSim { dut =>
      dut.line #= wrapLine; dut.off #= off; dut.size #= size; dut.used #= used
      dut.clockDomain.forkStimulus(10)
      dut.clockDomain.waitSampling(5)
      check(dut)
    }

  test("extract tripwire stays silent on a LONG ending exactly at the line boundary", VerilatorTest) {
    driveWrap(12, Size.LONG, used = true) { dut =>
      val exp = (BigInt(0xAC) << 24) | (BigInt(0xAD) << 16) | (BigInt(0xAE) << 8) | BigInt(0xAF)
      assert(dut.value.toBigInt == exp, s"value ${dut.value.toBigInt.toString(16)} exp ${exp.toString(16)}")
    }
  }

  test("extract tripwire exempts a wrapping access whose data is NOT consumed", VerilatorTest) {
    // Slot A of an LS EU cross-line split pair: presented at the ORIGINAL crossing
    // offset/size purely to obtain `DLoadRsp.line` (`DLoadCmd.lineOnly`), so the
    // tripwire must not fire. Its `.data` is a don't-care by contract -- which is
    // what makes the 2026-09-17 hardware change below safe for this caller.
    driveWrap(15, Size.WORD, used = false) { dut =>
      assert(dut.value.toBigInt == ((BigInt(0xAF) << 8) | BigInt(0x00)),
        "the out-of-line byte must read ZERO, not the line's own head byte")
    }
  }

  // ───────────────────────────────────────────────────────────────────────────
  // 2026-09-17: THE WRAP IS NOW CLOSED IN HARDWARE TOO.
  //
  // The tripwire above is `GenerationFlags.simulation`, so on silicon a crossing
  // access still returned the line's OWN HEAD BYTES -- data that is plausible,
  // aliased to real memory, and therefore silently wrong. That is exactly the
  // shape of the wrong-PC-on-RTE defect: a longword assembled half from the
  // exception frame and half from the top of the same line still looks like an
  // address.
  //
  // extract() now ties the wrap-reachable mux inputs to a constant zero, which is
  // exact (every legal offset/size reads an index the wrap cannot produce) and
  // free (a mux input becomes a literal). These tests are the fail-before /
  // pass-after evidence: each asserts the byte that wraps reads 0x00 and, just as
  // importantly, that the IN-LINE bytes are untouched.
  //
  // `used = false` throughout: the point is the DATA, and with used = true the
  // tripwire kills the run before the value can be read.
  // ───────────────────────────────────────────────────────────────────────────
  // (label, off, size, expected 32-bit value) -- line bytes are 0xA0 + index.
  // The label is spelled out rather than interpolated from `size`: a SpinalHDL
  // enum element has no useful toString outside elaboration, so `s"$size"` gives
  // "null" for every case and ScalaTest then rejects the suite for duplicate
  // test names.
  private def wrapCases = Seq(
    ("WORD@15", 15, Size.WORD, (BigInt(0xAF) << 8)),                          // 1 byte past
    ("LONG@15", 15, Size.LONG, (BigInt(0xAF) << 24)),                         // 3 bytes past
    ("LONG@14", 14, Size.LONG, (BigInt(0xAE) << 24) | (BigInt(0xAF) << 16)),  // 2 bytes past
    ("LONG@13", 13, Size.LONG,
      (BigInt(0xAD) << 24) | (BigInt(0xAE) << 16) | (BigInt(0xAF) << 8))      // 1 byte past
  )

  for ((label, off, size, exp) <- wrapCases) {
    test(s"extract returns ZERO, not the line head, for a wrapping $label", VerilatorTest) {
      driveWrap(off, size, used = false) { dut =>
        assert(dut.value.toBigInt == exp,
          f"$label value=0x${dut.value.toBigInt}%x expected 0x$exp%x -- " +
          "a wrapped lane must read 0x00; reading the line's own head byte is the " +
          "silent-wrong-data defect this closes")
      }
    }
  }

  // ...and every NON-wrapping offset must be bit-identical to the old behaviour.
  // This is the half that proves the fix is exact rather than merely safe.
  test("extract is unchanged for every non-wrapping offset/size", VerilatorTest) {
    M68kSim().withVerilator.compile(new WrapDut).doSim { dut =>
      dut.line #= wrapLine; dut.used #= false
      dut.clockDomain.forkStimulus(10)
      def model(off: Int, n: Int): BigInt =
        (0 until n).foldLeft(BigInt(0))((acc, k) => (acc << 8) | BigInt(0xA0 + off + k))
      for ((label, size, n) <- Seq(("BYTE", Size.BYTE, 1), ("WORD", Size.WORD, 2),
                                   ("LONG", Size.LONG, 4));
           off <- 0 to (16 - n)) {
        dut.off #= off; dut.size #= size
        dut.clockDomain.waitSampling(2)
        assert(dut.value.toBigInt == model(off, n),
          f"off=$off%d size=$label got 0x${dut.value.toBigInt}%x want 0x${model(off, n)}%x")
      }
    }
  }

  test("extract tripwire FIRES on a consumed WORD at line offset 15", VerilatorTest) {
    val err = intercept[Throwable] { driveWrap(15, Size.WORD, used = true)(_ => ()) }
    assert(err.toString.toLowerCase.contains("assert") || err.toString.contains("WRAPS") ||
           err.toString.toLowerCase.contains("finish"),
      s"expected the extract wrap assertion, got: $err")
  }

  test("extract tripwire FIRES on a consumed LONG at line offset 13", VerilatorTest) {
    val err = intercept[Throwable] { driveWrap(13, Size.LONG, used = true)(_ => ()) }
    assert(err.toString.toLowerCase.contains("assert") || err.toString.contains("WRAPS") ||
           err.toString.toLowerCase.contains("finish"),
      s"expected the extract wrap assertion, got: $err")
  }
}
