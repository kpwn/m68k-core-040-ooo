# Predecode-on-Miss Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an opword-only instruction predecoder that runs in a dedicated PREDECODE cycle of the I-cache refill FSM, caches per-line `{simple, lenWords}` metadata, and exposes the fetched window's 4 chunk-predecodes in the fetch response.

**Architecture:** A pure-Scala `PredecodeRef` encodes the authoritative opword→`ChunkPredecode` map (the spec). A combinational SpinalHDL `PredecodeWord` mirrors it, proven **equivalent by an exhaustive 16-bit opword sweep** (all 65536 values, RTL == ref). The I-cache gains a `predMem` array, a `PREDECODE` FSM state (32 parallel `PredecodeWord` over the filled line; commits tag/valid only after data+predecode are both written), and a registered `pred` output.

**Tech Stack:** Scala 2.13.16 / SpinalHDL 1.14.1 / SpinalSim + Verilator 5.032 / ScalaTest 3.2.19.

**Spec:** `docs/superpowers/specs/2026-05-31-predecode-on-miss-design.md`. Read it.

**Key constraints from the spec:** `simple = fast-opcode ∧ simple-EA ∧ ≤2 µops`; **32 `PredecodeWord` run in parallel → each must be LUT-frugal; bias to `complex`.** Off the FMax hot path (runs at refill rate). First-cut fast set (everything else → complex, easily extended later): **MOVE/MOVEA, MOVEQ, Bcc/BSR/BRA, and the read-direction ALU forms ADD/SUB/AND/OR/CMP/CMPA** (ADDA/SUBA included). Deferred-to-complex for the first cut: EOR, memory-destination RMW, ADDQ/SUBQ, Scc/DBcc, shifts, class-0 (immediate/bit) ops, class-4 (LEA/PEA/etc.), and ALL indexed/PC-indexed modes.

**Toolchain:** `sbt` at `~/sbt/bin/sbt` (NOT on PATH). Run a spec: `~/sbt/bin/sbt "testOnly <spec>"`; timeout up to 590000 ms. Verilator-backed sim tests are tagged `m68k040.VerilatorTest` (excluded from `fastTest`, run via `make SBT=~/sbt/bin/sbt test-verilator`).

**Branch:** execution on a fresh `feat/predecode` branch (controller creates it before Task 1; do NOT work on `master`).

**Existing code (use as-is):** `m68k040.cache.IcacheTypes` (`FetchCmd/FetchRsp/CacheMode/...`), `m68k040.cache.IcachePlugin` (IDLE/REFILL/REPLAY FSM, `dataMem`/`tagMem`/`valids`/`victim`, registered response regs `rspValidReg/rspPcReg/rspDataReg/rspFaultReg`, `ways=4/sets=64/tagBits=20/beatsPerLine=2/setBits=6/wayBits=2` from `CacheGeometry.l1i040`), `IcacheSpec` (Dut + `fetch`/`pulseInvalidateAll`/AR-counter helpers; ports via `dut.icache.logic.*`).

---

## File structure

| File | Responsibility |
|---|---|
| `src/main/scala/m68k040/cache/IcacheTypes.scala` (modify) | add `ChunkPredecode` bundle + packing; add `pred: Vec(ChunkPredecode,4)` to `FetchRsp` |
| `src/test/scala/m68k040/frontend/PredecodeRef.scala` | authoritative pure-Scala opword classifier (the spec/oracle) |
| `src/test/scala/m68k040/frontend/PredecodeRefSpec.scala` | curated hand-verified opcode cases for `PredecodeRef` |
| `src/main/scala/m68k040/frontend/PredecodeWord.scala` | combinational RTL classifier (mirrors `PredecodeRef`) |
| `src/test/scala/m68k040/frontend/PredecodeWordSpec.scala` | exhaustive RTL≡ref sweep (all 65536 opwords) |
| `src/main/scala/m68k040/cache/IcachePlugin.scala` (modify) | `predMem`, `PREDECODE` state, window-pred registered output |
| `src/test/scala/m68k040/cache/IcacheSpec.scala` (modify) | predecode-via-fetch integration tests |

---

## Task 1: `ChunkPredecode` type + `FetchRsp.pred`

**Files:** Modify `src/main/scala/m68k040/cache/IcacheTypes.scala`; Test `src/test/scala/m68k040/cache/ChunkPredecodeSpec.scala`

