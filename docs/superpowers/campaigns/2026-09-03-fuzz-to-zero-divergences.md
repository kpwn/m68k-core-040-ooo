# Campaign: fuzz lock-step to ZERO divergences

Branch: `fuzz/zero-divergence-campaign` (off `fmax-closure-fanout` @ `a5199bc`).
Goal (owner-set): drive the `FuzzLockStepSpec` campaign to **0 divergences** by fixing
every real RTL bug it exposes.

## Running divergence count

| Round | Date | Divergences / 200 | Delta | Notes |
|-------|------|-------------------|-------|-------|
| 0 (baseline) | 2026-09-01 | **57** | — | `fuzz_logs/*.log`, 8 batches of 25 seeds |
| 1 | 2026-09-03 | **20** | **−37** | MEASURED, full 200-seed sweep, 180 PASS. `fuzz_logs_round1/*.log` on this branch |
| 2 | 2026-09-03 | **13** | **−7** | MEASURED, full 200-seed sweep, 187 PASS. `fuzz_logs_round2/*.log`. TAS memory-indirect routing fixed + gate unification |
| 3 | 2026-09-03 | **5** | **−8** | MEASURED, full 200-seed sweep, 195 PASS. `fuzz_logs_round3/*.log`. Cluster C (A7 resync) fixed |
| 7 | 2026-09-03 | **4** | **−1** | MEASURED, full 200-seed sweep, 196 PASS. `fuzz_logs_round7/*.log`. Cluster E (seed 21) fixed |

### Round 7 measured result — cluster E CAPTURED, fixed, and one prediction retracted

**5 → 4.** Surviving: 57, 80, 109, 127. Seed 21 is gone.

The address was **captured, not inferred**. Rounds 4–6 established by program-level probing
that the EA arithmetic was fine and that only the immediate-source form failed, but also
established that a sandbox-only compare *structurally cannot* locate the store once
`An + bd` overflows 2^32 — every candidate address lands outside the compared region. So
this round tapped `LsEuPlugin`'s `s1Va` and its three input terms directly
(`ClusterEAddrCaptureSpec`), running both source forms in one program so only the source
operand varied:

```
imm source  pc=0x4080000c base=0xffff3ff0 disp=0x00010000 index=0xfffffffc -> s1Va=0x00003fec
reg source  pc=0x40800016 base=0xffff3ff0 disp=0x00010036 index=0xfffffffc -> s1Va=0x00004022
```

**The actual store address is `0x00003fec`.** It is neither of the two candidates round 6
had (invalidly) "refuted" — not `bd` ignored (`0xffff3fec`), not `bd` truncated to 16 bits
(`0xffff4022`). The long base displacement `0x00010036` decodes as `0x00010000`: it loses
its **low half-word**, and the correct high half proves the wraparound was never involved.

### Mechanism

`MicroOpAssembler`'s two immediate-shifted EA re-decodes — `immEa` (the line-0 immediate
mem-dest RMW address) and `immDstEa` (the `MOVE #imm,<mem>` destination) — hand-built a
**three-entry** words `Vec`. `EaDecoder`'s full-format path reads `words(2) ## words(3)` for
a LONG base displacement, and `EaDecoder.wAt` returns a hard `B(0,16)` for any index past
the `Vec` length — so `words(3)` read as constant zero.

The register-source form was always correct because it uses the unshifted `dstEa`, built
from the full 10-word window via `shiftedWordsFor`. And `DecodeStage.scala`'s own copies of
the same re-decode (`s0ImmEaVec`, `s1mi_immVec`) were **already four entries** and already
correct. The two `MicroOpAssembler` copies had silently drifted one word short — a
duplicated-logic divergence, not a design gap.

Fix (`85ff14e`): one shared `immShiftedWords` helper, six entries (ext word at 1, a LONG
`bd` at 2..3, a LONG outer displacement out to 5). Built as an explicit 2-way `Mux` per
element rather than through `shiftedWordsFor`'s dynamic index — the shift is one of exactly
two constants, so it keeps the same 2:1-mux-per-word cost as the `Vec`s it replaces instead
of putting a 10:1 dynamic select on the decode cone. No new predicate; nothing added to
`bad`.

Regression: `imm_src_fullfmt_long_bd_dst.s`, four cases (no wrap; seed 21's exact 2^32 wrap;
a line-0 `ORI.W` RMW exercising the *other* call site; a LONG immediate for the shift-2 leg),
each asserting both that the intended address WAS written and that the mis-decoded address
was NOT. Verified genuinely fail-before/pass-after — on unmodified RTL it fails at
`0xDEAD00A1`, case A's target never written.

### RETRACTION (fifth) — "seeds 21 and 57 share a root cause" is refuted

Round 6 predicted one fix would close both. **Measured: seed 57 diverges identically after
the fix** (`idx=35 pc: dut=0xeb3fe087 oracle=0x408000b4`). The doc had already flagged that
seed 57's probe crossed instruction families (`ORI` line-0 vs `OR` line-8) and was therefore
the weaker leg; it was. The mechanism now says so outright: seed 57's
`ori.w #0x254a,(20,%a0,%a2.l*2)` uses a **brief-format** extension word — one word — for
which a 3-entry `Vec` was always sufficient. Cluster E was never about "immediate source" as
such. It is specifically **a LONG base displacement behind an immediate**. Seed 57 is a
separate defect and stays open.

