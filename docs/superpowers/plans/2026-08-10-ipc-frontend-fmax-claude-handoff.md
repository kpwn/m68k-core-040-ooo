# IPC, cache, and FMax handoff to Claude

**Frozen:** 2026-08-10

**Core worktree:** `/home/qwertyoruiop/m68k-core-040-ooo-worktrees/agent-01`

**Branch:** `codex/ipc-dcache-vipt`

**Frozen RTL checkpoint:** `6b246de` (`frontend: retime the FTB framing verdict off the fetch-PC cone`)

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
above `6b246de` (section 14).  The following are local evidence or reservation files and must
not be committed:

- `.agent-reservation`
- `iter_100_CongestedCLBsAndNets.txt`
- `iter_120_CongestedCLBsAndNets.txt`
- `synth/archive/`
- `synth/fullcore_congested_nets.txt`

The last functional gate is green:

```text
make SBT=~/sbt/bin/sbt test-fast
149 passed / 149 total, 157 suites
```

The last focused frontend gate is `FetchDirectedFtbSpec` 17/17, with `FtbSpec`
3/3, `GshareSpec` 5/5 and `FtqCapacitySpec` 2/2.  The real
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
- `36b4866`, `1a7a0f3`, `28ec738` terminate FTQ capacity at the elaboration bound
  plus an assertion and remove the defensive full veto (section 13).
- `5f6de59`, `6b246de` register the FTB framing verdict at command time and take
  it off the application/fetch-PC cone (section 14).

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

> **Read this table as `IMPL_STRATEGY=default`.**  Section 18 changed
> `synth/impl_FullCore.tcl`'s default implementation recipe to `postrouteN`
> (iterated post-route `phys_opt_design` + `route_design -tns_cleanup`), which is
> worth +18.95 MHz on the *same* netlist.  The current physical baseline is
> therefore **`6b246de` + `FLOORPLAN_MODE=decode` + `IMPL_STRATEGY=postrouteN` =
> -1.472 ns / 182.749 MHz at 3 rounds (-1.463 / 183.050 at the 6-round plateau)**,
> not the -2.094 / 164.096 row below.  Every row here still regenerates exactly
> under `IMPL_STRATEGY=default`; use that to compare against any of them.

| Checkpoint | Default route | WNS / FMax | TNS / failing endpoints | Routed area | What it proved |
|---|---|---:|---:|---:|---|
| `2c7c544` five-ID base | both pblocks | -3.633 ns / 131.010 MHz | -61,194.376 / 59,598 | 113,298 LUT, 50,508 FF | First functional prefetch checkpoint; top 100 were `ringPlanSeq` |
| `3c4e1f8` token cut | decode only | **-2.720 ns / 148.810 MHz** | -38,486.906 / 51,523 | 114,741 LUT | Removes functional sequence association; result reproduced bit-for-bit from same DCP |
| `dc16fc1` redirect cut | decode only | -3.135 ns / 140.154 MHz | -40,968.956 / 47,952 | 111,555 LUT, 50,366 FF | Removes ROB doFlush/exception family and about 3k LUT; exposes `ringStale` |
| `cc22cd0` stale-plan cut | decode only | **-3.326 ns / 136.500 MHz** | **-43,658.238 / 46,278** | **113,642 LUT, 50,416 FF, 26 BRAM, 4 DSP** | Removes all `ringStale` paths; exposes remote ROB `coreHalted` quiesce family |
| `9101c5a` local quiesce | decode only | -3.534 ns / 132.732 MHz | -45,594.648 / 53,953 | 113,569 LUT, 50,385 FF, 26 BRAM, 4 DSP | Removes all remote halt paths; exposes defensive FTQ-full through live VIPT/L1I control |
| `28ec738` FTQ-full cut | decode only | **-2.587 ns / 151.814 MHz** | **-30,937.422 / 45,597** | **113,487 LUT, 50,328 FF, 26 BRAM, 4 DSP** | Removes the defensive capacity veto; `ftqCount` drops to 0 paths; exposes the `ringDrop`-startpoint cone. Improved synth *and* route together |
| `6b246de` FTB framing retime | decode only | **-2.094 ns / 164.096 MHz** | **-21,068.689 / 32,729** | **110,662 LUT, 50,389 FF, 26 BRAM, 4 DSP** | Registers the hit/in-window/after-drop conjunction in the provider; `ringDrop` drops to 0 paths; exposes `stalled -> ftbBlocked -> applyNow`. Improved every timing metric, total LUTs and pblock occupancy at once |

The head of the table is now `6b246de` at WNS **-2.094 ns / 164.096 MHz**.
`28ec738` and `6b246de` are the two cuts that improved post-synthesis WNS,
post-route WNS, TNS, failing endpoints, and area all at once; `28ec738` remains
the campaign's largest single gain (+19.082 MHz, +14.4%), with `6b246de` second
(+12.282 MHz, +8.1%).  Together they take the branch from 132.732 to
164.096 MHz, +23.6%.  Both routes are clean, with no hold or pulse-width
failures and no congestion windows above level 5.  Current utilization is 51.01%
of device LUTs, 11.61% of FFs, 5.42% of BRAM tiles, and 0.22% of DSPs.  Area is
not a problem.  The mapper redistributes covers toward MUXF7/F8, so do not infer
placement quality from total LUT count alone.

For history: at `9101c5a` the synthesis result was WNS -2.440 ns, a real
0.219-ns improvement over `cc22cd0`'s -2.659 ns, but its final route regressed
by 0.208 ns because a new 70.36%-routing family became dominant.  That cut was
kept because its intended family was gone and its mapped depth/area both
improved — a judgement the `28ec738` result retroactively vindicates, since
removing the newly-exposed family recovered far more than the interim
regression cost.

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
Keep all four controls for diagnosis.

**Re-measured on the `6b246de` netlist (section 15 step 7): `decode` still wins
(-2.094) over `none` (-2.187), `dcache` (-2.665) and `both` (-2.669), but the
decode advantage over `none` has shrunk from 0.442 ns to 0.093 ns.**
**Section 18 re-measured it again under the new `postrouteN` recipe and the
shrinkage REVERSED: `decode` -1.623 vs `none` -2.010, i.e. the pblock's edge grew
back to 0.387 ns / 11.45 MHz.  Section 18 also adds four more losing floorplan
variants (a repaired LS box, a fetch/predict box, that box alone, and a
FetchAlign-annexing decode box) — seven negative floorplan results in total.
Do not attempt another pblock geometry without reading section 18 step 2 first.**  Use the
newer table; this `3c4e1f8` one is kept for history.  Do not widen the X87 decode box: a prior
X103 experiment regressed.  Capture-filter cleanup should be its own exact-DCP
A/B.  FetchAlign/Icache are not actually members of the decode pblock, so do not
attribute an unplaced frontend cone improvement to that pblock without route
coordinates.

On `6b246de`, the decode region uses 29,662 / 43,680 LUT sites (67.91%), down
from 30,288 (69.34%) at `28ec738`, 32,805 (75.10%) at `9101c5a` and 34,173
(78.23%) at `cc22cd0` — a steady four-cut decline.  The same known split-carry warning
remains at the FetchAlign/Decode flattened-name boundary.  Preserve the current
geometry for baseline comparability; make capture-filter cleanup a separate
same-DCP A/B rather than mixing it into an RTL cut.

### Archived evidence

- `synth/archive/dc16fc1_internal_redirect_decode/`
- `synth/archive/cc22cd0_stale_plan_decode/`
- `synth/archive/9101c5a_frontend_quiesce_decode/`
- `synth/archive/28ec738_ftq_capacity_cut_decode/`
- `synth/archive/6b246de_ftb_framing_retime_decode/`

The current archive contains the exact generated Verilog, synthesized and
routed DCPs, MD5, Vivado log, timing/utilization/slack/congestion/fanout/path and
pblock reports.  Current generated-netlist MD5:
`d80f6218c5c7dcab94a33a52d64244fa`.

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

## 7. FMax cut from the `9101c5a` recensus — DONE at `28ec738`

> **Status: completed 2026-08-10.**  All five steps below were executed; see
> section 13 for the full record.  The measured result was **132.732 ->
> 151.814 MHz** with `ftqCount` falling to zero routed paths.  Step 5's
> instruction to recensus rather than guess was followed: the new target family
> is `FetchAlignPlugin_logic_ringDrop_1` fanning into I-cache prefetch state.
> Kept verbatim below as the worked template for the next cut.

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
`FLOORPLAN_MODE` as a `+`-separated set of pblock tokens
(`none`, `decode`, `decode_fe`, `dcache`, `backend`, `frontend`; `both` is an
alias for `decode+dcache`), default `decode`; and `IMPL_STRATEGY`
(`default`, `fanout`, `postroute`, `postroute2`, `postrouteN`, `exploreN`,
`explore`), default `postrouteN` with `POSTROUTE_ROUNDS=3` since section 18.
`IMPL_STRATEGY=default` reproduces the pre-section-18 flow verbatim.
For a parallel same-DCP A/B use `synth/floorplan_ab.sh <runroot> <spec>...` and
report it with `synth/floorplan_ab_report.sh`.  Before a new
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
- The present FMax (164.096 MHz at `6b246de`) is still below both 200 and
  250 MHz.  This is expected after the large IPC changes, but it is now the
  principal core task.  **Section 15 supersedes section 14's "next two cuts"
  ordering**: the whole frontend demand cone is measured at 0.271 ns and the
  top four families together at 0.294 ns, so the frontend ladder is finished
  and the remaining work is the D-cache/IssueQueue plateau at -1.800 ns.

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

**Claude executed section 7 in full on 2026-08-10; see section 13.**  The
132.7-MHz route was indeed not grounds for a rollback: the very next cut took it
to 151.8 MHz.  The frontier is now the `ringDrop`-to-prefetch fanout family, and
the same five-step spec-first discipline should be applied to it.

## 13. Completed FMax cut: FTQ-full capacity veto (Claude, 2026-08-10)

Executed section 7's five-step plan.  Step 1 (spec amendment) was already landed
as `36b4866`; steps 2-5 are below.

**Commits**

- `1a7a0f3` `frontend: measure maximum legal FTQ run-ahead` — the directed
  capacity proof plus a simulation-only `SimPublic` on `ibuf.io.cnt`/`avail`.
- `28ec738` `frontend: cut the FTQ-full fetch veto` — removes `ftqFull` from
  `ftbBlocked`, retains the hard `assert(!ftqFull)`.

### Step 2: the measured run-ahead bound

`src/test/scala/m68k040/frontend/FtqCapacitySpec.scala` builds the densest legal
FTQ producer the frontend can express: a chain of eight-byte windows whose first
word is a learned one-word unconditional branch to the next window.  Every
accepted cache command therefore yields exactly one applied prediction, exactly
one FTQ push, and exactly one genuine IBuf word — and the RTL already asserts
`resultEnd > ringDrop(resultSlot)` on application, so no legal stimulus can push
an FTQ entry without also consuming a ring slot or at least one IBuf word.  That
is the structural reason the bound is `RING + BUF_WORDS`.

The run holds decode for the entire run-ahead phase, withdraws the cache command
port on a 4-of-7 duty cycle to force applications into the one-entry target hold,
drives the ring to full occupancy, redirects at maximum occupancy, rebuilds
run-ahead, and finally releases decode so confirmations pop real entries.

Every push, pop, and flush is counted as a real event and an independent mirror
of occupancy is rebuilt from those counts and compared with the hardware
`ftqCount` register on every cycle; any divergence fails immediately.  Measured
census over 456 cycles:

| Quantity | Measured | Bound |
|---|---:|---:|
| Peak FTQ occupancy | **17** | 32 configured |
| `ftqFull` pulses | **0** | must be 0 |
| Peak fetch-ring occupancy | 3 | `RING` = 3 |
| Peak IBuf occupancy | 17 words | `BUF_WORDS` = 20 |
| Real FTQ pushes | 57 | — |
| Real FTQ pops (decode confirmations) | 40 | — |
| Flushes / flushes discarding live entries | 2 / 1 | — |
| Target-hold cycles | 128 | — |

Peak run-ahead is limited by the existing IBuf landing reservation
`cnt + (ringCount+1)*4 <= BUF_WORDS`, not by the FTQ: once 17 one-word windows
are resident, no further command may issue.  The elaboration bound
`ftqDepth >= RING + BUF_WORDS + 1` = 24 is the conservative structural argument;
17 is the reachable maximum under this reservation.

A companion elaboration test requires depth 32 to build and depth 16 to fail the
constructor bound with its exact message.

### Step 3: the cut

`ftqFull` is removed from `ftbBlocked`, which was its sole functional consumer.
`applyNow`, the fetch-PC mux, I-cache readiness, and prefetch/install state never
referenced it, matching the amendment's explicit prohibition list.  The hard
`assert(!ftqFull)` stays; SpinalHDL emits it inside `` `ifndef SYNTHESIS ``, and
the regenerated netlist confirms `FetchAlignPlugin_logic_ftqFull` is now read
only inside that block.

The generated Verilog differs from `9101c5a`'s archived netlist in exactly one
functional line — the `ftbBlocked` assign — plus the git-hash comment and
line-number-derived signal renaming.  This is a single-variable A/B.

### Step 4: mutation proof

Temporarily disabling the constructor bound (`require(true || ...)`) and
elaborating the same stress DUT at `ftqDepth = 16` makes the step-2 test trip the
retained tripwire: `FAILURE FTQ reached its defensive full state despite the
run-ahead bound` at t=1070, `spinal.sim.SimFailure: HDL assertion failure`.  Both
mutations were reverted; the working tree at `28ec738` contains neither.

This proves three things at once: the assertion is a live net rather than dead
text, the depth-32 choice is load-bearing because the next lower power of two
genuinely overflows, and the stress test is non-vacuous because it drives
occupancy past 16.

Independently, the step-2 census is **bit-identical before and after the cut** —
456 cycles, peak 17, 57 pushes, 40 pops, 2 flushes, 128 target-hold cycles, 0
full pulses.  The veto was never functionally engaged, only physically expensive.

### Functional gates

- `make SBT=~/sbt/bin/sbt test-fast`: **149 passed / 149 total, 157 suites**.
  The count moves from the documented 148 because the new elaboration-bound test
  is a pure-elaboration test and therefore untagged; the stress test itself is
  `VerilatorTest` and stays outside the fast gate.
- `FetchDirectedFtbSpec` 16/16 and `FtqCapacitySpec` 2/2.

### Step 5: physical result

**This is the largest single-cut FMax gain of the campaign, and the first cut
that improved both synthesis and route.**

| Metric | `9101c5a` baseline | `28ec738` FTQ cut | Delta |
|---|---:|---:|---:|
| Post-synth WNS | -2.440 ns | **-2.316 ns** | **+0.124 ns** |
| Post-route WNS | -3.534 ns | **-2.587 ns** | **+0.947 ns** |
| FMax | 132.732 MHz | **151.814 MHz** | **+19.082 MHz (+14.4%)** |
| TNS | -45,594.648 | **-30,937.422** | **+14,657.226 (-32.1%)** |
| Failing endpoints | 53,953 | **45,597** | **-8,356** |
| Routed LUT | 113,569 | 113,487 | -82 |
| Routed FF | 50,385 | 50,328 | -57 |
| BRAM / DSP | 26 / 4 | 26 / 4 | unchanged |
| Decode pblock | 32,805 / 43,680 (75.10%) | **30,288 / 43,680 (69.34%)** | -2,517 sites |

Route is clean: WHS +0.031 ns, THS 0.000, WPWS +1.458 ns, zero hold and zero
pulse-width failures, and no congestion windows above level 5.  Device use is
52.31% LUT, 11.60% FF, 5.42% BRAM, 0.22% DSP.  `FLOORPLAN_MODE decode`,
`SOURCE_MD5 = NETLIST_MD5 = 065275a9ef34e5abb30d4361c249bc40`, Vivado exit 0.

Unlike the previous three cuts, this one did **not** trade route for synthesis.
`dc16fc1`, `cc22cd0`, and `9101c5a` each removed their target family but lost
post-route WNS to a newly dominant family; this cut improved every timing metric
simultaneously and shrank both total and in-pblock area.

**Recensus — the target family is gone.**  `ftqCount` appears **0 times** in the
routed slack matrix and **0 times** in the whole post-route timing report, versus
100/100 of the top 100 startpoints at `9101c5a`.  The eliminated startpoint is
100% displaced; the new top 100 are unanimously:

```
100  FetchAlignPlugin_logic_ringDrop_1_reg[0]/C   (startpoint, all 100)
```

fanning into I-cache prefetch and line state:

| Endpoint family | Paths in top 100 |
|---|---:|
| `IcachePlugin_logic_lineReg` | 33 |
| `IcachePlugin_logic_pfNextPa` | 29 |
| `IcachePlugin_logic_pfTag_0` | 9 |
| `IcachePlugin_logic_arHoldAddr` | 9 |
| `IcachePlugin_logic_pfSet_0` | 3 |
| `IcachePlugin_logic_s1PredEntries_{0,1,2}` | 6 |
| other `IcachePlugin` prefetch state | 11 |

Worst path is `ringDrop_1_reg[0]/C -> IcachePlugin_logic_pfComplete_0_reg/D` at
-2.587 ns.  The critical path has therefore left the FTQ occupancy counter
entirely and now sits on the **ring-drop-to-prefetch** arc: a single `ringDrop`
bit broadcasting into the live VIPT/L1I prefetch address, tag, set, and line
registers.  That is the next cut's target family, and it is a genuinely
different structure from the four already removed — a fanout/broadcast problem
rather than a functional-association or capacity-veto problem.  Note the
startpoint is one bit driving all 100 paths, so the promising direction is
reducing what `ringDrop` gates or registering it closer to the I-cache, not
another association cut.

Archived at `synth/archive/28ec738_ftq_capacity_cut_decode/`.

**Superseded by section 14**: the "single `ringDrop` bit broadcasting" reading in
the paragraph above is wrong, and section 14 corrects it from the routed
checkpoint.  `ringDrop_1_reg[0]` has `fo=2`.  It was the *latest-arriving input*
to a long shared cone, not a broadcast source.  The fix shape suggested there —
reduce fanout or add a pipeline register toward the I-cache — was therefore also
wrong; the correct fix was a retiming across a register that already existed.

## 14. Completed FMax cut: FTB framing-verdict retime (Claude, 2026-08-10)

Executed section 13's recensus target.  **Result: 151.814 -> 164.096 MHz
(+12.282 MHz, +8.1%)**, with the `ringDrop` family eliminated to zero routed
paths and every timing metric improved again.

**Commits**

- `5f6de59` `docs(frontend): register the FTB framing verdict at command time` —
  binding amendment §2.1.1, corrected §4 predicate, new §9 item 8a.
- `6b246de` `frontend: retime the FTB framing verdict off the fetch-PC cone` —
  the RTL, both mutation-proved tests.

### Step 1: grounding corrected section 13's diagnosis

Section 13 concluded from the top-100 startpoint census that this was a
fanout/broadcast problem.  Querying the routed checkpoint directly disproves
that.  `report_design_analysis` gives the worst path a `High Fanout` column
value of 170, but the fanout-170 and fanout-166 nets are *five and six levels
downstream* of the startpoint; the startpoint net itself is:

```text
net (fo=2, routed)  0.153  0.262  ...ringDrop_1_reg[0] -> LUT5
```

`ringDrop` is a two-bit-per-slot register array with exactly three functional
readers: `resultAfterDrop`, `rspDropHead`, and two simulation assertions.  All
100 paths shared that startpoint because it was the **latest-arriving input to a
single long shared cone**, not because it drove 100 loads.  Neither of the two
directions section 13 proposed would have helped: there was no fanout to reduce,
and a pipeline register toward the I-cache would have inserted a real cycle into
the II=1 fetch cadence to fix a segment that was only 1.9 ns of the 6.6 ns path.

Decomposing that path by arrival time gives the real structure:

| Segment | Levels | Arrival | Delay | What it is |
|---|---:|---|---:|---|
| A framing + application | 6 | 0.000 -> 1.938 | 1.938 ns | `ringDrop -> resultAfterDrop -> applyNow -> fetch-PC mux` |
| B ITLB CAM | 7 | 1.938 -> 3.997 | 2.059 ns | CAM compare + two CARRY8 chains -> `tlb_io_hitEntry_ppn` |
| C address + L1I tag | 3 | 3.997 -> 4.937 | 0.940 ns | `lookupPaddr` -> L1I `hitVec` tag/valid qualification |
| D fire -> prefetch seed | 7 | 4.937 -> 6.596 | 1.659 ns | `isHit -> answerable -> cmdPort.fire -> seedPfWindow` |

Segment A is the only part that is predictor *framing arithmetic over values
that were already final one cycle earlier*.  B and C are the irreducible
parallel-VIPT core.  D is a speculative prefetch hint with no same-cycle
architectural requirement.  So the cut is A, and the next cut is D.

### Step 2: the amendment

`FtbLookupCmd` gains the window's leading-word `drop`; `FtbLookupRsp` gains a
registered `framedOk = hit && brLen != 0 && brWordOff + brLen <= 4 &&
brWordOff >= drop`, computed in the provider at command time from the same
asynchronous entry read that already produced the tag hit.

The equivalence is **structural, not incidental**.  `ringTail` has exactly one
writer, `ringInc(ringTail)` under `ic.cmd.fire`, and no redirect resets it.
`ringDrop` has exactly one writer, `ringDrop(ringTail) := cmdDrop`, under the
same condition.  `resultExpectedSlot` latches that same `ringTail` at that same
edge, and `ringInc(x) != x` for `RING=3`.  Therefore `ringDrop(resultSlot)` read
at C+1 is bit-identical to the `cmdDrop` the command carried at C, on every
cycle the value is consumed.  Zero cycles are added; no application rule
changes.

The amendment also prohibits `ringDrop` from the application, fetch-PC,
lookup-enable and I-cache readiness/prefetch cones for the future.  Its only
remaining functional consumer is response-time `rspDropHead`.

### Step 3: the cut

`applyNow` becomes `resultExpectedValid && resultProvidersValid &&
resultSlotLegal && framedOk && resultDirection && !ftbBlocked`.  `framedOk`
already carries the tag hit, so this is exactly the previous conjunction with
the hit/in-window/after-drop terms moved one cycle earlier.

The live `hit && resultInWindow && resultAfterDrop` form is retained as decline
telemetry and as an oracle asserted on every live-result cycle.  Those nets have
no synthesizable sink and are dead-code-eliminated; the retained assertion sits
inside `` `ifndef SYNTHESIS ``.

