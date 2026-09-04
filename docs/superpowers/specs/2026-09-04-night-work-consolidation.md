# Night-work consolidation, 2026-09-04

Merging three independently-verified branches onto `fmax-closure-fanout`, re-measuring
the whole bar on the merge rather than inheriting it, disposing of the two failures
that were already standing on unmodified mainline, and two housekeeping items.

Work happened on `consolidate/night-merge`, a worktree branch off `afbabdd`.
**Nothing has been merged to `fmax-closure-fanout`.** That is a decision for the
coordinator; SS8 states exactly what is being proposed.

---

## 0. TL;DR

* **All three branches merged. One real conflict, in a parameter list.** Only
  `worktree-agent-a1f87de42ebc01258` conflicted, and only in
  `ExecuteLockStepSpec.runLockStep`'s signature: Part 127 added `dcfg`/`maxCycles`/
  `cacr`, the lock-step-inversion branch added `structuralKnownGap`. All are optional
  with defaults and mutually independent, so the resolution is the union of the two
  parameter lists. No behavioural resolution was needed anywhere. SS1.
* **The merged tree is GREEN on the whole bar**, measured on the merge, not inherited:
  `fastTest` **341/341** (reconciling exactly with the expected 337 → ~341),
  `ExecuteLockStepSpec` **505/505** + 1 parked, `BsrFlushSkipSpec` **17/17**,
  `flush_younger_*` **4/4**, the six directed mispredict tests **6/6**, Part 127's two
  directed tests **23/23 + 14/14**, and fuzz **200 seeds, 3 divergences — seeds 80,
  109, 127, byte-for-byte the pre-existing set, IT DID NOT RISE**. SS2.
* **One real flake was seen once and is reported rather than hidden**, and it is NOT
  attributable to the merge: an AXI-protocol checker assertion on the **I-cache**
  attach (`AR id=1 presented while ALREADY outstanding`). It did not reproduce on an
  isolated re-run, in three independent full-suite runs on unmodified mainline, or in
  the final post-edit run. Part 127 did not touch `AxiProtocolChecker`. SS2.3.
