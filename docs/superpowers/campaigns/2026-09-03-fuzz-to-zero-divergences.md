# Campaign: fuzz lock-step to ZERO divergences

Branch: `fuzz/zero-divergence-campaign` (off `fmax-closure-fanout` @ `a5199bc`).
Goal (owner-set): drive the `FuzzLockStepSpec` campaign to **0 divergences** by fixing
every real RTL bug it exposes.

## Running divergence count

| Round | Date | Divergences / 200 | Delta | Notes |
|-------|------|-------------------|-------|-------|
| 0 (baseline) | 2026-09-01 | **57** | — | `fuzz_logs/*.log`, 8 batches of 25 seeds |
| 1 | 2026-09-03 | **not yet re-measured** | — | cluster A fix landed; measurement blocked on Vivado (see "Verification status") |

Baseline is deterministic: a 30-seed re-run at current HEAD reproduced the identical
diverging seed set byte-for-byte.

---

## Round 1 — the inventory

### Headline correction

The standing attribution — *"all 57 are one known wild-PC / A7-minus-8 bug class"* — is
**wrong**. There are **four** distinct root-cause signatures, and the largest by far is a
**harness/observability defect, not an RTL defect**. The "wild PC" and "A7" populations
that gave the old label its name are two *separate, small* clusters (10 and 6), not the
whole population; the 40 remaining divergences the label silently absorbed have nothing
to do with either.

Mechanical classification of all 57 (`grep '^\[fuzz\] seed=.* DIVERGED' fuzz_logs/*.log`):

| Reported field | dut vs oracle | Count |
|---|---|---|
| `pc` | dut = oracle + 2 | 28 |
| `pc` | dut = oracle + 4 | 2 |
| `pc` | dut = oracle + 6 | 10 |
| `pc` | dut = garbage (outside the image) | 10 |
| `a7` | dut = 0x000ffff8/c/e, oracle = 0x00100000 | 6 |
| `reg D5` | low byte 0x00 vs 0x79 | 1 |

The three `PC + N` rows are ONE cluster: `N` is just the byte length of whatever
instruction followed, because the DUT retire stream is short by exactly one record.

### Cluster summary

| # | Cluster | Count | Class | Status |
|---|---|-------|-------|--------|
| A | `Scc <mem>` commit dropped from the lock-step retire stream | **40** | **HARNESS** | fix written, unverified |
| B | memory-destination RMW with a memory-indirect / full-format EA takes a spurious exception | **10** | **RTL** | open |
| C | committed A7 transiently regresses one index after a `movem` `-(%sp)`/`(%sp)+` pair | **6** | undetermined (harness-observation vs real A7-shadow lag) | open |
| D | `Scc ([bd,An],Xn,od)` writes `Dn` instead of memory | **1** | **RTL** | open |

Totals: 57. RTL = 11, harness = 40, undetermined = 6, oracle-limitation = 0.

**None of the 57 involve `DIVU`/`DIVS`/`DIVSL`/`DIVUL` remainder behaviour.** The
`DivEuPlugin` remainder-latch bug being fixed concurrently (`BUG_calibration_word_
misplaced_0d00.md` Part 116) does **not** explain any divergence in this population, so
none of these will resolve for free from that fix. `mulu.l` appears in exactly one
minimized program (seed 10) and is incidental — seed 10's minimized program also carries
`svs (0x4027).w`, i.e. it is a cluster-A case. Independently corroborated by the EU
shared-state audit (`docs/superpowers/specs/2026-09-03-eu-shared-state-identity-audit.md`,
commit `4ceb833`), which reached the same zero from the same 57 reports, and which also
clears the multiplier (robId-keyed `Mem` + per-robId valid + flush clear — the correct
pattern). **But see BS-1 in the blind-spot ledger: this zero is a "cannot see it" zero,
not a "not there" zero** — the corpus never compares `Dr` at its own retire step, so it
is structurally incapable of reporting the divider bug's signature. Treat the divider as
unmeasured by this corpus, not as exonerated by it.

---

### Cluster A — `Scc <mem>` is dropped from the retire stream (40/57) — HARNESS

