# FMax closure, Slice 1: take `p0Live` off the L0 consume path (design)

## Context

The project's standing per-slice synth gate requires ≥250MHz post-route
(`synth/impl_FullCore.tcl`, xcku5p-ffvb676-2-e). A post-route measurement round
(2026-08-06/07) found the ACTUAL achieved FMax is only ~146MHz (WNS -2.844ns
at the 4.000ns/250MHz constraint), confirmed reproducible bit-for-bit across
independent runs and independent of clock target (a relaxed 200MHz constraint
still failed, ~140.57MHz — proving this is a structural, target-independent
critical path, not congestion or measurement noise; congestion was confirmed
completely clean at both targets).

A research pass (Fable 5, `docs`-adjacent scratchpad
`fmax-closure-brainstorm-report.md`, not committed) traced the exact worst
post-route path — 23 logic levels, 6.825ns data path (2.181ns logic / 4.644ns
route), every cell inside the `pb_decode` placement region — one-to-one onto
source RTL:

```
IBuf head FF -> 20:1 head barrel-rotate -> a SECOND live PredecodeWord.classify()
  instance ("p0Live") -> L0 select -> slot-1 word-realignment barrel ->
  slot-1's full computeOffload/EaDecoder cone (pre-register) -> dstEa_disp register
```

This spec covers ONLY the first segment of that chain: removing the live
`p0Live` classify() instance from the same-cycle consume path. This was
independently confirmed as the single largest attributable chunk (~1.9ns of
the 6.825ns path) both by Fable's per-segment arrival-time analysis and by a
throwaway attribution experiment (locally deleting the `p0Live` mux and
re-running the OOC-synth-only gate) run before this spec was written — see
the ledger entry / task #201 for the numeric result.

The remaining segments (slot-1 word realignment, slot-1's offload/EaDecoder
cone) are explicitly OUT OF SCOPE here — they are Fable's Candidate 2/2a/3,
gated on this slice's actual measured payoff and requiring their own design
pass. Do not attempt to also fix those in this slice.

## The bug this slice fixes (in the "FMax" sense — this is a performance fix,
## not a correctness fix; today's code is functionally correct)

`Aligner.scala` (`Aligner.align`, around line 63-65, current HEAD — re-verify
against live line numbers before implementing, this project's own established
convention given how much line drift has occurred across other slices this
session):

```scala
val p0Live = PredecodeWord.classify(words(0), words(1), words(2), words(3),
  extWValid = avail >= U(2, 4 bits), extW2Valid = avail >= U(3, 4 bits), extW3Valid = avail >= U(4, 4 bits))
val p0 = Mux(preds(0).ambiguousLine, p0Live, preds(0))
val L0 = p0.lenWords
```

`preds(0)` is the BAKED prediction for the instruction buffer's head word,
computed once at I-cache refill time (`IcachePlugin`, `ChunkPredecode`) and
carried through `InstructionBuffer`'s `entries(headPtr).pred` field
(`InstructionBuffer.scala:7-10, 71-78`). It is correct for the overwhelming
majority of instructions. `ambiguousLine` (a 1-bit field on `ChunkPredecode`)
is set when the baked prediction, at refill time, couldn't see past a 64-byte
I-cache line boundary and had to guess brief-vs-full-format framing for a
mode-6/mode7-reg3 EA — a rare, per-line-tail case (this was task #202's own
original fix, commit `de2f14c`; see that commit's message and
`Aligner.scala:43-62`'s comment for the full rationale, which remains
correct and does not need to change).

`p0Live` re-runs the SAME `classify()` logic against the LIVE buffered
`words`/`avail` (which, by the time the ambiguous instruction is the buffer
head, has very likely already absorbed the next I-cache line's words too,
since fetch runs independently of decode and `words`/`avail` are PC-relative
not line-relative). `p0` picks `p0Live` whenever the baked guess was
ambiguous.

