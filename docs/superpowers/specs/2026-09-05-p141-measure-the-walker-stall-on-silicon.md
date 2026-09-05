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
