| elim_move_chained.s — chained MOVE.L Dn,Dm rename-elim.
|
| Validates that a fan-out chain of move-elims all settle on the
| original source's phys reg, and that downstream mutations only
| affect the mutating arch reg — not the aliased snapshots.
|
| Chain: D0 = value; D1 ← D0; D2 ← D1; D3 ← D2; D4 ← D3.  After
| elim, ratmap[D1..D4] should all alias phys_of_D0.  Mutating D4
| then D3 then D2 then D1 must see each break its alias correctly.

    .text
    .org 0

_start:
    move.l  #0xAAAA5555, %d0
    move.l  %d0, %d1
    move.l  %d1, %d2              | elim reads ratmap[D1] = phys_of_D0
    move.l  %d2, %d3
    move.l  %d3, %d4
    move.l  %d4, %d5
    move.l  %d5, %d6

    | Break each alias bottom-up to exercise ref-counted free on phys
    | retirement with shared aliases.
    move.l  #0x11111111, %d6
    move.l  #0x22222222, %d5
    move.l  #0x33333333, %d4
    move.l  #0x44444444, %d3

    | D1 and D2 must still hold the ORIGINAL source value.
    cmp.l   #0xAAAA5555, %d1
    bne     _fail
    cmp.l   #0xAAAA5555, %d2
    bne     _fail
    | Broken-alias regs hold their new scalars.
    cmp.l   #0x11111111, %d6
    bne     _fail
    cmp.l   #0x22222222, %d5
    bne     _fail
    cmp.l   #0x33333333, %d4
    bne     _fail
    cmp.l   #0x44444444, %d3
    bne     _fail
    cmp.l   #0xAAAA5555, %d0
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
