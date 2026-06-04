# MMU Page-Fault Delivery (format-$7) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development / executing-plans. Checkbox steps. The synth gate (final task) is MANDATORY and must be measured HONESTLY (`mmuEnable`/`rootPtr` stay live OOC inputs — never const-folded; check the gen log for "UNASSIGNED REGISTER").

**Goal:** Deliver MMU access faults as precise 68040 access-fault exceptions (vector 2, **format-$7** frame), run a handler, and RTE — making demand paging real — holding the MMU-live synth ≥250 MHz.

**Architecture:** Reuse the exception subsystem's commit-side FSM (it already squashes younger, stacks a frame via the D-cache, fetches the vector, redirects, and RTEs). Add: (1) LS-EU flags a DTLB-faulting access into its ROB entry with vector 2 + the fault address + SSW attrs; (2) the FSM selects the **format-$7** layout (vs $0) by vector and stacks the wider frame (SR/PC/vector/SSW/faultAddr + MAME-040 internal-field values); (3) RTE handles the $7 format. Match the MAME m68040 model byte-for-byte.

**Tech Stack:** SpinalHDL 1.14.1 / sbt `~/sbt/bin/sbt` / Verilator / Vivado. Reference: `exception/ExceptionUnit.scala` + `rob/RobPlugin.scala` (slice-1 fault capture `{faulted,vectorNum,faultPc}` + `faultRetire` + the format-$0 stacker), `mmu/DtlbPlugin.scala`/`execute/LsEuPlugin.scala` (the `rsp.fault` the LS access sees), `thirdparty/mame/src/devices/cpu/m68000/m68000-sdp.cpp` (the MAME 040 access-fault frame builder + SSW: `m_base_ssw`, SSW_R/SSW_N/SSW_PROGRAM, the format-$7 stack), `lockstep/ExecuteLockStepSpec.scala`+`WhiteboxCapture.scala`+`oracle/Musashi.scala`. Spec: `docs/superpowers/specs/2026-06-04-mmu-fault-delivery-design.md`.

**Branch:** `feat/mmu-fault-delivery` (created; spec committed).

---

### Task 1: Capture the MMU access fault (vector 2 + faultAddr + SSW attrs)
**Files:** Modify `execute/LsEuPlugin.scala` (flag a DTLB-faulting access), `rename/RenamedUop.scala`/`rob/RobPlugin.scala` (per-entry `faultAddr` + SSW attrs), `mmu/DtlbPlugin.scala` (surface fault attrs if needed); Test `src/test/scala/m68k040/rob/AccessFaultCaptureSpec.scala`.
When an LS access gets `xlate.rsp.fault`, mark its ROB entry `faulted` with **vector 2** + `faultAddr = the faulting VA` + `{rw, size, supervisor}` for the SSW. Extend the slice-1 per-entry fault Vecs with `faultAddrStore` + `sswAttrStore` (RegInit, reset per-alloc like `faultedStore`).
- [ ] failing test (drive an LS µop whose translate faults → ROB entry faulted, vector 2, faultAddr captured) → FAIL → implement → PASS ×2 → commit `rob/ls: capture MMU access fault (vector 2 + faultAddr + SSW attrs)`.

### Task 2: Format-$7 frame in the exception FSM (vector-selected)
**Files:** Modify `exception/ExceptionUnit.scala`; Test `src/test/scala/m68k040/exception/Format7Spec.scala`.
The FSM (slice-1) stacks format-$0 for illegal/privilege. Add a **format-$7** path selected when the entry's vector is the access-fault (2): stack the 68040 $7 frame to SSP — `SR, PC, (0x7<<12)|(2*4), SSW, faultAddr, <internal fields>` — matching the MAME 040 builder. Decode `m68000-sdp.cpp` for the exact word order + SSW bits + the internal-field values for a no-pending-writeback data-access fault; build SSW from the captured `{rw,size,super}` + the model's base bits. (Frame size/word-order is the crux — match it.)
- [ ] failing test (drive a faulted (vec 2) entry to retire → a $7 frame appears at SSP with correct SR/PC/format-vector/SSW/faultAddr; SSP decremented by the $7 frame size; S set; redirect to mem[VBR+8]) → FAIL → implement → PASS ×2 → commit `exception: format-$7 access-fault frame (MAME-040-matched)`.

