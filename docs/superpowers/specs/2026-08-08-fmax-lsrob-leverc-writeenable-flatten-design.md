# FMax closure, "LS/ROB Lever C": hoist a per-entry write-select for the ROB fault register file (design)

**Date:** 2026-08-08
**Repo:** `/home/qwertyoruiop/m68k-core-040-ooo`, branch `feat/rob-predictor-mem`
**Status:** design spec. No RTL changed by this pass. Every number below is read
from a real routed netlist (`open_checkpoint` + `report_timing`/`get_nets`/
`get_pins`, read-only); no RTL story is presented as a conclusion without a
matching netlist measurement — the explicit lesson of
`docs/superpowers/specs/2026-08-07-fmax-slice3-frontend-dsteashift-collapse-design.md`'s
"POST-IMPLEMENTATION CORRECTIONS".

---

## 0. Checkpoint provenance (read before trusting any number)

| item | value |
|---|---|
| checkpoint | `synth/fullcore_routed.dcp` |
| checkpoint mtime | `2026-08-08 08:59` |
| netlist input | `generated/M68kFullCoreSynth.v`, mtime `2026-08-08 08:40` |
| repo HEAD at that time | `d357f85` — **includes both round-2 levers**, `2426dbd` (Frontend Lever A) and `8d2b538` (LS/ROB Lever A) |
| re-measured post-route WNS (this pass) | **-1.693 ns** (TNS -12789.770, 23 526 failing endpoints of 145 624) |
| post-route FMax | **175.65 MHz** |
| current HEAD (`a2733dc`) vs checkpoint | `fb17694`/`d2b5264`/`a2733dc` are **docs-only** commits — the checkpoint is still netlist-accurate for HEAD |

Probe scripts (read-only, ~1 min each, `open_checkpoint` only — no synthesis,
no re-implementation, no repo file modified): `probeC.tcl`, `probeC2.tcl` in
`…/374c5f2c-…/scratchpad/`; raw outputs `vC_summary.rpt`, `vC_slack400.rpt`,
`vC_tagmem_to_faultaddrCE.rpt`, `vC_tagmem_to_allfault.rpt`,
`vC_tagmem_to_completes.rpt`, `probeC.out`, `probeC2.out`.

---

## 1. The target family, re-measured on the CURRENT checkpoint

400-path survey (`vC_slack400.rpt`), collapsed per start/end register bank:

| # | family | worst | paths /400 |
|---|---|---:|---:|
| 1 | `ibuf entries_*_pred_lenWords -> DecodeStage fed_payload_specs_*_dstEa_*` | **-1.693** | ~70 |
| 2 | `DecodeStage ucPendPkt_words_0 -> FetchAlign decodePc/fetchPc/headPtr/ucRomMem` | **-1.624** | ~110 |
| 3 | **`DcachePlugin tagMem -> RobPlugin fault*Store`** | **-1.521** | **217** |

Family 3's own sub-breakdown (all from `DcachePlugin_logic_tagMem_0_reg`):

```
-1.521   10 paths  -> RobPlugin_logic_faultAtcStore_9_reg/CE      <- family worst
-1.521    7 paths  -> RobPlugin_logic_faultWrStore_*/CE
-1.485    4 paths  -> RobPlugin_logic_faultInstrStore_*/CE
-1.437  164 paths  -> RobPlugin_logic_faultAddrStore_*_reg[*]/CE
-1.418    6 paths  -> RobPlugin_logic_faultedStore_*/CE
-1.360   15 paths  -> RobPlugin_logic_faultSizeStore_*/CE
-1.352    5 paths  -> RobPlugin_logic_faultSupStore_*/CE
-1.345    6 paths  -> RobPlugin_logic_faultVecStore_*/CE
```

**Every endpoint in the family is a `/CE` pin.** No `/D` endpoint of any
`fault*Store` array reaches the worst 400 — a change from the pre-round-2
picture (the original grounding report saw `/D` siblings at comparable slack on
the then-dominant `eaAuto` source family, which LS/ROB Lever A has since
removed). This matters: **the write-ENABLE decode is now the whole of the ROB
side of this family**, which is exactly the surface Lever C was scoped against.

---

## 2. Cell-by-cell trace of the family's worst path (-1.521 ns)

`report_timing -from {*DcachePlugin_logic_tagMem_*_reg*/CLKARDCLK} -to {*RobPlugin_logic_fault*Store_*_reg*/CE|/D}`
(`vC_tagmem_to_allfault.rpt`, path 1):

