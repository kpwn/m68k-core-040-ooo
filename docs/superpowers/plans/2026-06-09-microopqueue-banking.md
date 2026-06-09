# MicroOpQueue Ring Banking Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bank the `MicroOpQueue` flop ring by `addr mod 4` so the 4-wide compacted barrel write collapses from a 16×(4:1×197b) crossbar into one `tail[1:0]` rotate + per-bank row-enables (and reads factor 16:1→4:1∘4:1), cutting the ring's post-route congestion hotspot — flop-only, contract-preserving.

**Architecture:** Replace `ring = Vec.fill(16)(Reg)` with `bankRegs = Seq.fill(4)(Vec.fill(4)(Reg))` (same flops, partitioned by `addr[1:0]`). The compacted write becomes a single `tail[1:0]` rotate of the 4 push µops into the 4 banks + a per-bank 1-of-4 row write-enable; the 2 read ports factor into per-bank row-reads + a 4:1 bank-select. `head`/`tail`/`count`/`freeSlots`/flush logic is unchanged.

**Tech Stack:** SpinalHDL 1.14.1 / Scala 2.13 / sbt at `~/sbt/bin/sbt` (NOT on PATH) / Verilator / Vivado 2025.2 (`xcku5p-ffvb676-2-e`, OOC 4 ns). Lock-step vs `m68k040.oracle.Musashi`.

**Working dir:** isolated git worktree on `feat/microopqueue-banking` off current `master` (`65b2023`). Run all `sbt`/`vivado` from the worktree root.

**Memory discipline (HARD):** NEVER run Verilator and Vivado concurrently. NEVER run the whole `ExecuteLockStepSpec` (OOMs 12 GB) — `-z "<substr>"` subsets, `JAVA_OPTS=-Xmx10g`. NEVER launch a background `sbt` without a `timeout` wrapper; run each heavy gate in its OWN JVM (do NOT batch fastTest + lock-step + IPC in one). Before any vivado run, `pgrep -af vivado`; only kill `040`/`FullCore` jobs (spare any `030`). Output to a file (grep|tail loses everything on kill).

---

## File Structure

- `src/main/scala/m68k040/decode/MicroOpQueue.scala` — **the only RTL change.** Replace the flat `ring` Vec + compacted write + flat reads with the 4-bank layout + rotate-write + factored reads. The `io` bundle, `head`/`tail`/`count`/`freeSlots`/`pushN`/`popN`/flush are unchanged.
- `src/test/scala/m68k040/decode/MicroOpQueueSpec.scala` — **modify:** add a randomized push/pop/flush stress test vs a Scala reference FIFO (the banking de-risk).

Current `MicroOpQueue` (for reference): `depth=16`, `ptrW=4`, `countW=5`. `ring = Vec.fill(depth)(RegInit(DecodedUop zero))`; `head`/`tail`/`count = RegInit(0)`; `freeSlots = depth-count`; `io.push.ready = freeSlots>=4`; `pushFire = push.valid && push.ready`; `pushN = pushFire ? push.count : 0`; reads `ring(head)`/`ring((head+1).resized)`; `popN = pop.fire ? (count>1?2:1) : 0`; compacted write `when(pushFire){ for v in 0..3: when(push.count>v){ ring((tail+v).resized) := push.uops(v) }; tail := tail+pushN }`; `head := head+popN` on pop.fire; `count := count+pushN-popN`; flush resets head/tail/count.

---

## Task 1: Bank the ring storage + rotate-write + factored reads

**Files:**
- Modify: `src/main/scala/m68k040/decode/MicroOpQueue.scala`

Constants to add at the top of the class body (after `ptrW`/`countW`): `val banks = 4`, `val rows = depth / banks` (=4 for depth 16), `require(depth % banks == 0)`, `val rowW = log2Up(rows)` (=2). Bank index = `addr(1 downto 0)`, row index = `addr(ptrW-1 downto 2)`.

- [ ] **Step 1: Replace the flat ring with banked storage**