**The FMax problem**: static timing analysis must charge the FULL depth of
`classify()` (a real combinational decoder over 4 words) against `L0`'s
arrival time for EVERY instruction, because `p0` is a `Mux` selecting between
`p0Live` (slow) and `preds(0)` (fast, already registered) based on a runtime
condition STA cannot statically prove is almost-always-false. `L0` then feeds
almost the entire rest of the front-end's per-cycle critical path (slot-1 PC,
the realignment barrel, the BTB query, `effShift`, `decodePcNext`, the IBuf
shift amount) — so this one `classify()` instance's depth is effectively
added to nearly every downstream frontier this design has fought FMax battles
over.

## The fix: register `p0Live`, feed `p0` from the register, not the live wire

**Key correctness argument, established by reading the surrounding code, not
assumed:** while `preds(0).ambiguousLine` is true, the existing `.elsewhen`
stall arm (`Aligner.scala:70-73`) already keeps `r.slot0Valid := False` and
`r.shiftWords := 0` — meaning `InstructionBuffer`'s `headPtr` CANNOT advance
(no shift is ever issued) for as long as the head remains ambiguous.
`InstructionBuffer.entries` is written only by `push` (which writes the TAIL
slot, `InstructionBuffer.scala` around line 100 — the physical tail is
`(headPtr + count) mod BUF_WORDS`, and an ambiguous head implies `count > 0`,
so head and tail slots can only coincide when `count == 0`, which cannot hold
simultaneously with an ambiguous head) and by `shift`/`flush` (which move
`headPtr`, not the entry contents). **Therefore the head entry's baked
`pred` (and hence `preds(0).ambiguousLine`'s truth value) is provably stable,
cycle over cycle, for as long as the aligner is stalled on it** — the ONLY
way the head's identity changes during a stall is a `flush` (which resets
`headPtr` to 0 and `count`/starts fresh — the pending resolve becomes
meaningless and MUST be invalidated).

