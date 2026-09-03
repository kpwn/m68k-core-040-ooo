# Per-branch RAT/Freelist checkpoint — design scope

**Date:** 2026-09-03
**Repo / branch:** `m68k-core-040-ooo`, `fmax-closure-fanout`, HEAD `3278ded`
**Status:** SCOPE ONLY. No RTL is authorised by this document. It exists so a future
session can execute directly against it instead of re-deriving the analysis — per the
explicit brief this document was written under.
**Primary sources, re-read in full for this document, not taken from prose:**
`src/main/scala/m68k040/rename/RatTable.scala`, `Freelist.scala`, `RenameStage.scala`;
`src/main/scala/m68k040/rob/RobPlugin.scala` (flush/rollback/age logic, ~2714 lines);
`src/main/scala/m68k040/frontend/Ras.scala` (the checkpoint/restore precedent);
`src/main/scala/m68k040/execute/iq/IssueQueuePlugin.scala` (flush fanout);
`src/main/scala/m68k040/ls/StoreQueue.scala` (the ROB age-compare precedent).
**Companion SoC-repo bug thread:** `macqd700-soc-worktrees/m68k040ooo-integration/docs/
BUG_calibration_word_misplaced_0d00.md`, Parts 29 (mechanism first found), 61 (the
rejected "registered early-flush" attempt and its root-cause finding), 62 (an important
caveat on this fix's actual payoff for the specific boot bug — read before green-lighting
implementation on that basis alone), 105 (current status, still open as of the newest
build).

---

## 0. What this document is answering, and what it is not

Part 29 (real-hardware ILA capture, `u1.pc`-tapped at the branch EU) found a concrete
mechanism: a correctly-resolved branch's ROB entry (`robId=18`, the boot ROM's
`0x40800284: bsrw 0x408010f0`) is **legitimately, correctly flushed as collateral
damage** by an unrelated, OLDER (ROB-earlier), independently mispredicting indirect
branch (`robId=16`) that reaches the ROB head and fires `branchRedirect` one cycle
before `robId=18` can retire on its own. This is standard, correctly-implemented
flush-younger-than-mispredictor semantics — not a bug — applied to an adversarial
ROB-allocation-order collision that (per Part 30's six real-hardware captures) recurs
on nearly every boot attempt because the culprit is almost always the ROM's own
VIA1 shift-register RTC/PRAM bit-bang driver, called hundreds of times per boot and
woven immediately around the collision site even on a healthy (MAME) boot (Part 62).

Part 61 investigated the obvious textbook fix — register the branch EU's S1
resolution and fire a narrower squash one cycle later instead of waiting for retire —
and found it **structurally unsafe to bolt onto this codebase's current global
"restore to committed" RAT/Freelist rollback**, for a precise, re-verified reason
(§2 below). It scoped the real fix as "a multi-day project" without detailing it. This
document is that detail.

**Read Part 62 before treating this as a boot-bug fix.** Part 62's own MAME-based
analysis is a load-bearing caveat this document must not let readers skip: `0x40800284`
has **no retry loop** (hit exactly once per boot, in two independently cross-checked
traces), which closes off the strongest version of "flush-timing-alone permanently
blocks boot." Part 62's own verdict is **"flush timing is a modulator, not the sole
cause"** — the boot block is at least two stacked, independently-real, real-time-paced
hardware mechanisms (this collision's odds, **and** a separately-confirmed `$0D24`/
csCode-21 race one `bsr` further downstream, Part 44). Building this mechanism will
very likely change (probably improve) the odds of a clean boot pass, but per Part 62 and
Part 105 (which re-confirms the blocker is unaffected by the newest unrelated RTL fix
landed since) **it is not proven, and should not be sold, as a guaranteed unblock of
the specific `$0DB0` boot hang** — that requires a real-hardware trial after building it,
not an a-priori guarantee. What this document DOES establish, independently of that
specific bug, is a real, measured, core-wide IPC cost for today's retire-gated recovery
(Part 61 §3, reproduced in §5.4 below) that stands on its own as engineering
justification.

---

## 1. Today's mechanism, read end to end

### 1.1 RAT — `RatTable.scala`

Each of the five RATs (`intRat` archDepth=20/physIdWidth=6, `nzvcRat`/`xRat`/`fpccRat`
archDepth=1/physIdWidth=4, `fpRat` archDepth=8/physIdWidth=4 — sizes from
`RenameStage.scala:31-41`) is a dual register-file: `specReg`/`commReg` Vecs plus one
`location` bit-vector per arch register (`location(a)=1` ⇒ `specReg` is current,
`=0` ⇒ `commReg` is current). Reads are `Mux(location(a), specReg(a), commReg(a))` —
fully combinational, O(1). **Rollback is a single global op**: `when(io.rollback) {
location := 0 }` — every arch register's read reverts to `commReg` in one cycle, with
zero per-branch or per-robId selectivity (`RatTable.scala:97-100`).

### 1.2 Freelist — `Freelist.scala`

A circular free-phys-id ring (`ram: Mem(UInt(idW), 1<<ptrW)`) with `head`/`tail`/`count`
pointers plus a fourth pointer, `commHead`, that mirrors `head` but advances only as
popped destinations actually **retire** (comment at `Freelist.scala:74-89`). Flush is
**pointer-only**: `when(initDone && io.flush) { head := commHead; count :=
initCountVal }` (`:124-131`) — returns every in-flight speculative pop to the pool by
snapping `head` back to the one committed-shadow pointer. The RAM itself is never
rewritten on flush; the file's own comment explains why this is safe **only** because
the RAT also resets to committed state on the same flush (no stale speculative phys-id
survives in flight). Push (return of a freed old-pdst) happens **only from actual
commits** — `RenameStage.scala:93-102` wires `intFree.io.push(k).valid :=
commitPorts(k).valid && commitPorts(k).intWrite`, and `RobPlugin.scala`'s own invariant
comment (`Freelist.scala:148-161`, cross-referenced) proves `commitPorts(k).valid &&
flushing` is architecturally unreachable via `headReady`'s `!flushing` gate. **This
means the Freelist's RAM is never written speculatively** — every write is an
already-safe, already-committed value. That asymmetry with the RAT/RAS matters for
cost (§3.2).

### 1.3 ROB flush — `RobPlugin.scala`

`branchRedirect = retire0 && p0.retireAlone && mispredictStore(h0)` (`:1850`) — fires
**only once the mispredicting branch is the ROB head**, i.e. only after every older
instruction has already retired. `doFlushReg := branchRedirect || exc.redirectValid ||
debugRecoverEnter || debugPcApply` (`:2537`) registers that OR one cycle; `flushing =
flush.valid || doFlushReg || excSquash` (`:952`) is **the one signal** that fans out to
`when(flushing) { tail := head; count := 0 }` (`:2550-2552`) in the ROB itself, and —
per `RenameStage.scala`'s own class doc — to "rollback all RATs + flush all
freelists" (`intRat.io.rollback := flush`, `intFree.io.flush := flush`, etc.,
`RenameStage.scala:106-115`), and further out to the IssueQueue's `flushSignal`
(`IssueQueuePlugin.scala:523-578`) and (per that same class-doc list) LSU-SQ/DTLB/
ITLB/FetchAlign/RAS.

