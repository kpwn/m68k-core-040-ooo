# Campaign plan: frequency, area and IPC as ONE budget

Status 2026-09-13. Supersedes the framing of the earlier "close 200 MHz with
hot-neutral retimes" effort, which is measured-closed (see §1).

---

## 0. The framing

`performance = IPC x frequency`. This campaign previously treated them as separate
goals and lost on both. They are one budget, joined by AREA:

    area  ->  device utilisation  ->  placement spread  ->  wire delay  ->  frequency

At ~80% LUT utilisation the placer cannot keep a unit together, so area spent on
execute width is paid back as lost frequency. **Peak IPC the front end cannot sustain
is the worst thing to buy with area**, because the frequency loss is sustained.

The machine is **2-wide at rename and retire** but **5-wide at issue** (2 ALU +
branch + LS + CPLX). A large part of the backend exists for bandwidth that cannot be
sustained. Rebalancing toward what the front end can feed is the through-line of this
plan -- not "trimming fat".

---

## 1. MEASURED, do not re-derive

Full census of every failing setup endpoint at 200 MHz + eth
(`build/vivado200eth_loc/checkpoints/route.dcp`, 23,304 endpoints):

- **route = 80.4% of datapath delay.** Per failing path: logic 0.983 ns, net 4.036 ns,
  against a 5.0 ns budget. If routing were free, every failing path would pass.
- Slack is a smooth plateau, no cliff: 78% of failing endpoints are within 0.3 ns.
- CPU is 69.1% of failing endpoints; u_l2c 13.7%; u_dafb 6.3%.
- Utilisation: ~80% LUTs, **URAM 64/64 = 100%**. CPU = 107,902 of 174,619 design LUTs.
- Every logical unit is smeared across the die (DivEu 99% of X, DecodeStage 95%,
  Icache 91% of Y) because `M68kCore` is ONE flat 374,450-line Verilog module.

Consequences, each independently tested:

- **Retiming is exhausted.** Four correct, individually-verified locality retimes
  (`9ca2a430`) moved post-route WNS **-0.053 ns** (worse). Retiming shortens logic --
  the 20% that is not binding.
- **Name-based floorplanning is blocked.** `place_design` fails: synthesis has fused
  cells from different units into single indivisible shapes (one MUXF7/F8 tree holds
  both store-queue and ROB exception-FSM logic). You cannot pblock boundaries that no
  longer exist.
- **Module hierarchy alone does not cluster.** Modules that ARE real today are smeared
  too (`AxiDMerge`: 1,389 cells over 80x120 slices; `StoreQueue`: 155 rows). Vivado
  places by CONNECTIVITY, not hierarchy. Hierarchy is a PREREQUISITE for pblocking,
  not a timing fix.

---

## 1a. THE IPC LEDGER -- what each item does to the hot path

The goal is to close 200 MHz WITHOUT losing IPC, ideally GAINING it. Tracking that
explicitly, because "area reduction" reads as a pure sacrifice and here it is not:

| item | hot-path IPC | why |
|---|---|---|
| Early slow wakeup (S3->S1b) | **+2 cycles per shift->dependent edge** | broadcast at the bypass cycle was 2 cycles late; anticipation restores the machine's own "wake 2 before bypass" rule |
| Bitfield extraction | **+2 cycles per shift** | shift drops lat-6 -> lat-4 once S1a/S1b (bitfield-only) go |
| Speculative LS wakeup | **+1 cycle on the most common dependency** | load-use; dependents currently learn at completion broadcast |
| Balanced bypass mux | neutral IPC, **shortens the hot read path** | linear N-level fold -> compare + log2(N) + mux |
| Write-port reservation | neutral | select-time stall only on reservation miss; ~1 write/cycle vs 2 ports |
| `op` out of `IqHot` | neutral | no functional reader |
| DebugCtrl out of perf builds | neutral | not in any datapath |
| Bitfield on the single CPLX port | **- (unquantified)** | head-of-line blocking; the ONE item with a real IPC cost |

So the ledger is strongly positive before the area win is even counted. The only debit
is bitfield contending for the single CPLX issue port -- measure it (§6).

