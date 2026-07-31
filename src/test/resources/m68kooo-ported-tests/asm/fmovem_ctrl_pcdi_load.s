| fmovem_ctrl_pcdi_load.s — FMOVEM.L (d16,PC),<ctrl regs> must DECODE.
|
| THE BUG THIS LOCKS IN (task #140 session, 2026-07-28)
| ------------------------------------------------------------------------
| decode_1111.vh's FMOVEM control-register block accepted (An), (An)+,
| -(An), (d16,An), (xxx).W and (xxx).L -- but NOT (d16,PC).  The Q700 ROM
| contains, at 0x408ED416:
|
|     F23A 9800 0006     fmovem.l (0x408ED420,PC),FPCR/FPSR
|
| and that instruction lives INSIDE the ROM's own F-line / FP support path.
| Failing to decode it meant the F-line handler re-executed the very
| instruction that had trapped -- an unbreakable self-recursion.  Measured on
| real hardware at ~24,000 exceptions/second, with exc-ring showing 24 of 24
| captured entries as vec=0x0B at that single PC.  The machine was alive
| (level-2 IRQs still dispatching) but doing nothing else.
|
| This is the SAME failure mode as the earlier FMOVEM.L ...,-(An) gap that
| caused the no-text/no-icons bug.  That pass added the An-relative modes and
| missed the PC-relative ones, which is why this test exercises the exact ROM
| encoding rather than a convenient one.
|
| WHY THE ENCODING IS HAND-ASSEMBLED: the point is to reproduce the ROM's
| bytes exactly (F23A 9800 <disp16>), not whatever the assembler picks for a
| mnemonic.  The displacement is computed by the assembler from labels so the
| (d16,PC) base is right by construction: base = address of the disp16 word
| = insn + 4 (past the opword AND the FPU ext1 opmode word), NOT insn + 2.
| Getting that +4 wrong yields a plausible-but-wrong address 2 bytes off,
| which is exactly the kind of error a self-consistent test can hide -- so
| the loaded values below are chosen to be distinctive.
|
| STILL NOT SUPPORTED after this fix, deliberately (see FAIL note below):
|   (d8,PC,Xn), (d8,An,Xn) and #imm forms of FMOVEM ctrl.  Those need the
|   3-phase index crack interleaved with the control-register list walk,
|   which is a bigger change than this one; they continue to trap.  This
|   file does NOT claim the EA matrix is complete.
|
| PASS sentinel: 0xC0FFEE00
| FAIL sentinels:
|   0xDEADFC01 — FPCR wrong after FMOVEM.L (d16,PC),FPCR/FPSR
|   0xDEADFC02 — FPSR low half wrong after the same
|   0xDEADFC03 — FPCR wrong after the 3-register (FPCR/FPSR/FPIAR) form
|   0xDEADFC04 — FPIAR wrong after the 3-register form
|   0xDEADFC05 — FPCR clobbered by the FPIAR-only form (list mask ignored)
|   0xDEADFC00 — the instruction TRAPPED F-line: decode gap not fixed
|   0xDEADFC06 — CONTROL case failed: (d16,An) multi-reg ctrl move is ALSO
|                broken, so the bug is not specific to PC-relative
| A HANG/timeout instead of any sentinel means the instruction still traps
| F-line — i.e. the decode gap is not fixed at all.

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000

_start:
    lea     0x00010000, %a7

    | Install an F-line (vector 11, offset 11*4 = 0x2C) handler so a decode
    | gap reports 0xDEADFC00 instead of hanging.  Without this the failure
    | mode is an indistinguishable TIMEOUT: "still traps" and "decodes but
    | the crack never completes" look identical, and they need different
    | fixes.
    move.l  #fline_handler, 0x2C

    | ── Prime FPCR/FPSR/FPIAR with values the loads must overwrite ───────
    move.l  #0x000000F0, %d0
    fmove.l %d0, %fpcr
    move.l  #0x0000FFFF, %d0
    fmove.l %d0, %fpsr
    | NOTE: FPIAR is deliberately NOT primed via `fmove.l %d0,%fpiar`.
    | That form (op F200, ext1 0x8400 = Dn-direct, mask=FPIAR) is ITSELF
    | undecoded -- decode_1111.vh's Dn-direct control-register branch accepts
    | only mask 100 (FPCR) and 010 (FPSR), never 001 (FPIAR) -- so priming
    | with it traps F-line and masks the result of the case under test.
    | Found via the CORE_DEBUG [FLINE] trace: it was the ONLY opword reaching
    | the F-line fallback, which is also what proved the (d16,PC) rows below
    | now decode correctly.  Separate gap, tracked separately.

    | ── Case 0 (CONTROL): same multi-register ctrl block, but via (d16,An),
    | ── a mode that was already supported.  This isolates "the ctrl block
    | ── works and only my PC-relative addition is broken" from "the
    | ── multi-register ctrl block never worked here at all".  Failing this
    | ── means the bug is NOT where the (d16,PC) evidence points.
    | ── F228 = opword mode 101 (d16,An), reg 0 -> A0; disp 0.
    lea     tbl2, %a0
i0:
    .short  0xF228, 0x9800
    .short  0

    fmove.l %fpcr, %d1
    cmp.l   #0x00000030, %d1
    bne     fail_c0_ctrl

    | re-prime so case 1 cannot pass on case 0's leftovers
    move.l  #0x000000F0, %d0
    fmove.l %d0, %fpcr

    | ── Case 1: the EXACT ROM shape — FMOVEM.L (d16,PC),FPCR/FPSR ────────
    | ext1 = 0x9800 : bit15..13=100 (ctrl-reg move, mem->ctrl),
    |                 mask bits12..10 = 110 = FPCR+FPSR
i1:
    .short  0xF23A, 0x9800
    .short  tbl2 - (i1 + 4)

    fmove.l %fpcr, %d1
    cmp.l   #0x00000030, %d1
    bne     fail_c1_fpcr
    fmove.l %fpsr, %d2
    and.l   #0x0000FFFF, %d2
    bne     fail_c1_fpsr

    | ── Case 2: all three — FMOVEM.L (d16,PC),FPCR/FPSR/FPIAR ────────────
    | ext1 = 0x9C00 : mask bits12..10 = 111.  Memory order is FPCR, FPSR,
    | FPIAR at offsets 0/4/8, so a phase-ordering error shows up as FPIAR
    | picking up FPCR's word.
i2:
    .short  0xF23A, 0x9C00
    .short  tbl3 - (i2 + 4)

    fmove.l %fpcr, %d1
    cmp.l   #0x00000010, %d1
    bne     fail_c2_fpcr
    fmove.l %fpiar, %d3
    cmp.l   #0x12345678, %d3
    bne     fail_c2_fpiar

    | ── Case 3: FPIAR only — the list mask must be honoured, i.e. FPCR
    | ── must survive untouched.  ext1 = 0x8400 : mask bits12..10 = 001.
i3:
    .short  0xF23A, 0x8400
    .short  tbl1 - (i3 + 4)

    fmove.l %fpcr, %d1
    cmp.l   #0x00000010, %d1          | still case 2's value
    bne     fail_c3_fpcr
    fmove.l %fpiar, %d3
    cmp.l   #0x0000ABCD, %d3
    bne     fail_c2_fpiar

    move.l  #0xC0FFEE00, %d1
    move.l  %d1, PASS_SENT
    bra     .

fail_c0_ctrl:
    move.l  #0xDEADFC06, %d1
    move.l  %d1, PASS_SENT
    bra     .

fline_handler:
    move.l  #0xDEADFC00, %d1
    move.l  %d1, PASS_SENT
    bra     .

fail_c1_fpcr:
    move.l  #0xDEADFC01, %d1
    move.l  %d1, PASS_SENT
    bra     .
fail_c1_fpsr:
    move.l  #0xDEADFC02, %d1
    move.l  %d1, PASS_SENT
    bra     .
fail_c2_fpcr:
    move.l  #0xDEADFC03, %d1
    move.l  %d1, PASS_SENT
    bra     .
fail_c2_fpiar:
    move.l  #0xDEADFC04, %d1
    move.l  %d1, PASS_SENT
    bra     .
fail_c3_fpcr:
    move.l  #0xDEADFC05, %d1
    move.l  %d1, PASS_SENT
    bra     .

    .align 2
    | FPCR only takes bits [15:4]; 0x30 = round-to-nearest / extended-ish
    | pattern that reads back verbatim.
tbl2:
    .long   0x00000030          | -> FPCR
    .long   0x00000000          | -> FPSR
tbl3:
    .long   0x00000010          | -> FPCR
    .long   0x00000000          | -> FPSR
    .long   0x12345678          | -> FPIAR
tbl1:
    .long   0x0000ABCD          | -> FPIAR (mask = FPIAR only)
