# EU shared-state identity audit — hunting the `remLatch` anti-pattern core-wide

**Date:** 2026-09-03
**Scope:** read-only structural audit. **No RTL was changed by this audit.**
**Baseline commit:** `a5199bc` (branch `fmax-closure-fanout`). Every line citation
below is against `git show a5199bc:<path>`. A concurrent agent has
`DivEuPlugin.scala` / `MicroOpAssembler.scala` / `IssueQueuePlugin.scala` open in
the working tree; line numbers there may have drifted.

---

## 0. TL;DR

* **12 execution units / result paths audited.** **One** is genuinely exposed:
  `DivEuPlugin`'s `remLatch`/`ovLatch` — the already-known case
  (`BUG_calibration_word_misplaced_0d00.md` Part 116). This audit found **no
  second instance of the same defect**.
* **The multiplier is CLEAN, and the negative is rigorous.** MUL's 64-bit high
  half — the one structure in this core with exactly the divider's
  two-results-one-µop shape — is stored in a **robId-keyed** `Mem` with a
  **per-robId valid bit** that is **flush-cleared**, and the MULHI crack tail is
  gated on *its own* robId's valid bit
  (`DivEuPlugin.scala:881-885`, `:1419-1441`, `:1448`). An early-issued MULHI
  cannot read another multiply's high half; it simply waits. **The multiplier is
  the design the divider should have been**, and it lives 100 lines below it in
  the same file.
* **The most important new finding is not in the RTL, it is in the test
  harness.** `WhiteboxCapture.scala:69` —
  `val emit = (!isTempOnly && !wb.divRem) || wb.keepCommit` — combined with
  `DivEuPlugin.scala:1503-1504` (`wbObs.divRem := compDivRem`,
  `wbObs.keepCommit := False` hardcoded) means **the DIVREM and MULHI crack
  tails emit no commit record at all**. Lock-step and fuzz therefore **never
  compare `Dr` after a long divide, nor `Dh` after a 64-bit multiply**. That is
  a complete, mechanical explanation of why `fastTest`, the ported corpus,
  lock-step and a 200-seed fuzz campaign all missed a bug that real hardware
  found in one boot. **It also means the MULHI path, though structurally sound,
  is entirely unverified end-to-end.**
* **Fuzz re-triage: 0 of 57 divergences carry the divider/multiplier
  fingerprint** — and, per the point above, **they structurally could not**. The
  attribution of the 57 to the wild-PC/A7 class is *not* disturbed by the Part
  116 finding. Detail in §4.

---

## 1. The anti-pattern, stated as a test

A state element is **exposed** if all three hold:

1. it holds a *result*, *result fragment*, *operand* or *intermediate* across
   cycles; **and**
2. it is **plugin-global** — shared by every operation that flows through the
   unit — rather than per-operation; **and**
3. it lacks **all** of: an instruction-identity tag (robId / physreg), a reset
   or `init` value, and a clear-on-flush.

And, decisively, the "safe by construction" escape hatch is only valid when the
enforcing mechanism is **local and inspectable**. The divider's failure was
precisely that its stated invariant
(`DivEuPlugin.scala:683-686`: *"The DIVREM is the next CPLX µop in age order
(single-outstanding), so the latch is valid"*) depended on a **different
plugin's** arbitration policy (`IssueQueuePlugin.scala:498`,
`ohC = OHMasking.first(cplxReady)` — oldest **ready**, no barrier), and the
compensating barrier had been **deliberately narrowed** to memory-divisor forms
(`MicroOpAssembler.scala:2826`, `divremUop.srcBValid := divlDivisorIsMem`).

I therefore grade every finding on **who enforces the guarantee**:

* **Tier A — provably safe**: identity-tagged, or no cross-cycle shared state at
  all, or provably dead.
* **Tier B — safe by a local, single-file structural invariant** (a busy bit or
  a FIFO order the same plugin owns and can be read in one sitting).
* **Tier C — safe only by a cross-plugin or cross-layer assumption** — the
  divider's category. Document or remove.
* **Tier D — genuinely exposed.**

---

## 2. Unit-by-unit findings

### 2.1 `DivEuPlugin` — divide result path — **Tier D, EXPOSED (known)**

`DivEuPlugin.scala:778-779`:

```scala
val remLatch = Reg(Bits(32 bits))     // no init, no robId tag, no flush clear
val ovLatch  = RegInit(False)         //    init only; no tag, no flush clear
```

