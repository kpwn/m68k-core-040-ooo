# BUG: a load parked on the inhibited-store barrier launches to the D-cache with a
# STALE "no forward" verdict -- returns the previous generation of a just-pushed slot

**Status**: FIXED 2026-09-08 (branch `fix/lsu-stale-fwd-verdict-inhibited-barrier`).
Sim-reproduced on the unfixed RTL (`ExecuteLockStepSpec` "stale-fwd[...]" tests),
value-for-value the hardware symptom; fixed RTL passes the same tests with the
scenario still exercised. Hardware confirmation PENDS a SoC rebuild with the cpu040
pin bumped past the fix (no board access from this session).

## Hardware symptom (measured on the Quadra 700 SoC, 2026-09-08)

ROM helper `0x408990E2: move.l (sp),d0` (Translate24 wrapper), SP=0x3FF1C2, called by
`jsr %pc@(d16)` at `0x40898E98` from the SCSI COMMAND-phase handler right after the
53C96 FIFO fill loop `0x40898E90: move.b (a2)+,0x20(a3) ; dbf d2,loop`. On call #154
(the 150th disk command) the load returned **D0 = 0x8CF88000**, an OLDER image of the
slot, while coherent memory held the just-pushed RA `0x40898E9C`. No interrupt in the
window. Downstream: Translate24 -> `rts` into memory-test filler -> vector 4 -> bomb.

## Mechanism (CONFIRMED in simulation, file:line against `555dc2c8`)

1. The FIFO stores are cache-INHIBITED (serialized device) stores; each is a PRECISE
   store that drains only at the ROB head and pops on its AXI B response
   (`StoreQueue.scala:915-960`).
2. The `jsr` push is a COPYBACK "fast" store (`LsEuPlugin.scala:951`,
   `fastStore = mmuEnable && cacheable`): it allocates in the SQ and completes at once.
3. The helper load queries the SQ while the last FIFO store is still resident. In the
   forward cone, `serialStall = anyOlderInhibitedStore` (`StoreQueue.scala:794`) masks
   the exact-match `hit` (`:797`) but `stall` is NOT raised because the match is full
   (`:809`, `stall := (anyPartial || anySameLine) && !fullValid`). Verdict captured at
   p3->p4: `hit=0, stall=0` (`LsEuPlugin.scala:2887-2889`).
4. p4 sees neither `fullForward` nor `mustRetry` and drops into the launch arm, parking
   on `p4LaunchOk = !sq.io.barrier.olderInhibitedStore` (`LsEuPlugin.scala:2512`,
   `:2532`). While parked it NEVER re-queries: `p4RetryQuery` (`:1379`) covers only
   stall / split-hit, so the query mux keeps serving p3.
5. The FIFO store's B arrives -> it pops -> the barrier lifts the same cycle -> the load
   launches to the D-cache (`alignedEnq`) with the stale `hit=0` while the push is still
   in the ring, uncommitted (the `jsr` retires after the FIFO store). The cache/DRAM
   returns the slot's previous generation. The `rts`'s own pop of the same slot, a few
   cycles later, gets a correct SQ forward (the barrier is gone), so only D0 is wrong.

Sim evidence (unfixed RTL, `scratchpad/stalefwd_unfixed_final2.log`):
`slot load rob=41 completed with data=0x8cf88000 <-- the slot's OLDER generation`,
`rob=43` (the rts pop) `0x408000ac`, lock-step `Divergence(52, ccr: dut=0x08
oracle=0x00)` at exactly the helper load (N set = negative old image). Reproduces for
COPYBACK with the line evicted, COPYBACK with the line resident-dirty, and WRITE-THROUGH,
at device-store B latency 5 and 60 cycles.

## Why only 1 call in 154 on hardware (measured in sim, not conjecture)

Three independent things normally keep the load from reaching its SQ query before the
last FIFO store drains; the corrupting call is the one where none of them applies:

- **Cold `jsr`**: the BTB is indexed by the jsr's own PC and trained at retire; an
  unpredicted `jsr` mispredicts and is recovered by a COMMIT-TIME redirect
  (`RobPlugin.scala:1850`), which is ordered behind the precise store's B response
  (measured: redirect 2 cycles after the store's completion). Warm on every ROM call
  after the first.
- **Loop-exit `dbf` mispredict**: same commit-time recovery, same ordering. The ROM's
  10-iteration loop is protected on every call where the exit is mispredicted; a
  history predictor occasionally gets the exit right -- that is the exposed call.
- **MMU off** makes every store precise (`fastStore=false`), deferring the push's A7
  write-back until its own drain; the board runs with the MMU on (TC=0xC000).

## Fix

`SqFwdRsp.serial` (the `serialStall` term) is exported alongside `hit/stall`; p4
captures it as `fwdSerial` at both capture sites and treats a serial-masked verdict as
provisional: `mustRetry`/`p4RetryQuery` include `fwdSerial`, so the load re-queries
every cycle (the query mux selects p4) until a verdict is captured with the barrier
absent, then forwards or launches on THAT verdict. The wait releases on exactly the
predicate the launch gate already waited on (`anyOlderInhibitedStore` ==
`barrier.olderInhibitedStore` for the same robId), so no new wait condition exists;
an INHIBITED load re-queries until `anyOlder` clears, which is the `olderStore` term
its launch gate already required.

## Regression tests

`ExecuteLockStepSpec`: `stale-fwd[b5]`, `stale-fwd[b60]` (COPYBACK, line evicted),
`stale-fwd[b60] ... COPYBACK, line resident`, `... WRITE-THROUGH, line evicted`, and
the literal `dbf` loop control. Each lock-steps against Musashi and asserts (a) the
load actually parked on the barrier (non-vacuity), (b) no slot load completed with
the old image, (c) no load of the slot launched to the cache while its producing
store was resident. Verified FAILING on `555dc2c8` (all five) and PASSING with the
fix (all five, still parking 8-60 cycles on the barrier).

Harness traps found on the way (all load-bearing for the reproduction):
`cpusha` is mis-sized by Musashi (no CPUSH handler) -- evict by four same-set
write-allocating stores instead; the slot must be inside one 16-byte line (a
`...BE` slot is a split access, a different SQ arm); the jsr must be the SAME
instruction executed twice (a priming call through a different jsr warms nothing).
