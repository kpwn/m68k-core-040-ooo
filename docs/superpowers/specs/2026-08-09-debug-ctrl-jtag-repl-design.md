# `debug_ctrl` for the 040 OoO core — JTAG-REPL-compatible design

**Status:** ACCEPTED ARCHITECTURE ADDENDUM for **Stages 0-1** (integration decisions
resolved in §15, 2026-08-17). Stages 2-7 remain PROPOSED / DESIGN ONLY. Implementation
plan: `docs/superpowers/plans/2026-08-17-debug-ctrl-stage0-stage1.md`.

**Date:** 2026-08-09

**Scope:** the future host-facing debug controller, precise core-stop machinery,
architectural-state access, and cache-maintenance seam for this repository. The SoC
address decode and physical-memory transport remain owned by `macqd700-soc`.

**Compatibility target:** the currently deployed
`/home/qwertyoruiop/macqd700-soc/tools/jtag_repl.tcl`, its Python GDB bridge, and the
actual register map implemented by sibling RTL
`macqd700-soc/cpu/rtl/core/debug/debug_ctrl.v`. Compatibility means the existing
commands continue to perform their documented operation; it does not mean copying
the legacy 3,000-line module or its known defects.

---

## 0. Decision summary

1. Add a 20-bit, 32-bit-data AXI4-Lite `DebugCtrlPlugin`, clocked on the core side of
   the SoC's existing AXI-Lite asynchronous bridge. `0x5090_0000` remains a **SoC**
   decode decision; the plugin sees BAR-local offsets.
2. Preserve the deployed CSR offsets and append-only `OFF_FEATURES` discovery model.
   Unsupported offsets read zero and ignore writes with an OKAY response. A feature
   bit is one only when every operation implied by that legacy bit is real.
3. Keep host configuration in a debug-reset domain which survives CPU/unified reset.
   CPU runtime state is cleared by CPU reset. AXI READY is low while the debug domain
   itself is reset, so no accepted request can lose its response.
4. Make stop, resume, step, breakpoint, halt-after, and halt-on-exception decisions at
   a **macro-instruction boundary owned by commit**. A halt is not reported until
   commit is parked, younger speculative state is recovered, and architectural
   readback is stable. Memory quiescence is reported separately so a wedged bus cannot
   prevent the debugger from stopping and reading registers.
5. Single-step executes exactly one macro-instruction. If that instruction traps,
   stepping stops before the first handler instruction, after exception entry is
   complete. Dual retire must never allow a second macro across the step boundary.
6. Keep breakpoint/opcode comparison out of the commit hot path. A registered
   frontend sideband marks a first-of-macro entry; commit consumes the mark precisely
   before the marked macro has side effects. The sideband pipeline has II=1 and adds
   no idle bubbles.
7. Read D0-D7/A0-A7 through one shared committed-map/PRF snapshot port. Do not add 16
   PRF read ports or a 512-bit always-updating debug mirror. Architectural apply is a
   halted-only sequential transaction and stays halted on completion.
8. JTAG memory reads/writes remain ordinary SoC AXI accesses and therefore bypass the
   CPU's write-back D-cache. The debug core supplies halted-only cache push/invalidate
   commands and truthful completion/rejection status; it does not add a second memory
   master in the core.
9. Add no `Global` database key for debug. Parameters are constructor/config values.
   Cross-plugin communication uses the services in section 5 only.
10. Implement in capability-gated tranches. Manual stop/continue/step and truthful
    status land before optional watchpoints, A-traps, trace, or performance counters.

---

## 1. Evidence and current state

### 1.1 New-core architecture constraints

The governing architecture document,
`docs/superpowers/specs/2026-05-31-m68k-040-ooo-architecture-design.md`, fixes the
following requirements relevant to debug:

- architectural state changes only at the precise instruction commit boundary;
- stores become visible only at commit and cache maintenance is serializing;
- plugin communication is through typed services and named database keys only;
- wide comparisons, high-fanout control, and large muxes are registered/banked;
- extra latency is preferred over a critical combinational path;
- commit trace is a first-class architectural observation point.

Current RTL already has useful building blocks, but not a debug subsystem:

- `RobPlugin` owns normal retire, exception/RTE/system-state sequencing, registered
  redirect, STOP quiesce, and the sticky fatal `coreHalted` state.
- `RenameStage` owns the committed architectural-to-physical maps.
- `RegFilePlugin` provides allocated read/write ports. Separate write sharing keys
  become separate physical ports and may never write the same physical address in the
  same cycle.
- `ExceptionUnit` owns the non-renamed committed state (`SR` system byte, VBR,
  USP/ISP/MSP, SFC/DFC, CACR) and already waits for SQ/D-cache quiescence before
  exception traffic and cache maintenance.
- `DcacheService` already exposes a real maintenance command, done pulse, and a strong
  `maintQuiesced` precondition.
- `FullCoreSynth.BackendWiringPlugin` currently reaches into plugin internals for much
  of the full-core wiring. New debug code must not extend that pattern; the debug seam
  defined here is service-only.

There is currently no AXI debug slave, breakpoint block, debug stop state, JTAG top,
or debug service in this repository.

### 1.2 Implementation reality at the macro boundary

The architecture document describes an instruction-level ROB. The current
implementation allocates entries per uop and carries `firstOfInstr` into
`RobPayload.first`; macro boundaries are reconstructed around those markers. Debug
must depend on an explicit **macro-boundary service**, not on internal ROB shape and
not on simulation-only `commitObs`/`keepCommit` taps. This abstraction also prevents
debug from freezing halfway through a cracked instruction.

The boundary service is responsible for translating the implementation's current
uop retirement into one architectural event per macro. A later instruction-level ROB
can implement the same service without changing `DebugCtrlPlugin`.

### 1.3 Deployed JTAG topology

The current SoC path, verified from `macqd700-soc` RTL and its debug design document,
is:

```text
Vivado JTAG-AXI master
  -> SoC AXI crossbar master M1
  -> peripheral/debug window at absolute 0x5090_0000
  -> AXI-Lite async bridge (pb_clk -> core_clk)
  -> CPU wrapper's 20-bit / 32-bit AXI-Lite debug slave
  -> debug_ctrl
```

The new core should replace only the CPU-side slave and core taps. The upstream
bridge, absolute address, JTAG-AXI transport, Tcl transaction helpers, and separate
ordinary-memory path remain reusable.

### 1.4 Lessons from the deployed controller

The sibling implementation and its integration tests establish several non-obvious
requirements:

- CPU reset must not wipe breakpoint/configuration registers or reset an in-flight
  debug AXI transaction.
- READY must be deasserted during debug reset; accepting an address while the response
  FSM is held in reset creates a permanent host timeout.
- A registered halt decision may have skid. Breakpoints and step must therefore block
  commit at the precise boundary or perform a coherent commit-owned recovery; merely
  freezing a downstream clock-enable is insufficient.
- A resumable stop must never combinationally mask in-flight uops without also
  squashing their ROB/IQ state. The legacy implementation once evaporated such uops
  and permanently wedged the ROB.
- A step completed by "one retired uop" is wrong for cracked instructions and for
  injected exception/RTE traffic. The user-visible unit is a macro-instruction.
- Watchpoint reports may be created speculatively, but a stop may only be raised when
  the owning macro is architecturally accepted. Stores cannot be stopped halfway
  through the commit-to-cache handshake.
