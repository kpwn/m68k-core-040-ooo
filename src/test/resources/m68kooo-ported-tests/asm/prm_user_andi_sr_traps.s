| prm_user_andi_sr_traps.s — ANDI #imm,SR is privileged in user mode.
|
| Spec: M68000 PRM, ANDI to SR.  Immediate logical operations to SR
| are privileged when the destination is SR; user mode raises vector 8.

    .text
    .org 0

_start:
    lea     0x00010000, %a7
    move.l  #_priv_handler, 0x00000020
    move.l  #_bad_vec4, 0x00000010

    move.l  #0x00008000, %a0
    move.l  %a0, %usp
    andi.w  #0xDFFF, %sr

    andi.w  #0xF8FF, %sr       | must trap in user mode

_fallthrough:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0001, %d0
    move.l  %d0, (%a0)
_halt_ft:
    bra     _halt_ft

_bad_vec4:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0002, %d0
    move.l  %d0, (%a0)
_halt_v4:
    bra     _halt_v4

_priv_handler:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt
