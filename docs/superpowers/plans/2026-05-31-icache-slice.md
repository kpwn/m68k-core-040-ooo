# I-Cache Slice Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the synthesizable VIPT L1 I-cache (datapath + miss/refill FSM + Axi4ReadOnly master) behind a clean fetch service and an identity-stubbed translation port, verified standalone in SpinalSim against an AXI memory model.

**Architecture:** A `CacheGeometry` pure-Scala helper fixes the 16 KiB/4-way/64 B/64-set VIPT layout. `IcachePlugin` (FiberPlugin) owns async-read tag/valid arrays + per-way BRAM data + a 4-state refill FSM + a 256-bit `Axi4ReadOnly` master (2 beats/line). It exposes `FetchService` and resolves `TranslationService`, which `IdentityTranslationPlugin` provides as VA==PA this slice. Round-robin replacement, `invalidateAll`. Tested against `spinal.lib`'s AXI sim memory.

**Tech Stack:** Scala 2.13.16 / SpinalHDL 1.14.1 (`spinal.core`, `spinal.lib` Stream/Flow/fsm, `spinal.lib.bus.amba4.axi.{Axi4Config,Axi4ReadOnly,Axi4}`, sim agents `spinal.lib.bus.amba4.axi.sim.{AxiMemorySim,Axi4ReadOnlySlaveAgent,SparseMemory}`), SpinalSim + Verilator 5.032, ScalaTest 3.2.19.

**Spec:** `docs/superpowers/specs/2026-05-31-icache-slice-design.md`. Read it; this plan implements it.

**Verified SpinalHDL/AXI API facts (real, from the 1.14.1 jar — use these):**
- `Axi4Config(addressWidth, dataWidth, idWidth, useId=…, useRegion=…, …)` — 27 params, mostly defaulted booleans (`useBurst`/`useLen`/`useSize`/`useLast`/`useResp` default true). Construct with named args: `Axi4Config(addressWidth = 32, dataWidth = 256, idWidth = 2)`.
- `Axi4ReadOnly(config)` is a Bundle with `.ar: Stream[Axi4Ar]` and `.r: Stream[Axi4R]`; call `.setAsMaster()` on the master side (or declare `master(Axi4ReadOnly(cfg))`).
- `Axi4Ar` fields: `.addr`, `.id`, `.len`, `.size`, `.burst` (+ others). `Axi4R` fields: `.data: Bits`, `.id`, `.resp: Bits`, `.last: Bool`.
- Burst constant: `spinal.lib.bus.amba4.axi.Axi4.burst.INCR`.
- For a 64 B line over 256-bit (32 B) beats: `len := U(beatsPerLine-1) = 1`, `size := U(log2Up(32)) = 5`, `burst := Axi4.burst.INCR`, 2 beats.
- Testbench slave: `spinal.lib.bus.amba4.axi.sim.AxiMemorySim(axi, clockDomain, AxiMemorySimConfig())` started with `.start()`, backed by writing the program image via its memory (`SparseMemory` has `write(addr, byte)` / `writeArray(addr, bytes)`). If the exact `AxiMemorySim` API is awkward, `Axi4ReadOnlySlaveAgent` or a hand-rolled sim fork is acceptable (Task 5 gives a reference).
- `Mem`: `val data = Mem(Bits(w bits), depth)`; async read `data.readAsync(addr)`; sync read `data.readSync(addr)`; write `data.write(addr, data, enable)`. Async LUTRAM for tags/valid via `Vec(Reg(...))` or `Mem(...).readAsync`.
- FSM: `import spinal.lib.fsm._`; `new StateMachine { val IDLE = new State with EntryPoint; val REFILL = new State; … IDLE.whenIsActive{…}; … }`.

**Toolchain:** `sbt` at `~/sbt/bin/sbt` (NOT on PATH). Run a spec: `~/sbt/bin/sbt "testOnly <spec>"`; timeout up to 600000 ms. SpinalSim tests compile RTL via Verilator (slow) — tag them `m68k040.VerilatorTest` so `fastTest` excludes them; run them via `make SBT=~/sbt/bin/sbt test-verilator`.

**Branch:** execution happens on a fresh branch `feat/icache` (the controller creates it before Task 1; do not work on `master`).

---

## File structure

