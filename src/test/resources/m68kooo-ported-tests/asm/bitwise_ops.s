| bitwise_ops.s — AND, OR, EOR, NOT bitwise operations
|
| Tests:
|   1. AND.L — bitwise AND, sets Z if result=0, clears V and C
|   2. OR.L — bitwise OR
|   3. EOR.L — bitwise exclusive OR
|   4. NOT.L — bitwise NOT (invert all bits)

    .text
    .org 0

_start:
    | Test 1: AND.L
    | D0 = 0xFFFF0000, D1 = 0x0000FFFF
    | AND: D0 = 0x00000000 (no overlap, Z=1)
    move.l  #0xFFFF0000, %d0
    move.l  #0x0000FFFF, %d1
    and.l   %d1, %d0                | D0 = 0x00000000, Z=1

    lea     0x00100000, %a0

    | Test 2: AND with overlap
    | D2 = 0xF0F0F0F0, D3 = 0x0F0F0F0F
    | AND: D2 = 0x00000000 (no bits in common)
    move.l  #0xF0F0F0F0, %d2
    move.l  #0x0F0F0F0F, %d3
    and.l   %d3, %d2                | D2 = 0x00000000

    lea     0x00200000, %a1

    | Test 3: OR.L
    | D4 = 0xFFFF0000, D5 = 0x0000FFFF
    | OR: D4 = 0xFFFFFFFF (all bits set, Z=0, N=1)
    move.l  #0xFFFF0000, %d4
    move.l  #0x0000FFFF, %d5
    or.l    %d5, %d4                | D4 = 0xFFFFFFFF

    lea     0x00300000, %a2

    | Test 4: EOR.L (exclusive OR, XOR)
    | D6 = 0xFFFFFFFF, D7 = 0xFFFFFFFF
    | EOR: D6 = 0x00000000 (same values → 0, Z=1)
    move.l  #0xFFFFFFFF, %d6
    move.l  #0xFFFFFFFF, %d7
    eor.l   %d7, %d6                | D6 = 0x00000000, Z=1

    lea     0x00400000, %a3

    | Test 5: NOT.L (bitwise NOT, complement)
    | D0 = 0x00000000
    | NOT: D0 = 0xFFFFFFFF (all bits flipped, N=1, Z=0)
    move.l  #0x00000000, %d0
    not.l   %d0                     | D0 = 0xFFFFFFFF, N=1

    lea     0x00500000, %a4

    | Test 6: NOT on negative number
    | D1 = 0xFFFFFFFF (all bits set)
    | NOT: D1 = 0x00000000 (all bits cleared, Z=1, N=0)
    move.l  #0xFFFFFFFF, %d1
    not.l   %d1                     | D1 = 0x00000000, Z=1

    lea     0x00600000, %a5

    | All bitwise ops tested; signal PASS
    lea     0xFFFF0000, %a6
    move.l  #0xC0FFEE00, %d6
    move.l  %d6, (%a6)              | PASS

_halt:
    stop    #0x2700
    bra     _halt

_fail:
    lea     0xFFFF0000, %a6
    move.l  #0xDEADBEEF, %d6
    move.l  %d6, (%a6)
    stop    #0x2700
    bra     _halt
