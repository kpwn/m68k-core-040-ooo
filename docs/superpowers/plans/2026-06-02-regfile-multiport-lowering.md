# Multi-Write Register-File Lowering Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the core's multi-write-port async-read memories (RAT, Freelist, ROB payload) natively FPGA-synthesizable by adopting NaxRiscv's `MultiPortWritesSymplifier` transformation phase, replacing the `regfile_fix.py` netlist post-process.

**Architecture:** Vendor a trimmed MIT-attributed `MultiPortWritesSymplifier` (+ `RamAsyncMwXor`/`RamAsyncMwMux`) into the tree; add it to a shared `M68kSpinalConfig` used by both Verilog generation and the RF specs' simulation. The phase lowers each multi-write `Mem` at elaboration — XOR-banks for <10-bit data (RAT, Freelist), Live-Value-Table banks for ≥10-bit (ROB payload). The three structures' RTL is unchanged. Tests run against the lowered logic (equivalence proof); synth then needs no post-process.

**Tech Stack:** SpinalHDL 1.14.1 / Scala 2.13 / sbt at `~/sbt/bin/sbt` (NOT on PATH) / Verilator / ScalaTest. Vivado 2025.2, part `xcku5p-ffvb676-2-e`. NaxRiscv source: `/home/qwertyoruiop/m68k-ooo-v2/thirdparty/NaxRiscv/src/main/scala/naxriscv/compatibility/MultiportRam.scala`.

**Branch:** `feat/regfile-multiport` (created; spec committed).

**KEY RISK (read first):** the phase uses SpinalHDL **internal** API (`spinal.core.internals.{PhaseMemBlackboxing, MemTopology}`, `wrapConsumers`, `MemWrite.writeEnable/address/data/mask`, `mem.removeStatement`/`foreachStatements`). NaxRiscv may have been pinned to a different SpinalHDL than our 1.14.1. **Compile early (Task 1 Step 3) and fix any API drift** before proceeding. If an internal symbol is gone/renamed, find its 1.14.1 equivalent (search the SpinalHDL jar sources) — do not stub it out.

---

### Task 1: Vendor the lowering phase + shared SpinalConfig + wire into GenVerilog

**Files:**
- Create: `src/main/scala/m68k040/hw/MultiportRam.scala`
- Create: `src/main/scala/m68k040/M68kSpinalConfig.scala`
- Modify: `src/main/scala/m68k040/top/GenVerilog.scala`

- [ ] **Step 1: Create the vendored phase**

