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
| 1 | strcmp bench kernel + ALU-arm re-test | ✅ **gated 396/396**, `677eaf2f` | byte-identical to the board loop; both spec arms exactly flat |
| — | branch-ring torn read + class counters | ✅ **gated 400/400**, `a31dd04b` | fail-before on master: 10/32 branch, 15/32 exc entries stitched |
| 2 | LS spec wakeup rebased onto master | ✅ **gated 396/396**, `462e189c` | **−1.84% (34 kernels)**, −5.10% on the historical 14, `chase-pure` −12.50%; **area-free** (its A/B swing is inside the ~800 LUT noise floor); 3 kernels regress |
| 3 | I-cache prefetch instrumentation + FDIP counters | ✅ **gated 396/396**, `e8149e24` | `+425` flop bits; **zero nets added inside `IcachePlugin`**; 5 mutations verified |
| 4 | D-side speculative prefetch | ⛔ **DEAD**, `907b4016` | +6.2% misses removed at **206% of the bus**; Finder phase **−126.8%**; ceiling 1.8% not 13% |
| 5 | Fetch-directed prefetch (FDIP) | ⏸ **blocked on one board session** | `IC_MISS − IC_MISS_SEQ` is its entire market; `lane100_icpf` bitstream is built and ADB-patched |
| 6 | announce timing for the early-probe path | ⛔ **DEAD** | the 0.0% confirm was measured on the BASELINE arm; the board profile already confirms **83–86%**. Fixing placement raises confirm by up to **46 points** and moves cycles **+0.063%/+0.064% (worse)**, 12/14 kernels bit-identical at both seeds. **The confirm rate is not the metric that matters.** |
| 7 | RAS architectural shadow | ✅ **gated 396/396**, `81f196ee` | **2.008 → 1.008 mispredicts/iter (−49.8%)**, IPC 0.168→0.258, **+69 flop bits**. The retire-time shadow stack **cannot work here** — Tier-1 `earlyFire` redirects before the ROB head and Tier 2 suppresses the later flush |
| 8 | `alignedDone` → `alignedEarlyWbFire` | 🔄 folded into 6 | free one-cycle-early wake on the parked path |
| 9 | BRAM as an implicit mux | 🔄 in flight | 10,322 LUTRAM in the core against 36 RAMB36; constraint is read latency, not storage |
| — | LS OoO park + recovery | ✅ **gated 396/396**, `68a89f88` | `orderRedirects` **0 → 7**, ~20.9 cyc each, **cost ONE flop bit**; lock-step reds 22 → 17. ⛔ the relaxation itself is still blocked by a **third pre-existing** corpus defect |

**#7 gates #5.** FDIP's yield is bounded by prediction accuracy; returns are both a
mispredict source and a fetch redirect, so fix the double-mispredict-per-call first or
FDIP gets measured against a handicapped predictor.
**#3 gates #5** too: know what next-line already covers before crediting FDIP with it.

## Lever 5 refined — FDIP: the go/no-go is ONE counter, and FDIP is NOT next

Measured/derived while instrumenting the existing prefetcher:

- **The structural cap, quantified from board numbers.** 32.16 I-misses/kinst = one new
  line every **31.1 instructions**, against a mispredict every **10.9** (1000/92.14).
  The stream is redirected **~3x between consecutive line first-touches** — before
  counting correctly-predicted taken branches, which next-line also cannot cross.
  Next-line's runway is shorter than its stride.
- **The FTQ already exists and is ON.** `FetchAlignPlugin(enableFetchDirected = true)`
  (`FullCoreSynth.scala:856`), 32-entry `ftqMem` of `{brPc, brLen, target, phtIdx,
  isCond}`, pushed at prediction/redirect time and popped at align/decode confirm. So
  the predicted **target is already a registered value on the fetch side**, and the
  cheap form of FDP is NOT a second predictor — it is letting `seedPfWindow` take the
  predicted-target line as a SECOND seed (~100 flops mirroring the frontier registers,
  plus arbitration for the existing 4 speculative MSHRs). No extra BTB/gshare port.
- **But the FTQ is fetch->decode bookkeeping, not run-ahead** — its entries describe
  branches fetch has already PASSED. The cheap form buys one fetch latency at each taken
  branch; it fixes "next-line cannot cross a taken branch" and is not true run-ahead.
  Real run-ahead needs the predictor queried with a PC fetch has not reached = a second
  port. In-tree plans to cost that:
  `docs/superpowers/plans/2026-08-09-ipc-fetch-directed-btb-implementation-plan.md`.

