| mmu_swapmode_dual_srp_walk.s — real-HW boot deadlock repro, faithful
| _SwapMMUMode variant (2026-07-16 platform-side investigation).
|
| Fifth variant in the mmu_pflusha_dirty_dcache_* family.  The four
| prior variants all PASS; live-HW JTAG evidence (wedge-status probe on
| build 0xbf8ba691, frozen at pc_live=0x40803f82 / exc_count=0x14af)
| shows the deadlock fires at the RETURN from the ROM's _SwapMMUMode
| ($A05D) helper and that the prior repros missed three ingredients,
| all captured from the live wedge via JTAG reads of the real MMU
| config records at (0xcb8) -> 0x3fff92 / 0x3fffa6:
|
|   1. BOTH modes run with translation ENABLED (TC=0x0000C000: E=1,
|      P=8K pages — prior tests used 4K-page TC=0x8770 and swapped
|      from an ITT-covered config).
|   2. Post-swap TTRs are ITT0/DTT0=0xF900C060, ITT1/DTT1=0x807FC040 —
|      neither covers the CODE region (0x40xxxxxx) nor RAM/stack
|      (0x00xxxxxx).  After PFLUSHA, the very next IFETCH (the helper's
|      own `move.w (%sp)+,%sr; rts` tail) needs a cold I-side table
|      walk, and the SR-restore load needs a cold D-side walk, both
|      exactly while the MOVEC-TC/PFLUSHA-triggered dcache flush
|      (mmu_dcache_flush_req) may still be draining.  The prior tests
|      kept ITT0=0x4000C000 (code transparent) and DTT0=0x007FA000
|      (all RAM transparent) through the swap, so the I-side cold-walk
|      interplay was never exercised.  (The prior replay also omitted
|      the helper's `movec %d1,%itt0` — the ROM writes ITT0 too.)
|   3. The other mode's record has ALL TTRs = 0 (everything walked)
|      with its own separate SRP — the swap is table-mode -> table-mode
|      with two distinct SRP roots, not off -> on.
|
| Construction: two full 3-level 8K-page table sets (1:1 for RAM
| 0x000000-0x3FFFFF and code 0x40800000-0x4083FFFF), two config
| records laid out exactly like the ROM's (rec+4=SRP, +8=TC, +12=TTR0,
| +16=TTR1), and a byte-exact copy of the ROM helper at 0x40803f4e
| (SR push, IPL7 mask, mode-byte store, MOVEC ITT0/DTT0/ITT1/DTT1/
| SRP/TC, PFLUSHA, SR pop, RTS).  The main loop performs 128 swap
| pairs (B' then back to A'), with a per-iteration dirty-line count
| (i mod 128) and a per-iteration 1-cycle-granularity delay (i) to
| sweep the pflusha-retire vs flush-done alignment phase.
|
| PASS sentinel: 0xC0FFEE00 at 0xFFFF0000 (ITT1-covered post-swap).
| FAIL sentinels:
|   0xDEAD0BAD — unexpected exception during the run
|   0xDEAD0901 — post-swap stack probe readback mismatch
| A hang (timeout, no sentinel) = the HW deadlock reproduced.

    .text
    .org 0

    .set SRP_A, 0x00200000          | mode-A table set (TTRs disabled mode)
    .set SRP_B, 0x00280000          | mode-B table set (0xF9/0x80 TTR mode)

