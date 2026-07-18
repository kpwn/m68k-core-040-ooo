| elim_move_reg.s — MOVE.L Dn,Dm rename-elim basic correctness.
|
| Validates that Phase A2 move-elim preserves the source's value in
| the destination arch register across subsequent reads.  Both Dn and
| Dm must carry the moved value; a subsequent mutation of Dn must NOT
| bleed into Dm (alias-break-on-rewrite via RAT ref-count).

    .text
    .org 0

_start:
    move.l  #0x12345678, %d0      | D0 = source value
    move.l  %d0, %d1              | elim: ratmap[D1] ← phys_of_D0
    move.l  %d0, %d2              | elim: ratmap[D2] ← phys_of_D0
    move.l  %d0, %d3              | elim: ratmap[D3] ← phys_of_D0

    | Mutate D0 — must break the alias for D1..D3 which keep 0x12345678.
    move.l  #0xDEADBEEF, %d0

    | Check: D1 must equal the ORIGINAL value, not the new D0.
    cmp.l   #0x12345678, %d1
    bne     _fail
    cmp.l   #0x12345678, %d2
    bne     _fail
    cmp.l   #0x12345678, %d3
    bne     _fail
    cmp.l   #0xDEADBEEF, %d0
    bne     _fail

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d4
    move.l  %d4, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d4
    move.l  %d4, (%a0)
_halt_fail:
    bra     _halt_fail
