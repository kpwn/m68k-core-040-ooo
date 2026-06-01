# Decode Sub-Slice 1 (Register/Immediate Simple) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Decode the register/immediate simple m68k instructions into pre-rename µops via two parallel `SimpleDecodeUnit`s (2-wide), emitting a `DecodeUopService` stream, verified end-to-end through the real frontend.

**Architecture:** A `DecodedUop` bundle (architectural operands) is the decode→rename contract. A pure combinational `SimpleDecodeUnit` cracks one `DecodePacket → DecodedUop` for the reg/imm forms (isolated + directed-tested like `Aligner`/`PredecodeWord`). `DecodeStage` (FiberPlugin) consumes `DecodeFeedService.feed`, runs two units on slots 0/1, and emits `DecodeUopService.uops = Stream(Vec(DecodedUop,2))` — using the **clean service-port convention** (plain directionless wires; standalone tests host a small sink/probe plugin).

**Tech Stack:** Scala 2.13.16 / SpinalHDL 1.14.1 / SpinalSim + Verilator / ScalaTest 3.2.19.

**Spec:** `docs/superpowers/specs/2026-06-01-decode-simple-design.md` (esp. §3 DecodedUop, §4 decode rules). The §6 service-port cleanup is ALREADY DONE (merged) — services are plain wires; follow that convention for `DecodeUopService`.

**Toolchain:** `sbt` at `~/sbt/bin/sbt` (NOT on PATH). Per-spec `~/sbt/bin/sbt "testOnly <spec>"`; timeout up to 590000 ms. Verilator sims tagged `m68k040.VerilatorTest`.

**Branch:** execution on fresh `feat/decode-simple` (controller creates before Task 1; not `master`).

**Existing (use as-is):** `m68k040.frontend.{DecodePacket(pc,words:Vec(16,5),wordCount,simple,lenWords,complex,fault)}`; `m68k040.services.DecodeFeedService { def feed: Stream[Vec[DecodePacket]]; def slot1Valid: Bool }` (now plain wires); `m68k040.frontend.FetchAlignPlugin`, `m68k040.cache.{IcachePlugin, IcacheSim.attachMemoryWithWords, FetchProbePlugin}`, `m68k040.mmu.IdentityTranslationPlugin`, `m68k040.core.ParamPlugin`, `m68k040.isa.{Size, Cluster}`. **Service convention:** ports are plain `Stream`/`Flow`; a plugin registers a service by `extends FiberPlugin with TheService`; standalone tests host a probe/sink plugin that wires the service to top-level IO in its OWN `during build`.

**Register numbering:** D0–D7 = 0..7, A0–A7 = 8..15 (4-bit `archReg`).

---

## File structure

| File | Responsibility |
|---|---|
| `src/main/scala/m68k040/decode/DecodedUop.scala` | `DecodedUop` bundle + `DecOp` SpinalEnum |
| `src/main/scala/m68k040/decode/SimpleDecodeUnit.scala` | combinational reg/imm decode |
| `src/main/scala/m68k040/decode/DecodeStage.scala` | FiberPlugin: two units; DecodeUopService |
| `src/main/scala/m68k040/services/Services.scala` (modify) | add `DecodeUopService` (plain wires) |
| `src/test/scala/m68k040/decode/SimpleDecodeUnitSpec.scala` | directed decode tests |
| `src/test/scala/m68k040/decode/UopSinkPlugin.scala` | test-only sink: wires DecodeUopService to top-level IO |
| `src/test/scala/m68k040/decode/DecodeStageSpec.scala` | end-to-end frontend→decode tests |

---

## Task 1: `DecodedUop` + `DecOp` + `DecodeUopService`

**Files:** Create `src/main/scala/m68k040/decode/DecodedUop.scala`; Modify `src/main/scala/m68k040/services/Services.scala`; Test `src/test/scala/m68k040/decode/DecodedUopSpec.scala`

