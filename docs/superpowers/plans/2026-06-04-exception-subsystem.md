# Exception Subsystem (slice 1: format-$0 precise delivery) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development / executing-plans. Checkbox steps. The synth gate (final task) is MANDATORY (≥250 MHz).

**Goal:** Deliver **precise format-$0 exceptions** — illegal-instruction + privilege-violation — by reusing the ROB commit-time recovery: at the faulted instruction's retirement, squash younger work, switch to supervisor, stack a 68040 **format $0** frame to the supervisor stack, fetch the handler vector, redirect; and **RTE** to return. Lock-step the entry→handler→RTE sequence vs Musashi (SR/A7/PC), holding the full-core synth ≥250 MHz.

**Architecture:** New committed (NOT renamed) architectural state — SR system byte (S/I/T), VBR, and a banked A7 (USP vs SSP, selected by committed S). The ROB captures `{faulted, vectorNum, faultPc}` per entry (set at decode for illegal/privilege). At retire of a faulted head entry, reuse the registered `doFlush` (squash younger), and a **commit-side exception FSM** sets S=1, banks A7→SSP, stacks the format-$0 frame (SR, PC, format/vector word) to SSP by driving the D-cache store port, fetches `mem[VBR+vec*4]` via the D-cache load port, and redirects fetch to the handler. RTE reverses it. Exceptions are serializing (commit pauses during entry/exit), so the committed-state swaps are safe.

**Tech Stack:** SpinalHDL 1.14.1 / sbt `~/sbt/bin/sbt` (NOT on PATH) / Verilator / Vivado. Reference: `rob/RobPlugin.scala` (commit ports, `doFlush`/registered redirect, `mispredictStore`/`nextPcStore` pattern for per-entry fault fields, `RedirectService`), `decode/MicroOpAssembler.scala`/`OperationDecoder.scala` (`unimplemented` → illegal; add a privilege check), `cache/DcachePlugin.scala` (load/store ports the FSM drives), `mmu/DtlbPlugin.scala` (translate the stack/vector addresses — MMU usually off in these tests, identity), `lockstep/WhiteboxCapture.scala` + `ExecuteLockStepSpec.scala` + `oracle/OracleStep.scala` (has `sr`+`a[]`). Spec: `docs/superpowers/specs/2026-06-03-exception-subsystem-design.md`.

**Branch:** `feat/exceptions` (created; spec committed).

**SCOPE:** format $0 only; sources = illegal-instruction (`unimplemented`) + privilege violation (privileged op in user mode); single supervisor SP (no MSP/ISP split); RTE. **DEFER:** MMU page-fault delivery (format $7 — fast-follow), interrupts, trace, TRAP#n/TRAPV/CHK/DIV0, double-fault. **A reset state must be sane** (start in supervisor with a valid SP/PC, matching how the existing lock-step boots — keep current straight-line/memory tests green, i.e. they never fault).

---

### Task 1: Architectural system state (SR / VBR / USP-SSP, A7 banking)
**Files:** Create `src/main/scala/m68k040/exception/SystemState.scala` (or fold into ROB commit area); Modify `rename/RenameStage.scala` (A7 banking on committed S); Test `src/test/scala/m68k040/exception/SystemStateSpec.scala`.
Committed registers: `srSys` (S/I/T bits), `vbr`, `usp`, `ssp`. A7 (int arch reg 15) reads/writes select USP vs SSP by **committed S**. Since S changes only on exception/RTE (serializing — pipeline drained), implement A7 banking as a committed-mapping swap or a committed-S-muxed A7 source/sink (the simplest correct form given A7 is renamed reg 15: on the S transition, swap the committed value backing A7 between USP/SSP). Default reset: S=1 (supervisor), so existing tests (which never change S) are unaffected.
- [ ] failing test (poke S=0/1 → A7 reads USP/SSP; an S 0→1 transition banks A7; VBR/SR read/write) → FAIL → implement → PASS ×2 → commit `exception: committed SR/VBR/USP-SSP system state + A7 banking`.

