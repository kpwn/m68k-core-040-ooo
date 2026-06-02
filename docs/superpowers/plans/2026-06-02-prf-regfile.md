# Physical Register Files (Execute Slice 2) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Three async-read, multi-write, bypassing physical register files (int 48×32, NZVC 16×4, X 16×1) with NaxRiscv-style dynamic port allocation (`newRead/newWrite/newBypass`), lowered to distributed-RAM banks by the multi-write phase already in our config.

**Architecture:** A `RegFilePlugin(spec)` collects port-allocation requests during Fiber setup and builds the storage in build: a multi-write `Mem` (read async; lowered to LVT for int / XOR for NZVC/X), a per-read bypass-over-RF mux, write-port merging by `sharingKey`+`priority`, and an init-zero boot sweep. Three instances expose marker services `Int/Nzvc/XRegFileService`. Built incrementally — Task 1 nails the Fiber+lowering lifecycle on a minimal int file, Tasks 2–4 add bypass+init, write-merge, and the three instances.

**Tech Stack:** SpinalHDL 1.14.1 / Scala 2.13 / sbt `~/sbt/bin/sbt` (NOT on PATH) / Verilator. The multi-write lowering phase is `m68k040.hw.MultiPortWritesSymplifier`, installed by `m68k040.M68kSpinalConfig` and the test config `m68k040.M68kSim`.

**Branch:** `feat/prf-regfile` (created; spec committed).

**Lifecycle model (important):** our `FiberPlugin` runs ALL `during setup` blocks before ALL `during build` blocks. So consumers allocate ports in their **setup** (`rf.newRead()` etc., which create the port Bundle + record the request), and the RF wires everything in its **build** (reading the now-complete request buffers). No explicit `Retainer` is needed for this ordering; if a future consumer must allocate in build, add one then.

**Init + XOR caveat:** the init-zero sweep writes 0 through write-port 0. Through the XOR lowering this only yields logical-zero if the lowered banks start at 0 — which Verilator zero-inits by default (the merged Freelist init already relies on this and passes). Task 2's init-zero test confirms it; if it ever fails in sim, initialize the banks explicitly.

---

### Task 1: Minimal int RF — dynamic newRead/newWrite + write→read through the lowering

**Files:**
- Create: `src/main/scala/m68k040/execute/regfile/RegfileService.scala`
- Create: `src/main/scala/m68k040/execute/regfile/RegFilePlugin.scala`
- Create: `src/test/scala/m68k040/execute/regfile/RegFileProbePlugin.scala`
- Create: `src/test/scala/m68k040/execute/regfile/RegFileSpec.scala`

- [ ] **Step 1: Service + port bundles**

Create `src/main/scala/m68k040/execute/regfile/RegfileService.scala`:

```scala
package m68k040.execute.regfile

import spinal.core._

/** One physical register class. */
case class RegfileSpec(name: String, dataWidth: Int, depth: Int) {
  def addressWidth: Int = log2Up(depth)
}
object RegfileSpec {
  val Int  = RegfileSpec("int",  32, 48)
  val Nzvc = RegfileSpec("nzvc", 4,  16)
  val X    = RegfileSpec("x",    1,  16)
}

// Plain-wire ports (our service convention): the consumer drives the inputs,
// the RF drives `data` on reads.
case class RegFileReadPort(addressWidth: Int, dataWidth: Int) extends Bundle {
  val addr = UInt(addressWidth bits)   // consumer drives
  val data = Bits(dataWidth bits)      // RF drives (async + bypass)
}
case class RegFileWritePort(addressWidth: Int, dataWidth: Int) extends Bundle {
  val valid   = Bool()                 // consumer drives
  val address = UInt(addressWidth bits)
  val data    = Bits(dataWidth bits)
}
case class RegFileBypassPort(addressWidth: Int, dataWidth: Int) extends Bundle {
  val valid   = Bool()                 // consumer drives
  val address = UInt(addressWidth bits)
  val data    = Bits(dataWidth bits)
}

/** NaxRiscv-style dynamic port allocation. Call newX() from a consumer's
  * `during setup`; the RF wires them in its `during build`. */
trait RegfileService {
  def spec: RegfileSpec
  def newRead(forceNoBypass: Boolean = false): RegFileReadPort
  def newWrite(latency: Int = 1, sharingKey: Any = null, priority: Int = 0): RegFileWritePort
  def newBypass(): RegFileBypassPort
}
```