This means the classic "patch write into the IBuf's storage" mechanism
(Fable's Candidate 1 report considered this as the primary shape, alongside a
lower-risk alternative) is unnecessary. A simpler, lower-risk mechanism
suffices: keep computing `p0Live` combinationally every cycle exactly as
today (cheap; it was already being computed every cycle regardless of
whether `ambiguousLine` was true), but REGISTER its output, and read the
Mux's slow input from the register instead of the live wire. Consume the
registered value only once it reports non-ambiguous (i.e. `avail` has grown
enough for THAT cycle's live classify() to actually resolve it) — until
then, keep re-registering the (still-ambiguous) live guess every cycle and
stay in the stall arm, exactly as today, just one cycle later per attempt.

```scala
// In FetchAlignPlugin.scala (or wherever the aligner's inputs are assembled —
// re-verify the exact call site, `FetchAlignPlugin.scala:315` at design time),
// alongside the existing ibuf/aligner wiring:
val p0LiveReg = Reg(ChunkPredecode())
// Computed unconditionally every cycle from the LIVE head words/avail — same
// classify() call as today, just its result now lands in a register instead
// of feeding `p0` directly:
val p0LiveNext = PredecodeWord.classify(ibuf.io.head(0), ibuf.io.head(1), ibuf.io.head(2), ibuf.io.head(3),
  extWValid = ibuf.io.avail >= U(2, 4 bits), extW2Valid = ibuf.io.avail >= U(3, 4 bits), extW3Valid = ibuf.io.avail >= U(4, 4 bits))
p0LiveReg := p0LiveNext
when(flushCondition) {           // whatever this project's existing flush signal is at this
  p0LiveReg.ambiguousLine := True // point in FetchAlignPlugin — re-verify the exact name/scope
}                                  // force "not yet resolved" on flush; safe default, costs at
                                   // most 1 extra stall cycle in the (already rare) case a
                                   // flush lands exactly on an ambiguous head
```

And in `Aligner.align` (`Aligner.scala`), change the signature to accept the
registered value as an additional parameter (rather than computing `p0Live`
inside `align` itself), e.g.:

```scala
def align(headPc: UInt, words: Vec[Bits], preds: Vec[ChunkPredecode], avail: UInt,
          p0LiveReg: ChunkPredecode): Result = {
  ...
  val p0 = Mux(preds(0).ambiguousLine, p0LiveReg, preds(0))
  val L0 = p0.lenWords
  ...
}
```

(Exact parameter shape / whether to pass the whole `ChunkPredecode` or just
the fields `align` needs is an implementation-time call — keep it minimal and
follow this file's existing style.)

**This is a deliberate, hand-placed pipeline register** — not something
`phys_opt`'s automatic retiming could do on its own (there is no existing
register in the right place for it to retime across; the whole cone between
`preds(0).ambiguousLine`'s current value and `p0`'s consumption is one
architecturally-flat combinational block today). Placing it here is a
genuine microarchitectural change: the ambiguous-case resolve now takes at
minimum 2 cycles (compute -> register -> consume) instead of a single
same-cycle combinational chain — but this ONLY affects the rare
`ambiguousLine` case, which was ALREADY stalling for potentially multiple
cycles waiting for `avail` to grow, so this adds at most one extra stall
cycle to an already-rare, already-multi-cycle-tolerant path.

## Non-goals / explicitly out of scope for this slice

- Slot-1's word-realignment barrel, `computeOffload`, and the EaDecoder cone
  (Fable's Candidates 2/2a/3) — separate slice(s), gated on this slice's
  measured payoff.
- Slot-1's OWN `ambiguousLine` handling (`Aligner.scala` around line
  159-182, task #209's fix) — that fix already stalls (`slot1Ok=False`) on
  an ambiguous slot-1 candidate rather than live-reclassifying it, so it is
  NOT part of the critical path this slice targets and needs no change.
- Any change to `ChunkPredecode`'s bit layout, `IcachePlugin`'s refill-time
  predecode, or `InstructionBuffer`'s storage shape.

## Verification requirements (binding — this project's standing rule for any
## RTL change, doubly so for a change to a historically regression-prone area)

- `~/sbt/bin/sbt compile` clean.
- A DIRECTED test exercising the `ambiguousLine` resolve path specifically —
  this project's ported corpus already has coverage here (task #202's own
  fix cites `pea_memind`, and the report's grounding notes
  `rom_frontier_decode_matrix` case 11, `bf_memind_dyn_straddle`,
  `move_l_memind_to_memind` as directly relevant); confirm these still pass
  and consider whether a new minimal directed unit test (mirroring this
  project's existing `Aligner`/`FetchAlignPlugin` test conventions, if any
  exist — check `src/test/scala/m68k040/frontend/` for precedent) specifically
  forcing a flush to land exactly while `ambiguousLine` is pending would be a
  valuable NEW addition (this exact interaction — flush arriving mid-resolve —
  has no known existing test coverage per Fable's own report).
- `ExecuteLockStepSpec` full suite: expect 390/394 (the 4 standing
  pre-existing failures unrelated to this area — do not accept a different
  count or different failing tests without investigating).
- Full ported test corpus (~870 tests): zero new regressions vs the current
  baseline (check `.superpowers/sdd/progress.md` or the most recent full-corpus
  run for the current expected fail count/list).
- OOC-synth-only gate (fast sanity check) AND the real post-route gate
  (`synth/impl_FullCore.tcl`) — per this project's own hard-learned lesson
  from this exact area (task #202 shipped without either, which is very
  likely a large contributor to today's regression): **do not repeat that
  mistake.** Report the measured FMax delta explicitly; this slice's success
  criterion is "clears ~200MHz post-route on its own" per Fable's estimate,
  not 250MHz (250MHz needs the follow-on slot-1 slice too).

## Open question for the plan-writing pass

Whether `p0LiveNext`'s computation should read `ibuf.io.head`/`ibuf.io.avail`
directly (as sketched above) or whatever the actual live signals feeding
today's `p0Live` are at the real call site — the sketch above is illustrative
of the mechanism, not a literal diff; the implementation plan must re-derive
the exact signal names from the live `FetchAlignPlugin.scala`/`Aligner.scala`
at plan-writing time.
