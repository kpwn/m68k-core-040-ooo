# IPC push, task #126: fetch-directed BTB — window-indexed Fetch Target Buffer + Fetch Target Queue + confirm-or-flush (design)

Status: **DESIGN ONLY.** No RTL written, no RTL modified, no Vivado run by this pass.
Baseline HEAD: `bb4d40e` (= merge `f6265f7`, the landed I-side MSHR chain, + its docs commit).
Initiative: IPC-push (`.superpowers/sdd/progress-ipc-push-2026-08-09.md`), under the binding
user goal *"push ipc as high as reasonably possible without blowing up lut count or crashing
fmax."*

Grounding this spec is built on: the ledger entries **"Post-I-side-chain bottleneck
re-characterization"** and **"Fetch-directed BTB grounding (task #126)"** in that same file.
Every netlist number quoted below is traceable to
`…/scratchpad/gate/final2_timing.rpt` (the 221.828 MHz / `8267ff1` arm) unless stated
otherwise. Every RTL claim below was re-derived from live source during this pass and is
line-cited.

---

## Contents

0. [Context and the measured problem](#0-context-and-the-measured-problem)
1. [Goals / Non-goals](#1-goals--non-goals)
2. [Architecture](#2-architecture)
3. [**The load-bearing correctness argument — confirm-or-flush**](#3-the-load-bearing-correctness-argument--confirm-or-flush)
4. [Flush / redirect / exception interaction (F1–F12)](#4-flush--redirect--exception-interaction-f1f12)
5. [FMax treatment and the mandatory census gate](#5-fmax-treatment-and-the-mandatory-census-gate)
6. [Cost, blast radius, slicing](#6-cost-blast-radius-slicing)
7. [Explicit fallback: Approach C](#7-explicit-fallback-approach-c)
8. [Alternatives considered and rejected](#8-alternatives-considered-and-rejected)
9. [Verification plan (binding)](#9-verification-plan-binding)
10. [Open questions for the plan-writing pass](#10-open-questions-for-the-plan-writing-pass)

---

## 0. Context and the measured problem

The just-landed I-side MSHR chain retired I-fetch as a bottleneck outright: under ideal
memory `independent-ALU` now runs at **2.000 IPC / 100 % dual / 100 % active** and
`dependent-ALU` at **1.002 IPC / 100 % active** — both at their theoretical floors. The
5-seed re-characterization then put the new #1 loss squarely on control flow:

| bucket | cycles | share of the realistic-memory aggregate |
|---|---|---|
| irreducible floor (2-wide retire / dep chain) | 1642 | 17.7 % |
| **core-side stall** (ideal-memory cycles above floor) | **4361** | **47.1 %** |
| memory-side stall | 3259 | 35.2 % |

Of the core-side stall, `call-return` (1941 cyc) + `branchy` (612) + `hot-loop` (506) =
**3059 cyc = 70.1 % of all core stall = 33.0 % of the entire realistic-memory aggregate**.
`hot-loop` and `branchy` are ~95 % memory-INSENSITIVE (39–40 cyc of memory stall each), so no
memory-side lever can ever touch them.

**Cost per CORRECTLY-predicted taken transfer, measured:** 5.08 cyc (`hot-loop`), 6.5 cyc
(`call-return`). Decomposed exactly against today's RTL:

```
N    feed.fire on the branch; predictFire; ibuf.flush; ringStale all; fetchPc := target
N+1  ic.cmd.fire with the target window          (a cmd issued at N is born stale)
N+2  I-cache T-stage
N+3  I-cache S1
N+4  ic.rsp.valid -> ibuf.push
N+5  aligner sees the target words -> feed.fire
```
**4 dead cycles (N+1..N+4)**, plus ~1 cycle of `slot1WouldPred` single-issue defer.
`hot-loop`'s body is 4 one-word macros = exactly one 8-byte window, giving 3 emit cycles +
4 restart cycles = 7 cyc/iter against a measured 7.08 and a floor of 2.0.

**Why the flush is CORRECT today, not a missed check** (`FetchAlignPlugin.scala:661-677`):
there is no fetch-time predictor at all. The BTB/RAS/gshare are queried with
`res.slot0.pc` / `res.slot1.pc` (`FetchAlignPlugin.scala:428-431`) — the aligner's
per-instruction PCs, derived from the `decodePc` register — while fetch is a pure sequential
walker (`ic.cmd.payload.pc := fetchPc` at `:246`, `fetchPc := fetchPc + 8` at `:262`) with
**zero predictor input**. At the instant a prediction is made, every word in the IBuf beyond
the branch and every one of the up-to-3 outstanding fetches is genuinely fall-through, i.e.
wrong-path. Nothing along the target path has ever been fetched, so there is nothing to keep.

> **Stale-comment verification (asked for explicitly).** `Btb.scala:44` documents `queryPc`
> as *"the fetch PC to look up (the issued fetch window base)"*. Re-checked against live
> source this pass: `FullCoreSynth.scala:94` wires `btb.logic.queryPc := fa.logic.btbQueryPc0`
> and `FetchAlignPlugin.scala:428` drives `btbQueryPc0 := res.slot0.pc`, which
> `Aligner.align` sets to `headPc` == `decodePc`. **The comment is confirmed STALE/wrong** —
> nothing has ever queried the BTB with a fetch window base. Correcting it is part of this
> lever's diff.

Closing this gap therefore requires a genuinely new fetch-side prediction mechanism. That is
what this spec designs.

---

## 1. Goals / Non-goals

### 1.1 Goals

* **G1.** Eliminate the 4-cycle fetch restart on a *correctly predicted* taken branch, for
  the branches a window-indexed fetch-time predictor can cover. Target: realistic-memory
  aggregate IPC **≥ +10 %** (projection is ~+20 %; ideal-memory projection ~+29 %).
* **G2.** Do it **without** regressing post-route FMax below the current 221.828 MHz, and
  ideally improving it by deleting Lever D's `spec2*` cone — which the netlist says is the
  design's current #1 failing path.
* **G3.** Do it **without** growing LUT count. Lever D's own measured cost (+1302 LUTs /
  +512 LUTRAM) is deleted by this lever; the new FTB/FTQ must fit inside that budget.
* **G4.** **Never regress prediction coverage.** Any branch the fetch-time mechanism does not
  cover must fall back to *exactly today's* decode-time BTB/RAS/gshare path, flush and all.
  This lever must be IPC-monotone by construction, not by measurement.
* **G5.** Close the new mis-framing hazard (§3) **by construction**, with a differential test
  strong enough to state "fetch-direction is architecturally invisible", not by inspection.

### 1.2 Non-goals

* **N1. No change to branch-prediction ALGORITHMS or their training.** Same BTB bimodal
  update rule, same gshare fold/PHT/GHR, same RAS, same retire-time training sources. The one
  *accuracy-affecting* consequence is stated honestly and measured, not hidden: see
  §2.6 (fetch-time direction for conditionals) — it is not a claim of "zero accuracy change".
* **N2. No change to MISPREDICTION recovery cost.** Task #116 (resolve-time selective squash)
  stays rejected on its existing architectural grounds (needs per-branch RAT/freelist
  checkpoints × 3 RATs + partial IQ/scoreboard/SQ squash; flush is only correct at a drained
  ROB — `RenameStage.scala:81-87`, `Freelist.scala:122-124`, `RobPlugin.scala:905/1282`; it
  multiplies the known wrong-path-resource-leak bug class #176/#194/#200; it conflicts with
  the ratified MSHR §4.3 retire-time-only-flush simplification). The fresh 5-seed data
  *weakened* it further — true mispredicts are ~3 % of the aggregate, an order of magnitude
  below this lever's 33 %.
* **N3. No I-cache / MSHR / crossbar work.** The residual I-fetch loss is crossbar-bound
  (one outstanding read per master port), not core-bound. Out of scope.
* **N4. No demand hit-under-miss on the I side.** Still needs `FetchRsp` tagging + a
  FetchAlign ring rework, which MSHR design §6.3 explicitly declines. Untouched.
* **N5. No LSU store→load throughput work** (the *other* new finding: `load/store` is 907 cyc
  of pure core-side stall, 20.8 % of core stall). Separate lever, separate design pass.
* **N6. Phase boundaries are non-goals for the first slice** — see §6.3: cross-window
  branches (Phase 2), fetch-time RAS for `rts` (Phase 3), and removal of the slot-1 defer
  (Phase 4) are each *out of scope for Phase 1* and each need their own gate.
* **N7. No `set_false_path` / XDC / floorplan work.** Lever E's measured result stands
  (`pb_decode` widening REGRESSES: -6.84 MHz at the proposed 52 columns).

---

## 2. Architecture

### 2.1 One-paragraph summary

Add a **window-indexed Fetch Target Buffer (FTB)**, read at fetch time off the `fetchPc`
register with a **registered** result, that answers "does the 8-byte window I am about to
fetch contain a taken control transfer, and if so at which word offset, with what length, to
what target?". On a hit, fetch (a) records a **trailing-word truncation** on that window's
outstanding-ring entry — the exact mirror of the existing leading-word `ringDrop` — and
(b) redirects `fetchPc` to the target, and (c) pushes a record onto a small **Fetch Target
Queue (FTQ)**. The IBuf therefore comes to hold *the predicted dynamic instruction byte
stream*, contiguous from `decodePc`, rather than the sequential one. At decode, the FTQ head
is **confirmed** against the aligner's own framing; on confirm the branch is emitted with
`predTaken`/`predTarget` stamped and `decodePc` jumps straight to the target with **no flush,
no ring restale, no refetch**; on any disagreement the *existing* redirect mechanism is
reused verbatim to flush and refetch sequentially.

### 2.2 Is the FTB a new structure, or a re-indexing of `Btb.scala`? — NEW, and here is why

Grounded in the live `Btb.scala`, not assumed:

| | existing `BtbPlugin` | required FTB |
|---|---|---|
| index granularity | **word**: `idxLo = 1`, `idx = pc[7:1]`, 128 entries (`Btb.scala:52-58`) | **window**: `pc[9:3]` |
| tag | `pc[31:8]`, 24 b | `pc[31:10]`, 22 b |
| entry payload | `{tag, target(32), brType(2), counter(2)}` (`BtbEntry`, `Btb.scala:17-22`) | **additionally** `brWordOff(2)`, `brLen(3)` |
| allocation policy | one entry per branch PC | one entry per *window* (a window can hold up to 4 branches; the table holds one) |
| valid bits | `Vec.fill(entries)(RegInit(False))` (`:63`) — 1-cycle `invalidateAll` | same discipline, plus a **single-entry clear** on a confirm mismatch (§3.6) |
| consumer | decode-time, combinational, same cycle | fetch-time, **registered** result |

The index granularity and the entry payload both differ, and the allocation policies are not
reconcilable in one direct-mapped array. Re-indexing `Btb.scala` in place would destroy the
word-granular decode-time fallback that **G4** requires. So:

* **NEW file** `src/main/scala/m68k040/frontend/Ftb.scala` — `FtbPlugin`, `FtbEntry`.
* `Btb.scala`'s **storage, training, counter semantics and slot-0 port are retained
  unchanged**, and keep serving the decode-time fallback path.
* `Btb.scala`'s **slot-1 port is DELETED**: `query2BasePc` / `query2Sel` / `query2Valid` and
  the whole `spec2Taken/spec2Target/spec2Hit/spec2Type` 9-way speculative block
  (`Btb.scala:75-201`). That block is Lever D, and per §5 it is the design's **current #1
  failing path**. Its architectural consumer (`slot1WouldPred`) is re-sourced from the FTQ at
  ~1 LUT level (§2.7).
* Both tables are trained from the **same** `BtbUpdate` Flow (`Services.scala:51-61`), which
  gains one field (`len`).

### 2.3 The FTB table

```scala
case class FtbEntry(tagBits: Int) extends Bundle {
  val tag       = UInt(tagBits bits)   // pc[31:10] for 128 window entries
  val brWordOff = UInt(2 bits)         // branch's FIRST word within the window == B[2:1]
  val brLen     = UInt(3 bits)         // branch length in words, 1..5
  val target    = UInt(32 bits)        // learned taken-target
  val brType    = UInt(2 bits)         // 0 = cond (Bcc/DBcc), 1 = uncond (BRA/BSR/JMP/JSR)
  val counter   = UInt(2 bits)         // 2-bit saturating bimodal, identical rule to BtbEntry
}
```
* Depth **128** (parameterised via a new `Config.scala` `ftbEntries`, default 128), giving
  1 KiB of code coverage — 4× the existing BTB's 256 B, deliberately, because the FTB indexes
  windows not words.
* `valids` in a `Reg` Vec (same as `Btb.scala:63`) so `invalidateAll` is one cycle and a
  single-entry clear is free.
* Derived: `brEndOff = brWordOff +^ brLen - 1`, range 0..6.
* **Phase-1 install filter:** an entry is installed only when `brEndOff <= 3`, i.e. the whole
  branch instruction lies inside the indexed window. Cross-window branches degrade to
  "no FTB entry" → the decode-time BTB fallback handles them exactly as today (**G4**).
  The `brLen` field is already sized (3 b) for the Phase-2 cross-window case so no retraining
  or format change is needed later.

**Training.** `BtbUpdate` (`Services.scala:51-56`) gains `len : UInt(4 bits)`.
`BranchEuPlugin` already computes both endpoints: `u1.pc` (the payload's `btbPc`) and
`u1.nextPc` (the assembler-computed fall-through, used at `BranchEuPlugin.scala:231`), so
`len = (u1.nextPc - u1.pc)(4 downto 1)`. Install gate: `isBtbBranch` (unchanged — returns,
`isScc`, `isCondTrap` and `addrErr` already excluded, `BranchEuPlugin.scala:251-262`) **AND**
`1 <= len <= 5` **AND** `brEndOff <= 3`. Counter/allocation semantics are copied verbatim
from `Btb.scala:216-228` — same `cInc`/`cDec`/`ctrAlloc`, so the two tables agree on
direction by construction.

**Invalidation.** The FTB is wired to **both** `IcachePlugin.logic.invalidateAll` **and**
`IcachePlugin.logic.maintInvalidateAll`, exactly as the BTB is
(`FullCoreSynth.scala:82-83`). This is mandatory, not optional — see §3.7 (hazard H7).

### 2.4 The fetch-side lookup — **registered**, off `fetchPc`

```
    ftbRes      = Reg{ hit, brWordOff, brLen, target, brType, takenDir, phtIdx }
                                                 // == lookup(fetchPc) as of the PREVIOUS cycle
    ftbResFresh = (RegNext(fetchPc) === fetchPc)  // 32-bit register-to-register compare
```
* Combinational async LUTRAM read at `idxOf(fetchPc)` + tag compare, **result registered**.
  `fetchPc` is a plain register whose only other input is `+8`, and fetch runs up to
  `RING`=3 windows plus ~5 IBuf windows ahead of decode, so a one-cycle-late result costs
  nothing architecturally.
* Freshness is defined **by value**, not by event: `ftbRes` is the lookup of `fetchPc` as it
  stood one cycle ago, so it is usable exactly when `fetchPc` still holds that same value.
  `RegNext(fetchPc) === fetchPc` is a register-to-register 32-bit compare (~2 LUT levels)
  feeding the issue decision — deliberately not a list of "did any of these events fire",
  which would be both longer and wrong for the case below. A prediction is applied only when
  `ftbRes.hit && ftbResFresh`.
* **Consequence, stated honestly:** the first window fetched after any *change in the value
  of* `fetchPc` cannot be fetch-directed — one *fetch-side* bubble per redirect. In a tight
  loop whose target lands in the **same** window (the `hot-loop` case — its whole body is one
  8-byte window) `fetchPc` is re-assigned to the value it already held, so `ftbResFresh`
  stays true and the loop predicts every iteration. This is precisely why freshness is
  defined by value rather than by "a redirect fired". Where it does cost a
  window, the RING=3 + 20-word IBuf run-ahead absorbs it: it is a fetch-throughput effect,
  not a decode-side bubble, and it is bounded at 1 cycle.
* **A combinational (unregistered) FTB read feeding `fetchPc` directly is REJECTED** — see
  §8.1; the evidence is this project's own measured -36.29 MHz I-cache prefetch-cone
  regression (`349a585`).

### 2.5 The fetch-side action, on `ic.cmd.fire` with an applied prediction

All four of these happen in the **same** cycle, in the existing `when(ic.cmd.fire)` block
(`FetchAlignPlugin.scala:248-264`), as one atomic action:

1. `ringKeep(ringTail) := ftbRes.brEndOff + 1` — **NEW** per-ring-entry field, 3 bits × 3
   entries. The exact mirror of the existing `ringDrop(ringTail) := pendingDrop`.
   Non-predicted issues write `ringKeep := 4` (keep the whole window).
2. `fetchPc := ftbRes.target` (instead of `fetchPc + 8`), and
   `pendingDrop := ftbRes.target(2 downto 1)` — reusing the existing leading-drop mechanism
   verbatim.
3. `ftqPush` — enqueue `{brPc, brLen, target, phtIdx, isCond}` (§2.7).
4. GHR shift, if the prediction was a conditional (§2.6).

The response path (`FetchAlignPlugin.scala:296-314`) changes by exactly one expression:
```
    val startWord = rspDropHead
-   val nWords    = U(4, 3 bits) - startWord.resize(3)
+   val nWords    = ringKeep(ringHead) - startWord.resize(3)     // keep - drop
```
with the issue-time invariant `ringKeep > ringDrop` guaranteed by a stricter issue-side
condition: **a prediction is declined unless `brWordOff >= pendingDrop`**, i.e. the branch's
*first* word must be at or after the window's entry point. (A `brWordOff < pendingDrop` hit
means the window is being entered *inside* the claimed branch — an aliased entry or a
mid-branch redirect target; see A6 in §3.5.) Since `brEndOff >= brWordOff >= pendingDrop`,
`ringKeep = brEndOff + 1 > ringDrop` follows. This edit lives in the `rsp -> ibuf.push` cone,
which is **not** in any feedback loop.

`ibufRoomForIssue` (`:243-244`) is unchanged: it reserves a full 4 words per outstanding
window, and truncation only *shrinks* a window, so the no-overflow invariant is preserved as
a strict upper bound (its own comment already says so).

### 2.6 Direction source for conditionals — a real, measured trade

`Btb.scala`'s bimodal counter and the gshare PHT can disagree for a conditional. Today the
decode-time composition (`FetchAlignPlugin.scala:446-451`) resolves it as
`Mux(condBtbHit0, gsPhtTaken0, btbPredTaken0)` — gshare wins for a conditional BTB hit.

To keep that at fetch time without a serial `FTB RAM -> fold/XOR -> PHT RAM` chain (which is
exactly the 2-RAM-in-series shape that cost -36.29 MHz in the I-cache prefetch first cut),
use the **linearity of the gshare fold**:

`indexOf(pc) = fold(pc[31:1]) ^ fold(ghr)` (`Gshare.scala:81`), and `fold` is an XOR
reduction, hence linear over XOR. With `B = {fetchPc[31:3], brWordOff, 1'b0}`, the low chunk
of `fold(B[31:1])` is `{fetchPc[11:3], brWordOff}`, so

```
    indexOf(B) == idxBase ^ brWordOff        where  idxBase = indexOf({fetchPc[31:3], 3'b0})
```
`idxBase` is computable from **registers only** (`fetchPc`, `ghr`). Therefore: issue **4
speculative PHT reads** at `idxBase ^ k`, k ∈ 0..3, all launched from registers in parallel
with the FTB RAM read, and late-select by `brWordOff` at the end. This is structurally the
same late-select pattern Lever D proved (`Btb.scala:178-201`) — at 4 ways instead of 9, on a
2-bit-wide RAM instead of a 60-bit one, and **replacing** Lever D's 9 ways, so it is
LUTRAM-negative.

Composition, at fetch: `ftbTaken = ftbRes.hit && ((brType === uncond) || phtTakenSpec(brWordOff))`.
The GHR shifts at fetch on an applied conditional prediction, with the predicted bit.
`phtIdx = idxBase ^ brWordOff` is carried in the FTQ so retire trains the **exact** entry the
lookup read (the same discipline `Services.scala:64-70` already mandates).

**The honest part.** A *not-taken* conditional FTB hit produces no redirect and no FTQ entry;
it is handled entirely by the unchanged decode-time path, which reads the PHT with the
GHR-as-of-decode and shifts the GHR there. So the GHR is shifted at fetch for applied
predictions and at decode for everything else, and the two can interleave out of program
order. gshare is already explicitly **accept-corruption** (`Gshare.scala:39-42`: no
checkpoint/restore, wrong-path bits shift out) and correctness never depends on the GHR — the
branch EU cross-checks direction AND target (`BranchEuPlugin.scala:247`). So this is an
**accuracy nuance, never a correctness issue**, and it is exactly what the `branchy` kernel
in the IPC gate (§9.4) is there to adjudicate. It is *not* claimed to be neutral.

Double-shift avoidance is mandatory: `gsShiftValid` (`FetchAlignPlugin.scala:637`) gains
`&& !ftqConfirm`.

### 2.7 The Fetch Target Queue

```scala
case class FtqEntry() extends Bundle {
  val brPc    = UInt(32 bits)   // B — the predicted branch's own PC
  val brLen   = UInt(3 bits)    // Lb — words, 1..5
  val target  = UInt(32 bits)   // T
  val phtIdx  = UInt(11 bits)   // the fetch-time gshare index (carry-down for training)
  val isCond  = Bool()          // phtIdx is meaningful
}
```
* Depth **4**, parameterised (`ftqDepth`), implemented as a small FIFO of registers inside
  `FetchAlignPlugin.logic` (`ftq`, `ftqHead`, `ftqTail`, `ftqCount`) — the same shape as the
  existing outstanding ring (`FetchAlignPlugin.scala:180-195`), including `ringInc`'s
  explicit non-power-of-two wrap helper if a non-power-of-two depth is ever chosen.
* Cost: 4 × (32 + 3 + 32 + 11 + 1) = **316 FF** plus ~8 FF of pointers.
* **Depth rationale:** fetch can be ~8 windows ahead of decode (RING=3 in flight + up to 5
  windows resident in the 20-word IBuf), and in a 1-window loop every window is predicted.
  A `hot-loop` iteration is 3 emit cycles at decode while fetch issues 1 window/cycle ⇒ ~3
  predictions in flight. Depth 4 covers that with margin. **Depth is a swept parameter in the
  IPC gate ({2, 4, 6}) — not asserted.**
* **FTQ full ⇒ do not apply the prediction.** Fetch simply issues sequentially for that
  window. This is self-consistent, not a hazard: the un-predicted branch is later caught by
  the decode-time BTB fallback (**G4**), which flushes exactly as today. Fetch must **never**
  truncate a window without a matching FTQ entry, and must **never** push an FTQ entry
  without truncating — the two are written in the same `when(ic.cmd.fire)` arm, under one
  common `applyPrediction` term, so this holds by construction.

**Relationship to the outstanding ring — no duplicated state.** They track different things
with different lifetimes and never overlap:

| | outstanding ring (`ringStale`/`ringDrop`/`ringKeep`) | FTQ |
|---|---|---|
| tracks | in-flight **I-cache fetches** | in-flight **predictions** |
| lifetime | `ic.cmd.fire` → `ic.rsp.valid` (≤ 3 entries) | `ic.cmd.fire` → decode confirm (spans the IBuf too) |
| what it stores | the *physical* shape of one window's push (leading drop, trailing keep, discard bit) | the *architectural claim* about one branch |
| flushed by | `ringStale.foreach(_ := True)` | `ftqFlush` |

The only coupling is at issue (one atomic write to both) and at flush (§4: **every**
`when` block that sets `ringStale.foreach(_ := True)` also sets `ftqFlush := True`, with no
exceptions — that co-location is the incoherence guard).

### 2.8 The decode side

Delete: the BTB slot-1 read (`btbQueryBasePc1`/`btbQuerySel1`, `FetchAlignPlugin.scala:439-440`)
and `Aligner.Result.slot1Sel` (`Aligner.scala:27, 102`). Keep: the slot-0 BTB/gshare/RAS reads
(the **G4** fallback).

Add, all launched from registers:

```
    ftqV        = ftqCount =/= 0
    ftqDelta    = (ftq(ftqHead).brPc - decodePc) >> 1            // 32-bit sub, reg-to-reg
    ftqNear     = ftqV && ftqDelta(31 downto 4) === 0            // branch inside the 10-word window
    ftqAt0      = ftqNear && ftqDelta(3 downto 0) === 0          // decodePc IS the branch
    spliceWords = ftqDelta(3 downto 0) +^ ftq(ftqHead).brLen     // genuine words remaining
    availEff    = ftqNear ? min(ibuf.io.avail, spliceWords) : ibuf.io.avail
```
`availEff` replaces `ibuf.io.avail` at **both** consumers: `Aligner.align(..., availEff, ...)`
(`FetchAlignPlugin.scala:412`) and the `p0LiveReg` validity flags (`:401-404`). This is the
**genuine-word clamp** — §3 is entirely about why it is the correctness mechanism.

`p0LiveInvalidate` (`:405-406`) gains one term: `|| (RegNext(availEff) =/= availEff)`. This is
required because the FTQ head can change (a push into an empty FTQ, a pop, a flush) without a
`push.fire`/`shift`/`flush`, and `p0LiveReg`'s existing invalidation set does not cover that.

Confirm / mismatch / slot-1 defer:

```
    ftqConfirm  = ftqAt0 && res.slot0Valid && res.slot0.simple &&
                  (res.slot0.lenWords === ftq(ftqHead).brLen)
    ftqLenBad   = ftqAt0 && res.slot0Valid && !ftqConfirm
    ftqOvershoot= ftqNear && !ftqAt0 && feed.fire && (effShift > ftqDelta(3 downto 0))
    ftqStarved  = ftqNear && !res.slot0Valid && (availEff < ibuf.io.avail)
    ftqPast     = ftqNear && ftqDelta(31)                      // decodePc > brPc; defensive
    ftqMismatch = (ftqLenBad || ftqOvershoot || ftqStarved || ftqPast) && !quiesce && !stalled
    slot1WouldPred_ftq = ftqNear && !ftqAt0 &&
                         (ftqDelta(3 downto 0) === res.slot0.lenWords) && res.slot1Valid &&
                         res.slot0Valid && !ftqConfirm
```
`slot1WouldPred_ftq` **replaces** today's `slot1WouldPred` (`FetchAlignPlugin.scala:467`),
which is the only architectural consumer of the deleted `spec2` block. It compares
`res.slot0.lenWords` (== `L0`) rather than the deleted `Aligner.Result.slot1Sel`: `slot1Sel`
existed solely to reach the BTB one arm-mux earlier than `slot0.lenWords`, and with the RAM
read gone that one mux level no longer buys anything. **`slot1Sel` is therefore deleted
outright** (consistent with §6.2), and this compare is 4 bits wide against a register-derived
value — ~1 LUT level in place of an 11-level RAM + tag-compare + 16:1-mux cone.

**Mismatch redirect target**, differing by class (see §3.5 for why each is safe):

| class | did a packet emit this cycle? | `newPc` |
|---|---|---|
| `ftqStarved`, `ftqPast` | no (`!res.slot0Valid`, or defensive) | `decodePc` |
| `ftqLenBad` (only reachable with `L0 < Lb`, see §3.5-A2) | yes, un-predicted, all its words genuine | `decodePcNext` |
| `ftqOvershoot` | yes, un-predicted, all its words genuine | `decodePcNext` |

On **`ftqConfirm`** (this is the entire IPC win):
```
    feed.payload(0).predTaken  := True
    feed.payload(0).predTarget := ftq(ftqHead).target
    feed.payload(0).phtValid   := ftq(ftqHead).isCond
    feed.payload(0).phtIndex   := ftq(ftqHead).phtIdx
    slot1ValidOut              := False                 // Phase 1 keeps the defer
    effShift                   := res.slot0.lenWords
    decodePc                   := ftq(ftqHead).target   // register-to-register mux
    ftqPop                     := feed.fire
    // NOT set: ibuf.io.flush, ringStale, fetchPc, pendingDrop, started
```
and the decode-time prediction is suppressed for that slot: `predictedThisEmit` becomes
`slot0Predicted && !ftqConfirm`, so `predictFire` (and its flush) cannot fire on a confirmed
branch.

On **`ftqMismatch`**: reuse the *existing* redirect mechanism verbatim — a new `when` block
placed at the same priority as `predictFire` (below the architectural redirects) that does
exactly what `FetchAlignPlugin.scala:680-703` does, with `newPc` per the table above:
`decodePc := newPc; fetchPc := newPc(31 downto 3) @@ 0; ibuf.io.flush := True;
stalled := False; started := True; pendingDrop := newPc(2 downto 1);
ringStale.foreach(_ := True); faultHold := False; faultEmitted := False;
ftqFlush := True; ftbSuppress := True;` plus a single-entry FTB clear (§3.6).

`slot1WouldPred` is re-sourced from the FTQ: today it needs the 9-way `spec2` read; now it is
a 4-bit compare of `ftqDelta(3 downto 0)` (register-derived) against `L0`. **That
substitution is the FMax win** (§5).

---

## 3. The load-bearing correctness argument — confirm-or-flush

This section is the reason this lever gets a full design→review→plan→implement cycle rather
than a direct dispatch.

### 3.1 The hazard, stated precisely

Today the BTB is only ever consulted at **already-predecoded instruction boundaries**
(`res.slot0.pc` / `res.slot1.pc`), so a wrong prediction can only mis-*target* — never
mis-*frame*. A window-indexed FTB **guesses the branch's word offset and length before decode
confirms either**. If that guess is wrong, the truncation splices the target's words into the
middle of what should have been a real instruction's remaining bytes. That is **silent
corruption** (a wrong displacement, a wrong EA, a wrong PC on an otherwise-valid
instruction), not a crash and not a perf loss.

### 3.2 The reframing that makes the argument tractable

The IBuf content under fetch-direction is **not** "correct words with wrong words spliced
in". It is exactly the correct architectural instruction byte stream **conditional on the
FTQ head's claim being true** — i.e. conditional on:

> **CLAIM(B, Lb):** there is an instruction boundary at address `B`, and the instruction
> there is exactly `Lb` words long.

(The *taken-ness* of that instruction is a separate, pre-existing question — §3.7 H7.)

So the whole correctness obligation reduces to: **no architectural state may be affected by
any framing decision made while CLAIM is unverified, unless that decision provably depends
only on bytes that are correct whether or not CLAIM holds.**

### 3.3 The invariant the clamp buys — INV-P

The aligner's instruction lengths come from exactly two sources, and **both** already satisfy
a truth-or-stall property:

**INV-P1 (baked predecode).** `preds(i)` is produced by `PredecodeWord.classify` on the
**real 64-byte cache line** at I-cache refill time, with `extWValid`/`extW2Valid`/
`extW3Valid` reflecting the line boundary. `classify`'s documented contract
(`PredecodeWord.scala:87-97`, and the `memDestExt`/`eaExt` helpers) is: when a word it would
need to distinguish brief- from full-format is *not known*, it either rejects to COMPLEX
(`ok := False`) or assumes-brief **and sets `ambiguousLine := True`**. It never returns a
silently-wrong length. Hence `preds(i).simple && !preds(i).ambiguousLine ⇒ preds(i).lenWords
is the TRUE length at that address`, and truncation cannot change this — truncation drops
words from the *push*, not from the bake.

**INV-P2 (live reclassify).** `p0LiveReg` is `classify(head(0..3), flags derived from
availEff)`. With the clamp, words `0 .. availEff-1` are *genuine bytes at `decodePc`
regardless of CLAIM* (they lie strictly before the splice). By the same contract, the result
is either the true length or `ambiguousLine`.

Therefore:

> **INV-P.** Every length the aligner ever consumes (`L0` via
> `p0 = Mux(preds(0).ambiguousLine, p0LiveReg, preds(0))`, `Aligner.scala:95`; `L1` via
> `p1 = preds(L0)` gated on `!p1.ambiguousLine`, `:267`) is either the TRUE architectural
> length at that address, or the aligner STALLS.

This is not a new property. It is the property tasks #202 and #209 already installed and that
`PredecodeSimpleLenSpec` already covers exhaustively (65 536 opwords × extension content ×
validity combinations, run to completion). This lever's job is only to keep it true, which is
exactly what the clamp does.

### 3.4 The three existing gates that the clamp turns into the framing guard

With `availEff` substituted, three **already-load-bearing** aligner checks do all the work:

| gate | source | what it now guarantees |
|---|---|---|
| `when(avail < L0)` stall | `Aligner.scala:167` | slot0's own words `0..L0-1` are all genuine (all < splice) |
| `.elsewhen(p0.ambiguousLine)` stall | `Aligner.scala:106` | slot0's *framing lookahead* never used a post-splice word |
| `slot1Ok`'s `avail >= L0L1` + `!p1.ambiguousLine` | `Aligner.scala:267` | slot1's words `L0..L0+L1-1` are all genuine, and its length came from a non-ambiguous bake |

and one more, for free:

| `!p0.simple && (avail < WINDOW)` stall | `Aligner.scala:110` | a COMPLEX packet can never be emitted under a binding clamp (`spliceWords <= 4 + 5 = 9 < 10`), so the 10-word complex window is never assembled from post-splice bytes |

**Consequence:** *no instruction is ever framed, emitted, or shifted using a single byte at or
beyond the splice.* Every emitted packet is correct whether or not CLAIM holds. That is the
"decision depends only on bytes correct either way" obligation from §3.2, discharged by
construction.

### 3.5 Exhaustive enumeration of the ways a stale/wrong FTB entry reaches the guard

Let the FTQ head claim `(B, Lb, T)` and let decode walk from `decodePc = D ≤ B`. Let
`splice = B + 2·Lb`. Every possible disagreement falls in exactly one of these classes.
For each: what happens, which guard catches it, and **before what**.

| # | disagreement | what physically happens | guard that fires | fires BEFORE |
|---|---|---|---|---|
| **A1** | The real instruction at `B` is **longer** than `Lb` (`L0 > Lb`) | IBuf words `Lb..` at `B` are target bytes | at `D = B`, `availEff == Lb < L0`, so the `when(avail < L0)` stall (`Aligner.scala:167`) gives `!slot0Valid` ⇒ **`ftqStarved`**. (If `preds(0)` was ambiguous, `p0LiveReg` under the clamp reports ambiguous ⇒ the `.elsewhen(p0.ambiguousLine)` stall ⇒ same fire.) | **any packet is emitted at `B` at all** — nothing is framed, nothing shifts |
| **A2** | The real instruction at `B` is **shorter** than `Lb` (`L0 < Lb`) | extra fall-through words survive before the target's | `ftqAt0 && slot0Valid && lenWords =/= brLen` ⇒ **`ftqLenBad`** | the *next* packet. The packet at `B` itself **does** fire, un-predicted (no `predTaken` stamp) — and that is correct: `L0 < Lb <= availEff`, so **all of its words are genuine**. The mismatch `when` then redirects to `decodePcNext` and flushes the IBuf in the same cycle, so the leftover words never reach decode. |
| **A3** | The real instruction at `B` is **COMPLEX** (e.g. a JMP with a memory-indirect EA that predecode classifies non-`simple`) | the complex arm would need `avail >= WINDOW`(10) (`Aligner.scala:110`) | at `D = B` the clamp gives `availEff == brLen <= 5 < 10`, so the complex arm is **unreachable** ⇒ `!slot0Valid` ⇒ **`ftqStarved`**. (For `D < B` the complex arm stays reachable exactly when its full 10-word window is genuine, i.e. `ftqDelta + brLen >= 10` — which is the correct condition, not a hole.) (The `res.slot0.simple` term in `ftqConfirm` is belt-and-braces for the same case.) | any packet is emitted at `B` |
| **A4** | There is **no boundary at `B`**: an instruction starting at `p < B` *ends after* `B` but at or before `splice` | its own words are all genuine (< splice), so it frames correctly and emits — but `decodePcNext` skips `B` | `effShift > ftqDelta` ⇒ **`ftqOvershoot`**, evaluated in the *same* cycle as that emit | the *next* packet — the one that would be framed from post-splice bytes at a fall-through PC, i.e. the corrupting one |
| **A5** | No boundary at `B`, and the straddling instruction extends **past** `splice` | its framing needs post-splice words | `avail < L0` (or `ambiguousLine` from the clamped live reclassify) ⇒ `!slot0Valid` ⇒ **`ftqStarved`** | that instruction is emitted at all |
| **A6** | The window is entered at an offset **inside** the claimed branch (`pendingDrop > brWordOff`) — an aliased entry or a mid-branch redirect target | the branch's own leading word(s) would be dropped | declined at **fetch issue**: `applyPrediction` requires `brWordOff >= pendingDrop`; no truncation, no FTQ push, no `fetchPc` redirect | anything is fetched wrongly |
| **A7** | `decodePc` is already **past** `B` when the FTQ head is examined | argued unreachable: the FTQ is written in fetch order, decodePc advances monotonically between flushes, and A4 catches every skip in the cycle it happens | **`ftqPast`** (defensive) | — |
| **A8** | The FTB entry is stale w.r.t. **SMC** but `(B, Lb)` still framing-consistent (e.g. a 1-word non-branch now sits at `B`) | framing is CORRECT; the *taken-ness* claim is wrong | **not** this guard — see §3.7 H7 (pre-existing class, closed by `maintInvalidateAll`) | — |
| **A9** | The FTQ head's branch is **far** from decode (`ftqDelta ≥ 16` words) | the splice is beyond the aligner's 10-word visibility, so all visible words are genuine | no guard needed; `ftqNear` is false ⇒ no clamp, no confirm, no mismatch. Safe by the 10-word window bound. | — |
| **A10** | Two predictions in flight; entry 2's splice is nearer than entry 1's | impossible — the FTQ is in fetch order, so `splice(1) < B(2) ≤ splice(2)`; clamping to the head is the tightest constraint | — | — |

**Completeness argument.** A disagreement can only be about (i) *where* the boundary is
(A4/A5/A6/A7), (ii) *how long* the instruction there is (A1/A2/A3), (iii) *whether it is a
taken branch* (A8), or (iv) *whether the claim is even in scope* (A9/A10). (i)+(ii) are
exactly the framing hazard and are covered above. (iii) is not new (§3.7). (iv) is a
non-event. There is no fifth axis: `(B, Lb)` is the entire claim, and the IBuf's byte content
is a deterministic function of the fetch actions taken (leading drop, trailing keep, target
redirect), all of which are recorded in the ring and the FTQ.

**The load-bearing sub-claim, restated for the reviewer to tick:** for A1–A5, the guard fires
**before any architectural effect**, because (per §3.4) *no packet is ever assembled from a
post-splice byte*. The mismatch flush is therefore always a pure perf event, never a
correction of already-committed state.

### 3.6 What happens on mismatch, and why it cannot livelock

`ftqMismatch` fires the **existing** redirect action (`FetchAlignPlugin.scala:680-703`
verbatim) with `newPc = decodePc`, except for the A4 overshoot case where the emit is allowed
to fire (its bytes are genuine, §3.5) and `newPc = decodePcNext`. Recovery cost is identical
to today's `predictFire` cost — 4 cycles — and it is rare.

Two mandatory liveness closures, because a naive mismatch flush *would* livelock (refetch →
same window → same FTB hit → same splice → same mismatch, forever):

* **L1 — `ftbSuppress` (mandatory).** A sticky register set on `ftqMismatch`, cleared on the
  next `feed.fire`. While set, `applyPrediction` is forced false. The sequential refetch
  therefore delivers the window **untruncated**, decode makes forward progress, and the bit
  clears. Forward progress is guaranteed because the untruncated stream is exactly today's
  stream, whose progress is already established.
* **L2 — single-entry FTB invalidate (mandatory, not merely "recommended").** On
  `ftqMismatch`, clear `ftbValids(idxOf(brPc))`. Without this, a branch whose real decode is
  COMPLEX (A3) or which permanently straddles a boundary would mismatch on **every**
  occurrence — a persistent 4-cycle penalty on top of the un-predicted cost, i.e. strictly
  worse than today, violating **G4**. `valids` is a `Reg` Vec (per `Btb.scala:63`'s
  precedent), so a second writer is free — no second `Mem` write port. The entry re-trains at
  the next retire if it was a transient.

Over-firing of the guard is *safe by construction* (it degrades to today's behaviour);
under-firing is the only danger, and §3.5 argues it cannot happen.

### 3.7 Hazards that are NOT new, and must not be conflated with the above

* **H7 — a stale entry on a non-branch.** `FetchAlignPlugin`'s prediction is not gated on
  predecode agreeing the slot is a branch, so a stale table entry stamps `predTaken` on any
  instruction kind — and `predTaken` is read **only** by `BranchEuPlugin`, so a non-branch
  µop carrying it is verified by nothing. This is **documented today** at
  `IcachePlugin.scala:60-72` and is why the BTB (unlike the RAS and gshare) is wired to
  `maintInvalidateAll`. **The FTB inherits exactly this hazard and exactly this closure**:
  it MUST be wired to both `invalidateAll` and `maintInvalidateAll` (§2.3). Note the FTB does
  not *widen* the hazard: `predTaken` is only stamped when `decodePc === B` exactly, and `B`
  was a real retiring branch's PC, so reaching a non-branch still requires SMC — the same
  precondition as today.
  * *Considered and deferred:* additionally gating the confirm on a cheap opword-class check
    of `res.slot0.words(0)` (the pattern `s0IsBsr`/`s0IsRts` etc. already use,
    `FetchAlignPlugin.scala:486-491`). It would close H7 independently of `maintInvalidateAll`,
    but it adds a decode of the opword into the `suppressSlot1` cone — the exact corridor this
    lever exists to relieve — and a mis-classification would silently lose predictions. Listed
    as a Phase-2 option, measured before adoption, not taken in Phase 1.
* **H8 — a wrong TARGET.** Unchanged: `BranchEuPlugin.scala:247` cross-checks
  `predTaken != actualTaken || (actualTaken && actualTarget != predTarget)` and the
  commit-time redirect recovers. Fetch-direction changes nothing here.
* **H9 — GHR / RAS corruption.** Both are already accept-corruption by design
  (`Gshare.scala:39-42`, `Ras.scala:18-20`). §2.6's fetch-vs-decode shift interleaving lands
  inside that existing tolerance.

---

## 4. Flush / redirect / exception interaction (F1–F12)

Enumerated in the style of Lever F's C1–C9 binding checklist
(`2026-08-08-fmax-leverf-upstream-storequeue-design.md` §3), because this is the same class of
obligation: a small set of properties that this subsystem's own history says get broken. A
reviewer must tick each.

| # | event | required behaviour | why it is correct / where it is enforced |
|---|---|---|---|
| **F1** | external `redirect.valid` (`:680`) | `ftqFlush`, `ftbSuppress := False`, in addition to today's `ibuf.flush` + `ringStale.foreach(_ := True)` + `faultHold := False` | The IBuf is emptied, so no splice survives; the clamp is derived from the FTQ head so it releases automatically. `ftbSuppress` is cleared because a fresh path deserves a fresh prediction. |
| **F2** | `mispredictRedirect.valid` (`:730`) — commit flush **and** the µcode `ucComplexResume` path (`FullCoreSynth.scala:68-70`) | same as F1 | Same argument. Note this port carries **two** distinct events; both need the FTQ flush. |
| **F3** | `resume.valid && stalled` (`:706`) | same as F1 | A complex instruction can sit anywhere relative to a splice; flushing everything is the only coherent action. |
| **F4** | decode-time `predictFire` (`:661`) — the **fallback** BTB/RAS prediction, on a branch the FTB did not cover | `ftqFlush` added to the existing block | Reachable: an FTQ entry for a *later* branch B is invalidated when an earlier branch at `p < B` redirects. Without the flush the FTQ head would describe a stream that no longer exists. |
| **F5** | `ftqConfirm` and a same-cycle architectural redirect | the architectural redirect **wins** | SpinalHDL last-`when`-wins ordering: place the `ftqConfirm` action at the same position `predictFire` occupies today (after the normal `decodePc` advance, before `redirect`/`resume`/`mispredictRedirect`). Priority is then identical to today's proven ordering. |
| **F6** | `ftqConfirm` and `predictFire` on the same slot | confirm wins; `predictedThisEmit := slot0Predicted && !ftqConfirm` | Otherwise a confirmed branch would *also* flush — losing the entire win and double-popping nothing. |
| **F7** | I-fetch fault (`rspFault`, `:284`) with a live FTQ | `faultHold` is set as today; the FTQ is **NOT** dropped, the clamp stays live | Dropping it would leave a truncated window + target words in the IBuf with nothing describing the splice — decode would walk past `B` into target bytes believing them sequential. **Silent corruption.** Keeping the FTQ is mandatory. |
| **F8** | `emittingFaultPacket` (`:562`) racing `ftqMismatch` | mismatch has priority: `emittingFaultPacket := faultHold && !res.slot0Valid && !ftqMismatch && !stalled && !quiesce` | Both trigger on `!res.slot0Valid`. If the fault packet won, its `pc := decodePc` would be emitted while `decodePc` is mid-splice-disagreement — a fault reported at a PC decode never actually reached. |
| **F9** | `ftqMismatch` under `faultHold` | the mismatch flush **clears** `faultHold`/`faultEmitted`, exactly like a redirect | Otherwise deadlock: `ic.cmd.valid` is gated on `!faultHold` (`:245`), so the sequential refetch could never issue. Clearing is safe and self-correcting: the refetch re-hits the same ITLB/bus condition and re-raises the fault at the correct PC. `ftbSuppress` (L1) bounds it to one extra round trip. |
| **F10** | `quiesce` (STOP) or `stalled` (complex) with a live FTQ | `ftqMismatch` is gated `&& !quiesce && !stalled`; the FTQ and clamp simply hold | Firing a flush while fetch is quiesced perturbs state for no benefit. On the wake redirect (F2) everything is cleared. Under `stalled` the head is an already-emitted complex packet; the mismatch, if real, re-evaluates after `resume` (F3 flushes anyway). |
| **F11** | `invalidateAll` / `maintInvalidateAll` (CINV/CPUSH) with a live FTQ | FTB `valids` cleared (as the BTB's are); **the FTQ is left alone** | The FTQ describes windows *already fetched*. Cancelling it would strand the splice (same corruption as F7). The delivered-stale-response semantics is the same architecturally-defensible position the I-side chain already took for §6.5's bullet 3 (`IcacheInvalidateSpec` pins exactly-one-response-per-fetch). |
| **F12** | ring wedge risk | `ftqFlush` **never** cancels an in-flight I-cache response | `ringCount` is decremented ONLY by `ic.rsp.valid`, with no timeout and no other path (`FetchAlignPlugin.scala:290-294, 322-328`). This is exactly the wedge the I-side chain refused to create when it rejected MSHR §6.5 bullet 3. The FTQ flush touches FTQ state only; ring entries are marked stale, never dropped. |

Additionally, two structural invariants that must be asserted in simulation (falsifiability
hooks, mirroring Lever F §2.2's `diagFaultKind*Fires` precedent):

* **INV-A:** `ringKeep(i) > ringDrop(i)` for every occupied ring entry. Violation ⇒ a
  zero-word or negative-width push.
* **INV-B:** a ring entry has `ringKeep < 4` **iff** an FTQ entry was pushed in the same
  `ic.cmd.fire` cycle. Violation ⇒ a splice with nothing describing it (the F7 corruption).

---

## 5. FMax treatment and the mandatory census gate

### 5.1 What the netlist actually says today

The current #1 failing path at 221.828 MHz, traced cell by cell out of `final2_timing.rpt`
(the authoritative artifact for this baseline):

```
Slack -0.508ns   Source: FetchAlignPlugin_logic_decodePc_reg[1]_rep__1/C
                 Dest:   FetchAlignPlugin_logic_decodePc_reg[3]/D
Data Path 4.487ns (logic 1.272 / route 3.215 = 71.7% route), 14 levels
(CARRY8=2 LUT1=1 LUT4=1 LUT5=2 LUT6=5 MUXF7=2 RAMD64E=1)

0.109  decodePc_reg[1]/Q                          -> fo=108 net, 0.376ns route
0.650  CARRY8 BtbPlugin_logic_mem_reg_r4_..._i_1/O[3]   (spec2 way-4 index adder)
1.199  net ADDRF2                                  (fo=72, 0.550ns route)
1.297  RAMD64E .../RAMF/O                          (the Lever D speculative LUTRAM read)
2.243  _zz_BtbPlugin_logic_spec2Taken_3_40         (tag compare)
3.527  _zz_BtbPlugin_logic_predTaken2Comb          (the 16:1 spec2Taken(L0) late select, MUXF7x2)
4.092  when_FetchAlignPlugin_l529                  (suppressSlot1, fo=41, 0.515ns route)
4.517  decodePc_reg[3]/D                           (the decodePcNext mux)
```
**The BTB slot-1 read cone consumes 3.42 ns of the 4.487 ns path.** 7 of the top-10 setup
paths are this family (`decodePc → decodePc` / `decodePc → ibuf.headPtr`), all with the same
14–15-level composition.

This lever **deletes that cone**: `spec2*` goes away, and `slot1WouldPred` is re-sourced from
a 4-bit register-derived compare (§2.7). That is the FMax case, and it is why the dispatch
brief's original premise ("the real risk is FMax") is **inverted** on the netlist evidence.

### 5.2 What this lever ADDS to the contested corridor — stated, not glossed

| addition | where | depth estimate |
|---|---|---|
| `availEff = min(avail, spliceWords)` | at the head of the aligner's `avail < L0` / `slot1Ok` cones | +1–2 LUT levels, both operands register-launched |
| `ftqAt0` / `ftqNear` (32-bit reg-to-reg subtract + compare) | parallel to the aligner | off registers, does not extend the loop |
| `ftqConfirm`'s 4-bit `lenWords === brLen` | after `L0` | +1 level, replacing an 11-level RAM+tag+16:1-mux cone |
| `decodePc := ftqTarget` | into the `decodePcNext` mux | *shallower* than today's `Mux(rasPredictSlot0, rasPredTarget, btbPredTarget0)`, whose second operand is a RAM output |
| `ftqOvershoot`'s `effShift > ftqDelta` | after `effShift` | +1–2 levels on the redirect path (not on `decodePcNext`) |

**Retained:** the decode-time **slot-0** BTB/gshare/RAS read, because **G4** requires a
fallback for every branch the FTB does not cover (cross-window branches are Phase 2; a
window holds only one FTB entry). Deleting it as the grounding sketch proposed would convert
those branches from a 4-cycle predicted restart into a ~14-cycle commit-time mispredict, and
could make the lever a net IPC loss. This is a deliberate departure from the grounding's
recommendation, taken for IPC-monotonicity.

### 5.3 The honest FMax projection

* Deleting `spec2` removes the measured -0.508 ns holder.
* Retaining the slot-0 read leaves a shallower sibling of the same family. On the
  pre-Lever-D grounding, the slot-0 port closed at **-0.594 ns** against slot-1's -1.368 ns —
  identical RAM, identical tag compare, identical downstream cone, but addressed from a plain
  register. Rescaled onto today's baseline, expect the frontend family to land somewhere
  around **-0.45 to -0.55 ns**.
* The design is a **flat wall, not a single path**: `final2_timing.rpt` reports **4512 failing
  endpoints, TNS -462.267 ns** at the 250 MHz constraint. Path #4 is `DcachePlugin tagMem →
  dirtys` at **-0.454 ns**; path #10 is `AluEu → RegFilePluginNzvc` at **-0.423 ns**. So even
  *perfect* frontend relief moves WNS only -0.508 → ~-0.454, i.e. **221.8 → ~224.5 MHz
  (+1.2 %)**.

**The claim this spec makes is therefore:** *FMax-neutral-to-slightly-positive, plus a real
TNS and failing-endpoint reduction.* It is **not** a route to 250 MHz, and nobody should gate
this lever on a top-line number.

**Second honest caveat** (Lever D's own post-mortem, `progress-fmax-levers-2026-08-08.md`
:355-359): when Lever D took the BTB out of the `headPtr → headPtr` loop, a **new,
independent bottleneck at nearly identical depth** was exposed — `Aligner`'s
`preds(L0) → L1 → L0+L1 → slot1Ok → io_shift`, 16 levels, 69 % route, zero BTB cells. That
family does not appear in today's top-10, but the whack-a-mole pattern is real and this
lever's `availEff` clamp lands *directly on `slot1Ok`*. A post-route gate is mandatory
regardless of how good §5.1 looks.

### 5.4 **MANDATORY PREREQUISITE — Task 1 of the implementation plan: a fresh post-route census**

> **This is a gate, not an assumption.** No RTL for this lever may be written before it
> completes.

**Why it is required.** There is **no post-route `.dcp` for the current 221.828 MHz design** —
the 2026-08-09 gate wrote reports only. The newest `.dcp` on the machine
(`…/scratchpad/wt-asl-base/synth/fullcore_routed.dcp` @ `344c0c5` / 190.11 MHz) predates
Lever F, per-beat predecode and the whole I-side prefetch chain, and on that draw the frontend
contributes **zero** top-100 endpoints — it is unusable for this corridor. And
`final2_timing.rpt` was written with `-max_paths 10`, so **no per-family census of the 4512
failing endpoints exists anywhere on disk.** Every number in §5.3 is extrapolated from 10
reported paths.

**Deliverables of Task 1** (one full `impl` run at HEAD, on an **uncontended** machine —
check `free -g` and `ps aux | grep vivado` first and wait for a real window; at the time of
writing a sibling `macqd700-soc` `full_impl` held 6.9 GB):

1. `write_checkpoint` of the routed design (so future passes never repeat this).
2. `report_timing -max_paths 5000 -nworst 1 -setup` (full failing-endpoint list).
3. A per-family census: group all failing endpoints by leaf module prefix
   (`FetchAlignPlugin`, `BtbPlugin`, `DcachePlugin`, `RobPlugin`, `AluEu`, …) with
   count / worst slack / median slack per family.
4. `report_timing -through` probes for: (a) `BtbPlugin_logic_mem_reg_r4*` (the Lever-D
   spec2 cells — how many endpoints traverse them?), (b) the slot-0 port cells, and
   (c) the `Aligner preds(L0) → io_shift` family flagged in §5.3.
5. `report_utilization` + `report_design_analysis -congestion`.

**Gate conditions on Task 1's output:**

* **G-T1a.** If the `spec2`/slot-1 family does **not** own the plurality of frontend failing
  endpoints, §5.1's whole FMax case is wrong and the lever must be re-scoped before
  implementation.
* **G-T1b.** If the `Aligner preds(L0) → slot1Ok → io_shift` family is already at or worse
  than the frontend BTB family, the `availEff` clamp's placement must be re-designed (e.g.
  clamp only the `avail < L0` gate and derive `slot1Ok`'s bound differently) **before** any
  RTL is written.
* **G-T1c.** The projection "relief caps around ~224.5 MHz" must be re-derived from the real
  distribution and recorded in the plan. If the real cap is materially lower, the LUT and IPC
  cases must carry the lever on their own.

---

## 6. Cost, blast radius, slicing

### 6.1 Area

| item | estimate |
|---|---|
| FTB table, 128 × 63 b payload | ~1 LUTRAM block class, comparable to today's `BtbEntry` (60 b × 128) |
| FTB `valids` | 128 FF |
| FTQ, depth 4 | ~316 FF + ~8 FF pointers |
| `ringKeep`, 3 b × 3 | 9 FF |
| `ftbSuppress`, `ftbRes*` registers | ~80 FF |
| 4 speculative PHT reads + 4:1 select | small (PHT is 2 b wide) |
| **DELETED**: Lever D's `spec2*` 9-way replication | **-1302 LUTs / -512 LUTRAM** (its own measured landing cost) |
| **DELETED**: `Aligner.Result.slot1Sel`, the BTB slot-1 port and its 9 tag comparators | included above |

**Net expectation: LUT-neutral to LUT-negative, FF +~500.** This must be reported pre/post as
an explicit gate (**G3**), not assumed.

### 6.2 Blast radius

| file | change |
|---|---|
| `src/main/scala/m68k040/frontend/Ftb.scala` | **NEW** — `FtbPlugin`, `FtbEntry`, `FtqEntry` |
| `src/main/scala/m68k040/frontend/FetchAlignPlugin.scala` | fetch-side apply, `ringKeep`, FTQ, `availEff`, confirm/mismatch, `gsShiftValid` gate, `emittingFaultPacket` gate, `ftqFlush` in 4 existing `when` blocks |
| `src/main/scala/m68k040/frontend/Aligner.scala` | takes `availEff`; delete `slot1Sel` (`:18-27`, `:102`) |
| `src/main/scala/m68k040/frontend/Btb.scala` | delete `query2*`/`spec2*` (`:75-201`); consume `upd.payload.len`; fix the stale `queryPc` header comment (`:44`) |
| `src/main/scala/m68k040/frontend/Gshare.scala` | 4 speculative PHT reads off `idxBase ^ k` + late select |
| `src/main/scala/m68k040/services/Services.scala` | `BtbUpdate.len` |
| `src/main/scala/m68k040/execute/BranchEuPlugin.scala` | drive `len = (nextPc - pc) >> 1` |
| `src/main/scala/m68k040/rob/RobPlugin.scala` | carry `len` through `branchTrainMem`'s `BranchTrainPayload` |
| `src/main/scala/m68k040/Config.scala` | `ftbEntries`, `ftqDepth` |
| `src/main/scala/m68k040/top/FullCoreSynth.scala` + `src/test/scala/m68k040/fuzz/FuzzDut.scala` + `src/test/scala/m68k040/lockstep/ExecuteLockStepSpec.scala` + `src/test/scala/m68k040/bench/IpcBenchSpec.scala` | wire `FtbPlugin`, including **both** invalidate sources |
| `src/test/scala/m68k040/frontend/BtbLateSelectEquivalenceSpec.scala` | **DELETE** (its subject, `spec2*`, is removed). Record this explicitly in the ledger — it was a 49 152-point / 3-mutation-caught proof and its removal is a real, deliberate coverage retirement, not an accident. |

### 6.3 Slicing (each slice ends with the standing full-core post-route gate)

* **Slice 0 — the census gate (§5.4).** No RTL. Blocking.
* **Slice 1 — training only, inert.** `BtbUpdate.len` end to end (BranchEu → ROB →
  Btb/Ftb-stub). Provably behaviour-neutral (nothing consumes it yet), gated for zero
  regression. Mirrors V2a.1's inert-slice discipline.
* **Slice 2 — the FTB table + fetch-side lookup, `ftbEnable = False` by default.** A
  `RegInit(True/False)` sim-pokeable control — **NOT** an `in Bool()` inside the plugin
  `Area`, per the recorded trap (a top-level port where `default(...)` silently does nothing;
  cost last time: one full IPC sweep that came back bit-identical and looked like "the feature
  does nothing").
* **Slice 3 — the FTQ, `availEff` clamp, confirm-or-flush, `ringKeep`.** The big one. Ends
  with `FtbConfirmGuardSpec` + `FtbStreamEquivalenceSpec` green and the post-route gate.
* **Slice 4 — delete `spec2*`, re-source `slot1WouldPred` from the FTQ.** This is where the
  FMax delta lands; gate it *separately* so the delta is attributable.
* **Phase 2 (separate design pass):** cross-window branches (`brEndOff ≥ 4`, a two-window
  fetch plan); optionally the opword-class confirm hardening (§3.7); optionally deleting the
  decode-time slot-0 read once FTB coverage is measured high enough.
* **Phase 3 (separate design pass):** fetch-time RAS — record `brType = return` in the FTB so
  fetch knows *where* the return is and pops the RAS for *where it goes*. This is what covers
  `call-return`'s third transfer (`rts`); Phase 1 covers `bsr` + `bne` only.
* **Phase 4 (separate design pass):** the grounding's own "phase 2" — stop deferring slot 1 on
  a confirmed prediction by re-basing `slot1.pc := ftqTarget`. Worth ~1 cyc/branch, but it
  adds a mux to the `decodePc` loop, so it must be separately gated.

---

## 7. Explicit fallback: Approach C — recent-fetch-window replay buffer

**This is a documented retreat, not a from-scratch redesign.** If §3's correctness argument
cannot be closed with full confidence during implementation — specifically if
`FtbStreamEquivalenceSpec` (§9.3) cannot be made to pass with mutation coverage, or if
Task 1's gate G-T1b forces a clamp redesign that reopens §3.4 — **abandon Approach A and
implement Approach C instead.** Do not attempt to patch A's guard incrementally.

### 7.1 Mechanism

Change **nothing** about prediction. Keep `predictFire`'s flush exactly as-is. Add a tiny
fully-associative buffer of the last N = 4 fetched 8-byte windows.

```scala
case class ReplayEntry() extends Bundle {
  val valid = Bool()
  val base  = UInt(29 bits)                    // window base, pc[31:3]
  val data  = Bits(64 bits)                    // the 4 words
  val preds = Vec(ChunkPredecode(), 4)         // ~10 b each
}
```
* ~4 × (1 + 29 + 64 + 40) = **~536 FF**. No RAM, no new table, no training path.
* **Fill:** on every non-stale, non-fault `ic.rsp.valid`, round-robin, straight from the
  `rspWords`/`rspPreds` values already live in the push cone
  (`FetchAlignPlugin.scala:271-272`). Zero new datapath.
* **Use:** on ANY redirect (external / resume / mispredict / `predictFire`), compare
  `newPc(31 downto 3)` against the 4 `base` fields. On a hit, push that window into the IBuf
  **on cycle N+1** with the leading drop `newPc(2 downto 1)` applied, instead of waiting for
  the I-cache round trip; set `fetchPc := window + 8` so the sequential walk continues from
  the next window. The 4-cycle restart becomes ~1 cycle.
* **Coherence:** cleared on **both** `invalidateAll` and `maintInvalidateAll`, exactly as the
  BTB is. This is the *only* coherence obligation, and it is the same one the BTB already
  discharges.

### 7.2 Why it introduces no new hazard class

* The words are **byte-identical to what the I-cache would have returned** — they came from
  it, unmodified, and the `preds` came with them.
* The IBuf still holds the **sequential** stream from `decodePc`. There is no splice, no
  truncation, no length guess, no clamp. §3 does not apply at all.
* The prediction machinery is untouched, so `predTaken`/`predTarget`/EU verification/
  mispredict recovery are all bit-identical to today.
* The only new failure mode is *staleness after an I-cache invalidate*, closed by the clear
  above — and it is strictly narrower than the BTB's own staleness exposure, because a replay
  entry is consumed at most a few cycles after it was fetched.

### 7.3 Value and limits — stated honestly

* Helps only targets whose window is among the last 4 fetched: `hot-loop` **yes**,
  `call-return`'s `rts` **often**, `branchy` **partially**, cold/far targets **never**.
* Leaves the ~1-cycle `slot1WouldPred` defer untouched.
* Realistic capture: ~40–60 % of the restart cost on the loop kernels, ~0 % elsewhere.
  **Rough estimate +8–12 % aggregate**, versus A's ~+20 %.
* **Near-zero corridor risk:** entirely in the `redirect → ibuf.push` cone, fed from
  registers, touching neither the `decodePc` loop nor the `fetchPc` loop.
* One slice, one gate.

---

## 8. Alternatives considered and rejected

### 8.1 Combinational (unregistered) FTB read feeding `fetchPc` — REJECTED

Would remove the one-cycle `ftbResFresh` staleness (§2.4) at the cost of putting
`fetchPc → index → RAMD64E → tag compare → hit → mux → fetchPc(D)` in the fetch feedback
loop. **Directly disconfirmed by this project's own measurement:** the I-cache prefetch first
cut (`349a585`) built exactly this shape — `tPaddr → source mux → +1 (CARRY8×3) →
tagMem.readAsync (RAMD64E×2) → 20-bit tag compare → prefetchArmed → FSM`, 18 levels — and
measured **170.882 MHz, WNS -1.852 ns, a -36.29 MHz / -17.5 % regression**. The fix
(`8267ff1`) was to split it into two register-to-register hops, recovering to 221.828 MHz.
Do not rebuild the rejected shape.

### 8.2 Approach B — 4 parallel reads of the existing word-indexed BTB at `fetchPc + {0,2,4,6}`, priority-encoded — VARIANT, not rejected outright

Pro: zero new storage, zero new training path, tags stay exact instruction PCs (so a hit means
"a branch retired at exactly this PC" — strictly less aliasing than A's window index). Con:
4 read ports at fetch; still needs a `len` field added to `BtbEntry` for the truncation; and
**still needs the identical confirm-or-flush guard of §3**, because a word offset can still
land on an extension word rather than an instruction boundary. **Assessment: a
design-spec-time variant of A, not a separate approach.** Recommendation: build A. Revisit B
only if Task 1's census shows the FTB's own LUTRAM is a problem, since B's storage cost is
zero. *(Note B does not reduce §3's verification burden at all — that is the whole reason A
is preferred: A's extra storage buys a cleaner training/allocation story for the same guard.)*

### 8.3 "Prove the splice can never be reached by a pre-branch instruction's lookahead", instead of the clamp — REJECTED

Tempting, and it would save 1–2 LUT levels on `avail`. But it requires proving that no
instruction starting before `B` can extend past `B + 2·Lb`, which is exactly the
**overlapping-instruction-stream** case (the same window entered at different offsets on
different dynamic paths). On a 16-bit-aligned ISA that is legal and constructible. "Rare in
practice" is not a correctness argument in this project. Rejected.

### 8.4 Fetch full windows and splice at decode via `effShift` — REJECTED

Instead of truncating at the branch's end, fetch window W in full and skip the leftover
fall-through words at confirm time with a larger `effShift`. Pro: the `rsp → push` cone is
untouched; cross-window branches need no special case. Con: it moves the splice arithmetic
**into the `decodePc`/`shift` loop** — the exact contested corridor — and wastes up to 3
(or 7) IBuf words per predicted branch. Since the push cone has slack and the decode loop does
not, truncation is the right side to pay on. Rejected, matching the grounding's own
recommendation.

### 8.5 Issue the target fetch combinationally in the redirect cycle N (saving 1 of 4 cycles) — REJECTED

Puts the whole prediction cone in front of the ITLB/I-cache accept path. Bad ratio, bad
corridor. Unchanged from the grounding's own rejection.

### 8.6 Task #116 (resolve-time mispredict recovery) — REJECTED, unchanged

See N2. Orthogonal to this lever and weakened further by the fresh data.

---

## 9. Verification plan (binding)

### 9.1 Baselines (re-measure, do not quote)

All of these are the ledger's landed numbers at HEAD `bb4d40e`; every one must be
**re-measured on the implementation branch's own base commit**, not copied:

| gate | baseline |
|---|---|
| `ExecuteLockStepSpec` | **390 / 394** (the documented 4: 3 × ITLB + `STOP #imm → halt → IRQ → handler → RTE → resume`) |
| ported corpus | **58 fails of 870**, fail list byte-identical |
| full post-merge suite | **514 / 518** |
| post-route FMax | **221.828 MHz**, WNS -0.508 ns |
| CLB LUTs / FF | **103 740 / 47 343** |
| IPC agg `IPC_MEM=l2:5:70`, seeds 1/2/3 | 9228 / 9316 / 9207 cyc |
| IPC agg `IPC_MEM=zero`, seeds 1/2/3 | 5999 / 6019 / 6007 cyc |

Worktree isolation is **mandatory** for every A/B (`git worktree add`; a `git checkout <sha>`
in the shared tree caused a real collision before — task #199).

### 9.2 `FtbConfirmGuardSpec` — the load-bearing directed/exhaustive test

A component-level test on `FetchAlignPlugin` (+ a stub `FtbPlugin` whose table contents are
sim-poked directly, so wrong entries can be constructed at will). One directed case per row
of §3.5, each asserting **both** that the guard fires **and** that no packet with a
post-splice byte ever reached `feed`:

* **A1** real length > `Lb` (build a 2-word `Bcc.W` and claim `Lb = 1`).
* **A2** real length < `Lb` (build a 1-word `Bcc.B` and claim `Lb = 2`).
* **A3** the instruction at `B` decodes COMPLEX.
* **A4** overshoot — an instruction starting before `B` that ends strictly between `B` and the
  splice.
* **A5** an instruction straddling the splice.
* **A6** `pendingDrop > brWordOff` (declined at issue; assert no truncation and no FTQ push).
* **A7** `ftqPast` (forced by a poked FTQ entry).
* **A9** `ftqDelta ≥ 16` (assert the clamp is inactive and the confirm never fires).
* **L1/L2** — a *repeated* A3 case: assert the second occurrence does **not** mismatch again
  (the FTB entry was invalidated), and that `ftbSuppress` guarantees forward progress.
* Plus the positive control: a well-formed entry confirms, `feed` emits the branch with
  `predTaken`/`predTarget`/`phtIndex` stamped, `ibuf.io.flush` stays **low**, and no ring
  entry is re-staled.

**Mutation-tested, both directions**, matching the standard `BtbLateSelectEquivalenceSpec`
(3/3 mutations caught) and `PerBeatPredecodeEquivSpec` set this session: individually remove
each of `ftqLenBad`, `ftqOvershoot`, `ftqStarved`, `ftqPast`, the `availEff` clamp, `L1` and
`L2` — the suite must go **red** for each. A guard term no mutation can kill is either dead or
untested, and must be investigated before landing.

Collect every mismatch and assert once at the end. Do **not** abort at the first — that is
precisely the flaw that makes `PredecodeWordSpec`'s "exhaustive 65536-opword" claim
untrustworthy (standing project warning).

### 9.3 `FtbStreamEquivalenceSpec` — the differential proof

The strongest available statement, and the one that decides A-vs-C (§7):

> For a randomized code image **and randomized (including deliberately WRONG) FTB contents**,
> the sequence of `(pc, lenWords, words[0 .. lenWords-1], simple, complex, fault)` tuples
> emitted on `feed` with `ftbEnable = True` is **IDENTICAL** to the sequence emitted with
> `ftbEnable = False`.

i.e. **fetch-direction is architecturally invisible.** Run over ≥ 100 randomized images ×
≥ 10 randomized FTB tables each (a wall-clock-bounded target — report the counts actually
run, do not silently shrink them), with the FTB deliberately seeded with a mix of correct,
length-wrong, offset-wrong and fully-aliased entries. Same mutation discipline as §9.2.
Collect-then-assert, never abort-at-first-mismatch.

*Scope note, stated so nobody over-reads the result:* the tuple deliberately **excludes**
`predTaken`/`predTarget`, because a fetch-directed prediction legitimately changes *which*
instructions are fetched next. The equivalence is over the **framing** of whatever is emitted
— which is exactly the §3 obligation and nothing more. Target-correctness is covered by the
existing EU verification (H8) and by the end-to-end suites.

### 9.4 `FtqFlushSpec`

One directed case per row of §4 (F1–F12), plus live assertions of INV-A and INV-B running
throughout every other frontend test (a `SimPublic` + a monitor thread, the cheapest possible
falsifiability hook).

### 9.5 Existing suites

* `AlignerSpec`, `FetchAlignSpec`, `InstructionBufferSpec`, `BtbPluginSpec`, `GshareSpec`,
  `RasPluginSpec`, `PredecodeSimpleLenSpec` — all must pass at exact baseline parity.
  `PredecodeSimpleLenSpec` in particular is now **load-bearing for INV-P** (§3.3) and must be
  cited as such in the RTL comment.
* `BtbLateSelectEquivalenceSpec` is **deleted** with `spec2*` (§6.2) — record it as a
  deliberate coverage retirement in the ledger, with the reason.
* `ExecuteLockStepSpec` 390/394, fail set **byte-identical**.
* Full ported corpus (870), 4-shard parallel sweep via `tools/fuzz/ported-sweep-parallel.sh`:
  **58 fails, name-for-name identical** (`diff` empty). Run the branch/redirect-heavy ported
  tests explicitly *before* the full sweep — a fetch-direction framing bug manifests as a
  wrong-target or wrong-PC divergence, not as a slow clock.

### 9.6 IPC gate — the accept/reject decision

`IPC_SEED ∈ {1,2,3,4,5}` pinned, **paired** before/after, **both** memory models
(`IPC_MEM=zero` and `IPC_MEM=l2:5:70`), worktree-isolated. `JAVA_OPTS=-Xmx6g
~/sbt/bin/sbt 'testOnly m68k040.bench.IpcBenchSpec'`.

**ACCEPT** iff all of:
* realistic-memory aggregate **≥ +10 %** (projection ~+20 %);
* `hot-loop`, `branchy`, `call-return` each improve (they are the target);
* **no kernel regresses more than 2 %** — with the explicit caveat that `load/store` is the
  one kernel with real seed-to-seed variance (5 seeds span 46 cyc = 4.4 % under ideal memory),
  so its band must be read against that spread, not against zero. This is the trap that
  produced the earlier "-1.77 % regression" false alarm, closed as PRNG noise.

**Additionally report, whatever the verdict:** the `branchy` delta specifically, as the
adjudicator of §2.6's fetch-time-direction accuracy trade; and a swept FTQ depth
{2, 4, 6} so the depth choice is measured rather than asserted.

**REJECT ⇒ evaluate Approach C** (§7) against the same gate before abandoning the initiative
lever.

### 9.7 FMax + LUT gate

* Post-route (**not** OOC — OOC has been proven unreliable this session, including for
  targeted-family checks), **uncontended** (`free -g` + `ps aux | grep -i vivado` first, and
  wait for a real window).
* Report WNS, top-10 endpoints with their families, TNS, and the failing-endpoint count
  against Task 1's census.
* **Accept:** FMax ≥ 221.828 MHz - 2 MHz (i.e. no material regression), and the `spec2`
  family absent from the top-10.
* **LUT: explicit pre/post report.** Expect net ≤ 0 (§6.1). A material LUT increase is a
  REJECT under **G3** independent of FMax and IPC.
* Slice 4 (the `spec2` deletion) must be gated **separately** so its delta is attributable —
  this session's repeated lesson is that combined gates hide which lever did what.

---

## 10. Open questions for the plan-writing pass

1. **Re-confirm every line citation against live source before editing.** This spec cites
   `FetchAlignPlugin.scala` :180-195, :243-264, :271-314, :401-412, :428-440, :562, :606-626,
   :637, :661-677, :680-748; `Aligner.scala` :18-27, :95-110, :167, :267; `Btb.scala` :44,
   :52-63, :75-201, :216-228; `Gshare.scala` :81; `BranchEuPlugin.scala` :231, :247, :251-262;
   `IcachePlugin.scala` :60-72; `Services.scala` :51-70. Verify all of them.
2. **`FtbPlugin` as a `FiberPlugin` vs. an `Area` inside `FetchAlignPlugin`.** The former
   matches `BtbPlugin`/`RasPlugin`/`GsharePlugin` convention and gets a standalone unit-test
   DUT for free; the latter avoids a wiring round trip on a *fetch-side* signal. Recommend the
   plugin form for testability; confirm the wiring does not add a Fiber build-order cycle
   (`FullCoreSynth.scala:66-70`'s `mispredictRedirect` comment documents the precedent hazard).
3. **`ftqDelta`'s exact width and saturation.** `(brPc - decodePc) >> 1` is a 32-bit subtract;
   confirm the `ftqNear` bound (`delta(31 downto 4) === 0`) is the right one given
   `HEAD_WORDS = 10` and that `ftqPast` is derivable from `delta(31)` without a separate
   magnitude compare.
4. **Where exactly `availEff` is substituted.** `Aligner.align`'s parameter, `p0LiveReg`'s
   three validity flags — but **NOT** `ibufRoomForIssue` (`:243-244`, which must keep using
   raw `ibuf.io.cnt`, physical occupancy) and **NOT** `InstructionBuffer.io.avail` itself.
   Pin this list before implementing; a wrong substitution here is a silent-corruption
   vector.
5. **Whether `ftqOvershoot` should suppress the emit or allow it.** §3.5-A4 argues *allow*
   (the packet's bytes are genuine, and `newPc = decodePcNext` loses nothing). Confirm against
   `effShift`/`decodePcNext`'s exact timing (`:606-618`) that allowing it does not create a
   second-order case where slot1 also emitted and `decodePcNext` accounts for both.
6. **`ftbEnable` control shape.** MUST be a self-assigned `RegInit`, never `in Bool()` inside
   the plugin `Area` (recorded trap: a top-level port where `default(...)` silently does
   nothing and every full-core testbench reads 0). And it must not be poked immediately after
   `forkStimulus` (reset overwrites it) — use the non-blocking
   `fork { for (_ <- 0 until 8) { poke; waitSampling() } }` pattern.
7. **Task 1's census script** — write it as a reusable `synth/census.tcl` so no future pass
   has to rediscover that `-max_paths 10` is useless for family analysis.
8. **Does `Config.scala` need `ftbEnable` as an elaboration parameter too**, so a
   zero-cost-when-disabled build exists for A/B? Probably yes; confirm against how
   `btbEntries` is threaded (`Config.scala:23` → `ParamPlugin.scala:19` → `Global`).

---

## 11. Honest status summary

* The **IPC case is strong and measured**: 33.0 % of the realistic-memory aggregate is
  control-flow bubbles on *correctly predicted* branches, with the mechanism confirmed
  cycle-for-cycle in RTL.
* The **FMax case is favourable but bounded**: this lever deletes the design's measured #1
  failing cone rather than adding to it, but the design is a flat wall (4512 failing
  endpoints) so the top-line upside is ~+1.2 % at best. Do not gate on the top line.
  §5.4's census is a **hard prerequisite**, not a nicety — the current FMax reasoning rests on
  10 reported paths and no routed checkpoint exists.
* The **risk is concentrated in exactly one place**: the mid-instruction mis-framing hazard of
  §3, which is a correctness class this design has never had. §3.3–§3.6 argue it is closed
  *by construction* by reusing three already-load-bearing aligner gates plus a genuine-word
  clamp, and §9.2/§9.3 specify the mutation-tested differential proof that must back the
  argument up rather than replace it.
* **The retreat is pre-authorised**: if that proof cannot be made to hold, Approach C (§7) is
  the documented fallback — ~+8–12 %, one slice, no new hazard class — not a re-design.
* **Deliberate departure from the grounding's sketch**, recorded so it is not mistaken for an
  oversight: the decode-time **slot-0** BTB/gshare/RAS read is **retained** as the FTB-miss
  fallback (§5.2), because deleting it would convert every uncovered branch into a full
  commit-time mispredict and could make the lever a net IPC loss. This trades some of the
  projected FMax relief for IPC-monotonicity (**G4**), and is revisitable in Phase 2 once
  real FTB coverage is measured.