- [ ] **Step 1: Write the failing test**
```scala
package m68k040.decode

import spinal.core._
import spinal.lib._
import org.scalatest.funsuite.AnyFunSuite

class DecodedUopSpec extends AnyFunSuite {
  test("DecodedUop has the spec 3 fields") {
    SpinalConfig().generateVerilog(new Component {
      val u = DecodedUop()
      assert(u.pc.getWidth == 32 && u.imm.getWidth == 32)
      assert(u.srcAReg.getWidth == 4 && u.dstReg.getWidth == 4)
      assert(u.cond.getWidth == 4 && u.branchDisp.getWidth == 32)
      val s = master(Stream(Vec(DecodedUop(), 2)))
      s.valid := False; s.payload.foreach(_.assignDontCare())
      val probe = out(Bool()); probe := s.payload(0).isBranch
    })
  }
}
```

- [ ] **Step 2: Run it, expect FAIL.** `~/sbt/bin/sbt "testOnly m68k040.decode.DecodedUopSpec"`.

- [ ] **Step 3: Write `DecodedUop.scala`**
```scala
package m68k040.decode

import m68k040.isa.{Cluster, Size}
import spinal.core._

object DecOp extends SpinalEnum {
  val MOVE, ADD, SUB, AND, OR, CMP, BRANCH, ILLEGAL = newElement()
}

/** Pre-rename µop: the decode→rename contract. Architectural operands
  * (D0-7 = 0..7, A0-7 = 8..15). Rename maps these to physical MicroOp fields. */
case class DecodedUop() extends Bundle {
  val valid        = Bool()
  val pc           = UInt(32 bits)
  val op           = DecOp()
  val cluster      = Cluster()
  val size         = Size()
  val srcAReg      = UInt(4 bits); val srcAValid = Bool()
  val srcBReg      = UInt(4 bits); val srcBValid = Bool()
  val dstReg       = UInt(4 bits); val dstValid  = Bool()
  val useImm       = Bool();       val imm       = Bits(32 bits)
  val readsNzvc    = Bool();       val readsX    = Bool()
  val writesNzvc   = Bool();       val writesX   = Bool()
  val isBranch     = Bool()
  val cond         = Bits(4 bits)
  val branchDisp   = Bits(32 bits)
  val unimplemented= Bool()
}
```

- [ ] **Step 4: Add `DecodeUopService` to `services/Services.scala`** (plain-wire convention; add imports if needed):
```scala
import m68k040.decode.DecodedUop

/** Produced by the decode stage; consumed by the (future) rename stage.
  * Two µops/cycle. Plain Stream (directionless) per the service convention. */
trait DecodeUopService {
  def uops: Stream[Vec[DecodedUop]]   // Vec length 2
  def uop1Valid: Bool                  // second µop valid this fire
}
```

- [ ] **Step 5: Run it, expect PASS.** `~/sbt/bin/sbt compile`.
- [ ] **Step 6: Commit** — `git add -A && git commit -m "decode: DecodedUop contract + DecOp enum + DecodeUopService"`

---

## Task 2: `SimpleDecodeUnit` — combinational reg/immediate decode

The decode brain. Pure function `DecodePacket → DecodedUop`; directed-tested. Two instances later.

**Files:** Create `src/main/scala/m68k040/decode/SimpleDecodeUnit.scala`; Test `src/test/scala/m68k040/decode/SimpleDecodeUnitSpec.scala`