- JTAG memory accesses bypass the write-back D-cache. A D-cache push is mandatory
  before host reads; host writes require push, memory write, then D-cache and I-cache
  invalidation.
- Live general-register reads are meaningful only at an effective halt.
- The legacy architectural apply FSM resumes as a side effect. This forces the
  existing GDB bridge to approximate a step after a register write. The new contract
  deliberately removes that defect while retaining old Tcl compatibility.

---

## 2. Goals and non-goals

### 2.1 Goals

- Existing `jtag_repl.tcl` commands can discover and use each implemented feature at
  the same absolute address and local offset.
- Debug idle has no throughput bubbles and does not add a wide unregistered control
  or comparator to fetch, dispatch, issue, execute, or commit hot paths.
- Every resumable stop exposes a coherent architectural state and can make forward
  progress on continue.
- Halt, step, exception stop, breakpoint, and watchpoint semantics are precise at the
  macro-instruction boundary.
- Register read/write and cache maintenance are truthful: rejected operations are
  reported as rejected, not as a plausible zero or a stuck busy bit.
- Debug configuration can be armed before CPU reset and remains armed through it.
- The implementation is small enough to keep the core's FMax/area goals credible and
  can compile out optional trace/watchpoint blocks.

### 2.2 Non-goals

- Implementing a new JTAG TAP, replacing Vivado JTAG-AXI, or moving the debug block in
  front of the SoC crossbar.
- Adding a coherent debugger memory port. Physical memory access remains the SoC AXI
  master's job; cache maintenance makes that view coherent while halted.
- FPU register access in the first implementation. FPU support must receive new
  append-only registers/capabilities when the architectural FPU exists.
- Reproducing legacy wedge snapshots or tied-off performance counters before this
  core has real producers for them.
- Supporting debug operations while the core clock is stopped. The design parks
  architectural progress; it never gates off the clock needed for AXI responses,
  cache drain, or reset synchronization.

---

## 3. Host-visible compatibility contract

### 3.1 AXI4-Lite behavior

The CPU-side slave has 20 address bits, 32 data bits, independent AW/W channels, byte
strobes, and at most one outstanding read plus one outstanding write. It must:

- capture AW and W independently and respond only after both have arrived;
- honor `WSTRB` per byte for ordinary RW fields and use documented write-one pulses
  only on strobed bytes;
- return OKAY/zero for unmapped reads and OKAY/drop for unmapped writes;
- keep READY low during debug-domain reset;
- never clear an accepted request merely because CPU reset asserted;
- pipeline the address/bank decode and read mux as needed. No host command relies on a
  fixed two-cycle legacy latency;
- hold `RVALID`/`BVALID` and payload stable until accepted.

The proposed version epoch is `0xDEB6_0100`. `DBG_BUILD_ID` remains supplied by the
SoC build. Version is informational; behavior is discovered through `OFF_FEATURES`.

### 3.2 Frozen deployed offsets

The following ranges are the compatibility namespace. An implementation may leave a
range absent only by returning zero and clearing the corresponding feature bit.

| Local range / offset | Deployed meaning |
|---|---|
| `0x000` / `0x004` | version / build ID |
| `0x008` / `0x00C` | control / status |
| `0x010` / `0x014` | next live PC / last committed PC |
| `0x018` / `0x01C` / `0x020` | redirect PC / redirect trigger / IRQ inject |
| `0x024` / `0x028` / `0x02C` | last exception vector / PC / reset cause |
| `0x030`-`0x050` | halt-after target, BP0, halt control/reason/hit descriptor |
| `0x054` / `0x058` / `0x05C` | exception fault address / RAM-window selector / monitor sense |
| `0x060`-`0x07C` | 256-bit halt-on-exception mask |
| `0x080`-`0x090` | breakpoint skip-once, BP1-BP3, four-slot enable mask |
| `0x094`-`0x09C` | double-fault and legacy diagnostic PC capture |
| `0x0A0`-`0x0AC` | features / debug-reset control / trace depths |
| `0x0B0`-`0x0DC` | two data-watchpoint configs and queued hit report |
| `0x0E0`-`0x108` | two A-trap slots and capture; these actual deployed offsets win over stale documents which proposed another block at `0x100` |
| `0x200`-`0x214` | D-cache probe, D-cache op, I-cache op |
| `0x1000`-`0x1018` | cycle, macro-retire, and truthful sourced counters |
| `0x2000`-`0x2084` | architectural write shadows and apply/status |
| `0x2100`-`0x2194` | live VBR/SR/SP banks/D/A/MMU/system state |
| `0x3000`-`0x3040` | optional implementation diagnostics/snapshots |
| `0x10000` / `0x11000` | optional PC trace body / head |
| `0x12000` / `0x13000` | optional exception ring / head |
| `0x14000` / `0x15000` | optional retired-branch ring / head |

The first implementation need not decode all of these as functional registers. It
must reserve them, return zero for absent functions, and never repurpose them.

### 3.3 Control and status semantics

Preserve the current `OFF_CONTROL` write encoding:

| Bit | Meaning |
|---:|---|
| 0 | manual halt request level; any accepted CONTROL write with this bit clear is an explicit resume request |
| 1 | single-step pulse |
| 2 | deprecated reset-pulse alias |
| 3 | init-done override level, SoC-owned |
| 4 | cold-reset hold level, survives CPU reset |
| 5 | cold-reset pulse |
| 6 | reserved, reads zero |
| 7 | legacy step-arm observation; host software must continue to use bit 1 |

`OFF_STATUS` preserves bit 0 halted, bit 1 exception pending, bit 2 init-done seen,
bit 3 running, and bit 4 auto-halt latched. `halted` and `running` are mutually
exclusive. `halted` is asserted only for the effective-halt definition in section
6.3; a sampled request is not yet a halt.

The existing Tcl step sequence writes HALT, HALT|STEP, then zero. The controller must
treat this as one step command. The final zero may release an ordinary halt but must
not cancel the step already accepted.

`OFF_HALT_CTL` bit 2 clears sticky reason/report latches but does not itself resume;
an accepted `OFF_CONTROL` write with bit 0 clear performs resume. This preserves the
deployed `continue` sequence and avoids a one-cycle halt-ownership hole.

`OFF_HALT_CTL` bit 0 is the halt-after arm command and reads back the current armed
state. Writing either half of `OFF_HALT_AFTER_LO/HI` advances the configuration epoch
and disarms the comparator; the host arms only after programming the complete 64-bit
absolute target. An automatic hit consumes the arm before the core can be resumed, so
continue cannot immediately retrigger against the same target. A zero-strobe write is
not a programming event and changes neither target, epoch, nor arm state.

### 3.4 Feature discovery

Bits 0-18 retain their deployed meanings and are never renumbered:

```text
0 dbg_reset_domain       7 halt_exc_mask       14 trace_trigger
1 axi_ready_gated        8 fault_snap          15 atrap_bp
2 cfg_wipe               9 rts_snap            16 atrap_regcap
3 cpu_reset_count       10 live_arch           17 atrap_d0qual
4 pc_trace              11 dcache_probe        18 mon_sense
5 exc_ring              12 perf_counters
6 break_pc_multi        13 watchpoints
```

Legacy bit 11 covers both probe readback and cache-operation support. Do not set it
for a maintenance-only tranche unless the deployed probe registers are also real.
Cache-operation commands may still be implemented while bit 11 is clear because
current Tcl addresses them directly, but updated software should gain a new, narrower
append-only discovery bit before depending on that distinction.

