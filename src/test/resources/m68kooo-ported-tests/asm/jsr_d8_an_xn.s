| jsr_d8_an_xn.s -- JSR (d8,An,Xn) brief-indexed call.
|
| Task #203 (B2): V2 migration of JSR brief-indexed (d8,An,Xn) form.
| Exercises target = An + sx8(disp) + Xn[.W|.L] * scale, with the EA
| computed BEFORE the ret_pc push so A7 as base/index observes the
| pre-call SP (PRM §4.135).  Also covers long-index variant.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    lea     0x00010000, %a7

    | (d8,An,D.W*4): target = A0 + 4 + sx(D1.W) * 4
    | A0 = _sub_w - 16, D1.W = 3 → target = _sub_w - 16 + 4 + 12 = _sub_w.
    lea     _sub_w - 16, %a0
    moveq   #3, %d1
    moveq   #0, %d0
_jsr_w:
    .word   0x4eb0, 0x1404        | opword=4eb0, ext1=1404 (D1.W*4 brief).
_after_w:
    cmp.l   #0x11112222, %d0
    bne     _fail
    cmpa.l  #0x00010000, %a7
    bne     _fail
    move.l  -4(%a7), %d7
    cmp.l   #_after_w, %d7
    bne     _fail

    | (d8,An,A.L*2): long-index from an A-reg.
    | A2 = _sub_l - 16, A3.L = 3, scale=2 (*2) → A2 + 10 + A3*2 = _sub_l
    move.l  #_sub_l, %a2
    sub.l   #16, %a2
    move.l  #3, %a3
    moveq   #0, %d0
_jsr_l:
    .word   0x4eb2, 0xba0a        | opword=4eb2, ext1=ba0a (A3.L*2, disp=+10)
_after_l:
    cmp.l   #0x33334444, %d0
    bne     _fail
    move.l  -4(%a7), %d7
    cmp.l   #_after_l, %d7
    bne     _fail

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d6
    move.l  %d6, (%a0)
_halt:
    bra     _halt

_sub_w:
    move.l  #0x11112222, %d0
    rts

_sub_l:
    move.l  #0x33334444, %d0
    rts

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d6
    move.l  %d6, (%a0)
_halt_fail:
    bra     _halt_fail
