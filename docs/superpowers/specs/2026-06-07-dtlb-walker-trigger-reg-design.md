# Register the DTLB-miss→walker trigger (cross-module cone elimination #1) — Design

**Status:** Draft (the priority fix from the cross-module-cone audit `synth/XMODULE_CONES_AUDIT.md`). Standing rule: registered module boundaries ([[synth-gate-every-slice]]).
**Date:** 2026-06-07

## 1. Purpose & motivation

The worst post-route cone (current master, WNS **−1.206 ns**, the whole top-10) is `DcachePlugin valids → DtlbPlugin walker FSM` — an UNJUSTIFIED cross-module combinational cone. Root cause (audit): the walk-**trigger** is live-combinational. `walker.io.req.vpn := _req.vpn` and `walker.io.start := needWalk` where `needWalk = mmuEnable && _req.valid && !tlbHit && …` is driven off the LIVE banked `tlb.io.hit` (the deep hitVec) AND the LS-EU-driven `_req.vpn`. The synthesizer legally merges D-cache `valids`/`ldS1Set` into that VPN/hit cone, landing a D-cache valid bit at the walker FSM's clock-enable — a 17-level, 70%-route arc spanning Dcache→LsEu→DTLB.

The table walker has its OWN AXI port — there is NO data coupling to the cache; the coupling is purely the trigger chain. Register it: capture the miss request into flops and drive `walker.io.start`/`walker.io.req` from those flops. The cross-module combinational path is severed at the register; +1 cycle to LAUNCH a walk is free (the walk is already multi-cycle + the LS-EU stalls on a DTLB miss until `rsp.ready`).

## 2. Scope

**In:**
- **A registered miss-request stage in `DtlbPlugin`:** add `missReqReg = {valid, vpn, write, supervisor}` flops (RegInit). On a cycle where the access misses the DTLB and needs a walk (`needWalk` today), capture `{_req.vpn, _req.write, _req.supervisor}` into `missReqReg` (valid pulse). Drive `walker.io.req.{vpn,isWrite,isSuper,rootPtr} := missReqReg.*` (+ registered `rootPtr`) and `walker.io.start := missReqReg.valid` from the FLOPS, not the live `_req`/`tlbHit`. The deep `hitVec`/valids cone now ends at `missReqReg`.
- **Preserve walk correctness + single-outstanding:** the walker is single-outstanding; the registered trigger must pulse `start` exactly once per miss (don't re-launch while the walker is busy — gate the capture on walker-idle + the existing `latchMatch`/`latchValid` anti-respin logic). The +1-cycle delay to walk-start is invisible: the LS-EU holds the request valid + stalls (`rsp.ready=False`) until the walk fills the TLB. Confirm no miss is dropped (the capture fires when `needWalk` would have, just registered) and no double-walk.
- **Keep the existing registered hit-path (`hr*`)** + the `latchMatch` post-walk anti-respin — unchanged.
- **Verification:** ALL MMU/demand-paging lock-step programs UNCHANGED ×2 (a DTLB miss → walk → fill → retry → translated access; data + instruction page faults; the format-$7 delivery). The +1 walk-launch cycle is latency-agnostic. Confirm (deterministic, the point of this slice): the `valids → DTLB walker` cone is GONE from a post-route worst-path list and WNS improves from −1.206 (the next cone binds — better). The cone removal is RTL-deterministic even though the absolute FMax is gen-noisy.

**Out:** the exc-mux cone (slice #2 = land the exc-private-D-cache-port branch); the regfile readAsync cone (#3, deferred); the ITLB walker (`ItlbPlugin` — if it has the SAME live-trigger pattern, fix it the same way as a fast-follow, but scope this slice to the DTLB).

## 3. Dataflow

```
cycle N   : access misses DTLB -> needWalk -> missReqReg <= {vpn, write, super}  (valid pulse, gated walker-idle)
cycle N+1 : walker.io.req <= missReqReg.* ; walker.io.start <= missReqReg.valid   (off FLOPS — cone severed)
            walker runs (multi-cycle AXI walk) -> fills TLB -> LS-EU retries (was already stalled)
```

## 4. Verification
- **Directed:** a DTLB miss launches the walk one cycle later off the registered trigger; no double-walk; the walk result fills the TLB + the access retries correctly.
- **Lock-step (the gate), ×2:** ALL MMU programs UNCHANGED — `mmu-st-ld`, `mmu-ld-alu-st`, page-fault→handler→map→RTE→resume, format-$7, ITLB tests. ALL existing UNCHANGED. ITLB seed flake → baseline-repro first.
- **`make test-fast`** + targeted verilator `-z` subsets.
- **Post-route confirmation (deterministic cone removal — NOT a noisy-FMax gate):** gen + `impl_FullCore.tcl`; confirm the `valids → DTLB walker` / `→ walker FSM` cone is OFF the worst-path list and WNS is materially better than −1.206 (the gate-failer is gone). Report the new worst path (expected: the exc-mux or regfile cone — the next audit items). One run is enough to confirm the cone removal (it's structural).

## 5. Open items
- The exact `needWalk` capture gating: today `needWalk` already excludes the post-walk `latchMatch` window; the registered capture must fire on the SAME condition (don't miss a walk, don't double-fire). Confirm against the walker's busy/idle.
- `rootPtr` is already a registered control input — register it into `missReqReg` too (or keep driving it live since it's already a flop; the cone is the vpn/hit part).
- If `ItlbPlugin` has the identical live-trigger, note it for a fast-follow (same fix).