Reserve these new append-only bits:

| Bit | Name | Meaning |
|---:|---|---|
| 19 | `arch_apply_stays_halted` | apply is atomic relative to execution and does not resume |
| 20 | `arch_dirty_apply` | only shadows written since the last apply/config-wipe are changed |
| 21 | `cache_maint_only` | halted-only push/invalidate works even if legacy bit 11 probe support is absent |
| 22 | `macro_retire_count` | `OFF_INST_*` and halt-after count architectural macro-instructions, not internal uops |
| 23 | `stop_status_v2` | effective halt means commit recovery and stable architectural state; cache/apply commands additionally wait for reported memory quiescence |
| 24 | `branch_ring` | 32-entry retired-branch history with resolved flow metadata |

Host tools should append these names to their feature list. Old tools ignore the high
bits and continue using the frozen offsets.

No feature bit may advertise a tied-off counter, stale shadow, placeholder probe, or
operation that can be silently dropped.

---

## 4. Block decomposition

```text
AXI-Lite
   |
   v
DebugCtrlPlugin  -- config, CSR bank, reset control, shadow/apply FSM
   | DebugCommitService commands/status/events
   v
RobPlugin        -- macro boundary, park/recover/resume/step authority
   |
   +--> RedirectService -- registered squash/restart
   |
   +--> CommittedMapService --> one PRF read/direct-write port group
   |
   +--> DebugSystemStateService -- committed special state/apply
   |
   +--> DebugMemoryQuiesceService -- SQ/D-cache settled
   |
   `--> DebugCacheMaintenanceService -- serialized cache command/done/reject

FrontendDebugMatchPlugin -- registered PC/opword comparators, marker sideband
   `-----------------------> RobPlugin marker at the macro head
```

This is a logical decomposition. `FrontendDebugMatchPlugin` may be a small Area inside
the frontend owner if that avoids a second stream adapter, provided its configuration
and marker are obtained through services and no plugin internals are reached.

The legacy design's large port bundle is not reproduced. Events and commands are
small registered bundles; bulk architectural shadows remain local to
`DebugCtrlPlugin`.

---

## 5. Service ownership

No new key is added to `Global.scala`. Debug parameters (`enabled`, trace depth,
watchpoint slots) are Scala constructor/config values owned by the debug plugin/top.

Each proposed service has one documented provider:

| Service | Sole provider | Main consumers | Contract |
|---|---|---|---|
| `DebugCommitService` | `RobPlugin` | `DebugCtrlPlugin` | registered halt/resume/step/config commands in; park state, boundary events, count, saved PC, stop event/reason out |
| `CommittedMapService` | `RenameStage` | `DebugCtrlPlugin`, exception path | read-only committed phys IDs for D/A, NZVC, and X; no speculative RAT access |
| `DebugSystemStateService` | `RobPlugin` (delegating internally to its `ExceptionUnit`) | `DebugCtrlPlugin` | live committed special state plus halted-only write command/ack |
| `DebugMemoryQuiesceService` | `LsEuPlugin` | `DebugCtrlPlugin` | SQ empty, no precise drain, and D-cache `maintQuiesced`; no command input |
| `DebugCacheMaintenanceService` | `RobPlugin` or a dedicated cache-maintenance arbiter replacing the current direct exception wiring | `DebugCtrlPlugin`, architectural CPUSH/CINV path | one serialized request/response owner for DC/IC push/invalidate |
| `FrontendDebugMatchService` | frontend debug-match owner | `DebugCtrlPlugin`, `RobPlugin` | configuration snapshot in, precise first-of-macro marker/hit metadata out |

The implementation plan must settle the cache-maintenance provider before RTL. Two
independent drivers of `DcacheService.maintCmd` are forbidden. The preferred migration
is a tiny arbiter/service which accepts mutually exclusive architectural and debug
requests, because debug is allowed only while commit is parked. If the existing
`ExceptionUnit` remains the sole command owner, expose a debug request through
`DebugCacheMaintenanceService` and let that owner sequence it internally.

All service bundles use plain SpinalHDL fields. They do not add direction overrides,
anonymous subclasses, or test-only structural exceptions.

### 5.1 Direct PRF access discipline

The debug plugin allocates:

- one integer PRF read port, with the address selected from
  `CommittedMapService.intPhys(archIdx)`;
- one integer direct-write request;
- one NZVC direct-write request;
- one X direct-write request.

The three direct writes share physical-port keys with the existing exception/RTE
direct writers and use an explicit priority policy. Debug apply is accepted only when
commit is parked, the exception sequencer is idle, speculative state is flushed, and
memory is quiescent, so a collision should be impossible. Sharing the key remains
mandatory because `RegFilePlugin` documents same-address writes from different
physical ports as silent corruption.

Do not add one read port per architectural register. The host issues one live-register
read at a time and tolerates a pipelined response.

---

## 6. Precise halt/resume/step semantics

### 6.1 Stop states

The commit owner implements the following conceptual state machine with registered
state transitions:

```text
RUNNING
  -- sampled stop request --> STOP_PENDING

STOP_PENDING
  -- finish any already-partially-retired cracked macro
  -- forbid retire from crossing into a new macro
  -- stop new frontend admission
  -- at boundary --> RECOVER

RECOVER
  -- registered flush/redirect to the next unexecuted macro PC
  -- rollback speculative RAT/IQ/ROB/frontend state
  --> HALTED

HALTED
  -- stable architectural state access
  -- outstanding AXI responses and older committed stores may finish
  -- cache ops / arch apply wait for DebugMemoryQuiesceService before launch
  -- resume --> registered restart, RUNNING
  -- step --> STEP_RUNNING

STEP_RUNNING
  -- execute one macro; block a second macro in the same dual-retire cycle
  -- normal completion or completed exception entry --> RECOVER with STEP reason
```

The exact implementation may add internal recovery stages, but the observable
preconditions cannot be weakened.

### 6.2 Macro-boundary rules

- Ordinary manual and halt-after stops are post-commit stop-the-world operations, not
  pre-effect break/watch hits. The macro occupying the commit head when such a stop is
  sampled is allowed to complete in full (including all remaining uops if it was
  already partially retired); no following macro may retire. The halt materializes
  only after recovery has flushed all younger in-flight work, so architectural state
  and JTAG register readback present one consistent committed view.
- If retirement is partway through a cracked macro, the remaining uops of that macro
  may complete/retire, but the next macro must not start.
- A fault, interrupt, RTE, or serializing system macro reaches this boundary only when
  its sequencer has installed the final architectural state and redirect PC. A pending
  ordinary halt then parks at that redirect PC; it must not deadlock waiting for the
  normal `retire0` event those paths deliberately do not produce.
- Retire slot 1 is suppressed whenever it belongs to a different macro than slot 0 and
  a stop/step boundary is being taken.
- A faulting instruction completes for step purposes when exception entry has fully
  installed the handler PC and architectural state. The handler's first instruction
  does not retire.
- An RTE/system instruction completes only after its serializing state update and
  redirect are complete.
- An interrupt recognized during step may complete entry and stop at handler entry;
  no state from a partially executed interrupted macro may commit.

These rules require explicit `macroFirst` and either `macroLast` or an equivalent
boundary event from the ROB. Debug code must not infer them from PC equality; multiple
uops intentionally share one PC and loops may revisit a PC.

### 6.3 Effective halt

`OFF_STATUS.halted` and `OFF_HALT_REASON.effective_halt` mean all of:

1. no architectural retirement can occur;
2. no frontend/dispatch admission can create new work;
3. younger speculative entries have been coherently rolled back;
4. the exception/RTE/system sequencer is idle;
5. the committed map and non-renamed system state are stable for readback.

Execution-unit completions already accepted before recovery may drain internally, but
they may not alter committed state or a reallocated ROB entry. The clock and bus
response machinery continue running.

Effective halt deliberately does **not** require the store queue or D-cache to be
idle. An older committed store, refill, or write response may still be finishing. If a
bus is wedged, requiring global memory quiescence here would make the debugger unable
to stop at the exact moment it is needed most. `DebugMemoryQuiesceService` therefore
reports that condition separately. Live architectural reads are valid as soon as
effective halt asserts; cache maintenance and architectural apply accept/hold a
command but do not launch their shared memory/state resources until quiescence is
true.

Fatal `coreHalted`/double-fault state also sets effective halt, but it is non-resumable
and has a distinct reason. A resume request is explicitly rejected until CPU reset.

### 6.4 Manual halt and resume

Manual halt is a level in `OFF_CONTROL` for readback compatibility, but the commit
owner receives registered commands. A host write with bit 0 set requests a stop. An
accepted CONTROL write with bit 0 clear requests resume, even if the stored level was
already clear before an automatic halt.

Automatic stops park independently of sticky reason bits. Clearing reason latches
cannot briefly release the core. Resume clears the parked state only after any required
breakpoint skip-once state is armed.

Manual halt is intentionally post-commit. Breakpoints/watchpoints retain the distinct
pre-effect rule in section 6.7: their matching macro does not commit.

### 6.5 Single-step

Step starts only from effective halt. It releases fetch/commit at the saved next PC and
allows exactly one macro-instruction to complete. It then returns to effective halt at
the following PC. A trapping step stops at handler entry after the complete exception
frame/state transition.

Step does not use a retired-uop counter and does not rely on a delayed global halt
overlay. The one-step budget and cross-macro dual-retire gate are local to the commit
owner.

The command is rejected, with a sticky rejected status, if the core is running,
fatally halted, applying architectural state, or performing cache maintenance.

### 6.6 Halt-after and exception stop

`OFF_INST_LO/HI` is a monotonic architectural macro count. `OFF_HALT_AFTER_LO/HI` is
an absolute target. Halt-after triggers after the target macro completes and stops
before the following macro. Count comparison may be pipelined; a pending result must
carry the count/epoch so it cannot stop on a stale target after host reprogramming.

The count advances only when the last uOp of a macro retires. The implemented registered
comparator holds retirement at the intervening clean macro boundary while a new count is
sampled; its 64-bit comparison therefore does not enter the live commit path. While
halt-after is armed, dual retirement may not cross from a completed macro into its
successor. A target-write invalidation is observed on the accepted write edge, before a
result from the superseded epoch can acquire halt ownership.

The inhibited-load launch interlock may conservatively dominate that precise
retirement guard. It must never miss a pending/due halt-after boundary; a stale
comparison may only postpone a device read until configuration refreshes. It
must not authorize halt ownership or stall ordinary cacheable loads. The
ROB-owned `DebugLoadPreemptService` exports this guard without putting epoch
validation on LSU flow control; see `docs/debug-load-guard.md`.

Halt-on-exception uses the 256-bit deployed mask and stops at completed handler entry.
The exception descriptor (vector, fault PC, fault address) is captured atomically with
the event and remains stable until acknowledged.

### 6.7 PC breakpoints

Four deployed PC slots are eventual compatibility scope. They are precise and
pre-effect: when a marked macro reaches the commit boundary, it does not commit, the
core recovers to that macro's PC, and the host sees that PC in `HALT_HIT_PC`.

The match is performed in a registered frontend side pipeline:

- comparator inputs are registered PCs/config local to the frontend;
- compare throughput matches the frontend width every cycle;
- results travel as a one-bit marker plus slot ID with the macro;
- the comparison never drives fetch/decode ready combinationally;
- the marker is honored only for the first uop of the macro;
- a wrong-path marker is discarded by ordinary recovery.

On a hit, hardware arms skip-once for the matching slot before reporting HALTED.
Continue refetches the same PC without re-marking it once; the skip bit is consumed and
the slot becomes active again. Host writes to `OFF_BP_SKIP_ONCE` remain supported.

---

## 7. Architectural register access

### 7.1 Live readback

Live D/A reads select an architectural index, read its committed physical ID from
`CommittedMapService`, and use the single debug PRF read port. Special-state reads use
`DebugSystemStateService`. A registered AXI response gives the selected data time to
settle; a second latency cycle is acceptable.

Readback includes the deployed set:

- D0-D7, A0-A7;
- SR (system byte plus committed X/NZVC), PC, VBR;
- USP, ISP, MSP through the frozen `*_SSP` compatibility offsets, and active A7;
- CACR, SFC, DFC;
- TC, ITT0/1, DTT0/1, URP, SRP and MMUSR. The append-only live registers at
  `0x2184`-`0x2194` complete the dump with CACR, SFC, DFC, a canonical PC alias,
  and MMUSR; `OFF_PC` remains the compatible primary next-PC readback.

This core implements the 68040 three-bank stack model. The deployed register names
`OFF_ARCH_SSP` and `OFF_LIVE_SSP` are therefore retained at their frozen offsets but
mean the master supervisor stack bank (MSP); `*_ISP` names the interrupt supervisor
stack bank. They are never aliases of one storage element. A7 remains the active
USP/ISP/MSP value selected by committed `(S,M)`.

General-register live reads are valid only at effective halt. Existing Tcl/GDB already
enforces this. Raw reads while running are outside the validity contract and must not
be cached or described as a snapshot.

### 7.2 Shadow writes and dirty mask

Writes to `0x2000`-`0x2084` update debug-domain shadow registers and set one dirty bit
per architectural field. Byte strobes merge into the shadow. Config wipe clears both
shadow values and dirty bits.

Dirty apply fixes a dangerous legacy behavior: changing one register must not write
zero/default shadows into every other register. A full checkpoint restore remains
possible by writing every shadow before apply.

### 7.3 Apply transaction

Writing `ARCH_APPLY_START`:

1. rejects if not effectively halted or if another debug operation is busy;
2. snapshots the dirty mask and requested PC;
3. waits for memory quiescence, then confirms the committed map is stable and
   speculative state recovered;
4. sequentially writes dirty D/A mappings through the shared direct-write port;
5. writes dirty NZVC/X and non-renamed system/MMU state through their sole-owner
   services;
6. requests the appropriate TLB/cache/predictor invalidations when TC/TTR/root/CACR or
   PC changes require them;
7. installs the dirty PC as the next restart PC;
8. clears the applied dirty bits and sets DONE;
9. **remains halted**.

Sequential physical writes are atomic relative to execution because the core cannot
resume while BUSY. No architectural observer can see the intermediate cycles.

`OFF_ARCH_STATUS` retains bits BUSY=0, DONE=1, REJECTED=2. DONE means all writes and
required invalidations completed. REJECTED is never encoded as DONE. A clear-status
write clears DONE/REJECTED without affecting halt.

Old `jtag_repl.tcl` remains compatible: its `arch-apply` command only polls status, and
its later `continue` write releases the core. The current Python GDB bridge also works
for continue. Updated GDB tooling can check feature bit 19 and perform an exact
register-write-then-step instead of reporting the legacy approximation.

