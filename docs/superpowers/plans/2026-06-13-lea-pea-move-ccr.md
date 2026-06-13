# LEA / PEA / MOVE-from-SR / MOVE-from-CCR / MOVE-to-CCR Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the FAST line-4 ops LEA, PEA, MOVE-from-SR, MOVE-from-CCR, MOVE-to-CCR to the m68k 68040 OoO core (Track C of the ISA-completion line-4 round), with NO supervisor-state CHANGE (only a read of the committed S byte + a privilege check for MOVE-from-SR).

**Architecture:**
- **LEA** computes a control EA address and writes it to An via a new LS-cluster *address-generate* µop (`leaAddr`): the LS EU AGU already computes `base + disp + Xn*scale`; we short-circuit completion in IDLE writing that address to the int dst with NO translate / NO cache access (so it never page-faults).
- **PEA** = `[leaAddr -> T0]` + `[stkPush store T0 -> -(A7)]` (2 µops; the push reuses LINK's register-data stkPush precedent).
- **MOVE-from-CCR / MOVE-to-CCR / MOVE-from-SR** are ALU-cluster cracks reusing the existing `toCcr` NZVC+X read ports. MOVE-from-CCR/SR write an int result = the assembled CCR/SR value (a new `fromCcr`/`fromSr` ALU result path). MOVE-to-CCR writes NZVC+X from the EA source low byte (reuse `toCcr` write path with an EA source instead of an immediate).
- **MOVE-from-SR** additionally reads the committed `srSys` system byte (wired read-only from the ROB's `exc.ss.srSys` to both ALU EUs — safe because the only writer of srSys is exception-entry/RTE which fully flushes) and is **privileged**: a new `needsSupervisor` µop flag makes the ROB convert the head to a faulted entry (vector 8, format-$0) at retire when the committed S bit is 0.

**Tech Stack:** SpinalHDL, sbt (`~/sbt/bin/sbt`, NOT on PATH), Verilator lock-step vs Musashi/MAME oracle, Vivado OOC synth.

---

## Reconcile-lesson (MANDATORY)

If you ADD a field to `DecodedUop` / `RenamedUop` / `OpSpec`, EVERY µop builder must assign it or the FullCoreDut gets an unassigned-field LATCH that fastTest WON'T catch (only the full-core lock-step + the 65536-opword PredecodeWordSpec do). The µop builders in `MicroOpAssembler.scala` that assign EVERY field are: the inline `opUop`, `ldUop`, `stUop`, `rmwStUop`, `divlUop`, `divremUop`, `mullUop`, `mulhiUop`, `ibrUop`, and the helper builders `movemMoveUop`, `movemAnUpdUop`, `mkUop`. Also `Microcode.resolve` in `decode/Microcode.scala`. After adding any new field, grep ALL of these and assign it. Prefer reusing existing fields/roles over new fields.

New fields this plan adds (keep to the minimum):
- `leaAddr : Bool` (DecodedUop + RenamedUop) — LS address-generate (write s1Va to int dst, no mem access).
- `fromCcr : Bool`, `fromSr : Bool`, `needsSupervisor : Bool` (DecodedUop + RenamedUop) — ALU CCR/SR read result + the privilege check.

No new `OpSpec` field is needed (the OperationDecoder classification rides existing OpSpec fields + a small set of `is*` opword matches in the assembler, mirroring how JMP/JSR/RTE/TRAP are matched there).

---

## File Structure

- `src/main/scala/m68k040/decode/DecodedUop.scala` — add `leaAddr`, `fromCcr`, `fromSr`, `needsSupervisor` fields.
- `src/main/scala/m68k040/rename/RenamedUop.scala` — same 4 fields.
- `src/main/scala/m68k040/rename/RenameStage.scala` — thread the 4 fields decode→rename.
- `src/main/scala/m68k040/decode/MicroOpAssembler.scala` — assign the 4 new fields in EVERY builder (default values); add the LEA / PEA / MOVE-CCR/SR cracks + their sequence-selection arms; add the bad-EA gating.
- `src/main/scala/m68k040/decode/Microcode.scala` — assign the 4 new fields in `resolve` (defaults).
- `src/main/scala/m68k040/decode/OperationDecoder.scala` — classify the new line-4 opwords (LEA / PEA / MOVE-from-SR / MOVE-from-CCR / MOVE-to-CCR) in the `is(0x4)` block.
- `src/main/scala/m68k040/execute/LsEuPlugin.scala` — `leaAddr` address-generate path (IDLE completes with s1Va, no translate/cache); route a `leaAddr` µop into the LS IQ.
- `src/main/scala/m68k040/execute/iq/IssueQueuePlugin.scala` — `isLs` must accept a `leaAddr` µop (currently gates on `memOp =/= NONE`).
- `src/main/scala/m68k040/execute/AluEuPlugin.scala` — `fromCcr`/`fromSr` int result path; MOVE-to-CCR (EA source → NZVC+X); a new `srSysIn` input port for `fromSr`.
- `src/main/scala/m68k040/rob/RobPlugin.scala` — `needsSupervisor` per-entry store; at retire convert to faulted vector-8 when committed S==0; expose `excSrSys` for wiring.
- `src/main/scala/m68k040/top/FullCoreSynth.scala` — wire ROB's `exc.ss.srSys` → both ALU EUs' `srSysIn`.
- `src/main/scala/m68k040/frontend/PredecodeWord.scala` — frame LEA / PEA / MOVE-from-SR / MOVE-from-CCR / MOVE-to-CCR lengths (opword + EA ext).
- Tests: `src/test/scala/m68k040/decode/OperationDecoderSpec.scala`, `.../decode/PredecodeWordSpec.scala`, `.../decode/MicroOpAssemblerSpec.scala`, and the full-core lock-step harness (find via `grep -rln "LockStep\|lockstep\|Musashi" src/test`).

---

## Build / test commands (each `-z` subset gets its OWN JVM)

- Decode/predecode/assembler specs (fast, no Verilator):
  - `JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt "testOnly *OperationDecoderSpec" 2>&1 | tee /home/qwertyoruiop/tmp/opdec.log`
  - `JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt "testOnly *PredecodeWordSpec" 2>&1 | tee /home/qwertyoruiop/tmp/predec.log`
  - `JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt "testOnly *MicroOpAssemblerSpec" 2>&1 | tee /home/qwertyoruiop/tmp/asm.log`
- fastTest (the project's fast regression alias — confirm its exact name with `grep -rn "fastTest\|test-fast" build.sbt` first):
  - `JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt fastTest 2>&1 | tee /home/qwertyoruiop/tmp/fast.log`
- Lock-step (Verilator) per `-z` filter, e.g.:
  - `JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt "testOnly *LockStep* -- -z LEA" 2>&1 | tee /home/qwertyoruiop/tmp/ls_lea.log`
  (Discover the actual lock-step spec name + how it takes directed programs by reading the harness first.)
- OOC ≥200 synth gate (run `pgrep -af vivado` FIRST — if Track D is gating, WAIT; NEVER 2 vivados, NEVER Verilator+vivado together):
  - `JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt "runMain m68k040.top.GenFullCoreSynthVerilog" 2>&1 | tee /home/qwertyoruiop/tmp/gen.log`
  - `timeout 1800 vivado -mode batch -source synth/ooc_M68kFullCoreSynth.tcl 2>&1 | tee /home/qwertyoruiop/tmp/vivado.log`

---

## Task 1: Add the 4 new µop fields (struct + threading + every builder)

This task ONLY adds the fields with safe defaults everywhere and threads them; no behavior yet. It exists to isolate the reconcile-lesson (latch) risk and keep a green build.

**Files:**
- Modify: `src/main/scala/m68k040/decode/DecodedUop.scala`
- Modify: `src/main/scala/m68k040/rename/RenamedUop.scala`
- Modify: `src/main/scala/m68k040/rename/RenameStage.scala`
- Modify: `src/main/scala/m68k040/decode/MicroOpAssembler.scala`
- Modify: `src/main/scala/m68k040/decode/Microcode.scala`

- [ ] **Step 1: Add fields to `DecodedUop`** (after the `indexLong`/`indexScale` block at the end of the bundle):

```scala
  // ── LEA address-generate (DecOp.MOVE, LS cluster) ───────────────────────────
  // Compute the control-EA ADDRESS (base + disp + Xn*scale, via the LS-EU AGU) and
  // write it to the int dst — NO memory access, NO translate (so it never faults).
  // The LS EU completes it in IDLE with s1Va as the result. Default False.
  val leaAddr  = Bool()
  // ── MOVE from/to SR/CCR (ALU cluster) ───────────────────────────────────────
  // fromCcr: write the int dst = CCR byte {X,N,Z,V,C} zero-extended (.W). fromSr:
  // write the int dst = the 16-bit SR = {srSysIn, CCR byte}. Both READ NZVC+X (the
  // toCcr read ports). needsSupervisor: a privileged op (MOVE-from-SR) — the ROB
  // converts the head to a faulted vector-8 entry at retire if committed S==0.
  val fromCcr  = Bool()
  val fromSr   = Bool()
  val needsSupervisor = Bool()
```

- [ ] **Step 2: Add the same 4 fields to `RenamedUop`** (after its `indexLong`/`indexScale`), with one-line comments:

```scala
  // LEA address-generate (LS): write s1Va to the int dst, no mem access. Threaded.
  val leaAddr  = Bool()
  // MOVE from/to CCR/SR (ALU). fromCcr/fromSr select the CCR/SR int result; needs-
  // Supervisor = privileged (ROB vector-8 check on committed S). Threaded.
  val fromCcr  = Bool()
  val fromSr   = Bool()
  val needsSupervisor = Bool()
```

- [ ] **Step 3: Thread them in `RenameStage`** — find the block that copies `r.<field> := dec.<field>` (around `r.indexLong := dec.indexLong`) and add:

```scala
      r.leaAddr         := dec.leaAddr
      r.fromCcr         := dec.fromCcr
      r.fromSr          := dec.fromSr
      r.needsSupervisor := dec.needsSupervisor
```

- [ ] **Step 4: Assign defaults in EVERY MicroOpAssembler builder.** In each of `movemMoveUop`, `movemAnUpdUop`, `mkUop`, and the inline builders `opUop`, `ldUop`, `stUop`, `rmwStUop`, `divlUop`, `divremUop`, `mullUop`, `mulhiUop`, `ibrUop`, add (next to where each assigns `indexLong`/`indexScale`):

```scala
    <u>.leaAddr := False; <u>.fromCcr := False; <u>.fromSr := False; <u>.needsSupervisor := False
```

(Replace `<u>` with the builder's variable: `u`, `opUop`, `ldUop`, etc. For `mkUop` use `u.`.) Grep to confirm none missed: `grep -n "indexLong" src/main/scala/m68k040/decode/MicroOpAssembler.scala` — every hit's builder must also get the 4 new assigns.

- [ ] **Step 5: Assign defaults in `Microcode.resolve`** — open `src/main/scala/m68k040/decode/Microcode.scala`, find where it builds a `DecodedUop` and assigns `indexLong`/`indexScale` (or the field-init block), and add the 4 `:= False` assigns there too.

- [ ] **Step 6: Compile to verify no latch / no missing assignment**

Run: `JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt "Test/compile" 2>&1 | tee /home/qwertyoruiop/tmp/compile1.log`
Expected: compiles clean (no `LATCH DETECTED` / `assignment overlap` / `NOT assigned` errors).

- [ ] **Step 7: Run fastTest to confirm no regression**

Run: `JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt fastTest 2>&1 | tee /home/qwertyoruiop/tmp/fast1.log`
Expected: all PASS (the new fields are dead so behavior is unchanged).

- [ ] **Step 8: Commit**

```bash
git add src/main/scala/m68k040/decode/DecodedUop.scala src/main/scala/m68k040/rename/RenamedUop.scala src/main/scala/m68k040/rename/RenameStage.scala src/main/scala/m68k040/decode/MicroOpAssembler.scala src/main/scala/m68k040/decode/Microcode.scala
git commit -m "$(cat <<'EOF'
lea-pea-ccr: add leaAddr/fromCcr/fromSr/needsSupervisor uop fields (defaults only)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: OperationDecoder classification for the 5 ops

Classify the new line-4 opwords. LEA/PEA/MOVE-CCR/SR are decoded primarily in the assembler (like JMP/JSR/RTE), so OperationDecoder only needs to NAME a benign op + size + the operand roles so the assembler's `bad` doesn't fire and so EA ext words get the right operand class. Keep each in a distinct sub-band.

Opword encodings:
- LEA: `0100 An 1 11 mmmrrr` = `0x41C0 | An<<9 | ea`. op[15:6] for a fixed An is messy, so match by: `op[15:12]==4 && op[8:6]==7` (bits 8:6 == 111) — that is the LEA pattern (`1 11` = bit8=1, bits7:6=11). (CHK is bit8=1,bit6=0; LEA is bit8=1,bits7:6=11, i.e. op[8:6]==7.)
- PEA: `0100 1000 01 mmmrrr` = `0x4840 | ea`; op[15:6] == `0100100001` (0x121).
- MOVE-from-SR: `0100 0000 11 mmmrrr` = `0x40C0 | ea`; op[15:6] == `0100000011` (0x103).
- MOVE-from-CCR: `0100 0010 11 mmmrrr` = `0x42C0 | ea`; op[15:6] == `0100001011` (0x10B).
- MOVE-to-CCR: `0100 0100 11 mmmrrr` = `0x44C0 | ea`; op[15:6] == `0100010011` (0x113).

(Do NOT touch 0x46C0 MOVE-to-SR — Track D.)

**Files:**
- Modify: `src/main/scala/m68k040/decode/OperationDecoder.scala` (the `is(0x4)` block)
- Test: `src/test/scala/m68k040/decode/OperationDecoderSpec.scala`

- [ ] **Step 1: Write failing OperationDecoder tests.** Add to `OperationDecoderSpec` (mirror the existing line-4 test style — read the file to match its `decode(opword)` + assertion idiom):

```scala
  test("LEA computes a control EA -> An (MOVE, non-illegal, LONG)") {
    val o = OperationDecoder.decode(B"16'h41D0")  // LEA (A0),A0
    assert(!o.illegal.toBoolean)
    // op is a benign MOVE placeholder; the assembler builds the real leaAddr crack.
  }
  test("PEA is non-illegal") {
    val o = OperationDecoder.decode(B"16'h4850")  // PEA (A0)
    assert(!o.illegal.toBoolean)
  }
  test("MOVE-from-SR is non-illegal, WORD") {
    val o = OperationDecoder.decode(B"16'h40D0")  // MOVE SR,(A0)
    assert(!o.illegal.toBoolean)
  }
  test("MOVE-from-CCR is non-illegal, WORD") {
    val o = OperationDecoder.decode(B"16'h42D0")  // MOVE CCR,(A0)
    assert(!o.illegal.toBoolean)
  }
  test("MOVE-to-CCR is non-illegal, WORD") {
    val o = OperationDecoder.decode(B"16'h44D0")  // MOVE (A0),CCR
    assert(!o.illegal.toBoolean)
  }
  test("MOVE-to-SR stays untouched (Track D) -> still illegal here") {
    val o = OperationDecoder.decode(B"16'h46D0")  // MOVE (A0),SR
    assert(o.illegal.toBoolean)
  }
```

NOTE: SpinalHDL `OperationDecoder.decode` is combinational hardware called inside a `Component`/sim context in the existing spec — copy the EXACT harness wrapper the existing tests use (likely a tiny `Component` with the result poked out, or a pure-combinational eval). Match it; do not invent a new harness.

- [ ] **Step 2: Run to verify they fail**

Run: `JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt "testOnly *OperationDecoderSpec" 2>&1 | tee /home/qwertyoruiop/tmp/opdec_fail.log`
Expected: the 5 new "non-illegal" tests FAIL (line-4 default is illegal); the MOVE-to-SR test PASSES.

- [ ] **Step 3: Implement the classification** in the `is(0x4)` block of `OperationDecoder.scala`. Add AFTER the MOVEM block (so it is a distinct sub-band), each a separate `when`:

```scala
        // ── LEA An,<ea> (0100 An 1 11 mmmrrr): op[8:6]==111. Compute the control EA
        // ADDRESS -> An (full-32, no flags). A benign MOVE placeholder; the assembler
        // builds the leaAddr crack (LS address-generate). The An dst rides the normal
        // int dst (op[11:9]). NOT CHK (CHK is op[8:6] with bit6=0).
        when(opword(8) && (opword(7 downto 6) === B"11")) {
          o.illegal := False
          o.op := DecOp.MOVE; o.size := Size.LONG
          // operands left to the assembler's leaAddr crack (no EASRC operand here:
          // the EA is an ADDRESS, computed by the AGU, not a loaded value).
        }
        // ── PEA <ea> (0100 1000 01 mmmrrr): op[15:6]==0x121. Push the control EA addr.
        when(opword(15 downto 6) === B"10'b0100100001") {
          o.illegal := False
          o.op := DecOp.MOVE; o.size := Size.LONG
        }
        // ── MOVE from SR (0100 0000 11 mmmrrr): op[15:6]==0x103. SR(16) -> EA (.W).
        // PRIVILEGED (040): the assembler sets needsSupervisor (ROB vector-8 if S==0).
        when(opword(15 downto 6) === B"10'b0100000011") {
          o.illegal := False
          o.op := DecOp.MOVE; o.size := Size.WORD
        }
        // ── MOVE from CCR (0100 0010 11 mmmrrr): op[15:6]==0x10B. CCR(byte,ZX) -> EA (.W).
        when(opword(15 downto 6) === B"10'b0100001011") {
          o.illegal := False
          o.op := DecOp.MOVE; o.size := Size.WORD
        }
        // ── MOVE to CCR (0100 0100 11 mmmrrr): op[15:6]==0x113. EA(.W low byte) -> CCR.
        when(opword(15 downto 6) === B"10'b0100010011") {
          o.illegal := False
          o.op := DecOp.MOVE; o.size := Size.WORD
        }
```

IMPORTANT ordering: the LEA `when(opword(8) && opword(7:6)==11)` must NOT clobber CHK (CHK requires `!opword(6)`, so they are disjoint) nor the MOVEM/EXT patterns (those use op[8:6] differently — verify by checking that 0x4880/0x48C0/0x49C0 EXT and 0x4840 SWAP have op[7:6] != 11: SWAP=0x4840 op[7:6]=00, EXT.W=0x4880 op[7:6]=10, EXT.L=0x48C0 op[7:6]=11 — CONFLICT!). EXT.L (0x48C0-C7, op[8:6]=011? recompute: 0x48C0 = 0100 1000 11 000rrr -> op[8:6] = bits 8,7,6 = 0,1,1 = 011 -> bit8=0). So EXT.L has bit8=0, LEA has bit8=1 -> disjoint. PEA 0x4840 op[8:6]=001 (bit8=0). Confirm with the test matrix below; the `opword(8) &&` guard on LEA is what separates it from EXT/SWAP/PEA (all bit8=0). MOVEM has bit11=1 & bits9:7=001 — LEA with An=... can have bit11 set; but LEA requires op[8:6]==111 (bit7=bit6=1) whereas MOVEM has bit7=0 (bits9:7=001 means bit7=1? bits 9,8,7 = 0,0,1 -> bit7=1, bit8=0). MOVEM bit8=0 -> disjoint from LEA bit8=1. Good. ALSO: the CHK arm earlier (`when(opword(8) && !opword(6) ...)`) and the new LEA arm (`when(opword(8) && opword(7:6)==11)`) are disjoint on bit6.

- [ ] **Step 4: Run the OperationDecoder tests**

Run: `JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt "testOnly *OperationDecoderSpec" 2>&1 | tee /home/qwertyoruiop/tmp/opdec_pass.log`
Expected: all PASS (incl. the unchanged MOVE-to-SR-illegal test).

- [ ] **Step 5: Commit**

```bash
git add src/main/scala/m68k040/decode/OperationDecoder.scala src/test/scala/m68k040/decode/OperationDecoderSpec.scala
git commit -m "$(cat <<'EOF'
lea-pea-ccr: OperationDecoder classifies LEA/PEA/MOVE-from-SR/CCR/to-CCR (line-4)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Predecode framing (lengths) for the 5 ops

Frame the instruction lengths so `nextPc` is correct (else `lenWords=0 -> nextPc=pc` deadlock). All 5 are opword + EA extension words; reuse the existing `eaExt` helper.

**Files:**
- Modify: `src/main/scala/m68k040/frontend/PredecodeWord.scala` (the `is(U(4,4 bits))` block)
- Test: `src/test/scala/m68k040/decode/PredecodeWordSpec.scala`

- [ ] **Step 1: Write failing framing tests.** Add to `PredecodeWordSpec` (match its existing per-opword `predecode(op)` + `lenWords`/`simple` idiom):

```scala
  test("LEA (d16,An),An frames opword + 1 ext (len 2)") {
    val r = PredecodeWord.decode(B"16'h41E8")  // LEA (d16,A0),A0
    assert(r.simple.toBoolean); assert(r.lenWords.toInt == 2)
  }
  test("LEA (A0),A0 frames len 1") {
    val r = PredecodeWord.decode(B"16'h41D0")
    assert(r.simple.toBoolean); assert(r.lenWords.toInt == 1)
  }
  test("PEA (d16,A0) frames len 2") {
    val r = PredecodeWord.decode(B"16'h4868")
    assert(r.simple.toBoolean); assert(r.lenWords.toInt == 2)
  }
  test("MOVE from SR (A0) frames len 1") {
    val r = PredecodeWord.decode(B"16'h40D0")
    assert(r.simple.toBoolean); assert(r.lenWords.toInt == 1)
  }
  test("MOVE from CCR (A0) frames len 1") {
    val r = PredecodeWord.decode(B"16'h42D0")
    assert(r.simple.toBoolean); assert(r.lenWords.toInt == 1)
  }
  test("MOVE to CCR #imm... actually (A0) frames len 1") {
    val r = PredecodeWord.decode(B"16'h44D0")
    assert(r.simple.toBoolean); assert(r.lenWords.toInt == 1)
  }
```

(Use the EXACT `PredecodeWord` invocation the spec already uses — read it first.)

- [ ] **Step 2: Run to verify they fail**

Run: `JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt "testOnly *PredecodeWordSpec" 2>&1 | tee /home/qwertyoruiop/tmp/predec_fail.log`
Expected: the new tests FAIL (currently `simple=false`/`lenWords=0`).

- [ ] **Step 3: Implement framing** in the `is(U(4, 4 bits))` block. `srcMode`/`srcReg` already exist in that scope (used by CHK/DIV.L/JMP). Add:

```scala
        // LEA (0100 An 1 11 mmmrrr): control EA, opword + EA ext. LEA uses control-
        // alterable + PC-rel modes (incl indexed). Frame via eaExt (no #imm). bit8=1 &
        // op[7:6]=11. Excludes CHK (bit6=0).
        val isLea = op(8) && (op(7 downto 6) === B"11")
        when(isLea) {
          val (ok, e) = eaExt(srcMode, srcReg, sizeL = False, allowImm = false)
          when(ok) { r.simple := True; r.lenWords := (U(1, 3 bits) + e).resized }
        }
        // PEA (0100 1000 01 mmmrrr): control EA, opword + EA ext.
        val isPea = op(15 downto 6) === B"10'b0100100001"
        when(isPea) {
          val (ok, e) = eaExt(srcMode, srcReg, sizeL = False, allowImm = false)
          when(ok) { r.simple := True; r.lenWords := (U(1, 3 bits) + e).resized }
        }
        // MOVE from SR/CCR (0x40C0 / 0x42C0): SR/CCR -> EA (.W), data-alterable EA.
        val isMoveFromSr  = op(15 downto 6) === B"10'b0100000011"
        val isMoveFromCcr = op(15 downto 6) === B"10'b0100001011"
        when(isMoveFromSr || isMoveFromCcr) {
          val (ok, e) = eaExt(srcMode, srcReg, sizeL = False, allowImm = false)
          when(ok) { r.simple := True; r.lenWords := (U(1, 3 bits) + e).resized }
        }
        // MOVE to CCR (0x44C0): EA(.W) -> CCR, data EA incl #imm.
        val isMoveToCcr = op(15 downto 6) === B"10'b0100010011"
        when(isMoveToCcr) {
          val (ok, e) = eaExt(srcMode, srcReg, sizeL = False, allowImm = true)
          when(ok) { r.simple := True; r.lenWords := (U(1, 3 bits) + e).resized }
        }
```

NOTE: confirm `eaExt`'s signature (params: `(mode, reg, sizeL, allowImm)`) by reading its definition near the top of the file; the call above matches the CHK call at the file's line ~211. If `eaExt` accepts An-direct (mode 1) with ext 0, LEA/PEA would frame an illegal-EA op as len-1 — that is FINE for framing (the assembler rejects the EA and faults vector 4; the length is still 1 which is correct for a 1-word opword). Do NOT over-restrict here.

- [ ] **Step 4: Run the predecode tests**

Run: `JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt "testOnly *PredecodeWordSpec" 2>&1 | tee /home/qwertyoruiop/tmp/predec_pass.log`
Expected: all PASS (incl. the 65536-opword parity test if present — it must not regress).

- [ ] **Step 5: Commit**

```bash
git add src/main/scala/m68k040/frontend/PredecodeWord.scala src/test/scala/m68k040/decode/PredecodeWordSpec.scala
git commit -m "$(cat <<'EOF'
lea-pea-ccr: predecode framing for LEA/PEA/MOVE-from-SR/CCR/to-CCR

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: MOVE-from-CCR and MOVE-to-CCR cracks (no SR, no privilege)

Do the two NON-privileged, NON-SR CCR moves first — they are the lowest-risk and prove the ALU CCR read/write path before LEA/SR.

### 4a: MOVE-to-CCR (EA source .W low byte -> NZVCX)

This is "ANDI-to-CCR but a full MOVE with any EA source". The existing `toCcr` write path takes `imm[4:0]`; MOVE-to-CCR instead takes the EA source value's low 5 bits and writes them DIRECTLY (no AND/OR/EOR fold — it is a plain assignment of {X,N,Z,V,C} = src[4:0]). Reuse the `toCcr` flag-write machinery with a "direct move" semantic.

**Files:**
- Modify: `src/main/scala/m68k040/decode/MicroOpAssembler.scala`
- Modify: `src/main/scala/m68k040/execute/AluEuPlugin.scala`
- Test: lock-step harness directed programs.

- [ ] **Step 1: Add the MOVE-to-CCR opword match + crack in MicroOpAssembler** (in the `is*` opword-match region near JMP/JSR, ~line 690):

```scala
    // ── MOVE to CCR (0x44C0 | ea): EA(.W low byte) -> CCR {X,N,Z,V,C} = src[4:0]. ──
    // NOT privileged. The op µop (ALU, toCcr write path) takes the EA source as srcB
    // (reg/imm/memSimple-load->T0) and WRITES NZVC+X directly (no logical fold). For a
    // memSimple source the generic crackLoad already prepends a load -> T0 (the EASRC
    // srcB routes to T0); we mark the op µop fromMoveToCcr below.
    val isMoveToCcrOp = (op(15 downto 6) === B"10'b0100010011")
```

The EA source must be routed as srcB EASRC. The simplest: set the OpSpec srcB = EASRC in OperationDecoder for MOVE-to-CCR. **Update Task 2's MOVE-to-CCR arm** to add `o.srcB := easrc` (the EA source) so the generic crackLoad path prepends the load for a memory EA and srcB reads T0/reg/imm. Then here mark the op µop:

```scala
    when(isMoveToCcrOp) {
      opUop.cluster   := Cluster.INT
      opUop.toCcr     := True            // reuse the toCcr flag-write ports (NZVC+X dst)
      opUop.op        := DecOp.MOVE      // MOVE -> direct assign (no AND/OR/EOR fold)
      opUop.readsNzvc := False; opUop.readsX := False   // a plain MOVE-to-CCR does NOT read old CCR
      opUop.writesNzvc := True; opUop.writesX := True
      opUop.dstValid  := False
      opUop.unimplemented := False
      // srcB carries the EA source (reg/imm, or T0 from a cracked load) -> the ALU EU
      // assigns CCR := srcB[4:0] when toCcr && op==MOVE.
    }
```

NOTE: the existing `toCcr` AluEu path muxes on `u1.op` AND -> &, OR -> |, default(EOR) -> ^. A `DecOp.MOVE` op with `toCcr` will hit the `default` (EOR) — WRONG. Fix the AluEu mux in Step 2 to add a MOVE -> direct-assign case.

ALSO: the `isToCcr` (ANDI/ORI/EORI #imm,CCR) detection uses `spec.op === AND/OR/EOR`; MOVE-to-CCR has `spec.op === MOVE`, so it does NOT collide with `isToCcr`. But make sure `isMoveToCcrOp` is excluded from `bad`: add `!isMoveToCcrOp` to the `bad` expression's allow-list (mirror `!isJmpOp`), AND ensure a memSimple source still cracks the load — for that, DO NOT exclude it from crackLoad. Since MOVE-to-CCR uses srcB=EASRC and op=MOVE with no EADST and no IMMQ3, the generic `crackLoad` (usesSrcEa && srcIsMem) WILL prepend a load. Verify the op µop's `toCcr` override survives the crack (it is applied to `opUop`, which is uops(1) of the load crack). Good.

- [ ] **Step 2: AluEu — direct CCR assign for MOVE-to-CCR.** In `AluEuPlugin.scala`, update the `ccrNew` mux (around line 241) to handle `DecOp.MOVE` (direct assign of the source's low 5 bits, no fold):

```scala
    val ccrNew = u1.op.mux(
      DecOp.AND  -> (ccrOld & ccrImm),
      DecOp.OR   -> (ccrOld | ccrImm),
      DecOp.MOVE -> ccrImm,                  // MOVE-to-CCR: CCR := src[4:0] (direct)
      default    -> (ccrOld ^ ccrImm))       // EOR
```

`ccrImm := s1Src2(4 downto 0)` already = srcB low 5 bits (the EA source for MOVE-to-CCR / the imm for ANDI-to-CCR). NOTE the CCR bit layout {X(4),N(3),Z(2),V(1),C(0)} maps src[4:0] directly — confirm Musashi's MOVE-to-CCR uses the SAME bit order (it does: CCR is the SR low byte, X=4,N=3,Z=2,V=1,C=0).

- [ ] **Step 3: Lock-step MOVE-to-CCR.** Add a directed program to the lock-step harness exercising `MOVE #imm,CCR` and `MOVE Dn,CCR` and `MOVE (An),CCR` with various NZVCX bit patterns; assert 0 diverged vs Musashi.

Run: `JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt "testOnly *LockStep* -- -z MoveToCcr" 2>&1 | tee /home/qwertyoruiop/tmp/ls_mtccr.log`
Expected: 0 diverged.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
lea-pea-ccr: MOVE-to-CCR crack (ALU direct CCR := src[4:0], any EA source)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

### 4b: MOVE-from-CCR (CCR byte ZX -> EA dest .W)

CCR {X,N,Z,V,C} (5 bits) zero-extended to a 16-bit word -> the EA destination (.W). The ALU EU reads NZVC+X (toCcr read ports) and produces an INT RESULT = the CCR byte; the result is then written to the EA (reg dest directly, or a store crack for a memory dest).

**Files:**
- Modify: `src/main/scala/m68k040/decode/MicroOpAssembler.scala`
- Modify: `src/main/scala/m68k040/decode/OperationDecoder.scala` (MOVE-from-CCR operand roles)
- Modify: `src/main/scala/m68k040/execute/AluEuPlugin.scala`
- Test: lock-step.

- [ ] **Step 1: OperationDecoder — give MOVE-from-CCR a dst EA role.** Update Task 2's MOVE-from-CCR arm to name the EA as the destination and the CCR as the (implicit) source:

```scala
        when(opword(15 downto 6) === B"10'b0100001011") {  // MOVE from CCR
          o.illegal := False
          o.op := DecOp.MOVE; o.size := Size.WORD
          o.dst := eadst2  // op[5:0] EA as the WRITE destination (see note)
          o.dstWrites := True
        }
```

NOTE: MOVE-from-CCR's EA is in op[5:0] (the SOURCE EA field), but it is a DESTINATION (write). The existing `EADST` operand kind reads the DEST-EA field `op[8:6]##op[11:9]` (MOVE's dst). For MOVE-from-CCR the write EA is op[5:0]. The clean reuse: treat it like the line-4 unary RMW dst (CLR-style) which uses `EASRC` as a write dest (`spec.dst.kind === EASRC`, `memDest` crack). So set `o.srcA := easrc; o.dst := easrc` and let the assembler's CLR-like `crackClr`/store path write the result. BUT the result here is the CCR byte, not 0. Simpler: handle MOVE-from-CCR ENTIRELY in the assembler (like JMP) rather than via the generic dst machinery — see Step 2. So in OperationDecoder leave MOVE-from-CCR as just `op:=MOVE,size:=WORD,illegal:=False` (revert the dst lines) and do the dst routing in the assembler.

- [ ] **Step 2: Assembler — MOVE-from-CCR crack.** The op µop is an ALU op that produces the CCR byte as its int result; route it to the EA dest:
  - **Reg dest (Dn/An):** single ALU op µop, dst = the EA reg, `fromCcr := True`. (.W to a data reg = partial merge preserve upper 16; to An — MOVE-from-CCR to An is illegal on 68k, reject.)
  - **Memory dest (MEMSIMPLE):** `[fromCcr op -> T1]` + `[store.w T1 -> <ea>]` (CLR-style: no leading load).

Add the opword match + a dedicated crack. Reuse `crackClr`'s store builder (`rmwStUop` reads T1). Set the op µop:

```scala
    val isMoveFromCcrOp = (op(15 downto 6) === B"10'b0100001011")
    // EA (op[5:0]) is the WRITE destination. Reg dest -> single ALU op (fromCcr). Mem
    // dest -> [fromCcr -> T1] + [store.w T1]. An-direct dest is illegal (CCR->An invalid).
    val mfcEaIsDataReg = (srcEa.klass === EaClass.DATAREG)
    val mfcEaIsMem     = (srcEa.klass === EaClass.MEMSIMPLE) && !srcEa.pcRel
    val moveFromCcrBad = isMoveFromCcrOp && !mfcEaIsDataReg && !mfcEaIsMem
    when(isMoveFromCcrOp) {
      opUop.op        := DecOp.MOVE
      opUop.cluster   := Cluster.INT
      opUop.size      := Size.WORD
      opUop.fromCcr   := True
      opUop.readsNzvc := True; opUop.readsX := True     // read CCR (toCcr read ports)
      opUop.writesNzvc := False; opUop.writesX := False
      opUop.srcAValid := False; opUop.srcBValid := False; opUop.useImm := False
      opUop.unimplemented := False
      when(mfcEaIsDataReg) {
        // .W write to Dn = partial merge: read old Dn as srcA (merge upper-16 source).
        opUop.srcAReg := srcEa.reg; opUop.srcAValid := True
        opUop.dstReg  := srcEa.reg; opUop.dstValid := True
      } otherwise {
        // mem dest: produce into T1, the store reads T1 (rmwStUop, size forced WORD).
        opUop.dstReg := U(T1, 5 bits); opUop.dstValid := True
      }
    }
```

For the memory-dest store, reuse `rmwStUop` but its size is `Mux(isBitOp, BYTE, spec.size)` = WORD here (good). You need a sequence-selection arm:

```scala
    } elsewhen(isMoveFromCcrOp) {
      when(moveFromCcrBad) {
        // illegal EA (An-direct / pcRel / #imm) -> vector 4 (force like `bad`).
        out.count := 1; out.uops(0) := opUop; out.uops(1) := opUop
      } elsewhen(mfcEaIsMem) {
        out.count := 2; out.uops(0) := opUop; out.uops(1) := rmwStUop
      } otherwise {
        out.count := 1; out.uops(0) := opUop; out.uops(1) := opUop
      }
```

AND set the forced-illegal fields for `moveFromCcrBad` (faulted vector 4) in a `when(moveFromCcrBad){...}` block next to `jmpBad`. AND add `!isMoveFromCcrOp` to the `bad` allow-list. Place the `elsewhen(isMoveFromCcrOp)` arm BEFORE the generic `crackLoad`/`otherwise` (so it owns the sequence). For `rmwStUop` to address op[5:0] correctly it already uses `srcEa.base`/`rmwEaDisp` — good (MOVE-from-CcR is never a line-0 immediate, so `opIsLineImm` is false and rmwEaDisp=srcEa.disp).

- [ ] **Step 3: AluEu — fromCcr int result.** The CCR byte = `{0...0, X, N, Z, V, C}` (16-bit zero-extend of the 5-bit CCR; bits 15:8 = 0, bit 7:5 = 0, bits 4:0 = X N Z V C). Build it and select it as the int result when `fromCcr`:

```scala
    // MOVE-from-CCR int result: zero-extended CCR byte. CCR layout {X(4),N(3),Z(2),V(1),C(0)}.
    val ccrByte   = (U(0, 27 bits) ## (s1X ## s1Nzvc(3 downto 0))).asBits   // 32-bit, low 5 = CCR
    val fromCcrRes = ccrByte
```

Then in the `mergedResult` selection (around line 231) add a fromCcr override. Since a .W reg-dest does a partial merge (preserve Dn upper 16), the result fed to the merge should be the CCR byte low 16; the existing `sizeMerged` (size=WORD) will merge `result[15:0]` with `s1Src1[31:16]`. So set the pre-merge `result`/`s1Src2`-derived value to `fromCcrRes`. The cleanest: introduce the override right where `mergedResult` is computed:

```scala
    val baseResult = ... (existing sizeMerged / moveaResult mux)
    val mergedResult = Mux(u1.fromCcr || u1.fromSr, /* see Task 6 for fromSr */ ccrOrSrResult, baseResult)
```

For 4b only `fromCcr` exists; gate `Mux(u1.fromCcr, fromCcrMerged, baseResult)` where `fromCcrMerged` is the WORD-size partial merge of `fromCcrRes` into `s1Src1` (use the same `sizeMerged` formula with `s1Src2 := fromCcrRes`). Simplest implementation: compute `sizeMerged` off a `srcForMove = Mux(u1.fromCcr, fromCcrRes, s1Src2)` so the existing WORD-merge applies uniformly. Refactor `sizeMerged` to read `srcForMove` instead of `s1Src2`. Verify MOVE-to-Dn partial-merge for .W still works (it reads srcA=old Dn for the merge — preserved).

- [ ] **Step 4: Lock-step MOVE-from-CCR** — directed program: set CCR to known patterns (via MOVE-to-CCR from 4a), then `MOVE CCR,Dn` / `MOVE CCR,(An)`; assert the written word == zero-extended CCR. 0 diverged.

Run: `JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt "testOnly *LockStep* -- -z MoveFromCcr" 2>&1 | tee /home/qwertyoruiop/tmp/ls_mfccr.log`
Expected: 0 diverged.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
lea-pea-ccr: MOVE-from-CCR crack (ALU CCR-byte int result -> reg/mem EA)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: LEA (address-generate) + PEA (compute + push)

### 5a: LS-EU address-generate path + IQ routing

**Files:**
- Modify: `src/main/scala/m68k040/execute/iq/IssueQueuePlugin.scala`
- Modify: `src/main/scala/m68k040/execute/LsEuPlugin.scala`
- Test: an LS-EU unit/lock-step.

- [ ] **Step 1: IQ routing** — `isLs` (line 173) must accept a `leaAddr` µop even with `memOp === NONE`:

```scala
    def isLs(u: RenamedUop): Bool =
      (u.cluster === m68k040.isa.Cluster.LS) && ((u.memOp =/= m68k040.isa.MemOp.NONE) || u.leaAddr)
```

- [ ] **Step 2: LS-EU IDLE address-generate.** In `LsEuPlugin.scala` IDLE (around line 687-723), BEFORE the `isLoad || isStore` branch, handle `leaAddr`: capture completion immediately with the computed `s1Va` as the int result, NO translate, NO cache. `s1Va` is already `base + disp + scaled index` (line ~266). Add:

```scala
      IDLE.whenIsActive {
        busy := False
        when(s1Valid) {
          when(u1.leaAddr) {
            // LEA: write the computed effective ADDRESS to the int dst. No translate,
            // no cache access -> never page-faults. compData = s1Va (reuse the stkPush
            // address-writeback precedent), pdst = An, wake consumers of An.
            captureCompletion(s1Va.asBits)   // captureCompletion already muxes compData;
                                             // see Step 3 — leaAddr forces compData := s1Va.
            busy := False; s1Valid := False
          } elsewhen(isLoad || isStore) {
            ... (existing)
          } otherwise {
            captureCompletion(B(0, 32 bits))
          }
        }
      }
```

- [ ] **Step 3: compData select for leaAddr.** In `captureCompletion` (line ~540), the `compData` mux currently selects stkPush -> s1Va, autoStoreAn -> s1AnWb, else ldResult. For `leaAddr`, compData must be s1Va. Add it FIRST:

```scala
      compData := Mux(u1.leaAddr, s1Va.asBits,
                  Mux(u1.stkPush, s1Va.asBits,
                  Mux(isAutoStoreAn, s1AnWb, ldResult)))
```

And ensure the int write fires: `compPdstValid := u1.pdstValid && !u1.ccrRestore` already covers it (leaAddr has pdstValid=True, ccrRestore=False). `compWakes := (isLoad && !ccrRestore) || stkPush || (isAutoStoreAn && pdstValid)` — ADD `|| u1.leaAddr` so the An write wakes dependents:

```scala
      compWakes := (isLoad && !u1.ccrRestore) || u1.stkPush || u1.leaAddr || (isAutoStoreAn && u1.pdstValid)
```

NOTE: a `leaAddr` µop has `memOp === NONE`, so `isLoad`/`isStore` are both False — the `compIsLoad`/`compRmwStore`/`compEaAutoDrop` derived signals stay benign (isStore False -> compRmwStore False; isAutoStoreAn False). Verify `s1Va` is valid for a NONE-memOp µop: `s1Va = s1Base + s1Disp + s1Index` is computed unconditionally from the registered operands (line ~266), independent of memOp — good. The index term rides psrcC (set when the EA is indexed); the AGU adds it. For a NON-indexed LEA, psrcCValid=False -> idxTerm0=0. Good.

- [ ] **Step 4: Unit test the LS address-generate** — drive a `leaAddr` µop (base An + disp + index) into the LS EU standalone DUT and assert the int writeback == base+disp+scaled-index, with NO translate request asserted. (Match the existing LsEu unit-test harness; if none, cover via lock-step in 5b.)

Run: `JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt "testOnly *LsEu* 2>&1 | tee /home/qwertyoruiop/tmp/lseu.log` (use the real spec name)
Expected: PASS.

- [ ] **Step 5: Assembler — LEA crack.** Add the opword match + the leaAddr µop. LEA EA = op[5:0] control modes (MEMSIMPLE incl indexed + PC-rel; reg-direct/imm/(An)+/-(An) illegal). The leaAddr µop computes the EA and writes An (op[11:9]):

```scala
    val isLeaOp = op(8) && (op(15 downto 12) === B"4'h4") && (op(7 downto 6) === B"11")
    // LEA control EA: MEMSIMPLE (incl indexed) but NOT predec/postinc (those are
    // MEMCOMPLEX here? no — (An)+/-(An) are MEMSIMPLE w/ autoMode; LEA forbids them).
    val leaEaOk = (srcEa.klass === EaClass.MEMSIMPLE) && (srcEa.autoMode === EaAuto.NONE)
    val leaAn   = (U(8, 5 bits) + op(11 downto 9).asUInt).resized
    val leaUop  = mkUop(cluster = Cluster.LS,
                        srcAReg = srcEa.base, srcAValid = srcEa.baseValid,
                        dstReg  = leaAn,      dstValid  = True,
                        useImm  = True,
                        imm = Mux(srcEa.pcRel, (pkt.pc + U(2,32 bits) + srcEa.disp.asUInt).asBits, srcEa.disp),
                        size = Size.LONG, first = True)
    // mkUop leaves memOp=NONE, leaAddr=False -> override:
    leaUop.memOp  := MemOp.NONE
    leaUop.leaAddr := True
    // indexed EA: route the index reg on srcC + size/scale (mkUop set srcC invalid).
    leaUop.srcCReg  := srcEa.indexReg; leaUop.srcCValid := srcEa.indexValid
    leaUop.indexLong := srcEa.indexLong; leaUop.indexScale := srcEa.indexScale
    val leaBad = isLeaOp && !leaEaOk
```

NOTE: `mkUop` builds via fixed params; the `.leaAddr`/`.memOp`/`.srcC*`/`.index*` overrides must be applied to the returned `leaUop` AFTER construction (SpinalHDL allows reassigning a Bundle field once; mkUop assigned them once to defaults, so a second assignment is an OVERLAP error). To avoid the overlap, EITHER extend `mkUop` with `leaAddr`/`srcC`/`index` params, OR build leaUop inline (a dedicated builder like `ibrUop`). RECOMMENDED: build `leaUop` inline as a full DecodedUop (copy the `ibrUop` builder shape, assign every field incl the 4 new ones, set `leaAddr:=True`, `cluster:=LS`, `memOp:=NONE`, srcA=base, srcC=index, dst=An, imm=disp/foldedPc). This avoids mkUop-overlap and is the safest.

Add `!isLeaOp` to the `bad` allow-list; force vector-4 illegal for `leaBad` (next to `jmpBad`); add the sequence arm:

```scala
    } elsewhen(isLeaOp) {
      out.count := 1
      out.uops(0) := Mux(leaBad, opUop, leaUop)
      out.uops(1) := Mux(leaBad, opUop, leaUop)
```

- [ ] **Step 6: Lock-step LEA** — directed program: `LEA (A0),A1`, `LEA (d16,A0),A1`, `LEA (d8,A0,D2.w*4),A1`, `LEA (d16,PC),A1`, `LEA (xxx).L,A1`; assert A1 == the computed address (0 diverged). Also a `LEA D0,A1` (illegal -> vector 4 trap).

Run: `JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt "testOnly *LockStep* -- -z Lea" 2>&1 | tee /home/qwertyoruiop/tmp/ls_lea.log`
Expected: 0 diverged (incl. the illegal-EA trap).

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
lea-pea-ccr: LEA via LS address-generate uop (base+disp+Xn*scale -> An, no mem access)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

### 5b: PEA (compute EA -> T0, push T0 -> -(A7))

**Files:**
- Modify: `src/main/scala/m68k040/decode/MicroOpAssembler.scala`
- Test: lock-step.

- [ ] **Step 1: Assembler — PEA crack.** PEA = `[leaAddr EA -> T0]` + `[stkPush store T0 -> -(A7)]`. The leaAddr µop is 5a's `leaUop` but with dst=T0 (not An). The push reuses LINK's register-data stkPush (`linkPush` stores srcB=register). Build:

```scala
    val isPeaOp = op(15 downto 6) === B"10'b0100100001"
    val peaEaOk = (srcEa.klass === EaClass.MEMSIMPLE) && (srcEa.autoMode === EaAuto.NONE)
    // leaAddr -> T0 (reuse the inline LEA builder shape, dst = T0, first = True).
    val peaAddr = <inline DecodedUop like leaUop but dstReg=U(T0,5 bits), first=True>
    // push.l T0 -> -(A7): stkPush store, base/dst A7 (A7 -= 4), store DATA = T0 (srcB).
    val peaPush = mkUop(cluster = Cluster.LS, memOp = MemOp.STORE, stkPush = True,
                        srcAReg = U(A7, 5 bits), srcAValid = True,   // base A7 (addr = A7-4)
                        srcBReg = U(T0, 5 bits), srcBValid = True,    // store data = computed EA
                        dstReg  = U(A7, 5 bits), dstValid  = True,    // A7 := A7-4
                        first = False)
    val peaBad = isPeaOp && !peaEaOk
```

NOTE: confirm the stkPush store's DATA path takes srcB when `srcBValid` (LINK's `linkPush` relies on this: "the LsEu data0 mux selects the register when srcBValid"). So the push stores T0's value. The stkPush writeback writes A7 := A7-4 (s1Va = A7-4, the predec address). Confirm `peaPush`'s store address = A7-4 (stkPush predecrements by sizeBytes=4 for a .L push) — yes, the BSR/JSR push does exactly this.

Add `!isPeaOp` to `bad`, force vector-4 for `peaBad`, sequence arm:

```scala
    } elsewhen(isPeaOp) {
      out.count := Mux(peaBad, U(1, 2 bits), U(2, 2 bits))
      out.uops(0) := Mux(peaBad, opUop, peaAddr)
      out.uops(1) := Mux(peaBad, opUop, peaPush)
```

ORDER: the leaAddr (T0) is uops(0) (first), the push uops(1). The push depends on T0 (its srcB) — dynamic LS wakeup tracks T0's pdst (like RTS's ibranch depending on the pop-load T0). Verify the LS->LS T0 dependency wakes (the leaAddr completes + wakes T0; the push's srcB=T0 is tracked by lsBusy/lsWakeup as for the RTS load->ibranch chain).

- [ ] **Step 2: Lock-step PEA** — directed program: set A7, `PEA (d16,A0)`, `PEA (d8,A0,D1.l*2)`, `PEA (xxx).L`; assert A7 -= 4 AND mem[A7'] == the computed EA (0 diverged). Check the pushed VALUE + A7 explicitly.

Run: `JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt "testOnly *LockStep* -- -z Pea" 2>&1 | tee /home/qwertyoruiop/tmp/ls_pea.log`
Expected: 0 diverged.

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
lea-pea-ccr: PEA crack ([leaAddr -> T0] + [stkPush store T0 -> -(A7)])

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: MOVE-from-SR (read srSys + privilege check)

The heaviest task. If, mid-implementation, MOVE-from-SR requires supervisor-state CHANGE machinery or more than this clean crack, STOP and report it as a deferral (the other 4 ops stand alone). It should NOT — it only READS srSys and does a privilege check.

### 6a: Wire committed srSys to the ALU EUs + fromSr int result

**Files:**
- Modify: `src/main/scala/m68k040/rob/RobPlugin.scala` (expose `excSrSys`)
- Modify: `src/main/scala/m68k040/execute/AluEuPlugin.scala` (`srSysIn` input + fromSr result)
- Modify: `src/main/scala/m68k040/top/FullCoreSynth.scala` (wire ROB.srSys -> both ALU EUs)
- Modify: `src/main/scala/m68k040/decode/MicroOpAssembler.scala` (MOVE-from-SR crack)

- [ ] **Step 1: Expose srSys from RobPlugin.** Find where `exc.ss.srSys` is defined (the `exc` unit, ~line 476). Add a plugin-level read accessor:

```scala
    val excSrSysOut = UInt(8 bits); excSrSysOut := exc.ss.srSys; excSrSysOut.simPublic()
```

Make it reachable from BackendWiringPlugin (a `var`/service accessor mirroring how the ROB exposes other signals to the wiring plugin — check how `rob` is referenced in `BackendWiringPlugin`).

- [ ] **Step 2: AluEu srSysIn port.** Add an 8-bit input wire to `AluEuPlugin` (a `var srSysIn: UInt = null`, created in setup, defaulted to 0 if unwired for standalone tests):

```scala
    // Committed SR system byte (for MOVE-from-SR int result). Wired from the ROB's
    // exc.ss.srSys in FullCoreSynth; defaults to 0 in standalone DUTs (no fromSr test
    // there). Safe to read combinationally: the only writer (exc entry / RTE) fully
    // flushes, so no in-flight fromSr ever sees a stale srSys.
    val srSysInWire = in UInt(8 bits)   // or a plugin port per the codebase's convention
```

(Match the codebase's EU-input convention — likely a `Bool`/`UInt` wire exposed as a `var` set by the wiring plugin, like `lsEu.excLoadCmdValid`. Look at how DivEu/BranchEu receive external inputs.)

- [ ] **Step 3: fromSr int result in AluEu.** SR = `{srSys(8), CCR-byte(8)}` where the CCR byte = `{0,0,0, X,N,Z,V,C}`. 16-bit, zero-extended to 32:

```scala
    val srWord    = (srSysInWire ## (U(0,3 bits) ## s1X ## s1Nzvc(3 downto 0))).asBits  // 16 bits
    val fromSrRes = (U(0, 16 bits) ## srWord.asUInt).asBits                              // 32-bit ZX
```

Extend the `srcForMove` introduced in Task 4b so `fromSr` also overrides the move source:

```scala
    val srcForMove = Mux(u1.fromSr, fromSrRes, Mux(u1.fromCcr, fromCcrRes, s1Src2))
```

(`sizeMerged` reads `srcForMove`; MOVE-from-SR is .W so the WORD-merge writes the low 16 = the SR word and preserves Dn upper 16 for a reg dest.)

- [ ] **Step 4: Wire it in FullCoreSynth.** In `BackendWiringPlugin.setup`/`build`, wire `eu0.srSysInWire := rob.excSrSysOut` and `eu1.srSysInWire := rob.excSrSysOut` (match the existing `lsEu.<port> := exc.<...>` wiring style).

- [ ] **Step 5: Assembler — MOVE-from-SR crack** (mirror MOVE-from-CCR's 4b structure but `fromSr` + `needsSupervisor`):

```scala
    val isMoveFromSrOp = (op(15 downto 6) === B"10'b0100000011")
    val mfsEaIsDataReg = (srcEa.klass === EaClass.DATAREG)
    val mfsEaIsMem     = (srcEa.klass === EaClass.MEMSIMPLE) && !srcEa.pcRel
    val moveFromSrBad  = isMoveFromSrOp && !mfsEaIsDataReg && !mfsEaIsMem
    when(isMoveFromSrOp) {
      opUop.op := DecOp.MOVE; opUop.cluster := Cluster.INT; opUop.size := Size.WORD
      opUop.fromSr := True
      opUop.needsSupervisor := True            // PRIVILEGED: ROB vector-8 if S==0
      opUop.readsNzvc := True; opUop.readsX := True
      opUop.writesNzvc := False; opUop.writesX := False
      opUop.srcBValid := False; opUop.useImm := False
      opUop.unimplemented := False
      when(mfsEaIsDataReg) {
        opUop.srcAReg := srcEa.reg; opUop.srcAValid := True   // .W merge upper-16 source
        opUop.dstReg  := srcEa.reg; opUop.dstValid := True
      } otherwise {
        opUop.srcAValid := False
        opUop.dstReg := U(T1, 5 bits); opUop.dstValid := True
      }
    }
```

Sequence arm (same shape as MOVE-from-CCR):

```scala
    } elsewhen(isMoveFromSrOp) {
      when(moveFromSrBad) { out.count := 1; out.uops(0) := opUop; out.uops(1) := opUop }
      .elsewhen(mfsEaIsMem) { out.count := 2; out.uops(0) := opUop; out.uops(1) := rmwStUop }
      .otherwise { out.count := 1; out.uops(0) := opUop; out.uops(1) := opUop }
```

Add `!isMoveFromSrOp` to `bad`; force vector-4 illegal for `moveFromSrBad`.

IMPORTANT: `needsSupervisor` must be carried on the FIRST µop of the instruction (the op µop, which for a mem-dest is uops(0)). For a mem-dest MOVE-from-SR the op µop is uops(0) (firstOfInstr) and the store is uops(1) — good. The privilege fault must trigger at the FIRST µop's retire. Confirm the op µop is `firstOfInstr := True` for MOVE-from-SR (no leading load -> `opHasLeadingLoad` false -> firstOfInstr True). Good.

- [ ] **Step 6: Compile + fastTest** (no behavior assertion yet — the privilege check lands in 6b):

Run: `JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt fastTest 2>&1 | tee /home/qwertyoruiop/tmp/fast6a.log`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
lea-pea-ccr: MOVE-from-SR crack (wire committed srSys to ALU EUs; fromSr int result)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

### 6b: Privilege-violation check in the ROB (vector 8 when committed S==0)

**Files:**
- Modify: `src/main/scala/m68k040/rob/RobPlugin.scala`
- Test: lock-step (supervisor + user mode).

- [ ] **Step 1: Store `needsSupervisor` per ROB entry.** Add a `needsSupStore` Mem/Vec mirroring `faultedStore` (find its declaration). At alloc (the `alloc0`/`alloc1` blocks ~line 363/379) write `needsSupStore(tail) := allocUopVec(0).needsSupervisor` (and `tail+1` for slot1).

- [ ] **Step 2: Convert to faulted vector-8 at the head when committed S==0.** Where `faultRetire`/`exceptionPending` are computed (~line 213, 417), the head's effective fault = its own `faultedStore(h0)` OR (`needsSupStore(h0) && !exc.ss.s`). Add a combinational privilege-violation signal and fold it into the fault path WITHOUT changing the static `faultedStore` (the op didn't statically fault). Cleanest:

```scala
    // Privilege violation: a needsSupervisor head retiring while committed S==0 takes a
    // vector-8 (format-$0) exception. The op does NOT commit its result (precise).
    val privViolation = headReady && needsSupStore(h0) && !exc.ss.s && excIdle
```

Then make the fault path see it. `faultRetire` (line 213) and `exceptionPending`/`exceptionVector`/`exceptionPc` must reflect a privilege violation:

```scala
    val faultRetire = headReady && (faultedStore(h0) || privViolation) && excIdle
    ...
    exceptionPending := faultRetire
    exceptionVector  := Mux(privViolation && !faultedStore(h0), U(8, 8 bits), faultVecStore(h0))
    exceptionPc      := Mux(privViolation && !faultedStore(h0), pcStore(h0), faultPcStore(h0))
```

AND gate `retire0` (line 239) so a privilege-violating head does NOT normally commit: add `&& !privViolation`. AND the interrupt-pending check (line 501) already requires `!faultedStore(h0)`; ALSO require `!privViolation` (a pending privilege fault has priority over an interrupt). AND `excEntryVector`/`excEntryPc` (line 474-475) derive from `faultVecStore(h0)`/`faultPcStore(h0)` — they must use the priv-violation vector/pc when `privViolation`. Refactor so `excEntryVector := Mux(interruptPending, interruptVec, exceptionVector)` and `excEntryPc := Mux(interruptPending, interruptPc, exceptionPc)` (reuse the muxed `exceptionVector`/`exceptionPc`).

NOTE: a privilege violation is format-$0 (vector 8 is not is2/is7/interrupt in ExceptionUnit) — confirmed: ExceptionUnit's `is2 = vector in {5,6,7}`, `is7 = vector 2`, so vector 8 -> format-$0. The PC stacked for a privilege violation = the FAULTING instruction's PC (`pcStore(h0)`), restartable (the handler may emulate) — matches Musashi (privilege violation stacks the offending instruction PC, format-$0). Confirm against the oracle in lock-step.

- [ ] **Step 2.5: Verify the new combinational priv path doesn't create a long arc / cycle.** `exc.ss.s` is a committed register read; `needsSupStore(h0)` is a Mem read at the head — both are already on the retire cone (faultedStore(h0) is). No new deep arc. Compile and check no comb loop.

Run: `JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt "Test/compile" 2>&1 | tee /home/qwertyoruiop/tmp/compile6b.log`
Expected: clean.

- [ ] **Step 3: Lock-step MOVE-from-SR (supervisor) + privilege trap (user).** Two directed programs:
  1. SUPERVISOR (S=1, the reset SR=0x2700): `MOVE SR,Dn` / `MOVE SR,(An)` — assert the written word == the full 16-bit SR (system byte + CCR), 0 diverged.
  2. USER (enter user mode first — e.g. via an RTE/MOVE-to-SR in the harness setup that drops S, OR the harness's existing way to start in user mode; check how the exception lock-step sets user mode): `MOVE SR,Dn` in user mode -> a PRIVILEGE VIOLATION (vector 8), handler entry, frame stacked at SSP, RTE returns. Assert 0 diverged through the trap (the exception subsystem delivers vector 8).

Run: `JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt "testOnly *LockStep* -- -z MoveFromSr" 2>&1 | tee /home/qwertyoruiop/tmp/ls_mfsr.log`
Expected: 0 diverged (both supervisor read + user-mode privilege trap).

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
lea-pea-ccr: ROB privilege-violation check (MOVE-from-SR vector-8 when committed S==0)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: Full validation + ≥200 OOC synth gate

**Files:** none (validation only).

- [ ] **Step 1: Full decode/parity/fastTest sweep** (each its own JVM):

```bash
JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt "testOnly *OperationDecoderSpec" 2>&1 | tee /home/qwertyoruiop/tmp/final_opdec.log
JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt "testOnly *PredecodeWordSpec" 2>&1 | tee /home/qwertyoruiop/tmp/final_predec.log
JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt "testOnly *MicroOpAssemblerSpec" 2>&1 | tee /home/qwertyoruiop/tmp/final_asm.log
JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt fastTest 2>&1 | tee /home/qwertyoruiop/tmp/final_fast.log
```

Expected: all PASS. The 65536-opword PredecodeWordSpec parity MUST be green (every new opword framed).

- [ ] **Step 2: Full lock-step ×2 (two seeds)** to catch uninitialized-Reg flakiness:

```bash
JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt "testOnly *LockStep*" 2>&1 | tee /home/qwertyoruiop/tmp/final_ls1.log
# re-run with a different seed per the harness's seed mechanism
```

Expected: 0 diverged across all directed + free-running programs.

- [ ] **Step 3: ≥200 OOC synth gate.** Run `pgrep -af vivado` FIRST. If a vivado is already running (Track D may be gating), WAIT for it to finish — NEVER run 2 vivados, NEVER Verilator + vivado together.

```bash
pgrep -af vivado   # must be empty before proceeding
JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt "runMain m68k040.top.GenFullCoreSynthVerilog" 2>&1 | tee /home/qwertyoruiop/tmp/final_gen.log
timeout 1800 vivado -mode batch -source synth/ooc_M68kFullCoreSynth.tcl 2>&1 | tee /home/qwertyoruiop/tmp/final_vivado.log
grep -iE "Slack|Fmax|WNS|Timing" /home/qwertyoruiop/tmp/final_vivado.log | tail
```

Expected: FMax ≥ 200 MHz. Record the achieved FMax + worst path.

- [ ] **Step 4: Final commit (if any synth-driven tweak was needed)** — otherwise no-op. Record the final SHA.

---

## Self-Review notes (spec coverage)

- LEA (control + PC-rel + indexed; reg/An/imm/(An)+/-(An) illegal): Task 2 (decode), 3 (framing), 5a (crack + LS address-gen). ✓
- PEA (compute EA -> push; verify value + A7): Task 5b. ✓
- MOVE-from-SR (privileged, vector 8 when S==0, reads srSys): Task 6a (read) + 6b (privilege). ✓
- MOVE-from-CCR (CCR byte ZX -> EA): Task 4b. ✓
- MOVE-to-CCR (EA src .W low byte -> NZVCX, reuse toCcr write): Task 4a. ✓
- Reconcile-lesson (every builder assigns new fields): Task 1. ✓
- DO-NOT-touch (MOVE-to-SR / MOVEC / MOVES / STOP / RESET / RTD / MOVE-USP): untouched; Task 2 explicitly leaves 0x46C0 illegal. ✓
- Validation (lock-step incl privilege trap, decode/parity/fastTest, ≥200 gate): Task 6b, 7. ✓
```
