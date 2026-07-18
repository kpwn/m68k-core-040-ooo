| dcache_hit_second_load.s — L1D cache hit on a repeat load
|
| Scenario: we store a value to a RAM address (commit-time write-back
| lands in the cache dirty), then re-read the same address twice.  The
| first load (same LSU issue cycle as the store-commit-bvalid wait)
| exercises the read-hit path — the line is already populated with the
| store's merged value.  The second load reads the same line a second
| time, forcing the cache's tag-hit + data-mux path to reproduce the
| exact same word without stale or X data.
|
| Data integrity is all we're testing: PASS if both loads see the
| stored pattern; FAIL otherwise.  The cache's correctness contract is
| unchanged from the pass-through wrapper — any difference is a bug.
|
| What this would catch:
|   - Tag compare reading the wrong way.
|   - BRAM data_ram_q[*] stale on back-to-back loads.
|   - PLRU update corrupting the tag for the hit way.
|   - Any cache-line-offset miscalculation (woff=0 vs word-0).

    .text
    .org 0

_start:
    | Fill a scratch slot with a known value.
    move.l  #0xCAFEBABE, %d0
    lea     0x00200000, %a0
    move.l  %d0, (%a0)          | store — caches line, marks dirty

    | Load #1: should hit the line we just wrote.
    move.l  (%a0), %d1
    move.l  #0xCAFEBABE, %d2
    cmp.l   %d2, %d1
    bne     _fail

    | Load #2: same address, forces a second LOOKUP on the hot line.
    move.l  (%a0), %d3
    cmp.l   %d2, %d3
    bne     _fail

    | Load #3 from a different word in the same line (offset +4) —
    | exercises PLRU update without new fill.
    move.l  #0xDEADF00D, %d4
    move.l  %d4, 4(%a0)
    move.l  4(%a0), %d5
    move.l  #0xDEADF00D, %d6
    cmp.l   %d6, %d5
    bne     _fail

_pass:
    lea     0xFFFF0000, %a1
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a1)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a1
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a1)
_halt_fail:
    bra     _halt_fail