**AND the locality argument means some of these pay twice.** Static/local wakeup is not
mainly an IPC play: dynamic wakeup requires a CROSS-PLUGIN BROADCAST from each EU into
all 16 IQ slots (`aluSlowWakeup` x2 ALUs, `lsWakeup`, four `cplx*Wakeup`), each fanning
to ~5 comparators per slot. That is the same shape-C anti-pattern as §1, inside the
hot select region. Closing the wakeup-select loop LOCALLY -- the IQ predicts the common
case, the EU signals only the exception (a miss -> replay) -- shrinks the loop's physical
extent, which under an 80%-wire regime is worth more than any logic-level count.
⚠️ This is why an analysis that judged static wakeup by LOGIC LEVELS ("saves ~0 of 19")
reached the wrong conclusion: it measured the 20%.

---

## 2. Free wins -- zero IPC cost, do these first

They also calibrate the utilisation->WNS slope, which is still UNMEASURED. Do not
spend IPC until that slope is known.

1. **Balanced bypass mux** (`RegFilePlugin`). The read path was
   `bypasses.foldLeft(rfData)(Mux(hit, b.data, acc))` -- LINEAR, so N sources = N mux
   levels on the hot register-read path (6 today). The priority is unnecessary: rename
   guarantees every in-flight writer owns a DISTINCT physical register, so at most one
   source can match. Hit-vector + balanced masked-OR = compare + log2(N) + one mux.
   *Written, uncommitted, untested.*
2. **`op` out of `IqHot`.** 6 bits x 16 slots of compaction shift network x 5 select
   muxes, with NO functional reader (`simPublic` only). 3 tests reference it.
   *Still open.* ⚠️ The bit-field merge made this HARDER to lose by accident and easier
   to finish: the resolution deliberately kept `isAluSlowProducer`/`srcBIsReg` reading
   the precomputed `isAluSlow`/`srcBRegDespiteImm` flops rather than re-deriving them
   from `u.op` in the select cone (the merged-in branch had re-introduced `u.op === ...`
   there). Keep every new IQ predicate precomputed on the PUSH path or this item dies.
3. **`DebugCtrlPlugin` out of performance builds.** 5,232 cells. NOTE: VIO and ADB
   injection are SoC-side (`SLOT_ADBINJ`, JTAG-AXI) and are NOT affected -- keep both.
   This blinds `halt-status`/exc-ring, so it is a measurement-build knob only.

---

## 3. The bitfield cascade -- main line of work

> ### ✅ DONE 2026-09-13 -- steps 1-5 landed, MEASURED. Do not re-plan this section.
>
> Merged as `e35fc65e` (agent commits `1b3f5b9a` + `6924f4e1`, resolved onto master).
> OOC area, BASE vs HEAD through the same flow:
>
> | | LUT | FF | CARRY | MUXF | RAM |
> |---|---|---|---|---|---|
> | BASE | 100434 | 39765 | 1036 | 4928 | 15804 |
> | HEAD | 99298 | 38481 | 1024 | 4678 | 15854 |
> | **delta** | **-1136 (-1.1%)** | **-1284 (-3.2%)** | -12 | -250 | +50 |
>
> **Area and IPC moved the SAME direction** -- the duplicated bit-field cone left both
> ALU EUs *and* shift went lat-6 -> lat-4. That is the campaign's thesis holding on a
> real measurement, not an argument.
>
> Step 5 (early broadcast) is `92e8f69a`. ⚠️ Its anchor stage MOVED as a consequence of
> step 4: the rule is "broadcast two stages before S3", which was S1b in the six-stage
> pipe and is **S1a** in the four-stage one. The anchor tracks pipe DEPTH, not a stage
> NAME, and getting it wrong is a silent stale read. Re-check it on any future change to
> the slow depth.
>
> Step 6 stays refuted (write-port structural hazard, not wakeup) -- only §4a removes it.
>
> **Still unvalidated:** the merge resolution has not been compiled or tested (a Vivado
> build held the memory budget). Run the EU/IQ suites before trusting it.


