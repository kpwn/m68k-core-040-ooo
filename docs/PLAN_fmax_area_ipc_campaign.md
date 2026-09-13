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
3. **`DebugCtrlPlugin` out of performance builds.** 5,232 cells. NOTE: VIO and ADB
   injection are SoC-side (`SLOT_ADBINJ`, JTAG-AXI) and are NOT affected -- keep both.
   This blinds `halt-status`/exc-ring, so it is a measurement-build knob only.

---

## 3. The bitfield cascade -- main line of work

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
5. Shift is then clean fixed-latency -> convert to a STATIC scoreboard trigger,
   retiring `aluSlowWait` / `aluSlowWakeup`.
6. Which lets `aluSlowSlots` / `aluFastAcceptNext` / `isAluSlow` leave the **hot IQ
   select cone** (15 of its 19 levels).

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

Sim/whitebox is clean -- no test reaches into a bitfield signal. `AluFastSlowSpec` does
exercise BITFIELD as a slow op and its cases need re-homing. Stale comments to fix while
in there: `:598-599`, `:131-133`, `:811`, `:20-22`, `:58-66`.

---

## 4. Bigger structural items, in value order

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
  `place.dcp` with `route_design -directive Explore`, then the TWO post-route passes the
  normal flow does and a naive resume SKIPS -- `phys_opt_design -directive
  AggressiveExplore` then `-directive AlternateReplication`. Skipping them left 10
  failing hold endpoints; running them took it to 1.
