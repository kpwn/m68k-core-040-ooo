# Microbenchmark suite: merge, and re-measurement against current HEAD

**Date:** 2026-09-05
**Branch:** `bench/microbench-merged` (NOT merged to `fmax-closure-fanout`)
**Base:** `fmax-closure-fanout` @ `5e3fe4e`
**Worktree:** `/home/qwertyoruiop/m68k-core-040-ooo-worktrees/microbench-merge`

Two jobs: land `bench/hw-microbench-suite` + `bench/walk-kernel-premise-fix` onto
current HEAD, and answer "how do the IPC and latency numbers look after today's
D-cache/MMU changes?"

Everything here is **simulation only**. No board, no SD card, no JTAG lease was
touched; a boot A/B test was running on the hardware throughout.

---

## 0. TL;DR

1. **Two of the three fixes are invisible.** The speculative-fetch gate (`8887507`)
   and the `RTE`/`committedCcr` resync (`7057e80`) are **byte-identical to the
   baseline in every row of `IpcBenchSpec`** — not "within noise", identical cycle
   counts. Bisected, not assumed.

2. **The walker→L1D change (`2db5bd3`) costs ~0.5% aggregate IPC** on the
   `IpcBenchSpec` corpus (0.659 → 0.656, +41 cycles over 8877), on kernels that
   **never perform a table walk at all**. It is a steady-state cost of the walker
   being a D-cache client, not of walking.

3. **And it is worth it.** With page-table locality — the shape real code has —
   the change **cuts TLB-miss walk cost by 44%**: 28.6 → 16.0 cycles under the
   L2-faithful memory model. The pre-existing "+1.3% on the worst case" figure is
   real but is the wrong instrument; it was measured on a kernel built to exclude
   reuse. Both are reported below.

4. **The crossover is located.** Walk cost is a flat, zero-variance **16.000** for
   working sets of 8, 32 and 128 pages, and jumps to 31.2 at 256 pages. Between 128
   and 256 pages the page-table subtree stops fitting in the 8 KiB L1D.

5. **Everything else did not move.** Store→load→use, L1D hit, branch recovery,
   DIV and MUL latency/throughput are unchanged to the last digit.

6. **Two pre-existing defects found and fixed, both test-only.** The branch tip
   `5e3fe4e` **does not compile its test tree**, and the microbenchmark suite as
   committed **does not pass on either arm**. Details in §5.

---

## 1. The merge

### 1.1 What was on the branches

`git diff HEAD...bench/hw-microbench-suite -- src/main` is empty; so is the same
diff for `bench/walk-kernel-premise-fix`. Both are test-only, and **this merge
touches no file under `src/main`** — verified with
`git diff --stat 5e3fe4e..HEAD -- src/main` (empty).

The two branches share `fb8c23a` and `8cf7d76` and then diverge: the suite branch
adds four more commits, the walk branch one different commit. Neither contains the
other, so both had to be merged.

### 1.2 The `IpcBenchSpec.scala` conflict, and why the resolution is safe

The merge base is `bb3bca1`. From there:

* **The branch** refactored `IpcBenchSpec.scala` from a 1244-line self-contained
  file into a **153-line spec** on top of a new 1264-line `CoreBenchHarness.scala`,
  shared with the new `MicrobenchSpec.scala`.
* **HEAD** made three small edits to the *same* region of `IpcBenchSpec.scala`
  (13 insertions, 4 deletions), all of them integration wiring pulled in by the
  three fixes.

Git produced one whole-file conflict hunk (lines 56–1148 vs a single line). It is
not a 1000-line semantic conflict; it is a rename/extract that git could not see.

**Resolution:** take the branch's `IpcBenchSpec.scala` wholesale (`--theirs`), then
re-apply HEAD's three edits **by hand into `CoreBenchHarness.scala`**, which is
where the code they patch now lives:

| # | HEAD edit | from | new home |
|---|---|---|---|
| 1 | `m68k040.top.SpeculativeFetchGate.wire(host)` after `robHeadValidIn` | `d615b8f` | `CoreBenchHarness.scala:112` |
| 2 | `exc.dcStoreAck := lsEu.logic.excStoreAckOut` | `486744f` | `CoreBenchHarness.scala:248` |
| 3 | walker memories folded into `dmem` | `8a6a431` | `CoreBenchHarness.scala:766` |

**Why it is safe, and how that was checked rather than asserted:**

* **The extraction is faithful.** Every non-comment source line of `bb3bca1`'s
  `IpcBenchSpec.scala` was mechanically checked for presence in
  `CoreBenchHarness.scala` + the new `IpcBenchSpec.scala`. 14 lines did not match
  verbatim; every one is a deliberate signature change of the refactor
  (`runKernel` gained a `seed` parameter; the inline `copybackDtt` TTR pokes became
  an `MmuSetup` case class whose **default is derived from `k.copybackDtt`**, so
  existing kernels get bit-identical MMU state). No behaviour was dropped.
* **Edit (3) is not optional.** `2db5bd3` deleted `DtlbPlugin.walkerAxi` and
  `ItlbPlugin.walkerAxi`. The branch's harness attaches memory models to them, so
  without this edit the harness *does not compile at all*. This is why the conflict
  could not be resolved by taking either side whole.
* **`IpcBenchSpec` does not regress.** It passes on both arms and its sanity bounds
  hold (`dependent-ALU` 1.002 ≤ 1.25; `independent-ALU` dual-retire 100.0% > 50%;
  the superscalar-gain check reports `[result]`, not the front-end-bottleneck
  `FINDING`). Its numbers at `bb3bca1` under the merged harness reproduce the
  historical table exactly (§3).

### 1.3 The walk-kernel premise fix

Both branches independently found the same false premise in `kTlbChase` — its chase
only strides correctly if the loaded long is **zero**, and `SparseMemory` PRNG-fills
fresh pages — and fixed it two different ways:

* `hw-microbench-suite`: `zeroFillData = true`, i.e. back the whole D-side store
  with a `ConstFillSparseMemory(0)`.
* `walk-kernel-premise-fix`: an explicit 4-byte poke per strided page.

`zeroFillData` is **strictly stronger** (it also covers any address the chase
reaches that the poke loop did not enumerate), so that is what survives. The other
branch's `zeroData` knob and its `MB_WALKDIAG` diagnostic test are kept, so the old
broken behaviour can still be exhibited side by side.

`assertStridePremise` (new) is now wired into the `mmu` group, so the premise is
**checked before the numbers are believed**, not after they are escalated.

---

## 2. Measurement method

* Two worktrees differing **only in `src/main`**:
  * **before** = `bench/hw-microbench-suite` @ `77c540f` (its `src/main` is exactly
    `bb3bca1`),
  * **after** = this branch @ `c5f5d62` (its `src/main` is exactly `2db5bd3`).
  The identical `MicrobenchSpec.scala` was used in both; only `CoreBenchHarness.scala`
  differs, and only by the three wiring edits above plus the shared cycle-budget fix
  (§5.2).
* `MB_SEEDS=3` on every suite row; `IPC_SEED=27992` pinned on both `IpcBenchSpec`
  arms so the two are directly comparable.
* Two memory regimes, matching the suite's own write-up: **zero-latency** for
  everything, and **`IPC_MEM=l2:5:70`** for the memory-sensitive groups.
* **Timing source validated first.** V1 NOP linearity at three length pairs, V2
  dependent-ALU chain, V3 independent-ALU: `0.500 / 0.500 / 0.500` (spread 0.0000),
  `1.000`, `0.500` — `VALIDATION PASSED` on **both** arms, zero-latency model.
* **Retired counts printed beside every differential.** Every `mmuloc` run reports
  its full macro count (`716/714`, `572/570`, `1532/1530`, `3068/3066`) on both
  arms — no truncated runs anywhere in the reported set.
* **DEP vs IND is stated per row.** Every chase and latency figure here is DEP
  (dependency chain, measuring latency); the IND rows are labelled as such.

