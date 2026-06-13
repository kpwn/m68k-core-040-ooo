# FMax 250-Push: D-cache / DTLB Cross-Module Crossings Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Drive the 68040 OoO core's post-route FMax from ~211 MHz toward 250 MHz (WNS≥0 @ 4.000 ns) by REGISTERING the route-dominated cross-module crossings in the D-cache / DTLB / LS-EU region.

**Architecture:** The post-route limiter is a cluster of cross-module (66-73% route) paths that fan combinationally out of the LS-EU's AGU into the D-cache tag access and out of the D-cache/LS-EU into the DTLB hit/walker cone. The project pattern is to REGISTER the cross-module boundary (+1 latency cycle is latency-agnostic — lock-step is instruction-level, the LS EU is single-outstanding). We attack, in order: (1) the LS-EU AGU→D-cache tag arc (register the computed access address/cmd at the boundary), (2) revive the held `feat/dtlb-req-register` fix (`b16884e`) which registers the LS-EU→DTLB request, and measure them in combination, (3) if the AluEu ROXL/ROXR barrel shifter surfaces as the new limiter, extend its existing `s2Stage1` pipeline.

**Tech Stack:** SpinalHDL (Scala) RTL; Verilator lock-step vs a grafted Musashi 68040 (with software MMU); Vivado post-route synth on xcku5p-ffvb676-2-e via `synth/impl_FullCore.tcl` (the authoritative floorplanned flow).

**WORKTREE NOTE:** All RTL/gen/synth work for this campaign MUST run inside this worktree (`/home/qwertyoruiop/m68k-core-040-ooo/.claude/worktrees/agent-ac627eb2973e20547`, branch `feat/fmax-dcache-dtlb-crossings`). The shared checkout at `/home/qwertyoruiop/m68k-core-040-ooo` is on `master` (`4004bef`) — it was used ONLY to capture the baseline FMax (master == the correct baseline ref). Run gen + vivado from the worktree dir for every FIX so the synth sees the modified RTL. `generated/` and `synth/*.rpt`/`*.log` are per-checkout artifacts.

---

## Operating Rules (read before every task)

- **POST-ROUTE GATE PROTOCOL.** Before launching Vivado: `pgrep -af vivado` FIRST. A FOREIGN `impl-pipe.tcl` job intermittently holds Vivado — WAIT for it (never run two vivados, never Verilator+vivado concurrently). Kill stale orphaned sbt JVMs (memory pressure). Post-route is DETERMINISTIC (same netlist → same FMax) — trust a single run. IGNORE OOC numbers (pessimistic + unfloorplanned); gate on `synth/impl_FullCore.tcl` only.
- **GEN + SYNTH SEQUENCE (from the worktree dir):** `JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt "runMain m68k040.top.GenFullCoreSynthVerilog"` then `vivado -mode batch -nojournal -log synth/vivado_FullCore.log -source synth/impl_FullCore.tcl`. Read the result from the `POSTROUTE_FULLCORE_WNS_NS` / `POSTROUTE_FULLCORE_RESULT` lines and the new worst path from `synth/fullcore_route_timing.rpt`.
- **FLOORPLAN BOX SIZING:** `synth/floorplan_dcache.xdc` pblock `pb_dcache` is a per-cell-count box (currently `SLICE_X36Y110:SLICE_X87Y214`, ~8467 cells captured). If an RTL change GROWS the captured netlist (the printed `FLOORPLAN pb_dcache cells:` count climbs materially), the box congests and regresses — widen ONLY the RIGHT edge (keep left at X36, co-located with pb_decode) to restore density, re-synth, report.
- **LOCK-STEP VALIDATION (correctness-critical — this is the LS/D-cache/DTLB HOT PATH).** After EACH RTL change, lock-step vs Musashi. Each `-z` subset gets its OWN JVM: `JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt '...' 2>&1 | tee /home/qwertyoruiop/tmp/<log>.log | tail -40`. **0 diverged required.** NEVER const-fold the MMU. NEVER weaken/relabel/skip a test to make it pass.
- **NO RECONCILE GAPS:** these fixes add NO new DecodedUop/RenamedUop/LS-ctx fields (they register existing signals), so no µop-builder/ROB/IRQ-poker reconciliation is needed. If that changes, assign the new field in EVERY builder + poker.
- **COMMIT PER FIX** with the measured before→after FMax + the worst path it moved to. Do NOT merge or delete the worktree (the controller reviews + merges).

## Lock-step command menu (the LS / MMU cone)

Run these AFTER each RTL change (own JVM each; pick the subset that exercises the changed cone, run the FULL set before the final commit):

