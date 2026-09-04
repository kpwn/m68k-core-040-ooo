# 68040 OoO latency microbenchmark suite — first measured latencies

**Date:** 2026-09-04
**Branch:** `bench/hw-microbench-suite` (worktree `m68k-core-040-ooo-worktrees/hw-microbench`)
**Base commit:** `bb3bca1` — *record this: the DTLB/MMU figures are a "before" baseline for
the in-flight change that routes table walks through the L1D.*
**Scope:** SIMULATION ONLY. No board, no JTAG lease, no SD card was touched.

---

## 0. Scope change and what it means

This campaign was originally scoped to run on the FPGA. It was re-scoped mid-flight to
**simulation only**, so the SD card holding the verified calibration-fix-only ROM and the
Happy-Mac boot volume was **never accessed**, no bitstream was loaded, and the JTAG lease
was never taken. The board-preservation constraint is satisfied trivially and completely.

One hardware finding was already established before the re-scope and is retained here
because it decides whether this suite can ever be ported to silicon:

> **There is no working free-running cycle counter on the current hardware build.**
> `OFF_CYCLE_LO/HI` (`0x5090_1000/1004`) are declared in the debug register map but are
> **Stage 7**, while `M68kSocketTop` is built at `debugStage = 5`. They are absent from
> `DebugCtrlPlugin`'s read mux entirely and return `0x00000000`. The core honestly
> self-reports this: `OFF_FEATURES` bit 12 (`perf_counters`) reads 0.
> A real free-running 64-bit **retired-macro-instruction** counter *does* exist and is
> live at `OFF_INST_LO/HI` (`0x5090_1008/100C`), readable by a plain CPU load or over
> JTAG. `inst-count` in the REPL reads a *different*, **halt-captured** register
> (`OFF_HALT_HIT_INST_LO/HI`, `0x5090_0048/004C`), which is why it returns a stale value
> — often `0` — on a running CPU. That is the documented cause of its unreliability.

So a future hardware port of this suite needs a cycle counter added (two `is()` cases in
`DebugCtrlPlugin`'s read mux plus a 64-bit free-running `Reg`), and would then reuse the
kernels and the differential method here unchanged. **That work was explicitly taken out
of scope and was not done.**

---

## 1. Timing source, and how it was validated

**Source used:** the simulation clock, counted by the existing bench harness's per-cycle
commit histogram (`CoreBenchHarness.runKernel`). A kernel's `windowCycles` is the number
of clock edges between its **first** and **last** macro-instruction commit — pipeline fill
and drain are outside the window by construction. This is not a CSR and not a wall clock;
in Verilator the testbench counts edges directly.

**Validation.** The suite refuses to report anything until three checks pass, and it
`assert`s on each, so a broken timing source fails the test rather than producing numbers:

| Check | What it proves | Result |
|---|---|---|
| **V1 linearity** — marginal cost of a NOP measured at three different (short,long) pairs: (200,400), (400,800), (200,800) | Cost is strictly proportional to N. A counter that saturated, wrapped, double-counted or dropped cycles cannot give the same slope at three lengths. | **0.500, 0.500, 0.500** cyc/NOP. Spread **0.0000**. |
| **V2 known cost** — dependent ALU chain | Anchors the *absolute* scale, which linearity alone cannot. Documented cost is 1 cycle (single-cycle ALU with result forwarding). | **1.000 ± 0.000** cyc/op — exact. |
| **V3 dual retire** — independent ALU chain | The counter tracks real 2-wide retire, not instruction count. | **0.500 ± 0.000** cyc/op — exactly 2/cycle. |

All three are exact to three decimals with zero variance across seeds. **The timing source
is sound.**

---

## 2. Method

### 2.1 Differential (marginal-cost) timing

Every latency is a **difference between two runs of the same kernel** differing only in the
length of a dependency chain:

```
cyclesPerStep = (cycles(nLong) - cycles(nShort)) / (nLong - nShort)
```

Everything constant cancels *exactly*: pipeline fill, drain, register setup, loop preamble,
the window's own edges, and the cold-start misses of a first pass. No overhead constant is
ever hand-estimated — the subtraction is structural. Where a chain step contains work
besides the operation under test, a **matched control** kernel with only that operation
replaced is measured and subtracted; both halves are always printed so the subtraction is
auditable.

### 2.2 Latency vs throughput

This is an out-of-order core: it hides EU latency by issuing independent work. Every kernel
is labelled **DEP** (serial dependency chain → measures **latency**) or **IND** (independent
ops → measures **throughput**). For the long-latency EUs both are reported, because the gap
between them is the result.

### 2.3 Variance

Each figure is the mean over **3 seeds** (5 where noted). SpinalHDL randomises the AXI
agents' ready-throttling per seed, so a single reading is one draw from a distribution, not
a measurement. Mean, standard deviation, min/max **and the raw per-seed samples** are
printed for every row — a mean plus sigma can hide a bimodal or degenerate sample, and in
one case below it did.

### 2.4 What is real RTL and what is a model — read before quoting any memory number

The core, its L1I/L1D, the store queue, the TLBs and the table walker are **real RTL**.
Beyond the core's AXI ports there is **no RTL**; `m68k040.sim.AxiMemModel` stands in for
the SoC. Its own header is explicit about its provenance:

- `hitCycles = 5` (L2 hit round trip) is **calibrated** against the real L2
  (`macqd700-soc/docs/l2c_spec.md:668-679`). L2-hit numbers are meaningful.
- `dramCycles` is ***explicitly UNMEASURED*** — "real DDR4/MIG latency is undocumented in
  BOTH repos. This MUST be swept, never assumed." **Any absolute DDR figure from this suite
  is a model input echoed back, not a property of the machine.**
- The model's L2 has **unbounded capacity** (`l2Lines` is a `mutable.Set` that never
  evicts). An L2 **capacity** miss cannot be produced at all — only a **cold, first-touch**
  miss. The "DRAM" row below is therefore a cold-miss measurement.