**Everything downstream of `flushing` is keyed to one flat Boolean pulse, not to a
robId.** This works today because `branchRedirect`/`excSquash`/`debugRecoverEnter` are
**all** retire-gated (they only ever fire once their triggering instruction reaches the
ROB head) — so at the instant `flushing` asserts, "everything currently resident in
the ROB/IQ/SQ" and "everything younger than head" are, by construction, the identical
set. `tail := head` is correct precisely because nothing between the old head and the
old tail can be anything but wrong-path or the-thing-that-just-flushed's own younger
work.

### 1.4 The existing per-branch checkpoint precedent — `Ras.scala`

The Return Address Stack already has EXACTLY the shape of mechanism this document
proposes generalising, landed 2026-08-28 as an independent fix for unbounded RAS
prediction-quality drift (`Ras.scala:18-94`, `checkpointSave`/`checkpointRestore`
ports). Its class doc is explicit that it is a **conservative approximation, not a
true per-branch scheme**: `checkpointSave` fires on `rob.count === 0` (nothing
outstanding ⇒ live state is architecturally correct by construction) rather than on
each branch's own rename, because — in the RAS author's own words, quoted directly —
a precise per-branch version "would be real ROB-side plumbing, out of scope for this
narrowly-scoped predictor fix." Two design choices from `Ras.scala` transfer directly:

* **Content, not just a pointer, must be checkpointed** when the structure being
  checkpointed can be **speculatively overwritten** between checkpoint and restore.
  RAS learned this the hard way: a naive pointer-only checkpoint is unsound if
  wrong-path speculation can wrap the RAS's circular buffer (16 entries) more than
  once before a restore, given the ROB is 64 deep — so `Ras.scala` checkpoints the
  full 16×32-bit `ras` array (`ckRas`), not just `rasSp`/`count`. The **same question
  must be asked, and answered, for each half of this document's proposal** (§3.1/§3.2)
  — and the answer differs between the RAT and the Freelist, which is a real,
  cost-relevant asymmetry (§1.2 above; RAS is closer to the RAT case, not the
  Freelist case).
* **`checkpointRestore` is given last-assignment priority** over any same-cycle
  push/pop/invalidate, with a simulation-only assert (`!(checkpointSave &&
  checkpointRestore)`) pinning that the two never fire together. The same discipline
  applies below.

---

## 2. Why today's mechanism cannot support a narrower flush — two distinct gaps,
## not one

