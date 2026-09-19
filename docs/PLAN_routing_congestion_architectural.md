# PLAN: architectural relief for routing congestion / wire delay (200 MHz, xcku5p)

Source: fable-agent structural review 2026-09-14, verified against the generated
netlist `cpu040/generated/M68kSocketTop.v` at cpu040 `e5bace11` (includes the A7
merge `f3d058bf`) and the routed reports under
`macqd700-soc-worktrees/eth200/build/vivado200_c/reports/`.

**Central fact driving every item: ~80.4% of critical-path delay is WIRE, not logic.**
So the levers are *removing broadcasts and narrowing what crosses between units*,
not shrinking logic depth. Judge changes by **failing-endpoint FAMILY counts** from
`synth/census.tcl` on the routed DCP, not by WNS (placement variance is +-0.4-0.6 ns
and the native flow segfaults in `route_design`, so the recovery path is what runs).

## Status
- [x] **Item 1** dead LsEu/DivEu bypass sources — DONE (`deadProbe` checked first)
- [x] **Item 2** DebugCtrl int write port merged onto `BranchEuPlugin.IntWbKey` — DONE
- [ ] Items 3-12 below: tackle ONE PER SUBAGENT, **simulation only** (no Vivado runs)

## Verified backend port shape (baseline, before items 1-2)
| structure | shape | source |
|---|---|---|
| Int PRF `RamAsyncMwMux_1` | **6W / 17R**, 54x32, LVT + 2 `ReplicatedBank` replicas per write bank (17 reads > the 12-read cliff) | `MultiportRam.scala:170-230`, `RegfileService.scala:17` |
| Int write groups | AluEu0, AluEu1, BranchEu(+excA7), LsEu, DivEu, **DebugCtrl "excA7"** (orphaned -> item 2) | `AluEuPlugin.scala:141`, `BranchEuPlugin.scala:146`, `FullCoreSynth.scala:73`, `LsEuPlugin.scala:253`, `DivEuPlugin.scala:288`, `DebugCtrlPlugin.scala:84` |
| Int bypass sources | 7 into 15 bypassed reads (masked OR-reduce) | `RegFilePlugin.scala:200-254` |
| NZVC PRF `RamAsyncMwXor_7` | 5W / 4R, 16x4 | |
| ROB payload | 2W (tail/tail+1) / 2R (h0,h1), LVT-lowered | `RobPlugin.scala:314, 930, 1736, 1763` |
| IQ | 16 slots, 2 ways, **compacting** shift queue; `RenamedUop` ~418b in `coldWay0/1`, read by all **5** issue ports | `IssueQueuePlugin.scala:22-24, 105-170, 228-232` |
| SQ | 8 entries, forward query ~72b into all entries | `StoreQueue.scala:37-60, 97` |

## Failing-endpoint families (routed, WNS -0.873, 70-82% route on every top path)
1. `LsEu p4Ctx_fwdHit -> SQ CAM -> p4Ctx_fwdHit` (-0.873; `p4RetryQuery` fanout 101)
2. `Dcache tagMem -> ldS1HitVec -> loadMissDiscovered (fo 237) -> LsEu normalReqArm (fo 170) -> IQ triggers/CE` (**3336 of 3856** failing endpoints)
3. `Ftb/Gshare -> FetchAlign applyNow (fo 147) -> fetchPc -> ITLB lookupVpn (fo 171) -> Icache s0Ppn (fo 168)`
4. `FetchAlign ftqHead -> ftqMem RAMD32 -> 8 CARRY8 -> Ras ckRas[*]`
5. `Rob head (replica 16) -> LsEu alignedPushPtr/p4Valid -> Dcache probeLineLine[*]`
6. `AluEu s1Ctx.op -> DivEu bf1Packed`
7. Non-clock high-fanout: `_zz_IssueQueuePlugin_logic_readyReg_1` (= `pushPort.valid && readyReg`) **fanout 3,546**; `RobPlugin tail_reg[3..5]_rep` ~1,425; `payload/wr1LocAddr[*]` ~1,407

---

