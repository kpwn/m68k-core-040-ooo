# The I-side cache-inhibited speculation gate: what it actually costs

**2026-09-04.** Follow-up to `2026-09-04-speculative-inhibited-fetch-fix.md`. The brief was:
*make the already-working fix cheap enough to merge, by replacing its drain detection with
something far cheaper — reuse existing ROB-empty / quiesce state instead of adding 377 new
endpoints.*

**Outcome: no RTL change, and the brief's premise is measurably false.**

1. The fix adds **one flip-flop**, not 377. There is nothing to reuse an existing signal
   *instead of*.
2. The cheaper predicate the brief pointed at — a ROB-head-equivalent, matching the D-side's
   `p4LaunchOk` — is **permissive**. It was built, and it let a real wrong-path 64-byte burst
   into `CM=10` space. Two further weakenings (ROB+frontend; frontend alone) were built and
   are permissive too. Permissive is not a trade at any frequency.
3. The one genuinely new timing arc (ITLB `cacheMode` into `cmdPort.ready`) is irreducible by
   construction: consulting cache mode *earlier* is the whole fix.
4. The `-0.171 ns` is best explained by placement churn on a design whose BASE post-route
   worst **six** paths all sit at exactly `+0.001 ns`. That zero-margin fragility, and the
   fact that it has never been measured, is the finding worth carrying forward.

Simulation and netlist analysis only. **No board, no SD card, no JTAG lease, and no new
Vivado run** — the mutex was held by another agent's post-route gate throughout, and the
`>= 17 GB available` admission rule was not satisfiable (14 GB). Every synthesis number below
is re-read from the three arms already run, and from the three generated netlists whose md5s
`…-fetch-fix.md` §4.5 quotes.

---

## 1. "+377 endpoints are the drain-detection registers" is wrong

`…-fetch-fix.md` §4.5 attributes FIX's 168465 `clk` setup endpoints (against BASE/CTRL's
168088) to "the `SpeculativeFetchGate` drain-detection registers (`drainedQ` plus the
frontend/decode/rename/ROB quiet terms it samples) … spread across four plugins", and
recommends "a cheaper way to compute *the machine is drained* than 377 new endpoints".

**The gate adds exactly one register.** Measured on the same three netlists:

| arm | netlist md5 | flop-driving signals | flop **bits** |
|---|---|---|---|
| BASE (`ca4b901`) | `506d3e0b8c39ca278bf3f90b18f030bd` | 8580 | **64115** |
| CTRL (`ba30f8a`) | `8319b19e8dc84e7b25eb29975728d471` | 8580 | **64115** |
| FIX  (`7f3a45f`) | `b2f6b052e51c98b9bc2d2e6c57fb0391` | 8581 | **64116** |

(Counted by parsing every non-blocking assignment target inside an `always @(posedge …)`
block of `generated/M68kFullCoreSynth.v` and summing declared widths. The **absolute** number
is a proxy — inferred memories declared `reg [w:0] m [0:n]` contribute `w+1`, not
`(w+1)*(n+1)` — but the method is identical across the three files, so the **delta** is
exact, and it is independently corroborated by the one-line `reg`-declaration diff below.)

The `reg`-declaration diff between CTRL and FIX is one line:

```
> reg                 _zz_IcachePlugin_logic_nonSpecFetch_1
```

which is `drainedQ`. Everything else the gate adds is **13 combinational wires** — the AND
tree over ~37 signals that all already existed, emitted at
`M68kFullCoreSynth.v:47278-47290`. (`IcachePlugin_logic_nonSpecFetch` is declared `reg` but
assigned in an `always @(*)` block; it is a wire, not a flop.)

### 1.1 What the +377 actually is

Post-place-and-route cell counts confirm it independently:

| arm | post-synth LUT / FF | post-route LUT / FF | `clk` **hold** endpoints | `clk` **setup** endpoints |
|---|---|---|---|---|
| BASE | 99165 / 39652 | 98765 / **39671** | **54463** | 168088 |
| CTRL | 99165 / 39652 | 98765 / **39671** | **54463** | 168088 |
| FIX  | 97453 / 39664 | 97444 / **39678** | **54470** | 168465 |

- Registers post-route: **+7**, not +377. The **hold**-endpoint delta is exactly
  `54470 − 54463 = +7`, matching the register delta — hold checks land one per flop `D` pin.