Replace `val ring = Vec.fill(depth)(RegInit({...}))` (lines ~42-44) with:

```scala
    // Banked flop ring: bank = addr[1:0], row = addr[3:2]. SAME 16x197b flops, partitioned
    // so the 4-wide compacted write is a single tail[1:0] rotate into 4 banks (4 wide muxes)
    // instead of a 16-wide 4:1 crossbar — cuts the ring congestion hotspot. NOT a RAM.
    val banks = 4
    require(depth % banks == 0, "MicroOpQueue depth must be a multiple of banks")
    val rows  = depth / banks
    val rowW  = log2Up(rows)
    def bankOf(addr: UInt): UInt = addr(1 downto 0)
    def rowOf(addr: UInt):  UInt = addr(ptrW - 1 downto 2)
    val bankRegs = Seq.fill(banks)(Vec.fill(rows)(RegInit({
      val u = DecodedUop(); u.assignFromBits(B(0, widthOf(DecodedUop()) bits)); u
    })))
```

- [ ] **Step 2: Rewrite the compacted write as a `tail[1:0]` rotate into the banks**

Replace the write block (current lines ~64-72, `when(pushFire){ for v ... ring(tail+v) := uops(v); tail := ... }`) with:

```scala
    // ── Compacted multi-push via a single tail[1:0] ROTATE into the 4 banks ──────
    // The v-th valid µop targets addr=tail+v; for v=0..3 those hit the 4 distinct banks
    // (tail+v) mod 4 = a rotation by tail[1:0]. So bank b receives uop index
    // vForBank(b) = (b - tail) mod 4, written iff vForBank(b) < count, at row (tail+v)[3:2].
    val tailLo = tail(1 downto 0)
    for (b <- 0 until banks) {
      // vForBank: which push slot lands in bank b this cycle (0..3).
      val vForBank = (U(b, 2 bits) - tailLo)            // mod-4 by 2-bit wrap
      val addrB    = (tail + vForBank.resize(ptrW)).resized   // = tail + v (the ring addr in bank b)
      val writeEnB = pushFire && (vForBank < io.push.count.resize(2) ||
                                  io.push.count >= U(banks, 3 bits))   // count>=4 -> all 4 banks written
      when(writeEnB) {
        bankRegs(b)(rowOf(addrB)) := io.push.uops(vForBank)
      }
    }
    when(pushFire) {
      tail := (tail + pushN.resize(ptrW)).resized
    }
```

NOTE on the enable: `vForBank` is the 2-bit (mod-4) push-slot index for bank b. It is written iff `vForBank < count`. Because `count` is 0..4 and `vForBank` is 0..3, express it as `vForBank < count` for count≤3 and "all banks" for count==4 — the `|| count>=4` term covers the count==4 case where every `vForBank ∈ {0,1,2,3}` is `< 4`. (Equivalently: `writeEnB = pushFire && (io.push.count > vForBank.resize(3))`, using a 3-bit compare so count==4 > all vForBank — prefer this simpler single form.)

Use the simpler single form:

```scala
      val writeEnB = pushFire && (io.push.count > vForBank.resize(3))
```

- [ ] **Step 3: Rewrite the 2 read ports as per-bank row-reads + bank-select**

Replace the read assignments (current lines ~58-61, `io.pop.payload(0) := ring(head)` / `payload(1) := ring((head+1).resized)`) with:

```scala
    // ── Pop: read head and head+1 via per-bank row-read + 4:1 bank-select ────────
    val head1     = (head + 1).resized
    val headRow   = rowOf(head)
    val head1Row  = rowOf(head1)
    // Per-bank value at the head row / head+1 row, then select the bank.
    val atHeadRow  = Vec((0 until banks).map(b => bankRegs(b)(headRow)))
    val atHead1Row = Vec((0 until banks).map(b => bankRegs(b)(head1Row)))
    io.pop.payload(0) := atHeadRow(bankOf(head))
    io.pop.payload(1) := atHead1Row(bankOf(head1))
```