Written by the DIV FSM's `DIVING` completion (`:1364-1365`), read by the
trailing DIVREM µop (`:1337`). Neither is in the flush block at `:1446-1449`
(which clears `legacyValid`, `compValid`, `mulHiValid` — but not these two).

Two independent un-interlocked paths, both already documented in Part 116 §8:

* **Issue-order race.** The legacy lane's admission gate is
  `!busy && !s1Valid` (`:403-407`), so DIVREM cannot enter S1 *during* a divide.
  The reachable race is therefore DIVREM issuing **before its own DIV** — which
  `IssueQueuePlugin.scala:498` permits for any DIVREM whose sources are ready
  before the DIV's, and which the barrier at `MicroOpAssembler.scala:2826`
  covers only for memory divisors. A register-divisor `DIVSL.L` whose dividend
  or divisor comes from a slow producer is unprotected. This is the ROM's shape.
* **Flush poisoning.** A wrong-path DIV in flight when a flush lands still runs
  `DIVING` to completion and still executes `:1364-1365`; only its *completion*
  is suppressed (`compFlushed`, `:1455`). It leaves a poisoned `remLatch` for a
  later correct-path DIVREM. The DIV FSM arm at `:1339-1349` has **no
  `!flushSig` guard**, unlike every sibling arm (CHK/CMP2/FPCTRLRD/FPSTORECVT/
  DIVREM), which all take an explicit capture-and-discard path.

Failure shape: **quotient always right, remainder intermittently belonging to a
different divide** — including a wrong *sign*, which is what turned a ±2
remainder into a ±4 pointer error in the ROM. Not fixed here; Part 116 §10 owns
the fix proposal.

### 2.2 The multiplier (`MUL` / `MULHI`, same plugin) — **Tier A, CLEAN**

This is the highest-priority suspect on shape alone: a 32×32→64 product has two
halves and is cracked into `MUL` (→ Dl) + `MULHI` (→ Dh), exactly the
quotient/remainder topology. **It is not exposed, and the reason is structural,
not incidental.**

```
DivEuPlugin.scala:881   val mulHiMem   = Mem(Bits(32 bits), 64)   // KEYED BY robId
DivEuPlugin.scala:882   val mulHiValid = Reg(Bits(64 bits)) init 0 // per-robId valid
DivEuPlugin.scala:884   val mulHiHeadReady = mulHiPendingQ.io.pop.valid && mulHiValid(mulHiHead.robId)
DivEuPlugin.scala:885   val mulHiData      = mulHiMem.readAsync(mulHiHead.robId)
```

All three identity properties are present:

* **Tagged.** The high half is stashed at `tailRobId = mulRobId + 1`
  (`:1439-1441`) and read back at the MULHI's **own** robId (`:885`). A MULHI
  that reaches the EU before its producer is pushed into `mulHiPendingQ`
  (`:812-815`) — it does **not** occupy `s1` — and simply blocks on
  `mulHiValid(its robId)`. The divider's race has no analogue here: the
  equivalent of "read the previous divide's remainder" is impossible because the
  read is address-checked, not order-checked.
* **Flush-cleared.** `mulHiValid := 0` (`:1448`), `mulResultQ.io.flush := flushSig`
  and `mulHiPendingQ.io.flush := flushSig` (`:801-802`), `mulReserved := 0`
  (`:818`), `mulCtxValid.foreach(_ := False)` (`:845`). The stash write at
  `:1439-1441` sits inside `when(!flushSig)` (`:1417`) and the clear is written
  *later* in the same clocked block, so a same-cycle flush wins under SpinalHDL
  last-write-wins. A wrong-path MUL cannot leave a stash behind.
* **Reset.** `init 0` on `mulHiValid`; `mulHiMem` contents are unreadable until
  the matching valid bit is set.

The MUL descriptor pipe is likewise per-operation: `mulCtx` is a shadow pipe of
depth `MulCore.Latency` carrying robId/pdst/dstArch (`:829-846`), and `MulCore`
itself (`MulCore.scala:53-60`) is a plain 7-stage DSP pipeline with a `valid`
shift register — every stage holds exactly one operation's data.

**Two residual notes, neither a bug:**

