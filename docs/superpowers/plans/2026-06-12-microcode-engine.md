# Microcode Engine (v1: straight-line) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a ROM-driven straight-line microcode engine in DecodeStage (a generalization of the MOVEM micro-sequencer) and prove it by implementing the deferred >3-uop `ABCD/SBCD/ADDX/SUBX -(Ay),-(Ax)` MEMORY forms as its first customers.

**Architecture:** `OperationDecoder` classifies a cold opcode as `microcoded` + emits a `ucEntry` (entry uPC). A NEW `decode/Microcode.scala` holds a compile-time Scala table of uop *descriptors* (template + operand selectors + sequencing) and a HARDWARE resolver that, given the latched instruction fields (Ay/Ax/Dn ids, op kind, size) and a uPC, builds the resolved `DecodedUop` for that step. `DecodeStage` gains a ucode SEQUENCER -- a register-walked uPC that emits 1 uop/cycle (v1) via the SAME `pushProduced` machinery MOVEM uses, holds `fed` until `isLast`, and is aborted by `pipeFlush`. Mutually exclusive with the fast-crack head AND the MOVEM FSM.

**Tech Stack:** SpinalHDL (Scala-elaborated hardware), sbt + Verilator for sim/specs, Vivado OOC for the >=200 MHz FMax gate, Musashi as the lock-step oracle.

**Track ownership (STAY IN LANE):** own NEW `decode/Microcode.scala`, the ucode sequencer region of `decode/DecodeStage.scala`, and the cold-opcode flagging in `decode/OperationDecoder.scala` for **line-8/9/C/D only**. Also the line-8/9/C/D predecode framing in `frontend/PredecodeWord.scala` (the BCD/ADDX mem forms must frame len=1). Do NOT touch line-4 (MOVEC/MOVES/STOP are a later slice), `decode/EaDecoder.scala`, or the AGU (Track A owns those). The controller reconciles `OperationDecoder`/`DecodeStage`/`PredecodeWord` overlap with Track A at merge.

---

## Background the engineer needs

### Instruction semantics (68040 ISA + Musashi)

`ABCD -(Ay),-(Ax)` (line-C, opmode 4, EA mode field 001): both operands PREDECREMENT memory.
- `Ay := Ay - 1; Ax := Ax - 1; (Ax).B := BCD_add( (Ax) + (Ay) + X )` (byte-only).
- `SBCD -(Ay),-(Ax)` (line-8, opmode 4): BCD subtract `(Ax) - (Ay) - X`.
- A7-byte rule: if Ay or Ax is A7, predecrement BY 2 (keep stack even) though only the low byte is accessed. The engine computes this from size + A7-ness.

`ADDX -(Ay),-(Ax)` (line-D, opmode 4/5/6 = .B/.W/.L, EA mode field 001) / `SUBX` (line-9):
- `Ay := Ay - sz; Ax := Ax - sz; (Ax).sz := (Ax) +/- (Ay) +/- X`, sz in {1,2,4}. A7 .B -> predec 2.

### The uop sequence (6 uops -- this is WHY it is ucode, not a <=3 fast crack)

```
uPC0: LOAD.sz  (Ay)  -> T0     [eaAuto=PREDEC Ay, addr=Ay-delta; writes T0]
uPC1: ADD.L    Ay - delta -> Ay   [Ay predec write-back; dropped crack uop (divIsRem)]
uPC2: LOAD.sz  (Ax)  -> T1     [eaAuto=PREDEC Ax, addr=Ax-delta; writes T1]
uPC3: ADD.L    Ax - delta -> Ax   [Ax predec write-back; dropped crack uop (divIsRem)]
uPC4: <BCD|ADDX|SUBX>.sz  srcA=T1, srcB=T0 -> T2   [+ NZVCX/X]
uPC5: STORE.sz T2 -> (Ax)      [eaAuto=NONE: Ax already decremented at uPC3; isLast]
```

