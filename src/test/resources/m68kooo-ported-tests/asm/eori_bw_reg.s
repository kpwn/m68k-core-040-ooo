| eori_bw_reg.s -- EORI.B/W immediate forms on data registers
|
| Covers the low-risk decode hole where EORI.L was implemented but byte
| and word register destinations still fell through to illegal.  The
| assertions check that byte/word writes preserve upper Dn bits and that
| NZVC are based on the operand size, not the full 32-bit register.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    lea     0x00010000, %a7

    | EORI.B preserves upper 24 bits, sets N from bit 7, and clears C.
    move.l  #0x1234560f, %d0
    moveq   #0, %d5
    subi.b  #1, %d5              | set C before EORI
    eori.b  #0xff, %d0
    bpl     _fail1               | low byte 0xf0 -> N=1
    beq     _fail1
    bcs     _fail1               | EORI clears C
    cmp.l   #0x123456f0, %d0
    bne     _fail1

    | EORI.W zero result sets Z even though the preserved upper word is nonzero.
    move.l  #0xabcdffff, %d1
    moveq   #127, %d5
    addi.b  #1, %d5              | set V before EORI
    eori.w  #0xffff, %d1
    bne     _fail2               | low word result is zero
    bmi     _fail2
    bvs     _fail2               | EORI clears V
    cmp.l   #0xabcd0000, %d1
    bne     _fail2

    | Word N comes from bit 15, not bit 31.
    move.l  #0x13578000, %d2
    eori.w  #0x0001, %d2
    bpl     _fail3
    beq     _fail3
    cmp.l   #0x13578001, %d2
    bne     _fail3

    | Byte Z comes from the low byte, not the full register.
    move.l  #0xfedcba5a, %d3
    eori.b  #0x5a, %d3
    bne     _fail4
    bmi     _fail4
    cmp.l   #0xfedcba00, %d3
    bne     _fail4

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_fail1:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0001, %d0
    move.l  %d0, (%a0)
_halt_fail1:
    bra     _halt_fail1

_fail2:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0002, %d0
    move.l  %d0, (%a0)
_halt_fail2:
    bra     _halt_fail2

_fail3:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0003, %d0
    move.l  %d0, (%a0)
_halt_fail3:
    bra     _halt_fail3

_fail4:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0004, %d0
    move.l  %d0, (%a0)
_halt_fail4:
    bra     _halt_fail4
