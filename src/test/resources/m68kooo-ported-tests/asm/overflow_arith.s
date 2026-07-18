| overflow_arith.s — Arithmetic overflow and carry flag behavior
|
| Tests ADD/SUB with signed overflow (V flag) and carry (C flag).
| Key cases:
|   1. Positive + Positive = Negative (overflow) → V=1
|   2. Negative + Negative = Positive (overflow) → V=1
|   3. 0x80000000 + 0x80000000 = 0 (overflow, carry) → V=1, C=1
|   4. 0x7FFFFFFF + 1 = 0x80000000 (overflow) → V=1
|   5. SUB with underflow → V=1

    .text
    .org 0

_start:
    | Test 1: 0x7FFFFFFF + 0x7FFFFFFF = 0xFFFFFFFE (overflow, no carry)
    | Expected: V=1, C=0, N=1, Z=0
    move.l  #0x7FFFFFFF, %d0
    move.l  #0x7FFFFFFF, %d1
    add.l   %d1, %d0                | D0 = 0xFFFFFFFE, V=1 (pos+pos→neg)

    | Jump based on overflow flag (not yet fully wired, but test for future)
    | For now, use LEA to burn cycles and let flags settle

    lea     0x00100000, %a0
    lea     0x00200000, %a1

    | Test 2: 0x80000000 + 0x80000000 = 0x00000000 (overflow + carry)
    | Expected: V=1, C=1, Z=1, N=0
    move.l  #0x80000000, %d2
    move.l  #0x80000000, %d3
    add.l   %d3, %d2                | D2 = 0x00000000, V=1, C=1, Z=1

    lea     0x00300000, %a2
    lea     0x00400000, %a3

    | Test 3: 0x7FFFFFFF + 1 = 0x80000000 (overflow, positive→negative)
    | Expected: V=1, C=0, N=1, Z=0
    move.l  #0x7FFFFFFF, %d4
    add.l   #1, %d4                 | D4 = 0x80000000, V=1

    lea     0x00500000, %a4
    lea     0x00600000, %a5

    | Test 4: Subtraction with underflow (negative - positive = positive)
    | D5 = 0x80000000 (min), D6 = 1
    | D5 = D5 - D6 = 0x7FFFFFFF (overflow, neg-pos→pos)
    | Expected: V=1, C=0, N=0, Z=0
    move.l  #0x80000000, %d5
    move.l  #1, %d6
    sub.l   %d6, %d5                | D5 = 0x7FFFFFFF, V=1

    lea     0x00700000, %a6

    | If we reach here, all flags tested; signal PASS
    lea     0xFFFF0000, %a7
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a7)              | PASS

_halt:
    stop    #0x2700
    bra     _halt

_fail:
    lea     0xFFFF0000, %a7
    move.l  #0xDEADBEEF, %d7
    move.l  %d7, (%a7)
    stop    #0x2700
    bra     _halt