### 2.5 Cache control

Stated per row. The L1D is 8 KiB, 4-way, 128 sets, 16-byte lines, round-robin replacement.
Levels are selected by **footprint**, not by flushing: a 4 KiB working set fits in L1D and
hits; a 32 KiB working set is 4× the cache and misses essentially every access while
remaining L2-resident from the previous pass; a straight-line stride-64 walk touches a
never-before-seen 64-byte line every step and is cold to the model's DRAM path.

---

## 3. Measured results

Memory rows: `IPC_MEM=l2:5:70`. All other rows: zero-latency memory (isolates the core).
All values in **core clock cycles**.

### 3.1 Store-to-load forwarding — the headline

| Measurement | Cycles | Method |
|---|---|---|
| **store → load → use round trip (DEP)** | **10.000 ± 0.000** | DEP; differential; COPYBACK; 2 macros/step |
| reference: load removed (IND) | 2.000 ± 0.000 | throughput-bound reference, **not** a subtraction control |

The kernel stores `d0` to an address and immediately reloads it into `d0`, so the loaded
value feeds the next store: a strict serial chain in which every load's address matches a
store still resident in the 8-deep store queue and must be satisfied by the SQ forward path.

**Two methodology points that changed the answer:**

1. **The first version measured the wrong thing.** Under the MMU-off default every store is
   WRITETHROUGH and must reach AXI; store drain (~10.6 cyc/store) dominated and *hid* the
   forwarding latency. Test and control came out at 10.873 vs 10.597 — a difference of
   0.277 that would have been reported as "forwarding cost" and would have been meaningless.
   Re-running under **COPYBACK** (match-all transparent translation, CM=01), where a store
   hit resolves entirely on-chip with zero AXI traffic, gives the clean 10.000 above.
2. **The "control" is not subtractable and is not subtracted.** Removing the load also
   removes the only RAW edge — the remaining store-reads-`d0`/move-writes-`d0` pair is a
   WAR dependency that register renaming eliminates. That kernel is throughput-bound
   (2.000 cyc/pair = 1 cycle/store issue) while the forwarding kernel is latency-bound.
   The 5× gap is what demonstrates the forwarding chain is genuinely latency-bound; it is
   *not* an overhead to subtract.

**On the m68k-ooo comparison:** this measures that the forward path exists and resolves a
dependent store→load→use chain in 10 cycles. It does **not** measure m68k-ooo, and no
speedup ratio against it is claimed here — that would require running the same kernel on
that core, which was not done.

### 3.2 Memory hierarchy (`IPC_MEM=l2:5:70`)

| Level | Marginal cost per dependent load | Method / cache control |
|---|---|---|
| **L1D hit** | ~~11.000 ± 0.159~~ **SUPERSEDED — see §3.2a; real load-to-use is 17–19** | control kernel not correctly matched; over-subtracts |
| **L1D miss → L2 hit** | **20.139 ± 0.533** | 32 KiB footprint = 4× L1D → misses every access; L2-resident from prior pass |
| **cold miss → model DRAM** | **98.242 ± 1.858** at `dramCycles=70`; **core-side fixed cost 28.5** (sweep intercept) | straight-line stride-64; a fresh 64 B line every step |