Part 61 found and documented the first gap in depth. Reading `RobPlugin.scala`'s flush
fanout in full for this document surfaces a **second, larger gap** that Part 61's own
scope (RAT/Freelist only) did not fully size. Both must close for a genuinely narrow/
early flush to be correct; closing only the first is not sufficient, and a design that
implements only the first is not safe to ship. This is the single most important
finding of this document, so it is stated up front rather than left to be inferred from
the task breakdown.

### 2.1 Gap 1 (Part 61's finding, re-verified): no per-branch RAT/Freelist state

`RatTable.io.rollback` and `Freelist.io.flush` are each a **single global** "restore to
last committed" operation. There is no hook to roll back "only what's younger than
robId X" while leaving `[head, X)` untouched. Firing the *existing* `flushing`/
`doFlushReg` machinery **early** (before the mispredicting branch reaches head, with
older, not-yet-retired entries still resident between `head` and the branch) breaks the
coincidence §1.3 relies on:

* If `flushPc` is set to the branch's own resolved target (the natural "narrow squash"
  choice), the global rollback-to-committed discards every not-yet-retired **older**
  entry between the current head and the branch too — and fetch resumes past them.
  Their architectural effects are never executed. **Silent, permanent instruction
  loss** — strictly worse than today's latency-driven nondeterminism.
* If `flushPc` is instead the current committed PC, the corruption is avoided, but the
  design degenerates into "squash everything in flight the instant ANY branch anywhere
  resolves mispredicted" — safe, but strictly MORE aggressive than today, and delivers
  no latency win at all (this is, functionally, just today's mechanism with worse
  timing headroom).

### 2.2 Gap 2 (new to this document): every OTHER `flushing` consumer also assumes
### "flush is always head-anchored," and stale-completion safety currently rests on
### that assumption

`flushing` does not stop at RAT/Freelist. Per `RenameStage.scala`'s own class doc it
also fans out to the IssueQueue, the LSU Store Queue, DTLB/ITLB, FetchAlign, and RAS.
Every one of those consumers today implements "flush" as **"discard everything I am
currently holding"** — a flat pulse, not an age comparison — and that is only correct
because, per §1.3, "everything currently resident" and "everything younger than the
flush point" are the same set under a head-anchored flush.

Concretely, `IssueQueuePlugin.scala` squashes **all** slot selection and the registered
issue-stage pipe on `flushSignal` (`:523-578`), including an explicit fix (`:562-579`,
task #139) for a same-cycle race where a wrong-path uop that entered the registered
issue stage the cycle *before* a flush must not be allowed to present as valid to the
EU on the flush cycle itself. `RobPlugin.scala` itself relies on the identical
principle for robId reuse: a completion for a since-discarded wrong-path robId is
tolerated **only** because "alloc-reset has priority on a reused index" —
`RobPlugin.scala:1475-1479`'s comment states this explicitly: a stale completion write
is unconditionally overridden by the allocator's `completes := False` reset when that
robId is reallocated, **on the same cycle**. This works today because reallocation of a
just-freed robId only ever happens after a **head-anchored** `tail := head` flush,
where every stale in-flight completion for a discarded entry is known, by construction,
to be at most a few EU-pipeline-depths old relative to the flush — the existing ordering
priority is sufficient.

**An early/narrow flush breaks this in a new way that Parts 19-27 of the boot
investigation already spent five real-hardware ILA rounds ruling out for *today's*
design** (concluding, each time, no robId-reuse race exists — precisely because
today's flush is head-anchored). Generalising `tail := robId_i + 1` (preserving
`[head, robId_i]`) reclaims `robId_i+1 .. old_tail` for new allocations **immediately**,
while:

* The IQ may still be holding, or an EU may still have in-flight, an issued
  wrong-path uop from that same now-reclaimed range that has not yet completed — and
  today's IQ flush logic squashes indiscriminately (§ above), not by age, so it has no
  way to leave `[head, robId_i]`'s own not-yet-issued entries alone while squashing
  only `(robId_i, old_tail]`.
* A late completion for a genuinely-stale, since-reclaimed robId could arrive after
  that robId has already been reallocated to a **new**, correct-path instruction and
  already itself completed — a same-robId-two-legitimate-owners collision that the
  existing "alloc-reset wins" priority does not, on inspection, obviously still cover
  once the two events are separated by more than one cycle (today's fix is a
  same-cycle override, not a generation/epoch tag).

**This is not disproven — it is an open, load-bearing correctness question this
document is explicitly not resolving**, flagged here so the implementation plan (§6)
budgets real investigation time for it (Task S3) rather than treating it as a detail.

### 2.3 The one genuine piece of good news found while establishing §2.2

`IssueQueuePlugin`'s slots already carry `robId` in every context (`hot.robId`,
`IssueQueuePlugin.scala:116-234`), and `StoreQueue.scala` already carries `robId` per
entry **and** already implements a wraparound-safe ROB age-compare primitive
(`StoreQueue.scala:327-360`, `committed(a)` ⇒ unconditionally older, else circular
`(b-a) mod 64` compare) — hardened by a real, documented bug fix (the
`movem_rom_mem_forms` ported-test regression, where a naive `< 32` half-ring threshold
broke because a still-undrained precise store can legitimately sit more than 31 robIds
behind a live query). **The primitive an age-selective squash needs — "is robId A
strictly younger than robId B, correctly, across wraparound" — already exists in this
codebase, in production, with its own subtlety already paid for.** Generalising the IQ
and SQ's flush logic from a flat pulse to "squash if `age(slot.robId) > age(robId_i)`"
is mechanical reuse of an already-proven idiom, not new invention — but it still means
touching real per-slot squash logic in at least two more plugins beyond RAT/Freelist,
which is the direct source of this document's "multi-day, not multi-hour" sizing.

