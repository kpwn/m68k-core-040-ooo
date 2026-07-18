| waw_hazard.s — Write-After-Write hazard
|
| Two writes to D0 with an intervening reader of the FIRST value:
|   D0 = 0x11111111
|   D1 = D0 + 1        (reads first D0 = 0x11111111)
|   D0 = 0x22222222    (WAW: must not stomp D1's source)
| Then we verify D1 == 0x11111112 AND D0 == 0x22222222.
|
| If rename is broken (e.g. both D0 writes alias the same physreg, or the
| second write retires before the dependent reader issues), D1 ends up
| 0x22222223 and the test FAILs.

    .text
    .org 0

_start:
    move.l  #0x11111111, %d0    | first D0 producer
    move.l  %d0, %d1            | D1 = 0x11111111 (reads OLD D0)
    addq.l  #1, %d1             | D1 = 0x11111112
    move.l  #0x22222222, %d0    | WAW: redefine D0
    | check D1 == 0x11111112
    move.l  #0x11111112, %d2
    cmp.l   %d2, %d1
    bne     _fail
    | check D0 == 0x22222222
    move.l  #0x22222222, %d2
    cmp.l   %d2, %d0
    bne     _fail

    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d3
    move.l  %d3, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d3
    move.l  %d3, (%a0)
_halt_fail:
    bra     _halt_fail