> ### Caveat that applies to every absolute number below
> The simulated memory model's latencies are **not the real DDR**. `dramCycles` is
> an unmeasured model parameter, and the model's L2 has *unbounded* capacity (it is
> a `mutable.Set` that never evicts), so only cold misses exist — an L2 *capacity*
> miss cannot be produced at all. Anything labelled "→ model DRAM" is a model
> artefact until calibrated. What *is* real: the core-side fixed costs, and every
> **before-vs-after delta**, because both arms run the identical model.

> ### Second caveat, on the validation gate
> The V1 NOP-linearity gate **only passes under the zero-latency model**. Under
> `IPC_MEM=l2:5:70` a straight-line NOP unroll goes I-fetch-bound, and the three
> pairs give 0.635/0.696/0.676 (before) and 0.618/0.703/0.674 (after) — spreads of
> 0.061 and 0.084, both over the 0.05 bound. That is the gate correctly reporting
> that *straight-line code is not linear in N when instruction fetch costs money*,
> not a broken timing source. The suite's own write-up ran the memory group as
> `MB_ONLY=memory` for exactly this reason; the L2 phase here does the same.

---

## 3. The before/after table

Zero-latency memory unless the row says otherwise. `bb3bca1` column re-measured on
this hardware with the merged harness, not copied from the brief.

| measurement | brief's `bb3bca1` | measured `bb3bca1` | measured HEAD `5e3fe4e` | delta |
|---|---|---|---|---|
| store→load→use, forwarded (DEP) | 10.000 ± 0.000 | **10.000 ± 0.000** | **10.000 ± 0.000** | **none** |
| reference: load removed (IND) | — | 2.000 ± 0.000 | 2.000 ± 0.000 | none |
| L1D hit, M1 differential pure chase (DEP) | 11.000 ± 0.000 | **11.000 ± 0.000** | **11.000 ± 0.000** | **none** |
| L1D hit, M2 per-event D$ cmd→cmd (DEP) | 11.000 ± 0.000 | **11.000 ± 0.000** | **11.000 ± 0.000** | **none** |
| independent loads (IND) | — | 1.500 ± 0.000 | 1.500 ± 0.000 | none |
| L1D miss → L2 hit (DEP, `l2:5:70`) | ~20.9 | **20.948 ± 0.034** | **20.977 ± 0.044** | +0.03 (+0.1%) |
| cold miss → model DRAM (DEP, `l2:5:70`) | — | 93.484 ± 0.062 | 93.371 ± 0.045 | −0.11 |
| branch recovery, flush→commit | 13.002 ± 0.003 | **13.002 ± 0.003** | **13.000 ± 0.000** | −0.002 |
| loop iter, branch predicted | — | 11.200 ± 0.000 | 11.200 ± 0.000 | none |
| loop iter, branch toggling | — | 10.010 ± 0.000 | 10.010 ± 0.000 | none |
| `divu.w` latency \| throughput | 70.0 \| 70.0 | **70.000 \| 70.000** | **70.000 \| 70.000** | **none** |
| `divs.w` latency \| throughput | 70.0 \| 70.0 | 70.000 \| 70.000 | 70.000 \| 70.000 | none |
| `mulu.w` latency \| throughput | 12.0 \| 1.85 | **12.000 \| 1.850** | **12.000 \| 1.850** | **none** |
| `muls.w` latency \| throughput | 12.0 \| 1.85 | 12.000 \| 1.850 | 12.000 \| 1.850 | none |
| **aggregate IPC** | **0.655** | **0.659** | **0.656** | **−0.003 (−0.46%)** |

The brief's 0.655 vs the 0.659 measured here is a **seed** difference, not a
regression: the historical run did not pin `IPC_SEED`. Both arms here use 27992, so
the −0.003 is a like-for-like delta.

`divu.w` latency == throughput == 70.0 confirms the divider is fully serialising
(no overlap between independent divides); `mulu.w` 12.0 DEP vs 1.850 IND is the 6.5×
latency-hiding the brief warns about — measured both ways again here, both
unchanged.

