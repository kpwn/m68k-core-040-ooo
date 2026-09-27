# Perf lever queue — goal, method, and the measured state of every lever

**GOAL (owner, 2026-09-27):** implement every lever below, validate each with IPC
benchmarks, synthesise the whole thing at **100 MHz for fast iteration**, and only
then push for **200 MHz closure**.

## Method, and why it is in this order

**100 MHz is the iteration lane, 200 MHz is the shipping target.** Measured cadence:

| stage | 100 MHz | 200 MHz |
|---|---:|---:|
| synth | 8:32 | 12:18 |
| place | 9:22 | 12:25 |
| **route** | **6:07** | **25:57** |
| post-route phys_opt | skipped (timing met) | 6 passes for +0.034 ns total |
| **wall, no lock wait** | **~33 min** | ~2 h |

What the 100 MHz lane is valid for: **area (exact), correctness, mispredicts/kinst,
and any board measurement whose metric is counted in cycles.**
What it is NOT valid for:
- **WNS.** A 100 MHz build says nothing about 200 MHz closure. Do not quote it.
- **Cross-lane LUT comparisons.** Synthesis retimes and replicates harder against a
  tight constraint, so a 100 MHz build of identical RTL lands lower on LUTs. Compare
  within a lane only.
- **Memory-latency-hiding gains.** DDR latency is fixed in ns, so at half clock it
  costs half the cycles — the machine looks like it has a much closer memory system.
  D-cache / hit-under-miss / load-to-use wins read SMALLER here than at 200 MHz.
- **Never compare Dhrystones across lanes:** ~57K at 100 MHz vs ~111K at 200 MHz.
  That confusion already cost four board runs.

Bitstreams need the ADB firmware injected or the machine has no keyboard/mouse:
`patch_adb_bitstream.py patch --bit …blank.bit --mmi …adb.mmi --manifest …adb.json
--firmware files/342s0440-b.bin --output …local.bit`. Never flash the raw `blank.bit`.

## THE GROUND TRUTH: the machine is front-end STARVED, not back-end stalled

Board `perf`, 100 MHz, `build_id=0xD01DBDC5`, two windows from one session:

| per kinst | whole session (boot + Finder + benchmarks) | Dhrystone only |
|---|---:|---:|
| mispredicts | **92.14** | 21.41 |
| I-cache misses | **32.16** | 0.19 |
| D-cache misses | 24.43 | 1.17 |
| DTLB walks | 12.63 | 0.04 |
| IPC | 0.1706 | 0.3613 |
| retire stall (ROB busy, nothing retiring) | 7.59% | 52.22% |
| D-cache stall / walk stall | 6.1% / 6.7% | 0.83% / 0.08% |

Retire is 0/1/2 per cycle, so from 9.32G retired in 54.6G cycles at 7.59% retire
stall, **the ROB is EMPTY 75-84% of the time** on the real workload (Dhrystone:
12-30%). Rough attribution: mispredict recovery ~20% of cycles, I-cache refill ~14%,
ITLB walks ~11% — ~45% in the front end, against 6.1% D-cache and 6.7% walk.

**Almost all IPC work in this campaign has been back-end.** It optimises the ~8%
where the ROB is busy and stuck.

## Area: track as we go, cut at the end

**Plan (owner, 2026-09-27): track area in the 100 MHz lane as each lever lands, then
do a dedicated area-reduction pass once they are all implemented.** Running ledger:
`docs/PERF_AREA_LEDGER.md`, appended automatically by the lane script so it cannot be
forgotten. Area inside the lane is exact; comparing a lane row to a 200 MHz build is
not (see above).

Why the cut-later order is right: shrinking LUTs can WORSEN timing, because congestion
is pin DENSITY rather than area — Vivado's own remedy (`CELL_BLOAT_FACTOR`) makes
designs bigger, and area anti-correlated with WNS across three builds of this design.
So an area pass aimed at 200 MHz closure is only meaningful once the feature set is
frozen. Precedent: the last area campaign removed **5,048 LUT with zero IPC cost**
(152,093 → 147,045 at 200 MHz).

