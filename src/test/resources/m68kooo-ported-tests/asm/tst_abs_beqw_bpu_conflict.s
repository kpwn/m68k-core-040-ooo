| tst_abs_beqw_bpu_conflict.s — TST.B (xxx).W + word-displacement Bcc
| under deliberate BPU (rtl/core/fetch/bpu.v) mistraining and index
| conflict.
|
| bpu.v is a 64-entry DIRECT-MAPPED table: index = pc[6:1] (6 bits),
| tag = pc[31:7] (25 bits).  Because m68k instructions are always
| 2-byte aligned (pc[0] == 0 always), tag++index together reconstruct
| the FULL 32-bit address — no two DIFFERENT addresses can ever share
| BOTH index and tag (that would require being the same address).  So
| a same-index/different-tag "collision" can only ever evict/miss, not
| produce a false-positive hit; the one genuine misprediction case is
| the SAME branch trained to predict the wrong way for its own next
| resolution.  This test exercises both:
|
|   (1) SELF-MISTRAIN: train our own beq.w to strongly-predict TAKEN
|       (four back-to-back Z=1/taken resolutions), then resolve it
|       Z=0/not-taken five times in a row.  Per bpu.v's update FSM
|       (counter saturates 11->10->01->evict on repeated not-taken),
|       the first TWO of those five are genuine BPU mispredicts
|       (counter still reads as "predict taken" going in); the
|       misprediction-recovery path must still produce the
|       architecturally-correct not-taken outcome and correct Z on
|       every single call.
|
|   (2) INDEX-CONFLICT EVICTION: a decoy TST.B abs.W + beq.w pair is
|       placed at _real_site + 0x80 (exactly 128 = 2^7 bytes away).
|       Adding 128 to an address changes ONLY bit 7 and above, so
|       pc[6:1] (the BPU index) is IDENTICAL for the real pair and the
|       decoy pair, while pc[31:7] (the tag) necessarily differs by
|       exactly bit 7 — a real, deliberately-constructed same-index/
|       different-tag pair, not a guess.  Interleaving decoy-taken
|       resolutions (which evict/overwrite the shared BPU entry via the
|       "taken && tag-mismatch -> allocate fresh entry" rule) between
|       real-pair resolutions forces the real branch to repeatedly miss
|       the BPU (default not-taken-until-resolved) and must not corrupt
|       CCR or the resolved branch direction.
|
| PASS: 0xC0FFEE00 sentinel.
| FAIL sentinels:
|   0xDEAD0801 — self-mistrain: a not-taken call resolved TAKEN
|   0xDEAD0802 — self-mistrain: a taken call (training phase) resolved NOT-TAKEN
|   0xDEAD0803 — index-conflict churn: real pair resolved wrong direction
|   0xDEAD0804 — index-conflict churn: decoy pair resolved wrong direction

    .text
    .org 0

    | Flag bytes must live at fixed LOW-MEMORY addresses reachable by
    | absolute-SHORT (16-bit sign-extended) EAs — they are NOT labels in
    | this (0x40800000-linked) code, since a code-segment label's value
    | would not fit in 16 bits.
    .equ    REAL_FLAG,  0x00001030
    .equ    DECOY_FLAG, 0x00001031

_start:
    lea     0x00020000, %a7

    lea     REAL_FLAG, %a0
    move.b  #0x00, (%a0)
    lea     DECOY_FLAG, %a1
    move.b  #0x00, (%a1)

    bra     _driver

| ── _real_site: the actual ROM-shaped pair.  In: REAL_FLAG byte.
|    Out: d0 = 1 if branch taken, 0 if fall-through. ─────────────────
    .org    0x2000
_real_site:
    .short  0x4a38, REAL_FLAG      | tst.b REAL_FLAG.W
    .short  0x6700                  | beq.w
    .short  (_real_taken - .)
    moveq   #0, %d0
    rts
_real_taken:
    moveq   #1, %d0
    rts

| ── _decoy_site: a DIFFERENT branch at exactly _real_site+0x80, so it
|    shares the real pair's BPU index (pc[6:1]) but not its tag
|    (pc[31:7] differs in bit 7). ────────────────────────────────────
    .org    0x2080
_decoy_site:
    .short  0x4a38, DECOY_FLAG      | tst.b DECOY_FLAG.W
    .short  0x6700                  | beq.w
    .short  (_decoy_taken - .)
    moveq   #0, %d1
    rts
_decoy_taken:
    moveq   #1, %d1
    rts

    .org    0x2100
_driver:
| ---- Phase 1: train real pair to strongly-taken (4x Z=1) ----------
    lea     REAL_FLAG, %a0
    moveq   #0, %d2
_train_loop:
    move.b  #0x00, (%a0)           | Z=1 -> must take
    bsr     _real_site
    tst.l   %d0
    beq     _fail_train_not_taken
    addq.l  #1, %d2
    cmp.l   #4, %d2
    blt     _train_loop

| ---- Phase 2: resolve Z=0 (not-taken) 5x in a row.  First two calls
|      are genuine BPU mispredicts (counter still reads "taken"); the
|      resolved direction must be correct on every single call. -----
    moveq   #0, %d2
_resolve_loop:
    move.b  #0x01, (%a0)           | Z=0 -> must NOT take
    bsr     _real_site
    tst.l   %d0
    bne     _fail_resolve_taken
    addq.l  #1, %d2
    cmp.l   #5, %d2
    blt     _resolve_loop

| ---- Phase 3: index-conflict churn.  Interleave decoy (always taken,
|      to force the "taken && tag-mismatch -> allocate" eviction of
|      whatever the real pair last trained at the shared index) with
|      real-pair calls alternating Z=1/Z=0, 20 reps. ----------------
    lea     REAL_FLAG, %a0
    lea     DECOY_FLAG, %a1
    moveq   #0, %d2
_churn_loop:
    move.b  #0x00, (%a1)           | decoy Z=1 -> always taken, evicts
    bsr     _decoy_site             |   the real pair's shared-index entry
    tst.l   %d1
    beq     _fail_decoy_wrong

    move.l  %d2, %d3
    andi.l  #1, %d3
    beq     _churn_real_z1

    move.b  #0x01, (%a0)           | odd reps: real Z=0 -> must NOT take
    bsr     _real_site
    tst.l   %d0
    bne     _fail_real_wrong
    bra     _churn_next

_churn_real_z1:
    move.b  #0x00, (%a0)           | even reps: real Z=1 -> must take
    bsr     _real_site
    tst.l   %d0
    beq     _fail_real_wrong

_churn_next:
    addq.l  #1, %d2
    cmp.l   #20, %d2
    blt     _churn_loop

_pass:
    lea     0xFFFF0000, %a2
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a2)
_halt:
    bra     _halt

_fail_train_not_taken:
    move.l  #0xDEAD0802, %d7
    bra     _fail_common
_fail_resolve_taken:
    move.l  #0xDEAD0801, %d7
    bra     _fail_common
_fail_real_wrong:
    move.l  #0xDEAD0803, %d7
    bra     _fail_common
_fail_decoy_wrong:
    move.l  #0xDEAD0804, %d7
_fail_common:
    lea     0xFFFF0000, %a2
    move.l  %d7, (%a2)
_halt_fail:
    bra     _halt_fail