### 3.1 Aggregate IPC, per kernel

| kernel | `bb3bca1` cycles | HEAD cycles | Δ |
|---|---|---|---|
| dependent-ALU | 401 | 401 | 0 |
| independent-ALU | 214 | 214 | 0 |
| load/store | 1139 | 1146 | **+7** |
| load-stream | 687 | 694 | **+7** |
| store-stream | 471 | 477 | **+6** |
| same-line-copyback | 286 | 289 | **+3** |
| shift-stream | 494 | 495 | +1 |
| shift-mixed | 462 | 462 | 0 |
| branchy | 434 | 434 | 0 |
| deep-backlog | 943 | 943 | 0 |
| hot-loop | 315 | 326 | **+11** |
| mixed | 611 | 609 | −2 |
| call-return | 2420 | 2428 | **+8** |
| **AGGREGATE** | **8877 (IPC 0.659)** | **8918 (IPC 0.656)** | **+41** |

Retired macro count is 5847 on both arms, identically, kernel by kernel — the
denominator did not move, only the cycles.

---

## 4. Attribution: a real bisect, not a guess

`IpcBenchSpec` at `IPC_SEED=27992`, one commit per row, each on its own worktree
with the speculative-fetch gate explicitly wired into the harness where the RTL
provides it:

| commit | what it adds | aggregate | cycles | per-kernel |
|---|---|---|---|---|
| `bb3bca1` | baseline | 0.659 | 8877 | — |
| `8887507` | + no speculative I-fetch into inhibited pages | **0.659** | **8877** | **byte-identical in all 13 rows** |
| `7057e80` | + `RTE` resyncs `committedCcr` | **0.659** | **8877** | **byte-identical in all 13 rows** |
| `5e3fe4e` | + walker → L1D passthrough | 0.656 | 8918 | +41 |

**All 41 cycles belong to `2db5bd3`.** The other two contribute exactly zero — as
predicted for the `RTE` fix (exception path only), and, more interestingly, also for
the fetch gate, which turns out to cost nothing at all on this corpus.

**What the 41 cycles are.** They land only on kernels that touch the D-cache, and
`IpcBenchSpec`'s kernels run MMU-off or under a match-all TTR — **not one of them
ever performs a table walk**. So this is not walk cost. The walker is now a third
client arbitrated onto the D-cache load/store port pair inside `LsEuPlugin`, and the
cost shows up as ~1% on load/store-bearing kernels even with the walker permanently
idle. `hot-loop` is the outlier at +3.5% (315 → 326). I have **not** isolated which
arbitration stage that is; that would need cycle-level tracing of the LS ready chain
and is named as unmeasured in §7 rather than guessed at.

---

## 5. Two pre-existing defects, found on the way

Both are `src/test` only. Both are fixed on this branch. Neither was caused by the
bench merge.

### 5.1 `fmax-closure-fanout` @ `5e3fe4e` does not compile its test tree

`2db5bd3` removed `DtlbPlugin.walkerAxi` / `ItlbPlugin.walkerAxi` from `src/main`
but left **six references** behind:

```
ExecuteLockStepSpec.scala:10413,10414,10578,10579
M1ThrowawayFrameIrqSpec.scala:236,237
```

`sbt Test/compile` fails with six errors. This is not caused by the bench branches —
neither branch touches either file, and `walkerAxi` is absent from `src/main` at
`5e3fe4e` by inspection. **`make test-fast` and `ExecuteLockStepSpec` cannot have
been run on this branch tip since `2db5bd3` landed.**

Fixed by deleting the stale `BehavioralMemAgent`s: the walkers reach memory through
the D-cache agent that is already attached, and in `M1ThrowawayFrameIrqSpec` they
shared `dmem.mem` anyway, so nothing observable changes.

### 5.2 The microbenchmark suite as committed does not pass

`runKernel`'s cycle budget was a flat 20000 (zero-latency) / 500000 (L2 model). The
suite's own largest kernel outgrew it:

```
before arm (bb3bca1): 5254 was not >= 10250  [chase-loop-512-ld-4]
after  arm (5e3fe4e): 5272 was not >= 10250  [chase-loop-512-ld-4]
```

`chase-loop-512-ld-4` needs 10250 macro-instructions and a dependent load chase
retires roughly one macro every four cycles. The completeness assertion then aborted
the **entire suite**, taking the `branch`, `divmul`, `fpu`, `mmu` and `mmuloc`
groups down with it — which is why the first pair of runs reported nothing past
`loaddecomp`.

It reproduces identically on both arms, so it is a property of the kernel and the
cap, not of either RTL. Fixed by scaling the budget: `max(flat, n * perMacro)` with
`perMacro` 30 / 300. The wait loop already exits the instant `n` macros retire, so a
generous budget costs a healthy kernel nothing and still terminates a wedged one.
The failure message now prints achieved cycles-per-macro too, because a truncated
run yields a plausible-looking differential rather than an obvious crash — the same
failure class as the false-premise kernels.

---

## 6. The memory hierarchy, and the walker change

### 6.1 The worst case (pre-existing kernel), reproduced

`kTlbChase` strides forward forever at 32 KiB, so no leaf descriptor is ever
revisited. It bounds the **downside** and structurally excludes the upside.

| walk cost (DEP; walk − TTR on byte-identical kernels) | `bb3bca1` | HEAD | delta |
|---|---|---|---|
| zero-latency model | 15.489 ± 0.315 | **21.522 ± 0.075** | **+6.03 (+38.9%)** |
| `l2:5:70` | 63.978 ± 0.342 | **64.672 ± 0.172** | **+0.69 (+1.1%)** |

Both reproduce the walker agent's own numbers (15.623 → 21.507, +37.7%; 63.528 →
64.344, +1.3%) to within the seed spread. **Confirmed, on the full suite.** The
penalty collapses once memory costs anything, exactly as claimed.

### 6.2 The regime the worst case excludes: page-table locality

New kernel, `kTlbLocality` (§6.4 for the geometry). Same chase, same three
instructions per step, same differential method — but the address sequence **cycles
over a bounded working set** so the page-table subtree can stay resident. Every
access still misses the DTLB, so a real 3-level walk runs every single step.

**Walk cost, `IPC_MEM=l2:5:70` — the regime that answers the owner's question:**

| working set | `bb3bca1` | HEAD | delta |
|---|---|---|---|
| 8 pages | 28.614 ± 0.115 | **16.000 ± 0.000** | **−12.61 (−44.1%)** |
| 32 pages | 28.385 ± 0.184 | **16.000 ± 0.000** | **−12.39 (−43.6%)** |
| 128 pages | 28.267 ± 0.088 | **16.000 ± 0.000** | **−12.27 (−43.4%)** |
| 256 pages | 28.379 ± 0.091 | 31.184 ± 0.107 | +2.81 (+9.9%) |

**Walk cost, zero-latency model — the crossover, cleanly:**

| working set | `bb3bca1` | HEAD | delta |
|---|---|---|---|
| 8 pages | 16.592 ± 0.177 | **16.000 ± 0.000** | −0.59 |
| 32 pages | 16.764 ± 0.153 | **16.000 ± 0.000** | −0.76 |
| 128 pages | 16.220 ± 0.165 | **16.000 ± 0.000** | −0.22 |
| 256 pages | 16.495 ± 0.169 | **23.590 ± 0.017** | **+7.10** |

The matched control (`walk = false`, match-all D-side TTR) is **13.000 ± 0.000** at
every working set, on both arms, under **both** memory models — the byte-identical
kernel with walking disabled costs the same everywhere, which is what makes the
subtraction mean only "the walk".

### 6.3 The mechanism, shown three ways