```
Slack (VIOLATED) : -1.521 ns
  Source:      DcachePlugin_logic_tagMem_0_reg/CLKARDCLK      (RAMB18E2, RAMB18_X5Y77, pb_dcache)
  Destination: RobPlugin_logic_faultAtcStore_9_reg/CE         (FDPE,     SLICE_X76Y136)
  Data Path Delay: 5.370 ns  (logic 1.846 ns / 34.4%   route 3.524 ns / 65.6%)
  Logic Levels: 11  (CARRY8=1 LUT2=1 LUT3=2 LUT4=1 LUT5=3 LUT6=3)
```

| # | arrival | cell | out-net (fanout) | RTL meaning |
|---|---|---|---|---|
| — | 0.076 | `DcachePlugin_logic_tagMem_0_reg` RAMB18E2 | | tag BRAM |
| 1 | 1.490 | LUT6 `…axi_r_ready_INST_0_i_27` | fo=1 | way-0 tag compare (part) |
| 2 | 1.673 | CARRY8 `…_i_15` | fo=3 | tag compare carry |
| 3 | 2.064 | LUT2 `…_i_11` | `DcachePlugin_logic_stS2HitVec_0` fo=15 | store-S2 hit vector |
| 4 | 2.409 | LUT6 `…_i_5` | `_zz_DcachePlugin_logic_refillWriteHold` fo=1 | refill-vs-store hold |
| 5 | 2.484 | LUT4 `…_i_1` | `when_DcachePlugin_l991` fo=60 | store-S2 ack condition |
| 6 | 2.758 | LUT6 `LsEuPlugin_logic_pendReady[2]_i_3` | `RobPlugin_logic_exc_dcStoreAck` fo=30 | D-cache store ack |
| 7 | 2.911 | LUT5 `LsEuPlugin_logic_pendReady[2]_i_1` | **`sq_io_sqCompletion_valid`** fo=66 | `StoreQueue.io.sqCompletion.valid` |
| 8 | 3.464 | LUT5 `LsEuPlugin_logic_compData[31]_i_9` | **`when_LsEuPlugin_l1667`** fo=4 | `when(!applyFault)` — `LsEuPlugin.scala:1667` |
| 9 | 3.825 | LUT3 `RobPlugin_logic_faultSupStore_0_i_7` (INIT=`8'hEF`) | **fo=2083, +0.671 ns** | `= !sqFaultCompletionPort.valid` (see §3) |
| 10 | 4.586 | LUT3 `RobPlugin_logic_faultSupStore_9_i_3` (INIT=`8'h01`) | fo=10, +0.366 ns | entry-9 sq write term |
| 11 | 4.988 | LUT5 `RobPlugin_logic_faultSupStore_9_i_1` | `RobPlugin_logic_faultAtcStore_9` fo=3, +0.458 ns | entry-9 union write-enable |
| — | 5.446 | → `RobPlugin_logic_faultAtcStore_9_reg/CE` | | |

**Delay budget of the -1.521 ns path:**

| segment | Δ | share | levels |
|---|---:|---:|---:|
| tagMem → `sq_io_sqCompletion_valid` (D-cache store-S2 + SQ drain-ack cone) | 2.835 ns | **52.8 %** | 7 |
| `sqCompletion_valid` → `when_LsEuPlugin_l1667` (deferred-replay arbitration) | 0.553 ns | 10.3 % | 1 |
| **`when_l1667` → CE (ROB fault write-enable decode)** | **1.711 ns** | **31.9 %** | **3** |

The original grounding report measured this same shared ROB tail at
**1.936 ns / 34.5 %** on the pre-round-2 checkpoint. It is now
**1.711 ns / 31.9 %** — i.e. **still a comparable fraction, confirmed, and still
the single largest bounded block of the path outside the D-cache itself.**

The 164-path `faultAddrStore_*/CE` sub-family (-1.437 ns) has the identical head
and an identical 3-level ROB tail (`when_l1667` at 3.735 → CE at 5.361 =
**1.626 ns**), through the same fo=2083 net (`vC_tagmem_to_faultaddrCE.rpt`).

---

## 3. What the ROB tail actually *is* — and the correction to the original Lever C framing

Decoding the three tail LUTs from their `INIT` masks and pin lists
(`probeC.out`, "PATH CELLS" section):

* **Level 9**, LUT3 `faultSupStore_0_i_7`, `INIT=8'hEF`, inputs
  `when_LsEuPlugin_l1667` (fo=4), `pendApply[2]_i_4_n_0`, `pendApply[2]_i_3_n_0`
  ⇒ `O = !(applyFault && (applyFast||applyBacklog) && !liveCompletionFires)`
  = **`!sqFaultCompletionPort.valid`** (`LsEuPlugin.scala:1663`, `:1689`). One
  **global** node, broadcast at **fanout 2083**.
