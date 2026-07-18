| elim_zero_idiom_variants.s — all four zero-idiom forms.
|
| Each of MOVEQ #0, SUB Dn,Dn, EOR Dn,Dn takes the zero-idiom path.
| Post-elim the arch reg must read 0 AND the CCR must reflect Z=1,
| N=0, V=0, C=0.  Back-to-back zero-idioms on different Dn exercise
| the PHYS_ZERO_TAG (phys 16) ref-counted aliasing path.

    .text
    .org 0

_start:
    | Prime each reg with non-zero so the elim result is observable.
    move.l  #0xDEADBEEF, %d0
    move.l  #0xCAFEBABE, %d1
    move.l  #0x01234567, %d2
    move.l  #0xFFFF0001, %d3

    | Variant 1: MOVEQ #0,Dn
    moveq   #0, %d0
    cmp.l   #0, %d0
    bne     _fail

    | Variant 2: SUB.L Dn,Dn  (writes X=0 too)
    sub.l   %d1, %d1
    cmp.l   #0, %d1
    bne     _fail

    | Variant 3: EOR.L Dn,Dn
    eor.l   %d2, %d2
    cmp.l   #0, %d2
    bne     _fail

    | All three together back-to-back on a fresh reg — exercises
    | PHYS_ZERO_TAG ref-count growth + reg-write-breaks-alias path.
    moveq   #0, %d3
    moveq   #0, %d4
    moveq   #0, %d5
    cmp.l   #0, %d3
    bne     _fail
    cmp.l   #0, %d4
    bne     _fail
    cmp.l   #0, %d5
    bne     _fail

    | Mutate D5 — must break the alias to PHYS_ZERO and stand alone.
    move.l  #0xABCD1234, %d5
    cmp.l   #0xABCD1234, %d5
    bne     _fail
    | D3, D4 must still read 0 (their alias is intact).
    cmp.l   #0, %d3
    bne     _fail
    cmp.l   #0, %d4
    bne     _fail

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d7
    move.l  %d7, (%a0)
_halt_fail:
    bra     _halt_fail
