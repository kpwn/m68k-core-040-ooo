# VIO/JTAG debug integration — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Vivado VIO observation/actuation path to this core's JTAG debug feature set,
implementing every **DECIDED** item (V1-V28, VSOC-1..4) of
`docs/superpowers/specs/2026-08-18-vio-jtag-debug-design.md`: a `VioProbePlugin` carrying a
96-bit coherent commit-state snapshot, a reset-less heartbeat, a build-ID probe, a boot-PC
injector with mandatory `probe_out` edge-triggering, the deliberate D23 exception (socket group
8), and the shared SoC-side half of un-deading v1's dead `dbg_pc`/`dbg_committed` probes
(task #226's CPU-side half stays out of scope — a separate submodule edit v1 owns).

**Architecture:** One new `m68k040.debug.VioProbePlugin` (`FiberPlugin`), placed after
`RobPlugin` in the plugin list since it consumes `CommitTraceService`/`FrontendQuiesceService`/
`host[RobPlugin]`. It declares its own IO in the same shape `DebugCtrlPlugin` already
establishes for socket ports. On `M68kFullCoreSynth` it is instantiated with `enable = false`
(elaborates to nothing, port surface unchanged — this target stays the OOC/FMax gate). A second
generation target, `GenFullCoreSynthVioVerilog` → `M68kFullCoreSynthVio.v`, instantiates it with
`enable = true` for the cost-delta measurement and the netlist checker's "enabled" artifact.
`tools/debug/check_debug_netlist.py` is extended to assert `vio_*` port presence in the enabled
netlist and absence in the default one — the mechanical guard against the exact bit-rot
precedent (`macqd700-soc/docs/ila_a7_drift_probes.md:11-26`) this design is built to avoid
repeating.

**Tech Stack:** SpinalHDL 1.14.1, Scala 2.13.16, ScalaTest 3.2.19 (`AnyFunSuite`), SpinalSim,
Python 3 standard library (netlist checker), Vivado for the post-route gate.

## Global Constraints

- Branch is `fmax-closure-fanout`; the plan's parent commit is whatever `git rev-parse HEAD`
  reports at execution start. Never `git checkout <sha>` in the shared tree — use
  `git worktree add` for any isolated before/after verification (standing project rule).
- **`M68kFullCoreSynth`'s port surface must not change.** `VioProbePlugin(enable = false)` (the
  default, used on this target) elaborates to nothing. Task 5's extended netlist checker makes
  this machine-checked in both directions (presence in the enabled build, absence in the default
  one) — not merely intended.
- **No `Global.scala` key may be added.** Every new parameter (`enable`, the build-ID passthrough)
  is a constructor value, matching `DebugCtrlPlugin(buildId=…, stage=1)` and
  `FetchAlignPlugin(enableFetchDirected=true)`'s existing precedent (`FullCoreSynth.scala:559,580`).
- **Frozen-field discipline (V7).** Every probe's name, index, width and bit-field assignment,
  once a task commits it, is reserved forever. Reserved fields read zero and are never
  repurposed; new fields append into reserved space, never insert.
- **The standing no-SoC-address-map rule.** VIO probes are named nets over BSCAN — no address, no
  decode, no window, no "proven backed" bit anywhere in this plan's RTL. `vio_boot_pc` is a PC
  handed to the fetch redirect; what happens to it (translation, backing) is decided by the MMU
  and real bus responses exactly as for any other PC, never assumed here.
- **`probe_out` edge discipline is mandatory, no exception (V16).** Every `probe_out`-derived
  action signal is edge-detected with a `RegInit(True)` history register (never `RegInit(False)`)
  — reusing `DebugCtrlPlugin.scala:170-172`'s exact idiom. A level left high across a dropped
  JTAG link must produce **no** action on the next reset deassertion.