**Seeds** (40): 2, 10, 22, 25, 28, 29, 33, 36, 43, 49, 61, 62, 64, 66, 68, 69, 72, 73,
77, 87, 90, 91, 95, 102, 108, 110, 114, 132, 134, 140, 143, 155, 157, 170, 171, 177,
178, 185, 186, 199.

**Minimal reproducer — seed 108** (`fuzz_100_125.log`), minimized by the harness to a
4-instruction body:

```
	st (0x402b).l
	or.l %d3,%d4
Lend:
	bra.s Lend
```

```
detail: idx=32 pc: dut=0x408000a8 oracle=0x408000a6
  idx 31 dut{pc=0x408000a0 ...} orc{pc=0x408000a0 ...}
  idx 32 dut{pc=0x408000a8 ... reg4=0xf7dbf83f(v=true)} orc{pc=0x408000a6 ...}
  idx 33 dut{pc=0x408000a8 ...}                          orc{pc=0x408000a8 ...}
```

The body starts at `0x408000a0`. `st (0x402b).l` is 6 bytes (`0xa0`→`0xa6`); `or.l
%d3,%d4` is 2 (`0xa6`→`0xa8`). At `idx 32` the oracle retires the `st` (nextPc `0xa6`);
the DUT's `idx 32` is already the `or.l` (nextPc `0xa8`, and it reports the `or.l`'s
32-bit D4 result). **The `Scc` produced no retire record at all**, so every later index
is shifted by one, and the reported `PC + N` is the length of whichever instruction the
shift happened to expose.

Repro: `FUZZ_SEED_START=108 FUZZ_SEED_COUNT=1 FUZZ_BLOCKS=20 testOnly m68k040.fuzz.FuzzLockStepSpec`

**Evidence this is the whole cluster, not a coincidence**: all 40 minimized programs
contain at least one `s<cc> <memory EA>`. Four of them (seeds 108, 25, 2, 185) minimize
to a 4-6 instruction body in which the Scc-mem is the only non-trivial instruction.

**Mechanism (root-caused by code reading, exact):**

`MicroOpAssembler.scala:2568-2582` cracks `Scc <ea>` memory-dest into
`[branch-EU op µop -> T1][trailing rmwStore T1 -> EA]`, and explicitly sets
`opUop.keepCommit := True` on the op µop with the comment *"the kept commit; the trailing
store is dropped"*.

`WhiteboxCapture.onCommit` (`src/test/scala/m68k040/lockstep/WhiteboxCapture.scala:62-69`)
decides:

```scala
val isTempOnly = wb.intWrite && wb.dstArch >= 16 && !wb.nzvcWrite && !wb.xWrite
val emit = (!isTempOnly && !wb.divRem) || wb.keepCommit
```

`AluEuPlugin.WbObs`, `LsEuPlugin` and `DivEuPlugin` all carry a `keepCommit` field and
drive it from `u1.keepCommit`. **`BranchEuPlugin.BrWbObs` was the only EU observation
bundle that omitted it** (`BranchEuPlugin.scala:57-64`), so every consumer constructed
`WhiteboxCapture.Wb(...)` without it and it defaulted to `false`. For `Scc <mem>` the
branch EU reports `anWrite=1, anArch=T1 (>=16)`, no flags → `isTempOnly=true`,
`keepCommit=false` → `emit=false` → the instruction is silently deleted from the
lock-step stream.

**Why this is a harness defect and not an RTL defect**: the RTL executes `Scc <mem>`
correctly, and that is independently verified by the ported asm corpus, which checks the
resulting memory byte rather than the retire stream — `scc_abs_long.s`, `scc_predec.s`,
`scc_mem_indexed.s`, `scc_mem_forms.s`, `scc_mem_an_indirect.s`, `scc_mem_incdec.s`
(`src/test/resources/m68kooo-ported-tests/asm/`). The directed `ExecuteLockStepSpec` has
the same blind spot in its capture code; it simply has no `Scc <mem>` lock-step case, so
nothing ever exercised it.

This is the second instance of the precedent recorded in `MEMORY.md` (the MSHR V1.6b
"real RTL race" that turned out to be a harness artifact). It is worth stating plainly:
**70% of a six-week-old "known RTL bug class" was a missing field in a sim-only
observation bundle.**

