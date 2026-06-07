# Shifts & rotates (line E register forms) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development / executing-plans. Checkbox steps, TDD. Gate = LOCK-STEP (correctness) + OOC-synth sanity proxy. Do NOT gate on post-route (it's non-deterministic/unreliable right now — see the spec); shifts are FMax-neutral.

**Goal:** ASL/ASR/LSL/LSR/ROL/ROR/ROXL/ROXR, register destination, imm + register count, .B/.W/.L. Lock-stepped vs Musashi.

**Architecture:** New combinational barrel shifter in/near `AluDatapath`, driven by a line-E shift µop {op, dir, size, count}. Reuses the ALU EU + the X/NZVC flag paths. Flags (C=last-bit-out, X=C except ROL/ROR, V=ASL-MSB-change, count-0 specials) match Musashi exactly.

**Tech Stack:** SpinalHDL / sbt `~/sbt/bin/sbt` / Verilator / Vivado(OOC sanity only). Reference: `decode/OperationDecoder.scala`+`MicroOpAssembler.scala` (add line E), `execute/AluDatapath.scala`+`AluEuPlugin.scala` (the shifter + flags; X read/write already exist for ADD/SUB), `frontend/PredecodeWord.scala` (line-E length = 1 word, no extension — confirm), `oracle/Musashi.scala`, `lockstep/ExecuteLockStepSpec.scala`. Spec: `docs/superpowers/specs/2026-06-07-shifts-rotates-design.md`.

**Branch:** `feat/shifts-rotates` (off master).

---

### Task 1: Diagnose — ALU shifter, 2nd-src count read, flag paths, predecode
**Files:** read-only. Confirm: `AluDatapath` has no shifter (add one); the ALU EU can read a 2nd data-reg source for the register shift count (`Dc`); the X read/write ports exist (ROXL/ROXR, ASL/etc. X=C); line-E predecode length (1 word). Note Musashi's exact C/X/V/count-0 rules per op.
- [ ] Capture decisions in the Task-1 commit (empty ok).

### Task 2: Barrel shifter datapath + a Scala-ref spec
**Files:** `execute/AluDatapath.scala` (+ a `Shifter` helper); Test `execute/ShifterSpec.scala`.
Combinational barrel shifter: inputs {data, count[5:0], op(8), dir, size}, outputs {result, C, X-out, V}. Validate vs a Scala reference (mirroring Musashi) over all 8 ops × dir × size × counts {0,1,size-1,size,>size,63}.
- [ ] failing test (ref sweep) → FAIL → implement → PASS ×2 → commit `alu: barrel shifter datapath (8 shift/rotate ops + flags)`.

### Task 3: Decode line-E + integrate (immediate count)
**Files:** `decode/OperationDecoder.scala`+`MicroOpAssembler.scala`, `frontend/PredecodeWord.scala`(+Ref), `execute/AluEuPlugin.scala`, Test `decode/ShiftDecodeSpec.scala` + lock-step.
Decode `1110 ccc d ss i tt rrr` with `i=0` (immediate count 1-8, where ccc=0→8) → shift µop. Wire the shifter into the ALU EU; writeback Dr + NZVCX. Predecode length.
- [ ] failing test (each op .B/.W/.L imm count + flags) → FAIL → implement → PASS ×2 → commit `shifts: line-E decode + immediate count + ALU integrate`.

### Task 4: Register shift count (i=1)
**Files:** `decode/*` (2nd src = Dc), `execute/AluEuPlugin.scala`, Test extend.
`i=1` → count = `Dc[5:0] mod 64` (a 2nd data-reg read). Handle count 0 (flag specials) + count ≥ size.
- [ ] failing test (register count incl. 0, ≥size, mod-64) → FAIL → implement → PASS ×2 → commit `shifts: register shift count (Dc mod 64)`.

### Task 5: Lock-step + OOC sanity
**Files:** `lockstep/ExecuteLockStepSpec.scala`, run suites.
- [ ] Step 1: lock-step vs Musashi ×2 — all 8 ops, imm + reg count, .B/.W/.L, flag-edge operands (C last-out, ASL-V, ROX-through-X, count 0/≥size) — result + NZVCX step-for-step. ALL existing UNCHANGED (ITLB flake → baseline-repro first).
- [ ] Step 2: `make test-fast` + targeted verilator `-z` subsets — report totals.
- [ ] Step 3: OOC-synth SANITY only (`GenFullCoreSynthVerilog` + `ooc_M68kFullCoreSynth.tcl`): 0 err, no UNASSIGNED REGISTER, report LUT (the shifter adds some). Do NOT run/gate on post-route (non-deterministic — see spec).
- [ ] Step 4: commit `shifts-rotates: lock-step + OOC sanity`.

---

## Self-Review
**Spec coverage:** shifter datapath (T2); line-E decode + imm count (T3); register count (T4); lock-step + sanity (T5). ✓
**Placeholder scan:** shifter structure + flag rules are explicit T1/T2 (spec §5); validated vs a Scala ref. No TBD.
**Type consistency:** new shift µop {op,dir,size,count} into the ALU EU; barrel shifter → result + C/X/V; NZVCX via the existing flag paths (X read/write reused); register count = 2nd Dc read. ✓