1. The correctness of `tailRobId = mulRobId + 1` rests on MUL and MULHI being
   **ROB-adjacent**. They are: the crack emits `mullUop` then `mulhiUop`
   back-to-back (`MicroOpAssembler.scala:2926`, `:2939+`), and a memory
   multiplier only adds a *leading* load. Should adjacency ever break, the
   failure mode is a MULHI that never becomes ready — a **loud ROB deadlock**,
   not a silent wrong `Dh`. That is the right failure mode and worth preserving
   deliberately; an assertion on it would be cheap.
2. `MicroOpAssembler.scala:2979` still carries MULHI's *ordering barrier*
   (`mulhiUop.srcAValid := mullMulIsMem`), gated exactly as DIVREM's is, and its
   30-line comment still refers to a `mulHiLatch` that **no longer exists** —
   the path was since rebuilt as the robId-keyed stash above. The barrier is now
   vestigial belt-and-braces. Harmless, but the comment is actively misleading
   to anyone auditing this file after Part 116 and should be corrected.

### 2.3 `AluEuPlugin` (including the fast/slow split) — **Tier A, CLEAN**

`grep '= Reg('` over `AluEuPlugin.scala` returns **zero hits**. Every one of its
~55 state elements is a `RegNext` pipeline stage (`:162-173`, `:583-586`,
`:666-715`, `:737-771`, `:836-853`), and each stage carries its own
`s*Ctx : IqContext` — i.e. robId and the full µop travel *with* the data through
every stage of both the fast (S1→S3) and slow (S1→S1a→S1a2→S1b→S2→S3) paths.
There is no plugin-global holding register of any kind, so criterion (2) of the
anti-pattern fails outright. Flush is handled by gating each stage's valid
(`!flushPort`, `:583`, `:737`, `:747`, `:753`, `:764`).

### 2.4 `Shifter` — **Tier A, CLEAN**

`Shifter.scala` instantiates **no registers at all**. Its `ShiftS1Mid` /
`ShiftS1aMid` bundles (`:34`, `:76`) are *payload type definitions*; the actual
flops are `AluEuPlugin`'s own `RegNext` stages (`s1aStage1a`, `s1bStage1`,
`s2Stage1`). Purely combinational, therefore no cross-cycle state to share.

### 2.5 `Bitfield` — **Tier A, CLEAN**

`Bitfield.scala` contains no `Reg` of any kind. Same argument as the shifter:
the multi-cycle bitfield path is realised entirely as `AluEuPlugin` `RegNext`
stages (`:666-715`), each carrying `s*Ctx`.

### 2.6 `AluDatapath` — **Tier A, CLEAN**

No registers (`AluDatapath.scala`, zero `Reg` occurrences). Combinational.

### 2.7 `BranchEuPlugin` — **Tier A, CLEAN**

Zero bare `Reg(`. All state is `RegNext` (`:138-147`, `:337-342`) carrying
`s1Ctx`. Single-cycle EU; no result fragment survives an operation.

### 2.8 FPU arithmetic pipes (`FpAddPipe`, `FpMulPipe`, `FpCheapPipe`, `FpRoundPack`, `FpNarrowPack`) — **Tier A, CLEAN**

All five are strict `RegNext` stage pipes; every per-operation attribute,
**including the rounding mode**, is latched at issue and *travels with the
request* rather than being read globally at completion — see the explicit
comments at `FpAddPipe.scala:82`, `FpMulPipe.scala:49`,
`FpCheapPipe.scala:116`. That is the correct discipline and it is applied
consistently. `FpMulPipe`'s `opA0..prd3` (`:91-95`) are DSP pipeline stages
paired with a `ctxValid` shift register (`:101`), same shape as `MulCore`.

### 2.9 FPU iterative lane (`FpDivSqrtCore` + `DivEuPlugin`'s FP wrapper) — **Tier B, safe by a local busy bit**

`FpDivSqrtCore.scala:82-102` is a single-context iterative FSM: every operand,
exponent, rounding mode and the result register `resReg` are latched at accept
and are `init`-ed. It is deliberately ROB-unaware and holds exactly one
operation.

The enforcing invariant is **local and explicit**: `fpIterBusy`
(`DivEuPlugin.scala:389`) is set on the accept cycle (`:1096`) and *is itself*
the issue-port admission gate (`:407`, `Mux(issueIsFpIter, !fpIterBusy, ...)`).
The comment at `:1085-1094` states exactly why the set must be at accept and not
at FS1. Flush uses a **poison** bit (`fpIterFlushed`, `:1084`, `:1100`) rather
than clearing busy, because the core's `done` is a held level that must still be
acked or the engine wedges — a genuinely correct treatment, documented at
`:1076-1083`. Result delivery is gated `fpIterDone && !fpIterFlushed` (`:1199`).

