# Decode Matrix Slice 1 — Table-Driven Reg/Imm Decode Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the hand-written `SimpleDecodeUnit` with the decode-matrix framework — an EA-agnostic table-driven `OperationDecoder`, an opcode-agnostic `EaDecoder`, and a `MicroOpAssembler` — covering exactly today's op set (MOVEQ, MOVE.B/W/L, Bcc/BSR/BRA, ADD/SUB/AND/OR/CMP + ADDA/SUBA/CMPA) over register-direct and immediate addressing modes only, with identical results (lock-step unchanged).

**Architecture:** Two decoupled combinational decoders — `OperationDecoder.decode(opword) → OpSpec` (a line-grouped masked-pattern table that never inspects the EA field bits 5–0) and `EaDecoder.decode(eaField, size, words) → EaSpec` (mode+reg → data-reg / addr-reg / immediate, opcode-agnostic) — combined by `MicroOpAssembler.assemble(opspec, pkt) → DecodedUop` (one µop per instruction in this slice, no temps, no memory). `DecodeStage` calls the assembler instead of `SimpleDecodeUnit`. The output `DecodedUop` contract is unchanged, so rename and the whole backend are untouched.

**Tech Stack:** SpinalHDL 1.14.1 / Scala 2.13 / sbt at `~/sbt/bin/sbt` (NOT on PATH) / Verilator / Vivado (xcku5p-ffvb676-2). Masked patterns use SpinalHDL's `M"...."` `MaskedLiteral`. Tests wrap the combinational decode in a tiny `Component` and poke (mirror `SimpleDecodeUnitSpec`). The lock-step (`ExecuteLockStepSpec`) + full-core synth (`GenFullCoreSynthVerilog`) are the end gates.

**Branch:** create `feat/decode-matrix-1` off `master` before Task 1.

**Key facts (verified against the codebase):**
- `DecodedUop` fields (unchanged target contract): `valid, pc, op:DecOp, cluster:Cluster, size:Size, srcAReg:UInt(4)/srcAValid, srcBReg:UInt(4)/srcBValid, dstReg:UInt(4)/dstValid, useImm/imm:Bits(32), readsNzvc/readsX/writesNzvc/writesX, isBranch, cond:Bits(4), branchDisp:Bits(32), unimplemented`. Reg ids: D0–7 = 0–7, A0–7 = 8–15.
- `DecodePacket`: `valid, pc, words:Vec(Bits(16),5), wordCount:UInt(3), simple, lenWords:UInt(3), complex, fault`.
- `DecOp` enum: `MOVE, ADD, SUB, AND, OR, CMP, BRANCH, ILLEGAL`. `Size`: `BYTE, WORD, LONG`. `Cluster`: `INT, EA, LS, CPLX`.
- Existing operand conventions the new path MUST preserve (from `SimpleDecodeUnit` + `SimpleDecodeUnitSpec`): MOVE/MOVEQ put the source in **srcB** (ALU MOVE computes `result = src2`); ALU EA→Dn ops put dest-reg in **srcA** and EA operand in **srcB**; ADDA/SUBA/CMPA (opmode 3/7) likewise put An destination in **srcA** and EA source in **srcB**. The datapath computes `src1 op src2`, so this ordering is required for SUB/CMP/SUBA/CMPA. CMP/CMPA do not write a reg (`dstValid=false`) but set `writesNzvc`.
- m68k EA field = 6 bits: `mode = ea(5 downto 3)`, `reg = ea(2 downto 0)`. Reg-direct/imm modes only in this slice: `000`=Dn, `001`=An, `111`+reg `100`=`#imm`. All other modes → `EaClass.MEMSIMPLE`/`MEMCOMPLEX`/`ILLEGAL` (defined but unused → `unimplemented`).

---

### Task 1: Decode contracts (OpSpec / OperandSrc / EaSpec)

**Files:**
- Create: `src/main/scala/m68k040/decode/DecodeContracts.scala`
- Test: `src/test/scala/m68k040/decode/DecodeContractsSpec.scala`

- [ ] **Step 1: Write the failing test** (elaboration + default sanity)

```scala
package m68k040.decode

import m68k040.VerilatorTest
import m68k040.isa.Size
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

class DecodeContractsSpec extends AnyFunSuite {
  class Dut extends Component {
    val o = out(OpSpec())
    val e = out(EaSpec())
    o := OpSpec.illegalDefault()
    e := EaSpec.illegalDefault()
  }
  test("contracts elaborate; illegal defaults are sane", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      sleep(1)
      assert(dut.o.illegal.toBoolean)
      assert(dut.o.srcA.kind.toEnum == OperandKind.NONE)
      assert(dut.e.klass.toEnum == EaClass.ILLEGAL)
    }
  }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `~/sbt/bin/sbt "testOnly m68k040.decode.DecodeContractsSpec"`
Expected: FAIL to compile (`OpSpec`/`EaSpec` not defined).

- [ ] **Step 3: Implement the contracts**

Create `src/main/scala/m68k040/decode/DecodeContracts.scala`:

```scala
package m68k040.decode

import m68k040.isa.{Cluster, Size}
import spinal.core._

