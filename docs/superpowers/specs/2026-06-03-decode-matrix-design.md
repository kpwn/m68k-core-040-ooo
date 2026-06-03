# Full Decode Matrix — EA-agnostic decode / opcode-agnostic EA — Design

**Status:** Draft for review
**Date:** 2026-06-03
**Parent spec:** `2026-05-31-m68k-040-ooo-architecture-design.md` (ch 2 decode / µop expansion, ch 4 clusters, ch 5 frontend). Builds on the merged `2026-06-01-decode-simple` slice (`SimpleDecodeUnit`/`DecodeStage`).
**Reference:** NaxRiscv `DecoderPlugin` (declarative masked-pattern → microop-field decode — the project's locked FPGA-at-high-clock reference). [[project-040-ooo-overview]].

## 1. Purpose

Define the **decode framework** that lets the full m68k opcode × addressing-mode matrix be added incrementally without blowing up LUTs or FMax. The organizing principle (user-locked): **decode is EA-agnostic** (the operation decoder never looks at the effective-address mode) and **EA decode is opcode-agnostic** (one addressing-mode decoder serves every instruction). The two are combined by a separate µop-assembly/cracking step.

This is a **framework** design: it specifies the structure and contracts so opcodes/modes are filled in as table rows over many later slices. It is NOT a full opcode enumeration.

## 2. Scope

**In (framework, designed here):**
- The three decoupled sub-units and their data contracts: `OperationDecoder` (EA-agnostic, table-driven), `EaDecoder` (opcode-agnostic), `MicroOpAssembler` (cracker).
- µop expansion model (1 instruction → 1–4 µops) + the expansion queue between decode and rename.
- Temp-register model (scratch regs in the rename space for cracked operands).
- The masked-pattern decode-table mechanism + the FMax/LUT discipline.
- The expanded `DecodedUop` contract (operand roles, temps, micro-op fields).

**In (first implementation slice):**
- Implement the table-driven `OperationDecoder` + `EaDecoder` + `MicroOpAssembler` covering **register-direct and immediate** EA modes only (`Dn`, `An`, `#imm`), replacing the hand-written `SimpleDecodeUnit` body for the ops it already supports (MOVE/MOVEQ/ADD/SUB/AND/OR/CMP) and proving the table + two-decoder split + expander end-to-end via Musashi lock-step. **No LS cluster needed** (no memory access).

**Out (designed, deferred to later slices):**
- **Memory-EA cracking** (load/store µops, AGU, temps-for-memory) — blocked on the **LS cluster** (AGU + D-cache + store queue + load/store EU), which does not exist yet. The framework reserves the contract; implementation follows the LS cluster.
- **Complex addressing modes** (`(d8,An,Xn)`, 020+ full extension formats, memory-indirect) — need the EA cluster + LS cluster.
- **Microcoded instructions** (MOVEM, MUL/DIV multi-step, MOVEP, bit-field, BCD, privileged/MMU) — the framework reserves a `microcoded` µop path; the microcode-ROM sequencer is its own slice.
- **Extension-word width past 7 words** (the predecode `lenWords` limit) — logged.

## 3. Current state

`SimpleDecodeUnit.decode(pkt: DecodePacket): DecodedUop` is pure-combinational, register/immediate only, one µop per instruction, hand-written `when`-trees over the line nibble (only MOVEQ + reg-reg ALU). `DecodedUop` carries arch regs (`srcAReg`/`srcBReg`/`dstReg`, 4 bits each) + flags + branch fields; no EA descriptor, no temps, no micro-op sequencing. `DecodeStage` maps each aligned slot through `SimpleDecodeUnit` 1:1 and skids the output (`PipeStage`). Backend today: 2 ALU EUs + 1 branch EU + 3 PRFs; **no load/store / AGU / D-cache**.

## 4. Architecture

### 4.1 Pipeline placement + µop expansion
Decode is no longer 1:1 with instructions. Each of the 2 decode slots expands one instruction into a **1–4 µop** sequence. A small **µop expansion queue** (`MicroOpQueue`) buffers the variable-rate expansion and is drained **2-wide** into rename (`RENAME_WIDTH`). For the first slice (reg/imm) every instruction expands to exactly 1 µop, so the queue is degenerate (1:1) — but the queue + the variable-length contract are built now so memory-cracking slices don't restructure the pipe.

```
… → Align → DecodeStage (2 slots: OperationDecoder ‖ EaDecoder → MicroOpAssembler)
         → MicroOpQueue (variable-in, 2-wide-out) → Rename → …
```

The existing `PipeStage` flush discipline (mispredict squash) extends to the queue (a flush clears it; it holds pre-rename µops, no architectural state). FMax lever: if the assembler/table lands on the critical path, insert a `PipeStage` between decode and the queue (same approach as the frontend-pipeline slice).

### 4.2 OperationDecoder (EA-agnostic, table-driven)
Input: the 16-bit opword (and, for a few ops, size/format sub-fields — but **never** the EA field bits 5–0). Output `OpSpec`:
```
OpSpec {
  op          : DecOp           // ADD/SUB/AND/OR/CMP/MOVE/BRANCH/… (extended as the matrix grows)
  size        : Size            // B/W/L (from the size field where present)
  srcRole     : OperandRole     // none | dReg(field) | aReg(field) | imm | EAsrc | EAdst
  dstRole     : OperandRole
  readsNzvc, writesNzvc, readsX, writesX : Bool
  cluster     : Cluster         // INT | EA | LS | CPLX steering hint
  isBranch    : Bool;  cond : Bits(4)
  microcoded  : Bool;  ucodeHandle : UInt   // reserved; microcode ROM slice fills this
  illegal     : Bool
}
```
`OperandRole` names *where* an operand lives (a register field, an immediate, or "the EA-field operand"), **not** the EA mode. `EAsrc`/`EAdst` mean "this operand is whatever the EA field decodes to" — register or memory is the `EaDecoder`'s call. This is the EA-agnostic boundary.

Implemented as a **masked-pattern table**: a list of rows `(mask, match) → OpSpec field assignments`, **grouped by line nibble** (bits 15–12) so each line's parallel matcher has bounded fan-in. Rows mask out bits 5–0 (the EA field), so a row matches regardless of addressing mode. Authoring an instruction = adding a row. (SpinalHDL builds the matcher + field-mux; mirrors NaxRiscv `DecoderPlugin`.)

### 4.3 EaDecoder (opcode-agnostic)
Input: a 6-bit EA field (`mode` 5–3, `reg` 2–0), `size`, and the instruction's extension words. Output `EaSpec`:
```
EaSpec {
  klass    : EaClass   // dataReg | addrReg | imm | memSimple | memComplex | pcRel | absShort | absLong
  baseReg  : UInt(4)   // An / PC sentinel
  useIndex : Bool;  index : IndexSpec     // (complex only)
  disp     : SInt(32)
  preDec, postInc : Bool                  // -(An) / (An)+ side-effects
  imm      : Bits(32)
  extWords : UInt(3)   // ext-word consumption (cross-checked vs predecode length)
}
```
Pure combinational, identical for every instruction, instantiated once per EA field (MOVE source + dest = two `EaDecoder`s). The mode table (000=Dn … 111/4=#imm) is fixed m68k. `memSimple` = `(An),(d16,An),(xxx).W/.L,(d16,PC),(An)+,-(An)`; `memComplex` = `(d8,An,Xn)` + 020 full-format. For the first slice only `dataReg`/`addrReg`/`imm` paths are exercised; the memory `klass`es are defined but unused until the LS cluster.

### 4.4 MicroOpAssembler (cracker)
Combines `OpSpec` + `EaSpec`(s) → an ordered 1–4 `DecodedUop` sequence:
- **reg / imm** operand → operand fields on the operation µop directly (no extra µop). *(first slice = this path only)*
- **memSimple EAsrc** → load µop (LS cluster, AGU = base+disp **inside** the load µop) writing a temp; operation µop reads the temp.
- **memSimple EAdst** → operation µop writes a temp; store µop (LS) writes temp→mem.
- **memComplex** → a preceding **EA µop** (EA cluster) computes the address into an addr-temp, fed to the load/store µop.
- **`(An)+` / `-(An)`** → an **An-update µop** (or fold An-writeback into the load/store µop) in addition to the access.
- **JSR/BSR** → store(return PC) + branch µop (per the locked branch-cracking direction). *(branch already exists; call-cracking is its own slice.)*
- **microcoded** → emit a single `microcoded` handle µop; the microcode sequencer (later slice) expands it.

The assembler is the ONLY unit that sees both `OpSpec` and `EaSpec`; the decoders stay agnostic. The crack pattern is selected by `EaSpec.klass` (simple vs complex vs reg/imm), **independent of `OpSpec.op`** — preserving the opcode-agnostic-EA principle.

### 4.5 Temp registers
Add a small fixed set of temp arch regs to the int and addr rename spaces — sized to the max cracking depth (≤4 µops ⇒ ~2 int temps + ~2 addr temps; exact count finalized when memory cracking is implemented). The cracker assigns temps within one instruction's µop group; they carry data load→op and op→store, are dead at the instruction boundary, and are freed on commit like any reg. Impact: `archCount` (int RAT 16→16+nT; addr likewise) and freelist `physCount` bump — both already parametric on the location-bit RAT + freelist; the `commHead`/pointer rollback (3d-1b) is unaffected. **First slice adds no temps** (reg/imm needs none); the temp regs are introduced with the first memory-cracking slice.

## 5. LUT / FMax discipline
- **Line-grouped matcher:** the top nibble selects a per-line row-set; matching within a line keeps fan-in shallow.
- **EA decode is shared, narrow logic** (6-bit mode + ext-word slice), not part of the opcode matcher.
- **Pipelining is the escape hatch:** decode is already a `PipeStage`; if synth shows the table/assembler critical, add a register between decode and the µop queue (the frontend slice proved this recovers FMax with no behavioral change — lock-step is latency-agnostic).
- **Synth gate** each slice: `GenFullCoreSynthVerilog` OOC ≥250 MHz; report WNS + critical path; the decode table must not become the critical path (or be pipelined if it does).

## 6. Extension words
Decode needs the full instruction words (disp/index/imm) for `EaDecoder`. The aligner delivers them via `DecodePacket.words`; predecode already computes `lenWords` (consuming ext words). **Constraint:** predecode `lenWords` is 3 bits (≤7 words); 040 worst-case (full extension + two 32-bit displacements) reaches ~11 words — widen `lenWords`/`words` or add a slow path. Logged as a deferral; the first slice (reg/imm, ≤1 ext word for `#imm.L`) is unaffected.

## 7. Verification
- **Unit (decoupled):** `OperationDecoderSpec` (opword → `OpSpec` rows, table coverage) and `EaDecoderSpec` (mode+ext → `EaSpec`) tested independently — the decoupling is what makes per-unit testing possible.
- **Cracker + end-to-end:** Musashi lock-step (`ExecuteLockStepSpec`-style), grown incrementally — each opcode/mode group added gets lock-step programs over representative op×mode combinations vs Musashi (the architectural-correctness gate).
- **Determinism:** every lock-step run twice (per the established seed-flakiness discipline; see [[spinalhdl-sim-poke-gotchas]]).
- **Synth gate** per §5.

## 8. First slice (concrete) + deferrals
**First slice — "decode-matrix-1: table-driven reg/imm decode":**
1. Define `OpSpec`, `OperandRole`, `EaSpec`, `EaClass` contracts + the expanded `DecodedUop`/µop fields (operand roles, micro-op count=1, `microcoded`/`illegal`), keeping the existing `DecodedUop` consumers (rename) compiling.
2. `OperationDecoder` masked-pattern table (line-grouped) for the existing op set (MOVE/MOVEQ/ADD/SUB/AND/OR/CMP) — rows EA-field-masked.
3. `EaDecoder` for `Dn`/`An`/`#imm` (the reg/imm `klass`es).
4. `MicroOpAssembler` reg/imm path (1 µop/instr) + the `MicroOpQueue` (degenerate 1:1 now, variable-length contract built).
5. Wire into `DecodeStage` replacing the `SimpleDecodeUnit` body; re-verify the existing lock-step corpus (results unchanged) + add reg/imm op×mode programs; synth gate ≥250 MHz.

**Deferrals (each its own later slice, in rough order):** LS cluster (AGU/D$/store-queue/load-store EU) → memory-EA cracking (memSimple, temps, load/store µops) → `(An)+/-(An)` side-effects → EA cluster + complex modes → microcode ROM (MOVEM/MUL/DIV/…) → extension-word width fix → call-cracking (JSR/BSR).

## 9. Open items
- Exact temp count + whether int/addr temps share or split a freelist (decide with the first memory slice).
- Whether the µop queue is a distinct component or folded into the existing decode→rename skid (decide when expansion >1 µop first appears).
- `cluster` steering values are placeholders until the EA/LS/CPLX clusters exist.
- Microcode handle encoding (reserved field width) — set when the microcode slice starts.
