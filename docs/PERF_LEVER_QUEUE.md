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
ITLB walks ~3% — ~37% in the front end, against 6.1% D-cache and 6.7% walk.

⛔ **CORRECTED 2026-09-28. The "ITLB ~11%" in this table was WRONG and self-inconsistent**
— it sat next to a 6.7% walk-stall figure it exceeded. `OFF_PERF_STALL_WALK` is
`perfLvlDtlbWalk || perfLvlItlbWalk`, the **UNION of BOTH walkers**
(`DebugCtrlPlugin.scala:712-714`), so 6.7% caps the whole I+D walk bucket and an 11% ITLB
share is arithmetically impossible. Triangulated three ways, the real figure is **~3.0% of
all cycles (3.65% on Finder idle)**: 6.7% ÷ (10.25 ITLB + 12.63 DTLB walks/kinst) = **17.2
cycles/walk**, against a structural floor of ~14-15, and it **closes** —
(10.25+12.63) x 17.2 = 389 cycles/kinst versus the 392.8 that 6.7% reports, so the two
walkers barely overlap and the bucket is fully accounted for.

This also retracts the in-tree "220+ cycles of completely dead frontend" per walk: that
assumed a private AXI port and 70-cycle DDR, but the walker has been a `DcacheService`
client since 2026-09-04. **`OFF_PERF_ITLB_WALK` (0x010A4) is in every bitstream and has
never been read** — one register read settles it on silicon.

⛔ **CORRECTION 2026-09-27 — the "ITLB walks ~11%" figure above is REFUTED by this very
session's own counter, and the honest number is 3.0-5.4%.** `OFF_PERF_STALL_WALK`
(0x010B0) is **`perfLvlDtlbWalk || perfLvlItlbWalk`** (`DebugCtrlPlugin.scala:712-714`)
— the UNION of the two walkers' busy cycles, not the D side alone. It reads **6.7%**, so
that one number is a hard cap on the WHOLE I+D walk bucket and an 11% ITLB share is
arithmetically impossible. Combining it with the measured DTLB rate (12.63 walks/kinst)
and a trace-measured ITLB rate (10.25/kinst, see lever 15) bounds a walk at **17.2
cycles of walker-busy if the two walkers never overlap and 31.1 if they always do**,
putting the ITLB's own share at **3.0% (no overlap) to 5.4% (full overlap)**. That also
retracts the walker design doc's "220+ cycles of completely dead frontend" for the
board: that figure assumed a private AXI port and the bench's 70-cycle DDR, and since
2026-09-04 the walker is a `DcacheService` client whose descriptor reads hit L1D/L2.
**`OFF_PERF_ITLB_WALK` (0x010A4) exists in every bitstream and has never been read** —
one register read settles this outright.

✅ **AND THE ARITHMETIC CLOSES, from a third independent direction.** A walk's duration is
bounded from below by structure: 1 cycle miss capture + 1 capture→start + 1 IDLE→RD_ROOT +
3 × [1 cycle `m2sPipe` walker-request stage + **2 cycles cmd→rsp**, the exact, separately
measured latency of a walker descriptor read through the D-cache load port (2026-09-05;
`LsEuPlugin.scala:3471` demuxes the walker response combinationally off
`dcache.loadRsp.valid`)] + 1 FINISH + 1 IDLE→hit = **≈14-15 cycles with zero arbitration
wait and every descriptor in L1D**. The board-derived figure is **17.2**, two to three
cycles above that floor — exactly the room arbitration against CORE-LS and the other
walker needs. And at 17.2 the union closes on the sum: (10.25 + 12.63) × 17.2 = **389
cycles/kinst against the 392.8 the 6.7% counter reports**, i.e. the two walkers barely
overlap and the whole measured walk bucket is accounted for. **So take the LOW end: the
ITLB is ~3.0% of all cycles (3.65% on Finder idle), not 5.4% and certainly not 11%.**

**Almost all IPC work in this campaign has been back-end.** It optimises the ~8%
where the ROB is busy and stuck.

### Replicated 2026-09-27 on `lane100_lsw0`, with the phase verified from the SCREEN

The earlier "Dhrystone only" column was carved out of a mixed session. This one is a
**Dhrystone-only window by construction**: Speedometer's Benchmark Mix dialog set to
`Dhrystones Iter. = 25` with every other test at 1, sampled as cumulative counters and
differenced across the plateau. Attribution is not a counter fingerprint — a captured
frame mid-window shows the red run-marker on `Dhrystones/sec` at `Itr. 16`, every other
row still holding its stale value.

| | earlier session | **`lsw0`, verified** |
|---|---:|---:|
| IPC | 0.3613 | **0.3532** |
| mispredicts / kinst | 21.41 | **25.53** |
| flushes / kinst | — | **25.57** |
| retired BTB-eligible branches / kinst | — | **210.99** |
| I-cache misses / kinst | 0.19 | **0.187** |
| D-cache misses / kinst | 1.17 | **1.147** |
| DTLB / ITLB walks per kinst | 0.04 | **0.068 / 0.061** |
| retire stall | 52.22% | **52.58%** |
| D-cache stall / walk stall | 0.83% / 0.08% | **0.80% / 0.09%** |

21.64 s wall, 2,111,618,465 cycles, 745,871,262 instructions, **97.6 MHz effective** —
which independently confirms the clock. Idle reference on the same bitstream: IPC 0.1211.

Three things this pins down:

1. **The memory system is not the Dhrystone lever.** D-cache stall + walk stall together
   are **0.89% of cycles**. Anything justified by Dhrystone D-cache behaviour is chasing
   under one percent.
2. **Mispredicts are.** 25.53/kinst at ~12.9 cycles of recovery each is **~0.33 cycles per
   instruction of the 2.83 actually spent — about 11.6% of all cycles**, thirteen times the
   entire memory system. `flushes ~= mispredicts` (25.57 vs 25.53) means essentially every
   mispredict costs a whole-ROB squash.
3. **Dhrystone is branch-dense**: 211 BTB-eligible retired branches per kinst — better than
   one in five instructions — mispredicting at **12.10%** (upper bound, see the denominator
   caveat in the `perf` output).

⚠️ The **KWhetstones** row reads **0.000** on every run. That is the FPU benchmark returning
nothing, not a fast result, and it is consistent with the known FP defects. Unmeasured —
flagged here so no one reads the Average as an all-round figure.

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
| 8 | `alignedDone` → `alignedEarlyWbFire` | ⛔ **DEAD** | captured its bucket exactly (+524 grants = 528 OoO writebacks); gap≥2 across all 14 kernels **~3,900 → ~30**; cycles **bit-identical at both seeds**. With lever 6 the announce now reaches W−1 for essentially every hit — **the ceiling — and nothing moves.** |
| 9 | BRAM as an implicit mux | 🔄 in flight | 10,322 LUTRAM in the core against 36 RAMB36; constraint is read latency, not storage |
| — | LS OoO park + recovery | ✅ **gated 396/396**, `68a89f88` | `orderRedirects` **0 → 7**, ~20.9 cyc each, **cost ONE flop bit**; lock-step reds 22 → 17. ✅ the third blocker is **ROOT-CAUSED, FIXED AND CORPUS-GATED** (`7dff52b7`/`bcff067c`, `perf/ooo-ls-issue`): the barrier tested store **readiness** where the invariant is SQ **occupancy**, so a store held ineligible by `intraMacroOk` barriered nothing — directed control **3/6** -> fix **9/0**, and at FULL CORPUS SCALE the gate is **CLOSED IN BOTH DIRECTIONS**: knob-OFF control and knob-ON+fix both **1,010 / 12 with IDENTICAL failure sets, name-for-name null delta**, 87 sweep registrations each so neither side is vacuous. Three-way attribution complete for all six: **pass OFF / FAIL with knob+readiness / pass with knob+occupancy** — the knob is the trigger, the one term is the cause, nothing else moved — all six decisive registrations pass under **both** the copyback and mmuwalk postures (prior work: knob-ON-with-defect was 1,004/18 with those six red). Non-vacuity confirmed: 87 sweep registrations ran and each of the six was located by name. All three blockers now addressed; **the relaxation has no known unfixed blocker, which is NOT "ship it"** — knob stays default-OFF, now pending MEASUREMENT rather than pending a defect. Corpus + lock-step gates running |
| 11 | store-queue congestion (`sqNarrowDrainMerge`) | ✅ **gated 396/396**, `59ecd9a0` | ~400 LUT, **2,123 RTL lines deleted**; IPC **bit-identical, 34 kernels x 2 seeds**; corpus + lock-step identical name-for-name. ⛔ **module CEILING reached** — the whole SQ is 4,722 LUT ~ 5% of socket_core, so nothing confined to it can move congestion level 5 |
| 12 | PRF write/read port merge (`PINS_PRF_FMAN_SHARE` + `PINS_PRF_SLOWREAD`) | ✅ **gated 396/396 both arms** | crosses the LVT `coreCount` step **only in combination**: 96 -> **80 cells (-21.6%)**, **-1,112..-1,168 LUTRAM**. FMAN deletes one of six write-address broadcasts outright = **-16.7% of the `ADDRH` sink pins** |
| 13 | slot-1 DBcc deferral (`deferSlot1Dbcc`) | 🔄 **gated 400/400**, `881b9ec3` — IPC measured, corpus/lockstep/area OUTSTANDING | on `br-dbcc-2` (two branches per 8-byte window): mispredicts **1074 → 14 (−98.7%)**, cycles **14,958 → 5,232 (−65.0%)**, IPC **0.137 → 0.391**; `cond-dir-nopred` **610 (57%) → 1 (7%)** confirms the mechanism directly. ⚠️ `br-dbcc` (ONE branch per window) is near-inert (3→2) — the FTB already holds it and `slot1WouldFtq` defers it, so the market is **two-control-transfers-per-window only**. ⛔ RTD/RTE deliberately excluded: slot 0 cannot predict them either (`s0IsReturn` is RTS\|\|RTR), so deferral costs a slot for nothing. ⛔ **Dynamic frequency in real code is UNMEASURED** — 3.41% is a STATIC ROM census; the microbenchmark bounds the per-occurrence win, not the population |
| 14 | explicit `DBcc` loop predictor (owner, 2026-09-27) | 📋 QUEUED — **gated behind #13** | **80.8% of ROM DBcc are `DBF`/`DBT`: pure counted loops, outcome is the counter alone.** Removes the once-per-loop EXIT mispredict gshare cannot get |
| 15 | ITLB victim buffer (32-entry, `itlbVictimEntries`) | ✅ **FULLY GATED** `perf/itlb-victim-buffer` `626cd6b1` — `test-fast` **396/0/2** (master lineage, NOT 400), mmu **66/66**, lockstep **690/12/1** and corpus **1010/12** both **identical name-for-name**, OFF netlist byte-identical | ITLB walks **10.25 → 2.68/kinst (−73.9%)** aggregate, Finder idle **17.89 → 1.20 (−93.3%)** = the infinite-ITLB floor. **≈2.2% of session cycles, ≈3.4% of Finder-idle.** Area: **+1,451 FF, LUT −229 (noise, inside the ~800 floor)**, LUTRAM/BRAM/DSP unchanged — flops are the spare resource here (LUT-bound 78.8%, FF 24.5%). OFF netlist **byte-identical**. ⚠️ measured on a real 7.5.3 MAME trace (8.45M insts), because `IpcBenchSpec` has **ZERO** ITLB walks. ✅ **The corpus gate is NON-VACUOUS and that had to be proven**: 39 `ported-sweep-mmuwalk` programs ran (`ForceMmuWalkCopyback`, all four TTRs zero, real identity page tables) so every cold-TLB fetch performs a genuine root→pointer→page walk — **all 39 pass in both arms and none of the 12 reds carries that prefix**. That posture is the ONLY thing in the whole regression suite that walks the ITLB; without it the ON arm would have been an exact null |
| 16 | sectored 64 B L1D lines (`CPU_DCACHE_SECTORED`) | ✅ **gated 396/396 + corpus/lockstep identical name-for-name**, `perf/l1d-sector64` | **+8.51% / +8.67% / +8.44% bandwidth** (16k two seeds, 64k control): L2 read transactions **3.99x / 3.998x fewer** with compulsory misses **IDENTICAL**, so nothing was traded away. Storage **−67.4% (3,840 bits)**. OFF netlist structurally identical. ⛔ **Prediction was +62–77%, measured +8.5% — wrong by 8x**, and the pre-recorded falsifier fired verbatim: a transaction costs **~2 cycles, not ~16**. **After this slice the copy loop is still at 4.4% of the 128-bit bus** (~45 cyc / 16 B for ~6 instructions) — **load→store dependency bound through a ONE-MSHR D-cache, not bus bound. Do not price the next D-side lever off transaction count; attribute those 45 cycles first.** Found **3 data-loss defects** (burst write-hold x2, multi-hot tag). ✅ **AREA MEASURED**, matched 100 MHz lane pair `sect0`/`sect1`: total LUT **146,097 -> 146,034 (-63)**, logic LUT 95,935 -> 95,906 (-29), **FF 90,781 -> 90,622 (-159)**, BRAM **168 unchanged** — i.e. **AREA-NEUTRAL**, everything far inside the ~800 LUT floor. The -67.4% tag/state storage saving does NOT appear as a large LUT drop because LUTRAM packs 32-64 bits per LUT (~124 LUT equivalent), partly offset by the new burst-fill and eviction-walk FSM. ⚠️ WNS 0.041 -> 0.089 is NOT a result: this lane pins `ROUTE_DIRECTIVE=Default`/`POST_ROUTE_PHYSOPT_MAX=0` and its own header says WNS is not to be read from it |
| 17 | `MOVE16` as a single wide 16-byte access | 🔄 **MEASURED, NOT BUILT** — oracle on `perf/move16-wide`; semantics established from the M68040UM | See the dedicated section below. **The brief's premise was false**: MOVE16 as microcoded today is **already +35.3%** faster than the `move.l` copy loop, so the lever is **+10.3%**, not 4x |

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