**This is Tier B not Tier A only because the guarantee is a busy bit rather than
a tag** — but the bit is owned, set, and consumed inside one plugin, and no
other plugin's arbitration can defeat it. Contrast the divider, whose invariant
was a *sentence about another plugin's policy*.

Also: the FP iterative lane produces **one** result. It has no second-result
shape at all, so even a broken invariant could not produce the Part 116
fingerprint.

### 2.10 `FpuControlPlugin` — **Tier B / one item to document**

* `fpcr` / `fpsr` / `fpiar` (`:125-127`) are **architectural registers**, not
  per-op state; written only through `setFpcr`/`setFpsr`/`setFpiar`, which are
  driven from `ExceptionUnit`'s serializing `SysKind.FMOVE_FPCTRL` arm at a
  commit point with the EU flushed. Correct by the serialization argument
  restated at `:151-155`.
* `orFpsrExc` (`:157-160`) OR-accumulates EXC/AEXC bits from a **speculative**
  EU completion. `DivEuPlugin.scala:1240-1244` gates its own contribution on
  `!flushSig`, but a wrong-path FP op that *completes before* the flush arrives
  still accrues. This is a **different** class from the Part 116 bug (sticky
  architectural pollution, not a mis-associated result) and is bounded — the
  bits are sticky-OR, so the damage is a spuriously-set FPSR accrued-exception
  bit, never a wrong arithmetic value. **Flagged for documentation, not
  claimed as a bug**: I did not evidence a reachable case where an FPSR bit is
  set that a real 68040 would leave clear.
* `uiValid` / `uiCmdReg1B` / `uiSrcOperand` / `uiDstOperand` (`:174-177`) are a
  genuine plugin-global **operand** hold with no identity tag — 176 bits of
  source/destination operand latched at vector-11 delivery and read back by an
  arbitrarily-later `FSAVE`. It satisfies criteria (1) and (2). It is **Tier B**
  because both producer and consumer are states of the *same* serializing
  `ExceptionUnit` FSM, which the comment at `:194-197` states. Worth keeping in
  mind that this is the one FP structure whose safety rests on a stated FSM
  property rather than a tag.

### 2.11 LS / AGU path (`LsEuPlugin`, `StoreQueue`) — **Tier A + Tier B, no exposure**

Four candidate structures, all resolved:

* **`lineA` / `aDone` (`LsEuPlugin.scala:1296-1297`) — Tier A, PROVABLY DEAD.**
  This *is* the exact anti-pattern shape (a global 128-bit half-result holder for
  a cross-line split load, untagged, no init). But it is written only inside
  `bkFsm`'s `WAIT_A` arm (`:1961-1962`), and `bkFsm` is unreachable:
  `bkStart` is assigned `False` at `:805` and **has no other driver** (verified
  by exhaustive grep), which the comment at `:842-846` states outright
  (*"nothing drives `bkStart` any more"*). `bkBusy` is therefore a synthesis
  constant. Dead, not merely unlikely. **Recommend deleting it** — a dormant
  copy of the exact bug we are hunting is a trap for the next auditor.
* **`splitMergeLine` (`LsEuPlugin.scala:1892`) — Tier B, the live split-merge
  holder.** Also untagged and un-init-ed, written on a split slot-A response
  (`:1894-1896`) and consumed on slot B (`:1904-1908`). Its safety argument is
  stated at `:1880-1891` and each leg is verifiable in-file:
  * a split pair is pushed **atomically, contiguously, in one cycle**
    (`:1642-1667`, `alignedPushPtr += 2`);
  * the ring sends strictly in order (`alignedSendPtr + 1`, `:1614-1617`) and
    retires responses strictly in order (`alignedRspPtr + 1/+2`, `:1600-1612`);
  * `alignedSendHeld` (`:938-939`) blocks slot B's send until slot A has
    resolved;
  * a faulting slot A aborts the pair (`alignedRspAbortsPair`) so B is never
    sent.
  Together these mean at most one pair can occupy the "A resolved, B pending"
  window. **The one assumption this rests on that is not visible in
  `LsEuPlugin.scala` is that `dcache.loadRsp` returns responses in send
  order.** That is the single thing to document (or assert) here; it is the only
  place in the LS path where the reasoning leaves the file, and therefore the
  only place with the divider's *shape* of risk. I did **not** find evidence
  that the D-cache reorders — I am flagging it as unverified by this audit, not
  as broken.
