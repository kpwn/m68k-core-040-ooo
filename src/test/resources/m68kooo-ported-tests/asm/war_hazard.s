| war_hazard.s — Write-After-Read hazard
|
|   D0 = 0xAAAA0000
|   D1 = D0 + 1            (reader of D0)
|   D0 = 0x55550000        (writer of D0, must NOT be observed by D1)
|
| Then immediately re-read D0 to confirm the new value, and check D1
| still holds the OLD value + 1. If the OoO machinery lets the second
| MOVE complete before D1's source is captured, D1 becomes 0x55550001.

    .text
    .org 0

_start:
    move.l  #0xAAAA0000, %d0
    move.l  %d0, %d1
    addq.l  #1, %d1             | D1 = 0xAAAA0001 (must use OLD D0)
    move.l  #0x55550000, %d0    | new D0 — WAR vs the move above

    | check D1 still holds OLD-derived value
    move.l  #0xAAAA0001, %d2
    cmp.l   %d2, %d1
    bne     _fail
    | check D0 holds NEW value
    move.l  #0x55550000, %d2
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