## Item 3 — `fpWideImm` side table (80b out of the uop record)
**Mechanism.** `fpWideImm` (80b) rides `DecodedUop` -> `MicroOpQueue` (16 x ~450b LUTRAM)
-> rename skid -> `IqContext` -> BOTH `coldWay` Mems (x5 read ports) -> one consumer,
`DivEu fpS1Imm`. Census counted **137+86+77+69 failing endpoints** on `fpWideImm` regs.
Replace with an 8-entry x 80b `FpImmTable` (1W/1R LUTRAM); tag in `imm[2:0]` (those rows
set `useImm := False` so `imm` is free); write at the only non-zero writer; free at the
`fpS1Imm` capture; free-bitmap cleared to all-free on flush (unconsumed entries belong to
squashed uops). Decode stalls at 8 outstanding FP immediates.
**Est.** ~-1k LUT, -~300 FF, +~80 LUT table. IPC 0. Confidence med-high.
**Files.** `DecodedUop.scala:808`, `RenamedUop.scala:214`, `MicroOpAssembler.scala:395, 514, 2431-2529`, `RenameStage.scala:272`, `DivEuPlugin.scala:1078-1121`, `IqContext.scala`.
**Validate.** `IqFpSpec`, FPU specs, fp corpus (FMOVEM/FPSP), lockstep. Sim assert "entry valid at consume"; directed flush-then-FP-imm test.
**Risk.** Low-med (FP op reading a freed/overwritten slot).

## Item 4 — per-class cold payload Mems
**Mechanism.** Every issue port fetches the whole ~418b `RenamedUop` from `coldWay0/1`,
but EUs read disjoint subsets: AluEu 31 fields, BranchEu 32 (sole reader of `branchDisp`,
`predTarget`, `predTaken`, `phtIndex`, `cond`, `anInc`, `isScc/isDbcc`), LsEu 34, DivEu 35
(sole reader of `fpuOp`, `fpSrcKind/Fmt`, `bf*`, `div*`, all `pFp*`). Split into
`ColdAlu`/`ColdBr`/`ColdLs`/`ColdCplx`, each a pair of 1W Mems read only by its port(s).
**Est.** cold LUTRAM bit-reads 2x32x418x5 -> ~2x32x(2x130+230+170+330): **-1.5k to -2k LUT**. IPC 0.
**Files.** `IqContext.scala` (bundles + `assignFrom`), `IssueQueuePlugin.scala:228-232` + port wiring, the four EU `issuePort` types, `IssueQueueService.issue`.
**Validate.** Missing field = elaboration failure (safe direction). `IqColdPayloadSpec`, lockstep, corpus. Netlist grep of `coldWay*` widths.
**Risk.** Low. **Combine with item 3.**

## Item 5 — SQ forward retry as a registered replay (current worst path)
**Mechanism.** `LsEuPlugin.scala:1490-1503`: `fwdQueryCtx = Mux(p4RetryQuery, p4Ctx.xlate, p3Ctx)`
drives the CAM. `p4RetryQuery` derives from the PREVIOUS query's response, so
response -> select (fo 101) -> 8 x ~72b CAM -> `io.fwd.rsp.hit` -> `p4Ctx.fwdHit` closes in
ONE cycle (16 levels, 71% route, -0.873). Fix: on `p4RetryQuery`, load `p3Ctx := p4Ctx.xlate`
(hold P2 one cycle) so the CAM address is ALWAYS the P3 flop; retry gates a mux on P3's INPUT.
**Est.** loop becomes flop->CAM->flop. +1 cycle per retry (`fwdStall`/`twoAccess`/`serial` only). Area ~0.
**Files.** `LsEuPlugin.scala:1487-1507`, P2/P3 valid/hold around `p3Valid` (`:964`).
**Validate.** The five `BUG_lsu_stale_fwd_verdict_across_inhibited_barrier` regression tests, LS/SQ specs, corpus. `report_timing -through [get_nets *p4RetryQuery*]` shows no CAM traversal.
**Risk.** MEDIUM — this is the stale-forward-verdict hazard class. Re-argue barrier/serial ordering in the commit message.