- Setup endpoints additionally cover sequential **control** pins (`CE`, `R`/`S`). The +377 is
  ~370 control pins that stopped being constant-tied under a different mapping. It is
  remapping churn, not new state.
- **FIX is 1321 LUTs SMALLER than BASE post-route** (97444 vs 98765) and 1712 smaller
  post-synth. Whatever the 0.171 ns is, it is not an area cost. All three arms ran the same
  `synth/impl_FullCore.tcl` and the same six floorplan `.xdc` files (md5-identical), so this
  is a netlist-mapping difference, not a setup difference. A ~1.7 % LUT swing from adding one
  AND tree is not *explained* here — it is reported because it is itself evidence of how
  freely this netlist re-maps, which is the same effect §4.2 blames for the timing move.

So the prescribed remedy has no target. Deleting the predicate *entirely* would recover one
flip-flop.

---

## 2. Can a weaker predicate do the job? Measured, and no

The brief asked whether "no older instruction can squash this fetch" is satisfiable by
something much weaker — in particular whether the D-side's ROB-head test
(`LsEuPlugin.p4LaunchOk`) has a sufficient I-side equivalent.

Each row is a real build of `SpeculativeFetchGate` with `drainedRaw` weakened as shown, run
against the three sibling `spec-mmio I-side` tests. The live term
`nonSpecFetch := drainedQ && (fl.ringCount === 0)` is unchanged in every row, so every
variant still carries ring-empty.

| `drainedRaw` | CONTROL-NEG | CONTROL-POS (window ARs) | THE RULE (window ARs) | verdict |
|---|---|---|---|---|
| `robQuiet` — the ROB-head equivalent | PASS (0) | PASS (**5**) | **FAIL — 1 burst @ `0x50000000`** | **PERMISSIVE** |
| `frontendQuiet && robQuiet` | PASS (0) | PASS (**5**) | **FAIL — 1 burst @ `0x50000000`** | **PERMISSIVE** |
| `frontendQuiet` alone | PASS (0) | PASS (**5**) | **FAIL — 1 burst @ `0x50000000`** | **PERMISSIVE** |
| `decodeQuiet && renameQuiet && robQuiet` | PASS (0) | PASS (**5**) | PASS (0) | not disproven — see §2.2 |
| `frontendQuiet && decodeQuiet && renameQuiet && robQuiet` (**shipped**) | PASS (0) | PASS (**5**) | PASS (0) | — |

The control-positive holds at **5** window ARs in every row, so no variant is passing by
suppressing the frontend: the run-ahead across the boundary is still happening and the
detector still sees it. A row that FAILS `THE RULE` is **proven permissive** — it issued a
real 64-byte `INCR` burst into `CM=10` space, spanning sixteen longword device registers.

### 2.1 Why ROB-empty cannot be enough

The core's own source says so, independently of this work. `FullCoreSynth.scala:176-181`
refreshes the RAS checkpoint "every cycle the ROB is fully drained (`rob.logic.count === 0`):
at that instant nothing is outstanding, so the live state is architecturally correct **(mod a
few cycles of fetch->dispatch pipeline latency)**." That parenthesis is the hole. After every
redirect and every flush the ROB is empty for several cycles while the frontend is already
fetching ahead. In the `THE RULE` probe the redirect to `0x4FFFFFF0` empties the machine, the
first line fetch (`0x4FFFFFC0`) covers the whole 8-byte program, and the run-ahead fetch of
`0x50000000` is presented while `RobPlugin_logic_count` is still zero because nothing has
retired yet. A ROB-only gate opens and the burst goes out — which is exactly what row 1
measures.

Structurally, three of the five things that can squash a fetch live *upstream* of the ROB:

| redirect source | where it lives | ROB entry? |
|---|---|---|
| fetch-time BTB/RAS prediction (`predictPending`/`predictFire`) | FetchAlign / IBuf | **no** |
| decode-side µcode resume (`ucComplexResumeValidReg`) | DecodeStage | **no** |
| a fully renamed branch in `RenameStage.uopsStaged` (a real 1-deep register) | Rename | **not yet** |
| branch resolution (`earlyPend`) | BranchEu → ROB | yes |
| commit-time exception (`excActive`) | ROB | yes |

That is why both the ROB group alone and the frontend+ROB pair are permissive.

### 2.2 The one variant that passed is NOT a licence to adopt it

`decodeQuiet && renameQuiet && robQuiet` (dropping the 15-term frontend group) passes this
probe. **It should not be adopted**, for three reasons:

- Passing one probe is absence of evidence. The frontend group's necessity rests on the
  enumeration in `SpeculativeFetchGate`'s doc comment, and the specific hole it plugs —
  `!fl.res.slot0Valid`, "the aligner cannot currently form an instruction" — is a state this
  probe's 3-NOP-plus-`BRA.S` program never lands in at the decisive cycle. The sibling test
  therefore **cannot** catch a permissive removal of the frontend group. That is a real gap
  in the probe, worth recording.
- It buys nothing. It removes ~15 combinational leaves and **zero** registers; the flop
  count is 1 either way. It cannot plausibly move a 0.171 ns number that the gate's own nets
  never appear in.
- It trades a predicate that is *provable by elimination* for one that is merely
  *unfalsified*, in exchange for nothing measurable. Against the standing rule that a
  permissive gate is unacceptable at any frequency, that is the wrong direction.

### 2.3 Before / after / revert, re-run first-hand

The sweep above doubles as the after-arm (last row). A separate **gate-off** arm forces
`ic.logic.nonSpecFetch := True`, which is behaviourally the pre-fix RTL:

| arm | CONTROL-NEG | CONTROL-POS | THE RULE | verdict |
|---|---|---|---|---|
| BEFORE — `nonSpecFetch := True` (gate inert) | PASS (0 in window, 7 ARs total) | PASS (**5** window ARs of 6) | **FAIL — 1 wrong-path burst @ `0x50000000`** | defect present |
| AFTER — shipped predicate | PASS (0 in window, 7 ARs total) | PASS (**5** window ARs of 6) | PASS (0 window ARs of 1 total) | defect gone |
| REVERT — any of the three permissive variants (§2) | PASS (0) | PASS (**5**) | **FAIL — 1 wrong-path burst** | defect returns |

The control-positive is **5 window ARs in every arm**, so the frontend is running ahead
across the boundary identically in all of them and the RULE pass is a real negative.

(`…-fetch-fix.md` §4.2's revert control recorded **2** wrong-path bursts where this one
records 1. Both arms are non-zero and that is the whole claim; the count differs because the
two arms are different trees — that one is `wt-ictrl`'s rebased `measure/ispec-netname-control`,
this one is the fix tree with the final assignment forced — and the sim seed is derived per
build. Recorded rather than smoothed over.)

---

## 3. There is no existing "the machine is drained" signal to reuse

Every candidate, audited:

| signal | what it means | why it is not this |
|---|---|---|
| `RobPlugin._frontendQuiesceActive` (`:2347`) = `stopped \|\| coreHalted \|\| debugQuiesceActive` | a **request** to stop fetching (STOP, fatal halt, debug halt) | an input to draining, not a report of it; says nothing about occupancy |
| `LsEuService.sqDrained` = `lsEu.sqEmptySig` | store queue empty | D-side only |
| `dc.maintQuiesced` | D-cache maintenance idle | D-side only |
| `debugQuiescedOut` (`FullCoreSynth.scala:526`) = `sqDrained && maintQuiesced && !debugMaintActive` | "safe for the debugger to poke memory state" | silent on frontend / decode / rename / ROB occupancy |
| `rob.logic.count === 0`, `rob.logic.flushing` | ROB occupancy | this *is* the `robQuiet` group, and §2 row 1 shows it is not sufficient |

Nor does a per-stage composite exist to lean on: `DecodeStage` has no `busy`/`idle` val
(`movemActive`, `ucActive`, `movepActive`, `fmovemxActive`, `stashValid`, the four pipe-stage
`valid`s and `queue.io.pop.valid` are only ever combined ad-hoc at each use site), and
`FetchAlignPlugin`'s only `simPublic` idle-ish signals are the three STOP-quiesce bits.
`SpeculativeFetchGate`'s enumeration is not a re-derivation of something that already
existed — the composite genuinely did not exist.

---

## 4. Where the 0.171 ns actually comes from

### 4.1 One genuinely new arc, and it is irreducible

`cmdPort.ready` gains `!inhibitedSpecBlock == (lookupCacheable || nonSpecFetch)`.
`lookupCacheable` is `xlate.rsp.cacheMode =/= INHIBITED`, and `_rsp.cacheMode`
(`ItlbPlugin.scala:409-439`) is a four-way mux whose arms are `Mux(ttInhibited, …)`, a
constant, **`tlbEntry.cacheMode`**, and `latchCmode`. `_rsp.ready` in the same `when` chain is
driven only by the *selectors* — `ttHit`, `mmuEnable`, `tlbHit`, `latchMatch` — never by an
entry's payload. So before this change `cmdPort.ready` depended on the ITLB hit decision but
not on the matched entry's data; now it does, and that data reaches FetchAlign's ring
control.

