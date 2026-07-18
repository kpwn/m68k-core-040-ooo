| movec_usp_vbr_cacr_roundtrip.s — directed coverage for the MOVEC
| compact-CR table (fix/movec-usp-wedge).
|
| CR_USP (PRM code 0x800) was missing from the decode/commit CR-select
| tables, so `movec %a0,%usp` raised a bogus vec-4 ILLEGAL and wedged.
| This test covers BOTH directions for the fixed register plus two
| neighbours that were already working (VBR 0x801, CACR 0x002) so a
| future table regression on any of the three trips a FAIL sentinel,
| not a timeout:
|   1. movec Rn,USP  then  movec USP,Rm   — round-trip through USP
|   2. cross-check: movec USP,Rm must match what MOVE USP,An reads
|      (the 0x4E68 opcode) — both must observe the same register
|   3. movec Rn,VBR  then  movec VBR,Rm   — round-trip (restore 0 after)
|   4. movec Rn,CACR then  movec CACR,Rm  — round-trip
| PASS: 0xC0FFEE00 at 0xFFFF0000.  Any mismatch writes a distinct
| 0xBADxxxxx code to the sentinel (FAIL).
    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000

_start:
    move.w  #0x2700, %sr
    move.l  #0x000FE000, %sp
    lea     PASS_SENT, %a6

    | ── 1. USP round-trip via MOVEC both directions ────────────────
    move.l  #0x000F8000, %a0
    movec   %a0, %usp
    movec   %usp, %a1
    cmpa.l  %a0, %a1
    bne     _fail_usp_movec

    | ── 2. MOVEC USP,Rn vs MOVE USP,An must agree ──────────────────
    move    %usp, %a2              | classic 0x4E68 form
    cmpa.l  %a0, %a2
    bne     _fail_usp_cross

    | and the reverse cross: write via classic MOVE, read via MOVEC
    move.l  #0x000F9000, %a3
    move    %a3, %usp              | classic 0x4E60 form
    movec   %usp, %d0
    cmp.l   %a3, %d0
    bne     _fail_usp_cross2

    | ── 3. VBR round-trip ──────────────────────────────────────────
    move.l  #0x00012340, %d1
    movec   %d1, %vbr
    movec   %vbr, %d2
    cmp.l   %d1, %d2
    bne     _fail_vbr
    moveq   #0, %d1
    movec   %d1, %vbr              | restore VBR=0 (don't skew later exc)

    | ── 4. CACR round-trip ─────────────────────────────────────────
    | Keep DE/IE clear so cache-enable state is unchanged: use only
    | harmless low bits for the pattern, then restore 0.
    move.l  #0x00000000, %d3
    movec   %d3, %cacr
    movec   %cacr, %d4
    cmp.l   %d3, %d4
    bne     _fail_cacr

    | ── PASS ────────────────────────────────────────────────────────
    move.l  #0xC0FFEE00, (%a6)
    bra     .

_fail_usp_movec:
    move.l  #0xBAD00001, (%a6)
    bra     .
_fail_usp_cross:
    move.l  #0xBAD00002, (%a6)
    bra     .
_fail_usp_cross2:
    move.l  #0xBAD00003, (%a6)
    bra     .
_fail_vbr:
    move.l  #0xBAD00004, (%a6)
    bra     .
_fail_cacr:
    move.l  #0xBAD00005, (%a6)
    bra     .
