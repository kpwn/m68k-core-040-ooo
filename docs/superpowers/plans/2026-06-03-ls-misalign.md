# LS Misaligned / Line- & Page-Crossing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development / executing-plans. Checkbox steps. The synth gate (final task) is MANDATORY (≥250 MHz).

**Goal:** Let a single load/store that crosses a 16-byte L1D line and/or a 4 KB page complete in **one µop** via a two-access-slot mechanism — optimistic single access when aligned, a bounded second access when it crosses — and lock-step misaligned/line-crossing memory programs vs Musashi, holding the full-core synth ≥250 MHz.

**Architecture:** The AGU (in `LsEuPlugin`) computes `crossLine`/`crossPage`/`twoAccess` + `addrB`. The LS EU's single-outstanding FSM gains a second access step (access A then, if `twoAccess`, access B) and a byte-lane **split-merge** (load gathers the `size` bytes spanning the two lines; store splits into two `{addr,bytes,strobe}` slots). The store queue entry gains an **optional second slot**; forwarding checks both slots; commit-drain emits both atomically; flush squashes both. Under the identity `DTranslationService` no fault can occur, but the **two-translation** path (slot B for page-cross) is structured so the real-DTLB slice slots faults in.

**Tech Stack:** SpinalHDL 1.14.1 / sbt `~/sbt/bin/sbt` (NOT on PATH) / Verilator / Vivado. Builds on merged LS cluster + memory-cracking. Reference: `execute/LsEuPlugin.scala` (AGU, single-outstanding FSM, RESOLVE/completion stage), `cache/DcachePlugin.scala` (load/store ports, byte-lane), `ls/StoreQueue.scala` (entry, forward, drain-ack), `lockstep/ExecuteLockStepSpec.scala`. Spec: `docs/superpowers/specs/2026-06-03-ls-misalign-design.md`.

