# Predecrement / Postincrement Addressing Modes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement `-(An)` predecrement and `(An)+` postincrement EA modes (modes 4/3) — the EA carries an auto-update marker; the assembler folds the `An := An ± delta` write-back into the load/store crack (generalizing the call/return `stkPush`/`anInc` side-effect to any An).

**Architecture:** `EaDecoder` reclassifies modes 3/4 to a memory class + `autoMode{postInc,preDec}` + `autoDelta`; the assembler's load/store/RMW cracks emit the An write-back µop side-effect; the LS EU/AGU computes the access address (`An` for postinc, `An−delta` for predec) and writes `An±delta` on the existing int port. No new EU datapath.

**Tech Stack:** SpinalHDL 1.14.1 / Scala 2.13 / sbt at `~/sbt/bin/sbt` (NOT on PATH) / Verilator / Vivado 2025.2 (`xcku5p-ffvb676-2-e`, OOC 4 ns). Lock-step vs `m68k040.oracle.Musashi`.

**Working dir:** isolated git worktree on `feat/predec-postinc` off current `master` (`4e92bd3`).

**Discipline (HARD):** `~/sbt/bin/sbt`. NEVER Verilator+Vivado concurrent. NEVER the whole `ExecuteLockStepSpec` — `-z` subsets, each its OWN `JAVA_OPTS=-Xmx10g timeout 1000 ~/sbt/bin/sbt …` JVM, never batched, output to files. DO NOT run vivado for intermediate steps; the synth gate is the final step (≥200 — relaxed). After ANY decode change, re-run `OperationDecoderSpec` + `PredecodeWordSpec` (VerilatorTest, OUTSIDE fastTest — they rot silently per the [[isa-completion-roadmap]] lesson).

**Templates to study first:** the `stkPush` predecrement-store (BSR/JSR push to `-(A7)`) and the RTS/RTR `anInc` postincrement in `MicroOpAssembler.scala` + `LsEuPlugin.scala` (the A7 side-effect ride on the int write port); the `crackLoad`/`crackStore`/`crackRmw`/`crackLoadOnly` paths (`MicroOpAssembler.scala:~90-145, 331-399`); `EaDecoder.scala` (the mode switch); `EaSpec` in `DecodeContracts.scala`.

---

## File Structure

- `src/main/scala/m68k040/decode/DecodeContracts.scala` — `EaSpec` += `autoMode` + `autoDelta`.
- `src/main/scala/m68k040/decode/EaDecoder.scala` — modes 3/4 → memory class + autoMode/delta.
- `src/main/scala/m68k040/decode/DecodedUop.scala` — a µop auto-update marker (postInc flag + reuse/generalize stkPush for predec; carry the An delta).
- `src/main/scala/m68k040/decode/MicroOpAssembler.scala` — fold the An write-back into the load/store/RMW cracks.
- `src/main/scala/m68k040/execute/LsEuPlugin.scala` — address = `An`/`An−delta`; write `An±delta`.
- Tests: extend the decode spec (`EaDecoderSpec`/a new one), `ExecuteLockStepSpec`.

---

## Task 1: EaDecoder + EaSpec — classify predec/postinc with auto-update

**Files:** `DecodeContracts.scala`, `EaDecoder.scala`, decode spec.

- [ ] **Step 1: Add `autoMode` + `autoDelta` to `EaSpec`**

In `DecodeContracts.scala`, add an enum + fields to `EaSpec`:
```scala
object EaAuto extends SpinalEnum { val NONE, POSTINC, PREDEC = newElement() }
// ... in EaSpec:
  val autoMode  = EaAuto()      // (An)+ -> POSTINC, -(An) -> PREDEC, else NONE
  val autoDelta = UInt(3 bits)  // An adjust in bytes (1/2/4, or 2 for a byte access on A7)
```
Default `autoMode := EaAuto.NONE`, `autoDelta := 0` in the `EaDecoder` defaults block.

