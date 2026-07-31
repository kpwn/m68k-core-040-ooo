| tst_abs_beqw_cache_miss.s — TST.B (xxx).W + word-displacement Bcc,
| with the operand byte and the branch target explicitly forced through
| every combination of D-cache/I-cache HIT vs MISS via CINV LINE.
|
| MOTIVATION.  tst_abs_beqw.s proves the pair correct with caches in
| whatever state the harness leaves them (uncontrolled).  This test
| controls D-cache state for the 0x0349 operand fetch and I-cache state
| for the beq.w TARGET line explicitly, via CINVL DC/IC, and walks all
| four {operand: hit/miss} x {target: hit/miss} combinations plus a
| churn loop that flips state every iteration.  MMU is enabled in
| TT-TRANSPARENT mode (matching cinv_line_basic.s's recipe) purely
| because mmu.v forces cache_inh=1 globally whenever the MMU is off —
| without this the D-cache never actually caches anything and "hit"
| vs "miss" would be meaningless for the operand side.  This is a
| lighter config than tst_abs_beqw_mmu_on.s's real page-table walk;
| that axis is covered separately there.
|
| PASS: 0xC0FFEE00 sentinel.
| FAIL sentinels:
|   0xDEAD0B02 — bus error (unexpected fault)
|   0xDEAD0701 — scenario A (D-miss, I-miss, Z=1) not taken
|   0xDEAD0702 — scenario B (D-hit,  I-hit,  Z=0) wrongly taken
|   0xDEAD0703 — scenario C (D-miss, I-hit,  Z=1) not taken
|   0xDEAD0704 — scenario D (D-hit,  I-miss, Z=1) not taken
|   0xDEAD0705 — churn loop: Z=1 rep not taken
|   0xDEAD0706 — churn loop: Z=0 rep wrongly taken
|   0xDEAD0707 — CACR readback mismatch (setup sanity failed)

    .text
    .org 0

_start:
    lea     0x00020000, %a7

    move.l  #_panic_berr, 0x00100008
    move.l  #0x00100000, %d0
    movec   %d0, %vbr

    | DTT0 = passthrough 0x00xxxxxx cacheable copyback (covers 0x0349).
    move.l  #0x000FE020, %d7
    movec   %d7, %dtt0
    | ITT0 = passthrough 0x4xxxxxxx (test code) cacheable copyback.
    move.l  #0x400FE020, %d7
    movec   %d7, %itt0
    | DTT1 = passthrough 0xFFxxxxxx (sentinel) non-cacheable.
    move.l  #0xFF00E060, %d7
    movec   %d7, %dtt1
    | TC.E = 1 (4K page, MMU on so DTT cache-mode bits are honoured).
    move.l  #0x8000, %d7
    movec   %d7, %tc
    | CACR.DE = enable D-cache.
    move.l  #0x80000000, %d7
    movec   %d7, %cacr
    movec   %cacr, %d6
    cmp.l   %d7, %d6
    bne     _fail_cacr

    lea     0x00000349, %a4          | operand byte
    lea     0x00000340, %a5          | operand's containing D-cache line

| ── Scenario A: D-cache MISS, I-cache MISS, Z=1 (must take) ─────────
    cinvl   %dc, (%a5)
    lea     _tgt_a, %a0
    cinvl   %ic, (%a0)
    move.b  #0x00, (%a4)
    .short  0x4a38, 0x0349
    .short  0x6700
    .short  (_tgt_a - .)
    bra     _fail_a
_tgt_a:

| ── Scenario B: D-cache HIT (line warm from A), I-cache HIT (target
|    line warm from A's own fetch), Z=0 (must NOT take) ─────────────
    move.b  #0x01, (%a4)
    .short  0x4a38, 0x0349
    .short  0x6700
    .short  (_fail_b - .)
    | correct: fall through

| ── Scenario C: D-cache MISS (re-invalidate operand line), I-cache
|    HIT (reuse _tgt_a, already warm), Z=1 (must take) ──────────────
    cinvl   %dc, (%a5)
    move.b  #0x00, (%a4)
    .short  0x4a38, 0x0349
    .short  0x6700
    .short  (_tgt_c - .)
    bra     _fail_c
_tgt_c:

| ── Scenario D: D-cache HIT (operand warm from C), I-cache MISS (fresh
|    target line _tgt_d never fetched before), Z=1 (must take) ──────
    lea     _tgt_d, %a0
    cinvl   %ic, (%a0)
    move.b  #0x00, (%a4)
    .short  0x4a38, 0x0349
    .short  0x6700
    .short  (_tgt_d - .)
    bra     _fail_d
_tgt_d:

| ── Churn loop: flip D-cache state every iteration, 16 reps, always
|    re-fetching through whatever state results ─────────────────────
    moveq   #0, %d6
_churn_loop:
    move.l  %d6, %d0
    andi.l  #1, %d0
    beq     _churn_no_inv
    cinvl   %dc, (%a5)
_churn_no_inv:
    move.b  #0x00, (%a4)
    .short  0x4a38, 0x0349          | Z=1, must take
    .short  0x6700
    .short  (_churn_z_ok - .)
    bra     _fail_churn_z1
_churn_z_ok:
    move.b  #0x01, (%a4)
    .short  0x4a38, 0x0349          | Z=0, must NOT take
    .short  0x6700
    .short  (_fail_churn_z0 - .)
    | correct: fall through

    addq.l  #1, %d6
    cmp.l   #16, %d6
    blt     _churn_loop

_pass:
    lea     0xFFFF0000, %a1
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a1)
_halt:
    bra     _halt

_fail_a:
    move.l  #0xDEAD0701, %d7
    bra     _fail_common
_fail_b:
    move.l  #0xDEAD0702, %d7
    bra     _fail_common
_fail_c:
    move.l  #0xDEAD0703, %d7
    bra     _fail_common
_fail_d:
    move.l  #0xDEAD0704, %d7
    bra     _fail_common
_fail_churn_z1:
    move.l  #0xDEAD0705, %d7
    bra     _fail_common
_fail_churn_z0:
    move.l  #0xDEAD0706, %d7
    bra     _fail_common
_fail_cacr:
    move.l  #0xDEAD0707, %d7
_fail_common:
    lea     0xFFFF0000, %a1
    move.l  %d7, (%a1)
_halt_fail:
    bra     _halt_fail

_panic_berr:
    move.l  #0xDEAD0B02, %d7
    lea     0xFFFF0000, %a1
    move.l  %d7, (%a1)
_halt_panic:
    bra     _halt_panic
