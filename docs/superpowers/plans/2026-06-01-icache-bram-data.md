# I-cache BRAM Data Array Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the L1I data array a synchronous-read (BRAM-inferable) memory, splitting the read into a clean 2-cycle S0(accept)/S1(respond) pipeline so the I-cache is FPGA-mappable and timing-clean.

**Architecture:** Today the I-cache reads tags + all 4 data ways + muxes + lane-selects combinationally in the accept cycle, then registers a 1-cycle response. The 256-bit `readAsync` data array cannot be BRAM. New: in the accept cycle (S0) launch a synchronous data-array read (`readSync`, address registered into the BRAM) and do the async tag-compare / hit-way and async pred read (registered into S1 latches); the next cycle (S1) the BRAM beats are available, get muxed by the registered hit-way + lane-selected, and registered into the response stage. cmd→rsp becomes 2 cycles (the +1 is the fetch→decode cycle). The consumer (`FetchAlignPlugin`) is single-outstanding and latency-agnostic, so it needs no change.

**Tech Stack:** SpinalHDL 1.14.1 / Scala 2.13 / sbt at `~/sbt/bin/sbt` (NOT on PATH) / Verilator / ScalaTest. Vivado 2025.2 for the synth check (part `xcku5p-ffvb676-2-e`).

**Branch:** `feat/icache-bram-data` (already created; spec committed at `517f19f`).

---

### Task 1: dataMem → synchronous-read BRAM + 2-cycle S0/S1 pipeline

**Files:**
- Modify: `src/main/scala/m68k040/cache/IcachePlugin.scala` (the whole `logic` Area)
- Test: `src/test/scala/m68k040/cache/IcacheSpec.scala` (add one latency test)

- [ ] **Step 1: Write the failing latency test**

Add this test inside `class IcacheSpec` in `src/test/scala/m68k040/cache/IcacheSpec.scala` (after the existing Test 1, before Test 2 is fine — placement does not matter):

```scala
  // -------- Latency: a warm hit responds exactly 2 cycles after accept (BRAM read) --------
  test("warm hit responds exactly two cycles after cmd accept", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      IcacheSim.attachMemory(dut.icache.logic.axi, cd, base = 0L, size = 0x10000)
      dut.probe.logic.cmdIn.valid #= false
      dut.probe.logic.cmdIn.payload.pc #= 0
      dut.icache.logic.invalidateAll #= false
      cd.waitSampling(2)
      pulseInvalidateAll(dut, cd)

      val base = 0x6000L
      fetch(dut, cd, base)            // cold miss warms the line
      cd.waitSampling(4)              // let the pipeline drain to idle

      // Present a hit and measure latency precisely.
      dut.probe.logic.cmdIn.valid #= true
      dut.probe.logic.cmdIn.payload.pc #= base
      cd.waitSamplingWhere(
        dut.probe.logic.cmdIn.ready.toBoolean && dut.probe.logic.cmdIn.valid.toBoolean)
      dut.probe.logic.cmdIn.valid #= false
      // +1 cycle: BRAM read still in flight, no response yet
      cd.waitSampling()
      assert(!dut.probe.logic.rspOut.valid.toBoolean,
        "rsp must NOT be valid 1 cycle after accept (BRAM read in flight)")
      // +2 cycles: response arrives
      cd.waitSampling()
      assert(dut.probe.logic.rspOut.valid.toBoolean,
        "rsp must be valid exactly 2 cycles after accept")
      assert(dut.probe.logic.rspOut.payload.data.toBigInt == IcacheSim.window64(base),
        "2-cycle hit data mismatch")
      cd.waitSampling(4)
    }
  }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `~/sbt/bin/sbt "testOnly m68k040.cache.IcacheSpec -- -z \"two cycles\""`
Expected: FAIL — on the current 1-cycle implementation `rspOut.valid` is already true at +1 cycle, so the first assert ("must NOT be valid 1 cycle after accept") fails.

- [ ] **Step 3: Replace the `logic` Area with the BRAM/pipeline implementation**

Replace the entire `val logic = during build new Area { ... }` block in `src/main/scala/m68k040/cache/IcachePlugin.scala` (lines 34–270, i.e. everything from `val logic = during build new Area {` up to and including its closing `}` before the `// ---- FetchService trait implementation ----` comment) with exactly this:

```scala
  val logic = during build new Area {

    // ---- FetchService ports: plain directionless Stream/Flow (NOT slave/master) ----
    val cmdPort       = Stream(FetchCmd())
    val rspPort       = Flow(FetchRsp())
    val axi           = master(Axi4ReadOnly(axiCfg))
    val invalidateAll = in Bool()

    // ---- resolve TranslationService ----
    val xlate = host[TranslationService]
    val activePc = UInt(32 bits)
    xlate.req.vpn        := activePc(31 downto 12)
    xlate.req.supervisor := False

    // ---- storage arrays ----
    // Tags + pred: async-read LUTRAM (single write port -> distributed RAM). Kept
    // async so hit/miss is resolved in the accept cycle (a miss starts REFILL with
    // no added latency).
    val tagMem  = Seq.fill(ways)(Mem(UInt(tagBits bits), sets))
    val predMem = Seq.fill(ways)(Mem(Bits(128 bits), sets))
    // Data: synchronous-read BRAM. 2 beats x 64 sets = 128 entries per way.
    val dataMem = Seq.fill(ways)(Mem(Bits(256 bits), sets * beatsPerLine))
    // Valid bits: register array, cleared by invalidateAll
    val valids  = Vec.fill(ways)(Vec.fill(sets)(RegInit(False)))
    // Round-robin victim pointer per set
    val victim  = Vec.fill(sets)(RegInit(U(0, wayBits bits)))

    // ---- invalidateAll: priority clear of all valid bits ----
    when(invalidateAll) {
      for (w <- 0 until ways; s <- 0 until sets) valids(w)(s) := False
    }

    // ---- miss-state latches ----
    val missPC    = Reg(UInt(32 bits))
    val missSet   = Reg(UInt(setBits bits))
    val missTag   = Reg(UInt(tagBits bits))
    val victimWay = Reg(UInt(wayBits bits))
    val beatCnt   = Reg(UInt(1 bits)) init U(0, 1 bits)
    val arSent    = Reg(Bool()) init False
    val lineReg   = Reg(Bits(512 bits))

    // ---- shared data-array read port (synchronous; BRAM) ----
    // Address+enable are driven by the FSM (IDLE hit accept, or REPLAY). The
    // result `dataBeat` is the registered BRAM output, valid the NEXT cycle.
    val dataReadAddr = UInt((setBits + 1) bits)
    val dataReadEn   = Bool()
    dataReadAddr := U(0, (setBits + 1) bits)
    dataReadEn   := False
    val dataBeat = Vec(dataMem.map(_.readSync(dataReadAddr, dataReadEn)))

    // ---- S1 (response-build) pipeline registers ----
    // The cycle after a read is launched, dataBeat is ready: mux by the latched
    // hit-way, lane-select, and register into the rsp output stage below.
    val s1Valid = Reg(Bool()) init False
    val s1Way   = Reg(UInt(wayBits bits))
    val s1Pc    = Reg(UInt(32 bits))
    val s1Fault = Reg(Bool())
    val s1Lane  = Reg(UInt(2 bits))
    val s1Pred  = Reg(Vec(ChunkPredecode(), 4))
    s1Valid := False   // default each cycle; armed in IDLE-hit / REPLAY below

    // ---- rsp output register stage ----
    val rspValidReg = Reg(Bool()) init False
    val rspPcReg    = Reg(UInt(32 bits))
    val rspDataReg  = Reg(Bits(64 bits))
    val rspFaultReg = Reg(Bool())
    val rspPredReg  = Reg(Vec(ChunkPredecode(), 4))

    // ---- window predecode helper ----
    def windowPred(entry: Bits, pc: UInt): Vec[ChunkPredecode] = {
      val win  = entry.subdivideIn(16 bits)(pc(5 downto 3))
      val nibs = win.subdivideIn(4 bits)
      Vec(nibs.map(b => b.as(ChunkPredecode())))
    }

    // ---- S1 -> rsp output register (runs every cycle; meaningful when s1Valid) ----
    val s1Beat   = dataBeat(s1Way)
    val s1Window = s1Beat.subdivideIn(64 bits)(s1Lane)
    rspValidReg := s1Valid
    rspPcReg    := s1Pc
    rspDataReg  := s1Window
    rspFaultReg := s1Fault
    rspPredReg  := s1Pred

    // ---- rsp outputs (combinational from the registered stage) ----
    rspPort.valid         := rspValidReg
    rspPort.payload.pc    := rspPcReg
    rspPort.payload.data  := rspDataReg
    rspPort.payload.fault := rspFaultReg
    rspPort.payload.pred  := rspPredReg

    // ---- default output assignments ----
    cmdPort.ready := False
    axi.ar.valid  := False
    axi.ar.payload.assignDontCare()
    axi.r.ready   := False
    activePc      := cmdPort.payload.pc

    // ---- hit detection (combinational, from cmdPort.pc + translation) ----
    val idlePc    = cmdPort.payload.pc
    val idleSet   = idlePc(11 downto 6)
    val idleTag   = xlate.rsp.ppn
    val hitVec = Vec(Bool(), ways)
    for (w <- 0 until ways)
      hitVec(w) := valids(w)(idleSet) && (tagMem(w).readAsync(idleSet) === idleTag)
    val isHit       = hitVec.orR
    val hitWayIdx   = OHToUInt(hitVec)
    val idleBeatSel = idlePc(5)
    val idleLaneIdx = idlePc(4 downto 3)
    val idleReadAddr  = (idleSet ## idleBeatSel).asUInt
    val idlePredEntry = Vec(predMem.map(_.readAsync(idleSet)))

    // Single-in-flight: do not accept a new cmd while a hit response is draining
    // (S1 or the rsp register). Costs nothing — the consumer is single-outstanding.
    val inFlight = s1Valid || rspValidReg

    // ---- FSM ----
    val fsm = new StateMachine {
      val IDLE      = new State with EntryPoint
      val REFILL    = new State
      val PREDECODE = new State
      val REPLAY    = new State

      // ----- IDLE: accept; hit -> arm S1 read, miss -> latch + refill -----
      IDLE.whenIsActive {
        activePc      := cmdPort.payload.pc
        cmdPort.ready := !inFlight

        when(cmdPort.fire) {
          when(isHit) {
            // Arm S1: launch the data BRAM read; latch control for next-cycle mux.
            dataReadAddr := idleReadAddr
            dataReadEn   := True
            s1Valid := True
            s1Way   := hitWayIdx
            s1Pc    := idlePc
            s1Fault := xlate.rsp.fault
            s1Lane  := idleLaneIdx
            s1Pred  := windowPred(idlePredEntry(hitWayIdx), idlePc)
          } otherwise {
            missPC    := idlePc
            missSet   := idleSet
            missTag   := idleTag
            victimWay := victim(idleSet)
            beatCnt   := U(0, 1 bits)
            arSent    := False
            goto(REFILL)
          }
        }
      }

      // ----- REFILL: issue AXI AR; collect 2 R beats into dataMem -----
      REFILL.whenIsActive {
        activePc := missPC
        val lineBase = missPC & ~U(63, 32 bits)

        when(!arSent) {
          axi.ar.valid         := True
          axi.ar.payload.addr  := lineBase
          axi.ar.payload.id    := U(0, 2 bits)
          axi.ar.payload.len   := U(1, 8 bits)
          axi.ar.payload.size  := U(5, 3 bits)
          axi.ar.payload.burst := Axi4.burst.INCR
          when(axi.ar.ready) { arSent := True }
        }

        axi.r.ready := True
        when(axi.r.valid) {
          val writeAddr = (missSet ## beatCnt).asUInt
          for (w <- 0 until ways) {
            when(victimWay === U(w, wayBits bits)) {
              dataMem(w).write(writeAddr, axi.r.payload.data)
            }
          }
          when(beatCnt === U(0, 1 bits)) {
            lineReg(255 downto 0)   := axi.r.payload.data
          } otherwise {
            lineReg(511 downto 256) := axi.r.payload.data
          }
          beatCnt := beatCnt + 1
          when(axi.r.payload.last) { goto(PREDECODE) }
        }
      }

      // ----- PREDECODE: classify line, write predMem + tag/valid -----
      PREDECODE.whenIsActive {
        activePc := missPC
        val words  = lineReg.subdivideIn(16 bits)
        val chunks = Vec(words.map(w => PredecodeWord.classify(w)))
        val packed = chunks.asBits
        for (w <- 0 until ways) {
          when(victimWay === U(w, wayBits bits)) {
            predMem(w).write(missSet, packed)
            tagMem(w).write(missSet, missTag)
            valids(w)(missSet) := True
          }
        }
        victim(missSet) := victim(missSet) + 1
        goto(REPLAY)
      }

      // ----- REPLAY: arm S1 read for the just-filled line, then IDLE -----
      REPLAY.whenIsActive {
        activePc := missPC
        val replayBeatSel  = missPC(5)
        val replayReadAddr = (missSet ## replayBeatSel).asUInt
        val replayPredEntry = Vec(predMem.map(_.readAsync(missSet)))

        dataReadAddr := replayReadAddr
        dataReadEn   := True
        s1Valid := True
        s1Way   := victimWay
        s1Pc    := missPC
        s1Fault := xlate.rsp.fault
        s1Lane  := missPC(4 downto 3)
        s1Pred  := windowPred(replayPredEntry(victimWay), missPC)

        goto(IDLE)
      }
    }

    // FetchService accessors
    def cmd: Stream[FetchCmd] = cmdPort
    def rsp: Flow[FetchRsp]   = rspPort
  }
```

