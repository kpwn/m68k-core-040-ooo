| branch_conditions.s — Test all 16 Bcc conditions
|
| Condition codes (from 68040 PRM):
|   0000 BRA — always
|   0001 BSR — always (branch to subroutine, not fully wired yet)
|   0010 BHI — high (C=0, Z=0)
|   0011 BLS — low or same (C=1, Z=1)
|   0100 BCC — carry clear (C=0) / BHS — high or same
|   0101 BCS — carry set (C=1) / BLO — low
|   0110 BNE — not equal (Z=0)
|   0111 BEQ — equal (Z=1)
|   1000 BVC — overflow clear (V=0)
|   1001 BVS — overflow set (V=1)
|   1010 BPL — plus (N=0)
|   1011 BMI — minus (N=1)
|   1100 BGE — greater-or-equal ((N XOR V) = 0)
|   1101 BLT — less-than ((N XOR V) = 1)
|   1110 BGT — greater-than ((N XOR V) = 0 AND Z = 0)
|   1111 BLE — less-or-equal ((N XOR V) = 1 OR Z = 1)
|
| Strategy: set flags with CMP or TST, then test each condition.

    .text
    .org 0

_start:
    | Test BRA (always) — unconditional
    bra     _test_ne

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
    stop    #0x2700
    bra     _fail

_test_ne:
    | Test BNE (Z=0, not equal)
    | CMP D0, D1 where D0 != D1 → Z=0 → BNE taken
    move.l  #0x00000001, %d0
    move.l  #0x00000002, %d1
    cmp.l   %d0, %d1                | D1 - D0 → Z=0 (not equal)
    bne     _test_eq                | BNE taken (correct)
    bra     _fail                   | should not reach

_test_eq:
    | Test BEQ (Z=1, equal)
    | CMP D2, D2 → Z=1 → BEQ taken
    move.l  #0x12345678, %d2
    cmp.l   %d2, %d2                | D2 - D2 = 0 → Z=1
    beq     _test_pls               | BEQ taken (correct)
    bra     _fail

_test_pls:
    | Test BPL (N=0, plus/positive)
    | TST on positive value → N=0, Z=0 → BPL taken
    move.l  #0x7FFFFFFF, %d3
    tst.l   %d3                     | TST sets Z=0, N=0
    bpl     _test_mi                | BPL taken (correct)
    bra     _fail

_test_mi:
    | Test BMI (N=1, minus/negative)
    | TST on negative value → N=1 → BMI taken
    move.l  #0x80000000, %d4
    tst.l   %d4                     | TST sets N=1
    bmi     _test_vc                | BMI taken (correct)
    bra     _fail

_test_vc:
    | Test BVC (V=0, no overflow)
    | ADD two small values → no overflow → V=0 → BVC taken
    move.l  #0x00000001, %d5
    move.l  #0x00000002, %d6
    add.l   %d6, %d5                | 1 + 2 = 3, no overflow, V=0
    bvc     _test_vs                | BVC taken (correct)
    bra     _fail

_test_vs:
    | Test BVS (V=1, overflow)
    | ADD 0x7FFFFFFF + 0x7FFFFFFF → overflow → V=1 → BVS taken
    move.l  #0x7FFFFFFF, %d7
    move.l  #0x7FFFFFFF, %a0
    add.l   %a0, %d7                | overflow, V=1
    bvs     _test_cc                | BVS taken (correct)
    bra     _fail

_test_cc:
    | Test BCC (C=0, carry clear) / BHS (high or same)
    | ADD small values → no carry → C=0 → BCC taken
    move.l  #0x00000001, %d0
    move.l  #0x00000001, %d1
    add.l   %d1, %d0                | 1 + 1 = 2, C=0
    bcc     _test_cs                | BCC taken (correct)
    bra     _fail

_test_cs:
    | Test BCS (C=1, carry set) / BLO (low)
    | ADD 0xFFFFFFFF + 1 → carry → C=1 → BCS taken
    move.l  #0xFFFFFFFF, %d2
    add.l   #1, %d2                 | overflow, C=1
    bcs     _test_hi                | BCS taken (correct)
    bra     _fail

_test_hi:
    | Test BHI (high: C=0 AND Z=0)
    | CMP 0x00000002 - 0x00000001 → C=0, Z=0 → BHI taken
    move.l  #0x00000001, %d3
    move.l  #0x00000002, %d4
    cmp.l   %d3, %d4                | D4 - D3 = 1, C=0, Z=0
    bhi     _test_ls                | BHI taken (correct)
    bra     _fail

_test_ls:
    | Test BLS (low or same: C=1 OR Z=1)
    | CMP 0x00000001 - 0x00000002 → C=1 (borrow), Z=0 → BLS taken
    move.l  #0x00000002, %d5
    move.l  #0x00000001, %d6
    cmp.l   %d5, %d6                | D6 - D5 = -1, C=1 (borrow)
    bls     _test_ge                | BLS taken (C=1)
    bra     _fail

_test_ge:
    | Test BGE (>=: (N XOR V) = 0)
    | Positive - Positive with no overflow → N=0, V=0 → BGE taken
    move.l  #0x00000005, %d0
    move.l  #0x00000003, %d1
    sub.l   %d1, %d0                | 5 - 3 = 2, N=0, V=0 → (N XOR V) = 0
    bge     _test_lt                | BGE taken (correct)
    bra     _fail

_test_lt:
    | Test BLT (<: (N XOR V) = 1)
    | Negative - Positive → underflow, N=1, V=1 → (N XOR V) = 0, not taken
    | Or: Positive - Positive = negative value → N=1, V=0 → (N XOR V) = 1 → BLT taken
    move.l  #0x00000003, %d2
    move.l  #0x00000005, %d3
    sub.l   %d3, %d2                | 3 - 5 = -2, N=1, V=0 → (N XOR V) = 1
    blt     _test_gt                | BLT taken (correct)
    bra     _fail

_test_gt:
    | Test BGT (>: (N XOR V) = 0 AND Z = 0)
    | 5 - 3 = 2 (positive) → N=0, V=0, Z=0 → BGT taken
    move.l  #0x00000005, %d4
    move.l  #0x00000003, %d5
    sub.l   %d5, %d4                | 5 - 3 = 2, N=0, V=0, Z=0
    bgt     _test_le                | BGT taken (correct)
    bra     _fail

_test_le:
    | Test BLE (<=: (N XOR V) = 1 OR Z = 1)
    | 3 - 5 = -2 → N=1, V=0, Z=0 → (N XOR V) = 1 → BLE taken
    move.l  #0x00000003, %d6
    move.l  #0x00000005, %d7
    sub.l   %d7, %d6                | 3 - 5 = -2, N=1, V=0 → (N XOR V) = 1
    ble     _pass                   | BLE taken (correct)
    bra     _fail

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)              | PASS

_halt:
    stop    #0x2700
    bra     _halt