- [ ] **Step 1: Write the failing test**
```scala
package m68k040.cache

import spinal.core._
import spinal.lib._
import org.scalatest.funsuite.AnyFunSuite

class ChunkPredecodeSpec extends AnyFunSuite {
  test("ChunkPredecode is 4 bits; FetchRsp carries 4 of them") {
    SpinalConfig().generateVerilog(new Component {
      val c = ChunkPredecode()
      assert(c.simple.isInstanceOf[Bool])
      assert(c.lenWords.getWidth == 3)
      assert(c.asBits.getWidth == 4)
      val r = master(Flow(FetchRsp()))
      r.valid := False
      r.payload.assignDontCare()
      assert(r.payload.pred.length == 4)
      val probe = out(Bool()); probe := r.payload.pred(0).simple
    })
  }
}
```

- [ ] **Step 2: Run it, expect FAIL** (`ChunkPredecode` undefined). `~/sbt/bin/sbt "testOnly m68k040.cache.ChunkPredecodeSpec"`.

- [ ] **Step 3: Add `ChunkPredecode` to `IcacheTypes.scala`** (after the imports, before `FetchCmd`):
```scala
/** Per-16-bit-word predecode result (one per chunk; 32 per 64-byte line).
  * `lenWords` (1..5 = 2/4/6/8/10 bytes) is meaningful only when `simple`. 4 bits. */
case class ChunkPredecode() extends Bundle {
  val simple   = Bool()
  val lenWords = UInt(3 bits)
}
```

- [ ] **Step 4: Add `pred` to `FetchRsp`** — change the `FetchRsp` case class body to add:
```scala
  val pred  = Vec(ChunkPredecode(), 4)   // predecode for the 4 words of the returned window
```
(Keep the existing `pc`/`data`/`fault` fields and their comments.)

- [ ] **Step 5: Run it, expect PASS.** Also `~/sbt/bin/sbt compile`.
- [ ] **Step 6: Commit** — `git add -A && git commit -m "cache: ChunkPredecode type + FetchRsp.pred window metadata"`

---

## Task 2: `PredecodeRef` (authoritative Scala classifier) + curated cases

**Files:** Create `src/test/scala/m68k040/frontend/PredecodeRef.scala`; Test `src/test/scala/m68k040/frontend/PredecodeRefSpec.scala`

