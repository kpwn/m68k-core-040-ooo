# FMax closure, Slice 3: collapse slot-1's chained dst-EA shift barrels (design)

## POST-IMPLEMENTATION CORRECTIONS (read this first)

Implementation (commit `1b64ccc`) and an independent review both found this
spec wrong in three material ways. The RTL landed anyway — see
`.superpowers/sdd/progress-fmax-slice3.md` for the full record and the
**PROBATIONARY** status — but do not trust the claims below as originally
written; use this section instead.

**1. Mechanism correction (the load-bearing one).** This spec models the two
shifts as independent DATA muxes: `arr[a][b]` collapsible to a flat index
`a+b`. **That model is wrong, confirmed from the actual post-route netlist
path chain by both the implementer and an independent reviewer.**
`dstShift` — the SECOND mux's SELECT — is itself downstream of the FIRST
mux's DATA, via `OperationDecoder.decode(pkt.words(0)).size`:
`L0 -> Aligner slot-1 mux -> pkt.words(0) -> OperationDecoder(~1.2ns) ->
size -> srcEaWordCount -> dstShift -> second mux SELECT -> EaDecoder`. The
two muxes were **never chained as data** on the real critical arc — the
data side (`pkt.words(1..)`) had well over a nanosecond of slack relative
to its own select the whole time. Consequently:
- Re-sourcing the mux's DATA from `rawWords` (this spec's whole mechanism)
  **cannot shorten the real critical path** — confirmed: OOC-synth-only
  **regresses** by -0.137ns (the new `l0 +^ dstShift` adder lands directly
  on the dominant SELECT chain, widening the dynamic index range).
- The real post-route gate improved marginally (+0.020ns / +0.559MHz,
  ~0.5% of the violation) and the originally-cited path family did
  disappear from the worst-10 — but this is placement-equilibrium, **not
  attributable to the designed mechanism**. Treat it as "functionally
  clean, mechanism disproven, effect unattributed", not as a won FMax
  delta, in any future accounting of this initiative.
- **The real lever for this cone** (identified but NOT implemented): for
  slot-1's dst path on the MOVE lines, `spec.size` is literally
  `op[15:12]` — reading it directly instead of running the full
  `OperationDecoder` table would drop that table (~1.2ns on the baseline
  critical chain — two orders of magnitude more than this slice's actual
  effect) out of the `L0 -> dstEa` cone entirely. This is the change a
  follow-on slice should make; it likely subsumes this one, at which point
  reverting this slice's interface threading becomes free and should be
  done.

**2. `EaDecoder` citation correction.** This spec (below) claims
`EaDecoder.decode` reads its `words` argument "only at static indices 1,
2, 3". False — `EaDecoder.scala`'s full-format outer-displacement path
(`fOdWordAt`) is a DYNAMIC read over indices 2..5. The real consumed set is
1..5, dynamic over 2..5. (Root cause: a pre-existing stale comment in
`EaDecoder.scala` claiming "the shifted-vector callers pass only 3
entries" — true for some callers, false for `shiftedWordsFor`, the one
that matters here. That comment has been corrected.)

**3. Correctness-invariant correction.** This spec states the algebraic
identity holds "for every reachable `(L0, dstShift, words)` combination" —
**false as written**. `Aligner.scala`'s slot-1 loop zero-fills
`pkt.words(j)` for `j >= L1`; the new path reads the NEXT instruction's
real words there instead. The TRUE (and sufficient) invariant is
CONDITIONAL: `o.dstEa` is bit-identical only where it is actually
consumed, which — after a full consumer-tree derivation independently
re-verified by two people — is exactly the MOVE-line-gated region strictly
inside `L1`. See `MicroOpAssembler.scala`'s comment above the 3-arg
`computeOffload` overload for the full, current, correct derivation
(including the `DecodeStage.scala` `s1mi_isMove` consumer this spec never
analyzed). This conditional contract is now backed by an exhaustive
65536-opword automated test in `MicroOpAssemblerOffloadSpec` (added
post-review) rather than resting on comment-only enforcement.

## Context (original — mechanism claims below are superseded, see above)

Same initiative as Slice 2 (D-cache S1a/S1b split, its own design spec) —
both cover the two paths tied for worst post-route path after Slice 1
landed (146.11 -> 167.029 MHz). This spec covers the front-end path:

```
ibuf/entries_11_pred_lenWords_reg -> _zz_..._specs_1_dstEa_disp_reg (-1.986ns)
```