### Task 3: RTE from format-$7
**Files:** Modify `exception/ExceptionUnit.scala`; Test `src/test/scala/m68k040/exception/RteFormat7Spec.scala`.
RTE inspects the stacked format word; for $7 restore SR/PC/A7 (and resume/re-run per the MAME 040 model) and pop the $7 frame size. Reuse the slice-1 RTE FSM; branch on format.
- [ ] failing test (preload a $7 frame at SSP, RTE → SR/PC/A7 restored, SSP += $7 size, redirect) → FAIL → implement → PASS ×2 → commit `exception: RTE from format-$7`.

### Task 4: Lock-step (page fault → handler → RTE) + HONEST SYNTH GATE
**Files:** Modify `lockstep/ExecuteLockStepSpec.scala`.
- [ ] Step 1: A program (MMU enabled): a page table with the data page **non-resident**; a load/store to it → access fault → vector to a handler (in memory) that writes a valid descriptor for the page → RTE → the access now succeeds → continue. Assert the commit stream (PC/SR/A7) + the stacked $7 frame match the MAME 040 oracle step-for-step. ×2 deterministic. (If the MAME RTE-from-$7 re-runs the access, structure the handler/test to match.)
- [ ] Step 2: `ExecuteLockStepSpec` all green ×2 (existing format-$0 exception + MMU + memory programs UNCHANGED + the new page-fault program).
- [ ] Step 3: `make test-fast` + `test-verilator` green; report totals (baseline 67 / 164).
- [ ] Step 4: **HONEST SYNTH GATE (mandatory):** `~/sbt/bin/sbt "runMain m68k040.top.GenFullCoreSynthVerilog"` (0 elaboration errors, NO "UNASSIGNED REGISTER"; `mmuEnableIn`/`rootPtrIn` remain live top inputs) then `vivado -mode batch -nojournal -log synth/vivado_FullCore.log -source synth/ooc_M68kFullCoreSynth.tcl 2>&1 | grep -iE "RESULT|Unsupported|multi-driven net on pin|CRITICAL WARNING|Synth Design complete"`. Confirm 0 err / 0 crit-warn, **WNS ≥ 0 (≥250 MHz)** with the MMU LIVE (baseline 258.8). Report WNS+FMAX+critical path. If the $7 frame logic regresses FMax (it's commit-side serializing — shouldn't), register/narrow it and re-synth.
- [ ] Step 5: commit `mmu/exception: format-$7 page-fault delivery + RTE; lock-step vs MAME 040; honest synth >=250MHz`.

---

## Self-Review
**Spec coverage:** fault capture vec2+faultAddr+SSW (T1) §2; format-$7 frame MAME-matched (T2) §2/§3; RTE-from-$7 (T3) §2; page-fault→handler→RTE lock-step + honest gate (T4) §4. Bus errors / WB-pending fields / ITLB faults deferred. ✓
**Placeholder scan:** the $7 frame layout/SSW is "decode `m68000-sdp.cpp`" (concrete oracle source; document any microarch-specific field that can't be matched + assert the meaningful subset) — not a TBD. Honest-gate guard explicit (no const-fold).
**Type consistency:** `{faulted,vectorNum,faultPc,faultAddr,sswAttrs}` (T1) consumed by the $7 stacker (T2); the $7 frame layout (T2) is the inverse of RTE-from-$7 (T3); reuses slice-1 `faultRetire`/exception FSM/`doFlush`. Vector 2 = access fault throughout.
