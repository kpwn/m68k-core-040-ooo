# Locality over global state — the 200 MHz cone family

Owner directive (2026-09-13): *"we would like our core to be a collection of
interacting highly local machines, not a mess of global state and round trips"*,
and *"some cold paths may be kicked off immediately rather than waiting for the hot
one to complete, and hot success kills the effects of the cold path down the line."*

## The measurement that motivates it

The 200 MHz failing cones are **not deep logic**. Judge a cone by its logic/route
split before touching it:

| cone | levels | route % |
|---|---|---|
| `exc_fsm_stateReg -> RobPlugin sysValStore_61[14]/CE` | 6 | **88.5%** (4.990 ns route vs 0.647 ns logic) |
| `DcachePlugin loadShadowValid -> maint_lastSet[1]/CE` | 11 | **84%** |
| `RobPlugin completes -> ... -> IQ triggers[7]/CE` | 27 | **80.3%** |
| `exc fsFrameBase -> Dtlb missReqReg_write/CE` | 16 | 67% |

A path that is 85% wire cannot be fixed by adding or removing logic. It is a
**locality** problem: one LUT output crossing the die to many loads, or a
combinational round trip between two distant plugins in a single cycle.

## The three shapes, and what to do about each

**(A) Raw combinational service export.** A `override def` that returns an
expression over internal state rather than a register. Fixed: `maintQuiesced` was
`dcIdleForMaint && !maintBusyReg` — 17 state bits — and is now a flop
(`DcachePlugin.maintQuiescedReg`). An audit of all 259 `override def` found no
other real offender; the remaining raw exports are the `rob.logic.X` / `exc.Y`
direct reach-ins in `FullCoreSynth` that bypass the service traits.

**(B) Combinational round trip.** A -> B -> A in one cycle. The confirmed case was
`DcachePlugin.maintQuiesced -> ExceptionUnit S_DRAIN -> DcachePlugin maintCmd.valid
-> maintenance FSM register CEs`. Registering the *export* broke the loop; the
remaining one-way leg is not in the top 50.

**(C) Cross-plugin broadcast into a register array.** A decode of one plugin's
state fanning out to hundreds of clock enables elsewhere. `exc.active` was a LUT
output with **fanout 113**, reaching (via `pipeFlush` -> AluEu `fastFire` ->
`ccrObs.valid`) the CEs of the ROB's 64-entry `sysValStore`/`nzvcValStore`/
`xValStore` — 192 flops.

## Two transforms, and the difference between them

**Safe: restage a pure STATE DECODE through a flop.**
```scala
active := RegNext(fsm.stateNext =/= fsm.enumOf(fsm.IDLE)) init False
```
Cycle-identical by construction, since `stateReg == RegNext(stateNext)`. Nothing
observable moves, and the result is a *register* the placer can replicate per
consumer region — which a LUT already placed with its loads spread cannot be.

**Risky: staging a HANDSHAKE.** `4b00cccb` applied the same idea to
`maintCmd.valid` and was reverted by `68c778b6` because it regressed `DcacheSpec`:
delaying a `valid` moves an accept/backpressure window. **State decode: yes.
Handshake: no**, unless you have re-argued the protocol.

## Prefer a local self-check to a global precondition

The D-cache maintenance `WAIT` state re-tests the LOCAL `dcIdleForMaint` before
touching the arrays. That is what makes an early or stale command safe, and it is
why registering the remote precondition costs nothing. Copy this: let the cold path
be kicked off from registered inputs and have the consumer's own local check hold
or kill it, rather than gating the cold register's CE on the hot combinational
verdict computed in another plugin.

`SpeculativeFetchGate.scala:80-127` and `FetchAlignPlugin.scala:116-133` are the
house style to imitate: export the NEXT value, register it at the destination, fan
out locally, and write down the soundness argument.

## OPEN — the largest cone, and the trap in its obvious fix