* **`pendMem` / `pendFault` / `pendFaultPayload` (`:1734-1737`) — Tier A.** A
  ring of per-store records, each carrying its own `robId` (`:1754`). Identity
  travels with the payload.
* **`loadBusyReg` (`:2292`) and `excStoreOutstanding` (`:298`) — Tier B.**
  Single occupancy bits, not result holders. `:2276-2291` states the "no robId
  tag needed" argument (an inhibited load only launches once it is the ROB head,
  so the next terminal response is necessarily its own). This is an *ordering*
  claim — Tier C in shape — but it governs **bus occupancy, not result data**, so
  the worst reachable failure is a control/timing bug, never a wrong register
  value. Out of scope for this pattern; noted for completeness.
* `StoreQueue.scala:243-267` is a pointer ring of per-store entries; no shared
  result latch.

### 2.12 `IssueQueuePlugin` port structures and scoreboards — **Tier A, CLEAN**

* Per-slot state (`:110-160`) is exactly that — per slot.
* The wakeup scoreboards `lsBusy` / `cplxBusy` / `aluSlow*Busy` / `cplxFp*Busy`
  (`:319-356`) are **indexed by physical register**, i.e. tagged by the rename
  identity, and **all nine are flush-cleared** (`:1437-1445`). This closes the
  historical "scoreboard leak" class (a wrong-path op claiming a resource and
  never releasing it) for this plugin.
* `physToSlot` (`:253`) is physreg-indexed. `coldWay0/1` (`:228-229`) are
  robId-addressed Mems.
* The registered `selPorts → issuePorts` m2sPipe stage carries the whole
  `IqContext` (robId + µop) as payload, so no identity is dropped at the cut.

**The one structural fact worth restating**, because it is the enabler of the
Part 116 bug rather than a bug itself: `ohC = OHMasking.first(cplxReady)`
(`:498`) selects the oldest **ready** CPLX slot, whereas the LS port two lines
above (`:495-497`) uses `ohLoldest = OHMasking.first(lsPresent)` then
`& lsReady` — a genuine older-blocks-younger barrier. Giving the CPLX port the
LS port's discipline is Part 116 §10's option (2).

### 2.13 Shared completion / CDB plumbing — **Tier A, CLEAN**

`DivEuPlugin`'s three-source arbiter (`:1391-1449`) is atomic by construction:
`legacyResult` and both FIFOs hold their payloads until selected, so a
same-cycle DIV/MUL or MUL/MULHI collision cannot lose a result on the Flow-only
ROB lane. Every arbitrated payload is a full `CplxResult` carrying its own
`robId`/`pdst`/`dstArch` (`:1400-1415`). The registered `comp*` stage is
per-completion and the ports are gated by
`compLive = compValid && !compFlushed && !flushSig` (`:1455`).

### 2.14 Adjacent state checked for completeness (not EUs)

* `RegFilePlugin.scala:87` — physreg-indexed `Mem`. Tagged by construction.
* `ItlbPlugin.scala:149-161` — a 1-entry translation latch, but **VPN-tagged**
  (`latchMatch = latchValid && (latchVpn === tlbKey(_req.vpn))`). Correct shape.
* `ExceptionUnit.scala:430-1078` (`cur*` / `sysCap*` / `pop*` / `fs*`) — a single
  serializing commit-point FSM handling one event at a time with the pipe
  drained. Tier B by the same argument as §2.10.
* `RobPlugin.scala:2044-2049` (`heldCcrFold`) — a single-entry hold of the
  faulting instruction's NZVC, consumed at exception-entry observation. It is
  **sim-only whitebox reconstruction state**, not architectural, and the fault→
  entry window is serialized. Negligible.

---

## 3. Risk ranking

