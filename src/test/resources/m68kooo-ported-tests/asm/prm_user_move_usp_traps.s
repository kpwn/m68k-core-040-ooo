| prm_user_move_usp_traps.s — MOVE USP,An is privileged in user mode.
|
| Spec: M68040 User's Manual, privilege model and MOVE USP instruction.
| User-mode execution of MOVE to/from USP raises privilege violation
| exception vector 8.

    .text
    .org 0

_start:
    lea     0x00010000, %a7
    move.l  #_priv_handler, 0x00000020
    move.l  #_bad_vec4, 0x00000010

    move.l  #0x00008000, %a0
    move.l  %a0, %usp
    andi.w  #0xDFFF, %sr       | enter user mode

    move.l  %usp, %a1          | must trap before writing A1

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