`DecOp.isAluSlow = SHIFT || BITFIELD`, inside `AluEuPlugin` which is instantiated
**TWICE** (eu0, eu1) -- so it is duplicated.

⚠️ The slow pipe is **SIX** stages: `S1, S1a, S1a2, S1b, S2, S3`. The comment at
`AluEuPlugin.scala:598-599` saying "lat-5 (S1,S1a,S1b,S2,S3)" is **STALE** -- it
predates task #123's S1a2 stage, and the class doc at :62 contradicts it. Fix it.

**TWO of those stages are pure pass-throughs for shift, not one.** Verified by
fan-out: `s1aStage1a` (:607) has exactly ONE reader, `s1a2Stage1a` (:761); and
`s1bStage1` (:771) has exactly ONE reader, `s2Stage1` (:777). No datapath function
reads either. S1a and S1b exist ONLY for bitfield. Shift therefore goes
**lat-6 -> lat-4** -- two cycles off every shift.

Stage map: SHIFT computes in S1 (`Shifter.stage1a`), S1a2 (`stage1b`), S2 (`stage2`),
S3 (merge/writeback). BITFIELD computes in S1 (forward funnel + `bfCmd`), S1a
(`Bitfield.stage1`), S1a2 (`Bitfield.stage2`), S1b (inverse funnel + store-form mux).
`Bitfield.scala` (220 lines) has exactly ONE caller in the repo -- it moves wholesale.

So bitfield sets the latency of the common case AND forced the dynamic wakeup. Moving
it cascades:

1. Add BITFIELD as a **lane** in `DivEuPlugin` (CPLX). DivEu is already lane-structured
   -- `issuePort.ready` is per-op-class (MUL credit gate, FP-fixed unconditional,
   FP-iter own busy, DIV/CHK fallback), so a pipelined bitfield lane accepts 1/cycle and
   does NOT queue behind an iterative DIV.
2. Arbitrate it onto DivEu's single `intW`. Safe HERE (unlike at the PRF) because lane
   accept gates give real backpressure.
3. Delete the bitfield datapath from `AluEuPlugin` -- removes **two** copies (funnel
   shifter, mask gen, BFFFO priority encoder). **This is where the area lands.**
4. Drop S1b from the shift pipe: shift lat-5 -> **lat-4**, one cycle off a common op.
5. ~~Convert shift to a STATIC scoreboard trigger~~ -- **DOWNGRADED, see §3b. Do the
   6-line EARLY BROADCAST instead; it gets the entire IPC win for free.**
6. ~~Which lets `aluSlowSlots`/`aluFastAcceptNext`/`isAluSlow` leave the hot IQ select
   cone~~ -- **WRONG, this was a non-sequitur. Those masks exist for a WRITE-PORT
   STRUCTURAL HAZARD (fast-S1 and slow-S3 share one physical port via `wbKey`;
   `fastAcceptNextPort` is the look-ahead keeping them exclusive), NOT for dependency
   wakeup. At lat-4 the collision still exists, so they stay. Only §4a write-port
   RESERVATION removes them.**

Steps 1-3 = area. 4-6 = IPC + hot path. Each is independently checkable; do not bet the
whole cascade up front. The load-bearing assumption (S1a/S1b are pure pass-throughs for
shift) is **VERIFIED** -- see the stage map above.

### 3a. Extraction risks -- every one of these fails SILENTLY

1. ⚠️ **`fastAcceptNextPort` (`AluEuPlugin.scala:237`) hardcodes `!s1bValid`** because
   S1b is "one before S2". Delete S1b without RE-POINTING this to the new pre-S2 stage
   and the IQ's select-time look-ahead lies by a cycle -> a fast S1 writeback collides
   with a slow S3 writeback on the `wbKey`-merged port. The fast request has
   `priority=1`, so it manifests as a **silently DROPPED SLOW RESULT**, not an error.
   Highest-risk line in the change.
2. ⚠️ **Do NOT delete the `rdC` third int read port.** Its comment (:131-133) claims
   BITFIELD is the only `psrcC` user on this EU -- **STALE**. `CASOP` reads it
   (`casC`, :416, fed by `Microcode.scala:821,852-853`). Deleting it silently corrupts
   CAS/CAS2.
