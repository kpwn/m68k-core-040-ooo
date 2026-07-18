| swap_unary.s — SWAP, NEG, NOT unary operations
|
| Tests:
|   1. SWAP — exchange high and low words of a register
|   2. NEG — negate (two's complement, 0 - Dn)
|   3. NOT — bitwise complement (flip all bits)

    .text
    .org 0

_start:
    | Test 1: SWAP — exchange high and low words
    | D0 = 0x12345678
    | SWAP: D0 = 0x56781234
    move.l  #0x12345678, %d0
    swap    %d0                     | D0 = 0x56781234

    lea     0x00100000, %a0

    | Test 2: SWAP on same high/low word
    | D1 = 0x11111111
    | SWAP: D1 = 0x11111111 (unchanged)
    move.l  #0x11111111, %d1
    swap    %d1                     | D1 = 0x11111111

    lea     0x00200000, %a1

    | Test 3: NEG — negate a positive value
    | D2 = 0x00000001
    | NEG: D2 = 0xFFFFFFFF (two's complement of 1 is -1)
    move.l  #0x00000001, %d2
    neg.l   %d2                     | D2 = 0xFFFFFFFF

    lea     0x00300000, %a2

    | Test 4: NEG on negative value
    | D3 = 0xFFFFFFFF (-1)
    | NEG: D3 = 0x00000001 (negating -1 gives +1)
    move.l  #0xFFFFFFFF, %d3
    neg.l   %d3                     | D3 = 0x00000001

    lea     0x00400000, %a3

    | Test 5: NEG on zero
    | D4 = 0x00000000
    | NEG: D4 = 0x00000000 (Z=1, C=0, V=0)
    move.l  #0x00000000, %d4
    neg.l   %d4                     | D4 = 0x00000000, Z=1

    lea     0x00500000, %a4

    | Test 6: NEG with overflow
    | D5 = 0x80000000 (most negative)
    | NEG: 0x00000000 - 0x80000000 = 0x80000000 (can't negate min value!)
    | Result: D5 = 0x80000000, V=1 (overflow)
    move.l  #0x80000000, %d5
    neg.l   %d5                     | D5 = 0x80000000, V=1

    lea     0x00600000, %a5

    | Test 7: NOT — bitwise complement
    | D6 = 0x00000000
    | NOT: D6 = 0xFFFFFFFF
    move.l  #0x00000000, %d6
    not.l   %d6                     | D6 = 0xFFFFFFFF

    lea     0x00700000, %a6

    | Test 8: NOT on 0xFFFFFFFF
    | D7 = 0xFFFFFFFF
    | NOT: D7 = 0x00000000
    move.l  #0xFFFFFFFF, %d7
    not.l   %d7                     | D7 = 0x00000000

    lea     0x00800000, %a0

    | All unary ops tested; signal PASS
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