* **Level 10**, LUT3 `faultSupStore_9_i_3`, `INIT=8'h01` (3-input NOR), inputs
  = the level-9 net + `faultSupStore_0_i_8_n_0` (fo=230, the `robId[5:3]` group
  compare) + `faultSupStore_9_i_6_n_0` (fo=34, the entry-9 low-bit compare)
  ⇒ `O = sqFaultCompletion.valid && (robId === 9)` — **the per-entry sq select**.
* **Level 11**, LUT5/LUT6 per entry — for `faultAddrStore_4` the terminal LUT6
  `INIT=64'hFFFFFFFEFFFEFFFE` decodes to
  `en₄ = alloc0Sel₄ | euSel₄ | alloc1Sel₄ | sqSel₄ | (lsFaultCompletion.valid & lsOh₄)`
  — the 5-port union enable, driving the entry's CE net (fo=37).

So the decode is **already 3 levels and already one-hot**: SpinalHDL emits the
one-hot itself — `generated/M68kFullCoreSynth.v:211230` / `:211238` /
`:211247` / `:211260` / `:211269-70`:

```verilog
assign _zz_119 = ({63'd0,1'b1} <<< RobPlugin_logic_lsFaultCompletion_payload_robId);
assign _zz_127 = ({63'd0,1'b1} <<< RobPlugin_logic_sqFaultCompletion_payload_robId);
assign _zz_136 = ({63'd0,1'b1} <<< RobPlugin_logic_euFaultCompletion_payload_robId);
assign _zz_147 = ({63'd0,1'b1} <<< RobPlugin_logic_tail);
assign _zz_155 = ({63'd0,1'b1} <<< (RobPlugin_logic_tail + 6'h01));
…
if(_zz_127[5]) begin RobPlugin_logic_faultAddrStore_5 <= RobPlugin_logic_sqFaultCompletion_payload_faultAddr; end
```

**This is a material correction to the original grounding report's Lever C
sketch.** That sketch proposed "a one-hot pre-decode of each write port's
`robId`… collapses levels". Both halves are wrong on the current netlist:

1. The one-hot pre-decode **already exists** (Spinal generates `1 <<< robId`,
   not 64 `robId === i` compares) — proposing it is a no-op.
2. There are **no levels left to collapse**: the tail is already 3 LUTs, and
   its logic delay is only **0.216 ns of 1.711 ns (12.6 %)**. Route is
   **1.495 ns (87.4 %)**.

**The ROB tail is a FANOUT problem, not a logic-depth problem.** A "flattening"
that only removes LUT levels can recover at most ~0.1 ns. The recoverable prize
is the 1.495 ns of route, and route here is fanout-driven.

### 3.1 Where the fanout-2083 goes — decomposed on the netlist

`get_pins -of [get_nets …faultSupStore_0_i_7_n_0]`, load cells grouped by array
(`probeC2.tcl`, `probeC2.out`):

```
NET  = LsEuPlugin_logic_sq/RobPlugin_logic_faultSupStore_0_i_7_n_0   FO = 2084
LOAD PINS = 2083
  LOADGRP RobPlugin_logic_faultAddrStore = 2019     (97.0 %)
  LOADGRP RobPlugin_logic_faultSupStore  =   64     ( 3.0 %)
```

Array flop counts on the same checkpoint:
`faultAddrStore` **2048** (64×32), `faultVecStore` 320, `faultSizeStore` 128,
`faultWrStore`/`faultSupStore`/`faultAtcStore`/`faultInstrStore`/`faultedStore`
64 each.

**Reading:** the late `sqFaultCompletion.valid` node is consumed 64 times by the
per-entry *enable* decode (the thing on the critical path) and **2019 times by
the 64 × 32 `faultAddrStore` per-bit DATA multiplexers** — which are *not* on
this path at all. The enable path pays a 0.671 ns route penalty it does not
cause: it is sharing a driver with a 2019-load data-mux array.

`faultSupStore_0_i_7_n_0` is one of only three non-global nets in the entire
design with fanout > 1000 (`probeC2.out`: excluding `clk`/`reset`/`<const*>`/
BRAM `WCLK`, the list is `LsEuPlugin_logic_sq_n_200` fo=1079,
`_n_201` fo=1078, `_zz_IssueQueuePlugin_logic_readyReg_1` fo=5295,
`when_PipeStage_l17_1` fo=1125 — plus this one at 2084).

### 3.2 Empirical fanout → routed-delay curve for THIS placement

Every `net (fo=N, routed) D` line in the worst-400 report (6166 samples,
`vC_slack400.rpt`):