- [ ] **Step 2: RegFilePlugin (minimal: reads + writes, NO bypass/merge/init yet)**

Create `src/main/scala/m68k040/execute/regfile/RegFilePlugin.scala`:

```scala
package m68k040.execute.regfile

import spinal.core._
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin
import scala.collection.mutable.ArrayBuffer

class RegFilePlugin(val spec: RegfileSpec) extends FiberPlugin with RegfileService {
  private case class WriteReq(port: RegFileWritePort, latency: Int, key: Any, priority: Int)
  private val reads    = ArrayBuffer[(RegFileReadPort, Boolean)]()
  private val writeReq = ArrayBuffer[WriteReq]()
  private val bypasses = ArrayBuffer[RegFileBypassPort]()

  override def newRead(forceNoBypass: Boolean = false): RegFileReadPort = {
    val p = RegFileReadPort(spec.addressWidth, spec.dataWidth)
    reads += ((p, forceNoBypass)); p
  }
  override def newWrite(latency: Int = 1, sharingKey: Any = null, priority: Int = 0): RegFileWritePort = {
    val p = RegFileWritePort(spec.addressWidth, spec.dataWidth)
    writeReq += WriteReq(p, latency, if (sharingKey == null) new Object else sharingKey, priority); p
  }
  override def newBypass(): RegFileBypassPort = {
    val p = RegFileBypassPort(spec.addressWidth, spec.dataWidth)
    bypasses += p; p
  }

  val logic = during build new Area {
    assert(writeReq.nonEmpty, s"RegFile ${spec.name}: at least one write port required (for init)")

    // Task 3 will merge by key; for now one physical write per request.
    val phys = writeReq.map(_.port).toSeq

    val ram = Mem(Bits(spec.dataWidth bits), spec.depth)
    for (w <- phys) ram.write(w.address, w.data, enable = w.valid)

    for ((r, _) <- reads) r.data := ram.readAsync(r.addr)
  }
}
```

- [ ] **Step 3: Test probe**

Create `src/test/scala/m68k040/execute/regfile/RegFileProbePlugin.scala`:

```scala
package m68k040.execute.regfile

import spinal.core._
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin

/** Allocates a representative int-RF port set in setup, exposes them as top IO
  * in build so a sim can drive/observe. */
class RegFileProbePlugin extends FiberPlugin {
  var rd0: RegFileReadPort = null
  var rd1: RegFileReadPort = null
  var wr0: RegFileWritePort = null
  var wr1: RegFileWritePort = null
  var byp0: RegFileBypassPort = null

  during setup {
    val rf = host[RegFilePluginInt]   // the int instance (see Task 4); for Task 1 use host[RegFilePlugin]
    rd0  = rf.newRead()
    rd1  = rf.newRead()
    wr0  = rf.newWrite(latency = 1)
    wr1  = rf.newWrite(latency = 1)
    byp0 = rf.newBypass()
  }

  val logic = during build new Area {
    // read ports
    val rd0Addr = in UInt (rd0.addr.getWidth bits); rd0.addr := rd0Addr
    val rd0Data = out Bits (rd0.data.getWidth bits); rd0Data := rd0.data
    val rd1Addr = in UInt (rd1.addr.getWidth bits); rd1.addr := rd1Addr
    val rd1Data = out Bits (rd1.data.getWidth bits); rd1Data := rd1.data
    // write ports
    val w0v = in Bool(); val w0a = in UInt (wr0.address.getWidth bits); val w0d = in Bits (wr0.data.getWidth bits)
    wr0.valid := w0v; wr0.address := w0a; wr0.data := w0d
    val w1v = in Bool(); val w1a = in UInt (wr1.address.getWidth bits); val w1d = in Bits (wr1.data.getWidth bits)
    wr1.valid := w1v; wr1.address := w1a; wr1.data := w1d
    // bypass
    val b0v = in Bool(); val b0a = in UInt (byp0.address.getWidth bits); val b0d = in Bits (byp0.data.getWidth bits)
    byp0.valid := b0v; byp0.address := b0a; byp0.data := b0d
  }
}
```

