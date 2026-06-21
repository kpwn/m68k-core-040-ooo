# Slice 3 — gshare/GHR direction predictor (design)

**Goal:** Predict the DIRECTION of correlated / data-dependent conditional branches that the
slice-1 per-PC bimodal misses. The `branchy` IPC kernel is stuck at 0.236 because its inner
`beq` alternates taken/not-taken (a deterministic 1-0-1-0 driven by the prior iteration) — a
per-PC 2-bit counter oscillates ~50%, but a global-history-indexed PHT captures the pattern.

**Builds on:** BTB+bimodal (slice 1, `7c7711b`) + RAS (slice 2, `2576184`). The BTB still
supplies the TARGET; gshare supplies the DIRECTION for conditional branches. Reuses slice-1's
predTaken/predTarget carry-down, the branch-EU mispredict verify, and the commit-time recovery —
all UNCHANGED. This is **slice 3 of 3** (final branch-predictor slice).

**Hard gate:** post-route FMax **≥ 200 MHz**, **≥ 2 fresh regens both ≥ 200**. The PHT Mem read +
the XOR index must NOT become the new critical path.

**Invariant:** prediction is PERFORMANCE-ONLY. Committed architectural state is byte-identical vs
Musashi. A gshare misprediction (or a corrupt GHR) is only a perf loss — the branch EU always
verifies the actual direction/target and recovers via the existing commit redirect.

---

## 1. The GsharePlugin

A new `frontend/Gshare.scala` (`GsharePlugin`), wired like BtbPlugin/RasPlugin (directionless
plain wires driven/read by the wiring layer; idle-defaulted so a standalone DUT elaborates).

- `ghr : Reg(UInt(ghrBits bits))` — the speculative global history register. `ghrBits =
  Global.GHR_BITS` (from `M68kParams.gshareHistory`, default 16).
- `pht : Mem(UInt(2 bits), phtEntries)` — pattern history table of 2-bit saturating counters.
  `phtEntries = Global.PHT_ENTRIES` (from `M68kParams.gshareEntries`, default 2048). `idxBits =
  log2Up(phtEntries) = 11`. Init each counter to **weakly-taken (2)** so a cold conditional that
  the BTB tracks (it has a target ⇒ was taken at least once) predicts taken until trained.
- **Index:** `phtIndex(pc) = fold(pc(31 downto 1), idxBits) XOR fold(ghr, idxBits)` where `fold`
  reduces a wider value to `idxBits` by XOR-ing its chunks (PC is wider than idxBits; GHR is 16 →
  folded to 11). Classic gshare. Document the exact fold in the code.
- Combinational read port (per query PC): `phtTaken = pht.readAsync(phtIndex) >= 2`; also exposes
  `phtIndexComb` (the 11-bit index) for the carry-down.
- GHR shift control (in, from FetchAlign): `shiftValid` + `shiftDir` → `when(shiftValid) { ghr :=
  (ghr << 1) | shiftDir }`.
- Update port (in, from ROB retire): `updateValid` + `updateIndex(11b)` + `updateTaken` →
  `pht(updateIndex) := saturate±1(updateTaken)`.
- `invalidateAll` (from the I-cache invalidate): `ghr := 0` (PHT may be left as-is — it self-
  retrains; clearing is optional).

---

## 2. Direction composition (fetch)

gshare overrides ONLY the direction of CONDITIONAL branches that hit the BTB. The BTB lookup
already returns `brType` (0=cond, 1=uncond) with its target; expose it so FetchAlign can form:

```
condBtbHit = btbHit && (brType == cond)         // a conditional branch the BTB tracks
predDirCond = phtTaken                            // gshare's direction for it (from the PHT)
```

- **Conditional BTB hit:** the predicted-taken decision becomes `phtTaken` (NOT the BTB counter);
  `predTarget = btbTarget` (unchanged — gshare never touches the target).
- **Unconditional (BRA/BSR/JMP/JSR):** unchanged (BTB force-taken via brType==uncond).
- **Return (RTS/RTR):** unchanged (RAS, slice 2).
- **BTB miss:** unchanged (no prediction).

So the only change to the slice-1/2 slot prediction is: for a conditional BTB hit, the
predicted-taken bit is sourced from the PHT instead of the BTB counter. Everything downstream
(stamp, suppress, predictFire redirect) is the slice-1 path, keyed on this new predicted-taken.

The BTB's own bimodal counter stays maintained at retire (slice 1) but is IGNORED for
conditionals (gshare supplies their direction). Leaving it maintained keeps the BTB self-
contained; no BTB change.