**Branch:** `feat/ls-misalign` (already created; spec committed). **Aligned fast path must stay unchanged** (`twoAccess=0` → exactly today's behavior).

---

### Task 1: AGU cross-detection
**Files:** Modify `execute/LsEuPlugin.scala`; Test: `src/test/scala/m68k040/ls/AguCrossSpec.scala`.
In S0/S1 compute (from `va`, `sizeBytes` = 1/2/4 by `size`): `lineOff = va(3 downto 0)`; `crossLine = (lineOff +^ sizeBytes) > 16`; `pageOff = va(11 downto 0)`; `crossPage = (pageOff +^ sizeBytes) > 4096`; `twoAccess = crossLine || crossPage`; `addrB = (va & ~U(15,32 bits)) + 16` (next line base; equals next page base when crossPage). Register alongside `s1Va`.
- [ ] Step 1: failing test — drive a long (size 4) at va offset 14 → `crossLine` true, `twoAccess` true, addrB = next line; size 2 at 0x...FFF → `crossPage` true; aligned long at offset 0 → twoAccess false. (Expose the computed signals via a small probe or simPublic.)
- [ ] Step 2: run → FAIL. Step 3: implement. Step 4: PASS ×2. Step 5: commit `ls: AGU line/page cross-detection (twoAccess, addrB)`.

### Task 2: Byte-lane split-merge
**Files:** Modify `cache/DcachePlugin.scala` (or a `DcacheByteLane` object); Test: `src/test/scala/m68k040/cache/ByteLaneSplitSpec.scala`.
Extend the byte-lane helpers: a **load merge** that, given line A data + line B data + the split offset + size, gathers the `size` big-endian bytes spanning the boundary into the 32-bit result; a **store split** that produces `{strobeA, dataA}` and `{strobeB, dataB}` for the `size` data bytes at the offset. Aligned (no cross) ⇒ all bytes in A, strobeB=0 (unchanged path).
- [ ] Steps: failing test (long at offset 14 from two known lines → correct merged value; store split → correct strobes); FAIL; implement; PASS ×2; commit `cache: byte-lane split-merge for cross-boundary access`.

### Task 3: LS EU two-access sequencing (loads)
**Files:** Modify `execute/LsEuPlugin.scala`; Test: `src/test/scala/m68k040/ls/LsEuCrossSpec.scala`.
Extend the FSM: when `twoAccess`, after access A's data is captured, issue access B at `addrB` (each can independently hit/miss-refill the L1D — reuse the WAIT/refill path per access), capture B, then byte-lane-merge A+B → the registered completion (same RESOLVE/completion stage). `issuePort.ready` stays low across both accesses (single-outstanding). Translation: request slot B's VPN when `crossPage` (reuse A's ppn for line-cross). Aligned (twoAccess=0) ⇒ one access, unchanged.
- [ ] Steps: failing test — inject a misaligned load spanning two preloaded lines → merged value via wbObs/PRF; a page-crossing load (identity xlate) → two accesses, correct merge; aligned load unchanged. FAIL; implement; PASS ×2; commit `execute: LS EU two-access load sequencing + merge`.

### Task 4: Store queue two-slot atomic drain + forward
**Files:** Modify `ls/StoreQueue.scala`, `execute/LsEuPlugin.scala`; Test: `src/test/scala/m68k040/ls/StoreQueueSplitSpec.scala`.
Give the SQ entry an **optional second slot** `{addrB, dataB, strobeB, validB}` (one entry = one atomic store). `alloc` carries both slots (from the store split). **Forward**: a younger load checks BOTH slots of every older entry for overlap. **Drain**: emit slot A then slot B as two `DStoreCmd`s, pop only after BOTH are drain-ACKed (extend the drain-ack hazard fix to two writes). **Flush**: pointer rollback drops both slots (unchanged). Aligned store ⇒ validB=false (single-slot, unchanged).
- [ ] Steps: failing test — split store alloc → forward to a younger load that overlaps slot B; commit-drain writes both halves to behavioral memory atomically (both ACKed before pop); flush squashes a split store (neither half drains). FAIL; implement; PASS ×2; commit `ls: store queue two-slot atomic drain + dual-slot forward`.

### Task 5: End-to-end lock-step (misaligned programs) + SYNTH GATE
**Files:** Modify `lockstep/ExecuteLockStepSpec.scala`.
- [ ] Step 1: Add misaligned/line-crossing load/store programs vs Musashi, e.g. `move.l %d0,(2 byte-misaligned addr)` then load-back; a long store at a line-crossing address then a load of an overlapping range; seed data so Musashi (which models 68040 misaligned semantics) is the oracle; assert commit stream + final memory. ×2 deterministic.
- [ ] Step 2: `ExecuteLockStepSpec` all green ×2 (aligned programs unchanged + new misaligned ones).
- [ ] Step 3: `make SBT=~/sbt/bin/sbt test-fast` + `test-verilator` green; report totals (baseline 58 / 126).
- [ ] Step 4: **SYNTH GATE (mandatory):** `~/sbt/bin/sbt "runMain m68k040.top.GenFullCoreSynthVerilog"` then `vivado -mode batch -nojournal -log synth/vivado_FullCore.log -source synth/ooc_M68kFullCoreSynth.tcl 2>&1 | grep -iE "RESULT|Unsupported|multi-driven net on pin|CRITICAL WARNING|Synth Design complete"`. Confirm 0 err / 0 crit-warn, **WNS ≥ 0 (≥250 MHz)** (baseline 260.55); report WNS+FMAX+critical path. The two-access sequencing is latency (not a new comb cloud); if the dual-slot SQ forward-compare regresses FMax, register it / keep SQ depth small and re-synth (report before/after).
- [ ] Step 5: commit `ls: misaligned/line-/page-crossing access (two-access-slot) + lock-step + synth >=250MHz`.

---

## Self-Review
**Spec coverage:** AGU cross-detect (T1) §3; byte-lane split-merge (T2) §3.2; LS-EU two-access loads (T3) §3.1; SQ two-slot atomic drain + dual-slot forward (T4) §3.3; two-translation on page-cross (T3, identity) §2; lock-step + synth (T5) §4. Precise page-fault-on-second-half explicitly deferred (identity never faults; structure built). ✓
**Placeholder scan:** concrete signals (`crossLine`/`crossPage`/`twoAccess`/`addrB`, slot-B fields) + concrete tests per task. No TBD.
**Type consistency:** `twoAccess`/`addrB` (T1) consumed in T3/T4; the SQ entry second slot `{addrB,dataB,strobeB,validB}` (T4) produced by the store split (T2) and the alloc (T4); byte-lane merge/split (T2) used by T3/T4; aligned fast path (`twoAccess=0`/`validB=false`) preserved everywhere.
