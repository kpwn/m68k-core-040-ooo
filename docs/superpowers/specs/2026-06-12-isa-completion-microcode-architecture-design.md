# ISA-completion architecture — fast decode-matrix crack vs µcode engine (the hot/cold split)

**Date:** 2026-06-12
**Status:** design (brainstorm) — for user review before the parallel-track dispatch
**Driver:** complete the integer 68040 ISA toward "fully functional" (the gate for the deferred 250 push). User steer: *"complex/cold instructions can be emitted by a µcode engine."*

## Principle — split the remaining ISA by TEMPERATURE, two emit mechanisms behind one DecodeStage

- **FAST decode-matrix crack** (the existing `OperationDecoder` + `EaDecoder` + `MicroOpAssembler`, ≤3-µop `AssembledUops` budget): instructions whose decode latency matters because they're COMMON. Stays single-cycle, combinational crack.
- **µCODE engine** (NEW — a generalization of the MOVEM micro-sequencer): a ROM/sequencer in `DecodeStage` that, on a COLD/COMPLEX opcode, holds `fed` and emits an arbitrary-length µop stream over multiple cycles via the SAME `pushProduced` machinery MOVEM already uses. Cold instructions don't care about a few decode cycles, so a sequencer is exactly right — and it retires per-instruction hand-cracking for the long tail.

The just-merged **MOVEM** micro-sequencer (`DecodeStage` FSM: latch state, hold `fed`, emit 2 µops/cycle, `pipeFlush` abort) is the **proof-of-concept** for this mechanism. The µcode engine is its generalization.

## The µcode engine (v1 scope = straight-line microcode)

**Routing.** `OperationDecoder` classifies a cold opcode as `microcoded` + emits a `ucEntry` (entry µPC into the µcode ROM) instead of a fast crack. `DecodeStage` routes: `microcoded` → the µcode engine owns emission (mutually exclusive with the fast-crack head and the MOVEM FSM).

**The ROM.** A Scala-defined table (`Vec` of µop descriptors, indexed by µPC). Each row:
- a µop *template*: `{op (DecOp), size, memOp, flags-written}`;
- *operand selectors* (a small mux vocabulary resolved by the engine from the latched instruction fields): `An`/`Dn` from the opcode, the instruction's `#imm`/extension word, the EA base/disp, the control-register id, and engine **temps** `T0/T1/…` (reuse the existing T0/T1 int temp regs the mem-RMW/predec cracks already use);
- *sequencing*: `nextUpc` (default µPC+1) + an `isLast` marker that releases `fed`.

**Emission.** The engine drives `pushProduced.uops(0..k)` + count each cycle (1–2/cycle, like MOVEM), holds `fed.ready := False` until `isLast`, `pipeFlush` aborts — all the MOVEM FSM machinery, parameterized by the ROM instead of MOVEM's bespoke mask loop.

**v1 = STRAIGHT-LINE only.** v1 handles FIXED-length cold sequences (no data-dependent looping/branching in µcode). The **data-dependent** ones — MOVEM's mask loop, variable-width bit-field, CAS retry — stay bespoke FSMs (MOVEM already is) until a v2 µcode-loop primitive (conditional `nextUpc` + a loop counter). This keeps v1 tractable and still covers the bulk of the cold tail (MOVEC/MOVES/STOP/RTD/cache-ctl/PACK-UNPK/the >3-µop BCD memory forms).