Create `src/main/scala/m68k040/hw/MultiportRam.scala` with exactly this (trimmed port of NaxRiscv's `naxriscv.compatibility.MultiportRam`, MIT):

```scala
// Adapted from NaxRiscv (naxriscv.compatibility.MultiportRam), SPDX MIT,
// "2023 Everybody". Trimmed to the async-read multi-write lowering used by this
// core (RAT / Freelist / ROB payload). Lowers a Mem with >1 write port and
// async reads into single-write distributed-RAM banks: XOR-trick for narrow
// data, Live-Value-Table (Mux) for wide data (>=10 bits).
package m68k040.hw

import spinal.core._
import spinal.core.internals._
import spinal.lib._

case class MemWriteCmd[T <: Data](payloadType: HardType[T], depth: Int) extends Bundle {
  val address = UInt(log2Up(depth) bits)
  val data    = payloadType()
}

case class MemRead[T <: Data](payloadType: HardType[T], depth: Int) extends Bundle with IMasterSlave {
  val cmd = Flow(UInt(log2Up(depth) bits))
  val rsp = payloadType()
  override def asMaster() = { master(cmd); in(rsp) }
}

case class RamAxyncMwIo[T <: Data](payloadType: HardType[T], depth: Int, writePorts: Int, readPorts: Int) extends Bundle {
  val writes = Vec.fill(writePorts)(slave(Flow(MemWriteCmd(payloadType, depth))))
  val read   = Vec.fill(readPorts)(slave(MemRead(payloadType, depth)))
}

/** N-write / M-read async register file via the XOR trick (one 1W RAM per write
  * port). Precondition: no two write ports target the same address in the same
  * cycle. */
case class RamAsyncMwXor[T <: Data](payloadType: HardType[T], depth: Int, writePorts: Int, readPorts: Int) extends Component {
  val io = RamAxyncMwIo(payloadType, depth, writePorts, readPorts)
  val rawType = HardType(Bits(payloadType.getBitsWidth bits))
  val ram = List.fill(writePorts)(Mem.fill(depth)(rawType))

  val writes = for ((port, storage) <- (io.writes, ram).zipped) yield new Area {
    val values = ram.filter(_ != storage).map(_.readAsync(port.address))
    val xored  = (port.data.asBits :: values).reduceBalancedTree(_ ^ _)
    storage.write(enable = port.valid, address = port.address, data = xored)
  }

  val reads = for (port <- io.read) yield new Area {
    val values = ram.map(_.readAsync(port.cmd.payload))
    val xored  = values.reduceBalancedTree(_ ^ _)
    port.rsp := xored.as(payloadType)
  }

  def addMemTags(spinalTags: Seq[SpinalTag]): this.type = { ram.foreach(_.addTags(spinalTags)); this }
}

/** N-write / M-read async register file via a Live-Value-Table (one bank per
  * write port + a narrow XOR table recording which bank last wrote each
  * address). Preferred for wide data. */
case class RamAsyncMwMux[T <: Data](payloadType: HardType[T], depth: Int, writePorts: Int, readPorts: Int) extends Component {
  val io = RamAxyncMwIo(payloadType, depth, writePorts, readPorts)
  val rawType = HardType(Bits(payloadType.getBitsWidth bits))
  val ram = List.fill(writePorts)(Mem.fill(depth)(rawType))

  val location = RamAsyncMwXor(
    payloadType = UInt(log2Up(writePorts) bits),
    depth = depth, writePorts = writePorts, readPorts = readPorts
  )

  val writes = for ((port, storage, loc) <- (io.writes, ram, location.io.writes).zipped) yield new Area {
    storage.write(enable = port.valid, address = port.address, data = port.data.asBits)
    loc.valid := port.valid
    loc.address := port.address
    loc.data := U(ram.indexOf(storage))
  }

  val reads = for ((port, loc) <- (io.read, location.io.read).zipped) yield new Area {
    loc.cmd.valid := port.cmd.valid
    loc.cmd.payload := port.cmd.payload
    val perBank = ram.map(_.readAsync(port.cmd.payload))
    port.rsp := Vec(perBank)(loc.rsp).as(payloadType)
  }

  def addMemTags(spinalTags: Seq[SpinalTag]): this.type = { ram.foreach(_.addTags(spinalTags)); this }
}

/** SpinalHDL elaboration phase: rewrite any Mem with >1 write port and async
  * reads into XOR/LVT banks (single-write distributed RAM). Multi-write Mems
  * with sync reads are not used in this core; we error rather than silently
  * leave them unsynthesizable. */
class MultiPortWritesSymplifier extends PhaseMemBlackboxing {
  override def doBlackboxing(pc: PhaseContext, typo: MemTopology): Unit = {
    if (typo.writes.size <= 1) return

    if (typo.readsSync.size == 0 && typo.readsAsync.size != 0) {
      typo.writes.foreach(w => assert(w.mask == null, "MultiPortWritesSymplifier: masked writes unsupported"))
      typo.writes.foreach(w => assert(w.clockDomain == typo.writes.head.clockDomain))
      val cd = typo.writes.head.clockDomain
      import typo._
      val ctx = List(mem.parentScope.push(), cd.push())

      val io = if (typo.mem.width >= 10) {
        RamAsyncMwMux(Bits(mem.width bits), mem.wordCount, writes.size, readsAsync.size)
          .setCompositeName(mem).addMemTags(mem.getTags().toSeq).io
      } else {
        RamAsyncMwXor(Bits(mem.width bits), mem.wordCount, writes.size, readsAsync.size)
          .setCompositeName(mem).addMemTags(mem.getTags().toSeq).io
      }

      for ((dst, src) <- (io.writes, writes).zipped) {
        dst.valid.assignFrom(src.writeEnable)
        dst.address.assignFrom(src.address)
        dst.data.assignFrom(src.data)
      }
      for ((reworked, old) <- (io.read, readsAsync).zipped) {
        reworked.cmd.valid := True
        reworked.cmd.payload.assignFrom(old.address)
        wrapConsumers(typo, old, reworked.rsp)
      }

      mem.removeStatement()
      mem.foreachStatements(s => s.removeStatement())
      ctx.foreach(_.restore())
    } else {
      SpinalError(s"MultiPortWritesSymplifier: unsupported multi-write Mem topology " +
        s"(writes=${typo.writes.size}, readsAsync=${typo.readsAsync.size}, readsSync=${typo.readsSync.size}) for ${typo.mem}. " +
        s"Only async-read multi-write Mems are handled; add the sync branch from NaxRiscv if needed.")
    }
  }
}
```

- [ ] **Step 2: Create the shared SpinalConfig**

Create `src/main/scala/m68k040/M68kSpinalConfig.scala`:

```scala
package m68k040

import m68k040.hw.MultiPortWritesSymplifier
import spinal.core._

/** Single source of truth for the core's SpinalHDL elaboration config. Adds the
  * multi-write-Mem lowering phase so RAT/Freelist/ROB-payload synthesize on FPGA
  * (no FPGA RAM primitive has >1 write port). Used by both Verilog generation
  * and the register-file specs' simulation (so tests exercise the lowered logic). */
object M68kSpinalConfig {
  def apply(targetDirectory: String = null): SpinalConfig = {
    val base = if (targetDirectory != null) SpinalConfig(targetDirectory = targetDirectory) else SpinalConfig()
    base.addTransformationPhase(new MultiPortWritesSymplifier())
  }
}
```

- [ ] **Step 3: Compile — shake out internal-API drift**

Run: `~/sbt/bin/sbt compile`
Expected: SUCCESS. If it fails on a `spinal.core.internals` symbol (e.g. `wrapConsumers`, `MemTopology.readsAsync`, `MemWrite.writeEnable`, `mem.foreachStatements`), locate the 1.14.1 equivalent (the internal API is stable across nearby versions but names can shift) and fix. Do NOT remove the lowering logic. Common adjustments: `wrapConsumers(typo, old, expr)` may be `typo.wrapConsumers(...)`; `src.writeEnable`/`src.address`/`src.data` are fields of `MemWrite`. Compile until green.

- [ ] **Step 4: Wire the config into GenVerilog**

In `src/main/scala/m68k040/top/GenVerilog.scala`, replace each `SpinalConfig(targetDirectory = "generated")` with `m68k040.M68kSpinalConfig(targetDirectory = "generated")`. There are three occurrences (GenVerilog, GenSynthVerilog, GenBackendSynthVerilog). Add `import m68k040.M68kSpinalConfig` if convenient, or use the fully-qualified name. Leave everything else unchanged.

- [ ] **Step 5: Elaborate the backend top and confirm lowering**

Run: `~/sbt/bin/sbt "runMain m68k040.top.GenBackendSynthVerilog"`
Expected: `Generated generated/M68kBackendSynth.v`, no `SpinalError`.

Verify the multi-write Mems were lowered (no array has two `always` write blocks anymore — each bank has exactly one writer):

Run: `grep -cE "RobPlugin_logic_payload\[" generated/M68kBackendSynth.v` and inspect that the payload is now bank/location structures (names containing `_ram_`/`location`), not a single 2-write array. Also: `grep -iE "RamAsyncMwXor|RamAsyncMwMux|_xored|location" generated/M68kBackendSynth.v | head` should show the lowered components exist.

Then confirm there are NO two-write-block-same-array patterns left for specRam/commitRam/ram/payload (the thing `regfile_fix.py` used to fix): each lowered bank reg has a single write `always` block. (A quick check: the generator no longer emits the side-by-side duplicate `always @(posedge clk)` writers to one array.)

- [ ] **Step 6: Commit**

```bash
git add src/main/scala/m68k040/hw/MultiportRam.scala src/main/scala/m68k040/M68kSpinalConfig.scala src/main/scala/m68k040/top/GenVerilog.scala
git commit -m "hw: vendor NaxRiscv MultiPortWritesSymplifier; lower multi-write Mems"
```

---

### Task 2: Run the register-file specs through the lowering (equivalence proof)

**Files:**
- Create: `src/test/scala/m68k040/M68kSim.scala`
- Modify: `src/test/scala/m68k040/rename/RatTableSpec.scala`, `FreelistSpec.scala`, `RenameStageSpec.scala`, `src/test/scala/m68k040/rob/RobPluginSpec.scala`

- [ ] **Step 1: Create the shared sim config**

Create `src/test/scala/m68k040/M68kSim.scala`:

```scala
package m68k040

import spinal.core.sim._

/** SimConfig that applies the core's elaboration config (incl. the multi-write
  * Mem lowering phase), so register-file simulations exercise the lowered
  * XOR/LVT banks — making the existing assertions an equivalence proof for the
  * lowering. Append `.withVerilator` at the call site where the spec used it. */
object M68kSim {
  def apply(): SpinalSimConfig = SimConfig.withConfig(M68kSpinalConfig())
}
```

- [ ] **Step 2: Route RatTableSpec and FreelistSpec through M68kSim**

In `src/test/scala/m68k040/rename/RatTableSpec.scala` and `src/test/scala/m68k040/rename/FreelistSpec.scala`, replace every `SimConfig.withVerilator.compile(` with `M68kSim().withVerilator.compile(`. Add `import m68k040.M68kSim` near the other imports if the package doesn't already make it visible (both specs are in package `m68k040.rename`, so import `m68k040.M68kSim`).

- [ ] **Step 3: Route RenameStageSpec and RobPluginSpec through M68kSim**

In `src/test/scala/m68k040/rename/RenameStageSpec.scala` and `src/test/scala/m68k040/rob/RobPluginSpec.scala`:
- Replace every `SimConfig.compile(` with `M68kSim().compile(`.
- Replace every `SimConfig.withVerilator.compile(` with `M68kSim().withVerilator.compile(`.
- Add `import m68k040.M68kSim` if needed.

(The bare `SimConfig.compile` calls keep the default backend — `M68kSim()` only adds the elaboration config, it does not force Verilator.)

- [ ] **Step 4: Add a targeted lowering reconstruction test**

This proves the XOR/LVT reconstruction directly (interleaved multi-port writes then reads), not just incidentally. Add to `src/test/scala/m68k040/rename/RatTableSpec.scala` inside its test class (it already has a `mk`/DUT factory and `SimConfig.withVerilator.compile(mk)` pattern — reuse the SAME DUT factory the other tests use; if the factory is named differently, match it). Use the existing port names of the DUT — read the top of RatTableSpec to confirm the write/read port field names (`io.writes(i).valid/addr/data`, `io.reads(r).addr/data`) before writing the body:

```scala
  test("multi-port writes reconstruct via lowered banks (XOR)") {
    M68kSim().withVerilator.compile(mk).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      // idle all write/commit ports
      dut.io.writes.foreach { w => w.valid #= false }
      dut.io.commits.foreach { c => c.valid #= false }
      cd.waitSampling(2)
      // Write two different arch regs from the two write ports in the same cycle
      // (distinct addresses -> XOR precondition satisfied).
      dut.io.writes(0).valid #= true; dut.io.writes(0).addr #= 3; dut.io.writes(0).data #= 17
      dut.io.writes(1).valid #= true; dut.io.writes(1).addr #= 9; dut.io.writes(1).data #= 42
      cd.waitSampling()
      dut.io.writes.foreach { w => w.valid #= false }
      cd.waitSampling()
      // Read them back through the lowered read network.
      dut.io.reads(0).addr #= 3
      dut.io.reads(1).addr #= 9
      sleep(1) // settle async reads
      assert(dut.io.reads(0).data.toBigInt == 17, s"reg3 got ${dut.io.reads(0).data.toBigInt}")
      assert(dut.io.reads(1).data.toBigInt == 42, s"reg9 got ${dut.io.reads(1).data.toBigInt}")
    }
  }
```

IMPORTANT: before writing this, READ `RatTableSpec.scala` to confirm: the DUT factory name (`mk`), the exact field names on `io.writes`/`io.reads`/`io.commits`, whether `location`/init writes the spec RAM (so a freshly-written value reads back without a commit), and the params (archDepth ≥ 10 so addr 9 is valid; if archDepth is 16 for the int RAT this holds). If the spec's DUT uses different field names or a smaller archDepth, adjust the addresses/names to fit — keep the intent: two same-cycle writes to distinct addresses via the two write ports, then read both back correctly. If `reads.data` reflects spec only after `location` bit is set, this still holds (a write sets it). Do NOT weaken the equality assertions.

- [ ] **Step 5: Run the four RF specs (now lowered)**

Run each (allow 590000 ms; Verilator builds are cold):
- `~/sbt/bin/sbt "testOnly m68k040.rename.RatTableSpec"`
- `~/sbt/bin/sbt "testOnly m68k040.rename.FreelistSpec"`
- `~/sbt/bin/sbt "testOnly m68k040.rename.RenameStageSpec"`
- `~/sbt/bin/sbt "testOnly m68k040.rob.RobPluginSpec"`
Expected: ALL pass — the existing assertions now validate the lowered XOR/LVT logic, plus the new reconstruction test.

If any RF spec FAILS: this is a real equivalence finding. Diagnose whether (a) the lowering is wrong (unlikely — NaxRiscv-proven), (b) a same-address same-cycle multi-write violates the XOR precondition in that structure (a real RTL hazard the lowering exposes — report it), or (c) a sim-timing issue in the new test. Report BLOCKED with specifics rather than weakening assertions.

- [ ] **Step 6: Full suites**

Run: `make SBT=~/sbt/bin/sbt test-fast`  → expect all pass.
Run: `make SBT=~/sbt/bin/sbt test-verilator`  → expect all pass.
Report totals.

- [ ] **Step 7: Commit**

```bash
git add src/test/scala/m68k040/M68kSim.scala src/test/scala/m68k040/rename/RatTableSpec.scala src/test/scala/m68k040/rename/FreelistSpec.scala src/test/scala/m68k040/rename/RenameStageSpec.scala src/test/scala/m68k040/rob/RobPluginSpec.scala
git commit -m "test: run RAT/Freelist/Rename/ROB specs through multi-write lowering"
```

---

### Task 3: Synthesize without regfile_fix.py; delete it

**Files:**
- Modify: `synth/ooc_full.tcl`, `synth/ooc_backend.tcl` (remove any regfile_fix dependency in the flow — they don't call it directly, but the README/usage does)
- Delete: `synth/regfile_fix.py`

- [ ] **Step 1: Regenerate the full pipeline (phase now in config)**

Run: `~/sbt/bin/sbt "runMain m68k040.top.GenSynthVerilog"`
Expected: `Generated generated/M68kCoreSynth.v`, no SpinalError. Do NOT run `regfile_fix.py`.

- [ ] **Step 2: Synthesize directly**

Run: `vivado -mode batch -nojournal -log synth/vivado_full.log -source synth/ooc_full.tcl 2>&1 | grep -iE "Unsupported RAM|multi-driven|Synth Design complete|FULL_SYNTH_DONE|ERROR|CRITICAL" | head -20`
Expected: synthesis completes; **no "Unsupported RAM template"**, **no "multi-driven net"** critical warnings, 0 errors. Record the `FULL_SYNTH_DONE WNS` value.

- [ ] **Step 3: Confirm mapping**

Run: `grep -iE "Block RAM Tile|RAMB36|CLB LUTs|LUT as Memory|CLB Registers" synth/full_util.rpt | grep -E "^\|" | head`
Expected: I-cache data still ~16 RAMB36; the lowered RAT/Freelist/ROB banks appear as LUT-as-Memory (distributed RAM) — `LUT as Memory` > 0. Record LUT/FF/BRAM counts and the WNS; compare to the `regfile_fix.py` baseline (full pipeline post-synth WNS was +1.07 ns). Note any FMax delta in the commit message; a regression >0.3 ns warrants a comment (the XOR/LVT read network is deeper than the merged register-file form).

- [ ] **Step 4: Delete regfile_fix.py and clean references**

```bash
git rm synth/regfile_fix.py
```
Grep for any remaining references and remove them: `grep -rn "regfile_fix" synth/ docs/ README* 2>/dev/null`. Remove mentions in `synth/*.tcl` comments if present. (Do not edit the design/plan docs' historical references — only operational scripts.)

- [ ] **Step 5: Commit**

```bash
git add -A synth/
git commit -m "synth: drop regfile_fix.py; multi-write Mems lower natively via the phase

Full pipeline OOC-synthesizes with no Unsupported-RAM / multi-driven warnings;
RAT/Freelist/ROB lower to distributed-RAM XOR/LVT banks. Post-synth WNS <record> @250MHz."
```

---

## Self-Review

**1. Spec coverage:**
- §3 strategy (XOR <10b / LVT ≥10b, async branch) → Task 1 vendored phase. ✓
- §4 components: vendored phase (T1.1), `M68kSpinalConfig` (T1.2), GenVerilog wiring (T1.4), `M68kSim` (T2.1), remove regfile_fix (T3.4). ✓
- §5 verification: RF specs through phase (T2.2–T2.3), targeted lowering test (T2.4), full suite (T2.6), synth without regfile_fix + FMax compare (T3.2–T3.3). ✓
- §6 files all mapped across T1–T3. ✓

**2. Placeholder scan:** No TBD/TODO. Vendored code given in full. The one judgment point (RatTableSpec field names for the new test) is explicitly gated by "READ the spec first" with fallback instructions — not a placeholder, a necessary lookup. ✓

**3. Type consistency:** `MultiPortWritesSymplifier`, `RamAsyncMwXor`, `RamAsyncMwMux`, `M68kSpinalConfig.apply(targetDirectory)`, `M68kSim.apply()` names consistent across tasks. `M68kSim()` returns `SpinalSimConfig` so `.withVerilator`/`.compile` chain works. GenVerilog uses `M68kSpinalConfig(targetDirectory = "generated")` matching the apply signature. ✓