/** Where a backend operand slot sources its value. EA-agnostic: `EASRC`/`EADST`
  * mean "the operand encoded in the EA field" — register vs memory is the
  * EaDecoder's call. `REGFIELD` = a register named by a fixed opword field (Dn,
  * bits 11-9). `IMMQ` = the MOVEQ 8-bit signed immediate. */
object OperandKind extends SpinalEnum {
  val NONE, REGFIELD, EASRC, EADST, IMMQ = newElement()
}

case class OperandSrc() extends Bundle {
  val kind   = OperandKind()
  val isAddr = Bool()        // REGFIELD only: An (true) vs Dn (false)
  def setNone(): Unit = { kind := OperandKind.NONE; isAddr := False }
}

/** What an EA field decodes to (opcode-agnostic). This slice only produces the
  * register/immediate classes; the memory classes are reserved for later slices. */
object EaClass extends SpinalEnum {
  val DATAREG, ADDRREG, IMM, MEMSIMPLE, MEMCOMPLEX, ILLEGAL = newElement()
}

case class EaSpec() extends Bundle {
  val klass = EaClass()
  val reg   = UInt(4 bits)    // full reg id: Dn=0..7, An=8..15 (valid for DATAREG/ADDRREG)
  val imm   = Bits(32 bits)   // valid for IMM
}
object EaSpec {
  def illegalDefault(): EaSpec = {
    val e = EaSpec()
    e.klass := EaClass.ILLEGAL; e.reg := 0; e.imm := 0
    e
  }
}

