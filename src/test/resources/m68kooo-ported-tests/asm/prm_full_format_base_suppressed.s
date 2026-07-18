| prm_full_format_base_suppressed.s — full-format indexed BS suppresses An.
|
| Spec: M68040 User's Manual, full extension-word effective-address
| format.  When BS=1, the encoded base register is suppressed and the
| base displacement supplies the base address.

    .text
    .org 0

_start:
    | A0 deliberately points elsewhere.  BS=1 means it must not
    | contribute to the effective address.
    lea     0x00002000, %a0
    lea     0x00000700, %a1
    move.l  #0x13579BDF, (%a1)
    moveq   #0, %d0

    | MOVE.L (full format, BD.W=0x0700, BS=1, D0.W*1),D1.
    | Instruction uses mode 110/reg A0, but A0 is suppressed.
    .word   0x2230, 0x01A0, 0x0700
    cmp.l   #0x13579BDF, %d1
    bne     _fail1

    | With D0.W=4 and scale x1, the same suppressed-base form reaches
    | BD+4, proving the index still applies.
    moveq   #4, %d0
    move.l  #0x2468ACE0, 4(%a1)
    .word   0x2430, 0x01A0, 0x0700
    cmp.l   #0x2468ACE0, %d2
    bne     _fail2

_pass:
    lea     0xFFFF0000, %a2
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a2)
_halt:
    bra     _halt

_fail1:
    move.l  #0xDEAD0001, %d7
    bra     _fail
_fail2:
    move.l  #0xDEAD0002, %d7

_fail:
    lea     0xFFFF0000, %a2
    move.l  %d7, (%a2)
_halt_fail:
    bra     _halt_fail
