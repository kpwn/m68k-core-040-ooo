package m68k040.fuzz

import m68k040.VerilatorTest
import org.scalatest.funsuite.AnyFunSuite

/** MULx.L / DIVx.L with an auto-update source EA, (An)+ and -(An).
  *
  * A real 68040 accepts every DATA addressing mode for the long multiply/divide, in all
  * forms (32-bit, 64-bit Dh:Dl / Dr:Dq, DIVxL remainder). Silicon hit
  * `MULS.L (A1)+,D4:D5` (4C19 5C04, Acrobat Reader on 7.5.3) raising vector 4.
  *
  * The program is self-checking (PASS = 0xC0FFEE00 at the sentinel; a failing check
  * writes 0xBAD0_00nn, nn = the case number). Expected values are computed here in Scala
  * and interpolated, so every check is an exact value. Covered:
  *   - the exact silicon opcode, plus MULU/MULS .L 32 and 64 forms, (An)+ and -(An);
  *   - Dl numerically equal to the address register's number (D1 with A1);
  *   - A7 as the address register, both directions;
  *   - DIVS.L/DIVU.L 32-bit quotient-only, DIVxL.L 32-bit with remainder, 64-bit Dr:Dq;
  *   - divide-by-zero through (An)+ and -(An): vector 5 with An ALREADY updated (the EA
  *     is evaluated before the zero check -- MC68040 and Musashi agree), stacked PC =
  *     next instruction, Dq/Dr untouched;
  *   - an access fault (bus error) on the operand load through (An)+ and -(An): the
  *     handler sees An NOT updated; it repoints An at good memory and RTEs; the
  *     restarted instruction completes with exactly ONE An update.
  */
class MulDivAutoEaSpec extends AnyFunSuite {
  private def u32(x: BigInt): Long = (x & BigInt(0xffffffffL)).toLong
  private def s32(x: Long): BigInt = BigInt(x.toInt)
  private def h(x: Long): String = f"0x${x & 0xffffffffL}%08X"

  private def mulL(signed: Boolean, a: Long, b: Long): (Long, Long) = {
    val p = if (signed) s32(a) * s32(b) else BigInt(a & 0xffffffffL) * BigInt(b & 0xffffffffL)
    (u32(p >> 32), u32(p))
  }
  /** (quotient, remainder) of a 32- or 64-bit dividend by a 32-bit divisor (no overflow). */
  private def divL(signed: Boolean, hi: Option[Long], lo: Long, d: Long): (Long, Long) = {
    val dividend: BigInt = hi match {
      case Some(hv) =>
        val raw = (BigInt(hv & 0xffffffffL) << 32) | BigInt(lo & 0xffffffffL)
        if (signed && raw.testBit(63)) raw - (BigInt(1) << 64) else raw
      case None => if (signed) s32(lo) else BigInt(lo & 0xffffffffL)
    }
    val divisor = if (signed) s32(d) else BigInt(d & 0xffffffffL)
    // C semantics: truncate toward zero, remainder takes the dividend's sign.
    val q = dividend / divisor; val r = dividend - q * divisor
    (u32(q), u32(r))
  }

