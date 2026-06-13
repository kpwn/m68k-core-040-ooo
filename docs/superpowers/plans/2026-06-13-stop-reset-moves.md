# STOP / RESET / MOVES Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the deferred privileged system ops RESET (0x4E70) and STOP (0x4E72) on the 68040 OoO core via the existing commit-time system-op (sysOp) path; honestly assess and (most likely) DEFER MOVES with a written reason.

**Architecture:** RESET and STOP ride the Track-D commit-time `sysOp` path (`OperationDecoder` flags → `MicroOpAssembler` op-µop → ROB serializing `sysRetire`/`sysTriggerSig` → `ExceptionUnit` S_APPLY/S_REDIR FSM). RESET is a no-op `SysKind.RESET` that the FSM consumes + advances PC (vector-8 in user mode). STOP is `SysKind.STOP`: it loads SR := imm16 (reusing the MOVE-to-SR SR-write + A7 banking) AND then QUIESCES the core (a `stopped` reg in the ROB gates fetch/retire) until an interrupt with level > the new SR I-mask is recognized; the interrupt entry clears `stopped`. MOVES needs FC-qualified bus accesses (SFC/DFC alternate address space) that this core lacks — assess against Musashi, defer if not lock-steppable.

**Tech Stack:** SpinalHDL (Scala), Verilator lock-step vs Musashi (`tools/musashi/musashi_run.cpp`), Vivado OOC synth (≥200 MHz gate).

---

## Critical Context (READ BEFORE STARTING)

### The sysOp commit path (Track D) — how a privileged system op flows
1. **`src/main/scala/m68k040/decode/OperationDecoder.scala`** (the `is(0x4)` band): a sysOp opword sets `o.sysOp := True`, `o.sysKind := SysKind.<X>`, `o.sysReadDir`, and leaves `o.illegal := False`. Existing precedents in this exact band: MOVE-to-SR (`0x46C0`), MOVE-USP (`0x4E6x`), MOVEC (`0x4E7A/B`).
2. **`src/main/scala/m68k040/decode/DecodedUop.scala`**: `SysKind` enum (`NONE, MOVE_TO_SR, MOVE_USP, MOVEC`), plus the `sysOp`/`sysKind`/`sysReadDir` bundle fields.
3. **`src/main/scala/m68k040/decode/MicroOpAssembler.scala`** (`val isSysOp = spec.sysOp`, the `when(isSysOp)` block ~line 900): builds the op-µop — `op := MOVE` (result = source value for a WRITE), carries the sysOp markers, sets operands. The op-µop's EU writeback VALUE is captured per-ROB-entry into `sysValStore` (so a WRITE delivers the source value to the FSM). `isSysOp` is in the `bad` exclusion list (~line 765/772).
4. **`src/main/scala/m68k040/rename/RenamedUop.scala`**: threads `sysOp`/`sysKind`/`sysReadDir`/`needsSupervisor`/`dstArch`.
5. **`src/main/scala/m68k040/rob/RobPlugin.scala`**: per-entry `sysOpStore`/`sysKindStore`/`sysReadDirStore`/`sysValStore`/`sysValRdyStore` (alloc ~line 437-467). `sysRetire` (head is a sysOp, value ready, excIdle) → `sysTriggerSig := sysRetire && exc.ss.s` (S=1) or `sysPrivFault := sysRetire && !exc.ss.s` (S=0 → vector-8). The FSM ctx (`sysKind`/`sysVal`/`sysRc`/`sysDstPhys`/`sysPc`/`sysNextPc`) is wired into the ExceptionUnit (~line 587-594).
6. **`src/main/scala/m68k040/exception/ExceptionUnit.scala`**: `S_APPLY` switches on `sysCapKind` (1=MOVE-to-SR, 2=MOVE-USP, 3=MOVEC) and applies the committed-state write; `S_REDIR` pulses the obs (post-state sysByte + re-banked A7) + redirects to `sysNextPc`. `sysCapKind` is `sysKind.asBits.asUInt.resize(2)` — **2 bits, currently holds 0..3; RESET=4 and STOP=5 will NOT fit in 2 bits** (see Task 0 — widen `sysKind` ctx to 3 bits).
7. **`src/main/scala/m68k040/frontend/PredecodeWord.scala`** (`is(U(4,4 bits))`): frames the instruction LENGTH so `nextPc` is right (the sysOp redirect target = nextPc). RESET=len1, STOP=len2 (opword + imm16). `src/test/scala/m68k040/frontend/PredecodeRef.scala` is the parity reference the 65536-opword `PredecodeWordSpec` checks — it MUST match.

### SysKind enum width gotcha
`SysKind` currently has 4 values (`NONE, MOVE_TO_SR, MOVE_USP, MOVEC` = indices 0-3). Adding `RESET`+`STOP` → 6 values, indices 0-5, needing **3 bits**. The ExceptionUnit's `sysKind: UInt = U(0, 2 bits)` ctx param + `sysCapKind = Reg(UInt(2 bits))` + the `RobPlugin` wiring `sysKindStore(h0).asBits.asUInt.resize(2)` are all **2 bits** today. Task 0 widens the FSM ctx path to 3 bits BEFORE adding the new kinds, else the new enum values silently truncate (RESET=4 → 0=NONE garbage).

### STOP value-source design (the key non-obvious decision)
MOVE-to-SR captures its source register's EU writeback as `sysValStore`. **STOP has no source register** — the SR comes from the imm16 (the 2nd instruction word). Mirror MOVE-to-SR by making the STOP op-µop a `MOVE` with `useImm := True`, `imm := <imm16 zero-extended>`: the ALU EU's MOVE result = imm16, captured into `sysValStore` exactly like a register source. Then `S_APPLY` for `SysKind.STOP` writes SR from `sysCapVal[15:0]` identically to MOVE-to-SR (the same `ss.setSrSys` + the same `obsSetCcr5` CCR fold). NO new value-capture plumbing.

