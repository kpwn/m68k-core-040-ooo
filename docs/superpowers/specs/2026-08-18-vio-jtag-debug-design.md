# Vivado VIO integration into the JTAG debug feature set — design

**Status:** PROPOSED / DESIGN ONLY. All decisions in §0 are locked (prior verified
investigation + explicit user direction); none is implemented. Direct input to a future
`writing-plans` pass.

**Date:** 2026-08-18

**Scope:** adding a Vivado VIO (Virtual Input/Output) observation and actuation path to this
core's JTAG debug feature set, alongside — never instead of — the `dbg_axi` debug/control
slave. In scope: the core-side probe export group and the new RTL it needs (a committed-PC
latch, a saturating retire counter, a reset-less heartbeat, and the mandatory `probe_out`
edge discipline); the deliberate, explicitly-bounded exception this group takes to the
socket-adapter spec's D23; the socket-contract group that carries it; and the
`macqd700-soc`-side wiring, IP-config and host-script work that consumes it.

**Primary source:** the verified scoping pass
`~/.claude/projects/-home-qwertyoruiop-m68k-core-040-ooo/memory/vio-jtag-debug-scoping-2026-08-18.md`,
cross-checked against a live, working VIO instance in `macqd700-soc` rather than from docs
alone. Citations below were independently re-confirmed against HEAD `5b3cc74` and against
`macqd700-soc` at its current checkout while writing this spec; the six places where this
spec **corrects, extends or contradicts** that source are called out explicitly in §10.

**Dispatched under** the standing `/goal` session directive: *"shipping a comprehensive jtag
debugging feature set is key, and VIO should be used as well."*

**Related specs, and the seams this one touches:**

- `docs/superpowers/specs/2026-08-09-debug-ctrl-jtag-repl-design.md` — the `dbg_axi`
  debug/control slave. Owns halt/step, the register map, and all CSR-visible state. This
  spec adds nothing to it and reinterprets nothing in it.
- `docs/superpowers/specs/2026-08-18-axi-socket-adapter-design.md` — the socket contract.
  Owns **D23** (the port-surface rule this spec takes an exception to) and **D28** (the
  halt-reason channel this spec *consumes* and does not build).
- `docs/superpowers/plans/2026-08-17-debug-ctrl-stage0-stage1.md` — Stage 0 + Stage 1.
  Tasks 1-12 are merged; Task 13 (the synth acceptance gate) is the only step outstanding.
  This spec's staging (§9) is sequenced against that gate.

---

## 0. Decision summary

Every numbered **DECIDED** item below is citable by a future implementation plan without
re-deriving it. Items tagged **NOTED** record a confirmed non-problem or an accepted
residual; items tagged **VSOC** must be executed in `macqd700-soc`, not here; items tagged
**DEFERRED** are deliberately out of scope with a named owner.

Decisions are prefixed **V** and SoC items **VSOC** so that no number in this spec can be
confused with the axi-socket spec's `D1`-`D30` / `SOC-1`-`SOC-4` or the debug-ctrl spec's
own numbering. Cross-spec references are always written with the owning spec's prefix
(`D23`, `D28`, `SOC-2`, …).

### Scope and contract

| # | Decision |
|---:|---|
| **V1** | VIO is an **independent bootstrap/recovery and liveness path**, complementary to `dbg_axi`, never a replacement for it. The overlap between the two (PC, retire count) is deliberate redundancy, justified by a real recovery event, not duplication to be minimised. (§1) |
| **V2** | A single, **explicitly enumerated `vio_*` export group** is granted an exception to axi-socket **D23** ("the socket top exports only socket ports"). It is the *only* exception; the 37 probe/test ports of `M68kFullCoreSynth` stay off the socket top exactly as D23 requires. The group is declared in `cpu_socket.vh` as a numbered socket group, not smuggled through as unnamed wires. (§2) |
| **V3** | The group is **always present and ungated** on the socket top. It is not `ifdef`-gated there. Rationale: a gate is what produced the documented bit-rot precedent, and the group's *core-side* cost is ~120 FF — the ~3.0 LUT/5.4 FF-per-bit cost is the VIO **IP's**, which lives SoC-side, where the `ifdef` that controls it already exists. Gate where the cost is, not where the wires are. (§2.2) |
| **V4** | On `M68kFullCoreSynth` the group is behind an elaboration-time **constructor parameter, default `false`**, so that target's port surface stays byte-for-byte what axi-socket D23 froze it as. A dedicated generation target with the parameter `true` exists solely for the cost-delta measurement and for the netlist checker's both-configurations test. (§2.3) |
| **V5** | **`M68kSocketTop` does not exist as code today** — verified: `src/main/scala/m68k040/top/` contains only `DecodeUopInputPlugin`, `ExecSynthProbes`, `FullCoreSynth`, `GenVerilog`, `SynthProbePlugin`, and the string `M68kSocketTop` occurs in this repository **only inside the axi-socket spec**. This spec is therefore written against `FullCoreSynth` as the concrete target, with the socket top as a named forward dependency. The probe logic is a self-contained plugin so the same code serves both. (§2.4) |
| **V6** | Implementation vehicle: one new `VioProbePlugin` (a `FiberPlugin`), not wiring scattered through `BackendWiringPlugin`. A socket-contract exception must be auditable in one file. (§2.5) |
| **V7** | **Frozen-field discipline**, borrowed verbatim from the debug regmap's frozen-offset rule: a probe's name, index, width and bit-field assignment are reserved forever once published. Reserved fields read zero and are never repurposed. Fields are append-only into reserved space. (§3.1) |

### Core-side probe design

