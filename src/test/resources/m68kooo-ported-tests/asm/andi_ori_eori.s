| andi_ori_eori.s — ANDI.L / ORI.L / EORI.L correctness + CCR
|
| Hypothesis: each immediate logical op updates N = result[31] and
| Z = (result == 0); it clears V and C; and leaves X unchanged.
|
| Expected:
|  1. D0 = 0xFF00FF00 ANDI #0x0F0F0F0F → 0x0F000F00, N=0, Z=0, V=C=0
|  2. D1 = 0x00000000 ANDI #0xFFFFFFFF → 0x00000000, Z=1
|  3. D2 = 0x12345678 ORI  #0x00FF0000 → 0x12FF5678, N=0, Z=0
|  4. D3 = 0x00000000 ORI  #0x00000000 → 0x00000000, Z=1
|  5. D4 = 0xFFFFFFFF EORI #0xFFFFFFFF → 0x00000000, Z=1
|  6. D5 = 0x12345678 EORI #0xFFFFFFFF → 0xEDCBA987, N=1, Z=0
|
| IMPORTANT: CMP / TST / MOVE.L #imm all overwrite CCR.  So we check
| flags IMMEDIATELY after each logical op (no intervening CCR-writer),
| and only after the flag check do we validate the result value with
| CMP.L.  V and C are always cleared by these ops, so checking them on
| the first case is sufficient (they're repeated logic for each).

    .text
    .org 0

_start:
    | ── 1. ANDI.L nonzero positive: check N=0, Z=0, V=C=0 immediately ──
    move.l  #0xFF00FF00, %d0
    andi.l  #0x0F0F0F0F, %d0         | D0 = 0x0F000F00, N=0, Z=0, V=C=0
    beq     _fail                    | Z=0 → BEQ not taken
    bmi     _fail                    | N=0 → BMI not taken
    bvs     _fail                    | V=0 → BVS not taken
    bcs     _fail                    | C=0 → BCS not taken
    | Now safe to CMP for value
    move.l  #0x0F000F00, %d7
    cmp.l   %d7, %d0
    bne     _fail

    | ── 2. ANDI.L to zero: Z=1 ──
    move.l  #0x00000000, %d1
    andi.l  #0xFFFFFFFF, %d1         | D1 = 0, Z=1, N=0
    bne     _fail                    | Z=1 → BNE not taken
    bmi     _fail                    | N=0

    | ── 3. ORI.L sets bits ──
    move.l  #0x12345678, %d2
    ori.l   #0x00FF0000, %d2         | D2 = 0x12FF5678
    beq     _fail                    | nonzero
    bmi     _fail                    | bit31 clear
    move.l  #0x12FF5678, %d7
    cmp.l   %d7, %d2
    bne     _fail

    | ── 4. ORI.L of zero on zero stays zero → Z=1 ──
    move.l  #0x00000000, %d3
    ori.l   #0x00000000, %d3
    bne     _fail                    | Z=1 → BNE not taken

    | ── 5. EORI.L all-ones → zero, Z=1 ──
    move.l  #0xFFFFFFFF, %d4
    eori.l  #0xFFFFFFFF, %d4
    bne     _fail                    | Z=1

    | ── 6. EORI.L flips all bits → 0xEDCBA987, N=1 ──
    move.l  #0x12345678, %d5
    eori.l  #0xFFFFFFFF, %d5
    bpl     _fail                    | N=1 → BPL not taken
    beq     _fail                    | Z=0 → BEQ not taken
    move.l  #0xEDCBA987, %d7
    cmp.l   %d7, %d5
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
