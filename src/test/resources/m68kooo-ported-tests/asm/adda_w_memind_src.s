| adda_w_memind_src.s -- Task #213 (D2)
|
| Directed: ADDA.W <memind-src>,An -- word sign-extend through memind.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    lea     0x00115f00, %a7

    | -- No-idx memind src: ADDA.W ([32,A4]),A5
    lea     0x00115200, %a4
    lea     0x00115220, %a0
    move.l  #0x00115400, (%a0)       | [A4+32] = pointer 0x00115400
    lea     0x00115400, %a0
    move.w  #0xFFFE, (%a0)           | [target].W = 0xFFFE (sx → 0xFFFFFFFE)
    move.l  #0x00000010, %a5         | A5 = 0x10

    | ADDA.W <ea>,An:  opword = 1101_aaa_011_mmm_rrr
    |   aaa=A5=101, mode=110, rrr=100 (A4).
    |   = 1101_101_011_110_100 = 0xDAF4
    | ext1 for no-idx memind null-od word-bd = 0x0164 (same as above).
    .word   0xDAF4, 0x0164, 0x0020
    | A5 should be 0x10 + (sign-extend 0xFFFE) = 0x10 - 2 = 0x0E.
    move.l  %a5, %d0
    cmp.l   #0x0000000E, %d0
    bne     _fail1

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a0)
_halt:
    bra     _halt

_fail1:
    move.l  #0xDEAD0001, %d7

_fail:
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
_halt_fail:
    bra     _halt_fail
