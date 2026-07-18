| prm_rte_format2_pops_12.s — RTE from format-2 frame pops 12 bytes.
|
| Spec: M68040 User's Manual, exception stack frames and RTE.  A
| format-2 frame contains SR, PC, format/vector, and a longword
| instruction address; RTE discards all 12 bytes.

    .text
    .org 0

_start:
    lea     0x00010000, %a7
    move.l  %a7, %d6

    move.l  #0xA55A5AA5, -(%a7)   | format-2 extra longword
    move.w  #0x2000, -(%a7)       | format=2, vector offset 0
    move.l  #_landed, -(%a7)
    move.w  #0x2000, -(%a7)
    rte

_bad_resume:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0001, %d0
    move.l  %d0, (%a0)
_halt_bad:
    bra     _halt_bad

_landed:
    cmp.l   %d6, %a7
    bne     _fail2
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_fail2:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0002, %d0
    move.l  %d0, (%a0)
_halt_fail:
    bra     _halt_fail
