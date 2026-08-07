# FMax closure, Slice 2: split D-cache LOAD S1 into S1a/S1b (design)

## Context

Slice 1 (commit `9d2f6a7`+`f85c355`+`fa4924e`, landed) registered the
front-end's `p0Live` live-reclassify, moving post-route FMax from
146.11 MHz to 167.029 MHz (WNS -2.844ns -> -1.987ns). The new worst
post-route path (`synth/impl_FullCore.tcl`) is:

```
DcachePlugin_logic_tagMem_2_reg/CLKARDCLK -> LsEuPlugin_logic_compData_reg[8]/D
(-1.987ns, 13 logic levels, 63% route)
```

tied almost exactly with a separate front-end path (Slice 3, covered by its
own design spec) at -1.986ns. **Both must land before the next post-route
gate shows real combined improvement** — closing only one leaves the other
as the new worst path at effectively the same delay.

This spec covers ONLY the D-cache path. A research pass (grounding fork,
this session) traced it source-to-source; independently re-verified against
live `DcachePlugin.scala`/`LsEuPlugin.scala` before writing this spec.

**USER DECISION (explicit, asked before starting this slice):** this fix
requires a genuine new pipeline stage costing **one uniform extra cycle of
load-to-use latency on every D-cache load hit** (not a rare-case cost like
Slice 1's ambiguous-EA stall). User confirmed: proceed. This file has 4
prior FMax splits with the identical latency-for-FMax tradeoff (see
`DcachePlugin.scala`'s own header comment, lines 26-34, and `LsEuPlugin.scala`
lines ~625, ~429, ~765-788, ~677-688) — this would be a 5th, following the
exact established pattern.

## The cone (source-cited, current HEAD)

`DcachePlugin.scala`:
- `tagMem`/`dataMem` are `Mem(...).readSync` per way (lines 85-112) — simple
  dual-port BRAM, one write + one sync-read port shared between load and
  store-RMW reads. `rdTag`/`rdData` (lines 111-112) are the BRAM's own
  output registers — genuinely registered, this is NOT the problem.
- S1 tag-compare (lines 269-276): for each way `w`,
  `ldS1HitVec(w) := ldS1Cacheable && valids(w)(ldS1Set) && (rdTag(w) === ldS1Tag)`
  (both operands already registered: `rdTag` is the BRAM output reg,
  `ldS1Tag`/`ldS1Set`/`ldS1Cmode` are plain `Reg`s latched at accept time,
  line 654-660). `ldS1Hit = ldS1HitVec.orR` (275); `ldS1HitWay =
  OHToUInt(ldS1HitVec)` (276) — cheap, ~3-5 logic levels total.
- Way-select (line 280): `ldS1Line = rdData(ldS1HitWay)` — a dynamic 4:1
  128-bit mux keyed off the just-computed hit-way index. ~2 levels.
- Response build (lines 306-311):
  ```scala
  loadRspPort.valid         := ldS1Resp || busFaultResp || inhibitedResp
  loadRspPort.payload.data  := Mux(inhibitedResp,
                                    DcacheByteLane.extract(missLine, missOff, missSize),
                                    DcacheByteLane.extract(ldS1Line, ldS1Off, ldS1Size))
  loadRspPort.payload.line  := Mux(inhibitedResp, missLine, ldS1Line)
  loadRspPort.payload.fault := Mux(busFaultResp, True, ldS1Fault)
  ```
  where `ldS1Resp = ldS1Valid && ldS1Hit` (line 287).
  `DcacheByteLane.extract` (`DcacheTypes.scala:131`) is 4x 16:1 dynamic
  byte-lane muxes then a 3-way size-select switch — the single deepest
  segment, ~6 levels.

`LsEuPlugin.scala`:
- `captureCompletion` (lines 822-837), called from `WAIT.whenIsActive` (line
  1451-1452) the same cycle `dcache.loadRsp.valid` pulses:
  `compData := Mux(u1.leaAddr, ..., Mux(u1.stkPush, ..., Mux(isAutoStoreAn,
  ..., ldResult)))` where `ldResult = Mux(movea&&WORD,
  signExtend(result(15 downto 0)), result)` — 4 chained Mux levels, register
  write is `compData` (line 692).

Full chain: tag-compare(~3) -> OHToUInt encode(~2) -> way-mux(~2) ->
byte-lane extract+size-select(~6) -> completion Mux/sign-extend(~4) ~= 13
levels — one flat combinational block, **no register between the BRAM
output and `compData`**. This is the same *shape* as Slice 1's `p0Live`
problem (one long unbroken cone into a register), but NOT the same *fix*:
there is no defeated/bypassed register here to cheaply reconnect.
`DcachePlugin.scala`'s own header comment (lines 33-34) already states
S1 is the terminal cycle of D-cache access — there is no idle earlier slot
to move this work into. The only way to shorten it is a genuinely new
register boundary, hence the accepted latency cost.

**Confirmed NOT in this cone:** DTLB/MMU translation (`ldS1Tag` is captured
from `xlate.rsp` one cycle earlier, at accept time, lines 654-660 — already
registered before this cone starts).

**Confirmed downstream fan-in is narrow:** grepped every use of
`ldS1Hit`/`ldS1HitVec`/`ldS1HitWay`/`ldS1Line`/`ldS1Resp` in
`DcachePlugin.scala`. Only two consumers exist:
1. The response build above (lines 306-311) — the thing we're delaying.
2. The miss/REFILL trigger, `when(ldS1Valid && !ldS1Hit)` (line 666) — uses
   **only `ldS1Hit`** (the cheap compare+orR result), never `ldS1HitWay` or
   `ldS1Line`. The victim-eviction capture in that same block (lines
   671-703, `victimEvictTag := rdTag(vw)` etc.) indexes by `vw =
   victim(ldS1Set)` — an independent round-robin counter, **not**
   `ldS1HitWay`. **The miss/eviction/refill FSM does not depend on anything
   this slice delays.** This is the key fact that keeps the fix narrowly
   scoped: only the HIT *response data path* moves by one cycle; hit/miss
   *detection* and the whole REFILL/EVICT_WR/REPLAY machinery are
   untouched.

**Confirmed the LsEuPlugin consumer is already latency-agnostic:**
`WAIT.whenIsActive { ...; when(dcache.loadRsp.valid) { ... } }` — `loadRsp`
is a `Flow`; `WAIT` simply holds until `.valid` pulses, however many cycles
that takes. **No LsEuPlugin.scala change is required** — this mirrors
exactly how `busFaultResp`/`inhibitedResp` (REPLAY-driven pulses, already
several cycles later than the ordinary hit path) are consumed today without
any special-casing on the LsEuPlugin side.

## The fix: split S1 into S1a (compare+way-select) / S1b (extract+respond)

Add one new register stage in `DcachePlugin.scala`, downstream of the
existing S1 tag-compare/way-select and upstream of the byte-lane extract:

```scala
// ---- LOAD S2 (registered post-hit-detect response build) ----
// FMax closure Slice 2: the old S1 response build (way-select -> byte-lane
// extract -> loadRspPort) was one flat ~13-level combinational cone from the
// BRAM tag-read output straight into LsEuPlugin's compData register — see
// docs/superpowers/specs/2026-08-07-fmax-slice2-dcache-s1-split-design.md.
// S1's hit/miss DECISION (ldS1Hit, used only by the miss/REFILL trigger at
// line 666) is untouched -- only the HIT RESPONSE DATA moves one cycle
// later. Costs one uniform extra cycle of load-to-use latency on every
// cache hit (LsEuPlugin's WAIT state is already Flow.valid-driven / latency-
// agnostic -- no consumer-side change needed).
val ldS2Valid = RegInit(False)
val ldS2Hit   = Reg(Bool())
val ldS2Line  = Reg(Bits(128 bits))
val ldS2Off   = Reg(UInt(offBits bits))
val ldS2Size  = Reg(Size())
val ldS2Fault = Reg(Bool())
ldS2Valid := ldS1Valid
ldS2Hit   := ldS1Hit
ldS2Line  := ldS1Line
ldS2Off   := ldS1Off
ldS2Size  := ldS1Size
ldS2Fault := ldS1Fault

val ldS2Resp = ldS2Valid && ldS2Hit
loadRspPort.valid         := ldS2Resp || busFaultResp || inhibitedResp
loadRspPort.payload.data  := Mux(inhibitedResp,
                                  DcacheByteLane.extract(missLine, missOff, missSize),
                                  DcacheByteLane.extract(ldS2Line, ldS2Off, ldS2Size))
loadRspPort.payload.line  := Mux(inhibitedResp, missLine, ldS2Line)
loadRspPort.payload.fault := Mux(busFaultResp, True, ldS2Fault)
```

This REPLACES the existing lines 306-311 (which drove `loadRspPort` straight
off `ldS1*`) — the `ldS1Hit`/miss-trigger use at line 666 is NOT touched;
`ldS1HitWay`/`ldS1Line` themselves are also left in place (still computed in
S1, now consumed by the new `ldS2Line :=` capture instead of directly by
`loadRspPort`).

### `inFlight` / single-outstanding semantics — CONSERVATIVE choice, locked

`inFlight = ldS1Valid` (line 621) currently gates `loadCmdPort.ready` (line
647), enforcing the D-cache's existing single-outstanding-load contract: no
new load is accepted until the in-flight one's S1 has resolved.

**Decision (deliberately conservative, to bound this slice's risk on a
heavily-hardened, many-times-fixed FSM):** extend this to
`inFlight = ldS1Valid || ldS2Valid`, preserving the EXACT single-outstanding
invariant — a load now simply takes 3 cycles (S0 accept / S1 compare+select
/ S2 extract+respond) instead of 2, with **no other FSM/arbitration
behavior change**. A more aggressive pipelined variant (allow a new load's
S1 to run concurrently with the previous load's S2, since S2 no longer
touches the shared BRAM read port or AXI channels and could in principle
overlap) is NOT part of this slice — flagged as a possible follow-on for
more throughput, explicitly out of scope here. The conservative choice
matches exactly what was asked for in the go-ahead ("uniform +1 cycle load
latency"), not an implicit throughput change.

### `dcIdleForMaint` — REQUIRED change, conservative parity

`dcIdleForMaint` (line 1092, gates when a CACHE MAINTENANCE walk — CPUSH/
CINV — may start) already lists `!ldS1Valid` as one of its "every D-cache
datapath resource, genuinely idle" conjuncts, with its own comment (lines
1086-1091) noting that a PRIOR review (P5.4) found a live-combinational-term
omission here was a real bug (`!storePort.valid` had to be added, not just
belt-and-braces). Following that same standard of caution: **extend this to
`!ldS1Valid && !ldS2Valid`.** Rationale: while S2 does not itself contend
for the shared BRAM port or AXI channels, `dcIdleForMaint`'s documented
intent is "every load/store pipeline stage fully drained," and a maintenance
walk's tag/valid/dirty invalidation could otherwise race a still-in-flight
S2 response for the same set/way (the S2 register's DATA is a frozen
snapshot so this is not a data-corruption risk, but preserving "nothing is
still resolving anywhere in the load pipe" as an exact invariant costs at
most one extra cycle of walk-start delay — maintenance walks are already
rare, ROB-serialized events per the `S_DRAIN -> S_APPLY -> S_MAINTWAIT`
sequencer cited in the surrounding comment, lines 1079-1082). Do not ship
this slice with `dcIdleForMaint` reading "idle" while `ldS2Valid` is set.

## Non-goals / explicitly out of scope

- The `busFaultResp`/`inhibitedResp` REPLAY-driven response pulses — these
  are already several cycles removed from any ordinary-hit critical path
  (fired from within the `REPLAY` FSM state, ~line 908-936) and are NOT part
  of the -1.987ns cone. Leave them exactly as-is (still Mux'd into
  `loadRspPort` alongside the new `ldS2Resp`, same priority/shape as today).
- A pipelined (S1/S2-overlapping) variant for extra load throughput — see
  "CONSERVATIVE choice, locked" above.
- Any change to the STORE pipeline (S0/S1/S2 write-through/copyback RMW) —
  untouched; it shares the read port but not this response-build cone.
- Any change to `LsEuPlugin.scala` — confirmed unnecessary (see "Confirmed
  the LsEuPlugin consumer is already latency-agnostic" above). If the
  implementation finds this assumption wrong, STOP and escalate rather than
  improvising a fix — this is a hard-won, load-bearing claim, not a guess.

## Verification requirements (binding)

- `~/sbt/bin/sbt compile` clean.
- **Named whitebox regression, explicitly (not just "part of the suite")**:
  `pendingStoreMiss.simPublic()`'s consumer — "Fable5 Bug1 regression test"
  (`DcachePlugin.scala:174`, "times a racing load to land exactly on the
  IDLE cycle [a store-drain miss] is picked up"). Find this test (grep the
  test tree for `pendingStoreMiss` or the Fable5/Bug1 naming) and confirm it
  still passes. This is the single test in this file most likely to be
  timing-sensitive to the `inFlight` extension above — a load's port-freeing
  point shifts by one cycle on a hit, which could shift (not eliminate) the
  exact race window this test targets. Report explicitly whether the timing
  offset in the test changed or the test was insensitive to it.
- `DcacheDrainRefillRaceSpec` (referenced at `DcachePlugin.scala:128`,
  "same-way-collision coincidence check" via `victimWay.simPublic()`) and
  any other directed `Dcache*Spec` tests in `src/test/scala/m68k040/cache/`
  (or wherever they live — locate at implementation time): full pass,
  zero new failures.
- A NEW directed test exercising the added latency directly: a load that
  hits, confirming `loadRspPort.valid` now pulses on cycle accept+3 (not
  the old accept+2) via a cycle-counted whitebox check — mirrors this
  file's own established style of `simPublic()`-based directed tests. Also
  confirm a MISS still transitions to `EVICT_WR`/`REFILL` on the same cycle
  as before (accept+2), i.e., miss-detection timing is provably unchanged.
- `ExecuteLockStepSpec` full suite: expect 390/394 (same 4 standing
  pre-existing failures as Slice 1's baseline — do not accept a different
  count/list without investigating).
- Full ported test corpus (~870 tests, via `tools/fuzz/ported-sweep-parallel.sh`
  in isolated git worktrees): zero new regressions vs. the current baseline
  fail list (post-Slice-1: 812 pass / 58 fail, see
  `.superpowers/sdd/progress-fmax-p0live.md`).
- OOC-synth-only gate AND the real post-route gate
  (`synth/impl_FullCore.tcl`). Report the measured FMax delta explicitly.
  Success criterion for THIS slice alone: the `DcachePlugin_logic_tagMem_2_reg
  -> LsEuPlugin_logic_compData_reg` path family should disappear from the
  worst-8 post-route paths (causal confirmation, same standard as Slice 1's
  attribution check) — the ABSOLUTE FMax number may not move much on its
  own if Slice 3's front-end path is still unlanded and becomes the new
  bottleneck (expected, per the "both must land together" framing above).
  If Slice 3 has already landed by the time this is measured, report the
  COMBINED number.

## Open questions for the plan-writing pass

- Confirm the exact live line numbers for every citation above (this
  project's own established convention — line numbers drift across
  sessions).
- Locate the exact test file(s) for the "Fable5 Bug1 regression test" and
  any other `Dcache*Spec` files, by grep, before writing the verification
  task steps (do not guess paths).
- Confirm `Size()` (used for `ldS2Size`) and `CacheMode` (not needed here,
  `ldS1Cmode` doesn't flow into the new register) import/type shapes match
  `DcachePlugin.scala`'s existing style exactly.