| fanout bucket | n | mean route delay | max |
|---|---:|---:|---:|
| 1-4 | 3043 | 0.151 ns | 0.573 |
| 5-16 | 1113 | 0.257 ns | 0.887 |
| 17-48 | 772 | 0.221 ns | 0.760 |
| 49-100 | 777 | **0.300 ns** | 0.722 |
| 101-200 | 254 | **0.295 ns** | 0.531 |
| 201-500 | 3 | 0.342 ns | 0.342 |
| **> 1500** | **204** | **0.742 ns** | 1.215 |

204 of the net instances on the worst-400 paths sit in the >1500 bucket, and
they cost ~2.5× a 49-200-fanout net. Our specific hop measures 0.671 ns.

### 3.3 The in-netlist analogue: `completes`, the same event with a narrow payload

`RobPlugin.scala:703` — `for (c <- completion) when(c.valid) { completes(c.payload) := True }`
— is a 64-entry `Reg` `Vec` (`RobPlugin.scala:120`) written from the **same LS
boundary event**: `completion(4)` is `lsEu.sqCompletionPort`
(`top/FullCoreSynth.scala:200-201`), driven by the same
`when(…) { sqCompletionPort.valid := True }` at `LsEuPlugin.scala:1663-1665`.
The only structural difference is that its payload is **1 bit of constant
`True`** — so there is no wide per-bit data-mux array to inflate the late net's
fanout.

`report_timing -from {tagMem…} -to {*RobPlugin_logic_completes_*_reg*/CE|/D}`
(`vC_tagmem_to_completes.rpt`):

```
Slack: -1.233 ns   Destination: RobPlugin_logic_completes_25_reg/D
  … net (fo=66)  → sq_io_sqCompletion_valid              3.506
  … LUT6 pendApply[2]_i_4  → fo=4                        3.874
  … LUT2 pendApply[2]_i_1  → when_LsEuPlugin_l1663 fo=24 4.405   <- ROB boundary
  … LUT5 completes_24_i_4  → fo=16   (+0.479)            4.921
  … LUT6 completes_25_i_3  → fo=1    (+0.087)            5.105
  … LUT3 completes_25_i_1  → fo=1    (+0.049)            5.243   → /D
```

| | levels | logic | route | **ROB tail total** | max late-net fanout |
|---|---:|---:|---:|---:|---:|
| `fault*Store` (wide array) | 3 | 0.216 ns | 1.495 ns | **1.711 ns** | **2083** |
| `completes` (1-bit array) | 3 | 0.223 ns | 0.615 ns | **0.838 ns** | **16** |

**Same source, same LS boundary, same ROB region, same number of LUT levels,
near-identical logic delay — 2.9× the route delay, entirely explained by
fanout.** This is the measured, in-netlist evidence that a fanout-limited
version of the fault write-enable decode is achievable in this placement, and
it bounds the prize at **0.873 ns** (the difference).

---

## 4. Write-port audit — re-verified against live source

`grep -n "faultAddrStore(.*) *:=" src/main/scala/m68k040/rob/RobPlugin.scala`,
plus a repo-wide grep confirming no other file writes it (only three *comments*
mention it: `BranchEuPlugin.scala:43`, `LsEuPlugin.scala:676`,
`ExceptionUnit.scala:736`). **The original report's line numbers have all
shifted by 1-3; these are current.**

| # | gate | write | port | data | fields written |
|---|---|---|---|---|---|
| 1 | `RobPlugin.scala:750` `when(lsFaultCompletion.valid)` | `:753` | LS EU live MMU/bus fault | `.payload.faultAddr` | faulted, faultVec:=2, faultAddr, faultWr, faultSize, faultSup, faultAtc, faultInstr:=False (`:751-762`) |
| 2 | `RobPlugin.scala:768` `when(sqFaultCompletion.valid)` | `:771` | SQ precise-drain bus error | `.payload.faultAddr` | **byte-identical field set** (`:769-776`) |
| 3 | `RobPlugin.scala:788` `when(euFaultCompletion.valid)` | `:795` | BranchEu/DivEu execute-time fault | `.payload.faultAddr` | faulted, faultVec:=`payload.vector`, faultInstr:=False, faultAddr (`:789-795`) |
| 4 | `RobPlugin.scala:799` `when(alloc0)` | `:811` | alloc slot 0 (I-fetch fault PC) | `allocUopVec(0).faultAddr` | full alloc reset (`:806-822`) |
| 5 | `RobPlugin.scala:824` `when(alloc1)` | `:835` | alloc slot 1 | `allocUopVec(1).faultAddr` | ″ (`:831-840`) |