_start:
    lea     0x00010000, %a7

    | Vector table at 0x00100000; distinct handler per interesting vector
    | so the FAIL sentinel identifies the first fault class.
    move.l  #_panic_berr, 0x00100008  | bus error
    move.l  #_panic_aerr, 0x0010000C  | address error
    move.l  #_panic_ill,  0x00100010  | illegal instruction
    move.l  #_panic_priv, 0x00100020  | privilege violation

    move.l  #0x00100000, %d0
    movec   %d0, %vbr

    | ── Build the two identical 1:1 page-table sets ──────────────────
    lea     SRP_A, %a5
    bsr     _build_tables
    lea     SRP_B, %a5
    bsr     _build_tables

    | URP mirrors SRP_A (we stay in supervisor mode throughout).
    move.l  #SRP_A, %d0
    movec   %d0, %urp

    | Enable both caches.
    move.l  #0x80008000, %d0
    movec   %d0, %cacr

    | Enter mode A (tables live, no TTR coverage anywhere) — from this
    | point EVERY ifetch and data access is table-walked or ATC-hit.
    lea     REC_A, %a0
    moveq   #0, %d1
    bsr     _swap_helper

    | ── Main sweep loop: 128 iterations, each deliberately racing a
    | CPUSHA %dc (data-cache-push-all — the SAME shared S_FA_SCAN walker
    | m68k_core_flush.vh's arbiter uses, entered via dcache.v's
    | ml_was_hit=1 "maint path" instead of the arbiter's flush_all_req
    | path) against the ROM-faithful MOVEC-TC/PFLUSHA swap sequence.
    |
    | Confirmed-on-HW mechanism (2026-07-16 wedge-status readback on
    | build 0xbf8ba691+instrumentation, at the live post-PFLUSHA freeze):
    | mmu_dcache_flush_req=1, fq_valid[0]=1, fq_active=1, dcache=IDLE
    | simultaneously — only reachable if some flush-all walker invocation
    | completed WITHOUT dcache.v ever asserting flush_all_done.  The one
    | code path that does that is S_FA_DONE's ml_was_hit branch (pulses
    | maint_done instead) — and dcache.v's flush_all_req acceptance
    | branch (S_IDLE, rtl/core/mem/dcache.v) never reset ml_was_hit to 0
    | before this fix, so an arbiter-sourced flush that gets accepted
    | while ml_was_hit is still (even transiently) 1 from ANY CPUSH-ALL/
    | PAGE maintenance walk inherits the wrong completion attribution and
    | never signals flush_all_done — permanently stranding
    | fq_valid[0]/fq_active/mmu_dcache_flush_req exactly as observed.
    |
    | d5 = iteration index i (0..127).  Per iteration:
    |   - dirty i lines, then CPUSHA %dc (own walk duration scales ~i)
    |   - a (127-i)-iteration delay loop before the swap (inverse sweep,
    |     so together with the CPUSH's own i-scaled duration this sweeps
    |     a diagonal cut across the 2-D {cpush-duration, gap-to-MOVEC-TC}
    |     space in a single 128-iteration pass)
    |   - dirty (i mod 128) MORE lines for the swap's own MOVEC-TC/
    |     PFLUSHA flush, then the swap itself (the wedging transition)
    moveq   #0, %d5
_iter_loop:
    | CPUSH-target dirty lines: count = i (0..127), distinct region from
    | the swap's own dirty region below so the two flushes' dirty-line
    | counts are independently controlled by the same sweep index.
    move.l  %d5, %d6
    beq     .no_cpush_dirty
    lea     0x00108000, %a1
    move.l  %d5, %d4
    subq.l  #1, %d4
.cpush_dirty_loop:
    move.l  #0xBBBBBBBB, (%a1)
    adda.l  #32, %a1
    dbra    %d4, .cpush_dirty_loop
