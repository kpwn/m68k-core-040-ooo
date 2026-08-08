# FMax closure, "Lever E": `pb_decode` pblock resize — floorplan-only design, **measured and REJECTED**

> **Verdict up front.** The proposed resize was designed, then tested as a **9-arm
> place-and-route sweep off one shared post-`opt_design` checkpoint** (netlist bit-identical
> across every arm; the pblock geometry is the only variable). **It regresses.** Widening
> `pb_decode` is monotone in the wrong direction on design WNS — **-1.829 -> -1.982 ->
> -2.071 -> -2.210 ns** as the box goes 40 -> 46 -> 52 -> 60 columns — i.e. the proposal
> (52 cols) costs **-6.84 MHz**. Widening `pb_dcache` instead costs -3.43 MHz;
> `EXCLUDE_PLACEMENT` fails placement outright.
>
> **Recommendation: make no floorplan change this round.** One configuration — widening
> *both* boxes together — is the sole non-regressing arm and replicated positive twice
> (+1.2 and +7.0 MHz); it is logged in §7 as a conditional candidate needing its own gated
> confirmation, and is deliberately *not* proposed here. Even its optimistic value is ~7 MHz
> against a ~24 MHz gap.
>
> The value of this spec is that the floorplan lever is now **closed by measurement rather
> than by argument** — exactly what the sibling specs asked for — plus two reusable
> findings: a mechanical explanation of *why* the die has no room (§6), and a previously
> undocumented **~6.8 MHz sensitivity to the order in which the two floorplan XDCs are
> read** (§5.2).

## Context

Part of FMax-closure round 3/4 (`.superpowers/sdd/progress-fmax-levers-2026-08-08.md`).
The session goal is binding: **post-route FMax >= 200 MHz with margin.** The current
combined, double-run, less-contended measurement is **~176 MHz** (WNS -1.674 / -1.693 ns);
the gap is **+24 MHz / +0.69 ns**.

Four RTL-level levers are in flight this round (Frontend Lever C, Lever U1, LS/ROB
Lever C, Lever D). A fifth path family — **`DecodeStage fed.packets -> pushReg`**, the
`MicroOpAssembler.assemble` cone, measured at **-1.268 ns (189.8 MHz)** — was grounded
independently and found to be structurally *unlike* every other family this round:

* It is a **band, not a path**: of 2274 worst-per-endpoint paths of the `pushReg` stage,
  **85 are worse than -1.000 ns** (the exact 200 MHz threshold), 225 worse than -0.800 ns,
  spanning ~40 `DecodedUop` fields across all four packed µop slots.
* It has a **measured floor of -1.187 ns (192.8 MHz)** on a path with **zero arithmetic
  content** (14 LUT levels, no CARRY8, 74.4 % route) — so no amount of RTL arithmetic
  simplification can take the family past ~192.8 MHz.
* Every path in the family is **66-75 % route-dominated**.

That combination pointed at placement, not logic depth — hence a floorplan lever. This
spec's change is **XDC-only. No RTL is touched, under any variant.**

---

## 1. Grounding: what is measurably true of `pb_decode` today

**Provenance.** §1 is `open_checkpoint` queries against `synth/fullcore_routed.dcp`
(written 2026-08-08 08:59), whose netlist `generated/M68kFullCoreSynth.v` carries
`// Git hash : d357f85...` — the exact commit behind this round's ~176 MHz baseline.
Design WNS on that checkpoint is **-1.693 ns = 175.65 MHz**, and its own #1 path
(`ibuf/entries_10_pred_lenWords_reg[1]/C -> fed_payload_specs_0_dstEa_disp_reg[6]/D`)
*also* terminates inside `pb_decode`.

### 1.1 `pb_decode` is over-subscribed and physically tears the family in half

| quantity | value |
|---|---|
| `pb_decode` range | `SLICE_X36Y0:SLICE_X75Y104` — **40 cols x 105 rows = 4200 SLICE sites** |
| leaf cells assigned | **19 601** (LUT 15 735 / FF 3 642 / other 224) |
| placed **inside** the box | 18 369 |
| placed **outside** the box | **1 229 (6.3 %)** (+3 unplaced) |
| actual placement bounding box | **X31..X79, Y3..Y106** vs a box of X36..X75, Y0..Y104 |
| SLICE-site occupancy inside the box (any cell) | **3983 / 4200 = 94.8 %** |

