# 250MHz over-constraint census — which cones in this core are *structurally* hard

**Date** 2026-08-25 · **Branch** `fmax-postroute-200mhz-closure` · **Netlist** `M68kFullCoreSynth.v`
md5 `872c3dc5f666463f9842f5cb2a4b4d0c` · **Part** `xcku5p-ffvb676-2-e` · **Floorplan** `decode+fetch`
· **Strategy** `postrouteN` ×9

**Status** DIAGNOSTIC ONLY. No RTL changed, no default flow changed, no sign-off number restated.
Feeds **task #114** (LS/IPC overhaul program) and **task #115** (congestion + floorplan as a
first-class gate metric).

**Builds on, does not duplicate:** `2a144d61` (200MHz post-route closure, +0.042ns / 201.694 MHz)
and `59dbc435` (the `fpS1Kind` / `s1FpSrc` stop-on-evidence survey). §6 below reconciles this
census with `59dbc435`'s "band-limited, no lever" verdict — both are correct, about different
questions.

---

## 1. What was asked, and the one-line answer

The premise: at the 5.000ns sign-off constraint the design sits in a flat, saturated regime — the
worst 100 post-route paths span 0.053ns across 10 families in 8 plugins, so nothing can be told
apart. The proposal: re-target the *whole implementation* at 4.000ns (250MHz) as a **diagnostic
overshoot**, on the theory that heavier pressure forces the placer/router to rank paths by real
structural difficulty; and the hypothesis that restructuring genuinely complex cones (fanout,
multi-writer arrays, wide muxes/shifters, arithmetic depth) helps synthesis and routing *broadly*,
not just on the one path fixed.

**The overshoot works, and it works far better than the earlier survey suggested — but only if you
stop ranking by slack.**

At 5.000ns this netlist has **0 failing endpoints out of 168,756**. There is no failing population
at all, so there is literally nothing to rank; the "worst 100" is just the top of a passing
distribution. At 4.000ns the same netlist exposes **22,363 failing endpoints, TNS −9,825.3ns**.
That population is not flat at all — it is extremely concentrated:

| ranked by | #1 family | share |
|---|---|---|
| **worst slack** (what `59dbc435` used) | `DivEuPlugin.s1FpSrc` | −0.958ns, but only **0.6%** of total TNS |
| **structural weight** (TNS / endpoint population) | `RobPlugin.faultedStore_16` | **39.2%** of total TNS, **29.0%** of all failing endpoints |

**The worst path is not the hardest structure.** `s1FpSrc` holds the worst *slack* and ranks
**#20 of 119** by structural weight — 103 endpoints, 8 destination registers, a genuinely tiny
cone. `faultedStore_16` holds only the 5th-worst slack and owns **more than a third of the entire
design's timing debt** across 6,479 endpoints and 755 destination registers.

Ranking by WNS in a saturated regime measures *what the optimiser happened to stop on*. Ranking
the over-constrained failing population by TNS measures *what the optimiser had to fight*. Those
are different questions and this core answers them differently by a factor of 60.

---

## 2. Method

No new heavy implementation run was launched. Two reasons, both material:

1. `2a144d61` had **already** run exactly this experiment. Its `probe4ns` arm is a genuine
   4.000ns-**primary** build — `IMPL_PROBE_PERIOD_NS` swaps the constraint file before
   `synth_design`, so 4.000ns drives synthesis, placement *and* routing. It is not a 5.000ns
   netlist re-analysed at 4ns. Its own verdict line reads
   `POSTROUTE_FULLCORE_RESULT FAILED_AT_250  ACHIEVED_FMAX_MHZ 201.694` — the expected
   "won't hit 250, and that's fine" outcome. The 5.000ns re-check afterwards is a pure re-analysis
   of the frozen routed netlist and does not touch the 4.000ns numbers used here.
2. A concurrent agent's placer-sensitivity baseline (`IMPL_SEED_THREADS` sweep) was re-running
   that identical configuration on this machine throughout this work, and **reproduced it
   bit-for-bit** — `POSTSYNTH −1.236`, `ROUND 0 −1.385`, `ROUND 1 −1.230`, `ROUND 2 −1.224`,
   identical to the archived run. Launching a third concurrent copy would have contended for
   memory and raced on the same `synth/fullcore_*.rpt` / `fullcore_routed.dcp` output paths for
   zero new information.

What *was* missing was never the build — it was the analysis depth. The committed flow emits only
a **top-100** slack matrix and **10** detailed paths, which is precisely the slice that hides the
structure. So the routed 4.000ns checkpoint (`synth/fullcore_routed.dcp`, the `probe4ns` MET200
netlist) was snapshotted and re-opened for three offline census passes:

* **population census** — `get_timing_paths -max_paths 40000 -slack_lesser_than 0`, one worst path
  per endpoint, grouped by startpoint register family (`census_ooc_fullcore.tcl`'s `regkey`
  collapsing, so `foo_reg[3]_rep_2/C` and `foo_reg[17]/C` are one family). 22,363 endpoints,
  119 families. Per family: count, TNS, worst slack, max datapath delay, max logic levels,
  fraction of endpoints landing on a `/CE` pin, and distinct destination register count.
