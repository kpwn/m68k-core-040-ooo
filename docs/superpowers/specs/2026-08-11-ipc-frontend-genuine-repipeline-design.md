# Genuine frontend re-pipelining: the deficit is a clock-enable population, not a path (design)

**Status:** design only. No RTL, no synthesis run for this document beyond one
read-only census on the pinned routed checkpoint. Awaiting explicit go-ahead
before any implementation.

**Branch:** `codex/ipc-dcache-vipt`, worktree
`/home/qwertyoruiop/m68k-core-040-ooo-worktrees/agent-01`.
**Baseline:** `6b246de` RTL + `FLOORPLAN_MODE=decode` + `IMPL_STRATEGY=postrouteN`
(`POSTROUTE_ROUNDS=3`) = **WNS −1.472 ns / 182.749 MHz**, reproduced exactly six
times (handoff §18 rows 11/15/16, §23.5's `M0_CONTROL`, §24.7's revert gate, and
this document's own probe control).

**Predecessors, all required reading before implementing this:**
`docs/superpowers/plans/2026-08-10-ipc-frontend-fmax-claude-handoff.md` §13–§26;
`docs/superpowers/specs/2026-08-10-ipc-fetchalign-icache-pipeline-design.md`
(the B2/B3 design — **partly superseded here, see §6**);
`docs/superpowers/plans/2026-08-10-ipc-fetchalign-icache-combined-implementation-plan.md`
(the 24-task plan — **its ordering rationale is reversed here, see §6.1**);
and, on the sibling branch `feat/rob-predictor-mem`,
`docs/superpowers/specs/2026-08-09-ipc-ls-eu-pipeline-depth-design.md` +
`docs/superpowers/plans/2026-08-09-ipc-ls-eu-late-split-implementation-plan.md`
(read for the *pattern* of what goes wrong, distilled into §8.3 and §11).

---

## Contents

- §0 Why this document exists, and what it corrects
- §1 The new measurement: the deficit is 64 % clock-enable
- §2 Goals / non-goals
- §3 The design principle: shallow-CE, registered-/D
- §4 The four boundaries
- §5 The CE-vs-D constraint, applied boundary by boundary
- §6 What this supersedes, and why B3 is not rebuilt
- §7 FMax projection, and the acceptance threshold
- §8 IPC cost, hazards, and the cross-file dependency sweep
- §9 The unavoidable risk of this whole strategic direction
- §10 Slices, and the early-cheap-canary ordering
- §11 Verification plan
- §12 Cost estimate
- §13 Open questions

---

## 0. Why this document exists, and what it corrects

Handoff §26.7 closed the FMax campaign with two options: accept 182.749 MHz, or
attempt **a genuine multi-stage architectural re-pipelining**, "not one cut, but
the several that would be needed to move a 29-family, 4,890-endpoint
distribution". The user has chosen the second, explicitly and twice ("we are too
close to 200 MHz to give up"). This document is that design.

It exists because the campaign's own conclusion was correct but incomplete. Every
lever was measured and closed: startpoint families (§15, §17), endpoint cones
(§19 step 9), precise fix points (§16, §19), the ITLB hit-way cone (§20), the
whole three-arc program (§24), the whole LSU cluster (§25), targeted placement
and the only BUFG (§26), all seven floorplan variants (§18), all four tool
directive axes (§19 step 8, §26.6), the speed grade (§26.9), and the elaboration
lottery (§21). Seven consecutive grounding passes each priced their named target
at ≈0.00 ns. What none of them measured is **what the 4,890 endpoints are**, as
opposed to where they start and end.

### 0.1 One correction to the dispatch premise, stated first

The dispatch for this design described the current worst path as
`DecodeStage fed_payload_packets_0_words_0[4] → FetchAlign predictPending/D`
(17 levels, route 4.063 ns of 5.432 ns). **That is not the current worst path.**
It is the path left standing in §25's `a3_cluster` *hypothetical* — after the
three frontend arcs **and** all 42,428 D-cache/LS-EU cells have been
false-pathed. On the untouched baseline it measures **−1.451 ns**, 0.021 ns
*behind* WNS, and §26.2 already retracted it on exactly these grounds.

This document's own probe control reproduces the real worst path:

```
PROBE_CTRL WNS    -1.472
PROBE_CTRL START  DcachePlugin_logic_stS2Payload_paddr_reg[5]/C
PROBE_CTRL END    IssueQueuePlugin_logic_sbNzvc_busy_reg[8]/D
PROBE_CTRL LEVELS 21   LOGIC 1.733   NET 3.721
```

This is the **third** time a §25.4/§24.5-class residual has been carried into a
dispatch as though it described the real design (§26.7 records the first two).
The rule §26.7 wrote — *a target must be re-measured on the untouched baseline
before it is dispatched* — is restated here as a standing global constraint on
every task derived from this spec.

The correction does not change the strategic direction, and it does not remove
that path from scope: boundary **P2** below attacks it structurally rather than
by false-path. But it does change the projection, because it means the frontend
does **not** own the current WNS path. §7 prices that honestly.

---

## 1. The new measurement: the deficit is 64 % clock-enable

`synth/probe_pinkind_population.tcl` (committed with this document) is a
read-only census of the 4,890 endpoints below −1.000 ns on the pinned baseline
checkpoint, classified by **capture-pin kind** and cross-tabbed by
(startpoint family → endpoint family) with per-arc logic depth and route share.
It is a pure census, so there is no `set_false_path` to guard; the control above
is its validation. Evidence: `synth/probe_pinkind/`.

Handoff §24.5 reported a pin-kind split, but only over the **2,129-endpoint
residual** of the `a3_cluster` hypothetical. Nobody has taken it on the real
population. The result:

| capture pin kind | endpoints | share |
|---|---:|---:|
| **`/CE` (clock enable)** | **3,130** | **64.0 %** |
| `/D` (data) | 1,400 | 28.6 % |
| `/R` (sync reset) | 239 | 4.9 % |
| BRAM `ADDR*` | 83 | 1.7 % |
| other | 38 | 0.8 % |

**Nearly two-thirds of the endpoints standing between this core and 200 MHz are
clock-enable pins.** By endpoint family:

| endpoint family | endpoints | `/D` | `/CE` | `/R` | CE share |
|---|---:|---:|---:|---:|---:|
| `IcachePlugin` | 2,427 | 669 | **1,751** | 7 | 72 % |
| `DcachePlugin` | 703 | 239 | 231 | 144 | 33 % |
| **`RasPlugin`** | **496** | **0** | **496** | 0 | **100 %** |
| `RobPlugin` | 334 | 116 | 218 | 0 | 65 % |
| `IssueQueuePlugin` | 329 | 215 | 114 | 0 | 35 % |
| `FetchAlignPlugin` | 229 | 60 | 169 | 0 | 74 % |
| `LsEuPlugin` | 163 | 59 | 57 | 47 | 35 % |
| `DecodeStage` | 68 | 30 | 3 | 35 | 4 % |
| `FtbPlugin` | 61 | 1 | 60 | 0 | 98 % |
| `GsharePlugin` | 26 | 0 | 26 | 0 | 100 % |
| `DtlbPlugin` | 16 | 5 | 5 | 6 | — |
| others | 38 | 6 | 0 | 0 | — |

And by arc, with mean logic depth and mean delay split (arcs ≥ 25 endpoints):

| arc | n | `/D` | `/CE` | `/R` | mean levels | mean logic | mean net | route share |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| **`FetchAlign → Icache`** | **2,427** | 669 | **1,751** | 7 | **19.0** | 1.718 | 3.454 | 66.8 % |
| `Dcache → Dcache` | 703 | 239 | 231 | 144 | 13.2 | 1.690 | 3.384 | 66.7 % |
| **`DecodeStage → Ras`** | **496** | **0** | **496** | 0 | 16.0 | 1.247 | 3.831 | **75.4 %** |
| `Dcache → IssueQueue` | 323 | 209 | 114 | 0 | 19.8 | 1.566 | 3.642 | 69.9 % |
| `Dcache → Rob` | 233 | 15 | 218 | 0 | 13.3 | 0.987 | 3.982 | 80.1 % |
| `FetchAlign → FetchAlign` | 126 | 43 | 83 | 0 | 19.3 | 1.697 | 3.397 | 66.7 % |
| `Dcache → LsEu` | 111 | 7 | 57 | 47 | 18.0 | 1.481 | 3.573 | 70.7 % |
| `DecodeStage → FetchAlign` | 103 | 17 | 86 | 0 | 16.6 | 1.365 | 3.757 | 73.4 % |
| `AluEu → Rob` | 98 | 98 | 0 | 0 | 12.7 | 1.203 | 3.905 | 76.4 % |
| `DecodeStage → DecodeStage` | 68 | 30 | 3 | 35 | 14.0 | 1.321 | 3.665 | 73.5 % |
| `FetchAlign → Ftb` | 61 | 1 | 60 | 0 | 19.0 | 1.674 | 3.374 | 66.8 % |
| `LsEu → LsEu` | 34 | 34 | 0 | 0 | 17.0 | 1.566 | 3.531 | 69.3 % |
| `AluEu → OTHER` | 28 | 0 | 0 | 0 | 10.9 | 1.070 | 3.929 | 78.6 % |

### 1.1 Why this reframes everything

Handoff §23.5 established, from a real controlled A/B with a same-session
control arm, that the iterated post-route loop's ability to improve a netlist
depends on what kind of pin the limiting cone terminates on:

> `predictTargetReg[14]/C → s1PredEntries_0[122]/CE` is a **clock-enable** cone,
> which post-route optimisation has far less freedom to fix than a data path.
> … "B3 is bad" is the wrong lesson; "B3 creates an optimisation-resistant
> structure" is the right one.

The numbers behind that: baseline gains **+0.622 ns** across three post-route
rounds and is still gaining at round 3; B3's netlist started *better*
(round 0 −1.836 vs −2.094) and gained only **+0.110 ns**, finishing 0.254 ns
worse. Six further independent interventions (§19 step 8, §26.6, §26.9 — two
placer/phys-opt directives, two synthesis directives, one router directive, one
faster silicon grade) show the identical signature: easier start, dead
convergence, worse finish.

Put the §23.5 lesson and the §1 census together and the campaign's whole shape
falls out:

- The deficit is **64 % CE**. Those endpoints are, by §23.5's measurement, the
  ones the tool is least able to help with — which is why iterated post-route
  work banked +18.65 MHz and then stopped, and why every subsequent lever
  measured ≈0.00 ns.
- The three most CE-saturated families are `RasPlugin` (100 % CE),
  `GsharePlugin` (100 %), `FtbPlugin` (98 %) and `IcachePlugin` (72 %) — the
  entire branch-prediction and instruction-cache state of the design.
- Those families have **low logic delay and very high route share**
  (`DecodeStage → Ras` is 1.247 ns of logic against 3.831 ns of net, 75.4 %
  route, at only 16 levels). A CE broadcast to hundreds of flops is a *load*
  problem, exactly as §18 step 2 and §26.3 both concluded from geometry.

**The correct architectural target is therefore not "insert stages into data
paths". It is: get the deep combinational verdicts off the wide clock-enable
broadcasts.** That is a different change from anything this campaign has tried,
it is the precise inverse of what B3 did, and it is what §3 specifies.

### 1.2 There are exactly two deep enable cones in the frontend

An RTL sweep of every frontend register's enable expression (all four files:
`FetchAlignPlugin.scala`, `IcachePlugin.scala`, `InstructionBuffer.scala`,
`DecodeStage.scala`, plus `Ftb.scala`/`Gshare.scala`/`Ras.scala`) resolves the
3,130 CE endpoints onto **two** combinational cones, and nothing else:

