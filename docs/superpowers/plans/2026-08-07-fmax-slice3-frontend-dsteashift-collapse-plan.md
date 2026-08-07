# FMax closure Slice 3: collapse slot-1 dst-EA chained shifts — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Collapse slot-1's TWO chained 10-way dynamic barrel-shift muxes
(Aligner's `L0` shift, then `MicroOpAssembler.computeOffload`'s independent
`dstShift` shift, re-indexing an already-shifted array) feeding
`Offload.dstEa` into ONE mux on the summed shift `L0+dstShift`, applied
directly to the raw IBuf window. Closes the
`ibuf/entries_*_pred_lenWords_reg -> ..._specs_1_dstEa_disp_reg` critical
path family identified after Slice 1 (-1.986ns post-route).

**Architecture:** Thread slot-1's raw (pre-Aligner-shift) window and `L0`
through `Aligner.Result` -> `DecodeFeedService` -> a new 3-arg
`MicroOpAssembler.computeOffload` overload, used ONLY for slot1. Pure
algebraic refactor (byte-identical `dstEa` output) — zero IPC cost, zero
behavior change for src-EA/spec/header (untouched) or slot0 (untouched,
still uses the existing 1-arg overload).

**Tech Stack:** SpinalHDL/Scala, sbt, ScalaTest (SpinalSim), Vivado
(`synth/impl_FullCore.tcl` for the post-route gate).

## Global Constraints

- Design spec (read in full before starting):
  `docs/superpowers/specs/2026-08-07-fmax-slice3-frontend-dsteashift-collapse-design.md`
- The correctness invariant is BINDING: the new 3-arg `computeOffload`'s
  `o.dstEa` output must be byte-for-bit identical to the old 1-arg version's,
  for every reachable `(L0, dstShift, words)` combination. This is a pure
  refactor — any observable difference is a bug, not an acceptable
  trade-off.
- `o.spec`/`o.srcEa`/`dstEaField`/`dstShift`'s own computation is UNCHANGED
  — only the final `shiftedWordsFor` call's input array/shift amount
  changes.
- Slot0's `computeOffload` call (`DecodeStage.scala:88`) and the 1-arg
  overload's body are UNTOUCHED.
- `DecodePacket` itself gains NO new fields — the raw-words/L0 threading
  lives on `Aligner.Result`/`DecodeFeedService` only.
- `~/sbt/bin/sbt compile` must stay clean throughout.
- `AlignerSpec` 9/9, `FetchAlignSpec` same 1 pre-existing unrelated failure
  (confirmed in Slice 1's ledger) — do not accept a different count.
- `ExecuteLockStepSpec` must stay at 390/394.
- Full ported corpus (~870 tests) must show zero new regressions vs. the
  current baseline (post-Slice-1: 812 pass / 58 fail, or Slice 2's
  post-landing baseline if Slice 2 lands first — check
  `.superpowers/sdd/progress-fmax-p0live.md` / this slice's own ledger for
  the actual current baseline at implementation time).
- Both OOC-synth-only AND the real post-route gate
  (`synth/impl_FullCore.tcl`) are required. Report the measured FMax delta
  explicitly.

---

### Task 1: Thread raw words/L0 through to slot1's `computeOffload`, verify, synth-gate

**Files:**
- Modify: `src/main/scala/m68k040/frontend/Aligner.scala` (extend `Result`,
  populate the 2 new fields in `align()`, around lines 10-18 and 206-220)
- Modify: `src/main/scala/m68k040/services/Services.scala` (extend
  `DecodeFeedService`, around lines 178-180)
- Modify: `src/main/scala/m68k040/frontend/FetchAlignPlugin.scala` (wire
  the 2 new service members from `res.slot1RawWords`/`res.slot1L0`)
- Modify: `src/main/scala/m68k040/decode/MicroOpAssembler.scala` (add the
  3-arg `computeOffload` overload, around lines 460-472)
- Modify: `src/main/scala/m68k040/decode/DecodeStage.scala` (change the
  slot1 call site, line 89)
- Test: locate the file(s) covering `computeOffload`/`Offload` directly
  (grep `computeOffload` across `src/test/scala/`) and
  `AlignerSpec.scala`/`FetchAlignSpec.scala` for regression coverage.

**Interfaces:**
- Consumes: `Aligner.align()`'s existing `words: Vec[Bits]` parameter (the
  raw WINDOW=10-wide IBuf window) and its existing local `L0: UInt` (line
  86) — both already in scope inside `align()`, no new computation.
