# Foundation: Framework Backbone & Keystone Types — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up a compiling, elaborating SpinalHDL project with the NaxRiscv-style plugin backbone (FiberPlugin + Database), the keystone type contracts (µop bundle, ROB entry, predecode metadata, commit-trace port), the Database key registry + service catalog (Appendix B of the spec), and the ported engineering tooling — all smoke-tested. No pipeline logic yet.

**Architecture:** Plugins are `spinal.lib.misc.plugin.FiberPlugin`s hosted by a `PluginHost` inside a `M68kCore` Component; a `new Database` blackboard carries `Global` keys set in the `setup` phase and read in the `build` phase. Keystone bundles are SpinalHDL `Bundle`s parameterized by a `M68kParams` case class. The first elaboration smoke test is the framework canary — if the Fiber/Database incantation is wrong, it fails here, immediately.

**Tech Stack:** Scala 2.13.16, SpinalHDL 1.14.1 (`spinalhdl-core`/`-lib`/`-sim`), sbt 1.10.11, ScalaTest 3.2.19, Verilator (later tasks), bash tooling ported from `m68k-core-030-inorder`.

**Reference:** Design doc `docs/superpowers/specs/2026-05-31-m68k-040-ooo-architecture-design.md`. Verified framework API (this is real, from the 1.14.1 jar): `FiberPlugin` provides `during build {…}` / `during setup {…}` / `host` / `host[T]` / `addService(x)` / `awaitBuild()`; `PluginHost` provides `new PluginHost` / `asHostOf(plugins)` / `apply[T]`; `Database` provides `new Database` / `Database.blocking[T]()` / `Database.value[T]()` / `db.on{…}`; a Database `Element[T]` has `.get` / `.set(v)` / `.apply()` using the current Database scope. If the top-level scoping in Task 4 fails to elaborate, consult the upstream VexiiRiscv `VexiiRiscv.scala` top component for the canonical `database.on { host.asHostOf(plugins) }` pattern.

**Package root:** `m68k040`. **Source dirs:** `src/main/scala/m68k040/...`, `src/test/scala/m68k040/...`.

---

## File structure (created by this plan)

