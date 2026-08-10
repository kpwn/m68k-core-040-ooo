# FetchAlign → I-cache demand arc: a real pipeline stage (design)

**Date:** 2026-08-10
**Status:** Design proposal. No RTL written. Requires explicit go-ahead before implementation.
**Branch:** `codex/ipc-dcache-vipt`
**Parent architecture:** `2026-05-31-m68k-040-ooo-architecture-design.md`
**Amends (does not supersede):** `2026-08-10-icache-parallel-vipt-design.md` §1, §2, §3.2
**Companion campaign record:** `docs/superpowers/plans/2026-08-10-ipc-frontend-fmax-claude-handoff.md` §13–§21
**Structural precedent (different subsystem, same discipline):** `2026-08-09-ipc-ls-eu-full-pipeline-design.md`

---

## Contents

- [0. What this document is, and why it exists now](#0-what-this-document-is-and-why-it-exists-now)
- [1. Goals / Non-goals](#1-goals--non-goals)
- [2. The arc, read off the live RTL and the routed netlist](#2-the-arc-read-off-the-live-rtl-and-the-routed-netlist)
- [3. Every stage boundary this chain currently lacks](#3-every-stage-boundary-this-chain-currently-lacks)
- [4. The design: one new architectural stage (F1 / F2)](#4-the-design-one-new-architectural-stage-f1--f2)
- [5. The II=2 risk, and why this design does not pay it](#5-the-ii2-risk-and-why-this-design-does-not-pay-it)
- [6. Alternatives considered and rejected](#6-alternatives-considered-and-rejected)
- [7. Hazard analysis](#7-hazard-analysis)
- [8. Amendments to the binding parallel-VIPT spec](#8-amendments-to-the-binding-parallel-vipt-spec)
- [9. FMax projection — grounded, and deliberately unflattering](#9-fmax-projection--grounded-and-deliberately-unflattering)
- [10. IPC cost, and the combined acceptance metric](#10-ipc-cost-and-the-combined-acceptance-metric)
- [11. Slices and gates](#11-slices-and-gates)
- [12. Verification plan and mutation proofs](#12-verification-plan-and-mutation-proofs)
- [13. Cost estimate](#13-cost-estimate)
- [14. Open questions](#14-open-questions)

---

## 0. What this document is, and why it exists now

The FMax-recovery campaign recorded in handoff §13–§21 is **closed as a
cut-based effort**. Two real cuts landed (`28ec738` +19.082 MHz, `6b246de`
+12.282 MHz), one implementation-strategy win landed (§18, +18.95 MHz), and then
five consecutive grounding passes (§15, §16, §17, §19, §20) measured every
remaining candidate to exhaustion:

| lever | measured ceiling |
|---|---:|
| startpoint-family cutting | +0.012 ns |
| endpoint-cone cutting | +0.012 ns (reached independently) |
| precise fix points (D-cache/IQ Fix A + Fix B) | +0.000 ns |
| ITLB hit-way cone removed entirely | +0.000 ns |
| **all of the above applied simultaneously** | **+0.012 ns** |
| floorplan variants (§18) | every variant regressed |
| implementation-strategy directives (§19 step 8) | both axes regressed |
| fresh elaboration ("netlist lottery", §21) | bit-exact; void |

Against a **0.472 ns** deficit to the 200 MHz deployment floor.

§20 step 4 replaced the question. The limiter is not a path, a family, or a
cone — it is a **population**: 4,890 endpoints sit below −1.000 ns, only 98 of
them below −1.400 ns, at a modal **18–20 logic levels with 69.2 % net delay**,
in a device that is **51.01 % LUT-utilised with no congestion window above
level 5**. The design is not short of area and not congested. It is short of
**stages**.

§20 step 4b then measured which structural change moves the most of that
population. One register insertion on the ITLB hit-way retires **2,618 of the
4,890 endpoints — 53.5 %, twenty times every other candidate on file
combined** — and it sits inside the `FetchAlign → IcachePlugin` arc, which owns
**2,628 startpoints and 2,427 endpoints** of the deficit population, more than
half of both sides.

This document designs the pipelining of that arc. It is the first of the three
arcs §20 step 6 named; the other two (D-cache store pipe → IQ scoreboard,
1,370/1,032; DecodeStage → RAS/ROB, 679/830) are explicit non-goals here.

**It is not presented as a win.** §9 states an honest projection in which this
arc, alone, moves routed WNS by approximately nothing, and §10 states the real
cycle cost it charges for that. §11 makes the go/no-go gate explicit, including
the conditions under which the correct decision is to revert it.

---

## 1. Goals / Non-goals

### 1.1 Goals

1. **Halve the logic depth of the frontend demand chain** by inserting exactly
   one architectural pipeline stage at the measured midpoint of the worst
   frontend path — the ITLB output. The routed trail (§2.3) splits
   5.481 ns into 3.145 ns + 2.336 ns at that boundary; both halves clear the
   4.000 ns constraint with margin, and both clear a 5.000 ns (200 MHz) period
   with a large margin.
2. **Preserve resident-hit II = 1.** The frontend's sustained fetch bandwidth
   must not change. This is the binding constraint, and it is what makes the
   naive form of the pre-authorised fix unacceptable (§5).
3. **Retire the FetchAlign → I-cache share of the sub-(−1.000 ns) endpoint
   population** — the metric §20 step 4b established as the one that tracks the
   200 MHz deficit — and report the retirement as the primary result, with WNS
   secondary.
4. **Discharge the second half of the parallel-VIPT amendment's obligation.**
   §20 filed the required report; this document designs the recovery that report
   identified, in a form that does not cost the fetch bandwidth the naive
   recovery would.
5. **Take the free half first.** Three sub-changes on the miss/prefetch/install
   side cost zero resident-hit cycles under the standing licence (handoff §15
   step 9) and remove the single largest endpoint object in the design
   (`lineReg`, 512 cells / 1,024 of the worst 4,000 endpoints / 7.49 % of TNS).
   They ship as their own slice, ahead of anything that charges a cycle.

### 1.2 Non-goals

- **The D-cache store-S1/S2 → IssueQueue scoreboard arc** (1,370/1,032). It is
  tied with this arc at −1.472 ns to three decimals, so it must eventually be
  fixed too, but its design is a separate dispatch. Nothing here may touch
  `DcachePlugin` or `IssueQueuePlugin`.
- **The DecodeStage → RAS/ROB arc** (679/830). Separate dispatch.
- **Any change to `Tlb.scala`.** The class is shared with the DTLB; a registered
  output mode there would put the D-side load path at risk for no I-side benefit.
  The new register lives in `IcachePlugin`, where it is a stage of the fetch
  pipe, not a property of the TLB. `ItlbPlugin` is likewise untouched.
- **ITLB/DTLB depth, associativity, or a micro-ITLB.** The handoff's standing
  rule ("do not deepen either TLB") holds, and the parallel-VIPT amendment §4
  forbids adding an associative structure.
- **Response tagging / general hit-under-miss.** `FetchRsp` stays untagged and
  in acceptance order. The design in §4 preserves that contract exactly; it does
  not weaken it in order to buy the stage.
- **Floorplan, implementation strategy, re-synthesis experiments.** All closed
  (§18, §19 step 8, §21).
- **Area reduction.** §20 step 6: a 51 %-utilised uncongested device with 69 %
  net delay is not short of area.
- **Prefetch algorithm changes.** The five-ID stream prefetch's policy (rules
  P1–P4 of `2026-08-10-icache-five-id-stream-prefetch-design.md`) is unchanged;
  only *when* its state registers are written moves.

---

## 2. The arc, read off the live RTL and the routed netlist

### 2.1 The chain, in RTL order

Everything below happens in **one cycle** today. File references are to the
worktree at `codex/ipc-dcache-vipt`.

| # | step | site |
|---|---|---|
| 1 | `redirect / resume&stalled / quiesce / stalled / faultHold / ftbSuppress / targetHoldValid` → `ftbBlocked` | `FetchAlignPlugin.scala:422-424` |
| 2 | `+ resultFramedOk, resultDirection, resultExpectedValid, resultProvidersValid, resultSlotLegal` → `applyNow` | `:461-462` |
| 3 | `applyNow / predictFire / targetHoldValid` → 3:1 `cmdWindowPc` and `cmdDrop` muxes → `ic.cmd.payload.pc` | `:542-561` |
| 4 | `pc[31:12]` → `xlate.req.vpn` → ITT0/ITT1 compare, `Tlb` bank/set decode, 4-way tag compare → `hitVec` → `io.hit` / `MuxOH(hitVec, entVec)` → `_rsp.{ready, ppn, cacheMode, fault}` | `IcachePlugin.scala:131-136`; `Tlb.scala:95-115`; `ItlbPlugin.scala:104-108, 278-309` |
| 5 | `xlate.rsp.ppn` → `lookupPaddr = ppn ## pc[11:0]` → `lookupTag` → 4 × `tagMem.readAsync(lookupSet)` compare, qualified by `valids` and `lookupCacheable` → `hitVec` → `isHit` / `hitWayIdx` | `IcachePlugin.scala:184-188, 456-467` |
| 6 | `isHit / lookupFault / setBlocked / answerable` → `cmdPort.ready` → `cmdPort.fire` | `:649-661` |
| 7a | fire → FetchAlign ring write (`ringStale/ringDrop/ringKeep/ringPlanSeq/ringTail/planSeq/pendingDrop/fetchPc`), FTB + gshare lookup launch | `FetchAlignPlugin.scala:563-582, 475-486` |
| 7b | fire → `s1Valid/s1Way/s1Pc/s1Fault/s1Atc/s1Lane/s1PredEntries/s1FromMiss` | `IcachePlugin.scala:676-700` |
| 7c | fire && miss → `missPC/missPA/missSet/missTag/missCacheable/victimWay/beatCnt/arSent/missBusFault/missPoison`, `demandFillStart`, `goto(REFILL)` | `:701-715` |
| 7d | `demandFillStart` → vetoes the completed-prefetch installer → `pfInstallIdx`, `lineReg` (512 bits), `missPC/PA/Set/Tag/victimWay/commitBeat`, `goto(PF_PRED)` | `:723-735` |
| 7e | fire → `seedPfWindow()` → `pfNextPa`, `pfDemandLine`, `pfLimitPa`, `pfSeqValid` | `:546-564, 663-675` |
| 7f | `heldDemandMiss`, `pfWindowUpdate`, `demandFillStart` → prefetch allocator → `pfValid/pfArSent/pfComplete/pfBeat/pfErr/pfPoison/pfPa/pfSet/pfTag/pfWay/pfNextPa` | `:471-472, 840-863` |
| 7g | `heldDemandMiss`, `demandFillStart`, `refillActive` → registered AR hold → `arHoldValid/arHoldId/arHoldAddr` | `:878-902` |

Note two structural facts that matter later:

- `lookupTag` **is** `xlate.rsp.ppn`. `lookupPaddr(31 downto 12)` is the
  concatenation's high half, so step 5 is literally "compare the TLB's PPN
  against four LUTRAM tags". There is no arithmetic between them.
- `lookupSet` is `lookupPc(11 downto 6)` — **purely virtual**, page-invariant,
  and independent of the translation. That is the parallel-VIPT property, and
  the design in §4 preserves it unchanged.

### 2.2 What is already registered, and what is not

The campaign has already retimed everything that could be retimed for free on
this arc: the FTQ-full capacity veto (§13), the FTB framing verdict (§14), the
decode-time BTB/RAS action (`predictPending`), the FTQ-mismatch action
(`ftqMismatchPending`), the ROB flush/exception redirect, and the frontend
quiesce. `applyNow` is now reached at **0.836 ns and four levels** from its
worst startpoint (§14 step 5 recensus). There is nothing left to retime on the
FetchAlign side of the boundary without adding a stage.

### 2.3 The routed trail, and the arrival-time decomposition

From `synth/archive/6b246de_default_postrouteN3_decode/fullcore_routed.dcp`
(netlist identity `3531a01b346df92e2e14c389ef47289b`, handoff §21), path #2 of
the worst 10, −1.472 ns, tied with path #1 to three decimals:

```
FetchAlign stalled/C                                              0.107
  -> ftqMem -> cmdWindowPc -> applyNow            (fo=143)        0.733
  -> Icache pfDemandLine -> predictTargetReg      (fo=177)        1.220
  -> pfDemandLine[31]_i_41                        (fo=165)        1.647   <-- PC select done
  -> Tlb _zz_hitVec_2[1]                                          2.063   <-- ITLB in
  -> LUT6 -> CARRY8 -> hitVec_20 -> LUT3 -> LUT4                  2.856
  -> ItlbPlugin_logic_tlb_io_hit                  (fo=67)         3.145   <-- ITLB out
  -> Icache lookupPaddr[3]                        (fo=11)         3.477
  -> s1Way -> CARRY8 -> Icache hitVec_10                          3.981
  -> arHoldId -> missPA                                           4.625
  -> pfInstallIdx[0]                              (fo=518)                <-- 0.472 ns route
  -> IcachePlugin lineReg[418]/D                                  5.481
```

| segment | span | delay | what it is |
|---|---|---:|---|
| **P** | 0.000 → 1.647 | 1.647 ns | `applyNow` AND-tree + the 3:1 fetch-PC/drop mux |
| **T** | 1.647 → 3.145 | 1.498 ns | ITLB: ITT compare, bank/set decode, 4-way tag compare, `orR`, `MuxOH`, response mux (the `Tlb` internal segment alone is 2.063 → 3.145 = **1.082 ns**) |
| **V** | 3.145 → 3.981 | 0.836 ns | `lookupPaddr` → L1I tag/valid/cacheable qualification → `isHit` |
| **D** | 3.981 → 5.481 | 1.500 ns | accept → miss capture → install veto → the fanout-518 install select (0.472 ns of pure route) → `lineReg` capture enable |

Twenty logic levels. 66 % route. §20 step 1 states the register-insertion model
for the T/V boundary directly: it "splits 5.481 ns into 3.145 + 2.336", and both
halves clear 4.000 ns.

### 2.4 Population evidence

| measurement | value | source |
|---|---:|---|
| endpoints below −1.000 ns (the 200 MHz floor) | **4,890** of 171,230 | §20 step 4 |
| ... below −1.400 ns | 98 | §20 step 4 |
| sub-threshold startpoints in `FetchAlignPlugin` | **2,628** | §20 step 4 |
| sub-threshold endpoints in `IcachePlugin` | **2,427** | §20 step 4 |
| of those, worst path through the ITLB hit-way | **2,620 (53.6 %)** | §20 step 4 |
| endpoints retired by the ITLB hit-way register alone | **2,618 (53.5 %)** | §20 step 4b |
| endpoints still below −1.000 after *every* candidate on file | 2,017 | §20 step 4b |
| `lineReg` endpoint cone | 512 cells / 1,024 of the worst 4,000 / 7.49 % TNS | §19 step 9 |
| ITLB hit-way cone | 3,662 failing endpoints (11.3 %) / 9.0 % TNS | §20 steps 1–2 |
| mean sub-threshold endpoint | 1.579 ns logic + 3.546 ns net, 18–20 levels | §20 step 4 |

---

## 3. Every stage boundary this chain currently lacks

Three, and only three, are natural. Naming them all is a requirement of the
dispatch; recommending only one of them is the design decision.

| id | boundary | splits | cycle cost | verdict |
|---|---|---|---|---|
| **B1** | between **P** and **T** — register the selected fetch-PC before the ITLB | 1.647 / 3.834 | +1 fetch latency | **Deferred.** Badly unbalanced: the remaining 3.834 ns half is 96 % of a 4.000 ns period and would immediately become the limiter. Insufficient alone. Revisit only if measurement shows the P+T half is the new binding half (§11 slice 5). |
| **B2** | between **T** and **V** — register the ITLB response with the fetch context | **3.145 / 2.336** | +1 fetch latency, **II = 1 preserved** (§5) | **Recommended.** The measured midpoint. This is the pre-authorised recovery of the parallel-VIPT amendment §2, in a form that does not cost fetch bandwidth. |
| **B3** | between **D**'s architectural half and its speculative half — drive the prefetch window, the prefetch allocator, the AR arbiter and the completed-prefetch installer from a registered accepted-demand context rather than from the live verdict | removes the 1.500 ns **D** tail, incl. the fanout-518 net, from the demand cone | **zero resident-hit cycles** under the standing licence | **Recommended, and first.** Everything it moves is speculative prefetch/install state with no same-cycle architectural requirement. |

There is no fourth boundary. **V** (0.836 ns, three levels: a LUTRAM read, a
20-bit compare, a 4-way reduction) is the irreducible VIPT core and must not be
split — splitting it is exactly the "restore a translation-to-BRAM-address
dependency" the parallel-VIPT amendment forbids.

**Recommended set: B3 then B2.** B1 explicitly deferred.

---

## 4. The design: one new architectural stage (F1 / F2)

### 4.1 Stage map

The I-cache demand path becomes a four-stage in-order pipeline. Only **F1** is
new; F2 is today's accept cycle, and S1/rsp are today's response stages,
unchanged.

| stage | new? | work | context it owns |
|---|---|---|---|
| **F1** — *accept + translate* | **NEW** | accept `ic.cmd`; drive `xlate.req` from the registered PC; the ITLB answers combinationally within this cycle | `f1Valid`, `f1Pc` (32 b) |
| **F2** — *verdict + dispatch* | today's IDLE/PF_PRED accept | tag/valid/cacheable qualification against the **registered** PPN; hit / miss / fault verdict; arm the data BRAM from the **registered virtual** set; write the `s1*` context; capture the miss context; run the FSM transition | `f2Valid`, `f2Pc` (32 b), `f2Ppn` (20 b), `f2Cmode` (2 b), `f2Fault` (1 b) |
| **S1** — *way select* | unchanged | `dataBeat(s1Way)`, lane select, `windowPred`, `s1FromMiss` bypass | `s1*` (exists) |
| **rsp** | unchanged | `FetchRsp` output register | `rsp*Reg` (exists) |

New state: **≈ 89 flops.** Nothing else is added.

### 4.2 Advance discipline

House pattern (`DcachePlugin` load S0/S1/S2, `LsEuPlugin` P1–P4): a forward
register with a valid bit, a single combinational ready, producer holds and
retries.

```
f1Ready  = !f1Valid || f1Advance
f1Advance = f1Valid && xlate.rsp.ready && f2Ready
f2Ready  = !f2Valid || f2Dispatch
```

- `ic.cmd.ready := f1Ready`. FetchAlign's producer contract is unchanged: it
  holds `valid`/`payload` stable under backpressure, which it already does.
- `xlate.req.valid := f1Valid`, `xlate.req.vpn := f1Pc(31 downto 12)`. This is
  **stronger** than today's `lookupActive && cmdPort.valid`: the request now
  follows a genuinely accepted fetch, satisfying parallel-VIPT §4's "an idle
  frontend must not launch a walk for an unowned payload" by construction.
- **A cold ITLB miss holds F1**, exactly as it holds the command today: `_req`
  stays asserted with a stable VPN, `ItlbPlugin`'s existing registered
  `missReqReg` trigger (`ItlbPlugin.scala:150-171`) launches the walk, and F1
  advances on the first cycle the response resolves. No replay machinery, no
  recirculation mux, no change to the walker.
- `f2Dispatch` is permitted only while the FSM is in `IDLE` or `PF_PRED` — the
  two states in which `lookupTick` runs today. In `REFILL`/`PREDECODE`/`REPLAY`/
  `FAULT`, F2 holds.

`xlate.rsp.ready` therefore still appears in a live cone — but only as an input
to a **local stage-advance enable**, reached from F1's own register in ~1.3 ns.
It no longer reaches the tag compare, the miss capture, the install select, or
the prefetch state. That relocation is the entire point.

### 4.3 The F1 → F2 boundary contents, and why each field is there

| field | width | why it must cross |
|---|---:|---|
| `f2Valid` | 1 | stage occupancy |
| `f2Pc` | 32 | `lookupSet` = `f2Pc(11:6)`, beat `f2Pc(5)`, lane `f2Pc(4:3)`, `s1Pc`, `missPC`, `rsp.pc` |
| `f2Ppn` | 20 | the L1I tag compare operand, and `missPA(31:12)` |
| `f2Cmode` | 2 | `lookupCacheable`, `missCacheable`, the INHIBITED bypass |
| `f2Fault` | 1 | the translation/permission fault placeholder path |

`f2Paddr` is **not** a register: it is `f2Ppn ## f2Pc(11:0)`, zero logic, exactly
as `lookupPaddr` is today (`IcachePlugin.scala:185`). Keeping the concatenation
combinational at F2 is what preserves the amendment's "PPN supplies the tag"
invariant without a 32-bit register.

The per-way tags are **not** registered at F1 (option (X) in §6.4): `tagMem` is
async LUTRAM read at F2 from `f2Pc(11:6)`, which is what §2.3's measured 2.336 ns
post-cut half already includes. Registering the four raw tags at F1 would shave
roughly 0.5 ns off F2 for 80 flops; it is recorded as a tuning knob, not a
default.

### 4.4 What F2 does — a direct restatement of today's `lookupTick`

`lookupTick(canStartFill)` (`IcachePlugin.scala:642-717`) moves wholesale from
"live command + live translation" to "registered F2 context", with **no change
to any decision rule**:

```
lookupSet        := f2Pc(11 downto 6)          // unchanged: virtual, page-invariant
lookupPaddr      := f2Ppn ## f2Pc(11 downto 0) // unchanged expression, registered operand
lookupTag        := f2Ppn
hitVec(w)        := f2Cacheable && valids(w)(lookupSet) && (tagMem(w).readAsync(lookupSet) === f2Ppn)
dataReadAddr     := (lookupSet ## f2Pc(5))
dataReadEn       := f2Valid && !setBlocked
setBlocked       := !fillAllowed && (fillArrayWrActive && (lookupSet === missSet))
answerable       := f2Fault || isHit || (fillAllowed || !pfLookupSetBusy)
f2Dispatch       := f2Valid && fsmPermits && !setBlocked && answerable
```

`f2Dispatch` replaces `cmdPort.fire` at every one of its seven consumers
(§2.1, rows 7a–7g), except 7a, which stays on `ic.cmd.fire` — see §4.6.

The data BRAM is armed **at F2**, from `f2Pc(11:6)`, one cycle later than the
ITLB request. That is not a translation dependency: the address is virtual and
registered. The amendment's prohibition is on the *address/enable being a
function of the translation result*, and it remains satisfied to the letter
(§8).

### 4.5 What does **not** move

`REPLAY`, `PREDECODE`, `PF_PRED`, `FAULT`, `missPoison`, `s1FromMiss`, the
INHIBITED direct-delivery bypass, the per-beat predecode dwell, the five-ID fill
pool, the AXI R routing by RID, the `valids`-write-yields-to-invalidate priority
guard, the D3-SET-I rule, and the whole `s1 → rsp` stage are **byte-for-byte
unchanged**. This is a change to *when the verdict is computed*, not to *what the
verdict is*.

### 4.6 FetchAlign side: `RING` 3 → 4, and nothing else

Response latency after acceptance moves from 2 cycles to 3. To sustain II = 1
the outstanding-record ring must hold one more entry.

```scala
val RING = 4          // was 3
```

Everything else is already parameterised and needs no edit:

- `ringInc` (`:246-248`) folds to the plain `+1` wrap for a power-of-two `RING`
  — the design **loses** a comparator and a mux.
- `resultSlotLegal = resultSlot < RING` (`:356`) becomes tautological for a
  2-bit index; the assertion stays as documentation and dead-code-eliminates.
- `token.ringSlot` is already `UInt(2 bits)` (`Services.scala:81`) — `log2Up(3)`
  and `log2Up(4)` are both 2. **The FTB/gshare token pipeline needs no change at
  all**, and neither do `Ftb.scala:57` / `Gshare.scala:112`.
- `require(ftqDepth >= RING + BUF_WORDS + 1)` (`:260`) becomes 4 + 20 + 1 = 25,
  satisfied by the configured 32.
- The FTB/gshare fixed-C+1 response contract is untouched, because
  `planLookupFire = ic.cmd.fire && !issueBornStale` still fires at **F1**
  acceptance — the same cycle it fires today relative to FetchAlign. `applyNow`
  still lands one cycle later and still selects the next command's PC. **No
  predictor timing changes.** This is why row 7a stays on `ic.cmd.fire`.

The one real interaction is the IBuf landing reservation
`cnt + (ringCount+1)*4 <= BUF_WORDS` (`:534-535`). At `ringCount = 3` it admits a
fourth outstanding fetch only when `cnt = 0`. That is not a throughput bug: with
four words landing per window and decode consuming one instruction per cycle,
the steady state is IBuf-limited, not ring-limited (§13 measured peak IBuf 17 of
20 words against peak ring 3 of 3). The fourth slot is needed exactly when the
IBuf *is* empty — the post-redirect refill — which is precisely the case it
serves. A `BUF_WORDS` 20 → 24 bump is available as a measured variant (§11
slice 6) if measurement disagrees; it is not proposed by default, because the
IBuf shift mux was a previously measured FMax limiter.

### 4.7 Cycle-accurate cadences

**Resident hit stream, warm ITLB (the II = 1 case):**

```
cycle:      N     N+1    N+2    N+3    N+4
F1:        cmd0   cmd1   cmd2   cmd3   cmd4
F2:         -     cmd0   cmd1   cmd2   cmd3
S1:         -      -     cmd0   cmd1   cmd2
rsp:        -      -      -     cmd0   cmd1
```

One command accepted per cycle, one response per cycle, forever. **II = 1,
latency 3.** Today: II = 1, latency 2.

**Clean redirect to first useful decode group:** the parallel-VIPT amendment §1
records N+4. This design makes it **N+5**. That is the honest headline cost, and
§10 prices it.

**Cold ITLB (walk):** F1 holds; the walk overlaps whatever the cache is doing.
One extra cycle on top of a ~30-cycle walk.

**Demand miss:** F2 captures the miss and frees itself; F1's next command
advances into F2 and *holds* there for the whole refill; `REPLAY` answers the
missing command first, then F2 dispatches the held one. Order preserved.
Compared to today, exactly **one** additional accepted-but-unanswered command
sits inside the cache during a refill — covered by `RING = 4`.

---

## 5. The II=2 risk, and why this design does not pay it

Handoff §20 step 3 and the session ledger both flag the same thing, correctly:

> `xlate.rsp.ready` and `xlate.rsp.ppn` are consumed by the fetch **acceptance**
> gate, not only by a downstream capture. Registering the hit-way makes the
> translation answer one cycle stale, so a naive insertion forces the L1I to
> accept at most one command every two cycles — **fetch II = 2, halving frontend
> bandwidth.**

That is exactly right **for a single-cycle-accept cache**. `cmdPort.ready`
depends on the answer; if the answer for command N only exists at N+1, then
command N is accepted at N+1 and command N+1 cannot be accepted before N+2.

The premise of that reasoning is that acceptance and verdict must happen in the
same cycle. **This design deletes the premise.** Acceptance moves to F1 and is
conditioned only on the translation *resolving* (a hit or a fault — not on the
cache verdict); the verdict moves to F2 and is a *pipeline stage*, not a
handshake term. The pipe is elastic: F1 and F2 each hold a command, each accepts
one per cycle when its successor is free, and in the resident-hit steady state
neither ever stalls.

So the cost is **latency, not throughput**: +1 cycle of fetch latency, II
unchanged at 1. That is the shape the standing principle covers ("one extra
latency cycle is always worth it for real throughput") — with the honest
qualification that here the throughput being purchased is *FMax*, not fetch
bandwidth, so the trade has to be argued on the combined metric (§10) rather
than assumed.

**This is a genuine restatement of the risk, not a dismissal of it.** The
mandatory proof obligation is that II = 1 is *measured*, not argued:
`FetchAlignResidentCadenceSpec` (which already requires useful two-wide decode
packets on consecutive cycles through a real ITLB walk and real demand misses)
is the gate, and slice 2 does not land if it regresses.

---

## 6. Alternatives considered and rejected

### 6.1 Naive registered hit-way, single-cycle accept retained

II = 2. Halves frontend bandwidth for a core whose binding goal is "200 MHz
*with good IPC*". **Rejected**, and it is the alternative §20 step 3 was warning
about.

### 6.2 Registered hit-way + VPN-stability compare

The sketch: the ITLB key is `pc[31:12]`, constant across a 4 KiB page — 64 L1I
lines of sequential fetch — so a registered hit-way plus a live
`lookupVpnReg === _req.vpn` comparator is correct with a bubble only on a page
cross or a cross-page redirect.

**Rejected, and it is strictly dominated.** Three reasons, in order of weight:

1. It leaves a live 20-bit equality compare **inside the cone being cut**, so its
   recovery is strictly smaller than the 1.082 ns modelled — §20 step 3's own
   objection, and it stands.
2. It does not move the **V** or **D** segments at all. The single-cycle accept
   is retained, so the cycle still contains PC-select + compare + tag + hit +
   accept + install. The path shortens by less than the ITLB's own segment and
   stays ~20 levels deep. It does not reduce *average depth*, which §20 step 6
   established as the actual deficit.
3. Its bubble lands on page crosses and cross-page redirects — i.e. on exactly
   the control-flow events that already cost the most. It converts a uniform
   +1-cycle latency into a non-uniform penalty concentrated where it hurts most,
   which is worse for IPC at equal average cost.

It is worth recording that this variant was the one the grounding pass sketched,
and that a full stage split is *cheaper in IPC* than it, not more expensive.

### 6.3 Speculate-then-confirm on the hit-way (the fetch-directed BTB pattern)

Assume the previous cycle's hit-way, continue, confirm one cycle later, flush on
mismatch.

**Rejected on a correctness argument, not a cost argument.** The fetch-directed
BTB may speculate because **`BranchEuPlugin` independently verifies predicted
direction and target against the resolved ones**, so a mis-speculation is caught
by a mechanism that exists for another reason. **There is no verifier for a
cache hit-way.** A wrong way delivers wrong *instruction bytes* into the IBuf,
and the only detector would be the confirm itself. Worse, the recovery would
have to unwind an already-*accepted* fetch command, and this design explicitly
has no such mechanism: `FetchRsp` is untagged, and `IcachePlugin.scala:239-261`
records at length why an in-flight response can never be cancelled (it strands a
FetchAlign ring entry and hard-hangs the frontend). Building a cancel path to
support a speculation with no independent verifier is the wrong trade in a core
that has already paid for silent-corruption bugs of this class.

### 6.4 Register the four raw L1I tags at F1 as well

Reduces F2 from ~2.34 ns to ~1.85 ns for 80 flops. **Not the default**, because
F2 is the *shorter* half at the measured split and spending flops to shorten it
buys nothing until F2 is proven binding. Recorded as slice-5 tuning.

### 6.5 B1 instead of B2 (register the fetch-PC before the ITLB)

1.647 / 3.834 split. The long half is 96 % of a 4.000 ns period. **Insufficient
alone**; deferred to slice 5 as the *second* stage if the P+T half measures
binding.

### 6.6 A larger / deeper / micro-ITLB

Forbidden by the handoff's standing rule and by parallel-VIPT §4 ("adds no
associative structure"). Also does not address a design whose deficit is average
depth.

### 6.7 Do nothing

**A legitimate outcome, and §11's gate can select it.** 182.749 MHz with today's
IPC is a real, gated, reproducible result. If slice 2 measures a real IPC cost
and no FMax gain, the correct engineering answer is to revert it and re-scope the
program (§9.4).

---

## 7. Hazard analysis

| id | hazard | resolution |
|---|---|---|
| **H1** | **Response ordering.** `FetchRsp` is untagged; FetchAlign attributes every response to its ring head (`FetchAlignPlugin.scala:608-614`). | F1/F2 is strictly in-order and never reorders. F2 holds while it cannot dispatch; F1 holds behind it. A miss is answered by `REPLAY` before F2 dispatches the command behind it (§4.7). The `IcachePlugin.scala:595-641` ordering argument is preserved verbatim. |
| **H2** | **One extra accepted command in flight** relative to the parallel-VIPT amendment §3.2, which deliberately declined to accept a younger command behind a discovered miss. | The amendment's concern is *answering* out of order, not *holding*. This design accepts one more command into a holding stage and answers nothing early. It costs exactly one more ring slot (covered by `RING = 4`) and one more window of wrong-path fetch after a redirect — bounded, and already the existing regime at depth 3. Requires the explicit amendment in §8. |
| **H3** | **Post-fault in-flight windows.** A fault response can be followed by responses for younger windows already in the pipe. | Pre-existing and already handled: `rspFault` captures only the first fault (`:616-625`), `faultHold` stops issue, and the synthetic fault packet is emitted only once decode drains everything buffered before the fault. Depth 4 changes the count, not the class. Directed coverage required (§12). |
| **H4** | **`invalidateAll` / CINV lands while F1 or F2 holds a command.** | F2's verdict reads `valids` and `tagMem` **live**, so an invalidate before dispatch is seen. An invalidate after F2's hit verdict but before the response is the *existing* window (accept → rsp is already 2 cycles today); the existing argument — CINV/CPUSH is an architectural synchronisation point, and `ExceptionUnit` serialises it via `S_APPLY → S_REDIR` with a real fetch redirect — covers 3 cycles as well as 2. |
| **H5** | **PFLUSHA lands while F2 holds a captured PPN**, so one command uses a pre-flush translation. | Same closure, and stronger: `sysFlushAllValid` is pulsed from `S_APPLY` and followed by `S_REDIR`'s redirect (`ExceptionUnit.scala:359-362, 1471-1478`), which marks every in-flight ring entry stale and discards its response. Instruction fetch already translates far ahead of commit, so this is not a new class — the window merely grows by one cycle inside an already-serialised region. |
| **H6** | **D3-SET-I** (write-first async `tagMem` vs register `valids` on a fill-commit cycle). | Rule unchanged, evaluated at F2 over `f2Pc(11:6)` vs `missSet` — a registered-vs-registered compare, cheaper than today's. F2 holds instead of the command being un-accepted; retry semantics identical. |
| **H7** | **Prefetch-set-busy hold** (`pfLookupSetBusy`). | Same relocation: evaluated at F2 over registered `pfSet(i)` and `f2Pc(11:6)`. |
| **H8** | **A wrong-path fetch triggers an ITLB walk one cycle earlier / one command deeper.** | Already the regime today (fetch runs ahead of the ROB). ITLB fills are speculation-safe by design; the deferred U-bit write is `robId`-keyed and discarded on flush (`ItlbPlugin.scala:216-229`). No new class. |
| **H9** | **Slice 1's inverted install/demand priority starves a demand miss.** | `pfInstallAny` is true for at most the 2-cycle `PF_PRED` dwell per completed speculative line, and a completed slot is consumed unconditionally. Worst case: a demand miss waits 2 cycles behind an install — against a ~70–78-cycle line service. If the install is for the *same* line the demand wants, waiting is strictly better: it then hits. Bounded-wait assertion required (§12). |
| **H10** | **Slice 1's registered prefetch-window seed** makes `pfNextPa`/`pfDemandLine`/`pfLimitPa` one cycle stale relative to the accepted demand. | The window is a speculative hint with no architectural requirement (§14 segment D, and the standing licence). The page-crossing restart rule in `seedPfWindow` (`:552-562`) is order-sensitive, not timing-sensitive, and is preserved exactly by feeding it from the registered context. Costs a prefetch one cycle of earliness. |
| **H11** | **Deadlock: F1 holds forever on a translation that never resolves.** | Identical to today's held-command case: `_req.valid` stays asserted, `needWalk` fires, the walker is single-outstanding and always terminates in a fill or a fault latch. The only new requirement is that F1's hold does not suppress `xlate.req.valid` — it must not, by construction (`xlate.req.valid := f1Valid`). |
| **H12** | **The `assert(!ftqFull)` run-ahead bound (§13) is invalidated by `RING = 4`.** | The bound is `RING + BUF_WORDS` = 24 ≤ 32. `FtqCapacitySpec` must be re-run and its measured peak re-recorded; the elaboration `require` already encodes the structural argument. |
| **H13** | **`ibufRoomForIssue` throttles the fourth ring slot to `cnt = 0`.** | Analysed in §4.6. Measurement decides whether it matters (`FetchAlignRingTurnoverSpec` + IPC); slice 6 is the remedy if it does. |

---

## 8. Amendments to the binding parallel-VIPT spec

`2026-08-10-icache-parallel-vipt-design.md` is binding. This design requires
three explicit, narrow amendments, each of which must land as a documentation
commit **before** the corresponding RTL (the discipline §13/§14 used).

**A1 — §1, the cycle contract.** The resident L1I hit path becomes a
**three-cycle, initiation-interval-one** pipeline. Cycle N: `cmd.fire` accepts
into F1 and launches the ITLB. Cycle N+1: the registered translation qualifies
the async tags for the registered virtual set, arms the data BRAM, and captures
the S1 context. Cycle N+2: registered way/lane control selects the BRAM output.
Cycle N+3: `FetchRsp.valid`. The clean-redirect first-useful-group figure moves
from N+4 to **N+5**.

**A2 — §2, the recovery is taken, in a bandwidth-preserving form.** The
amendment's pre-authorised recovery ("pipeline the ITLB's internal hit-way
result") is exercised, with two clarifications it did not specify: the register
lives at the **fetch pipe's** F1/F2 boundary inside `IcachePlugin`, not inside
`Tlb`/`ItlbPlugin` (so the shared D-side is untouched); and acceptance is moved
into a pipeline stage so that II = 1 is preserved. Both prohibitions are
retained in full: **no translation-to-BRAM-address dependency is restored** (the
BRAM address stays `f2Pc(11:6)`, purely virtual and registered) and **the
response register is not deleted**.

**A3 — §3.2, the accept-behind-a-miss rule.** The sentence "the cache does not
accept one extra younger command behind the newly discovered miss" is amended
to: *the cache does not **answer** one extra younger command behind a newly
discovered miss.* It may hold one in F2 and one in F1. The ordering guarantee is
unchanged and is now enforced structurally by the in-order stage discipline
rather than by refusing acceptance. The wrong-path speculation bound grows by
exactly one window, which `RING = 4` covers and `FtqCapacitySpec` re-measures.

---

## 9. FMax projection — grounded, and deliberately unflattering

### 9.1 What is already measured, and it is not encouraging

This campaign's hardest-won rule (§15 step 4, §19 step 9, §20 step 4b) is:
**price what you name; a census is not a value.** So the modelled value of this
exact design is stated first, from the archived probes, before any argument:

| modelled cut | ΔWNS | ΔTNS | Δfailing endpoints | source |
|---|---:|---:|---:|---|
| ITLB hit-way alone (= B2) | **+0.000 ns** | +1,572.9 (9.0 %) | −911 | §20 step 2 |
| `lineReg` endpoint cone (≈ B3's largest object) | **+0.000 ns** | +1,310.3 (7.49 %) | −1,024 | §19 step 9 |
| install-select fanout-518 net (B3's tail) | +0.000 ns | +970.1 (5.54 %) | −767 | §19 step 9 |
| ITLB + entire frontend cone + both D-cache/IQ fixes | **+0.012 ns** | +2,663.6 (15.2 %) | −1,223 | §20 step 2 |

**B2 and B3, modelled generously, are worth 0.000 ns of WNS on the frozen
`6b246de` placement.** The mechanism is a dead tie: path #1
(`DcachePlugin stS2Payload_paddr[5] → IssueQueuePlugin sbNzvc_busy[8]`) and
path #2 (this arc) are both −1.472 ns to three decimals, from unrelated
subsystems. Deleting either exposes the other at the identical slack. Three
independent cut models have now reached the same −1.460 ns floor.

### 9.2 What *does* move, and why it is the metric that tracks 200 MHz

| metric | baseline | after B2 (measured) | after B2 + B3 (estimated) |
|---|---:|---:|---:|
| WNS | −1.472 ns | −1.472 ns | −1.472 to −1.46 ns |
| FMax | 182.749 MHz | 182.749 MHz | ~182.7–183.2 MHz |
| TNS | −17,499.5 | −15,926.6 (+9.0 %) | ~−14,900 (+15 %) |
| failing endpoints | 32,408 | 31,497 | ~30,400 |
| **endpoints below −1.000 ns** | **4,890** | **2,272 (−53.5 %)** | **~2,150** |

The last row is the one §20 step 4 established as the real deficit measure.
Reaching 200 MHz requires improving 4,890 endpoints, of which only 98 lie below
−1.400 ns. **This design retires more than half of them with one register
insertion** — twenty times what every other candidate on file achieves combined.

### 9.3 The honest projection

**Projected post-route result for slices 1+2, alone: −1.472 to −1.350 ns, i.e.
182.7 to 186.9 MHz, most likely ~183–185 MHz. 200 MHz is not reached by this
arc, and the design does not claim it will be.** (200 MHz needs −1.000 ns; the
top of this band is still 0.350 ns short.)

The reasoning, stated so it can be checked rather than believed:

- The **floor** of the band is the static model, which is 0.000 ns, plus B3's
  unmodelled share. The tie with the D-cache/IQ arc is real and placement
  freedom does not remove it: that path is −1.472 ns on its own merits.
- The **ceiling** of the band comes from this campaign's two landed cuts, both
  of which beat their static prediction because deleting a live cone gives the
  placer freedom a `set_false_path` cannot model. `28ec738` predicted +0.124 ns
  at synthesis and delivered **+0.947 ns** at route (7.6×); `6b246de` predicted
  +0.489 and delivered +0.493 (1.0×). Both, however, started from a *non-zero*
  static prediction. Extrapolating a 7.6× multiplier from zero is not a
  prediction, it is a hope, so the ceiling is set from the TNS/endpoint mass
  being removed (~15 % of TNS, ~2,600 endpoints, and 512 flops' worth of
  fanout-518 install net) rather than from a multiplier.
- **The campaign's own false leads are the calibration.** §16 measured a family
  worth 0.000 ns despite 752 census paths; §15 measured one worth 0.020 ns
  despite 2,673 startpoint paths; §19 step 9 measured the largest cone in the
  design (1,024 endpoints, 7.49 % TNS) at 0.000 ns. This document's population
  argument is a *different* metric from those, and it is the metric §20 step 4b
  introduced precisely because path-counting kept failing — but it has **never
  been validated against a real route**. It could be the next false lead. It
  must be confirmed by a real post-route gate, and until it is, no number in §9.2
  should be quoted as a result.

### 9.4 The falsifier, stated in advance

If the real post-route gate for slices 1+2 measures **ΔWNS < +0.050 ns** *and*
the IPC gate measures **> 1.0 % aggregate loss**, then:

- slice 2 is a net regression in delivered performance (§10) and must be
  **reverted** (or left disabled behind its elaboration flag);
- slice 1, being free, stays regardless;
- and the program should be re-scoped to attack the **D-cache/IQ arc first**,
  since the measured tie means neither arc can move WNS while the other stands.

That last point is the strategically important one and it is deliberately not
buried: **this arc and the D-cache/IQ arc are tied. Neither alone moves WNS.
The program only pays if at least two of the three arcs land.** That is the
strongest argument for the elaboration-flag structure in §11 — it lets the two
arcs be measured *together* without a revert cycle.

---

## 10. IPC cost, and the combined acceptance metric

### 10.1 The metric

Per the campaign convention established by the I-side MSHR work
("+12.4 % delivered performance (IPC × FMax)"), the acceptance metric is

```
delivered = IPC_aggregate × FMax
```

not IPC and not FMax in isolation. The trade here is explicit: this design buys
FMax (which multiplies *all* IPC by a constant) with fetch latency (which costs
IPC). It is accepted only if the product improves.

Baselines to beat (handoff §4, §5, §19):

| | value |
|---|---:|
| FMax (`6b246de` + `decode` + `postrouteN` × 3) | **182.749 MHz** |
| 11-kernel aggregate IPC, ideal memory | **0.6739** |
| 11-kernel aggregate IPC, `l2:5:70` | **0.5465** |
| delivered (ideal) | 123.15 M-instr/s |
| delivered (`l2:5:70`) | 99.87 M-instr/s |

**Break-even table** — the FMax slice 2 must deliver for a given IPC cost:

| IPC cost | required FMax | required ΔWNS |
|---:|---:|---:|
| 0.5 % | 183.67 MHz | +0.027 ns |
| **1.0 %** | **184.60 MHz** | **+0.055 ns** |
| 1.5 % | 185.53 MHz | +0.081 ns |
| 2.0 % | 186.48 MHz | +0.108 ns |
| 3.0 % | 188.40 MHz | +0.163 ns |

Note how small these are — a consequence of the deficit being 0.472 ns. The
projected band in §9.3 straddles the 1 % row. **This is genuinely uncertain, and
the design says so rather than picking the flattering end.**

### 10.2 Where the cycle is actually spent

The added stage costs **+1 cycle of fetch latency**, not throughput. It is paid
exactly once per frontend drain event:

| event | frequency | cost |
|---|---|---|
| commit-time mispredict redirect | branch rate × mispredict rate | +1 cycle |
| decode-time `predictFire` redirect (BTB/RAS) | per applied decode-time prediction that flushes the IBuf | +1 cycle |
| `ftqMismatch` recovery | rare | +1 cycle |
| exception / RTE / system-op `S_REDIR` | rare | +1 cycle |
| I-cache demand miss re-look-up after refill | per miss | +1 cycle on ~70–78 |
| **steady-state sequential fetch** | — | **0** — the IBuf is the limiter, not fetch latency |

Analytic bound: if `p` is the fraction of retired instructions that cause a
frontend drain, the cost is `p` cycles per instruction. At the baseline ideal
IPC of 0.674 (1.484 cycles/instruction), a 1 % IPC loss corresponds to
`p ≈ 0.0148` — roughly 1.5 drain events per 100 instructions. The redirect-heavy
kernels (`branchy`, `hot-loop`, `call-return`) will exceed that; the
straight-line kernels (`independent-ALU`, `shift-stream`, `store-stream`) will be
at or near zero. **The aggregate is a suite-composition average and must be
measured, not modelled.** Both memory models are mandatory: the `l2:5:70` model
partially *hides* the extra cycle behind memory latency, so **the ideal-memory
model is the pessimistic bound** and is the one that gates.

### 10.3 Second-order effects, both directions

- **Small gain:** F1 keeps `xlate.req` asserted while the FSM is busy, so a cold
  ITLB walk for the next window can now overlap a demand refill. Today the
  command is held with `lookupActive` false and the walk cannot start until the
  refill ends. Worth up to a full walk latency on a cold-stream page cross;
  rare, but real, and it partially offsets the miss-path +1.
- **Small gain:** `RING = 4` removes the non-power-of-two `ringInc` comparator.
- **Small loss:** one extra window of wrong-path fetch after a redirect
  (H2) — bounded, no correctness impact, marginal AXI bandwidth.
- **Unknown:** the `ibufRoomForIssue` interaction (§4.6, H13). This is the
  single most likely place for an unexpected IPC loss and the reason
  `FetchAlignRingTurnoverSpec` is a required gate.

---

## 11. Slices and gates

Every slice ends with the standing full-core gates: `make test-fast`, and — for
any slice that changes RTL — the mandatory post-route gate reported with WNS,
FMax, TNS, failing endpoints, **and the sub-(−1.000 ns) endpoint population**,
which this document adds to the standard report set.

| # | slice | scope | files | cycle cost | gate |
|---|---|---|---|---|---|
| **0** | **Ground the exact boundary set** | Extend `synth/probe_itlb_hitway.tcl` / `probe_population_after_cuts.tcl` to model *exactly* B2 + B3's fix points together (ITLB `io_hit`/`io_hitEntry`/`hitVec` nets; the `demandFillStart` → install-select nets; the prefetch-window and AR-arbiter arcs). Assert a non-zero object count on every cut (§19 step 1's rule). Reproduce the baseline exactly first. | `synth/*.tcl` only | none | baseline reproduced bit-exact; population retirement reported for the combined boundary set |
| **1** | **B3 — decouple the speculative install/prefetch state from the live verdict** (free) | 1a: invert install-vs-demand priority — a completed prefetch install wins, and a *demand miss capture* is gated on registered `pfInstallAny` instead of the install being gated on live `demandFillStart`. 1b: seed `pfNextPa`/`pfDemandLine`/`pfLimitPa`/`pfSeqValid` from a registered accepted-demand context. 1c: register `heldDemandMiss`/`pfWindowUpdate` for the allocator and AR-hold arbiter. | `IcachePlugin.scala` | ≤ 2 cycles on a demand miss that collides with a completing install; 1 cycle of prefetch earliness | `IcachePrefetchSpec`, `IcacheSpec`, `IcacheParallelViptSpec`, `IcacheInvalidateSpec`; **`FetchAlignResidentCadenceSpec` II = 1 unchanged**; new bounded-wait assertion (H9); IPC neutral within noise on 3 seeds × 2 models; post-route |
| **2** | **B2 — the F1/F2 split** | The new F1 stage; `lookupTick` relocated to F2 over the registered context; `RING` 3 → 4; §8's three spec amendments landed first. **Behind an elaboration flag** (`icacheVerdictStage: Boolean = true`), following the `enableFetchDirected` / `earlyFree` precedent, so the arc can be A/B'd against the D-cache/IQ arc without a revert. | `IcachePlugin.scala`, `FetchAlignPlugin.scala`, new `IcacheFetchPipelineSpec` | **+1 fetch latency**, II = 1 preserved | §12 in full; lock-step × 2; `FtqCapacitySpec` re-measured; `test-fast`; IPC 3 seeds × 2 models; post-route with population report |
| **3** | **Decide** | Apply §9.4's falsifier and §10.1's break-even table to slice 2's measured pair. Land, or disable the flag and record the negative. | docs | — | an explicit written verdict either way |
| **4** | **Hand off to arc 2** | Report the recensus: which families and which endpoint population remain, so the D-cache/IQ arc's design starts from a measured state rather than this document's projection. | docs, `synth/*.tcl` | — | recensus filed |
| **5** | *conditional* — **balance the halves** | Only if slice 2's post-route shows the P+T half binding: either register the four raw L1I tags at F1 (§6.4, +80 flops, F2 −0.5 ns) or add B1 (§6.5, +1 further cycle). Each is its own measured decision. | `IcachePlugin.scala` / `FetchAlignPlugin.scala` | 0 or +1 | same as slice 2 |
| **6** | *conditional* — **`BUF_WORDS` 20 → 24** | Only if `FetchAlignRingTurnoverSpec`/IPC show the fourth ring slot is reservation-starved (H13). The IBuf shift mux was a previously measured FMax limiter, so this is measured, never assumed. | `InstructionBuffer.scala` | none | IBuf specs; post-route (area + FMax) |

**Ordering rationale.** Slice 1 is free and removes the largest single endpoint
object in the design, so it goes first regardless of what happens to slice 2 —
it is worth landing on its TNS/endpoint breadth alone (a class of cut this
campaign has twice seen convert into real placement gains). Slice 2 charges a
real cycle and is therefore gated, flagged, and reversible.

---

## 12. Verification plan and mutation proofs

The house standard on this branch is: a new mechanism needs a **differential
in-RTL oracle** plus a **mutation proof that the oracle is a live net**. §14's
framing retime is the model — a live `resultFramedOk === (hit && inWindow &&
afterDrop)` assertion running in *every* fetch-directed simulation, with two
mutations that trip it.

### 12.1 Required in-RTL oracles (simulation-only, inside `` `ifndef SYNTHESIS ``)

1. **Translation-capture oracle.** Whenever F2 is valid and the ITLB is
   quiescent for `f2Pc`'s VPN (no fill, no flush, no walk completion since
   capture), assert that `{f2Ppn, f2Cmode, f2Fault}` equals a live
   re-translation of `f2Pc`. This is the direct analogue of §14's framing
   oracle and it is what makes the boundary a *proven* retime of the value
   rather than an assumed one.
2. **Ordering oracle.** A monotonically increasing acceptance sequence number is
   carried F1 → F2 → S1 → rsp (simulation-only), and every emitted `FetchRsp`
   asserts its sequence number is exactly the previous one + 1. This proves the
   untagged in-order contract structurally, for every existing test, forever.
3. **II oracle.** In a resident-hit stream with no stall source asserted, assert
   `f1Advance` every cycle — a live tripwire for a regression to II = 2.
4. **Bounded-wait oracle (slice 1).** A demand miss held by `pfInstallAny` must
   be admitted within N cycles (N = the `PF_PRED` dwell + 1).

### 12.2 Required mutation proofs

| # | mutation | must trip |
|---|---|---|
| M1 | F2 consumes `xlate.rsp.ppn` live instead of `f2Ppn` | the translation-capture oracle, on a page-crossing fetch stream |
| M2 | F1 advances without `xlate.rsp.ready` | a fault/miss misclassification in `IcacheSpec`'s cold-walk case, and the capture oracle |
| M3 | F2 dispatches while the FSM is in `REFILL` | the ordering oracle |
| M4 | `RING` left at 3 | `FetchAlignRingTurnoverSpec` — a measurable II regression, proving the ring change is load-bearing rather than cosmetic |
| M5 | slice 1a's priority left un-inverted (install still gated on live `demandFillStart`) | a new directed test that a completed install and a demand miss in the same cycle both complete, in the specified order |

### 12.3 New directed test: `IcacheFetchPipelineSpec`

Minimum cases, all against the real `ItlbPlugin` + real walker + real
`BehavioralMemAgent` (the `FetchAlignResidentCadenceSpec` fixture pattern):

1. **Sustained II = 1** over ≥ 32 resident-hit commands, with exact PCs and
   opwords checked, and exactly one response per command in order.
2. **Latency = 3** measured exactly from `cmd.fire` to `rsp.valid`.
3. **Cold ITLB walk with F1 occupied**: the walk launches, F1 holds, the command
   advances on the first resolved cycle, and the response is correct.
4. **Walk overlapping a demand refill** (the §10.3 gain): assert the walk starts
   before the refill completes.
5. **Miss with a younger command held in F2**: `REPLAY`'s response is emitted
   first; the held command's response second; both correct.
6. **Fault with two younger commands in the pipe** (H3): exactly one fault
   response, `faultHold` engages, younger responses are consumed and not
   mis-attributed.
7. **`invalidateAll` in each of the four pipe positions** (H4): no stale line is
   ever delivered.
8. **Set-collision hold at F2** (H6) during a `PF_PRED` array-write cycle.
9. **Redirect with commands in F1 and F2**: both responses are consumed and
   discarded via `ringStale`; the ring returns to empty; no wedge.

### 12.4 Existing suites that must pass unchanged

`IcacheSpec`, `IcacheParallelViptSpec`, `IcachePrefetchSpec`,
`IcacheInvalidateSpec`, `PerBeatPredecodeEquivSpec`, `IdentityTranslationSpec`,
`FetchAlignSpec`, `FetchAlignResidentCadenceSpec`, `FetchAlignRingTurnoverSpec`,
`FetchDirectedFtbSpec`, `FtbSpec`, `GshareSpec`, `RasPluginSpec`,
`FtqCapacitySpec` (re-measured peak), the MMU suite, `test-fast` (149/149), and
the lock-step suite × 2 seeds.

---

## 13. Cost estimate

| item | estimate |
|---|---:|
| `IcachePlugin.scala` restructure (F1 stage, `lookupTick` → F2, slice 1's three decouplings) | ~300–400 lines changed |
| `FetchAlignPlugin.scala` (`RING` + comments + the run-ahead note) | ~20 lines |
| spec amendments (§8) | ~80 lines |
| `IcacheFetchPipelineSpec` + oracles + mutation harness | ~600–700 lines |
| probe script extensions (slice 0) | ~100 lines |
| **total** | **~1,100–1,300 lines across ~7 files** |
| new flops | ~89 (F1/F2 context) + 0 (RING widening reuses existing 2-bit indices) |
| new LUTs | net negative expected: the `ringInc` comparator and the live install-veto cone are deleted; the F2 verdict logic is the same logic, relocated |
| BRAM / DSP | unchanged |

---

## 14. Open questions

1. **Should slice 2 wait for the D-cache/IQ arc's design?** The measured tie
   (§9.1) means neither arc alone moves WNS. The elaboration flag in slice 2
   exists precisely so the answer can be "no, land it disabled, and measure the
   pair" — but if the arc-2 design is imminent, sequencing slice 2 *after* it and
   gating both together is the cheaper measurement.
2. **Is the population metric predictive?** It has never been validated against a
   real route. Slice 2's post-route gate is the first test of it, and that is
   worth stating as a result in its own right regardless of the FMax outcome —
   it either validates or retires the metric the whole three-arc program is
   prioritised by.
3. **`BUF_WORDS`.** §4.6 argues the fourth ring slot is only needed when the IBuf
   is empty. If measurement disagrees, slice 6 is the remedy, but it reopens a
   previously measured FMax limiter.
4. **Does anything else consume the 3-cycle response latency assumption?** The
   audit in §4.6 found only `RING` and the FTQ run-ahead bound. A grep-level
   re-audit is a slice-2 prerequisite, not an assumption.
