# FMax Lever N-A — pre-decode the ALU op class into the µop

Date: 2026-08-08
Status: DESIGN + IMPLEMENTED (this document was written before the RTL edit and
updated with the as-built details; see §8 for anything that moved).
Baseline commit: `344c0c5`
Ledger: `.superpowers/sdd/progress-fmax-levers-2026-08-08.md`
Grounding report: `.../scratchpad/fmax-nzvcvalstore-grounding-report.md`

---

## 1. The finding this acts on

The pinned-netlist post-route gate (190.11 MHz, WNS -1.260 ns) found a NEW WNS
family holding 23 of the top-100 worst endpoints:

```
AluEuPlugin s1Ctx_uop_op  ->  RobPlugin {nzvcValStore, sysValStore, xValStore}
```

A dedicated grounding pass on that routed checkpoint measured it as far bigger
than the top-100 sample suggested: **~2,368 flops and ~3,100 failing paths behind
one unregistered EU→ROB seam — on the order of 15 % of the design's 18,215
failing endpoints.** The same source cone independently overruns the **NZVC PRF
write-data** endpoint at **-1.082 ns**.

Path anatomy of the -1.260 ns WNS path (5.242 ns data, 72 % route, 16 levels):

| segment | delay | RTL |
|---|---|---|
| `s1Ctx_uop_op[5]/Q` (fanout **89**) | 0.109 | `AluEuPlugin.scala:153` |
| 2 LUT levels: `DecOp` compares -> `aEff`/`bEff`/`cin` operand prep | **0.626** | `AluDatapath.scala:43-57` |
| 3 × CARRY8 (`sum32`) + adder tail | 1.03 | `AluDatapath.scala:58` |
| flag gen (`piece()` + n/z/v/c) -> `rsp.nzvc` | 1.64 | `AluDatapath.scala:99-132` |
| **3 LUT levels: the final-flag mux chain** | **0.401** | `AluEuPlugin.scala:234` + `:514` |
| one 1.043 ns cross-die route hop | 1.043 | ALU EU (X14-26) → ROB array (X39-95) |
| 2-LUT 64-entry ROB write mux | 0.416 | `RobPlugin.scala:731` |

**The two segments in bold are pure redundancy.** `s1Ctx.uop.op` is a µop field
that has been sitting in a register since decode. The ALU EU nevertheless
re-derives op-class booleans from it COMBINATIONALLY, TWICE, at S1 — once at the
head of the cone (in series with, and *before*, the carry chain) and once at its
tail (a 5-deep serial 2:1 mux chain).

This is the identical shape as four levers that already succeeded this session
(Frontend Lever A, Lever B, Lever U1, the IQ scoreboard redundant-gate fix):
*something already known is being recomputed on the critical path.*

## 2. Scope

**IN**: pre-decode the op-class control into the µop; consume it in
`AluDatapath` and `AluEuPlugin`; re-associate the final-flag mux so the one
late-arriving input sits at the outermost level.

**OUT** (explicitly, per the dispatch): Lever N-B (jointly registering `ccrObs` +
`completionPort`). No change to `AluEuPlugin.scala:836-853` (task #176's
committed-CCR retire timing). No flush gating added to the ALU EU's `ccrObs`
path. No change to the ROB.

## 3. Design

### 3.1 New bundle: `m68k040.decode.AluOpClass`

New file `src/main/scala/m68k040/decode/AluOpClass.scala`. 14 bits, every field a
**pure function of `DecOp`**:

| field | replaces (pre-lever expression) | site |
|---|---|---|
| `zeroA` | `isNeg \|\| isNegx` | `AluDatapath.scala:53-54` |
| `invB` | `isSub \|\| isSubx` | `AluDatapath.scala:54` |
| `cinSel` (2b) | the 3-deep `cin` Mux chain | `AluDatapath.scala:55-57` |
| `isArith` | `op===ADD \|\| isSub \|\| isNeg \|\| isNegx \|\| isAddx \|\| isSubx` | `AluDatapath.scala:52` |
| `borrow` | `isSub \|\| isNeg \|\| isNegx \|\| isSubx` | `AluDatapath.scala:127` |
| `isTas` | `cmd.op === DecOp.TAS` | `AluDatapath.scala:117` |
| `isBitOp` | `cmd.op === DecOp.BITOP` | `AluDatapath.scala:118` |
| `isExtended` | `(op===NEGX)\|\|(op===ADDX)\|\|(op===SUBX)` | `AluEuPlugin.scala:224` |
| `isNbcd` | `u1.op === DecOp.NBCD` | `AluEuPlugin.scala:248` |
| `isBcd` | `u1.op === DecOp.BCD \|\| isNbcd` | `AluEuPlugin.scala:249` |
| `isCasOp` | `u1.op === DecOp.CASOP` | `AluEuPlugin.scala:373` |
| `isShift` | `u1.op === DecOp.SHIFT` | `AluEuPlugin.scala:174` |
| `isBitfield` | `u1.op === DecOp.BITFIELD` | `AluEuPlugin.scala:179` |

