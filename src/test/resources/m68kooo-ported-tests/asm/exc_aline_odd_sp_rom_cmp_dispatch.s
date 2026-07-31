| exc_aline_odd_sp_rom_cmp_dispatch.s -- A-line frame on odd supervisor SP
|
| Covers the ROM boot shape around low-RAM 0x6b54:
|   - supervisor A7 is odd before the A-line opcode
|   - vec-10 pushes a format-0 frame at odd addresses
|   - Q700 dispatcher reads saved PC with movea.l 10(sp),a2
|   - dispatcher reads the A-line opword and takes the low A-trap path
|
| PASS: odd-address frame stores and split saved-PC read preserve the
|       stacked PC/opword.

    .text
    .org 0

_start:
    lea     0x00010001, %a7          | deliberately odd supervisor SP
    move.l  #_handler, 0x00000028    | vector 10 (A-line)

_aline_site:
    .short  0xa05d                   | same low-RAM A-trap seen in boot

_fallthrough:
    move.l  #0xDEAD0000, %d7
    bra     _fail

_handler:
    move.l  %a2, -(%sp)
    move.l  %d2, -(%sp)

    | Q700 dispatcher prologue: after two pushes, frame.PC is at 10(sp).
    movea.l 10(%sp), %a2
    cmp.l   #_aline_site, %a2
    bne     _fail_pc

    move.w  (%a2)+, %d2
    cmp.w   #0xa05d, %d2
    bne     _fail_opword

    cmpi.w  #0xa800, %d2
    bcs     _pass

_fail_cmp_branch:
    move.l  #0xDEAD0003, %d7
    bra     _fail

_fail_pc:
    move.l  #0xDEAD0001, %d7
    bra     _fail

_fail_opword:
    move.l  #0xDEAD0002, %d7
    bra     _fail

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
_halt_fail:
    bra     _halt_fail