---

## 3. Proposed mechanism

### 3.1 RAT checkpoint

Add a small bank of **N checkpoint slots** to each of the five RATs (or, more likely,
one shared checkpoint bank sized to the sum of all five RATs' widths, indexed
identically — implementation choice, not a design-level distinction). Each slot stores
a **flat, fully-resolved current-mapping snapshot** — i.e. the *combinational read
output* `Mux(location(a), specReg(a), commReg(a))` for every `a` in `archDepth`,
captured post-this-cycle exactly as `Ras.scala`'s `nextRasSp`/`nextCount` pattern does
(`Ras.scala:136-144`) — **not** a location-bit + specReg pair. This sidesteps needing a
per-checkpoint `location` vector at all: restore simply writes the flat snapshot into
`specReg` and forces `location := all-ones`, i.e. "trust specReg for everything after a
restore" (a new op RatTable does not have today; `rollback` forces `location := 0`,
the opposite polarity). Per-RAT checkpoint width: `archDepth × physIdWidth` — 120 bits
(int) + 4 (nzvc) + 4 (x) + 32 (fp) + 4 (fpcc) = **164 bits/slot**.

**Content, not just a pointer, is required here too**, exactly per `Ras.scala`'s own
lesson (§1.4): the RAT's `specReg` IS speculatively overwritten by every younger
rename between checkpoint and restore, so a pointer-only scheme is not available — the
RAT has no pointer to begin with, only content, so this is the natural (only) design,
not a choice.

### 3.2 Freelist checkpoint — cheaper than RAS's, for a real structural reason

Checkpoint **only the `head` pointer** (`ptrW` bits) per freelist per slot — NOT the
RAM content. This is the asymmetry flagged in §1.2: unlike the RAS's `ras` array
(written by *speculative*, possibly-wrong-path pushes) and unlike a naive read of
`Ras.scala`'s "checkpoint content, not just the pointer" lesson, the Freelist's `ram`
is **only ever written by already-committed pushes** (§1.2). Between a checkpoint
(taken at a branch's own rename) and its restore (that branch's own misprediction
resolution), the only thing that can happen to Freelist state is: (a) more speculative
**pops** from younger renames — exactly what `head := checkpointHead` undoes, same
shape as today's `head := commHead`; (b) legitimate **pushes** from older,
still-in-flight, eventually-retiring instructions between the old head and the branch —
which land at `tail` and are UNAFFECTED by a restore that only touches `head`, exactly
mirroring how today's global flush leaves `tail`/`commHead`/the RAM alone. `count` is
**derived**, not stored: `count := tail - checkpointHead` (wrapping `ptrW`-bit
arithmetic) at restore time, mirroring how today's flush hardcodes `count :=
initCountVal` because the `tail - commHead == freeN` invariant always holds by
construction — the per-branch case needs the general subtraction instead of the
constant, but no new storage. Per-freelist checkpoint width: `ptrW` bits — intFree
(physCount=50) 6 bits, the other four (physCount=16 each) 4 bits — **22 bits/slot**
total across all five freelists.

**This asymmetry is worth stating plainly because it is easy to over-cost by pattern-
matching on RAS**: a naive "checkpoint everything RAS-style" design would try to
snapshot Freelist RAM content too and pay for it; the actual required cost is an order
of magnitude smaller, because the Freelist's own write discipline (RAM written only by
committed values) makes the pointer-only scheme sound where RAS's isn't.

### 3.3 Checkpoint allocation and the fallback that keeps this incremental

Maintain N slots, each with a `valid` bit and an `owner: UInt(6 bits)` (robId). At
**rename time**, for a uop that is a branch (or more precisely, per `Ras.scala`'s own
carve-out reasoning, any uop `BranchEuPlugin` can resolve as a redirect — this needs
the exact predicate cross-checked against `MicroOpAssembler`'s branch-cracking rules in
implementation, not re-derived here): if a free slot exists, allocate it (round-robin
or a small freelist of slot ids — same idiom as the physical-register Freelist itself,
just N-deep instead of 50/16-deep), tag the uop's `RenamedUop` payload with the
checkpoint id (`log2Up(N)` new bits, threaded rename → dispatch → ROB exactly like
`robId` already is), and drive `checkpointSave` on all five RATs and five freelists that
cycle. **If no free slot exists, the branch gets no checkpoint and falls back
unmodified to today's retire-gated `branchRedirect` path.**

This fallback is the single most important design choice in this document, for three
reasons: (1) it means dispatch/rename never has to stall waiting for a checkpoint slot
— unlike the physical-register Freelist's `popReady` gate, checkpoint exhaustion
degrades gracefully rather than blocking the pipeline; (2) it bounds the new
mechanism's correctness surface to exactly N in-flight branches at a time, which is
what makes an incremental N=2 slice (§5.3) meaningful rather than a toy; (3) it means
the existing, re-verified-correct retire-gated mechanism (Part 61 §1) is **never
removed** — it remains the architectural backstop for every branch that doesn't get a
checkpoint, exactly as Part 61 §6(b) anticipated ("wired alongside, not replacing,
today's retire-gated `branchRedirect` as a correctness backstop").

### 3.4 ROB tail truncation, generalised

`BranchCompletion` (`BranchCompletion` bundle referenced at `BranchEuPlugin.scala`
S1, `robId`/`mispredict`/`nextPc`) gains a `checkpointId`/`hasCheckpoint` field,
threaded the same way `robId` already is. On `s1Valid && mispredict && hasCheckpoint`,
register the pulse one cycle (same FMax-driven discipline as `doFlushReg` today — no
combinational execute→flush path) and fire:

```
tail  := robId_i + 1          // preserves [head, robId_i]; today's code has
                               // tail := head only because head == robId_i always
