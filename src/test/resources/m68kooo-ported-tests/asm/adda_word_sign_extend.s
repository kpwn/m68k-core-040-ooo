| adda_word_sign_extend.s — ADDA.W sign-extends word source to long
|
| PRM §4.4: ADDA.W <ea>,An performs An = An + sign_extend_long(<ea>).
| The 32-bit add uses the full An, and CCR is never touched.
|
| Covers the V2 Stage D-2 ADDA.W 2-µop crack (EXT src → TMP1; ADD An,TMP1).
|
| PASS sentinel: 0xC0FFEE00 → 0xFFFF0000.

    .text
    .org 0

_start:
    | Negative word source sign-extends, so A0 should DECREASE.
    movea.l #0x00010000, %a0
    move.w  #0xffff, %d0              | D0 low word = -1 (0xffff)
    adda.w  %d0, %a0                  | A0 += -1 = 0x0000ffff
    cmpa.l  #0x0000ffff, %a0
    bne     _fail

    | Positive word source zero-extends the top half.
    movea.l #0x00100000, %a1
    move.l  #0x12347fff, %d1          | low word = 0x7fff
    adda.w  %d1, %a1                  | A1 += 0x7fff = 0x00107fff
    cmpa.l  #0x00107fff, %a1
    bne     _fail

    | An source sign-extends identically.
    movea.l #0x0000ffff, %a2
    movea.l #0x00200000, %a3
    adda.w  %a2, %a3                  | A3 += sx(0xffff) = A3 + -1
    cmpa.l  #0x001fffff, %a3
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