`pb_decode` has **never been resized** since it was defined (`39d99aa`, 2026-06-09), while
the netlist has grown continuously.

**The spill is directional and is almost entirely this family's two endpoint registers:**

| spill direction | cells | composition |
|---|---:|---|
| **right** (X76..X79) | **811** | **608 `pushReg`**, 151 `fed_payload`, 52 other |
| **left** (X31..X35) | **342** | **342 `fed_payload`** (100 %) |
| top (Y105..Y106) | 76 | 72 other, 4 `pushReg` |

**1105 of 1229 spilled cells (90 %) are `fed_payload` or `pushReg` FFs.** The placer
extruded `fed` through the *left* wall and `pushReg` through the *right* wall, separating
the launch and capture registers of a **single-cycle combinational cone** by up to **48
SLICE columns**. The grounding pass's trace of the -1.187 ns floor path shows the
consequence directly: `SLICE_X54Y71 -> X63Y46 -> X75Y42 -> X68Y27`, 74.4 % route.

### 1.2 A quarter of the box is squatted by cells that do not belong to it

`pb_decode`'s 18 369 in-box cells occupy only **2881 distinct SLICEs**, but 3983 SLICEs in
the box are occupied. So **>= 1102 SLICEs inside `pb_decode` (>= 26 % of the box) are held
exclusively by non-member cells.** A Vivado pblock without `EXCLUDE_PLACEMENT` constrains
its members *in* but does not keep non-members *out*.

Official pblock utilization from the control arm (§5) confirms the same picture from the
tool's own accounting — and shows `pb_dcache` is **worse off than `pb_decode`**:

| pblock (control arm) | CLB by own cells | CLB by foreign cells | total CLB / available | util |
|---|---:|---:|---:|---:|
| `pb_decode` | 3909 | 2197 | 4344 / 4200 | **103.4 %** |
| `pb_dcache` | 6043 | 2258 | 6181 / 5460 | **113.2 %** |

