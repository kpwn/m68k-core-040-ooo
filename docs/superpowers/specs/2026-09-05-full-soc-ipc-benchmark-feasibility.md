# Full-SoC IPC benchmark: feasibility scoping, and a memory-model sensitivity sweep

**Date:** 2026-09-05
**Branch:** `bench/microbench-merged`
**Status:** **SCOPING + SENSITIVITY DATA. No full-SoC build was started.**

Two asks arrived: (1) sweep `dramCycles` to answer "what does a walk cost with
~200-cycle DRAM?", and (2) scope — *before committing hours* — running the benchmarks
in the full-SoC testbench with the real `l2c_ctrl.v` in the path.

Both are answered here. The sweeps redirected themselves partway through, and the
result is a better answer than the full-SoC run would have produced.

**Bottom line: I recommend NOT building the full-SoC IPC benchmark for this
question, on measured evidence rather than cost.** The stated motivation — the sim
L2 never evicts, so it flatters the baseline — does not apply to these kernels:
their entire working set is **1.67% of the real 2 MB L2**, so a truly-evicting L2
would evict nothing and behave identically to the model (§3). Meanwhile the sweep in
§2 already bounds the thing that experiment was meant to bound, in minutes: **the 44%
walk-cost win is the conservative end of the range and grows monotonically to 88% as
L2 latency becomes more realistic.**

---

## 1. The `dramCycles` sweep

`kTlbLocality`, both arms, `MB_SEEDS=3`, `MB_ONLY=mmuloc`, `MB_WS=8,32,256`. Only
`dramCycles` varies; the model's L2 hit stays 5 cycles. **DEP** (dependency chain,
latency) throughout. Walk cost = (walk − TTR) on byte-identical kernels. Retired
counts were complete on every run (`716/714`, `572/570`, `3068/3066`), and
`assertLocalityPremise` passed on every reported configuration.

### Walk cost vs `dramCycles`

| arm | ws | dram=70 | dram=140 | dram=200 | dram=400 ⚠ |
|---|---|---|---|---|---|
| **after** (`2db5bd3`) | 8 | **16.000 ± 0.000** | **16.000 ± 0.000** | **16.000 ± 0.000** | 12.381 ± 0.031 |
| **after** | 32 | **16.000 ± 0.000** | **16.000 ± 0.000** | **16.000 ± 0.000** | 13.910 ± 0.101 |
| **after** | 256 | 31.184 ± 0.107 | 31.234 ± 0.068 | 31.124 ± 0.089 | 27.596 ± 0.101 |
| **before** (`bb3bca1`) | 8 | 28.614 ± 0.115 | 28.394 ± 0.139 | 28.514 ± 0.112 | 24.397 ± 0.077 |
| **before** | 32 | 28.385 ± 0.184 | 28.413 ± 0.177 | 28.455 ± 0.123 | 26.639 ± 0.199 |
| **before** | 256 | 28.379 ± 0.091 | 28.391 ± 0.080 | 28.184 ± 0.076 | 24.846 ± 0.037 |

### ⚠ The `dram=400` column is contaminated — do not read it as a result

The matched control (`walk = false`) is the validity check, and it holds at **exactly
13.000 ± 0.000** for dram = 70 and 140 on both arms. At **dram=400 it breaks**, moving
to 14.9–16.6 on *both* arms:

| control (TTR, no walk) | dram=70 | dram=140 | dram=200 | dram=400 |
|---|---|---|---|---|
| after, ws=8 | 13.000 | 13.000 | 13.000 | **16.619** |
| before, ws=8 | 13.000 | 13.000 | 13.000 | **16.606** |
| after, ws=256 | 13.000 | 13.000 | 13.094 | **16.618** |

At 400-cycle DRAM the unrolled code body's cold I-fetch stops being free, the control
absorbs part of it, and because the walk arm is slower per step it overlaps that
I-fetch differently — so the common term no longer cancels in the subtraction. The
apparent *fall* in walk cost at dram=400 is that artefact, not a finding. **Trust the
70/140/200 columns; discard 400.** (ws=256 at dram=200 shows the very start of it, at
13.094–13.101.)

### Answers to the three questions asked

1. **Does the after-arm stay pinned at 16.000?** **Yes** — 16.000 ± 0.000 at ws=8 and
   ws=32 across the entire clean range, and (§2) across an 8× sweep of L2 latency too.
   A walk that never touches memory cannot care about memory latency. The claim needs
   no qualification in the resident regime.
2. **How does the before-arm scale?** **Roughly flat** — 28.4–28.6 across 70/140/200.
   This is the answer to the arithmetic puzzle: three serialised descriptor reads at
   70 cycles *should* cost ~210, and they cost 28. **The pre-change descriptors never
   reach DRAM at all** — they are served by the model's non-evicting L2, so the model
   *is* flattering the baseline, exactly as suspected. It also means `dramCycles` was
   the wrong knob, which is why §2 exists.