**Cone A — `ic.cmd.fire`.** `= {FetchAlign issue gates} && xlate.rsp.ready &&
(isHit || lookupFault)` — i.e. the ITLB way-mux and the L1I four-way physical tag
compare are inside a clock enable. Its CE consumers:

| register(s) | file:line | flops |
|---|---|---:|
| `s1Way/s1Pc/s1Fault/s1Atc/s1Lane/s1FromMiss/`**`s1PredEntries`** | `IcachePlugin:676-694` | **~1,040** (`s1PredEntries` is `Reg(Vec(Bits(256), ways))` at `:328` = **1,024**) |
| `miss{PC,PA,Set,Tag,Cacheable}`, `victimWay`, `beatCnt`, `arSent`, `missPoison` | `IcachePlugin:701-714` | ~130 |
| `pfSeqValid/pfDemandLine/pfNextPa/pfLimitPa` (window seed) | `IcachePlugin:546-564`, called `:671` | ~100 |
| `pfValid/pfArSent/pfComplete/pfBeat/pfErr/pfPoison/pfPa/pfSet/pfTag/pfWay` (allocator, via `heldDemandMiss`) | `IcachePlugin:840-863` | ~200 |
| `arHoldValid/Id/Addr` | `IcachePlugin:890-901` | ~40 |
| `fetchPc`, `ringStale/ringDrop/ringKeep/ringPlanSeq`, `ringTail`, `planSeq`, `pendingDrop`, `ringCount` | `FetchAlignPlugin:563-581`, `:668-672` | ~60 |
| `resultExpectedSlot/Seq/BornStale`, `targetHoldValid/Pc/Drop` | `FetchAlignPlugin:348-354`, `:587-598` | ~50 |
| **FTB `rspPayload`** (41 bits) and **gshare `winPayload`** — their CE is `ftbCmd.valid = ic.cmd.fire && !issueBornStale` | `Ftb.scala:89-98`, `Gshare.scala:123-129` | ~60 |

**Cone B — `feed.fire`.** `= {the aligner cone} && {the three-deep `PipeStage`
reverse-`ready` chain}`. Its CE consumers:

| register(s) | file:line | flops |
|---|---|---:|
| **`RasPlugin` `ras/rasSp/count`** — via `rasPushValid`/`rasPopValid` | `Ras.scala:64-76` ← `FetchAlignPlugin:1126-1128` | **576** |
| gshare `ghr` — via `gsShiftValid` | `Gshare.scala:155-157` ← `FetchAlignPlugin:1114-1116` | 16 |
| `predictTargetReg` — via `predictDetect` | `FetchAlignPlugin:1048` | 32 |
| `decodePc`, `stalled`, `faultEmitted`, `ftbSuppress`, `ftqMismatchPcReg/BrPcReg`, `ftqHead` | `FetchAlignPlugin:1002`, `:1037-1041`, `:1086-1092`, `:1246-1248`, `:1263-1272` | ~110 |
| `raw`/`fed`/`pushReg`/`uopsStaged` payload banks — via `slotFree` | `PipeStage.scala:17` | ~2,000 |
| `stashValid/stashUops/stashCount` | `DecodeStage.scala:1985-2003` | ~150 |

**That is the whole design in one sentence: every clock-enable endpoint in the
frontend deficit hangs off `ic.cmd.fire` or `feed.fire`; P1 and P3 shorten the
first, and P2 shortens the second.**

Two corrections to received wisdom fall out of the sweep and are recorded here
because they will otherwise be re-derived:

- **There are exactly FOUR `PipeStage` instantiations in `src/main`, not seven**
  (`DecodeStage.scala:113 raw`, `:146 fed`, `:1954 pushReg`,
  `RenameStage.scala:316 uopsStaged`), and the generated Verilog contains exactly
  four `when_PipeStage_l17*` nets. Handoff §26.2's "all 7 `PipeStage` `slotFree`
  enables" was counting **Vivado net names** — hierarchy-prefixed aliases and
  phys-opt replicas of those four — which is §18 step 3's instance-path trap
  reproducing one more time. Three of the four are in the frontend.
- **`FetchAlign predictPending` is a `/D` endpoint, not `/CE`.** Its
  unconditional default (`predictPending := False`, `FetchAlignPlugin:325`)
  makes the synthesised flop always-enabled with
  `D = predictDetect && !predictDetectBlocked`. That is why the census puts
  `DecodeStage → FetchAlign`'s 17 `/D` endpoints where it does, and it is why
  P2's benefit there is a *depth* reduction on a `/D` cone rather than a CE
  conversion.

---

## 2. Goals / non-goals

### 2.1 Goals

1. **Convert deep-`/CE` capture cones into shallow-`/CE` + registered-`/D`
   capture**, across the frontend families that own 64 % of the deficit
   population. Every new or relocated boundary must be shown, in RTL terms and
   confirmed on the routed netlist, to land its captured state on a `/D` cone.
2. **Reduce the mean logic depth of the `FetchAlign → Icache` arc** (2,427
   endpoints at 19.0 mean levels) by inserting exactly one genuine architectural
   pipeline stage at the measured midpoint.
3. **Sever the DecodeStage/Rename reverse-`ready` chain**, which is a four-stage
   combinational arc that terminates on 496 `RasPlugin` CE pins (100 % CE), 86
   `FetchAlign` CE pins and 26 `Gshare` CE pins — at **zero cycle cost**.
4. **Report the post-route *iteration curve*, not just the final WNS**, on every
   gate, and treat a B3-shaped curve as a hard stop (§10.2).
5. **Keep resident-hit fetch II = 1.** Unchanged binding constraint.

### 2.2 Non-goals

- **Any change under `DcachePlugin`, `LsEuPlugin`, `StoreQueue` or the D-side of
  `DtlbPlugin`.** §25 measured the entire LSU cluster, granted every conceivable
  internal restructuring simultaneously, at **+0.000 ns**. It is out of scope by
  dispatch and by evidence. Note §7.2 states plainly what that costs this design.
- **Rebuilding B3** (the registered `pfInstallArm` install decoupling). §6.2.
- **`Tlb.scala` / `ItlbPlugin.scala`.** Shared with the DTLB; the new register
  lives in `IcachePlugin` as a stage of the fetch pipe. Inherited unchanged from
  the B2 design's §1.2.
- **Deepening either TLB, adding a micro-ITLB, or tagging `FetchRsp`.** Standing
  rules; the single-occupancy contract on the untagged `FetchRsp` is preserved
  exactly (§8.2).
- **Any register on the `decodePc → Aligner → effShift → ibuf.io.shift →
  headPtr` loop.** `Btb.scala:146-152` states it outright: *"Registering anything
  on this arc would halve front-end IPC."* It is a genuine combinational
  feedback loop closed through `headPtr`/`decodePc`, and it is the reason
  `FetchAlign → FetchAlign` (126 endpoints, 19.3 levels) cannot simply be
  pipelined. P4 is the only sanctioned intervention near it, and it works by
  re-sourcing a *guard* (`extW*Valid`) from an already-registered value, not by
  cutting the loop.
- **Any register between the registered `doFlush` broadcast and the frontend**
  (`FullCoreSynth.scala:73-74`). Commit-time mispredict recovery is 2 cycles
  today (C: `doFlushReg` set; C+1: `fetchPc`/`decodePc` written, in-flight
  command born stale; C+2: new PC on the ITLB/L1I inputs). A register here adds
  +1 to *every* architectural mispredict — the highest-cost-per-cycle place in
  the design to add one.
- **Floorplan, tool directives, synthesis directives, speed grade, re-elaboration.**
  All closed and all measured negative. No task from this spec may propose one.
- **Area reduction.** 51.01 % LUT, 11.61 % FF, no congestion window above level 5.
  This design *adds* flops (§12) and that is correct.