### STOP quiesce/wake design (the real work)
After STOP commits (the SR is written + A7 re-banked + redirect to nextPc), the core must HALT. Mechanism:
- A `stopped` Reg in `RobPlugin.logic` (RegInit False), simPublic.
- **Set** `stopped` when a `SysKind.STOP` sysOp triggers its serializing retire AND S=1 (the supervisor case; the S=0 case is a vector-8 fault, NOT a halt). Set it the cycle the FSM captures (`sysTriggerSig && sysKindStore(h0)==STOP`), so it latches after the SR write commits.
- **While `stopped`**: gate `retire0`/`retire1` OFF (no instruction retires — though after STOP commits + redirect, younger work was squashed by the redirect, so the ROB is empty anyway; the gate is belt-and-suspenders) AND assert a `quiesce` output the fetch front-end honors (stop fetching). The redirect after STOP already points fetch at nextPc; we must hold fetch there.
- **Clear** `stopped` when `interruptPending` is recognized (an IRQ with level > the new SR I-mask, computed the SAME way the existing `iplActive` is). The interrupt entry then proceeds normally (the existing `interruptPending` → `excEntryTrigger` path). CRITICAL: `interruptPending` must be allowed to fire WHILE stopped — today it requires `count > 0` && `firstStore(h0)` (a head instruction present). When stopped the ROB is empty (`count==0`), so the interrupt-recognition gate needs a `stopped` term: `interruptPending := (stopped || normalIrqGate) && iplActive && excIdle && !flushing`. When stopped, the IRQ is recognized with no head present.
- The fetch quiesce: when `stopped`, the front-end must not advance. Investigate `src/main/scala/m68k040/frontend/FetchAlign.scala` / the redirect/resume ports during T2. If a clean fetch-halt hook does not exist, the fallback is: STOP redirects to its OWN pc (re-fetch STOP forever) so the core spins on STOP until the IRQ preempts it — BUT that re-executes STOP each time (re-writing SR), which is architecturally a no-op (SR := same imm16) and matches a CPU that re-decodes STOP while halted. Assess both in T2; prefer the explicit `stopped` fetch-gate, fall back to self-redirect only if the fetch-gate is intractable, and document which was chosen.

### Musashi STOP semantics (the oracle)
`tools/musashi/musashi_run.cpp` trace loop (line 180-224): each `step_one()` emits a post-instruction state record. When STOP executes, Musashi sets its internal `stopped` flag; subsequent `step_one()` calls while stopped consume cycles but emit the SAME pc/sr (the CPU is frozen). An IRQ (`set_irq`) clears stopped and the next `step_one()` takes the interrupt. **Implication for lock-step:** after the STOP trace step, the oracle emits repeated identical steps until the IRQ. The lock-step harness compares `nInstr` DUT commits vs `nInstr` oracle steps in order — so a STOP test must (a) inject the IRQ via a STOP-aware harness arm (raise iplIn once the DUT is `stopped`, since no further commit fires to drive the existing commit-PC-triggered injection), and (b) count instructions so the STOP step + the wake + handler align. T3 designs the exact test against a captured Musashi trace (do NOT guess the step count — run Musashi first and read the trace).

### MOVES assessment (Task 5 — likely DEFER)
MOVES (`0x0E00` line-0, opmode 7) moves to/from an ALTERNATE address space selected by SFC/DFC. This core: (a) has no function-code-qualified bus — every access is normal supervisor/user data space; (b) SFC/DFC are RAZ-WI in MOVEC (ExceptionUnit `S_APPLY` MOVEC case: SFC=0x000/DFC=0x001 are write-ignored, read-zero). A real MOVES with SFC/DFC=1 (user data) vs 5 (supervisor data) vs 2/6 (program space) would access DIFFERENT memory than a plain move on a real 68040 with separate address spaces — but this core has ONE flat space, so MOVES-with-any-FC degenerates to a plain move. Musashi DOES model FC (separate read/write address-space callbacks), so a Musashi MOVES with SFC/DFC pointing at a DIFFERENT space than the current one would diverge from this core's flat-space access. **The honest assessment (T5): MOVES cannot be lock-stepped meaningfully without an FC-qualified bus + RAZ-WI SFC/DFC made real. DEFER with this written reason; do NOT implement a fake that "passes" by ignoring FC.** T5 confirms by checking the ProgramAssembler supports MOVES + reading Musashi's m68k_in.c MOVES handler; if even the degenerate SFC/DFC=current-space case is not trace-distinguishable AND ProgramAssembler can emit it, implement ONLY that exact degenerate case; otherwise defer.

---

## Task 0: Widen the sysKind FSM ctx to 3 bits (prep — no behavior change)

**Files:**
- Modify: `src/main/scala/m68k040/exception/ExceptionUnit.scala` (the `sysKind` param + `sysCapKind` reg + the `S_APPLY` switch literals)
- Modify: `src/main/scala/m68k040/rob/RobPlugin.scala` (the `sysKind = ...resize(2)` wiring)
- Test: existing `src/test/scala/m68k040/lockstep/ExecuteLockStepSpec.scala` MOVE-USP/MOVE-to-SR/MOVEC tests (regression — must stay green)

- [ ] **Step 1: Widen the ExceptionUnit ctx param + reg from 2 to 3 bits**

In `ExceptionUnit.scala`, change the constructor param:
```scala
    sysKind:      UInt = U(0, 3 bits),
```
and the captured reg:
```scala
  val sysCapKind    = Reg(UInt(3 bits))
```
The `S_APPLY` switch literals are `U(1, 2 bits)` etc. — change them to 3 bits:
```scala
      switch(sysCapKind) {
        is(U(1, 3 bits)) {   // MOVE to SR
```
(repeat for `U(2, 3 bits)` MOVE-USP and `U(3, 3 bits)` MOVEC, and the `S_REDIR` `when(sysCapKind === U(1, 3 bits))`).

- [ ] **Step 2: Widen the RobPlugin wiring**

In `RobPlugin.scala` (~line 588), change:
```scala
      sysKind    = sysKindStore(h0).asBits.asUInt.resize(3),
```

- [ ] **Step 3: Run the existing sysOp lock-step regressions to confirm no behavior change**

Run (its OWN JVM, log to /home/qwertyoruiop/tmp):
```bash
cd /home/qwertyoruiop/m68k-core-040-ooo/.claude/worktrees/agent-a097d25888e6c8073
JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt 'testOnly *ExecuteLockStepSpec -- -z "MOVE USP" -z "MOVE D0,SR" -z "MOVEC"' 2>&1 | tee /home/qwertyoruiop/tmp/t0-regress.log | tail -30
```
Expected: all selected tests PASS (the resize(3) is value-identical for kinds 0-3).

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "stop-reset-moves T0: widen sysKind FSM ctx to 3 bits (prep for RESET/STOP)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 1: RESET (0x4E70) — no-op SysKind on the sysOp path

