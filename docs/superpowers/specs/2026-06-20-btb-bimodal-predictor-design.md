# Slice 1 — Fetch-time BTB + bimodal branch predictor (design)

**Goal:** Lift front-end-bound IPC on branchy/loop/mixed code by predicting branch
direction + target IN the instruction fetch front-end, so a **correctly-predicted**
branch costs ZERO pipeline squash (today every taken branch squashes ~5–6 cycles at
ROB-retire). Measured baseline: branchy IPC 0.23, mixed 0.57, aggregate 0.46;
the backend dual-retires 83% when active but is active only 44% (front-end starved).

**Locked direction (memory `branch-handling-direction`):** dedicated branch EU (exists),
NaxRiscv-style BTB+predictor in ifetch, decode/fetch-time redirect on predicted-taken /
unconditional. This is **slice 1 of 3**: BTB + 2-bit bimodal + carry-down + EU verify +
commit-time recovery. Slice 2 = return-address stack (RTS/RTR). Slice 3 = gshare/GHR
upgrade if the bimodal mispredict rate warrants.

**Hard gate:** post-route FMax **≥ 200 MHz** (the standing project gate). The DecodeStage
decode cone is the current ~218 MHz limiter; the new BTB lookup path must stay under the
fetch budget and not become the new limiter.

---

## 1. Architecture overview

```
            fetch PC
               │
      ┌────────┴─────────┐
      ▼                  ▼
  I-cache read       BTB read           (parallel, same set-index style)
   (tag+data)      (tag+target+ctr)
      │                  │
      │            hit && ctr>=2 ?  ──── yes ──► predictRedirect(target) → fetchPc next cycle
      ▼                  │                         (suppress post-branch insns in the window)
   aligned insns  ◄──────┘ predTaken/predTarget attached to the predicted branch
      │
   decode → rename → ROB/IQ  (predTaken,predTarget carried per-uop)
      │
   branch EU (S1): compute actual taken+target
      │   mispredict = (predTaken != actualTaken) || (actualTaken && actualTarget != predTarget)
      ▼
   ROB retire:
     - mispredict → existing commit-time redirect to actual nextPc (recovery)
     - always    → BTB update (alloc tag/target/brType + bump 2-bit counter)
```

Prediction is **performance-only**: it never changes committed architectural state. The
architectural result of every program is byte-identical to today (verified vs Musashi).

---

## 2. The BTB structure

**Tagged, direct-mapped, `btbEntries = 128` (parameterized, power of 2).** One SRAM-backed
table. Index = `fetchPc[1+log2(btbEntries) downto 2]` (word-granular; the low bit of PC is
always 0 for instructions — index off PC bits above the 2-byte word). Tag = the PC bits
above the index field.

Entry layout (`BtbEntry` bundle):
- `valid : Bool`
- `tag : UInt(tagBits)` — upper fetch-PC bits (no aliasing false-hits)
- `target : UInt(32 bits)` — the learned taken-target (PC-relative resolved, OR the
  last-seen indirect target for `JMP (An)`)
- `brType : UInt(2 bits)` — 0=cond (Bcc/DBcc), 1=uncond (BRA/BSR/JMP/JSR), 2=reserved
  (RTS — slice 2), 3=reserved. Used for stats + to force-taken unconditionals.
- `counter : UInt(2 bits)` — 2-bit saturating bimodal (0,1 = not-taken; 2,3 = taken).

**Read:** synchronous SRAM read indexed by the fetch set bits, registered alongside the
I-cache `rsp` (same pipeline stage as the I-cache window/predecode). On the registered
output: `btbHit = valid && (tag == fetchPc_tag)`. `predictTaken = btbHit && (counter >= 2
|| brType == uncond)`. (An unconditional's counter saturates to taken anyway; the
`brType==uncond` OR is belt-and-suspenders so a freshly-allocated uncond predicts taken on
the cycle it is first installed-then-refetched.)

**A BTB miss ⇒ predict not-taken ⇒ sequential fetch** (the safe default; a never-before-
seen branch costs one commit-time mispredict on its first taken execution, then is learned).

**Write (one port, from ROB retire):** on a retiring branch, `BTB[index(branchPc)] :=
{valid:=1, tag:=tag(branchPc), target:=actualTarget, brType:=brType, counter := updated}`.
Counter update: taken → `min(counter+1,3)`; not-taken → `max(counter-1,0)`. Allocation of a
new entry seeds `counter := taken ? 2 : 1` (weakly, in the resolved direction). Unconditional
branches seed/peg `counter := 3`.