- [ ] **Step 1: Write the failing curated test `PredecodeRefSpec.scala`** (hand-verified opcodes anchor the ref to reality):
```scala
package m68k040.frontend

import org.scalatest.funsuite.AnyFunSuite

class PredecodeRefSpec extends AnyFunSuite {
  import PredecodeRef.{classify, CP}
  // helpers to build opwords
  def cp(simple: Boolean, len: Int) = CP(simple, len)

  test("MOVEQ #imm,Dn -> simple, 1 word") {
    // 0111 ddd 0 iiiiiiii  (MOVEQ #0,D0 = 0x7000)
    assert(classify(0x7000) == cp(true, 1))
    assert(classify(0x7E05) == cp(true, 1))  // MOVEQ #5,D7
  }
  test("MOVEQ encoding with bit8=1 is not MOVEQ -> complex") {
    assert(classify(0x7100) == cp(false, 0))
  }
  test("MOVE.W D0,D1 -> simple, 1 word (reg->reg)") {
    // 0011 (size W) dst=001/reg, src=Dn  : MOVE.W D0,D1 = 0x3200
    assert(classify(0x3200) == cp(true, 1))
  }
  test("MOVE.L #imm32,D0 -> simple, 3 words (1 + imm.L 2)") {
    // 0010 (L) dst D0 (reg000,mode000) src #imm (mode111 reg100) = 0x203C
    assert(classify(0x203C) == cp(true, 3))
  }
  test("MOVE.L (d16,A0),D1 -> simple, 2 words") {
    // 0010 dst D1(reg001 mode000) src (d16,A0)=mode101 reg000 = 0x2228
    assert(classify(0x2228) == cp(true, 2))
  }
  test("MOVE.L (A0),(A1) mem->mem -> complex (>2 uops)") {
    // 0010 dst (A1)=reg001 mode010 src (A0)=mode010 reg000 = 0x2290
    assert(classify(0x2290) == cp(false, 0))
  }
  test("MOVE.L abs.L,abs.L -> complex (mem->mem)") {
    // src 111/001 abs.L, dst 111/001 abs.L = 0x23F9
    assert(classify(0x23F9) == cp(false, 0))
  }
  test("Bcc.s (byte disp) -> simple, 1 word") {
    assert(classify(0x6002) == cp(true, 1))  // BRA.s +2
  }
  test("Bcc.w (disp==0) -> simple, 2 words") {
    assert(classify(0x6000) == cp(true, 2))  // BRA.w
  }
  test("Bcc.l (disp==0xFF, 020+) -> simple, 3 words") {
    assert(classify(0x60FF) == cp(true, 3))  // BRA.l
  }
  test("ADD.L (A0),D0 (EA->Dn) -> simple, 1 word") {
    // 1101 Dn=000 opmode=010(L,EA->Dn) ea=(A0) mode010 reg000 = 0xD090
    assert(classify(0xD090) == cp(true, 1))
  }
  test("ADD.L D0,(A0) (Dn->EA, RMW) -> complex") {
    // opmode 110 (L, Dn->EA) = 0xD190
    assert(classify(0xD190) == cp(false, 0))
  }
  test("ADDA.L A1,A0 -> simple, 1 word (opmode 111)") {
    // 1101 An=000 opmode111 ea=A1(mode001 reg001) = 0xD1C9
    assert(classify(0xD1C9) == cp(true, 1))
  }
  test("CMP.W (d16,A0),D0 -> simple, 2 words") {
    // 1011 Dn000 opmode001(W,CMP) ea=(d16,A0) mode101 reg000 = 0xB068
    assert(classify(0xB068) == cp(true, 2))
  }
  test("EOR.W D0,(A0) (class B, opmode 101) -> complex (first cut)") {
    assert(classify(0xB150) == cp(false, 0))
  }
  test("indexed source (d8,A0,Xn) -> complex") {
    // ADD.L (d8,A0,Xn),D0 : ea mode110 reg000, opmode010 = 0xD0B0
    assert(classify(0xD0B0) == cp(false, 0))
  }
  test("deferred ops classify complex: ADDQ, DBcc, LSL, ANDI, LEA") {
    assert(classify(0x5240) == cp(false, 0))  // ADDQ.W #1,D0 (class 5)
    assert(classify(0x51C8) == cp(false, 0))  // DBRA D0 (class 5, cc=F)
    assert(classify(0xE148) == cp(false, 0))  // LSL.W #8,D0 (class E)
    assert(classify(0x0240) == cp(false, 0))  // ANDI.W #..,D0 (class 0)
    assert(classify(0x41D0) == cp(false, 0))  // LEA (A0),A0 (class 4)
  }
}
```

- [ ] **Step 2: Run it, expect FAIL** (`PredecodeRef` undefined). `~/sbt/bin/sbt "testOnly m68k040.frontend.PredecodeRefSpec"`.

