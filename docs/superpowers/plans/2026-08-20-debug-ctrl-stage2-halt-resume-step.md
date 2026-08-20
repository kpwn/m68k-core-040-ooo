# Debug/Control AXI Slave — Stage 2: Precise Manual Halt/Resume/Step Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the `dbg_axi` JTAG debug interface actually functional on cpu040: real manual halt/resume, single-step, halt-after (macro-count target), and a live/last committed PC readback — the minimum needed for a host (`tools/jtag_repl.tcl`'s `halt-status`/`pc`/`sweep`/manual-halt commands) to tell a genuine CPU hang from a live core, and to stop it precisely at a known point. Gate the whole feature behind a real, mirror-of-`VioProbePlugin` `enable` flag so it can be compiled out of area/FMax-reference builds.

**Architecture:** `RobPlugin` already owns two halt-shaped mechanisms (`stopped`/STOP and `coreHalted`/fatal) and a registered flush/redirect pulse (`doFlushReg`/`flushPcReg`, `RedirectService`) that every speculative structure (IQ, RAT, frontend, D/I-TLB, store queue) already reacts to via `BackendWiringPlugin`. Stage 2 adds a third halt kind — debug-requested — that reuses both: a new `DebugCommitService` trait (mirroring the existing `FrontendQuiesceService` setup-allocated-wire pattern to dodge the same Icache→Rob→Rename→Decode→FetchAlign→Icache Fiber cycle) exposes live/last PC, macro count, and halt state from `RobPlugin`; `DebugCtrlPlugin` drives a new `debugStopRequest`/`debugStepRequest`/`haltAfterTarget` sibling-wire group into `RobPlugin`, wired centrally in `BackendWiringPlugin` exactly like every other cross-plugin signal in this codebase. Macro-boundary detection (the spec's `macroFirst`/`macroLast` pair) reuses the ALREADY-EXISTING `RobPayload.first` (`macroFirst`) for the retiring entry and adds the one new piece: `RobPayload.first` of the NEXT entry (`p1`, already read every cycle) tells the ROB whether the entry about to retire is the last uop of its macro — no new per-uop field needs threading through `MicroOpAssembler.scala`'s ~20 crack sites.

**Tech Stack:** SpinalHDL 1.14.1, Scala 2.13.16, ScalaTest 3.2.19, SpinalSim/Verilator, Vivado for the post-route gate. Same as the Stage 0/1 plan (`docs/superpowers/plans/2026-08-17-debug-ctrl-stage0-stage1.md`) — read that plan's own Global Constraints section for anything not repeated here; this plan does not restate Stage 0/1's contract, only Stage 2's additions to it.

## Global Constraints

- Branch is `fmax-closure-fanout`; the plan's parent commit is whatever `git rev-parse HEAD` reports at execution start. Never `git checkout <sha>` in the shared tree — use `git worktree add` for any isolated before/after verification (project standing rule).
- **`docs/superpowers/specs/2026-08-09-debug-ctrl-jtag-repl-design.md` is the authority.** Section 6 (lines 389-527, "Precise halt/resume/step semantics") and section 12's "Stage 2" bullet (lines 777-787) define this plan's scope; section 13 (lines 848-864) lists the required assertions. Read section 6 in full before Task 4 — it is short and precise and this plan's task text does not repeat every clause.
- **`tools/debug/debug_regmap.def` is the frozen, machine-readable source of truth for every register offset and feature bit used in this plan — do NOT invent a new offset or bit.** Everything Stage 2 needs is ALREADY reserved at `STAGE=2` in that file (verified 2026-08-20): `OFF_CONTROL` bits 0/1/7 (`0x00008`, per spec §15.2's table — manual halt / single-step / step-arm, currently RAZ/WI), `OFF_STATUS` bits 0/1/4 (`0x0000C` — halted / exception-pending / auto-halt-latched), `OFF_PC` (`0x00010`, "Next live PC"), `OFF_LAST_PC` (`0x00014`, "Last committed PC"), `OFF_HALT_AFTER_LO/HI` (`0x00030`/`0x00034`), `OFF_HALT_CTL` (`0x0003C`, bit 2 = clear sticky reason/report latches), `OFF_HALT_REASON` (`0x00040`), `OFF_HALT_HIT_INST_LO/HI` (`0x00048`/`0x0004C`, macro count at the stop), `OFF_INST_LO/HI` (`0x01008`/`0x0100C`, free-running macro count), and feature bits 22 (`macro_retire_count`) / 23 (`stop_status_v2`), both already scoped to stage 2. `OFF_HALT_HIT_PC` (`0x00044`) is deliberately **stage 5** (breakpoint-hit reporting) — do not wire it in this plan; a manual/halt-after stop reports its location via `OFF_PC`/`OFF_LAST_PC` instead. `OFF_EXC_VEC`/`OFF_EXC_PC`/`OFF_HALT_EXC_MASK*`/`OFF_BREAK_PC*` are stage 5/9 — leave them RAZ/WI.
- **No new `Global.scala` key** (spec §0.9, restated from Stage 0/1's own constraint — still binding).
- **No new socket port.** Every Stage-2 register lives inside the existing `dbg_axi` CSR address space; `cpu_socket.vh`'s port list is unchanged by this plan.
- **The whole feature must be optional and gated** (explicit user directive, 2026-08-20): `DebugCtrlPlugin` currently has NO `enable` parameter and is instantiated unconditionally at `FullCoreSynth.scala:580`. Task 1 retrofits an `enable: Boolean = true` constructor parameter mirroring `VioProbePlugin`'s exact disable pattern (`src/main/scala/m68k040/debug/VioProbePlugin.scala:37-81`): every declared IO stays present and named identically regardless of `enable` (so the socket contract never moves), but with `enable=false` every register is tied to its Stage-1-shell RAZ/WI behavior (or, if simplest, the plugin's `logic` degenerates to exactly today's Stage-1-only behavior) and **zero** Stage-2 RTL (the halt FSM, the `DebugCommitService` consumer wiring in `RobPlugin`/`BackendWiringPlugin`) is instantiated. Every new sibling wire this plan adds to `RobPlugin` (`debugStopRequest`, `debugStepRequest`, `haltAfterTarget`) follows the SAME `allowOverride`-plus-idle-default convention already used for `coreHaltedIn`/`completion`/`branchCompletion` (`RobPlugin.scala:184-217, 384-398`), so `RobPlugin` itself elaborates correctly whether or not `BackendWiringPlugin` drives them from a live `DebugCtrlPlugin`.
- **Macro-boundary detection reuses `RobPayload.first`, no new per-uop field.** Confirmed by direct grep (2026-08-20): `firstOfInstr` is set at ~20 independent call sites across `MicroOpAssembler.scala`; `MicroOp.scala:12`'s `lastUop` field exists but is dead (zero consumers) and threading an equally-independent "last" bit through the same ~20 sites would be large and error-prone for no benefit — the retiring entry's own `RobPayload.first` (macroFirst) plus the NEXT entry's `RobPayload.first` (already read every cycle as `p1`, `RobPlugin.scala:594`) together give the exact spec-required "explicit macroFirst and macroLast **or an equivalent boundary event from the ROB**" (spec §6.2) with no new decode-side plumbing. Task 4 makes this precise.
- **Effective halt does NOT require the store queue or D-cache to be idle** (spec §6.3, verbatim: "Effective halt deliberately does not require the store queue or D-cache to be idle... If a bus is wedged, requiring global memory quiescence here would make the debugger unable to stop at the exact moment it is needed most"). Do not gate the halt FSM's `HALTED` transition on any SQ/D-cache busy signal. `DebugMemoryQuiesceService` (a SEPARATE, later concern for cache-maintenance/arch-apply launch) is explicitly OUT of scope for this plan — Stage 2 has no cache-maintenance or arch-apply command to gate.
- **Fatal halt (`coreHalted`) is non-resumable and takes priority over debug halt** (spec §6.3, §13: "a resume request cannot release a fatal halt"). Every task touching resume must preserve this.
- **`ExecuteLockStepSpec` baseline is 399/400** (1 known pre-existing failure: `CMP2.W (d8,An,Xn) indexed bounds pointer` (task #257, already tracked) -- CORRECTED 2026-08-20 during Task 1 execution, the STOP/ITLB-failures baseline this plan originally cited was stale project-memory, not the actual current HEAD baseline) — verify against this exact baseline, not a fresh count, at every task's gate. Re-read the CURRENT actual failing-test names before the first task (they may have drifted since this plan was written) rather than trusting this list blindly.
- **`sbt "testOnly X -- -z name"` as separate shell tokens SILENTLY DROPS the `-z` filter** in this environment and runs the entire suite. Use the single quoted form: `sbt "testOnly X -- -z name"`. (Standing warning from the Stage 0/1 plan's own ledger, repeated here since every task's verification step needs it.)
- **The real, hardware-motivating context for this plan**: this session found that `tools/jtag_repl.tcl`'s `halt-status`/`pc`/`sweep`/`arch` commands all read back zero/uninformative against a real CPU=m68k040 bitstream — not because the CPU is hung, but because none of this RTL exists yet (Stage 1 only implements version/build/features/control/status/reset-count). A concurrently-running boot_fsm ROM-mirror fix (commit `d3b21e3`, macqd700-soc worktree) is independently verified correct via direct AXI memory readback; whether the CPU is actually executing that code cannot currently be told apart from a genuine hang. This plan's Task 14 gate should be followed by a real hardware re-test once merged (tracked separately, not a task in this plan — this plan's scope is the RTL and its Scala-level verification only, not a new bitstream build/flash cycle).
- **Mandatory full-core OOC synth gate at the end (project standing rule, `[[synth-gate-every-slice]]`)**: report absolute LUT/FF/DSP/BRAM utilization and FMax for both `enable=true` and `enable=false` builds. **User directive (2026-08-20): the `enable=true` build's FMax hit is EXPLICITLY ACCEPTED, even beyond spec §11's provisional +2% ceiling** — the stated plan is that this halt/resume/step logic will eventually sit on a hot retire-path signal, production ships `enable=false` for full clock speed, and a JTAG-enabled debug bitstream running at roughly half clock speed is an accepted tradeoff, not a gate failure. **Concrete numeric targets (user, same message): `enable=true` (JTAG bitstream) targets ~100MHz; `enable=false` (production, no JTAG) targets ~200MHz** — these are the real product-level numbers this session's own real-hardware bitstream work already runs at 100MHz (`CORE_CLK_HZ=100_000_000` in the macqd700-soc build), so an `enable=true` post-route result comfortably above 100MHz is a PASS regardless of how far below the `enable=false` build's own number it lands; the `enable=false` build is judged against the actual current FMax-campaign reference (~200MHz class, re-read at execution time per this file's own standing rule below), not against the `enable=true` number. **This does NOT relax the `enable=false` build's gate**, which must still show zero-to-negligible cost against the pre-Stage-2 reference (it carries none of this task's new logic — see Task 1's `if(enable)` gating) — that build is production's actual target and spec §11's numbers still apply to it. Task 14 therefore gates on: (a) `enable=false` meets the strict spec §11 numbers against the ~200MHz-class reference, (b) `enable=true` clears ~100MHz post-route with real margin (not a knife-edge pass), and its WNS regression relative to `enable=false`, however large, traces cleanly to the NEW debug halt/resume/step logic itself (report the worst path and confirm it's plausibly this feature, not an unrelated regression riding along), and (c) still flag if the `enable=true` worst path is literally "a debug comparator, CSR read mux, or high-fanout debug enable" per spec §11's specific named failure mode, since THAT diagnosis (as opposed to the raw number) is what tells us whether a later pipelining pass could recover most of the loss cheaply — worth knowing even though it's no longer a hard blocker. The FMax reference is re-read at execution time from the most recent recorded post-route number on this branch, never hardcoded — Task 14 re-reads it.

---

## Task 1: Gate `DebugCtrlPlugin` behind a real `enable` flag

**Files:**
- Modify: `src/main/scala/m68k040/debug/DebugCtrlPlugin.scala`
- Modify: `src/main/scala/m68k040/top/FullCoreSynth.scala:580` (and the `main` at line 591-593)
- Test: `src/test/scala/m68k040/debug/DebugCtrlPluginSpec.scala` (locate the existing Stage-1 spec file — it exists since Stage 1 shipped; add to it, do not create a duplicate)

**Interfaces:**
- Consumes: nothing new.
- Produces: `DebugCtrlPlugin(buildId: BigInt, porCycles: Int, stage: Int, enable: Boolean = true)` — every later task in this plan places its new logic inside the `if (enable) { ... }` branch this task creates. Every later task's `RobPlugin`/`BackendWiringPlugin` wiring must be written so that `enable=false` produces IDENTICAL RTL (module-for-module) to the current `main` branch's `M68kFullCoreSynth.v` for everything outside `DebugCtrlPlugin` itself.

- [ ] **Step 1: Read the exact disable pattern to mirror**

Read `src/main/scala/m68k040/debug/VioProbePlugin.scala` lines 1-90 in full before writing this task's code — its own comments (lines 17-26, 41-45, 76-81) explain SpinalHDL's "a `val` declared only inside `if (enable) {...}` cannot be referenced outside that block" constraint and the exact idiom used to keep every IO port present-and-named regardless of `enable`. Follow that idiom exactly, not a paraphrase of it.

- [ ] **Step 2: Add the `enable` constructor parameter**

In `DebugCtrlPlugin.scala`, change the class signature:

```scala
class DebugCtrlPlugin(val buildId:   BigInt  = BigInt(0),
                      val porCycles: Int     = DebugRegMap.POR_CYCLES_DEFAULT,
                      val stage:     Int     = 1,
                      val enable:    Boolean = true) extends FiberPlugin {
```

Add a doc comment above the class (alongside the existing `@param` list) documenting `enable`: "When false, every socket port this plugin declares (`dbg_axi`, `cpu_cold_reset_pulse`, `cpu_cold_reset_hold`, `cpu_ram_window_lg2`, `cpu_mon_sense`) stays present with its exact name and width — the socket contract never moves — but is tied to a dead-idle value (AXI READY held low forever, `bresp`/`rresp` OKAY on the rare accepted default path, cold-reset outputs low, RAM window/mon-sense their POR constants) and zero debug logic is instantiated. Mirrors `VioProbePlugin`'s `enable` pattern exactly (spec `2026-08-18-vio-jtag-debug-design.md` V4)."

- [ ] **Step 3: Wrap the existing Stage-1 `logic` body in `if (enable) { ... } else { ... }`**

The whole current `val logic = during build new Area { ... }` body (lines 54-442 of the file as it exists before this task) becomes the `if (enable)` arm, UNCHANGED. Add an `else` arm that declares every port the `if` arm declares (same `slave(DbgAxiLite(...))`, same 4 `out`/`in` socket signals) with dead-idle drives:

```scala
val logic = during build new Area {
  val dbgAxi = slave(DbgAxiLite(DebugRegMap.DBG_AW, DebugRegMap.DBG_DW))
  dbgAxi.setName("dbg_axi")
  val coldResetPulse = out(Bool()).setName("cpu_cold_reset_pulse")
  val coldResetHold  = out(Bool()).setName("cpu_cold_reset_hold")
  val initDoneSeen   = in(Bool()).setName("init_done_seen")
  val ramWindowLg2   = out(UInt(6 bits)).setName("cpu_ram_window_lg2")
  val monSense       = out(UInt(7 bits)).setName("cpu_mon_sense")

  if (enable) {
    // <-- everything that is currently the WHOLE body of `logic` in the file
    // as it exists today (the `dbgAxi.setName`/`coldResetPulse`/etc lines above
    // are hoisted OUT of this block since both arms need them; do not
    // duplicate their declarations inside this `if`) goes here, unmodified.
  } else {
    dbgAxi.awready := False
    dbgAxi.wready  := False
    dbgAxi.arready := False
    dbgAxi.bvalid  := False
    dbgAxi.bresp   := DbgAxiLite.RESP_OKAY
    dbgAxi.rvalid  := False
    dbgAxi.rdata   := B(0, DebugRegMap.DBG_DW bits)
    dbgAxi.rresp   := DbgAxiLite.RESP_OKAY
    coldResetPulse := False
    coldResetHold  := False
    ramWindowLg2   := U(DebugRegMap.RAM_WINDOW_LG2_POR, 6 bits)
    monSense       := U(DebugRegMap.MON_SENSE_POR, 7 bits)
  }
}
```

Follow `VioProbePlugin`'s own comment (lines 41-45) about promoting shared `val`s to the enclosing scope rather than duplicating declarations in both arms — apply the identical restructuring discipline here (hoist `dbgAxi`/`coldResetPulse`/`coldResetHold`/`initDoneSeen`/`ramWindowLg2`/`monSense` to the `logic` Area's own scope, both arms only ASSIGN into them).

- [ ] **Step 4: Wire the flag through `FullCoreSynth.scala`**

At `FullCoreSynth.scala:580`, keep the call site unchanged for now (`new m68k040.debug.DebugCtrlPlugin(buildId = dbgBuildId, stage = 1)`) — the default `enable = true` preserves today's behavior exactly, so `M68kFullCoreSynth.v` is unaffected by this task. Add a SECOND generation target, mirroring the existing `GenFullCoreSynthVioVerilog` twin-target pattern at line ~596 (read that pattern first): a `GenFullCoreSynthNoDebugVerilog` object whose `main` calls `buildWith(..., debugEnable = false, outputName = "M68kFullCoreSynthNoDebug")`. This requires threading a new `debugEnable: Boolean` parameter through `buildWith`'s own signature (find it above line 555) down to the `DebugCtrlPlugin(...)` call site, defaulting `true` so every EXISTING caller of `buildWith` is unaffected.

- [ ] **Step 5: Directed test — both builds elaborate, and `enable=false` matches pre-Stage-2 Stage-1 behavior byte-for-byte**

Add to `DebugCtrlPluginSpec.scala` (find its existing `SimpleDut`-equivalent harness first — Stage 1 already has directed tests for this plugin standalone; match its DUT-construction convention exactly rather than inventing a new one):

```scala
test("enable=false: AXI READY never asserts, cold-reset outputs stay low, RAM window/mon-sense read POR defaults") {
  // construct the DUT with DebugCtrlPlugin(enable = false); drive a few AXI
  // AR/AW transactions across N cycles; assert dbgAxi.arready/awready/wready
  // NEVER go high (READY-low = accepted-nothing, not a hang -- the disabled
  // slave simply never completes a transaction, which is the correct "this
  // plugin does not exist" contract, not a protocol violation since AXI
  // permits READY to stay low indefinitely).
}
```

- [ ] **Step 6: Compile + lock-step regression + verilator lint on both targets**

```
~/sbt/bin/sbt compile
~/sbt/bin/sbt "testOnly m68k040.debug.DebugCtrlPluginSpec"
~/sbt/bin/sbt "testOnly m68k040.ExecuteLockStepSpec"   # expect 399/400, unchanged
~/sbt/bin/sbt "runMain m68k040.top.GenFullCoreSynth"
~/sbt/bin/sbt "runMain m68k040.top.GenFullCoreSynthNoDebugVerilog"
make lint-fpga-top CPU=m68k040   # from the macqd700-soc worktree, if reachable; note if not and defer
```

Diff `generated/M68kFullCoreSynth.v` against a copy taken BEFORE this task's edit (same `dbgBuildId` env) — it must be BYTE-IDENTICAL, proving the default-`enable=true` refactor is a pure no-op for the shipped netlist.

- [ ] **Step 7: Commit**

```bash
git add src/main/scala/m68k040/debug/DebugCtrlPlugin.scala src/main/scala/m68k040/top/FullCoreSynth.scala src/test/scala/m68k040/debug/DebugCtrlPluginSpec.scala
git commit -m "debug: gate DebugCtrlPlugin behind a real enable flag (Stage 2 task 1)"
```

---

## Task 2: `DebugCommitService` trait + `RobPlugin` setup-allocated-wire skeleton

**Files:**
- Modify: `src/main/scala/m68k040/services/Services.scala`
- Modify: `src/main/scala/m68k040/rob/RobPlugin.scala`
- Test: `src/test/scala/m68k040/rob/RobPluginSpec.scala` (add a sink-plugin test; follow `RobTestHelpers.scala`'s existing `FrontendQuiesceSinkPlugin` at lines 96-104 as the template)

**Interfaces:**
- Consumes: nothing (pure plumbing task — every field below is driven to an inert default; Task 3 onward gives them real values).
- Produces: `DebugCommitService` trait with:
  ```scala
  trait DebugCommitService {
    def effectiveHalt: Bool        // OFF_STATUS bit 0
    def autoHaltLatched: Bool      // OFF_STATUS bit 4
    def haltReasonDebug: UInt      // 3 bits: NONE/MANUAL/STEP/HALT_AFTER (Task 9 defines the encoding)
    def livePc: UInt               // OFF_PC — next-to-execute macro PC while halted
    def lastPc: UInt               // OFF_LAST_PC — most recently retired macro's PC
    def macroCount: UInt           // 64 bits (host reads as OFF_INST_LO/HI, two 32-bit halves)
    def haltHitInstCount: UInt     // 64 bits — macroCount latched at the stop (OFF_HALT_HIT_INST_LO/HI)
    // Debug -> ROB commands, consumed here for symmetry with the setup-allocated-wire
    // pattern's existing "same trait carries both directions" precedent (none currently
    // does — RedirectService/FrontendQuiesceService are one-directional — so this is a
    // DELIBERATE deviation: see Task 5's own note on why request/response live in one
    // trait rather than two).
  }
  ```
  Later tasks add `def request(stop: Bool, step: Bool, resume: Bool, haltAfterTarget: UInt, haltAfterArm: Bool): Unit` or an equivalent command bundle — Task 5 finalizes this trait's exact shape once the FSM's real input contract is known; THIS task only adds the 7 read-side accessors above, all driven to `False`/`0`.

- [ ] **Step 1: Add the trait to `Services.scala`**

Place it immediately after `FrontendQuiesceService` (line 49-54), following that trait's own doc-comment style:

```scala
/** ROB-owned debug halt/resume/step state (design spec
  * `docs/superpowers/specs/2026-08-09-debug-ctrl-jtag-repl-design.md` section 6, Stage 2).
  * Read-side only in this task; `DebugCtrlPlugin` is the sole consumer, wired centrally
  * in `BackendWiringPlugin` like every other cross-plugin signal in this codebase. */
trait DebugCommitService {
  def effectiveHalt:    Bool
  def autoHaltLatched:  Bool
  def haltReasonDebug:  UInt
  def livePc:           UInt
  def lastPc:           UInt
  def macroCount:       UInt
  def haltHitInstCount: UInt
}
```

- [ ] **Step 2: `RobPlugin` implements it via the setup-allocated-wire pattern**

Mirror `_frontendQuiesceActive`/`_frontendQuiesceNext` (`RobPlugin.scala:41-54`) EXACTLY — same reasoning (breaks the Icache→Rob→Rename→Decode→FetchAlign→Icache Fiber cycle), same `private var` + `override def` + `during setup { ... = Bool()/UInt(...) }` shape:

```scala
class RobPlugin extends FiberPlugin with CommitTraceService with RobAllocService with RedirectService
  with BtbUpdateService with GshareUpdateService with PrivilegeService with CacheControlService
  with FrontendQuiesceService with DebugCommitService {

  // ... existing _supervisor/_dcacheEnabled/_frontendQuiesceActive/_frontendQuiesceNext ...
  private var _debugEffectiveHalt:    Bool = null
  private var _debugAutoHaltLatched:  Bool = null
  private var _debugHaltReason:       UInt = null
  private var _debugLivePc:           UInt = null
  private var _debugLastPc:           UInt = null
  private var _debugMacroCount:       UInt = null
  private var _debugHaltHitInstCount: UInt = null
  override def effectiveHalt:    Bool = _debugEffectiveHalt
  override def autoHaltLatched:  Bool = _debugAutoHaltLatched
  override def haltReasonDebug:  UInt = _debugHaltReason
  override def livePc:           UInt = _debugLivePc
  override def lastPc:           UInt = _debugLastPc
  override def macroCount:       UInt = _debugMacroCount
  override def haltHitInstCount: UInt = _debugHaltHitInstCount
  during setup {
    _supervisor             = Bool()
    _dcacheEnabled          = Bool()
    _frontendQuiesceActive  = Bool()
    _frontendQuiesceNext    = Bool()
    _debugEffectiveHalt     = Bool()
    _debugAutoHaltLatched   = Bool()
    _debugHaltReason        = UInt(3 bits)
    _debugLivePc            = UInt(32 bits)
    _debugLastPc            = UInt(32 bits)
    _debugMacroCount        = UInt(64 bits)
    _debugHaltHitInstCount  = UInt(64 bits)
  }
```

- [ ] **Step 3: Drive every new wire to an inert default inside `logic`**

Near the END of `logic` (find where `_frontendQuiesceActive := stopped || coreHalted` is driven — the explore pass for this plan located it around line 1523; place these assignments immediately after it, same Area scope):

```scala
_debugEffectiveHalt    := False
_debugAutoHaltLatched  := False
_debugHaltReason       := U(0, 3 bits)
_debugLivePc           := p0.pc          // best available "next" PC today; Task 6 refines
_debugLastPc           := U(0, 32 bits)  // Task 3 gives this a real producer
_debugMacroCount       := U(0, 64 bits)  // Task 3 gives this a real producer
_debugHaltHitInstCount := U(0, 64 bits)
```

- [ ] **Step 4: Directed test — service resolves, defaults are inert**

In `RobTestHelpers.scala`, add a `DebugCommitSinkPlugin` mirroring `FrontendQuiesceSinkPlugin` (lines 96-104) exactly: implement nothing, just `host[DebugCommitService]` and expose each accessor as `out` IO for a directed test to peek. In `RobPluginSpec.scala`, add:

```scala
test("DebugCommitService resolves and every field is inert (False/0) with no debug driver wired") {
  // wire dut.rob + dut.dsink (DebugCommitSinkPlugin) via host.asHostOf(...);
  // run N cycles with normal retire traffic flowing; assert dsink.logic.effectiveHaltOut
  // stays False throughout and macroCountOut/haltHitInstCountOut stay 0 -- proves this
  // task adds zero observable behavior change, only plumbing.
}
```

- [ ] **Step 5: Compile + lock-step regression**

```
~/sbt/bin/sbt compile
~/sbt/bin/sbt "testOnly m68k040.rob.RobPluginSpec"
~/sbt/bin/sbt "testOnly m68k040.ExecuteLockStepSpec"   # expect 399/400, unchanged
```

- [ ] **Step 6: Commit**

```bash
git add src/main/scala/m68k040/services/Services.scala src/main/scala/m68k040/rob/RobPlugin.scala src/test/scala/m68k040/rob/RobTestHelpers.scala src/test/scala/m68k040/rob/RobPluginSpec.scala
git commit -m "rob: DebugCommitService skeleton, setup-allocated-wire pattern (Stage 2 task 2)"
```

---

## Task 3: Free-running macro-retire counter (`OFF_INST_LO/HI`, feature bit 22)

**Files:**
- Modify: `src/main/scala/m68k040/rob/RobPlugin.scala`
- Test: `src/test/scala/m68k040/rob/RobPluginSpec.scala`

**Interfaces:**
- Consumes: `retire0`/`retire1` (existing, `RobPlugin.scala:736-738`/`787-789`), `p0.first`/`p1.first` (existing `RobPayload` field).
- Produces: `_debugMacroCount` (Task 2's wire) now has a real value; `_debugLastPc` gets a real producer too (same task, since both are trivial retire-time captures).

- [ ] **Step 1: Add the counter register**

Near the other retire-time Regs (alongside `stoppedPc`/`coreHalted`, not inside the payload Mem — this is a single free-running counter, not per-entry state):

```scala
// ── Debug macro-retire counter (Stage 2, feature bit 22 macro_retire_count) ────
// Counts MACRO-INSTRUCTIONS retired, not micro-ops: increments once per retiring
// entry whose payload.first is True (the macro's own first uop -- payload.first
// is already threaded through every MicroOpAssembler crack site as the retire-time
// macro-boundary marker; see RobPayload.first / u.firstOfInstr). A macro's
// trailing uops (first=False) do not increment it, so a 5-uop MOVEM retiring
// across 5 cycles increments this exactly once, on the cycle its FIRST uop
// retires -- matching the spec's "OFF_INST_* ... count macro-instructions, not
// uops" (debug_regmap.def FEAT macro_retire_count). Free-running: never cleared
// except by CPU reset (it lives in RobPlugin's own logic, which IS the CPU-reset
// domain -- unlike DebugCtrlPlugin's surviving debug-domain registers).
val debugMacroCountReg = Reg(UInt(64 bits)) init 0
debugMacroCountReg.simPublic()
val debugMacroCountInc =
  (retire0 && p0.first).asUInt.resize(2) +
  (retire1 && p1.first).asUInt.resize(2)
when(debugMacroCountInc =/= 0) { debugMacroCountReg := debugMacroCountReg + debugMacroCountInc.resized }
```

- [ ] **Step 2: Add the last-committed-PC capture**

```scala
// ── Debug last-committed-PC (Stage 2, OFF_LAST_PC) ──────────────────────────
// The PC of the most recently retired MACRO (not every uop -- a trailing uop of
// a cracked macro shares its leading uop's PC by construction, per payload.pc's
// own doc comment, so capturing on EVERY retire vs only on first=True retires is
// observationally identical for this field; captured unconditionally on any
// retire for simplicity, matching that equivalence).
val debugLastPcReg = Reg(UInt(32 bits)) init 0
debugLastPcReg.simPublic()
when(retire1)      { debugLastPcReg := p1.pc }
  .elsewhen(retire0) { debugLastPcReg := p0.pc }
```

Place both immediately after the existing `retire1` definition (so `retire0`/`retire1`/`p0`/`p1` are already in scope) and BEFORE the head/tail-advance logic, matching this file's existing convention of computing retire-time derived state close to where `retire0`/`retire1` are defined.

- [ ] **Step 3: Wire into the Task-2 service accessors**

Replace Task 2's placeholder drives:

```scala
_debugMacroCount := debugMacroCountReg
_debugLastPc     := debugLastPcReg
```

- [ ] **Step 4: Directed test — counts macros, not uops**

```scala
test("debugMacroCount increments once per retiring macro, not once per retired uop") {
  // Drive a 3-uop cracked "macro" (first=True, False, False) through alloc/complete/retire
  // across 3 separate cycles (single-wide retire each cycle to keep the sequencing simple),
  // then a 1-uop macro. Assert dsink.logic.macroCountOut == 2 after both have retired, and
  // == 1 immediately after the FIRST uop of the 3-uop macro retires (not 0, not 3).
}
test("debugLastPc tracks the most recently retired entry's PC, single- and dual-retire") {
  // Retire two independent single-uop macros at DIFFERENT PCs in the SAME cycle
  // (retire0 && retire1 both true); assert debugLastPcOut == the SLOT-1 (p1) PC,
  // not slot 0 -- proves the elsewhen priority is right.
}
```

- [ ] **Step 5: Compile + lock-step regression**

```
~/sbt/bin/sbt compile
~/sbt/bin/sbt "testOnly m68k040.rob.RobPluginSpec"
~/sbt/bin/sbt "testOnly m68k040.ExecuteLockStepSpec"   # expect 399/400, unchanged
```

- [ ] **Step 6: Commit**

```bash
git add src/main/scala/m68k040/rob/RobPlugin.scala src/test/scala/m68k040/rob/RobPluginSpec.scala
git commit -m "rob: free-running macro-retire counter + last-committed-PC (Stage 2 task 3)"
```

---

## Task 4: Macro-boundary detection (`macroLast` via `p1.first`) + retire1 suppression at a debug-stop boundary

**Files:**
- Modify: `src/main/scala/m68k040/rob/RobPlugin.scala`
- Test: `src/test/scala/m68k040/rob/RobPluginSpec.scala`

**Interfaces:**
- Consumes: `p0.first`/`p1.first`, `count` (existing).
- Produces: `h0IsMacroLast: Bool` — internal signal (not exposed via the service; Task 5's FSM consumes it directly in the same `logic` scope) meaning "the entry about to retire at h0 this cycle is the LAST uop of its macro."

- [ ] **Step 1: Read spec §6.2 in full before writing this task** (`docs/superpowers/specs/2026-08-09-debug-ctrl-jtag-repl-design.md:426-444`) — this task implements exactly its four bullet points and the closing "Debug code must not infer them from PC equality" warning, which this derivation deliberately does NOT do (it uses the rename-produced `first` marker, never PC comparison).

- [ ] **Step 2: Derive `h0IsMacroLast`**

Place near `h0TraceArmed`'s own definition (search for it — the explore pass for this plan found it forward-declared around line 709 and driven later; add this new signal the same way, forward-declared here, driven where `h0TraceArmed` is driven, for the identical reason: both need `p1`/`count` which are in scope earlier than where the exception unit exposes the values `h0TraceArmed` actually depends on — check whether `h0IsMacroLast` needs any exception-unit input; if not, as this derivation only needs `p1.first`/`count`, it can be a single non-forward-declared `val` right where `p0`/`p1` are defined, simpler than `h0TraceArmed`'s split):

```scala
// ── Macro-boundary detection for debug stop (Stage 2) ───────────────────────
// "Is the entry retiring at h0 THIS cycle the LAST uop of its macro?" -- exactly
// spec section 6.2's macroFirst/macroLast pair, derived from the ALREADY-EXISTING
// per-entry `payload.first` (macroFirst) rather than a new per-uop field (see this
// plan's own Global Constraints note on why). True iff:
//   (a) the ROB will be empty after this retire (count <= the number retiring this
//       cycle) -- nothing behind h0 to be a trailing uop of the SAME macro, OR
//   (b) the NEXT entry (h1/p1) is itself a macro's first uop (p1.first) -- h0 was
//       the last uop of ITS macro.
// Deliberately independent of retire0/retire1/flushing: this is a property of the
// RING CONTENTS, not of whether h0 is retiring this cycle at all (Task 5 gates its
// USE on headReady/retire0 itself).
val h0IsMacroLast = (count <= 1) || p1.first
h0IsMacroLast.simPublic()
```

- [ ] **Step 3: Directed test — matches real MicroOpAssembler crack shapes**

This is the highest-risk correctness claim in this plan (a wrong derivation silently mis-stops mid-crack, which is exactly the failure mode spec §6.2 warns about). Test against REAL decode output, not hand-built `RenamedUop`s:

```scala
test("h0IsMacroLast is True on the trailing uop of a real cracked MOVEM, False on its leading uops") {
  // Use the FULL decode pipeline (DecodeStage + MicroOpAssembler via the existing
  // ExecuteLockStepSpec-style harness, or RobPluginSpec's own pokeRu if it already
  // threads firstOfInstr correctly for a hand-built 3-uop sequence) to allocate a
  // real MOVEM.L D0-D2,-(A7) (3 target registers -> 3 uops: first=True/False/False),
  // retire single-wide across 3 cycles, and assert h0IsMacroLast reads False, False,
  // True on the 3 respective retiring cycles.
}
test("h0IsMacroLast is True for every single-uop macro") {
  // Single NOP-shaped uop, first=True, alone in the ROB (count==1 after alloc).
  // Assert h0IsMacroLast == True.
}
test("h0IsMacroLast is True when h0 is the newest allocated entry and nothing follows it yet (count==1, no h1)") {
  // Alloc exactly one uop (first=True), do NOT alloc a second one behind it.
  // Assert h0IsMacroLast == True even though, in principle, more uops of the
  // SAME macro could still be dispatched later -- confirm this is IMPOSSIBLE by
  // checking DispatchPlugin.scala/MicroOpAssembler.scala allocate a whole crack
  // sequence atomically (same cycle or a contiguous burst with no OTHER macro's
  // uop interleaved) -- if that atomicity does NOT hold, this whole task's
  // derivation is wrong and must escalate to the human rather than silently
  // proceeding with a new per-uop macroLast field instead. Confirm before writing
  // Step 2's code, not after -- this is a NEEDS_CONTEXT-worthy question, not a
  // judgment call, if the atomicity assumption turns out false.
}
```

- [ ] **Step 4: Compile + lock-step regression**

```
~/sbt/bin/sbt compile
~/sbt/bin/sbt "testOnly m68k040.rob.RobPluginSpec"
~/sbt/bin/sbt "testOnly m68k040.ExecuteLockStepSpec"   # expect 399/400, unchanged (h0IsMacroLast is unused by anything yet)
```

- [ ] **Step 5: Commit**

```bash
git add src/main/scala/m68k040/rob/RobPlugin.scala src/test/scala/m68k040/rob/RobPluginSpec.scala
git commit -m "rob: h0IsMacroLast macro-boundary derivation for debug stop (Stage 2 task 4)"
```

---

## Task 5: The halt state machine — `debugStopRequest`/`debugResumeRequest` sibling wires, `RUNNING`/`STOP_PENDING`/`RECOVER`/`HALTED`

**Files:**
- Modify: `src/main/scala/m68k040/rob/RobPlugin.scala`
- Modify: `src/main/scala/m68k040/services/Services.scala` (finalize `DebugCommitService`'s command-side shape)
- Test: `src/test/scala/m68k040/rob/RobPluginSpec.scala`

**Interfaces:**
- Consumes: `h0IsMacroLast` (Task 4), `headReady`/`doFlushReg`/`flushPcReg`/`coreHalted` (existing).
- Produces: `_debugEffectiveHalt` now real; `headReady` gains a debug-halt term; `doFlushReg`/`flushPcReg` gain a RECOVER-to-restart-PC arm; a new sibling-driven input pair `debugStopRequestIn: Bool` / `debugResumeRequestIn: Bool` (same `allowOverride` + idle-default convention as `coreHaltedIn`).

- [ ] **Step 1: Add the sibling-driven command inputs**

Alongside `coreHaltedIn` (`RobPlugin.scala:384-398`), same pattern:

```scala
// ── Debug stop/resume request (Stage 2, sibling-driven from DebugCtrlPlugin via
// BackendWiringPlugin -- Task 6). allowOverride + idle-False default so RobPlugin
// elaborates standalone (no DebugCtrlPlugin instantiated, or enable=false) with
// this permanently False, matching coreHaltedIn's own convention exactly.
val debugStopRequestIn = Bool(); debugStopRequestIn.allowOverride; debugStopRequestIn := False
debugStopRequestIn.simPublic()
val debugResumeRequestIn = Bool(); debugResumeRequestIn.allowOverride; debugResumeRequestIn := False
debugResumeRequestIn.simPublic()
```

- [ ] **Step 2: The state machine**

Add a small SpinalEnum near the top of `logic` (or as a top-level object in this file, matching `HaltReason`'s own placement style):

```scala
object DebugHaltState extends SpinalEnum {
  val RUNNING, STOP_PENDING, RECOVER, HALTED = newElement()
}
```

State register and transition logic, placed AFTER `h0IsMacroLast` (Task 4) and AFTER `retire0`/`retire1`/`headReady` are all in scope, BEFORE the `doFlushReg`/head-advance block (so this task's own `when` arms on `doFlushReg` land at the SAME elaboration position as the existing `branchRedirect`/`exc.redirectValid` arms — "last assignment wins" ordering matters here exactly as documented for `DebugCtrlPlugin`'s own `cpuRstEvent` block):

```scala
// ── Debug halt state machine (Stage 2, spec section 6.1) ────────────────────
val debugHaltState = Reg(DebugHaltState()) init DebugHaltState.RUNNING
debugHaltState.simPublic()
val debugStopBoundaryHit = headReady && retire0 && h0IsMacroLast
// STOP_PENDING -> RECOVER the cycle the CURRENTLY-retiring macro finishes (its
// LAST uop retires); RUNNING -> STOP_PENDING the cycle a stop is requested.
// A stop sampled while the head is ALREADY at a fresh macro (h0.first == True,
// nothing yet retired of it) still waits for h0IsMacroLast on THAT SAME macro --
// spec 6.2's "If a stop is sampled while the head is already at a new macro,
// that macro does not retire" is satisfied because STOP_PENDING blocks retire0
// itself (see headReady's new term below) BEFORE the boundary check ever runs
// on a fresh macro that hasn't started retiring -- i.e. debugStopBoundaryHit can
// only fire for a macro that was ALREADY mid-retirement (multi-uop) when the
// stop was sampled; a fresh macro is blocked outright, never partially retired.
val restartPcCapture = UInt(32 bits)
restartPcCapture := p0.predNextPc   // the macro AFTER the one that just finished
switch(debugHaltState) {
  is(DebugHaltState.RUNNING) {
    when(debugStopRequestIn) { debugHaltState := DebugHaltState.STOP_PENDING }
  }
  is(DebugHaltState.STOP_PENDING) {
    when(debugStopBoundaryHit) { debugHaltState := DebugHaltState.RECOVER }
  }
  is(DebugHaltState.RECOVER) {
    // One cycle: the flush/redirect pulse (below) fires THIS cycle; by next
    // cycle speculative state has been rolled back (same latency every other
    // doFlushReg consumer already assumes -- BackendWiringPlugin's fan-out is
    // a single registered pulse, not a multi-cycle handshake).
    debugHaltState := DebugHaltState.HALTED
  }
  is(DebugHaltState.HALTED) {
    when(debugResumeRequestIn) { debugHaltState := DebugHaltState.RUNNING }
  }
}
val debugHalted = debugHaltState === DebugHaltState.HALTED
_debugEffectiveHalt := debugHalted
```

- [ ] **Step 3: Gate retire on `STOP_PENDING`/`HALTED`, suppress the boundary macro itself, extend `headReady`**

`headReady` (existing, `RobPlugin.scala:622`) becomes:

```scala
val headReady = (count > 0) && completes(h0) && !flushing && !coreHalted &&
  !(debugHaltState === DebugHaltState.HALTED)
```

Do NOT add `STOP_PENDING` to this gate — `STOP_PENDING` must still let the in-flight macro finish retiring (spec 6.1: "finish any already-partially-retired cracked macro"); only `HALTED` blocks retire outright. The actual "stop BEFORE the next macro" enforcement is `debugStopBoundaryHit`'s own transition into `RECOVER` (which flushes and redirects BEFORE the next macro's first uop can be dispatched/allocated — Task 7 makes RECOVER's flush pulse suppress frontend admission via the EXISTING `_frontendQuiesceActive` wire, not a new gate here).

Retire-1 suppression across the boundary (spec 6.2 "Retire slot 1 is suppressed whenever it belongs to a different macro than slot 0 and a stop/step boundary is being taken" and "the remaining uops of that macro may complete/retire, but the next macro must not start"): extend `retire1`'s existing guard (`RobPlugin.scala:787-789`, per the explore pass's verbatim capture) with one more conjunct:

```scala
val retire1 = retire0 && (count > 1) && completes(h1) && !p0.retireAlone && !p1.retireAlone &&
  !faultedStore(h1) && !p1.isRte && !p1.needsSup && !p1.sysOp && !h0TraceArmed &&
  !h0PreciseCompletedSticky && sysAuxRdy1 &&
  !(debugStopRequestIn && h0IsMacroLast)   // NEW: don't let h1 (a DIFFERENT macro,
                                           // since h0IsMacroLast means h0 is its
                                           // macro's last uop) start while a stop
                                           // is pending and h0 is about to close
                                           // the boundary the stop is waiting for.
```

- [ ] **Step 4: Extend `doFlushReg`/`flushPcReg` with the RECOVER arm**

Locate the existing driving site (explore pass: `RobPlugin.scala:1592-1594`, `doFlushReg := branchRedirect || exc.redirectValid`). Add a third disjunct, elaborated in the SAME `when` block (this file's own documented "last assignment wins" discipline — place this arm so it does not silently lose priority to the other two on a same-cycle coincidence; since a debug stop boundary and a mispredict/exception redirect firing on the EXACT same cycle is a real, if rare, possibility, decide priority explicitly rather than by accidental elaboration order — recommended: exception/mispredict redirect wins, since spec 6.1's RECOVER is itself just "the next unexecuted macro's PC", and if an exception is ALSO redirecting this cycle, the debug stop should re-observe the exception's own landing PC on ITS next boundary rather than racing it):

```scala
val debugRecoverEnter = debugHaltState === DebugHaltState.STOP_PENDING && debugStopBoundaryHit
doFlushReg := branchRedirect || exc.redirectValid || debugRecoverEnter
when(branchRedirect)        { flushPcReg := nextPcRd0 }
when(exc.redirectValid)     { flushPcReg := exc.redirectPc }
when(debugRecoverEnter && !exc.redirectValid) { flushPcReg := restartPcCapture }
```

(The final `when` deliberately excludes the `branchRedirect` case too — if `debugRecoverEnter` and `branchRedirect` land on the identical head the identical cycle, `restartPcCapture` (`p0.predNextPc`) and `nextPcRd0` are very likely the SAME value already, since both derive from the same retiring head's predicted-next-PC; but do not assume this without checking — if `RobPluginSpec` reveals they can differ, add an explicit priority note rather than silently trusting equality.)

- [ ] **Step 5: `_debugLivePc` becomes the real restart PC while halted**

Task 2's placeholder (`_debugLivePc := p0.pc`) is wrong once halted — while `HALTED`, `p0` is whatever NEW head the flush landed the ROB on (likely `count == 0`, so `p0.pc` is stale/undefined per this file's own SAFETY discipline). Replace with:

```scala
val debugLivePcReg = Reg(UInt(32 bits)) init 0
when(debugRecoverEnter) { debugLivePcReg := flushPcReg }   // captures the SAME value just latched into flushPcReg this cycle
_debugLivePc := debugLivePcReg
```

- [ ] **Step 6: Directed tests**

```scala
test("stop request during a single-uop macro halts after exactly that macro, HALTED asserts effectiveHalt") {}
test("stop request during a 3-uop cracked macro (MOVEM-shaped) lets all 3 uops retire, then halts before the NEXT macro") {}
test("stop request sampled while head is already a fresh macro's first uop still waits for THAT macro to fully retire before halting") {}
test("resume clears HALTED, retire resumes, debugLivePc matches the macro that actually executes next") {}
test("headReady/retire0/retire1 never fire while debugHaltState==HALTED, across 1000 cycles with completes(h0)/(h1) forced True") {}
test("repeated halt/continue (10 cycles) leaves the ROB in a consistent state each time, no stuck STOP_PENDING") {}
```

- [ ] **Step 7: Compile + full lock-step + directed regression**

```
~/sbt/bin/sbt compile
~/sbt/bin/sbt "testOnly m68k040.rob.RobPluginSpec"
~/sbt/bin/sbt "testOnly m68k040.ExecuteLockStepSpec"   # expect 399/400 -- debugStopRequestIn defaults False so this task is a behavioral no-op for every existing test
```

- [ ] **Step 8: Commit**

```bash
git add src/main/scala/m68k040/rob/RobPlugin.scala src/main/scala/m68k040/services/Services.scala src/test/scala/m68k040/rob/RobPluginSpec.scala
git commit -m "rob: debug halt state machine RUNNING/STOP_PENDING/RECOVER/HALTED (Stage 2 task 5)"
```

---

## Task 6: Wire `DebugCtrlPlugin`'s `OFF_CONTROL` bits 0 (manual halt) into `RobPlugin` via `BackendWiringPlugin`

**Files:**
- Modify: `src/main/scala/m68k040/debug/DebugCtrlPlugin.scala`
- Modify: `src/main/scala/m68k040/top/FullCoreSynth.scala` (the `BackendWiringPlugin` class)
- Test: `src/test/scala/m68k040/debug/DebugCtrlPluginSpec.scala`, a new full-core-level directed test (find where `M68kFullCoreSynth`-shaped tests wire the whole plugin set together, e.g. a lock-step-adjacent harness, and add ONE directed "halt via dbg_axi actually stops the CPU" test there — the exact file to extend depends on what full-core-with-debug-ctrl test infrastructure exists after Task 1; locate it before writing this step, do not create a parallel one if one already exists)

**Interfaces:**
- Consumes: Task 5's `DebugCommitService.effectiveHalt` / the new `debugStopRequestIn`/`debugResumeRequestIn` RobPlugin inputs (not part of the service trait — they are plain sibling wires like `coreHaltedIn`, resolved via `host[RobPlugin].logic.debugStopRequestIn` the SAME way `BackendWiringPlugin` already reaches into `RobPlugin`'s other sibling inputs — check the exact idiom `BackendWiringPlugin` uses today for `coreHaltedIn` if it's driven from anywhere, or for `preciseDrainBusyIn`, and mirror it).
- Produces: `OFF_CONTROL` bit 0 is real (no longer RAZ/WI); a write with bit 0 set requests stop, an accepted write with bit 0 clear requests resume (spec §6.4, verbatim — implement exactly this, not a toggle).

- [ ] **Step 1: `DebugCtrlPlugin` — make `OFF_CONTROL` bit 0 real**

In the `controlWord`/write-decode logic (inside the Task-1 `if (enable)` block), replace the Stage-1 placeholder:

```scala
// was: False ##  // bit 0  manual halt request  -- RAZ/WI until Stage 2
manualHaltLevel ##  // bit 0  manual halt request (Stage 2: real)
```

Add the register and its own edge-detected request/resume outputs (a level readback per spec §6.4 "Manual halt is a level in OFF_CONTROL for readback compatibility, but the commit owner receives registered COMMANDS" — so `manualHaltLevel` is the readback level, but the OUTPUT to RobPlugin is a ONE-CYCLE PULSE on each 0->1 and 1->0 transition, matching how `coldPulse` is already a pulse derived from a CONTROL write in this same file):

```scala
val manualHaltLevel = RegInit(False); manualHaltLevel.simPublic()
// (in the write decode, alongside ctrlInitDoneOvr/ctrlColdHold/coldPulse)
manualHaltLevel := m(0)
val manualHaltLevelPrev = RegNext(manualHaltLevel) init False
val debugStopRequest   = manualHaltLevel && !manualHaltLevelPrev
val debugResumeRequest = !manualHaltLevel && manualHaltLevelPrev
debugStopRequest.simPublic(); debugResumeRequest.simPublic()
```

Expose these as new socket-INTERNAL (not `dbg_axi`-facing — no new socket port, per this plan's Global Constraints) outputs the same way `coldResetPulse`/`coldResetHold` are exposed, but WITHOUT `.setName(...)` (those two are real `cpu_socket.vh` ports; these are new, purely-internal cross-plugin wires with no socket contract):

```scala
val debugStopRequestOut   = out(Bool()); debugStopRequestOut := debugStopRequest
val debugResumeRequestOut = out(Bool()); debugResumeRequestOut := debugResumeRequest
```

- [ ] **Step 2: `BackendWiringPlugin` — the actual connection**

Find `BackendWiringPlugin`'s constructor/`during build` in `FullCoreSynth.scala` (the explore pass located `doFlush`'s own resolution at line 59, `host[RedirectService].doFlush`) and add, guarded on the plugin actually existing (this wiring plugin ALREADY sits after `DebugCtrlPlugin` in the plugin list — no, wait, `DebugCtrlPlugin` is instantiated AFTER `BackendWiringPlugin` at line 574 vs 580; check whether `BackendWiringPlugin`'s `during build` runs late enough in the Fiber schedule to safely resolve `host[DebugCtrlPlugin]` regardless of list order — SpinalHDL FiberPlugin ordering is by `during setup`/`during build` dependency, not list position, but confirm this before assuming; if there's a genuine ordering hazard, this wiring may need to move OUT of `BackendWiringPlugin` into a new, small, LATER-only wiring plugin instantiated after `DebugCtrlPlugin` in the list — decide based on what you find, do not guess):

```scala
// Debug halt/resume request, Stage 2. host.get (not host[...]) because
// DebugCtrlPlugin may be absent from a leaner build variant in the future
// (today it is always instantiated, but enable=false already means "no debug
// logic" -- mirror that same optionality here rather than hard-requiring it).
host.get[m68k040.debug.DebugCtrlPlugin] match {
  case Some(dbg) =>
    rob.logic.debugStopRequestIn   := dbg.logic.debugStopRequestOut
    rob.logic.debugResumeRequestIn := dbg.logic.debugResumeRequestOut
  case None => // rob's own defaults (False/False) already apply
}
```

- [ ] **Step 3: `OFF_STATUS` bits 0/4 become real**

```scala
// was: False ## // bit 4 auto-halt latched (Stage 2)
// was: True  ## // bit 3 running -- always, there is no halt yet
// was: False ## // bit 0 halted (Stage 2)
val dbgCommit = host.get[m68k040.services.DebugCommitService]
val effHalt   = dbgCommit.map(_.effectiveHalt).getOrElse(False)
val autoHalt  = dbgCommit.map(_.autoHaltLatched).getOrElse(False)
// ...
rData := B(0, 27 bits) ##
         autoHalt ##             // bit 4  auto-halt latched
         !effHalt ##             // bit 3  running (mutually exclusive with halted, spec 3.3)
         initDoneLatched ##      // bit 2  init-done seen
         False ##                // bit 1  exception pending (Stage 2 does not implement this bit; stays 0)
         effHalt                 // bit 0  halted
```

(Resolve `host.get[DebugCommitService]` the SAME way `MmuControlPlugin`'s Handle-blocking pattern does — via the `logic` `Handle`, not a raw eager call in the constructor — matching this plugin's own existing `host.get[InterruptControlService]`-shaped precedent already used elsewhere in this codebase if `DebugCtrlPlugin` itself already resolves any optional service this way; if it does not yet, this is the first one it adds and must follow `RobPlugin`'s OWN `intCtrl` pattern at `RobPlugin.scala:151-158` — a `host.get[...]` with a `None` fallback to inert defaults — exactly, since that is the established convention for an OPTIONAL cross-plugin dependency in this codebase, as distinct from the setup-allocated-wire pattern which is for breaking a Fiber CYCLE, not for optionality.)

- [ ] **Step 4: `OFF_PC`/`OFF_LAST_PC` wired real**

```scala
is(DebugRegMap.OFF_PC)      { rData := dbgCommit.map(_.livePc).getOrElse(U(0,32 bits)).asBits }
is(DebugRegMap.OFF_LAST_PC) { rData := dbgCommit.map(_.lastPc).getOrElse(U(0,32 bits)).asBits }
```

- [ ] **Step 5: Bump the shipped `stage` default from 1 to 2**

At `FullCoreSynth.scala:580`, change `stage = 1` to `stage = 2` ONLY once Tasks 6-11 (this task through the assertions task) are all landed and gated — do NOT do this in Task 6 itself; leave `stage = 1` for now and flag it as this plan's Task 12's own Step 1 (features 22/23 must not be advertised before the halt-after/step machinery they imply is actually done — spec §3.4's honesty rule, restated).

- [ ] **Step 6: Directed test — halt via `dbg_axi` actually stops retirement, end to end**

```scala
test("writing OFF_CONTROL bit 0 = 1 through dbg_axi halts the full core at the next macro boundary; OFF_STATUS bit 0 reads 1; clearing bit 0 resumes") {
  // Full-core-shaped DUT (whatever harness Step 6's file-location decision above
  // settled on). Run a short real program (e.g. a tight NOP/ADD loop from the
  // existing ported-corpus asm fixtures) via dbg_axi's own AW/W/AR/R protocol
  // (mirror the AXI4-Lite driver already used by DebugCtrlPluginSpec's Stage-1
  // tests), issue a CONTROL write with bit 0 set mid-loop, poll OFF_STATUS until
  // bit 0 (halted) reads 1, assert retirement genuinely stopped (macroCount via
  // OFF_INST_LO stays constant across N further cycles), read OFF_PC and confirm
  // it is a PLAUSIBLE loop-body address (not 0, not garbage), then clear bit 0
  // and confirm macroCount resumes incrementing.
}
```

- [ ] **Step 7: Compile + full lock-step regression + full ported-corpus spot check**

```
~/sbt/bin/sbt compile
~/sbt/bin/sbt "testOnly m68k040.debug.DebugCtrlPluginSpec"
~/sbt/bin/sbt "testOnly m68k040.ExecuteLockStepSpec"   # expect 399/400
```

- [ ] **Step 8: Commit**

```bash
git add src/main/scala/m68k040/debug/DebugCtrlPlugin.scala src/main/scala/m68k040/top/FullCoreSynth.scala
git commit -m "debug: wire OFF_CONTROL bit 0 manual halt/resume end-to-end (Stage 2 task 6)"
```

---

## Task 7: `OFF_HALT_AFTER_LO/HI` — halt after N macro-instructions

**Files:**
- Modify: `src/main/scala/m68k040/debug/DebugCtrlPlugin.scala`
- Modify: `src/main/scala/m68k040/rob/RobPlugin.scala`
- Modify: `src/main/scala/m68k040/top/FullCoreSynth.scala`
- Test: `src/test/scala/m68k040/rob/RobPluginSpec.scala`, `src/test/scala/m68k040/debug/DebugCtrlPluginSpec.scala`

**Interfaces:**
- Consumes: Task 3's `debugMacroCountReg`, Task 5's halt FSM.
- Produces: writing `OFF_HALT_AFTER_LO/HI` then a trigger (reuse `OFF_HALT_CTL`'s currently-undocumented bits — check `debug_regmap.def`'s comment again: `OFF_HALT_CTL` says only "bit 2 clears sticky reason/report latches"; mint bit 0 = "arm halt-after" as this task's own addition to that register's semantics, documented here and in the `.def` file's own comment text, NOT a new offset) arms a pipelined macro-count comparator that halts the core (via the SAME `debugStopRequestIn` mechanism Task 5/6 built) once `debugMacroCountReg` reaches the target.

- [ ] **Step 1: Read spec §6.6 again** (lines 497-506) — "Count comparison may be pipelined; a pending result must carry the count/epoch so it cannot stop on a stale target after host reprogramming." This is the one subtlety in this task: a target write must invalidate any in-flight comparison from a STALE target.

- [ ] **Step 2: `DebugCtrlPlugin` — target registers + epoch**

```scala
val haltAfterTarget = Reg(UInt(64 bits)) init 0; haltAfterTarget.simPublic()
val haltAfterEpoch   = Reg(UInt(8 bits))  init 0; haltAfterEpoch.simPublic()   // bumped on every target write
val haltAfterArmed   = RegInit(False);            haltAfterArmed.simPublic()
// write decode, alongside OFF_RAM_WINDOW_LG2/OFF_MON_SENSE:
is(DebugRegMap.OFF_HALT_AFTER_LO) {
  haltAfterTarget(31 downto 0) := merged(haltAfterTarget(31 downto 0).asBits).asUInt
  haltAfterEpoch := haltAfterEpoch + 1
  haltAfterArmed := False   // a target write disarms until HALT_CTL re-arms it explicitly
}
is(DebugRegMap.OFF_HALT_AFTER_HI) {
  haltAfterTarget(63 downto 32) := merged(haltAfterTarget(63 downto 32).asBits).asUInt
  haltAfterEpoch := haltAfterEpoch + 1
  haltAfterArmed := False
}
is(DebugRegMap.OFF_HALT_CTL) {
  when(wStrb(0)) {
    when(wData(0)) { haltAfterArmed := True }
    // bit 2 (clear sticky latches) -- Task 9 implements this; leave a TODO comment
    // pointing at Task 9 rather than a silent no-op if wData(2) is set here and
    // nothing consumes it yet -- add the field now, drive it False, Task 9 wires it.
  }
}
```

Read `OFF_HALT_AFTER_LO/HI` back from `haltAfterTarget`'s two halves (mirror the existing `is(DebugRegMap.OFF_RAM_WINDOW_LG2)`-shaped read arms).

- [ ] **Step 3: Export target+epoch+armed to `RobPlugin`**

Same `out(Bool())`/`out(UInt(...))` pattern as `debugStopRequestOut` (Task 6 Step 1):

```scala
val haltAfterTargetOut = out(UInt(64 bits)); haltAfterTargetOut := haltAfterTarget
val haltAfterEpochOut  = out(UInt(8 bits));  haltAfterEpochOut  := haltAfterEpoch
val haltAfterArmedOut  = out(Bool());        haltAfterArmedOut  := haltAfterArmed
```

- [ ] **Step 4: `RobPlugin` — the comparator**

```scala
val haltAfterTargetIn = UInt(64 bits); haltAfterTargetIn.allowOverride; haltAfterTargetIn := U(0, 64 bits)
val haltAfterEpochIn  = UInt(8 bits);  haltAfterEpochIn.allowOverride;  haltAfterEpochIn  := U(0, 8 bits)
val haltAfterArmedIn  = Bool();        haltAfterArmedIn.allowOverride;  haltAfterArmedIn  := False
haltAfterTargetIn.simPublic(); haltAfterEpochIn.simPublic(); haltAfterArmedIn.simPublic()

// Pipelined: register the target/epoch/armed the cycle they're sampled, so the
// actual comparison is against a STABLE snapshot even if the host writes a new
// target the same cycle the comparison is evaluated. The epoch check is what
// spec 6.6 requires: a comparison result computed against epoch E must never be
// allowed to fire once the live epoch has moved past E (a stale in-flight
// compare from a superseded target).
val haltAfterTargetReg = RegNext(haltAfterTargetIn) init 0
val haltAfterEpochReg  = RegNext(haltAfterEpochIn)  init 0
val haltAfterArmedReg  = RegNext(haltAfterArmedIn && (haltAfterEpochIn === haltAfterEpochReg)) init False
val haltAfterHit = haltAfterArmedReg && (debugMacroCountReg >= haltAfterTargetReg)
when(haltAfterHit) { debugStopRequestIn := True }   // NOTE: this OVERRIDES the allowOverride
                                                     // default set by BackendWiringPlugin's
                                                     // Task-6 wiring -- confirm SpinalHDL's
                                                     // last-assignment-wins applies correctly
                                                     // here too (both are `:=` into the same
                                                     // signal from within the SAME Area/build
                                                     // scope now, not two different plugins
                                                     // racing an allowOverride -- if this causes
                                                     // an "already driven" elaboration error,
                                                     // OR debugStopRequestIn with the
                                                     // BackendWiringPlugin-driven level instead
                                                     // of assigning twice).
```

(Flag the last comment's uncertainty explicitly to the implementer — this is exactly the kind of thing the task reviewer should double-check by actually compiling it, not by trusting the plan's prose.)

- [ ] **Step 5: Directed tests**

```scala
test("halt-after fires exactly at the target macro count, not before, not one late") {}
test("halt-after re-armed with a NEW lower target after the OLD target was already exceeded does not immediately fire on stale epoch") {}
test("halt-after target write while a comparison is in flight does not fire against the old target") {}
```

- [ ] **Step 6: Compile + regression**

```
~/sbt/bin/sbt compile
~/sbt/bin/sbt "testOnly m68k040.rob.RobPluginSpec"
~/sbt/bin/sbt "testOnly m68k040.debug.DebugCtrlPluginSpec"
~/sbt/bin/sbt "testOnly m68k040.ExecuteLockStepSpec"   # expect 399/400
```

- [ ] **Step 7: Commit**

```bash
git add src/main/scala/m68k040/debug/DebugCtrlPlugin.scala src/main/scala/m68k040/rob/RobPlugin.scala src/main/scala/m68k040/top/FullCoreSynth.scala src/test/scala/m68k040/rob/RobPluginSpec.scala src/test/scala/m68k040/debug/DebugCtrlPluginSpec.scala
git commit -m "debug+rob: OFF_HALT_AFTER_LO/HI epoch-tagged halt-after (Stage 2 task 7)"
```

---

## Task 8: Single-step (`OFF_CONTROL` bit 1)

**Files:**
- Modify: `src/main/scala/m68k040/rob/RobPlugin.scala`
- Modify: `src/main/scala/m68k040/debug/DebugCtrlPlugin.scala`
- Test: `src/test/scala/m68k040/rob/RobPluginSpec.scala`

**Interfaces:**
- Consumes: Task 5's `HALTED` state, Task 4's `h0IsMacroLast`.
- Produces: a `STEP_RUNNING` state added to `DebugHaltState`; `OFF_CONTROL` bit 1 real.

- [ ] **Step 1: Read spec §6.5 again** (lines 483-495) — "Step starts only from effective halt... allows exactly one macro-instruction to complete... does not use a retired-uop counter." Reject (spec: "sticky rejected status") if not halted.

- [ ] **Step 2: Extend the enum and FSM**

```scala
object DebugHaltState extends SpinalEnum {
  val RUNNING, STOP_PENDING, RECOVER, HALTED, STEP_RUNNING = newElement()
}
```

```scala
val debugStepRequestIn = Bool(); debugStepRequestIn.allowOverride; debugStepRequestIn := False
debugStepRequestIn.simPublic()
val debugStepRejected = RegInit(False); debugStepRejected.simPublic()   // sticky, spec 6.5

// in the switch:
is(DebugHaltState.HALTED) {
  when(debugResumeRequestIn) { debugHaltState := DebugHaltState.RUNNING }
  .elsewhen(debugStepRequestIn) {
    debugHaltState := DebugHaltState.STEP_RUNNING
    debugStepRejected := False
  }
}
is(DebugHaltState.STEP_RUNNING) {
  // Exactly one macro: the FIRST macro to retire while in this state closes the
  // boundary immediately (h0IsMacroLast on ITS OWN first-retiring uop, not
  // waiting for a SEPARATE later macro) -- reuse debugStopBoundaryHit's shape but
  // scoped to THIS state so a plain STOP_PENDING boundary and a STEP boundary
  // never conflate.
  when(headReady && retire0 && h0IsMacroLast) { debugHaltState := DebugHaltState.RECOVER }
}
// Reject if step requested while not HALTED:
when(debugStepRequestIn && (debugHaltState =/= DebugHaltState.HALTED)) { debugStepRejected := True }
```

`headReady`'s existing gate already allows retirement to proceed normally in `STEP_RUNNING` (it only blocks on `HALTED`) — but `retire1` must be suppressed ENTIRELY while stepping (spec: "block a second macro in the same dual-retire cycle" — stronger than the plain stop-boundary case, which only blocks h1 if it's a DIFFERENT macro; single-step blocks h1 unconditionally once h0 alone would already close the step). Extend `retire1`'s guard again:

```scala
val retire1 = /* ...Task 5's version... */ &&
  !(debugStopRequestIn && h0IsMacroLast) &&
  !(debugHaltState === DebugHaltState.STEP_RUNNING)   // NEW: step never dual-retires, full stop
```

`RECOVER`'s existing transition to `HALTED` (Task 5) is unconditionally reused — a step's RECOVER is observationally identical to a stop's RECOVER (both just mean "roll back speculative state, land on the restart PC"), so no new RECOVER arm is needed, only a way for `OFF_HALT_REASON` (Task 9) to remember it was a STEP that caused this particular HALTED entry, not a manual stop.

- [ ] **Step 3: `DebugCtrlPlugin` — bit 1 real, bit 7 (step-arm observation) still RAZ/WI or real**

```scala
// was: False ## // bit 1 single-step pulse -- RAZ/WI until Stage 2
stepPulse ##  // bit 1 single-step pulse (Stage 2: real, self-clearing like coldPulse)
```

```scala
val stepPulse = RegInit(False); stepPulse.simPublic()
stepPulse := False   // self-clearing default, same convention as coldPulse
// in write decode:
when(m(1)) { stepPulse := True }
```

Bit 7 (legacy step-arm OBSERVATION) — check spec §3.3's exact bit-7 semantics before deciding whether Stage 2 needs to implement it or can leave it RAZ/WI; the spec text this plan already read (§15.2) marks it "RAZ/WI (Stage 2)" ambiguously (the parenthetical could mean "implemented in Stage 2" OR "still RAZ/WI as of Stage 2, deferred further" — re-read spec §3.3's own bit-7 description, not just §15.2's table, to resolve this before writing Step 3's code; if genuinely ambiguous even after re-reading, leave bit 7 RAZ/WI and note the deferral explicitly in the commit message rather than guessing).

- [ ] **Step 4: Directed tests**

```scala
test("single-step from HALTED executes exactly one macro (single-uop) then re-halts") {}
test("single-step from HALTED executes exactly one CRACKED macro (all its uops) then re-halts, never partway") {}
test("single-step while RUNNING (not halted) is rejected, sticky rejected status set, core does not step or halt") {}
test("single-step never dual-retires across the step boundary even when h1 could otherwise complete the same cycle") {}
```

- [ ] **Step 5: Compile + regression**

```
~/sbt/bin/sbt compile
~/sbt/bin/sbt "testOnly m68k040.rob.RobPluginSpec"
~/sbt/bin/sbt "testOnly m68k040.debug.DebugCtrlPluginSpec"
~/sbt/bin/sbt "testOnly m68k040.ExecuteLockStepSpec"   # expect 399/400
```

- [ ] **Step 6: Commit**

```bash
git add src/main/scala/m68k040/rob/RobPlugin.scala src/main/scala/m68k040/debug/DebugCtrlPlugin.scala src/test/scala/m68k040/rob/RobPluginSpec.scala
git commit -m "rob+debug: single-step OFF_CONTROL bit 1, STEP_RUNNING state (Stage 2 task 8)"
```

---

## Task 9: `OFF_HALT_REASON` + fatal-halt distinctness + resume rejection

**Files:**
- Modify: `src/main/scala/m68k040/rob/RobPlugin.scala`
- Modify: `src/main/scala/m68k040/debug/DebugCtrlPlugin.scala`
- Test: `src/test/scala/m68k040/rob/RobPluginSpec.scala`

**Interfaces:**
- Consumes: `coreHalted` (existing fatal mechanism), Task 5/8's `debugHaltState`.
- Produces: `_debugHaltReason` real (NONE=0, MANUAL=1, STEP=2, HALT_AFTER=3, FATAL=4 — a NEW, small, debug-ctrl-local encoding, distinct from `HaltReason.scala`'s own `W=3` fatal-cause codes, which stay exactly as they are: `HaltReason` answers "which SUBSYSTEM fatally halted", this new field answers "was this a debug stop, a step, a halt-after, or a fatal halt" at the OFF_HALT_REASON register level); resume rejected while `coreHalted`.

- [ ] **Step 1: Read spec §6.3's last paragraph again** ("Fatal coreHalted/double-fault state also sets effective halt, but it is non-resumable and has a distinct reason. A resume request is explicitly rejected until CPU reset.") and §13's "a resume request cannot release a fatal halt or an apply/cache BUSY state" — this task's whole job is making both literally true.

- [ ] **Step 2: `_debugEffectiveHalt` includes `coreHalted`**

Task 5's Step 2 defined `_debugEffectiveHalt := debugHalted` (i.e. only the debug FSM's own `HALTED` state). Fix:

```scala
_debugEffectiveHalt := debugHalted || coreHalted
```

- [ ] **Step 3: Track WHY, latch first-wins like `coreHalted`/`haltReason` already do**

```scala
object DebugHaltReasonCode {
  val NONE = 0; val MANUAL = 1; val STEP = 2; val HALT_AFTER = 3; val FATAL = 4
}
val debugHaltReasonReg = Reg(UInt(3 bits)) init 0; debugHaltReasonReg.simPublic()
// Captured the SAME cycle debugRecoverEnter fires (Task 5), mirroring haltReason's
// own first-wins-on-the-cycle-it-becomes-true discipline -- but here "first" means
// "which of the 3 debug causes was active when RECOVER was entered", since only
// ONE of {plain stop, step, halt-after} can be the reason a given RECOVER happened
// (they are mutually exclusive states this cycle by construction: STOP_PENDING vs
// STEP_RUNNING are different debugHaltState values, and halt-after only ever SETS
// debugStopRequestIn, funneling through the SAME STOP_PENDING path -- so it needs
// its OWN tag captured separately, not inferred from debugHaltState alone).
when(coreHaltedIn && !coreHalted) {
  debugHaltReasonReg := U(DebugHaltReasonCode.FATAL, 3 bits)   // first-wins, fatal always overrides on ITS OWN entry cycle
}.elsewhen(debugRecoverEnter) {
  debugHaltReasonReg := Mux(debugHaltState === DebugHaltState.STEP_RUNNING, U(DebugHaltReasonCode.STEP, 3 bits),
                        Mux(haltAfterHit,                                   U(DebugHaltReasonCode.HALT_AFTER, 3 bits),
                                                                             U(DebugHaltReasonCode.MANUAL, 3 bits)))
}
_debugHaltReason := Mux(coreHalted, U(DebugHaltReasonCode.FATAL, 3 bits), debugHaltReasonReg)
```

- [ ] **Step 4: Resume rejection while fatally halted**

Task 5's `HALTED` arm (`when(debugResumeRequestIn) { debugHaltState := DebugHaltState.RUNNING }`) must not fire while `coreHalted`:

```scala
is(DebugHaltState.HALTED) {
  when(debugResumeRequestIn && !coreHalted) { debugHaltState := DebugHaltState.RUNNING }
  .elsewhen(debugStepRequestIn && !coreHalted) { ... }
}
```

(`coreHalted` already independently blocks retire via `headReady`'s pre-existing `!coreHalted` term, so this Step is specifically about not letting the FSM's OWN state pretend to leave HALTED — without it, `debugHaltState` would flip to `RUNNING` while `coreHalted` silently keeps everything blocked anyway, which is a LATENT bug, not a live one, but still exactly what spec §13 says must not be possible: "cannot release a fatal halt" should mean the STATE says so too, not just the retire gate.)

- [ ] **Step 5: `OFF_HALT_REASON` register read**

```scala
is(DebugRegMap.OFF_HALT_REASON) {
  rData := B(0, 29 bits) ## dbgCommit.map(_.haltReasonDebug).getOrElse(U(0,3 bits)).asBits
}
```

- [ ] **Step 6: `OFF_HALT_CTL` bit 2 — clear sticky reason/report latches**

Task 7 left a TODO here. Implement it:

```scala
is(DebugRegMap.OFF_HALT_CTL) {
  when(wStrb(0)) {
    when(wData(0)) { haltAfterArmed := True }
    when(wData(2)) { /* signal RobPlugin to clear debugHaltReasonReg + debugStepRejected via a new pulse output, mirroring debugStopRequestOut's shape */ }
  }
}
```

Add `haltCtlClearOut: Bool` (pulse) mirroring `debugStopRequestOut`; in `RobPlugin`, `when(haltCtlClearIn) { debugHaltReasonReg := 0; debugStepRejected := False }` — but NOT while still `HALTED`/`STEP_RUNNING` with an ACTIVE (not yet acknowledged) stop, only the STICKY LATCHES per spec's own wording ("clears sticky reason/report latches" — a report of a PAST event, not the live state); confirm against spec text whether clearing while still halted is even meaningful/expected, and if the spec is silent, the safe choice is: allow the clear unconditionally (it only clears the REASON CODE display and the rejected-sticky bit, neither of which gates retire/halt behavior itself, so clearing them while halted cannot cause a live-state corruption — verify this claim against the actual consumers before shipping it, don't just assert it).

- [ ] **Step 7: Directed tests**

```scala
test("OFF_HALT_REASON reads MANUAL after a plain stop, STEP after a step, HALT_AFTER after a halt-after trigger") {}
test("fatal coreHalted sets OFF_HALT_REASON to FATAL regardless of any concurrent debug-halt state") {}
test("resume request while coreHalted is a no-op: debugHaltState stays HALTED, effectiveHalt stays True, forever") {}
test("OFF_HALT_CTL bit 2 clears the sticky reason back to NONE without disturbing an ACTIVE halt") {}
```

- [ ] **Step 8: Compile + regression**

```
~/sbt/bin/sbt compile
~/sbt/bin/sbt "testOnly m68k040.rob.RobPluginSpec"
~/sbt/bin/sbt "testOnly m68k040.debug.DebugCtrlPluginSpec"
~/sbt/bin/sbt "testOnly m68k040.ExecuteLockStepSpec"   # expect 399/400
```

- [ ] **Step 9: Commit**

```bash
git add src/main/scala/m68k040/rob/RobPlugin.scala src/main/scala/m68k040/debug/DebugCtrlPlugin.scala src/test/scala/m68k040/rob/RobPluginSpec.scala
git commit -m "rob+debug: OFF_HALT_REASON + fatal-halt distinctness + resume rejection (Stage 2 task 9)"
```

---

## Task 10: `OFF_HALT_HIT_INST_LO/HI` + finish `OFF_STATUS` bit 1 scope decision

**Files:**
- Modify: `src/main/scala/m68k040/rob/RobPlugin.scala`, `src/main/scala/m68k040/debug/DebugCtrlPlugin.scala`
- Test: `src/test/scala/m68k040/rob/RobPluginSpec.scala`

- [ ] **Step 1: Capture `haltHitInstCount` at RECOVER**

```scala
when(debugRecoverEnter) { debugHaltHitInstCountReg := debugMacroCountReg }
_debugHaltHitInstCount := debugHaltHitInstCountReg
```

(`debugMacroCountReg`'s value the cycle RECOVER is entered — confirm it already reflects the JUST-retired boundary macro's own increment, i.e. this capture happens AFTER Task 3's `debugMacroCountReg` update for the SAME cycle, not before — check elaboration order; if `debugMacroCountReg`'s own `when` block is textually earlier in `logic` than this Task 10 addition, last-assignment-wins is irrelevant here since they write DIFFERENT registers, but READ ordering (this Task reads `debugMacroCountReg`'s NEW value, not stale) is automatic in synchronous logic as long as this read is combinational off the SAME cycle's `debugMacroCountReg` register value pre-update — i.e. this captures the PRE-increment count, not post-increment, since Regs read their OLD value combinationally within the same cycle their next value is being computed. Decide explicitly whether `haltHitInstCount` should be inclusive or exclusive of the boundary macro itself and document the choice — the spec doesn't say; a reasonable, defensible choice is "count AFTER including the macro that just retired" i.e. wire this from `_debugMacroCount`'s COMBINATIONAL next-value expression, not the stale register — implement whichever you choose and write ONE line explaining why.)

- [ ] **Step 2: Wire `OFF_HALT_HIT_INST_LO/HI`** — same two-32-bit-half read pattern as every other 64-bit register in this file (`OFF_INST_LO/HI`, Task 3).

- [ ] **Step 3: Resolve `OFF_STATUS` bit 1 (exception pending) scope**

Spec §12's Stage 2 bullet does not mention exception-pending; §6.6 mentions "Halt-on-exception uses the 256-bit deployed mask" which IS explicitly Stage 5 (`halt_exc_mask` feature bit 7, stage 5 per `debug_regmap.def`). Confirm bit 1 stays `False` (RAZ) through Stage 2 — no code change needed, just an explicit verification step and a one-line comment in `DebugCtrlPlugin.scala` at that bit's position saying "Stage 5 (halt_exc_mask)" instead of the current bare `False` with no forward-reference, so a future reader isn't left wondering why it's still zero after Stage 2 shipped.

- [ ] **Step 4: Directed test + compile + regression + commit** (same shape as every prior task).

```bash
git add -A
git commit -m "rob+debug: OFF_HALT_HIT_INST_LO/HI + OFF_STATUS bit 1 scope note (Stage 2 task 10)"
```

---

## Task 11: Required assertions (spec §13, Stage-2-applicable subset)

**Files:**
- Modify: `src/main/scala/m68k040/rob/RobPlugin.scala`

- [ ] **Step 1: Add `GenerationFlags.simulation`-gated assertions**, placed near the existing ones in this file (there is precedent: `RobPlugin` itself has no `GenerationFlags.simulation` block today per the explore pass's findings — `DebugCtrlPlugin.scala` does, at lines 382-417 and 434-441; follow THAT file's placement/`FAILURE` convention exactly since it's the more directly analogous precedent for a debug-adjacent assertion, even though it lives in a different file):

```scala
GenerationFlags.simulation {
  assert(!(_debugEffectiveHalt && (retire0 || retire1)),
    "RobPlugin: retirement occurred while DebugCommitService.effectiveHalt was True (spec section 13)",
    FAILURE)
  assert(!(debugHaltState === DebugHaltState.HALTED && (headReady)),
    "RobPlugin: headReady asserted while HALTED (spec section 13: effective halt implies no retire)",
    FAILURE)
  // "a step consumes at most one macro event and retire slot 1 never crosses its boundary"
  assert(!(debugHaltState === DebugHaltState.STEP_RUNNING && retire1),
    "RobPlugin: retire1 fired during STEP_RUNNING -- a step must never dual-retire (spec section 13)",
    FAILURE)
  // "a resume request cannot release a fatal halt"
  assert(!(coreHalted && (debugHaltState === DebugHaltState.RUNNING)),
    "RobPlugin: debugHaltState left HALTED while coreHalted -- fatal halt was released (spec section 13)",
    FAILURE)
}
```

Also add the frontend-admission half of "effective halt implies no frontend/dispatch admission" — this requires extending `_frontendQuiesceActive`'s own driving line (`_frontendQuiesceActive := stopped || coreHalted`) to also OR in `_debugEffectiveHalt`, which is itself a REAL BEHAVIORAL change this task must make (not just an assertion) — without it, a debug-halted core would still let the frontend admit new fetch/decode work, silently violating spec §6.1's "forbid retire from crossing into a new macro... stop new frontend admission" the moment `debugHaltState` reaches `STOP_PENDING`, not just `HALTED`:

```scala
_frontendQuiesceActive := stopped || coreHalted || (debugHaltState =/= DebugHaltState.RUNNING)
```

(`STOP_PENDING`/`RECOVER`/`HALTED`/`STEP_RUNNING` ALL want frontend admission stopped — only `RUNNING` doesn't. `_frontendQuiesceNext` needs the analogous next-state extension; find its own driving site — the explore pass did not separately capture it, locate it now, it is almost certainly right next to `_frontendQuiesceActive`'s own drive — and extend it the same way using the FSM's NEXT-state expression, not the registered one, matching `FrontendQuiesceService`'s own documented `next`-vs-`active` distinction (`Services.scala:41-48`.)

- [ ] **Step 2: Compile + full lock-step + full ported-corpus regression**

This task changes REAL behavior (`_frontendQuiesceActive`'s new OR term), unlike every prior task's inert-by-default framing — run the FULL verification sweep, not just the fast lock-step:

```
~/sbt/bin/sbt compile
~/sbt/bin/sbt "testOnly m68k040.rob.RobPluginSpec"
~/sbt/bin/sbt "testOnly m68k040.ExecuteLockStepSpec"   # expect 399/400 -- debugHaltState stays RUNNING for every existing test (debugStopRequestIn/debugStepRequestIn both default False), so this extension is a no-op in practice, but VERIFY, don't assume
# Full ported corpus, isolated worktree, per project standing verification methodology:
git worktree add /tmp/debug-stage2-task11-after HEAD
cd /tmp/debug-stage2-task11-after && ~/sbt/bin/sbt "testOnly m68k040.PortedTestRunner"   # or whatever the actual full-corpus runner target is named -- confirm the exact sbt target before running
```

- [ ] **Step 3: Commit**

```bash
git add src/main/scala/m68k040/rob/RobPlugin.scala
git commit -m "rob: Stage-2 required assertions (spec section 13) + frontend-quiesce extension to debug halt states (Stage 2 task 11)"
```

---

## Task 12: Advertise features 22/23, bump `stage` to 2 in the shipped netlist target

**Files:**
- Modify: `src/main/scala/m68k040/top/FullCoreSynth.scala`
- Test: `src/test/scala/m68k040/debug/DebugCtrlPluginSpec.scala` (feature-honesty check)

- [ ] **Step 1: `FullCoreSynth.scala:580`**

```scala
new m68k040.debug.DebugCtrlPlugin(buildId = dbgBuildId, stage = 2)
```

- [ ] **Step 2: Directed test — the feature-honesty rule actually holds**

Per spec §3.4 ("No feature bit may advertise a tied-off counter, stale shadow, placeholder probe, or operation that can be silently dropped") and the Stage 0/1 plan's own precedent test for this (locate it — Stage 1 almost certainly already has a "features read `featuresForStage(stage)` and every advertised bit has SOME real behavior" test; extend it rather than writing a parallel one):

```scala
test("stage=2: OFF_FEATURES advertises bits 22 (macro_retire_count) and 23 (stop_status_v2), and both are genuinely reachable") {
  // read OFF_FEATURES, assert bits 22 and 23 set; then independently exercise
  // OFF_INST_LO incrementing (macro_retire_count) and a manual halt actually
  // reaching effective-halt semantics (stop_status_v2) THROUGH THE SAME TEST,
  // proving the bits aren't just set but backed by real behavior.
}
```

- [ ] **Step 3: Compile + full lock-step + regenerate the shipped netlist + verilator lint**

```
~/sbt/bin/sbt compile
~/sbt/bin/sbt "testOnly m68k040.debug.DebugCtrlPluginSpec"
~/sbt/bin/sbt "testOnly m68k040.ExecuteLockStepSpec"   # expect 399/400
~/sbt/bin/sbt "runMain m68k040.top.GenFullCoreSynth"
make lint-fpga-top CPU=m68k040   # from the macqd700-soc worktree if reachable
```

- [ ] **Step 4: Commit**

```bash
git add src/main/scala/m68k040/top/FullCoreSynth.scala src/test/scala/m68k040/debug/DebugCtrlPluginSpec.scala
git commit -m "debug: ship Stage 2 -- bump stage=2, advertise features 22/23 (Stage 2 task 12)"
```

---

## Task 13: Full-corpus regression + Verilator full-SoC lint (both `enable=true` and `enable=false`)

**Files:** none modified — verification only.

- [ ] **Step 1: Full lock-step, exact baseline check**

```
~/sbt/bin/sbt "testOnly m68k040.ExecuteLockStepSpec"
```
Confirm exactly the 4 pre-existing known failures, byte-identical names to this plan's Global Constraints list (re-verify the CURRENT names first — they may have drifted since this plan was written).

- [ ] **Step 2: Full ported corpus, isolated worktree, before/after diff**

```bash
git worktree add /tmp/debug-stage2-before main   # or whatever the correct pre-plan reference commit is -- confirm with git log before running
git worktree add /tmp/debug-stage2-after HEAD
# run the full corpus in both, diff fail lists by TEST NAME (not raw count -- this
# project's own standing lesson, repeated across multiple prior plans' ledgers)
```
Zero new failures expected — this whole plan is additive and every new path defaults inert unless a host actively drives `dbg_axi`.

- [ ] **Step 3: Full-SoC Verilator lint from the macqd700-soc worktree** (if reachable at execution time; if the worktree from this session's earlier work — `/home/qwertyoruiop/macqd700-soc-worktrees/m68k040ooo-integration` — still exists and has its `cpu040` submodule pointed at a commit at or after this plan's merge, regenerate `M68kSocketTop.v` there and re-run `make lint-fpga-top CPU=m68k040`; if not reachable, note this explicitly as deferred rather than skipping silently).

- [ ] **Step 4: Clean up worktrees, record results** in a progress note (this plan's own ledger, if using subagent-driven-development) before proceeding to Task 14.

---

## Task 14: Full-core OOC synth gate (project standing rule) — `enable=true` and `enable=false`, report against spec §11

**Files:** none modified — verification only.

- [ ] **Step 1: Re-read the current FMax reference** — do NOT trust this plan's own Global Constraints section's number; find the most recently recorded post-route number on `fmax-closure-fanout` at EXECUTION time (check `.superpowers/sdd/`-style progress notes, or the most recent commit message mentioning a WNS/FMax figure, e.g. commit `5aae2c5` "197.278 MHz" per this session's own git log — but confirm nothing more recent has landed since).

- [ ] **Step 2: Confirm no Vivado/JTAG session is active** (`/var/tmp/m68k-ooo-vivado.lock`, per this project's standing rule — check before launching, this session's own earlier investigation found the shared lock genuinely contended more than once).

- [ ] **Step 3: OOC synth, `enable=true`** (the shipped `M68kFullCoreSynth.v` from Task 12) — report LUT/FF/DSP/BRAM utilization and WNS/FMax. **Gate (user directive, 2026-08-20, supersedes spec §11's raw +2% FMax ceiling for this build only): pass if post-route FMax clears ~100MHz with real margin** — this is the real target the current macqd700-soc bitstream work already runs the JTAG-enabled build at (`CORE_CLK_HZ=100_000_000`), not a synthetic reference number. LUT/FF/DSP/BRAM deltas against the Step 1 reference are still reported (not gated numerically at +1.0%, since the whole point of this build is to carry real new logic) but must still show no DSP/BRAM usage from this plan's own additions (spec §11 rule 6, "no debug function uses DSPs" — that part of §11 is NOT superseded).

- [ ] **Step 4: OOC synth, `enable=false`** (`M68kFullCoreSynthNoDebug.v` from Task 1) — this is the production target and spec §11's numbers are fully binding here, unchanged by the user's `enable=true` relaxation. **Gate: FMax clears ~200MHz class** (the current FMax-campaign reference re-read in Step 1 — e.g. the session's own recent 197.278MHz-class result — within spec §11's 2% band), LUT/FF within +1.0% of the pre-Stage-2 reference, no DSP/BRAM beyond what already existed. This build should be very close to byte-identical to the CURRENT `main`-branch reference (modulo Stage 1's own already-accepted cost from the Stage 0/1 plan's own gate) since Task 1 gates every line this plan adds behind `if (enable)`.

- [ ] **Step 5: Check the timing report's worst path is not a debug comparator/CSR mux/high-fanout debug enable** (spec §11's explicit named failure mode) — if it is, that is a REAL finding requiring a fix task, not a note; do not accept the gate with this violation present.

- [ ] **Step 6: Report absolute numbers for both builds**, not just deltas (spec §11: "Report absolute utilization and deltas for every implemented tranche").

- [ ] **Step 7: If the gate passes, this plan is DONE.** If it fails, dispatch a targeted fix task (pipeline the offending path per spec §11 rule 1-4) and re-gate — do not merge a failing gate and defer it, per this project's `[[synth-gate-every-slice]]` standing rule.
