| move_imm_sr_len.s — regression for MOVE.W #imm,SR length handling.
|
| The ROM frontier hit 46FC 2700 at 0x408026a0.  If decode/predecode
| consume only the opword, the immediate word 0x2700 is fetched as a
| standalone MOVE.L opcode and can corrupt memory through A3.
|
| PASS: 0xC0FFEE00 sentinel.
| FAIL: 0xDEADBEEF sentinel.

    .text
    .org 0

_start:
    lea     0x00010000, %a7
    lea     0x00001004, %a3
    move.l  #0x11111111, %d0
    move.l  #0x22222222, 0x00001000

    | Exact ROM-shaped encoding: MOVE.W #$2700,SR.
    .short  0x46fc, 0x2700

    move.l  0x00001000, %d1
    cmp.l   #0x22222222, %d1
    bne     _fail

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
