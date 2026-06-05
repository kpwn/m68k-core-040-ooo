# Interrupt subsystem (simple protocol) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development / executing-plans. Checkbox steps, TDD. DELICATE exception/commit area — heavy lock-step ×2+ seeds. Final task = HONEST MMU-live synth gate (≥250; ~257 non-regress target).

**Goal:** Asynchronous, precise interrupt delivery: an external 3-bit IPL raises an interrupt taken BETWEEN instructions when `IPL > SR I-mask` (or NMI=7), delivered as a format-$0 frame via the existing commit-side exception FSM, with vector = autovector(24+level) or a vectored input. SIMPLE protocol — combinational `iackAvec`/`iackVector` inputs, NO faithful IACK bus-cycle FSM (postponed). Lock-stepped vs Musashi.

**Architecture:** Reuse the merged exception machinery (ROB → commit-side `ExceptionUnit` format-$0 FSM → RTE). New: an `iplIn` input + a recognition condition at a macro-instruction boundary that drives a 2nd async entry trigger (`interruptPending`) into the FSM; the entry SR-write adds `I-mask := level` (interrupt entries only); the stacked PC is the head instruction's PC (the not-yet-committed instruction, re-executed after RTE). Vector computed combinationally: `curVec = iackAvec ? (24+level) : iackVector`.

**Tech Stack:** SpinalHDL 1.14.1 / sbt `~/sbt/bin/sbt` / Verilator / Vivado `xcku5p-ffvb676-2-e` OOC 4ns. Reference: `rob/RobPlugin.scala` (`exceptionPending`:363, `faultRetire`:188, `exc` instantiation:393-404, per-entry PC + the first-µop/instruction-boundary marker, head `h0`), `exception/ExceptionUnit.scala` (entry FSM `IDLE.whenIsActive`:266, the entry SR-write `newSys`:358, `entryTrigger`/`entryVector`/`entryPc`:39), `exception/SystemState.scala` (`srSys` I-mask = bits 2:0, S=bit5, T=bits7:6), `top/FullCoreSynth.scala` (registered OOC inputs pattern :107-108 `mmuEnableIn`/`rootPtrIn`), the core's real input ports, `lockstep/ExecuteLockStepSpec.scala` + `oracle/Musashi` (`set_irq`/`set_interrupt_ack_response`/`cb_int_ack`/`run_until_sentinel_or_pc_with_irq_events`). Spec: `docs/superpowers/specs/2026-06-05-interrupts-design.md`.

**Branch:** `feat/interrupts` (create off master `88a3b5b`).

---