## Lever 15 — ITLB victim buffer, and the trace method that sized it (2026-09-27)

**The ITLB had never been touched by this campaign, and it could not be: `IpcBenchSpec`
produces exactly ZERO ITLB walks in every kernel** — the MMU is off except the two
`copybackDtt` ones, and those configure match-all transparent translation, so no kernel
ever walks. The walker design doc calls this out as a blocking prerequisite and expects
the answer to require building an MMU-enabled harness. It does not: the real workload is
available without one.

### How it was measured — a real System 7.5.3 instruction trace, and what validates it

MAME's `macqd700` with the project's `hd753.chd`, traced through the debugger's
per-instruction hook (`manager.machine.debugger:command("trace …")` armed from Lua at a
chosen sim time). Three phase-attributed windows of **1 emulated second each**, 8.45 M
instructions total: a boot window (t=10), the Finder coming up (t=21.5, `CurApName`
flips to `"Finder"` at t=21.48) and the Finder idle loop (t=40).

Machine state read live out of the running guest at t=40, which is what makes the model
match the array:

| | value | consequence |
|---|---|---|
| `TC` | **0x0000C000** | E=1, **P=1 ⇒ 8 KB pages**, so the ITLB key is `vpn>>1` (`ItlbPlugin.tlbKeyOf`) |
| `SRP` / `URP` | 0x003FDC00 / **0** | supervisor-only translation; **URP is never rewritten after boot, so `rootWrite`'s whole-ATC flush is INERT on this workload** |
| `ITT0/1`, `DTT0/1` | **all 0** | no TTR ever hits ⇒ **every instruction fetch consults the ITLB** |
| `CACR` | 0x80008000 | DE+IE |

⚠️ **The trace is a LOWER bound on fetch-side pressure, because MAME is in-order and
non-speculative.** Validated against the one I-side quantity the board has measured
(`lane100_icpf`, Finder idle, IC_MISS 127.93/kinst prefetch OFF → 61.24 ON):

| model of our 16 KiB/4-way/64 B L1I, idle1 window | I-miss/kinst |
|---|---:|
| demand only | 46.82 |
| demand + `cpusha both` invalidates | **71.39** (board OFF: 127.93) |
| + next-line prefetch + invalidates | **41.77** (board ON: 61.24) |
| modelled prefetch benefit | **−41%** (board: **−52%**) |

So the method lands within ~1.8x on the absolute rate and within 11 points on the
prefetcher's *relative* benefit. The residual is wrong-path fetch (169 mispred/kinst at
idle) plus fetch run-ahead. **Trust its ratios, not its absolute rates.**

### 🎯 NEW, and it reframes the whole I-side: the workload INVALIDATES BOTH L1s constantly

Counted in the traces, per million instructions:

| | Finder idle | boot | Finder launch |
|---|---:|---:|---:|
| **`cpusha both`** (pushes AND invalidates L1I+L1D — single ROM site 0x40885032) | **214.8** | 100.1 | 108.1 |
| `cpushl` | 421.2 | 301.7 | 707.7 |
| **`pflusha`** (wipes both ATCs — single ROM site 0x40803F80) | **38.3** | 150.4 | 263.8 |
| `ptest` | 213 | 160 | 411 |

**One whole-cache invalidate every ~4,650 instructions at idle**, and it accounts for
**+24.6 I-miss/kinst** — over half of the demand-only model's misses. Two consequences:

1. A large part of the board's I-miss residual is **compulsory-after-invalidate**, not
   capacity and not direction. That matters for lever 5: `IC_MISS − IC_MISS_SEQ` counts
   those first-touches as "non-sequential market", and FDIP cannot make a line that was
   just invalidated appear any earlier than the predictor reaches it.
2. ⛔ **`icMaintFlush` is being exercised ~215 times per Minst on the real workload** —
   and it is the path whose incompleteness is already a recorded defect with **zero sim
   coverage** (`f10f1ce5`, wired only in `FullCoreSynth`). This is not a rare corner.

Also settled cheaply, both as NEGATIVE results: the guest uses **only `pflusha`**, never
`pflush (An)`/`pflushn`/`pflushan` (all 136/406/580 occurrences disassemble as `pflusha`
from one site), so `OperationDecoder`'s collapse of the whole PFLUSH family onto
`SysKind.PFLUSHA` **costs nothing here**; and URP is never rewritten, so `rootWrite`'s
flush costs nothing either. Both were plausible over-flush levers; both are dead.

### What the array actually does, and why a victim buffer rather than a bigger array

Model mirrors `Tlb.scala` exactly (key `vpn>>1`, `bank=key[0]`, `set=key[2:1]`, 4 ways,
round-robin advanced only on allocation, `atcFlush` on every `pflusha`). 8.45 M
instructions:

| geometry | walks/kinst | vs shipping |
|---|---:|---:|
| **32e/4w/2b RR — SHIPPING** | **10.254** | — |
| 32e/4w/2b LRU | 9.440 | −8% |
| 32e/8w/2b RR (double associativity) | 9.114 | −11% |
| index XOR-fold `key^key>>3` (≈3 LUT2, free) | 8.668 | −16% |
| 64e/4w/2b RR (**the real MC68040 ATC size**) | 4.778 | −53% |
| 32e + **24**-entry victim buffer | 2.815 | −72% |
| **32e + 32-entry victim buffer** | **2.677** | **−74%** |
| 32e + 48-entry victim buffer | 2.672 | −74% (saturated) |
| 128e/4w/2b RR == infinite ITLB | 2.672 | −74% |

Composition of the 86,627 walks: **183 compulsory (0.2%)**, 22,393 PFLUSHA refills
(25.8%), **64,051 capacity/conflict (73.9%)**.

⚠️ **PER PHASE, because the aggregate rests on one of them** (measurement rule 3):

| phase | shipping | + victim 16 | + victim 24 | **+ victim 32** | PFLUSHA/Minst |
|---|---:|---:|---:|---:|---:|
| boot (t=10) | 3.473 | 2.950 (−15.1%) | 2.942 (−15.3%) | **2.942 (−15.3%)** | 150.4 |
| Finder launch (t=21.5) | 6.254 | 4.803 (−23.2%) | 4.751 (−24.0%) | **4.739 (−24.2%)** | 263.8 |
| **Finder idle (t=40)** | **17.887** | 5.983 (−66.5%) | 1.520 (−91.5%) | **1.198 (−93.3%)** | 38.3 |
| aggregate (equal instruction weight) | 10.254 | 4.707 | 2.815 | **2.677 (−73.9%)** | — |