* **structural census** — full `report_timing` detail for a representative (worst) path of each
  top family, parsed for logic-vs-route split, levels of logic, per-hop cell-type mix
  (`CARRY8`/`LUT*`/`MUXF7`/`MUXF8`/`RAMD32`), data-path-only fanouts, and the SLICE bounding box
  the path traverses.
* **placement geometry** — bounding box of each family's own register array, against a device
  extent of `SLICE_X0..112 Y0..239`.

Scripts are diagnostic scratch (not committed); every number below is regenerable from
`synth/archive/9c1f87e3_probe4ns_MET200_decode_fetch/` plus that checkpoint.

**Utilisation context.** CLB LUTs 112,273 / 216,960 = **51.75%**; CLB registers 54,335 (12.52%);
**CARRY8 only 906 (3.34%)**; F7 muxes 5,039; F8 muxes 1,675; BRAM 35 (7.29%). This is a
**LUT-and-mux dominated control machine, not an arithmetic-dominated datapath** — which is exactly
why the census below is full of wide multiplexers and write-enable decodes and nearly empty of
carry chains.

**Congestion context.** `report_design_analysis -congestion` finds **no congestion window above
level 5**. The design is *not* routability-limited. Where paths are route-dominated below, that is
**placement spread**, not congestion — a distinction that matters a great deal for task #115.

---

## 3. The ranked census — top 20 families at 4.000ns

Sorted by TNS share (structural weight). `NDST` = distinct destination registers. `CE%` = fraction
of the family's failing endpoints that terminate on a clock-enable pin (a write-enable decode
rather than a data path). `LOGIC`/`ROUTE`/`RT%`/`LEV`/`MAXFO`/`dX`/`dY` are from the family's
representative worst path.

| # | TNS% | endpts | NDST | worst | logic | route | RT% | lev | maxFO | CE% | dX | dY | family |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | **39.2%** | 6479 | 755 | −0.943 | 1.154 | 3.770 | 77 | 19 | 77 | 54% | 41 | 23 | `RobPlugin.faultedStore_16` |
| 2 | 7.4% | 1052 | 70 | −0.929 | 1.412 | 3.414 | 71 | 16 | 89 | 59% | 7 | 25 | `FetchAlignPlugin.ftqHead` |
| 3 | 6.1% | 1259 | 247 | −0.873 | 1.097 | 3.657 | 77 | 13 | **935** | 73% | 14 | 23 | `_zz_DecodeStage.fed_payload_specs_1_spec_op` |
| 4 | 5.3% | 1227 | 300 | −0.874 | 1.321 | 3.532 | 73 | 13 | 101 | 0% | 39 | 53 | `AluEuPlugin.s1Ctx_uop_op` |
| 5 | 4.8% | 898 | 76 | −0.953 | 1.730 | 3.119 | 64 | 14 | 140 | 83% | 10 | 11 | `RobPlugin.exc_fsFrameBase` |
| 6 | 3.5% | 579 | 92 | −0.917 | 1.250 | 3.563 | 74 | 17 | 165 | 79% | 8 | 55 | `FetchAlignPlugin.quiesce` |
| 7 | 3.2% | 921 | 100 | −0.824 | 0.815 | 3.905 | 83 | 10 | **473** | 36% | 47 | 63 | `RobPlugin.head` (rep 16) |
| 8 | 2.9% | 1267 | 208 | −0.794 | 0.555 | 4.135 | **88** | 5 | 191 | **89%** | 61 | 82 | `RobPlugin.exc_fsm_stateReg` (rep 0) |
| 9 | 2.6% | 797 | 122 | −0.875 | 1.181 | 3.334 | 74 | 12 | 167 | 18% | 6 | 31 | `_zz_DecodeStage.fed_payload_packets_0_words_0` |
| 10 | 2.6% | 1291 | 338 | −0.823 | 0.865 | 3.939 | 82 | 11 | 250 | 48% | 34 | 48 | `RobPlugin.exc_fsm_stateReg` |
| 11 | 2.2% | 413 | 92 | −0.846 | 1.116 | 3.709 | 77 | 12 | 99 | 0% | 32 | **114** | `AluEuPlugin.s1Ctx_uop_size` |
| 12 | 2.1% | 440 | 36 | −0.869 | 1.586 | 3.035 | 66 | 9 | 10 | 34% | 12 | 17 | `DcachePlugin.tagMem_3` |
| 13 | 2.1% | 547 | 186 | −0.868 | 1.367 | 3.484 | 72 | 16 | 54 | 0% | 44 | 31 | `AluEuPlugin.s1Ctx_uop_op_1` |
| 14 | 1.8% | 343 | 85 | −0.798 | 1.180 | 3.598 | 75 | 10 | 95 | 0% | 42 | 84 | `AluEuPlugin.s1Ctx_uop_op` (replica 1) |
| 15 | 1.3% | 166 | 17 | −0.939 | 1.210 | 3.712 | 75 | 13 | 65 | 52% | 54 | 22 | `DcachePlugin.stS2Valid` |
| 16 | 1.0% | 383 | 151 | −0.869 | 0.478 | 4.313 | **90** | 5 | 32 | 0% | **72** | **77** | `RobPlugin.exc_sysCapRc` |
| 17 | 0.9% | 215 | 29 | −0.954 | 0.932 | 4.003 | **81** | 9 | 212 | 0% | **68** | **111** | `IssueQueuePlugin.lines_0_ways_0_sel` |
| 18 | 0.9% | 287 | 111 | −0.789 | — | — | — | — | — | 0% | — | — | `_zz_DecodeStage.fed_payload_packets_1_words_1` |
| 19 | 0.8% | 190 | 150 | −0.860 | — | — | — | — | — | 0% | — | — | `RobPlugin.exc_sysCapVal` |
| 20 | 0.6% | 103 | 8 | −0.958 | **2.314** | 2.624 | **53** | **26** | 137 | 0% | 7 | 11 | `DivEuPlugin.s1FpSrc` |

