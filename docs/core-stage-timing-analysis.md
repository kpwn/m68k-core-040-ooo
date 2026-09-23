# Core pipeline stage timing: what limits each stage, and what can be folded

Measured on the routed 200 MHz release build (5.000 ns period, WNS +0.003), by
querying the checkpoint per stage rather than reading the summary report. Two
passes: the worst path into each stage's registers, and the worst path EXCLUDING
control-broadcast sources, so control-limited and datapath-limited stages can be
told apart.

## Answer up front: nothing folds at 200 MHz

| stage | ctrl slack | datapath slack | levels | datapath (ns) | true limiting source |
| --- | ---: | ---: | ---: | ---: | --- |
| IQ lsSkid | 0.440 | 0.524 | 8 | 4.260 | IQ `lines_0_ways_0_dynWait[14]` |
| LsEu P3 | 0.442 | 0.493 | 14 | 4.431 | Dcache `fsm_stateReg` |
| IQ readyReg | 0.318 | 0.416 | 10 | 4.348 | IQ `lines_0_ways_0_dynWait[14]` |
| IQ slot sel | 0.221 | 0.307 | 10 | 4.426 | IQ `lines_0_ways_0_dynWait[14]` |
| Dcache stS1 | -- | 0.305 | 8 | 4.418 | `tagMem` |
| LsEu S1 | 0.131 | 0.158 | 9 | 4.630 | Rob `exc_activeReg` |
| IQ triggers | 0.167 | 0.158 | 10 | 4.527 | IQ `lines_0_ways_0_dynWait[14]` |
| IQ dynWait | 0.157 | 0.099 | 15 | 4.835 | Dcache `fsm_stateReg` |
| LsEu completion | -- | 0.083 | 20 | 4.863 | AluEu `s1RdB` |
| Rob | 0.064 | 0.082 | 10 | 4.725 | Rob `exc_frameBase` |
| AluEu | 0.053 | 0.080 | 10 | 4.717 | Rob `exc_activeReg` |
| RegFileInt | 0.097 | 0.058 | 7 | 4.621 | Rob `exc_sysCapKind` |
| LsEu P4 | -- | 0.050 | 14 | 4.784 | LsEu `p3Ctx_paddr` |

Every stage consumes 4.26-4.86 ns of a 5.000 ns period ON ITS OWN DATAPATH.
Folding two stages sums their datapaths, and the cheapest pair in the load-to-use
loop (P3 + P4) is 4.431 + 4.784 = 9.2 ns. There is no fold headroom, and none
appears when control broadcasts are excluded.

A hypothesis this retires: "the stages look tight only because control broadcasts
dominate them, so relieving those would open fold headroom." Measured false. The
datapath-only slack is the same order as the control-limited slack and frequently
WORSE (IQ dynWait 0.099 vs 0.157, AluEu 0.080 vs 0.053, Rob 0.082 vs 0.064).

## What actually limits the core: internal control fan-out, not arithmetic

Nine of the thirteen stages are limited by a signal that is control state, not data:

* `RobPlugin exc_activeReg` limits AluEu (0.080) AND LsEu S1 (0.158); its siblings
  `exc_sysCapKind` and `exc_frameBase` limit RegFileInt (0.058) and Rob (0.082).
  The exception unit's state is broadcast into execution source registers across
  the core. This cone has form: the `ldo` payload-capture fix earlier in this
  campaign addressed a different part of the same unit and it was the routed WNS
  limiter at the time.
* ONE flop -- `lines_0_ways_0_dynWait_reg[14]`, a single bit of the OLDEST IQ
  slot's per-source wait vector -- is the limiting source for FOUR IQ stages
  (readyReg, lsSkid, slot sel, triggers). The oldest slot's readiness feeds the
  whole select-priority cone, so one bit gates the queue.
* `DcachePlugin fsm_stateReg` limits IQ dynWait and LsEu P3.

Two are genuine datapaths, and both matter for load-to-use and forwarding:

* LsEu P4, 14 levels, from `p3Ctx_paddr` -- the physical-address path.
* LsEu completion, 20 levels, from AluEu `s1RdB` -- the deepest cone in the core,
  and it is the FORWARDING path from an ALU source register into LS completion.

## Consequence for load-to-use

The 6.0-cycle dependent link is cmd(0) / rsp(1) / writeback + IQ dependency-clear
register(2) / IQ issue-select register(3) / S0(4) / S1(5) / next cmd(6). Removing
a stage requires the merged pair to fit 5.000 ns, and the table says the cheapest
pair is 9.2 ns. So load-to-use cannot be shortened by folding at this clock; it
needs either

1. the per-stage datapaths reduced (the control cones above are the largest single
   contributors, and `exc_activeReg` appears twice), or