| # | Decision |
|---:|---|
| **V8** | **One coherent bundle.** Every field whose mutual consistency an operator would reason about is carried in a *single* probe, `vio_cpu_snapshot`, all of whose fields are driven by registers updated on the same core-clock edge. Cross-probe reads are never assumed coherent — VIO's real, documented gotcha #1. (§3.2, §3.3) |
| **V9** | The bundle is provisioned at **96 bits** with 23 bits reserved-reads-zero, deliberately over-wide. Probe widths are baked into the IP config at generation time (gotcha #3), so growth room bought now costs ≈64 LUT / ≈117 FF and saves an IP regeneration, a marker bump and a stale-cache risk later. Stated as a bought trade, not an accident; the width also matches the deployed 96-bit `video_dbg_snap` precedent (NOTED-4). (§3.2) |
| **V10** | **Standalone probes are those with no cross-field invariant**: the heartbeat, the build ID, and the two legacy scalars. The heartbeat is *deliberately* outside the bundle — its job is to keep moving when the bundle freezes, which is precisely the case where sharing a capture register with frozen state would destroy its meaning. (§3.4) |
| **V11** | **Last-committed-PC latch — new RTL.** Qualified by `CommitTraceService.traceFire(k)`, **never** by `trace(k).fire`. `RobPlugin.scala:788` defaults `traceVec(k).assignDontCare()` while `:787` defaults `traceFireVec(k) := False`, so the `.fire` field inside the bundle is `x` between retires and the separate `traceFire` vector is concrete. Slot 1 wins over slot 0 when both retire (the younger instruction is the more recent PC). (§3.3.1) |
| **V12** | The latched value is the **POST-instruction PC** (`CommitTrace.scala:10`: "POST-instruction PC (next-instruction addr)"), i.e. the address of the instruction *after* the last one retired. This is a real semantic difference from v1's probe of the same name and must be spelled out in every consumer, because the two are not interchangeable. (§3.3.1) |
| **V13** | **Saturating retire counter — new RTL**, 32-bit, incremented by 0/1/2 per cycle, saturating at `0xFFFF_FFFF`. It **reuses `DebugCtrlPlugin`'s already-reviewed Task-11 pattern** (`DebugCtrlPlugin.scala:185`, `:373-374`) rather than inventing a second saturating-counter idiom: `when(count =/= max) { count := count + n }`, with "zero means nothing has retired since reset, and must not be reachable by wraparound". (§3.3.2) |
| **V14** | The retire counter and the PC latch live in the **core reset domain** (they describe this CPU's life, not the debugger's), matching `DebugCtrlPlugin`'s spec-15.1 classification of "CPU-coupled runtime state" that must not report a previous life as the current one. (§3.3.3) |
| **V15** | **Heartbeat — new RTL**, 32-bit free-running, in a **reset-less (`BOOT`-kind) domain**, reusing `DebugCtrlPlugin`'s existing `porCd` idiom (`DebugCtrlPlugin.scala:79`, rationale at `:426-435`). Reset-less is the whole point: it answers "is the core clock running?" *while reset is held*, which is the one question no `dbg_axi` read and no reset-domain counter can answer. (§3.4.1) |
| **V16** | **`probe_out` edge discipline (mandatory).** No `probe_out` is ever consumed as a level. Each is converted to a one-cycle pulse in RTL, and the edge detector's history register is `RegInit(True)` — reusing `DebugCtrlPlugin.scala:170`'s `cpuRstQ = RegInit(True)` idiom — so a `probe_out` left **high** across a dropped JTAG link produces **no** action when the design comes out of reset. Only a genuine low→high transition acts. (§4.1) |
| **V17** | **Boot-PC injector.** `vio_boot_pc[31:0]` (a level, sampled only when the pulse fires) + `vio_ctl[0]` = `boot_go` (edge-converted per V16), driving the same `FetchAlignPlugin.logic.redirect` seam that is the core's only unstall-from-reset mechanism (`FetchAlignPlugin.scala:585`: `ic.cmd.valid := started && …`, with `started` init `False` at `:189`). This is a zero-software bring-up boot. (§4.2) |
| **V18** | **The injector cannot resurrect a halted core.** `coreHalted` is sticky and forces `headReady` False (`RobPlugin.scala:375-376`, `:585`); a fetch redirect does not clear it. Stated as a limit of this feature, not glossed. Clearing a halt is Stage 2's resume, and belongs to the debug-ctrl spec. (§4.2.1) |
| **V19** | **Interaction with axi-socket D12/D16, named rather than discovered later.** On the socket top, D12 makes the reset-vector fetch real and D23 makes `redirect` *internal*. The VIO injector is then an **additional** redirect source at the same priority as the D12 fetch's own redirect, sequenced after it. It is load-bearing exactly in the cases D12 cannot serve: `enable=false` builds, builds predating D12, and forcing execution at an arbitrary PC on a live-but-idle core. (§4.2.2) |
| **V20** | **`vio_ctl[3:1]` are reserved-reads-zero, never repurposed** (V7). In particular `vio_ctl[1]` is reserved for a future halt/resume request whose *semantics* Stage 2 owns; this spec neither implements nor defines it. (§4.3) |

### Explicitly not built here

| # | Decision |
|---:|---|
| **V21** | **The halt-reason channel is OUT OF SCOPE and is consumed, not built.** axi-socket **D28** already owns building it, from `RobPlugin`'s halt seam outward, and already lists it as new scope in that spec's §11.1 item 2. This spec reserves `halt_reason[3:0]` in the bundle, reading **zero** until D28 lands, and exports the **already-existing** `coreHalted` (`RobPlugin.scala:375`) as a bit today. No second channel is created. (§5.1) |
| **V22** | **v1's dead `dbg_pc` / `dbg_committed` (task #226): the CPU-side half is OUT OF SCOPE; the SoC-side half is IN SCOPE and is shared.** The split is real, not a compromise — see §6 for the evidence. (§6) |
| **V23** | **The ILA group-7 export is NOT adopted** in this work. VIO answers "is it alive and where"; only an ILA answers "what happened in the last N cycles". Those are different instruments with different costs, and bundling them would make this spec's D23 exception unbounded. Recorded as a named follow-on candidate with its own justification already written down, not as an open question. (§8.3) |
| **V24** | **No memory-mapped anything.** VIO reads and drives named nets over BSCAN. This design contains no address decode, no address window, no address-range table, and no "this address was proven backed" inference — conformant with the project's standing rule by construction, and stated explicitly rather than left implicit. (§7) |

### Cost, staging, and the SoC side

| # | Decision |
|---:|---|
| **V25** | **Budget, as a gate not an estimate:** ≈**2.8 LUT / ≈5.1 FF** per `probe_in` **bit**, measured from the routed build (`utilization_route.rpt:110-114` over 792 configured bits), SoC-side in the VIO IP and inflated by the per-bit activity detector. 160 new `probe_in` bits ⇒ ≈**455 LUT / ≈816 FF** ≈ **0.21 %** of the xcku5p's 216 960 LUTs. Core-side new RTL is ≈120 FF and negligible LUT. Both are inside the debug-ctrl plan's own ≤ +1.0 % LUT/FF acceptance gate with an order of magnitude to spare. (§8.1) |
| **V26** | **Capacity caution, recorded so it is not attributed to VIO.** The SoC sits at **83.6 %** LUT utilisation (181 320 / 216 960, `utilization_route.rpt:24` — a derived figure, not a literal string anywhere), of which `u_cpu` alone is 57.6 %. Replacing v1 (~104 k LUT) with this core (~124 k) pushes it back toward **~93 %** *regardless of VIO*. VIO's 0.21 % is not what would break that, but the headroom is not free either, so the Stage-1.5 gate reports **SoC-level absolute utilisation**, not only the core-level delta. (§8.1) |
| **V27** | **Staging: a new Stage 1.5**, sequenced **after** Stage 1's Task 13 acceptance gate lands and **before** Stages 2-7. Not folded into Stage 1 (adding ports mid-gate muddies a measurement about to be taken); not a parallel plan (it shares Stage 1's exact substrate, and two plans racing the same files is precisely how the sibling's ILA group bit-rotted). ~6 tasks, outlined in §9. (§9) |
| **V28** | **Netlist-checker extension is a required deliverable, not a nicety.** `tools/debug/check_debug_netlist.py` is extended to assert the group's **presence** in the enabled configuration and its **absence** in the default one. This is the mechanical guard against the exact, documented bit-rot precedent this design is otherwise repeating. (§9, task 5) |
| **VSOC-1** | `macqd700-soc` must add a **group 8** to `rtl/soc/cpu_socket.vh` declaring the `vio_*` export ports, written in the same form as group 7's existing exception preamble, and `rtl/soc/cpu_stub.v` must declare the identical group tied to constant 0 so the single shared port-connection list binds either way. (§6.2, §8.2) |
| **VSOC-2** | `rtl/soc/fpga_top_debug_ctrl.vh:46-47`'s two `= 32'd0` tie-offs become plain wires bound to the new group-8 ports, restoring `probe_in5` / `probe_in9`. This is the **shared** half of the task-#226 fix and is written once for every core that ever binds the socket. (§6.2) |
| **VSOC-3** | The VIO IP config is bumped for the new probes (`C_NUM_PROBE_IN` 26→29, `C_NUM_PROBE_OUT` 2→4) and the cache marker is **strengthened to cover net identity**, not only count and width. The existing marker's count/width-only check has already produced one real stale-cache bug, and it does not even check every width it writes. (§8.2) |
| **VSOC-4** | The three hand-maintained host-side probe tables gain the new entries, including a **bit-field decode** for `vio_cpu_snapshot` — a 96-bit hex blob is not a debug feature. The existing `dbg_pc`→`probe_in5` / `dbg_committed`→`probe_in9` mappings keep working unchanged and must be regression-checked, not rewritten. (§8.2) |
| **NOTED-1** | `probe_in5` / `probe_in9` are already 32 bits wide and already bound to the two dead wires (`fpga_top_debug_vio.vh:433,437`). Those two need **no width change** — only a real driver. But the cache marker must still be bumped for them (VSOC-3), because it checks count and width and *not* net identity, so a rebind alone is a silent cache hit. Only the genuinely new probes force a `C_NUM_PROBE_IN` change. |
| **NOTED-4** | **There is a working precedent for V8's design in the same VIO instance, arrived at for the same reason.** `probe_in24` is `video_dbg_snap`, a 96-bit *"coherent one-pclk-edge scan-out snapshot … single wide buses rather than more individual probes"* (`rtl/soc/fpga_top_video.vh:143-147`, task #243; bound at `fpga_top_debug_vio.vh:457`), with its bit-field decoder in `tools/jtag_repl.tcl:4786-4800`. The bundle approach, the 96-bit width, and the decoder shape are all already deployed and working here — V8/V9/VSOC-4 follow an established local pattern rather than inventing one. |
| **NOTED-5** | The VIO instance is `ifdef VIO_ENABLE`-gated (`fpga_top_debug_vio.vh:102`/`:471`), but `ENABLE_VIO` defaults to **1** (`Makefile:2221`) and a real bitstream build **requires** it (`synth/vivado.tcl:200-201`: `REAL_FPGA_BUILD=1` full implementation now requires `ENABLE_VIO=1`). So VIO is effectively unconditional on any shipping bitstream — unlike `ENABLE_ILA`, which defaults to **0** (`synth/vivado.tcl:155`) and has no lint-matrix row (`Makefile:1786-1798`). That asymmetry is part of why V23 declines the ILA group. |
| **NOTED-6** | `setup_debug_vio` early-returns for real-MIG builds (`synth/vivado.tcl:1698-1701`), so no VIO net carries `MARK_DEBUG` on a shipping bitstream. This does **not** endanger the group: the core-side values arrive as CPU **output ports**, which the module boundary preserves, and the VIO IP's own ports are `DONT_TOUCH` (`fpga_top_debug_vio.vh:418`). Recorded because an implementer will notice the early return and wonder. |
| **NOTED-2** | VIO introduces no clock-domain crossing of its own: the JTAG↔fabric crossing is internal to the `vio` v3.0 IP, which runs its own ~20 MHz `INTERNAL_TCK` off a `BSCANE2` primitive. `probe_out` outputs are synchronous to the IP's `clk` input, which is `core_clk`, so V16's edge detector is a plain same-domain `RegNext` and needs no synchroniser. No new `.xdc` is required; the shared `dbg_hub` already exists. |
| **NOTED-3** | `create_ip` is mandatory. `create_debug_core` silently coerces to `ila` on the Vivado version in use — a documented failure mode in the sibling repo, recorded here so an implementer does not rediscover it. |

---

## 1. Why VIO, given `dbg_axi` exists (V1)

The two are not redundant, and the division is proven rather than argued.

**VIO reaches the design when `dbg_axi` cannot.** It is reachable when the AXI fabric, the
peripheral bus, or the CPU itself is wedged or dead; it is observable *during* CPU reset; it
requires no correct core RTL beyond the IP itself; and it is the only way to tell whether a
brand-new `dbg_axi` is even being clocked. It needs only a `.bit` and a `.ltx` — no software
stack, no host protocol, no register map version negotiation.

**This is not hypothetical.** `macqd700-soc/.claude/skills/m68k-jtag-wedge-recovery/SKILL.md`
records a live bring-up session on 2026-07-24/25 in which a CSR-based reset left `dbg_axi`
unresponsive: every `rd <addr>` returned non-hex data and the register dumps came back full of
the `0xBADA0BAD` abandoned-transaction sentinel. Before that session, "the only known recovery
was a physical power cycle." VIO — reaching the design over BSCAN rather than through the
AXI-Lite path — stayed reachable and recovered it, confirmed working live on 2026-07-25, with
no power cycle.

Three things about that event shape this design:

1. **The lever was exactly one `probe_out`.** `vio_hard_reset` = `probe_out1`, ORed into
   `platform_reset_req` downstream of the debounced reset button
   (`macqd700-soc/rtl/soc/fpga_top_clocks.vh:270-273`), pulsed by `vio-hard-reset` in the REPL
   (`tools/jtag_repl.tcl:1061-1068`). That lever is **SoC-side and entirely core-agnostic**;
   this spec neither replaces nor duplicates it, and it keeps working against this core
   unchanged. It is also the correct response to V18's "the injector cannot restart a halted
   core".
2. **It has a known scope limit, and this design does not widen it.** The skill records that
   `vio_hard_reset` is deliberately *not* wired to `fabric_gt_clr`, because pulsing GT CLR
   during an in-flight JTAG transaction can kill `dbg_hub`'s own clock — and a VIO write *is* a
   JTAG transaction. A board with a dead `core_clk` still needs a power cycle. `vio_heartbeat`
   (V15) is what tells an operator they are in that case rather than in a recoverable one.
3. **The overlap with `dbg_axi` is the point, and is what V1 locks.** The fields that overlap —
   PC, retire count — are exactly the ones an operator needs when the structured path is the
   thing that broke. Redundancy that only pays off during a failure still pays off; this event
   is the receipt.

**`dbg_axi` does what VIO structurally cannot.** A versioned register protocol, scriptable
without a Hardware Manager session open, breakpoints, architectural capture, halt/step/
continue. None of that fits in a static probe. This spec adds nothing to that list and takes
nothing off it.

---

## 2. The D23 exception (V2-V6)

### 2.1 What D23 says, and what group 7 shows about how an exception gets granted

The axi-socket spec locks **D23**: *"`M68kSocketTop` exports only socket ports; none of the
37 crosses it"*, with `M68kFullCoreSynth`'s port surface and behaviour left unchanged as the
OOC-synth / FMax-gate target (that spec, §9.3).

`cpu_socket.vh` has its own equivalent rule — "no CPU-specific signal crosses the socket" —
and its own deliberate exception to it, which is the precedent this spec follows. Verbatim,
`macqd700-soc/rtl/soc/cpu_socket.vh:178-181`:

> `7) ILA-only debug-export group — DELIBERATE EXCEPTION to the "no`
> `   CPU-specific signal crosses the socket" rule above.  Exists ONLY`
> `   when `ILA_ENABLE` is defined (i.e. `make impl ENABLE_ILA=1`); the`
> `   default build's port list is unaffected.`

