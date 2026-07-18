| rom_boot_checksum_loop.s — MOVE.L (An)+ + ADD.L + SUBQ.L #1,Dn + BNE
|
| Models the ROM checksum-walker shape: fetch a long via postincrement,
| accumulate it, and use SUBQ/BNE as the loop control. This is aimed at
| regressions in the exact decode/branch pairing that a ROM loop leans on.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    move.l  #0x00102000, %a0
    move.l  #0x11111111, (%a0)
    move.l  #0x22222222, 4(%a0)
    move.l  #0x33333333, 8(%a0)
    move.l  #0x44444444, 12(%a0)

    moveq   #0, %d0                 | running checksum
    moveq   #4, %d1                 | loop count

_loop:
    move.l  (%a0)+, %d2             | postincrement load
    add.l   %d2, %d0                | checksum += entry
    subq.l  #1, %d1
    bne     _loop

    cmp.l   #0xaaaaaaaa, %d0
    bne     _fail
    cmpa.l  #0x00102010, %a0
    bne     _fail

_pass:
    lea     0xFFFF0000, %a1
    move.l  #0xC0FFEE00, %d3
    move.l  %d3, (%a1)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a1
    move.l  #0xDEADBEEF, %d3
    move.l  %d3, (%a1)
_halt_fail:
    bra     _halt_fail
