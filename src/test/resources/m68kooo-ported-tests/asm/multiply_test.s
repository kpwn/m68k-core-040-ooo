| multiply_test.s — MULS and MULU (multiply signed/unsigned)
|
| NOTE: MULS and MULU are NOT yet implemented in decode.v.
| This test is a placeholder for when they are.
|
| Tests:
|   1. MULS.L — signed 32×32 → 64-bit multiply (result in D0:D1)
|   2. MULU.L — unsigned 32×32 → 64-bit multiply (result in D0:D1)
|
| These instructions produce a 64-bit result split across two registers.

    .text
    .org 0

_start:
    | Test 1: MULS.L — signed multiply
    | D0 = 0x00000005 (+5)
    | D1 = 0x00000003 (+3)
    | MULS.L D1, D0 → D0 = 0x0000000F (low 32 bits of 15)
    | (high 32 bits in DR, typically ignored for small products)
    move.l  #0x00000005, %d0
    move.l  #0x00000003, %d1
    muls.l  %d1, %d0                | D0 = 0x0000000F (5 * 3 = 15)

    lea     0x00100000, %a0

    | Test 2: MULU.L — unsigned multiply
    | D2 = 0x00000100
    | D3 = 0x00000010
    | MULU.L D3, D2 → D2 = 0x00001000 (256 * 16 = 4096)
    move.l  #0x00000100, %d2
    move.l  #0x00000010, %d3
    mulu.l  %d3, %d2                | D2 = 0x00001000

    lea     0x00200000, %a1

    | Test 3: MULS with negative operand
    | D4 = 0xFFFFFFFB (-5)
    | D5 = 0x00000003 (+3)
    | MULS.L D5, D4 → D4 = 0xFFFFFFF1 (-15, sign-extended)
    move.l  #0xFFFFFFFB, %d4        | -5
    move.l  #0x00000003, %d5
    muls.l  %d5, %d4                | D4 = 0xFFFFFFF1 (-15)

    lea     0x00300000, %a2

    | Test 4: MULU with zero
    | D6 = 0x00000000
    | D7 = 0xFFFFFFFF
    | MULU.L D7, D6 → D6 = 0x00000000 (0 * anything = 0)
    move.l  #0x00000000, %d6
    move.l  #0xFFFFFFFF, %d7
    mulu.l  %d7, %d6                | D6 = 0x00000000

    lea     0x00400000, %a3

    | Test 5: MULS with two negative operands
    | D0 = 0xFFFFFFFB (-5)
    | D1 = 0xFFFFFFFD (-3)
    | MULS.L D1, D0 → D0 = 0x0000000F (+15, positive result)
    move.l  #0xFFFFFFFB, %d0        | -5
    move.l  #0xFFFFFFFD, %d1        | -3
    muls.l  %d1, %d0                | D0 = 0x0000000F (+15)

    lea     0x00500000, %a4

    | All multiply tests done; signal PASS
    lea     0xFFFF0000, %a5
    move.l  #0xC0FFEE00, %d2
    move.l  %d2, (%a5)              | PASS

_halt:
    stop    #0x2700
    bra     _halt

_fail:
    lea     0xFFFF0000, %a5
    move.l  #0xDEADBEEF, %d2
    move.l  %d2, (%a5)
    stop    #0x2700
    bra     _halt
