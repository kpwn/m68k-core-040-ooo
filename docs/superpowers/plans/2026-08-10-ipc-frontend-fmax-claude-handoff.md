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
decode advantage over `none` has shrunk from 0.442 ns to 0.093 ns.**  Use the
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