3. **The past-crossover (ws=256) row?** The +9.9% penalty **holds** — 31.18 / 31.23 /
   31.12 across the clean range. It neither grows nor shrinks with DRAM speed, for the
   same reason: that traffic is caught by L2 as well.

**What the model can answer:** how walk cost responds to DRAM latency *within this
model*. **What it cannot:** what a walk costs on the real machine at any DRAM latency.
`dramCycles` is uncalibrated against the actual MIG/DDR4, and the model has no
row-buffer, bank, refresh, read/write-turnaround or queuing behaviour, and **no
write-side latency at all**. Nothing here is a hardware prediction.

**So, literally: at a modelled 200-cycle DRAM, a walk costs 16.000 cycles on the
current design and 28.5 on the pre-change design** — and both numbers are insensitive
to the 200.

---

## 2. The sweep the `dramCycles` result redirected me to — and the real answer

Since the baseline is flat in `dramCycles`, the parameter it is actually exposed to is
the **L2 hit latency**. Same kernels, same arms, DRAM pinned at 70, sweeping
`hitCycles`.

**The control holds at exactly 13.000 ± 0.000 at every one of these 24 points, on both
arms** — this sweep is clean end to end, unlike `dram=400`.

### Walk cost vs L2 hit latency

| arm | ws | hit=5 | hit=10 | hit=20 | hit=40 | slope (cyc per cyc of L2 hit) |
|---|---|---|---|---|---|---|
| **after** | 8 | **16.000 ± 0.000** | **16.000 ± 0.000** | **16.000 ± 0.000** | **16.000 ± 0.000** | **0.000** |
| **after** | 32 | **16.000 ± 0.000** | **16.000 ± 0.000** | **16.000 ± 0.000** | **16.000 ± 0.000** | **0.000** |
| **after** | 256 | 31.184 ± 0.107 | 38.725 ± 0.004 | 54.064 ± 0.083 | 84.493 ± 0.105 | 1.524 |
| **before** | 8 | 28.614 ± 0.115 | 42.939 ± 0.179 | 72.953 ± 0.205 | 132.844 ± 0.116 | **2.984** |
| **before** | 32 | 28.385 ± 0.184 | 43.097 ± 0.054 | 72.847 ± 0.275 | 133.194 ± 0.272 | **2.997** |
| **before** | 256 | 28.379 ± 0.091 | 43.001 ± 0.016 | 72.877 ± 0.047 | 132.923 ± 0.119 | **2.990** |

### What this says

* **The before-arm slope is 2.99 — three.** Fitted independently at three working
  sets, giving 2.984 / 2.997 / 2.990, with intercept ~13.3. That is precisely the
  three serialised descriptor reads (root → pointer → leaf), each paying full L2 hit
  latency, every walk, regardless of locality. The pre-change walker had no way to
  avoid them.
* **The after-arm slope is exactly 0.000** in the resident regime — 16.000 ± 0.000 at
  every point across an 8× sweep. Combined with §1's DRAM-invariance and the earlier
  zero-latency-model result, walk cost on the current design is **independent of every
  memory-model parameter there is**. It is L1D-resident RTL behaviour, not a model
  output.
* **Past the crossover the slope is 1.524**, about 51% of 2.99 — so at ws=256 roughly
  half the descriptor reads still hit L1D and half escape to L2. That is a quantitative
  measure of partial residency, not just "it got worse".

### Is the 44% robust, or an artefact of one favourable operating point?

**Robust, and 44% is the conservative end.** The win grows monotonically as L2 latency
becomes more realistic:

| L2 hit latency | before | after | win |
|---|---|---|---|
| 5 cyc (the model's default) | 28.614 | 16.000 | **−44.1%** |
| 10 cyc | 42.939 | 16.000 | **−62.7%** |
| 20 cyc | 72.953 | 16.000 | **−78.1%** |
| 40 cyc | 132.844 | 16.000 | **−88.0%** |

The real `l2c_ctrl.v` is a 2 MB pipelined cache behind an AXI crossbar; a 5-cycle hit
is an optimistic figure for it. So the direction of the correction is known even
without measuring it: **the more faithful the L2, the larger the win.** That is the
question the full-SoC build was commissioned to settle, settled in minutes with a
clean control at every point.

---

## 3. Why the full-SoC run would NOT remove the caveat it was meant to remove

The rationale was: *the sim L2 never evicts, which flatters the baseline; with real
`l2c_ctrl.v` you get true capacity and associativity.*

The real L2 is **2 MB, 8-way, 64 B line, 4096 sets**, fixed and not parameterised
(`/home/qwertyoruiop/macqd700-soc/rtl/soc/l2c_defs.vh:24-31`; the header at `:5`
states it outright). Eviction is genuinely real — tree-PLRU with busy-way masking and
a dirty-writeback victim buffer (`rtl/soc/l2c_ctrl.v:731-737`, `:858`, `:1112-1124`).

But here is the entire working set of these kernels, counted in **64-byte L2 lines**
(computed from the kernels' own address arithmetic — data + leaf/pointer/root
descriptors + the unrolled code body — not estimated):

| working set | data | leaf | ptr | root | code | **total** | **% of the 2 MB L2** |
|---|---|---|---|---|---|---|---|
| ws=8 | 8 | 4 | 1 | 1 | 38 | **52 lines / 3.2 KiB** | **0.16%** |
| ws=32 | 32 | 16 | 1 | 1 | 31 | **81 lines / 5.1 KiB** | **0.25%** |
| ws=128 | 128 | 64 | 1 | 1 | 81 | **275 lines / 17.2 KiB** | **0.84%** |
| ws=256 | 256 | 128 | 2 | 1 | 161 | **548 lines / 34.2 KiB** | **1.67%** |

**The largest kernel touches 1.67% of the real L2.** A truly-evicting 2 MB 8-way L2
cannot evict any of it. For these kernels the real L2 and the never-evicting model are
behaviourally the same cache; swapping one for the other changes the numbers only
through **hit latency** — which is exactly what §2 sweeps, across an 8× range, for a
few minutes instead of hours.

**A second and stronger point.** The after-arm figure depends on no memory-model
parameter at all: 16.000 ± 0.000 under the zero-latency model, across `dramCycles`
70→200, and across `hitCycles` 5→40. The L1D is real RTL in this harness, so **the
current design's walk cost is already RTL-truth.** All model uncertainty attaches to
the *before* arm — a configuration that no longer exists in the design. The uncertain
quantity is the size of the win against a superseded design, not the performance of
the shipping one. §2 bounds even that, and the bound only moves in the design's favour.

---

## 4. Feasibility, if it is built anyway

Scoped read-only against the SoC repo. **Not blocked — but more expensive and less
comparable than it looks, and sim speed is *not* the reason.**

| item | finding |
|---|---|
| **Where the 040 SoC lives** | NOT `/home/qwertyoruiop/macqd700-soc` — that checkout has no `cpu040` submodule and no `m68k040` in its Makefile at all. It is `/home/qwertyoruiop/macqd700-soc-worktrees/m68k040ooo-integration`, branch `feat/m68k040ooo-socket-integration` @ `4ffbb6f3`. |
| **Its `cpu040` vintage** | `7d74ba33` — **does not contain the walker→L1D change** (verified: `git -C cpu040 log \| grep -c "route MMU table walks through L1D"` → 0). A before/after comparison needs `M68kSocketTop.v` regenerated from *both* arms. |
| **Retire counting** | ✅ **Already works.** `RobPlugin_logic_retiredThisCycle` and `traceVec_{0,1}_*` survive into the generated Verilog as flat nets and are read at `tb/tb_fpga_top_rom.cpp:857-858` and `:986-1012`; `--public-flat-rw` is on (`Makefile:2428`). The testbench itself warns this is a raw name that "will drift with every cpu040 regen" (`:801-805`). |
| **DRAM=70 knob** | ✅ `make … SIM_DDR_READ_DELAY=70` → `rtl/board/ddr_ctrl.v:76-80`, applied once per burst at AR accept (first-word latency). **Read side only — no write-side delay knob.** The core-only model has the identical hole. |
| **The named trap** | ✅ **Confirmed real.** `Makefile:2433` emits `-DCPU_M68K040`; the testbench guards on `#ifdef CPU_M68K` (`tb/tb_fpga_top_rom.cpp:63`). `$(filter m68k,$(CPU))` is exact-word, so for `CPU=m68k040` the guard is **never satisfied** and every `CPUI_REF`/`DBGT_REF` becomes a `DeadSink` returning 0 and discarding writes — `+arch_dump_at_pc`, `+poke_word_at_pc`, `+halt_on_vec` are silent no-ops. **It does NOT break retire counting**, which goes via the separate cpu040 path above, so `+max_insts` is sound. It also cannot be fixed by adding `-DCPU_M68K`: `M68kSocketTop` is flat SpinalHDL with no `u_cpu.u_cpu.*` hierarchy, so the macro would expand to nonexistent members and fail to compile. |
| **Getting kernels in** | Backdoor poke into the DDR model (`preload_rom()`, `:1406-1442`), cold-boot only. Since `+poke_word_at_pc` is one of the dead hooks, **a ROM image is the only working route.** |
| **Build cost** | ~11 min Verilator rebuild + ~20 s sbt regen of the 20.7 MB / 382k-line `M68kSocketTop.v`, **×2 arms**. |
| **Sim speed** | ~2,000 cycles/wallclock-second. These kernels are small — the whole `IpcBenchSpec` steady-state window is 8,918 core cycles — so **the microbenchmarks themselves would be minutes, not hours.** The "tens of hours" figure in the campaign docs is for booting the Mac ROM to depth, a different workload. **Sim speed is not the blocker.** |

So: buildable in roughly a half-day. The blocker is not cost — it is §3: the
experiment would not exercise the property it was commissioned to test.

---

## 5. Recommendation

**Do not build the full-SoC IPC benchmark to answer the walk-cost question.** Per §3
it could not move the answer, and per §2 the answer is already bounded across an 8×
range of the only parameter that matters, with a clean control at every point.

**If a full-SoC run is wanted, point it at a question the core-only harness genuinely
cannot reach** — where the full SoC is the only valid instrument:

* **Contention.** The SoC crossbar carries DAFB video DMA and host-debug traffic
  against the CPU's fetch and LSU ports (`rtl/soc/fpga_top_xbar.vh:241`, `:278`,
  `:312`, `:330`). The core-only harness has **no competing masters at all**. Real IPC
  under video-refresh contention is unmeasurable here, and is already logged as an
  open item in `docs/fabric_concurrency_contract.md:394-398`.
