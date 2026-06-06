# Divider (DIVU/DIVS full 040) + CHK + DIV0 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development / executing-plans. Checkbox steps, TDD. Final task = HONEST MMU-live synth gate (≥250; ~257 non-regress target). Big slice — commit per task.

**Goal:** Integer division (DIVU/DIVS .W, .L 32÷32, .L 64÷32) on a new multi-cycle divider EU, plus CHK (vector 6) and DIV0 (vector 5) traps, lock-stepped vs Musashi.

**Architecture:** A radix-2 non-restoring multi-cycle `DivEuPlugin` (single-outstanding, busy-gated, dynamic-completion wakeup like `LsEuPlugin`); unsigned core + DIVS sign-normalization/fix-up; overflow → V (no write); divisor==0 → DIV0 fault. CHK is a bound-compare execute-time trap independent of the divider. DIV0/CHK reuse a generalized execute-time fault completion (`trapvFault` → `euFault{robId,vector}`) into the ROB → format-$0 FSM. The 64÷32 form reads a GPR pair (Dr:Dq dividend) + writes a pair — handle in rename/dispatch or by cracking.

**Tech Stack:** SpinalHDL 1.14.1 / sbt `~/sbt/bin/sbt` / Verilator / Vivado `xcku5p-ffvb676-2-e` OOC 4ns. Reference: `execute/BranchEuPlugin.scala` (`trapvFault` pattern :17-106, condition eval), `execute/LsEuPlugin.scala` (multi-cycle busy + `completion`/`wakeup` dynamic-completion), `execute/AluEuPlugin.scala`+`AluDatapath.scala`, `execute/iq/` (issue ports), `execute/regfile/` (PRF write ports), `decode/OperationDecoder.scala`+`EaDecoder.scala`+`MicroOpAssembler.scala` (decode/crack), `rename/` (RenamedUop — 2-dest?), `rob/RobPlugin.scala` (fault completions → faulted/vector), `exception/ExceptionUnit.scala` (format-$0 — unchanged), `top/FullCoreSynth.scala` (:57 trapvFault wiring), `lockstep/ExecuteLockStepSpec.scala`, `oracle/Musashi.scala`. Spec: `docs/superpowers/specs/2026-06-06-divider-chk-div0-design.md`.

**Branch:** `feat/divider` (already created; spec+plan committed).

---

### Task 1: Diagnose — fault-completion generalization, 2-dest µop support, EU/issue plumbing
**Files:** read-only.
Decide: (a) generalize `trapvFault` → `euFault{robId, vector}` vs add ports; (b) does rename/dispatch/PRF support a 2-source/2-dest µop (for 64÷32 Dr:Dq), or must it crack into divide + remainder-move? (c) which issue port the DivEU uses + how busy/single-outstanding gates issue; (d) where CHK executes (ALU/branch path) cheapest.
- [ ] Capture decisions in the Task-1 commit message (empty commit ok).

### Task 2: CHK (vector 6) — validate the generalized fault completion FIRST
**Files:** `decode/*` (decode CHK.W/CHK.L), the chosen EU, `rob/RobPlugin.scala` (generalized fault completion), Test `exception/ChkSpec.scala`.
Generalize the execute-time fault completion to carry a vector. Decode CHK → a bound-compare µop (reads Dn + ea); execute: `Dn<0 → N=1,trap`; `Dn>bound → N=0,trap`; else no-op. Fault → `euFault{robId, vec6}`.
- [ ] failing test (CHK Dn in-bounds → no trap; Dn<0 → fault vec6, N=1; Dn>bound → fault vec6, N=0) → FAIL → implement → PASS ×2 → commit `chk: bound-check trap (vector 6) via generalized euFault`.

### Task 3: Divider datapath (unsigned radix-2) — standalone
**Files:** `execute/DivEuPlugin.scala` (new) or a `DivDatapath.scala`; Test `execute/DivDatapathSpec.scala`.
Radix-2 non-restoring unsigned divider: inputs {dividend (up to 64b), divisor (32b), width}, multi-cycle, outputs {quotient, remainder, done, overflow, divByZero}. Validate vs a Scala reference over random + edge operands.
- [ ] failing test (random unsigned divides incl. divisor 1, max, overflow, divisor 0) vs Scala ref → FAIL → implement → PASS ×2 → commit `div: unsigned radix-2 multi-cycle divider datapath`.