Rationale:
- A LOAD writes its loaded value to its int dst, so the predec An write-back CANNOT ride the load. It rides a separate ADD uop -- exactly `MicroOpAssembler.anUpdUop` (ADD.L `An := An +/- delta` with `divIsRem` set so the commit observation is dropped while the An write lands in the PRF and is verified by a later reader). This is established precedent (crackLoad's srcAuto path + LINK A7-fold).
- The LOAD carries `eaAuto := PREDEC` + `eaDelta` so the LS-EU computes addr `An - delta` (same as the existing predec load); it does NOT write An (int dst = loaded value).
- The op uop (uPC4): srcA=T1 (dst byte / `dx`), srcB=T0 (source byte / `dy`). Confirmed in `AluEuPlugin.scala`: `dx = s1Src1[7:0]` (srcA), `dy = s1Src2[7:0]` (srcB); the `.B/.W` merge preserves `s1Src1[31:8]` (harmless: T2 is only stored at the op size). The op writes NZVCX (the macro's flag producer), reads X + old-Z (clear-only Z, the ADDX/BCD rule).
- The STORE (uPC5): accesses `(Ax)` with eaAuto=NONE (Ax already decremented at uPC3). `isLast` releases `fed`. It is the architectural commit carrying the real pc/nextPc.

### MOVEM FSM (what we generalize) -- `decode/DecodeStage.scala` ~127-401

Bespoke FSM: `movemActive` Reg gate; latches mask/base/dir/size on `movemBegin`; each cycle priority-extracts the lowest 1-2 mask bits, drives `pushProduced.uops(0..1)` + count; holds `fed.ready := False` while active (`movemHoldsFed`); on mask-drain emits a final An-update uop then clears `movemActive`; `pipeFlush` clears `movemActive` (LAST when-block). The ucode engine is the SAME shape but ROM-driven: `ucActive` Reg + `ucPc` Reg walking a Scala table, emitting `resolve(rom(ucPc), ctx)` each cycle until the `isLast` descriptor.

### Why these forms are currently illegal (`MicroOpAssembler.scala`)

`aluRmwMemBad` (~603) forces the X-mem forms illegal unless the EA is MEMSIMPLE. The `-(Ay),-(Ax)` forms have EA mode field 001 (An-direct) -> NOT MEMSIMPLE -> `bad` -> illegal. The engine takes them over BEFORE this gate via `microcoded` (assembler crack suppressed, MOVEM-style).

### Operand-selector vocabulary (ROM mux primitives)

| Selector | Resolves to (hardware) |
|---|---|
| `SAy` | `8 + op[2:0]`  (source An / Ay) |
| `SAx` | `8 + op[11:9]` (dest An / Ax) |
| `ST0` `ST1` `ST2` | engine temps (arch ids 16/17/18) |
| `SNegDeltaAy` / `SNegDeltaAx` | `-deltaAy` / `-deltaAx` (predec write-back imm, LONG) |
| `SNone` | unused slot |

deltaAn = `(size==BYTE && An==A7) ? 2 : sizeBytes`. Computed in the engine from size + A7-ness (NOT via EaDecoder -> zero Track-A coupling).

---

## File Structure

- **Create** `src/main/scala/m68k040/decode/Microcode.scala` -- ucode ROM (Scala descriptor table + selector enum) + hardware resolver `resolve(desc, ctx, valid) : DecodedUop` + entry constant. One responsibility: define the ROM and turn a descriptor + latched context into a DecodedUop.
- **Modify** `src/main/scala/m68k040/decode/DecodeContracts.scala` -- add `microcoded: Bool` + `ucEntry: UInt(4 bits)` to `OpSpec` (+ defaults).
- **Modify** `src/main/scala/m68k040/decode/OperationDecoder.scala` -- line-8/9/C/D: classify the `-(Ay),-(Ax)` BCD/ADDX/SUBX mem forms as `microcoded` + `ucEntry` (NON-illegal placeholder).
- **Modify** `src/main/scala/m68k040/decode/DecodeStage.scala` -- the ucode sequencer Area + routing/fed.ready/pushProduced overrides.
- **Modify** `src/main/scala/m68k040/decode/MicroOpAssembler.scala` -- suppress the normal crack for a microcoded opword; exclude it from `aluRmwMemBad`.
- **Modify** `src/main/scala/m68k040/frontend/PredecodeWord.scala` -- frame the mem forms (mode 001) simple len 1.
- **Test (new)** `src/test/scala/m68k040/decode/MicrocodeSpec.scala` -- sequencer emission tests (model: `MovemDecodeSpec`).
- **Test (modify)** `OperationDecoderSpec`, `BcdDecodeSpec`, `AddxSubxDecodeSpec`, `PredecodeWordSpec` -- flip "mem form illegal/deferred" to "microcoded + frames len 1".
- **Test (modify)** `ExecuteLockStepSpec` -- full-core Musashi lock-step + checkMem for the 4 mem forms.

---

## Conventions / commands

- `~/sbt/bin/sbt` NOT on PATH -- full path always.
- Each `-z` subset its OWN JVM: `JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt 'testOnly <FQCN> -- -z "<substr>"'`.
- Commit after each task (branch `feat/microcode-engine`). Footer: `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`.
- >=200 OOC gate (final): `pgrep -af vivado` FIRST (never 2 vivados; never Verilator+vivado concurrently -- WAIT). Then `runMain m68k040.top.GenFullCoreSynthVerilog` + `vivado -mode batch -source synth/ooc_M68kFullCoreSynth.tcl`.

---

## Task 1: OpSpec gains `microcoded` + `ucEntry`

**Files:** Modify `src/main/scala/m68k040/decode/DecodeContracts.scala` (`OpSpec` ~67-112, `illegalDefault` ~114-131)

- [ ] **Step 1:** After `movemSizeLong` (~111) add:
```scala
  // Microcode engine routing (v1 straight-line). A cold opcode whose uop stream
  // exceeds the <=3-uop fast budget is emitted by the DecodeStage ucode sequencer.
  // OperationDecoder sets microcoded + ucEntry (entry uPC into the Microcode ROM);
  // the sequencer walks the ROM from ucEntry. Kept NON-illegal (MOVEM-style placeholder).
  val microcoded = Bool()
  val ucEntry    = UInt(4 bits)
```
- [ ] **Step 2:** In `illegalDefault`, after the movem defaults (~129): `o.microcoded := False; o.ucEntry := 0`
- [ ] **Step 3:** Run: `JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt 'Test/compile'` -- Expected: SUCCESS.
- [ ] **Step 4:** Commit `DecodeContracts.scala` (`microcode: OpSpec gains microcoded + ucEntry routing fields`).

---

## Task 2: The Microcode ROM + hardware resolver (`Microcode.scala`)

**Files:** Create `src/main/scala/m68k040/decode/Microcode.scala`

- [ ] **Step 1:** Create the file with: the `Sel`/`UOp`/`Mem`/`Auto` Scala enums; `Desc` case class (uop, mem, auto, srcA, srcB, dst, useImm, imm, writesFlags, isFirst, isLast); the `rom: Vector[Desc]` (the 6-row BCD/ADDX/SUBX sequence above) + `BCD_MEM_ENTRY=0` + `romSize`; `T0=MicroOpAssembler.T0`, `T1=MicroOpAssembler.T1`, `T2=18`; a `Ctx()` Bundle (opword, pc, nextPc, op:DecOp, bcdSub, size, sizeBytesLog:UInt(2)); and `resolve(d, ctx, valid): DecodedUop` that drives EVERY DecodedUop field exactly once (mirror `movemMoveUop`'s fully-defaulted shape). The reference implementation is in this plan's design section / the controller's notes -- key field rules:
  - `op`: UMove->MOVE, UAddDrop->ADD, UOpFromCtx->ctx.op.
  - `cluster`: MNone->INT else LS. `size`: UAddDrop->LONG else ctx.size. `memOp` per Mem.
  - selectors resolve to (reg, valid): SAy=`8+op[2:0]`, SAx=`8+op[11:9]`, ST0/1/2 = 16/17/18.
  - `useImm`/`imm`: imm selectors SNegDeltaAy/SNegDeltaAx = `-deltaAn` (LONG), deltaAn = `(size==BYTE && An==A7)?2:1<<sizeBytesLog`.
  - flags: `readsNzvc=readsX=writesNzvc=writesX = d.writesFlags`.
  - `divIsRem = (uop==UAddDrop)` (dropped An write-backs).
  - `eaAuto`: ANoAuto->NONE; APredecAy/APredecAx->PREDEC with `eaDelta = deltaBytes(An)` (3-bit, A7-byte=2).
  - `bcdSub := ctx.bcdSub`; `firstOfInstr := d.isFirst`; all other fields False/0/pc as in movemMoveUop.
- [ ] **Step 2:** Run: `JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt 'Test/compile'` -- Expected: SUCCESS.
- [ ] **Step 3:** Read `src/main/scala/m68k040/execute/LsEuPlugin.scala`: CONFIRM a PREDEC load whose int dst is T0 (not An) writes ONLY T0 (the An write-back rides the separate ADD). Precedent: `crackLoad` srcAuto + `anUpdUop`. If the LS-EU unconditionally writes An on PREDEC, drop uPC1/uPC3 and fold An onto the load -- and report the deviation.
- [ ] **Step 4:** Commit `Microcode.scala` (`microcode: ROM descriptor table + hardware resolver`).

---

## Task 3: OperationDecoder classifies the mem forms as microcoded

**Files:** Modify `OperationDecoder.scala` (line-8/9/B/C/D block ~285-439); Test `BcdDecodeSpec`, `AddxSubxDecodeSpec`, `OperationDecoderSpec`.

Reg forms: BCD `opword(7 downto 3)==="00000"`; ADDX/SUBX `srcMode===000`. MEM forms (bit3=1): BCD `opword(7 downto 3)==="00001"` (opmode 4 only); ADDX/SUBX `eaMode===001` opmode 4/5/6.

- [ ] **Step 1:** In `BcdDecodeSpec.scala`, REPLACE the two "memory-form ... illegal/deferred" tests (~126-143) with microcoded assertions: `!dut.o.illegal`, `dut.o.microcoded`, `dut.o.op==DecOp.BCD`, `dut.o.bcdSub` matches line (False for 0xC109 ABCD, True for 0x8109 SBCD). Reuse the spec's existing decode helper (read the file head for its exact name).
- [ ] **Step 2:** Run: `... 'testOnly m68k040.decode.BcdDecodeSpec -- -z "memory form"'` -- Expected: FAIL.
- [ ] **Step 3:** In `OperationDecoder.scala`, add `isBcdMem = (line==0x8||0xC) && opword(8) && (opword(7 downto 3)===B"5'b00001")` and `isAddxSubxMem = isRmw && (line==0x9||0xD) && (eaMode===B"001")`. Add dispatch arms BEFORE `isBcdReg`/`isAddxSubx` (so the `.elsewhen` chain catches mem first):
```scala
        when(isBcdMem) {
          o.illegal := False; o.microcoded := True
          o.ucEntry := U(Microcode.BCD_MEM_ENTRY, 4 bits)
          o.op := DecOp.BCD; o.bcdSub := (line === 0x8); o.size := Size.BYTE
          o.readsNzvc := True; o.writesNzvc := True; o.readsX := True; o.writesX := True
        } .elsewhen(isAddxSubxMem) {
          o.illegal := False; o.microcoded := True
          o.ucEntry := U(Microcode.BCD_MEM_ENTRY, 4 bits)
          o.op := Mux(line === 0xD, DecOp.ADDX, DecOp.SUBX)
          when(opmode === 4) { o.size := Size.BYTE }
            .elsewhen(opmode === 5) { o.size := Size.WORD } .otherwise { o.size := Size.LONG }
          o.readsNzvc := True; o.writesNzvc := True; o.readsX := True; o.writesX := True
        } .elsewhen(isBcdReg) { /* existing unchanged */ }
```
  (`Microcode` is same-package -- no import. Leave srcA/srcB/dst NONE for the mem form so the assembler's `usesSrcEa` stays False.)
- [ ] **Step 4:** Run: `... 'testOnly m68k040.decode.BcdDecodeSpec -- -z "memory form"'` -- Expected: PASS.
- [ ] **Step 5:** Add ADDX.L (0xD389) + SUBX.B (0x9101) mem microcoded tests to `AddxSubxDecodeSpec.scala`; run the whole spec -- Expected: PASS (new + reg-form).
- [ ] **Step 6:** Run `OperationDecoderSpec` -- Expected: PASS (flip any mem-illegal assertion to microcoded; never delete coverage).
- [ ] **Step 7:** Commit (`microcode: OperationDecoder classifies BCD/ADDX/SUBX -(Ay),-(Ax) as microcoded`).

---

## Task 4: PredecodeWord frames the mem forms (len 1)

**Files:** Modify `PredecodeWord.scala` (line-8/9/C/D band ~404-432); Test `PredecodeWordSpec`.

- [ ] **Step 1:** Add a `PredecodeWordSpec` test: opwords 0xC109, 0x8109, 0xD389, 0x9101 each frame `simple && lenWords==1` (reuse the spec's existing predecode helper).
- [ ] **Step 2:** Run: `... 'testOnly m68k040.frontend.PredecodeWordSpec -- -z "memory forms"'` -- Expected: FAIL (unframed, len 0).
- [ ] **Step 3:** In the `elsewhen(opmode === 4/5/6)` arm, WIDEN the reg-form framing to also catch mode 001: ADDX/SUBX `(cls==9||0xD) && (srcMode===0 || srcMode===1)`; BCD `(cls==8||0xC) && opmode===4 && (srcMode===0 || srcMode===1)`. When matched -> `simple, lenWords=1`; otherwise -> the existing `memDestExt` path (handles opmode-5/6 mode-010 `(An)` RMW). CAUTION: mode 001 is An-DIRECT (never a valid ADD/SUB mem dest) so it is unambiguously the X-mem form across opmodes 4/5/6; `ADD.B Dn,(An)` is mode 010, NOT swallowed.
- [ ] **Step 4:** Run: `... 'testOnly m68k040.frontend.PredecodeWordSpec'` -- Expected: PASS (new + existing).
- [ ] **Step 5:** Commit (`microcode: predecode frames BCD/ADDX/SUBX -(Ay),-(Ax) mem forms (len 1)`).

---

## Task 5: The DecodeStage ucode SEQUENCER

**Files:** Modify `DecodeStage.scala` (ucode Area + routing); Modify `MicroOpAssembler.scala` (suppress crack); Test `MicrocodeSpec` (new).

- [ ] **Step 1:** In `MicroOpAssembler.assemble`: (a) add `&& !spec.microcoded` to `aluRmwMemBad` (mode 001 + op now ADDX/SUBX would otherwise trip it); (b) make the sequence-selection chain's FIRST arm `when(spec.microcoded){ out.count:=1; out.uops(0/1/2):=opUop }` (benign placeholder the sequencer overrides); ensure `opUop.unimplemented` stays False for a microcoded op (it will, since spec.illegal False + usesSrcEa False).
- [ ] **Step 2:** Create `MicrocodeSpec.scala` (clone `MovemDecodeSpec`'s `Dut`/`collect`, reading eaAuto/eaDelta/divIsRem/srcA/srcB/dst/writesNzvc/writesX/first). Assert the 6-uop stream for `ABCD -(A1),-(A0)` (0xC109): uPC0 LOAD A1(9)->T0(16) PREDEC first; uPC1 ADD A1->A1 drop; uPC2 LOAD A0(8)->T1(17) PREDEC; uPC3 ADD A0->A0 drop; uPC4 BCD srcA=T1(17) srcB=T0(16)->T2(18) writesNzvc+writesX; uPC5 STORE srcB=T2->(A0=8) eaAuto NONE.
- [ ] **Step 3:** Run: `... 'testOnly m68k040.decode.MicrocodeSpec'` -- Expected: FAIL (no sequencer).
- [ ] **Step 4:** Implement the sequencer in `DecodeStage.scala`:
  - `slot0IsMicrocoded = fed.valid && spec0.microcoded` (spec0 already decoded ~215).
  - Regs: `ucActive RegInit(False)`, `ucPc Reg(UInt(4))`, `ucCtx Reg(Microcode.Ctx())`; `when(pipeFlush){ucActive:=False}`.
  - `ucBegin = !ucActive && !movemActive && !movemPendValid && slot0IsMicrocoded && !stashValid`.
  - Build `ucEntryCtx` (opword/pc/nextPc from packet(0), op/bcdSub/size from spec0, sizeBytesLog = size.mux(BYTE->0,WORD->1,default->2)).
  - `ucResolved = Vec(rom.map(d => Microcode.resolve(d, ucCtx, True)))`; `ucLastVec = Vec(rom.map(d => Bool(d.isLast)))`; index by `ucPc.resize(log2Up(romSize))` -> `ucCurUop`, `ucCurLast`.
  - Extend the `pushProduced` driver to 3-way: `when(movemActive){...} elsewhen(ucActive){ valid:=True; uops(0..3):=ucCurUop; count:=1 } otherwise {normal}`.
  - `ucReleaseFed = ucActive && ucCurLast && pushProduced.ready`; `ucHoldsFed = ucActive || slot0IsMicrocoded`. Extend `fed.ready` to `(!stashValid && !movemHoldsFed && !slot0IsMovem && !ucHoldsFed && pushProduced.ready) || movemEnterSlot0 || ucReleaseFed`.
  - Exclude a microcoded slot0 from the normal head + normal consume: `normalHeadValid && !slot0IsMicrocoded`; guard the normal `fed.ready`/stash arm and `movemBegin`/`movemEnterSlot0` with `&& !slot0IsMicrocoded`.
  - Transitions: `when(ucBegin){ ucActive:=True; ucPc:=spec0.ucEntry; ucCtx:=ucEntryCtx; when(slot1Valid){ stash slot1 (a1raw) } } elsewhen(ucActive){ when(pushProduced.ready){ when(ucCurLast){ucActive:=False} otherwise {ucPc:=ucPc+1} } }`. `when(pipeFlush){ucActive:=False}` LAST.
  - v1 LIMIT (document in code): a microcoded op immediately followed by another microcoded/MOVEM op in the SAME fetch group is the untested edge (the stash carries a NORMAL slot1; the tested programs put a normal instr / NOP after each X-mem op).
- [ ] **Step 5:** Run: `... 'testOnly m68k040.decode.MicrocodeSpec'` -- Expected: PASS.
- [ ] **Step 6:** Run `MovemDecodeSpec DecodeStageSpec MicroOpAssemblerSpec` -- Expected: PASS (no regression).
- [ ] **Step 7:** Commit (`microcode: DecodeStage ROM-driven sequencer (BCD/ADDX/SUBX -(Ay),-(Ax) 6-uop)`).

---

## Task 6: Full-core Musashi lock-step (the mem forms)

**Files:** Modify `ExecuteLockStepSpec.scala` (near the ABCD/SBCD/ADDX register tests ~1093-1348 + predec-store ~2912; update the ~2888 "deferred" comment).

Harness: `runLockStep(name, src, nInstr, checkMem)` -- assembles `src` with Musashi (oracle), runs the DUT, compares per-instruction commits incl. the FULL CCR (N/Z/V/C/X). `checkMem=Seq(addr)` compares stored bytes. Read the existing ABCD-register test for the exact `src` overload (Seq[String] vs single String) + address region (the predec-store tests use 0x3000+).

- [ ] **Step 1:** Add `ABCD-mem`: seed A0/A1 above two data bytes, store the operand bytes via `move.b Dn,-(An)` then reset An, set X via `addi.b #0,Dn`, `abcd -(%a1),-(%a0)`, then `move.l %a0,%d3 ; move.l %a1,%d4` (RAW on the An updates), `.stop: bra .stop`. `checkMem=Seq(<dst addr>)`. Verify the result byte + A0/A1 + full CCR vs Musashi.
- [ ] **Step 2:** Run: `... 'testOnly m68k040.lockstep.ExecuteLockStepSpec -- -z "ABCD-mem"'` -- Expected: PASS (0 diverged, full CCR incl N/V). If diverged, use systematic-debugging (NEVER weaken the test): likely srcA/srcB swap (dx/dy) or store size/addr.
- [ ] **Step 3:** Add `SBCD-mem` (line-8 subtract), `ADDX-mem` (.L, 4-byte operands, checkMem the span), `SUBX-mem` (.B/.W). Cover: no-carry, carry/borrow (sets X), and the A7-even-byte rule (`-(%a7)` .B predec 2) -- if A7-as-SP collides with the SSP boot, cover the A7-even rule via a MicrocodeSpec `eaDelta==2` assertion instead and document.
- [ ] **Step 4:** Run each mem subset in its OWN JVM (`-z "ABCD-mem"`, `"SBCD-mem"`, `"ADDX-mem"`, `"SUBX-mem"`) -- Expected: all PASS.
- [ ] **Step 5:** Run the REGISTER-form subsets (`-z "ABCD"`, `-z "ADDX"`) -- Expected: PASS (no regression; the substring also catches the mem tests, fine).
- [ ] **Step 6:** Update the stale ~2888 comment: the ADDX/SBCD/ABCD memory predec forms are NOW implemented via the ucode engine (ref `Microcode.scala`), not deferred.
- [ ] **Step 7:** Commit (`microcode: full-core Musashi lock-step for BCD/ADDX/SUBX -(Ay),-(Ax) (full CCR + mem + An)`).

---

## Task 7: Regression sweep + fastTest

**Files:** none (execution only)

- [ ] **Step 1:** Non-ucode lock-step subsets, each own JVM: `-z "mixed"`, `-z "predec"`, `-z "postinc"` -- Expected: PASS.
- [ ] **Step 2:** fastTest: `... 'testOnly m68k040.decode.* m68k040.frontend.PredecodeWordSpec'` (or the `fastTest` sbt alias if defined in build.sbt) -- Expected: PASS.
- [ ] **Step 3:** Commit any test-only fixups (or `nothing to commit`).

---

## Task 8: The >=200 MHz OOC synth gate

**Files:** none (synth only)

- [ ] **Step 1:** `pgrep -af vivado` FIRST. If any vivado runs, WAIT (never 2 vivados / never Verilator+vivado). Re-check until clear.
- [ ] **Step 2:** `JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt 'runMain m68k040.top.GenFullCoreSynthVerilog'` -- Expected: SUCCESS.
- [ ] **Step 3:** Re-confirm `pgrep -af vivado` clear, then `vivado -mode batch -source synth/ooc_M68kFullCoreSynth.tcl` -- Expected: FMax >= 200 MHz. Capture FMax + worst-path endpoints.
- [ ] **Step 4:** Record FMax + worst path. If <200: check whether the `ucResolved` Vec mux limits (tiny 6-row ROM -> unlikely); if so register the resolved uop (latency-agnostic like MOVEM). Do NOT lower the gate.
- [ ] **Step 5:** Final commit of any tracked synth artifacts (or `nothing to commit`).

---

## Self-review checklist

- **Spec coverage:** engine skeleton (T2/T5), ROM descriptor format + selector vocab (T2), DecodeStage routing/sequencer (T5), generalizes MOVEM (T5 mirrors the FSM), BCD/ADDX-mem first customers (T3/T5/T6), lock-step full CCR incl N/V (T6), decode/predecode/fastTest (T3/T4/T7), >=200 gate (T8). OK
- **No test weakening:** every "deferred/illegal" assertion FLIPPED to a positive microcoded/len-1/lock-step assertion -- coverage grows. OK
- **Type consistency:** `microcoded`/`ucEntry` (OpSpec) used identically in OperationDecoder + DecodeStage; `Microcode.BCD_MEM_ENTRY/Ctx/resolve/rom/romSize/T0/T1/T2` defined T2, consumed T5. EaAuto.PREDEC/NONE, MemOp.LOAD/STORE, DecOp.BCD/ADDX/SUBX/ADD/MOVE, Size.BYTE/WORD/LONG existing. OK

## Known v1 expressiveness boundary (report this)

v1 microcode is STRAIGHT-LINE (nextUpc = uPC+1, single `isLast`); no data-dependent loop/branch. The BCD/ADDX-mem 6-uop sequence is fixed-length -> expressible cleanly. Data-dependent cold ops (MOVEM mask loop, bit-field, CAS) need the v2 loop primitive and stay bespoke. If the straight-line ROM cannot express the BCD-mem sequence cleanly (e.g. the predec An write-back cannot be a separate dropped ADD), REPORT exactly why instead of hacking around it.
