# Audit: instructions implemented via a narrow addressing-mode carve-out

Read-only review, 2026-09-12. Trigger: `FRESTORE d16(A5)` crashed real Mac OS
because `FSAVE`/`FRESTORE` admitted only register-indirect EAs
(`docs/BUG_frestore_displacement_ea_and_fline_frame.md`). That gap was invisible
because a *deferred* item with no failing test looks exactly like a *correct*
illegal trap. This document sweeps the decoder for the same shape:
**an instruction whose decode admits a strict subset of the addressing modes an
MC68040 allows, where the remainder falls to an illegal/unimplemented default —
or, worse, silently executes with a wrong address or a wrong length.**

Files audited: `src/main/scala/m68k040/decode/{OperationDecoder,MicroOpAssembler,
EaDecoder,DecodeStage,Microcode}.scala`, `src/main/scala/m68k040/frontend/PredecodeWord.scala`.
FSAVE/FRESTORE is excluded (being fixed separately); it is used only as the
reference shape.

## Two credibility rules applied to every entry

1. **Is the mode actually legal on a 68040?** Each finding names the
   architectural EA class (data / data-alterable / control / control-alterable /
   memory-alterable). Where a form turned out to be genuinely illegal on silicon,
   it is listed under "checked and correct", not as a gap.
2. **Does `PredecodeWord` agree on LENGTH?** Adding a mode almost always changes
   instruction length, and several families here are framed by a *fallback* that
   assumes a fixed or brief length. A length mismatch mis-frames the *next*
   instruction and produces a wild PC — the failure mode already recorded in this
   file's own `FMOVE.L #imm,FPCR` and `FBcc` comments. Each finding states whether
   the length is fixed or EA-dependent and whether `eaExt(...)` already covers it.

---

## Ranked summary

| # | Instruction / form | Missing modes | What happens today | Software likelihood | Severity |
|---|---|---|---|---|---|
| 1 | `MOVEM <list>,(bd,An,Xn)` / `(bd,PC,Xn)` — FULL-format ext | full-format (ext bit8=1) | **silently wrong EA and wrong length** | medium | critical (silent) |
| 2 | `Scc <ea>` with a full-format **memory-indirect** EA | `([bd,An,Xn],od)` etc. | **silently wrong address / wrong-register write** (not routed to µcode, not trapped) | low-medium | critical (silent) |
| 3 | `CAS` / `MOVES` with a full-format EA | full-format (ext bit8=1) | predecode frames **brief** → wrong `nextPc` → wild PC | low-medium | critical (silent) |
| 4 | `CMP2`/`CHK2 (d8,An,Xn)` / `(d8,PC,Xn)` (brief *and* full) | indexed control modes | vector 4 (deliberate fail-safe) | low | high (clean trap) |
| 5 | `DIVU.L/DIVS.L/MULU.L/MULS.L <ea>` with `(An)+` / `-(An)` | auto-update data modes | vector 4 | low | high (clean trap) |
| 6 | `FMOVEM.X` / `FMOVEM.L <ctrl-list>` with `(d8,PC,Xn)`; `FMOVEM.X` dynamic lists | indexed PC-rel; dynamic register list | vector 11 → FPSP → FPSP itself uses FMOVEM.X | low | high |
| 7 | `MOVE16` absolute forms (`F600/F608/F610/F618`) | the 4 abs.L forms | vector 11, framed **1 word** (so the abs.L is also executed as code) | low | high |
| 8 | line-E memory shift `<ea>` with a memory-indirect EA | `([bd,An,Xn],od)` | vector 4 | very low | medium (clean trap) |
| 9 | `NBCD <ea>` memory forms | all of data-alterable except `Dn` | vector 4 | very low | medium |
| 10 | Bit-field memory ops with an indexed PC-relative EA | `(d8,PC,Xn)`, `(bd,PC,Xn)` | vector 4 (deliberate fail-safe) | very low | low |

Items 1-3 are ranked above items 4-9 despite lower absolute frequency because
they are **silent**: there is no trap, no vector, no log line. Items 4-10 all
produce a clean, attributable exception.

---

