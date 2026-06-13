# Bit-op (An)+/-(An) BYTE eaDelta Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the RTL so a memory bit-op (`BSET/BCLR/BCHG #n,(A0)+` / `-(A0)`) increments/decrements An by 1 (BYTE access), not 2 — and assert the now-correct behavior in the decode + lock-step tests.

**Architecture:** Memory bit-ops are BYTE-sized, but their opword has no size field (the size bits encode the bit-op TYPE), so `OperationDecoder` leaves `OpSpec.size` at the WORD default. `EaDecoder.decode(eaField, spec.size, words)` therefore computes `autoDelta = 2` (WORD) for an `(An)+`/`-(An)` bit-op EA. The assembler already resolves the bit-op ACCESS size to BYTE (`bitOpSize`, `ldUop.size`/`rmwStUop.size := Size.BYTE`) but copies the WORD-derived `srcEa.autoDelta` into the load/store/anUpd `eaDelta`. Fix: in `MicroOpAssembler`, recompute a BYTE-resolved auto-delta for the bit-op crack (honoring the A7-byte→2 even-SP rule) and use it for the bit-op `eaDelta` fields, leaving all non-bit-op deltas untouched.

**Tech Stack:** SpinalHDL (Scala), Verilator sim, Musashi lock-step oracle, sbt.

---

## Root cause (confirmed by code reading)

- `OperationDecoder.scala:71-82`: the BITOP branch sets `o.op := DecOp.BITOP` and `o.bitOp`, but NEVER assigns `o.size`. `OpSpec.illegalDefault()` (`DecodeContracts.scala:144`) sets `o.size := Size.WORD`.
- `MicroOpAssembler.scala:138`: `srcEa = EaDecoder.decode(op(5 downto 0), spec.size, pkt.words)` → for a BITOP, `spec.size == WORD`.
- `EaDecoder.scala:25-28`: `autoDeltaOf` = `sizeBytes` (WORD→2), A7-byte exception only triggers when `size===BYTE`. So a WORD-typed bit-op `(An)+` yields `autoDelta = 2` (and on A7 it would ALSO be 2 but for the wrong reason).
- `MicroOpAssembler.scala:490, 581, 1561`: the bit-op mem-RMW load (`ldUop.eaDelta`), RMW store (`rmwStUop.eaDelta`), and the source-EA An-update ADD (`anUpdUop` via `srcEa.autoDelta`) all copy `srcEa.autoDelta` (= 2). Result: A0 over-increments by 1 after `BSET #n,(A0)+`.

Note the BSET #n,(A0)+ case currently hits the illegal path? NO — `BitOpDecodeSpec:150` asserts it stays illegal, but that assert is STALE (predec/postinc EAs are now MEMSIMPLE, not MEMCOMPLEX — see `EaDecoder.scala:63-70`). The bit-op crack ALREADY produces 3 µops for `(An)+`; the bug is only the delta value (2 instead of 1). Verify this empirically in Task 1.

## Fix design

In `MicroOpAssembler`, after `bitOpIsMem`/`bitOpSize` are defined (~line 207), add a BYTE-resolved auto-delta for the bit-op source EA:

```scala
// Memory bit-ops are BYTE-sized, but their opword has no size field, so
// OperationDecoder left spec.size at WORD -> EaDecoder computed autoDelta=2.
// A BYTE (An)+/-(An) must adjust An by 1 (A7-byte -> 2 to keep SP even). Recompute
// the source-EA auto-delta at BYTE for a memory bit-op; non-bit-ops keep srcEa.autoDelta.
val srcIsA7        = srcEa.base === U(15, 5 bits)            // A7 = base 8+7
val bitOpByteDelta = Mux(srcIsA7, U(2, 3 bits), U(1, 3 bits))
val srcEaDelta     = Mux(bitOpIsMem, bitOpByteDelta, srcEa.autoDelta)
```

Then replace the three bit-op-affected `eaDelta` sources:
- `ldUop.eaDelta := srcEa.autoDelta` → `ldUop.eaDelta := srcEaDelta`  (line ~490)
- `rmwStUop.eaDelta := srcEa.autoDelta` → `rmwStUop.eaDelta := srcEaDelta` (line ~581)
- `val srcDelta32 = srcEa.autoDelta.resize(32).asSInt` → `srcEaDelta.resize(32).asSInt` (line ~1561, the `anUpdUop` source-An update)

