# Fetch/Align Stage Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the fetch/align stage that buffers I-cache windows + predecode and emits a 2-wide `DecodePacket` stream, chaining instruction boundaries via predecode `lenWords` (simple) and stalling for `resume` on complex.

**Architecture:** Decomposed to isolate the hard logic — a **pure combinational `Aligner`** (constructed-window in → slot0/slot1 + shift + stall out, unit-tested directly), a stateful **`InstructionBuffer`** (word+predecode queue, tested standalone), and a **`FetchAlignPlugin`** that wires `FetchControl` (sequential PC + redirect/resume) + buffer + aligner to `FetchService`, tested end-to-end through the real I-cache.

**Tech Stack:** Scala 2.13.16 / SpinalHDL 1.14.1 / SpinalSim + Verilator 5.032 / ScalaTest 3.2.19.

**Spec:** `docs/superpowers/specs/2026-05-31-fetch-align-design.md`. Read it.

**Toolchain:** `sbt` at `~/sbt/bin/sbt` (NOT on PATH). Per-spec: `~/sbt/bin/sbt "testOnly <spec>"`, timeout up to 590000 ms. Verilator sims tagged `m68k040.VerilatorTest`; `make SBT=~/sbt/bin/sbt test-verilator`.

**Branch:** execution on fresh `feat/fetch-align` (controller creates before Task 1; not `master`).

**Existing (use as-is):** `m68k040.cache.{FetchCmd, FetchRsp(pc,data:64,fault,pred:Vec(ChunkPredecode,4)), ChunkPredecode(simple,lenWords:3)}`; `m68k040.services.FetchService(cmd:Stream[FetchCmd], rsp:Flow[FetchRsp])`; `IcachePlugin`, `IdentityTranslationPlugin`, `ParamPlugin`; `IcacheSim.attachMemoryWithWords(axi,cd,base,words)`. Plugin-registers-service-by-`extends FiberPlugin with TheService`; testbench reaches plugin ports via `dut.<plugin>.logic.*` post-elaboration (do NOT touch `logic.*` during host construction).

**Key spec rules:** no branch prediction (sequential fetch + `redirect`/`resume` ports); 2-wide only when head AND head+L0 are both `simple` and buffered; `complex` head → 1-wide packet + stall for `resume`; `lenWords` is in **words** (bytes = 2·lenWords); fetch is single-outstanding.

---

## File structure

| File | Responsibility |
|---|---|
| `src/main/scala/m68k040/frontend/DecodePacket.scala` | `DecodePacket` bundle |
| `src/main/scala/m68k040/frontend/Aligner.scala` | pure combinational boundary chaining (slot0/slot1/shift/stall) |
| `src/main/scala/m68k040/frontend/InstructionBuffer.scala` | word+predecode queue (enqueue/shift/flush/present) |
| `src/main/scala/m68k040/frontend/FetchAlignPlugin.scala` | FetchControl + buffer + aligner; resolves FetchService; decode stream + redirect/resume |
| `src/main/scala/m68k040/services/Services.scala` (modify) | add `DecodeFeedService` |
| `src/test/scala/m68k040/frontend/AlignerSpec.scala` | directed Aligner unit tests |
| `src/test/scala/m68k040/frontend/InstructionBufferSpec.scala` | buffer sim tests |
| `src/test/scala/m68k040/frontend/FetchAlignSpec.scala` | end-to-end frontend integration tests |

---

## Task 1: `DecodePacket` type + `DecodeFeedService`

**Files:** Create `src/main/scala/m68k040/frontend/DecodePacket.scala`; Modify `src/main/scala/m68k040/services/Services.scala`; Test `src/test/scala/m68k040/frontend/DecodePacketSpec.scala`