Leave `io.pop.valid := count > 0`, `io.pop1Valid := count > 1`, `popN`, `head := (head+popN).resized` on `io.pop.fire`, `count := (count+pushN-popN).resized`, `freeSlots`/`io.push.ready`/`pushFire`/`pushN`, and the flush block (`when(io.flush){ head:=0; tail:=0; count:=0 }`) UNCHANGED.

- [ ] **Step 4: Compile**

Run: `cd <worktree> && ~/sbt/bin/sbt compile`
Expected: success, no NO-DRIVER / latch / width errors. (`vForBank` is `UInt(2 bits)` wrapping mod-4; `rowOf` is `ptrW-1 downto 2` = 2 bits.)

- [ ] **Step 5: Add a randomized push/pop/flush stress test (the banking de-risk)**

In `MicroOpQueueSpec.scala`, add a test driving random push counts (0–4, gated by `push.ready`), random `pop.ready`, and occasional flush over ~400 cycles, comparing popped `dstReg` tags against a Scala reference FIFO. This exercises every `tail mod 4` / `head mod 4` alignment + wrap (where a banking bug surfaces):

```scala
  test("banking: randomized push/pop/flush preserves FIFO order vs reference model", VerilatorTest) {
    M68kSim().withVerilator.compile(mk).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      val rnd = new scala.util.Random(0x6809)
      dut.io.flush #= false; idle(dut); dut.io.pop.ready #= false
      cd.waitSampling()
      val model = scala.collection.mutable.Queue[Int]()   // reference FIFO of dstReg tags
      var tag = 0
      var cycles = 0
      while (cycles < 400) {
        // Decide push (only if ready) and pop-ready for THIS cycle.
        val canPush = dut.io.push.ready.toBoolean
        val nPush = if (canPush && rnd.nextInt(3) != 0) rnd.nextInt(5) else 0  // 0..4
        if (nPush > 0) pushBurst(dut, tag, nPush) else idle(dut)
        val popReady = rnd.nextInt(3) != 0
        dut.io.pop.ready #= popReady
        val doFlush = rnd.nextInt(40) == 0
        dut.io.flush #= doFlush
        sleep(1)
        // Sample the pop that fires THIS cycle (pop.valid && pop.ready), unless flushing.
        val popped = scala.collection.mutable.ArrayBuffer[Int]()
        if (!doFlush && dut.io.pop.valid.toBoolean && popReady) {
          popped += dut.io.pop.payload(0).dstReg.toInt
          if (dut.io.pop1Valid.toBoolean) popped += dut.io.pop.payload(1).dstReg.toInt
        }
        cd.waitSampling()
        // Apply the SAME effects to the reference model (push enqueues tags, pop dequeues).
        if (nPush > 0) { for (i <- 0 until nPush) model.enqueue((tag + i) & 0x1f); tag += nPush }
        for (p <- popped) {
          assert(model.nonEmpty, s"DUT popped $p with empty model @cycle $cycles")
          val exp = model.dequeue()
          assert(p == exp, s"FIFO order: DUT popped $p, model expected $exp @cycle $cycles")
        }
        if (doFlush) model.clear()
        cycles += 1
      }
    }
  }
```

(Match the masking: `pushBurst` tags are `(base+i) & 0x1f`, so the model uses the same mask. The `model.clear()` on flush mirrors the pointer reset.)

- [ ] **Step 6: Run the queue spec (existing + new)**

Run: `~/sbt/bin/sbt 'testOnly m68k040.decode.MicroOpQueueSpec'`
Expected: all 3 PASS — FIFO-order, flush, and the new randomized stress. A mismatch in the stress test = a banking rotate/addressing bug (check `vForBank = (b - tailLo) mod 4`, `rowOf`, and the read bank-select).

- [ ] **Step 7: Commit**

```bash
git add src/main/scala/m68k040/decode/MicroOpQueue.scala src/test/scala/m68k040/decode/MicroOpQueueSpec.scala
git commit -m "decode: bank the MicroOpQueue ring by addr[1:0] (rotate-write + factored reads); collapses the 4->16 write crossbar to a tail-rotate, cuts the ring congestion. Flop-only, contract-preserving"
```