## 1. `MOVEM` with a FULL-format indexed extension word — silent

**Encoding.** `0100 1d001s mmmrrr` + mask + EA ext.
`OperationDecoder.scala` `movemEaOk` admits mode 6 `(d8,An,Xn)` and mode 7/reg 3
`(d8,PC,Xn)` for **both** directions (tasks `movem-agu-index-hazard-2026-08-19`
and `movem-idx-an-store-2026-09-11`).

**The gap.** That classifier sees only the opword, so it cannot tell a *brief*
`(d8,An,Xn)` from a *full-format* `(bd,An,Xn)` (ext bit 8 = 1). `DecodeStage.scala`
therefore treats every admitted indexed MOVEM as brief:

* the EA is computed as base + sext(d8) + Xn, ignoring `bd`/`od` entirely;
* `movemPcIdxRealNextPc := ePc + 6` hardcodes "opword + mask + one ext word".

A full-format MOVEM is at minimum 4 words and can be 7. Both the address and the
length are wrong, and nothing traps. The code's own comment concedes it: *"the
full-format sub-case (ext word bit8=1) is NOT distinguishable from a single
opword there, so it silently computes a WRONG ... EA ... characterized not fixed
(task #200 report)"* (`DecodeStage.scala` ~:1980).

**Is it legal on a 68040?** Yes. `(bd,An,Xn)` with I/IS=000 is control-alterable
(valid for MOVEM in both directions); `(bd,PC,Xn)` is control (valid for the load
direction). This is not a reserved encoding.

**Likelihood.** This ROM demonstrably uses full-format extension words in
ordinary code — the Q700 RAM-sizing routine's
`cmpi.l #$316D6567,%a0@(0xFEFFC)` (full-format, IS=1, BD-SIZE=long, I/IS=000) was
*the* boot blocker of the calibration campaign
(`BUG_calibration_word_misplaced_0d00.md` Part 121/122). A compiler emitting a
>32KB frame offset for a register save/restore produces exactly this shape.

**Length.** EA-dependent. `PredecodeWord`'s MOVEM `mmOk` table never marks mode 6
or mode 7/reg 3 `simple` at all, so the length is not computed there — it is
computed in `DecodeStage` (`movemPcIdxRealNextPc`). `fullExtLen(eaW)` already
exists in `PredecodeWord` and would give the right answer, but the MOVEM arm does
not call it, and the resume path does not use `eaExt` either.

**Effort / risk.** Medium. Two changes that must land together: (a) `DecodeStage`
reads the real ext word (it already has `movemEntryPkt.words(2)`) and derives both
the `bd` term and the true word count; (b) either reject memory-indirect
(I/IS≠000) explicitly or route it to the µcode engine. Risk of *not* doing it is
higher than the risk of doing it: the current behaviour is unbounded silent
corruption. A cheap interim mitigation (low risk, ~5 lines) is to restrict
`movemEaOk`'s mode-6 / mode-7-3 arms to brief format and let full-format take
vector 4 — turning a silent wrong answer into a clean trap — but that needs the
ext word, which `OperationDecoder` cannot see; the gate belongs in `DecodeStage`.

## 2. `Scc <ea>` with a full-format memory-indirect EA — silent

**Encoding.** `0101 cccc 11 mmmrrr`. `MicroOpAssembler`'s `isSccOp` admits every
mode except 1 and mode 7/reg≥2, and — critically — `isSccOp` is one of the
**exclusion terms at the head of `bad`**, so `sccMemBad` can never fire for a
memory Scc.

**The gap.** `DecodeStage.scala` (~:131) states it outright:

> *NOTE (deliberately NOT yet routed ...): the line-E MEMORY shift/rotate form and
> Scc `<ea>` are ALSO missing, but neither fits the existing `MI_RMW_ENTRY` shape
> without new machinery ... Scc needs a CONDITION evaluation (branch EU) rather
> than an ALU host-op.*

and the surrounding block records the consequence of exactly this class of
omission: *"the family silently falls through to the ordinary non-microcoded fast
path, whose EA machinery cannot walk a pointer chain, so it computes a GARBAGE
address -> access fault -> wild PC (or, for Scc, a wrong-register write)."* That
has already happened four times to four other families (tasks #144/#145, #150,
#152, TAS/2026-09-03), each time found by fuzz, not by a test.

Unlike the shift case (item 8), Scc is **not** rescued by `bad`: `isSccOp`
suppresses it. So a memory-indirect Scc executes.

**Is it legal on a 68040?** Yes — `Scc <ea>` takes data-alterable modes, which
include the full-format memory-indirect forms.

**Likelihood.** Low-medium. Memory-indirect `Scc` is not something a C compiler
emits, but it is reachable from hand-written 68020+ assembly and from A-trap
dispatch tables. It is in the fuzz reachable set by construction.

**Length.** `PredecodeWord`'s Scc arm frames it through `memDestExt`, which
*does* call `fullExtLen(eaW)` for mode 6 — so the LENGTH is already right. Only
the execution path is wrong. That is what makes it silent rather than a wild PC.

**Effort / risk.** Medium-high: needs a new µcode entry (pointer-load then a
branch-EU condition store), because `MI_RMW_ENTRY` hosts an ALU op. A low-risk
interim fix is to add `(isSccOp && srcEa.klass === MEMINDIRECT)` as a `bad` term
so it traps vector 4 instead of executing wrongly.

## 3. `CAS` / `MOVES` / `CMP2`/`CHK2` full-format EA — predecode frames BRIEF

**Encodings.** `CAS 0000 1ss0 11 mmmrrr` + 1 ext + EA ext; `MOVES 0000 1110 ss
mmmrrr` + 1 ext + EA ext; `CMP2/CHK2 0000 0ss0 11 mmmrrr` + 1 ext + EA ext.

**The gap.** For all three families the EA's own extension word sits at op+2, not
op+1, and `classify()` only receives op+1 in `extW`. All three arms therefore
call `eaExt(..., eaW = B(0,16 bits))` — a hardcoded **zero**, which reads as
"brief format" — and say so:

* CMP2/CHK2: *"pass 0 to keep brief framing (full-format here is out of scope)"*
* CAS: *"pass eaW=0 (brief framing; full-format CAS EA is out of scope and would mis-frame ...)"*
* MOVES: *"pass eaW=0 (brief framing; the in-scope memory-alterable EAs all fit the brief lengths)"*

For CAS and MOVES nothing downstream rejects a full-format EA either: the µcode
`ucCasEaDec` re-decodes from `Vec(words(0), words(2), words(3))` — only three
words, so a `bd.L` (which needs words(2..3)) plus an `od` cannot even be read —
and there is no `klass =/= MEMSIMPLE` check on that path. So a full-format
`CAS`/`MOVES` gets a wrong length **and** a wrong address, with no trap.

CMP2/CHK2 is saved by accident: the assembler force-illegals every indexed shape
(item 4), and an illegal op flushes at the faulting PC, so the mis-frame never
reaches the next instruction.

**Is it legal on a 68040?** Yes. CAS and MOVES take memory-alterable modes
(mode 6 included, brief or full). CMP2/CHK2 takes control modes (mode 6 and
mode 7/reg 3 included).

**Likelihood.** Low-medium. `MOVES` is used by the ROM for alternate-address-space
access (18 candidate sites in the ROM disassembly, most of them data noise);
`CAS` appears a handful of times. Full-format EAs on them specifically are rare.

**Length.** EA-dependent; `eaExt` already computes it correctly **if given the
real word**. The fix is plumbing, not new logic: `classify()` already threads
`extW2`/`extW2Valid` (the FScc and cpGEN arms use exactly that for the same
op+2 shift), so these three arms only need `eaW = extW2, eaWKnown = extW2Known`.

**Effort / risk.** Low for the framing fix (three one-line changes, the pattern is
already proven by the FScc/cpGEN arms in the same function). The CAS/MOVES µcode
EA re-decode needs a wider word window or an explicit `MEMINDIRECT`/full-format
reject; the reject is the cheap, safe move.

## 4. `CMP2` / `CHK2` with indexed control EAs — deliberate fail-safe trap

**Encoding.** `0000 0ss0 11 mmmrrr` + ext word.
`MicroOpAssembler.scala:3640` :

```scala
val c2IndexedShape = (c2Mode === B"110") || ((c2Mode === B"111") && (c2Reg === B"011"))
val c2EaOk = (c2SrcEa.klass === EaClass.MEMSIMPLE) && (c2SrcEa.autoMode === EaAuto.NONE) &&
             !c2IndexedShape
```

The comment is explicit that this is an under-implementation, not an ISA rule:
*"mode 6 ... and mode 7/reg 3 ... are architecturally LEGAL CMP2/CHK2 control EAs
(§4.39), but this 2-load+compare crack only forms a straight base+disp address"*.

**Is it legal on a 68040?** Yes — control addressing modes, and `(d8,An,Xn)` /
`(d8,PC,Xn)` are control.

**What happens.** Vector 4, `unimplemented=True`. Clean and attributable. This is
the *correct* shape for a deferred item — unlike items 1-3.

**Likelihood.** Low. `CHK2`/`CMP2` are 68020+ bounds checks; Pascal-derived Mac
code uses `CHK` far more often than `CHK2`. Nothing in the ROM disassembly looks
like a real `CHK2` site.

**Length.** Already correct-by-luck (see item 3): the brief-indexed case is 3
words and `eaExt` returns ext=1 for mode 6/7-3 under the zero `eaW`, which is the
brief answer — right for brief, wrong for full. Generalizing needs item 3's
`extW2` plumbing first.

**Effort / risk.** Medium: the crack must thread `srcEa.indexReg`/`indexLong`/
`indexScale` onto `srcC` of *both* `c2Load0` and `c2Load1` (the builder
`c2LoadUop` already sets `u.srcCReg := c2SrcEa.indexReg`, so this may be mostly
done — the gate is the only thing rejecting it; verify the AGU result before
lifting the gate). Low risk if the two loads are checked to produce the same
indexed base.

## 5. `DIVU.L`/`DIVS.L`/`MULU.L`/`MULS.L` with `(An)+` or `-(An)`

**Encoding.** `0100 1100 01 mmmrrr` (DIV.L) / `0100 1100 00 mmmrrr` (MUL.L) + ext.

```scala
val divlDivisorOkMem = divlDivisorIsMem && (divlSrcEa.autoMode === EaAuto.NONE)
val mullMulOkMem     = mullMulIsMem     && (mullSrcEa.autoMode === EaAuto.NONE)
```

with the reason stated: *"(An)+/-(An) still isn't supported (would need a 4th µop
for the auto-update ADD — no room left at the .L64 3-µop ceiling) and stays
illegal"*.

**Is it legal on a 68040?** Yes. `DIVU.L <ea>,Dr:Dq` takes **data** addressing
modes, which include `(An)+` and `-(An)`.

**What happens.** Vector 4 (an explicit forced-illegal block, not a fall-through).

**Likelihood.** Low. Compilers materialise the operand into a register first;
`divu.l (a0)+,d0` is a hand-assembly idiom.

**Length.** Fixed at 2 words for these modes (opword + ext word, no EA
extension) — `eaExt` returns ext=0 for modes 3/4, and the existing framing for
`0x4C4x`/`0x4C0x` already covers them. **No predecode change needed.** The
blocker is purely the 3-µop crack budget.

**Effort / risk.** Medium. Either raise the crack budget for this family or fold
the An update into the load µop's `eaAuto`/`eaDelta` (the generic `crackLoad`
path already does exactly that for other families — see `srcAuto`/`anUpdUop`).
The latter looks like the right generalization and is probably small.