count := (robId_i + 1) - head // wrapping robIdW-bit arithmetic; today's flush
                               // hardcodes count := 0 because tail := head trivially
                               // makes it so
```

reusing the SAME `age = robId - head` wrapping-arithmetic idiom `RobPlugin.scala`
already uses for fault-port arbitration (`:1644-1653`) and `StoreQueue.scala` already
uses for its own age compare (§2.3) — not a new primitive.

### 3.5 The fanout generalisation (the actual multi-day part, per §2.2)

Every current `flushing` consumer that squashes "everything I'm holding" must instead
squash "everything younger than `robId_i`," using an age compare against each entry's
own already-present (IQ, SQ) or newly-threaded (DTLB/ITLB/FetchAlign/RAS) robId:

* **IssueQueue**: per-slot `age(slot.hot.robId) > age(robId_i)` replacing the flat
  `flushSignal` gate at each of the sites enumerated in §2.2 (slot selection `:523`,
  the registered issue-stage pipe `:547-579`, including re-deriving task #139's exact
  same-cycle-race fix for the narrower predicate).
* **StoreQueue**: already has the age-compare primitive (§2.3); needs its `flush`
  input (today presumably also a flat pulse — not re-verified in this document, flag
  for S3) converted to the same per-entry predicate.
* **DTLB/ITLB/FetchAlign/RAS**: not read in this document (out of the explicit scope
  Parts 61/62 already covered); each needs the SAME question asked — does it carry a
  robId today, and does its flush consumer squash flatly? `RasPlugin` is a special
  case: it has its OWN checkpoint/restore scheme already (§1.4), but keyed to
  `rob.count === 0` (a conservative, ROB-drain-triggered proxy), not to a specific
  branch. Whether the RAS's existing mechanism can be left as-is (its own doc already
  argues its bound is "whatever spans the last ROB-drain to the next flush," which
  remains true and sound even alongside this new mechanism, since it is a
  perf-only structure the EU always re-verifies) or should also be generalised to
  per-branch is an **open question for S3**, not decided here — the RAS's own
  correctness does not depend on this document's mechanism, only its prediction
  *quality* might benefit from a matching upgrade.

### 3.6 Checkpoint reclamation

When checkpoint slot `i` (owning `robId_i`) restores, every OTHER valid checkpoint
slot `j` with `age(owner_j) > age(robId_i)` names a branch that has just been flushed
away — its slot must be invalidated and returned to the free-checkpoint pool in the
same cycle (an N-wide comparator bank, N small by construction, cheap). Checkpoints
for branches `age(owner_j) <= age(robId_i)` (older, still in flight) are untouched —
each lives in its own dedicated slot's storage, independent of slot `i`'s restore,
which only ever reads slot `i`'s own registers and writes the live spec-RAT/Freelist
state (never another slot's storage) — so no explicit action is needed to protect them
beyond "don't touch other slots," which the RTL structure already guarantees by
construction.

### 3.7 Interaction with exceptions and debug recovery — explicit priority needed

Today, `doFlushReg := branchRedirect || exc.redirectValid || debugRecoverEnter ||
debugPcApply` are mutually exclusive **in practice** because all four are retire-gated
(§1.3). An early/narrow branch-restore is **not** retire-gated, so it can now fire
concurrently with, or in a cycle adjacent to, an exception/debug event that is
triggered by an OLDER instruction still sitting between `head` and `robId_i`. The
exception path must win in that race (an older fault's precise-exception semantics
cannot be preempted by a younger branch's speculative-recovery fast path), which
means: **the narrow restore must be suppressed, or itself be robId-gated to check that
no older, currently-resident entry has an outstanding fault/RTE/debug-halt condition,
whenever it would fire.** This is exactly the class of interaction Part 61 §6(c) named
("careful interaction analysis with the SAME precise-drain/debug-recovery races
`efcd953`/`77ce9bf`/`683355d` already had to fix") and is not resolved here — flagged
as a named implementation task (S4) with its own regression obligation (re-run the
precise-drain/debug-recovery directed corpus those three commits added, not just the
mispredict corpus Part 61 already re-ran).

---

## 4. Real hardware cost estimate

Per this project's own established practice (see the D-cache valids/dirtys LUTRAM fold
retrospective: raw register-count deltas are a reliable floor for FF cost, LUT cost for
the surrounding select/compare logic is a separate quantity that needs an actual OOC
synth gate to pin precisely, and can move in either direction from naive scaling once
routing/congestion effects are included) — this section gives a floor, not a
prediction, and explicitly does not claim synth-verified numbers (none were run; no
RTL exists yet).

**Storage floor, per checkpoint slot:**

| component | bits/slot |
|---|---|
| RAT snapshot (int 120 + nzvc 4 + x 4 + fp 32 + fpcc 4) | 164 |
| Freelist head pointers (int 6 + nzvc 4 + x 4 + fp 4 + fpcc 4) | 22 |
| control (valid 1 + owner robId 6) | 7 |
| **total** | **≈ 193 bits ≈ 200 bits** |

| N | FF floor (storage only) |
|---|---|
| 2 (the minimal slice, §5.3) | ~400 FF |
| 4 | ~800 FF |
| 8 | ~1,600 FF |

**LUT cost — two separate sources, neither precisely estimable without a synth gate:**

1. **Checkpoint bank select/allocate/restore logic**: an N-way write mux into the
   checkpoint bank on save, an N-way read mux on restore keyed by the resolving
   branch's checkpoint id, an N-wide age-compare bank for reclamation (§3.6), and a
   small (N-entry) checkpoint-id allocator mirroring the physical Freelist's own
   pointer-ring idiom at N-deep instead of 50/16-deep. Order-of-magnitude: a few
   hundred LUT at N=4, growing sub-linearly-to-linearly toward perhaps ~1-2k LUT at
   N=8 — smaller than or comparable to the storage FF count, by the general pattern
   this project's own LUT-count-reduction campaigns have repeatedly found for
   select/mux logic around register arrays of this size class.
2. **The fanout generalisation (§3.5)**: age-compare logic added to the IssueQueue's
   per-slot squash (all `IssueQueuePlugin.scala` sites listed in §2.2) and the
   StoreQueue's flush path, plus whatever DTLB/ITLB/FetchAlign/RAS turn out to need
   (§3.5, unread in this document). This is very likely the LARGER of the two LUT
   costs, and is fundamentally not estimable from this document's own reading —
   it requires reading those plugins' current flush wiring line by line, which S3
   (§6) is scoped to do.

**Bottom line**: a defensible FF floor of ~400-1,600 FF depending on N, with an
honestly-unquantified LUT cost that could plausibly run from a few hundred (checkpoint
bank alone) to several thousand (once the fanout generalisation is priced) — **this
project's own standing rule (every slice ends in a full-core OOC synth gate) is the
only way to pin the real number**, and that gate should be run per-slice (§6), not
once at the end, so a costly slice is caught early rather than after the whole
mechanism is built.

---

## 5. Alternatives and tradeoffs

### 5.1 Rejected: RAT/Freelist checkpoint alone, without generalising the fanout (§2.2)

Tempting because it is exactly Part 61's own scope and roughly half this document's
storage cost. **Rejected as unsafe**: §2.2 shows that reclaiming `robId_i+1..old_tail`
immediately (which is required for the mechanism to deliver any latency win at all —
see the reasoning in §2.2's second bullet) is unsafe unless the IQ (and whichever of
SQ/DTLB/ITLB/FetchAlign turn out to matter) stop squashing flatly and start squashing
by age. A narrower variant that restores RAT/Freelist state and redirects fetch
**without** truncating the ROB tail (leaving old wrong-path entries physically
resident until they naturally retire and get discarded by today's existing mechanism)
was considered and is **not viable**: new, correct-path instructions still need real
ROB space to rename into, and without a tail truncation they would have to enqueue
*behind* the still-resident wrong-path entries in robId order, which serialises behind
exactly the retire-latency this mechanism exists to remove. There is no partial
version of this fix that avoids §2.2's fanout work.

### 5.2 Bounded-N vs. a full per-robId (N=64) scheme

A full N=depth(=64) scheme would give exact coverage of the theoretical worst case
(every ROB entry is a branch) at roughly 32× this document's N=2 storage cost (~6,400
FF just for checkpoint storage) plus a much wider N-way mux/allocator. Given (a)
`branchy`'s own measured branch density (Part 61 §3: 0.508 IPC, backend idle 58.7% of
the window, from a kernel with only 5-6 macro-ops/iteration — real workloads do not
sustain anywhere near one branch per ROB slot) and (b) the graceful degrade-to-today's-
mechanism fallback (§3.3) meaning a smaller N never produces an incorrect result, only
a missed opportunity — **a small, bounded N (4-8) is the recommended target**, not a
full per-robId scheme. This mirrors how real-world OoO designs typically size branch
checkpoint structures (a small, fixed pool with dispatch-side backpressure), and this
design's fallback-instead-of-stall choice (§3.3) is actually a cheaper degrade path
than the textbook stall-on-exhaustion, at the cost of not guaranteeing every branch
gets the fast path.

### 5.3 Recommended incremental first slice: N=2

Directly targets the concrete pattern Part 29 observed (two branches in flight,
`robId=16` and `robId=18`) without committing to the full fanout generalisation's
final shape up front. An N=2 slice still requires §3.5's IQ/SQ generalisation in full
(the correctness requirement does not shrink with N — only the checkpoint bank's own
storage/mux cost does) — so N=2 does **not** avoid the "multi-day" part of this
project, but it does let S3-S5 (§6) be built, tested, and synth-gated against the
smallest possible checkpoint bank, deferring the "is N=4 or N=8 worth the extra
storage" decision to a follow-up slice once the fanout machinery (the actual expensive
part) already exists and works.

### 5.4 Why this is worth building independent of the specific boot bug

Part 61's own `IpcBenchSpec` baseline (zero-latency memory model, seed 860374149),
reproduced here as the standing engineering justification since §0 explicitly warns
against justifying this purely on the boot bug:

| kernel | IPC | dual%(active) | active% | note |
|---|---|---|---|---|
| independent-ALU | 2.000 | 100.0% | 100.0% | superscalar ceiling, no branches |
| dependent-ALU | 1.002 | 0.2% | 100.0% | latency-bound, no branches |
| hot-loop | 1.283 | 33.8% | 95.9% | tight back-edge, BTB-predicted after warmup |
| branchy | 0.508 | 23.0% | 41.3% | data-dependent alternating Bcc, genuine commit-time redirect every taken branch |

`branchy`'s backend sits idle 58.7% of the window even with a SHORT body (small
ROB backlog ahead of each branch at resolution time) — a real workload with a deeper
backlog ahead of a mispredicting branch pays strictly more. This is real, measured,
core-wide evidence independent of the boot investigation.

---

## 6. Staged implementation plan

Each task is independently buildable, states what it decides, and ends in a synth gate
per the standing rule (every slice/dispatched agent ends with a full-core OOC synth
gate). No task in this plan is authorised to run yet — this document is scope only.

| # | Task | Decides | Gate |
|---|---|---|---|
| **S0** | FMax rebaseline on current HEAD before any RTL, per this project's own standing FMax-resilience lesson (numbers go stale fast — see the FTB-prefetch design's T0 for the identical rationale). | The baseline every later slice's synth gate is measured against. | `synth/impl_FullCore.tcl` sign-off recipe, archive WNS/TNS/failing-endpoint count + netlist md5. |
| **S1** | **RatTable: add `checkpointSave`/`checkpointRestore` to a new N-slot checkpoint bank** (§3.1), mirroring `Ras.scala`'s port shape exactly (idle-defaulted directionless wires, `allowOverride`, sim asserts against same-cycle save+restore). N=2 to start (§5.3). Unit-test in isolation (`RatTableSpec`-style) before touching `RenameStage`. | The RAT half works standalone. | New `RatTableCheckpointSpec`; existing `RenameStage*Spec` suite green (no wiring yet, so should be a no-op diff for those). |
| **S2** | **Freelist: add `checkpointSave`/`checkpointRestore`** (§3.2 — pointer-only, cheaper than S1). Unit-test the derived-`count` arithmetic explicitly (this is the one piece of new arithmetic in this half, everything else is register copies). | The Freelist half works standalone, including the wraparound-derived-count case. | New `FreelistCheckpointSpec`, including a directed wraparound test. |
| **S3** | **Read DTLB/ITLB/FetchAlign/StoreQueue's actual current flush wiring, line by line** (this document did not — §3.5/§2.2 explicitly flag this as unread) and the IssueQueue's flush sites in full detail; design the exact per-consumer age-selective squash predicate for each; **resolve the robId-reuse/stale-completion question §2.2 raises** (does today's "alloc-reset wins" same-cycle priority actually cover a multi-cycle-separated early flush, or does it need a generation/epoch tag added to completion ports?) with either a proof or a new mechanism. This is the task most likely to change this document's own N/cost estimates. | Whether §2.2's fanout generalisation is as mechanical as §2.3's optimistic reading suggests, or needs new plumbing in more places than IQ/SQ. | Investigation only — no RTL gate; produces a follow-up design note or an amendment to this document. |
| **S4** | **ROB: generalise `tail := head` to `tail := robId_i + 1`** (§3.4) gated on `hasCheckpoint`, plus the exception/debug-recovery priority arbitration §3.7 flags. Re-run the precise-drain/debug-recovery directed corpus (commits `efcd953`/`77ce9bf`/`683355d`'s own regression tests) in addition to Part 61's mispredict-flush corpus (`mispredict`/`deep_mispredict`/`adv_store_squash_mispredict`/`adv_a7_spec_flush`/`adv_flush_restart_store`/`unstable_branch`). | Whether the narrow tail-truncation itself is safe once S3's fanout work lands. | `make test-fast`; the two directed corpora above; full-core OOC synth gate. |
| **S5** | **Wire it all together**: `RenameStage` checkpoint allocation (§3.3) including the graceful-fallback path, `BranchEuPlugin`/`BranchCompletion` checkpoint-id threading (§3.4), checkpoint reclamation (§3.6). This is the integration task — every prior slice was buildable/testable in isolation; this one is not. | End-to-end correctness of the N=2 mechanism. | Full directed corpus + ExecuteLockStepSpec + `IpcBenchSpec` (compare `branchy`/`hot-loop` IPC against §5.4's baseline — must improve, not merely hold even); full-core OOC synth gate against S0's baseline (§4's cost estimate gets its real number here). |
| **S6** | **Real-hardware trial against the specific boot bug**, per §0's own caution: build, deploy, and observe whether `$0DB0` transitions on a fresh boot. Per Part 62, a negative result here is **not** evidence the mechanism is broken — it would corroborate Part 62's "modulator, not sole cause" verdict and motivate picking up the `$0D24`/csCode-21 thread (Part 44) instead/next. A positive result is strong, but Part 62's own reasoning means it should be read as "the odds changed," and reproduced across multiple real cold boots (Part 33's own shim precedent needed 5 independent trials to characterise a 40% escape rate) before being called a fix. | Whether this mechanism, once built, actually moves the specific boot bug — the question this whole investigation started from. | Real JTAG/ILA capture per this document's own established discipline (`tools/jtag_lease.sh`, `break-pc`/`halt-status`, multiple independent cold-boot trials, not one). |

**N=4/N=8 sizing** (§5.2) is deliberately deferred past S5: build N=2 first, get a real
synth-gated cost number for the checkpoint bank alone at S5, and decide whether to grow
N as a cheap follow-up slice once the (much larger, per §4) fanout-generalisation cost
from S3 is also known.

---

## 7. Summary for a go/no-go decision

**Mechanism**: N bounded checkpoint slots (start N=2), each holding a flat RAT mapping
snapshot (~164 bits) + Freelist head pointers (~22 bits, pointer-only — cheaper than
the RAS precedent because the Freelist's RAM is only ever written by already-committed
values), allocated at a branch's own rename and restored at that branch's own
misprediction resolution, with **graceful fallback to today's correct, re-verified
retire-gated mechanism whenever no checkpoint slot is free** — this fix is additive,
never a correctness regression, and never blocks dispatch on checkpoint exhaustion.

**The hard part is not the checkpoint storage** (a genuinely small, RAS-precedented
addition, ~400 FF at N=2). **The hard part, found while researching this document and
not scoped by Part 61, is that every OTHER current consumer of the single global
`flushing` pulse (the IssueQueue for certain, likely the Store Queue/DTLB/ITLB/
FetchAlign) currently squashes "everything I'm holding" rather than "everything younger
than a given robId," because today's flush is always head-anchored and those two
statements have always been equivalent.** Making them independent requires converting
each of those consumers to an age-selective squash, reusing an age-compare primitive
that already exists and is already hardened in this codebase (`StoreQueue.scala`'s ROB
age-compare, born from a real bug fix) — mechanical in shape, but real, multi-file work,
and it is very likely the larger of the two LUT costs, not the checkpoint bank itself.

**Estimated real cost**: an FF floor of ~400 FF (N=2) to ~1,600 FF (N=8) for checkpoint
storage, plus an honestly-unquantified LUT cost for the checkpoint bank's own
select/restore/reclaim logic (order a few hundred to ~1-2k LUT) **and** a separately
unquantified — likely larger — LUT cost for generalising the IQ/SQ/(possibly DTLB/
ITLB/FetchAlign) flush logic to be age-selective, which cannot be sized without first
reading those plugins in the detail S3 scopes. No synth-verified number exists; none
should be quoted until S4/S5's actual OOC gates run.

**On the boot bug specifically**: do not greenlight this purely to fix
`$0DB0`. Part 62's own analysis shows the collision this mechanism targets is a
*modulator* of boot success odds, not the sole blocker — a separate, already-confirmed
`$0D24`/csCode-21 hardware-timing race (Part 44) sits one `bsr` downstream and is
independently real. This mechanism is a genuine, real, measured (Part 61 §3 / §5.4
above) core-wide IPC investment that is worth building on its own engineering merits;
treat any boot-bug improvement it produces as a welcome side effect to verify at S6,
not the primary justification.
