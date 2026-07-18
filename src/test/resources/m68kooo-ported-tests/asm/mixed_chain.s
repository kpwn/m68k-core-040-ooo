| mixed_chain.s — Mixed arithmetic chains with conditional branches
|
| Tests that flag-setting instructions (ADD, SUB, AND, OR) interact
| correctly with subsequent Bcc instructions in a realistic sequence.
| This catches any CCR stall bugs in complex instruction sequences.
|
| Chains tested:
|   ADD + BMI (result negative → branch)
|   SUB + BEQ (result zero → branch)
|   AND + BEQ (result zero → branch)
|   OR  + BNE (result non-zero → branch)
|   CMP + BGT (signed greater-than)
|   Multiple-op chain: ADD, SUB, CMP, BNE
|
| PASS sentinel: 0xC0FFEE00 → 0xFFFF0000

    .text
    .org 0

_start:
    | ── ADD.L: adding two positives to get negative (overflow) → BMI ──
    move.l  #0x60000000, %d0
    move.l  #0x60000000, %d1
    add.l   %d1, %d0            | 0xC0000000, N=1 (MSB set), V=1
    bpl     _fail               | N=1 → BMI should be taken

    | ── SUB.L: value equals itself → BEQ ──
    move.l  #0x12345678, %d0
    move.l  #0x12345678, %d1
    sub.l   %d1, %d0            | D0 = 0, Z=1
    bne     _fail               | Z=1, BEQ must be taken (BNE must not)

    | ── AND.L: mask clears all → BEQ ──
    move.l  #0xF0F0F0F0, %d0
    move.l  #0x0F0F0F0F, %d1
    and.l   %d1, %d0            | 0xF0 & 0x0F = 0 for each byte, Z=1
    bne     _fail               | Z=1

    | ── OR.L: result non-zero → BNE ──
    moveq   #0, %d0
    moveq   #1, %d1
    or.l    %d1, %d0            | D0 = 1, Z=0
    beq     _fail               | Z=0, BNE must be taken

    | ── CMP.L + BGT (signed greater-than) ──
    moveq   #10, %d0
    moveq   #3, %d1
    cmp.l   %d1, %d0            | 10-3=7, N=0, Z=0, V=0 → GT condition true
    ble     _fail               | 10 > 3, so BLE must NOT branch
    bgt     _bgt_ok             | BGT must branch
    bra     _fail
_bgt_ok:

    | ── Multi-op chain: ADD then SUB then CMP ──
    moveq   #5, %d0
    addq.l  #3, %d0             | D0 = 8
    subq.l  #2, %d0             | D0 = 6
    moveq   #6, %d1
    cmp.l   %d1, %d0            | 6-6=0, Z=1
    bne     _fail               | Z=1 after chain

    | ── PASS ──────────────────────────────────────────────────────────────
_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)          | PASS sentinel
_halt:
    stop    #0x2700
    bra     _halt

    | ── FAIL ──────────────────────────────────────────────────────────────
_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)          | FAIL sentinel
_halt_fail:
    stop    #0x2700
    bra     _halt_fail
