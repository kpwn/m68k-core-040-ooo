| shift_rotate.s — Barrel shifter: ASL, ASR, LSL, LSR, ROL, ROR
|
| Tests all shift/rotate instructions with various shift counts.
| Key cases:
|   1. ASL (arithmetic left shift) — left bits disappear, right 0-fill
|   2. ASR (arithmetic right shift) — right bits disappear, left sign-extend
|   3. LSL (logical left shift) — left bits disappear, right 0-fill
|   4. LSR (logical right shift) — right bits disappear, left 0-fill
|   5. ROL (rotate left) — bits wrap around, no carry flag
|   6. ROR (rotate right) — bits wrap around, no carry flag

    .text
    .org 0

_start:
    | Test 1: ASL (arithmetic left shift)
    | 0x00000001 << 1 = 0x00000002, N=0, Z=0, C=0
    move.l  #0x00000001, %d0
    asl.l   #1, %d0                 | D0 = 0x00000002

    lea     0x00100000, %a0

    | Test 2: ASL with overflow (C flag set when bit shifts out)
    | 0x40000000 << 2 = 0x00000000 (bits shift out to C, V flag set)
    move.l  #0x40000000, %d1
    asl.l   #2, %d1                 | D1 = 0x00000000

    lea     0x00200000, %a1

    | Test 3: ASR (arithmetic right shift with sign extension)
    | 0x80000001 >> 1 = 0xC0000000 (sign bit replicated)
    move.l  #0x80000001, %d2
    asr.l   #1, %d2                 | D2 = 0xC0000000

    lea     0x00300000, %a2

    | Test 4: LSL (logical left shift)
    | 0x80000000 << 1 = 0x00000000 (no sign extend, just 0-fill right)
    move.l  #0x80000000, %d3
    lsl.l   #1, %d3                 | D3 = 0x00000000

    lea     0x00400000, %a3

    | Test 5: LSR (logical right shift, 0-fill left)
    | 0x80000000 >> 1 = 0x40000000 (0-fill, not sign-extend)
    move.l  #0x80000000, %d4
    lsr.l   #1, %d4                 | D4 = 0x40000000

    lea     0x00500000, %a4

    | Test 6: ROL (rotate left)
    | 0x80000001 << 1 (rotated) = 0x00000003 (high bit wraps to low)
    move.l  #0x80000001, %d5
    rol.l   #1, %d5                 | D5 = 0x00000003

    lea     0x00600000, %a5

    | Test 7: ROR (rotate right)
    | 0x00000001 >> 1 (rotated) = 0x80000000 (low bit wraps to high)
    move.l  #0x00000001, %d6
    ror.l   #1, %d6                 | D6 = 0x80000000

    lea     0x00700000, %a6

    | If we reach here, all shifts tested; signal PASS
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