`stUop.eaDelta` (line ~537) uses `dstEa.autoDelta` and is the MOVE-store path only (never a BITOP — `crackStore`/`crackMemMem` are MOVE-only), so it is LEFT UNCHANGED.

This is decode/crack-only (a 3-bit mux in the assembler comb cone). No datapath/timing-critical change → almost certainly FMax-neutral.

---

### Task 1: Empirically confirm the bug + current crack shape (no code change yet)

**Files:**
- Read-only: `src/main/scala/m68k040/decode/MicroOpAssembler.scala`, `src/test/scala/m68k040/decode/BitOpDecodeSpec.scala`

- [ ] **Step 1: Add a temporary diagnostic assert to BitOpDecodeSpec** to print the actual crack for `BSET #n,(A0)+` (op 0x08D8, ext 0x0001, len 2). In a scratch test, print `dut.count`, `dut.uop0.memOp`, `dut.uop0.eaAuto`, `dut.uop0.eaDelta`, `dut.uop2.eaDelta`, `dut.uop0.unimplemented`.

```scala
test("DIAG BSET #n,(A0)+ current crack", VerilatorTest) {
  run { dut => drive(dut, 0x08D8, 0x0001, len = 2); sleep(1)
    println(s"count=${dut.count.toInt} uop0.memOp=${dut.uop0.memOp.toEnum} " +
      s"uop0.eaAuto=${dut.uop0.eaAuto.toEnum} uop0.eaDelta=${dut.uop0.eaDelta.toInt} " +
      s"uop2.eaDelta=${dut.uop2.eaDelta.toInt} unimpl=${dut.uop0.unimplemented.toBoolean}")
  }
}
```

- [ ] **Step 2: Run it**

Run: `JAVA_OPTS=-Xmx10g timeout 1100 ~/sbt/bin/sbt 'testOnly m68k040.decode.BitOpDecodeSpec -- -z "DIAG"' > /home/qwertyoruiop/tmp/diag.log 2>&1; tail -40 /home/qwertyoruiop/tmp/diag.log`

Expected: NOT unimplemented; `count=3`, `uop0.memOp=LOAD`, `uop0.eaAuto=POSTINC`, `uop0.eaDelta=2` (the BUG), `uop2.eaDelta=2`. This confirms the crack is produced and the delta is the wrong 2.

- [ ] **Step 3: Remove the diagnostic test** (it was a probe, not a kept test).

- [ ] **Step 4: Commit nothing** (no code change in this task — it is investigation only; do not commit).

---

### Task 2: Fix the RTL (BYTE eaDelta for memory bit-ops)

**Files:**
- Modify: `src/main/scala/m68k040/decode/MicroOpAssembler.scala` (near line 207, and lines ~490, ~581, ~1561)

- [ ] **Step 1: Add the BYTE-resolved delta** right after the `bitOpSize` definition (search for `val bitOpSize   = Mux(bitOpIsMem, Size.BYTE, Size.LONG)`):

```scala
    // Memory bit-ops are BYTE-sized, but the bit-op opword has NO size field (the size
    // bits encode the bit-op TYPE), so OperationDecoder left spec.size at the WORD
    // default -> EaDecoder computed autoDelta=2 for an (An)+/-(An) bit-op EA. A BYTE
    // (An)+/-(An) must adjust An by 1 (A7-byte -> 2 to keep the stack pointer even).
    // Recompute the source-EA auto-delta at BYTE for a memory bit-op; non-bit-ops keep
    // srcEa.autoDelta (their spec.size is correct). stUop uses dstEa.autoDelta (MOVE-only,
    // never a bit-op) so it is unaffected.
    val srcIsA7        = srcEa.base === U(15, 5 bits)          // A7 = base 8+7
    val bitOpByteDelta = Mux(srcIsA7, U(2, 3 bits), U(1, 3 bits))
    val srcEaDelta     = Mux(bitOpIsMem, bitOpByteDelta, srcEa.autoDelta)
```

- [ ] **Step 2: Use it on the load µop.** Replace `ldUop.eaAuto        := srcEa.autoMode; ldUop.eaDelta := srcEa.autoDelta` with `ldUop.eaAuto        := srcEa.autoMode; ldUop.eaDelta := srcEaDelta`.