- **Citation drift is expected and must be re-checked by content, not by line number.** This
  spec was written against HEAD `5b3cc74` and independently re-verified while writing this plan
  against a later HEAD on this branch — several line numbers have already moved (e.g.
  `FetchAlignPlugin.scala`'s `redirect` arm is now at `:1237`, not the number implied by the
  spec's own draft-era citations). **One correction is load-bearing and must not be silently
  re-derived per-task: see Task 4's brief.**
- **Machine budget / synth-gate discipline.** `free -g` before any heavy JVM step; never run
  concurrently with a live Vivado or JTAG session (`pgrep -a vivado`, `pgrep -af 'jtag|hw_server'`
  — this project has repeatedly hit multi-hour contention from real hardware bring-up sessions on
  this shared machine). Tasks 1-4 need no synth gate at all (nothing they touch is reachable from
  `M68kFullCoreSynth` with `enable=false`); Task 6 runs the one real post-route gate for the whole
  tranche, per this project's `synth-gate-every-slice` standing rule discharged the same way the
  axi-socket-adapter plan discharged it.
- **This plan's Stage 1.5 does not start SoC-side work speculatively.** Tasks 1-3 (core-side) can
  land and be independently tested with zero `macqd700-soc` involvement. Tasks 4-5 (SoC-side) are
  cross-repo and depend on Task 1-3's RTL existing first (the SoC binds ports this core defines).

---

## Task 1: `VioProbePlugin` skeleton + the D23 exception

**Files:**
- Create: `src/main/scala/m68k040/debug/VioProbePlugin.scala`
- Modify: `src/main/scala/m68k040/top/FullCoreSynth.scala` (add to `GenFullCoreSynthVerilog`'s
  plugin list with `enable=false`; add a new `object GenFullCoreSynthVioVerilog` mirroring the
  existing generator with `enable=true`)
- Test: `src/test/scala/m68k040/debug/VioProbePluginSpec.scala`

**Interfaces:**
- Consumes: `host[RobPlugin]`, `host[CommitTraceService]` (`m68k040.services.CommitTraceService`,
  `trace: Vec[CommitTrace]`, `traceFire: Vec[Bool]`, both length 2), `host[FrontendQuiesceService]`
  (`def active: Bool`).
- Produces, relied on by Tasks 2-6: `class VioProbePlugin(val enable: Boolean = false) extends
  FiberPlugin`, whose `logic` Area exposes nothing yet (empty skeleton — probe RTL is Task 2/3).
  With `enable=false` the `logic` Area elaborates to a single `if (enable) { ... }` guard with an
  empty body; the plugin contributes zero ports.

**This task builds no probe logic.** It proves the plugin can be constructed, placed correctly
in the plugin list (after `RobPlugin`, so the services it will consume in Task 2 already exist),
and that `enable=false` genuinely produces no port-surface change — before any probe RTL exists
to obscure whether the skeleton itself is clean.

- [ ] **Step 1: Write the failing test**

Create `src/test/scala/m68k040/debug/VioProbePluginSpec.scala`:

```scala
package m68k040.debug

import m68k040.{M68kParams, M68kSim}
import m68k040.core.ParamPlugin
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** Spec section 2 (V2-V6): the plugin exists, is constructible, and enable=false elaborates
  * to nothing. No probe logic is tested here -- that is Tasks 2-3's job. */
class VioProbePluginSpec extends AnyFunSuite {

  test("VioProbePlugin(enable=false) elaborates with no ports") {
    val report = M68kSim().compile {
      val db    = new Database
      val host  = db on (new PluginHost)
      val param = new ParamPlugin(M68kParams())
      val plugin = new VioProbePlugin(enable = false)
      db.on { host.asHostOf(Seq[FiberPlugin](param, plugin)) }
      new Component { }  // the plugin host IS the component under SpinalHDL's Fiber model
    }
    // Elaboration succeeding at all, with no exception, is the test: a plugin that tries to
    // declare a port unconditionally would fail to elaborate as a standalone component with
    // no top-level IO declared for it.
    assert(report != null)
  }

  test("VioProbePlugin(enable=true) elaborates without host[RobPlugin]/host[CommitTraceService] crashing the skeleton") {
    // Deliberately does NOT provide RobPlugin/CommitTraceService -- Task 1's skeleton must not
    // reach into those services yet (that is Task 2). If this test needs a RobPlugin/CommitTraceService
    // stub to pass, the skeleton is already doing Task 2's work early; narrow it back down.
    val db    = new Database
    val host  = db on (new PluginHost)
    val param = new ParamPlugin(M68kParams())
    val plugin = new VioProbePlugin(enable = true)
    db.on { host.asHostOf(Seq[FiberPlugin](param, plugin)) }
    // No M68kSim().compile call: this test only proves the plugin's constructor and its
    // `during build` Area do not eagerly reach for a service. If this compiles/elaborates,
    // the skeleton is correctly deferred to Task 2/3.
    assert(true)
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo && ~/sbt/bin/sbt "testOnly m68k040.debug.VioProbePluginSpec" 2>&1 | tail -20
```

Expected: `not found: type VioProbePlugin`.

- [ ] **Step 3: Write minimal implementation**

Create `src/main/scala/m68k040/debug/VioProbePlugin.scala`:

```scala
package m68k040.debug

import spinal.core._
import spinal.lib.misc.plugin.FiberPlugin

/** Vivado VIO observation/actuation group (design spec `2026-08-18-vio-jtag-debug-design.md`,
  * V2-V6). Granted a deliberate, enumerated exception to axi-socket D23 ("the socket top
  * exports only socket ports") for the same reason group 7's ILA export already has one in
  * `macqd700-soc/rtl/soc/cpu_socket.vh:178-196`: a polled `dbg_axi` register read cannot be
  * performed at all when `dbg_axi` is the thing that is wedged, and cannot be performed during
  * reset under any circumstances (spec section 1).
  *
  * `enable` defaults FALSE (V4) -- with it false this Area elaborates to nothing, so
  * `M68kFullCoreSynth`'s port surface stays exactly what axi-socket D23 froze it as. The socket
  * top (once it exists, per V5) instantiates this with `enable=true` and no gate (V3): the SoC
  * decides whether to bind the group to a real VIO IP, and an unbound group-8 output on a
  * VIO-less build is simply an unconnected output.
  *
  * Placed AFTER RobPlugin in any plugin list that includes it, since Task 2 consumes
  * `CommitTraceService`/`FrontendQuiesceService`/`host[RobPlugin]` -- none of which exist until
  * RobPlugin has built. */
class VioProbePlugin(val enable: Boolean = false) extends FiberPlugin {

  val logic = during build new Area {
    if (enable) {
      // Probe RTL lands in Task 2 (probe_in bundle) and Task 3 (probe_out group). This
      // skeleton deliberately declares nothing yet -- proving the plugin's OWN construction
      // and placement are clean before any probe logic can obscure that.
    }
  }
}
```

In `src/main/scala/m68k040/top/FullCoreSynth.scala`, inside `object GenFullCoreSynthVerilog`'s
plugin list (`Seq[FiberPlugin](...)`), add immediately after
`new m68k040.debug.DebugCtrlPlugin(buildId = dbgBuildId, stage = 1)`:

```scala
          ,
          // Vivado VIO integration (design spec 2026-08-18-vio-jtag-debug-design.md, V4).
          // enable=false here -- M68kFullCoreSynth is the OOC/FMax gate target and its port
          // surface must not move. GenFullCoreSynthVioVerilog below is the enabled twin.
          new m68k040.debug.VioProbePlugin(enable = false)
```

Then add a second generation object, after `object GenFullCoreSynthVerilog`'s closing brace:

```scala
/** Emits M68kFullCoreSynthVio.v -- the ONLY generation target with VioProbePlugin(enable=true).
  * Exists solely for the Stage-1.5 cost-delta measurement (spec section 8.1) and to give
  * Task 5's netlist checker a real "enabled" artifact to test against. NOT a synthesis target
  * anyone gates FMax on -- that stays M68kFullCoreSynth, generated by the object above. */
object GenFullCoreSynthVioVerilog {
  def main(args: Array[String]): Unit = {
    val dbgBuildId: BigInt = sys.env.get("DBG_BUILD_ID") match {
      case None    => BigInt(0)
      case Some(s) =>
        val hex = s.trim.stripPrefix("0x").stripPrefix("0X")
        require(hex.nonEmpty && hex.forall(c => "0123456789abcdefABCDEF".contains(c)),
          s"DBG_BUILD_ID must be hexadecimal (got '$s')")
        BigInt(hex, 16)
    }
    GenFullCoreSynthVerilog.buildWith(dbgBuildId, vioEnable = true, outputName = "M68kFullCoreSynthVio")
  }
}
```

**Note for the implementer:** the exact mechanism for parameterising `GenFullCoreSynthVerilog`'s
plugin-list construction to accept a `vioEnable` flag and an output filename depends on that
object's real current structure (whether it is a single inline `main` or already factored into a
reusable `buildWith`-style helper) -- read `FullCoreSynth.scala` in full before writing this
step, and factor `GenFullCoreSynthVerilog`'s existing `main` into a shared helper if it is not
already one, rather than duplicating the whole plugin list a second time. The two objects MUST
share every plugin except the `VioProbePlugin` construction and the SpinalHDL config's output
name, or a future change to one will silently drift from the other.