- **Reaching 200 MHz on frontend work alone.** §7.2 states why that is not
  projected, in advance, so it cannot be re-narrated afterwards.

---

## 3. The design principle: shallow-CE, registered-`/D`

Every boundary in §4 is an instance of one rule, stated once here so each
boundary can be checked against it mechanically.

> **A wide register bank must never take its clock enable from a deep
> combinational verdict. The verdict is captured into a narrow `/D` register;
> the wide bank's enable becomes a shallow, local expression (or is removed
> entirely); and the verdict register qualifies *consumption* rather than
> *capture*.**

Three properties make this the right shape here, and each is measured rather
than asserted:

1. **It is the inverse of B3's failure mode.** B3 took a deep verdict, registered
   it, and then used the register's `Q` as the enable of a 512-bit capture and a
   fanout-518 select — i.e. it kept the wide bank on a CE and merely moved the
   depth to the other side of a flop. The new limiting path was
   `predictTargetReg[14]/C → s1PredEntries_0[122]/CE`: still a CE endpoint,
   still 19-deep, and now optimisation-resistant. This rule forbids exactly that.
2. **The codebase already applies it one level upstream, deliberately.**
   `IcachePlugin.scala:645-647`:

   > *Arm the BRAM solely from virtual page-offset bits, in parallel with the
   > ITLB and tag lookup. **Never gate this enable with `hitVec`/`isHit`**:
   > doing so would put translation and tag comparison back on the BRAM ENARDEN
   > path.*

   The BRAM read enable is `cmdPort.valid && !setBlocked` — shallow, by explicit
   design decision. The *capture* of that BRAM's output into `s1PredEntries` is
   nonetheless placed inside `when(cmdPort.fire)` (`:693`), i.e. behind the full
   ITLB + physical tag compare. Boundary **P1** extends an already-established,
   already-documented principle from the array enable to the pipeline register
   one hop downstream. It is not a new idea in this codebase; it is an
   unfinished one.
3. **It costs nothing in cycles when the bank is a true one-cycle pipeline
   register.** `s1PredEntries` is consumed on the very next cycle
   (`IcachePlugin.scala:432`, `windowPred(s1PredEntries(s1Way), s1Pc)`, with
   `rspValidReg := s1Valid` at `:433`). Capturing it unconditionally and letting
   `s1Valid` qualify the read is bit-identical at every cycle the value is
   observed. The cost is toggle power on a ~930-flop bank, not area, not latency,
   not throughput.

**Where the rule does not apply, and must not be forced:** a register that
*holds* state across many cycles cannot be free-run. `lineReg` accumulates a
64-byte line across ~70–78 cycles of AXI beats; its write enable is genuinely
architectural. §4 does not touch it, and §6.2 explains why the B3-shaped attempt
on it is not repeated.

---

## 4. The four boundaries

Ordering below is by *structure*, not by execution order; the execution order is
§10 and it is deliberately different.

### 4.1 P2 — elastic de-chaining of the DecodeStage/Rename `ready` path

