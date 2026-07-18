| adda_long_mem_forms.s -- ADDA.L <ea>,An memory-source forms
|
| Covers the already-supported long memory-source family beyond the
| indirect/postinc/predec/displacement cases: absolute-short,
| absolute-long, and PC-relative siblings, plus one indirect anchor.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | Pre-seed an absolute-short slot before the main sequence.
    move.l  #0x00007200, %a6
    move.l  #0x00000009, %d0
    move.l  %d0, (%a6)

    | Exact indirect form keeps CCR unchanged.
    move.l  #0x00115000, %a5
    move.l  #0x00400000, %d0
    move.l  %d0, (%a5)
    move.l  #0x08000000, %a1
    moveq   #0, %d7
    tst.l   %d7
    adda.l  (%a5), %a1
    bne     _fail1
    cmpa.l  #0x08400000, %a1
    bne     _fail1
    cmpa.l  #0x00115000, %a5
    bne     _fail1

    | Absolute-short source.
    move.l  #0x00000100, %a0
    adda.l  0x7200.w, %a0
    cmpa.l  #0x00000109, %a0
    bne     _fail2

    | Absolute-long source from a compile-time data slot.
    move.l  #0x00000100, %a0
    adda.l  _adda_absl_slot, %a0
    cmpa.l  #0x0000011b, %a0
    bne     _fail3

    | PC-relative long source.
    move.l  #0x00000100, %a0
    adda.l  _adda_pc_slot(%pc), %a0
    cmpa.l  #0x0000012d, %a0
    bne     _fail4

    | Negative long from displacement form still sign-adds as a full long.
    move.l  #0x00115040, %a5
    move.l  #0xfffffff0, 12(%a5)
    move.l  #0x00000100, %a0
    adda.l  12(%a5), %a0
    cmpa.l  #0x000000f0, %a0
    bne     _fail5

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d2
    move.l  %d2, (%a0)
_halt:
    bra     _halt

_fail1:
    move.l  #0xDEAD0001, %d2
    bra     _fail
_fail2:
    move.l  #0xDEAD0002, %d2
    bra     _fail
_fail3:
    move.l  #0xDEAD0003, %d2
    bra     _fail
_fail4:
    move.l  #0xDEAD0004, %d2
    bra     _fail
_fail5:
    move.l  #0xDEAD0005, %d2
    bra     _fail

_fail:
    lea     0xFFFF0000, %a0
    move.l  %d2, (%a0)
_halt_fail:
    bra     _halt_fail

    .align 2
_adda_pc_slot:
    .long   0x0000002d

    .align 2
_adda_absl_slot:
    .long   0x0000001b
