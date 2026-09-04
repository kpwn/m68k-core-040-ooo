# Inverting the lock-step loop, and porting `NaxAllocatorChecker`

**Date:** 2026-09-04
**Scope:** items **2** and **4** of the ranked list in
`2026-09-04-naxriscv-architecture-comparison.md` (commit `87d4089`). Test/verification
infrastructure only; no RTL logic changed.
**Reference source:** `/home/qwertyoruiop/m68k-ooo-v2/thirdparty/NaxRiscv` (read-only).

---

## 0. Summary of what this actually bought

| | before | after |
|---|---|---|
| Fuzz corpus | 3 divergences / 200 (seeds 80, 109, 127) | **3 / 200, same seeds, same details** |
| `ExecuteLockStepSpec` | 460 pass, 2 pre-existing failures | 460 pass, same 2, **+2 real findings** |
| `make test-fast` | 337/337 (with a known `debugPcApply` flake) | 335/337 under load, **337/337 on re-run** |
| Registers compared per retire | whatever the DUT volunteered (1, sometimes 2) | **all 16 + CCR, structurally** |
| Architectural store stream | never compared (the fields were dead) | **compared byte-for-byte and in order** |
| Rename freelists checked | none | **all five, every cycle** |

Three defects found, all previously invisible:

1. **REAL RTL DEFECT — `CHK`/`CHK2` condition codes are stacked but never committed.**
   The handler runs on the *previous* instruction's flags. `docs/BUG_chk_chk2_flags_not_committed_on_trap.md`.
2. **A test whose stated invariant was never checked** — `"MOVEC CACR read -> 0 (RAZ)"`
   compared nothing, because MOVEC's destination register was never compared. The DUT
   returned `0x80008000` and the oracle `0`, and the test passed for its whole life.
3. **Two documented always-write simplifications made bus-observable** for the first time
   (CAS/CAS2 on a failed compare; the bit-field RMW spill byte). Both design docs said
   these were invisible "to an external bus observer we don't model". We now model it.

---

## 1. Why the old loop could not see any of that

`LockStep.compare` iterates **DUT** records and gates the register check on the DUT's own
`archRegValid`. Its own header says so (`LockStep.scala:18-19`):

> *"Register check is gated on `archRegValid`: a DUT that under-reports validity can hide a
> wrong register. **This is the trust model, not full coverage.**"*

and at `:20-21`:

> *"Memory writes (memAddr/memData/memWrite) are captured but **NOT yet compared**."*

The second is worse than advertised. The fields were never *populated* either —
`WhiteboxCapture` hardcodes `memAddr = 0, memData = 0, memWrite = false`. There was nothing
to un-discard.

NaxRiscv inverts the first: `main.cpp:2193-2210` iterates Spike's `log_reg_write` and asserts
`"INTEGER WRITE MISSING"` when the DUT reports no write. That is item 2's brief.

---

## 2. What was built

### 2.1 `ArchLockStep` — reference-driven, full-state

`src/test/scala/m68k040/lockstep/ArchLockStep.scala`. The loop is `for (i <- oracle.indices)`.
Per oracle step it runs, in order:

1. the **reference write set** (registers the oracle changed since step i-1) — this is
   NaxRiscv's check, kept purely because `"INTEGER WRITE MISSING/WRONG D3"` localises a
   failure far better than "some register differs";
2. a **full 16-register compare** — which also catches an *unexpected* DUT write, something
   the write-log check structurally cannot;
3. **CCR**, and 4. the **SR system byte**.

We can go further than NaxRiscv here because our oracle is richer: `OracleStep` carries the
whole architectural state per step where Spike's sits behind an API.

### 2.2 `ArchStateProbe` — the DUT side, read structurally

`intPrf[CommittedMapService.intPhys(i)]` for i ∈ 0..15, plus the committed NZVC and X
physical registers for the CCR. `RegFilePlugin.logic.shadow` already existed as a sim-only
mirror of the PRF (the real `ram` is rewritten out of the netlist by
`MultiPortWritesSymplifier`, so `getBigInt` cannot reach it — `RegFilePlugin.scala:115-131`).

Two `SimPublic` annotations were added, no logic: `intRat/nzvcRat/xRat.io.committedPhys` in
`RenameStage`, and the `Freelist` io ports. Only `intRat.committedPhys(15)` had a real RTL
consumer, so the rest were pruned from a Verilator build.