**Exactly five ports; `depth = 64` (`RobPlugin.scala:115`).** Priority is
SpinalHDL last-assign: alloc1 > alloc0 > eu > sq > ls.

Ports #1/#2 were **deliberately** kept separate — `RobPlugin.scala:345-348`
(the original report cited `:339-344`; **that citation is stale**):

> `// SQ precise-path drain fault (Task P2.4): a second lsFaultCompletion-shaped port`
> `// rather than sharing one -- the LS EU can fault a YOUNGER access (a translate-`
> `// time MMU fault) the SAME cycle the SQ faults an OLDER, already-drained precise`
> `// store's bus error. Identical shape/defaults to lsFaultCompletion above.`

**Read ports — exactly two, both unchanged by this design** (§7):
`RobPlugin.scala:922` (`exceptionFaultAddr := faultAddrStore(h0)`, asynchronous
64:1 read at the ROB head) and `RobPlugin.scala:1047`
(`entryFaultAddr = faultAddrStore(h0)` into `ExceptionUnit`).

---

## 5. The design

### 5.1 Chosen mechanism — hoist a **named per-entry write-select** for the two
### completion-side `LsFault` ports, so the late valid stops driving the
### `faultAddrStore` data-mux array

Replace the two dynamic-index write blocks at `RobPlugin.scala:750-763` and
`:768-777` with an explicit per-entry form whose per-entry select is a **named,
kept** node used by *both* the enable and the data mux:

```scala
// ── Lever C: per-entry write-select hoist ───────────────────────────────────
// SpinalHDL already elaborates `when(p.valid){ store(p.robId) := d }` into
// exactly `for i: when(p.valid && oneHot(p.robId)(i)) { store(i) := d }`
// (generated/M68kFullCoreSynth.v:211230/211238 + the per-entry `if(_zz_119[i])`
// bodies). Writing that form out by hand is therefore BIT-IDENTICAL. The point
// of writing it out is the `keep` attribute: without it the synthesizer absorbs
// `p.valid` directly into all 64x32 faultAddrStore per-bit data muxes, so the
// single late node ends up with fanout 2083 and a 0.671 ns route hop that the
// per-entry ENABLE decode pays for but does not cause (see the design spec's
// load decomposition: 2019 of 2083 loads are faultAddrStore data muxes).
val lsFaultOh  = UIntToOh(lsFaultCompletion.payload.robId, depth)
val sqFaultOh  = UIntToOh(sqFaultCompletion.payload.robId, depth)
val lsFaultSel = Vec(Bool(), depth)
val sqFaultSel = Vec(Bool(), depth)
for (i <- 0 until depth) {
  lsFaultSel(i) := lsFaultCompletion.valid && lsFaultOh(i)
  sqFaultSel(i) := sqFaultCompletion.valid && sqFaultOh(i)
  lsFaultSel(i).setName(s"lsFaultSel_$i").addAttribute("keep", "true")
  sqFaultSel(i).setName(s"sqFaultSel_$i").addAttribute("keep", "true")
}
for (i <- 0 until depth) {
  when(lsFaultSel(i)) { /* the exact body of :751-762, with `i` for `payload.robId` */ }
}
for (i <- 0 until depth) {
  when(sqFaultSel(i)) { /* the exact body of :769-776, with `i` for `payload.robId` */ }
}
```

**Textual order must be preserved exactly** — the whole `lsFaultSel` loop
before the whole `sqFaultSel` loop, and both before `euFaultCompletion`
(`:788`) and before `alloc0`/`alloc1` (`:799`/`:824`) — so SpinalHDL's
last-assign priority (alloc1 > alloc0 > eu > sq > ls) is bit-for-bit unchanged,
including the two documented same-index cases (`:747-748` "alloc wins on a
re-used index" and `:767-768`).

Ports #3/#4/#5 are **not** restructured (they are not on the late cone: the eu
port's own select `DivEuPlugin_logic_compRobId_reg[3]_25` and the alloc ports'
`_zz_RenameStage_logic_uopsStaged_*` arrive early and enter only at the terminal
LUT6 — measured, `probeC.out` "PATH CELLS").

### 5.2 Why this is expected to work, and how much

After the hoist, `sqFaultCompletion.valid`'s late node drives **64** kept select
LUTs instead of ~2083 mixed loads; each entry's ~32 `faultAddrStore` data-mux
LUTs plus its enable LUT consume the *local* `sqFaultSel_i` (fanout ~33).

* the fo=2083 hop (0.671 ns measured) moves into the 49-100 bucket, whose
  measured mean on this placement is **0.300 ns** (§3.2) ⇒ **≈ -0.37 ns**;