```bash
# LS straight loads/stores + RMW + MOVE-to-mem
JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt 'testOnly *ExecuteLockStepSpec -- -z "RMW" -z "MOVE CCR,(An)" -z "MOVE SR,(An)"' 2>&1 | tee /home/qwertyoruiop/tmp/ls-rmw.log | tail -40
# MOVEM / predec / postinc / indexed / stack push (LEA/PEA/BSR/JSR/RTS)
JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt 'testOnly *ExecuteLockStepSpec -- -z "MOVEM" -z "predec" -z "postinc" -z "indexed" -z "PEA" -z "LEA" -z "BSR" -z "JSR" -z "RTS" -z "LINK"' 2>&1 | tee /home/qwertyoruiop/tmp/ls-ea.log | tail -40
# MMU directed (the -z MMU cone): the DTLB walk / page-fault / ITLB specs (own JVM EACH)
JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt 'testOnly *MmuFaultOracleSpec' 2>&1 | tee /home/qwertyoruiop/tmp/mmu-dtlb.log | tail -40
JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt 'testOnly *ItlbFaultOracleSpec' 2>&1 | tee /home/qwertyoruiop/tmp/mmu-itlb.log | tail -40
# privilege / exception
JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt 'testOnly *ExecuteLockStepSpec -- -z "privilege" -z "IRQ" -z "RTE"' 2>&1 | tee /home/qwertyoruiop/tmp/ls-exc.log | tail -40
# fastTest (the non-verilator unit gate)
JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt fastTest 2>&1 | tee /home/qwertyoruiop/tmp/fasttest.log | tail -40
# FULL lock-step (before the final commit of each fix that touches the data path)
JAVA_OPTS=-Xmx10g timeout 1800 ~/sbt/bin/sbt 'testOnly *ExecuteLockStepSpec' 2>&1 | tee /home/qwertyoruiop/tmp/ls-full.log | tail -60
```

A PASS shows scalatest `All tests passed` / `Tests: succeeded N, failed 0` with **0 diverged**. The lock-step harness PRINTS the divergence (DUT vs oracle state) on any mismatch — grep the log for `diverge`/`MISMATCH`/`failed`.

---

## File Structure

- `src/main/scala/m68k040/execute/LsEuPlugin.scala` — the LS EU (AGU + load/store FSM + SQ + the DTLB request drive). **Modified by Fix #1 and Fix #2.**
- `src/main/scala/m68k040/cache/DcachePlugin.scala` — the L1D (tag/data BRAM, load FSM, store RMW). Read-only reference for Fix #1 (it consumes `loadCmd.{vaddr,paddr}`); not modified.
- `src/main/scala/m68k040/mmu/DtlbPlugin.scala` — the DTLB (`hr*` hit-result regs, `missReqReg` walker-trigger reg). Read-only reference for Fix #2; not modified (Fix #2 lives entirely in LsEuPlugin per `b16884e`).
- `src/main/scala/m68k040/execute/AluEuPlugin.scala` — the ALU EU + barrel shifter (`s1Stage1`/`s2Stage1`). **Modified by Fix #3 ONLY IF the shifter is the gating path.**
- `synth/floorplan_dcache.xdc` — the `pb_dcache` pblock. Re-size only if a fix grows the captured netlist.
- `generated/M68kFullCoreSynth.v` — generated artifact (regenerated each synth).

---

## Task 0: Re-establish the post-route baseline

**Files:** none (measurement only). The baseline ref is `master` (`4004bef`); it can be measured from the shared checkout OR by gen+synth on this worktree before any RTL change (identical RTL → identical netlist → identical FMax, post-route is deterministic).

- [ ] **Step 1: Confirm no foreign Vivado, then gen + baseline synth**

```bash
pgrep -af vivado    # MUST be empty (or a foreign impl-pipe job — WAIT for it)
cd /home/qwertyoruiop/m68k-core-040-ooo/.claude/worktrees/agent-ac627eb2973e20547
JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt "runMain m68k040.top.GenFullCoreSynthVerilog"
vivado -mode batch -nojournal -log synth/vivado_FullCore_baseline.log -source synth/impl_FullCore.tcl 2>&1 | tee /home/qwertyoruiop/tmp/fmax-synth-baseline.log
```

- [ ] **Step 2: Record the baseline WNS / FMax + the worst-path cluster**

```bash
grep -E "POSTROUTE_FULLCORE_WNS_NS|POSTROUTE_FULLCORE_RESULT|FLOORPLAN pb_dcache cells" /home/qwertyoruiop/tmp/fmax-synth-baseline.log
# Read the worst paths (Source -> Dest, logic levels, route %):
sed -n '1,120p' synth/fullcore_route_timing.rpt
```

Expected: a baseline near ~211 MHz (WNS ≈ −0.72). CONFIRM the worst path is the `LsEuPlugin s1Ctx_uop_{eaDelta,eaAuto,stkPush}/C → DcachePlugin tagMem .../D` AGU→tag arc (or its sibling, the D-cache→DTLB cone). Note the exact Source/Dest pins + logic levels + route% — this is the authoritative target. If the worst path is something ELSE, re-prioritize the fixes to attack the actual worst path first (report this).