Reading the CCR from the NZVC/X PRF rather than folding it from writeback observations is
what exposed finding 1: a fold and a structural read cannot both be wrong in the same
direction.

### 2.3 Alignment — measured, not calibrated

No delay constant was needed and none was guessed:

* `RobPlugin.logic.commitObs(k).fire` is `RegNext(retireK)` (`RobPlugin.scala:2639`).
* `RatTable.commReg` (hence `io.committedPhys`) is loaded from `commitPorts`, which are
  combinational off the same retire event (`RenameStage.scala:404-416`).

Both are exactly one register deep from retire, so sampling them in the same `onSamplings`
callback is consistent by construction. First run of 3 fuzz seeds: zero structural
divergences, so the reasoning held on contact.

### 2.4 `MemWriteCapture` — the architectural store stream

Tap: `DcachePlugin.logic.storePort` (already fully `simPublic`). It is the single ordered
point through which both the SQ's committed drain and the exception unit's frame pushes
reach memory, and it sits **upstream** of the copyback cache, so it carries program store
order rather than writeback order. Speculative stores never reach it.

Compared byte-for-byte against Musashi's `mem_event[...]` log
(`OracleState.memoryWriteTrace`), scoped to the data sandbox — the supervisor stack carries
exception frames whose layout legitimately differs between a real 68040 and Musashi's model,
and asserting on those would be a manufactured false positive.

This is strictly stronger than the pre-existing final-image check, which iterates only
addresses the *oracle* wrote and therefore cannot see a DUT store to an address the oracle
never touched, nor any ordering at all.

### 2.5 `AllocatorChecker` — item 4

Shadow busy-vector per freelist, asserting *Double alloc* / *Double free* against the
allocator's own pop/push ports, exactly like `NaxAllocatorChecker` (`main.cpp:1312-1355`).
Enabled by default; upstream's `ALLOCATOR_CHECKS?=no` is not copied. Five instances (§7.3:
we rename five register classes, NaxRiscv renames two).

One deviation is required. `Freelist` recovers from a mispredict by **pointer rollback**
(`head := commHead`), returning every still-speculative pop in one cycle with no push port
firing — a shape NaxRiscv's allocator does not have. The shadow therefore tracks the
speculative pop queue and frees it on flush; committed pops advance 1:1 with pushes, per the
RTL's own `commHead := commHead + pushCount`.

Result over 200 fuzz seeds and 462 directed tests: **zero violations**. That is a negative
result and is reported as one — the checker is now standing guard, but it has not yet caught
anything.

---

## 3. Coverage, stated honestly

**75.6 %** of oracle steps (15 169 / 20 061 over the 200-seed sweep) get a structural
compare. The shortfall is not under-reporting — it is a cycle in which **two complete macros
retire together**, where the committed RAT never holds the intermediate state at all. Those
steps are listed as UNCOVERED in the result, never silently skipped, and
`requireFullCoverage` turns them into a failure for a caller that wants it.

The residual hole is therefore: *a register the DUT does not volunteer, written wrongly by
macro k, and overwritten correctly by macro k+1 retiring in the same cycle.* The delta
comparator still covers every record at per-record granularity, so the two run together and
are complementary. This is why the old comparator was kept rather than replaced.

### What was not inverted, and why

* **Micro-op granularity.** §7.2 of the comparison document is right: one macro → N µops with
  no 1:1 oracle correspondence. The snapshot is taken at `macroLast` on the last observation
  firing in the cycle; a mid-macro committed state has no oracle step to compare against.
* **FP registers.** `ArchStateProbe` reads the integer, NZVC and X files. The FP PRF and
  FPCC are reachable the same way (`fpRat.io.committedPhys` is already `simPublic`) but
  `OracleStep.fp` is 80-bit `BigInt` and `FpuLockStepSpec` already compares it out of band
  through `afterRun`. Extending the structural probe to FP is a clean follow-on.
* **The SR system byte** is not renamed, so it still comes from the ROB commit observation
  rather than from a physical register. VBR/SFC/DFC/CACR likewise.