**Owns cone B in full** (§1.2): `DecodeStage → Ras` **496 endpoints (100 % CE,
the `RasPlugin`'s entire 576-flop array)**, `DecodeStage → FetchAlign` 103
(86 CE, 17 `/D` — the `predictPending`/`predictTargetReg` group), the
`feed.fire`-gated share of `FetchAlign → FetchAlign` 126, the gshare `ghr` share
of `Gshare` 26, and the **−1.451 ns residual path §25.4 named**. **≈ 600–700
endpoints, overwhelmingly CE.** (Note: the *rest* of `Gshare`'s 26 and all of
`FetchAlign → Ftb`'s 61 hang off `ic.cmd.fire` — cone A — via
`Gshare.scala:123-129` / `Ftb.scala:89-98`, so they belong to P1/P3, not here.)

**What is wrong today.** `frontend/PipeStage.scala` is the design's generic
1-deep elastic stage:

```scala
val slotFree = !valid || out.ready
when(slotFree) { valid := in.valid; data := in.payload }
when(flush)    { valid := False }
in.ready    := slotFree
```

`in.ready` is `out.ready` plus one OR term. **All four instances of the primitive
in `src/main` chain** (`DecodeStage.scala:113 raw`, `:146 fed`,
`:1954 pushReg`, `RenameStage.scala:316 uopsStaged`; §1.2 corrects the "seven"
figure), so `DecodeStage.fed.ready` — which is
`!stashValid && !movemHoldsFed && !slot0IsMovem && !ucHoldsFed && !movepHoldsFed
&& !slot0IsMovep && pushProduced.ready || …` (`DecodeStage.scala:2004`), i.e. the
full `MicroOpAssembler.assemble` ownership/serialisation cone — propagates
**combinationally backwards through two `PipeStage`s** into `df.feed.ready`, and
FetchAlign gates *all* of its prediction bookkeeping on `feed.fire`:

```
FetchAlignPlugin:1045   predictDetect := feed.fire && !faultHold && predictedThisEmit
FetchAlignPlugin:1126   rasPushValid  := feed.fire && !faultHold && s0IsCall
FetchAlignPlugin:1128   rasPopValid   := feed.fire && !faultHold && rasPredictSlot0
```

`rasPushValid`/`rasPopValid` are the clock enables of the entire 576-cell
`RasPlugin`. That is why `DecodeStage → Ras` is **496 endpoints, 100 % CE,
16.0 levels, 75.4 % route** — the deepest-routed arc per level in the whole
census. §17 root-caused this correctly and priced the fix at +0.006 ns; §26.2
false-pathed all seven `slotFree` enables for +0.000 ns. **Both are
path-deletion measurements and neither models what a skid buffer does to the
cone's depth** (§9.1).

**The change.** Replace `PipeStage` with a **two-entry skid** in which
`in.ready` is a function of local occupancy registers only:

```
mainValid, mainData      -- the existing forward register
skidValid, skidData      -- new: one extra payload bank
in.ready  := !skidValid                    -- 1 level, purely local
out.valid := mainValid
out.payload := mainData
```

with the standard skid discipline (accept into `main` when `main` is empty or
draining; otherwise into `skid`; refill `main` from `skid` on drain). Throughput
is preserved by construction — the skid absorbs exactly the one cycle of
in-flight data that the combinational `ready` used to prevent.

**Captured state and pin kind.** `mainData`/`skidData` are payload banks with a
`/D` forward path and a **1–2 level `/CE`** (`in.valid && !skidValid`,
`out.ready && skidValid`). `mainValid`/`skidValid` are 1-bit `/D` registers.
The deep cone (`assemble` → `fed.ready`) still exists, but it now terminates on
a **1-bit `/D`** (`skidValid`'s next-state) instead of fanning out as the clock
enable of the RAS, the Gshare PHT, `predictPending`, `predictTargetReg` and four
payload banks. ✔ Rule satisfied.

**Cycle cost: zero.** The forward latency of each stage is unchanged at one
cycle; a skid adds a *buffer*, not a *stage*. Steady-state throughput is
unchanged. What changes is the **acceptance cadence** — the frontend may now
accept one group further ahead of a stalled consumer. That is not an IPC cost
(it is, if anything, an IPC gain), but it *is* a real behavioural change and
§8.1 lists the re-proof obligation it creates.

### 4.2 P1 — shallow-CE S1 capture in the I-cache

**Owns the widest part of cone A:** the CE share of `FetchAlign → Icache` —
**1,751 endpoints**. `s1PredEntries` alone is `Reg(Vec(Bits(256), ways))`
(`IcachePlugin.scala:328`) = **1,024 flops**, and §18 step 2 measured the
terminal enable net at **931 loads / 0.540 ns** on the worst path. The remainder
is `lineReg`, the `miss*` context, the prefetch window/allocator and `arHold*`.

**What is wrong today.** `lookupTick` (`IcachePlugin.scala:642`) computes
`cmdPort.ready := xlate.rsp.ready && !setBlocked && answerable` at `:661` —
behind the live ITLB translate and the physical tag compare — and then places
every S1 capture inside `when(cmdPort.fire)`. The routed consequence, from
§18 step 2's `report_design_analysis` path #1:

```
FDCE/C -(46)- LUT2 -(4)- LUT6 -(1)- LUT6 -(143)- LUT6 -(1)- LUT3 -(176)-
LUT2 -(164)- ... -(113)- LUT6 -(931)- FDRE/CE
Path delay 5.990 ns = logic 2.019 (33.7%) + route 3.971 (66.3%), 20 levels
```

The last hop is **one LUT6 output reaching 931 flip-flops**, and it is a clock
enable.

**The change.** Split capture from qualification:

| today | after P1 |
|---|---|
| `when(cmdPort.fire) { s1PredEntries := lookupPredEntry }` | `when(s1CaptureEn) { s1PredEntries := lookupPredEntry }` where `s1CaptureEn := cmdPort.valid && !setBlocked` — the **same shallow expression the BRAM enable already uses** |
| `s1Way := hitWayIdx` inside the same `when` | unchanged: `s1Way` is `wayBits` wide (2 bits) and stays on the deep verdict, as a `/D` capture |
| `s1Valid := True` inside the same `when` | unchanged: 1 bit, `/D`, deep cone — this is the *only* place the verdict is allowed to be deep |

`s1Fault`, `s1Atc`, `s1FromMiss`, `s1Lane`, `s1Pc` follow `s1Way`: narrow, `/D`,
verdict-gated. The wide `s1PredEntries` bank and the raw data beat follow the
BRAM: shallow enable, no verdict.

**Why this is bit-identical at every observed cycle.** `s1PredEntries` is read
exactly once, on the cycle after capture, and only when `s1Valid` (`:432-433`).
On any cycle where `cmdPort.valid && !setBlocked` holds but `cmdPort.fire` does
not, `s1Valid` is False, `rspValidReg` is False, and the bank's contents are
never observed. On any cycle where `cmdPort.fire` holds, `cmdPort.valid` holds
too, so the capture occurs identically. The obligation this creates is
enumerated as H2 in §8.1: **every reader of `s1PredEntries` must be proven to be
`s1Valid`-qualified**, and the in-RTL oracle in §11.1 makes that a live check
rather than an audit.

**Captured state and pin kind.** After P1 the deep verdict cone terminates on
~10 narrow `/D` flops (`s1Valid`, `s1Way[1:0]`, `s1Fault`, `s1Atc`, `s1FromMiss`,
`s1Lane`) instead of a 931-load `/CE` broadcast plus those flops. The final net's
fanout drops from ~931 to ~10, which is a real delay reduction on the surviving
path — §18 step 2 attributes 0.540 ns of the worst path to that terminal net
alone — and not merely a relabelling. ✔ Rule satisfied.

**Cycle cost: zero.** No stage is added; no handshake changes.

**Scope limit, stated so it is not over-claimed.** P1 covers only the CE
consumers of cone A whose *data* is verdict-independent. It does **not** cover
`pfSeqValid`/`pfDemandLine`/`pfNextPa`/`pfLimitPa`: `seedPfWindow()`
(`IcachePlugin.scala:546-564`) takes `lookupPaddr` — the live ITLB PPN — as its
**data** as well as having `cmdPort.fire` as its enable, so shortening the enable
alone leaves the cone. Those, plus the `miss*` capture and the allocator arm, are
P3's to fix (P3 makes `lookupPaddr` a registered `f2Ppn ## f2Pc(11:0)`). P1 and
P3 are therefore complementary on the same arc, not alternatives: **P1 removes
the 931-load broadcast; P3 removes the depth behind it.**

### 4.3 P3 — the F1/F2 translate split (a genuine new stage)

**Owns:** the `/D` share and the residual depth of `FetchAlign → Icache` —
2,427 endpoints at **19.0 mean levels**, the deepest large arc in the design.
§20 step 4b measured a register insertion at this boundary as retiring **2,618
of the 4,890** sub-threshold endpoints (53.5 %), twenty times every other
candidate on file combined.

**This is boundary B2 of
`2026-08-10-ipc-fetchalign-icache-pipeline-design.md` §4, carried forward
substantially unchanged.** That design is sound and its correctness analysis
(its §4.2 advance discipline, §4.3 boundary contents, §5's II=2 refutation, §6's
five rejected alternatives, §7's thirteen hazards) is not re-derived here. What
changes is its **priority and its ordering** (§6.1) — B2 is the `/D`-shaped half
of that design and it was never built, while B3, the `/CE`-shaped half, was
built first and regressed.

**The split point, measured.** §20 step 1's routed trail decomposes the arc:

```
FetchAlign stalled/C → applyNow (fo 143) → predictTargetReg (fo 177)
  → pfDemandLine[31] (fo 165) → Tlb _zz_hitVec_2[1]        arrival 2.063
  → CARRY8 → hitVec_20 → ItlbPlugin tlb_io_hit (fo 67)     arrival 3.145  <-- CUT HERE
  → Icache lookupPaddr[3] = ppn ## pc[11:0]                arrival 3.477
  → s1Way → hitVec_10 → arHoldId → missPA
  → pfInstallIdx[0] (fo 518, 0.472 ns route)
  → IcachePlugin lineReg[418]/D                            arrival 5.481
```

Cutting at `tlb_io_hit` splits 5.481 ns into **3.145 + 2.336**. Both halves clear
the 4.000 ns constraint with margin and both clear a 5.000 ns period with a large
margin.

**Captured state (the F1 → F2 boundary), ~89 flops, all `/D`:**

| field | width | why |
|---|---:|---|
| `f2Valid` | 1 | stage occupancy |
| `f2Pc` | 32 | the accepted window PC; `lookupPaddr = f2Ppn ## f2Pc(11:0)` at F2 |
| `f2Drop` | 2 | the window's leading-word drop, paired at accept (§14's `cmdDrop` discipline) |
| `f2Ppn` | 20 | the **captured** translation result |
| `f2Cmode` | 2 | cache mode from the same translation |
| `f2Fault` | 1 | translation fault verdict, captured with its own translation |
| `f2RingSlot` | 2 | the FetchAlign ring slot this command belongs to |
| `f2LaneIdx` | ~5 | today's `lookupLaneIdx` |
| `f2SeqNo` | sim-only | the ordering oracle (§11.1) |

Every one of these is a plain data register written under `f1Advance` — a
**shallow, local** enable (`f1Valid && f2Ready`, 1–2 levels) — with the payload
on `/D`. ✔ Rule satisfied. This is precisely the shape B3 was not.

**Cycle cost: +1 cycle of fetch latency, II = 1 preserved.** The B2 design's §5
establishes this: today `xlate.rsp.{ready,ppn}` gate `cmdPort.ready`, so a
registered translation with today's structure would force fetch II = 2 and halve
frontend bandwidth. The split deletes that premise — acceptance moves to F1 and
the verdict becomes a pipeline stage rather than a handshake term.
`token.ringSlot` is already 2 bits and `log2Up(3) == log2Up(4)`, so the
FTB/gshare token pipeline needs no change.

**Measured redirect-latency cost, per path** (the "+1 cycle" is not uniform, and
the paths that matter most are the cheapest):

| redirect path | today | after P3 |
|---|---:|---:|
| commit-time mispredict (`RobPlugin` → `doFlushReg` → new PC on the ITLB inputs) | 2 cycles | 3 |
| fetch-directed FTB apply (`applyNow` selects `directTargetPc` in the same cycle the prediction returns) | **0-bubble** | **0-bubble** (unchanged — `applyNow` is upstream of F1) |
| decode-time BTB/RAS fallback (`predictFire`) | 1-cycle bubble | 2 |
| FTQ framing mismatch recovery | 2 cycles | 3 |
| I-cache demand-miss re-lookup after refill | on ~70–78 | +1 on ~70–78 |
| steady-state sequential fetch | — | **0** (the IBuf is the limiter, not fetch latency) |

**`RING` 3 → 4 is not free, and B2's §4.6 under-states it.** The IBuf landing
reservation is `ibuf.io.cnt +^ ((ringCount +^ 1) * 4) <= BUF_WORDS`
(`FetchAlignPlugin.scala:534-535`) with `BUF_WORDS = 20`. At `RING = 4` a full
ring requires `cnt + 20 <= 20`, i.e. `cnt == 0` — the fourth slot is reachable
only with a completely empty IBuf. That is hazard **H7** in §8.2 and it is the
single most likely source of an unanticipated IPC loss in P3.

### 4.4 P4 (conditional) — the `availEff` predecode clamp

**Owns:** `FetchAlign → FetchAlign` 126 endpoints (83 CE), specifically the
`ftqHead → p0LiveReg_lenWords` arc that §23.2 identified as the **third** family
waiting at −1.460 ns once the other two are removed.

§24.2 traced it and §24.4 already designed the fix, which is IPC-neutral and
~30 lines: feed `classify`'s `extW*Valid` from the already-existing
`availEffPrev` register (`FetchAlignPlugin:768`) instead of live `availEff`,
leaving the Aligner's live path untouched. `p0LiveInvalidate` already contains
`(availEffPrev =/= availEff)` and already forces `ambiguousLine := True`, and
`Aligner:95` already consults `p0LiveReg` only via
`Mux(preds(0).ambiguousLine, …)`. Projected slack on the arc ≈ +1.1 ns.

§24.4 measured it at **+0.009 ns** and correctly declined to build it *as a
timing cut*. It is included here as **conditional**, to be built only if P1+P2+P3
land and the recensus shows this family binding — which is exactly the state
§23.2 predicted. It is not a canary and it is not a headline.

---

## 5. The CE-vs-D constraint, applied boundary by boundary

The dispatch makes this a hard design constraint. Restated as a checklist, with
each boundary's verdict and the evidence required to confirm it post-route:

| boundary | new/relocated captured state | pin kind of that state | deep cone now terminates on | post-route confirmation required |
|---|---|---|---|---|
| **P2** skid | `mainData`, `skidData` payload banks; `mainValid`, `skidValid` | `/D` payload with 1–2 level `/CE` | **one 1-bit `/D`** (`skidValid` next-state) per stage | `RasPlugin` CE endpoint count in the sub-(−1.000) population must fall from 496 toward 0; no new `/CE` endpoint may appear whose enable cone exceeds 4 levels |
| **P1** S1 capture | `s1PredEntries` bank moves to the BRAM's own shallow enable; `s1Valid`/`s1Way`/`s1Fault`/`s1Atc`/`s1FromMiss`/`s1Lane` keep the verdict | wide bank: shallow `/CE`; verdict: narrow `/D` | ~10 narrow `/D` flops | the terminal net's fanout must fall from ~931 to ≤ 16, verified with `get_pins -leaf` (**not** `get_pins -of_objects`, per §26.8's method note) |
| **P3** F1/F2 | `f2{Valid,Pc,Drop,Ppn,Cmode,Fault,RingSlot,LaneIdx}` | **all `/D`**, enable `f1Advance` at 1–2 levels | `/D` of the F2 context bank | no path into any `f2*` register may exceed 12 logic levels; `ItlbPlugin_logic_tlb_io_hit` must not appear on any path terminating on a `/CE` |
| **P4** clamp | none (re-sources an existing read) | n/a | unchanged | `p0LiveReg_lenWords` slack ≥ −1.000 |

**A boundary that cannot satisfy its row is not built.** The check is mechanical
and is a required deliverable of each slice's post-route gate, alongside WNS —
`synth/probe_pinkind_population.tcl` re-run on the new checkpoint produces every
number in the "confirmation" column directly.

**One honest caveat on this constraint.** §23.5's CE-vs-D finding rests on
**one** controlled A/B (B3) plus a consistent-but-indirect pattern across six
tool-axis interventions. It is the best explanation available for why B3
regressed, and it is a falsifiable structural hypothesis rather than a
measurement of CE cones in general. §10.2's canary is designed to test it
directly and cheaply before the expensive boundaries are built.

---

## 6. What this supersedes, and why B3 is not rebuilt

### 6.1 The ordering rationale of the combined plan is reversed

`2026-08-10-ipc-fetchalign-icache-combined-implementation-plan.md` ordered its
slices as B3 first ("slice 1 is free and removes the largest single endpoint
object in the design, so it goes first regardless"), then B2, then Fix A. Handoff
§23.8 already recorded that this rationale needs rewriting:

> the largest endpoint object is not the same thing as the binding path, and
> removing it moved a different `FetchAlign → Icache` path below the old floor.

This design goes further and states the corrected rule: **order by pin-kind
safety and by cycle cost, not by endpoint count.** Endpoint count is a breadth
proxy that has now failed its one validation against a real route, in the
optimistic direction (§23.5). Pin kind is the property that predicted B3's
failure after the fact and is the one this design is built on.

The resulting order is §10: P2 (0 cycles, smallest diff, purest CE test) → P1
(0 cycles, largest CE population) → P3 (+1 cycle, the genuine stage) → P4
(conditional).

### 6.2 B3 is not rebuilt, and `lineReg`'s CE is left alone

B3 made `pfInstallArm` a `Reg` and the **sole enable** for the 512-bit `lineReg`
capture and the fanout-518 `pfInstallIdx` select. It is not rebuilt, for three
independent reasons:

1. **It violates §3's rule.** It moved a deep verdict behind a flop and then used
   that flop's `Q` as a wide clock enable. The resulting limiting path was still
   a `/CE`, still ~19 deep, and the netlist gained only +0.110 ns across three
   post-route rounds against baseline's +0.622 ns.
2. **It is measured, with an airtight A/B.** §23.5's control arm reproduced the
   pinned baseline exactly on all four headline metrics in the same session,
   back-to-back, under the same mutex — so tool version, machine state, flow
   drift, placement variance and contention are all common-mode and the
   −0.254 ns / −8.107 MHz is B3's. It was subsequently reverted (`6a7ae80`) and
   the revert confirmed by its own post-route gate.
3. **`lineReg` is not a pipeline register.** It accumulates a line across ~70–78
   cycles of AXI beats; its write enable is architectural state, not a verdict
   that can be relocated. §3's rule explicitly does not apply to it, and no
   boundary in §4 touches it.

**What is kept from B3's work:** the two test assets already retained at
`9957024` (the discriminating AR-arbiter demand-priority test and the bounded
sampling helpers), and the five defects §23.3 documented — which are re-listed as
required review checks in §11.4 because three of them were *plan-snippet*
defects, i.e. they will recur in any plan derived from prose.

### 6.3 The B2 design is carried forward, not replaced

§4.3 is B2. Its hazard analysis, its rejected alternatives, its spec amendments
to `2026-08-10-icache-parallel-vipt-design.md` (§1 cycle contract, §2 recovery in
a bandwidth-preserving form, §3.2 accept-behind-a-miss → *answer*-behind-a-miss),
its `IcacheFetchPipelineSpec` case list and its five mutation proofs all stand
and are incorporated by reference. Do not re-derive them; do re-verify each
snippet against the live RTL before writing it (§11.4).

---

## 7. FMax projection, and the acceptance threshold

### 7.1 What is already measured, stated before any argument

The campaign's hardest rule is *price what you name*. The static value of every
boundary in §4, from the archived probes:

| modelled cut | ΔWNS | source |
|---|---:|---|
| ITLB hit-way alone (≈ P3's cut point) | **+0.000 ns** | §20 step 2 |
| `lineReg` + `predictPending` endpoint cones | +0.000 ns | §19 step 9 |
| all 7 `PipeStage` `slotFree` enables (≈ P2's target nets) | **+0.000 ns** | §26.2 |
| `ftqHead`/`p0LiveReg` family (P4) | +0.009 ns | §24.4 |
| B2 + B3 + Fix A + arc 3, together | +0.021 ns | §24.4 |
| **the above + the entire 42,428-cell LSU cluster** | **+0.021 ns** | §25.3 |

**Every static model of every boundary in this design is between +0.000 and
+0.021 ns, against a 0.472 ns requirement.** That is stated first, at the top of
the projection section, because it is the single most important number a reader
of this document needs.

### 7.2 Why the frontend alone cannot move WNS, and what could

The §1 census makes the ceiling arithmetic explicit. The frontend arcs this
design can touch are:

```
FetchAlign→Icache 2427 + DecodeStage→Ras 496 + FetchAlign→FetchAlign 126
  + DecodeStage→FetchAlign 103 + FetchAlign→Ftb 61 + Gshare 26
  = 3,239 of 4,890 endpoints (66.2 %)
```

The remaining **1,651** are `Dcache→*` (1,370), `AluEu→Rob` (98),
`DecodeStage→DecodeStage` (68), `LsEu→LsEu` (34) and small others — all out of
scope by §2.2. **The current WNS path is among them**
(`stS2Payload_paddr[5] → sbNzvc_busy[8]`, −1.472 ns). So on a purely static
reading, a perfect frontend re-pipelining leaves WNS at −1.472 ns and delivers
**+0.000 ns**.

There is exactly one mechanism by which it could do better, and it should be
named rather than hoped for:

> **The iterated post-route loop's yield is a function of how much room the
> netlist leaves it.** §18 measured that loop at **+0.622 ns** on the baseline —
> the single largest win of the entire campaign, larger than any RTL cut. §23.5
> measured it at **+0.110 ns** on B3's netlist. The loop's yield varies by a
> factor of 5.7 with netlist structure, and 0.622 − 0.110 = 0.512 ns is *itself
> larger than the 0.472 ns deficit*. A netlist whose failing-endpoint count falls
> from 32,408 toward ~20,000, and whose sub-threshold population falls from 4,890
> toward ~1,650, plausibly gives that loop materially more room on the D-cache
> paths it currently cannot reach.

That is the thesis of this design. It is **not** a measurement, it cannot be
modelled by any static what-if, and §10.2 makes it the canary's primary test
rather than a claim in a summary.

### 7.3 The honest projection

| | value |
|---|---|
| **Floor** | **+0.000 ns / 182.749 MHz.** The static evidence. Seven grounding passes returned ≈0.00 ns; B3 returned −0.254 ns, so the floor is genuinely not guaranteed to be zero. |
| **Central estimate** | **+0.080 to +0.200 ns / 184.5–187.9 MHz.** Reasoning: P1+P2 retire ~2,400 CE endpoints at zero cycle cost, P3 halves the depth of the 2,427-endpoint 19-level arc, and the post-route loop is given a netlist with roughly a third fewer sub-threshold endpoints. |
| **Optimistic** | **+0.300 ns / 192.3 MHz**, if the loop's yield rises materially (§7.2). This requires the loop to gain more than 0.622 ns, which has never been observed. |
| **200 MHz** | **NOT PROJECTED.** It requires +0.472 ns, which requires the 1,651 out-of-scope endpoints to clear as well. A companion D-cache/LSU re-pipelining — the one §25 showed cannot be reached by *cutting* — would be needed. This document does not claim otherwise, and no task derived from it may report a 200 MHz expectation. |

### 7.4 Acceptance threshold

Per campaign convention the metric is `delivered = IPC_aggregate × FMax`, not
either alone. Baselines (handoff §4, §18, §23.4):

| | value |
|---|---:|
| FMax | 182.749 MHz |
| 11-kernel aggregate IPC, ideal memory (**the gating model**) | 0.6739 |
| 11-kernel aggregate IPC, `l2:5:70` (non-gating) | 0.5465 |
| delivered (ideal) | 123.15 M-instr/s |

Break-even, from the B2 design's §10.1 table:

| IPC cost | required FMax | required ΔWNS |
|---:|---:|---:|
| 0.0 % | 182.75 MHz | +0.000 ns |
| 0.5 % | 183.67 MHz | +0.027 ns |
| **1.0 %** | **184.60 MHz** | **+0.055 ns** |
| 1.5 % | 185.53 MHz | +0.081 ns |
| 2.0 % | 186.48 MHz | +0.108 ns |

**ACCEPT the program if all of:**
- combined post-route **ΔWNS ≥ +0.120 ns (≥ 186.0 MHz)**;
- ideal-model aggregate IPC loss **≤ 1.0 %**;
- CLB LUTs within **+3 %**, CLB registers within **+6 %** (§12 budgets +2.5 k FF);
- every §5 pin-kind confirmation passes;
- lock-step, `test-fast` and the explicit `VerilatorTest` suites' fail-**name-lists**
  byte-identical to baseline (never counts — §25.6's silent-skip trap).

The +0.120 ns bar is **roughly twice break-even at the 1 % IPC row**, chosen
deliberately: the flow is deterministic for a fixed netlist on this branch
(reproduced six times to three decimals), so the uncertainty is netlist-to-netlist
placement equilibrium, not run-to-run noise — and the LS-EU corridor measured a
*logically redundant one-line change* at **−6.55 MHz** from placement equilibrium
alone. A result inside 1× break-even is not distinguishable from that effect.

**REJECT and revert** on any of: ΔWNS < +0.000 ns; ideal-IPC loss > 2.0 %; any
new correctness failure; any §5 pin-kind confirmation failing; or the §10.2
iteration-curve stop condition firing on any slice.

**MARGINAL (+0.000 to +0.120 ns):** the zero-cycle boundaries (P1, P2, P4) are
**kept** — they cost nothing on the IPC axis, so any non-negative ΔWNS is a
strict improvement in delivered performance. P3 alone is reverted or left
disabled behind its elaboration flag, and the program is re-scoped to a
companion D-cache design with the recensus this one produces.

---

## 8. IPC cost, hazards, and the cross-file dependency sweep

### 8.1 Cycle cost, per boundary

| boundary | resident-hit throughput | latency | where the cycle is spent |
|---|---|---|---|
| P2 skid | unchanged (full throughput by construction) | unchanged | none — the skid absorbs the cycle the combinational `ready` used to prevent |
| P1 S1 capture | unchanged | unchanged | none — same data, same cycle, different enable |
| **P3 F1/F2** | **II = 1 preserved** | **+1 cycle** | per frontend drain event only (§8.4) |
| P4 clamp | unchanged | unchanged | none |

**Two structural facts make added frontend latency cheap here, and they are why
this arc is the right one to spend a cycle on.** First, the design already has
two elasticity buffers between the added stage and the machine that consumes its
output — the 20-word IBuf and the 16-deep `MicroOpQueue` — so steady-state
throughput is bounded by those, not by fetch latency.
`DecodeStage.scala:11-31` states the consequence outright: *"the decode is
LATENCY-AGNOSTIC (whitebox lock-step joins by `robId`, the `MicroOpQueue` already
buffers), so the extra frontend cycle changes no architectural result."* Second,
the fetch-directed FTB path — the one that carries the *predicted-taken* traffic
that would otherwise be the dominant redirect source — is **zero-bubble today and
stays zero-bubble** (§4.3's table), because `applyNow` sits upstream of F1.
**H7 is the qualifier on both**: elasticity only helps if the ring can actually
run ahead.

**Only P3 costs IPC.** The B2 design's §10.2 analytic bound applies unchanged: if
`p` is the fraction of retired instructions causing a frontend drain, the cost is
`p` cycles per instruction; at 1.484 cycles/instruction a 1 % IPC loss is
`p ≈ 0.0148`. Redirect-heavy kernels (`branchy`, `hot-loop`, `call-return`) exceed
that; straight-line kernels are at ~0. The aggregate is a suite-composition
average and **must be measured, not modelled**. The ideal-memory model is the
pessimistic bound (the `l2:5:70` model partially hides the extra cycle behind
memory latency) and is the one that gates.

### 8.2 Hazards specific to this design

Beyond the thirteen inherited with B2 (its §7), the new boundaries create:

**H1 — P2 changes the frontend acceptance cadence, and `feed.fire` is a
bookkeeping trigger.** `predictDetect`, `rasPushValid`, `rasPopValid`,
`gsShiftValid`, `ftqConfirmFire` and `ftqMismatchDetect` are all gated on
`feed.fire`. A skid makes `feed.fire` occur in cycles where it previously would
not have (the frontend runs one group further ahead). §17 step 5 named this
exactly and called it *"a genuine cycle-behaviour change to the frontend, not a
retiming"*. Every one of these six triggers needs a re-proof that it remains
1:1 with the architectural event it is counting, plus its own directed test.
**This is the single largest correctness surface in the design and it is on the
canary.** That is deliberate — a canary whose risk is hidden is not a canary.

**H2 — P1's `s1PredEntries` is captured on cycles the command does not fire.**
Every reader must be `s1Valid`-qualified. Audit target list: `s1Beat`, `s1PredW`,
`windowPred`, `rspValidReg`, the `s1FromMiss` bypass, and anything in the
`REPLAY`/miss path that reads S1 state. The §11.1 oracle makes this a live check.

**H3 — P2 must flush *both* held entries.** `pipeFlush` today clears one `valid`.
`PipeStage.apply` relies on `when(flush){valid := False}` being the **last**
assignment, so it wins over the same-cycle capture (`DecodeStage.scala:101`
documents this last-wins property explicitly). The skid must preserve that
ordering property for both banks, and the `raw` stage's comment about "no FSM
holds `raw`" must be re-verified, not inherited.

**H4 — static wakeup/scoreboard triggers that encode latency as a constant.**
The LS-EU corridor's H11 is the transferable lesson: `IssueQueuePlugin` records
that a *static* `sbX` trigger assumes a fixed producer-to-select offset, and
lock-step verification is latency-agnostic **by design** so it will not catch a
violation. P3 adds a cycle to the fetch path, which is upstream of rename, so it
should be latency-neutral for the IQ — **but that must be established by
enumeration, not assumed.** Required pre-P3 sweep: every static trigger in
`IssueQueuePlugin`, every `RegNext`-depth assumption in `RenameStage`, and the
FTQ run-ahead bound `RING + BUF_WORDS` (re-measure `FtqCapacitySpec`'s peak).

**H5 — the ITLB's undocumented II = 2.** The sibling corridor found that
`hrMatch = hrValid && (hrVpn === _req.vpn)` with `hrValid`/`hrVpn` set from the
*previous* cycle's request means **a changing VPN never matches**; the VPN must
be held stable for two consecutive cycles. P3 changes when `xlate.req` is
asserted and for how long. This must be checked against `ItlbPlugin.scala`
directly before P3 is written. It is exactly the class of hidden contract that
blocked the LS-EU split.

**H6 — `FetchRsp` is untagged.** Single-occupancy of the response path is a
*contract*, not an effort budget. P3 adds a stage but must not allow two
commands to be simultaneously eligible to respond. B2's §4.2 advance discipline
handles this; the §11.1 ordering oracle proves it structurally, forever.

**H7 — `RING = 4` versus the IBuf landing reservation.** As §4.3 shows, the
fourth ring slot is only reachable with `ibuf.io.cnt == 0` at `BUF_WORDS = 20`.
If that starves the fourth slot in practice, P3 pays its +1 latency without
recovering the run-ahead that hides it. Two remedies exist and **neither may be
assumed**: raise `BUF_WORDS` 20 → 24 (which reopens the IBuf head-rotate mux, a
previously *measured* FMax limiter — this is B2's conditional slice 6), or accept
a 3-deep ring with the new stage and re-derive the response-latency bound.
`FetchAlignRingTurnoverSpec` and the per-kernel `p` measurement (§8.4) are the
instruments; this must be resolved **before** P3's RTL, not after its gate.

**H8 — the ITLB request must stay asserted for the whole F1 dwell.**
`xlate.req.valid := lookupActive && cmdPort.valid` (`IcachePlugin.scala:133`) is
driven from the *live* command port. Once F1 holds a command across a stall, the
request must remain asserted and its VPN stable, or the walk restarts / the hit
response is lost. This is the I-side instance of the exact failure class that
blocked the LS-EU split (§8.3), and H5 (the ITLB's two-cycle VPN-stability
requirement) compounds it. Both are P3 prerequisites.

**H9 — dead flops already at the boundary, and one that is not.** `rspPcReg`
(`IcachePlugin.scala:340`, 32 flops) is written at `:434` and read only by
`rspPort.payload.pc` at `:442`; `FetchAlignPlugin` never reads
`ic.rsp.payload.pc`. `p0LiveReg.size` is documented as unread by construction and
survives elaboration anyway. Neither is load-bearing and both may be reclaimed
incidentally. **`fed`'s re-registered `packets` (~500 flops,
`DecodeStage.scala:139-145`) is the opposite** — it looks redundant against
`raw.packets` but is deliberately load-bearing (packet/spec same-cycle alignment
for `assemble` and Lever U1's `ucPendSpecReg` identity proof). **Do not treat it
as free in P2's skid sizing.**

### 8.3 The cross-file dependency sweep — a required, blocking task

The LS-EU split's blocker was a **liveness** dependency disguised as a value
dependency: the LS EU's registered DTLB request was consumed by `DcachePlugin`
— *a different file* — at a cycle the LS EU did not control, and the safety
slice's own conservative hold was what masked it. The spec had enumerated
`LsEuPlugin.scala`'s own live reads and listed `DcachePlugin.scala` as a
"read-only contract", i.e. it read the *interface* and not the *internal
consumers of shared state*. The ledger's own conclusion:

> the cross-file dependency pattern has now bitten this corridor TWICE, both
> times because a spec enumerated one file. Any future slice here should start by
> enumerating what reads the shared resources **from outside** it.

**Therefore, as a blocking prerequisite to P1 and P3:** enumerate every consumer,
in every file, of `xlate.req` / `xlate.rsp` on the I-side, of `cmdPort.*`, of
`s1*`, and of the FetchAlign→I-cache ring/FTQ state — and for each, classify it
as a *value* dependency (satisfied by capturing at the boundary) or a *liveness*
dependency (satisfied only by keeping the producer asserted until an
unbounded-latency consumer accepts). Liveness dependencies must be listed with
file:line and an explicit hold mechanism. A slice may not proceed on the
assumption that a captured value is sufficient.

### 8.4 A benchmark-fitness check, before P3

The LS-EU spec's strongest single move was discovering, before designing, that
**no kernel in the suite exercised the mechanism it was about to change** — the
`load/store` and `mixed` kernels were both same-address store-then-load-back and
spent *exactly zero cycles* in the states being optimised. It landed a purpose-built
kernel with its fitness measured on three axes (seed stability, memory-model
sensitivity, attributability to the mechanism) before the design.

P3's cost is `p`, the frontend-drain rate. **Before P3 is designed in detail,
measure `p` per kernel** on the existing 11-kernel suite. If no kernel drives
`p` above ~0.02, the suite cannot falsify P3's IPC cost and a redirect-density
kernel must be landed first. This is a prerequisite, not a nice-to-have — the
acceptance threshold in §7.4 is a 1.0 % IPC bar and an instrument that cannot
resolve 1.0 % makes the bar meaningless.

---

## 9. The unavoidable risk of this whole strategic direction

Stated plainly, because it is real and it is not something the verification plan
can remove.

### 9.1 The payoff cannot be validated without building it

Every fast tool this campaign has (`probe_slack_ladder.tcl`,
`probe_dcache_iq*.tcl`, `probe_specsize*.tcl`, `probe_endpoint_cones.tcl`,
`probe_combined_arcs.tcl`, `probe_stS2_paddr_hub.tcl`, this document's own
`probe_pinkind_population.tcl`) operates on a **fixed existing placement**. A
`set_false_path` deletes *timing arcs*; it does not delete *cells*, does not free
*routing resources*, does not change *where anything is placed*, and above all
does not model *depth halving* — it models path removal, after which the next
path is promoted at its own unchanged delay.

Genuine pipelining does something a false path cannot represent: it takes a
19-level, 5.48 ns path and makes it two ~10-level, ~2.9 ns paths, **for every
path in the region simultaneously**. Nothing in the toolchain available here
predicts that.

The campaign has measured the resulting error in both directions:

- `28ec738` predicted **+0.124 ns** at synthesis and delivered **+0.947 ns** at
  route — **7.6× optimistic error in the design's favour**;
- `6b246de` predicted +0.489 and delivered +0.493 — accurate;
- **B3 predicted −7.5 % TNS and 4,890 → 3,870 endpoints, and the real route did
  the opposite on both, delivering −0.254 ns.** §23.5: *"the first time it has
  been wrong in the optimistic direction, predicting a gain and delivering a
  loss."*

**So: the static evidence in §7.1 (+0.000 to +0.021 ns) is not a refutation of
this design, and it is not a licence for it either. It is silent.** The only
instrument that answers the question is a real, iterated post-route gate on real
RTL, at ~35 minutes per uncontended run. That is the unavoidable cost of this
strategic direction and it should be accepted explicitly rather than discovered
mid-programme.

### 9.2 The other named risks

- **The population/TNS proxy has failed its only validation.** §23.5 and §25.5
  both say so. This design therefore does **not** prioritise by endpoint count
  (§6.1) and does not report population movement as a result — only as a §5
  confirmation that the intended structural change happened.
- **The CE-vs-D hypothesis rests on one A/B.** §5's caveat. The canary tests it.
- **Placement equilibrium can swing a logically-neutral change by several MHz.**
  The LS-EU corridor measured **−6.55 MHz, +4,785 LUTs, 4,512 → 9,136 failing
  endpoints** from a *provably logically redundant* one-line change with zero
  added flops, where pure logic analysis predicted the opposite sign. Any single
  slice's result must be read with that in mind; §7.4's +0.120 ns bar is set
  above it for exactly this reason.
- **"Transcribe the plan's RTL verbatim" is not a safe execution model for this
  codebase.** §23.3 found five real defects in ~90 lines the plan said to write
  verbatim — three of them silent-corruption or hang class, and two of them
  *reachable deadlocks*. §11.4 makes independent re-derivation mandatory.
- **The frontend does not own the current WNS path** (§0.1, §7.2). Even complete
  success here leaves 1,651 out-of-scope endpoints below −1.000 ns.

---

## 10. Slices, and the early-cheap-canary ordering

### 10.1 Slice map

| # | slice | scope | cycle cost | diff size | gate |
|---|---|---|---|---|---|
| **0** | **Ground** | Re-measure every §4 target on the **untouched** baseline (§0.1's rule). Measure per-kernel frontend-drain rate `p` (§8.4). Run the §8.3 cross-file enumeration for P1/P3. Pin the baseline iteration curve. | none | docs + `synth/*.tcl` | baseline reproduced exactly; `p` reported per kernel; liveness list filed with file:line |
| **1** | **P2 — CANARY.** 2-entry skid replacing `PipeStage`, all 4 instances | `frontend/PipeStage.scala`, plus the H1 re-proofs in `FetchAlignPlugin.scala`/`DecodeStage.scala` | **0** | ~80 lines RTL + ~400 test | **the full §10.2 canary protocol** |
| **2** | **P1** — shallow-CE S1 capture | `cache/IcachePlugin.scala` | **0** | ~40 lines RTL + ~300 test | §10.2 protocol; §5 row confirmation; I-cache Verilator suite |
| **3** | **P3** — F1/F2 split, behind elaboration flag `icacheVerdictStage` | `IcachePlugin.scala`, `FetchAlignPlugin.scala`, new `IcacheFetchPipelineSpec` | **+1 fetch latency** | ~400 lines RTL + ~700 test | §10.2 protocol; §7.4 IPC gate on 3 seeds × 2 models; B2's five mutation proofs |
| **4** | **Decide** | Apply §7.4 verbatim. Land, or disable the flag, and record the negative either way. | — | docs | an explicit written verdict |
| **5** | *conditional* **P4** — `availEffPrev` clamp | `FetchAlignPlugin.scala`, `PredecodeWord.scala` | 0 | ~30 lines | only if slice 4 says continue and the recensus shows the family binding |
| **6** | **Recensus + handoff** | Re-run `probe_pinkind_population.tcl`; report the surviving population, its pin-kind split and its arcs, so a companion D-cache design starts from measurement. | — | docs | recensus filed |

**P3 is behind an elaboration flag** (`icacheVerdictStage: Boolean`, following
the `enableFetchDirected` / `earlyFree` precedent), for the reason the LS-EU
corridor established: it separates the **placement/area cost of the restructure**
from the **IPC effect of the behaviour change**, and makes the flag-off gate
falsifiable and binary (*"IPC must be bit-identical"* — that corridor delivered
54/54 rows bit-identical on exactly this construction). Two non-obvious
requirements come with it: it must be an **elaboration constant** so the false
arm is not elaborated at all (no dead mux enters the netlist), and the flag must
be proven inert by a **bit-exact comment-stripped generated-Verilog MD5** before
anything hangs off it.

### 10.2 The canary protocol — the iteration curve is the verdict

This is the part of the plan that exists specifically to catch a B3-style
optimisation-resistance failure early and cheaply.

**Why P2 is the canary:**

1. **Smallest diff.** One generic 20-line file plus its re-proof obligations.
2. **Provably zero cycle cost.** Full throughput by construction, no stage added.
   A post-route regression is therefore unambiguously attributable to *structure*,
   with no behavioural confound — the cleanest possible test of the §3 hypothesis.
3. **Purest CE signal.** Its dominant endpoint family, `RasPlugin`, is **100 % CE
   over 496 endpoints** with the design's highest route-per-level ratio. If
   converting a deep-CE cone to a shallow one does not help *there*, it will not
   help anywhere.
4. **Its risk is on the table, not hidden** (H1). If the acceptance-cadence
   re-proof turns out to be intractable, that is worth discovering at ~80 lines.

**What is run.** The full landed recipe — `FLOORPLAN_MODE=decode`,
`IMPL_STRATEGY=postrouteN`, **`POSTROUTE_ROUNDS=5`** (not 3; the baseline
plateaus at round 5 and the plateau is the signal), fresh synthesis
(`REUSE_SYNTH_DCP` unset), machine verifiably uncontended at both ends with
archived evidence, and a **same-session control arm** in an isolated
`git worktree` at the pinned baseline SHA, back-to-back under the same mutex —
exactly the construction §23.5 used to make its A/B airtight.

**What is compared: the per-round trajectory, not the final number.**

Reference curves, all measured:

```
BASELINE (6x reproduced)  -2.094 -> -1.623 -> -1.552 -> -1.472 -> -1.464 -> -1.463
                          total +0.622 ns over rounds 0-3, still gaining at round 3
B3 (regressed)            -1.836 -> -1.740 -> -1.726 -> -1.726
                          total +0.110 ns; round 0 BETTER by 0.258, final WORSE by 0.254
-3 speed grade            -1.994 -> -1.639 -> -1.620 -> -1.620   total +0.374, dead at round 2
postrouteNt (placer)      -2.087 -> -1.841 -> -1.839 -> -1.839   total +0.246, dead at round 2
postrouteNx (phys-opt)    -2.094 -> -1.771 -> -1.723 -> -1.723   total +0.371, dead at round 2
AlternateRoutability      -2.148 -> -2.000 -> -1.988 -> -1.984   total +0.164, dead at round 1
PerformanceOptimized      -3.043 -> -2.827 -> -2.827 -> -2.827   total +0.216, dead at round 1
```

Seven independent interventions, one signature: **easier start, dead
convergence, worse finish.** The decision rule:

| verdict | condition | action |
|---|---|---|
| **HEALTHY** | total round-0→plateau gain **≥ 0.55 ns** *and* still gaining at round 3 | continue to the next slice |
| **AMBIGUOUS** | gain **0.37–0.55 ns** | re-run **once** with a fresh control arm. If still ambiguous, treat as RESISTANT. |
| **RESISTANT — HARD STOP** | gain **≤ 0.37 ns**, *regardless of round-0 WNS and regardless of final WNS* | **stop the programme and reassess.** Do not proceed to P1 or P3. |

The 0.37 ns boundary is the best result any of the seven failures achieved
(`-3-e`, +0.374); 0.55 ns is baseline's +0.622 with a small allowance. **A
round-0 WNS better than baseline's −2.094 is a warning sign, not a success** —
all seven failures had one.

**The thesis test, reported alongside.** §7.2's mechanism is that a structurally
better netlist gives the loop *more* room. So report `Δ(iteration gain)` vs
baseline's +0.622 ns explicitly:

- gain **> 0.622 ns** → the §7.2 thesis is alive; the programme is worth
  continuing even at ΔWNS ≈ 0;
- gain **0.55–0.622 ns** → neutral; continue with the §7.3 central estimate;
- gain **< 0.55 ns** → the thesis is weakened; P3's +1 cycle is not justified and
  slice 4 should be brought forward.

**Also required on every canary and slice gate**, as a mechanical checklist:
WNS and FMax; **every post-route round's WNS**; post-**synthesis** WNS (§23.5
point 1: a synthesis-level regression is attributable to the RTL alone, before
any placement decision — B3's was −0.081 ns and it was the earliest available
warning); TNS; failing endpoints; the sub-(−1.000 ns) population **and its
pin-kind split** from `probe_pinkind_population.tcl`; the §5 confirmation row for
the slice's boundary; LUT/FF/BRAM/DSP; WHS/THS/WPWS; congestion; and the control
arm's four headline metrics.

---

## 11. Verification plan

### 11.1 Required in-RTL oracles (simulation-only, inside `` `ifndef SYNTHESIS ``)

The house standard on this branch is a **differential in-RTL oracle** plus a
**mutation proof that the oracle is a live net** (§14's framing retime is the
model). Four are required:

1. **P2 acceptance-cadence oracle.** A simulation-only counter per bookkeeping
   trigger (`predictDetect`, `rasPushValid`, `rasPopValid`, `gsShiftValid`,
   `ftqConfirmFire`, `ftqMismatchDetect`) and a matching counter of the
   architectural event each is supposed to track, asserted equal at every
   quiescent point. This is what turns H1 from an audit into a live check
   running in every frontend simulation, forever.
2. **P1 qualification oracle.** Assert that on every cycle `rspValidReg` is
   asserted, the `s1PredEntries`/`s1Beat` contents equal what a live
   `when(cmdPort.fire)`-gated shadow copy would hold. Direct analogue of §14's
   `framedOk` oracle.
3. **P3 translation-capture oracle** (inherited from B2 §12.1): whenever F2 is
   valid and the ITLB is quiescent for `f2Pc`'s VPN, assert
   `{f2Ppn, f2Cmode, f2Fault}` equals a live re-translation of `f2Pc`.
4. **P3 ordering oracle** (inherited): a monotonically increasing acceptance
   sequence number carried F1 → F2 → S1 → rsp, with every emitted `FetchRsp`
   asserting its number is exactly the previous + 1. This proves the untagged
   in-order single-occupancy contract (H6) structurally, for every existing test.

Plus B2's **II oracle**: in a resident-hit stream with no stall source asserted,
assert `f1Advance` every cycle — a live tripwire against a regression to II = 2.

### 11.2 Required mutation proofs

Each must be shown to trip, with the observed failure text recorded, then
reverted:

| # | slice | mutation | must trip |
|---|---|---|---|
| M1 | P2 | skid `in.ready` made `!skidValid && out.ready` (re-introducing the chain) | a new directed test that the frontend accepts one group ahead of a stalled decode |
| M2 | P2 | `pipeFlush` clears only `mainValid` | a redirect-with-both-slots-occupied test (wrong-path group survives) |
| M3 | P2 | one bookkeeping trigger left un-requalified | oracle 1 |
| M4 | P1 | a reader of `s1PredEntries` de-qualified from `s1Valid` | oracle 2 |
| M5 | P1 | `s1CaptureEn` left as `cmdPort.fire` | the §5 pin-kind confirmation must show the 931-fanout net back — i.e. the *structural* check is itself mutation-proved |
| M6–M10 | P3 | B2 §12.2's M1–M5 verbatim (live `xlate.rsp.ppn` at F2; F1 advancing without `xlate.rsp.ready`; F2 dispatching during `REFILL`; `RING` left at 3; …) | as specified there |

### 11.3 Test suites

**New:** `PipeStageSkidSpec` (throughput-equivalence against the current
`PipeStage` on a randomised backpressure stream, plus the flush cases);
`IcacheS1CaptureSpec`; `IcacheFetchPipelineSpec` (B2 §12.3's nine cases verbatim).

**Must pass unchanged:** `IcacheSpec`, `IcacheParallelViptSpec`,
`IcachePrefetchSpec`, `IcacheInvalidateSpec`, `PerBeatPredecodeEquivSpec`,
`IdentityTranslationSpec`, `FetchAlignSpec`, `FetchAlignResidentCadenceSpec`,
`FetchAlignRingTurnoverSpec`, `FetchDirectedFtbSpec`, `FtbSpec`, `GshareSpec`,
`RasPluginSpec`, `FtqCapacitySpec` (peak **re-measured**, not assumed),
`MicroOpQueueSpec`, `DecodeCrackPipeSpec`, `DecodeContractsSpec`, `AlignerSpec`,
the MMU suite, `ExecuteLockStepSpec` × 2 seeds, `EndToEndLockStepSpec`.

**Two standing traps, both mandatory on every gate report:**

1. **`make test-fast` does not test this RTL.** `build.sbt:21-22` excludes
   `m68k040.VerilatorTest`, and **120 of the 157 spec files carry it** — the
   entire I-cache, FetchAlign, LS and lock-step surface runs only when invoked
   explicitly. Every slice must report an explicit `testOnly` Verilator run
   alongside `test-fast`.
2. **`sbt testOnly` with a non-existent fully-qualified name runs nothing,
   reports no error, and prints `All tests passed`.** The only signal is
   requested-vs-`Suites: completed`. Every gate must report both numbers.
   Compare **fail-name-lists**, never counts.

Also: `ExecuteLockStepSpec` and `IpcBenchSpec` both compile a DUT named
`FullCoreDut` into `<worktree>/simWorkspace/FullCoreDut`, and SpinalHDL
de-duplicates workspaces only *within* a JVM — **running them concurrently in the
same worktree corrupts that directory. Serialise them.**

### 11.4 Execution discipline

- **Independent re-derivation is mandatory.** §23.3's five defects were all in
  RTL a plan said to write verbatim; three were silent-corruption or hang class
  and two were *reachable deadlocks*. Every task must verify from the live RTL
  the behaviour its snippet claims, and every per-task review must re-derive the
  mechanism from the RTL rather than accept the report's prose. Both reviewers
  that did so in §23 found things the implementers missed.
- **`git worktree add` for any isolated before/after verification.** Standing
  operational rule; `git checkout <sha>` in a shared tree has caused a real
  collision before.
- **Anti-vacuity assertions in every concurrency test** — assert the test
  actually reached the state it claims to exercise (the LS-EU corridor's
  `sawConcurrency`/`sawWaitAWithFrontIdle` pattern). Without them a whole suite
  can pass vacuously behind a disabled elaboration flag.
- **Machine discipline.** Fresh `synth_design` spawns ~9 workers: **run fresh
  syntheses ONE at a time**; two thrash. Impl-only (`REUSE_SYNTH_DCP=1`) runs:
  three at a time. Check `free -g` and competing `impl_FullCore.tcl` count before
  and after every gate and archive the evidence.
- **A latent reporting trap, still unfixed:** `synth/impl_FullCore.tcl:249` and
  `synth/floorplan_ab_report.sh:18` hardcode `1000/(4.000 − WNS)` regardless of
  the constraint actually used. Do not change `synth/clk.xdc` without fixing
  those first.

---

## 12. Cost estimate

| item | estimate |
|---|---:|
| `frontend/PipeStage.scala` skid (P2) | ~80 lines |
| P2 re-proof edits in `FetchAlignPlugin.scala` / `DecodeStage.scala` | ~60 lines |
| `cache/IcachePlugin.scala` S1 capture split (P1) | ~40 lines |
| `cache/IcachePlugin.scala` + `FetchAlignPlugin.scala` F1/F2 (P3) | ~400 lines |
| `FetchAlignPlugin.scala` + `PredecodeWord.scala` clamp (P4, conditional) | ~30 lines |
| oracles + mutation harness + three new specs | ~1,400 lines |
| probe/tooling extensions | ~150 lines |
| **total** | **~2,150 lines across ~9 files** |
| new flops, P2 | +4 payload banks. `raw`/`fed` are the large ones (`fed` carries `packets` + `specs`; §17 sized the `raw` bank at ~540 FF). Budget **+1,800 to +2,400 FF**, i.e. **+3.6 % to +4.8 %** of 50,389 — at 11.61 % device utilisation. |
| new flops, P1 | 0 |
| new flops, P3 | ~89 (F1/F2 context); `RING` widening reuses existing 2-bit indices |
| new LUTs | expected net negative for P1/P3 (the live install-veto and framing cones shrink); P2 adds skid control (~small) |
| BRAM / DSP | unchanged |

Area growth is a review point, not a rejection (standing rule), and §20 step 6
established that a 51 %-utilised, uncongested device with 69 % net delay is not
short of area. The §7.4 budget (+3 % LUT, +6 % FF) is set accordingly.

---

## 13. Open questions

1. **Does the CE hypothesis survive its first real test?** §5's caveat and
   §10.2's canary. This is the question the whole design turns on, and it is
   answerable in one slice.
2. **Does the iterated post-route loop gain *more* than 0.622 ns on a
   structurally better netlist?** §7.2's thesis. Never measured; the canary
   measures it as a by-product. If the answer is no, the programme's ceiling is
   the static +0.021 ns and slice 4 should terminate it.
3. **Can the suite resolve a 1.0 % IPC change from a frontend-drain cost?**
   §8.4. If not, a redirect-density kernel is a prerequisite to P3, not an
   optional extra.
4. **Is there a liveness dependency on the I-side equivalent to the LS-EU
   corridor's DTLB one?** §8.3. H5 (the ITLB's undocumented II = 2) is a specific
   candidate and must be resolved from `ItlbPlugin.scala` before P3 is written.
5. **Should a companion D-cache re-pipelining design be commissioned in
   parallel?** §7.2 says the frontend alone cannot move WNS on a static reading,
   and §25 says the D-cache cannot be reached by *cutting* — which leaves
   re-pipelining as its only remaining option too. If the canary comes back
   HEALTHY, that design becomes the critical path to 200 MHz, and it is out of
   scope here by dispatch.
