| exg_basic.s -- EXG register-register forms
|
| Covers the Q700 ROM frontier opcode:
|   0xC78F at 0x40800A90: EXG D3,A7
|
| Also exercises Dn,Dm and An,Am sibling forms.  EXG is long-sized and
| must not modify the CCR.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | Dn,Dm.
    move.l  #0x11112222, %d0
    move.l  #0x33334444, %d1
    moveq   #0, %d5
    tst.l   %d5
    exg     %d0, %d1
    bne     _fail
    cmp.l   #0x33334444, %d0
    bne     _fail
    cmp.l   #0x11112222, %d1
    bne     _fail

    | An,Am.
    lea     0x00102000, %a4
    lea     0x00103000, %a5
    moveq   #0, %d5
    tst.l   %d5
    exg     %a4, %a5
    bne     _fail
    move.l  %a4, %d2
    cmp.l   #0x00103000, %d2
    bne     _fail
    move.l  %a5, %d2
    cmp.l   #0x00102000, %d2
    bne     _fail

    | Dn,An: exact ROM shape EXG D3,A7.
    move.l  #0x00180000, %d3
    lea     0x00080000, %a7
    moveq   #0, %d5
    tst.l   %d5
    .word   0xc78f                | exg %d3,%a7
    bne     _fail
    cmp.l   #0x00080000, %d3
    bne     _fail
    move.l  %a7, %d2
    cmp.l   #0x00180000, %d2
    bne     _fail

    lea     0xFFFF0000, %a6
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a6)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a6
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a6)
_fail_halt:
    bra     _fail_halt