`cinSel` encoding (`object AluCin`): `ZERO=0` (ADD / non-arith), `ONE=1`
(SUB/CMP/NEG), `X=2` (ADDX), `NOTX=3` (NEGX/SUBX).

There is **exactly one producer**, `AluOpClass.of(op: DecOp.C)`, holding
verbatim the expressions listed above.

### 3.2 Where it is baked: `RenameStage`, one line

```scala
r.op     := dec.op
r.aluCls := AluOpClass.of(dec.op)   // <- new, adjacent, same source
```

`RenamedUop` gains `val aluCls = AluOpClass()`.

**Why rename and not `DecodedUop`**: `DecodedUop` has ~15 independent producer
sites (`OperationDecoder`, `Microcode.resolve`/`resolveFromBits`, the 8
`MicroOpAssembler` builders, `DecodeContracts`' default). Adding a derived field
there would mean 15 chances to hand-set it inconsistently — precisely the failure
mode this lever must not introduce. `RenamedUop` has **exactly one** producer
(`RenameStage`, field-by-field; `uopsPort.payload(s) := slotN` copies the whole
bundle thereafter, as do `DispatchPlugin` → `IqContext` and the ROB's
`allocUopVec`). Rename is still many cycles upstream of the ALU EU's S1 — the µop
sits in the IQ in a register — so the field is just as "already known" at S1 as
`op` itself, at 1/15th the divergence surface.

**Why not a local `RegNext(AluOpClass.of(issuePort.payload.uop.op))` in the ALU
EU** (which would cost no µop width at all): that inserts a LUT level between the
IQ's age-priority issue-select mux and the S1 register — i.e. it moves work onto
the S0 path, which the IQ-scoreboard grounding pass already measured as 73.3 %
issue-select cone. Carrying the bits through the IQ entry register instead adds
**zero** logic to S0.

### 3.3 What the consumers change to

`AluDatapath` gains a second entry point:

```scala
def apply(cmd: AluCmd): AluRsp = apply(cmd, AluOpClass.of(cmd.op))   // unchanged behaviour
def apply(cmd: AluCmd, cls: AluOpClass): AluRsp = { ... }            // the EU's path
```

The single-argument overload is simultaneously (a) the compatibility path for the
unit-test DUTs and for the `op = DecOp.CMP` **constant** instance inside the CAS
kernel (`AluEuPlugin.scala:390`, where `of()` constant-folds to nothing), and
(b) *the definition of what `aluCls` is required to equal*.

Inside `apply(cmd, cls)`: `aEff`/`bEff` take `cls.zeroA`/`cls.invB`; `cin`
becomes a single 4-way `cls.cinSel.mux`; `isArith`/`borrow`/`isTas`/`isBitOp`
come from `cls`. **No `DecOp` compare remains upstream of the carry chain.**

In `AluEuPlugin`: `val aluCls = u1.aluCls` once at S1, then `isShift`,
`isBitfield`, `isExtended`, `isBitOp`, `isNbcd`, `isBcd`, `casIsOp` all read from
it, and `AluDatapath(cmd, aluCls)` replaces `AluDatapath(cmd)`.

### 3.4 The final-flag mux re-association

```scala
// before
finalNzvc = Mux(casIsOp, casNzvc, Mux(u1.toCcr, ccrNzvc, Mux(isBcd, bcdNzvc, aluNzvc)))
finalX    = Mux(u1.toCcr, ccrX,   Mux(isBcd, bcdCarry, rsp.xOut))
// after
useAluNzvc = !casIsOp && !u1.toCcr && !isBcd
nonAluNzvc = Mux(casIsOp, casNzvc, Mux(u1.toCcr, ccrNzvc, bcdNzvc))
finalNzvc  = Mux(useAluNzvc, aluNzvc, nonAluNzvc)
useAluX    = !u1.toCcr && !isBcd
finalX     = Mux(useAluX, rsp.xOut, Mux(u1.toCcr, ccrX, bcdCarry))
```

Pre-decoding the *selects* alone would not have helped here — they were already
1 LUT off a register and arrived early. What costs 3 LUT levels is that the ONE
late input (`aluNzvc`, straight off the adder + flag gen) sat at the BOTTOM of a
serial chain. Hoisting it to the outermost mux makes it 1 level.

**This is unconditionally value-identical — no mutual-exclusivity argument is
needed.** `useAluNzvc` is exactly "none of the three priority selects fired",
i.e. the old chain's fall-through arm, and the relative priority
`cas > toCcr > bcd` is preserved verbatim inside `nonAluNzvc`. All four cases:
`cas=1 → C`; `cas=0,toCcr=1 → Cc`; `cas=0,toCcr=0,bcd=1 → Bd`; none → `A`.
Identical in every case, *including* the physically-impossible overlaps. Same
argument for `finalX` with two selects.

Cost accounting per leg (LUT levels from that leg's input to `finalNzvc`):
alu **3 → 1**, cas 1 → 1, ccr 2 → 2, bcd 3 → 2. Nothing is traded away.

## 4. Correctness argument

This is a **pure re-sourcing of an already-computable value**. No architectural
state, no new decode semantics, no protocol change, no latency change, no IPC
change. Deleting `aluCls` and calling `AluOpClass.of(op)` at each point of use
would restore the previous netlist exactly.

The obligation splits cleanly in two:

**(C1) `AluOpClass.of(op)` == the pre-lever inline expressions, for every op.**
Not argued — measured, two independent ways (§5).

**(C2) `RenamedUop.aluCls` == `AluOpClass.of(RenamedUop.op)`.** By construction:
one producer, one call site, adjacent lines, same `dec.op` operand, whole-bundle
copies downstream. Also gated live in hardware (§5).

**(C3) The mux re-association is value-identical** — the 4-case enumeration in
§3.4, which is exhaustive over the select space.

Not-obligations, checked and dismissed:
- `cmd.op` is still carried and still drives the 12-way `result` mux and the
  `srLogicRes`/`ccrNew`/`anWide`/`isPack`/`isUnpk`/`isBfResolve`/`isLogicSr`
  sites — those are untouched.
- The `casCmpCmd` instance's op is the Scala constant `DecOp.CMP`; its class
  constant-folds, so no new hardware and no new pairing obligation.
- `AluOpClass` is consumed only by the ALU EU. Div/LS/branch EUs are untouched.

## 5. Verification plan (as executed)

New `src/test/scala/m68k040/execute/AluOpClassSpec.scala`:

1. **Field differential, exhaustive over `DecOp`.** A DUT emits
   `AluOpClass.of(op)` beside a VERBATIM transcription of the 13 pre-lever
   expressions (and decodes `cinSel` back to a carry-in, checked for both `xIn`
   polarities). Swept over every `DecOp` encoding × `xIn`.
2. **Whole-datapath differential.** `AluDatapathRef` is a frozen copy of
   `AluDatapath` at `344c0c5`. The DUT runs the shipping two-argument
   `AluDatapath(cmd, cls)` beside it and compares `result`/`nzvc`/`xOut` over a
   full corner cross-product (every op × size × 16×16 operand corners × `xIn`,
   with `extByte`/`bitOp` varied) plus a randomized fill.
3. **Live rename pairing gate.** Pushes every `DecOp` encoding through the real
   `RenameStage` (both slots, so slot1's intra-group-bypass path is covered) and
   checks the observed `aluCls` against an independently-written pure-Scala model
   — plus an assertion that the observed `op` is the one just pushed, so the gate
   cannot silently pass by reading a stale payload.

Plus the standing gates: `sbt compile`, `ExecuteLockStepSpec`, the ALU/rename/IQ
unit suites, the full ported corpus (before/after, byte-identical fail lists),
and a synth gate.

**Mutation obligations** (both must be demonstrated, not assumed):
- Break `AluOpClass.of` (drop `CMP` from `isSub`) ⇒ tests 1 and 2 must fail.
- Break only the rename pairing ⇒ **only** test 3 must fail.

## 6. Cost

+14 bits on `RenamedUop`. That lands in: the rename output register, the 16 IQ
slot registers (16 × 14 = 224 FF) with their compaction-shift muxes, and the 5
issue-port select muxes. Estimated a few hundred LUTs — same order as Lever B's
+1227, and to be reported exactly from the synth gate, not estimated.

Zero latency. Zero IPC (no register added on any path; nothing moves between
pipeline stages).

## 7. Expected outcome

The grounding report's netlist-based estimate: ~1 LUT level + route at the head
(~0.30 ns) and ~2 levels at the tail (~0.25 ns) ⇒ **~0.4–0.55 ns off every sink
of this cone simultaneously** — the ROB `nzvcValStore`/`sysValStore`/`xValStore`
capture AND the independently-failing **NZVC PRF write-data endpoint
(-1.082 ns)**, which Lever N-B alone would *not* fix. That last point is the
reason N-A is ordered first.

Per this round's standing methodology correction, judge this on **TNS and
failing-endpoint count**, not solo WNS: WNS is a max over ~18k violated
endpoints and is close to placement noise at this design's density. A family
that owns ~15 % of the failing endpoints is exactly a TNS lever.

Do not quote a projected MHz without a gate.

## 8. As-built deltas from the pre-implementation draft

- The `finalX` re-association was added alongside `finalNzvc` (same shape, same
  unconditional-identity argument); the draft only named `finalNzvc`.
- `isShift`/`isBitfield` were included in the bundle although they are not on the
  measured `nzvcValStore` path. Justification: they are the same one-line
  pattern, they feed `isSlow`, and `isSlow` gates both `issuePort.ready` (a
  combinational output back into the IQ's issue arbitration) and `fastFire`,
  which is the enable on the NZVC/X PRF writes — i.e. one of this family's own
  sinks.
- `anWide`, `isLogicSr`, `isFromCcrSr`, `isPack`/`isUnpk`/`isBfResolve` were
  deliberately NOT pre-decoded: they are either not pure functions of `op`, or
  their selects already arrive early and pre-decoding them would buy nothing but
  µop width.
