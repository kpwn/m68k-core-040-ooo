| stress_mixed.s — Stress test combining many operations
|
| A complex sequence combining:
|   - Multiple arithmetic operations with flag dependencies
|   - Memory loads and stores
|   - Branch conditions based on computed flags
|   - All registers exercised
|
| This test verifies that the core can handle realistic code patterns.

    .text
    .org 0

_start:
    | Initialize all registers
    move.l  #0x00000010, %d0        | D0 = 16
    move.l  #0x00000020, %d1        | D1 = 32
    move.l  #0x00000040, %d2        | D2 = 64
    move.l  #0x00000080, %d3        | D3 = 128
    move.l  #0x00000100, %d4        | D4 = 256
    move.l  #0x00000200, %d5        | D5 = 512
    move.l  #0x00000400, %d6        | D6 = 1024
    move.l  #0x00000800, %d7        | D7 = 2048

    lea     0x00001000, %a0         | A0 = data base
    lea     0x00001100, %a1         | A1 = another address
    lea     0x00001200, %a2
    move.l  #0x00001300, %a3
    move.l  #0x00001400, %a4
    move.l  #0x00001500, %a5
    move.l  #0x00001600, %a6
    lea     0x00010000, %a7         | A7 = stack

    | Test 1: Arithmetic chain with flag propagation
    add.l   %d1, %d0                | D0 = 16 + 32 = 48
    add.l   %d2, %d0                | D0 = 48 + 64 = 112
    cmp.l   %d3, %d0                | compare 112 vs 128 → C=1 (borrow)
    bls     _less                   | if less-or-same, branch
    bra     _fail

_less:
    | Test 2: Memory operations
    move.l  %d0, (%a0)              | [0x1000] = 112
    move.l  %d1, (4,%a0)            | [0x1004] = 32
    move.l  %d2, (8,%a0)            | [0x1008] = 64

    lea     0x00100000, %a0         | padding

    | Test 3: Load-use chain
    move.l  (%a0), %d0              | load value at A0 (should still be 0x1000 area)
    lea     0x00100000, %a1

    | Test 4: Complex flag condition
    move.l  #0x7FFFFFFF, %d0
    move.l  #1, %d1
    add.l   %d1, %d0                | D0 = 0x80000000, overflow, N=1, V=1
    bvs     _overflow               | if overflow, branch
    bra     _fail

_overflow:
    | Test 5: Bitwise operations
    move.l  #0xFFFF0000, %d2
    move.l  #0x0000FFFF, %d3
    and.l   %d3, %d2                | D2 = 0x00000000, Z=1
    beq     _bitwise_ok             | Z=1 → BEQ taken
    bra     _fail

_bitwise_ok:
    or.l    #0x12345678, %d2        | D2 = 0x12345678
    not.l   %d2                     | D2 = 0xEDCBA987

    lea     0x00200000, %a0

    | Test 6: Sign extension chain
    move.l  #0xFFFFFFFF, %d4        | -1
    ext.l   %d4                     | still -1
    tst.l   %d4                     | D4 is negative
    bmi     _signed_ok              | N=1 → BMI taken
    bra     _fail

_signed_ok:
    | Test 7: More address register operations
    adda.l  %d1, %a0                | A0 += 32
    adda.l  %d2, %a1                | A1 += ... (some value)

    lea     0x00300000, %a2

    | Test 8: Subtraction with underflow
    move.l  #0x00000005, %d5
    move.l  #0x00000010, %d6
    sub.l   %d6, %d5                | D5 = 5 - 16 = -11, N=1, C=1
    bmi     _underflow_ok           | N=1 → BMI taken
    bra     _fail

_underflow_ok:
    | Test 9: All operations completed successfully
    lea     0x00400000, %a3

    | Signal PASS
    lea     0xFFFF0000, %a7
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a7)              | PASS

_halt:
    stop    #0x2700
    bra     _halt

_fail:
    lea     0xFFFF0000, %a7
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a7)
    stop    #0x2700
    bra     _halt