- Produces: `Aligner.Result.slot1RawWords: Vec[Bits]` (WINDOW=10 wide,
  `Bits(16 bits)` each), `Aligner.Result.slot1L0: UInt(4 bits)`;
  `DecodeFeedService.slot1RawWords: Vec[Bits]`,
  `DecodeFeedService.slot1L0: UInt`; `MicroOpAssembler.computeOffload(pkt:
  DecodePacket, rawWords: Vec[Bits], l0: UInt): Offload` (new overload,
  `Offload` return type unchanged — confirm its exact definition via grep
  before writing test code that constructs/inspects one).

- [ ] **Step 1: Locate and read the exact current line numbers**

```bash
grep -n "case class Result\|def align\b\|r.slot1.words\|val L0" src/main/scala/m68k040/frontend/Aligner.scala
grep -n "trait DecodeFeedService\|def feed\|def slot1Valid" src/main/scala/m68k040/services/Services.scala
grep -n "def computeOffload\|def assemble\b" src/main/scala/m68k040/decode/MicroOpAssembler.scala
grep -n "fedIn.payload.specs" src/main/scala/m68k040/decode/DecodeStage.scala
grep -n "extends DecodeFeedService\|class FetchAlignPlugin" src/main/scala/m68k040/frontend/FetchAlignPlugin.scala
```
Re-verify the design spec's citations still match before editing.

- [ ] **Step 2: Extend `Aligner.Result`**

In `Aligner.scala`, add to `case class Result()` (around line 10-18):
```scala
  case class Result() extends Bundle {
    val slot0      = DecodePacket()
    val slot0Valid = Bool()
    val slot1      = DecodePacket()
    val slot1Valid = Bool()
    val slot1RawWords = Vec(Bits(16 bits), WINDOW)
    val slot1L0       = UInt(4 bits)
    val shiftWords = UInt(4 bits)
    val stall      = Bool()
    val complex    = Bool()
  }
```

- [ ] **Step 3: Populate the new fields in `align()`**

Immediately after the existing `val L0 = p0.lenWords` line (86), add:
```scala
    r.slot1RawWords := words
    r.slot1L0       := L0
```
(Set unconditionally, mirroring how `r.slot0`/`r.slot1` themselves are
default-assigned early via `assignDontCare()` then overridden in the
`when` arms below — these two new fields need no `when`-gating since they
are pure passthroughs of already-valid inputs, always meaningful
regardless of which arm below fires; `slot1Valid` still gates whether a
CONSUMER should look at them, exactly as it already gates `r.slot1`
itself.)

- [ ] **Step 4: Extend `DecodeFeedService`**

In `Services.scala`, add to `trait DecodeFeedService` (around line 178-180):
```scala
trait DecodeFeedService {
  def feed: Stream[Vec[DecodePacket]]   // Vec length 2
  def slot1Valid: Bool
  def slot1RawWords: Vec[Bits]
  def slot1L0: UInt
}
```

- [ ] **Step 5: Implement the new service members in `FetchAlignPlugin`**

Find where `FetchAlignPlugin` currently implements `feed`/`slot1Valid`
(from `res = Aligner.align(...)`) and add the 2 new members alongside,
sourced from the same `res`:
```scala
override def slot1RawWords: Vec[Bits] = res.slot1RawWords
override def slot1L0: UInt = res.slot1L0
```
(Match this file's exact existing style for how `feed`/`slot1Valid` are
currently implemented — `def` vs `val`, override placement, etc.)

- [ ] **Step 6: Add the 3-arg `computeOffload` overload**

