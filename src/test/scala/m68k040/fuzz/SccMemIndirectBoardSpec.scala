package m68k040.fuzz

import m68k040.VerilatorTest
import org.scalatest.funsuite.AnyFunSuite

/** Board vector-4 at 001df38c: 56f6 8161 001c, SNE ([28,A6]).
  * Execute real instructions through the core, not an assembler-only decode test.
  */
class SccMemIndirectBoardSpec extends AnyFunSuite {
  private def condition(cc: Int, sr: Int): Boolean = {
    val n = (sr & 8) != 0; val z = (sr & 4) != 0
    val v = (sr & 2) != 0; val c = (sr & 1) != 0
    Vector(true, false, !c && !z, c || z, !c, c, !z, z,
      !v, v, !n, n, n == v, n != v, !z && n == v, z || n != v)(cc)
  }

  test("board Scc memory-indirect: every condition and CCR, suppressed/pre/post index", VerilatorTest) {
    // Index D1.W=4. Both indexed cases have a word outer displacement of 2.
    // Poison bytes surrounding the destination catch widened stores.
    val shapes = Seq(("0x8161,0x001c", 0x2001c, 0x21000),
      ("0x1122,0x001c,0x0002", 0x20020, 0x21002),
      ("0x1126,0x001c,0x0002", 0x2001c, 0x21006))
    val body = (for ((ext, pointer, target) <- shapes; cc <- 0 until 16; sr <- 0 until 32) yield {
      val expected = if (condition(cc, sr)) 255 else 0
      f"""
        move.l #0x21000,0x$pointer%08x
        move.l #0x55aabbcc,0x$target%08x
        move.w #$sr,%%ccr
        .word 0x${0x50f6 | (cc << 8)}%04x,$ext
        move.w %%sr,%%d4
        and.l #31,%%d4
        cmp.l #$sr,%%d4
        bne.l _fail
        cmp.l #0x${(expected.toLong << 24) | 0xaabbcc}%08x,0x$target%08x
        bne.l _fail
        cmp.l #0x21000,0x$pointer%08x
        bne.l _fail
        cmp.l #0x11223344,%%d6
        bne.l _fail
      """
    }).mkString("\n")
    val src = s"""
      .text
      .org 0
      lea 0x10000,%a7
      move.l #_fail,0x10
      move.l #_fail,0x08
      lea 0x20000,%a6
      move.l #0x11223344,%d6
      moveq #4,%d1
      $body
      move.l #0xc0ffee00,0xffff0000
    _done: bra _done
    _fail: move.l #0xdeadbeef,0xffff0000
      bra _fail
    """
    assert(PortedTestRunner.run("scc_memind_board", src, 2000000L) == PortedPass)
  }

  test("board Scc pointer-read and destination-write bus faults", VerilatorTest) {
    val src = """
      .text
      .org 0
      lea 0x10000,%a7
      move.l #_fail,0x10
      move.l #_read_fault,0x08
      lea 0xaaa9ffe4,%a6
      move.w #0x11,%ccr
    _read_op:
      .word 0x56f6,0x8161,0x001c
      bra _fail
    _read_fault:
      cmp.l #_read_op,2(%a7)
      bne _fail
      move.w (%a7),%d0
      and.l #31,%d0
      cmp.l #0x11,%d0
      bne _fail
      lea 60(%a7),%a7
      move.l #_write_fault,0x08
      lea 0x20000,%a6
      move.l #0xaaaa0000,0x2001c
      move.w #0x14,%ccr
    _write_op:
      .word 0x56f6,0x8161,0x001c
      bra _fail
    _write_fault:
      cmp.l #_write_op,2(%a7)
      bne _fail
      move.w (%a7),%d0
      and.l #31,%d0
      cmp.l #0x14,%d0
      bne _fail
      cmp.l #0xaaaa0000,0x2001c
      bne _fail
      move.l #0xc0ffee00,0xffff0000
    _done: bra _done
    _fail: move.l #0xdeadbeef,0xffff0000
      bra _fail
    """
    assert(PortedTestRunner.run("scc_memind_faults", src, 200000L) == PortedPass)
  }
}