| # | Structure | Tier | Real risk |
|---|---|---|---|
| 1 | `DivEuPlugin` `remLatch`/`ovLatch` (`:778-779`) | **D** | **Live, hardware-confirmed.** Wrong `Dr` after `DIVU.L`/`DIVS.L`/`DIVSL.L`/`DIVUL.L` with a register divisor; quotient always correct. Silent, intermittent, order-sensitive, survives clock-rate change. |
| 2 | `LsEuPlugin` `lineA`/`aDone` (`:1296-1297`) | A (dead) | Zero today. **Non-zero tomorrow**: it is a verbatim copy of the bug's shape sitting in dead code behind a `bkStart := False`. If anything ever re-drives `bkStart`, it ships the divider bug into the load path. Delete. |
| 3 | `LsEuPlugin` `splitMergeLine` (`:1892`) | B | Safe under an in-file FIFO invariant. Its one off-file dependency — D-cache response ordering — is undocumented and unasserted. Assert it. |
| 4 | `FpuControlPlugin` `uiSrcOperand`/`uiDstOperand` (`:176-177`) | B | 176 bits of untagged operand held indefinitely; safe only because producer and consumer are states of one serializing FSM. Correct today; fragile to any future attempt to overlap FSAVE with exception delivery. |
| 5 | `FpuControlPlugin` `orFpsrExc` speculative accrual | — | Different class (sticky-flag pollution, never a wrong value). Not evidenced as reachable. Documented, not claimed. |
| 6 | `MicroOpAssembler.scala:2979` MULHI barrier + its comment | — | Not a correctness risk. Its comment describes a `mulHiLatch` that no longer exists and will mislead the next auditor into thinking MUL shares the divider's defect. Correct the comment. |

Everything else audited is Tier A.

---

## 4. Fuzz re-triage: is any of the 57/200 the divider bug?

Corpus: `fuzz_logs/*.log`, 8 batches, 200 seeds, 2026-09-01. Confirmed
**57 DIVERGED / 143 PASS = 28.5%**, all `DIVERGED[STEP]`, 57 full reports.

**Breakdown of the divergence *signature*:**

| signature | count | shape |
|---|---:|---|
| `pc:` with `dut` inside `0x4080xxxx` (offset +2 / +4 / +6) | 40 | instruction-length / decode-step |
| `pc:` with `dut` a wild value (`0x4ad9b710`, `0x8c7c5dc4`, …) | 10 | wild-PC class |
| `a7:` `dut=0x000ffff8/c/e` vs `oracle=0x00100000` | 6 | the A7-minus-N class |
| `reg Dn:` | **1** | seed 80 |

**The single register divergence, seed 80, is not a divide.** Its minimized
culprit is `scs ([0x11,%a5],%d1.l*4,0x0)` — a memory-indirect EA `Scc`
(`fuzz_75_100.log:939+`); DUT writes `0x00` to D5's low byte where the oracle
leaves it. Unrelated.

**Instruction-level scan.** 16 of the 57 minimized programs contain a
`DIV*`/`MUL*`. Restricting to the forms that actually use a crack tail (only the
64-bit forms produce `DIVREM`/`MULHI`; `.W` packs the remainder into the same
32-bit result and never touches `remLatch` — `DivEuPlugin.scala:1360-1362`):

* long DIV (`DIV*.L Dq:Dr` / `DIVSL.L`): seeds **21, 33, 140** — 3 of 57.
* long MUL (`MUL*.L Dh:Dl`): seeds **10, 61, 90, 91, 95, 13** — 6 of 57.
* the rest are `.W` forms or 32/32 quotient-only forms with no crack tail.

All nine of those report a **PC** divergence, not a register divergence.

**Verdict: 0 of 57 (0%) carry the divider/multiplier fingerprint, and the
attribution of the population to the wild-PC/A7 class is undisturbed.**

**But the negative is weaker than it looks, and this is the important part.**
The fuzz/lock-step comparator is *structurally incapable* of reporting a wrong
`Dr` or `Dh` as a divergence at the offending instruction:

```
WhiteboxCapture.scala:69   val emit = (!isTempOnly && !wb.divRem) || wb.keepCommit
DivEuPlugin.scala:1503     wbObs.divRem     := compDivRem      // True for DIVREM *and* MULHI
DivEuPlugin.scala:1504     wbObs.keepCommit := False           // hardcoded
DivEuPlugin.scala:597      legacyResult.crackTail := u1.divIsRem || (u1.op === DecOp.MULHI)
DivEuPlugin.scala:1431     compDivRem := True                  // the MULHI arm
```