`rob.logic.interruptPending || rob.logic.tracePendingFire` -> `lsEu.
irqPreemptPendingIn` (`FullCoreSynth.scala:298`) is the core's worst path family:
**-1.834 ns, 27 levels, 80.3% route**, Rob -> LsEu -> IQ in one cycle. It conjoins
two 64-entry async head muxes (`faultedStore(h0)`, the `p0` payload Mem read) plus
`headReady`.

The obvious fix is to export a shallow SUPERSET, e.g.
`irqPreemptArmed = iplActive || tracePendingReg`. The domination holds — both
`interruptPending` and `tracePendingFire` conjoin their respective term — and the
IPC cost looks free, because `irqPreemptPendingIn` is consumed ONLY in the
`p4Inhibited` arm of `p4LaunchOk`, so ordinary cacheable loads never see it.

**Do not implement it on that argument alone.** The termination story ("a parked
load doesn't retire, so the interrupt gets taken, so the gate clears") assumes the
interrupt can be RECOGNIZED while the load is parked. Recognition needs
`normalIrqGate`, which requires `p0.first`. If an inhibited load sits at the ROB
head as a NON-FIRST uop of a cracked instruction, the superset suppresses its
launch, the instruction never completes, the head never reaches a `first` uop,
`normalIrqGate` never asserts and `iplActive` never clears: **livelock**. The exact
`interruptPending` is immune precisely because it conjoins `normalIrqGate`.

### RESOLVED: it can, so the naive superset is UNSOUND

`MicroOpAssembler.scala:124-126` settles it in its own words:

> *"every later move + the final An update is non-first, so an interrupt is only
> taken at the MOVEM boundary (never mid-emission -- the partly-emitted moves would
> otherwise be re-run after RTE since they share the MOVEM pc)"*

A MOVEM emits ONE `first` uop (the snapshot, line 235) followed by many non-first
move uops (line 127) and a non-first trailing An update (line 176). So a
**MOVEM against cache-inhibited space parks non-first inhibited LOAD uops at the
ROB head** -- and the Q700 ROM does exactly that on device registers. `iplActive`
high during such a MOVEM would suppress every remaining element forever. Livelock
is reachable, not theoretical. Do not ship `iplActive || tracePendingReg`.

### The formulation that IS sound

    irqPreemptArmed = iplActive && (p0.first || stopped)

*Dominates.* `interruptPending = (normalIrqGate || stopped) && ... && iplActive`,
and `normalIrqGate` conjoins `p0.first`; the `stopped` disjunct is carried
explicitly. `tracePendingFire` conjoins `traceNormalGate`, which is the same
expression as `normalIrqGate`, so it is dominated too (add `tracePendingReg` if the
trace half is to be covered without its gate).

*Preserves progress*, which is the property the naive version loses. When the head
is a NON-first uop the gate is off, so the parked MOVEM element launches and the
macro advances to a `first` boundary where the interrupt can be recognized. When
the head IS `first`, every remaining term that could block recognition is
self-clearing: `faultedStore(h0)` -> faultRetire; `privViolation` -> exception;
`isRte`/`sysOp` -> not a load, so no inhibited load is parked; `preciseDrainBusyIn`
/ `inhibitedLoadBusyIn` -> bounded transactions; `!excIdle`/`flushing` ->
transient; `coreHalted` -> nothing should progress by design.

*And it is still shallow.* It drops the 64:1 `faultedStore(h0)` mux, `headReady`
(which reads `completes(h0)`), `privViolation`, `!flushing`, `excIdle`,
`!coreHalted`, and both `preciseDrainBusyIn`/`inhibitedLoadBusyIn` -- the last of
which also breaks the Rob->LsEu->Rob ring that is currently closed by a single flop
(`loadBusyReg`). What remains is `iplActive` (two registers and a 3-bit compare)
and one bit of the head payload Mem read.

NOT YET IMPLEMENTED. It changes a liveness-critical gate, so it wants the fuzz and
lockstep suites behind it, plus a directed test that runs a MOVEM against inhibited
space with an interrupt held pending.