Notes for the implementer:
- This removes the old `readDataMem` helper and the in-IDLE/REPLAY async data reads (`idleBeatData`/`idleWindow`/`replayBeatData`/`replayWindow`) — the data read now goes through the single shared `readSync` port + S1 mux.
- `dataMem(w).readSync(dataReadAddr, dataReadEn)` is the only data read port; `dataMem(w).write(...)` in REFILL is the write port → simple dual-port → BRAM.
- `tagMem`/`predMem` stay `readAsync` (unchanged mapping).
- The `// ---- FetchService trait implementation ----` block and the two `override def` lines below the `logic` Area are unchanged.

- [ ] **Step 4: Run the latency test to verify it passes**

Run: `~/sbt/bin/sbt "testOnly m68k040.cache.IcacheSpec -- -z \"two cycles\""`
Expected: PASS.

- [ ] **Step 5: Run the full IcacheSpec to confirm no regressions**

Run: `~/sbt/bin/sbt "testOnly m68k040.cache.IcacheSpec"`
Expected: all tests PASS (cold miss, same-line hit, eviction, invalidate, predecode, boundary, and the new latency test). The existing `fetch` helper polls `rsp.valid`, so it tolerates the new 2-cycle latency automatically.

- [ ] **Step 6: Commit**

```bash
git add src/main/scala/m68k040/cache/IcachePlugin.scala src/test/scala/m68k040/cache/IcacheSpec.scala
git commit -m "icache: data array -> sync-read BRAM (2-cycle S0/S1 pipeline)"
```