* **A working set that actually exceeds 2 MB**, where real L2 eviction bites.
* **Write-side memory latency**, which neither model has.

**On getting a real number, the two routes named:**

1. **Calibrate `dramCycles` against the actual MIG — this is the one I recommend**,
   and it is hours, not days: capture the MIG user-interface read round-trip with an
   ILA (or an AXI traffic generator) and set `SIM_DDR_READ_DELAY` / `dramCycles` to the
   measured value. No RTL change to the core, no Stage-7 scope, and it makes **every
   existing and future sim number** interpretable at once rather than answering one
   question. State its limitation whenever it is used: it pins the *mean first-word
   latency* of a model that still has the wrong *shape* under contention — fine for a
   serialised dependent walk, not fine for throughput-under-load.
   **Caveat specific to this question:** §1 and §2 show walk cost is insensitive to
   `dramCycles` on both arms, so calibrating it would **not** change any number in this
   document. Its value is for the *other* memory rows (cold miss → DRAM, L1D miss →
   L2), not for walk cost.
2. **Add a cycle counter to the SoC** (`OFF_CYCLE_LO/HI`, `FEATURES` bit 12) — the
   better long-term investment and the only route to ground truth on silicon, but it is
   Stage-7 work in a Stage-5 build, needs a bitstream and board time (currently
   occupied by a boot A/B), and **a counter alone would not answer this question**: the
   kernels, the MMU setup and the differential method would all still have to be ported
   to the target. Build it for its own sake, not to close this measurement.

**I have started neither.** No board, no SD card, no JTAG lease was touched.

---

## 6. What this document does and does not claim

* §1 and §2 characterise **the model's** sensitivity, not the machine's. Both
  `dramCycles` and `hitCycles` are uncalibrated against real hardware. No number here
  is a hardware prediction.
* The `dram=400` column is **contaminated** and is reported only so it is not
  re-derived later as a finding; the control breaks there (§1).
* §3's footprint table is computed exactly from the kernels' address arithmetic and the
  L2's published geometry.
* §4 is a **read-only survey**. No SoC build was run, so the ~11 min build and
  ~2,000 cycles/s figures come from recorded evidence in that repo's own campaign docs,
  not from a timing run of mine.
* Premise assertions (`assertLocalityPremise`), full retired counts and DEP/IND labels
  are enforced and printed on every row of §1 and §2, identically to the core-only suite.
* The core-only numbers in
  `2026-09-05-microbench-suite-merge-and-remeasure.md` are **unchanged**; this is an
  additional instrument, not a replacement.

## 7. Reproducing

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/microbench-merge
# DRAM sweep (L2 hit pinned at 5)
for d in 70 140 200; do
  MB_SEEDS=3 MB_ONLY=mmuloc MB_WS=8,32,256 IPC_MEM=l2:5:$d \
    sbt 'testOnly m68k040.bench.MicrobenchSpec'; done
# L2-hit sweep (DRAM pinned at 70) -- the informative one
for h in 5 10 20 40; do
  MB_SEEDS=3 MB_ONLY=mmuloc MB_WS=8,32,256 IPC_MEM=l2:$h:70 \
    sbt 'testOnly m68k040.bench.MicrobenchSpec'; done
```

Logs: `scratchpad/dramsweep/{before,after}_dram{70,140,200,400}.log`,
`scratchpad/hitsweep/{before,after}_hit{5,10,20,40}.log`.
