| prm_brief_index_w_scale4.s — brief indexed mode applies W index scale.
|
| Spec: M68040 User's Manual, effective-address calculation, brief
| extension word.  A word index is sign-extended to 32 bits before the
| encoded scale factor is applied.

    .text
    .org 0

_start:
    lea     table, %a0

    | Case 1: positive index — D0.W=2, scale=4, disp=4
    | EA = A0 + 4 + sign_ext(D0.W)*4 = A0 + 4 + 8 = A0+12 = table[3]
    move.l  #2, %d0
    move.l  4(%a0, %d0.w*4), %d1
    cmp.l   #0x44444444, %d1
    bne     _fail1

    | Case 2: negative index — D0.W=-1 (=0xFFFF sign-extended)
    | scale=4, disp=8 → EA = A0 + 8 + (-1)*4 = A0+4 = table[1]
    move.l  #0x0000ffff, %d0     | D0.W = -1
    move.l  8(%a0, %d0.w*4), %d1
    cmp.l   #0x22222222, %d1
    bne     _fail2

_pass:
    lea     0xFFFF0000, %a1
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a1)
_halt:
    bra     _halt

_fail1:
    move.l  #0xDEAD0001, %d7
    bra     _fail
_fail2:
    move.l  #0xDEAD0002, %d7

_fail:
    lea     0xFFFF0000, %a1
    move.l  %d7, (%a1)
_halt_fail:
    bra     _halt_fail

    .align 4
table:
    .long   0x11111111
    .long   0x22222222
    .long   0x33333333
    .long   0x44444444