3. ⚠️ **Narrowing `DecOp.isAluSlow` (`DecodedUop.scala:177`) to SHIFT-only** is correct,
   but any BITFIELD uop still routed to `Cluster.INT` is then treated as a **fast lat-1
   producer** by the static scoreboards -> silent RAW hazard. The cluster change has >=4
   sites across TWO mirrored models (`Microcode.scala:2640` `resolve()` and `:3224`
   `resolveHw()`, plus `MicroOpAssembler.scala:3503` and the register-form block at
   `:1431-1464`). `MicrocodeResolveEquivalenceSpec` catches a one-armed edit; it cannot
   catch a both-armed one.
4. **A dedicated pipelined bitfield LANE in DivEu is mandatory, not optional.** Bit-field
   memory RMW chains issue **3-4 BITFIELD uops back to back** (`Microcode.scala:711,
   730-734`); DivEu's legacy lane gates on `!busy && !s1Valid` (:414-418) and would
   serialize them behind each other and behind any DIV/CHK.
5. **DivEu's `s0B` goes through a `useImm` mux** (`DivEuPlugin.scala:379`). Every
   BITFIELD uop sets `useImm=True` AND needs the **raw** `rdB` (this is what
   `srcBRegDespiteImm` exists for). DivEu already has one ad-hoc raw-rdB workaround for
   the FP rows (:1083); bitfield needs the same, or BFINS reads the immediate as its
   insert source.
6. **Bit-field cracks contain non-BITFIELD uops that STAY on AluEu**: `UBfShiftOff` ->
   SHIFT, `UBfAdd` -> ADD, `UBfResolve` -> BFRESOLVE (a fast lat-1 ALU op). So one
   bit-field instruction becomes a CROSS-CLUSTER chain (ALU BFRESOLVE -> CPLX BITFIELD
   -> ALU SHIFT/ADD), and the CPLX->ALU edges need `cplxWait`/`cplxNzvcWait` instead of
   `aluSlowWait`.
7. **BITFIELD's contract for DivEu**: writes INT (full 32 bits, NO .B/.W size merge) and
   NZVC (N,Z only; V=0,C=0); **never writes X**; reads NO flags; needs up to THREE int
   sources; raises NO faults (`AluEuService` has no fault port -- bit-field illegality is
   resolved at decode). DivEu has 3 int reads and no X port, so the shape fits.

**MEASURED SIZING -- the area win is modest, so do this for the right reasons.**
Net ~**-1,510 LUT** and ~-480 FF: delete 2 copies from AluEu (est. -3,360 LUT, measured
-1,568 FF), add 1 to DivEu (+1,680 LUT, +784 FF) plus lane overhead (~+170 LUT, +300 FF).
⚠️ Scope note: that is against the CORE-ONLY OOC census (`synth/census_util.rpt`:
103,740 LUTs = 47.82%, FFs 10.91%). The ~80% figure in §1 is the FULL SoC build
(174,619 of 216,960) -- the SoC number is the one that governs placement pressure. **FF
savings do not relieve the binding resource; LUTs do.** The strongest argument is
second-order: removing ~3,670 FF of 918-bit `IqContext` fanout from two EUs cuts routing
and control-set pressure (1,391 unique control sets) on a design that keeps losing on
placement rather than logic depth. The contingent second prize is deleting the two
lat-match-only stages (~7,236 FF across both instances, 15% of ALL design flops) -- but
that is load-bearing in the `wbKey` proof and in `fastAcceptNextPort`, so gate it
separately.

⚠️ **TOP IPC RISK, and it is unmodelled: single CPLX issue port head-of-line blocking.**
`ohC` selects exactly ONE CPLX slot per cycle. Today BITFIELD can go to EITHER ALU port.
Worse, memory-RMW chains emit **three consecutive `UBfMem` uops** which would all contend
for that one port alongside the entire FP family, MUL, DIV and CHK -- delaying every FP
and MUL uop behind them. Measure before committing.