The generated Verilog differs from `28ec738`'s archived netlist in exactly the
new command/response fields, the provider's `qFramed` assign, the rewritten
`applyNow` assign, the new assertion, and line-number-derived renaming.  A true
single-variable A/B.

### Step 4: mutation proofs

Both required by amendment §9 item 8a, and both trip:

1. **Provider ignores the carried drop** (`brWordOff >= 0`).  The new `FtbSpec`
   matrix fails at window `0x4000 off=0 drop=1`: `framedOk=true, expected
   false`.  Note the existing integrated suite does *not* catch this mutation,
   which is why the provider-level matrix is mandatory rather than optional.
2. **Lookup carries `pendingDrop` instead of the paired `cmdDrop`.**  The new
   integrated `FetchDirectedFtbSpec` case fails; with its test-side check
   removed, the RTL itself fires `FAILURE registered FTB framing verdict
   diverged from its live ring-drop oracle`, followed by the pre-existing
   `applied FTB plan violates its ring slot/drop framing contract`.  This proves
   the oracle is a live net and that the `cmdDrop` pairing is load-bearing.

The integrated case is new coverage in its own right: `W` branches to an
unaligned target so its target window is fetched with drop=3, and that target
window has its own learned branch at word offset 0 — inside the dropped words.
It must be declined, and the command stream must stay `W / T2 / T2+8` with one
apply, one push, one FTQ entry.  Nothing previously exercised a drop-shadowed
prediction end to end.

### Functional gates

- `make SBT=~/sbt/bin/sbt test-fast`: **149 passed / 149 total, 157 suites** —
  unchanged, because both new tests are `VerilatorTest` and sit outside the fast
  gate.
- `FetchDirectedFtbSpec` 17/17, `FtbSpec` 3/3, `GshareSpec` 5/5,
  `FtqCapacitySpec` 2/2.

No IPC re-measurement was run.  This is deliberate: the cut is a proven pure
retiming with a live in-RTL equality oracle running in every fetch-directed
simulation, and the 17 directed frontend tests count applications, pushes,
confirmations and exact command streams — a stronger cycle-behaviour proof than
an aggregate IPC number would be.

### Step 5: physical result

| Metric | `28ec738` baseline | `6b246de` framing retime | Delta |
|---|---:|---:|---:|
| Post-synth WNS | -2.316 ns | **-1.827 ns** | **+0.489 ns** |
| Post-synth TNS / endpoints | — | -9,071.870 / 12,559 | — |
| Post-route WNS | -2.587 ns | **-2.094 ns** | **+0.493 ns** |
| FMax | 151.814 MHz | **164.096 MHz** | **+12.282 MHz (+8.1%)** |
| TNS | -30,937.422 | **-21,068.689** | **+9,868.733 (-31.9%)** |
| Failing endpoints | 45,597 | **32,729** | **-12,868 (-28.2%)** |
| Routed LUT | 113,487 | **110,662** | **-2,825** |
| LUT as logic / memory | — | 101,469 / 9,193 | — |
| Routed FF | 50,328 | 50,389 | +61 |
| BRAM / DSP | 26 / 4 | 26 / 4 | unchanged |
| Decode pblock | 30,288 / 43,680 (69.34%) | **29,662 / 43,680 (67.91%)** | -626 sites |
| Worst path delay / levels | 6.568 ns / 24 | **5.990 ns / 20** | -0.578 ns / -4 levels |

Route is clean: WHS +0.020 ns, THS 0.000, WPWS +1.458 ns, zero hold and zero
pulse-width failures, and no congestion windows above level 5 at either placer
or router stage.  Device use is 51.01% LUT, 11.61% FF, 5.42% BRAM, 0.22% DSP.
`FLOORPLAN_MODE decode`, `SOURCE_MD5 = NETLIST_MD5 =
d80f6218c5c7dcab94a33a52d64244fa`, Vivado exit 0.

Like `28ec738`, and unlike the three cuts before it, this one improved
post-synthesis WNS, post-route WNS, TNS, failing endpoints, total LUTs and
in-pblock occupancy simultaneously.  The +61 FF is the provider's new `drop`
command register and `framedOk` result bit; the -2,825 LUT is the deleted live
framing/mux cone plus its dead-code-eliminated oracle nets.

Two cuts have now taken the branch from 132.732 MHz to 164.096 MHz (+23.6%).

**Recensus — the target family is gone.**  `ringDrop` appears **0 times** in the
routed slack matrix and **0 times** in the entire post-route timing report,
versus 100/100 of the top-100 startpoints at `28ec738`.  `ftqCount` remains at
0.  The new top 100 are again unanimous:

```
100  FetchAlignPlugin_logic_stalled_reg/C   (startpoint, all 100)
```

ending in I-cache S1 predecode and line state:

| Endpoint family | Paths in top 100 |
|---|---:|
| `IcachePlugin_logic_s1PredEntries_{0,1,2,3}` | 79 |
| `IcachePlugin_logic_lineReg` | 21 |

Worst path is `stalled_reg/C -> IcachePlugin_logic_s1PredEntries_3_reg[18]/CE`
at -2.094 ns, 5.990 ns over 20 levels, 66% route.  Segment A collapsed exactly
as intended: `applyNow` is now reached at **0.836 ns and four levels** from the
new startpoint, against 1.938 ns and six levels from `ringDrop` before.

### The next cut, and why it is *not* another PC-mux cut

`stalled` reaches `applyNow` through `ftbBlocked`:

```text
ftbBlocked = redirect.valid || (resume.valid && stalled) || quiesce
          || stalled || faultHold || ftbSuppress || targetHoldValid
```

This is the same **association/veto** shape as the four cuts of section 8, and
the same spec-first five-step discipline applies.  The specific question to
answer from the architecture, not from the netlist, is: `ic.cmd.valid` already
carries its own independent `!stalled`, so no command can fire while stalled.
For the fetch-PC-mux consequence of `applyNow`, the `stalled` term in
`ftbBlocked` is therefore redundant.  It is **not** obviously redundant for
`applyNow`'s other three effects — the `ringKeep` truncation, the FTQ push, and
the `targetHold` capture — and §4's "one `applyNow` event performs exactly once"
rule is what must be re-proved before removing it.  Do not remove `stalled` from
`ftbBlocked` wholesale; establish first whether the architecturally required
gate is on the *application bookkeeping* or only on the *PC selection*, and if
it is the former, split the term rather than delete it.

> **Superseded by section 15.**  The redundancy question above was answered —
> `stalled` *is* fully redundant, for all four of `applyNow`'s consequences —
> but the cut is worth **0.020 ns** and was therefore not landed.  See
> section 15 for the proof, the what-if ladder, and why the top-100 startpoint
> census stopped identifying removable families at this checkpoint.

Behind that, the measured remaining structure is segment D of the step-1 table:
`isHit -> answerable -> cmdPort.ready -> cmdPort.fire -> seedPfWindow`, 7 levels
and 1.659 ns of prefetch-window seeding charged to the demand cycle.  The
prefetch stream window is a speculative hint — `pfNextPa`, `pfDemandLine`,
`pfLimitPa`, `pfSeqValid` — with no same-cycle architectural requirement, so
seeding it from a registered demand context costs a prefetch one cycle of
earliness and nothing else.  That is an `IcachePlugin` prefetch-spec amendment
(`2026-08-10-icache-five-id-stream-prefetch-design.md`, rules P1/P4 and the AR
ordering argument), not a FetchAlign one.

Segments B+C together are about 3.0 ns: a 32-entry fully-associative ITLB CAM
plus L1I tag/valid qualification.  Against 3.965 ns of usable period they fit,
but only once A and D are both gone.  **Do not deepen either TLB** and do not
attack B or C before D — the handoff's standing rule holds, and B/C are the
parallel-VIPT contract the IPC campaign was built on.

Archived at `synth/archive/6b246de_ftb_framing_retime_decode/`.

## 15. Measured negative result: the frontend cut ladder is exhausted (Claude, 2026-08-10)

**The dispatch hypothesis was overturned.**  Section 14 named
`stalled -> ftbBlocked -> applyNow` as the next cut and asked whether the
`stalled` term could be removed or split.  It can — the redundancy proof holds
in full, for every one of `applyNow`'s consequences, not merely the fetch-PC
mux.  But interrogating the routed checkpoint shows the cut is worth
**0.020 ns (+0.5 MHz)**, and that even the *maximal* version of it — deleting
`applyNow` from the design entirely — is worth **0.271 ns (+7.7 MHz)**.  No RTL
was landed.  The measured conclusion is that the "census the top-100
startpoints, remove that family" method that produced the previous five cuts
has stopped working at this checkpoint, and the reason is structural rather
than incidental.

All figures below come from read-only interrogation of the archived `6b246de`
routed checkpoint (`synth/archive/6b246de_ftb_framing_retime_decode/fullcore_routed.dcp`,
netlist MD5 `d80f6218c5c7dcab94a33a52d64244fa`).  The probe reproduced the
archived WNS, startpoint and endpoint exactly before any what-if was applied,
which validates the method.  Evidence is in `synth/probe_stalled/`.

### Step 1: what `stalled` is, and every consequence of `applyNow`

`stalled` is the complex-instruction emit-once latch.  It is set at exactly one
site — the edge after `feed.fire && !emittingFaultPacket && res.complex` — and
cleared at exactly five sites: the `predictFire`, `ftqMismatch`, `redirect`,
`resume && stalled` and `mispredictRedirect` blocks.  Those five conditions are
*textually identical to `ftqFlush`*, and all five also assert
`ibuf.io.flush := True` and `ringStale.foreach(_ := True)`.

`applyNow` has four consequences, not one:

| # | Consequence | Site |
|---|---|---|
| E1 | `ringKeep(resultSlot) := resultEnd` — truncates the in-flight window | `when(applyNow)` |
| E2 | FTQ push: `ftqMem.write`, `ftqTail++`, `ftqCount++` | `ftqPush := applyNow` |
| E3 | `targetHoldValid/Pc/Drop` capture, when `!ic.cmd.fire` | `when(applyNow)` |
| E4 | fetch-PC/drop mux selects `directTargetPc` | `cmdWindowPc`/`cmdDrop` |

### Step 2: the redundancy proof — it holds, for all four consequences

The dispatch asked for the "one `applyNow` event performs exactly once"
invariant to be re-proved before removing `stalled`.  It survives:

1. **While `stalled` is true, `ic.cmd.valid` is false** (`!stalled` is a direct
   term of it).  Therefore no command fires, E4 is unobservable, `ftbCmd.valid`
   (`= ic.cmd.fire && ...`) is low so no lookup launches, and E3 always takes
   its `!ic.cmd.fire` arm.
2. **At most one application can occur per stall.**  `resultExpectedValid` is
   `RegNext(ic.cmd.fire && !issueBornStale)`, so it can only be true in the
   *first* stalled cycle (from the command that fired on the complex packet's
   own fire cycle).  E3 then latches `targetHoldValid`, which is itself a
   `ftbBlocked` term, so a second application is doubly impossible.
3. **Every stall exit erases E1/E2/E3.**  The five `stalled := False` sites are
   exactly the `ftqFlush` terms; `ftqFlush` (assigned last, so it wins) zeroes
   `ftqHead/ftqTail/ftqCount` and clears `targetHoldValid`.  All five also
   flush the IBuf and stale every ring entry, so a truncated `ringKeep` window
   is discarded either in flight or after landing, and `ringKeep` is rewritten
   to 4 at the next issue.
4. **During the stall the residue is inert.**  `feed.valid` is false, so
   `ftqConfirmFire`, `predictDetect`, the RAS push/pop and the GHR shift cannot
   fire; `ftqMismatchDetect` and `ftqStarveRaw` carry explicit `!stalled`
   terms.  The only live readers of a newly non-empty FTQ are `availEff` (which
   can only make `p0LiveReg` report ambiguity, a conservative stall, and is
   flushed at exit) and `slot1ValidOut`, which every consumer qualifies with
   `fed.valid`.  A non-empty FTQ during a stall is in any case already
   reachable today from applications made *before* the complex packet fired.
5. The capacity bound is unchanged: applications remain 1:1-bounded by live
   cache commands, and a stalled application still consumes a ring slot, so
   `RING + BUF_WORDS` still holds.

Note this argument is *deferred*, not same-cycle: the design's existing
tolerance for `predictFire`/`ftqMismatch`/`mispredictRedirect` colliding with
`applyNow` rests on same-cycle `ftqFlush` priority, whereas `stalled` is a
level and the erasure happens at the stall's exit.  That is why the dispatch
was right to demand the proof — it is a genuinely different argument.  It is
recorded here so a future implementer does not have to redo it, but it was
**not simulation-proved**, because the measurement below made the cut
pointless.

### Step 3: the measured ceiling — a `set_false_path` what-if ladder

Static what-if on the routed checkpoint is a fair predictor *for this cut*
specifically, because unlike `28ec738` (which deleted a 6-bit comparator and
its fanout) and `6b246de` (which deleted 2,825 LUTs of live framing cone),
removing `stalled` from `ftbBlocked` deletes essentially no logic: `ftbBlocked`
goes from a 7-leaf to a 5-leaf OR and `applyNow` from 12 to 10 inputs, both of
which map to the same LUT depth.  There is no cone shrink for the placer to
exploit.

| What-if | WNS | New worst path |
|---|---:|---|
| baseline (reproduced) | -2.094 | `stalled -> s1PredEntries_3[18]/CE` |
| false-path `stalled -> applyNow` only | **-2.074** | `FtbPlugin rspPayload_brWordOff[0] -> same endpoint` |
| false-path **every** `stalled` path | **-2.074** | identical — `applyNow` is `stalled`'s only critical route |
| false-path the whole `applyNow` net | **-1.823** | `DcachePlugin stS1Payload_paddr[6] -> IssueQueuePlugin sbInt_busy[34]` |
| + take the ITLB result off the demand path | -1.823 | unchanged — the limit has left the frontend |
| + false-path `stS1Payload_paddr` | -1.800 | `DcachePlugin missSet[4] -> IssueQueuePlugin sbInt_busy[34]` |
| + false-path decode `spec_size` | -1.800 | unchanged |
| + false-path `RobPlugin doFlushReg` | **-1.800** | unchanged |

So: **removing the top four families outright moves WNS from -2.094 to -1.800,
a total of 0.294 ns (164.096 -> 172.414 MHz).**  200 MHz needs WNS >= -1.000,
i.e. 1.094 ns.  There is no family-cutting route to it from this checkpoint.

### Step 4: why the top-100 startpoint census stopped being a family detector

`stalled` really is the startpoint of **2,673 of the worst 4,000 unique-endpoint
paths**.  It is also worth 0.020 ns.  Both are true because it is the
*latest-arriving input to a very large shared cone*, and the inputs immediately
behind it (`FtbPlugin rspPayload_brWordOff`, `quiesce`, `rspPayload_brType`,
`targetHoldValid`, and then the mux data input `rspPayload_target`) are 0.020,
0.023 and 0.271 ns behind respectively.  Cutting one merely rotates the census
to the next.

This is the same class of error section 14 recorded for the `High Fanout`
column, one level up.  **Reusable lesson: a startpoint census counts which
register happens to arrive last into a shared cone; it does not measure what
that register costs.  Pair every census with a `set_false_path` what-if ladder
on the routed checkpoint before designing a cut.**  The ladder above takes
about 40 seconds of Vivado time against 20+ minutes for a route, and it would
have correctly predicted this result before any RTL was written.

The slack distribution confirms the plateau.  Over the worst 4,000 unique
endpoints:

| Slack bucket (ns) | Endpoints |
|---|---:|
| -2.1 .. -2.0 | 144 |
| -2.0 .. -1.9 | 738 |
| -1.9 .. -1.8 | 460 |
| -1.8 .. -1.7 | 358 |
| -1.7 .. -1.6 | 606 |
| -1.6 .. -1.5 | 843 |
| -1.5 .. -1.4 | 685 |
| -1.4 .. -1.3 | 166 |

Only 144 endpoints are worse than -2.0.  There is no tall spike left to remove.

### Step 5: the real families, by count rather than by top-100

Worst-4,000 unique-endpoint paths, grouped:

| Startpoint register | Paths | | Endpoint register | Paths |
|---|---:|---|---|---:|
| `FetchAlignPlugin stalled` | 2,673 | | `IcachePlugin lineReg` | 1,024 |
| `DcachePlugin stS1Payload_paddr` | 752 | | `IcachePlugin s1PredEntries_{0..3}` | 920 |
| `DecodeStage fed_payload_specs_0_spec_size` | 418 | | `DcachePlugin s0Payload_lineData` | 241 |
| `DcachePlugin tagMem_2` | 47 | | `FetchAlignPlugin fetchPc` | 58 |
| `AluEuPlugin s1Ctx_uop_op` | 29 | | `IcachePlugin arHoldAddr` | 52 |
| `LsEuPlugin p3Ctx_paddr` | 20 | | `IssueQueuePlugin sbInt_busy` | 50 |

The three post-frontend families, with their real shape:

- `DcachePlugin stS1Payload_paddr[6] -> IssueQueuePlugin sbInt_busy[34]`:
  5.805 ns, 22 levels, **70.5% route**.
- `DecodeStage fed_payload_specs_0_spec_size[0] -> FetchAlign predictPending`
  (and `RasPlugin ras_*`): 5.757 ns, 17 levels, **77.9% route**.
- `stalled -> IcachePlugin lineReg[60]/CE`: 5.930 ns, 21 levels, 63.6% route.

Every one of them is route-dominated at 17-22 levels.  That is the signature of
a placement/congestion-bound design, not a logic-depth-bound one, and it is why
single-term control cuts have stopped paying.

### Step 6: what to do instead

1. **Do not spend a route on the `stalled` term.**  It is +0.5 MHz for a
   weakened external-input collision contract.
2. The frontend demand cone (`applyNow` -> fetch-PC mux -> ITLB CAM -> L1I tag
   -> `cmd.ready` -> `cmd.fire` -> S1/`lineReg` capture enable) is worth
   0.271 ns in total and cannot be improved further without breaking the
   parallel-VIPT contract.  Section 14's "attack segment D next" is still the
   best frontend idea — the S1 capture *enable* is `cmd.fire`-gated and
   therefore sits behind the ITLB and tag compare, while the S1 *data*
   (`s1PredEntries`) is a raw all-ways BRAM read with no hit dependency, so the
   enable could be `cmd.valid`-gated with `s1Valid` carrying the qualification —
   but the ladder caps the whole frontend at +7.7 MHz, so it is no longer the
   priority.
