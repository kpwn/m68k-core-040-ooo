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
| — | LS OoO park + recovery | ✅ **gated 396/396**, `68a89f88` | `orderRedirects` **0 → 7**, ~20.9 cyc each, **cost ONE flop bit**; lock-step reds 22 → 17. ⛔ the relaxation itself is still blocked by a **third pre-existing** corpus defect |
| 11 | store-queue congestion (`sqNarrowDrainMerge`) | ✅ **gated 396/396**, `59ecd9a0` | ~400 LUT, **2,123 RTL lines deleted**; IPC **bit-identical, 34 kernels x 2 seeds**; corpus + lock-step identical name-for-name. ⛔ **module CEILING reached** — the whole SQ is 4,722 LUT ~ 5% of socket_core, so nothing confined to it can move congestion level 5 |
| 12 | PRF write/read port merge (`PINS_PRF_FMAN_SHARE` + `PINS_PRF_SLOWREAD`) | ✅ **gated 396/396 both arms** | crosses the LVT `coreCount` step **only in combination**: 96 -> **80 cells (-21.6%)**, **-1,112..-1,168 LUTRAM**. FMAN deletes one of six write-address broadcasts outright = **-16.7% of the `ADDRH` sink pins** |
| 13 | slot-1 coverage completion (DBcc / FBcc / BRA.L) | 🔄 in flight | `slot1WouldUncond` misses **7.26% of ROM control transfers**; DBcc is 3.41% STATIC and far higher dynamic (loop-closing). ⛔ RTD/RTE deliberately excluded — slot 0 cannot predict them either, so deferral costs a slot and buys nothing |
| 14 | explicit `DBcc` loop predictor (owner, 2026-09-27) | 📋 QUEUED — **gated behind #13** | **80.8% of ROM DBcc are `DBF`/`DBT`: pure counted loops, outcome is the counter alone.** Removes the once-per-loop EXIT mispredict gshare cannot get |
| 15 | **ITLB victim buffer** (`ITLB_VICTIM=32`) | ✅ **BUILT, gated 396/396 both arms**, default OFF | trace-driven on real 7.5.3: **10.25 ITLB walks/kinst**, 73.9% of them capacity/conflict; a 32-entry FIFO victim buffer removes **−73.9%**, which IS the infinite-ITLB floor. OFF netlist **byte-identical**; ON adds **~1,509 flop bits**. Worth **2.2-4.0% of cycles** on boot/Finder and **exactly nothing on Dhrystone** (0.061 walks/kinst there) |

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

Per phase, shipping geometry: boot **3.47**, Finder launch **6.25**, **Finder idle 17.89**
walks/kinst. Composition of the 86,627 walks: **183 compulsory (0.2%)**, 22,393 PFLUSHA
refills (25.8%), **64,051 capacity/conflict (73.9%)**.

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
  ⚠️ Per rule 7 that is a sanity check, NOT an area measurement.
- `ItlbVictimSpec` is a **paired** test: the `victimEntries = 0` arm must show the walk
  (fail-before) and the ON arm must show zero walks, one promote, and **the same PPN**;
  plus PFLUSHA must clear the buffer or the re-touch would be answered out of the
  pre-flush address map.
- ⛔ **This lever cannot be measured on Dhrystone.** The board's Dhrystone-only window
  walks the ITLB **0.061 times per kinst**. Its workload is boot and the Finder, and the
  metric that moves unambiguously is `OFF_PERF_ITLB_WALK` (0x010A4), predicted 10.25 →
  2.68 per kinst — a 3.8x change, far outside any noise floor. Read that counter with
  `IC_MISS`/`MISPRED` alongside so the phase can be shown to match.

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
(ring position *is* program order). Whether shrinking the module moves congestion at all
is **still being measured** — the `SQ_DEPTH=4` calibration arm is in placement; the
structural argument (4,722 LUT ~ 5% of socket_core) says it cannot, but that is arithmetic,
not a result. The two real targets are the **integer PRF** (9,957 LUT, 4,584 LUTRAM,
~10% of the device, ~5,800 sink pins — lever 12) and the **`DStoreCmd` merge payload**
(**−117 wires between two blocks that both appear in congested windows**).

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
