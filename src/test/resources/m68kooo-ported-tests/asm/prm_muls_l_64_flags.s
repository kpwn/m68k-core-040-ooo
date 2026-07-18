| prm_muls_l_64_flags.s — MULS.L 64-bit product flag semantics.
|
| Spec: M68000 PRM, integer instruction reference, MULS/MULU long.
| For the 64-bit product form, N reflects bit 63 of the product, Z
| reflects the entire 64-bit product, and V and C are cleared.

    .text
    .org 0

_start:
    | Signed -2 * 3 = -6, product high bit set.  V/C must be clear.
    move.l  #0xfffffffe, %d0
    move.l  #3, %d2
    .short  0x4C02, 0x0C01       | muls.l %d2, %d1:%d0
    bpl     _fail1
    beq     _fail1
    bvs     _fail1
    bcs     _fail1
    cmp.l   #0xfffffffa, %d0
    bne     _fail1
    cmp.l   #0xffffffff, %d1
    bne     _fail1

    | Zero product sets Z and still clears V/C.
    moveq   #0, %d0
    move.l  #0x76543210, %d2
    .short  0x4C02, 0x0C01       | muls.l %d2, %d1:%d0
    bne     _fail2
    bmi     _fail2
    bvs     _fail2
    bcs     _fail2
    cmp.l   #0, %d0
    bne     _fail2
    cmp.l   #0, %d1
    bne     _fail2

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

_fail:
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
_halt_fail:
    bra     _halt_fail