3. The next campaign target is **`DcachePlugin` store-S1 physical address ->
   `IssueQueuePlugin` integer/NZVC scoreboard busy**, plus the decode
   `spec_size` -> RAS/predict family.  Both are new subsystems for this
   campaign and both need their own grounding pass.
   > **Both grounding passes are done and both came back negative.**  The
   > D-cache/IQ family is worth 0.000 ns (section 16); the decode
   > `spec_size` -> `predictPending`/RAS family is worth +0.006 ns, and its whole
   > endpoint group +0.004 ns (section 17).  Section 17 step 6 also measures the
   > 200-rung ladder: retiring *two hundred* families still lands at 189.7 MHz.
   > Do not pick a fourth family — go to placement/implementation strategy.
4. Because the plateau is route-dominated, **re-validate the floorplan**: the
   four-mode comparison in section 5 was measured at `3c4e1f8`, five RTL cuts
   ago, when the decode pblock was 78.23% full; it is now 67.91% full and the
   limiting families have moved from the frontend to the D-cache/IQ.  Step 7
   below is that experiment.

### Step 7: exact-DCP floorplan re-validation — `decode` still wins

Four modes, all placed and routed from the **identical** `6b246de`
post-synthesis checkpoint (`REUSE_SYNTH_DCP=1`; all four reported the same
post-synth WNS -1.827, which is the control that proves the netlist was
identical).

| Mode | WNS / FMax | TNS | Failing endpoints | Routed LUT / FF |
|---|---:|---:|---:|---:|
| **decode** (default) | **-2.094 ns / 164.096 MHz** | **-21,068.689** | **32,729** | 110,662 / 50,389 |
| none | -2.187 ns / 161.629 MHz | -29,536.104 | 43,701 | 110,774 / 50,384 |
| dcache | -2.665 ns / 150.038 MHz | -43,360.996 | 61,293 | 110,771 / 50,401 |
| both | -2.669 ns / 149.948 MHz | -38,648.195 | 47,702 | 110,693 / 50,436 |

All four routes are clean: zero hold failures, zero pulse-width failures,
WHS +0.023 to +0.030 ns, WPWS +1.458 ns, no congestion window above level 5.
Archived at `synth/archive/6b246de_floorplan_{none,dcache,both}/`.

Two things worth carrying forward:

- **`decode` remains correct as the default**, so section 5's guidance is
  re-confirmed on the current netlist rather than inherited from `3c4e1f8`.
  The broad D-cache pblock is still clearly harmful, and `both` is now the
  *worst* mode, not the second best — so it is not merely inert.
- **The decode pblock's WNS advantage has collapsed from 0.442 ns to
  0.093 ns** (`none` was -3.162 vs -2.720 at `3c4e1f8`; it is -2.187 vs -2.094
  now).  Five RTL cuts removed most of what the pblock was buying.  Its TNS and
  failing-endpoint advantage is still large (-21.1k/32.7k versus -29.5k/43.7k),
  so keep it, but do not expect a *new* floorplan to be the lever that reaches
  200 MHz.  A floorplan aimed at the *current* limiters would have to enclose
  `DcachePlugin` store-S1 plus `IssueQueuePlugin`'s scoreboard, which the
  existing `pb_dcache` box does not do (and `pb_dcache` already
  over-captures through flattened-name matching, per section 5).

### Step 8: new reusable tool — `synth/probe_slack_ladder.tcl`

Committed with this section.  It opens a routed checkpoint read-only, emits a
slack histogram and a startpoint/endpoint family census over the worst N unique
endpoints, then repeatedly `set_false_path`s the *current* worst startpoint
register and re-reports WNS — so the delta between rungs is what retiring that
family is actually worth.  It costs about 40 seconds against 11-21 minutes for
a route.

Its output on `6b246de` is the whole argument of this section in one table:

```
rung   WNS      delta   worst startpoint
   0  -2.094   +0.000   FetchAlignPlugin_logic_stalled_reg
   1  -2.074   +0.020   FtbPlugin_logic_rspPayload_brWordOff_reg[0]
   2  -2.050   +0.024   FetchAlignPlugin_logic_quiesce_reg
   3  -2.024   +0.026   GsharePlugin_logic_pht_spinal_port4_reg[1]
   4  -2.002   +0.022   FetchAlignPlugin_logic_targetHoldValid_reg
   5  -2.001   +0.001   FtbPlugin_logic_rspPayload_brType_reg[0]
   6  -1.960   +0.041   GsharePlugin_logic_pht_spinal_port3_reg[1]
   7  -1.959   +0.001   FetchAlignPlugin_logic_faultHold_reg
   8  -1.959   +0.000   GsharePlugin_logic_pht_spinal_port2_reg[1]
   9  -1.932   +0.027   GsharePlugin_logic_pht_spinal_port5_reg[1]
  10  -1.848   +0.084   FetchAlignPlugin_logic_ftbSuppress_reg
```

Every rung has the same endpoint, `IcachePlugin_logic_s1PredEntries_3_reg[18]/CE`.
Eleven consecutive register families — `applyNow`'s AND-term inputs, its
`ftbBlocked` OR-term inputs, and the gshare PHT read ports behind
`resultDirection` — are worth **0.246 ns between them**.  That is what a
shared cone looks like from the outside, and it is exactly the shape a
startpoint census cannot distinguish from a removable family.

Run it before designing the next cut:

```bash
DCP=synth/archive/<ckpt>/fullcore_routed.dcp LADDER_STEPS=10 \
  vivado -mode batch -nojournal -source synth/probe_slack_ladder.tcl
```

### Step 9: the miss/predecode-path pipelining licence, measured

**Standing architectural licence (from the user, 2026-08-10):** predecode runs
on a cache-line *miss*, and that latency is such that a deeper pipeline there is
essentially free.  For any signal that lives on the miss-fill / prefetch-install
path rather than the resident-hit path, inserting a pipeline register costs
nothing measurable in real IPC — it hides behind tens of cycles of L2/DDR line
service.  This does **not** extend to the resident-hit II=1 cadence, where every
added cycle is a real throughput cost.  Record this when scoping any I-cache
cut: it materially lowers the burden of proof on the miss/prefetch side, and it
makes the already-scouted prefetch-window-seed cut (section 14, segment D) a
low-risk pipeline insertion rather than something needing resident-hit-grade
scrutiny.

It also has a measurable consequence, and the measurement is worth having before
anyone spends a route on it.  Freeing **every** I-cache miss/prefetch-state
endpoint (`lineReg`, `pf*`, `miss*`, `arHold*`, both CE and D) on the `6b246de`
routed checkpoint:

| What-if | WNS | New worst path |
|---|---:|---|
| baseline | -2.094 | `stalled -> s1PredEntries_3[18]/CE` |
| all miss/prefetch-state endpoints free | **-2.094** | **unchanged** |
| + resident-hit `s1*` capture also free | -1.991 | `stalled -> IcachePlugin commitBeat[0]/D` |

So the licence is real and useful but **buys 0.000 ns of WNS at this
checkpoint**: the binding endpoints are the resident-hit S1 predecode capture
(`s1PredEntries`, 920 of the worst 4,000), not the miss/prefetch state.  Where
it does pay is breadth — `lineReg` alone is 1,024 of the worst 4,000 endpoints,
plus about 260 more across `arHoldAddr`/`missPA`/`pfNextPa`/`pfPa_*`/`pfLimitPa`/
`pfDemandLine`, so pipelining them is worth roughly 1,280 failing endpoints and
a large slice of TNS even at unchanged WNS.  This campaign has twice seen TNS
and endpoint-count reductions convert into real placement gains (`28ec738`,
`6b246de`), so it is a legitimate cut to bank — just not one to expect an FMax
number from on its own.

### Status after this section

No RTL changed; `6b246de` remains the head RTL checkpoint at **-2.094 ns /
164.096 MHz**, `FLOORPLAN_MODE decode`, netlist MD5
`d80f6218c5c7dcab94a33a52d64244fa`.  All six landed timing cuts are preserved.
The frontend recovery ladder that took the branch from 131.010 to 164.096 MHz
is finished; the next work is the D-cache/IQ plateau, and it should begin with
a ladder run, not a census.

## 16. Measured negative result: the D-cache/IQ family is worth 0.000 ns (Claude, 2026-08-10)

**Grounding only.  No RTL changed, and none should be written for this family.**
Section 15 step 5 named `DcachePlugin stS1Payload_paddr -> IssueQueuePlugin
sbInt_busy` (752 of the worst 4,000 paths, 5.805 ns / 22 levels / 70.5 % route)
as the next campaign target.  Running `synth/probe_slack_ladder.tcl`'s
methodology against it first — exactly as section 15 step 8 instructs — shows
the family is worth **0.000 ns of WNS, 0.27 % of TNS and six failing
endpoints**.  Section 15's lesson reproduces on a second, independent subsystem,
and in a more extreme form: 752 census paths, zero recoverable slack.

Evidence: `synth/probe_dcache_iq/` (script `synth/probe_dcache_iq.tcl`),
read-only against `synth/archive/6b246de_ftb_framing_retime_decode/fullcore_routed.dcp`,
netlist MD5 `d80f6218c5c7dcab94a33a52d64244fa`.  The probe reproduced the
archived WNS (-2.094), TNS (-21,068.689), failing-endpoint count (32,729) and
worst path exactly before any what-if, which validates the method.

### Step 1: the measurement

| What-if | WNS | TNS | Failing endpoints | Worst path |
|---|---:|---:|---:|---|
| baseline (reproduced) | -2.094 | -21,068.689 | 32,729 | `stalled -> s1PredEntries_3[18]/CE` |
| false-path **all 32** `stS1Payload_paddr` regs | **-2.094** | -21,011.314 | 32,723 | unchanged |
| + false-path **all 50** `sbInt_busy` regs | **-2.094** | -20,934.162 | 32,673 | unchanged |
| + free the whole frontend (`applyNow` nets + `stalled`) | -1.778 | -19,772.711 | 32,640 | `FtbPlugin rspPayload_target[12] -> s1PredEntries_3[18]/CE` |

Deleting the entire startpoint family recovers **0.000 ns of WNS**, 57.4 ns of
TNS (0.27 %) and **six** failing endpoints.  Deleting the entire *endpoint*
family on top of it — every bit of `sbInt_busy`, which no real cut could ever
achieve — recovers 134.5 ns of TNS (0.64 %) and 56 endpoints, still at
0.000 ns of WNS.

The reason is arithmetic, and it is the reason the census misleads.  The
family's own best slack is **-1.823 ns**, which is **0.271 ns better than the
design's WNS**.  It is not the critical path and never was.  The 5.805 ns
figure quoted in section 15 step 5 is *data-path delay*, not slack; by slack
this family is third, behind `stalled -> s1PredEntries` (-2.094) and the
frontend cone generally.  Delay and criticality are different quantities and
this campaign should stop quoting the former.  Separately, the 752 census paths
map to only six *exclusively* affected endpoints: each of the other 746
endpoints keeps a near-identical violating path from a different startpoint into
the same cone, so retiring one startpoint merely rotates the census.

### Step 2: the ladder past it — the whole region is a backfilled plateau

With the frontend and this family both freed, fourteen further rungs:

```
rung   WNS      delta   worst startpoint -> endpoint
   0  -1.778   +0.000   FtbPlugin rspPayload_target[12] -> s1PredEntries_3[18]/CE
   1  -1.776   +0.002   DecodeStage spec_size[0]        -> FetchAlign predictPending/D
   2  -1.770   +0.006   DecodeStage packets_0_words_0[4]-> FetchAlign predictPending/D
   3  -1.752   +0.018   DcachePlugin missSet[4]         -> IssueQueuePlugin sbNzvc_busy[9]/D
   4  -1.745   +0.007   FetchAlign targetHoldPc[13]     -> s1PredEntries_3[18]/CE
   5  -1.741   +0.004   FetchAlign fetchPc[14]          -> s1PredEntries_3[18]/CE
   6  -1.716   +0.025   FetchAlign ftqHead[0]           -> FetchAlign p0LiveReg_lenWords[2]/D
   7  -1.696   +0.020   DcachePlugin stS2Payload_paddr[8] -> IssueQueuePlugin sbNzvc_busy[9]/D
   8  -1.691   +0.005   FetchAlign targetHoldValid      -> s1PredEntries_3[18]/CE
   9  -1.672   +0.019   DcachePlugin missCmode[1]       -> IssueQueuePlugin sbNzvc_busy[9]/D
  10  -1.664   +0.008   DcachePlugin tagMem_2/CLKARDCLK -> DcachePlugin valids_0_30/D
  11  -1.644   +0.020   DecodeStage packets_0_words_1[0]-> FetchAlign predictPending/D
  12  -1.632   +0.012   DcachePlugin tagMem_3/CLKARDCLK -> DcachePlugin valids_0_30/D
  13  -1.625   +0.007   FetchAlign ibuf/headPtr[0]      -> FetchAlign predictPending/D
  14  -1.613   +0.012   FetchAlign ibuf/headPtr[1]      -> FetchAlign predictPending/D
```

Rungs 3, 7 and 9 are the *same* D-cache-to-IQ-scoreboard cone reappearing with
three different D-cache startpoints (`missSet`, `stS2Payload_paddr`,
`missCmode`) into `sbNzvc_busy` instead of `sbInt_busy` — 0.057 ns between
them.  That is the definitive backfill signature: the cone, not the register,
is the object, and the cone has at least half a dozen registers arriving within
0.06 ns of each other.

**Aggregate ceiling: retiring seventeen consecutive register families —
the whole frontend demand cone plus the whole D-cache/IQ ready cone plus the
decode `spec_size` family plus the D-cache tag/valid arcs — moves WNS from
-2.094 to -1.613, i.e. 0.481 ns, 164.096 -> 176.601 MHz.**  200 MHz needs
1.094 ns.  Family cutting, even taken to an unreachable limit, reaches 44 % of
the required gain.  The method is finished, not merely tired.

### Step 3: what the D-cache-to-IQ connection actually is

It is not a data dependency.  It is the **elastic accept-last ready chain**,
combinational and unbroken from the D-cache's admission decision back through
four LS-EU stages into the issue queue's scoreboard bookkeeping:

```text
stS1Payload.paddr  (registered store descriptor, store-drain S1)
  -> stS1Set = paddr(offBits+setBits-1 downto offBits)                    DcachePlugin:635
  -> refillWriteHold = (stS1Valid && !barriers && stS1Set === missSet) || ...   :802
  -> refill / store array write enables  wrEn(w), wrSet(w)
  -> earlyProbeSetWriteVec(i) = OR_w( wrEn(w) && wrSet(w) === probeVaddr(i).set ) :418
  -> earlyProbeHit  = earlyProbeOwnsCmd && earlyProbeHits(idx)
                      && !earlyProbeSetWriteVec(idx)                      :426
  -> useEarlyProbe  = earlyProbeHit && !ldS1Valid                         :429
  -> loadProbePort.ready / loadCmdPort.ready                              :929 / :965
  -> LsEu normalReqArm = tValid && tIsMem && txReady && ...
                         && (!tIsLoad || dcache.loadProbe.ready)          LsEuPlugin:1749
  -> tCanLeave -> tReady -> s1ToT -> s1Ready
  -> issuePort.ready = s1Ready && !sqFlushSig && !excActive               LsEuPlugin:1780
  -> IssueQueuePlugin selPorts(3).ready -> selPorts(3).fire
  -> when(selPorts(k).fire){ when(pdstValid){ sbInt.busy(pdst) := False } } IQ:948-953
```

The last hop is what makes it wide rather than merely long: the scoreboard
clear is a *decoded* write over a 50-bit bitmap, so one `fire` bit fans into 50
independent write-enable cones, each ANDed with a 6-bit `pdst` comparator.  That
is the 50-endpoint `sbInt_busy` family, and 46 of its 50 worst paths start at
`stS1Payload_paddr` (the other 4 at `RobPlugin doFlushReg`).

