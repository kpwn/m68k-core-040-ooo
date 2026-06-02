# Branch Unit + Commit-Time Mispredict Recovery (Slice 3d-1) — Design

**Status:** Draft for review
**Date:** 2026-06-02
**Parent spec:** `.../2026-05-31-m68k-040-ooo-architecture-design.md` (ch 5 execute, ch 6 branch, ch 8 commit; invariant #1 precise retire, #2 FMax/no-high-fanout). [[branch-handling-direction]].
**Reference (deep-analyzed + Haiku-confirmed):** NaxRiscv `BranchPlugin` (execute-time resolve → registered reschedule), `CommitPlugin` (captures reschedule, recovers at **commit**: `commitNext := allocNext` pointer-jump, `RfTranslation.rollback := reschedule(onCommit).valid` → rollback to **committed**), pointer-based ROB validity, single-bit IQ `clear`, registered `flushIt`. Recovery is **commit-time**; squash is **pointer-based** (no per-entry valid fanout).
**Builds on:** the merged 3c full core (lock-stepped) + the frontend `PipeStage` skids (with a `flush` input tied False, to be wired here).
**Slice position:** 3d-1 — first branch slice. No predictor yet (sequential fetch → taken = mispredict → commit-time redirect). 3d-2 = BTB/predictor (cut penalty); 3d-3 = call-cracking (after store µops).

**FMax constraint (first-class):** the full core is at +0.773 ns / 309 MHz post-synth; 3d-1 must not regress it. The mispredict flush must be a **single registered commit-time pulse driving pointer/small-bitmap resets only** — no combinational execute→everywhere broadcast, no per-entry valid-clear farm, no age-compare per entry.

---

## 1. Purpose

Add conditional/unconditional branch resolution and precise, FMax-safe mispredict recovery: a dedicated latency-1 **branch EU** resolves taken/target (reading NZVC) and flags mispredict; the **ROB** captures it and, at the mispredicting branch's **retire**, fires **one registered flush pulse** that (pointer-)squashes younger state and redirects fetch. Prerequisite refactor: make ROB validity **pointer/count-derived** so the squash is a pointer reset (zero per-entry fanout) — the user-chosen NaxRiscv-exact approach.

## 2. Scope

**In:** (1) **ROB pointer-based validity** — remove the `valids` Vec; head-validity = `count>0`/`count>1`; flush = `tail:=head; count:=0` (no per-entry clear); keep `completes` (out-of-order), with alloc-priority over a stale post-flush completion. (2) **Branch EU** (`m68k040.execute.BranchEuPlugin`, latency-1): reads NZVC, evaluates the 68k condition, computes target = `pc+2+branchDisp`, taken, resolved-next-PC, mispredict; completes to the ROB carrying `{robId, mispredict, nextPc}`. (3) **IQ BRANCH sel-class + a 3rd issue port** (the port deferred in 3b): slots carry a 2-wide `sel` (ALU/BRANCH); push steers `isBranch`→BRANCH; select adds a branch port → branch EU. (4) **Commit-time recovery**: ROB stores `{mispredict, nextPc}` per branch entry (from completion); branches are `retireAlone` (retire 1-wide); at retire of a mispredicting branch → a **registered** flush pulse driving rename rollback + freelist reset + IQ clear + the two FE `PipeStage.flush` + ROB pointer reset, plus `FetchAlign.redirect := nextPc`. A small `RedirectService` (FlushService) owns the registered pulse + PC. (5) **Branch trace fix**: the branch's `commitObs.pc` = the **resolved next-PC** (target if taken, else pc+2), from the branch completion — not the `predNextPc=pc+2` stub. (6) Lock-step corpus extended to **2-byte short branches** (`Bcc.s`, `BRA.s`) taken + not-taken, vs Musashi; re-synth to confirm WNS held.

**Out:** the predictor/BTB (3d-2 — without it every taken branch mispredicts → commit-time redirect, correct but with penalty); call-cracking (3d-3, needs store µops); `>2-byte` branches (`Bcc.w`, the `predNextPc` length stub stays); JMP/JSR/RTS (later); execute-time recovery / RAT checkpoints (not supported by location-bit rename, higher fanout).

## 3. Components

### 3.1 ROB → pointer-based validity (refactor)
- Remove `valids`. `retire0 = (count > 0) && completes(h0) && !flushing`; `retire1 = retire0 && (count > 1) && completes(h1) && !p0.retireAlone && !p1.retireAlone`.
- Alloc: `completes(tail):=False` (+`tail`, `count`), no valid set. Retire: `head++`, `count--`, no valid clear.
- **Flush (pointer-only):** `tail := head; count := 0` — that's it (the `valids.foreach(:=False)` 64-wide net is gone). `completes` of squashed entries are out-of-range (count excludes them) and are reset on re-alloc.
- **Stale-completion race:** a wrong-path µop still in an EU completes after flush, setting `completes(staleRobId):=True`. Give the **alloc-reset priority** over the completion-set on the same index (`when(allocFire & idx==tail) completes:=False` wins), so a re-allocated entry is never seen complete from a stale set. (Two completion ports + alloc — a narrow priority, not a fanout farm.)

### 3.2 Branch EU (`BranchEuPlugin`, latency-1)
Same 2-stage shape as `AluEuPlugin` (S0 read | M2S | S1 resolve). S0 reads the NZVC PRF (`newRead`) at `pNzvcSrc` (branches read NZVC; allocate the flag read port deferred in the ALU EU). S1:
- `taken = evalCond(uop.cond, nzvc)` — the 16 68k conditions (T/F/HI/LS/CC/CS/NE/EQ/VC/VS/PL/MI/GE/LT/GT/LE) from the 4-bit `cond` + NZVC. (Unconditional BRA = cond T = always taken.)
- `target = uop.pc + 2 + uop.branchDisp` (2-byte short branch).
- `nextPc = taken ? target : uop.pc + 2`.
- `mispredict = taken` (no predictor → fetch went sequential → a taken branch's younger fetches are wrong-path). (3d-2: `mispredict = taken =/= predictedTaken || target =/= predictedTarget`.)
- Completion to ROB: `{robId, mispredict, nextPc}` (extend the completion/wbObs; the ALU completion stays `{robId}`-only, or unify a completion bundle with `mispredict`/`nextPc` fields that ALU drives as false/dontcare). No PRF write (branches write no int reg; NZVC unchanged by Bcc/BRA).

### 3.3 IQ BRANCH sel-class + 3rd issue port
- Slot `sel : Bits(2)` (bit0=ALU, bit1=BRANCH; `sel===0` empty). Push sets the class from the µop: `isBranch ? BRANCH : ALU`. (Rename/decode already carry `isBranch`/`cluster`.)
- `issue : Vec[Stream[IqContext]](3)` — issue(0/1)=ALU (age-select over ALU-class ready slots, as today), issue(2)=BRANCH (age-select over BRANCH-class ready slots). The branch EU consumes issue(2).
- Depend-on-READ unchanged: a Bcc's NZVC-source trigger is set (it reads NZVC) — so a Bcc waits for its NZVC producer (the prior flag-writer). This is the one real flag dependency (the CCR-reservation insight: only readers wait).

### 3.4 Commit-time recovery + `RedirectService`
- ROB per-entry store (small): `{mispredict, nextPc}` written at the branch's completion (keyed by robId), like the value path. (Reuse a small store or extend the payload's completion-written fields.)
- At retire: the branch (retireAlone) retires 1-wide (it's correct — it commits). If its `mispredict` is set, assert a **registered** `redirect` for the NEXT cycle: `RedirectService { doFlush: Bool (registered pulse); flushPc: UInt }`. `doFlush` drives — all as register/pointer resets — `rename.flushPort`, `iq.flushPort`, `decode.pipeFlush`, `rename.pipeFlush` (the two `PipeStage.flush`), and the ROB's own `tail:=head; count:=0`. `flushPc` → `FetchAlign.redirect`. The registered pulse + pointer/bitmap resets keep it off the critical path (NaxRiscv pattern).
- Ordering: the branch retires (commits, advancing head past it) the cycle it's seen complete+mispredict; `doFlush` registers True → next cycle the squash + redirect fire. The committed RAT now includes the branch, so `rename` rollback (location→committed) restores the post-branch state; squashing younger (pointer reset) discards wrong-path. Correct precise recovery.

### 3.5 Branch trace (resolved next-PC)
The whitebox `commitObs.pc` for a branch must be the **resolved next-PC** (the branch completion's `nextPc`), not `predNextPc`. So the ROB uses, for a branch entry, the completion-stored `nextPc`; for non-branches, `predNextPc=pc+2` (2-byte). The lock-step then matches Musashi's post-instruction PC across a taken branch.

## 4. Verification
- **ROB refactor (unit):** `RobPluginSpec` still green with pointer-validity (ring alloc/retire/in-order/retireAlone/flush=pointer-reset/free-loop); add a stale-completion-after-flush test (a completion to a squashed robId must not cause a spurious retire after re-alloc).
- **Branch EU (unit):** resolve each of the 16 conditions vs NZVC (directed, against a small reference / Musashi `Bcc` CCR semantics); target = pc+2+disp; mispredict=taken; nextPc correct.
- **End-to-end lock-step** (`ExecuteLockStepSpec`, extend): 2-byte short-branch programs vs Musashi —
  1. `Bcc.s` not-taken (fall-through) — sequential, no flush.
  2. `Bcc.s` taken (forward) — taken → commit-time flush + redirect; the trace pc = target; subsequent instrs from the target lock-step.
  3. `BRA.s` (unconditional) — always taken → redirect.
  4. a loop: `moveq`/ALU + a `Bcc.s` backward taken N times then fall-through (a small countdown) — sustained mispredict-recover-redirect, freelist/ROB recycle, flush correctness.
  Deterministic (run 2×).
- **Synth gate:** re-run `GenFullCoreSynthVerilog` (now with branch EU + IQ branch port + redirect) OOC at 250 MHz; confirm **WNS ≥ 0** (the registered flush + pointer squash must not regress 309 MHz); report the new WNS + critical path (it must NOT be the flush broadcast). `make test-fast`+`test-verilator` green.

## 5. Files
| File | Change |
|---|---|
| `src/main/scala/m68k040/rob/RobPlugin.scala` | pointer-based validity (drop `valids`); flush = pointer reset; store `{mispredict, nextPc}` per branch; commit-time redirect via RedirectService; branch trace nextPc |
| `src/main/scala/m68k040/services/Services.scala` | `RedirectService { doFlush, flushPc }` |
| `src/main/scala/m68k040/execute/BranchEuPlugin.scala` | NEW — latency-1 branch EU (cond eval, target, mispredict, nextPc) |
| `src/main/scala/m68k040/execute/iq/IssueQueuePlugin.scala` + `IqContext.scala` | 2-wide `sel` (ALU/BRANCH), 3rd issue port, push class steering |
| `src/main/scala/m68k040/dispatch/DispatchPlugin.scala` | set the IQ push sel-class from `isBranch` |
| `src/main/scala/m68k040/frontend/FetchAlignPlugin.scala` | (redirect already exists) wire the RedirectService redirect; the `PipeStage.flush` wired from `doFlush` |
| `src/main/scala/m68k040/top/FullCoreSynth.scala` + lockstep specs | add branch EU + branch issue wiring + RedirectService wiring; branch lock-step corpus |

## 6. Open items / deferrals (logged)
- **No predictor (3d-2):** every taken branch is a commit-time mispredict (penalty = branch reaching head). Correct but slow; 3d-2 (BTB+predictor) cuts it.
- `>2-byte` branches (`Bcc.w`) need real `predNextPc` length (the `pc+2` stub); 2-byte short branches only this slice. (Branch `nextPc` is resolved exactly by the EU; it's the OTHER instructions' `predNextPc` that's the 2-byte limit.)
- Stale-completion handling via alloc-priority (narrow); if a future EU has longer latency, revisit the post-flush in-flight-completion window.
- `RedirectService` registered pulse is the FMax linchpin — keep `doFlush` a registered Bool fanning only to register/pointer resets; never combinationally from execute.
- JMP/JSR/RTS/call-cracking, FP branches, exception redirects — later.