- [ ] **Step 3: Use it on the RMW store µop.** Replace `rmwStUop.eaAuto        := srcEa.autoMode; rmwStUop.eaDelta := srcEa.autoDelta` with `rmwStUop.eaAuto        := srcEa.autoMode; rmwStUop.eaDelta := srcEaDelta`.

- [ ] **Step 4: Use it on the source-An-update ADD µop.** Replace `val srcDelta32 = srcEa.autoDelta.resize(32).asSInt` with `val srcDelta32 = srcEaDelta.resize(32).asSInt`.

- [ ] **Step 5: Re-run the Task-1 diagnostic mentally / via Task 4** — defer verification to the test tasks.

- [ ] **Step 6: Commit (Item 1 RTL fix)**

```bash
git add src/main/scala/m68k040/decode/MicroOpAssembler.scala
git commit -m "$(cat <<'EOF'
fix(decode): memory bit-op (An)+/-(An) uses BYTE eaDelta (1, A7->2), not WORD 2

A memory BTST/BSET/BCLR/BCHG is BYTE-sized, but the bit-op opword has no size
field (the size bits encode the bit-op type), so OperationDecoder left
OpSpec.size at the WORD default. EaDecoder then computed autoDelta=2 for an
(An)+/-(An) bit-op EA, so BSET #n,(A0)+ over-incremented A0 by 1 (and -(A0)
over-decremented). Recompute the source-EA auto-delta at BYTE for a memory
bit-op crack (load / RMW-store / source-An-update ADD), honoring the A7-byte->2
even-SP rule. Non-bit-op deltas and the MOVE-store (dstEa) path are unchanged.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: Item 3 — fix the stale BitOpDecodeSpec:150 assert (assert eaDelta=1)

**Files:**
- Modify: `src/test/scala/m68k040/decode/BitOpDecodeSpec.scala` (the `BSET #n,(A0)+ ... -> illegal` test near line 148-155)

- [ ] **Step 1: Replace the stale illegal test** with the correct post-fix crack assertion. The current test:

```scala
  // ── BSET #n,(A0)+ post-inc -> deferred (MEMCOMPLEX), illegal ───────────────────
  // static BSET = 0000 1000 11 mmmrrr; (A0)+ = mode3 reg0 -> 0000 1000 11 011 000 = 0x08D8.
  test("BSET #n,(A0)+ (post-inc) -> illegal (MEMCOMPLEX deferred)", VerilatorTest) {
    run { dut => drive(dut, 0x08D8, 0x0001, len = 2); sleep(1)
      assert(dut.uop0.unimplemented.toBoolean, "post-inc bit-op mem-dest stays illegal")
    }
  }
```

