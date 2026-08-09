| move_ccr_to_dn.s — MOVE.W CCR,Dn (opword 0x42C0 | Dn)
|
| PRM §4.135: MOVE from CCR.  68010+, UNPRIVILEGED.  The condition code
| register is moved to the destination as a WORD; only the low byte
| holds the condition codes and the upper byte reads as ZERO.  A data
| register destination is word-sized, so bits [31:16] of Dn are
| preserved.
|
| REGRESSION CONTEXT: this opcode had no decode row at all.  Every
| encoding of 0x42C0..0x42FF fell through to ILLEGAL and took vector 4;
| with no vec-4 handler installed that is an exception storm, not a
| quiet wrong answer.  Verified against Musashi: with D1 = 0xFFFFFFFF
| and Z set, `move.w %ccr,%d1` yields 0xFFFF0004.
|
| Every expected value below was cross-checked against Musashi v4.60 in
| 68040 mode (states matched exactly).
|
| NOTE ON ORDERING: almost every instruction writes CCR, so each
| `move.w #imm,%ccr` must sit IMMEDIATELY before the `move.w %ccr,Dn`
| that reads it, and the result must be stashed in a register before any
| cmp.l (which clobbers CCR).

    .text
    .org 0

_start:
    lea     0x00010000, %a7

    | ── Case 1: all five flags set; upper word of Dn preserved ──────
    | CCR = 0x1F (X,N,Z,V,C).  D0 = 0xFFFFFFFF beforehand, so a correct
    | word-sized write leaves 0xFFFF001F.  A LONG-sized write would give
    | 0x0000001F and a byte-sized one 0xFFFFFF1F — both caught here.
    move.l  #0xFFFFFFFF, %d0
    move.w  #0x001F, %ccr
    move.w  %ccr, %d0
    move.l  %d0, %d7               | stash before cmp clobbers CCR
    cmp.l   #0xFFFF001F, %d7
    bne     _fail

    | ── Case 2: all flags clear; upper word still preserved ─────────
    | This is the case that proves the upper BYTE of the written word is
    | zero rather than stale: D1[15:8] was 0x56 and must become 0x00.
    move.l  #0x12345678, %d1
    move.w  #0x0000, %ccr
    move.w  %ccr, %d1
    move.l  %d1, %d7
    cmp.l   #0x12340000, %d7
    bne     _fail

    | ── Case 3: unimplemented CCR bits [7:5] read back as zero ──────
    | Writing 0xFFFF to CCR only latches bits [4:0]; the read must
    | return 0x001F, not 0x00FF or 0xFFFF.
    move.l  #0xDEADBEEF, %d2
    move.w  #0xFFFF, %ccr
    move.w  %ccr, %d2
    move.l  %d2, %d7
    cmp.l   #0xDEAD001F, %d7
    bne     _fail

    | ── Case 4: a mixed flag pattern, and D3 upper word = 0x0000 ────
    move.l  #0x0000FFFF, %d3
    move.w  #0x000A, %ccr
    move.w  %ccr, %d3
    move.l  %d3, %d7
    cmp.l   #0x0000000A, %d7
    bne     _fail

    | ── Case 5: reading CCR must not itself disturb CCR ─────────────
    | Two back-to-back reads of the same CCR value must agree.
    move.l  #0x00000000, %d4
    move.l  #0x00000000, %d5
    move.w  #0x0011, %ccr
    move.w  %ccr, %d4
    move.w  %ccr, %d5
    cmp.l   %d4, %d5
    bne     _fail
    cmp.l   #0x00000011, %d5
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
