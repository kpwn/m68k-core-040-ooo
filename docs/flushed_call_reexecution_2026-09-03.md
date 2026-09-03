# Is a flushed `bsr`/`jsr` ever SKIPPED instead of re-executed? — NEGATIVE RESULT

**Date:** 2026-09-03
**Branch:** `investigate/bsr-flush-skip` (off `fmax-closure-fanout` @ `c807e23`)
**Scope:** core-level simulation only. No hardware, no SoC, no boot.
**Verdict: NO.** Across 139 directed lock-step programs plus a 56-run sim-seed
sweep and 4 new self-checking corpus tests, a squashed `bsr`/`jsr` was
re-fetched and re-executed **every single time** it was on the recovered
architectural path. Zero divergences against the Musashi reference. The
hypothesis is dead as stated.

---

## 1. The hypothesis under test

From the companion SoC repo's `docs/BUG_calibration_word_misplaced_0d00.md`:

* **Part 29** proved by real-hardware ILA capture that the `bsr.w` at ROM
  `0x40800284` **is** correctly received and resolved by the branch EU
  (`cond=T / ibranch=0 / taken=1 / redirect=1 / mispredict=1`, `robId=18`), and
  is then collaterally flushed by an **older**, unrelated `rts`-class
  misprediction (`robId=16`, PC `0x40804144`) that reaches the retire head one
  cycle earlier.
* **Part 62** established from a full-program-order MAME trace that
  `0x40800284` executes **exactly once** per boot on a healthy machine, with no
  retry loop.

The unifying hypothesis this session was asked to test: *a flushed subroutine
call is NOT re-executed — fetch resumes past it — so the entire called
subroutine tree is silently skipped.* If true, it would explain three
separately-documented boot bugs with one mechanism.

---

## 2. What the RTL says should happen

Reading `RobPlugin.scala`, `BranchEuPlugin.scala`, `FullCoreSynth.scala` and
`FetchAlignPlugin.scala` on `c807e23`:

1. **The flush is RETIRE-GATED.**
   `RobPlugin.scala:1850` — `val branchRedirect = retire0 && p0.retireAlone &&
   mispredictStore(h0)`.
   It fires only when the mispredicting branch is **at the ROB head**, i.e. it
   is the oldest in-flight instruction and everything older has already
   architecturally committed. (The long comment at `RobPlugin.scala:1812-1849`
   records that an "early/narrow" squash was investigated and rejected as
   unsafe precisely because it would let fetch resume past older, not-yet-
   retired work — see Part 61.)
2. **The redirect PC is the mispredicting branch's own resolved next-PC.**
   `RobPlugin.scala:2539` — `when(branchRedirect) { flushPcReg := nextPcRd0 }`,
   where `nextPcRd0 = nextPcMem.readAsync(h0)` (`:1426`) holds the branch EU's
   `nextPc` (`BranchEuPlugin.scala:232`: `Mux(redirect, target, u1.nextPc)`).
3. **`doFlushReg` (`:2537`) fans out once** (`FullCoreSynth.scala:75-102`):
   IQ clear, decode/rename skid flush, RAT rollback + freelist restore to the
   committed shadow, store-queue flush, RAS checkpoint restore, DTLB/ITLB
   micro-flush, and `faRedir.valid := doFlush; faRedir.payload := flushPc`.
4. **The frontend restarts cleanly at `flushPc`.**
   `FetchAlignPlugin.scala:1356` takes the highest-priority `mispredictRedirect`
   arm into `commonRedirect` (`:1264-1285`): `decodePc := flushPc`,
   `fetchPc := flushPc(31 downto 3) @@ 0`, `ibuf.io.flush := True`,
   `pendingDrop := flushPc(2 downto 1)`, and **every** outstanding fetch-ring
   entry marked stale.

**Therefore, by construction:** at the flush instant the committed architectural
state equals the state immediately after the mispredicting branch, and fetch
restarts at that branch's correct next PC. There is no per-PC "already
fetched / already executed" state anywhere that could suppress an instruction.
The only per-PC state that survives a flush is predictor state (BTB / FTB /
gshare / RAS), and none of those can *suppress* an instruction — they can only
predict a redirect, which the branch EU then verifies and corrects.

**Important corollary (and the reason Part 29's observation is not by itself a
defect):** because the flush is retire-gated, a younger in-flight call was, by
definition, fetched on the mispredicting branch's *wrong* path. It is only
architecturally *required* to execute if the branch's *correct* path also
reaches it. A wrong-path `bsr` that never retires is correct behaviour, not a
lost call.

---

## 3. What was built

### 3.1 `src/test/scala/m68k040/fuzz/BsrFlushSkipSpec.scala`

A directed matrix of **139 programs** (16 shapes × a distance sweep), each run
through **full Musashi lock-step** (`FuzzRunner.run`), which compares the DUT's
committed PC / register / CCR / A7 stream instruction-by-instruction against the
reference model and then compares sandbox memory. A skipped call diverges on the
first committed PC after the redirect; a doubly-executed call diverges on the
call counter.