This is the one place the M3b campaign's "`cmdPort.ready` from registered state only"
property is relaxed, which the fix's own comment already states. **It cannot be registered
away**: the defect being fixed is precisely that cache mode was consulted one stage too late
(only at `doAllocate`, after both R beats had already returned). A registered cacheability is
last lookup's cacheability, which is the bug.

### 4.2 The design has no margin, and that has never been measured

BASE's post-route `clk` intra-clock WNS is `+0.001 ns`. Not "about zero" — the worst **six**
paths are all at exactly `+0.001`:

```
BASE:  0.001  0.001  0.001  0.001  0.001  0.001  0.003  0.007  0.010  0.011  0.022 …
FIX:  -0.171 -0.171 -0.171 -0.171 -0.171 -0.171 -0.170 -0.170 -0.170 -0.170  0.036 …
```

A six-way tie at `+0.001 ns` is a placer that spent every available picosecond and stopped
the instant it met the constraint. Any netlist perturbation re-runs placement into a
different local optimum and surfaces a previously near-tied family — including, as here, a
perturbation that *reduces* LUT count by 1.3 % and adds a single flip-flop. FIX's failing
family (`RobPlugin_logic_exc_fsFrameBase_reg[7]/C` →
`DcachePlugin_logic_s0Payload_lineData_reg[*]/R`) is not BASE's, and `grep` for
`nonSpecFetch` / `inhibitedSpecBlock` / `SpeculativeFetchGate` across
`fullcore_route_timing.rpt` returns **0**. This is the failure mode
`docs/superpowers/memory/fmax-resilience-postmortem-2026-08-19.md` documents.

**The CTRL arm did not measure this, and nothing else has either.** CTRL forces
`nonSpecFetch := True`, which constant-folds the whole gate away, so after constant
propagation CTRL *is* BASE — which is why it reproduced BASE to the digit (same WNS, same
TNS, the same 168088 endpoints, the same worst path). CTRL proved SpinalHDL line-number churn
is harmless. It contained no perturbation, so it says nothing about how much slack this
design loses to an arbitrary functionally-neutral one.

**Consequence: there is no perturbation-sensitivity baseline for this netlist.** Until there
is, "change X costs 0.17 ns" cannot be distinguished from "any change costs about 0.17 ns",
and every future correctness fix will look equally expensive. The recommended next experiment
for whoever picks up FMax recovery is a **noise arm**: BASE plus a functionally neutral but
non-foldable perturbation of comparable size, to establish the run-to-run floor before
attributing cost to any specific change.

---

## 5. Recommendation

**Land the fix as it stands.**

1. There is nothing to make cheaper: **one flip-flop, 13 wires, and 1321 fewer LUTs than
   BASE** post-route.
2. The cheaper predicate the brief pointed at is permissive, proven by build-and-run, as are
   two further weakenings (§2). Re-admitting the speculative device read is not a trade.
3. The one real new arc is irreducible by construction (§4.1).
4. The deployed SoC runs at **100 MHz**. FIX's worst routed path is `5.000 + 0.171 = 5.171`
   ns long, so re-timed against a 10.000 ns period *this same routed netlist* would show
   ≈`+4.83` ns; a build actually targeting 10 ns would do at least as well, having far more
   placement freedom. (Stated as an arithmetic re-scaling of a measured path, not as a
   measurement — the deployed bitstream is `M68kSocketTop`, a different netlist, and no
   100 MHz arm was run here.) `FAILED_AT_200` is a synthesis-gate result about a design
   sitting at `+0.001 ns`, not a deployment constraint.

If FMax recovery later wants the 0.17 ns back, the levers are the `exc_fsFrameBase` →
`s0Payload_lineData` reset fanout and the floorplan — not this predicate.

**Checked, since it decides whether landing this helps the boot work at all: the deployed
build does carry the gate.** `SpeculativeFetchGate.wire(host)` sits in
`FullCoreSynth.scala:298`, inside `BackendWiringPlugin`'s build area, and `SocketTop.scala:136`
instantiates that same `BackendWiringPlugin` class. So `M68kSocketTop` — the bitstream netlist
— gets it, not only `M68kFullCoreSynth`. (Elaboration-path reasoning; no socket build was run
here.)

