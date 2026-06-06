# ALU immediates + EOR (register/data dest) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development / executing-plans. Checkbox steps, TDD. Final gate = HONEST **POST-ROUTE** (`impl_FullCore.tcl`), non-regress vs master ~240 (OOC-synth is ~15-25 MHz optimistic — proxy only).

**Goal:** Line-0 immediates (ADDI/SUBI/ANDI/ORI/EORI/CMPI) to data regs, ANDI/ORI/EORI to CCR, and EOR (reg dest). Lock-stepped vs Musashi.

**Architecture:** Reuse the existing ALU datapath (ADD/SUB/AND/OR/CMP) + add EOR; new line-0 immediate decode (op/size/imm-length) + the to-CCR write. Data-register destinations only (memory-dest RMW = a later slice). Not privileged (no SR forms here).

**Tech Stack:** SpinalHDL / sbt `~/sbt/bin/sbt` / Verilator / Vivado. Reference: `decode/OperationDecoder.scala` (line 8/9/B/C/D ALU; add line-0 + EOR), `decode/MicroOpAssembler.scala`, `decode/EaDecoder.scala` (IMM klass), `execute/AluDatapath.scala`+`AluEuPlugin.scala` (add EOR/xor), `frontend/PredecodeWord.scala` (line-0 length = opword + imm word(s)), the committed-CCR path in `rob/RobPlugin.scala`, `lockstep/ExecuteLockStepSpec.scala`, `oracle/Musashi.scala`. Spec: `docs/superpowers/specs/2026-06-06-alu-immediates-eor-design.md`. Post-route gate: `synth/impl_FullCore.tcl`.

**Branch:** `feat/alu-immediates` (off master).

---

### Task 1: Diagnose — imm-source today, EOR datapath, predecode length, CCR write
**Files:** read-only. Confirm: does `<ea>=#imm` already work for line-8/9/B/C/D? Does `AluDatapath` have an xor (EOR) or need one? Does `PredecodeWord` length-cover line 0 (else nextPc mis-frames)? Does the committed-CCR fold accept a direct CCR write (for to-CCR)?
- [ ] Capture decisions in the Task-1 commit (empty ok).

### Task 2: EOR (reg dest) + predecode length for line 0
**Files:** `decode/OperationDecoder.scala`+`MicroOpAssembler.scala`, `execute/AluDatapath.scala` (xor if needed), `frontend/PredecodeWord.scala` (+`PredecodeRef.scala`), Test `execute/EorSpec.scala` + PredecodeWordSpec.
Add EOR (line B opmode 4/5/6, mode 0 dest): `Dm ^= Dn`, NZ, V=C=0. Add line-0 predecode length (.B/.W = +1 word, .L = +2).
- [ ] failing test (EOR Dn,Dm flags; line-0 predecode length) → FAIL → implement → PASS ×2 → commit `alu: EOR (reg dest) + line-0 predecode length`.

### Task 3: Line-0 immediates ADDI/SUBI/ANDI/ORI/EORI/CMPI → Dn
**Files:** `decode/OperationDecoder.scala`+`MicroOpAssembler.scala`, Test `decode/ImmDecodeSpec.scala` + lock-step.
Decode `0000 ooo0 ss mmmrrr`+imm (ooo: 0=ORI,1=ANDI,2=SUBI,3=ADDI,5=EORI,6=CMPI; mode0 dest) → the ALU op with srcB=#imm (sign/zero-extended per size). Flags per op; CMPI writes no reg.
- [ ] failing test (each immediate op .B/.W/.L #imm,Dn + flags) → FAIL → implement → PASS ×2 → commit `alu: line-0 immediates ADDI/SUBI/ANDI/ORI/EORI/CMPI -> Dn`.

### Task 4: ANDI/ORI/EORI to CCR
**Files:** `decode/*`, the committed-CCR path, Test `execute/ImmCcrSpec.scala`.
Decode `0000 ooo0 00 111100`+imm.B (ooo 0/1/5) → CCR := CCR op imm[4:0]. NOT privileged (CCR only).
- [ ] failing test (ANDI/ORI/EORI #imm,CCR → NZVCX) → FAIL → implement → PASS ×2 → commit `alu: ANDI/ORI/EORI to CCR`.

### Task 5: Lock-step + POST-ROUTE gate
**Files:** `lockstep/ExecuteLockStepSpec.scala`, run suites.
- [ ] Step 1: lock-step vs Musashi ×2 — all immediate ops #imm,Dn (.B/.W/.L, flag-affecting operands), ANDI/ORI/EORI #imm,CCR, EOR Dn,Dm — value + NZVCX step-for-step. ALL existing UNCHANGED (ITLB flake → baseline-repro first).
- [ ] Step 2: `make test-fast` + targeted verilator `-z` subsets — report totals.
- [ ] Step 3: quick OOC sanity (proxy).
- [ ] Step 4: **POST-ROUTE GATE** — gen + `vivado -mode batch -nojournal -source synth/impl_FullCore.tcl`; report POSTROUTE WNS/FMAX vs master (~240, run same flow on master for apples-to-apples). Non-regress. If regressed, fix the offending cone.
- [ ] Step 5: commit `alu-immediates: lock-step + POST-ROUTE gate`.

---

## Self-Review
**Spec coverage:** EOR + line-0 predecode (T2); line-0 immediates (T3); to-CCR (T4); lock-step + post-route (T5). ✓
**Placeholder scan:** imm-source-today / EOR-datapath / predecode-length / CCR-write are explicit T1 diagnoses (spec §5). No TBD.
**Type consistency:** reuses ALU ops (ADD/SUB/AND/OR/CMP) + new EOR; srcB=#imm via the existing imm path; flags via the committed-CCR path; data-reg dest only. Predecode length per size (line-0 imm words). ✓
