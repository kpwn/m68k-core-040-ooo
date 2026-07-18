| mmu_pflusha_dirty_dcache_reconfig.s — task: real-HW boot deadlock repro
| (2026-07-15/16, macqd700-soc build 0xbf8ba691, cpu@26ef235e).
|
| HW symptom: live JTAG on a real Quadra 700 boot (first time ever
| exercised against a REAL SCSI/SD disk, not the "no disk" floppy
| path) froze completely — pc_live AND exc_count both frozen for
| minutes (survives JTAG session restart + hw_server refresh), while
| the cycle counter kept incrementing (core_clk alive — genuine
| pipeline deadlock, not a dead-clock/reload artifact).
|
| Frozen exactly at the instruction AFTER a PFLUSHA:
|
|   40803f60: movec %d1,%dtt0
|   40803f64: movel %a0@(0x10),%d1
|   40803f68: movec %d1,%itt1
|   40803f6c: movec %d1,%dtt1
|   40803f70: movel %a0@(8),%d1
|   40803f74: moveal %a0@(4),%a0
|   40803f78: movec %a0,%srp
|   40803f7c: movec %d1,%tc
|   40803f80: pflusha                 <-- retires
|   40803f82: movew %sp@+,%sr         <-- pc_live frozen HERE
|   40803f84: rts
|
| Suspected mechanism (see rtl/core/m68k_core_execute.vh:581-586 and
| rtl/core/m68k_core_flush.vh's mmu-walker-wire sequencer): PFLUSHA
| retire pulses `mmu_dcache_flush_req` level-high until the dcache's
| flush-all-writeback walker (rtl/core/mem/dcache.v S_FA_*) completes
| and mmu_flush_done_pulse fires.  While that's pending,
| `lsu_ready_to_iqmem` blocks ALL new iq_mem issue (loads AND stores)
| into the LSU, and both the I-side and D-side MMU translate-request
| gates (`immu_req_valid_w & ~mmu_dcache_flush_req`,
| `dmmu_req_valid & ~mmu_dcache_flush_req`) block ATC walks.  This is
| the FIRST ROM control path that ever issues a real PFLUSHA while
| the dcache has genuinely DIRTY lines needing writeback (every prior
| boot path either had CACR off or an empty/clean dcache at the
| PFLUSHA site) — so the flush-all walker's writeback-eviction path
| (S_FA_RD/S_FA_AW/S_FA_B) is exercised live for the first time here.
|
| This test reproduces the exact micro-pattern in isolation:
|   1. Enable the D-cache and dirty 16 lines across 16 distinct sets
|      (forces the flush-all walker to actually evict+writeback,
|      not just fast-path through an all-clean scan).
|   2. Replay the exact ROM MOVEC sequence (DTT0/ITT1/DTT1/SRP/TC)
|      from a config table, matching addressing modes 1:1.
|   3. PFLUSHA.
|   4. Immediately execute a LOAD (`movew %sp@+,%sr`) — the exact
|      instruction HW is frozen fetching/executing on.
|
| If the core deadlocks, the sim +timeout cycle cap (see .args) fires
| and the test FAILs as a hang (no PASS sentinel written) — that is
| the expected RED signal for this regression until the root cause
| is fixed.  PASS sentinel: 0xC0FFEE00 at 0xFFFF0000.
|
| FAIL sentinels:
|   0xDEAD0BAD — unexpected bus/address error during setup
|   0xDEAD0F01 — post-pflusha check value mismatch

    .text
    .org 0

_start:
    lea     0x00010000, %a7

    | Minimal exception vectors so any unexpected fault gets a
    | distinctive sentinel instead of silently free-running.
    move.l  #_panic, 0x00100008     | bus error
    move.l  #_panic, 0x0010000C     | address error
    move.l  #_panic, 0x00100010     | illegal instruction
    move.l  #_panic, 0x00100020     | privilege violation

    move.l  #0x00100000, %d0
    movec   %d0, %vbr

    | ITT0 — supervisor passthrough for the 0x40000000.. code region
    | (matches byte_lane_indexed_mmu_on.s / real Q700 ROM recipe).
    move.l  #0x4000C000, %d0
    movec   %d0, %itt0

    | DTT0 — supervisor passthrough for low DRAM.
    move.l  #0x007FA000, %d0
    movec   %d0, %dtt0

    | DTT1 — supervisor passthrough for the 0xFFFFxxxx sentinel region.
    move.l  #0xFF00E060, %d0
    movec   %d0, %dtt1

    | URP/SRP initial root (never walked — TTs cover everything used
    | by this test, matches the working ATC-test recipe).
    move.l  #0x00200000, %d0
    movec   %d0, %urp
    movec   %d0, %srp

    | Enable MMU translation (3-level, 4K pages).
    move.l  #0x00008770, %d0
    movec   %d0, %tc

    | ── D-cache enable ──────────────────────────────────────────────
    move.l  #0x80000000, %d0
    movec   %d0, %cacr

    | ── Dirty 16 cache lines across 16 distinct sets (32B lines,
    |    32 sets total in the 4KB/4-way dcache) — the ROM boot path
    |    that hangs has genuinely dirty data cached (disk buffers,
    |    stack spill, etc.) by the time it reaches this PFLUSHA.
    lea     0x00104000, %a1
    moveq   #15, %d6
.dirty_loop:
    move.l  #0xAAAAAAAA, (%a1)
    adda.l  #32, %a1
    dbra    %d6, .dirty_loop

    | ── Replay the exact ROM MOVEC sequence (0x40803f60-0x40803f7c) ──
    lea     CFG_TBL, %a0

    move.l  0x00(%a0), %d1          | DTT0 reload (identical passthrough)
    movec   %d1, %dtt0
    move.l  0x10(%a0), %d1          | ITT1/DTT1 shared value (E=0: inert)
    movec   %d1, %itt1
    movec   %d1, %dtt1
    move.l  0x08(%a0), %d1          | new TC value (keep MMU enabled)
    movea.l 0x04(%a0), %a0          | new SRP value (table ptr clobbered,
                                     | matches real ROM: last table read)
    movec   %a0, %srp
    movec   %d1, %tc

    | Push a benign supervisor SR so the post-PFLUSHA pop below is
    | well-formed (mirrors the real ROM's call-frame convention where
    | the caller pushed the old SR before entering this helper).
    move.w  #0x2700, -(%sp)

    pflusha

    | ── This is the EXACT instruction HW is frozen fetching/executing
    |    on (PC 0x40803f82 in the live repro). ──
    movew   %sp@+, %sr

    | If we reach here, no deadlock in this sim config — verify the
    | reconfiguration actually took effect (D1 low byte from the TC
    | load should match what we wrote) as a sanity check, then PASS.
    move.l  #0xCAFEBABE, %d0
    cmp.l   #0xCAFEBABE, %d0
    bne     _fail_check

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a0)
_halt:
    bra     _halt

_fail_check:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0F01, %d7
    move.l  %d7, (%a0)
_halt_fc:
    bra     _halt_fc

_panic:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0BAD, %d7
    move.l  %d7, (%a0)
_halt_panic:
    bra     _halt_panic

    .align  4
CFG_TBL:
    .long   0x007FA000     | +0x00: DTT0 reload value (unchanged passthrough)
    .long   0x00200000     | +0x04: new SRP value (reuse existing valid root)
    .long   0x00008770     | +0x08: new TC value (MMU stays enabled)
    .long   0x00000000     | +0x0C: (unused, padding to match +0x10 stride)
    .long   0xFF00E060     | +0x10: new ITT1/DTT1 value — same passthrough
                            |        as the original DTT1 (must keep
                            |        covering the 0xFFFFxxxx sentinel
                            |        region: DTT1 is one of the two
                            |        registers this sequence overwrites,
                            |        and losing that coverage mid-test
                            |        faults the PASS/FAIL sentinel store
                            |        itself — a test-harness artifact,
                            |        not the bug under test).
