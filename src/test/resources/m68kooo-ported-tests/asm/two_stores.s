| two_stores.s — Independent back-to-back stores must both commit
|
| Store 0x11111111 to scratch+0 and 0x22222222 to scratch+0x10
| (independent addresses). Then read both back and verify.
| Catches bugs where the store buffer drops or reorders commits, or
| where iss_ready is released too early and overlaps two AXI writes.

    .text
    .org 0

_start:
    lea     0x00100000, %a0
    lea     0x00100010, %a1
    move.l  #0x11111111, %d0
    move.l  #0x22222222, %d1

    move.l  %d0, (%a0)          | store A
    move.l  %d1, (%a1)          | store B (independent)

    move.l  (%a0), %d2          | reload A
    move.l  (%a1), %d3          | reload B

    | check D2 == 0x11111111
    move.l  #0x11111111, %d4
    cmp.l   %d4, %d2
    bne     _fail
    | check D3 == 0x22222222
    move.l  #0x22222222, %d4
    cmp.l   %d4, %d3
    bne     _fail

    lea     0xFFFF0000, %a2
    move.l  #0xC0FFEE00, %d5
    move.l  %d5, (%a2)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a2
    move.l  #0xDEADBEEF, %d5
    move.l  %d5, (%a2)
_halt_fail:
    bra     _halt_fail
