| move_sr_ea_memdst.s — (An)-destination MOVE.W SR,<ea> coverage.
|
| Covers the memory-destination form that used to retire as SYS_NOP:
|   .word 0x40D0  MOVE.W SR,(A0)
|
| PASS: 0xC0FFEE00.  FAIL: 0xDEADBEEF.

    .text
    .org 0

_start:
    lea     0x00020000, %a7

    lea     _slot, %a0
    move.w  #0x271F, %sr
    .word   0x40D0                       | MOVE.W SR,(A0)

    move.l  _slot, %d0
    cmp.l   #0x271FAAAA, %d0
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

    .align 2
_slot:
    .long   0xAAAAAAAA