---

## Task 2: Lock-step regression + test-fast (behavior-preserving proof)

**Files:** none (run only). The queue is on every instruction's decode→rename path; a banking bug shows as a divergence/deadlock.

- [ ] **Step 1: Decode + crack specs**

Run: `~/sbt/bin/sbt 'testOnly m68k040.decode.DecodeStageSpec' 'testOnly m68k040.decode.DecodeCrackPipeSpec' 'testOnly m68k040.decode.CrackLoadSpec' 'testOnly m68k040.decode.CrackStoreSpec' 'testOnly m68k040.decode.MemRmwDecodeSpec'`
Expected: all PASS (these push variable 1–4 µop bursts through the queue).

- [ ] **Step 2: Full lock-step subsets vs Musashi (each its OWN timeout-wrapped JVM)**

Run EACH separately (do NOT batch — OOM):
```bash
for z in "loop" "call" "RMW" "IRQ" "bne" "nested bsr" "DIV" "MUL"; do
  JAVA_OPTS=-Xmx10g timeout 1000 ~/sbt/bin/sbt "testOnly m68k040.lockstep.ExecuteLockStepSpec -- -z \"$z\"" > /home/qwertyoruiop/tmp/banking-ls-"${z// /_}".log 2>&1
  echo "$z: $(grep -iE 'Tests: succeeded|failed|diverged|Simulation failed' /home/qwertyoruiop/tmp/banking-ls-"${z// /_}".log | tail -2)"
done
```
Expected: every group PASS, 0 diverged, 0 "Simulation failed".

- [ ] **Step 3: test-fast (own JVM)**

Run: `JAVA_OPTS=-Xmx10g timeout 1000 ~/sbt/bin/sbt fastTest`
Expected: `All tests passed.` (~91).

- [ ] **Step 4: Commit (empty marker)**

```bash
git commit --allow-empty -m "test: lock-step subsets + test-fast green for MicroOpQueue banking (behavior-preserving)"
```

---

## Task 3: Measurement — banking + full-DecodeStage pblock (composed)

**Files:** create `synth/floorplan_full.xdc` (the corrected full-DecodeStage pblock); uses `synth/ooc_M68kFullCoreSynth.tcl` + an impl tcl.

- [ ] **Step 1: Generate the netlist**

Run: `JAVA_OPTS=-Xmx10g ~/sbt/bin/sbt "runMain m68k040.top.GenFullCoreSynthVerilog"` → `generated/M68kFullCoreSynth.v`.

- [ ] **Step 2: OOC + congestion sanity (vivado — NO Verilator concurrent)**

`pgrep -af vivado` first (spare 030). Run OOC and confirm the ring write fabric shrank:
```bash
timeout 1800 vivado -mode batch -nojournal -nolog -source synth/ooc_M68kFullCoreSynth.tcl
grep -nE "Slack \(VIOLATED|Source:|Destination:|Logic Levels" synth/M68kFullCoreSynth_timing.rpt | head -12
```
Expected: still synthesizes; the ring write is no longer a 16-wide crossbar (worst path may shift). Confirm no inferred RAM (ring stays flops): `grep -iE "RAMB|LUT as Memory" synth/M68kFullCoreSynth_util.rpt`.

- [ ] **Step 3: Write the full-DecodeStage pblock + composed impl, run it (vivado)**