At the corroborated **17.2 cycles per walk** that is **3.0% of all cycles** on the mixed
session and **3.65% on Finder idle**, of which the buffer recovers **73.9% / 93.3%** —
i.e. **≈2.2% of session cycles and ≈3.4% of Finder-idle cycles**, for a measured **+1,451
flops and no measurable LUTs**, and for literally nothing at all while the flag is off (the
OFF netlist is byte-identical).

**The aggregate −73.9% is carried by the idle phase, and that is mechanism, not luck:**
the buffer is flushed by PFLUSHA along with the array, so where PFLUSHA is frequent
(boot, app launch) most walks are flush refills it cannot help, and where PFLUSHA is rare
(the steady-state idle loop) the walks are conflict-dominated and it removes almost all of
them. So the value is concentrated in **steady-state interactive behaviour — which is
where the machine is actually used** — while boot and launch still get 15-24%. Quote the
phase, never the aggregate alone.

Three things decide the design:
- **A 32-entry victim buffer reaches the infinite-ITLB floor and beats doubling the array
  outright** (−74% vs −53%), because it is fully associative where the array's 8 rows are
  the problem.
- **FIFO replacement measures the same as LRU at every size** (2.677 vs 2.676 at 32), so
  the buffer keeps no age state: one ring pointer.
- **It is probed only on an L1 MISS**, so it adds nothing to `fetchPc → ITLB lookupVpn →
  Icache s0Ppn` — this core's 22-level worst path. A promote costs the already-stalled
  fetch **one cycle** (the next lookup is an ordinary array hit) instead of three
  dependent descriptor reads; deliberately NOT a new leg on the response mux.

Unlike the speculative ITLB prefetch scoped in
`docs/superpowers/specs/2026-09-03-walker-dcache-routing-revalidation-and-itlb-prefetch-design.md`,
**every §9.3 side-effect (S1-S10) falls away**: each entry was installed by a real demand
walk that already set U in memory, so a promote is a cache-to-cache move and not a table
search. No speculative bit, no new fault surface, no fifth walker requester, no `LdRspTag`
widening. That doc's §9.5 option (c) — "reuse the existing 1-entry sticky walk-result
latch" — is separately **measured worthless**: adding it to the model changes the walk
count by **zero**, because the array is refilled in the same cycle the latch is.

### State, gates, and what it is NOT

- `ShippingCoreConfig.itlbVictimEntries`, **default 0**, `ITLB_VICTIM=<n>` overrides;
  printed in the `SHIPPING_CONFIG` provenance line. Threaded into both tops AND into
  `FuzzDut` / `ExecuteLockStepSpec` / `CoreBenchHarness`, so the corpus can exercise it.
- **OFF is byte-identical** to the pre-change netlist: generated both and diffed
  `M68kSocketTop.v` — 0 differing lines after normalising SpinalHDL's source-line-derived
  signal names (`when_…_lNNN`), which encode no logic. `Tlb`'s new eviction report is
  itself behind `reportEvictions`, so a TLB with no buffer behind it grows no ports.
- ON adds **~1,509 flop bits** (32 × 46 of state + a 5-bit ring pointer) and **zero**
  hardware counters — `vic.promote` is `simPublic` and tests count it in `onSamplings`.
  ✅ **Measured at 100 MHz (synth-only, both arms, provenance round-tripped):
  socket_core FF +1,451 and total LUT −237, i.e. NO MEASURABLE LUT COST** — the LUT delta
  is negative and well inside the ~800 LUT floor, LUTRAM/BRAM/DSP all unchanged. See
  `docs/PERF_AREA_LEDGER.md`'s synth-only sub-table, including why the per-module rows of
  the two reports must not be read against each other.
- **Ported corpus (`PortedM68kOooSpec`): 1010 ok / 12 failed in BOTH arms, IDENTICAL
  name-for-name** — the known FMOVEM.X family, `mmu_atc_write_hit_sets_modified`,
  `exc_partial_macro_move_mem_mem` and `rom_scc_mmio_btst_dbf_timeout`, all already in the
  defect register. ✅ **And it is not a null test of this lever: 39 `ported-sweep-mmuwalk`
  programs ran**, i.e. the `ForceMmuWalkCopyback` posture with all four TTRs zero, real
  identity page tables and `TC.E`+`CACR.DE|IE` on, where every cold-TLB instruction fetch
  runs a genuine root→pointer→page walk. **All 39 pass in both arms**, and not one of the 12
  reds carries the `ported-sweep-mmuwalk` prefix. That posture is the only thing in the
  regression suite that walks the ITLB at all, so it is what makes this gate informative.
- **`ExecuteLockStepSpec`: 690 ok / 12 failed / 1 ignored in BOTH arms, and the 12 failures
  are IDENTICAL NAME-FOR-NAME** (not merely equal in count) — 11 of the documented
  `odd-ssp` "level-1/level-7 IRQ at every boundary of the LINK #-75 stretch" family plus
  `lock-step: LSU NZVC wakeup preserves alternating flags and partial Scc destinations`.
  Those are the known pre-existing reds (uninitialised registers vs a randomised PRF), and
  since the OFF netlist is byte-identical to master the OFF arm *is* master's result, which
  makes it a sound reference for the comparison.
- `ItlbVictimSpec` is a **paired** test: the `victimEntries = 0` arm must show the walk
  (fail-before) and the ON arm must show zero walks, one promote, and **the same PPN**;
  plus PFLUSHA must clear the buffer or the re-touch would be answered out of the
  pre-flush address map.
- ⛔ **This lever cannot be measured on Dhrystone.** The board's Dhrystone-only window
  walks the ITLB **0.061 times per kinst**. Its workload is boot and the Finder, and the
  metric that moves unambiguously is `OFF_PERF_ITLB_WALK` (0x010A4), predicted 10.25 →
  2.68 per kinst — a 3.8x change, far outside any noise floor. Read that counter with
  `IC_MISS`/`MISPRED` alongside so the phase can be shown to match.

## Lever 5 re-sized 2026-09-27 — FDIP's market is real, but two thirds of it is software wiping the cache

Same real-7.5.3 trace and method as lever 15 (see its section for the validation and the
lower-bound caveat). Modelling our L1I exactly — 16 KiB, 64 sets x 4 ways x 64 B, RR,
plus the in-tree next-line prefetcher (`seedPfWindow`, depth 5, clamped to the 4 KiB
page) — and, crucially, **including the workload's own `cpusha both`**, the Finder idle
demand-miss stream decomposes as:

| bucket | share of demand misses | of which SEQ |
|---|---:|---:|
| compulsory (line never touched in the window) | 0.2% | 0.8% |
| **post-invalidate** (line was RESIDENT when a `cpusha both` wiped the cache) | **28.5%** | 1.8% |
| replacement (capacity / conflict) | 71.3% | 0.0% |
| **`IC_MISS_SEQ` equivalent, all buckets** | **0.5%** | — |

**That 0.5% independently corroborates the board's 4.5%** from a completely different
instrument, on the number lever 5's go/no-go rests on: the residual really is
overwhelmingly non-sequential, and next-line structurally cannot reach it.

**But the capacity sweep says the replacement bucket is not what it looks like.** Same
stream, same invalidates, prefetch on:

| L1I geometry | I-miss/kinst | vs shipping |
|---|---:|---:|
| **16 KiB 64s x 4w RR — SHIPPING** | **41.77** | — |
| 16 KiB 64s x 4w **LRU** | 48.67 | **+16.5% — LRU is WORSE than round-robin here** |
| 16 KiB 32s x 8w RR | 41.16 | −1.5% |
| 16 KiB 16s x 16w RR | 39.94 | −4.4% |
| 32 KiB 128s x 4w RR | 34.23 | −18.0% |
| 32 KiB 64s x 8w RR | 32.17 | −23.0% |
| 64 KiB 256s x 4w RR | 29.34 | −29.8% |
| **256 KiB — near-infinite** | **28.04** | **−32.9%** |

**A near-infinite I-cache removes only a third of the misses.** The floor of 28/kinst is
the workload re-fetching its own working set after each of its 771 wholesale invalidates
(one every ~4,600 instructions; ~129 distinct lines ≈ 8 KiB touched per interval). So:

- **~67% of L1I demand misses at Finder idle are software-driven wholesale-invalidate
  refills.** No cache geometry removes them, and FDIP cannot remove them either — it can
  only fetch them EARLIER.
- **~33% are capacity/conflict**, and associativity is nearly worthless against them
  (8-way −1.5%, 16-way −4.4%) while SIZE is not (32 KiB −18%). ⚠️ The caches are already
  32 of 35 RAMB36, so 32 KiB needs the BRAM budget checked first.
- **Round-robin beats LRU by 16.5%** on this stream. Do not "improve" the replacement
  policy without measuring it.

### Why this makes an FDIP number LESS worth producing right now, not more

A 68040 `CPUSHA` invalidates the CPU's own L1s; it does not invalidate the SoC's 2 MB L2.
So the dominant miss population — the post-invalidate refills — is **L2-resident and
cheap**, and fetching it earlier is worth far less per miss than the DRAM latency a
sizing run would implicitly assume. The value of FDIP therefore hinges on the **L2
hit/miss split of `IC_MISS`**, which is:

- **not measurable in sim today** — `AxiMemModel`'s L2 is an unbounded never-evicted set,
  so every line hits at 5 cycles after first touch and only compulsory misses see
  `dramCycles`; and
- **not measured on the board** — lever 3 added `IC_MISS`/`PF_*`/`IC_MISS_SEQ`/`PF_LATE`
  but nothing splits them by L2 residency.

Add that split to lever 5's sizing plan (it is not in items 1-4 today). Until it exists,
an FDIP sim IPC figure is measured against a memory system that is simultaneously too
kind (infinite L2) and too harsh (`crossbarSingleOutstanding = false` overstating
overlap, against a real crossbar that is single-outstanding per master port) — two errors
in opposite directions that must not be assumed to cancel.