NOTE for Task 1 only: there is no `RegFilePluginInt` yet (Task 4 adds the marker subclasses). For Task 1, change `host[RegFilePluginInt]` to `host[RegFilePlugin]` and construct a single `new RegFilePlugin(RegfileSpec.Int)` in the Dut. Task 4 switches it to the marker.

- [ ] **Step 4: Write→read test (the failing test first — will fail because nothing is wired until Step 2 exists; if Step 2 already compiles, this verifies it)**

Create `src/test/scala/m68k040/execute/regfile/RegFileSpec.scala`:

```scala
package m68k040.execute.regfile

import m68k040.M68kSim
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

class RegFileSpec extends AnyFunSuite {

  class Dut extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val rf    = new RegFilePlugin(RegfileSpec.Int)
    val probe = new RegFileProbePlugin
    db.on { host.asHostOf(Seq[FiberPlugin](rf, probe)) }
  }

  test("write then read returns the written value") {
    M68kSim().compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      // idle
      dut.probe.logic.w0v #= false; dut.probe.logic.w1v #= false; dut.probe.logic.b0v #= false
      dut.probe.logic.rd0Addr #= 0; dut.probe.logic.rd1Addr #= 0
      cd.waitSampling(2)
      // write addr 5 = 0xDEADBEEF via write port 0
      dut.probe.logic.w0v #= true; dut.probe.logic.w0a #= 5; dut.probe.logic.w0d #= BigInt("DEADBEEF", 16)
      cd.waitSampling()
      dut.probe.logic.w0v #= false
      cd.waitSampling()
      // read addr 5
      dut.probe.logic.rd0Addr #= 5
      sleep(1)
      assert(dut.probe.logic.rd0Data.toBigInt == BigInt("DEADBEEF", 16),
        s"got 0x${dut.probe.logic.rd0Data.toBigInt.toString(16)}")
    }
  }
}
```

- [ ] **Step 5: Compile + run — fix Fiber-lifecycle / lowering issues**

