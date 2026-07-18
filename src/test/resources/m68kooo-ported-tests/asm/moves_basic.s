| moves_basic.s — MOVES in supervisor: round-trip a long through (An).
|
| MOVES is the 68010+ "move between address spaces" instruction —
| privileged, uses SFC/DFC as the bus function code for the access.
|
| Current landing: this core plumbs SFC/DFC as observability-only
| 3-bit registers (MOVEC round-trip works); the bus does NOT yet
| forward FC bits to the AXI master.  This test therefore verifies:
|   (a) MOVEC can write + read back SFC and DFC (CR plumbing).
|   (b) MOVES.L Dn,(An) writes 4 bytes to memory — observable by
|       reading the same address via plain MOVE.L and comparing.
|   (c) MOVES.L (An),Dn reads them back correctly.
|   (d) MOVES.W round-trip.
|
| A followup landing will tie DFC through to the bus and extend the
| check to include actual FC verification.
|
| PASS: sentinel 0xC0FFEE00.

    .text
    .org 0

_start:
    lea     0x00010000, %a7

    | Program DFC = 1 (user data space).  MOVEC encodes DFC as 0x001.
    move.l  #1, %d0
    movec   %d0, %dfc
    movec   %dfc, %d1
    cmp.l   #1, %d1
    bne     _fail

    | Program SFC = 5 (supervisor data space) to exercise the other reg.
    move.l  #5, %d0
    movec   %d0, %sfc
    movec   %sfc, %d1
    cmp.l   #5, %d1
    bne     _fail

    | MOVES.L D0,(A0): store D0 into memory at (A0).
    lea     0x00020100, %a0
    move.l  #0xFACEB00C, %d0
    moves.l %d0, (%a0)

    | Verify via plain MOVE.L (A0),Dn.
    move.l  (%a0), %d2
    cmp.l   #0xFACEB00C, %d2
    bne     _fail

    | MOVES.L (A0),D3: read back via MOVES.
    moves.l (%a0), %d3
    cmp.l   #0xFACEB00C, %d3
    bne     _fail

    | MOVES.W round-trip.
    move.l  #0x00001234, %d4
    moves.w %d4, (%a0)
    move.w  (%a0), %d5
    andi.l  #0xFFFF, %d5
    cmp.l   #0x1234, %d5
    bne     _fail

    | MOVES.W read-back via MOVES too.
    moveq   #0, %d6
    moves.w (%a0), %d6
    andi.l  #0xFFFF, %d6
    cmp.l   #0x1234, %d6
    bne     _fail

    lea     0xFFFF0000, %a1
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a1)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a1
    move.l  #0xDEADBEEF, %d7
    move.l  %d7, (%a1)
_halt_fail:
    bra     _halt_fail