- [ ] **Step 3: Write `PredecodeRef.scala`** — the authoritative classifier:
```scala
package m68k040.frontend

/** Authoritative pure-Scala opword classifier (the spec for predecode).
  * RTL PredecodeWord must be equivalent (proven by exhaustive sweep). */
object PredecodeRef {
  final case class CP(simple: Boolean, lenWords: Int)
  val COMPLEX = CP(false, 0)

  /** Extension-word count for an EA mode/reg. None => complex (indexed/PC-indexed
    * or, when allowImm=false, immediate). sizeL selects #imm width (long=2). */
  def eaExt(mode: Int, reg: Int, sizeL: Boolean, allowImm: Boolean): Option[Int] = mode match {
    case 0 | 1 | 2 | 3 | 4 => Some(0)        // Dn, An, (An), (An)+, -(An)
    case 5                 => Some(1)        // (d16,An)
    case 6                 => None           // (d8,An,Xn) indexed -> complex
    case 7 => reg match {
      case 0 => Some(1)                      // abs.W
      case 1 => Some(2)                      // abs.L
      case 2 => Some(1)                      // (d16,PC)
      case 3 => None                         // (d8,PC,Xn) -> complex
      case 4 => if (allowImm) Some(if (sizeL) 2 else 1) else None  // #imm
      case _ => None
    }
    case _ => None
  }
  def isMem(mode: Int): Boolean = mode > 1   // anything but Dn(0)/An(1)

  def classify(op0: Int): CP = {
    val op  = op0 & 0xffff
    val cls = (op >> 12) & 0xf
    cls match {
      // ---- MOVE.B/.L/.W (dst field reversed: dstReg=op[11:9], dstMode=op[8:6]) ----
      case 0x1 | 0x2 | 0x3 =>
        val sizeL   = cls == 0x2
        val srcMode = (op >> 3) & 7; val srcReg = op & 7
        val dstMode = (op >> 6) & 7; val dstReg = (op >> 9) & 7
        val se = eaExt(srcMode, srcReg, sizeL, allowImm = true)
        val de = dstMode match {
          case 0 | 1 | 2 | 3 | 4 | 5 => eaExt(dstMode, dstReg, sizeL, allowImm = false)
          case 7 => dstReg match { case 0 => Some(1); case 1 => Some(2); case _ => None } // abs.W/abs.L only
          case _ => None                       // mode 6 indexed dest -> complex
        }
        if (se.isEmpty || de.isEmpty) COMPLEX
        else {
          val srcMem = isMem(srcMode)
          val dstMem = dstMode != 1 && isMem(dstMode)   // An(1) dest = MOVEA, register (not mem)
          if (srcMem && dstMem) COMPLEX               // mem->mem = >2 uops
          else CP(simple = true, lenWords = 1 + se.get + de.get)
        }
      // ---- MOVEQ ----
      case 0x7 =>
        if (((op >> 8) & 1) == 0) CP(simple = true, lenWords = 1) else COMPLEX
      // ---- Bcc / BSR / BRA ----
      case 0x6 =>
        val d8 = op & 0xff
        val len = if (d8 == 0x00) 2 else if (d8 == 0xff) 3 else 1
        CP(simple = true, lenWords = len)
      // ---- OR / SUB / AND / ADD (read-direction forms only) ----
      case 0x8 | 0x9 | 0xC | 0xD =>
        val opmode  = (op >> 6) & 7
        val srcMode = (op >> 3) & 7; val srcReg = op & 7
        if (opmode == 4 || opmode == 5 || opmode == 6) COMPLEX   // Dn->EA RMW
        else {
          val sizeL = opmode == 2 || opmode == 7                 // long when EA->Dn long or An long
          eaExt(srcMode, srcReg, sizeL, allowImm = false) match {
            case Some(e) => CP(simple = true, lenWords = 1 + e)
            case None    => COMPLEX
          }
        }
      // ---- CMP / CMPA (opmode 0,1,2,3,7); EOR (4,5,6) -> complex first cut ----
      case 0xB =>
        val opmode  = (op >> 6) & 7
        if (opmode == 0 || opmode == 1 || opmode == 2 || opmode == 3 || opmode == 7) {
          val srcMode = (op >> 3) & 7; val srcReg = op & 7
          val sizeL = opmode == 2 || opmode == 7
          eaExt(srcMode, srcReg, sizeL, allowImm = false) match {
            case Some(e) => CP(simple = true, lenWords = 1 + e)
            case None    => COMPLEX
          }
        } else COMPLEX
      case _ => COMPLEX
    }
  }
}
```