* the level count is unchanged (the kept select LUT replaces the existing
  per-entry `faultSupStore_9_i_3` level), so no new logic delay;
* the two remaining hops (fo=10 at 0.366 ns, fo=3 at 0.458 ns) are today
  well above their buckets' means (0.257 / 0.151 ns) — consistent with local
  congestion around a 2019-load net — so a further ~0.1-0.3 ns is *plausible*
  but is **not claimed**.

**Central estimate: family 3 worst goes -1.521 → ≈ -1.15 ns (range -1.25 …
-1.00).** The `completes` analogue (§3.3) bounds the ceiling at ≈ -0.65 ns; the
floor, if the tool ignores the attribute entirely, is **0.000 ns** (see §5.4).

### 5.3 Is this pure logic, or does it cost latency? — **PURE LOGIC. Zero latency cost.**

The rewritten form is *definitionally* what SpinalHDL already elaborates: the
generated Verilog for the current source is literally
`if(<valid>) begin … if(_zz_127[i]) faultAddrStore_i <= …; end` for every
`i ∈ [0,64)`. Factoring `valid && oneHot(i)` into a named signal and inverting
the loop/condition nesting produces the **same boolean function of the same
signals in the same cycle**. There is:

* **no new register** — nothing is sampled a cycle early or late;
* **no change to when a fault is recorded** — same cycle, same entry, same data;
* **no change to port arbitration or priority** — last-assign order preserved;
* **no change to the same-cycle-different-robId capability** of ports #1/#2
  (`RobPlugin.scala:345-348`) — each port keeps its own independent select
  vector, and two different `i` can be selected in the same cycle exactly as
  today;
* **no change to any read site** (§7).

The only genuinely new thing in the netlist is a **synthesis directive**
(`keep`) on 128 wires. That is not a behavioural change; it is a constraint on
how the tool may factor an unchanged function. Consequently this lever's
*correctness* risk is near-zero and its *effectiveness* risk is entirely a
tool-behaviour question — which is why §5.4 makes the fanout drop, not just the
slack, an explicit acceptance criterion.

Area: **+128 LUTs** for the kept selects; likely partly offset because the
per-entry data muxes lose one late input each. Not expected to be material at
this design's size, but must be reported from the post-synth utilisation report.

### 5.4 Acceptance criterion is the measured FANOUT, not only the slack

Because the mechanism is a tool directive, the implementation task **must**
report, from the post-synth (or post-route) checkpoint:

```tcl
get_property FLAT_PIN_COUNT [get_nets -hier -filter {NAME =~ "*sqFaultSel_*"}]
# and the driver of sqFaultCompletionPort.valid inside the ROB fault region
```

* **PASS:** no net in the `sqFaultCompletion.valid`/`lsFaultCompletion.valid`
  cone inside the ROB fault region has fanout > ~200 (today: 2084), and the
  family-3 worst endpoint improves by ≥ 0.25 ns.
* **INCONCLUSIVE / try the documented fallback:** the fanout stays > 1000 —
  i.e. the tool absorbed the kept select anyway. Fallback, in order:
  1. escalate `keep` → `addAttribute("DONT_TOUCH", "TRUE")` on the select Vecs
     (stronger; blocks `opt_design` merging, but also blocks some `phys_opt`
     replication — measure, do not assume);
  2. add `addAttribute("MAX_FANOUT", 128)` on `sqFaultCompletionPort.valid`
     (`LsEuPlugin.scala:1689`) so Vivado replicates the driver itself.
  Report which one was needed. Precedent for RTL attributes in this codebase:
  `DcachePlugin.scala:90` (`addAttribute("ram_style", "block")`).
* **FAIL:** fanout drops as intended but slack does not improve ≥ 0.15 ns —
  then the fanout hypothesis is wrong for this placement and the lever should be
  reverted, exactly as Slice 3 was.

### 5.5 The other two sketched sub-options — evaluated and REJECTED

**(a) "One-hot pre-decode registered one stage early."** Two independent
reasons to reject:

1. The one-hot pre-decode **already exists** in the emitted RTL
   (`M68kFullCoreSynth.v:211230/211238/211247/211260/211269`), so the
   non-registered half of the proposal is a no-op.
2. *Registering* it is **not** a pure logic restructuring — it is a latency
   change, and an unsafe one. The one-hot's input `robId` is not available a
   cycle early: for the sq port,
   `sqFaultCompletionPort.payload := Mux(applyFast, sq.io.sqFaultCompletion.payload, pendFaultPayload(pendApply))`
   (`LsEuPlugin.scala:1690`), and `applyFast` (`:1660`) is itself produced by
   the very same late `sq.io.sqCompletion.valid` this path starts from. So a
   registered one-hot would necessarily delay the fault write by one cycle,
   putting it in the same hazard class as Lever B (§6): the ROB's
   `nmiEdge`/`nmiPending` latch is calibrated against this same-cycle fast path
   (`LsEuPlugin.scala:1646-1656`). **Out of scope for a lever advertised as
   pure-logic.**

