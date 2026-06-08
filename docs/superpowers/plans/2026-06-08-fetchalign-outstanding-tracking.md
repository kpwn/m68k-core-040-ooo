# FetchAlign Per-Fetch Outstanding Tracking (Slice 1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace `FetchAlignPlugin`'s global staleness/leading-drop registers with a small per-fetch outstanding-record FIFO (depth-parameterized, =1 now) so each fetch carries its own `stale`+`drop` — fixing the latent stale-fetch-during-miss deadlock that blocks P1, and building the multi-outstanding-ready FetchAlign side.

**Architecture:** A per-fetch record carrying its own `stale` + `drop`, captured at issue (born-stale if a redirect fires the same cycle; marked stale if a redirect fires while it's in flight; drop latched from a `pendingDrop` reg) — immune to later redirects overwriting global state. Slice 1 is the depth-1 instance (`recValid`/`recStale`/`recDrop`, single-outstanding preserved); Slice 2 generalizes it to a depth-N ring. No `IcachePlugin` change.

**Tech Stack:** SpinalHDL 1.14.1 / Scala 2.13 / sbt at `~/sbt/bin/sbt` (NOT on PATH) / Verilator / Vivado 2025.2 (`xcku5p-ffvb676-2-e`, OOC 4 ns). Lock-step vs `m68k040.oracle.Musashi`.

**Working dir:** isolated git worktree on `feat/fetchalign-outstanding-tracking` off current `master` (`fdb53b0`). Run all `sbt`/`vivado` from the worktree root.

**Memory discipline (HARD):** NEVER run Verilator and Vivado concurrently. NEVER run the whole `ExecuteLockStepSpec` (OOMs 12 GB) — `-z "<substr>"` subsets, `JAVA_OPTS=-Xmx10g`. Before any vivado run, `pgrep -af vivado`; only kill `040`/`FullCore` jobs. `fastTest` EXCLUDES `VerilatorTest`.

**Test-strategy note (read first):** This is a **behavior-preserving refactor** — on master (no P1) the buggy nested-during-miss case happens to work, so the deadlock fix is only *observable* under P1's +1 cycle. Therefore the correctness gates are: (a) a directed unit test of the new per-fetch invariant, (b) the full redirect lock-step suite staying green (regression — the refactor must not change any currently-passing redirect behavior), and (c) the **definitive proof deferred to the P1 resume**: rebase `feat/decode-push-register` on this and confirm `nested bsr` now passes. Do NOT fabricate a master-only "failing" test that doesn't actually fail.

---

## File Structure

- `src/main/scala/m68k040/frontend/FetchAlignPlugin.scala` — **the only RTL change.** Replace `rspStale`/`dropPending`/`dropCount` with the `FetchRecord` ring + `pendingDrop`; refactor the issue gate, the 3 redirect blocks (redirect/resume/mispredict), and the rsp-handling to use it.
- `src/test/scala/m68k040/frontend/FetchAlignSpec.scala` — **modify:** add a directed redirect-during-in-flight invariant test.
- Tests (run only): `FetchFaultSpec`, `ExecuteLockStepSpec` (redirect subsets), `fastTest`.

Reference (current `FetchAlignPlugin` state regs, ~lines 54-80): `fetchInFlight`, `rspStale`, `dropPending`, `dropCount` — these are what change. `decodePc`/`fetchPc`/`stalled`/`started`/`faultHold`/`faultEmitted`/`faultPc` are UNCHANGED.

---

## Task 1: Replace global staleness/drop with the per-fetch record ring

**Files:**
- Modify: `src/main/scala/m68k040/frontend/FetchAlignPlugin.scala`

- [ ] **Step 1: Add the per-fetch record state; remove the old globals**

Slice 1 is single-outstanding, so the "record FIFO" is a SINGLE record (Slice 2 generalizes `{recValid,recStale,recDrop}` to a depth-N ring). Near the top of `logic` (where `fetchInFlight`/`rspStale`/`dropPending`/`dropCount` are declared, ~lines 59-80), REMOVE `fetchInFlight`, `rspStale`, `dropPending`, `dropCount` (and the old `SimPublic(rspStale, fetchInFlight)`) and ADD:

```scala
    // Per-fetch outstanding-record tracking (Slice 1: ONE record = single-outstanding;
    // Slice 2 generalizes {recValid,recStale,recDrop} to a depth-N ring once the I-cache
    // is pipelined). The in-flight fetch carries its OWN stale + leading-drop, captured at
    // ISSUE — immune to later redirects overwriting global state (the bug that wedged
    // nested-bsr-during-miss: a stale window mis-attributed to a redirect target).
    val recValid = Reg(Bool()) init False   // a fetch is outstanding (replaces fetchInFlight)
    val recStale = Reg(Bool()) init False   // a redirect happened after it issued -> discard its rsp
    val recDrop  = Reg(UInt(2 bits)) init 0 // leading words to drop on its rsp (target[2:1])
    // Leading-word drop intent for the NEXT fetch to issue (set by a redirect to
    // target[2:1]; latched into recDrop at issue, then cleared).
    val pendingDrop = Reg(UInt(2 bits)) init 0
    spinal.core.sim.SimPublic(recValid, recStale, recDrop, pendingDrop)
```

- [ ] **Step 2: Compute the redirect-this-cycle signal and the issue gate**

The three redirect sources all bump the same way; compute a single combinational pulse BEFORE the FSM/issue logic (place it right after the record-state declarations):

```scala
    // Any fetch-redirect this cycle (external redirect, complex-resume, or commit
    // mispredict). A fetch issued THIS cycle used the pre-redirect fetchPc -> born stale.
    // (mispredictRedirect/redirect/resume are all declared above; resume only when stalled.)
    val redirectThisCycle = redirect.valid || (resume.valid && stalled) || mispredictRedirect.valid
```

Change the issue gate (current line ~92 `ic.cmd.valid := started && !fetchInFlight && …`) to:

```scala
    ic.cmd.valid      := started && !recValid && ibuf.io.push.ready && !stalled && !faultHold
    ic.cmd.payload.pc := fetchPc
```

(At depth-1, a response and a new issue never coincide: `cmd.valid` is gated on `!recValid` (old value), and a rsp sets `recValid := False` the same cycle — so the next issue is the following cycle. No inc/dec race.)

- [ ] **Step 3: Capture the record on issue (replace the `fetchInFlight := True` block)**

Replace the current `when(ic.cmd.fire) { fetchInFlight := True }` (~lines 95-97) with:

```scala
    when(ic.cmd.fire) {
      // Capture this fetch's record. Born stale iff a redirect fires THIS cycle (this
      // fetch used the old, now-wrong fetchPc). Latch the drop intent; consume it.
      recValid := True
      recStale := redirectThisCycle
      recDrop  := pendingDrop
      pendingDrop := 0
    }
```

Same-cycle ordering: a same-cycle issue+redirect can't issue a NEW fetch (the gate needs `!recValid`, and if a fetch is in flight `recValid` is True). When it CAN issue (`!recValid`), the redirect block (Step 5) guards on `when(recValid)` (the OLD value = False) so it does NOT overwrite — and this block already set `recStale := redirectThisCycle` (True). So a fetch issued the same cycle as a redirect is correctly born-stale. Consistent.

- [ ] **Step 4: Consume the record on response (rewrite the rsp-handling)**

The current rsp block (~lines 109-149) uses `rspStale`/`dropPending`/`dropCount`. Rewrite it to read the in-flight record. Replace the `rspFault` line + the `when(ic.rsp.valid){…}` block with:

```scala
    // A fault response (not stale): latch the fault + faulting PC, stop fetching.
    val rspFault = ic.rsp.valid && ic.rsp.payload.fault && !recStale && !faultHold
    when(rspFault) {
      faultHold := True
      faultPc   := ic.rsp.payload.pc
    }

    when(ic.rsp.valid) {
      recValid := False          // the outstanding fetch's response arrived (record consumed)
      fetchPc  := fetchPc + 8

      when(!recStale && !ic.rsp.payload.fault) {
        // Leading-word drop carried by THIS fetch's record.
        val startWord = recDrop
        val nWords    = U(4, 3 bits) - startWord.resize(3)
        for (j <- 0 until 4) {
          when(U(j) < nWords) {
            val srcIdx = (startWord + U(j, 2 bits)).resize(2)
            ibuf.io.push.payload.words(j)          := rspWords(srcIdx)
            ibuf.io.push.payload.preds(j).simple   := rspPreds(srcIdx).simple
            ibuf.io.push.payload.preds(j).lenWords := rspPreds(srcIdx).lenWords
          }
        }
        ibuf.io.push.payload.n := nWords
        ibuf.io.push.valid     := True
      }
      // stale OR fault: enqueue nothing (discard), as before.
    }
```

(`fetchPc := fetchPc + 8` stays in the rsp block; the redirect blocks override `fetchPc` later in the source, so they win on a redirect cycle. `recValid := False` here vs `recValid := True` in the issue block (Step 3) can't conflict at depth-1 — issue is gated on `!recValid`, so the two never fire the same cycle.)

- [ ] **Step 5: Update the three redirect blocks (redirect / resume / mispredict)**

In EACH of the three `when` blocks (`redirect.valid` ~198, `resume.valid && stalled` ~233, `mispredictRedirect.valid` ~255), REPLACE the old `dropPending := True; dropCount := newPc(2 downto 1)` and the `when(fetchInFlight && !ic.rsp.valid){ rspStale := True }` lines with:

```scala
      pendingDrop := newPc(2 downto 1)
      // Mark the in-flight fetch (if any) stale: a redirect invalidates it.
      // (No !ic.rsp.valid guard — the per-fetch recStale bit is robust; a stale rsp is
      // consumed + discarded in Step 4. recValid is NOT cleared: the fetch is still
      // physically coming; its stale response clears recValid normally, preserving the
      // single-outstanding invariant — a new fetch issues only after that frees occupancy.)
      when(recValid) { recStale := True }
```

Keep everything else in those blocks unchanged (`decodePc`/`fetchPc`/`ibuf.io.flush`/`stalled`/`started`/`faultHold`/`faultEmitted`). The old comment block (lines ~212-229) about "keep fetchInFlight" / the `!ic.rsp.valid` guard can be deleted (the per-fetch `recStale` replaces that reasoning) — replace it with a one-line: `// Per-fetch stale tracking (recValid/recStale/recDrop) replaces the single-bit rspStale/dropPending.`

- [ ] **Step 6: Compile**

Run: `~/sbt/bin/sbt compile`
Expected: success, no NO-DRIVER / latch / width errors. (Single record `recValid/recStale/recDrop` + `pendingDrop`; no pointers/Vec at depth-1.)

- [ ] **Step 7: Add a directed redirect-during-in-flight invariant test**

In `FetchAlignSpec.scala`, add a test that proves a fetch issued before a redirect is discarded and the post-redirect target is fetched cleanly. Using the existing `Dut` (real `IcachePlugin` + `FetchAlignPlugin` + `DecodeFeedProbePlugin`) and `IcacheSim.attachMemoryWithWords`:

```scala
  test("redirect during an in-flight fetch discards the stale window, fetches the new target", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      val a = 0x8000L; val b = 0x9000L
      // Two distinct windows: A = MOVEQ #1 x4, B = MOVEQ #2 x4 (1-word simple ops).
      IcacheSim.attachMemoryWithWords(dut.ic.logic.axi, cd, a, Seq.fill(8)(0x7201)) // MOVEQ #1,%d1
      IcacheSim.attachMemoryWithWords(dut.ic.logic.axi, cd, b, Seq.fill(8)(0x7402)) // MOVEQ #2,%d2
      dut.probe.logic.feedOut.ready #= false
      dut.fa.logic.resume.valid #= false; dut.fa.logic.redirect.valid #= false
      cd.waitSampling(2)
      // Redirect to A (cold -> miss/refill in flight), then quickly redirect to B before
      // A's window can be consumed — B must win, with a correct (lenWords>0) packet at B.
      dut.fa.logic.redirect.valid #= true; dut.fa.logic.redirect.payload #= a
      cd.waitSampling(); dut.fa.logic.redirect.payload #= b
      cd.waitSampling(); dut.fa.logic.redirect.valid #= false
      dut.probe.logic.feedOut.ready #= true
      cd.waitSamplingWhere(dut.probe.logic.feedOut.valid.toBoolean)
      assert(dut.probe.logic.feedOut.payload(0).pc.toLong == b,
        s"expected target B=0x${b.toHexString}, got 0x${dut.probe.logic.feedOut.payload(0).pc.toLong.toHexString}")
      assert(dut.probe.logic.feedOut.payload(0).lenWords.toInt > 0,
        s"stale-window leak: lenWords=${dut.probe.logic.feedOut.payload(0).lenWords.toInt} (would self-redirect)")
    }
  }
```

(If the exact 2-cycle redirect spacing does not straddle the refill on this harness, widen/narrow the `waitSampling` between the two redirects until A's fetch is genuinely in flight when B redirects; the assertion — B's PC with `lenWords>0` — is the invariant regardless.)

- [ ] **Step 8: Run the FetchAlign specs**

Run: `~/sbt/bin/sbt 'testOnly m68k040.frontend.FetchAlignSpec' 'testOnly m68k040.decode.FetchFaultSpec'`
Expected: all PASS — the existing 2-wide stream + complex-stall/resume tests, the new redirect-during-in-flight test, and the I-fetch-fault tests (fault gated on `!frontRec.stale`).

- [ ] **Step 9: Commit**

```bash
git add src/main/scala/m68k040/frontend/FetchAlignPlugin.scala src/test/scala/m68k040/frontend/FetchAlignSpec.scala
git commit -m "fetch: per-fetch outstanding-record tracking (FetchRecord{stale,drop}); robust redirect staleness + drop, replaces global rspStale/dropPending/dropCount (Slice 1, depth 1)"
```

---

## Task 2: Full redirect lock-step regression + test-fast (behavior-preserving proof)

**Files:** none (run only).

- [ ] **Step 1: Redirect-heavy lock-step subsets vs Musashi**

The refactor must not change ANY currently-passing redirect behavior. Run (never the whole spec):
```bash
JAVA_OPTS=-Xmx10g ~/sbt/bin/sbt \
  'testOnly m68k040.lockstep.ExecuteLockStepSpec -- -z "nested bsr"' \
  'testOnly m68k040.lockstep.ExecuteLockStepSpec -- -z "call"' \
  'testOnly m68k040.lockstep.ExecuteLockStepSpec -- -z "jsr"' \
  'testOnly m68k040.lockstep.ExecuteLockStepSpec -- -z "rtr"' \
  'testOnly m68k040.lockstep.ExecuteLockStepSpec -- -z "loop"' \
  'testOnly m68k040.lockstep.ExecuteLockStepSpec -- -z "bne"' \
  'testOnly m68k040.lockstep.ExecuteLockStepSpec -- -z "DBcc"' \
  'testOnly m68k040.lockstep.ExecuteLockStepSpec -- -z "IRQ"'
```
Expected: ALL PASS, 0 diverged, 0 "Simulation failed". `nested bsr` (line 1391) must stay green. Any deadlock/divergence = the refactor broke a redirect corner — debug against the old single-bit behavior.

- [ ] **Step 2: Exception/trap redirect subsets**

```bash
JAVA_OPTS=-Xmx10g ~/sbt/bin/sbt \
  'testOnly m68k040.lockstep.ExecuteLockStepSpec -- -z "TRAP"' \
  'testOnly m68k040.lockstep.ExecuteLockStepSpec -- -z "illegal"' \
  'testOnly m68k040.lockstep.ExecuteLockStepSpec -- -z "RTE"'
```
Expected: PASS (the mispredict/exception-redirect path + fault-hold interaction).

- [ ] **Step 3: test-fast**

Run: `~/sbt/bin/sbt fastTest`
Expected: `All tests passed.` (~91).

- [ ] **Step 4: Commit (empty marker if no code change)**

```bash
git commit --allow-empty -m "test: full redirect lock-step + test-fast green for per-fetch fetch tracking (behavior-preserving)"
```

---

## Task 3: OOC FMax sanity + merge readiness

**Files:** uses `synth/ooc_M68kFullCoreSynth.tcl`.

- [ ] **Step 1: Generate + OOC (vivado — NO Verilator concurrent)**

Run:
```bash
JAVA_OPTS=-Xmx10g ~/sbt/bin/sbt "runMain m68k040.top.GenFullCoreSynthVerilog"
pgrep -af vivado    # confirm no 040/FullCore job of yours; spare 030
timeout 1800 vivado -mode batch -nojournal -nolog -source synth/ooc_M68kFullCoreSynth.tcl
```
Expected: a `RESULT FullCore WNS … FMAX …` line. This is a FRONT-END (fetch) change, NOT the decode→ring critical path — FMax must be **neutral** (within OOC noise of ~229 OOC; not a regression). It is a sanity guard, not the gate (the decode→ring P1 + the I-cache Slice 2 are the FMax levers).

- [ ] **Step 2: Report + commit**

Record OOC FMax (neutral expected) and that the worst path is unchanged (still the decode→ring push cone, since this slice doesn't touch it).
```bash
git commit --allow-empty -m "synth: OOC sanity for fetch tracking — FMax neutral <FMax> (front-end change, not the critical path)"
```

---

## Done-When

- `FetchAlignPlugin` uses the per-fetch record (`recValid`/`recStale`/`recDrop` + `pendingDrop`, the depth-1 instance that Slice 2 generalizes to a ring); `rspStale`/`dropPending`/`dropCount`/`fetchInFlight` removed.
- New redirect-during-in-flight unit test + `FetchAlignSpec` + `FetchFaultSpec` green.
- Full redirect + exception lock-step subsets green (0 diverged); `fastTest` green — behavior-preserving on master.
- OOC FMax neutral (front-end change).
- **Success criterion (verified in the P1 resume, not this slice):** rebase `feat/decode-push-register` (`319a139`) on this merged slice → `nested bsr` lock-step PASSES (the deadlock is gone). This is the whole reason for Slice 1.
- Memory updated ([[frontend-fmax-250-campaign]]): Slice 1 done; P1 unblocked; Slice 2 (I-cache pipelining + raise `FETCH_OUTSTANDING`) is next.