Create `synth/floorplan_full.xdc` — capture the FULL DecodeStage (add cells AFTER opt; the prior 1284-cell under-match was from adding pre-opt):
```tcl
create_pblock pb_decode
resize_pblock pb_decode -add {SLICE_X36Y0:SLICE_X75Y104}
add_cells_to_pblock pb_decode [get_cells -hier -filter {NAME =~ *DecodeStage_logic*}]
```
Create `synth/banking_impl.tcl`:
```tcl
read_verilog generated/M68kFullCoreSynth.v
read_xdc synth/clk.xdc
synth_design -top M68kFullCoreSynth -part xcku5p-ffvb676-2-e -mode out_of_context
opt_design
read_xdc synth/floorplan_full.xdc
puts "PBLOCK_CELLS [llength [get_cells -of_objects [get_pblocks pb_decode]]]"
place_design
phys_opt_design
route_design
set wns [get_property SLACK [get_timing_paths -max_paths 1 -nworst 1 -setup]]
puts "BANKING+PBLOCK WNS $wns FMAX [expr {1000.0/(4.000 - $wns)}] (baseline 194.6, pblock-only 211.5)"
report_design_analysis -congestion -file synth/banking_congestion.rpt
report_timing -nworst 10 -max_paths 10 -file synth/banking_worst10.rpt
puts "BANKING_IMPL_DONE"
```
Run (background, timeout-wrapped, output to file; ONE wait):
```bash
nohup bash -c 'cd <worktree>; timeout 5400 vivado -mode batch -nojournal -log synth/banking_vivado.log -source synth/banking_impl.tcl > synth/banking_console.log 2>&1; echo EXIT=$? >> synth/banking_console.log' >/dev/null 2>&1 &
```
Then watch `synth/banking_console.log` for `BANKING_IMPL_DONE` / `EXIT=`. Expected: `PBLOCK_CELLS` ≈ 20k (full capture, not 1284); WNS improved vs the 211.5 pblock-only point; the ring no longer the level-5 congestion hotspot in `banking_congestion.rpt`.

- [ ] **Step 4: Report + commit**

Record: banking OOC worst path; banking+pblock post-route FMax vs 194.6 (baseline) / 211.5 (pblock-only); the ring congestion before/after; PBLOCK_CELLS captured.
```bash
git add synth/floorplan_full.xdc synth/banking_impl.tcl synth/banking_worst10.rpt synth/banking_congestion.rpt
git commit -m "synth: banking + full-DecodeStage pblock measurement — <FMax> (baseline 194.6, pblock-only 211.5); ring congestion <before>-><after>"
```

---

## Task 4: (Then) layer P1 — separate measurement

**Files:** none new (uses the P1 branch).

- [ ] **Step 1: Combine banking + P1, re-measure**

After Task 3 confirms banking+pblock, measure all three levers: merge/cherry-pick the banking change onto a worktree that also has P1 (`feat/decode-push-register`), gen the netlist, and run `synth/banking_impl.tcl` (banking + full-DecodeStage pblock) on the P1+banking RTL.
Run (vivado): same flow as Task 3 Step 3.
Expected: WNS improved further (P1 registers the push so the decode cone is off the path; banking shrinks the write fabric; the pblock co-locates) — all three on the same #1 arc. Report the three-lever FMax vs the prior points; this informs whether 250 is reached and what (if anything) is the next limiter.

- [ ] **Step 2: Report — decide next**

Record the three-lever post-route FMax + the new worst path. If ≥250 → done (merge banking + P1, apply the pblock to the impl flow). If short → the new worst path names the next target (D-cache/DTLB or IQ per the floorplan baseline #4/#5) — its own slice/pblock.

---

## Done-When

- `MicroOpQueue` uses the 4-bank layout (`bankRegs` + `tail[1:0]` rotate-write + factored reads); `io` contract + `head`/`tail`/`count`/flush unchanged; ring stays flops (no inferred RAM).
- `MicroOpQueueSpec` (FIFO-order + flush + the new randomized stress) green; decode/crack + full lock-step subsets green (0 diverged); `fastTest` green — behavior-preserving.
- Banking OOC shows the write crossbar collapsed; banking + full-DecodeStage pblock post-route measured vs 194.6/211.5, ring congestion dropped, `PBLOCK_CELLS`≈20k.
- Three-lever (banking + pblock + P1) post-route measured; next limiter identified (or 250 reached).
- Memory updated ([[frontend-fmax-250-campaign]]) with the banking result + the composed-FMax numbers.