The BTB is **invalidated** (all `valid := 0`) on the same signal that invalidates the
I-cache (`invalidateAll`) and is naturally coherent because it is re-learned; no snoop.

---

## 3. Fetch-time prediction + redirect

When the registered BTB lookup for a fetch window says `predictTaken`:

1. A new **`predictRedirect : Flow(UInt(32 bits))`** port into `FetchAlignPlugin` carries
   `entry.target`. It sits in the redirect priority **below** the commit-time
   `mispredictRedirect`/external `redirect` (architectural redirects always win over a
   speculative prediction) but drives `fetchPc`/`decodePc` the same way the existing
   redirects do (reuse the `decodePc := newPc; fetchPc := newPc(31 downto 3) @@ 0;
   pendingDrop := newPc(2 downto 1); recStale on the in-flight fetch` machinery verbatim).
2. The predicted-taken branch is in some word of the fetch window. Instructions in the
   window **after** the branch are wrong-path and must be suppressed: the aligner/feed must
   not emit slot1 (or any later word) once a predicted-taken branch is emitted in an earlier
   slot. Concretely: the predicted-branch word index gates `slot1Valid := slot1Valid &&
   !slot0PredictedTaken` (and, when slot0 itself is the predicted-taken branch, fetch
   redirects so the next window starts at target).
3. The `~1-cycle bubble` is the redirect turnaround (predict this cycle → redirected fetch
   next cycle). A hot loop reaches steady state with the loop-top BTB entry warm, so the
   back-edge is predicted every iteration → no squash.

**Staleness:** the per-fetch `recStale`/`recDrop` tracking (already robust to overlapping
redirects) now also fires on a `predictRedirect`. This is the highest-risk interaction —
multiple redirects (predict + a coincident commit redirect) in flight — and is a primary
test focus (the explore flagged the prior "dropCount leak" bug class here).

---

## 4. Carry-down + EU verification

Add two fields to the per-instruction payload, threaded fetch→decode→rename→IQ/ROB→branch EU:
- `predTaken : Bool` — this instruction was predicted taken at fetch.
- `predTarget : UInt(32 bits)` — the target fetch was redirected to (only meaningful when
  `predTaken`).

These ride the existing PC/nextPc/branchDisp carriers (DecodePacket → DecodedUop →
RenamedUop → IqContext → branch EU `u1`). Non-branch uops carry `predTaken=False`.

The branch EU (resolves at S1 today) already computes `taken` and `target`/`nextPc`. Add:
```
actualTaken  = (the existing redirect condition: ibranch | (Bcc taken) | dbBranch ...)
actualTarget = (the existing target: relTarget / indTarget)
mispredict   = (predTaken =/= actualTaken) || (actualTaken && (actualTarget =/= predTarget))
```
The `completionPort.payload.mispredict` flag **changes meaning from "taken" to
"mispredicted"** (today it is `s1Valid && redirect`; it becomes `s1Valid && mispredict`).
`completionPort.payload.nextPc` stays the actual next PC (target if actually taken, else
fall-through) — exactly what the recovery redirect needs.

**The BTB-update payload** (branchPc, actualTaken, actualTarget, brType) must also reach the
ROB retire stage. The branch's PC + computed actualTarget are available at the EU; carry
them to the ROB entry (the ROB already stores `nextPcStore`; add `btbTarget`/`btbTaken`/
`brType`/`isBranch` per-entry, written from the branch completion, read at retire to drive
the BTB write port).

---

## 5. Commit-time recovery (reuse existing path)

On `mispredict`, recovery reuses today's machinery unchanged:
- ROB retire of a mispredicted branch pulses `doFlushReg` with `flushPc := actual nextPc`
  (today it pulses on `mispredictStore(h0)`; that store now holds "mispredicted" not
  "taken").
- `doFlush` fans out to `iq.flushPort`, `DecodeStage.pipeFlush`, `RenameStage.pipeFlush`,
  and `FetchAlign.mispredictRedirect` — all unchanged.

A **correctly-predicted** branch sets `mispredict=False` → no flush → the speculatively-
fetched correct-path successors retire normally (the win). Bimodal has **no GHR**, so there
is no global predictor history to checkpoint/restore on a mispredict — the only recovery is
the PC redirect (which exists). The BTB itself is updated at retire (§2), so wrong-path
branches never corrupt it.

---

## 6. Scope (slice 1)

IN: Bcc, BRA, BSR, JMP, JSR (including `JMP (An)`/`JSR (An)` indirect via the learned
`target` — the BTB stores last-seen target; a wrong guess is a normal mispredict). DBcc is
predicted on its `dbBranch` condition (treat like a Bcc).