| File | Responsibility |
|---|---|
| `build.sbt`, `project/build.properties` | sbt project + deps + `fastTest` task |
| `.gitignore` | ignore `target/`, sim output, coursier cache |
| `Makefile` | `compile` / `test-fast` / `test` / `verilog` targets |
| `src/main/scala/m68k040/Config.scala` | `M68kParams` case class (Appendix A sizing) |
| `src/main/scala/m68k040/isa/Isa.scala` | architectural constants, `RegFile`/`Size`/`MemOp`/`Cluster` enums, CCR masks |
| `src/main/scala/m68k040/Global.scala` | Database key registry (`Global` object) — Appendix B |
| `src/main/scala/m68k040/services/Services.scala` | service-interface trait catalog — Appendix B |
| `src/main/scala/m68k040/types/MicroOp.scala` | keystone µop bundle (spec 4.4) |
| `src/main/scala/m68k040/types/RobEntry.scala` | instruction/ROB entry bundle (spec 4.5) |
| `src/main/scala/m68k040/types/PredecodeMeta.scala` | predecode metadata bundle (spec 5.1) |
| `src/main/scala/m68k040/types/CommitTrace.scala` | commit-trace port bundle (invariant #5) |
| `src/main/scala/m68k040/core/M68kCore.scala` | top Component: Database + PluginHost host of plugins |
| `src/main/scala/m68k040/core/ParamPlugin.scala` | sets `Global` keys in `setup` |
| `src/main/scala/m68k040/core/HelloPlugin.scala` | no-op canary plugin (reads a key, emits a Reg) |
| `src/main/scala/m68k040/top/GenVerilog.scala` | `main` that elaborates Verilog |
| `src/test/scala/m68k040/TestTags.scala` | `SlowTest`/`VerilatorTest`/`BoardTest` tags |
| `src/test/scala/m68k040/*Spec.scala` | smoke + bundle-width specs |
| `tools/agent_worktree_pool.sh`, `tools/pm_gate.sh`, `tools/musashi/README.md` | ported engineering tooling |
| `AGENTS.md`, `README.md` | project orientation |

---

## Task 1: Project scaffold (build + ignore)

**Files:**
- Create: `build.sbt`
- Create: `project/build.properties`
- Create: `.gitignore`

- [ ] **Step 1: Write `project/build.properties`**

```
sbt.version=1.10.11
```

- [ ] **Step 2: Write `build.sbt`**

```scala
ThisBuild / version      := "0.1.0"
ThisBuild / scalaVersion := "2.13.16"

val spinalVersion = "1.14.1"

lazy val fastTest = taskKey[Unit]("Run fast tests only (exclude slow/verilator/board tags)")

lazy val root = (project in file("."))
  .settings(
    name := "m68k-core-040-ooo",
    libraryDependencies ++= Seq(
      "com.github.spinalhdl" %% "spinalhdl-core" % spinalVersion,
      "com.github.spinalhdl" %% "spinalhdl-lib"  % spinalVersion,
      compilerPlugin("com.github.spinalhdl" %% "spinalhdl-idsl-plugin" % spinalVersion),
      "com.github.spinalhdl" %% "spinalhdl-sim"  % spinalVersion % Test,
      "org.scalatest"        %% "scalatest"       % "3.2.19"      % Test
    ),
    scalacOptions += "-language:reflectiveCalls",
    fork := true,
    Test / fork := true,
    fastTest := (Test / testOnly)
      .toTask(" * -- -l m68k040.SlowTest -l m68k040.VerilatorTest -l m68k040.BoardTest")
      .value
  )
```

- [ ] **Step 3: Write `.gitignore`**

```
target/
project/target/
project/project/
.bsp/
.coursier/
simWorkspace/
*.fst
*.vcd
hs_err_pid*.log
.idea/
.metals/
.bloop/
```

- [ ] **Step 4: Verify it compiles (empty project)**

Run: `sbt compile`
Expected: `[success]` (downloads deps on first run; no sources yet, compiles cleanly).

- [ ] **Step 5: Commit**

```bash
git add build.sbt project/build.properties .gitignore
git commit -m "build: sbt + SpinalHDL 1.14.1 project scaffold"
```

---

## Task 2: `M68kParams` sizing case class

**Files:**
- Create: `src/main/scala/m68k040/Config.scala`
- Test: `src/test/scala/m68k040/ConfigSpec.scala`

- [ ] **Step 1: Write the failing test**

```scala
package m68k040

import org.scalatest.funsuite.AnyFunSuite

class ConfigSpec extends AnyFunSuite {
  test("default params expose the Appendix A baselines") {
    val p = M68kParams()
    assert(p.robDepth == 64)
    assert(p.physInt == 48)
    assert(p.physNzvc == 16)
    assert(p.physX == 16)
    assert(p.decodeWidth == 2)
    assert(p.retireWidth == 2)
    assert(p.l1dKb == 16 && p.l1iKb == 16)
  }

  test("derived widths are consistent") {
    val p = M68kParams()
    assert(p.robIdWidth == 6)        // log2Up(64)
    assert(p.physIntIdWidth == 6)    // log2Up(48) == 6
  }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `sbt "testOnly m68k040.ConfigSpec"`
Expected: FAIL — `object M68kParams is not a member of package m68k040`.

- [ ] **Step 3: Write the minimal implementation**

```scala
package m68k040

import spinal.core._

/** Compile-time sizing for the whole core (spec Appendix A). All depths are
  * parameters so IPC/area/FMax can be swept without rearchitecting. */
case class M68kParams(
    robDepth:     Int = 64,
    physInt:      Int = 48,
    physNzvc:     Int = 16,
    physX:        Int = 16,
    intRsDepth:   Int = 8,
    eaRsDepth:    Int = 8,
    memRsDepth:   Int = 8,
    cplxRsDepth:  Int = 4,
    loadQDepth:   Int = 8,
    storeQDepth:  Int = 8,
    l1iKb:        Int = 16,
    l1dKb:        Int = 16,
    l2Kb:         Int = 1024,
    btbEntries:   Int = 256,
    gshareHistory:Int = 16,
    gshareEntries:Int = 2048,
    tlbWays:      Int = 4,
    tlbSets:      Int = 16,
    decodeWidth:  Int = 2,
    retireWidth:  Int = 2
) {
  val robIdWidth:     Int = log2Up(robDepth)
  val physIntIdWidth: Int = log2Up(physInt)
  val physNzvcIdWidth:Int = log2Up(physNzvc)
  val physXIdWidth:   Int = log2Up(physX)
  val sqPtrWidth:     Int = log2Up(storeQDepth)
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `sbt "testOnly m68k040.ConfigSpec"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/scala/m68k040/Config.scala src/test/scala/m68k040/ConfigSpec.scala
git commit -m "feat: M68kParams sizing case class (Appendix A baselines)"
```

---

## Task 3: ISA constants & enums

**Files:**
- Create: `src/main/scala/m68k040/isa/Isa.scala`
- Test: `src/test/scala/m68k040/isa/IsaSpec.scala`

- [ ] **Step 1: Write the failing test**

```scala
package m68k040.isa

import org.scalatest.funsuite.AnyFunSuite

class IsaSpec extends AnyFunSuite {
  test("16 architectural integer registers (D0-D7, A0-A7)") {
    assert(Isa.ARCH_INT_REGS == 16)
  }
  test("CCR bit positions match m68k (X N Z V C)") {
    assert(Isa.CCR_C == 0 && Isa.CCR_V == 1 && Isa.CCR_Z == 2 && Isa.CCR_N == 3 && Isa.CCR_X == 4)
  }
  test("NZVC mask covers bits 3..0 and X mask is bit 4") {
    assert(Isa.MASK_NZVC == 0xF)
    assert(Isa.MASK_X == 0x10)
  }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `sbt "testOnly m68k040.isa.IsaSpec"`
Expected: FAIL — `object Isa is not a member of package m68k040.isa`.

- [ ] **Step 3: Write the minimal implementation**

```scala
package m68k040.isa

import spinal.core._

/** Architectural constants shared across plugins. m68k CCR layout is
  * bit4=X, bit3=N, bit2=Z, bit1=V, bit0=C. */
object Isa {
  val ARCH_INT_REGS = 16    // D0-D7, A0-A7
  val DATA_WIDTH    = 32

  val CCR_C = 0
  val CCR_V = 1
  val CCR_Z = 2
  val CCR_N = 3
  val CCR_X = 4

  val MASK_NZVC = 0xF       // N,Z,V,C (bits 3..0)
  val MASK_X    = 0x10      // X (bit 4)
}

/** Operand/result access size. */
object Size extends SpinalEnum {
  val BYTE, WORD, LONG = newElement()
}

/** Memory operation carried by a µop. */
object MemOp extends SpinalEnum {
  val NONE, LOAD, STORE = newElement()
}

/** Scheduler cluster a µop is steered to (spec 4.8). */
object Cluster extends SpinalEnum {
  val INT, EA, LS, CPLX = newElement()
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `sbt "testOnly m68k040.isa.IsaSpec"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/scala/m68k040/isa/Isa.scala src/test/scala/m68k040/isa/IsaSpec.scala
git commit -m "feat: ISA constants + Size/MemOp/Cluster enums"
```

---

## Task 4: Framework canary — Database keys, top, ParamPlugin, HelloPlugin

This is the **canary task**: it proves the FiberPlugin + Database + PluginHost composition actually elaborates. Do it as one unit because the pieces only make sense together.

**Files:**
- Create: `src/main/scala/m68k040/Global.scala`
- Create: `src/main/scala/m68k040/core/ParamPlugin.scala`
- Create: `src/main/scala/m68k040/core/HelloPlugin.scala`
- Create: `src/main/scala/m68k040/core/M68kCore.scala`
- Test: `src/test/scala/m68k040/FrameworkSmokeSpec.scala`

- [ ] **Step 1: Write the failing smoke test**

```scala
package m68k040

import m68k040.core.{M68kCore, ParamPlugin, HelloPlugin}
import spinal.core._
import spinal.lib.misc.plugin.FiberPlugin
import org.scalatest.funsuite.AnyFunSuite

class FrameworkSmokeSpec extends AnyFunSuite {
  test("core hosts plugins and elaborates to Verilog") {
    val report = SpinalConfig(targetDirectory = "simWorkspace/gen").generateVerilog {
      val plugins = Seq[FiberPlugin](new ParamPlugin(M68kParams()), new HelloPlugin())
      new M68kCore(plugins)
    }
    assert(report.toplevelName == "M68kCore")
  }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `sbt "testOnly m68k040.FrameworkSmokeSpec"`
Expected: FAIL — unresolved `m68k040.core.M68kCore` / `Global`.

- [ ] **Step 3: Write `Global.scala` (Database key registry — Appendix B start)**

```scala
package m68k040

import spinal.lib.misc.database.Database

/** Single registry of Database keys. INVARIANT #3: every key has exactly one
  * producer plugin (documented in the comment). `blocking` keys make readers
  * await the producer; `value` keys are plain. */
object Global {
  // Producer: ParamPlugin (setup phase)
  val ROB_DEPTH       = Database.blocking[Int]()
  val PHYS_INT_REGS   = Database.blocking[Int]()
  val PHYS_NZVC_REGS  = Database.blocking[Int]()
  val PHYS_X_REGS     = Database.blocking[Int]()
  val DECODE_WIDTH    = Database.blocking[Int]()
  val RETIRE_WIDTH    = Database.blocking[Int]()
}
```

- [ ] **Step 4: Write `ParamPlugin.scala` (the single producer of the keys)**

```scala
package m68k040.core

import m68k040.{Global, M68kParams}
import spinal.lib.misc.plugin.FiberPlugin

/** Publishes the M68kParams sizing into the Database during the setup phase so
  * every other plugin can read it during build. Sole producer of Global sizing keys. */
class ParamPlugin(p: M68kParams) extends FiberPlugin {
  val logic = during setup {
    Global.ROB_DEPTH.set(p.robDepth)
    Global.PHYS_INT_REGS.set(p.physInt)
    Global.PHYS_NZVC_REGS.set(p.physNzvc)
    Global.PHYS_X_REGS.set(p.physX)
    Global.DECODE_WIDTH.set(p.decodeWidth)
    Global.RETIRE_WIDTH.set(p.retireWidth)
  }
}
```

- [ ] **Step 5: Write `HelloPlugin.scala` (no-op canary that reads a key + makes RTL)**

```scala
package m68k040.core

import m68k040.Global
import spinal.core._
import spinal.lib.misc.plugin.FiberPlugin

/** Placeholder plugin proving a plugin can block on a Database key during build
  * and emit real hardware. Deleted once the first real frontend plugin lands. */
class HelloPlugin extends FiberPlugin {
  val logic = during build new Area {
    val depth   = Global.ROB_DEPTH.get          // blocks until ParamPlugin sets it
    val counter = Reg(UInt(log2Up(depth) bits)) init (0)
    counter := counter + 1
  }
}
```

- [ ] **Step 6: Write `M68kCore.scala` (top: Database + PluginHost)**

```scala
package m68k040.core

import spinal.core._
import spinal.lib.misc.database.Database
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}

/** Top-level core shell. Owns the Database blackboard and the PluginHost; all
  * behavior lives in the hosted plugins (spec invariant #3). */
class M68kCore(val plugins: Seq[FiberPlugin]) extends Component {
  setDefinitionName("M68kCore")
  val database = new Database
  val host     = new PluginHost
  database.on {
    host.asHostOf(plugins)
  }
}
```

- [ ] **Step 7: Run the smoke test**

Run: `sbt "testOnly m68k040.FrameworkSmokeSpec"`
Expected: PASS — emits `simWorkspace/gen/M68kCore.v`.
If it FAILS in elaboration (fiber deadlock / scope error), the suspect is Step 6's scoping: try wrapping host creation in the database scope (`val host = database on (new PluginHost)`), per the VexiiRiscv reference noted in the plan header. Re-run until green before moving on.

- [ ] **Step 8: Commit**

```bash
git add src/main/scala/m68k040/Global.scala src/main/scala/m68k040/core/ src/test/scala/m68k040/FrameworkSmokeSpec.scala
git commit -m "feat: plugin framework backbone + Database registry (canary green)"
```

---

## Task 5: Keystone µop bundle (spec 4.4)

**Files:**
- Create: `src/main/scala/m68k040/types/MicroOp.scala`
- Test: `src/test/scala/m68k040/types/MicroOpSpec.scala`

- [ ] **Step 1: Write the failing test**

```scala
package m68k040.types

import m68k040.M68kParams
import spinal.core._
import org.scalatest.funsuite.AnyFunSuite

class MicroOpSpec extends AnyFunSuite {
  test("MicroOp has the spec 4.4 fields with param-derived widths") {
    SpinalConfig().generateVerilog(new Component {
      val p   = M68kParams()
      val uop = in(MicroOp(p))
      // field presence + widths (compile-time assertions inside elaboration)
      assert(uop.robId.getWidth == p.robIdWidth)
      assert(uop.psrc0.getWidth == p.physIntIdWidth)
      assert(uop.sqPtr0.getWidth == p.sqPtrWidth)
      assert(uop.twoAccess.isInstanceOf[Bool])
      out(uop.lastUop)        // keep something driven so elaboration is valid
    })
  }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `sbt "testOnly m68k040.types.MicroOpSpec"`
Expected: FAIL — `not found: type MicroOp`.

- [ ] **Step 3: Write the implementation**

```scala
package m68k040.types

import m68k040.M68kParams
import m68k040.isa.{Cluster, MemOp, Size}
import spinal.core._

/** Internal scheduling unit (spec 4.4). NEVER commits architecturally — produces
  * only internal state. The instruction (RobEntry) is the architectural unit. */
case class MicroOp(p: M68kParams) extends Bundle {
  val robId        = UInt(p.robIdWidth bits)
  val uopIdx       = UInt(2 bits)            // up to 4 µops/instruction
  val lastUop      = Bool()
  val cluster      = Cluster()
  val opClass      = Bits(8 bits)            // decoded op selector (refined per cluster later)
  val staticLatency= UInt(4 bits)            // ready_cycle = issue_cycle + staticLatency

  // integer source/dest physical registers
  val psrc0        = UInt(p.physIntIdWidth bits)
  val psrc1        = UInt(p.physIntIdWidth bits)
  val src0Ready    = Bool()
  val src1Ready    = Bool()
  val pdst         = UInt(p.physIntIdWidth bits)
  val pdstValid    = Bool()
  val archDst      = UInt(log2Up(m68k040.isa.Isa.ARCH_INT_REGS) bits)

  // split-renamed flags (spec 4.6): NZVC and X independent
  val pNzvcSrc     = UInt(p.physNzvcIdWidth bits)
  val pXSrc        = UInt(p.physXIdWidth bits)
  val readsNzvc    = Bool()
  val readsX       = Bool()
  val pNzvcDst     = UInt(p.physNzvcIdWidth bits)
  val pXDst        = UInt(p.physXIdWidth bits)
  val writesNzvc   = Bool()
  val writesX      = Bool()

  val size         = Size()
  val imm          = Bits(32 bits)

  // memory (spec 4.4 / 7.1.1): up to two accesses for misaligned line/page crossing
  val memOp        = MemOp()
  val twoAccess    = Bool()
  val sqPtr0       = UInt(p.sqPtrWidth bits)
  val sqPtr1       = UInt(p.sqPtrWidth bits)

  val mayTrap      = Bool()
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `sbt "testOnly m68k040.types.MicroOpSpec"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/scala/m68k040/types/MicroOp.scala src/test/scala/m68k040/types/MicroOpSpec.scala
git commit -m "feat: keystone MicroOp bundle (spec 4.4, two-access)"
```

---

## Task 6: ROB entry & predecode metadata bundles (spec 4.5, 5.1)

**Files:**
- Create: `src/main/scala/m68k040/types/RobEntry.scala`
- Create: `src/main/scala/m68k040/types/PredecodeMeta.scala`
- Test: `src/test/scala/m68k040/types/RobEntrySpec.scala`

- [ ] **Step 1: Write the failing test**

```scala
package m68k040.types

import m68k040.M68kParams
import spinal.core._
import org.scalatest.funsuite.AnyFunSuite

class RobEntrySpec extends AnyFunSuite {
  test("RobEntry and PredecodeMeta elaborate with spec fields") {
    SpinalConfig().generateVerilog(new Component {
      val p    = M68kParams()
      val rob  = in(RobEntry(p))
      val meta = in(PredecodeMeta())
      assert(rob.outstandingUops.getWidth == 3)   // up to 4 µops -> log2Up(4)+1 headroom
      assert(rob.ccrWriteMask.getWidth == 5)       // X,N,Z,V,C
      assert(meta.ccrReadMask.getWidth == 5)
      out(rob.valid)
      out(meta.simple)
    })
  }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `sbt "testOnly m68k040.types.RobEntrySpec"`
Expected: FAIL — `not found: type RobEntry`.

- [ ] **Step 3: Write `PredecodeMeta.scala`**

```scala
package m68k040.types

import spinal.core._

/** Rich predecode metadata produced in the PD stage (spec 5.1 / "Frontend
  * Metadata"). Drives scheduling and retirement decisions. */
case class PredecodeMeta() extends Bundle {
  val length      = UInt(3 bits)     // instruction length in words (1..5)
  val simple      = Bool()           // simple (fast path) vs complex (microcode)
  val branchType  = Bits(3 bits)     // none/Bcc/BSR/JMP/JSR/RTS/RTR/DBcc encoding
  val isCall      = Bool()           // BSR/JSR -> RAS push
  val isReturn    = Bool()           // RTS/RTR -> RAS pop
  val usesMem     = Bool()
  val writesMem   = Bool()
  val mayTrap     = Bool()
  val ccrReadMask = Bits(5 bits)     // X,N,Z,V,C consumed
  val ccrWriteMask= Bits(5 bits)     // X,N,Z,V,C produced
  val eaClass     = Bits(4 bits)     // EA addressing-mode class
}
```

- [ ] **Step 4: Write `RobEntry.scala`**

```scala
package m68k040.types

import m68k040.M68kParams
import spinal.core._

/** The architectural unit (spec 4.5). The ROB is keyed by INSTRUCTION, never by
  * µop. Commit is the single precise retire boundary and the lock-step compare point. */
case class RobEntry(p: M68kParams) extends Bundle {
  val valid        = Bool()
  val pc           = UInt(32 bits)
  val predNextPc   = UInt(32 bits)
  val length       = UInt(3 bits)
  val simple       = Bool()
  val retireAlone  = Bool()
  val serialize    = Bool()

  // mappings for commit + physreg release (new + old), per renamed resource
  val intArchDst   = UInt(log2Up(m68k040.isa.Isa.ARCH_INT_REGS) bits)
  val intNewPdst   = UInt(p.physIntIdWidth bits)
  val intOldPdst   = UInt(p.physIntIdWidth bits)
  val intWrites    = Bool()
  val nzvcNewPdst  = UInt(p.physNzvcIdWidth bits)
  val nzvcOldPdst  = UInt(p.physNzvcIdWidth bits)
  val xNewPdst     = UInt(p.physXIdWidth bits)
  val xOldPdst     = UInt(p.physXIdWidth bits)
  val ccrWriteMask = Bits(5 bits)     // X,N,Z,V,C this instruction writes

  // store-queue slots owned by this instruction (range; two for misaligned)
  val sqPtrBase    = UInt(p.sqPtrWidth bits)
  val sqPtrCount   = UInt(2 bits)

  // completion bookkeeping (spec 4.5.1): singleUop bypasses the counter
  val singleUop      = Bool()
  val outstandingUops= UInt(3 bits)
  val complete       = Bool()

  // precise-exception state (spec 8)
  val excValid     = Bool()
  val excVector    = UInt(8 bits)
  val faultAddr    = UInt(32 bits)
  val stackFormat  = UInt(4 bits)

  val branchResolved = Bool()
  val mispredicted   = Bool()
}
```

- [ ] **Step 5: Run it to verify it passes**

Run: `sbt "testOnly m68k040.types.RobEntrySpec"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/scala/m68k040/types/RobEntry.scala src/main/scala/m68k040/types/PredecodeMeta.scala src/test/scala/m68k040/types/RobEntrySpec.scala
git commit -m "feat: RobEntry + PredecodeMeta keystone bundles (spec 4.5, 5.1)"
```

---

## Task 7: Commit-trace port bundle (invariant #5) + service catalog

**Files:**
- Create: `src/main/scala/m68k040/types/CommitTrace.scala`
- Create: `src/main/scala/m68k040/services/Services.scala`
- Test: `src/test/scala/m68k040/types/CommitTraceSpec.scala`

- [ ] **Step 1: Write the failing test**

```scala
package m68k040.types

import spinal.core._
import org.scalatest.funsuite.AnyFunSuite

class CommitTraceSpec extends AnyFunSuite {
  test("CommitTrace exposes the lock-step compare fields") {
    SpinalConfig().generateVerilog(new Component {
      val t = out(CommitTrace())
      t.assignDontCare()
      assert(t.pc.getWidth == 32)
      assert(t.archRegWrite.getWidth == 32)
      assert(t.ccr.getWidth == 5)
    })
  }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `sbt "testOnly m68k040.types.CommitTraceSpec"`
Expected: FAIL — `not found: type CommitTrace`.

- [ ] **Step 3: Write `CommitTrace.scala`**

```scala
package m68k040.types

import spinal.core._

/** Designed-in observability (spec invariant #5). One pulse per retired
  * instruction, consumed by the lock-step harness which compares architectural
  * state against the reference model at the ROB retire boundary. */
case class CommitTrace() extends Bundle {
  val fire         = Bool()          // a single instruction retired this cycle
  val pc           = UInt(32 bits)
  val opword       = Bits(16 bits)
  val archRegId    = UInt(4 bits)
  val archRegWrite = Bits(32 bits)
  val archRegValid = Bool()
  val ccr          = Bits(5 bits)    // X,N,Z,V,C after this instruction
  val ccrValid     = Bool()
  val memAddr      = UInt(32 bits)
  val memData      = Bits(32 bits)
  val memWrite     = Bool()
  val excTaken     = Bool()
  val excVector    = UInt(8 bits)
}
```

- [ ] **Step 4: Write `services/Services.scala` (service-interface catalog — Appendix B)**

```scala
package m68k040.services

import m68k040.types.CommitTrace
import spinal.core._

/** Catalog of cross-plugin service interfaces (spec invariant #3 / Appendix B).
  * A plugin implements a trait and registers via addService(this); consumers
  * resolve it with host[ServiceName]. Grown as real plugins are added. */

/** Produced by the commit plugin; consumed by the verification harness. */
trait CommitTraceService {
  def trace: CommitTrace
}

/** Produced by the redirect/flush owner (commit/branch); consumed by frontend. */
trait FlushService {
  def doFlush: Bool
  def flushPc: UInt
}
```

- [ ] **Step 5: Run it to verify it passes**

Run: `sbt "testOnly m68k040.types.CommitTraceSpec"`
Expected: PASS. Also run `sbt compile` to confirm `Services.scala` compiles.

- [ ] **Step 6: Commit**

```bash
git add src/main/scala/m68k040/types/CommitTrace.scala src/main/scala/m68k040/services/Services.scala src/test/scala/m68k040/types/CommitTraceSpec.scala
git commit -m "feat: CommitTrace observability port + service catalog (Appendix B)"
```

---

## Task 8: Verilog generation entrypoint + test tags

**Files:**
- Create: `src/main/scala/m68k040/top/GenVerilog.scala`
- Create: `src/test/scala/m68k040/TestTags.scala`

- [ ] **Step 1: Write `TestTags.scala`**

```scala
package m68k040

import org.scalatest.Tag

/** Tags excluded by `fastTest` (see build.sbt). */
object SlowTest      extends Tag("m68k040.SlowTest")
object VerilatorTest extends Tag("m68k040.VerilatorTest")
object BoardTest     extends Tag("m68k040.BoardTest")
```

- [ ] **Step 2: Write `GenVerilog.scala`**

```scala
package m68k040.top

import m68k040.M68kParams
import m68k040.core.{M68kCore, ParamPlugin, HelloPlugin}
import spinal.core._
import spinal.lib.misc.plugin.FiberPlugin

object GenVerilog {
  def plugins(p: M68kParams): Seq[FiberPlugin] =
    Seq(new ParamPlugin(p), new HelloPlugin())

  def main(args: Array[String]): Unit = {
    val p = M68kParams()
    SpinalConfig(targetDirectory = "generated")
      .generateVerilog(new M68kCore(plugins(p)))
    println("Generated generated/M68kCore.v")
  }
}
```

- [ ] **Step 3: Verify generation works**

Run: `sbt "runMain m68k040.top.GenVerilog"`
Expected: prints `Generated generated/M68kCore.v`; file exists.

- [ ] **Step 4: Commit**

```bash
git add src/main/scala/m68k040/top/GenVerilog.scala src/test/scala/m68k040/TestTags.scala
git commit -m "feat: Verilog gen entrypoint + scalatest tags"
```

---

## Task 9: Makefile

**Files:**
- Create: `Makefile`
- Test: manual target invocation.

- [ ] **Step 1: Write `Makefile`**

```makefile
SBT ?= sbt

.PHONY: compile test-fast test verilog clean

compile:
	$(SBT) compile

test-fast:
	$(SBT) fastTest

test:
	$(SBT) test

verilog:
	$(SBT) "runMain m68k040.top.GenVerilog"

clean:
	$(SBT) clean
	rm -rf simWorkspace generated
```

- [ ] **Step 2: Verify `make test-fast` runs the smoke + bundle suites green**

Run: `make test-fast`
Expected: all specs PASS (ConfigSpec, IsaSpec, FrameworkSmokeSpec, MicroOpSpec, RobEntrySpec, CommitTraceSpec), none excluded yet.

- [ ] **Step 3: Commit**

```bash
git add Makefile
git commit -m "build: Makefile (compile/test-fast/test/verilog)"
```

---

## Task 10: Port engineering tooling from the 030 repo

**Files:**
- Create: `tools/agent_worktree_pool.sh`
- Create: `tools/pm_gate.sh`
- Create: `tools/musashi/README.md`
- Create: `tools/.gitkeep` (if needed)

- [ ] **Step 1: Port the worktree pool with renamed env/paths**

Run:
```bash
mkdir -p tools tools/musashi
cp /home/qwertyoruiop/m68k-core-030-inorder/tools/agent_worktree_pool.sh tools/agent_worktree_pool.sh
sed -i \
  -e 's/M68K030_WORKTREE_POOL_SIZE/M68K040_WORKTREE_POOL_SIZE/g' \
  -e 's/M68K030_WORKTREE_POOL/M68K040_WORKTREE_POOL/g' \
  -e 's#/home/qwertyoruiop/m68k-core-030-inorder-worktrees#/home/qwertyoruiop/m68k-core-040-ooo-worktrees#g' \
  tools/agent_worktree_pool.sh
cp /home/qwertyoruiop/m68k-core-030-inorder/tools/pm_gate.sh tools/pm_gate.sh
chmod +x tools/agent_worktree_pool.sh tools/pm_gate.sh
```

- [ ] **Step 2: Verify the scripts are syntactically valid and help works**

Run:
```bash
bash -n tools/agent_worktree_pool.sh && bash -n tools/pm_gate.sh && echo "syntax OK"
tools/agent_worktree_pool.sh help
```
Expected: `syntax OK`, then the usage text. (Do NOT run `init` here — it needs at least one commit on the repo, which exists, but pool creation is the PM's call.)

- [ ] **Step 3: Write `tools/musashi/README.md` (reference-model vendoring note)**

```markdown
# Musashi reference model (lock-step oracle)

The lock-step harness (spec ch 11) compares architectural state against Musashi
at every retired instruction. Reuse the integration already built in the sibling
repo rather than rebuilding:

    cp -r /home/qwertyoruiop/m68k-core-030-inorder/tools/musashi/* .

Then follow that copy's build instructions. This directory is the vendoring point;
the lock-step bridge is implemented in the verification-harness plan, not here.
```

- [ ] **Step 4: Commit**

```bash
git add tools/
git commit -m "chore: port worktree pool + pm gate; musashi vendoring note"
```

---

## Task 11: Orientation docs

**Files:**
- Create: `README.md`
- Create: `AGENTS.md`

- [ ] **Step 1: Write `README.md`**

```markdown
# m68k-core-040-ooo

Out-of-order, superscalar 68000–68040 core. SpinalHDL, plugin (Fiber+Database) backbone.

- **Design doc:** `docs/superpowers/specs/2026-05-31-m68k-040-ooo-architecture-design.md`
- **Plans:** `docs/superpowers/plans/`

## Commands
    make compile      # sbt compile
    make test-fast    # fast scalatest gate (excludes slow/verilator/board tags)
    make test         # full suite
    make verilog      # elaborate generated/M68kCore.v

## Layout
    src/main/scala/m68k040/core       top shell + framework plugins
    src/main/scala/m68k040/isa        architectural constants + enums
    src/main/scala/m68k040/types      keystone bundles (MicroOp, RobEntry, ...)
    src/main/scala/m68k040/services   cross-plugin service interfaces
    src/main/scala/m68k040            M68kParams, Global key registry
    tools                             worktree pool, pm gate, musashi
```

- [ ] **Step 2: Write `AGENTS.md`**

```markdown
# Agent guide

- Architecture is fixed by the design doc; do not deviate without a spec update.
- INVARIANT #3: every Database key in `Global` has exactly ONE producer plugin.
  Adding a key means documenting its producer in the comment.
- Plugins communicate ONLY via `host[Service]` and `Global` keys — never reach
  into another plugin's internals.
- Reserve an isolated workspace before editing: `tools/agent_worktree_pool.sh reserve <name> "<task>"`.
- Gate before handoff: `make test-fast`. Full `make test` / Verilator runs are PM-serialized.
```

- [ ] **Step 3: Commit**

```bash
git add README.md AGENTS.md
git commit -m "docs: README + AGENTS orientation"
```

---

## Self-review notes (author-completed)

- **Spec coverage:** invariant #3 → Global registry + Services catalog + AGENTS rule (T4/T7/T11); invariant #5 → CommitTrace (T7); keystone 4.4/4.5/5.1 → MicroOp/RobEntry/PredecodeMeta (T5/T6); 4.6 split flags → MicroOp NZVC/X fields (T5); Appendix A → M68kParams (T2); engineering process → tooling port (T10). Pipeline *logic* (frontend, rename, clusters, LSU, MMU, commit) is explicitly out of scope — each is a later plan.
- **Type consistency:** `M68kParams` field names (`robIdWidth`, `physIntIdWidth`, `physNzvcIdWidth`, `physXIdWidth`, `sqPtrWidth`) are used identically in MicroOp/RobEntry. `Global` keys set in ParamPlugin match those read by HelloPlugin (`ROB_DEPTH`).
- **No placeholders:** every code/command step is concrete; the one conditional (T4 step 7 scoping fallback) is a real debug instruction gated by a smoke test, not a TODO. `HelloPlugin` is explicitly a temporary canary, removed when the first frontend plugin lands.

## Downstream plans (not this plan)

Database key registry grows per subsystem. Subsequent plans, each producing working/testable software: **(2)** verification harness + Musashi lock-step bridge against a trivial trace producer; **(3)** frontend (fetch/predecode/align); **(4)** decode + µop expansion; **(5)** rename (dual-RAM RAT, freelists, split CCR); **(6)** ROB + commit + CommitTrace producer; **(7)** clustered issue; **(8)** execute units; **(9)** LSU; **(10)** MMU/caches; **(11)** top/SoC + AXI. Build order keeps a lock-step-verifiable core as early as possible (harness before backend depth).