The semantic question the arc answers is legitimate and cheap to state: *"may
this load consume its held early-probe snapshot, or did a concurrent store /
refill array write to the same virtual set stale it?"*  The store-S1 physical
set index is one of the four write-port sets being compared.  Nothing here is
accidental or vestigial — it is the price of the `useEarlyProbe` VIPT fast path
that the IPC campaign was built on (section 3: "the hard test requires
`useEarlyProbe` for every hit").

It is also *physically* long: the routed path runs SLICE_X48Y203 (D-cache
store-drain) -> X50-54Y192-206 (D-cache probe/write control) -> X52Y181 (LS EU
tx) -> X61Y135 -> X73Y131 (IQ scoreboard).  About 72 CLB rows and 25 columns,
which is where the 70.5 % route comes from.  Section 15 step 7 already noted
that no existing pblock encloses both ends.

### Step 4: the cross-referenced `fe3de1c` pattern does not transfer — it is already here

`fe3de1c` `iq: delete provably-redundant !slowFire term from the scoreboard
busy-clear gate (FMax)` is **already in this branch's history** for
`src/main/scala/m68k040/execute/iq/IssueQueuePlugin.scala`, three commits below
`61ce032`.  The clear loop at IQ:948-953 is the post-fix form —
`when(selPorts(k).fire)` with no `!slowFire` — and the long comment above it
records that grounding (it measured the family at -1.699 ns and the `op`-field
MuxOH as the mechanism).  So the pattern is not a new opportunity here; it was
the *previous* cut on this exact endpoint family, and what remains is the
irreducible `fire && pdstValid && pdst==k` decode.  Notably that earlier cut
took this family from WNS-holder (-1.699) to non-critical (-1.823 today, third
place) — it worked, and it used up the available slack in this cone.

### Step 5: fix shapes considered, and why none is justified

Two clean, spec-shaped cuts exist.  Both are worth 0.000 ns and neither should
be built for timing reasons:

1. **Register the IQ -> LS-EU issue-port ready (2-deep skid).**  This severs the
   whole chain above at its widest point.  It is the textbook fix for a long
   combinational ready chain, it is architecturally clean, and it costs a real
   `IqContext`-width buffer plus a re-proof of the LS ordering/flush contract.
   Measured worth: 0.000 ns WNS.
2. **Retime `earlyProbeSetWriteVec` out of `earlyProbeHit` into a registered
   stale-invalidate of `earlyProbeReadies(i)`** — the same class of cut as
   `6b246de` (register the verdict when the information becomes final).  This
   would take every write-port set comparison, and therefore `stS1Set`,
   `stS2Set`, `stS3Set` and `missSet`, entirely off `loadCmdPort.ready` /
   `loadProbePort.ready`.  It is *not* a pure retime: the same-cycle
   write-versus-consume case would have to be re-proved or conservatively
   blocked, because a snapshot consumed in the same cycle as the write that
   stales it is exactly the case the live compare exists to catch.  Measured
   worth: 0.000 ns WNS, and it would put a genuine correctness invariant at
   risk for 0.27 % of TNS.

Recommendation: do neither now.  Keep (2) on file as a candidate *if* the
design ever becomes IPC-limited by `loadProbe.ready` back-pressure, where it
would be justified on throughput grounds rather than timing.

### Step 6: what this means for the 200 MHz goal

The remaining 1.094 ns is not in any register family.  Three independent
measurements now say the same thing:

- section 15: the frontend ladder caps at 0.294 ns and eleven consecutive
  families share 0.246 ns of it;
- section 15 step 9: freeing every I-cache miss/prefetch endpoint buys
  0.000 ns;
- this section: freeing the top two post-frontend families buys 0.000 ns, and
  seventeen families together buy 0.481 ns.

Every limiting path is 17-22 levels at 63-78 % route on a device that is 51 %
full.  That is a **placement/congestion-bound** design.  The levers that remain
are structural, and they should be evaluated in this order:

1. **A floorplan aimed at the current limiters**, not the historical ones.  The
   `pb_decode` box's advantage has already collapsed from 0.442 ns to 0.093 ns
   (section 15 step 7) and `pb_dcache` is actively harmful.  The measured spans
   here — D-cache store-drain at Y~203, LS EU at Y~181-194, IQ scoreboard at
   Y~131-135 — argue for a *backend* region enclosing `DcachePlugin` +
   `LsEuPlugin` + `IssueQueuePlugin`, and for repairing `pb_dcache`'s
   flattened-name over-capture first.  This is a same-DCP experiment, cheap.
2. **A real pipeline stage in the two long elastic chains** — the frontend
   demand cone (section 14 segment D) and the LS accept chain (step 5 fix 1
   above) — accepted as a *cycle* cost and justified by IPC measurement, not by
   the what-if ladder, since a static ladder cannot model the placement freedom
   a genuine cone deletion creates.  Both of this campaign's two best cuts beat
   their static prediction for exactly that reason.
3. **Retiming/replication passes in the tool** (`-directive` sweep,
   `phys_opt_design` iterations) before any further RTL.  Untried on this
   branch and cheap relative to an RTL cut.

The ladder tool should still be run before each of these, but its role changes:
it is now an *exclusion* test (does this cut have any WNS to recover at all?)
rather than a target selector.

### Status after this section

No RTL changed.  `6b246de` remains the head RTL checkpoint at **-2.094 ns /
164.096 MHz**, `FLOORPLAN_MODE decode`.  New committed tool:
`synth/probe_dcache_iq.tcl` (adds TNS / failing-endpoint deltas and an
arbitrary-family what-if to the section-15 ladder, which only reported WNS).

## 17. Measured negative result: `spec_size -> predictPending / RAS` is a decode-backpressure cone worth +0.006 ns (Claude, 2026-08-10)

**Grounding only.  No RTL changed, and none should be written for this family.**
Section 15 step 5 named `DecodeStage fed_payload_specs_0_spec_size ->
FetchAlign predictPending` / `RasPlugin ras_*` (418 of the worst 4,000 paths,
5.757 ns / 17 levels / **77.9 % route**) as the second of two new campaign
targets.  Section 16 measured the first one (D-cache/IQ) at 0.000 ns.  This is
the second, and the result is the same in kind: **retiring the `spec_size`
startpoint family outright is worth +0.006 ns, and retiring the entire
`predictPending` + `predictTargetReg` + `RasPlugin` *endpoint* group — 675
cells, far more than any real cut could reach — is worth +0.004 ns measured
across a 200-rung ladder.**

Evidence: `synth/probe_specsize/` (scripts `synth/probe_specsize.tcl`,
`synth/probe_specsize2.tcl`, `synth/probe_specsize3.tcl`), all read-only against
`synth/archive/6b246de_ftb_framing_retime_decode/fullcore_routed.dcp`, netlist
MD5 `d80f6218c5c7dcab94a33a52d64244fa`.  Every probe reproduced the archived
WNS (-2.094), TNS (-21,068.689), failing-endpoint count (32,729), startpoint and
endpoint exactly before any what-if, which validates the method.

### Step 1: what the architectural connection actually is

The dispatch hypothesis was that a decoded µop's SIZE field feeds fetch
prediction because instruction length is needed to compute a next-fetch target.
**That is wrong.**  `spec_size` does not reach the predictor as *data* at all.
It reaches it as *backpressure*, through the decode stage's elastic `ready`
chain.  The real arc, read off the live RTL:

```text
DecodeStage.scala:135    fedIn.payload.specs(i) := MicroOpAssembler.computeOffload(raw.payload.packets(i))
DecodeStage.scala:146    val fed = PipeStage(fedIn, pipeFlush)          <- spec_size register lives here
DecodeStage.scala:159    a0 = MicroOpAssembler.assemble(fed.packets(0), fed.specs(0))
DecodeStage.scala:2004   fed.ready := (!stashValid && !movemHoldsFed && !slot0IsMovem && !ucHoldsFed &&
                                       !movepHoldsFed && !slot0IsMovep && pushProduced.ready)
                                      || movemEnterSlot0 || ucEnterSlot0 || movepEnterSlot0
DecodeStage.scala:137    raw.ready   := fedIn.ready                     <- = !fedValid || fed.ready   (PipeStage.scala:16-18)
DecodeStage.scala:111    df.feed.ready := rawIn.ready                   <- = !rawValid || raw.ready
FetchAlignPlugin:1045    predictDetect := feed.fire && !faultHold && predictedThisEmit   -> predictPending
FetchAlignPlugin:1126-28 rasPushValid  := feed.fire && !faultHold && s0IsCall
                         rasPopValid   := feed.fire && !faultHold && rasPredictSlot0
```

So: `spec.size` is an input to `assemble`, whose output drives the decode-stage
ownership/serialization predicates (`slot0IsMovem`, `slot0IsMovep`,
`slot0OwnedByUc`, `deferSlot1`, `pushProduced.ready`) that form `fed.ready`.
`fed.ready` then propagates **backwards, combinationally, through two 1-deep
`PipeStage`s** — each of which has `in.ready := !valid || out.ready`
(`PipeStage.scala:16-18`) — to `df.feed.ready`, and FetchAlign gates *all* of its
prediction bookkeeping on `feed.fire = feed.valid && feed.ready`.  Prediction and
RAS are downstream of decode's acceptance decision, not of any length field.

The routed path confirms this exactly.  Its penultimate stretch is the net
`FetchAlignPlugin_logic_ibuf/when_PipeStage_l17` at fanout 462 — that is
`PipeStage.scala:17`'s `when(slotFree)`, i.e. the `raw` stage's
`!valid || out.ready` — feeding `predictTargetReg[31]_i_3 ->
predictPending_i_9 -> _i_3 -> _i_1 -> predictPending_reg/D`.  The prefix
(0.000 -> 4.316 ns of the 5.757 ns) is the decode-side `fed.ready` cone; only the
last 1.47 ns is FetchAlign consuming it.

Note also the *physical* asymmetry that makes this path 77.9 % route: the
startpoint `_zz_..._spec_size_reg[0]` is placed at `SLICE_X57Y20` inside
`pb_decode`, and `predictPending_reg` at `SLICE_X24Y99` outside it — 4.487 ns of
routing across that span.  This is the decode-pblock boundary that section 5
already flags.

### Step 2: the measurement — WNS

| What-if (from the pristine routed checkpoint) | WNS | Worst path |
|---|---:|---|
| baseline (reproduced) | -2.094 | `stalled -> s1PredEntries_3[18]/CE` |
| false-path **all 7** `fed_payload_specs_*_spec_size` regs | **-2.094** | **unchanged** |

The family is 0.318 ns *behind* the WNS.  Its own worst path is **-1.776 ns**,
i.e. it imposes a floor of 173.13 MHz — but it costs **0.000 ns today**.

Worst slack into each endpoint sub-family, from any startpoint:

| Endpoint group | Cells | Worst slack | Worst source |
|---|---:|---:|---|
| `FetchAlignPlugin predictPending` | 3 | **-1.776** | `spec_size[0]` |
| `RasPlugin ras_*` (whole plugin) | 576 | -1.727 | `spec_size[0]` |
| `FetchAlignPlugin predictTargetReg` | 96 | -1.441 | `spec_size[0]` |

### Step 3: the ladder — where the family lands, and what it is worth

A 200-rung ladder from the pristine checkpoint (`synth/probe_specsize/ladder_ref200.txt`):

```
rung   WNS      delta   worst startpoint -> endpoint
  12  -1.800   +0.023   DcachePlugin missSet[4] -> IssueQueue sbInt_busy[34]
  13  -1.778   +0.022   FtbPlugin rspPayload_target[12] -> s1PredEntries_3[18]/CE
  14  -1.776   +0.002   DecodeStage fed_payload_specs_0_spec_size[0] -> predictPending/D
  15  -1.770   +0.006   DecodeStage fed_payload_packets_0_words_0[4] -> predictPending/D
  16  -1.745   +0.025   FetchAlign targetHoldPc[13] -> s1PredEntries_3[18]/CE
```

`spec_size` becomes binding only at rung 14, after thirteen other families are
retired, and **retiring it is worth +0.006 ns (rung 15's delta)** — after which
the *same endpoint* is immediately re-limited by the next `fed` payload register.
This is section 15's shared-cone artifact reproducing for a third time: 19 of the
200 rungs end at `predictPending_reg/D`, sourced by
`fed_payload_specs_0_spec_size`, `fed_payload_packets_0_words_0`,
`fed_payload_packets_0_words_1`, `ibuf/headPtr[0..3]`,
`fed_payload_specs_0_srcEa_base`, `ibuf/entries_*_pred_lenWords`,
`ibuf/entries_*_pred_ambiguousLine`, `decodePc`, `ibuf/count` — i.e. essentially
**every register that can reach `feed.valid` or `feed.ready`**.  There is no
"`spec_size` family"; there is one `feed.fire` cone with dozens of
near-simultaneous inputs.

### Step 4: the decisive control — retire the whole endpoint group

To bound the *maximal* version of any conceivable fix, the same 200-rung ladder
was re-run with all 675 `predictPending` + `predictTargetReg` + `RasPlugin_logic_*`
cells false-pathed as **endpoints** from rung 0
(`synth/probe_specsize/ladder_nopredict200.txt`):

| Rung | Reference ladder WNS | Endpoint-group-free ladder WNS | Divergence |
|---:|---:|---:|---:|
| 0 | -2.094 | -2.094 | 0.000 |
| 5 | -2.001 | -2.001 | 0.000 |
| 10 | -1.848 | -1.848 | 0.000 |
| 20 | -1.720 | -1.716 | 0.004 |
| 50 | -1.502 | -1.498 | 0.004 |
| 100 | -1.389 | -1.383 | 0.006 |
| 150 | -1.324 | -1.322 | 0.002 |
| **199** | **-1.271** | **-1.267** | **0.004** |

**Deleting every path into the entire prediction/RAS state of the design — a
strictly stronger intervention than any real RTL cut — is worth 0.004 ns.**

TNS and failing endpoints (`synth/probe_specsize/probe3.out`), which section 15
step 9 established as the metric worth banking even at unchanged WNS:

| What-if | WNS | TNS | Failing endpoints |
|---|---:|---:|---:|
| baseline (reproduced exactly) | -2.094 | -21,068.689 | 32,729 |
| `spec_size` startpoints free (7 cells) | -2.094 | -21,045.141 | 32,723 |
| + whole `predictPending`/`predictTargetReg`/RAS endpoint group free (675 cells) | -2.094 | **-20,263.918** | **32,137** |

So the *startpoint* family is worth 23.5 ns of TNS (0.11 %) and six endpoints;
the whole endpoint group is worth 804.8 ns (3.8 %) and 592 endpoints.  That
breadth is real but is only about half the I-cache miss/prefetch licence already
scouted in section 15 step 9 (~1,280 endpoints), and unlike that one it is on the
resident II=1 decode-accept path, so it is strictly the worse of the two banks.

### Step 5: the fix that exists, and why it should not be built now

There *is* a clean, textbook fix, and it is worth recording so nobody re-derives
it: **sever the reverse `ready` path** by making `rawIn.ready` a function of
local occupancy registers only — replace the `raw` `PipeStage` with a 2-entry
skid buffer (`s2mPipe`-style), so `df.feed.ready` no longer depends
combinationally on `fed.ready` and therefore not on `assemble`/`spec_size` at
all.  Throughput is preserved by construction; the cost is one extra `RawPacket`
of storage (2 × `DecodePacket` ≈ 540 FF, about +1.1 % of the design's flops) plus
the obligation to extend `pipeFlush` to squash *both* held entries and to
re-prove the `feed.fire`-gated bookkeeping (`predictDetect`, `rasPush/PopValid`,
`gsShiftValid`, `ftqConfirmFire`, `ftqMismatchDetect`) under the changed
acceptance cadence — that last item is a genuine cycle-behaviour change to the
frontend, not a retiming, so it would need the full §9-item-8a-grade mutation
proof the last two cuts received.

**Measured value: +0.006 ns (+0.2 MHz).**  That is a real elasticity change to
the frontend/decode contract, with a real IPC exposure surface, for a quarter of
what the already-rejected `stalled` cut was worth.  Do not build it.

### Step 6: the ladder's own verdict on 200 MHz

The 200-rung reference ladder is the most useful thing produced by this pass, and
it should retire the "find the next family" strategy outright:

| Families retired | WNS | FMax |
|---:|---:|---:|
| 0 | -2.094 | 164.10 MHz |
| 10 | -1.848 | 170.94 MHz |
| 20 | -1.720 | 174.83 MHz |
| 50 | -1.502 | 181.75 MHz |
| 100 | -1.389 | 185.60 MHz |
| 200 | **-1.271** | **189.72 MHz** |

**Retiring two hundred distinct startpoint register families outright recovers
0.823 ns and still lands 10 MHz short of 200 MHz** (which needs WNS >= -1.000,
i.e. +1.094 ns).  The tail is flat: rungs 100-200 average +0.0012 ns each.  Three
independent grounding passes (sections 15, 16 and 17) have now each concluded
that the named family is worth ~0.00 ns, on three different subsystems, and this
ladder explains why: at -2.094 ns the design has no removable spike left, only a
broad route-dominated plateau at 17-22 logic levels and 63-78 % route.

The remaining levers are therefore **not RTL family cuts**.  In descending order
of expected value:

1. **Placement/congestion, not logic.**  Every limiting path in every one of the
   three grounded families is 63-78 % route at moderate logic depth.  Section 16's
   "next" list already proposes a floorplan aimed at `DcachePlugin` +
   `LsEuPlugin` + `IssueQueuePlugin`; this section adds a second, independent
   datapoint for the *decode/FetchAlign* boundary — `spec_size` at `X57Y20`
   inside `pb_decode` driving `predictPending` at `X24Y99` outside it, 4.487 ns
   of pure route.  A same-DCP A/B that either widens the capture filter to pull
   `FetchAlignPlugin`/`IcachePlugin` into the decode box, or splits the box, is
   cheap and is measuring the thing that is actually binding.  (Section 5's
   warning stands: a prior X103 widening regressed, so this must be an exact-DCP
   A/B, not a guess.)
2. **Implementation-strategy sweep** — Vivado `-directive`
   (`ExplorePostRoutePhysOpt`, `AggressiveExplore`), `phys_opt_design` passes,
   higher placer effort.  A route-dominated plateau is exactly the regime where
   these pay, and none has been tried in this campaign.
3. **A real pipeline stage**, justified by an IPC measurement rather than by a
   census — the elastic `ready` chains (this section's `feed.ready`, section 16's
   IQ scoreboard) are the natural cut points, but each costs a cycle somewhere
   and must be paid for with an IPC number.
4. The banked breadth cuts (section 15 step 9's I-cache miss/prefetch pipelining,
   ~1,280 endpoints) as TNS/endpoint relief that may convert into placement gain,
   accepting that neither predicts an FMax number on its own.

### Status after this section

No RTL changed.  `6b246de` remains the head RTL checkpoint at **-2.094 ns /
164.096 MHz**, `FLOORPLAN_MODE decode`, netlist MD5
`d80f6218c5c7dcab94a33a52d64244fa`.  New committed probes:
`synth/probe_specsize.tcl` (family scope, per-endpoint worst slack, isolated
what-if, ladder-to-family), `synth/probe_specsize2.tcl` (200-rung reference
ladder plus the endpoint-group-free control ladder), `synth/probe_specsize3.tcl`
(TNS / failing-endpoint deltas).

## 18. Floorplan A/B: every floorplan variant regressed — but post-route physical optimisation is worth +18.95 MHz (Claude, 2026-08-10)

**Sixteen full post-route runs from the identical `6b246de` post-synthesis
checkpoint.  Result in one line: no floorplan change lands — all seven floorplan
variants regressed, including both of the two boundaries this pass was dispatched
to attack — but iterated post-route physical optimisation takes the branch from
-2.094 ns / 164.096 MHz to -1.463 ns / 183.050 MHz, with better TNS, fewer failing
endpoints, identical flop count and +19 LUTs.  That is +18.95 MHz (+11.5 %) for
zero RTL and effectively zero area, and it is the largest single gain of this
entire campaign, larger than `28ec738`'s +19.08 MHz only in percentage terms but
free of any behavioural risk.  `IMPL_STRATEGY` is now a first-class knob on
`synth/impl_FullCore.tcl` and its default has changed accordingly.**

Sections 15-17 each retired one *logic* family.  This section retires the
*floorplan* and lands the *tool recipe*.

### Step 0: method and controls

All sixteen routes re-place and re-route the identical post-synthesis checkpoint
`synth/archive/6b246de_ftb_framing_retime_decode/fullcore_synth.dcp`
(`REUSE_SYNTH_DCP=1`, generated-netlist MD5
`d80f6218c5c7dcab94a33a52d64244fa`).  **Every run reported the same
post-synthesis WNS `-1.827`** — the control proving the netlist was identical
across the whole comparison, so only the constraint or the recipe differed.  Runs
are isolated in their own directories so three can proceed in parallel without
fighting over `synth/fullcore_*.rpt`.

Three tools are committed with this section:

- `synth/floorplan_ab.sh <runroot> <spec>...` — each `<spec>` is
  `<FLOORPLAN_MODE>[@<IMPL_STRATEGY>]`, launched in parallel from one fixed DCP.
  Roughly 6 GB RSS and 20-30 min each; three at a time is the machine limit.
- `synth/floorplan_ab_report.sh <dir>...` — emits the full gate report shape
  (WNS/FMax, TNS, failing endpoints, WHS/WPWS, area, per-pblock occupancy,
  congestion, top-path source/destination and logic-vs-route split) from either an
  A/B run directory or a `synth/archive/` checkpoint.  It reproduces the pinned
  section-5 baseline row exactly, which is its own validation.
- `synth/probe_floorplan_census.tcl`, `synth/probe_floorplan_geo.tcl`,
  `synth/probe_floorplan_filters.tcl` — read-only placement census, worst-path
  geography, and capture-filter sizing.

`synth/impl_FullCore.tcl` now takes `FLOORPLAN_MODE` as a `+`-separated *set* of
pblock tokens (`decode`, `decode_fe`, `dcache`, `backend`, `frontend`; `both`
remains an alias for `decode+dcache`) and a new `IMPL_STRATEGY`
(`default` | `fanout` | `postroute` | `postroute2` | `postrouteN` | `exploreN` | `explore`).

### Step 1: the placement census — where everything actually is

`synth/probe_floorplan_census.tcl` reports the placed bounding box and centroid of
every plugin.  This is the data a floorplan proposal has to be built from, and it
had not been collected before:

| Plugin | cells | placed bbox | centroid |
|---|---:|---|---|
| IcachePlugin | 17,264 | X1..X50, Y1..Y136 | X17.1 Y61.8 |
| FtbPlugin | 1,076 | X1..X34, Y57..Y135 | X16.4 Y102.0 |
| RasPlugin | 576 | X10..X59, Y51..Y82 | X21.2 Y61.6 |
| GsharePlugin | 830 | X10..X32, Y75..Y103 | X24.0 Y91.7 |
| FetchAlignPlugin | 5,982 | X10..X65, Y20..Y105 | X41.1 Y70.9 |
| DecodeStage | 33,006 | X32..X89, Y0..Y186 | X61.7 Y48.4 |
| RenameStage | 7,427 | X32..X89, Y3..Y159 | X67.0 Y79.6 |
| IssueQueuePlugin | 20,132 | X30..X112, Y6..Y181 | X81.8 Y106.9 |
| RobPlugin | 34,621 | X4..X111, Y0..Y225 | X53.0 Y138.9 |
| LsEuPlugin | 22,198 | X4..X95, Y55..Y237 | X35.5 Y163.3 |
| DcachePlugin | 22,640 | X4..X84, Y139..Y239 | X42.3 Y210.0 |
| DtlbPlugin | 3,109 | X49..X109, Y136..Y237 | X81.5 Y197.7 |
| AluEuPlugin | 12,209 | X29..X112, Y62..Y238 | X103.3 Y147.3 |
| DivEuPlugin | 5,373 | X51..X112, Y59..Y232 | X96.0 Y160.0 |

Device is `SLICE_X0..X112, Y0..Y239` (27,120 slices).  The layout the placer finds
on its own is already coherent: fetch/predict bottom-left, decode/rename/IQ
bottom-right, ROB across the middle, LS cluster top-left, ALU/DIV top-right.
Section 17's boundary datapoint is confirmed —
`_zz_..._fed_payload_specs_0_spec_size_reg[0]` at `SLICE_X57Y20` inside
`pb_decode`, `predictPending_reg` at `X24..X36, Y85..Y99` mostly outside it.

### Step 2: the decisive measurement — the WNS family crosses no pblock at all

`synth/probe_floorplan_geo.tcl` reports, for the worst N unique endpoints, the
placed coordinates of both ends and the plugin pair.  Over the worst **300**
unique endpoints on `6b246de`:

```
PAIR count=300  avg_manhattan=34.5  worst_slack=-2.094  FetchAlignPlugin -> IcachePlugin
```

**All three hundred are the same arc**, and all three hundred share one
startpoint register:

```
FetchAlignPlugin_logic_stalled_reg   SLICE_X26Y99
   -> IcachePlugin_logic_s1PredEntries_{0..3}_reg[*]/CE   X18..X26, Y56..Y96
   Manhattan span 24..48 CLBs
```

`report_design_analysis -timing`, path #1:

```
Path delay 5.990 ns = logic 2.019 (33.7%) + route 3.971 (66.3%)
Logic levels 20, routes 19,  PBlocks crossed: 0,  High Fanout: 931
  FDCE/C -(46)- LUT2 -(4)- LUT6 -(1)- LUT6 -(143)- LUT6 -(1)- LUT3 -(176)-
  LUT2 -(164)- LUT6 -(1)- LUT6 -(1)- LUT6 -(1)- CARRY8 -(1)- LUT5 -(2)-
  LUT2 -(23)- LUT6 -(3)- LUT6 -(11)- LUT6 -(1)- CARRY8 -(1)- LUT2 -(2)-
  LUT6 -(8)- LUT5 -(113)- LUT6 -(931)- FDRE/CE
```

Three facts follow, and together they are why this pass came back negative on
floorplanning:

1. **`PBlocks crossed: 0`.**  The path lives entirely in the unfloorplanned left
   strip; `pb_decode` starts at X36 and the path never leaves X18..X26.  No
   adjustment of any pblock boundary can recover it.  Section 17's `spec_size` ->
   `predictPending` crossing is real and does cost 4.487 ns of route — but that
   path is at **-1.776 ns**, 0.318 ns *behind* WNS, and section 17 already
   measured retiring its whole endpoint group at +0.004 ns.  The dispatch premise
   ("4.487 ns of pure route crosses this single pblock boundary") is true and
   simultaneously not the limiter.
2. **The span is short and the delay is still 66 % route.**  24-48 CLBs of
   Manhattan distance is a quarter of the die, not a die-length haul.  The route
   delay here is *load*, not distance.
3. **The terminal net drives 931 flip-flops**, with four more nets at fan-out
   113-176 upstream.  The ~930 `s1PredEntries_{0..3}` flops sit at about
   2.3 FF/slice over X18..X26 x Y56..Y96 — sparse because they are BRAM-read
   capture registers interleaved with the rest of the fetch datapath.  One LUT6
   output reaching all of them is the last stretch of the path.

This is section 15 step 6 item 2 seen physically: the S1 capture *enable* is
`cmd.fire`-gated (hence behind the ITLB and tag compare) while the S1 *data* is a
raw all-ways BRAM read with no hit dependency.  The 931-load enable broadcast is
that gating.

### Step 3: `pb_dcache`'s over-capture, root-caused — and the `IS_PRIMITIVE` trap

Section 5 recorded that the legacy D-cache pblock "captured an unrelated ROB carry
chain through flattened-name matching".  The exact mechanism, from
`synth/probe_floorplan_filters.tcl`:

```
LsEuPlugin_logic_sq/RobPlugin_logic_branchTrainMem_reg_0_63_35_41_i_1   CARRY8
LsEuPlugin_logic_sq/RobPlugin_logic_branchTakenStore_60_i_3             LUT6
LsEuPlugin_logic_sq/IssueQueuePlugin_logic_aluSlowIntBusy[15]_i_5       LUT6
```

It is **not flattening — it is a submodule instance path prefix**.
`LsEuPlugin_logic_sq` is the StoreQueue instance; Vivado absorbed ROB
branch-train and IQ scoreboard logic into it, so `NAME =~ *LsEuPlugin_logic*`
matches those cells on the *instance* segment of the path.  Measured on the exact
`6b246de` netlist:

| Filter | cells | LUT | LUT occupancy at X36Y110:X87Y214 |
|---|---:|---:|---:|
| legacy 3-way OR (`pb_dcache`) | 45,561 | 32,298 | 74.3 % |
| foreign leaf names excluded | 36,431 | 23,545 | 54.3 % |

**27 % of the legacy box's LUT demand was foreign**, including a ROB carry chain
that a pblock will happily tear away from the rest of its chain.

The repair has a trap worth recording, because the obvious fix silently does not
work.  Excluding the foreign *leaf* names is not enough: `get_cells -hier` also
returns the **hierarchical instances themselves** (`LsEuPlugin_logic_sq`,
`DtlbPlugin_logic_{tlb,umq,walker}`, and on the frontend side
`FetchAlignPlugin_logic_ibuf`), whose own names carry no foreign substring — and
adding one hierarchical object re-constrains every leaf beneath it regardless of
that leaf's name:

| Filter | pblock members reported |
|---|---:|
| foreign names excluded, no `IS_PRIMITIVE` | 24,214 (~9.4k leaves fold into 4 parents, and come along) |
| foreign names excluded, `+ IS_PRIMITIVE` | 33,613 |

The LS-owned leaves inside those instances are still captured either way — they
are named `LsEuPlugin_logic_sq/<...>`, so they match the prefix and are
primitives.  Only the absorbed foreign leaves are dropped.  Both new XDCs carry
the exclusions *and* `IS_PRIMITIVE`, and the first A/B round was re-run after this
was found.

**The repair did not help.**  See row 8 below: the *clean* backend capture routes
at -2.595 ns, **worse** than the contaminated one at -2.328 ns.  So the
over-capture was a real defect but it was **not** why `pb_dcache` hurt; the region
itself is harmful.  Section 5's explanation for the `dcache` mode should be
corrected accordingly.

### Step 4: the A/B table

All from the same `6b246de` synthesis checkpoint, 4.000 ns constraint.  Every run
is hold-clean and pulse-width-clean (WHS +0.019 to +0.036 ns, WPWS +1.458 ns,
zero THS/TPWS failures) and shows no congestion window above level 5.

| # | Variant | WNS / FMax | TNS | Failing endpoints | Routed LUT / FF | vs. incumbent |
|---|---|---:|---:|---:|---:|---:|
| 0 | **`decode` incumbent (§15 step 7)** | **-2.094 / 164.096** | -21,068.689 | 32,729 | 110,662 / 50,389 | — |
| 1 | `none` (§15 step 7) | -2.187 / 161.629 | -29,536.104 | 43,701 | 110,774 / 50,384 | -2.47 MHz |
| 2 | `dcache` (§15 step 7) | -2.665 / 150.038 | -43,360.996 | 61,293 | 110,771 / 50,401 | -14.06 MHz |
| 3 | `both` (§15 step 7) | -2.669 / 149.948 | -38,648.195 | 47,702 | 110,693 / 50,436 | -14.15 MHz |
| 4 | `decode_fe` (boundary 1, literal) | -2.757 / 147.995 | -33,963.488 | 42,483 | 110,678 / 50,389 | **-16.10 MHz** |
| 5 | `decode+frontend` v1 (no `IS_PRIMITIVE`) | -2.472 / 154.512 | -33,089.121 | 49,778 | 110,620 / 50,416 | -9.58 MHz |
| 6 | `decode+backend` v1 (no `IS_PRIMITIVE`) | -2.328 / 158.028 | -26,649.537 | 38,588 | 110,611 / 50,421 | -6.07 MHz |
| 7 | `decode+frontend` v2 (repaired capture) | -2.149 / 162.628 | -31,428.354 | 48,595 | 110,667 / 50,373 | -1.47 MHz |
| 8 | `decode+backend` v2 (repaired capture) | -2.595 / 151.630 | -29,185.477 | 43,932 | 110,612 / 50,419 | -12.47 MHz |
| 9 | `frontend` alone (no `pb_decode`) | -2.292 / 158.932 | -29,825.727 | 41,833 | 110,729 / 50,389 | -5.16 MHz |
| 10 | `decode` @ `fanout` | -2.389 / 156.519 | -23,251.814 | 33,055 | 110,662 / 50,391 | -7.58 MHz |
| 11 | **`decode` @ `postroute`** | **-1.623 / 177.841** | **-17,909.066** | **32,254** | **110,663 / 50,389** | **+13.75 MHz** |
| 12 | `decode` @ `explore` | -1.642 / 177.242 | **-15,082.161** | **28,795** | 110,810 / 50,422 | +13.15 MHz |
| 13 | `none` @ `postroute` | -2.010 / 166.389 | -29,241.080 | 43,717 | 110,782 / 50,384 | +2.29 MHz |
| 14 | `decode` @ `postroute2` | -1.552 / 180.115 | -17,916.010 | 32,425 | 110,675 / 50,389 | +16.02 MHz |
| 15 | `decode` @ `postroute` (repeat) | -1.623 / 177.841 | -17,909.066 | 32,254 | 110,663 / 50,389 | +13.75 MHz |
| 16 | **`decode` @ `postrouteN`, 6 rounds** | **-1.463 / 183.050** | **-17,472.854** | 32,409 | **110,681 / 50,389** | **+18.95 MHz** |
| 17 | `decode` @ `exploreN`, 6 rounds | -1.642 / 177.242 | -15,516.527 | **30,048** | 110,814 / 50,422 | +13.15 MHz |

Rows 4-9 are the assigned floorplan experiments.  Rows 10-15 are the
implementation-strategy sweep that the step-2 measurement redirected the pass
into.

#### What each floorplan variant taught

- **Row 4, `decode_fe` — the literal boundary-1 fix is the *worst* variant tried
  (-16.10 MHz).**  Annexing `FetchAlignPlugin` + `RasPlugin` into `pb_decode`
  does close the `spec_size` -> `predictPending` crossing, and it costs 16 MHz,
  because it drags FetchAlign right into X36..X87 and away from `IcachePlugin`
  (centroid X17Y62), which owns every one of the worst 300 endpoints.  The trade
  was predicted in the XDC's own comment before the run and is now measured.
- **Rows 5/7, the frontend box.**  A loose region (X0Y20:X35Y135, 64-69 % LUT
  occupancy — comparable to `pb_decode`'s 67.91 %) enclosing exactly the cluster
  that owns the WNS family still loses.  Repairing the capture recovers most of
  the v1 loss (-2.472 -> -2.149) and it is the *closest* floorplan variant to the
  incumbent, but its TNS is 49 % worse (-31,428 vs -21,069) with 48,595 failing
  endpoints vs 32,729.  The WNS *family changed* under it — from
  `stalled -> s1PredEntries` to `GsharePlugin pht_port2 -> Icache lineReg` —
  i.e. the box moved the peak without lowering the plateau.
- **Row 9, `frontend` alone.**  Dropping `pb_decode` and keeping only the new
  frontend box is worse than either.  `pb_decode`'s remaining value is real even
  though section 15 measured its WNS edge over `none` at only 0.093 ns; its
  TNS/endpoint edge is what is left of it.
- **Rows 6/8, the backend box.**  The repaired capture is *worse* than the
  contaminated one.  Two independent geometries (legacy X36Y110:X87Y214 and the
  population-centred X14Y132:X72Y239) and two capture filters all land between
  -2.328 and -2.665.  The LS-cluster region is simply a bad place to put a
  constraint on this netlist, and section 16 already explained why it cannot pay:
  the D-cache/IQ family is worth 0.000 ns.

**Combined verdict on floorplanning: seven variants, seven regressions, spanning
both dispatched boundaries, two geometries per region, and two capture filters.
`FLOORPLAN_MODE=decode` remains correct and unchanged.**  The pblock lever is
exhausted on this netlist in the same sense that the family-cut lever is.

#### What the implementation-strategy sweep taught

- **Row 10, `fanout` — the 931-load hypothesis, tested and refuted as a lever.**
  `phys_opt_design -directive AggressiveFanoutOpt` *did* do its job: the WNS
  startpoint moved off `FetchAlignPlugin_logic_stalled_reg` to
  `FtbPlugin_logic_rspPayload_brWordOff_reg[1]` — which is **exactly rung 1 of
  section 15's what-if ladder**, the family the ladder predicted would be next.
  And FMax still fell 7.58 MHz.  That is the ladder's prediction confirmed from
  the opposite direction: retiring the `stalled` broadcast is worth ~0.02 ns and
  the replication cost more elsewhere than it bought.  The 931-load net is the
  *mechanism* of the top path, not a *lever*.
- **Rows 11/12 — post-route physical optimisation is the lever.**  Both recipes
  that add a `phys_opt_design` pass **after** `route_design` gain ~13.5 MHz.
  `postroute` (today's flow + one post-route phys-opt + `route_design
  -tns_cleanup`) has the better WNS; `explore` (`place_design -directive
  ExtraTimingOpt`, `phys_opt_design -directive AggressiveExplore`,
  `route_design -directive Explore`, then a post-route round of both) has the
  better TNS (-15,082) and the fewest failing endpoints (28,795) at a cost of
  +148 LUT.  `postroute` is also the cheaper of the two in wall time.
  Both keep the WNS family in the frontend (`Gshare pht_port3 -> Icache lineReg`
  and `Ftb rspPayload_brWordOff -> Icache lineReg`), still at 66-70 % route.

`decode@postroute` is **strictly dominant** over the incumbent: better WNS
(-1.623 vs -2.094), better TNS (-17,909 vs -21,069), fewer failing endpoints
(32,254 vs 32,729), identical LUT/FF (110,663/50,389 vs 110,662/50,389),
identical pblock occupancy (67.91 %), identical BRAM/DSP, hold- and
pulse-width-clean, no congestion window above level 5.  There is no column on
which it loses.

#### The convergence curve, and what landed

Row 16 is one run that prints WNS after every post-route round, so the whole curve
comes from a single controlled experiment:

| Post-route rounds | WNS | FMax | delta |
|---:|---:|---:|---:|
| 0 (= the historical `default` flow) | -2.094 | 164.096 MHz | — |
| 1 | -1.623 | 177.841 MHz | +0.471 ns |
| 2 | -1.552 | 180.115 MHz | +0.071 ns |
| 3 | **-1.472** | **182.749 MHz** | +0.080 ns |
| 4 | -1.464 | 183.016 MHz | +0.008 ns |
| 5 | -1.463 | 183.050 MHz | +0.001 ns |
| 6 | -1.463 | 183.050 MHz | +0.000 ns |

Round 0 reproduced `-2.094` exactly, and `postroute` was independently reproduced
at `-1.623` three separate times (rows 11, 15, and round 1 of row 16), and
`postroute2` at `-1.552` twice (row 14, and round 2 of row 16).  The recipe is
deterministic.

The `exploreN` curve is worse and flatter: -1.769 / 173.340 MHz at round 0 (so the
`ExtraTimingOpt` / `AggressiveExplore` / `route -directive Explore` combination is
worth +9.24 MHz on its own, before any post-route pass), then -1.642 at round 1
and no further improvement through round 6.  It buys the best TNS/endpoint counts
in the table (-15,517 / 30,048) but loses 5.8 MHz of WNS to plain iterated
`postroute`, and costs +133 LUT.

**Landed: `synth/impl_FullCore.tcl`'s `IMPL_STRATEGY` now defaults to
`postrouteN` with `POSTROUTE_ROUNDS=3`, i.e. -1.472 ns / 182.749 MHz** — 0.622 ns
of the 0.631 ns the plateau offers, at roughly half its wall time (the 6-round run
took 45 minutes against about 20 for the old flow).  `POSTROUTE_ROUNDS=1` is the
fast-gate option and still carries +13.75 MHz.  **`IMPL_STRATEGY=default`
reproduces the historical flow verbatim**, so every physical number published in
this document before this section regenerates unchanged; any comparison against a
pre-section-18 row must use it.

`FLOORPLAN_MODE` keeps its `decode` default, and row 13 is why: under the new
recipe, `none` gives -2.010 against `decode`'s -1.623.  **The decode pblock's edge
did not merely survive the recipe change, it grew from 0.093 ns / 2.47 MHz to
0.387 ns / 11.45 MHz.**  Section 15 step 7's warning that the pblock was becoming
marginal is therefore withdrawn: post-route physical optimisation has more to work
with when the placement is floorplanned.

Row 16's archived evidence is at `synth/archive/6b246de_postrouteN6_decode/`
(routed DCP, MD5 sidecar, Vivado log, timing/utilization/slack-matrix, congestion,
fan-out, path-analysis and pblock reports).  Its top path is still
`stalled -> IcachePlugin lineReg[418]/D` at 5.445 ns / 68.7 % route, but the
**second**-worst is now `DcachePlugin stS2Payload_paddr[5] -> IssueQueuePlugin
lines_7_ways_0_triggers[9]` — the D-cache/IQ family from section 16 has surfaced
again at the new slack level, which is a live pointer for the next pass.

### Step 5: the landed configuration, gated end to end

The default flip was then run as a real gate with **no environment overrides at
all**, so what is measured is literally what `synth/impl_FullCore.tcl` now does:

```
FLOORPLAN_MODE decode
IMPL_STRATEGY postrouteN            (POSTROUTE_ROUNDS defaults to 3)
POSTROUTE_ROUND 0 WNS -2.094
POSTROUTE_ROUND 1 WNS -1.623
POSTROUTE_ROUND 2 WNS -1.552
POSTROUTE_ROUND 3 WNS -1.472
POSTROUTE_FULLCORE_WNS_NS -1.472    ACHIEVED_FMAX_MHZ 182.749
```

| Metric | incumbent (`default`) | **landed (`postrouteN`, 3 rounds)** |
|---|---:|---:|
| WNS / FMax | -2.094 / 164.096 MHz | **-1.472 / 182.749 MHz** |
| TNS | -21,068.689 | **-17,499.508** |
| TNS failing endpoints | 32,729 / 171,230 | **32,408 / 171,230** |
| WHS / THS failing | +0.020 / 0 | +0.020 / 0 |
| WPWS / TPWS failing | +1.458 / 0 | +1.458 / 0 |
| CLB LUTs | 110,662 (51.01 %) | 110,679 (51.01 %) |
| CLB Registers | 50,389 (11.61 %) | 50,389 (11.61 %) |
| CARRY8 / BRAM / DSP | 632 / 26 / 4 | 632 / 26 / 4 |
| `pb_decode` LUT occupancy | 29,662 / 43,680 (67.91 %) | 29,662 / 43,680 (67.91 %) |
| Congestion | none above level 5 | none above level 5 |

**+17 LUTs, zero flops, zero BRAM/DSP, identical pblock occupancy, +18.653 MHz.**
Archived at `synth/archive/6b246de_default_postrouteN3_decode/`.  A paired control
run with `IMPL_STRATEGY=default` on the same script reproduced **-2.094 exactly**,
confirming that every pre-section-18 number regenerates unchanged.

Two things about this gate are worth carrying forward.

**The WNS family has moved off the frontend.**  The worst path is now

```
DcachePlugin_logic_stS2Payload_paddr_reg[5]/C
   -> IssueQueuePlugin_logic_sbNzvc_busy_reg[8]/D
   5.454 ns = logic 1.733 (31.8%) + route 3.721 (68.2%)
```

with the frontend `stalled -> IcachePlugin lineReg[418]/D` arc now *second*.  That
is **section 16's D-cache/IQ family**, which section 16 measured at **0.000 ns** —
but it measured it at the old slack level, on the old placement.  It is now the
binding family.  Section 16's two specified fixes (register the IQ -> LS-EU
issue-port ready as a 2-deep skid; retime `earlyProbeSetWriteVec` out of
`earlyProbeHit`) were shelved as "worth 0.000 ns"; **that verdict is now stale and
must be re-measured before either is dismissed again.**

**A harness trap, fixed.**  `synth/floorplan_ab.sh` originally exported a
hard-coded `IMPL_STRATEGY=default` for any spec without an `@<strategy>` suffix,
which silently overrode the script's own default.  The first confirmation run
printed `IMPL_STRATEGY default` when it should have printed `postrouteN`, which is
how it was caught.  The runner now exports the variable only when the spec names a
strategy.  Any future harness that wraps `impl_FullCore.tcl` must not pre-set
`IMPL_STRATEGY` or `FLOORPLAN_MODE` to a literal default.

### Step 6: what this means for 200 MHz

Section 17 step 6 put the ceiling of family-cutting at 189.72 MHz after retiring
*two hundred* startpoint families.  This section adds two facts to that picture:

- **The floorplan lever is spent** (seven regressions), and
- **the tool-recipe lever was worth +18.65 MHz on its own** (+18.95 at the
  plateau) and had never been tried.

At -1.472 ns (the landed 3-round default) the branch is **182.749 MHz** and needs
+0.472 ns more to reach the 200 MHz deployment floor; the 6-round plateau is
-1.463 ns / 183.050 MHz.  For calibration, the section-17 ladder applied to the
*old* placement said 200 families were worth 0.823 ns; that ladder must now be
re-run against the `postroute` checkpoint, because a static what-if ladder is
computed on a fixed placement and this placement is different.  **Re-running
`synth/probe_slack_ladder.tcl` against the new routed DCP is the first thing the
next session should do** — it is 40 seconds and it re-scopes every remaining
candidate.

Remaining levers, in descending expected value:

1. **Finish the implementation-strategy sweep.**  Only three recipes have been
   tried.  Untried and cheap: `-directive ExplorePostRoutePhysOpt`,
   `route_design -directive AggressiveExplore`, `phys_opt_design
   -directive ExploreWithAggressiveHoldFix`, multiple `-tns_cleanup` rounds
   (rows 14 and 16 probe that axis and it plateaus at 3-4 rounds), and non-default
   `place_design` directives paired with the winning post-route pass.  This lever
   has produced the only positive result in four consecutive sessions.
2. **Re-price section 16's D-cache/IQ fixes, which are no longer 0.000 ns.**  The
   landed gate's WNS path is `DcachePlugin stS2Payload_paddr[5] -> IssueQueuePlugin
   sbNzvc_busy[8]` — that family is now *binding*, where section 16 measured it as
   0.000 ns behind a frontend limiter on the old placement.  Section 16 step 5's
   two specified fixes should be re-measured against the new checkpoint before
   being dismissed again.
3. **A real pipeline stage**, justified by an IPC measurement rather than a
   census — section 16 step 5 fix 1 (register the IQ -> LS-EU issue-port ready)
   and section 17 step 5 (2-entry skid on the decode `raw` stage) are both
   specified and both cost a cycle somewhere.  The static ladder cannot price
   them, because it cannot model the placement freedom a genuine cone deletion
   creates; both of this campaign's best RTL cuts beat their static prediction
   for exactly that reason.
4. **Re-synthesis under the new recipe.**  Every number in this document comes
   from one frozen `6b246de` synthesis checkpoint.  A fresh `synth_design` with
   the post-route pass in the loop has never been measured.
5. Banked TNS/endpoint-breadth cuts (section 15 step 9), now with a concrete
   target: `explore` shows that 28,795 failing endpoints is reachable by tooling
   alone, so RTL breadth cuts should be judged against that, not against 32,729.

**What should *not* be tried again**: a new pblock geometry or capture filter.
Seven variants across both evidenced boundaries, two geometries per region and
two capture filters all regressed, and the WNS path crosses zero pblock
boundaries.

### Functional gates

`make SBT=~/sbt/bin/sbt test-fast` — **149/149 tests, 157 suites, 0 failed**, on
the worktree with all of this section's changes present.  No RTL changed in this
section; the only tracked non-documentation changes are `synth/*.xdc`,
`synth/*.tcl`, `synth/*.sh` and the reservation file.

### Status after this section

**No RTL changed.**  `6b246de` remains the head RTL checkpoint.  What changed is
the physical-implementation recipe:

- **New physical baseline: `6b246de` + `FLOORPLAN_MODE=decode` +
  `IMPL_STRATEGY=postrouteN` (`POSTROUTE_ROUNDS=3`) = -1.472 ns / 182.749 MHz**,
  from the same netlist MD5 `d80f6218c5c7dcab94a33a52d64244fa`.  The 6-round
  plateau is -1.463 ns / 183.050 MHz and is the archived checkpoint.
- The old -2.094 ns / 164.096 MHz row is still correct **for
  `IMPL_STRATEGY=default`**, and section 5's table should be read that way from
  now on.
- `FLOORPLAN_MODE=decode` unchanged and re-validated on today's netlist under both
  recipes.  No pblock geometry or capture filter changed on the default path;
  `floorplan_frontend.xdc`, `floorplan_backend.xdc` and `floorplan_decode_fe.xdc`
  are committed as measured-negative diagnostics with their evidence in-file.
- `make SBT=~/sbt/bin/sbt test-fast`: 149/149, 157 suites, 0 failed.
- Distance to the 200 MHz deployment floor: **0.472 ns** (was 1.094 ns at the
  start of this section).

## 19. Re-priced against the new placement: BOTH section-16 fixes are still worth 0.000 ns — the postroute pass flattened the slack distribution into a 13-family, 0.091 ns plateau (Claude, 2026-08-10)

**Grounding only.  No RTL changed, and none should be written for this family.**
Section 18 step 5 recorded that the landed `IMPL_STRATEGY=postrouteN` gate's WNS
path had become `DcachePlugin stS2Payload_paddr[5] -> IssueQueuePlugin
sbNzvc_busy[8]`, i.e. section 16's D-cache/IQ family, and correctly flagged
section 16's "worth 0.000 ns" verdict as **stale** and requiring re-measurement
on the new placement.  It has now been re-measured, with a valid handle for each
of the two shelved fixes, against the actual landed routed checkpoint.

**The verdict survives, and it is stronger than before: Fix A is worth 0.000 ns
of WNS, Fix B is worth 0.000 ns of WNS, and both together are worth 0.000 ns of
WNS.**  The family is nominally the WNS path, but the frontend `stalled ->
IcachePlugin lineReg` arc is tied with it to three decimal places, and behind
the two of them lies a plateau of thirteen families spanning 0.091 ns.  Neither
fix should be built.  What *did* change is the breadth number: Fix B is now
worth 2.16 % of TNS and 234 failing endpoints, roughly eight times section 16's
0.27 % / six endpoints.

Evidence: `synth/probe_dcache_iq2/` and `synth/probe_dcache_iq3/` (scripts
`synth/probe_dcache_iq2.tcl`, `synth/probe_dcache_iq3.tcl`), read-only against
`synth/archive/6b246de_default_postrouteN3_decode/fullcore_routed.dcp`, netlist
MD5 `d80f6218c5c7dcab94a33a52d64244fa`.

### Step 0: the baseline is exactly reproducible, and reused rather than re-run

HEAD is `a2ed18b`; it differs from the gate commit `2b7588d` **only in
documentation**, and `git diff 6b246de..HEAD -- src/` is empty, so the RTL is
unchanged since the head RTL checkpoint.  The archived
`6b246de_default_postrouteN3_decode/` run *is* the landed default gated end to
end with no environment overrides, so it is the current baseline by
construction rather than by assumption.

Both probe sessions reproduced it **exactly** before any what-if — WNS -1.472,
TNS -17,499.508, 32,408 failing endpoints, worst path `stS2Payload_paddr[5] ->
sbNzvc_busy[8]` — which is the control that validates the method.

| Baseline (`6b246de` + `FLOORPLAN_MODE=decode` + `IMPL_STRATEGY=postrouteN`, 3 rounds) | |
|---|---:|
| WNS / FMax | **-1.472 ns / 182.749 MHz** |
| TNS | -17,499.508 |
| Failing endpoints | 32,408 / 171,230 |
| Distance to the 200 MHz floor (WNS >= -1.000) | **0.472 ns** |

### Step 1: a harness lesson — the first Fix A model measured nothing, and said so

Fix A was first modelled as `set_false_path -through` the nets matching
`*IssueQueuePlugin_logic_selPorts_3_m2sPipe_ready*`, which is the correct RTL
name (`IssueQueuePlugin.scala` drives `selPorts_3_ready` from
`selPorts_3_m2sPipe_ready`, and the LS EU drives that from `issuePort.ready`).
**That glob matched zero nets** — the wire does not survive synthesis under that
name — and a zero-match `set_false_path` is a silent no-op that would have been
reported as a perfect 0.000 ns result.

It was not, because `probe_dcache_iq2.tcl` prints the matched object count for
every cut and `probe_dcache_iq3.tcl` *errors out* on a zero match.  The run
printed `PROBE_CUT fixA nets: 0` and the scenario duly came back
bit-identical to the baseline in all four metrics, which is the signature of a
no-op rather than of a measurement.

**Rule for every future what-if probe in this campaign: print, and preferably
assert on, the number of objects each `set_false_path` actually constrained.  A
what-if that matches nothing is indistinguishable from a what-if that is worth
nothing.**  Section 18 step 5 recorded the same class of trap on the environment
side (`floorplan_ab.sh` silently forcing `IMPL_STRATEGY=default`); this is its
constraint-side twin.

Fix A was then re-measured with a handle that cannot silently miss: an
**upper bound** that false-paths every D-cache/LS-EU cell to every
IssueQueuePlugin cell — 42,428 startpoint cells to 20,133 endpoint cells.  That
deliberately over-cuts.  A 2-deep skid on the issue-port ready removes only the
ready arc; this removes the ready arc *and* every other LS-to-IQ combinational
arc, wakeups included.  Whatever the upper bound measures, the real Fix A is
worth no more.

### Step 2: the measurement

| Scenario | WNS | ΔWNS | TNS | ΔTNS | Failing endpoints | Δ | Worst path after |
|---|---:|---:|---:|---:|---:|---:|---|
| baseline (reproduced) | -1.472 | — | -17,499.508 | — | 32,408 | — | `stS2Payload_paddr[5] -> sbNzvc_busy[8]` |
| **Fix A** (upper bound, 42,428 -> 20,133 cells) | **-1.472** | **+0.000** | -17,374.262 | +125.2 (0.72 %) | 32,363 | -45 | `stalled -> Icache lineReg[418]` |
| **Fix B** (4 `earlyProbeSetWriteVec` nets) | **-1.472** | **+0.000** | -17,120.842 | +378.7 (2.16 %) | 32,174 | -234 | `stalled -> Icache lineReg[418]` |
| Fix A + Fix B | -1.472 | +0.000 | -17,079.137 | +420.4 (2.40 %) | 32,129 | -279 | `stalled -> Icache lineReg[418]` |
| Fix A + Fix B + **the entire frontend cone** | **-1.460** | **+0.012** | -16,208.058 | +1,291.5 (7.38 %) | 31,894 | -514 | `ftqHead[0] -> p0LiveReg_lenWords[0]` |

Both fixes, and both together, recover **0.000 ns of WNS**.  The reason is a
dead tie, and the archived path-analysis report shows it directly: path #1 is
`stS2Payload_paddr[5] -> sbNzvc_busy[8]` at -1.472 and path #2 is
`FetchAlignPlugin stalled -> IcachePlugin lineReg[418]` at **-1.472** — the same
number to three decimals, from a completely unrelated subsystem.  Deleting
either one exposes the other at the identical slack.

The endpoint-side census makes the size of the trapped headroom precise.  Of the
worst paths into the 66 `sbNzvc_busy` / `sbInt_busy` scoreboard cells:

```
paths  worstSlack  startpoint family
   61    -1.472    DcachePlugin_logic_stS2Payload_paddr_reg
    2    -1.378    IssueQueuePlugin_logic_lines_0_ways_1_sel_reg
    3    -1.208    RobPlugin_logic_doFlushReg_reg
```

**The D-cache/IQ family's own internal headroom is 0.094 ns** (from -1.472 to
the next family at -1.378), and **the design can realise 0.000 ns of it**,
because the frontend tie caps the gain at zero.  That distinction — a family
with real local headroom that is nonetheless worth nothing globally — is the
sharpest version of the census-versus-slack lesson this campaign has produced.

### Step 3: the ladder — a 13-family, 0.091 ns plateau

With the whole D-cache/IQ family retired, twelve further consecutive family
retirements:

```
rung   WNS      delta   worst startpoint -> endpoint
   0  -1.472   +0.000   FetchAlign stalled            -> Icache lineReg[418]
   1  -1.460   +0.012   FetchAlign ftqHead[0]         -> FetchAlign p0LiveReg_lenWords[0]
   2  -1.451   +0.009   DecodeStage fed packets_0_words_0[4] -> FetchAlign predictPending
   3  -1.447   +0.004   Dcache tagMem_3/CLKARDCLK     -> Dcache s0Payload_lineData[103]/CE
   4  -1.436   +0.011   FetchAlign quiesce            -> Icache lineReg[418]
   5  -1.432   +0.004   Gshare pht_port3[1]           -> Icache lineReg[418]
   6  -1.423   +0.009   Ftb rspPayload_brType[0]      -> Icache lineReg[418]
   7  -1.410   +0.013   Gshare pht_port4[1]           -> Icache lineReg[418]
   8  -1.406   +0.004   Ftb rspPayload_brWordOff[0]   -> Icache lineReg[418]
   9  -1.389   +0.017   FetchAlign decodePc[3]_rep    -> FetchAlign predictPending
  10  -1.389   +0.000   Dcache tagMem_1/CLKARDCLK     -> Dcache valids_0_30
  11  -1.388   +0.001   FetchAlign targetHoldValid    -> Icache lineReg[418]
  12  -1.381   +0.007   FetchAlign decodePc[0]        -> FetchAlign predictPending
```

**Thirteen consecutive families span 0.091 ns.**  Compare this against the same
measurement on the *old* placement (section 16 step 2): there, seventeen
families spanned 0.481 ns.  The post-route physical-optimisation pass did not
merely lower WNS by 0.622 ns — **it flattened the slack distribution**, which is
exactly what an optimiser that has already harvested every cheap path should do.
Every remaining path is now within ~6 % of the worst one.

That has a direct and unwelcome consequence for RTL work: **on this placement,
family cutting is worth roughly a fifth of what it was worth before, per
family.**  The lever did not merely get tired; the tool consumed it.

### Step 4: the absolute ceiling of every candidate on file

The last row of the step-2 table is the number that settles the question.  It
applies, simultaneously:

- Fix A at its over-cut upper bound (every LS/D-cache cell severed from every IQ cell),
- Fix B (every `earlyProbeSetWriteVec` net severed), and
- the entire frontend demand cone (`applyNow` nets plus the `stalled` register),
  which section 15 measured as the campaign's single richest region.

Together they move WNS from -1.472 to **-1.460**: **0.012 ns, 182.749 ->
183.150 MHz.**  The 200 MHz floor needs 0.472 ns.  **Every RTL candidate this
campaign has on file, applied at once and modelled generously, delivers 2.5 % of
the remaining requirement.**

### Step 5: Fix B's correctness risk, re-verified rather than inherited

Section 16 step 5 flagged Fix B as "not a pure retime" — the same-cycle
write-versus-consume case would have to be re-proved or conservatively blocked.
That claim was checked against the routed netlist rather than carried forward,
because the task was specifically to re-examine whether the calculus had
changed.

It has not, and the routed path confirms the mechanism literally.  The measured
WNS path runs

```
DcachePlugin stS2Payload_paddr[5]/C
  -> DcachePlugin_logic_refillWriteHold2          (stS2Valid && stS2Set === missSet,  :804)
  -> DcachePlugin_logic_wrTagEn_3   fo=164        (a LIVE array write enable)
  -> DcachePlugin_logic_earlyProbeSetWriteVec_3   (:418, the write-port set compare)
  -> ... loadProbe.ready -> LsEu normalReqArm -> tReady -> s1Ready -> issuePort.ready
  -> IssueQueuePlugin sbNzvc_busy[8]/D
```

`wrEn` / `wrTagEn` are declared `Vec.fill(ways)(False)` (`DcachePlugin.scala`
:112-115) — pure combinational defaults driven by the refill FSM and the store-S3
arm in the same cycle they take effect on the memory write port.  So
`earlyProbeSetWriteVec` compares against the **live** write port, in the cycle of
the write, by construction.  A registered stale-invalidate raises its flag at the
end of that cycle and is visible only at N+1, whereas `loadCmdPort.fire` consumes
the snapshot at N.  The hole is real, it is exactly one cycle wide, and it is
precisely the case the live compare exists to catch: a load consuming a
pre-store snapshot of a set that a drained (already-retired) store just wrote,
which the store queue can no longer forward for.

Section 16 priced that risk as "a genuine correctness invariant at risk for
0.27 % of TNS".  The correct restatement on the new placement is **"for 0.000 ns
of WNS and 2.16 % of TNS"**.  The breadth number improved by 8x; the WNS number
is still zero, and zero is the number that gates the 200 MHz goal.  **The risk
calculus did not improve — the thing being bought is still not FMax.**

Fix B stays on file exactly where section 16 left it: a candidate *if* the design
ever becomes IPC-limited by `loadProbe.ready` back-pressure, justified on
throughput grounds, with the same-cycle case proved or conservatively blocked
first.  It is not a timing fix.

### Step 6: what this means for 200 MHz

Four independent measurements now agree, and the newest is the most decisive:

- section 15: the frontend ladder caps at 0.294 ns;
- section 15 step 9: freeing every I-cache miss/prefetch endpoint buys 0.000 ns;
- section 16: the D-cache/IQ family buys 0.000 ns behind a frontend limiter;
- **this section: the same family buys 0.000 ns while nominally *being* the
  limiter, and every candidate on file combined buys 0.012 ns.**

The design is placement- and congestion-bound, and the post-route pass has now
flattened what was left.  **No RTL family cut reaches 200 MHz on this
placement**, and the honest reading is that no combination of them does either.

What remains, in descending expected value, is unchanged from section 18 step 6
except that lever 2 is now closed:

1. **The implementation-strategy sweep** — the only lever that has produced a
   positive result in five consecutive sessions.  Continued in step 7 below.
2. ~~Re-price section 16's D-cache/IQ fixes~~ — **closed by this section, 0.000 ns.**
3. **A real pipeline stage**, justified by an IPC measurement rather than by a
   static ladder.  Note carefully that this section does **not** refute that
   lever: the ladder cannot model the placement freedom a genuine cone deletion
   creates, and both of this campaign's best RTL cuts beat their static
   prediction.  What this section refutes is the *ready-chain* pipeline stage
   (Fix A) as a **timing** proposal specifically — its over-cut upper bound is
   0.000 ns, which a placement argument cannot rescue, because the tie is with a
   different subsystem entirely.
4. **Re-synthesis under the new recipe.**  Every physical number in this document
   comes from one frozen `6b246de` synthesis checkpoint.  A fresh `synth_design`
   with the post-route pass in the loop has still never been measured, and it is
   the last untried *cheap* structural change.
5. Banked TNS/endpoint-breadth cuts, judged against `explore`'s 28,795 failing
   endpoints rather than against 32,408.

### Step 7: the recensus — the limiter is not a startpoint family, it is the parallel-VIPT amendment's own named risk cone

Task item 4 asks what the recensus shows as the next-most-binding family if the
two shelved fixes price out.  The answer is that **the question is mis-framed,
and this section's own ladder is the evidence.**

Every what-if ladder this campaign has run (sections 15, 16, 17 and step 3
above) retires **startpoint** families, because a top-N census is naturally read
that way.  Re-read step 3's ladder by *endpoint* instead:

```
rungs 0, 4, 5, 6, 7, 8, 11  ->  IcachePlugin_logic_lineReg_reg[418]/D      (7 of 13)
rungs 2, 9, 12              ->  FetchAlignPlugin_logic_predictPending_reg/D (3 of 13)
rungs 3, 10                 ->  DcachePlugin tag/valid arcs                (2 of 13)
rung  1                     ->  FetchAlign p0LiveReg_lenWords[0]/D         (1 of 13)
```

**Ten of the thirteen rungs share two endpoints.**  Retiring startpoints one at
a time does not remove a cone; it rotates through the *fan-in* of the same cone,
which is precisely why the ladder crawls at ~0.007 ns per rung.  Sections 15 and
16 both diagnosed "backfill" and both attributed it to the startpoint families
being adjacent in slack.  The endpoint reading says something stronger and more
actionable: they are adjacent because they are **the same path, entered from
different doors.**

#### What that cone actually is

Reading the archived routed trail of the frontend arc end to end — rather than
inferring it — gives the mechanism:

```text
FetchAlignPlugin stalled_reg/C
  -> FetchAlignPlugin applyNow                       fo=143
  -> FetchAlignPlugin predictTargetReg[13]           fo=177
  -> IcachePlugin pfDemandLine[31] logic             fo=165
  -> ItlbPlugin  tlb_io_hit                          fo=67    <-- LIVE TRANSLATE
  -> IcachePlugin lookupPaddr[3]                              <-- ppn ## pc[11:0]  (:185)
  -> IcachePlugin hitVec_1 / s1Way                            <-- PHYSICAL TAG COMPARE
  -> IcachePlugin arHoldId -> missPA[29]
  -> IcachePlugin pfInstallIdx[0]                    fo=518   <-- 0.472 ns OF ROUTE, ONE NET
  -> IcachePlugin lineReg0_in[418] -> lineReg[418]/D
```

A redirect, a **live ITLB translation**, a **physical tag compare**, and the
**prefetch-install decision** all resolve in one cycle, and the install select
fans out to 518 loads at the end of it.  `lookupPaddr` is literally
`(xlate.rsp.ppn ## lookupPc(11 downto 0))` (`IcachePlugin.scala`:185) — the
translation result concatenated combinationally into the address that then
drives the tag compare.

One net on that chain, `pfInstallIdx[0]` at fanout 518, carries **0.358-0.491 ns
of pure route delay** across the top paths (0.472 ns on the reported WNS path).
For scale: the distance to the 200 MHz floor is 0.472 ns.  That is a coincidence
of magnitude, not a claim that deleting the net buys the floor — the D-cache/IQ
arc is tied at the same slack and would backfill — but it does identify where
the time physically is.

A caution for whoever picks this up, recorded because this pass nearly published
it wrong: the obvious reading of that net's name is that it is `pfInstallSel`
(`IcachePlugin.scala`:544), and **that reading is incorrect.**  `pfInstallSel`
is `OHToUInt(OHMasking.first(pfInstallVec))` over `pfValid && pfComplete &&
!pfErr && !pfPoison` — four *registers*, so its own fan-in is about three levels
deep and it cannot be what a 20-level path arrives through.  The net carries the
synthesised name of the register it feeds while being driven by the *install
condition* at the end of the demand chain.  Name a net from the routed trail,
not from the RTL identifier it resembles.

#### This is a spec-anticipated risk, and its reporting obligation is now overdue

`docs/superpowers/specs/2026-08-10-icache-parallel-vipt-design.md` is a **binding
amendment** that authorised exactly this structure — "a single accepted fetch
launches the ITLB lookup, async tag/prediction lookup, and synchronous data-BRAM
read in parallel from the same virtual address" — and it named this precise cone
as its one physical risk, in its own words:

> the only physical risk is the live ITLB-to-hit-context register cone.  That
> cone **must be reported separately in the next 250-MHz route gate**.

and it pre-authorised the recovery:

> If the new ITLB-to-S1 cone materially regresses routed FMax, the permitted
> recovery is to **pipeline the ITLB's internal hit-way result** or improve
> placement.  Do not restore a translation-to-BRAM-address dependency or delete
> the response register.

`ItlbPlugin_logic_tlb_io_hit` appears on **four of the ten** worst reported paths
in the landed gate.  The cone did materialise, the separate report the amendment
required has not been filed by any section of this handoff, and the recovery it
permits — pipelining the ITLB hit-way result — is **already architecturally
authorised**, so it needs no new spec decision, only a design and a proof.

**That is the honest answer to "what is the next-most-binding family": it is not
a family.  It is the live-translate-to-install cone, it is the one structural
lever this campaign has not measured, its fix is pre-authorised by a binding
spec, and filing its measurement discharges an obligation that spec already
imposed.**

### Step 8: the implementation-strategy lever, closed on the directive axes

Section 18 step 6 named "finish the implementation-strategy sweep" as lever 1,
on the grounds that it was the only lever to have produced a positive result in
five sessions.  It is now substantially closed, and the reason is instructive.

Section 18's `exploreN` changed **three** directives at once relative to
`postrouteN` (placer, phys-opt, router) and measured worse on WNS (-1.642) but
much better on breadth (30,048 vs 32,409 failing endpoints).  That is a
confound.  Two new recipes separate the two axes that matter, each holding the
router at default:

- `postrouteNt` = `postrouteN` + `place_design -directive ExtraTimingOpt` only
- `postrouteNx` = `postrouteN` + `phys_opt_design -directive AggressiveExplore`
  in the post-route rounds only

Both from the same frozen `6b246de` netlist (MD5 `d80f6218…`), `FLOORPLAN_MODE=decode`,
`POSTROUTE_ROUNDS=3`, run in parallel via `synth/floorplan_ab.sh`.
Evidence: `synth/probe_strategy2/`.

| Recipe | placer | post-route phys-opt | router | WNS | FMax | vs incumbent |
|---|---|---|---|---:|---:|---:|
| **`postrouteN` (incumbent)** | default | default | `-tns_cleanup` | **-1.472** | **182.749** | — |
| `postrouteNt` | `ExtraTimingOpt` | default | `-tns_cleanup` | -1.839 | 171.262 | **-11.49 MHz** |
| `postrouteNx` | default | `AggressiveExplore` | `-tns_cleanup` | -1.723 | 174.734 | **-8.02 MHz** |
| `exploreN` (§18 row 17) | `ExtraTimingOpt` | `AggressiveExplore` | `Explore` | -1.642 | 177.242 | -5.51 MHz |

Round by round:

```
postrouteN   -2.094 -> -1.623 -> -1.552 -> -1.472   (still improving at round 3; -1.463 by round 5)
postrouteNt  -2.087 -> -1.841 -> -1.839 -> -1.839   (stalled at round 2)
postrouteNx  -2.094 -> -1.771 -> -1.723 -> -1.723   (stalled at round 2)
```

Four things this measures:

1. **Both non-default directives are individually harmful.**  Neither axis
   explains `exploreN`'s breadth advantage as a WNS proposition; both simply
   lose FMax.
2. **The internal control holds.**  `postrouteNx` round 0 is -2.094, reproducing
   `postrouteN` round 0 *exactly* — the two runs differ only in the rounds, which
   is what the experiment intended.  `postrouteNt` round 0 is -2.087, so
   `ExtraTimingOpt` placement is worth **+0.007 ns before any post-route work**
   and then costs 0.367 ns across the rounds.  It is not a bad placement; it is a
   placement that post-route phys-opt cannot improve.
3. **The mechanism is convergence, not starting point.**  Both variants *stall by
   round 2* while the incumbent is still gaining at round 3 and beyond.  The
   +18.65 MHz that section 18 landed came from **iterating** plain
   `phys_opt_design` / `route_design -tns_cleanup`, not from any directive — the
   directives reach a local optimum faster and stop.
4. **The axes are not additive, so do not reason one-factor-at-a-time here.**
   `exploreN`, with *all three* non-default directives, beats **both** single-axis
   variants (-1.642 vs -1.723 and -1.839).  The `Explore` router partially
   compensates for what the placer and phys-opt directives cost.  A future sweep
   must vary the router jointly rather than assume separability.

**Verdict: `IMPL_STRATEGY=postrouteN` with all-default directives is the optimum
of every recipe tried (now nine), and the directive axes are exhausted.**  The
one genuinely untried item on this lever remains section 18 step 6's lever 4 — a
fresh `synth_design` with the post-route pass in the loop, since every physical
number in this document descends from one frozen synthesis checkpoint.

No default was changed.  `postrouteNt` and `postrouteNx` are retained in
`synth/impl_FullCore.tcl` as measured-negative diagnostics, in the same spirit as
section 18's committed floorplan XDCs.

### Step 9: the endpoint cones, priced — the reframing is right and it still buys nothing

Step 7 argued the ladder should be read by endpoint.  `synth/probe_endpoint_cones.tcl`
prices those cones directly, against the same routed checkpoint, with every cut
asserting on its object count.  The baseline reproduced exactly for a third
independent time.

| Scenario | WNS | ΔWNS | TNS | ΔTNS | Failing endpoints | Δ |
|---|---:|---:|---:|---:|---:|---:|
| baseline | -1.472 | — | -17,499.508 | — | 32,408 | — |
| `lineReg` endpoint cone (512 cells) | -1.472 | **+0.000** | -16,189.253 | +1,310.3 (7.49 %) | 31,384 | **-1,024** |
| `predictPending` endpoint cone (3 cells) | -1.472 | +0.000 | -17,498.057 | +1.5 (0.01 %) | 32,407 | -1 |
| both cones | -1.472 | +0.000 | -16,187.802 | +1,311.7 (7.50 %) | 31,383 | -1,025 |
| the fanout-518 install-select net (13 nets, incl. 9 phys-opt replicas) | -1.472 | +0.000 | -16,529.416 | +970.1 (5.54 %) | 31,641 | -767 |
| **install-select net + Fix B** (both tied arcs, each at its own fix point) | **-1.460** | **+0.012** | -16,150.751 | +1,348.8 (7.71 %) | 31,407 | -1,001 |

Three conclusions, and the third is the one that closes the section.

**The reframing was correct as a description and useless as a lever.**  The
`lineReg` cone is by a wide margin the largest single object this campaign has
measured — **1,024 failing endpoints and 7.49 % of TNS from one `set_false_path`** —
and it is worth **0.000 ns of WNS**, because the D-cache/IQ arc is tied behind it.
Step 7's diagnosis of *why* the startpoint ladder crawls is right; it simply does
not follow that the cone is worth anything.

**Rung count is not worth, again.**  `predictPending` supplied three of the
thirteen ladder rungs in step 3 and is worth **1.5 ns of TNS and one endpoint**.
Three cells.  Any future census must price what it names.

**Cutting both tied arcs at their precise fix points still yields 0.012 ns.**
Scenario 5 is the experiment step 7 was built for: sever the fanout-518 install
select (all thirteen nets, including the nine replicas `phys_opt_design` created)
*and* Fix B, i.e. remove the frontend arc and the D-cache/IQ arc simultaneously
at exactly the places an RTL fix would.  WNS moves -1.472 -> -1.460, and the new
limiter is a **third** arc, `FetchAlign ftqHead[0] -> p0LiveReg_lenWords[0]`.
That is numerically identical to step 2's Fix A + Fix B + whole-frontend-cone row
(-1.460), reached by a completely different route — which is itself the
confirmation that -1.460 is a real floor and not an artefact of one cut model.

The endpoint ladder past both cones agrees: six further endpoint-cone
retirements move -1.472 to -1.451, **0.021 ns**, and five of the six are the
D-cache `stS2Payload_paddr` startpoint arriving at yet another IQ endpoint.

**Final statement of the negative result.  On this placement, no cut of any
shape — startpoint family, endpoint cone, or precise fix point, alone or in any
combination measured — recovers more than 0.012 ns of WNS.  The 200 MHz floor
needs 0.472 ns.  Every remaining path is within 6 % of the worst one, and the
design is uniformly limited by placement and routing rather than by any
identifiable piece of logic.**

### Functional gates

`make SBT=~/sbt/bin/sbt test-fast` — **149/149 tests, 157 suites, 0 failed**, on
the worktree with all of this section's changes present.  **No RTL
changed in this section**; the only tracked non-documentation changes are
`synth/*.tcl` (three new read-only probes plus two measured-negative
`IMPL_STRATEGY` recipes) and the reservation file.

### Status after this section

**No RTL changed.**  `6b246de` remains the head RTL checkpoint and
`FLOORPLAN_MODE=decode` + `IMPL_STRATEGY=postrouteN` (3 rounds) remains the
landed physical baseline at **-1.472 ns / 182.749 MHz**.

- **Section 16's verdict survives re-measurement.**  Fix A (2-deep skid on the
  IQ -> LS-EU issue-port ready) and Fix B (retime `earlyProbeSetWriteVec`) are
  each worth **0.000 ns of WNS** on the new placement, as is the pair.  Neither
  was implemented, and neither should be.
- **What changed is only their breadth value**: Fix B is now worth 2.16 % of TNS
  and 234 endpoints, about eight times section 16's figure.  Its one-cycle
  write-versus-consume correctness hole was re-verified against the routed
  netlist and is real, so it remains a throughput candidate only.
- **Distance to the 200 MHz deployment floor: 0.472 ns, unchanged.**  The goal is
  **not** met.
- Levers closed by this section: family cutting (0.012 ns ceiling), endpoint-cone
  cutting (same 0.012 ns ceiling, reached independently), and the
  implementation-strategy directive axes (both regress).
- Lever left standing and never measured: **pipelining the ITLB hit-way result**,
  which `2026-08-10-icache-parallel-vipt-design.md` already pre-authorises as the
  named recovery for the cone it also named as its one physical risk — and whose
  required separate route-gate report this section is the first to file.
- Also untried: a fresh `synth_design` under the post-route recipe.  Every
  physical number in this document descends from one frozen `6b246de` synthesis
  checkpoint.

## 20. The parallel-VIPT amendment's named risk, finally reported: the ITLB hit-way cone is real, is 4 of the 10 worst paths, and retires 53.5 % of the 200 MHz deficit population for 0.000 ns of WNS — because the deficit is 4,890 endpoints deep, not four paths (Claude, 2026-08-10)

**Grounding only.  No RTL changed, and none should be written for this cone.**

This section discharges a standing, binding spec obligation.
`docs/superpowers/specs/2026-08-10-icache-parallel-vipt-design.md` §2 named
**"the live ITLB-to-hit-context register cone"** as the parallel-VIPT design's
*one* physical risk, required that it "be reported separately in the next
250-MHz route gate", and pre-authorised exactly one recovery:

> If the new ITLB-to-S1 cone materially regresses routed FMax, the permitted
> recovery is to pipeline the ITLB's internal hit-way result or improve
> placement.  Do not restore a translation-to-BRAM-address dependency or delete
> the response register.

That report had never been filed across every route gate since, and the fix had
never been measured.  **It is filed here, and its verdict is: the cone is
exactly as real as the amendment feared, and removing it entirely recovers
0.000 ns of WNS.  The spec's own precondition — "materially regresses routed
FMax" — is therefore not met, and the pre-authorised recovery is not
triggered.**

Evidence: `synth/probe_itlb_hitway/` (script `synth/probe_itlb_hitway.tcl`) and
`synth/probe_slack_population/` (script `synth/probe_slack_population.tcl`),
read-only against
`synth/archive/6b246de_default_postrouteN3_decode/fullcore_routed.dcp`, netlist
MD5 `d80f6218c5c7dcab94a33a52d64244fa`.  Both sessions reproduced the baseline
exactly — WNS -1.472, TNS -17,499.508, 32,408 failing endpoints, worst path
`stS2Payload_paddr[5] -> sbNzvc_busy[8]` — before any what-if.

### Step 1: what the cone actually is, read off the routed netlist

The `Tlb` (`src/main/scala/m68k040/mmu/Tlb.scala`) is 32 entries / 4 ways /
2 banks, so `setsPerBank = 4` and the lookup is a shallow per-bank 4-way tag
compare, not a CAM.  Its "hit-way result" is `hitVec` (:100-113), reduced to
`io.hit := hitVec.orR` and `io.hitEntry := MuxOH(hitVec, entVec)` (:114-115).
`ItlbPlugin` consumes it combinationally (`tlbHit`/`tlbEntry`, :107-108) into the
`_rsp.ready`/`_rsp.ppn` response mux (:294-298), and `IcachePlugin` consumes
*that* combinationally as `lookupPaddr = xlate.rsp.ppn ## lookupPc(11:0)`
(:185) — which is both the L1I physical tag compare **and**, via
`cmdPort.ready := xlate.rsp.ready && !setBlocked && answerable` (:661), the
fetch **acceptance** decision.

That is the amendment's cone, and here it is as routed, path #2 of the worst 10
(-1.472 ns, tied with the WNS path to three decimals):

```
FetchAlign stalled/C                                              0.107
  -> ftqMem -> cmdWindowPc -> applyNow            (fo=143)        0.733
  -> Icache pfDemandLine -> predictTargetReg      (fo=177)        1.220
  -> pfDemandLine[31]_i_41 (fo=165, 0.376 ns route)               1.647
  -> Tlb _zz_hitVec_2[1]                                          2.063   <-- ITLB in
  -> LUT6 -> CARRY8 -> hitVec_20 -> LUT3 -> LUT4                  2.856
  -> ItlbPlugin_logic_tlb_io_hit                  (fo=67)         3.145   <-- ITLB out
  -> Icache lookupPaddr[3]                        (fo=11)         3.477
  -> s1Way -> CARRY8 -> Icache hitVec_10                          3.981
  -> arHoldId -> missPA                                           4.625
  -> pfInstallIdx[0]                              (fo=518, 0.472 ns route)
  -> IcachePlugin lineReg[418]/D                                  5.481
```

- **The ITLB's own internal hit-way segment is 2.063 -> 3.145 = 1.082 ns, 19.7 %
  of a 5.481 ns path.**  The amendment was right to flag it.
- **It is on 4 of the 10 worst paths** — #2 (-1.472), #3 (-1.470), #9 (-1.458),
  #10 (-1.457), all the `FetchAlign stalled -> Icache lineReg / commitBeat`
  family.  The other six are D-cache/IQ (5) and `ftqHead -> p0LiveReg` (1).
- **3,662 failing endpoints run through it** — 11.3 % of the 32,408.  That is
  the widest single cone this campaign has priced.

Registering `hitVec` inside `Tlb` cuts this path at `tlb_io_hit`, splitting
5.481 ns into 3.145 + 2.336.  Both halves clear 4.000 ns, so
`set_false_path -through` the 106 matched `io_hit` / `io_hitEntry_*` /
internal `hitVec` nets is not merely an upper bound — it is the *accurate*
model of the authorised register insertion.  (Object count printed and asserted
non-zero, per section 19 step 1's rule.)

### Step 2: the measurement the spec asked for

| Scenario | WNS | ΔWNS | TNS | ΔTNS | Failing endpoints | Δ | Worst path after |
|---|---:|---:|---:|---:|---:|---:|---|
| baseline (reproduced) | -1.472 | — | -17,499.508 | — | 32,408 | — | `stS2Payload_paddr[5] -> sbNzvc_busy[8]` |
| **ITLB hit-way, alone** | **-1.472** | **+0.000** | -15,926.618 | **+1,572.9 (9.0 %)** | 31,497 | **-911** | `stS2Payload_paddr[5] -> sbNzvc_busy[8]` |
| ITLB + D-cache/IQ (Fix A upper bound) | **-1.460** | +0.012 | -15,801.374 | +1,698.1 (9.7 %) | 31,452 | -956 | `ftqHead[0] -> p0LiveReg_lenWords[0]` |
| ITLB + Fix A + Fix B + entire frontend cone | -1.460 | +0.012 | -14,835.954 | +2,663.6 (15.2 %) | 31,185 | -1,223 | `ftqHead[0] -> p0LiveReg_lenWords[0]` |

**The authorised fix, applied at its most generous, is worth 0.000 ns of WNS.**
The mechanism is section 19's tie, now confirmed from the other side: path #1
(D-cache/IQ) and path #2 (this cone) are both -1.472, so deleting either exposes
the other at the identical slack.  Deleting *both* lands on path #7,
`ftqHead -> p0LiveReg_lenWords`, at -1.460 — and adding Fix B and the entire
frontend demand cone on top of that changes nothing further.  **-1.460 is now
the third independent route to the same floor** (section 19 reached it twice).

What the cone *does* own is breadth: **9.0 % of TNS and 911 failing endpoints,
from a single register insertion.**  That is the largest TNS-per-cut this
campaign has measured — four times Fix B, more than the entire frontend demand
cone.  It is still not FMax, and FMax is what the 200 MHz goal gates on.

### Step 3: the honest IPC cost, had it been built

It matters that this is *not* a free retime, because the throughput-over-latency
principle that justified `ringDrop` and `ftbFraming` does **not** transfer here.

`xlate.rsp.ready` and `xlate.rsp.ppn` are consumed by the fetch **acceptance**
gate, not only by a downstream capture.  Registering the hit-way makes the
translation answer one cycle stale, so a naive insertion forces the L1I to
accept at most one command every two cycles — **fetch II=2, halving frontend
bandwidth.**  For a core whose binding goal is "200 MHz *with good IPC*", that
trades the entire IPC budget for 0.000 ns.

There *is* a cheaper variant, and it is worth recording because it is the only
version anyone should ever revisit: the ITLB lookup key is the VPN
`pc[31:12]`, which is **constant across a whole 4 KiB page** — 64 L1I lines of
sequential fetch.  So a registered hit-way plus a live
`lookupVpnReg === _req.vpn` stability comparator would be correct with a bubble
only when the VPN actually changes (page cross, or a cross-page redirect), which
is rare.  But that variant leaves a live 20-bit equality compare in the very cone
being cut, so its real recovery is **strictly smaller than the 1.082 ns
modelled above** — and the modelled version is already worth 0.000 ns.  A
smaller cut of a path whose full removal buys nothing buys nothing.

**Verdict: do not build it now.**  The obligation is discharged by this report,
not by the fix; the spec conditioned the recovery on a material routed-FMax
regression, and the measurement shows there is none to recover.  Step 4b
qualifies this in the fix's favour — it is the largest single component of the
deficit by endpoint count — but not enough to reverse it on its own.

### Step 4: the measurement that explains the whole campaign — the slack *population*

Every ladder in sections 15, 16, 17, 19 and step 2 above asks *"which path is
worst"*.  That is the wrong question, and this is the number that proves it — a
census of how many **endpoints** sit below each slack threshold:

| slack better than... | endpoints | share of 171,230 |
|---|---:|---:|
| -1.400 | **98** | 0.06 % |
| -1.200 | 2,823 | 1.6 % |
| **-1.000 (the 200 MHz floor)** | **4,890** | **2.9 %** |
| -0.800 | 7,247 | 4.2 % |
| -0.500 | 15,904 | 9.3 % |
| -0.250 | 23,445 | 13.7 % |
| 0.000 | 32,408 | 18.9 % |

**Reaching 200 MHz does not require fixing four paths, or thirteen families.  It
requires improving 4,890 endpoints.**  Only 98 endpoints — 2 % of that
population — lie below -1.400, which is precisely why every family retirement
this campaign has attempted recovered 0.000 to 0.017 ns: each one removes a
handful of endpoints from the top of a dense, nearly continuous distribution and
immediately exposes the next.

That population is, by breadth, strikingly **concentrated** — which is what
makes the zero WNS result counter-intuitive enough to be worth stating plainly:

| sub-(-1.000) endpoints by START family | | by END family | |
|---|---:|---|---:|
| FetchAlignPlugin | **2,628** | IcachePlugin | **2,427** |
| DcachePlugin | 1,370 | DcachePlugin | 703 |
| DecodeStage | 679 | RasPlugin | 496 |
| AluEuPlugin | 152 | RobPlugin | 334 |
| LsEuPlugin | 34 | IssueQueuePlugin | 329 |
| RobPlugin | 23 | FetchAlignPlugin | 229 |
| (4 others) | 4 | (9 others) | 372 |

**One arc — `FetchAlign -> IcachePlugin` — owns more than half of the endpoints
that must move to reach 200 MHz, and 2,620 of the 4,890 (53.6 %) have their
worst path running through the ITLB hit-way cone specifically.**  The deficit
*is* concentrated.  It simply cannot be *cut*, because every one of those
endpoints is also reachable by a second, third and fourth path of nearly
identical delay, so severing the worst one just promotes the next.  That is the
single most important thing this campaign has learned, and it took a population
census rather than a path census to see it.

And the population's shape says where the delay actually lives:

```
logic levels  9-11 :   452 endpoints        mean over the 4,890 sub-(-1.000) endpoints:
logic levels 12-15 :   464                    logic delay 1.579 ns
logic levels 16-17 :   708                    net   delay 3.546 ns
logic levels 18-20 : 3,195   <-- 65.3 %       net share   69.2 %
logic levels 21-22 :    59
```

**Two-thirds of the endpoints that must move sit at 18-20 logic levels, and
69.2 % of their delay is routing, not logic.**

### Step 4b: the fairest possible test of the authorised fix — and it passes, while still not reaching 200 MHz

Judging the ITLB fix by WNS alone is not quite fair to it, because a dense
distribution makes WNS almost incapable of moving.  The fair question is: **how
many of the 4,890 does each cut actually retire?**  An endpoint is only retired
if it has no *other* remaining path below -1.000 — which is exactly what the
step-4 concentration finding puts in doubt.  Measured directly
(`synth/probe_population_after_cuts/`):

| cumulative cut | endpoints still below -1.000 | retired | WNS |
|---|---:|---:|---:|
| baseline | 4,890 | — | -1.472 |
| **+ ITLB hit-way** | **2,272** | **-2,618 (53.5 %)** | -1.472 |
| + D-cache/IQ (Fix A upper bound) | 2,136 | -136 | -1.460 |
| + Fix B + entire frontend demand cone | **2,017** | -119 | -1.460 |

**A single register insertion inside `Tlb.scala` retires 2,618 of the 4,890
endpoints standing between this core and 200 MHz — 53.5 % of the entire deficit
population, and twenty times what every other candidate on file achieves
combined.**  This is by a wide margin the largest object anyone in this campaign
has found.

And it still does not get to 200 MHz, because **after applying every candidate at
once, 2,017 endpoints remain below -1.000 ns** and WNS is pinned at -1.460.
Those two facts together are the campaign's conclusion: the ITLB hit-way cone is
genuinely the biggest single component of the FMax deficit, *and* removing it
completely still leaves 41 % of the deficit population untouched, spread across
the D-cache/IQ, RAS, ROB and DecodeStage structures with no comparable
concentration left to attack.

**This does not change the recommendation not to build it now** — 0.000 ns of
WNS today, against a real fetch-throughput cost, is a bad trade for a core whose
goal is "200 MHz *with good IPC*".  It does change what the fix *is*: not a
failed lever, but **the natural first slice of the architectural-pipelining
design described in step 6**, where its one-cycle cost is justified alongside the
other stage splits rather than paid alone for nothing.

### Step 5: the deep ladder — 45 consecutive family retirements are worth 0.196 ns

Sections 15/16/17/19 each walked 13-17 rungs and stopped at a plateau.  This one
was run to 45, each rung false-pathing the worst path's **entire** family at both
ends (startpoint cell *and* endpoint cell) — an idealisation far stronger than
any RTL change can achieve:

```
rung  0  -1.472    rung 10  -1.428    rung 20  -1.383    rung 30  -1.325    rung 40  -1.288
rung  1  -1.472    rung 11  -1.418    rung 21  -1.378    rung 31  -1.323    rung 41  -1.282
rung  2  -1.460    rung 12  -1.417    rung 22  -1.373    rung 32  -1.323    rung 42  -1.282
rung  3  -1.451    rung 13  -1.413    rung 23  -1.372    rung 33  -1.318    rung 43  -1.279
rung  4  -1.451    rung 14  -1.408    rung 24  -1.369    rung 34  -1.318    rung 44  -1.277
```

Full table: `synth/probe_itlb_hitway/deep_ladder.txt`.

**Forty-five family retirements move WNS from -1.472 to -1.277: 0.196 ns, a mean
of 0.0044 ns per rung.**  The 200 MHz floor needs 0.472 ns.  Linear
extrapolation puts that at roughly **110 consecutive family retirements** — and
the rate is decelerating, not accelerating, as the distribution densifies.  The
families cycle through every major structure in the core: D-cache/IQ, FetchAlign,
I-cache, Gshare, FTB, RAS, ROB, ALU EU, DecodeStage.  There is no concentration
left to attack.

### Step 6: what this means — the category of work required has changed

The routed design is **51.01 % CLB LUTs (110,679 / 216,960), 11.61 % registers,
with no congestion window above level 5**.  It is neither full nor congested.
Yet net delay is **69.2 % of the mean sub-(-1.000) endpoint's datapath**, and
those endpoints sit at a modal **18-20 logic levels**, traversing five or more
nets with fanouts of 46, 67, 121, 143, 165, 166, 177, and 518.

That is the whole diagnosis in one line: **at a 4.000 ns target, 19 logic levels
leaves ~0.21 ns per level, and this design's levels cost ~0.27 ns because
two-thirds of each is high-fanout routing.**  The deficit is not located in any
path; it is the average depth of the design.

Note what that rules out.  A 51 %-utilised, uncongested device with 69 % net
delay is not short of *area* — so area-reduction work would not help, and the
standing "area growth is a review point, not a rejection" rule remains safe.  It
is short of *stages*.

Consequently:

- **RTL point cuts are exhausted, and now provably so.**  Family cutting,
  endpoint-cone cutting, precise fix points, the D-cache/IQ pair, the entire
  frontend demand cone, and the ITLB hit-way cone — alone and in every
  combination — top out at **+0.012 ns** against a 0.472 ns requirement.  Three
  independent routes reach the identical -1.460 floor.
- **Placement and implementation strategy are exhausted.**  Every floorplan
  variant regressed (section 18); both directive axes regressed (section 19).
  The one strategy win, iterated post-route phys-opt (+18.95 MHz), is already
  landed and is what produced the flat distribution being measured here.
- **What is left is not a timing cut.**  Closing 0.472 ns across 4,890 endpoints
  requires reducing *average logic depth*, which means real architectural
  pipelining — splitting deep combinational stages across the
  FetchAlign -> I-cache demand/install arc (2,628 startpoints / 2,427 endpoints,
  of which the ITLB hit-way register insertion alone is worth 2,618 retired
  endpoints), the D-cache store pipe -> IQ scoreboard wakeup arc (1,370 / 1,032),
  and the DecodeStage -> RAS/ROB arc (679 / 830) — counts are per-family
  startpoint and endpoint populations from the step-4 census, not matched pairs.
  Each costs a cycle of latency and must be justified on its own IPC terms, not
  as a timing patch.
  That is a design cycle with its own spec, not a lever this campaign can pull.
  Step 4b gives that future design its ordering and its expected yield: even all
  three arcs together leave 2,017 endpoints below -1.000, so a fourth pass would
  be needed, and 250 MHz is not in reach at this depth at all.
- The cheapest remaining *unmeasured* experiment is still a fresh `synth_design`
  under the post-route recipe: every physical number in this document descends
  from one frozen `6b246de` synthesis checkpoint, and a re-synthesis is the one
  variable never varied.  It is a lottery ticket, not a plan.

### Step 7: status

**No RTL changed.**  `6b246de` remains the head RTL checkpoint;
`FLOORPLAN_MODE=decode` + `IMPL_STRATEGY=postrouteN` (3 rounds) remains the
landed physical baseline at **-1.472 ns / 182.749 MHz**.  Repository gate
`make SBT=~/sbt/bin/sbt test-fast` re-run and green: **149/149 tests, 157 suites
completed, 0 failed, 0 aborted** — the section-19 baseline exactly.

- The parallel-VIPT amendment's §2 reporting obligation is **discharged**.  Its
  pre-authorised recovery is **not triggered** and should **not be built now**:
  the cone is 19.7 % of its path and 11.3 % of all failing endpoints, but
  removing it entirely is worth **0.000 ns of WNS**, and building it would cost
  fetch throughput.  It is, however, worth **2,618 retired sub-(-1.000)
  endpoints — 53.5 % of the 200 MHz deficit population** — which makes it the
  designated first slice of any future architectural-pipelining spec.
- **Distance to the 200 MHz deployment floor: 0.472 ns.  The goal is NOT met, at
  182.749 MHz.**
- The blocker is now characterised rather than merely observed: **4,890 endpoints
  below -1.000 ns (2,017 surviving every candidate cut applied at once), 45
  idealised family retirements worth 0.196 ns, a modal 18-20 logic levels at
  69.2 % net delay in a 51 %-utilised, uncongested device.**
- **This closes the FMax-recovery campaign as a cut-based effort.**  Sections 13
  and 14 landed real cuts; sections 15, 16, 17, 19 and 20 measured the remaining
  candidates to exhaustion; section 18 landed the one implementation-strategy
  win.  Everything after this needs a different category of work.

## 21. Closed at zero cost: the "elaboration lottery ticket" is void — re-elaboration at HEAD is bit-exact, so there is no free re-roll (Claude, 2026-08-10)

Section 20 flagged one cheap unmeasured experiment before committing to the
multi-session architectural-pipelining effort: *every number in this campaign
descends from one frozen generated-Verilog checkpoint*
(`generated/M68kFullCoreSynth.v`, md5 `d80f6218c5c7dcab94a33a52d64244fa`,
archived at `synth/archive/6b246de_default_postrouteN3_decode/`).  The sibling
branch `feat/rob-predictor-mem` independently discovered a real SpinalHDL
elaboration non-determinism bug whose two draws implemented **12.7-17.8 MHz
apart** — larger than any single RTL lever here.  If this branch still had it, a
fresh `GenFullCoreSynthVerilog` could land a materially better netlist by luck
alone, for the price of one synth run.

**It does not.  The experiment is closed as a negative, and no synth was run.**

### The fix is already on this branch

`3e57fe6 fix(regfile): pin physical write-port slot order — closes the SpinalHDL
elaboration "netlist lottery"` is an **ancestor of HEAD** (`a9f14ff`).
`src/main/scala/m68k040/execute/regfile/RegFilePlugin.scala` groups write
requests through a `scala.collection.mutable.LinkedHashMap` keyed on
first-appearance order, not `groupBy` (whose immutable-`HashMap` iteration order
is a function of the `new Object` sharing keys' **JVM identity hashes**, and
therefore a per-run lottery).  A sweep for other instances of the same hazard
found **none**: `groupBy` now appears in `src/main/scala/` only inside that
file's own do-not-reintroduce comment, and there are no `.toSet` iterations or
other identity-hash-keyed collections driving structure.

### Grounded, not assumed: two cold-JVM re-elaborations are byte-identical

The fix's presence was not taken on trust.  Two independent
`~/sbt/bin/sbt 'runMain m68k040.top.GenFullCoreSynthVerilog'` runs, each from a
cold JVM with the output deleted first:

| artifact | md5 | comment-stripped md5 |
|---|---|---|
| archived campaign baseline | `d80f6218c5c7dcab94a33a52d64244fa` | `3531a01b346df92e2e14c389ef47289b` |
| fresh re-elaboration #1 | `d79df4c8d7ba87cb9f499ca878fb6dbd` | `3531a01b346df92e2e14c389ef47289b` |
| fresh re-elaboration #2 | `d79df4c8d7ba87cb9f499ca878fb6dbd` | `3531a01b346df92e2e14c389ef47289b` |

The two fresh rolls are **byte-identical to each other**, and the entire diff
against the archived baseline is **one line**, a comment:

```
3c3
< // Git hash  : 9a013ae1344c69d03cd2632b3e2a048e3d3e3c4c
---
> // Git hash  : a9f14ffcc1373b11d118a1313c4626e14b85d7e7
```

Vivado ignores it.  **Re-synthesising would consume a full post-route gate to
reproduce `-1.472 ns / 182.749 MHz` exactly.**  Nothing was synthesised.

### Byproduct: the baseline's `Git hash` header is stale, and the netlist is *not*

The archived header names `9a013ae`, which **predates** the section-14 FTB
framing-verdict retime (landed in `9a013ae..6b246de`).  That looked like the
campaign might have been synthesising a pre-cut netlist.  It was not — the retime's
own new signals are present in the archived baseline and in the fresh rolls in
identical counts (`qFramed` ×3, `qEnd` ×7).  The RTL content is current and
correct; only SpinalHDL's emitted `// Git hash` comment lagged.  Independently,
`git diff 6b246de..HEAD -- src/main/scala` is **empty** — every commit since the
archived checkpoint is docs/synth-only.

**Use the comment-stripped hash `3531a01b346df92e2e14c389ef47289b` as the durable
netlist identity.**  The full-file md5 changes with every commit, because the
header comment carries the git HEAD, so it is worthless as a "did the RTL change?"
check.  On-disk `generated/M68kFullCoreSynth.v` was restored bit-for-bit to
`d80f6218...` so the md5 cited throughout sections 13-20 keeps resolving.

### Status

- **No RTL changed, no synth run, nothing committed** beyond this section and the
  progress-file pointer.  `git status --porcelain -- src/` is empty.
- The `-1.472 ns / 182.749 MHz` baseline stands unchanged and is now known to be
  **reproducible from source**, not an artifact of one lucky or unlucky draw.
- That is the real value here: it removes an outstanding doubt about *every*
  A/B measurement in sections 13-20.  Those gates compared netlists that differ
  only by their intended RTL edit, with no lottery noise underneath.
- **Section 20's conclusion is unchanged and now has no cheap alternative left in
  front of it.**  The next work is genuine architectural pipelining.

## 22. The first architectural-pipelining design is written: FetchAlign -> I-cache, one new stage, II=1 preserved (Claude, 2026-08-10)

**Design only.  No RTL, no synth.**  Section 20 step 6 closed the cut-based
campaign and named three arcs that need real pipelining, in measured priority
order.  Arc 1 -- `FetchAlign -> IcachePlugin`, 2,628 sub-(-1.000 ns) startpoints
and 2,427 endpoints, including the ITLB hit-way cone that alone retires 53.5 % of
the 4,890-endpoint deficit population -- now has a full design:

**`docs/superpowers/specs/2026-08-10-ipc-fetchalign-icache-pipeline-design.md`**

Grounded in live RTL reads (`FetchAlignPlugin.scala`, `IcachePlugin.scala`,
`Tlb.scala`, `ItlbPlugin.scala`, `ExceptionUnit.scala`) plus the archived
`6b246de_default_postrouteN3_decode` routed evidence in sections 19-21.

**What it proposes.**  Exactly one new architectural stage in the I-cache demand
path, placed at the *measured* midpoint of the worst frontend path -- the ITLB
output, which section 20 step 1 showed splits 5.481 ns into 3.145 + 2.336.  The
cache becomes `F1 (accept + translate) -> F2 (verdict + dispatch) -> S1 -> rsp`.
F2 is today's accept cycle relocated onto a registered translation context; S1
and rsp are untouched.  Plus a *free* slice that decouples the speculative
prefetch/install state (`lineReg`, the fanout-518 install select, the prefetch
window, the AR arbiter) from the live demand verdict under section 15 step 9's
standing miss-path licence.  `RING` 3 -> 4 in FetchAlign; the FTB/gshare token
pipeline needs **no** change (`token.ringSlot` is already 2 bits and
`log2Up(3) == log2Up(4)`).

**The II=2 risk is real and the design does not pay it.**  Section 20 step 3's
warning is correct *for a single-cycle-accept cache*: `xlate.rsp.{ready,ppn}`
gate `cmdPort.ready`, so a registered hit-way with today's structure forces
fetch II=2.  The design deletes that premise -- acceptance moves to F1 and the
verdict becomes a pipeline stage rather than a handshake term.  **II stays 1;
the cost is +1 cycle of fetch latency** (clean-redirect first-useful-group N+4 ->
N+5).  The VPN-stability-compare variant is evaluated and rejected as *strictly
dominated*, and hit-way speculate-then-confirm is rejected on correctness: the
fetch-directed BTB may speculate only because `BranchEuPlugin` independently
verifies it, and **there is no verifier for a cache hit-way** -- a wrong way
delivers wrong instruction bytes with no detector.

**The projection is deliberately unflattering.**  The exact modelled cut is
already measured at **+0.000 ns WNS** (section 20 step 2), because this arc is
tied at -1.472 ns with the D-cache/IQ arc to three decimals.  Projected
post-route for slices 1+2 alone: **-1.472 to -1.350 ns, 182.7-186.9 MHz, most
likely ~183-185 MHz.  200 MHz is not reached by this arc.**  What it does move is
the population metric section 20 step 4b introduced: **4,890 -> ~2,150 endpoints
below -1.000 ns**, and ~15 % of TNS.  The spec states in advance that this metric
has **never been validated against a real route**, and that slice 2's post-route
gate is the first test of it -- a result worth having either way.

**Acceptance is on IPC x FMax, with a break-even table.**  At the 182.749 MHz /
0.6739-ideal-IPC baseline, slice 2 must deliver **+0.055 ns (184.60 MHz) to break
even against a 1.0 % IPC loss**.  The projected band straddles that.  An explicit
falsifier is written into the spec: if post-route measures < +0.050 ns *and* IPC
costs > 1.0 %, slice 2 is reverted (or left disabled behind its elaboration
flag), slice 1 stays because it is free, and the program re-scopes to attack the
D-cache/IQ arc first.

**The strategic point, stated plainly in the spec:** because the two arcs are
tied, **neither alone can move WNS -- the program only pays if at least two of
the three arcs land.**  Slice 2 is therefore specified behind an elaboration flag
(`icacheVerdictStage`, following `enableFetchDirected`/`earlyFree`) so arc 1 and
arc 2 can be measured *together* without a revert cycle.

Six slices, three binding amendments to
`2026-08-10-icache-parallel-vipt-design.md` (§1 cycle contract, §2 recovery taken
in a bandwidth-preserving form, §3.2 accept-behind-a-miss reworded to
*answer*-behind-a-miss), 13 hazards, four required in-RTL oracles and five
mutation proofs.  ~1,100-1,300 lines across ~7 files.  **Awaiting explicit
go-ahead before any implementation.**
