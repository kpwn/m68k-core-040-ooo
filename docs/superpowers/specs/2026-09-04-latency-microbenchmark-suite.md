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
| **L1D hit** | **9.844 ± 0.000** | 4 KiB footprint < 8 KiB L1D; raw with-load 859.0/pass, control 229.0/pass, /64 accesses |
| **L1D miss → L2 hit** | **20.948 ± 0.034** | 32 KiB footprint = 4× L1D; raw 12277.5 vs 1552.0/pass, /512 accesses |
| **cold miss → model DRAM** | **93.484 ± 0.062** at `dramCycles=70` | straight-line stride-64; a fresh 64 B line every step |

All measured with `zeroFillData` and `assertChasePremise` in force (§3.2a), and the raw
halves of every subtraction are printed so the control can be audited rather than trusted.

**Cross-check against the un-subtracted method.** The L1D-hit row is 9.844, and the pure
one-instruction chase that needs no control at all gives **11.000** (§3.2a). The difference
is the control op's own latency: the control replaces the load with a register move that
itself costs ~1 cycle, so a subtracted figure necessarily reads ~1 low. **9.844 + 1 ≈ 11.0**
— the two methods agree. Where they differ, **prefer the 11.000**, which involves no
subtraction at all.

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

> **CAVEAT — the sweep predates the premise fix.** All three points above were taken with
> the PRNG-filled backing store (§3.2a), so the chase was walking scattered addresses. The
> corrected `dramCycles=70` point is **93.484 ± 0.062**, not 98.242. Every access in this
> kernel is a cold miss either way, so the **slope should be unaffected** and ~1.00 is
> expected to survive — but the **intercept is not trustworthy until the sweep is re-run**
> with `zeroFillData`. Re-running it is one command (`--dram-sweep`); I did not have budget.

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

### 3.2a Load-to-use: a retraction, and the corrected measurement

**I published two wrong numbers here before this section was rewritten. Both are retracted.**

| Attempt | Value | Verdict |
|---|---|---|
| §3.2 original, 3-op chase minus control | 11.000 | **correct**, but the method was under-justified |
| "correction" via pure chase | 18.737 | **WRONG — broken kernel premise.** Retracted. |
| corrected pure chase, premise enforced | **11.000** | **stands**, and now confirmed by a second method |

#### The premise bug — in my own kernel