**Fix** (this branch): add `keepCommit` to `BrWbObs`, drive it from `u1.keepCommit`
mirroring `AluEuPlugin.scala:853`, and pass it at the three full lock-step capture sites
(`FuzzLockStepSpec.scala`, `MiHangTraceSpec.scala`, `ExecuteLockStepSpec.scala`). The
other nine `dut.branchEu.logic.wbObs` capture sites build
`WhiteboxCapture.Wb(0, 0L, false, ...)` with `intWrite=false`, so `isTempOnly` is already
false there and they emit correctly — no change needed.

**Predicted effect**: 57 → 17. This is a prediction, not a measurement. See
"Verification status".

---

### Cluster B — spurious exception on memory-dest RMW with a memory-indirect EA (10/57) — RTL

**Seeds** (10): 3, 4, 21, 41, 57, 74, 103, 109, 126, 127.

**Minimal reproducer — seed 3** (`fuzz_0_25.log`):

```
	move.l #0x400b,%a3
	move.l #0x402e,%d0
	move.l %d0,(0x401c).l
	tas ([0x12,%a3,%d6.l*1],0x0)
Lend:
	bra.s Lend
```

```
detail: idx=36 pc: dut=0xfa227f05 oracle=0x408000ba
  idx 35 dut{pc=0x408000b4 sr=0x2700 a7=0x00100000} orc{pc=0x408000b4 sr=0x2700 a7=0x00100000}
  idx 36 dut{pc=0xfa227f05 sr=0x2700 a7=0x000ffff8} orc{pc=0x408000ba sr=0x2708 a7=0x00100000}
```

**Hypothesis (mechanism):** at the divergence the DUT's `a7` has dropped by exactly
**8** — a 68040 format-$0 exception frame — and the PC is garbage. The DUT takes an
exception the oracle does not, and vectors through an *uninitialized* vector-table entry
(the fuzz sandbox leaves the vector page as seeded random fill), which is why the
resulting PC is random and frequently odd. The likely vector is 4 (illegal instruction),
consistent with the "reject → safe trap" policy documented at `PredecodeWord.scala:112`.

9 of the 10 minimized programs contain a memory-destination RMW instruction with a
memory-indirect EA — `tas ([...])` (seeds 3, 4, 21, 41, 74, 103, 126), `lsr.w ([...])`
(127), `rol.w ([...])` (109), `eori.w #imm,([...])` (41). Seed 57 is the odd one out:
`ori.w #0x254a,(20,%a0,%a2.l*2)`, an indexed (not memory-indirect) mem-dest RMW — so the
cluster boundary is provisionally "**full-format / non-trivial EA on a memory-destination
RMW**", and seed 57 may yet split off. Flagged as not fully resolved.

**Supporting code evidence:** `PredecodeWord.memDestExt` (line 118) accepts mode 6
unconditionally, framing full-format extension words via `fullExtLen(eaW)` — so
`tas ([bd,An],od)` is classified SIMPLE. But `MicroOpAssembler.scala:781` defines
`srcIsMem = (srcEa.klass === EaClass.MEMSIMPLE)`, and `EaDecoder.scala:138-139` classifies
a real memory-indirect EA as `EaClass.MEMINDIRECT`. So the fast-path assembler's mem-dest
RMW crack is gated off for exactly these EAs while predecode has already committed to
SIMPLE. There **is** a memory-indirect microcode engine with an `MI_RMW_ENTRY`
(`Microcode.scala:2258`, routed at `DecodeStage.scala:2031`) that handles this shape for
other families — the open question for round 2 is whether `TAS`/shift-mem/`EORI`-mem are
in `ucIsMemInd`'s opcode gate at all, or fall through to the illegal path.

**Classification: real RTL bug** — Musashi executes these, and a real 68040 supports
full memory-indirect addressing for these instructions. Not an oracle limitation.

**Do not fix speculatively.** The above is a code-reading hypothesis; it needs an
executed repro (seed 3, plus a directed `tas ([bd,An],od)` case) confirming the vector
number before any RTL change.

---