**Files:**
- Modify: `src/main/scala/m68k040/decode/DecodedUop.scala` (add `RESET` to `SysKind`)
- Modify: `src/main/scala/m68k040/decode/OperationDecoder.scala` (flag 0x4E70 as sysOp/RESET)
- Modify: `src/main/scala/m68k040/decode/MicroOpAssembler.scala` (the RESET op-µop in the `when(isSysOp)` block)
- Modify: `src/main/scala/m68k040/frontend/PredecodeWord.scala` + `src/test/scala/m68k040/frontend/PredecodeRef.scala` (frame 0x4E70 as len1)
- Modify: `src/main/scala/m68k040/exception/ExceptionUnit.scala` (`S_APPLY` RESET case = no-op)
- Test: `src/test/scala/m68k040/decode/OperationDecoderSpec.scala`, `src/test/scala/m68k040/frontend/PredecodeWordSpec.scala`

- [ ] **Step 1: Add RESET to the SysKind enum**

In `DecodedUop.scala`, extend `SysKind`:
```scala
object SysKind extends SpinalEnum {
  val NONE,
      MOVE_TO_SR,   // <ea>.W -> SR (system byte + CCR); re-banks A7 on an S flip.
      MOVE_USP,     // An <-> USP (direction in sysReadDir).
      MOVEC,        // Rn <-> Rc {VBR/USP/CACR/...} (direction in sysReadDir, Rc in imm).
      // RESET (0x4E70): privileged; asserts the external reset line. Architecturally a
      // NOP (no state change); the FSM consumes it + advances PC (serializing). S=0 -> vec 8.
      RESET
      = newElement()
}
```

- [ ] **Step 2: Write a failing OperationDecoder test for RESET**

In `OperationDecoderSpec.scala`, find how an existing sysOp (MOVEC) is asserted (grep `sysOp`/`sysKind` in the spec) and mirror its decode-helper. Add:
```scala
  test("RESET (0x4E70) decodes as a privileged sysOp (SysKind.RESET, not illegal)") {
    val spec = OperationDecoder.decode(B(0x4E70, 16 bits))   // match the spec's actual call style
    assert(!spec.illegal.???, "RESET must not be illegal")    // use the spec's eval helper
    // assert spec.sysOp and spec.sysKind == SysKind.RESET via the spec's evaluation idiom
  }
```
NOTE: `OperationDecoder.decode` returns a hardware `OpSpec` (Bundle) — the spec must be evaluated in a sim/`SpinalHdlBaseSpec` context. Match EXACTLY how the existing MOVEC/MOVE-USP decode test reads `spec.sysOp`/`spec.sysKind` (grep the file first; do not invent the eval idiom).

- [ ] **Step 3: Run it — expect FAIL (RESET still illegal)**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo/.claude/worktrees/agent-a097d25888e6c8073
JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt 'testOnly *OperationDecoderSpec -- -z "RESET"' 2>&1 | tee /home/qwertyoruiop/tmp/t1-dec.log | tail -20
```
Expected: FAIL (RESET decodes illegal / sysOp false).

- [ ] **Step 4: Flag 0x4E70 in OperationDecoder**

In `OperationDecoder.scala`, inside `is(0x4)`, after the MOVEC `when` (~line 339), add:
```scala
        // ── RESET (0x4E70): privileged; asserts the external reset line for 512 clks.
        // Architecturally a NOP (no state change). A COMMIT-TIME SYSTEM op so it serializes
        // + advances PC like the other sysOps; S=0 -> vector-8. The FSM does nothing but
        // consume it + redirect to nextPc.
        when(opword === B"16'h4E70") {
          o.illegal := False
          o.op := DecOp.MOVE
          o.size := Size.LONG
          o.sysOp := True
          o.sysKind := SysKind.RESET
          o.sysReadDir := False
          o.dst.setNone(); o.dstWrites := False
        }
```

- [ ] **Step 5: Build the RESET op-µop in MicroOpAssembler**

In `MicroOpAssembler.scala`, the `when(isSysOp)` block already sets `op := MOVE`, `sysOp := True`, `sysKind := spec.sysKind`, `sysReadDir := spec.sysReadDir`, `firstOfInstr := True`. RESET needs NO source/dst. Add an explicit RESET arm inside `when(isSysOp)` (after the MOVE-USP `when`):
```scala
      // RESET: no source, no dst, no value needed (the FSM is a no-op). Clear all operands
      // so nothing is read/written; the op-µop is purely the serializing macro boundary.
      when(spec.sysKind === SysKind.RESET) {
        opUop.srcAValid := False; opUop.srcBValid := False; opUop.dstValid := False
        opUop.useImm := False
      }
```
RESET is already covered by `isSysOp` in the `bad` exclusion list. No `bad` change needed.

**sysValRdy gotcha:** `sysRetire` gates on `sysValRdyStore(h0)`, set by the `ccrCompletion` when the op-µop's value lands. A RESET op-µop with NO dst/flags writes nothing → does its EU `ccrCompletion` ever fire to set `sysValRdy`? Check `RobPlugin` line ~386-390. If a no-write op never sets `sysValRdy`, RESET would hang at the head. **Mitigation (apply only if it hangs):** mark value-ready at ALLOC. In `RobPlugin.scala` alloc (~line 445 and the `tail+1` mirror ~line 467), change `sysValRdyStore(tail) := False` to:
```scala
      sysValRdyStore(tail)  := allocUopVec(0).sysOp && (allocUopVec(0).sysKind === m68k040.decode.SysKind.RESET)
```
**VERIFY** via the T1 lock-step (Step 11) whether the completion path already sets it; only add the alloc-ready term if RESET hangs. Document which.

- [ ] **Step 6: Frame RESET length in PredecodeWord + PredecodeRef**

In `PredecodeWord.scala` `is(U(4,4 bits))`, after the RTD framing (~line 223):
```scala
        // RESET (0x4E70): single-word privileged sysOp. Frame SIMPLE len1 so nextPc=pc+2
        // (the redirect target after the serializing retire).
        val isReset = op === B"16'h4E70"
        when(isReset) { r.simple := True; r.lenWords := U(1, 3 bits) }
```
Add the SAME RESET=len1 case to `src/test/scala/m68k040/frontend/PredecodeRef.scala` (its line-4 framing) so the 65536-opword parity holds. Read the Ref's line-4 section first and mirror RTS/RTR.

- [ ] **Step 7: Implement the RESET no-op in the ExceptionUnit S_APPLY**

In `ExceptionUnit.scala` `S_APPLY` switch, add a RESET case (kind 4) that does NOTHING:
```scala
        is(U(4, 3 bits)) {                          // RESET : no architectural state change
          // The external reset line is not modeled for lock-step; RESET is an internal NOP.
        }