- [ ] **Step 1: Write the failing test**
```scala
package m68k040.frontend

import spinal.core._
import spinal.lib._
import org.scalatest.funsuite.AnyFunSuite

class DecodePacketSpec extends AnyFunSuite {
  test("DecodePacket carries pc, up to 5 words, predecode flags") {
    SpinalConfig().generateVerilog(new Component {
      val p = DecodePacket()
      assert(p.pc.getWidth == 32)
      assert(p.words.length == 5 && p.words(0).getWidth == 16)
      assert(p.wordCount.getWidth == 3)
      assert(p.lenWords.getWidth == 3)
      val s = master(Stream(Vec(DecodePacket(), 2)))
      s.valid := False; s.payload.foreach(_.assignDontCare())
      val probe = out(Bool()); probe := s.payload(0).simple
    })
  }
}
```

- [ ] **Step 2: Run it, expect FAIL.** `~/sbt/bin/sbt "testOnly m68k040.frontend.DecodePacketSpec"`.

- [ ] **Step 3: Write `DecodePacket.scala`**
```scala
package m68k040.frontend

import spinal.core._

/** One m68k instruction handed to the (future) decode stage. */
case class DecodePacket() extends Bundle {
  val pc        = UInt(32 bits)
  val words     = Vec(Bits(16 bits), 5)   // opword + up to 4 following words (10 bytes max simple)
  val wordCount = UInt(3 bits)            // valid words in `words` (1..5)
  val simple    = Bool()
  val lenWords  = UInt(3 bits)            // predecode length in words (meaningful iff simple)
  val complex   = Bool()                  // !simple
  val fault     = Bool()
}
```