Every shape is built so the younger call is genuinely **fetched and dispatched
on the mispredicted path** *and* is **on the architecturally correct path** — the
wrong path falls straight through into the correct path at or just before the
call. For the conditional-branch shapes `flushPcReg` therefore lands **exactly
on the call opcode**, the tightest possible case for a "redirect skips the
instruction at flushPc" defect.

| Shape | Older mispredicting instruction | Younger call |
|---|---|---|
| `bcc_bsr` (k=0..16) | cold forward `Bcc`, actually taken | `bsr.w` at the redirect PC |
| `bcc_jsr_abs` / `bcc_jsr_ind` | same | `jsr abs.l` / `jsr (An)` (ibranch) |
| `bcc_two_calls` | same | two back-to-back `bsr.w` |
| `late_bcc_bsr` (k≤24) | `Bcc` on a DIVU-dependent condition | `bsr.w` |
| `very_late_bcc_bsr` (k≤96) | `Bcc` on a 3-deep DIVU chain (ROB-filling window) | `bsr.w` |
| `load_dep_bcc_bsr` | `Bcc` on a cold D-cache load | `bsr.w` |
| **`rts_bsr` (k=1..9)** | **`rts` whose RAS prediction is wrong** (callee rewrites the stacked return address) — *Part 29's literal shape* | `bsr.w` at the rts's real return address |
| `bsr_cold_bsr` | cold `bsr` (predicted not-taken) | `bsr.w` on its fall-through, executed later |
| `loop_exit_bsr` | BTB-**trained** backward `bne` exiting (predicted taken, actually not) | `bsr.w` at the fall-through |
| `bcc_bsr_tree` | cold forward `Bcc` | head of a **3-deep call tree** |
| `deep_rob_bsr` (k≤96) | cold forward `Bcc` | call still in the frontend skid |
| `double_mispredict` | **two** stacked mispredicting `Bcc`s | `bsr.w` behind both |
| `far_callee_bsr` | cold forward `Bcc` | `bsr.w` to a callee 1 KiB away (cold I-line) |
| `bsr_target_mispredict` | cold forward `Bcc` | `bsr.w` whose callee immediately mispredicts again |
| `store_wrong_path_bsr` | cold forward `Bcc` | `bsr.w` behind squashed speculative **stores** |

`k` (the wrong-path filler count, 2 bytes each) also sweeps the call site's
offset within the 8-byte fetch window and the 16-byte cache line — `k=7` puts a
4-byte `bsr.w` straddling the 16-byte boundary at the redirect PC.

**Non-vacuity guard.** A separate test (`bsr flush probe`) re-runs 15
representative programs on the DUT with `RobPlugin.branchRedirect`,
`doFlushReg` and `flushPcReg` tapped, and asserts a real commit-time mispredict
flush actually occurred. Sample output:

```
bcc_bsr_k07          branchRedirect=5 doFlushReg=5 flushPc=[0x4080001e,0x40800038,0x40800022,0x40800040,0x40800040]
rts_bsr_k04          branchRedirect=6 doFlushReg=6 flushPc=[0x4080002e,0x40800014,0x40800036,0x40800018,0x4080003e,0x4080003e]
bcc_bsr_tree_k04     branchRedirect=9 doFlushReg=9 flushPc=[0x40800018,0x40800032,0x4080003a,0x40800042,0x40800040,0x40800038,0x4080001c,0x4080004a,0x4080004a]
```

For `bcc_bsr_k07` the call site is `0x4080001e`; `flushPc[0] = 0x4080001e` is
the older `Bcc`'s redirect landing exactly on the `bsr` opcode, and
`flushPc[1] = 0x40800038` is that very `bsr` subsequently resolving and
redirecting to its own target — i.e. the squashed call was demonstrably
re-fetched and re-executed. For `rts_bsr_k04`, `flushPc[1] = 0x40800014` is the
mispredicting `rts` redirecting onto the `bsr`, and `flushPc[2] = 0x40800036`
is that `bsr` resolving — Part 29's exact shape, recovered correctly.

**Sim-seed sweep.** `SparseMemory`'s page fill is seed-derived, so the bytes the
wrong path fetches beyond the program (and hence frontend timing and the exact
ROB occupancy at flush) change with the seed. 7 shapes × 8 seeds = 56 extra
runs, all pass. This is the sim-side analogue of Part 62's "which RTS collides
varies boot to boot".

### 3.2 Four new self-checking corpus tests

Added to `src/test/resources/m68kooo-ported-tests/asm/` (they run automatically
in `PortedM68kOooSpec`):

* `flush_younger_bsr_reexec.s` — 6 cases: `bsr.w` / `jsr abs` / `jsr (An)` /
  two back-to-back calls at the redirect PC, distances 0/2/3/4/5/7. Asserts the
  callee ran exactly 7 times and that no wrong-path filler leaked (`D4 == 0`).
* `flush_younger_rts_bsr_reexec.s` — Part 29's literal shape at three distances
  (RAS-mispredicting `rts` with a younger `bsr` in flight on the real return
  path). Also asserts A7 balance.