---

## 8. Memory and cache semantics

### 8.1 Memory transport ownership

The debug CSR block is not a memory master. `r`, `w`, `dump-mem`, and GDB memory
packets continue to address the SoC crossbar directly. Addresses are physical from the
debugger's perspective; CPU MMU translation is not applied to JTAG-AXI traffic.

Consequently:

- before a host read, push dirty D-cache lines to memory;
- before a host write, push first so dirty CPU data is not lost;
- perform the host write;
- invalidate D-cache and I-cache before resume.

This is the deployed GDB bridge's sequence and remains required for the new core.

### 8.2 Cache-operation contract

The `OFF_DCACHE_OP`/`OFF_ICACHE_OP` encodings and status remain:

- write bit 0 START;
- D-cache write bit 1 selects push versus invalidate;
- read bit 0 BUSY, bit 1 DONE, bits 3:2 selected cache(s).

The implementation records REJECTED in bit 4 and ERROR in bit 5 of both operation
registers. The two addresses expose the same shared status word. A new START clears
the previous DONE/REJECTED/ERROR result before accepting or rejecting that request; it
must not silently leave BUSY=0/DONE=0 for a rejected command.

A cache command is accepted only at effective halt. The shared maintenance owner
reserves it atomically, sets BUSY, and waits for `DebugMemoryQuiesceService` before
launching the real walk. This preserves the current host sequence, which may issue a
push immediately after HALTED, while closing the legacy halt-request/ack skew. The CSR
must not set BUSY based merely on a halt **request**. DONE is set only after the real
walk and all writeback responses finish. Simulation uses a configurable completion
watchdog/assertion; a bus-timeout integration reports ERROR rather than pretending the
cache became coherent.

The existing `DcacheService.maintQuiesced` contract is the launch precondition. Debug
and architectural CPUSH/CINV share one command owner; no mux may create overlapping
drivers or overlapping AXI AW/W ownership.

### 8.3 D-cache probe

Legacy feature bit 11 is asserted only after the select/tag/flags/data registers at
`0x200`-`0x20C` read actual arrays and the cache operations work. The probe is
halt-safe and may borrow the maintenance array read port. It is not placed on the
normal load tag/data path and may take multiple cycles.

---

## 9. Watchpoints, A-traps, and trace (later tranches)

### 9.1 Data watchpoints

Preserve the two deployed physical-address slots and descriptor layout. Compare after
translation using the access's physical address. A match creates a report tagged with
the owning ROB/macro identity; it does **not** directly halt the core.

- Wrong-path load matches are discarded when their ROB owner is squashed.
- A load report becomes stoppable only when its macro commits.
- Store address/data may be matched before SQ drain, but the stop is raised only after
  the store macro has committed and its required memory handshake is safe.
- A cracked RMW may produce separate read and write reports; retain the depth-two
  deployed report queue and overflow indication.
- The eventual halt is at the next safe macro boundary, never mid-crack or
  mid-exception entry.

The compare pipeline is registered at the LS boundary and must not lengthen the DTLB,
SQ-forward, D-cache CAM, or response path.

### 9.2 A-trap breakpoints

Preserve the two deployed A-line opcode slots, care-mask polarity, optional D0
qualifier, A0/D0 capture, and skip-once behavior. Opcode comparison uses the same
registered frontend marker path as PC breakpoints. Capture reuses the one debug PRF
read port after the precise pre-effect halt; it does not add dedicated D0/A0 ports.

A D0 qualifier is resolved from committed state while parked. A mismatch refetches
with skip-once and resumes without reporting a hit. This feature is optional and must
remain capability-gated because a nonmatching qualified trap incurs a debug recovery.

### 9.3 Trace and telemetry

PC trace, retired-branch, and exception rings use synchronous BRAM with a registered
AXI read response. Depths are reported by `OFF_CAP_TRACE` and `OFF_CAP_TRACE2`; host
code never assumes the RTL default. Trace is optional by constructor parameter and
must compile out cleanly. `OFF_CAP_TRACE` reports PC depth in `[31:16]` and exception
depth in `[15:0]`; the append-only `OFF_CAP_TRACE2[15:0]` reports retired-branch depth.

The initial PC-retirement trace depth is 32 entries. It records architectural macro
completion, not uop activity: push the macro's raw PC only when a retiring ROB entry
has `lastOfInstr=True`. If two independently complete macros retire in one cycle,
push slot 0 then slot 1 so ring order remains program order. `OFF_PC_TRACE_HEAD`
identifies the next write position; after wrap, the preceding 32 ring positions are
the 32 most recently completed macros. The ring remains live while RUNNING and freezes
naturally once effective halt is reached, giving JTAG a stable history alongside the
stable architectural snapshot.

The initial retired-branch history depth is also 32 entries. It records only
architecturally retired branch-family macros, never speculative resolution. One entry
occupies four 32-bit words at `OFF_BRANCH_RING_BODY + index*16`: raw branch PC,
resolved next PC, metadata, and a reserved-zero word. Metadata bit 0 is taken, bit 1
is mispredicted, bits `[3:2]` are the branch type, and all other bits are reserved
zero. `OFF_BRANCH_RING_HEAD` is the next write position. Branch history is pushed at
the same macro-commit boundary as the PC trace, so a halted reader sees only committed
control flow and no wrong-path branch.

The initial exception history depth is 32 entries. One entry occupies four 32-bit
words at `OFF_EXC_RING_BODY + index*16`: metadata, exception PC, fault address, and
installed handler PC. Metadata bits `[7:0]` hold the vector and all remaining bits are
reserved zero until an append-only format revision defines them. Push exactly once
when exception entry has installed its final architectural state and handler PC; do
not push for speculative faults or intermediate exception-entry uops.

All three head registers identify the next write position. After wrap, walking the
preceding `depth` entries yields oldest-to-newest history. They remain live while
RUNNING and freeze naturally only once effective halt is reached; no explicit freeze
command is required for the initial implementation.

Performance feature bit 12 remains zero until every advertised counter has a real
producer. Cycle count, macro count, exception count, flush count, predictor
mispredictions, and cache hit/miss events should be exposed by typed observation
services, not by reaching into implementation internals.

---

### 9.4 Passive fetch-word check (2026-09-18)

Append capability bit 25 `fetch_word_check` and registers `0x1800..0x1814`
defined by `debug_regmap.def`. This optional diagnostic observes
`host[DecodeFeedService]` accepted packets, including speculative packets; it
does not imply retirement. It never drives stream ready, halt, flush, recovery,
or any architectural state. There are no new Global keys.

Host disables CTL bit0, programs exact virtual PC and expected low-16-bit opcode,
then writes CTL=3 to clear and enable. Registered copies of both accepted packet
lanes feed the comparator; slot1 requires its own valid qualifier. Fault packets
are excluded. SEEN saturates at 0xffffffff and counts selected-PC observations,
including correct words, so zero cannot masquerade as a clean observation.
The first differing opcode latches HIT_PC, HIT_WORD `{expected, observed}`, and
CTL bit1; CTL bit2 records the lane (slot0 wins simultaneous mismatches).
Later packets cannot overwrite the captured mismatch. Reprogram only disabled.
CTL clear is strobe-qualified, wins over a simultaneous capture, and leaves
PC/WORD configuration unchanged. CPU reset clears pending samples, evidence and
SEEN while preserving configuration/enable; config wipe clears all state.

