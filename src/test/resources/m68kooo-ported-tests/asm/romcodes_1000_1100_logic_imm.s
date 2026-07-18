| romcodes_1000_1100_logic_imm.s -- OR/AND immediate forms in groups 1000/1100
|
| Exercises the ROM-visible immediate encodings that widen the existing
| OR/AND decode paths without touching the memory-destination forms.

    .text
    .org 0

_start:
    | OR.B #imm8,D0 -- upper 24 bits preserved.
    move.l  #0xAABBCC00, %d0
    .word   0x803c, 0x0012          | or.b #0x12, %d0
    move.l  #0xAABBCC12, %d1
    cmp.l   %d1, %d0
    bne     _fail

    | AND.W #imm16,D0 -- upper 16 bits preserved.
    move.l  #0x11223344, %d0
    .word   0xc07c, 0x0f0f          | and.w #0x0f0f, %d0
    move.l  #0x11220304, %d1
    cmp.l   %d1, %d0
    bne     _fail

    | AND.L #imm32,D0 -- full-width literal mask.
    move.l  #0x12345678, %d0
    .word   0xc0bc, 0x00ff, 0x00ff  | and.l #0x00ff00ff, %d0
    move.l  #0x00340078, %d1
    cmp.l   %d1, %d0
    bne     _fail

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d2
    move.l  %d2, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d2
    move.l  %d2, (%a0)
_halt_fail:
    bra     _halt_fail