.no_cpush_dirty:

    | CPUSH DC, ALL — kicks off dcache.v's S_FA_SCAN walker via the
    | ml_was_hit=1 maint path.  Duration scales with the dirty count
    | just populated above (0 dirty ~34 cycles up to ~1200 cycles at
    | i=127, per dcache.v's documented flush-all timing).
    cpusha  %dc

    | Inverse-scaled gap: (127-i) extra loop turns between the CPUSHA
    | and the swap's own MOVEC-TC — sweeps the alignment between CPUSH's
    | completion/handshake tail and the arbiter's next flush_all_req
    | assertion across the full iteration range.
    move.l  #127, %d0
    sub.l   %d5, %d0
.gap_loop:
    subq.l  #1, %d0
    bpl     .gap_loop

    | Dirty (i mod 128) dcache lines in RAM (copyback pages) — feeds the
    | swap's OWN MOVEC-TC/PFLUSHA-triggered flush duration.
    move.l  %d5, %d6
    andi.l  #0x7F, %d6
    beq     .no_dirty
    lea     0x00104000, %a1
    subq.l  #1, %d6
.dirty_loop:
    move.l  #0xAAAAAAAA, (%a1)
    adda.l  #32, %a1
    dbra    %d6, .dirty_loop
.no_dirty:

    | Swap to mode B (the wedging transition on real HW).
    lea     REC_B, %a0
    moveq   #1, %d1
    bsr     _swap_helper

    | Post-swap probe, mirroring the ROM caller (`move.b 0xcb2,-(sp)`
    | at 0x40898e9c): a stack push whose D-translation is a cold walk.
    move.b  0x00000CB2, -(%sp)
    move.b  (%sp)+, %d0
    cmp.b   #1, %d0
    bne     _fail_probe

    | Swap back to mode A.
    lea     REC_A, %a0
    moveq   #0, %d1
    bsr     _swap_helper

    addq.l  #1, %d5
    cmpi.l  #128, %d5
    blt     _iter_loop

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a0)
_halt:
    bra     _halt

_fail_probe:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0901, %d7
    move.l  %d7, (%a0)
_halt_fp:
    bra     _halt_fp

_panic_berr:
    move.l  #0xDEAD0B02, %d7
    bra     _panic_common
_panic_aerr:
    move.l  #0xDEAD0B03, %d7
    bra     _panic_common
_panic_ill:
    move.l  #0xDEAD0B04, %d7
    bra     _panic_common
_panic_priv:
    move.l  #0xDEAD0B08, %d7
_panic_common:
    | Stash the iteration index for post-mortem (mapped RAM).
    move.l  %d5, 0x00100280
    | Sentinel via the table-mapped alias VA (valid in BOTH modes; the
    | raw PA 0xFFFF0000 is only ITT-covered in mode B).
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
_halt_panic:
    bra     _halt_panic

| ── _swap_helper: byte-faithful copy of ROM 0x40803f4e..0x40803f84 ──
| In:  a0 = config record (+4 SRP, +8 TC, +12 TTR0, +16 TTR1)
|      d1 = mode byte for 0xcb2
| Matches the ROM helper instruction-for-instruction (including the
| SR push / IPL7 mask / mode-byte store prologue and the
| `pflusha; move.w (%sp)+,%sr; rts` tail that wedges on HW).
_swap_helper:
    move.w  %sr, -(%sp)
    ori.w   #0x0700, %sr
    move.b  %d1, 0x00000CB2
    move.l  12(%a0), %d1
    movec   %d1, %itt0
    movec   %d1, %dtt0
    move.l  16(%a0), %d1
    movec   %d1, %itt1
    movec   %d1, %dtt1
    move.l  8(%a0), %d1
    movea.l 4(%a0), %a0
    movec   %a0, %srp
    movec   %d1, %tc
    pflusha
    move.w  (%sp)+, %sr
    rts

| ── _build_tables: build one 1:1 8K-page 3-level table set at (%a5) ──
| Layout (offsets from a5):
|   +0x0000 root   (128 x 4B)
|   +0x0200 L2_ram (128 x 4B)   root[0x00]
|   +0x0400 L2_rom (128 x 4B)   root[0x20]
|   +0x0600 L3_ram (512 x 4B)   L2_ram[0x00..0x0F], 16 tables x 32
|   +0x0E00 L3_rom (32 x 4B)    L2_rom[0x20]
|   +0x0E80 L2_hi  (128 x 4B)   root[0x7F]
|   +0x1080 L3_hi  (32 x 4B)    L2_hi[0x3F] — maps VA 0xFFFE0000-
|                               0xFFFFFFFF 1:1 noncached so the test
|                               sentinel PA 0xFFFF0000 is reachable in
|                               mode A too (TTRs disabled there)
| Descriptors: upper levels |0x0A (resident, U=1); pages |0x39
| (resident, U=1, M=1, CM=copyback) — matching the live-HW page
| descriptor 0x40802039 read back over JTAG.
_build_tables:
    | Zero the whole 0x1100-byte region.
    move.l  %a5, %a0
    move.w  #(0x1100/4)-1, %d0
