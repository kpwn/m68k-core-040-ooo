| fpu_fpsr_fpcc_readback.s — FPSR.FPCC must be visible to FMOVE.L FPSR,Dn.
|
| Regression for the gap found 2026-07-28 by the newly-added FP state
| comparison in the Musashi fuzz co-sim (the fuzzer had been generating and
| executing FPU instructions while NEITHER model emitted FP state, so nothing
| about the FPU was ever actually compared).
|
| The gap: `arch_fpsr` in commit.v only ever held the last value an explicit
| FMOVE.L Dn,FPSR wrote.  FCMP-driven condition codes land in a SEPARATE
| `arch_fpcc` register, which FBcc reads directly -- which is exactly why
| branches always behaved correctly and this stayed invisible.  But software
| that reads FPSR architecturally saw stale/zero condition codes.  Mac OS SANE
| does read FPSR directly.
|
| Measured before the fix: `fcmp.x %fp1,%fp0` then `fmove.l %fpsr,%d1` gave
| 0x00000000 where Musashi gives 0x08000000 (N set).  All integer registers
| matched in the failing fuzz seeds, confirming FBcc itself was fine.
|
| NOTE the bit order is reversed between the two representations, which is the
| easiest thing to get wrong here: fpu_top's fcmp_fpcc_w packs
| {[3] NaN, [2] I, [1] Z, [0] N} (alu.v agrees -- fpcc_in[0] is N), while PRM
| FPSR runs N at bit 27 down to NAN at bit 24.
|
| PASS sentinel: 0xC0FFEE00
| FAIL sentinels:
|   0xDEADFC01 — negative compare did not set FPSR.N
|   0xDEADFC02 — equal compare did not set FPSR.Z
|   0xDEADFC03 — equal compare wrongly left FPSR.N set

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000

_start:
    lea     0x00010000, %a7

    | ── negative result must set N (FPSR bit 27) ──────────────────────
    | FCMP <ea>,FPn computes FPn - <ea>, so this is -3.0 - 1.0 = -4.0.
    fmove.s #0r-3.0, %fp0
    fmove.s #0r1.0,  %fp1
    fcmp.x  %fp1, %fp0
    fmove.l %fpsr, %d1
    move.l  %d1, %d2
    and.l   #0x08000000, %d2
    beq     fail_n

    | ── equal operands must set Z (bit 26) and leave N clear ──────────
    fmove.s #0r2.5, %fp2
    fmove.s #0r2.5, %fp3
    fcmp.x  %fp3, %fp2
    fmove.l %fpsr, %d3
    move.l  %d3, %d4
    and.l   #0x04000000, %d4
    beq     fail_z
    move.l  %d3, %d4
    and.l   #0x08000000, %d4
    bne     fail_zn

    move.l  #0xC0FFEE00, %d0
    move.l  %d0, PASS_SENT
    bra     .

fail_n:
    move.l  #0xDEADFC01, %d0
    move.l  %d0, PASS_SENT
    bra     .
fail_z:
    move.l  #0xDEADFC02, %d0
    move.l  %d0, PASS_SENT
    bra     .
fail_zn:
    move.l  #0xDEADFC03, %d0
    move.l  %d0, PASS_SENT
    bra     .
