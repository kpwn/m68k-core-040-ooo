# Bit-Field Memory Dynamic Offset/Width (Slice 3c) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Implement dynamic (Dn-sourced) offset and/or width for the 8 MEMORY bit-field ops (BFTST/BFEXTU/BFEXTS/BFFFO/BFCHG/BFCLR/BFSET/BFINS), byte-identical vs Musashi. The last integer-ISA slice.

**Architecture:** A COLD instruction. Reuse the existing static-mem bit-field crack (3a/3b microcode chains) + the register-form dynamic machinery (`BFRESOLVE` + the `bfDynamic` funnel). The only new thing: a runtime signed byte base `Tbase = eaBase + (offsetDn >>>signed 3)` (two's-complement reproduces Musashi's signed floor — no special-case) feeding the load/store µops; always process the 5-byte spill window (straight-line engine, no runtime needHi branch).

**Tech Stack:** SpinalHDL 1.14.1 / Scala 2.13 / Verilator / Vivado 2025.2 (xcku5p-ffvb676-2-e). sbt `~/sbt/bin/sbt`. Oracle = Musashi.

## Global Constraints

- **Spec authoritative:** `docs/superpowers/specs/2026-06-30-bitfield-mem-dynamic-3c-design.md`. §3 = VERBATIM Musashi (match each op's flags/extract/insert/BFFFO-offset/sign-extend).
- **GOVERNING: FMax-neutrality dominates (cold op).** Reuse ONLY existing structures (bfDynamic funnel, BFRESOLVE, ALU shift/add, LS load/store µops). **No new hot datapath** (no new ALU arm / funnel change / AGU change). **No archDepth bump** unless the gate proves it FMax-neutral — DEFAULT to recomputing `byteBase` for the store phase (reuse `T0/T1/T2` + extra cold µops). Minimize the decode-area footprint (reuse the register-form dynamic offset/width-Dn latching) — decode-area growth tipped the front-end floor in CAS.
- Signed byte base: `byteBase = eaBase + (offsetDn >>>signed 3)`, `bitOff = offsetDn & 7` (two's-complement = Musashi's `ea+=offset/8; offset%=8; if<0{+=8;ea--}`). Width `= ((widthSrc-1)&31)+1` (0→32).
- Always-5-byte span (no data-dependent branch in the straight-line engine); funnel leaves the spill byte unchanged when unused → RMW write-back is a no-op.
- Honesty: never fake/weaken/skip/delete tests; never const-fold/stub MMU/SR; honesty over green.
- Lock-step each ≥2×. JVM ~7.6GB → SINGLY / small `-z`, ONE `-z`/run, each `JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt`. Never two vivados / Verilator+vivado / whole ExecuteLockStepSpec in one JVM. `pgrep -x vivado` before synth; spare foreign jobs; kill only own sbt by PID.
- **POST-ROUTE gate = NO FLOOR REGRESSION vs master** (≥4-6 regens, BOTH `_zz_` orderings, every ≥200 AND floor ≳ master's ~206-209). If the cold path perturbs the front-end sub-floor (CAS-style), SHRINK the decode/cold footprint (free — it's cold), don't retime a hot path.
- Commit `git commit -F <file>` (no backtick-words); body ends `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`.
- gas: `m68k-linux-gnu-as -m68040`. Confirm gas emits the dynamic memory forms (e.g. `bfextu (%a0){%d1:%d2},%d3`, `bfins %d3,(%a0){%d1:#8}`, `bfclr (%a0){#4:%d2}`).

---

### Task 1: Un-gate the dynamic memory forms + decode/crack routing

**Files:**
- Modify: `src/main/scala/m68k040/decode/OperationDecoder.scala` (the line-E bit-field arm — allow Do/Dw on memory EAs; route microcoded; preserve the 3b legality: control-alterable EA for RMW, PC-rel-RMW illegal)
- Modify: `src/main/scala/m68k040/decode/MicroOpAssembler.scala` and/or `DecodeStage.scala` (latch offset/width-Dn for the dynamic-mem crack; reuse the register-form's dynamic latching)
- Test: `src/test/scala/m68k040/decode/BitfieldDecodeSpec.scala` (add dynamic-mem decode cases)

**Interfaces:**
- Consumes: existing bit-field decode (static-mem 3a/3b + register-form dynamic).
- Produces: dynamic memory bit-field forms decode microcoded (not illegal); Do=ext[11]/Dw=ext[5] select Dn sources; legality preserved.

- [ ] **Step 1: Confirm gas + encoding.** Assemble `bfextu (%a0){%d1:%d2},%d3` / `bfins %d3,(%a0){%d1:#8}` / `bfclr (%a0){#4:%d2}` / `bfchg (%a0){%d1:%d2}` with `m68k-linux-gnu-as -m68040`; objdump → confirm Do=ext bit11, Dw=ext bit5, offset-Dn=ext[8:6], width-Dn=ext[2:0].
- [ ] **Step 2: Write `BitfieldDecodeSpec` dynamic-mem cases (failing):** BFEXTU/BFINS/BFCLR/BFCHG dynamic-offset and/or dynamic-width on `(%a0)` decode microcoded (not illegal); a dynamic-mem RMW at PC-rel stays illegal; read-only dynamic-mem at a read EA legal.
- [ ] **Step 3: Run — verify fail** (currently illegal).
- [ ] **Step 4: Implement the un-gate + routing** (minimal decode footprint — reuse the register-form dynamic offset/width-Dn latch path; just extend the memory arm to accept Do/Dw and route to the v2 engine with a dynamic-mem entry).
- [ ] **Step 5: Run `BitfieldDecodeSpec` + exhaustive `PredecodeWordSpec`/`PredecodeRefSpec` — PASS** (predecode already frames these; confirm no regression).
- [ ] **Step 6: Commit** (`feat(bf3c): un-gate dynamic-offset/width memory bit-field forms + decode routing`).

---

### Task 2: Dynamic crack — read-only ops (BFTST/BFEXTU/BFEXTS/BFFFO)

**Files:**
- Modify: `src/main/scala/m68k040/decode/Microcode.scala` (dynamic-mem read-only chain: byteBase pre-µop + resolve + LOAD lo (+ spill) + bfDynamic funnel extract)
- Modify: `src/main/scala/m68k040/decode/DecodeStage.scala` (feed offsetDn/widthDn + eaBase into the chain)
- Test: `src/test/scala/m68k040/lockstep/ExecuteLockStepSpec.scala`

**Interfaces:**
- Consumes: Task 1 routing; the existing `BFRESOLVE` µop + `bfDynamic` funnel (register-form); existing ALU shift/add + LS load µops.
- Produces: dynamic-mem read-only ops working (Dn result + flags), byteBase = eaBase + (offsetDn>>>3), bitOff = offsetDn&7, width from widthDn.

- [ ] **Step 1: Write read-only dynamic-mem lock-step (failing):** `BFEXTU (%a0){%d1:%d2},%d3` with: small in-byte offset; large offset (e.g. d1=20 → byteBase+2,bitOff=4); negative offset (d1=-1 → byteBase=ea-1,bitOff=7); spill (bitOff=6,width=30); dynamic width incl d2=0→32. Plus `BFEXTS` (sign-extend), `BFFFO` (offset-based result), `BFTST` (flags only). Check Dn + flags vs Musashi.
- [ ] **Step 2: Run — verify fail.**
- [ ] **Step 3: Implement the read-only crack.** byteBase pre-µop (`eaBase + (offsetDn >>>signed 3)` via existing ALU shift+add; `bitOff=offsetDn&7`); resolve {bitOff,width} via BFRESOLVE (reuse); LOAD lo (+ always LOAD spill byte); bfDynamic funnel extract → Dn + flags. Reuse the static read-only chain shape, swapping the static disp base for the runtime byteBase.
- [ ] **Step 4: Run ×2 — PASS** (each own JVM). Confirm negative/large/spill all match Musashi.
- [ ] **Step 5: Commit** (`feat(bf3c): dynamic-mem read-only crack (BFTST/BFEXTU/BFEXTS/BFFFO)`).

---

### Task 3: Dynamic crack — RMW ops (BFCHG/BFCLR/BFSET/BFINS)

**Files:**
- Modify: `src/main/scala/m68k040/decode/Microcode.scala` (dynamic-mem RMW chain: + funnel lo/hi recompute + STORE lo (+ spill); always-5-byte; store-phase byteBase)
- Test: `src/test/scala/m68k040/lockstep/ExecuteLockStepSpec.scala`

**Interfaces:**
- Consumes: Task 2's byteBase/resolve pattern + the static RMW funnel (lo/hi store forms).
- Produces: dynamic-mem RMW ops working; mem updated; flags from the original field (BFINS from the inserted value).

- [ ] **Step 1: Write RMW dynamic-mem lock-step (failing):** `BFCLR/BFCHG/BFSET (%a0){%d1:%d2}` + `BFINS %d3,(%a0){%d1:%d2}` with small/large/negative offset + spill + dynamic width. Verify mem (checkMem incl. the spill byte) + flags vs Musashi (BFINS N/Z from inserted value; BFCHG/CLR/SET flags from original field).
- [ ] **Step 2: Run — verify fail.**
- [ ] **Step 3: Implement the RMW crack** (always-5-byte: load lo + spill, funnel res, funnel lo/hi recompute, store lo + spill; store-phase byteBase = recompute `eaBase + (offsetDn>>>3)` to avoid holding a temp — the FMax-neutral default; only hold a temp if Step-6 gate proves it free).
- [ ] **Step 4: Run ×2 — PASS.** Confirm the no-op spill write-back when field doesn't reach it (mem byte-identical).
- [ ] **Step 5: Commit** (`feat(bf3c): dynamic-mem RMW crack (BFCHG/BFCLR/BFSET/BFINS)`).

---

### Task 4: Regression

**Files:** none (test runs only)

- [ ] **Step 1: Static bit-field regression ×2** — the 3a/3b static-mem bit-field lock-step (all 8 ops) + the register-form dynamic bit-field — byte-identical (the dynamic-mem crack must not perturb the static/register paths).
- [ ] **Step 2: Broad regression ×2** — CAS/CAS2, MOVES, full-ext mem-indirect, MOVEM, branch/loop, MOVEC — byte-identical.
- [ ] **Step 3: Decode regression** — `BitfieldDecodeSpec` + exhaustive `PredecodeWordSpec`/`PredecodeRefSpec` green.
- [ ] **Step 4: Commit** if any test files were added (`test(bf3c): regression confirmation`).

---

### Task 5: POST-ROUTE FMax gate — NO FLOOR REGRESSION

**Files:** none (synth only)

- [ ] **Step 1: `pgrep -x vivado`** — wait for any foreign job; never two vivados.
- [ ] **Step 2: Regen+gate ≥4-6× surfacing BOTH `_zz_` orderings** (gen `.v` → md5 → `vivado -mode batch -source synth/impl_FullCore.tcl` → grep `ACHIEVED_FMAX_MHZ` + limiting net).
- [ ] **Step 3: Evaluate.** EVERY regen ≥200 AND the floor must NOT regress vs master (~206-209). Confirm NO new limiter is in the 3c logic (limiters should stay the pre-existing front-end ibuf/decodePc/bf-funnel arcs). If a regen dips below master's floor (CAS-style perturbation), STOP and report — the fix is to SHRINK the cold/decode footprint (reuse more of the existing path, drop any added temp), not retime a hot path.
- [ ] **Step 4: Report** every regen's md5 + FMax + limiting net.
- [ ] **Step 5** (controller): merge + update `isa-completion-roadmap` memory (integer ISA COMPLETE).

---

## Self-Review

**Spec coverage:** §0 cold/FMax-first (global constraints + Task 5) ✓; §1 signed byteBase (Task 2/3) ✓; §2 crack + reuse + always-5-byte + temp strategy (Task 2/3) ✓; §3 Musashi verbatim (Task 2/3 per-op) ✓; §4 decode/un-gate/predecode (Task 1) ✓; §5 testing matrix (Task 2/3/4) ✓; §6 no-floor-regression gate (Task 5) ✓; §7 build order = task order ✓.
**Placeholder scan:** SpinalHDL specifics delegated to the implementer (reuse 3a/3b + register-form patterns); files/tests/observable-semantics/FMax-gate pinned. No vague "handle edge cases."
**Type consistency:** `byteBase`/`bitOff`/`BFRESOLVE`/`bfDynamic`/`T0/T1/T2`/offsetDn/widthDn/Do(ext11)/Dw(ext5) consistent across tasks.