```
`S_REDIR` then pulses the obs with the UNCHANGED sysByte + A7 and redirects to `sysNextPc` — correct for a no-op that just advances PC. The `obsSetCcr5` fold is gated `when(sysCapKind === U(1,3 bits))` so RESET leaves CCR alone — correct.

- [ ] **Step 8: Run the decode + predecode tests — expect PASS**

```bash
JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt 'testOnly *OperationDecoderSpec *PredecodeWordSpec' 2>&1 | tee /home/qwertyoruiop/tmp/t1-decparity.log | tail -30
```
Expected: PASS (RESET decodes sysOp/RESET; 65536-parity holds with the RESET ref).

- [ ] **Step 9: Write the RESET lock-step tests (supervisor fall-through + user vector-8)**

In `ExecuteLockStepSpec.scala`, near the sysOp tests (~line 2113), add:
```scala
  // RESET (0x4E70): privileged; architecturally a NOP in supervisor (no state change),
  // the FSM consumes it + advances PC. Lock-step: RESET falls through, surrounding moves
  // unaffected. (No external reset-line model needed for lock-step.)
  test("lock-step: RESET (supervisor) falls through (no state change)", VerilatorTest) {
    runLockStep("reset-fallthrough",
      "moveq #1,%d0 ; reset ; moveq #2,%d1 ; " +
      ".stop: bra .stop",
      nInstr = 3)
  }
```
For the user-mode vector-8 trap, mirror the existing privilege test (~line 2636 `privileged op at S=0 -> vector-8` OR the focused `MOVE SR,Dn in USER mode raises a vector-8 privilege violation` ~line 1191). RESET at S=0 must raise vector 8. Match whichever style the existing privilege test uses (a full `runLockStep` with `initialSr = Some(0x0000)` + a vector-8 handler, OR a focused assertion checking `vec == 8`).

- [ ] **Step 10: Run the RESET lock-step — expect PASS (0 diverged)**

```bash
JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt 'testOnly *ExecuteLockStepSpec -- -z "RESET" -z "reset"' 2>&1 | tee /home/qwertyoruiop/tmp/t1-lockstep.log | tail -30
```
Expected: PASS, lock-step `res.ok` true, 0 divergences. If RESET hangs (no commit within the cycle cap), apply the T1-Step-5 alloc-ready mitigation.

- [ ] **Step 11: Commit**

```bash
git add -A && git commit -m "stop-reset-moves T1: RESET (0x4E70) as a no-op commit-time sysOp

Decode 0x4E70 -> sysOp/SysKind.RESET (privileged, not illegal); MicroOpAssembler
emits a value-less op-µop; PredecodeWord/Ref frame len1; ExceptionUnit S_APPLY RESET
case is a NOP (S_REDIR just advances PC). Lock-step: supervisor fall-through + S=0
vector-8 trap, 0 diverged.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 2: STOP quiesce/wake mechanism (the ROB `stopped` state)

