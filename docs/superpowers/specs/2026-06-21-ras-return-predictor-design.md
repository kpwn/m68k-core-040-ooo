# Slice 2 — Return-address stack (RAS) for RTS/RTR prediction (design)

**Timing amendment (2026-08-10):** RAS semantics, push/pop timing, and EU
verification below remain binding. Statements that `predictFire` mutates fetch
state on the same `feed.fire` cycle are superseded by
`2026-08-09-ipc-fetch-directed-btb-token-pipeline-amendment.md`. A taken BTB/RAS
fallback now captures its target on `feed.fire` C and performs the fetch/ring
action from registered state in C+1, while retaining the same C+1 target-command
timing.

**Goal:** Predict the target of returns (RTS/RTR) at fetch so a return costs ZERO pipeline
squash, the way slice 1 did for branches. Today RTS/RTR are deliberately UNPREDICTED (the
BTB excludes them) → every return pays the ~5–6-cycle commit-time squash. Call/return-heavy
code (the common case: a loop calling a leaf subroutine) is dominated by this.

**Builds on:** the merged BTB+bimodal predictor (slice 1, commit `7c7711b`). The RAS reuses
slice 1's `predTaken`/`predTarget` carry-down, the branch-EU `mispredict` verification, and
the commit-time recovery — all UNCHANGED. This is **slice 2 of 3**: slice 1 = BTB+bimodal
(done), slice 2 = RAS (this), slice 3 = gshare/GHR.

**Hard gate:** post-route FMax **≥ 200 MHz**, **≥ 2 fresh regens both ≥ 200**. The pre-existing
IQ `sbInt_busy` scoreboard is the current ~208 limiter; the RAS read/push/pop must NOT become
the new critical path.

**Invariant:** prediction is PERFORMANCE-ONLY. Committed architectural state is byte-identical
vs Musashi. A corrupt RAS is NEVER a correctness bug (the branch EU always loads + verifies the
real return target) — only a perf loss.

---

## 1. The RAS structure

A small circular return-address stack in the fetch front-end. Prefer a **separate
`frontend/Ras.scala`** (its own small structure with push/pop/predict ports) wired into
`FetchAlignPlugin`, parallel to how `BtbPlugin` is wired — do not entangle it with the BTB.

- `rasEntries = 16` (parameterized, power of 2). Each entry = a 32-bit return PC (a register
  Vec or small Mem; 16×32 is tiny — a register Vec is fine and gives 1-cycle combinational read).
- `rasSp : UInt(log2(rasEntries) bits)` — the speculative top-of-stack pointer (wraps).
- `count : UInt(log2(rasEntries)+1 bits)` — a saturating occupancy (0..rasEntries) so an EMPTY
  RAS predicts nothing. push → `count := min(count+1, rasEntries)`; pop → `count := max(count-1,0)`.
- **Overflow** (push when full): circular — overwrite the oldest (the pointer wraps); `count`
  saturates at `rasEntries`. A hint structure; EU-verify covers any resulting wrong guess.
- **Underflow** (pop when `count==0`): NO prediction (the return is left unpredicted, exactly
  as today — falls back to the commit-time redirect).
- **Invalidate:** clear `count := 0` (and optionally `rasSp := 0`) on the same `invalidateAll`
  that clears the I-cache / BTB. (Cheap; keeps the RAS from carrying stale state across a
  cache flush.)

Read (predict): `rasTop = ras[rasSp - 1]`, valid iff `count > 0`.

---

## 2. Push hook — fetch, on `isCall` (BSR/JSR)

In the aligner cycle (the SAME combinational phase where slice 1 stamps `predTaken`/`predTarget`
and suppresses post-branch window words), when the emitted slot's predecode metadata says
`isCall`:
- `retPC = slotPc + (slot.lenWords << 1)` — the call's fall-through PC (already computed by
  predecode; `PredecodeMeta`/the aligner expose `lenWords`/`isCall`).
- `ras[rasSp] := retPC ; rasSp := rasSp + 1 ; count := min(count+1, rasEntries)`.

