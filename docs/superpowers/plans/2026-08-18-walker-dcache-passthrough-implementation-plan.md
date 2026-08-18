# Walker → `DcacheService` Passthrough — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Route **both** halves of ITLB/DTLB table-walk memory traffic — the `TableWalker`'s three dependent descriptor **reads** and the U/M-bit descriptor **writeback** — off their private `Axi4` masters and onto `DcachePlugin`'s existing `DcacheService` client interface, closing the confirmed D-cache/walker coherency bug in both directions, by implementing every **DECIDED** item (W1-W30) of `docs/superpowers/specs/2026-08-18-walker-dcache-passthrough-design.md`.

**Architecture:** No new `Component` and no new `FiberPlugin`. `LsEuPlugin`'s existing 2-source exception override mux (`LsEuPlugin.scala:2020-2078`) is extended to **4 sources** (ordinary LS pipe, exception sequencer, ITLB walker, DTLB walker) with two registered owner codes, per-walker aging counters, a 1-bit round-robin, and a **depth-4 ownership-tag FIFO** that identifies each load response positionally. `DcachePlugin.scala` receives **zero** edits; `DcacheService` gains no field; no DUT is rewired on the response side. The walkers expose plugin-level `var` client hooks in the shape of the existing `umCommitValid`/`umFlush` idiom, wired by the top level / each DUT exactly where `walkerAxi` is wired today.

**Tech Stack:** SpinalHDL 1.14.1 (`spinalhdl-core`, `spinalhdl-lib`, `spinalhdl-sim`, `spinalhdl-idsl-plugin`), Scala 2.13.16, ScalaTest 3.2.19 (`AnyFunSuite`), SpinalSim (default backend — new specs must stay **untagged** so `make SBT=~/sbt/bin/sbt test-fast` sees them; `fastTest` excludes `m68k040.VerilatorTest`/`SlowTest`/`BoardTest`), Python 3 standard library only (`tools/socket/check_socket_netlist.py`), Vivado for the post-route gates.

**Spec:** `docs/superpowers/specs/2026-08-18-walker-dcache-passthrough-design.md` (commit `849f78a`, 30 DECIDED items W1-W30, 4 recorded non-decisions N1-N4, 7 review rounds, signed off round 7). Every task below cites the W-numbers it implements. **The spec travels with this plan; executors read both.**

## Global Constraints

