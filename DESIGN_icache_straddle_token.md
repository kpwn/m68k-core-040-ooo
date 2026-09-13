# I-cache straddle tokens: get `PredecodeWord.classify` off the fetch critical path

**Status:** design agreed with owner 2026-09-13, implementation starting.
**Motivation:** 200 MHz campaign. Measured post-synth on the eth200 build, the worst
cone in the entire design — and the source of the top FIVE setup violations — is:

```
ftqHead_reg[1] -> ftqMem async read -> 32-bit (ftqHeadE.brPc - decodePc) carry chain
  -> availEff -> PredecodeWord.classify (full ISA, 7 head words) -> p0LiveReg
      -1.694 ns, 30 logic levels, 74% route   (27 of 29 cells inside FetchAlignPlugin)
```

## Why that cone exists at all

The instruction length IS already in the I-cache: `ChunkPredecode{simple, lenWords,
ambiguousLine, size}` is computed at REFILL and stored inline with the data
(`lineMem` entry = 256 data bits + PRED_BITS_PER_BEAT, beat-granular BRAM).

`ambiguousLine` is set only when refill-time predecode had to GUESS: the extension
word that disambiguates brief- vs full-format EA lives past the 64-byte line
boundary, in a line not yet fetched. **Nothing ever resolves that bit afterwards.**
So to cover a line-straddling corner case, the frontend re-runs the entire
1338-line full-ISA classifier EVERY CYCLE into `p0LiveReg`. The common case — where
the cache already knows the length exactly — pays for the rare one.

## The design

Resolve the straddle at REFILL (across lines), publish the answer through a TOKEN,
and let the classifier become a rare fallback that may take an extra cycle.

### 1. Token encoding — zero extra storage

`Aligner.scala:85` is `val p0 = Mux(preds(0).ambiguousLine, p0LiveReg, preds(0))`:
when `ambiguousLine` is set the cached entry is discarded WHOLESALE. So its
`simple` (1) + `lenWords` (4) are dead payload in exactly that case, and can be
reinterpreted as `{hasToken, idx[3:0]}` -> 16 side-table entries. The line array
does not grow, needs no 8-bit padding, and needs no read-modify-write.

`size` is NOT reusable: it is not ambiguity-qualified and `Aligner` reads
`preds(0).size` directly in both arms of the mux.

### 2. Side table

16 entries of `{valid, ownerKey, lenWords}`, `ownerKey = {way(2), set(6), word(5)}`
= 18 bits x 16 -> a couple of LUTRAMs, with its OWN write port so the lazy drain
never contends with `lineMem`.

### 3. Refill-time resolver

On the fill of line N+1, the extension word that line N's tail instruction was
missing is now in hand: resolve that instruction's true length, allocate a side
entry, and set the token in line N's predecode. Also handle the mirror case: when
filling line N, if N+1 is ALREADY resident (sequential prefetch often puts it
there), resolve immediately rather than waiting for a refill that may never come.

### 4. Lazy write-back

The fix-up parks in a 1-2 entry queue `{way, set, beat, word, payload}` and drains
on any idle `lineMem` write-port cycle. Refill always wins by construction. Drop
when full. **Kill the pending entry on any conflicting fill to the same {way,set}
and on `invalidateAll`** (CINV/CPUSHA) — see the safety argument below.

## Safety: every failure mode lands on "resolve it live once more"

This is the load-bearing property, and it is why the TOKEN indirection was chosen
over writing a corrected length back into the line.

- **Eviction.** The token lives IN the line's predecode, so it dies with the line;
  a refill rewrites predecode wholesale and the old token is unreachable. There is
  no path by which a stale fix-up writes a wrong length into a LIVE line — which
  would be the dangerous direction (frontend frames garbage, executes wrong bytes;
  this codebase has been bitten by that shape twice already: the duplicate
  outstanding AXI id after CPUSHA, and the stale `lv` fetch buffer).
- **Token reuse.** If entry K is recycled for a different line, the `ownerKey`
  compare fails on read and we fall back to the live classify.
- **Queue full / port busy / line not resident.** Drop the fix-up; the frontend
  resolves live exactly as it does today.

So the only invariant to protect is: *never write to a line that is not the one we
resolved*. Everything else degrades to today's behaviour.

## Payoff

`PredecodeWord.classify` stops being a 30-level combinational cone evaluated every
cycle into `p0LiveReg` and becomes a rare fallback that can be pipelined or stalled
(the owner explicitly granted the extra cycle of latency for straddles). That is
what moves 200 MHz; the `availEffPrev` retime (landed separately) only shortens the
cone's INPUTS and is a stopgap.

## Open choices (not risks)

- Whether the refill resolver re-reads line N's tail opcode, or whether predecode
  stores a small hint (base length + which EA slot was guessed) so resolution needs
  only the extension word.
- How the side-table read stage interacts with the existing `.elsewhen(p0.ambiguousLine)`
  stall arm in `Aligner`.