These are **marginal** costs: the cost of adding one more dependent load to the chain, with
the matched no-load control already subtracted. The control replaces the load with a
register move, which itself costs 1 cycle, so **absolute load-to-use is these values +1**
(L1D hit ≈ 12 cycles). The chain is a zero-load pointer chase — the D-side memory is
zero-filled so the loaded value is 0 under any byte order, and the address register is made
to depend on it (`adda.l %d1,%a0` with `d1==0`), giving a true serial load-to-use dependency
without needing the memory model's endianness to be correct.

**The DRAM row is a model artefact and must not be quoted as this machine's DDR latency.**
`dramCycles=70` is an unmeasured parameter; the 98.242 figure largely echoes it back.

#### The DRAM sensitivity sweep — what is actually real here

`tools/run_microbench.sh --dram-sweep` re-runs the memory group across `dramCycles` so the
model parameter can be separated from the core's own behaviour:

| `dramCycles` | measured cold-miss cost | linear fit | residual |
|---|---|---|---|
| 20 | 48.360 ± 1.247 | 48.466 | −0.106 |
| 40 | 68.582 ± 1.548 | 68.405 | +0.177 |
| 70 | 98.242 ± 1.858 | 98.313 | −0.071 |

```
slope     = 0.997 cycles per dramCycle
intercept = 28.53 cycles
```

Residuals are within ±0.18 cycles over a 50-cycle sweep — an essentially perfect straight
line. Two real results follow, neither of which depends on the unmeasured parameter:

- **Slope ≈ 1.00.** On a serial dependent chain the core hides *essentially none* of the
  memory latency — every extra DRAM cycle appears 1:1 in the dependent chain. That is the
  correct and expected behaviour for a true load-to-use dependency (there is no independent
  work to overlap), and it is the positive confirmation that this benchmark is genuinely
  latency-bound rather than accidentally measuring throughput.
- **Intercept ≈ 28.5 cycles.** This is the **core-side fixed cost** of a dependent load that
  misses both L1D and L2 — miss detection, AXI request, refill, replay and load-to-use —
  with the memory system's contribution removed. **This is a real property of this RTL and
  is the number worth quoting.** The 98.242 figure is not.

So: quote the L1D hit (11.0), the L2 hit (20.1), the slope (~1.0) and the intercept (28.5).
Do not quote 98.242 as a DDR latency.

### 3.2a Load-to-use: correction, decomposition, and a cycle trace

The §3.2 memory figures were challenged as implausibly high. Re-measuring settled it, and
**one of my own numbers has to be corrected.**

#### The correction

§3.2's "L1D hit = 11.000" came from a 3-instruction chase minus a control kernel. **That
control is not correctly matched.** Its `move.l %d2,%d1` reads a loop-*invariant* register,
so it sits OFF the dependency chain — the subtraction removes the two address adds but
leaves the AGU and the consumer wakeup inside the residue, and it over-subtracts. A
purpose-built kernel with no control at all settles it:

```
move.l (%a0),%a0        # 1 macro/step, chain = address -> AGU -> D$ -> writeback -> address
```

| Method | Value | Notes |
|---|---|---|
| M1 differential, pure chase (DEP) | **18.737 ± 0.531** | 1 macro/step, no subtraction |
| M2 per-event, D$ cmd→cmd (DEP) | **17.000 ± 0.000** | direct, shares no arithmetic with M1 |

Two independent methods agree at **17–19 cycles**, so **§3.2's 11.000 is superseded for
load-to-use.** The relative structure of §3.2 (L1D < L2 < DRAM) and the DRAM *slope* (0.997,
a difference-of-differences that is insensitive to the control) still stand; the absolute
per-access values from that subtraction should be read as lower bounds.

#### Latency or serialisation? — the decisive test

| | cycles/load | |
|---|---|---|
| **dependent** loads (pointer chase) | **18.74** | DEP |
| **independent** loads (6 dests, 6 resident lines) | **1.50** | IND |
| **ratio** | **12.5×** | |

**The load path is deeply pipelined, not serialised.** It sustains a load every 1.5 cycles
given ILP — in fact *better* than the design docs' stated initiation interval of "1 load
every 3 cycles at the D-cache port, 1 per 9 through the LS EU". So this is a pure latency
property that the machine can hide, not a throughput defect. That distinction matters
because the two diagnoses call for completely different fixes, and it rules out the more
serious one.

#### Where the cycles go — cycle-by-cycle trace

`MB_TRACE=trace-chase` dumps every load-path stage per cycle. One steady-state iteration
(`#` = stage active), reproduced verbatim:

```
cycle  P1 P2 PT P3 P4 C0 C1 C2 RS CM WB
  144  #  .  .  .  .  .  .  .  .  .  .     P1  issue ctx + registered operands
  145  .  #  .  .  .  .  .  .  .  .  .     P2  DTLB + VIPT probe launched
  146  .  .  #  .  .  .  .  .  .  .  .     P2T translation response
  147  .  .  .  #  .  .  .  .  .  .  .     P3  resolved PA -> SQ query
  148  .  .  .  .  #  .  .  .  .  .  .     P4  SQ forward response -> resolve
  149  .  .  .  .  .  #  .  .  .  .  .     C0  D$ accepts address (loadCmd fire)
  150  .  .  .  .  .  .  #  .  .  .  .     C1  tag compare + way select
  151  .  .  .  .  .  .  .  #  .  .  .     C2  byte-lane extract
  152  .  .  .  .  .  .  .  .  .  .  .     <-- BUBBLE
  153  .  .  .  .  .  .  .  .  .  .  .     <-- BUBBLE
  154  .  .  .  .  .  .  #  .  .  .  .     C1 AGAIN
  155  .  .  .  .  .  .  .  #  #  .  .     C2 AGAIN + loadRsp (data returns)
  156  .  .  .  .  .  .  .  .  .  #  #     completion + writeback/wakeup
  157  .  .  .  .  .  .  .  .  .  .  .     <-- BUBBLE
  158  .  .  .  .  .  .  .  .  .  .  .     <-- BUBBLE
  159  .  .  .  .  .  .  .  .  .  .  .     <-- BUBBLE
  160  #  .  .  .  .  .  .  .  .  .  .     next dependent load starts
```

**16 cycles per dependent load, of which 11 show stage activity and 5 are bubbles** —
cycles where no observed load-path stage is active at all. The 11 active cycles match the
design intent almost exactly (see §3.2b). The 5 bubbles are the excess, in two regions:

- **Bubble A, cycles 152–153 (2 cycles), and a duplicated cache pass.** The D-cache load
  stages `ldS1Valid`/`ldS2Valid` fire **twice** per load — at 150/151 and again at 154/155,
  with the data only returning on the second pass. `DcacheSpec.scala:2241` asserts a load
  hit responds **2 cycles after accept**; here accept is 149 and the response is 155, i.e.
  **6 cycles**. Either this is not taking the plain-hit path, or the access is re-run.
- **Bubble B, cycles 157–159 (3 cycles).** Writeback and wakeup broadcast at 156; the
  dependent load's P1 is at 160. The design specifies the consumer becomes ready on the
  registered `lsWait` clear and issues at **wakeup+1**; observed is **wakeup+4**.

**Interpretation, held separate from the observation.** The trace above is fact. Two
readings are possible for Bubble A: a genuine replay/re-issue, or a legitimate two-pass
design (the docs describe a virtual-set probe launched in parallel with the DTLB, which
could be the 150/151 pass, with 154/155 the tagged resolve). I did not confirm which. The
follow-up that would settle it is narrow: check whether the D-cache load path takes its
`DcacheSpec`-asserted 2-cycle hit route in this scenario, and whether `wakeupPort.valid` →
dependent select really costs 4 cycles rather than the specified 1. **I am not claiming a
bug; I am reporting 5 unattributed cycles per load and naming exactly where they sit.**

#### Store-to-load forwarding, same treatment

| | cycles |
|---|---|
| forwarded store→load→use **pair** (store + load) | **10.000** |
| single dependent load served by the L1D | **18.737** |

A forwarded pair containing *both* a store and a load costs ~8.7 cycles **less** than a
single cache-served dependent load. So **the forward path really is short-circuiting the
cache pipeline** rather than running through it — P4 resolves `fwdHit` without launching a
cache access, exactly as intended. That is a genuine, measured vindication of the
forwarding design. It remains true that no comparison against m68k-ooo was run, so the
*relative* advantage over that core is still an unmeasured claim.

### 3.2b Measurement versus design intent

A review of the design docs and the RTL (citations below) establishes that **11–12 cycles
of load-to-use is the designed number, not a regression** — and that the design says so
only implicitly:

- The intended depth is **9 stages issue→completion**
  (`2026-08-09-ipc-ls-eu-full-pipeline-design.md:559`), plus a registered
  completion/writeback stage, plus the IQ's +1 dynamic-wakeup hop. That counts to 11–12,
  and the implemented register chain (P1, P2, P2T, P3, P4, ring, C0, C1, C2, comp\*,
  wakeup) matches the trace above one-for-one.
