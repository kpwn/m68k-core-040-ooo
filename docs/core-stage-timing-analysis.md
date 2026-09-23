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