**Decode rules (implement exactly; opword = `packet.words(0)`):** default `DecodedUop`: `valid := packet.valid-ish` (driven from a `valid` input), all flags False, `op := ILLEGAL`, `unimplemented := False`, regs 0/invalid, `cluster := INT`. **If `!packet.simple` → `op=ILLEGAL, unimplemented=True`** (complex isn't this unit's job). Else decode by `cls = op[15:12]`; any operand in a **memory** EA (mode ∉ {0 Dn, 1 An, and 7/4 #imm for src}) → `unimplemented := True`:
- `cls==0x7` (MOVEQ, require `op[8]==0`): `op=MOVE`, `dstReg=op[11:9]` (Dn), `dstValid`, `useImm`, `imm=S8→32(op[7:0])`, `size=LONG`, `writesNzvc`. (`op[8]==1` → ILLEGAL/unimplemented.)
- `cls∈{1,2,3}` (MOVE.B/L/W): `sizeMap: 1→BYTE,3→WORD,2→LONG`. `srcMode=op[5:3],srcReg=op[2:0]; dstMode=op[8:6],dstReg=op[11:9]`. `op=MOVE`.
  - src: mode 0 → `srcAReg=srcReg,srcAValid`; mode 1 → `srcAReg=8+srcReg,srcAValid`; mode 7/reg 4 → `useImm` (imm: BYTE/WORD = `S→32(words(1))`, LONG = `(words(1)##words(2))`); else → `unimplemented`.
  - dst: mode 0 → `dstReg=op[11:9],dstValid,writesNzvc`; mode 1 (MOVEA) → `dstReg=8+op[11:9],dstValid` (NO `writesNzvc`); else → `unimplemented`.
- `cls∈{0x8(OR),0x9(SUB),0xC(AND),0xD(ADD),0xB(CMP)}` ALU read-forms: `opmode=op[8:6]`, EA `mode=op[5:3]/reg=op[2:0]`, `Dn=op[11:9]`. EA must be reg (mode 0/1) else `unimplemented`. opmode {4,5,6} or (cls 8/C && opmode {3,7}) [MUL/DIV] → `unimplemented` (shouldn't arrive as simple, but safe). `op`: 8→OR,9→SUB,C→AND,D→ADD,B→CMP. `eaReg = (mode==1 ? 8+reg : reg)`.
  - opmode {0,1,2} (EA→Dn, size B/W/L): `srcAReg=eaReg,srcAValid; srcBReg=Dn,srcBValid; dstReg=Dn` (Dn 0..7). CMP → `!dstValid`; others → `dstValid`.
  - opmode {3,7} (ADDA/SUBA/CMPA, EA→An, size W/L): `srcAReg=eaReg,srcAValid; srcBReg=8+Dn,srcBValid; dstReg=8+Dn`. CMPA(cls B) → `!dstValid`; ADDA/SUBA → `dstValid`.
  - Flags: ADD/SUB → `writesNzvc+writesX`; AND/OR → `writesNzvc`; CMP/CMPA → `writesNzvc, !dstValid`; ADDA/SUBA → none.
  - size: opmode 0→BYTE,1→WORD,2→LONG,3→WORD,7→LONG.
- `cls==0x6` (Bcc/BSR/BRA): `op=BRANCH, isBranch, cond=op[11:8]`. `disp8=op[7:0]`. `branchDisp = disp8==0x00 ? S16→32(words(1)) : disp8==0xFF ? (words(1)##words(2)) : S8→32(disp8)`. `readsNzvc = cond >= 2` (conditional). No reg ops/writes.
- else → `op=ILLEGAL, unimplemented=True`.

- [ ] **Step 1: Write the failing test `SimpleDecodeUnitSpec.scala`** (construct packets, assert uop fields):
```scala
package m68k040.decode

import m68k040.VerilatorTest
import m68k040.frontend.DecodePacket
import m68k040.isa.Size
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import org.scalatest.funsuite.AnyFunSuite

class SimpleDecodeUnitSpec extends AnyFunSuite {
  class Dut extends Component {
    val pkt = in(DecodePacket())
    val uop = out(DecodedUop())
    uop := SimpleDecodeUnit.decode(pkt)
  }
  def drivePkt(dut: Dut, opword: Int, w1: Int = 0, w2: Int = 0, simple: Boolean = true, len: Int = 1): Unit = {
    dut.pkt.valid #= true; dut.pkt.pc #= 0x1000; dut.pkt.simple #= simple
    dut.pkt.complex #= !simple; dut.pkt.lenWords #= len; dut.pkt.wordCount #= len; dut.pkt.fault #= false
    dut.pkt.words(0) #= opword; dut.pkt.words(1) #= w1; dut.pkt.words(2) #= w2
    dut.pkt.words(3) #= 0; dut.pkt.words(4) #= 0
  }
  test("MOVEQ #5,D3", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      drivePkt(dut, 0x7605); sleep(1)  // 0111 011(d=3) 0 00000101
      assert(dut.uop.op.toEnum == DecOp.MOVE && dut.uop.dstReg.toInt == 3 && dut.uop.dstValid.toBoolean)
      assert(dut.uop.useImm.toBoolean && dut.uop.imm.toLong == 5)
      assert(dut.uop.size.toEnum == Size.LONG && dut.uop.writesNzvc.toBoolean && !dut.uop.writesX.toBoolean)
    }
  }
  test("MOVE.W D0,D1 (reg->reg)", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      drivePkt(dut, 0x3200); sleep(1)  // MOVE.W D0->D1
      assert(dut.uop.op.toEnum == DecOp.MOVE && dut.uop.srcAReg.toInt == 0 && dut.uop.srcAValid.toBoolean)
      assert(dut.uop.dstReg.toInt == 1 && dut.uop.dstValid.toBoolean && dut.uop.writesNzvc.toBoolean)
    }
  }
  test("MOVEA.L A0,A1 (no flags)", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      drivePkt(dut, 0x2248); sleep(1)  // 0010 dstReg=001(A1) dstMode=001 srcMode=001(An) srcReg=000(A0) = 0x2248
      assert(dut.uop.op.toEnum == DecOp.MOVE && dut.uop.srcAReg.toInt == 8 && dut.uop.dstReg.toInt == 9)
      assert(dut.uop.dstValid.toBoolean && !dut.uop.writesNzvc.toBoolean)
    }
  }
  test("ADD.L D1,D0 (writes NZVC+X)", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      drivePkt(dut, 0xD081); sleep(1)  // 1101 Dn=000 opmode010 ea=D1(mode000 reg001)=0xD081
      assert(dut.uop.op.toEnum == DecOp.ADD && dut.uop.srcAReg.toInt == 1 && dut.uop.srcBReg.toInt == 0)
      assert(dut.uop.dstReg.toInt == 0 && dut.uop.dstValid.toBoolean)
      assert(dut.uop.writesNzvc.toBoolean && dut.uop.writesX.toBoolean)
    }
  }
  test("CMP.W D2,D3 (no dst write)", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      drivePkt(dut, 0xB642); sleep(1)  // 1011 Dn=011(D3) opmode001(W,CMP) ea=D2(mode000 reg010) = 0xB642
      assert(dut.uop.op.toEnum == DecOp.CMP && !dut.uop.dstValid.toBoolean && dut.uop.writesNzvc.toBoolean)
      assert(dut.uop.srcAReg.toInt == 2 && dut.uop.srcBReg.toInt == 3)
    }
  }
  test("BRA.w", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      drivePkt(dut, 0x6000, w1 = 0x0010, len = 2); sleep(1)
      assert(dut.uop.op.toEnum == DecOp.BRANCH && dut.uop.isBranch.toBoolean && dut.uop.cond.toInt == 0)
      assert(dut.uop.branchDisp.toLong == 0x10 && !dut.uop.readsNzvc.toBoolean)
    }
  }
  test("BEQ.s +4 (reads flags)", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      drivePkt(dut, 0x6704); sleep(1)  // 0110 0111(BEQ) 00000100
      assert(dut.uop.op.toEnum == DecOp.BRANCH && dut.uop.cond.toInt == 7 && dut.uop.branchDisp.toLong == 4)
      assert(dut.uop.readsNzvc.toBoolean)
    }
  }
  test("complex packet -> unimplemented", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      drivePkt(dut, 0xC0C1, simple = false); sleep(1)  // MULU complex
      assert(dut.uop.unimplemented.toBoolean && dut.uop.op.toEnum == DecOp.ILLEGAL)
    }
  }
  test("memory-EA simple (MOVE (A0),D0) -> unimplemented", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      drivePkt(dut, 0x2010); sleep(1)  // MOVE.L (A0),D0 : src mode010
      assert(dut.uop.unimplemented.toBoolean)
    }
  }
}
```

- [ ] **Step 2: Run it, expect FAIL** (`SimpleDecodeUnit` undefined).
- [ ] **Step 3: Write `SimpleDecodeUnit.scala`** — `object SimpleDecodeUnit { def decode(pkt: DecodePacket): DecodedUop = {...} }`, implementing the rules above. Default-assign every field of the returned `DecodedUop` then override per class. Sign-extension: `S8→32 = B(op(7 downto 0)).asSInt.resize(32).asBits`; `S16→32` similar from `words(1)`. Use the opword bit slices exactly as specified.
- [ ] **Step 4: Run it, expect PASS** (9 tests). Iterate to green; if a hand-derived opword in a test is wrong, re-derive from the m68k encoding (don't flip assertions blindly) — but the encodings above are checked. Fix the decode logic to match.
- [ ] **Step 5: Commit** — `git add -A && git commit -m "decode: SimpleDecodeUnit (reg/immediate combinational decode)"`

---

## Task 3: `DecodeStage` plugin + end-to-end frontend test

**Files:** Create `src/main/scala/m68k040/decode/DecodeStage.scala`; Create `src/test/scala/m68k040/decode/UopSinkPlugin.scala`; Test `src/test/scala/m68k040/decode/DecodeStageSpec.scala`

**`DecodeStage` (structural spec):** `class DecodeStage extends FiberPlugin with DecodeUopService`. In `during build`:
- `val df = host[DecodeFeedService]`.
- `val uopsPort = Stream(Vec(DecodedUop(), 2))` (plain). `val uop1ValidReg = ...` — actually drive combinationally: `def uops = logic.uopsPort`, `def uop1Valid = logic.uop1Sig`.
- Decode both slots: `uopsPort.payload(0) := SimpleDecodeUnit.decode(df.feed.payload(0))`; `uopsPort.payload(1) := SimpleDecodeUnit.decode(df.feed.payload(1))`. `uopsPort.valid := df.feed.valid`; `uop1Sig := df.feed.valid && df.slot1Valid`. `df.feed.ready := uopsPort.ready` (1:1 passthrough; combinational decode). Set each `payload(i).valid` from feed validity (slot0 = feed.valid; slot1 = uop1Sig).
- Register `extends ... with DecodeUopService`.

**`UopSinkPlugin` (test helper):** `class UopSinkPlugin extends FiberPlugin`; in `during build`: `val du = host[DecodeUopService]; val uopsOut = master(Stream(Vec(DecodedUop(),2))); uopsOut << du.uops` (exposes the µop stream as top-level IO + drives `du.uops.ready`). Test pokes `uopsOut.ready`, reads `uopsOut.payload`/`du.uop1Valid` (via `dut.sink.logic.uopsOut`). (Mirror `FetchProbePlugin`.)

- [ ] **Step 1: Write the failing end-to-end test `DecodeStageSpec.scala`:**
```scala
package m68k040.decode

import m68k040.{M68kParams, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.mmu.IdentityTranslationPlugin
import m68k040.cache.{IcachePlugin, IcacheSim}
import m68k040.frontend.FetchAlignPlugin
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

class DecodeStageSpec extends AnyFunSuite {
  class Dut extends Component {
    val db = new Database
    val host = db on (new PluginHost)
    val ic = new IcachePlugin
    val fa = new FetchAlignPlugin
    val dec = new DecodeStage
    val sink = new UopSinkPlugin
    db.on { host.asHostOf(Seq[FiberPlugin](
      new ParamPlugin(M68kParams()), new IdentityTranslationPlugin, ic, fa, dec, sink)) }
  }
  test("frontend decodes a 2-wide MOVEQ stream into MOVE uops", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      val base = 0x8000L
      // MOVEQ #1,D0 (0x7001), MOVEQ #2,D1 (0x7201), ...
      IcacheSim.attachMemoryWithWords(dut.ic.logic.axi, cd, base, Seq(0x7001,0x7201,0x7402,0x7603))
      dut.sink.logic.uopsOut.ready #= false
      dut.fa.logic.resume.valid #= false
      dut.fa.logic.redirect.valid #= true; dut.fa.logic.redirect.payload #= base
      cd.waitSampling(); dut.fa.logic.redirect.valid #= false
      dut.sink.logic.uopsOut.ready #= true
      cd.waitSamplingWhere(dut.sink.logic.uopsOut.valid.toBoolean)
      val u0 = dut.sink.logic.uopsOut.payload(0)
      assert(u0.op.toEnum == DecOp.MOVE && u0.dstReg.toInt == 0 && u0.imm.toLong == 1, s"u0 dst=${u0.dstReg.toInt} imm=${u0.imm.toLong}")
      assert(dut.dec.logic.uop1Sig.toBoolean, "slot1 should be valid (2-wide)")
      val u1 = dut.sink.logic.uopsOut.payload(1)
      assert(u1.op.toEnum == DecOp.MOVE && u1.dstReg.toInt == 1 && u1.imm.toLong == 2, s"u1 dst=${u1.dstReg.toInt} imm=${u1.imm.toLong}")
    }
  }
}
```
(If `uop1Sig` isn't directly reachable, expose `uop1Valid` via the sink: add `val u1v = out(Bool()); u1v := du.uop1Valid` to `UopSinkPlugin` and assert `dut.sink.logic.u1v`.)

- [ ] **Step 2: Run it, expect FAIL** (`DecodeStage`/`UopSinkPlugin` undefined). `~/sbt/bin/sbt "testOnly m68k040.decode.DecodeStageSpec"`.
- [ ] **Step 3: Implement `DecodeStage.scala` + `UopSinkPlugin.scala`** per the structural specs. `DecodeStage` consumes `host[DecodeFeedService]`, decodes both slots, emits `uops` (plain Stream), `feed.ready := uops.ready`. `UopSinkPlugin` exposes the µop stream + drives ready. Expose ports as public vals on the logic Areas for the testbench.
- [ ] **Step 4: Run it, expect PASS.** Iterate the feed/uops handshake (1:1 passthrough) and the slot-valid wiring to green; do NOT weaken assertions.
- [ ] **Step 5: Confirm gates** — `make SBT=~/sbt/bin/sbt test-fast` green; `make SBT=~/sbt/bin/sbt test-verilator` (SimpleDecodeUnit 9 + DecodeStage 1 + prior) all pass.
- [ ] **Step 6: Commit** — `git add -A && git commit -m "decode: DecodeStage (two SimpleDecodeUnits, 2-wide) + e2e frontend test"`

---

## Self-review notes (author-completed)
- **Spec coverage:** §3 DecodedUop → T1; §4 decode rules → T2 (directed); §5 DecodeStage + DecodeUopService → T3; §6 service cleanup → DONE (merged) and followed (plain-wire `DecodeUopService` + `UopSinkPlugin`). §7 verification → T2 directed + T3 end-to-end. Deferred (§10): memory-EA decode, complex/microcode, rename.
- **Type consistency:** `DecodedUop` fields (T1) used identically in `SimpleDecodeUnit` (T2) and `DecodeStage`/tests (T3); `DecOp` members; `DecodeUopService { uops: Stream[Vec[DecodedUop]]; uop1Valid }`; reg numbering D0-7=0..7/A0-8..15 consistent.
- **Calibration:** DecodedUop + directed decode tests are verbatim/concrete (the tests are the correctness anchor with hand-derived opwords); SimpleDecodeUnit + DecodeStage bodies are structured specs implemented against the rules + gating tests.

## Downstream (not this plan)
Sub-slice 2 (memory-EA decode → load/store µops); sub-slice 3 (complex/microcode + `resume` driver); rename (consumes `DecodeUopService` → physical `MicroOp`).