⛔ And the standing blocker is unchanged: **#7 gates #5**, and `rasBranchRepair` is still
default OFF and unmerged.

### ⛔ THE I-SIDE DOES NOT TRAVERSE THE CROSSBAR — verified in the SoC RTL 2026-09-27

Stated because it has now been got wrong twice, in both directions, and it changes what
an I-side sizing run is allowed to assume. Verified end to end in `macqd700-soc-public`:

| stage | limit | source |
|---|---|---|
| `IcachePlugin` MSHR file | **5** (1 demand + 4 speculative, 5 distinct AXI IDs) | `IcachePlugin.scala`, `AxiIds.I_DEMAND=0`, `I_SPEC_BASE..I_SPEC_LAST=1..4` |
| CPU → fabric | **dedicated 256-bit `f_axi_*` port on `l2c`, NEVER `axi_xbar.v`** | `fpga_top_ddr.vh:141` ("never through axi_xbar.v"), bind at `:275` (task #269 "Level A"); `l2c_ctrl.v:419` |
| fetch-port AR accept | 1 active + **1-entry pre-latch** (`f_arready = !fetch_ar_pend_v`, `l2c_ctrl.v:434`) | throttles ISSUE RATE, not concurrency — the active slot frees when the burst's beats are walked into the L2 pipeline, not when data returns (`fetch_ar_act_free_c`, `:508`) |
| L2 lookup | shared with the LSU's AR, round-robin with `fetch_favor` | `l2c_ctrl.v:575,587` |
| L2 MSHRs | **8 primary, up to 4 same-line secondary each**, ARs issuable on consecutive clocks, MSHR index carried as the AXI read ID | `l2c_mshr.v:46-52` |
| MIG bridge | 8 outstanding | ditto |

**So the binding limit on I-side memory-level parallelism is the CPU's own 5 MSHRs, not
the fabric.** Consequences for any I-side measurement:

- ⛔ **`crossbarSingleOutstanding = true` is the WRONG model for the I side.** It models a
  per-master-port limiter that is not in the I path at all. It is the honest config for
  the **D** side, which *is* the crossbar's M0 master. An I-side arm run under it
  understates what the fabric allows.
- The fetch path was ALSO once 1-deep and it was already found and fixed: `l2c_ctrl.v:411-423`
  records that `f_arready = !fetch_ar_have` had *"re-serialised the CPU's 5-MSHR instruction
  prefetcher down to one fetch in flight"*, closed 2026-09-02 by the pre-latch, described
  as *"the single limiter between a 5-deep prefetcher above and 8 MSHRs / an 8-outstanding
  MIG bridge below"*. Do not re-derive that as a live limit.

**`axi_xbar.v:1340`'s "slot 2 is the CPU instruction fetch" describes a configuration no
cpu040 build uses.** When `L2C_ENABLE` is defined the xbar's M2 is tied **permanently
idle** (`m2_idle_arvalid = 1'b0`, `fpga_top_xbar.vh:247-292,447-482`), and
`synth/vivado.tcl` forces `L2C_ENABLE` on for every cpu040 build (the Makefile also calls
it non-optional, since the M2 fallback is 128-bit against `AXI_I_DW = 256`). In the
`L2C_ENABLE`-undefined fallback there is no cpu040 at all — `cpu_stub` ties `axi_i_arvalid`
low. **So `rs_state[2]` carries no traffic in any shipping build, and `cpu_rd_busy`
reflects slot 0 (the LSU) alone.** It is not "I-fetch to non-L2 space": ROM-mirror fetches
also use the direct port, folded locally by `ifa_araddr_folded`, with out-of-window
fetches getting a local DECERR from `ifetch_window_guard`.

⚠️ **Safety coupling, quoted because it is load-bearing** (`fpga_top_xbar.vh:262-285`):
because I-fetch bypasses the crossbar and **the crossbar holds the only decode of the
`0x5000_0000` peripheral window**, a speculative instruction fetch cannot reach a device
register — which is why cpu040 ships with `IcachePlugin(iFetchCanReachMmio = false)`. If
`ifa_*` is ever rerouted through the crossbar, **the CPU must be rebuilt with
`iFetchCanReachMmio = true`** or speculative fetch gains a path to read-to-clear registers.

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

## Lever 10 — age-matrix IQ: CLOSED, dead on area AND on fanout (2026-09-27)

Reopened on the fanout claim, then measured at 200 MHz with congestion reports:

| | OFF | ON |
|---|---|---|
| WNS | −0.283 | −0.273 (**+0.010 ns — 40x below the ±0.4 ns netlist sensitivity**) |
| congestion level | **5** | **5** |
| character | Short, East + West | Long, North |
| wire/cell | 2.787 / 3.161 | 2.348 / **4.023** |
| attributed to | socket_core 80%, **`LsEuPlugin_logic_sq` 16%/14%** | socket_core 56%/65%, **`LsEuPlugin_logic_sq` 32%/28%** |

**`IssueQueuePlugin` does not appear in the congestion attribution at all, in either
arm.** The change cuts IQ fanout in a block that is not congested. Level stays 5; the
hotspot just moves. Dead on both counts — do not revisit.

⛔ **Carry-forward CORRECTED 2026-09-27.** The store queue is in every congested window,
but module share is an **upper bound**, not an attribution: the widest net inside it is
`qLineB` at **≤32 sinks/bit**, while `ADDRH[0..5]` at **971 fanout** — the net actually
visible in those windows — belongs to the **integer PRF**, not the SQ. The SQ's registers
do check out as its own (1,710 predicted vs 1,713 measured), and its address-compare
matrix is 20.2% of the module against the age network's ~3.8%, so **banking is impossible**
(ring position *is* program order).

⛔ **MEASURED 2026-09-28, and it REFUTES the structural argument.** The `SQ_DEPTH=4`
calibration arm (a −45% store queue) came back and **module-local shrinking DOES move
congestion**: `report_design_analysis -congestion` run through the *identical* path on both
routed checkpoints gives **base = 2 level-5 windows (East + West), `SQ_DEPTH=4` = ZERO**
("No congestion windows are found above level 5"). The claim that "nothing confined to this
module can move congestion level 5" — mine, from the 4,722 LUT ≈ 5%-of-socket_core
arithmetic — was **wrong**, and it was wrong in the direction arithmetic usually is: it
reasoned about area when congestion is pin density.

⚠️ But it is **not a lever**, for two independent reasons. **WNS got WORSE, −0.283 →
−0.649** — and that 0.366 ns sits *inside* the ±0.4 ns post-route netlist sensitivity, so it
is not attributable either way; what is certain is that removing the congestion bought no
timing. And `SQ_DEPTH=4` is **not cycle-neutral** (three kernels use 7 of 8 entries), so it
was only ever a probe of whether the direction exists. It does. It just does not pay.

This is the third build in this campaign where **area and WNS moved in opposite directions**,
which is now a pattern rather than a curiosity. The two real targets are the **integer PRF** (9,957 LUT, 4,584 LUTRAM,
~10% of the device, ~5,800 sink pins — lever 12) and the **`DStoreCmd` merge payload**
(**−117 wires between two blocks that both appear in congested windows**).

## ⛔ "ONE LS PORT x 4 BYTES/INSTRUCTION IS THE BANDWIDTH CEILING" — REFUTED 2026-09-28

I argued that the copy loop was bound by LS-port occupancy: 16 bytes costs 4 loads + 4
stores = 8 memory ops, one LS issue port, therefore a hard 200 MB/s floor, therefore a wide
`MOVE16` (2 ops instead of 8) buys 4x and reaches the bus. **Measured, that is wrong.**

**`MOVE16` as microcoded TODAY is already +35.3% faster than the `move.l` loop** — 36.091
vs 48.819 cycles per 16-byte line — **on exactly the same 8 accesses.** Its microcode issues
**L,L,S,S** where the `move.l` loop issues **L,S,L,S**, and that interleave lets two
serialised fills overlap through the single MSHR. So operation COUNT was never the binding
term; operation ORDER was.

Consequences:

- A wide `MOVE16` is worth **+10.3%**, not 4x — and that is the honest figure, because the
  Q700 ROM's `BlockMove` **already uses MOVE16**, so the `move.l` loop is not the baseline
  real code runs. It reaches **6.1% of the 128-bit bus**, up from 4.1%. Not 800 MB/s.
- **The real ceiling is the ONE-MSHR memory system.** After a wide `MOVE16`, **76% of the
  loop is memory.** Attribution of the 48.819: **22.404 pipe + 26.415 memory** (2 fills +
  0.94 writeback through one MSHR). An issue-port model says the LS pipe is 16% of the loop;
  measured it is **46%**.
- An all-loads-first microcode reorder (zero area) was built and measured: **13.8% faster
  in-cache, 4.4% SLOWER streaming. Closed.**

⛔ **Coverage hole #7: the corpus contained ZERO `MOVE16` instructions**, so none of this was
visible. Now closed, and the in-tree comment that implied MOVE16 was slow is corrected.

### ⚠️ THREE MOVE16 SEMANTIC DIVERGENCES from documented silicon (all Musashi's)

| | documented MC68040 | this core |
|---|---|---|
| extent | UM §7.4.2: a 16-byte **ALIGNED** line; device wraps A3/A2 | raw unmasked 4 LONGs at `An+0/4/8/12` |
| `Ax==Ay` | UM note 7 + errata `MC68040DE_D` item 5: increments **once (+16)** | **+32** |
| cache | UM §4.3.3: MOVE16 **does NOT allocate** for read or write misses | allocates, and **write-allocates** |

The third matters for bandwidth: our MOVE16 pollutes the cache and performs a write-allocate
that the architecture says should not happen. The fast wide form does **not** require
breaking conformance — a 16-byte-wide access with the existing cross-line split reproduces
Musashi's exact semantics in 1 access (aligned) or 2 (misaligned) instead of 4.

## ✅ LS-OoO ISSUE: +12.7% IPC, ATTRIBUTED — and the correctness fix is FREE

Three-arm measurement, 34 kernels, two seeds, `IPC_V2=1`, all on one tree at one commit.

| arm | cycles s1 / s7 | IPC |
|---|---:|---:|
| `off` | 616,383 / 616,792 | 0.441 / 0.440 |
| `on-fixed` (occupancy barrier) | **546,950 / 547,253** | **0.497 / 0.496** |
| `on-defect` (readiness barrier) | **546,950 / 547,253** | **0.497 / 0.496** |

**−11.28% cycles, +12.7% IPC.** Per-kernel on seed 1: **16 of 33 improved >0.5%, ZERO
regressed**, largest single kernel only 14.5% of the saving. The pre-registered structural
prediction `off <= on-fixed` held on all 33.

**The instrument validated itself before being trusted.** `IQ_HOL=1` only *prints* the
`[ls-bypass]` counters (sampling is ungated, in the per-cycle loop), but that had to be
proven rather than assumed: `off +HOL` reproduced **616,383 exactly** and `on-fixed +HOL`
**546,950 exactly**, so the flag is provably print-only and its counters apply to the
numbers above.

### Attribution, from the counters

- **`orderRedirects = 0` in every arm** ⇒ the P4 park and barrier recovery contributed
  **nothing**. The gain is the relaxation itself, not the park.
- **`relaxedSelectDifferedCycles = 33,588 across 22 of 34 kernels`** ⇒ the relaxed select
  genuinely fired, heavily. This is not a lever that failed to engage.

### ⛔ THE CORRECTNESS FIX COSTS EXACTLY ZERO — and the MOB is UNMEASURED, not zero

`on-defect` and `on-fixed` are **bit-identical at both seeds**, and
`relaxedSelectDifferedCycles` is **identical (33,588) in both arms**. Two conclusions, one
of which is a non-result and must not be reported as one:

1. ✅ **Occupancy costs nothing over readiness.** The barrier fix that closed all six corpus
   reds is free. It also means the historical `+7.5%` / `−9.18%` / `−14.17%` figures were
   **NOT inflated by the corrupting bypasses** — the defect bought no speed.
2. ⛔ **The MOB's upper bound is UNMEASURED — and the three arms bracket the WRONG SET.**
   `on-defect` and `on-fixed` differ *only* for a store that is **ready but withheld** by
   `intraMacroOk`; they treat an **unready** store **identically** (both block). The MOB's
   market is precisely the *unready* store — the unresolved address. So this experiment
   says nothing about disambiguation, whatever it measures. By the pre-registered gate
   `delta_k = relaxedSelectDifferedCycles(on-defect) − (on-fixed)`, a kernel with
   `delta_k = 0` is **uninformative, not a null**. Here `delta_k = 0` on *every* kernel: the
   specific convoy (unready LS -> ready non-`firstOfInstr` STORE -> ready LOAD) never occurs
   in these 34 kernels, even though the general relaxation fires constantly. **An
   unmeasurable upper bound is not a small one.**

⚠️ Still unpriced: the barrier's recovery cost. `orderRedirects = 0` means these kernels
never exercise it, so the only number in existence remains ~20.9 cycles x N on a
device-polling microprogram.

## 🎯 NO-WRITE-ALLOCATE PRICED, NO RTL: the destination fill costs 9.7 cycles per line

Measured with the one-access-per-line oracle (`kLineOneAccess` + `WriteAllocateSizeSpec`),
same 16 KB footprint, `IPC_MEM=l2:5:60:4096`, `copybackDtt=true`:

| kernel | per line | cyc/line s1 / s17 |
|---|---|---:|
| **load-only** `move.l (a0),d1` | 1 fill, 0 writeback | 11.024 / 11.015 |
| **store-only** `move.l d0,(a0)` | 1 **write-allocate** fill, ~1 writeback | 20.710 / 20.745 |
| **DELTA** | the destination-side cost | **9.686 / 9.730** |

Seeds agree to **0.45%**. Both kernels retire an identical 12,300 instructions and make
exactly **one access per 16-byte line**, so the difference isolates the destination side.

**Against the loops that matter that is large:** 9.7 cycles is **26.8% of the 36.091-cycle
`MOVE16` loop** and 21.5% of the 45-cycle sectored+fill-forward loop — bigger than sectored
lines (+8.5%), fill-forward (+4.3%) and a wide `MOVE16` (+10.3%) individually.

⚠️ **It is an UPPER BOUND, and the gap matters.** The delta contains the write-allocate fill
**plus the writeback the dirtied line later causes**. A no-allocate store still has to get
the data to memory, so it removes the fill and *some* of the writeback, not all of it. Do
not quote 26.8% as the expected gain — it is the ceiling.

⛔ **Do not sum it with the other D-side levers**, which are measured sub-additive.

✅ **And the M68040 UM says `MOVE16` must not allocate anyway** (§4.3.3), so for the MOVE16
path this is a **conformance fix that happens to be the largest bandwidth lever measured** —
our MOVE16 currently allocates and write-allocates, which documented silicon does not.

## ✅ LS-OoO ISSUE — COMPLETE GATE TABLE (only area + board outstanding)

| gate | result |
|---|---|
| `test-fast` | **396 / 0 / 2** — exact count |
| ported corpus | **1,010 / 12 both arms, EXACT SET MATCH**, 87 sweep registrations each |
| `ExecuteLockStepSpec` | OFF **12**, ON **17** — **name-for-name, ZERO unclassified** |
| IPC | **−11.265% cycles / +12.7%**, both seeds, **16 kernels improved, 0 regressed** |
| attribution | `orderRedirects = 0` on all 34 kernels, both arms |
| instrument validity | `IQ_HOL=1` proven **print-only** (cycles bit-identical) |
| area / board | ⛔ **outstanding** |

**The lock-step 5-red delta is fully accounted for**, which is why it had to be compared by
name and not by count:

| arm | total | `odd-ssp` LINK `#-75` | NZVC non-vacuity assert | **unclassified** |
|---|---:|---:|---:|---:|
| knob-OFF | 12 | 11 | 1 | **0** |
| knob-ON | 17 | 16 | 1 | **0** |

6 ON-only / 1 OFF-only / 11 shared, **all `odd-ssp`**. That family is red in BOTH postures and
its membership is timing-sensitive **by construction** — Musashi zeroes registers while
SpinalSim randomises the PRF, so a word write leaves garbage uppers and which boot offsets
trip depends on LS issue timing. Three independent levers have now moved it in both
directions. The NZVC entry fails knob-OFF too.

✅ **Attribution is closed: the +12.7% is the IQ SELECT RELAXATION ALONE.** `orderRedirects`
is zero in every arm, so the P4 park and the barrier recovery contribute nothing to it — and
that also means the recovery's ~20.9-cycle cost is **still unpriced**, because these kernels
never exercise it.

## ⛔ THE "7% vs 70%" MOB MARKET: BOTH FIGURES WERE MISAPPLIED — the answer is a BOUND

Two numbers for the same population disagreed by 10x. Neither was wrong as measured; both
were used for something they do not measure.

- **~7% is a SINGLE-KERNEL figure generalised to a corpus.** 1,535 of 22,999 cycles with a
  store at the head, measured on one calibrated Dhrystone kernel, was used to argue the MOB
  is the wrong build corpus-wide. Withdrawn by its own author.
- **70% is a CATEGORY ERROR (mine).** `oldestStoreUnready` counts "the oldest LS slot is an
  unready store" **regardless of whether a younger ready load is blocked**. Dividing it by
  `youngerReadyLoadBlocked` divides two different predicates.

**No counter reports the intersection**, so today it can only be BOUNDED:

| arm | `oldestUnready` | `oldestStoreUnready` | `youngerReadyLoadBlocked` | intersection |
|---|---:|---:|---:|---|
| `off` | 440,366 | 184,308 | 352,419 | **[96,361 … 184,308] = 27.3–52.3%** |
| `on-fixed` | 380,077 | 134,298 | 191,933 | [0 … 134,298] = 0–70% |

✅ **The `off` arm's hard LOWER bound of ≥27.3% already refutes ~7% as a corpus figure.**
Settling it exactly needs one extra term in the `[ls-order-window]` histogram tuple —
sim-only, no RTL. (Deliberately not added mid-flight: a harness recompile between arms
would have built one arm against a different harness than its own controls.)

## ⛔ THE TWO D-SIDE BANDWIDTH LEVERS ARE SUB-ADDITIVE — NEVER SUM THEM

Measured 2x2, `memcpy-16k`, `IPC_MEM=l2:5:60:4096` (`863b45cb`):

| arm | copy-B/cyc | vs baseline |
|---|---:|---:|
| baseline | 0.32793 | — |
| + sectored 64 B lines | 0.35600 | **+8.56%** |
| + fill-forward | 0.34215 | **+4.34%** |
| **both** | 0.35975 | **+9.70%** |

Additive would be +12.90%, multiplicative +13.27%. **Measured +9.70%.**

**And the shortfall is PREDICTED BY THE MECHANISM, not fitted to the data.** Fill-forward
saves a fixed **2 cycles per cacheable MISS**; sectoring removes **3.99x of the misses**; so
fill-forward should retain about a quarter of its value. Its marginal contribution on top of
sectoring is **measured +1.05% against +1.09% predicted — a 0.04 pp fit.** That is
independent confirmation of the same story the L2 transaction counters told.

⛔ **So `+8.5%` and `+4.35%` MUST NOT BE SUMMED ANYWHERE.** `+4.35%` is a *no-sectoring*
number. Fill-forward remains worth shipping — it costs no area and it helps the regimes
sectoring does not — but if sectoring ships, its streaming case is ~4x weaker than its
recorded headline.

✅ **Instrument note, recorded as a pair:** the bandwidth bench is **fully deterministic** —
the sectored arm came back **bit-identical across two separate worktrees** on every shared
point. `maxBusy` varied **2-6 cycles between runs of one netlist**. Same tree, two
instruments, opposite reproducibility; knowing which one a claim rests on is what settled
the p127 question. Also: fill-forward's standalone **+4.34% reproduces its documented
+4.35% to 0.01 pp**, so the baseline was not drifting.

## ✅ THE INTEGRATION MERGE `integ/all-shippable` — RE-GATED AS A COMBINATION (2026-09-28)

Every gated improvement the campaign decided to ship, merged onto one branch and
**re-measured as a combination** — because a merged tree is a new configuration and none
of the individual measurements transfer to it.

`integ/all-shippable` `1a8ddf3a`, worktree `integ2`. Source hashes, highest value first:

| merged | source | brings |
|---|---|---|
| `integ/all-gated` | `5015dad7` | the **only silicon-proven win**: the two branch-coverage flags, **+3.06% on the board** (55,300 → 56,993.8 Dhry/s); plus `rasBranchRepair`, load-only `specLoadWakeup` |
| `perf/slot1-coverage` | `881b9ec3` | slot-1 `DBcc` deferral (−98.7% mispredicts on its shape) |
| `perf/itlb-victim-buffer` | `626cd6b1` | ITLB victim buffer, −73.9% walks, +1,451 FF, no measurable LUT |
| `perf/sq-congestion` | `59ecd9a0` | SQ narrow drain merge (~400 LUT), `SQ_DEPTH` knob |
| (already in) | | LS-OoO issue **+12.7%**, sectored 64 B lines **+8.56%**, MOVE16 oracle, stall budget |

`perf/dside-mshr2` is **deliberately excluded**: its headline change measured **−0.72%**.

### The provenance line is the deliverable, and three flags were NOT on it

`SHIPPING_CONFIG` now carries **16 fields**. Each source branch appended its own flags to
the same closing string, so a naive conflict resolution silently drops one branch's
provenance — the failure family with **four instances on record** in this core, one of
which cost a withdrawn silicon number. Every flag was round-tripped: generate twice, diff
the line **and** the netlist.

| flag | env override | default | line | netlist |
|---|---|---|---|---|
| `lsOooIssue` | `LS_OOO_ISSUE` | false | ✅ | ✅ +167 reg bits |
| `specLoadWakeup` | `SPEC_LOAD_WAKEUP` | false | ✅ | ✅ |
| `dcacheHitUnderMiss` | none (settled) | **true** | ✅ | prints only |
| `dcacheHitUnderMissRead` | `CPU_DCACHE_HIT_UNDER_MISS_READ` **or** `CPU_DCACHE_HUM_READ` | false | ✅ both | ✅ **byte-identical to each other** |
| `dcacheFillForward` | `CPU_DCACHE_FILL_FORWARD` | false | ✅ | ✅ |
| `dcacheSectored` | `CPU_DCACHE_SECTORED` | false | ✅ | ✅ −69 reg bits, +21 always |
| `rasBranchRepair` | `CPU_RAS_BRANCH_REPAIR` | false | ✅ | ✅ +8 reg bits |
| `computeDirectTargets` | `CPU_COMPUTE_DIRECT_TARGETS` | false | ✅ | ✅ `1'b0` → real predicate |
| `deferSlot1Uncond` | `CPU_DEFER_SLOT1_UNCOND` | false | ✅ | ✅ `1'b0` → real predicate |
| `deferSlot1Dbcc` | `CPU_DEFER_SLOT1_DBCC` | false | ✅ | ✅ `1'b0` → real predicate |
| `itlbVictimEntries` | `ITLB_VICTIM` | 0 | ✅ | ✅ +849 reg bits, **+1 module** |
| `storeQueueDepth` | `SQ_DEPTH` | 8 | ⚠️ **ADDED** | ✅ −207 reg bits at 4 |
| `sqNarrowDrainMerge` | `SQ_NARROW_MERGE` | false | ⚠️ **ADDED** | ✅ −84 reg bits |
| `icachePrefetch` | `DBG_IC_PREFETCH_DISABLE` (inverted) | **true** | ⚠️ **ADDED** | ✅ RegInit `1'b1`→`1'b0` |

⛔ **`dcacheHitUnderMissRead` had TWO env names.** It was given an override independently on
two branches — `CPU_DCACHE_HUM_READ` and `CPU_DCACHE_HIT_UNDER_MISS_READ`. Picking a side
makes the other name a silent no-op. **Both are read**; the two produce byte-identical
netlists.

⛔ **Three flags reached `ShippingCoreConfig` without reaching the print.** `SQ_DEPTH` and
`SQ_NARROW_MERGE` came that way from `perf/sq-congestion`. Worse, `DBG_IC_PREFETCH_DISABLE`
was read **directly in `IcachePlugin`** and changes a RegInit's reset value — a real netlist
change, on a feature that is **ON and worth a board-measured +2.6%**, with a diagnostic
bitstream indistinguishable from a shipping one in its own log. Now centralised and printed.

✅ A typo is a **hard error**: `CPU_DEFER_SLOT1_DBCC=ture` → `IllegalArgumentException`,
not a silent baseline. All 11 booleans + `ITLB_VICTIM=32` elaborate together.

### Cost of the merge at DEFAULT (everything off)

**+930 flop bits, +8,690 wire bits, +16 always blocks, 0 new modules** against `8c2866ce`.
Not free, and all of it is `integ/all-gated`'s **debug instrumentation** — the branch and
exception rings, the mispredict class counters, the I-prefetch CSRs — plus the `isCall` uop
field `rasBranchRepair` needs. **No gated feature is live:** `slot0ComputedPred`,
`slot1WouldUncond` and `slot1WouldDbcc` are all hard-wired `1'b0` in the default netlist.

### Gate results — the COMBINATION, not the parts

| gate | pre-merge `8c2866ce` | merged `1a8ddf3a` | verdict |
|---|---|---|---|
| `test-fast` | **396**/0/2 | **400**/0/2 | +4, **explained by name** |
| ported corpus (933 programs) | 1010 pass / **12** fail | 1010 pass / **12** fail | **failing sets IDENTICAL name-for-name**, 0 new, 0 tests lost |
| `ExecuteLockStepSpec` | 679 / **12** / 1 | 691 / **12** / 1 | **same 12 reds name-for-name**; **+12 new tests, all passing** |
| `ItlbVictimSpec` | (absent) | 3/3 both arms | ON arm: 90 accesses → 30 walks + 58 promotes |

**The +4 in `test-fast` is exactly** `DebugRingAtomicitySpec` (2), `PerfCounterSpec`
(mispredict class counters), `RobPluginSpec` (`debugBranchRetire` is ONE event) — the
ring-tearing and class-counter work. The other **6** new tests are `VerilatorTest`-tagged
and excluded, which is why the number is 400 and not 406.

⚠️ **AND THAT IS A COVERAGE FINDING:** `ItlbVictimSpec` is 3/3 Verilator-tagged, so
`test-fast` **never exercises the ITLB victim buffer at all**. A "gated 396/396 both arms"
claim on that branch said nothing about the feature. Run it explicitly.

Comparisons are an **explicit Python set comparison**, never `comm` — `comm` fails silently
under a locale collation mismatch and prints an empty "did not reproduce" section that is a
failed comparison, not a green result.

### IPC of the combination: **−13.6% cycles**, and it is NOT the sum of the parts

`IpcBenchSpec`, `IPC_V2=1`, `IPC_MEM=l2:5:60:4096`, all 34 kernels, two seeds, OFF vs
every lever ON **on the same merged tree**:

| | seed 1 | seed 17 |
|---|---:|---:|
| **cycle-weighted aggregate** | **−13.63%** | **−13.36%** |
| unweighted mean | −8.03% | — |
| median | −2.91% | — |

Best: `dhrystone-x0-byteAbs-cb` **−38.9%**, `byteSplit-cb` **−36.0%**,
`byteStoreOnly-cb` −22.4%, `byteLoadOnly` −18.8%, `dhrystone-x0-cb` −15.3%,
`chase-pure` −12.5%.

⛔ **DO NOT SUM.** LS-OoO alone is +12.7%, sectored lines +8.56%, the branch flags +3.06% on
silicon. Summed they predict well over 25%. **Measured: 13.6%.** Sub-additivity is the rule
in this machine, not the exception (see the D-side 2x2 below).

⚠️ **Three kernels REGRESS, and only one of them is real:**

| kernel | Δ s1 | Δ s17 | OFF-arm seed noise | verdict |
|---|---:|---:|---:|---|
| `same-line-copyback` | +7.29% | +6.16% | −0.58% | **REAL, +6.7%** |
| `load-stream` | +4.59% | +2.18% | **+13.25%** | sign reproduces, **magnitude unresolvable** |
| `load/store` | +2.58% | +1.10% | **+6.15%** | sign reproduces, **magnitude unresolvable** |

The seed-noise column is the **OFF arm against itself** at seeds 1 and 17 — identical RTL.
The two kernels that look like the worse regressions are the two with the largest seed
noise in the whole suite. `same-line-copyback` has essentially none, so its +6.7% is the
one regression that must be explained before this ships.

### D-side 2x2 re-measured on the merged tree — the levers survived the merge exactly

`memcpy-16k`, `IPC_MEM=l2:5:60:4096`, two seeds, copy-B/cyc:

| arm | s1 | s17 | vs baseline | recorded |
|---|---:|---:|---:|---:|
| baseline | 0.3278 | 0.3276 | — | 0.32793 |
| + fill-forward | 0.3421 | 0.3422 | **+4.36% / +4.46%** | +4.34% |
| + sectored | 0.3557 | 0.3560 | **+8.51% / +8.67%** | +8.56% |
| **both** | 0.3603 | 0.3598 | **+9.91% / +9.83%** | +9.70% |

All three reproduce their recorded figures to within 0.2 pp on a tree that merged four
branches into them — which is the point of re-measuring rather than inheriting.

**And the sub-additivity holds on the merged tree:** additive would be **+12.87%**,
measured **+9.91%**. Fill-forward's marginal contribution on top of sectoring is
**+1.29 pp** (8.51 → 9.91), against the ~+1.09 pp the mechanism predicts from sectoring
removing 3.99x of the misses fill-forward saves 2 cycles on. Same story, same order of
magnitude, on a differently-composed core.

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

## Lever 17 — `MOVE16` as one wide access: MEASURED WITHOUT RTL, and the premise was wrong

Measured on `perf/move16-wide` (`a700b79c` + test-only additions), `IPC_MEM=l2:5:60:4096`,
seeds 1 and 17, via `Move16OracleSpec` + `kMemcpyQuads`/`kMemcpyMove16`. Every arm has
**`dAR` = 2.001 and `dAW` = 0.938 per 16-byte line**, so the memory-system control holds
exactly across the whole sweep. Reproducibility 0.03-0.25% between seeds.

### ⛔ COVERAGE HOLE #7, and it had a WRONG COMMENT attached

**The IPC/bench corpus contained ZERO `MOVE16` instructions** — only a comment in
`CoreBenchHarness.kMemcpy` asserting *"a MOVE16-based copy is NOT currently a faster path
and this `move.l` loop is the fair baseline."* **That claim is false by 35%.** The comment is
why nobody ever measured it. Prior holes: zero A6/A7 operands, zero store→load pairs, zero
load→compare→branch chains, 25 suite mispredicts vs the board's 24.4 MPKI, zero DBcc, no
scattered kernel larger than L1.

### Streaming, 16 KB, 3 measured passes (3,072 lines)

| kernel | D-cache acc/line | order | cyc/line | 16 B/cyc | vs `move.l` |
|---|---:|---|---:|---:|---:|
| `memcpy-16k` — `move.l (a0)+,(a1)+` x4 | 8 | L,S x4 | **48.819** | 0.3277 | ref |
| `memcpy-q4` — displacement x4 + 2 `lea` | 8 | L,S x4 | 54.685 | 0.2926 | −10.7% |
| `memcpy-q2` | 4 | L,S x2 | 40.680 | (0.3933) | +20.0% |
| `memcpy-q1` — **the wide-MOVE16 proxy** | **2** | L,S | **32.712** | **(0.4891)** | **+49.2%** |
| `memcpy-m16` — **MOVE16 as microcoded TODAY** | 8 | L,L,S,S x2 | **36.091** | 0.4433 | **+35.3%** |

⚠️ **Read the `16 B/cyc` column carefully.** `q4`/`m16`/`memcpy-16k` really do move all 16
bytes of every line, so for them it is the measured `copyB/cyc` the spec prints. `q2` and
`q1` deliberately move only 8 and 4 bytes per line — the spec prints **0.1969** and
**0.1223** for them — so their column entries are **parenthesised PROJECTIONS**: `16 /
cyc-per-line`, i.e. the bandwidth an instruction with that access count would reach if it
moved the whole line. That projection is the point of the oracle, and it is sound here
because the datapath is 128 bits wide either way (`DLoadRsp.line`, `DStoreCmd.lineData`)
and the fills/writebacks are byte-count-independent — but it is a projection, not a
measurement of 16 bytes moved.

✅ **`q1` is a CONSERVATIVE proxy, not an optimistic one.** Its body is 5 macros / 6 uops /
14 bytes (1 load, 1 store, 2 `lea`, `subq`, `bne`); a wide-`MOVE16` body is 3 macros / **the
same 6 uops** / 8 bytes (1 wide load, 1 wide store, 2 dropped address ADDs, `subq`, `bne`),
with the same single load→store dependency and the same 2 accesses. The wide form is
strictly denser in the 8-byte fetch window, so it should land at or below 32.712.

### L1-resident, 2 KB (`dAW=0`, zero fills in the measured window) — pure LS-pipeline cost

| kernel | acc/line | order | cyc/line |
|---|---:|---|---:|
| `l1res-q4` | 8 | L,S x4 | 28.268 |
| `l1res-q2` | 4 | L,S x2 | 14.267 |
| `l1res-q1` | 2 | L,S | **7.728** |
| `l1res-m16` (today's microcode) | 8 | L,L,S,S x2 | 18.247 |
| `l1res-m16` + all-loads-first reorder | 8 | L,L,L,L,S,S,S,S | **15.734** |

### 🎯 THE 48.8 CYCLES, ATTRIBUTED — which lever 16 asked for and nobody supplied

`streaming − L1-resident`, per arm: **q4 26.417, q2 26.413, q1 24.984, m16 17.844**. So
**within one access ORDERING and at high access counts** the memory system is a near-exact
additive constant:

```
cyc/line  =  LS-pipeline(accesses)  +  26.415   (2 L1 fills + 0.94 writeback, ONE MSHR)
48.819    =  22.404                 +  26.415
```

⚠️ **Two honest caveats, both of which I nearly cherry-picked past.** (1) q4 and q2 agree
to **0.004 cycles** across a 2x change in access count, but **q1's adder is 24.984 — 5.4%
lower**. Fewer accesses contend less with the fill machinery, so the adder is not a true
constant; it drifts down as accesses are removed, which makes the q1 projection slightly
*conservative* again. (2) The adder is **not** ordering-invariant at all — see the m16 row
and the warning below.

and the L1-resident series is linear at **~3.4 cycles per narrow D-cache access**
(`(28.268−7.728)/6 = 3.42`), **not ~1 cycle**. The single LS issue port
(`IssueQueuePlugin.scala:726`) is therefore **not** the binding term — 8 accesses occupy
16.4% of the cycles but *cost* 46% of them, because each access is ~3.4 cycles of
serialised progress through a single-ported D-cache whose store drain is **refused in
113,106 of the 129,490 cycles it has data to give (87.3%)**.

⛔ **The constant adder is a property of ONE ORDERING, not of the kernel.** MOVE16's
L,L,S,S grouping gets an adder of only **17.84** (36.091 − 18.247). Interleaving loads and
stores is what lets the source fill and the destination write-allocate fill **overlap**
through the single MSHR. Do not carry 26.415 across an ordering change.

### ⛔ MY PRE-RECORDED FALSIFIER FIRED (recorded in `Move16OracleSpec` before the first run)

Predicted **+12%** (range 5-20%), falsifier at **≥ 35%**. Measured **+49.2%** against the
`move.l` loop. The model was wrong by ~4x because it priced an access at ~1 cycle and
assumed miss latency dominated; the LS pipeline is 46% of the loop, not 16%.

### ⛔ ...AND THE OPPOSITE ERROR: the honest number is +10.3%, not +49%

**The ROM's `BlockMove` fast path already uses `MOVE16 (Ax)+,(Ay)+`**
(`docs/AUDIT_narrow_ea_carveouts.md` §7: `0x40884482`/`0x40884490`/`0x40884496`). So the
baseline for real block copy is **`memcpy-m16` = 36.091**, not the `move.l` loop. Against
that, a wide MOVE16 is **32.712 → +10.3%**. The `move.l`-relative **+49.2%** is only
available to software that is not already using MOVE16.

That also kills the brief's arithmetic: a wide MOVE16 reaches **0.489 copyB/cyc = 48.9 MB/s
at 100 MHz**. A copy both reads and writes, so the 128-bit master's copy ceiling is
8 copy-B/cyc = 0.8 GB/s at 100 MHz (the same denominator the sectoring spec used for its
4.1%); that puts a wide MOVE16 at **6.1% of the bus, up from 4.1%** — **not the 800 MB/s
the "8 memory ops → 2, so 2 B/cyc → 8 B/cyc" model predicts.** The floor that model
computes is real; it simply is not the binding constraint. After the change,
**7.7 of 32.7 cycles are pipeline and 25.0 are the one-MSHR memory system (76%)**. GB/s
lives in MLP and transfer granularity, not in the LS port.

### ⛔ all-loads-first microcode reorder: MEASURED, and it is a REGRESSION — do not build it

A pure row reorder of `MOVE16_ENTRY` to L,L,L,L,S,S,S,S (the existing `ST2`/`ST3` temps,
`romSize` and every other entry number unchanged, **zero area**) is **13.8% FASTER
in-cache** (18.247 → 15.734, bit-identical both seeds) and **4.4% SLOWER streaming**
(36.091 → 37.745). Streaming is the case that matters for a copy loop. Closed.

### Semantics — established from the M68040 User's Manual, and the oracle is the obstacle

| | documented MC68040 | Musashi = this core |
|---|---|---|
| extent | *"the lines are aligned to 16-byte boundaries"*; UM §7.4.2 line read is *"a block of four long words, aligned to a 16-byte memory boundary"* with the device wrapping A3/A2 | **raw, unmasked** 4 LONGs at `An+0/4/8/12` |
| `Ax == Ay` | UM instruction-summary **note 7** + errata `MC68040DE_D` item 5: *"the address register is only incremented once, and the line is copied over itself"* → **+16** | **+32** |
| cache | UM §4.3.3: *"Accesses by the MOVE16 instruction also do not allocate cache lines in the data cache for either read or write misses... Write hits invalidate a matching line and perform an external access."* | ordinary loads/stores: allocates on read miss, **write-allocates** on write miss |

**`move16_basic.s` stages 2 and 3 ASSERT both of the first two deviations** (stage 2 guards
`0x00020300` specifically to prove no aligned-down write happened; stage 3 asserts `+32`).
They are the only two assertions in the whole corpus that block architectural conformance,
`handle_deref_rmw_count.s`'s three MOVE16 sites are all 16-byte aligned, and **lock-step
generates no MOVE16 at all**.

✅ **But conformance does NOT have to be touched to build the fast path.** Treat MOVE16 as a
**16-byte-wide access** and the existing cross-line split machinery covers Musashi's
unmasked semantics exactly: aligned → 1 access, misaligned → 2 (slot A + slot B), against 4
today. `DcacheByteLane.storeStrbA/storeStrbB/storeDataA/storeDataB/extractCross` are
already generic per-byte loops over `k` gated by `kActive(size,k)`; only the loop bound and
a 128-bit `data` input change. So **no spec amendment, no test change, strictly fewer
accesses in both cases.**

### What it costs, and who owns what

`DLoadRsp.line` is **already 128 bits** and `DStoreCmd.{strb,lineData}` are **already
16/128 bits**, so the wide datapath exists. The gap is exactly what the brief said:
`StoreQueue`'s entry `data` is **32 bits** and `storeStrbA` derives the strobe from
`Size ∈ {BYTE,WORD,LONG}`, so **4 of 16 bits is the widest strobe anything can present**,
and **nothing coalesces** (`sqNarrowDrainMerge` is not in this lineage and deleted logic
rather than adding a coalescer).

- **Mine** — `Microcode.scala` (10 rows → 4: one wide load, one wide store, two address
  ADDs), a `wide16` `DescBits`/uop bit (~30-40 flops over ~15 carriers, +252 ROM bits),
  `LsEuPlugin` staging (2 x 128 bits = **+256 flops**; the `twoAccess` FSM already makes ONE
  uop perform TWO translated accesses, which is the exact skeleton needed).
- **Needs routing — `StoreQueue`**: `SqAlloc.wide` (+1 bit), `maskAs := 0xFFFF` when wide
  (the overlap network is **already a 16-bit byte mask**, so no widening there), and a drain
  override for `strb`/`lineData`. Either **+768 flops** (128-bit per-entry data) or
  **+128 wires** from the LSU into the drain mux — and that block is named in the
  `DStoreCmd`-payload congestion finding, so prefer the flops.
- **Needs routing — `DcachePlugin`**: recognise `strb === 0xFFFF` on a COPYBACK store miss
  and skip the write-allocate refill. **This is where the remaining prize is**: it removes
  one of the two serialised fills, i.e. part of the 25.0-cycle memory term that is 76% of
  the post-change loop. It is also what the UM says MOVE16 must do anyway. **Unmeasured —
  do not sum it with +10.3%** (and note the two existing D-side levers are sub-additive).

### 🎯 HOW TO SIZE no-write-allocate CHEAPLY, BEFORE building it

Same trick as this lever's own oracle — no RTL. Add two one-access-per-line kernels over
the same 16 KB footprint:

* **load-only**: one `move.l (a0),d0` per 16-byte line → 1 fill, 0 writeback, 1 access.
* **store-only**: one `move.l d0,(a1)` per 16-byte line → 1 **write-allocate** fill,
  ~1 writeback, 1 access.

`store-only − load-only` is the measured cost of the destination-side write-allocate fill
plus its writeback — i.e. an upper bound on what a full-line no-allocate store can remove,
priced from measured per-operation costs rather than from a transaction count. Do that
before quoting any figure for it.


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
9. **The stall budget is an ACCOUNTING IDENTITY, not a decomposition — check conservation
   before writing RTL.** On a kernel that is never front-end starved (`robEmpty == 0`)
   with a 2-wide retire, `uops` is fixed by the program, so:

   ```
   cycles = retireStall + retire1 + retire2       uops = retire1 + 2*retire2
      =>   Δcycles = ΔretireStall − Δretire2
   ```

   **A lever that cuts retire-stall and gives back the same number of dual-retire cycles
   is cycle-neutral BY CONSTRUCTION.** Both deltas are the same cycles, counted once as a
   stall and once as an extra single-retire cycle. Read the test as:

   - both fall together → **CONSERVED. The gain never existed — stop.**
   - stall falls, `retire2` holds → cycles *must* fall; if they did not, something really
     absorbed it — **attack that.**

   `StallBudget.conservationResidual` ships it; `[stall-budget]` prints `conserved` when
   the identity binds and returns `None` when the preconditions fail. Verified by
   reproducing all eight `-cb` cycle deltas from two of its own terms.

   ⛔ **This retracts the "retire-stall −8.2% win cancelled out" reading of
   `byteAbs-cb`.** ΔStall −1,519 with ΔRetire2 −1,519 is a bucket swap, not a gain in
   transit. Apply the same test to the two other "works, but blocked" levers —
   `dcacheHitUnderMissRead` and `loadBypassUnreadyLoad`.

   A pair census on the same kernel also shows the retire stage **declines nothing**:
   every lost pair is `h1LateByOne`, `headForbidsPair` is **0 on both arms**, and
   GATE-SAID-NO is bit-identical at 1,536. The pair is *not available*, not refused — so
   the remaining ~5.2% needs h1 to **complete** earlier, which is execution bandwidth,
   not a retire gate.

   🎯 Open lead, unsized: `load/store` has **96 of 289 retiring cycles blocked by
   `h0PreciseCompletedSticky`** — pairs present AND complete, and declined.


## Lever 14 — an explicit predictor for `DBcc` (owner's idea, 2026-09-27)

**Why this ISA is a good target.** A general loop predictor (the loop component of
TAGE-SC-L; the Pentium M loop detector) has to *detect* that a branch is a counted loop.
The 68k declares it in the opcode, and the ROM census says the declaration is nearly
always the useful one:

| form | share of ROM DBcc | behaviour |
|---|---:|---|
| **`DBF` / `DBRA`** | **78.8%** | condition constant-false -> taken iff `Dn != -1` after decrement: **a pure counted loop** |
| `DBT` | 2.1% | condition constant-true -> **never loops**, statically not-taken, zero state |
| data-dependent (`DBNE`, `DBEQ`, ...) | 19.2% | genuine conditional, needs the normal predictor |

**80.8% carry no data-dependent condition at all.** A small table holding
(tag, trip count, current count, confidence) predicts the **loop exit** exactly for a
stable trip count. gshare structurally cannot: it mispredicts once per loop execution,
which on the short loops typical of ROM string/block code is a 12-33% mispredict rate on
that branch.

**It also dodges a frozen parameter.** `gshareEntries` is stuck at 2048 with the 11-bit
index hardcoded in ~15 sites, and deriving it from `Global` breaks elaboration. A
separate small table avoids that entirely. Size precedent in-tree: `brPredTable` is
**68 LUT**.

### ⛔ ORDERING: this is gated behind lever 13, and building it first would measure a null

The attribution is **38.7% no-predict-relative-uncond / 34.5% returns / 22.7% indirect /
only 3.9% DIRECTION**. A loop predictor attacks the direction bucket, i.e. the smallest
one, and today would read as noise.

That is an artifact of the coverage hole, not a verdict: **a slot-1 `DBcc` has no
prediction at all, so its mispredicts are counted as COVERAGE, not direction.** Closing
coverage (lever 13) moves them into the direction bucket as loop-*exit* mispredicts —
exactly what this lever then removes. Re-read the attribution after 13 lands before
sizing this. Same discipline as `#7 gates #5`.

## ✅ FIRST SILICON BANDWIDTH NUMBERS + FIRST SILICON A/B OF FILL-FORWARD (2026-09-30)

Instrument: SoC `tools/board_membench.sh` (branch `tools/board-membench`), bare metal, VIA1-T1
timed, data verified in-program. RAM copyback via DTT0 — **the same mode Mac OS 7.5.3 maps
RAM with** (page-table walk on a booted Finder: every RAM page CM=01, TC=0xC000, no TTR
covers RAM). Copy/fill/chase reproduce to **<=0.02% across launches and bitstream reloads**;
the read stream is bimodal per launch (two states 1-8% apart) — never A/B it on few launches.

### Shipping default (P1, `e98a322c`), cycles — L1/L2 identical at 100 and 200 MHz

| kernel | L1 (2-4 KB) | L2 (256 KB) | DDR (8-16 MB) @100 | DDR @200 |
|---|---:|---:|---:|---:|
| chase, cyc/hop | 8.29 | 21.01 | 43.8 (438 ns) | 55.7 (278 ns) |
| copy `move.l`, B/cyc | 0.437 | 0.245 | 0.206 | 0.195 |
| copy `MOVE16`, B/cyc | 0.527 | 0.262 | 0.219 | 0.206 |
| read (sum), B/cyc | 1.90-1.92 | 0.84-0.91 | 0.73 | 0.68 |
| fill, B/cyc | 0.547 | 0.308 | 0.277 | 0.265 |

The copy headline is **0.19-0.25 B/cyc** — **~3% of the 8 B/cyc goal**, and 25-34% below
what the sim bench's `memcpy-16k` (0.328) claims. The DDR-miss penalty over an L2 hit is
~11 core cycles + ~118 ns fixed (fits both clocks). Controls: write-through copy 0.21,
inhibited copy 0.12 B/cyc; **WT read at L2 (0.98) beats copyback read (0.84)** — ~2.7 cyc
per line that only the copyback miss path pays.

### Sim calibration (`BoardMembenchSimSpec`, same loop bodies)

Chase slopes are 0.995 (hit) and 1.002 (dram) cyc/cyc. Fit to silicon:
**`hitCycles` ≈ 6** (both clocks); **`dramCycles` ≈ 21 at 100 MHz, ≈ 33 at 200 MHz**
(with hit=5; DDR chase = 22.9 + dram). The historical `l2:5:60`/`l2:5:70` is ~2-3x too
pessimistic on DDR latency. **With latency calibrated, the sim is still ~25-34% too fast on
copy** (L2: 48.8 sim vs 65.35 silicon cyc/16 B) — the missing cost is on the WRITE side
(write-allocate + dirty writeback, which `AxiMemModel` prices at `hitCycles` and zero).

### ⛔ Fill-forward on silicon: the memcpy win is a SIM ARTIFACT — it is a LOSS

`CPU_DCACHE_FILL_FORWARD=1` vs P1, 100 MHz, both off `e98a322c`, 3/3 Finder boots
(exc-halt 2/3/4 armed, framebuffer read), 9 vs 6 launches over 2 bitstream loads each:

| kernel | silicon Δcycles | spread | sim Δcycles (same kernel) |
|---|---:|---:|---:|
| chase L2 | **-2.000 cyc/hop (-9.5%)** | <0.01% | -1.86 |
| chase DDR | -1.25 cyc/hop (-2.8%) | 0.02% | -1.93 |
| copy `move.l` L2 | **+4.08% (SLOWER)** | 0.00% | -4.1% |
| copy `move.l` DDR | **+2.35% (SLOWER)** | 0.01% | -2.9% |
| MOVE16 / read / fill | 0.00% | <0.2% | — |

Same D-cache miss count and same dcache-busy stall in both arms; fill-forward adds 2.67
cycles per line of pure retire stall to the `move.l` copy. **The sim gets the mechanism
right (dependent-load latency -2 cyc) and the bandwidth sign wrong.** Do not ship
fill-forward on the strength of `memcpy-16k`; its real value is dependent-load latency.

### Core defect found by the benchmark: D-side `CPUSH` does not invalidate

`ExceptionUnit.scala` CPUSH arm sends `push=True, invalidate=False`. A 68040 `CPUSH` pushes
**and invalidates**. Measured on silicon: after `cpusha dc`, a host/inhibited write to the
line is invisible to the next cacheable load (stale 0xDEAD0000 read back while DDR held the
new value); adding `cinva dc` fixes it. Any DMA-in sequence that relies on CPUSH is exposed.