2. more period, which means the area/hierarchy work -- `socket_core` is ~95k FLAT
   LUTs with no component boundaries, and three shallow stages carry only 3-4
   logic levels yet burn ~4.4 ns, i.e. ~4.2 ns of pure wire.

Ordering follows: relieve the control cones (`exc_activeReg` first -- it limits two
of the load-to-use stages), then re-measure, and only then consider a fold.

## Is NZVC affecting us? The file no; the flag COMPUTATION yes, it is the critical path

| structure | slack | levels | limiting source |
| --- | ---: | ---: | --- |
| RegFilePluginNzvc (the file) | 0.312 | 3 | boot reset, not a datapath |
| paths OUT of the NZVC file | none under 1.0 ns | | nothing reading flags is tight |
| LsEu NZVC logic | 0.083 | **20** | AluEu `s1RdB[17]` |
| AluEu NZVC logic | 0.301 | 17 | AluEu `s1Src1` |
| Rob NZVC logic | 0.148 | 15 | AluEu `s1Ctx_uop_op` |

The flag REGISTER FILE is clear -- zero endpoints under 1.0 ns are sourced from it,
despite costing 1,081 LUTs for a 4-bit payload (the W x R replication overhead
dominates a narrow file). What is tight is flag GENERATION: 15, 17 and 20 logic
levels, the deepest combinational cones in the core. The 20-level LS one at 0.083 ns
is the same path this document lists as "LsEu completion", so the CPU's critical
path is the LS-side NZVC computation for MOVE-to-memory.

There is precedent for the fix in the ALU: AluEuPlugin already notes a "24-level
s1Src2 -> NZVC cone, so it uses the retimed S1-through-S3 path". The ALU's flag cone
was split across three stages; the LS side still computes its flags in one shot at
completion (`moveNzvc(s1Data, size)` -> `storeNzvc` -> `compNzvc`). Retiming the LS
flag computation the way the ALU's already is would split the core's critical path.

## NEAR MISS: three bypasses that look dead, are not, and the gate does not cover them

Recorded because a green gate nearly justified deleting live forwarding paths.

The int-PRF bypass-liveness probe (`RegFilePlugin.bypLive`, sim-only) recorded ZERO
hits on both AluEu instances' SLOW-path bypass and on DivEu's, across four diverse
kernels (dependent-ALU, mixed, call-return, dhrystone). Marked `deadProbe = true`,
the FULL 396-test `test-fast` gate then passed with no assertion -- which reads as
proof they are dead.

They are not. `shift-stream` fires the slow-path int bypass 35 times
(eu0slowWrites=238, eu1slowWrites=123) and its NZVC/X siblings 16 and 15 times.
Neither the four-kernel sweep nor test-fast contains a dependent consumer behind a
SLOW-path ALU op, which is the only thing that bypass serves -- and AluEuPlugin's own
S1a-broadcast note says exactly that it must: "consumer's S0 read lands exactly on
S3, which is the cycle intByps forwards the result. Not early, not late."

Two conclusions:
  * Do not delete those bypasses. The comment is right and the probe was under-covered.
  * `test-fast` has a COVERAGE HOLE: no dependent edge behind a slow ALU op. A
    forwarding change validated only against it would pass while silently breaking
    every shift/rotate dependent edge -- the silent-wrong-value class RegFilePlugin's
    own precondition comment warns about.

The one bypass that WAS safely removed (the early-An write-back's, see
`perf(ls): drop the early-An bypass port`) rests on a different argument: its write
has latency 1 and the early wake plus the IQ's two registers put the consumer's read
at N+2, so the PRF read serves it -- and removing it was measured bit-identical on
every kernel, including the ones that exercise it.

## Does most code depend on NZVC? Measured: LS-produced flags are 94% dead

The question was whether timing on the flag path can be loosened because most
instructions do not consume flags. Measured with a sim-only bypass-liveness probe
(who WRITES flags) against the NZVC forwarding probe (whose flags are READ):

| kernel | LS flag writes | LS flags forwarded | never forwarded |
| --- | ---: | ---: | ---: |
| dhrystone-x0-cb | 4,096 | 256 | 94% |
| store-stream | 369 | 23 | 94% |
| load/store | 192 | 12 | 94% |
| branchy | 0 | 0 | -- |
| call-return | 0 | 0 | -- |

LS produces 36% of all flag writes on Dhrystone (every memory MOVE sets NZVC), but
94% are overwritten unread. On the BRANCH-heavy kernels LS-produced flags are
consumed ZERO times: every branch takes its flags from the ALU, whose cone is
already retimed across S1-S3.

