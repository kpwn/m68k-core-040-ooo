| fpu_fpsr_fpcc_after_arith.s — FP ARITHMETIC must update FPSR.FPCC too.
|
| DEFERRED (known-failing): this is the second half of task #150 and is NOT
| yet fixed.  It is committed RED on purpose so the gap cannot be lost, and
| so it flips green by itself the moment the fix lands.
|
| Background: commit 94e3fa70 made FCMP-driven condition codes
| architecturally visible by folding `arch_fpcc` into FPSR on read (and
| keeping arch_fpcc coherent on explicit FPSR writes).  But `arch_fpcc` is
| only ever WRITTEN by FCMP —
|     fpu_top.v:  mov_is_fcmp_r <= issue_fire && is_fcmp;
| — whereas a real 68040 updates FPCC from the RESULT of every FP arithmetic
| operation.  So after FADD/FSUB/FMUL/FDIV the visible FPSR is stale.
|
| Measured 2026-07-28 (RTL run completed, pass=1, so these are real values
| and not fault-storm residue):
|     2.0 - 5.0 = -3.0  -> FPSR  RTL 0x00000000  Musashi 0x08000000 (N)
|     4.0 - 4.0 =  0.0  -> FPSR  RTL 0x00000000  Musashi 0x04000000 (Z)
|
| Why it matters: software that reads FPSR directly rather than branching
| with FBcc sees stale condition codes.  Mac OS SANE reads FPSR directly.
| FBcc itself is fine — it consumes arch_fpcc, and every failing fuzz seed in
| this class had ALL integer registers matching, i.e. identical branch paths.
|
| TO FIX: compute FPCC from the arithmetic result at each FPU completion path
| (fpu_add / fpu_mul / fpu_div / fpu_sqrt, plus the mov shortcut path that
| handles fmov/fabs/fneg/fint/fintrz) and drive it out through the same
| fpcc_upd_en_out / fpcc_upd_val_out channel FCMP already uses.  Mind the bit
| order: fpu_top packs {[3] NaN, [2] I, [1] Z, [0] N} while PRM FPSR runs N at
| bit 27 down to NAN at bit 24.
|
| PASS sentinel: 0xC0FFEE00
| FAIL sentinels:
|   0xDEADFC11 — negative arithmetic result did not set FPSR.N
|   0xDEADFC12 — zero arithmetic result did not set FPSR.Z

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000

_start:
    lea     0x00010000, %a7

    | ── negative arithmetic result must set N (FPSR bit 27) ───────────
    fmove.s #0r2.0, %fp0
    fsub.s  #0r5.0, %fp0            | 2.0 - 5.0 = -3.0
    fmove.l %fpsr, %d1
    move.l  %d1, %d2
    and.l   #0x08000000, %d2
    beq     fail_arith_n

    | ── zero arithmetic result must set Z (FPSR bit 26) ───────────────
    fmove.s #0r4.0, %fp2
    fsub.s  #0r4.0, %fp2            | 0.0
    fmove.l %fpsr, %d3
    move.l  %d3, %d4
    and.l   #0x04000000, %d4
    beq     fail_arith_z

    move.l  #0xC0FFEE00, %d0
    move.l  %d0, PASS_SENT
    bra     .

fail_arith_n:
    move.l  #0xDEADFC11, %d0
    move.l  %d0, PASS_SENT
    bra     .
fail_arith_z:
    move.l  #0xDEADFC12, %d0
    move.l  %d0, PASS_SENT
    bra     .