**(b) "Merge ports #1/#2 behind a 2-entry arbiter."** Rejected on behaviour.
`RobPlugin.scala:345-348` records that the two ports exist *specifically*
because they can fire the same cycle for different robIds. Only two arbiter
shapes exist:
* *pick-one-per-cycle + stall/queue the loser* — a real behavioural change
  (the ROB has no backpressure on either completion port; a deferred MMU fault
  would race the entry's retirement). **Unacceptable.**
* *2-wide write* — preserves behaviour but is **not a merge at all**: it is
  still two independent one-hot decodes and two data sources per entry. Zero
  fanout reduction, so zero benefit against the measured problem.

Also note the premise ("now that both write byte-identical field sets") is
*true* (`:751-762` vs `:769-776` — verified, identical field lists, identical
constants `faultVec := U(2)` / `faultInstr := False`) but irrelevant: identical
*field sets* do not imply identical *values* or *indices*.

**(c) "Move port #3 (`euFaultCompletion`) onto a narrow dedicated register."**
The premise checks out: `RobPlugin.scala:792-794` says the value is "Only
meaningful for vector 3 (address error)", and `ExceptionUnit.scala:739`
confirms it — `val ppcOrTarget = Mux(entryVector === 3, entryFaultAddr, ppc.resized)`.
But the option is rejected for this slice on three grounds:

1. **It targets the wrong thing.** Port #3 is not on the late cone (measured:
   its select `DivEuPlugin_logic_compRobId_reg[3]_25`, fo=70, enters only at the
   terminal LUT6). Removing it cannot shorten this path.
2. **It is not safe without a proof I do not have.** A single dedicated
   register requires that at most one address-error can be in flight in the ROB
   at a time. Several branches can execute (and each can compute an odd target)
   before the oldest retires; establishing the bound is a real analysis, not a
   spec footnote.
3. It *would* be a worthwhile **area** item — dropping one input from 64×32
   per-bit muxes — and belongs with task #127, not here.

---

## 6. Non-goals

* **Lever B (register the deferred-replay ROB completion ports,
  `LsEuPlugin.scala:1665`/`:1689`) is explicitly OUT OF SCOPE and must not be
  proposed by the implementation task.** The file's own comment at
  `LsEuPlugin.scala:1646-1656` records that an always-registered version of this
  exact thing was already built once and **mis-timed IRQ/NMI handling by a
  cycle**: "`irq-nmi` needs EXACTLY one extra register stage (RobPlugin's own
  nmiEdge/nmiPending latch) beyond a direct-compare interrupt's timing,
  calibrated against this SAME-cycle fast path; the always-registered version
  added a SECOND stage on top and mis-timed the injection by a cycle." A
  strategic review this round rejected it for that reason. Lever C is
  deliberately the *pure-logic* alternative that touches neither the fast path
  nor its cycle timing.
* **Task #127 (fold the ROB per-entry `Reg` `Vec`s into `Mem`/BRAM)** — the
  original grounding report's §3.2 gives four evidence-backed reasons this is
  not a bounded FMax slice (5 same-cycle writers with a deliberate split;
  `MultiPortWritesSymplifier` produces `RamAsyncMwMux` banks that are themselves
  a top-3 congestion source, `RobPlugin_logic_payload/…` fanout 5294; the
  asynchronous head read at `:922` would need a pre-registered `h0` or +1 cycle
  of exception latency). Unchanged verdict.
* No change to `faultVecStore`/`faultPcStore`/`sysValStore`/`completes` or any
  other ROB array's write structure.