---

## 3. Both predicted directions matter (the win)

The branchy `beq` must be predicted right on BOTH its taken and not-taken iterations:

- **Predicted TAKEN conditional** (`condBtbHit && phtTaken`): stamp `predTaken=True`,
  `predTarget=btbTarget`, suppress post-branch words, redirect fetch (the slice-1 `predictFire`
  path), and shift `GHR<<1 | 1`.
- **Predicted NOT-TAKEN conditional** (`condBtbHit && !phtTaken`): leave `predTaken=False` (the
  default — NO redirect, fall through, slot1 stays valid), and shift `GHR<<1 | 0`.

Every conditional (taken or not) carries its `phtIndex` down (§5). The branch EU's existing
`mispredict = (predTaken != actualTaken) || (actualTaken && actualTarget != predTarget)` verifies
BOTH cases with NO change:
- predicted-not-taken + actually-not-taken → `mispredict=False` → no squash (the win on N iters).
- predicted-taken + actually-taken (+ target match) → `mispredict=False` → no squash (win on T iters).
- any wrong guess → `mispredict=True` → the existing commit redirect to the actual nextPc.

On branchy, the PHT entries for the two GHR contexts (after-taken vs after-not-taken) learn the
opposite directions, so both iterations predict correctly → the ~50% squash rate collapses.

---

## 4. GHR speculative update (fetch)

On `feed.fire && condBtbHit` (a predicted conditional is emitted), drive `shiftValid=True`,
`shiftDir = phtTaken` (the predicted direction bit). Only CONDITIONAL branches update the GHR
(unconditionals/returns carry no directional information and are excluded). The lookup index uses
the GHR BEFORE this shift; the carried `phtIndex` matches that lookup, so the retire update (§5)
trains the right entry.

Note: at most one predicted-redirecting slot/cycle (slice-1 discipline), but a predicted-NOT-taken
conditional does NOT redirect yet still updates the GHR — so the GHR shift is gated on
`condBtbHit` (a conditional was emitted), NOT on `predictFire` (taken-only). A predicted-not-taken
conditional in slot0 lets slot1 proceed (fall-through); ensure the GHR shifts exactly once for the
emitted conditional (gate on the slot actually consumed by `feed.fire`).

---

## 5. Retire-time PHT update (carry the fetch index down)

Add `phtIndex : UInt(11 bits)` (and a `isCond` marker, or reuse brType) to the carry-down:
DecodePacket → DecodedUop → RenamedUop → IqContext → branch EU → BranchCompletion → ROB per-entry
store → retire. This mirrors the slice-1 `btbPc`/`btbTaken` path exactly.

At retire, when a CONDITIONAL branch retires (the ROB knows `isCond`/brType + actualTaken + the
carried `phtIndex`), drive a new `phtUpdate` Flow: `updateValid=True, updateIndex=phtIndex,
updateTaken=actualTaken`. The GsharePlugin does `pht(updateIndex) := saturate±1(actualTaken)`.

Using the FETCH-TIME `phtIndex` (carried) — not a recomputed retire-time index — is MANDATORY:
the speculative GHR at retire differs from the GHR at the lookup, so only the carried index trains
the entry the lookup actually read. (This carry-down is required regardless of the recovery scheme.)

---

## 6. Recovery = ACCEPT CORRUPTION (no checkpoint)

The speculative `ghr` is NOT checkpointed or restored on a flush/redirect. After a mispredict the
GHR carries wrong-path bits, which shift out naturally over ~`ghrBits` predicted-conditional
cycles. EU-verify guarantees correctness (a gshare direction mispredict is just the existing
commit redirect; the carried predTaken is verified exactly as in slice 1). NO new EU/recovery
logic — only the `phtIndex` carry + the update/shift ports.