In `MicroOpAssembler.scala`, immediately after the existing 1-arg
`computeOffload` (around lines 463-472), add:
```scala
  /** Slot-1-only variant: collapses the two chained dynamic shifts (Aligner's
    * L0 shift, then this function's own dstShift shift) into one mux on the
    * summed index, applied directly to the raw (pre-Aligner-shift) IBuf
    * window. Byte-identical `dstEa` output to the 1-arg overload -- see
    * docs/superpowers/specs/2026-08-07-fmax-slice3-frontend-dsteashift-collapse-design.md
    * for the algebraic proof. `o.spec`/`o.srcEa` are UNCHANGED (computed
    * identically to the 1-arg overload; src-EA never needs the second shift). */
  def computeOffload(pkt: DecodePacket, rawWords: Vec[Bits], l0: UInt): Offload = {
    val o = Offload()
    o.spec := OperationDecoder.decode(pkt.words(0))
    o.srcEa := srcEaFor(pkt, o.spec.size)
    val dstEaField = pkt.words(0)(8 downto 6) ## pkt.words(0)(11 downto 9)
    val dstShift = srcEaWordCount(pkt.words(0)(5 downto 3).asUInt, pkt.words(0)(2 downto 0).asUInt,
                                   o.spec.size, pkt.words)
    val totalShift = (l0 +^ dstShift).resize(5)
    o.dstEa := EaDecoder.decode(dstEaField, o.spec.size, shiftedWordsFor(rawWords, totalShift))
    o
  }
```

- [ ] **Step 7: Update the slot1 call site**