The DIVREM/MULHI writeback record is **dropped from the commit stream**, so no
`CommitObservation` is produced for it and the comparator never indexes `Dr` or
`Dh`. `WhiteboxCapture.scala:17-19` states the assumption verbatim:

> *"the Dr write still lands in the PRF and is verified by a later instruction
> that reads Dr"*

That is the **test-side twin of the RTL's false assumption**. It is only true if
a later instruction both reads `Dr` *and* surfaces it in its own commit record.
In randomly generated fuzz programs `Dr` frequently is never read again; when it
is, the divergence surfaces later and attributed to an unrelated instruction —
which is one plausible contributor to the wild-PC population, though I have **no
evidence** for that and am not claiming it.

**So the honest statement is:** the fuzz corpus supplies **no positive evidence**
of the divider bug, and could not have, and it also supplies **no evidence
against** it. The reclassification opportunity the brief hoped for is not there;
what is there instead is a far more actionable finding — a verification blind
spot covering **every second result this core produces**.

---

## 5. Stress-test design (specified, NOT run)

### 5.1 Why every existing gate missed it — mechanically

| gate | why it missed |
|---|---|
| `make test-fast` / `fastTest` | Excludes `ExecuteLockStepSpec`. Separately shown unable to detect a flush-related hang class a dedicated campaign caught immediately, so its negative result carries little weight for flush-shaped defects generally. |
| Ported corpus | The `divl_*` tests each execute a divide in isolation with no concurrent CPLX traffic and no slow producer feeding `Dq`. The race needs DIVREM ready-before-DIV, which needs a *late* dividend/divisor. Structurally out of reach. |
| Lock-step (`ExecuteLockStepSpec`) | **`Dr` is never compared** (`WhiteboxCapture.scala:69`). Even a 100%-reproducible wrong remainder is invisible at the divide. |
| Fuzz (200 seeds) | Same blind spot, plus the generator rarely emits [slow producer → long DIV] adjacency. |
| Unit specs (`DivWSpec`, `CplxMulPipelineSpec`) | `.W` only / MUL-pipeline-shape only; neither drives DIVREM against a stale latch. |

**Corollary that must be fixed first: no test in this repo, at any level, ever
checks the high half of a 64-bit multiply either.** The MULHI path is
structurally sound (§2.2) but *empirically unverified*.

### 5.2 The blocking prerequisite

**Before writing any new test, make the second result observable.** Two options:

* **(a) Preferred — emit the crack tail as a commit record.** Give
  `WhiteboxCapture` a third category between "emit as its own oracle step" and
  "drop": a **merge** record that folds the crack tail's `dstArch`/`result` into
  the *preceding* kept step's observation, so the comparator checks both `Dq`
  and `Dr` (and both `Dl` and `Dh`) against the single oracle instruction. This
  keeps the 2-µop→1-oracle-step alignment that `divRem` exists to preserve while
  removing the blind spot.
* **(b) Cheaper stopgap.** A directed spec that reads the whitebox PRF/`peekWb`
  for the DIVREM's robId directly (`WhiteboxCapture.Handle.peekWb` already
  exists, `:56`) and asserts it against a Scala-computed remainder.

Any stress test written before (a) or (b) lands will pass for the wrong reason.

### 5.3 Test shapes, and what each targets

**S1 — late-operand DIVREM race (the direct Part 116 reproducer).**
```
move.l  (%a0),%d2          ; slow producer -> the DIVISOR (register form!)
divsl.l %d2,%d6:%d5        ; DIV not ready; DIVREM's srcA (old d6) IS ready
```
Loop it with `%d6` pre-seeded to a distinctive sentinel and a *different*
preceding divide that leaves a known `remLatch` value. Assert `%d6 == d5_before
mod d2` every iteration. Vary the producer's latency (L1 hit / L1 miss / MMIO)
to sweep the ready-time delta. **Existing tests cannot express this**: it needs
a slow producer feeding a *register* divisor, which is precisely the case
`MicroOpAssembler.scala:2826` excludes from the barrier.

**S2 — identical-operand repeatability (the shape that proved the bug).**
Execute the *same* divide with the *same* operands N≥1000 times in a loop with
varying surrounding instruction mix, and assert **bit-identical results every
iteration**. This needs no oracle at all — self-consistency is the invariant,
which is what made `-42767/4 → r=-3` then `r=+1` so decisive on hardware. Apply
the same shape to `MUL*.L` (`Dh:Dl`), `FDIV`, `FSQRT`. Cheap, and it is the one
test that would have caught this in one run.