A research pass (grounding fork, this session) re-traced this against
CURRENT (post-Slice-1) source. **Revised framing vs. the earlier Fable-5
brainstorm research**: `Aligner.scala` no longer contains a `computeOffload`/
EaDecoder cone at all — that was already relocated to `DecodeStage.scala` by
an EARLIER, already-landed FMax initiative ("ANGLE E + LEVER 2",
`DecodeStage.scala:38-49,83-90`), which computes each slot's `Offload`
(OpSpec + srcEa/dstEa) on the Aligner's PRE-register output and carries it
through the existing `fedIn`->`fed` `PipeStage` register
(`DecodeStage.scala:78-92`). **This is not a defeated/bypassed register
case like Slice 1's `p0Live`** — `fed` is an intentional, correctly-placed
pipeline register; the problem is purely that the combinational cone feeding
its `specs(1).dstEa.disp` field is unnecessarily deep.

## The cone (source-cited, current HEAD)

1. **Aligner's slot-1 word barrel** (`Aligner.scala:206-220`, esp. 214-220):
   ```scala
   val L0 = p0.lenWords   // Aligner.scala:86, now sourced from the registered
                           // p0LiveReg/preds(0) post-Slice-1 -- not itself a limiter
   ...
   val idx = (L0 +^ U(i)).resize(4)
   when(U(i) < L1) { r.slot1.words(i) := words(idx) } .otherwise { r.slot1.words(i) := 0 }
   ```
   A 10-way dynamic mux per output word (`words` is the WINDOW=10-wide raw
   IBuf-head window), keyed on `L0`. This produces `r.slot1.words`, i.e.
   "slot1's own words, re-based so index 0 = slot1's header opword."