| File | Responsibility |
|---|---|
| `src/main/scala/m68k040/cache/CacheGeometry.scala` | pure-Scala geometry + address math + VIPT-safety |
| `src/main/scala/m68k040/cache/IcacheTypes.scala` | `FetchCmd`/`FetchRsp`/`TranslationReq`/`TranslationRsp`/`CacheMode` bundles |
| `src/main/scala/m68k040/services/Services.scala` (modify) | add `FetchService`, `TranslationService` |
| `src/main/scala/m68k040/Global.scala` (modify) | add `L1I_KB`/`L1I_WAYS`/`L1I_LINE_BYTES` |
| `src/main/scala/m68k040/Config.scala` (modify) | add `l1iWays`/`l1iLineBytes` to `M68kParams` |
| `src/main/scala/m68k040/core/ParamPlugin.scala` (modify) | publish the new keys |
| `src/main/scala/m68k040/mmu/IdentityTranslationPlugin.scala` | identity `TranslationService` stub |
| `src/main/scala/m68k040/cache/IcachePlugin.scala` | arrays + hit logic + refill FSM + AXI master |
| `src/test/scala/m68k040/cache/CacheGeometrySpec.scala` | geometry unit tests |
| `src/test/scala/m68k040/cache/IcacheSim.scala` | sim harness: build core-under-test + AXI memory + helpers |
| `src/test/scala/m68k040/cache/IcacheSpec.scala` | the SpinalSim test cases |

---

## Task 1: CacheGeometry helper

**Files:** Create `src/main/scala/m68k040/cache/CacheGeometry.scala`; Test `src/test/scala/m68k040/cache/CacheGeometrySpec.scala`

- [ ] **Step 1: Write the failing test**

```scala
package m68k040.cache

import org.scalatest.funsuite.AnyFunSuite

class CacheGeometrySpec extends AnyFunSuite {
  val g = CacheGeometry(cacheBytes = 16 * 1024, lineBytes = 64, ways = 4,
    indexingPolicy = CacheIndexingPolicy.Vipt)

  test("derived geometry for the L1I") {
    assert(g.sets == 64)
    assert(g.offsetBits == 6)
    assert(g.indexBits == 6)
    assert(g.tagBits == 20)
    assert(g.virtualIndexBits == 12)
  }
  test("address decomposition") {
    val a = 0x40801234L
    assert(g.offset(a) == 0x34)
    assert(g.index(a) == ((0x40801234L >>> 6) & 0x3f).toInt)
    assert(g.tag(a) == (0x40801234L >>> 12))
  }
  test("VIPT-safe under 4 KiB pages (no coloring)") {
    assert(g.isViptSafe(4096))
  }
}
```

- [ ] **Step 2: Run it, expect FAIL** (`not found: type CacheGeometry`). `~/sbt/bin/sbt "testOnly m68k040.cache.CacheGeometrySpec"`.

- [ ] **Step 3: Create `CacheGeometry.scala`** — port the proven helper from `/home/qwertyoruiop/m68k-core-030-inorder/src/main/scala/m68k030/cache/CacheGeometry.scala`, retargeted: package `m68k040.cache`; keep `CacheIndexingPolicy{Pipt,Vipt}`, the `CacheGeometry` case class with `sets/offsetBits/indexBits/virtualIndexBits/tagBits`, `offset/index/tag`, `isViptSafe/requireViptSafe/requirePolicySafe`, and the power-of-two/log2 helpers. Replace the `object CacheGeometry { defaultI030/D030 }` with:
```scala
object CacheGeometry {
  val l1i040: CacheGeometry =
    CacheGeometry(cacheBytes = 16 * 1024, lineBytes = 64, ways = 4,
      indexingPolicy = CacheIndexingPolicy.Vipt)
}
```
Do it with:
```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
mkdir -p src/main/scala/m68k040/cache
sed -e 's/^package m68k030.cache/package m68k040.cache/' \
  /home/qwertyoruiop/m68k-core-030-inorder/src/main/scala/m68k030/cache/CacheGeometry.scala \
  > src/main/scala/m68k040/cache/CacheGeometry.scala
```
Then edit the trailing `object CacheGeometry { ... }` to the `l1i040` form above (remove `defaultI030`/`defaultD030`). Confirm `grep -n "030\|I030\|D030" src/main/scala/m68k040/cache/CacheGeometry.scala` is empty.

- [ ] **Step 4: Run it, expect PASS** (3 tests).
- [ ] **Step 5: Commit** — `git add src/main/scala/m68k040/cache/CacheGeometry.scala src/test/scala/m68k040/cache/CacheGeometrySpec.scala && git commit -m "cache: CacheGeometry helper (VIPT L1I 16K/4-way/64B)"`

---

## Task 2: Slice parameters in M68kParams / Global / ParamPlugin

**Files:** Modify `src/main/scala/m68k040/Config.scala`, `src/main/scala/m68k040/Global.scala`, `src/main/scala/m68k040/core/ParamPlugin.scala`; Test `src/test/scala/m68k040/cache/IcacheParamSpec.scala`

- [ ] **Step 1: Write the failing test**