This task builds the HALT machinery FIRST (without wiring STOP's decode yet), so T3 can wire STOP to it. Design + verify the quiesce interacts correctly with `interruptPending`/`excIdle`/redirect.

**Files:**
- Modify: `src/main/scala/m68k040/rob/RobPlugin.scala` (the `stopped` reg + the `interruptPending` gate + a quiesce output)
- Investigate: `src/main/scala/m68k040/frontend/FetchAlign.scala` (the fetch-halt hook) + `src/main/scala/m68k040/top/FullCoreSynth.scala` (wiring)
- Test: `src/test/scala/m68k040/rob/RobInterruptSpec.scala` (a focused unit test for the stopped→IRQ→clear transition)

- [ ] **Step 1: Investigate the fetch front-end for a clean quiesce hook**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo/.claude/worktrees/agent-a097d25888e6c8073
grep -n 'redirect\|resume\|halt\|stall\|fetchEnable\|quiesce\|valid' src/main/scala/m68k040/frontend/FetchAlign.scala | head -40
grep -n 'redirect\|resume\|FetchAlign\|fa\.\|rob' src/main/scala/m68k040/top/FullCoreSynth.scala | head -40
```
Read `FetchAlign.scala` + how `RobPlugin` drives the redirect/resume into fetch. DECIDE: (a) an explicit `stopped` fetch-gate (preferred — fetch holds when stopped), or (b) self-redirect fallback (STOP redirects to its own PC, re-fetching STOP until preempted). Document the decision in the commit + the final report.

- [ ] **Step 2: Add the `stopped` reg to RobPlugin**

In `RobPlugin.scala` `logic` area (near the other Regs, ~line 56-130), add:
```scala
    // ── STOP (0x4E72) halt state ────────────────────────────────────────────────
    // Set when a SysKind.STOP sysOp serializes its retire in SUPERVISOR (S=1; the S=0
    // case is a vector-8 fault, not a halt). While `stopped` the core quiesces: no
    // instruction retires (the ROB is empty post-redirect anyway) AND fetch is held
    // (the quiesce output gates the front-end). An interrupt recognized at the right
    // level CLEARS it (the IRQ entry resumes execution at the STOP successor / handler).
    val stopped = RegInit(False); stopped.simPublic()
```

- [ ] **Step 3: Drive `stopped` set/clear (wire after the exc unit + sysRetire are built)**

After the sysOp wiring (~line 601, where `sysTriggerSig` is set), add:
```scala
    // STOP halts the core after its serializing retire (supervisor only; S=0 -> vector-8).
    when(sysTriggerSig && (sysKindStore(h0) === m68k040.decode.SysKind.STOP)) { stopped := True }
    // The interrupt entry resumes: clear `stopped` when an interrupt is recognized.
    when(interruptPending) { stopped := False }
```

- [ ] **Step 4: Let `interruptPending` fire WHILE stopped (no head present)**

Modify the `interruptPending` assignment (~line 633) to recognize an IRQ when stopped even with `count==0`:
```scala
    val maskI = exc.ss.srSys(2 downto 0)
    val iplActive = (iplIn > maskI) || (iplIn === U(7, 3 bits))
    // Normal recognition needs a first-µop head; when STOPPED the ROB is empty (count==0)
    // and the IRQ wakes the halted core with no head present.
    val normalIrqGate = (count > 0) && firstStore(h0) && !faultedStore(h0) &&
                        !isRteStore(h0) && !privViolation && !sysOpStore(h0)
    interruptPending := (normalIrqGate || stopped) && !flushing && excIdle && iplActive
```

- [ ] **Step 5: Gate retire OFF while stopped (belt-and-suspenders)**

Add `&& !stopped` to `retire0` (~line 290):
```scala
    val retire0 = headReady && !faultedStore(h0) && !isRteStore(h0) && !sysOpStore(h0) &&
                  !interruptPending && !privViolation && !stopped
```

- [ ] **Step 6: Expose the quiesce output (the fetch-halt)**

Per the T2-Step-1 decision: if an explicit fetch-gate, add a `quiesce` Bool to RobPlugin (simPublic) driven by `stopped`, and wire it into `FullCoreSynth.scala` to hold the front-end. If self-redirect fallback, instead in T3 set the STOP redirect target to STOP's own PC and skip this step (document it). Add:
```scala
    // Fetch-halt while stopped (the front-end holds; the redirect after STOP points fetch
    // at nextPc, and quiesce prevents it advancing until the IRQ resumes).
    val quiesce = Bool(); quiesce := stopped; quiesce.simPublic()
```
Wire `quiesce` in `FullCoreSynth.scala` to whatever fetch-enable / hold mechanism T2-Step-1 identified.

- [ ] **Step 7: Write a focused unit test in RobInterruptSpec (stopped→IRQ→clear)**

Mirror the existing interrupt-recognition unit tests in `RobInterruptSpec.scala`: drive a STOP sysOp at the head (set the per-entry sysOp stores — per the RECONCILE-LESSON the poker must drive `sysOpStore`/`sysKindStore`/`sysReadDirStore`/`sysValRdyStore`), let it retire (S=1), assert `dut.logic.stopped` goes True; then raise `iplIn` above the mask, assert `interruptPending` fires and `stopped` clears. Match the existing spec's poke helpers (read RobInterruptSpec first — it already pokes interrupt state).

- [ ] **Step 8: Run the unit test — expect PASS**

```bash
JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt 'testOnly *RobInterruptSpec' 2>&1 | tee /home/qwertyoruiop/tmp/t2-rob.log | tail -30
```
Expected: PASS (stopped sets on STOP retire, clears on IRQ recognition).

- [ ] **Step 9: Commit**

```bash
git add -A && git commit -m "stop-reset-moves T2: ROB stopped-state quiesce/wake for STOP

A `stopped` reg set when a SysKind.STOP sysOp retires in supervisor; gates retire +
drives a fetch quiesce; `interruptPending` fires while stopped (count==0, no head) so
an IRQ above the SR mask resumes the core (clears stopped + takes the interrupt).
Unit-tested in RobInterruptSpec.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 3: STOP (0x4E72) decode + SR-load + wire to the quiesce

**Files:**
- Modify: `src/main/scala/m68k040/decode/DecodedUop.scala` (add `STOP` to `SysKind`)
- Modify: `src/main/scala/m68k040/decode/OperationDecoder.scala` (flag 0x4E72)
- Modify: `src/main/scala/m68k040/decode/MicroOpAssembler.scala` (STOP op-µop = MOVE imm16 with sysOp/STOP markers; carry imm16 from words(1))
- Modify: `src/main/scala/m68k040/frontend/PredecodeWord.scala` + `src/test/scala/m68k040/frontend/PredecodeRef.scala` (frame 0x4E72 as len2)
- Modify: `src/main/scala/m68k040/exception/ExceptionUnit.scala` (`S_APPLY` STOP case = SR write like MOVE-to-SR)
- Test: `OperationDecoderSpec.scala`, `PredecodeWordSpec.scala`, `ExecuteLockStepSpec.scala`

- [ ] **Step 1: Add STOP to SysKind**

In `DecodedUop.scala`, AFTER `RESET` (so RESET=4, STOP=5 — matching the FSM literals):
```scala
      // STOP (0x4E72) + imm16: privileged; SR := imm16 (reuses the MOVE-to-SR SR-write +
      // banking) then HALT until an interrupt with level > the new I-mask. The SR write is
      // S_APPLY; the halt is the ROB `stopped` state. S=0 -> vector 8.
      STOP
      = newElement()
```

- [ ] **Step 2: Failing decode test for STOP**

In `OperationDecoderSpec.scala`, mirror the RESET test (T1-Step-2) for `0x4E72` / `SysKind.STOP`.

- [ ] **Step 3: Run — expect FAIL**

```bash
JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt 'testOnly *OperationDecoderSpec -- -z "STOP"' 2>&1 | tee /home/qwertyoruiop/tmp/t3-dec.log | tail -20
```

- [ ] **Step 4: Flag 0x4E72 in OperationDecoder**

After the RESET `when` (T1), add:
```scala
        // ── STOP (0x4E72) + imm16: privileged; SR := imm16 then HALT until IRQ > new mask.
        // A COMMIT-TIME SYSTEM op: the SR write reuses the MOVE-to-SR S_APPLY path (the
        // value = imm16, carried by the assembler as a MOVE-imm op-µop -> sysValStore).
        // The halt is the ROB `stopped` state. S=0 -> vector-8.
        when(opword === B"16'h4E72") {
          o.illegal := False
          o.op := DecOp.MOVE       // result = imm16 (the new SR), captured for the FSM
          o.size := Size.WORD
          o.sysOp := True
          o.sysKind := SysKind.STOP
          o.sysReadDir := False
          o.dst.setNone(); o.dstWrites := False
        }
```

- [ ] **Step 5: Build the STOP op-µop in MicroOpAssembler (MOVE imm16)**

Inside `when(isSysOp)`, add a STOP arm (after RESET's). STOP's value is `pkt.words(1)` (the imm16):
```scala
      // STOP: SR := imm16. The op-µop is a MOVE whose result = imm16 (zero-extended), so
      // the EU writeback VALUE (captured into sysValStore) carries the new SR to the FSM
      // exactly like MOVE-to-SR's register source. No int operands / dst.
      when(spec.sysKind === SysKind.STOP) {
        opUop.op := DecOp.MOVE
        opUop.srcAValid := False; opUop.srcBValid := False; opUop.dstValid := False
        opUop.useImm := True
        opUop.imm := pkt.words(1).asUInt.resize(32).asBits   // imm16 -> new SR (zero-ext)
      }
```
**VERIFY** a `MOVE useImm` op-µop with no dst still drives a `ccrCompletion`/wbObs that sets `sysValRdy` with `result = imm16`. Check the ALU EU's MOVE path (a MOVE with useImm produces `result = imm`). If the no-dst suppresses the value capture, apply the alloc-ready fallback (extend the T1-Step-5 alloc term to include STOP) OR give the op-µop a dropped dst — investigate exactly and document.

- [ ] **Step 6: Frame STOP length in PredecodeWord + PredecodeRef**

In `PredecodeWord.scala` `is(U(4,4 bits))` (after RESET):
```scala
        // STOP (0x4E72) + imm16: opword + 1 imm word -> SIMPLE len2 (nextPc = pc+4).
        val isStop = op === B"16'h4E72"
        when(isStop) { r.simple := True; r.lenWords := U(2, 3 bits) }
```
Add the same STOP=len2 to `PredecodeRef.scala`.

- [ ] **Step 7: Implement STOP SR-write in ExceptionUnit S_APPLY**

Add a STOP case (kind 5) that writes SR exactly like MOVE-to-SR:
```scala
        is(U(5, 3 bits)) {                          // STOP : SR := sysVal[15:0]
          // Identical SR write to MOVE-to-SR: system byte = sysVal[15:8] (S/T/I incl. the
          // new I-mask), CCR = sysVal[4:0]. A7 re-banks on an S flip (S_REDIR via ss.a7).
          // The HALT itself is the ROB `stopped` state (set on the STOP sysRetire).
          ss.setSrSys.valid := True; ss.setSrSys.payload := sysCapVal(15 downto 8).asUInt
        }
```
And extend the `S_REDIR` CCR fold to fire for STOP too:
```scala
      when(sysCapKind === U(1, 3 bits) || sysCapKind === U(5, 3 bits)) {
        obsSetCcr5Valid := True
        obsSetCcr5      := sysCapVal(4 downto 0).asUInt
      }
```
The RobPlugin MOVE-to-SR full-CCR commit (~line 607 `when(exc.obsSetCcr5Valid)`) keys off `obsSetCcr5Valid`, so STOP's CCR commit is automatic.

- [ ] **Step 8: Run decode + parity — expect PASS**

```bash
JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt 'testOnly *OperationDecoderSpec *PredecodeWordSpec' 2>&1 | tee /home/qwertyoruiop/tmp/t3-decparity.log | tail -30
```

- [ ] **Step 9: Capture the Musashi STOP trace to design the lock-step exactly**

Build the STOP test program and run Musashi's trace to see the EXACT step pc/sr (do NOT guess). Program shape (mirror the autovector IRQ test ~line 687-699):
```
move.l #handler,%d0 ; move.l %d0,0x74 ;   // install vector 29 (autovec level 5) @ 0x74
stop #0x2000 ;                              // SR := 0x2000 (S=1, mask=0), then HALT
moveq #2,%d2 ;                              // resumes here after RTE
loop: bra loop ;
handler: moveq #9,%d3 ; rte
```
Inspect the oracle trace: run `Musashi.assembleAndTrace(src, irqEvents = Seq((stopPc, 5)), interruptAckVector = None, initialSr = Some(0x2700))` from a scratch sbt console (or a throwaway test) and PRINT the steps. The IRQ-event PC = the STOP instruction's PC (the IRQ wakes the halted core). Note how many repeated stopped-steps Musashi emits before the IRQ entry, then set `nInstr` accordingly. The DUT injects iplIn while `stopped` (T2's gate) — the existing harness raises iplIn on a commit's successor PC, but STOP halts so NO further commit fires; the test must instead raise iplIn once `dut.rob.logic.stopped.toBoolean` is true. Add a STOP-specific harness arm (a variant of `runIrqLockStep`) that polls `stopped` and raises iplIn then.

- [ ] **Step 10: Write the STOP resume-on-IRQ lock-step test**

Add to `ExecuteLockStepSpec.scala` a STOP test using the STOP-aware IRQ harness (T3-Step-9). Lock-step the FULL state (SR incl. mask / A7 / PC) across: STOP (SR write) → halt → IRQ entry → handler → RTE → resume at the STOP successor. Assert `res.ok` (0 diverged). If the Musashi trace's repeated-stopped-steps make a clean `nInstr` alignment impossible, filter the oracle's repeated identical stopped-steps (compare only the architecturally-meaningful steps: STOP commit, handler, RTE, resume) — this is a faithful comparison (the DUT, being event-driven, produces ONE commit per architectural step, not the cycle-stepped oracle's repeats). If even that is not trace-faithful, STOP-and-report the precise alignment problem (the "may warrant its own slice" escape hatch — but try hard first).

- [ ] **Step 11: Run the STOP lock-step — expect PASS**

```bash
JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt 'testOnly *ExecuteLockStepSpec -- -z "STOP" -z "stop #"' 2>&1 | tee /home/qwertyoruiop/tmp/t3-lockstep.log | tail -40
```
Expected: PASS, 0 diverged. If it diverges, use superpowers:systematic-debugging (read the trace, the WhiteboxCapture commit stream) — do NOT weaken the test.

- [ ] **Step 12: Commit**

```bash
git add -A && git commit -m "stop-reset-moves T3: STOP (0x4E72) SR-load + halt + resume-on-IRQ

Decode 0x4E72 -> sysOp/SysKind.STOP; MicroOpAssembler emits a MOVE-imm16 op-µop so the
imm16 (new SR) is captured into sysValStore; ExceptionUnit S_APPLY writes SR like
MOVE-to-SR (+CCR fold, +A7 re-bank); the ROB stopped state (T2) halts the core until an
IRQ above the new mask resumes it. PredecodeWord/Ref frame len2. Lock-step vs Musashi
across STOP -> halt -> IRQ -> handler -> RTE -> resume, 0 diverged.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 4: Reconcile the new SysKind values across ALL builders + pokers (RECONCILE-LESSON)

Adding `RESET`/`STOP` to `SysKind` widens the enum. Every µop builder that assigns `sysKind` and every test poker that drives `sysKind`/`sysOp` stores must handle the new values (default-inert is fine, but the field must be ASSIGNED everywhere to avoid an elaboration latch / spurious garbage). The full-core lock-step + the 65536-opword PredecodeWordSpec catch what fastTest won't.

**Files (grep-driven — verify each assigns sysKind):**
- `src/main/scala/m68k040/decode/MicroOpAssembler.scala`: `movemMoveUop`, `movemAnUpdUop`, the base `opUop` defaults, `ldUop`/`stUop`/`rmwStUop`, `divlUop`/`divremUop`/`mullUop`/`mulhiUop`/`ibrUop`, `mkUop`
- `src/main/scala/m68k040/decode/Microcode.scala`: `resolve` (sets `sysKind := SysKind.NONE`)
- `src/test/scala/m68k040/rob/RobPluginSpec.scala`, `RobInterruptSpec.scala`, `src/test/scala/m68k040/exception/InterruptEntrySpec.scala`: any poker building a RenamedUop / driving `sysKindStore`

- [ ] **Step 1: Grep every sysKind assignment + poke and confirm coverage**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo/.claude/worktrees/agent-a097d25888e6c8073
grep -rn 'sysKind\|sysOp\b\|SysKind' src/main/scala src/test/scala | grep -v '\.md' | sort
```
For each builder: confirm `sysKind` is ASSIGNED (any value; `NONE` is the default). For each test poker that sets `sysOpStore`/`sysKindStore`: confirm it compiles + drives a valid enum value with the widened enum. The new enum values don't change existing assignments (they all set NONE or a specific kind), so most need NO change — but VERIFY none rely on a 2-bit width or an exhaustive 4-value switch.

- [ ] **Step 2: Run fastTest (the broad decode/unit regression)**

```bash
JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt 'testOnly *DecodedUopSpec *MicroOpAssemblerSpec *RobPluginSpec *RobInterruptSpec *InterruptEntrySpec *Line4DecodeSpec *MicrocodeSpec' 2>&1 | tee /home/qwertyoruiop/tmp/t4-fast.log | tail -40
```
Expected: PASS. A latch / spurious-field error shows as an elaboration failure or a divergence.

- [ ] **Step 3: Commit (only if any builder/poker needed a fix)**

```bash
git add -A && git commit -m "stop-reset-moves T4: reconcile widened SysKind across builders + pokers

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```
(If nothing needed changing, skip the commit and note it in the report.)

---

## Task 5: MOVES (0x0E00) honest assessment + (defer | degenerate impl)

**Files:**
- Read: `tools/musashi/musashi/m68k_in.c` (the MOVES handler — confirm FC-qualified access)
- Read: the `ProgramAssembler` source under `src/test/scala/m68k040/oracle/` (does it emit MOVES?)
- Modify (ONLY if degenerate case is lock-steppable): `OperationDecoder.scala`, `MicroOpAssembler.scala`, `Microcode.scala` (MOVES is µcode per the arch map)
- Else: write the deferral reason into this plan's `## Results` section + the final report

- [ ] **Step 1: Read Musashi's MOVES + confirm FC semantics**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo/.claude/worktrees/agent-a097d25888e6c8073
grep -n -i 'moves\|sfc\|dfc\|m68ki_read.*fc\|function_code\|FUNCTION_CODE' tools/musashi/musashi/m68k_in.c | head -40
```
Confirm: MOVES reads/writes through SFC/DFC-qualified callbacks (a DIFFERENT address space than a plain move when SFC/DFC select a non-current space).

- [ ] **Step 2: Check whether the ProgramAssembler / Musashi harness can emit + run MOVES**

```bash
grep -rn -i 'moves\|0x0e00\|0x0E00' tools/musashi/ src/test/scala/m68k040/oracle/ | head
```
Determine if a MOVES program can even be assembled + traced.

- [ ] **Step 3: Decide + write the assessment**

DEFER (the expected outcome) if: this core has no FC-qualified bus AND SFC/DFC are RAZ-WI, so a real MOVES is not faithfully modellable and a Musashi MOVES with SFC/DFC != current space would diverge — and a weakened "SFC/DFC = current space" test would be a FAKE (it tests nothing MOVES-specific; it's just a move). Write the deferral into the `## Results` section with the exact reason. ONLY implement if the degenerate SFC/DFC=current-space case is genuinely trace-faithful vs Musashi AND it exercises real MOVES decode/crack (not a relabeled move) — and even then, flag it as degenerate. Per the rules: honest deferral beats a fake pass.

- [ ] **Step 4: Commit the assessment (a doc-only commit if deferred)**

```bash
git add docs/superpowers/plans/2026-06-13-stop-reset-moves.md
git commit -m "stop-reset-moves T5: MOVES assessment — DEFER (no FC-qualified bus / RAZ-WI SFC/DFC)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```
(Adjust the message if a degenerate impl is genuinely warranted.)

---

## Task 6: Full lock-step regression + the ≥200 OOC synth gate

**Files:** none (validation only)

- [ ] **Step 1: Run the full ExecuteLockStepSpec (all sysOp + IRQ + the new RESET/STOP)**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo/.claude/worktrees/agent-a097d25888e6c8073
JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt 'testOnly *ExecuteLockStepSpec' 2>&1 | tee /home/qwertyoruiop/tmp/t6-full-lockstep.log | tail -50
```
Expected: ALL tests PASS, 0 diverged.

- [ ] **Step 2: Run the decode/parity/fastTest suite**

```bash
JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt 'testOnly *OperationDecoderSpec *PredecodeWordSpec *MicroOpAssemblerSpec *DecodedUopSpec' 2>&1 | tee /home/qwertyoruiop/tmp/t6-decode.log | tail -40
```
Expected: ALL PASS (incl. the 65536-opword parity).

- [ ] **Step 3: WAIT for any running Vivado, THEN run the OOC synth gate**

A controller post-route P&R may be running. CHECK FIRST and WAIT:
```bash
pgrep -af vivado && echo "VIVADO BUSY — WAIT" || echo "clear to synth"
```
NEVER run 2 vivados / never Verilator+vivado simultaneously. Once clear, run the full-core OOC synth (find the standard script the other slices use):
```bash
ls synth/*.tcl
# Run the standard full-core OOC synth, log to /home/qwertyoruiop/tmp, and report the
# achieved FMax (≥200 MHz gate) + the worst-path source/dest.
```
Expected: ≥200 MHz. Report the FMax + worst path.

- [ ] **Step 4: Final commit (if synth produced a new ooc tcl or any tweak)**

```bash
git add -A && git commit -m "stop-reset-moves T6: full lock-step + decode/parity green; OOC >=200 gate

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Results

- Plan SHA: `eb780c5`
- **RESET (0x4E70): IMPLEMENTED.** No-op `SysKind.RESET` on the commit-time sysOp path; the
  S_APPLY FSM does nothing, S_REDIR advances PC. The value-less op-µop's `sysValRdy` is set
  by the normal no-write completion (no alloc-ready fallback needed — verified by the passing
  lock-step). Lock-step: supervisor fall-through + S=0 vector-8 trap, **0 diverged**.
- **STOP (0x4E72): IMPLEMENTED.** `SysKind.STOP`; the op-µop is a MOVE-imm16 so imm16 (the new
  SR) flows into `sysValStore`; S_APPLY writes SR like MOVE-to-SR (+CCR fold +A7 re-bank).
  **Quiesce design = explicit fetch-gate** (NOT self-redirect): a ROB `stopped` reg drives a
  FetchAlign `quiesce` input (faultHold-style fetch+feed suppress); `interruptPending` fires
  while stopped (count==0, no head) to wake; the IRQ-entry vector redirect resumes fetch. A
  `stoppedPc` reg latches STOP's nextPc so the IRQ entry stacks the correct resume PC (else
  the empty-ROB `pcStore(h0)` is stale → RTE resumes at garbage; this was the one real bug
  caught + fixed via the lock-step). Lock-step: SR-load + halt + level-5 autovector wake +
  handler + RTE + resume, full SR/A7/PC/regs, **0 diverged**.
- **MOVES (0x0E00): DEFERRED — reason:** This core has no function-code-qualified bus and
  SFC/DFC are RAZ-WI (MOVEC SFC=0x000/DFC=0x001 are write-ignored, read-zero). A real MOVES
  selects an ALTERNATE address space via SFC/DFC. Decisive finding: in this Musashi build
  `m68ki_read_N_fc`/`m68ki_write_N_fc` do `(void)fc;` — the FC is IGNORED for the actual
  access (it only sets the fault-frame FC register + feeds the PMMU, which is OFF in lock-step).
  So a degenerate flat-space MOVES *would* trace-match Musashi — but that is precisely why it
  would be a FAKE: neither the core (RAZ-WI SFC/DFC, flat bus) nor the MMU-off Musashi exercises
  the FC distinction, so a MOVES test would be byte-for-byte indistinguishable from a plain MOVE
  and prove NOTHING MOVES-specific, while still requiring µcode-engine decode/crack work. Per
  the no-fake-pass rule, deferred. Implementing MOVES faithfully needs an FC-qualified D-cache
  port + real SFC/DFC made readable (its own slice, gated behind the MMU's FC plumbing).
- New struct/Ctx fields (for the controller to cross-check builders+pokers):
  - `SysKind.RESET` (=4), `SysKind.STOP` (=5) appended to the enum (DecodedUop.scala).
  - ExceptionUnit `sysKind` ctx param + `sysCapKind` reg widened 2→3 bits (+ the S_APPLY/S_REDIR
    literals); RobPlugin `sysKind = ...resize(3)` wiring.
  - RobPlugin `stopped` (RegInit False) + `stoppedPc` (Reg UInt32) — both simPublic.
  - FetchAlignPlugin `quiesce` (Bool, allowOverride idle) — wired from `rob.logic.stopped` in
    FullCoreSynth.
  - No new field on DecodedUop/RenamedUop/Microcode.Ctx (RESET/STOP reuse the existing
    sysOp/sysKind/imm fields), so the µop builders + ROB/IRQ test pokers needed NO new
    assignments — confirmed by T4 grep + the green fastTest/lock-step.
- Lock-step results: **0 diverged** on every test (RESET ×2, STOP ×1, + all pre-existing sysOp/
  IRQ/exception tests in the full ExecuteLockStepSpec run).
- Decode/parity/fastTest: 101/103 green — OperationDecoderSpec (+RESET/+STOP), PredecodeWordSpec
  (65536-opword parity, RESET=len1/STOP=len2), MicroOpAssemblerSpec, DecodedUopSpec,
  RobInterruptSpec, InterruptEntrySpec, MicrocodeSpec, RobPluginSpec. The 2 failures
  (Line4DecodeSpec: `CLR.L (A0)+` + `SWAP D5`) are PRE-EXISTING on the base commit `5d8e791`
  (verified by running Line4DecodeSpec at the base in a throwaway worktree — same 2 fail) —
  unrelated to this work.
- Full ExecuteLockStepSpec note: the 226-test suite OOMs at ~11 Verilator DUT compiles per JVM
  (a pre-existing harness JVM ceiling, independent of these changes; it OOMs identically with
  any test content). Validated the touched cone in per-JVM `-z` batches: batch A (RESET ×2,
  STOP, MOVE-USP, MOVE-to-SR, MOVEC-USP) 6/6; batch B (privilege-op-at-S=0, RESET-at-S=0,
  MOVEC-VBR-exc, MOVE-SR-read forms) 7/7; batch C (all 5 IRQ tests — confirms the
  interruptPending gate change is benign) 5/5. Plus 11 general tests (moveq/alu/cmp/move
  chains/MOVE.B-W/MOVEA) passed before each OOM. All 0 diverged.
- OOC synth gate (synth_design, xcku5p-ffvb676-2-e, 4.0ns constraint — `synth/ooc_M68kFullCoreSynth.tcl`):
  - **This branch: FMax 198.6 MHz** (WNS -1.035ns). Worst path: `AluEuPlugin s1Src2 ->
    s2Stage1_roxlRes` (the ALU barrel-shifter ROXL cone, 19 logic levels, 72% routing).
  - **Base `5d8e791`: FMax 188.9 MHz** (WNS -1.294ns) — same AluEu shifter limiter.
  - => FMAX-NEUTRAL-to-positive (this branch is ~10 MHz faster; the worst path is the
    pre-existing ALU shifter, NOT the decode/ROB/exception/fetch cone these changes touch).
    OOC synth_design is more pessimistic than the post-route P&R the controller runs (the
    honest post-route baseline is ~240 per memory); both base + branch sit below 200 on this
    pessimistic OOC measurement, so the change does not regress the gate.
- Final SHA: (the final commit below)
- Blockers / traces: none. The only real bug (stoppedPc — the IRQ entry stacking a stale
  empty-ROB pcStore(h0) instead of STOP's resume PC) was caught by the STOP lock-step and
  fixed; trace evidence /home/qwertyoruiop/tmp/t3-lockstep.log (diverged step 4: dut=0x0a vs
  oracle=0x0e) -> t3-lockstep2.log (0 diverged after the stoppedPc latch).

---

## Self-Review notes
- Spec coverage: RESET (T1), STOP quiesce (T2) + STOP SR-load/wake (T3), MOVES assessment (T5), reconcile (T4), validation+synth (T6). The SysKind width gotcha is handled in T0 BEFORE adding values.
- Type consistency: `SysKind.RESET`=4, `SysKind.STOP`=5 (appended after MOVEC=3); FSM literals `is(U(4,3 bits))`/`is(U(5,3 bits))` match; `sysKind` ctx + `sysCapKind` widened to 3 bits in T0; `obsSetCcr5` fold extended to STOP (kind 5).
- The two genuine UNKNOWNS flagged for in-execution verification (not guessed): (a) whether a value-less / no-dst sysOp op-µop sets `sysValRdy` (T1-Step-5, T3-Step-5 — alloc-ready fallback documented); (b) the exact Musashi STOP trace step alignment (T3-Step-9 — run Musashi FIRST, do not guess nInstr; filter repeated stopped-steps). Both have documented fallbacks and an honest escape hatch rather than a fake pass.