Every chase kernel here depends on the **loaded value being zero**, so the address
register advances by a known constant and walks the footprint the kernel claims.
`AxiMemModel`'s default backing store is **`SparseMemory()`, whose freshly-allocated pages
are PRNG-filled** — the file says so at `AxiMemModel.scala:684-685` ("`AxiMemModel`'s own
default is still `SparseMemory()`"), which is exactly why the project ships an opt-in
`ConstFillSparseMemory`. So `a0` jumped to garbage, the chase scattered across random
addresses, and with MMU off it never faulted — it just quietly measured a **miss** path
while claiming to measure an L1D hit.

A kernel with a false premise does not crash. It still produces a differential, still
reports tight variance, and still looks like a measurement. **This is the same failure
class as an unvalidated timing source**, and it is why the suite now carries
`assertChasePremise`: after each chase run it checks how many distinct 16-byte lines the
load stream actually touched, and fails if the chain derailed. A behaving chase touches a
handful; the broken one touched hundreds.

(The identical bug independently invalidated the DTLB-walk kernel — see §4.1. Same root
cause, found by a different route.)

#### The corrected number, by two independent methods

| Method | Value |
|---|---|
| M1 differential over chain length, pure chase (DEP) | **11.000 ± 0.000** |
| M2 direct per-event, D-cache cmd→cmd (DEP) | **11.000 ± 0.000** |

The per-event interval histogram is `{11:189, 16:1, 19:1}` — **189 of 191 intervals are
exactly 11 cycles**. Two methods sharing no arithmetic agree to three decimals with zero
variance across seeds. **Dependent load-to-use is 11 cycles.**

#### Latency or serialisation? — the question that had to be settled first

| | cycles/load |
|---|---|
| **dependent** loads (pointer chase) | **11.00** |
| **independent** loads (6 dests, 6 resident lines) | **1.50** |
| **ratio** | **7.3×** |

**The load path is pipelined, not serialised.** It sustains a load every 1.5 cycles given
ILP — better than the design docs' stated initiation interval of 3 (cache port) / 9 (LS EU).
Had independent loads also cost ~11, the path would be serialised and no hit-path
restructuring would be the binding fix. They do not, so **hit-path latency is the binding
constraint** and the budget below is the right thing to chase.

### 3.2b Where the 11 cycles go — cycle-accurate trace

`MB_TRACE=trace-chase` dumps every load-path stage per cycle. Steady state is an exact
11-cycle period (P1 at 207, 218, 229 …), reproduced verbatim:

```
cycle  P1 P2 PT P3 P4 C0 C1 C2 RS CM WB
  207  #  .  .  .  .  .  .  .  .  .  .    issue ctx + registered operands
  208  .  #  .  .  .  .  .  .  .  .  .    DTLB request + L1D virtual-set probe launched
  209  .  .  #  .  .  .  .  .  .  .  .    translation response captured
  210  .  .  .  #  .  .  .  .  .  .  .    resolved PA -> store-queue query
  211  .  .  .  .  #  .  .  .  .  .  .    SQ forward response -> resolve / cache launch
  212  .  .  .  .  .  #  .  .  .  .  .    D-cache ACCEPTS the address
  213  .  .  .  .  .  .  .  #  #  .  .    byte-lane extract + DATA RETURNED
  214  .  .  .  .  .  .  .  .  .  #  #    completion + writeback / wakeup broadcast
  215  .  .  .  .  .  .  .  .  .  .  .    <-- bubble
  216  .  .  .  .  .  .  .  .  .  .  .    <-- bubble
  217  .  .  .  .  .  .  .  .  .  .  .    <-- bubble
  218  #  .  .  .  .  .  .  .  .  .  .    next dependent load issues
```

Split into the two things that must not be conflated:

| Segment | Cycles | Count |
|---|---|---|
| Front: issue ctx + registered operands | 207 | 1 |
| **HIT PATH: address formed → data returned** | **208 → 213** | **6** |
| Completion + writeback / wakeup | 214 | 1 |
| Wakeup → next dependent issue (bubble) | 215–217 | 3 |
| **Total load-to-use** | 207 → 218 | **11** |

### 3.2c The hit path against the 1-cycle target / 2-cycle ceiling

**Design target (project owner):** *"dcache hit path needs to be very fast; 1 cycle in the
L1D hit and TLB hit case would be great; 2 is our maximum."*

**Hit path = 6 cycles, against a target of 1 and a ceiling of 2.** (Quoting the 11-cycle
load-to-use against this budget would overstate it: 5 of the 11 are issue, writeback and
wakeup, which the budget does not cover.)

#### The critical finding: the cache array is not the problem

| Cycle | What it does | Verdict |
|---|---|---|
| 208 | DTLB request + virtual-set probe launched | TLB |
| 209 | translation response captured | TLB |
| 210 | resolved PA → store-queue query | **SQ disambiguation** |
| 211 | SQ forward response → resolve / cache launch | **SQ disambiguation** |
| 212 | D-cache accepts the address | cache-boundary accept |
| 213 | extract + **data returned** | **array access** |

**The D-cache array access is ONE cycle (212→213)** — accept to data. That is inside the
2-cycle ceiling and better than the 2-cycle hit `DcacheSpec.scala:2241` pins. **The array,
the tag compare and the way mux are not where the budget is being spent.**

The 6-cycle hit path is: **2 cycles of TLB + 2 cycles of store-queue disambiguation +
1 cycle of cache-boundary accept + 1 cycle of array access.** Four of the six cycles are
work *sequenced in front of* an array access that is already fast enough. Add the 3-cycle
wakeup bubble at 215–217 and that is 7 of the 11 cycles spent outside the cache array.

### 3.2d Framed against NaxRiscv — the reference design

The owner's framing: *"NaxRiscv has a fast cache hit and tlb hit case; meets 200MHz."* So a
fast hit path and 200 MHz closure are **not** in tension, and the earlier version of this
section — which estimated an "FMax cost of collapsing to 2 cycles" — **presumed the wrong
model and has been deleted.** The question is structural, not a trade.

**Sourcing discipline first.** The two commissioned comparison docs
(`2026-09-04-naxriscv-architecture-comparison.md`, `…-walker-comparison-round2.md`) were
read in full. They do **not** contain a NaxRiscv hit latency, stage list, D-side VIPT
description, way-prediction mechanism, or FMax figure — they are a decode/rename/commit
comparison and a walker-port comparison. **The 200 MHz claim is the owner's, not sourced
from these docs, and nothing about NaxRiscv's hit-path timing is asserted below that the
docs do not actually say.** Answering "what does their hit path do differently, stage by
stage" requires reading the reference tree itself
(`/home/qwertyoruiop/m68k-ooo-v2/thirdparty/NaxRiscv` @ `9f452d5`) — **that was not done
here** and is the top follow-up.

What the docs **do** establish is structural, and it lines up with the trace exactly:

1. **Their load pipe cannot back-pressure at all.** `io.load.cmd.ready := True`
   (`DataCache.scala:1324`); readiness at the client boundary is purely the arbiter's
   one-hot grant. *"Every condition that would otherwise require holding a command — miss,
   way hazard, bank busy, refill-slot collision, line locked, unique-miss — is instead
   answered as `REDO` in the fixed response window."* A command occupies the pipe for
   exactly `loadRspAt` cycles; **"No client can ever hold a cache resource."**
2. **Fixed latency, so tracking is positional** — a one-hot `History` shift register, not an
   ownership FIFO. The docs state the causality directly: *"The positional `History`
   register is not merely 'simpler'; it is only available because `DataCache`'s load pipe
   is fixed-latency."*
3. **Disambiguation is not in front of the cache access.** NaxRiscv's store→load ordering is
   enforced in the LSU, and an LSU load enters the cache with `redoOnDataHazard = False`
   (`LsuPlugin.scala:966`, `Lsu2Plugin.scala:987`) — *"the LSU enforces store→load ordering
   for its own traffic through its disambiguation logic"*, so the cache access is not gated
   behind a hazard check. **Our P3/P4 sit between translation and the cache access and cost
   2 of our 6 hit-path cycles.** The docs do **not** say whether their check runs in
   parallel with the access or elsewhere, so I claim only that it is not in this position.
4. **No structure outlives one pass through the port.** The docs' own comparison table:
   *"Early-VIPT probe tokens ⇒ W11 deadlock | No structure that outlives one port pass |
   **Yes.** W11 is the price of a feature NaxRiscv lacks."*
5. **The docs name our deepest divergence themselves:** *"Our shadow-accept variable-latency
   pipe cannot use a static scheme"*, and *"Our shadow-accept pipe cannot copy the policy
   without copying the pipe."*

**The synthesis.** Their hit path is short because there is no stall logic in it: ready is
hardwired true, latency is fixed, hazards are resolved by re-issuing a fresh command rather
than by holding or replaying in place, and nothing allocated survives a pass. Ours is a
**variable-latency, shadow-accept pipe with an early-VIPT probe whose tokens outlive a
pass**, and it pays for that in exactly the places the trace shows: 2 cycles of
disambiguation sequenced ahead of the array, a cache-boundary accept cycle, and a 3-cycle
wakeup turnaround that a fixed-latency pipe would not need (with fixed latency a consumer
can be woken at a known offset; with variable latency it must wait for actual completion).

So the honest conclusion is **not** "we must buy 2 cycles with 40 MHz". It is: **our
hit-path cost is a consequence of a variable-latency pipe with hazard checks in front of the
array, and the reference design avoids it by making the pipe fixed-latency and
non-blocking.** Whether our pipe can be made fixed-latency is a design question that this
measurement cannot answer — but it is the right question, and it is not an FMax trade.

**Two things to note before anyone touches FMax at all.** The array access is already
1 cycle, so the tag/data/way-mux path — the usual place a 1-cycle VIPT hit is won or lost —
is *not* the constraint here. And 3 of the 11 cycles are a wakeup bubble that is not a stage
at all. Neither of those is bought with frequency.

#### Store-to-load forwarding, same treatment

| | cycles |
|---|---|
| forwarded store→load→use **pair** (store + load) | **10.000** |
| single dependent load served by the L1D | **11.000** |

A forwarded pair containing *both* a store and a load costs slightly **less** than a single
cache-served dependent load, so the forward path genuinely resolves in P4 without launching
a cache access, as designed. That is a measured vindication of the forwarding design. No
comparison against m68k-ooo was run, so the *relative* advantage over that core remains an
unmeasured claim.

### 3.2e Measurement versus design intent

Design review of the docs and RTL establishes that **11 cycles of load-to-use is close to
the designed number, not a regression**:

- Intended depth is **9 stages issue→completion**
  (`2026-08-09-ipc-ls-eu-full-pipeline-design.md:559`), plus a registered completion stage
  and the IQ's wakeup hop. The implemented chain in the trace (P1, P2, P2T, P3, P4, C0, C2,
  comp/wb) matches one-for-one.
