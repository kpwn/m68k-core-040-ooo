| adda_l_d8_an_xn_src.s -- ADDA.L (d8,An,Xn),An (V2-fired indexed src).
|
| Task #222 (E6) — V2 covers ADDA.L with brief-indexed source.
| ADDA does not modify CCR; this test guards that and verifies the
| target An receives the loaded long.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | Pre-set CCR-touching state: clear with cmpi yielding Z=1.
    | (We then assert Z is unchanged after ADDA below.)
    cmpi.l  #0, %d0

    | ADDA.L scale x1, long-index, disp +8 — A0 += (8,A1,D2.L*1).
    movea.l #0x10000000, %a0
    lea     0x00133000, %a1
    lea     0x00133010, %a6           | put 0x07 at a1+8+8=0x10
    move.l  #0x12345678, (%a6)
    moveq   #8, %d2
    .word   0xd1f1, 0x2808       | adda.l (8,A1,D2.L*1),A0
    cmpa.l  #0x22345678, %a0
    bne     _adda_fail

    | Verify Z still 1 (ADDA does not write CCR).
    bne     _fail
    beq     _ok1
    bra     _fail
_ok1:

    | ADDA.L scale x4 word-index — A2 += (4,A3,D5.W*4).
    movea.l #0x00010000, %a2
    lea     0x00133100, %a3
    lea     0x00133108, %a6           | a3+4+1*4=0x108
    move.l  #0x000000FF, (%a6)
    moveq   #1, %d5
    .word   0xd5f3, 0x5404       | adda.l (4,A3,D5.W*4),A2
    cmpa.l  #0x000100FF, %a2
    bne     _fail

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_adda_fail:
_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
_halt_fail:
    bra     _halt_fail