## 6. FP register-list transfers with indexed PC-relative EAs, and dynamic lists

Three separate narrow gates, all in `DecodeStage.scala`:

* `ucFpCtrlPcRelOk = ucFpCtrlIsLoad && ucBfEaDec.pcRel && !ucBfEaDec.indexValid`
  — so `FMOVEM.L (d16,PC),FPCR/FPSR` works (the Q700 ROM relies on it) but
  `FMOVEM.L (d8,PC,Xn),FPCR` takes vector 11. The comment concedes it: *"`(d8,PC,Xn)`
  ... stays excluded via the `!indexValid` term: it needs the 3-phase index crack
  ... which this fix does not add."*
* `s0IsFpGenMemEa` (the FMOVEM.X FSM) admits modes 2,3,4,5,6 and 7/reg{0,1,2} —
  **not** mode 7/reg 3 `(d8,PC,Xn)`.
* `s0FpIsStatic = !s0FpExt1(11)` — **dynamic** register lists (mask in a data
  register) are not admitted at all, for either FMOVEM family.

**Is it legal on a 68040?** Yes on all three counts. `(d8,PC,Xn)` is a control
mode and legal for the load direction of both FMOVEM families; dynamic lists are
a standard 68881/68040 FMOVEM feature.

**What happens.** Vector 11. That lands in the ROM FPSP — which itself uses
`FMOVEM.X`. This repo has already been bitten by exactly that recursion
(`fpu_fmovem_x_an_indirect.s`: *"every trap drags in the ROM FPSP, which itself
uses FMOVEM.X — so the fallback is not a graceful degradation, it is a fault in
the handler for the fault"*). That is what lifts the severity above the raw
frequency.

**Likelihood.** Low for the indexed PC-relative forms. **Dynamic FMOVEM lists are
the one to watch** — Motorola's own FPSP source uses dynamic `fmovem` in its
save/restore paths, so if the ROM's FPSP is a Motorola derivative this is a live
gap rather than a latent one. Worth one targeted grep of the ROM's vec-48..55
handlers before deciding.

**Length.** EA-dependent but already correct: `PredecodeWord`'s cpGEN arm calls
`eaExt(fpEaMode, fpEaReg, ..., eaW = extW2, eaWKnown = extW2Known)`, which frames
mode 7/reg 3 (brief and full) properly. **The length half is already done** — only
the execution gates need widening. Dynamic lists do not change length at all.

**Effort / risk.** Medium for the indexed PC-relative EA (needs the index threaded
through the FP register-list walk; the control-list rows already carry
`indexFromEa = true`, so the An-indexed case works and PC-indexed is likely a
small delta). Dynamic lists are a larger, separate piece of work.

## 7. `MOVE16` — only `(Ax)+,(Ay)+` decodes

**Encodings.** Five forms exist on a 68040:

| form | opword | words |
|---|---|---|
| `MOVE16 (Ax)+,(Ay)+` | `0xF620\|Ax` + ext (Ay in ext[14:12]) | 2 |
| `MOVE16 (An)+,(xxx).L` | `0xF600\|An` + abs.L | 3 |
| `MOVE16 (xxx).L,(An)+` | `0xF608\|An` + abs.L | 3 |
| `MOVE16 (An),(xxx).L` | `0xF610\|An` + abs.L | 3 |
| `MOVE16 (xxx).L,(An)` | `0xF618\|An` + abs.L | 3 |

`OperationDecoder` decodes only `opword(15 downto 3) === 0xF620>>3`. The other
four *"deliberately stay on the F-line illegal default (vector 11)"*.

**Is it legal on a 68040?** Yes, all five. These are not an `<ea>` field at all —
the address is an implied 32-bit absolute, so `eaExt` is irrelevant here.

**What happens.** Vector 11 — **and the length is framed as 1 word** by
`PredecodeWord`'s generic F-line `.otherwise` arm. So if the trap is ever
resumed past (an FPSP-style `RTE` to `nextPc`), execution lands *inside the
absolute address*. Today the vector-11 path re-executes rather than skipping, so
this is latent, not live; it becomes live the moment anything RTEs past it.

**Likelihood.** Low. Three independent signals agree:
* The Q700 ROM's `BlockMove` fast path at `0x40884482`/`0x40884490`/`0x40884496`
  uses the `(Ax)+,(Ay)+` form — the one that already works. Every absolute-form
  hit in the linear disassembly (`0x4080c594`, `0x40845116`, `0x4089457e`, …)
  sits in obvious data, with no plausible instruction stream around it.
* **Musashi implements only `1111011000100...`** (`m68k_in.c:723`), i.e. the
  `(Ax)+,(Ay)+` form. So adding the absolute forms means adding them *without a
  lock-step oracle*, exactly like the `FSF (xxx).L` carve-out.
* Apple's `BlockMoveData` is the dominant MOVE16 consumer and uses the
  postincrement form.

**Effort / risk.** Low-medium. Each form is a 4×LONG copy with one side a
literal address — the existing `MOVE16_ENTRY` µcode row shape covers it with a
base-less (`baseValid=False`, address folded into the displacement) variant, the
same trick the FMOVEM.X abs/PC-rel paths already use. The **required** companion
change is a `PredecodeWord` arm framing `0xF600-0xF61F` as 3 words. The real cost
is verification: no Musashi oracle, so it needs a hand-written directed test.

**Recommendation: leave alone unless a concrete failure points at it.** The
evidence says real software does not use these, and adding oracle-less decode
widens the divergence surface. Do, however, consider the 3-word predecode frame
on its own — it costs nothing, and it makes the vector-11 trap report a truthful
`nextPc`.

## 8. Line-E memory shift/rotate with a memory-indirect EA

`isShiftMem` (`OperationDecoder`, line E, `ss==11`, mode ∉ {0,1}) is a full
memory-alterable form and works for `(An)`, `(An)+`, `-(An)`, `(d16,An)`,
`(d8,An,Xn)` brief, `(xxx).W/.L`. A **memory-indirect** EA is not routed to the
µcode engine (the same `DecodeStage` note as item 2) and is not reachable from
`MI_RMW_ENTRY` because `Microcode.scala` hardcodes `u.shiftOp`/`u.shiftDir`.

Unlike Scc, this one **does** trap cleanly: the shift family carries no exclusion
term at the head of `bad`, so `usesSrcEa && !srcEaOk` fires for a `MEMINDIRECT`
class and it becomes vector 4. Legal on a 68040 (memory-alterable includes the
full-format forms); very unlikely in real code; clean failure. **Safe to leave.**

## 9. `NBCD <ea>` — register form only

```scala
// NBCD Dn (0100 1000 00 000 rrr) ... register form ONLY -- memory-EA NBCD stays
// deferred/illegal, task #159
when(opword(15 downto 3) === B"13'b0100100000000") { ... }
```

**Is it legal on a 68040?** Yes — `NBCD <ea>` takes **data-alterable** modes:
`Dn`, `(An)`, `(An)+`, `-(An)`, `(d16,An)`, `(d8,An,Xn)`, `(xxx).W`, `(xxx).L`.
All seven memory forms are missing.

**What happens.** Vector 4 (the line-4 `illegalDefault`).

**Likelihood.** Very low. Every `nbcd` in the ROM disassembly is data noise —
verified by context at `0x4080c438` (inside an obvious `4000,4100,4200,…` byte
table), `0x4087c972`, `0x4089c898`. BCD arithmetic on memory operands is a
COBOL/financial idiom, not a Mac Toolbox one. Note that the *register* form is
equally rare, so this is not "the register form is hot and the memory form is
cold" — the whole instruction is cold.

**Length.** EA-dependent; `eaExt`/`memDestExt` already handle the whole
data-alterable set (`PredecodeWord` line ~666 explicitly frames only the register
form today, and `memDestExt` is exactly the right helper — it is what `TAS <ea>`,
`CLR/NEG/NOT`-mem, `Scc`-mem and the line-E memory shift already use).

**Effort / risk.** Low, and lower than it looks: `TAS <ea>` (task #158) is the
exact precedent — it rides the generic `crackRmw` load-op-store path with no EU
change, because the `NBCD` ALU cone already exists for the register form and is
generic over whether `srcA` came from a register or a loaded temp. The work is
(a) widen the `OperationDecoder` pattern from `op[15:3]` to `op[15:6]`,
(b) add `NBCD` to `isLine4Unary` so `line4UnaryMemBad`'s
`rmwOpInScope && MEMSIMPLE` arm admits it, (c) add the `memDestExt` arm in
`PredecodeWord`. Mostly mechanical. **Safe to defer**, but it is the cheapest
item on this list if someone wants a clean win.

## 10. Bit-field memory ops with an indexed PC-relative EA

```scala
val bfmEaOk = (bfmEaDec.klass === EaClass.MEMSIMPLE) && (bfmEaDec.autoMode === EaAuto.NONE) &&
              !(bfmEaDec.pcRel && bfmEaDec.indexValid)
```

`BFTST/BFEXTU/BFEXTS/BFFFO (d8,PC,Xn){off:wd}` is architecturally legal (control
mode, read-only op) and the comment says the shape *"is not actually known-broken,
but it is DELIBERATELY still out of scope"* — a fail-safe pending verification
work. Vector 4, clean. Very low likelihood. **Safe to leave**, and the comment is
already honest about why.

---

## Checked and found CORRECT (not findings)

These were examined and the restriction matches real 68040 behaviour. Recording
them so nobody re-derives them:

* **`PTEST`** — gated to `(An)` only (`0xF548-4F`, `0xF568-6F`). Correct: unlike the
  68030, the MC68040 `PTEST` has **only** the `(An)` form. Not a carve-out.
* **`PFLUSH` family** — `0xF500-0xF51F` with mode ≤ 3 covers `PFLUSHN (An)`,
  `PFLUSH (An)`, `PFLUSHAN`, `PFLUSHA`. That is the complete 68040 set.
* **`LEA`/`PEA`** — `leaPeaEaOk` rejects `autoMode ≠ NONE`. Correct: `(An)+`/`-(An)`
  are not control modes, so `LEA (A0)+,A1` is genuinely illegal on silicon.
  Memory-indirect is routed to `MI_LEA_ENTRY`/`MI_PEA_ENTRY`, not trapped.
* **`JMP`/`JSR`** — `ctrlEaOk = srcIsMem` plus the µcode `MI_JMP_ENTRY`/
  `MI_JSR_ENTRY` routing covers the full control set including brief-indexed and
  memory-indirect. Verified against real ROM `0x40809A04`
  (`jsr @($400,D2.w*4)@(0)`).
* **`TST An`** — admitted for `.W`/`.L`, rejected for `.B`. Correct (68020+ added
  `TST An`; there is no byte-size An operand).
* **`MOVE to CCR`** — `mtcSrcOk` rejects `ADDRREG`. Correct: `MOVE An,CCR` is not
  a data addressing mode.
* **Bit-field memory RMW** (`BFCHG/BFCLR/BFSET/BFINS`) — `ctrlAlterable` admits
  modes 2, 5, 6 and 7/reg{0,1} only. Correct: RMW needs control-**alterable**, so
  PC-relative and auto-update modes are genuinely illegal.
* **`CAS`/`MOVES` EA mask** — both use the memory-alterable set
  `(An)/(An)+/-(An)/(d16,An)/(d8,An,Xn)/(xxx).W/.L`, rejecting `Dn`/`An`/PC-rel/
  `#imm`. Correct. (Their *full-format framing* is item 3; the mask itself is right.)
* **`MULU.W`/`MULS.W` rejecting `An`-direct** — correct, `An` is not a data mode.
* **Line-0 immediate + FULL-format (I/IS=000) RMW destination** — this **is
  implemented**. `limmFullFmtDstBad` was removed on 2026-09-03 (`169a94b7`) once
  `extW3` lookahead + `ambiguousLine` made the framing exact. `srcEaIsMemSimpleEff`
  / `l0Fixup` in `MicroOpAssembler` reads the EA's real ext word at
  `words(3)` for a `.L` immediate. See the note below about the stale test.
* **`FScc` / `FDBcc` / `FTRAPcc` / `FBcc`** — the prompt listed `FSF (xxx).L`
  (`0xF27F`) as a one-opword carve-out. That is now historical: commit `b8e2c89d`
  ("F-line type 001 is now complete") generalized `FScc <ea>` to the same EA scope
  as the integer `isSccOp`, and `FBcc` with a real condition is a full branch.
  `0xF27F` survives only as a **reserved-EA substitution** (mode 7/reg 7, a
  non-canonical encoding that the Q700 ROM nevertheless executes) and does not
  need generalizing.
* **`PACK` / `UNPK` memory forms** — the prompt listed `op[5:3]=001` as
  unimplemented. Also historical: task #198 added `PACK_MEM_ENTRY` /
  `UNPK_MEM_ENTRY` and `PredecodeWord`'s `isPackUnpkFrame` has framed both forms
  as len=2 from day one. No gap.
* **`EaDecoder` full-format support** — the file header still says the indexed
  modes *"stay MEMCOMPLEX so the assembler keeps them unimplemented"*. That
  comment is **stale**: modes 6 and 7/reg 3 are decoded for both brief and
  full-format, returning `MEMSIMPLE` for I/IS=000 and `MEMINDIRECT` otherwise.

---

## Incidental observations (not addressing-mode gaps, but found on the way)

1. **A stale test.** `src/test/scala/m68k040/decode/MemRmwDecodeSpec.scala:327`
   (`"ADDI.L #imm,(8,A0,D1.L*4) FULL-FORMAT no-mem-indirect dst -> ILLEGAL
   (vector 4)"`) pins behaviour that was **deliberately removed** on 2026-09-03
   — it was the Q700 boot blocker. The test file's last touch predates the
   removal (2026-06-23). Either it now fails, or something else is illegalising
   that shape and the removal comment is wrong. Worth one run of that single spec
   to find out which.
2. **`PredecodeWord`'s FSAVE/FRESTORE arm passes `eaWKnown = True`
   unconditionally** (`eaExt(..., eaW = extW, eaWKnown = True)`) rather than
   `extWKnown`. Every other arm in the function threads the real validity flag.
   For mode 6 / mode 7-3 that means a zero-filled `extW` at an I-cache-line
   boundary is trusted as "brief" instead of raising `ambiguousLine` — the exact
   silent-mis-frame class this file's own comments call out. Flagged here only
   because it sits in the code being changed right now; **out of this audit's
   scope by instruction.**
3. **Stale comment, `DecodeStage.scala:1778`** — says `(d8,An,Xn)` stays out of
   scope for the FP control-register list. It does not: `ucFpCtrlOk` admits any
   non-pcRel `MEMSIMPLE`, and the `fpCtrlLoadBaseGroup`/`fpCtrlStoreBaseGroup`
   µcode rows carry `indexFromEa = true`. Only the *PC*-indexed case is excluded.

---

## Bottom line

The prompt's starting list has **substantially shrunk** since it was written:
`PACK`/`UNPK` memory forms, `FScc`/`FDBcc`/`FTRAPcc`, and full-format
(no-mem-indirect) RMW destinations are all implemented. What remains, and what
this audit adds, sorts into two very different piles:

* **Three silent-wrong-answer cases (items 1-3)** — `MOVEM` full-format,
  memory-indirect `Scc`, and `CAS`/`MOVES` full-format framing. These are the
  ones that matter, not because they are frequent but because they produce no
  vector, no log line, and no reproducible PC. Item 1 is the one to fix: the ROM
  is *known* to use full-format EAs in ordinary code, and MOVEM is everywhere.
  Items 2 and 3 each have a cheap "make it trap instead of executing" mitigation
  that costs a few lines and converts silent corruption into a clean vector 4.
* **Seven clean-trap cases (items 4-10)** — every one produces an attributable
  exception, every one has a written rationale at the gate, and none of them has
  a credible path to real Mac OS or Q700 ROM code. `NBCD` memory is the cheapest
  to close if someone wants the ISA box ticked (the `TAS <ea>` precedent makes it
  nearly mechanical); `MOVE16`'s absolute forms are the one I would explicitly
  **leave alone** — Musashi has no oracle for them, real software does not use
  them, and adding oracle-less decode trades a latent trap for a live divergence
  risk.

No other narrow addressing-mode carve-outs were found. The decoder's habit of
writing the rationale *at the gate* is what made this audit tractable — items 1,
2 and 4 were all found by following the code's own admissions, not by
re-deriving the ISA.