- [ ] **Step 3: (measurement only — record baseline in the Fix #1 commit message; proceed to Task 1)**

---

## Task 1 (Fix #1): Register the LS-EU AGU address at the D-cache boundary

**The arc:** In `LsEuPlugin.scala` the effective address `s1Va = (s1Base.asSInt + s1Disp + s1Index.asSInt).asUInt` (line ~271) is combinational off the registered base, where `s1Disp` is selected from `u1.stkPush` / `u1.eaAuto` (PREDEC/POSTINC) / `u1.imm` and uses `u1.eaDelta` (line ~261-265). This live AGU sum feeds `loadVaddr := s1Va` (line ~344) → `dcache.loadCmd.payload.vaddr` → DcachePlugin `cmdSet`/`cmdTag` → the `tagMem`/`dataMem` BRAM read-address (`rdSet`, DcachePlugin.scala:122-127, 260-272). That is the `s1Ctx_uop_{eaDelta,eaAuto,stkPush}/C → tagMem/D` route-dominated worst path: the AGU adder + the eaDelta/eaAuto/stkPush mux fan combinationally across the LS-EU→D-cache module boundary into the cache's tag access.

**The fix:** REGISTER the address the cache tags on. The cache must launch its BRAM tag/data read off a REGISTERED command (`loadCmd.vaddr`/`paddr` captured into a flop INSIDE the LS-EU at the boundary), not off the live `s1Va`. The FSM already transitions `RESOLVE → WAIT/WAIT_A` on `dcache.loadCmd.fire`. We add a registered launch stage so `loadCmd` is driven from a flop. Because the EU is single-outstanding and `s1Va` is held stable while busy (s1Base/s1Ctx held), capturing `s1Va` into a launch register one cycle before driving `loadCmd.valid` is behavior-identical (+1 latency, latency-agnostic).

**Approach (minimal-risk):** Add a one-entry "load-launch register" `llReg {valid, vaddr, paddr, size, twoAccess, addrB, paddrB, bDone}` captured from the live `s1Va`/`s2Paddr`/`s1AddrB`/`s2PaddrB` when the FSM resolves "no SQ forward → go to cache", then drive `dcache.loadCmd.{valid,vaddr,paddr,size}` from `llReg` (a flop→port arc, off the AGU cone). Slot B (cross) uses `llReg.addrB`/`llReg.paddrB`, selected by `llReg.bDone`. This severs `s1Va → loadCmd.vaddr → cmdTag/cmdSet → rdSet` into `s1Va → llReg (flop)` and `llReg → loadCmd → cmdSet`.

**Files:**
- Modify: `src/main/scala/m68k040/execute/LsEuPlugin.scala` (the `loadVaddr`/`loadPaddr` drive ~342-349, the FSM RESOLVE/WAIT_A states ~787-855)
- Test: `src/test/scala/m68k040/lockstep/ExecuteLockStepSpec.scala` (existing — no new test; the LS cone is already covered)

- [ ] **Step 1: Read the exact current load-launch drive + FSM**

Re-read `LsEuPlugin.scala` lines 335-355 (the `loadVaddr`/`loadPaddr`/`dcache.loadCmd` defaults) and 685-873 (the FSM, esp. RESOLVE/WAIT/WAIT_A/WAIT_B) so the edit matches the live structure (line numbers may have drifted). Confirm `s1Va`, `s2Paddr`, `s1AddrB`, `s2PaddrB`, `u1.size`, `s1TwoAccess` are the signals the cache launch consumes.

- [ ] **Step 2: Add the load-launch register stage**

After the `s2Paddr`/`s2PaddrB`/`s2Fault` declarations (~line 331-333), add the launch register:

```scala
// ─────────────────────────────────────────────────────────────────────────
// FMax #1: REGISTER the AGU effective address at the D-cache boundary.
//
// `loadCmd.vaddr` was driven LIVE from `s1Va` (= s1Base + s1Disp(eaDelta/eaAuto/
// stkPush) + s1Index). That AGU sum fanned combinationally across the LS-EU ->
// D-cache module boundary into the cache's BRAM tag/data read-address (cmdSet/
// cmdTag -> rdSet) — the route-dominated worst path (s1Ctx_uop_{eaDelta,eaAuto,
// stkPush}/C -> DcachePlugin tagMem/D). CAPTURE the resolved access {vaddr,paddr,
// addrB,paddrB,size,twoAccess} into `llReg` flops the cycle the FSM decides to go
// to the cache, and drive `loadCmd` from THOSE flops the next cycle. The cache now
// tags on a REGISTERED address (flop -> loadCmd -> cmdSet); the AGU adder ends at
// the llReg flop. +1 cache-launch cycle is latency-agnostic (the EU is single-
// outstanding; s1* are held stable while busy). All RegInit/Reg (no uninit fanout).
val llReg = new Area {
  val valid     = RegInit(False)
  val vaddr     = Reg(UInt(32 bits))
  val paddr     = Reg(UInt(32 bits))
  val addrB     = Reg(UInt(32 bits))
  val paddrB    = Reg(UInt(32 bits))
  val size      = Reg(m68k040.isa.Size())
  val twoAccess = RegInit(False)
  val bDone     = RegInit(False)   // slot A launched; now driving slot B (cross)
}
```

- [ ] **Step 3: Drive `loadCmd` from `llReg`, and keep a LIVE `xlateVaddr` for the DTLB request**

Replace the live `loadVaddr := s1Va` / `loadPaddr := s2Paddr` cache-cmd drive (~line 342-349). Drive the cmd payload from `llReg`, selecting slot A vs slot B on `llReg.bDone`:

```scala
// ---- dcache load cmd: driven from the REGISTERED launch stage (llReg) ----
// slot A = llReg.vaddr/paddr; slot B (cross) = llReg.addrB/paddrB (selected once
// slot A is captured, bDone). The cache tags on this REGISTERED address.
val loadVaddr = UInt(32 bits)
val loadPaddr = UInt(32 bits)
loadVaddr := Mux(llReg.bDone, llReg.addrB,  llReg.vaddr)
loadPaddr := Mux(llReg.bDone, llReg.paddrB, llReg.paddr)
dcache.loadCmd.valid         := llReg.valid
dcache.loadCmd.payload.vaddr := loadVaddr
dcache.loadCmd.payload.paddr := loadPaddr
dcache.loadCmd.payload.size  := llReg.size
```

The DTLB request (`xlate.req.vpn`, ~line 396) must track the LIVE access being translated (translation happens in IDLE BEFORE the cache launch), NOT the llReg-registered cmd. Add a live `xlateVaddr` and point the DTLB-vpn drive at it. Insert before the `xlate.req` drive:

```scala
// The DTLB request tracks the LIVE access being translated (in IDLE, before the
// registered cache launch). Off the llReg (cache-launch) register: the translate
// stage runs first and feeds s2Paddr, which llReg later captures. s1AddrB is the
// registered-base-derived next-line base (a shallow stage off s1Va), so this does
// NOT re-introduce the eaDelta->tag arc onto the cache cmd (the cache cmd reads llReg).
val xlateVaddr = Mux(llReg.bDone, s1AddrB, s1Va)
```

Change `xlate.req.vpn := loadVaddr(31 downto 12)` to `xlate.req.vpn := xlateVaddr(31 downto 12)`. (If Fix #2 is already applied, instead set `reqDrvVpn := xlateVaddr(31 downto 12)` — see Task 2.)

- [ ] **Step 4: Capture `llReg` in the FSM and add a LAUNCH state**

Add a `LAUNCH` state (next to the others, ~line 685-693):

```scala
val LAUNCH = new State  // registered cache-launch: drive loadCmd off llReg
```

Replace the RESOLVE "otherwise" (no-forward) arm (~line 801-809) with a capture-and-go:

```scala
} otherwise {
  // no forward: CAPTURE the resolved access into the launch register and launch
  // the cache off the flop next cycle (severs s1Va -> loadCmd.vaddr -> cmdTag).
  llReg.valid     := True
  llReg.vaddr     := s1Va
  llReg.paddr     := s2Paddr
  llReg.addrB     := s1AddrB
  llReg.paddrB    := s2PaddrB
  llReg.size      := u1.size
  llReg.twoAccess := s1TwoAccess
  llReg.bDone     := False
  goto(LAUNCH)
}
```

Add the LAUNCH state body (after RESOLVE):

```scala
// Registered cache launch: loadCmd is driven from llReg (the flop) — the cache
// tags on a REGISTERED address. Hold until the cache accepts (loadCmd.fire), then
// go to WAIT (aligned) / WAIT_A (cross). loadCmd.valid := llReg.valid is driven
// above (outside the FSM).
LAUNCH.whenIsActive {
  busy := True
  when(dcache.loadCmd.fire) {
    when(llReg.twoAccess) { aDone := False; goto(WAIT_A) }
    otherwise { llReg.valid := False; goto(WAIT) }
  }
}
```

Update WAIT_A's slot-B launch (~line 829-842) to flip `llReg.bDone` (so the registered cmd selects addrB) instead of driving live `loadVaddr`/`loadPaddr`:

```scala
WAIT_A.whenIsActive {
  busy := True
  when(dcache.loadRsp.valid && !aDone) {
    lineA := dcache.loadRsp.payload.line
    aDone := True
  }
  when(dcache.loadRsp.valid || aDone) {
    // slot A done -> present slot B's REGISTERED address (llReg.bDone selects
    // addrB/paddrB in the loadCmd drive above). loadCmd.valid stays asserted
    // (llReg.valid still True) until the cache accepts slot B.
    llReg.bDone := True
    when(dcache.loadCmd.fire) { llReg.valid := False; goto(WAIT_B) }
  }
}
```

Ensure `llReg.valid := False` on every completion path that leaves the cache stages — add it alongside the existing `s1Valid := False` in the WAIT and WAIT_B `captureCompletion` arms (so a completed load drops the launch valid; the LAUNCH/WAIT_A `goto(WAIT)`/`goto(WAIT_B)` arms already clear it inline above).

- [ ] **Step 5: Re-read the whole edited FSM for consistency**

Read `LsEuPlugin.scala` 680-880. Verify: (a) `dcache.loadCmd.valid` is driven ONLY from `llReg.valid` (no leftover live `dcache.loadCmd.valid := True` in RESOLVE/WAIT_A), (b) `llReg.valid` clears on WAIT and WAIT_B completion, (c) the exc-arbitration MUX at the end (`when(excActive && excLoadCmdValid)`) still OVERRIDES `dcache.loadCmd` last-wins (it drives `loadCmd.vaddr/paddr` directly — unchanged, unaffected by llReg), (d) `xlate.req.vpn` reads `xlateVaddr` (live), not `loadVaddr` (registered).

- [ ] **Step 6: Generate the verilog (compile check)**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo/.claude/worktrees/agent-ac627eb2973e20547
JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt "runMain m68k040.top.GenFullCoreSynthVerilog" 2>&1 | tee /home/qwertyoruiop/tmp/fmax-gen-fix1.log | tail -8
```
Expected: `Generated generated/M68kFullCoreSynth.v`, no elaboration error, NO "UNASSIGNED REGISTER" / no new latch.

- [ ] **Step 7: Lock-step the LS / MMU cone (correctness gate)**

Run the full lock-step + MMU/ITLB oracle + fastTest (each own JVM):

```bash
JAVA_OPTS=-Xmx10g timeout 1800 ~/sbt/bin/sbt 'testOnly *ExecuteLockStepSpec' 2>&1 | tee /home/qwertyoruiop/tmp/fix1-ls-full.log | tail -60
JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt 'testOnly *MmuFaultOracleSpec' 2>&1 | tee /home/qwertyoruiop/tmp/fix1-mmu.log | tail -30
JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt 'testOnly *ItlbFaultOracleSpec' 2>&1 | tee /home/qwertyoruiop/tmp/fix1-itlb.log | tail -30
JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt fastTest 2>&1 | tee /home/qwertyoruiop/tmp/fix1-fast.log | tail -30
```
Expected: **0 diverged**, all pass. If ANY diverges: STOP. Do NOT relabel/weaken the test. Debug via superpowers:systematic-debugging (most likely: a stale `llReg.bDone` not cleared, or `loadCmd.valid` lingering — re-check the clear-paths).

- [ ] **Step 8: POST-ROUTE GATE (the FMax measurement)**

```bash
pgrep -af vivado    # MUST be clear
vivado -mode batch -nojournal -log synth/vivado_FullCore_fix1.log -source synth/impl_FullCore.tcl 2>&1 | tee /home/qwertyoruiop/tmp/fmax-synth-fix1.log
grep -E "POSTROUTE_FULLCORE_WNS_NS|POSTROUTE_FULLCORE_RESULT|FLOORPLAN pb_dcache cells" /home/qwertyoruiop/tmp/fmax-synth-fix1.log
sed -n '1,120p' synth/fullcore_route_timing.rpt   # the NEW worst path
```
Record before→after WNS/FMax + the NEW worst path. If the captured `pb_dcache` cell count grew materially and FMax regressed vs the structural expectation, re-size `floorplan_dcache.xdc` (widen right edge only), re-synth, report both numbers.

- [ ] **Step 9: Commit Fix #1**

```bash
git add src/main/scala/m68k040/execute/LsEuPlugin.scala
git commit -m "$(cat <<'EOF'
fmax 1: register the AGU effective address at the D-cache boundary

Sever the route-dominated worst path LsEu s1Ctx_uop eaDelta/eaAuto/stkPush -> Dcache
tagMem: the live AGU sum (s1Base+s1Disp+s1Index) fed loadCmd.vaddr -> cmdSet/cmdTag ->
the BRAM read-address combinationally across the module boundary. Capture the resolved
access into llReg flops in a LAUNCH stage and drive loadCmd from those flops; the cache
now tags on a REGISTERED address. +1 cache-launch cycle is latency-agnostic (EU single-
outstanding, s1 held). The DTLB request keeps a live xlateVaddr (translation precedes
the launch).

post-route: BEFORE -> AFTER (WNS/FMax); new worst path SRC -> DST.
lock-step: full ExecuteLockStep + MMU/ITLB oracle + fastTest, 0 diverged.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2 (Fix #2): Revive the DTLB-request register (`b16884e`)

**Context:** `feat/dtlb-req-register` (commit `b16884e`) registers the LS-EU→DTLB translation request interface (`reqReg`/`reqMatch`), severing the cross-module cone `Dcache valids/ldS1Set → LsEu → xlate.req.vpn/.valid → DTLB hitVec → hr*/missReqReg CE`. It was functionally GREEN (lock-step 0-diverged) but regressed SOLO (218.9 MHz) by exposing the AluEu shifter. The task spec hypothesis: it COMPOUNDS with Fix #1 (same cone family). We re-apply `b16884e`'s LsEuPlugin changes onto this branch (on top of Fix #1) and re-measure in combination.

**Files:**
- Modify: `src/main/scala/m68k040/execute/LsEuPlugin.scala` (the `xlate.req` drive → `reqDrv*`/`reqReg`/`reqMatch`, the IDLE consume gate, the exc-arb override)

- [ ] **Step 1: Inspect the `b16884e` diff (already studied — re-confirm against the Fix #1-modified file)**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo/.claude/worktrees/agent-ac627eb2973e20547
git show b16884e -- src/main/scala/m68k040/execute/LsEuPlugin.scala | tee /home/qwertyoruiop/tmp/b16884e.diff
```
The diff replaces the `xlate.req.valid/.vpn/.supervisor/.write` + `xlateRobIdSig` base drive with `reqDrv*` combinational nets + a `reqReg` flop area + `reqMatch`; gates the IDLE consume on `xlateReady && reqMatch`; moves the exc-arb override + the final `xlate.req`/`xlateRobIdSig` drive to AFTER the exc MUX (last-wins).

- [ ] **Step 2: Try a cherry-pick; if it conflicts, re-apply by hand**

```bash
git cherry-pick -n b16884e 2>&1 | tee /home/qwertyoruiop/tmp/fix2-cherry.log
git status --short
```
If clean-staged with no conflict markers, jump to Step 4. If it CONFLICTS (likely — Fix #1 edited the `loadVaddr`/`xlate.req` region), `git cherry-pick --abort` and re-apply by hand per Step 3.

- [ ] **Step 3 (if conflict): Re-apply `b16884e`'s four changes by hand**

In `LsEuPlugin.scala`:

(a) Replace the `xlate.req.*` / `xlateRobIdSig` base drive with combinational nets + reqMatch (KEEP Fix #1's `xlateVaddr` as the VPN source):

```scala
val reqDrvValid = Bool()
val reqDrvVpn   = UInt(20 bits)
val reqDrvSup   = Bool()
val reqDrvWrite = Bool()
val reqDrvRobId = UInt(6 bits)
reqDrvValid := s1Valid && (isLoad || isStore)
reqDrvVpn   := xlateVaddr(31 downto 12)   // Fix #1's live access VPN (NOT the llReg cmd)
reqDrvSup   := False
reqDrvWrite := isStore
reqDrvRobId := s1Ctx.robId

val reqReg = new Area {
  val valid = RegInit(False)
  val vpn   = Reg(UInt(20 bits))
  val sup   = Reg(Bool())
  val write = Reg(Bool())
  val robId = Reg(UInt(6 bits))
}
val reqMatch = reqReg.valid && (reqReg.vpn === xlateVaddr(31 downto 12)) &&
               (reqReg.write === isStore)
```

(b) Gate the IDLE translate-consume: the `when(xlateReady)` in the IDLE `isLoad || isStore` arm becomes `when(xlateReady && reqMatch)`.

(c) In the exc-arb override block, replace the direct `xlate.req.*` writes with `reqDrv*` writes (last-wins on the combinational nets):

```scala
when(excActive && excXlateValid) {
  reqDrvValid := True
  reqDrvVpn   := excXlateVpn
  reqDrvWrite := excXlateWrite
  reqDrvSup   := excXlateSupervisor
}
```

(d) At the very end of `logic` (after the exc MUX), register the nets and drive `xlate.req` once:

```scala
reqReg.valid := reqDrvValid
reqReg.vpn   := reqDrvVpn
reqReg.sup   := reqDrvSup
reqReg.write := reqDrvWrite
reqReg.robId := reqDrvRobId
xlate.req.valid      := reqReg.valid
xlate.req.vpn        := reqReg.vpn
xlate.req.supervisor := reqReg.sup
xlate.req.write      := reqReg.write
xlateRobIdSig        := reqReg.robId
```

IMPORTANT (interaction with Fix #1): `reqDrvVpn` AND `reqMatch` must use the LIVE `xlateVaddr`, NOT the llReg-registered `loadVaddr`. Double-check both.

- [ ] **Step 4: Generate the verilog (compile check)**

```bash
JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt "runMain m68k040.top.GenFullCoreSynthVerilog" 2>&1 | tee /home/qwertyoruiop/tmp/fmax-gen-fix2.log | tail -8
```
Expected: `Generated ...`, no error, no new latch / UNASSIGNED REGISTER.

- [ ] **Step 5: Lock-step (correctness — DTLB walk is the +1 risk; MMU FIRST)**

```bash
JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt 'testOnly *MmuFaultOracleSpec' 2>&1 | tee /home/qwertyoruiop/tmp/fix2-mmu.log | tail -30
JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt 'testOnly *ItlbFaultOracleSpec' 2>&1 | tee /home/qwertyoruiop/tmp/fix2-itlb.log | tail -30
JAVA_OPTS=-Xmx10g timeout 1800 ~/sbt/bin/sbt 'testOnly *ExecuteLockStepSpec' 2>&1 | tee /home/qwertyoruiop/tmp/fix2-ls-full.log | tail -60
JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt fastTest 2>&1 | tee /home/qwertyoruiop/tmp/fix2-fast.log | tail -30
```
Expected: **0 diverged**. If the MMU oracle diverges (a DTLB-miss walk that now needs an extra cycle to present its request), check `reqMatch` gates the consume correctly (a stale rsp must not be consumed). Do NOT const-fold the MMU.

- [ ] **Step 6: POST-ROUTE GATE (measure Fix #1 + Fix #2 COMBINED)**

```bash
pgrep -af vivado    # clear
vivado -mode batch -nojournal -log synth/vivado_FullCore_fix2.log -source synth/impl_FullCore.tcl 2>&1 | tee /home/qwertyoruiop/tmp/fmax-synth-fix2.log
grep -E "POSTROUTE_FULLCORE_WNS_NS|POSTROUTE_FULLCORE_RESULT|FLOORPLAN pb_dcache cells" /home/qwertyoruiop/tmp/fmax-synth-fix2.log
sed -n '1,120p' synth/fullcore_route_timing.rpt
```
Record the COMBINED Fix #1→Fix #2 WNS/FMax + the new worst path. Hypothesis: with BOTH the AGU→tag arc (Fix #1) AND the D-cache→DTLB cone (Fix #2) registered, the worst path migrates to the AluEu shifter (or another module). If Fix #2 REGRESSES the combined number, that's a valid finding — decide whether to KEEP (commit) or REVERT it.

- [ ] **Step 7: Commit Fix #2 (if net-positive or neutral-and-cone-removed)**

```bash
git add src/main/scala/m68k040/execute/LsEuPlugin.scala
git commit -m "$(cat <<'EOF'
fmax 2: register the LS-EU -> DTLB translation request (revive b16884e)

Re-apply the held dtlb-req-register fix on top of fmax 1: drive the DTLB req from a
1-entry register stage (reqReg/reqMatch) so the cross-module cone Dcache valids/ldS1Set
-> LsEu -> xlate.req.vpn/.valid -> DTLB hitVec -> hr*/missReqReg/CE now starts at a flop
inside the DTLB pblock. reqMatch gates the IDLE consume against the LIVE xlateVaddr (fmax
1 live access VPN), mirroring the single-outstanding DTLB-miss stall. +1 translate cycle
is latency-agnostic. MMU bit-exact (no const-fold); identity flows the same register.

post-route (combined w/ fmax 1): Fix1 -> Fix2 (WNS/FMax); new worst path.
lock-step: full ExecuteLockStep + MMU/ITLB oracle + fastTest, 0 diverged.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

If Fix #2 is NET-NEGATIVE in combination (regresses below Fix #1 alone), do NOT commit — `git checkout src/main/scala/m68k040/execute/LsEuPlugin.scala` to drop it, and report Fix #1 as the banked gain + the next limiter. Proceed to Task 3 based on the new worst path either way.

---

## Task 3 (Fix #3, CONDITIONAL): Pipeline the AluEu ROXL/ROXR barrel shifter

**ONLY do this task if, after Fix #1 (+ Fix #2), the post-route worst path is the `AluEuPlugin s1Src2 → s2Stage1 roxr/roxl` barrel shifter** (per the worst-path read in Task 2 Step 6 / Task 1 Step 8). If the worst path is elsewhere (back in the D-cache cone, or a front-end path), STOP and report the new limiter — do not speculatively pipeline the shifter.

**Context:** `AluEuPlugin.scala` already splits the shifter across S1→S2: `s1Stage1 = Shifter.stage1(shiftCmd)` (the deep variable-shift networks), `s2Stage1 = RegNext(s1Stage1)`, `s2Rsp = Shifter.stage2(s2Stage1)` (lines ~314-333). The limiter `s1Src2 → s2Stage1` means the `Shifter.stage1` combinational cone (from the registered `s1Src2` into the `s2Stage1` flop) is too deep. The fix: rebalance stage1/stage2, or add a THIRD register so each half is shallower.

**Files:**
- Modify: `src/main/scala/m68k040/execute/AluEuPlugin.scala` (the `s1Stage1`/`s2Stage1`/`s2Rsp` shift pipeline ~309-342) and possibly the `Shifter` object (locate it).

- [ ] **Step 1: Locate the Shifter object and read stage1/stage2**

```bash
grep -rn "object Shifter\|def stage1\|def stage2\|case class ShiftCmd\|case class.*Stage1" src/main/scala/m68k040 | tee /home/qwertyoruiop/tmp/shifter-loc.log
```
Read the `Shifter.stage1` / `stage2` definitions. Determine WHERE the depth is: if stage1 does the full barrel rotate and stage2 only assembles flags, rebalance (move the upper log-shift level into stage2). If both are already balanced, add a third stage register at a clean log-shift-level boundary.

- [ ] **Step 2: Read the AluEu shift pipeline + confirm the wakeup latency accounting**

Read `AluEuPlugin.scala` 295-365. The shifter is the lat-2 SLOW path with a dynamic `slowWakeup` (lines ~17, ~361). Confirm whether the wakeup is DYNAMIC (broadcast when the result is ready, like LsEu — then +1 stage is latency-agnostic, NO constant change) or a STATIC scoreboard latency (then bump the constant by 1):

```bash
grep -rn "slowWait\|aluSlow\|lat2\|latency = 2\|SlowLat\|slowWakeup" src/main/scala/m68k040/execute | tee /home/qwertyoruiop/tmp/shift-lat.log
```

- [ ] **Step 3: TDD baseline, then add the rebalance / third stage**

```bash
JAVA_OPTS=-Xmx10g timeout 600 ~/sbt/bin/sbt 'testOnly *ShifterSpec' 2>&1 | tee /home/qwertyoruiop/tmp/fix3-shifter-base.log | tail -20
```
Then apply the split. Preferred (keep `Shifter` combinational, insert a flop in AluEuPlugin) if `Shifter` exposes a clean mid-point; OTHERWISE introduce a `stage1b`/`stage2` split in the `Shifter` object, cutting at a clean log-shift-level boundary (move ~half of stage2's network into stage1b, register between). Implement the ACTUAL split from what Step 1 reveals — read the Shifter source and cut it at a real boundary; do NOT leave a placeholder. Example shape (exact split per Step 1):

```scala
val s1Stage1 = Shifter.stage1(shiftCmd)
val s2Stage1 = RegNext(s1Stage1)
// FMax #3: the Shifter.stage1 cone (s1Src2 -> s2Stage1) is the limiter once the
// D-cache/DTLB crossings are registered. Split the network across one more flop so
// each half of the shift network is shallower.
val s2bStage = RegNext(Shifter.stage2a(s2Stage1))   // <- the real mid-split from Step 1
val s2Rsp    = Shifter.stage2b(s2bStage)
```

- [ ] **Step 4: Shifter unit test + lock-step the shift cone**

```bash
JAVA_OPTS=-Xmx10g timeout 600 ~/sbt/bin/sbt 'testOnly *ShifterSpec' 2>&1 | tee /home/qwertyoruiop/tmp/fix3-shifter.log | tail -20
JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt 'testOnly *ExecuteLockStepSpec -- -z "ASL" -z "ASR" -z "LSL" -z "LSR" -z "ROXL" -z "ROXR" -z "ROL" -z "ROR" -z "SHIFT" -z "register count"' 2>&1 | tee /home/qwertyoruiop/tmp/fix3-ls-shift.log | tail -40
```
Expected: ShifterSpec 0 fail, shift lock-step 0 diverged. The dependent-chain-through-shift tests (lines ~1206-1238) exercise the lat — confirm they still pass with the deeper pipe.

- [ ] **Step 5: Full lock-step + fastTest**

```bash
JAVA_OPTS=-Xmx10g timeout 1800 ~/sbt/bin/sbt 'testOnly *ExecuteLockStepSpec' 2>&1 | tee /home/qwertyoruiop/tmp/fix3-ls-full.log | tail -60
JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt fastTest 2>&1 | tee /home/qwertyoruiop/tmp/fix3-fast.log | tail -30
```
Expected: 0 diverged, all pass.

- [ ] **Step 6: POST-ROUTE GATE**

```bash
pgrep -af vivado    # clear
JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt "runMain m68k040.top.GenFullCoreSynthVerilog"
vivado -mode batch -nojournal -log synth/vivado_FullCore_fix3.log -source synth/impl_FullCore.tcl 2>&1 | tee /home/qwertyoruiop/tmp/fmax-synth-fix3.log
grep -E "POSTROUTE_FULLCORE_WNS_NS|POSTROUTE_FULLCORE_RESULT" /home/qwertyoruiop/tmp/fmax-synth-fix3.log
sed -n '1,120p' synth/fullcore_route_timing.rpt
```
Record before→after + the new worst path. If WNS≥0 (250 met), DONE. Else report the remaining limiter.

- [ ] **Step 7: Commit Fix #3**

```bash
git add src/main/scala/m68k040/execute/AluEuPlugin.scala  # + Shifter source if split there
git commit -m "$(cat <<'EOF'
fmax 3: deepen the AluEu ROXL/ROXR barrel-shifter pipeline

Once the D-cache/DTLB crossings are registered (fmax 1/2), the limiter surfaced as
AluEu s1Src2 -> s2Stage1 (the Shifter.stage1 cone). Split the shift network across one
more flop so each half is shallower. The shift EU is the dynamic-wakeup SLOW path so +1
stage is latency-agnostic (no static scoreboard constant change).

post-route: BEFORE -> AFTER (WNS/FMax); remaining worst path.
ShifterSpec + full ExecuteLockStep + fastTest, 0 diverged.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: Final report (iterate-or-stop decision)

**Files:** none (report only).

- [ ] **Step 1: Decide iterate-or-stop**

After Fix #3 (or after Fix #2 if the shifter wasn't the limiter), read the latest worst path. IF WNS≥0 (250 met) → DONE. IF the worst path is a NEW route-dominated cross-module crossing in the LS/D-cache/DTLB family with clear headroom → ITERATE (register it, lock-step, post-route — same pattern). IF the worst path needs a deeper restructure (logic-bound not route-bound) or returns are diminishing → STOP and bank the gain.

- [ ] **Step 2: Write the campaign report (as the final agent message, NOT a file)**

Report: the plan SHA; the FMax progression (each fix: before→after + the worst path it moved to); which crossings were registered (+ latency/correctness note); lock-step results (0 diverged across the LS/MMU cone — name the specs); fastTest; the final post-route FMax + the remaining limiter; the final SHA; what's left to reach 250.

---

## Self-Review

**Spec coverage:**
- "re-establish the baseline + confirm worst-path cluster" → Task 0. ✓
- "target the current worst (AGU eaDelta/eaAuto → tagMem); register the computed address at the LS-EU→D-cache boundary" → Task 1 (llReg launch register). ✓
- "the D-cache→DTLB cone — revive/rebase b16884e, re-measure in combination" → Task 2 (cherry-pick or hand-apply, combined post-route). ✓
- "if the AluEu ROXL/ROXR shifter becomes the limiter, pipeline it (extend s2Stage1) — only if gating" → Task 3 (CONDITIONAL on worst-path read). ✓
- "iterate fix→lock-step→post-route→read worst path until WNS≥0 or diminishing returns" → Task 4 decision. ✓
- "validate after EACH change: load/store/RMW, MOVEM, predec/postinc, indexed, MMU directed (-z MMU), privilege/exception, fastTest; 0 diverged; never weaken a test" → every task's lock-step step + the command menu. ✓
- "post-route gate protocol (pgrep vivado first, impl_FullCore.tcl, floorplan box sizing)" → Operating Rules + every gate step. ✓
- "commit per fix with before/after FMax + worst path; don't merge/delete worktree" → each commit step + Operating Rules. ✓

**Placeholder scan:** Task 3 Step 3 intentionally defers the EXACT shift-split point to "what Step 1 finds" — honest (the split depends on the real `Shifter` structure, which Step 1 reads) and explicitly forbids leaving a code placeholder. Task 1 Step 3's slot-B condition is resolved to `Mux(llReg.bDone, s1AddrB, s1Va)`. No TBD/TODO/"add error handling" placeholders remain.

**Type consistency:** `llReg` fields (valid/vaddr/paddr/addrB/paddrB/size/twoAccess/bDone) used consistently in Task 1 Steps 2/3/4. `xlateVaddr` introduced in Task 1 Step 3, consumed by Task 2 Step 3 (`reqDrvVpn`/`reqMatch`). `reqReg`/`reqDrv*`/`reqMatch` match the `b16884e` diff names exactly. `s2Stage1`/`s1Stage1`/`s2Rsp` match the live AluEuPlugin. ✓