```scala
package m68k040.cache

import m68k040.{Global, M68kParams}
import m68k040.core.ParamPlugin
import spinal.core._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

class IcacheParamSpec extends AnyFunSuite {
  test("ParamPlugin publishes L1I geometry keys") {
    SpinalConfig().generateVerilog(new Component {
      val db = new Database
      val host = db on (new PluginHost)
      val probe = out(Bool())
      val reader = new FiberPlugin {
        val logic = during build new Area {
          probe := (Global.L1I_KB.get == 16 && Global.L1I_WAYS.get == 4 &&
                    Global.L1I_LINE_BYTES.get == 64).asBool ? True | False
        }
      }
      db.on { host.asHostOf(Seq[FiberPlugin](new ParamPlugin(M68kParams()), reader)) }
    })
  }
}
```

- [ ] **Step 2: Run it, expect FAIL** (`L1I_KB`/`L1I_WAYS`/`L1I_LINE_BYTES` not in `Global`).

- [ ] **Step 3: Add fields to `M68kParams`** in `src/main/scala/m68k040/Config.scala` — add to the case class params (with defaults): `l1iWays: Int = 4,` and `l1iLineBytes: Int = 64,` (place near `l1iKb`). Do not remove existing fields.

- [ ] **Step 4: Add keys to `Global`** in `src/main/scala/m68k040/Global.scala`, inside `object Global` (each `Database.blocking[Int]()`, producer ParamPlugin):
```scala
  val L1I_KB          = Database.blocking[Int]()
  val L1I_WAYS        = Database.blocking[Int]()
  val L1I_LINE_BYTES  = Database.blocking[Int]()
```

- [ ] **Step 5: Publish them in `ParamPlugin`** — in `src/main/scala/m68k040/core/ParamPlugin.scala`'s `during setup` block, add:
```scala
    Global.L1I_KB.set(p.l1iKb)
    Global.L1I_WAYS.set(p.l1iWays)
    Global.L1I_LINE_BYTES.set(p.l1iLineBytes)
```

- [ ] **Step 6: Run it, expect PASS.**
- [ ] **Step 7: Commit** — `git add -A && git commit -m "cache: publish L1I geometry params via Global keys"`

---

## Task 3: I-cache interface bundles + service traits

**Files:** Create `src/main/scala/m68k040/cache/IcacheTypes.scala`; Modify `src/main/scala/m68k040/services/Services.scala`; Test `src/test/scala/m68k040/cache/IcacheTypesSpec.scala`

- [ ] **Step 1: Write the failing test**

```scala
package m68k040.cache

import spinal.core._
import spinal.lib._
import org.scalatest.funsuite.AnyFunSuite

class IcacheTypesSpec extends AnyFunSuite {
  test("fetch/translation bundles have the spec widths") {
    SpinalConfig().generateVerilog(new Component {
      val cmd = slave(Stream(FetchCmd()))
      val rsp = master(Flow(FetchRsp()))
      cmd.ready := True
      rsp.valid := cmd.valid
      rsp.payload.assignDontCare()
      assert(cmd.payload.pc.getWidth == 32)
      assert(rsp.payload.data.getWidth == 64)
      val treq = TranslationReq(); val trsp = TranslationRsp()
      assert(treq.vpn.getWidth == 20 && trsp.ppn.getWidth == 20)
    })
  }
}
```

- [ ] **Step 2: Run it, expect FAIL** (types undefined).

- [ ] **Step 3: Create `IcacheTypes.scala`**
```scala
package m68k040.cache

import spinal.core._

object CacheMode extends SpinalEnum {
  val CACHEABLE, INHIBITED = newElement()
}

/** Upstream fetch request: a 32-bit PC. */
case class FetchCmd() extends Bundle {
  val pc = UInt(32 bits)
}

/** Fetch response: the 64-bit (8-byte) window at pc, plus fault. */
case class FetchRsp() extends Bundle {
  val pc    = UInt(32 bits)
  val data  = Bits(64 bits)
  val fault = Bool()
}

/** Translation request (virtual page number). */
case class TranslationReq() extends Bundle {
  val vpn        = UInt(20 bits)   // addr[31:12]
  val supervisor = Bool()
}

/** Translation response (physical page number + cache mode). */
case class TranslationRsp() extends Bundle {
  val ppn       = UInt(20 bits)
  val cacheMode = CacheMode()
  val fault     = Bool()
}
```