Known cost so far: Track 5's two branch flags are **+1,170 LUT (+0.81%)**, zero BRAM,
FFs flat.

## The queue

| # | lever | state | evidence |
|---|---|---|---|
| 1 | strcmp bench kernel + ALU-arm re-test | ✅ gated 396/396, `677eaf2f` | byte-identical to the board loop; both spec arms exactly flat |
| — | branch-ring torn read + class counters | ✅ gated 400/400, `a31dd04b` | fail-before on master: 10/32 branch, 15/32 exc entries stitched |
| 2 | LS spec wakeup rebased onto master | in flight | −5.10% / 14 kernels; base was OLD master `a204565f` |
| 3 | I-cache prefetch instrumentation | in flight | next-line prefetcher EXISTS; `prefetchEnable` unreachable from the debug bus |
| 4 | D-side speculative prefetch | queued — **size first** | `DcachePlugin` has ZERO prefetch machinery; design already in `DESIGN_ooo_load_issue_and_prefetch.md` |
| 5 | Fetch-directed prefetch (FDIP) | queued | next-line cannot cross a taken branch; at 92 mispred/kinst coverage is structurally capped |
| 6 | announce timing for the early-probe path | queued | ceiling **17.0-19.7%** of cycles, **0.0%** captured |
| 7 | RAS architectural shadow | queued | **a defect**: every mispredicted call costs TWO mispredicts (1024/2050 in `br-ind`) |
| 8 | `alignedDone` → `alignedEarlyWbFire` | queued | free one-cycle-early wake on the parked path |
| — | LS OoO park + recovery | in flight | separate standing goal; unblocks `loadBypassUnreadyLoad` |

**#7 gates #5.** FDIP's yield is bounded by prediction accuracy; returns are both a
mispredict source and a fetch redirect, so fix the double-mispredict-per-call first or
FDIP gets measured against a handicapped predictor.
**#3 gates #5** too: know what next-line already covers before crediting FDIP with it.

## Measured DEAD — do not revisit

- **Memory renaming / store-to-load bypass** — the satisfiable loads are the machine's
  FASTEST (a full SQ overlap forwards at P4, never touching the cache). ROM intra-BB
  1.31%; pure rename 0.019%. The only STALLING shape (partial overlap) is 0.06%.
- **Early load address resolution** — a statically resolvable address is by construction
  never the head of a dependence chain through its address. `frame-loads-a6` hits IPC
  1.000. ROM is 31.4% A7/A6-relative and it still cannot pay.
- **ALU-class speculative wakeup** — flat on 14 kernels AND on the strcmp shape.
- **Age-matrix IQ** — area-neutral; compaction costs zero logic, `OHMasking.first` is
  ~32 LUT. The case is fanout (2,746 → 994), not area.
- **BTB capacity growth** — `br-cap-fit` FITS the BTB and still mispredicted 27%;
  ~88% of that was slot-1 coverage, not capacity.
- **Route-directive sweep** — every alternative worse than `AggressiveExplore`
  (−0.233); `MoreGlobalIterations` by 0.4 ns.

## Measurement rules these levers are judged by

1. **`-cb` kernels for anything store-sensitive.** The default `copybackDtt=false`
   makes every store precise (~12.4 vs ~1.31 cycles) and has produced THREE wrong
   conclusions.
2. **More than one seed.** Store-heavy kernels swing ±9.9% seed to seed.
3. **Per-kernel, not just the aggregate.** One track's whole aggregate rested on a
   single kernel at a single seed whose own ledger was break-even.
4. **Check the ledger against the cycles.** If the mechanism's own accounting says
   break-even and the cycles move, the cycle delta is suspect.
5. **Assert on architectural register values, not retire counts.** `runKernel` runs
   until N macros retire WHATEVER THEY ARE — a harness bug let a branch vanish and
   every check still passed.
6. **Check the bench even CONTAINS the shape.** Four coverage holes found in one week:
   zero A6/A7 operands, zero store→load pairs, zero load→compare→branch chains, and 25
   total suite mispredicts against the board's 24.4 MPKI.
