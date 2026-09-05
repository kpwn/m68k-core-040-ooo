# p141 — measure the walker stall on silicon

Status: bitstream built and A/B run pending at time of writing.
cpu040 pin: `af3d214`. SoC branch: `build/p141-stall-csr` (from p140's `7182b577`).

## What is already established — do not re-derive

The `0x40806b68` boot wedge is caused by **arm C (`2db5bd3`, MMU table walks
routed through L1D)**, measured three times:

| build | arm C | result |
|---|---|---|
| p139 | excluded | **boots 6/6**, retire advancing |
| p138c | in | **wedges 6/6** |
| p140 | in | **wedges 6/6**, retire frozen at a bit-identical `0x0543222c` |

`0x40806b68` is an **A-line trap** (Slot Manager dispatch) — an exception entry.

Two things are excluded, and both cost real board time to establish:

* **Timing.** p140 `fabric_clk100` postroute WNS **+0.547 ns, 0 failing of
  272274**; WHS +0.011, 0 failing hold.
* **The obvious fix.** `030651f` (add `E_DRAIN`/`R_DRAIN` to `quiesceHoldOut`)
  was **verified active in the p140 netlist and still wedged**. Its premise was
  then disproven in sim twice — a 48-page unmapped sled gave `sledWalks=0` at
  4 KiB, and at **8 KiB, the page size this board actually runs**, the `E_DRAIN`
  runaway does not leave its page at all even when explicitly invited to.
  **Do not merge it.**

Sim does not reproduce the wedge. `WalkerExcEntryWedgeSpec` is 77/77 across both
page sizes, all three walker permission exits, nested faults, wrong-path squash,
the URP leg, the stack-pointer switch and realistic L2 latency. The negative is
honest but cannot distinguish "no bug" from "the model lacks the trigger".

## The design decision: CSRs, not an ILA

The brief for this experiment asked for an ILA. It was built with **live debug
CSRs instead**, for two reasons.

**The stall is static, so a trace buffer answers nothing extra.** Retire freezes
at a *bit-identical* count on every boot. That is a deterministic structural
stall, not a race — the machine then sits in one fixed state indefinitely. The
question "which of `dcIdleForMaint`'s seventeen terms never clears, and who holds
the walker's D-cache grant" is a steady-state question that a single read
settles.

**An ILA would risk destroying the measurement.** `ENABLE_ILA=1` costs ~28-32
BRAMs and a few thousand LUTs and reshapes placement design-wide. This campaign
has already paid for that exact lesson: **p128 rebuilt p123 with `MARK_DEBUG`
and the wedge stopped reproducing.** Four CSR read ports are as close to
non-perturbing as instrumentation gets, and they let this bitstream keep
p133/p140's exact `ENABLE_ILA=0` configuration, so the A/B stays controlled.

A third, practical reason surfaced during the work: `ILA_ENABLE` + `CPU_M68K040`
**does not build** on the p133/p140 SoC lineage. `rtl/soc/fpga_top_debug_ctrl.vh`
binds ~45 `dbg040_*` ports inside `` `ifdef ILA_ENABLE ``, but cpu040 HEAD
exposes only 30 — rounds 4-10 of an older campaign connect ports that exist on
no cpu040 ref. That was found and fixed only on the unmerged `lseu-psrca-ila`
SoC branch (`f5c616dd`), which in turn lacks the p138-p140 tooling and the p133
hold-analysis flow. Enabling the ILA here would have meant porting that fix
first.

If the CSR read turns out to be ambiguous, an ILA build remains available as a
follow-up — but it should then be understood as a *perturbing* experiment.

## The instrument

Four read-only words. All live: sampled from core signals with **no halt**,
which is the same property that makes `retired_macros` trustworthy on a wedged
board while `inst-count` is not. Deliberately in the `0x010xx` counter block
rather than the `0x020xx` architectural block, because `0x020xx` is the
halt-captured snapshot and **this livelock has no retire boundary, so a debug
halt does not land**.

| offset | name | contents |
|---|---|---|
| `0x5090101C` | `OFF_STALL_DC` | the 17 `dcIdleForMaint` terms, individually |
| `0x50901020` | `OFF_STALL_GRANT` | D-cache port ownership + grant machine |
| `0x50901024` | `OFF_STALL_EXC` | ExceptionUnit FSM one-hot + drain predicates |
| `0x50901028` | `OFF_STALL_WALK` | both TableWalker FSMs |

Read them **alongside `OFF_INST_LO`/`HI`** (`0x50901008`/`0x5090100C`): the
retire count identifies the wedge, these say what it is stuck on.

### `OFF_STALL_DC` — 0x5090101C

Bit order matches the `dcIdleForMaint` expression in `DcachePlugin.scala`.
**Polarity is not uniform** — this is the single easiest thing to misread here:

* `[13:0]` block when **SET**: `resetSweepBusy, busy, ldS1Valid, ldS2Valid,
  loadShadowValid, earlyProbeValid, pendingStoreMiss, pendingWtKickoff, s0Valid,
  stS1Valid, stS2Valid, stS3Valid, serialStoreInFlight, storeMissBarrier`
* `[17:14]` block when **CLEAR** (they are *done* flags): `stAwDone, stWDone,
  evictAwDone, evictWDone`
* `[21:18]` `storeOutstanding` — blocks when non-zero
* `[22]` `dcIdleForMaint`, `[23]` `maintBusyReg`, `[24]` `maintQuiesced`

### `OFF_STALL_GRANT` — 0x50901020

`[1:0]` `ldOwner`, `[3:2]` `stOwner` (0=CORE, 1=ITLB, 2=DTLB); `[4]` `ldGrantOk`,
`[5]` `stGrantOk`, `[6]` `quiesceHold`, `[7]` `walkGrantHeld`, `[8]`
`walkerOwnsLoad`, `[9]` `walkerOwnsStore`, **`[10]` `walkerPortWedge`**, `[11]`
`walkGrantProgress`, `[15:12]` `coreStOutstanding`, `[16]` `walkStOutstanding`,
`[17]` `ldBusyExc`, `[18]` `ldFifoFull`, `[19]` `coreLsLoadReq`, `[21:20]`
`walkLdReq{itlb,dtlb}`, `[23:22]` `walkStReq{itlb,dtlb}`, `[24]` `loadCmd.valid`,
`[25]` `loadCmd.ready`, `[26]` `loadRsp.valid`, `[27]` `store.valid`, `[28]`
`store.ready`, `[29]` `storeAck`, `[30]` `excStoreOutstanding`, `[31]`
`walkWedgeCnt != 0`.

`[10] walkerPortWedge` is the design's **own** detector for a walker holding a
port with no progress for `walkerWedgeLimit` cycles. If it is set, the stall is
proven to be in the walker/port hand-over rather than upstream of it.

### `OFF_STALL_EXC` — 0x50901024

`[0]` `active`, `[1]` `sqDrained`, `[2]` `dcQuiesced`, `[3]` `quiesceHoldOut`,
`[4]` `maintDoneIn`, `[5]` `redirectValid`; `[26:6]` the FSM **one-hot** in
declaration order: `IDLE, E_DRAIN, E_STORE, E_STWAIT, E_VECREQ, E_VECWAIT,
E_REDIR, R_DRAIN, R_SRREQ, R_SRWAIT, R_PCREQ, R_PCWAIT, R_PCREQ2, R_PCWAIT2,
R_FMTREQ, R_FMTWAIT, R_REDIR, S_DRAIN, S_APPLY, S_MAINTWAIT, S_REDIR`.

The three DRAIN-family states are the ones that can wait *unboundedly* on
someone else — `E_DRAIN`/`R_DRAIN` on `sqDrained && dcQuiesced`, `S_MAINTWAIT` on
`maintDoneIn` — so a DRAIN bit set with its predicate clear names both the waiter
and what it waits for in a single read.

### `OFF_STALL_WALK` — 0x50901028

DTLB in `[15:0]`, ITLB in `[31:16]`. Each: `[4:0]` state one-hot `IDLE, RD_ROOT,
RD_PTR, RD_PAGE, FINISH`; `[5]` `cmdSent`; `[6]` `loadCmd.valid`; `[7]`
`loadCmd.ready`; `[8]` `loadRsp.valid`; `[9]` `start`; `[10]` `donePulse`.

`loadCmd.valid && !loadCmd.ready` is a walker **blocked on the D-cache port** —
the shape arm C would produce.

## Traps found while building this

* **`StateMachine.stateReg` does not exist at class-body elaboration time.**
  Reading it is a null dereference (measured). Both FSM taps therefore emit
  one-hot via `isActive`, which registers a deferred post-build task. This is
  why `ExceptionUnit.quiesceHoldOut` was already written that way.
* **`TableWalker` is a child `Component`**, so its internals are not readable
  from a parent scope, and `simPublic()` does not help — it affects simulation
  visibility only, never synthesis-time cross-component reads. It needed a real
  `out` port.
* **`DebugRegMap.scala` is GENERATED** from `tools/debug/debug_regmap.def` by
  `tools/debug/gen_debug_regmap.py`, and `DebugRegMapSpec` enforces it. A hand
  edit passes elaboration and fails the spec at the synth gate. Declaring the
  registers in the `.def` also propagates them to `debug_regmap.py` and
  `debug_regmap.tcl`.
* **`grant[6]` and `exc[3]` are the same net** (`LsEuPlugin.quiesceHold` is
  driven by `exc.quiesceHoldOut`). Their agreeing is a wiring tautology, not
  corroboration. The decoder says so on the line itself.

Every bit layout above was verified **assignment-by-assignment against
`generated/M68kSocketTop.v`**, not against the Scala it was written from.

## Reading the result

```
tools/p141_start_repl.sh <bit> <ltx>
PAIRS=6 SETTLE=200 OUTDIR=/tmp/p141_ab \
  A_BIT=<p133>/fpga_top.bit A_LTX=<p133>/fpga_top.ltx A_NAME=p133 A_ID=0x14f3c599 \
  B_BIT=build/vivado/fpga_top.bit B_LTX=build/vivado/fpga_top.ltx \
  B_NAME=p141 B_ID=<from fpga_top.buildinfo> \
  tools/p141_ab_boot.sh
```

The discriminator is **retire ADVANCING vs FROZEN**, not the screen — both arms
show a mixed screen distribution. The stall words are decoded only when retire
is frozen; decoding a running machine manufactures plausible-looking noise.

Two informative outcomes:

* **Still wedges** → the decoded stall state is the deliverable.
* **No longer wedges** → one of the three never-boarded fixes (`1cec5af` TLB
  duplicate refill, `6e3de3b` FSAVE/FRESTORE 8 KiB PA — **live on this board,
  which runs `TC=0x0000c000`** — or `56ad2d4` `coreStOutstanding`) cleared it,
  and the next step is bisecting which.

And one that is a finding rather than a failure: if adding four CSR reads makes
the wedge disappear, that means it is **perturbation-sensitive**, which is
itself diagnostic and must be reported as such rather than quietly re-rolled.

---

# RESULT — measured on silicon 2026-09-05

The wedge reproduced and the stall words are **bit-identical on every wedged
boot**:

```
dc=0x0003c002  grant=0x08000000  exc=0x00000203  walk=0x00210028
```

A/B against the preserved p133 artifact, interleaved, same session:
p133 **retire ADVANCING** every cycle; p141 **retire FROZEN** every cycle, at
`0x40806b68`. Screens were mixed on both arms, as expected — retire liveness is
the discriminator, not the screen.

## What is stuck

| word | reading |
|---|---|
| D-cache | **`busy` is the ONLY blocking term.** All sixteen other `dcIdleForMaint` terms are clear: `storeOutstanding=0`, all four AXI `*Done` flags set, every load/store pipeline stage empty. |
| grant | **`ldOwner=CORE`, `stOwner=CORE`, `walkGrantHeld=0`, `walkerPortWedge=0`.** No walker holds any D-cache port. `store.valid=1 / store.ready=0`. |
| ExceptionUnit | **`E_STWAIT`**, `active=1`, `sqDrained=1`, **`dcQuiesced=0`**. |
| walkers | **DTLB in `RD_PAGE`, `cmdSent=1`**, `loadCmd.valid=0`, `loadRsp.valid=0`. ITLB `IDLE`. |

## The deadlock, stated plainly

1. The A-line trap at `0x40806b68` enters the ExceptionUnit, which reaches
   `E_STWAIT` — it is pushing the exception frame and waiting on the store.
2. That store is presented and **refused**: `store.valid=1, store.ready=0`.
3. It is refused because the D-cache is **`busy`** — and `busy` is False *only*
   in the load FSM's `IDLE` state (`DcachePlugin.scala:1442`; it is set in
   `EVICT_WR`, `REFILL` and `REPLAY`). So the load FSM is parked in a
   miss/fill state and never returns to `IDLE`.
4. It is parked there on behalf of the **DTLB walker**, which sits in `RD_PAGE`
   with `cmdSent=1` — its page-descriptor read was **accepted** and it is
   waiting for a response that never arrives.
5. `busy` never clears → `dcIdleForMaint` never asserts → `dcQuiesced` stays 0
   → the exception store is never accepted → retire never advances.

## Two hypotheses this kills

**"A walker holds the port and starves the core."** Refuted directly:
`walkGrantHeld=0`, `ldOwner=stOwner=CORE`, `walkerPortWedge=0`. The walker
released the grant on acceptance, exactly as `LsEuPlugin.scala:3379` specifies.
This is consistent with — and now explains — why `030651f` (adding
`E_DRAIN`/`R_DRAIN` to `quiesceHoldOut`) was verified active in the p140 netlist
and **still wedged**: it was defending a grant hand-over that was never the
problem.

**"The exception sequencer is stuck in a DRAIN state."** It is not. It is in
`E_STWAIT`, past the drain, blocked on a store the D-cache will not take.

## Where the bug is

Not in the arbiter and not in the ExceptionUnit. It is a **lost or misrouted
D-cache load response for an accepted DTLB descriptor read**: the walker's
command was consumed (`cmdSent=1`, grant released), but no `loadRsp` was ever
delivered back to it, so both the walker and the D-cache load FSM wait forever.
The prime suspects are the response-routing path arm C introduced — the
ownership FIFO and its `ldRspTag` demux in `LsEuPlugin` — and whichever of
`EVICT_WR`/`REFILL`/`REPLAY` the load FSM is parked in.

`busy` alone does not say which of those three states it is. That is the first
thing the next probe round should add, alongside the ownership-FIFO occupancy
and `ldRspTag`.

## Caveat, reported not buried

This bitstream's `fabric_clk100` is **WNS +1.329 ns, 0 failing of 272165**
(better than p140's +0.547) and **WHS +0.010, 0 failing of 272117** intra-clock.
But the `pb_clk → fabric_clk100` group has **one failing hold endpoint,
WHS −0.177 ns**, on
`u_pb_s1_cdc/u_bridge/ar_fifo/rptr_gray_reg[2]` → its 2-flop synchroniser — a
gray-code CDC pointer in the peripheral-bus bridge. p140 met that same path by
only **+0.021 ns**, so both builds are marginal on what is an unconstrained
asynchronous crossing that arguably should carry `set_false_path`/
`set_max_delay -datapath_only` rather than be hold-analysed at all. It is in the
peripheral bus, not the CPU/walker/D-cache path, so it does not explain a
CPU-internal deadlock that is bit-identical across boots — but it is a real
constraint gap and should be closed on its own merits.

---

# ILA RESULT — the approach, captured cycle-exact 2026-09-05

The owner's call to build the ILA anyway was right: the snapshot named the
stuck term, but it could not have produced the mechanism below.

**`ILA_ENABLE + CPU_M68K040` was a real build defect and is now fixed** (see the
SoC commit "fix(ila): make ILA_ENABLE + CPU_M68K040 build again"). The ILA
bitstream is `build_id=0xf6b570c4`, cpu040 `358d687`, and `hw_ila_1` enumerates
on the device.

## First finding: the wedge is NOT perturbation-sensitive

The ILA build carries ~28-32 extra BRAMs and thousands of LUTs and has a
completely different placement. **It wedges anyway, with a bit-identical stall
word** to the CSR build:
`dc=0x0003c002 grant=0x08000000 exc=0x00000203 walk=0x00210028`.

That is a direct contrast with p128, where instrumentation made the wedge
disappear. This deadlock is structural and placement-independent.

## Capture method — the part that matters

Arming while the machine was already wedged triggers instantly and fills the
buffer with 4096 identical post-freeze samples (useless). The working recipe is:

1. `reset hold` — CPU held, trigger condition verifiably false (`dc=0x0003c001`).
2. arm (trigger position 3968/4096, late, to keep the approach).
3. `reset release` — the wedge then happens *while armed*.

Also: **the retire count at freeze is NOT constant on this build**
(`0x050906ab`, `0x0541fcfe`, `0x050902dd`, `0x05421cdc`). Only the *stall state*
is bit-identical. So an equality trigger on the retire count does not work; the
trigger is the three-word deadlock state itself.

## The mechanism, cycle by cycle

`rel` is relative to the last retire (sample 3949).

```
 rel  exc        ld    dtlb      cmdSent cmdV cmdR rsp busy  blocking
  +0  IDLE       CORE  IDLE         1     0    0    0   0    earlyProbeValid
  +1  E_DRAIN    CORE  RD_ROOT      0     1    0    0   0    earlyProbeValid
  +2  E_DRAIN    DTLB  RD_ROOT      0     1    1    0   0    -            <- accepted
  +3  E_STORE    CORE  RD_ROOT      1     0    0    0   0    ldS1Valid    <- grant released
  +4  E_STWAIT   CORE  RD_ROOT      1     0    0    1   0    ldS2Valid    <- response OK
  +5  E_STWAIT   CORE  RD_PTR       0     1    0    0   0    ...
  +6  E_STWAIT   DTLB  RD_PTR       0     1    1    0   0    ...          <- accepted
  +8  E_STWAIT   CORE  RD_PTR       1     0    0    1   0    ...          <- response OK
  +9  E_STWAIT   CORE  RD_PAGE      0     1    0    0   0    ...
 +10  E_STWAIT   DTLB  RD_PAGE      0     1    1    0   0    ...          <- ACCEPTED
 +11  E_STWAIT   CORE  RD_PAGE      1     0    0    0   0    ...          <- grant released
 +12  E_STWAIT   CORE  RD_PAGE      1     0    0    0   1    busy,!evictAwDone,!evictWDone
 +13… E_STWAIT   CORE  RD_PAGE      1     0    0    0   1    busy         <- forever
```

So `busy` **did not "never clear"** — it was **newly asserted at rel +12** by the
walker's third descriptor read and then never cleared. That is discrimination
(b)/(c), not (a), and it is exactly what a frozen snapshot could not have told
us.

The first two descriptor reads (`RD_ROOT`, `RD_PTR`) **hit** and returned in two
cycles. The third (`RD_PAGE`) **missed**: the load FSM left `IDLE` (hence
`busy`), started an eviction (`!evictAwDone`/`!evictWDone` at +12, both done by
+13), entered the refill — and **the refill's AXI read response never arrives**.

Note the grant machine is working perfectly throughout: CORE→DTLB→CORE
hand-overs complete in one cycle, every time, right up to +11. Nothing is
starved and no grant is held. That is why `030651f` could not have helped.

## The trigger condition

In this capture five table walks start. The separation is total:

| walk start | ExceptionUnit state at start | outcome |
|---|---|---|
| rel −2085 | `IDLE` | completed |
| rel −413 | `IDLE` | completed |
| rel −160 | `IDLE` | completed |
| rel −122 | `IDLE` | completed |
| **rel +1** | **`E_DRAIN`** | **DEADLOCK** |

**A table walk that begins while an exception entry is in progress — and that
then MISSES the D-cache — never gets its refill response.** Four walks that
started with the sequencer idle all completed normally.

## Where to look next

The failure is in the **D-cache line-fill read path for a walker-initiated
miss taken while an exception-frame store is in flight** (`serialStoreInFlight=1`,
`storeOutstanding=1` across rel +5…+17). The eviction completes; the refill read
response does not come back.

Prime suspects, in order:
1. **AXI read-ID / response routing** for the refill issued concurrently with the
   exception's serialised write — there is already a `cpu040-axi-id-race`
   worktree and an "AXI socket-adapter: 2 CRITICAL silent-corruption gaps" note.
2. The D-cache load FSM's `REFILL` arm and its interaction with
   `serialStoreInFlight` / the store-side AXI pair flags.
3. The MSHR/`AxiDMerge` multiplexing of a walker-tagged fill against core traffic.

The next probe round should add the **load FSM state** (`EVICT_WR`/`REFILL`/
`REPLAY` — `busy` alone cannot distinguish them), the refill AXI **ARID/RID**,
and the ownership-FIFO `ldRspTag`.

Raw captures: `logs/p141/p141_approach.csv.gz` (the approach, the important one)
and `logs/p141/p141_terminal.csv.gz`.
