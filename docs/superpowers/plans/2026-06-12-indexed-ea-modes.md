# Indexed Addressing Modes (brief format) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the 68k brief-format indexed addressing modes `(d8,An,Xn*scale)` (EA mode 6) and `(d8,PC,Xn*scale)` (EA mode 7/3) on the m68k 68040 OoO core, composing with every op that takes a memory-EA source/dest (MOVE, ALU, RMW, etc.).

**Architecture:** This is TRACK A of the ISA-completion architecture (`docs/superpowers/specs/2026-06-12-isa-completion-microcode-architecture-design.md`, SHA f1bf645). Indexed addressing is an **EA-decode extension**, not a new opcode: `EaDecoder` parses the brief extension word and classifies modes 6/7-3 as `MEMSIMPLE` (no longer `MEMCOMPLEX`), carrying an index descriptor `{indexReg, indexLong, scale}`. The existing load/store/RMW cracks in `MicroOpAssembler` thread the index register into a **third source operand** (`srcCReg`/`psrcC` — already plumbed through rename + IQ wakeup for DIV.L). The `LsEuPlugin` AGU gains a third int read port and computes `va = base + disp + (Xn sized) << scale`. PC-rel base = `pc+2` (the address of the extension word — VERIFIED vs Musashi: `lea (0,%pc,%d1.w),%a2` with d1=2 at PC=0x40800002 yields A2=0x40800006 = 0x40800004+0+2). Brief format adds exactly 1 extension word → predecode framing updated.

**Tech Stack:** SpinalHDL (Scala), Verilator unit specs + Musashi/MAME lock-step (`m68k-linux-gnu-as -m68040` assembles brief format natively), Vivado OOC synth gate (≥200 MHz).

**Scope (brief format ONLY):** Brief extension word `D/A(1) | Xn(3) | W/L(1) | scale(2) | 0(1=brief) | d8(8)`. Index Xn = D0-D7 (D/A=0) / A0-A7 (D/A=1); index size .W (sign-extend low 16) or .L (full 32); scale `*1/2/4/8`; signed 8-bit displacement d8. `EA = base(An or pc+2) + sext(d8) + (Xn sized) << scale`. The FULL extension format (`bit8=1`: memory-indirect / base-outer-disp / suppressed base/index) is Track B (µcode) and OUT OF SCOPE — `bit8=1` stays `MEMCOMPLEX`/illegal.

**Out of lane (do NOT touch):** line-4 LEA/PEA/MOVE-SR (a later serialized Track-A slice), the µcode path, MOVEM-indexed (Track B). No `OperationDecoder` edit is needed — it is EA-class-agnostic (emits EASRC/EADST roles; the EaDecoder/assembler resolve the class). MOVEM's predecode `mmExt` table is left rejecting indexed (out of scope).

---

## Background facts (verified during planning)

