# Dcache store-RMW pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development / executing-plans. Checkbox steps, TDD. Final task = HONEST MMU-live synth gate (≥250, target ≥258; mmuEnable live, both TLBs+walkers inferred).

**Goal:** Pipeline the D-cache store read-modify-write into two cycles so the binding `exc-FSM→DcachePlugin.dataMem` store path (WNS +0.009 / 250.6 MHz, route 71.7%) leaves the critical list and FMax recovers to ≥258.

**Architecture:** Today the store RMW (`DcachePlugin.scala:161-178`) reads the old 128-bit line async, byte-merges, and writes dataMem all in one cycle, fronted by the exc-FSM's far-placed store-port select. Split it: store-S0 latches the store payload + the old-line read into registers near the cache; store-S1 merges + writes dataMem. Latency-agnostic lock-step makes the +1 cycle free. Preserve single-outstanding + write-through-ACK ordering + refill-write priority.

**Tech Stack:** SpinalHDL 1.14.1 / sbt `~/sbt/bin/sbt` / Verilator / Vivado `xcku5p-ffvb676-2-e` OOC 4ns. Reference: `cache/DcachePlugin.scala` (store RMW :133-202, dataMem write port :63-74), `exception/ExceptionUnit.scala` (the dcStore producer :145-206, already RegNext'd), `top/BackendWiringPlugin.scala` (storePort mux: exc-store vs SQ-drain — check if its select is the exc_fsm front of the path), `lockstep/ExecuteLockStepSpec.scala`, `ls/StoreQueue.scala` (drain/forward/ACK). Spec: `docs/superpowers/specs/2026-06-05-dcache-store-rmw-pipeline-design.md`.

**Branch:** `feat/dcache-store-pipeline` (create off master).

---

### Task 1: Diagnose + confirm the exact binding path
**Files:** read-only; `synth/M68kFullCoreSynth_timing.rpt`, `DcachePlugin.scala`, `BackendWiringPlugin.scala`.
Confirm the worst path Source/Dest (`exc_fsm_stateReg[3]` → `dataMem_0 port1`), and trace which signals (store-port select mux? stSet/stOff adder? readAsync old line? 16-lane merge?) are the 14 levels. Identify whether the exc-FSM store-port *select* in BackendWiring is on the path (register it too if so).
- [ ] Write the diagnosis as a comment block in the plan branch's commit message / a scratch note; identify the exact register boundary to insert. No code yet.

### Task 2: Pipeline the store RMW (store-S0 / store-S1)
**Files:** Modify `cache/DcachePlugin.scala`; Test `src/test/scala/m68k040/ls/` (a directed Dcache store-RMW spec — extend the existing LS/Dcache test, or add `DcacheStorePipelineSpec.scala`).
Introduce a store-S0 stage: on `storePort.valid`, latch `{paddr, data, size, strb, useStrb, lineData}` + the hit-way + the old-line read into registers. Move the byte-merge + `dataMem.write` to store-S1 from the registered old line + registered payload. Keep `stMergeReg/stStrbReg/stAddrReg → AXI aw/w` firing after the merge; keep refill dataMem-write priority over store-S1-write; keep single-outstanding (the producer never presents a 2nd store mid-drain).
- [ ] **Step 1: failing directed test** — a store hit to a known line, then read it back (via the load port) → expect the merged bytes (the test asserts the line updates, now one cycle later). Run → FAIL (or shows old timing assumption) .
- [ ] **Step 2: implement** the S0/S1 split in `DcachePlugin.scala`.
- [ ] **Step 3:** run the directed test → PASS ×2.
- [ ] **Step 4:** if BackendWiring's storePort select is on the path (Task 1), register the muxed storePort one stage earlier; re-run.
- [ ] **Step 5: commit** `dcache: pipeline store RMW into S0(read)/S1(merge+write)`.

### Task 3: Store→load + SQ-forward correctness
**Files:** Test `lockstep/ExecuteLockStepSpec.scala` (or the LS inject spec).
- [ ] **Step 1:** a lock-step program with store-then-immediate-load to the same line + a store-then-load to an overlapping sub-word (SQ forward path) → vs Musashi, PC/SR/regs step-for-step. Confirm the +1-cycle cache write is covered by SQ forwarding + the drain-resident-until-ACK window. PASS ×2.
- [ ] **Step 2: commit** `dcache: store-pipeline store->load + SQ-forward lock-step`.

### Task 4: Full lock-step regression + HONEST SYNTH GATE
**Files:** none (run suites).
- [ ] **Step 1:** `ExecuteLockStepSpec` ALL existing programs (memory, MMU, format-$0/$2/$7, traps, ITLB) UNCHANGED ×2.
- [ ] **Step 2:** `make test-fast` + `make test-verilator` (`-Xmx12g`) green — report totals.
- [ ] **Step 3: HONEST SYNTH GATE:** `~/sbt/bin/sbt "runMain m68k040.top.GenFullCoreSynthVerilog"` — confirm gen 0 errors, NO "UNASSIGNED REGISTER", `mmuEnableIn`/`rootPtrIn` live, both ITLB+DTLB+walkers inferred (16 RAMB). Then `vivado -mode batch -nojournal -log synth/vivado_FullCore.log -source synth/ooc_M68kFullCoreSynth.tcl` ; `grep -iE "RESULT|Unsupported|multi-driven|CRITICAL WARNING|complete" synth/vivado_FullCore.log`. Confirm 0 err / 0 crit-warn, **WNS ≥ 0 (FMAX ≥ 258 target; ≥250 hard floor)**, and that `exc_fsm_stateReg → dataMem` is OFF the worst-path list (read `synth/M68kFullCoreSynth_timing.rpt`). Report WNS+FMAX+the new critical Source→Dest+logic levels+route% + the delta from 250.6.
- [ ] **Step 4:** if WNS<0 or still <258, peel the next layer (e.g. register the store-port select, or pipeline the new worst path) on this branch, re-synth, report before/after. Do NOT merge below 250.
- [ ] **Step 5: commit** `dcache: store-RMW pipeline; lock-step; honest synth >=258`.

---

## Self-Review
**Spec coverage:** store-S0/S1 pipeline (T2) §2/§3; single-outstanding + write-through + refill-priority preserved (T2) §2; store→load/SQ-forward (T3) §4; exc-FSM select registration (T1/T2) §5; honest gate (T4) §4. ✓
**Placeholder scan:** the old-line read form (readSync vs readAsync-into-reg) is a T2 implementation choice (§5 open item) — the implementer picks by area/inference + reports. No TBD in the steps.
**Type consistency:** `storePort.payload.{paddr,data,size,strb,useStrb,lineData}` (existing DStoreCmd fields) latched into S0 regs; `wrEn/wrSet/wrData` (existing dataMem write port) driven from S1. `stMergeReg/stStrbReg/stAddrReg` (existing) unchanged producers, now fed from S1. ✓
