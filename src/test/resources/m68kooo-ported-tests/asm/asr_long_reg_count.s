| asr_long_reg_count.s — ASR.L Dm,Dn with the register-count form.
|
| Stage D-3 corner: register-form shift emits src_b=Dm (not imm).  This
| verifies the V2 assembler's reg-count path wires src_b correctly and
| the ALU reads the count from src_b.  Small counts only (0..8) to
| avoid the pre-existing alu.v 5-bit shift_amt truncation (the PRM
| defines Dm mod 64 as the effective count; alu.v's handling of
| counts 32..63 is a separate pre-existing limitation tracked out of
| scope of this stage — see docs note in alu.v).
|
| Expected:
|  1. D6=1, D5=0x80000000; ASR.L D6,D5 → D5=0xC0000000, N=1, C=0, V=0.
|  2. D6=4, D5=0xFFFFFFF0; ASR.L D6,D5 → D5=0xFFFFFFFF, N=1, C=0.
|  3. D6=8, D5=0x0000FF00; ASR.L D6,D5 → D5=0x000000FF, N=0, Z=0, C=0.
|  4. D6=0, D5=0x80000000; ASR.L D6,D5 → D5=0x80000000, C=0, N=1.
|                          (Zero-count preserves X; C is cleared per PRM.)

    .text
    .org 0

_start:
    | ── 1. ASR.L D6(=1), D5(=0x80000000) → 0xC0000000 ──
    move.l  #0x00000001, %d6
    move.l  #0x80000000, %d5
    asr.l   %d6, %d5                 | D5 = 0xC0000000
    bcs     _fail                    | C=0 (LSB was 0)
    bpl     _fail                    | N=1
    beq     _fail                    | Z=0
    bvs     _fail                    | V=0 (ASR never sets V)
    move.l  #0xC0000000, %d7
    cmp.l   %d7, %d5
    bne     _fail

    | ── 2. ASR.L D6(=4), D5(=0xFFFFFFF0) → 0xFFFFFFFF ──
    move.l  #0x00000004, %d6
    move.l  #0xFFFFFFF0, %d5
    asr.l   %d6, %d5                 | D5 = 0xFFFFFFFF (sign-extend)
    bcs     _fail                    | C=0 (bit 3 was 0, last out)
    bpl     _fail                    | N=1
    beq     _fail                    | Z=0
    move.l  #0xFFFFFFFF, %d7
    cmp.l   %d7, %d5
    bne     _fail

    | ── 3. ASR.L D6(=8), D5(=0x0000FF00) → 0x000000FF ──
    move.l  #0x00000008, %d6
    move.l  #0x0000FF00, %d5
    asr.l   %d6, %d5                 | D5 = 0x000000FF
    bcs     _fail                    | C=0 (bit 7 was 0)
    bmi     _fail                    | N=0
    beq     _fail                    | Z=0
    move.l  #0x000000FF, %d7
    cmp.l   %d7, %d5
    bne     _fail

    | ── 4. ASR.L D6(=0), D5(=0x80000000) → 0x80000000, C=0 ──
    move.l  #0x00000000, %d6
    move.l  #0x80000000, %d5
    asr.l   %d6, %d5                 | Zero-count: C cleared, X preserved,
                                    | N from result, Z from result.
    bcs     _fail                    | C=0 per PRM zero-count rule
    bpl     _fail                    | N=1
    beq     _fail                    | Z=0
    move.l  #0x80000000, %d7
    cmp.l   %d7, %d5
    bne     _fail

    | Pass sentinel.
    move.l  #0xC0FFEE00, %d0
    move.l  #0xFFFF0000, %a0
    move.l  %d0, (%a0)
    bra     .

_fail:
    move.l  #0xDEADBEEF, %d0
    move.l  #0xFFFF0000, %a0
    move.l  %d0, (%a0)
    bra     .