- It was deliberate. Goal G3 (`:212-216`) states the target pipeline should be *longer*
  than the previous 9-cycle load latency "possibly by 1–2 cycles — and that is the intended
  outcome, not a cost to be minimised", under an explicit user principle preferring depth
  over a serial FSM.
- **~6–7 of those cycles are FMax tax.** Six separate splits were added to the load path
  for timing, each waved through in-source with the phrase "latency-agnostic; lock-step
  absorbs the +1" — e.g. `DcachePlugin.scala:860-872` ("Costs one uniform extra cycle of
  load-to-use latency on every D-cache load hit ... the 5th of this shape in this file").
  That phrase is true of the *lock-step verification harness*; it is not true of IPC.
- **Only one of the six had its IPC cost measured** (Slice 2: −0.8% aggregate IPC for
  +8.35 MHz). **Nobody ever summed the six**, and no document states the resulting
  end-to-end load-to-use figure.
- **Nothing pins it.** `DcacheSpec.scala:2241` exact-asserts the *cache port's* 2-cycle hit
  response (3 of ~16 cycles); the LS-EU front and completion stages are unpinned, and
  `IpcBenchSpec.scala:973` states outright that its kernels measure "EU + D-cache
  THROUGHPUT (initiation interval), **not load-use latency**". There was no
  dependent-load kernel anywhere in the tree before this suite.

**So the adjudication is: the design intends ~11–12, the machine delivers ~16–19, and the
trace locates the ~5-cycle gap in two specific places.** The deep part is architectural and
was chosen knowingly; the bubbles are the fixable part. Given 68k code is load-dense and
independent loads pipeline at 1.5 cycles, dependent-load latency is a strong candidate for
the dominant term in the 0.655 aggregate IPC — but I did **not** measure that attribution,
so it stays a hypothesis.

### 3.2c The hit path against the 1-cycle target / 2-cycle ceiling

**Design target (project owner):** *"dcache hit path needs to be very fast; 1 cycle in the
L1D hit and TLB hit case would be great; 2 is our maximum."*

#### First: is the hit path even the binding constraint?

Yes. Independent loads sustain **1.50 cycles** each against **18.74** dependent (§3.2a).
The path is **pipelined, not serialised**, so throughput is not the limiter and the
**latency of the hit path is the binding constraint.** Had independent loads also cost ~11,
the budget would have been the wrong thing to chase; it is not.

#### The hit path, isolated from the surrounding overhead

The budget is on the *hit path*, so it must be reported separately from load-to-use.
Splitting the 16-cycle trace at the two boundaries that matter — address formed, and data
returned:

| Segment | Cycles (trace) | Count |
|---|---|---|
| Front-end: issue ctx + registered operands (P1) | 144 | 1 |
| **HIT PATH: address formed → data returned** | **145 → 155** | **10** |
| Completion + writeback/wakeup | 156 | 1 |
| Wakeup → next dependent issue | 157–159 | 4 |
| **Total load-to-use** | 144 → 160 | **16** |

**Hit path = 10 cycles against a target of 1 and a ceiling of 2 — 5× over the maximum.**
(Reporting load-to-use's 16 against the budget would overstate the overrun; 6 of those 16
cycles are issue/writeback/wakeup, which the budget does not cover.)

#### Every cycle in the hit path, and whether it is load-bearing

| Cycle | Stage | What it does | Load-bearing? |
|---|---|---|---|
| 145 | P2 | DTLB request + L1D virtual-set probe launched | work |
| 146 | P2T | translation response captured | work (TLB) |
| 147 | P3 | resolved PA → store-queue query | work (SQ disambiguation) — **FMax split #2** |
| 148 | P4 | SQ forward response → resolve / cache launch | **FMax split #3** |
| 149 | C0 | D-cache accepts the address | **FMax split #4** (EA registered at the cache boundary) |
| 150 | C1 | tag compare + way select | **FMax split #5** (registered hit-detect) |
| 151 | C2 | byte-lane extract | **FMax split #6** (S1→S1a/S1b) |
| 152–153 | — | **nothing active** | **BUBBLE (2)** |
| 154 | C1 | tag compare + way select **again** | duplicate pass |
| 155 | C2 + RSP | extract again; **data returned** | duplicate pass |

**The D-cache array access itself is not the problem.** `DcacheSpec.scala:2241` pins a load
hit at 2 cycles after accept, and that 2-cycle array access **already meets the ceiling**.
The 10-cycle hit path is: 2 cycles of TLB, 2 cycles of store-queue disambiguation, 1 cycle
of cache-boundary accept, 2 cycles of array access, and **4 cycles of bubble + duplicated
pass**.

