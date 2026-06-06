# Subroutine call/return Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development / executing-plans. Checkbox steps, TDD. Final task = HONEST MMU-live synth gate (≥250; ~266 non-regress).

**Goal:** BSR/JSR (call: push retPC + branch), RTS/RTR (return: pop + branch), JMP (computed-target branch). Lock-stepped vs Musashi.

**Architecture:** Crack calls into [stack-push retPC] + [branch]; returns into [stack-pop → T] + [indirect-branch → T]. Two new mechanisms: (1) a data-driven/indirect branch whose `nextPc` = a source operand (AGU EA address for JSR/JMP; loaded value for RTS/RTR) — drives the existing `completionPort.nextPc`/RedirectService; (2) stack push/pop (A7 predecrement/postincrement). Reuse the LS cluster (store/load), the branch EU (redirect), the cracker, and the RAS/`isCall`/`isReturn`/`branchType` predecode infra.

**Tech Stack:** SpinalHDL 1.14.1 / sbt `~/sbt/bin/sbt` / Verilator / Vivado. Reference: `decode/OperationDecoder.scala` + `MicroOpAssembler.scala` (decode/crack — see the DIV.L crack for a multi-µop example), `decode/EaDecoder.scala` (control modes; `(An)+`/`-(An)` are MEMCOMPLEX-deferred), `execute/BranchEuPlugin.scala` (target = `pc+2+disp` today; `completionPort.{mispredict,nextPc}` → redirect), `execute/LsEuPlugin.scala` + `ls/StoreQueue.scala` (store/load + dynamic wakeup), `rob/RobPlugin.scala`, `types/PredecodeMeta.scala` + `frontend/PredecodeWord.scala` (`branchType`/`isCall`/`isReturn`), `exception/ExceptionUnit.scala` (hand-rolled stack push/pop reference), `lockstep/ExecuteLockStepSpec.scala`, `oracle/Musashi.scala`. Spec: `docs/superpowers/specs/2026-06-06-call-return-design.md`.

**Branch:** `feat/call-return` (create off master).

---

### Task 1: Diagnose — indirect-branch mechanism + stack-push/pop mechanism
**Files:** read-only.
Decide: (a) the indirect/computed-target branch form (new DecOp `JUMP`/`IBRANCH` vs a flag on BRANCH that takes the target from a source operand) + how it drives `completionPort.nextPc` unconditionally; (b) the stack mechanism (general `-(An)`/`(An)+` store/load side-effect vs hand-cracked A7 ALU + `(A7)`); (c) for RTS, how the indirect branch consumes the pop-load's result (dynamic wakeup — branch waits on the load); (d) the JSR/JMP target = AGU EA address (control modes) path.
- [ ] Capture decisions in the Task-1 commit (empty ok).

### Task 2: Indirect-branch + JMP (computed target, no stack)
**Files:** `decode/OperationDecoder.scala`+`MicroOpAssembler.scala`, `execute/BranchEuPlugin.scala`, Test `execute/JmpSpec.scala` + a lock-step program.
Add the indirect-branch (target from a source operand). Decode JMP (`0x4EC0|ea`) → an indirect branch to the AGU EA address (control modes `(An)`/`(d16,An)`/`(xxx).W/.L`/`(d16,PC)`). Unconditional redirect.
- [ ] failing test (JMP (An) / (d16,An) / abs → PC = target) → FAIL → implement → PASS ×2 → commit `cf: indirect-branch mechanism + JMP (computed target)`.

### Task 3: Stack push/pop (the chosen mechanism)
**Files:** `decode/*` (+`EaDecoder.scala` if implementing general `-(An)`/`(An)+`), `ls/*`/`execute/LsEuPlugin.scala`, Test `execute/StackOpSpec.scala`.
Implement the push/pop primitive (predecrement-store / postincrement-load on A7, per Task-1's choice). Validate with a directed push-then-pop (move to -(A7), move from (A7)+) round trip.
- [ ] failing test (push.l then pop.l A7 round trip; A7 updated correctly) → FAIL → implement → PASS ×2 → commit `cf: stack push/pop (A7 predec/postinc)`.

### Task 4: BSR + RTS (the core call/return round trip)
**Files:** `decode/*`, Test `execute/CallReturnSpec.scala` + a lock-step program.
BSR (`0x61xx`) → [push.l retPC=nextPc] + [PC-relative branch to pc+2+disp]. RTS (`0x4E75`) → [pop.l (A7)+ → T] + [indirect-branch → T] (branch waits on the load).
- [ ] failing test (`BSR sub … sub: … RTS` → returns to retPC, A7 restored, regs correct) → FAIL → implement → PASS ×2 → commit `cf: BSR + RTS (call/return round trip)`.

### Task 5: JSR + RTR
**Files:** `decode/*`, `exception/`/CCR path for RTR, Test extend `CallReturnSpec.scala`.
JSR (`0x4E80|ea`) → [push.l retPC] + [indirect-branch → AGU EA address] (control modes). RTR (`0x4E77`) → [pop.w → CCR restore] + [pop.l → T] + [indirect-branch → T]; A7 += 6.
- [ ] failing test (JSR via (An)/(d16,An)/abs/(d16,PC); RTR restores CCR+PC) → FAIL → implement → PASS ×2 → commit `cf: JSR + RTR`.

### Task 6: Lock-step + HONEST SYNTH GATE
**Files:** `lockstep/ExecuteLockStepSpec.scala`, run suites.
- [ ] Step 1: lock-step vs Musashi ×2 — `BSR…RTS` round trip; nested calls; JSR through ≥3 control EA modes; JMP; RTR; A7 + stacked return addresses + final PC/SR/regs + stack memory step-for-step. ALL existing UNCHANGED (ITLB seed flake → repro on baseline first).
- [ ] Step 2: `make test-fast` + targeted verilator subsets (`-z` to avoid the 38-DUT OOM) — report totals.
- [ ] Step 3: HONEST SYNTH GATE — gen (mmuEnable/ipl live, no UNASSIGNED REGISTER, ~16 RAMB), `vivado -mode batch -nojournal -source synth/ooc_M68kFullCoreSynth.tcl`; confirm 0 err/crit, **WNS ≥ 0 (≥250; ~266 non-regress)**. Report WNS+FMAX+critical path. <250 → pipeline the offender (before/after); don't merge below 250.
- [ ] Step 4: commit `call-return: lock-step + honest synth >=250`.

---

## Self-Review
**Spec coverage:** indirect branch + JMP (T2); stack push/pop (T3); BSR+RTS (T4); JSR+RTR (T5); lock-step + gate (T6). ✓
**Placeholder scan:** the indirect-branch form + stack mechanism are explicit T1 decisions (spec §5) — implementer picks + records; not placeholders. No TBD.
**Type consistency:** the indirect branch reuses `completionPort.{mispredict,nextPc}` (existing) with the target from a source operand; the cracks emit existing store/load + branch µops; retPC = `nextPc` (existing DecodedUop field). RTR CCR restore via the committed-CCR path. ✓
