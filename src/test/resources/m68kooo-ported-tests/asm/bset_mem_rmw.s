| bset_mem_rmw.s — BSET #n,(An) byte memory RMW crack
|
| PRM §4.18 BSET: memory target is one byte, bit number mod 8.
| CCR.Z = NOT(old_bit), then the bit is SET.  N/V/C/X preserved.
|
| Covers the V2 Stage D-4 static-source BSET-mem crack (3 µops):
|   Phase 0: TMP1 = byte load (An)      (UOP_LOAD)
|   Phase 1: TMP1 = BSET TMP1,#imm      (ALU_BSET, flags_wr={Z})
|   Phase 2: STORE TMP1,(An)            (UOP_STORE)
|
| Corner the D-4 agent almost got wrong: the CCR.Z reported by BSET
| reflects the OLD bit value, not the new one.  Setting a bit that
| was already 1 must produce Z=0; setting a bit that was 0 must
| produce Z=1.  Also the byte stored back must differ from the
| loaded byte in ONLY the BSET'd bit — a byte-merge bug would
| clobber neighbouring bits.
|
| PASS sentinel: 0xC0FFEE00 → 0xFFFF0000.

    .text
    .org 0

_start:
    lea     0x00200000, %a0
    move.b  #0x10, (%a0)               | start: 0b0001_0000

    | BSET #5,(A0) — bit 5 is 0 → Z=1, byte becomes 0b0011_0000 = 0x30.
    bset    #5, (%a0)
    bne     _fail                      | Z must be 1 (old bit was 0)
    move.b  (%a0), %d0
    and.l   #0xff, %d0
    cmp.l   #0x30, %d0
    bne     _fail

    | BSET #5,(A0) again — now bit 5 = 1 → Z=0, byte stays 0x30.
    bset    #5, (%a0)
    beq     _fail                      | Z must be 0 (old bit was 1)
    move.b  (%a0), %d0
    and.l   #0xff, %d0
    cmp.l   #0x30, %d0
    bne     _fail

    | BSET #0,(A0) — set bit 0 of 0x30 → 0x31, Z=1.
    bset    #0, (%a0)
    bne     _fail
    move.b  (%a0), %d0
    and.l   #0xff, %d0
    cmp.l   #0x31, %d0
    bne     _fail

    | BSET #7,(A0) — set the top bit → 0xB1, Z=1.
    bset    #7, (%a0)
    bne     _fail
    move.b  (%a0), %d0
    and.l   #0xff, %d0
    cmp.l   #0xB1, %d0
    bne     _fail

    | Bit-number > 7 must fold via mod 8 — but static form already
    | forces ext1[2:0] in the mem crack.  Musashi would wrap #13 to
    | bit 5 (13 mod 8).  Bit 5 is currently 1 → Z=0, byte unchanged.
    bset    #13, (%a0)                 | bit (13 mod 8) = bit 5, already set
    beq     _fail                      | Z=0 (old bit was 1)
    move.b  (%a0), %d0
    and.l   #0xff, %d0
    cmp.l   #0xB1, %d0
    bne     _fail

    | Neighbour-byte noise check: BSET on (A0+4) must not disturb (A0).
    move.b  #0x00, 4(%a0)
    bset    #3, 4(%a0)                 | (d16,An) byte — sets bit 3.
    bne     _fail                      | Z=1 old bit was 0
    move.b  4(%a0), %d1
    and.l   #0xff, %d1
    cmp.l   #0x08, %d1
    bne     _fail
    | (A0) still 0xB1 from the previous step.
    move.b  (%a0), %d1
    and.l   #0xff, %d1
    cmp.l   #0xB1, %d1
    bne     _fail

_pass:
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