### Cluster C — transient committed-A7 regression after `movem -(%sp)` / `(%sp)+` (6/57)

**Seeds** (6): 8, 13, 18, 54, 60, 139. **All six** minimized programs contain a
`movem.<sz> <regs>,-(%sp)` immediately followed by `movem.<sz> (%sp)+,<regs>`.

**Minimal reproducer — seed 60** (`fuzz_50_75.log`), 8-instruction body:

```
	nop
	movem.l %d1,-(%sp)
	movem.l (%sp)+,%d1
	move.l #0xfffffffd,%d6
	move.l #0x403f,%a0
	move.l (-7,%a0,%d6.l*4),%a5
Lend:
	bra.s Lend
```

```
detail: idx=36 a7: dut=0x000ffffc oracle=0x00100000
  idx 33 dut{a7=0x000ffffc reg15=0x000ffffc(v=true)} orc{a7=0x000ffffc}   <- movem push, AGREES
  idx 34 dut{a7=0x00100000 reg15=0x00100000(v=true)} orc{a7=0x00100000}   <- movem pop,  AGREES
  idx 35 dut{a7=0x00100000 reg6=0xfffffffd(v=true)}  orc{a7=0x00100000}   <- AGREES
  idx 36 dut{a7=0x000ffffc reg8=0x0000403f(v=true)}  orc{a7=0x00100000}   <- A7 REGRESSES
  idx 37 dut{a7=0x00100000 reg13=0x86b28c00(v=true)} orc{a7=0x00100000}   <- self-corrects
```

The push and the pop both compare **clean**. Two instructions later, on an unrelated
`move.l #0x403f,%a0`, the committed A7 momentarily reverts to the pre-pop value and then
self-corrects on the very next index.

**Hypothesis:** `WhiteboxCapture.result` reconstructs A7 as `a7Run`, folding OoO arch-15
writebacks, but **force-resyncs `a7Run` to `a7Static` whenever `a7Static` changes**
(`WhiteboxCapture.scala:88-95`), where `a7Static = rob.logic.exc.ss.a7`. `ss.a7` is
documented there as tracking only exception/RTE/boot A7 changes. If `ss.a7` is also
updated (or updated *late*) by the `movem` stack traffic, a delayed `ss.a7` sample two
commits later looks like a change and stomps the correctly-folded `a7Run` with the stale
push value.

**Classification: UNDETERMINED.** If it is purely the `a7Static` resync heuristic, it is
a harness bug. If `ss.a7` genuinely lags the architectural A7, it is a **real and serious
RTL bug** — `ss.a7` is the SP used for exception frame stacking, so an exception taken in
that window would stack at the wrong address. Round 2 must settle this by tracing
`rob.logic.exc.ss.a7` directly on seed 60. Do not assume harness.

---

### Cluster D — `Scc ([bd,An],Xn,od)` writes `Dn` instead of memory (1/57) — RTL

**Seed 80** (`fuzz_75_100.log`), 6-instruction body:

```
	move.l #0x400b,%a5
	move.l #0x4021,%d0
	move.l %d0,(0x401c).l
	scs ([0x11,%a5],%d1.l*4,0x0)
Lend:
	bra.s Lend
```

```
detail: idx=36 reg D5: dut=0x6dd63400 oracle=0x6dd63479
  idx 36 dut{pc=0x408000ba ... reg5=0x6dd63400(v=true)} orc{pc=0x408000ba ...}
```

The DUT retires the `scs` with the correct PC but **writes D5** — clobbering D5's low
byte with the condition byte `0x00`. `D5` is not an operand of the instruction: `5` is
`op[2:0]`, the EA *register* field (`%a5`).

**Mechanism (code-read, high confidence):** `isSccOp` (`MicroOpAssembler.scala:1575`) is
true for any line-5 `ss==11` with `mode != 1` and not `mode7/reg>=2` — which includes
mode 6 memory-indirect. It also suppresses the `sccMemBad` illegal path (`:1890`). But
`sccIsMem = isSccOp && srcIsMem && !srcEa.pcRel` uses `srcIsMem = klass === MEMSIMPLE`
(`:781`), which is **false** for `MEMINDIRECT`. So the crack falls into the `otherwise`
arm at `:2586-2588` — the `Scc Dn` form — and writes `D<op[2:0]>`.