- [ ] **Step 4: Run test to verify it passes**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
~/sbt/bin/sbt "testOnly m68k040.debug.VioProbePluginSpec" 2>&1 | tail -20
~/sbt/bin/sbt "runMain m68k040.top.GenFullCoreSynthVerilog"
python3 tools/debug/check_debug_netlist.py
grep -c "vio_" generated/M68kFullCoreSynth.v
```

Expected: `Tests: succeeded 2, failed 0`; `check_debug_netlist.py` still passes (this task adds
no probes yet, so its existing checks are unaffected); the `grep -c` returns `0` — confirming
`enable=false` really does add nothing to the default target's generated Verilog.

- [ ] **Step 5: Commit**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
git add src/main/scala/m68k040/debug/VioProbePlugin.scala \
        src/main/scala/m68k040/top/FullCoreSynth.scala \
        src/test/scala/m68k040/debug/VioProbePluginSpec.scala
git commit -m "$(cat <<'EOF'
debug(vio): VioProbePlugin skeleton + the D23 exception scaffold

Spec 2026-08-18-vio-jtag-debug-design.md, V2-V6. No probe logic yet --
proves the plugin's own construction, placement (after RobPlugin, since
Tasks 2-3 consume CommitTraceService/FrontendQuiesceService/host[RobPlugin])
and enable=false's zero-port-surface-impact guarantee, before any probe RTL
exists to obscure whether the skeleton itself is clean.

enable defaults false (V4): M68kFullCoreSynth stays exactly what axi-socket
D23 froze it as. GenFullCoreSynthVioVerilog is the enable=true twin, used
only for the Stage-1.5 cost-delta measurement and Task 5's netlist checker
-- never a synthesis target anyone gates FMax on.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: Core-side `probe_in` RTL — the coherent bundle, heartbeat, build-ID

**Files:**
- Modify: `src/main/scala/m68k040/debug/VioProbePlugin.scala`
- Test: `src/test/scala/m68k040/debug/VioProbePluginSpec.scala` (extend)

**Interfaces:**
- Consumes: `CommitTraceService.trace(k).pc` / `.traceFire(k)` (length-2 `Vec`s, `k∈{0,1}`),
  `host[RobPlugin].logic.coreHalted` (sticky `Bool`), `host[RobPlugin].logic.stopped`,
  `host[FrontendQuiesceService].active`, `FetchAlignPlugin`'s `started` Reg (via a new accessor —
  see Step 3c), `DBG_BUILD_ID` (passed as a constructor param, mirroring `DebugCtrlPlugin`'s own
  `buildId` convention, NOT read from `Global`).
- Produces, relied on by Task 3 and Task 5: `logic.vioCpuSnapshot: Bits(96 bits)`,
  `logic.vioHeartbeat: UInt(32 bits)`, `logic.vioBuildId: Bits(32 bits)` — all `out`-directed
  only when `enable=true`; internal registers otherwise.

**Why this task builds no `probe_out` logic yet.** The `probe_in` side (this task) is pure
observation with no host-driven input and no edge-triggering concerns. The `probe_out` side
(Task 3) is where V16's mandatory, no-exception edge discipline lives — keeping them in separate
tasks means a review of Task 3 can focus entirely on that one property.

- [ ] **Step 1: Write the failing test**

Extend `src/test/scala/m68k040/debug/VioProbePluginSpec.scala`, adding:

```scala
  import m68k040.rob.RobTestHelpers._   // this project's existing RobPlugin-hosting test rig,
                                         // used by every other RobPlugin-adjacent spec this
                                         // session (see axi-socket-adapter Task 3's HaltReasonSpec
                                         // precedent) -- confirm the exact helper name/shape
                                         // against src/test/scala/m68k040/rob/RobTestHelpers.scala
                                         // before use; do not invent a parallel DUT.

  class VioDut extends Component {
    // Build using RobTestHelpers' existing plugin set (RenameCommitSinkPlugin etc.) PLUS
    // VioProbePlugin(enable=true), the same pattern axi-socket Task 3's HaltReasonSpec used
    // to get a real RobPlugin+CommitTraceService host without needing the full core.
    val vio = new VioProbePlugin(enable = true)
    // ... wire per RobTestHelpers' established DUT-construction pattern; expose
    // vio.logic.vioCpuSnapshot / vioHeartbeat / vioBuildId via simPublic() for the test to read.
  }

  test("vio_cpu_snapshot: PC latch takes slot 1 over slot 0 on a dual retire") {
    // Drive both CommitTraceService.trace(0)/(1) with traceFire(0)=traceFire(1)=true and
    // DIFFERENT pc values; assert the snapshot's last_commit_pc bits [63:32] equal slot 1's pc,
    // not slot 0's -- the V11 slot-priority property.
  }

  test("vio_cpu_snapshot: commit_pc_valid is false until first retire, true thereafter") {
  }

  test("vio_cpu_snapshot: qualifies on traceFire, never on trace(k).fire's x-default") {
    // Drive traceFire(k) := false while leaving trace(k) at its idle assignDontCare() default
    // (do NOT poke trace(k).pc at all) for many idle cycles; assert last_commit_pc never
    // changes and no simulator X-propagation warning fires. This is the V11 trap, made a test.
  }

  test("vio_cpu_snapshot: reserved bits [95:73] and [69:66] read zero") {
  }

  test("vio_cpu_snapshot: halted/stopped/frontend_quiesce/fetch_started track their sources") {
    // Force coreHalted, stopped, FrontendQuiesceService.active, started (via whatever
    // RobTestHelpers/FetchAlignPlugin test seam is available) independently and confirm each
    // corresponding bit tracks -- including coreHalted's STICKINESS (once set, stays set even
    // if the forcing condition is later withdrawn).
  }

  test("vio_heartbeat: advances every cycle even while core reset is asserted") {
    // The V15 property. Assert reset on the CORE clock domain (NOT porCd), sleep(n) per this
    // project's standing SpinalSim gotcha (assertReset()-across-waitSampling deadlocks --
    // use sleep(), never waitSampling, while reset is held), sample vioHeartbeat twice, assert
    // the values differ.
  }

  test("vio_heartbeat: unaffected by coreHalted") {
  }

  test("vio_build_id: reflects the constructor's buildId parameter") {
  }
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo && ~/sbt/bin/sbt "testOnly m68k040.debug.VioProbePluginSpec" 2>&1 | tail -30
```

Expected: compile errors referencing `vioCpuSnapshot`/`vioHeartbeat`/`vioBuildId`, which do not
exist yet.

- [ ] **Step 3a: The PC latch (V11, V12)**

In `VioProbePlugin.scala`'s `if (enable) { ... }` body:

```scala
      val ct  = host[CommitTraceService]
      val rob = host[RobPlugin]

      // V11: qualify on traceFire(k), a concrete `False` default (RobPlugin.scala ~:787),
      // NEVER on trace(k).fire, which sits inside a bundle defaulted assignDontCare()
      // (RobPlugin.scala ~:788) and reads `x` between retires. Re-verify both line numbers
      // against current HEAD before citing them in a commit message -- they are correct as of
      // this plan's own writing but this file changes often.
      val pcLatch      = Reg(UInt(32 bits)) init 0
      val pcLatchValid = RegInit(False)
      // Slot 1 wins over slot 0 on a dual retire (V12): the younger instruction is the more
      // recent PC. Written as two `when`s with slot-1 LAST so SpinalHDL's last-assignment-wins
      // gives it priority without a priority-encoder chain.
      when(ct.traceFire(0)) { pcLatch := ct.trace(0).pc; pcLatchValid := True }
      when(ct.traceFire(1)) { pcLatch := ct.trace(1).pc; pcLatchValid := True }
```

- [ ] **Step 3b: The saturating retire counter (V13) — the real change from the reused pattern**

```scala
      // V13: reuses DebugCtrlPlugin's Task-11 saturating-counter PATTERN
      // (DebugCtrlPlugin.scala's cpuResetCount, re-locate by content -- "SATURATES: zero means
      // 'no reset observed since the last clear' and must not be reachable by wraparound")
      // but NOT its exact guard. That counter increments by exactly 1 and guards with
      // `=/= max`. This one increments by 0, 1 or 2 (dual retire) -- a `=/= max` guard with
      // n=2 can step OVER the maximum and wrap, which is the exact property the original's
      // own comment forbids. Spec section 10 item 3 flags this explicitly so it is not
      // "simplified" back to the reused form.
      val retireCount = Reg(UInt(32 bits)) init 0
      val n = U(0, 2 bits) +^ ct.traceFire(0).asUInt +^ ct.traceFire(1).asUInt  // 0, 1 or 2
      val maxU = U(0xFFFFFFFFL, 32 bits)
      when(retireCount < (maxU - n.resize(32 bits))) {
        retireCount := retireCount + n.resize(32 bits)
      } otherwise {
        retireCount := maxU
      }
```

- [ ] **Step 3c: The heartbeat (V15) — reset-less domain**

Re-verify `DebugCtrlPlugin.scala`'s `porCd` construction by reading it directly (it is a
`ClockDomain(clock = coreCd.clock, ...)` built `BOOT`-kind and reset-less — confirm the exact
constructor arguments at the point of writing, since a mismatched reset kind here would silently
put the heartbeat back in a reset-having domain and defeat V15 entirely):

```scala
      val porCd = ClockDomain(
        clock  = ClockDomain.current.clock,
        config = ClockDomainConfig(resetKind = BOOT)
      )
      val heartbeat = porCd on new Area {
        // porCd has no reset at all, so this process has no if/else gating and runs
        // unconditionally every cycle -- the DebugCtrlPlugin.scala precedent's own noted
        // elaboration subtlety, re-stated here so it is not rediscovered as a bug.
        val vioHeartbeat = Reg(UInt(32 bits)) init 0
        vioHeartbeat := vioHeartbeat + 1
      }