## Item 6 — break Dcache -> LsEu -> IQ combinational ready chain (3336-endpoint family)
**Mechanism.** `DcachePlugin.scala:1515-1520`: `loadProbePort.ready` includes
`!(ldS1Valid && !ldS1Hit)` — the tag BRAM's CURRENT output — and that ready feeds
`normalReqArm` -> `xlate.req.fire` -> `tCanLeave` -> `tReady` -> `s1Ready` ->
`issuePort.ready` -> IQ `m2sPipe` (`collapsBubble=false`) -> `selPorts(3).ready` -> slot
`fire` -> `triggers`/scoreboard/compaction CEs in all 16 slots. Five plugins, one cycle.
Fix in two halves: (a) `loadProbe.ready` from REGISTERED occupancy only (credit on the
4-entry probe result queue); a miss at S1 cancels probes behind it via existing
`loadProbeCancel` (a probe is a side-effect-free array read; device reads gate later at
`p4LaunchOk`). (b) `issuePort.ready` from a registered skid-occupancy flag (one-entry skid
at P1, `LsEuPlugin.scala:708-709`).
**Est.** three register-bounded segments. +~420 FF, +~100 LUT. Hit case neutral if the skid is transparent; miss +1-2 cycles on a 20+-cycle event.
**Files.** `DcachePlugin.scala:1515-1520, 1590-1594, 2720-2724` (`storePipeHeld` same term), `LsEuPlugin.scala:2994-3027`.
**Validate.** `DcacheSpec` (73 tests; caught the `4b00cccb` regression), LS specs, mmuwalk 39/39, ls+cache 307/307, corpus with `PORTED_CACHE_SWEEP_LIST` SET, `IpcBenchSpec`.
**Risk.** MED-HIGH. ⚠️ An earlier `maintCmd.valid` staging was REVERTED for moving a backpressure window — this MUST be a credit/occupancy design, NOT a delayed valid.

## Item 7 — non-compacting IQ with an age matrix (kills the 3,546-fanout net)
**Mechanism.** Compacting shift queue: every `pushPort.fire` rewrites all 16 slots from the
line above, decrements `physToSlot`, shifts the triangular `triggers`. That one enable
(`pushPort.valid && readyReg`) is the **largest non-clock net in the core, 3,546 loads**, and
every hot flop carries a 3:1 (hold/shift/push) input mux. Fixed slots + 16x16 age matrix keeps
the identical oldest-first-per-class policy (`oldest = ready & ~(ageRow & classReady)`, 2 LUT
levels); LS in-order (`ohL`) and divide-family rules become "no older occupied slot of that
class"; `physToSlot` becomes static; triggers become a per-slot 16-bit wait mask cleared by the
producer's `fire` (an existing broadcast).
**Est.** -~1.4k LUT of shift muxes, +256 FF, +~200 LUT select. IPC identical by policy.
**Files.** `IssueQueuePlugin.scala` (slot array, `Scoreboard`, `dep()`, select `:454-560`, compaction `:1452`).
**Validate.** `IssueQueueSpec`, and `IqAluSlowSpec`/`IqCplxSpec`/`IqLsSpec`/`IqFpSpec` **run SOLO** (flaky under parallel sbt load — see memory). Lockstep, corpus, `IpcBenchSpec` pinned seed (retired AND cycles must match).
**Risk.** MED-HIGH — same-cycle wakeup race guards must be re-derived (several get simpler).

## Item 8 — ROB payload banked by robId parity (drop the LVT)
**Mechanism.** `payload` has 2 alloc writes at `tail`/`tail+1` and 2 reads at `h0`/`h1`, so it
lowers to `RamAsyncMwMux` with a `location` XOR table and `wr1LocAddr[*]` at ~1,407 fanout.
The ROB OWNS `tail`, so `tail` and `tail+1` always have OPPOSITE parity: bank by parity, each
bank single-write (the lowering pass leaves 1W Mems alone), 2:1 swap on write, `p0`/`p1` read
opposite banks selected by `h0(0)` (a flop).
**Est.** removes the `location` machinery; halves the payload write-address broadcast. IPC 0.
**Files.** `RobPlugin.scala:314, 928-931, 1736, 1763` + `payloadFrom` writers.
**Validate.** ROB specs, lockstep; netlist: `RamAsyncMwMux RobPlugin_logic_payload` disappears.
**Risk.** Low-med (alloc1-only cases, flush `tail := head`).
⚠️ The 1,815 LUT attributed to `RobPlugin_logic_payload` in `utilization_route.rpt` is
IMPLAUSIBLE for a 1-bit 2W/2R XOR table — likely a rebuilt-hierarchy artifact. Saving is "up to".