and its justification, `:181-185`:

> `A polled dbg_axi`
> `   register read cannot give a real-time Vivado ILA the cycle-`
> `   accurate commit/PRF-write/A7 taps a HW bring-up bisect needs, so`
> `   this group promotes ~46 named `dbg_ila_*_w` wires … as real`
> `   output ports on whichever module binds `u_cpu`.`

Three properties make that a *granted* exception rather than a leak, and **V2 reproduces all
three**:

1. It is **numbered and named in the contract file itself**, with its own preamble stating
   that it is an exception and why. It is not a set of wires that merely happen to cross.
2. Its membership is **enumerated**, not open-ended.
3. Both binders — the real CPU and `cpu_stub.v` — declare an **identical** port list, so the
   single shared connection list in `fpga_top` binds either way (`cpu_socket.vh:191-196`).

**V2:** the `vio_*` group is granted the same standing, as a new **group 8**, for the same
shape of reason: a polled `dbg_axi` register read cannot be performed at all when `dbg_axi` is
the thing that is wedged, and cannot be performed *during* reset under any circumstances.
Everything else D23 forbids stays forbidden — in particular the 37 probe/test ports, none of
which this group re-exports.

### 2.2 Where it lives, and why it is not gated there (V3)

The obvious reading of the group-7 precedent is "copy the `ifdef`". This spec deliberately
does not, and the reason is the precedent's *outcome* rather than its form.

`macqd700-soc/docs/ila_a7_drift_probes.md:11-26` records what happened to group 7, verbatim
(`:11-17`):

> *"**2026-07-04 update — bit-rot fix + v6 probes.** The whole probe map below went dead when
> the CPU was carved into the separate `m68k-ooo` repo and wired back in as a git submodule
> behind `rtl/soc/cpu_socket.vh` (2026-05-27 split): `make impl ENABLE_ILA=1` failed
> elaboration because `rtl/soc/fpga_top_debug_vio.vh` referenced `dbg_ila_*_w` wires that the
> CPU submodule computed internally (`m68k_axi_wrapper.v`) but never exposed as module ports."*

A gate is a second elaboration that must agree with the first, and the two silently drifted.
Note the failure *mode*: not a lint error, but a **synthesis-time elaboration failure several
minutes into a ~40-minute implementation run** — and only in the non-default configuration,
which `Makefile:1786-1798`'s lint matrix does not cover (it has `vio`, `vio+l2c` and
`smoke+vio` rows and no ILA row at all). A gated group is a configuration nobody builds until
the day they need it, which is the worst possible day to discover it does not elaborate.

The cost argument settles it. Splitting the cost by side:

| Side | What it is | Cost |
|---|---|---|
| Core | PC latch, retire counter, heartbeat, bundle register, edge detectors | ≈120 FF, negligible LUT |
| SoC | The `vio` IP's per-bit capture + activity detector | ≈2.8 LUT / ≈5.1 FF **per probe_in bit** (§8.1) |

The expensive part is not the wires; it is the IP. And the IP's gate **already exists**,
SoC-side, in the build system. So:

**V3:** on the socket top the group is **always present, ungated**. One deployment netlist, no
two elaborations to disagree, and the bit-rot root cause is removed rather than re-created.
The SoC decides whether to *bind* the group to VIO probes; an unbound group-8 output on a
build without VIO is an unconnected output, which costs the core the ~120 FF it already paid
and costs the SoC nothing.

### 2.3 The `M68kFullCoreSynth` gate (V4)

The one place a gate is genuinely required is the FMax target. axi-socket D23 froze
`M68kFullCoreSynth`'s port surface — "no port added, removed or tied off" — precisely so the
post-route number stays comparable across the socket work. An always-on group there would
break that.

**V4:** `VioProbePlugin` takes a constructor parameter (`enable`, default `false`), following
this repository's established gating idiom — `DebugCtrlPlugin(buildId = …, stage = 1)` and
`FetchAlignPlugin(enableFetchDirected = true)` in `FullCoreSynth.scala:510,531`, and
axi-socket D16's own `enable`-defaults-false reset-vector plugin. With `enable = false` the
plugin elaborates to nothing and `M68kFullCoreSynth` is bit-identical to today.

A second generation object (`GenFullCoreSynthVioVerilog`, emitting
`M68kFullCoreSynthVio.v`) sets it `true`. Its only purposes are (a) the Stage-1.5 cost-delta
measurement and (b) giving V28's netlist checker a real "enabled" artefact to test against.
It is **not** a synthesis target anyone gates on for FMax.

### 2.4 The forward dependency, stated rather than assumed (V5)

Verified while writing this spec, not inherited: `src/main/scala/m68k040/top/` contains
exactly `DecodeUopInputPlugin.scala`, `ExecSynthProbes.scala`, `FullCoreSynth.scala`,
`GenVerilog.scala`, `SynthProbePlugin.scala`. A repository-wide search for the string
`M68kSocketTop` returns hits in **one** file: the axi-socket spec. The module is designed but
not built.

**V5** therefore writes this spec against `FullCoreSynth` as the concrete, buildable target,
and names the socket top as a forward dependency with a defined hand-off:

- **Today / without the socket top:** `VioProbePlugin(enable = true)` in the V4 generation
  target. The group appears as top-level ports on `M68kFullCoreSynthVio`.
- **When `M68kSocketTop` lands:** the same plugin is instantiated there with `enable = true`
  and no gate (V3). The plugin's code does not change; only its host does.

Nothing in §§3-5 depends on the socket top existing. §6.2 and §8.2 (the SoC side) do depend
on it, and are marked as such.

### 2.5 One plugin, one file (V6)

**V6:** all of it lives in a new `VioProbePlugin extends FiberPlugin`, taking its inputs
through the existing service traits (`CommitTraceService` for the retire seam,
`FrontendQuiesceService` and direct `host[RobPlugin]` reads for the state bits) and declaring
its own `out`/`in` ports, in the same shape as `DebugCtrlPlugin` declares its socket IO. It is
placed **after** `RobPlugin` in the plugin list, since it consumes `CommitTraceService`.

Consuming the *service* rather than `FullCoreSynth`'s existing `traceOut`/`fireOut` top ports
is what makes V5's hand-off free: those ports do not exist on the socket top, the service does.

---

## 3. `probe_in` — what the core exports (V7-V15)

### 3.1 Frozen-field discipline (V7)

