# ALU EU slow-path de-serialization (SHIFT / BITFIELD, II 7 -> 1)

**Date**: 2026-08-09
**Initiative**: IPC-push ("push ipc as high as reasonably possible without
blowing up lut count or crashing fmax")
**Status**: RTL and simulation gate complete; paired post-route FMax/LUT and IQ
endpoint census pending on the serialized Vivado window. The benchmark kernels
(`shift-stream`, `shift-mixed`) and their before/after measurements are recorded
below.
**Source finding**: `.superpowers/sdd/progress-ipc-push-2026-08-09.md`,
"EU-wide audit COMPLETE: 14 blocks surveyed, 1 genuine new footgun found".
**Governing principle**: "throughput over latency" (same ledger). This lever is
the best-case shape of it — it costs **zero** extra latency.

---

## 1. Problem

`AluEuPlugin.scala:197`:

```scala
issuePort.ready := !((s1Valid && isSlow) || s1aValid || s1a2Valid || s1bValid || s2Valid || s3Valid)
```

`isSlow = (u1.op === DecOp.SHIFT) || (u1.op === DecOp.BITFIELD)` (`:174-180`).

The slow datapath S1 -> S1a -> S1a2 -> S1b -> S2 -> S3 is **already a real
6-stage pipeline with complete per-stage storage** for both op classes:

| stage | shift chain | bit-field chain | ctx/valid |
|---|---|---|---|
| S1  | `s1Stage1a` (`:565`) | `bfCmd` (`:628-644`) | `s1Valid`, `s1Ctx` (`:152-153`) |
| S1a | `s1aStage1a`, `s1aSrc1` (`:567-569`) | `s1aBfCmd`, `s1aBfStoreF1`, `s1aBfBitOff1`, `s1aBfInvSrc1`, `s1aBfT2` (`:649-653`) | `s1aValid`, `s1aCtx` |
| S1a2| `s1a2Stage1a`, `s1a2Src1` (`:721-723`) | `s1a2BfMid` + 4 (`:665-669`) | `s1a2Valid`, `s1a2Ctx` |
| S1b | `s1bStage1`, `s1bSrc1` (`:731-733`) | `s1bInvRes/InvSrc/BitOff/StoreForm/BfFunnelRes/BfN/BfZ` (`:692-698`) | `s1bValid`, `s1bCtx` |
| S2  | `s2Stage1`, `s2Src1` (`:737-739`) | `s2BfRes/BfN/BfZ` (`:740-742`) | `s2Valid`, `s2Ctx` |
| S3  | `s3ShiftRes/Nzvc/X`, `s3Src1` (`:749-752`) | `s3BfRes`, `s3BfNzvc` (`:753-754`) | `s3Valid`, `s3Ctx` |

Every stage register is an unconditional `RegNext` of the previous stage; the
valid chain is a pure shift register. **Nothing in the datapath is shared
across ops.** The structure is a pipeline that is artificially prevented from
holding more than one op.

Consequence: initiation interval **7 cycles per ALU port** for any SHIFT or
BITFIELD µop, and — because the gate is unconditional — the same EU accepts
**nothing at all**, fast ops included, for those 7 cycles.

### 1.1 Measured cost (new, this task)

Two new kernels were added to `src/test/scala/m68k040/bench/IpcBenchSpec.scala`
because **no kernel in the suite exercised this path at all** (the file says so
itself at line 83: "Inert for the shift-free IPC kernels"). Baseline on HEAD
`c65a598`, `IPC_SEED` pinned:

| kernel | IPC_MEM=zero | IPC_MEM=l2:5:70 | dual%(act) | active% |
|---|---|---|---|---|
| `independent-ALU` (reference) | **2.000** | 0.382 | 100.0% | 100.0% |
| `shift-stream` | **0.286** | **0.282** | 33.8% | 21.4% |
| `shift-mixed` | **0.646** | **0.631** | 61.0% | 40.2% |

Bit-identical across `IPC_SEED` ∈ {1,2,3} (both kernels are tight,
I-cache-resident, memory-traffic-free loops, so they are seed- and
memory-model-invariant by construction — a deliberately clean measurement).

* `shift-stream` (6 independent `lsl.l #1,%dN` + `subq` + `bne` = 8 macros):
  **1703 cycles / 60 iterations = 28.4 cycles per iteration.** Predicted from
  II=7: 3 slow ops per EU × 7 = 21, + one `aluSlowHandoff` bubble per slow
  handoff (3/EU) = 24, + loop/branch overhead ≈ 28. **The model and the
  measurement agree.**
* `shift-mixed` (4 shifts + 2 `bfextu` + 12 independent `add.l %d6,%aN` +
  `subq` + `bne` = 20 macros): 1250 cycles / 40 iterations = **31.25
  cycles/iteration**. The 12 independent fast ALU ops — which a 2-wide machine
  should absorb in ~6 cycles — buy almost nothing, because the two ALU EUs
  spend the window closed. `active%` = 40.2%: the backend retires nothing on
  60% of cycles.
* `shift-stream` runs at **1/7th** of `independent-ALU`'s IPC. That ratio is
  the II directly.

Real-world weight (from the audit, real evidence): a 16-bit word histogram over
the real Quadra 950 ROM gives register-form shifts 1.50% and bit-field ops
0.37% of all words (a lower bound, since extension/data words are included),
i.e. order **3-4% of real instructions**, concentrated in QuickDraw / blit /
bit-packing code — exactly what this core's deployment target runs.

---

## 2. Goals / non-goals

**Goals**

* G1. Take SHIFT/BITFIELD initiation interval from 7 to **1** per ALU port.
* G2. Stop a slow op from blocking that EU's **fast** ops (amplifier 1).
* G3. Add **zero** cycles of latency to any op, fast or slow.
* G4. Add **no** PRF write ports and **no** completion ports.
* G5. Preserve every existing correctness invariant, in particular the `wbKey`
  write-port merge proof (`:91-113`) and the P5.7 select->fire wakeup contract
  (`IssueQueuePlugin.scala:370-421`).
* G6. FMax-neutral (design WNS is currently held elsewhere; see §5) and
  LUT-neutral-to-negligible.
* G7. Remove false X-source dependencies from shift forms whose count cannot be
  zero, without changing any architectural flag result.

**Non-goals**

* N1. Structurally removing the static oldest->port0 / second-oldest->port1
  mapping (`IssueQueuePlugin.scala:313-314`). See §4.4 — the head-of-line
  effect is reduced ~7x by this lever but not eliminated. A dynamic port
  assignment is a separate lever with its own select-cone FMax cost.
* N2. Shortening the slow op's 6-cycle *latency*. Those stages exist for
  measured FMax reasons (FMax #3, FMax #4, task #123) and must not be merged.
* N3. Touching the D-cache, LS EU, or frontend.

---

## 3. Architecture

### 3.1 The only real hazard

With the pipe allowed to fill, "two slow ops contend for the slow write /
completion ports" is **vacuous**: two slow ops that entered S1 on different
cycles reach S3 on different cycles, by construction of the pure `RegNext`
valid chain. At most one slow op is at S3 in any cycle, exactly as today.

The genuine hazard is the *other* one the code documents (`:182-190`, `:91-113`,
`:771-774`, `:794-797`, `:807-861`): the **fast S1 writeback and the slow S3
writeback share one physical PRF write port per regfile** (merged by `wbKey`),
one `completionPort`, and the `Mux(s3Valid, slow, fast)` wbObs/ccrObs selects.
All three require:

> **INV-1**: `fastFire(T) && s3Valid(T)` is never true.

Derivation of the minimal gate:

```
fastFire(T)  = s1Valid(T) && !isSlow(T)
s1Valid(T)   = issuePort.fire(T-1)
s3Valid(T)   = s2Valid(T-1)
```

⇒ accepting a **fast** µop at cycle C is unsafe **iff `s2Valid(C)`**. Accepting
a **slow** µop is never unsafe. That is the entire requirement.

### 3.2 Change 1 — `AluEuPlugin` (the correctness gate)

Replace `:192-197` with:

```scala
// Slow-classification of the µop PRESENTED at the issue port. `issuePort.payload`
// is the IQ's registered issue-stage payload (a flop output), so this is one
// LUT level, the same depth as today's `isSlow` off `s1Ctx`.
val isSlowIn = (issuePort.payload.uop.op === DecOp.SHIFT) ||
               (issuePort.payload.uop.op === DecOp.BITFIELD)

// S3 RESERVATION. A slow op at S2 this cycle lands its S3 writeback NEXT cycle,
// which is exactly when a fast op accepted now would do its S1 writeback.
// Block the fast op; never block a slow op (the 6 stages are a real pipeline).
// `!issuePort.valid` advertises capacity when the IQ's non-collapsing issue
// register is empty; its retained payload is stale and must not block a SLOW load.
issuePort.ready := !flushPort && (!issuePort.valid || isSlowIn || !s2Valid)
```

`s1aValid`/`s1a2Valid`/`s1bValid`/`s3Valid` drop out of the `ready` expression
(they stay, they are the pipeline's own valid chain).

**Note vs. the audit's sketch**: the audit proposed "a ~6-flop S3-reservation
shift register per ALU EU". **No new flops are needed.** The `s1Valid ->
s1aValid -> s1a2Valid -> s1bValid -> s2Valid -> s3Valid` chain *is* that shift
register, already built and already correct. `s2Valid` is precisely "a slow op
will be at S3 in 1 cycle".

Also export the flush input and one look-ahead bit per ALU EU on `AluEuService`:

```scala
/** True iff a FAST µop selected by the IQ this cycle is guaranteed to be
  * ACCEPTED next cycle (i.e. no slow op will be at S3 then). Needed by the IQ
  * to preserve the P5.7 select->fire invariant; see the design doc §4.2. */
def fastAcceptNext: Bool   // = !s1bValid
def flush: Bool
```

(`s2Valid(C+1) == s1bValid(C)`.) `fastAcceptNext` is false during flush.

### 3.3 Change 2 — `IssueQueuePlugin` (the wakeup-contract gate)

The existing `aluSlowHandoff(k)` gate (`:420-425`) is **deleted** and replaced
by a candidate-mask term on the two ALU select ports. `IssueQueueService` owns a
plain `Vec[Bool]` input named `aluFastAcceptNext`; integration wiring connects
each element to the corresponding `AluEuService.fastAcceptNext`, preserving the
service-only plugin boundary:

```scala
// Per-slot slow-ALU classification, STORED at push time (see FMax note §5.2),
// shifted on compaction exactly like `lsWait`/`cplxWait`/`aluSlowWait`.
val aluSlowSlot = B(slots.map(_.isAluSlow))          // slotCount bits, flop outputs

// A port whose EU cannot accept a FAST µop next cycle considers only SLOW
// candidates this cycle.
val cand0 = aluReady & Mux(aluFastAcceptNext(0), B.getAllTrue, aluSlowSlot)
val cand1 = aluReady & Mux(aluFastAcceptNext(1), B.getAllTrue, aluSlowSlot)
val oh0   = OHMasking.first(cand0)
val oh1   = OHMasking.first(cand1 & ~oh0)
...
selPorts(0).valid := oh0.orR && !flushSignal      // no aluSlowHandoff term
selPorts(1).valid := oh1.orR && !flushSignal
```

Expressing the block as a **candidate mask** rather than a valid-kill is
deliberate and is what buys most of §4.4's head-of-line relief for free: when
port 0's EU cannot take a fast µop this cycle, port 0 picks the oldest *slow*
µop instead of idling, and the skipped fast µop immediately becomes eligible
for `oh1` (`cand1 & ~oh0`).

`AluEuService` also needs no other change: the slow-dependency tracking in the
IQ (`aluSlowIntBusy` / `aluSlowNzvcBusy` / `aluSlowXBusy`,
`IssueQueuePlugin.scala:637-658, 811-838`) is **already multi-in-flight safe by
construction** — they are per-physreg *bitmaps*, not counters, and
`aluSlowRemaining` (`:826-829`) already handles a consumer with several
in-flight slow operands ("With a deeper slow pipe the two producers' wakeups can
be several cycles apart"). Nothing there needs to change.

### 3.4 Change 3 — precise X-source dependencies (selected after measurement)

The first paired implementation measurement was intentionally checked against
the projection in §6.  The pipe itself reached II=1, but the core-level result
was only 1.51x/1.66x:

| kernel | baseline cycles / IPC | II1-only cycles / IPC | gain |
|---|---|---|---|
| `shift-stream` | 1703 / 0.286 | 1127 / 0.432 | 1.51x |
| `shift-mixed` | 1250 / 0.646 | 754 / 1.072 | 1.66x |

The directed EU test proves six consecutive accepts and six consecutive,
uniquely tagged completions, so accepting this shortfall as an EU-pipeline
limit would be false.  The remaining serialization is decoder-created:
The initial implementation set `readsX := !isRo` for every AS/LS/ROX form.
That makes the six independent immediate `lsl.l #1,Dn` operations one
architectural-X rename chain.

The line-E architecture already distinguishes the cases:

* immediate AS/LS counts are encoded as 1-8, so they always replace X and do
  not need its previous value;
* memory AS/LS has an implicit count of one and likewise does not read X;
* register-count AS/LS can have count zero, so it must read X to preserve it;
* ROX always reads X; RO never reads or writes X.

Therefore decode shall annotate:

```scala
// register destination
readsX := isRox || (isRegisterCount && !isRo)

// memory destination, implicit count = 1
readsX := isRoxMem
```

`writesX` is unchanged.  This is not speculative dependency prediction and it
does not change the shifter or any flag value.  It only stops immediate/memory
AS/LS from waiting for a value they provably cannot consume.  The binding
shift/rotate design is updated in the same phase.

The paired post-correction measurement validates that diagnosis:

| kernel | zero cycles / IPC | L2-faithful cycles / IPC | zero gain | L2 gain |
|---|---|---|---|---|
| `shift-stream` | 578 / **0.843** | 637 / **0.765** | **2.95x** | **2.71x** |
| `shift-mixed` | 620 / **1.303** | 671 / **1.204** | **2.02x** | **1.91x** |

`shift-stream` reaches/exceeds the predeclared target.  `shift-mixed` remains
below its 1.5-1.8 projection, but its residual is now independently bounded by
the existing frontend measurements, not attributed to the ALU without proof:
the body is 44 bytes (eleven 4-byte fetch windows minimum) and the current
fetch path pays about 5.08 cycles per correctly predicted taken transfer
(`2026-08-09-ipc-fetch-directed-btb-design.md`, §1).  Eleven window cycles plus
that measured backedge cost predicts about 16 cycles/iteration; the observed
620/40 = 15.5 cycles/iteration matches it.  The remaining amplifier is therefore
the already-specified fetch-directed-BTB lever, not slow-pipe serialization.

### 3.5 Cost

| | delta |
|---|---|
| new flops | **0** in the EU; `slotCount` (16) 1-bit `isAluSlow` slot bits in the IQ |
| new PRF write ports | 0 |
| new completion ports | 0 |
| new latency | **0** cycles, fast or slow |
| new service wires | 2 EU->IQ forecasts (`fastAcceptNext`) + 2 IQ->EU flush wires, one pair per ALU EU |
| LUTs | EU: net-negative (a 6-term NOR becomes a 2-term expression). IQ: one extra AND across a `slotCount`-wide mask per ALU port + 16 flops. Est. **< 100 LUTs net** against a 103,740-LUT design. |
| decode dependency refinement | no new state or datapath; two small Boolean terms replace two coarse terms |

---

## 4. Correctness argument

### 4.1 INV-1 (the write-port / completion exclusion) — restored

`fastFire(T) = s1Valid(T) && !isSlow(T)`. `s1Valid(T)` requires
`issuePort.fire(T-1)`, which requires `issuePort.ready(T-1)`. `s1Ctx` is an
unconditional `RegNext(issuePort.payload)`, so when `s1Valid(T)` holds,
`isSlow(T) == isSlowIn(T-1)`. Therefore `fastFire(T)` implies
`issuePort.ready(T-1) && !isSlowIn(T-1)`, which by the new gate implies
`!s2Valid(T-1)`, i.e. `!s3Valid(T)`. ∎

This is *stronger* than the audit's framing: it holds regardless of how many
slow ops are in flight, because `s3Valid` is a single-bit shift-chain output
and the exclusion is expressed against it directly.

The `wbKey` doc-comment's proof (`:96-107`) must be **rewritten** to this
argument. Its current form ("a slow op anywhere in S1..S3 holds `issuePort.ready`
low") stops being true and must not be left as stale documentation of a
now-different invariant. `priority = 1` on the fast requests stays as the
defensive tie-break.

### 4.2 The P5.7 select->fire invariant — REQUIRES the IQ change (this is a second, real bug if skipped)

`IssueQueuePlugin.scala:370-421` documents a load-bearing invariant:

> `selPorts(k).fire at C  =>  issuePorts(k).fire at C+1`

It exists because the **static latency-1 wakeup fires at SELECT time**: a
producer P selected at C clears its dependents' trigger bits at C, so a
dependent D is ready at C+1, can be selected at C+1 on the *other* ALU port, and
reads the PRF at C+2 — where P's bypass lives *only if P actually fired at C+1*.
If P is held in the registered issue stage for even one cycle, D reads the stale
previous occupant of P's physreg. This is the exact mechanism of the
`bfins_mem_dyn_both` regression (silent wrong-address corruption, not a hang).

Today the invariant holds because a slow handoff at C is the *only* way the EU
can be un-ready at C+1, and `aluSlowHandoff(k)` suppresses select on exactly
that cycle.

**Under the new EU gate alone, the invariant BREAKS.** A fast µop selected at C
lands in the registered stage at C+1 and needs `ready(C+1) = !s2Valid(C+1) =
!s1bValid(C)`, which the EU gate does not guarantee. A held fast producer
re-opens the P5.7 window.

**This is not resolved automatically by fixing II. It is a distinct second
obligation, and it is the single largest correctness risk in this lever.**

Restored proof with Change 2: `selPorts(k).fire at C` requires (i)
`issuePorts(k).ready(C)` (`selPorts(k).ready == issuePorts(k).ready` because the
registered stage is `m2sPipe(collapsBubble = false)`, whose `this.ready :=
ret.ready`) and (ii) the selected µop ∈ `cand_k(C)`.

* Selected µop is **slow** ⇒ `ready_k(C+1) = isSlowIn(C+1) || … = True`. ∎
* Selected µop is **fast** ⇒ by (ii) it survived the mask, so
  `eu(k).fastAcceptNext(C) = !s1bValid_k(C)` ⇒ `s2Valid_k(C+1) = s1bValid_k(C) =
  False` ⇒ `ready_k(C+1) = True`. ∎

Hence the old `aluSlowHandoff(k)` term is genuinely subsumed, not merely
"probably fine" — its whole job (guarantee readiness at C+1) is discharged by
the mask, for both classes and for every cycle, not just the handoff cycle.
Deleting it also retires **amplifier 3** (its 1-cycle bubble per slow op).

**An EU-only fix is provably impossible** — recorded so it is not retried.
Suppose `ready = f(EU state, isSlowIn)`. For the invariant we need
`ready(C) => ready(C+1)` for an arbitrary selected class. A slow µop must have
`ready(C) = True` unconditionally (else slow II regresses), and that provides no
information about `s1a2Valid(C)` / `s1aValid(C)`, so the fast case at C+1 cannot
be discharged. Widening the fast term to `!(s2Valid || s1bValid || s1a2Valid ||
…)` just pushes the requirement one stage earlier each time; closing the
recursion means gating the *slow* accept too, which collapses slow throughput
(simulating `a(t) allowed iff !a(t-4) && !a(t-5)` gives ≈0.44 ops/cycle, worse
than the clean design and non-monotone). The IQ must participate.

### 4.3 Liveness (no starvation, no deadlock)

`s1bValid_k` is asserted only if EU *k* accepted a slow µop 4 cycles earlier, so
the mask only ever *removes fast candidates*, and only while slow µops are being
consumed. If both EUs are continuously fed slow µops, a fast µop could be
skipped repeatedly — but slow µops come from the same finite IQ, and the ROB
retires **in order**, so a blocked older fast µop stalls retire, which stalls
dispatch, which drains the supply of younger slow µops; within `slotCount` +
ROB-drain cycles the mask releases. Bounded, no livelock. `oh0`/`oh1` remain
disjoint (`oh1` explicitly excludes `oh0`) and both remain subsets of
`aluReady`, so no µop can be selected by two ports and no not-ready µop can be
selected.

### 4.4 Head-of-line blocking (amplifier 2) — reduced ~7x, NOT structurally eliminated

Today: while port 0's EU is in its 7-cycle slow window, `oh0` still names the
oldest ready ALU µop, `selPorts(0).ready` is False so it cannot fire, and `~oh0`
masks it out of port 1 — the **oldest** ALU µop is stuck for 7 cycles while
younger µops overtake it on port 1, stalling in-order retire.

After this lever: port 0 is unable to take a fast µop for exactly **1 cycle per
slow µop** (`s1bValid`), and in that cycle the candidate-mask form re-routes —
port 0 takes the oldest *slow* µop and the oldest fast µop becomes `oh1`'s
candidate. So the worst-case head-of-line delay for the oldest ALU µop falls
from 7 cycles to at most 1, and usually 0.

**Honest**: the static oldest->port0 mapping itself is unchanged. A residual
1-cycle skew remains, and a pathological both-EUs-slow-every-cycle stream can
still defer the oldest fast µop (bounded, §4.3). Fixing the mapping properly is
non-goal N1: re-measure `shift-mixed` after this lever and only revisit if a
residual gap shows.

### 4.5 Flush / wrong-path kill — selected

Flush kill is part of this lever. Multiple in-flight slow operations make the
pre-existing drain behavior unsafe through an additional channel: the IQ clears
its `aluSlow*Busy` bitmaps on flush, but a stale later `slowWakeup` can match a
new producer that reused the same physical register and incorrectly clear its
busy bit. ROB/PRF reuse latency alone does not prove this safe.

The ALU therefore blocks issue during flush, gates same-cycle fast/slow PRF
writes, completion, `slowWakeup`, `ccrObs`, and delayed `wbObs`, and kills every
slow valid stage on the next edge:

```scala
s1aValid  := RegNext(s1Valid && isSlow && !flushPort) init False
// same `&& !flushPort` kill on S1a2/S1b/S2/S3
```

This costs about five valid-kill gates plus output-valid gating, adds no latency,
and removes reliance on a redirect-pipeline timing margin.

### 4.6 Defence in depth: why the EU keeps its own `!s2Valid` term

With Change 2 in place, a fast µop presented at C was necessarily selected at
C-1 with `!s1bValid(C-1)`, i.e. `!s2Valid(C)`, so `issuePort.ready` for a
presented fast µop is *always* True and could be reduced to a constant. **Do
not.** The EU must remain correct against any producer, including the standalone
`AluEuSourcePlugin` harness (`src/test/scala/m68k040/execute/AluEuSourcePlugin.scala:108`
drives `issue` directly with no IQ). The two gates have two different jobs and
must be maintained separately:

* EU `!s2Valid` = **correctness** (INV-1: write-port / completion exclusion). Local, self-contained.
* IQ candidate mask = **wakeup contract** (select->fire invariant). Requires IQ knowledge.

---

## 5. FMax treatment

### 5.1 Evidence (read-only, from an existing routed checkpoint — no new synthesis)

Mined from `synth/lscensus_*.rpt` + `synth/fullcore_routed.dcp`
(routed, 2026-08-09 10:27, `xcku5p-ffvb676-2`, 4.000 ns requirement, from the
concurrent LS-corridor census, commit `8c5ab56`). No Vivado was launched (the
machine had 4 concurrent Vivado processes and load ~15 at the time).

* Design WNS **-0.508 ns**, TNS -462.267 ns, **4,512 / 157,325 failing
  endpoints**. WNS is held by `FetchAlignPlugin` (157 endpoints, worst -0.508).
* **`AluEuPlugin` destination family: 13 failing endpoints, worst -0.187 ns,
  median -0.106 ns** — second-lowest of any real family (only `DivEuPlugin` and
  `RegFilePluginX` are lower). Compare `DcachePlugin` 1260 / -0.454 and
  `RobPlugin` 1122 / -0.395.
* **`issuePort_ready` / `issuePorts_*_ready` appear ZERO times in the entire
  4,512-endpoint census.** The ALU issue-accept cone is not near critical.
* The AluEu-*sourced* paths that do fail are the known
  `s1Ctx_uop_op -> RobPlugin nzvcValStore` / `RegFilePluginNzvc` family — the
  execute->ROB/PRF *writeback* cone, untouched by this lever.
* Congestion: **no windows above level 5** in either placer-final or router-
  initial reporting. Utilisation 103,740 / 216,960 CLB LUTs (47.8%), 47,343 FFs,
  26 BRAM, 6 DSP — **the pre-change LUT baseline to quote in the gate report.**

**Read**: the EU-side change (§3.2) lands in a region with ≥0.32 ns of margin
against WNS, and shortens rather than lengthens its own cone (a 6-term NOR of
stage valids becomes `isSlowIn || !s2Valid`; `isSlowIn` is one LUT level off the
IQ's registered payload, the same depth `isSlow` already has off `s1Ctx`).

### 5.2 Where the risk actually is: the IQ select cone

`IssueQueuePlugin` has 172 failing endpoints (worst -0.349 ns) and
`selPorts_*_rValid_i_*` nets **are** in the failing set. This project has a
direct, measured, expensive precedent for exactly the mistake this lever could
make (`IssueQueuePlugin.scala:929-946`, quoting a post-route grounding run):

> a `&& !slowFire` term ... `selPorts_4_fire && !(op===SHIFT || op===BITFIELD)`
> ... **is the SINGLE mechanism that dragged the 6-bit `op` field's MuxOH out of
> the select cone and INTO the scoreboard-clear cone, making this family the
> design's WNS holder (9 of the 10 worst paths, -1.699 ns).**

**Therefore, MANDATORY design constraint, not a preference**: the per-slot
slow-ALU classification must be a **stored flop** (`slot.isAluSlow`, written at
push from the already-existing `push0IsAluSlow`/`push1IsAluSlow` at
`IssueQueuePlugin.scala:862-863`, shifted on compaction alongside `lsWait` /
`cplxWait` / `aluSlowWait`), **never** a fresh `op === SHIFT || op === BITFIELD`
decode placed after `MuxOH(oh_k, contexts)` or anywhere else inside the select
cone. With the stored bit, the select cone grows by exactly one AND across an
already-existing `slotCount`-wide mask, ahead of the priority encoder — the same
shape as the `!isLs`/`!isCplx` terms already in `aluReady`.

### 5.3 Gate (mandatory)

1. **Post-route** (not OOC — proven unreliable this session) full-core gate on an
   **uncontended** machine, paired against the same commit without the change,
   worktree-isolated. Report WNS / TNS / failing-endpoint count / MHz.
2. **Targeted census** of `selPorts_*_rValid*` and `AluEuPlugin*` endpoint
   families before/after, reusing `synth/census.tcl` (already parameterised from
   the LS-corridor probes). The number to watch is the `IssueQueuePlugin` family
   count/worst, not just top-line WNS.
3. **Explicit LUT report** pre/post against the 103,740 baseline ("don't blow up
   LUT count" is an explicit user constraint).
4. Accept if WNS does not regress materially and the `IssueQueuePlugin` family
   does not move up the ranking. Reject-and-revert otherwise (the session's
   established discipline; cf. the `compValid` placement-ballast rejection).

---

## 6. Verification plan

**Unit (`AluFastSlowSpec`, extend)**
1. **II=1**: issue 6 distinct slow µops on 6 consecutive cycles; assert
   `issue.ready` was high on every one, and that all 6 results/flags land at S3
   on 6 consecutive cycles, each with a distinct robId and checked result. The
   landed burst is SHIFT; existing BITFIELD functional/lock-step tests cover the
   same physical slow stages.
2. **INV-1 assertion**: the landed directed boundary test offers a fast op
   exactly when `s2Valid`, asserts `fastFire && s3Valid` is false, then proves
   the fast op accepts on the next cycle. A longer randomized mixed
   SHIFT/BITFIELD/fast soak remains desirable before final physical acceptance.
3. **Latency unchanged**: the existing "completes at final S3" and
   "writeback lands at S3 (PRF holds old dst until then)" tests must pass
   **byte-identically** (`lat == 7` in the harness: six physical stages plus its
   pre-capture sample) — this lever must not move latency.
4. **Fast-op blocking is exactly 1 cycle**: assert `ready` is low for a fast µop
   iff `s2Valid`, never longer.

**IQ**
5. **Select->fire invariant**: the landed directed IQ test checks the forecast
   masks and exact slow-around-blocked-fast routing, and the integrated ALU test
   checks the forecast phase against real S2/S3 state. A randomized end-to-end
   `sel.fire(C) => issue.fire(C+1)` soak is retained as a follow-up assertion;
   it must drive ready consistently with the forecast promise.
6. Directed re-run of the P5.7 regression cluster, including
   `bfins_mem_dyn_both` (the test that originally exposed this hazard class).
7. Existing 17 IQ/dispatch specs green.

**Core**
8. `ExecuteLockStepSpec` — 390/394, fail set **byte-identical** to baseline.
9. Ported corpus (870 tests, 812 pass baseline) — byte-identical fail list,
   per-shard. Run the 220-test flush/mispredict/shift/bitfield subset used by the
   FMax round first as a fast screen.
10. **Flush-during-slow-stream directed test** (the §4.5 obligation): drive a
    dense slow stream, force a mispredict/exception flush mid-stream, and assert
    no stale `completes()` set on a re-allocated ROB index and no stale PRF write
    to a re-issued physreg.

**IPC**
11. `shift-stream` + `shift-mixed`, paired `IPC_SEED=1`, **both** `IPC_MEM=zero`
    and `IPC_MEM=l2:5:70`, against the baselines in §1.1.
12. Full 10-kernel suite both memory models — prove no regression on the eight
    pre-existing kernels (especially `independent-ALU` at 2.000, which shares the
    ALU issue path).

**Projection to check against** (stated before implementation so the result can
falsify the design, not be rationalised after): `shift-stream` should move from
0.286 toward ~0.75-0.80 (the limiter becomes the per-register loop-carried
slow-op latency of ~8-9 cycles, not II); `shift-mixed` from 0.646 toward
~1.5-1.8. Both ≈ **2.4-2.8x**.  The first result, 0.432/1.072, materially
missed that range.  Investigation found the false immediate-shift X dependency
in §3.4.  The corrected result is 0.843/1.303.  `shift-stream` validates the
slow-pipe model; `shift-mixed`'s remaining gap is quantitatively explained by
the separately measured frontend taken-transfer cost (also §3.4), which becomes
the next limiter after the ALU gate is removed.

### 6.1 Implementation verification record (2026-08-09)

The implementation is complete through simulation and the mandatory fast gate;
the physical gate in §5.3 remains pending on the PM-serialized Vivado window.
The checks are deliberately association- and throughput-sensitive rather than
latency-window-only:

- `AluFastSlowSpec` + `IqAluSlowSpec`: 11/11, including six consecutive slow
  accepts, six consecutive uniquely tagged completions, exact fast/S3 collision
  reservation, slow-candidate bypass, and dense-pipeline flush/reuse;
- existing IQ/dispatch regression set: 17/17;
- `OperationDecoderSpec`: 46/46, including the immediate/register/memory X-read
  distinctions and an exhaustive 65,536-opword operand-routing sweep;
- focused Musashi shift/rotate dependency checks: 3/3, and the historical
  `bfins_mem_dyn_both` wakeup-contract regression: 1/1;
- phase-local `make SBT=~/sbt/bin/sbt test-fast`: 133/134. The sole failure was
  the independently reproduced stale `PredecodeRefSpec` EOR/CMPM expectation;
  no new ALU/IQ/decode test failed. After the adjacent line-B and line-0 oracle
  corrections were integrated, the combined branch gate passed 138/138 at
  `5d54ad9`; and
- full ten-kernel `IpcBenchSpec`, seed 1: 4,657 macros / 7,685 cycles
  (0.606 aggregate IPC) with ideal memory and 4,658 / 11,212 (0.415) with the
  5/70-cycle hierarchy. Both runs pass, `independent-ALU` remains exactly 2.000
  IPC in the ideal model, and the eight pre-existing kernels retain their
  D1/D2 LSU-phase cycle counts.

During this gate, two old `OperationDecoderSpec` assertions were found to encode
superseded behavior: reversed ADDA operands and illegal CMPM. They were repaired
to verify the live ALU operand contract and the real `CMPM_ENTRY` microcode
association. Updating the tests instead of changing correct RTL is part of the
test-honesty requirement.

---

## 7. Alternatives considered

| # | Alternative | Verdict |
|---|---|---|
| A1 | The audit's "~6-flop S3 reservation shift register" | **Superseded, strictly better**: the existing `s1Valid..s3Valid` chain *is* that shift register. Zero new flops; `s2Valid` is exactly "slow op at S3 next cycle". |
| A2 | Give the slow path its own PRF write ports (drop the `wbKey` merge) | **Reject.** Removes the hazard entirely but adds 3 write ports × 2 ALU EUs across Int/Nzvc/X. The 2026-07-12 audit named the Int-PRF write-port count "the congestion epicenter"; violates G4 and the LUT constraint. |
| A3 | Stall the slow pipe (enable-gate S1..S3) when a fast op collides | **Reject.** 6 wide stage enables (the bit-field chain alone is ~7 registers deep per stage), real area and timing on a proven-sensitive cone, and it inverts the standing "throughput over latency" principle. |
| A4 | EU-only gate, leave `IssueQueuePlugin` untouched | **Proven impossible** (§4.2). Documented so it is not retried. |
| A5 | Also fix the static oldest->port0 mapping (dynamic port assignment) | **Out of scope** (N1). The candidate-mask form of Change 2 recovers most of the benefit for free; re-measure before spending select-cone timing budget on the rest. |
| A6 | Bundle the slow-chain flush kill | **Selected** (§4.5). Required to prevent stale dynamic wakeups from clearing a reused producer's busy bit. |
| A7 | Do nothing — "shifts are rare" | **Reject on evidence.** 3-4% of instructions on the real target ROM, 7x IPC penalty measured, and the collateral damage to fast ops (`shift-mixed` active% = 40.2%) is larger than the direct cost. |

---

## 8. Risk assessment (honest)

* **R1 — IQ select-cone FMax (HIGH likelihood of being the deciding factor).**
  There is a measured -1.699 ns precedent in this exact cone for exactly this
  kind of term. Mitigated by the stored-per-slot-bit constraint (§5.2), but the
  §5.3 gate is the real arbiter and this lever must be prepared to be rejected
  on it, like `compValid` was.
* **R2 — P5.7 wakeup-contract regression (HIGH severity, MEDIUM likelihood if
  the IQ gate is implemented carelessly).** Getting Change 2 wrong produces
  *silent stale-operand corruption*, not a hang — the `bfins_mem_dyn_both`
  failure mode. Verification item 5 (a live checked invariant) is not optional.
* **R3 — wrong-path drain density (LOW).** 6x more stale completions/PRF writes
  in an unchanged, already-covered window (§4.5). Argued safe; hardening
  available; directed test mandated regardless.
* **R4 — head-of-line residual (LOW).** Reduced ~7x, not eliminated (§4.4).
  Could leave measurable IPC on the table; N1 tracks it.
* **R5 — benchmark ceiling (MEDIUM, measurement-interpretation risk).** Both new
  kernels become *latency*-bound after the fix (per-register loop-carried slow
  chain ≈ 9 cycles), so the measured delta will UNDERSTATE the throughput
  improvement available to real code with more independent slow work. Do not
  read the kernel number as the ceiling.
* **R6 — placement equilibrium (LOW-MEDIUM, this session's recurring lesson).**
  Logic-only changes in issue-select cones have surprised this initiative
  before. Post-route paired A/B is the only trustworthy check; OOC will not do.
* **R7 — stale documentation (LOW severity, HIGH likelihood if rushed).** The
  old `wbKey` proof, single-outstanding comments, completion note, and P5.7
  handoff block all asserted the replaced invariant. They are rewritten to the
  S3-reservation/select-forecast proof in this phase; future edits must preserve
  that consistency because these comments are load-bearing for later audits.

---

## 9. Files

| file | change |
|---|---|
| `src/main/scala/m68k040/execute/AluEuPlugin.scala` | `issuePort.ready` (§3.2); new `fastAcceptNext` and `flush` on `AluEuService`; rewrite the stale latency/single-outstanding comments; flush-kill the valid chain and outputs. |
| `src/main/scala/m68k040/execute/iq/IqContext.scala` | Expose `IssueQueueService.aluFastAcceptNext` without reaching into either EU's internals. |
| `src/main/scala/m68k040/execute/iq/IssueQueuePlugin.scala` | per-slot `isAluSlow` flop (push + compaction), ALU candidate masks, delete `aluSlowHandoff` + its doc block, rewrite the P5.7 proof (§4.2). |
| `src/main/scala/m68k040/decode/OperationDecoder.scala` | precise X-source annotations from §3.4; no datapath or flag-semantic change. |
| `src/main/scala/m68k040/top/SynthProbePlugin.scala`, `ExecSynthProbes.scala` | registered forecast inputs keep both fast/slow candidate-mask cones live in synthesis-only IQ/backend probes. |
| `src/test/scala/m68k040/execute/AluFastSlowSpec.scala` | verification items 1-4. |
| `src/test/scala/m68k040/execute/iq/*` | verification items 5-7. |
| `src/test/scala/m68k040/decode/OperationDecoderSpec.scala` | immediate/register/memory AS/LS/ROX/RO X-dependency checks. |
| `src/test/scala/m68k040/bench/IpcBenchSpec.scala` | **already landed** (`shift-stream`, `shift-mixed`). |

Both the forecast and flush are wired at all six IQ+EU integrations:
`FullCoreSynth`, `IpcBenchSpec`, `ExecuteLockStepSpec`, `FuzzDut`,
`BackendWhiteboxSpec`, and `LsBackendInjectSpec`. Standalone IQ fixtures either
drive the forecast explicitly or retain the fail-safe `False` default. The two
synthesis-only IQ probes have no ALU EU; they expose registered top-level
forecast inputs so neither candidate-mask branch is constant-trimmed.

**Out of bounds for this lever** (concurrent work): `src/main/scala/m68k040/frontend/`,
`src/main/scala/m68k040/execute/LsEuPlugin.scala`.
