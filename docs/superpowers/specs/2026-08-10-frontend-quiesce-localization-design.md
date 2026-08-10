# Cycle-exact frontend quiesce localization

**Status:** binding amendment for the STOP/fatal-halt frontend contract

**Date:** 2026-08-10

**Physical input:** `cc22cd0`, generated-netlist MD5
`9ceebf788ec66393a3458eecafb4d255`

## 1. Scope and reason

This amendment preserves the architectural STOP and fatal diagnostic-halt
behavior while terminating their control path at a register local to
FetchAlign.  It supersedes only the implementation detail in the historical
STOP and D-cache recovery plans that directly wires:

```text
FetchAlign.quiesce := Rob.stopped || Rob.coreHalted
```

The architectural behavior remains fixed:

- supervisor `STOP` retires alone, applies its SR value, records the successor
  PC, then prevents all later fetch, decode feed, and retire;
- an eligible interrupt wakes `STOP` and redirects to the interrupt handler;
- fatal `coreHalted` prevents fetch, feed, and retire and does not wake for an
  interrupt;
- already accepted I-cache traffic may finish and must drain without feeding
  stale bytes while quiesced.

The `cc22cd0` default-decode route is clean but has WNS -3.326 ns
(136.500 MHz), TNS -43,658.238 ns, and 46,278 failing setup endpoints.  Every
routed top-100 path starts at `RobPlugin_logic_coreHalted_reg/C` and crosses
FetchAlign/I-cache control; the worst ends at `IcachePlugin_logic_lineReg[152]/CE`,
is 7.220 ns / 23 levels, and is 70.25% routing.  The direct registered halt level
must therefore stop at one frontend register rather than enter the live
predictor/ITLB/cache command cone.

## 2. Ownership and service boundary

Add a plain directionless `FrontendQuiesceService` to the service catalog:

```scala
trait FrontendQuiesceService {
  def active: Bool
  def next: Bool
}
```

`RobPlugin` is the sole producer.  No `Global` key is added.  FetchAlign consumes
the service through `host[FrontendQuiesceService]`; it never reaches into
`RobPlugin.logic`.

Both service wires are allocated during ROB `setup` and driven during `build`,
using the existing `PrivilegeService`/`CacheControlService` pattern.  This is
load-bearing: ROB build depends on rename, decode, and FetchAlign, so resolving a
wire that is allocated only inside ROB build would recreate a Fiber dependency
cycle.

`active` is the architecturally visible current halt state and is used for
verification/observation.  `next` is the exact combinational next-state value
captured at the frontend boundary.  It is not permission to create another halt
state owner.

Standalone FetchAlign DUTs that contain no `FrontendQuiesceService` use a
constant-false `next` value.  Every full-core composition contains `RobPlugin`
and therefore uses the real service automatically; no duplicated top/harness
wire is allowed.

## 3. Exact next-state contract

Name the existing ROB decisions without changing their priority:

```text
stopEnter = sysTriggerSig && p0.sysKind == STOP
stoppedNext = (stopped || stopEnter) && !interruptPending
fatalNext = coreHalted || coreHaltedIn

FrontendQuiesceService.active = stopped || coreHalted
FrontendQuiesceService.next   = stoppedNext || fatalNext
```

The expression matches the actual last-assignment priorities:

1. STOP may set `stopped`;
2. a same-cycle recognized interrupt clears `stopped` and wins;
3. `coreHaltedIn` sets the sticky fatal state, which has no clear path.

FetchAlign owns exactly one local register:

```text
fetchQuiesced(C+1) = FrontendQuiesceService.next(C)
```

with reset value false.  By induction, after every active edge:

```text
fetchQuiesced == FrontendQuiesceService.active
```

because ROB's two source registers and the frontend register are loaded from the
same next-state truth in the same cycle.  A simulation assertion must enforce
this equality.

A plain `RegNext(active)` is forbidden.  It asserts one cycle late, permitting
one fetch command or decode feed after the ROB-visible halt state becomes true.
If the exact next-state expression cannot be preserved, the only acceptable
fallback is assert-exact and clear-late (one wake bubble), never assert-late.

## 4. Functional use

Every existing FetchAlign `quiesce` condition uses `fetchQuiesced`, including:

- FTB application veto and blocked telemetry;
- I-cache command validity and ring allocation;
- BTB/gshare prediction enables;
- decode feed and synthetic fetch-fault emission;
- FTQ starvation/mismatch detection and recovery qualification.

Quiescence is a hard veto, not the registered-redirect kill-after-apply class.
There is no same-edge FTQ/ring flush dedicated to STOP that could safely discard
a physical application later.

Quiescence must not gate the I-cache response path, ring response accounting, or
stale-response retirement.  Requests accepted before halt continue to complete
and drain while no bytes reach decode.  Interrupt wake/redirect then clears the
old speculative stream through the established redirect machinery.

The public/internal name `quiesce` may remain as the local register for test
visibility, but it is no longer a sibling-driven input and must not use
`allowOverride`.

## 5. Required verification

The implementation is not accepted by static equality alone.  Tests must expose
real command/feed handshakes and cover:

1. **STOP entry:** on the first edge that makes `stopped` visible, local quiesce
   is also true and neither `ic.cmd.fire` nor `feed.fire` occurs.
2. **Interrupt wake:** local quiesce clears on the same edge as `stopped`; the
   interrupt redirect target is the first later accepted fetch.
3. **Fatal entry:** a one-cycle `coreHaltedIn` pulse makes `coreHalted` and local
   quiesce true together; neither later interrupt activity nor input deassertion
   clears either.
4. **Live plan collision:** a valid fixed-C+1 FTB/gshare result on the halt edge
   produces no application, FTQ push, target hold, cache command, or feed.
5. **Drain while halted:** an I-cache response accepted before halt still retires
   its ring slot; no deadlock, duplicate, bytes, or synthetic fault escapes.
6. **Backpressure:** exercise both ready-high and ready-low command postures.
7. **Protocol assertion:** after reset, `fetchQuiesced == service.active` every
   cycle.

At least one test must fail under the deliberate mutation
`fetchQuiesced := RegNext(service.active)` by observing the first-cycle leaked
command/feed.  Merely testing eventual quiescence is vacuous.

The focused frontend/STOP tests and mandatory
`make SBT=~/sbt/bin/sbt test-fast` must pass before physical measurement.

## 6. Physical and area acceptance

Expected hardware is one frontend flip-flop plus the setup-allocated service
wires; the old direct top-level net and its replicated control cone are removed.
`active` is verification-only in the frontend and should not survive as a new
functional fanout.  No BRAM, DSP, CAM, cache port, queue entry, or architectural
latency is added.

Generate a fresh full-core netlist and archive its MD5, Verilog, synth/routed
DCPs, logs, and reports.  At the 4.000 ns target report:

- synthesis and route WNS;
- routed TNS and failing endpoints;
- hold/pulse-width status;
- LUT, FF, BRAM, and DSP delta;
- whether every `coreHalted -> frontend` top-100 path is gone;
- the newly exposed top-path family, logic levels, and routing fraction;
- decode-pblock occupancy under the unchanged default-decode floorplan.

The cut remains valuable if it removes its measured family and exposes another
limiter even when it does not close 250 MHz alone.  A later regression must be
diagnosed from the new cone and area evidence rather than by reflexively
restoring the proven remote halt path.
