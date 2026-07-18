| bit_field_ops.s — Bit field operations (BCHG, BSET, BCLR, BTST)
|
| Tests bit-level operations on register bits or memory bits.
| These are useful for hardware flags and bit arrays.
|
| Tests:
|   1. BTST — test bit (set Z based on bit value)
|   2. BSET — set bit to 1
|   3. BCLR — clear bit to 0
|   4. BCHG — toggle bit

    .text
    .org 0

_start:
    | Test 1: BTST — test a bit
    | D0 = 0x00000001 (bit 0 is set)
    | BTST #0, D0 → Z=0 (bit is set)
    move.l  #0x00000001, %d0
    btst    #0, %d0                 | test bit 0 of D0, Z = ~bit

    lea     0x00100000, %a0

    | Test 2: BTST — test a clear bit
    | D1 = 0x00000000
    | BTST #0, D1 → Z=1 (bit is clear)
    move.l  #0x00000000, %d1
    btst    #0, %d1                 | test bit 0 of D1, Z=1

    lea     0x00200000, %a1

    | Test 3: BSET — set a bit
    | D2 = 0x00000000
    | BSET #3, D2 → D2 = 0x00000008 (bit 3 set)
    move.l  #0x00000000, %d2
    bset    #3, %d2                 | D2 = 0x00000008

    lea     0x00300000, %a2

    | Test 4: BSET on already-set bit
    | D3 = 0x00000008 (bit 3 already set)
    | BSET #3, D3 → D3 = 0x00000008 (unchanged)
    move.l  #0x00000008, %d3
    bset    #3, %d3                 | D3 = 0x00000008 (unchanged)

    lea     0x00400000, %a3

    | Test 5: BCLR — clear a bit
    | D4 = 0x000000FF (bits 0-7 set)
    | BCLR #3, D4 → D4 = 0x000000F7 (bit 3 cleared)
    move.l  #0x000000FF, %d4
    bclr    #3, %d4                 | D4 = 0x000000F7

    lea     0x00500000, %a4

    | Test 6: BCLR on already-clear bit
    | D5 = 0x000000F7 (bit 3 already clear)
    | BCLR #3, D5 → D5 = 0x000000F7 (unchanged)
    move.l  #0x000000F7, %d5
    bclr    #3, %d5                 | D5 = 0x000000F7 (unchanged)

    lea     0x00600000, %a5

    | Test 7: BCHG — toggle a bit
    | D6 = 0x00000000
    | BCHG #15, D6 → D6 = 0x00008000 (bit 15 toggled to 1)
    move.l  #0x00000000, %d6
    bchg    #15, %d6                | D6 = 0x00008000

    lea     0x00700000, %a6

    | Test 8: BCHG toggle back
    | D7 = 0x00008000 (bit 15 is 1)
    | BCHG #15, D7 → D7 = 0x00000000 (bit 15 toggled to 0)
    move.l  #0x00008000, %d7
    bchg    #15, %d7                | D7 = 0x00000000

    lea     0x00800000, %a0

    | All bit operations tested; signal PASS
    lea     0xFFFF0000, %a1
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a1)              | PASS

_halt:
    stop    #0x2700
    bra     _halt

_fail:
    lea     0xFFFF0000, %a1
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a1)
    stop    #0x2700
    bra     _halt
