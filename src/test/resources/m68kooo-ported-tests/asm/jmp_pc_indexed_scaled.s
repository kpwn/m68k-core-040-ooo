| jmp_pc_indexed_scaled.s -- JMP (d8,PC,Xn.W*scale) brief form
|
| Covers the ROM stop at 0x408007c2:
|   4efb 7200    jmp (0,PC,D7.W*2)
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    move.w  #((_target - (_jmp_scaled + 2)) / 2), %d7
_jmp_scaled:
    .word   0x4efb, 0x7200        | jmp (0,pc,d7.w*2)
    bra     _fail                 | must be skipped

_target:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
_halt_fail:
    bra     _halt_fail