**DivEu integration, settled:** copy the **MUL lane** (pruned context descriptor +
credit-reserved `StreamFifo` + credit-counter accept gate), NOT the FP-fixed lane, whose
unconditional accept is only sound because it owns its completion register outright.
`intW` needs NO structural change: DivEu does not arbitrate at the write port, it
arbitrates one stage earlier into a shared completion register with HOLDING sources
(`:1537-1566`), so a bitfield lane is a **fourth arm** reusing `captureArb`. Everything
downstream (`intW`, `intByp`, `nzvcW`, `nzvcByp`, `completionPort`, wakeups, `wbObs`,
`ccrObs`) then comes free. No new IQ wait bit, no new ROB completion port, no new
`ccrCompletion` port, no new regfile port. Do NOT use `sharingKey` here -- that is the
silent priority-fold, and DivEu cannot construct the mutual-exclusion proof AluEu has.

⚠️ `s0B = Mux(u0.useImm, u0.imm, rdB.data)` -- every BITFIELD row sets `useImm=True` AND
needs the raw register (that is what `srcBRegDespiteImm` exists for). Capture raw
`rdB.data`, or BFINS silently inserts the immediate. Precedent: `fpS1IntB := rdB.data`.
⚠️ DivEu does NOT gate `psrcAValid` (bare `val s0A = rdA.data`); AluEu does, and its
comment records a real ISA gap on the bit-field dynamic-offset path. Audit every
`UBfMem`/`UBfReg` row for a possibly-invalid srcA.
⚠️ DivEu already carries THREE FMax post-mortems; adding 7 barrel rotates and a 32-input
CLZ risks placement congestion. OOC A/B before committing -- and elaboration order alone
has moved post-route FMax 12.7-17.8 MHz here, so one measurement is not a gate.

Sim/whitebox is clean -- no test reaches into a bitfield signal. `AluFastSlowSpec` does
exercise BITFIELD as a slow op and its cases need re-homing. Stale comments to fix while
in there: `:598-599`, `:131-133`, `:811`, `:20-22`, `:58-66`.

---

## 3b. Slow-ALU wakeup: do the EARLY BROADCAST, not the static conversion

**The rule this machine obeys** is "wake the consumer exactly 2 cycles before the
producer's bypass cycle" -- which is why the FAST path's static `events` trigger clears
at SELECT time, not at writeback. The slow path broadcast at S3, i.e. AT its bypass
cycle, so a dependent became ready a cycle later and read a cycle after that: **2 cycles
late on every shift->dependent edge.**

**The fix is ~6 lines in `AluEuPlugin`,** moving the broadcast from S3 to S1b
(`slowWakeupPort.valid := s1bValid && !flushPort`, payload from `s1bCtx.uop`). Wake at
S3-2 -> ready at S3-1 -> select at S3-1 -> the consumer's S0 read lands exactly on S3,
the cycle `intByps`/`nzvcByps`/`xByps` forward. **This captures 100% of the IPC a fully
static conversion would deliver.** The gain is ANTICIPATION, not staticness.

Sound because the slow pipe CANNOT STALL, making S1b a reliable 2-cycle predictor of S3:
`issuePort.ready` is unconditional for a slow uop, the valid chain is plain `RegNext`
with no back-pressure, and the S3 write port is reserved ahead by `fastAcceptNextPort`.
Those three are the invariant; if any stops holding this becomes a SILENT stale read
(there is no replay path). Flush-safe because `doFlush` is retire-gated, so a flush kills
producer and early-woken consumer together -- a mid-pipeline flush source would break
that. After the bitfield extraction, re-point the broadcast to the new 2-before-S3 stage.

**Why the STATIC conversion is NOT worth doing after this:**
- **IPC delta becomes ZERO.** It buys only area: ~98 FF of `aluSlow*` bitmaps + wait bit,
  several hundred LUTs of the per-slot wakeup CAM (`IssueQueuePlugin.scala:1136-1164`,
  which is OFF the critical path), and 2 EU ports.