- Branch is `fmax-closure-fanout`; the plan's parent commit is whatever `git rev-parse HEAD` reports at execution start (must be `849f78a` or a descendant). **Never `git checkout <sha>` in the shared tree** — use `git worktree add` for any isolated before/after verification (project standing rule, task #199 collision).
- **`DcachePlugin.scala` receives ZERO edits.** Spec W4, verbatim: *"`DcachePlugin.scala` is not modified at all by this design. That is the single largest risk reduction available and it is why W4 is worth stating as a decision rather than an implementation detail."* Any task that finds itself wanting to edit that file has mis-read the spec — the FIFO-full term, the cacheMode stamp and every owner qualification live on `LsEuPlugin`'s side of the boundary (spec §4.2.3, "Where the FIFO-full term lives"). `src/main/scala/m68k040/ls/StoreQueue.scala` and `src/main/scala/m68k040/cache/IcachePlugin.scala` are likewise untouched.
- **No `Global.scala` key may be added.** `src/main/scala/m68k040/Global.scala` holds exactly 13 `Database.blocking[Int]()` keys and must be byte-identical at the end of this plan. `WALKER_AGE_LIMIT` and the W26 wedge bound are **constructor parameters** of `LsEuPlugin`, following `DebugCtrlPlugin`'s and `AxiDMergePlugin`'s precedent.
- **`AxiIds.scala` renumbering is FORBIDDEN.** Bullet #11 of the socket plan's own Global Constraints (`docs/superpowers/plans/2026-08-18-axi-socket-adapter-implementation-plan.md:23`), quoting socket spec §4.3: *"The one thing the implementation must not do is renumber `WALK_READ`/`WALK_WRITE` to 'fix' the collision."* Spec W20: `AxiIds.WALK_READ`/`WALK_WRITE` become unreferenced and are **kept defined**, with a comment recording why. No value in that file changes.
- **`M68kFullCoreSynth`'s port surface must not move for any task that does not explicitly intend to change it.** `tools/socket/check_socket_netlist.py` enforces this against `tools/socket/fullcore_ports.golden` (205 ports today). **Exactly one task in this plan intends to change it — Task 15** — which removes the 78 `itlbAxi_*`/`dtlbAxi_*` ports and re-baselines the golden with a recorded justification. Every other RTL task must leave the checker green against the *unchanged* golden. Walkers-through-`DcacheService` is an internal wiring change everywhere else.
- **The standing no-SoC-address-map rule.** `~/.claude/projects/-home-qwertyoruiop-m68k-core-040-ooo/memory/feedback-no-soc-address-map-assumptions.md`: the CPU may only reason from MMU/page-table attributes and real contemporaneous bus responses, never from a cached or assumed SoC decode map ("never ever ever"). This plan's table-walk cacheMode is a **FIXED architectural policy constant** — `CACR.DE ? WRITETHROUGH : INHIBITED` (W1) — **never derived from an address range**. Spec §4.1.1 states why this does not violate the rule: *"the descriptor reads target the page table's own physical pages, and the walker IS the attribute-resolution mechanism… there is no page attribute in existence to consult, for anyone, ever."* No file under `src/main/scala` may gain an address comparison, range table, or "this address was proven backed" bit as a result of this plan.
- **Synth-gate-every-slice** (`~/.claude/projects/-home-qwertyoruiop-m68k-core-040-ooo/memory/synth-gate-every-slice.md`, standing rule). Spec §5 makes it **three** named checkpoints, not one: **baseline before the first RTL commit** (Task 1), **post-mux** (Task 11), **post-MMU/final** (Task 19). Every intermediate RTL task additionally runs `python3 tools/socket/check_socket_netlist.py` plus `make SBT=~/sbt/bin/sbt test-fast` and must state in its report whether it suspects a timing-path change.
- **The FMax reference is re-read at execution time, never hardcoded.** Task 1 measures it. The most recent recorded uncontended post-route number on this branch is **197.278 MHz** (netlist at `e267df3`, slice commit `5aae2c5`, task #219, recorded in `.superpowers/sdd/progress-fmax-closure-fanout-2026-08-17.md`) — i.e. `FAILED_AT_200` by 0.069 ns against the ≥200 MHz goal, **so there is no headroom to spend**. This branch has moved since the spec was scoped; **Task 1's own measurement wins over any number quoted anywhere in this document or the spec.**
- **Never run two Vivado sessions concurrently**, and never launch a gate while a JTAG session is live. Check `pgrep -a vivado` and `pgrep -af 'jtag|xsdb|hw_server'` first. FMax on this machine is unreliable under contention (repeatedly confirmed: 214.3 vs 163.9 MHz for an identical commit).
- **Machine budget:** 29 GB RAM total, at most 2 concurrent heavy JVMs, **none at all while Vivado runs**. Run `free -g` before starting any `sbt` task (`~/.claude/projects/-home-qwertyoruiop-m68k-core-040-ooo/memory/machine-resource-budget.md`).
- **W27's table is the unit of correctness, not any individual finding.** Spec §4.2.7, verbatim: *"No further per-finding patching of this class: the table is the unit of correctness."* and *"A plan-writer must re-derive the table against the then-current file (the line numbers are locators, the sites are the decision) and must not add a `dcache.*` reference to `LsEuPlugin` without adding a row."* Tasks 4, 5, 6 and 7 between them implement all 34 rows; Task 7 Step 8 re-derives the sweep against the then-current file and fails the task if a `dcache.*` reference exists with no row.
- **Citation drift is expected and must be re-checked, not trusted.** Every `LsEuPlugin.scala` / `DcachePlugin.scala` / `ExceptionUnit.scala` line number below was re-resolved against `849f78a` while writing this plan and **all of the spec's locators verified exact** — with one exception recorded here: the spec cites the `S_DRAIN` deadlock comment as `ExceptionUnit.scala:1362-1381`; at `849f78a` it starts at **`:1361`**. An implementer who finds a number moved again must re-locate by the **quoted code**, never by the number.
- **Verification obligations are enumerated in spec §10** (§10.1 acceptance, §10.2 directed tests, §10.3 regression sweeps, §10.4 synth gate) and each maps to a task in the Self-Review coverage table at the end of this plan. A task may not be marked done with one of its §10 obligations unimplemented.

## Plan-level risk: the concurrent standalone fix for spec §11.1 item 9

Spec §11.1 item 9 records a **live, currently-shipping, silent-corruption bug at HEAD**, independent of this design: an exception-sequencer load can consume an ordinary LS-pipe load's response as its own (`ExceptionUnit.scala`/`LsEuPlugin.scala`). The spec's round-5 pass reclassified it as *"tracked and fixed as its own standalone task… completely independent of whether this design is ever built"*.

**Status at the time this plan was written (verified, not assumed):** `git log --all --since=2026-08-16 -- src/main/scala/m68k040/exception/ExceptionUnit.scala src/main/scala/m68k040/execute/LsEuPlugin.scala` returns **no such commit** on any branch. The most recent commits to either file are `ee91d51` / `c8b0037`, both unrelated (FPU FSAVE frame size; `S_REDIR` A7 re-bank). **The standalone fix has NOT landed.**

**Why this is a real plan-level risk and not a footnote.** The standalone fix and this plan's **Task 4** edit *the same region* — `LsEuPlugin.scala:2027-2115`'s exception override block and `excLoadOutstanding`'s neighbourhood — and the standalone fix may additionally touch `ExceptionUnit.scala`, which this plan's **Task 10** edits. A rebase conflict in one or both is likely, in either landing order.

**The plan is resilient to both orderings, and the rule is the same in both:**

- **If it lands BEFORE Task 4** — Task 4 Step 0 re-derives spec §4.2.3's W30 trace against the then-current RTL and **builds W30 on top of** whatever the standalone fix implemented, rather than duplicating it. If the standalone fix already qualifies `excLoadOutstanding`'s clear or already restricts exception-load admission, Task 4 keeps the stronger of the two predicates and records the merge in its report.
- **If it lands AFTER Task 4** — the standalone fix's author will find W30 part (1) (the tagged clear) and W30 part (2) (`ldOwnerFifoEmpty` in `excLoadAdmit`) already present and already closing the trace. Task 4's report must therefore say so explicitly, so the standalone task can be closed as already-satisfied rather than re-implemented.
- **In NEITHER ordering may W30 part (2) be weakened.** Spec §11.1 item 9, verbatim: *"A plan-writer who finds this already fixed upstream must re-verify the trace rather than assume it, and must **not** weaken W30 part (2) on that basis: W30 part (2) is also what restores W14's argument for the walker-vs-`CORE-EXC` case, which no upstream fix would cover."* Weakening `excLoadAdmit` back toward `!ldOwnerFifoHoldsWalker` simultaneously reopens the round-3 C2 finding **and** re-inherits the live HEAD bug; §4.3's first assertion is a structural tripwire that fires the instant anyone does it.

---

## File Structure

**`src/main/scala` — modified (9 files). No new `src/main` file is created by this plan.**

| File | Responsibility after this plan | Tasks |
|---|---|---|
| `cache/DcacheTypes.scala` | **Declaration-only.** Adds `DLoadToken.WALK_ITLB = 0x81` / `WALK_DTLB = 0x82` beside the existing `Width`, and rewrites the `:6-8` token-layout doc comment so it describes bit 7 as "non-LS source" with all three reserved values enumerated. **No bundle field is added or changed** — W4's "zero new client awareness" and W7's "no `DcacheService` bundle change" both stand. | 3 |
| `execute/LsEuPlugin.scala` | Owns the whole arbitration layer: the 4-source command mux, `ldOwner`/`stOwner`, aging/`force`/round-robin, the depth-4 ownership FIFO and `ldRspTag`, `excLoadOutstanding`/`coreStOutstanding`, every admission predicate, all 34 W27 rows, the walker client pass-through hooks, the W26 wedge counters, and the `GenerationFlags.simulation` assertion block. **Virtually all new logic in this plan lives here.** | 4,5,6,7,8,10 |
| `exception/ExceptionUnit.scala` | Exports `quiesceHold` over `S_DRAIN \|\| S_APPLY` (W19); rewrites the now-false clause of the `:1361-1381` deadlock comment. | 10 |
| `mmu/TableWalker.scala` | Consumes `DcacheService` client hooks instead of `Axi4ReadOnly`; `io.axi`, `axiCfg` and `selectWord` deleted; `loadRsp.fault` terminates the walk (D19 re-establishment). | 14 |
| `mmu/ItlbPlugin.scala` | `walkerAxi`, its D27 `b.ready` guard and the AW/W/B drain FSM deleted; twelve client `var` hooks added; `drainArmed` replaces `drainAwDone && drainWDone`; `socketMerged` constructor param dropped. | 2,14 |
| `mmu/DtlbPlugin.scala` | Same as `ItlbPlugin`. | 2,14 |
| `socket/AxiDMerge.scala` | Shrinks to 2 read owners (`DCACHE`, `RESETVEC`) / 1 write owner (pass-through); read-side D20 watchdog kept, write-side deliberately dropped; `io.wedgeIsRead` tied `True`; §4.4 assertion block pruned. | 2 |
| `socket/AxiDMergePlugin.scala` | Drops the `itlb`/`dtlb` connections and their `socketMerged` requirement. | 2 |
| `socket/HaltReason.scala` | Adds `WALKER_PORT_WEDGE = 5` (W26). | 10 |
| `cache/AxiIds.scala` | Comment-only: records `WALK_READ`/`WALK_WRITE` as reserved-but-unused (W20). **No value changes.** | 2 |
| `top/FullCoreSynth.scala` | Wires the twelve walker client hooks per TLB, `quiesceHold`, and the W26 wedge into the D28 halt fold. The `itlbAxi`/`dtlbAxi` top-level ports disappear as a consequence of the plugin-side deletion. | 10,15 |

**`src/test/scala` — created (7 files):**

| File | Responsibility |
|---|---|
| `sim/DcacheClientMemAgent.scala` | W18. A `Stream`/`Flow` analogue of `BehavioralMemAgent` that answers `DLoadCmd`/`DStoreCmd` out of a `SparseMemory`, for the 8 DUT classes that host no `DcachePlugin`. |
| `cache/DLoadTokenSpec.scala` | W25 disjointness of the reserved token space. |
| `execute/WalkerDcachePortArbSpec.scala` | W7/W8/W9/W13/W23/W24/W27-store/W28/W29/W30 — the arbitration layer's directed suite, grown task by task. |
| `execute/WalkerSplitLoadRaceSpec.scala` | W27's split-leg failure (`:756`/`:1508`/`:1536`/`:1574-1575`). |
| `mmu/WalkerProbeDeadlockSpec.scala` | W11 early-probe deadlock + W25 token-collision. |
| `mmu/WalkerCacheModeSpec.scala` | W1/W2/W3 cacheMode policy. |
| `mmu/WalkerDescriptorCoherencySpec.scala` | §10.1 — the read-side (§1.2) and write-side (§1.1) coherency acceptance tests. |

**`src/test/scala` — deleted (1 file):** `socket/WalkerIdGuardSpec.scala` (W20 — its subject `walkerAxi` ceases to exist).

**`src/test/scala` — migrated (24 files, §8.1).** Four buckets, and the plan budgets them as spec §8.1 demands (the **non-`sharedMem`** case is the **dominant** one — only 6 of 24 files pass `sharedMem` at *every* walker attach site — and deleting those attach lines is a real per-site behaviour change, not a mechanical find/replace):

| Bucket | Count | Files | Task |
|---|---:|---|---|
| No `DcachePlugin` — need `DcacheClientMemAgent` | **8 files / 8 DUT classes** | `mmu/DtlbSpec`, `mmu/ItlbSpec`, `mmu/UmWriteSpec`, `mmu/MmuControlSpec`, `mmu/DtlbStreamPipelineSpec`, `cache/IcacheParallelViptSpec`, `frontend/FetchAlignResidentCadenceSpec`, `ls/DtlbMissFlushSpec` (its **first** `Dut`, `DtlbCleanMissSerializationSpec` at `:97`; the file's other DUT does host `DcachePlugin`) | 15 |
| Full-core / lock-step / fuzz DUTs | 12 | `bench/IpcBenchSpec`, `lockstep/ExecuteLockStepSpec`, `exception/FsaveFrestoreSpec`, `exception/ExceptionStoreDrainArbSpec`, `execute/FpuControlWiringSpec`, `fuzz/FuzzLockStepSpec`, `fuzz/PortedTestRunner`, `fuzz/Cmp2HangTraceSpec`, `fuzz/EoriAddaDecodeTraceSpec`, `fuzz/MiHangTraceSpec`, `fuzz/P27HangTraceSpec`, `fuzz/WildPcA7TraceSpec` | 16 |
| LS-cluster DUTs | 5 | `ls/DtlbCrossPageSplitSpec`, `ls/DtlbMissFlushSpec` (second DUT), `ls/DtlbViptChangedVpnSpec`, `ls/LsEuFastPreciseSpec`, `ls/PreciseDrainIrqRaceSpec` | 16 |
| Reworked in place | 1 | `socket/AxiDMergeSpec` | 2 |

**`docs/` — modified (2 files, W22/§9.3):** a superseding **Task 5R** inserted into `docs/superpowers/plans/2026-08-18-axi-socket-adapter-implementation-plan.md` after Task 5, plus its Task 13's two `itlbAxi_aw_payload_len` citations re-pointed (`:4717`, `:4855`); and an **addendum block** in `docs/superpowers/specs/2026-08-18-axi-socket-adapter-design.md` covering D7/D8/D9/D19/D20/D27. Task 18.

**`tools/socket/fullcore_ports.golden`** — re-baselined once, in Task 15, with the 78 `itlbAxi_*`/`dtlbAxi_*` lines removed.

**Progress ledger:** `.superpowers/sdd/progress-walker-dcache-passthrough-2026-08-18.md`, created by Task 1 and appended by every task.

---

## Naming contract (fixed once here; every task uses these exact names)

These are the names later tasks depend on. A task that renames one of them breaks its neighbours.

```scala
// ── in LsEuPlugin.scala, file scope, above `class LsEuPlugin` ──────────────────
/** W29: the FOUR-valued load-response identity tag pushed into the ownership FIFO.
  * CORE_LS and CORE_EXC are ONE arbiter owner (W6) but TWO response identities (W30). */
object LdRspTag { val CORE_LS = 0; val CORE_EXC = 1; val ITLB = 2; val DTLB = 3 }

/** W6: the THREE-valued arbiter owner code carried by `ldOwner`/`stOwner`.
  * DISTINCT from LdRspTag above — the numbers do not line up and must never be
  * used interchangeably (spec §4.2.3's round-5 M-R5-4 correction). */
object DcPortOwner { val CORE = 0; val ITLB = 1; val DTLB = 2 }

/** POSITIONAL indices into the per-walker vectors (`walkerLoadAdmit`, `walkLoadCmd`,
  * `ldAge`, …). Two walkers, so 0/1. DISTINCT from both objects above. */
object WalkerIdx { val ITLB = 0; val DTLB = 1; val COUNT = 2 }
```

| Name | Type | Meaning | Introduced |
|---|---|---|---|
| `ldOwner` / `stOwner` | `Reg(UInt(2 bits))` | **Current grant** (W28 clause (c)). Never "who is in flight". | T7 (declared in T4/T6 as forward declarations) |
| `walkerOwnsLoad` | `Bool` | `ldOwner =/= DcPortOwner.CORE` | T4 (forward-declared `False`), real in T7 |
| `dcLoadHeldByOther` | `Bool` | `excActive \|\| walkerOwnsLoad` | T5 |
| `coreLsLoadReq` | `Bool` | `Mux(useSplitCmd, llReg.valid, alignedSendValidRaw)` — **both** legs visible to the arbiter | T5 |
| `coreLsLoadAdmit` | `Bool` | `coreLsLoadReq && !dcLoadHeldByOther` | T5 |
| `excLoadOutstanding` | `RegInit(False)` | set by W23's predicate, cleared by W30's tagged term | T4 |
| `ldBusyExc` | `Bool` | `excLoadOutstanding \|\| (excActive && excLoadCmdValid)` | T4 |
| `excLoadAdmit` | `Bool` | `excActive && excLoadCmdValid && ldOwnerFifoEmpty` (**W30 part 2**) | T4 |
| `ldOwnerFifoEmpty` / `…Full` / `…Occupancy` / `…HoldsWalker` | `Bool`/`UInt` | **PRE-push, PRE-pop** views of the FIFO's *registered* state only | T4 |
| `ldRspTag` | `UInt(2 bits)` | `ldFifoTags(ldFifoPop)` — the **pre-pop** head; tags *this* cycle's response | T4 |
| `ldPushTag` | `UInt(2 bits)` | W29's one-hot encode of the four admission predicates | T4 (2 legs), T7 (4 legs) |
| `coreStOutstanding` | `Reg(UInt(3 bits))` | CORE store fires (SQ **and** exception sequencer) minus CORE acks | T6 |
| `excStoreAdmit` | `Bool` | `excActive && excStoreValid && (stOwner === DcPortOwner.CORE)` | T6 |
| `coreLsStoreAdmit` | `Bool` | `sq.io.drain.valid && (stOwner === DcPortOwner.CORE) && !excStoreAdmit` | T6 |
| `walkerLoadAdmit(i)` / `walkerStoreAdmit(i)` | `Vec(Bool(), 2)` | see T7 for the exact terms | T7 |
| `walkLoadCmd(i)` / `walkLoadRsp(i)` / `walkStore(i)` / `walkStoreAck(i)` | plugin-level `var` hooks | W15's four hooks per walker | T7 |
| `quiesceHold` | plugin-level `var Bool` | W19; default-idle `False` | T7 (hook), T10 (driver) |
| `walkerPortWedge` | `Bool` | W26's sticky wedge report | T11 |

---

## Task 1: Baselines — synth checkpoint #1, `test-fast`, ported corpus, fuzz

Spec §5 ("Full-core OOC synth **before** the first RTL commit of the implementation, on an uncontended machine… This is the baseline, recorded in the plan"), §10.3 ("the plan must capture a **fresh pre-change baseline** and diff against it. Diffing against the recorded 713/728 would attribute pre-existing drift to this work"), §10.4.

**Files:**
- Create: `.superpowers/sdd/progress-walker-dcache-passthrough-2026-08-18.md`

**Interfaces:**
- Consumes: nothing (first task).
- Produces, relied on by Tasks 11, 15, 16, 17 and 19:
  - A `## Baseline (Task 1)` section in the progress ledger carrying, as literal numbers: post-route **WNS (ns)** and **achieved FMax (MHz)**, **CLB LUTs**, **CLB Registers**, the **top-10 failing timing paths' start/end point names**, the `test-fast` **pass/total** count, the ported-corpus **pass/total**, and the fuzz sweep's **divergence count**.

- [ ] **Step 1: Confirm the machine is uncontended and record it**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
pgrep -a vivado; pgrep -af 'jtag|xsdb|hw_server'; free -g
```
Expected: **no** vivado / JTAG process, and ≥ 20 GB free. If either check fails, STOP and wait — spec §5 and `ported-tests-triage-2026-07-17.md` both record that FMax measured under contention is meaningless (214.3 vs 163.9 MHz for one commit).

- [ ] **Step 2: Create the ledger and record the parent commit**

Create `.superpowers/sdd/progress-walker-dcache-passthrough-2026-08-18.md`:

```markdown
# Walker → DcacheService passthrough — progress ledger

Plan: `docs/superpowers/plans/2026-08-18-walker-dcache-passthrough-implementation-plan.md`
Spec: `docs/superpowers/specs/2026-08-18-walker-dcache-passthrough-design.md` (`849f78a`)
Branch: `fmax-closure-fanout`

## Baseline (Task 1)

Parent commit: <output of `git rev-parse HEAD`>
Machine state at measurement: `pgrep -a vivado` empty, `pgrep -af jtag` empty, free -g = <N>

### Synth checkpoint #1 (pre-RTL)
| Metric | Value |
|---|---|
| Post-route WNS (ns) | <fill> |
| Achieved FMax (MHz) | <fill> |
| CLB LUTs | <fill> |
| CLB Registers | <fill> |

Top-10 failing paths (start → end), verbatim from the timing report:
1. <fill>
...

### Functional baselines
| Sweep | Result |
|---|---|
| `make test-fast` | <pass>/<total> |
| Ported corpus | <pass>/<total> |
| Fuzz sweep | <divergences>/<runs> |
| `mmu_atc_write_hit_sets_modified` | <PASS or FAIL — expected FAIL, this is the bug> |
```

- [ ] **Step 3: Run the pre-change OOC synth in an isolated worktree**

`git worktree add` is mandatory — never `git checkout` in the shared tree.

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
git worktree add /home/qwertyoruiop/tmp/claude-1000/-home-qwertyoruiop-m68k-core-040-ooo/374c5f2c-f0cd-4208-8a9e-23690599d409/scratchpad/wt-walker-base HEAD
cd /home/qwertyoruiop/tmp/claude-1000/-home-qwertyoruiop-m68k-core-040-ooo/374c5f2c-f0cd-4208-8a9e-23690599d409/scratchpad/wt-walker-base
make SBT=~/sbt/bin/sbt verilog
ls synth/            # locate this branch's existing OOC flow script and reuse it verbatim
```
Run the same OOC flow the task #218/#219 gates used (see `synth/` and `.superpowers/sdd/progress-fmax-closure-fanout-2026-08-17.md` for the exact invocation and `POSTROUTE_ROUNDS` setting — **use the recorded round count for THIS netlist; do not inherit a plateau from a different netlist**, per `ufa-vtl-campaign-concluded-2026-08-13.md`). Expected: a post-route timing summary and utilisation report.

- [ ] **Step 4: Record the top-10 failing paths by SHAPE, not just by number**

Spec §5: *"A path list that is shape-identical to the baseline is the pass criterion, not merely a number that happens to hold."* Extract the start and end point of each of the top 10 failing paths and paste them verbatim into the ledger. In particular record whether any of them already traverses `rdSet`, `rdEn`, `loadCmdPort_payload_vaddr`, `loadCmdPort_ready`, `storePort_ready`, `loadProbePort_valid`, or the LS EU's aligned-queue pointers — Tasks 12 and 20 compare against exactly this list.

- [ ] **Step 5: Capture the functional baselines (Vivado now idle)**

```bash
cd /home/qwertyoruiop/tmp/.../wt-walker-base
free -g                      # confirm the synth JVM/Vivado are gone
make SBT=~/sbt/bin/sbt test-fast
```
Then run the ported-corpus sweep and the fuzz sweep in that same worktree using this repo's existing runners (`src/test/scala/m68k040/fuzz/PortedTestRunner.scala`, `src/test/scala/m68k040/fuzz/FuzzLockStepSpec.scala`). Record pass/total and divergence counts.

- [ ] **Step 6: Confirm the acceptance bug actually fails at baseline**

Run `mmu_atc_write_hit_sets_modified` from the ported corpus and record the result. Spec §10.1 makes this the acceptance test for §1.1; **it is expected to FAIL here**. If it unexpectedly PASSES at baseline, record that fact prominently — it changes what Task 18 can claim, and spec §7 item 6 already names the residual (`mmu-atc-m-bit-tracking`'s own `c629bec` M-bit logic fix) as the alternative explanation.

- [ ] **Step 7: Commit the ledger**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
git add .superpowers/sdd/progress-walker-dcache-passthrough-2026-08-18.md
git commit -m "docs(walker): Task 1 baselines -- synth checkpoint #1, test-fast, ported, fuzz

Pre-RTL uncontended baseline for the walker/DcacheService passthrough plan.
Spec section 5 and section 10.3 both require a fresh baseline rather than an
inherited number.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Task 2: `AxiDMerge` shrink and the socket Task 4/5 code rework (W20, W21)

**Why this is FIRST and not last.** `AxiDMergePlugin.scala:47-48` reads `it.walkerAxi` / `dt.walkerAxi`. The moment Task 15 deletes those fields, that file stops compiling. Doing the shrink now removes the references, so the walker deletion later needs no bridging step at all — and this task is otherwise fully self-contained (`AxiDMergePlugin` is instantiated in **no** plugin list today; `FullCoreSynth.scala:368` and `ResetVectorPlugin.scala:38` reach for it via `host.get`/`host`, and `AxiDMergeSpec` tests the `AxiDMerge` *component* directly). Nothing later in this plan depends on it, so it could equally have gone last; first is strictly cheaper.

Implements **W20** (Task 4's `RESET_VEC` half survives untouched; the walker-guard half dies with its host in Task 15; `AxiIds` comment) and **W21** (Task 5's arbiter shrinks: read 4→2 owners, write 3→1 pass-through; read-side D20 watchdog kept, **write-side deliberately dropped as a stated deviation**; `io.wedgeIsRead` kept tied `True`; §4.4 assertion block pruned).

**Files:**
- Modify: `src/main/scala/m68k040/socket/AxiDMerge.scala` (`io.itlb`/`io.dtlb` at `:103-104`; `Owner` object at `:8-30`; read side `:130-231`; write side `:233-307`; assertion block `:313-337`)
- Modify: `src/main/scala/m68k040/socket/AxiDMergePlugin.scala:36-49` (the `require` and the two `<>` connections)
- Modify: `src/main/scala/m68k040/mmu/ItlbPlugin.scala:33,71` and `src/main/scala/m68k040/mmu/DtlbPlugin.scala:30,75` (drop the now-unreachable `socketMerged` constructor parameter)
- Modify: `src/main/scala/m68k040/cache/AxiIds.scala:65-69` (comment only)
- Test: `src/test/scala/m68k040/socket/AxiDMergeSpec.scala`

**Interfaces:**
- Consumes: Task 1's ledger only.
- Produces, relied on by Tasks 14 and 18:
  - `AxiDMerge`'s `io` no longer has `itlb` / `dtlb` fields.
  - `AxiDMerge.Owner` has exactly `DCACHE` and `RESETVEC` on the read side; the write side has no owner enum at all.
  - `io.wedge: Bool` and `io.wedgeIsRead: Bool` **keep their names, widths and directions**; `wedgeIsRead` is now a constant `True`.
  - `ItlbPlugin` / `DtlbPlugin` constructors are `(entries, ways, banks)` — **no** `socketMerged`.

- [ ] **Step 1: Write the failing test**

Rewrite `src/test/scala/m68k040/socket/AxiDMergeSpec.scala`'s DUT-facing surface and add these three tests (keep every existing `DCACHE`-vs-`RESETVEC` read-arbitration test — they are the ones that must still pass):

```scala
  test("W21: the merge has no itlb/dtlb slave ports at all") {
    // A compile-time property expressed as a runtime reflection check, because the
    // strongest statement is "the field is gone", not "it is tied off". Spec section 9.2:
    // "two permanently-dead owner slots are unreachable silicon ... which is worse than
    // absent -- a future reader would have to re-derive why they never fire."
    val names = classOf[AxiDMerge].getMethods.map(_.getName).toSet
    assert(!names.contains("io"), "sanity: io is a val, reached below via an elaboration")
    SimConfig.compile(new MergeDut(64)).doSim("no-walker-ports", seed = 1) { dut =>
      val fields = dut.m.io.getClass.getDeclaredFields.map(_.getName).toSet
      assert(!fields.contains("itlb"), "AxiDMerge still declares io.itlb")
      assert(!fields.contains("dtlb"), "AxiDMerge still declares io.dtlb")
    }
  }

  test("W21: the write side is a pass-through -- dc AW/W/B reach `out` with no grant state") {
    SimConfig.compile(new MergeDut(8)).doSim("write-passthrough", seed = 2) { dut =>
      dut.clockDomain.forkStimulus(10)
      // Present a dc write and hold `out` ready; it must complete in the minimum number
      // of cycles a pass-through takes, i.e. WITHOUT waiting for any arbitration grant.
      dut.m.io.out.aw.ready #= true
      dut.m.io.out.w.ready  #= true
      dut.m.io.dc.aw.valid  #= true
      dut.m.io.dc.w.valid   #= true
      dut.m.io.dc.w.payload.last #= true
      dut.clockDomain.waitSampling()
      assert(dut.m.io.out.aw.valid.toBoolean, "AW did not pass straight through")
      assert(dut.m.io.out.w.valid.toBoolean,  "W did not pass straight through")
      assert(dut.m.io.dc.aw.ready.toBoolean,  "dc.aw.ready not forwarded from out.aw.ready")
    }
  }

  test("W21: io.wedgeIsRead is a constant True and io.wedge still fires on a read wedge") {
    SimConfig.compile(new MergeDut(8)).doSim("wedge", seed = 3) { dut =>
      dut.clockDomain.forkStimulus(10)
      // Grant the read side to the D-cache and then make NO progress for the whole window.
      dut.m.io.dc.ar.valid #= true
      dut.m.io.out.ar.ready #= false
      dut.m.io.out.r.valid  #= false
      var fired = false
      for (_ <- 0 until 40) {
        dut.clockDomain.waitSampling()
        if (dut.m.io.wedge.toBoolean) fired = true
        assert(dut.m.io.wedgeIsRead.toBoolean,
          "wedgeIsRead must be constant True once the write side has no grant state")
      }
      assert(fired, "the read-side D20 watchdog did not fire")
    }
  }
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
free -g && make SBT=~/sbt/bin/sbt test-fast
```
Expected: `AxiDMergeSpec` FAILS — "AxiDMerge still declares io.itlb", and the write-pass-through test fails or hangs waiting for a grant.

- [ ] **Step 3: Shrink `AxiDMerge`**

In `src/main/scala/m68k040/socket/AxiDMerge.scala`:

1. **`Owner`** — reduce the read-side enum to `DCACHE`/`RESETVEC` only and delete the write-side owner concept. Update the class doc comment's ASCII diagram (`:42-43`) so it no longer shows `itlbAxi`/`dtlbAxi` feeding the merge, and rewrite `:52-54`'s "makes the ITLB/DTLB both emit AR=2/AW=3 collision inert" sentence — that collision is now inert because **the walkers no longer emit AXI at all** (this plan), not because the arbiter latches an owner.
2. **`io`** — delete `val itlb = slave(Axi4(axiCfg))` and `val dtlb = slave(Axi4(axiCfg))` (`:103-104`). Keep `rvArValid`/`rvArAddr`/`rvArReady`/`rvRValid`/`rvRData`/`rvRResp`/`rvRReady`, `wedge`, `wedgeIsRead`, `out` **unchanged in name, width and direction**.
3. **Read side (`:130-231`)** — two owners. `pick`/`rr`/`busy`/`owner` stay (a real 2-owner merge); `io.itlb.r.valid`/`io.dtlb.r.valid` drives and their `r.ready` mux arms (`:211-212`, `:217-218`) are deleted. The D20 watchdog at `:225-230` (`progress = out.ar.fire || out.r.fire`) is **kept verbatim**.
4. **Write side (`:233-307`)** — becomes a pass-through:

```scala
  // ── Write side: ONE owner (the D-cache). A PASS-THROUGH, not an arbiter. ───────────
  //
  // W21 (walker/DcacheService passthrough design, section 9.2). The ITLB and DTLB walkers
  // no longer emit AXI at all -- their U/M descriptor writeback is an ordinary
  // `DcacheService.store`, arbitrated inside LsEuPlugin -- so this direction has exactly
  // one owner left and no grant state to arbitrate.
  //
  // DELIBERATE DEVIATION FROM D20 AS WRITTEN, stated rather than quietly half-satisfied:
  // socket design spec section 8.3 requires "each direction of AxiDMergePlugin carries a
  // bounded-grant watchdog", and this direction no longer has one. D20's own stated scope
  // is the wedge mode the MERGE creates -- "three owners now share one port, so an owner
  // that never completes starves the other two". With one write owner that mode is
  // STRUCTURALLY ABSENT; a single master's transaction terminating is D19's obligation,
  // not D20's. The read side's watchdog is unchanged.
  val wr = new Area {
    io.out.aw << io.dc.aw
    io.out.w  << io.dc.w
    io.dc.b   << io.out.b
  }
```

5. **`:306-307`** — `io.wedge := rd.wedge` and:

```scala
  // W21: a dead port, KEPT tied True with this comment rather than removed. D28's
  // halt-reason encoding is shared with D15 and a shape change there is gratuitous scope
  // (spec section 9.2, named consequence 1: "Recommendation: keep it, tied True").
  // Every wedge this component can now report IS a read-side wedge.
  io.wedgeIsRead := True
```

6. **Assertion block (`:313-337`)** — **prune it, do not leave it vacuous.** Spec §9.2, named consequence 2: *"Leaving vacuous assertions in place is worse than removing them: they read as coverage that does not exist."* Delete the `bHot <= 1` assertion (one `b.valid` source now), the `!(wr.grant && wr.busy)` assertion (no grant state), and the write-grant-cleared-on-`b.fire` assertion (no subject). Shrink `rHot <= 1` from 4 terms to 2 and keep it.

- [ ] **Step 4: Drop the walker connections from `AxiDMergePlugin`**

In `src/main/scala/m68k040/socket/AxiDMergePlugin.scala`, delete the `ItlbPlugin`/`DtlbPlugin` imports, the `val it`/`val dt` lookups, the two `merge.io.itlb <> …` / `merge.io.dtlb <> …` lines and their 8-line comment, and reduce the `require` to:

```scala
    require(dc.socketMerged,
      "AxiDMergePlugin requires DcachePlugin to be constructed with socketMerged = true, " +
      "otherwise its AXI bundle is a top-level master port the arbiter cannot drive the " +
      "response side of")
```
Also update the class doc comment: this plugin now hosts a **two-owner read merge and a write pass-through**, and the ordering requirement is only "after `DcachePlugin`".

- [ ] **Step 5: Drop `socketMerged` from the two MMU plugins**

`ItlbPlugin.scala:33` / `DtlbPlugin.scala:30`: delete the `val socketMerged: Boolean = false` parameter. `ItlbPlugin.scala:71` / `DtlbPlugin.scala:75` become:

```scala
    walkerAxi = master(Axi4(axiCfg)).setName("itlbAxi")   // (and "dtlbAxi")
```
This is intentionally a *no-op on the port surface* — `socketMerged` is `false` at every construction site in the tree, so `M68kFullCoreSynth` is byte-identical. The field is deleted only because Task 15 deletes `walkerAxi` and a dangling constructor flag would outlive its subject.

- [ ] **Step 6: Record `AxiIds`'s reserved-but-unused status (comment only)**

`src/main/scala/m68k040/cache/AxiIds.scala:65-69` becomes:

```scala
  // -- table walkers (RESERVED BUT UNUSED since the walker/DcacheService passthrough) ---
  /** Table-walker descriptor read. **NO LONGER EMITTED.** The ITLB/DTLB walkers route
    * their descriptor reads through `DcacheService.loadCmd` and their U/M writeback
    * through `DcacheService.store`; every transaction they cause now leaves the core with
    * a D-CACHE id. Kept DEFINED and NOT renumbered: this plan's global constraint (socket
    * plan Global Constraints bullet 11, quoting socket spec section 4.3) forbids
    * renumbering, and removing these would shift nothing but would invite exactly that. */
  val WALK_READ = 2
  /** Table-walker U/M descriptor write-back. **NO LONGER EMITTED** -- see WALK_READ. */
  val WALK_WRITE = 3
```
**No value changes.**

- [ ] **Step 7: Run the tests to verify they pass**

```bash
free -g && make SBT=~/sbt/bin/sbt test-fast
python3 tools/socket/check_socket_netlist.py
```
Expected: `AxiDMergeSpec` PASSES (all three new tests plus every retained `DCACHE`/`RESETVEC` test); `test-fast` matches Task 1's baseline count; the port-surface FREEZE check PASSES (this task changes no port of `M68kFullCoreSynth`).

- [ ] **Step 8: Commit**

```bash
git add src/main/scala/m68k040/socket/AxiDMerge.scala \
        src/main/scala/m68k040/socket/AxiDMergePlugin.scala \
        src/main/scala/m68k040/mmu/ItlbPlugin.scala \
        src/main/scala/m68k040/mmu/DtlbPlugin.scala \
        src/main/scala/m68k040/cache/AxiIds.scala \
        src/test/scala/m68k040/socket/AxiDMergeSpec.scala
git commit -m "walker(W20/W21): shrink AxiDMerge to 2 read owners / write pass-through

Reworks the already-merged socket Task 5 (cf56ed1) ahead of the walkers losing
their AXI masters. Read side 4 owners -> 2 (DCACHE, RESETVEC) with the D20
watchdog kept; write side 3 owners -> 1, a pass-through with NO watchdog -- a
STATED deviation from D20 as written (spec section 9.2), because the wedge mode
D20 exists for is structurally absent at one owner. io.wedgeIsRead kept tied
True with a comment; the section-4.4 assertion block pruned rather than left
vacuous. AxiIds.WALK_READ/WALK_WRITE kept defined and NOT renumbered.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Task 3: W25 — reserved walker load tokens and the corrected token-layout comment

Spec **W25** (§4.5). A don't-care walker token is a **silent-corruption bug**: `DcachePlugin.scala:420-422` matches a resident early-probe entry on **both** token **and** vaddr, and a walker's `vaddr` is a *physical* page-table address which, under the identity-mapped supervisor page tables this project's own MMU tests use, can equal a live LS-EU probe's `vaddr` exactly.

**Files:**
- Modify: `src/main/scala/m68k040/cache/DcacheTypes.scala:6-9`
- Test: `src/test/scala/m68k040/cache/DLoadTokenSpec.scala` (create)

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces, relied on verbatim by Tasks 7 and 13:
  - `m68k040.cache.DLoadToken.WALK_ITLB: Int = 0x81`
  - `m68k040.cache.DLoadToken.WALK_DTLB: Int = 0x82`
  - `DLoadToken.Width: Int = 8` (unchanged)

- [ ] **Step 1: Write the failing test**

Create `src/test/scala/m68k040/cache/DLoadTokenSpec.scala`:

```scala
package m68k040.cache

import org.scalatest.funsuite.AnyFunSuite

/** W25 (walker/DcacheService passthrough design, section 4.5). The walker load tokens are
  * RESERVED VALUES, not don't-cares. DcachePlugin.scala:420-422 matches a resident early
  * probe on token AND vaddr; a walker's vaddr is a physical page-table address, which under
  * identity-mapped supervisor page tables can equal a live probe's vaddr exactly. A
  * colliding token then makes `earlyProbeOwnsCmd` true for the WALKER's command and the
  * walker is answered from a stale probe snapshot, extracted at the PROBE's size and
  * offset. Wrong descriptor, no fault, no assertion.
  *
  * Deliberately UNTAGGED so `make test-fast` runs it. */
class DLoadTokenSpec extends AnyFunSuite {

  /** LS-EU probe tokens are `(False ## False ## robId)` (LsEuPlugin.scala:867) and split
    * command tokens are `(False ## bDone ## robId)` (LsEuPlugin.scala:762-765), so every
    * LS-sourced token has bit 7 CLEAR. robId is 6 bits. */
  private val lsTokens: Seq[Int] = for (half <- 0 to 1; rob <- 0 until 64) yield (half << 6) | rob

  /** The exception sequencer's reserved token (LsEuPlugin.scala:2064). */
  private val EXC = 0x80

  test("the walker tokens are 0x81 and 0x82 and fit the declared width") {
    assert(DLoadToken.WALK_ITLB == 0x81)
    assert(DLoadToken.WALK_DTLB == 0x82)
    assert(DLoadToken.Width == 8)
    for (t <- Seq(DLoadToken.WALK_ITLB, DLoadToken.WALK_DTLB, EXC))
      assert(t >= 0 && t < (1 << DLoadToken.Width), s"token $t does not fit ${DLoadToken.Width} bits")
  }

  test("the four reserved/non-LS tokens are pairwise distinct") {
    val reserved = Seq(EXC, DLoadToken.WALK_ITLB, DLoadToken.WALK_DTLB)
    assert(reserved.distinct.size == reserved.size,
      "two non-LS clients share a token; an ITLB command could alias a DTLB entry")
  }

  test("no walker token collides with ANY reachable LS-EU probe or split-command token") {
    for (t <- Seq(DLoadToken.WALK_ITLB, DLoadToken.WALK_DTLB, EXC))
      assert(!lsTokens.contains(t), s"reserved token 0x${t.toHexString} collides with an LS token")
  }

  test("bit 7 is the non-LS-source discriminator: set for every reserved token, clear for every LS token") {
    for (t <- Seq(EXC, DLoadToken.WALK_ITLB, DLoadToken.WALK_DTLB))
      assert((t & 0x80) != 0, s"reserved token 0x${t.toHexString} has bit 7 clear")
    for (t <- lsTokens) assert((t & 0x80) == 0, s"LS token 0x${t.toHexString} has bit 7 set")
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
free -g && ~/sbt/bin/sbt "testOnly m68k040.cache.DLoadTokenSpec"
```
Expected: FAIL to compile — `value WALK_ITLB is not a member of object m68k040.cache.DLoadToken`.

- [ ] **Step 3: Write the minimal implementation**

Replace `src/main/scala/m68k040/cache/DcacheTypes.scala:6-9` in full:

```scala
object DLoadToken {
  // TOKEN LAYOUT (W25, walker/DcacheService passthrough design section 4.5).
  //
  //   [7] = NON-LS SOURCE. When SET, the whole token is one of the reserved values
  //         enumerated below and bits [6:0] carry NO robId/split meaning at all.
  //   [6] = split half        -- MEANINGFUL ONLY WHEN [7] IS CLEAR
  //   [5:0] = ROB id          -- MEANINGFUL ONLY WHEN [7] IS CLEAR
  //
  // Reserved (bit 7 set), all three pairwise distinct and disjoint from every reachable
  // LS token (LsEuPlugin.scala:867 probes and :762-765 split commands both drive bit 7
  // clear):
  //   0x80  the serializing exception unit (LsEuPlugin.scala:2064)
  //   0x81  the ITLB table walker            <- WALK_ITLB
  //   0x82  the DTLB table walker            <- WALK_DTLB
  //
  // The two walker values are DISTINCT rather than one shared "walker" token so an ITLB
  // command can never alias a DTLB command's early-probe entry either. Walkers never
  // LAUNCH a probe; these values exist purely so their commands cannot MATCH one --
  // DcachePlugin.scala:420-422 matches on token AND vaddr, and a walker's vaddr is a
  // physical page-table address that CAN equal a live probe's vaddr under identity-mapped
  // supervisor page tables.
  //
  // Kept as a plain UInt field in all public bundles.
  val Width = 8
  val WALK_ITLB = 0x81
  val WALK_DTLB = 0x82
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
~/sbt/bin/sbt "testOnly m68k040.cache.DLoadTokenSpec"
make SBT=~/sbt/bin/sbt test-fast
python3 tools/socket/check_socket_netlist.py
```
Expected: `DLoadTokenSpec` 4/4 PASS; `test-fast` matches Task 1's baseline; FREEZE check PASSES (declaration-only change, no bundle field, no port).

- [ ] **Step 5: Commit**

```bash
git add src/main/scala/m68k040/cache/DcacheTypes.scala src/test/scala/m68k040/cache/DLoadTokenSpec.scala
git commit -m "walker(W25): reserved ITLB/DTLB load tokens + corrected token-layout comment

DLoadToken.WALK_ITLB = 0x81 / WALK_DTLB = 0x82, disjoint from the exception
sequencer's 0x80 and from every reachable LS-EU probe/split token. A don't-care
walker token can match a live early-probe entry on token AND vaddr
(DcachePlugin.scala:420-422) under identity-mapped supervisor page tables and
silently answer the walker from a stale probe snapshot.

The :6-8 layout comment is rewritten in the same edit, as W25 requires: under the
old wording 0x81/0x82 read as 'exception unit, split half 0, robId 1/2', which is
false. Bit 7 now documents as 'non-LS source' with [6]/[5:0] meaningful only when
it is clear.

Declaration-only: no bundle field, no behavioural change, DcachePlugin untouched.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Task 4: The load ownership FIFO, `ldRspTag`, and the CORE-EXC drain-to-zero boundary (W7 load side, W29, W30, W23)

This is the first `LsEuPlugin` task and it is deliberately **CORE-only**: no walker exists yet, so the FIFO holds nothing but `CORE_LS` and `CORE_EXC` tags. That is not a stub — it is exactly the configuration in which spec §11.1 item 9's **live HEAD bug** is reachable (*"no walker required — this is a `CORE-LS` ↔ `CORE-EXC` failure, which is why `rspOwner` cannot see it"*), so the task's own directed test is a real regression test for a real shipping bug, provable before and after.

Implements **W7**'s load-direction FIFO, **W29**'s four-valued push tag, **W30** parts (1) and (2), **W23** (`:2115`, `:2027`'s header, `excLoadOutstanding`'s set term), and W27's rows for the FIFO push, the FIFO pop and W30's clear.

**Files:**
- Modify: `src/main/scala/m68k040/execute/LsEuPlugin.scala` — new file-scope objects above `class LsEuPlugin`; a new ownership `Area` inserted after `val cacheCtrl = host.get[CacheControlService]` (`:219`); `:2027`'s header; `:2115`
- Test: `src/test/scala/m68k040/execute/WalkerDcachePortArbSpec.scala` (create)

**Interfaces:**
- Consumes: `DLoadToken` from Task 3 (not used until Task 7, but the file compiles against it).
- Produces, relied on verbatim by Tasks 5, 6, 7, 8 and 10:
  - File-scope `object LdRspTag { val CORE_LS = 0; val CORE_EXC = 1; val ITLB = 2; val DTLB = 3 }`
  - File-scope `object DcPortOwner { val CORE = 0; val ITLB = 1; val DTLB = 2 }`
  - File-scope `object WalkerIdx { val ITLB = 0; val DTLB = 1; val COUNT = 2 }`
  - Inside `logic`, an `Area` named `dcArb` exposing: `ldOwner: UInt(2 bits)` (a `Reg`), `walkerOwnsLoad: Bool`, `ldOwnerFifoEmpty: Bool`, `ldOwnerFifoFull: Bool`, `ldOwnerFifoOccupancy: UInt`, `ldOwnerFifoHoldsWalker: Bool`, `ldRspTag: UInt(2 bits)`, `ldPushTag: UInt(2 bits)`, `walkerLoadAdmit: Vec[Bool]` (length `WalkerIdx.COUNT`), `excLoadOutstanding: Bool` (a `Reg`), `ldBusyExc: Bool`, `excLoadAdmit: Bool`, `coreLsLoadAdmit: Bool`. Every one is `simPublic`.
  - `LsEuPlugin` gains **no** constructor parameter in this task.

- [ ] **Step 0: Re-check the live-bug status before writing any code**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
git log --all --oneline --since=2026-08-16 -- \
  src/main/scala/m68k040/exception/ExceptionUnit.scala \
  src/main/scala/m68k040/execute/LsEuPlugin.scala
```
Read every hit's message. If one of them fixes *"an exception-sequencer load consuming a flushed / stale ordinary LS-EU load's response"*:
- Re-derive spec §4.2.3's W30 trace (steps 1-5) against the **then-current** RTL and record the result in the progress ledger.
- Build W30 **on top of** that fix; do not duplicate its mechanism.
- **Do not weaken W30 part (2)** on the grounds that the trace no longer reproduces — spec §11.1 item 9 forbids exactly that, because part (2) is also what restores W14's argument for the walker-vs-`CORE-EXC` case, which no upstream fix covers.

If there is no such commit (the state when this plan was written), proceed and record in the report that W30 closes the bug as a by-product, so the standalone task can be closed as already-satisfied rather than re-implemented.

- [ ] **Step 1: Write the failing test**

Create `src/test/scala/m68k040/execute/WalkerDcachePortArbSpec.scala`. Later tasks add tests to this same file; this task creates it with the **W30** row from spec §10.2 plus the FIFO's own invariants. The DUT is the existing full-core LS/exception cluster the `ls/` specs already build — reuse `src/test/scala/m68k040/ls/PreciseDrainIrqRaceSpec.scala`'s DUT construction verbatim as the starting point (it already instantiates `LsEuPlugin` + `DcachePlugin` + `ExceptionUnit` + a `BehavioralMemAgent`), and add `simPublic` taps for the `dcArb` signals listed under **Interfaces** above.

```scala
package m68k040.execute

import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** W7 / W29 / W30 / W23 / W24 / W27 / W28 -- the directed suite for the D-cache port
  * arbitration layer (walker/DcacheService passthrough design, section 10.2).
  *
  * Grown task by task: Task 4 adds the W30 rows, Task 5 the CORE-LS response rows, Task 6
  * the store rows, Tasks 7-9 the walker rows. Deliberately UNTAGGED so `test-fast` runs it. */
class WalkerDcachePortArbSpec extends AnyFunSuite {

  // ── Task 4 rows: the ownership FIFO and the CORE-EXC boundary ──────────────────

  test("W7: the FIFO holds exactly one entry per accepted loadCmd and pops one per loadRsp") {
    // Structural invariant, checked continuously over an ordinary mixed load stream with
    // NO exception and NO walker: occupancy must equal (fires so far - responses so far),
    // and must never exceed 3 -- the maximum DcachePlugin can have accepted-and-unresponded
    // (spec section 4.2.3, "Maximum simultaneously accepted-but-unresponded loads: 3").
    withDut("fifo-accounting", seed = 1) { dut =>
      var fires = 0; var rsps = 0
      dut.clockDomain.onSamplings {
        if (dut.dcLoadCmdFire.toBoolean) fires += 1
        if (dut.dcLoadRspValid.toBoolean) rsps += 1
        val occ = dut.ldOwnerFifoOccupancy.toInt
        assert(occ == fires - rsps, s"FIFO occupancy $occ != in-flight ${fires - rsps}")
        assert(occ <= 3, s"FIFO occupancy $occ exceeded the proven 3-entry bound")
      }
      runLoadStream(dut, cycles = 3000)
      assert(fires > 50, s"the stimulus did not exercise the port (only $fires fires)")
    }
  }

  test("W30(1): excLoadOutstanding does NOT clear on a CORE-LS straggler's response") {
    // The spec section 4.2.3 W30 trace, forced end to end:
    //  1. an ordinary CORE-LS load MISSES (long refill), so loadCmdPort.ready goes low;
    //  2. an RTE raises excActive with that response still owed;
    //  3. REPLAY re-launches the filled line's read AND goto(IDLE) in the SAME cycle, so
    //     the next cycle has ready HIGH while the replayed read is still in S1;
    //  4. the exception sequencer's held, registered dcLoadCmd.valid fires in that cycle.
    // Under the naive `when(dcache.loadRsp.valid && excLoadOutstanding)` mirror the CORE-LS
    // response then clears someone else's flag.
    withDut("w30-clear", seed = 2) { dut =>
      forceW30Window(dut)
      // The CORE-LS straggler's response lands first; it carries CORE_LS, so it must not
      // clear the exception's flag.
      waitFor(dut) { dut.dcLoadRspValid.toBoolean && dut.ldRspTag.toInt == 0 /*CORE_LS*/ }
      dut.clockDomain.waitSampling()
      assert(dut.excLoadOutstanding.toBoolean,
        "excLoadOutstanding cleared on a CORE-LS response -- the naive mirror is in place")
    }
  }

  test("W30(2): the exception sequencer's load is not admitted with ANY response in flight") {
    withDut("w30-admit", seed = 3) { dut =>
      dut.clockDomain.onSamplings {
        // The tautology-against-W30 structural tripwire, checked from the testbench as well
        // as from the RTL assertion, because it is the property a future weakening breaks.
        assert(!(dut.excLoadAdmit.toBoolean && !dut.ldOwnerFifoEmpty.toBoolean),
          "excLoadAdmit was weakened: the exception's command can be admitted with a " +
          "response in flight")
      }
      forceW30Window(dut)
      runExceptionEpisode(dut)
    }
  }

  test("W30(3): the exception sequencer captures ITS OWN word, not the LS load's") {
    // THIS is the assertion that can be demonstrated failing at current HEAD (spec section
    // 11.1 item 9) -- it is the live production bug, not a hypothetical.
    withDut("w30-own-word", seed = 4) { dut =>
      val expected = plantDistinctExceptionWord(dut)   // e.g. the vector word at 0x0080
      forceW30Window(dut)
      val got = captureExcLoadResult(dut)
      assert(got == expected,
        f"the exception sequencer captured 0x$got%08x, expected its own 0x$expected%08x " +
        "-- it consumed the CORE-LS straggler's response")
    }
  }

  test("W29: the push tag is the leg that actually fired, never a separately-clocked owner") {
    withDut("w29-tag", seed = 5) { dut =>
      dut.clockDomain.onSamplings {
        if (dut.dcLoadCmdFire.toBoolean) {
          val nAdmit = Seq(dut.coreLsLoadAdmit, dut.excLoadAdmit).count(_.toBoolean)
          assert(nAdmit == 1,
            s"loadCmd.fire with $nAdmit admission predicates true -- must be EXACTLY one " +
            "(a zero-predicate fire is an unqualified presentation header)")
          val expect = if (dut.excLoadAdmit.toBoolean) 1 /*CORE_EXC*/ else 0 /*CORE_LS*/
          assert(dut.ldPushTag.toInt == expect,
            s"pushed tag ${dut.ldPushTag.toInt} does not name the leg that fired ($expect)")
        }
      }
      runLoadStream(dut, cycles = 2000)
      runExceptionEpisode(dut)
    }
  }
}
```

The four helpers (`withDut`, `runLoadStream`, `forceW30Window`, `runExceptionEpisode`, `plantDistinctExceptionWord`, `captureExcLoadResult`, `waitFor`) are private methods of this class. `forceW30Window` is the load-bearing one and must be written to the trace, not approximated:

```scala
  /** Drives spec section 4.2.3's W30 window deterministically: an in-flight CORE-LS load
    * MISS at the moment `excActive` rises. The window is 1-2 cycles wide, so it is created
    * by construction rather than hunted for:
    *   - arm the behavioural memory with a long (>= 20 cycle) read latency so the refill is
    *     unambiguously in flight;
    *   - issue an ordinary supervisor load to an address known not to be resident;
    *   - the cycle after `dcache.loadCmd.fire`, inject the RTE / trap that raises excActive;
    *   - do NOT poke `loadCmdPort.ready` -- the whole point is that DcachePlugin raises it
    *     itself on REPLAY's `goto(IDLE)` while the replayed read is still in S1. */
  private def forceW30Window(dut: ArbDut): Unit = { /* … as described … */ }
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
free -g && ~/sbt/bin/sbt "testOnly m68k040.execute.WalkerDcachePortArbSpec"
```
Expected: compilation FAILS — `value ldOwnerFifoOccupancy is not a member of …`. After the Step-3 signals exist but before the W30 terms, expected: **`W30(1)` and `W30(3)` FAIL** (`W30(3)` is the live HEAD bug), `W29` FAILS on the zero-predicate check at the unqualified `:2027` header.

- [ ] **Step 3: Add the file-scope encodings**

At the top of `src/main/scala/m68k040/execute/LsEuPlugin.scala`, immediately above `class LsEuPlugin`, add the three objects **exactly** as given in this plan's "Naming contract" section. Copy them verbatim, including the comments — the three encodings are different numbers and spec §4.2.3's round-5 correction (M-R5-4) exists because conflating them is a real, reachable bug.

- [ ] **Step 4: Add the ownership `Area`**

Insert into `logic`, immediately after `val cacheCtrl = host.get[m68k040.services.CacheControlService]` (`:219`) and **before** the exc-arbitration `allowOverride` defaults at `:224`. It must be this early because `:263-265` (Task 6) and `:716` (Task 5) both read signals it declares.

```scala
    // ══ D-cache port ownership and admission (walker/DcacheService passthrough, section 4.2)
    //
    // Declared HERE, ahead of every consumer, because :263-265's store base drive and
    // :716's alignedSendValid both read out of it. Combinational members are declared and
    // assigned in place; the ones that depend on later-declared LS state (coreLsLoadReq,
    // the walker request valids) are forward-declared as bare Bools and driven from the
    // arbitration block near the mux, per SpinalHDL's ordinary declare-then-drive shape.
    val dcArb = new Area {

      // ── W28 clause (c): ldOwner is the CURRENT GRANT, reloaded unconditionally every
      // cycle from the arbiter's decision. It is NOT "latched on a grant and held" --
      // a held owner is exactly the current-grant vs in-flight confusion that produced
      // the round-3 C2 finding. Anything that must persist across cycles lives in the
      // ownership FIFO (loads) or in the outstanding counters (stores).
      //
      // W28 clause (a): this plan implements the REGISTERED-GRANT shape, so
      // `walkerLoadAdmit(i)`'s grant term IS `ldOwner === <i's owner code>` and
      // `walkerOwnsLoad` IS `ldOwner =/= CORE`. Identical value, identical cycle, by
      // construction rather than by review. The combinational-grant shape is NOT used.
      val ldOwner = Reg(UInt(2 bits)) init U(DcPortOwner.CORE, 2 bits)
      ldOwner.simPublic()
      val walkerOwnsLoad = ldOwner =/= U(DcPortOwner.CORE, 2 bits)

      // ── W7: the depth-4 load ownership FIFO. Depth 4 is one more than the PROVEN
      // maximum of 3 simultaneously accepted-but-unresponded loads (spec section 4.2.3),
      // so it can never be the binding constraint. Written as an explicit ring rather
      // than a StreamFifo because every view below must be PRE-push and PRE-pop -- a
      // function of the REGISTERED state only, never of this cycle's push/pop. That
      // reading is load-bearing for section 4.3's second assertion and must not be
      // "simplified" to a post-pop one.
      val ldFifoDepth = 4
      val ldFifoTags  = Vec.fill(ldFifoDepth)(Reg(UInt(2 bits)) init U(LdRspTag.CORE_LS, 2 bits))
      val ldFifoValid = Vec.fill(ldFifoDepth)(RegInit(False))
      val ldFifoPushPtr = Reg(UInt(log2Up(ldFifoDepth) bits)) init 0
      val ldFifoPopPtr  = Reg(UInt(log2Up(ldFifoDepth) bits)) init 0

      val ldOwnerFifoEmpty     = !ldFifoValid.reduce(_ || _)
      val ldOwnerFifoFull      = ldFifoValid.reduce(_ && _)
      val ldOwnerFifoOccupancy = CountOne(ldFifoValid)
      val ldRspTag             = ldFifoTags(ldFifoPopPtr)          // PRE-pop head
      val ldOwnerFifoHoldsWalker = (0 until ldFifoDepth).map(i =>
        ldFifoValid(i) &&
        (ldFifoTags(i) === U(LdRspTag.ITLB, 2 bits) ||
         ldFifoTags(i) === U(LdRspTag.DTLB, 2 bits))).reduce(_ || _)
      ldOwnerFifoEmpty.simPublic(); ldOwnerFifoFull.simPublic()
      ldOwnerFifoOccupancy.simPublic(); ldRspTag.simPublic()
      ldOwnerFifoHoldsWalker.simPublic()

      // W29 property 3: `rspOwner` is DERIVED from the tag, never stored beside it. Used
      // only where the meaning is genuinely "which of the three arbiter owners", i.e. by
      // the walker response demux (Task 7) and W26's reporting (Task 11).
      val rspOwner = Mux(ldRspTag === U(LdRspTag.ITLB, 2 bits), U(DcPortOwner.ITLB, 2 bits),
                     Mux(ldRspTag === U(LdRspTag.DTLB, 2 bits), U(DcPortOwner.DTLB, 2 bits),
                                                                U(DcPortOwner.CORE, 2 bits)))

      // ── Admission predicates (spec section 4.2.3). Forward-declared here because
      // `coreLsLoadReq` is built from LS state declared much later in `logic`; driven at
      // the mux. Every qualification in this file cites a PREDICATE by name and never
      // open-codes an owner comparison -- that distinction IS the round-3 C2 finding.
      val coreLsLoadReq   = Bool()
      val coreLsLoadAdmit = Bool()
      val dcLoadHeldByOther = excActive || walkerOwnsLoad
      coreLsLoadReq.simPublic(); coreLsLoadAdmit.simPublic(); dcLoadHeldByOther.simPublic()

      // W23 second site: SET by the admission predicate, never by a bare fire.
      // W30 part (1): CLEARED by W29's tag, never by an unqualified loadRsp.valid mirror
      // of excStoreOutstanding -- the store-side invariant that makes that mirror safe
      // (E_DRAIN/R_DRAIN wait on sqDrained) has NO load-side analogue; it was investigated
      // and DISPROVED against the RTL (spec section 4.2.3, W30).
      val excLoadOutstanding = RegInit(False); excLoadOutstanding.simPublic()
      val ldBusyExc    = excLoadOutstanding || (excActive && excLoadCmdValid)
      // W30 part (2): `ldOwnerFifoEmpty`, NOT `!ldOwnerFifoHoldsWalker`. The exception
      // sequencer's load-side drain-to-zero holds against EVERY other client, not only
      // walkers, because W6 makes CORE-LS and CORE-EXC one arbiter owner and a CORE-LS
      // straggler is exactly as indistinguishable to ExceptionUnit's unqualified
      // dcLoadRsp as a walker's descriptor would be.
      val excLoadAdmit = excActive && excLoadCmdValid && ldOwnerFifoEmpty
      ldBusyExc.simPublic(); excLoadAdmit.simPublic()

      // Walker admission: forward-declared and hardwired FALSE until Task 7 gives the
      // walkers legs. Declared now so W29's push encode below is written ONCE, in its
      // final four-legged form, rather than being rewritten later -- a rewrite is exactly
      // where a chain-order regression would hide.
      val walkerLoadAdmit = Vec(Bool(), WalkerIdx.COUNT)
      for (i <- 0 until WalkerIdx.COUNT) walkerLoadAdmit(i) := False
      walkerLoadAdmit.foreach(_.simPublic())

      // ── W29: the push payload. Derived COMBINATIONALLY, this cycle, from the four
      // ADMISSION PREDICATES that actually gate the mux legs -- never from ldOwner or any
      // other separately clocked register, under EITHER of W28's admissible shapes. The
      // chain order MIRRORS the payload mux's own last-assignment-wins order (CORE-LS
      // base -> walker legs -> CORE-EXC last), so the tag is correct by construction and
      // agrees with the mux under ANY number of simultaneously-true predicates, not only
      // under the exclusivity the assertion below checks.
      val ldPushTag = UInt(2 bits)
      ldPushTag := U(LdRspTag.CORE_LS, 2 bits)
      when(walkerLoadAdmit(WalkerIdx.ITLB)) { ldPushTag := U(LdRspTag.ITLB, 2 bits) }
      when(walkerLoadAdmit(WalkerIdx.DTLB)) { ldPushTag := U(LdRspTag.DTLB, 2 bits) }
      when(excLoadAdmit)                    { ldPushTag := U(LdRspTag.CORE_EXC, 2 bits) }
      ldPushTag.simPublic()
    }
```

- [ ] **Step 5: Drive the FIFO, the outstanding flag, and the W27 rows it creates**

Add at the **end** of `logic`, after `:2115` (so `dcache.loadCmd.fire` and `dcache.loadRsp.valid` are fully driven). Three W27 rows, each with its disposition written into the source:

```scala
    // ── W27 row (new, W29): the FIFO PUSH. The `fire` is correctly UNQUALIFIED -- the
    // push must happen on EVERY accepted command regardless of owner, because that is
    // what makes the tag POSITIONAL. The qualification lives in the PAYLOAD (ldPushTag).
    // This is the row that would have caught the round-4 Critical: the defect was never
    // "an unqualified fire", it was "a qualified fire paired with an unqualified value".
    when(dcache.loadCmd.fire) {
      dcArb.ldFifoTags(dcArb.ldFifoPushPtr)  := dcArb.ldPushTag
      dcArb.ldFifoValid(dcArb.ldFifoPushPtr) := True
      dcArb.ldFifoPushPtr := dcArb.ldFifoPushPtr + 1
    }
    // ── W27 row (new, W7): the FIFO POP. DELIBERATELY UNQUALIFIED, and it MUST STAY THAT
    // WAY. This is the single site in this file where a bare `dcache.loadRsp.valid`
    // consumer is REQUIRED: positional tagging is correct only if exactly one entry is
    // popped per response, whoever the response belongs to. Adding an owner term here
    // would desynchronise the FIFO from the response stream and mis-tag every subsequent
    // response -- the "fix" would be the bug. Recorded explicitly so a future sweep of
    // the ownerless-handshake defect class does not "correct" it. Soundness rests on the
    // two premises proved in spec section 4.2.3: exactly one loadRsp per accepted
    // loadCmd, and strict in-order completion by DcachePlugin.
    when(dcache.loadRsp.valid) {
      dcArb.ldFifoValid(dcArb.ldFifoPopPtr) := False
      dcArb.ldFifoPopPtr := dcArb.ldFifoPopPtr + 1
    }
    // ── W23 second site: SET by the admission predicate, not by a bare fire. Under a
    // walker grant `dcache.loadCmd.fire` is the WALKER's, and setting this flag on it
    // corrupts ldBusyExc -- which is W7's own input, so the bug would feed straight back
    // into the hand-over rule meant to prevent it.
    when(dcache.loadCmd.fire && dcArb.excLoadAdmit) { dcArb.excLoadOutstanding := True }
    // ── W27 row (new, W30): the CLEAR. Qualified by W29's tag. The obvious mirror
    // `when(dcache.loadRsp.valid && excLoadOutstanding)` is a new instance of exactly the
    // defect class this sweep exists to eliminate, and its failure is reachable at HEAD.
    when(dcache.loadRsp.valid && dcArb.ldRspTag === U(LdRspTag.CORE_EXC, 2 bits)) {
      dcArb.excLoadOutstanding := False
    }
```

- [ ] **Step 6: Bind `excLoadAdmit` to the RTL it actually gates (W23, `:2027` and `:2115`)**

Nothing in the design's earlier rounds bound `excLoadAdmit` to a line of RTL, which is how the exception sequencer came to present its command straight into an occupied FIFO. Two edits:

```scala
    // was: when(excActive && excLoadCmdValid) {          // :2027 -- UNQUALIFIED
    when(dcArb.excLoadAdmit) {                            // W23 / W27 :2027-2028 row
```
and

```scala
    // was: excLoadCmdReady := dcache.loadCmd.ready       // :2115 -- UNCONDITIONAL
    //
    // W23. `dcache.loadCmd.ready` (DcachePlugin.scala:981) does NOT reference `valid` at
    // all -- DcachePlugin asserts it on its own schedule. In exactly the window this
    // design most needs protected (a response outstanding, the exception command
    // correctly WITHHELD) a `ready` pulse would otherwise advance the exception FSM with
    // NO command issued, and it would then consume the next response as its own.
    //
    // The predicate is `excLoadAdmit` and NOT the weaker `(ldOwner === CORE) && excActive
    // && excLoadCmdValid`: an owner COMPARISON is a current-grant fact where the hazard is
    // an IN-FLIGHT fact. That substitution is the single most transferable lesson in the
    // design document.
    excLoadCmdReady := dcache.loadCmd.ready && dcArb.excLoadAdmit
```

- [ ] **Step 7: Add the simulation assertion block (spec §4.3, §4.2.3 property 2)**

Add at the end of `logic`, in the style of `DcachePlugin.scala:2073-2087`'s existing block:

```scala
    // ── Structural tripwires (walker/DcacheService passthrough, sections 4.3 and 4.2.3).
    // Every one is a property LsEuPlugin holds BY CONSTRUCTION under a correct
    // implementation, so none can fire on correct behaviour -- they fire only if a future
    // edit weakens the thing they name.
    //
    // SUPERSEDED FORMS, kept as comments because an assertion that is wrong in the
    // WEAKENING direction and one that is wrong in the FIRING-ON-CORRECT-BEHAVIOUR
    // direction fail differently and a maintainer should recognise either:
    //   ROUND-3 (walker-only, cannot see a CORE-LS straggler):
    //     assert(!(ldOwnerFifoHoldsWalker && (excLoadOutstanding || (excActive && excLoadCmdValid))))
    //   ROUND-4 (WRONG -- fires on correct behaviour: excLoadCmdValid is ExceptionUnit's
    //   HELD ldoValidReg, so this predicate IS the W30 wait, not a violation of it):
    //     assert(!(!ldOwnerFifoEmpty && (excActive && excLoadCmdValid) && !excLoadOutstanding))
    GenerationFlags.simulation {
      // Tautological against W30 part (2) ON PURPOSE: a tripwire against a future
      // weakening of excLoadAdmit's FIFO term back toward !ldOwnerFifoHoldsWalker, which
      // would simultaneously reopen the round-3 C2 finding and re-inherit the live HEAD
      // bug of spec section 11.1 item 9.
      assert(!(dcArb.excLoadAdmit && !dcArb.ldOwnerFifoEmpty),
        "excLoadAdmit was weakened: the exception's command can be admitted with a " +
        "response in flight", FAILURE)
      // The direct, checkable statement of W30's soundness proof. Reads occupancy
      // PRE-pop, per the FIFO's own definition; that reading is load-bearing (on the
      // cycle the exception's own response arrives, excLoadOutstanding is still True
      // because the clear takes effect at the NEXT edge, and the entry it names is still
      // the registered head).
      assert(!(dcArb.excLoadOutstanding &&
               !(dcArb.ldOwnerFifoOccupancy === 1 &&
                 dcArb.ldRspTag === U(LdRspTag.CORE_EXC, 2 bits))),
        "excLoadOutstanding is set without exactly its own command in the ownership FIFO",
        FAILURE)
      // W29 property 2, in its ROUND-6 form: FIRE-QUALIFIED and `=== 1`, not `<= 1`.
      // `<= 1` is satisfied by ZERO predicates true, and "a loadCmd.fire happened with no
      // admission predicate authorising it" is a real, reachable state -- exactly what an
      // unqualified presentation header produces.
      when(dcache.loadCmd.fire) {
        assert(CountOne(Cat(dcArb.coreLsLoadAdmit, dcArb.excLoadAdmit,
                            dcArb.walkerLoadAdmit(WalkerIdx.ITLB),
                            dcArb.walkerLoadAdmit(WalkerIdx.DTLB))) === 1,
          "load-admission predicates are not one-hot at loadCmd.fire", FAILURE)
      }
      // The FIFO's own protocol: a response with nothing outstanding would desynchronise
      // the positional tagging for every later response.
      assert(!(dcache.loadRsp.valid && dcArb.ldOwnerFifoEmpty),
        "a loadRsp arrived with an empty ownership FIFO", FAILURE)
    }
```

**Note for the implementer:** the one-hot assertion reads `coreLsLoadAdmit`, which Task 5 drives. Until then, drive it from the temporary expression `Mux(useSplitCmd, llReg.valid, alignedSendValid)` at the mux site so the assertion is meaningful in this task; Task 5 replaces that with the real `coreLsLoadReq && !dcLoadHeldByOther`.

- [ ] **Step 8: Run the tests to verify they pass**

```bash
free -g && ~/sbt/bin/sbt "testOnly m68k040.execute.WalkerDcachePortArbSpec"
make SBT=~/sbt/bin/sbt test-fast
python3 tools/socket/check_socket_netlist.py
```
Expected: all five Task-4 rows PASS; `test-fast` matches Task 1's baseline (this task is behaviour-changing **only** in the direction that fixes the §11.1 item 9 bug — if any existing test regresses, that is a finding, not a rebaseline); FREEZE check PASSES.

- [ ] **Step 9: Commit**

```bash
git add src/main/scala/m68k040/execute/LsEuPlugin.scala \
        src/test/scala/m68k040/execute/WalkerDcachePortArbSpec.scala
git commit -m "walker(W7/W29/W30/W23): load ownership FIFO + the CORE-EXC drain-to-zero boundary

Depth-4 positional ownership FIFO inside LsEuPlugin, pushed with W29's
four-valued ldRspTag encoded COMBINATIONALLY from the admission predicates that
gate the mux legs (never from a separately clocked owner register), popped
deliberately unqualified. All FIFO views are pre-push/pre-pop.

W30 part (1): excLoadOutstanding's clear is tagged, not a bare loadRsp.valid
mirror of excStoreOutstanding -- the store-side invariant that makes that mirror
safe has no load-side analogue (investigated and disproved).
W30 part (2): excLoadAdmit requires ldOwnerFifoEmpty, so the exception
sequencer's load-side drain-to-zero holds against EVERY client, not only walkers.
That closes spec section 11.1 item 9's LIVE HEAD bug as a by-product.
W23: :2027's presentation header and :2115's excLoadCmdReady both take
excLoadAdmit -- an ADMISSION predicate, not the weaker owner comparison, which is
a current-grant fact where the hazard is an in-flight fact.

DcachePlugin.scala untouched. No DcacheService bundle change. No DUT rewired.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Task 5: The CORE-LS load side — split-leg visibility, admission, and response tagging (W27 load rows, W11's `:716` factor-up)

Implements W27's `:756`, `:757-764`, `:766`, `:1508`, `:1536`, `:720`, `:1476`/`:1480`, `:1520`, `:1541`, `:1574-1575`, `:1579` rows, plus the `:716` / `:1774-1776` half of W11's substitution. Still **CORE-only** — `walkerOwnsLoad` is `False` until Task 7 — but the *structure* that makes a walker safe lands here, and the split-leg half of it is the one genuinely structural item in the whole defect class.

**Files:**
- Modify: `src/main/scala/m68k040/execute/LsEuPlugin.scala:716-720`, `:756`, `:1508`, `:1536`, `:1520`, `:1541`, `:1574-1575`, `:1774-1776`
- Test: `src/test/scala/m68k040/execute/WalkerSplitLoadRaceSpec.scala` (create)

**Interfaces:**
- Consumes: `dcArb.{coreLsLoadReq, coreLsLoadAdmit, dcLoadHeldByOther, ldRspTag}` from Task 4.
- Produces, relied on by Task 7:
  - `alignedSendValidRaw: Bool` — `:716`'s expression **without** the `!excActive`/`!dcLoadHeldByOther` term.
  - `alignedSendValid: Bool` — `alignedSendValidRaw && !dcArb.dcLoadHeldByOther`; **keeps its current meaning**, so `:766`'s `alignedCmdFire` is unchanged and still reads the same signal that drives the port.
  - `dcArb.coreLsLoadReq` / `dcArb.coreLsLoadAdmit` are now really driven.

- [ ] **Step 1: Write the failing test**

Create `src/test/scala/m68k040/execute/WalkerSplitLoadRaceSpec.scala` with spec §10.2's three rows. The "foreign" fire/response that a walker would produce is injected in this task by a **test-only forced fire** on the shared `dcache.loadCmd` port (a second command admitted while the BK FSM is in `WAIT_A`); Task 7 re-points the same tests at a real walker leg and they must keep passing unchanged.

```scala
package m68k040.execute

import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** W27's split leg (walker/DcacheService passthrough design, section 4.2.7, "The split leg").
  *
  * `useSplitCmd = bkBusy` and :756 reads
  *   dcache.loadCmd.valid := Mux(useSplitCmd, llReg.valid, alignedSendValid)
  * so W11's owner fold, which reaches only the `alignedSendValid` leg at :716, is BYPASSED
  * ENTIRELY while a split is in flight: llReg.valid reaches the port with no owner or
  * arbitration term anywhere in its cone.
  *
  * Deliberately UNTAGGED so `test-fast` runs it. */
class WalkerSplitLoadRaceSpec extends AnyFunSuite {

  test("W27(a): a foreign loadCmd.fire in WAIT_A must not advance the BK FSM past slot B") {
    // 1. drive a cross-line split load to WAIT_A with aDone LOW, so llReg.valid is low and
    //    the arbiter sees CORE-LS idle;
    // 2. admit a FOREIGN command (Task 7: a walker's; here: an injected one);
    // 3. let slot A land so aDone rises and WAIT_A raises llReg.valid;
    // 4. let the foreign command fire in that window.
    // Unqualified, :1536 sees the FOREIGN fire, clears llReg.valid and goes to WAIT_B with
    // SLOT B NEVER ISSUED.
    withSplitDut("waitA-foreign-fire", seed = 1) { dut =>
      driveSplitToWaitA(dut)
      val slotBPaddr = dut.expectedSlotBPaddr
      injectForeignFire(dut)
      releaseSlotA(dut)
      // Asserted on a CACHE-SIDE command observer, never on the FSM's own state: the
      // failure mode is precisely "the FSM believes it issued".
      assert(observedLoadCmdPaddrs(dut).contains(slotBPaddr),
        "slot B was never issued to the cache -- the BK FSM advanced on a foreign fire")
    }
  }

  test("W27(a'): the LAUNCH twin -- a foreign fire must not advance past slot A") {
    withSplitDut("launch-foreign-fire", seed = 2) { dut =>
      driveSplitToLaunch(dut)
      val slotAPaddr = dut.expectedSlotAPaddr
      injectForeignFire(dut)
      assert(observedLoadCmdPaddrs(dut).contains(slotAPaddr),
        "slot A was never issued -- :1508 advanced on a foreign fire")
    }
  }

  test("W27(b): bkCompletes' WAIT_B arm must ignore a foreign loadRsp") {
    // bkCompletes is defined OUTSIDE both whenIsActive bodies (its own comment: 'Derived
    // from bkFsm.isActive rather than from an assignment inside either FSM'), so an editor
    // fixing only the two FSM bodies misses it. Unqualified, the WAIT_B arm treats a
    // foreign response as slot B's completion, releasing S1/backCompFires and completing a
    // cross-line load from someone else's line.
    withSplitDut("waitB-foreign-rsp", seed = 3) { dut =>
      driveSplitToWaitB(dut)
      injectForeignLoadRsp(dut, tag = 2 /* LdRspTag.ITLB */, data = 0xDEADBEEFL)
      dut.clockDomain.waitSampling()
      assert(!dut.bkCompletes.toBoolean, "bkCompletes fired on a foreign response")
      assert(!dut.backCompFires.toBoolean, "backCompFires fired on a foreign response")
      assert(mergedResultOf(dut) != 0xDEADBEEFL, "the split load completed from foreign data")
    }
  }

  test("W27(c): a split pending in WAIT_A registers as a CORE-LS request to the arbiter") {
    // Half (i) of the fix. Without it a split load is INVISIBLE to the arbiter and the
    // aging counters reason about a requester that is not in their request set --
    // which is what makes spec section 4.2.5's starvation proof false for split loads.
    withSplitDut("split-visibility", seed = 4) { dut =>
      driveSplitToWaitA(dut)
      releaseSlotA(dut)                       // aDone rises, llReg.valid rises
      dut.clockDomain.waitSampling()
      assert(dut.coreLsLoadReq.toBoolean,
        "a split load with llReg.valid high is invisible to the arbiter")
    }
  }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
free -g && ~/sbt/bin/sbt "testOnly m68k040.execute.WalkerSplitLoadRaceSpec"
```
Expected: (a) and (a′) FAIL — the split FSM advances without issuing; (b) FAILS — the load completes from foreign data; (c) FAILS — `coreLsLoadReq` is low.

- [ ] **Step 3: Factor `:716`'s owner term up into `coreLsLoadAdmit`**

Replace `LsEuPlugin.scala:716-720`:

```scala
    // W11 / W27: the owner term MOVES UP one level, into `coreLsLoadAdmit`, so it covers
    // the SPLIT leg of :756 too. `alignedSendValid` keeps its current meaning, so :766's
    // `alignedCmdFire` is unchanged and still reads the same signal that drives the port
    // -- which is exactly why that row needs no term of its own: the fire qualifier and
    // the valid drive read ONE signal. That is the pattern every other ready site copies.
    val alignedSendValidRaw = !alignedEmpty && alignedValid(alignedSendPtr) &&
                              !alignedSent(alignedSendPtr) && !bkBusy
    val alignedSendValid = alignedSendValidRaw && !dcArb.dcLoadHeldByOther
    val alignedRspValid = !alignedEmpty && alignedValid(alignedRspPtr) &&
                          alignedSent(alignedRspPtr) && !bkBusy
    // W7 caveat (a) / W27 :720 row: under the ownership FIFO, CORE's response consumers
    // are NO LONGER guaranteed inert when someone else's response arrives. Qualified by
    // `ldRspTag === CORE_LS` -- W29's four-valued tag, STRICTLY STRONGER than the
    // three-valued `rspOwner === CORE`, which was sound only via a secondary
    // alignedSent/aDone argument this design should not rest on. Not cosmetic:
    // `alignedRspFire` decrements alignedCount and advances alignedRspPtr, so an
    // unqualified version pops a core load off the aligned queue on somebody else's
    // response and hands a foreign word to an architectural register.
    val alignedRspFire  = alignedRspValid && dcache.loadRsp.valid &&
                          (dcArb.ldRspTag === U(LdRspTag.CORE_LS, 2 bits))
```
`:1476`/`:1480` need **no** term: they are nested under the now-qualified `alignedRspFire`. Add a one-line comment saying so, so a later reviewer can tell "considered and justified" from "not looked at".

- [ ] **Step 4: Make the split leg visible and admitted (`:756`)**

Replace `:756` and drive Task 4's forward declarations:

```scala
    // ── W27's split leg, half (i) VISIBILITY and half (ii) ADMISSION. Doing only one of
    // them is WORSE than doing neither: gating without requesting turns the silent
    // corruption into a livelock.
    dcArb.coreLsLoadReq   := Mux(useSplitCmd, llReg.valid, alignedSendValidRaw)
    dcArb.coreLsLoadAdmit := dcArb.coreLsLoadReq && !dcArb.dcLoadHeldByOther
    dcache.loadCmd.valid  := dcArb.coreLsLoadAdmit
```
The payload drives at `:757-765` are unchanged. **W27's `:757-764` row is a recorded constraint, not a code change** — add this comment immediately above them:

```scala
    // ── W27 :757-764 row (NEW -- stated, no term needed). This CORE-LS payload base is
    // safe for exactly one reason and only that reason: the walker legs and the exception
    // leg are LATER drivers in this same scope, and last-assignment-wins overrides this
    // base. THE ORDERING IS LOAD-BEARING, NOT INCIDENTAL: walker legs after this base,
    // the exception leg last, per the priority chain of section 4.2.4. Do not reorder.
    // (After W29 the TAGGING no longer depends on this ordering -- the tag comes from the
    // pairwise-exclusive admission predicates -- but the PAYLOAD SELECTION still does.)
```

- [ ] **Step 5: Qualify the two BK-FSM `loadCmd.fire` samplings**

`:1508` (`LAUNCH`) and `:1536` (`WAIT_A`):

```scala
        when(dcache.loadCmd.fire && dcArb.coreLsLoadAdmit) {   // W27 :1508 / :1536 rows
```
Both, with the identical predicate and the identical reason: unqualified, a foreign fire advances the split FSM with a slot never issued.

- [ ] **Step 6: Qualify the three remaining `loadRsp` consumers**

`:1520` (`WAIT_A`), `:1541` (`WAIT_B`), and — the row that is structurally easiest to miss because it is defined *outside* both `whenIsActive` bodies — `:1574-1575`:

```scala
        when(dcache.loadRsp.valid && !aDone &&
             (dcArb.ldRspTag === U(LdRspTag.CORE_LS, 2 bits))) {          // :1520
...
        when(dcache.loadRsp.valid &&
             (dcArb.ldRspTag === U(LdRspTag.CORE_LS, 2 bits))) {          // :1541
...
    // W27 :1574-1575 row (NEW -- FIX). BOTH arms take the tag. Deliberately defined
    // outside both whenIsActive bodies (see the comment above), so an editor implementing
    // "the WAIT_A/WAIT_B sampling" literally touches the two FSM bodies and leaves this
    // untouched -- which is why the defect class is settled by a TABLE and not by a list
    // of findings. Unqualified, the WAIT_B arm treats a foreign response as slot B's
    // completion, releasing S1 and backCompFires on a mid-flight response.
    val bkRspIsCoreLs = dcache.loadRsp.valid && (dcArb.ldRspTag === U(LdRspTag.CORE_LS, 2 bits))
    val bkCompletes   = (bkInWaitB && bkRspIsCoreLs) ||
                        (bkInWaitA && bkRspIsCoreLs && dcache.loadRsp.payload.fault)
```
`:1579`'s `backCompFires` needs **no** term — it is covered transitively by the two qualified parents. Add a one-line comment saying so.

- [ ] **Step 7: Substitute `!excActive` → `!dcLoadHeldByOther` in the probe arms (`:1774-1776`)**

```scala
    splitReqArm := txValid && txSecond && !txWaitingRsp && !sqFlushSig && !dcArb.dcLoadHeldByOther
    normalReqArm := tValid && tIsMem && txReady && !splitReqArm &&
                    !sqFlushSig && !dcArb.dcLoadHeldByOther && (!tIsLoad || dcache.loadProbe.ready)
```
This is W11's *probe-suppression* half. It is a pure substitution — one register-sourced signal for another inside an existing AND-tree, adding **no** logic level — and it is several levels upstream of `loadProbe.valid`, so it adds nothing at the cache boundary. The `probeCancelAll` half is an *addition* and lands in Task 8. **Leave `:1777-1778`'s `normalReqFire`/`splitReqFire` and `:2083`'s `lsXlateReqValid` on `!excActive`**: those govern the DTLB request stream, which walkers do not contend for (the walker computes physical addresses from `rootPtr` + VA slices; spec §6.1).

- [ ] **Step 8: Run the tests to verify they pass**

```bash
free -g && ~/sbt/bin/sbt "testOnly m68k040.execute.WalkerSplitLoadRaceSpec m68k040.execute.WalkerDcachePortArbSpec"
make SBT=~/sbt/bin/sbt test-fast
python3 tools/socket/check_socket_netlist.py
```
Expected: all four `WalkerSplitLoadRaceSpec` rows PASS, Task 4's five rows still PASS, `test-fast` matches Task 1's baseline, FREEZE PASSES.

- [ ] **Step 9: Commit**

```bash
git add src/main/scala/m68k040/execute/LsEuPlugin.scala \
        src/test/scala/m68k040/execute/WalkerSplitLoadRaceSpec.scala
git commit -m "walker(W27 load side): split-leg visibility+admission and ldRspTag response gating

:716's owner term is factored UP into coreLsLoadAdmit so it covers the SPLIT leg
of :756, which no owner or arbitration term reached at all -- llReg.valid was
driving the port with nothing in its cone. Half (i) VISIBILITY (the split
registers as a CORE-LS request, without which the starvation proof is false for
split loads) and half (ii) ADMISSION land together; doing only one is worse than
doing neither.

The two BK-FSM loadCmd.fire samplings (:1508/:1536) take coreLsLoadAdmit; the
three loadRsp consumers (:720/:1520/:1541) and bkCompletes (:1574-1575, defined
outside both FSM bodies and therefore the easiest row in the table to miss) take
ldRspTag === CORE_LS. :1476/:1480 and :1579 are recorded no-ops, covered
transitively by their qualified parents. :757-764's driver ordering is recorded
as a load-bearing constraint in the source.

W11's probe-SUPPRESSION half (:1774-1776) substituted; the probeCancelAll
ADDITION is Task 8.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Task 6: The store side — `stOwner`, `coreStOutstanding`, and W27's store rows (W13, W24, W5, W28 clause (c) store half)

The store direction keeps **drain-to-zero, unchanged** (W7): it needs no FIFO, because a WRITETHROUGH store is classified *serial* by `inputStoreSerial` (`DcachePlugin.scala:631-632`) so `storePort.ready`'s `!serialStoreInFlight && (storeOutstanding === 0)` term (`:1865-1867`) already forces a walker U/M store to be the sole accepted descriptor. The walker store side is single-outstanding **twice over**, and W13's latched-`stOwner` demux is all the identity the untagged `storeAck` needs.

Implements W13, W24, W5's store half, W28 clause (c)'s store realisation, and W27's `:263-264`, `:265`, `:270-271`, `:2067`, `:2069`, `:2070-2071`, `:2073`, `:2076` rows.

**Files:**
- Modify: `src/main/scala/m68k040/execute/LsEuPlugin.scala` — `dcArb` (add the store members), `:263-265`, `:270-271`, `:2067-2078`
- Test: `src/test/scala/m68k040/execute/WalkerDcachePortArbSpec.scala` (extend)

**Interfaces:**
- Consumes: `dcArb` from Task 4.
- Produces, relied on by Tasks 7 and 14:
  - `dcArb.stOwner: UInt(2 bits)` (a `Reg`), `dcArb.coreStOutstanding: UInt(3 bits)` (a `Reg`), `dcArb.excStoreAdmit: Bool`, `dcArb.coreLsStoreAdmit: Bool`, `dcArb.walkerStoreAdmit: Vec[Bool]` (length `WalkerIdx.COUNT`, all `False` until Task 7), `dcArb.walkStOutstanding: Vec[Bool]` (length `WalkerIdx.COUNT`, all `False` until Task 7). All `simPublic`.

- [ ] **Step 1: Write the failing test**

Append to `WalkerDcachePortArbSpec`:

```scala
  // ── Task 6 rows: the store direction ───────────────────────────────────────────

  test("W24: every StoreQueue entry reaches the cache while a foreign store owns the port") {
    // Asserted against a CACHE-SIDE BYTE-WRITE OBSERVER, never against the SQ's own
    // pointers -- the failure mode is precisely "the SQ believes it drained". :265's
    // `sq.io.drain.ready := dcache.store.ready` is UNCONDITIONAL today, so when a foreign
    // store owns the port the SQ advances sendPtr/acceptedHalves for a store that was
    // never sent. That store is PERMANENTLY LOST and nothing anywhere notices -- a dropped
    // architectural store is the most damaging failure in the design document.
    withDut("w24-sq-not-lost", seed = 10) { dut =>
      val observed = scala.collection.mutable.Map[Long, Byte]()
      dut.mem.setByteWriteObserver((a, b) => observed(a) = b)
      val expected = driveKnownStoreStream(dut, n = 32)   // (addr -> byte) the program wrote
      forceForeignStoreOwnership(dut)                     // Task 7: a walker's U/M store
      drainAndQuiesce(dut)
      for ((a, b) <- expected)
        assert(observed.get(a).contains(b),
          f"store to 0x$a%08x never reached the cache -- the SQ drain consumed a foreign ready")
    }
  }

  test("W27 store: an exception store must not jump ahead of an in-flight foreign U/M store") {
    // With stOwner naming a walker and its U/M store accepted but not yet acked, raise
    // excActive && excStoreValid. Without the :2067/:2069/:2076 fixes the exception payload
    // reaches dcache.store, excStoreOutstanding sets on somebody else's transaction, and
    // the WALKER's storeAck is then demuxed back to the walker -- retiring a U/M queue
    // entry whose descriptor byte was NEVER WRITTEN. That is section 1.1's original
    // coherency bug, reintroduced by the redesign that exists to fix it.
    withDut("w27-exc-vs-walker-store", seed = 11) { dut =>
      val descAddr = forceForeignStoreAccepted(dut)       // accepted, ack withheld
      raiseExceptionStore(dut)
      dut.clockDomain.waitSampling(4)
      assert(!dut.excStoreReady.toBoolean, "excStoreReady pulsed under a foreign grant")
      assert(!dut.excStoreOutstanding.toBoolean,
        "excStoreOutstanding set on a foreign store's fire")
      releaseForeignStoreAck(dut)
      assert(descriptorByteInMemory(dut, descAddr) == expectedUmByte,
        "the U/M descriptor byte was never written but the queue entry retired")
    }
  }

  test("W13/W28(c): stOwner is held while ANY store is outstanding, and the ack demuxes by it") {
    withDut("w13-ack-demux", seed = 12) { dut =>
      dut.clockDomain.onSamplings {
        // The third of section 4.3's mandated assertions, checked from the bench too.
        assert(!(dut.stOwner.toInt != 0 /*CORE*/ && dut.coreStOutstanding.toInt != 0),
          "a foreign client held the D-cache store port with a core store still outstanding")
        // StoreQueue.scala:518 asserts on a stray ack, so a mis-demuxed walker ack is an
        // immediate simulation failure rather than a subtle corruption -- but assert the
        // POSITIVE property here so the test pins the demux, not the SQ's own guard.
        if (dut.dcStoreAck.toBoolean && dut.stOwner.toInt != 0)
          assert(!dut.sqDrainAck.toBoolean, "a foreign storeAck reached the StoreQueue")
      }
      interleaveCoreAndForeignStores(dut, cycles = 3000)
    }
  }
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
free -g && ~/sbt/bin/sbt "testOnly m68k040.execute.WalkerDcachePortArbSpec"
```
Expected: the three new rows FAIL to compile (`stOwner` not a member), then FAIL on behaviour once the signals exist but the terms do not.

- [ ] **Step 3: Add the store-side members to `dcArb`**

Append inside the `dcArb` `Area` from Task 4:

```scala
      // ── W5: ownership is tracked INDEPENDENTLY per direction. NOT on D9's deadlock
      // grounds -- that argument does not transfer down to the DcacheService layer, where
      // no client can hold DcachePlugin's own AXI write channel -- but because a walker's
      // READ phase and its U/M WRITE phase are separated by an ENTIRE ROB COMMIT (the
      // descriptor reads happen at walk time; the U/M byte drains at the triggering
      // instruction's RETIREMENT). A combined token would serialise two operations that
      // are already hundreds of cycles apart, for no correctness benefit whatsoever.
      val stOwner = Reg(UInt(2 bits)) init U(DcPortOwner.CORE, 2 bits); stOwner.simPublic()

      // W6 makes the LS pipe and the exception sequencer ONE owner, so this counter must
      // count the exception sequencer's accepted stores AS WELL AS the SQ drain's -- the
      // :2073 row's safety argument depends on exactly that.
      val coreStOutstanding = Reg(UInt(3 bits)) init 0; coreStOutstanding.simPublic()

      // Walker-side outstanding stays ONE BIT each: the U/M drain is single-outstanding by
      // its own arming gate, and DcachePlugin's serial-store rule makes it so a second
      // time. All-False until Task 7.
      val walkStOutstanding = Vec.fill(WalkerIdx.COUNT)(RegInit(False))
      walkStOutstanding.foreach(_.simPublic())
      val walkerStoreAdmit = Vec(Bool(), WalkerIdx.COUNT)
      for (i <- 0 until WalkerIdx.COUNT) walkerStoreAdmit(i) := False
      walkerStoreAdmit.foreach(_.simPublic())

      val stOwnerIsCore = stOwner === U(DcPortOwner.CORE, 2 bits)
      val excStoreAdmit   = excActive && excStoreValid && stOwnerIsCore
      val coreLsStoreAdmit = Bool()          // driven at :263 (needs sq.io.drain)
      excStoreAdmit.simPublic(); coreLsStoreAdmit.simPublic()
```

- [ ] **Step 4: Qualify the store base drive and the SQ ready (`:263-265`, W24)**

```scala
    // ── W27 :263-264 row (NEW -- stated, no term needed on the PAYLOAD). Safe ONLY
    // because the exception override (:2067) and the walker legs are LATER drivers in
    // this same scope and last-assignment-wins overrides this base -- the property
    // :2020-2026's own comment already relies on. THE ORDERING IS LOAD-BEARING. Do not
    // reorder. The `valid` DOES take an owner term, because a base `valid` under a
    // foreign owner is a command, not just a payload.
    dcArb.coreLsStoreAdmit := sq.io.drain.valid && dcArb.stOwnerIsCore
    dcache.store.valid   := dcArb.coreLsStoreAdmit
    dcache.store.payload := sq.io.drain.payload
    // ── W24. :265 was UNCONDITIONAL. The codebase already knows this is a hazard and
    // already closes it for the exception case at :2068, with a comment saying why; that
    // protection does not extend to a walker. When a walker's U/M store owns the port,
    // `dcache.store.ready` pulses for the WALKER's command, the SQ advances sendPtr /
    // acceptedHalves for a store that was never sent, and that store is permanently lost.
    // :2068's existing `sq.io.drain.ready := False` stays exactly as it is -- the same
    // rule at the finer CORE-internal granularity, correctly ordered by
    // last-assignment-wins.
    sq.io.drain.ready := dcache.store.ready && dcArb.stOwnerIsCore
```

- [ ] **Step 5: Demux the store responses (`:270-271`, W13)**

```scala
    // ── W13 / W27 :270-271 row. MANDATORY, not prudent: StoreQueue.scala:518 already
    // carries `assert(!(io.drainAck && !drainBusy), "StoreQueue: drainAck arrived with no
    // accepted drain half")`, so a walker storeAck reaching the SQ is an immediate
    // simulation failure -- and in synthesis a spurious pop of the SQ head, which
    // DcachePlugin.scala:2038-2046 documents as a known-catastrophic failure class.
    sq.io.drainAck := dcache.storeAck && dcArb.stOwnerIsCore && !excStoreOutstanding
    sq.io.drainErr := dcache.storeErr && dcArb.stOwnerIsCore && !excStoreOutstanding
```
(`excStoreOutstanding` stays exactly where it is at `:268`; it is **not** moved into `dcArb`, so `:2073`/`:2076` keep referring to the same register.)

- [ ] **Step 6: Qualify the exception store block (`:2067-2078`)**

```scala
    excStoreReady := False
    // ── W27 :2067 row (NEW -- FIX). Header takes the ADMISSION predicate.
    when(dcArb.excStoreAdmit) {
      sq.io.drain.ready := False        // :2068 -- unchanged, see the W24 note at :265
      // ── W27 :2069 row. NO separate term. This line already sits INSIDE the header
      // above, with `excStoreReady := False` defaulted outside it, so it INHERITS
      // excStoreAdmit automatically. (Round 3 called this "the store-side exact twin of
      // W23" and prescribed an extra `&& excStoreAdmit`; that characterisation was wrong
      // and the extra term is redundant -- W23's real site at :2115 is unconditional at
      // FILE scope. Two rows describing sites inside one `when` block must not give
      // contradictory guidance.) What DOES survive from that note and is still
      // load-bearing: `storePort.ready` (DcachePlugin.scala:1865) no more references
      // `valid` than `loadCmdPort.ready` does; on stores `excStoreAdmit` IS the owner form
      // because store admission is drain-to-zero PLUS owner, with no in-flight-vs-granted
      // gap to fall through. That asymmetry with the load side is exactly why the round-3
      // C2 finding happened, and why every site cites its admission predicate.
      excStoreReady := dcache.store.ready
      dcache.store.valid   := True        // :2070-2071 -- inside the corrected header
      dcache.store.payload := excStorePayload
    }
    // ── W27 :2073 row (NEW -- FIX, defence in depth, with its safety argument stated).
    // In principle safe without a term: excStoreOutstanding set implies coreStOutstanding
    // =/= 0, and the store direction's surviving drain-to-zero forbids a hand-over in that
    // window -- BUT ONLY IF coreStOutstanding counts the exception sequencer's accepted
    // stores as well as the SQ drain's (it does; see dcArb). That is a cross-section
    // invariant holding up a silent-corruption site, so the row takes the term anyway:
    // one AND on a low-fanout control register, and the argument stops being load-bearing.
    when(dcache.storeAck && excStoreOutstanding && dcArb.stOwnerIsCore) {
      excStoreOutstanding := False
    }
    // ── W27 :2076 row (NEW -- FIX). Same class as W23's second site: an unqualified
    // `fire` under a foreign grant sets a CORE-side outstanding flag on someone else's
    // transaction, corrupting the very stBusy(CORE) the hand-over rule reads.
    when(dcache.store.fire && dcArb.excStoreAdmit) { excStoreOutstanding := True }
```

- [ ] **Step 7: Drive `coreStOutstanding` and `stOwner`'s update rule**

Add at the end of `logic`, beside Task 4's FIFO drives:

```scala
    // ── W28 clause (c), STORE half. Like ldOwner, stOwner is reloaded every cycle from a
    // pure function of the CURRENT-cycle arbiter decision and the outstanding counters --
    // there is no separate latch and no "latched on a grant and held" state. The HOLD term
    // is required here and absent on loads because the store direction has no ownership
    // FIFO: a walker's U/M store drops its own `valid` the moment it is accepted (it is
    // single-outstanding and waiting for the ack), so without the hold the grant would
    // revert to CORE and the walker's ack would be demuxed to the StoreQueue. That is
    // exactly the persistence W28 clause (c) says lives "in stBusy/walkStOutstanding" --
    // and it does: the hold reads nothing else.
    val stBusyAny = (dcArb.coreStOutstanding =/= 0) || dcArb.walkStOutstanding.reduce(_ || _)
    dcArb.stOwner := Mux(stBusyAny, dcArb.stOwner, dcArb.stGrantNext)   // stGrantNext: Task 7

    // coreStOutstanding counts CORE fires (SQ drain AND exception sequencer -- W6 makes
    // them one owner) minus CORE acks. Acks are attributable without a tag because the
    // store direction's drain-to-zero forbids two owners having stores in flight at once.
    val coreStFire = dcache.store.fire && (dcArb.coreLsStoreAdmit || dcArb.excStoreAdmit)
    val coreStAck  = dcache.storeAck && dcArb.stOwnerIsCore && (dcArb.coreStOutstanding =/= 0)
    when(coreStFire && !coreStAck) { dcArb.coreStOutstanding := dcArb.coreStOutstanding + 1 }
      .elsewhen(!coreStFire && coreStAck) { dcArb.coreStOutstanding := dcArb.coreStOutstanding - 1 }
```
**Until Task 7 exists, `dcArb.stGrantNext` does not.** Add it to `dcArb` now as `val stGrantNext = UInt(2 bits)` and drive it `U(DcPortOwner.CORE, 2 bits)` at this same site; Task 7 replaces that drive with the real arbiter.

- [ ] **Step 8: Add the store assertion**

Append inside Task 4's `GenerationFlags.simulation` block:

```scala
      // Section 4.3's third mandated assertion.
      assert(!(!dcArb.stOwnerIsCore && (dcArb.coreStOutstanding =/= 0)),
        "a walker held the D-cache store port with a core store still outstanding", FAILURE)
      assert(dcArb.coreStOutstanding <= 4,
        "coreStOutstanding exceeded DcachePlugin's S0+S1+S2+S3 capacity of 4", FAILURE)
```

- [ ] **Step 9: Run the tests to verify they pass**

```bash
free -g && ~/sbt/bin/sbt "testOnly m68k040.execute.WalkerDcachePortArbSpec m68k040.execute.WalkerSplitLoadRaceSpec"
make SBT=~/sbt/bin/sbt test-fast
python3 tools/socket/check_socket_netlist.py
```
Expected: all rows PASS; `test-fast` matches Task 1's baseline; FREEZE PASSES. Pay attention to `exception/ExceptionStoreDrainArbSpec` — it is the existing directed proof of the 2-source store arbitration and it must not move.

- [ ] **Step 10: Commit**

```bash
git add src/main/scala/m68k040/execute/LsEuPlugin.scala \
        src/test/scala/m68k040/execute/WalkerDcachePortArbSpec.scala
git commit -m "walker(W13/W24/W27 store side): stOwner, coreStOutstanding, ack demux

:265's sq.io.drain.ready was UNCONDITIONAL -- under a foreign store grant the SQ
advances sendPtr/acceptedHalves for a store that was never sent to the cache and
that store is permanently lost. Now gated by stOwnerIsCore, with :2068's existing
hold left in place (same rule, finer granularity, correctly ordered).

storeAck/storeErr demux by stOwner (W13) -- MANDATORY, since StoreQueue.scala:518
already asserts on a stray ack. :2067's presentation header takes excStoreAdmit;
:2069 inherits it (no separate term -- it sits inside that header); :2073 and
:2076 take their owner/admission terms.

W28 clause (c) store half: stOwner is reloaded every cycle from
Mux(stBusyAny, stOwner, stGrantNext) -- a pure function of the current-cycle
arbiter decision and the outstanding counters, which is where clause (c) says
store-side persistence lives. The hold is required here and absent on loads
because the store direction has no ownership FIFO.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Task 7: The walker legs — client hooks, the owner arbiter, admission, and the cacheMode stamp (W4, W5, W6, W15 LS-EU half, W28, W1, W2, W3, W13 walker half, W29 walker legs)

Everything the walkers need on `LsEuPlugin`'s side lands here. The walkers themselves still emit AXI (Tasks 14/15 convert them); the new hooks are driven **by the directed tests** in this task, which is what makes every W7/W8/W9/W23/W24/W27/W28/W29 row testable before the MMU is touched — and it is why the "foreign fire / foreign response" injections of Tasks 5 and 6 can now be re-pointed at a real leg.

**Files:**
- Modify: `src/main/scala/m68k040/execute/LsEuPlugin.scala` — `during setup` (new hooks), `logic`'s defaults, `dcArb` (the arbiter), the mux legs after `:765`
- Test: `src/test/scala/m68k040/execute/WalkerDcachePortArbSpec.scala` (extend), `src/test/scala/m68k040/execute/WalkerSplitLoadRaceSpec.scala` (re-point), `src/test/scala/m68k040/mmu/WalkerCacheModeSpec.scala` (create)

**Interfaces:**
- Consumes: everything from Tasks 4, 5, 6; `DLoadToken.WALK_ITLB`/`WALK_DTLB` from Task 3.
- Produces, relied on verbatim by Tasks 8, 9, 10, 14 and 15 — the plugin-level hooks, allocated in `during setup`, default-idle with `allowOverride` in `logic`, **byte for byte the pattern `umAccessRobId`/`umCommitValid`/`umFlush` already use** (`DtlbPlugin.scala:100-108`) so a standalone DUT that does not wire them still elaborates:

```scala
  // ── Walker D-cache client ports (W15). Indexed by WalkerIdx.{ITLB, DTLB}.
  // Driven by the ITLB/DTLB plugins, consumed by the mux; the response halves are
  // driven BY the mux and consumed by those plugins.
  var walkLoadCmdValid:  Vec[Bool] = null   // in  : the walker presents a descriptor read
  var walkLoadCmdPaddr:  Vec[UInt] = null   // in  : the descriptor's BYTE address
  var walkLoadCmdReady:  Vec[Bool] = null   // out : admitted this cycle
  var walkLoadRspValid:  Vec[Bool] = null   // out : THIS walker's response (tag-demuxed)
  var walkLoadRspData:   Vec[Bits] = null   // out : loadRsp.payload.data, 32 bits
  var walkLoadRspFault:  Vec[Bool] = null   // out : loadRsp.payload.fault
  var walkStoreValid:    Vec[Bool] = null   // in  : the U/M writeback presents
  var walkStorePaddr:    Vec[UInt] = null   // in  : 16-byte-aligned line address
  var walkStoreStrb:     Vec[Bits] = null   // in  : 16-bit one-hot byte strobe
  var walkStoreLineData: Vec[Bits] = null   // in  : 128-bit beat
  var walkStoreReady:    Vec[Bool] = null   // out : admitted this cycle
  var walkStoreAck:      Vec[Bool] = null   // out : THIS walker's ack (stOwner-demuxed)
  var quiesceHold:       Bool      = null   // in  : W19, from ExceptionUnit
```
  **Deliberately flat `Vec`s of primitives rather than `Stream[DLoadCmd]`/`Flow[DLoadRsp]`**, for the same reason `AxiDMerge`'s reset-vector owner is a flat port: the walker's transaction shape is fixed (`size = LONG`, identity-physical `vaddr := paddr`, `cacheMode` stamped by the mux, `token` a reserved constant), so the only fields it can vary are the address and the handshake. A full bundle would invite a walker to vary a field the mux is required to own. Also: `LsEuPlugin` gains constructor parameters `walkerAgeLimit: Int = 64` and `walkerWedgeLimit: BigInt = 65536` (the latter used in Task 11).

- [ ] **Step 1: Write the failing test**

Append spec §10.2's remaining `WalkerDcachePortArbSpec` rows, and create `WalkerCacheModeSpec`:

```scala
  // ── Task 7 rows: the walker legs ───────────────────────────────────────────────

  test("W7(a): drain-to-zero holds at the CORE-EXC boundary against a walker") {
    withDut("w7-exc-boundary", seed = 20) { dut =>
      dut.clockDomain.onSamplings {
        assert(!(dut.excLoadOutstanding.toBoolean && dut.ldOwnerFifoHoldsWalker.toBoolean),
          "a walker load was outstanding while the exception sequencer had one")
      }
      interleaveWalkerAndExceptionLoads(dut, cycles = 4000)
    }
  }

  test("W7(a'): CORE-LS and a walker may be CONCURRENTLY outstanding, and neither steals the other's response") {
    withDut("w7-fifo-concurrency", seed = 21) { dut =>
      var sawMixed = false
      var lastAlignedCount = 0; var lastRspPtr = 0
      dut.clockDomain.onSamplings {
        if (dut.ldOwnerFifoOccupancy.toInt >= 2 && dut.ldOwnerFifoHoldsWalker.toBoolean)
          sawMixed = true
        // A walker response must leave the aligned queue's bookkeeping untouched.
        if (dut.dcLoadRspValid.toBoolean && dut.ldRspTag.toInt >= 2 /*ITLB|DTLB*/) {
          assert(dut.alignedCount.toInt == lastAlignedCount,
            "a walker response decremented alignedCount")
          assert(dut.alignedRspPtr.toInt == lastRspPtr,
            "a walker response advanced alignedRspPtr")
        }
        lastAlignedCount = dut.alignedCount.toInt; lastRspPtr = dut.alignedRspPtr.toInt
      }
      runLoadStreamWithWalkerInterleave(dut, cycles = 4000)
      assert(sawMixed, "the stimulus never achieved CORE-LS + walker concurrency; the " +
        "test proves nothing about the FIFO")
    }
  }

  test("W8(b): a walker starved for WALKER_AGE_LIMIT cycles under continuous SQ drain DOES get the port") {
    // The direct answer to the scoping memo's "a busy store queue could postpone a walker
    // indefinitely". Uses the constructor-parameterised limit (4) so the test is short.
    withDut("w8-aging", seed = 22, ageLimit = 4) { dut =>
      startContinuousLoadStream(dut)
      requestWalkerLoad(dut, WalkerIdx.ITLB)
      val granted = waitUpTo(dut, cycles = 200) { dut.walkLoadCmdReady(0).toBoolean }
      assert(granted, "the walker never got the port under a continuous CORE-LS stream")
    }
  }

  test("W8(c): a forced walker takes exactly ONE command's worth of port, then base priority restores") {
    withDut("w8-no-chain-hold", seed = 23, ageLimit = 4) { dut =>
      startContinuousLoadStream(dut)
      requestWalkerLoadBackToBack(dut, WalkerIdx.ITLB, n = 3)
      val gaps = measureCoreLsFiresBetweenWalkerFires(dut)
      assert(gaps.forall(_ >= 1),
        s"a walker chain-held the port against CORE-LS (gaps = $gaps)")
    }
  }

  test("W9(d): ITLB and DTLB alternate") {
    withDut("w9-round-robin", seed = 24, ageLimit = 4) { dut =>
      requestBothWalkersContinuously(dut)
      val order = collectWalkerGrantOrder(dut, n = 12)
      assert(order.sliding(2).forall(p => p(0) != p(1)),
        s"the two walkers did not alternate: $order")
    }
  }

  test("W23(e): a walker loadCmd.ready in the same cycle as a presented exception load does not advance the exception FSM") {
    withDut("w23-e", seed = 25) { dut =>
      val st = arrangeWalkerReadyWithExceptionPresented(dut)
      dut.clockDomain.waitSampling(3)
      assert(!dut.excLoadCmdReady.toBoolean, "excLoadCmdReady pulsed with no command issued")
      assert(!dut.excLoadOutstanding.toBoolean, "excLoadOutstanding set on a walker's fire")
      assert(excFsmState(dut) == st, "the exception FSM advanced")
    }
  }

  test("W23(e'): with a WALKER IN THE FIFO the exception command is WITHHELD -- valid low, no fire, occupancy unchanged") {
    // THE round-3 C2 regression test, in its ROUND-6 strengthened form. The three original
    // assertions all read either excLoadCmdReady or the FSM's own state and NONE of them
    // reads loadCmd.valid/fire -- so as first written this row was blind to the failure it
    // is most often cited as covering: an UNQUALIFIED :2027 presentation header leaves
    // excLoadAdmit correct and LOW (so excLoadCmdReady stays low, so the FSM correctly does
    // not advance, so all three original assertions PASS) while still driving
    // dcache.loadCmd.valid := True into a HIGH ready, firing the port spuriously every
    // cycle and pushing bogus CORE_LS entries behind the walker's.
    withDut("w23-e-prime", seed = 26) { dut =>
      admitWalkerLoadAndWithholdResponse(dut)          // a walker entry sits in the FIFO
      forceLoadCmdPortReadyHigh(dut)                   // DcachePlugin holds ready high
      presentExceptionLoad(dut)
      val occ0 = dut.ldOwnerFifoOccupancy.toInt
      val st0  = excFsmState(dut)
      for (_ <- 0 until 20) {
        dut.clockDomain.waitSampling()
        assert(!dut.excLoadCmdReady.toBoolean, "excLoadCmdReady pulsed while withheld")
        assert(!dut.dcLoadCmdValid.toBoolean,  "loadCmd.valid HIGH while withheld")
        assert(!dut.dcLoadCmdFire.toBoolean,   "loadCmd fired while withheld")
        assert(dut.ldOwnerFifoOccupancy.toInt == occ0, "the FIFO gained an entry while withheld")
        assert(excFsmState(dut) == st0, "the exception FSM advanced while withheld")
      }
      releaseWalkerResponse(dut)
      assert(!excConsumedWalkerResponse(dut), "the exception path consumed the walker's response")
    }
  }

  test("W28(b)/W29: the pushed tag names the leg that FIRED, not the grant register") {
    // THE round-4 Critical scenario, both directions. Must be shown to FAIL against a
    // `push <= grantedOwner` form or the test is not pinning the bug.
    withDut("w29-c-r4-1", seed = 27) { dut =>
      // (i) grant lands on ITLB at N-1 with the fire WITHHELD (ready low via loadShadow),
      //     then at N an exception raises and the CORE-EXC leg fires.
      grantWalkerButWithholdFire(dut, WalkerIdx.ITLB)
      raiseExceptionLoadAndReleaseReady(dut)
      waitForLoadCmdFire(dut)
      assert(dut.ldPushTag.toInt == 1 /*CORE_EXC*/,
        s"pushed ${dut.ldPushTag.toInt}; the grant register still read ITLB but CORE-EXC fired")
      assert(deliveredToExceptionPath(dut), "the response did not reach the exception path")
      assert(!anyWalkerRspDemuxFired(dut), "a walker response demux fired")
      // (ii) the mirror: grant on CORE at N-1, a walker fires at N.
      grantCoreButWithholdFire(dut)
      admitWalkerAndReleaseReady(dut, WalkerIdx.DTLB)
      waitForLoadCmdFire(dut)
      assert(dut.ldPushTag.toInt == 3 /*DTLB*/, "the walker's own fire was tagged CORE")
      assert(!dut.alignedRspFire.toBoolean, "alignedRspFire consumed a walker response")
    }
  }

  test("W28(a): same-cycle owner coherence on the READ side") {
    // NECESSARY BUT NOT SUFFICIENT, and the plan says so out loud: the round-4 review
    // demonstrated this check passes trivially (it compares an admit-predicate-derived
    // value against itself) while the W29 bug above is live. The row above is its other half.
    withDut("w28-a", seed = 28) { dut =>
      dut.clockDomain.onSamplings {
        if (dut.dcLoadCmdFire.toBoolean)
          assert(presentedPayloadOwner(dut) == qualifiedOwner(dut),
            "the payload presented and the owner every qualification reads disagree")
      }
      walkOwnerThroughEveryTransition(dut)
    }
  }
```

Create `src/test/scala/m68k040/mmu/WalkerCacheModeSpec.scala`:

```scala
package m68k040.mmu

import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** W1 / W2 / W3 (walker/DcacheService passthrough design, section 4.1).
  *
  * Table-walk descriptor accesses use a FIXED ARCHITECTURAL cacheMode --
  * `CACR.DE ? WRITETHROUGH : INHIBITED`, NEVER COPYBACK -- stamped BY THE MUX inside
  * LsEuPlugin, not carried on WalkReq and not resolved inside the MMU plugins. This is not
  * a preference: WRITETHROUGH is what makes the coherency fix hold BY CONSTRUCTION in all
  * three line-residency states without depending on the eviction path, and INHIBITED does
  * not merely fail to help -- it STRUCTURALLY REINTRODUCES the bug (an INHIBITED store
  * never touches the array, DcachePlugin.scala:714-717).
  *
  * This spec ALSO carries the standing no-SoC-address-map rule's local obligation: the
  * mode must be identical for every descriptor address, because it is a policy constant
  * and not an address-derived property.
  *
  * Deliberately UNTAGGED so `test-fast` runs it. */
class WalkerCacheModeSpec extends AnyFunSuite {

  test("W1: CACR.DE = 1 => every walker command carries WRITETHROUGH") {
    withWalkerDut("de1", seed = 1, dcacheEnabled = true) { dut =>
      val modes = collectWalkerCmdCacheModes(dut, reads = 6, writes = 3)
      assert(modes.nonEmpty, "the stimulus issued no walker command")
      assert(modes.forall(_ == CacheMode.WRITETHROUGH.position),
        s"walker commands carried $modes, expected all WRITETHROUGH")
    }
  }

  test("W1: CACR.DE = 0 => every walker command carries INHIBITED") {
    withWalkerDut("de0", seed = 2, dcacheEnabled = false) { dut =>
      val modes = collectWalkerCmdCacheModes(dut, reads = 6, writes = 3)
      assert(modes.forall(_ == CacheMode.INHIBITED.position),
        s"walker commands carried $modes, expected all INHIBITED")
    }
  }

  test("W3: the read and write halves ALWAYS agree, including across a live DE change") {
    // If the read allocated the line (WRITETHROUGH) while the write bypassed the array
    // (INHIBITED), we would have manufactured the P5.7 mismatch inside the MMU.
    withWalkerDut("de-agree", seed = 3, dcacheEnabled = true) { dut =>
      val a = collectWalkerCmdCacheModes(dut, reads = 3, writes = 2)
      setDcacheEnabled(dut, false)
      val b = collectWalkerCmdCacheModes(dut, reads = 3, writes = 2)
      assert(a.distinct.size == 1 && b.distinct.size == 1 && a.head != b.head,
        s"the halves disagreed or DE had no effect: a=$a b=$b")
    }
  }

  test("W1: COPYBACK is never emitted for a walker command, at any DE setting or address") {
    for ((de, seed) <- Seq((true, 4), (false, 5)))
      withWalkerDut(s"no-copyback-$de", seed = seed, dcacheEnabled = de) { dut =>
        val modes = collectWalkerCmdCacheModesOverAddressSweep(dut, addrs = 32)
        assert(!modes.contains(CacheMode.COPYBACK.position),
          "a walker command carried COPYBACK")
        assert(modes.distinct.size == 1,
          "the walker cacheMode varied with the descriptor ADDRESS -- it is a FIXED policy " +
          "constant and must never be derived from an address range (standing rule)")
      }
  }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
free -g && ~/sbt/bin/sbt "testOnly m68k040.execute.WalkerDcachePortArbSpec m68k040.mmu.WalkerCacheModeSpec"
```
Expected: compilation FAILS — `walkLoadCmdValid is not a member of LsEuPlugin`.

- [ ] **Step 3: Allocate the hooks and the constructor parameters**

Add the two constructor parameters to `class LsEuPlugin` (**not** `Global.scala` keys):

```scala
class LsEuPlugin(/* existing params unchanged */,
                 /** W8. Cycles a walker may go un-granted before its `force` bit outranks
                   * CORE-LS. Constructor-parameterised ONLY so directed tests can use a
                   * short value like 4; PRODUCTION IS THE CONSTANT 64. Mirrors
                   * AxiDMerge's grantTimeout parameterisation rationale verbatim
                   * (AxiDMerge.scala:88-96). No finite value can break the starvation
                   * bound -- the bound is stated IN TERMS of this limit -- so 64 is SAFE,
                   * merely possibly non-optimal. Task 19 re-derives it or records that 64
                   * is retained on purpose. */
                 val walkerAgeLimit: Int = 64,
                 /** W26. See Task 10 for the derivation of this bound. */
                 val walkerWedgeLimit: BigInt = 65536) extends FiberPlugin ... {
```
In `during setup`, allocate every hook listed under **Interfaces**, e.g.:

```scala
    walkLoadCmdValid  = Vec.fill(WalkerIdx.COUNT)(Bool())
    walkLoadCmdPaddr  = Vec.fill(WalkerIdx.COUNT)(UInt(32 bits))
    walkLoadCmdReady  = Vec.fill(WalkerIdx.COUNT)(Bool())
    walkLoadRspValid  = Vec.fill(WalkerIdx.COUNT)(Bool())
    walkLoadRspData   = Vec.fill(WalkerIdx.COUNT)(Bits(32 bits))
    walkLoadRspFault  = Vec.fill(WalkerIdx.COUNT)(Bool())
    walkStoreValid    = Vec.fill(WalkerIdx.COUNT)(Bool())
    walkStorePaddr    = Vec.fill(WalkerIdx.COUNT)(UInt(32 bits))
    walkStoreStrb     = Vec.fill(WalkerIdx.COUNT)(Bits(16 bits))
    walkStoreLineData = Vec.fill(WalkerIdx.COUNT)(Bits(128 bits))
    walkStoreReady    = Vec.fill(WalkerIdx.COUNT)(Bool())
    walkStoreAck      = Vec.fill(WalkerIdx.COUNT)(Bool())
    quiesceHold       = Bool()
```
In `logic`, beside the existing exc defaults at `:224-240`, default the **inputs** idle with `allowOverride` (the outputs are driven by the mux and must not be defaulted):

```scala
    // W15: default-idle inputs so a standalone DUT with no MMU plugins wired elaborates,
    // byte for byte the pattern umAccessRobId/umCommitValid/umFlush already use.
    for (i <- 0 until WalkerIdx.COUNT) {
      walkLoadCmdValid(i).allowOverride;  walkLoadCmdValid(i)  := False
      walkLoadCmdPaddr(i).allowOverride;  walkLoadCmdPaddr(i)  := U(0, 32 bits)
      walkStoreValid(i).allowOverride;    walkStoreValid(i)    := False
      walkStorePaddr(i).allowOverride;    walkStorePaddr(i)    := U(0, 32 bits)
      walkStoreStrb(i).allowOverride;     walkStoreStrb(i)     := B(0, 16 bits)
      walkStoreLineData(i).allowOverride; walkStoreLineData(i) := B(0, 128 bits)
    }
    quiesceHold.allowOverride; quiesceHold := False
    walkLoadCmdValid.foreach(_.simPublic()); walkLoadCmdReady.foreach(_.simPublic())
    walkStoreValid.foreach(_.simPublic());   walkStoreReady.foreach(_.simPublic())
    walkLoadRspValid.foreach(_.simPublic()); walkStoreAck.foreach(_.simPublic())
```

- [ ] **Step 4: Build the per-direction arbiter inside `dcArb`**

```scala
      // ── W8 / W9: base priority is CORE-EXC > CORE-LS > {ITLB, DTLB} (round-robin),
      // i.e. TODAY'S BEHAVIOUR EXACTLY whenever no walker is pending. Fairness comes from
      // an aging counter: after `walkerAgeLimit` un-granted cycles a walker's `force` bit
      // outranks CORE-LS. `force` NEVER outranks CORE-EXC's own PRESENTED command -- but
      // it DOES take the port on cycles the exception sequencer is not presenting one,
      // which is REQUIRED, not optional: since Task 11 the exception sequencer performs
      // genuinely virtual FSAVE/FRESTORE accesses through the DTLB, so a DTLB miss raised
      // BY THE EXCEPTION SEQUENCER ITSELF must be able to run its walk while excActive is
      // high. A blanket "no walkers during excActive" rule would DEADLOCK.
      def walkerOwnerCode(i: Int) = if (i == WalkerIdx.ITLB) DcPortOwner.ITLB else DcPortOwner.DTLB

      val ldAge   = Vec.fill(WalkerIdx.COUNT)(Reg(UInt(log2Up(walkerAgeLimit + 1) bits)) init 0)
      val ldForce = Vec(Bool(), WalkerIdx.COUNT)
      val stAge   = Vec.fill(WalkerIdx.COUNT)(Reg(UInt(log2Up(walkerAgeLimit + 1) bits)) init 0)
      val stForce = Vec(Bool(), WalkerIdx.COUNT)
      // W9: with two requesters, round-robin and "the one that didn't go last" are the
      // same thing, so this is ONE FLOP per direction, not a rotation base.
      val ldRr = RegInit(False)
      val stRr = RegInit(False)
      val ldGrantNext = UInt(2 bits)
      val stGrantNext = UInt(2 bits)
```
And, at the arbitration site near the mux (so the walker request valids are in scope):

```scala
    // ── Aging (W8). Resets on GRANT or on the request genuinely going away -- the
    // reset-on-withdrawal clause is what makes the starvation bound's step 2 true.
    for (i <- 0 until WalkerIdx.COUNT) {
      val ldGranted = dcArb.ldOwner === U(dcArb.walkerOwnerCode(i), 2 bits)
      when(!walkLoadCmdValid(i) || ldGranted) { dcArb.ldAge(i) := 0 }
        .elsewhen(dcArb.ldAge(i) =/= walkerAgeLimit) { dcArb.ldAge(i) := dcArb.ldAge(i) + 1 }
      dcArb.ldForce(i) := dcArb.ldAge(i) === walkerAgeLimit
      val stGranted = dcArb.stOwner === U(dcArb.walkerOwnerCode(i), 2 bits)
      when(!walkStoreValid(i) || stGranted) { dcArb.stAge(i) := 0 }
        .elsewhen(dcArb.stAge(i) =/= walkerAgeLimit) { dcArb.stAge(i) := dcArb.stAge(i) + 1 }
      dcArb.stForce(i) := dcArb.stAge(i) === walkerAgeLimit
    }

    // ── Grant decision, LOAD direction. Registered-grant shape (W28 clause (a)):
    // `ldOwner := ldGrantNext` UNCONDITIONALLY every cycle.
    val ldWalkFirst  = Mux(dcArb.ldRr, U(WalkerIdx.DTLB, 1 bits), U(WalkerIdx.ITLB, 1 bits))
    val ldWalkSecond = ~ldWalkFirst
    val ldWalkPick   = Mux(walkLoadCmdValid(ldWalkFirst), ldWalkFirst, ldWalkSecond)
    val ldWalkAny    = walkLoadCmdValid.reduce(_ || _)
    val ldWalkForced = dcArb.ldForce(ldWalkPick) && walkLoadCmdValid(ldWalkPick)
    val excLoadPresent = excActive && excLoadCmdValid
    dcArb.ldGrantNext := U(DcPortOwner.CORE, 2 bits)
    when(ldWalkAny && walkLoadCmdValid(ldWalkPick) && !excLoadPresent &&
         (!dcArb.coreLsLoadReq || ldWalkForced)) {
      dcArb.ldGrantNext := Mux(ldWalkPick === U(WalkerIdx.ITLB, 1 bits),
                               U(DcPortOwner.ITLB, 2 bits), U(DcPortOwner.DTLB, 2 bits))
    }
    dcArb.ldOwner := dcArb.ldGrantNext

    // ── Grant decision, STORE direction. Same shape, plus the surviving drain-to-zero
    // (`coreStOutstanding === 0`); the HOLD lives in Task 6's `stOwner :=` assignment.
    val stWalkFirst  = Mux(dcArb.stRr, U(WalkerIdx.DTLB, 1 bits), U(WalkerIdx.ITLB, 1 bits))
    val stWalkSecond = ~stWalkFirst
    val stWalkPick   = Mux(walkStoreValid(stWalkFirst), stWalkFirst, stWalkSecond)
    val stWalkAny    = walkStoreValid.reduce(_ || _)
    val stWalkForced = dcArb.stForce(stWalkPick) && walkStoreValid(stWalkPick)
    val excStorePresent = excActive && excStoreValid
    dcArb.stGrantNext := U(DcPortOwner.CORE, 2 bits)
    when(stWalkAny && walkStoreValid(stWalkPick) && !excStorePresent &&
         (dcArb.coreStOutstanding === 0) && (!sq.io.drain.valid || stWalkForced)) {
      dcArb.stGrantNext := Mux(stWalkPick === U(WalkerIdx.ITLB, 1 bits),
                               U(DcPortOwner.ITLB, 2 bits), U(DcPortOwner.DTLB, 2 bits))
    }
    // W9's flip fires on a walker's ACTUAL command fire -- the grant being USED -- which
    // is the only once-per-grant event that is well defined. Flipping on the grant itself
    // would oscillate every cycle the owner register names a walker.
    when(dcache.loadCmd.fire && dcArb.walkerLoadAdmit.reduce(_ || _)) { dcArb.ldRr := !dcArb.ldRr }
    when(dcache.store.fire   && dcArb.walkerStoreAdmit.reduce(_ || _)) { dcArb.stRr := !dcArb.stRr }
```

**Implementation refinement, recorded rather than silently made:** spec §4.2.3 writes `walkerLoadAdmit(i) = grant(i) && !ldBusyExc && !quiesceHold && !ldOwnerFifo.full`. Under the registered-grant shape `ldOwner` is one cycle behind the request set, so a walker that withdraws between the grant edge and the next cycle would leave `admit` true with `valid` low — presenting a garbage payload. The admit predicates below therefore AND in **the walker's own `valid`**. This is **strictly stronger** than the spec's formula (which presupposes a granted walker is a requesting one), and it preserves pairwise exclusivity unchanged: `ldOwner` is single-valued, so at most one walker's term can be true.

- [ ] **Step 5: Replace the walker admit stubs with the real predicates**

In `dcArb`, replace Task 4's / Task 6's `:= False` loops:

```scala
      for (i <- 0 until WalkerIdx.COUNT) {
        walkerLoadAdmit(i) := (ldOwner === U(walkerOwnerCode(i), 2 bits)) &&
                              walkLoadCmdValid(i) &&
                              !ldBusyExc && !quiesceHold && !ldOwnerFifoFull
        walkerStoreAdmit(i) := (stOwner === U(walkerOwnerCode(i), 2 bits)) &&
                               walkStoreValid(i) && !quiesceHold
      }
```
(These move to the arbitration site if `walkLoadCmdValid` is not yet in scope inside `dcArb`; keep the declarations in `dcArb` and drive them here.)

- [ ] **Step 6: Add the walker mux legs — the ONLY place the cacheMode is stamped (W1/W2/W3)**

Immediately **after** the CORE-LS payload base at `:757-765` and **before** the exception block at `:2027`, so last-assignment-wins gives the priority chain CORE-LS base → walker legs → CORE-EXC last:

```scala
    // ══ Walker legs (W4). LOWEST-priority legs of the last-assignment-wins chain, so a
    // priority-encoded select puts them at the FAR end of the tree rather than in series
    // with the CORE path (section 5 item (1)). Placed HERE -- after the CORE-LS base,
    // before the exception override -- and that ordering is load-bearing (W27's :757-764
    // and :263-264 rows).
    //
    // W2: the cacheMode is stamped BY THE MUX, here, once, for BOTH halves. WalkReq gains
    // no field and the MMU plugins gain no CacheControlService dependency. That is what
    // makes W3 ("one mode for both halves") true BY CONSTRUCTION rather than by
    // discipline -- it is structurally impossible for the two halves to disagree.
    //
    // W1: `CACR.DE ? WRITETHROUGH : INHIBITED`, NEVER COPYBACK. Textually the same
    // expression as :2061-2063's exception-path fold, deliberately, so a future change to
    // the DE policy cannot drift between the two sites. It is a FIXED ARCHITECTURAL POLICY
    // CONSTANT and is never derived from an address: the descriptor reads target the page
    // table's OWN physical pages and this walker IS the attribute-resolution mechanism, so
    // there is no page attribute in existence for anyone to consult. (Standing rule:
    // never reason about an access from an assumed SoC decode map.)
    val walkCacheMode = Mux(cacheCtrl.map(_.dcacheEnabled).getOrElse(False),
                            m68k040.cache.CacheMode.WRITETHROUGH,
                            m68k040.cache.CacheMode.INHIBITED)
    for (i <- 0 until WalkerIdx.COUNT) {
      when(dcArb.walkerLoadAdmit(i)) {
        dcache.loadCmd.valid := True
        // W16: `paddr` is the descriptor's BYTE address, NOT the line-aligned one the old
        // AXI AR used -- the byte lane is what selects the descriptor word. And the lane
        // comes from the VIRTUAL address (`cmdOff = cmdVaddr(offBits-1 downto 0)`,
        // DcachePlugin.scala:321-324), so THE LOAD-BEARING REQUIREMENT IS THAT `vaddr`
        // CARRIES THE DESCRIPTOR'S BYTE OFFSET. `paddr` carries the same value because
        // the walker is an identity-physical client (the same convention :2029-2035 uses
        // for the exception sequencer), not because the lane needs it.
        dcache.loadCmd.payload.vaddr := walkLoadCmdPaddr(i)
        dcache.loadCmd.payload.paddr := walkLoadCmdPaddr(i)
        dcache.loadCmd.payload.size  := m68k040.isa.Size.LONG
        dcache.loadCmd.payload.cacheMode := walkCacheMode
        // W25: a RESERVED value, never a don't-care.
        dcache.loadCmd.payload.token := U(
          if (i == WalkerIdx.ITLB) m68k040.cache.DLoadToken.WALK_ITLB
          else                     m68k040.cache.DLoadToken.WALK_DTLB,
          m68k040.cache.DLoadToken.Width bits)
      }
      when(dcArb.walkerStoreAdmit(i)) {
        sq.io.drain.ready := False        // the same hold :2068 applies for the exception
        dcache.store.valid := True
        // W17: emitted as an explicit-strobe DStoreCmd. `data`/`size` are genuinely
        // ignored under useStrb (both merge sites select on it and the AXI beat is built
        // from stS3MergeData/stS3MergeStrb); LONG is assigned as a legal, obviously-safe
        // value rather than left unassigned. `precise = False` is correct and deliberate:
        // `precise` routes a bus error to the SQ's precise-fault path and a U/M writeback
        // has NO owning SQ entry, so it lands on the async diagnostic channel instead --
        // which is the right place for it.
        dcache.store.payload.paddr     := walkStorePaddr(i)
        dcache.store.payload.useStrb   := True
        dcache.store.payload.strb      := walkStoreStrb(i)
        dcache.store.payload.lineData  := walkStoreLineData(i)
        dcache.store.payload.data      := B(0, 32 bits)
        dcache.store.payload.size      := m68k040.isa.Size.LONG
        dcache.store.payload.cacheMode := walkCacheMode
        dcache.store.payload.precise   := False
      }
    }
    // ── Handshake and response returns to the walkers (W13 / W14 / W29).
    for (i <- 0 until WalkerIdx.COUNT) {
      walkLoadCmdReady(i) := dcache.loadCmd.ready && dcArb.walkerLoadAdmit(i)
      walkStoreReady(i)   := dcache.store.ready   && dcArb.walkerStoreAdmit(i)
      // The walker sees a `valid` ONLY for its own response, so TableWalker itself needs
      // NO owner awareness at all. Routing is by W7's FIFO head -- INSIDE LsEuPlugin --
      // so `exc.dcLoadRsp` (FullCoreSynth.scala:350-351) and every DUT that replicates it
      // stay byte-for-byte untouched (W14).
      val myTag = if (i == WalkerIdx.ITLB) LdRspTag.ITLB else LdRspTag.DTLB
      walkLoadRspValid(i) := dcache.loadRsp.valid && (dcArb.ldRspTag === U(myTag, 2 bits))
      walkLoadRspData(i)  := dcache.loadRsp.payload.data
      walkLoadRspFault(i) := dcache.loadRsp.payload.fault
      walkStoreAck(i)     := dcache.storeAck &&
                             (dcArb.stOwner === U(dcArb.walkerOwnerCode(i), 2 bits))
    }
    // Walker store outstanding: one bit each, set on its own fire, cleared on its own ack.
    for (i <- 0 until WalkerIdx.COUNT) {
      when(dcache.store.fire && dcArb.walkerStoreAdmit(i)) { dcArb.walkStOutstanding(i) := True }
      when(walkStoreAck(i))                                { dcArb.walkStOutstanding(i) := False }
    }
```

- [ ] **Step 7: Re-point Tasks 5's and 6's injected "foreign" traffic at a real walker leg**

`WalkerSplitLoadRaceSpec`'s `injectForeignFire` / `injectForeignLoadRsp` and `WalkerDcachePortArbSpec`'s `forceForeignStoreOwnership` / `forceForeignStoreAccepted` are rewritten to drive `walkLoadCmdValid(WalkerIdx.ITLB)` / `walkStoreValid(WalkerIdx.DTLB)` instead of a test-only forced fire. **The assertions do not change** — if any of them now fails, that is a finding about Task 5/6's fix, not a test bug.

- [ ] **Step 8: Re-derive W27's sweep against the then-current file (mandatory gate for this task)**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
grep -n "dcache\.\(loadCmd\|store\|loadRsp\|storeAck\|storeErr\|loadProbe\)" \
  src/main/scala/m68k040/execute/LsEuPlugin.scala
```
Read **every** hit in context and confirm each is one of: a row this plan implemented with a term; a row this plan recorded as a justified no-op with the justification **written in the source**; or a `dcache.*` reference this plan itself introduced (the FIFO push, the FIFO pop, W30's clear, the walker legs above) **which already carries its disposition in a comment**. Spec §4.2.7's rule, restated so it does not have to be rediscovered: *a `dcache.*` handshake reference introduced by this design is a row in the table on the same terms as one already in the file.* If any hit has neither a term nor a written justification, **this task is not done**. Record the final hit count and the disposition of each new row in the progress ledger.

Also confirm the two non-sites the table names are still non-sites: `dcache.loadBusy` appears **only** in the file's header doc comment, and `host[DcacheService]` at `:197` is the service handle itself.

- [ ] **Step 9: Run the tests to verify they pass**

```bash
free -g && ~/sbt/bin/sbt "testOnly m68k040.execute.* m68k040.mmu.WalkerCacheModeSpec"
make SBT=~/sbt/bin/sbt test-fast
python3 tools/socket/check_socket_netlist.py
```
Expected: every `WalkerDcachePortArbSpec`, `WalkerSplitLoadRaceSpec` and `WalkerCacheModeSpec` row PASSES; `test-fast` matches Task 1's baseline (the walker hooks are idle in every existing DUT, so this task is behaviour-neutral for them); FREEZE PASSES (the hooks are plugin-level `var`s, not ports).

- [ ] **Step 10: Commit**

```bash
git add src/main/scala/m68k040/execute/LsEuPlugin.scala \
        src/test/scala/m68k040/execute/WalkerDcachePortArbSpec.scala \
        src/test/scala/m68k040/execute/WalkerSplitLoadRaceSpec.scala \
        src/test/scala/m68k040/mmu/WalkerCacheModeSpec.scala
git commit -m "walker(W4-W9/W15/W28/W1-W3): the walker legs of the LsEuPlugin port mux

Extends the existing 2-source exception override mux to FOUR sources. Two
registered owner codes (W28 clause (a): registered-grant shape, so grant(i) IS
ldOwner === i and walkerOwnsLoad IS ldOwner =/= CORE -- identical value, identical
cycle, by construction), per-walker aging counters with a constructor-
parameterised walkerAgeLimit (production 64), and a one-flop round-robin per
direction flipped on a walker's actual command FIRE.

The walker legs sit AFTER the CORE-LS base and BEFORE the exception override, so
last-assignment-wins yields the priority chain CORE-EXC > CORE-LS > walkers; that
ordering is recorded in the source as load-bearing.

W1/W2/W3: the cacheMode is stamped ONCE, by the mux, for BOTH halves --
CACR.DE ? WRITETHROUGH : INHIBITED, never COPYBACK. WalkReq gains no field and
the MMU plugins gain no service dependency, which makes "one mode for both
halves" true by construction rather than by discipline. It is a fixed
architectural policy constant, never derived from an address.

W15: twelve plugin-level `var` hooks per the umCommitValid/umFlush idiom,
default-idle with allowOverride so a standalone DUT still elaborates.
W14 preserved intact: response routing is by the FIFO head INSIDE LsEuPlugin, so
exc.dcLoadRsp and every DUT that replicates it stay byte-for-byte untouched.

Refinement recorded, not silently made: walkerLoadAdmit/walkerStoreAdmit AND in
the walker's own `valid`, strictly stronger than the spec's grant-only formula,
because a registered grant is one cycle behind the request set.

DcachePlugin.scala still untouched. M68kFullCoreSynth port surface unchanged.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Task 8: The early-probe deadlock and the token-collision half (W11 cancel half, W25 use)

**This is a real deadlock that a naive implementation of this design walks straight into, and it is not in the scoping memo.** `DcachePlugin.scala:981` gates load-command admission on `(!earlyProbeTokenPresent || earlyProbeOwnsCmd)`. When the mux hands the load port to a walker: (1) CORE's `loadCmd.valid` drops; (2) `loadProbePort.ready` (`:945-951`) is gated on `(!loadCmdPort.valid || useEarlyProbe)`, which that drop **satisfies**, so the LS EU keeps launching probes; (3) all 4 probe slots fill with tokens whose matching load commands the mux will never let through; (4) the walker's command matches none of them, so `loadCmdPort.ready` is **false forever**. Neither side can advance.

The fix is a **correctness requirement, not an optimisation**, and it reuses machinery that already exists for exactly this hand-over.

**Files:**
- Modify: `src/main/scala/m68k040/execute/LsEuPlugin.scala:863`
- Test: `src/test/scala/m68k040/mmu/WalkerProbeDeadlockSpec.scala` (create)

**Interfaces:**
- Consumes: `dcArb.walkerOwnsLoad` (Task 4), the walker hooks (Task 7).
- Produces: nothing new by name; `probeCancelAll` gains one OR term.

- [ ] **Step 1: Write the failing test**

Create `src/test/scala/m68k040/mmu/WalkerProbeDeadlockSpec.scala`:

```scala
package m68k040.mmu

import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** W11 and W25 (walker/DcacheService passthrough design, sections 4.2.6 and 4.5).
  *
  * Deliberately UNTAGGED so `test-fast` runs it. */
class WalkerProbeDeadlockSpec extends AnyFunSuite {

  test("W11: an ITLB walk makes forward progress with all 4 early-probe slots resident") {
    // MUST BE SHOWN TO HANG without probeCancelAll's walkerOwnsLoad term. Note the ITLB
    // walker specifically: while a DTLB walk is in flight DtlbPlugin.scala:154 holds
    // _req.ready low, so `dcache.loadProbe.valid := probeWanted && xlate.req.ready` is
    // already false and the deadlock is not reachable that way. The fix covers both
    // regardless -- relying on that asymmetry would be exactly the kind of implicit
    // invariant this codebase's review history keeps catching.
    withProbeDut("itlb-probe-deadlock", seed = 1) { dut =>
      fillAllFourProbeSlots(dut)
      assert(dut.earlyProbeTokenPresent.toBoolean, "the stimulus did not fill the probe slots")
      requestWalkerLoad(dut, WalkerIdx.ITLB)
      val progressed = waitUpTo(dut, cycles = 500) { dut.walkLoadCmdReady(0).toBoolean }
      assert(progressed, "DEADLOCK: the walker's command was never admitted with 4 " +
        "resident probe tokens -- probeCancelAll is missing its walkerOwnsLoad term")
    }
  }

  test("W11: handing the port to a walker cancels every resident probe and suppresses new launches") {
    withProbeDut("probe-cancel", seed = 2) { dut =>
      fillAllFourProbeSlots(dut)
      requestWalkerLoad(dut, WalkerIdx.DTLB)
      waitFor(dut) { dut.walkerOwnsLoad.toBoolean }
      assert(dut.probeCancelAll.toBoolean, "probeCancelAll did not assert on a walker grant")
      // Suppression is the OTHER half, folded into normalReqArm several levels upstream.
      for (_ <- 0 until 8) {
        dut.clockDomain.waitSampling()
        if (dut.walkerOwnsLoad.toBoolean)
          assert(!dut.probeWanted.toBoolean, "a new probe launched while a walker owned the port")
      }
    }
  }

  test("W25: a walker command whose vaddr equals a live probe's vaddr is answered from MEMORY, not the probe") {
    // Identity-mapped supervisor page tables are the ORDINARY case in this project's own
    // MMU tests, so a walker's PHYSICAL descriptor address can equal a live LS-EU probe's
    // VIRTUAL address exactly. With a don't-care token that makes earlyProbeOwnsCmd true
    // for the WALKER's command and answers it from a stale probe snapshot, extracted at the
    // PROBE's size and offset. MUST be shown to return the WRONG descriptor with a
    // don't-care token.
    withProbeDut("w25-collision", seed = 3) { dut =>
      val descAddr = 0x00021004L
      plantDescriptor(dut, descAddr, value = 0x11223344)
      plantStaleProbeAt(dut, vaddr = descAddr, staleData = 0xAAAAAAAA)
      requestWalkerLoadAt(dut, WalkerIdx.ITLB, descAddr)
      val got = awaitWalkerLoadData(dut, WalkerIdx.ITLB)
      assert(got == 0x11223344L,
        f"the walker was answered 0x$got%08x from a stale probe snapshot, not 0x11223344 " +
        "from the array -- its token collided with a resident early-probe entry")
    }
  }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
free -g && ~/sbt/bin/sbt "testOnly m68k040.mmu.WalkerProbeDeadlockSpec"
```
Expected: the first test **times out / hangs** (the deadlock, which is the point); the second FAILS on `probeCancelAll`. The third should already PASS because Task 3 + Task 7 gave the walker a reserved token — **re-run it with the token temporarily forced to a colliding LS value to confirm it can fail**, then restore. A test that cannot fail proves nothing.

- [ ] **Step 3: Add the OR term (`:863`)**

```scala
    // ── W11. An ADDITION, not a substitution -- and section 5 counts it as one of the two
    // real new costs in this design. One 3-input OR from registers instead of a 2-input
    // OR, and it stays OFF the protected rdSet/rdEn net BY CONSTRUCTION: probeCancelAll
    // feeds only the earlyProbeValids control registers (:1976-1982), whereas
    // loadProbePort.fire is what drives rdSet/rdEn (:950-956). Choosing the CANCEL route
    // over gating loadProbe.valid directly was made for exactly this reason; both work
    // functionally, only one stays off the protected net.
    //
    // Cancelling a probe is functionally free: DcacheTypes.scala:12-18 and :36-45 state
    // that an unusable/absent probe qualification simply makes the later resolved DLoadCmd
    // use the ordinary path. It is a PERFORMANCE event, not a correctness one -- and
    // DcachePlugin.scala:1973-1983's own comment confirms the same mechanism is already
    // load-bearing for the analogous maintenance-quiesce problem.
    val probeCancelAll = sqFlushSig || excActive || dcArb.walkerOwnsLoad
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
free -g && ~/sbt/bin/sbt "testOnly m68k040.mmu.WalkerProbeDeadlockSpec m68k040.execute.*"
make SBT=~/sbt/bin/sbt test-fast
python3 tools/socket/check_socket_netlist.py
```
Expected: all three PASS; `test-fast` matches Task 1's baseline; FREEZE PASSES. **Report that this task adds a real logic level** (`probeCancelAll` 2-input OR → 3-input OR) so Task 11's synth checkpoint looks for it specifically — spec §5 names it as one of only two genuine additions.

- [ ] **Step 5: Record the honest cost of the FIFO/probe trade in the ledger**

Spec §4.2.3 caveat (b) requires this to be stated in both directions, not as "strictly better": **latency genuinely improves** (a walker no longer waits for a live load stream to empty), and **probe-destruction frequency genuinely rises** (under drain-to-zero a walker was only ever admitted into a *quiet* port, so `probeCancelAll` had little resident to destroy; under the FIFO the walker interleaves into a *live* stream, so each of a walk's up to three descriptor reads can individually destroy up to 4 resident early probes). Record in the ledger that **§10.3's IPC evidence is what settles whether the trade is net positive on real workloads** — Task 19 measures it.

- [ ] **Step 6: Commit**

```bash
git add src/main/scala/m68k040/execute/LsEuPlugin.scala \
        src/test/scala/m68k040/mmu/WalkerProbeDeadlockSpec.scala
git commit -m "walker(W11/W25): close the early-probe deadlock a naive port hand-over hits

probeCancelAll = sqFlushSig || excActive || walkerOwnsLoad. Without the third
term: handing the load port to a walker drops CORE's loadCmd.valid, which
SATISFIES loadProbePort.ready's (!loadCmdPort.valid || useEarlyProbe) gate, so
the LS EU keeps launching probes until all 4 slots hold tokens whose matching
commands the mux will never admit -- and the walker's command matches none of
them, so loadCmdPort.ready is false forever. A real deadlock, not in the scoping
memo, reachable via the ITLB walker.

This is an ADDITION, not a substitution (one 3-input OR from registers), counted
as such in the design's section 5 cost accounting. It stays off the protected
rdSet/rdEn net by construction: probeCancelAll feeds only earlyProbeValids,
while loadProbePort.fire is what drives rdSet/rdEn.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Task 9: `quiesceHold` — the `S_DRAIN`/`S_APPLY` maintenance-quiesce hole (W19 / M1)

`ExceptionUnit.scala:1361-1381` carries a **written deadlock analysis** whose final clause is *"With the LS EU flushed, nothing re-arms them."* After this change that sentence is **false**: a walker can re-arm them. `dcIdleForMaint` (`DcachePlugin.scala:1602-1608`) requires `!ldS1Valid && !ldS2Valid && !loadShadowValid && !earlyProbeValid && … && (storeOutstanding === 0)`, and a walker issuing descriptor reads keeps those set — so `S_DRAIN`'s `sqDrained && dcQuiesced` wait may never be observed.

**Files:**
- Modify: `src/main/scala/m68k040/exception/ExceptionUnit.scala` (the `:1361-1381` comment; a new `quiesceHold` export beside the `dbgFsmIs*` block at `:2335-2341`)
- Modify: `src/main/scala/m68k040/top/FullCoreSynth.scala` (one wiring line beside `exc.maintDoneIn := dc.maintDone` at `:429`)
- Test: `src/test/scala/m68k040/execute/WalkerDcachePortArbSpec.scala` (extend)

**Interfaces:**
- Consumes: `LsEuPlugin.quiesceHold` (the hook, Task 7).
- Produces, relied on by Task 15's DUT wiring:
  - `ExceptionUnit.quiesceHold: Bool` — `True` exactly while the FSM is in `S_DRAIN` or `S_APPLY`; `simPublic`.

- [ ] **Step 1: Write the failing test**

Append to `WalkerDcachePortArbSpec`:

```scala
  test("W19/M1: a walker command presented during S_APPLY is REFUSED") {
    // The one-cycle window the fix exists for: S_APPLY is where maintCmdOut is actually
    // PULSED (ExceptionUnit.scala:1735), but DcachePlugin's maintBusyReg only rises on the
    // maintenance FSM's own IDLE -> WAIT edge (DcachePlugin.scala:1682). That leaves one
    // cycle, entirely inside S_APPLY and AFTER S_DRAIN has released the hold, during which
    // the cache is FULLY OPEN -- reintroducing exactly the transaction the quiesce just
    // finished waiting out, one cycle before the maintenance FSM latches its own
    // protection. Bounded, not a deadlock, but it defeats the point of the quiesce and is
    // free to close.
    withDut("w19-s-apply", seed = 30) { dut =>
      requestWalkerLoadContinuously(dut, WalkerIdx.DTLB)
      issueCpushAll(dut)
      var admittedDuringHold = false
      dut.clockDomain.onSamplings {
        if (dut.excQuiesceHold.toBoolean &&
            (dut.walkLoadCmdReady(1).toBoolean || dut.walkStoreReady(1).toBoolean))
          admittedDuringHold = true
      }
      awaitMaintDone(dut)
      assert(!admittedDuringHold, "a walker command was admitted during S_DRAIN/S_APPLY")
    }
  }

  test("W19: quiesceHold NEVER closes CORE admission, and the maintenance walk completes") {
    withDut("w19-core-unaffected", seed = 31) { dut =>
      startContinuousLoadStream(dut)
      issueCpushAll(dut)
      val done = waitUpTo(dut, cycles = 20000) { dut.maintDone.toBoolean }
      assert(done, "the maintenance walk never completed -- quiesceHold blocked CORE")
    }
  }

  test("W19: the hold is NOT asserted for all of excActive -- an FSAVE/FRESTORE DTLB miss can still walk") {
    // Section 4.2.4's requirement: since Task 11 the exception sequencer performs genuinely
    // VIRTUAL FSAVE/FRESTORE accesses through the DTLB, so a DTLB miss raised BY THE
    // EXCEPTION SEQUENCER ITSELF must be able to run its walk while excActive is high.
    // A blanket "walkers may not run during excActive" rule would DEADLOCK.
    withDut("w19-fsave-walk", seed = 32) { dut =>
      val walked = runFrestoreWithDtlbMiss(dut)
      assert(walked, "the exception sequencer's own DTLB miss could not run its walk")
    }
  }
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
free -g && ~/sbt/bin/sbt "testOnly m68k040.execute.WalkerDcachePortArbSpec"
```
Expected: the `S_APPLY` test FAILS (a walker is admitted in the open cycle); the other two are expected to pass already and exist as regression guards for the fix's blast radius.

- [ ] **Step 3: Export `quiesceHold` from `ExceptionUnit`**

Beside the `dbgFsmIs*` block at `:2335-2341` (which is where `fsm` is already in scope at class level):

```scala
  // ── W19 (walker/DcacheService passthrough design, section 6.2). Closes walker
  // admission at the LsEuPlugin port mux for the duration of the commit-time sysOp
  // quiesce. CORE is entirely unaffected.
  //
  // SPANS S_DRAIN **AND** S_APPLY, not S_DRAIN alone: S_APPLY (:1735) is where
  // maintCmdOut is actually pulsed, but DcachePlugin's maintBusyReg only rises on the
  // maintenance FSM's own IDLE -> WAIT edge (DcachePlugin.scala:1682), leaving a
  // one-cycle fully-open window inside S_APPLY after S_DRAIN has released.
  //
  // S_MAINTWAIT and later need no hold: by then maintBusyReg is set and
  // loadCmdPort.ready / storePort.ready refuse walker commands on their own -- BOTH
  // directions carry the identical !maintBusyReg gate (DcachePlugin.scala:981 and
  // :1865-1866), a bounded STALL (sets*ways = 512 iterations plus writeback beats), not
  // a deadlock: the maintenance walk's completion depends on nothing the walker holds.
  val quiesceHold = fsm.isActive(fsm.S_DRAIN) || fsm.isActive(fsm.S_APPLY)
  quiesceHold.simPublic()
```

- [ ] **Step 4: Rewrite the now-false clause of the `:1361-1381` deadlock comment**

Spec §6.2 makes this a **verification obligation**, not tidying: *"leaving a now-false deadlock proof in the source is exactly the drift this project's review history keeps catching."* Locate the comment by its text (`"With the LS EU flushed, nothing re-arms them."` — at `849f78a` it is at `:1381`) and replace that final sentence with:

```scala
    //     With the LS EU flushed, nothing re-arms them -- EXCEPT a table walker, which
    //     since the walker/DcacheService passthrough (W19) is an ordinary DcacheService
    //     client and CAN re-arm every one of dcIdleForMaint's terms. `quiesceHold` below
    //     is what restores this clause: while this state (and S_APPLY) is active, the LS
    //     EU's port mux admits NO NEW WALKER COMMAND on either direction, so the walker
    //     stalls BETWEEN descriptor reads and the cache reaches quiesce. The hold is at
    //     COMMAND granularity, not walk granularity -- a walker does not need to FINISH
    //     its walk for the cache to quiesce, only to have no command IN FLIGHT, which is
    //     at most one AXI round trip away. Nothing this state needs depends on a walk
    //     finishing: the ITLB walk gates instruction fetch, which is irrelevant while the
    //     frontend is squashed, and the DTLB walk gates an LS EU translation, and the LS
    //     EU is flushed. So the hold is bounded by this state's own bounded exit plus
    //     S_APPLY's single cycle.
```

- [ ] **Step 5: Wire it in `FullCoreSynth`**

Immediately after `exc.maintDoneIn := dc.maintDone` (`:429`):

```scala
    // W19: the commit-time sysOp quiesce closes WALKER admission at the LS EU's port mux.
    // CORE is unaffected -- see ExceptionUnit's `quiesceHold` for why this is required
    // and why it must span S_APPLY as well as S_DRAIN.
    lsEu.quiesceHold        := exc.quiesceHold
```

- [ ] **Step 6: Run the tests to verify they pass**

```bash
free -g && ~/sbt/bin/sbt "testOnly m68k040.execute.WalkerDcachePortArbSpec"
make SBT=~/sbt/bin/sbt test-fast
python3 tools/socket/check_socket_netlist.py
```
Expected: all three W19 rows PASS; `test-fast` matches Task 1's baseline; FREEZE PASSES (`quiesceHold` is an internal wire, not a port).

- [ ] **Step 7: Commit**

```bash
git add src/main/scala/m68k040/exception/ExceptionUnit.scala \
        src/main/scala/m68k040/top/FullCoreSynth.scala \
        src/test/scala/m68k040/execute/WalkerDcachePortArbSpec.scala
git commit -m "walker(W19): quiesceHold over S_DRAIN || S_APPLY + fix the now-false deadlock proof

ExceptionUnit.scala's written S_DRAIN deadlock analysis ends 'With the LS EU
flushed, nothing re-arms them.' After this design a WALKER re-arms them -- a
walker issuing descriptor reads keeps every dcIdleForMaint term set, so
S_DRAIN's sqDrained && dcQuiesced wait may never be observed.

The hold spans S_APPLY as well as S_DRAIN: S_APPLY is where maintCmdOut is
pulsed, but maintBusyReg only rises on the maintenance FSM's own IDLE->WAIT edge,
leaving a one-cycle fully-open window after S_DRAIN releases.

Command granularity, not walk granularity, and CORE is never held -- so it is
provably terminating, and the exception sequencer's OWN FSAVE/FRESTORE DTLB miss
can still run its walk (a blanket no-walkers-during-excActive rule would
deadlock). The stale comment is rewritten in the same commit.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Task 10: W26 — the production wedge counter on D28's halt-reason channel

W10 declines D20's **timer** with a proof (§4.2.5). It does **not** decline D20's **observability**, and leaving that open is a real hole: a wedge at *this new merge point* produces **no AXI grant to time out** (D20's watchdog is one layer down and never sees it), **no abandoned transaction** (D19's invariant is not violated — nothing was ever issued), and **no halt reason** (D28's channel, built so hangs are *attributable* rather than silent, is never driven). The result would be a hang **less observable than the mechanism it displaces** — the wrong direction of travel.

**Files:**
- Modify: `src/main/scala/m68k040/socket/HaltReason.scala`
- Modify: `src/main/scala/m68k040/execute/LsEuPlugin.scala` (`dcArb`)
- Modify: `src/main/scala/m68k040/top/FullCoreSynth.scala:365-395` (the halt fold and the reason mux)
- Test: `src/test/scala/m68k040/socket/HaltReasonSpec.scala` (extend), `src/test/scala/m68k040/execute/WalkerDcachePortArbSpec.scala` (extend)

**Interfaces:**
- Consumes: `dcArb` (Tasks 4/6/7), `walkerWedgeLimit` (Task 7's constructor param).
- Produces, relied on by Task 15:
  - `m68k040.socket.HaltReason.WALKER_PORT_WEDGE: Int = 5`, and `HaltReason.name(5) == "WALKER_PORT_WEDGE"`.
  - `LsEuPlugin.logic.dcArb.walkerPortWedge: Bool` — sticky once set; `simPublic`.

- [ ] **Step 1: Write the failing test**

Append to `HaltReasonSpec`:

```scala
  test("W26: WALKER_PORT_WEDGE is a distinct kind that fits the declared width") {
    assert(HaltReason.WALKER_PORT_WEDGE == 5)
    assert(HaltReason.WALKER_PORT_WEDGE < (1 << HaltReason.W),
      "the new kind does not fit HaltReason.W bits")
    val all = Seq(HaltReason.NONE, HaltReason.DCACHE_DIAG, HaltReason.FS_XLATE,
                  HaltReason.RESET_VECTOR, HaltReason.ARBITER_WEDGE,
                  HaltReason.WALKER_PORT_WEDGE)
    assert(all.distinct.size == all.size, "two halt reasons share a code")
    assert(HaltReason.name(HaltReason.WALKER_PORT_WEDGE) == "WALKER_PORT_WEDGE")
  }
```
and to `WalkerDcachePortArbSpec`:

```scala
  test("W26: a wedged walker request raises walkerPortWedge and NEVER fabricates a response") {
    // The D20 posture preserved verbatim: an arbiter cannot construct a truthful
    // completion on behalf of its owner, so this REPORTS and never fabricates.
    withDut("w26-wedge", seed = 40, wedgeLimit = 64) { dut =>
      wedgeTheLoadPort(dut)                       // hold loadCmdPort.ready low indefinitely
      requestWalkerLoadContinuously(dut, WalkerIdx.ITLB)
      var sawRsp = false
      dut.clockDomain.onSamplings { if (dut.walkLoadRspValid(0).toBoolean) sawRsp = true }
      val fired = waitUpTo(dut, cycles = 400) { dut.walkerPortWedge.toBoolean }
      assert(fired, "walkerPortWedge never asserted on a wedged walker request")
      assert(!sawRsp, "a response was FABRICATED for the walker -- D20's posture is violated")
      assert(dut.coreHalted.toBoolean, "the wedge did not latch the sticky core halt")
      assert(dut.haltReason.toInt == HaltReason.WALKER_PORT_WEDGE,
        "the halt was not attributed to the walker port")
    }
  }

  test("W26: the counter clears on progress and never fires under ordinary traffic") {
    withDut("w26-no-false-fire", seed = 41, wedgeLimit = 64) { dut =>
      dut.clockDomain.onSamplings {
        assert(!dut.walkerPortWedge.toBoolean, "walkerPortWedge fired under ordinary traffic")
      }
      runLoadStreamWithWalkerInterleave(dut, cycles = 5000)
    }
  }
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
free -g && ~/sbt/bin/sbt "testOnly m68k040.socket.HaltReasonSpec m68k040.execute.WalkerDcachePortArbSpec"
```
Expected: `value WALKER_PORT_WEDGE is not a member of object HaltReason`.

- [ ] **Step 3: Add the halt-reason kind**

In `src/main/scala/m68k040/socket/HaltReason.scala`, after `ARBITER_WEDGE = 4`:

```scala
  /** W26 (walker/DcacheService passthrough design, section 4.2.5): the LS EU's D-cache
    * port arbitration point had a walker request pending for its full bounded window with
    * NO command fire on that direction at all. A wedge THERE produces no AXI grant to time
    * out (D20 is one layer down), no abandoned transaction (D19's invariant is not
    * violated -- nothing was issued), and would otherwise drive no halt reason at all --
    * i.e. a hang LESS observable than the raw-AXI mechanism it displaces. Like D15 and
    * D20, this REPORTS and never fabricates a loadRsp or a storeAck. */
  val WALKER_PORT_WEDGE = 5
```
and the matching `case WALKER_PORT_WEDGE => "WALKER_PORT_WEDGE"` in `name`. **`HaltReason.W` stays 3** — 5 fits.

- [ ] **Step 4: Add the counters to `dcArb`**

```scala
      // ── W26. One counter per DIRECTION, incremented while a walker request is high with
      // no grant and NO FIRE AT ALL on that direction; cleared on any fire or grant. This
      // is the ONLY new production state W10's starvation proof does not already justify,
      // and it buys the difference between a silent hang and an attributable one.
      //
      // BOUND DERIVATION (spec section 4.2.5 requires the plan to derive it explicitly
      // rather than inherit D20's 2e9): this is a STRUCTURAL-WEDGE detector on an on-chip
      // arbiter, not an off-chip fabric timeout -- its expiry can only mean an arbiter
      // bug. It must therefore exceed every LEGITIMATE wait, whose worst case is
      // dominated by N4's maintenance walk:
      //     walkerAgeLimit                     64  (W8's fairness window)
      //   + store drain-to-zero            <=   4  store descriptors
      //   + one refill round trip          <= 256  cycles (fabric-bounded, D19)
      //   + a maintenance walk           128*4 = 512 iterations, each <= 64 cycles
      //                                        = 32768  <-- dominates
      //   ------------------------------------------------
      //   worst legitimate wait            ~ 33092 cycles
      // Rounded generously to the next power of two: 65536, the default of
      // `walkerWedgeLimit`. Parameterised so directed tests can use a short value.
      val wedgeCtrW  = log2Up(walkerWedgeLimit + 1)
      val ldWedgeCtr = Reg(UInt(wedgeCtrW bits)) init 0
      val stWedgeCtr = Reg(UInt(wedgeCtrW bits)) init 0
      val walkerPortWedge = RegInit(False); walkerPortWedge.simPublic()
```
and at the arbitration site:

```scala
    when(ldWalkAny && !dcache.loadCmd.fire) {
      when(dcArb.ldWedgeCtr =/= walkerWedgeLimit) { dcArb.ldWedgeCtr := dcArb.ldWedgeCtr + 1 }
    } otherwise { dcArb.ldWedgeCtr := 0 }
    when(stWalkAny && !dcache.store.fire) {
      when(dcArb.stWedgeCtr =/= walkerWedgeLimit) { dcArb.stWedgeCtr := dcArb.stWedgeCtr + 1 }
    } otherwise { dcArb.stWedgeCtr := 0 }
    when(dcArb.ldWedgeCtr === walkerWedgeLimit || dcArb.stWedgeCtr === walkerWedgeLimit) {
      dcArb.walkerPortWedge := True      // sticky; never fabricates a response
    }
```

- [ ] **Step 5: Keep the sim-only starvation assertion too**

Spec §4.2.5 keeps *both*: the sim assertion fires far earlier and far more precisely; W26's counter is the backstop for silicon. Append inside Task 4's `GenerationFlags.simulation` block:

```scala
      // W10's sim-only starvation assertion. K is sized from the drain bound: the load
      // direction's only residual wait is the CORE-EXC boundary (bounded by the exception
      // sequencer's own bounded access) and the store direction's is at most 4 store
      // descriptors, so `walkerAgeLimit + 512` is generous while still being orders of
      // magnitude tighter than W26's silicon backstop.
      for (i <- 0 until WalkerIdx.COUNT) {
        assert(dcArb.ldAge(i) < walkerAgeLimit || dcArb.ldWedgeCtr < (walkerAgeLimit + 512),
          "a walker load request stayed un-granted past LIMIT + K", FAILURE)
      }
```

- [ ] **Step 6: Fold it into the D28 halt drive**

In `FullCoreSynth.scala`, extend the halt OR and the reason mux. **Priority is stated here rather than left to elaboration order**, matching the existing comment's discipline:

```scala
    // W26: a FIFTH halt producer. Placed LAST in the priority chain for the same reason
    // the arbiter wedge is: a port-arbitration wedge is the most likely CONSEQUENCE of one
    // of the others, so anything with a sub-code or a more specific subject wins.
    val walkerWedge = lsEu.logic.dcArb.walkerPortWedge
    rob.logic.coreHaltedIn := dc.diagFault || exc.fsXlateFault || arbWedge || rvHalt || walkerWedge
```
and add the innermost `Mux(walkerWedge, U(HaltReason.WALKER_PORT_WEDGE, HaltReason.W bits), U(HaltReason.NONE, …))` arm.

- [ ] **Step 7: Run the tests to verify they pass**

```bash
free -g && ~/sbt/bin/sbt "testOnly m68k040.socket.HaltReasonSpec m68k040.execute.WalkerDcachePortArbSpec"
make SBT=~/sbt/bin/sbt test-fast
python3 tools/socket/check_socket_netlist.py
```
Expected: all PASS; `test-fast` matches Task 1's baseline; FREEZE PASSES (`haltReason` is already a 3-bit port and does not resize).

- [ ] **Step 8: Commit**

```bash
git add src/main/scala/m68k040/socket/HaltReason.scala \
        src/main/scala/m68k040/execute/LsEuPlugin.scala \
        src/main/scala/m68k040/top/FullCoreSynth.scala \
        src/test/scala/m68k040/socket/HaltReasonSpec.scala \
        src/test/scala/m68k040/execute/WalkerDcachePortArbSpec.scala
git commit -m "walker(W26): production wedge counter on D28's halt-reason channel

W10 declines D20's TIMER with a proof; it does NOT decline D20's OBSERVABILITY.
A wedge at this new merge point produces no AXI grant to time out, no abandoned
transaction, and would drive no halt reason at all -- a hang LESS observable than
the raw-AXI mechanism it displaces.

One counter per direction, cleared on any fire or grant, latching the sticky
coreHalted with a new distinct kind WALKER_PORT_WEDGE = 5. It REPORTS and never
fabricates a loadRsp or storeAck -- D20's posture preserved verbatim. The bound
is derived explicitly (65536, dominated by N4's 512-iteration maintenance walk)
rather than inheriting D20's 2e9. W10's sim-only starvation assertion is kept
alongside: it fires far earlier and far more precisely; the counter is the
backstop for silicon.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Task 11: Synth checkpoint #2 — post-mux, uncontended (spec §5, §10.4)

**Gate task. No RTL.** Spec §5 mandates *"Full-core OOC synth after the mux change and again after the walker/MMU changes, as two separate checkpoints, so a regression is attributable."* This is the first of those two, and it is the one that measures the change the design knowingly makes: the `loadCmdPort.payload.vaddr` mux widening 3-way → 5-way onto the protected `rdSet` net, and `probeCancelAll`'s added OR term.

**Files:**
- Modify: `.superpowers/sdd/progress-walker-dcache-passthrough-2026-08-18.md`

**Interfaces:**
- Consumes: Task 1's baseline numbers and top-10 path list.
- Produces: a `## Synth checkpoint #2 (post-mux)` ledger section in the same shape, plus an explicit PASS/FAIL verdict and, if FAIL, the lever applied.

- [ ] **Step 1: Confirm the machine is uncontended**

```bash
pgrep -a vivado; pgrep -af 'jtag|xsdb|hw_server'; free -g
```
No Vivado, no JTAG, ≥ 20 GB free. Otherwise STOP.

- [ ] **Step 2: Run the OOC synth in a fresh worktree at this commit**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
git worktree add /home/qwertyoruiop/tmp/.../scratchpad/wt-walker-mux HEAD
cd /home/qwertyoruiop/tmp/.../scratchpad/wt-walker-mux
make SBT=~/sbt/bin/sbt verilog
# then the same OOC flow, same POSTROUTE_ROUNDS, as Task 1
```

- [ ] **Step 3: Compare the top-10 failing path SHAPE, not just the number**

Spec §5: *"A path list that is shape-identical to the baseline is the pass criterion, not merely a number that happens to hold."* Check each of the seven named nets explicitly, and **check the `loadCmdPort_payload_vaddr` → `cmdSet` → `rdSet` arc FIRST and by name** — it is the one the design knowingly widens:

```bash
grep -nE 'rdSet|rdEn|loadCmdPort_payload_vaddr|loadCmdPort_ready|storePort_ready|loadProbePort_valid|aligned(Send|Rsp|Push)Ptr' \
  <the post-route timing report>
```
Record which, if any, of these newly appears in the top 10 relative to Task 1's list.

- [ ] **Step 4: Apply the levers, in order, if FMax regressed**

Spec §5 fixes both the levers and their order. **Do not improvise, and do not revert.**

1. **§5 item (1)'s list, in order:**
   a. Reorder the mux legs so the two walker inputs sit at the *far* end of the select tree rather than in series with the CORE path. (Task 7 already places them lowest-priority; verify the synthesised structure agrees.)
   b. If it still moves: **precompute a single registered `walkVaddrSel` payload** — a 2-way ITLB/DTLB pre-mux, one cycle early. A walker command is registered and stable, so this costs **no throughput**, and it returns the boundary mux to 4-way with one flop-fed leg.
2. **Move the age/`force` comparison off the grant decision path** — precompute `force(i)` a cycle early. It is a counter comparison against a constant and has a full cycle of slack by construction.

**"Reverting the design is not a lever"** (spec §5, verbatim); it is the outcome only if every lever above is exhausted **and measured**. If a lever is applied, it is an RTL change: commit it, re-run `test-fast` and the directed specs, and re-run this checkpoint.

- [ ] **Step 5: Record the verdict and commit the ledger**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
git add .superpowers/sdd/progress-walker-dcache-passthrough-2026-08-18.md
git commit -m "docs(walker): synth checkpoint #2 -- post-mux, uncontended

Attributes any FMax movement to the arbitration layer alone, before the MMU side
changes. Compares the top-10 failing path SHAPE against Task 1's baseline, with
the loadCmdPort_payload_vaddr -> cmdSet -> rdSet arc checked first and by name --
the one net the design knowingly widens 3-way -> 5-way.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Task 12: `DcacheClientMemAgent` — the sim helper for the 8 DUTs with no `DcachePlugin` (W18)

Eight DUT classes host **no** `DcachePlugin` and today attach a `BehavioralMemAgent` straight to `walkerAxi`. Once the walker speaks `DcacheService` instead, those DUTs have nothing to answer it. W18 builds a `Stream`/`Flow` analogue of `BehavioralMemAgent` so they keep working.

**Files:**
- Create: `src/test/scala/m68k040/sim/DcacheClientMemAgent.scala`
- Test: `src/test/scala/m68k040/sim/DcacheClientMemAgentSpec.scala` (create)

**Interfaces:**
- Consumes: `m68k040.sim.AxiMemModel`'s `SparseMemory` and `AxiMemModelConfig` (existing).
- Produces, relied on verbatim by Task 15's eight DUT edits:

```scala
package m68k040.sim

/** W18. A behavioural `DcacheService` CLIENT-SIDE responder for DUTs that host no
  * DcachePlugin, in the shape of `m68k040.ls.BehavioralMemAgent` but over the walker's
  * flat Stream/Flow hooks instead of an Axi4 bundle.
  *
  * @param cmdValid/cmdPaddr/cmdReady        the walker's load command hooks
  * @param rspValid/rspData/rspFault         the load response hooks THIS agent drives
  * @param stValid/stPaddr/stStrb/stLineData the U/M store hooks
  * @param stReady/stAck                     the store handshake hooks THIS agent drives
  * @param latency  cycles from an accepted command to its response. DEFAULT 3, NOT 0:
  *                 a zero-latency responder cannot reproduce the in-flight windows the
  *                 arbitration layer exists to handle, and several of the migrated tests
  *                 count walker commands across exactly those windows.
  * @param sharedMem  pass the SAME SparseMemory the DUT's other memory agents use when
  *                   the test intends shared page-table memory; null allocates a private
  *                   one (see the per-site review obligation in Tasks 15/16). */
class DcacheClientMemAgent(cmdValid: Bool, cmdPaddr: UInt, cmdReady: Bool,
                           rspValid: Bool, rspData: Bits, rspFault: Bool,
                           stValid: Bool, stPaddr: UInt, stStrb: Bits, stLineData: Bits,
                           stReady: Bool, stAck: Bool,
                           cd: ClockDomain,
                           latency: Int = 3,
                           sharedMem: SparseMemory = null) {
  val mem: SparseMemory
  def pokeByte(addr: Long, value: Int): Unit
  def peekByte(addr: Long): Int
  def poke128(addr: Long, data: BigInt): Unit
  def peek128(addr: Long): BigInt
  /** Count of accepted load commands -- the direct replacement for the migrated tests'
    * `walkerAxi.ar`-fire counters. */
  def loadCmdFires: Int
  /** Count of accepted stores -- the replacement for U/M drain observation. */
  def storeFires: Int
  /** OPT-IN, DEFAULT-OFF, mirroring BehavioralMemAgent's own hook. */
  def setByteWriteObserver(f: (Long, Byte) => Unit): Unit
  /** Force the next response to carry `fault` -- the D19 re-establishment path's stimulus. */
  def armLoadFault(addr: Long): Unit
}
```

- [ ] **Step 1: Write the failing test**

Create `src/test/scala/m68k040/sim/DcacheClientMemAgentSpec.scala` with a tiny DUT exposing the twelve hooks as ports, and assert:

```scala
  test("a LONG load returns the BIG-ENDIAN descriptor word at the byte address") {
    // The whole point of routing through DcacheService is that the walker stops
    // hand-rolling this: DcacheByteLane.extract's LONG case is the ONE definition, and the
    // agent must match it byte for byte or the migrated MMU tests will disagree with the
    // full-core ones for reasons that have nothing to do with the RTL.
    // Byte at the LOWEST address is the descriptor's MSB (task #194).
    withAgentDut("be-lane", seed = 1) { (dut, ag) =>
      ag.pokeByte(0x1004, 0x11); ag.pokeByte(0x1005, 0x22)
      ag.pokeByte(0x1006, 0x33); ag.pokeByte(0x1007, 0x44)
      assert(issueLoad(dut, 0x1004) == 0x11223344L)
    }
  }

  test("the lane is selected by the BYTE address, not the line base") {
    withAgentDut("lane-select", seed = 2) { (dut, ag) =>
      for (i <- 0 until 16) ag.pokeByte(0x2000 + i, 0xA0 + i)
      assert(issueLoad(dut, 0x2000) == 0xA0A1A2A3L)
      assert(issueLoad(dut, 0x2008) == 0xA8A9AAABL)
    }
  }

  test("an explicit-strobe store writes EXACTLY the strobed bytes and acks once") {
    withAgentDut("strb-store", seed = 3) { (dut, ag) =>
      for (i <- 0 until 16) ag.pokeByte(0x3000 + i, 0x00)
      val acks = issueStrbStore(dut, paddr = 0x3000, byteOff = 7, value = 0x5A)
      assert(acks == 1, s"expected exactly one storeAck, got $acks")
      assert(ag.peekByte(0x3007) == 0x5A)
      for (i <- 0 until 16 if i != 7)
        assert(ag.peekByte(0x3000 + i) == 0x00, s"byte $i was written outside the strobe")
    }
  }

  test("the response respects the configured latency and never fires unaccepted") {
    withAgentDut("latency", seed = 4, latency = 5) { (dut, ag) =>
      val gap = measureCmdToRspCycles(dut, 0x1000)
      assert(gap == 5, s"response arrived after $gap cycles, expected 5")
      // A response with no accepted command would desynchronise the ownership FIFO's
      // positional tagging in every DUT that DOES host the arbitration layer.
      assert(countUnsolicitedResponses(dut, cycles = 200) == 0)
    }
  }

  test("loadCmdFires / storeFires count accepted commands, replacing the ar-fire counters") {
    withAgentDut("counters", seed = 5) { (dut, ag) =>
      issueLoad(dut, 0x1000); issueLoad(dut, 0x1010)
      issueStrbStore(dut, 0x3000, 1, 0x11)
      assert(ag.loadCmdFires == 2 && ag.storeFires == 1)
    }
  }

  test("armLoadFault makes exactly the next response to that address carry fault") {
    withAgentDut("fault", seed = 6) { (dut, ag) =>
      ag.armLoadFault(0x4000)
      assert(issueLoadFault(dut, 0x4000), "the armed fault did not appear")
      assert(!issueLoadFault(dut, 0x4000), "the fault was sticky; it must be one-shot")
    }
  }
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
free -g && ~/sbt/bin/sbt "testOnly m68k040.sim.DcacheClientMemAgentSpec"
```
Expected: `not found: type DcacheClientMemAgent`.

- [ ] **Step 3: Implement the agent**

Model it on `src/test/scala/m68k040/ls/BehavioralMem.scala` and `src/test/scala/m68k040/sim/AxiMemModel.scala`. Load path: on `cmdValid && cmdReady` (drive `cmdReady` high unless a response is queued), capture `cmdPaddr`, schedule a response `latency` cycles later reading four bytes **big-endian from the byte address** (`b0 ## b1 ## b2 ## b3`, byte at the lowest address is the MSB — the task-#194 convention `DcacheByteLane.extract`'s LONG case uses, which the walker is now downstream of). Store path: on `stValid && stReady`, write the strobed bytes of `stLineData` at `stPaddr + i` for each set `stStrb(i)`, then pulse `stAck` one cycle later. Never pulse `rspValid` or `stAck` without a matching accepted command.

- [ ] **Step 4: Run the test to verify it passes**

```bash
~/sbt/bin/sbt "testOnly m68k040.sim.DcacheClientMemAgentSpec"
make SBT=~/sbt/bin/sbt test-fast
```
Expected: 6/6 PASS; `test-fast` unchanged (test-only addition).

- [ ] **Step 5: Commit**

```bash
git add src/test/scala/m68k040/sim/DcacheClientMemAgent.scala \
        src/test/scala/m68k040/sim/DcacheClientMemAgentSpec.scala
git commit -m "walker(W18): DcacheClientMemAgent -- behavioural DcacheService responder

A Stream/Flow analogue of BehavioralMemAgent for the 8 DUT classes that host no
DcachePlugin and today attach an AXI memory straight to walkerAxi. Big-endian
LONG lane selection matching DcacheByteLane.extract byte for byte (task #194
convention), explicit-strobe stores, a configurable non-zero default latency (a
zero-latency responder cannot reproduce the in-flight windows the arbitration
layer exists to handle), and loadCmdFires/storeFires counters that directly
replace the migrated tests' walkerAxi.ar-fire counters.

Test-only. No src/main change.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Task 13: `TableWalker` — descriptor reads through `DcacheService`, and the D19 re-establishment (W16)

**Files:**
- Modify: `src/main/scala/m68k040/mmu/TableWalker.scala` (`axiCfg` `:37`, `io.axi` `:45`, `selectWord` `:86-94`, `issueRead()` `:104-120`, the three `when(io.axi.r.fire)` arms `:141`/`:161`/`:180`, the class doc comment `:21-33`)
- Test: `src/test/scala/m68k040/mmu/DtlbSpec.scala` etc. are migrated in Task 15; this task's own coverage is a new case in `WalkerProbeDeadlockSpec`'s DUT plus the D19 fault case.

**Interfaces:**
- Consumes: `DcacheClientMemAgent` (Task 12) for its own directed test.
- Produces, relied on verbatim by Task 14:
  - `TableWalker.io` loses `axi` and gains:

```scala
    val ldCmdValid = out Bool ()      // present a descriptor read
    val ldCmdPaddr = out UInt (32 bits)   // the descriptor's BYTE address (NOT line-aligned)
    val ldCmdReady = in  Bool ()
    val ldRspValid = in  Bool ()      // ALREADY qualified to THIS walker by LsEuPlugin
    val ldRspData  = in  Bits (32 bits)
    val ldRspFault = in  Bool ()
```
  - `TableWalker.axiCfg` and `TableWalker.selectWord` no longer exist.

- [ ] **Step 1: Write the failing test**

Add to `src/test/scala/m68k040/mmu/WalkerProbeDeadlockSpec.scala` (or a small dedicated DUT in the same file — the walker is now a pure `DcacheService` client, so it needs no AXI harness at all):

```scala
  test("W16: the walk consumes loadRsp.payload.data directly and resolves a 3-level walk") {
    withWalkerOnlyDut("w16-three-level", seed = 10) { (dut, ag) =>
      plantThreeLevelTable(ag, vpn = 0x12345, ppn = 0x0ABCD)
      startWalk(dut, vpn = 0x12345)
      assert(awaitWalkDone(dut), "the walk never completed")
      assert(dut.rspPpn.toLong == 0x0ABCD, "the walk resolved the wrong PPN")
      assert(ag.loadCmdFires == 3, s"expected 3 dependent reads, got ${ag.loadCmdFires}")
    }
  }

  test("W16: loadCmd.paddr is the descriptor's BYTE address, not the line base") {
    // The load-bearing requirement is that VADDR carries the descriptor's byte offset --
    // DcachePlugin derives the lane from cmdVaddr(offBits-1 downto 0), NOT from paddr. The
    // walker is identity-physical so it sets vaddr := paddr := descAddr, and getting this
    // wrong reads lane 0 of every descriptor line with no fault and no assertion.
    withWalkerOnlyDut("w16-byte-addr", seed = 11) { (dut, ag) =>
      plantThreeLevelTableAtOddLanes(ag, vpn = 0x12345)
      startWalk(dut, vpn = 0x12345)
      assert(awaitWalkDone(dut))
      assert(observedCmdPaddrs(dut).forall(a => (a & 0xF) != 0),
        "the walker line-aligned its descriptor address; the byte lane will resolve to 0")
    }
  }

  test("W16/D19: loadRsp.fault terminates the walk as NON_RESIDENT via the existing FINISH path") {
    // D19 RE-ESTABLISHMENT. The socket design spec's D19 evidence list explicitly names
    // TableWalker.scala:114 -- the descriptor R consumer this redesign DELETES -- and its
    // section 12 requires the invariant to be RE-CHECKED, not assumed, by any work that
    // changes a response consumer. This is that re-check, and it is MANDATORY, not the
    // "small, genuine improvement" an earlier draft called it: today a walker read's AXI
    // error is SILENTLY IGNORED (TableWalker never inspects r.resp at all), so the new
    // handling is strictly STRONGER than the site it replaces.
    withWalkerOnlyDut("w16-d19", seed = 12) { (dut, ag) =>
      plantThreeLevelTable(ag, vpn = 0x12345, ppn = 0x0ABCD)
      ag.armLoadFault(rootDescAddrFor(0x12345))
      startWalk(dut, vpn = 0x12345)
      assert(awaitWalkDone(dut), "the walk HUNG on a faulting response -- D19 is violated")
      assert(dut.rspFault.toBoolean, "the faulting walk did not report a fault")
      assert(dut.rspFaultReason.toInt == MmuFaultReason.NON_RESIDENT.position)
      assert(!dut.rspUmWriteValid.toBoolean, "a faulting walk queued a U/M write")
    }
  }
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
free -g && ~/sbt/bin/sbt "testOnly m68k040.mmu.WalkerProbeDeadlockSpec"
```
Expected: `value ldCmdValid is not a member of …io`.

- [ ] **Step 3: Convert `TableWalker`**

1. Delete `val axiCfg = Axi4Config(...)` (`:37`), `val axi = master(Axi4ReadOnly(axiCfg))` (`:45`), the AXI defaults (`:74-76`), the `Axi4`/`Axi4Config`/`Axi4ReadOnly` imports, and **`selectWord` (`:86-94`) in full**.
   `selectWord` is a hand copy of `DcacheByteLane.extract`'s LONG case — the walker's own doc comment at `:80-85` says so. Now that the walker is *downstream* of the first copy (`DcachePlugin.scala:511-513` already applies it to build `loadRsp.payload.data`), keeping a second copy of the task-#194 big-endian convention would be a live drift hazard.
2. Add the six `io` members listed under **Interfaces**.
3. `issueRead()` becomes:

```scala
    def issueRead(): Unit = {
      io.ldCmdValid := !cmdSent
      io.ldCmdPaddr := descAddr        // the descriptor's BYTE address -- NOT line-aligned.
      when(io.ldCmdValid && io.ldCmdReady) { cmdSent := True }
    }
```
   with `arSent` renamed `cmdSent` (`:55`) and its `arSent := False` resets renamed accordingly. Default `io.ldCmdValid := False` / `io.ldCmdPaddr := descAddr` at the top of `logic`, as the old AXI defaults did.
4. Each of the three `when(io.axi.r.fire)` arms becomes `when(io.ldRspValid)` with `val d = io.ldRspData`. **`TableWalker` needs NO owner awareness**: `LsEuPlugin` already qualifies each walker's `ldRspValid` by `ldRspTag`, so the walker sees a `valid` only for its own response.
5. Add the D19 fault arm to each of the three `RD_*` states, ahead of the descriptor decode:

```scala
      when(io.ldRspValid) {
        when(io.ldRspFault) {
          // W16 / D19 re-establishment. `loadRsp.fault` (a physical AXI refill error) is
          // reachable for a walk for the FIRST TIME -- today a walker read's AXI error is
          // silently ignored because TableWalker never inspects r.resp. Terminating the
          // walk here is what replaces TableWalker.scala:114 on D19's evidence list:
          // DcachePlugin's refill R consumer (already on that list) terminates the
          // transaction, and loadRsp.fault carries that termination to this FSM.
          rFault := True; rReason := MmuFaultReason.NON_RESIDENT
          rUmValid := False
          goto(FINISH)
        } otherwise {
          /* the existing descriptor decode, unchanged */
        }
      }
```
6. Rewrite the class doc comment's AXI paragraph (`:21-33`). It currently explains at length why the walker reconstructs the big-endian descriptor itself *because it has its own dedicated AXI port straight to physical memory*. That premise is now false. Replace it with: the walker is a `DcacheService` client; `loadRsp.payload.data` is already the extracted big-endian LONG at the byte address; the task-#194 convention now lives in exactly one place (`DcacheByteLane.extract`); and `loadRsp.fault` terminates the walk.

- [ ] **Step 4: Run the test to verify it passes**

```bash
free -g && ~/sbt/bin/sbt "testOnly m68k040.mmu.WalkerProbeDeadlockSpec"
```
Expected: all three W16 rows PASS. **`test-fast` as a whole will NOT compile yet** — `ItlbPlugin`/`DtlbPlugin` still connect `walker.io.axi`. That is expected; Task 14 closes it. Note this explicitly in the task report so a reviewer does not read it as a failure.

- [ ] **Step 5: Commit**

```bash
git add src/main/scala/m68k040/mmu/TableWalker.scala \
        src/test/scala/m68k040/mmu/WalkerProbeDeadlockSpec.scala
git commit -m "walker(W16): TableWalker reads descriptors through DcacheService

io.axi, axiCfg and selectWord deleted. selectWord was a hand copy of
DcacheByteLane.extract's LONG case (the walker's own doc comment said so); now
that the walker is DOWNSTREAM of the first copy, a second copy of the task-#194
big-endian convention would be a live drift hazard.

paddr is the descriptor's BYTE address, not the line base -- the lane comes from
cmdVaddr(offBits-1 downto 0), and the walker is identity-physical, so vaddr must
carry the byte offset or every descriptor reads lane 0 with no fault.

D19 RE-ESTABLISHMENT (socket spec section 12 requires a re-check, not an
assumption, from any work that changes a response consumer): loadRsp.fault now
terminates the walk as NON_RESIDENT via the existing FINISH path. This is
MANDATORY, not polish -- socket spec D19's evidence list names
TableWalker.scala:114 explicitly and this redesign deletes that exact site. The
replacement is strictly stronger: today a walker read's AXI error is silently
ignored.

NOTE: the tree does not fully compile at this commit -- ItlbPlugin/DtlbPlugin
still connect walker.io.axi. Task 14 closes it.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Task 14: `ItlbPlugin` / `DtlbPlugin` — client hooks and the U/M writeback as a `DStoreCmd` (W15, W17, W20 walker half)

**Files:**
- Modify: `src/main/scala/m68k040/mmu/DtlbPlugin.scala` (`walkerAxi` `:69,75-98`, the drain FSM `:318-358`)
- Modify: `src/main/scala/m68k040/mmu/ItlbPlugin.scala` (`walkerAxi` `:65,71-93`, the drain FSM `:250-289`)
- Test: `src/test/scala/m68k040/mmu/UmWriteSpec.scala` is migrated in Task 15; this task's coverage is the U/M drain case added to the walker-only DUT.

**Interfaces:**
- Consumes: `TableWalker`'s new `io` (Task 13).
- Produces, relied on verbatim by Task 15's wiring and by all 24 migrated test files:
  - `ItlbPlugin` / `DtlbPlugin` **no longer have `walkerAxi` or `axiCfg`**.
  - Each gains twelve plugin-level `var` hooks with **exactly** these names and types, allocated in `during setup`, default-idle with `allowOverride` in `logic` (the `umAccessRobId`/`umCommitValid`/`umFlush` pattern at `DtlbPlugin.scala:100-108`):

```scala
  var walkLoadCmdValid:  Bool = null   // out : the walker presents a descriptor read
  var walkLoadCmdPaddr:  UInt = null   // out : the descriptor's byte address
  var walkLoadCmdReady:  Bool = null   // in
  var walkLoadRspValid:  Bool = null   // in  : already qualified to THIS walker
  var walkLoadRspData:   Bits = null   // in  : 32 bits
  var walkLoadRspFault:  Bool = null   // in
  var walkStoreValid:    Bool = null   // out : the U/M writeback presents
  var walkStorePaddr:    UInt = null   // out : 16-byte aligned line address
  var walkStoreStrb:     Bits = null   // out : 16-bit one-hot byte strobe
  var walkStoreLineData: Bits = null   // out : 128-bit beat
  var walkStoreReady:    Bool = null   // in
  var walkStoreAck:      Bool = null   // in  : already demuxed to THIS walker
```

- [ ] **Step 1: Write the failing test**

Add to the walker-only DUT from Task 13:

```scala
  test("W17: the U/M writeback drains as an explicit-strobe DStoreCmd and acks once") {
    withTlbOnlyDut("w17-um-drain", seed = 20) { (dut, ag) =>
      plantThreeLevelTable(ag, vpn = 0x12345, ppn = 0x0ABCD, umClear = true)
      val descAddr = leafDescAddrFor(0x12345)
      translateAndCommit(dut, vpn = 0x12345, isWrite = true)
      assert(awaitUmDrain(dut), "the U/M writeback never drained")
      // The U bit AND the M bit, at the descriptor's NUMERIC LOW byte, which -- 68k memory
      // being big-endian -- physically lives at descAddr + 3 (task #194).
      val b = ag.peekByte(descAddr + 3)
      assert((b & 0x08) != 0 && (b & 0x10) != 0, f"U/M not set: byte = 0x$b%02x")
      assert(ag.storeFires == 1, s"expected exactly one store, got ${ag.storeFires}")
    }
  }

  test("W17: drainArmed keeps the queue's hold-until-drainAck contract (single-outstanding)") {
    withTlbOnlyDut("w17-single-outstanding", seed = 21) { (dut, ag) =>
      withholdStoreAck(dut)
      queueTwoUmWrites(dut)
      dut.clockDomain.waitSampling(30)
      assert(ag.storeFires <= 1, "a second U/M store was presented before the first acked")
      releaseStoreAck(dut)
      assert(awaitUmDrain(dut, n = 2), "the second U/M write never drained")
    }
  }

  test("W17: the drain registers are DRIVEN -- no undriven Reg survives the FSM deletion") {
    // The only writer of drainAddrReg/drainBeatReg/drainStrbReg was the
    // `when(umq.io.drain.valid && drainAwDone && drainWDone)` block that this task
    // deletes. Deleting it verbatim would leave three uninitialised Regs -- and per this
    // project's own sim-poke gotchas those RANDOMISE PER SEED rather than reading zero, so
    // the failure would be seed-dependent and easy to miss. Run several seeds on purpose.
    for (s <- Seq(1, 7, 13, 29)) withTlbOnlyDut(s"w17-driven-$s", seed = s) { (dut, ag) =>
      plantThreeLevelTable(ag, vpn = 0x22222, ppn = 0x0BEEF, umClear = true)
      translateAndCommit(dut, vpn = 0x22222, isWrite = false)
      assert(awaitUmDrain(dut))
      assert(ag.peekByte(leafDescAddrFor(0x22222) + 3) != 0,
        s"seed $s: the drain wrote a randomised/undriven payload")
    }
  }
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
free -g && ~/sbt/bin/sbt "testOnly m68k040.mmu.WalkerProbeDeadlockSpec"
```
Expected: compilation FAILS (`walkerAxi.ar << walker.io.axi.ar` — `io.axi` no longer exists, from Task 13).

- [ ] **Step 3: Replace `walkerAxi` with the client hooks (both plugins)**

Delete `var walkerAxi: Axi4` and `def axiCfg` from the class body, and in `logic` delete the `walkerAxi = …setName(…)` line, the `walkerAxi.ar << walker.io.axi.ar` / `walkerAxi.r >> walker.io.axi.r` pair, the `aw`/`w` idle defaults, and **the whole D27 `walkerAxi.b.ready` guard with its 15-line comment**.

W20: this is not "dead code left with a comment", it is **code whose subject is removed** — the guard guards `walkerAxi`, and `walkerAxi` ceases to exist. **`38e6c31` and `56f2438` are NOT reverted**: a `git revert` would take out the `RESET_VEC` ARID half as collateral (Task 9 of the socket plan still needs it) and would rewrite the history of merged, reviewed work for no benefit. Leave a short note where the guard was:

```scala
    // D27's PRINCIPLE -- fail closed, never fail open, on an unrecognised response --
    // survives and RELOCATES: it is exactly what W13's stOwner demux does inside
    // LsEuPlugin, and what DcacheTypes.scala:126-133's existing storeAck id-demux already
    // embodies inside DcachePlugin. The AXI-level guard that used to sit here has no
    // subject any more: this walker emits no AXI.
```
Then allocate the twelve hooks in `during setup` and default the **inputs** idle in `logic`:

```scala
    walkLoadCmdReady.allowOverride; walkLoadCmdReady := False
    walkLoadRspValid.allowOverride; walkLoadRspValid := False
    walkLoadRspData.allowOverride;  walkLoadRspData  := B(0, 32 bits)
    walkLoadRspFault.allowOverride; walkLoadRspFault := False
    walkStoreReady.allowOverride;   walkStoreReady   := False
    walkStoreAck.allowOverride;     walkStoreAck     := False
```
and connect the walker:

```scala
    walkLoadCmdValid   := walker.io.ldCmdValid
    walkLoadCmdPaddr   := walker.io.ldCmdPaddr
    walker.io.ldCmdReady := walkLoadCmdReady
    walker.io.ldRspValid := walkLoadRspValid
    walker.io.ldRspData  := walkLoadRspData
    walker.io.ldRspFault := walkLoadRspFault
```

- [ ] **Step 4: Replace the AW/W/B drain FSM with `drainArmed` (both plugins)**

Keep `drainByteOff`, `drainBeat` and `drainStrb`'s **combinational construction verbatim** (`DtlbPlugin.scala:320-330` / `ItlbPlugin.scala:252-262`) — it is already exactly `DStoreCmd`'s `useStrb` form. Keep the three holding registers with the same payloads. Delete `drainAwDone`/`drainWDone` and their three `when` blocks, and replace the latch condition:

```scala
    // ── W17. `drainArmed` replaces `drainAwDone && drainWDone` as the "no drain currently
    // presented" predicate: the same single-outstanding discipline, ONE bit instead of two,
    // because there is now ONE handshake instead of two.
    //
    // The latch condition NECESSARILY CHANGES and must not be copied verbatim: the deleted
    // `when(umq.io.drain.valid && drainAwDone && drainWDone)` block was the ONLY WRITER of
    // these three registers, and deleting it as-is would leave them undriven -- which, per
    // this project's own sim-poke gotchas, RANDOMISES PER SEED rather than reading zero.
    val drainArmed = RegInit(False)
    when(umq.io.drain.valid && !drainArmed) {
      drainAddrReg := (umq.io.drain.payload.addr(31 downto 4) ## U(0, 4 bits)).asUInt
      drainBeatReg := drainBeat
      drainStrbReg := drainStrb
      drainArmed   := True
    }
    when(walkStoreValid && walkStoreReady) { drainArmed := False }

    walkStoreValid    := drainArmed
    walkStorePaddr    := drainAddrReg     // 16-byte aligned
    walkStoreStrb     := drainStrbReg
    walkStoreLineData := drainBeatReg
    // The queue holds the entry until drainAck, preserving its existing contract and
    // matching DcacheService.store's stated "elastic ordered drain; payload stable until
    // fire" (DcacheTypes.scala:122).
    umq.io.drainAck   := walkStoreAck
```
**`useStrb`, `data`, `size`, `cacheMode` and `precise` are NOT set here** — the mux stamps them (Task 7). That is W2 and W3 made structural: the MMU plugins gain no `CacheControlService` dependency and cannot make the two halves disagree.

- [ ] **Step 5: Run the tests to verify they pass**

```bash
free -g && ~/sbt/bin/sbt "testOnly m68k040.mmu.WalkerProbeDeadlockSpec"
```
Expected: all three W17 rows PASS across all four seeds. **`test-fast` still will not compile** — 24 test files and `FullCoreSynth` still reference `walkerAxi`. Task 15 closes it. Say so in the report.

- [ ] **Step 6: Commit**

```bash
git add src/main/scala/m68k040/mmu/ItlbPlugin.scala src/main/scala/m68k040/mmu/DtlbPlugin.scala \
        src/test/scala/m68k040/mmu/WalkerProbeDeadlockSpec.scala
git commit -m "walker(W15/W17/W20): ITLB/DTLB walkers become DcacheService clients

walkerAxi, its D27 b.ready guard and the whole AW/W/B drain FSM deleted; twelve
plugin-level var hooks added in the umCommitValid/umFlush idiom, default-idle
with allowOverride so a standalone DUT still elaborates.

W20: Task 4's commits (38e6c31, 56f2438) are NOT reverted -- the walker-side
guard dies with its HOST, while the RESET_VEC ARID half (which socket Task 9
still needs) is untouched. D27's principle relocates to W13's stOwner demux.

W17: drainArmed replaces drainAwDone && drainWDone -- one handshake, one bit. The
latch condition NECESSARILY changes rather than being copied verbatim: the
deleted block was the ONLY writer of drainAddrReg/drainBeatReg/drainStrbReg, and
undriven Regs randomise per seed in this project's sim, so a verbatim deletion
would have been a seed-dependent bug. Tested across four seeds on purpose.

useStrb/data/size/cacheMode/precise are deliberately NOT set here -- the LsEuPlugin
mux stamps them, which is what makes W3's 'one mode for both halves' true by
construction.

NOTE: the tree does not fully compile at this commit -- FullCoreSynth and 24 test
files still reference walkerAxi. Task 15 closes it.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Task 15: Top-level wiring, the 8 no-`DcachePlugin` DUTs, and the port-surface re-baseline (W14, §8.1 bucket 1)

**This is the ONE task in this plan that intentionally moves `M68kFullCoreSynth`'s port surface.** Deleting `walkerAxi` removes the 78 `itlbAxi_*` / `dtlbAxi_*` ports the golden currently pins. The golden is re-baselined here, once, with the justification recorded — not quietly, and not in any other task.

**Files:**
- Modify: `src/main/scala/m68k040/top/FullCoreSynth.scala` (the walker hook wiring, beside the existing `dtlb.umAccessRobId` / `itlb.flushAll` block at `:322-341`)
- Modify: `tools/socket/fullcore_ports.golden` (regenerated)
- Modify (8 files, §8.1 bucket 1): `src/test/scala/m68k040/mmu/DtlbSpec.scala`, `mmu/ItlbSpec.scala`, `mmu/UmWriteSpec.scala`, `mmu/MmuControlSpec.scala`, `mmu/DtlbStreamPipelineSpec.scala`, `cache/IcacheParallelViptSpec.scala`, `frontend/FetchAlignResidentCadenceSpec.scala`, `ls/DtlbMissFlushSpec.scala` (**its first `Dut` only**, `DtlbCleanMissSerializationSpec` at `:97`)
- Delete: `src/test/scala/m68k040/socket/WalkerIdGuardSpec.scala`

**Interfaces:**
- Consumes: the hooks from Tasks 7, 13 and 14; `DcacheClientMemAgent` from Task 12.
- Produces, relied on by Tasks 16 and 17:
  - `FullCoreSynth` wires `lsEu.walk*(WalkerIdx.ITLB)` ↔ `itlb.walk*` and `…(WalkerIdx.DTLB)` ↔ `dtlb.walk*`.
  - `tools/socket/fullcore_ports.golden` is the new 127-port surface (205 − 78).

- [ ] **Step 1: Wire the walker hooks in `FullCoreSynth`**

Immediately after `itlb.flushAll := rob.logic.exc.sysFlushAllValid` (`:341`), replacing the now-stale comment at `:342-343` about `walkerAxi` surfacing automatically:

```scala
    // ── Walker D-cache client ports (W15). The ITLB/DTLB walkers no longer have AXI
    // masters: their descriptor reads and U/M writebacks are ordinary DcacheService
    // traffic, arbitrated by the LS EU's 4-source port mux. The itlbAxi/dtlbAxi top-level
    // ports disappear with them -- the one INTENDED port-surface change in this plan.
    //
    // Response routing happens INSIDE LsEuPlugin (by the ownership FIFO's head on loads,
    // by stOwner on stores), which is why exc.dcLoadRsp above is UNTOUCHED and why no DUT
    // is rewired on the response side (W14).
    lsEu.walkLoadCmdValid(WalkerIdx.ITLB)  := itlb.walkLoadCmdValid
    lsEu.walkLoadCmdPaddr(WalkerIdx.ITLB)  := itlb.walkLoadCmdPaddr
    itlb.walkLoadCmdReady                  := lsEu.walkLoadCmdReady(WalkerIdx.ITLB)
    itlb.walkLoadRspValid                  := lsEu.walkLoadRspValid(WalkerIdx.ITLB)
    itlb.walkLoadRspData                   := lsEu.walkLoadRspData(WalkerIdx.ITLB)
    itlb.walkLoadRspFault                  := lsEu.walkLoadRspFault(WalkerIdx.ITLB)
    lsEu.walkStoreValid(WalkerIdx.ITLB)    := itlb.walkStoreValid
    lsEu.walkStorePaddr(WalkerIdx.ITLB)    := itlb.walkStorePaddr
    lsEu.walkStoreStrb(WalkerIdx.ITLB)     := itlb.walkStoreStrb
    lsEu.walkStoreLineData(WalkerIdx.ITLB) := itlb.walkStoreLineData
    itlb.walkStoreReady                    := lsEu.walkStoreReady(WalkerIdx.ITLB)
    itlb.walkStoreAck                      := lsEu.walkStoreAck(WalkerIdx.ITLB)
    // …the identical twelve lines for `dtlb` at WalkerIdx.DTLB.
```
Write both blocks out in full — a `for` loop over a `Seq((itlb, ITLB), (dtlb, DTLB))` does not typecheck cleanly here because the two plugin types are unrelated, and this file's established style is explicit wiring anyway.

- [ ] **Step 2: Delete `WalkerIdGuardSpec` (W20)**

```bash
git rm src/test/scala/m68k040/socket/WalkerIdGuardSpec.scala
```

- [ ] **Step 3: Migrate the 8 no-`DcachePlugin` DUT classes**

For each of the eight, the edit has the same three parts. **Do them per file, running that file's suite after each, not as one sweep** — the counters are the part that silently mis-measures.

1. **DUT surface.** Replace `def walkerAxi = dtlb.walkerAxi` (`DtlbSpec.scala:54` and its siblings) with the twelve hooks the DUT must expose, e.g.:

```scala
    // W18: the walker is a DcacheService client now; this DUT hosts no DcachePlugin, so
    // DcacheClientMemAgent answers it. Exposed as DUT members so the agent can bind.
    def walkLoadCmdValid  = dtlb.walkLoadCmdValid
    def walkLoadCmdPaddr  = dtlb.walkLoadCmdPaddr
    def walkLoadCmdReady  = dtlb.walkLoadCmdReady
    // … and the other nine
```
   with `.simPublic()` on each inside the DUT's build.

2. **Agent attach.** Replace `new BehavioralMemAgent(dut.walkerAxi, cd)` with:

```scala
      val mem = new m68k040.sim.DcacheClientMemAgent(
        dut.walkLoadCmdValid, dut.walkLoadCmdPaddr, dut.walkLoadCmdReady,
        dut.walkLoadRspValid, dut.walkLoadRspData, dut.walkLoadRspFault,
        dut.walkStoreValid, dut.walkStorePaddr, dut.walkStoreStrb, dut.walkStoreLineData,
        dut.walkStoreReady, dut.walkStoreAck, cd)
```

3. **Counters and wait loops.** Retarget these **exact** sites, and note the two shapes are different edits with different failure modes:

| Site | Today | Becomes |
|---|---|---|
| `DtlbSpec.scala:135, 177, 230` | `if (walkerAxi.ar.valid && walkerAxi.ar.ready) arCount += 1` | `if (walkLoadCmdValid.toBoolean && walkLoadCmdReady.toBoolean) cmdCount += 1` (or read `mem.loadCmdFires`) |
| `UmWriteSpec.scala:220, 290, 351` | same ar-fire counter | same |
| `DtlbStreamPipelineSpec.scala:118` | same ar-fire counter | same |
| `IcacheParallelViptSpec.scala:111, 166` | same ar-fire counter | same |
| `FetchAlignResidentCadenceSpec.scala:139, 141` | ar-fire counter **plus an address trace** | counter **plus** `walkLoadCmdPaddr.toLong` capture |
| **`UmWriteSpec.scala:182`** | **NOT a counter** — `while (!(walkerAxi.ar.valid && walkerAxi.ar.ready) …)` — a **wait loop** guarding a flush injection | a **wait predicate** on the same fire condition. **A wrong predicate here HANGS the test rather than mis-counting**, so verify it individually. |

- [ ] **Step 4: Regenerate the port-surface golden — the one intended change**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
make SBT=~/sbt/bin/sbt verilog
python3 tools/socket/check_socket_netlist.py          # EXPECTED to FAIL, loudly
git diff --stat tools/socket/fullcore_ports.golden    # inspect BEFORE regenerating
python3 tools/socket/check_socket_netlist.py --regen
git diff tools/socket/fullcore_ports.golden
```
**Verify the diff is EXACTLY the 78 `itlbAxi_*`/`dtlbAxi_*` lines and nothing else.** Any other port appearing, disappearing or resizing is a bug in this plan's work, not a rebaseline:

```bash
git diff -U0 tools/socket/fullcore_ports.golden | grep '^[-+][a-zA-Z]' | grep -vE '^-(itlbAxi|dtlbAxi)_' | head
```
Expected: **empty**. If not, stop and investigate.

Add a header comment to `tools/socket/fullcore_ports.golden` recording the re-baseline:

```
# Re-baselined by the walker/DcacheService passthrough plan, Task 15 (W15/W17).
# The 78 itlbAxi_*/dtlbAxi_* ports were removed because the ITLB/DTLB table walkers
# no longer emit AXI at all -- their descriptor reads and U/M writebacks are ordinary
# DcacheService client traffic arbitrated inside LsEuPlugin. This is the ONE intended
# port-surface change in that plan; every other task must leave this file untouched.
```

- [ ] **Step 5: Run the tests to verify they pass**

```bash
free -g && ~/sbt/bin/sbt "testOnly m68k040.mmu.* m68k040.cache.* m68k040.frontend.FetchAlignResidentCadenceSpec"
python3 tools/socket/check_socket_netlist.py
```
Expected: the eight migrated DUTs' suites PASS; FREEZE PASSES against the **new** golden. `test-fast` as a whole still fails to compile (17 files remain) — Task 16.

- [ ] **Step 6: Commit**

```bash
git add -A src/main/scala/m68k040/top/FullCoreSynth.scala tools/socket/fullcore_ports.golden \
       src/test/scala/m68k040/mmu src/test/scala/m68k040/cache src/test/scala/m68k040/frontend \
       src/test/scala/m68k040/ls/DtlbMissFlushSpec.scala
git rm --cached src/test/scala/m68k040/socket/WalkerIdGuardSpec.scala 2>/dev/null || true
git commit -m "walker(W14/W18): top-level wiring, the 8 no-DcachePlugin DUTs, golden re-baseline

FullCoreSynth wires the twelve walker client hooks per TLB. exc.dcLoadRsp is
UNTOUCHED and no DUT is rewired on the response side -- routing is by the
ownership FIFO's head INSIDE LsEuPlugin (W14).

THE ONE INTENDED PORT-SURFACE CHANGE in this plan: the 78 itlbAxi_*/dtlbAxi_*
ports disappear with the masters, and tools/socket/fullcore_ports.golden is
re-baselined here, once, with the justification in its header. The diff was
verified to be EXACTLY those 78 lines and nothing else.

Eight DUT classes migrate to DcacheClientMemAgent. Their walkerAxi.ar-fire
counters retarget to walkLoadCmd fires -- and UmWriteSpec.scala:182 is NOT one of
them: it is a WAIT LOOP guarding a flush injection, retargeted as a wait
predicate, a different edit whose failure mode is a hang rather than a miscount.

WalkerIdGuardSpec deleted (W20) -- its subject no longer exists.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Task 16: The 17 remaining test files — full-core, lock-step, fuzz and LS-cluster DUTs (§8.1 buckets 2 and 3)

**This is the expensive part of the churn and the plan budgets it as such.** Spec §8.1's most consequential correction: the original inventory characterised the attached walker memory as "usually `sharedMem = <the same memory the D-cache uses>`", with non-shared memory as a rare exception. **The truth is the opposite.** Only **6 of 24** files pass `sharedMem` at *every* walker attach site (`exception/FsaveFrestoreSpec`, `exception/ExceptionStoreDrainArbSpec`, `execute/FpuControlWiringSpec`, `fuzz/PortedTestRunner`, `fuzz/P27HangTraceSpec`, `ls/PreciseDrainIrqRaceSpec`), and `lockstep/ExecuteLockStepSpec` — by far the largest single site, 38 references across 19 attach pairs — passes `sharedMem` at only **5 of its 19** sites.

So the **dominant** case is a *separate, non-shared, all-zero* page-table memory attached to the walker. **Deleting those attach lines is a genuine behaviour change per site** — those DUTs read all-zero descriptors today and will read the shared D-cache memory afterwards — and each needs individual review and, in many cases, a page-table image planted where none existed. **Do not treat this as a find/replace.**

**Files:** the 12 full-core/lock-step/fuzz DUTs and the 5 LS-cluster DUTs listed in this plan's File Structure table.

**Interfaces:**
- Consumes: Task 15's `FullCoreSynth` wiring (these DUTs mostly build full cores, so most of them get the walker path for free once the attach lines go).
- Produces: nothing new by name.

- [ ] **Step 1: Build the per-site inventory before editing anything**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
grep -n "walkerAxi\|itlbAxi\|dtlbAxi" $(grep -rln "walkerAxi\|itlbAxi\|dtlbAxi" src/test/) \
  > /home/qwertyoruiop/tmp/claude-1000/-home-qwertyoruiop-m68k-core-040-ooo/374c5f2c-f0cd-4208-8a9e-23690599d409/scratchpad/walker-sites.txt
```
For **each** site record, in the progress ledger, a three-column row: `file:line` | `sharedMem passed? (yes/no)` | `disposition`. There are only three admissible dispositions and every site takes exactly one:

- **(A) Shared-memory site — cheap deletion.** The agent was attached with `sharedMem = <the D-cache's memory>`, so the page tables already live in the memory the D-cache serves. **Delete the attach line.** Behaviour is unchanged by construction.
- **(B) Non-shared site whose test never actually walks.** The MMU is off for the whole test, or no translation ever misses. **Delete the attach line** and record *why the walker is unreachable in this test* — that sentence is the evidence, and without it the site is indistinguishable from (C).
- **(C) Non-shared site whose test DOES walk.** The DUT read all-zero descriptors from a private memory and will now read the shared memory. **Plant a page-table image in the shared memory** that reproduces the translation the test was getting (all-zero descriptors mean non-resident, so most of these tests were exercising the fault path — reproduce *that*, not a resident mapping, unless the test's assertions say otherwise). **Re-run and diff the test's own assertions**, not just pass/fail.

- [ ] **Step 2: Migrate bucket 2 (12 full-core / lock-step / fuzz DUTs), file by file**

Order them cheapest-first so the expensive one lands with the pattern already proven:

1. The five all-shared files — `exception/FsaveFrestoreSpec`, `exception/ExceptionStoreDrainArbSpec`, `execute/FpuControlWiringSpec`, `fuzz/PortedTestRunner`, `fuzz/P27HangTraceSpec` — are disposition (A) throughout. Delete the attach lines. Run each file's suite.
2. The four small trace DUTs — `fuzz/Cmp2HangTraceSpec`, `fuzz/EoriAddaDecodeTraceSpec`, `fuzz/MiHangTraceSpec`, `fuzz/WildPcA7TraceSpec` (2 references each) — classify per Step 1, then edit. These are single-program replay harnesses, so (B) is likely and the *evidence sentence* is what makes it safe.
3. `bench/IpcBenchSpec` (`:593-594`) — **plain `attachFull(dut.dtlb.walkerAxi, cd, memCfg)` / `attachFull(dut.itlb.walkerAxi, …)` with NO `sharedMem` argument at all.** The original spec draft cited this as a `sharedMem` example and **withdrew the citation**. Classify honestly: an IPC benchmark that runs with the MMU enabled is disposition (C) and its **IPC numbers may legitimately move**, which matters because Task 19 reads them.
4. `fuzz/FuzzLockStepSpec`.
5. **`lockstep/ExecuteLockStepSpec` last** — 38 references, 19 attach pairs, `sharedMem` at only 5 of them. Work through it pair by pair, running the affected tests after each. This is the primary correctness net for the whole plan (§10.3) and a silent behaviour change here is the single most expensive mistake available.

- [ ] **Step 3: Migrate bucket 3 (5 LS-cluster DUTs)**

`ls/DtlbCrossPageSplitSpec`, `ls/DtlbMissFlushSpec` (its **second** DUT, `DtlbFlushReuseSpec` — the first was done in Task 15), `ls/DtlbViptChangedVpnSpec`, `ls/LsEuFastPreciseSpec`, `ls/PreciseDrainIrqRaceSpec`. These **do** host `DcachePlugin`, so they need no `DcacheClientMemAgent` — only the attach-line disposition and, where the DUT does not already instantiate `LsEuPlugin` + both TLBs wired together, the walker hook wiring copied from Task 15's `FullCoreSynth` block.

`ls/PreciseDrainIrqRaceSpec` is disposition (A) throughout. The other four need per-site classification.

- [ ] **Step 4: Verify nothing references the deleted surface**

```bash
grep -rn "walkerAxi\|itlbAxi\|dtlbAxi" src/ tools/ docs/superpowers/plans/2026-08-18-walker-dcache-passthrough-implementation-plan.md
```
Expected: **no hits in `src/`**. Hits in `tools/socket/fullcore_ports.golden`'s header comment and in this plan/the design spec are expected and correct.

- [ ] **Step 5: Run the full fast suite**

```bash
free -g && make SBT=~/sbt/bin/sbt test-fast
python3 tools/socket/check_socket_netlist.py
```
Expected: **compiles**, and the pass/total matches Task 1's baseline. Any test that changed result is a **finding** and must be triaged against its Step-1 disposition before this task is done — a disposition-(C) site whose assertions moved means the planted page-table image does not reproduce the old translation.

- [ ] **Step 6: Commit**

```bash
git add -A src/test
git commit -m "walker(section 8.1): migrate the remaining 17 test files off walkerAxi

The DOMINANT case is the one the original inventory filed as rare: only 6 of 24
files pass sharedMem at EVERY walker attach site, and ExecuteLockStepSpec -- by
far the largest, 19 attach pairs -- shares at only 5 of them. Deleting a
non-shared attach line is a genuine behaviour change per site: those DUTs read
all-zero descriptors from a private memory today and read the shared D-cache
memory afterwards. Every site is classified (A) shared / (B) never walks, with
the reason recorded / (C) walks, page-table image planted, and the per-site
inventory is in the progress ledger.

IpcBenchSpec:593-594 passes NO sharedMem argument -- the original draft's
citation of it as a sharedMem example was withdrawn -- so its IPC numbers may
legitimately move; Task 19 reads them.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Task 17: The coherency acceptance tests — both directions (§10.1, §1.1, §1.2)

The bug this whole plan exists to fix, tested directly. **Both** halves: the documented write-side bug (§1.1) and the **undocumented read-side bug** (§1.2), which no existing test covers and which the write-side fix does nothing for.

**Files:**
- Create: `src/test/scala/m68k040/mmu/WalkerDescriptorCoherencySpec.scala`
- Modify: `.superpowers/sdd/progress-walker-dcache-passthrough-2026-08-18.md`

**Interfaces:**
- Consumes: the full-core DUT construction from Task 16's migrated `lockstep`/`ls` specs.
- Produces: the acceptance verdict Task 19 reports.

- [ ] **Step 1: Write the failing tests**

```scala
package m68k040.mmu

import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** Section 10.1: the acceptance tests for the coherency bug this design exists to close.
  *
  * BOTH tests must be shown to FAIL AT HEAD (before the design lands) or they prove
  * nothing -- that is the evidence standard section 10.1 explicitly requires. Run them in
  * a `git worktree` at the plan's parent commit to establish that, never by checking out
  * in the shared tree.
  *
  * Deliberately UNTAGGED so `test-fast` runs them. */
class WalkerDescriptorCoherencySpec extends AnyFunSuite {

  test("section 1.2 (READ side, previously UNDOCUMENTED): a walk sees a COPYBACK-dirty descriptor") {
    // TableWalker used to issue every descriptor read on its own AXI master straight to
    // backing memory. If the descriptor's line is COPYBACK-dirty in L1D -- which needs
    // nothing more exotic than an ordinary supervisor `move.l` into page-table memory that
    // has not yet evicted -- the walk read STALE BACKING MEMORY while the newer data sat
    // only in L1D, and built a translation from a descriptor the program had already
    // overwritten.
    withFullCoreDut("read-side-coherency", seed = 1) { dut =>
      enableMmu(dut); setCacrDe(dut, true)
      mapPageTableMemoryAsCopyback(dut)
      // Supervisor stores rewrite a LEAF descriptor, leaving the line DIRTY in L1D and
      // deliberately NOT evicted.
      val newPpn = 0x0BEEF
      runSupervisorStoresRewritingLeafDescriptor(dut, vpn = 0x33333, ppn = newPpn)
      assert(lineIsDirtyResident(dut, leafDescLineFor(0x33333)),
        "the stimulus evicted the line; the test would pass vacuously")
      // Now touch a VA that forces a walk of that descriptor.
      touchVaForcingWalk(dut, vpn = 0x33333)
      assert(resolvedPpnFor(dut, 0x33333) == newPpn,
        "the walk resolved from STALE BACKING MEMORY, not from the dirty L1D line")
    }
  }

  test("section 1.1 (WRITE side): a U/M writeback to a dirty resident line is not lost on eviction") {
    // If the descriptor's line is resident COPYBACK-dirty, the eventual eviction used to
    // write the OLDER cached image back over the walker's update: a genuine LOST UPDATE,
    // not mere staleness. That is the observed mmu_atc_write_hit_sets_modified shape.
    withFullCoreDut("write-side-coherency", seed = 2) { dut =>
      enableMmu(dut); setCacrDe(dut, true)
      mapPageTableMemoryAsCopyback(dut)
      val descAddr = leafDescAddrFor(0x44444)
      makeLineDirtyResident(dut, descAddr)
      performWriteAccessForcingUmUpdate(dut, vpn = 0x44444)
      forceEvictionOf(dut, descAddr)
      val b = peekBackingMemoryByte(dut, descAddr + 3)   // task #194: the numeric low byte
      assert((b & 0x08) != 0, f"U bit lost on eviction: byte = 0x$b%02x")
      assert((b & 0x10) != 0, f"M bit lost on eviction: byte = 0x$b%02x")
    }
  }

  test("section 1.1 (WRITE side): a U/M writeback to a CLEAN resident line is visible to a later read") {
    // The clean-resident case: L1D silently held the PRE-update byte and a later read of
    // that descriptor -- by a subsequent walk, or by ordinary supervisor code reading its
    // own page tables -- hit the stale copy. Under WRITETHROUGH the update lands in BOTH
    // places in EVERY residency state, with no dependence on eviction ordering.
    withFullCoreDut("clean-resident", seed = 3) { dut =>
      enableMmu(dut); setCacrDe(dut, true)
      val descAddr = leafDescAddrFor(0x55555)
      makeLineCleanResident(dut, descAddr)
      performWriteAccessForcingUmUpdate(dut, vpn = 0x55555)
      assert((supervisorLoadByte(dut, descAddr + 3) & 0x18) == 0x18,
        "an ordinary supervisor read of the descriptor saw the pre-update byte")
    }
  }
}
```

- [ ] **Step 2: Demonstrate both FAIL at the plan's parent commit**

```bash
git worktree add /home/qwertyoruiop/tmp/.../scratchpad/wt-walker-headbug <plan parent commit>
# copy this spec in (it will need the pre-migration DUT surface), run it, record the failures
```
Spec §10.1: *"This test must be shown to fail at HEAD before the fix, or it proves nothing."* Record both failure signatures in the progress ledger. If a test cannot be made to fail at the parent commit, **that is a finding about the test**, not a licence to skip it.

- [ ] **Step 3: Run the acceptance test for the tracked bug**

```bash
free -g && ~/sbt/bin/sbt "testOnly m68k040.mmu.WalkerDescriptorCoherencySpec"
# then the ported-corpus case:
~/sbt/bin/sbt "testOnly *PortedTestRunner* -- -z mmu_atc_write_hit_sets_modified"
```
Expected: all three new rows PASS, and `mmu_atc_write_hit_sets_modified` moves **fail → pass**.

**If it does NOT pass**, spec §7 item 6 fixes what that means and the plan must not improvise: *"the residual is `mmu-atc-m-bit-tracking`'s own M-bit logic fix (`c629bec`), which was blocked on a synth gate — not new design work here."* Record the residual, confirm the three directed rows above still pass (they test the coherency half this plan owns), and hand the M-bit-logic half back to that branch. **Do not widen this plan's scope to chase it.**

- [ ] **Step 4: Commit**

```bash
git add src/test/scala/m68k040/mmu/WalkerDescriptorCoherencySpec.scala \
        .superpowers/sdd/progress-walker-dcache-passthrough-2026-08-18.md
git commit -m "walker(section 10.1): coherency acceptance -- both directions

The READ side (section 1.2) is the previously UNDOCUMENTED half: a walk used to
read stale backing memory while the newer descriptor sat COPYBACK-dirty in L1D,
which needs nothing more exotic than an ordinary supervisor move.l into
page-table memory. No existing test covered it and the write-side fix does
nothing for it.

The WRITE side (section 1.1) covers both residency states: the dirty-resident
LOST UPDATE (the mmu_atc_write_hit_sets_modified shape) and the clean-resident
staleness. Under WRITETHROUGH the update lands in BOTH places in EVERY state,
with no dependence on eviction ordering -- the bug is closed BY CONSTRUCTION.

Both were demonstrated FAILING at the plan's parent commit in a worktree before
being demonstrated passing here, per section 10.1's evidence standard.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Task 18: The socket plan and spec rework (W22, §9.3 items 1-4)

Four edits, **none optional**. Leaving the socket plan claiming a 4-owner arbiter while the netlist has 2, or the socket spec claiming D19 evidence that this work deleted, is precisely the stale-plan drift this project's process exists to prevent.

**Files:**
- Modify: `docs/superpowers/plans/2026-08-18-axi-socket-adapter-implementation-plan.md` (insert Task 5R after Task 5, which ends at the `## Task 6` heading at `:2093`; re-point `:4717` and `:4855`)
- Modify: `docs/superpowers/specs/2026-08-18-axi-socket-adapter-design.md` (a new addendum block in the style of the two existing "Added by the …" blocks at `:79` and `:92`)

**Interfaces:**
- Consumes: Task 2's implemented shrink (Task 5R *documents* it; it does not re-do it).
- Produces: no code.

- [ ] **Step 1: Insert `Task 5R` into the socket plan**

Immediately before `## Task 6: MmioCover…` (`:2093`), stating that **Task 5's merged output no longer matches its own spec sections**, and carrying the W21 shrink as *already implemented* by this plan's Task 2 (with its commit hash), so a reader of either document reaches the same netlist. It must name: read 4 owners → 2; write 3 owners → 1 pass-through; the read-side D20 watchdog kept; the **write-side watchdog deliberately dropped as a stated deviation**; `io.wedgeIsRead` tied `True`; the §4.4 assertion block pruned; and the two named consequences from spec §9.2.

- [ ] **Step 2: Re-point Task 13's two stale citations**

`…-axi-socket-adapter-implementation-plan.md:4717` and `:4855` both cite `itlbAxi_aw_payload_len` as a netlist-naming example. That signal ceases to exist. Replace both with `DcachePlugin_logic_axi_aw_payload_len`. Task 13's *interface shape* is unaffected (§9.2 confirms `M68kSocketTop` never references `itlbAxi`/`dtlbAxi` in its RTL) — this is a citation fix, not a design change.

- [ ] **Step 3: Add the addendum block to the socket design spec**

In the style of the two existing blocks, recording per D-number:

- **D7** — its forcing constraint is **dissolved**: the walkers no longer emit AXI, so there is no third and fourth master to merge.
- **D8** — the ITLB-vs-DTLB disambiguation argument is **moot** for the same reason.
- **D9** — its write-side half is **vacuous**, and — separately and importantly — **its deadlock argument does not transfer down a layer**. At the `DcacheService` layer no client can hold `DcachePlugin`'s own AXI write channel, so a combined read+write token would block only SQ-drain *admission*, never descriptors already in flight: a **throughput** cost, not a cycle. (W5's per-direction independence rests on its own throughput grounds — a walker's read phase and its U/M write phase are separated by an entire ROB commit — **not** on D9.)
- **D10** — unchanged.
- **D19** — **RE-ESTABLISHED for a new consumer.** D19's evidence list explicitly names `TableWalker.scala:114`, and this work **deletes that exact site**; socket spec §12 requires the invariant to be *re-checked, not assumed*, by any work that changes a response consumer. The replacement evidence is W16: `DcachePlugin`'s refill R consumer (already on D19's list) terminates the walk's transaction, and `loadRsp.fault` carries that termination to the walker's FSM → `NON_RESIDENT` → the existing `FINISH` path. Strictly **stronger** than the site it replaces, since today a walker read's AXI error is silently ignored.
- **D20** — the **read-side** watchdog survives; the **write-side watchdog is deliberately dropped**, a stated deviation from D20-as-written (which requires one per direction), justified because D20's own stated scope is the wedge mode the *merge* creates and that mode is structurally absent at one owner. D20's *observability posture* is additionally **carried down a layer** by W26.
- **D27** — the walkers' AXI sites are **removed with their host**; the principle (fail closed, never fail open, on an unrecognised response) **relocates** into W13's `stOwner` demux and the existing `storeAck` id-demux inside `DcachePlugin`. `WalkerIdGuardSpec` is deleted; `38e6c31`/`56f2438` are **not reverted**, because the `RESET_VEC` ARID half survives and socket Task 9 still needs it.

- [ ] **Step 4: Verify no other stale citation survives**

```bash
grep -rn "itlbAxi\|dtlbAxi\|walkerAxi\|WalkerIdGuardSpec" docs/superpowers/plans/ docs/superpowers/specs/ \
  | grep -v walker-dcache-passthrough
```
Every remaining hit must be inside a passage that is *explicitly describing the pre-rework state* (Task 4's and Task 5's own historical text, which stays as the record of merged work). Anything that reads as a **current** statement must be corrected.

- [ ] **Step 5: Commit**

```bash
git add docs/superpowers/plans/2026-08-18-axi-socket-adapter-implementation-plan.md \
        docs/superpowers/specs/2026-08-18-axi-socket-adapter-design.md
git commit -m "docs(walker/W22): socket plan Task 5R + spec addendum for D7-D10/D19/D20/D27

Task 5 is ALREADY MERGED, so leaving its plan claiming a 4-owner arbiter while
the netlist has 2 is exactly the stale-plan drift this process exists to prevent.
Task 5R records the shrink; the spec addendum records that D7's forcing
constraint is dissolved, D8 is moot, D9's write half is vacuous AND its deadlock
argument does not transfer down a layer, D19 is RE-ESTABLISHED for a new consumer
(its evidence list named TableWalker.scala:114, which this work deletes), D20's
write-side watchdog is deliberately dropped while its observability posture is
carried down a layer by W26, and D27's principle relocates rather than
disappearing.

Task 13's two itlbAxi_aw_payload_len citations re-pointed at a surviving net.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Task 19: Final gate — synth checkpoint #3, the regression sweeps, and the deferred derivations

**Files:**
- Modify: `.superpowers/sdd/progress-walker-dcache-passthrough-2026-08-18.md`
- Possibly modify: `src/main/scala/m68k040/execute/LsEuPlugin.scala` (only if Step 5 re-derives `walkerAgeLimit`)

**Interfaces:**
- Consumes: every earlier task; Task 1's baselines; Task 11's post-mux checkpoint.
- Produces: the plan's verdict.

- [ ] **Step 1: Confirm the machine is uncontended, then run synth checkpoint #3**

```bash
pgrep -a vivado; pgrep -af 'jtag|xsdb|hw_server'; free -g
git worktree add /home/qwertyoruiop/tmp/.../scratchpad/wt-walker-final HEAD
# make verilog + the same OOC flow, same POSTROUTE_ROUNDS, as Tasks 1 and 11
```
Compare against **both** Task 1's baseline and Task 11's post-mux checkpoint, so any movement is attributable to the arbitration layer or to the MMU side separately. Apply spec §5's lever list in order if it regressed; **reverting the design is not a lever**.

- [ ] **Step 2: Run the mandatory regression sweeps (§10.3), Vivado now idle**

All in a `git worktree`, never the working tree:

```bash
free -g
make SBT=~/sbt/bin/sbt test-fast                       # vs Task 1's count
~/sbt/bin/sbt "testOnly m68k040.lockstep.ExecuteLockStepSpec"   # the primary correctness net
# then the full ported-corpus sweep and the full fuzz sweep, diffed against Task 1
```
Spec §10.3 requires the diff to be against **Task 1's fresh baseline**, not against the recorded historical 713/728 — that record is 9-28 days stale and predates the FPU, frontend-restructure and FMax campaigns, so diffing against it would attribute pre-existing drift to this work.

`ExecuteLockStepSpec` is called out by name as the primary net because it already attached walker memory agents at 19 sites and therefore exercises the new path on **every** MMU-enabled test.

- [ ] **Step 3: Record the IPC evidence for §4.2.3 caveat (b)'s trade**

Run `bench/IpcBenchSpec` and compare against Task 1. Spec §4.2.3 caveat (b) states the trade honestly in both directions and says §10.3's evidence *"is what settles whether the trade is net positive on real workloads"*: latency improves (a walker no longer waits for a live load stream to empty), while **probe-destruction frequency rises** (a walker now interleaves into a *live* stream, so each of a walk's up to three descriptor reads can destroy up to 4 resident early probes). Record the measured direction. Note that `IpcBenchSpec`'s own numbers may also have moved for the Task-16 disposition-(C) reason.

- [ ] **Step 4: Re-derive or deliberately retain `WALKER_AGE_LIMIT` (non-blocking)**

Spec §4.2.4: the 64 was derived against the **drain-to-zero** cost model, and under the ownership FIFO the `force` path reverts to being a rare fallback rather than the common case — so the limit *could likely be smaller*. The spec deliberately declines to name a replacement, because picking one without re-deriving it against the FIFO model would just be a different unjustified constant.

Now that `WalkerDcachePortArbSpec` can measure the real grant-latency distribution, either re-derive it or **record that 64 is retained on purpose**. Either outcome is acceptable; **silently keeping 64 with no statement is not** — an unchanged number with a changed reason is exactly the drift §12's ambiguity check exists to catch. This step is **non-blocking**: no finite value can break the starvation bound, which is stated *in terms of* the limit.

- [ ] **Step 5: Confirm the 68040 UM wording on table-search cacheability (non-blocking)**

Spec §4.1.4 flags this as its one **unverified claim**: no MC68040 User's Manual text on table-search cacheability was findable in this repository, so W1 is justified on this core's own mechanics (§4.1.2) and is merely *consistent with* — not derived from — the widely-reported behaviour of performing table searches as cachable write-through accesses. If the UM is available, confirm the wording and record the section number in the ledger. **If the UM says otherwise, only §4.1.4's constant changes, not the architecture.**

- [ ] **Step 6: Run the W27 completeness sweep one last time**

```bash
grep -cn "dcache\.\(loadCmd\|store\|loadRsp\|storeAck\|storeErr\|loadProbe\)" \
  src/main/scala/m68k040/execute/LsEuPlugin.scala
```
Confirm the count matches Task 7 Step 8's recorded number plus any row a later task added, and that each addition carries a written disposition. Confirm also that `src/main/scala/m68k040/cache/DcachePlugin.scala` is **byte-identical to the plan's parent commit**:

```bash
git diff <plan parent commit> -- src/main/scala/m68k040/cache/DcachePlugin.scala \
                                 src/main/scala/m68k040/ls/StoreQueue.scala \
                                 src/main/scala/m68k040/cache/IcachePlugin.scala
```
Expected: **empty**. This is W4's central risk-reduction claim and the plan's own Global Constraint; a non-empty diff here invalidates §5's whole FMax argument.

- [ ] **Step 7: Write the final verdict and commit**

Record in the ledger: the three synth checkpoints side by side; the four sweep results vs baseline; the acceptance verdict for `mmu_atc_write_hit_sets_modified` and both §10.1 directed tests; the `WALKER_AGE_LIMIT` decision; the UM confirmation status; the `DcachePlugin` byte-identity check; and — explicitly — **whether the standalone §11.1-item-9 fix landed before or after this plan**, and what that means for closing that task.

```bash
git add .superpowers/sdd/progress-walker-dcache-passthrough-2026-08-18.md \
        src/main/scala/m68k040/execute/LsEuPlugin.scala
git commit -m "walker: final gate -- synth checkpoint #3, regression sweeps, deferred derivations

Three-checkpoint synth comparison (baseline / post-mux / post-MMU) so any FMax
movement is attributable. Full test-fast, ExecuteLockStepSpec, ported-corpus and
fuzz sweeps diffed against Task 1's FRESH baseline rather than the 9-28 day stale
historical record. IPC measured for section 4.2.3 caveat (b)'s honestly-stated
trade. WALKER_AGE_LIMIT re-derived or deliberately retained with a statement.
DcachePlugin.scala / StoreQueue.scala / IcachePlugin.scala confirmed
byte-identical to the plan's parent commit -- W4's central claim and the
foundation of the whole section 5 FMax argument.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Self-Review

Run against the spec with fresh eyes after the plan was complete. Findings were fixed **inline** above; they are recorded here rather than left as open items.

### 1. Spec coverage — every DECIDED item maps to a task

| Item | Task(s) | Item | Task(s) |
|---|---|---|---|
| **W1** fixed `CACR.DE ? WT : INHIBITED` policy | 7 (+ `WalkerCacheModeSpec`) | **W16** `TableWalker` conversion, `selectWord` deleted, D19 | 13 |
| **W2** stamped by the mux, not on `WalkReq` | 7 | **W17** U/M writeback as `DStoreCmd`, `drainArmed` | 14 |
| **W3** one mode for both halves | 7 (structural: one signal) | **W18** `DcacheClientMemAgent`, 8 files / 8 DUTs | 12, 15 |
| **W4** extend the `LsEuPlugin` mux, `DcachePlugin` untouched | 7 (verified byte-identical in 19) | **W19** `quiesceHold` over `S_DRAIN\|\|S_APPLY` | 9 |
| **W5** independent per-direction ownership | 6, 7 | **W20** Task 4's code dies with its host; `AxiIds` kept | 2, 14, 15 |
| **W6** `CORE` is one arbiter owner | 4, 7 | **W21** `AxiDMerge` shrink | 2 |
| **W7** load FIFO / store drain-to-zero | 4 (load), 6 (store) | **W22** separate plan, Task 5R, addendum | 18 |
| **W8** aging + `force`, `WALKER_AGE_LIMIT` | 7 (+ re-derivation in 19) | **W23** `:2115`, `:2027`, the set term | 4 |
| **W9** ITLB/DTLB round-robin | 7 | **W24** `sq.io.drain.ready` | 6 |
| **W10** no bounded-grant timer, sim assertion kept | 10 | **W25** reserved walker tokens + doc comment | 3 (constants), 7 (use), 8 (collision test) |
| **W11** early-probe deadlock | 5 (suppression), 8 (cancel) | **W26** production wedge counter + halt kind | 10 |
| **W12** FMax mitigation, three checkpoints | 1, 11, 19 | **W27** the exhaustive 34-row table | 4, 5, 6, 7 (+ sweeps in 7.8 and 19.6) |
| **W13** store-response demux | 6, 7 | **W28** same-cycle owner coherence (a)(b)(c) | 4 (a, c-load), 6 (c-store), 7 |
| **W14** no bundle demux, no top-level rewiring | 15 (verified) | **W29** the four-valued push tag | 4, 7 |
| **W15** plugin-level `var` hooks | 7 (LS side), 14 (MMU side) | **W30** tagged clear + `ldOwnerFifoEmpty` | 4 |

**Recorded non-decisions.** **N1** (non-atomic descriptor RMW) and **N3** (the pre-existing `umQueueFull` cycle) are unchanged by this plan and no task touches them — correctly, since both predate it. **N2** (walker reads now allocate L1D lines) is an accepted consequence of W1 and is what makes repeat walks cheap; Task 19 Step 3's IPC measurement is where it would show up. **N4** (a walker load *and* a walker U/M store are both refused for a whole maintenance walk — a bounded stall, not a deadlock) is covered on the liveness side by Task 9's second directed test ("the maintenance walk completes"), and W26's bound in Task 10 is explicitly sized against it.

**§10 verification obligations.** §10.1 → Tasks 17 and 1 Step 6. §10.2's ten rows → Tasks 2 (`AxiDMergeSpec`), 3, 4, 5, 6, 7, 8, 9, 10, 12, 13, 14, 15. §10.3's four sweeps → Task 1 (baseline) and Task 19 (diff). §10.4 → Tasks 1, 11, 19.

### 2. Gaps found and how they were resolved

- **The port-surface FREEZE checker directly conflicts with this design, and the spec does not mention it.** `tools/socket/fullcore_ports.golden` pins 205 ports, 78 of which are `itlbAxi_*`/`dtlbAxi_*`, and the socket plan's Global Constraint forbids moving that surface. Deleting `walkerAxi` necessarily moves it. **Resolved** by making the change *explicit and singular*: Task 15 is the one task that intends it, regenerates the golden with `--regen`, verifies the diff is exactly those 78 lines and nothing else, and records the justification in the golden's own header. Every other RTL task must leave the checker green against the unchanged golden, and this is written into Global Constraints.
- **`AxiDMergePlugin` would stop compiling at the MMU conversion.** It reads `it.walkerAxi`/`dt.walkerAxi`. **Resolved** by sequencing the socket Task 4/5 rework **first** (Task 2) rather than last: it removes those references, so Tasks 13/14 need no bridging tie-off. That also drops the now-unreachable `socketMerged` flag from the two MMU plugins before its subject disappears.
- **`ldOwner` under W28's registered-grant shape is one cycle behind the request set.** The spec writes `walkerLoadAdmit(i) = grant(i) && …`, which presupposes a granted walker is still requesting. It may not be. **Resolved** in Task 7 by ANDing in the walker's own `valid` — strictly stronger than the spec's formula, pairwise exclusivity unchanged — and the refinement is **recorded in the plan and in the source**, not made silently.
- **The store owner cannot be reloaded unconditionally every cycle.** W28 clause (c) prescribes `stOwner := stGrantNext` every cycle with no hold. But a walker's U/M store drops its own `valid` the moment it is accepted (it is single-outstanding, waiting for the ack), so an unconditional reload would revert the owner to `CORE` and demux the walker's ack to the `StoreQueue` — tripping `StoreQueue.scala:518`. **Resolved** in Task 6 with `stOwner := Mux(stBusyAny, stOwner, stGrantNext)`, which is *not* a separate latch: it is a pure function of the current-cycle arbiter decision and the outstanding counters, i.e. exactly where clause (c) itself says store-side persistence lives. The load side needs no such term because the FIFO carries it.
- **W9's "flip on each walker grant" is ambiguous and one reading oscillates.** Under registered-grant, `ldOwner` names a walker on *every* cycle it holds the port, so flipping on "grant" would toggle every cycle. **Resolved** by flipping on the walker's actual `loadCmd.fire` — the grant being *used* — which is the only once-per-grant event that is well defined. Recorded in Task 7's source comment.
- **The one-hot assertion reads `coreLsLoadAdmit` a task before it has a driver.** Task 4 declares it and Task 5 drives it, so Task 4 alone would fail elaboration on an undriven `Bool`. **Resolved** by an explicit implementer note at the end of Task 4 Step 7: drive it from the temporary expression `Mux(useSplitCmd, llReg.valid, alignedSendValid)` at the mux site, which Task 5 then replaces.
- **`stGrantNext` was declared twice.** Task 6 needs it for `stOwner`'s update rule and Task 7 builds the arbiter that drives it. **Resolved**: Task 6 declares it inside `dcArb` and drives it to `CORE`; Task 7 only *replaces the drive*. Called out in both tasks.
- **`rspOwner` is declared by W29 property 3 but never read by any prescribed RTL.** W29 requires it be *derived*, never stored, and the rows that genuinely mean "not a walker" now use the stronger four-valued tag instead. **Resolved** by keeping it as a one-line derived, `simPublic` observability signal that Task 7's W28(a) directed test reads as `qualifiedOwner(dut)` — it earns its place as the check's subject rather than as dead RTL. The same applies to `ldOwnerFifoHoldsWalker`, which after W30 is read only by the W7(a) directed test and the superseded-assertion comments; both are noted as observability, not logic.
- **`WalkerCacheModeSpec` needed an obligation the spec states only in prose.** The standing no-SoC-address-map rule makes "the mode is a fixed policy constant" a *testable* claim. **Resolved** by adding a fourth test that sweeps 32 descriptor addresses and asserts the mode does not vary with the address — so a future edit that reaches for an address range fails a test rather than a review.

### 3. Type and name consistency

Checked across all 19 tasks against the **Naming contract** table. The three encodings that spec §4.2.3's round-5 correction exists to keep apart — `DcPortOwner` (three-valued arbiter owner, `CORE=0`), `LdRspTag` (four-valued response identity, `CORE_LS=0`), and `WalkerIdx` (positional vector index, `ITLB=0`) — are declared once, in one place, with comments saying they are different numbers, and every task uses the qualified form (`U(LdRspTag.ITLB, 2 bits)`, `WalkerIdx.ITLB`) rather than a bare literal.

Cross-task signatures verified: `walkLoadCmdPaddr` is `UInt(32 bits)` in `TableWalker.io` (T13), the MMU plugins' hooks (T14), `LsEuPlugin`'s `Vec` (T7) and `DcacheClientMemAgent`'s constructor (T12); `walkLoadRspData` is `Bits(32 bits)` in all four; `walkStoreStrb` is `Bits(16 bits)` and `walkStoreLineData` is `Bits(128 bits)` in all four, matching `DStoreCmd.strb`/`lineData`. `ExceptionUnit.quiesceHold` (T9) matches `LsEuPlugin.quiesceHold` (T7) and the wiring (T9). `HaltReason.WALKER_PORT_WEDGE` (T10) is read by `FullCoreSynth` (T10) and asserted by `HaltReasonSpec` (T10). `DLoadToken.WALK_ITLB`/`WALK_DTLB` (T3) are read by the mux (T7) and by `DLoadTokenSpec` (T3). `DcacheClientMemAgent.loadCmdFires`/`storeFires` (T12) are the exact names Task 15's counter migration uses.

### 4. Placeholder scan

No `TBD`, `TODO`, `XXX`, `???`, `<fill in>` or "implement later" survives in any step. The `<fill>` markers inside Task 1's ledger **template** are deliberate — that is a form the executor fills with measurements, and the template is the deliverable. Every code step carries a real code block; every "similar to Task N" temptation was resolved by repeating the code (Task 15's twelve wiring lines are written out for both TLBs rather than looped, and the same reason is given). The two values the spec deliberately left to the plan are both **derived here, not deferred**: W26's wedge bound (Task 10, derived to 65536 from N4's 512-iteration maintenance walk) and W8's `WALKER_AGE_LIMIT` (retained at 64 with the spec's own justification, with an explicit non-blocking re-derivation step in Task 19 that requires a *statement* either way).