### Task 2: ROB fault capture + decode fault sources
**Files:** Modify `rob/RobPlugin.scala` (per-entry `{faulted, vectorNum}`, set at alloc from the uop), `decode/*` (illegal → vector 4; privilege → vector 8); Test `src/test/scala/m68k040/rob/RobFaultSpec.scala`.
Each ROB entry carries `faulted`/`vectorNum` (from the renamed uop's `unimplemented`/privilege flags — thread a `faultVector` field through DecodedUop→RenamedUop, default none). Decode sets illegal (`unimplemented`) → vector 4; a privileged op (e.g. a stub privileged opcode, or RTE/MOVEC when S=0) → vector 8. Mirror the branch `mispredictStore` per-entry pattern.
- [ ] failing test (alloc a faulted uop → ROB entry carries faulted+vector; at retire it signals an exception-pending, like the branch retireAlone+mispredict path) → FAIL → implement → PASS ×2 → commit `rob: per-entry fault capture (faulted, vectorNum)`.

### Task 3: Commit-side exception FSM (stack format $0 + vector fetch + redirect)
**Files:** Create `src/main/scala/m68k040/exception/ExceptionUnit.scala`; Modify `rob/RobPlugin.scala` (trigger at faulted retire, reuse `doFlush`/`RedirectService`), `cache/DcachePlugin.scala` (the FSM drives store/load); Test `src/test/scala/m68k040/exception/ExceptionEntrySpec.scala`.
At retire of a `faulted` head entry: assert the registered redirect (squash younger, like a mispredict), then the FSM (commit paused/serializing): `srSysOld = srSys; srSys.S := 1`; bank A7→SSP; compute `frameBase = SSP - 8`; stack **format $0** (4 words: SR(old), PC(faultPc) hi/lo, `(0x0<<12)|vectorOffset` format/vector word) to `frameBase` via D-cache stores (translate via DtlbPlugin — identity when MMU off); `SSP := frameBase`; load `vec = mem[VBR + vectorNum*4]` via the D-cache load; redirect fetch to `vec` in supervisor. Confirm the EXACT 68040 format-$0 layout + word order against the m68040 PRM so it matches Musashi.
- [ ] failing test (drive a faulted entry to retire → frame appears at SSP-8 in `BehavioralMem` with correct SR/PC/format-vector word; SSP decremented; S set; fetch redirected to the vector target) → FAIL → implement → PASS ×2 → commit `exception: commit-side format-$0 entry FSM (stack + vector + redirect)`.

### Task 4: RTE
**Files:** Modify `decode/*` (decode RTE → a serializing exception-return uop), `exception/ExceptionUnit.scala` (RTE handler); Test `src/test/scala/m68k040/exception/RteSpec.scala`.
RTE (opcode 0x4E73, privileged): at its retire (serializing), the FSM pops the format-$0 frame from SSP — restore SR (incl. S → may bank A7 back to USP), restore PC, `SSP += 8` — and redirects fetch to the restored PC. Reuse the entry FSM's memory path (loads from SSP).
- [ ] failing test (preload a format-$0 frame at SSP, execute RTE → SR+PC restored, SSP incremented, A7 re-banked if S cleared, fetch redirected) → FAIL → implement → PASS ×2 → commit `exception: RTE (format-$0 frame restore + redirect)`.

### Task 5: Whitebox SR/A7 + lock-step + SYNTH GATE
**Files:** Modify `lockstep/WhiteboxCapture.scala` (surface SR system byte + A7 per commit), `ExecuteLockStepSpec.scala`; Test additions there.
- [ ] Step 1: Extend the whitebox/commit observation to carry the committed SR (full 16-bit) + A7, so the lock-step can compare them against `OracleStep.sr`/`a(7)` through an exception. Existing programs (no exception) must still compare equal (SR system byte = supervisor reset value, matching Musashi's boot SR).
- [ ] Step 2: Lock-step program: a handler in memory + a vector table at VBR, an illegal instruction that vectors to the handler (handler does a couple of moves) then RTE back to continue. Assert the commit stream (PC/SR/A7/regs) matches Musashi step-for-step across entry→handler→RTE. ×2 deterministic. (A privilege-violation program likewise if a privileged opcode is wired.)
- [ ] Step 3: `ExecuteLockStepSpec` existing programs UNCHANGED ×2 (no fault) + the new exception program(s) ×2.
- [ ] Step 4: `make test-fast` + `test-verilator` green; report totals (baseline 58 / 155).
- [ ] Step 5: **SYNTH GATE (mandatory):** `~/sbt/bin/sbt "runMain m68k040.top.GenFullCoreSynthVerilog"` then `vivado -mode batch -nojournal -log synth/vivado_FullCore.log -source synth/ooc_M68kFullCoreSynth.tcl 2>&1 | grep -iE "RESULT|Unsupported|multi-driven net on pin|CRITICAL WARNING|Synth Design complete"`. 0 err / 0 crit-warn, **WNS ≥ 0 (≥250 MHz)** (baseline 256.34); report WNS+FMAX+critical path. The exception FSM is commit-side + serializing (latency, not a comb cloud); the per-entry fault fields are small. If a wide net (e.g. the committed-S A7 mux fanout) regresses FMax, register/narrow it and re-synth (report before/after).
- [ ] Step 6: commit `exception: format-$0 precise delivery + RTE; lock-step vs Musashi; synth >=250MHz`.

---

## Self-Review
**Spec coverage:** committed SR/VBR/USP-SSP + A7 banking (T1) §3.1; ROB fault capture + sources (T2) §3.3; commit-side format-$0 entry FSM reusing doFlush (T3) §3/§3.2; RTE (T4) §2; whitebox SR/A7 + lock-step + synth (T5) §4. Format $7 / MMU-fault delivery, interrupts, trace, other traps explicitly DEFERRED. ✓
**Placeholder scan:** the exact format-$0 layout is "confirm against the m68040 PRM" (concrete source, the agent reads the real format so it matches Musashi) — not a TBD; everything else is concrete contracts + tests + the Musashi lock-step gate.
**Type consistency:** `faulted`/`vectorNum` (T2) consumed by the entry FSM (T3); SR/VBR/USP/SSP (T1) read/written by the FSM (T3) + RTE (T4) + surfaced by the whitebox (T5); the entry FSM's frame layout (T3) is the inverse of RTE's restore (T4); `doFlush`/`RedirectService` (existing) reused for the squash+redirect. Reset S=1 keeps all non-faulting tests unchanged.