## Item 9 — generation-bit robIds (stop broadcasting ROB head into the LS cluster)
**Mechanism.** `RobPlugin_logic_head_reg[1]` has >=16 replicas because every LS age compare
subtracts it: SQ "older than query" (`StoreQueue.scala:372-398`), `alignedPushPtr`
(`LsEuPlugin.scala:1228`), `robHeadIn`, UmWriteQueue drain (family #5). Carry one wrap bit per
LS-facing robId: `older(a,b) = (a.wrap == b.wrap) ? a.idx < b.idx : a.idx > b.idx`. Head stays
only for retire.
**Est.** removes a ~5b x 16-replica broadcast. +1 bit per robId in LSU/SQ. ~0 LUT. IPC 0.
**Files.** `Global.scala:18` (or an LS-local width), `StoreQueue.scala` age logic, `LsEuPlugin.scala` age uses, `UmWriteQueue.scala`.
**Validate.** SQ/LS specs, corpus; count paths `-from *RobPlugin_logic_head_reg* -to *LsEuPlugin*`.
**Risk.** MEDIUM — the SQ age proof at `:372-398` (63 live entries vs 8 SQ entries) must be redone for wrap semantics.

## Item 10 — narrow the SQ -> Dcache store beat (128b+16strb -> 64b+8strb+offset)
**Mechanism.** Every drained store carries a full line image (`StoreQueue.scala:497-506`,
`DcacheTypes.scala:189`), captured into `s0Payload` (`DcachePlugin.scala:1169`) — 197 failing
endpoints on `s0Payload_lineData`. A 68k store spans at most 4 bytes: ship two words + strb +
offset, place into the line at the Dcache S1 merge (ONE 4:1 demux level, local). Same for walker
stores (`ItlbPlugin.scala:523`, `DtlbPlugin.scala:676`, `LsEuPlugin.scala:3532`).
**Est.** ~-100 wires x SQ->Dcache distance, -~200 FF. IPC 0.
**Validate.** `DcacheSpec`, ls+cache 307, corpus cache-ON.
**Risk.** Low-med — the strb/lineData form was chosen to shorten the Dcache merge; the demux must stay ONE level.

## Item 11 — cluster-partitioned PRF (the structural LVT replacement) — HIGH RISK, DO LAST
**Mechanism.** Today each of 6 writers owns a full 54x32 copy (x2 replicas) plus a 3-bit XOR
LVT (`location` ~1,973 LUT), and every read is a 6:1 mux SELECTED BY AN LVT LOOKUP. Assign each
physreg a home bank by PRODUCER CLUSTER (rename knows `cluster`): ALU pair (2W over ~24 entries),
LS (1W,16), BR (1W,8), CPLX (1W,8); exception/debug mux onto the target bank's single port under
the existing static-exclusivity rule. Reads become LUTRAM read + 4:1 mux selected by `pdst` HIGH
BITS — an ADDRESS BIT, not an LVT read — so the read path gets SHORTER.
**This is the opposite of the refuted consumer-side read crossbar** (which added a select-time mux
and measured -0.502 ns).
**Est.** PRF 8.1k -> ~3.5-4k LUT; LVT gone; write-data fanout -4x. IPC risk: per-class freelist
exhaustion (e.g. MOVEM burst of 16 LS-class destinations) stalls RENAME — that is backpressure at
rename, NOT on the PRF ports (owner constraint respected) — size banks with headroom and MEASURE.
**Files.** `RenameStage.scala:61-65` (per-bank freelists), `Freelist.scala`, `RegFilePlugin.scala`, `MultiportRam.scala` (bypass the lowering), `DebugCtrlPlugin`/`FullCoreSynth` write sites.
**Validate.** OOC PRF gate with a banked probe in `ExecSynthProbes.scala`, `ExecuteLockStepSpec`, corpus, `IpcBenchSpec` for stall cycles.
**Risk.** HIGH (silent-corruption class). Only after items 1-2 show the PRF is still on the critical list.

## Item 12 — smaller locality items
- **RAS checkpoint**: `Ras.scala:175-190` copies all 16x32b into `ckRas` under
  `checkpointSave = (rob.count === 0)` (`FullCoreSynth.scala:209`) — a ROB-side decode crossing to
  the frontend with 512+ CE loads (family #4). Export `count === 0` as a REGISTERED flag (the save
  is already a documented proxy "mod a few cycles of pipeline latency"). IPC 0.
- **`AluEu s1Ctx.op -> DivEu bf1Packed`** (family #6): should vanish after items 1 and 4; if not,
  capture `bf1*` from DivEu's own registered issue payload only.
- **Frontend** (family #3): `applyNow` (fo 147) -> fetch-PC mux -> ITLB `lookupVpn` (fo 171) ->
  Icache stage 0 in one cycle. A registered fetch-PC stage is the textbook cut but may cost a
  redirect bubble; frontend cones get absorbed post-route, so RANK LAST and A/B it.
- **SoC side** (`macqd700-soc` `rtl/`, NOT this repo): `u_l2c req_id -> tags BRAM -> data URAM
  ADDR_A` at 75-82% route (-0.871) — register the way/URAM address (+1 L2-hit cycle);
  `u_jtag_n2w -> u_xbar` at 79% route — an AXI register slice (keeps ADB/VIO intact).

## Could not verify without Vivado (agent noted)
- The `RobPlugin_logic_payload` 1,815 LUT attribution (see item 8 warning).
- Whether Dcache `dataMem` (128b x 128 sets, `readSync`) is already BRAM; if so a 128->32 way-mux
  narrowing only shrinks the S2 registers.
- Exact bypass-network and cold-Mem LUT counts (items 1, 4) — derived from port/width arithmetic.
- Item 6's hit-case IPC neutrality (depends on the skid being occupancy-registered).

---

## Item 13 — register the L2C array read address (ATTEMPTED 2026-09-15, FAILED, reverted)

**Why it is worth doing.** After items 3+6, the CPU is no longer where the failing
paths are. Violated-path ownership on the post-items-3+6 netlist (60-path sample,
`Default` route): **20 u_ddr, 12 u_l2c, 10 u_pb_s1_cdc, 10 u_scsi** vs only **8 in
u_cpu** (5 IcachePlugin, 2 FetchAlignPlugin, 1 RenameStage) — and NONE in the LSU,
IQ or D-cache. `u_l2c/.../mem_reg_uram_5/ADDR_A[6]` sits at -0.758.
`l2c_ctrl.v:752-758` already measured the mechanism: the `tags_raddr` driver at
fo=64 with **1.332 ns of ROUTE against 0.097 ns of logic** — 24% of the failing
path, pure distance, because the arrays are 100% of this device's URAM and span
the die. `max_fanout = 10` replication helped but cannot shorten a wire.

**What I tried and why it is WRONG.** Inserting one free-running register between
the `tags_raddr` mux and both arrays, then widening the Critical-7 `inst_hist`
window 2 -> 3 deep and the skew re-read `rr_q` 1 -> 2 deep to match the new
read-to-resolve distance. Result: **`L2C_TAGS ASSERT: set 546 tag 0 left valid in
BOTH way 0 and way 1`** (a duplicate-way install — silent data corruption) plus
`same_id_ordering` FAIL ("second completion is B's (hit) data"). Baseline is
65 PASS / 0 FAIL; reverted and re-confirmed 65/0.

**The reason.** `tags_raddr = rr_start_c ? req_set : (do_accept_c ? cur_set : q_set)`
selects on control state valid in the SAME cycle the array is read. Registering the
address alone makes the array read at T+1 with an address chosen from T's control
state, while `q_set`/`cur_set` and the accept decision have already advanced — so
stage 2 resolves against a snapshot that is not its own request's. Widening the
hazard windows does not fix that; it is an alignment break, not a window shortage.

**What the correct fix requires.** A genuine extra PIPELINE STAGE, not a register
insertion: the request tracking that consumes the array output must be delayed by
the same cycle the address is, so the resolve stage still sees the read issued for
its own request. That means carrying {set, way-select, request identity} alongside
the registered address and re-deriving stage 2's view from the delayed copy, then
re-auditing `arr_ce_c` (it clock-enables tq_* and l2c_data's second register and
must not hold the new first stage), `rr_start_c`/`rr_busy_c`, and the
`accept_slot_c` term. Budget it as a pipeline restructure of `l2c_ctrl.v` with the
ten tb-l2c* benches as the gate, not as a one-line timing tweak.

**Test gate (baselines captured 2026-09-15):** tb-l2c 65/0, tb-l2c-chain 4/0,
tb-l2c-mut 65/0, tb-l2c-stress 7/0, tb-l2c-bypass-all 4/0, tb-l2c-bypdepth 65/0.
tb-l2c-sctr prints statistics, no verdict line. The hazard paths ARE exercised:
tb-l2c reports "set-hazard refusals 25981, skew re-reads 450".