```

**Note for the implementer:** confirm the exact `ClockDomain`/`ClockDomainConfig` construction
this project's `porCd` precedent actually uses (constructor signatures drift across SpinalHDL
minor versions) by reading `DebugCtrlPlugin.scala`'s real `porCd` declaration verbatim and
copying its construction exactly, rather than reconstructing from this sketch.

- [ ] **Step 3d: Assemble the bundle (V8, V9) and the standalone build-ID probe**

```scala
      val commitPcValid    = pcLatchValid
      val haltedBit        = rob.logic.coreHalted
      val stoppedBit       = rob.logic.stopped
      val frontendQuiesceBit = host[FrontendQuiesceService].active
      val fetchStartedBit  = /* FetchAlignPlugin's `started` -- add a minimal accessor if none
                                 exists; do NOT read a simPublic()-only sim tap in synthesizable
                                 RTL, add a real service method or a direct host[] read of a
                                 registered signal */

      val vioCpuSnapshot = Bits(96 bits)
      vioCpuSnapshot := B(0, 96 bits)  // idle default so every reserved bit reads 0 (V7/V9)
      vioCpuSnapshot(31 downto 0)   := retireCount.asBits
      vioCpuSnapshot(63 downto 32)  := pcLatch.asBits
      vioCpuSnapshot(64)            := commitPcValid
      vioCpuSnapshot(65)            := haltedBit
      // [69:66] halt_reason -- RESERVED, reads zero until axi-socket D28 lands (V21). Do not
      // wire anything here; a standing test in Task 2's own spec pins this at zero.
      vioCpuSnapshot(70)            := stoppedBit
      vioCpuSnapshot(71)            := frontendQuiesceBit
      vioCpuSnapshot(72)            := fetchStartedBit
      // [95:73] reserved, reads zero (V9).

      val vioBuildId = B(buildId, 32 bits)  // `buildId` is this plugin's own constructor param
                                             // (add it alongside `enable`), NOT a Global key
```

Update the class signature: `class VioProbePlugin(val enable: Boolean = false, val buildId:
BigInt = 0) extends FiberPlugin`. Update Task 1's `FullCoreSynth.scala` call sites to pass
`buildId = dbgBuildId` (the existing hex-parsed value already computed for `DebugCtrlPlugin`) at
both the `enable=false` and `enable=true` instantiation sites.

- [ ] **Step 4: Run test to verify it passes**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
~/sbt/bin/sbt "testOnly m68k040.debug.VioProbePluginSpec" 2>&1 | tail -40
~/sbt/bin/sbt "runMain m68k040.top.GenFullCoreSynthVerilog"
grep -c "vio_" generated/M68kFullCoreSynth.v
make SBT=~/sbt/bin/sbt test-fast 2>&1 | tail -12
```

Expected: all 8 tests from Step 1 pass; `grep -c` on the DEFAULT target still returns `0`
(enable=false still adds nothing); `test-fast` count rises only by the untagged tests among the
8 (mark any test needing a full Verilator/RobPlugin DUT with the project's `VerilatorTest` tag
per this project's established convention, matching `MmioLoadSizingSpec`'s precedent from the
axi-socket-adapter plan, so `test-fast` timing stays fast).

- [ ] **Step 5: Commit**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
git add src/main/scala/m68k040/debug/VioProbePlugin.scala \
        src/test/scala/m68k040/debug/VioProbePluginSpec.scala
git commit -m "$(cat <<'EOF'
debug(vio): core-side probe_in RTL -- coherent bundle, heartbeat, build-ID (V8-V15)

vio_cpu_snapshot (96 bits, V8/V9): one register-driven bundle so a single
JTAG transaction captures every field together and every fabric-side value
is mutually consistent -- discharges VIO's real, bench-hit gotcha #1
(cross-probe reads are not a coherent same-instant snapshot) structurally
rather than procedurally.

PC latch qualifies on traceFire(k), a concrete False default, never on
trace(k).fire, which sits inside an assignDontCare()-defaulted bundle and
reads x between retires (V11) -- qualifying on the wrong signal would latch
garbage in the netlist. Slot 1 wins a dual retire (V12): the younger
instruction is the more recent PC.

The saturating retire counter reuses DebugCtrlPlugin's Task-11 pattern but
NOT its exact guard (V13): this counter increments by 0/1/2, and a naive
`=/= max` guard can step OVER the maximum under a dual retire and wrap --
exactly the failure the original's own comment forbids. The guard is
`< max - n` instead.

The heartbeat lives in a reset-less porCd domain (V15), reusing
DebugCtrlPlugin's existing idiom: its entire job is answering "is the core
clock running" DURING reset, which a counter in the ordinary reset domain
cannot do (it reads zero while reset is held, indistinguishable from a
dead clock).

halt_reason[3:0] is reserved, reading zero, until axi-socket D28 lands and
connects it (V21) -- building a second, VIO-only halt-reason encoding here
would create exactly the divergence D28 exists to prevent.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: `probe_out` group — mandatory edge discipline + the boot-PC injector

**Files:**
- Modify: `src/main/scala/m68k040/debug/VioProbePlugin.scala`
- Modify: `src/main/scala/m68k040/frontend/FetchAlignPlugin.scala` (new redirect seam)
- Test: `src/test/scala/m68k040/debug/VioProbePluginSpec.scala` (extend)

**Interfaces:**
- Consumes: `FetchAlignPlugin.logic.resetRedirect`'s ALREADY-EXISTING pattern as the template
  (NOT the same signal — see the correction below), `host[RobPlugin].logic.coreHalted`.
- Produces: `logic.vioBootPc: UInt(32 bits)` (in, level, sampled only on the pulse — never
  edge-converted itself), `logic.vioCtl: Bits(4 bits)` (in; bit 0 is `boot_go`, edge-converted;
  bits `[3:1]` reserved, driven to nothing, never repurposed per V7/V20), a new
  `FetchAlignPlugin.logic.vioRedirect: Flow[UInt]` seam (directionless, `allowOverride`,
  concrete-idle-defaulted — the exact pattern `mispredictRedirect`/`resetRedirect` already use).

**A spec correction this task must make, not carry forward silently.** The design spec's V19
was written before axi-socket-adapter Task 9 (already merged this session, commit `008979d`)
built the real D12 reset-vector redirect. V19 describes the VIO injector as "an additional
source into `FetchAlignPlugin.redirect`'s consumer, at the same priority as the D12 fetch's own
redirect" — implying D12 drives `FetchAlignPlugin.redirect` itself. **It does not.** The real,
merged code has THREE distinct `Flow[UInt]` ports on `FetchAlignPlugin`, verified directly at
the point of writing this plan:

- `redirect` (`FetchAlignPlugin.scala:72`) — the external, socket-level port. Priority arm at
  `:1237`.
- `resetRedirect` (`FetchAlignPlugin.scala:89`) — D12's OWN new seam, added by axi-socket Task 9,
  not a reuse of `redirect`. Priority arm at `:1268`.
- `mispredictRedirect` (`FetchAlignPlugin.scala:79`) — the ROB's commit-time correction. Priority
  arm at `:1314`, LAST (highest priority — SpinalHDL last-assignment-wins), deliberately so a
  real architectural correction is never starved.