Built only with enabled debug, stage >=5, enabled optional history, and an actual
DecodeFeedService producer. Otherwise its feature bit is clear and all offsets
are RAZ/WI. This is an opcode/virtual-PC association observer, not an ATC probe
or an instruction-memory coherence oracle: the host must establish that the
selected PC should hold the expected immutable instruction in that context.

## 10. Reset and CDC

### 10.1 Reset domains

Use two reset classes on the same downstream core clock:

| State | Reset source | Examples |
|---|---|---|
| debug-owned/config | board POR or explicit debug-domain reset only | AXI FSM, halt/BP/WP configs, exception mask, arch shadows/dirty bits, cold-reset hold, feature/version registers |
| CPU runtime | CPU/unified reset notification | retired count, stop reason/hit reports, live operation BUSY/DONE, pending markers, stop/step state |

The AXI slave and cold-reset-hold register must never be reset by the CPU reset they
request. A synchronized CPU-reset edge increments the surviving reset counter and
clears runtime state without disturbing accepted AXI handshakes or host configuration.

On CPU reset:

- any in-progress apply/cache command terminates with an explicit reset-aborted status;
- core-side stop state returns to reset behavior;
- breakpoint/watchpoint/A-trap configuration remains armed;
- runtime hit reports and counters clear as defined;
- cold-reset hold remains asserted until the host writes it clear.

### 10.2 Clock-domain boundary

Baseline integration mandates the existing SoC AXI-Lite async bridge. Everything
downstream, including `DebugCtrlPlugin`, stop service, and cache service, runs on the
core clock. This makes the debug-reset split a reset-domain issue, not a data CDC.

If a future integration clocks the CSR block separately, commands cross through a
request/ack toggle or asynchronous FIFO and multiword status crosses through a frozen
snapshot mailbox. Multi-bit buses must never be synchronized bit-by-bit. Halt/config
levels must be synchronized before use, and reset deassertion must be synchronized in
each destination domain.

Reset/pulse outputs which leave the core clock domain are synchronized by the SoC
reset controller. The core plugin exposes intent; it does not assume the destination
clock.

---

## 11. Idle-path FMax and area rules

1. Debug configuration is registered near its consumer. Do not broadcast raw AXI CSR
   mux outputs across the core.
2. PC/opword/watchpoint equality trees are registered and their results are sideband
   payloads. They do not drive a hot ready/valid signal combinationally.
3. Halt commands may cost an extra cycle to register. Debug response latency is not an
   IPC concern; idle execution must remain bubble-free.
4. The commit owner sees only a small registered command/marker/reason bundle. Wide
   halt-after and exception-mask lookups are pipelined or locally banked with event
   epoch tags.
5. Use one PRF read port and shared direct-write ports. Do not mirror all architectural
   registers into debug FFs.
6. Trace memories infer BRAM and are optional. No debug function uses DSPs.
7. An `enabled=false` build removes the AXI/debug plugins without adding `Global` keys
   or changing the core's architectural interfaces.

Provisional acceptance gates, measured against the same parent commit and identical
post-route flow:

- basic CSR + stop/step + arch/cache tranche: no more than +1.0% device LUT and FF,
  no DSP/BRAM except explicitly enabled trace, and achieved FMax no worse than 2% from
  the uncontended reference while remaining above the architecture's hard floor;
- complete optional breakpoint/watch/A-trap set: no more than +2.0% device LUT/FF;
- no top timing endpoint may be a debug comparator, CSR read mux, or high-fanout debug
  enable. If one appears, add a pipeline/register stage before accepting the slice.

These are gates, not estimates. Report absolute utilization and deltas for every
implemented tranche.

---

## 12. Staged implementation and verification

### Stage 0 — contract and host conformance

- Land this architecture addendum before RTL.
- Add one machine-readable register/capability definition in the new repo and generate
  or compare the Scala RTL constants, Tcl constants, and Python constants against it.
- Import/adapt the sibling fake-REPL host tests. Pin unmapped-zero, byte strobes,
  feature-bit honesty, step command sequence, cache polling, and arch-apply polling.

### Stage 1 — AXI/reset/status shell

- Implement `DebugCtrlPlugin` with version/build/features, CONTROL/STATUS,
  debug-reset control, CPU-reset count, cold-reset outputs, and RAZ/WI behavior.
- Unit-test independent AW/W order, backpressure, byte strobes, reset during idle, CPU
  reset during accepted transactions, and READY-low during debug reset.
- Keep every optional feature bit zero.

### Stage 2 — precise manual halt/resume/step

- Add `DebugCommitService` and the explicit macro-boundary event.
- Integrate fetch admission stop, registered recovery redirect, stable-state effective
  halt, separate memory-quiescent status, fatal-halt reporting, macro count, and
  halt-after.
- Directed tests: stop before a fresh macro, stop midway through every cracked family,
  dual-retire crossing, outstanding load/refill/store drain, manual halt during
  exception/RTE, resume liveness, repeated halt/continue, and fatal-halt rejection.
- Step every single-uop and cracked family; step A-line/F-line/TRAP/bus-error/RTE/STOP
  cases; assert exactly one macro or completed handler entry and continued liveness.

### Stage 3 — live state and halted apply

- Add committed-map/system-state services and shared PRF ports.
- Provide a complete coherent register dump at effective halt and halted-only register
  set through the shadow/dirty/apply transaction; JTAG must never sample speculative
  maps or expose a partially applied register set.
- Implement all legacy live/shadow offsets, dirty mask, apply FSM, rejection, and
  stays-halted capability.
- Round-trip every D/A, X/NZVC/SR bit, all SP banks, PC/VBR/CACR/SFC/DFC, and MMU state.
- Prove one-register dirty apply leaves every other register unchanged.
- Assert no direct-write physical-port collision and no retirement while apply BUSY.
- Run the Python GDB register-packet tests, including register-write then exact step.
- Add the initial 32-entry committed macro-PC, retired-branch, and completed-exception
  histories described in section 9.3. These are part of the boot-debug surface: they
  share the coherent halted view and do not imply that Stage 7 performance telemetry
  is present.
- Validate wrap/head semantics, synchronous-read latency, simultaneous dual-retire
  events, and committed-only branch/exception producers.

### Stage 4 — cache maintenance and SoC memory truth

- Arbitrate debug and architectural maintenance through one owner.
- Implement D/I all push/invalidate, DONE/REJECTED/ERROR, and later the deployed probe.
- Test dirty-line host reads, host writes followed by D/I invalidate, in-flight store
  drain, writeback error, CPU reset abort, and repeated operations without stuck BUSY.
- In `macqd700-soc`, map the slave at `0x5090_0000` through the existing async bridge
  and run the same fake-REPL/GDB memory tests before hardware.

### Stage 5 — precise break/exception facilities

- Add the registered frontend marker sideband, four PC slots, hardware skip-once,
  256-bit exception mask, atomic reason descriptor, and honest feature bit 6/7.
- Test pre-effect register/store behavior, wrong-path matches, same-PC loops, all four
  slots, reset-persistent arming, step from a breakpoint, and simultaneous reasons.
- Define reason priority for the single primary code while retaining all sticky legacy
  bits: fatal diagnostic > completed exception > precise breakpoint/A-trap > step >
  committed watchpoint > halt-after > manual.

### Stage 6 — watchpoints and A-traps

