# Fetch/Align Stage — Design

**Status:** Historical baseline; the 2026-08-09 depth-3 ring addendum below is binding
**Date:** 2026-05-31
**Parent spec:** `docs/superpowers/specs/2026-05-31-m68k-040-ooo-architecture-design.md` (ch 1 Frontend; invariants #2 FMax, #3 plugin boundaries)
**Builds on:** merged I-cache (`FetchService`) + predecode-on-miss (`FetchRsp.pred`).
**Slice position:** third frontend slice. Consumes `FetchService`; produces a 2-wide decode-packet stream. The decode stage that consumes it is a later slice.

---

## 1. Purpose

Turn the I-cache's 8-byte fetch windows (+ per-word predecode) into a stream of **decode packets** — one per m68k instruction, two per cycle when possible — by buffering fetched words and chaining instruction boundaries through the predecode `lenWords`. This is where predecode-on-miss finally enables 2-wide.

**Key principle (from brainstorming):** the aligner masters runs of **simple** instructions (predecode gives their length → boundaries chain cheaply). A **complex** instruction has no predecode length, so the aligner emits it 1-wide and **stalls** until a downstream `resume` PC (its real length, computed by decode/microcode later) is supplied. This keeps the aligner from re-implementing decode.

---

## 2. Scope

**In this slice:**
- `FetchControl`: PC sequencer driving `FetchService.cmd`; `redirect` and `resume` control ports.
- `InstructionBuffer`: word-granular queue of `{word, ChunkPredecode}` accumulated from fetch windows.
- `Aligner`: 2-wide boundary chaining via predecode; complex → 1-wide + stall.
- `DecodePacket` output: `Stream(Vec(DecodePacket, 2))` with per-slot valid + backpressure.
- End-to-end verification through the real I-cache + identity translation + predecode.
- Packaged as a `FetchAlignPlugin` (FiberPlugin) resolving `FetchService`, exposing the decode-packet stream + the `redirect`/`resume` ports.

**Out of scope (later slices):**
- The decode stage that consumes `DecodePacket` (and the real driver of `resume`).
- Further branch-predictor changes. BTB/gshare/RAS prediction was added by later
  ratified slices and is intentionally unchanged by the depth-3 ring turnover update.
- Loop-stream detector; the real `redirect` driver (backend mispredict).

---

## 3. Control model — no prediction; sequential + redirect + resume

- **Sequential fetch:** `FetchControl` holds `nextFetchPc` (8-byte-aligned granularity), issues `FetchService.cmd` whenever the `InstructionBuffer` has room, advancing `nextFetchPc += 8`. Branches are emitted as ordinary instructions; the front-end keeps fetching fall-through until redirected.
- **`redirect: Flow(UInt(32))`** (input; driven externally — backend mispredict/exception/future predictor; tests now): flush the `InstructionBuffer`, set the decode PC and `nextFetchPc` to the redirect target, discard in-flight fetches (drop responses whose PC predates the redirect — see §6 staleness).
- **`resume: Flow(UInt(32))`** (input; driven externally — future decode/microcode after resolving a complex instruction's length; tests now): supplies the next decode PC after a stalled complex instruction; un-stalls the aligner.

## 4. Data types

```
DecodePacket {                    // one m68k instruction handed to decode
  pc        : UInt(32)            // instruction start PC
  words     : Vec(Bits(16), 10)   // live aligner visibility window
  wordCount : UInt(4 bits)        // valid words in `words` (1..10)
  simple    : Bool                // predecode simple bit (false => complex)
  lenWords  : UInt(4 bits)        // predecode length (meaningful iff simple)
  complex   : Bool                // !simple — decode/microcode resolves length
  fault     : Bool                // from FetchRsp.fault (translation/access fault at this PC)
}
```
Output port: `Stream(Vec(DecodePacket, 2))` plus a per-cycle `slotValid: Vec(Bool, 2)` (slot 0 always valid when the stream fires; slot 1 valid only on a 2-wide cycle). Consumer `ready` backpressures the aligner.

## 5. Components

### 5.1 `InstructionBuffer`
- A 20-word circular queue of `{word: Bits(16), pred: ChunkPredecode}`. The
  aligner sees a 10-word head window and each fetch response pushes at most four
  words. The extra capacity absorbs three outstanding 8-byte fetch windows while
  retaining a live decode head.
- **Enqueue:** when a `FetchRsp` arrives (8-byte window = 4 words + 4 preds), append the words at/after the fetch PC's position (the first fetch after a redirect may start mid-window: drop words before the decode PC). Each word carries its predecode chunk from `FetchRsp.pred`.
- **Dequeue/shift:** the aligner consumes 0–N head words/cycle; shift the queue by the consumed count.
- **Flush:** `redirect` clears it.
- Implemented as a circular register ring: dequeue advances a head pointer and
  enqueue writes only the tail slots. This avoids rebuilding the full buffer on
  every shift while keeping the head read asynchronous (invariant #2).

### 5.2 `Aligner`
Reads the head word + its predecode (and following words):
- **head `simple`, `lenWords = L0`, words `[0, L0)` present in buffer:** slot0 = `DecodePacket(pc=headPc, words=buffer[0..L0), simple, lenWords=L0)`. Candidate slot1 at buffer index `L0` (pc = headPc + 2·L0): if **`simple`, `lenWords = L1`, words `[L0, L0+L1)` present** → slot1 valid (2-wide), shift `L0+L1`, advance decode PC by `2·(L0+L1)`. Else slot0 only (1-wide), shift `L0`, advance by `2·L0`.
- **head `complex`:** slot0 = complex packet (`words` = the buffered words from head, `wordCount` = however many are buffered, capped at 5; `complex=true`), 1-wide. **Stall:** do not shift/advance; assert `stalledOnComplex`. Resume when `resume.valid` arrives → set decode PC to `resume.payload`, flush buffer ahead of it (or shift to it), clear stall. (The complex packet's bytes let a future decode/microcode begin; `resume` delivers the next PC once length is known.)
- **insufficient words buffered** for slot0's claimed length → stall (await more fetch); emit nothing.
- **fault:** if the head word's source fetch had `fault`, mark the packet `fault=true` (a faulting fetch still produces a packet so the exception is delivered in order; first cut: identity translation never faults, but carry it).

Output fires (`stream.valid`) when slot0 is emittable and the consumer is `ready`.

### 5.3 `FetchControl`
- Tracks up to three accepted fetches in an in-order record ring. Each record owns
  its redirect-stale bit and its first-window leading-word drop. The I-cache hit
  pipeline returns responses in issue order, so the next response consumes the
  record at the ring head while a new request allocates the tail.
- Issues `FetchService.cmd.pc = nextFetchPc` when the IBuf reservation and record
  capacity both permit it, incrementing `nextFetchPc` by 8 per accepted fetch.
  The conservative landing-space invariant is
  `ibufCount + 4*ringCount + 4 <= BUF_WORDS`: it reserves four words for every
  existing response, including one arriving this cycle, plus the new request.
- **Consume-and-replace invariant:** a full ring is available for issue when a
  non-faulting head response is valid in that same cycle. Thus record capacity is
  `ringCount < RING || (rsp.valid && !rsp.fault)`, not merely
  `ringCount < RING`. A fault response intentionally receives no full-ring
  turnover credit: `faultHold` first latches on this cycle, and the resident-hit
  optimization must not admit an additional younger request at that boundary.
  Non-full behavior is unchanged. On the full-ring
  collision `head == tail`; response classification must read the old head
  stale/drop record, while the clock edge writes the replacement tail record and
  advances both pointers. Occupancy remains three. `rsp.valid` is registered and
  independent of `cmd`, so this adds no combinational ready loop.
- On `redirect`: `nextFetchPc := decodePc := redirect.payload`; flush buffer; ignore the in-flight response (staleness §6).
- On `resume`: `nextFetchPc`/`decodePc` continue from `resume.payload` (used to restart after a complex stall, which may require refetching from that PC if the buffer was cleared past it).

## 6. Staleness / ordering

The I-cache resident-hit port is pipelined and returns accepted requests in order.
The three-entry record ring associates each untagged response with the oldest
request. A redirect/resume marks every existing record stale; an issue coincident
with that redirect used the old fetch PC and is born stale as well. Its eventual
response is discarded. The redirect's leading-word drop remains in `pendingDrop`
for the first subsequently issued target window.

Consume-and-replace does not weaken this rule. If response, replacement issue, and
redirect coincide on a full ring, the response consumes the old head record, the
replacement receives the same-cycle redirect-stale mark, and the redirect's drop
belongs to the later target request. No response may observe the newly written
tail metadata in place of the old head metadata.

## 7. Verification (end-to-end through the real frontend)

Host `ParamPlugin + IdentityTranslationPlugin + IcachePlugin + FetchAlignPlugin`. Preload AXI memory with a hand-laid-out instruction stream via `attachMemoryWithWords`. Drive `redirect` to start at a base PC, then consume the `DecodePacket` stream and assert:
1. **Sequential simple stream, 2-wide:** a run of 1-word simple ops (e.g. `MOVEQ`,`MOVE D0,D1`,…) emits two packets/cycle with correct `pc`/`lenWords`/`words`.
2. **Mixed lengths:** `MOVE.L #imm32,Dn` (3 words) followed by `MOVEQ` (1) — correct boundaries, `words`/`wordCount`, PCs.
3. **Window/line spanning:** an instruction whose words span two 8-byte fetch windows (and a 64-byte line) is emitted only once fully buffered, with correct bytes.
4. **Complex stall→resume:** a deferred instruction (the directed fixture is
   `NBCD (A0)`, opcode `0x4810`) emits a 1-wide `complex` packet, the aligner
   stalls; driving `resume` with the next PC continues the stream.
5. **Redirect:** mid-stream `redirect` flushes and restarts decode at the new PC; no stale packets.
6. **Backpressure:** holding consumer `ready` low stalls the aligner without losing/duplicating packets.
7. **Full-ring turnover:** reach `ringCount == 3`, present the head response while
   the IBuf reservation permits another request, and require `cmd.fire` in that
   same cycle. Prove both pointers advance, count stays three, and all responses
   emerge in original request order.
8. **Turnover redirect collision:** collide a full-ring head response, replacement
   issue, and unaligned redirect. Prove all old-path records including the
   replacement are discarded, then prove the target response applies its own
   leading-word drop and is the first emitted packet.
9. **Fault boundary:** with a full ring, inject a non-stale fault response and prove
   no replacement command fires; the response frees one record and latches the
   fault hold. This check prevents the hit-path optimization widening fault-path
   speculation.

These exercise **I-cache → predecode → align** together (the align test is a frontend integration test).

## 8. Files & structure

| File | Responsibility |
|---|---|
| `src/main/scala/m68k040/frontend/DecodePacket.scala` | `DecodePacket` bundle |
| `src/main/scala/m68k040/frontend/InstructionBuffer.scala` | word+pred queue (enqueue/shift/flush) |
| `src/main/scala/m68k040/frontend/Aligner.scala` | 2-wide boundary chaining + complex stall |
| `src/main/scala/m68k040/frontend/FetchAlignPlugin.scala` | FiberPlugin: FetchControl + buffer + aligner; resolves FetchService; exposes decode stream + redirect/resume |
| `src/main/scala/m68k040/services/Services.scala` (modify) | add `DecodeFeedService` (the 2-wide DecodePacket stream) |
| `src/test/scala/m68k040/frontend/FetchAlignSpec.scala` | end-to-end frontend integration tests (§7) |

## 9. Open items for the plan
- Exact `InstructionBuffer` implementation (shift-register vs circular FIFO) and enqueue alignment (dropping pre-PC words on the first post-redirect fetch).
- Whether the aligner is purely combinational over the buffer head (registered output) or has its own stage; baseline: combinational select + registered `DecodePacket` output (1-cycle), uniform with the rest.
- `words` extraction: pull `lenWords` consecutive 16-bit words from the buffer head into the packet's `Vec(5)`.
- Resume semantics detail: does `resume` always require a refetch (buffer flushed) or can the buffer retain post-complex words? Baseline: flush at complex, refetch from `resume` PC (simplest; optimize later).

## 10. Known divergences / deferrals (logged)
- The later BTB/gshare/RAS predictor remains decode-directed: a correctly predicted
  taken branch still redirects and restarts fetch. Fetch-directed prediction is a
  separate design and is not part of this ring change.
- Complex instructions are 1-wide and stall for `resume` (driven by tests now; real driver = decode slice).
- Three resident-hit fetches may be outstanding. Demand-refill ordering remains
  blocking because `FetchRsp` itself is untagged.
- No loop-stream detector.