**S3 — back-to-back same-unit ops.** A dense stream of long divides with
distinct operand sets, `Dr`/`Dq` register allocation rotated so consecutive
macro-ops do **not** share destinations (defeating any accidental RAW
serialization that hides the race). Assert every `(q,r)` pair satisfies
`q*divisor + r == dividend` **and** matches the oracle. The invariant
`q*d + r == dividend` is what §7 of Part 116 used to prove the returned pair was
not a valid division result at all; it is worth asserting in the harness
permanently.

**S4 — interleaved different-unit CPLX traffic.** Long DIV interleaved with
`MUL.L`, `CHK`, `CMP2`, `FDIV`, `FMOVEM`, so the CPLX port's oldest-ready
selection has real freedom to reorder. Sweep the interleave depth. Targets
exactly the arbitration policy at `IssueQueuePlugin.scala:498`.

**S5 — flush during execution.** A mispredicted branch whose shadow contains a
long DIV, with a correct-path long DIV immediately after the join. Targets the
second Part 116 mechanism (wrong-path DIV completes `:1364-1365` and poisons the
latch for a correct-path DIVREM) and the missing `!flushSig` guard at
`:1339-1349`. Sweep the flush cycle across the divide's full 64-cycle window —
this is a *cycle-offset sweep*, not a single case. Repeat for `FDIV`/`FSQRT`
(exercises the `fpIterFlushed` poison path) and for split loads (exercises
`splitMergeLine` + `alignedPoisoned`).

**S6 — issue-order / ROB-depth sweep.** Re-run S1/S3/S4 with the ROB
artificially throttled to depths {4, 8, 16, 64} and with the IQ slot count
varied, which changes which ops are simultaneously ready and therefore the
selection order. Order-dependent defects are the ones that hide from a single
fixed configuration.

**S7 — a permanent structural assertion, not a test.** In simulation, assert on
every DIVREM completion that the `remLatch` it consumes was written by *its own*
producer. The cheapest form: add a sim-only `remLatchOwner : Reg(UInt(6 bits))`
written alongside `remLatch` at `:1364` and assert
`remLatchOwner + 1 === compRobId` in the DIVREM arm. This is a **detector**, not
a fix, and it would have converted every existing test in the repo into a test
for this bug. Analogous assertions belong on `splitMergeLine` (assert the
consuming descriptor is the pair-partner of the producing one) and on the MULHI
adjacency assumption (§2.2 note 1).

### 5.4 Ordering

1. Fix observability (§5.2a).
2. Land S7's assertions — they are near-free and retroactively arm the whole
   existing corpus.
3. S2 (self-consistency; no oracle needed; highest yield per line).
4. S1, S5 (the two evidenced mechanisms).
5. S3, S4, S6 (breadth).

None of this was run: a Vivado build, a hardware campaign and a JVM fix agent
were live, and the machine-budget rule (max 2 heavy JVMs, never during Vivado)
forbids adding one.

---

## 6. Explicitly NOT claimed

* **No new bug is claimed.** The only Tier-D finding is the already-documented
  Part 116 defect. Items 2-6 in §3 are hygiene, dead code, or documentation.
* **No RTL or test change was made by this audit.**
* The multiplier is reported **clean on structure**, and simultaneously
  **unverified on behaviour** — those are different statements and both are in
  §2.2 / §5.1 deliberately.
* The D-cache load-response ordering that `splitMergeLine` depends on was **not
  verified** by this audit. It is flagged as an undocumented dependency, not as
  a defect.
* The fuzz population is reported as **0% attributable** to this bug class *and*
  as **structurally unable to attribute it**. Neither is evidence that the class
  is absent from the corpus.

---

## 7. Single highest-priority recommendation

**Close the observability hole first (§5.2a), because it is what allowed a
silent wrong-answer bug to survive every gate this project has — and it is
currently hiding the multiply high half as well as the divide remainder.** Then
fix `remLatch` by copying the design that already exists 100 lines below it in
the same file: robId-keyed stash + per-robId valid + flush clear
(`DivEuPlugin.scala:881-885`, `:1439-1441`, `:1448`). That is Part 116 §10's
option (3), and the multiplier is the working precedent that it costs little and
needs no ordering barrier at all.
