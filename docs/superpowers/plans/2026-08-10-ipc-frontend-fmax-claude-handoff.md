# IPC, cache, and FMax handoff to Claude

**Frozen:** 2026-08-10

**Core worktree:** `/home/qwertyoruiop/m68k-core-040-ooo-worktrees/agent-01`

**Branch:** `codex/ipc-dcache-vipt`

**Frozen RTL checkpoint:** `9101c5a` (`frontend: localize cycle-exact quiesce`)

This is the continuation point after the IPC/cache pipeline push and its first
cumulative frontend FMax recovery passes.  The design is functionally healthy,
area is healthy, and the remaining work is now principally timing recovery plus
the explicitly listed integration/verification items.  Do not discard the
landed timing cuts merely because a later route exposes a different limiting
cone: each measured cut removed its intended family and the cumulative design
is still smaller than the first five-ID frontend checkpoint.

## 1. Start here

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/agent-01
git status --short --branch
git rev-parse HEAD
```

Expected tracked state is clean at the documentation handoff commit immediately
above `9101c5a`.  The following are local evidence or reservation files and must
not be committed:

- `.agent-reservation`
- `iter_100_CongestedCLBsAndNets.txt`
- `iter_120_CongestedCLBsAndNets.txt`
- `synth/archive/`
- `synth/fullcore_congested_nets.txt`

The last functional gate is green:

```text
make SBT=~/sbt/bin/sbt test-fast
148 passed / 148 total, 156 suites
```

The last focused frontend gate is `FetchDirectedFtbSpec` 16/16.  The real
STOP -> IRQ -> handler -> RTE -> resume lockstep test also passes.  A deliberate
negative mutation changed the local capture from the exact next state to
`RegNext(service.active)`; the new halt-collision test failed because the ROB
state was already active while the frontend-local bit was still false.  The
restored exact-next implementation passed.

## 2. Non-negotiable rules and goals

- Architecture is binding.  Amend the owning design specification before RTL.
- Plugins communicate only through `host[Service]` or documented `Global` keys.
  Every `Global` key has exactly one producer.  Prefer a service for the next
  ROB-to-frontend control cut; do not reach into ROB internals from FetchAlign.
- Keep bundles plain SpinalHDL types.  Fix tests rather than weakening bundles.
- Reserve an isolated workspace for new work.
- Run `make SBT=~/sbt/bin/sbt test-fast` before handoff.  PM-serialize full
  Verilator runs.
- Tests are meant to reveal defects.  Throughput tests must count real fires,
  associations, results, and non-vacuity conditions, not merely valid pulses.
- Timing target remains 250 MHz (4.000 ns), even though 200 MHz is the present
  deployment floor.  Preserve as much headroom as possible.
- Area growth is not an automatic rejection.  Report it and its source before
  considering removal.  Present area is excellent.
- The user explicitly accepts cumulative cuts that are worthwhile in isolation
  even when one cut alone does not close timing.
- Keep TLBs shallow.  They are CAM-heavy; throughput came from tagged/elastic
  interfaces, banking, and latency overlap, not added entries or lookup copies.

## 3. Landed architecture

| Block | Current hot-path contract | Important boundary |
|---|---|---|
| FetchAlign + L1I | Three-cycle resident hit, II=1; depth-three tagged fetch ring turns over on response | Sequential resident hits feed bubble-free after fill |
| I-side prefetch | Five total AXI IDs: ID0 precise demand, IDs1-4 same-page sequential lines | Cross-ID responses may complete out of order; one install pipe preserves cache-array ownership |
| L1I/ITLB | True VIPT: virtual set/offset BRAM read starts in parallel with ITLB lookup | Physical tag/permission qualifies the registered read; no ITLB result on BRAM enable |
| DTLB/LSU/L1D | Tagged registered changed-VPN DTLB hits at II=1; VIPT probe and translation launch together | The returned PA qualifies the original L1D read; the hard test requires `useEarlyProbe` for every hit |
| DTLB geometry | 32 entries, four ways, two banks, one walker | No second CAM, no hit-under-miss, no extra walker |
| Store queue/L1D | Independent send/ack cursors; committed non-precise COPYBACK hits pipeline through S0-S3 | Store miss, WT/inhibited, precise, and maintenance remain ordering barriers |
| Slow ALU | SHIFT/BITFIELD pipeline accepts at II=1 | Flush poisons all stages; IQ forecasts fixed-path capacity |
| Integer multiply | Integrated CPLX multiplier accepts at II=1 | Four DSP48E2s with AREG=2, BREG=2, MREG=1, PREG=1; no LUT multiplier |
| Divider | Iterative and one-at-a-time | This is an intentionally hard/area-expensive pipeline candidate, not a hidden hot-path FSM |

Cold or serialized mechanisms that remain reasonable include DTLB/ITLB page
walks, cache refills/evictions, maintenance, precise/MMIO stores, split-access
replay, iterative division, and exception-frame sequencing.  Do not mechanically
pipeline these without preserving ordering, fault, and flush semantics.

### Key landed commits

- `8e84ae8` strength-reduces MOVEM deltas and removes two decode DSPs.
- `61ce032` pipelines slow ALU operations at II=1.
- `51212f4`, `50b6433`, `3a393f7`, `51f2c62`, `952d651`, `cbc388c`,
  `73dd6be` reconcile, test, integrate, and physically map the CPLX multiplier.
- `9f2aead`, `684c228`, `6e61344`, `3f0dead` implement and prove tagged
  changed-VPN DTLB/VIPT throughput, real split translations, and flush/reuse.
- `436394f`, `93cb436`, `20f4cc6`, `55b5850` implement the elastic COPYBACK
  store-drain pipeline and S3 result/write stage.
- `2ad754c`, `18b5bc1`, `0fea790` turn over the fetch ring, prove resident
  frontend cadence, and make L1I lookup genuinely VIPT-parallel.
- `aa1f348`, `7efb12b`, `d4ab004`, `2c7c544` specify and implement the five-ID
  L1I stream prefetcher.
- `3c4e1f8`, `dc16fc1`, `cc22cd0` are the three cumulative frontend timing cuts
  described in section 5.
- `e421e6b`, `9101c5a` specify and implement the cycle-exact ROB-owned frontend
  quiesce service, removing the remote `coreHalted` family.

## 4. Pinned IPC results

Use the ledger
`.superpowers/sdd/progress-ipc-push-2026-08-09.md` as the detailed source.  Do
not compare aggregates with different kernel sets or old unpinned `/tmp` logs.

| Workload | Before | Current | Result |
|---|---:|---:|---:|
| Independent ALU, ideal memory | about 292 cycles / 1.464 IPC | 214.3 cycles / **1.998 IPC** | Reaches the two-wide floor |
| Independent ALU, `l2:5:70` | 1129 cycles / 0.379 IPC | 276 cycles / **1.552 IPC** | 4.10x fewer cycles than the serialized-I-side state |
| Complete 11-kernel aggregate, ideal | prior three-seed total 22,909 cycles / 0.6741 IPC | 7,619 / 7,648 / 7,649 cycles / **0.6739 IPC** | Three-seed total +7 cycles; neutral |
| Complete 11-kernel aggregate, `l2:5:70` | prior three-seed total 34,378 cycles / 0.4492 IPC | 9,491 / 9,441 / 9,325 cycles / **0.5465 IPC** | Three-seed cycles -17.8%, IPC +21.7% |
| COPYBACK store stream, ideal | 1,814 cycles / 0.269 IPC | 601 cycles / **0.812 IPC** | 3.02x |
| COPYBACK store stream, `l2:5:70` | 1,992 cycles / 0.245 IPC | 779 cycles / **0.626 IPC** | 2.56x |

The old independent-ALU collapse under `l2:5:70` was instruction starvation,
not ALU execution: a one-outstanding 16-byte I-side request repeatedly paid the
70-cycle line-service latency.  Five distinct 64-byte requests now cover that
latency.  The remaining gap from 1.552 to 2.0 is cold/redirect/miss-window and
finite-buffer behavior rather than an ALU issue bottleneck.

## 5. Pinned physical results

All figures use the 4.000 ns constraint.  A negative WNS therefore gives
`FMax = 1000 / (4.000 - WNS)` MHz.

| Checkpoint | Default route | WNS / FMax | TNS / failing endpoints | Routed area | What it proved |
|---|---|---:|---:|---:|---|
| `2c7c544` five-ID base | both pblocks | -3.633 ns / 131.010 MHz | -61,194.376 / 59,598 | 113,298 LUT, 50,508 FF | First functional prefetch checkpoint; top 100 were `ringPlanSeq` |
| `3c4e1f8` token cut | decode only | **-2.720 ns / 148.810 MHz** | -38,486.906 / 51,523 | 114,741 LUT | Removes functional sequence association; result reproduced bit-for-bit from same DCP |
| `dc16fc1` redirect cut | decode only | -3.135 ns / 140.154 MHz | -40,968.956 / 47,952 | 111,555 LUT, 50,366 FF | Removes ROB doFlush/exception family and about 3k LUT; exposes `ringStale` |
| `cc22cd0` stale-plan cut | decode only | **-3.326 ns / 136.500 MHz** | **-43,658.238 / 46,278** | **113,642 LUT, 50,416 FF, 26 BRAM, 4 DSP** | Removes all `ringStale` paths; exposes remote ROB `coreHalted` quiesce family |
| `9101c5a` local quiesce | decode only | **-3.534 ns / 132.732 MHz** | **-45,594.648 / 53,953** | **113,569 LUT, 50,385 FF, 26 BRAM, 4 DSP** | Removes all remote halt paths; exposes defensive FTQ-full through live VIPT/L1I control |

The current synthesis result is WNS **-2.440 ns**, a real 0.219-ns improvement
over `cc22cd0`'s -2.659 ns.  The final route regressed by 0.208 ns because a new
70.36%-routing family became dominant; keep the cut because its intended family
is gone and its mapped depth/area both improved.  Route is clean, with no hold
or pulse-width failures and no congestion windows above level 5.  Current
utilization is only 52.35% of device LUTs, 11.61% of FFs, 5.42% of BRAM tiles,
and 0.22% of DSPs.  Versus `cc22cd0`, routed LUTs fall by 73 and FFs by 31.
Area is not a problem.  The mapper did redistribute covers toward MUXF7/F8, so
do not infer placement quality from total LUT count alone.

### Floorplan result

The four same-DCP `3c4e1f8` routes were:

| Mode | WNS / FMax | TNS | Failing endpoints |
|---|---:|---:|---:|
| none | -3.162 ns / 139.626 MHz | -48,919.734 | 63,028 |
| decode | **-2.720 ns / 148.810 MHz** | **-38,486.906** | **51,523** |
| dcache | -3.648 ns / 130.753 MHz | -52,409.535 | 59,696 |
| both | -2.804 ns / 146.972 MHz | -46,685.883 | 60,324 |

`decode` is now the default and reproduced exactly.  The broad D-cache pblock is
disabled by default because it is locally overfull, captured an unrelated ROB
carry chain through flattened-name matching, and was worse than no floorplan.
Keep all four controls for diagnosis.  Do not widen the X87 decode box: a prior
X103 experiment regressed.  Capture-filter cleanup should be its own exact-DCP
A/B.  FetchAlign/Icache are not actually members of the decode pblock, so do not
attribute an unplaced frontend cone improvement to that pblock without route
coordinates.

On `9101c5a`, the decode region uses 32,805 / 43,680 LUT sites (75.10%), down
from 34,173 / 43,680 (78.23%) at `cc22cd0`.  The same known split-carry warning
remains at the FetchAlign/Decode flattened-name boundary.  Preserve the current
geometry for baseline comparability; make capture-filter cleanup a separate
same-DCP A/B rather than mixing it into an RTL cut.

### Archived evidence

- `synth/archive/dc16fc1_internal_redirect_decode/`
- `synth/archive/cc22cd0_stale_plan_decode/`
- `synth/archive/9101c5a_frontend_quiesce_decode/`

The current archive contains the exact generated Verilog, synthesized and
routed DCPs, MD5, Vivado log, timing/utilization/slack/congestion/fanout/path and
pblock reports.  Current generated-netlist MD5:
`909dfa3941f47171a1fa77b5a8b8712b`.

## 6. Completed FMax cut: cycle-exact local quiesce

The previous routed top-100 all began at `RobPlugin_logic_coreHalted_reg/C`.
`9101c5a` removes that entire family.  `RobPlugin` now solely produces
`FrontendQuiesceService { active, next }`; FetchAlign captures `next` locally
and asserts that the local register equals architectural `active` after every
edge.  No `Global` key or sibling-plugin internal reach was added.

The exact equations are:

```text
stopEnter = sysTriggerSig && p0.sysKind == STOP
stoppedNext = (stopped || stopEnter) && !interruptPending
fatalNext = coreHalted || coreHaltedIn
frontendQuiesceNext = stoppedNext || fatalNext
```

A plain `RegNext(stopped || coreHalted)` is incorrect.  It would leave
`ic.cmd.fire` and/or `feed.fire` possible for one cycle after the ROB-visible
halt state asserts.  The negative mutation proves this is not a stylistic
difference.  Directed coverage also proves a transition-edge command remains a
legal pre-halt transaction, its C+1 plan is blocked in the first full halted
cycle, both accepted responses drain without feed, interrupt wake is exact, and
fatal halt remains sticky.

## 7. Exact next FMax cut from the `9101c5a` recensus

Every routed top-100 path now starts at
`FetchAlignPlugin_logic_ftqCount_reg[0]/C` and ends at an I-cache `lineReg`
data/enable.  The worst is 7.430 ns / 23 levels, with 2.202 ns logic and 5.228 ns
route (70.36%).  It traverses `ftqFull -> ftbBlocked -> applyNow`, the selected
fetch PC, live ITLB CAM/permission, L1I tag/way qualification, and speculative
installer control.  There are no `coreHalted` paths in the top 100.

The narrow first candidate is the defensive FTQ-full veto.  The binding token
amendment already says legal run-ahead cannot fill the 32-entry FTQ, and RTL
already asserts `!ftqFull` every simulation cycle.  Yet `ftqFull` remains a
functional input to `ftbBlocked` and is now the measured critical source.  Do
this spec-first:

1. Amend the binding FTB token spec so defensive-full is an asserted invariant,
   not a functional application veto, for the fixed 32-entry depth.
2. Prove the legal maximum run-ahead bound in a directed stress test with decode
   blocked, maximum fetch-ring/IBuf occupancy, prediction turnover, redirects,
   and target holds; record peak FTQ occupancy and require it below 32.
3. Remove only `ftqFull` from `ftbBlocked`; retain the hard `assert(!ftqFull)`.
4. Mutation-reduce FTQ depth or disable the bound and require the stress test or
   assertion to fail, so the proof cannot be vacuous.
5. Regenerate and route before deciding the following cut.  If `ftqCount`
   remains through another function, recensus rather than broadening by guess.

Do not simply remove FTQ capacity protection at arbitrary parameter values.  If
the FTQ becomes configurable below the proven envelope, either reject that
configuration elaboration-time or restore a separately pipelined capacity
mechanism.

## 8. Why the four current frontend cuts stay

- `3c4e1f8` replaced live dynamic sequence equality with a local fixed-C+1
  association and assertion-only provider tokens.  It improved the first
  measured route materially and removed the `ringPlanSeq` family.
- `dc16fc1` lets registered internal redirect application occur physically and
  relies on same-edge flush/stale priority to kill it.  It removed the measured
  doFlush/exception family and reduced mapped LUTs by about 3,000.
- `cc22cd0` suppresses FTB/gshare lookup for cache commands already known to be
  born stale.  It removed the entire `ringStale` top-100 family.  Its isolated
  WNS is worse only because the next remote halt cone is now exposed.
- `9101c5a` localizes exact halt next-state at FetchAlign.  It improves
  post-synth WNS by 0.219 ns, removes every remote-halt top-100 path, and slightly
  reduces LUT/FF area.  Its route regression is the newly exposed defensive
  FTQ-full/VIPT family, not failure of the cut.

Reverting any of these restores a previously measured limiter.  Continue
cutting the newly exposed registered control cones instead.

## 9. Synthesis recipe

Generate the exact full-core RTL, then run the default decode floorplan:

```bash
~/sbt/bin/sbt 'runMain m68k040.top.GenFullCoreSynthVerilog'
vivado -mode batch -nojournal \
  -log synth/<descriptive-name>.log \
  -source synth/impl_FullCore.tcl