- [ ] **Step 2: Decode modes 3/4 (replace the MEMCOMPLEX classification)**

In `EaDecoder.scala`, replace `is(3, 4) { e.klass := EaClass.MEMCOMPLEX }` with:
```scala
      is(3) {                                      // (An)+  postincrement
        e.klass := EaClass.MEMSIMPLE; e.baseValid := True; e.disp := 0
        e.autoMode := EaAuto.POSTINC; e.autoDelta := autoDelta(size, reg)
      }
      is(4) {                                      // -(An)  predecrement
        e.klass := EaClass.MEMSIMPLE; e.baseValid := True; e.disp := 0
        e.autoMode := EaAuto.PREDEC;  e.autoDelta := autoDelta(size, reg)
      }
```
with a helper (object-level in `EaDecoder`):
```scala
  // An adjust in bytes: sizeBytes, EXCEPT a byte access on A7 (reg==7) -> 2 (keep SP even).
  private def autoDelta(size: Size.C, reg: Bits): UInt = {
    val sb = size.mux(Size.BYTE -> U(1,3 bits), Size.WORD -> U(2,3 bits), Size.LONG -> U(4,3 bits))
    Mux(size === Size.BYTE && reg === U"3'b111", U(2, 3 bits), sb)
  }
```
(The EA for predec is `base − autoDelta`; for postinc it's `base`. The assembler/EU apply this — see Task 2/3. `disp` stays 0; `base = An` already set by the defaults `8+reg`.)

- [ ] **Step 3: Decode spec**

Extend `EaDecoderSpec` (or add a focused spec): mode 3 → `MEMSIMPLE` + `POSTINC` + delta {1/2/4}; mode 4 → `PREDEC`; `-(A7)`/`(A7)+` byte → delta 2; word/long on A7 → delta 2/4. Existing modes unchanged.

Run: `~/sbt/bin/sbt 'testOnly m68k040.decode.EaDecoderSpec'` — PASS.

- [ ] **Step 4: Commit**

```bash
git add src/main/scala/m68k040/decode/DecodeContracts.scala src/main/scala/m68k040/decode/EaDecoder.scala src/test/scala/m68k040/decode/EaDecoderSpec.scala
git commit -m "decode: EaDecoder classifies -(An)/(An)+ (modes 3/4) with autoMode+autoDelta (A7-byte=2)"
```

---

## Task 2 + 3: Assembler crack + LS EU — fold the An write-back

**Files:** `DecodedUop.scala`, `MicroOpAssembler.scala`, `LsEuPlugin.scala`. (Decode-assemble-EU are coupled: a predec/postinc µop needs all three to lock-step, so build + validate them together.)

- [ ] **Step 1: µop carries the auto-update**

In `DecodedUop.scala`, generalize the predec side-effect (today's `stkPush` = predecrement store of A7) to any An, and add postincrement. Add a small marker (mirror `stkPush`):
```scala
  // EA auto-update (general -(An)/(An)+): the mem µop, besides its load/store, writes
  // its base An := An ± autoDelta. preDec: addr = An-delta, An := An-delta (generalizes
  // stkPush to any An/size); postInc: addr = An, An := An + delta. 0/NONE = no update.
  val eaAuto    = EaAuto()       // NONE / POSTINC / PREDEC
  val eaDelta   = UInt(3 bits)
```
Default `eaAuto := NONE`, `eaDelta := 0` everywhere a µop is built (mkUop + the cracks).

- [ ] **Step 2: Assembler — set the An write-back on the load/store/RMW µops**

In `MicroOpAssembler.scala`, where `srcEa`/`dstEa` are MEMSIMPLE with `autoMode != NONE`, thread `eaAuto`/`eaDelta` onto the corresponding mem µop AND make that µop write `An`:
- `crackLoad` (`ldUop`, srcEa): `ldUop.eaAuto := srcEa.autoMode; ldUop.eaDelta := srcEa.autoDelta`. When `autoMode != NONE`, the load also writes its base An: `ldUop.dstReg := srcEa.base; ldUop.dstValid := True` (the An update; the LOADED value goes to the existing T0 result — see the LS EU step for how the An update + the load value coexist: the An write is the µop's int dst, the loaded value flows to T0 via the load-result path exactly as the RTS pop does).
- `crackStore` (`stUop`, dstEa): same — `stUop.eaAuto/eaDelta := dstEa.*`; when auto, `stUop.dstReg := dstEa.base; stUop.dstValid := True` (the An update). This is exactly `stkPush` generalized (predec) + the new postinc.
- `crackRmw` (`rmwStUop`): the RMW shares ONE EA; put the An update on the store side once (`rmwStUop.eaAuto/eaDelta := dstEa.*` + the An dst), and the leading load uses the SAME computed address (predec: the already-decremented An; postinc: An) — pass the auto info so the load and store agree on the address (compute the predec once; both access it).
- For BSR/JSR's existing `stkPush`: leave it working — it is the `PREDEC` case with delta 4 on A7. Re-express it via `eaAuto := PREDEC; eaDelta := 4` OR keep `stkPush` and have the LS EU treat `stkPush` as `PREDEC` — pick the lower-churn option that keeps BSR/JSR/RTS/RTR/LINK/UNLK lock-step green.

- [ ] **Step 3: LS EU / AGU — address compute + An write-back**

In `LsEuPlugin.scala`, generalize the `stkPush` address+writeback to `eaAuto`:
- Access address: `PREDEC -> base − eaDelta`; `POSTINC -> base`; `NONE -> base + disp` (today's path). (`base` = the An read via the µop's source port, as `stkPush` reads A7.)
- An write-back (the µop's int dst, on the existing write port): `PREDEC -> base − eaDelta`; `POSTINC -> base + eaDelta`. (`stkPush` already writes `base − size`; postinc adds the `+` case.)
- The LOADED value (for a load `(An)+`/`-(An)`): flows to T0 as today; the An write is a SEPARATE int dst — confirm the µop can carry both the load-result (T0) and the An update. If a single µop can't write two int regs, crack the An update into its own tiny ADD µop (`An := An ± delta`) ordered after the load / before the store, mirroring how LINK folds A7 with a crack-drop (`divIsRem`) — choose whichever the existing port budget allows; the lock-step is the arbiter.

NOTE (the key correctness point — RAW on An): the An write is LS-produced at variable latency, so a following reader of An must use the dynamic wakeup — this is exactly the `s0IsLsIntProd` path the LINK/UNLK slice added; confirm it covers the predec/postinc An producer (it should — `isLs && pdstValid`).

- [ ] **Step 4: Lock-step bring-up (each its own JVM)**

Run, building up: `MOVE (An)+,Dn` / `MOVE Dn,-(An)` first, then `(Ay)+,(Ax)+`, then ALU/RMW. Use `-z` subsets. Fix against Musashi; the An write-back + address compute are where bugs surface (off-by-delta, A7-byte, RAW-on-An).

- [ ] **Step 5: Commit**

```bash
git add src/main/scala/m68k040/decode/DecodedUop.scala src/main/scala/m68k040/decode/MicroOpAssembler.scala src/main/scala/m68k040/execute/LsEuPlugin.scala
git commit -m "decode+ls: -(An)/(An)+ An write-back folded into the load/store/RMW crack (generalized stkPush + postinc)"
```

---

## Task 4: Lock-step matrix + stale-spec sweep + synth gate

**Files:** `ExecuteLockStepSpec.scala` (+ run-only).

- [ ] **Step 1: Add the lock-step matrix**

In `ExecuteLockStepSpec.scala` (reg-seed pattern: `move.l #addr,%a0 ; …`), with `checkMem`:
- `MOVE.B/.W/.L (A0)+,D0` ; `MOVE.B/.W/.L D0,-(A0)` ; `MOVE.L (A1)+,(A0)+` (block copy step) ; `MOVE.B D0,-(A7)` then `(A7)+` (byte → A7±2 even).
- `ADD.L (A0)+,D0` ; `CLR.L (A0)+` ; `NEG.L -(A0)` ; and the now-enabled `ADDX.L -(A1),-(A0)` / `SBCD -(A1),-(A0)` register-pair-decrement memory forms.
- Edges: An wraparound; `(A0)+` followed by a `move.l %a0,%d1` (RAW on the An update); a predec whose An is also the data source.

- [ ] **Step 2: Run the lock-step subsets**
```bash
for z in "An)+" "-(A" "ADDX" "SBCD" "block copy" "CLR" "NEG"; do
  JAVA_OPTS=-Xmx10g timeout 1000 ~/sbt/bin/sbt "testOnly m68k040.lockstep.ExecuteLockStepSpec -- -z \"$z\"" > /home/qwertyoruiop/tmp/pp-"${z// /_}".log 2>&1
  echo "$z: $(grep -iE 'Tests: succeeded|failed|diverged|Simulation failed' /home/qwertyoruiop/tmp/pp-"${z// /_}".log | tail -1)"
done
```
Expected: all PASS, 0 diverged. (Pick `-z` substrings matching your actual test names.)

- [ ] **Step 3: Stale-spec sweep + test-fast (each own JVM)**
```bash
JAVA_OPTS=-Xmx10g timeout 900 ~/sbt/bin/sbt 'testOnly m68k040.decode.OperationDecoderSpec m68k040.decode.EaDecoderSpec m68k040.frontend.PredecodeWordSpec'
JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt fastTest
```
Expected: green (regression — the BSR/JSR/RTS/RTR/LINK/UNLK that ride the generalized stkPush must stay green; PredecodeWordSpec parity holds — predec/postinc add no extension word).

- [ ] **Step 4: Commit tests + run the ≥200 synth gate (vivado — NO Verilator concurrent)**
```bash
git add src/test/scala/m68k040/lockstep/ExecuteLockStepSpec.scala
git commit -m "test: -(An)/(An)+ lock-step matrix (MOVE/ALU/RMW/ADDX/SBCD/CLR/NEG mem forms, A7-byte even, RAW-on-An)"
JAVA_OPTS=-Xmx10g ~/sbt/bin/sbt "runMain m68k040.top.GenFullCoreSynthVerilog"
pgrep -af vivado   # spare any 030
timeout 1800 vivado -mode batch -nojournal -nolog -source synth/ooc_M68kFullCoreSynth.tcl
grep -nE "RESULT FullCore|Slack \(VIOLATED|Source:|Destination:" synth/M68kFullCoreSynth_timing.rpt | head
git commit --allow-empty -m "synth: predec/postinc gate — FMax <X> (>=200), worst path <…>"
```
Expected: FMax ≥ 200 (the An adjust is a shallow add; expect FMax-neutral, D-cache still the limiter).

---

## Done-When

- `EaDecoder` modes 3/4 → MEMSIMPLE + `autoMode`/`autoDelta` (A7-byte=2); decode spec green.
- Assembler folds the `An := An ± delta` write-back into load/store/RMW (generalized `stkPush` + postinc); BSR/JSR/RTS/RTR/LINK/UNLK stay green.
- LS EU computes `An`/`An−delta` address + writes `An±delta`; RAW-on-An via the `s0IsLsIntProd` dynamic wakeup.
- Lock-step matrix green (MOVE/ALU/RMW + the now-enabled ADDX/SBCD/CLR/NEG memory forms, A7-byte even, RAW-on-An, wraparound), 0 diverged; `OperationDecoderSpec`/`PredecodeWordSpec`/`fastTest` green.
- ≥200 synth gate reported.
- Memory updated ([[isa-completion-roadmap]]): predec/postinc done; **MOVEM is the committed next slice** (now unblocked).
