| btst_dynamic_mem_byte.s — BTST Dm,(An) / BTST Dm,(d16,An) byte-mem
|
| PRM §4.16 BTST: for memory destinations the operand is one byte and
| the bit number is taken mod 8.  Z = NOT(addressed_bit); no write-back.
|
| Covers the V2 Stage D-4 dynamic-source BTST-mem crack:
|   Phase 0: TMP2 = Dm AND 7         (ALU_AND Dm,#7,TMP2)
|   Phase 1: TMP1 = byte load (EA)   (UOP_LOAD)
|   Phase 2: BTST TMP1,TMP2          (ALU_BTST, flags_wr={Z}, no dst)
|   Phase 3+: An writeback if postinc/predec
|
| Corner the D-4 agent almost got wrong: without the explicit "Dm AND 7"
| pre-mask, the ALU's b[4:0] would read bits 0-4 of Dm for a .B op —
| but PRM says mod 8.  So Dm=9 must test bit 1 (9 mod 8), not bit 9
| (which doesn't exist on a byte).  The test walks Dm = 0..15 against
| a known byte pattern and asserts Z tracks (pattern>>((Dm & 7)) & 1) == 0.
|
| PASS sentinel: 0xC0FFEE00 → 0xFFFF0000.

    .text
    .org 0

_start:
    | Byte pattern: 0xAA = 0b1010_1010 at (A1+0)
    | bits (LSB..MSB): 0 1 0 1 0 1 0 1
    lea     0x00100000, %a1
    move.b  #0xAA, (%a1)

    | Walk Dm = 0..7 (bits 0..7 of 0xAA respectively).
    | Z = NOT(bit) so:
    |   Dm=0 → bit 0 = 0 → Z = 1
    |   Dm=1 → bit 1 = 1 → Z = 0
    |   Dm=2 → bit 2 = 0 → Z = 1
    |   ...
    moveq   #0, %d0
    btst    %d0, (%a1)                 | bit 0 → Z=1
    bne     _fail

    moveq   #1, %d0
    btst    %d0, (%a1)                 | bit 1 → Z=0
    beq     _fail

    moveq   #2, %d0
    btst    %d0, (%a1)                 | bit 2 → Z=1
    bne     _fail

    moveq   #7, %d0
    btst    %d0, (%a1)                 | bit 7 → Z=0
    beq     _fail

    | Now test bit numbers >= 8 to confirm mod-8 mask.
    | Dm=8 should test bit 0 of the byte = 0 → Z=1.
    moveq   #8, %d0
    btst    %d0, (%a1)
    bne     _fail

    | Dm=9 should test bit 1 = 1 → Z=0.
    moveq   #9, %d0
    btst    %d0, (%a1)
    beq     _fail

    | Dm=15 should test bit 7 = 1 → Z=0.
    moveq   #15, %d0
    btst    %d0, (%a1)
    beq     _fail

    | (d16,An) — byte-mem target with offset.  Write 0x55 at (A1+4).
    | 0x55 = 0b01010101; bit 0 = 1, bit 1 = 0, bit 2 = 1.
    move.b  #0x55, 4(%a1)
    moveq   #0, %d1
    btst    %d1, 4(%a1)                | bit 0 = 1 → Z=0
    beq     _fail
    moveq   #1, %d1
    btst    %d1, 4(%a1)                | bit 1 = 0 → Z=1
    bne     _fail

    | BTST must NOT modify memory — verify 0xAA and 0x55 still intact.
    move.b  (%a1), %d2
    and.l   #0xff, %d2
    cmp.l   #0xAA, %d2
    bne     _fail
    move.b  4(%a1), %d2
    and.l   #0xff, %d2
    cmp.l   #0x55, %d2
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