- ⛔ **The compaction shift DISCARDS trigger bits 0/1** (`:951`). That is sound today only
  because `push.ready` guarantees line 0 is empty at every compaction. A DELAYED event
  references an already-freed slot and violates exactly that premise -- on the most
  common firing site (`OHMasking.first` picks the LOWEST index, so producers fire from
  line 0/1), within one cycle of the fire. Result: silent stale read.
- Repairing it needs **2d guard bits per slot** below index 0 across all 16 slots (+96 FF
  after extraction, +160 before -- so never attempt it before §3) plus a guard-extended
  `physToSlot` across five scoreboards. The wider trigger zero-compare lands in the
  `ready` cone INCLUDING slot 0, whose test is currently a free literal `True` and sits
  at the head of both `OHMasking.first` priority chains. **Net effect on the 19-level
  path is plausibly NEGATIVE.**
- `!aluSlowWait` is one input of a 9-input AND; dropping to 8 removes no LUT level.

⚠️ It would also force slow producers into `sbInt`/`sbNzvc`/`sbX`, whose retimed C+1
busy-clear is WRONG for them -- re-adding the `!slowFire` discrimination that
`IssueQueuePlugin.scala:1278-1310` documents as the design's measured WNS holder.

---

## 4. Bigger structural items, in value order

> ### ⭐ 0. PRF WRITE-PORT SLOT PERMUTATION -- best MHz per unit of risk, do it FIRST
>
> `RegFilePlugin` records that the physical write-port slot ORDER moved post-route
> FMax by **12.7-17.8 MHz** between two observed draws. That is larger than any single
> RTL lever measured in this campaign, and it costs NO area, NO IPC and NO logic
> change -- nothing in the design depends on the order.
>
> The current order (`0=AluEu0 1=AluEu1 2=BranchEu 3=LsEu 4=DivEu 5=excA7`) was chosen
> to END an elaboration-order lottery, not to be fast, and **has never been measured**.
> So there is a double-digit-MHz result sitting unclaimed behind a one-line change.
>
> Now sweepable without touching source (`83a1a1ac`):
>
>     PRF_SLOT_PERM_INT=2,0,1,3,4,5    make impl ...
>
> Default = identity = today's order, so the knob is inert until used. A malformed
> permutation FAILS ELABORATION -- it would otherwise silently delete a physical write
> port, which is register-file corruption that shows up in a timing report as a
> *faster* build.
>
> **Method:** 6 slots = 720 permutations, so do NOT sweep exhaustively. Each point is a
> full implementation run. Sample a handful, and read §7 before believing any delta --
> run-to-run implementation variance is real and this effect must be separated from it.
> The Nzvc and X files have their own slot orders and their own knobs.


1. **Load-use speculative wakeup.** `lsWait` is DYNAMIC: a load's dependents learn at
   completion broadcast and issue a cycle late. Load-use is the most common dependency
   in real code, so this cycle is paid constantly. Static/speculative wakeup on
   predicted cache hit + replay on miss is the standard fix and is **the biggest IPC
   lever in the backend**. Also the hardest -- this core's replay history is a minefield.
2. **Execute-width rebalance.** eu0 and eu1 are both FULL ALUs (11,186 cells each). With
   2-wide rename/retire, much of the 5-wide issue capability is unsustainable. The
   bitfield extraction is the first instance; sharing or specialising further is the
   bigger prize.
3. **ROB per-entry state.** 19,318 cells, much of it flip-flop `Vec`s (`sysValStore`
   64x32, nzvc/x/faulted/faultDyn/mispredict/phtValid/btbIsBranch/branchTaken) where a
   `Mem` lands in LUTRAM. NOTE: the `sysValStore` fold was tried and correctly REJECTED
   as unsound (physical-register-lifetime hazard) -- read that comment before touching.
4. **PRF sizing.** 50 physical int registers against a 64-entry ROB = ~34 in-flight
   renames, so the PRF, not the ROB, bounds the window. One of the two is mis-sized.
5. **Module hierarchy** (make major plugins real `Component`s). Not a timing fix by
   itself (§1) but the prerequisite for any floorplanning, and it stops synthesis fusing
   units together.

---

## 4a. Two PRF write ports via WRITE-PORT RESERVATION (the design that makes it work)