* No change to `euFaultCompletion`, `alloc0`, `alloc1` write blocks.
* No change to `LsEuPlugin.scala` at all under the primary design (only under
  the §5.4 fallback #2, and then only an attribute).
* No floorplan/XDC change. `synth/floorplan_dcache.xdc`'s own header records
  three iterations where looser/shifted pblocks *regressed*; if a floorplan
  lever is ever tried it must be an A/B synth pair, not an argument.

---

## 7. Read-side impact — confirmed NONE

`faultAddrStore` has exactly two read sites, both unchanged:

* `RobPlugin.scala:922` — `exceptionFaultAddr := faultAddrStore(h0)`,
  an **asynchronous** 64:1 read at the ROB head, consumed combinationally by the
  exception FSM.
* `RobPlugin.scala:1047` — `entryFaultAddr = faultAddrStore(h0)` into
  `ExceptionUnit` (thence `ExceptionUnit.scala:739` `ppcOrTarget` and `:747`
  `curFault`).

This design changes only *how the write enable is factored*. The storage type
(`Vec.fill(depth)(RegInit(U(0,32 bits)))`, `RobPlugin.scala:295`), its width,
its per-alloc reset semantics, the read index (`h0`) and the read's
combinational nature are all untouched — so no read-side timing or semantic
change is possible. (Contrast the rejected `Mem` fold, which *would* change the
read.) The sibling arrays `faultWr/faultSize/faultSup/faultAtc/faultInstr/
faulted/faultVec` are read at `:917`, `:923-930` and `:1048-1055` and are
likewise unaffected except that ports #1/#2 now write them under a named select
of identical value.

No read site of `faultAddrStore` exists outside `RobPlugin.scala` (repo-wide
grep: the only other hits are three comments —
`BranchEuPlugin.scala:43`, `LsEuPlugin.scala:676`, `ExceptionUnit.scala:736`).

---

## 8. Verification requirements (binding)

* `~/sbt/bin/sbt compile` clean.
* **Equivalence proof appropriate to a pure-logic restructuring — do this
  first, it is cheap and it is the whole correctness argument.** Generate the
  Verilog before and after (`generated/M68kFullCoreSynth.v`) and diff the
  `RobPlugin_logic_fault*Store_*` always-block bodies. The expected diff is
  **only** the introduction of the named `lsFaultSel_*`/`sqFaultSel_*` wires
  (plus their `(* keep *)` attributes) and the substitution of those wires for
  the inline `<valid> && _zz_119[i]` / `_zz_127[i]` conditions — **no change to
  which entry is written, with what data, in what priority order.** If the diff
  shows anything else, stop: the restructure is not the identity it claims.
* Existing ROB/exception suites: locate by grep over `src/test/scala/`
  (`Rob`, `Exception`, `LsFault`, `Precise`) — do not guess file names — and
  confirm full pass.
* **Targeted same-cycle multi-fault coverage** (ports #1/#2 exist *because* of
  this case, `RobPlugin.scala:345-348`): run/locate the tests that exercise a
  precise-store drain bus error concurrent with a live MMU fault. Start from the
  Task P2.4 / precise-drain test names and the `irq-nmi` and
  `bsr-loop-mispredict` tests named at `LsEuPlugin.scala:1616-1626` and
  `:1646-1656`. If no test constructs two faults landing the same cycle for two
  different robIds, **add one** — the select-vector rewrite is precisely the
  code whose failure mode would be "two same-cycle writes collapse into one".
* Ported tests for the fault/exception families specifically (grep the corpus
  for `bus_err`, `addr_err`, `mmu`, `access_fault`, `trapv`, `chk`, `div0`) —
  run explicitly **before** the full sweep.
* `ExecuteLockStepSpec` full suite: expect **390/394** (this session's standing
  baseline — the same 4 pre-existing failures).
* Full ported corpus (~870 tests) via `tools/fuzz/ported-sweep-parallel.sh` in
  isolated git worktrees (`git worktree add` is mandatory — `git checkout <sha>`
  in the shared tree caused a real collision before, task #199): zero new
  regressions vs. the current baseline.
* **Netlist acceptance check (§5.4)** — report the measured
  `FLAT_PIN_COUNT` of the sq/ls fault-valid cone before (2084) and after.
  This is a required deliverable, not an optional nicety: without it a null
  slack result is uninterpretable.
* **OOC synth gate AND the real post-route gate.** Caveat learned this session:
  OOC and post-route disagree about which paths are critical in this design —
  an OOC number alone is not a verdict. Report both.
* **This lever must be gated COMBINED with the others, not solo.** §1 shows
  three independent families within 0.172 ns of each other; the ucPendPkt
  grounding report's §5 proves (via `all_fanin` cone intersection: 1 shared cell
  between family 2 and family 3) that they are independent. Perfectly fixing
  family 3 alone leaves WNS at -1.693 ns / 175.65 MHz, **unchanged**. The
  success criterion for *this* lever in isolation is therefore **causal**: the
  `tagMem -> RobPlugin fault*Store` family's worst endpoint improves by
  ≥ 0.25 ns and its endpoint count in the worst-400 (today **217**) falls, with
  no regression elsewhere. The accept/reject decision belongs to the combined
  slice, measured on an **uncontended** machine with a **double run** (this
  session's two runs agreed to 0.019 ns; treat any single-run delta below
  ~0.15 ns as noise).