- It was deliberate. Goal G3 (`:212-216`) states the target pipeline should be *longer* than
  the previous 9-cycle load latency *"possibly by 1–2 cycles — and that is the intended
  outcome, not a cost to be minimised"*.
- **~6 of those cycles are FMax tax**: six separate splits were added to the load path for
  timing, each waved through in-source as *"latency-agnostic; lock-step absorbs the +1"*
  (e.g. `DcachePlugin.scala:860-872`, *"the 5th of this shape in this file"*). That is true
  of the lock-step verification harness and **not** of IPC.
- **Only one of the six had its IPC cost measured** (Slice 2: −0.8% aggregate IPC for
  +8.35 MHz). Nobody ever summed the six, and no document states the end-to-end figure.
- **Nothing pins it.** `DcacheSpec.scala:2241` pins the cache port's 2-cycle hit (1 of the
  11 cycles as measured); `IpcBenchSpec.scala:973` states outright that its kernels measure
  initiation interval, *"not load-use latency"*. **There was no dependent-load kernel
  anywhere in the tree before this suite.**

Given 68k code is load-dense and independent loads pipeline at 1.5 cycles, dependent-load
latency is a strong candidate for the dominant term in the 0.655 aggregate IPC — but I did
**not** measure that attribution, so it stays a hypothesis.

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

