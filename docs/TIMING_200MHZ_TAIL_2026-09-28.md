# 200 MHz closure on `integ/all-shippable`, and what the tight tail is made of

Build: `macqd700-soc-public/build/lane200_integoff`, route directive `AggressiveExplore`.
CPU provenance: `cpu040_sha=c4ed1fc7` (`integ/all-shippable` head), `dirty=no`,
`target_freq_mhz=200`, `core_clk_hz=200_000_000`, `cpu_ipc_profile=throughput-v2`.

`SHIPPING_CONFIG` (read from the build log, per the four-instance rule -- this is the
**flags-OFF arm**, i.e. the baseline for the new levers):

    lsOooIssue=false ipcThroughput=true ipcLateStore=true specLoadWakeup=false
    dcacheHitUnderMiss=true dcacheHitUnderMissRead=false dcacheFillForward=false
    dcacheSectored=false rasBranchRepair=false computeDirectTargets=false
    deferSlot1Uncond=false deferSlot1Dbcc=false itlbVictimEntries=0
    storeQueueDepth=8 sqNarrowDrainMerge=false icachePrefetch=true

## Result: closed, with EXACTLY ZERO margin

    WNS  +0.000 ns   TNS 0.000   0 failing of 345,675 setup endpoints
    WHS  +0.000 ns   THS 0.000   0 failing of 344,827 hold endpoints
    WPWS +0.000 ns               0 failing of 114,295 pulse-width endpoints
    "All user specified timing constraints are met."

Clocks are genuinely constrained: `core_mmcm_clkout0` = 5.000 ns / 200 MHz,
`checking no_clock (0)`, 0 register pins with no clock. All 2,915 detailed paths MET,
none violated. So the zeros are a real result, not an unconstrained-design artifact
and not a failed parse -- `fpga_top.buildinfo.resume`'s `wns=0.000` is faithful.

**This is worse than both prior closures (+0.007, +0.010).** Zero margin is not
shippable-by-inspection, and per `neither-wns-metric-attributes-small-effects`, a
0.000 here cannot be compared to a +0.007 there at all. Treat it as "route found an
answer at this netlist", nothing more.

## The tail is CONTROL BROADCAST, not datapath

Five paths sit at exactly 0.000. **One is the SONIC TX DMA; four are `u_cpu/socket_core`.**
(The SONIC path is merely the first one the report prints -- it does not own the tail.
I initially read it as the binding module and that was wrong.)

Slack distribution below 0.020 ns: 5 @ 0.000, then a dense tail -- 21 paths at 0.017
alone, ~110 paths under 0.020 ns. The design is not limited by one path; it is limited
by a *wall* of near-identical ones, which is why placement variance has always beaten
single-path levers here.

Recurring source->destination hubs in that wall, all of the same SHAPE -- a control
register fanning out to a wide **clock-enable or reset** pin, never a datapath D pin:

| Source (fanout hub) | Destination | Paths <=0.020 |
|---|---|---|
| `RobPlugin_logic_doFlushReg_reg` | `IssueQueuePlugin ... triggers_reg[0]/CE`, `selPorts_1_rData_robId/D` | 4 |
| `DcachePlugin_logic_tagMem_{0,3}_reg/CLKARDCLK` | `victimEvictLine_reg[*]/CE`, `maint_wbLineReg_reg[*]/CE` | 7 |
| `GsharePlugin_logic_pht_spinal_port4_reg[1]` | `IcachePlugin_logic_mshrPa_{2,3}_reg[*]/CE` | 5 |
| `RobPlugin_logic_exc_fsm_stateReg_reg[1]_rep__1` | `DtlbPlugin ... token_reg[*]/CE` | 3 |
| `DcachePlugin_logic_missCmode_reg[0]` | `LsEuPlugin_logic_s1Base_reg[20]/R`, IQ `dynWaitAny/D` | 2 |

Worst path profile: **14 logic levels, 4.876 ns of 5.000 ns, route 3.680 ns = 75.5%**
(logic only 24.5%). Clock skew -0.001 ns, uncertainty 0.061 ns. Route-dominated,
consistent with `200mhz-closed-fullcore` (80.4% wire).

### Consequences

1. **The lever that matters for fmax is FANOUT on control nets, not area.** This is the
   same conclusion as `compaction-is-free-ohmasking-is-cheap` ("the win is fanout"),
   now confirmed from the *binding* paths rather than from a count.
2. **GOAL.txt step 0 is at risk.** LS-OoO issue costs +1,421 LUT and touches the IQ's
   issue select -- the exact structure on the far end of `doFlushReg`'s 4 tightest
   paths. Shipping it into a 0.000 ns design will not close unless it stays off those
   nets. Price it against THIS tail, not against an area number.
