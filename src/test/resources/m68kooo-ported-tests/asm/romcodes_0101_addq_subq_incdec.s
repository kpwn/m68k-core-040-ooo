| romcodes_0101_addq_subq_incdec.s -- ADDQ/SUBQ RMW inc/dec EAs
|
| Covers the group-5 quick memory-destination slice:
|   ADDQ/SUBQ.{B,W,L} #imm,(An)+
|   ADDQ/SUBQ.{B,W,L} #imm,-(An)
|
| Checks postincrement vs predecrement order, A7 byte stride, neighbour-byte
| preservation, and that the final CCR seen by branches is the quick ALU CCR.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | ADDQ.L (An)+: store through old A0, then advance by four.
    lea     0x00115000, %a0
    move.l  #0x00000005, (%a0)
    addq.l  #3, (%a0)+
    bmi     _fail1
    beq     _fail1
    bvs     _fail1
    bcs     _fail1
    cmpa.l  #0x00115004, %a0
    bne     _fail1
    lea     0x00115000, %a6
    move.l  (%a6), %d0
    cmp.l   #0x00000008, %d0
    bne     _fail1

    | SUBQ.W (An)+: 0 - 1 => 0xffff, borrow and negative, A1 += 2.
    lea     0x00115020, %a1
    move.l  #0x0000abcd, (%a1)
    subq.w  #1, (%a1)+
    bcc     _fail2
    bpl     _fail2
    beq     _fail2
    bvs     _fail2
    cmpa.l  #0x00115022, %a1
    bne     _fail2
    lea     0x00115020, %a6
    move.l  (%a6), %d1
    cmp.l   #0xffffabcd, %d1
    bne     _fail2

    | ADDQ.B (A7)+: 0xf8 + 8 => 0x00, byte stack postinc advances by two.
    lea     0x00115040, %a7
    move.l  #0xf8112233, (%a7)
    addq.b  #8, (%a7)+
    bne     _fail3
    bcc     _fail3
    bmi     _fail3
    bvs     _fail3
    cmpa.l  #0x00115042, %a7
    bne     _fail3
    lea     0x00115040, %a6
    move.l  (%a6), %d2
    cmp.l   #0x00112233, %d2
    bne     _fail3

    | SUBQ.L -(An): predecrement by four before load/store.
    lea     0x00115064, %a2
    move.l  #0x00000003, -4(%a2)
    subq.l  #2, -(%a2)
    bmi     _fail4
    beq     _fail4
    bvs     _fail4
    bcs     _fail4
    cmpa.l  #0x00115060, %a2
    bne     _fail4
    move.l  (%a2), %d3
    cmp.l   #0x00000001, %d3
    bne     _fail4

    | SUBQ.B -(A7): byte stack predec subtracts two before the byte store.
    lea     0x00115082, %a7
    lea     0x00115080, %a6
    move.l  #0x01020304, (%a6)
    subq.b  #1, -(%a7)
    bne     _fail5
    bcs     _fail5
    bmi     _fail5
    bvs     _fail5
    cmpa.l  #0x00115080, %a7
    bne     _fail5
    move.l  (%a6), %d4
    cmp.l   #0x00020304, %d4
    bne     _fail5

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_fail1:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0101, %d0
    move.l  %d0, (%a0)
_halt_fail1:
    bra     _halt_fail1

_fail2:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0102, %d0
    move.l  %d0, (%a0)
_halt_fail2:
    bra     _halt_fail2

_fail3:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0103, %d0
    move.l  %d0, (%a0)
_halt_fail3:
    bra     _halt_fail3

_fail4:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0104, %d0
    move.l  %d0, (%a0)
_halt_fail4:
    bra     _halt_fail4

_fail5:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0105, %d0
    move.l  %d0, (%a0)
_halt_fail5:
    bra     _halt_fail5
