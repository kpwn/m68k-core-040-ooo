| ifstage_smc_victim_cpusha.s — BUG-11: the ACCIDENTAL pass, and a canary
|
| Task #166 / memory_model_review.md BUG-11.  Identical to
| ifstage_smc_victim_lv.s except the maintenance instruction is
| `cpusha %bc` — push and invalidate EVERY line of BOTH caches.
|
| ── THIS TEST PASSES TODAY, AND THAT IS NOT A FIX. ───────────────────
|
| It passes by accident of timing, and the accident is itself the most
| alarming part of BUG-11.  From the IFSTAGE_DEBUG=1 trace:
|
|   CPUSHA %bc stalls commit for ~160 cycles (the scalar S_FA_SCAN
|   walker, memory_model_review.md §2.6: 163 cycles even with zero dirty
|   lines).  While commit is stalled the FRONT END keeps running ahead
|   sequentially past the not-yet-executed branch, filling l1 with
|   0x40800060, 70, 80, 90, a0, b0, c0, d0 in turn.  Each fill rotates the
|   buffers, so by the time `bra.s LA1` finally redirects, line
|   0x40800040 has long since fallen out of l0/l1/lv.  The redirect
|   MISSES all three, takes the hard path, and refetches the patched
|   bytes (trace: `L0_FILL if_addr=40800040 rdata_top=7e224a82`).
|
| Compare ifstage_smc_victim_lv.s, whose 3-cycle `cpushl %bc` leaves no
| time to run ahead: there the same branch logs
| `SOFT_HIT rdir=40800040 hit_lv=1` and executes pre-patch bytes.
|
| So whether a code patch is observed depends on how many cycles the
| maintenance instruction happened to stall for — exactly the
| "boots nine times out of ten" non-determinism this project has chased.
|
| ── Canary value ─────────────────────────────────────────────────────
| memory_model_review.md §2.6 proposes replacing the scalar flush-all
| walker with a per-set dirty-OR bitmap, cutting the clean CPUSHA case
| from 163 cycles to ~3.  If that optimisation lands while BUG-11 is
| still open, the run-ahead window disappears and THIS TEST WILL START
| FAILING with 0xDEAD0011.  That is not a regression in the flush
| optimisation — it is BUG-11 becoming visible on a code path that used
| to hide it.  Fix if_stage.v, do not slow the flush back down.
|
| PASS sentinel: 0xC0FFEE00  (expected today — see above)
| FAIL sentinels:
|   0xDEAD0011 — STALE: pre-patch moveq #$11 executed => BUG-11 real
|   0xDEAD00A0 — the store never became visible

    .text
_start:
    lea     0x00010000, %a7
    move.l  #_illegal, 0x00000010       | vec 4
    move.l  #_buserr,  0x00000008       | vec 2
    move.l  #_addrerr, 0x0000000c       | vec 3
    move.l  #_priv,    0x00000020       | vec 8

    lea     LA1, %a0
    move.w  #0x7E22, %d1                | replacement: moveq #$22,%d7
    moveq   #0, %d2
    moveq   #0x55, %d7
    bra     LA1

    .balign 16
LA1:
    moveq   #0x11, %d7                  | +0  <- PATCH SITE
    tst.l   %d2                         | +2
    bne.s   LC1                         | +4
    moveq   #1, %d2                     | +6
    nop                                 | +8
    nop                                 | +10
    nop                                 | +12
    nop                                 | +14  falls through -> lv := LA1

    .balign 16
LB1:
    move.w  %d1, (%a0)                  | +0
    cpusha  %bc                         | +2  flush + invalidate everything
    bra.s   LA1                         | +4  taken -> lv soft hit
    nop
    nop
    nop
    nop
    nop

    .balign 16
LC1:
    move.w  (%a0), %d4
    cmp.w   #0x7E22, %d4
    bne     _fail_nopatch
    cmp.l   #0x22, %d7
    bne     _fail_stale
    move.l  #0xC0FFEE00, 0xFFFF0000
    bra     .

_fail_stale:
    move.l  #0xDEAD0011, 0xFFFF0000
    bra     .
_fail_nopatch:
    move.l  #0xDEAD00A0, 0xFFFF0000
    bra     .
_illegal:
    move.l  #0xDEAD0004, 0xFFFF0000
    bra     .
_buserr:
    move.l  #0xDEAD0002, 0xFFFF0000
    bra     .
_addrerr:
    move.l  #0xDEAD0003, 0xFFFF0000
    bra     .
_priv:
    move.l  #0xDEAD0008, 0xFFFF0000
    bra     .