Replace with (BSET #n,(A0)+ now cracks to load.B->T0 / BITOP->T1 / store.B T1->(A0)+, with the An post-update folded onto the store, eaDelta=1 because mem bit-ops are BYTE):

```scala
  // ── BSET #n,(A0)+ post-inc -> mem-RMW crack, BYTE eaDelta=1 (NOT illegal) ───────
  // static BSET = 0000 1000 11 mmmrrr; (A0)+ = mode3 reg0 -> 0000 1000 11 011 000 = 0x08D8.
  // Memory bit-ops are BYTE-sized, so the (A0)+ post-increment adjusts A0 by 1 (eaDelta=1),
  // NOT 2. The load + RMW store both carry POSTINC/eaDelta=1; the store folds the A0 write.
  test("BSET #n,(A0)+ (post-inc) -> RMW crack, BYTE eaDelta=1 on (A0)+", VerilatorTest) {
    run { dut => drive(dut, 0x08D8, 0x0001, len = 2); sleep(1)
      assert(!dut.uop0.unimplemented.toBoolean, "post-inc mem bit-op now cracks")
      assert(dut.count.toInt == 3, "load + BITOP + store")
      assert(dut.uop0.memOp.toEnum == MemOp.LOAD && dut.uop0.size.toEnum == Size.BYTE, "load.B")
      assert(dut.uop0.srcAReg.toInt == 8, "base = A0")
      assert(dut.uop0.eaAuto.toEnum == EaAuto.POSTINC, "load carries POSTINC")
      assert(dut.uop0.eaDelta.toInt == 1, "BYTE (A0)+ -> eaDelta = 1, NOT 2")
      assert(dut.uop1.op.toEnum == DecOp.BITOP && dut.uop1.bitOp.toInt == 3 && dut.uop1.size.toEnum == Size.BYTE, "BITOP BSET byte")
      assert(dut.uop2.memOp.toEnum == MemOp.STORE && dut.uop2.size.toEnum == Size.BYTE, "store.B")
      assert(dut.uop2.eaAuto.toEnum == EaAuto.POSTINC && dut.uop2.eaDelta.toInt == 1, "store carries POSTINC eaDelta=1")
      assert(dut.uop2.dstReg.toInt == 8 && dut.uop2.dstValid.toBoolean, "store folds the A0 post-update write")
    }
  }
```

- [ ] **Step 2: Add the EaAuto import** if not present. Check the import line `import m68k040.isa.{Cluster, MemOp, Size}` — `EaAuto` is in `m68k040.decode` (the spec's own package), so it is already in scope (no import needed; the spec is `package m68k040.decode`). Confirm by compiling in Step 3.

- [ ] **Step 3: Run BitOpDecodeSpec**

Run: `JAVA_OPTS=-Xmx10g timeout 1100 ~/sbt/bin/sbt 'testOnly m68k040.decode.BitOpDecodeSpec' > /home/qwertyoruiop/tmp/bitopdecode.log 2>&1; tail -30 /home/qwertyoruiop/tmp/bitopdecode.log`
Expected: all tests PASS (including the rewritten one).

- [ ] **Step 4: Commit (Item 3)**

```bash
git add src/test/scala/m68k040/decode/BitOpDecodeSpec.scala
git commit -m "$(cat <<'EOF'
test(decode): assert BSET #n,(A0)+ cracks with BYTE eaDelta=1 (was stale 'illegal')

The (An)+ bit-op EA is now a MEMSIMPLE auto-update (not MEMCOMPLEX), so BSET
#n,(A0)+ cracks to load.B/BITOP/store.B with the A0 post-update folded on the
store. After the Item-1 fix the eaDelta is BYTE (1), so assert that exact crack
shape rather than the stale '-> illegal'.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: Item 1 validation — bit-op postinc/predec lock-step vs Musashi

**Files:**
- Modify: `src/test/scala/m68k040/lockstep/ExecuteLockStepSpec.scala` (add after the existing mem bit-op tests, ~line 1930, before the "flag preservation" block)

The lock-step harness compares the FULL register stream (incl. A0/A7), flags, PC, and `checkMem` bytes against Musashi at every commit. To prove the An post-update, the program does the bit-op then `move.l %a0,%dN` so A0's resolved value is in the compared register stream. These tests would FAIL on the buggy delta=2 (A0 ends at base+2 instead of base+1) and PASS after the fix.

- [ ] **Step 1: Add the new lock-step tests** right after the `bit-mem-dyn-bset` test (line ~1930):

```scala
  // ── memory bit-op with (An)+/-(An): BYTE access -> An adjusts by 1 (the eaDelta fix) ──
  // The bit-op RESULT in memory AND the An post-update lock-step vs Musashi (full reg
  // stream + checkMem). A BYTE (An)+ must increment An by 1; pre-fix it over-incremented
  // by 2. `move.l %a0,%d7` lands A0's resolved value in the compared register stream.
  test("lock-step: BSET #n,(A0)+ post-inc -> mem RMW + A0 += 1 (BYTE)", VerilatorTest) {
    runLockStep("bit-mem-bset-postinc",
      "move.l #0x11223300,%d0 ; move.l #0x3000,%a0 ; move.l %d0,(%a0) ; " +
      "bset #0,(%a0)+ ; move.l %a0,%d7 ; " +                  // mem[0x3000] bit0 set; A0 -> 0x3001
      ".stop: bra .stop", nInstr = 5, checkMem = Seq(0x3000L), checkSpan = 4)
  }
  test("lock-step: BCLR #7,(A0)+ post-inc -> mem RMW + A0 += 1 (BYTE)", VerilatorTest) {
    runLockStep("bit-mem-bclr-postinc",
      "move.l #0x112233ff,%d0 ; move.l #0x3000,%a0 ; move.l %d0,(%a0) ; " +
      "bclr #7,(%a0)+ ; move.l %a0,%d7 ; " +                  // byte 0xff bit7 clear -> 0x7f; A0 -> 0x3001
      ".stop: bra .stop", nInstr = 5, checkMem = Seq(0x3000L), checkSpan = 4)
  }
  test("lock-step: BCHG #1,(A0)+ post-inc -> mem RMW + A0 += 1 (BYTE)", VerilatorTest) {
    runLockStep("bit-mem-bchg-postinc",
      "move.l #0x11223355,%d0 ; move.l #0x3000,%a0 ; move.l %d0,(%a0) ; " +
      "bchg #1,(%a0)+ ; move.l %a0,%d7 ; " +                  // byte 0x55 bit1 toggle -> 0x57; A0 -> 0x3001
      ".stop: bra .stop", nInstr = 5, checkMem = Seq(0x3000L), checkSpan = 4)
  }
  test("lock-step: BSET #n,-(A0) pre-dec -> mem RMW + A0 -= 1 (BYTE)", VerilatorTest) {
    runLockStep("bit-mem-bset-predec",
      "move.l #0x11223300,%d0 ; move.l #0x3000,%a0 ; move.l %d0,(%a0) ; " +
      "move.l #0x3001,%a0 ; bset #0,-(%a0) ; move.l %a0,%d7 ; " +  // -(A0): A0 0x3001 -> 0x3000, byte bit0 set
      ".stop: bra .stop", nInstr = 6, checkMem = Seq(0x3000L), checkSpan = 4)
  }
  test("lock-step: BSET %d1,(A0)+ dynamic post-inc -> mem RMW + A0 += 1 (BYTE)", VerilatorTest) {
    runLockStep("bit-mem-dyn-bset-postinc",
      "moveq #3,%d1 ; move.l #0x11223300,%d0 ; move.l #0x3000,%a0 ; move.l %d0,(%a0) ; " +
      "bset %d1,(%a0)+ ; move.l %a0,%d7 ; " +                 // byte bit3 set -> 0x08; A0 -> 0x3001
      ".stop: bra .stop", nInstr = 6, checkMem = Seq(0x3000L), checkSpan = 4)
  }
  // A7-byte special case: a BYTE (A7)+ adjusts A7 by 2 (keep SP even). Seed via a scratch
  // pointer, bit-op on (A7)+, then read A7 -> Musashi applies the +2 rule too.
  test("lock-step: BSET #n,(A7)+ -> mem RMW + A7 += 2 (A7-byte even rule)", VerilatorTest) {
    runLockStep("bit-mem-bset-a7-postinc",
      "move.l #0x11223300,%d0 ; move.l #0x3000,%a1 ; move.l %d0,(%a1) ; " +
      "move.l #0x3000,%a7 ; bset #0,(%a7)+ ; move.l %a7,%d7 ; " +  // A7 0x3000 -> 0x3002 (byte->2)
      ".stop: bra .stop", nInstr = 6, checkMem = Seq(0x3000L), checkSpan = 4)
  }
```

- [ ] **Step 2: Run the new lock-step subset**

Run: `JAVA_OPTS=-Xmx10g timeout 1100 ~/sbt/bin/sbt 'testOnly m68k040.lockstep.ExecuteLockStepSpec -- -z "postinc" -z "predec" -z "a7"' > /home/qwertyoruiop/tmp/ls-postinc.log 2>&1; tail -40 /home/qwertyoruiop/tmp/ls-postinc.log`
Expected: all new tests PASS, "0 diverged". If a test fails on the A7 case because the assembler rejects `(%a7)+` syntax, replace `%a7` with `%sp` (GNU as alias) and re-run; if Musashi cannot express it at all, keep the note in the report but the non-A7 cases remain the core proof.

- [ ] **Step 3: Run a non-postinc bit-op + a non-bit-op postinc regression subset** (confirm the delta change didn't break the existing mem bit-ops or the non-bit-op autoDelta):

Run: `JAVA_OPTS=-Xmx10g timeout 1100 ~/sbt/bin/sbt 'testOnly m68k040.lockstep.ExecuteLockStepSpec -- -z "bit-mem" -z "ABCD" -z "predec" -z "movem"' > /home/qwertyoruiop/tmp/ls-regress.log 2>&1; tail -40 /home/qwertyoruiop/tmp/ls-regress.log`
Expected: PASS (existing `bit-mem-*` non-postinc + the predec-ABCD/SBCD non-bit-op autoDelta tests unaffected).

- [ ] **Step 4: Commit (Item 1 validation)**

```bash
git add src/test/scala/m68k040/lockstep/ExecuteLockStepSpec.scala
git commit -m "$(cat <<'EOF'
test(lockstep): bit-op (An)+/-(An) BYTE eaDelta vs Musashi (A0 +=1/-=1, A7 +=2)

New lock-step proof for the Item-1 fix: BSET/BCLR/BCHG #n,(A0)+ and BSET #n,-(A0)
(+ dynamic BSET Dn,(A0)+) assert the bit-op result in memory AND the An
post-update against Musashi (full reg stream + checkMem, 0 diverged). Includes
the A7-byte case (BSET #n,(A7)+ -> A7 += 2). These FAIL on the buggy eaDelta=2.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: Item 2 — fix the stale CrackStoreSpec:78 mem-to-mem MOVE assert

**Files:**
- Modify: `src/test/scala/m68k040/decode/CrackStoreSpec.scala` (the `MOVE.L (A1),(A0) mem-to-mem stays unimplemented` test, ~line 78-83)

mem-to-mem MOVE is implemented (`crackMemMem`, `MicroOpAssembler.scala:240`): cracks into `[load src -> T0][store T0 -> dst]` (count 2). The op is `MOVE.L (A1),(A0)` = 0x2091: src EA (A1) = mode2 reg1 → load base A1 (reg 9); dst EA (A0) = mode2 reg0 → store base A0 (reg 8); store data = T0.

- [ ] **Step 1: Confirm the actual crack first** with a temporary diagnostic (same DUT exposes uop0/uop1/count). Add to CrackStoreSpec a scratch print, run `-z "DIAG-memmem"`, observe, then remove. Expected: count=2, uop0=LOAD base A1->T0, uop1=STORE srcB=T0 base A0. (Read the test file's `Dut`/`drive`/`run` helpers to mirror them; they parallel BitOpDecodeSpec.)

- [ ] **Step 2: Replace the stale test** (current):

```scala
  test("MOVE.L (A1),(A0) mem-to-mem stays unimplemented", VerilatorTest) {
    // MOVE.L (A1),(A0): src EA = (A1) memSimple, dst EA = (A0) memSimple.
    // dstMode=010 dstReg=000, src=(A1) = 010 001. opword = 0010 000 010 010 001 = 0x2091
    run { dut => drive(dut, 0x2091); sleep(1)
      assert(dut.uop0.unimplemented.toBoolean, "two-memory MOVE must stay unimplemented")
    }
  }
```

Replace with (verify the field names/temp constant against the actual values printed in Step 1; use `T0` if the spec already defines it like BitOpDecodeSpec, else `MicroOpAssembler.T0`):

```scala
  test("MOVE.L (A1),(A0) mem-to-mem cracks to load(A1)->T0 + store T0->(A0)", VerilatorTest) {
    // MOVE.L (A1),(A0): src EA = (A1) memSimple, dst EA = (A0) memSimple.
    // dstMode=010 dstReg=000, src=(A1) = 010 001. opword = 0010 000 010 010 001 = 0x2091
    // Now implemented (crackMemMem): [load.L (A1) -> T0][store.L T0 -> (A0)], count 2.
    run { dut => drive(dut, 0x2091); sleep(1)
      assert(!dut.uop0.unimplemented.toBoolean, "mem-to-mem MOVE now cracks")
      assert(dut.count.toInt == 2, "load + store")
      assert(dut.uop0.memOp.toEnum == MemOp.LOAD && dut.uop0.size.toEnum == Size.LONG, "uop0 = load.L")
      assert(dut.uop0.srcAReg.toInt == 9 && dut.uop0.srcAValid.toBoolean, "load base = A1 (reg 9)")
      assert(dut.uop0.dstReg.toInt == MicroOpAssembler.T0, "load -> T0")
      assert(dut.uop1.memOp.toEnum == MemOp.STORE && dut.uop1.size.toEnum == Size.LONG, "uop1 = store.L")
      assert(dut.uop1.srcAReg.toInt == 8 && dut.uop1.srcAValid.toBoolean, "store base = A0 (reg 8)")
      assert(dut.uop1.srcBReg.toInt == MicroOpAssembler.T0 && dut.uop1.srcBValid.toBoolean, "store data = T0")
    }
  }
```

Adjust the exact constants (T0 reference, size enum import) to match what Step 1 printed and what CrackStoreSpec already imports.

- [ ] **Step 3: Run CrackStoreSpec**

Run: `JAVA_OPTS=-Xmx10g timeout 1100 ~/sbt/bin/sbt 'testOnly m68k040.decode.CrackStoreSpec' > /home/qwertyoruiop/tmp/crackstore.log 2>&1; tail -30 /home/qwertyoruiop/tmp/crackstore.log`
Expected: all PASS.

- [ ] **Step 4: Commit (Item 2)**

```bash
git add src/test/scala/m68k040/decode/CrackStoreSpec.scala
git commit -m "$(cat <<'EOF'
test(decode): assert MOVE.L (A1),(A0) mem-to-mem crack (was stale 'unimplemented')

mem-to-mem MOVE is implemented (crackMemMem: load src -> T0, store T0 -> dst).
Assert the actual 2-uop crack (load.L A1->T0, store.L T0->A0) instead of the
stale 'stays unimplemented'.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: Full regression gate (parity + fastTest + decode specs)

**Files:** none (test execution only)

- [ ] **Step 1: OperationDecoderSpec**

Run: `JAVA_OPTS=-Xmx10g timeout 1100 ~/sbt/bin/sbt 'testOnly m68k040.decode.OperationDecoderSpec' > /home/qwertyoruiop/tmp/opdec.log 2>&1; tail -20 /home/qwertyoruiop/tmp/opdec.log`
Expected: PASS (BITOP classification unchanged — we only touched the assembler crack).

- [ ] **Step 2: PredecodeWordSpec (65536-parity)**

Run: `JAVA_OPTS=-Xmx10g timeout 1100 ~/sbt/bin/sbt 'testOnly m68k040.frontend.PredecodeWordSpec' > /home/qwertyoruiop/tmp/predec.log 2>&1; tail -20 /home/qwertyoruiop/tmp/predec.log`
(Find the exact class path with `grep -rl "class PredecodeWordSpec" src/test` if the package differs.)
Expected: PASS (predecode framing unchanged).

- [ ] **Step 3: fastTest (93/93 no regression)**

Run: `JAVA_OPTS=-Xmx10g timeout 1100 ~/sbt/bin/sbt fastTest > /home/qwertyoruiop/tmp/fasttest.log 2>&1; tail -30 /home/qwertyoruiop/tmp/fasttest.log`
Expected: all pass, no regression vs the documented 93/93 baseline. (If fastTest is an sbt alias, confirm via `grep -rn "fastTest" build.sbt`.)

- [ ] **Step 4: No commit** (test runs only). Record pass counts for the report.

---

### Task 7: FMax OOC gate decision

**Files:** none

- [ ] **Step 1: Decide.** The change is a 3-bit mux in the decode/assembler comb cone (`srcEaDelta`), feeding the eaDelta field that already existed. No datapath, no new registers, no critical-path structure. Per the campaign rules, a decode-only / FMax-irrelevant change MAY skip the gate WITH a note.

- [ ] **Step 2: Check for a running Vivado FIRST** (never run two): `pgrep -af vivado`. If a foreign impl-pipe or the parallel 250-FMax agent holds Vivado, do NOT start a second — note it and skip.

- [ ] **Step 3:** If no Vivado is running AND there is any doubt, run the standard full-core OOC synth (≥200 MHz gate) per the repo's synth flow; else document "decode-only, FMax-neutral, gate skipped (Vivado busy / change is a 3-bit decode mux off the critical path)". Record the decision + reason for the report.

---

## Self-review

- **Spec coverage:** Item 1 RTL fix = Task 2; Item 1 lock-step proof (A0 +=1/-=1, dynamic, A7) = Task 4; Item 2 (CrackStoreSpec) = Task 5; Item 3 (BitOpDecodeSpec eaDelta=1) = Task 3; parity/fastTest/OperationDecoder/regression = Task 6; gate decision = Task 7. All covered.
- **Hard rule (no fake-pass):** every replaced assert states the CORRECT now-implemented behavior, verified against the actual µops (Task-1/Task-5 diagnostics) and Musashi (Task 4). The delta is FIXED in RTL (Task 2) before asserting eaDelta=1 — never asserting the buggy 2.
- **Placeholder scan:** all code blocks are concrete; constants (0x08D8, 0x2091, reg ids 8/9, T0) derived from the encodings.
- **Type consistency:** `srcEaDelta` is `UInt(3 bits)` (matches `eaDelta`/`autoDelta` width); `srcDelta32` keeps `.resize(32).asSInt`; `MicroOpAssembler.T0` is the temp constant the decode specs already use.