**GHR checkpoint/restore is a documented later refinement** (carry the GHR snapshot down + restore
from the mispredicting branch's snapshot at the commit redirect) — added only if the measured
branchy/correlated accuracy is poor with accept-corruption.

---

## 7. Scope

IN: Bcc and DBcc (conditional branches) get gshare direction; the GHR + PHT + retire update.

OUT (deferred): GHR checkpoint/restore (§6); a tournament / choice predictor (gshare REPLACES the
bimodal for conditionals — if a kernel regresses from PHT aliasing, that is a measured FINDING for
a future tournament slice, not built here); any change to unconditional/return prediction or the
BTB target path.

---

## 8. Files (expected touch list)

- NEW `frontend/Gshare.scala` — the `GsharePlugin`: `ghr` reg, `pht` Mem, the combinational
  read (phtTaken + phtIndexComb), the shift port, the update port, invalidate.
- `frontend/Btb.scala` — expose `brType` on the lookup output (so FetchAlign can form
  `condBtbHit`); the BTB already stores brType — surface it on the combinational predict port.
- `frontend/FetchAlignPlugin.scala` — query the Gshare PHT (per slot PC), form `condBtbHit`,
  source the conditional predicted-taken from `phtTaken`, drive the GHR shift (`shiftValid/
  shiftDir`) on the emitted conditional, and capture `phtIndex` onto the slot's DecodePacket.
- `frontend/DecodePacket.scala`, `decode/DecodedUop.scala`, `rename/RenamedUop.scala`, the IQ
  context, `execute/BranchEuPlugin.scala` (BranchCompletion), `rob/RobPlugin.scala` — thread
  `phtIndex` (+ `isCond`/brType if not already present) to retire; drive the `phtUpdate` Flow at
  retire (mirror the slice-1 btb-update path).
- `top/FullCoreSynth.scala`, `bench/IpcBenchSpec.scala`, `lockstep/ExecuteLockStepSpec.scala`
  DUT wiring — instantiate + wire the GsharePlugin (query←fetch, shift←fetch, update←retire,
  invalidate←icache).
- `Global.scala` + `core/ParamPlugin.scala` — add `GHR_BITS` / `PHT_ENTRIES` keys, published from
  `M68kParams.gshareHistory`/`gshareEntries` (the params already exist).
- NO change to the branch-EU mispredict logic, the commit-time recovery, the RAS, or the BTB
  target path.

---

## 9. Testing

**Correctness (byte-identical vs Musashi, gshare live, ×2+):** ALL existing branch / loop /
call-return lock-step green (Bcc taken/not-taken, DBcc/DBRA loops, backward bne loops, bsr/jsr/
rts/rtr, nested, the BTB-staleness + RAS tests). Prediction is perf-only — any divergence is a
real carry-down/update/staleness bug → FIX it.

**Unit:** a directed `GsharePlugin`/`GshareSpec` — index = `fold(pc) XOR fold(ghr)`; counter
saturate ±1; GHR shift; and a learns-the-alternation check (train an alternating outcome at a PC
across two GHR contexts → the two PHT entries converge to opposite directions).

**Performance (IpcBenchSpec):**
- **kBranchy MUST improve** (from 0.236) — the alternating `beq` is gshare's target; report the
  before/after + the mispredict/flush count drop.
- **CRITICAL REGRESSION-WATCH:** hot-loop (0.501), mixed (~0.59), call-return (0.319), and the
  ALU/load kernels must NOT regress. gshare-replaces-bimodal can alias a strongly-biased branch
  across history contexts and predict it worse; the harness measures all kernels — if one
  regresses materially, REPORT it (a finding for a tournament refinement), do not hide it.

**FMax gate (HARD): ≥ 200 MHz, ≥ 2 fresh regens both ≥ 200.** Regenerate + post-route
`synth/impl_FullCore.tcl`; check the route timing report's top path — the PHT Mem read + the XOR
index + the GHR must NOT be the new critical path (the IQ `sbInt_busy` scoreboard / front-end
arcs are the current ~205-218 limiters). If the gshare path limits, register the PHT read or
shrink the index fold and re-gate. Report BOTH regen numbers + the critical path. If <200
reliably, STOP and report DONE_WITH_CONCERNS — do NOT merge below gate.

---

## 10. Risks

- **PHT aliasing regression** — gshare replacing the bimodal can worsen a strongly-biased branch.
  Mitigation: the IPC regression-watch (§9); a tournament is the documented follow-up if measured.
- **FMax** — the PHT Mem read + XOR index feeds the fetch predicted-taken mux. Mitigation: a
  shallow async/sync PHT read indexed by the folded XOR; gate on ≥2 regens ≥200.
- **GHR-index consistency** — the lookup index (pre-shift GHR) and the carried/updated index must
  match exactly. Mitigation: capture `phtIndex` at the SAME point the lookup is read; the unit
  test's learns-the-alternation check guards it.
- **Accept-corruption accuracy** — a polluted GHR after a mispredict lowers accuracy until it
  shifts out. Acceptable for slice 3 (the chosen scheme); GHR checkpoint is the refinement if the
  measured branchy gain is weak.