**Why 5-wide issue is right and should stay.** The point of five select ports is
LATENCY-CLASS DECOUPLING -- a DIV must not occupy a port an ALU op needs -- not peak
bandwidth. Specialised ports per latency class are correct. The real duplication cost
is that eu0 and eu1 are each a FULL ALU carrying a six-stage shift+bitfield pipe; that
is what §3 attacks. Do not "narrow the issue width" as an area measure.

**The tension.** Static wakeup is a PROMISE: "this result is in the PRF at T+N". Write-
port contention breaks the promise and the consumer reads STALE. So fewer write ports
and static wakeup are in direct conflict -- unless the port is RESERVED when the promise
is made.

**The resolution: reserve the write port at SELECT time.** A small shared table, 2 ports
x N future cycles (a 2-bit-wide shift register ~6 deep):

- A STATIC-latency op reserves its slot at T+N when it is selected. If no slot is free
  it is not selected. The promise is then guaranteed by construction.
- A VARIABLE-latency op (DIV, FP-iterative, loads) does NOT reserve. It arbitrates for a
  free port when it actually completes, and -- the safety property -- **broadcasts its
  dynamic wakeup only once the write has landed**. A delayed write can then never be
  read early.

Heterogeneous latencies SELF-DISTRIBUTE: two lat-1 ALU ops want the same future cycle
(that is what the 2 ports are for), while a lat-4 shift lands three cycles later and
does not contend at all. At ~1 write/cycle average against 2 ports, occupancy is low,
and a reservation miss is a cheap SELECT-time stall (the uop stays in the IQ).