**Classification: real RTL bug**, same root family as cluster B (predecode accepts
full-format/memory-indirect EAs that the fast-path assembler's `MEMSIMPLE` gate then
rejects), but with a *silent wrong-register-write* outcome rather than a trap. Silent
corruption makes this the highest-severity finding of round 1 even though it accounts for
only one divergence — the fuzz generator just rarely emits `Scc` with a memory-indirect
EA. Expect the true incidence to be higher than 1/200 in real code.

---

## Verification status (round 1) — READ THIS BEFORE TRUSTING ANY NUMBER

The cluster-A fix is **written but NOT executed**. A Vivado build was holding the machine
for the entire round (`pgrep -f vivado` → 15 processes, ~1GB RSS and climbing), and the
project rule is *max 2 heavy JVMs, never during Vivado* (29GB total; the fuzz test JVM
peaks ~7.6GB per Verilator compile). Running it would have risked an OOM that destroys
other agents' multi-hour work. So:

- The **57 → 17 figure for round 1 is a PREDICTION, not a measurement.**
- The verification bar (`make fastTest`, `make test-fast`, `BsrFlushSkipSpec` + the four
  `flush_younger_*.s` corpus tests) has **not** been run.
- The standing full-core OOC synth gate has **not** been run. The RTL delta is a single
  1-bit field on `BrWbObs`, a sim-only observation bundle that already carries
  five other fields and whose peers (`AluEuPlugin.WbObs` etc.) already carry exactly this
  field; expected impact is nil, but that is an expectation, not a measured result.

**To close round 1**, once Vivado is clear:

```bash
# 1. the three cluster-A minimal repros — MUST fail before this commit, pass after
JAVA_OPTS=-Xmx10g FUZZ_SEED_START=108 FUZZ_SEED_COUNT=1 FUZZ_BLOCKS=20 \
  ~/sbt/bin/sbt 'testOnly m68k040.fuzz.FuzzLockStepSpec'
#    repeat for FUZZ_SEED_START=2 and FUZZ_SEED_COUNT=25 ... etc

# 2. the full 200-seed campaign, 8 batches of 25, one JVM each (tools/fuzz/sweep.sh)
# 3. make fastTest && make test-fast
# 4. BsrFlushSkipSpec + flush_younger_*.s
# 5. full-core OOC synth gate
```

The predicted post-fix residue is 17 (10 cluster B + 6 cluster C + 1 cluster D). Any
number other than 17 is itself informative: **more** than 17 means cluster A was
masking further divergences behind the index shift (likely — an index shift aborts the
comparison at the first mismatch, so everything downstream in those 40 seeds is
currently unmeasured); **fewer** would mean a cluster was mis-assigned.

---

## Corpus blind-spot ledger

**A divergence count is only meaningful next to the list of things the corpus can see.**
This table must be updated alongside the count table at the top; a count of zero on a
corpus that cannot observe a whole result class is not really zero. **If closing a blind
spot makes the count go UP, that is progress, not regression.**

The comparator sees, per retired instruction: `pc`, full `sr`, `a7`/`msp`/`isp`, and
**the single arch register written by that instruction** — plus, at end of program, a
byte-compare of the sandbox window (`FuzzLockStepSpec.scala:454-469`). Anything else is
invisible. `WhiteboxCapture.onCommit`'s `emit` gate then *deletes* records:
`emit = (!isTempOnly && !wb.divRem) || wb.keepCommit`.

