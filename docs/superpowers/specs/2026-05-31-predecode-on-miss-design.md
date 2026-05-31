# Predecode-on-Miss — Design

**Status:** Draft for review
**Date:** 2026-05-31
**Parent spec:** `docs/superpowers/specs/2026-05-31-m68k-040-ooo-architecture-design.md` (ch 1 Frontend length scanner / 2-wide; invariants #2 FMax, #3 plugin boundaries)
**Builds on:** the merged I-cache slice (`docs/superpowers/specs/2026-05-31-icache-slice-design.md`).
**Slice position:** second frontend slice. Produces predecode metadata at line-fill time; the fetch/align stage that *consumes* it for 2-wide selection is a later slice.

---

## 1. Idea & rationale

Run an instruction **predecoder over each line as it is refilled** and cache the result in a parallel
predecode array, so the fetch *hit* path gets instruction-boundary metadata for free. This moves the
m68k variable-length scan off the 250 MHz hot path and onto the multi-cycle refill path. Because
predecode runs during refill (not fetch), **the classifier's logic depth is unconstrained by FMax** —
its only cost is area. The metadata enables the later align stage to find two instruction starts and
issue 2-wide.

**Key simplification (opword-only):** the classifier looks only at each 16-bit opword. m68k
`length = 2 + 2·extwords`, and for every non-indexed EA mode the extword count is fully determined by
the opword (size field + mode/reg bits). So:
- There is **no line-crossing problem for predecode** — every opword is 2 bytes fully in-line (32 word
  positions at even bytes 0..62), and length is opword-determined even when the instruction's extension
  *bytes* spill into the next line. Byte availability across lines is the align stage's concern.
- "unknown" collapses to `simple=false` (complex) — no separate boundary marker.

## 2. Scope

**In this slice:**
- `PredecodeWord`: a pure combinational opword → `ChunkPredecode` classifier (the "decoder").
- A **dedicated PREDECODE cycle** in the I-cache refill FSM that classifies all 32 words of the
  filled line and writes a per-line predecode array.
- Expose the fetched window's predecode (4 chunks) in the fetch response.
- Standalone verification of the classifier + the I-cache integration.

**Out of scope (later slices):**
- The fetch/align stage: chaining `lenWords` to locate the two instruction starts, 2-wide pairing
  rules. This slice only *produces* the metadata.
- Brief-format indexed → simple (the +1-word-peek upgrade); full-format indexed; `CINV`/non-cacheable
  predecode; predecode of the bypass path.

## 3. Data types

```
ChunkPredecode {
  simple  : Bool          // fast-path opcode + non-indexed simple EA mode
  lenWords: UInt(3 bits)  // 1..5 (= 2/4/6/8/10 bytes); meaningful only when simple
}                          // 4 bits per chunk
```
- `simple=False` ⇒ complex (indexed/microcoded/privileged/etc.); `lenWords` is don't-care.
- Per line: 32 chunks × 4 bits = **128 bits**.

## 4. The classifier — `PredecodeWord` (opword-only, pure combinational)

Input: one 16-bit opword. Output: `ChunkPredecode`.

Decode using opword fields: class `op[15:12]`, plus per-class size and EA `mode[5:3]`/`reg[2:0]`.
Produce `simple=1` + exact `lenWords` for the fast-path opcode classes with a **non-indexed simple EA
mode**; otherwise `simple=0`.

**Fast-path opcode classes (simple when EA is simple):** `MOVE`/`MOVEA`, `ADD`/`ADDA`/`ADDQ`,
`SUB`/`SUBA`/`SUBQ`, `AND`, `OR`, `EOR`, `CMP`/`CMPA`, `MOVEQ`, `LEA`, `PEA`, `Bcc`/`BSR`/`BRA`,
`DBcc`, `Scc`, simple shifts/rotates (`ASL/ASR/LSL/LSR/ROL/ROR/ROXL/ROXR` register/memory forms),
`TST`, `CLR`, `NOT`, `NEG`, `SWAP`, `EXT`, `NOP`.

**Simple EA modes (extword count known from opword):**
| mode/reg | EA | extwords |
|---|---|---|
| 000 Dn / 001 An / 010 (An) / 011 (An)+ / 100 −(An) | register/indirect | 0 |
| 101 (d16,An) | displacement | 1 |
| 111/010 (d16,PC) | PC-disp | 1 |
| 111/000 abs.W | absolute short | 1 |
| 111/001 abs.L | absolute long | 2 |
| 111/100 #imm | immediate | 1 (.B/.W) or 2 (.L) — from size field |

`lenWords = 1 (opword) + Σ extwords` over the instruction's operand(s). For two-EA `MOVE`, sum source
and destination extwords (e.g. `MOVE.L #imm32, abs.L` = 1+2+2 = 5 words = 10 bytes).

**Complex (`simple=0`):** indexed modes `110 (d8,An,Xn)` and `111/011`/`111/100`-PC-indexed (brief or
full), memory-indirect, `MOVEM`, `MOVEP`, `CAS`/`CAS2`, bitfield ops, `MOVES`, `CHK2`/`CMP2`, F-line,
MMU/`PFLUSH`/`PTEST`, `TAS`, privileged/`STOP`/`RESET`/`RTE`, and any opword not in the fast set.

The classifier is a flat combinational function (LUT-like); spec correctness is defined by the Scala
reference in the tests (§7), which is the single source of truth for the opword→`ChunkPredecode` map.

## 5. I-cache integration

Add to `IcachePlugin`:
- `predMem = Seq.fill(ways)(Mem(Bits(128 bits), sets))` — one packed predecode entry per (way, set),
  alongside `tagMem`/`dataMem`.
- **Refill FSM gains a `PREDECODE` state:** `IDLE → REFILL → PREDECODE → REPLAY → IDLE`.
  - `REFILL`: capture the 2 × 256-bit beats into the data array (as today) **and** into a 512-bit
    line register `lineReg`.
  - `PREDECODE` (one cycle): instantiate **32 `PredecodeWord`** over `lineReg`'s 32 words; pack the
    results into 128 bits; `predMem(victim).write(set, packed)`; **also** commit `tagMem(victim)` and
    set `valids(victim)(set) := True` here (so a line becomes valid only once *both* data and
    predecode are written — a hit always sees consistent data+predecode). → `REPLAY`.
  - `REPLAY`: as today (registered response), now also driving the predecode fields (below).
- **Hit path:** read `predMem(hitWay)` async alongside data; select the 4 chunks covering the fetched
  64-bit window (the 4 words at `pc[5:3]·4 .. +3`); drive into the response.
- `FetchRsp` gains `pred: Vec(ChunkPredecode, 4)` (the window's 4 chunk-predecodes). The registered
  response carries it with the same 1-cycle latency.

Tag/valid commit moving from `REFILL`'s `r.last` into `PREDECODE` is the only behavior change to the
existing FSM; data capture and the registered response are unchanged in spirit.

## 6. Data flow / timing

- Hit latency unchanged (registered response; `predMem` read parallels `dataMem` read).
- Miss latency: +1 cycle (the PREDECODE stage) versus the current I-cache. Acceptable — it is amortized
  over all subsequent hits to the line, and miss latency is dominated by AXI refill anyway.
- The 32-way parallel classifier in `PREDECODE` is one cycle of combinational logic at refill rate
  (not fetch rate). If it proves too deep for one cycle, it pipelines into two PREDECODE cycles with no
  hot-path impact — but one cycle is expected to suffice given opword-only depth.

## 7. Verification

- **`PredecodeWord` (pure function):** a SpinalSim `PredecodeWordSpec` sweeps a representative set of
  opwords and compares `{simple, lenWords}` against a **pure-Scala reference** (`PredecodeRef`) that
  encodes the same opword→class/length map — the single source of truth. Plus a **curated table** of
  real m68k opwords with hand-computed expected results (e.g. `MOVEQ`=`(1,simple)`,
  `ADD.L #imm32,Dn`=`(3,simple)`, `MOVE.L #imm32,abs.L`=`(5,simple)`, `(d8,An,Xn)` op=`complex`,
  `MOVEM`=`complex`, `Bcc.w`=`(2,simple)`).
- **Stretch oracle:** cross-check `lenWords·2` against **Musashi's `m68k_disassemble`** (returns
  instruction length) over a sweep — strongest confidence. Requires a small `musashi_run --disasm`
  mode (or a separate tool); deferred to a follow-up, not blocking this slice.
- **I-cache integration:** extend `IcacheSpec` — place known instruction bytes in the AXI test memory,
  fetch, and assert the returned `pred` matches `PredecodeRef` over those bytes; confirm a line becomes
  hit-valid only after PREDECODE (data+predecode consistent).

## 8. Files & structure

| File | Responsibility |
|---|---|
| `src/main/scala/m68k040/cache/IcacheTypes.scala` (modify) | add `ChunkPredecode`; add `pred` to `FetchRsp` |
| `src/main/scala/m68k040/frontend/PredecodeWord.scala` | the opword classifier (combinational) |
| `src/main/scala/m68k040/cache/IcachePlugin.scala` (modify) | `predMem`, PREDECODE state, window-pred output |
| `src/test/scala/m68k040/frontend/PredecodeRef.scala` | pure-Scala reference classifier (test oracle) |
| `src/test/scala/m68k040/frontend/PredecodeWordSpec.scala` | classifier sweep + curated-opcode tests |
| `src/test/scala/m68k040/cache/IcacheSpec.scala` (modify) | predecode-via-fetch integration tests |

## 9. Open items for the plan
- Exact `PredecodeWord` decode table (per opword class) — enumerated in the plan from the m68k opcode
  map; `PredecodeRef` and the RTL share the same table definition conceptually.
- `ChunkPredecode` packing order in the 128-bit word (chunk 0 = bytes 0–1, LSB-first) — fix in the plan.
- Whether `predMem` read is async (LUTRAM) or sync (BRAM) — baseline async to match the registered
  response timing; revisit if area-pressured.

## 10. Known divergences / deferrals (logged)
- Opword-only: indexed-mode instructions classify as complex (no fast-path length) until the
  +1-word-peek upgrade.
- 2-wide selection/pairing lives in the align slice; this slice only produces metadata (no consumer yet
  — tested standalone).
- Musashi-disasm cross-check deferred.