### 4.1 DTLB table-walk cost — ROOT-CAUSED (my kernel, not the walker) and since closed

I reported this as unmeasurable, with raw samples `{0.00, 0.00, 567.25, 556.43, 0.00}`, and
refused to publish a mean. **Refusing was right, and the cause has since been found — it was
my kernel, not a walker defect.**

`chaseStep` requires the loaded value to be zero. `prepMem` wrote the page tables but
**never the data pages**, and an untouched `SparseMemory` page is **PRNG-filled**. So `a0`
jumped to garbage and, under `mmuWalkD` (no D-side TTR), faulted. The short and long
variants share a prefix and derail at the *same* step, which is exactly why the differential
came out at precisely `0.00` rather than as noise.

Measured on unmodified `bb3bca1`, the walk kernel retired **66/174, 65/174, 158/174,
158/174, 69/174** — **incomplete on every seed**, including the two that produced non-zero
numbers. So even the "successful" 567.25 and 556.43 samples were timing a truncated kernel.

**This is the same root cause that invalidated my own pure-chase kernel (§3.2a)** — found by
a different route, in a different kernel, on the same day. That is what motivated
`assertChasePremise`: a chase kernel that silently truncates produces confident numbers from
a run that never happened, which is the same failure class as an unvalidated timing source.
Both are now enforced rather than assumed.

With the premise made true the kernel completes on every seed, and the walker agent closed
the measurement (their numbers, on `bench/walk-kernel-premise-fix`):

| | baseline | walker→L1D branch | Δ |
|---|---|---|---|
| walk cost, zero-latency model | 15.623 ± 0.379 | 21.507 ± 0.050 | +37.7% |
| walk cost, **L2-faithful (5/70 cyc)** | 63.528 ± 0.263 | 64.344 ± 0.173 | **+1.3%** |

The penalty collapses once memory costs anything, because upper-level descriptors hit L1D.
**ITLB was still not attempted** — it needs a code image spanning tens of 4 KiB pages, a
materially different generator, and no ITLB number exists in this suite.

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

1. **The combined L1D-hit + TLB-hit path is 6 cycles against a 1-cycle target and a
   2-cycle ceiling** (§3.2c). The D-cache **array access is 1 cycle and already meets the
   budget**; the overrun is 2 cycles of TLB plus 2 cycles of store-queue disambiguation
   sequenced *in front of* the array, plus a cache-boundary accept cycle.
2. **Dependent load-to-use is 11.000 cycles**, confirmed by two independent methods with
   zero variance (§3.2a). Earlier drafts of this doc reported 18.737 — that was a broken
   kernel premise and is **retracted**.
3. **Latency is the problem, not throughput.** Independent loads sustain **1.50 cycles**
   each (7.3x better than dependent). The path is pipelined, not serialised.
4. **Do not quote an "FMax cost" for shortening the hit path.** An earlier draft estimated
   30-40 MHz; that presumed fast-hit and high-FMax are in tension, which the NaxRiscv
   reference disproves. The gap is structural (§3.2d), not a frequency trade.
5. **Nothing about NaxRiscv's hit-path timing is sourced.** The two comparison docs contain
   no NaxRiscv hit latency, stage list, VIPT description, way-prediction mechanism or FMax
   number. Only the structural properties in §3.2d are quoted; anything more must come from
   the reference tree, which was not read here.
6. **Chase-style kernels need a premise assertion.** `AxiMemModel`'s default backing store
   is PRNG-filled; a chase that assumes zero silently walks garbage and still produces a
   confident differential. Use `zeroFillData = true` **and** `assertChasePremise`.
7. **The absolute DRAM figure is a model input, not a measurement.** `dramCycles` is
   unmeasured in both repos. From the sweep, quote the slope (~1.00) and the intercept
   (**28.5 cycles** core-side fixed miss cost), not 98.242.
8. **No L2 capacity miss can be produced** by this model — its L2 never evicts. "L2 miss"
   here always means *cold, first touch*.
9. **Aggregate IPC 0.655 is not a like-for-like delta against the historical 0.53.**
