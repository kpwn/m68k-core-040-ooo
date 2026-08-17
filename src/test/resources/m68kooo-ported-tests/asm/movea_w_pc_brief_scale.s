| movea_w_pc_brief_scale.s — brief-format index SCALE must be honoured,
| for both (d8,An,Xn.W*s) and (d8,PC,Xn.W*s).
|
| ORIGINAL MOTIVATION (2026-05-21): the Q700 ROM uses
| MOVEA.W (d8,PC,D0.W*2),A0 at 0x40809BCE in the VBL IRQ handler dispatch,
| and an HW investigation suspected our impl might not honour the scale field
| for brief-format PC-indexed addressing.
|
| STATUS: that suspicion is REFUTED.  Checked against Musashi 2026-07-28 --
| scale 1/2/4 match the golden model exactly for BOTH the An-base and PC-base
| forms.  This test now locks that in rather than chasing a phantom.
|
| WHY IT WAS REWRITTEN: the previous version of this file DID NOT ASSEMBLE
| ("attempt to move .org backwards") and had therefore been reported as [SKIP]
| by every `make test` run -- contributing ZERO coverage while looking
| harmless in the summary.  It was an unfinished draft; its own comments read
| "Hmm but _movea position depends on prior insns" and "Actually scrap this
| approach", and a leftover `.org 64` collided with the code already emitted.
| No coverage is lost by this rewrite: there was none to lose.  The .org games
| are gone -- the table is reached through labels instead.
|
| Table layout (byte offsets from `tbl`):
|     0: 11 22 33 44 55 66     6: BE EF     8: 77 88 99 AA     12: CA FE
| With D0=3:  scale*2 -> offset  6 -> 0xBEEF
|             scale*4 -> offset 12 -> 0xCAFE
| MOVEA.W SIGN-EXTENDS its word source into the full 32-bit An, which is why
| the expected values are 0xFFFFBEEF / 0xFFFFCAFE -- documented instruction
| behaviour, not a quirk of the table.
|
| PASS sentinel: 0xC0FFEE00
| FAIL sentinels:
|   0xDEAD5C01 — (An,Dn.W*2) scale not honoured
|   0xDEAD5C02 — (An,Dn.W*4) scale not honoured
|   0xDEAD5C03 — (PC,Dn.W*2) scale not honoured
|   0xDEAD5C04 — (PC,Dn.W*4) scale not honoured

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000

_start:
    lea     0x00010000, %a7

    | ── An base, scale = 2 ───────────────────────────────────────────
    lea     tbl, %a2
    moveq   #3, %d0
    movea.w (0,%a2,%d0.w*2), %a1
    cmpa.l  #0xFFFFBEEF, %a1
    bne     fail_an2

    | ── An base, scale = 4 ───────────────────────────────────────────
    movea.w (0,%a2,%d0.w*4), %a3
    cmpa.l  #0xFFFFCAFE, %a3
    bne     fail_an4

    | ── PC base, scale = 2 (the form the Q700 ROM actually uses) ─────
    movea.w tbl(%pc,%d0.w*2), %a4
    cmpa.l  #0xFFFFBEEF, %a4
    bne     fail_pc2

    | ── PC base, scale = 4 ───────────────────────────────────────────
    movea.w tbl(%pc,%d0.w*4), %a5
    cmpa.l  #0xFFFFCAFE, %a5
    bne     fail_pc4

    move.l  #0xC0FFEE00, %d1
    move.l  %d1, PASS_SENT
    bra     .

fail_an2:
    move.l  #0xDEAD5C01, %d1
    move.l  %d1, PASS_SENT
    bra     .
fail_an4:
    move.l  #0xDEAD5C02, %d1
    move.l  %d1, PASS_SENT
    bra     .
fail_pc2:
    move.l  #0xDEAD5C03, %d1
    move.l  %d1, PASS_SENT
    bra     .
fail_pc4:
    move.l  #0xDEAD5C04, %d1
    move.l  %d1, PASS_SENT
    bra     .

    .align 2
tbl:
    .byte   0x11,0x22,0x33,0x44,0x55,0x66
    .short  0xBEEF
    .byte   0x77,0x88,0x99,0xAA
    .short  0xCAFE
