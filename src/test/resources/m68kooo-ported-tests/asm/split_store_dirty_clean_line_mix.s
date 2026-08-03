| split_store_dirty_clean_line_mix.s — split store whose two halves land
| in D-cache lines that are in DIFFERENT states.
|
| A split store issues two independent cache beats.  If a beat that
| MISSES were mishandled (fill dropped, write lost during the refill,
| second beat launched before the first line's eviction completed) the
| symptom would be a store that lands only partially — or not at all —
| exactly the hardware symptom under investigation.  Every other split
| test in the suite runs with both lines already resident and dirty from
| the seeding writes, so the miss path is never taken.
|
| Four sub-cases, each a LONG store at 2 (mod 4) straddling a 32-byte
| D-cache line boundary (region+0x1E → bytes 0x1E..0x21):
|   A  both lines dirty + resident        (baseline, matches other tests)
|   B  low line dirty, high line PUSHED+INVALIDATED  → 2nd beat misses
|   C  low line PUSHED+INVALIDATED, high line dirty  → 1st beat misses
|   D  both lines PUSHED+INVALIDATED                 → both beats miss
|
| After the aligned read-back check, each case also pushes both lines
| back to memory and re-reads, so a beat that landed in the cache but
| was lost on eviction is still caught.
|
| The D-cache is deliberately enabled (TC.E=1 + copyback DTT0 +
| CACR.DE); without that mmu.v forces cache_inh=1 and the whole test
| would be a no-op that still printed PASS.  Sentinel 0x0B is the
| positive control for that.
|
| PASS sentinel: 0xC0FFEE00.
| FAIL sentinels 0xDEAD06nn:
|   A1/A2 case A lo/hi wrong      A3 case A post-push re-read wrong
|   B1/B2 case B lo/hi wrong      B3 case B post-push re-read wrong
|   C1/C2 case C lo/hi wrong      C3 case C post-push re-read wrong
|   D1/D2 case D lo/hi wrong      D3 case D post-push re-read wrong
|   0B    CONTROL: CACR.DE did not stick — cache is OFF, results void
|   0C    CONTROL: seed not observable
|   0A    unexpected address error (vec 3)

    .text

    .equ BASE, 0x00100000            | 32 B aligned
    .equ RA,   BASE+0x000
    .equ RB,   BASE+0x040
    .equ RC,   BASE+0x080
    .equ RD,   BASE+0x0C0

_start:
    lea     0x00010000, %a7
    move.l  #_addr_err, 0x0000000C

    | ── Turn the D-cache ON ───────────────────────────────────────
    move.l  #0x4000C000, %d7
    movec   %d7, %itt0
    move.l  #0x0000E020, %d7         | 0x00xxxxxx, E=1, S=ign, CM=01 copyback
    movec   %d7, %dtt0
    move.l  #0xFF00E060, %d7         | 0xFFxxxxxx, non-cacheable (sentinel)
    movec   %d7, %dtt1
    move.l  #0x8000, %d7
    movec   %d7, %tc
    move.l  #0x80000000, %d7
    movec   %d7, %cacr
    movec   %cacr, %d6
    cmp.l   %d7, %d6
    bne     _f0B

    | ─────────────────────────── CASE A ───────────────────────────
    | Both lines dirty and resident.
    lea     RA, %a0
    move.l  #0xA1A2A3A4, 0x1C(%a0)
    move.l  #0xB1B2B3B4, 0x20(%a0)
    move.l  0x1C(%a0), %d1
    cmp.l   #0xA1A2A3A4, %d1
    bne     _f0C
    move.l  #0x55667788, %d0
    move.l  %d0, 0x1E(%a0)
    move.l  0x1C(%a0), %d1
    cmp.l   #0xA1A25566, %d1
    bne     _fA1
    move.l  0x20(%a0), %d1
    cmp.l   #0x7788B3B4, %d1
    bne     _fA2
    | Force both lines out to memory, then re-read from memory.
    lea     RA+0x00, %a1
    cpushl  %dc, (%a1)
    lea     RA+0x20, %a1
    cpushl  %dc, (%a1)
    move.l  0x1C(%a0), %d1
    cmp.l   #0xA1A25566, %d1
    bne     _fA3
    move.l  0x20(%a0), %d1
    cmp.l   #0x7788B3B4, %d1
    bne     _fA3

    | ─────────────────────────── CASE B ───────────────────────────
    | Low line dirty + resident, HIGH line pushed and invalidated so
    | the SECOND beat misses.
    lea     RB, %a0
    move.l  #0xA1A2A3A4, 0x1C(%a0)
    move.l  #0xB1B2B3B4, 0x20(%a0)
    lea     RB+0x20, %a1
    cpushl  %dc, (%a1)
    move.l  #0x55667788, %d0
    move.l  %d0, 0x1E(%a0)
    move.l  0x1C(%a0), %d1
    cmp.l   #0xA1A25566, %d1
    bne     _fB1
    move.l  0x20(%a0), %d1
    cmp.l   #0x7788B3B4, %d1
    bne     _fB2
    lea     RB+0x00, %a1
    cpushl  %dc, (%a1)
    lea     RB+0x20, %a1
    cpushl  %dc, (%a1)
    move.l  0x1C(%a0), %d1
    cmp.l   #0xA1A25566, %d1
    bne     _fB3
    move.l  0x20(%a0), %d1
    cmp.l   #0x7788B3B4, %d1
    bne     _fB3

    | ─────────────────────────── CASE C ───────────────────────────
    | LOW line pushed and invalidated so the FIRST beat misses; high
    | line dirty + resident.
    lea     RC, %a0
    move.l  #0xA1A2A3A4, 0x1C(%a0)
    move.l  #0xB1B2B3B4, 0x20(%a0)
    lea     RC+0x00, %a1
    cpushl  %dc, (%a1)
    move.l  #0x55667788, %d0
    move.l  %d0, 0x1E(%a0)
    move.l  0x1C(%a0), %d1
    cmp.l   #0xA1A25566, %d1
    bne     _fC1
    move.l  0x20(%a0), %d1
    cmp.l   #0x7788B3B4, %d1
    bne     _fC2
    lea     RC+0x00, %a1
    cpushl  %dc, (%a1)
    lea     RC+0x20, %a1
    cpushl  %dc, (%a1)
    move.l  0x1C(%a0), %d1
    cmp.l   #0xA1A25566, %d1
    bne     _fC3
    move.l  0x20(%a0), %d1
    cmp.l   #0x7788B3B4, %d1
    bne     _fC3

    | ─────────────────────────── CASE D ───────────────────────────
    | BOTH lines pushed and invalidated — both beats miss.
    lea     RD, %a0
    move.l  #0xA1A2A3A4, 0x1C(%a0)
    move.l  #0xB1B2B3B4, 0x20(%a0)
    lea     RD+0x00, %a1
    cpushl  %dc, (%a1)
    lea     RD+0x20, %a1
    cpushl  %dc, (%a1)
    move.l  #0x55667788, %d0
    move.l  %d0, 0x1E(%a0)
    move.l  0x1C(%a0), %d1
    cmp.l   #0xA1A25566, %d1
    bne     _fD1
    move.l  0x20(%a0), %d1
    cmp.l   #0x7788B3B4, %d1
    bne     _fD2
    lea     RD+0x00, %a1
    cpushl  %dc, (%a1)
    lea     RD+0x20, %a1
    cpushl  %dc, (%a1)
    move.l  0x1C(%a0), %d1
    cmp.l   #0xA1A25566, %d1
    bne     _fD3
    move.l  0x20(%a0), %d1
    cmp.l   #0x7788B3B4, %d1
    bne     _fD3

    lea     0xFFFF0000, %a2
    move.l  #0xC0FFEE00, (%a2)
_halt:  bra _halt

_fA1:   move.l #0xDEAD06A1, 0xFFFF0000
_hA1:   bra _hA1
_fA2:   move.l #0xDEAD06A2, 0xFFFF0000
_hA2:   bra _hA2
_fA3:   move.l #0xDEAD06A3, 0xFFFF0000
_hA3:   bra _hA3
_fB1:   move.l #0xDEAD06B1, 0xFFFF0000
_hB1:   bra _hB1
_fB2:   move.l #0xDEAD06B2, 0xFFFF0000
_hB2:   bra _hB2
_fB3:   move.l #0xDEAD06B3, 0xFFFF0000
_hB3:   bra _hB3
_fC1:   move.l #0xDEAD06C1, 0xFFFF0000
_hC1:   bra _hC1
_fC2:   move.l #0xDEAD06C2, 0xFFFF0000
_hC2:   bra _hC2
_fC3:   move.l #0xDEAD06C3, 0xFFFF0000
_hC3:   bra _hC3
_fD1:   move.l #0xDEAD06D1, 0xFFFF0000
_hD1:   bra _hD1
_fD2:   move.l #0xDEAD06D2, 0xFFFF0000
_hD2:   bra _hD2
_fD3:   move.l #0xDEAD06D3, 0xFFFF0000
_hD3:   bra _hD3
_f0B:   move.l #0xDEAD060B, 0xFFFF0000
_h0B:   bra _h0B
_f0C:   move.l #0xDEAD060C, 0xFFFF0000
_h0C:   bra _h0C
_addr_err:
    move.l  #0xDEAD060A, 0xFFFF0000
_h0A:   bra _h0A