| ID | Blind spot | Status | Effect on count when closed |
|----|-----------|--------|------------------------------|
| BS-1 | **DIVREM (`Dr`) never compared at its own step.** `MicroOpAssembler.scala:2839` sets `divremUop.divIsRem := True`; `DivEuPlugin.scala:1503-1504` sets `divRem := compDivRem`, `keepCommit := False`. A long divide's remainder is only ever checked if a later instruction happens to read `Dr`. | **OPEN** | may increase |
| BS-2 | Branch-EU `keepCommit` missing from `BrWbObs` → `Scc <mem>` deleted entirely (cluster A). | **CLOSED this round** (unverified) | −40 predicted |
| BS-3 | **No final architectural register-file compare.** The end-of-program check is memory-only. Any wrong register that is never subsequently read, and never steers control flow, is invisible forever. | **OPEN** | may increase |
| BS-4 | `divIsRem` is reused as a *generic* crack-drop marker far beyond divide — `LsEuPlugin.scala:2732` (`compStkPush ‖ compCcrRestore ‖ compRmwStore ‖ compEaAutoDrop ‖ compCrackDrop`) and throughout the microcode/CPLX cracks. Only `A7` and `CCR` writes of a dropped µop are folded into running state (`WhiteboxCapture.scala:88-100`); a dropped µop writing **`An` where n≠7** (e.g. a source-EA `(An)+`/`-(An)` auto-update) is neither folded nor compared. | **OPEN, unquantified** | unknown |

**Correction to the EU audit's claim about `Dh`.** The audit reports that the corpus never
compares `Dh` after a 64-bit multiply. That does not match the source:
`MicroOpAssembler.scala:2990` sets `mulhiUop.divIsRem := **False**` (vs `:2839`
`divremUop.divIsRem := True` for the divide, which does match). So the MUL high-half µop
appears to be *emitted* as its own commit record, not dropped. I have not confirmed
whether `mulhiUop`'s destination is architectural (`Dh`, arch < 16 → emitted) or a temp
(arch ≥ 16 → suppressed by `isTempOnly` regardless of `divIsRem`), so this is flagged for
confirmation rather than asserted. **BS-1 (the divide remainder) is confirmed; the `Dh`
half of that claim is not.**

**BS-3 is the cheapest high-value corpus fix available** and should probably precede any
further RTL work: adding an end-of-program `D0-D7`/`A0-A7` compare against Musashi's final
state is harness-only, needs no RTL change, and would immediately make BS-1 and BS-4
observable instead of silent.

### On the audit's hypothesis for the 40-strong PC cluster

The audit offers the hypothesis that the 40 near-PC divergences may be *downstream
symptoms of unobserved wrong intermediate values*, surfacing first as a wrong branch
target. **The hypothesis is right about the family of defect and wrong about the
mechanism**, and the distinction matters because it changes what round 2 should do.

It is right that the cause is an observability gate — it is precisely an
`emit`/`keepCommit` blind spot, exactly the class the audit says to look for. But the
mechanism is *record deletion*, not *value corruption*: the `Scc <mem>` instruction emits
no commit record, so the retire stream is short by one and every later index is compared
against the wrong oracle step. No wrong intermediate value is implied anywhere, and no
upstream RTL bug is implied.

The evidence that discriminates the two is in seed 108's context block: at the shifted
index the DUT's record carries the *correct* result of the *next* instruction
(`or.l %d3,%d4` → `reg4=0xf7dbf83f`), and the streams re-align perfectly at `idx 33`. A
wrong-intermediate-value theory cannot produce a clean single-record deletion with every
surviving record correct and self-consistent. So this does **not** collapse into "wrong
values seen late" — it collapses into one missing bundle field, already fixed.

## Round 2 plan

0. Close **BS-3** (end-of-program register-file compare) FIRST — harness-only, no RTL
   risk, and it converts BS-1/BS-4 from silent to visible. Expect the count to RISE.
   Record the blind-spot ledger state next to any count so the number stays interpretable.
1. Execute the round-1 verification above; record the honest number in the table.
2. Cluster C first (6 divergences, and it is the only one that could be a *serious*
   silent RTL bug): trace `rob.logic.exc.ss.a7` on seed 60 to settle harness-vs-RTL.
3. Cluster D (1 divergence but silent corruption): confirm the `srcIsMem`/`MEMINDIRECT`
   path with an executed repro, then either route `Scc`-memind into `MI_RMW_ENTRY` or
   make it illegal — matching whatever the ISA requires, not whatever is convenient.
4. Cluster B (10 divergences): confirm the vector number, then decide route-vs-trap.
5. Re-run the campaign after each fix; never claim a count that has not survived a fresh
   full 200-seed run.