So the premise holds, but the implementation is retiming, NOT a timing exception.
`set_multicycle_path` on the flag write would be unsound: the tool must assume the
earliest possible reader, and a dependent Bcc can be that reader. Retiming makes the
extra cycle REAL, and the IQ's existing `readsNzvc` wait bit plus the NZVC wakeup
then keep a consumer correct automatically -- it simply waits one cycle longer in
the rare case it exists.

The target is specific. The core's critical path is

    AluEu s1RdB_1_reg[17]  ->  LsEu compNzvc_reg[2]
    4.863 ns, 20 levels (CARRY8 x2 = the ALU's own adder), 70% route

i.e. ALU arithmetic + forwarding bypass + late store-data capture + `moveNzvc`, all
in ONE cycle. Splitting it costs one cycle of flag latency on move-to-memory ->
conditional-branch chains, which the table above says is close to free.

## BUT: the CPU is not the FMax limiter -- the L2 cache is

Before spending effort on any CPU path, note where the design's WNS actually is:

    u_l2c/g_active.u_ctrl/req_addr_reg[21]  ->  u_tags/g_way[6].mem_reg_bram_1/WEA[1]
    0.003 ns, 4.278 ns datapath, logic 0.820 (19%) route 3.458 (81%), 13 levels

The CPU's worst path is 0.046 ns. So improving ANY CPU path below 0.046 buys ZERO
design WNS -- the binding constraint is the L2 controller's tag write-enable cone,
which already carries its own FMax history (see l2c_ctrl.v's 2026-09-15 note on
`tag_match_c -> l2c_pri8 -> tw_en -> WEA`). Slack work starts there; CPU-path work
only pays once that is relieved, or as headroom for a future fold.

## Lever-by-lever disposition

Every lever this analysis identified, with what was done and -- where not -- the
specific blocking reason rather than a judgement call.

IMPLEMENTED (all gate-clean at 396 succeeded / 0 failed / 2 ignored):

| lever | result |
| --- | --- |
| IQ: load may pass an older unready LOAD | +7.5% calibrated Dhrystone, seed-robust |
| LS: early An write-back at S1 (auto-update store, stack push, LEA) | +34.1% call/return, +33.1% copy-dense |
| Both wired into SocketTop's throughput-v2 | they were NOT reaching the SoC before |
| SoC: registered CPU reset (`cpu_rst_core`) | 6,324 endpoints off a sub-0.5 ns constraint |
| LS: drop the early-An PRF bypass port | forwarding mux 7 -> 6 sources, bit-identical IPC |

NOT IMPLEMENTED, each with a specific reason:

* **L2C tag write-enable (the design's actual WNS limiter, 0.003 ns).** The cone
  walks 72 CLB rows -- SLICE_X76Y35 (req_addr) through X72Y51/55/73, X74Y79,
  X72Y82, X58Y107 to RAMB36_X4Y21 -- so it is placement spread, not RTL depth, and
  pinning only the tag BRAMs would not fix the logic spread between. Pblocks were
  already evaluated and rejected on this design (2026-09-16), and l2c_ctrl.v carries
  two rounds of prior FMax work on this exact cone (see its 2026-09-15 note on
  `tag_match_c -> l2c_pri8 -> tw_en -> WEA`, and the one-level WEA decode).

* **LS NZVC retiming (the CPU's critical path, 20 levels).** Sound in principle --
  94% of LS-produced flags are never forwarded -- but `compNzvc` has SIX write sites
  and feeds `wbObs.nzvc`, the lockstep commit observation. Retiming it requires
  re-aligning the whitebox capture, and getting that subtly wrong produces silent
  lockstep drift. Not a change to make without dedicated validation.

* **`exc_activeReg` fan-out** (limits AluEu 0.080 and LsEu S1 0.158). Already
  replicated by the tool -- the routed path starts at `exc_activeReg_reg_rep__1` --
  so the residual is route distance, not fanout. Registering it at consumers is a
  correctness change to a squash level, not a timing transform.

* **The oldest IQ slot's `dynWait[14]`** (limits four IQ stages). The bit is FP_B,
  i.e. an FP dependency bit sitting in the readiness cone of every slot even on
  integer code -- but the readiness NOR is ~2 levels; the depth is the 16-slot
  select priority cone behind it, which prior work already narrowed ~7x.

* **`moveNzvc` depth.** Already optimal shape: the three zero-tests are computed in
  parallel and muxed by size, ~3 levels of the 20.

The ordering that follows: CPU-path work cannot move design WNS until the L2C cone
is relieved (CPU worst 0.046 vs design 0.003), and the L2C cone is a placement
problem whose obvious remedy is already on the rejected list. That makes the
area/hierarchy work -- `socket_core` at ~95k FLAT LUTs with no component boundaries
-- the gating item for every remaining slack lever, not an alternative to them.
