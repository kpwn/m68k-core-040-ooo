# Register the DTLB-miss→walker trigger Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development / executing-plans. Checkbox steps, TDD. Gate = LOCK-STEP (correctness, latency-agnostic) + a POST-ROUTE confirmation that the `valids→DTLB-walker` cone is GONE and WNS beats −1.206 (deterministic cone removal; don't chase the gen-noisy absolute FMax).

**Goal:** Sever the unjustified cross-module cone `Dcache valids → DTLB walker FSM` (WNS −1.206, the gate-failer) by registering the DTLB-miss→walker trigger in `DtlbPlugin`.

**Architecture:** Capture the miss request `{vpn, write, supervisor}` into `missReqReg` flops on the cycle a walk is needed; drive `walker.io.req`/`walker.io.start` from those flops instead of the live `_req`/`tlbHit`. +1 cycle to launch a walk (free — walk is multi-cycle, LS-EU stalls on the miss). Single-outstanding preserved (pulse start once; gate the capture on walker-idle + the existing `latchMatch` anti-respin).

**Tech Stack:** SpinalHDL / sbt `~/sbt/bin/sbt` / Verilator / Vivado. Reference: `mmu/DtlbPlugin.scala` (the live trigger :129-161 — `needWalk`, `walker.io.req`/`.start`, `tlbHit`, `latchMatch`, the registered `hr*` hit-path), `mmu/TableWalker.scala` (the walker FSM + its own AXI), `lockstep/ExecuteLockStepSpec.scala` (MMU programs). Spec: `docs/superpowers/specs/2026-06-07-dtlb-walker-trigger-reg-design.md`. Audit: `git show analysis/xmodule-cones:synth/XMODULE_CONES_AUDIT.md`. Post-route gate: `synth/impl_FullCore.tcl`.

**Branch:** `feat/dtlb-walker-trigger-reg` (off master).

## CRITICAL memory: never Verilator + vivado concurrently (OOM'd before); `free -h` + `pgrep vivado` before place&route (another agent may run sbt).

---

### Task 1: Diagnose the trigger + single-outstanding gating
**Files:** read-only `DtlbPlugin.scala` + `TableWalker.scala` + the audit. Confirm `needWalk`'s exact condition, how the walker signals busy/idle/done, and the `latchMatch`/`latchValid` anti-respin window — so the registered capture fires exactly once per miss without dropping or double-launching.
- [ ] Capture the plan in the Task-1 commit (empty ok).

### Task 2: Register the miss→walker trigger
**Files:** `mmu/DtlbPlugin.scala`; Test `mmu/DtlbWalkerSpec.scala` (or the existing DTLB/MMU directed spec).
Add `missReqReg = RegInit{valid=False, vpn, write, supervisor}`. Capture `{_req.vpn, _req.write, _req.supervisor}` when a walk is needed (gated walker-idle + not in the `latchMatch` window). Drive `walker.io.req.{vpn,isWrite,isSuper}` + `walker.io.start` from `missReqReg`. Keep the registered `hr*` hit-path + `rootPtr` as-is.
- [ ] **Step 1: failing/representative directed test** — a DTLB miss launches the walk one cycle later off the registered trigger; single-outstanding (no double-walk); the walk fills + the access retries. Run → implement → PASS ×2.
- [ ] **Step 2: commit** `dtlb: register the miss->walker trigger (sever the Dcache valids->walker cone)`.

### Task 3: Lock-step (MMU unchanged) + post-route cone-removal confirmation
**Files:** none (run suites).
- [ ] Step 1: lock-step vs Musashi ×2 — ALL MMU/demand-paging programs UNCHANGED (mmu-st-ld, mmu-ld-alu-st, page-fault→handler→map→RTE→resume, format-$7, ITLB) + ALL existing. ITLB seed flake → baseline-repro `8ae3afc`-or-current-master first.
- [ ] Step 2: `make test-fast` + targeted verilator `-z` subsets — report totals.
- [ ] Step 3: **POST-ROUTE CONE-REMOVAL CONFIRMATION** — gen + `vivado -mode batch -nojournal -source synth/impl_FullCore.tcl`. Confirm the `valids → DTLB walker` / `→ walker fsm_stateReg` cone is OFF the worst-path list (read `synth/fullcore_route_timing.rpt`) and WNS is materially better than the master −1.206 (the gate-failer gone). Report the new worst path + WNS. (One run confirms the structural cone removal; don't chase the gen-noisy absolute number.)
- [ ] Step 4: commit `dtlb-walker-trigger-reg: lock-step unchanged + valids->walker cone removed (WNS vs -1.206)`.

---

## Self-Review
**Spec coverage:** registered miss→walker trigger (T2); MMU lock-step unchanged + cone-removal confirmation (T3). ✓
**Placeholder scan:** the capture-gating (walker-idle + latchMatch) is an explicit T1 diagnosis (spec §5). No TBD.
**Type consistency:** `missReqReg.{valid,vpn,write,supervisor}` (RegInit) feeds `walker.io.req`/`.start` (existing walker ports); the registered `hr*` hit-path + `latchMatch` anti-respin unchanged. Latency-agnostic (+1 walk-launch cycle). ✓