**Sizing, cheapest first — do 1-3 and then STOP and decide:**
1. **Free, one bitstream, no RTL:** with the new `OFF_IC_PREFETCH_CTL` toggle, run a
   session prefetch ON then OFF and read IC_MISS / PF_ISSUED / PF_USED / PERF_BRANCH /
   MISPRED_COUNT / CYCLE / INST. coverage = `USED/(USED+IC_MISS)`; waste =
   `1 - USED/ISSUED`; benefit = `IC_MISS(off) - IC_MISS(on)`. **Decision rule:** high
   coverage with IC_MISS still ~32/kinst -> the misses are cold/capacity and FDP is the
   WRONG lever; high waste -> direction-limited, FDP both fixes it and recovers bandwidth.
2. **`IC_MISS_SEQ` — ~40 LUT, and it IS the go/no-go.** Demand misses whose line ==
   previous demand line + 64. That is next-line's exact ceiling, so **FDP's entire
   addressable market is `IC_MISS - IC_MISS_SEQ`.** One registered compare at `s1Disp`.
3. **`PF_LATE`** — cycles a demand fetch was held on an in-flight speculative fill
   (`heldOnSetBusy` already exists, registered). Without it, high coverage hides "right
   line, too late", whose fix is depth, not direction.
4. Only if 1-3 say *direction*: a two-pass Verilator oracle (record the demand-line
   sequence, replay with the prefetcher fed that future k lines ahead) bounds what ANY
   FDP can claim — **driven by ROM boot + Finder, not the 25 kernels**, on `-cb`.

**Why FDP is not next:** 96% of mispredicts are predictor COVERAGE holes (38.7%
relative-unconditional with no predictor, 34.5% returns, 22.7% indirect; only 3.9%
direction). FDP follows the same predictor, so it is wrong exactly where fetch is wrong
and degenerates to next-line precisely where next-line already fails. Fix coverage
first — larger lever (~20% of cycles) and the precondition for FDP to have a correct
stream. ⚠️ And `pfNextPa_reg[*]/CE` — the prefetch frontier's clock enable — is already
the destination of the core's **ten worst setup paths** (−1.524 ns, 24 levels,
`FetchAlignPlugin.scala:68-78`), i.e. the most FMax-hostile place in the design to add a
second frontier.

## Lever 9 — BRAM as a mux, not just as storage (owner, 2026-09-27)

**"we can start using more bram where we can to remove load from LUTs" + "bram is also
an implicit mux".** The second half is the point: a BRAM read port IS an addressed
multiplexer, so moving a structure into BRAM absorbs its SELECTION logic as well as its
storage. That meets the census head-on — the core is **49% LUT6 selection logic and
only 1.2% carry**, so fan-in and payload width are where the LUTs are, and BRAM buys
selection with a hard macro.

⚠️ **URAM is entirely spoken for by the L2C** — the budget is RAMB36. Core currently
uses **36**; whole design 172.

Measured inventory (from `lane100_br5`):

| structure | LUTRAM | total LUT in scope | RAMB36 |
|---|---:|---:|---:|
| `RegFilePluginInt_logic_ram` | 4,848 | **8,050** | 0 |
| `DecodeStage_logic_queue` | 1,024 | 3,976 | 0 |
| `RegFilePluginFp_logic_ram` | 504 | 1,123 | 0 |
| socket_core total | 10,322 | 95,813 | 36 |

The ~3,200 logic LUTs above the LUTRAM count in the int PRF scope are its read/bypass
mux network — that is the part the "implicit mux" argument reclaims, so the prize
there is nearer 8k than 4.8k.

**The binding constraint is not storage, it is read latency.** The core has **44
`readAsync` sites** against 15 `readSync`, plus 9 explicit `ram_style="distributed"`
and 4 `"block"`. `RegFilePlugin` is `ram.readAsync(r.addr)` with a bypass mux over it —
async read is exactly why it is LUTRAM, and BRAM/URAM cannot do async. Converting one
means **the address must be produced one cycle earlier**, i.e. reading at SELECT rather
than at issue. That is a pipeline change, not an attribute.

Order of work, cheapest first:
1. **Free:** the 6 `readSync` Mems with no `ram_style` (`IcachePlugin` x5,
   `FpCheapPipe` x1) — already synchronous, so forcing `block` has no pipeline impact.