/** EA-agnostic operation descriptor produced by the OperationDecoder. */
case class OpSpec() extends Bundle {
  val op       = DecOp()
  val size     = Size()
  val cluster  = Cluster()
  val srcA     = OperandSrc()
  val srcB     = OperandSrc()
  val dst      = OperandSrc()
  val dstWrites = Bool()                 // the dst slot is written back
  val readsNzvc = Bool();  val writesNzvc = Bool()
  val readsX    = Bool();  val writesX    = Bool()
  // MOVE writes NZVC only when its destination is a DATA register; the dst type
  // lives in the EA class, so the rule is delegated to the assembler.
  val writesNzvcIfDataDst = Bool()
  val isBranch = Bool();   val cond = Bits(4 bits)
  val illegal  = Bool()
}
object OpSpec {
  def illegalDefault(): OpSpec = {
    val o = OpSpec()
    o.op := DecOp.ILLEGAL; o.size := Size.WORD; o.cluster := Cluster.INT
    o.srcA.setNone(); o.srcB.setNone(); o.dst.setNone()
    o.dstWrites := False
    o.readsNzvc := False; o.writesNzvc := False
    o.readsX := False;    o.writesX := False
    o.writesNzvcIfDataDst := False
    o.isBranch := False;  o.cond := 0
    o.illegal := True
    o
  }
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `~/sbt/bin/sbt "testOnly m68k040.decode.DecodeContractsSpec"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/scala/m68k040/decode/DecodeContracts.scala src/test/scala/m68k040/decode/DecodeContractsSpec.scala
git commit -m "decode: OpSpec/OperandSrc/EaSpec contracts (decode-matrix framework)"
```

---

### Task 2: EaDecoder (Dn / An / #imm, opcode-agnostic)

**Files:**
- Create: `src/main/scala/m68k040/decode/EaDecoder.scala`
- Test: `src/test/scala/m68k040/decode/EaDecoderSpec.scala`

- [ ] **Step 1: Write the failing test**

```scala
package m68k040.decode

import m68k040.VerilatorTest
import m68k040.isa.Size
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import org.scalatest.funsuite.AnyFunSuite

class EaDecoderSpec extends AnyFunSuite {
  class Dut extends Component {
    val eaField = in Bits (6 bits)
    val size    = in(Size())
    val words   = in(Vec(Bits(16 bits), 5))
    val out0    = out(EaSpec())
    out0 := EaDecoder.decode(eaField, size, words)
  }
  def run(f: Dut => Unit): Unit = SimConfig.withVerilator.compile(new Dut).doSim { dut =>
    dut.words.foreach(_ #= 0); f(dut)
  }
  test("Dn -> DATAREG reg n", VerilatorTest) { run { dut =>
    dut.eaField #= 0x03 /*mode0 reg3*/; dut.size #= Size.LONG; sleep(1)
    assert(dut.out0.klass.toEnum == EaClass.DATAREG && dut.out0.reg.toInt == 3)
  }}
  test("An -> ADDRREG reg 8+n", VerilatorTest) { run { dut =>
    dut.eaField #= 0x0A /*mode1 reg2*/; dut.size #= Size.LONG; sleep(1)
    assert(dut.out0.klass.toEnum == EaClass.ADDRREG && dut.out0.reg.toInt == 10)
  }}
  test("#imm.W sign-extends from words(1)", VerilatorTest) { run { dut =>
    dut.eaField #= 0x3C /*mode7 reg4*/; dut.size #= Size.WORD; dut.words(1) #= 0xFFFE; sleep(1)
    assert(dut.out0.klass.toEnum == EaClass.IMM && dut.out0.imm.toLong == 0xFFFFFFFEL)
  }}
  test("#imm.L is words(1)##words(2)", VerilatorTest) { run { dut =>
    dut.eaField #= 0x3C; dut.size #= Size.LONG; dut.words(1) #= 0x1234; dut.words(2) #= 0x5678; sleep(1)
    assert(dut.out0.klass.toEnum == EaClass.IMM && dut.out0.imm.toLong == 0x12345678L)
  }}
  test("memory mode -> MEMSIMPLE (reserved, not reg/imm)", VerilatorTest) { run { dut =>
    dut.eaField #= 0x10 /*mode2 (An)*/; dut.size #= Size.LONG; sleep(1)
    assert(dut.out0.klass.toEnum == EaClass.MEMSIMPLE)
  }}
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `~/sbt/bin/sbt "testOnly m68k040.decode.EaDecoderSpec"`
Expected: FAIL to compile (`EaDecoder` not defined).

- [ ] **Step 3: Implement EaDecoder**

Create `src/main/scala/m68k040/decode/EaDecoder.scala`:

```scala
package m68k040.decode

import m68k040.isa.Size
import spinal.core._
import spinal.lib._

/** Opcode-agnostic effective-address decoder. This slice resolves the
  * register-direct and immediate modes fully; every memory mode is classified
  * (MEMSIMPLE/MEMCOMPLEX) but its fields are NOT produced here — memory cracking
  * is a later slice (see the decode-matrix design spec). */
object EaDecoder {
  def decode(eaField: Bits, size: Size.C, words: Vec[Bits]): EaSpec = {
    val e    = EaSpec()
    val mode = eaField(5 downto 3)
    val reg  = eaField(2 downto 0)

    // defaults
    e.klass := EaClass.ILLEGAL
    e.reg   := 0
    e.imm   := 0

    switch(mode) {
      is(0) { e.klass := EaClass.DATAREG; e.reg := reg.asUInt.resized }              // Dn
      is(1) { e.klass := EaClass.ADDRREG; e.reg := (U(8, 4 bits) + reg.asUInt).resized } // An
      is(2, 3, 4, 5, 6) { e.klass := EaClass.MEMSIMPLE }                              // (An)/(An)+/-(An)/(d16,An)/(d8,An,Xn)
      is(7) {
        switch(reg) {
          is(0, 1) { e.klass := EaClass.MEMSIMPLE }   // (xxx).W / (xxx).L
          is(2, 3) { e.klass := EaClass.MEMSIMPLE }   // (d16,PC) / (d8,PC,Xn)
          is(4) {                                      // #imm
            e.klass := EaClass.IMM
            when(size === Size.LONG) {
              e.imm := words(1) ## words(2)
            } otherwise {
              // byte uses the low 8 bits of the (sign-extended) word per m68k
              e.imm := words(1).asSInt.resize(32).asBits
            }
          }
          default { e.klass := EaClass.ILLEGAL }
        }
      }
    }
    e
  }
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `~/sbt/bin/sbt "testOnly m68k040.decode.EaDecoderSpec"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/scala/m68k040/decode/EaDecoder.scala src/test/scala/m68k040/decode/EaDecoderSpec.scala
git commit -m "decode: opcode-agnostic EaDecoder (Dn/An/#imm; memory modes classified, reserved)"
```

---

### Task 3: OperationDecoder (line-grouped masked-pattern table, EA-agnostic)

**Files:**
- Create: `src/main/scala/m68k040/decode/OperationDecoder.scala`
- Test: `src/test/scala/m68k040/decode/OperationDecoderSpec.scala`

- [ ] **Step 1: Write the failing test**

```scala
package m68k040.decode

import m68k040.VerilatorTest
import m68k040.isa.Size
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

class OperationDecoderSpec extends AnyFunSuite {
  class Dut extends Component {
    val opword = in Bits (16 bits)
    val o      = out(OpSpec())
    o := OperationDecoder.decode(opword)
  }
  def run(op: Int)(check: Dut => Unit): Unit =
    SimConfig.withVerilator.compile(new Dut).doSim { dut => dut.opword #= op; sleep(1); check(dut) }

  test("MOVEQ: MOVE/LONG, dst REGFIELD data, srcB IMMQ, writesNzvc", VerilatorTest) {
    run(0x7605) { dut =>
      assert(dut.o.op.toEnum == DecOp.MOVE && dut.o.size.toEnum == Size.LONG)
      assert(dut.o.dst.kind.toEnum == OperandKind.REGFIELD && !dut.o.dst.isAddr && dut.o.dstWrites.toBoolean)
      assert(dut.o.srcB.kind.toEnum == OperandKind.IMMQ && dut.o.writesNzvc.toBoolean && !dut.o.illegal.toBoolean)
    }
  }
  test("MOVE.W: srcB EASRC, dst EADST, writesNzvcIfDataDst", VerilatorTest) {
    run(0x3200) { dut =>
      assert(dut.o.op.toEnum == DecOp.MOVE && dut.o.size.toEnum == Size.WORD)
      assert(dut.o.srcB.kind.toEnum == OperandKind.EASRC)
      assert(dut.o.dst.kind.toEnum == OperandKind.EADST && dut.o.dstWrites.toBoolean)
      assert(dut.o.writesNzvcIfDataDst.toBoolean && !dut.o.writesNzvc.toBoolean)
    }
  }
  test("ADD.L EA->Dn: srcA REGFIELD(Dn), srcB EASRC, writesNzvc+X", VerilatorTest) {
    run(0xD081) { dut =>
      assert(dut.o.op.toEnum == DecOp.ADD && dut.o.size.toEnum == Size.LONG)
      assert(dut.o.srcA.kind.toEnum == OperandKind.REGFIELD && !dut.o.srcA.isAddr)
      assert(dut.o.srcB.kind.toEnum == OperandKind.EASRC)
      assert(dut.o.dst.kind.toEnum == OperandKind.REGFIELD && dut.o.dstWrites.toBoolean)
      assert(dut.o.writesNzvc.toBoolean && dut.o.writesX.toBoolean)
    }
  }
  test("CMP.L EA->Dn: no dst write, writesNzvc", VerilatorTest) {
    run(0xB081) { dut =>
      assert(dut.o.op.toEnum == DecOp.CMP && !dut.o.dstWrites.toBoolean && dut.o.writesNzvc.toBoolean)
    }
  }
  test("ADDA.L: opmode7, srcA REGFIELD(An destination), srcB EASRC, no flags", VerilatorTest) {
    run(0xD1C1) { dut =>
      assert(dut.o.op.toEnum == DecOp.ADD && dut.o.size.toEnum == Size.LONG)
      assert(dut.o.srcA.kind.toEnum == OperandKind.REGFIELD && dut.o.srcA.isAddr)
      assert(dut.o.srcB.kind.toEnum == OperandKind.EASRC)
      assert(dut.o.dst.kind.toEnum == OperandKind.REGFIELD && dut.o.dst.isAddr && dut.o.dstWrites.toBoolean)
      assert(!dut.o.writesNzvc.toBoolean && !dut.o.writesX.toBoolean)
    }
  }
  test("Bcc: isBranch, cond, readsNzvc for cond>=2", VerilatorTest) {
    run(0x6700) { dut => assert(dut.o.isBranch.toBoolean && dut.o.cond.toInt == 0x7 && dut.o.readsNzvc.toBoolean) }
  }
  test("RMW form (ADD Dn->EA, opmode4) -> illegal in this slice", VerilatorTest) {
    run(0xD181) { dut => assert(dut.o.illegal.toBoolean) }
  }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `~/sbt/bin/sbt "testOnly m68k040.decode.OperationDecoderSpec"`
Expected: FAIL to compile (`OperationDecoder` not defined).

- [ ] **Step 3: Implement OperationDecoder**

Create `src/main/scala/m68k040/decode/OperationDecoder.scala`. The decode is grouped by the line nibble `opword(15 downto 12)`; within a line, masked-pattern `when`s select the op. The decoder NEVER reads `opword(5 downto 0)` (the EA field) to classify the operation — it only assigns `EASRC`/`EADST` roles. (The MOVE dst-mode bits 6–8 and ALU opmode bits 6–8 ARE read — they select the operation form/direction, not an addressing mode; dst data-vs-addr-reg flag behavior is delegated via `writesNzvcIfDataDst`.)

```scala
package m68k040.decode

import m68k040.isa.{Cluster, Size}
import spinal.core._

/** EA-agnostic operation decoder: opword -> OpSpec. Masked-pattern table grouped
  * by line nibble. Reproduces today's op set over reg/imm modes; memory/complex
  * forms are marked illegal (handled by later slices). */
object OperationDecoder {
  def decode(opword: Bits): OpSpec = {
    val o = OpSpec.illegalDefault()
    o.allowOverride

    val line = opword(15 downto 12)
    val dnField = OperandSrc(); dnField.kind := OperandKind.REGFIELD; dnField.isAddr := False
    val anField = OperandSrc(); anField.kind := OperandKind.REGFIELD; anField.isAddr := True
    val easrc   = OperandSrc(); easrc.kind := OperandKind.EASRC;  easrc.isAddr := False
    val eadst   = OperandSrc(); eadst.kind := OperandKind.EADST;  eadst.isAddr := False
    val immq    = OperandSrc(); immq.kind  := OperandKind.IMMQ;   immq.isAddr := False

    switch(line) {
      // ---- MOVEQ (0111 rrr0 dddddddd) ----
      is(0x7) {
        when(opword(8) === False) {
          o.illegal := False
          o.op := DecOp.MOVE; o.size := Size.LONG
          o.srcB := immq
          o.dst := dnField; o.dstWrites := True
          o.writesNzvc := True
        }
      }
      // ---- MOVE.B/.W/.L (00 ss ...) src EA = bits 5-0, dst EA = bits 11-6 ----
      is(0x1, 0x3, 0x2) {
        o.illegal := False
        o.op := DecOp.MOVE
        when(line === 0x1) { o.size := Size.BYTE }
          .elsewhen(line === 0x3) { o.size := Size.WORD }
          .otherwise { o.size := Size.LONG }
        o.srcB := easrc                       // source EA -> srcB (ALU MOVE result = src2)
        o.dst  := eadst; o.dstWrites := True   // dest EA
        o.writesNzvcIfDataDst := True          // NZVC only if dst is a data reg (assembler resolves)
      }
      // ---- Bcc / BSR / BRA (0110 cccc dddddddd) ----
      is(0x6) {
        o.illegal := False
        o.op := DecOp.BRANCH; o.isBranch := True
        o.cond := opword(11 downto 8)
        o.cluster := Cluster.INT
        o.readsNzvc := (opword(11 downto 8).asUInt >= 2)
      }
      // ---- OR/SUB/CMP/AND/ADD (1ooo ... ) ----
      is(0x8, 0x9, 0xB, 0xC, 0xD) {
        val opmode = opword(8 downto 6)
        val isRmw  = (opmode === 4 || opmode === 5 || opmode === 6)
        val isMulDiv = ((line === 0x8 || line === 0xC) && (opmode === 3 || opmode === 7))
        when(!isRmw && !isMulDiv) {
          o.illegal := False
          switch(line) {
            is(0x8) { o.op := DecOp.OR }
            is(0x9) { o.op := DecOp.SUB }
            is(0xB) { o.op := DecOp.CMP }
            is(0xC) { o.op := DecOp.AND }
            is(0xD) { o.op := DecOp.ADD }
          }
          when(opmode === 0 || opmode === 1 || opmode === 2) {
            // EA -> Dn : srcA = Dn (dest operand), srcB = EA
            o.srcA := dnField; o.srcB := easrc; o.dst := dnField
            when(line =/= 0xB) { o.dstWrites := True }   // CMP writes no reg
            when(opmode === 0) { o.size := Size.BYTE }
              .elsewhen(opmode === 1) { o.size := Size.WORD }
              .otherwise { o.size := Size.LONG }
            when(line === 0xD || line === 0x9) { o.writesNzvc := True; o.writesX := True }   // ADD/SUB
              .elsewhen(line === 0xC || line === 0x8 || line === 0xB) { o.writesNzvc := True } // AND/OR/CMP
          } .elsewhen(opmode === 3 || opmode === 7) {
            // ADDA/SUBA/CMPA : srcA = An destination, srcB = EA source, dst An
            o.srcA := anField; o.srcB := easrc; o.dst := anField
            when(line =/= 0xB) { o.dstWrites := True }
            when(line === 0xB) { o.writesNzvc := True }  // CMPA sets flags, no write
            when(opmode === 3) { o.size := Size.WORD } .otherwise { o.size := Size.LONG }
          }
        }
      }
    }
    o
  }
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `~/sbt/bin/sbt "testOnly m68k040.decode.OperationDecoderSpec"`
Expected: PASS (7 tests). If a masked case mis-selects, fix the table (do NOT relax the test).

- [ ] **Step 5: Commit**

```bash
git add src/main/scala/m68k040/decode/OperationDecoder.scala src/test/scala/m68k040/decode/OperationDecoderSpec.scala
git commit -m "decode: EA-agnostic OperationDecoder (line-grouped masked-pattern table)"
```

---

### Task 4: MicroOpAssembler (OpSpec + EaDecoder -> DecodedUop)

**Files:**
- Create: `src/main/scala/m68k040/decode/MicroOpAssembler.scala`
- Test: `src/test/scala/m68k040/decode/MicroOpAssemblerSpec.scala`

The assembler resolves each `OperandSrc` to the `DecodedUop` reg/imm fields, calling `EaDecoder` for `EASRC` (field `opword(5..0)`) and `EADST` (field `dstReg=opword(11..9)`, `dstMode=opword(8..6)` → assembled as `mode ## reg`). It also evaluates `writesNzvcIfDataDst` against the dst EA class, computes branch displacement from `words`, and sets `unimplemented` when any operand resolves to a memory/illegal EA (this slice is reg/imm only).

- [ ] **Step 1: Write the failing test** (equivalence with the known-good op set — these mirror `SimpleDecodeUnitSpec`)

```scala
package m68k040.decode

import m68k040.VerilatorTest
import m68k040.frontend.DecodePacket
import m68k040.isa.Size
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import org.scalatest.funsuite.AnyFunSuite

class MicroOpAssemblerSpec extends AnyFunSuite {
  class Dut extends Component {
    val pkt = in(DecodePacket())
    val uop = out(DecodedUop())
    uop := MicroOpAssembler.assemble(pkt)
  }
  def drive(dut: Dut, op: Int, w1: Int = 0, w2: Int = 0, len: Int = 1): Unit = {
    dut.pkt.valid #= true; dut.pkt.pc #= 0x1000; dut.pkt.simple #= true; dut.pkt.complex #= false
    dut.pkt.lenWords #= len; dut.pkt.wordCount #= len; dut.pkt.fault #= false
    dut.pkt.words(0) #= op; dut.pkt.words(1) #= w1; dut.pkt.words(2) #= w2
    dut.pkt.words(3) #= 0; dut.pkt.words(4) #= 0
  }
  def run(check: Dut => Unit): Unit = SimConfig.withVerilator.compile(new Dut).doSim(check)

  test("MOVEQ #5,D3", VerilatorTest) { run { dut => drive(dut, 0x7605); sleep(1)
    assert(dut.uop.op.toEnum == DecOp.MOVE && dut.uop.dstReg.toInt == 3 && dut.uop.dstValid.toBoolean)
    assert(dut.uop.useImm.toBoolean && dut.uop.imm.toLong == 5 && dut.uop.size.toEnum == Size.LONG)
    assert(dut.uop.writesNzvc.toBoolean && !dut.uop.writesX.toBoolean && !dut.uop.unimplemented.toBoolean)
  }}
  test("MOVE.W D0,D1 (src in srcB, writesNzvc)", VerilatorTest) { run { dut => drive(dut, 0x3200); sleep(1)
    assert(dut.uop.op.toEnum == DecOp.MOVE && dut.uop.srcBReg.toInt == 0 && dut.uop.srcBValid.toBoolean)
    assert(!dut.uop.srcAValid.toBoolean && dut.uop.dstReg.toInt == 1 && dut.uop.dstValid.toBoolean)
    assert(dut.uop.writesNzvc.toBoolean)
  }}
  test("MOVEA.L A0,A1 (src in srcB, no flags)", VerilatorTest) { run { dut => drive(dut, 0x2248); sleep(1)
    assert(dut.uop.srcBReg.toInt == 8 && dut.uop.srcBValid.toBoolean)
    assert(dut.uop.dstReg.toInt == 9 && dut.uop.dstValid.toBoolean && !dut.uop.writesNzvc.toBoolean)
  }}
  test("MOVE.L #imm,D0", VerilatorTest) { run { dut => drive(dut, 0x203C, 0x1234, 0x5678, len = 3); sleep(1)
    assert(dut.uop.useImm.toBoolean && dut.uop.imm.toLong == 0x12345678L && dut.uop.dstReg.toInt == 0)
    assert(dut.uop.writesNzvc.toBoolean)
  }}
  test("ADD.L D1,D0 (srcA=Dn dest, srcB=EA, NZVC+X)", VerilatorTest) { run { dut => drive(dut, 0xD081); sleep(1)
    assert(dut.uop.op.toEnum == DecOp.ADD && dut.uop.srcAReg.toInt == 0 && dut.uop.srcBReg.toInt == 1)
    assert(dut.uop.dstReg.toInt == 0 && dut.uop.dstValid.toBoolean && dut.uop.writesNzvc.toBoolean && dut.uop.writesX.toBoolean)
  }}
  test("CMP.L D1,D0 (no write, NZVC)", VerilatorTest) { run { dut => drive(dut, 0xB081); sleep(1)
    assert(dut.uop.op.toEnum == DecOp.CMP && !dut.uop.dstValid.toBoolean && dut.uop.writesNzvc.toBoolean)
  }}
  test("Bcc word disp", VerilatorTest) { run { dut => drive(dut, 0x6700, 0x0010, len = 2); sleep(1)
    assert(dut.uop.isBranch.toBoolean && dut.uop.cond.toInt == 7 && dut.uop.branchDisp.toLong == 0x10)
  }}
  test("memory-EA operand -> unimplemented (reg/imm slice)", VerilatorTest) { run { dut => drive(dut, 0xD090); sleep(1)
    // ADD.L (A0),D0 — EA is memory -> not handled this slice
    assert(dut.uop.unimplemented.toBoolean)
  }}
  test("non-simple packet -> unimplemented", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      drive(dut, 0x7605); dut.pkt.simple #= false; dut.pkt.complex #= true; sleep(1)
      assert(dut.uop.unimplemented.toBoolean)
    }
  }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `~/sbt/bin/sbt "testOnly m68k040.decode.MicroOpAssemblerSpec"`
Expected: FAIL to compile (`MicroOpAssembler` not defined).

- [ ] **Step 3: Implement MicroOpAssembler**

Create `src/main/scala/m68k040/decode/MicroOpAssembler.scala`:

```scala
package m68k040.decode

import m68k040.frontend.DecodePacket
import m68k040.isa.{Cluster, Size}
import spinal.core._
import spinal.lib._

/** Combines the EA-agnostic OperationDecoder with the opcode-agnostic EaDecoder
  * into one DecodedUop. This slice: register/immediate operands only, exactly one
  * µop per instruction (no temps, no memory). Any operand that resolves to a
  * memory/illegal EA -> `unimplemented` (memory cracking is a later slice). */
object MicroOpAssembler {
  def assemble(pkt: DecodePacket): DecodedUop = {
    val uop = DecodedUop()
    val op  = pkt.words(0)
    val spec = OperationDecoder.decode(op)

    // EA fields. Source EA = op(5..0). Dest EA (MOVE) = dstMode(8..6) ## dstReg(11..9).
    val srcEa = EaDecoder.decode(op(5 downto 0), spec.size, pkt.words)
    val dstEaField = op(8 downto 6) ## op(11 downto 9)
    val dstEa = EaDecoder.decode(dstEaField, spec.size, pkt.words)

    // ---- defaults ----
    uop.valid         := pkt.valid
    uop.pc            := pkt.pc
    uop.op            := spec.op
    uop.cluster       := spec.cluster
    uop.size          := spec.size
    uop.srcAReg       := 0; uop.srcAValid := False
    uop.srcBReg       := 0; uop.srcBValid := False
    uop.dstReg        := 0; uop.dstValid  := False
    uop.useImm        := False; uop.imm    := 0
    uop.readsNzvc     := spec.readsNzvc; uop.readsX := spec.readsX
    uop.writesNzvc    := spec.writesNzvc; uop.writesX := spec.writesX
    uop.isBranch      := spec.isBranch; uop.cond := spec.cond
    uop.branchDisp    := 0
    uop.unimplemented := False

    // A µop is reg/imm-decodable only if every active EA-sourced operand is a
    // register or immediate. Memory/illegal EAs (or illegal op, or !simple) ->
    // unimplemented (cleared fields above keep the bundle well-formed).
    val srcEaOk = (srcEa.klass === EaClass.DATAREG) || (srcEa.klass === EaClass.ADDRREG) || (srcEa.klass === EaClass.IMM)
    val dstEaOk = (dstEa.klass === EaClass.DATAREG) || (dstEa.klass === EaClass.ADDRREG)
    val usesSrcEa = (spec.srcA.kind === OperandKind.EASRC) || (spec.srcB.kind === OperandKind.EASRC)
    val usesDstEa = (spec.dst.kind === OperandKind.EADST)

    // ---- place each operand slot ----
    def place(o: OperandSrc, setReg: (UInt) => Unit, setValid: (Bool) => Unit, setImm: () => Unit): Unit = {
      switch(o.kind) {
        is(OperandKind.REGFIELD) {
          setReg(Mux(o.isAddr, (U(8, 4 bits) + op(11 downto 9).asUInt).resized, op(11 downto 9).asUInt.resized))
          setValid(True)
        }
        is(OperandKind.EASRC) {
          when(srcEa.klass === EaClass.IMM) { setImm() }
            .otherwise { setReg(srcEa.reg); setValid(True) }
        }
        is(OperandKind.EADST) { setReg(dstEa.reg); setValid(True) }
        is(OperandKind.IMMQ)  { uop.useImm := True; uop.imm := op(7 downto 0).asSInt.resize(32).asBits }
        default {}
      }
    }
    place(spec.srcA, r => uop.srcAReg := r, v => uop.srcAValid := v, () => { uop.useImm := True; uop.imm := srcEa.imm })
    place(spec.srcB, r => uop.srcBReg := r, v => uop.srcBValid := v, () => { uop.useImm := True; uop.imm := srcEa.imm })
    place(spec.dst,  r => uop.dstReg := r,  v => uop.dstValid := v,  () => {})
    when(spec.dst.kind =/= OperandKind.NONE && spec.dstWrites) { uop.dstValid := True }
    when(spec.dst.kind =/= OperandKind.NONE && !spec.dstWrites) { uop.dstValid := False } // CMP/CMPA

    // MOVE: NZVC only if the destination EA is a data register.
    when(spec.writesNzvcIfDataDst) { uop.writesNzvc := (dstEa.klass === EaClass.DATAREG) }

    // Branch displacement (byte / word / long), reproducing the simple-decode rule.
    when(spec.isBranch) {
      val disp8 = op(7 downto 0)
      when(disp8 === 0x00) { uop.branchDisp := pkt.words(1).asSInt.resize(32).asBits }
        .elsewhen(disp8 === M"11111111") { uop.branchDisp := pkt.words(1) ## pkt.words(2) }
        .otherwise { uop.branchDisp := disp8.asSInt.resize(32).asBits }
    }

    // ---- unimplemented gating ----
    when(!pkt.simple || spec.illegal ||
         (usesSrcEa && !srcEaOk) || (usesDstEa && !dstEaOk)) {
      uop.op            := DecOp.ILLEGAL
      uop.unimplemented := True
      uop.dstValid := False; uop.srcAValid := False; uop.srcBValid := False
      uop.writesNzvc := False; uop.writesX := False; uop.isBranch := False
    }
    uop
  }
}
```

NOTE on `place` using Scala closures that emit hardware inside `switch` — each `place` call elaborates a `switch(o.kind)`; the `setReg`/`setValid` thunks emit `:=` into the enclosing scope. This is valid SpinalHDL (the thunks build hardware when invoked). If the Scala-closure style trips elaboration, inline the three `switch` blocks explicitly (same logic) — do not change behavior.

- [ ] **Step 4: Run it to verify it passes**

Run: `~/sbt/bin/sbt "testOnly m68k040.decode.MicroOpAssemblerSpec"`
Expected: PASS (9 tests). These mirror `SimpleDecodeUnitSpec` — if any operand placement diverges, fix the assembler (the existing behavior is ground truth).

- [ ] **Step 5: Commit**

```bash
git add src/main/scala/m68k040/decode/MicroOpAssembler.scala src/test/scala/m68k040/decode/MicroOpAssemblerSpec.scala
git commit -m "decode: MicroOpAssembler (OpSpec + EaDecoder -> DecodedUop, reg/imm path)"
```

---

### Task 5: Wire into DecodeStage, retire SimpleDecodeUnit, full verification + synth gate

**Files:**
- Modify: `src/main/scala/m68k040/decode/DecodeStage.scala`
- Delete: `src/main/scala/m68k040/decode/SimpleDecodeUnit.scala`, `src/test/scala/m68k040/decode/SimpleDecodeUnitSpec.scala`
- Check (no edit expected): `src/test/scala/m68k040/decode/DecodeStageSpec.scala`, `src/test/scala/m68k040/lockstep/ExecuteLockStepSpec.scala`

- [ ] **Step 1: Point DecodeStage at the assembler**

In `src/main/scala/m68k040/decode/DecodeStage.scala`, replace the two decode calls:
```scala
    val d0 = SimpleDecodeUnit.decode(df.feed.payload(0))
    val d1 = SimpleDecodeUnit.decode(df.feed.payload(1))
```
with:
```scala
    val d0 = MicroOpAssembler.assemble(df.feed.payload(0))
    val d1 = MicroOpAssembler.assemble(df.feed.payload(1))
```
Leave everything else (validity overrides, skid, service overrides) unchanged — the output contract is identical.

- [ ] **Step 2: Delete the superseded unit + its spec**

```bash
git rm src/main/scala/m68k040/decode/SimpleDecodeUnit.scala src/test/scala/m68k040/decode/SimpleDecodeUnitSpec.scala
```
(`MicroOpAssemblerSpec` now covers that behavior. `DecodedUopSpec` stays — it tests the bundle, not the unit.)

- [ ] **Step 3: Run the decode + frontend unit specs**

Run: `~/sbt/bin/sbt "testOnly m68k040.decode.DecodeStageSpec m68k040.decode.DecodedUopSpec m68k040.decode.OperationDecoderSpec m68k040.decode.EaDecoderSpec m68k040.decode.MicroOpAssemblerSpec m68k040.decode.DecodeContractsSpec"`
Expected: all PASS. `DecodeStageSpec` asserts the same decoded fields it always did (the contract is unchanged); if it referenced `SimpleDecodeUnit` directly, repoint it to `MicroOpAssembler.assemble` (decode behavior identical — do NOT change asserted values).

- [ ] **Step 4: Lock-step (correctness gate) + full suites, twice for determinism**

Run: `~/sbt/bin/sbt "testOnly m68k040.lockstep.ExecuteLockStepSpec"` → all programs green; run twice (deterministic — see the seed-flakiness discipline). The decoded µops are bit-identical to before, so every program must still lock-step vs Musashi.
Run: `make SBT=~/sbt/bin/sbt test-fast` and `make SBT=~/sbt/bin/sbt test-verilator` → report totals (baseline: test-fast 56, test-verilator 75; the net count changes by the new decode specs minus the removed `SimpleDecodeUnitSpec`).

- [ ] **Step 5: Synth gate (FMax)**

Run: `~/sbt/bin/sbt "runMain m68k040.top.GenFullCoreSynthVerilog"` then
`vivado -mode batch -nojournal -log synth/vivado_FullCore.log -source synth/ooc_M68kFullCoreSynth.tcl 2>&1 | grep -iE "RESULT|Unsupported|multi-driven net on pin|CRITICAL WARNING|Synth Design complete"`.
Expected: 0 errors / 0 critical warnings; WNS ≥ 0 (≥ 250 MHz). Report WNS + FMAX + critical-path Source→Destination. The decode table must NOT be the critical path; if it is, insert a `PipeStage` between `OperationDecoder`/assembler and `uopsPort` in `DecodeStage` (decode is latency-agnostic for lock-step) and re-synth — report before/after.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "decode: replace SimpleDecodeUnit with table-driven framework (OperationDecoder + EaDecoder + assembler); reg/imm; lock-step unchanged, >=250MHz"
```

---

## Self-Review

**1. Spec coverage (vs `2026-06-03-decode-matrix-design.md`):**
- §4.2 OperationDecoder (EA-agnostic, masked-pattern, line-grouped) → Task 3. ✓
- §4.3 EaDecoder (opcode-agnostic; reg/imm produced, memory classified-but-reserved) → Task 2. ✓
- §4.4 MicroOpAssembler reg/imm path (1 µop/instr, memory → unimplemented) → Task 4. ✓
- §2 "first slice = reg/imm, replace SimpleDecodeUnit, no LS dep" → Task 5. ✓
- §4.1 µop expansion queue → **intentionally NOT built** (degenerate 1:1 this slice; the existing DecodeStage skid is the 1:1 path; §9 defers the explicit queue to when expansion >1 first appears — YAGNI). Documented here so it isn't mistaken for a gap.
- §4.5 temps → none this slice (reg/imm needs none); deferred to the first memory slice. ✓
- §5 LUT/FMax (line-grouped + pipeline escape hatch) → Task 5 Step 5. ✓
- §7 verification (decoupled unit tests + lock-step + determinism + synth) → Tasks 2/3/4 unit + Task 5 lock-step/synth. ✓

**2. Placeholder scan:** No TBD/TODO; every code step has complete code. The one judgement note (Task 4 closure-style `place`) gives an explicit fallback (inline the switches) — not a placeholder.

**3. Type consistency:** `OpSpec`/`OperandSrc`/`OperandKind`/`EaSpec`/`EaClass` defined in Task 1 are used with the same field/enumerant names in Tasks 2–4 (`klass`, `reg`, `imm`, `kind`, `isAddr`, `srcA/srcB/dst`, `dstWrites`, `writesNzvcIfDataDst`, `illegal`). `EaDecoder.decode(eaField:Bits, size:Size.C, words:Vec[Bits])` and `OperationDecoder.decode(opword:Bits)` and `MicroOpAssembler.assemble(pkt:DecodePacket)` signatures are consistent across their definition (Tasks 2/3/4) and call sites (Task 4 assembler, Task 5 DecodeStage). `DecodedUop` field names match the verified contract.
