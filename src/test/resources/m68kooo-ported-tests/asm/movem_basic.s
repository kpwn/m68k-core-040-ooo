| movem_basic.s — MOVEM to/from memory round-trip.
|
| DEFERRED: MOVEM (0x48xx / 0x4Cxx) is not decoded in phase 2.1.  Test
| will fail (or the opwords will be pattern-matched as A-line / F-line /
| ILLEGAL — it's 0x4C -> 0100 1100, MS nibble 4, so NOT A-line/F-line;
| it'll be left as UOP_NOP or raise vec 4).
|
| Sequence:
|   1. Load D0..D3 with known values, A0..A2 with known values.
|   2. MOVEM.L D0-D3/A0-A2, -(SP)   — predecrement, pushes 7 longs.
|   3. Clobber D0..D3/A0..A2 with different values.
|   4. MOVEM.L (SP)+, D0-D3/A0-A2   — postincrement, pops them back.
|   5. Verify originals restored.  If all 7 match, write PASS.
|
| Coverage:
|   - register-list bitmask decode (two different orderings)
|   - predecrement form reverses register list (A2 first, D0 last)
|   - postincrement form normal order (D0 first, A2 last)
|   - SP fully restored to original value (pre-LINK discipline)
|
| PASS: 0xC0FFEE00 at 0xFFFF0000.
| FAIL: 0xDEADBEEF at 0xFFFF0000.

    .text
    .org 0

_start:
    lea     0x00010000, %a7              | stack base
    move.l  %a7, %d7                     | D7 = SP snapshot for later check

    | Canary values chosen so each register slot is distinguishable.
    move.l  #0x11111111, %d0
    move.l  #0x22222222, %d1
    move.l  #0x33333333, %d2
    move.l  #0x44444444, %d3
    move.l  #0xA0A0A0A0, %a0
    move.l  #0xA1A1A1A1, %a1
    move.l  #0xA2A2A2A2, %a2

    | Push D0-D3, A0-A2 to -(SP).  Predecrement form: A2 pushed first,
    | D0 pushed last — SP ends at original - 28.
    movem.l %d0-%d3/%a0-%a2, -(%a7)

    | Clobber every register to something else.
    move.l  #0xBAD00000, %d0
    move.l  #0xBAD00001, %d1
    move.l  #0xBAD00002, %d2
    move.l  #0xBAD00003, %d3
    move.l  #0xBAD0000A, %a0
    move.l  #0xBAD0000B, %a1
    move.l  #0xBAD0000C, %a2

    | Pop them back.  Postincrement form: D0 popped first, A2 popped last.
    movem.l (%a7)+, %d0-%d3/%a0-%a2

    | Check SP fully restored.
    cmp.l   %d7, %a7
    bne     _fail

    | Check each register.
    cmp.l   #0x11111111, %d0
    bne     _fail
    cmp.l   #0x22222222, %d1
    bne     _fail
    cmp.l   #0x33333333, %d2
    bne     _fail
    cmp.l   #0x44444444, %d3
    bne     _fail

    move.l  %a0, %d4
    cmp.l   #0xA0A0A0A0, %d4
    bne     _fail
    move.l  %a1, %d4
    cmp.l   #0xA1A1A1A1, %d4
    bne     _fail
    move.l  %a2, %d4
    cmp.l   #0xA2A2A2A2, %d4
    bne     _fail

    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
_halt_fail:
    bra     _halt_fail