- **`psrcC` is already fully threaded**: `DecodedUop.srcCReg/srcCValid` → `RenameStage` (reads(6+s), slot1 same-cycle bypass at line 220) → `RenamedUop.psrcC/psrcCValid` → IQ wakeup for **every** cluster incl. LS (`IssueQueuePlugin.scala:571` LS wakeup, :392/:418 dependency, :430 intra-bundle). DIV.L 64-bit is the only current user. So routing the index reg to `srcCReg` requires NO rename/IQ changes — only an LS-EU read port + AGU wiring.
- **The int regfile uses dynamic `newRead()`** (`RegFilePluginInt`), so adding a 3rd LS read port (`rdIndex = irf.newRead()`) is supported (ports lower as needed). AluEu uses 2 reads, DivEu 3, LsEu currently 2.
- **The AGU is split S0/S1** for FMax: `s1Base` is the REGISTERED (post-bypass) psrcA; `s1Va = s1Base + s1Disp` is a shallow adder in S1 off the flop. The index term must be added in S1 WITHOUT re-chaining the ALU→base cone. The index operand (psrcC) is read in S0 (`rdIndex.data`) and REGISTERED into `s1Index` at the S0→S1 boundary (mirrors `s1Base`/`s1Data`), so the AGU adder stays a shallow `s1Base + s1Disp + s1IndexScaled` off flops.
- **Existing `(d16,PC)` crack** folds `pc+2+disp` into the load `imm` with `baseValid=False` (`MicroOpAssembler.scala:461` pcRelAddr). For `(d8,PC,Xn)` the same `pc+2+d8` fold applies; the index is still a runtime register, so `baseValid=False` + `imm=pc+2+d8` + `srcCReg=Xn`.
- **The assembler re-decodes the EA for line-0 immediate / DIV.L / MUL.L** from a shifted words vector. For indexed those re-decodes also produce the index descriptor (the brief word is the EA's own 1 extension word, at the same offset the existing disp/abs come from). No special handling: the indexed descriptor rides `srcEa`/`immEa`/`divlSrcEa`/`mullSrcEa` like `disp`.
- **DIV.L / MUL.L with a memSimple divisor/multiplier are currently deferred** (`divLOk`/`mulLOk` require reg/imm). Indexed is a memSimple sub-case → it stays deferred there too (no new work; the `bad`/illegal path already covers it). NOT in the lock-step matrix.

---

## File Structure

- `src/main/scala/m68k040/decode/DecodeContracts.scala` — extend `EaSpec` with the index descriptor fields (`indexValid`, `indexReg`, `indexLong`, `indexScale`); update `EaSpec.illegalDefault()`.
- `src/main/scala/m68k040/decode/EaDecoder.scala` — parse the brief extension word for modes 6 / 7-3; classify as `MEMSIMPLE` with the index descriptor + base/disp; keep `bit8=1` (full format) as `MEMCOMPLEX`.
- `src/main/scala/m68k040/decode/DecodedUop.scala` — the `srcCReg`/`srcCValid` field already exists (DIV.L). Add `indexLong` + `indexScale` µop fields (the AGU needs the size/scale of the index operand). `srcCReg` carries the index reg id.
- `src/main/scala/m68k040/rename/RenamedUop.scala` — thread `indexLong` + `indexScale` (psrcC already threaded).
- `src/main/scala/m68k040/rename/RenameStage.scala` — copy `indexLong`/`indexScale` decode→renamed (psrcC mapping already wired).
- `src/main/scala/m68k040/decode/MicroOpAssembler.scala` — fold the index source into the load/store/RMW cracks: set `srcCReg=indexReg`, `srcCValid=indexValid`, `indexLong`/`indexScale` on `ldUop`/`stUop`/`rmwStUop`; widen `dstEaOk`/gating so an indexed MEMSIMPLE EA is in scope; default the new fields in every other µop builder (mkUop, movem*, div*, mul*, ibr) to avoid SpinalHDL assignment-overlap.
- `src/main/scala/m68k040/execute/LsEuPlugin.scala` — add `rdIndex = irf.newRead()` (psrcC); register `s1Index`; add the scaled-index term to `s1Va` and to `s1AddrB`/cross detection (so an indexed access that crosses a line/page is handled).
- `src/test/scala/m68k040/decode/EaDecoderSpec.scala` — unit tests for the brief-format parse (modes 6 / 7-3, .W/.L, scale, negative d8, full-format rejection).
- `src/test/scala/m68k040/frontend/PredecodeWordSpec.scala` — length-framing tests for indexed EAs.
- `src/test/scala/m68k040/lockstep/ExecuteLockStepSpec.scala` — the indexed lock-step matrix.
- `src/main/scala/m68k040/frontend/PredecodeWord.scala` — `eaExt` + `memDestExt`: modes 6 / 7-3 brief → 1 ext word (was `ok=False`); full format (`bit8=1`) stays complex. NOTE: predecode sees only the OPWORD, not the extension word, so it CANNOT distinguish brief vs full at framing time. RESOLUTION below (Task 6).

---

## Task 1: EaSpec index descriptor fields

**Files:**
- Modify: `src/main/scala/m68k040/decode/DecodeContracts.scala`
- Test: (compile-only; exercised by Task 2)

- [ ] **Step 1: Add the index descriptor to `EaSpec`**

In `case class EaSpec()`, after the `autoMode`/`autoDelta` fields, add:

```scala
  // Brief-format INDEXED EA (modes 6 / 7-3): EA = base + sext(d8) + (Xn sized) << scale.
  // indexValid => an index register read is needed (Xn). indexReg = the full reg id
  // (Dn=0..7 / An=8..15). indexLong => .L index (full 32); False => .W (sign-extend low
  // 16). indexScale = the 2-bit scale exponent (0=*1,1=*2,2=*4,3=*8). The d8 displacement
  // rides `disp` (base+disp fold as usual); PC-rel (mode 7-3) folds pc+2+d8 (base=0).
  // NONE for every non-indexed EA. Carried on MEMSIMPLE alongside base/disp/autoMode.
  val indexValid = Bool()
  val indexReg   = UInt(5 bits)
  val indexLong  = Bool()
  val indexScale = UInt(2 bits)
```

- [ ] **Step 2: Default them in `EaSpec.illegalDefault()`**

In `object EaSpec.illegalDefault()`, after the `autoMode`/`autoDelta` line, add:

```scala
    e.indexValid := False; e.indexReg := 0; e.indexLong := False; e.indexScale := 0
```

- [ ] **Step 3: Compile**

Run: `cd /home/qwertyoruiop/m68k-core-040-ooo/.claude/worktrees/agent-a14a4a0f9ae7096cb && JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt "compile" 2>&1 | tee /home/qwertyoruiop/tmp/idx-t1-compile.log | tail -20`
Expected: `[success]` (the `EaDecoder.decode` defaults block now also must set these — see Task 2; if compile fails on "not assigned", proceed to Task 2 first then re-run).

- [ ] **Step 4: Commit**

```bash
git add src/main/scala/m68k040/decode/DecodeContracts.scala
git commit -m "ea: EaSpec index descriptor fields (indexValid/Reg/Long/Scale) for brief-format indexed modes"
```

---

## Task 2: EaDecoder brief-format parse (modes 6 / 7-3)

**Files:**
- Modify: `src/main/scala/m68k040/decode/EaDecoder.scala`
- Test: `src/test/scala/m68k040/decode/EaDecoderSpec.scala`

- [ ] **Step 1: Write the failing tests**

Append to `EaDecoderSpec.scala` (before the final closing brace of the class):

```scala
  // ── Brief-format indexed (d8,An,Xn*scale) (mode 6) ────────────────────────────
  // ext word layout: D/A(15) | Xn(14:12) | W/L(11) | scale(10:9) | 0(8 brief) | d8(7:0)
  test("(4,A0,D1.w*2) -> MEMSIMPLE INDEXED base=A0 disp=4 index=D1 .W scale=1(=*2)", VerilatorTest) { run { dut =>
    dut.eaField #= 0x10 /*mode6 reg0=A0*/ + 0x06 - 0x06 // mode6 reg0
    dut.eaField #= 0x30 /*mode6 reg0*/; dut.size #= Size.LONG
    dut.words(1) #= 0x1204 /* D1, .W, scale*2, d8=4 */; sleep(1)
    assert(dut.out0.klass.toEnum == EaClass.MEMSIMPLE)
    assert(dut.out0.baseValid.toBoolean && dut.out0.base.toInt == 8)   // A0
    assert(dut.out0.disp.toLong == 4)
    assert(dut.out0.indexValid.toBoolean && dut.out0.indexReg.toInt == 1)   // D1
    assert(!dut.out0.indexLong.toBoolean && dut.out0.indexScale.toInt == 1) // .W, *2
    assert(!dut.out0.pcRel.toBoolean)
  }}
  test("(−2,A3,A5.l*8) -> base=A3 negative disp, index=A5 .L scale=3(=*8)", VerilatorTest) { run { dut =>
    dut.eaField #= 0x33 /*mode6 reg3=A3*/; dut.size #= Size.LONG
    // D/A=1 (An), Xn=101(A5), W/L=1(.L), scale=11(*8), brief=0, d8=0xFE(-2)
    dut.words(1) #= ((1<<15)|(5<<12)|(1<<11)|(3<<9)|0xFE); sleep(1)
    assert(dut.out0.klass.toEnum == EaClass.MEMSIMPLE)
    assert(dut.out0.base.toInt == 11)            // A3
    assert(dut.out0.disp.toLong == 0xFFFFFFFEL)  // sext(-2)
    assert(dut.out0.indexReg.toInt == 13)        // A5 = 8+5
    assert(dut.out0.indexLong.toBoolean && dut.out0.indexScale.toInt == 3)
  }}
  test("(d8,PC,Xn) mode 7-3 -> MEMSIMPLE INDEXED pcRel base invalid", VerilatorTest) { run { dut =>
    dut.eaField #= 0x3B /*mode7 reg3*/; dut.size #= Size.LONG
    dut.words(1) #= 0x1206 /* D1, .W, *2, d8=6 */; sleep(1)
    assert(dut.out0.klass.toEnum == EaClass.MEMSIMPLE)
    assert(!dut.out0.baseValid.toBoolean && dut.out0.pcRel.toBoolean)
    assert(dut.out0.disp.toLong == 6)
    assert(dut.out0.indexValid.toBoolean && dut.out0.indexReg.toInt == 1)
  }}
  test("full-format (bit8=1) mode 6 stays MEMCOMPLEX (out of scope)", VerilatorTest) { run { dut =>
    dut.eaField #= 0x30; dut.size #= Size.LONG
    dut.words(1) #= 0x1304 /* bit8=1 => full extension format */; sleep(1)
    assert(dut.out0.klass.toEnum == EaClass.MEMCOMPLEX)
  }}
```

- [ ] **Step 2: Run to verify FAIL**

Run: `cd /home/qwertyoruiop/m68k-core-040-ooo/.claude/worktrees/agent-a14a4a0f9ae7096cb && JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt "testOnly m68k040.decode.EaDecoderSpec" 2>&1 | tee /home/qwertyoruiop/tmp/idx-t2-fail.log | tail -25`
Expected: FAIL (the indexed modes still classify as `MEMCOMPLEX`; index fields not set).

- [ ] **Step 3: Implement the brief-format parse**

In `EaDecoder.scala`, in the `defaults` block (after `e.autoDelta := 0`), add:

```scala
    e.indexValid := False
    e.indexReg   := 0
    e.indexLong  := False
    e.indexScale := 0
```

Add a shared brief-format parser helper inside `decode` (before the `switch(mode)`), reading the first extension word `words(1)`:

```scala
    // Brief-format extension word (modes 6 / 7-3): D/A(15) | Xn(14:12) | W/L(11) |
    // scale(10:9) | brief=0(8) | d8(7:0). bit8=1 => FULL format (memory-indirect /
    // base-outer-disp) — OUT OF SCOPE (Track B µcode) -> the caller keeps MEMCOMPLEX.
    val extW       = words(1)
    val briefIsFull = extW(8)                              // bit8=1 => full ext format
    val idxDA      = extW(15)                              // 1 => An, 0 => Dn
    val idxXn      = extW(14 downto 12).asUInt
    val idxReg     = Mux(idxDA, (U(8, 5 bits) + idxXn).resized, idxXn.resize(5))
    val idxLong    = extW(11)                              // 1 => .L, 0 => .W
    val idxScale   = extW(10 downto 9).asUInt
    val idxD8      = extW(7 downto 0).asSInt.resize(32).asBits   // sext(d8)
```

Replace `is(6) { e.klass := EaClass.MEMCOMPLEX }` with:

```scala
      is(6) {                                      // (d8,An,Xn*scale) brief indexed
        when(briefIsFull) { e.klass := EaClass.MEMCOMPLEX }   // full format -> Track B
        .otherwise {
          e.klass := EaClass.MEMSIMPLE; e.baseValid := True
          e.disp  := idxD8
          e.indexValid := True; e.indexReg := idxReg
          e.indexLong  := idxLong; e.indexScale := idxScale
        }
      }
```

Replace `is(3) { e.klass := EaClass.MEMCOMPLEX }` (inside the `mode===7` switch) with:

```scala
          is(3) {                                  // (d8,PC,Xn*scale) brief indexed
            when(briefIsFull) { e.klass := EaClass.MEMCOMPLEX }
            .otherwise {
              e.klass := EaClass.MEMSIMPLE; e.baseValid := False; e.pcRel := True
              e.disp  := idxD8
              e.indexValid := True; e.indexReg := idxReg
              e.indexLong  := idxLong; e.indexScale := idxScale
            }
          }
```

- [ ] **Step 4: Run to verify PASS**

Run: `cd /home/qwertyoruiop/m68k-core-040-ooo/.claude/worktrees/agent-a14a4a0f9ae7096cb && JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt "testOnly m68k040.decode.EaDecoderSpec" 2>&1 | tee /home/qwertyoruiop/tmp/idx-t2-pass.log | tail -25`
Expected: PASS (all EaDecoderSpec tests, incl. the 4 new ones).

- [ ] **Step 5: Commit**

```bash
git add src/main/scala/m68k040/decode/EaDecoder.scala src/test/scala/m68k040/decode/EaDecoderSpec.scala
git commit -m "ea: brief-format indexed parse (modes 6 / 7-3) -> MEMSIMPLE + index descriptor; full-format stays MEMCOMPLEX"
```

---

## Task 3: Thread indexLong/indexScale through DecodedUop + Rename

**Files:**
- Modify: `src/main/scala/m68k040/decode/DecodedUop.scala`
- Modify: `src/main/scala/m68k040/rename/RenamedUop.scala`
- Modify: `src/main/scala/m68k040/rename/RenameStage.scala`
- Test: (compile + exercised by lock-step in Task 7)

- [ ] **Step 1: Add index fields to `DecodedUop`**

In `case class DecodedUop()`, after `firstOfInstr`, add:

```scala
  // ── Brief-format indexed EA (the AGU index term) ────────────────────────────
  // For a mem µop whose address is an indexed EA, the index register rides srcCReg/
  // srcCValid (psrcC after rename), and these two fields tell the LS-EU AGU how to
  // size+scale it: indexLong => use the full 32-bit Xn (else sign-extend Xn[15:0]);
  // indexScale = the 2-bit scale exponent (0=*1,1=*2,2=*4,3=*8). EA = base + disp +
  // (Xn sized) << indexScale. NONE/0 for every non-indexed µop (srcCValid gates it).
  val indexLong  = Bool()
  val indexScale = UInt(2 bits)
```

- [ ] **Step 2: Add to `RenamedUop`**

In `case class RenamedUop()`, after `firstOfInstr`, add:

```scala
  // Brief-format indexed EA: the index reg rides psrcC/psrcCValid; these size+scale it
  // in the LS-EU AGU (indexLong => full 32 vs .W sign-extend; indexScale = *1/2/4/8).
  val indexLong  = Bool()
  val indexScale = UInt(2 bits)
```

- [ ] **Step 3: Wire them in `RenameStage`**

Find where renamed fields are copied from `dec` (near `r.firstOfInstr := dec.firstOfInstr` / the `r.psrcC := ...` block around line 176). Add:

```scala
      r.indexLong  := dec.indexLong
      r.indexScale := dec.indexScale
```

(Place alongside the other scalar field copies for slot `s`.)

- [ ] **Step 4: Compile**

Run: `cd /home/qwertyoruiop/m68k-core-040-ooo/.claude/worktrees/agent-a14a4a0f9ae7096cb && JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt "compile" 2>&1 | tee /home/qwertyoruiop/tmp/idx-t3-compile.log | tail -30`
Expected: FAIL with SpinalHDL "NOT assigned" errors for `indexLong`/`indexScale` in every `DecodedUop` builder in `MicroOpAssembler.scala` (opUop, ldUop, stUop, rmwStUop, mkUop, movemMoveUop, movemAnUpdUop, divlUop, divremUop, mullUop, mulhiUop, ibrUop). This is EXPECTED — Task 4 assigns them. (If you prefer green-at-each-step, do Task 4 Step 3's default-assignments first, then return here.)

- [ ] **Step 5: Commit (after Task 4 compiles green — see note)**

This task's commit is folded into Task 4 (they must compile together). Skip the standalone commit; proceed to Task 4.

---

## Task 4: MicroOpAssembler — fold the index source into the EA cracks

**Files:**
- Modify: `src/main/scala/m68k040/decode/MicroOpAssembler.scala`
- Test: (compile + lock-step in Task 7)

- [ ] **Step 1: Default the new fields in EVERY DecodedUop builder**

SpinalHDL requires every field assigned exactly once. In each builder, add the default `indexLong := False; indexScale := 0` next to the existing `bitOp`/`extByte` default line:

- `movemMoveUop` (after `u.isScc := False; u.isDbcc := False` / near `u.shiftOp...`): `u.indexLong := False; u.indexScale := 0`
- `movemAnUpdUop`: same.
- `opUop` (after `opUop.isScc := False; opUop.isDbcc := False`): `opUop.indexLong := False; opUop.indexScale := 0`
- `ldUop`, `stUop`, `rmwStUop`: same pattern (after their `...isDbcc := False; ...extByte...` line): `<u>.indexLong := False; <u>.indexScale := 0`
- `divlUop`, `divremUop`, `mullUop`, `mulhiUop`, `ibrUop`: same.
- `mkUop` (after `u.isScc := False; u.isDbcc := False; ...`): `u.indexLong := False; u.indexScale := 0`

- [ ] **Step 2: Fold the SOURCE index into `ldUop`**

In `ldUop` (the memSimple-source / RMW load), the address base is `srcEa.base`/`srcEa.baseValid`, disp via `rmwEaDisp`/pcRel fold. Add the index source. Replace `ldUop.srcCReg := 0; ldUop.srcCValid := False` with:

```scala
    // Indexed EA: the index register rides srcC; the AGU sizes+scales it. For a line-0
    // immediate mem-dest the index descriptor lives on immEa (its ext = the brief word,
    // following the imm); otherwise srcEa. base+disp already account for pcRel/disp.
    val ldIdxEa = Mux(opIsLineImm, immEa, srcEa)
    ldUop.srcCReg   := ldIdxEa.indexReg
    ldUop.srcCValid := ldIdxEa.indexValid
    ldUop.indexLong := ldIdxEa.indexLong; ldUop.indexScale := ldIdxEa.indexScale
```

(Remove the old `ldUop.srcCReg := 0; ldUop.srcCValid := False` line.)

- [ ] **Step 3: Fold the DEST index into `stUop` (MOVE reg→mem)**

In `stUop`, the address is `dstEa.base`/disp. Replace `stUop.srcCReg := 0; stUop.srcCValid := False` with:

```scala
    // Indexed MOVE destination: the dest-EA index reg rides srcC (the store DATA is srcB).
    stUop.srcCReg   := dstEa.indexReg
    stUop.srcCValid := dstEa.indexValid
    stUop.indexLong := dstEa.indexLong; stUop.indexScale := dstEa.indexScale
```

NOTE: a mem-to-mem MOVE (`crackMemMem`) reads the load value into T0 (srcB on the store), and the dest index on srcC — both can coexist (srcA=base, srcB=T0 data, srcC=index). The SOURCE index of a mem-to-mem MOVE rides `ldUop` (Step 2). Both EAs indexed compose.

- [ ] **Step 4: Fold the RMW index into `rmwStUop`**

In `rmwStUop` (mem-dest RMW store, EA = `srcEa`), replace `rmwStUop.srcCReg := 0; rmwStUop.srcCValid := False` with:

```scala
    // Indexed RMW: load + store share ONE EA; the index reg rides srcC on BOTH so they
    // compute the SAME indexed address (mirrors how eaAuto is on both). Store data = T1
    // (srcB); base = srcEa.base (srcA); index = srcC.
    val rmwIdxEa = Mux(opIsLineImm, immEa, srcEa)
    rmwStUop.srcCReg   := rmwIdxEa.indexReg
    rmwStUop.srcCValid := rmwIdxEa.indexValid
    rmwStUop.indexLong := rmwIdxEa.indexLong; rmwStUop.indexScale := rmwIdxEa.indexScale
```

- [ ] **Step 5: Verify the gating already admits indexed (no change needed, confirm)**

The `bad` gate uses `srcIsMem`/`dstIsMem` = `klass === MEMSIMPLE`. Since indexed is now MEMSIMPLE, `crackLoad`/`crackStore`/`crackMemMem`/`crackRmw` already fire for it, and `srcEaOk`/`dstOk` already accept MEMSIMPLE. The `aluRmwMemBad`/`lineImmBad`/`eorMemBad`/`bitOpMemBad`/`line4UnaryMemBad`/`addqMemBad` gates all test `=/= MEMSIMPLE` → indexed now PASSES them (in scope). **No gate edit needed.** Confirm by reading the gate block; do NOT add an indexed special-case.

CAVEAT — JMP/JSR/MOVEM/DIV.L/MUL.L: control-EA (`ctrlEaOk = srcIsMem`) would now ACCEPT an indexed JMP/JSR target. On the real 68k, `(d8,An,Xn)` and `(d8,PC,Xn)` ARE valid control/JMP modes — so admitting them is CORRECT, and `ibrUop` already reads `srcEa.base`+`imm` but NOT the index. To avoid a SILENTLY-WRONG indexed JMP/JSR target (index dropped), add an explicit guard: an indexed control EA stays illegal for JMP/JSR in THIS slice (indexed JMP/JSR deferred — the branch-EU AGU has no index port). Add after `val ctrlEaOk = srcIsMem`:

```scala
    // Indexed JMP/JSR targets are deferred (the branch-EU AGU reads no index reg) — an
    // indexed control EA stays illegal here rather than silently dropping the index.
    val ctrlEaIndexed = srcIsMem && srcEa.indexValid
    val ctrlEaOkNoIdx = ctrlEaOk && !ctrlEaIndexed
```

Then change `jmpBad`/`jsrBad` to use `ctrlEaOkNoIdx`:

```scala
    val jmpBad = isJmpOp && !ctrlEaOkNoIdx
    val jsrBad = isJsrOp && !ctrlEaOkNoIdx
```

DIV.L/MUL.L already require reg/imm (`divLOk`/`mulLOk`), so an indexed (memSimple) divisor is already deferred/illegal — no change. MOVEM's FSM uses its own EA path (predecode `mmExt` rejects indexed) — out of scope, no change.

- [ ] **Step 6: Compile (Task 3 + Task 4 together — green)**

Run: `cd /home/qwertyoruiop/m68k-core-040-ooo/.claude/worktrees/agent-a14a4a0f9ae7096cb && JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt "compile" 2>&1 | tee /home/qwertyoruiop/tmp/idx-t4-compile.log | tail -20`
Expected: `[success]`.

- [ ] **Step 7: Commit (Task 3 + Task 4)**

```bash
git add src/main/scala/m68k040/decode/DecodedUop.scala src/main/scala/m68k040/rename/RenamedUop.scala src/main/scala/m68k040/rename/RenameStage.scala src/main/scala/m68k040/decode/MicroOpAssembler.scala
git commit -m "crack: fold the brief-format index source into load/store/RMW cracks (srcC=Xn + indexLong/Scale); defer indexed JMP/JSR"
```

---

## Task 5: LsEuPlugin AGU — add the scaled-index term

**Files:**
- Modify: `src/main/scala/m68k040/execute/LsEuPlugin.scala`
- Test: (lock-step in Task 7)

- [ ] **Step 1: Declare the 3rd int read port**

In the `during setup` block where `rdBase`/`rdData` are allocated (`rdBase = irf.newRead(); rdData = irf.newRead()`), add:

```scala
    rdIndex = irf.newRead()   // brief-format indexed EA: the index register Xn (psrcC)
```

And declare the var alongside `var rdBase, rdData: RegFileReadPort = null`:

```scala
  var rdIndex: RegFileReadPort = null
```

- [ ] **Step 2: Read psrcC in S0 + compute the scaled index**

After `rdData.addr := u0.psrcB`, add:

```scala
    rdIndex.addr := u0.psrcC
    // Index operand (Xn): .W sign-extends the low 16 bits, .L uses the full 32. Then
    // shift-left by the scale exponent (0..3 => *1/2/4/8). Zero when no index (psrcCValid
    // false) so a non-indexed access adds nothing. Read in S0 (off the regfile, possibly
    // bypassed), REGISTERED into s1Index at the S0->S1 boundary so the AGU adder stays a
    // shallow stage off flops (mirrors s1Base) — the index is NOT on the deep ALU->base
    // cone (Xn is an architectural reg read, not an ALU-result bypass into the base).
    val idxRaw0  = Mux(u0.indexLong, rdIndex.data.asUInt,
                       rdIndex.data(15 downto 0).asSInt.resize(32).asUInt)
    val idxTerm0 = Mux(u0.psrcCValid, (idxRaw0 |<< u0.indexScale), U(0, 32 bits))
```

- [ ] **Step 3: Register the index term at the S0→S1 boundary**

Where `s1Base`/`s1Data` are declared as `Reg(...)` and latched, add a parallel `s1Index`:

```scala
    val s1Index = Reg(UInt(32 bits))
```

Find where `s1Base := base0` (the M2S latch, gated by `issuePort.fire`/`issue.ready`), and add alongside it:

```scala
    s1Index := idxTerm0
```

(Match the exact latch condition used for `s1Base`/`s1Data` — they are latched together when a new µop enters S1.)

- [ ] **Step 4: Add the index term to the effective address**

Change `val s1Va = (s1Base.asSInt + s1Disp).asUInt` to add the registered index term:

```scala
    val s1Va = (s1Base.asSInt + s1Disp + s1Index.asSInt).asUInt
```

NOTE on auto-update interaction: indexed modes NEVER carry `eaAuto` (predec/postinc are modes 3/4; indexed is mode 6 / 7-3 — mutually exclusive), and `stkPush` is A7-only. So `s1Disp` (which muxes on stkPush/eaAuto) carries the plain `imm` (= d8 or pc+2+d8) for an indexed µop, and `s1Index` adds the scaled Xn. The `s1AnWb` (auto write-back) path is unused for indexed (no int dst on an indexed load/store unless... see below).

CAVEAT — `s1AnWb`/`s1AnPost`: these compute the An write-back for auto modes off `s1Base` only (no index). Indexed µops set `eaAuto=NONE`, so the An-write path is inert. But CONFIRM the indexed store/RMW does not spuriously write An: an indexed MOVE reg→mem store has `dstValid := dstAuto` (= False for indexed, since `dstEa.autoMode === NONE`), and the indexed RMW store has `dstValid := srcAuto` (False). So no spurious An write. Good — no change needed.

- [ ] **Step 5: Add the index term to the cross-line/page base (`s1AddrB`)**

The cross-detection computes the next-line/page base off `s1Va` (already includes the index after Step 4 — `s1AddrB = (s1Va & ~15) + 16` is DOWNSTREAM of `s1Va`). Read the `s1AddrB` definition: if it is derived from `s1Va`, NO change is needed (it already sees the indexed address). If it is derived from `s1Base + disp` independently, add `+ s1Index.asSInt` there too. CONFIRM by reading the `s1AddrB`/cross block; the planning read shows it derives from `s1Va` (`s1Va & ~15 ... + 16`), so it is already correct.

- [ ] **Step 6: Compile**

Run: `cd /home/qwertyoruiop/m68k-core-040-ooo/.claude/worktrees/agent-a14a4a0f9ae7096cb && JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt "compile" 2>&1 | tee /home/qwertyoruiop/tmp/idx-t5-compile.log | tail -20`
Expected: `[success]`.

- [ ] **Step 7: Commit**

```bash
git add src/main/scala/m68k040/execute/LsEuPlugin.scala
git commit -m "lseu: AGU index term (3rd read port psrcC, .W/.L sext + scale<<, registered s1Index) for brief-format indexed EA"
```

---

## Task 6: Predecode framing for indexed EAs (length = +1 brief ext word)

**Files:**
- Modify: `src/main/scala/m68k040/frontend/PredecodeWord.scala`
- Test: `src/test/scala/m68k040/frontend/PredecodeWordSpec.scala`

**KEY CONSTRAINT:** predecode sees ONLY the opword, NOT the extension word — so it CANNOT distinguish brief (1 ext word) from full (variable). RESOLUTION: frame indexed (modes 6 / 7-3) as SIMPLE with **1** extension word (the brief case). A FULL-format indexed EA would be mis-framed (length wrong), but the assembler classifies a full-format EA (`bit8=1`) as MEMCOMPLEX → `bad` → the op µop becomes an illegal-instruction fault (vector 4). A mis-framed length on an ILLEGAL instruction is harmless for correctness: the illegal op faults at retire and stacks `pc` (the FAULTING pc, not nextPc), and the front-end is redirected by the exception. The ONLY risk is the NEXT sequential fetch being mis-aligned before the fault retires, but the illegal-instruction exception flushes the pipeline at the faulting pc. This matches how every other out-of-scope EA is handled (framed by its in-scope assumption; the assembler illegalises the rest). DOCUMENT this in the code comment. (A precise full-vs-brief length needs the ext word — a Track-B concern when full format is implemented.)

- [ ] **Step 1: Write the failing predecode tests**

Read the existing `PredecodeWordSpec.scala` to match its DUT/assert style, then append (mirroring the existing `lenWords` assertions):

```scala
  test("MOVE.L (d8,A0,Xn) src -> SIMPLE len 2 (opword + brief ext)", VerilatorTest) { run { dut =>
    // move.l (4,%a0,%d1.w*2),%d2  => opword 0x2430 (MOVE.L, dst D2, src mode6 reg0)
    dut.op #= 0x2430; sleep(1)
    assert(dut.r.simple.toBoolean && dut.r.lenWords.toInt == 2)
  }}
  test("MOVE.L (d8,PC,Xn) src (mode 7-3) -> SIMPLE len 2", VerilatorTest) { run { dut =>
    // move.l (6,%pc,%d1.w*2),%d2 => opword 0x243B (src mode7 reg3)
    dut.op #= 0x243B; sleep(1)
    assert(dut.r.simple.toBoolean && dut.r.lenWords.toInt == 2)
  }}
  test("ADD.L %d1,(d8,A0,Xn) RMW dest -> SIMPLE len 2", VerilatorTest) { run { dut =>
    // add.l %d1,(4,%a0,%d2.w) => opword 0xD3B0 (line D, Dn src, opmode6 .L, dest mode6 reg0)
    dut.op #= 0xD3B0; sleep(1)
    assert(dut.r.simple.toBoolean && dut.r.lenWords.toInt == 2)
  }}
```

(Confirm the opwords with `m68k-linux-gnu-as` if unsure — see Task 7 Step 0.)

- [ ] **Step 2: Run to verify FAIL**

Run: `cd /home/qwertyoruiop/m68k-core-040-ooo/.claude/worktrees/agent-a14a4a0f9ae7096cb && JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt "testOnly m68k040.frontend.PredecodeWordSpec" 2>&1 | tee /home/qwertyoruiop/tmp/idx-t6-fail.log | tail -25`
Expected: FAIL (indexed currently `ok=False` → complex → `lenWords=0`).

- [ ] **Step 3: Implement — indexed modes add 1 ext word in `eaExt`**

In `PredecodeWord.scala`, in `def eaExt`, replace `is(U(6, 3 bits)) { ok := False }` with:

```scala
        is(U(6, 3 bits)) {
          // Brief-format indexed (d8,An,Xn): 1 ext word. Predecode cannot see bit8
          // (full vs brief) — frame as brief len 1; a full-format EA is illegalised by
          // the assembler (MEMCOMPLEX -> vector-4 fault), where a mis-framed length is
          // harmless (the illegal op flushes at the faulting pc). Track B handles full.
          ext := U(1, 3 bits)
        }
```

In the `is(U(7, 3 bits))` inner switch, replace `is(U(3, 3 bits)) { ok := False }` with:

```scala
            is(U(3, 3 bits)) { ext := U(1, 3 bits) }   // (d8,PC,Xn) brief indexed: 1 ext word
```

- [ ] **Step 4: Implement — indexed in `memDestExt` (RMW / mem-dest path)**

In `def memDestExt`, add an indexed case. After the `is(U(5, 3 bits))` line, add:

```scala
        is(U(6, 3 bits)) { ok := True; ext := U(1, 3 bits) }   // (d8,An,Xn) brief indexed
```

And in its `is(U(7, 3 bits))` inner switch, add after `is(U(1, 3 bits))`:

```scala
            is(U(3, 3 bits)) { ok := True; ext := U(1, 3 bits) }   // (d8,PC,Xn) brief indexed
```

NOTE: `memDestExt` mode 7-3 is PC-rel which is NOT alterable (read-only) — a mem-DEST RMW into `(d8,PC,Xn)` is ISA-illegal. But framing it len-2 is harmless (the assembler will... actually PC-rel dest: `pcRel`/`baseValid=False` MEMSIMPLE — the RMW store would write PC-space. The 68k forbids it; the assembler's `aluRmwMemBad` only checks `=/= MEMSIMPLE`, so it would NOT reject pcRel). To be SAFE and ISA-correct, do NOT add the mode 7-3 case to `memDestExt` (leave PC-rel RMW dest as complex/illegal). REVISE: only add the mode-6 case to `memDestExt`; OMIT the mode 7-3 line above. (PC-rel is a source-only mode for RMW.)

- [ ] **Step 5: Run to verify PASS**

Run: `cd /home/qwertyoruiop/m68k-core-040-ooo/.claude/worktrees/agent-a14a4a0f9ae7096cb && JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt "testOnly m68k040.frontend.PredecodeWordSpec" 2>&1 | tee /home/qwertyoruiop/tmp/idx-t6-pass.log | tail -25`
Expected: PASS.

- [ ] **Step 6: Guard against PC-rel RMW dest in the assembler (ISA correctness)**

Since `eaExt`/`memDestExt` now frame indexed, ensure an indexed PC-rel EA used as an RMW DEST stays illegal (PC-rel is not alterable). In `MicroOpAssembler.scala`, the mem-dest gates (`aluRmwMemBad` etc.) accept any MEMSIMPLE — including `(d8,PC,Xn)` (pcRel). Add a guard: a pcRel EA is NOT an alterable destination. After the existing `memDest` computation, OR into the relevant bad-gates. Simplest: extend `memDest` to exclude pcRel:

```scala
    // PC-relative EAs (d16,PC)/(d8,PC,Xn) are NOT alterable -> never a mem-dest RMW/store
    // destination (ISA rule). They are SOURCE-only; a write to PC-space is illegal.
    val memDest       = srcIsMem && eaIsDst && rmwOpInScope && !srcEa.pcRel
```

VERIFY this does not regress existing `(d16,PC)` behavior: `(d16,PC)` as an RMW dest was previously MEMSIMPLE+pcRel and `memDest` would have fired (a latent pre-existing gap) — but predecode `memDestExt` rejected mode 7-2 (only modes 2/3/4/5 + 7-0/7-1), so a `(d16,PC)` RMW-dest was COMPLEX → illegal already. This guard makes the assembler agree. Run the existing RMW + mem lock-step (Task 7) to confirm no regression.

- [ ] **Step 7: Compile + re-run predecode spec**

Run: `cd /home/qwertyoruiop/m68k-core-040-ooo/.claude/worktrees/agent-a14a4a0f9ae7096cb && JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt "testOnly m68k040.frontend.PredecodeWordSpec" 2>&1 | tee /home/qwertyoruiop/tmp/idx-t6-final.log | tail -25`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/scala/m68k040/frontend/PredecodeWord.scala src/main/scala/m68k040/decode/MicroOpAssembler.scala src/test/scala/m68k040/frontend/PredecodeWordSpec.scala
git commit -m "predecode: frame brief-format indexed EAs as +1 ext word (eaExt/memDestExt); guard PC-rel against mem-dest RMW"
```

---

## Task 7: Lock-step the indexed matrix vs Musashi

**Files:**
- Modify: `src/test/scala/m68k040/lockstep/ExecuteLockStepSpec.scala`
- Test: itself

- [ ] **Step 0: Confirm opwords + Musashi expectations for each case**

Use the real assembler to pin encodings + expected results before writing asserts:

```bash
cd /tmp && cat > idxmat.s <<'EOF'
.text
_start:
  move.l #0x1000,%a0
  move.l #2,%d1
  move.l (4,%a0,%d1.w*2),%d2
  move.l %d3,(8,%a0,%d1.l*4)
  add.l  %d4,(4,%a0,%d1.w)
  bra .
EOF
m68k-linux-gnu-as -m68040 -o idxmat.o idxmat.s && m68k-linux-gnu-objcopy -O binary idxmat.o idxmat.bin && xxd idxmat.bin
```

For each lock-step program, also run Musashi directly (`assembleAndRun`-equivalent CLI from `/tmp`) to predict register/memory values, matching the established RMW test pattern (`checkMem`, `nInstr`, `.stop: bra .stop`).

- [ ] **Step 1: Add the indexed lock-step tests**

Append to `ExecuteLockStepSpec.scala` (after the RMW block, mirroring `runLockStep(...)` usage). Seed a memory cell, set base An + index Dn, run the indexed op, halt; `checkMem` verifies stores; the register/flag/PC stream lock-steps loads.

```scala
  // ── Brief-format indexed addressing lock-step ───────────────────────────────
  test("lock-step: MOVE.L (d8,An,Dn.w*2) load", VerilatorTest) {
    // [0x3008] = 0xCAFEBABE; a0=0x3000, d1=2; (4,a0,d1.w*2)=0x3000+4+4=0x3008 -> d2
    runLockStep("idx-load-l",
      "move.l #0xCAFEBABE,%d0 ; move.l #0x3000,%a0 ; move.l %d0,8(%a0) ; " +
      "move.l #2,%d1 ; move.l (4,%a0,%d1.w*2),%d2 ; " +
      ".stop: bra .stop", nInstr = 5)
  }
  test("lock-step: MOVE.L Dn,(d8,An,Dn.l*4) store", VerilatorTest) {
    // a0=0x3000, d1=1; (4,a0,d1.l*4)=0x3000+4+4=0x3008; store d3 -> [0x3008]
    runLockStep("idx-store-l",
      "move.l #0x3000,%a0 ; move.l #1,%d1 ; move.l #0x12345678,%d3 ; " +
      "move.l %d3,(4,%a0,%d1.l*4) ; " +
      ".stop: bra .stop", nInstr = 4, checkMem = Seq(0x3008L))
  }
  test("lock-step: ADD.L Dn,(d8,An,Dn.w) indexed RMW", VerilatorTest) {
    // seed [0x3004]=0x10000001; a0=0x3000,d1=4 -> (0,a0,d1.w)=0x3004; add d4 -> mem
    runLockStep("idx-rmw-add-l",
      "move.l #0x10000001,%d0 ; move.l #0x3000,%a0 ; move.l %d0,4(%a0) ; " +
      "move.l #4,%d1 ; move.l #0x20000002,%d4 ; add.l %d4,(0,%a0,%d1.w) ; " +
      ".stop: bra .stop", nInstr = 6, checkMem = Seq(0x3004L))   // -> 0x30000003
  }
  test("lock-step: ALU indexed source (ADD.L (d8,An,Dn),Dm)", VerilatorTest) {
    runLockStep("idx-alu-src",
      "move.l #0x00000005,%d0 ; move.l #0x3000,%a0 ; move.l %d0,8(%a0) ; " +
      "move.l #4,%d1 ; move.l #0x00000003,%d2 ; add.l (4,%a0,%d1.w),%d2 ; " +   // 0x3000+4+4=0x3008
      ".stop: bra .stop", nInstr = 6)   // d2 = 3 + 5 = 8
  }
  test("lock-step: indexed .W vs .L index size", VerilatorTest) {
    // .W index sign-extends: d1=0xFFFF (=-1 as .W) -> -1*1; .L would be +0xFFFF.
    runLockStep("idx-size-w",
      "move.l #0xAABBCCDD,%d0 ; move.l #0x3004,%a0 ; move.l %d0,(%a0) ; " +   // [0x3004]
      "move.l #0x0000FFFF,%d1 ; move.l (4,%a0,%d1.w*1),%d2 ; " +              // 0x3004+4-1=0x3007? -> use *0 disp
      ".stop: bra .stop", nInstr = 4)
  }
  test("lock-step: indexed scale *1/*2/*4/*8", VerilatorTest) {
    runLockStep("idx-scale",
      "move.l #0x11111111,%d0 ; move.l #0x3000,%a0 ; move.l %d0,16(%a0) ; " +  // [0x3010]
      "move.l #2,%d1 ; move.l (0,%a0,%d1.l*8),%d2 ; " +                        // 0x3000+0+16=0x3010
      ".stop: bra .stop", nInstr = 5)
  }
  test("lock-step: indexed negative d8", VerilatorTest) {
    runLockStep("idx-neg-d8",
      "move.l #0xDEADBEEF,%d0 ; move.l #0x3010,%a0 ; move.l %d0,-8(%a0) ; " +  // [0x3008]
      "move.l #4,%d1 ; move.l (-16,%a0,%d1.l*2),%d2 ; " +                      // 0x3010-16+8=0x3008
      ".stop: bra .stop", nInstr = 5)
  }
  test("lock-step: (d8,PC,Xn) PC-relative indexed load", VerilatorTest) {
    // PC-rel base = pc+2 (the ext word's addr). Use a data label after the code so the
    // computed address hits a known constant. Verified vs Musashi: base = instr pc + 2.
    runLockStep("idx-pc-load",
      "move.l #0,%d1 ; move.l (datum - . - 2,%pc,%d1.w),%d2 ; " +
      ".stop: bra .stop ; .align 2 ; datum: .long 0x0BADF00D", nInstr = 3)
  }
  test("lock-step: indexed An as index register", VerilatorTest) {
    runLockStep("idx-an-index",
      "move.l #0x44332211,%d0 ; move.l #0x3000,%a0 ; move.l %d0,12(%a0) ; " +  // [0x300C]
      "move.l #12,%a2 ; move.l (0,%a0,%a2.l*1),%d2 ; " +                       // 0x3000+0+12=0x300C
      ".stop: bra .stop", nInstr = 5)
  }
```

NOTE: the `idx-size-w` / `idx-pc-load` displacement arithmetic above is illustrative — in Step 0, PIN each address with the assembler + a Musashi run and ADJUST the d8/expected so the access lands on the seeded cell. Do NOT commit a test whose expected value you have not confirmed against Musashi. The harness lock-steps the FULL retired stream (regs/flags/PC), so a load test needs no `checkMem` (the loaded reg is compared); a store/RMW test needs `checkMem`.

- [ ] **Step 2: Run the indexed lock-step subset (own JVM)**

Run: `cd /home/qwertyoruiop/m68k-core-040-ooo/.claude/worktrees/agent-a14a4a0f9ae7096cb && JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt "testOnly m68k040.lockstep.ExecuteLockStepSpec -- -z idx" 2>&1 | tee /home/qwertyoruiop/tmp/idx-t7-lockstep.log | tail -40`
Expected: all `idx-*` tests PASS, **0 diverged** vs Musashi. If a test diverges, DEBUG with `superpowers:systematic-debugging` (do NOT weaken/relabel — real fix only). Common suspects: index sign-extension (.W), scale shift, PC-rel base (pc vs pc+2), or the S0→S1 `s1Index` latch condition.

- [ ] **Step 3: Commit**

```bash
git add src/test/scala/m68k040/lockstep/ExecuteLockStepSpec.scala
git commit -m "test: indexed-EA lock-step matrix (load/store/RMW/ALU-src, .W/.L, scale *1/2/4/8, neg d8, PC-rel, An-index) vs Musashi — 0 diverged"
```

---

## Task 8: Full decode/predecode/regression gate

**Files:** none (verification only)

- [ ] **Step 1: Run the decode + predecode + EA specs (own JVM)**

Run: `cd /home/qwertyoruiop/m68k-core-040-ooo/.claude/worktrees/agent-a14a4a0f9ae7096cb && JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt "testOnly m68k040.decode.EaDecoderSpec m68k040.decode.OperationDecoderSpec m68k040.frontend.PredecodeWordSpec" 2>&1 | tee /home/qwertyoruiop/tmp/idx-t8-decode.log | tail -30`
Expected: ALL PASS (no regression in OperationDecoder/predecode parity).

- [ ] **Step 2: Run test-fast (own JVM)**

Run: `cd /home/qwertyoruiop/m68k-core-040-ooo/.claude/worktrees/agent-a14a4a0f9ae7096cb && JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt "test-fast" 2>&1 | tee /home/qwertyoruiop/tmp/idx-t8-fast.log | tail -30`
(If `test-fast` is an alias not present, use the Makefile target: `make test-fast` — check `Makefile` for the exact sbt invocation and replicate it with the JAVA_OPTS/timeout wrapper.)
Expected: PASS count ≥ the pre-change baseline (record the delta).

- [ ] **Step 3: Run the full lock-step spec (own JVM) — no regression**

Run: `cd /home/qwertyoruiop/m68k-core-040-ooo/.claude/worktrees/agent-a14a4a0f9ae7096cb && JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt "testOnly m68k040.lockstep.ExecuteLockStepSpec" 2>&1 | tee /home/qwertyoruiop/tmp/idx-t8-lockstep-all.log | tail -40`
Expected: ALL PASS (existing + new), 0 diverged.

- [ ] **Step 4: Commit (if any test-only fixups were needed)**

```bash
git add -A && git commit -m "test: indexed-EA full decode/predecode/fast/lock-step regression green" || echo "nothing to commit"
```

---

## Task 9: ≥200 OOC synth gate (FMax)

**Files:** none (synth gate only)

**CRITICAL — vivado mutual exclusion:** A concurrent controller / Track-B vivado may be running. NEVER run 2 vivados or Verilator+vivado simultaneously.

- [ ] **Step 1: Wait for any concurrent vivado to finish**

Run: `pgrep -af vivado` — if ANY real `vivado` synth/impl process is running (ignore the controller's `until`/watcher bash wrappers), WAIT. Arm a monitor:

Use the Monitor tool: `until ! pgrep -x vivado >/dev/null 2>&1; do sleep 30; done; echo "VIVADO_FREE"` — proceed only after `VIVADO_FREE`.

- [ ] **Step 2: Generate the synth Verilog**

Run: `cd /home/qwertyoruiop/m68k-core-040-ooo/.claude/worktrees/agent-a14a4a0f9ae7096cb && JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt "runMain m68k040.top.GenFullCoreSynthVerilog" 2>&1 | tee /home/qwertyoruiop/tmp/idx-t9-gen.log | tail -20`
Expected: `[success]`, the synth Verilog regenerated (note any `UNASSIGNED` warnings — there should be none new).

- [ ] **Step 3: Run the OOC synth (vivado free — re-check first)**

Run: `pgrep -x vivado || echo FREE` then, if FREE:
`cd /home/qwertyoruiop/m68k-core-040-ooo/.claude/worktrees/agent-a14a4a0f9ae7096cb && timeout 3000 vivado -mode batch -source synth/ooc_M68kFullCoreSynth.tcl 2>&1 | tee /home/qwertyoruiop/tmp/idx-t9-synth.log | tail -40`
Expected: completes; extract WNS/FMax + worst path.

- [ ] **Step 4: Extract + check the gate**

Run: `grep -iE "WNS|Worst Negative|FMAX|fmax|Slack|All user specified|critical|ERROR" /home/qwertyoruiop/tmp/idx-t9-synth.log | tail -30`
Expected: FMax ≥ 200 MHz. Record WNS, FMax, and the worst path (note whether it touches the new index AGU adder or is the pre-existing LS-EU/Dcache path). If the index term regressed FMax below the prior baseline meaningfully, REPORT it (the registered `s1Index` should keep the adder a shallow stage off flops — if the worst path is `rdIndex -> idxTerm0 -> s1Index`, that is a flop input and acceptable; if it is `s1Base + s1Disp + s1Index -> s1Va -> DTLB`, consider a small pipeline tweak, but only if it drops below 200).

- [ ] **Step 5: Commit the synth result note (if a tcl/probe file was added)**

```bash
git add -A && git commit -m "synth: indexed-EA OOC gate >=200MHz (record FMax/WNS/worst-path)" || echo "nothing to commit"
```

---

## Self-Review checklist (run before handoff)

- **Spec coverage:** modes 6 + 7-3 (Task 2) ✓; index reg .W/.L + scale (Task 2/5) ✓; d8 incl. negative (Task 2/7) ✓; PC-rel base=pc+2 VERIFIED (planning) ✓; load/store/RMW/ALU-src compose (Task 4/7) ✓; predecode length + parity (Task 6/8) ✓; lock-step matrix (Task 7) ✓; ≥200 gate (Task 9) ✓; index-src field threaded (Task 3) ✓.
- **Out of lane:** no LEA/PEA/MOVE-SR, no µcode, no OperationDecoder edit (confirmed EA-class-agnostic), MOVEM-indexed left out (Task 6 note) ✓.
- **>3-µop compose check:** indexed adds NO extra µop (the index is a 3rd SOURCE on the existing load/store/RMW µops, not a new µop). Indexed RMW = [load, op, store] = 3 µops (same as non-indexed RMW), each carrying srcC=Xn. Mem-to-mem MOVE with BOTH EAs indexed = [load(srcC=srcIdx), store(srcC=dstIdx)] (+ no auto, since indexed≠auto) = 2 µops. So NO path exceeds the 3-µop `AssembledUops` budget. If a future compose (e.g. indexed + predec, impossible since mutually exclusive) ever needed a 4th, REPORT rather than force — but none exists here.
- **Type consistency:** `indexValid/indexReg/indexLong/indexScale` (EaSpec); `indexLong/indexScale` + `srcCReg/srcCValid` (DecodedUop/RenamedUop); `rdIndex`/`s1Index`/`idxTerm0` (LsEu) — names consistent across tasks ✓.
- **Real-fix rule:** every task ends green or with an explicit documented illegal-path (full-format, PC-rel RMW dest, indexed JMP/JSR) — no weakened tests ✓.
