# Bit operations (BTST / BSET / BCLR / BCHG) — design

**Date:** 2026-06-10
**Status:** approved (brainstorm) — ready for implementation plan
**Branch (to be):** `feat/bit-ops`

## Goal

Implement the four 68k single-bit instructions **BTST / BSET / BCLR / BCHG**, both the
**static** (bit number in an extension word) and **dynamic** (bit number in Dn) forms, with
**register** (Dn) and **memory** destinations. Each tests bit *n* and sets **Z = complement
of that bit**; all but BTST then set / clear / toggle it. Resumes the ISA-completion track
([[isa-completion-roadmap]]); gates at **≥200 MHz** (the relaxed gate, see
[[synth-gate-every-slice]]).

## Context

The machinery is in place: `OperationDecoder` already earmarks line-0 opmode-4 as
"bit/BTST-imm"; `DecOp.SHIFT` is the exact template (an op with a 2-bit sub-kind field +
an imm-or-reg operand, register-or-memory); and the **mem-RMW crack** (load→op→store) used
by line-0 immediates and line-4-to-memory is reused directly. Bit-ops add no new addressing
machinery — memory destinations use whatever EA modes the current `EaDecoder`/mem-RMW
already support (`(An)`, `(d16,An)`, `(xxx).W/.L`); the `-(An)`/`(An)+`/indexed modes stay
deferred (they land with MOVEM).

## Encoding (line 0)

All four ops live in line 0 (`0000`). Decode by `bit8` then disambiguate:

- **Dynamic** `0000 rrr 1 tt mmmrrr`: `bit8=1`, `rrr`(11:9)=Dn holding the bit number,
  `tt`(7:6)=op, EA=bits 5:0. **Exception: `mode==001` (An-direct) is MOVEP, NOT a bit-op**
  → route to illegal/deferred (out of scope).
- **Static** `0000 1000 tt mmmrrr` + extension word: `bits 11:8 == 1000` (opmode 4, bit8=0),
  `tt`(7:6)=op, EA=bits 5:0; the **extension word's low bits are the bit number**. (Note:
  for opmode-4, bits 7:6 are the op `tt`, NOT a size field — size is implicit, below.)
- `tt`: **00=BTST, 01=BCHG, 10=BCLR, 11=BSET**.
- Out of scope (route to existing illegal/deferred): MOVEP (dynamic, mode 001), MOVES
  (line-0 opmode 7).

**Operand width / bit modulo** is set by the destination, not an opword size field:
- **EA mode 000 (Dn)** → operate on the full **LONG**; bit number **mod 32**.
- **memory EA** → operate on a **BYTE** (read-modify-write); bit number **mod 8**.

BTST may also read the two PC-relative *source* EAs because it only tests and never writes:
`(d16,PC)` (mode 7/reg 2) and `(d8,PC,Xn)` (mode 7/reg 3), in both dynamic and static
bit-number forms. Predecode must frame these as `bitBase + 1` words for the displacement/
brief-indexed case (`bitBase=1` dynamic, `2` static); when the indexed extension selects
full format, the RTL uses its actual 1–5-word extension length. BCHG/BCLR/BSET must continue
to reject both PC-relative EAs because they require a data-alterable destination. The
separate immediate-EA form (`BTST Dn,#imm`) remains deferred in this slice.

## Architecture

### 1. Op + fields (`DecodedUop`)
Add `DecOp.BITOP` and a 2-bit `bitOp` field (`tt`), mirroring `DecOp.SHIFT`/`shiftOp`. The
bit-number source mirrors the shift count: **static** → `useImm` + `imm` (ext-word low
bits); **dynamic** → `srcB` (Dn). No new operand fields beyond these.

### 2. Decode (`OperationDecoder`)
In the line-0 path, before the existing opmode 0/1/2/3/5/6 immediates:
- `bit8=1 && mode!=001` → BITOP dynamic: `op:=BITOP`, `bitOp:=tt`, `srcB:=Dn`(11:9),
  `useImm:=False`; size/mod from the EA (Dn→LONG, mem→BYTE).