2. **Free-ish:** audit the 9 explicit `"distributed"` choices — deliberate, or inherited?
3. **Real work:** hoist an address a stage to convert a `readAsync` consumer. Start with
   the ROB payload reads at `h0`/`h1`, not the PRF.
4. **Speculative synergy, unproven:** the speculative-wakeup machinery already exists to
   present the PRF read address one cycle early. If generalised from a speculative trick
   to a structural "read at select", a sync BRAM read lands exactly where today's async
   read does. Attractive, and entirely unvalidated.

⚠️ BRAM is not free in every dimension: the I-cache line width has a **BRAM cliff at
397 bits** — 384 is the last free width and a 9th predecode bit costs +2 tiles. Check
width efficiency before widening anything into a tile.

## Lever 10 — age-matrix IQ, judged on FANOUT not area (reopened 2026-09-27)

I closed this on the wrong criterion. The measured **+3,487 LUT (+2.25%)** is real and 4x
the ~800 LUT noise floor — but the change was never proposed as an area win. Its claim:

- **`push.fire`'s direct flop-bit fanout inside the IQ drops 2,746 -> 994** (-64%),
  split across 8 per-line enables
- **IPC is bit-identical**, so there is no performance risk to weigh against the cost
- FFs are free here (~21% utilised); it spends +406
- and on THIS design **area anti-correlates with WNS**: the area-only netlist was 5,048
  LUT SMALLER than m20 and closed WORSE. Congestion is pin DENSITY, and Vivado's own
  remedy (`CELL_BLOAT_FACTOR`) makes designs BIGGER. So +3,487 LUT is not evidence
  against a congestion lever — it is the expected shape of one.

**It is UNMEASURED on its own claim, not dead.** The 100 MHz lane cannot judge it: both
arms close trivially there, so lane WNS says nothing about the tight constraint.

⚠️ **And a naive 200 MHz WNS A/B is also weak**: netlist sensitivity on this design is
**±0.4 ns**, and the two arms ARE different netlists, so a single WNS pair cannot be
distinguished from that sensitivity unless the effect is large. What would convince:
1. **post-place congestion** (`report_design_analysis -congestion`, per-region levels) —
   directly the quantity the fanout claim is about, and far less lottery-prone than WNS;
2. **the specific endpoints** — does the IQ select cone's slack improve, and does
   `push.fire` leave the worst-path list? A targeted improvement is credible where a
   global WNS delta is not;
3. WNS only as corroboration, never as the sole evidence.

Running: `build/iqam200_{off,on}` at 200 MHz, congestion-first.

## Measured DEAD — do not revisit

- **Memory renaming / store-to-load bypass** — the satisfiable loads are the machine's
  FASTEST (a full SQ overlap forwards at P4, never touching the cache). ROM intra-BB
  1.31%; pure rename 0.019%. The only STALLING shape (partial overlap) is 0.06%.
- **Early load address resolution** — a statically resolvable address is by construction
  never the head of a dependence chain through its address. `frame-loads-a6` hits IPC
  1.000. ROM is 31.4% A7/A6-relative and it still cannot pay.
- **ALU-class speculative wakeup** — flat on 14 kernels AND on the strcmp shape.
- ~~**Age-matrix IQ**~~ — **MOVED BACK TO OPEN 2026-09-27, see lever 10.** It was listed
  dead on a +3,487 LUT measurement, but **area was never its claim** — fanout was, and on
  this design area has ANTI-correlated with WNS across three builds.

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
6. **The LUT noise floor of this flow is ~800 LUT** — measured 2026-09-27: a change of
   **+1 assign and +0 flop bits** produced an **803 LUT swing** between two builds
   (`lsw0` 146,118 vs `lsw1` 145,315). Do not claim an area result below that. It also
   recalibrates the ledger: the age-matrix's +3,487 is 4x the floor and real; the branch
   flags' +1,170 is only ~1.5x and weaker than first presented.
7. **Elaboration flop counts are NOT an area measurement.** Three times now they have
   predicted flops accurately and LUTs badly (age matrix: predicted "a wash", measured
   **+3,487 LUT**). Use them as a sanity check only.
8. **Check the bench even CONTAINS the shape.** Four coverage holes found in one week:
   zero A6/A7 operands, zero store→load pairs, zero load→compare→branch chains, and 25
   total suite mispredicts against the board's 24.4 MPKI.