Aggregated by owning plugin — all 119 families, summing to 100%:

| plugin | families | TNS | TNS share | failing endpoints | endpoint share |
|---|---|---|---|---|---|
| **RobPlugin** | 20 | −5443.6 | **55.4%** | 11,795 | 52.7% |
| **DecodeStage** (incl. `_zz_`) | 20 | −1162.5 | 11.8% | 3,305 | 14.8% |
| **AluEuPlugin** | 14 | −1136.2 | 11.6% | 2,628 | 11.8% |
| **FetchAlignPlugin** | 4 | −1095.3 | 11.1% | 1,678 | 7.5% |
| DcachePlugin | 10 | −389.1 | 4.0% | 1,024 | 4.6% |
| IssueQueuePlugin | 22 | −263.4 | 2.7% | 834 | 3.7% |
| LsEuPlugin | 9 | −118.2 | 1.2% | 412 | 1.8% |
| DivEuPlugin (all FP) | 5 | −94.5 | 1.0% | 238 | 1.1% |
| BranchEu / Icache / Rename / Itlb | 15 | −122.5 | 1.3% | 449 | 2.0% |

**Four plugins own 90% of the design's timing debt at 250MHz, and RobPlugin alone owns more than
half.** The entire FP/DIV subsystem — the thing the previous survey spent its effort on — owns 1.0%.

---

## 4. Structural characterisation

### 4.1 `RobPlugin` per-entry store arrays — **multi-writer flop array + 64:1 read mux** (39.2%, and 55.4% across RobPlugin)

ROB geometry: `depth = 64`, `robIdW = 6` (`rob/RobPlugin.scala:235-236`). The arrays are
`Vec.fill(64)(Reg(...))` — **flop arrays, deliberately not `Mem`**:

| array | decl | shape | writers | reads |
|---|---|---|---|---|
| `faultedStore` | `RobPlugin.scala:382` | 64 × 1b | **6** | 9, all `(h0)`/`(h1)` |
| `faultAddrStore` | `RobPlugin.scala:520` | 64 × 32b = 2,048 FF | **6** | **2**, both `(h0)` |
| `sysValStore` | `RobPlugin.scala:395` | 64 × 32b | 4 (`ccrCompletion` ports) | 3 |
| `nzvcValStore` | `RobPlugin.scala:643` | 64 × 4b | 4 (`ccrCompletion` ports) | 4 |

The six writers of `faultAddrStore` are `lsFaultCompletion` (`:1354`), `sqFaultCompletion`
(`:1374`), `euFaultCompletion` (`:1400`), `fpFaultCompletion` (`:1427`), `allocUopVec_0` and
`allocUopVec_1` (`:1451`) — confirmed in the netlist at `M68kFullCoreSynth.v:327367 / 328007 /
329110 / 329880 / 331802 / 334684`. Last-assign priority `alloc1 > alloc0 > eu > sq > ls` is
load-bearing (comment `:1337`).

**This is exactly the 6-writer shape recorded as task #127's blocker** — the shape that "fits none
of the 3 proven fold patterns and needs a 4th, arbitrated-multi-writer pattern". The census prices
that blocker for the first time: not one of several near-tied families, but **39.2% of the design's
total timing debt in one array pair, and 55.4% across all of RobPlugin.**

**And the cluster is much larger than four arrays.** Ten further siblings share the identical
64-deep / multi-writer shape: `faultVecStore:381` (8b), `faultWrStore:521`, `faultSizeStore:531`
(2b), `faultSupStore:532`, `faultAtcStore:537`, `faultInstrStore:544`, `sysValRdyStore:402`,
`nzvcWrStore:644`, `xValStore:645`, `xWrStore:646`. By contrast the *bulk* ROB payload is already
a `Mem` (`payload`, `branchTrainMem:518`) — so the fold pattern this cluster needs is one the
plugin already applies elsewhere; it is the multi-writer arbitration, not the memory inference,
that is missing.

Corroborating physical signatures, all consistent:

* **`CE%` = 54%** — over half the family's failing endpoints terminate on a *clock-enable*, i.e.
  the per-entry write-enable decode, not the data path. `exc_fsFrameBase` is 83% CE and
  `exc_fsm_stateReg` (rep 0) is 89% CE. The cost is in **write-port decode**, not in computing the
  values.
* **The RTL already contains a hand-built mitigation for exactly this, and it was not enough.**
  `RobPlugin.scala:1330-1348` unrolls the ls/sq write-enable decode per entry into `keep`-attributed
  `lsFaultSel_i` / `sqFaultSel_i` wires, with an in-source comment recording that the shared
  `p.valid` node had **fanout 2,084, of which 2,019 loads were this array's per-bit data muxes**.
  That mitigation is in the netlist measured here, and the family is still #1 by a factor of five.
  Local fanout surgery has been tried on this structure and is exhausted; what remains is the fold.