- [ ] **Step 4: Add `DecodeFeedService` to `services/Services.scala`** (append; add imports if absent):
```scala
import m68k040.frontend.DecodePacket
import spinal.lib.{Stream, Vec}

/** Produced by the fetch/align stage; consumed by the (future) decode stage.
  * Two packets/cycle; slot 0 valid when the stream fires, slot 1 on 2-wide cycles. */
trait DecodeFeedService {
  def feed: Stream[Vec[DecodePacket]]   // Vec length 2
  def slot1Valid: Bool                  // is the second packet valid this fire?
}
```
(`Vec` import: SpinalHDL `Vec` is `spinal.core.Vec`; if `spinal.lib` doesn't export it, import `spinal.core.Vec`. Resolve at compile.)

- [ ] **Step 5: Run it, expect PASS.** `~/sbt/bin/sbt compile`.
- [ ] **Step 6: Commit** — `git add -A && git commit -m "frontend: DecodePacket type + DecodeFeedService"`

---

## Task 2: `Aligner` — pure combinational boundary chaining

The brain of the slice, isolated as a pure function so it's unit-tested directly with constructed windows (no buffer/fetch).

**Files:** Create `src/main/scala/m68k040/frontend/Aligner.scala`; Test `src/test/scala/m68k040/frontend/AlignerSpec.scala`

**Interface (structural spec):**
```scala
object Aligner {
  val WINDOW = 10   // head words the aligner inspects (2 max simple instrs = 10 words)
  case class Result() extends Bundle {
    val slot0      = DecodePacket()
    val slot0Valid = Bool()
    val slot1      = DecodePacket()
    val slot1Valid = Bool()
    val shiftWords = UInt(4 bits)   // words to consume from the buffer head this cycle (0..10)
    val stall      = Bool()         // nothing emittable (need more bytes) OR complex-stall
    val complex    = Bool()         // head is complex -> emitted slot0, awaiting resume
  }
  /** headPc = PC of words(0). words/preds = buffered head words (index 0 = head). avail = #valid buffered words. */
  def align(headPc: UInt, words: Vec[Bits], preds: Vec[ChunkPredecode], avail: UInt): Result
}
```

**Logic (implement exactly):**
- `p0 = preds(0)`, `L0 = p0.lenWords`.
- **avail == 0** → all-invalid, `stall := True`, `shiftWords := 0`.
- **p0.complex (`!p0.simple`)** and `avail >= 1` → `slot0` = complex packet: `pc=headPc`, `words` = `words(0..4)` (copy what's there), `wordCount = min(avail,5)`, `simple=False`, `complex=True`, `lenWords=0`, `fault=False`; `slot0Valid=True`, `slot1Valid=False`, `shiftWords := 0`, `stall := True`, `complex := True`. (Stall: plugin holds until `resume`.)
- **p0.simple:**
  - if `avail < L0` → `stall := True`, `slot0Valid := False`, `shiftWords := 0`.
  - else `slot0` = simple packet: `pc=headPc`, `words(i)=words(i) for i<L0 else 0`, `wordCount=L0`, `simple=True`, `lenWords=L0`, `complex=False`; `slot0Valid=True`.
    - `p1 = preds(L0)`, `L1 = p1.lenWords`. **slot1 valid** iff `p1.simple && avail >= L0 + L1` (and `L0+L1 <= WINDOW`). If valid: `slot1` = simple packet `pc=headPc + (L0<<1)`, `words(i)=words(L0+i) for i<L1 else 0`, `wordCount=L1`, `simple=True`, `lenWords=L1`; `slot1Valid=True`, `shiftWords := L0 + L1`.
    - else `slot1Valid := False`, `shiftWords := L0`.
  - `stall := !slot0Valid`, `complex := False`.

- [ ] **Step 1: Write the failing test `AlignerSpec.scala`** — drive constructed windows; assert outputs. (`VerilatorTest` — wraps the function in a Component.)
```scala
package m68k040.frontend

import m68k040.VerilatorTest
import m68k040.cache.ChunkPredecode
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import org.scalatest.funsuite.AnyFunSuite

class AlignerSpec extends AnyFunSuite {
  class Dut extends Component {
    val headPc = in UInt(32 bits)
    val words  = in Vec(Bits(16 bits), Aligner.WINDOW)
    val preds  = in Vec(ChunkPredecode(), Aligner.WINDOW)
    val avail  = in UInt(4 bits)
    val res    = out(Aligner.Result())
    res := Aligner.align(headPc, words, preds, avail)
  }
  def setPred(dut: Dut, i: Int, simple: Boolean, len: Int): Unit = {
    dut.preds(i).simple #= simple; dut.preds(i).lenWords #= len
  }
  test("two adjacent 1-word simple ops -> 2-wide", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      dut.headPc #= 0x1000
      for (i <- 0 until Aligner.WINDOW) { dut.words(i) #= 0; setPred(dut, i, simple=true, len=1) }
      dut.avail #= 10
      sleep(1)
      assert(dut.res.slot0Valid.toBoolean && dut.res.slot1Valid.toBoolean)
      assert(dut.res.slot0.lenWords.toInt == 1 && dut.res.slot1.lenWords.toInt == 1)
      assert(dut.res.slot1.pc.toLong == 0x1002)   // headPc + 2*L0
      assert(dut.res.shiftWords.toInt == 2)
      assert(!dut.res.stall.toBoolean && !dut.res.complex.toBoolean)
    }
  }
  test("3-word simple then 1-word simple -> 2-wide, slot1 pc=+6, shift=4", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      dut.headPc #= 0x2000
      for (i <- 0 until Aligner.WINDOW) { dut.words(i) #= 0; setPred(dut, i, simple=true, len=1) }
      setPred(dut, 0, simple=true, len=3)   // head is 3-word; next instr at index 3
      dut.avail #= 10
      sleep(1)
      assert(dut.res.slot0Valid.toBoolean && dut.res.slot1Valid.toBoolean)
      assert(dut.res.slot0.lenWords.toInt == 3)
      assert(dut.res.slot1.pc.toLong == 0x2006 && dut.res.slot1.lenWords.toInt == 1)
      assert(dut.res.shiftWords.toInt == 4)
    }
  }
  test("simple head + complex second -> 1-wide, shift=L0", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      dut.headPc #= 0x3000
      for (i <- 0 until Aligner.WINDOW) { dut.words(i) #= 0; setPred(dut, i, simple=true, len=1) }
      setPred(dut, 1, simple=false, len=0)  // second instruction complex
      dut.avail #= 10
      sleep(1)
      assert(dut.res.slot0Valid.toBoolean && !dut.res.slot1Valid.toBoolean)
      assert(dut.res.shiftWords.toInt == 1 && !dut.res.complex.toBoolean)
    }
  }
  test("complex head -> 1-wide complex packet, stall, shift=0", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      dut.headPc #= 0x4000
      for (i <- 0 until Aligner.WINDOW) { dut.words(i) #= 0; setPred(dut, i, simple=true, len=1) }
      setPred(dut, 0, simple=false, len=0)
      dut.avail #= 10
      sleep(1)
      assert(dut.res.slot0Valid.toBoolean && dut.res.slot0.complex.toBoolean)
      assert(!dut.res.slot1Valid.toBoolean && dut.res.complex.toBoolean && dut.res.stall.toBoolean)
      assert(dut.res.shiftWords.toInt == 0)
    }
  }
  test("insufficient bytes for head -> stall, slot0 invalid", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      dut.headPc #= 0x5000
      for (i <- 0 until Aligner.WINDOW) { dut.words(i) #= 0; setPred(dut, i, simple=true, len=3) }
      dut.avail #= 2   // head claims 3 words, only 2 available
      sleep(1)
      assert(!dut.res.slot0Valid.toBoolean && dut.res.stall.toBoolean && dut.res.shiftWords.toInt == 0)
    }
  }
}
```

- [ ] **Step 2: Run it, expect FAIL** (`Aligner` undefined). `~/sbt/bin/sbt "testOnly m68k040.frontend.AlignerSpec"`.
- [ ] **Step 3: Write `Aligner.scala`** implementing the logic above. Use dynamic `Vec` indexing (`preds(L0)`) — `L0`/`L1` are `UInt`. Build `words(i) for i<L0` via a loop with `when(i < L0)`. The `Result` is a `Bundle`; drive every field (default-assign then override) so nothing is undriven. `shiftWords` max = `L0+L1` ≤ 10 → 4 bits.
- [ ] **Step 4: Run it, expect PASS** (5 tests). Iterate the combinational logic to match the directed expectations; do NOT weaken assertions.
- [ ] **Step 5: Commit** — `git add -A && git commit -m "frontend: Aligner 2-wide boundary chaining (combinational)"`

---

## Task 3: `InstructionBuffer` — word+predecode queue

**Files:** Create `src/main/scala/m68k040/frontend/InstructionBuffer.scala`; Test `src/test/scala/m68k040/frontend/InstructionBufferSpec.scala`

**Structural spec:** A `Component` holding `BUF_WORDS = 12` entries of `{word: Bits(16), pred: ChunkPredecode}` plus a `count` register. IO:
- `push: slave Stream(Bundle{ words: Vec(Bits16,4); preds: Vec(ChunkPredecode,4); n: UInt(3) })` — enqueue `n` (0..4) words from a fetch window (n<4 when the first post-redirect fetch starts mid-window). `push.ready` when `count + 4 <= BUF_WORDS`.
- `head: out Vec(Bits16, 10)`, `headPred: out Vec(ChunkPredecode, 10)`, `avail: out UInt(4)` — the first `min(count,10)` buffered words/preds (index 0 = oldest), `avail = min(count,10)`. Entries beyond `count` are don't-care.
- `shift: in UInt(4)` — consume `shift` words from the head (0..10) this cycle.
- `flush: in Bool` — clear (count:=0).
Implement as a shift register: on a cycle, `count := count - shift + (push.fire ? n : 0)`; words shift down by `shift`, then appended push words land at the new tail. (Order: shift-out happens, push-in appends.) `flush` overrides (count:=0).

- [ ] **Step 1: Write the failing test** — push words, read head, shift, flush; assert order/avail.
```scala
package m68k040.frontend

import m68k040.VerilatorTest
import m68k040.cache.ChunkPredecode
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import org.scalatest.funsuite.AnyFunSuite

class InstructionBufferSpec extends AnyFunSuite {
  test("push two windows, head reflects FIFO order; shift consumes; flush clears", VerilatorTest) {
    SimConfig.withVerilator.compile(new InstructionBuffer).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.flush #= true; dut.io.shift #= 0; dut.io.push.valid #= false
      dut.clockDomain.waitSampling(); dut.io.flush #= false
      def pushWindow(base: Int, n: Int): Unit = {
        dut.io.push.valid #= true
        for (i <- 0 until 4) { dut.io.push.payload.words(i) #= (base + i); dut.io.push.payload.preds(i).simple #= true; dut.io.push.payload.preds(i).lenWords #= 1 }
        dut.io.push.payload.n #= n
        dut.clockDomain.waitSamplingWhere(dut.io.push.ready.toBoolean)
        dut.io.push.valid #= false
      }
      pushWindow(0x10, 4)   // words 0x10,0x11,0x12,0x13
      dut.clockDomain.waitSampling()
      assert(dut.io.avail.toInt >= 4)
      assert(dut.io.head(0).toInt == 0x10 && dut.io.head(3).toInt == 0x13)
      dut.io.shift #= 2; dut.clockDomain.waitSampling(); dut.io.shift #= 0; dut.clockDomain.waitSampling()
      assert(dut.io.head(0).toInt == 0x12, s"after shift 2, head should be 0x12, got ${dut.io.head(0).toInt}")
      dut.io.flush #= true; dut.clockDomain.waitSampling(); dut.io.flush #= false; dut.clockDomain.waitSampling()
      assert(dut.io.avail.toInt == 0)
    }
  }
}
```

- [ ] **Step 2: Run it, expect FAIL.** `~/sbt/bin/sbt "testOnly m68k040.frontend.InstructionBufferSpec"`.
- [ ] **Step 3: Write `InstructionBuffer.scala`** per the structural spec (shift-register of `BUF_WORDS=12`, `push`/`head`/`headPred`/`avail`/`shift`/`flush`). Async reads on `head`/`avail` (combinational from the register array). Drive all IO.
- [ ] **Step 4: Run it, expect PASS.** Iterate the shift/append ordering to match; do NOT weaken assertions.
- [ ] **Step 5: Commit** — `git add -A && git commit -m "frontend: InstructionBuffer word+predecode queue"`

---

## Task 4: `FetchAlignPlugin` — integration + end-to-end frontend tests

**Files:** Create `src/main/scala/m68k040/frontend/FetchAlignPlugin.scala`; Test `src/test/scala/m68k040/frontend/FetchAlignSpec.scala`

**Structural spec** — `class FetchAlignPlugin extends FiberPlugin with DecodeFeedService` (register synchronously). In `during build new Area`:
- Resolve `val ic = host[FetchService]`.
- Instantiate `InstructionBuffer`.
- IO ports (exposed for testbench): `val feed = master(Stream(Vec(DecodePacket(),2)))`, `val slot1ValidReg = Reg(Bool())` (drive a `slot1Valid` output), `val redirect = slave(Flow(UInt(32 bits)))`, `val resume = slave(Flow(UInt(32 bits)))`. Implement `def feed`/`def slot1Valid` from these.
- **FetchControl:** registers `nextFetchPc`, `decodePc`, `stalledOnComplex`. Drive `ic.cmd.valid` when buffer has room and not stalled and (single-outstanding) no fetch in flight; `ic.cmd.pc := nextFetchPc` (8-aligned). On `ic.rsp.fire`, enqueue into the buffer: compute `n` and the window words from `ic.rsp.data.subdivideIn(16 bits)` (4 words) and `ic.rsp.pred` (4 ChunkPredecode) — drop words before `decodePc`'s position within the window on the first fetch after a redirect/resume (use `decodePc(2 downto 1)` vs window base). Advance `nextFetchPc += 8` per accepted cmd.
- **Aligner:** feed `Aligner.align(decodePc, buffer.head, buffer.headPred, buffer.avail)`. Drive `feed.payload(0) := res.slot0`, `feed.payload(1) := res.slot1`, `feed.valid := res.slot0Valid && !res.complexHandledThisCycle...` — i.e. `feed.valid := res.slot0Valid`. `slot1Valid := res.slot1Valid`. On `feed.fire`: `buffer.shift := res.shiftWords`; `decodePc := decodePc + (res.shiftWords << 1)`. (When `feed` not ready, `buffer.shift := 0`, hold.)
- **Complex stall:** when `res.complex` and `feed.fire`, set `stalledOnComplex := True` (emitted the complex packet; do not advance — `shiftWords`=0). While stalled, stop issuing the aligner/feed for new packets until `resume.valid`. On `resume.valid`: `decodePc := resume.payload`, `nextFetchPc := resume.payload & ~7`, flush buffer, `stalledOnComplex := False`.
- **Redirect:** on `redirect.valid`: `decodePc := redirect.payload`, `nextFetchPc := redirect.payload & ~7`, flush buffer, clear stall, drop in-flight rsp (staleness: track an `expectFetchPc`; if `ic.rsp.pc` (window base) != expected, discard).
- Default-drive all outputs; registered `feed`/`slot1Valid` for clean timing if needed (baseline: combinational `feed` from aligner with the buffer providing registered head — acceptable since buffer head is registered).

- [ ] **Step 1: Write the failing end-to-end test `FetchAlignSpec.scala`.** Host `ParamPlugin + IdentityTranslationPlugin + IcachePlugin + FetchAlignPlugin`; preload a known instruction stream; drive `redirect` to start; consume `feed`; assert packets.
```scala
package m68k040.frontend

import m68k040.{M68kParams, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.mmu.IdentityTranslationPlugin
import m68k040.cache.{IcachePlugin, IcacheSim}
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

class FetchAlignSpec extends AnyFunSuite {
  class Dut extends Component {
    val db = new Database
    val host = db on (new PluginHost)
    val ic = new IcachePlugin
    val fa = new FetchAlignPlugin
    db.on { host.asHostOf(Seq[FiberPlugin](
      new ParamPlugin(M68kParams()), new IdentityTranslationPlugin, ic, fa)) }
  }

  /** Consume one stream fire; returns (slot0 pc, slot0 lenWords, slot0 complex, slot1Valid, slot1 pc). */
  def nextFire(dut: Dut, cd: ClockDomain): (Long, Int, Boolean, Boolean, Long) = {
    dut.fa.logic.feed.ready #= true
    cd.waitSamplingWhere(dut.fa.logic.feed.valid.toBoolean)
    val s0 = dut.fa.logic.feed.payload(0)
    val r = (s0.pc.toLong, s0.lenWords.toInt, s0.complex.toBoolean,
             dut.fa.logic.slot1Valid.toBoolean, dut.fa.logic.feed.payload(1).pc.toLong)
    cd.waitSampling()
    r
  }

  test("2-wide stream of 1-word simple ops with correct PCs", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      val base = 0x6000L
      // MOVEQ #0,D0 (0x7000) x8 — all simple len1
      val words = Seq.fill(8)(0x7000)
      IcacheSim.attachMemoryWithWords(dut.ic.logic.axi, cd, base, words)
      dut.fa.logic.feed.ready #= false
      dut.fa.logic.resume.valid #= false
      dut.fa.logic.redirect.valid #= true; dut.fa.logic.redirect.payload #= base
      cd.waitSampling(); dut.fa.logic.redirect.valid #= false
      // first fire should give slot0 pc=base, slot1 pc=base+2, both len1
      val (pc0, len0, cx0, s1v, pc1) = nextFire(dut, cd)
      assert(pc0 == base && len0 == 1 && !cx0, s"slot0 pc=$pc0 len=$len0")
      assert(s1v && pc1 == base + 2, s"slot1 valid=$s1v pc=$pc1")
    }
  }

  test("complex instruction emits 1-wide complex packet then stalls until resume", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      val base = 0x7000L
      // MOVEQ(simple1), MULU.W D1,D0 (0xC0C1, complex), MOVEQ...
      val words = Seq(0x7000, 0xC0C1, 0x7001, 0x7002, 0x7003, 0x7004)
      IcacheSim.attachMemoryWithWords(dut.ic.logic.axi, cd, base, words)
      dut.fa.logic.feed.ready #= false; dut.fa.logic.resume.valid #= false
      dut.fa.logic.redirect.valid #= true; dut.fa.logic.redirect.payload #= base
      cd.waitSampling(); dut.fa.logic.redirect.valid #= false
      // consume packets until we see the complex one at base+2
      var sawComplex = false; var guard = 0
      while (!sawComplex && guard < 50) {
        dut.fa.logic.feed.ready #= true
        cd.waitSamplingWhere(dut.fa.logic.feed.valid.toBoolean)
        val s0 = dut.fa.logic.feed.payload(0)
        if (s0.complex.toBoolean) { assert(s0.pc.toLong == base + 2, s"complex at ${s0.pc.toLong.toHexString}"); sawComplex = true }
        cd.waitSampling(); guard += 1
      }
      assert(sawComplex, "should have emitted a complex packet")
      // after complex, the stage stalls; resume past it (MULU.W is 1 word -> next pc = base+4)
      dut.fa.logic.feed.ready #= true
      dut.fa.logic.resume.valid #= true; dut.fa.logic.resume.payload #= base + 4
      cd.waitSampling(); dut.fa.logic.resume.valid #= false
      cd.waitSamplingWhere(dut.fa.logic.feed.valid.toBoolean)
      assert(dut.fa.logic.feed.payload(0).pc.toLong == base + 4, "resumed at base+4")
    }
  }
}
```

- [ ] **Step 2: Run it, expect FAIL** (`FetchAlignPlugin` undefined). `~/sbt/bin/sbt "testOnly m68k040.frontend.FetchAlignSpec"`.
- [ ] **Step 3: Implement `FetchAlignPlugin.scala`** per the structural spec. Reach the I-cache fetch via `host[FetchService]`. Drive `ic.cmd`/consume `ic.rsp`, enqueue to the buffer, run the aligner, emit `feed`. Implement redirect/resume/staleness. Expose `feed`/`slot1Valid`/`redirect`/`resume` as public vals on the logic Area for the testbench. Add `DecodeFeedService` (`def feed`, `def slot1Valid`).
- [ ] **Step 4: Run it, expect PASS** (2 end-to-end tests). This is the hard integration — iterate the FetchControl/enqueue/staleness timing to green. Likely issues: window-word enqueue offset after redirect (drop pre-PC words), single-outstanding fetch handshake, the feed/shift/decodePc advance, complex-stall gating. Fix the RTL; do NOT weaken assertions. If genuinely stuck after honest effort, report BLOCKED with the exact failure.
- [ ] **Step 5: Confirm gates** — `make SBT=~/sbt/bin/sbt test-fast` green; `make SBT=~/sbt/bin/sbt test-verilator` runs Aligner/InstructionBuffer/FetchAlign + prior sims, all pass.
- [ ] **Step 6: Commit** — `git add -A && git commit -m "frontend: FetchAlignPlugin (fetch control + buffer + aligner, 2-wide) + e2e tests"`

---

## Self-review notes (author-completed)
- **Spec coverage:** §4 types → T1; §5.2 Aligner → T2 (directed); §5.1 InstructionBuffer → T3; §3 control + §5.3 FetchControl + §6 staleness + end-to-end §7 → T4. Deferred items (§10: prediction, real resume/redirect drivers, LSD) not implemented.
- **Type consistency:** `DecodePacket{pc,words×5,wordCount:3,simple,lenWords:3,complex,fault}` used in T1/T2/T4; `Aligner.Result` fields (`slot0/slot0Valid/slot1/slot1Valid/shiftWords/stall/complex`) used in T2/T4; `InstructionBuffer` io (`push{words,preds,n}/head/headPred/avail/shift/flush`) used in T3/T4; `feed: Stream[Vec[DecodePacket]]` + `slot1Valid` in T1/T4.
- **Hard part isolation:** the boundary brain is a pure function with 5 directed tests (T2); the buffer is standalone (T3); only T4 is integration, with 2 end-to-end tests through the real I-cache. The end-to-end test reuses `IcacheSim.attachMemoryWithWords` + real predecode.
- **Placeholder scan:** T2/T3/T4 RTL are structured specs with exact logic rules + concrete gating tests (the tests define correctness). No TBDs.

## Downstream (not this plan)
The decode stage consuming `DecodeFeedService` (and driving `resume`); branch prediction (BTB/GShare/RAS) replacing pure-sequential fetch + computing `redirect`; loop-stream detector; fetch pipelining (multi-outstanding).
