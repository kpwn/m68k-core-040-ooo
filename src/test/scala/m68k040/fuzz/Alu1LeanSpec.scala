package m68k040.fuzz

import m68k040.VerilatorTest
import org.scalatest.funsuite.AnyFunSuite

/** CPU_ALU1_LEAN (docs/design/10-review.md §5.6): every eu0-only µop family
  * (`AluEuPlugin.alu0Only`) executed through the full core, each one surrounded by
  * independent ready ALU work so that, without the steering, the IQ would routinely
  * hand it to ALU1. Results are self-checked against constants; with the flag ON the
  * lean EU's simulation assertion additionally fails the run on any eu0-only issue to
  * port 1. Meaningful with the flag OFF too (plain functional coverage). */
class Alu1LeanSpec extends AnyFunSuite {
  test("eu0-only ALU families compute correctly with ALU1 busy (CPU_ALU1_LEAN steering)", VerilatorTest) {
    // Independent filler: three chains on d2/d3/a2 that never touch the checked regs.
    val f = "add.l %d2,%d3\n        addq.l #1,%d2\n        eor.l %d3,%d2\n        addq.l #3,%a2"
    def chk(reg: String, v: Long) = f"cmp.l #0x$v%08x,$reg\n        bne.l _fail"
    val body = s"""
        moveq #5,%d0
        moveq #7,%d1
        $f
        move.w #0x10,%ccr
        addx.l %d1,%d0
        $f
        ${chk("%d0", 13)}
        moveq #10,%d0
        moveq #3,%d1
        move.w #0x10,%ccr
        subx.l %d1,%d0
        $f
        ${chk("%d0", 6)}
        moveq #1,%d0
        move.w #0,%ccr
        negx.l %d0
        $f
        ${chk("%d0", 0xFFFFFFFFL)}
        move.l #0x19,%d0
        move.l #0x23,%d1
        move.w #0,%ccr
        abcd %d0,%d1
        $f
        ${chk("%d1", 0x42)}
        move.w #0,%ccr
        sbcd %d0,%d1
        $f
        ${chk("%d1", 0x23)}
        moveq #1,%d0
        move.w #0,%ccr
        nbcd %d0
        $f
        ${chk("%d0", 0x99)}
        move.l #0x0304,%d0
        moveq #0,%d1
        $f
        pack %d0,%d1,#0
        $f
        ${chk("%d1", 0x34)}
        move.l #0x34,%d0
        moveq #0,%d1
        $f
        unpk %d0,%d1,#0
        $f
        ${chk("%d1", 0x0304)}
        move.l #0x11112222,(%a0)
        move.l #0x11112222,%d0
        move.l #0x33334444,%d1
        $f
        cas.l %d0,%d1,(%a0)
        bne.l _fail
        $f
        ${chk("(%a0)", 0x33334444L)}
        move.l #0xF0F0F0F0,%d4
        moveq #4,%d5
        moveq #8,%d6
        $f
        bfextu %d4{%d5:%d6},%d7
        $f
        ${chk("%d7", 0x0F)}
        move.w #0x15,%ccr
        move.w %ccr,%d0
        and.l #0xFFFF,%d0
        ${chk("%d0", 0x15)}
        move.w #0x1F,%ccr
        andi.b #0x0A,%ccr
        move.w %ccr,%d0
        and.l #0xFFFF,%d0
        ${chk("%d0", 0x0A)}
        $f
        move.w #0x0A,%ccr
        ori.b #0x11,%ccr
        move.w %ccr,%d0
        and.l #0xFFFF,%d0
        ${chk("%d0", 0x1B)}
        move.w %sr,%d0
        ori.w #0x0700,%sr
        move.w %sr,%d1
        and.l #0x0700,%d1
        ${chk("%d1", 0x0700)}
        move.w %d0,%sr
    """
    val src = s"""
      .text
      .org 0
      lea 0x10000,%a7
      move.l #_fail,0x10
      move.l #_fail,0x08
      move.l #_fail,0x2c
      lea 0x20000,%a0
      moveq #0,%d2
      moveq #1,%d3
      lea 0,%a2
      move.l #40,%d6
      move.l %d6,0x20100
    _loop:
      $body
      subq.l #1,0x20100
      bne.l _loop
      move.l #0xc0ffee00,0xffff0000
    _done: bra _done
    _fail: move.l #0xdeadbeef,0xffff0000
      bra _fail
    """
    assert(PortedTestRunner.run("alu1_lean_rare_ops", src, 3000000L) == PortedPass)
  }
}
