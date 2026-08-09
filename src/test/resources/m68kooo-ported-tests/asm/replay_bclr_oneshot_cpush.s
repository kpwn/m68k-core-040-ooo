| replay_bclr_oneshot_cpush.s — an instruction decoded while an I-cache
| maintenance op invalidates the fetch buffers is DISPATCHED AND RETIRED
| TWICE.  This test asserts the architectural consequence on the three
| shapes that make it visible: a register RMW, a MEMORY read-modify-write,
| and a destructive test-and-clear one-shot flag (BCLR Dn,(An) + Scc).
|
| ── The defect (task #225, root-caused 2026-08-06) ────────────────────
|
| rtl/core/fetch/if_stage.v:388
|     wire consuming = pd_valid_w && (pd_consumed != 4'd0);
|     ... if (consuming) pc <= nxt_pc;
|
| rtl/core/decode/decode.v:3100,6383
|     wire f3_capture_en = !f3_valid_q && pd_valid;   // SKID REGISTER
|     ...
|     if (uop_valid && rn_ready && last_phase) pd_consumed = len_bytes;
|
| decode holds the fetched instruction in its F3 skid register and drives
| `pd_consumed` from that REGISTERED copy — it does NOT re-qualify the
| consume against the LIVE `pd_valid`.  if_stage's PC advance DOES.
|
| rtl/core/fetch/if_stage.v:484,861
|     wire inv_kill = snoop_hit_any || inv_maint_req;
|     if (!rst && inv_kill) begin l0_valid <= 0; l1_valid <= 0; ... end
|
| A CPUSH/CINV targeting the I-cache (`inv_maint_req`), or a D-side store
| that snoop-hits a buffered instruction line (`snoop_hit_any`), clears
| all four fetch line buffers.  `pd_valid_w` goes low on the next cycle.
| If decode's skid register fires its consume on exactly that cycle:
|
|   * the µop IS dispatched into the ROB, executes, and retires;
|   * `consuming` is 0, so if_stage does NOT advance `pc` past it.
|
| After the refill, fetch restarts at the SAME pc, and the instruction is
| fetched, decoded, dispatched, executed and retired a SECOND time.  It is
| a full architectural re-execution, not a trace artifact: measured in
| cpush_ic_maint_while_busy as two DISTINCT ROB tags (24 and 25) for
| pc=0x40800050, both is_last_uop, both popped, with no flush, no
| exception and no IRQ in between.
|
| ── Why BCLR Dn,(An) is the shape that hurts ─────────────────────────
|
| Mac OS gates completion routines behind a destructive test-and-clear:
|
|     bclr %d0,(%a0)   | Z := OLD bit
|     sne  %d0         | d0 = 0xFF only if the bit WAS set
|     tst.b %d0
|     beq  skip        | already clear -> never call the completion
|     jsr  (completion)
|
| BCLR on a memory EA cracks to LOAD byte -> ALU_BCLR -> STORE byte
| (decode_uop_assemble.v:5603).  A replay re-runs the whole crack, so the
| SECOND execution loads the ALREADY-CLEARED byte and reports Z=1.  The
| STORE is idempotent — memory looks identical — but the FLAG is not, and
| the Scc that consumes it is fetched after the second BCLR.  The one-shot
| is silently eaten and the guarded call never happens.  A memory-diff
| test cannot see this; only counting how many times the guarded path ran
| can.
|
| ── What this test does ──────────────────────────────────────────────
|
| Two straight-line sweeps of 24 blocks each.  Every block issues one
| CPUSHL %IC and then a different NOP pad (0..23), so the 3-cycle
| `inv_maint_req` window slides across a different instruction of the
| sequence at every block — the alignment that triggers the replay is a
| pipeline-timing property, not something a single fixed sequence can
| pin down.
|
| Sweep A — side-effect double-application.  Pads sit BEFORE the
| counters, so the window walks over them:
|
|   1. arm the one-shot flag byte (bit 0 set) in a CACHEABLE line, so the
|      BCLR's RMW runs against a DIRTY write-back D-cache line;
|   2. CPUSHL %IC,(A1) — the `inv_maint_req` trigger;
|   3. N NOPs (N = 0..23, one per block);
|   4. ADDQ.L #1,%D3   — register-visible execution counter;
|   5. ADDQ.B #1,(%A2) — MEMORY read-modify-write counter;
|   6. BCLR/SNE/TST.B/BEQ + ADDQ.L #1,%D4 — the one-shot guarded path.
|
| Sweep B — one-shot loss.  Same trigger, but the counters are hoisted
| ABOVE the CPUSH so the window walks over the BCLR/SNE/TST/BEQ group
| itself, which is the exact shape the Mac OS wait-loop uses:
|
|   1. arm the flag;  2. CPUSHL %IC,(A1);  3. N NOPs;
|   4. BCLR/SNE/TST.B/BEQ + ADDQ.L #1,%D4.
|
| Each block arms the flag exactly once and must run each counter exactly
| once, so the invariants are:
|
|     D3 == 24     (no instruction executed twice — register form)
|     (MCNT) == 24 (no instruction executed twice — MEMORY RMW form)
|     D4 == 48     (the one-shot was consumed exactly once per arming,
|                   over both sweeps)
|
| D3 > 24 or (MCNT) > 24 proves a replayed retire double-applies a side
| effect.  D4 < 48 proves the OS one-shot is eaten; D4 > 48 would mean
| the guarded call ran twice.  All three are independent detectors of the
| same defect; a fix must turn all three green.
|
| PASS: 0xC0FFEE00
| FAIL sentinels:
|   0xDEAD0002 — bus error
|   0xDEAD0003 — address error
|   0xDEAD0004 — illegal instruction
|   0xDEAD001M — M is a BITMASK of every invariant that broke, so one run
|                reports all three rather than short-circuiting at the
|                first:
|                  bit 0 (0xDEAD0011) — D3 != 24: a register-visible
|                        instruction executed a different number of times
|                        than the program contains it
|                  bit 1 (0xDEAD0012) — (MCNT) != 24: a MEMORY
|                        read-modify-write executed twice
|                  bit 2 (0xDEAD0014) — D4 != 48: the BCLR one-shot flag
|                        was consumed without the guarded path running
|                        (or the guarded path ran twice)

    .text
    .org 0

    .set FLAG,  0x00200000      | one-shot flag byte (own 32 B line)
    .set MCNT,  0x00200040      | memory RMW counter byte (own 32 B line)
    .set CPTGT, 0x00200080      | CPUSHL IC LINE target address
    .set NBLK,  24              | number of pad-sweep blocks

_start:
    lea     0x00010000, %a7
    move.l  #_illegal, 0x00000010       | vec 4
    move.l  #_buserr,  0x00000008       | vec 2
    move.l  #_addrerr, 0x0000000c       | vec 3

    | ─── D-cache-enable prologue ────────────────────────────────────
    | mmu.v:657 forces cache_inh=1 whenever TC.E==0, so the flag byte
    | can only live in a DIRTY write-back D-cache line if we bring up a
    | cacheable translation AND CACR.DE.  Same prologue shape as
    | bench_*_dcache.s.
    | DTT0: VA 0x00000000-0x7FFFFFFF, E=1, S=11, CM=01 (copyback).
    move.l  #0x007FE020, %d7
    movec   %d7, %dtt0
    | DTT1: VA 0x80000000-0xFFFFFFFF, E=1, S=11, CM=11 (noncacheable).
    | MUST stay non-cacheable — the PASS sentinel store to 0xFFFF0000 is
    | what ends the test and has to reach the AXI write channel.
    move.l  #0x807FE060, %d7
    movec   %d7, %dtt1
    | ITT0 mirrors DTT0 so the instruction side stays walk-free.
    move.l  #0x007FE020, %d7
    movec   %d7, %itt0
    move.l  #0x00008000, %d7            | TC.E
    movec   %d7, %tc
    move.l  #0x80000000, %d7            | CACR.DE
    movec   %d7, %cacr

    lea     FLAG, %a0
    lea     CPTGT, %a1
    lea     MCNT, %a2

    | Zero both counters.  The stores land in the (now cacheable) lines,
    | so from here on FLAG and MCNT live in DIRTY D-cache lines.
    clr.b   (%a0)
    clr.b   (%a2)
    moveq   #0, %d3                     | register execution counter
    moveq   #0, %d4                     | guarded-path counter

    | ─── sweep A: side-effect double-application ────────────────────
    | One block per pad length.  `.irp` unrolls straight-line so every
    | block sees a different alignment between the CPUSH's 3-cycle
    | inv_maint_req window and decode's F3 consume handshake.
.macro RBLKA pad
    move.b  #1, (%a0)                   | arm the one-shot (bit 0 set)
    cpushl  %ic, (%a1)                  | -> if_stage inv_kill
    .rept \pad
    nop
    .endr
    addq.l  #1, %d3                     | register RMW  (expect +1)
    addq.b  #1, (%a2)                   | MEMORY RMW    (expect +1)
    moveq   #0, %d1
    bclr    %d1, (%a0)                  | destructive test-and-clear
    sne     %d2                         | 0xFF iff the bit WAS set
    tst.b   %d2
    beq     9f
    addq.l  #1, %d4                     | the "completion call"
9:
.endm

    .irp pad, 0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23
    RBLKA \pad
    .endr

    | ─── sweep B: one-shot loss ─────────────────────────────────────
    | Counters hoisted above the CPUSH so the invalidate window walks
    | over the BCLR/SNE/TST.B/BEQ group itself — the Mac OS wait-loop
    | shape verbatim.
.macro RBLKB pad
    move.b  #1, (%a0)                   | arm the one-shot (bit 0 set)
    moveq   #0, %d1
    cpushl  %ic, (%a1)                  | -> if_stage inv_kill
    .rept \pad
    nop
    .endr
    bclr    %d1, (%a0)                  | destructive test-and-clear
    sne     %d2                         | 0xFF iff the bit WAS set
    tst.b   %d2
    beq     8f
    addq.l  #1, %d4                     | the "completion call"
8:
.endm

    .irp pad, 0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23
    RBLKB \pad
    .endr

    | ─── assertions ─────────────────────────────────────────────────
    | Accumulate a bitmask instead of branching to the first failure, so
    | a single run says which of the three independent detectors tripped.
    moveq   #0, %d6

    cmp.l   #NBLK, %d3                  | register-visible replay
    beq     1f
    bset    #0, %d6
1:
    moveq   #0, %d5                     | MEMORY RMW replay
    move.b  (%a2), %d5
    cmp.l   #NBLK, %d5
    beq     2f
    bset    #1, %d6
2:
    cmp.l   #(2*NBLK), %d4              | one-shot flag eaten / doubled
    beq     3f
    bset    #2, %d6
3:
    tst.l   %d6
    bne     _fail_mask

    move.l  #0xC0FFEE00, 0xFFFF0000
    bra     .

_fail_mask:
    move.l  #0xDEAD0010, %d0
    or.l    %d6, %d0
    move.l  %d0, 0xFFFF0000
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
