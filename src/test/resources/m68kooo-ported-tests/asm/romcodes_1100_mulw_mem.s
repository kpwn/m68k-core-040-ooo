| romcodes_1100_mulw_mem.s - MULU/MULS word memory-source forms
|
| Covers the simple source EAs first:
|   (An), (An)+, -(An), (d16,An), (xxx).W, (xxx).L, (d16,PC)
|
| Each case checks result, CCR flags, and address-register writeback
| where the addressing mode updates a source An.

    .text
    .org 0

_start:
    | MULU.W (A0),D0
    lea     _mulu_indirect_word, %a0
    move.l  #0x12340004, %d0
    mulu.w  (%a0), %d0
    bmi     _fail1
    bvs     _fail1
    bcs     _fail1
    beq     _fail1
    cmp.l   #0x00000040, %d0
    bne     _fail1
    lea     _mulu_indirect_word, %a1
    cmpa.l  %a1, %a0
    bne     _fail1

    | MULS.W (A1)+,D1
    lea     _muls_postinc_word, %a1
    move.l  #0x55AAFFF6, %d1      | -10 in the low word
    muls.w  (%a1)+, %d1
    bmi     _fail2
    bvs     _fail2
    bcs     _fail2
    beq     _fail2
    cmp.l   #0x0000001E, %d1      | 30
    bne     _fail2
    lea     _muls_postinc_word + 2, %a2
    cmpa.l  %a2, %a1
    bne     _fail2

    | MULU.W -(A2),D2
    lea     _mulu_predec_word + 2, %a2
    move.l  #0x0000FFFF, %d2
    mulu.w  -(%a2), %d2
    bpl     _fail3
    bvs     _fail3
    bcs     _fail3
    beq     _fail3
    cmp.l   #0xFFFE0001, %d2
    bne     _fail3
    lea     _mulu_predec_word, %a3
    cmpa.l  %a3, %a2
    bne     _fail3

    | MULS.W 4(A3),D3
    lea     _muls_disp_base, %a3
    move.l  #0x00000004, %d3
    muls.w  4(%a3), %d3
    bpl     _fail4
    bvs     _fail4
    bcs     _fail4
    beq     _fail4
    cmp.l   #0xFFFFFFF4, %d3      | -12
    bne     _fail4
    lea     _muls_disp_base, %a4
    cmpa.l  %a4, %a3
    bne     _fail4

    | MULU.W (xxx).W, D4
    move.w  #0x0010, 0x1234
    move.l  #0x00000010, %d4
    .word   0xc8f8
    .word   0x1234
    bmi     _fail5
    bvs     _fail5
    bcs     _fail5
    beq     _fail5
    cmp.l   #0x00000100, %d4
    bne     _fail5

    | MULS.W (xxx).L, D5
    move.w  #0xFFFD, 0x00105000
    move.l  #0x00000005, %d5
    .word   0xcbf9
    .long   0x00105000
    bpl     _fail6
    bvs     _fail6
    bcs     _fail6
    beq     _fail6
    cmp.l   #0xFFFFFFF1, %d5      | -15
    bne     _fail6

    | MULS.W (d16,PC),D6
    move.l  #0x00000004, %d6
    muls.w  _muls_pc_word(%pc), %d6
    bpl     _fail7
    bvs     _fail7
    bcs     _fail7
    beq     _fail7
    cmp.l   #0xFFFFFFF4, %d6      | -12
    bne     _fail7

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a0)
_halt:
    bra     _halt

_fail1:
    moveq   #1, %d7
    bra     _fail
_fail2:
    moveq   #2, %d7
    bra     _fail
_fail3:
    moveq   #3, %d7
    bra     _fail
_fail4:
    moveq   #4, %d7
    bra     _fail
_fail5:
    moveq   #5, %d7
    bra     _fail
_fail6:
    moveq   #6, %d7
    bra     _fail
_fail7:
    moveq   #7, %d7
    bra     _fail

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
    bra     _halt

_mulu_indirect_word:
    .word   0x0010

_muls_postinc_word:
    .word   0xFFFD

_mulu_predec_word:
    .word   0xFFFF

_muls_disp_base:
    .word   0x0000
    .word   0x0000
    .word   0xFFFD

_muls_pc_word:
    .word   0xFFFD