### Task 4: DIVS sign handling + overflow detection
**Files:** `DivEuPlugin`/datapath; Test extend `DivDatapathSpec.scala`.
Sign-normalize operands, divide magnitudes, fix-up quotient/remainder signs (68k rule: remainder takes the dividend's sign); detect signed overflow (incl. INT_MIN/-1).
- [ ] failing test (random signed incl. negatives, INT_MIN/-1, overflow) vs Scala ref → FAIL → implement → PASS ×2 → commit `div: signed DIVS sign-normalize + fix-up + overflow`.

### Task 5: DIVU.W/DIVS.W — decode + integrate + writeback + flags
**Files:** `decode/*`, `DivEuPlugin` integration (issue/busy/wakeup/writeback), `rob`, Test `execute/DivWSpec.scala` + a lock-step program.
Decode DIVx.W ea,Dn → div µop (32÷16). Integrate the DivEU: issue, busy single-outstanding, writeback {quotient[15:0], remainder[31:16]} to Dn, NZVC (V on overflow → no write), dynamic-completion wakeup. DIV0 → euFault{vec5}.
- [ ] failing test (DIVU.W + DIVS.W normal, overflow, DIV0) → FAIL → implement → PASS ×2 → commit `div: DIVU.W/DIVS.W decode+EU integrate+writeback+flags+DIV0`.

### Task 6: DIVU.L/DIVS.L 32÷32
**Files:** `decode/*`, `DivEuPlugin`, Test `execute/DivL32Spec.scala`.
Decode the .L 32÷32 form (ea,Dq → quotient in Dq; ea,Dr:Dq → remainder Dr + quotient Dq). Integrate writeback (1 or 2 dests).
- [ ] failing test (DIVU.L/DIVS.L 32÷32, quotient-only + remainder:quotient, overflow, DIV0) → FAIL → implement → PASS ×2 → commit `div: DIVU.L/DIVS.L 32÷32`.

### Task 7: DIVU.L/DIVS.L 64÷32 (Dr:Dq pair dividend)
**Files:** `decode/*` (the extension-word Dr:Dq encoding), `rename/`/`dispatch` (2-src/2-dest or crack), `DivEuPlugin`, Test `execute/DivL64Spec.scala`.
Decode the 64÷32 form: 64-bit dividend Dr:Dq ÷ 32-bit divisor → quotient Dq + remainder Dr. Handle the 2-source-pair read + 2-dest write (per Task-1's decision — direct or cracked). Overflow if quotient > 32 bits → V, no write.
- [ ] failing test (DIVU.L/DIVS.L 64÷32 normal, overflow, DIV0; pair read/write correct) → FAIL → implement → PASS ×2 → commit `div: DIVU.L/DIVS.L 64÷32 (Dr:Dq pair dividend)`.

### Task 8: Lock-step vs Musashi + HONEST SYNTH GATE
**Files:** `lockstep/ExecuteLockStepSpec.scala`, run suites.
- [ ] Step 1: lock-step vs Musashi ×2 — DIVU/DIVS .W/.L32/.L64 (normal+overflow), DIV0→handler→RTE, CHK in-bounds + both out-of-bounds→handler→RTE; quotient+remainder+N/Z/V + PC/SR/A7 step-for-step. ALL existing programs UNCHANGED (the pre-existing ITLB seed flake → repro on baseline before attributing).
- [ ] Step 2: `make test-fast` + `make test-verilator` (`-Xmx12g`, alone) — report totals.
- [ ] Step 3: HONEST SYNTH GATE — gen (mmuEnable/ipl inputs live, no UNASSIGNED REGISTER, ~16 RAMB), `vivado -mode batch -nojournal -source synth/ooc_M68kFullCoreSynth.tcl`; confirm 0 err/crit, **WNS ≥ 0 (≥250; ~257 non-regress)** — watch the 64-bit pair read/write didn't deepen the PRF/rename/IQ cone. Report WNS+FMAX+critical path. If <250, pipeline the offender (before/after) — don't merge below 250.
- [ ] Step 4: commit `divider+chk+div0: lock-step + honest synth >=250`.

---

## Self-Review
**Spec coverage:** CHK (T2) §2; unsigned+signed divider (T3/T4) §2; DIVU/DIVS .W (T5), .L32 (T6), .L64 pair (T7) §2; DIV0+overflow folded into T5-T7; generalized fault completion (T1/T2) §5; lock-step+gate (T8) §4. ✓
**Placeholder scan:** the fault-completion generalization, 2-dest-vs-crack, EU-port, and CHK-placement are explicit T1 decisions (§5) — the implementer picks + records; not placeholders. No TBD.
**Type consistency:** `euFault{robId,vector}` (generalized from `trapvFault{robId}`) consumed by the ROB (`faultedStore`/`faultVecStore`); DivEU `completion`/`wakeup` Flows mirror LsEu; writeback via PRF write port(s) (1 for single-dest, 2 for Dr:Dq pair). NZVC via the existing CCR completion path; V=overflow. ✓
