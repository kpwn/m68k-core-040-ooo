| fpu_fp_rat_leak_regression.s — permanent regression test for the FP-RAT
| physical-register-leak bug found + fixed via task #108's leak-hunt
| (see tb/tests/asm/fpu_dyadic_leak_stress.s for the throwaway
| investigation scaffolding this test supersedes).
|
| THE BUG (fixed in rtl/core/commit.v + rtl/core/m68k_core_rename.vh):
| commit.v's "macro buffer" (mb_* arrays, ~line 1810) defers a cracked
| macro-instruction's non-last-uop destination-register commit/free
| until the macro's LAST uop retires, then replays each buffered entry's
| rat_free_en/rat_commit_en pulse one per cycle (oldest first).  This
| defer mechanism is domain-generic (works for both the int RAT and the
| FP RAT), but the buffer had NO per-entry record of which RAT domain a
| given deferred destination belonged to.  Downstream
| (m68k_core_rename.vh), the generic rat_free_en/rat_commit_en pulses
| were routed to the int RAT vs the FP RAT based on `rob_head_is_fp` —
| computed from whatever instruction happened to be at the LIVE ROB head
| at the exact moment of drain.  Because the ROB moves on to unrelated
| instructions while a deferred entry sits in the buffer, a buffered
| FP-domain free could be silently routed to the INT RAT (or vice versa)
| based purely on incidental drain timing.  When an FP-domain free got
| misrouted, fp_rat.v never got its commit/free — the freed FP physical
| register never returned to the FP free list.  The FP free list
| (PREG_FP_W-sized — much smaller than the int one) exhausts fast under
| repeated dyadic FPU ops, permanently stalling dispatch
| (q_alloc_ok_for_uop stuck low forever since fp_alloc_ok never
| recovers) — this is exactly the hang that was root-caused live on
| hardware (dispatch stall once the FP physical-register free list was
| exhausted).
|
| THE FIX: commit.v now captures a per-entry `mb_is_fp[]` bit AT PUSH
| TIME (when rob_uop_type correctly reflects the macro actually being
| buffered) and exposes a single `rat_commit_is_fp` output that is set
| alongside every rat_free_en/rat_commit_en pulse — from mb_is_fp[] in
| the drained-replay path, and from the live rob_uop_type in the
| immediate (non-deferred) retire path.  m68k_core_rename.vh now routes
| to the int RAT vs FP RAT using this signal instead of inferring it
| from the ROB's live head, which does not correspond to a draining
| entry's actual domain.
|
| THIS TEST reproduces the exact instruction shapes that hit the bug on
| real hardware: dyadic FPU ops with an int-domain source B, cracked
| into multiple uops whose non-last uop's FP destination gets buffered
| (FADD.B Dn,FPn register-form, and FMUL.X (d,An,Xn),FPn memory-indexed
| form), plus FSUB.B for broader dyadic coverage and FNEG.X (monadic,
| no int source) as a negative control that should never leak.
| Confirmed empirically (this investigation) that unfixed RTL exhausts
| the FP free list and wedges dispatch permanently in well under 20
| iterations of this loop shape — 64 iterations here is a >3x margin
| over that empirical exhaustion point.  On unfixed RTL this test HANGS
| (times out, never reaches the PASS sentinel).  On fixed RTL it
| completes all 64 iterations and PASSes within a tight cycle budget.
|
| PASS sentinel: 0xC0FFEE00 written to 0xFFFF0000.

    .text
    .org 0

    .equ PASS_SENT,       0xFFFF0000
    .equ BUF,              0x00022000
    .equ ITER_COUNT_ADDR,  0x000F0000
    .equ TARGET_ITERS,     64

_start:
    lea     0x00018000, %a7

    lea     BUF, %a1
    move.l  #0x3F800000, 0(%a1)
    move.l  #0x40000000, 4(%a1)

    move.l  #0x3F800000, %d0
    .short  0xF200, 0x4000              | FMOVE.S D0,FP0
    move.l  #0x40000000, %d0
    .short  0xF200, 0x4080              | FMOVE.S D0,FP1

    clr.l   ITER_COUNT_ADDR

_iter_loop:
    | Register-form dyadic (matches the FIRST HW hang PC's instruction
    | shape: FADD.B Dn,FPn with an int-source-B read directly from Dn).
    | Cracked into >1 uop; the non-last uop's FP-domain destination is
    | the one that used to leak into the wrong RAT at drain time.
    move.l  #0x3F800000, %d0
    .short  0xF200, 0x5822               | FADD.B D0,FP0

    | Memory-indexed dyadic (matches the SECOND HW hang PC's instruction
    | shape: FMUL.X (d,An,Xn),FPn with an int-source-B fed via REG_TMP1).
    move.l  #0, %d3
    .short  0xF231, 0x48A3, 0x3000       | FMUL.X (0,A1,D3.W),FP1

    | FSUB.B Dn,FPn: broader coverage of the dyadic int-source class.
    move.l  #0x40000000, %d0
    .short  0xF200, 0x5828               | FSUB.B D0,FP0

    | FNEG.X FPn,FPn: monadic control shape, no int source — must never
    | leak on its own; included so a regression that narrows the fix to
    | "only int-sourced dyadic ops" still gets exercised against this.
    .short  0xF200, 0x001A               | FNEG.X FP0,FP0

    addq.l  #1, ITER_COUNT_ADDR
    move.l  ITER_COUNT_ADDR, %d0
    cmp.l   #TARGET_ITERS, %d0
    blt     _iter_loop

    lea     PASS_SENT, %a1
    move.l  #0xC0FFEE00, %d2
    move.l  %d2, (%a1)
_halt:
    bra     _halt