OUT (deferred):
- **RTS/RTR returns** — indirect via the stack; a BTB last-target is a poor predictor for
  returns. These keep paying the commit-time redirect in slice 1; the **return-address stack
  is slice 2**.
- **Execute-time fast recovery** — mispredicts stay commit-time (~5–6 cyc) in slice 1.
- **gshare/GHR** — slice 3, only if the bimodal mispredict rate warrants.

---

## 7. Files (expected touch list)

- `cache/IcacheTypes.scala` / new `frontend/Btb.scala` (or `cache/`) — `BtbEntry` bundle +
  the BTB table (Mem) + lookup/update logic. Prefer a **separate `BtbPlugin`** (its own
  SRAM, its own read port off fetch PC, its own write port from retire) rather than
  overloading the I-cache `predMem` (which is predecode scaffolding — leave it alone).
- `frontend/FetchAlignPlugin.scala` — the new `predictRedirect` Flow input + its priority +
  the post-predicted-branch slot suppression. The BTB read is wired to the fetch PC; the
  registered lookup result drives `predictRedirect` and the per-uop `predTaken`/`predTarget`.
- `frontend/DecodePacket.scala`, `decode/DecodedUop.scala`, `rename/RenamedUop.scala`,
  the IQ context, the ROB entry — add `predTaken`/`predTarget` (+ the BTB-update fields
  `isBranch`/`btbTarget`/`btbTaken`/`brType` on the ROB entry).
- `execute/BranchEuPlugin.scala` — `mispredict` redefinition (predicted-vs-actual) + surface
  the actualTarget/brType for the BTB update.
- `rob/RobPlugin.scala` — per-entry BTB-update store written from branch completion, read at
  retire to drive the BTB write port; the `mispredictStore` now holds "mispredicted".
- `top/FullCoreSynth.scala` + `bench/IpcBenchSpec.scala` + `lockstep/ExecuteLockStepSpec.scala`
  DUT wiring — instantiate + wire the BtbPlugin (read←fetch, write←retire, invalidate←icache).
- `core/M68kParams.scala` (or wherever params live) — `btbEntries` param.

Identify the exact param/wiring locations during planning; the above is the design contract.

---

## 8. Testing

**Correctness (the invariant): prediction never changes architectural results.**
- ALL existing branch lock-step tests (BranchEuSpec, JmpSpec, the lock-step Bcc/BSR/JSR/RTS/
  DBcc/loop coverage) stay green vs Musashi — byte-identical commits, with the predictor on.
- A directed BTB unit test: cold miss → predict-not-taken; learn → warm hit predicts taken→
  target; counter saturation (2 mispredicts to flip); tag mismatch → no false hit; indirect
  `JMP (An)` learns then mispredicts on a changed target then re-learns.
- A staleness stress test: a predicted-taken redirect coincident with a commit redirect (the
  `recStale`/`recDrop` interaction) — assert no dropped/duplicated/mis-attributed fetch
  (lock-step a loop containing a mispredicting inner branch).

**Performance (the point):**
- IPC bench: `kBranchy` (from 0.23) must improve; add a **hot-loop kernel** (a tight
  backward `dbra`/`bne` loop) that should approach the backend ceiling once the back-edge is
  predicted (near-zero squash). Report before/after IPC for branchy + the new loop + mixed.

**FMax gate (hard):** regenerate + post-route `synth/impl_FullCore.tcl`, **≥ 200 MHz**, run
**≥ 2 fresh regens both ≥ 200** (placement is ±~10 sensitive at this congestion — the
single-sample lesson from the bit-field slices). Confirm the BTB lookup is NOT the new
critical path (check the route timing report); if it is, register the BTB read deeper /
narrow the tag compare.

---

## 9. Risks

- **FMax** — the BTB read + tag-compare + target-mux feeds a new fetch-PC redirect path on
  the already-tight front-end. Mitigation: synchronous SRAM read registered like the I-cache
  rsp; keep the predictTaken/target mux shallow; gate the slice on ≥2 regens ≥200.
- **Staleness/recovery correctness** — overlapping predict+commit redirects stress
  `recStale`/`recDrop` (prior bug class). Mitigation: the staleness stress test + lock-step a
  mispredicting loop; architectural results must match Musashi exactly.
- **Indirect `JMP (An)`** — a learned target is often wrong (computed-goto / vtable). It is
  still correct (mispredict recovers) but may not help; acceptable for slice 1 (RAS + indirect
  predictor are later). Measure the mispredict rate.
- **Cold-start** — first taken execution of every branch is a mispredict (BTB miss → not-
  taken). Expected; loops amortize after 1–2 iterations.