---

### Task 2: Regression across the frontend + a streaming-hit test

**Files:**
- Test: `src/test/scala/m68k040/cache/IcacheSpec.scala` (add one streaming test)
- Verify: `src/test/scala/m68k040/frontend/FetchAlignSpec.scala` (no change expected — run it)

- [ ] **Step 1: Add a streaming same-line hit test**

Add inside `class IcacheSpec` in `src/test/scala/m68k040/cache/IcacheSpec.scala`:

```scala
  // -------- Streaming: 3 consecutive windows in a warm line all return correct data --------
  test("three consecutive same-line hits return correct windows at the new latency", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      IcacheSim.attachMemory(dut.icache.logic.axi, cd, base = 0L, size = 0x10000)
      dut.probe.logic.cmdIn.valid #= false
      dut.probe.logic.cmdIn.payload.pc #= 0
      dut.icache.logic.invalidateAll #= false
      cd.waitSampling(2)
      pulseInvalidateAll(dut, cd)

      val base = 0x7000L
      fetch(dut, cd, base)   // cold miss warms the whole 64B line
      // Three windows within the same line (offsets 8, 16, 24): all hits.
      for (off <- Seq(8L, 16L, 24L)) {
        val got = fetch(dut, cd, base + off)
        assert(got == IcacheSim.window64(base + off),
          s"streaming hit at +$off mismatch: got 0x${got.toString(16)} expected 0x${IcacheSim.window64(base + off).toString(16)}")
      }
      cd.waitSampling(4)
    }
  }
```

