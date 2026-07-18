| many_movs.s — ROB / free-list pressure via many independent MOVEs
|
| Emit many back-to-back MOVE.L #imm,Dn (all different imms, all
| writing different Dn). Each is independent, so the OoO window can
| expand them fully in parallel — exercises physical-register free
| list and ROB tail advancement under sustained dispatch.
|
| After the bulk of MOVEs, sum selected Dns; the sum must match.

    .text
    .org 0

_start:
    move.l  #0x00000001, %d0
    move.l  #0x00000002, %d1
    move.l  #0x00000004, %d2
    move.l  #0x00000008, %d3
    move.l  #0x00000010, %d4
    move.l  #0x00000020, %d5
    move.l  #0x00000040, %d6
    move.l  #0x00000080, %d7
    | sum D0..D7 into D0: expected = 0xFF
    add.l   %d1, %d0
    add.l   %d2, %d0
    add.l   %d3, %d0
    add.l   %d4, %d0
    add.l   %d5, %d0
    add.l   %d6, %d0
    add.l   %d7, %d0

    move.l  #0x000000FF, %d1
    cmp.l   %d1, %d0
    bne     _fail

    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d2
    move.l  %d2, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d2
    move.l  %d2, (%a0)
_halt_fail:
    bra     _halt_fail