In `DecodeStage.scala`, change line 89 from:
```scala
    fedIn.payload.specs(1)   := MicroOpAssembler.computeOffload(df.feed.payload(1))
```
to:
```scala
    fedIn.payload.specs(1)   := MicroOpAssembler.computeOffload(df.feed.payload(1), df.slot1RawWords, df.slot1L0)
```
Leave line 88 (slot0's call) completely unchanged.

- [ ] **Step 8: Compile**

```bash
~/sbt/bin/sbt compile
```
Expected: clean. If `DecodeFeedProbePlugin.scala` (the test probe
implementing `DecodeFeedService`, per the design spec's consumer count)
fails to compile because it doesn't implement the 2 new abstract members,
add a trivial implementation there too (source them from whatever it
already exposes, or hardwire zero/don't-care if it's genuinely unused by
that probe — check what the probe actually needs before deciding).

- [ ] **Step 9: Locate existing `computeOffload`/`Offload`-adjacent test coverage**

```bash
grep -rln "computeOffload\|Offload(" src/test/scala/
```
Read what's found. Identify whether direct unit coverage of
`computeOffload` already exists, or whether it's only exercised indirectly
via `AlignerSpec`/`FetchAlignSpec`/`ExecuteLockStepSpec`.

- [ ] **Step 10: Add the correctness-invariant directed test**

In whichever location Step 9 identifies as the right home (or a new
`MicroOpAssemblerOffloadSpec.scala` if none fits — match this project's
existing test-file naming/harness conventions), add a test that:
1. Constructs a slot1 scenario where the SOURCE EA has its own extension
   word(s) (`dstShift > 0` — e.g. a `(d16,An)` or brief/full-format indexed
   source per `srcEaWordCount`'s branches, `MicroOpAssembler.scala:405-427`)
   AND the instruction sits at a nonzero `L0` (i.e. is genuinely slot1, not
   slot0) within the raw window.
2. Calls BOTH the old 1-arg `computeOffload(pkt)` (on the already-L0-shifted
   `pkt`, exactly as today) and the new 3-arg
   `computeOffload(pkt, rawWords, l0)` (on the raw window + `L0`) with
   equivalent inputs (same underlying instruction bytes, one pre-shifted by
   the test harness to emulate Aligner's output, one passed raw).
3. Asserts `o.dstEa` is IDENTICAL between the two calls (this is the
   binding correctness invariant from the design spec — a pure refactor
   check, not a "does it decode correctly" check, though it should also
   independently assert the decoded `disp`/`imm` value against the known
   test-instruction encoding).

Before writing new test cases, grep `AlignerSpec`/`FetchAlignSpec` for any
existing slot1-with-source-extension-words case that could be reused/
extended instead of duplicated.

- [ ] **Step 11: Run the new test(s)**

```bash
~/sbt/bin/sbt "testOnly *<TheSpecFileName>*"
```
Expected: PASS. If FAIL, the algebraic identity claimed in the design spec
does not hold for some case — do not weaken the assertion; find and fix
the actual indexing bug (likely an off-by-one in `totalShift`'s
computation or `shiftedWordsFor`'s index base — re-derive against the
design spec's algebraic proof).

- [ ] **Step 12: `AlignerSpec` / `FetchAlignSpec` regression**

```bash
~/sbt/bin/sbt "testOnly m68k040.frontend.AlignerSpec"
~/sbt/bin/sbt "testOnly m68k040.frontend.FetchAlignSpec"
```
Expected: `AlignerSpec` 9/9; `FetchAlignSpec` the SAME single pre-existing
failure as Slice 1's baseline (confirmed pre-existing, unrelated —
`.superpowers/sdd/progress-fmax-p0live.md`). A DIFFERENT failure count/set
means investigate before proceeding.

- [ ] **Step 13: Targeted mem-indirect/dst-EA ported tests**

Grep the ported corpus for dst-EA mem-indirect/full-format framing tests
(this project's own historically fragile area for this exact code path —
see the design spec's Verification section for named examples like
`move_l_memind_to_memind`, `bf_memind_dyn_straddle`). Run them explicitly
before the full sweep to surface any indexing bug fast.

- [ ] **Step 14: `ExecuteLockStepSpec` full suite**

```bash
~/sbt/bin/sbt "testOnly m68k040.*.ExecuteLockStepSpec"
```
Expected: 390/394, same 4 pre-existing failures. Investigate any
difference before proceeding.

- [ ] **Step 15: Full ported test corpus (isolated worktree, before/after)**

Use `tools/fuzz/ported-sweep-parallel.sh` against the current baseline
commit (record its SHA before starting Step 2). Compare sorted fail-name
lists — expect byte-identical.

- [ ] **Step 16: OOC-synth-only gate**

```bash
vivado -mode batch -source synth/ooc_M68kFullCoreSynth.tcl
```
Record WNS/FMax. Confirm the `ibuf/entries_*_pred_lenWords_reg -> ...dstEa_disp_reg`
path family no longer appears in the worst-8 paths report.

- [ ] **Step 17: Real post-route gate**

```bash
vivado -mode batch -source synth/impl_FullCore.tcl
```
Record WNS/FMax. Compare against the current baseline (post-Slice-1 alone,
or combined with Slice 2 if it already landed). Report explicitly whether
the targeted path family is gone and what the new worst path is.

- [ ] **Step 18: Commit**

```bash
git add src/main/scala/m68k040/frontend/Aligner.scala \
        src/main/scala/m68k040/services/Services.scala \
        src/main/scala/m68k040/frontend/FetchAlignPlugin.scala \
        src/main/scala/m68k040/decode/MicroOpAssembler.scala \
        src/main/scala/m68k040/decode/DecodeStage.scala \
        src/test/scala/m68k040/frontend/DecodeFeedProbePlugin.scala \
        <any new/modified test spec files>
git commit -m "frontend: collapse slot-1 dst-EA chained shift barrels (FMax closure slice 3)"
```

## Self-Review Note

One cohesive task, not split further: the interface threading (Steps 2-7)
has no independently meaningful intermediate state (a partial version, e.g.
`Aligner.Result` extended but `DecodeFeedService` not yet, does not
compile), and the correctness-invariant test (Step 10) is the whole point
of the change — splitting it into a separate task would let "compiles" be
mistaken for "done" before the binding correctness proof actually runs.
Matches Slice 1/Slice 2's plan structure and rationale.