- `bits 11:8 == 1000` → BITOP static: `op:=BITOP`, `bitOp:=tt`, `useImm:=True`, `imm:=`ext
  word; size/mod from the EA.
- `bit8=1 && mode==001` (MOVEP) and opmode 7 (MOVES) → the existing illegal/deferred route.

### 3. Assemble (`MicroOpAssembler`)
- **Register dest (Dn):** one BITOP µop to the ALU EU (srcA=Dn data, bit number from
  imm/srcB). BTST writes no dst; BSET/BCLR/BCHG write Dn (full LONG, no partial merge since
  it's a .L bit op on Dn).
- **Memory dest:** **BTST** → a load-only µop (load byte → BITOP-on-byte → flags, NO store).
  **BSET/BCLR/BCHG** → the existing **mem-RMW crack** (load byte → BITOP-on-byte → store
  byte), same as line-0/line-4 mem-RMW.

### 4. Execute (ALU EU, fast path)
Bit-op datapath on the ALU EU (register form lat-1; the mem-RMW BITOP µop runs the same
datapath on the loaded byte):
- `bn = bitNumber & (size==LONG ? 31 : 7)` (mod from the source: imm for static, srcB for
  dynamic).
- `mask = U(1) << bn` (a 5- or 3-bit one-hot decode — shallower than the line-E barrel
  shifter, so the fast path holds at the 200 gate; fall back to the slow ALU path only if
  the synth gate complains).
- `Z = (data & mask) == 0`.
- `result = bitOp.mux(BTST -> data, BCHG -> data ^ mask, BCLR -> data & ~mask, BSET ->
  data | mask)`.
- **Flags: Z only.** Read the current NZVC, set Z := computed, **preserve N/V/C; X
  untouched** — reuse the EU's shallow flag-RMW path (the mechanism behind ANDI-to-CCR /
  NEGX's partial Z). BTST: flags only, no int writeback.

## Testing (gates, in order)

1. **`BitOpDecodeSpec`** (new) — the encoding: all 4 `tt`, static (+ext word) and dynamic,
   Dn vs memory EA → correct `op`/`bitOp`/`useImm`/`srcB`/size; MOVEP (mode 001) and MOVES
   route to illegal, not BITOP.
2. **Lock-step vs Musashi** — the matrix: {BTST,BSET,BCLR,BCHG} × {static, dynamic} ×
   {Dn-dest, memory-dest}, with bit-number edges **0, 7, 8(→mod-8 mem), 31, 32(→mod-32 Dn),
   63**. Confirm Z = ~bit and N/V/C/X unchanged; the 3 writing ops modify the right
   bit (Dn full-long / mem byte); BTST writes nothing. Memory forms exercise the load-only
   (BTST) and load-op-store (others) cracks.
3. **`make test-fast`** + the decode/crack regression — green.
4. **≥200 MHz full-core synth gate** — reported (relaxed gate; bit-op mask-gen is shallow).

## Non-goals

- 68020 bit-field ops (BFTST/BFSET/BFCLR/BFCHG/BFEXTU/…) — separate, later.
- MOVEP, MOVES — routed to illegal/deferred (out of scope here).
- New addressing modes — memory dest uses only the EA modes the current mem-RMW supports;
  `-(An)`/`(An)+`/indexed land with MOVEM.

## Risks

- **Z-only flag write** — must preserve N/V/C and X (bit-ops touch only Z). Mitigated by
  reusing the existing partial-flag-RMW path; lock-step asserts the other flags are
  unchanged across a bit-op.
- **Bit modulo by destination** — Dn = mod 32 on the long, memory = mod 8 on the byte; a
  swapped modulo is a silent wrong-bit bug. Covered by the bit-number-edge lock-step cases.
- **Encoding disambiguation** — dynamic bit-op (`bit8=1`) vs MOVEP (`bit8=1, mode 001`), and
  static bit-op (opmode 4) vs the other line-0 immediates. Covered by `BitOpDecodeSpec`.
- **BTST no-write / mem-BTST load-only** — BTST must not write a register or store to memory
  (a spurious store would corrupt memory). Covered by lock-step `checkMem` on mem-BTST.