* **Neither standing mainline failure is a regression.** Both are test expectations
  that contradict a deliberate design decision, and both are corrected. SS3, SS4.
  * The CMP2 one (task #257) is a **test that drifted**: `b9d0781` deliberately made
    that EA illegal in 2026-08-19 and updated the ported corpus but missed this case.
  * The RAS one **has never passed** — it fails identically at its own introducing
    commit, which was committed unrun. `git bisect` does not apply. It asserts a
    per-branch-precise guarantee the design explicitly does not make, and its named
    mechanism does not even occur.
* **Two previously-unrecorded RTL gaps were found while measuring, both filed, neither
  fixed** (correctly — each needs its own test and gate): the RAS `checkpointSave`
  proxy is unsound against frontend run-ahead, and slot-1 CALLS never push the RAS at
  all. SS4.
* **Postroute: the merge COSTS 5.107 MHz, and it is not noise.** Postroute WNS
  **-0.037 → -0.170 ns**, FMax **198.531 → 193.424 MHz**, TNS **-1.293 → -77.060 ns**,
  failing endpoints **73 → 1 136**, LUTs **+2 197**. Both arms ran back-to-back holding
  the project's own `flock`, uncontended. A third arm (two-tier only) splits it:
  **two-tier -0.060 ns / -2.34 MHz** (87 % of the added LUTs), **Part 127 and the rest
  -0.073 ns / -2.77 MHz** (13 % of the LUTs — deep, not wide). SS4b-4d. This is the
  two-tier reschedule's first postroute measurement; its IPC gains were reported
  without it.
* **One inference RETRACTED inside this document:** I first read `earlySuppressFe`
  appearing on the 5th-worst path as evidence that `earlyHit`'s PC compare costs. The
  two-tier-only arm shows that term in the violated set **zero** times, so it is a
  symptom of combined congestion, not a demonstrated independent cost. SS4d.

---

## 1. The merge

Base `afbabdd`. Merged in dependency order, `--no-ff` each time.

| branch | base | result |
|---|---|---|
| `worktree-agent-a0ebbb8c6d8f2209f` (Part 127, SQ flush-kept precise) | `afbabdd` | clean |
| `worktree-agent-ad806ae0de9145764` (two-tier reschedule) | `87d4089` | clean (auto-merged `ExecuteLockStepSpec`) |
| `worktree-agent-a1f87de42ebc01258` (`ArchLockStep` + allocator checker) | `87d4089` | **1 conflict** |

The conflict, in full: both branches extended `runLockStep`'s parameter list at the
same place. Part 127 added `dcfg` (D-side AXI timing), `maxCycles` and `cacr`; the
lock-step-inversion branch added `structuralKnownGap`. Every one is optional with a
default and none interacts with another, so the resolution is a straight union. The
method bodies — which both branches also edited, heavily — auto-merged, and the merged
body is coherent: `maxCycles` still feeds `val cap`, `dcfg` still reaches the D-side
`BehavioralMemAgent`, and `StructuralCapture.sampleCycle` still runs last in the
per-cycle block so it observes this cycle's commits.

**The structural check that mattered more than the conflict.** The two-tier design
document's most transferable finding is that `allocHalt` must be wired in **four**
places, and that its first implementation landed in the synthesis copy only —
producing convincing, entirely vacuous passes everywhere else. A textual merge is
exactly the operation that can silently drop one of those four. Verified post-merge,
all four survive:

```
src/main/scala/m68k040/top/FullCoreSynth.scala:110
src/test/scala/m68k040/fuzz/FuzzDut.scala:182
src/test/scala/m68k040/bench/IpcBenchSpec.scala:204
src/test/scala/m68k040/lockstep/ExecuteLockStepSpec.scala:278
```

## 2. The bar, re-measured on the merge

### 2.1 `make test-fast` / `fastTest`

```
Total number of tests run: 341
Suites: completed 215, aborted 0
Tests: succeeded 341, failed 0, canceled 0, ignored 2, pending 0
```

The two-tier branch measured **337/337** at `87d4089`. The new harness self-tests
(`HarnessSelfTestSpec`) account for the difference, exactly as predicted — this is a
reconciliation, not a regression. Note `fastTest` excludes `-l VerilatorTest`, so it
never contained either of the two standing failures; that is why they went unrecorded.

### 2.2 Fuzz: 200 seeds, **3 divergences, unchanged**

`tools/fuzz/sweep.sh 0 200 25 20`, 8 batches of 25. **200 unique seeds ran; 197 PASS.**

```
seed=80  DIVERGED[STEP] idx=79 reg D5: dut=0x00000000 oracle=0x0000007e
seed=109 DIVERGED[STEP] idx=64 pc:     dut=0x7bca4112 oracle=0x4080013e
seed=127 DIVERGED[STEP] idx=70 pc:     dut=0x3d973c90 oracle=0x4080015e
```

Byte-for-byte the pre-existing set: same three seeds, same failing index, same values.
**The count did not rise.** (`[sweep] failed batches: 3` is just the three batches that
contained a diverging seed; all eight batches produced a full 25 per-seed lines.)

*Contention caveat, stated because it is required to be.* This sweep overlapped a
second sbt JVM and, for part of its run, a Vivado build — a host-discipline violation
of mine, SS6. A fuzz lock-step verdict is deterministic per seed for a given RTL, and
all three divergences reproduced their recorded signatures exactly, so the measurement
stands; but it was not taken under the conditions it should have been.

### 2.3 `ExecuteLockStepSpec` — and an honest third failure

```
merge     : 507 run, 504 succeeded, 3 failed
mainline  : 468 run, 466 succeeded, 2 failed   (afbabdd, three independent runs, identical)
```

Two of the merge's three are the standing mainline pair (SS3, SS4). The third is new
to *this run* and needs stating plainly:

```
- whitebox: FOUR back-to-back real CPUSHL bc,(a1)/lea/dbf commits … sqDrained never
  gets stuck (…0x40887126 natural-trigger probe) *** FAILED ***
  java.lang.AssertionError: assertion failed:
    [AXI-PROTOCOL @cyc339] AR id=1 presented while ALREADY outstanding (addr=0x408000c0)
    at m68k040.sim.AxiProtocolChecker.fail(AxiMemModel.scala:219)
```

Evidence that it is a flake and not a merge regression, in order of strength:

1. **It did not reproduce on an isolated re-run of the same test on the same merged
   tree** — 2/2 pass.
2. **It did not appear in any of three independent full-suite runs on unmodified
   mainline** (466/2 each time, the same two failures).
3. **Part 127 did not touch `AxiProtocolChecker`.** Its `AxiMemModel.scala` delta is
   two new `L2Sweeps` presets; its `BehavioralMem.scala` delta is a `dcfg`
   passthrough. The failing address `0x408000c0` is program space, i.e. the **I-cache**
   attach, which has gone through `AxiMemModel` (and therefore the checker) since long
   before Part 127.
4. Every `ExecuteLockStepSpec` case runs under a fresh SpinalSim seed
   (`Start FullCoreDut case-N simulation with seed …`), and uninitialised registers
   randomise per seed.
5. **It fits a seed-flake class independently established elsewhere in this campaign
   the same day.** A sibling agent found `AguCrossSpec` passing at clean HEAD under seed
   `646783385` and failing under `2008931150`, and `RobPluginSpec`'s `debugPcApply`
   timing out at exactly 200/200 under `1996078524` while passing 44/44 standalone.
   The mechanism named there applies here directly and explains the one thing my own
   first three points did not: **adding tests shifts SpinalSim's seed sequence.** The
   merged tree carries +39 `ExecuteLockStepSpec` cases over mainline, so every
   downstream case runs under a *different* seed than it does on mainline — which is
   exactly why a latent seed-sensitive assertion would fire on the merged tree and never
   on the baseline, with no behavioural difference between them at all.

I followed the protocol that class prescribes: re-run standalone (passed 2/2), and
re-run a pristine baseline **in the same session** so the seed sequence is comparable
(three runs, 466/2 each). The counts reconcile arithmetically —
`468 mainline + 39 new − 1 removed − 1 parked = 505 run + 1 ignored`, which is what the
final post-edit run reports.

What is **not** claimed: that it is harmless. An AR-ID reuse violation is the same
class as `72f0cbc fix(icache): close AXI-ID-reuse deadlock`, so a rare surviving path
is plausible and worth its own investigation. It is reported here rather than being
dismissed as noise. It is not attributed to the merge because the evidence does not
support attributing it there.

### 2.4 The full bar, re-run after this session's own test edits

Everything below was re-run on the merged tree **after** the SS3/SS4 edits, one sbt JVM
at a time, each step preceded by a host pre-flight (`free -g` ≥ 5 GB; the batch-Vivado
count reported, not fatal — one JVM alongside a Vivado build is within budget on this
host with 11-15 GB genuinely free, four JVMs was not).

| check | result |
|---|---|
| `make test-fast` / `fastTest` | **341 run, 341 pass**, 0 failed, 2 ignored |
| `ExecuteLockStepSpec` | **505 run, 505 pass**, 0 failed, 1 ignored |
| `Cmp2Chk2DecodeSpec` (incl. the 3 new cases) | **14/14** |
| `StoreQueueSpec` (Part 127 b1, b2) | **23/23** |
| `LsEuFastPreciseSpec` (Part 127) | **14/14** |
| `BsrFlushSkipSpec` | **17/17** |
| `flush_younger_{bsr,bsr_exc,bsr_tree,rts_bsr}_reexec` | **4/4** |
| `mispredict`, `deep_mispredict`, `adv_store_squash_mispredict`, `adv_a7_spec_flush`, `adv_flush_restart_store`, `unstable_branch` | **6/6** |
| 200-seed fuzz | **197/200, 3 divergences (80/109/127) — unchanged** (SS2.2) |

**The lock-step count reconciles exactly**, which is the check that matters after a
merge plus a test edit: mainline 468 → merged 507 (Part 127's and `ArchLockStep`'s new
cases) → minus the removed `cmp2-idx` = 506 → minus the parked RAS test = **505 run + 1
ignored**. No test went missing.

The AXI-protocol flake of SS2.3 did not fire in this run either.

**The merged tree is green.** Both standing mainline failures are gone, by correcting
the tests rather than by changing behaviour: the only `src/main` edit in this entire
session is a comment.

### 2.5 Netlist for the gate

`generated/M68kFullCoreSynth.v` regenerated from the merged tree
(`sbt runMain m68k040.top.GenFullCoreSynthVerilog`), md5
**`ca77655a84766c973839462883763472`**, so the postroute gate is a pure-Vivado step
that can never overlap a JVM. Note `GenVerilog` is the wrong target — it emits
`M68kCore.v`; `impl_FullCore.tcl` reads `M68kFullCoreSynth.v`.

## 3. Standing failure #1 — CMP2.W (d8,An,Xn): the test was wrong

Reported as task #257, `Divergence(7, pc: dut=0x6a19e51c oracle=0x40800020)`.

`MicroOpAssembler.scala`'s `c2IndexedShape` forces mode 6 and mode 7/reg 3 illegal for
CMP2/CHK2. That is not an accident and not a stopgap someone forgot: it is
**`b9d0781`, 2026-08-19, "fix(decode): force CMP2/CHK2 indexed/full-format EA to
illegal (fail-safe)"**. The DUT therefore takes a correct vector-4 trap; the wild PC is
just the harness's uninitialised vector table afterwards.

The failing test predates that commit — it was written in `3e16f31`, the original
CMP2/CHK2 feature commit, when the shape was executed. `b9d0781` updated the *ported*
corpus (`chk2_cmp2_illegal_ea_traps.s` case `_c6` asserts exactly this trap, and `_c8`
the PC-indexed one) but missed this lock-step case. The tree has since held **two tests
demanding opposite behaviour from the same encoding**, one of them failing
continuously.

**Disposition: the lock-step test is wrong and is removed.** It cannot be repaired in
place — Musashi executes the instruction, so any faithful oracle comparison must
diverge. The contract moves to where it can be expressed:

* three new `Cmp2Chk2DecodeSpec` cases: mode 6 → illegal vec4, mode 7/reg 3 → illegal
  vec4, **and (d16,An) still legal** so the gate cannot go silently over-broad and the
  first two cannot pass vacuously;
* a comment at the removal site recording the contract, the history, and task #257.

**A correction to the fail-safe's own rationale, recorded for whoever does #257.** The
in-code comment says the crack "never reads an index register". That is **not true**:
`c2LoadUop` wires `srcCReg`/`srcCValid` from `c2SrcEa.indexReg`/`indexValid` and passes
`indexLong`/`indexScale` through, exactly like the generic `crackLoad` AGU path. The
brief-format sub-case therefore looks implementable on its own. What is genuinely
missing is full-format: the crack re-decodes the EA from a **two-word** window
(`Vec(words(0), words(2), words(3))`), which cannot carry a full-format bd/od chain.
The gate is right to exist; its stated reason is broader than the actual gap.

## 4. Standing failure #2 — the RAS phantom-leak test: it has never passed

Full measurement in `docs/BUG_ras_checkpoint_save_proxy_unsound.md`. Summary:

**It is not a regression.** Run at its own introducing commit `6962f72`, it fails with
the identical message. That commit's own message explains why: *"Pre-existing
uncommitted work found in this worktree at the start of this session … Committing this
now rather than discarding it, per this project's own standing rule against losing
uncommitted work."* It was committed without ever being run. `git bisect` against it —
the obvious tool, and the one this session was pointed at — is not applicable, and one
bisect probe was enough to establish that instead of seven.

**Two independent reasons it cannot pass**, both measured with a whitebox probe on the
RAS ports rather than argued:

1. `checkpointSave` is driven from `rob.count === 0`, which says nothing about the
   frontend — and the frontend runs ahead. Captured on unmodified mainline: on the
   *same cycle* as a wrong-path push, `save=true` and `ckCount` goes 0 → 1, so the
   checkpoint stores the phantom as if architecturally correct and the later restore
   faithfully restores it. In this particular program the mispredicting `beq` is the
   third instruction, so the entire excursion happens while the ROB is still filling:
   there is **no seed** for which it takes a wrong-path push *and* keeps a clean
   checkpoint. Ras.scala's own class comment concedes this window ("module the few
   cycles of fetch->dispatch pipeline latency, an accepted, bounded approximation") —
   the finding is that it is load-bearing, not marginal.
2. The test's named mechanism does not occur. `bsr wrongcall` is emitted in fetch
   **slot 1**, and `rasPushValid := feed.fire && !faultHold && s0IsCall` fires only for
   slot 0 — so the "UNMATCHED bsr" never pushes at all. The push actually observed is
   a wrong-path `bsr leaf` reached by fall-through, and it *is* matched, by its own
   wrong-path `rts` two cycles later.

That second point is a **second, previously-unrecorded gap in its own right**: slot-1
RETURNS are deliberately deferred to slot 0 so they always get a prediction
(`slot1WouldRasPred`, with a comment explaining that otherwise "every return
mispredicts"), but slot-1 CALLS get no equivalent treatment and simply do not push. For
any BSR/JSR that aligns into slot 1 the RAS is one entry short and the matching RTS
pops the wrong entry. Architecturally harmless — the branch EU verifies every return
target — but a silent, alignment-dependent hole in exactly the structure the RAS is.

**Disposition: parked as `ignore`, body kept verbatim, with the measurement filed.**
Not deleted, because the property it wants to test is real. Not "fixed" by weakening
the assertion, because a version that passes on this program would be vacuous: reviving
it needs the ROB kept non-empty across the whole excursion *and* the unmatched call
pinned into fetch slot 0, and the second half cannot be arranged from assembly text.
Both that and the sound RTL fix (a retire-driven committed shadow, mirroring
`RenameStage`'s committed RAT/Freelist — the ROB already knows `stkPush`/`isReturn` at
retire) are **scoped and not started**. They are retire-path plumbing and need their
own design pass, test and gate.

## 4b. The postroute gate: the merge costs 5.1 MHz, and it is not noise

`synth/impl_FullCore.tcl`, stock flow, `POSTROUTE_ROUNDS` default 9, 5.000 ns sign-off.
**Both arms ran back-to-back on the same machine, each holding
`/var/tmp/m68k-ooo-vivado.lock` exclusively via `flock`**, so neither overlapped the
other, the ILA build, or any JVM. This is the A/B the brief asked for, on postroute and
not on OOC.

| | baseline `afbabdd` | + two-tier ONLY | full merge (3 branches) |
|---|---|---|---|
| **postroute WNS** | **-0.037 ns** | **-0.097 ns** | **-0.170 ns** |
| **achieved FMax** | **198.531 MHz** | **196.194 MHz** | **193.424 MHz** |
| **TNS** | **-1.293 ns** | **-16.907 ns** | **-77.060 ns** |
| failing endpoints | 73 / 168 004 | 395 / 168 112 | 1 136 / 168 204 |
| CLB LUTs | 97 778 (45.07 %) | 99 681 (45.94 %) | 99 975 (46.08 %) |
| post-synth WNS | -0.989 ns | -1.559 ns | -2.108 ns |
| hold (WHS / THS) | +0.010 / 0.000 | +0.012 / 0.000 | +0.023 / 0.000 |
| verdict | `FAILED_AT_200` | `FAILED_AT_200` | `FAILED_AT_200` |

**The merge costs 5.107 MHz (-0.133 ns WNS). That is a real regression, not noise, and
it should not be waved away with the 0.919 ns figure** — that noise floor is for the
*OOC* gate, and this is postroute. Four independent things say so:

1. **TNS moved 60x and the failing-endpoint count 15.6x.** Noise moves WNS on one path;
   it does not take 73 failing endpoints to 1 136.
2. **The convergence shapes differ.** Baseline plateaus at -0.037 from round 4 and holds
   flat to round 9; the two-tier arm plateaus at -0.097 from round 3. The full merge is
   *still improving at round 9* (-0.639 → -0.332 → … → -0.183 → -0.170) — a netlist the
   router never finished fighting.
3. **The worst-path families are disjoint between arms** (below).
4. **+2 197 LUTs** is real added logic.

### 4c. Attribution: it splits roughly in half, and NOT the way the area does

The two-tier-only arm was built (`consolidate/twotier-only` = `afbabdd` + that branch
alone) and gated identically, so the decomposition is measured rather than argued:

| | ΔWNS vs baseline | ΔFMax | ΔLUTs |
|---|---|---|---|
| two-tier reschedule | **-0.060 ns** (45 %) | -2.337 MHz | **+1 903** (87 %) |
| Part 127 + `ArchLockStep` (by subtraction) | **-0.073 ns** (55 %) | -2.770 MHz | +294 (13 %) |

**The two contributions are inverted between area and timing.** The two-tier reschedule
brings 87 % of the added logic but only 45 % of the timing loss — it is *wide*. Part 127
(plus the `ArchLockStep` branch, whose only `src/main` delta is `simPublic`
name-preservation, so it is almost certainly all Part 127) adds barely 294 LUTs and
costs *more* timing — it is *deep*, i.e. it lands on a path that was already close.

This also means **the two-tier reschedule's postroute cost, measured here for the first
time, is -2.34 MHz** — the number that was missing when its IPC gains (+10.23 % on
`deep-backlog`, +1.24 % aggregate) were reported. At 196.194 vs 198.531 MHz the clock
loss is 1.18 %, so on aggregate IPC the branch is very slightly net-positive and on
`deep-backlog` clearly so. That trade is the coordinator's call, not mine, but it is now
a trade with both numbers on the table.

### 4d. The two named arcs — one correction

The brief and the two-tier document named two arcs for this gate to examine.

**`earlyHit`'s 32-bit PC compare on the retire cone.** In the **full merge** the routed
report puts `RobPlugin_logic_earlySuppressFe_i_5/O` inside the 5th-worst path
(-0.167 ns, 0.003 ns off the worst):

```
  Source:      RobPlugin_logic_completes_45_reg/C
  Destination: RobPlugin_logic_debugLivePcReg_reg[6]/D
  Logic Levels: 20  (LUT3=1 LUT4=2 LUT5=4 LUT6=13)
```

**I initially read that as evidence the compare costs, and I am retracting that
reading.** In the **two-tier-only** arm — same logic, same flow — `earlySuppressFe`,
`earlyPend` and `allocHalt` appear in the violated set **zero** times, and the worst
paths are entirely different (`RobPlugin exc.fsStep → Dtlb rspPayload.fault`,
`FetchAlign stalled → Icache mshrPa`). The Tier-2 term only surfaces once the *combined*
netlist is congested. So it is a **symptom of the congestion, not a demonstrated
independent cost**. It remains the cheapest thing to try — dropping it leaves the robId
match and the no-other-flush-source guard, which are already fail-safe — but this gate
did not prove it is the lever.

**The `!allocHalt` term on the rename ready chain** does not appear in the violated set
in either arm. The nearest rename paths (`_zz_RenameStage_logic_uopsStaged_payload_* →
IssueQueuePlugin coldWay*`) are **MET** at +0.034 to +0.037 ns — near-tied, but passing.

Worst-path families, for the record:

* **baseline**: `DivEu fpNarrow f64SigR`; `DcachePlugin fsm.stateReg → RobPlugin
  faultDynStore/faultedStore` (the known `faultDynMem` family).
* **two-tier only**: `RobPlugin exc.fsStep → Dtlb rspPayload.fault`; `FetchAlign stalled
  → Icache mshrPa`.
* **full merge**: `DecodeStage ucPendValid → ucComplexResumeTargetReg`; `AluEu
  s1Ctx.uop.op → RobPlugin sysValStore`; `Gshare pht → FetchAlign fetchPc`; `RobPlugin
  completes → debugLivePcReg`.

Three arms, three disjoint sets, all several-way near-tied — the "5-6 near-tied
worst-path families, not one outlier" shape the FMax resilience postmortem describes.
There is no single arc to cut here.

**No design was contorted for this number.** Correctness outranks FMax by standing
instruction; the numbers are reported as they came out.

## 5. Housekeeping

**Part 127 moved to the campaign document.** It was written in a core-repo worktree as
`docs/BUG_calibration_word_part127.md` with a filing note saying it must be appended to
`macqd700-soc:docs/BUG_calibration_word_misplaced_0d00.md` when the core changes
merged. Done: appended as `## Part 127 (2026-09-04, follow-up session)`, headings
demoted one level to nest like Parts 120-126, filing note replaced by a provenance
note, core-side copy deleted in the same core commit. Content preservation was
**verified programmatically**, not eyeballed: 597 body lines on both sides, identical
after normalising heading level.

**The CHK/CHK2 flags bug is cross-referenced from the two sites that hid it.**
`docs/BUG_chk_chk2_flags_not_committed_on_trap.md` is already reached from the test
suite via `structuralKnownGap` on `chk-neg` and `chk2-oob`. Added: a pointer at
`RobPlugin`'s `ccrFold` `CommitObs` field (the RTL side, with the scoped
`entryNzvcWriteValid` fix direction), and a pointer at `WhiteboxCapture`'s fold — the
harness line that **synthesised the flag the DUT never committed and then compared its
own synthesis against the oracle**, which is precisely why every existing test passed.
That line is load-bearing for every other exception step and must not be "fixed"; the
comment says so. **No RTL change was made for this bug**, per instruction.

## 6. Host discipline: a real over-budget, and a RETRACTED accusation

Two separate things happened here and they must not be conflated.

**Retracted — no build was destroyed.** Mid-session I was told, and initially recorded
here as fact, that my concurrency had OOM-killed a running Vivado `full_impl` building
the ILA bitstream, costing ~40 minutes on the decisive experiment for the project's
remaining boot blocker. **That did not happen. The claim is withdrawn.** The ILA build
was alive throughout and is still running. The diagnosis rested on two errors: a
process filter matching `$1=="vivado"` on `comm`, which misses the `flock`-wrapped
invocation and so made a live build look dead; and reading a routine per-phase
`free physical = 419` line in the Vivado log as a death marker. **Nothing I did killed
anything.** This paragraph is left in rather than deleted because the retraction is
part of the record, and because the bad process filter is worth naming: use

```
ps -eo pid,etime,args | grep -- "-mode batch" | grep -v grep
```

not the `comm`-based form, and never a bare `pgrep -f vivado` (it self-matches).

**Stands on its own merits — I was genuinely over budget.** Four concurrent JVMs
against a documented **two heavy JVM** budget on a 29 GB host, with `-Xmx6G` fuzz
batches, pushing the host to ~10 GB of swap. That is over budget whether or not it
caused a failure, and the fuzz sweep in SS2.2 was measured under it. The subtle part is
worth naming: I *did* check for a batch Vivado before planning my own postroute gate,
and correctly found none at that moment. I did not re-check before starting a test
sweep, and did not count my own sbt JVMs against the same budget. **The check belongs
before every heavy step, not once per session, and it must include `free -g` and my own
RSS — not just "is Vivado running right now".**

The postroute gate still waits for the Vivado slot: there is a genuine `full_impl`
holding `/var/tmp/m68k-ooo-vivado.lock` via `flock`. That was always the right reason
to wait; only the reasoning I was given for it was wrong.

## 7. Explicitly NOT claimed

* **The IPC side is inherited, not re-measured.** This session measured correctness
  and postroute timing. The two-tier document's IPC numbers (+10.23 % `deep-backlog`,
  +1.24 % aggregate, the two ~3.5 % kernel regressions) were not re-run, so the
  IPC-versus-clock trade in SS4c rests on their numbers and my clock numbers, taken on
  different days.
