# FMax Resilience Postmortem — 2026-08-19

**Status:** Analysis / recommendations, written while the Vivado build lock was
held by the v1-core session (no new synth/impl runs performed for this
document — every finding below is derived from artifacts already on disk:
`synth/fullcore_slack_matrix.rpt`, `synth/fullcore_path_analysis.rpt`,
`/tmp/path1_detail.rpt`, all from the 2026-08-19 04:30 routed checkpoint, plus
direct RTL reads).

## 1. What actually failed today

Two full postroute builds were run today against the corrected 5.000ns/200MHz
primary target (see `synth: force primary implementation target to
5.000ns/200MHz`, commit `31af87a`). Both were killed before completion for
operational reasons (resource handoff to a peer build), not because they
failed outright — the live round-by-round trend was:

| Build | Round 0 WNS | Round 1 WNS | Trend |
|---|---|---|---|
| 1st (pre-merge RTL) | -0.384ns | -0.270ns | ~+0.11ns/round |

That recovery rate, if it held, would plausibly close within a handful of
Vivado's own `phys_opt`/reroute rounds (default `POSTROUTE_ROUNDS=9`). But
relying on that convergence *by accident* every time is exactly the fragility
this doc is about — the last three FMax campaigns (`fmax-closure-fanout`
tasks #201/#217/#218/#219, the LUT-reduction campaigns #215/#240) all show
the same shape: a batch of small mechanical fixes, then a period where the
build sits close-but-failing, then one identified structural fix does the
real work. This doc tries to get ahead of the *next* occurrence of that
pattern instead of discovering it fresh each time.

## 2. Root cause: one recurring RTL anti-pattern, several instances

Every distinct worst-path *family* found in today's routed data traces back
to the same architectural shape:

> **A single, wide (>>2-way) fanout control/select signal drives the
> individually-decoded write-enable (or address) input of many separate
> per-entry flip-flops, with no register cut between the driver and the
> flops it enables.**

This is what you get from Chisel/SpinalHDL code shaped like:

```scala
val store = Vec.fill(depth)(Reg(...))
for (i <- 0 until depth) {
  when(someWideCondition && idxOh(i)) { store(i) := data }
}
```

`someWideCondition` (or the `idxOh` one-hot decode feeding it) becomes ONE
node that has to physically reach `depth` separately-placed flops' CE pins.
Depending on `depth` and how deep `someWideCondition`'s own combinational
cone is, this shows up as either (a) a routing-dominated high-fanout net, or
(b) a genuinely long same-cycle combinational chain, or — worst case, and
what we're actually seeing — both at once.

### 2.1 Concrete families found in today's data (worst 100 paths, `fullcore_slack_matrix.rpt`)

All numbers below are `Requirement=4.000ns` (the historical synth-only probe
constraint baked into that particular archived report); at the now-real
5.000ns target the same paths sit close to (some just over, some just under)
zero slack — this is exactly why the gap has been so persistently *small but
present* across builds.

1. **ROB fault-capture CE fanout — by far the largest population (~30 of the
   top-100 rows).** `DcachePlugin_logic_fsm_stateReg_reg[0]` and
   `RobPlugin_logic_exc_fsm_stateReg_reg[4]` fan out, in one uncut
   combinational cone (11–16 logic levels, 68-78% net delay), straight into
   the per-entry CE/D pins of `RobPlugin.scala`'s `faultAddrStore` /
   `faultVecStore` / `faultInstrStore` / `faultedStore` (`depth=64`,
   `Vec.fill(depth)(Reg...)`). One net alone hits **998-way fanout**
   (`RobPlugin_logic_faultAddrStore_17_reg[8]/CE`, path #9 in the archived
   analysis). This is `RobPlugin.scala:404-442`, and it is **exactly task
   #127's scope** ("Area: fold ROB per-entry Reg arrays into Mem/BRAM"),
   pending since the 2026-08-08 area spec.
   - **Why it was never folded despite being flagged for 11 days:** unlike
     every other field in this file that HAS been successfully folded
     (§3 below), `faultAddrStore` and its siblings have **up to 6 independent
     writers per cycle** — 2 alloc ports (`tail`/`tail+1`) plus 4 fault-
     completion sources (`lsFaultCompletion`, `sqFaultCompletion`,
     `euFaultCompletion`, `fpFaultCompletion`), each targeting a
     dynamically-computed, potentially-distinct ROB index. None of the three
     proven fold patterns in this codebase (§3) covers >2 simultaneous
     writers to independent addresses. That gap in the pattern library — not
     lack of will — is why this is still open.
2. **Integer register-file address decode.** The single literal *worst* path
   in the archive (`-0.991ns`, `path1_detail.rpt`): `BranchEuPlugin`'s
   `s1TgtBase[0]` reaches `RegFilePluginInt`'s LUTRAM cascade's `ADDRA0`
   input through a chain of LUT-combined nodes (Vivado packed unrelated
   logic — `RobPlugin_logic_nextPcMem_...`, `BranchEuPlugin_logic_dbExpired`
   — into the same physical slices as this path; the intermediate names are
   *placement* artifacts, not real logical relationships), ending on a
   **fanout-620** net into a `RAMD64E` address pin. This is inside
   `RegFilePluginInt`'s Xilinx-inferred LUTRAM structure
   (`RegFilePlugin.scala`) — the same LUTRAM family already flagged by task
   #198 ("Fix Int PRF LUTRAM-inference cliff") and #125 ("reduce Int-PRF
   write ports 8->5, congestion epicenter"). This is evidence that PRF
   *read/select* fanout, not just write-port count, is still a live
   contributor.
3. **Frontend imm/dst-field construction.** `_zz_DecodeStage_logic_fed_
   payload_packets_*_words_*_reg` (raw fetched instruction words) feed
   directly, combinationally, into `_zz_DecodeStage_logic_pushReg_payload_
   uops_*_imm_reg[bit]` — repeated for many individual bit positions across
   multiple destination slots (paths #2, #15, #31, #32, #41, #45, #57, #68,
   #78, #81, #84, #90-93 all belong to this family). This is a wide-decode
   combinational cone inside `DecodeStage.scala`'s immediate-field
   extraction, not a per-entry array problem — closer in shape to the
   already-fixed `memind_wide_disp_dst` class (task #239) than to the
   flop-array anti-pattern above.
4. **DivEu FP-classification residue.** `AluEuPlugin_logic_s1Ctx_uop_op` /
   `DivEuPlugin_logic_fpS1Kind`/`fpS1IntA`/`s1FpSrc` families (paths #7, #8,
   #26, #28, #59, #60, #61, #76, #85, #98) — this is the SAME fanout family
   task #218 targeted (149.95→200+MHz), but it is clearly not fully
   exhausted; residual paths in this family are still among the worst 100.
5. **Exception-FSM → DTLB.** `RobPlugin_logic_exc_fsStep_reg[0]` → `DtlbPlugin_
   logic_rspPayload_ppn` (paths #10, #86) — a smaller, two-path family, likely
   a single missing pipeline cut on the MMU-fault response mux.
6. **D-cache tag/valid/dirty fanout residue** (paths #44, #58, #71, #74, #75,
   #80, #83, #94, #99) — smaller residue of the *already-fixed* task #240
   fold; these are follow-on paths that were exposed once #240 knocked the
   dominant D-cache paths down, not evidence #240 was wrong.

**Key structural finding:** these are five-to-six *distinct, near-tied*
families, not one dominant outlier. That is why round-by-round WNS recovery
looked gradual (~0.11ns/round) rather than a single cliff — `phys_opt` has to
knock down several co-equal bottlenecks, and each round only chips at
whichever is currently worst before the next one becomes the limiter. It
also means a single hand-picked `keep` fix on any ONE family will not close
the whole gap by itself; the other near-tied families surface immediately
as the new WNS.

## 3. The proven fold patterns already in this codebase

`RobPlugin.scala` alone documents three distinct, already-shipped mitigation
patterns for this exact anti-pattern. Naming them explicitly here so future
work reuses the right one instead of re-deriving from scratch:

- **Pattern A — "B1 alloc-only fold"** (`RobPlugin.scala:79-124`, 8+ fields
  incl. the recent `fpuUnimp`/`fpuCmd`, task #249): applies when a field is
  written **only at allocation**, always at `tail`/`tail+1` (the ROB's fixed
  2-wide alloc ports). Folds into the existing `payload` Mem's 2-write-port
  shape for free — zero new hazard, because `tail != tail+1` always holds.
- **Pattern B — "single external writer fold"** (`RobPlugin.scala:255-271`,
  `nextPcMem`, task #127-adjacent "Slice B"): applies when a field has
  **exactly one writer anywhere in its lifetime** (here, `branchCompletion`).
  Folds into a plain 1-write-port `Mem` with `ram_style=distributed` for
  async read. No `MultiPortWritesSymplifier` XOR-LVT lowering, no same-cycle
  collision hazard by construction.
- **Pattern C — "multi-field single-writer bundle fold"** (task #129,
  predictor-training fields → `branchTrainMem`): applies when SEVERAL
  fields share the *same* single writer and read timing — bundle them into
  one wider `Mem` word instead of N separate single-writer Mems.

**The gap:** none of A/B/C covers a field with **multiple, temporally-
overlapping, independently-addressed writers** — which is exactly
`faultAddrStore`'s shape (2 alloc + 4 fault-completion sources). This is the
concrete reason task #127 has sat pending since 2026-08-08 despite three
successful sibling folds landing in the same window — it's not a smaller
version of the same problem, it's a genuinely different one.

### 3.1 A fourth pattern is needed: "arbitrated multi-writer fold"

Before attempting task #127 as an RTL change, the open design question that
must be answered — and does NOT require Vivado, just careful reading of
`RobPlugin.scala:975-1048` and the four completion-source producers — is:

> **Can two or more of {alloc0, alloc1, lsFaultCompletion, sqFaultCompletion,
> euFaultCompletion, fpFaultCompletion} target genuinely different ROB
> indices in the same cycle, in practice?**

If the answer is "provably no" (e.g. `euFaultCompletion` and
`fpFaultCompletion` can be shown mutually exclusive by construction, or the
S2-completion stage structurally serializes fault reporting), the fold
degrades to Pattern B/C with a priority mux ahead of ONE real write port
— zero functional risk, same technique already used for
`lsFaultSel`/`sqFaultSel`'s `keep`-attributed local select nodes
(`RobPlugin.scala:962-970`).

If the answer is "yes, they can genuinely coincide on different indices" —
which the current per-bit-CE implementation clearly assumes, since it
handles up to 6 simultaneous writes today — then a real Mem fold needs
either (a) 2 real write ports (matching the pre-existing "two ports because
alloc0/alloc1 need independent indices" precedent) with the 4 completion
sources arbitrated down to those same 2 ports by priority (possible loss:
a 3rd/4th/5th/6th simultaneous distinct-index fault-capture write would be
dropped that cycle — needs an explicit argument for why that's
architecturally impossible or provably harmless), or (b) accept a LUTRAM
multi-write (XOR-LVT) lowering for >2 ports, which is a much bigger,
higher-risk undertaking (this project's precedent for that class of fix,
the D-cache valids/dirtys LUTRAM fold, task #240, was scoped to exactly 1
writer per way — not a direct precedent for 6 writers).

**This is genuinely an open design question, not a mechanical task.** It
should go through this project's brainstorming→design→plan cycle before an
implementer agent is dispatched, specifically to answer the coincidence
question above with a real argument (not an assumption) before committing to
option (a) or (b).

## 4. Full audit: remaining `Vec.fill(depth)(...)` per-entry arrays

Grep sweep across `src/main/scala/m68k040/` for `Vec.fill(<N>)(Reg` /
`Vec.fill(<N>)(RegInit` patterns with `N` a real per-entry structure depth
(excludes small fixed-latency pipeline delay lines like `FpMulPipe`'s 7-deep
`ctxValid`, which are shift registers, not indexed-write arrays, and
therefore not subject to this anti-pattern at all — worth calling out
explicitly since they show the same `Vec.fill` syntax but a structurally
different, already-safe write pattern: sequential shift, not decoded
per-index write).

| File | Fields | Depth | Writer count (approx) | Risk | Notes |
|---|---|---|---|---|---|
| `RobPlugin.scala` | `faultAddrStore`+7 siblings | 64 | **6** (2 alloc + 4 completion), **CONFIRMED coincident** | **HIGH — proven critical-path contributor today** | Task #258 (2026-08-19): coincidence proven, real ceiling is 6/6, a plain 2-port fold would silently drop a real exception. Needs Pattern D (§3.1) design work, not a mechanical fold. See task #127. |
| `RobPlugin.scala` | `completes` | 64 | **9** (2 fixed alloc-reset + **7 dynamic** — 6 generic `completion` ports + branch) | **HIGH, same unsafe class as `faultAddrStore`** | Audited 2026-08-19 (task #260): worse than `faultAddrStore` (7 dynamic writers vs. 4). Not foldable with any proven pattern. |
| `RobPlugin.scala` | `nzvcWrStore`, `xWrStore` | 64 | **6** (2 fixed alloc-reset + **4 dynamic** `ccrCompletion` ports) | **HIGH, same unsafe class** | Audited 2026-08-19 (task #260). Sibling fields sharing the same `ccrCompletion` writer set (`nzvcValStore`/`xValStore`/`sysValStore`/`sysValRdyStore`) are the same shape, not yet individually confirmed. |
| `RobPlugin.scala` | `mispredictStore`, `branchTakenStore`, `btbIsBranchStore`, `phtValidStore` | 64 | 1 dynamic (`branchCompletion`, confirmed structurally exclusive from all other completion ports) **+ 2 fixed alloc-reset writes that are proven load-bearing** (verified via `btbIsBranchStore`'s ungated retire-time read — without the reset a reused slot reads a stale `True`) | MEDIUM, **not a 2-port case** | Audited 2026-08-19 (task #260): corrects this row's original "mostly 1-2 writers, easy" guess — genuinely needs 3 concurrent write addresses (2 fixed + 1 dynamic, proven mutually disjoint), which exceeds the proven ≤2-port Pattern A/B idiom (BRAM natively supports 2 write ports). Not implemented; a real 3-port design would be needed. |
| `RegFilePlugin.scala` | PRF backing store (`v`) | per-spec depth | write-port-limited by design | **contributing today** (path #1) but NOT a flop-array shape — it's already LUTRAM/BRAM-inferred; the fanout is on the *address/select* side, not the storage. Different fix class (see §5.2); scoping dispatched as task #259. |
| `StoreQueue.scala` | `valids`/`committed`/`robIds`/`paddrs`/`datas`/`sizes`/+9 more | 8 | 1-2 per field (alloc + drain/complete) | LOW-MEDIUM (small depth caps fanout severity) | Already had one real fold this session (task #252, `drainRowMem` elimination) — good precedent that this file rewards a fresh audit. Not re-examined 2026-08-19 (out of scope for task #260's MEDIUM-row list). |
| `Tlb.scala` (MMU) | `tags`/`ppns`/`wProt`/`sup`/`cmode`/`modif` | banks(2)×ways(4)×nSets(4) = 32 total | **1 each, confirmed** (`fillValid` only — no separate M-bit updater exists, the write-fault re-walk path reuses the same walker-completion pulse) | LOW, confirmed | Audited 2026-08-19 (task #260): genuinely single-writer, but the array is too small (32 total entries, 4-deep innermost dynamic dim) for the anti-pattern to matter — nowhere near the 100-1000-way-fanout regime seen elsewhere. `valids` has 2 writers (`fillValid` set + `invalidateAll` bulk clear, not a single-address write). Considered "not worth the risk for the payoff," no proven critical-path contribution — left as-is. |
| `IcachePlugin.scala` | `valids`, MSHR fields (`mshrValid`+6 siblings) | `ways×sets`, `MSHR_N` | small, bounded | LOW | MSHR_N is typically small (2-4); unlikely to be fanout-dominant |
| `DcachePlugin.scala` | `earlyProbe*` (5 fields) | 4 | small | LOW | Depth 4 — fanout-safe by construction |
| `UmWriteQueue.scala` | `valids`/`committed`/`robIds`/`addrs`/`bytes` | queue depth (small) | 1-2 | LOW | Not examined; likely fine given small depth |
| `LsEuPlugin.scala` | `alignedMem`+3, `pendMem`+2 | 4, 8 | 1-2 | LOW | Small depths |
| `Btb.scala`/`Ftb.scala`/`Ras.scala` | `valids`, `ras` | `entries` (BTB/FTB sizing) | 1 (train/update port) | LOW-MEDIUM | Single-writer — should be an easy Pattern-B candidate if these entries counts are large enough to matter; not measured this session |
| `RatTable.scala` | `specReg`/`commReg` (5 RAT instances: `intRat` archDepth=**20**, `nzvcRat`/`xRat`/`fpccRat` archDepth=1, `fpRat` archDepth=8) | see left | **4 each** (`writePorts=2` + `commitPorts=2`, all dynamic) | **NOT SAFE — do not fold, ever** | Audited 2026-08-19 (task #260), corrects two things: (1) writer count is 4, not the depth alone; (2) **this file's own header documents a Mem-based fold was already tried and reverted** for a real correctness bug — dropped same-cell writes on the 1-entry flag RATs (`nzvcRat`/`xRat`/`fpccRat`, both write ports hardwired to address 0) caused a genuine branch mis-resolution/loop-divergence bug. The current Reg-Vec-with-explicit-priority-loop IS that fix. **Never re-attempt a Mem fold here.** The slack-matrix hits (paths #11/#40) trace to `DecodeStage`'s queue-head-indexed combinational read feeding the RAT's write-address compare with no register cut — same family as row 3 below (frontend imm/dst-field construction), NOT a RAT-array problem. Fix belongs in `DecodeStage.scala`; tracked as task #262. Also corrects this doc's earlier factual error: `archDepth=8` is `fpRat`, not `intRat` (which is 20, `Isa.ARCH_INT_REGS`). |
| `MicroOpQueue.scala` | `bankRegs` | `rows` × `banks` | queue-shaped (few writers) | LOW | Already went through a BRAM-fold pass (task #215 Slice A/microcode ROM); this specific field may be a residual, not examined |

**Reading the table:** three rows are now confirmed HIGH (proven multi-
dynamic-writer, unsafe to fold with any pattern in this codebase) as of the
2026-08-19 audit (task #260), not just the original `faultAddrStore` row —
`completes` and `nzvcWrStore`/`xWrStore` are the same class, one of them
(`completes`) is structurally *worse* (7 dynamic writers). One row
(`mispredictStore`/`branchTakenStore`/`btbIsBranchStore`/`phtValidStore`)
moved from an optimistic MEDIUM guess to a confirmed **3-write-port
requirement** — genuinely too many for the ≤2-port idiom, not simply
"not yet attempted." The RatTable row carries the most important correction:
it's not just LOW-risk-by-depth, it's a **documented, already-reverted
prior fix** — the campaign's own history already tried and rejected a fold
here, and the real fix for its slack-matrix appearance is elsewhere
(`DecodeStage.scala`, task #262), not in this file at all.

Everything still marked plain LOW is LOW because of *small depth AND* (where
checked) *confirmed single-writer status* — the anti-pattern is a function
of `depth × writer-count × downstream logic depth`, and any of these could
become HIGH if their depth or writer count grows in a future change. The
`Tlb.scala` row is a useful negative-result precedent: single-writer alone
is not sufficient to justify a fold — a genuinely small array (32 entries)
gets no synth benefit from folding regardless of writer count, because
BRAM/LUTRAM primitives have a real minimum useful granularity well above
that size.

## 5. Recommendations — making the core resilient, not just this gate

### 5.1 Process guardrail: a per-entry-array checklist at design time

Any NEW `Vec.fill(N)(Reg...)` (or growing an existing one) with `N ≥ ~16`
should require an explicit one-line justification in the same commit
answering: **"how many independent writers does this array have per cycle,
and can two of them target different indices simultaneously?"** If the
answer is "exactly 1 (ever)" or "exactly 2, always at fixed offsets like
tail/tail+1", the array should be built as a `Mem` (Pattern A/B/C) from day
one, not as a Reg-Vec that gets flagged and folded retroactively months
later. If the answer is "more than 2, addresses vary" — that is the signal
this needs the same design-first treatment §3.1 recommends for
`faultAddrStore`, not a quick prototype.

This is cheap to enforce (it's a code-review question, not tooling) and
would have caught `faultAddrStore`'s shape at the point it grew its 4th/5th/
6th writer, rather than 11+ days after it was already flagged as a known
area/FMax cost.

### 5.2 A second, distinct root cause: PRF address/select fanout

§2's item 2 (register-file address decode, the literal worst path in
today's data) is NOT the flop-array anti-pattern — `RegFilePluginInt` is
already LUTRAM/BRAM-backed. Its problem is a *deep, LUT-combined
address-select cone* reaching the RAM's address pins with 620-way fanout.
This needs its own investigation, separate from §3/§3.1: likely candidates
are (a) whether the address/select computation itself has an avoidable
combinational hop that could be registered one cycle earlier without
changing read latency semantics, or (b) whether Vivado's LUT-combining is
packing genuinely-unrelated logic (`BranchEuPlugin`, `RobPlugin_logic_
nextPcMem`) into the same physical region purely for area, at a timing cost
that a `keep`/placement hint could prevent. This is a good candidate for a
**standalone follow-up investigation**, not something to fold into the ROB
fault-capture work.

### 5.3 Tooling: a standing "family census" step in the WNS gate

The existing `fullcore_slack_matrix.rpt` (top-100 worst paths,
`report_design_analysis -timing -max_paths 10` style detail) already
contains everything needed to do the family-clustering analysis in §2 — it
just wasn't being *read* that way as a standing practice; it's been treated
as a single WNS number rather than a categorized report. Recommend: after
every postroute build, before declaring "close but not there," grep-cluster
the top-100 by (a) driving register name prefix and (b) destination register
name prefix, same as done by hand in §2 above. A worst-path list where the
top 100 collapse into 2-3 families with a clear common root cause (like task
#218's DivEu fanout, or today's ROB fault-capture cluster) is a very
different, more actionable situation than one where the top 100 are 100
unrelated paths — and the current process doesn't distinguish between them
before deciding what to do next. This is a ~5-minute grep pass, not new
tooling — just needs to become a standing step.

### 5.4 Immediate next steps (do not require the Vivado lock)

1. Answer §3.1's coincidence question for `faultAddrStore`'s 6 writers by
   reading `lsFaultCompletion`/`sqFaultCompletion`/`euFaultCompletion`/
   `fpFaultCompletion`'s producers (`LsEuPlugin.scala`, `BranchEuPlugin.scala`,
   FP EU) — determine whether >2 can genuinely coincide on distinct indices.
   This gates whether task #127 is a Pattern-B-with-arbitration fold (safe,
   quick) or a real multi-write-port design problem (needs its own
   brainstorming cycle).
2. Once §5.4.1 is answered, either dispatch task #127 as a properly-scoped
   implementer task (if arbitration is provably safe) or write a short
   design note before touching the RTL (if not).
3. Audit the MEDIUM-risk rows in §4's table (`RobPlugin`'s other per-entry
   arrays, `Tlb.scala`'s nested arrays, `RatTable.scala`) for writer-count —
   each is a candidate for Pattern A/B/C if single/dual-writer, same
   mechanical shape as the already-successful folds.
4. Scope §5.2 (PRF address fanout) as its own investigation once Vivado
   access returns — it's the literal worst path today and is architecturally
   unrelated to the ROB work, so it shouldn't block or be blocked by task
   #127.
5. Bake §5.1's checklist question into this project's own contribution norms
   (e.g. reference it from `docs/debug-trace-taps.md`'s neighbor doc, or
   wherever future per-entry-array design decisions get recorded) so it's
   asked at design time going forward, not rediscovered per-campaign.