  test("MULx.L / DIVx.L (An)+ and -(An): values, An update, A7, DIV0 and access-fault restart", VerilatorTest) {
    val D = 0x00030000L          // data area
    val S = 0x00031000L          // alternate A7 area
    val G = 0x00032000L          // the "good" memory the bus-error handler repoints An at
    val Sv = 0x00033000L         // handler save slots
    val BAD = 0xAAAA0000L        // unmapped -> AXI DECERR -> access fault

    // Case 1: the exact silicon opcode, MULS.L (A1)+,D4:D5.
    val m1 = 0xFFFFFF85L; val l1 = 0x12345678L
    val (e1h, e1l) = mulL(signed = true, m1, l1)
    // Case 2: MULU.L -(A2),D3 (32).
    val m2 = 0x00010001L; val l2 = 0x0000FFFFL
    val (_, e2) = mulL(signed = false, m2, l2)
    // Case 3: MULS.L (A1)+,D1 (32; Dl number == An number).
    val m3 = 0xFFFFFFFDL; val l3 = 0x00000007L
    val (_, e3) = mulL(signed = true, m3, l3)
    // Case 4: MULU.L -(A3),D0:D6 (64).
    val m4 = 0xFFFFFFFFL; val l4 = 0xFFFFFFFEL
    val (e4h, e4l) = mulL(signed = false, m4, l4)
    // Case 5: A7 -- MULU.L (A7)+,D0 then MULS.L -(A7),D1:D2.
    val m5a = 0x00000003L; val l5a = 0x40000001L
    val (_, e5a) = mulL(signed = false, m5a, l5a)
    val l5b = 0x00000002L                             // -(A7) re-reads the SAME long
    val (e5bh, e5bl) = mulL(signed = true, m5a, l5b)
    // Case 6: DIVS.L (A0)+,D1 (32, quotient only).
    val d6 = 0xFFFFFFF9L; val n6 = 1000003L
    val (q6, _) = divL(signed = true, None, n6, d6)
    // Case 7: DIVUL.L (A0)+,D2:D3 (32 with remainder; Dr=D2, Dq=D3).
    val d7 = 10L; val n7 = 0xFFFFFFFFL
    val (q7, r7) = divL(signed = false, None, n7, d7)
    // Case 8: DIVS.L -(A0),D4:D5 (64; Dr=D4 high, Dq=D5 low) -- re-reads case 7's long.
    val n8h = 0xFFFFFFFFL; val n8l = 0xFFFFF000L       // -4096
    val (q8, r8) = divL(signed = true, Some(n8h), n8l, d7)
    // Case 9: A7 divides -- DIVU.L (A7)+,D6 then DIVSL.L -(A7),D2:D3.
    val d9 = 3L; val n9a = 100L; val n9b = 0xFFFFFF9CL  // -100
    val (q9a, _) = divL(signed = false, None, n9a, d9)
    val (q9b, r9b) = divL(signed = true, None, n9b, d9)
    // Case 12: bus-error restart, MULS.L (A1)+,D4:D5 then DIVU.L -(A2),D0.
    val gv = 0x00000011L; val l12 = 0x10000000L
    val (e12h, e12l) = mulL(signed = true, gv, l12)
    val n13 = 0x00000110L
    val (q13, _) = divL(signed = false, None, n13, gv)

    def fail(n: Int): String = f"bne _f$n%02d"
    val src =
      s"""    .text
         |_start:
         |    lea     0x00010000, %a7
         |    move.l  #_div0h, 0x14
         |    move.l  #_berrh, 0x08
         |    move.l  #_illh, 0x10
         |    moveq   #0, %d7
         || ---- case 1: MULS.L (A1)+,D4:D5, the exact silicon encoding
         |    lea     ${h(D)}, %a1
         |    move.l  #${h(m1)}, (%a1)
         |    move.l  #${h(l1)}, %d5
         |    moveq   #-1, %d4
         |    .short  0x4C19, 0x5C04
         |    cmp.l   #${h(e1h)}, %d4
         |    ${fail(1)}
         |    cmp.l   #${h(e1l)}, %d5
         |    ${fail(1)}
         |    cmpa.l  #${h(D + 4)}, %a1
         |    ${fail(1)}
         || ---- case 2: MULU.L -(A2),D3
         |    lea     ${h(D + 0x10)}, %a2
         |    move.l  #${h(m2)}, ${h(D + 0x0c)}
         |    move.l  #${h(l2)}, %d3
         |    mulu.l  -(%a2), %d3
         |    cmp.l   #${h(e2)}, %d3
         |    ${fail(2)}
         |    cmpa.l  #${h(D + 0x0c)}, %a2
         |    ${fail(2)}
         || ---- case 3: MULS.L (A1)+,D1 (D1 and A1 share the register number)
         |    lea     ${h(D + 0x20)}, %a1
         |    move.l  #${h(m3)}, (%a1)
         |    move.l  #${h(l3)}, %d1
         |    muls.l  (%a1)+, %d1
         |    cmp.l   #${h(e3)}, %d1
         |    ${fail(3)}
         |    cmpa.l  #${h(D + 0x24)}, %a1
         |    ${fail(3)}
         || ---- case 4: MULU.L -(A3),D0:D6 (64)
         |    lea     ${h(D + 0x34)}, %a3
         |    move.l  #${h(m4)}, ${h(D + 0x30)}
         |    move.l  #${h(l4)}, %d6
         |    mulu.l  -(%a3), %d0:%d6
         |    cmp.l   #${h(e4h)}, %d0
         |    ${fail(4)}
         |    cmp.l   #${h(e4l)}, %d6
         |    ${fail(4)}
         |    cmpa.l  #${h(D + 0x30)}, %a3
         |    ${fail(4)}
         || ---- case 5: A7 as the address register
         |    move.l  %a7, %a6
         |    lea     ${h(S)}, %a7
         |    move.l  #${h(m5a)}, (%a7)
         |    move.l  #${h(l5a)}, %d0
         |    mulu.l  (%a7)+, %d0
         |    move.l  %a7, %d3
         |    move.l  #${h(l5b)}, %d2
         |    muls.l  -(%a7), %d1:%d2
         |    move.l  %a7, %d4
         |    move.l  %a6, %a7
         |    cmp.l   #${h(e5a)}, %d0
         |    ${fail(5)}
         |    cmp.l   #${h(S + 4)}, %d3
         |    ${fail(5)}
         |    cmp.l   #${h(e5bh)}, %d1
         |    ${fail(5)}
         |    cmp.l   #${h(e5bl)}, %d2
         |    ${fail(5)}
         |    cmp.l   #${h(S)}, %d4
         |    ${fail(5)}
         || ---- case 6: DIVS.L (A0)+,D1 (quotient only)
         |    lea     ${h(D + 0x40)}, %a0
         |    move.l  #${h(d6)}, (%a0)
         |    move.l  #${h(n6)}, %d1
         |    divs.l  (%a0)+, %d1
         |    cmp.l   #${h(q6)}, %d1
         |    ${fail(6)}
         |    cmpa.l  #${h(D + 0x44)}, %a0
         |    ${fail(6)}
         || ---- case 7: DIVUL.L (A0)+,D2:D3 (32-bit dividend, remainder to D2)
         |    move.l  #${h(d7)}, (%a0)
         |    move.l  #${h(n7)}, %d3
         |    moveq   #-1, %d2
         |    divul.l (%a0)+, %d2:%d3
         |    cmp.l   #${h(q7)}, %d3
         |    ${fail(7)}
         |    cmp.l   #${h(r7)}, %d2
         |    ${fail(7)}
         |    cmpa.l  #${h(D + 0x48)}, %a0
         |    ${fail(7)}
         || ---- case 8: DIVS.L -(A0),D4:D5 (64-bit dividend D4:D5)
         |    move.l  #${h(n8h)}, %d4
         |    move.l  #${h(n8l)}, %d5
         |    divs.l  -(%a0), %d4:%d5
         |    cmp.l   #${h(q8)}, %d5
         |    ${fail(8)}
         |    cmp.l   #${h(r8)}, %d4
         |    ${fail(8)}
         |    cmpa.l  #${h(D + 0x44)}, %a0
         |    ${fail(8)}
         || ---- case 9: A7 divides
         |    move.l  %a7, %a6
         |    lea     ${h(S + 0x10)}, %a7
         |    move.l  #${h(d9)}, (%a7)
         |    move.l  #${h(n9a)}, %d6
         |    divu.l  (%a7)+, %d6
         |    move.l  %a7, %d0
         |    move.l  #${h(n9b)}, %d3
         |    moveq   #0, %d2
         |    divsl.l -(%a7), %d2:%d3
         |    move.l  %a7, %d1
         |    move.l  %a6, %a7
         |    cmp.l   #${h(q9a)}, %d6
         |    ${fail(9)}
         |    cmp.l   #${h(S + 0x14)}, %d0
         |    ${fail(9)}
         |    cmp.l   #${h(q9b)}, %d3
         |    ${fail(9)}
         |    cmp.l   #${h(r9b)}, %d2
         |    ${fail(9)}
         |    cmp.l   #${h(S + 0x10)}, %d1
         |    ${fail(9)}
         || ---- case 10: DIV0 through (A0)+ -- An is updated before the trap
         |    lea     ${h(D + 0x50)}, %a0
         |    clr.l   (%a0)
         |    moveq   #5, %d1
         |    divu.l  (%a0)+, %d1
         |_ret10:
         |    cmp.l   #1, %d7
         |    ${fail(10)}
         |    cmp.l   #5, %d1
         |    ${fail(10)}
         |    cmpa.l  #${h(D + 0x54)}, %a0
         |    ${fail(10)}
         |    cmp.l   #${h(D + 0x54)}, ${h(Sv)}
         |    ${fail(10)}
         |    cmp.l   #_ret10, ${h(Sv + 4)}
         |    ${fail(10)}
         || ---- case 11: DIV0 through -(A0), 64-bit form: Dr:Dq untouched, An decremented
         |    move.l  #0x12345678, %d2
         |    move.l  #0x9abcdef0, %d3
         |    divs.l  -(%a0), %d2:%d3
         |_ret11:
         |    cmp.l   #2, %d7
         |    ${fail(11)}
         |    cmp.l   #0x12345678, %d2
         |    ${fail(11)}
         |    cmp.l   #0x9abcdef0, %d3
         |    ${fail(11)}
         |    cmpa.l  #${h(D + 0x50)}, %a0
         |    ${fail(11)}
         |    cmp.l   #${h(D + 0x50)}, ${h(Sv)}
         |    ${fail(11)}
         |    cmp.l   #_ret11, ${h(Sv + 4)}
         |    ${fail(11)}
         || ---- case 12: access fault on the (A1)+ operand load -> restart
         |    move.l  #${h(gv)}, ${h(G)}
         |    lea     ${h(G)}, %a6
         |    lea     ${h(BAD)}, %a1
         |    move.l  #${h(l12)}, %d5
         |    moveq   #0, %d4
         |    muls.l  (%a1)+, %d4:%d5
         |    cmp.l   #3, %d7
         |    ${fail(12)}
         |    cmp.l   #${h(BAD)}, ${h(Sv + 8)}
         |    ${fail(12)}
         |    cmpa.l  #${h(G + 4)}, %a1
         |    ${fail(12)}
         |    cmp.l   #${h(e12h)}, %d4
         |    ${fail(12)}
         |    cmp.l   #${h(e12l)}, %d5
         |    ${fail(12)}
         || ---- case 13: access fault on the -(A1) operand load -> restart
         |    lea     ${h(G + 4)}, %a6
         |    lea     ${h(BAD + 4)}, %a1
         |    move.l  #${h(n13)}, %d0
         |    divu.l  -(%a1), %d0
         |    cmp.l   #4, %d7
         |    ${fail(13)}
         |    cmp.l   #${h(BAD + 4)}, ${h(Sv + 8)}
         |    ${fail(13)}
         |    cmpa.l  #${h(G)}, %a1
         |    ${fail(13)}
         |    cmp.l   #${h(q13)}, %d0
         |    ${fail(13)}
         || ---- PASS
         |    lea     0xFFFF0000, %a4
         |    move.l  #0xC0FFEE00, %d4
         |    move.l  %d4, (%a4)
         |_halt:
         |    bra     _halt
         |${(1 to 13).map(n => f"_f$n%02d:\n    move.l  #0x${0xBAD00000L + n}%08X, %%d4\n    bra     _fail\n").mkString}
         |_fail:
         |    lea     0xFFFF0000, %a4
         |    move.l  %d4, (%a4)
         |_hf:
         |    bra     _hf
         || DIV0 handler: record An and the stacked PC, count, return past the divide.
         |_div0h:
         |    move.l  %a0, ${h(Sv)}
         |    move.l  2(%sp), ${h(Sv + 4)}
         |    addq.l  #1, %d7
         |    rte
         |_illh:
         |    move.l  #0xBAD000FF, %d4
         |    bra     _fail
         || Access-fault handler: record A1 at fault time, repoint it at good memory, restart.
         |_berrh:
         |    move.l  %a1, ${h(Sv + 8)}
         |    move.l  %a6, %a1
         |    addq.l  #1, %d7
         |    rte
         |""".stripMargin.replace("%%", "%")
    val outcome = PortedTestRunner.run("muldiv_auto_ea", src, 400000L)
    assert(outcome == PortedPass, s"outcome $outcome")
  }
}