* **`NDST` = 755 distinct destination registers** from a single 1-bit source register — a
  one-to-many *write broadcast* across the array.
* **`MUXF7=1 MUXF8=1`** on the representative path, and again on `head` (#7) and `exc_fsm_stateReg`
  (#10). MUXF7/F8 pairs are how Vivado builds the **64:1 read mux** that selects entry `[head]`.
  The design carries 5,039 F7 and 1,675 F8 muxes against only 906 CARRY8s — the mux trees, not the
  adders, are the structure.
* **Fanout 1,013** on `faultAddrStore_16[14]_i_3_n_0`, and **7,510** on
  `RobPlugin_logic_payload/wr0LocValid` — the second-highest-fanout net in the entire design after
  `reset` itself.
* The array's own flops are **tightly placed** (`faultedStore_16` bbox 2×1 slices,
  `faultAddrStore_16` 7×4). The 77%-route split is therefore **not** a scattered-array problem; it
  is the cost of fanning one enable out to 755 destinations over a 41×23 slice region.

**Related, and part of the same cluster: `head` (#7, 3.2%).** `RobPlugin.scala:242` — a 6-bit
`Reg` with **exactly one, ungated writer** (`:1464`, `head := head + Mux(retire1,2,Mux(retire0,1,0))`).
It is not a depth problem at all: `h0 = head`, `h1 = head + 1` are the index of **roughly 23
distinct 64-deep `Vec` read muxes** (`faultedStore` ×8, `nzvcWrStore` ×5, `mispredictStore` ×4,
`completes` ×4, `nzvcValStore` ×3, ×2 each for eight more arrays, plus `payload.readAsync`,
`branchTrainMem`, …). A 6-bit net driving ~23 64:1 decoders — 0.815ns of logic against 3.905ns of
route, 473 loads, spanning 47×63 slices. **Folding the arrays collapses this family too**, because
each fold replaces a 64:1 flop-array mux with a memory read port.

**Assessment: the single genuinely-worthwhile architectural target in this core.** It matches every
shape the hypothesis named (multi-writer register array, wide mux, high fanout), it is the largest
by a factor of five, it subsumes families #1, #5, #7, #8, #10, #16, #19, and it already has a task.
The right fix is a fold to memory with arbitrated write ports — the same class of win as the
D-cache valids/dirtys LUTRAM fold (task #240), which bought ~17k LUTs *and* +0.8ns simultaneously.
The honest caveat from `59dbc435` still holds and is not contradicted: **this fix cannot move WNS
at 5.000ns on its own** (nothing can, by more than ~0.004ns, in a design with zero failing
endpoints). Its value is total optimiser effort, area, and headroom for the *next* constraint —
which is precisely the hypothesis under test.

**Distinct sub-shape worth separating: `exc_fsFrameBase` (#5, 4.8%) is arithmetic, not an array.**
`exception/ExceptionUnit.scala:1061`, a plain 32-bit `Reg` with **2 writers** (`:2076`, `:2098`).
Its consumers are **two 32-bit adders into the LS/DTLB address path** —
`fsFrameWordAddr = fsFrameBase + (step << 1)` (`:1097`) and `fsFrameBase + fsSize` (`:2358`) —
plus VPN slicing at `:2288/2290`. That is why its path is the carry-heaviest of the ROB families
(`CARRY8=4`, 1.730ns logic, the highest logic fraction outside DivEu). It is a **frame-address
generator feeding DTLB**, and it will *not* be fixed by the array fold; it is a separate, smaller
retime candidate. `exc_fsm_stateReg` (#8/#10) is different again: `ExceptionUnit.scala:1138`
declares **32 `new State`s** → a 6-bit state register with a 32-way `goto` mux whose every arm
drives shared outputs — a wide decode broadcast, 88% route, 5 levels, 89% CE.

### 4.2 `FetchAlignPlugin` `ftqHead` / `quiesce` / `ibuf.headPtr` — **control broadcast, plus one real barrel rotate** (10.9% + 0.5%)

* **`quiesce`** (#6, 3.5%) — `FetchAlignPlugin.scala:122`, `RegNext(quiesceNext)`: **one writer,
  ungated, one bit, zero logic depth of its own.** Its ~7 consumers (`:464`, `:557/601`, `:890`,
  `:974`, `:1025`, `:1037`, `:1106`) are all `&& !quiesce` AND-terms into wide control gates. The
  measured path carries **five separate nets of fanout ≥64** (165/164/144/144/110), is 79% CE, and
  spans 55 slice rows from a **single flop placed at one site** (bbox 0×0). This is a textbook
  un-replicated control broadcast.
* **`ftqHead`** (#2, 7.4%) — `FetchAlignPlugin.scala:278`, 5-bit, **2 writers** (`:1364` pop,
  `:1372` flush). It is the async read address of a 32-deep `Mem` (`ftqMem:277`), so the
  representative path shows `RAMD32`; the entry's fields then fan out to a 32-bit subtract
  (`ftqDiff = ftqHeadE.brPc - decodePc`, `:778`) and the mismatch comparator cone. The file's own
  FMax comment (`:295-320`) already names this arc.
* **`ibuf.headPtr`** (#8 by max datapath delay; 38 endpoints) — `InstructionBuffer.scala:86`, one
  writer with a 2-arm `when` (`:141-152`). **This is the one true shifter in the census**: `:156-172`
  is a 10-iteration loop each computing `(headPtr +^ i)` with a modulo-fold subtract and indexing
  `entries` — i.e. **ten parallel `BUF_WORDS:1` muxes over a full `Vec.fill(BUF_WORDS)(Reg(IbEntry()))`
  flop array**, plus a `BUF_WORDS × PUSH_WORDS` cross-product of per-slot write-enable compares at
  `:113-135`. Physically: 246-load first hop, 11 source flops spread 237 loads over 11×36, 75.8%
  route.

**Assessment: worthwhile, and the cheapest of the big three.** `quiesce` and `ftqHead` are exactly
the shape both proven local patterns already address (register duplication as in task #265,
keep-tagged broadcast as in `b6ac229`), and the frontend-quiesce localisation design
(`2026-08-10-frontend-quiesce-localization-design.md`) already exists and is directly applicable.
Low risk, no IPC cost, mechanical. `ibuf.headPtr` is a genuinely different, harder shape — a
barrel rotate over a flop array — and is small by TNS, so it is a *later* candidate, but it is the
one place in this core where the hypothesis's "shifter" category actually applies.

### 4.3 `DecodeStage` feed / packet / stash registers — **fanout-935 decode broadcast over 160-bit packet muxes** (11.6% across the feed/stash families)

* **`fed.payload.specs(1).spec_op`** (#3, 6.1%) — `DecodeStage.scala:78`, registered by
  `PipeStage` (`frontend/PipeStage.scala:16`), so it is a **flop bundle with exactly one
  write-enable**, not an array. Its single driver (`:163`) is the deep masked-pattern
  `computeOffload` decode, deliberately placed on the *pre*-register side. It carries a **935-load
  net** (`DecodeStage_logic_stashUops_2_fpWideImm[0]`, also in the design's global top-10 fanout
  list), 73% CE, 247 destinations, and the path is **pure LUT** (`LUT3=2 LUT5=3 LUT6=8`, zero carry).
* **`fed.payload.packets(N).words(M)`** (#9/#18, 3.5%) — `DecodePacket.scala:16`, **10 × 16b per
  packet × 2 packets**. ~60 read sites, including a `switch(idx)` **10:1 × 16b dynamic mux**
  (`:650`), shifted-window `Vec` selects (`:255/256`, `:290`, `:590/591`, `:734`), and — the
  expensive part — **full-packet-wide 2:1 muxes** ahead of ~20 further field extractions:
  `movemEntryPkt:830`, `movepEntryPkt:946`, `ucEntryPkt:1201`, each ≥160 bits. Family #9 terminates
  on `ucRomMem/ADDRARDADDR`, a BRAM address pin, with fanouts 167/129/89/59/44.
* **`stashUops`** — `DecodeStage.scala:195`, `Reg(Vec(DecodedUop(), 3))`, a 3-deep flop array of
  full uops. Important nuance: its **5 writer sites (`:2552, 2622, 2726, 2826, 2871`) all write the
  same source**, so they are five *enables* on one data path, not five data muxes — this is
  **not** a task-#127-shaped multi-writer array and should not be lumped with one.
* **`pushReg.valid`** (#22, 94% route, 3 levels, 408 loads) — `PipeStage` valid bit, 2 writers,
  narrow consumers. Its pressure is entirely in its *input* cone, which is why the stage exists.

**Assessment: worthwhile, and squarely in the named shapes.** A decode-time spec broadcast reaching
935 loads is a replication/pipelining problem, not a depth problem (13 levels, 1.097ns logic).
`fpWideImm` fanning to the whole stash is a strong candidate for a keep-tagged replicated
broadcast. The 160-bit whole-packet 2:1 muxes are a second, independent target: three of them sit
ahead of ~20 field extractions each, and narrowing them to the fields actually consumed is a
mechanical change.

### 4.4 `AluEuPlugin.s1Ctx_uop_op` / `uop_size` — **enum broadcast into wide result muxes** (11.6%)

`s1Ctx` is `RegNext(issuePort.payload)` (`AluEuPlugin.scala:163`) — **one writer, ungated**. The
pressure is entirely on the consumer side:

* **`u1.op`** is a `DecOp()` enum with ~35 members → 6 bits, driving **~26 equality comparators**
  (13 in AluEuPlugin, 8+ more in `AluDatapath.scala:43-51,117,118`), two enum `.mux()` trees
  (`:497`, `:520`), and the **main 12-way + default `cmd.op.mux(...)` 32-bit result mux**
  (`AluDatapath.scala:84`).
* **`u1.size`** is 2 bits driving **five 3-way × 32-bit byte/word/long partial-register merge
  trees** (`:409, :418, :456, :464, :480`), which then feed a 5-deep `mergedResult` Mux chain
  (`:509-513`).

Four netlist families (`uop_op`, `uop_op_1`, `uop_op_replica_1`, `uop_size`), 2,628 endpoints,
**0% CE** — genuine data paths, not enables. Destinations are `RobPlugin.nzvcValStore_33`,
`RobPlugin.sysValStore_45/33`, `LsEuPlugin.s1Index`. Spans are large: `uop_size` traverses
**dY = 114 slice rows — 47% of the die height**; `uop_op_replica_1` 42×84.

**Assessment: mixed — and it is the LS/IPC overlap.** `s1Ctx_uop_op` **already has replicas**
(`_1`, `_replica_1`); all of them are still failing and still spread, so replication has been tried
here and is not the answer. The arcs are AluEu → ROB value/flag stores and AluEu → LS index, i.e.
**exactly the writeback and address-generation arcs task #114 owns**. Crucially, most of those
destinations *are* the 4.1 arrays — so folding the ROB stores deletes the destinations rather than
retiming the paths to them. Sequence #127 first.

### 4.5 The route-dominated tail — **spread, not depth** (#16, #17, #22) and the D-cache correction

| family | RT% | levels | dX × dY | reading |
|---|---|---|---|---|
| `RobPlugin.exc_sysCapRc` → `RegFilePluginInt.ram` | **90%** | 5 | 72 × 77 | 0.478ns logic, 4.313ns wire |
| `RobPlugin.exc_fsm_stateReg` (rep 0) | 88% | 5 | 61 × 82 | 32-state FSM bit, 191 loads, 89% CE |
| `IssueQueuePlugin.lines_0_ways_0_sel` | 81% | 9 | **68 × 111** | 0.932ns logic — but see below |
| `_zz_DecodeStage.pushReg_valid` | **94%** | **3** | 51 × 69 | 0.262ns logic, 4.186ns route, 408 loads |

`RegFilePluginInt_logic_ram` is 9,694 cells spanning **73 × 125 slices — 65% of die width and 52%
of die height.**

Three levels of logic and 94% route is not a depth problem by any definition; no retime helps a
path that is 0.262ns of LUT and 4.186ns of wire. **These are floorplan/placement items and belong
to task #115, not to an RTL slice.** And again: *no congestion window above level 5* — spread, not
congestion, so the lever is region constraint / cluster co-location, not congestion relief.

**One qualification, and it matters.** `IssueQueuePlugin.lines_0_ways_0_sel` is not *purely* a
placement artifact. `IssueQueuePlugin.scala:110` gives 16 independent `sel` flops (2 ways × 8
lines) forming a **compaction shift register** (writers at `:164` hold, `:804` shift, `:822/833`
insert, `:1267` flush). Every one of them feeds **every wakeup comparator** (`:861, 887, 908, 926,
945, 961, 992`), then **five `OHMasking.first` priority encoders** (`:414-439`), then **five 16:1
`MuxOH` muxes over the full `IqContext`** (`:478-486`). That all-to-all structure is *why* the
cluster spreads over 68×111 slices — the structure causes the placement problem. The in-source
comment at `:443-446` already names this arc as FMax-critical. So it is a legitimate #114 target
(narrow the select cone) *and* a #115 target (co-locate the cluster), not merely the latter.

**Correction to an easy misreading of family #12.** `DcachePlugin.tagMem` is **not** a LUTRAM tag
array — `DcachePlugin.scala:129` forces `ram_style = "block"` precisely because it was inferring
distributed RAM and showing up in the placer congestion dump (comment `:123-128`). The failing arc
`tagMem_3 → dirtysMem_0/DP.HIGH/WE` is therefore **BRAM read → LUTRAM write-enable**. The
interesting half is the destination: `dirtysMem` (`:148`) is `Mem(Bool(), 128)` × 4 ways with **one
physical write port per way but six logical writers** muxed in combinationally
(`dirtysVoteD0..D5`, `:211-216`); `validsMem` (`:147`) is the same with four. That fold is task
#240's, already landed and already a win — so #12 is evidence the pattern *works*, and the residue
is the write-port arbitration mux, not the memory.

### 4.6 `DivEuPlugin.s1FpSrc` — **inherent arithmetic depth** (0.6%) — cross-reference, not re-litigation

`59dbc435` established this family's shape in full (single-cycle `floatx80_to_float64`: exponent
bias subtract → denormal shift count `s0_f64Cnt1` fo=137 → 64-bit shift-and-jam fo=65 →
round-to-nearest-even carry-propagate add; `CARRY8=16`, 26 levels, 47% logic; only another
pipeline stage applies, and that is not IPC-free because `FPSTORECVT` already holds S1 for two
cycles on a single-outstanding lane). **That analysis stands and is not repeated here.**

What this census adds is its *size*: **103 endpoints, 8 destination registers, 0.6% of TNS,
rank #20 of 119.** It is simultaneously the **worst-slack** family and one of the **smallest**
structures in the design — the only family in the top 20 that is logic-dominated (53% route) and
carry-heavy, and it is a local island (bbox 7×4, path span 7×11). `59dbc435` concluded "don't
touch it"; this census independently confirms that from the opposite direction and explains *why*
it looked important: **worst-slack ranking systematically over-weights small deep cones and
under-weights large shallow ones.** That is a methodological finding worth keeping.

---

## 5. Assessment summary

| rank | family | structural shape | assessment |
|---|---|---|---|
| **1** | `RobPlugin` fault/val/nzvc store arrays (+10 siblings) | **multi-writer flop array — 6 writers × 64 entries × 32b — plus a 64:1 MUXF7/F8 read mux and a fanout-1013 write-enable decode**; hand-unrolled `keep` mitigation already present and exhausted | **YES — the single biggest target in the core, by 5×.** Task #127. Matches every named shape. Subsumes families #1/#5/#7/#8/#10/#16/#19. |
| **2** | `FetchAlign.quiesce` / `ftqHead` | **un-replicated control broadcast into CE webs** — one flop, five nets ≥64 fanout, 55-row span, zero logic depth | **YES — cheapest win.** Both proven fanout patterns apply directly; the localisation design doc already exists. |
| **3** | `DecodeStage.fed_payload_specs/packets` | **fanout-935 decode broadcast** (pure LUT, 73% CE) + **three ≥160-bit whole-packet 2:1 muxes** ahead of ~20 field extractions each | **YES.** Replication for the broadcast; mux narrowing for the packets. No depth problem. |
| 4 | `IQ.lines_*_sel` → `selPorts_*_rData` | 16-flop compaction shift register → every wakeup comparator → **5 `OHMasking` priority encoders** → **5 × 16:1 `MuxOH` over full `IqContext`** | **YES, jointly with #115.** The all-to-all structure *causes* the 68×111 spread. Small by TNS (0.9%) but named FMax-critical in-source. |
| 5 | `AluEu.s1Ctx_uop_op` / `uop_size` | 6-bit enum → ~26 comparators + a 13-way 32b result mux; 2-bit size → five 3-way 32b merge trees. 0% CE, **already replicated**, die-spanning (dY 114) | **PARTIAL — sequence after #127.** Replication tried and insufficient; most destinations *are* the ROB arrays. Task #114 arc. |
| 6 | `ibuf.headPtr` | **barrel rotate** — 10 parallel `BUF_WORDS:1` muxes over a flop array + a `BUF_WORDS × PUSH_WORDS` write-enable cross-product | **LATER.** The only real "shifter" shape in the census, but small by TNS. |
| 7 | `Dcache.tagMem_3` → `dirtysMem` WE | BRAM read → LUTRAM write-enable with **6 logical writers on 1 physical port** | **PARTIAL.** Evidence the task #240 fold pattern *works*; residue is write-port arbitration, not the memory. |
| — | `RobPlugin.exc_sysCapRc/Val`, `exc_fsm_stateReg`, `pushReg_valid` | **88–94% route, 3–5 logic levels, spans to 72×77** | **NO — placement, not RTL.** Task #115. |
| — | `DivEu.s1FpSrc` | inherent arithmetic depth, `CARRY8=16`, 26 levels, logic-dominated | **NO.** Confirmed by `59dbc435`; 0.6% of TNS, 8 destination registers. |

---

## 6. Reconciling with `59dbc435`

`59dbc435` concluded the netlist is "band-limited, not outlier-limited", that every single-family
fix is worth 0.000–0.004ns of WNS, and that there is no lever. **Nothing in this census contradicts
that, and its stop-work verdict on `s1FpSrc` is reinforced, not weakened.**

The two documents answer different questions:

* `59dbc435` asked *"which family limits WNS, and what is fixing it worth in MHz?"* Answer: none of
  them, ~0.004ns, stop. Correct — and it must be, because at 5.000ns the design has **zero failing
  endpoints**, so WNS is set by whichever passing path happened to end up last.
* This census asks *"which cones are structurally hardest, i.e. cost the optimiser the most
  effort?"* Answer: one family owns 39.2% and one plugin owns 55.4%. Also correct.

The reason `59dbc435` saw a flat 0.053ns band is that it read the **top-100 slack list**, which is
the committed flow's only output — 100 rows out of a 22,363-endpoint population, all drawn from the
narrow tip where the optimiser converged. Its own diagnosis of the mechanism was right ("under a
4ns target every path fails by ~1ns, so `phys_opt` pushes all families uniformly … the probe's
virtue is what equalises the families"). The correction is only this: **that uniform pressure
equalises *slack*, because slack is the optimiser's objective function. It does not equalise
population, TNS, fanout, writer count, mux depth or placement span — and those are what the
hypothesis was actually about.** Rank by the invariants the optimiser is not optimising, and the
separation is 60:1.

Practical consequence: **the top-100 slack matrix is the wrong standing artifact for lever
selection.** It was also `2a144d61`'s own "calibration finding" from a different angle (synth-only
OOC pointed at the wrong family than post-route did). A TNS/population census over the
over-constrained failing set should become the standing report.

---

## 7. Feeds into the tracked efforts

### Task #114 — LS/IPC overhaul program

* The AluEu `s1Ctx` cluster (11.6% of TNS, 2,628 endpoints, 0% CE) fails on exactly the arcs #114
  owns: **AluEu → ROB `nzvcValStore`/`sysValStore` writeback** and **AluEu → `LsEuPlugin.s1Index`
  address generation**. `LsEuPlugin.alignedMem_2_vaddr` (207 endpoints) and
  `LsEuPlugin.p4Ctx_fwdStall → fwdData` (34 endpoints, `CARRY8=5`, 5 nets ≥32 fanout) are the
  LS-internal residue after `9c1f87e3`'s SQ drain-ack cut.
* The IQ select cone belongs here too: `sel → OHMasking.first → MuxOH(16) → uop.psrcA → PRF read
  address` (`IssueQueuePlugin.scala:414-486`, flagged in-source at `:443-446`) is the issue-latency
  arc, and its 5 parallel 16:1 full-`IqContext` muxes are the reason the IQ cluster cannot be
  placed compactly. Narrowing what `MuxOH` carries (select the *index*, read the payload from the
  already-registered `rData` side) is an IPC-neutral structural change.
* **Sequencing recommendation: do task #127 (ROB store fold) *before* any AluEu/LS retiming.** A
  large share of the AluEu families' 300 / 186 / 92 destination registers *are* the ROB store
  arrays. Folding those to memory deletes the destinations rather than retiming the path to them,
  and does it once for a cluster of fourteen arrays instead of once per consumer family.
* The dossier should carry the shape metric, not the slack metric: for each proposed slice, state
  the endpoint population and TNS share it removes, measured at 4.000ns.

### Task #115 — congestion + floorplan as a first-class gate metric

* **Congestion is not the problem; spread is.** Zero windows above level 5, yet 4 of the top 20
  families are 81–94% route with 3–9 logic levels. The gate metric should be **placement span of
  the failing population**, not congestion level.
* **Floorplan coverage is the gap.** `pb_decode` captures 23,603 cells and `pb_fetch` 16,369 — a
  little under 40k of roughly 166k placed cells, so **~76% of the design is in no pblock at all**,
  including *every* plugin in the top-8 by TNS except the FetchAlign/Decode ones. `2a144d61` was
  right not to widen the existing boxes; the actionable gap is the **absence** of a ROB /
  RegFile / IQ cluster box, not the size of the two that exist.
* **Concrete geometry to design against** (device is `SLICE_X0..112 Y0..239`):
  `RegFilePluginInt_logic_ram` 9,694 cells over **73 × 125** (65% × 52% of the die);
  `RobPlugin.sysValStore_20` 102 cells over 34 × 73; the `DcachePlugin.tagMem_3` name scope 23
  cells over **31 × 103**; `RobPlugin.head` 63 cells over 16 × 46. Note the *small* arrays are
  tightly placed (`faultedStore_16` 2×1, `faultAddrStore_16` 7×4, `s1FpSrc` 7×4) — the spread is in
  the large shared structures, so the co-location candidate is the **ROB ↔ RegFile ↔ IQ triangle**,
  whose sharpest single symptom is `exc_sysCapRc → RegFilePluginInt.ram` (90% route, 5 logic
  levels, 72 × 77 span).
* **Structure drives spread, so #115 and #114 are coupled, not independent.** The IQ cluster spans
  68 × 111 because five 16:1 full-`IqContext` `MuxOH`s connect every slot to every port; the
  RegFile spans 73 × 125 because it is a 9,694-cell LUTRAM bank with many ports. A pblock cannot
  compress an all-to-all structure — narrowing the structure is what makes the box feasible. Any
  ROB/RegFile/IQ floorplan A/B should therefore be run *after* #127, not before it.
* Project history records 7/7 floorplan regressions, so this is a *measurement* recommendation:
  add span-of-failing-population to the standing report first, and only then A/B a ROB/RegFile box.

---

## 8. Reproducing

```
# the 4.000ns-PRIMARY implementation (this is the diagnostic build; ~35 min + 9 postroute rounds)
IMPL_PROBE_PERIOD_NS=4.000 vivado -mode batch -source synth/impl_FullCore.tcl

# expected, and confirmed reproducible bit-for-bit on this netlist:
#   POSTSYNTH_FULLCORE_WNS_NS -1.236
#   POSTROUTE_ROUND 0..9  -1.385 -1.230 -1.224 -1.051 -0.958 -0.958 -0.958 -0.958 -0.958 -0.958
#   POSTROUTE_FULLCORE_RESULT FAILED_AT_250  ACHIEVED_FMAX_MHZ 201.694
#   SIGNOFF_200MHZ_WNS_NS 0.042  ->  MET_200
```

Archived evidence: `synth/archive/9c1f87e3_probe4ns_MET200_decode_fetch/`
(`fullcore_route_timing.rpt` = 4.000ns, 22,363 failing endpoints, TNS −9,825.314;
`fullcore_signoff_timing.rpt` = 5.000ns, **0** failing endpoints, TNS 0.000).

The census itself needs the routed checkpoint re-opened at 4.000ns and
`get_timing_paths -max_paths 40000 -nworst 1 -slack_lesser_than 0` grouped by `regkey` startpoint —
the committed `-max_paths 100` slack matrix is, per §6, too narrow a slice to rank structure.

---

## 9. Recommended standing-report change (not made here)

`impl_FullCore.tcl` currently emits a 100-row slack matrix. Adding a family census over the *whole*
failing population — count, TNS share, distinct destinations, CE fraction, max fanout, and
placement span — would make every future gate self-describing on the axis that actually selects
levers, and costs one extra `get_timing_paths` call. Deliberately **not** implemented in this pass:
a concurrent agent holds `synth/impl_FullCore.tcl` modified in the working tree, and this task was
scoped diagnostic-only.
