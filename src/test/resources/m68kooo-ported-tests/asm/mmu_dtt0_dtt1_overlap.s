| mmu_dtt0_dtt1_overlap.s — DTT0 wins when DTT0/DTT1 overlap.
|
| Goal: per 68040 PRM §3.1.4, DTT0 has priority over DTT1 when both
| match the same address.  Program both with overlapping ranges but
| DIFFERENT cache modes — DTT0 with cacheable copyback, DTT1 with
| non-cacheable.  Issue a write+read to a covered address.  If DTT0
| wins, the write hits the cache (and a CPUSH would expose it).  If
| DTT1 wins, every access goes straight to memory.
|
| Distinguish via cache: write a value, CINV the line.  If DTT0 wins
| (cacheable), CINV drops the dirty line and reload sees the OLD
| memory contents (0xFFFFFFFF).  If DTT1 wins (uncached), the write
| went directly to memory; CINV is a no-op on an uncached line and
| reload sees the WRITTEN value.
|
| Construction:
|   DTT0 = base 0x10, mask 0x00, E=1, S=11, CM=01 (copyback)
|        = 0x1000_E020 — covers ONLY 0x10xxxxxx
|   DTT1 = base 0x10, mask 0x00, E=1, S=11, CM=11 (non-cacheable)
|        = 0x1000_E060 — same coverage as DTT0
|   ITT0 = 0x4000_E020 — code passthrough
|   DTT1 ALSO needs to cover the sentinel (0xFFFF0000).  We use a
|   different bit pattern (base 0xFF mask 0x00) — but with DTT1 already
|   programmed for 0x10 overlap, we can't dual-purpose.  Instead,
|   route the sentinel via DTT1 by programming DTT1 = covers BOTH
|   regions impossible (each TTR is one base+mask).  Solution:
|   DTT0 covers sentinel + low memory; DTT1 only covers 0x10xxxxxx
|   with non-cacheable mode.  Then if DTT0's wider mask wins, low
|   memory IS cacheable through DTT0 and the test value lives there.
|
|   Use:
|   DTT0 = 0x000F_E020 — covers 0x00..0x0F (incl. test region & code base)
|                        cacheable copyback
|   DTT1 = 0x000F_E060 — same coverage, NON-cacheable
|   We omit sentinel-region coverage.  The sentinel is on 0xFFxxxxxx
|   which neither TTR covers; with TC.E=1 and no walker setup, that
|   write would page-fault.  So: do all PASS/FAIL writes to a low-
|   memory cell whose value we then read out architecturally — no
|   real sentinel write needed if the test format allows.
|
|   But the testbench requires the C0FFEE00 sentinel write at
|   0xFFFF0000.  We need DTT1 (the higher-priority loser) to be
|   programmed differently so we can also reach the sentinel.
|   Route: leave TC.E=0 (MMU disabled), so DTTs have no effect.  Use
|   TC.E disabled with passthrough — then the test cannot probe
|   DTT priority because TTRs are gated by TC.E.  68040 TTRs are
|   active even with MMU disabled (per PRM §3.1.4 "TTRs operate
|   independent of TC bit E").  But our Phase-A walker may differ.
|
| GIVEN the constraint set, SIMPLER design: leave MMU disabled, but
| program both DTT0 and DTT1 with the SAME base/mask covering low
| memory and DIFFERENT cache modes.  Cache enable via CACR.DE.  Drive
| the test exactly like cinv_line_basic but with DTT0+DTT1 both
| covering the test region.  If DTT0 wins, behaviour matches
| cinv_line_basic (CINV drops dirty data, reload sees 0xFFFFFFFF).
| If DTT1 wins, behaviour inverts (data persists).
|
| EXPECTED OUTCOME: today's MMU may treat TTR-priority differently
| (or not honour DTT1 at all when TC.E=0).  Either way the test
| outcome is informative.
|
| PASS sentinel: 0xC0FFEE00 when DTT0 priority observed (CINV drops
|                line + reload reads the un-written memory pattern).
| FAIL sentinels:
|   0xDEAD0901 — DTT1 won (reload still sees written value)
|   0xDEAD0902 — CACR write didn't take

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000

_start:
    lea     0x00020000, %a7

    | DTT0 — cacheable copyback over 0x00xxxxxx.
    move.l  #0x000FE020, %d7
    movec   %d7, %dtt0
    | DTT1 — non-cacheable over 0x00xxxxxx (same range).
    move.l  #0x000FE060, %d7
    movec   %d7, %dtt1
    | ITT0 — code passthrough.
    move.l  #0x400FE020, %d7
    movec   %d7, %itt0
    | TC.E=1.
    move.l  #0x8000, %d7
    movec   %d7, %tc

    | CACR.DE = 1.
    move.l  #0x80000000, %d7
    movec   %d7, %cacr
    movec   %cacr, %d6
    cmp.l   %d7, %d6
    bne     _fail_cacr

    | Test region 0x00011000.  Write 0xCAFEBABE into it.
    move.l  #0x00011000, %a0
    move.l  #0xCAFEBABE, (%a0)

    | CINV DC, LINE, (A0).
    cinvl   %dc, (%a0)

    | Reload.
    move.l  (%a0), %d0

    | If DTT0 wins (cacheable), the line was dirty -> CINV dropped it
    | -> reload reads memory which never received the writeback ->
    | 0xFFFFFFFF.
    cmp.l   #0xFFFFFFFF, %d0
    bne     _fail_dtt1_won

    | PASS — sentinel write goes via DTT1 (non-cacheable for 0xFF range)
    | but neither TTR covers 0xFFxxxxxx in this setup.  Need a separate
    | DTT2-equivalent... Use the fact that exception.v's commit-time
    | sentinel write goes through dcache which falls back to the bypass
    | path when no TTR matches and TC.E=1 + no walker.  In practice the
    | test will fault here.  Acceptable: writing to 0xFFFF0000 with no
    | TTR coverage may take vec-2.  We rewrite the test to use an
    | alternate DTT1 layout for the second probe.
    |
    | Pragmatic: re-program DTT1 to cover 0xFFxxxxxx now that DTT
    | priority has been observed for the low-region cache test.
    move.l  #0xFF00E060, %d7
    movec   %d7, %dtt1

    lea     PASS_SENT, %a1
    move.l  #0xC0FFEE00, %d2
    move.l  %d2, (%a1)
_halt:
    bra     _halt

_fail_dtt1_won:
    | DTT1 (non-cacheable) won — write was uncached, CINV no-op,
    | reload sees the written value.  Re-route sentinel:
    move.l  #0xFF00E060, %d7
    movec   %d7, %dtt1
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0901, %d2
    move.l  %d2, (%a1)
_halt_d1:
    bra     _halt_d1

_fail_cacr:
    move.l  #0xFF00E060, %d7
    movec   %d7, %dtt1
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0902, %d2
    move.l  %d2, (%a1)
_halt_cc:
    bra     _halt_cc
