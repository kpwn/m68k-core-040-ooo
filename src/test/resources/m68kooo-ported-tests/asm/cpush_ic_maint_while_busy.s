| cpush_ic_maint_while_busy.s — CPUSH IC LINE issued while the I-cache's
| own FSM is busy servicing unrelated fetch/fill traffic must still
| actually invalidate the targeted line, not silently no-op.
|
| Background (review-found bug, rtl/core/fetch/icache.v +
| rtl/core/m68k_core_rename.vh):
|   icache.v's edge-detector for maint_req (CPUSH/CINV) "consumed" the
|   rising edge (via maint_req_q) on WHATEVER cycle it arrived, but only
|   the S_IDLE case body ever actually invalidated anything.  If the
|   edge arrived while the FSM was busy (S_LOOKUP / S_FILL_REQ /
|   S_PRE_FILL_SNOOP / S_COMPLETE — i.e. mid unrelated fetch/fill), the
|   maintenance op was silently dropped: no line was invalidated, but
|   the edge was still "seen" so a later re-check in S_IDLE saw a stale,
|   already-consumed maint_req_q and did nothing.  Compounding this,
|   m68k_core_rename.vh synthesized `ic_maint_done` from the mere RISING
|   EDGE of commit's request (one cycle after the fire), not from the
|   icache's real completion — so commit.v believed the CPUSH/CINV had
|   completed instantly, even on the busy-FSM cycles where it hadn't
|   done anything at all.
|
| This test forces exactly that race architecturally: it primes the
| I-cache with a STALE copy of a code line, overwrites the underlying
| memory with NEW code, issues CPUSH IC LINE targeting that stale line,
| and IMMEDIATELY follows it with a long river of never-before-fetched
| NOP cache lines.  Because fetch runs ahead of in-order commit, while
| commit is still working through retiring the CPUSH (which asserts
| cache_maint_req and gates the ROB head on cache_maint_done — see
| commit.v's cache_maint_wait), the I-cache's fetch FSM is busy
| miss-filling those fresh NOP lines — exactly the "maint_req arrives
| while FSM != S_IDLE" window the bug hits.
|
| Strategy:
|   1. Leave the MMU disabled (default cold-boot state: TC.E=0).  With
|      the MMU off, mmu.v forces cache_inh=1 globally, so the D-cache
|      treats every access as non-cacheable and never caches our writes
|      — meaning it never pulses its automatic SMC snoop
|      (dc_snoop_valid) to the I-cache.  This decouples the test from
|      the AUTOMATIC D-write -> I-cache snoop-invalidate path already
|      covered by smc_dcache_to_icache.s, isolating the EXPLICIT
|      CPUSH/CINV IC path this bug lives in.
|   2. Stage OLD code at NEWCODE (0x00020000):
|        move.l #0xBADC0DE1, %d0 ; rts
|   3. JSR to it once.  This runs the OLD code (sanity-checks D0) AND
|      causes the I-cache to fetch + cache that 16 B line.
|   4. Overwrite NEWCODE (plain stores — bypass the D-cache per step 1)
|      with NEW code:
|        move.l #0xCAFEF00D, %d0 ; rts
|      The I-cache still holds the OLD bytes — nothing has invalidated
|      it yet.
|   5. CPUSH IC, LINE, (A0=NEWCODE), immediately followed by a long run
|      of fresh NOPs to keep the I-cache's fetch FSM busy right around
|      CPUSH's retire.
|   6. JSR to NEWCODE again.  Stale I-cache (bug) -> OLD code re-runs
|      (D0 = 0xBADC0DE1).  Genuinely-invalidated I-cache (fixed) -> NEW
|      code runs (D0 = 0xCAFEF00D).
|
| PASS: sentinel 0xC0FFEE00 (D0 == 0xCAFEF00D after the second JSR).
| FAIL sentinels:
|   0xDEAD0001 — priming JSR didn't run the OLD code correctly (setup
|                bug, not the bug under test)
|   0xDEAD0002 — bus error
|   0xDEAD0003 — address error
|   0xDEAD0004 — illegal instruction (I-cache served truly garbage
|                bytes, not just a stale-but-valid OLD line)
|   0xDEAD0005 — post-CPUSH JSR still ran the STALE OLD code: the
|                CPUSH IC LINE op was silently dropped while the
|                I-cache FSM was busy (the bug this test targets)

    .text
    .org 0

_start:
    lea     0x00010000, %a7
    move.l  #_illegal, 0x00000010       | vec 4 (illegal instr)
    move.l  #_buserr,  0x00000008       | vec 2 (bus error)
    move.l  #_addrerr, 0x0000000c       | vec 3 (address error)

    | NEWCODE target — 16 B-line-aligned RAM address.
    move.l  #0x00020000, %a0

    | Stage OLD code:  move.l #0xBADC0DE1,%d0 ; rts
    move.l  #0x203CBADC, (%a0)
    move.l  #0x0DE14E75, 4(%a0)

    | Prime: JSR runs the OLD code (also caches this I-cache line).
    jsr     (%a0)
    cmp.l   #0xBADC0DE1, %d0
    bne     _fail_prime

    | Overwrite with NEW code:  move.l #0xCAFEF00D,%d0 ; rts
    | MMU is disabled (cache_inh=1 globally) so this store bypasses the
    | D-cache entirely — no automatic SMC snoop fires here.
    move.l  #0x203CCAFE, (%a0)
    move.l  #0xF00D4E75, 4(%a0)

    | CPUSH IC, LINE, (A0) — must invalidate the stale I-cache line.
    cpushl  %ic, (%a0)

    | Long river of never-before-fetched NOPs.  Keeps the I-cache FSM
    | continuously busy miss-filling fresh cold lines for hundreds of
    | cycles right around the point CPUSH retires in commit and fires
    | cache_maint_req — the busy-FSM window this test targets.
    .rept 400
    nop
    .endr

    | Re-execute NEWCODE.  Stale I-cache -> OLD code re-runs (bug).
    | Genuinely invalidated I-cache -> NEW code runs (fixed).
    move.l  #0x00020000, %a0
    jsr     (%a0)
    cmp.l   #0xCAFEF00D, %d0
    bne     _fail_stale

    | PASS
    move.l  #0xC0FFEE00, 0xFFFF0000
    bra     .

_fail_prime:
    move.l  #0xDEAD0001, 0xFFFF0000
    bra     .

_fail_stale:
    move.l  #0xDEAD0005, 0xFFFF0000
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