1. **Descriptor traffic appears at the D-cache port, and it is exactly 3 per walk.**
   Loads per step at `dcache.loadCmdPort`, split data vs page-table region:

   | | `bb3bca1` | HEAD |
   |---|---|---|
   | ws=8 | 0.99 data **+ 0.00 desc** | 0.99 data **+ 2.98 desc** |
   | ws=32 | 0.99 data + 0.00 desc | 0.99 data + 2.97 desc |
   | ws=128 | 1.00 data + 0.00 desc | 1.00 data + 2.99 desc |
   | ws=256 | 1.00 data + 0.00 desc | 1.00 data + 2.99 desc |

   Before, the walker's traffic is invisible here because it had its own AXI master.
   After, it is three descriptor reads per step — root, pointer, leaf — arriving on
   the same port as the LS pipe.

2. **After the change, walk cost stops depending on the memory model.** In the
   resident regime HEAD measures **16.000 with standard deviation 0.000 under
   *both* the zero-latency model and `l2:5:70`.** A walk that touched memory could
   not possibly be insensitive to a 5-cycle-vs-0-cycle memory. All three descriptor
   reads are hitting L1D.

   `bb3bca1` in the *same* kernel measures 16.5 (zero-latency) vs 28.4 (`l2:5:70`) —
   **12 cycles of exposure to memory latency per walk**, because the private walker
   AXI always went to memory regardless of how much locality the program had. That
   12-cycle gap *is* the win, and it is why the win only appears once memory costs
   something.

3. **The crossover is sharp and located.** 16.000 → 23.6 (zero-latency) / 31.2
   (`l2:5:70`) between 128 and 256 pages of working set. At 256 pages the descriptor
   footprint is 32 leaf tables × 8 lines = 256 lines plus 256 data lines, against an
   8 KiB / 16 B / 4-way L1D — 512 lines total. So the subtree stops fitting, and the
   walk cost returns to roughly the no-locality figure. **Below that, up to 128
   pages (512 KiB of touched data), it is free.**

### 6.4 Kernel geometry, and why each constant is what it is

Nothing here is by feel; every constant is set against the real hardware.

* **8-page (32 KiB) page stride.** The DTLB is 32 entries, 4-way, 2 banks keyed
  bank = vpn[0], set = vpn[2:1]. An 8-page stride holds vpn[2:0] constant, so every
  access lands in one 4-way set; with more than 4 pages that set thrashes and **every
  step takes a real 3-level walk**. This measures walk cost with descriptors hot,
  rather than accidentally measuring DTLB hits.
* **Byte stride 32 KiB + 16 B, not 32 KiB.** The L1D is 8 KiB / 16 B lines / 4-way,
  so index = addr[10:4] and a pure 32 KiB stride maps the *entire* working set onto
  **one** L1D set. The extra 16 B per page rotates the data across all 128 sets while
  leaving vpn[2:0] untouched (16 B × 255 < 4 KiB). This is the difference between
  measuring the walker and measuring a self-inflicted conflict-miss storm.
* **Leaf tables allocated 0x100 apart, not 0x1000.** A leaf table here is 64 entries
  × 4 B = 256 B; the suite's historical 0x1000 bump is 16× its true size and maps
  *every* leaf-table base onto L1D set 0. `buildIdentityTables` gained a `leafStride`
  parameter **defaulted to the old 0x1000**, so no pre-existing kernel's number moves.
* **256 pages is a hard limit**, not a taste: the +16 B rotation reaches 4 KiB at
  p = 256 and would spill into the next page, destroying the constant-vpn[2:0]
  property. Enforced by `require`.
* **nShort = 2 passes, nLong = 4 passes**, both whole numbers of passes, so the
  differential is taken entirely in the warm steady state. `nLong ≤ 1024` steps keeps
  the 10-bytes-per-step body under the 16 KiB I-cache in **both** runs, so no I-fetch
  cliff falls between them — `kChaseLoop`'s own notes record a 66–77% I-fetch-bound
  loss on long straight-line unrolls under `l2:5:70`.

### 6.5 Premise discipline on the new kernel