Run: `~/sbt/bin/sbt "testOnly m68k040.execute.regfile.RegFileSpec"` (allow 590000 ms).
Expected: PASS. The two `newWrite` ports + async reads make `ram` a 2-write async Mem → the phase (in `M68kSim`'s config) lowers it to LVT (int is 32b). If the test errors at elaboration about ports not allocated (buffers empty in build), the lifecycle assumption is wrong — fix by having the probe allocate via a `Retainer` the RF awaits, or confirm `during setup` runs before the RF's `during build`. If it errors that `host[RegFilePlugin]` finds nothing, ensure the probe uses `host[RegFilePlugin]` and the Dut constructs one. Iterate to green.

- [ ] **Step 6: Commit**

```bash
git add src/main/scala/m68k040/execute/regfile/ src/test/scala/m68k040/execute/regfile/
git commit -m "regfile: minimal int PRF with dynamic newRead/newWrite (lowered multi-write)"
```

---

### Task 2: Bypass-over-RF + init-zero sweep

**Files:**
- Modify: `src/main/scala/m68k040/execute/regfile/RegFilePlugin.scala`
- Modify: `src/test/scala/m68k040/execute/regfile/RegFileSpec.scala`

- [ ] **Step 1: Add bypass mux + init-zero sweep to the build**

Replace the `val logic = during build new Area { ... }` body in `RegFilePlugin.scala` with:

```scala
  val logic = during build new Area {
    assert(writeReq.nonEmpty, s"RegFile ${spec.name}: at least one write port required (for init)")
    val phys = writeReq.map(_.port).toSeq

    val ram = Mem(Bits(spec.dataWidth bits), spec.depth)

    // init-zero boot sweep: write 0 to every address through physical write 0
    // before normal operation (no fetch happens until the first redirect).
    val initCounter = Reg(UInt(log2Up(spec.depth) + 1 bits)) init 0
    val initDone    = initCounter.msb
    when(!initDone) { initCounter := initCounter + 1 }

    for ((w, i) <- phys.zipWithIndex) {
      if (i == 0) {
        ram.write(
          address = Mux(initDone, w.address, initCounter.resized),
          data    = Mux(initDone, w.data, B(0, spec.dataWidth bits)),
          enable  = !initDone || w.valid)
      } else {
        ram.write(w.address, w.data, enable = w.valid && initDone)
      }
    }

    for ((r, noByp) <- reads) {
      val rfData = ram.readAsync(r.addr)
      if (noByp || bypasses.isEmpty) {
        r.data := rfData
      } else {
        val hits = bypasses.map(b => b.valid && b.address === r.addr)
        r.data := Mux(hits.orR, MuxOH(Vec(hits), bypasses.map(_.data)), rfData)
      }
    }
  }
```
If `MuxOH(Vec(hits), seq)` is not the right API in 1.14.1, use `OHMux.or(...)`/`spinal.lib.MuxOH`/a `PriorityMux` over `(hit, data)` pairs — pick the form that compiles; semantics: any bypass hit overrides RF data (at most one hit at a time in practice). Keep `import spinal.lib._`.

- [ ] **Step 2: Add init-zero + bypass tests**

Add to `RegFileSpec`:

```scala
  test("reads return 0 after the init sweep") {
    M68kSim().compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      dut.probe.logic.w0v #= false; dut.probe.logic.w1v #= false; dut.probe.logic.b0v #= false
      // wait out the init sweep (depth + margin cycles)
      cd.waitSampling(64)
      dut.probe.logic.rd0Addr #= 7
      sleep(1)
      assert(dut.probe.logic.rd0Data.toBigInt == 0, s"got 0x${dut.probe.logic.rd0Data.toBigInt.toString(16)}")
    }
  }

  test("bypass overrides RF data on a matching address") {
    M68kSim().compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      dut.probe.logic.w0v #= false; dut.probe.logic.w1v #= false; dut.probe.logic.b0v #= false
      dut.probe.logic.rd0Addr #= 0
      cd.waitSampling(64) // init done
      // RF[9] = 0x11111111
      dut.probe.logic.w0v #= true; dut.probe.logic.w0a #= 9; dut.probe.logic.w0d #= BigInt("11111111", 16)
      cd.waitSampling(); dut.probe.logic.w0v #= false; cd.waitSampling()
      // read 9 with NO bypass -> RF value
      dut.probe.logic.rd0Addr #= 9; sleep(1)
      assert(dut.probe.logic.rd0Data.toBigInt == BigInt("11111111", 16), "RF read")
      // drive bypass {9, 0x22222222} -> read 9 returns bypass value same cycle
      dut.probe.logic.b0v #= true; dut.probe.logic.b0a #= 9; dut.probe.logic.b0d #= BigInt("22222222", 16)
      sleep(1)
      assert(dut.probe.logic.rd0Data.toBigInt == BigInt("22222222", 16), "bypass should beat RF")
      // drop bypass -> RF value again
      dut.probe.logic.b0v #= false; sleep(1)
      assert(dut.probe.logic.rd0Data.toBigInt == BigInt("11111111", 16), "RF after bypass drops")
    }
  }
```

- [ ] **Step 3: Run**

Run: `~/sbt/bin/sbt "testOnly m68k040.execute.regfile.RegFileSpec"` → all 3 pass. If init-zero fails (reads non-zero), the XOR/LVT banks aren't zero-initialized in sim — add explicit zero init to the lowered banks (investigate `Mem` init through the phase) and re-run.

- [ ] **Step 4: Commit**

```bash
git add src/main/scala/m68k040/execute/regfile/RegFilePlugin.scala src/test/scala/m68k040/execute/regfile/RegFileSpec.scala
git commit -m "regfile: bypass-over-RF read mux + init-zero boot sweep"
```

---

### Task 3: Write-port merge by sharingKey + priority

**Files:**
- Modify: `src/main/scala/m68k040/execute/regfile/RegFilePlugin.scala`
- Modify: `src/test/scala/m68k040/execute/regfile/RegFileProbePlugin.scala`, `RegFileSpec.scala`

- [ ] **Step 1: Merge writes by key before building physical writes**

In `RegFilePlugin.logic`, replace `val phys = writeReq.map(_.port).toSeq` with a merge that collapses same-key requests into one physical write bus:

```scala
    // Merge write requests sharing a key into one physical write port.
    // Within a group, the highest-priority valid request wins (one-hot select).
    val groups = writeReq.groupBy(_.key).values.toSeq
    val phys = groups.map { grp =>
      val sorted = grp.sortBy(-_.priority)               // highest priority first
      val bus    = RegFileWritePort(spec.addressWidth, spec.dataWidth)
      val valids = Vec(sorted.map(_.port.valid))
      val oh     = OHMasking.first(valids)               // first (highest-prio) valid
      bus.valid   := valids.orR
      bus.address := MuxOH(oh, sorted.map(_.port.address))
      bus.data    := MuxOH(oh, sorted.map(_.port.data))
      bus
    }
```
(Use the `MuxOH`/`OHMasking.first` forms that compile in 1.14.1 — same as the bypass mux choice. `groups` order is deterministic enough for this slice; if a stable order matters, sort groups by the first request's allocation index.)

The rest of `logic` (ram, init sweep over `phys`, reads) is unchanged — it already iterates `phys`.

- [ ] **Step 2: Give the probe a same-key write pair to exercise the merge**

In `RegFileProbePlugin`, change the two write allocations so they SHARE a key with different priorities:

```scala
    val grpKey = new Object
    wr0 = rf.newWrite(latency = 1, sharingKey = grpKey, priority = 1)  // higher priority
    wr1 = rf.newWrite(latency = 1, sharingKey = grpKey, priority = 0)
```
(Leave the rest of the probe as-is. Now wr0/wr1 collapse to ONE physical write port; the init sweep uses it.)

- [ ] **Step 3: Add a merge test**

Add to `RegFileSpec`:

```scala
  test("same-key writes merge: higher priority wins a conflict") {
    M68kSim().compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      dut.probe.logic.w0v #= false; dut.probe.logic.w1v #= false; dut.probe.logic.b0v #= false
      dut.probe.logic.rd0Addr #= 0
      cd.waitSampling(64)
      // only wr1 (low prio) valid -> its value lands
      dut.probe.logic.w1v #= true; dut.probe.logic.w1a #= 3; dut.probe.logic.w1d #= BigInt("0000AAAA", 16)
      cd.waitSampling(); dut.probe.logic.w1v #= false; cd.waitSampling()
      dut.probe.logic.rd0Addr #= 3; sleep(1)
      assert(dut.probe.logic.rd0Data.toBigInt == BigInt("0000AAAA", 16), "low-prio write should land when alone")
      // both valid, same addr -> high-prio (wr0) wins
      dut.probe.logic.w0v #= true; dut.probe.logic.w0a #= 4; dut.probe.logic.w0d #= BigInt("0000BBBB", 16)
      dut.probe.logic.w1v #= true; dut.probe.logic.w1a #= 4; dut.probe.logic.w1d #= BigInt("0000CCCC", 16)
      cd.waitSampling(); dut.probe.logic.w0v #= false; dut.probe.logic.w1v #= false; cd.waitSampling()
      dut.probe.logic.rd0Addr #= 4; sleep(1)
      assert(dut.probe.logic.rd0Data.toBigInt == BigInt("0000BBBB", 16), "high-prio write should win the merge")
    }
  }
```

- [ ] **Step 4: Run — confirm earlier tests still pass with merged ports**

Run: `~/sbt/bin/sbt "testOnly m68k040.execute.regfile.RegFileSpec"` → all 4 pass. (write→read and bypass tests now exercise the merged single physical write port.)

- [ ] **Step 5: Commit**

```bash
git add src/main/scala/m68k040/execute/regfile/RegFilePlugin.scala src/test/scala/m68k040/execute/regfile/RegFileProbePlugin.scala src/test/scala/m68k040/execute/regfile/RegFileSpec.scala
git commit -m "regfile: write-port merge by sharingKey + priority"
```

---

### Task 4: Three instances (int/NZVC/X) + marker services + XOR-path smoke

**Files:**
- Modify: `src/main/scala/m68k040/execute/regfile/RegFilePlugin.scala` (add marker subclasses)
- Modify: `src/test/scala/m68k040/execute/regfile/RegFileProbePlugin.scala`, `RegFileSpec.scala`

- [ ] **Step 1: Marker service traits + per-class plugin subclasses**

Append to `RegfileService.scala`:

```scala
trait IntRegFileService  extends RegfileService
trait NzvcRegFileService extends RegfileService
trait XRegFileService    extends RegfileService
```

Append to `RegFilePlugin.scala`:

```scala
class RegFilePluginInt  extends RegFilePlugin(RegfileSpec.Int)  with IntRegFileService
class RegFilePluginNzvc extends RegFilePlugin(RegfileSpec.Nzvc) with NzvcRegFileService
class RegFilePluginX    extends RegFilePlugin(RegfileSpec.X)    with XRegFileService
```
(`RegFilePlugin`'s `spec` is a constructor `val`, so the subclasses fix it. The marker trait lets consumers do `host[IntRegFileService]`.)

- [ ] **Step 2: Point the probe at the int marker; add an NZVC probe + smoke**

In `RegFileProbePlugin`, change `host[RegFilePlugin]` to `host[IntRegFileService]` for the int ports, and ALSO allocate one NZVC read/write/bypass to cover the XOR path. Add NZVC fields and expose them:

```scala
  var nzRd: RegFileReadPort = null
  var nzWr: RegFileWritePort = null
  var nzByp: RegFileBypassPort = null

  during setup {
    val rf = host[IntRegFileService]
    rd0 = rf.newRead(); rd1 = rf.newRead()
    val grpKey = new Object
    wr0 = rf.newWrite(latency = 1, sharingKey = grpKey, priority = 1)
    wr1 = rf.newWrite(latency = 1, sharingKey = grpKey, priority = 0)
    byp0 = rf.newBypass()

    val nz = host[NzvcRegFileService]
    nzRd = nz.newRead(); nzWr = nz.newWrite(latency = 1); nzByp = nz.newBypass()
  }
```
Add to the probe's build, exposing the NZVC ports as IO (mirror the int port exposure pattern: `nzRdAddr` in, `nzRdData` out, `nzWv/nzWa/nzWd` in, `nzBv/nzBa/nzBd` in).

- [ ] **Step 3: Update the Dut to host all three RF instances**

In `RegFileSpec.Dut`, replace the single `rf` with the three marker plugins:

```scala
    val rfInt  = new RegFilePluginInt
    val rfNzvc = new RegFilePluginNzvc
    val rfX    = new RegFilePluginX
    val probe  = new RegFileProbePlugin
    db.on { host.asHostOf(Seq[FiberPlugin](rfInt, rfNzvc, rfX, probe)) }
```
(`rfX` has no allocated ports from the probe; its `assert(writeReq.nonEmpty)` would fire. Either allocate a dummy write on X in the probe setup, or relax the assert to allow a write-less file by skipping init when there are no writes. Choose: allocate one X write in the probe so all three files have ≥1 write and init runs. Add `var xWr = nz... ` analogously: `val xrf = host[XRegFileService]; xWr = xrf.newWrite(latency=1)` and drive it idle.)

- [ ] **Step 4: NZVC XOR-path smoke test**

Add to `RegFileSpec`:

```scala
  test("NZVC file (XOR path): write then read") {
    M68kSim().compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      // idle all (set every probe input false/0; include nz + x writes idle)
      cd.waitSampling(64)
      dut.probe.logic.nzWv #= true; dut.probe.logic.nzWa #= 2; dut.probe.logic.nzWd #= 0xD
      cd.waitSampling(); dut.probe.logic.nzWv #= false; cd.waitSampling()
      dut.probe.logic.nzRdAddr #= 2; sleep(1)
      assert((dut.probe.logic.nzRdData.toBigInt & 0xF) == 0xD, s"NZVC got ${dut.probe.logic.nzRdData.toBigInt.toString(16)}")
    }
  }
```
Ensure every probe input is initialized to a safe idle value at the top of each test (add the nz/x write `valid #= false` and addr/data `#= 0` lines to the existing tests' idle blocks so the new ports don't float).

- [ ] **Step 5: Run the RF spec + full suites**

Run: `~/sbt/bin/sbt "testOnly m68k040.execute.regfile.RegFileSpec"` → all pass (int LVT tests + NZVC XOR smoke).
Run: `make SBT=~/sbt/bin/sbt test-fast` → all pass; report total.
Run: `make SBT=~/sbt/bin/sbt test-verilator` → all pass; report total.

- [ ] **Step 6: Commit**

```bash
git add src/main/scala/m68k040/execute/regfile/ src/test/scala/m68k040/execute/regfile/
git commit -m "regfile: int/NZVC/X instances + marker services; XOR-path smoke"
```

---

## Self-Review

**1. Spec coverage:**
- §3.1 RegfileSpec (int/nzvc/x) → Task 1 Step 1 + Task 4. ✓
- §3.2 RegfileService (newRead/newWrite/newBypass) + marker traits → Task 1 Step 1, Task 4 Step 1. ✓
- §3.3 RegFileAsync storage (async multi-write Mem lowered, bypass mux, init-zero) → folded into `RegFilePlugin.logic` (Tasks 1–2); the spec's separate `RegFileAsync.scala` is realized inline in the plugin (simpler, no Component IO boundary) — a deliberate, noted deviation. ✓
- §3.4 RegFilePlugin (collect in setup, merge writes, wire in build) → Tasks 1–3. ✓
- §5 verification: init-zero (T2), write→read (T1), bypass-beats-RF (T2), two distinct writes (covered by write→read on distinct addrs + merge test), write-merge by key/priority (T3), NZVC XOR smoke (T4), suites (T4). ✓

**2. Placeholder scan:** No TBD/TODO. The "use the MuxOH/OHMasking form that compiles" notes are bounded API-selection guidance with explicit semantics, not placeholders. The probe IO exposure for NZVC (T4 Step 2) says "mirror the int pattern" — acceptable since the exact int pattern is shown in full in T1 Step 3; the engineer repeats it for 3 nz signals.

**3. Type consistency:** `RegFileReadPort{addr,data}`, `RegFileWritePort{valid,address,data}`, `RegFileBypassPort{valid,address,data}` consistent across plugin/probe/tests. `newRead/newWrite/newBypass` signatures match the trait. `RegFilePluginInt/Nzvc/X` + `Int/Nzvc/XRegFileService` consistent T4. `M68kSim()` (adds the lowering phase) used for all RF sims. Probe field names (`rd0,wr0,byp0,nzRd,...`) and exposed IO names (`rd0Addr,w0v,b0v,nzWv,...`) consistent between probe and tests.

**Note on file structure:** the spec listed `RegFileAsync.scala` as a separate storage component; the plan folds that storage into `RegFilePlugin.logic` to avoid a Component IO boundary (cleaner for plain-wire ports). If a standalone reusable `RegFileAsync` Component is wanted later (e.g. for an FP file), extracting it is straightforward. Logged as an intentional simplification.
