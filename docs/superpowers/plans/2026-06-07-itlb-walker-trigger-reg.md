# Register the ITLB-miss→walker trigger Implementation Plan

> Gate = LOCK-STEP (correctness, latency-agnostic) + a POST-ROUTE confirmation that the I-side
> `_req/tlbHit → ITLB-walker FSM` cone is GONE (walker driven from `missReqReg` flops) + WNS reported.
> This is a MIRROR of the merged DTLB fix (commit 10ce410 / `feat/dtlb-walker-trigger-reg`).

**Goal:** Sever the cross-module combinational cone `_req.vpn / banked tlbHit → ITLB walker FSM`
by registering the ITLB-miss→walker trigger in `ItlbPlugin`, exactly as done for the DTLB.

**Architecture:** Capture the miss request `{vpn, supervisor, robId}` into `missReqReg` flops on the
cycle a walk is needed; drive `walker.io.req`/`walker.io.start` from those flops instead of the live
`_req`/`tlbHit`. Fetch is always a read → `walker.io.req.isWrite := False` stays constant.
+1 cycle to launch (free — walk is multi-cycle, the I-fetch stalls on the ITLB miss).
Single-outstanding preserved (pulse start once; gate the capture on walker-idle + the existing
`latchMatch` anti-respin + `!missReqReg.valid` pending-suppress).

**Tech Stack:** SpinalHDL / sbt `~/sbt/bin/sbt` / Verilator / Vivado.
Reference: `mmu/DtlbPlugin.scala` (the merged template, commit 69da473), `mmu/ItlbPlugin.scala`
(lines 95-111 — the live trigger), `mmu/TableWalker.scala` (walker FSM + its own AXI),
`mmu/ItlbSpec.scala` + `mmu/DtlbSpec.scala` (the single-outstanding directed test to mirror).
Spec: `docs/superpowers/specs/2026-06-07-itlb-walker-trigger-reg-design.md`.
Post-route gate: `synth/impl_FullCore.tcl`; worst paths in `synth/fullcore_route_timing.rpt`.

**Branch:** `feat/itlb-walker-trigger-reg` (off master, spec at 64e686f).

## CRITICAL memory: never Verilator + vivado concurrently (OOM'd before); `free -h` + `pgrep vivado` before place&route.

---

### Task 1: Diagnose the trigger + single-outstanding gating
Read-only `ItlbPlugin.scala` + the merged DTLB template. Confirm `needWalk`'s exact condition,
walker busy/idle/done, the `latchMatch`/`latchValid` anti-respin window — so the registered capture
fires exactly once per ITLB miss without dropping or double-launching. (Done: identical to DTLB.)

### Task 2: Register the miss→walker trigger
**Files:** `mmu/ItlbPlugin.scala`; Test `mmu/ItlbSpec.scala`.
Add `missReqReg = RegInit{valid=False, vpn, sup, robId}`. Capture `{_req.vpn, _req.supervisor, umAccessRobId}`
when a walk is needed, gated `&& !missReqReg.valid`. Drive `walker.io.req.{vpn,isSuper}` + `walker.io.start`
from `missReqReg` (isWrite stays False; rootPtr stays live). `walkVpn`/`walkRobId` latch on `missReqReg.valid`.
- [ ] **Step 1: directed test** mirroring DtlbSpec — a held ITLB miss launches EXACTLY ONE 3-level walk
  (3 ARs), no re-walk while the resolved req stays valid. Run → implement → PASS ×2.
- [ ] **Step 2: commit** `itlb: register the miss->walker trigger (sever the _req/tlbHit->walker cone)`.

### Task 3: Lock-step (ITLB/I-fetch unchanged) + post-route cone-removal confirmation
- [ ] Step 1: lock-step vs Musashi ×2 (targeted `-z` subsets) — ALL ITLB/I-fetch programs UNCHANGED
  (ITLB non-identity-map + non-resident-I-page→format-$7→handler→RTE + the broad set) + ALL existing.
  KNOWN ITLB-non-resident seed flake → baseline-repro on current master at the SAME seed before
  attributing; distinguish a real regression from that flake.
- [ ] Step 2: `make test-fast` + targeted verilator subsets — report totals.
- [ ] Step 3: **POST-ROUTE CONE-REMOVAL CONFIRMATION** — gen `GenFullCoreSynthVerilog` +
  `vivado -mode batch -nojournal -source synth/impl_FullCore.tcl`. Confirm the ITLB-walker cone
  (`Itlb…walker fsm_stateReg` / `_req→walker`) is OFF the worst-path list (read
  `synth/fullcore_route_timing.rpt`); confirm the generated Verilog drives the walker from `missReqReg`.
  Report WNS + the new worst path. (Structural cone removal is the win; absolute FMax is gen-noisy.)
- [ ] Step 4: commit `itlb-walker-trigger-reg: lock-step unchanged + _req/tlbHit->walker cone removed (WNS)`.

---

## Self-Review
**Spec coverage:** registered miss→walker trigger (T2); ITLB lock-step unchanged + cone-removal confirmation (T3). ✓
**Type consistency:** `missReqReg.{valid,vpn,sup,robId}` (RegInit) feeds `walker.io.req`/`.start`;
`isWrite` constant False (fetch is a read); `latchMatch` anti-respin + registered hit-path unchanged.
Latency-agnostic (+1 walk-launch cycle). ✓