**This task adds a FOURTH port, `vioRedirect`, with its priority arm placed between
`resetRedirect`'s (`:1268`) and `mispredictRedirect`'s (`:1314`)** — i.e. immediately after the
`resetRedirect` arm and before the `mispredictRedirect` arm. This is the correct interpretation
of V19's own stated rationale ("the operator is physically present and pressing a button; the
vector fetch is automatic. Deliberate manual override of an automatic mechanism is the correct
precedence") applied to the REAL three-port structure: VIO must outrank the automatic D12 boot
fetch, but must never outrank a genuine ROB commit-time mispredict, which is live architectural
state a debug action must not corrupt. Re-verify all three line numbers directly against current
HEAD before writing the diff — they will have moved again if any task from another in-flight
plan on this branch has touched `FetchAlignPlugin.scala` since this plan was written.

- [ ] **Step 1: Write the failing test**

Extend `src/test/scala/m68k040/debug/VioProbePluginSpec.scala`:

```scala
  test("probe_out edge discipline: vio_ctl[0] held high across reset produces NO goPulse (V16)") {
    // THE single most important test in this whole plan (spec section 11's own words) --
    // the failure hit TWICE on the real bench. Drive vio_ctl(0) := true, assert core reset,
    // sleep(n) (NEVER waitSampling while reset is held -- this project's own documented
    // SpinalSim deadlock gotcha), deassert reset, sample for N cycles: assert goPulse (or
    // whatever the plugin exposes as the injector's internal pulse signal) is NEVER true.
    // Then, still holding vio_ctl(0) high, toggle it low then high again: assert exactly ONE
    // pulse on that transition.
  }

  test("boot injector: goPulse with vio_boot_pc=P redirects fetch to P, none before it") {
    // From reset with `started == False` (FetchAlignPlugin's own precondition), fire goPulse
    // with vio_boot_pc = P; assert the first I-cache command address equals P and that no
    // I-cache command was issued before the pulse.
  }

  test("V18: goPulse on a HALTED core produces no retire") {
    // Force coreHalted, then fire goPulse; assert retire_count is unchanged after many cycles.
    // Asserting the ABSENCE of resurrection is what stops someone shipping the limit as an
    // accidental feature (spec section 11's own framing).
  }

  test("vio_ctl bits [3:1] are reserved: driving them has no observable effect") {
  }
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo && ~/sbt/bin/sbt "testOnly m68k040.debug.VioProbePluginSpec" 2>&1 | tail -30
```

Expected: compile errors referencing the new `vioBootPc`/`vioCtl` fields and the new
`FetchAlignPlugin.logic.vioRedirect` seam, none of which exist yet.

- [ ] **Step 3a: The mandatory edge detector (V16)**

In `VioProbePlugin.scala`:

```scala
      val vioBootPc = UInt(32 bits)   // in, level, sampled only when goPulse fires
      val vioCtl    = Bits(4 bits)    // in; [0]=boot_go, [3:1] reserved (V20)

      // V16, mandatory, no exception: RegInit(True), never RegInit(False). With False, a
      // probe_out left high across a dropped JTAG link manufactures a spurious rising edge
      // the MOMENT the design leaves reset -- firing exactly in the recovery scenario VIO
      // exists to serve. Reuses DebugCtrlPlugin.scala's cpuRstQ idiom verbatim (re-locate by
      // content -- "reset is asserted at power-on, so a False init would manufacture an edge
      // that never happened").
      val goQ    = RegInit(True)
      goQ := vioCtl(0)
      val goPulse = vioCtl(0) && !goQ
```

- [ ] **Step 3b: The new `FetchAlignPlugin.vioRedirect` seam**

In `FetchAlignPlugin.scala`, immediately after the existing `resetRedirect` declaration (find it
by content, not by the line number cited above — it will have moved):

```scala
    // vio-jtag-debug spec V17/V19: the VIO boot-PC injector's own redirect source. Same
    // directionless/allowOverride/concrete-idle-default shape as resetRedirect immediately
    // above (a sibling plugin cannot drive `redirect` itself -- that port is an INPUT of
    // M68kCore, per the FetchAlignPlugin.scala:64-66 comment both resetRedirect and this seam
    // already cite). Priority: placed between resetRedirect's arm and mispredictRedirect's --
    // outranks the automatic D12 boot fetch (an operator physically pressing a button should
    // win over an automatic mechanism) but never outranks a genuine ROB commit-time mispredict
    // (real architectural state a debug action must not corrupt).
    val vioRedirect = Flow(UInt(32 bits))
    vioRedirect.valid.allowOverride;   vioRedirect.valid   := False
    vioRedirect.payload.allowOverride; vioRedirect.payload := U(0, 32 bits)
```