The push fires on **every fetched call**, INDEPENDENT of whether the BTB predicts the call's
target (a cold call the BTB hasn't learned still pushes its return address, so the matching
return can be predicted). The call's own target prediction remains the BTB's job (slice 1,
unchanged — BSR is PC-relative, JSR is BTB-learned-indirect).

---

## 3. Pop + predict hook — fetch, on `isReturn` (RTS/RTR)

When the emitted slot is `isReturn` AND `count > 0`:
- `predTarget = rasTop`; stamp `feed.payload(slot).predTaken := True` and `.predTarget :=
  predTarget` (exactly like slice 1's BTB `slot0IsPred` stamping).
- Capture `predTarget` into the common registered fallback action. A
  RAS-predicted return is otherwise identical to a BTB taken branch: action C+1
  drives decodePc/fetchPc/pendingDrop and makes every pre-action ring entry
  stale while preserving a target command issued in C+1 as live.
- `rasSp := rasSp - 1 ; count := count - 1`.

When `count == 0` on a return: no prediction (no `predTaken`, no redirect) — the return falls
through to the existing commit-time handling.

---

## 4. Compose with the BTB

For each emitted slot, the prediction source is selected by predecode type:
- `isReturn` → **RAS** (`predTaken = (count>0)`, `predTarget = rasTop`).
- otherwise → **BTB** (the slice-1 path, unchanged).

Returns and BTB hits are mutually exclusive (slice 1 does not learn RTS/RTR into the BTB), so
there is no arbitration conflict. A call (`isCall`) is a normal BTB-predicted branch for its
TARGET *and* triggers a RAS push — both happen for the same instruction with no conflict (BTB =
the call's forward target; RAS = the push of the return address).

The same single-predicted-redirect-per-cycle discipline from slice 1 holds: at most one slot is
predicted-taken (slot0, with slot1 suppressed or deferred). Because a call and a (predicted)
return both redirect fetch, the RAS push/pop only ever needs to fire for the predicted slot —
**at most one push or one pop per cycle** (single-ported RAS). A call that is NOT predicted-taken
by the BTB (cold) still pushes (§2); a return with an empty RAS does not pop/predict (§3).

---

## 5. Carry-down + EU verify + recovery — REUSED unchanged from slice 1

No new EU or ROB logic. The return's `predTaken`/`predTarget` ride the existing slice-1 rails
(DecodePacket → DecodedUop → RenamedUop → IqContext → branch EU `u1`). The branch EU already:
- computes the actual return target `actualTarget = indTarget = T0` (the value LOADED from the
  stack by the RTS/RTR pop-load µop), and
- sets `mispredict = (predTaken != actualTaken) || (actualTaken && actualTarget != predTarget)`.

A correctly RAS-predicted return → `mispredict=False` → no flush (the win). A wrong RAS guess →
`mispredict=True` → the existing commit-time ROB-retire redirect corrects to the real return
address. This is identical to how slice 1 handles a BTB branch — the EU does not need to know
the prediction came from the RAS vs the BTB.

---

## 6. Recovery = ACCEPT CORRUPTION (no checkpoint)

The speculative `rasSp`/`count`/contents are NOT checkpointed or restored on a flush/redirect.
Rationale: the branch EU ALWAYS verifies the real return target, so a corrupt RAS can only cause
a mispredict (which recovers via the existing commit redirect) — never an architectural error.
On clean call/return loops (no interleaved mispredicts) the RAS stays coherent and predicts every
return; the sp self-heals as balanced calls/returns rebalance it. **sp-checkpoint-repair (carry
the speculative sp down + restore from the youngest survivor on flush) is a documented later
refinement** — added only if the measured mispredict rate on branchy-subroutine code warrants it.

---

## 7. Scope

IN: RTS, RTR (pop+predict); BSR, JSR (push). The push retPC = the call fall-through (nextPc),
from predecode `lenWords`.

OUT (deferred): sp checkpoint/repair (§6); any BTB change; the gshare/GHR upgrade (slice 3).

---

## 8. Files (expected touch list)

- NEW `frontend/Ras.scala` — the `RasPlugin` (or a small `Ras` area): the ring + sp + count, a
  push port (retPC), a pop/predict port (→ predTaken/predTarget), invalidate. Mirror the
  `BtbPlugin` wiring style.
- `frontend/FetchAlignPlugin.scala` — drive the RAS push (on `isCall` of the emitted slot) and
  pop/predict (on `isReturn`); compose the predict source (`isReturn ? RAS : BTB`); include the
  RAS-predicted return in the common registered fallback detector/action. Needs the
  emitted slot's `isCall`/`isReturn`/`lenWords` from predecode (already in `PredecodeMeta` per
  the explore).
- `types/PredecodeMeta.scala` / `frontend/PredecodeWord.scala` — CONFIRM `isCall`/`isReturn`/
  `lenWords` reach the aligner output; if a field is missing on the aligner slot, thread it
  (predecode already classifies BSR/JSR/RTS/RTR — see the explore).
- `top/FullCoreSynth.scala`, `bench/IpcBenchSpec.scala`, `lockstep/ExecuteLockStepSpec.scala`
  DUT wiring — instantiate + wire the RasPlugin (push/pop/invalidate).
- `core/ParamPlugin.scala` (or wherever params live) — `rasEntries` param.
- NO change to `BranchEuPlugin`, `RobPlugin`, the carry-down fields, or `BtbPlugin` (all reused).

---

## 9. Testing

**Correctness (byte-identical vs Musashi, RAS live, ×2+):**
- ALL existing call/return lock-step: bsr/rts round-trip, jsr (An)/(xxx).L/(d16,PC)/(d16,An) +
  rts, nested bsr, rtr, bsr+rtd, link/unlk — stay green.
- NEW: a deep-nested-call lock-step (≥3 call levels, returns unwind in LIFO order — exercises
  RAS push/pop depth + ordering).
- NEW: a call/return-with-interleaved-mispredict lock-step (a subroutine containing a
  mispredicting data-dependent branch, so the RAS is corrupted on the wrong path) — asserts the
  architectural result still matches Musashi (proves EU-verify recovers a corrupt RAS).
- NEW: a directed `RasPluginSpec` unit test — push/pop/predict, wrap on overflow, no-predict on
  underflow, invalidate clears count.

**Performance (IpcBenchSpec):**
- NEW `kCallReturn` kernel: a loop calling a leaf subroutine (e.g. `.loop: bsr leaf ; sub
  #1,%d7 ; bne .loop ; leaf: add ... ; rts`) so each iteration has a call + a return. Report
  IPC predictor-RAS-on vs the slice-1 baseline (RAS-off, returns unpredicted): the return should
  now be predicted (~zero squash) → a measurable IPC lift. Confirm the existing kernels
  (hot-loop, branchy, mixed) do not regress.

**FMax gate (HARD):** regenerate + post-route `synth/impl_FullCore.tcl`, **≥ 200 MHz, ≥ 2 fresh
regens both ≥ 200**. Check the route timing report's top path — the RAS read/push/pop must NOT be
the new critical path (the IQ `sbInt_busy` scoreboard is the current ~208 limiter). If the RAS is
the limiter, register the pop-predict read or shrink the path; re-gate.

---

## 10. Risks

- **FMax** — the RAS pop-read + the predTarget mux feed the same fetch-redirect path slice 1
  added; keep the ring read a shallow combinational `ras[rasSp-1]` (16-deep register Vec) and the
  predict-source mux (`isReturn ? RAS : BTB`) shallow. Gate on ≥2 regens ≥200.
- **Same-cycle push/pop discipline** — confirm at most one push OR one pop per cycle (§4); a slot
  that is both isn't possible (an instruction is a call XOR a return). The aligner emits one
  predicted-redirecting slot/cycle, so the RAS is single-ported.
- **Staleness** — a RAS-predicted return MUST use the same registered fallback
  action as a BTB prediction. Action C+1 kills old responses/ring entries and
  preserves only its captured target record. The interleaved-mispredict
  lock-step test guards architectural recovery.
- **Cold-call / empty-RAS** — first call pushes but its return may still be unpredicted if fetch
  went wrong-path; expected, amortized once warm. Underflow predicts nothing (safe).