* `flush_younger_bsr_tree_reexec.s` — a 3-deep call **tree** flushed by (a) a
  cold `Bcc`, (b) a BTB-trained loop-exit, (c) an older cold `bsr`. Expects 9
  tree entries.
* `flush_younger_bsr_exc_reexec.s` — the **other** `doFlushReg` disjunct:
  `exc.redirectValid`. A-line (`0xA06E`) exception entry squashes a younger
  call; the handler bumps the stacked PC by 2 and RTEs, so the RTE redirect PC
  is exactly the squashed call's PC. Companion to the existing
  `adv_exc_entry_young_mem_squash.s`, which covers younger *memory* ops.

All four were first validated against Musashi standalone (`pass=1`,
`sentinel=0xc0ffee00`, expected counters) before being run on the DUT.

---

## 4. Results

| Suite | Result |
|---|---|
| `BsrFlushSkipSpec` — 139 directed lock-step programs | **139/139 PASS**, 0 divergences |
| `BsrFlushSkipSpec` — 7 shapes × 8 sim seeds | **56/56 PASS** |
| `BsrFlushSkipSpec` — non-vacuity flush probe (15 programs) | **PASS**; every program produced 4–9 real commit-time `branchRedirect` flushes |
| 4 new ported corpus tests | **4/4 PASS** on the DUT |
| Existing mispredict/flush/call regression subset (37 tests) | **PASS** (see §5) |
| `sbt fastTest` (non-Verilator unit suites) | 336 passed, 1 failed — see note below |

**`fastTest` note (not caused by this branch).** `RobPluginSpec`'s
"preciseDrainBusyIn holds a debugPcApply off an in-flight precise drain" timed
out (`200 was not less than 200 waitUntil timed out`, `RobPluginSpec.scala:115`)
during the full `fastTest` run on a machine at load average ~10. Re-running
`testOnly m68k040.rob.RobPluginSpec` in isolation gives **44/44 pass**. This
branch changes no RTL and no existing test, so this is a host-load-sensitive
flake in a fixed 200-cycle `waitUntil` bound, unrelated to the work here.

Not a single case skipped a call, executed one twice, corrupted A7, or leaked
wrong-path state.

---

## 5. Regression baseline

The staged 37-test subset covers `mispredict`, `deep_mispredict`,
`unstable_branch`, `adv_store_squash_mispredict`, `adv_a7_spec_flush`,
`adv_flush_restart_store`, `adv_spec_trap_squash`, `adv_spec_fline_squash`,
`adv_predicted_indirect_jmp_squash`, `adv_exc_entry_young_mem_squash`, the
`bsr_*` / `jsr_*` / `ras_*` / `rom_stack_cache_bsr_*` families, `irq_during_bsr`
and the four new tests. No RTL was changed on this branch, so this is a baseline
confirmation, not a fix verification.

---

## 6. What this means for the boot investigation

The negative result **removes** "the flushed call is skipped" from the candidate
list and, with it, the single-mechanism explanation for the three boot bugs. It
is consistent with — and independently reinforces — Part 29's own conclusion
("standard, correctly-implemented flush-younger-on-misprediction semantics
applied to an adversarial ROB-allocation-order collision") and Part 61's
re-verification of recovery-state correctness.

Concretely, this reframes Part 29's central observation. Because the flush is
retire-gated, the `bsr` at `0x40800284` being resolved-then-flushed is *proof
that it was on the wrong path of the older `rts`*, not evidence that a
correct-path call was lost. The right question is therefore no longer "why was
the call squashed" but **"why does the correct path after that `rts` never
reach `0x40800284` either"** — i.e. the divergence is upstream of, or inside,
the callee, not at the call site. That points at Part 62's §4 candidate (the
`$0D24`/csCode-21 hardware-timing race one `bsr` downstream) and at the
RAS/return-address provenance of the mispredicting `rts` itself, rather than at
the flush mechanism.

### Honest limits of this result

* Pure core-level Verilator simulation with the fuzz harness's behavioural
  memory. No MMU translation, no real MIG/DDR latency, no SoC fabric.
* **No interrupts.** Every shape here is interrupt-free. An IRQ arriving inside
  the flush-recovery window is a genuinely different (and, given Part 62's
  real-time-paced VIA1-SR driver, boot-relevant) mechanism. The existing corpus
  covers `irq_during_bsr` / `bsr_split_push_irq_in_gap` / `irq_at_rts_fetch_sweep*`
  separately, but this session did not construct an IRQ-during-mispredict-recovery
  shape. That is the most obvious remaining hole in this hypothesis class.
* The exception-flush path is covered by one directed corpus test, not by the
  full lock-step matrix.
* This tests *re-execution*, not the predictor's *efficiency*. The RAS
  `checkpointSave` in `FullCoreSynth.scala:159` only refreshes when the ROB is
  fully drained (`count === 0`), so after a flush the RAS can be restored to a
  stale-but-safe state. That costs extra mispredicts; it cannot cause a skip,
  and the lock-step runs confirm it does not.