- Add ROB-tagged watchpoint reports and the depth-two queue, then A-trap match/capture.
- Reuse the exact deployed layouts and set bits 13/15/16/17 only after full integration
  tests pass.
- Cover speculative-load rejection, RMW two-report behavior, split accesses, store
  commit/drain safety, D0 qualifier mismatch, and capture/read-port arbitration.

### Stage 7 — trace and truthful telemetry

- Add only telemetry with real event producers, beginning with the optional performance
  counters described in section 9.3. Do not advertise counters or enhanced trace
  controls until every reported value is backed by implemented hardware.

### Gates for every RTL stage

1. `make SBT=~/sbt/bin/sbt test-fast`.
2. Focused Scala/Spinal simulations for the changed service/block.
3. Full-core lock-step and Verilator integration under the project's PM-serialized
   full-test policy.
4. Generated-Verilog lint and structural checks for multiple drivers, latches, and
   PRF write collisions.
5. Uncontended post-route comparison only after confirming no Vivado/JTAG-sensitive
   build is active. Never run two Vivado sessions concurrently.
6. SoC fake-host tests, then an explicitly scheduled hardware session. Hardware must
   verify arm-before-reset, halt/continue/step liveness, register write+step, and cache-
   coherent memory reads/writes.

---

## 13. Required assertions

The implementation should carry synthesis-excluded assertions for at least:

- effective halt implies no retire, allocation, or frontend admission;
- HALTED is never asserted before coherent recovery and stable architectural state;
- a step consumes at most one macro event and retire slot 1 never crosses its boundary;
- a breakpoint-marked macro has no architectural write/store before the stop event;
- a resume request cannot release a fatal halt or an apply/cache BUSY state;
- accepted cache maintenance reaches DONE, ERROR, ABORTED, or REJECTED;
- no maintenance command launches unless SQ and D-cache quiescence are true;
- debug/exception direct PRF writers never collide;
- an apply changes only dirty fields and cannot retire concurrently;
- CPU reset cannot change surviving debug configuration;
- debug reset cannot accept AXI requests;
- feature bits imply reachable, non-tied-off behavior.

---

## 14. Open integration decisions

These do not block the architecture but must be fixed in an implementation plan:

1. Whether the shared cache-maintenance owner is a new arbiter plugin or an extension
   of the ROB/ExceptionUnit owner. It cannot remain top-level internal wiring.
2. RESOLVED for Stage 5 in section 15.6: the frontend owns a registered configuration
   snapshot and stamps `{debugBreakValid, debugBreakSlot}` into the ordinary uop
   payload; decode, rename, and the ROB copy those fields verbatim.
3. The SoC socket/reset-port naming for debug POR, CPU-reset notification, build ID,
   cold-reset hold/pulse, and init-done observation.
4. Which append-only offset exposes cache REJECTED/ERROR and architectural dirty-mask
   diagnostics. Existing offsets and bit meanings remain frozen.
5. Whether the initial SoC tranche implements the D-cache probe together with cache
   maintenance (allowing legacy feature bit 11) or advertises only new bit 21.

None of these permits direct access to another plugin's internals, a second producer
for a `Global` key, or an idle-path throughput bubble.

---

## 15. Resolved integration decisions for Stages 0-1 (2026-08-17)

This section resolves the parts of §14 that block Stage 0/1 and records the
Stage-1 behavioural choices that the implementation plan depends on. §14 items
1, 2, 4 and 5 remain open; they block Stage 2 or later and are deliberately not
decided here.

### 15.1 §14 decision 3 — SoC socket and reset port naming (RESOLVED)

The core exports the socket names **verbatim**, forced onto the generated
Verilog with SpinalHDL `setName()`, so `macqd700-soc` binds them with no rename
shim. The authority is `macqd700-soc/rtl/soc/cpu_socket.vh` §4 (lines 145-162)
and §6 (lines 170-176); the names are additionally machine-checked against that
file by `tools/debug/test_sibling_conformance.py`.

| Core-side port | Dir | Width | Socket authority |
|---|---|---:|---|
| `dbg_axi_awaddr` / `awvalid` / `awready` | in/in/out | 20/1/1 | `cpu_socket.vh:146-148` |
| `dbg_axi_wdata` / `wstrb` / `wvalid` / `wready` | in/in/in/out | 32/4/1/1 | `cpu_socket.vh:149-152` |
| `dbg_axi_bresp` / `bvalid` / `bready` | out/out/in | 2/1/1 | `cpu_socket.vh:153-155` |
| `dbg_axi_araddr` / `arvalid` / `arready` | in/in/out | 20/1/1 | `cpu_socket.vh:156-158` |
| `dbg_axi_rdata` / `rresp` / `rvalid` / `rready` | out/out/out/in | 32/2/1/1 | `cpu_socket.vh:159-162` |
| `cpu_cold_reset_pulse` | out | 1 | `cpu_socket.vh:171` |
| `cpu_cold_reset_hold` | out | 1 | `cpu_socket.vh:172` |
| `cpu_ram_window_lg2` | out | 6 | `cpu_socket.vh:173` |
| `cpu_mon_sense` | out | 7 | `cpu_socket.vh:174` |
| `init_done_seen` | in | 1 | `cpu_socket.vh:176` |

There is **no** `AWPROT`/`ARPROT` on this interface. `cpu_socket.vh:145-162` does
not declare them, so the standard SpinalHDL `AxiLite4` bundle (which does) is not
used; a dedicated `DbgAxiLite` bundle carries exactly the socket's signal set.