`kTlbLocality` sets `zeroFillData = true` **and** every reported run is preceded by
`assertLocalityPremise`, which requires the D-side data addresses to be *exactly* the
`wsPages` addresses the cycle defines — not a distinct-line bound, an exact set. A
line-count bound cannot discriminate here because the kernel legitimately touches one
new line per step.

That check needed a new `splitLoads` helper, and the reason is itself a finding:
**since `2db5bd3`, walker descriptor reads are arbitrated onto the same
`dcache.loadCmdPort` as the LS pipe**, so `ldCmdAddrs` now mixes descriptor traffic
into every MMU-on kernel. A premise assertion written against the old behaviour
would fire spuriously on HEAD. Premises are asserted against the data half; the
descriptor half is reported as a measurement in its own right (§6.3.1).

---

## 7. What I did not measure

* **Which arbitration stage costs the 41 cycles** in §4. It is confined to
  D-cache-touching kernels with the walker idle, but I did not trace it to a
  specific stall. Isolating it needs cycle-level LS ready-chain tracing.
* **`hot-loop`'s +3.5%** specifically — the largest single-kernel regression, and
  disproportionate to the others. Not explained.
* **Real-hardware anything.** No board, no JTAG, no bitstream. Every number is
  Verilator against `AxiMemModel`.
* **A DDR-calibrated absolute.** The `l2:5:70` numbers use an unmeasured
  `dramCycles`; only the deltas and the core-side fixed costs are trustworthy. The
  DRAM *sensitivity sweep* (`tools/run_microbench.sh --dram-sweep`) was **not** re-run
  on the new locality kernel — the slope/intercept of walk cost vs `dramCycles` would
  turn §6.2's win into a calibrated one, and is the obvious next step.
* **Working sets above 256 pages**, where the crossover would be characterised rather
  than merely located. Blocked by the kernel's in-page rotation limit (§6.4); a looped
  rather than unrolled variant would lift it.
* **I-side (ITLB) walks.** Every MMU kernel here keeps the I-side on a match-all TTR
  on purpose, so only D-side walk cost is measured. The same change also routes ITLB
  walks through the D-cache, and that is unmeasured.
* **No synth gate was run, and none is needed** — this branch touches no file under
  `src/main`. Said explicitly rather than skipped silently.

---

## 8. Verification

| check | result |
|---|---|
| `MicrobenchSpec`, zero-latency, 3 seeds, HEAD arm | PASS (after §5.2) |
| `MicrobenchSpec`, zero-latency, 3 seeds, `bb3bca1` arm | PASS (after §5.2) |
| `MicrobenchSpec`, `l2:5:70`, memory+mmu+mmuloc, both arms | PASS |
| `IpcBenchSpec`, both arms + both bisect points | PASS, sanity bounds hold |
| timing-source validation V1/V2/V3 | PASS on both arms (zero-latency; see §2 caveat) |
| `git diff 5e3fe4e..HEAD -- src/main` | **empty** |
| synth gate | **not run, not required** (test-only change) |

## 9. Reproducing

```bash
# after arm
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/microbench-merge
MB_SEEDS=3 sbt 'testOnly m68k040.bench.MicrobenchSpec'
MB_SEEDS=3 IPC_MEM=l2:5:70 MB_ONLY=memory,mmu,mmuloc sbt 'testOnly m68k040.bench.MicrobenchSpec'
IPC_SEED=27992 sbt 'testOnly m68k040.bench.IpcBenchSpec'

# just the locality sweep, custom working sets
MB_ONLY=mmuloc MB_WS=8,16,32,64,128,256 IPC_MEM=l2:5:70 sbt 'testOnly m68k040.bench.MicrobenchSpec'

# the false-premise diagnostic, old behaviour beside new
MB_WALKDIAG=1 MB_SEEDS=5 sbt 'testOnly m68k040.bench.MicrobenchSpec'
```

Logs: `scratchpad/results2/{before,after}_suite_{zero,l2}.log`,
`scratchpad/results/{before,after}_ipc.log`, `scratchpad/bisect/mid{1,2}-*_ipc.log`.