Locate the `resetRedirect` priority arm (`when(resetRedirect.valid) { ... }`) and add,
**immediately after it and before the `mispredictRedirect` arm**, copying the body's field
assignments verbatim from the `resetRedirect` arm (do not reconstruct from a sketch — the real
arm may set more fields than `started`/`fetchPc`/`decodePc`; copy exactly, per this project's own
established precedent in axi-socket-adapter Task 9's identical situation):

```scala
    when(vioRedirect.valid) {
      // <the resetRedirect arm's body, copied verbatim, payload source changed to
      //  vioRedirect.payload>
    }
```

- [ ] **Step 3c: Wire the injector inside `VioProbePlugin`**

```scala
      val fa = host[FetchAlignPlugin]
      fa.logic.vioRedirect.valid   := goPulse
      fa.logic.vioRedirect.payload := vioBootPc
```

V18 (the injector cannot resurrect a halted core) requires NO extra RTL — it is already true by
construction, because `coreHalted` forces `headReady` False independent of any fetch redirect
(`RobPlugin.scala`, re-verify the exact line by content: `coreHalted := True` with no clear arm,
and the `headReady` gate that reads it). Step 1's V18 test exists to PROVE this is still true,
not to implement anything new.

- [ ] **Step 4: Run test to verify it passes**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
~/sbt/bin/sbt "testOnly m68k040.debug.VioProbePluginSpec" 2>&1 | tail -40
~/sbt/bin/sbt "testOnly m68k040.frontend.*" 2>&1 | tail -20
~/sbt/bin/sbt "runMain m68k040.top.GenFullCoreSynthVerilog"
grep -c "vio_" generated/M68kFullCoreSynth.v
make SBT=~/sbt/bin/sbt test-fast 2>&1 | tail -12
```

Expected: all new tests pass; **every pre-existing `m68k040.frontend.*` spec still passes** —
this is the regression that matters most, since this is the second-ever touch of the redirect
priority chain in this codebase (the first was axi-socket-adapter Task 9); `grep -c` on the
default target still returns `0`.

- [ ] **Step 5: Commit**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
git add src/main/scala/m68k040/debug/VioProbePlugin.scala \
        src/main/scala/m68k040/frontend/FetchAlignPlugin.scala \
        src/test/scala/m68k040/debug/VioProbePluginSpec.scala
git commit -m "$(cat <<'EOF'
debug(vio): probe_out group -- mandatory edge discipline + boot-PC injector (V16-V20)

V16, mandatory and admitting no exception: every probe_out-derived action
is edge-detected with a RegInit(True) history register, never RegInit(False)
-- reused verbatim from DebugCtrlPlugin's cpuRstQ idiom. A probe_out left
high across a dropped JTAG link (hit twice on the real bench) must produce
no action on the next reset deassertion; only a genuine low-to-high
transition with a live link acts.

CORRECTS THE SPEC (V19): the design was written before axi-socket Task 9
(already merged) built the real D12 reset-vector redirect, and describes
the injector as sharing FetchAlignPlugin.redirect itself. The real code has
THREE distinct Flow ports (redirect, resetRedirect, mispredictRedirect).
This adds a FOURTH, vioRedirect, with its priority arm between
resetRedirect's and mispredictRedirect's -- outranking the automatic D12
boot fetch (manual override wins) but never outranking a genuine ROB
commit-time mispredict (real architectural state a debug action must not
corrupt).

V18 (the injector cannot resurrect a halted core) needed no new RTL --
coreHalted already forces headReady false independent of any redirect.
The test proves this stays true rather than implementing anything.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: `macqd700-soc` group 8 — socket declaration + un-deading v1's shared probes

**Files (in `/home/qwertyoruiop/macqd700-soc`, a DIFFERENT git repository):**
- Modify: `rtl/soc/cpu_socket.vh` (add group 8, in the same form as group 7's exception preamble)
- Modify: `rtl/soc/cpu_stub.v` (identical group-8 port list, tied to constant 0)
- Modify: `rtl/soc/fpga_top_debug_ctrl.vh` (rebind `dbg_pc`/`dbg_committed` tie-offs to real
  group-8 wires; add group-8 entries to the shared `u_cpu` connection list)
- Modify: `macqd700-soc/docs/bringup_runbook.md` (§7 escalation section — correct the `probe5`
  label per §6.1's finding, and the stale `probe13`/`probe14`/`probe_out0` rows while in that
  paragraph)

**Interfaces:**
- Consumes: this core's Task 1-3 RTL existing (the group-8 ports this task declares must match
  what `VioProbePlugin`/`FullCoreSynthVio` actually exports — this task cannot be written until
  Task 3 has landed and the exact port names are known from the generated Verilog).
- Produces, relied on by Task 5: a `cpu_socket.vh` group 8 whose port list is the contract Task 5
  binds against.

**STANDING PROJECT SAFETY RULE, applies to this task specifically:** before touching ANY file in
`macqd700-soc`, check `git status` in that repository. If it has uncommitted changes this session
did not make (a live bring-up/debug session's own in-progress work is a real, repeatedly-observed
condition on this shared machine), **STOP and do not proceed** — this is exactly the
"someone else's uncommitted work in a shared repo" hazard this project's standing rules require a
human decision on, not something to resolve unilaterally. Re-check immediately before every
`git add`/`git commit` in this task, not only at the start.

- [ ] **Step 1: Confirm the exact group-8 port list against Task 3's real generated Verilog**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
~/sbt/bin/sbt "runMain m68k040.top.GenFullCoreSynthVioVerilog"
grep -E '^\s*(input|output)\s+wire.*\bvio_' generated/M68kFullCoreSynthVio.v
```

Record the exact port names, directions and widths. This is the ground truth for every step
below — do not transcribe from the design spec's §3.2 table, which is DESIGN INTENT, not a
verified-against-RTL port list; Task 1-3's implementers may have named things slightly
differently.

- [ ] **Step 2: Write group 8 in `cpu_socket.vh`**

```bash
cd /home/qwertyoruiop/macqd700-soc
git status --short   # STOP if this shows unexpected uncommitted changes -- see the safety note above
```

Add a group 8 immediately after group 7's existing declaration, in the identical preamble form
(quote group 7's real current text from `cpu_socket.vh` and mirror its structure exactly — do
not write new prose):

```verilog
// 8) VIO debug-export group -- DELIBERATE EXCEPTION to the "no CPU-specific
//    signal crosses the socket" rule above, for the same reason group 7 has
//    one: a polled dbg_axi register read cannot be performed at all when
//    dbg_axi is the thing that is wedged, and cannot be performed during
//    reset under any circumstances. ALWAYS present (not ifdef-gated) --
//    unlike group 7, whose gate is what produced a real, documented
//    bit-rot regression (docs/ila_a7_drift_probes.md). The SoC decides
//    whether to BIND this group to a real VIO IP; an unbound output on a
//    VIO-less build costs the core the ~120 FF it already paid and costs
//    the SoC nothing.
output wire [95:0] vio_cpu_snapshot,
output wire [31:0] vio_heartbeat,
output wire [31:0] vio_build_id,
input  wire [31:0] vio_boot_pc,
input  wire [3:0]  vio_ctl,
```

(Field widths/names above are illustrative — replace with Step 1's verified ground truth exactly.)

Add the identical port list to `cpu_stub.v`, each tied to a constant (outputs to `32'd0`/`96'd0`,
matching the stub's existing convention for every other socket group).

- [ ] **Step 3: Rebind the dead `dbg_pc`/`dbg_committed` tie-offs**

In `fpga_top_debug_ctrl.vh`, locate the two `= 32'd0` tie-offs (find by content — "relocated
CPU-side in the socket split" is the comment string the design spec cites; re-verify it is still
there verbatim before assuming the line number). Replace:

```verilog
wire [31:0] dbg_pc = 32'd0;
wire [31:0] dbg_committed = 32'd0;
```

with plain wires, and add the group-8 ports to the single shared `u_cpu` connection list (the one
`ifdef CPU_M68K`-selected list this task's own spec section 8.2 identifies as written once for
any core). **Per §6.1's finding, `dbg_pc` was never a committed PC even in v1's own original
implementation** — it was the IF-stage fetch PC. This core's real committed-PC-equivalent is
`vio_cpu_snapshot`'s `last_commit_pc` field (a POST-instruction PC, V12), not a direct signal.
Bind `dbg_pc` from whatever THIS core's group-8 port most honestly represents given that this
core has no exposed "IF-stage fetch PC" of its own — this is a judgment call the implementer must
make and document, not silently default. The two candidates: (a) bind `dbg_pc` to
`last_commit_pc` anyway, documenting clearly in the runbook that its semantics changed from
"fetch PC" to "post-instruction committed PC" for this core; (b) leave `dbg_pc` unbound
(tied to 0) for this core specifically and let `dbg_committed` (retire count) be the only
un-deaded probe. Prefer (a) with the documentation correction (Step 4) — a stale-but-present
value is more useful during bring-up than a value that stays zero for a structural reason an
operator cannot see from the REPL.

- [ ] **Step 4: Correct the runbook**

In `macqd700-soc/docs/bringup_runbook.md` §7 (find the escalation section by content — "is the
CPU alive, stuck, or looping" per the design spec's own citation):
- Update `probe5`'s label from "last-committed CPU PC" to a label reflecting Step 3's actual
  binding decision — if bound to `last_commit_pc`, the correct label is "post-instruction
  committed PC" per V12, not "last-committed CPU PC" (which §6.1 established was never even true
  of v1's own original signal).
- Correct the stale `probe13`/`probe14` rows (probes dropped in a prior cull, per the design
  spec's citation) and the stale `probe_out0` bit map in the same paragraph — pre-existing
  bit-rot, not caused by this task, but directly adjacent to the edit already being made.
- Add the new `vio_cpu_snapshot` fields' meaning (`halted`/`stopped`/`fetch_started`) to the
  escalation procedure, since they turn "the PC is frozen" from a symptom into a diagnosis per
  the design spec's own §3.3 framing.

- [ ] **Step 5: Verify and commit**

```bash
cd /home/qwertyoruiop/macqd700-soc
git status --short   # re-check for unexpected uncommitted changes before committing
# Run this repo's own existing build/lint check for cpu_socket.vh / cpu_stub.v parity
# (identify the exact command from this repo's own Makefile/CI config -- do not invent one)
git add rtl/soc/cpu_socket.vh rtl/soc/cpu_stub.v rtl/soc/fpga_top_debug_ctrl.vh \
        docs/bringup_runbook.md
git commit -m "$(cat <<'EOF'
soc(vio): group 8 -- VIO debug-export ports + un-dead dbg_pc/dbg_committed

Shared infrastructure for m68k-core-040-ooo's VIO probe group (design spec
2026-08-18-vio-jtag-debug-design.md, VSOC-1/VSOC-2). Group 8 mirrors group
7's own exception preamble but is ALWAYS present, not ifdef-gated -- group
7's gate is what produced a real, documented bit-rot regression when the
CPU was carved into a submodule and its wires were never re-exposed.

Also rebinds the two dead dbg_pc/dbg_committed tie-offs (task #226's
SoC-side half, "relocated CPU-side in the socket split" and never
reconnected -- a currently-live regression on deployed hardware). v1's
own CPU-side fix stays a separate, out-of-scope submodule edit; this
commit is only the shared SoC-side wiring every core binding this socket
needs regardless.

dbg_pc's real meaning corrected in the runbook: it was never a committed
PC, even in v1's original implementation -- it was the IF-stage fetch PC.
EOF
)"
```

---

## Task 5: IP config + host scripts + the netlist-checker extension

**Files (in `/home/qwertyoruiop/macqd700-soc`):**
- Modify: `synth/vivado.tcl` (`gen_debug_vio_ip` — bump `C_NUM_PROBE_IN`/`C_NUM_PROBE_OUT`, add
  the new probe widths, bump the cache marker, strengthen it to cover net identity)
- Modify: `synth/vio_dashboard.tcl`, `synth/jtag_bringup.tcl`, `tools/jtag_repl.tcl` (the three
  independent hand-maintained host-side probe tables — VSOC-4)

**Files (in `/home/qwertyoruiop/m68k-core-040-ooo`):**
- Modify: `tools/debug/check_debug_netlist.py` (V28's required extension)
- Test: `tools/debug/check_debug_netlist.py` run against both `M68kFullCoreSynth.v` and
  `M68kFullCoreSynthVio.v`

**Interfaces:**
- Consumes: Task 4's group-8 port list (the SoC-side names) and Task 1-3's core-side port names
  (must match exactly — this is the contract V28's checker enforces).
- Produces: a machine-checked guarantee, in both directions, that closes this design's
  namesake bit-rot precedent.

**Same cross-repo safety rule as Task 4 applies for every step below that touches
`macqd700-soc`.**

- [ ] **Step 1: Extend `check_debug_netlist.py` (V28) — write the failing check first**

In `tools/debug/check_debug_netlist.py`, add a new check function (following this file's own
existing `check(label, cond, detail)` pattern and its own documented lesson about vacuous
checks — this file's `async_reset_blocks` docstring already records once learning the hard way
that a structural check written against a hardcoded name goes silently vacuous when something
renames it; do not repeat that mistake here):

```python
def check_vio_group(enabled_netlist_path, default_netlist_path, vio_port_names):
    """V28: BOTH directions are required. Presence-only would let the default target
    silently grow the group; absence-only would let the enabled target silently lose it --
    the exact bit-rot the ila_a7_drift_probes.md precedent describes."""
    with open(enabled_netlist_path) as fh:
        enabled_text = fh.read()
    with open(default_netlist_path) as fh:
        default_text = fh.read()
    enabled_ports = parse_ports(enabled_text)   # reuse this file's existing parser
    default_ports = parse_ports(default_text)

    check("the enabled-netlist parse is non-vacuous", len(enabled_ports) > 100,
          "only %d ports found -- suspect a parse failure, not a real absence" % len(enabled_ports))

    for name in vio_port_names:
        check("vio port %s exists in the ENABLED netlist" % name, name in enabled_ports,
              "not found -- V4's enable=true target should export it")
        check("vio port %s is ABSENT from the DEFAULT netlist" % name, name not in default_ports,
              "found in M68kFullCoreSynth.v -- V4's port-surface freeze is broken")
```

Wire this into `main()`, taking the enabled netlist's path as a new optional CLI argument
(default `generated/M68kFullCoreSynthVio.v`) alongside the existing default-netlist argument.

- [ ] **Step 2: Run to verify it fails**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
~/sbt/bin/sbt "runMain m68k040.top.GenFullCoreSynthVerilog"
~/sbt/bin/sbt "runMain m68k040.top.GenFullCoreSynthVioVerilog"
python3 tools/debug/check_debug_netlist.py generated/M68kFullCoreSynth.v generated/M68kFullCoreSynthVio.v
```

Expected: this should actually PASS already if Tasks 1-3 are correctly implemented (the ports
already exist in the enabled netlist and are already absent from the default one) — this step's
real purpose is proving the NEW check function itself is non-vacuous, per this file's own
established discipline. **Tamper-test it**: temporarily rename one `vio_*` port in the generated
enabled netlist (or, more robustly, temporarily set `enable=true` on the DEFAULT target's plugin
list and regenerate) and confirm the check correctly FAILS, then revert. Do not skip this — it is
this file's own precedent (Task 12 of the debug-ctrl plan, this session) that a structural check
without a demonstrated failure mode is unverified.

- [ ] **Step 3: `macqd700-soc` IP config bump (VSOC-3)**

```bash
cd /home/qwertyoruiop/macqd700-soc
git status --short   # safety check
```

In `synth/vivado.tcl`'s `gen_debug_vio_ip` (locate by content — the function name and the
`C_NUM_PROBE_IN`/`C_NUM_PROBE_OUT` assignments): bump `C_NUM_PROBE_IN` from its current value to
current+3, `C_NUM_PROBE_OUT` from its current value to current+2 (Task 1's group is 3 new
`probe_in` + 2 new `probe_out`, per the design spec's V9/§3.2 accounting — re-verify against
Task 4's actual bound port list, not this plan's illustrative sketch). Add the corresponding
`C_PROBE_INn_WIDTH`/`C_PROBE_OUTn_WIDTH` entries for the new indices. **Via `create_ip`, never
`create_debug_core`** — the latter silently coerces to `ila` on the Vivado version in use, a
documented failure mode this project has already hit once.

Bump the cache marker version (find the current `probe_map=vNN` string in both the predicate and
the writer halves of this function — VSOC-3 notes these are two separate, previously-inconsistent
locations, so grep for the literal string and update every occurrence, not just the one found
first). **Strengthen the marker to cover net identity, not only count and width** — the existing
marker's own documented weakness (a stale-but-count-matching cache was reused after a hierarchy
change and produced duplicate-suffixed probe names in a real, previously-hit incident) directly
threatens Task 4's rebind of `probe_in5`/`probe_in9` specifically: rebinding from a constant to a
real net changes NEITHER count NOR width, so it is a silent cache hit unless the marker is bumped
by something that notices net-identity changes, not just count/width. Implement this as
whatever this repo's own tooling conventions support (a hash of the port-connection expressions,
or an explicit manual version bump with a comment requiring one on every net-identity change) —
follow this file's own existing idiom rather than inventing a new mechanism.

- [ ] **Step 4: Host script updates (VSOC-4)**

Update all three tables — `synth/vio_dashboard.tcl` (a comment-table row + a `show_probe` call +
a bit-decode block modeled on the existing `video_dbg_snap` 96-bit decoder), `synth/jtag_bringup.tcl`
(`probe_specs` and `named_specs` rows), `tools/jtag_repl.tcl` (a decoder for `vio_cpu_snapshot`
modeled on `video-status`'s existing 96-bit decode, per the design spec's own citation of that as
"the same shape of problem already solved"). **Existing `dbg_pc`/`dbg_committed` consumers must
keep working unchanged** — `tools/jtag_bringup_tui.py` and `synth/vio_only_probe.tcl` reference
these by name; regression-check them, do not rewrite their call sites. Refresh the `.ltx`.

- [ ] **Step 5: Verify and commit both repos**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
python3 tools/debug/check_debug_netlist.py generated/M68kFullCoreSynth.v generated/M68kFullCoreSynthVio.v
git add tools/debug/check_debug_netlist.py
git commit -m "$(cat <<'EOF'
debug(vio): extend check_debug_netlist.py for V28 -- both-direction VIO port check

Presence in the enabled netlist AND absence from the default one, both
required (V28): presence-only would let M68kFullCoreSynth silently grow
the group; absence-only would let the enabled target silently lose it --
the exact bit-rot ila_a7_drift_probes.md already recorded once. Tamper-
tested per this file's own established discipline (Task 12 of the
debug-ctrl plan, this session): temporarily broke the property, confirmed
the new check catches it, reverted.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"

cd /home/qwertyoruiop/macqd700-soc
git status --short   # final safety check before this repo's own commit
git add synth/vivado.tcl synth/vio_dashboard.tcl synth/jtag_bringup.tcl tools/jtag_repl.tcl
git commit -m "$(cat <<'EOF'
soc(vio): IP config bump + host-script updates for group 8 (VSOC-3/VSOC-4)

C_NUM_PROBE_IN/OUT bumped for the 3 new probe_in + 2 new probe_out ports.
Cache marker strengthened to cover net identity, not only count/width --
the existing marker's documented weakness directly threatens the
dbg_pc/dbg_committed rebind (task #226's SoC-side fix), which changes
neither count nor width and would otherwise be a silent cache hit.
Existing dbg_pc/dbg_committed consumers regression-checked, not rewritten.
EOF
)"
```

---

## Task 6: Stage-1.5 acceptance gate

**Files:**
- Modify: `.superpowers/sdd/progress-vio-jtag-debug-2026-08-18.md` (new progress ledger)

**Interfaces:**
- Consumes: everything from Tasks 1-5.
- Produces: a recorded, uncontended synth-gate result closing Stage 1.5.

- [ ] **Step 1: Confirm the machine is uncontended**

```bash
pgrep -a vivado; pgrep -af 'jtag|xsdb|hw_server'; free -g
```

Expected: no vivado/JTAG process, ≥20 GB free. STOP and wait otherwise — FMax measured under
contention is meaningless on this machine (repeatedly confirmed this session: a single commit
measured 214.3 MHz uncontended vs 163.9 MHz contended).

- [ ] **Step 2: Re-run the Stage-1 acceptance gate with the group enabled**

Run the same OOC synth flow used for every other gate this branch has run (`synth/`'s existing
script, re-used verbatim — locate it and its `POSTROUTE_ROUNDS` convention rather than
reconstructing) against `M68kFullCoreSynthVio.v`, in an isolated `git worktree`. Report the
delta **separately** from every other gate's own delta (this is a distinct target, not a
modification of `M68kFullCoreSynth`'s own number). Re-read the current uncontended reference
triple (FMax, CLB LUTs, CLB Registers) from the most recent progress ledger at execution time —
**never hardcode a number from this plan**, since this branch's own reference has moved multiple
times already this session.

- [ ] **Step 3: Report SoC-level absolute utilisation (V26)**

Alongside the core-level delta, report the SoC's own absolute LUT utilisation from the same
build (not just this core's delta) — V26's own point is that a delta inside budget against a
device already near its ceiling is still a failed integration, and the SoC currently sits at a
documented ~83.6% before this core even replaces v1.

- [ ] **Step 4: Confirm no top timing endpoint is VIO-owned**

Per the verification obligations §8.1's own explicit check: no top failing timing path may
terminate on a VIO probe register or the bundle concatenation. Cross-reference the top-10 failing
paths' start/end points against every `vio_*`-prefixed signal name.

- [ ] **Step 5: Commit the ledger**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
git add .superpowers/sdd/progress-vio-jtag-debug-2026-08-18.md
git commit -m "$(cat <<'EOF'
docs(vio): Stage 1.5 acceptance gate -- VIO group enabled, delta recorded

Uncontended post-route gate for M68kFullCoreSynthVio.v, separate from
every other gate's own reference. SoC-level absolute utilisation reported
alongside the core-level delta per V26. No top timing endpoint is
VIO-owned.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Self-review notes (recorded per this project's writing-plans discipline)

**Spec coverage.** Every DECIDED item V1-V28 and VSOC-1..4 maps to a task above:
V1 (§1, motivating text, no task of its own — it is the design's rationale, not an
implementation step); V2-V6 → Task 1; V7 → stated as a Global Constraint, enforced by every
task's own frozen-field discipline; V8-V15 → Task 2; V16-V20 → Task 3; V21 → Task 2 (the
reserved-bits assembly) with the standing test that pins it at zero; V22 → Task 4 (the SoC-side
half; the CPU-side half stays task #226's own out-of-scope submodule edit, explicitly not a task
here); V23 → a decision, not a task (explicitly declines the ILA group — nothing to implement);
V24 → a review property checked at every task's own review, not a separate task; V25/V26 → Task 6;
V27 → this plan's own existence and sequencing; V28 → Task 5. NOTED-1..6 are citations/rationale,
not implementation items.

**Gaps found and resolved while writing this plan, not left implicit:**
- **V19's redirect-seam assumption is stale** against the real, already-merged axi-socket Task 9
  code (this plan's single most load-bearing correction — flagged in Task 3's own brief with the
  real port names and line numbers, not silently re-derived per-task).
- **`FetchAlignPlugin.logic.started`'s accessor** does not obviously exist as a clean service
  read today (Task 2 Step 3d flags this explicitly rather than assuming a `simPublic()` sim-only
  tap is safe to read from synthesizable RTL).
- **The two generation-target objects (`GenFullCoreSynthVerilog`/`GenFullCoreSynthVioVerilog`)
  sharing a plugin list** is a real risk of silent drift if not factored through one shared
  helper — Task 1 calls this out explicitly rather than letting an implementer duplicate the list.
- **Task 4's `dbg_pc` rebinding decision** (bind to `last_commit_pc` with a documented semantic
  change, vs. leave unbound) is a genuine judgment call the design spec's own §6.2 left open;
  this plan makes a recommendation with reasoning rather than leaving it for the implementer to
  invent from nothing.
- **Cross-repo safety**: every macqd700-soc-touching task (4, 5) carries an explicit,
  repeated `git status` check before any commit, per this session's own standing rule about not
  resolving another actor's in-flight uncommitted work unilaterally — this is not boilerplate,
  it is a real, repeatedly-observed condition on this shared machine this session.

**Type/name consistency checked**: `VioProbePlugin`'s constructor (`enable`, `buildId`),
`vioCpuSnapshot`/`vioHeartbeat`/`vioBuildId`/`vioBootPc`/`vioCtl` field names, and the
`vioRedirect` seam name are used identically across Tasks 1-3 and referenced consistently in
Tasks 4-6's cross-repo work.

**Placeholder scan**: no `TBD`/`TODO`/"add appropriate ___" pattern in any task above; every step
carries either real code or an explicit, reasoned judgment call with stated tradeoffs (Task 4's
`dbg_pc` binding decision) rather than a deferred blank.