---

## 6. Independent re-verification of the pristine tree

No RTL changed, so this is confirmation rather than new coverage — but it was actually run,
on `fix/ispec-inhibited-fetch` at `c48f9ae` with a clean `git status`, one JVM at a time
(committed launcher: `run_ispec_reverify.sh`).

| run | result | vs `…-fetch-fix.md` §4.3-4.4 |
|---|---|---|
| `ExecuteLockStepSpec` | **509 run, 509 pass, 0 fail** (1 ignored) | matches |
| `make test-fast` | **341 run, 340 pass, 1 fail** | matches — the fail is `RobPluginSpec`'s `preciseDrainBusyIn holds a debugPcApply off an in-flight precise drain…`, the documented baseline flake |
| `m68k040.ls.*` | **92 run, 82 pass, 10 fail** | matches (see the flake note below) |
| `m68k040.cache.*` | **208 run, 207 pass, 1 fail** — `VIPT D2: four distinct probe results queue without aliasing and cancel-all releases them` | matches |
| **`ls` + `cache` combined** | **300 run, 289 pass, 11 fail** | matches, and the 11 **names are byte-identical** to the baseline's |
| 200-seed fuzz | **200 seeds, 3 divergences, 0 generator failures** — seeds **80 / 109 / 127**, same three values | matches exactly |

```
[fuzz]   seed=80  [STEP] idx=79 reg D5: dut=0x00000000 oracle=0x0000007e
[fuzz]   seed=109 [STEP] idx=64 pc: dut=0x7bca4112 oracle=0x4080013e
[fuzz]   seed=127 [STEP] idx=70 pc: dut=0x3d973c90 oracle=0x4080015e
```

### 6.1 A load-sensitive flake population in `m68k040.ls.*`, worth recording

The first `ls` pass here reported **11** failures, not 10 — the baseline's 10 plus
`load miss refills then writes PRF + fires completion`. Rather than accept or dismiss it, it
was chased:

| run | tree | failures | the extra one |
|---|---|---|---|
| 1 | FIX `c48f9ae` | 11 | `load miss refills then writes PRF + fires completion` |
| 2 | FIX `c48f9ae` | 11 | **`byte access never crosses …`** — a *different* test |
| 3 | FIX `c48f9ae` | **10** | none |
| control | **BASE `ca4b901`**, `ls.*` alone | **10** | none |

Every run contains the same baseline 10; the extra rotates and then disappears. Run 3's set
and the BASE control's set are byte-identical to each other and to the `ls` half of the
baseline's combined `ls`+`cache` log (`/home/qwertyoruiop/tmp/base_lscache.log`, 289/11).
Runs 1-2 were taken with another agent's post-route Vivado gate on the box (load average
7-8); run 3 and the control after it eased (~5).

**Conclusion: an environmental, load-sensitive flake population in `m68k040.ls.*`, not a
regression** — no test that passes on BASE fails reproducibly on FIX. The operational lesson
is that `ls`-suite *name-set* equality is only meaningful on an uncontended box, and a single
`ls` run under contention will over-report by one or two.

---

## 7. Not run / not claimed

- **No new Vivado run and no three-arm re-gate**, because no RTL changed: the FIX netlist is
  byte-identical to the one already gated (md5 `b2f6b052e51c98b9bc2d2e6c57fb0391`). The
  standing table in `…-fetch-fix.md` §4.5 remains the gate result.
- **This does not fix the Mac boot.** Nobody has shown the frontend is steered into
  `0x50F0Fxxx` during boot. Unchanged from that doc's §6.
- The §2 variants are **experiments, not candidates**. They were built on a throwaway branch,
  the worktree was restored to `fix/ispec-inhibited-fetch` at `c48f9ae` with a clean status,
  and the branch was deleted; none of them exist in the shipped RTL. Both arms are
  reproducible from the committed launchers `run_ispec_predicate_sweep.sh` and
  `run_ispec_gateoff.sh`, which rewrite the gate in place and `git checkout --` it back.
- §2.2 records a **real gap in the sibling probe**: it cannot detect a permissive removal of
  the frontend quiet group. Closing that would need a program whose aligner holds a
  predicted-taken branch at the decisive cycle. Not attempted.