2. **`MicroOpAssembler.computeOffload`** (`MicroOpAssembler.scala:463-472`),
   called on `df.feed.payload(1)` = Aligner's `r.slot1`
   (`DecodeStage.scala:89`):
   ```scala
   def computeOffload(pkt: DecodePacket): Offload = {
     val o = Offload()
     o.spec := OperationDecoder.decode(pkt.words(0))                    // (465)
     o.srcEa := srcEaFor(pkt, o.spec.size)                              // (466)
     val dstEaField = pkt.words(0)(8 downto 6) ## pkt.words(0)(11 downto 9)  // (467)
     val dstShift = srcEaWordCount(pkt.words(0)(5 downto 3).asUInt,
                                    pkt.words(0)(2 downto 0).asUInt,
                                    o.spec.size, pkt.words)              // (468-469)
     o.dstEa := EaDecoder.decode(dstEaField, o.spec.size,
                                  shiftedWordsFor(pkt.words, dstShift))  // (470)
     o
   }
   ```
   `srcEaWordCount` (405-427) is a mode/reg `switch` (incl.
   `miEaWordCountG`'s bd/od-size mux chain, 396-404) reading `pkt.words(0)`/
   `pkt.words(1)` (STATIC indices — cheap, these are already at fixed
   offsets thanks to Aligner's shift) to determine how many words slot1's
   OWN source EA consumed, i.e. where slot1's DESTINATION EA's extension
   words begin.
   `shiftedWordsFor` (437-441) calls `wordAtDynG` (428-434) — **a SECOND,
   INDEPENDENT 10-way dynamic mux per word**, re-indexing `pkt.words` (which
   was ALREADY barrel-shifted by `L0` in step 1) by `dstShift`.
3. **`EaDecoder.decode`** (`EaDecoder.scala:34` onward) — confirmed by
   direct read: reads its `words` argument **only at static indices 1, 2, 3**
   (e.g. `e.disp := words(1) ## words(2)` for a full-format bd.L EA,
   `EaDecoder.scala:150`) — never dynamically, never beyond index 3. So of
   `shiftedWordsFor`'s 10-element output, only 3 are ever consumed; the
   depth cost is in COMPUTING those 3 (each its own 10-way mux chained onto
   an already-10-way-muxed input), not in the unused width (Vivado should
   already prune the unread 7 elements regardless).
4. **Confirmed `srcEaFor`/src-EA does NOT need this collapse**: it calls
   `EaDecoder.decode(pkt.words(0)(5 downto 0), size, pkt.words)`
   (`MicroOpAssembler.scala:452-458`, the FSF special-case aside) — no
   SECOND shift, src's own extension words sit at `pkt.words(1..3)`
   directly (Aligner's shift alone is sufficient for src). **Only the DST
   path chains two shifts. This narrows the fix's blast radius to the
   `o.dstEa := ...` line alone — `o.spec`/`o.srcEa` and the header/dstShift-
   determination logic are UNCHANGED.**

Net: for the 3 words `EaDecoder.decode` actually reads for `dstEa`, the RTL
today computes `wordAtDynG(wordAtDynG-built-array(L0-shift), dstShift)` — a
genuine mux-of-mux (index-into-an-array-that-is-itself-a-mux), each hop
costing a full ~10-way dynamic select. Chaining two independent dynamic
index computations here is provably more expensive than one dynamic select
on the SUMMED index `L0 + dstShift` applied directly to the raw
(un-Aligner-shifted) IBuf window — the same class of simplification as
"index into `arr[a][b]` when `a+b` addresses the same flat array directly."

## The fix: single combined-shift read for slot-1's dst-EA words only

Requires slot-1's raw (pre-Aligner-shift) window and `L0` to reach
`computeOffload`'s call site in `DecodeStage.scala` — today only the
ALREADY-shifted `df.feed.payload(1): DecodePacket` is available there via
`DecodeFeedService` (`Services.scala:178-180`,
`def feed: Stream[Vec[DecodePacket]]`). Confirmed only 2 consumers of this
service exist in the whole tree (`DecodeStage.scala`,
`DecodeFeedProbePlugin.scala` — a test probe), so extending it is a bounded
change.

**Locked design:**

1. `Aligner.Result` (`Aligner.scala:10-18`) gains two new fields, populated
   alongside the existing `r.slot1` assignment in `align()`:
   ```scala
   val slot1RawWords = Vec(Bits(16 bits), WINDOW)   // = the raw `words` input, unshifted
   val slot1L0       = UInt(4 bits)                 // = L0
   ```
   Set unconditionally: `r.slot1RawWords := words` (the function's own
   `words` parameter — already in scope, zero new logic, just wiring) and
   `r.slot1L0 := L0`. These are the SAME `words`/`L0` already used to build
   `r.slot1.words` in step 1 above — no new computation, just exposing
   values that already exist inside `align()` but don't currently escape it.
2. `DecodeFeedService` (`Services.scala:178-180`) gains two new abstract
   members: `def slot1RawWords: Vec[Bits]`, `def slot1L0: UInt`.
   `FetchAlignPlugin.scala` (the sole implementer) wires them straight from
   `res.slot1RawWords`/`res.slot1L0` (`res = Aligner.align(...)`, existing
   call site).
3. `MicroOpAssembler` gains a second `computeOffload` overload, used ONLY
   for slot1:
   ```scala
   def computeOffload(pkt: DecodePacket, rawWords: Vec[Bits], l0: UInt): Offload = {
     val o = Offload()
     o.spec := OperationDecoder.decode(pkt.words(0))
     o.srcEa := srcEaFor(pkt, o.spec.size)                       // UNCHANGED
     val dstEaField = pkt.words(0)(8 downto 6) ## pkt.words(0)(11 downto 9)
     val dstShift = srcEaWordCount(pkt.words(0)(5 downto 3).asUInt,
                                    pkt.words(0)(2 downto 0).asUInt,
                                    o.spec.size, pkt.words)       // UNCHANGED
     val totalShift = (l0 +^ dstShift).resize(5)                 // NEW: one adder
     o.dstEa := EaDecoder.decode(dstEaField, o.spec.size,
                                  shiftedWordsFor(rawWords, totalShift))  // rawWords, not pkt.words
     o
   }
   ```
   The existing single-arg `computeOffload(pkt)` is UNCHANGED and stays the
   one used for slot0 (`DecodeStage.scala:88`,
   `MicroOpAssembler.computeOffload(df.feed.payload(0))`) — slot0 has no
   `L0`-style pre-shift (it starts at the IBuf head literally), so this
   collapse doesn't apply to it and it must not be touched.
4. `DecodeStage.scala:89` changes from
   `fedIn.payload.specs(1) := MicroOpAssembler.computeOffload(df.feed.payload(1))`
   to
   `fedIn.payload.specs(1) := MicroOpAssembler.computeOffload(df.feed.payload(1), df.slot1RawWords, df.slot1L0)`.

**Correctness invariant (binding, must be proven — not asserted):**
`o.dstEa` from the NEW 3-arg overload must be **byte-for-bit identical** to
`o.dstEa` from the OLD 1-arg overload, for every reachable
`(L0, dstShift, words)` combination. This is a pure algebraic identity:
`shiftedWordsFor(pkt.words, dstShift)(i) == wordAtDynG(pkt.words, i+dstShift)
== wordAtDynG(rawWords, L0+i+dstShift)` (since `pkt.words(j) ==
wordAtDynG(rawWords, L0+j)` by construction, step 1 above) `==
shiftedWordsFor(rawWords, L0+dstShift)(i)`. The plan's directed tests must
verify this identity holds for concrete cases, not just "the test suite
still passes" — pick cases that exercise `dstShift > 0` (a slot1 whose
SOURCE EA itself has extension words, e.g. `(d16,An)` or brief/full-format
indexed source, per `srcEaWordCount`'s branches at
`MicroOpAssembler.scala:405-427`) combined with `L0 > 0` in a way the
existing test suite's slot1 cases may not already cover in combination —
grep `AlignerSpec`/`FetchAlignSpec`/`DecodeStage`-adjacent tests for
existing dual-EA-extension-word slot1 coverage before writing new tests, to
avoid duplicating coverage that already exists.

## Non-goals / explicitly out of scope

- Any change to slot0's `computeOffload` call or the 1-arg overload's body.
- Any change to `o.spec`/`o.srcEa`/`dstEaField`/`dstShift`'s own computation
  — only the FINAL `shiftedWordsFor` call's input array changes.
- Slot-1's own `ambiguousLine` handling (`Aligner.scala:176-202`, task
  #209's fix) — separate parallel path, doesn't feed `dstEa`, unaffected.
- Eliminating Aligner's `L0`-shift of `r.slot1.words` itself — still needed
  for `o.spec`/`o.srcEa`/`dstShift`'s own determination (header/first-ext-
  word reads at static indices), and for anything else in the codebase that
  consumes `df.feed.payload(1).words` directly (not just `computeOffload`)
  — do not attempt to remove it as part of this slice.
- Any change to `DecodePacket` itself — the new raw-words/L0 fields live on
  `Aligner.Result`/`DecodeFeedService`, NOT on `DecodePacket`, so slot0's
  copy of `DecodePacket` carries no new dead weight.

## Verification requirements (binding)

- `~/sbt/bin/sbt compile` clean.
- The correctness-invariant directed tests described above (algebraic
  identity, `dstShift>0` combined with `L0>0`), added to whichever spec
  file already covers `computeOffload`/`Offload` (locate via grep — likely
  `MicroOpAssemblerSpec.scala` or similar; confirm the real file name/path
  at implementation time, do not guess).
- Targeted ported tests known to exercise dst-EA mem-indirect/full-format
  framing specifically (this project's own established high-risk area for
  this exact code path — tasks #145/#147/#150-156/#178-179 in the project's
  own history all touched dst-EA word-indexing bugs): grep the ported
  corpus for `memind`/`dst`-EA-family test names (e.g. `move_l_memind_to_memind`,
  `bf_memind_dyn_straddle`, and any `pea`/`lea`/mem-indirect-dst tests) and
  run them explicitly before the full-corpus sweep — this is the area most
  likely to silently regress from an indexing-arithmetic mistake, and
  targeted tests surface a bug faster than waiting for the full sweep.
- `AlignerSpec`/`FetchAlignSpec`: full pass, same counts as Slice 1's
  baseline (`AlignerSpec` 9/9; `FetchAlignSpec` 1 pre-existing unrelated
  failure, confirmed in Slice 1's ledger).
- `ExecuteLockStepSpec` full suite: expect 390/394 (same 4 standing
  pre-existing failures).
- Full ported test corpus (~870 tests, `tools/fuzz/ported-sweep-parallel.sh`,
  isolated git worktrees): zero new regressions vs. current baseline (post-
  Slice-1: 812 pass / 58 fail, see `.superpowers/sdd/progress-fmax-p0live.md`
  — or Slice 2's post-landing baseline if Slice 2 lands first).
- OOC-synth-only gate AND the real post-route gate
  (`synth/impl_FullCore.tcl`). Report the measured FMax delta explicitly.
  Success criterion for THIS slice alone: the
  `ibuf/entries_*_pred_lenWords_reg -> ..._specs_1_dstEa_disp_reg` path
  family should disappear from the worst-8 post-route paths (causal
  confirmation, same standard as Slice 1). Report the COMBINED number if
  Slice 2 has already landed.

## Open questions for the plan-writing pass

- Confirm exact live line numbers for every citation above at plan-writing
  time (established convention — line numbers drift).
- Confirm `Offload`'s exact definition/location (referenced but not
  re-derived here — grep `case class Offload` or `object Offload` in
  `MicroOpAssembler.scala`) to get field names exactly right in new test
  code.
- Locate the exact existing test file(s) covering `computeOffload`
  directly (if any exist as unit tests distinct from the full
  `AlignerSpec`/`FetchAlignSpec`/`ExecuteLockStepSpec` suites) before
  deciding where the new correctness-invariant tests belong.