* **Memory ordering** is verified only up to reordering within an 8-entry window — see §5.
* **Defects 5 and 6** of the 2026-09-03 list (a `set_bus_skew` Tcl glob matching 0 of 30
  instances; the OOC synth gate's 0.919 ns noise floor) are synthesis-flow defects with no
  lock-step surface. Nothing here addresses them, which matches §5.1's own 4-of-7 scoring.

---

## 4. The acceptance test

`src/test/scala/m68k040/lockstep/HarnessSelfTestSpec.scala`, 8 tests. Each historical defect
is **reintroduced** behind a default-off switch and the outcome asserted, always against an
unmutated control run of the same program with the same seed.

Switches: `WhiteboxCapture.Regressions{legacyA7Resync, dropTrailingFold}` and
`FuzzRunner.SelfTest{dropSecondDst, dropKeepCommit, corruptArch}`.

| defect | reintroduced how | result |
|---|---|---|
| **1 / 2** — `Dr` and `Dh` never compared | comparator-level: a DUT record claiming only `D0` for a macro that writes `D0` and `D3` | pre-fix `LockStep.compare` **passes**; `ArchLockStep` fails with `INTEGER WRITE MISSING/WRONG D3` |
| **1 / 2** end-to-end | write garbage into the physical register the committed RAT names for a long divide's remainder, through the boot seed port, after it retires | outcome kind is `ARCH`, which *is* the blindness proof — the structural comparator is only evaluated after `LockStep.compare` returns ok |
| **4** — lagged `a7` shadow replayed as live | `legacyA7Resync` | delta comparator fails on `a7`; the same run reports `structural=OK`. Immunity **measured**: a STEP divergence now carries the other comparator's verdict in its context |
| **7** — crack-tail folded onto the wrong instruction | `dropTrailingFold` | same shape, same measured immunity |
| **3** — every `Scc <mem>` record deleted | `dropKeepCommit` | caught by both. The gain is **attribution**: the delta comparator reports a PC mismatch one instruction downstream; the structural comparator reports `DUPLICATE BOUNDARY ... (harness bug, not an RTL bug)` at the deleted instruction itself — a shape only a harness defect can produce |
| **5, 6** | — | out of scope, see §3 |

Note what the end-to-end 1/2 test really shows. Corrupting the architectural remainder while
leaving every writeback observation intact defeats the **post-fix** delta comparator too:
`archReg2*` compares the writeback *value*, not architectural state. `secondDst` closed the
original hole; it did not close the class.

Two further comparator-level tests guard properties the write-log check alone would miss: an
**unexpected** DUT write (one the reference never made) must fail, and a **missing retire
boundary** must be reported rather than silently skipped.

---

## 5. New divergences, and their attribution

The brief says a rise is good news but must be attributed. There were two rises during
development. Both were run to ground; **neither survives**, and the corpus finished where it
started at 3/200.

### 5.1 Store order — 13 seeds — ORACLE artifact, DUT is the correct side

Strict byte-order comparison flagged 13 of 200 seeds, all one shape: the DUT writing at `A`
where the reference wrote at `A+2`. Minimized to two instructions:

```asm
	move.l #0x4018,%a4
	move.l %d4,-(%a4)
```

Musashi's `MOVE.L <ea>,-(An)` handlers **hardcode** two 16-bit writes, low half first
(`m68k_in.c:6349-6355`), unconditionally for every CPU type — the 68000/68010
predecrement-long bus quirk. This is *not* the `M68K_SIMULATE_PD_WRITES` path, which is off
(`m68kconf.h:102`); the split is in the opcode handler itself. A 68040 with a 32-bit port
issues one long write, which is what this core does.

So the reference is the side that is out of order, and the comparator now allows a reference
write to be matched up to `ReorderWindow = 8` entries ahead — but **never across an unmatched
reference write to the same address**, so two writes to one byte still have to arrive in
order. Out-of-order matches are counted (32 across 16 seeds) so a systematic change stays
visible.

### 5.2 Silent rewrites — 53 seeds — two documented deliberate simplifications

187 value-preserving DUT byte writes with no reference counterpart, across 53 of 200 seeds.
Both classes are recorded design decisions, and both were *predicted* by their own docs to be
invisible to us:

1. **CAS / CAS2 on a failed compare.** `2026-06-24-cas-cas2-design.md` §6: *"the engine has
   no conditional store ... CAS/CAS2 always issue their store(s)"*, cost stated as *"a
   redundant bus write on mismatch (invisible to the architectural oracle; **matters only to
   an external bus observer we don't model**)"*. Found from a 7-line program on the
   comparator's first run.
2. **Memory bit-field RMW spill byte.** `2026-06-30-bitfield-mem-dynamic-3c.md`:
   *"Always-5-byte span ... funnel leaves the spill byte unchanged when unused → RMW
   write-back is a no-op."* Minimal repro `bfchg 4(%a2){#10:#17}` — one extra byte one past
   the field.

Method for the second: `LOCKSTEP_MEM_STRICT=1` promotes silent rewrites to hard divergences,
which lets the **existing** fuzz minimizer shrink a program down to the single instruction
that produced one. Attribution was measured, not guessed — with `FUZZ_SKIP=cas,cas2` the
count drops from 187/53 seeds to the bit-field class alone.

They remain a reported statistic rather than a failure, but the count is printed so a spike —
a genuinely duplicated store, which would be a real bug — is visible.

### 5.3 A free by-product: better attribution on the three divergences we already had

The three surviving fuzz divergences (seeds 80, 109, 127) are unchanged, but the structural
comparator now independently confirms each at the **same oracle index**, and for two of them
it says something more useful than the delta comparator does:

| seed | delta comparator | structural comparator |
|---|---|---|
| 80 | `idx=79 reg D5: dut=0x00000000 oracle=0x0000007e` | `idx=79 ARCH REG MISMATCH: D5: dut=0x00000000 oracle=0x0000007e` (agrees) |
| 109 | `idx=64 pc: dut=0x7bca4112 oracle=0x4080013e` | `idx=64 ARCH REG MISMATCH: A7: dut=0x000ffff8 oracle=0x00100000` |
| 127 | `idx=70 pc: dut=0x3d973c90 oracle=0x4080015e` | `idx=70 ARCH REG MISMATCH: A7: dut=0x000ffff8 oracle=0x00100000` |

For 109 and 127 the delta comparator reports a **wild PC**, which is a symptom with a wide
differential. The structural comparator reports **A7 exactly 8 bytes low** — i.e. an exception
frame was pushed and the return never popped it — which names the mechanism. Whoever picks
these up should start there.

### 5.4 The two directed-suite failures that were real

Covered in §0 and `docs/BUG_chk_chk2_flags_not_committed_on_trap.md`. Both were structural
divergences on tests that had passed for their whole lives.

`chk-neg` and `chk2-oob` now carry `structuralKnownGap = Some("BUG_chk_chk2_...md")`, a
`runLockStep` argument that runs the comparison, prints the divergence loudly, and does not
fail. Remove it when the RTL is fixed — the tests then become the regression test. The
argument requires a `docs/BUG_*.md` by name so one cannot be added quietly.

---

## 6. Environment knobs

| variable | effect |
|---|---|
| `LOCKSTEP_STRUCTURAL=0` | disables the structural compare, allocator checker and store-stream compare everywhere |
| `LOCKSTEP_STRUCTURAL_STATS=1` | prints per-run coverage, store counts, allocator errors, silent rewrites and out-of-order matches |
| `LOCKSTEP_MEM_STRICT=1` | promotes silent rewrites to divergences, so the fuzz minimizer can localise one |

---

## 7. Follow-on work this exposes

1. **Fix `CHK`/`CHK2` flag commit** (the doc suggests a direction: a symmetric
   `entryNzvcWriteValid` mirroring the existing RTE restore path, which is the established
   safe pattern for this class). Then delete both `structuralKnownGap` arguments.
2. **Extend the structural probe to FP/FPCC.** The mappings are already `simPublic`; only the
   80-bit compare needs threading, and `FpuLockStepSpec` already has the oracle side.
3. **Recover the 24 % uncovered boundaries.** A cycle retiring two macros could still be
   attributed to the earlier one when the later µop writes no architectural register, which
   is knowable from the writeback record the harness already holds.
4. **Audit for more `archRegValid == false` retire paths.** Two of the three findings were
   instructions that retire without an EU writeback (MOVEC via `sysRetire`, CHK via the
   fault path). That is a small, enumerable set, and it is exactly where the old comparator
   was blind.