**Debug power-on reset.** The socket has *no* board-level POR input
(`cpu_socket.vh:95-97` gives only `clk` and `rst`). The core therefore generates
its own, exactly as `macqd700-soc/cpu/rtl/core/debug/debug_reset_ctl.v` does: a
counter of deliberately reset-less flops whose only initial value is the FPGA
configuration INIT, producing a `POR_CYCLES`-long pulse after configuration and
then deasserting forever. In SpinalHDL this is a derived `ClockDomain` with
`resetKind = BOOT`. `debug_reset_ctl.v`'s header states the requirement this
satisfies: "dbg_rst … Asserted for POR_CYCLES clocks after FPGA configuration,
then deasserted FOREVER -- it is NOT a function of cpu_rst." A POR pulse rather
than bare INIT is mandatory because the debug domain holds registers with
non-zero reset values (`cpu_ram_window_lg2` = 26, `cpu_mon_sense` = 7'h06).
`POR_CYCLES` default is 16, matching the sibling module's default.

**CPU-reset notification.** The core reset is *observed*, never consumed, by the
debug domain: a rising-edge detector on the socket `rst`, clocked in the debug
domain, with reset value 1 so a `rst` already high when the debug domain leaves
POR does not manufacture a spurious edge (`debug_reset_ctl.v:133-142`). No extra
socket port is added.

**Build ID.** `DBG_BUILD_ID` remains SoC-supplied (§3.1). It enters the core as a
`DebugCtrlPlugin` constructor parameter, defaulting to `0x00000000`, which the
Verilog generator may override from the environment. It is deliberately NOT a
socket port and NOT a `Global` database key (§0.9).

**Init-done observation.** `init_done_seen` is a level input; the debug domain
latches it sticky so a debugger attaching after DDR calibration still sees it.
The deployed reference sends `OFF_CONTROL` bit 3 (init-done override) to its own
DEDICATED output port, `dbg_init_done_override` (`debug_ctrl.v:157`, `:966`),
and `OFF_STATUS` bit 2 reads `init_done_latched` ALONE, with no OR
(`debug_ctrl.v:1426`) — consistent with this same spec's own §3.3 ("init-done
override level, SoC-owned"). This core's `cpu_socket.vh` SoC-fabric control
group has no init-done-override output at all, so there is nowhere on this
socket to send that signal. This design therefore makes a **deliberate
divergence** from the deployed reference: `OFF_CONTROL` bit 3 ORs directly
into the same bit `OFF_STATUS` bit 2 reads, rather than driving a separate
port. Naming the real consequence: STATUS bit 2 — a claim that DDR
calibration completed — becomes forgeable by the host itself, which is
exactly the class of thing §15.2 forbids two paragraphs below (a host must
"discover the truth by reading back rather than by waiting for a status bit
that will never set"). This is judged acceptable here because the forgery is
self-inflicted: a debug host that writes CONTROL bit 3 is lying to *itself*
about DDR-cal completion — there is no third party relying on the bit for the
lie to defraud. Any Stage-2-or-later extension that lets an agent other than
the writer act on STATUS bit 2 must revisit this call.

Sticky init-done latching does **not** survive CPU reset, despite living in
the debug reset domain — living in that domain does not by itself mean
surviving CPU reset; `debug_reset_ctl.v` exists specifically so logic in that
same domain can tell the two categories apart, via the CPU-reset rising-edge
detector at `debug_reset_ctl.v:133-142`. The deployed reference explicitly
WIPES both `ctrl_init_done_override` (`debug_ctrl.v:2468`) and
`init_done_latched` (`debug_ctrl.v:2561`) inside a `counters_clear`-gated
block (`debug_ctrl.v:2464`, itself driven from that same rising-edge
detector) on every CPU-reset rising edge. The contract comment at
`debug_ctrl.v:2447-2462` names this "CPU-COUPLED RUNTIME state" — state
that, if it survived, would report a previous life as the current one — as
exactly the category that must NOT survive, distinct from "host
configuration" (break/halt-exception config, `ram_window_lg2_r`,
`mon_sense_r`, the arch shadows, and `ctrl_cold_reset_hold`) which must.
Stage 1 does not implement any init-done-latching RTL yet — that lands in
Task 9 — so this spec records the requirement for that implementer rather
than prescribing RTL here: Task 9 must add a CPU-reset rising-edge detector
in the debug clock domain (mirroring `debug_reset_ctl.v:133-142`'s
`cpu_rst_event`) and gate both the sticky init-done latch and the
CONTROL-bit-3 override register on it, clearing each to its POR value of 0
on that edge, exactly as `debug_ctrl.v:2464`'s `counters_clear` block does.
`cold_reset_hold` is explicitly **not** part of this wipe set — §15.2
already states it survives CPU reset, matching the deployed reference.

### 15.2 Stage-1 CONTROL and STATUS behaviour

Stage 1 has no halt/step machinery, so it must not *pretend* to. Per-bit
behaviour, chosen so a host discovers the truth by reading back rather than by
waiting for a status bit that will never set:

| CONTROL bit | Stage-1 behaviour |
|---:|---|
| 0 manual halt request | **RAZ/WI** — write ignored, reads 0 (Stage 2 implements it) |
| 1 single-step pulse | **RAZ/WI** (Stage 2) |
| 2 deprecated reset-pulse alias | real: aliases bit 5 |
| 3 init-done override level | real |
| 4 cold-reset hold level | real, drives `cpu_cold_reset_hold`, survives CPU reset |
| 5 cold-reset pulse | real, drives a 1-cycle `cpu_cold_reset_pulse` |
| 6 reserved | reads 0 |
| 7 legacy step-arm observation | **RAZ/WI** (Stage 2) |

`OFF_STATUS` in Stage 1: bit 0 `halted` = 0, bit 1 `exception pending` = 0, bit 2
`init_done_seen` = real, bit 3 `running` = 1, bit 4 `auto-halt latched` = 0. This
keeps §3.3's "`halted` and `running` are mutually exclusive" true.

**Known gap, deliberately accepted:** the frozen contract has no discovery bit
for manual halt itself, so a Stage-1 build is distinguishable from a
halt-capable one only by `OFF_VERSION` (`0xDEB6_0100`) and by CONTROL bit 0
reading back 0 after a write. A new append-only bit for basic stop/step should
be minted when Stage 2 lands, and this is recorded as an open item rather than
worked around.

### 15.3 `cfg_wipe` scope (feature bit 2)

`OFF_DBG_RESET_CTL` bit 0 restores host configuration to POR defaults **without
resetting the AXI slave FSM**, so the very write that requested the wipe still
receives its `B` response. This copies `debug_ctrl.v:3016-3024` verbatim,
including its explicit contrast with a true debug-domain reset ("that DOES
disturb the AXI FSM and is therefore fire-and-forget from the host's point of
view"). In Stage 1 the wipe restores `cpu_ram_window_lg2` to 26 and
`cpu_mon_sense` to `0x06` and nothing else. It deliberately does **not** clear
`cold_reset_hold` or the init-done override, because the deployed wipe list
(`debug_ctrl.v:3024-3062`) does not clear them either, and feature bit 2 must
mean the same thing on both cores.

### 15.4 CPU-reset counter

`OFF_DBG_RESET_CTL[31:16]` is a 16-bit count of observed CPU-reset rising edges,
living in the debug reset domain (so it survives the resets it counts), cleared
by writing bit 1. It **saturates** at `0xFFFF` rather than wrapping: zero is a
meaningful value ("no reset observed since clear") and must not be reachable by
wraparound.

### 15.5 Stage-1 feature value

`OFF_FEATURES` reads `0x0004000F` — bits 0 `dbg_reset_domain`, 1
`axi_ready_gated`, 2 `cfg_wipe`, 3 `cpu_reset_count`, 18 `mon_sense`. Every other
bit reads 0. This value is not written by hand anywhere: it is computed from the
`STAGE` column of `tools/debug/debug_regmap.def`, which is the machine-checked
form of §3.4's "No feature bit may advertise a tied-off counter, stale shadow,
placeholder probe, or operation that can be silently dropped."

### 15.6 Stage-5 frontend breakpoint marker (2026-08-21)

`DecodeStage` is the sole producer of `FrontendDebugMatchService`. The service accepts
four PC values, their enable mask, and the hardware/host skip-once mask from
`DebugCtrlPlugin`; it returns only a registered four-bit skip-consumed pulse mask. The
service configuration is copied into frontend-local registers before comparison.

The comparison consumes the already-registered decode uop PC and terminates at the
existing decode push register. It never drives `valid`, `ready`, packet selection, or
queue admission. The resulting `debugBreakValid` and two-bit `debugBreakSlot` fields
are ordinary plain SpinalHDL fields in `DecodedUop`, `RenamedUop`, and the ROB payload.
All uops of a cracked macro carry the same comparison result; the ROB acts on it only
when the marked entry is `firstOfInstr` at the architectural head. Ordinary frontend,
queue, rename, and ROB recovery therefore discard wrong-path markers without a special
kill path.

Skip-once is armed in the surviving debug domain from the ROB's precise hit report
before `HALTED` becomes visible. It suppresses the first registered frontend match for
that slot after restart and is consumed when the marked first uop is accepted into the
decode push register. A precise breakpoint halt always empties the machine and restarts
at its saved hit PC, so this first post-halt match is the required refetch; the compare
and consume pulse remain outside every backpressure path.
