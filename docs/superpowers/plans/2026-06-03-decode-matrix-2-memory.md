# Decode Matrix Slice 2 — Memory-EA Cracking (memSimple loads + simple stores) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans. Steps use checkbox (`- [ ]`). The synth gate (final task) is mandatory.

**Goal:** Crack simple memory effective-address operands into load/store µops + int temp registers, feeding the merged LS cluster, so the core executes real load/store instructions end-to-end and **Musashi-lock-steps over load/store programs** (the milestone) — holding the full-core synth ≥250 MHz.

**Architecture:** The `EaDecoder` already classifies `memSimple`; the `MicroOpAssembler` now **expands** a memory instruction into a 1–2 µop sequence (load µop → temp, then the op reading the temp; or a store µop) instead of `unimplemented`. A new **µop expansion queue** between `DecodeStage` and rename buffers the variable-rate expansion and drains 2-wide. Rename gains **2 int temp arch regs** (T0/T1) the cracker targets. The op µop reads the temp via the normal PRF/wakeup (the LS EU's dynamic-completion wakeup, already built, wakes the temp consumer).

**Tech Stack:** SpinalHDL 1.14.1 / Scala 2.13 / sbt `~/sbt/bin/sbt` (NOT on PATH) / Verilator / Vivado. Builds on merged slices: decode-matrix-1 (`OperationDecoder`/`EaDecoder`/`MicroOpAssembler`, spec `2026-06-03-decode-matrix-design.md`), LS cluster-1 (`LsEuPlugin`/`DcachePlugin`/`StoreQueue`, spec `2026-06-03-ls-cluster-1-design.md`; LS µop = `{cluster=LS, memOp, srcA=base, imm=disp, size, dst/srcB}`). Reference: `decode/MicroOpAssembler.scala`, `decode/EaDecoder.scala`, `rename/RenameStage.scala` (archCount, freelist, RAT), `execute/LsEuPlugin.scala`, `lockstep/ExecuteLockStepSpec.scala` + `WhiteboxCapture.scala`.

**Branch:** `feat/decode-matrix-2` off `master`.

**Scope:** memSimple modes `(An)`, `(d16,An)`, `(xxx).W`, `(xxx).L`, `(d16,PC)` as a **source** operand (load) for ALU/MOVE, and as a **MOVE destination** (store of a register source). DEFER (→ stay `unimplemented`, assert in tests): `(An)+`/`-(An)` side-effects, `(d8,An,Xn)`/memComplex, RMW-to-memory (`ADD Dn,(mem)`), two-memory MOVE `(mem),(mem)`.

---

### Task 1: Int temp arch regs (T0/T1) in rename

**Files:** Modify `src/main/scala/m68k040/rename/RenameStage.scala` (and any `archCount`/freelist sizing constant); Test: `src/test/scala/m68k040/rename/TempRegsSpec.scala`.

Add 2 int temp arch regs above D0–7/A0–7: arch ids **16, 17** (int RAT `archDepth` 16→18, int freelist `archCount` 16→18, `physCount` bump to keep ≥ the same free headroom, e.g. 48→50). The cracker references temps by these arch ids. Reg-id width must already cover 16–17 (the µop reg fields are `UInt(4 bits)` = 0–15 → **widen to 5 bits** for int reg ids across `DecodedUop`/`RenamedUop`/RAT/freelist, OR map temps into the existing A-reg space is NOT acceptable — use 5-bit ids). Verify the RAT/freelist parameterize cleanly (3d-1b `commHead` rollback unaffected).

- [ ] **Step 1:** failing test — alloc/commit a temp-targeting renamed uop (dstArch=16), assert it renames to a distinct pdst and commits (rename/RAT/freelist handle arch id 16). Mirror `RenameStageSpec`.
- [ ] **Step 2:** run → FAIL (id 16 out of range / width).
- [ ] **Step 3:** widen int reg-id fields to 5 bits; bump int RAT `archDepth=18`, int freelist `archCount=18`/`physCount` accordingly; thread through. Keep flag RATs unchanged.
- [ ] **Step 4:** run → PASS; re-run `RenameStageSpec`, `FreelistSpec`, `RobPluginSpec`, `ExecuteLockStepSpec` (all green ×2 — widening + 2 temps must not change existing behavior).
- [ ] **Step 5:** commit — `rename: 2 int temp arch regs (T0/T1) for memory-EA cracking`

---

### Task 2: µop expansion queue (variable-in, 2-wide-out)

**Files:** Create `src/main/scala/m68k040/decode/MicroOpQueue.scala`; Test: `src/test/scala/m68k040/decode/MicroOpQueueSpec.scala`.

A FIFO that accepts up to **4 µops/cycle** (2 decode slots × up to 2 µops each) and emits **2/cycle** to rename, preserving order, flushable (mispredict). For this slice each slot emits 1–2 µops (load+op, or 1). Built now because cracking first exceeds 1 µop/instr.

- [ ] **Step 1:** failing test — push a burst of N µops (varying per-cycle counts), pop 2/cycle, assert FIFO order + count; flush clears it. Determinism ×2, all `RegInit`.
- [ ] **Step 2:** run → FAIL.
- [ ] **Step 3:** implement a small ring `Vec(Reg(DecodedUop))` (depth e.g. 16), multi-push (hardware-sum counts — NO `when`-gated Scala vars), 2-pop, flush=pointer reset. Expose `Stream[Vec[DecodedUop],2]` + `uop1Valid` (the rename input contract).
- [ ] **Step 4:** run → PASS ×2.
- [ ] **Step 5:** commit — `decode: micro-op expansion queue (variable-in, 2-wide-out)`

---

### Task 3: Cracker — memSimple load (EAsrc) → load µop + op

**Files:** Modify `src/main/scala/m68k040/decode/MicroOpAssembler.scala` (return a µop *sequence*); Test: `src/test/scala/m68k040/decode/CrackLoadSpec.scala`.

When `srcEa.klass == MEMSIMPLE` and the op consumes an EA source (the `EASRC` role): emit **µop0 = load** `{cluster=LS, memOp=LOAD, srcA=baseAn, imm=disp, size, dst=T0}` then **µop1 = the operation** with the `EASRC` operand replaced by `T0`. Disp/base from `EaDecoder` (extend it to produce `(An)` base + `(d16,An)` disp + abs/(d16,PC) → base/disp; `(xxx).W/.L` use a zero base + abs disp; `(d16,PC)` base = PC). Change `assemble` to return `Vec[DecodedUop]` + a count (1 or 2); `DecodeStage` feeds the queue.

- [ ] **Step 1:** failing test — `ADD.L (A0),D1` → 2 µops: µop0 LOAD (base=8, dst=16), µop1 ADD (srcB=16, dst=1); `MOVE.L (d16,A0),D2` → LOAD with disp, MOVE reading T0. memComplex/`(An)+` → still `unimplemented`. (Unit-test the assembler's Vec output.)
- [ ] **Step 2:** run → FAIL.
- [ ] **Step 3:** implement: `EaDecoder` memSimple base/disp extraction; `MicroOpAssembler` emits the load+op sequence; non-memory stays 1 µop.
- [ ] **Step 4:** run → PASS.
- [ ] **Step 5:** commit — `decode: crack memSimple load EA source into load uop + temp`

---

### Task 4: Cracker — MOVE reg→memSimple (store)

**Files:** Modify `MicroOpAssembler.scala`; Test: `src/test/scala/m68k040/decode/CrackStoreSpec.scala`.

When the op is MOVE and the **destination** EA (`EADST`) is `MEMSIMPLE`: emit **1 store µop** `{cluster=LS, memOp=STORE, srcA=baseAn, imm=disp, size, srcB=dataReg}` (no temp; the data is the register source). RMW-to-mem (ALU op with mem dst) stays `unimplemented`.

- [ ] **Step 1:** failing test — `MOVE.L D1,(A0)` → 1 store µop (base=8, srcB=1); `MOVE.W D2,(d16,A1)` → store with disp; `ADD.L D1,(A0)` → `unimplemented`.
- [ ] **Step 2:** run → FAIL.
- [ ] **Step 3:** implement the MOVE-store crack.
- [ ] **Step 4:** run → PASS.
- [ ] **Step 5:** commit — `decode: crack MOVE reg->memSimple into a store uop`

---

### Task 5: Wire the queue into DecodeStage + rename input

**Files:** Modify `src/main/scala/m68k040/decode/DecodeStage.scala`, `src/main/scala/m68k040/rename/RenameStage.scala` input; Test: existing `DecodeStageSpec` + a new `DecodeCrackPipeSpec`.

`DecodeStage` decodes 2 slots → up to 4 µops → push into `MicroOpQueue` → its 2-wide output is the `DecodeUopService.uops`. Rename consumes the queue output (unchanged contract). Wire the queue flush to the existing `pipeFlush`.

- [ ] **Step 1:** failing test — feed a packet that cracks to 2 µops; assert both appear in order on the rename-facing stream over 2 cycles.
- [ ] **Step 2:** run → FAIL.
- [ ] **Step 3:** wire decode→queue→rename; flush wiring.
- [ ] **Step 4:** run → PASS; `DecodeStageSpec`, `RenameStageSpec` green.
- [ ] **Step 5:** commit — `decode: expansion queue between decode and rename`

---

### Task 6: End-to-end Musashi lock-step over load/store programs (THE milestone) + SYNTH GATE

**Files:** Modify `src/test/scala/m68k040/lockstep/ExecuteLockStepSpec.scala` (add the LS cluster to `FullCoreDut` + load/store programs; the I-cache + D-cache share the behavioral memory image); Modify `top/FullCoreSynth.scala` if not already (LS cluster is in the full core from LS-1).

- [ ] **Step 1:** Add the LS cluster (`LsEuPlugin`+`DcachePlugin`+`StoreQueue`+`DIdentityTranslationPlugin`) to the lock-step `FullCoreDut` and back the D-cache AXI with the same `SparseMemory` as the program image (loads read program-adjacent data; stores write memory, checked at the end). Wire `iq.issue(3)` → LS EU (replace the `issue(3).ready := False` tie), LS completion → ROB + `iq.lsWakeup`, ROB commit/flush → SQ.
- [ ] **Step 2:** Add load/store lock-step programs (each ×2 deterministic), e.g.:
  - `moveq #X,%d0 ; move.l %d0,(%a0) ; move.l (%a0),%d1` (store then load-back; D1==X)
  - `move.l (%a0),%d0 ; add.l (%a1),%d0` (two loads + add)
  - a load feeding an ALU op feeding a store (temp dataflow through the LS dynamic wakeup)
  Seed `%a0`/`%a1` to known data addresses in the behavioral memory; assert the commit stream + final memory vs Musashi.
- [ ] **Step 3:** Run `ExecuteLockStepSpec` → all (old + new) green ×2.
- [ ] **Step 4:** `make SBT=~/sbt/bin/sbt test-fast` + `test-verilator` → green; report totals.
- [ ] **Step 5: SYNTH GATE (mandatory):** `~/sbt/bin/sbt "runMain m68k040.top.GenFullCoreSynthVerilog"` then `vivado -mode batch -nojournal -log synth/vivado_FullCore.log -source synth/ooc_M68kFullCoreSynth.tcl 2>&1 | grep -iE "RESULT|Unsupported|multi-driven net on pin|CRITICAL WARNING|Synth Design complete"`. Confirm 0 err / 0 crit-warn, WNS ≥ 0 (≥250 MHz); report WNS+FMAX+critical path. Pipeline the offending boundary if <250 (report before/after).
- [ ] **Step 6:** commit — `decode+ls: end-to-end memory lock-step (load/store programs) + synth >=250MHz`

---

## Self-Review
**Spec coverage (decode-matrix-design §4.3/§4.4/§4.5 + ls-cluster):** EaDecoder memSimple base/disp (T3); cracker load+temp (T3) + store (T4); µop expansion queue §4.1 (T2); temps in rename §4.5 (T1); end-to-end lock-step §7 (T6); synth gate (T6). RMW-to-mem/complex/(An)+/- explicitly deferred. ✓
**Placeholder scan:** concrete tasks; the EaDecoder base/disp extraction (T3) names the exact modes. No TBD.
**Type consistency:** `memOp`/`cluster`/5-bit int reg-id (T1) used in T3/T4; `MicroOpQueue` `Stream[Vec[DecodedUop],2]` (T2) consumed in T5; `assemble → Vec[DecodedUop]+count` (T3) used in T5. LS µop field mapping matches LS-1 (`srcA`=base, `imm`=disp, `srcB`=store data, `dst`=load temp).