Worth naming as a pattern: the shared discriminator ("immediate form fails, register form
passes") was real and correctly measured at both sites, and it still did not imply a shared
cause. A discriminator localises; it does not identify.

### Round 7 verification

| Gate | Result |
|------|--------|
| Directed test, unmodified RTL | **FAILS** at `0xDEAD00A1` (case A's target never written) |
| Directed test, with fix | PASSES |
| `make test-fast` / `sbt fastTest` | **337 succeeded, 0 failed**, 2 ignored, 214 suites |
| `BsrFlushSkipSpec` | 17/17 |
| `flush_younger_*` (4 tests) | 4/4 |
| fuzz seed 21 | PASS |
| fuzz seed 57 | still DIVERGED (prediction refuted, see above) |
| Full 200-seed fuzz sweep | **196 PASS / 4 DIVERGED** — `fuzz_logs_round7/` |
| Ported corpus (partial) | **472 of 905 run, 19 failed, ALL in the FPU/FPSP/FSAVE families — zero non-FPU failures** |

The ported sweep was stopped at 472/905, not completed: four concurrent shards drove the
host ~17 GB into swap and three of them stalled on long-timeout tests (one carries a
3,000,000-cycle `.timeout`). It is reported as a partial, because that is what it is. Its
signal is still worth having — across 472 tests the failure set is *entirely* FPU/FPSP,
matching the documented pre-existing FMOVEM/FPU gap (task #229; the FPU corpus stands at
20/48), with **no** integer, decode, EA or exception test failing. A decode change is
exactly the kind that would show up there, and it did not. Someone re-running it should use
fewer shards, or `tools/fuzz/ported-sweep.sh` serially.

### SYNTH GATE — the fix is CORRECT but costs 13.1 MHz. DO NOT MERGE AS-IS.

Full-core OOC (`synth/ooc_M68kFullCoreSynth.tcl`, 5 ns primary), A/B against this branch's
own base under the *same current* constraints, so the stale-baseline problem does not apply:

| Netlist | WNS | FMax |
|---------|-----|------|
| `01e0682` branch base | **-0.607** | **178.35 MHz** |
| + cluster-E fix | **-1.052** | **165.23 MHz** (**-13.1**) |

Both sides are reproducible: the base measured -0.607 on two independent runs, and the fix
measured -1.052 on two *different* netlists (a first 6-entry attempt and the final 4-entry
one). The 6-vs-4 pair is itself informative — **bit-identical WNS**, i.e. Vivado prunes the
outer-displacement words, confirming they are dead logic and that Vec *width* is not the
cost. What costs is making `words(3)` of the shifted view LIVE at all.

Per-endpoint attribution (a second synth pass reporting the two families by name) shows the
change is **not** a simple regression — it moves two near-tied families in opposite
directions:

| Endpoint | base | fix | delta |
|----------|------|-----|-------|
| `LsEuPlugin_logic_s1Index_reg/R` | -0.607 | **+0.001** | **+0.608 (now MEETS)** |
| `RobPlugin_logic_faultDynMem_reg/RAMD` | -0.374 | **-1.052** | **-0.678** |

So the fix *retires* the family that was the base's WNS — plausibly causally, since
`immEa`/`immDstEa` feed `ldIdxEa`/`rmwIdxEa` and hence `s1Index` — and a previously-slack
family becomes the new critical path. `faultDynMem` is the ROB fault-capture Mem, i.e. the
**6-writer array of task #127**, already on record as the one remaining `Vec.fill(depth)(Reg)`
shape that fits none of the three proven fold patterns. Its 0.678 ns move has no logical
path to this change (`immEa`/`immDstEa` feed only `.disp` and the index descriptor — they do
**not** feed `bad`; that was checked, not assumed), so it is most likely a
resource-sharing/fanout shift rather than a lengthened cone.

**Consequence for landing:** this campaign has already measured-and-reverted a *correct* fix
for a 20 MHz cost once (`784b27b`), then recovered the whole regression by rematching the
predicate off the raw opword (`a9e0e6d`). The same play is the right next step here, and
this fix should **not** merge until it is either reformulated or task #127 is folded.
Recorded as measured, not as a blocker to the correctness result: seed 21 is genuinely fixed.

### CROSS-THREAD: the SoC boot blocker is a DIFFERENT bug — measured, not assumed

The boot-blocker thread (Part 121, SoC `3cd977fb`) proposed that its spurious `vec=0x04`
on `cmpi.l #$316D6567,%a0@(0xFEFFC)` (opword `0x0CB0`, ext `0x8170`), fuzz seed 57, and
cluster E were plausibly one immediate-source decode family — which would have meant one
fix closing all three. **Tested. They are not.**

1. The boot blocker **reproduces in this repo as a unit test** —
   `docs/superpowers/repros/cmpi_l_fullfmt_long_bd.s`, which installs a vector-4 handler so
   an illegal trap reports as itself. It fails with sentinel `0xDEAD0004`.
2. **The cluster-E fix does not fix it.** Sentinel unchanged with the fix applied.
3. Isolation (`docs/superpowers/repros/limm_long_fullfmt_illegal_probe.s`), single-variable,
   same opcode / same EA / same extension word, only the immediate size differing:

   | Case | Result |
   |------|--------|
   | `ORI.W #imm,(bd32,An,D0.L)` full format | **PASSES** |
   | `ORI.L #imm,(bd32,An,D0.L)` full format | **ILLEGAL** |

   So it is not `CMPI`-specific and not index-suppression-specific — it is **any line-0
   `.L` immediate with a full-format destination EA**.

4. That discriminator names the gate exactly. `MicroOpAssembler.scala:1575`:

   ```scala
   val limmFullFmtDstBad = opIsLineImm && immIsLong && !isBitOp && !isToCcr &&
                           limmDstIsFullEa && pkt.words(3)(8)
   ```

   `immIsLong` **is** the measured `.W`-passes/`.L`-traps discriminator. This is a blanket
   ILLEGAL gate feeding the global `bad`, not — as `DecodeStage.scala:695` claims — a
   "residual, much narrower edge case where extW3 itself is unavailable". Whatever made that
   comment true has stopped being true, or was never true for the MEMSIMPLE (`I/IS=000`)
   case. Note the gate's own doc comment (line 1559ff) says the .L form was gated
   deliberately because predecode framed it BRIEF and mis-fetched the following instruction
   — so the fix is not simply deleting the term; the framing has to be correct first.

**Relationship between the two bugs: sequential, not shared.** `limmFullFmtDstBad` rejects
the encoding before any address is computed. Cluster E's fix repairs the *address* for that
same encoding family. So this fix is **necessary but not sufficient** for the boot blocker:
relax the legality gate and the long-`bd` address bug fixed here is what would otherwise
have been waiting behind it. Whoever takes the gate should re-run the two repros above.

**Warning for that work:** `limmFullFmtDstBad` is a term *in* the global `bad` expression
(`MicroOpAssembler.scala:1935`). This campaign has measured `bad` at **50 MHz** for an added
term. Narrowing an existing term is not the same as adding one, but it must be re-gated.

### Same class, NOT fixed — carried forward

Five more call sites still pass 3-entry `Vec`s and would lose a long `bd` the same way for a
full-format source EA. None has a reproducer yet, so none was touched:

| Site | File |
|------|------|
| `divlSrcEa` | `MicroOpAssembler.scala` |
| `mullSrcEa` | `MicroOpAssembler.scala` |
| `bfmEaDec`  | `MicroOpAssembler.scala` |
| `c2SrcEa`   | `MicroOpAssembler.scala` |
| `ucCasEaDec`| `DecodeStage.scala` |

### Round 3 measured result — every remaining divergence is a named RTL bug

**13 → 5.** Surviving: 21, 57, 80, 109, 127. **All 8 cluster-C seeds fixed; zero harness
defects remain.**

| Seed | Cluster | Mechanism |
|------|---------|-----------|
| 80 | D | `Scc` memind writes `D<op[2:0]>` — **silent** wrong-register corruption |
| 109, 127 | B | line-E memory shift/rotate memind — needs `shiftOp`/`shiftDir` in µcode ctx |
| 21 | E | full-format indexed EA, `An + bd` overflows 2^32, DUT does not truncate |
| 57 | — | extension-word framing mis-shift (brief↔full, bit 8); candidate task #223 repro |

Campaign trajectory: **57 → 20 → 13 → 5**, every step attributed. Of the original 57,
**46 were harness defects** (clusters A and C) and **11 were RTL**; 6 RTL are now fixed
(all 8 TAS seeds mapped onto them) and 5 remain.


### Round 2 measured result

**20 → 13.** Surviving: 8, 13, 18, 21, 54, 57, 60, 64, 80, 82, 109, 127, 139.

| Cluster | Now | Note |
|---|-----|------|
| C (A7 harness artifact) | 8 | 8, 13, 18, 54, 60, 64, 82, 139 — untouched, harness fix deferred behind the divider agent |
| B — TAS memind | **0** (was 8) | all 8 fixed; seed 21's residual is a DIFFERENT bug (cluster E) |
| B — SHIFT-mem memind | 2 | 109, 127 — deliberately out of scope (needs new µcode ctx fields) |
| B — seed 57 framing | 1 | separate defect, candidate #223 reproducer |
| D — Scc memind | 1 | 80 — deliberately out of scope (needs condition eval in the engine) |
| E — 32-bit-wrapping full-format indexed EA | 1 | 21 — NEW, unmasked by the TAS fix |

**CORRECTION (self-caught, supersedes this doc's first characterisation of seed 21).**
I initially reported seed 21 as "TAS still writes a wrong value, so 7 of 8 TAS seeds
fixed". **That was wrong — I inferred it from the seed's shape instead of computing the
addresses.** Having now done the arithmetic:

- Seed 21's TAS is `tas ([0x17,%a0,%d1.l*4],0x4)` with `A0=0x4011`, `D1=0xffffffff`.
  Intermediate = `0x4011 + 0x17 + (-1*4)` = **`0x4024`**; `[0x4024] = 0x4015` (written by
  the program); final EA = `0x4015 + 0x4` = **`0x4019`**. **`0x4019` is NOT among the
  mismatching bytes** — the only mismatches are `0x4022`/`0x4023`. **The TAS is correct.**
- The mismatching bytes belong to a *different* instruction later in the same program:
  `move.w #0x80,(0x10036,%a3,%d6.l*4)` with `A3=0xffff3ff0`, `D6=0xffffffff`.
  `A3 + 0x10036` = `0x1_0000_4026` — **it overflows 32 bits** — then `+(-4)` truncates to
  **`0x00004022`**, storing word `0x0080` → `[0x4022]=0x00`, `[0x4023]=0x80`. **That is
  exactly what the oracle has.** The DUT left the stale prologue bytes (`0xbd`, `0x85`)
  there, so the DUT's store landed somewhere else.

**So all 8 TAS seeds are fully fixed, not 7.** Seed 21's residual is a **distinct,
pre-existing bug that the TAS wild-PC had been masking** — the third instance of the
unmasking pattern in this campaign.

### New cluster E — full-format indexed EA whose base displacement overflows 32 bits

**Seed 21** (1 divergence). `move.w #0x80,(0x10036,%a3,%d6.l*4)`: a full-format indexed EA
(the `0x10036` displacement needs a LONG base displacement, so bit8=1 with BD SIZE=11) in
which `An + bd` wraps past `2^32`. The 68k EA sum is modulo 2^32; the DUT appears not to
truncate (or mis-sizes the long `bd`), so the store lands at the wrong address —
**silently**, with no fault. Classified **RTL**, distinct from clusters B/D (this EA is
`MEMSIMPLE`, not `MEMINDIRECT`, so the classifier gap cannot reach it) and distinct from
seed 57 (brief format, no wraparound).

Lesson recorded against myself: *shape-matching a reproducer is not root-causing it.*
Both of this campaign's wrong calls so far came from inferring a mechanism from an
instruction's appearance rather than computing what it actually does.


Round-1 measurement provenance: `LOGDIR=fuzz_logs_round1 tools/fuzz/sweep.sh 0 200 25 20`,
8 batches of 25 seeds, one JVM per batch, run serially. 200 seeds run, 180 PASS,
20 DIVERGED. Predicted 17; actual 20, and the 3-seed difference is fully explained below
(it is unmasking, not regression).

### Post-round-1 cluster distribution (measured)

| # | Cluster | Baseline | Now | Change |
|---|---------|----------|-----|--------|
| A | `Scc <mem>` dropped from retire stream | 40 | **0** | **eliminated** |
| B | mem-dest RMW + memory-indirect EA → spurious exception | 10 | **11** | +1 unmasked (seed 22) |
| C | committed A7 transiently regresses after `movem` push/pop | 6 | **8** | +1 unmasked (seed 64), +1 newly exposed (seed 82) — **root-caused round 2: HARNESS** |
| D | `Scc ([bd,An],Xn,od)` writes `Dn` | 1 | **1** | unchanged |
| | **total** | **57** | **20** | **−37** |

Surviving seeds: 3, 4, 8, 13, 18, 21, 22, 41, 54, 57, 60, 64, 74, 80, 82, 103, 109, 126,
127, 139.

**38 of the 40 cluster-A seeds now pass outright.** The other two were carrying a *second*
bug that the index shift had been hiding, exactly as predicted — an index shift aborts the
comparison at the first mismatch, so everything downstream was unmeasured:

- **seed 22**: was `PC+2` (cluster A), now `idx=92 pc: dut=0x211cbad1 oracle=0x408001a8`
  (wild PC, cluster B). Its minimized program always contained
  `tas ([0x1f,%a2,%d1.l*2],0x4)` alongside the `sle (%a2)`.
- **seed 64**: was `PC+2` (cluster A), now `a7: dut=0x000ffffc oracle=0x00100000`
  (cluster C).

**seed 82 — newly diverging, and NOT a regression from this fix.** It passed at baseline
and now reports cluster C's exact signature (`idx 32` push clean, `idx 33` pop clean,
`idx 34` A7 regresses to the pre-pop value on an unrelated instruction). The decisive
evidence is its minimized body:

```
	movem.w %a2/%d2,-(%sp)
	movem.w (%sp)+,%a2/%d2
	lea (0xb0a3).l,%a5
```

**There is no `Scc` in it at all**, so the index-shift path this commit touches cannot
reach it; and this commit's RTL delta is a single observation register with no functional
feedback. The likely exposure mechanism is the project's documented sim gotcha that
*uninitialised Regs randomise per-seed* — adding a register perturbs the sim's
register-init stream, and cluster C is timing/state-sensitive.

The real conclusion is about cluster C, not about the fix: **cluster C's exposed-seed set
is not stable under benign perturbation, so 6 was an undercount and the true incidence is
≥ 8.** A purely deterministic reconstruction bug (like cluster A) would not behave this
way. That does not by itself prove cluster C is RTL rather than harness — a racy `ss.a7`
*sample* is also timing-sensitive — but it raises its priority and means any future count
for it should be treated as a lower bound.

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
| B | memory-destination RMW with a memory-indirect / full-format EA takes a spurious exception | **11** | **RTL** | root-caused round 2: missing `ucIsMemInd` classifier (same bug as D) |
| C | committed A7 transiently regresses one index after a `movem` `-(%sp)`/`(%sp)+` pair | **8** | **HARNESS** (round 2: `a7Static` resync replays a >=2-commit-lagged shadow) | root-caused, fix pending one executed test |
| D | `Scc ([bd,An],Xn,od)` writes `Dn` instead of memory | **1** | **RTL** | root-caused round 2: SAME bug as B |

Totals: 57. **As classified in round 1**: RTL = 11, harness = 40, undetermined = 6,
oracle-limitation = 0. **Superseded by round 2**, which root-caused cluster C as a
harness artifact too: **RTL = 11 (clusters B + D, one bug), harness = 46 (clusters A + C,
two separate bugs), oracle-limitation = 0, undetermined = 0.** So 46 of the original 57 —
81% — were defects in the measuring instrument, not the CPU.

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

## Verification status (round 1) — UPDATED after the measurement

**DONE:**
- **Directed before/after for cluster A**: seed 108 (`FUZZ_SEED_START=108 FUZZ_SEED_COUNT=1
  FUZZ_BLOCKS=20`) **DIVERGED at baseline** (`fuzz_logs/fuzz_100_125.log`) and **PASSes**
  on this commit (37.5s). This is a genuine fail-before/pass-after, on a deterministic
  byte-reproducible repro, not a merely-passing test.
- **Full 200-seed sweep**: 57 → **20**, recorded above.

**STILL OUTSTANDING** (blocked again — the divider agent started its full-core OOC synth
gate at ~17:33, so there are now 2 real `vivado -mode batch` builds and the
"never a heavy JVM during Vivado" rule applies again):
- `make fastTest`
- `make test-fast`
- `BsrFlushSkipSpec` + the four `flush_younger_*.s` corpus tests
- the standing full-core OOC synth gate for the 1-bit `BrWbObs` field

**Correct way to check whether the machine is clear** (a naive `pgrep -f vivado`
self-matches your own `bash -c` wrapper, because the wrapper's command line contains the
string, and will block forever):

```bash
ps -eo comm,args | awk '$1=="vivado" && /-mode batch/ && !/task_worker/' | wc -l   # 0 == clear
```

`comm` is the process's own binary name, so a shell wrapper can never satisfy `$1=="vivado"`.
`jtag_repl.tcl` sessions (`-mode tcl`), `hw_server` and `cs_server` are **not** build gates —
this project has an explicit documented correction on that point.

## Original pre-measurement verification notes (kept for the record)

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
| BS-1 | **DIVREM (`Dr`) never compared at its own step.** `MicroOpAssembler.scala:2839` sets `divremUop.divIsRem := True`; `DivEuPlugin.scala:1503-1504` sets `divRem := compDivRem`, `keepCommit := False`. A long divide's remainder is only ever checked if a later instruction happens to read `Dr`. | **BEING CLOSED BY THE DIVIDER AGENT** — not mine, do not duplicate. As of 2026-09-03 their work is *uncommitted* in the shared tree (`git status`: modified `WhiteboxCapture.scala`, `LockStep.scala`, `CommitObservation.scala`, `ExecuteLockStepSpec.scala`, `DivEuPlugin.scala`, `IssueQueuePlugin.scala`), adding an `archReg2Id/archReg2Write/archReg2Valid` "second architectural destination" fold that folds the dropped `divRem` tail's value into the kept step — covering BOTH `Dr` and the 64-bit MUL `Dh`. | may increase |
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

---

## Round 2 — clusters B and D ROOT-CAUSED (they are ONE bug)

**Result: B and D are the same defect, and it is a known, recurring, previously-fixed
defect class — a missing `ucIsMemInd` classifier.** Not a new bug class; the fourth
instance of one this project has already fixed three times.

### The mechanism

Routing a memory-indirect (`EaClass.MEMINDIRECT`) EA into the microcode engine requires
the instruction family to be named in **three parallel gate lists**, which must agree:

| Gate | File:line | Role |
|------|-----------|------|
| `slot0IsMemInd` | `DecodeStage.scala:729-746` | routes slot 0 into the engine |
| `slot1IsMemIndEarly` | `DecodeStage.scala:269-280` | routes slot 1 into the engine |
| `ucIsMemInd` | `DecodeStage.scala:1959` | the engine's own entry re-decode |

Each enumerates the same families: MOVE src/dst, ALU src (opmode 0/1/2), ALU dst
(opmode 4/5/6), ADDQ/SUBQ, line-0 immediate (`IMMEXT`), dynamic bit-op, single-EA, and
LEA/PEA/JMP/JSR. The single-EA member is:

```scala
// DecodeStage.scala:590 (and :244, :1919) — identical in all three
val s0IsSingleEa = (spec0.op === DecOp.CLR) || (spec0.op === DecOp.NEG) ||
                   (spec0.op === DecOp.NEGX) || (spec0.op === DecOp.NOT) ||
                   (spec0.op === DecOp.TST)
```

**`DecOp.TAS` is absent. `DecOp.SHIFT` (the line-E memory shift/rotate form) is absent.
`Scc <mem>` is absent.** Those are exactly — and only — the three families in clusters B
and D. When a family is unlisted, `ucIsMemInd` is false and the instruction falls through
to the ordinary non-microcoded fast-path assembler, whose EA machinery cannot walk a
memory-indirect pointer chain.

The consequence is already documented verbatim in this very file, for the family that was
missing *last* time (`DecodeStage.scala:1868-1873`, task #150, `add_l_dn_memind_dst`):

> "this shape had NO classifier reaching ucIsMemInd at all … so a full-format mem-indirect
> RMW dst-EA (opmode 4/5/6) fell all the way through to the ordinary non-microcoded fast
> path **with a garbage EA, producing a wild PC** (same class of bug as #144/#145)."

That is cluster B's symptom word for word. The garbage EA drives an access to an unmapped
address → bus-error/access-fault exception → 8-byte format-$0 frame (**explaining the
observed `a7 = 0x00100000 - 8`**) → vector fetched from the fuzz sandbox's *uninitialised*
vector page → **wild, often odd PC**. Every element of the observed signature is accounted
for.

### Why cluster D's symptom differs (and why it is the cleaner probe)

The coordinator predicted this exactly. `Scc` misses the classifier like the others, but
its fast-path fallback is *not* a garbage EA — the assembler has a dedicated Scc arm:

```scala
// MicroOpAssembler.scala:781 / :2553
val srcIsMem = (srcEa.klass === EaClass.MEMSIMPLE)      // MEMINDIRECT is NOT MEMSIMPLE
val sccIsMem = isSccOp && srcIsMem && !srcEa.pcRel
```

With `sccIsMem` false, the crack takes the `otherwise` arm — the **`Scc Dn` register form**
— and writes `D<op[2:0]>`. For seed 80's `scs ([0x11,%a5],%d1.l*4,0x0)`, `op[2:0]` is the
EA's *base-register* field (`%a5` → 5), so the condition byte lands in **D5**. This is
precisely "the EA/destination-form decode collapses a memory destination to a register
one". It also makes D the **most dangerous** finding: silent wrong-register corruption
with no trap, versus B's loud crash.

`isSccOp` additionally suppresses the illegal-instruction fallback that would otherwise
catch it (`MicroOpAssembler.scala:1890` ANDs in `!isSccOp`), so nothing downstream rescues it.

### Mapping to the 11 cluster-B seeds

| Instruction family | Seeds | Missing classifier |
|---|---|---|
| `tas ([...])` | 3, 4, 21, 22, 41, 74, 103, 126 | `DecOp.TAS` |
| `lsr.w`/`rol.w` `([...])` | 109, 127 | `DecOp.SHIFT` mem-form |
| `scs ([...])` (cluster D) | 80 | `Scc` mem-form |

### Seed 57 does NOT belong to cluster B — and may be task #223

Seed 57 is `ori.w #0x254a,(20,%a0,%a2.l*2)`: a **brief-indexed** EA, which `EaDecoder`
classifies `MEMSIMPLE`, **not** `MEMINDIRECT`. The classifier gap above cannot reach it,
and its family (line-0 immediate, `IMMEXT`) *is* already listed in all three gates. So it
is a genuinely separate defect.

Its shape — a memory-**destination** with a **brief-indexed** EA taking a spurious
exception — is the same shape as the long-open **task #223 `move_idx_idx`**
("context-dependent spurious illegal trap"), whose reproducer is
`move.b (0xa,A2,D1.w*8),(0,A3,D0.w)` — brief-indexed on both sides, also `MEMSIMPLE`.
**Recommend treating seed 57 as a candidate reproducer for task #223.** It is far smaller
than the existing one (4-instruction body vs a full matrix test) and it is deterministic.

#### Sharpened seed-57 hypothesis: an extension-word FRAMING mis-shift

Seed 57's body is 4 instructions and its signature is unchanged from baseline:

```
	move.l #0x2,%a2
	move.l #0x3ffe,%a0
	ori.w #0x254a,(20,%a0,%a2.l*2)
```

The intended EA is `A0 + A2.l*2 + 20` = `0x3ffe + 4 + 20` = **`0x4016`** — even, word-aligned,
inside the sandbox. Nothing about the intended access can fault. Yet the DUT takes an
exception (`a7 = 0x100000 - 8`) and vectors to a wild PC.

`ORI.w #imm,<ea>` is a line-0 immediate: the **immediate word precedes the EA extension
word**, so the EA must be re-decoded from a window shifted by `immWords` (the mechanism
`ucImmEaVec`/`ucImmWords` implements at `DecodeStage.scala:1904-1907`). Now compare the
two candidate words:

| Word | Value | bit 8 | Interpreted as |
|------|-------|-------|----------------|
| true brief ext for `(20,%a0,%a2.l*2)` | `0xAA14` | **0** | brief format, disp `0x14` |
| the immediate `#0x254a` | `0x254a` | **1** | **FULL format** (bd/od, possibly memory-indirect) |

Bit 8 is the brief/full-format selector. **If the EA re-decode is off by one word here, it
reads the immediate as the extension word, and bit 8 = 1 flips it from brief to
full-format** — a completely different EA shape with a bd/od chain, yielding a garbage
address → access fault → format-$0 frame → wild PC. That reproduces the observed signature
exactly, and it explains why the fuzzer found it with this specific immediate: most random
immediates with bit 8 clear would decode as a *brief* EA and merely produce a wrong (but
non-faulting) address, which the memory compare might or might not catch.

This is the same defect *class* as the documented task #152 bug in this very code path
("an unguarded check here misfired … mis-shifting its EA re-decode"). It is a concrete,
statically checkable lead: audit the `immWords` shift for the **brief-indexed** (mode 6)
destination case specifically.

**Negative result, stated plainly: cluster B proper is NOT task #223.** The coordinator's
lead was worth checking and it does not hold for the 10 memory-indirect seeds — those are
the classifier gap, a different mechanism from #223's brief-indexed/`MEMSIMPLE` shape. The
lead does appear to pay off for the one outlier.

### Corroboration: the test corpus correlates perfectly with the classifier list

I tried to *falsify* the hypothesis by looking for an existing passing memory-indirect
test covering TAS, shift-mem or Scc-mem. There is none — and the wider pattern is a
perfect one-to-one correlation. Every family **with** a classifier has a directed
memory-indirect test:

`clr_l_memind.s` (CLR) · `add_l_dn_memind_dst.s` (ALU dst, task #150) ·
`add_l_memind_src_dn.s` (ALU src) · `andi_l_memind_dst.s` (line-0 imm) ·
`b2_addq_subq_memind_null_od.s` (ADDQ/SUBQ) · `bit_ops_full_memind.s`,
`bit_dyn_indexed_memind.s` (bit ops) · `lea_memind_an.s`, `pea_memind.s`,
`jsr_mem_indexed.s`, `control_full_memind_siblings.s` (LEA/PEA/JMP/JSR)

Every family **without** a classifier has **no** memory-indirect test:

- no `tas_memind.s` — TAS memind is untested
- no shift/rotate memind test — `shift_mem_word.s` is the plain (`MEMSIMPLE`) memory form
- no Scc memind test — all six Scc tests (`scc_abs_long`, `scc_mem_an_indirect`,
  `scc_mem_indexed`, `scc_predec`, `scc_mem_incdec`, `scc_d16_an_disp`) are `MEMSIMPLE`
  forms, i.e. exactly the forms that already work

The corpus grew a test each time a classifier gap was found and fixed. The three families
with no test are precisely the three with no classifier — which is why six weeks of
regression runs never caught this, and why the fuzzer did.

**The fix must therefore ship with three new directed tests** — `tas_memind.s`,
`shift_mem_memind.s`, `scc_memind.s` — or the corpus keeps the same hole.

### Proposed fix (NOT YET LANDED — no executed repro yet)

Add the three missing families to all **three** gate lists (`:590`+`:738`, `:244`+`:269`,
`:1919`+`:1959`) — they must stay in lockstep or the engine mis-routes, which is the exact
trap task #144 documents. `TAS` and `SHIFT`-mem extend the single-EA list; `Scc` needs a
raw-opword classifier in the style of `s0IsLea`/`s0IsJmp`, because `Scc` is a branch-class
µop whose `spec.op` does not identify it.

Per this project's standing rule I have **not** landed this against a static-only
hypothesis. Verification plan, once a JVM is free: seed 80 (6-instruction body, cluster D,
cleanest) and seed 3 (6-instruction body, cluster B) must fail before and pass after, then
a full 200-seed re-sweep. Expect **20 → ~9** if the analysis is right (11 B/D seeds minus
seed 57 which stays).

---

## Round 2 — cluster C ROOT-CAUSED: a harness artifact (high confidence, one test from certain)

**Verdict: (a) harness reconstruction artifact**, not an RTL A7 rollback. The premise the
harness's resync rests on is **factually false in this RTL, and the harness file says so
itself two paragraphs later.**

### The false premise

`WhiteboxCapture.scala:98-103` asserts:

> "The committed ss.a7 (a7Static) tracks ONLY exception/RTE/boot A7 changes … (An OoO write
> leaves ss.a7 unchanged, so it never triggers a spurious resync.)"

That is obsolete. I verified the current RTL directly (not from comments — this repo has a
documented history of stale-comment-driven false positives):

```scala
// ExceptionUnit.scala:734 — UNCONDITIONAL, every cycle
ss.writeA7.valid  := True; ss.writeA7.payload  := committedA7In
```
```scala
// SystemState.scala:70 — ss.a7 is just a mux of those continuously-written banks
val a7 = Mux(s, supBank, usp)
```
```scala
// RobPlugin.scala:2629 — plus one more cycle of delay into the observation
obs(0).a7 := RegNext(exc.ss.a7)
```

`committedA7In` is the int-PRF readback at `committedPhysA7` (`FullCoreSynth.scala:541`,
`FuzzDut.scala:283`), allocated `forceNoBypass`. So `ss.a7` mirrors **every** A7 write
including MOVEM's — and lags by **≥2 commit cycles**. The lag is even documented inline at
`ExceptionUnit.scala:727-733` ("SETTLE CAVEAT … the ACTIVE bank tracks A7 with ~1-2 cycle
lag"), and `WhiteboxCapture.scala:123-126` — *in the same method as the false premise* —
already acknowledges it and papers over it **for `msp`/`isp` only** (`:129-130`), leaving
`a7Run` itself still driven by the stale path.

### Why it produces exactly this signature

`WhiteboxCapture.scala:110` force-resyncs `a7Run` on any **value edge** of the lagging
sample, and does so **before** the OoO fold at `:116`. So a record that carries its own
arch-15 writeback is immune (its fold overwrites the resync); only a record with **no** A7
write exposes the stale value. Replaying seed 82 with a 2-step lag:

| idx | insn | `a7Static` (lagged) | resync? | arch-15 fold | emitted a7 |
|-----|------|--------------------|---------|--------------|------------|
| 32 | movem push | 0x00100000 (stale) | no | 0x000ffffc | 0x000ffffc ✓ |
| 33 | movem pop | 0x00100000 (stale) | no | 0x00100000 | 0x00100000 ✓ |
| 34 | **lea** | **0x000ffffc** (push value, late) | **yes** | none | **0x000ffffc ✗** |
| 35 | next | 0x00100000 (pop value, late) | yes | – | 0x00100000 ✓ |

This reproduces the observed record exactly — including the one-step regression, the
self-correction, and the requirement for **two adjacent A7-changing retirements followed by
a non-A7 instruction**. That last condition is why it is rare (8/200) and why its seed set
moves under benign perturbation: it depends on commit *spacing* relative to the settle
window. My round-1 observation that C is timing-fragile is explained, and it points at the
harness, not the RTL.

### Negative result on the rename-exposure lead

**Cluster C is NOT the tasks #176/#194/#200 phys-reg-reuse hazard class.** MOVEM does not
use the `committedPhysA7` bypass at all — its A7 update is an ordinary rename-allocated INT
`ADD` emitted as the macro's last µop (`MicroOpAssembler.scala:140-181`,
`DecodeStage.scala:2702`). Freelist hygiene is sound: commit pushes the *old* phys while
`commReg(15)` takes `intNew`, and `commReg` is never rolled back on flush. The one
documented exposure window is exception-path-only and already mitigated by the
`sysOwnA7Valid` latch (`ExceptionUnit.scala:625-643`); the reproducer contains no
exception. The lead was worth checking and it does not hold.

### The discriminating test (zero instrumentation, no RTL change)

Append one A7-reading instruction to the reproducer:

```
	movem.w %a2/%d2,-(%sp)
	movem.w (%sp)+,%a2/%d2
	lea (0xb0a3).l,%a5
	move.l %sp,%d0
```

`D0` is compared through the `archReg` path, sourced from the **EU writeback observation**
(`WhiteboxCapture.onWb`), which is completely independent of `ss.a7`. If `D0 == 0x00100000`
while idx 34's `a7` field still reads `0x000ffffc`, the architectural A7 is provably intact
and only the harness regressed → confirmed (a). If `D0` is also wrong → genuine RTL → (b).

**Not fixed yet** — standing rule. The resync is load-bearing for the exception path, so
the fix must make it fire on an *event* (an `a7StaticValid`/`ExcRec` trigger) rather than on
a value edge, not simply be deleted.

### Spillover: an existing bug doc may rest on the same false premise

`docs/BUG_fmovemx_dataload_rob_commit_corruption.md:182-190` argues that a wrong `a7` on an
instruction that does not touch A7 proves genuinely wrong execution, on the grounds that the
harness's `a7` is "the live architectural A7 … a COMPLETELY SEPARATE piece of state". Separate
storage is true; *reliable per-step observation* is not — it is the same 2-commit-lagged
shadow replayed through the same resync. If that reproducer moves A7 twice in close
succession, its "untouched a7 is also wrong" evidence is explained by this artifact and
should be re-derived before being used to conclude wrong execution. **Flagged, not
adjudicated** — I have not re-run that case.

## Round 2 plan

0. **BS-3 is DEFERRED, deliberately — collision risk, not deprioritisation.** The obvious
   implementation (fold all 16 arch int registers in `WhiteboxCapture.result`, compare
   against `oracleSteps.last.d`/`.a` — the oracle already carries the full register vectors
   per step, so no RTL change is needed) edits `WhiteboxCapture.scala` and
   `FuzzLockStepSpec.scala`. The divider agent has **uncommitted** work in the shared tree
   in exactly `WhiteboxCapture.scala` + `LockStep.scala` + `CommitObservation.scala` +
   `ExecuteLockStepSpec.scala`. Landing BS-3 now would conflict with unversioned work and
   risk destroying it. **Sequence: let them commit first, then rebase and do BS-3.**
   Note their `archReg2` fold already covers the divRem-tail subset; BS-3 remains valuable
   for the broader case (any register written by a dropped µop, or never read again).
   Also flag: this branch edits `ExecuteLockStepSpec.scala` too, so expect a small merge
   conflict there with the divider agent's branch — trivial, different regions.
1. Execute the round-1 verification above; record the honest number in the table.
2. Cluster C first (6 divergences, and it is the only one that could be a *serious*
   silent RTL bug): trace `rob.logic.exc.ss.a7` on seed 60 to settle harness-vs-RTL.
3. Cluster D (1 divergence but silent corruption): confirm the `srcIsMem`/`MEMINDIRECT`
   path with an executed repro, then either route `Scc`-memind into `MI_RMW_ENTRY` or
   make it illegal — matching whatever the ISA requires, not whatever is convenient.
4. Cluster B (10 divergences): confirm the vector number, then decide route-vs-trap.
5. Re-run the campaign after each fix; never claim a count that has not survived a fresh
   full 200-seed run.

---

## Synth gate (round 2) — MEASURED, with a real regression, fully isolated

Full-core OOC synth (`synth/ooc_M68kFullCoreSynth.tcl`, 5 ns primary constraint), run on
four variants of the same tree so the delta is attributable rather than asserted:

| Variant | WNS (ns) | FMax (MHz) | Delta vs base |
|---------|----------|------------|---------------|
| `a5199bc` — branch point, none of my changes | −0.706 | **175.25** | — |
| `3eacf56` — + round-1 `BrWbObs.keepCommit` | −0.706 | **175.25** | **0.00** |
| `db86a83` with the TAS entry **removed** (gate unification only) | −0.706 | **175.25** | **0.00** |
| `db86a83` — full round-2 fix | −1.447 | **155.11** | **−20.14** |
| round-3: TAS matched from the **raw opword** instead | **−0.607** | **178.35** | **+3.10** |

### What this establishes

1. **Round 1's `BrWbObs.keepCommit` is exactly free** — bit-identical WNS. The prediction
   that a sim-only observation field costs nothing is now measured, not assumed.
2. **The gate unification refactor is exactly free** — also bit-identical. A Scala `def`
   inlines to the same hardware at each call site, so the structural fix (the thing that
   disarms the drift trap) carries **zero** timing cost. That is worth knowing: this class
   of cleanup can be applied elsewhere without a timing argument.
3. **The entire −20.14 MHz comes from one line**: `|| (spec.op === DecOp.TAS)`. The cost is
   *functional*, not stylistic — it is the price of actually letting TAS enter the µcode
   engine, which adds real logic and fanout on the decode critical path.

### Assessment — flagged, not chased

−20.14 MHz (−11.5%) against a project goal of ≥200 MHz is **not** a modest regression, so
per the campaign's standing guidance this is flagged for the owner rather than optimised
away. The trade currently on the table is: **8 wild-PC divergences (a real, silent-until-it-
crashes correctness bug) in exchange for 11.5% FMax.** Note the baseline itself (175.25)
is already below the 200 MHz goal on this OOC gate, so this change is not what breaks the
goal — but it does move it materially further away.

### Concrete optimisation hypothesis for whoever takes this (untested)

`spec0.op === DecOp.TAS` reads the **decoded, offloaded** spec, which arrives late. The
same file already decodes four families straight from the **raw opword** instead —
`s0IsLea`, `s0IsPea`, `s0IsJmp`, `s0IsJsr` (`DecodeStage.scala:~740`) — precisely because
the opword is available earlier. TAS has a trivially decodable encoding:

```
TAS <ea> = 0100 1010 11 mmm rrr     -> opw(15 downto 6) === B"0100101011"
```

So replacing the `spec.op` comparison with a raw-opword match, in the style of the existing
`s0IsJmp`, is a plausible way to recover most or all of the 20 MHz **without giving up the
fix**. **MEASURED (round 3): the hypothesis held.** Replacing `spec.op === DecOp.TAS` with
`opw(15 downto 6) === B"10'b0100101011"` recovered the entire regression:
**155.11 → 178.35 MHz**, i.e. **+23.24 MHz**, landing **3.10 MHz above the original
baseline** — with the TAS fix fully intact (`tas_memind.s` PASS, seeds 3/4 PASS,
`make test-fast` 337/337).

Honest caveat on the +3.10: this should **not** be read as "adding TAS made the core
faster". Vivado is deterministic for identical input (three separate runs all returned
exactly −0.706), but a netlist perturbation can shift which path is critical and how
optimisation proceeds. The defensible claim is **"no timing regression; measured slightly
above baseline"** — not a speedup attributable to this change.

The transferable lesson: **in this decode stage, hanging a new term off the registered/
offloaded `spec` costs real FMax, while matching the same condition from the raw opword is
free.** That is why `s0IsLea`/`s0IsPea`/`s0IsJmp`/`s0IsJsr` are written the way they are,
and it is the pattern to follow when the remaining families (SHIFT-mem, Scc) are added.

---

## Round 4 — cluster D "fault loudly" stopgap: BUILT, MEASURED, REVERTED

**Negative result. The stopgap works functionally and costs too much to keep.**

### What was built

Divert `Scc <memory-indirect EA>` to the illegal path (vector 4) so it traps instead of
silently writing `D<op[2:0]>`. Functionally it did exactly what was intended — seed 80's
signature changed from

```
idx=79 reg D5: dut=0x00000000 oracle=0x0000007e      <- SILENT register corruption
```
to
```
idx=79 pc: dut=0x76ab2465 ... a7=0x000ffff8          <- loud vector-4 trap, format-$0 frame
```

and all nine working `Scc` forms kept passing (`scc_abs_long`, `scc_basic`,
`scc_d16_an_disp`, `scc_mem_an_indirect`, `scc_mem_byte`, `scc_mem_forms`,
`scc_mem_incdec`, `scc_mem_indexed`, `scc_predec`) plus `tas_memind`, and
`make test-fast` stayed 337/337.

### Why it was reverted — two measurements

| Formulation | WNS | FMax | vs 178.35 |
|---|---|---|---|
| term added to the global `bad` expression | −2.812 | **128.01** | **−50.34** |
| confined to a local override inside the `Scc` block | −1.520 | **153.37** | **−25.0** |

The first formulation put `srcEa.klass === MEMINDIRECT` into `bad` — a very wide AND/OR
tree feeding the illegal path — and cost **50 MHz**. Confining the same test to a small
local override at the tail of the `when(isSccOp)` block recovered half of it, but **25 MHz
(−14%) remained**.

**The trade was rejected**: the stopgap does **not** fix the bug. Both the old and new
behaviours are architecturally wrong (a real 68040 executes `Scc` with a memory-indirect
EA); it only converts a silent wrong answer into a loud one. Paying 14% FMax for a change
with no correctness benefit, when the real fix is a µcode entry that would not touch these
paths at all, is a bad deal — and hunting a third formulation would be exactly the
design-contortion this campaign has been told to avoid. Reverted; `MicroOpAssembler.scala`
is back to unmodified.

### The transferable finding (this is the part worth keeping)

Round 3 established *match the raw opword, not `spec.op`*. Round 4 generalises it:

> **In this decode stage, WHERE a term is added matters more than what it tests.**
> The identical predicate cost 50 MHz in the global `bad` expression, 25 MHz in a local
> block override, and (as the TAS case showed) 0 MHz when derived from the raw opword and
> consumed by a narrow gate.

`bad` is now known to be timing-critical and should be treated as closed to new terms.

### Recommendation

Go **straight to the real fix** — a dedicated µcode entry for `Scc` memory-indirect that
evaluates the condition in the branch EU and stores the byte through the engine's
`host-store` row, in the style of `MI_RMW_ENTRY` but with a condition source instead of an
ALU host-op. That routes the instruction correctly (removing the silent corruption as a
side effect) and, because it adds no term to `bad` or to any late `spec`-derived
expression, should be timing-neutral. **Until it lands, seed 80's silent D-register
corruption remains open and known** — recorded here rather than papered over.

---

## Round 5 — two negative results (one of them against my own earlier claim)

### 1. CMP2 (task #257) and cluster E do NOT share a root cause

**Checked by computing the addresses, not by matching shapes.** They do not overlap, and
fixing cluster E will **not** close task #257.

The failing test is `cmp2.w (-4,%a0,%d2.l),%d1` with `A0=0x3000`, `D2=4`
(`ExecuteLockStepSpec.scala:6400`). Its EA is:

```
0x3000 + (-4) + 4*1 = 0x3000      -- no overflow anywhere, every term small and positive
```

So the 2^32 wrap that defines cluster E is simply **not present** in the CMP2 case. The
shape match ("indexed EA → wild PC") was misleading, exactly as warned.

The actual mechanism is a **deliberate, documented fail-safe**, not a bug:

```scala
// MicroOpAssembler.scala:3233-3235, 3332
val c2IndexedShape = (c2Mode === B"110") || ((c2Mode === B"111") && (c2Reg === B"011"))
val c2EaOk  = (c2SrcEa.klass === EaClass.MEMSIMPLE) && (c2SrcEa.autoMode === EaAuto.NONE) &&
              !c2IndexedShape
val c2Bad = isCmp2Chk2Op && !c2EaOk
```

with the in-code rationale: the CMP2/CHK2 2-load+compare crack *"only forms a straight
base+disp address (it never reads an index register, walks a full-format bd/od chain, or
follows memory-indirection)"*, so indexed shapes are **forced illegal on purpose** —
*"Force them illegal here as a fail-safe until the crack actually implements index/
full-format EA compute"*. Mode 6 is `c2IndexedShape`, so the test's EA is rejected →
vector 4 → and because the lock-step harness leaves the vector table uninitialised, the
handler PC is garbage: the observed `dut=0x990f1a31`.

**So task #257 is an UNIMPLEMENTED FEATURE with an explicit in-code TODO, not a mystery
and not an address-arithmetic defect.** Closing it means implementing index/full-format EA
computation in the CMP2/CHK2 crack — self-contained, well-marked work, unrelated to
cluster E. The paired test `chk2_cmp2_illegal_ea_traps.s` documents the current
fail-safe behaviour, so that test's expectations must move with any fix.

### 2. RETRACTION: cluster E is not "the DUT doesn't truncate"

I characterised cluster E as *"the 68k EA sum is mod 2^32; the DUT appears not to
truncate"*. **That was an inference from the symptom, and the code contradicts it.** The
LS-EU address adder is:

```scala
// LsEuPlugin.scala:440
val s1Va = (s1Base.asSInt + s1Disp + s1Index.asSInt).asUInt
```

Three 32-bit terms summed as `SInt(32 bits)`. SpinalHDL truncates that by construction —
there is no widening and no carry-out to lose. **The AGU already computes mod 2^32**, so
"fails to truncate" cannot be the mechanism.

What is actually established about seed 21 is only this: the DUT's
`move.w #0x80,(0x10036,%a3,%d6.l*4)` did **not** land at `0x4022` (the oracle's, and the
architecturally correct, address), because the stale prologue bytes survive there. **Where
it did land is unknown.** Plausible remaining candidates — none verified — are the framing
or sign/zero-extension of the **long base displacement** (`0x10036` needs BD SIZE=11, a
full 32-bit `bd`), or the routing of a MOVE with an *immediate source* and a *full-format
indexed destination* (a shape with prior history here: task #178 needed a dedicated
`MI_MOVE_DST_IMM_ENTRY` for its memory-indirect sibling). Note `limmFullFmtDstBad` is
**not** it — that gate is `opIsLineImm && immIsLong`, and seed 21's instruction is line-3
`MOVE.W`, not a line-0 immediate.

**Next step for cluster E is a trace, not a patch**: run seed 21 under `FUZZ_PROG` and
capture the actual store address. Fixing the truncation that already exists would be
fixing nothing.

**Third self-retraction of this campaign, same failure mode each time**: reasoning from an
instruction's appearance instead of from what the hardware computes. Logged again.

### FMax baseline is now STALE — do not compare across it

Commit `1cc57891` (another agent) fixed four real defects in `synth/vivado.tcl`'s
constraint handling, including a `set_bus_skew` matching 0 of 30 instances and clock
groups built against an empty clock object. **Every FMax number in this document
(175.25 / 155.11 / 178.35 / 128.01 / 153.37) was measured BEFORE that landed** and is
self-consistent only within that set. Any future measurement must **re-baseline** — i.e.
re-run the branch point under the new constraints — before a delta is attributed to a code
change. Otherwise their constraint fix will be misread as this branch's regression or win.

---

## Round 6 — cluster E ISOLATED by measurement (no theorising this time)

Per the standing correction against myself, I did not name a mechanism. I ran a probe that
makes the DUT *report its own computed address*, then a second that varies one thing.

### Probe 1 — does the EA computation itself work?

```
	move.l #0xffff3ff0,%a3
	move.l #0xffffffff,%d6
	lea    (0x10036,%a3,%d6.l*4),%a0
	move.l %a0,(0x4000).l              <- publish the DUT's OWN computed EA
	move.w #0x80,(0x10036,%a3,%d6.l*4) <- the failing store, same EA
```

Result: the only divergences are `mem[0x4022]`/`[0x4023]`. **`mem[0x4000..0x4003]` does NOT
diverge**, so the DUT's `lea` of that exact full-format indexed EA produced **`0x00004022`,
the architecturally correct address**, and wrote it correctly.

**Therefore: long base-displacement framing is NOT broken.** That hypothesis is dead — the
same `bd`, index, scale and wraparound resolve correctly through `lea`.

### Probe 2 — is it the immediate source?

Same destination EA shape, two stores, two distinct target addresses:

```
	move.w #0x80,(0x10036,%a3,%d6.l*4)   -> 0x4022   (immediate source)
	move.w %d1,(0x10036,%a4,%d6.l*4)     -> 0x402a   (register source)
```

Result: `0x4022` diverges, **`0x402a` does not**.

| Form | full-format indexed dst | outcome |
|------|------------------------|---------|
| `lea` of the EA | computes `0x4022` | correct |
| `move.w %d1,<ea>` (register source) | stores at `0x402a` | correct |
| `move.w #imm,<ea>` (immediate source) | — | **wrong address** |

### Established fact

**`MOVE #imm,<full-format indexed destination>` computes the wrong destination address.**
Not the EA arithmetic, not the wraparound, not the long `bd` — those all work. It is
specific to the **immediate-source** form.

This is the exact shape of the **task #178** precedent, which needed a dedicated
`MI_MOVE_DST_IMM_ENTRY` because the plain `MI_MOVE_DST_ENTRY` *"reads the 'other' side as
a REGISTER (wrong for an immediate source)"* (`DecodeStage.scala`, `ucMoveDstMiImmEarly`).
Task #178 fixed that for the **memory-indirect** destination. **Cluster E is the same
defect one class over: the `MEMSIMPLE` full-format indexed destination**, which never went
through the µcode engine and so never got the equivalent treatment.

The mechanism is almost certainly extension-word framing: with an immediate source the
immediate words precede the destination EA's extension words, so the dst EA must be decoded
from a shifted window, and a full-format EA (long `bd` = 2 extra words) shifts differently
from the brief form. **Stated as the leading candidate, not as established** — what is
established is the three-way table above.

### Testable prediction linking cluster E and seed 57

Seed 57 is `ori.w #0x254a,(20,%a0,%a2.l*2)` — also **immediate source + indexed
destination**, and its failure signature was independently traced to a suspected
brief↔full extension-word mis-shift (bit 8 of the word being read as the EA extension).
Cluster E is now measured to be immediate-source-specific with an indexed destination.

**Prediction:** these two share a root cause (immediate-source destination-EA framing),
differing only in decode site — line-3 `MOVE` for seed 21, line-0 immediate for seed 57.
**Test it** by re-running seed 57's shape with a register source (`or.w %d1,(20,%a0,%a2.l*2)`):
if that passes while the `ori.w` form fails, the same discriminator holds and one fix
plausibly closes both (2 of the remaining 5). Unlike this campaign's earlier bad
shape-matches, this prediction comes with a measured discriminator and an explicit way to
falsify it — but it is **not yet confirmed**.

### Prediction TESTED and CONFIRMED — seeds 21 and 57 share a class

```
	move.l #0x2,%a2
	move.l #0x4006,%a1
	move.w #0x3333,%d1
	or.w   %d1,(20,%a1,%a2.l*2)        idx 6  <- REGISTER source   -> PASSES
	move.l #0x3ffe,%a0
	ori.w  #0x254a,(20,%a0,%a2.l*2)    idx 8  <- IMMEDIATE source  -> idx=8 pc: dut=0xc6e7d967
```

The register-source store at idx 6 retires cleanly; the immediate-source form at idx 8
faults to a wild PC. Same discriminator as cluster E, one decode family over.

**Combined result — the same class, two decode sites:**

| Seed | Instruction | Dst EA | Symptom |
|------|-------------|--------|---------|
| 21 | `move.w #imm,<ea>` (line 3) | full-format indexed | silent **wrong address** |
| 57 | `ori.w #imm,<ea>` (line 0) | brief indexed | **fault** → wild PC |

Both are *immediate operand followed by an indexed destination EA*; in both, the
register-operand counterpart works. **One fix plausibly closes both — 2 of the remaining 5.**

**Caveat, stated because it weakens the second test.** Seed 21's probe was clean: both
stores were `MOVE.W` with the same destination shape, and *only the source type* varied —
a true single-variable experiment. Seed 57's probe crosses instruction families (`ORI`
line-0 vs `OR` line-8), so it establishes "the immediate FORM fails where the register form
works" but does not isolate the difference to the source operand alone as tightly. The
shared-class conclusion rests mainly on seed 21's clean result plus the matching signature;
treat "one fix closes both" as **likely, not proven**, until the fix is actually attempted
and both are re-run.

---

## METHOD: the "make the DUT publish" probe

Named here because it is the technique that finally located cluster E after three rounds
of reasoning-from-shape had mislocated it (twice by me, once in review), and because this
campaign has established that **unobservable intermediates are its dominant failure mode**:

- lock-step never compared `Dr` (a real wrong-remainder bug survived every suite);
- the harness silently *deleted* every `Scc <mem>` retire record (40 of 57 divergences);
- the committed A7 was a ≥2-commit-lagged shadow replayed as if live (8 more);
- a CDC `set_bus_skew` check was matching 0 of 30 instances.

Four separate cases of "the thing was never actually looked at". The general antidote:

> **Force the DUT to write its own intermediate value into architectural state the
> comparator already checks, then vary exactly one input.**

For an address bug: compute the same EA with `lea` and store the result somewhere compared.
The oracle computes the architecturally correct address, so the comparator does the
diffing for you — no new instrumentation, no waveform, no RTL change.

```
	lea    (0x10036,%a3,%d6.l*4),%a0
	move.l %a0,(0x4000).l                <- the DUT publishes its OWN computed EA
	move.w #0x80,(0x10036,%a3,%d6.l*4)   <- the failing store, identical EA
```

`mem[0x4000..0x4003]` matching while the store missed is what proved the EA *arithmetic*
was fine and moved the search to the immediate-source path. Then one further single-variable
step (register source vs immediate source, same instruction, same destination shape) closed
it. Generalises to any wrong-value divergence whose intermediate is otherwise invisible.

### Its limit, learned the hard way in this same round

The probe only sees what the comparator covers — here, the sandbox `0x4000..0x403f`. I then
tried to locate the DUT's *actual* store address by choosing `A3` so that a candidate
mis-computation would land inside the sandbox, and recorded two "refutations":

| Candidate | Predicted address | Probe result |
|-----------|-------------------|--------------|
| `bd` truncated to 16 bits | `0x4020` | passed → "refuted" |
| `bd` ignored entirely | `0x4020` | passed → "refuted" |

**Both refutations are INVALID and are retracted here.** To bring each candidate inside the
sandbox I set `A3` to a small value (`0x3fee`, `0x4024`) — which **removes the 2^32
overflow** (`A3 + bd` wrapping) that is the defining feature of the failing case. Those runs
exercised a *different, probably-working* configuration; their passing says nothing about
the bug. Same failure mode as the three earlier retractions, one level up: I varied a term
that I had already established was load-bearing.

With the overflow present, every candidate is unreachable by a sandbox-only compare:

```
A3=0xffff3ff0, bd=0x10036, index=-4
  correct EA         = 0x00004022   (in sandbox)
  bd ignored         = 0xffff3fec   (outside — invisible)
  bd truncated to 16 = 0xffff4022   (outside — invisible)
```

**Therefore: locating cluster E's actual store address requires a direct capture of the
store address (D-cache AXI write channel or the LS-EU `s1Va`), not another program-level
probe.** That is the next step; further candidate-guessing is explicitly the wrong move.

**Established about cluster E (unchanged, all measured):** the EA arithmetic is correct via
`lea`; a register-source store to the same destination shape is correct; only
`MOVE #imm,<full-format indexed destination>` is wrong; the same discriminator holds for
seed 57's `ori.w` (line-0) form. **Not established:** where the store actually goes, and
therefore the mechanism.

---

## TASK SCOPE: `MI_SCC_ENTRY` — `Scc <memory-indirect EA>` (fuzz seed 80, cluster D)

Written to be picked up by someone else; this will likely outlive the current session.
**Priority: highest of the remaining set after cluster E** — it is the only *silent*
failure left. Everything else announces itself with a fault, a hang or a wild PC.

### The bug

`Scc <ea>` with a full-format memory-indirect EA writes the condition byte into
**`D<op[2:0]>`** — `op[2:0]` being the EA's *base-register* field, not a destination at all.
Seed 80's `scs ([0x11,%a5],%d1.l*4,0x0)` silently clobbers `D5`. No trap, no hang.

Mechanism (established, `MicroOpAssembler.scala`):
`sccIsMem = isSccOp && srcIsMem && !srcEa.pcRel` with `srcIsMem = (srcEa.klass === EaClass.MEMSIMPLE)`
(`:781`, `:2553`). `MEMINDIRECT` is not `MEMSIMPLE`, so the crack falls into the `Scc Dn`
arm at `:2586`. `isSccOp` also suppresses the illegal fallback (`:1890`), so nothing
downstream catches it.

### Why it needs a new µcode entry

`Scc` cannot ride `MI_RMW_ENTRY` (rows 24-27: ptr-load / host-load→T1 / **host-op**→T1 /
host-store). That entry's op row is `UMiHostOp`, which does `u.op := ctx.miOp` — an
**ALU/unary** op. `Scc`'s result is a *condition evaluation*, which lives in the **branch
EU**, and the µcode host-op model has no path to it. This is why the family was never
routed and why it needs its own entry, in the manner of task #201's dedicated
`MI_LEA_ENTRY`/`MI_PEA_ENTRY`/`MI_JMP_ENTRY`/`MI_JSR_ENTRY` (each of which sits *outside*
the §7 host-op chain for the same reason). **Calibration: comparable in size to task #201.**

### Work items

1. **`Microcode.scala`** — add `MI_SCC_ENTRY` with rows: ptr-load → T0; evaluate `cond`
   (`cccc` from the opword) → condition byte → T1; host-store T1.B → `T0 + od (+ post-index)`.
   The condition source is the new part: either give the entry a branch-class row, or carry
   the evaluated condition into the context. Reuse `MI_RMW_ENTRY`'s ptr-load and host-store
   rows verbatim; only the middle row is new.
2. **`DecodeStage.scala`** — add an `ucSccMi` classifier and add it to all **three** gates
   (`slot0IsMemInd`, `slot1IsMemIndEarly`, `ucIsMemInd`) via the existing shared-predicate
   header, plus an arm in the `ucMiEntry` Mux chain.
3. **`MicroOpAssembler.scala`** — no change expected: routing into the engine happens
   *before* the fast-path assembler sees the instruction (the documented LEA/PEA/JMP/JSR
   precedent at `:1630`). Verify rather than assume.
4. **Test** — `scc_memind.s`, in the style of the existing `scc_*.s` set, covering
   no-index / pre-indexed / post-indexed and a non-zero outer displacement. Must FAIL before
   the fix (check it in a pre-fix worktree — a decode-gate omission can pass for the wrong
   reason).
5. **Re-measure** the full 200 seeds; seed 80 should clear.

### Constraints that shape the fix (learned expensively, rounds 3-5)

- **Do NOT add a term to the global `bad` expression** in `MicroOpAssembler`. Measured cost
  of one predicate there: **−50 MHz**. In a local block override: **−25 MHz**. Off the raw
  opword into a narrow gate: **0 MHz**.
- **Match predicates off the RAW OPWORD, not `spec.op`.** `isSccOp` already is
  (`isLine5 && ss5===3 && ...`), so reuse it rather than introducing a `spec`-derived test.
- Route through the µcode engine; that is now the only permitted path anyway.
- **Do not attempt the "make it trap instead" stopgap** — it was built, measured and
  reverted in round 4 (−25 MHz for zero correctness movement, since both behaviours are
  architecturally wrong). See that section before re-proposing it.

### Definition of done

Seed 80 passes; `scc_memind.s` fails before and passes after; all nine existing `scc_*.s`
still pass; `make test-fast` clean; `ExecuteLockStepSpec` no worse than the 2 known
pre-existing failures; OOC synth gate re-baselined post-`1cc57891` and reported honestly.