- [ ] **Step 2: Run IcacheSpec**

Run: `~/sbt/bin/sbt "testOnly m68k040.cache.IcacheSpec"`
Expected: all PASS including the new streaming test.

- [ ] **Step 3: Run the frontend integration spec (latency transparency + redirect staleness)**

Run: `~/sbt/bin/sbt "testOnly m68k040.frontend.FetchAlignSpec"`
Expected: all PASS unchanged. This exercises the real I-cache end-to-end through `FetchAlignPlugin` (sequential fetch, redirect/resume, complex-stall). It is event-driven on `rsp.valid` and single-outstanding, so the 2-cycle latency — including any redirect issued while a fetch is in flight (now a wider window) — is handled by the existing `rspStale` path with no code change. If any FetchAlignSpec test hard-codes a cycle count and fails, fix the test's wait to poll the relevant `valid`/`fire` signal (do not change the RTL).

- [ ] **Step 4: Run the full fast + Verilator suites**

Run: `make SBT=~/sbt/bin/sbt test-fast`
Expected: all PASS (the full fast suite — was 53 before this slice; now +2 IcacheSpec tests).

Run: `make SBT=~/sbt/bin/sbt test-verilator`
Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add src/test/scala/m68k040/cache/IcacheSpec.scala
git commit -m "icache: streaming-hit regression test for BRAM data path"
```

---

### Task 3: Confirm BRAM inference in synthesis (non-gating)

**Files:**
- Use: `synth/` harness (already present on this branch: `synth/clk.xdc`, `synth/ooc_synth.tcl`, `synth/regfile_fix.py`), `src/main/scala/m68k040/top/GenVerilog.scala` (`GenSynthVerilog` — full pipeline incl. I-cache).

**Context:** `GenSynthVerilog` wires the full pipeline (incl. `IcachePlugin`). The earlier full-pipeline synth failed because the I-cache data/tag/pred + RAT/Freelist/ROB Mems were multi-write or wide-async. After this slice the I-cache **data** array should infer BRAM. The RAT/Freelist/ROB-payload multi-write Mems are a **separate** known issue (`memory/fpga-synth-multiwrite-mem.md`); `synth/regfile_fix.py` already converts those. The goal of this task is only to confirm `dataMem` now maps to Block RAM and no longer throws "Unsupported RAM template".

- [ ] **Step 1: Regenerate the full-pipeline Verilog**

Run: `~/sbt/bin/sbt "runMain m68k040.top.GenSynthVerilog"`
Expected: `Generated generated/M68kCoreSynth.v`. The `dataMem` warnings should now say `readSync`/Block RAM-compatible rather than the previous `readAsync ... can only be write first` for `dataMem` (tag/pred may still warn — they stay async LUTRAM, which is fine).

- [ ] **Step 2: Apply the known register-file fix for the backend multi-write Mems**

Run: `python3 synth/regfile_fix.py generated/M68kCoreSynth.v`
Expected: prints `applied N block edits across M merged arrays` (this handles RAT/Freelist/ROB payload — NOT the I-cache data array, which is now a clean single-write BRAM and is not matched by the multi-write merger).

- [ ] **Step 3: Synthesize and check BRAM inference**

Create `synth/ooc_full.tcl` with:

```tcl
read_verilog generated/M68kCoreSynth.v
read_xdc synth/clk.xdc
synth_design -top M68kCoreSynth -part xcku5p-ffvb676-2-e -mode out_of_context
report_utilization -file synth/full_util.rpt
set bram [get_property USED [lindex [get_property -quiet STATISTICS [current_design]] 0]]
puts "SYNTH_DONE check synth/full_util.rpt for Block RAM Tile"
```

Run: `vivado -mode batch -nojournal -log synth/vivado_full.log -source synth/ooc_full.tcl 2>&1 | grep -iE "Unsupported RAM|Block RAM|completed|ERROR" | head`
Expected: **no** `Unsupported RAM template` error for `dataMem`; synthesis completes.

- [ ] **Step 4: Confirm Block RAM count**

Run: `grep -iE "Block RAM Tile|RAMB36|RAMB18" synth/full_util.rpt`
Expected: a non-zero `Block RAM Tile` row (the four 256-bit × 128 data ways now occupy RAMB tiles). Record the count.

- [ ] **Step 5: Commit the synth harness + generated artifacts gitignore**

Add a `.gitignore` entry so generated netlists / Vivado logs are not committed as noise. Create or append to `/home/qwertyoruiop/m68k-core-040-ooo/.gitignore`:

```
generated/
synth/*.rpt
synth/*.log
synth/vivado*.jou
clockInfo.txt
.Xil/
vivado*.log
vivado*.jou
```

```bash
git add .gitignore synth/clk.xdc synth/ooc_synth.tcl synth/ooc_backend.tcl synth/impl_backend.tcl synth/ooc_full.tcl synth/regfile_fix.py src/main/scala/m68k040/top/GenVerilog.scala src/main/scala/m68k040/top/SynthProbePlugin.scala src/main/scala/m68k040/top/DecodeUopInputPlugin.scala
git commit -m "synth: full-pipeline harness; confirm icache data array infers BRAM"
```

---

## Self-Review

**1. Spec coverage:**
- §1/§4 sync-read data + S0/S1 pipeline → Task 1 (the full `logic` rewrite). ✓
- §3 latency model (2-cycle, consumer unchanged) → Task 1 latency test + Task 2 Step 3 (FetchAlignSpec). ✓
- §5 hazards: single-in-flight accept (`inFlight` gate) → Task 1 RTL; `readSync` enable → Task 1 (`dataReadEn`); no functional change → Task 1 Steps 5; redirect/resume widened stale window → Task 2 Step 3 (FetchAlignSpec). ✓
- §6 verification: hit latency (T1), miss→refill→replay (existing IcacheSpec, T1 Step 5), sequential hits (T2 streaming test), redirect mid-flight (T2 Step 3), BRAM inference (T3). ✓
- §7 files: IcachePlugin + IcacheSpec → T1/T2. ✓

**2. Placeholder scan:** No TBD/TODO/"handle edge cases"; every code step shows full code. The one Vivado TCL `STATISTICS` line is a convenience print; the authoritative check is the `report_utilization` grep in T3 Step 4. ✓

**3. Type consistency:** `dataReadAddr`/`dataReadEn`/`dataBeat`/`s1Valid`/`s1Way`/`s1Pc`/`s1Fault`/`s1Lane`/`s1Pred`/`rsp*Reg` names are consistent between the RTL block and the prose. `windowPred` signature unchanged from the original. `readSync(addr, enable)` and `write(addr, data)` are the SpinalHDL `Mem` API. `ChunkPredecode()`, `PredecodeWord.classify`, `FetchCmd`/`FetchRsp` unchanged. ✓