* **The attribution in SS4c is two arms, not three.** Part 127 and the `ArchLockStep`
  branch were not separated from each other; their -0.073 ns is a joint figure obtained
  by subtraction. It is *probably* all Part 127 (the other branch's only `src/main`
  delta is `simPublic` name-preservation), but that is an argument, not a measurement.
* Not claimed that a single Vivado run per arm is a distribution. Each arm was gated
  **once**. Place-and-route is seeded, and this design has a recorded history of
  arm-to-arm differences that did not survive re-measurement — the -0.133 ns total is
  well outside anything that has been seen as run-to-run variance here, but the
  per-arm split (-0.060 / -0.073) is closer in and would be firmer with a repeat.
* The merged tree is green on the whole simulation bar (SS2.4), but "green" is not
  "clean": the AXI-protocol flake of SS2.3 fired once and remains **unexplained**, and
  a single non-reproduction is weak evidence — it was seen once in roughly six
  full-suite runs, so a rate of a few percent is entirely consistent with the data.
* Not claimed that the AXI-protocol flake is benign, only that the evidence does not
  attribute it to the merge.
* Not claimed that either RAS gap costs measurable IPC. Neither was A/B'd; nor was the
  `683355d` checkpoint change that introduced the proxy.
* Not claimed that the CMP2 fail-safe is architecturally correct behaviour. It is a
  deliberate, documented refusal to execute a legal instruction, and it remains task
  #257.