### Task 1: Diagnose recognition boundary + entry plumbing
**Files:** read-only; `RobPlugin.scala`, `ExceptionUnit.scala`, `SystemState.scala`, `FullCoreSynth.scala`, the cracker (`decode/MicroOpAssembler.scala`), the lock-step harness + `Musashi`.
Identify: (a) the **macro-instruction-boundary marker** — how to tell the ROB head is the FIRST µop of an instruction (cracker's first/last-µop flag, or an instruction-PC that changes per macro-instr); (b) the head's **instruction PC** field (the stacked PC for an interrupt — must be the macro-instruction PC, NOT a µop nextPc); (c) how `exceptionPending`→`exc.entryTrigger` is wired (interrupt becomes a 2nd source) and how to encode the **entry kind** (fault vs interrupt) so the SR-mask-update + vector-select apply only to interrupts; (d) the `srSys[2:0]` I-mask read; (e) how the lock-step harness drives the DUT + Musashi and where to inject IRQ events.
- [ ] Capture findings in the Task-1 commit message (empty commit ok). No functional code.

### Task 2: IPL input + IACK inputs (core IO + FullCoreSynth)
**Files:** Modify the core top / the plugin that owns external IO, `top/FullCoreSynth.scala`; Test a directed spec.
Add `iplIn` (UInt 3b), `iackAvec` (Bool), `iackVector` (UInt 8b) as core inputs, surfaced to the ROB/exception logic. In `FullCoreSynth` drive them from registered OOC inputs (mirror `mmuEnableIn`/`rootPtrIn` :107-108 — `RegNext(in ...) init 0`, so they're LIVE and not const-folded). Default idle (ipl=0) for tests that don't wire them.
- [ ] **Step 1:** add the IO + wiring; build; a directed test pokes iplIn and observes it reaches the recognition point. → PASS ×2.
- [ ] **Step 2: commit** `io: add iplIn + iackAvec/iackVector inputs (live OOC in FullCoreSynth)`.

### Task 3: Recognition + interruptPending (macro-instruction boundary)
**Files:** Modify `rob/RobPlugin.scala`; Test `src/test/scala/m68k040/rob/` directed spec.
Compute `interruptPending` = `(iplIn > srSys[2:0] || iplIn === 7) && head-is-first-µop-of-instruction && !faultedStore(h0) && excIdle`. Capture the interrupt level + the head instruction PC + `curVec = iackAvec ? (U(24)+iplIn) : iackVector` for the entry. The head must NOT commit when `interruptPending` (gate `retire0` like the faulted-head case). Single async entry (excIdle/exc.active re-suppresses).
- [ ] **Step 1: failing directed test** — iplIn=3 with mask=2 at a first-µop head → interruptPending, head not committed, level/curVec/instrPc captured; iplIn=2 with mask=2 → NOT pending; iplIn=7 (NMI) through mask=7 → pending; mid-cracked-instruction head → NOT pending. Run → FAIL.
- [ ] **Step 2: implement.**
- [ ] **Step 3:** test → PASS ×2.
- [ ] **Step 4: commit** `rob: interrupt recognition at macro-instruction boundary (ipl>mask||NMI)`.

### Task 4: Delivery via the exc-FSM (format-$0 + I-mask := level)
**Files:** Modify `exception/ExceptionUnit.scala` (+ `RobPlugin.scala` trigger wiring); Test `exception/` directed spec.
Feed `interruptPending` as a 2nd `entryTrigger` source with `entryKind=interrupt`, `entryVector=curVec`, `entryPc=head instr PC`. Reuse the format-$0 stack states (NOT $2/$7). **NEW:** the entry SR-write (`newSys` :358) for an interrupt entry must additionally set `srSys[2:0] := level` (raise the mask); for fault/trap entries it stays as-is (S=1/T=0 only). NMI sets mask=7. RTE restores the old SR (mask included) — unchanged.
- [ ] **Step 1: failing directed test** — an interrupt entry stacks {SR, head PC, curVec<<2} format-$0, vectors to VBR+curVec*4, and the committed SR after entry has I-mask=level + S=1 + T=0. Run → FAIL.
- [ ] **Step 2: implement.**
- [ ] **Step 3:** test → PASS ×2.
- [ ] **Step 4: commit** `exc: interrupt delivery (format-$0, SR I-mask:=level) via the entry FSM`.

### Task 5: Lock-step vs Musashi (autovector + vectored + masked + NMI + nested)
**Files:** Modify `lockstep/ExecuteLockStepSpec.scala` (+ the harness/`Musashi` binding for IRQ events + iackAvec/iackVector if needed).
Drive the DUT's `iplIn`/`iackAvec`/`iackVector` and Musashi's `set_irq`/`set_interrupt_ack_response` consistently; use `run_until_sentinel_or_pc_with_irq_events` so both take the IRQ at the same PC/boundary.
- [ ] **Step 1:** programs vs Musashi step-for-step (PC/SR incl. mask/A7 + $0 frame), ×2: (a) autovector IRQ → handler → RTE → resume; (b) vectored IRQ (`set_interrupt_ack_response`); (c) masked IRQ (ipl ≤ mask) NOT taken; (d) NMI (7) through mask=7; (e) nested — a higher-level IRQ preempting a running handler.
- [ ] **Step 2:** ALL existing programs (memory, MMU, format-$0/$2/$7, traps, ITLB, RTE) UNCHANGED ×2 (idle iplIn=0). Pre-existing ITLB seed flake → repro on baseline before attributing.
- [ ] **Step 3: commit** `interrupts: lock-step vs Musashi (autovector/vectored/masked/NMI/nested)`.

### Task 6: Full regression + HONEST SYNTH GATE
**Files:** none.
- [ ] **Step 1:** `make test-fast` + `make test-verilator` (`-Xmx12g`, alone) green — report totals.
- [ ] **Step 2: HONEST SYNTH GATE:** `~/sbt/bin/sbt "runMain m68k040.top.GenFullCoreSynthVerilog"` — 0 err, NO "UNASSIGNED REGISTER", `mmuEnableIn`/`rootPtrIn`/`iplIn`/`iackAvec`/`iackVector` live (the new inputs MUST be registered OOC inputs, not const-folded), ~16 RAMB. Then `vivado -mode batch -nojournal -log synth/vivado_FullCore.log -source synth/ooc_M68kFullCoreSynth.tcl`; confirm 0 err / 0 crit-warn, **WNS ≥ 0 (≥250; ~257 non-regress target)** — the IPL-compare + vector-select are commit-side, off the D-cache cone, should not regress. Report WNS+FMAX+critical path.
- [ ] **Step 3:** if <250, pipeline the offending boundary on this branch + re-synth (report before/after). Do NOT merge below 250.
- [ ] **Step 4: commit** `interrupts: full regression + honest synth >=250`.

---

## Self-Review
**Spec coverage:** IPL+IACK inputs (T2) §2; recognition at macro-instr boundary (T3) §2; format-$0 delivery + I-mask:=level (T4) §2; lock-step autovector/vectored/masked/NMI/nested (T5) §4; honest gate (T6) §4. Simple protocol — no IACK FSM (postponed). ✓
**Placeholder scan:** the macro-instruction-boundary marker + head-instr-PC field are T1 diagnoses (the implementer finds the actual cracker/ROB fields) — not placeholders, the design is explicit (§2/§5). No TBD.
**Type consistency:** `iplIn`(UInt 3b)/`iackAvec`(Bool)/`iackVector`(UInt 8b) new inputs; `interruptPending`(Bool) 2nd source into `entryTrigger`; `entryKind`(fault|interrupt) selects the SR-mask-update + vector path; `entryVector=curVec`(8b)/`entryPc`=head instr PC reuse the existing FSM ports; `srSys[2:0]` mask read+written. ✓