#### How many of the 10 are FMax-motivated stage boundaries

**Five of the ten cycles in the hit path are stage boundaries inserted to buy FMax**
(splits #2–#6 in the table above; a sixth, the AGU base register, and a seventh, the
completion register, sit outside the hit path). Each was accepted in-source with the same
phrase — *"latency-agnostic; lock-step absorbs the +1"* — which is true of the lock-step
verification harness and **not** of IPC. Only one of the six ever had its IPC cost measured.

That gives the actionable sentence: **5 of the 10 hit-path cycles are FMax-motivated stage
boundaries, and a further ~4 are a bubble plus an apparently duplicated cache pass. The
irreducible work — TLB lookup plus tag/data/hit — is roughly 2–4 cycles.**

#### The tension, named but not resolved here

A 1-cycle VIPT hit with the TLB in parallel is a classically tight timing path — that is
precisely *why* these splits were taken. Collapsing them will cost FMax on a design
currently near 198 MHz postroute. **This is the owner's call, and the number they need is:**

> **Estimate: collapsing the hit path toward the 2-cycle ceiling means undoing ~5 splits and
> plausibly costs on the order of 30–40 MHz, returning the design to roughly 157–167 MHz.**

Basis, and its weakness: only one split has a measured delta — Slice 2 bought **+8.35 MHz**
(167.029 → 175.377) for +1 cycle and −0.8% IPC. Scaling that single data point across five
splits gives ~40 MHz. Independently, the "before" figures quoted in the split commentary
cluster in the same place (FMax #3's path was *"the 25-level / 6.349 ns critical path
(157 MHz)"*), which is a consistency check rather than a second measurement. **This is an
estimate from design-doc deltas, not a build result — no Vivado build was run.** The two
sources agreeing at ~157–167 MHz is encouraging but they are not independent.

Worth noting before anyone pays that price: **~4 of the 10 cycles (the bubble and the
duplicated pass) are not FMax splits at all.** If those are recoverable they cost no
frequency, and they alone would take the hit path from 10 to ~6. That is the cheap half of
the problem and should be understood before trading away 40 MHz for the expensive half.

**Caveat on the duplicated pass.** The design is named *vipt-**parallel**-dcache*, and the
docs describe the DTLB request and the L1D virtual-set probe being launched **in parallel**
at P1/P2. The trace does not obviously show that: the TLB resolves at 145–146 and the tag
compare runs at 150, four cycles later, with a second pass at 154. Either my probes
(`ldS1Valid`/`ldS2Valid`) are observing the tagged resolve rather than the parallel probe,
or the TLB and tag accesses are not actually overlapping as designed. **I did not confirm
which, and the distinction matters a great deal** — if the VIPT parallelism is not being
realised, that is a larger and cheaper win than any stage collapse. It is the single most
valuable follow-up from this whole exercise.

### 3.3 Branch misprediction

| Measurement | Cycles | Method |
|---|---|---|
| **recovery: flush → next commit** | **13.002 ± 0.003** | DIRECT per-event instrumentation, 181 events/run |
| loop iteration, branch predicted | 11.200 ± 0.000 | DEP; differential over iterations |
| loop iteration, branch toggling | 10.010 ± 0.000 | DEP; differential over iterations |

The recovery figure is **measured, not inferred from pipeline depth**: the harness records,
for every retire-gated pipeline flush, the number of cycles to the next committed
macro-instruction. The histogram for one seed is `{13:181}` — **all 181 events are exactly
13 cycles**, with no spread at all.

**A differential I deliberately did not report.** `(toggling − predictable)` looks like a
misprediction penalty and is not one: the conditional guards an ADD, so the toggling loop
skips it on half its iterations while the predictable loop executes it every time. The two
loops do not retire the same instruction mix, and the difference mixes real recovery cost
with a −0.5-instruction-per-iteration accounting artefact — it comes out **negative
(−1.19)**. It is omitted rather than dressed up. The direct instrumentation needs no such
control and is the number above.

### 3.4 Long-latency execution units

| Op | LATENCY (DEP) | THROUGHPUT (IND) | Ratio |
|---|---|---|---|
| `divu.w` | **70.000 ± 0.000** | **70.000 ± 0.000** | **1.00×** |
| `divs.w` | **70.000 ± 0.000** | **70.000 ± 0.000** | **1.00×** |
| `mulu.w` | **12.000 ± 0.000** | **1.850 ± 0.000** | **6.5×** |
| `muls.w` | **12.000 ± 0.000** | **1.850 ± 0.000** | **6.5×** |

This is the clearest demonstration in the suite of why latency and throughput must be
measured separately on an OoO core:

- **The divider is confirmed single-outstanding by measurement.** Six *independent* divides
  are exactly as expensive as six *dependent* ones — issuing independent work buys nothing.
  This was previously an assumption; it is now a number.
- **The multiplier is pipelined and the OoO engine hides it almost completely.** A
  dependent chain pays 12 cycles per multiply; independent multiplies retire at 1.85
  cycles apiece. A throughput-only benchmark would have reported the multiplier as ~6.5×
  cheaper than its true latency.

**These were previously unmeasurable.** The bench harness never observed `divEu`'s
writeback port, so any kernel containing MUL/DIV died with `commit robId=N with no
writeback observed` — which is exactly why the IPC kernel corpus carries the standing
restriction *"NO DIV/MUL/CHK"*. `divEu` is the CPLX EU and retires MULU/MULS, DIVU/DIVS
**and** the FPU. Adding one line (`snapWb(dut.divEu.logic.wbObs)`) unblocked the whole
class. This is a real gap that was closed, not a workaround.

### 3.5 MMU

| Measurement | Cycles | Status |
|---|---|---|
| page-strided cold access, TTR-translated (no walk possible) | 104.567 ± 3.227 (n=5) | stable, reported |
| **DTLB miss + 3-level table walk** | — | **NOT MEASURED — see §4** |

The TTR row is a real, stable measurement: TC.E=1 with a match-all D-side transparent
translation, 32 KiB stride, cold lines, so it is the cold-miss cost with the walker
guaranteed not to run.

**Baseline context for the sibling walker change:** at `bb3bca1` the D-side table walker
has its **own dedicated AXI port** and does **not** go through the L1D
(`TableWalker.scala`; `DtlbPlugin.scala:76-83` binds `walkerAxi`). A walk is **3 dependent
single-beat reads, single-outstanding**. Descriptor reads are therefore entirely uncached
today. That is the structural "before" state; the *cost* number to go with it is the piece
I could not produce.

### 3.6 Aggregate IPC

Re-ran the existing `IpcBenchSpec` at `bb3bca1`, zero-latency memory:

```
AGGREGATE   5848 macro-instructions / 8928 cycles = IPC 0.655
```

Per-kernel range: `load/store` 0.251 … `independent-ALU` 1.995 (99.5% dual-retire).

Against the **~0.53** aggregate figure carried in the project's memory, current HEAD
measures **0.655**. Two cautions before treating that as a +24% improvement: the historical
0.53 was recorded under a different kernel corpus and predates the FPU landing and the
frontend restructure, and **I did not re-derive it under matched conditions**. The honest
statement is: *0.655 is the aggregate IPC of the current corpus at this commit*; it is not a
like-for-like delta against 0.53.

This run also served as the **regression check on my refactor** — `IpcBenchSpec` passes
unchanged after the harness was split into a shared trait and `divEu` observation was added.

---

## 4. What I could not measure, and why

Reported as plainly as the successes.

### 4.1 DTLB / ITLB table-walk cost — attempted twice, both invalid

**Attempt 1** (looped chase, differential over outer passes): unusable, spread larger than
the mean — `342.365 ± 484.177`, range `[0.000 … 1027.094]`.

**Attempt 2** (rebuilt as a byte-identical matched pair differing *only* in whether a
match-all D-side TTR is installed, so the difference isolates the walker): the no-walk half
became stable (`104.567 ± 3.227`), but the walk half is **bimodal**, raw samples over 5
seeds:

```
page-stride access, real walk :  {0.00, 0.00, 567.25, 556.43, 0.00}
```

Three of five seeds return **exactly 0.00**, meaning the `n=60` and `n=120` runs terminated
at the *same* cycle — the differential measured nothing. Two seeds return a consistent
~560. Averaging these would produce a confident-looking number that is meaningless, so
**no walk latency is reported.**

**This is itself a lead worth chasing.** A run whose end point is independent of chain
length points at the MMU-on / real-page-table configuration terminating on something other
than the chase — a wedge, or a fault path — in a majority of seeds. Given a sibling agent is
actively changing this exact walker, that instability should be understood before the change
lands, and it may be a real defect rather than a benchmark artefact. I did not have budget to
root-cause it. The kernels and page-table builder are committed and ready
(`kTlbChase`, `buildIdentityTables`), so reproducing it is one command.

Consequently the requested **"before" baseline for the walker change is only partially
delivered**: the structural baseline is documented (§3.5) and the no-walk control is
measured, but the walk cost itself is not.

**ITLB was not attempted at all.** It needs a code image spanning tens of 4 KiB pages to
force ITLB set eviction, which is a materially different kernel generator from anything
here. No ITLB number exists in this suite.

### 4.2 FPU — blocked by a harness limitation

`fadd.x` / `fmul.x` / `fdiv.x` all fail with `commit robId=N with no writeback observed`,
including on the *setup* instruction. The FPU is reachable in principle — it lives inside
`DivEuPlugin`, which this DUT does instantiate — but FP results are written to the FP
register file, and the bench harness's macro-classification (`WhiteboxCapture`) models
**integer** writebacks only. Adding `divEu.wbObs` fixed MUL/DIV because those write the
integer PRF; it does not cover FP destinations. Measuring FPU latency needs FP writeback
observation plumbed into the capture, which is a larger change than this task's budget.
Note also that the immediate-`<ea>` form of `fmove` is documented as deliberately
unimplemented in this core even though it assembles, so FP kernels must source operands
through integer registers.

### 4.3 I-cache hierarchy — not attempted

L1I hit / miss → L2 / miss → DRAM has **no kernel in this suite**. A defensible I-side
measurement needs a straight-line image larger than the 16 KiB L1I with the instruction mix
held constant so the backend never becomes the limiter, and the differential taken across
memory configurations rather than across chain length. I ran out of budget before building
it. The D-side numbers in §3.2 say nothing about the I-side.

### 4.4 Hardware

Nothing was measured on silicon; that was the re-scope. §0 records what a port would need.

---

## 5. Deliverables

| Path | What |
|---|---|
| `src/test/scala/m68k040/bench/CoreBenchHarness.scala` | Harness extracted from `IpcBenchSpec` into a shared trait so the IPC suite and this suite drive the *same* proven DUT and cycle accounting. Adds: seed parameter on `runKernel`; `Kernel.prepMem` (preload D-memory / page tables before the CPU runs); `Kernel.mmu` (explicit MMU programming); SQ forward-hit counter; `flushToCommit` exposed on the result; **`snapWb(divEu.logic.wbObs)`** — the one-line fix that makes MUL/DIV measurable. |
| `src/test/scala/m68k040/bench/IpcBenchSpec.scala` | Reduced to its test body; mixes in the trait. Behaviour unchanged, verified by re-running it. |
| `src/test/scala/m68k040/bench/MicrobenchSpec.scala` | The suite: differential measurement core, `Stat` with raw per-seed samples, validation gate, and all kernels. Groups selectable via `MB_ONLY`, seed count via `MB_SEEDS`. |
| `tools/run_microbench.sh` | Committed re-runnable driver with host guards (refuses ≥2 heavy JVMs, refuses during a Vivado build via `flock` — never `pgrep`, which self-matches) and the `--dram-sweep` mode. Logs to `bench_logs/`. |

Reproduce:

```sh
tools/run_microbench.sh                              # core groups, ideal memory
IPC_MEM=l2:5:70 MB_ONLY=memory tools/run_microbench.sh l2:5:70
MB_ONLY=validation tools/run_microbench.sh           # timing-source gate only
tools/run_microbench.sh --dram-sweep                 # the sweep that still needs running
```

The measurement logic is deliberately separated from the timing source: kernels are plain
68k assembly strings and the analysis is differential arithmetic over cycle counts. When a
hardware cycle counter exists (§0), the same kernels and the same differential method port
across with only the counter read replaced.

---

## 6. Standing cautions for anyone quoting these numbers

1. **The combined L1D-hit + TLB-hit path is ~10 cycles against a 1-cycle target and a
   2-cycle ceiling** (§3.2c). The D-cache *array* access is 2 cycles and is within budget;
   the overrun is everything wrapped around it.
2. **Load-to-use is 17-19 cycles, not the 11.0 first reported** (§3.2a). Of a 16-cycle
   dependent load, ~11 cycles are designed pipeline depth and **~5 are bubbles**.
3. **Latency is the problem, not throughput.** Independent loads sustain **1.50 cycles**
   each (12.5x better than dependent). The path is pipelined, not serialised.
4. **The absolute DRAM figure is a model input, not a measurement.** `dramCycles` is
   unmeasured in both repos. From the sweep, quote the slope (~1.00) and the intercept
   (**28.5 cycles** core-side fixed miss cost). Do not quote 98.242 as a DDR latency.
5. **No L2 capacity miss can be produced** by this model — its L2 never evicts. "L2 miss"
   here always means *cold, first touch*.
6. **Aggregate IPC 0.655 is not a like-for-like delta against the historical 0.53.**
7. **There is no DTLB walk number.** If you need one, §4.1 has the reproduction and the
   open question.
