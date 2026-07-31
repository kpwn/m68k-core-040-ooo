| bcc_after_push_vector_rts_trampoline.s — a Bcc consuming flags across the
| Mac OS "push a vector, RTS to it" dispatch trampoline.
|
| WHY THIS EXISTS (2026-07-29, ioResult investigation)
| ------------------------------------------------------------------------
| The live ROM site that stalls does NOT call a plain subroutine.  At
| 0x0002F76C it does `jsr $4080FF0A`, and $4080FF0A is only:
|
|     4080FF0A  2F38 07BC   move.l  ($07BC).w,-(A7)
|     4080FF0E  4E75        rts
|
| i.e. it pushes a low-memory vector ON TOP of the JSR's return address and
| RTSes to it.  Control reaches the vectored routine with the original
| return address still buried on the stack; that routine's own RTS is what
| finally returns to the Bcc at 0x0002F772.
|
| Two properties make this interesting and NOT covered by
| bcc_reads_flags_set_across_rts.s:
|
|   1. The first RTS does NOT return where the JSR pushed.  A return-address
|      stack (rtl/core/fetch/ras.v, 8 entries, speculative + commit
|      pointers) predicts exactly that, so it MISPREDICTS here, and the
|      second RTS then resolves against a RAS stack misaligned by one entry.
|   2. The Bcc that consumes the flags is the first instruction to retire
|      after that second, mispredicted-context return.
|
| m68k_core.v's header states flag-reading branches are made correct only by
| "the flush path on every Bcc that's read-after-write".  A Bcc immediately
| after a mispredicted return exercises that flush path and the RAS
| correction at the same time.
|
| A PASS DOES NOT CLOSE THE HARDWARE QUESTION — no interrupts here, and the
| real vectored routine is far longer.  It does cover the structural shape.
|
| PASS sentinel: 0xC0FFEE00
| FAIL sentinels:
|   0xDEADTR01 -> 0xDEAD7201 — trampoline did not reach the vectored routine
|   0xDEAD7202 — vectored routine did not return to the caller
|   0xDEAD7203 — Z=1 across trampoline: BNE wrongly taken
|   0xDEAD7204 — Z=0 across trampoline: BNE wrongly not taken
|   0xDEAD7205 — N=1 across trampoline: BMI wrongly not taken
|   0xDEAD7206 — nested trampoline (2 deep): BNE wrong
|   0xDEAD7207 — branch outcome disagrees with the CCR readback
|   0xDEAD7208 — stack not balanced after the trampoline chain

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000
    .equ VEC,       0x00030000      | our stand-in for the low-mem vector
    .equ VEC2,      0x00030004
    .equ WITNESS,   0x00030008

_start:
    lea     0x00010000, %a7
    move.w  #0x2000, %sr

    | ── Case 1: trampoline reaches the vectored routine at all ─────────
    move.l  #targ_z1, VEC
    move.l  #0, WITNESS
    jsr     trampoline
    | flags now come from targ_z1 (Z=1)
    move.l  WITNESS, %d3
    cmp.l   #0x5A5A5A5A, %d3
    bne     fail_t1                 | vectored routine never ran

    | ── Case 2: Z=1 across the trampoline; BNE must NOT be taken ───────
    | (flags still those left by targ_z1 above — but re-do it cleanly so
    |  the Bcc is the FIRST instruction after the return)
    move.l  #targ_z1, VEC
    move.l  %a7, %d6                | remember SP to check balance later
    jsr     trampoline
    bne     fail_t3

    | ── Case 3: Z=0 across the trampoline; BNE MUST be taken ───────────
    move.l  #targ_z0, VEC
    jsr     trampoline
    bne     t3_ok
    bra     fail_t4
t3_ok:

    | ── Case 4: N=1 across the trampoline; BMI MUST be taken ───────────
    move.l  #targ_n1, VEC
    jsr     trampoline
    bmi     t4_ok
    bra     fail_t5
t4_ok:

    | ── Case 5: NESTED trampoline — two vector hops before the flags ───
    | Drives the RAS two entries out of alignment instead of one.
    move.l  #trampoline2, VEC
    move.l  #targ_z0, VEC2
    jsr     trampoline
    bne     t5_ok
    bra     fail_t6
t5_ok:

    | ── Case 6: branch outcome and CCR readback must AGREE ─────────────
    | This is the exact hardware signature: the branch behaved one way
    | while the architectural CCR read the other.
    move.l  #targ_z0, VEC
    jsr     trampoline
    move.w  %sr, %d7
    andi.w  #0x0004, %d7            | isolate Z
    bne     fail_t7                 | Z must be CLEAR (targ_z0)
    tst.w   %d7
    bne     fail_t7                 | readback must agree

    | ── Case 7: stack balance across the whole chain ───────────────────
    cmp.l   %a7, %d6
    bne     fail_t8

    | vectored routine must have returned to us at all
    move.l  WITNESS, %d3
    cmp.l   #0x5A5A5A5A, %d3
    bne     fail_t2

    move.l  #0xC0FFEE00, %d1
    move.l  %d1, PASS_SENT
    bra     .

| ── the trampoline: push a vector ON TOP of our return addr, RTS to it ──
trampoline:
    move.l  VEC, -(%a7)
    rts

trampoline2:
    move.l  VEC2, -(%a7)
    rts

| ── vectored targets: set flags as their LAST act, then RTS ────────────
| Their RTS pops the ORIGINAL jsr return address, so control lands on the
| caller's Bcc with these flags live.

targ_z1:
    move.l  #0x5A5A5A5A, WITNESS
    moveq   #0, %d0
    tst.l   %d0                     | Z=1
    rts

targ_z0:
    move.l  #0x5A5A5A5A, WITNESS
    moveq   #1, %d0
    tst.l   %d0                     | Z=0
    rts

targ_n1:
    move.l  #0x5A5A5A5A, WITNESS
    moveq   #-1, %d0
    tst.l   %d0                     | N=1, Z=0
    rts

fail_t1:
    move.l  #0xDEAD7201, %d1
    move.l  %d1, PASS_SENT
    bra     .
fail_t2:
    move.l  #0xDEAD7202, %d1
    move.l  %d1, PASS_SENT
    bra     .
fail_t3:
    move.l  #0xDEAD7203, %d1
    move.l  %d1, PASS_SENT
    bra     .
fail_t4:
    move.l  #0xDEAD7204, %d1
    move.l  %d1, PASS_SENT
    bra     .
fail_t5:
    move.l  #0xDEAD7205, %d1
    move.l  %d1, PASS_SENT
    bra     .
fail_t6:
    move.l  #0xDEAD7206, %d1
    move.l  %d1, PASS_SENT
    bra     .
fail_t7:
    move.l  #0xDEAD7207, %d1
    move.l  %d1, PASS_SENT
    bra     .
fail_t8:
    move.l  #0xDEAD7208, %d1
    move.l  %d1, PASS_SENT
    bra     .