The debug regmap already runs this rule for offsets
(`docs/superpowers/plans/2026-08-17-debug-ctrl-stage0-stage1.md:196-198`: *"an offset listed
here is reserved forever … it is NEVER repurposed"*). **V7** applies it verbatim to probes:

- A probe's **name, index, width** and **bit-field assignment** are reserved forever.
- Reserved fields **read zero** and are never repurposed.
- New fields are appended into reserved space, never inserted.

This matters more for VIO than for a register map, because probe geometry is baked into the
IP at generation time (gotcha #3): a field that moves silently disagrees with every `.ltx`,
every dashboard script and every runbook already written against it, with no version
handshake anywhere to catch it.

### 3.2 The probe map

| Probe | Width | Kind | SoC index | Contents |
|---|---:|---|---|---|
| `vio_cpu_snapshot` | 96 | in | `probe_in26` (new) | The coherent bundle — §3.3 |
| `vio_heartbeat` | 32 | in | `probe_in27` (new) | Free-running, reset-less core-clock counter — §3.4.1 |
| `vio_build_id` | 32 | in | `probe_in28` (new) | `DBG_BUILD_ID` as elaborated — §3.4.2 |
| `vio_boot_pc` | 32 | out | `probe_out2` (new) | Boot/redirect target — §4.2 |
| `vio_ctl` | 4 | out | `probe_out3` (new) | `[0]` = `boot_go`; `[3:1]` reserved — §4.3 |
| `last_commit_pc` (alias) | 32 | in | `probe_in5` (existing) | Rebound from the `dbg_pc` tie-off — §6 |
| `retire_count` (alias) | 32 | in | `probe_in9` (existing) | Rebound from the `dbg_committed` tie-off — §6 |

**Index allocation is append-only and leaves no holes.** The SoC's VIO is fully populated
today — 26 `probe_in` (0-25) and 2 `probe_out` (0-1), with **zero free indices**
(`fpga_top_debug_vio.vh:420-469`). The existing map's own `// was probe_in15` /
`// was probe_in16` / `// was probe_in18` comments (`:441-443`) record a renumbering after the
2026-04-26 probe cull, so *compacting rather than leaving holes* is this instance's
established practice. New probes therefore append at 26/27/28 and 2/3, taking
`C_NUM_PROBE_IN` 26→29 and `C_NUM_PROBE_OUT` 2→4 (VSOC-3).

The last two rows are **rebinds, not new probes**: `probe_in5`/`probe_in9` already exist at 32
bits and are already wired into the dashboard, the runbook and the host tools — they gain a
real driver instead of the constant zero they carry today (§6, NOTED-1).

New `probe_in` bits: **160**. New `probe_out` bits: **36**. Rebound bits: **64**, at no new
geometry cost. All against V25's budget.

**V9 — why 96 bits with 23 reserved.** The live fields below occupy 73 bits. Provisioning 96
buys 23 bits of append-room at ≈64 LUT / ≈117 FF (V25's measured per-bit figure), and buys out
one future IP regeneration, one marker bump, and one exposure to the stale-cache class of bug
that has already bitten this SoC once. That is the trade, made deliberately; an implementer
who wants to argue it down to 80 bits must argue against that specific cost, not against
"unused bits".

96 is also not an arbitrary round number: `probe_in24` (`video_dbg_snap`) is an existing,
working, **96-bit coherent one-edge snapshot** in the same VIO instance (NOTED-4), so the
width, the bundling approach and the host-side decoder shape all have a deployed local
precedent to copy rather than a new pattern to establish.

### 3.3 `vio_cpu_snapshot` — the coherent bundle (V8)

VIO's gotcha #1 is real and was hit on the bench: `probe_in` reads across *different* probes
are not a coherent same-instant snapshot, because each probe is its own JTAG transaction. The
sibling built a dedicated capture register after hitting exactly this.

**V8** discharges it structurally rather than procedurally. Every field below is driven by a
register in the core clock domain, and they are concatenated into **one** port. Within one
probe, one JTAG transaction captures every bit together; and because every field is
register-driven on the same edge, the fabric-side values are mutually consistent too. There
is no separate "capture" command and no arming sequence to get wrong.

| Bits | Field | Source | Notes |
|---|---|---|---|
| `[31:0]` | `retire_count` | new, §3.3.2 | Saturating |
| `[63:32]` | `last_commit_pc` | new, §3.3.1 | **POST-instruction PC** (V12) |
| `[64]` | `commit_pc_valid` | new | At least one retire since reset; `last_commit_pc` is meaningless until set |
| `[65]` | `halted` | `RobPlugin.scala:375` `coreHalted` | Sticky |
| `[69:66]` | `halt_reason` | **reserved, reads 0** | Filled by axi-socket **D28** (V21) |
| `[70]` | `stopped` | `RobPlugin.scala:361` `stopped` | `STOP` instruction, interrupt-wakeable |
| `[71]` | `frontend_quiesce` | `FrontendQuiesceService.active` (`RobPlugin.scala:47,1487`) | `stopped \|\| coreHalted` |
| `[72]` | `fetch_started` | `FetchAlignPlugin.scala:189` `started` | Has this core ever been booted? |
| `[95:73]` | reserved, reads 0 | — | V9 |

The four state bits at `[72:70]` and `[65]` are what turn "the PC is frozen" from a symptom
into a diagnosis: frozen + `halted` is a latched fault; frozen + `stopped` is a `STOP`
instruction waiting for an interrupt; frozen + `!fetch_started` means the core was never
booted at all; frozen with none of those set is a genuine wedge.

#### 3.3.1 The last-committed-PC latch (V11, V12)

New RTL. Nothing at top level exposes a committed PC today — the scoping pass's claim here is
correct and was re-verified: `RobPlugin.scala:375` marks `coreHalted` `simPublic()` only, and
`commitPc0`/`commitPc1` are `simPublic()` sim-only taps (`RobPlugin.scala:844`).

```
pcLatch      : Reg(UInt(32 bits)) init 0
pcLatchValid : RegInit(False)
when(ct.traceFire(1)) { pcLatch := ct.trace(1).pc; pcLatchValid := True }
  .elsewhen(ct.traceFire(0)) { pcLatch := ct.trace(0).pc; pcLatchValid := True }
```

**V11 — the qualifier trap, stated precisely.** The scoping pass warns that `traceOut_k_fire`
is `x` and `fireOut_k` is not, which is correct at the *port* level. At the *service* level —
which is what V6's plugin consumes — the same distinction is between
`CommitTraceService.trace(k).fire` and `CommitTraceService.traceFire(k)`:

- `RobPlugin.scala:788` — `traceVec(k).assignDontCare()` is the idle default, so
  `trace(k).fire` reads `x` between retires.
- `RobPlugin.scala:787` — `traceFireVec(k) := False` is a concrete idle default.

Qualifying on `trace(k).fire` would latch garbage in the netlist. The plugin uses
`traceFire(k)`. (This is a clarification of the scoping pass's wording, not a contradiction of
it — §10, item 2.)

**Slot priority.** Both slots can retire in one cycle. Slot 1 holds the younger instruction,
so slot 1 wins — otherwise the latch reports a PC one instruction stale on every dual retire,
which would make a tight loop look like it was executing only its first half.

**V12 — the PC's meaning.** `CommitTrace.scala:10` is explicit: *"POST-instruction PC
(next-instruction addr); must match Musashi `--trace` for lock-step"*. `last_commit_pc` is
therefore the address of the instruction **after** the last one retired, not the address of
the last one retired. Every consumer — dashboard label, runbook prose, `jtag_repl.tcl` field
name — must say so. This is not the same quantity as v1's `dbg_pc`, which was the **IF**
stage's fetch PC (§6.1); calling both "PC" without qualification is how an operator draws a
wrong conclusion at 3am.

The other three genuinely-live `CommitTrace` fields (`archRegId`, `archRegValid`,
`fire`) are deliberately **not** exported: they describe one retire event, and a probe read
milliseconds later samples an arbitrary unrelated one. Per-event data belongs in a trace
buffer (ILA or the debug-ctrl spec's Stage 7), not a static probe. The remaining fields are
hardwired dead constants anyway — `RobPlugin.scala:814,816,818,820-821,823-824` set `opword`,
`archRegWrite`, `ccr`, `memAddr`, `memData`, `excTaken`, `excVector` all to `0` in
`driveCommit`, which re-confirms the scoping pass's "21 of 26 `traceOut_*` ports are dead
constants" finding at its source.

#### 3.3.2 The saturating retire counter (V13)

New RTL. `DebugCtrlPlugin` already contains a reviewed, deployed-reference-matched saturating
counter with exactly the property wanted here, and **V13 reuses its pattern rather than
inventing a second one**. From `DebugCtrlPlugin.scala:173-185`:

> *"Surviving 16-bit count of observed CPU-reset edges. SATURATES: zero means 'no reset
> observed since the last clear' and must not be reachable by wraparound (spec 15.4)."*

and its increment, `:373-374`:

```
when(cpuResetCount =/= U(0xFFFF, 16 bits)) { cpuResetCount := cpuResetCount + 1 }
```

The VIO counter is the same shape, widened and with a variable increment:

```
retireCount : Reg(UInt(32 bits)) init 0
val n = U(0, 2 bits) + ct.traceFire(0).asUInt + ct.traceFire(1).asUInt   // 0, 1 or 2
when(retireCount < (U(0xFFFFFFFFL, 32 bits) - n)) { retireCount := retireCount + n }
  .otherwise { retireCount := U(0xFFFFFFFFL, 32 bits) }
```

The comparison is against `max - n` rather than `=/= max` because the increment is not 1: a
`=/= max` guard with `n = 2` can step *over* the maximum and wrap, which is the exact failure
the 16-bit original's comment forbids. **This is a real difference from the reused pattern and
is called out so an implementer does not "simplify" it back.**

Meaning, matching the original's discipline: **zero means nothing has retired since reset, and
must not be reachable by wraparound.** Saturation, not wrap, is what makes a stuck-at-`0` read
a diagnosis rather than an ambiguity.

**Why 32 bits.** At ~200 MHz and IPC ≈ 0.53, a 32-bit counter saturates after ~40 s of
execution. That is long enough that a bring-up session's "is it making progress" question is
always answered by a changing value, and the saturated state is itself informative ("it has
been running a long time"). A 16-bit counter would saturate in ~0.6 ms and be useless.

#### 3.3.3 Reset domain (V14)

`DebugCtrlPlugin` establishes the classification this repository uses (spec 15.1, quoted in
`DebugCtrlPlugin.scala:196-205`): state that "if it survived, would report a previous life as
the current one" is CPU-coupled runtime state and is wiped on the CPU-reset edge; host
configuration survives.

`retire_count`, `last_commit_pc`, `commit_pc_valid` and every state bit in §3.3 are squarely
CPU-coupled runtime state. **V14** puts them in the ordinary core reset domain, so a CPU reset
clears them naturally and a debugger attaching afterwards cannot be handed the previous boot's
retire count. No separate wipe logic is needed — this is the default domain doing the right
thing, recorded so nobody "helpfully" moves them into the debug domain later.

The heartbeat (§3.4.1) is the deliberate exception, and its exception is the point of it.

### 3.4 Standalone probes (V10)

**V10** — a field belongs outside the bundle when it has no cross-field invariant with
anything in it. Two qualify, and one of them qualifies emphatically.

#### 3.4.1 `vio_heartbeat` (V15)

A 32-bit free-running counter, incremented every core-clock cycle, in a **reset-less domain**.

**Why reset-less, and why not in the bundle.** The heartbeat's entire job is to answer *"is
the core clock running?"* — including while reset is asserted, and including when every other
signal in the design is frozen. A counter in the core reset domain reads zero while reset is
held and therefore cannot distinguish "held in reset" from "clock dead", which is the single
most common ambiguity at the start of a bring-up session. Putting it in the coherent bundle
would be worse still: the bundle is a still frame of frozen state, and the heartbeat must keep
moving through exactly the situations that freeze it.

`DebugCtrlPlugin` already has the domain: `DebugCtrlPlugin.scala:79`'s `porCd` (rationale at `:426-435`) is a
`BOOT`-kind, reset-less domain created for the POR counter, with the elaboration subtlety
already worked out and commented (*"`porCd` has no reset at all, so its process has no if/else
gating and this runs unconditionally every cycle"*). **V15 reuses that domain rather than
declaring a second one.**

**How an operator uses it.** Read it twice, seconds apart. Different ⇒ the core clock is
running. Identical ⇒ the clock is stopped or the MMCM is unlocked, and every other probe in
this design is meaningless. At 200 MHz the low 32 bits wrap every ~21 s, so two reads a
human-interval apart differing by chance is not a practical concern.

#### 3.4.2 `vio_build_id`

The elaborated `DBG_BUILD_ID` (`FullCoreSynth.scala:477-485`), exported as a probe. It is
already readable over `dbg_axi`, and that is the point: **the probe is readable when `dbg_axi`
is not**. "Is the bitstream in this device the one I just built?" is the first question of
every bring-up session and the last question of every confusing one, and a stale bitstream
makes every other reading a lie. Static, so no coherence question arises; standalone for the
same reason.

---

## 4. `probe_out` — what the host drives (V16-V20)

### 4.1 The edge discipline (V16)

Gotcha #2, hit **twice** on the real bench: a `probe_out` is a **level**. It survives design
reset. It cannot be cleared if the JTAG link drops mid-session — the value is held in the VIO
IP's own registers, which the design's reset does not reach, and with no link there is no way
to write them.

**V16, mandatory and admitting no exception:** no `probe_out` is consumed as a level. Each
becomes a one-cycle pulse:

```
val goQ = RegInit(True)          // NOT False -- see below
goQ := vio_ctl(0)
val goPulse = vio_ctl(0) && !goQ
```

**The `RegInit(True)` is the whole safety property.** With `RegInit(False)`, a `probe_out`
left high across a dropped link produces a spurious rising edge the moment the design leaves
reset — i.e. the failure mode fires *precisely* in the recovery scenario VIO exists to serve,
and fires automatically on every subsequent reset until someone reattaches and clears it. With
`RegInit(True)`, a stuck-high level is seen as "already consumed" and produces nothing; only a
genuine low→high transition acts, which requires a live link, which is the condition under
which the operator meant it.

This is not a new idiom: `DebugCtrlPlugin.scala:170-172` does exactly this for the CPU-reset
edge detector —

```
val cpuRstQ = RegInit(True)
cpuRstQ := cpuRstLevel
val cpuRstEvent = cpuRstLevel && !cpuRstQ
```

— for the same reason (reset is *asserted* at power-on, so a `False` init would manufacture an
edge that never happened). V16 is that reviewed pattern applied to a second signal class.

Per NOTED-2 the detector needs no synchroniser: `probe_out` is synchronous to the VIO IP's
`clk`, which is `core_clk`.

`vio_boot_pc` is a **data** level and is *not* edge-converted — it is only ever sampled in the
cycle `goPulse` fires, so its level nature is harmless. V16 governs every `probe_out` that
*acts*; a payload sampled by an acting pulse is covered by the pulse.

### 4.2 The boot-PC injector (V17)

Re-verified at source, not inherited: `FetchAlignPlugin.scala:585` gates the I-cache command
on `started`, `ic.cmd.valid := started && ringSlotAvailable && ibufRoomForCmd && …`, and
`:189` declares `val started = Reg(Bool()) init False  // don't fetch until first redirect`,
set to `True` only in the redirect/resume arms (`:1198, 1219, 1237, 1283`). **The core fetches
nothing after reset until a redirect pulses.** `FetchAlignPlugin.scala:72` declares
`val redirect = slave(Flow(UInt(32 bits)))`.

**V17:** `goPulse` (§4.1) drives that seam with `vio_boot_pc` as the payload. The result is a
zero-software bring-up boot: with a `.bit` and a `.ltx` and nothing else, an operator can
start the CPU at an arbitrary PC and watch `retire_count` move.

Because the injector shares the redirect seam, it also serves as a **wedge escape** on a live
core: a redirect flushes the frontend and restarts fetch at a known address. It is not a
substitute for a reset, and it is not a halt.

#### 4.2.1 What it cannot do (V18)

**V18, stated as a limit rather than left to be discovered:** the injector cannot restart a
**halted** core. `coreHalted` is sticky (`RobPlugin.scala:375-376`, `when(coreHaltedIn) {
coreHalted := True }`, with no clear arm) and forces `headReady` False
(`RobPlugin.scala:585`). A fetch redirect refills the frontend; the ROB still retires nothing.
The snapshot's `halted` bit is what tells the operator this has happened, and the correct
response is a reset (via `dbg_axi`'s cold-reset control, or the SoC's own reset tree),
not another `goPulse`.

Clearing a halt is a *resume*, whose semantics the debug-ctrl spec owns in Stage 2. This spec
does not invent a second way to do it.

#### 4.2.2 Coexistence with axi-socket D12/D16 (V19)

A cross-spec interaction the scoping pass did not identify, resolved here rather than left to
collide during implementation (§10, item 5).

axi-socket **D12** makes reset/boot a real vector-0 AXI fetch; **D16** puts it behind an
`enable` parameter defaulting off; **D23** makes `redirect` *internal* on the socket top under
D12/D16 rather than a port. So on the socket top there are two would-be owners of the same
seam.

**V19:** the VIO injector is an **additional** source into `FetchAlignPlugin.redirect`'s
consumer, at the same priority as the D12 fetch's own redirect and sequenced after it, so a
`goPulse` in the same cycle as the vector-0 redirect wins. Rationale: the operator is
physically present and pressing a button; the vector fetch is automatic. Deliberate manual
override of an automatic mechanism is the correct precedence, and the alternative (automatic
wins) would make the injector useless in the case where D12 is booting to a wrong or
unreachable vector, which is exactly when a human reaches for it.

The injector remains load-bearing in three cases D12 does not cover:

1. Builds where D12's `enable = false` — which per D16 is **every existing sim, lock-step and
   OOC flow**, and the `M68kFullCoreSynthVio` target of V4.
2. Any build predating D12, including everything that can be built today.
3. Forcing execution at an arbitrary PC on a live, idle, non-halted core — a thing D12
   structurally cannot do, since it fires once out of reset.

It does **not** cover a D15 halt (V18): a failed vector-0 fetch latches `coreHalted`, and no
redirect clears that.

### 4.3 `vio_ctl` reserved bits (V20)

`vio_ctl` is 4 bits: `[0]` is `boot_go`; `[3:1]` are **reserved, driven to nothing, and never
repurposed** (V7).

**V20** names one of them: `vio_ctl[1]` is reserved for a future halt/resume request. This
spec explicitly does **not** define its semantics — precise halt/resume is the debug-ctrl
spec's §6 and its Stage 2, and a VIO-side halt whose meaning disagreed with the `dbg_axi`
halt would be worse than no VIO halt at all. Reserving the bit costs nothing now and avoids
re-generating the IP when Stage 2 wants it.

---

## 5. What this spec does not build

### 5.1 The halt-reason channel (V21)

axi-socket **D28** already owns this, and says so at length in that spec's §6.4: the halt seam
is today a plain undiscriminated `Bool` (`RobPlugin.scala:373-376`), driven as
`dc.diagFault || exc.fsXlateFault` (`FullCoreSynth.scala:364`), with the kind codes
`DcachePlugin`-private. D28 widens it to a `{valid, reason}` pair with a sticky first-wins
reason register and allocates codes for four producers. That spec's §11.1 lists it as item 2
in its own implementation order, ahead of the two features that report through it.

**V21: this spec builds none of it.** Building a second, VIO-only halt-reason encoding would
create exactly the divergence D28 exists to prevent, and would be thrown away when D28 lands.
Instead:

- The bundle **reserves** `halt_reason[3:0]` at bits `[69:66]`, reading zero.
- The bundle **exports today** the `halted` bit from the already-existing `coreHalted`
  (`RobPlugin.scala:375`), which requires no new channel at all.
- When D28 lands, its reason field is connected to the reserved bits. Because V7 froze the
  geometry, that is a one-line wiring change with no IP regeneration and no script churn.

Four reason codes fit in 4 bits with room; if D28 allocates more than 15, the overflow goes
into V9's reserved space, which is why V9 bought it.

### 5.2 Everything the debug-ctrl spec owns

Halt/step/continue, breakpoints, watchpoints, A-traps, architectural register read/write,
cache maintenance, the register map, and the feature-discovery bitmap. This spec adds no
register, changes no offset, and claims no feature bit.

---

## 6. v1's dead `dbg_pc` / `dbg_committed` — the split (V22)

The scoping pass raised this as a headline finding (a currently-live regression on deployed
hardware, tracked as task #226) and suggested this core's new PC-latch/counter RTL might
"un-dead" v1's probes as a bonus, since the VIO wiring is shared. **That suggestion is half
right, and the half that is wrong matters.** Investigated against real source rather than
assumed:

### 6.1 What was actually there

- `rtl/soc/fpga_top_debug_ctrl.vh:46-47` — the tie-offs, `wire [31:0] dbg_pc = 32'd0;` and
  `wire [31:0] dbg_committed = 32'd0;`, with the comment admitting they were "relocated
  CPU-side in the socket split".
- `rtl/soc/fpga_top_debug_vio.vh:433,437` — `.probe_in5 (dbg_pc)` and
  `.probe_in9 (dbg_committed)`, in an instantiation (`:418`) gated only by `VIO_ENABLE`, which
  defaults on and is *required* for a real bitstream (NOTED-5) — i.e. effectively
  unconditional, and specifically **not** ILA-gated. The two probes are live in every shipping
  build; only their values are fake.
- The commit that killed them is `8df24ac`; before it, `70d1040^:rtl/soc/fpga_top_cpu.vh:406-436`
  bound `.dbg_pc(dbg_pc)` / `.dbg_committed(dbg_committed)` on a directly-instantiated
  `m68k_core`, and `8df24ac^:rtl/soc/cpu_socket.vh:110-111` declared them in the contract.
- Host-side consumers assume they are live: `tools/jtag_bringup_tui.py:62,69,503`;
  `synth/vio_only_probe.tcl:61` (which names `dbg_committed` and `dbg_pc` explicitly);
  `docs/hw_debug.md:84,89`; `docs/an9134_50mhz_bringup.md:308,312`; and most importantly
  `docs/bringup_runbook.md:268-283`, whose escalation section §7 is the primary CPU-liveness
  procedure: `:273` "probe5 — last-committed CPU PC (shows boot progress)", `:275` "probe9 —
  retired-insn counter ('catches CPU alive but slow')", closing at `:282-283` with *"dbg_pc +
  dbg_committed tells you whether the core is running, stuck on a specific PC, or looping."*
  **Both read `0x00000000` unconditionally.** The documented primary escalation path for "is
  the CPU alive" is currently a pair of constants.

**A correction to the historical record, which this spec must not propagate.** The old socket
comment at `8df24ac^:rtl/soc/cpu_socket.vh:110` called `dbg_pc` a *"committed/architectural PC
tap"*, and `docs/bringup_runbook.md:273` still calls it the *"last-committed CPU PC"*. It was
neither. Its driver is `cpu/rtl/core/fetch/if_stage.v:359`, `assign dbg_pc = pc;` — the
**IF-stage fetch PC**, confirmed by `cpu/rtl/core/m68k_core.v:777`
(`assign dbg_ila_if_pc = dbg_pc;`) and by the warning in
`macqd700-soc/docs/rom_boot_bringup.md:439`. `dbg_committed` *is* what it claims — a µop
retire counter (`cpu/rtl/core/commit.v:638`). So the two probes were never a matched pair, and
this core's `last_commit_pc` (V12, a post-instruction *committed* PC) is a **third** distinct
quantity from either. Rebinding `probe_in5` (VSOC-2) is therefore also the moment to make the
runbook's label true rather than merely live. See §10, item 4.

### 6.2 The split, and why it is a split (V22)

The two halves of the fix have different homes, and the difference is structural, not a matter
of convenience.

**SoC-side — shared, written once, IN SCOPE (VSOC-1, VSOC-2).**
`rtl/soc/fpga_top_debug_ctrl.vh` has exactly **one** `u_cpu` port-connection list, selected
between `m68k_axi_wrapper` and `cpu_stub` by `` `ifdef CPU_M68K `` at `:139-143`. So the wire
declarations, the `u_cpu` binding, and the two `probe_in5`/`probe_in9` hookups are written
once and reused verbatim by **any** core that binds the socket. Declaring the ports in
`cpu_socket.vh` as group 8 (VSOC-1) and rebinding the tie-offs (VSOC-2) is therefore shared
infrastructure, it is work this core needs for its own probes regardless, and doing it once
makes v1's fix a four-line change.

**CPU-side — per-core, independent, OUT OF SCOPE here.** Each core must drive those ports from
whatever internal signals it has. For v1 that means adding two `output wire [31:0]` ports to
`cpu/rtl/core/m68k_axi_wrapper.v` (after `init_done_seen` at `:156`) fed from wires that
**already exist at wrapper scope** — `:305` `wire [31:0] dbg_pc;` and `:307`
`wire [31:0] dbg_committed;`, already driven by the core at `:712-713`. `m68k_core.v` needs no
change at all; the driver ports are already there (`m68k_core.v:154,156`).

**But that file is in a different git repository.** `macqd700-soc/.gitmodules` pins `cpu` to
`/home/qwertyoruiop/m68k-ooo`, branch `split/macqd700-soc`, at `4eab9008`, with its own
`cpu/.git`. Touching it is a submodule edit plus a pointer bump in the SoC — a different
repository, a different review, and a different core's semantics (an IF PC, not a committed
PC, §6.1).

**V22, concretely:**

- **IN SCOPE:** VSOC-1 (the group-8 socket declaration + `cpu_stub.v` parity) and VSOC-2
  (rebinding the SoC-side tie-offs to it, restoring `probe_in5`/`probe_in9`). Both are needed
  for this core's own probes and both are shared.
- **OUT OF SCOPE:** the v1 submodule edit. It stays task #226.
- **Hand-off, so task #226 does not repeat this investigation:** once VSOC-1/VSOC-2 land, the
  entire remaining v1 fix is (a) two `output wire [31:0]` ports on `m68k_axi_wrapper.v` after
  `:156`, assigned from the existing `:305`/`:307` wires; (b) a submodule pointer bump. Zero
  new logic, `m68k_core.v` untouched. **With the caveat that v1's `dbg_pc` is the IF PC**, so
  either the group-8 port is named for what it carries or v1 drives it from `rob_pc` instead —
  a decision task #226 must make consciously, and which §6.1 exists to prevent it from making
  unconsciously.

There is also a **cheap ILA-only alternative** for v1 that needs no submodule edit — under
`` `ifdef ILA_ENABLE ``, drive `dbg_pc` from the already-bound `dbg_ila_if_pc_w`
(`fpga_top_debug_ctrl.vh:110,243`) or `dbg_ila_rob_pc_w` (`:83,216`), and count
`dbg_ila_rob_pop_w` (`:80,213`) SoC-side for the counter. It is recorded here for task #226's
benefit but is **not** recommended and is not adopted: it covers only ILA builds while the VIO
is unconditional, so the default build still reads zero, and it makes a permanent contract
depend on the group-7 exception (which `cpu_socket.vh:178-181` explicitly scopes to ILA
builds). Group 8 is the durable home.

### 6.3 Reusing `probe_in5` / `probe_in9` versus V8's coherence rule

These two are *separate* probes, so V8's "one coherent bundle" rule appears to forbid using
them for PC and retire count. It does not, and the distinction is worth stating because an
implementer will hit it:

The incoherence gotcha bites when an operator **cross-references two probes** and reasons
about them as one instant. Each of `probe_in5` and `probe_in9` is a *single self-contained
scalar*, individually meaningful ("the PC right now", "the count right now"). Reading them is
fine. Reading them and concluding "at PC X the core had retired N instructions" is not — and
that inference is exactly what `vio_cpu_snapshot` exists to make available.

**Decision:** bind **both**. The legacy pair keeps `tools/jtag_bringup_tui.py`, `hw_debug.md`
and `bringup_runbook.md` working with no host-side churn (VSOC-4) and costs no new IP geometry
(NOTED-1); the bundle is the coherent view. The dashboard labels the legacy pair as scalars
and the bundle as the snapshot, so nobody cross-references the wrong pair.

---

## 7. Standing-rule conformance: no address-decode assumption (V24)

The project's standing rule — *never assume a static/known SoC address-decode map; the CPU may
only reason from MMU/page-table-configured attributes and real contemporaneous bus responses*
— is checked against this design explicitly rather than assumed to be satisfied.

**V24:** it is satisfied by construction, and here is the argument rather than the assertion.

- VIO probes are **named nets**, reached over BSCAN through the `dbg_hub`. There is no
  address, no decode, no window, and no bus transaction of any kind in the probe path.
- The core-side probe logic reads registers (`coreHalted`, `stopped`, `started`, the commit
  trace) and drives one existing `Flow` port. It performs no memory access.
- `vio_boot_pc` is a **PC**, not an address the core reasons about: it is handed to the
  fetch redirect, and whatever happens next — translation, cacheability, whether anything is
  backed there — is decided by the MMU and by the real bus responses, exactly as for any other
  PC. This design forms no belief about that address, caches no "proven backed" bit, and
  consults no range table.
- Nothing in §§3-5 branches on an address value.

The one place an address-shaped assumption *could* creep in is a future temptation to give VIO
a peek/poke path ("read memory when `dbg_axi` is wedged"). That is **not** in this design and
must not be added without re-checking this section: such a path would need address decode and
would have to obtain it from real bus responses, never from a map.

---

## 8. Cost, and the SoC side

### 8.1 Budget (V25, V26)

Real numbers from the live routed SoC build (`macqd700-soc/build/vivado/reports/`,
Vivado 2025.2, `xcku5p-ffvb676-2-i`, Design State: Routed, 2026-08-18), not estimates:

| Item | Cost | Source |
|---|---|---|
| `u_dbg_vio` (`debug_vio`) total | 2 375 LUT / 4 274 FF | `utilization_route.rpt:110-114` |
| — of which `PROBE_IN_INST` | 2 251 LUT / 4 010 FF | `utilization_route.rpt:114` |
| Current total `probe_in` width | 792 bits | sum of the 26 configured widths |
| ⇒ VIO `probe_in`, per **bit** | ≈**2.8 LUT / ≈5.1 FF** (with `C_EN_PROBE_IN_ACTIVITY=1`) | derived from the two rows above |
| `dbg_hub` | 462 LUT / 765 FF | `utilization_route.rpt:25` |
| This design's new `probe_in` bits | 160 (`vio_cpu_snapshot` 96 + `vio_heartbeat` 32 + `vio_build_id` 32) | §3.2 |
| ⇒ SoC-side VIO IP cost | ≈**455 LUT / ≈816 FF** | |
| As a fraction of the xcku5p's 216 960 LUTs | ≈**0.21 %** | |
| Core-side new RTL | PC latch 32 FF + valid 1 + retire counter 32 + heartbeat 32 + bundle/edge regs ≈ 20 ⇒ ≈**120 FF**, negligible LUT | §3, §4 |
| debug-ctrl plan's acceptance gate | ≤ **+1.0 %** device LUT **and** FF | debug-ctrl spec §11 |

**V25:** the budget line is that gate, unchanged and not renegotiated. Both figures sit an
order of magnitude inside it. `probe_out` (36 bits) is not separately characterised in the
routed report; it is register-only on the fabric side and is bounded by the same order.

**Refinement of the source figure.** The scoping pass quoted ≈3.0 LUT / ≈5.4 FF per bit. The
routed report gives ≈2.8 / ≈5.1. The difference is immaterial to every conclusion; the
measured figure is used here because it is directly derivable from a citable report row. See
§10, item 7.

**Cost has mattered here before, in both directions.** `fpga_top_debug_vio.vh:61-70,304-308`
document a 2026-04-26 "debug-bloat cull" that dropped three probes to free ~5 k LUTs of
VIO-internal storage — and `:72-86` documents *restoring* one of them, because it was "exactly
the instrument this project then spent a full day without." That is the precedent for both V9's
deliberate over-provisioning and V23's refusal to bundle in an ILA: buy the instrument you will
actually reach for, and do not buy the one you will cull.

There is **no FMax or floorplan interaction**: the `dbg_hub` already exists, on its own
independent clock, with zero timing violations in the current routed build. The core-side
additions are a latch and two counters fed from already-registered signals and driving only
top-level ports — no new combinational path into any existing cone.

**V26 — the capacity caution, recorded so it is not misattributed.** The 83.6 % figure is
**derived, not a literal string in any file** — it is `utilization_route.rpt:24`'s top row,
181 320 of 216 960 CLB LUTs = 83.57 %. (That report has no `Util%` column; the post-synth
`utilization_synth.rpt:35` prints 84.80 % as a pre-opt estimate. Do not cite a doc for this —
`docs/hardware_feasibility.md:75` says 48.6 % and `docs/fmax_autopsy_20260425.md:106` says
94 %, both from different eras and configurations.) That routed build is `enable_vio=1`,
`enable_ila` **absent** — so 83.6 % is already a VIO-inclusive, ILA-free number.

The arithmetic that matters: `u_cpu` (`m68k_axi_wrapper`) alone is **104 358 LUT =
57.6 %** of the design (`utilization_route.rpt:57`; note `:59` is the *inner* `m68k_core` at
100 601, so cite the wrapper row, not the core row), leaving ≈**35 600 LUT free**. Swapping v1
for this core (~124 k per the socket spec) spends ~20 k of that 35.6 k and pushes the device
back toward **~93 %** *regardless of VIO*. VIO's 0.21 % is not what would break that. But the
headroom is not free either, so the Stage-1.5 gate (§9, task 6) reports **SoC-level absolute
utilisation** alongside the core-level delta — a delta inside budget against a device at 97 %
is still a failed integration.

### 8.2 `macqd700-soc` work (VSOC-1 … VSOC-4)

Coordination-level scope. Task-by-task detail belongs to the later `writing-plans` pass.

| Item | Change | Depends on |
|---|---|---|
| **VSOC-1** | Add **group 8** to `rtl/soc/cpu_socket.vh` declaring the `vio_*` ports, with an exception preamble written in the same form as group 7's (`:178-196`) — stating that it is a deliberate exception, why (`dbg_axi` is unreachable when wedged and always unreachable during reset), and enumerating its membership. Add the identical group to `rtl/soc/cpu_stub.v`, tied to constant 0, so the single shared connection list binds either way. | This core exporting the group (V3/V5) |
| **VSOC-2** | `rtl/soc/fpga_top_debug_ctrl.vh:46-47` — turn the two `= 32'd0` tie-offs into plain wires; add the group-8 entries to the shared `u_cpu` connection list at `:139-143`. Restores `probe_in5`/`probe_in9` (§6.2). | VSOC-1 |
| **VSOC-3** | Bump the VIO IP config in `synth/vivado.tcl`'s `gen_debug_vio_ip` (`:669-763`): `C_NUM_PROBE_IN` 26→29, `C_NUM_PROBE_OUT` 2→4, plus `C_PROBE_IN26/27/28_WIDTH` and `C_PROBE_OUT2/3_WIDTH` (`:707-741`). **Via `create_ip`, never `create_debug_core`** (NOTED-3). Bump the cache marker version `probe_map=v22`→`v23` in **both** the predicate (`:681-693`) and the writer (`:744-760`), and update `probe_count`. **Strengthen the marker to cover net identity**, not only count and width — see below. RTL and IP config must be bumped **together** (gotcha #3) or they silently disagree; the RTL says so itself at `fpga_top_debug_vio.vh:459-462`. | VSOC-2 |
| **VSOC-4** | Update the **three** hand-maintained host-side tables — there is no shared map, each is its own list: (a) `synth/vio_dashboard.tcl` — a row in the comment table (`:16-57`) and a `show_probe` call (`:148-164`), plus a bit-decode block for the snapshot in the style of the existing `vio_rst_bundle` decode (`:173-183`); (b) `synth/jtag_bringup.tcl` — rows in `probe_specs` (`:318-335`, currently only reaching `probe_in15`) and `named_specs` (`:341-372`); (c) `tools/jtag_repl.tcl` — new probes appear in the generic `vio-read` dump (`:4688-4727`) for free but as raw hex, so add a decoder modelled on `video-status`'s handling of the 96-bit `video_dbg_snap` (`:4786-4800`), which is the same shape of problem already solved (NOTED-4). Existing `dbg_pc`/`dbg_committed` consumers — `tools/jtag_bringup_tui.py:62,69,503` and `synth/vio_only_probe.tcl:61` — must keep working **unchanged** and be regression-checked, not rewritten. Refresh the `.ltx` (`synth/vivado.tcl:2078`). | VSOC-3 |

**On VSOC-3's marker, specifically.** The existing check is documented as insufficient by its
own authors, `synth/vivado.tcl:658-668`: a stale-but-count-matching cached IP was reused after
a hierarchy change and produced duplicate-suffixed probe names (`s0_wready` /
`s0_wready_1`) in the `.ltx`, with readbacks that did not reflect the new netlist — *"bump the
version rather than trusting a 'probe_count matches' cache hit blindly."* Two concrete
weaknesses an implementer must not inherit:

1. **It does not check identity at all**, only count and width. **This directly affects
   VSOC-2:** rebinding `probe_in5`/`probe_in9` from a constant to a real net changes neither
   the count nor any width, so it is a *silent cache hit* and the marker version must be
   bumped by hand or the rebind will not take.
2. **The writer emits widths the reader never checks.** `probe_in16_width` /
   `probe_in17_width` are written (`:752-753`) but absent from the predicate (`:681-693`), so a
   width change on either would silently hit the cache. The new probes must be added to
   **both** halves, and the asymmetry is worth fixing while there.

An implementer working in this file will also meet the `setup_debug_vio` early return
described in NOTED-6; it is a non-issue for this group and is recorded there so it does not
become a detour.

**Also update `macqd700-soc/docs/bringup_runbook.md` §7 (`:268-283`)**, whose "is the CPU
alive, stuck, or looping" escalation points at `probe5`/`probe9`. Three things change: they
become live again (VSOC-2); `probe5`'s label must say **post-instruction committed PC**, not
"last-committed CPU PC", which was never true even of v1 (§6.1); and the snapshot's `halted` /
`stopped` / `fetch_started` bits turn the "stuck" branch from a guess into a decision
procedure (§3.3). While there, the section's stale `probe13`/`probe14` rows (`:278`, probes
dropped in the 2026-04-26 cull) and its stale `probe_out0` bit map (`:279-280`) are worth
correcting — pre-existing bit-rot, not caused by this work, but in the exact paragraph being
edited.

### 8.3 The ILA group-7 question, decided (V23)

The scoping pass left this as an open sub-decision: whether to adopt the group-7 ILA export in
the same stage. **V23 decides it: not in this work.**

- The two instruments answer different questions. VIO answers *"is it alive, and where"*; only
  an ILA answers *"what happened in the last N cycles before it died"*. The sibling's 13 ILA
  capture scripts are what actually closed real HW-only bugs on v1, so this is not a dismissal
  of ILA's value — it is a statement that the value is separate.
- The costs are different classes. VIO is ~0.21 % of LUTs and no BRAM. An ILA capture buffer
  is BRAM, and the Stage-1 acceptance gate requires **exactly zero** BRAM delta. An ILA cannot
  pass that gate; bundling it would fail the gate for the wrong reason.
- The build postures are different, and the difference is exactly the risk. `ENABLE_VIO`
  defaults **on** and is *required* for a real bitstream (`synth/vivado.tcl:200-201`), so a VIO
  group is exercised by every build anyone makes. `ENABLE_ILA` defaults **off**
  (`synth/vivado.tcl:155`) and has no lint-matrix row (`Makefile:1786-1798`), so an ILA group
  is exercised by nobody until the day it is needed — the precise condition that produced the
  §2.2 bit-rot. Adopting group 7 here would be adopting that posture.
- Scope discipline. V2's exception is defensible precisely because it is small and enumerated.
  Adding ~46 more CPU-specific wires in the same breath makes it an open-ended carve-out, and
  the reviewer of the next exception request would have no line to hold.
- `cpu_socket.vh:178-196`'s group 7 already exists and is v1-specific. If this core ever wants
  an ILA export, it wants its **own** group, designed against its own signal set, with its own
  gate — a separate design pass, not a rider on this one.

Recorded as a named follow-on candidate with its justification pre-written, so a future
session picks it up as a decision already framed rather than as an open question.

---

## 9. Staging: Stage 1.5 (V27, V28)

**V27:** a new **Stage 1.5**, sequenced **after** Stage 1's Task 13 acceptance gate lands and
**before** Stages 2-7.

**Why not folded into Stage 1.** Stage 1 (Tasks 9-13) is a closed unit ending in a hard numeric
gate — LUT/FF ≤ +1.0 %, DSP/BRAM delta exactly zero, FMax within 2 % of the uncontended
reference. Adding ports and counters mid-gate muddies a measurement that is about to be taken,
and Task 12's netlist-conformance checker would have to be written twice.

**Why not a parallel plan.** It shares Stage 1's exact substrate: the same `FullCoreSynth`
plugin list, the same generated netlist, the same port checker, the same synth gate — plus the
genuinely coupled D23-exception decision. Two plans racing the same files is precisely how the
sibling's ILA group bit-rotted (`macqd700-soc/docs/ila_a7_drift_probes.md:11-26`, §2.2).

**Why before Stages 2-7 rather than after.** Stages 2-7 need a real hardware bring-up session
to accept. VIO is the instrument that makes that session debuggable when `dbg_axi` misbehaves
— and per §1 that is a scenario with a live precedent, not a hypothetical. Building the
recovery instrument after the thing it exists to recover is the wrong order.

**Shape — ~6 tasks, an outline for the later `writing-plans` pass, not task detail:**

1. **`VioProbePlugin` skeleton + the D23 exception** — the plugin (V6), its `enable` parameter
   (V4), the V4 generation target, and the group's enumeration. No probe logic yet.
2. **Core-side probe RTL** — the PC latch (V11/V12), the saturating retire counter (V13), the
   heartbeat in `porCd` (V15), the state bits, and the bundle assembly (V8/V9). Unit-tested
   against the §11 obligations.
3. **`probe_out` group with mandatory edge-triggering** — V16's detectors, the boot injector
   (V17), and the V18/V19 limits made into tests rather than prose.
4. **SoC-side wiring** — VSOC-1 and VSOC-2 (group 8, `cpu_stub.v` parity, un-deading
   `probe_in5`/`probe_in9`).
5. **IP config + host scripts + the netlist-checker extension** — VSOC-3, VSOC-4, and **V28**.
6. **Re-run the Stage-1 acceptance gate with the group enabled**, delta reported **separately**
   from Stage 1's own, plus SoC-level absolute utilisation per V26.

**V28 — task 5's checker extension is a required deliverable.** `tools/debug/check_debug_netlist.py`
already parses the top module's port list and already learned once, the hard way, that a
structural check written against a hardcoded name goes silently vacuous when something renames
it (its own `async_reset_blocks` docstring records that regression). It is extended to assert:

- every `vio_*` port exists in the **enabled** netlist under its exact group-8 name, with the
  right direction and width; and
- **no** `vio_*` port exists in the default `M68kFullCoreSynth.v`, which is what mechanically
  enforces V4's freeze of the FMax target's port surface.

Both directions are required. Presence-only would let the default target silently grow the
group; absence-only would let the enabled target silently lose it — the exact bit-rot the
`ila_a7_drift_probes.md` precedent describes.

**Gates.** The project's standing rules apply unchanged: `make SBT=~/sbt/bin/sbt test-fast`,
full lock-step, and an **uncontended** post-route gate (FMax on this machine is unreliable
under concurrent Vivado/JTAG sessions — repeatedly confirmed). `git worktree add` is mandatory
for any before/after comparison.

---

## 10. Corrections and extensions to the source material

Places where this spec **departs from, sharpens, or contradicts** the scoping memory. Recorded
rather than silently resolved.

1. **`M68kSocketTop` does not exist** (V5). The scoping memory frames the D23 conflict as if
   the module were real. It is designed but unbuilt: `src/main/scala/m68k040/top/` has no such
   file and the identifier appears in this repository only inside the axi-socket spec. This
   spec targets `FullCoreSynth` and names the socket top as a forward dependency. *Extension,
   not a contradiction — the D23 conflict is real either way, it just cannot be discharged
   yet.*

2. **The `traceOut_k_fire` warning is right but names the wrong seam for a plugin** (V11). The
   memory says "use `fireOut_k` instead", which is correct at the *port* level. V6's plugin
   consumes the *service*, where the same distinction is `CommitTraceService.traceFire(k)`
   (concrete `False` default, `RobPlugin.scala:787`) versus `trace(k).fire` (inside a bundle
   defaulted `assignDontCare()`, `:788`). Same trap, correct name. *Clarification.*

3. **The saturating-counter reuse needs one real change, not a copy** (V13). The memory says to
   reuse `DebugCtrlPlugin`'s pattern, and this spec does — but that counter increments by 1 and
   guards with `=/= max`. The retire counter increments by 0/1/2, and a `=/= max` guard with
   `n = 2` can step *over* the maximum and wrap, which is the exact property the original's
   comment forbids. The guard becomes `< max - n`. *Extension; flagged so an implementer does
   not "simplify" it back to the original form.*

4. **`dbg_pc` was never a committed PC** (§6.1). The memory (and the pre-split socket comment
   at `8df24ac^:rtl/soc/cpu_socket.vh:110`, and `docs/bringup_runbook.md`'s escalation section)
   treat `dbg_pc`/`dbg_committed` as a matched liveness pair. `dbg_pc`'s driver is
   `cpu/rtl/core/fetch/if_stage.v:359`, the **IF-stage fetch PC**;
   `macqd700-soc/docs/rom_boot_bringup.md:439` warns about this directly. `dbg_committed` *is*
   a retire counter. This core's `last_commit_pc` is a **third** quantity (post-instruction
   committed PC, V12). Three different things have been called "PC" across two repositories.
   *Genuine correction of a stale claim in the source material and in the SoC's own historical
   comment.*

5. **The VIO boot injector collides with axi-socket D12/D16, which the memory does not
   mention** (V19). Both want `FetchAlignPlugin.redirect`, and D23 makes it internal on the
   socket top. Resolved here (manual override wins, with the three cases where the injector is
   load-bearing enumerated) rather than left to be discovered during implementation.
   *Extension; a real cross-spec interaction the scoping pass missed.*

6. **The "plan line ~4625 open question about this recovery scenario" could not be located, and
   the claim appears stale.** The memory cites an open question at approximately that line of
   the debug-ctrl plan as directly answered by VIO. At HEAD `5b3cc74` the plan is 4731 lines and
   line ~4625 is inside the spec-traceability matrix; the plan's actual "Open questions I could
   not resolve from real source" section is at `:4676` and a search for `wedge`/`VIO`/`recovery`
   across the file returns nothing matching the described question (`wedge` appears only as the
   four reserved `OFF_WEDGE*` legacy offsets at `:348-351`). **This spec does not rely on that
   citation.** §1 rests instead on the wedge-recovery skill document, which is a live,
   first-hand record and is independently sufficient. *Flagged as unverifiable rather than
   silently dropped: either the line reference drifted, or the question was resolved and
   removed, or it was misremembered — the difference is not determinable from the current tree,
   and it does not change any decision here.*

7. **Two cited figures drifted slightly and are restated from the routed report** (§8.1). The
   memory quotes ≈3.0 LUT / ≈5.4 FF per `probe_in` bit; `utilization_route.rpt:110-114` over
   the 792 currently configured bits gives ≈**2.8 / ≈5.1**. Immaterial to every conclusion, but
   the measured figure is used because it is derivable from a citable row. Separately, the
   memory's **83.6 %** is correct but is a **derived** number — it appears as a literal string
   nowhere in that repository; it is `utilization_route.rpt:24`'s 181 320 / 216 960. Recorded
   so a future reader does not go looking for a string that does not exist, and does not
   accidentally cite `docs/fmax_autopsy_20260425.md:106`'s stale 94 % instead. *Refinement.*

8. **`ila_a7_drift_probes.md`'s bit-rot block is lines 11-26, not 12-27** (§2.2). Trivial, but
   the spec quotes it verbatim, so the range is corrected rather than copied. *Citation
   correction.*

**Confirmed accurate, spot-checked at source rather than copied:** the `started`-gates-fetch
mechanism (`FetchAlignPlugin.scala:189,585`); the `traceOut` dead-constant finding
(`RobPlugin.scala:814-824` in `driveCommit`); `coreHalted` existing but being `simPublic()`-only
(`RobPlugin.scala:375`); the `probe_in5`/`probe_in9` tie-offs and their VIO binding
(`fpga_top_debug_ctrl.vh:46-47`, `fpga_top_debug_vio.vh:418,433,437`); the marker's
count/width-only weakness, which its own authors document at `synth/vivado.tcl:658-668`; the
wedge-recovery event and its single `probe_out1` lever; and the group-7 exception preamble,
quoted verbatim in §2.1 from `cpu_socket.vh:178-185`.

**One scoping-memory claim sharpened rather than corrected:** the memory calls the VIO
instance one that needs "no CDC of its own" and describes the shared `dbg_hub` as already
present with "zero extra `.xdc`". Both confirmed. It also implies the instance is
unconditional; it is `VIO_ENABLE`-gated but that gate defaults on and is mandatory for a real
bitstream (NOTED-5), so the practical claim holds and the literal one does not.

---

## 11. Verification obligations

To be turned into concrete tasks by the implementation plan; listed here so the plan cannot
omit a class of check.

**Probe geometry and the D23 exception (§2, §3.1)**
- Structural, **both directions** (V28): every `vio_*` port present in the enabled netlist with
  exact name/direction/width; **no** `vio_*` port present in the default `M68kFullCoreSynth.v`.
- `enable = false` leaves `M68kFullCoreSynth`'s port list and behaviour bit-identical to the
  pre-change netlist. This is what discharges axi-socket D23's freeze; a diff of the generated
  port list is the check.
- The group's membership matches `cpu_socket.vh` group 8 exactly — no port on one side and not
  the other. Cross-repo, so it belongs to the same task as VSOC-1.

**Coherence and field mapping (§3.2, §3.3)**
- Every field of `vio_cpu_snapshot` is register-driven, and all of them update on the same
  edge. Checkable structurally in the generated Verilog; a combinationally-driven field is the
  failure this catches.
- Reserved bits `[95:73]` and `[69:66]` read exactly zero in every reachable state.
- Bit positions match the §3.2 table, checked against the generated netlist rather than against
  the RTL's intent — this is the thing V7 freezes and the thing every host script depends on.

**PC latch (§3.3.1)**
- Dual retire in one cycle latches **slot 1**'s PC, not slot 0's.
- The latch is qualified on `traceFire(k)` and never on `trace(k).fire`: structurally, no `x`
  reaches the port in a 4-state simulation of a run with idle cycles between retires. This is
  the check that would catch the V11 trap being reintroduced.
- `commit_pc_valid` is `False` until the first retire and `True` thereafter until reset.
- The latched value equals the **post-instruction** PC — the same quantity the lock-step
  harness compares against Musashi's `--trace`, so an existing lock-step run is the oracle.

**Retire counter (§3.3.2)**
- Counts +2 on a dual retire, +1 on a single, +0 on a bubble, over a directed sequence with all
  three.
- **Saturation, specifically at the `n = 2` boundary:** preload to `max - 1` and apply a dual
  retire; the result is `max`, not a wrapped value. This is the V13/§10-item-3 case and the one
  a naive `=/= max` guard fails.
- Reads zero after a core reset with no retires (V14) — i.e. it does not report a previous
  life.

**Heartbeat (§3.4.1)**
- Advances while the **core reset is asserted** (the entire point of V15). Directed: hold
  reset, sample twice, assert the values differ.
- Does not reset on the core reset edge; is unaffected by `coreHalted`.

**`probe_out` edge discipline (§4.1)**
- **The V16 property, directed:** drive `vio_ctl[0]` high, hold it high across a reset
  assert/deassert, and assert that **no** `goPulse` is produced. Then toggle low→high and
  assert exactly one. This is the failure that was hit twice on the real bench, and it is the
  single most important test in this list.
- Exactly one pulse per rising edge, never two, and none on a falling edge.

**Boot injector (§4.2)**
- From reset with `started == False`, a `goPulse` with `vio_boot_pc = P` causes the first
  I-cache fetch to be at `P`, and none before it.
- **V18, directed:** with `coreHalted` set, a `goPulse` produces no retire —
  `retire_count` is unchanged. Asserting the *absence* of the resurrection is what stops
  someone shipping the limit as an accidental feature.
- **V19, directed:** a `goPulse` in the same cycle as a D12 vector-0 redirect results in a
  fetch at `vio_boot_pc`. Deferred until D12 exists; recorded now so it is not forgotten when
  it does.

**Halt-reason (§5.1)**
- `halt_reason` reads zero in every state until D28 lands. Written as a **standing** check, so
  that when D28 connects it the test fails and forces a conscious update rather than silently
  passing on a field that changed meaning.
- `halted` tracks `coreHalted` exactly, including its stickiness.

**Cost gate (§8.1)**
- Re-run the Stage-1 acceptance gate with the group enabled. Report the delta **separately**
  from Stage 1's own delta, against the reference triple (FMax, `CLB LUTs`, `CLB Registers`)
  **re-read at execution time, never hardcoded**.
- Report **SoC-level absolute utilisation** as well as the core-level delta (V26).
- No top timing endpoint may be a VIO probe register or the bundle concatenation — the same
  endpoint rule the debug-ctrl spec §11 applies to debug comparators and CSR read muxes.

**Standing-rule conformance (§7)**
- Review check, not automatable: no address decode, no address window, no range table, and no
  "proven backed" inference anywhere in the probe path. Re-checked by the reviewer of any
  future change to §§3-5, and explicitly re-checked if a peek/poke path is ever proposed.