- [ ] **Step 4: Run it, expect PASS** (all curated cases). If a hand-computed expectation in the test disagrees with the ref, RE-DERIVE the opword by hand from the m68k encoding (don't just flip the assertion) — the curated values are the anchor; fix whichever is genuinely wrong.
- [ ] **Step 5: Commit** — `git add -A && git commit -m "predecode: authoritative PredecodeRef classifier + curated opcode cases"`

---

## Task 3: `PredecodeWord` RTL + exhaustive equivalence sweep

**Files:** Create `src/main/scala/m68k040/frontend/PredecodeWord.scala`; Test `src/test/scala/m68k040/frontend/PredecodeWordSpec.scala`

- [ ] **Step 1: Write the failing test** (exhaustive: all 65536 opwords, RTL == ref):
```scala
package m68k040.frontend

import m68k040.VerilatorTest
import m68k040.cache.ChunkPredecode
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

class PredecodeWordSpec extends AnyFunSuite {
  class Dut extends Component {
    val op  = in  Bits (16 bits)
    val out = out (ChunkPredecode())
    out := PredecodeWord.classify(op)
  }
  test("RTL PredecodeWord matches PredecodeRef for ALL 65536 opwords", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      for (op <- 0 until 65536) {
        dut.op #= op
        sleep(1)
        val ref = PredecodeRef.classify(op)
        val rs  = dut.out.simple.toBoolean
        assert(rs == ref.simple, f"op=0x$op%04x simple: rtl=$rs ref=${ref.simple}")
        if (ref.simple)
          assert(dut.out.lenWords.toInt == ref.lenWords,
            f"op=0x$op%04x len: rtl=${dut.out.lenWords.toInt} ref=${ref.lenWords}")
      }
    }
  }
}
```

- [ ] **Step 2: Run it, expect FAIL** (`PredecodeWord` undefined). `~/sbt/bin/sbt "testOnly m68k040.frontend.PredecodeWordSpec"`.

- [ ] **Step 3: Write `PredecodeWord.scala`** — a combinational SpinalHDL function that mirrors `PredecodeRef.classify` exactly. Structure:
```scala
package m68k040.frontend

import m68k040.cache.ChunkPredecode
import spinal.core._

object PredecodeWord {
  /** Combinational opword -> ChunkPredecode. Mirrors PredecodeRef. LUT-frugal:
    * flat case on op(15 downto 12) + small per-class logic. 32 of these run in
    * parallel during the I-cache PREDECODE cycle. */
  def classify(op: Bits): ChunkPredecode = {
    val r = ChunkPredecode()
    r.simple   := False
    r.lenWords := U(0, 3 bits)

    val cls = op(15 downto 12).asUInt
    // EA extension-word helper (returns (ok, ext)); ok=False => complex.
    def eaExt(mode: UInt, reg: UInt, sizeL: Bool, allowImm: Bool): (Bool, UInt) = {
      val ok  = Bool(); val ext = UInt(3 bits)
      ok := True; ext := U(0, 3 bits)
      switch(mode) {
        is(U(0),U(1),U(2),U(3),U(4)) { ext := 0 }
        is(U(5))                     { ext := 1 }
        is(U(6))                     { ok := False }
        is(U(7)) {
          switch(reg) {
            is(U(0)) { ext := 1 }
            is(U(1)) { ext := 2 }
            is(U(2)) { ext := 1 }
            is(U(3)) { ok := False }
            is(U(4)) { when(allowImm) { ext := Mux(sizeL, U(2,3 bits), U(1,3 bits)) } otherwise { ok := False } }
            default  { ok := False }
          }
        }
      }
      (ok, ext)
    }
    // ... implement each class (MOVE 1/2/3, MOVEQ 7, Bcc 6, ALU 8/9/C/D, CMP/EOR B)
    //     using the SAME rules as PredecodeRef.classify. Drive r.simple / r.lenWords.
    r
  }
}
```
Fill in each class body to match `PredecodeRef` precisely (MOVE with reversed dst field + mem→mem complex; MOVEQ bit8; Bcc disp8 0x00/0xFF/else; ALU opmode 4/5/6 complex + sizeL = opmode∈{2,7}; CMP opmode∈{0,1,2,3,7} else complex). The exhaustive sweep is the correctness gate — iterate until RTL == ref for all 65536 opwords. Do NOT change the test or `PredecodeRef`; fix the RTL.

- [ ] **Step 4: Run it, expect PASS** (one VerilatorTest, 65536 comparisons). Allow up to 590000 ms (Verilator build + 65536-iteration sim). If a class mismatches, the assertion prints the exact `op` — decode it and align the RTL to the ref.
- [ ] **Step 5: Commit** — `git add -A && git commit -m "predecode: PredecodeWord RTL classifier (exhaustively == PredecodeRef)"`

---

## Task 4: I-cache integration — `predMem`, `PREDECODE` state, window-pred output

**Files:** Modify `src/main/scala/m68k040/cache/IcachePlugin.scala`; Test (modify) `src/test/scala/m68k040/cache/IcacheSpec.scala`

- [ ] **Step 1: Write the failing integration test** — add to `IcacheSpec` (uses the existing Dut/fetch helpers; the `fetch` helper currently returns the 64-bit data — add a `fetchPred` helper that also samples `rsp.pred`). Place known instruction words in the AXI test memory and assert the returned predecode matches `PredecodeRef`.
```scala
  test("fetched window carries predecode matching PredecodeRef", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      // Build a line image whose 4 words at the fetched window are known opwords:
      //   word0 = MOVEQ #5,D0 (0x7005, simple len1)
      //   word1 = NOP-as-complex stand-in: use ADDQ 0x5240 (complex first-cut)
      //   word2 = MOVE.W D0,D1 (0x3200, simple len1)
      //   word3 = BRA.w        (0x6000, simple len2)
      // Place them at line base 0x5000, window 0 (pc=0x5000, pc[5:3]=0 -> words 0..3).
      val base = 0x5000L
      val words = Seq(0x7005, 0x5240, 0x3200, 0x6000)
      val agent = IcacheSim.attachMemoryWithWords(dut.icache.logic.axi, cd, base, words)
      dut.icache.logic.cmdPort.valid #= false
      dut.icache.logic.cmdPort.payload.pc #= 0
      dut.icache.logic.invalidateAll #= false
      cd.waitSampling(2); pulseInvalidateAll(dut, cd)
      // fetch the window and read pred
      dut.icache.logic.cmdPort.valid #= true
      dut.icache.logic.cmdPort.payload.pc #= base
      cd.waitSamplingWhere(dut.icache.logic.cmdPort.ready.toBoolean)
      dut.icache.logic.cmdPort.valid #= false
      cd.waitSamplingWhere(dut.icache.logic.rspPort.valid.toBoolean)
      for (i <- 0 until 4) {
        val ref = PredecodeRef.classify(words(i))
        assert(dut.icache.logic.rspPort.payload.pred(i).simple.toBoolean == ref.simple, s"chunk $i simple")
        if (ref.simple)
          assert(dut.icache.logic.rspPort.payload.pred(i).lenWords.toInt == ref.lenWords, s"chunk $i len")
      }
      cd.waitSampling(4)
    }
  }
```
And add to `IcacheSim` a helper `attachMemoryWithWords(axi, cd, base, words: Seq[Int]): Axi4ReadOnlySlaveAgent` that preloads a `SparseMemory` with the given 16-bit words **little-endian** at `base, base+2, ...` (and `memByte` elsewhere is fine), then returns a serving `Axi4ReadOnlySlaveAgent` (same pattern as the existing `attachMemory`). Words are stored low-byte-first: `mem.write(base+2*i, (w & 0xff).toByte); mem.write(base+2*i+1, ((w>>8)&0xff).toByte)`.

- [ ] **Step 2: Run it, expect FAIL** (no `pred` populated / `attachMemoryWithWords` missing). `~/sbt/bin/sbt "testOnly m68k040.cache.IcacheSpec"`.

- [ ] **Step 3: Modify `IcachePlugin.scala`** per this structural spec (mirror the existing style):
  1. **Imports/types:** import `m68k040.frontend.PredecodeWord`. The line has 32 words; `wordsPerLine = 32`; predecode entry width = `32 * 4 = 128` bits.
  2. **Arrays (near `dataMem`):** add `val predMem = Seq.fill(ways)(Mem(Bits(128 bits), sets))`.
  3. **Line capture (in REFILL `when(axi.r.valid)`):** add a `val lineReg = Reg(Bits(512 bits))` and capture each beat: `lineReg(255 + 256*b downto 256*b) := axi.r.payload.data` indexed by `beatCnt` (use a `switch(beatCnt)` or `lineReg.subdivideIn(256 bits)(beatCnt) := ...` equivalent). Keep the existing `dataMem` write.
  4. **Move tag/valid commit OUT of REFILL:** in REFILL's `when(axi.r.payload.last)`, do NOT write tag/valid/victim; instead `goto(PREDECODE)`.
  5. **New `PREDECODE` state** (declare `val PREDECODE = new State` alongside REFILL/REPLAY):
     ```
     PREDECODE.whenIsActive {
       activePc := missPC
       // classify all 32 words of lineReg
       val words = lineReg.subdivideIn(16 bits)        // Vec of 32 x 16-bit, index 0 = bytes[1:0]
       val chunks = Vec(words.map(w => PredecodeWord.classify(w)))   // 32 ChunkPredecode
       val packed = chunks.asBits                                    // 128 bits
       for (w <- 0 until ways) when(victimWay === U(w, wayBits bits)) {
         predMem(w).write(missSet, packed)
         tagMem(w).write(missSet, missTag)
         valids(w)(missSet) := True                    // valid only now: data+predecode both written
       }
       victim(missSet) := victim(missSet) + 1
       goto(REPLAY)
     }
     ```
     NOTE on `subdivideIn` word order: confirm `lineReg.subdivideIn(16 bits)(k)` selects bits `[16k+15:16k]`, i.e. chunk k = the k-th 16-bit word = line bytes `2k,2k+1`. The packing `chunks.asBits` must place chunk k's 4 bits at `[4k+3:4k]` so the window slice (below) lines up. Verify against the integration test; if endianness/order is off, fix the slice/order here (not the test).
  6. **Registered pred output:** add `val rspPredReg = Reg(Vec(ChunkPredecode(), 4))`. Default-drive `rspPort.payload.pred := rspPredReg` in the default-assignments block (alongside the existing `rspPort.payload.pc/data/fault := rsp*Reg`). 
  7. **Window-pred select helper:** given a 128-bit `predEntry` and `pc`, the window is `pc(5 downto 3)` (0..7), covering chunks `w*4 .. w*4+3`. Extract: `val winBits = predEntry.subdivideIn(16 bits)(pc(5 downto 3))` (16 bits = 4 chunks), then `winBits.subdivideIn(4 bits)` → Vec of 4, map each to a `ChunkPredecode` via `.as(ChunkPredecode())` or assign fields. Define `def windowPred(predEntry: Bits, pc: UInt): Vec[ChunkPredecode]`.
  8. **Hit path (IDLE hit):** read `predMem(hitWay).readAsync(idleSet)` and set `rspPredReg := windowPred(thatEntry, idlePc)` alongside the existing `rspDataReg := idleWindow`.
  9. **REPLAY:** read `predMem(victimWay).readAsync(missSet)` and set `rspPredReg := windowPred(thatEntry, missPC)` alongside `rspDataReg := replayWindow`.

  Behavior to preserve: hit latency unchanged (registered response now also carries pred); miss path gains the one PREDECODE cycle; a line is hit-valid only after PREDECODE. All 5 existing IcacheSpec tests must still pass.

- [ ] **Step 4: Run it, expect PASS.** Run `~/sbt/bin/sbt "testOnly m68k040.cache.IcacheSpec"` — the new predecode test AND the original 5 must pass (6 total). Iterate on the chunk packing/window-slice order if `pred(i)` mismatches (the test prints which chunk); do NOT weaken assertions.
- [ ] **Step 5: Confirm gates** — `make SBT=~/sbt/bin/sbt test-fast` green; `make SBT=~/sbt/bin/sbt test-verilator` runs the predecode sweep + icache sims and passes.
- [ ] **Step 6: Commit** — `git add -A && git commit -m "cache: predecode-on-miss PREDECODE stage + predMem + window pred output"`

---

## Self-review notes (author-completed)

- **Spec coverage:** `ChunkPredecode`/`FetchRsp.pred` (§3) → T1; `PredecodeWord` classifier opword-only + simple definition (§4) → T2 (ref) + T3 (RTL≡ref exhaustive); dedicated PREDECODE cycle + `predMem` + valid-after-both-written + window exposure (§5) → T4; verification via Scala reference + curated + (here, stronger) exhaustive sweep (§7) → T2/T3/T4. Musashi-disasm cross-check remains the deferred stretch (§7). The first-cut fast set (subset of §4 list, rest → complex) is explicit and extensible.
- **Type consistency:** `ChunkPredecode{simple:Bool, lenWords:UInt(3)}` used identically in T1/T2/T3/T4; `PredecodeRef.classify(Int):CP` ↔ `PredecodeWord.classify(Bits):ChunkPredecode`; `FetchRsp.pred: Vec(ChunkPredecode,4)`; window select `pc(5 downto 3)`; 128-bit predMem entry; `attachMemoryWithWords` defined in T4 and used in T4's test.
- **Placeholder scan:** the RTL `PredecodeWord` body (T3) and the IcachePlugin modify (T4) are structured specs that "mirror PredecodeRef" / "preserve existing style", each gated by a hard test (exhaustive 65536-sweep; predecode-via-fetch + the 5 existing tests). The single source of truth (`PredecodeRef`) is given verbatim. Not placeholders — bounded, test-anchored.
- **Bias-to-complex / LUT budget:** enforced by the first-cut fast set; everything outside it (incl. deferred ADDQ/Scc/DBcc/shifts/class-0/class-4/indexed) classifies complex (T2 curated cases assert this).

## Downstream (not this plan)
Extend the fast set (ADDQ/SUBQ, Scc/DBcc, shifts, LEA/PEA, brief-indexed via the +1-word peek); Musashi-disasm length cross-check tool; the fetch/align stage that consumes `pred` for 2-wide instruction selection + pairing.
