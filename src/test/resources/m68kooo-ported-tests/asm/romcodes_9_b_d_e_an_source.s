| romcodes_9_b_d_e_an_source.s -- ADD/SUB/CMP with address-register sources
|
| Covers the low-risk 1001/1011/1101 source-mode widening:
|   add.w/l  Ay,Dn
|   sub.w/l  Ay,Dn
|   cmp.w/l  Ay,Dn
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | ADD.W Ay,Dn preserves the upper word of Dn.
    move.l  #0x00010010, %a1
    move.l  #0xabcd0003, %d0
    add.w   %a1, %d0
    cmp.l   #0xabcd0013, %d0
    bne     _fail1

    | ADD.L Ay,Dn consumes the full 32-bit source.
    move.l  #0x00010010, %a2
    move.l  #0xabcd0003, %d1
    add.l   %a2, %d1
    cmp.l   #0xabce0013, %d1
    bne     _fail2

    | SUB.W Ay,Dn preserves the upper word of Dn.
    move.l  #0x00010010, %a3
    move.l  #0xabcd0020, %d2
    sub.w   %a3, %d2
    cmp.l   #0xabcd0010, %d2
    bne     _fail3

    | SUB.L Ay,Dn consumes the full 32-bit source.
    move.l  #0x00010010, %a4
    move.l  #0xabcd0020, %d3
    sub.l   %a4, %d3
    cmp.l   #0xabcc0010, %d3
    bne     _fail4

    | CMP.W Ay,Dn hits Z when the low 16 bits match.
    move.l  #0x00010010, %a5
    move.l  #0xabcd0010, %d4
    cmp.w   %a5, %d4
    bne     _fail5

    | CMP.L Ay,Dn hits Z on a full 32-bit match.
    move.l  #0x00010010, %a6
    move.l  #0x00010010, %d5
    cmp.l   %a6, %d5
    bne     _fail6

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a0)
_halt:
    bra     _halt

_fail1:
    move.l  #0xDEAD0001, %d7
    bra     _fail
_fail2:
    move.l  #0xDEAD0002, %d7
    bra     _fail
_fail3:
    move.l  #0xDEAD0003, %d7
    bra     _fail
_fail4:
    move.l  #0xDEAD0004, %d7
    bra     _fail
_fail5:
    move.l  #0xDEAD0005, %d7
    bra     _fail
_fail6:
    move.l  #0xDEAD0006, %d7
    bra     _fail

_fail:
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
    bra     _halt