* No RTL behaviour was changed anywhere in this session. The only `src/main` edit is a
  comment. No bitstream, no SD card, no JTAG lease.

## 8. What is being proposed for `fmax-closure-fanout`

`consolidate/night-merge`, containing:

* the three merges (three merge commits, one hand-resolved parameter-list conflict),
* `395ffba` — the two test dispositions, the new RAS bug document, and the CHK
  cross-references.

**One item of external evidence, obtained by a sibling agent, bears directly on
whether to land this.** Part 127's store-queue fixes are now independently confirmed to
resolve the `S_DRAIN` stall: a new `StoreQueueSpec` reproduction asserting `io.empty`
directly **fails on `afbabdd` and passes on `bc943d8`**, with a drain-ack responder
forked so a slow drain cannot masquerade as a wedge. That is a real *permanent hang*
(JTAG-halt-provokable, and with `CACR.DE = 0` every store is precise, so the exposed
path is the one the board actually runs) removed by one of the branches being merged.
It strengthens the case for landing. **It does not fix the boot, and nothing here claims
it does** — Part 127 SS0 is explicit that the wedge is not fixed.

The whole simulation bar is green on it (SS2.4). **Merging is a coordinator decision
and the only thing still outstanding is the postroute gate**, which waits for the
Vivado slot. Correctness outranks FMax by standing instruction, so the postroute number
will be reported as it comes out and the design will not be contorted for it. What that
gate should look at first, per the two-tier design document: the `!allocHalt` term on
the rename ready chain (a recorded co-critical arc family), then `earlyHit`'s 32-bit PC
compare on the retire cone — the latter is defence-in-depth and can be dropped if it
costs.