**MOVEM:** keep its bespoke FSM for now (it's an optimized 2/cycle data-dependent loop); fold it onto the engine only if/when the v2 loop primitive lands. Noted, not required.

## Complete classification of the remaining ISA

| Instruction / family | Mechanism | Rationale |
|---|---|---|
| **Indexed EA `(d8,An,Xn*scale)`** (modes 6, 7/3, BRIEF format) | **FAST** | Ubiquitous in compiled code; an EA-decode extension (index-reg read + scale + add) cracking into existing load/store µops. ≤3 µops. |
| **LEA, PEA** | **FAST** | LEA = compute-EA→An (1 µop); PEA = EA→predec-push (2 µops). Hot; depend on indexed EA. |
| **MOVE to/from CCR, to/from SR, MOVE USP, ANDI/ORI/EORI to SR** | **FAST** | Simple reg↔SR/CCR/USP moves (1–2 µops). Privileged but structurally trivial; `to CCR` immediates already exist. |
| **Full extension addressing** `([bd,An,Xn],od)` (memory-indirect/base-outer-disp) | **µCODE** | Cold; multi-access (load the indirect pointer, add outer disp). A µcode customer, NOT fast-path. |
| **MOVEC** (VBR/CACR/control regs), **MOVES** (alt address space) | **µCODE** | Cold, privileged, rare. Fixed short sequences. |
| **STOP, RESET, RTD** | **µCODE** | Cold/rare; STOP/RESET have side effects best sequenced. |
| **Cache control CINV/CPUSH** | **µCODE** | Cold, 040-specific cache management. |
| **ADDX/SBCD/ABCD `-(Ay),-(Ax)` memory forms** | **µCODE** | The deferred >3-µop forms (2 loads + op + store + 2 An updates) that exceed the fast crack budget — exactly what the engine unblocks. |
| **NBCD** (mem + reg) | FAST (reg) / µCODE (mem) | Reg form ≤3 µops; mem form via the engine. |
| **Bit-field (BF…), CAS/CAS2, CMP2/CHK2, PACK/UNPK, MOVEP, TRAPcc** | **µCODE** (BF/CAS need v2 loop) | 020+ exotica the 040 supports; cold. PACK/UNPK/MOVEP/CMP2/TRAPcc = straight-line (v1); BF/CAS = data-dependent (v2). |
| **F-line FPU dispatch** | **µCODE** (trap) | Until a real FPU: F-line → emulation-trap delivery (a fixed cold sequence). Real FPU datapath = separate major effort, out of scope. |
| **I-side MMU / ITLB, 8KB pages, TTR** | neither (MMU track) | Separate MMU slice, not a decode concern. |

## Parallel tracks + file ownership (manage the shared-decode-file conflict)

The two tracks DO touch shared decode files (`OperationDecoder`, `MicroOpAssembler`, `DecodeStage`) — the known ISA-slice conflict hazard. Partition to minimize, controller reconciles the rest (as with bit-ops/LINK):

- **Track A — Indexed addressing (FAST), owner files:** `decode/EaDecoder.scala` (modes 6 / 7-3 → new `INDEXED` class + brief-extension parse: index reg `Xn`, `.W/.L`, scale `*1/2/4/8`, `d8`), `decode/MicroOpAssembler.scala` (the indexed-EA crack: index-reg read + scaled add into the address µop — folds into the existing load/store/RMW cracks), `execute/LsEuPlugin.scala` AGU (the `base + d8 + Xn*scale` address add), `decode/DecodedUop.scala`+`rename/RenamedUop.scala` (index-reg src field). LEA/PEA as the Track-A follow-on. Touches `OperationDecoder` ONLY for LEA/PEA opcode classification (a distinct code region from Track B's cold-flagging).
- **Track B — µcode engine (INFRA), owner files:** NEW `decode/Microcode.scala` (the ROM + µop-descriptor format + operand-selector vocabulary), `decode/DecodeStage.scala` (the µcode sequencer + routing — additive to the existing MOVEM FSM region), `decode/OperationDecoder.scala` (the `microcoded`/`ucEntry` classification for the cold opcodes — a distinct region from Track A's EA/LEA work). First µcode customers to prove it: **MOVEC + the ADDX/SBCD/ABCD memory forms** (one fixed-sequence privileged op + one >3-µop crack).

**Conflict protocol:** both add to `OperationDecoder` (different opcode regions: Track A = LEA/PEA/EA-modes; Track B = MOVEC/MOVES/STOP/BCD-mem flagging) and `DecodeStage` (Track A's indexed crack rides the existing fast path with minimal DecodeStage change; Track B owns the sequencer). The controller serializes the merges and reconciles `OperationDecoder`/`DecodeStage` (precedent: the bit-ops + LINK/UNLK dual-fix reconciliation). Each track ends with its own lock-step + the ≥200 synth gate; controller does the combined gate.

## Sequencing within the tracks
- **Track A:** indexed brief-format EA → LEA/PEA (depends on the EA) → then the simple SR/USP/CCR moves (independent, can be a 3rd Track-A slice or fold in).
- **Track B:** the engine skeleton + ROM + routing (MOVEC + one BCD-mem form as the first two customers) → then MOVES/STOP/RTD/cache-ctl/PACK-UNPK/MOVEP/CMP2 ride it incrementally → v2 loop primitive (MOVEM-retrofit, bit-field, CAS) later.

## Non-goals (this architecture)
- A real FPU datapath (F-line emulation-trap only, for now).
- I-side MMU/ITLB (separate MMU track).
- The v2 µcode-loop primitive (data-dependent BF/CAS/MOVEM-retrofit) — explicitly deferred to keep v1 straight-line.

## Risks
- **Shared decode-file conflicts** between the two parallel tracks — mitigated by the ownership partition + controller reconciliation; the real risk is `OperationDecoder` line-4 (LEA/PEA/MOVE-SR are all line-4, same band as the cold MOVEC/STOP/cache-ctl) → the two tracks edit the SAME `is(0x4)` block. May need to SERIALIZE the line-4 pieces even if the rest is parallel. Flag for the planning step.
- **Privileged-instruction correctness** (SR/USP/MOVEC change supervisor state) — lock-step must cover privilege traps + the S-bit/USP banking already in the exception subsystem. No const-folding the MMU/SR.
- **µcode ROM operand-selector vocabulary** must be expressive enough for the first customers without over-engineering — start minimal (the selectors the first 2 customers need), grow per customer.
