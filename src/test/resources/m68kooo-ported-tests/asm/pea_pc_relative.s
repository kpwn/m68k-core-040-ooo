| pea_pc_relative.s -- PEA (d16,PC)
|
| Covers the ROM frontier:
|   4080ed9c: 487a 0128  pea (0x128,PC)

    .text
    .org 0

_start:
    lea     0x00012000, %a7

    | PEA must preserve CCR.
    moveq   #0, %d7
    cmp.l   %d7, %d7

_pea_pos:
    .word   0x487a
    .word   _pos_target - (_pea_pos + 2)
    bne     _fail_flags
    bra     _pos_check
    .space  0x20, 0
_pos_target:
    .long   0x13579bdf

_pos_check:
    move.l  (%a7), %d0
    move.l  #_pos_target, %d1
    cmp.l   %d1, %d0
    bne     _fail_pos
    move.l  %a7, %d2
    cmp.l   #0x00011ffc, %d2
    bne     _fail_sp1
    bra     _pea_neg

    | Also cover a negative displacement sibling.
_neg_target:
    .long   0x2468ace0
_pea_neg:
    .word   0x487a
    .word   _neg_target - (_pea_neg + 2)
    move.l  (%a7), %d3
    move.l  #_neg_target, %d4
    cmp.l   %d4, %d3
    bne     _fail_neg
    move.l  %a7, %d5
    cmp.l   #0x00011ff8, %d5
    bne     _fail_sp2

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
    bra     _pass

_fail_flags:
    move.l  #0xDEAD0001, %d0
    bra     _fail
_fail_pos:
    move.l  #0xDEAD0002, %d0
    bra     _fail
_fail_sp1:
    move.l  #0xDEAD0003, %d0
    bra     _fail
_fail_neg:
    move.l  #0xDEAD0004, %d0
    bra     _fail
_fail_sp2:
    move.l  #0xDEAD0005, %d0

_fail:
    lea     0xFFFF0000, %a0
    move.l  %d0, (%a0)
    bra     _fail