**This DELETES hot-path logic rather than adding it.** The core already has a degenerate,
one-cycle, per-EU version of exactly this: `fastAcceptNext` ("a fast candidate is
eligible only when fastAcceptNext says the EU will have no S3 collision next cycle").
Generalising it replaces the ad-hoc `aluFastAcceptNext`/`aluSlowSlots` muxing -- which
sits in the IQ SELECT cone -- with one shared lookup.

**Prerequisite:** this makes adding `ready` to `RegFileWritePort` mandatory, not an
optional cleanup -- an arbitrating completer needs to be told "not this cycle". See the
dead-end entry below for why merging without it is unsafe.

**Residual:** loads are the most common writer AND variable-latency, so they keep
dynamic wakeup with deferred broadcast -- safe, but it leaves the load-use cycle
unclaimed. Reserving on PREDICTED hit with replay on miss buys it back; that is §4.1 and
should be decided separately from the write-port work.

---

## 5. DEAD ENDS -- do not retry

- **PRF write-port merging while `RegFileWritePort` has no `ready`.** Every int writer
  except `excA7` can write back-to-back (eu0, eu1, lsEu, branchEu, and divEu via
  pipelined `MulCore`). A 1-deep holding slot does NOT fix it: the invariant is the
  DRAIN GUARANTEE, not the writer's rate, and any member that always wins starves its
  co-members. Failure mode is a SILENTLY LOST register write; a sim assert does not
  protect silicon. Attempt reverted; diff kept at `scratchpad/prf_arb_UNSOUND.patch`.
  **Unblocked only by adding `ready` to the write port.**
- **`irqPreemptArmed = iplActive || tracePendingReg`** (naive shallow superset for the
  -1.834 ns cone). LIVELOCKS: MOVEM against cache-inhibited space parks NON-first
  inhibited load uops at the ROB head, so suppressing them means the macro never
  completes, so `normalIrqGate` never asserts, so the suppression never lifts. The sound
  form adds `&& (p0.first || stopped)`.
- **PRF depth 50 -> 32.** Would cut in-flight renames ~34 -> ~16 against a 64-entry ROB.
  Severe throttle for a modest area win.
- **Clustered / unit-local register files** (Alpha-21264 style). Wrong axis: it reduces
  READ ports per copy, and reads are already the cheap dimension (exactly linear below
  the measured 12-read LUTRAM inference cliff; replication is area-NEUTRAL). Write ports
  are what cost -- under LVT each is a FULL copy.
- **Name-pattern floorplanning** -- blocked by LUT-level fusion (§1).

---

## 6. Instrumentation we still lack

- **IPC baseline.** `OFF_INST_LO/HI` (macro retire count) exists. Baseline real IPC on
  the boot workload and re-measure after every area change, or we will be guessing at
  IPC the way we were guessing at timing before the census.
- **Fast area gate.** OOC synth of the core is ~10 min vs ~50 for a full route. Its FMax
  has a ~1 ns noise floor -- **use it for AREA only**, which is what it is good for.
  ⭐ A debug-OFF twin ALREADY EXISTS: `M68kFullCoreSynthNoDebug`
  (`FullCoreSynth.scala:828`, `debugEnable = false`). So `DebugCtrlPlugin`'s area cost is
  measurable with NO RTL change -- run the gate on both twins and diff. Do this first; it
  also calibrates the gate itself against a known-nonzero delta.
- **Bitfield dynamic frequency** in the boot workload, before betting on §3.
- **Utilisation -> WNS slope.** Unknown. Establish it with one full route per ~10k LUTs
  removed. If 10k buys ~0.2 ns, 200 MHz is reachable; if it buys 0.03 ns, stop.

---

## 7. Measurement discipline -- traps already paid for

- **Post-synth does not predict post-route.** `9ca2a430` improved post-synth WNS by
  +0.457 ns and TNS by 45%, then finished -0.053 ns WORSE. The baseline itself goes
  -1.694 post-synth -> -0.678 post-route, because place/route levels the tall cone into
  the plateau. **Judge only post-route.**
- **Mid-route WNS/TNS are ESTIMATES** for unrouted nets and wildly optimistic: one build
  read "WNS -0.486 / TNS -281" at global iteration 0 and settled at -0.731 / -5763.
  Never quote a mid-route number.
- **`report_timing_summary` samples are biased** across clock groups. Its 176-path
  sample said the CPU was 7 of 176 failing endpoints; the full census said 69%.
- **Check the path TYPE.** Several "worst paths" were HOLD, not setup
  (`Requirement: 0.000ns`).
- **Vivado segfaults at `Phase 11.1.1 Leaf ClockOpt Init`** on some netlists, AFTER
  routing succeeds but BEFORE write_checkpoint. No parameter disables that pass
  (checked `list_param` for skew|clockopt|leafclock|progdelay). Recovery: resume from
  `place.dcp` -- but `place.dcp` is written at `vivado.tcl:2525` and the flow runs MORE
  PASSES AFTER IT, so a naive resume silently drops them. The CORRECT sequence, measured:

  | resume | passes | WNS | WHS | failing hold |
  |---|---|---|---|---|
  | v1 | route only | -0.197 | **-0.510** | **1** |
  | v2 | + pre-route `phys_opt -directive AggressiveExplore` (:2531) | **-0.089** | **+0.009** | **0** |
  | v3 | + pre-route `phys_opt -force_replication_on_nets` (:2549) | -0.089 | +0.009 | 0 (no change) |

  So: **`open_checkpoint place.dcp` -> `phys_opt_design -directive AggressiveExplore` ->
  `route_design -directive Explore` -> `phys_opt_design -directive AggressiveExplore` ->
  `phys_opt_design -directive AlternateReplication`.** The force-replication pass matches
  NO nets on this design (`dbg_overlay_q|crat_rb|vif_rdata`), which is why v3 == v2
  bit-for-bit; include it anyway for fidelity, it is free.

  ⚠️ Skipping the PRE-route pass is the expensive mistake: it cost -0.108 ns of WNS and,
  worse, left **-0.510 WHS with a failing hold endpoint** -- and hold violations corrupt
  data at ANY clock rate, which is what produced visible video glitching on the board.
  ⚠️ A recovered build still lands ~0.13 ns short of a native full-flow build
  (-0.089 vs +0.043 on the reference). That gap is NOT a missing pass -- it is placement
  variation (the resumed `place.dcp` came from a different run) and/or the cost of
  whatever RTL differs. Consequence: **a recovered build cannot serve as a timing-clean
  control**, so it cannot distinguish a real RTL bug from timing marginality.
