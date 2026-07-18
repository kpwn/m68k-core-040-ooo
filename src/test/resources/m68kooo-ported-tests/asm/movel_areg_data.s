| movel_areg_data.s -- MOVE.L An,Dn
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | Zero address value sets Z and clears N/V/C.
    movea.l #0x00000000, %a0
    move.l  #0x12345678, %d0
    .word   0x2008              | move.l %a0,%d0
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail
    cmp.l   #0x00000000, %d0
    bne     _fail

    | Negative address value sets N.
    movea.l #0x80000004, %a7
    move.l  #0xfeedbeef, %d0
    .word   0x200f              | move.l %a7,%d0
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    cmp.l   #0x80000004, %d0
    bne     _fail

    | Positive non-zero address clears N/Z/V/C.
    movea.l #0x00123456, %a3
    move.l  #0xaaaaaaaa, %d0
    .word   0x200b              | move.l %a3,%d0
    beq     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail
    cmp.l   #0x00123456, %d0
    bne     _fail

_pass:
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
