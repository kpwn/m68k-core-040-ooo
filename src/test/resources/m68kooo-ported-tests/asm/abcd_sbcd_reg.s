| abcd_sbcd_reg.s — ABCD / SBCD reg-reg forms
|
| Verifies 8-bit BCD add/sub with X-flag carry-in / carry-out.
|
|   ABCD: Dx[7:0] = (Dx + Dy + X) with nibble adjust; X=C=carry-out.
|   SBCD: Dx[7:0] = (Dx - Dy - X) with nibble adjust; X=C=borrow-out.
|
| Z is STICKY per PRM: new Z = old_Z AND (result == 0).  We set/clear
| old_Z deliberately so the result Z reflects the expected behaviour.
|
| Upper 24 bits of the destination Dx are preserved; only the low byte
| participates in the BCD op.  We verify that too (marker nibble in
| bits 31..8 stays intact).

    .text
    .org 0

_start:
    | ── Seed: clear X by doing an ADD.L that does NOT carry ──
    moveq   #0, %d0
    add.l   %d0, %d0                  | X=0, Z=1 after this

    | ── ABCD 0x12 + 0x34 with X=0 → 0x46.  No wrap. ──
    | Seed Dx = 0xABCD_FE12 (marker upper 24), Dy = 0x00000034.
    move.l  #0xABCDFE12, %d0
    move.l  #0x00000034, %d1
    | Clear X.  We already cleared it above via add.l %d0,%d0 where D0 was 0.
    | But the moveqs/moves above may have modified NZ but never X — OK.
    abcd    %d1, %d0                  | D0[7:0] = 12+34+0 = 46
    | Expect D0 = 0xABCDFE46 (upper preserved).
    move.l  #0xABCDFE46, %d7
    cmp.l   %d7, %d0
    bne     _fail

    | ── ABCD 0x99 + 0x01 with X=0 → 0x00, X=C=1 (wrap) ──
    move.l  #0x11111199, %d0
    move.l  #0x00000001, %d1
    | Clear X first (ABCD reads X).
    moveq   #0, %d2
    add.l   %d2, %d2                  | X=0
    abcd    %d1, %d0                  | D0[7:0] = 0x99+0x01 = 0x00, X=1
    move.l  #0x11111100, %d7
    cmp.l   %d7, %d0
    bne     _fail
    | Verify X=1 by chaining a second ABCD with both operands 0 → 0+0+1=1.
    move.l  #0x22222200, %d3
    move.l  #0x00000000, %d4
    abcd    %d4, %d3                  | 0+0+1 = 01; X=0 after (no wrap)
    move.l  #0x22222201, %d7
    cmp.l   %d7, %d3
    bne     _fail

    | ── ABCD 0x50 + 0x50 + X=0 → 0x00, X=C=1 (exact 100 wrap) ──
    move.l  #0x33333350, %d0
    move.l  #0x00000050, %d1
    moveq   #0, %d2
    add.l   %d2, %d2                  | X=0
    abcd    %d1, %d0                  | 50+50+0 = 0x00, X=C=1
    | Verify C=1 BEFORE doing CMP (which would clobber).  Use BCS to
    | check carry-set.
    bcc     _fail                     | expect C=1 → BCC should NOT take
    | Now verify the value.
    move.l  #0x33333300, %d7
    cmp.l   %d7, %d0
    bne     _fail

    | ── SBCD 0x50 - 0x25 with X=0 → 0x25 ──
    move.l  #0x44444450, %d0
    move.l  #0x00000025, %d1
    moveq   #0, %d2
    add.l   %d2, %d2                  | X=0
    sbcd    %d1, %d0                  | 50-25-0 = 25, C=0
    bcs     _fail                     | expect C=0
    move.l  #0x44444425, %d7
    cmp.l   %d7, %d0
    bne     _fail

    | ── SBCD 0x20 - 0x30 with X=0 → 0x90, X=C=1 (borrow) ──
    move.l  #0x55555520, %d0
    move.l  #0x00000030, %d1
    moveq   #0, %d2
    add.l   %d2, %d2                  | X=0
    sbcd    %d1, %d0                  | 20-30-0 = 0x90 (wrap), X=C=1
    bcc     _fail                     | expect C=1
    move.l  #0x55555590, %d7
    cmp.l   %d7, %d0
    bne     _fail

    | ── SBCD 0x20 - 0x20 with X=1 → 0x99, X=1 (borrow via X) ──
    | First set X=1 via a wrapping add.
    move.l  #0xFFFFFFFF, %d5
    move.l  #0x00000001, %d6
    add.l   %d6, %d5                  | D5 = 0, X=C=1
    | Now do the SBCD that depends on X.
    move.l  #0x66666620, %d0
    move.l  #0x00000020, %d1
    sbcd    %d1, %d0                  | 20-20-1 = -1 → 0x99, X=C=1
    bcc     _fail                     | expect C=1 (borrow)
    move.l  #0x66666699, %d7
    cmp.l   %d7, %d0
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