- [ ] **Step 4: Add service traits to `services/Services.scala`** (append; keep existing traits):
```scala
import m68k040.cache.{FetchCmd, FetchRsp, TranslationReq, TranslationRsp}
import spinal.lib.{Stream, Flow}

/** Produced by the I-cache; consumed by the fetch/align stage (later). */
trait FetchService {
  def cmd: Stream[FetchCmd]
  def rsp: Flow[FetchRsp]
}

/** Produced by the MMU/ITLB (identity stub this slice); consumed by the I-cache.
  * Combinational: drive `rsp` from `req` within the same cycle. */
trait TranslationService {
  def req: TranslationReq
  def rsp: TranslationRsp
}
```
(If `services/Services.scala`'s existing imports already cover spinal, avoid duplicate imports — compile and fix import collisions.)

- [ ] **Step 5: Run it, expect PASS.** Also `~/sbt/bin/sbt compile`.
- [ ] **Step 6: Commit** — `git add -A && git commit -m "cache: I-cache interface bundles + FetchService/TranslationService"`

---

## Task 4: Identity translation stub plugin

**Files:** Create `src/main/scala/m68k040/mmu/IdentityTranslationPlugin.scala`; Test `src/test/scala/m68k040/cache/IdentityTranslationSpec.scala`

- [ ] **Step 1: Write the failing test** (sim: drive vpn, expect ppn==vpn, cacheable, no fault)

```scala
package m68k040.cache

import m68k040.{VerilatorTest}
import m68k040.mmu.IdentityTranslationPlugin
import m68k040.services.TranslationService
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

class IdentityTranslationSpec extends AnyFunSuite {
  // Expose the translation service ports on a test Component
  class Dut extends Component {
    val io = new Bundle {
      val vpn = in UInt(20 bits)
      val ppn = out UInt(20 bits)
      val cacheable = out Bool()
    }
    val db = new Database
    val host = db on (new PluginHost)
    val xlate = new IdentityTranslationPlugin
    db.on { host.asHostOf(Seq[FiberPlugin](xlate)) }
    val svc = host[TranslationService]
    svc.req.vpn := io.vpn
    svc.req.supervisor := False
    io.ppn := svc.rsp.ppn
    io.cacheable := (svc.rsp.cacheMode === m68k040.cache.CacheMode.CACHEABLE)
  }

  test("identity translation: ppn==vpn, cacheable, no fault", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      for (v <- Seq(0x00040L, 0xABCDEL, 0xFFFFFL)) {
        dut.io.vpn #= v
        sleep(1)
        assert(dut.io.ppn.toLong == v, s"ppn should equal vpn $v")
        assert(dut.io.cacheable.toBoolean, "stub is cacheable")
      }
    }
  }
}
```

- [ ] **Step 2: Run it, expect FAIL** (`IdentityTranslationPlugin` undefined). Run `~/sbt/bin/sbt "testOnly m68k040.cache.IdentityTranslationSpec"`.

- [ ] **Step 3: Create `IdentityTranslationPlugin.scala`** — a FiberPlugin that registers a combinational `TranslationService`:
```scala
package m68k040.mmu

import m68k040.cache.{CacheMode, TranslationReq, TranslationRsp}
import m68k040.services.TranslationService
import spinal.core._
import spinal.lib.misc.plugin.FiberPlugin

/** Identity VA==PA translation stub. Registers TranslationService combinationally.
  * Replaced by the real ITLB later — the I-cache is unchanged (resolves the same service). */
class IdentityTranslationPlugin extends FiberPlugin {
  val logic = during build new Area {
    val reqReg = TranslationReq()
    val rspReg = TranslationRsp()
    rspReg.ppn       := reqReg.vpn               // identity
    rspReg.cacheMode := CacheMode.CACHEABLE
    rspReg.fault     := False

    addService(new TranslationService {
      override def req: TranslationReq = reqReg
      override def rsp: TranslationRsp = rspReg
    })
  }
}
```
(Implementation note: the service exposes the SAME signal objects the consumer drives/reads. The consumer drives `req.*`; this plugin reads `req` and combinationally drives `rsp`. Confirm in elaboration that `req` is driven by exactly one consumer — in this slice, the I-cache.)

- [ ] **Step 4: Run it, expect PASS.**
- [ ] **Step 5: Commit** — `git add -A && git commit -m "mmu: identity translation stub (TranslationService)"`

---

## Task 5: AXI memory sim harness

**Files:** Create `src/test/scala/m68k040/cache/IcacheSim.scala`

This is test infrastructure (no RTL of its own): a helper that builds a known backing image and attaches an AXI read-slave memory model to a DUT's `Axi4ReadOnly` master.

- [ ] **Step 1: Write `IcacheSim.scala`** — helper providing (a) a deterministic backing image and (b) AXI memory attach.

```scala
package m68k040.cache

import spinal.core._
import spinal.core.sim._
import spinal.lib.bus.amba4.axi.Axi4ReadOnly
import spinal.lib.bus.amba4.axi.sim.{AxiMemorySim, AxiMemorySimConfig}

object IcacheSim {
  /** Deterministic byte at a given address: byte = (addr * 7 + 0x11) & 0xff.
    * Lets tests predict fetched data without storing a full image. */
  def memByte(addr: Long): Int = ((addr * 7 + 0x11) & 0xff).toInt

  /** The 64-bit little-endian window at a 8-aligned address (matches how the cache
    * presents the 8-byte block; byte i of the window is memByte(base+i)). */
  def window64(base: Long): BigInt =
    (0 until 8).foldLeft(BigInt(0)) { (acc, i) =>
      acc | (BigInt(memByte(base + i)) << (8 * i))
    }

  /** Attach an AxiMemorySim slave to `axi`, preloaded so reads return memByte(addr).
    * Returns the started sim (already serving). Preloads `bytes` from base 0 up to `size`. */
  def attachMemory(axi: Axi4ReadOnly, cd: ClockDomain, base: Long, size: Int): AxiMemorySim = {
    val mem = AxiMemorySim(axi, cd, AxiMemorySimConfig())
    mem.start()
    val img = Array.tabulate(size)(i => memByte(base + i).toByte)
    mem.memory.writeArray(base, img)
    mem
  }
}
```
**Implementation note / fallback:** `AxiMemorySim(axi, clockDomain, config)` + `.start()` + `.memory.writeArray(addr, bytes)` is the intended API (`spinal.lib.bus.amba4.axi.sim`). If the exact method names differ in 1.14.1 (e.g. `memory` accessor, or `writeArray` signature), adapt to the actual API by reading the class — the REQUIRED behavior is: the slave answers the cache's AR/R bursts with bytes equal to `memByte(addr)`. If `AxiMemorySim` proves unworkable, fall back to a hand-rolled fork that holds `axi.ar.ready := true`, waits for `ar.valid`, then emits `ar.len+1` R beats where beat `i`'s `data` packs `memByte(addr + i*32 + b)` for b in 0..31, asserting `last` on the final beat. Either way, `window64`/`memByte` define the golden values the cache tests assert against.

- [ ] **Step 2: Compile-check** — `~/sbt/bin/sbt Test/compile`. Fix any AXI sim API mismatches now (this file has no test yet; it must compile). Expected: compiles.
- [ ] **Step 3: Commit** — `git add src/test/scala/m68k040/cache/IcacheSim.scala && git commit -m "cache(test): AXI memory sim harness + golden data model"`

---

## Task 6: IcachePlugin — arrays, hit logic, single-miss refill FSM, AXI master

**Files:** Create `src/main/scala/m68k040/cache/IcachePlugin.scala`; Test `src/test/scala/m68k040/cache/IcacheSpec.scala`

This is the core RTL. Build it to pass the tests below. The structural spec is exact; write the SpinalHDL body against it and the verified API facts.

**Structural spec for `IcachePlugin` (FiberPlugin):**
- Parameters from `Global` (`L1I_KB`, `L1I_WAYS`, `L1I_LINE_BYTES`) → build a `CacheGeometry`; `requireViptSafe(4096)`.
- Constants: `beatBytes = 32` (256-bit), `beatsPerLine = lineBytes/beatBytes = 2`, `windowBytes = 8`.
- IO (exposed via `FetchService` + an AXI master + control): a `Stream(FetchCmd)` input `cmd`, a `Flow(FetchRsp)` output `rsp`, a `master(Axi4ReadOnly(Axi4Config(addressWidth=32, dataWidth=256, idWidth=2)))` `axi`, and an `in Bool()` `invalidateAll`. Register `FetchService` exposing `cmd`/`rsp`.
- Resolve `host[TranslationService]`; drive `xlate.req.vpn := cmd.payload.pc(31 downto 12)`, `supervisor := False`; read `xlate.rsp.ppn`.
- Arrays: `tags = Vec.fill(ways)(Mem(UInt(tagBits bits), sets))` async-read; `valids = Vec.fill(ways)(Vec.fill(sets)(RegInit(False)))` (FF, async); `data = Vec.fill(ways)(Mem(Bits(256 bits), sets*beatsPerLine))` (each way: `sets*2` entries of 256-bit; line `s` beat `b` at index `s*2+b`). Victim: `victim = Vec.fill(sets)(RegInit(U(0, log2Up(ways) bits)))`.
- Hit detect (combinational, async reads): `set = pc(11 downto 6)`; `reqTag = xlate.rsp.ppn` (== pc[31:12] under identity, width 20 = tagBits); `hits(w) = valids(w)(set) && (tags(w).readAsync(set) === reqTag)`; `hit = hits.orR`; `hitWay = OHToUInt(hits)`.
- Data read on hit: window is byte `pc(5:3)`-selected 64-bit slice of the line. The line is two 256-bit beats; `beatSel = pc(5)` (which 32-byte half), `within = pc(4 downto 3)` (which 64-bit lane in that 32-byte half → 4 lanes). Read `data(hitWay).readAsync(set @@ beatSel)` (index `set*2 + beatSel`), then `rsp.data := beat.subdivideIn(64 bits)(within)`.
- FSM (`spinal.lib.fsm`): `IDLE` (EntryPoint), `REFILL`, `REPLAY`.
  - `IDLE`: `cmd.ready := !busy`. When `cmd.fire`: if `hit` → drive `rsp.valid := True`, `rsp.data` from the hit way, `rsp.pc := pc`, `rsp.fault := xlate.rsp.fault`, stay IDLE. If miss → latch `pc/set/reqTag/victim(set)`, go REFILL. (Hold `cmd.ready := False` until done.)
  - `REFILL`: drive `axi.ar.valid` once: `axi.ar.addr := lineBase` (`= pc & ~63`), `axi.ar.len := 1`, `axi.ar.size := 5`, `axi.ar.burst := Axi4.burst.INCR`, `axi.ar.id := 0`. Accept `axi.r` beats (`axi.r.ready := True`): beat `b` (0,1) writes `data(victim).write(set@@b, axi.r.data)`. On `axi.r.last`: write `tags(victim).write(set, reqTag)`, `valids(victim)(set) := True`, `victim(set) := victim(set) + 1`, go REPLAY.
  - `REPLAY`: now the line is present → drive `rsp` from way `victim` (the just-filled way) for the latched `pc`, then go IDLE (and re-accept cmd).
- `invalidateAll`: when high, clear every `valids(w)(s) := False` (one cycle). Highest priority.
- All hot-path reads async; no global broadcast.

- [ ] **Step 1: Write the failing test `IcacheSpec.scala`**

```scala
package m68k040.cache

import m68k040.{M68kParams, VerilatorTest}
import m68k040.mmu.IdentityTranslationPlugin
import m68k040.services.FetchService
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import m68k040.core.ParamPlugin
import org.scalatest.funsuite.AnyFunSuite

class IcacheSpec extends AnyFunSuite {
  // A DUT that hosts ParamPlugin + IdentityTranslationPlugin + IcachePlugin and
  // surfaces the fetch port + AXI master + invalidateAll on IO.
  class Dut extends Component {
    val db = new Database
    val host = db on (new PluginHost)
    val ic = new IcachePlugin
    db.on { host.asHostOf(Seq[FiberPlugin](
      new ParamPlugin(M68kParams()), new IdentityTranslationPlugin, ic)) }
    // IcachePlugin must expose its ports for the testbench. Convention: the plugin
    // creates `val io` (cmd: slave Stream(FetchCmd), rsp: master Flow(FetchRsp),
    // axi: master Axi4ReadOnly, invalidateAll: in Bool) and this Dut wires them out
    // 1:1. (The plugin's io is accessible as ic.logic.io — expose via a stable handle.)
  }

  def fetch(dut: Dut, pc: Long): BigInt = {
    // single-outstanding: drive cmd, wait for rsp.valid, read data
    dut.fetchCmdPc #= pc
    dut.fetchCmdValid #= true
    dut.clockDomain.waitSamplingWhere(dut.fetchCmdReady.toBoolean)
    dut.fetchCmdValid #= false
    dut.clockDomain.waitSamplingWhere(dut.fetchRspValid.toBoolean)
    dut.fetchRspData.toBigInt
  }
  // NOTE: the exact testbench plumbing (how Dut surfaces cmd/rsp/axi) is part of
  // Task 6 implementation; the REQUIRED behavior the tests assert is below.

  test("cold miss refills then returns the correct 64-bit window", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      IcacheSim.attachMemory(dut.axiHandle, dut.clockDomain, base = 0, size = 0x10000)
      dut.invalidateAllPin #= true; dut.clockDomain.waitSampling(); dut.invalidateAllPin #= false
      val pc = 0x1040L  // 8-aligned
      val got = fetch(dut, pc)
      assert(got == IcacheSim.window64(pc), f"window mismatch at $pc%x")
    }
  }

  test("second fetch in the same line hits with no new AXI burst", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      val mem = IcacheSim.attachMemory(dut.axiHandle, dut.clockDomain, 0, 0x10000)
      dut.invalidateAllPin #= true; dut.clockDomain.waitSampling(); dut.invalidateAllPin #= false
      val base = 0x2000L
      fetch(dut, base)              // miss -> refill
      val arCountAfterFirst = dut.arBeatCounter   // count of AR handshakes seen so far
      val got = fetch(dut, base + 8) // same 64B line, different window
      assert(got == IcacheSim.window64(base + 8))
      assert(dut.arBeatCounter == arCountAfterFirst, "no new AR for same-line hit")
    }
  }

  test("crossing the 64B line boundary triggers a new refill", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      IcacheSim.attachMemory(dut.axiHandle, dut.clockDomain, 0, 0x10000)
      dut.invalidateAllPin #= true; dut.clockDomain.waitSampling(); dut.invalidateAllPin #= false
      val base = 0x3000L
      fetch(dut, base + 56)   // last window of line
      val c0 = dut.arBeatCounter
      val got = fetch(dut, base + 64) // next line -> new refill
      assert(got == IcacheSim.window64(base + 64))
      assert(dut.arBeatCounter == c0 + 1, "line cross should refill once")
    }
  }
}
```

- [ ] **Step 2: Run it, expect FAIL** (IcachePlugin undefined). Run `~/sbt/bin/sbt "testOnly m68k040.cache.IcacheSpec"`.

- [ ] **Step 3: Implement `IcachePlugin.scala`** per the structural spec above. Surface the ports so the `Dut` testbench can drive them: give `IcachePlugin` an `io` Bundle (`cmd`, `rsp`, `axi`, `invalidateAll`) created in `during build`, and in the test `Dut` wire `cmd/rsp/axi/invalidateAll` out to top-level pins named so the test helpers (`fetchCmdPc`, `fetchCmdValid`, `fetchCmdReady`, `fetchRspValid`, `fetchRspData`, `axiHandle`, `invalidateAllPin`, `arBeatCounter`) resolve. `arBeatCounter` is a sim-side counter the testbench increments by monitoring `axi.ar.fire` in a fork — implement it in the `Dut`/test, not the RTL. Adjust the test's plumbing accessors to match your actual `Dut` wiring (keep the assertions intact).

- [ ] **Step 4: Run it, expect PASS** (3 VerilatorTest tests). Iterate on FSM/timing only; do not weaken the data-equality or AR-count assertions. If hit data is off by a window-lane, fix the `pc(5:3)` window selection (`beatSel=pc(5)`, lane=`pc(4:3)`), not the test.

- [ ] **Step 5: Commit** — `git add -A && git commit -m "cache: IcachePlugin hit path + single-miss refill over AXI"`

---

## Task 7: Round-robin eviction + invalidateAll behavior

**Files:** Modify `src/test/scala/m68k040/cache/IcacheSpec.scala` (add tests); adjust `IcachePlugin.scala` only if needed.

- [ ] **Step 1: Add the failing tests** to `IcacheSpec`:

```scala
  test("filling all 4 ways then a 5th tag evicts round-robin (way 0 first)", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      IcacheSim.attachMemory(dut.axiHandle, dut.clockDomain, 0, 0x100000)
      dut.invalidateAllPin #= true; dut.clockDomain.waitSampling(); dut.invalidateAllPin #= false
      // five addresses with the SAME set index (bits[11:6]) but different tags (bits[31:12]).
      // set bits = 0; stride one full way-set worth = 0x1000 (4KiB) keeps index const, bumps tag.
      val set0 = 0x0L
      val addrs = (0 until 5).map(i => set0 + i.toLong * 0x1000L)
      addrs.take(4).foreach(a => fetch(dut, a))   // fill ways 0..3
      val cBefore = dut.arBeatCounter
      fetch(dut, addrs(4))                          // evicts victim (way 0)
      // re-fetching the first address (was in way 0) must MISS again -> +1 refill
      val cAfterFifth = dut.arBeatCounter
      assert(cAfterFifth == cBefore + 1, "5th distinct tag must refill")
      val cBeforeReFetch = dut.arBeatCounter
      val got = fetch(dut, addrs(0))
      assert(got == IcacheSim.window64(addrs(0)))
      assert(dut.arBeatCounter == cBeforeReFetch + 1, "evicted line (way 0) must miss again")
    }
  }

  test("invalidateAll forces a re-miss", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      IcacheSim.attachMemory(dut.axiHandle, dut.clockDomain, 0, 0x10000)
      dut.invalidateAllPin #= true; dut.clockDomain.waitSampling(); dut.invalidateAllPin #= false
      val pc = 0x4080L
      fetch(dut, pc)
      val c0 = dut.arBeatCounter
      fetch(dut, pc)                                 // hit, no AR
      assert(dut.arBeatCounter == c0)
      dut.invalidateAllPin #= true; dut.clockDomain.waitSampling(); dut.invalidateAllPin #= false
      val cInv = dut.arBeatCounter
      val got = fetch(dut, pc)                        // re-miss after invalidate
      assert(got == IcacheSim.window64(pc))
      assert(dut.arBeatCounter == cInv + 1, "invalidateAll must force a refill")
    }
  }
```

- [ ] **Step 2: Run, expect FAIL** if the round-robin victim or invalidate isn't right yet (or PASS if Task 6 already implemented them correctly). Run `~/sbt/bin/sbt "testOnly m68k040.cache.IcacheSpec"`.
- [ ] **Step 3: Fix `IcachePlugin`** if needed: ensure `victim(set)` increments per refill (round-robin, way 0 first when set is empty) and `invalidateAll` clears all valids with top priority.
- [ ] **Step 4: Run, expect PASS** (all 5 IcacheSpec tests).
- [ ] **Step 5: Commit** — `git add -A && git commit -m "cache: round-robin eviction + invalidateAll"`

---

## Task 8: Core integration + elaboration smoke

**Files:** Modify `src/main/scala/m68k040/top/GenVerilog.scala` (or add a cached-core gen); Test `src/test/scala/m68k040/cache/IcacheElaborateSpec.scala`

- [ ] **Step 1: Write the failing test** — the I-cache + identity stub host inside a core and elaborate to Verilog (non-Verilator, fast):

```scala
package m68k040.cache

import m68k040.M68kParams
import m68k040.core.ParamPlugin
import m68k040.mmu.IdentityTranslationPlugin
import spinal.core._
import spinal.lib.misc.plugin.FiberPlugin
import org.scalatest.funsuite.AnyFunSuite

class IcacheElaborateSpec extends AnyFunSuite {
  test("I-cache + identity translation elaborate inside a plugin host") {
    val report = SpinalConfig(targetDirectory = "simWorkspace/gen").generateVerilog {
      m68k040.cache.IcacheTop(Seq[FiberPlugin](
        new ParamPlugin(M68kParams()), new IdentityTranslationPlugin, new IcachePlugin))
    }
    assert(report.toplevelName.nonEmpty)
  }
}
```

- [ ] **Step 2: Run it, expect FAIL** (`IcacheTop` undefined).
- [ ] **Step 3: Add an `IcacheTop` helper** (small Component hosting the given plugins via Database+PluginHost and exposing the I-cache `io` at top level) in `IcachePlugin.scala` or a new `src/main/scala/m68k040/cache/IcacheTop.scala`. Model it on `m68k040.core.M68kCore` (the working `database on PluginHost` pattern).
- [ ] **Step 4: Run it, expect PASS.** Confirm `make SBT=~/sbt/bin/sbt test-fast` still green (this elaborate test is non-Verilator) and `make SBT=~/sbt/bin/sbt test-verilator` runs the I-cache sim tests.
- [ ] **Step 5: Commit** — `git add -A && git commit -m "cache: IcacheTop elaboration wrapper + smoke"`

---

## Self-review notes (author-completed)

- **Spec coverage:** geometry §2 → T1; params §3 → T2; ports §4 (fetch/translation/AXI) → T3/T4/T6; internal structure §5 + data flow §6 → T6; replacement+invalidate §7 → T7; plugin integration §8 → T6/T8; testing §9 → T5/T6/T7. Deferred items (§1/§12) are not implemented by design.
- **Calibration:** types/geometry/params/services/identity-stub and ALL test code are verbatim/complete. The `IcachePlugin` body (T6) is a precise structural spec implemented against the verified AXI/Mem/FSM API facts and gated by 5 concrete data-equality + AR-count tests — the same approach used for the framework canary and the musashi_run trace extension. The one place the implementer must finalize is the testbench port plumbing (how `Dut` surfaces the plugin `io`); the assertions are fixed.
- **Placeholder scan:** no TBD/TODO; the AXI-sim-API and FSM-timing notes are bounded "confirm against lib / iterate to green" instructions with hard gating assertions, not placeholders.
- **Type consistency:** `FetchCmd.pc/FetchRsp.data`, `TranslationReq.vpn/TranslationRsp.ppn/cacheMode`, `CacheMode.CACHEABLE`, `Global.L1I_*`, `M68kParams.l1iWays/l1iLineBytes`, `IcacheSim.window64/memByte` are used consistently across tasks.

## Known risks / notes
- AXI sim slave API (`AxiMemorySim`) is the one external surface not verbatim-verified; Task 5 gives the intended usage + a hand-rolled fallback, and Task 6's data-equality tests will surface any mismatch immediately.
- Window-lane selection (`pc[5:3]`) is the most likely off-by-one; the tests assert exact bytes via `window64`, pinpointing it.
- The testbench monitors `axi.ar.fire` to count refills (`arBeatCounter`) — implement that as a sim fork in the `Dut`/test, not in RTL.
