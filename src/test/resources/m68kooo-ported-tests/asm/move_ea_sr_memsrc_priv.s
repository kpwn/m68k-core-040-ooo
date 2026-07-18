| move_ea_sr_memsrc_priv.s — privilege wins before memory source load.
|
| User-mode MOVE.W (A0),SR is privileged.  A0 deliberately points at an
| unmapped address; a buggy crack that issues the load before the
| privilege check takes vec 2.  Correct behavior is vec 8.
|
| PASS: vec 8 handler writes 0xC0FFEE00.
| FAIL: vec 2 handler or fallthrough writes a DEADBEEF variant.

    .text
    .org 0

_start:
    lea     0x00010000, %a7
    move.l  #_bus_handler, 0x00000008    | vec 2 — must not fire
    move.l  #_priv_handler, 0x00000020   | vec 8 — expected

    | Drop to user mode, then attempt privileged memory-source SR write.
    andi.w  #0xDFFF, %sr
    lea     0xAAAA0000, %a0
    .word   0x46D0                       | MOVE.W (A0),SR

_fail:
    lea     0xFFFF0000, %a1
    move.l  #0xDEADBEEF, %d1
    move.l  %d1, (%a1)
_halt_fail:
    bra     _halt_fail

_bus_handler:
    lea     0xFFFF0000, %a1
    move.l  #0xDEADB002, %d1
    move.l  %d1, (%a1)
_halt_bus:
    bra     _halt_bus

_priv_handler:
    lea     0xFFFF0000, %a1
    move.l  #0xC0FFEE00, %d1
    move.l  %d1, (%a1)
_halt:
    bra     _halt