(The independent grounding pass measured 107.10 % / 102.84 % on the *shared* checkpoint.
The two orderings disagree because spill is a property of an individual placement run, not
of the netlist — **pblock over-100 % is a noisy diagnostic and should not be used as a
standalone justification.** That caveat is itself one of this spec's findings.)

### 1.3 It is spread, not routing congestion

`report_design_analysis -congestion` on **both** the control and the widened arms reports
*"No congestion windows are found above level 5"* — identical to the 2026-06-09 baseline.
The family's 66-75 % route fraction is **long-net spread (placement distance)**, not a
router fight. Calling it a "congestion wall" is imprecise; the correct term is a
placement-spread wall, and that distinction matters because relieving spread by giving a
structure more area only helps if the area is genuinely free (§6 shows it is not).

---

## 2. The precedent, read from the repo rather than paraphrased

Seven prior floorplan experiments exist. All numbers are full-core post-route, OOC 4 ns,
quoted from the commits themselves:

| # | commit | pblock | change | post-route | verdict |
|---|---|---|---|---|---|
| 1 | `1f8d440` | `pb_decode` | none -> `X36Y0:X75Y104` (40 cols) | 194.6 -> **211.5** | **+16.9** |
| 2 | `cebe62f` | `pb_decode` | -> `X40Y30:X71Y95` (32x66, **TIGHTER**) | 211.5 -> 208.8 | **regress -2.7** |
| 3 | `cef7710` | `pb_dcache` | none -> `X36Y110:X75Y214` (40 cols) | 221.5 -> **231.6** | **+10.1** |
| 4 | `01cc3b4` hdr v2 | `pb_dcache` | -> `X28Y110:X83Y214` (**WIDER BOTH SIDES**) | 231.6 -> 225.3 | **regress -6.3** |
| 5 | `01cc3b4` hdr v3 | `pb_dcache` | -> `X36Y70:X75Y174` (**SHIFTED DOWN**) | 231.6 -> 223.4 | **regress -8.2** |
| 6 | `818d83e` | `pb_dcache` | 40 -> 46 cols, **widen RIGHT only** | 208.8 -> **211.2** | **+2.4** |
| 7 | `4004bef` | `pb_dcache` | 46 -> 52 cols, **widen RIGHT only** | 191.7 -> **211.7** | **+20.0** |

### 2.1 The hypothesis this spec was built on

A sibling spec this round
(`docs/superpowers/specs/2026-08-08-fmax-lsrob-leverc-writeenable-flatten-design.md`,
Non-goals) cites the header as recording "three iterations where looser/shifted pblocks
*regressed*". That is accurate as a count but misattributes the cause: the three
regressions are **one tighter box (#2), one widened-on-both-sides box (#4), and one
shifted box (#5)** — none is a left-edge-anchored widen-right. The only maneuver with a
success record is exactly a left-edge-anchored widen-right in response to measured netlist
growth: attempted twice (#6, #7), succeeded twice, once for +20.0 MHz. The repo states the
rule itself (`floorplan_dcache.xdc:31-36`): *"keep the LEFT edge co-located with pb_decode
(X36) ... widen ONLY right ..., restoring ~v1 density ... without spreading."*

So the hypothesis was well-founded: **`pb_decode` is the one pblock that has never
received the one treatment that has always worked.** §5 shows the hypothesis is
nevertheless **false for `pb_decode` in isolation**, and §6 explains why the analogy to
`pb_dcache` broke.

---

## 3. The change that was proposed and tested

```tcl
# synth/floorplan_decode.xdc
-resize_pblock pb_decode -add {SLICE_X36Y0:SLICE_X75Y104}
+resize_pblock pb_decode -add {SLICE_X36Y0:SLICE_X87Y104}
```

**40 -> 52 columns; 4200 -> 5460 SLICE sites (+30 %). Left edge, Y span and cell filter
unchanged.** Design rationale as of §2: left edge pinned at X36 (the invariant both
successes kept and both losers broke); widen right only; terminal width 52 = the width
`pb_dcache` converged on, making the two boxes column-identical and preserving the
decode -> LS vertical adjacency; and — the load-bearing assumption — **the expansion
territory was believed to be nearly free**, measured at only **31.6 %** SLICE-site
occupancy (X76..X107 / Y0..Y104, 1061 of 3360 sites). §6 shows this assumption was the
error.

Device geometry, for the record (`get_clock_regions`, xcku5p-ffvb676): SLICE X0..X112,
Y0..Y239, 27 120 SLICEs; clock-region columns break at X0-28 / X29-55 / X56-89 / X90-112
and rows at Y0-59 / 60-119 / 120-179 / 180-239. `X36..X87` stays inside clock-region
columns X1+X2 — the 52-column variant crosses **no new** clock-region boundary; only the
60-column variant (X36..X95) reaches into column X3.

---

### 3.1 The alternative variant: `EXCLUDE_PLACEMENT`

§1.2 suggests a second, more surgical mechanism: keep the >= 1102 SLICEs of foreign logic
*out* of the box (`set_property EXCLUDE_PLACEMENT 1 [get_pblocks pb_decode]`) instead of
making the box bigger. LUT capacity is not obviously the binding constraint (15 735 LUTs
against 4200 x 8 = 33 600), so it is plausible on paper, and it has the attraction of not
annexing any territory at all.

It has **no precedent in this design** — neither existing pblock uses it — so it was
included in the sweep as a measured arm rather than argued about. §5 reports the outcome:
`place_design` fails.

---

## 4. Method — how the A/B was made honest

The sibling spec's demand was *"an A/B synth pair, not an argument."* This went further:

* **One shared `synth_design` + `opt_design`**, written to `fp/post_opt.dcp`, reused by
  **every** arm. Netlist, synthesis and optimization are therefore **bit-identical across
  all arms** — the only variable is the pblock geometry. (Netlist =
  `generated/M68kFullCoreSynth.v` @ `d357f85`, md5 `ddb0a5b6...`, byte-identical to the
  one behind the ~176 MHz baseline.)
* Each arm then ran the real flow tail: `read pblocks -> place_design ->
  phys_opt_design -> route_design`, mirroring `synth/impl_FullCore.tcl`'s ordering
  (pblocks applied after `opt_design`).
* All work in an **isolated `git worktree`**
  (`.claude/worktrees/fp-levere`, detached at `d357f85`). The shared tree was never
  written; other agents were implementing RTL in it concurrently.
* Metrics captured per arm: design WNS/FMax and its worst path; the target family's worst
  slack and its violating-endpoint counts at -1.000 / -0.800 ns (identical query, 1124
  paths enumerated in every arm); per-pblock cell spill; per-pblock utilization; and a
  congestion report.

**Known methodological offset, disclosed:** the control arm reproduces the *gated flow's*
netlist but not its exact number — **-1.829 ns here vs -1.693 ns in the gated run** — a
0.136 ns gap attributable to the checkpoint-split flow and machine contention. All
conclusions below are therefore **within-sweep comparisons only**; no absolute number here
should be quoted against the session baseline.

---

## 5. Results

**9 arms, one shared post-`opt_design` checkpoint, ~13-16 min of place+phys_opt+route
each.** `pb_dcache`'s baseline geometry is `SLICE_X36Y110:SLICE_X87Y214` (52 cols) unless
stated. All raw logs and reports: `.claude/worktrees/fp-levere/fp/`.

### 5.1 Primary sweep — XDC read order as in the gated flow (`decode` first)

| arm | `pb_decode` | `pb_dcache` | WNS (ns) | FMax | vs control | family worst | `< -1.0` | `< -0.8` | spill dec / dca |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|
| **A40 (control)** | X36..X75 (40c) | 52c | **-1.829** | **171.56** | — | -1.622 | **39** | **107** | 952 / 3821 |
| C46 | X36..X81 (46c) | 52c | -1.982 | 167.17 | **-4.39** | -1.436 | 71 | 151 | 1988 / 11281 |
| **B52 (the proposal)** | X36..X87 (52c) | 52c | **-2.071** | 164.72 | **-6.84** | -1.353 | 75 | 182 | 1321 / 8659 |
| E60 | X36..X95 (60c) | 52c | -2.210 | 161.03 | **-10.53** | -1.542 | 180 | 289 | 1295 / 9019 |
| F40X | 40c + `EXCLUDE_PLACEMENT` | 52c | **place_design FAILED** | — | — | — | — | — | — |
| G_DCA | X36..X75 (40c) | X36..X99 (64c) | -1.948 | 168.12 | **-3.43** | -1.448 | 115 | 294 | 1516 / 2596 |
| **H_BOTH** | X36..X87 (52c) | X36..X99 (64c) | **-1.790** | **172.71** | **+1.16** | -1.465 | 116 | 238 | **306 / 1895** |

**The proposal (B52) regresses by 6.84 MHz.** Widening `pb_decode` is monotone in the
wrong direction on design WNS across all three widths tried.

Two secondary observations, both against the lever:

* The **target family's *worst* path does improve** with width (-1.622 -> -1.436 -> -1.353),
  confirming the §1 mechanism was correctly diagnosed — but its **violating-endpoint count
  gets worse in every single arm** (39 -> 71 / 75 / 180 at -1.000 ns). Since 200 MHz
  requires the *band* to move, not the worst path, the family metric that matters is
  negative too.
* `pb_dcache`'s spill **2-3x'd (3821 -> 8659 / 11281) in arms that never touched
  `pb_dcache`** — the first direct evidence of the inter-pblock coupling that §6 explains.

`F40X` is a clean hard negative: `ERROR: [Place 30-642] Placement Validity Check : Failed
to find legal placement` / `[Place 30-99] Placer could not place all instances`. The §3.1
`EXCLUDE_PLACEMENT` alternative is not merely worse, it is infeasible at the current box
size.

### 5.2 Perturbation arms — and an unrelated fragility they exposed

To probe how much of the above is signal, the control and the best arm were re-run with
the two floorplan XDCs applied in the **opposite order** — nominally a cosmetic change.
It is not cosmetic: **`pb_decode` captures 29 890 cells when its XDC is applied first and
17 255 when applied second, while `pb_dcache`'s capture is unchanged (21 398 vs 21 397).**

| arm | `pb_decode` | `pb_dcache` | WNS (ns) | FMax | vs its own control | spill dec / dca |
|---|---|---|---:|---:|---:|---:|
| A40s (control, swapped order) | 40c | 52c | -2.070 | 164.74 | — | 1187 / 8438 |
| H_BOTHs | 52c | 64c | **-1.822** | 171.76 | **+0.248 ns / +7.0 MHz** | **0** / 5188 |

Two things follow.

1. **A ~0.24 ns / 6.8 MHz swing (A40 -1.829 vs A40s -2.070) is available from nothing but
   the order in which two constraint files are read.** This is a real, previously
   undocumented fragility in the gated floorplan — `synth/impl_FullCore.tcl` happens to use
   the better order (decode first), by accident rather than by design. It is also the
   honest ceiling on how much any single-run floorplan number here can be trusted.
2. **`H_BOTH` beats its own control in *both* orderings** (+0.039 ns and +0.248 ns), and its
   own two values agree to within 0.032 ns while the two controls disagree by 0.241 ns.
   The both-boxes-widened configuration is therefore both **directionally positive 2/2**
   and markedly **more robust to capture perturbation** — with `pb_decode` spill falling to
   952 -> 306 and, in the swapped order, to **exactly zero**.

### 5.3 What the numbers do and do not support

* **Supported:** widening `pb_decode` alone is a regression (-4.4 to -10.5 MHz, three
  widths, monotone). Widening `pb_dcache` alone is a regression (-3.4 MHz).
  `EXCLUDE_PLACEMENT` is infeasible. **The lever as specified in §3 is rejected.**
* **Supported, weakly:** widening **both** boxes is worth somewhere between +1.2 and
  +7.0 MHz. It replicated positive twice, but its lower estimate is inside the 0.136 ns
  (~4.4 MHz) flow-split offset disclosed in §4, and neither run used the real gated flow.
* **Not supported by anything here:** any claim that floorplanning can supply a meaningful
  share of the +24 MHz this session needs. The best measurement in the entire sweep is
  **172.71 MHz.**

---

## 6. Why it failed — the mechanism, measured

The load-bearing assumption in §3 was that X76..X107 / Y0..Y104 is spare area. Its **31.6 %
SLICE-site occupancy is true and misleading**: the region is sparsely *packed* but not
unclaimed. Classifying every cell placed there in the control arm:

| occupant | cells |
|---|---:|
| **`RobPlugin`** | **8 751** |
| **`IcachePlugin`** | **6 990** |
| `FetchAlignPlugin` | 1 184 |
| `DecodeStage` (the spill itself) | 775 |
| `RenamePlugin` | 757 |
| other / misc | ~1 100 |

So the "free" territory is where the **ROB and the I-cache live**. Widening `pb_decode`
into it does not consume slack — it **evicts the ROB and the I-cache**, and the eviction
lands on other critical families. That is visible directly in the results: the control's
worst path is a `DcachePlugin tagMem -> RobPlugin faultAddrStore` arc, and every arm that
widened `pb_decode` alone made the design worse while making `pb_dcache`'s spill 2-3x
worse (3821 -> 8659 / 11281 cells) despite `pb_dcache` itself being untouched.

This also explains the divergence from the `pb_dcache` precedent: when `pb_dcache` was
widened right in `818d83e`/`4004bef`, its expansion territory (X88..X107 / Y110..Y214) was
genuinely almost empty — **11.3 %** occupancy, 237 cells — so nothing had to be displaced.
`pb_decode`'s right-hand territory is not that. **The precedent's rule ("anchor left, widen
right") was necessary but not sufficient; the missing precondition is that the territory
being annexed must be unclaimed, and it is not.**

The coupling is confirmed constructively by the two-pblock arms: relieving `pb_decode`
alone regresses (it squeezes the LS cluster), relieving `pb_dcache` alone regresses (it
squeezes the ROB/I-cache band), and relieving **both** together is the only arm that
recovers to the control — because only then does the displaced logic have somewhere to go.
Spill collapses accordingly (`pb_decode` 952 -> 306, `pb_dcache` 3821 -> 1895). But the
resulting timing gain is **+1.16 MHz**, i.e. the die is **placement-saturated**: reshuffling
area between structures is close to zero-sum, and the ~24 MHz this session needs is not
sitting in the floorplan.

---

## 7. Decision and recommendation

**REJECT the lever for this round. `synth/floorplan_decode.xdc` and
`synth/floorplan_dcache.xdc` are unchanged by this spec** (it adds documentation only).

Specifically:

1. **Do not widen `pb_decode` alone.** Measured -4.4 to -10.5 MHz across three widths.
2. **Do not widen `pb_dcache` alone.** Measured -3.4 MHz.
3. **Do not add `EXCLUDE_PLACEMENT`.** `place_design` fails outright at the current box
   size (`[Place 30-642] Failed to find legal placement`) — a hard, unambiguous negative.
4. **The both-boxes variant (`pb_decode` X36..X87 + `pb_dcache` X36..X99) is a
   *conditional candidate*, not a claimed win.** It is the only non-regressing
   configuration, it replicated positive under both XDC orderings (+1.2 and +7.0 MHz), and
   it drives `pb_decode` spill to 306 / 0 cells. But its lower estimate sits inside the
   documented 0.136 ns flow-split offset and it has never been run through the real gated
   flow. **If — and only if — a spare gated slot exists, it is worth one confirmation
   double-run.** It must not be bundled into another lever's gate, because it would
   contaminate that lever's attribution. It is explicitly *not* part of this spec's
   proposed change, which is "no floorplan change".
5. **Do not spend more of this round on floorplanning.** Even the optimistic reading of
   the candidate above is ~7 MHz against a ~24 MHz gap. The remaining FMax must come from
   the RTL levers.
6. **Separately, note the read-order fragility** (§5.2): `synth/impl_FullCore.tcl` reads
   `floorplan_decode.xdc` before `floorplan_dcache.xdc`, and that ordering is worth ~6.8 MHz
   over the reverse purely through which cells each pblock captures. Nothing needs to change
   today — the current order is the good one — but the two XDC headers should eventually
   record that the order is load-bearing, so nobody "tidies" it.

### What would have to change for a floorplan lever to work

The sweep says the constraint is global, not local: `pb_decode`, `pb_dcache`, the ROB and
the I-cache are all competing for the same band of the die, and every pairwise
reallocation tested is zero-sum. A floorplan lever that actually moves this design would
have to be the **whole-die linear-pipeline plan** already sketched as step 4 of
`docs/analysis/2026-06-09-floorplan-strategy.md` (fetch -> decode -> rename -> IQ -> EU ->
regfile in adjacent regions, caches anchored to their BRAM columns, ROB given an explicit
home) — i.e. **five or six pblocks placed together as one plan**, not one box widened. That
is a dedicated-session change with a long iteration loop (each arm here cost ~13-16 min of
place+route even with synthesis amortized), and on this evidence it should not be attempted
opportunistically mid-round.

---

## 8. Non-goals

* **No RTL change of any kind**, under any arm. Zero verification surface: no lock-step
  run, no ported-test run, no functional-equivalence argument is required or claimed.
* **No new pblocks**, no hard-macro anchoring, no `LOC` constraints, no `CONTAIN_ROUTING`.
* **`EXCLUDE_PLACEMENT` is not adopted** — tested, fails placement (§5).
* **This lever never claimed to fix the family's logic.** The residual logic-depth items
  the grounding pass identified (the non-offloaded `EaDecoder` heads at
  `MicroOpAssembler.scala:518/1065/1961/2125/2261/2426`, and the `bfmDispLo`/`bfmDispHi`
  adder chain at `:2289-2294`) are worth ~+3 MHz together and are separate, still-open,
  cheap-and-provable RTL items.

## 9. Standing warning this sweep does **not** remove

Three of the four RTL levers in flight this round add netlist into or adjacent to
`pb_decode` (Frontend Lever C: ~481 FFs inside it; Lever D: ~550-700 LUTs, whose own spec
names `pb_decode` congestion as its principal risk; Lever U1: net-negative, harmless). The
box is at 94.8 % site occupancy with 1229 cells already spilling, and this sweep proves
**there is no floorplan escape valve** — widening the box is not available as a remedy if
those levers degrade placement. If the round's combined gate comes in below the sum of the
levers' standalone measurements, §6 is the explanation to reach for first, and the response
must be an RTL/area response (e.g. the LUT-reduction track), not a floorplan one.

## 10. Open questions for a plan-writing pass

This spec ends in a rejection, so **no implementation plan should be written for it.** If a
future dedicated floorplan session picks the thread up, these are the concrete open items
this sweep leaves behind:

1. **Is the both-boxes variant real?** It replicated positive under both XDC orderings
   (§5.2) but has never been run through the real gated flow. One gated double-run,
   in isolation, would settle it. Note its geometry
   (`pb_decode` X36..X87 / `pb_dcache` X36..X99) puts `pb_dcache`'s right edge into
   clock-region column X3 (X90..X112), the only arm geometry that crosses a clock-region
   boundary while still improving — worth understanding rather than assuming.
2. **Where should the ROB live?** It is the largest single occupant (8751 cells) of the
   contested band and has no pblock of its own. Giving it an explicit home is the most
   obvious missing piece of the floorplan, and step 3 of the 2026-06-09 strategy doc
   already anticipated it.
3. **`pb_dcache` is at 113.2 % CLB and spills 3821 cells** in the control — worse than
   `pb_decode` — yet widening it alone regresses. Any whole-die plan has to solve these two
   jointly, which is precisely the H arm's finding.
4. **The `*Plugin_logic*` cell filters still under-capture post-flatten** (a caveat both
   XDC headers have carried since June, still unaddressed). A whole-die plan should fix
   capture first, because every geometry conclusion here is conditional on which cells the
   filter actually caught.