.zero_loop:
    clr.l   (%a0)+
    dbra    %d0, .zero_loop

    | root[0x00] -> L2_ram ; root[0x20] -> L2_rom
    move.l  %a5, %d0
    addi.l  #0x0200+0x0A, %d0
    move.l  %d0, 0x000(%a5)
    move.l  %a5, %d0
    addi.l  #0x0400+0x0A, %d0
    move.l  %d0, 0x080(%a5)

    | L2_ram[k] -> L3_ram_k  (k = 0..15)
    lea     0x0200(%a5), %a0
    move.l  %a5, %d0
    addi.l  #0x0600+0x0A, %d0
    moveq   #15, %d2
.l2ram_loop:
    move.l  %d0, (%a0)+
    addi.l  #0x80, %d0
    dbra    %d2, .l2ram_loop

    | L2_rom[0x20] -> L3_rom
    move.l  %a5, %d0
    addi.l  #0x0E00+0x0A, %d0
    move.l  %d0, 0x0400+0x080(%a5)

    | L3_ram: 512 pages, VA n*0x2000 -> PA n*0x2000 | 0x39
    lea     0x0600(%a5), %a0
    move.l  #0x00000039, %d0
    move.w  #511, %d2
.l3ram_loop:
    move.l  %d0, (%a0)+
    addi.l  #0x2000, %d0
    dbra    %d2, .l3ram_loop

    | L3_rom: 32 pages, VA 0x40800000+n*0x2000 -> 1:1 | 0x39
    lea     0x0E00(%a5), %a0
    move.l  #0x40800039, %d0
    moveq   #31, %d2
.l3rom_loop:
    move.l  %d0, (%a0)+
    addi.l  #0x2000, %d0
    dbra    %d2, .l3rom_loop

    | root[0x7F] -> L2_hi ; L2_hi[0x7F] -> L3_hi ;
    | L3_hi covers VA 0xFFFC0000-0xFFFFFFFF (root idx 0x7F, L2 idx
    | 0x7F = VA[24:18] all-ones), 1:1, noncached (CM=10 -> |0x59).
    | Sentinel VA 0xFFFF0000 -> L3_hi[0x18] -> PA 0xFFFF0000.
    move.l  %a5, %d0
    addi.l  #0x0E80+0x0A, %d0
    move.l  %d0, 0x1FC(%a5)
    move.l  %a5, %d0
    addi.l  #0x1080+0x0A, %d0
    move.l  %d0, 0x0E80+0x1FC(%a5)
    lea     0x1080(%a5), %a0
    move.l  #0xFFFC0059, %d0
    moveq   #31, %d2
.l3hi_loop:
    move.l  %d0, (%a0)+
    addi.l  #0x2000, %d0
    dbra    %d2, .l3hi_loop
    rts

    .align  4
| Records laid out exactly like the ROM's ((0xcb8) -> 0x3fff92 record,
| values read back from the live wedged board over JTAG):
|   +0 (unused pad — ROM record field we don't consume)
|   +4 SRP, +8 TC, +12 TTR0 (-> ITT0+DTT0), +16 TTR1 (-> ITT1+DTT1)
REC_A:
    .long   0x00000000
    .long   SRP_A
    .long   0x0000C000              | TC: E=1, 8K pages
    .long   0x00000000              | TTR0 disabled
    .long   0x00000000              | TTR1 disabled
REC_B:
    .long   0x00000000
    .long   SRP_B
    .long   0x0000C000              | TC: E=1, 8K pages
    .long   0xF900C060              | ITT0/DTT0: 0xF9 transparent, NC-serialized
    .long   0x807FC040              | ITT1/DTT1: 0x80-0xFF transparent, NC