```

`synth/impl_FullCore.tcl` enforces the 4.000 ns target and accepts
`FLOORPLAN_MODE={none,decode,dcache,both}`.  Default is `decode`.  Before a new
run, pin the exact HEAD and generated-Verilog MD5 in the ledger.  Archive both
DCPs, generated Verilog, log, and reports before regenerating.  Report WNS,
TNS, failing endpoints, hold/PW status, LUT/FF/BRAM/DSP, top-path family, logic
levels, route percentage, and floorplan occupancy.  A synthesis-only WNS is an
early signal, not a substitute for the route gate.

## 10. Separate work not on this branch

### Debug controller design

No debug/JTAG RTL exists in the Spinal core yet.  A design-only addendum is
committed separately:

```text
worktree: /home/qwertyoruiop/m68k-core-040-ooo-worktrees/agent-02
branch:   codex/debug-ctrl-jtag-design
commit:   843249d docs: specify JTAG-compatible debug controller
```

It preserves the deployed sibling `macqd700-soc` contract: debug base
`0x5090_0000`, AXI-Lite local map, append-only discovery at `0x0A0`, exact
macro-level halt/step, halted-only architectural/cache operations, explicit
CDC/reset ownership, and shared cache-maintenance arbitration.  Review/cherry-
pick the spec before any RTL.  Do not invent a second maintenance producer or
copy `BackendWiringPlugin`'s internal-reaching pattern.

### SoC five-ID fabric support

The platform-side crossbar/L2 support is ready in another repository:

```text
worktree: /home/qwertyoruiop/macqd700-soc-worktrees/soc-if-outstanding
branch:   agent/soc-if-outstanding
commits:  bf6c859 afcc0cb 4a9d608 4079649
```

It accepts five distinct live CPU-IF IDs by default, blocks a duplicate or sixth,
routes all multibeat responses by RID, permits cross-ID completion reorder, and
retains legacy behavior for other masters/targets.  Gates: AXI xbar 422/0, L2C
31/0, L2C chain 4/0 with eight ARs before first R, VRAM chain 14/0, all eight
lint configurations green.

The remaining SoC integration blocker is the current CPU wrapper/`if_to_axi`:
it is still one-outstanding, fixed-ID, and emits 16-byte ARLEN=0 requests.  The
core and fabric service contracts are ready, but the wrapper/submodule bump must
carry five distinct IDs and 64-byte ARLEN=3 lines before hardware exercises the
new concurrency.

## 11. Remaining correctness/verification debt

- Debug RTL/host integration is unimplemented; only the design addendum exists.
- SoC CPU wrapper integration is required for real five-ID instruction traffic.
- Iterative DIV is still single-outstanding.  Replication/unrolling is an area
  trade, not a cheap pipeline-register insertion.
- There is no current FPU RTL.  The updated FPU specs require fixed-latency
  DSP-backed FADD/FMUL paths to be elastic/II=1 and allow FDIV/FSQRT to remain
  iterative; follow the spec before implementation.
- Continue exact flush/ROB-ID reuse, fault, permission, split-page, maintenance,
  miss-barrier, and output-collision tests whenever a queue becomes multi-flight.
- Do not deepen either TLB as a throughput shortcut.  Measure CAM cost and prove
  need first.
- The present FMax is far below both 200 and 250 MHz.  This is expected after the
  large IPC changes, but it is now the principal core task.

## 12. Handoff completion state

This handoff phase has:

1. a spec-first, service-clean, cycle-exact local frontend-quiesce cut;
2. directed mutation-proven halt/wake/drain tests;
3. the mandatory 148-test gate green;
4. exact generated-netlist MD5 and archived synthesis/route evidence;
5. a top-path recensus showing the `coreHalted` family gone;
6. honest WNS/TNS/failing-endpoint and area deltas; and
7. a measurement-selected next cut at defensive FTQ-full, not a rollback.

Codex stopped here at the user's request.  Claude should begin with the
spec/test proof in section 7 and must not treat the 132.7-MHz route as grounds to
discard the cumulative IPC or timing work.
