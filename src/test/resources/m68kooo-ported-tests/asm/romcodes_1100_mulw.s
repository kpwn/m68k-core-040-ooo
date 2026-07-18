| romcodes_1100_mulw.s - MULU/MULS word forms
|
| Covers register-direct and immediate word sources.  The high halves of
| the source registers are filled with garbage to prove decode narrows to
| the low word before entering the shared multiply lane.

    .text
    .org 0

_start:
    | MULU.W D1,D0 - both operands use only the low word.
    move.l  #0xAABB0010, %d0
    move.l  #0xCCDD0004, %d1
    .word   0xc0c1                  | mulu.w %d1,%d0
    move.l  #0x00000040, %d2
    cmp.l   %d2, %d0
    bne     _fail

    | MULS.W D1,D0 - signed low words with garbage high halves.
    move.l  #0x1122FFF6, %d0        | -10 in low word
    move.l  #0x3344FFFD, %d1        | -3 in low word
    .word   0xc1c1                  | muls.w %d1,%d0
    move.l  #0x0000001E, %d2        | 30
    cmp.l   %d2, %d0
    bne     _fail

    | MULU.W #imm16,D0 - immediate source uses only the low word.
    move.l  #0x55AA0010, %d0
    .word   0xc0fc, 0x0010          | mulu.w #0x0010,%d0
    move.l  #0x00000100, %d2
    cmp.l   %d2, %d0
    bne     _fail

    | MULS.W #imm16,D0 - signed immediate source and N flag from bit 31.
    move.l  #0x77880004, %d0
    .word   0xc1fc, 0xfffd          | muls.w #-3,%d0
    bpl     _fail
    move.l  #0xFFFFFFF4, %d2        | -12
    cmp.l   %d2, %d0
    bne     _fail

    | MULU.W can also produce a negative 32-bit result flag-wise.
    move.l  #0x0000ffff, %d0
    move.l  #0x1234ffff, %d1
    .word   0xc0c1                  | mulu.w %d1,%d0
    bpl     _fail
    move.l  #0xfffe0001, %d2
    cmp.l   %d2, %d0
    bne     _fail

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d3
    move.l  %d3, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d3
    move.l  %d3, (%a0)
_halt_fail:
    bra     _halt_fail