3. **CORRECTION -- the Gshare -> `mshrPa/CE` path is NOT spurious.** I first read it as
   a merged enable term coupling two unrelated structures. It is real dataflow, and the
   tree already documents it: `FetchAlignPlugin.scala:55-100` records the frontend's
   longest cone as

       ftbBlocked -> applyNow -> live ITLB CAM/permission -> L1I cacheability
                  -> speculative prefetch installer control
                  -> mshrPa -> IcachePlugin_logic_pfNextPa_reg[*]/CE

   and calls that tail "pre-existing and structural". `mshrPa_{2,3}` are the *prefetch*
   slots (`mshrPa(pfFreeMshr)`, `IcachePlugin.scala:2346`), so their CE is the
   prefetch-ALLOCATE enable, a six-way conjunction:

       pfWindowHasCandidate && !anyInvalidate && !demandFillStart
         && !pfWindowUpdate && !demandStuckQ && !s0KillsWindowQ    ... then pfHasFree

   with `s0KillsWindowQ = s0Valid && !s0Replay && (s0Fault || !s0Cacheable)` dragging
   the ITLB/cacheability cone in. Gshare reaches it through the prediction that forms
   the fetch window. There is no free win from "unhooking" anything.

### The lever this actually exposes: RETIME THE PREFETCH ALLOCATE ENABLE

The prefetch allocate is the one consumer in that cone where **a cycle of latency is
free by construction** -- a prefetch issued one cycle later is still a prefetch, and
nothing architectural observes when it was allocated. That is a strictly stronger
version of the argument this tree has already used twice, for `quiesce` and for
`icMaintFlush` (`FetchAlignPlugin.scala:55-100`: "WHY ONE CYCLE OF LATENCY IS FREE").

Cost: registering the conjunction is a handful of flops and no logic levels.
Prize: it removes 5 of the ~110 sub-0.020 ns paths, and it is the *only* hub in the
table whose consumer is provably latency-tolerant -- `doFlushReg`, the exception FSM
and `missCmode` are all correctness-critical arms that cannot simply be delayed.

⚠️ The one thing to check: the elsewhen chain at `IcachePlugin.scala:2329-2350` also
advances `pfNextPa` on the non-allocating arms, so the retime must move **all** the
arms together or the window walks out of step with the allocation -- the same
"partial cut" trap the `icMaintFlush` note names as "the one thing NOT to do here".

## Falsifier for claim 1

If fanout on those control nets is the limiter, then replicating `doFlushReg` and
`missCmode` (a flop each, no logic) should move those specific endpoints off the tail
without changing IPC or area materially. If the tail does not move, the limiter is
route congestion in the destination structures and replication will not help --
in which case see `area-vs-density-congestion`.


---

## ⛔ THE PREFETCH-ALLOCATE RETIME IS DEAD (2026-09-29) — and so is its successor idea

I proposed registering the prefetch-allocate conjunction as "free by construction".
**Both that lever and the follow-up it suggested are dead.** Recorded so neither is
re-derived.

### 1. The retime: already analysed and rejected IN THE SOURCE

The full path was extracted from `timing_summary.rpt` rather than reasoned about:

    Gshare pht_port4[1] -> FtbPlugin brType -> IcachePlugin s0Ppn -> ITLB CAM (hitVec_11)
      -> s0Cacheable -> lookupPageCacheable -> cmdPort_ready1 -> pfNextPa -> mshrPa_2/CE

It runs **through `s0Cacheable`**, i.e. through `s0KillsWindowQ` — which is a **SAFETY
GATE, not a convenience**. `IcachePlugin.scala:2327` documents it at length: it bounds a
faulting/non-cacheable page to **at most ONE speculative line**, and that bound is
**mutation-verified** — "deleting `!s0KillsWindowQ` below re-opens T+1 and the test fails
with `RULE-P1 BOUND VIOLATED: 2 speculative ARs`" (`IcachePrefetchSpec` P1 residual,
recorded in `IcacheMutationProofSpec` under M6).

The same comment enumerates three closures and rejects each — on **design intent**
(putting the live verdict back in the allocator cone is the one arc M4 exists to delete,
spec §5.4), on **cost** (a verdict-free `!cmdPort.fire` gate permanently starves the
prefetcher), and on **cost/benefit** (allocate-then-retract needs a new MSHR lifecycle
transition and cannot recall an AXI burst anyway).

**A prefetch allocate is latency-tolerant; its SAFETY GATE is not.** That is the
distinction my "free by construction" argument missed.

### 2. The `exactlyOne` idea it suggested: dead on geometry

The path's cells are named `_zz_io_dbgMultiHotVpn_*` and produce `hitVec_11`, which looked
like a debug output costing fetch-path time. It is not — those cells build the **real CAM
hit vector**; the naming follows a shared cone. This is the second confirmation this
session of the standing rule **never verify a netlist by grepping signal names**.

I then supposed `io.hit = OneHotSafe.exactlyOne(hitVec)` (= `orR && !multiHot`, where
`multiHot` is all `C(n,2)` pairwise ANDs) was a carry chain worth deleting. **It is not:
`hitVec` is `ways` wide and `Tlb.DefaultWays = 4`** — the TLB's 32 entries are
4 ways x 2 banks x 4 rows, so `multiHot` is **6 AND terms**, about one LUT. I had taken
"32-entry ITLB CAM" from memory and assumed the compare was 32 wide.

### What is actually true about this path

It is the frontend's documented longest cone, it is known, and it **closed** in this build
(+0.003 ns). `FetchAlignPlugin.scala:55-100` already retimed the one arc that was
retimable (`icMaintFlush`, worth 1.129 ns of pure prefix) and returned the endpoint to its
structural level. There is no missed lever here — the tail is a wall, and this is one
brick of it that has already been worked.
