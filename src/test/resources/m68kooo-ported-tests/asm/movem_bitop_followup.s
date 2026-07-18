| movem_bitop_followup.s -- 6-register MOVEM round-trip followed by a
| bit-op that immediately reads a restored D register.
|
| Regression for BUG_movem_large_plus_bitop_hangs.md.  The old fuzz
| workaround capped MOVEM round-trips at 4 registers because 5+ followed
| by BCLR/BCHG/BSET could leave the reader waiting forever.
|
| PASS: 0xC0FFEE00 at 0xFFFF0000.
| FAIL: 0xDEADBEEF at 0xFFFF0000.

    .text
    .org 0

_start:
    lea     0x00080000, %a7

    move.l  #0x11111119, %d0
    move.l  #0x22222222, %d2
    move.l  #0x33333333, %d3
    move.l  #0x55555555, %d5
    lea     0x00100200, %a2
    lea     0x00100300, %a3

    movem.l %d0/%d2-%d3/%d5/%a2-%a3, -(%a7)

    move.l  #0xDEAD0000, %d0
    move.l  #0xDEAD0002, %d2
    move.l  #0xDEAD0003, %d3
    move.l  #0xDEAD0005, %d5
    lea     0x0010AA00, %a2
    lea     0x0010BB00, %a3

    movem.l (%a7)+, %d0/%d2-%d3/%d5/%a2-%a3

    bclr    #3, %d0
    cmp.l   #0x11111111, %d0
    bne     _fail
    cmp.l   #0x22222222, %d2
    bne     _fail
    cmp.l   #0x33333333, %d3
    bne     _fail
    cmp.l   #0x55555555, %d5
    bne     _fail
    move.l  %a2, %d6
    cmp.l   #0x00100200, %d6
    bne     _fail
    move.l  %a3, %d6
    cmp.l   #0x00100300, %d6
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
