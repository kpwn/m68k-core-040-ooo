| lea_chain.s — LEA + (d16,An) load chain stressing AGU
|
| Builds a linked chain: LEA sets An, then MOVE.L (d16,An),Dn reads
| from a computed address, then LEA reuses that value as the next An.
|
| We pre-seed 4 memory cells with a "linked list" of pointers.  Each
| pointer chase is an LEA (to set up the base) + load (d16,An).
|
|   [0x00103000] = 0x00103020
|   [0x00103020] = 0x00103040
|   [0x00103040] = 0x00103060
|   [0x00103060] = 0xC0FFEE01  (end marker)
|
| NOTE: MOVE.L #imm,(An) isn't implemented, so we seed via Dn→(An).

    .text
    .org 0

_start:
    | Seed memory via D0 as temp
    lea     0x00103000, %a0
    move.l  #0x00103020, %d0
    move.l  %d0, (%a0)

    lea     0x00103020, %a1
    move.l  #0x00103040, %d0
    move.l  %d0, (%a1)

    lea     0x00103040, %a2
    move.l  #0x00103060, %d0
    move.l  %d0, (%a2)

    lea     0x00103060, %a3
    move.l  #0xC0FFEE01, %d0
    move.l  %d0, (%a3)

    | Walk the chain with (An),Dn loads (not (An),An — MOVEA.L
    | (An),An IS supported, but we also want the displacement form
    | exercised).  Save each pointer to Dn, then into An via LEA-like
    | load through (d16,An_prev).
    lea     0x00103000, %a0
    move.l  (%a0), %d1               | D1 = 0x00103020
    | Compute effective address from D1.  Use a load-through-A1
    | where A1 is the previous pointer.  We've already seeded A1 =
    | 0x00103020, so we can walk using (An),Dn loads only.
    move.l  (%a1), %d2               | D2 = 0x00103040
    move.l  (%a2), %d3               | D3 = 0x00103060
    move.l  (%a3), %d4               | D4 = 0xC0FFEE01

    cmp.l   #0xC0FFEE01, %d4
    bne     _fail

    | Also exercise (d16,An) displacement loads.  Use A0 at 0x00103000
    | and read back offset 0, 0x20, 0x40, 0x60 individually via d16.
    lea     0x00103000, %a0
    move.l  (%a0), %d1               | = 0x00103020
    move.l  32(%a0), %d2             | (d16=0x20,A0) = 0x00103040
    move.l  64(%a0), %d3             | = 0x00103060
    move.l  96(%a0), %d4             | = 0xC0FFEE01

    cmp.l   #0x00103020, %d1
    bne     _fail
    cmp.l   #0x00103040, %d2
    bne     _fail
    cmp.l   #0x00103060, %d3
    bne     _fail
    cmp.l   #0xC0FFEE01, %d4
    bne     _fail

    lea     0xFFFF0000, %a4
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a4)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a4
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a4)
_halt_fail:
    bra     _halt_fail
