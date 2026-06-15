# 68040 MSP/ISP three-stack banking + live M-bit (Slice A) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make A7 bank across USP/ISP/MSP per the 68040 `(S,M)` model with a live M-bit (settable by MOVE-to-SR, restored by RTE, preserved across fault/trap entry), backed by **live-coherent** committed banks, validated airtight by lock-step with MSP/ISP compared every step.

**Architecture:** Extend every S-only stack-bank selection to `(S,M)` (`SystemState` gets `msp` + an `isp` rename); make the committed banks continuously mirror the live committed A7 (a committed-RAT read of arch-15 → a new int-PRF read port → `ss.writeA7` every cycle); widen the exception entry/RTE bank routing; thread MSP/ISP through the oracle trace → `OracleStep` → comparator and sample the (now-live) committed banks per step. Slice B (interrupt-with-M=1 throwaway frame $1) is OUT of scope.

**Tech Stack:** SpinalHDL 1.14.1 / Scala 2.13 (sbt at `~/sbt/bin/sbt`, NOT on PATH); Verilator lock-step; the Musashi C reference (`tools/musashi/`, built with `make`); Vivado 2025.2 post-route gate (`synth/impl_FullCore.tcl`).

**Spec:** `docs/superpowers/specs/2026-06-14-msp-isp-mbit-banking-design.md` (note §4.1 live-coherent banks).

---

## Conventions for every task
- **sbt invocation (memory discipline — never deviate):** run each heavy gate in its OWN JVM, output to a file, never batch:
  `JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt "testOnly <spec> -- -z \"<filter>\"" 2>&1 | tee /tmp/<name>.log`
  NEVER run the whole `ExecuteLockStepSpec` in one JVM (OOM); always `-z`-filter to a subset. Never two Vivado jobs at once, never Verilator+Vivado at once. Kill only stale sbt JVMs by explicit PID (never an over-broad `pkill -f`).
- **Commit messages:** use a heredoc/file for `-F -` (backtick-words mangle under bash `-m`). End every body with `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`.
- **The M-bit is SR bit 12 = system-byte bit 4.** S is SR bit 13 = system-byte bit 5. `popSr` is the full 16-bit SR, so `popSr(13)`=S, `popSr(12)`=M.
- **Compile-state map:** Task 1's `ssp`→`isp` rename breaks `src/main` (ExceptionUnit) AND `src/test` seed sites; Task 2 renames BOTH back to green. Do not be alarmed by a non-compiling tree between Task 1 and Task 2 — Task 1's unit test compiles standalone.

---

## Task 1: `SystemState` — three-way `(S,M)` A7 banking

**Files:**
- Modify: `src/main/scala/m68k040/exception/SystemState.scala`
- Test (create): `src/test/scala/m68k040/exception/SystemStateBankSpec.scala`

- [ ] **Step 1: Write the failing unit test** (banking truth table + write routing)

Create `src/test/scala/m68k040/exception/SystemStateBankSpec.scala`:

```scala
package m68k040.exception

import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite
import m68k040.VerilatorTest

class SystemStateHarness extends Component {
  val ss = new SystemState
  val io = new Bundle {
    val srSys = in UInt (8 bits)
    val isp   = in UInt (32 bits)
    val msp   = in UInt (32 bits)
    val usp   = in UInt (32 bits)
    val a7    = out UInt (32 bits)
  }
  ss.setSrSys.valid := True; ss.setSrSys.payload := io.srSys
  ss.setIsp.valid   := True; ss.setIsp.payload   := io.isp
  ss.setMsp.valid   := True; ss.setMsp.payload   := io.msp
  ss.setUsp.valid   := True; ss.setUsp.payload   := io.usp
  io.a7 := ss.a7
}

class SystemStateBankSpec extends AnyFunSuite {
  test("a7 banks USP/ISP/MSP by (S,M)", VerilatorTest) {
    SimConfig.compile(new SystemStateHarness).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.usp #= 0xAAAA0000L; dut.io.isp #= 0xBBBB0000L; dut.io.msp #= 0xCCCC0000L
      dut.io.srSys #= 0x00; dut.clockDomain.waitSampling(2); assert((dut.io.a7.toLong & 0xffffffffL) == 0xAAAA0000L) // S=0 -> USP
      dut.io.srSys #= 0x10; dut.clockDomain.waitSampling(2); assert((dut.io.a7.toLong & 0xffffffffL) == 0xAAAA0000L) // S=0,M=1 -> USP
      dut.io.srSys #= 0x20; dut.clockDomain.waitSampling(2); assert((dut.io.a7.toLong & 0xffffffffL) == 0xBBBB0000L) // S=1,M=0 -> ISP
      dut.io.srSys #= 0x30; dut.clockDomain.waitSampling(2); assert((dut.io.a7.toLong & 0xffffffffL) == 0xCCCC0000L) // S=1,M=1 -> MSP
    }
  }

  test("writeA7 routes to the (S,M)-selected bank, leaving others intact", VerilatorTest) {
    class WHarness extends Component {
      val ss = new SystemState
      val io = new Bundle {
        val srSys = in UInt (8 bits)
        val data  = in UInt (32 bits)
        val wr    = in Bool()
        val isp = out UInt (32 bits); val msp = out UInt (32 bits); val usp = out UInt (32 bits)
      }
      ss.setSrSys.valid := True; ss.setSrSys.payload := io.srSys
      ss.writeA7.valid := io.wr; ss.writeA7.payload := io.data
      io.isp := ss.isp; io.msp := ss.msp; io.usp := ss.usp
    }
    SimConfig.compile(new WHarness).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.wr #= false; dut.io.srSys #= 0x30; dut.clockDomain.waitSampling()
      dut.io.data #= 0x12340000L; dut.io.wr #= true; dut.clockDomain.waitSampling(); dut.io.wr #= false
      dut.clockDomain.waitSampling()
      assert((dut.io.msp.toLong & 0xffffffffL) == 0x12340000L) // M=1 -> MSP
      assert((dut.io.isp.toLong & 0xffffffffL) == 0L)
      assert((dut.io.usp.toLong & 0xffffffffL) == 0L)
    }
  }
}
```

- [ ] **Step 2: Run it — expect FAIL** (compile error: `setIsp`/`setMsp`/`isp`/`msp` not members of `SystemState`)

Run: `JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt "testOnly m68k040.exception.SystemStateBankSpec" 2>&1 | tee /tmp/ss_bank.log`
Expected: compilation failure referencing the new names.

- [ ] **Step 3: Implement the three-bank model** — replace the body of `class SystemState` (lines 30-72) with:

```scala
class SystemState extends Area {
  /** Supervisor bit position within the 8-bit SR system byte (SR bit 13). */
  val S_BIT = 5
  /** Master bit position within the 8-bit SR system byte (SR bit 12). */
  val M_BIT = 4

  val srSys = RegInit(U(0x27, 8 bits))   // S=1, M=0, I=7, T=0 (matches Musashi boot SR)
  val vbr   = RegInit(U(0, 32 bits))
  val usp   = RegInit(U(0, 32 bits))
  val isp   = RegInit(U(0, 32 bits))     // M=0 supervisor bank (Interrupt Stack Pointer)
  val msp   = RegInit(U(0, 32 bits))     // M=1 supervisor bank (Master Stack Pointer)
  srSys.simPublic(); vbr.simPublic(); usp.simPublic(); isp.simPublic(); msp.simPublic()

  /** Committed S (supervisor) and M (master) bits. */
  val s = srSys(S_BIT); s.simPublic()
  val m = srSys(M_BIT); m.simPublic()

  /** Active supervisor stack: M ? MSP : ISP. */
  val supBank = Mux(m, msp, isp)
  /** Architectural A7, banked by committed (S, M). */
  val a7 = Mux(s, supBank, usp); a7.simPublic()

  // ── write ports ────────────────────────────────────────────────────────────
  val setSrSys = Flow(UInt(8 bits))
  val setVbr   = Flow(UInt(32 bits))
  val setUsp   = Flow(UInt(32 bits))
  val setIsp   = Flow(UInt(32 bits))
  val setMsp   = Flow(UInt(32 bits))
  val writeA7  = Flow(UInt(32 bits))   // writes the bank selected by committed (S, M)
  setSrSys.valid.allowOverride; setSrSys.valid := False; setSrSys.payload.allowOverride; setSrSys.payload := U(0, 8 bits)
  setVbr.valid.allowOverride;   setVbr.valid := False;   setVbr.payload.allowOverride;   setVbr.payload := U(0, 32 bits)
  setUsp.valid.allowOverride;   setUsp.valid := False;   setUsp.payload.allowOverride;   setUsp.payload := U(0, 32 bits)
  setIsp.valid.allowOverride;   setIsp.valid := False;   setIsp.payload.allowOverride;   setIsp.payload := U(0, 32 bits)
  setMsp.valid.allowOverride;   setMsp.valid := False;   setMsp.payload.allowOverride;   setMsp.payload := U(0, 32 bits)
  writeA7.valid.allowOverride;  writeA7.valid := False;  writeA7.payload.allowOverride;  writeA7.payload := U(0, 32 bits)

  // ── commit-time updates ──────────────────────────────────────────────────
  when(setSrSys.valid) { srSys := setSrSys.payload }
  when(setVbr.valid)   { vbr   := setVbr.payload }
  // writeA7 routes by COMMITTED (S, M). Listed FIRST so an explicit setIsp/setMsp/setUsp
  // in the same cycle wins (later-`when` wins in SpinalHDL).
  when(writeA7.valid) {
    when(s) { when(m) { msp := writeA7.payload } otherwise { isp := writeA7.payload } }
    .otherwise { usp := writeA7.payload }
  }
  when(setUsp.valid)   { usp := setUsp.payload }
  when(setIsp.valid)   { isp := setIsp.payload }
  when(setMsp.valid)   { msp := setMsp.payload }
}
```

- [ ] **Step 4: Run the unit test — expect PASS**

Run: `JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt "testOnly m68k040.exception.SystemStateBankSpec" 2>&1 | tee /tmp/ss_bank.log`
Expected: both tests PASS. (The whole project will NOT compile until Task 2 — that's expected.)

- [ ] **Step 5: Commit**

```bash
git add src/main/scala/m68k040/exception/SystemState.scala src/test/scala/m68k040/exception/SystemStateBankSpec.scala
git commit -F - <<'EOF'
systemstate: three-way (S,M) A7 banking (USP/ISP/MSP) + msp bank

Rename ssp->isp (M=0 supervisor bank), add msp (M=1 bank) + setMsp/setIsp,
m accessor (system-byte bit4), a7 = Mux(s, Mux(m, msp, isp), usp), writeA7
routes by committed (S,M). SystemStateBankSpec covers the truth table + routing.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
```

---

## Task 2: `ExceptionUnit` — `(S,M)` entry/RTE banking (snapshot model) + rename to green

This restores compilation and gives correct fault/trap/RTE M-banking using the existing snapshot mechanism (same robustness as today's `ssp`, now M-aware). `ss.writeA7` stays idle here; the live-coherent upgrade is Task 3.

**Files:**
- Modify: `src/main/scala/m68k040/exception/ExceptionUnit.scala` (`:385`, `:393`, `:454`, `:528-539`)
- Modify: any other `src/main` reference to `ss.ssp`/`setSsp` (grep)
- Modify: `src/test/scala/m68k040/lockstep/ExecuteLockStepSpec.scala` seed sites (`:473`, `:659`, `:1170`) + any other test `ss.ssp`

- [ ] **Step 1: Entry frame-base uses the current supervisor bank (line 385)**

Replace line 385 with:

```scala
        // new SP = current supervisor stack (M ? MSP : ISP) - frame size. M is PRESERVED
        // across fault/trap entry (the &0x3f mask in E_REDIR keeps bit4), so the
        // post-stack SP is written back to this SAME bank.
        val supSp = Mux(ss.m, ss.msp, ss.isp)
        val nb = Mux(is7, supSp - 60, Mux(is2, supSp - 12, supSp - 8))
```

- [ ] **Step 2: RTE frame-base read (line 393)** — replace `frameBase := ss.ssp` with:

```scala
        frameBase := Mux(ss.m, ss.msp, ss.isp)   // RTE reads the current supervisor stack
```

- [ ] **Step 3: Entry post-stack SP write routed by M (line 454)** — replace `ss.setSsp.valid := True; ss.setSsp.payload := frameBase` with:

```scala
      when(ss.m) { ss.setMsp.valid := True; ss.setMsp.payload := frameBase }
      .otherwise { ss.setIsp.valid := True; ss.setIsp.payload := frameBase }
```

- [ ] **Step 4: RTE SP restore + re-bank A7 by restored (S,M) (lines 528-539)** — replace the `newSsp`/`setSsp`/`obsA7` block with:

```scala
      val newSsp = frameBase + Mux(popIs7, U(60, 32 bits), Mux(popIs2, U(12, 32 bits), U(8, 32 bits)))
      when(ss.m) { ss.setMsp.valid := True; ss.setMsp.payload := newSsp }
      .otherwise { ss.setIsp.valid := True; ss.setIsp.payload := newSsp }
      redirectValid := True
      redirectPc    := popPc
      obsFire    := True
      obsPc      := popPc
      obsSysByte := popSr(15 downto 8)
      // A7 after RTE = restored-(S,M) bank. popSr(13)=S, popSr(12)=M. For Slice A
      // (fault/trap), M is unchanged so the popped bank == the restored supervisor bank
      // and obsA7 resolves to Mux(S, newSsp, usp) — identical to the old behavior.
      val poppedSameBank = (popSr(12) === ss.m)
      val rsupBank = Mux(popSr(12), ss.msp, ss.isp)
      obsA7 := Mux(popSr(13), Mux(poppedSameBank, newSsp, rsupBank), ss.usp)
```

- [ ] **Step 5: Rename remaining `ss.ssp`/`setSsp` in `src/main` and the test seeds**

Run: `grep -rn "\.ssp\b\|setSsp\b" src/main/ src/test/`
- `src/main`: rename any straggler (e.g. `FullCoreSynth`/backend wiring) `ss.ssp`→`ss.isp`, `setSsp`→`setIsp`.
- `src/test`: rename the supervisor-SP seeds (`ExecuteLockStepSpec.scala:473,659,1170` `dut.rob.logic.exc.ss.ssp #= ...` → `...ss.isp #= ...`) and any other test `ss.ssp`. (Reset M=0 → supervisor bank = ISP.)

- [ ] **Step 6: Compile the whole project + run an exception regression subset**

Run: `JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt "compile" "Test/compile" 2>&1 | tee /tmp/compile_t2.log`
Expected: BUILD SUCCESS (no `ssp` errors anywhere).
Run: `JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt "testOnly m68k040.lockstep.ExecuteLockStepSpec -- -z \"RTE\"" 2>&1 | tee /tmp/lockstep_rte_t2.log`
Expected: PASS — fault/trap/RTE lock-step unchanged (M=0 → supervisor bank = ISP, behaves exactly as the old `ssp`).

- [ ] **Step 7: Commit**

```bash
git add src/main/scala/m68k040/exception/ExceptionUnit.scala src/test/scala/m68k040/lockstep/ExecuteLockStepSpec.scala
# plus any other src/main or src/test files renamed in Step 5
git commit -F - <<'EOF'
exc: (S,M) entry/RTE stack banking (MSP/ISP) + ssp->isp rename

Entry frame base + post-stack SP + RTE restore use the current supervisor bank
Mux(m,msp,isp) (M preserved by the existing &0x3f mask); RTE re-banks A7 by the
restored (S,M). Snapshot model unchanged (ss.writeA7 still idle); the live-
coherent upgrade is the next task. Interrupt-with-M=1 is Slice B; M=0 interrupts
+ all fault/trap/RTE lock-step unchanged.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
```

---

## Task 3: Live-coherent committed banks (commit-time A7 → `ss`) — the heavy piece

Make `ss.usp/isp/msp` continuously mirror the live committed A7 (spec §4.1) so a MOVE-to-SR (S,M) switch loads a correctly-preserved bank. THIS IS THE RISKIEST TASK (hot rename/PRF path + commit→`ss` timing vs the exception FSM). Mechanism mapped from `rename/RatTable.scala`, `execute/regfile/`, `top/FullCoreSynth.scala`.

**Files:**
- Modify: `src/main/scala/m68k040/rename/RatTable.scala` (expose committed arch-15 mapping)
- Modify: `src/main/scala/m68k040/rename/RenameStage.scala` (allocate the committed-15 read; OR do it in BackendWiring)
- Modify: `src/main/scala/m68k040/exception/ExceptionUnit.scala` (add `committedA7In` input; drive `ss.writeA7`; settle `frameBase`)
- Modify: `src/main/scala/m68k040/top/FullCoreSynth.scala` (`BackendWiringPlugin`, near `:205-213`) (new int-PRF read port → `committedA7In`)
- Modify: `src/test/scala/m68k040/lockstep/ExecuteLockStepSpec.scala` (its own backend-wiring mirror, if it duplicates the `excA7` wiring — drive `committedA7In` there too)

- [ ] **Step 1: Expose the committed arch-15 phys mapping in `RatTable`**

In `src/main/scala/m68k040/rename/RatTable.scala`, the committed mapping lives in `commReg` (read API muxes spec/committed via `location`). Add a committed-only accessor for a fixed arch reg, e.g.:

```scala
  // Committed (retirement) phys mapping for a specific arch reg, ignoring any
  // in-flight speculative rename (used to read the COMMITTED A7 value).
  def committedPhys(arch: Int): UInt = commReg(arch)
```

(If `commReg` is private to the elaboration scope, expose it via a `val committedPhysA7 = commReg(15)` published on the component’s `io`/logic so the top wiring can reach it. Match the file’s existing visibility idiom.)

- [ ] **Step 2: Add the `committedA7In` input + drive `ss.writeA7` in `ExceptionUnit`**

In `src/main/scala/m68k040/exception/ExceptionUnit.scala`:
- Add an input near the other A7 wiring (around `:199`):

```scala
  // Live committed A7 value (PRF[committedRAT[15]]), wired from the top. Drives the
  // SystemState bank continuously so ss.usp/isp/msp mirror the architectural A7 of the
  // active (S,M) bank -> a MOVE-to-SR bank switch loads a correctly-preserved bank.
  val committedA7In = UInt(32 bits); committedA7In := U(0, 32 bits)  // idle default (drivable for standalone tests)
```
- Replace the idle `ss.writeA7` default (`:216`) with a continuous drive:

```scala
  ss.writeA7.valid  := True
  ss.writeA7.payload := committedA7In
```
- The FSM's `setIsp`/`setMsp`/`setUsp` already fire on serializing cycles and win (later-`when` in `SystemState`). The continuous `writeA7` keeps the ACTIVE bank live every other cycle.

- [ ] **Step 3: Settle `frameBase` against the live bank**

The entry path currently captures `frameBase` on the `IDLE`→entry edge (`:386`, `goto(E_DRAIN)`). With the live readback (1-cycle PRF latency), capture `frameBase` on the `E_DRAIN`→`E_STORE` edge instead, from the now-settled `ss.supBank`. In `E_DRAIN` (`:410`):

```scala
    E_DRAIN.whenIsActive {
      when(sqDrained) {
        // Recompute the frame base from the SETTLED live supervisor bank (the readback
        // has caught up to the committed A7 of the faulting point; younger ops squashed).
        val supSp = Mux(ss.m, ss.msp, ss.isp)
        frameBase := Mux(curIs7, supSp - 60, Mux(curIs2, supSp - 12, supSp - 8))
        goto(E_STORE)
      }
    }
```
(Keep the `:385-386` capture as the initial value; this recompute makes it authoritative once settled. `curIs7`/`curIs2` are the latched format flags — confirm their names at `:360-361`.)

- [ ] **Step 4: Wire the committed-A7 read in the top + harness**

In `src/main/scala/m68k040/top/FullCoreSynth.scala` `BackendWiringPlugin` (near the `excA7` write at `:205-213`):

```scala
  // Live committed A7 readback: a new int-PRF read port addressed by the committed
  // arch-15 phys mapping, fed to the exception unit so its SystemState banks mirror
  // the architectural A7.
  during setup {
    a7CommittedRead = host[m68k040.execute.regfile.IntRegFileService].newRead()
  }
  // in build/logic:
  a7CommittedRead.addr := host[RenameStagePlugin]....committedPhysA7   // committed phys of arch-15
  exc.committedA7In := a7CommittedRead.data.asUInt
```
(Use the actual rename plugin accessor from Step 1 and the actual `IntRegFileService` from `BackendWiringPlugin`'s existing usage. The standalone/lock-step harness `ExecuteLockStepSpec` mirrors this backend wiring — replicate the same `committedA7In` drive there, or default it inert if that DUT seeds A7 directly; ensure `exc.committedA7In` is driven so the banks track.)

- [ ] **Step 5: Compile + the delicate verification (existing a7/exception lock-step MUST stay green)**

Run: `JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt "compile" "Test/compile" 2>&1 | tee /tmp/compile_t3.log` → BUILD SUCCESS.
Run, each in its own JVM (the a7-tracking interaction is the risk — `commitObs.a7 := RegNext(exc.ss.a7)` now tracks live; confirm no off-by-one vs the whitebox `a7Run` fold):

```
JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt "testOnly m68k040.lockstep.ExecuteLockStepSpec -- -z \"RTE\""   2>&1 | tee /tmp/t3_rte.log
JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt "testOnly m68k040.lockstep.ExecuteLockStepSpec -- -z \"bsr\""   2>&1 | tee /tmp/t3_bsr.log   # call/return: heavy A7 push/pop -> exercises live banks
JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt "testOnly m68k040.lockstep.ExecuteLockStepSpec -- -z \"pea\""   2>&1 | tee /tmp/t3_pea.log   # A7-predecrement stores
JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt "testOnly m68k040.lockstep.ExecuteLockStepSpec -- -z \"trap\""  2>&1 | tee /tmp/t3_trap.log
```
Expected: ALL PASS, 0 diverged. If `a7` diverges by one step, the readback/`frameBase` settle timing or the `RegNext(exc.ss.a7)` phase needs adjustment (this is the crux — iterate here, do NOT relax the test). Re-run the failing subset ×2 (seed flake rule).

- [ ] **Step 6: Commit**

```bash
git add src/main/scala/m68k040/rename/RatTable.scala src/main/scala/m68k040/rename/RenameStage.scala src/main/scala/m68k040/exception/ExceptionUnit.scala src/main/scala/m68k040/top/FullCoreSynth.scala src/test/scala/m68k040/lockstep/ExecuteLockStepSpec.scala
git commit -F - <<'EOF'
exc: live-coherent committed A7 banks (commit-time A7 -> ss.writeA7)

New committed-RAT read of arch-15 + a new int-PRF read port feed the live
committed A7 into ExceptionUnit.committedA7In, which drives ss.writeA7 every
cycle (routed by S,M) so ss.usp/isp/msp mirror the architectural A7 of the active
bank. Entry frameBase recomputed from the settled live bank on E_DRAIN->E_STORE.
Fixes the pre-existing snapshot staleness; makes MOVE-to-SR bank switching load a
preserved bank. All existing a7/exception/call-return lock-step green.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
```

---

## Task 4: Oracle + comparator plumbing for MSP/ISP

**Files:**
- Modify: `tools/musashi/musashi_run.cpp:209-223`
- Modify: `src/test/scala/m68k040/oracle/OracleStep.scala`
- Modify: `src/test/scala/m68k040/lockstep/CommitObservation.scala`
- Modify: `src/test/scala/m68k040/lockstep/LockStep.scala:39-41`

- [ ] **Step 1: Emit MSP/ISP in the oracle trace** — extend the per-step `fprintf` (lines 209-223) with `msp=`/`isp=`:

```cpp
                " a4=0x%08x a5=0x%08x a6=0x%08x a7=0x%08x"
                " msp=0x%08x isp=0x%08x\n",
                ... ,
                ref.get_reg(MusashiRef::REG_A6), ref.get_reg(MusashiRef::REG_A7),
                ref.get_reg(MusashiRef::REG_MSP), ref.get_reg(MusashiRef::REG_ISP));
```
(`REG_MSP`/`REG_ISP` already exist + are mapped: `m68k_ref.h:52-54`, `m68k_ref.cpp:269-270`.)

- [ ] **Step 2: Rebuild + install the oracle** — Run: `cd tools/musashi && make all 2>&1 | tee /tmp/musashi_build.log; cd -`
Expected: builds `musashi_run` + `install` copies it to `~/.cache/m68k-core-040-ooo/musashi_run` (the harness-preferred path, `Musashi.scala:11-16`).

- [ ] **Step 3: `OracleStep` fields + parse** — in `OracleStep.scala`:

```scala
final case class OracleStep(pc: Long, sr: Int, d: Vector[Long], a: Vector[Long],
                            msp: Long = -1L, isp: Long = -1L) {
  def ccr: Int = sr & 0x1f
}
```
and in `parseLine`:
```scala
    } yield OracleStep(pc, sr.toInt & 0xffff, d, a,
                       msp = kv.get("msp").map(hex).getOrElse(-1L),
                       isp = kv.get("isp").map(hex).getOrElse(-1L))
```

- [ ] **Step 4: `CommitObservation` fields + comparator** — in `CommitObservation.scala`:

```scala
    a7:           Long = -1L,
    msp:          Long = -1L,
    isp:          Long = -1L
```
and in `LockStep.scala` after the `a7` compare (line 41):
```scala
        else if (c.msp >= 0 && s.msp >= 0 && (c.msp & 0xffffffffL) != (s.msp & 0xffffffffL))
          Some(f"msp: dut=0x${c.msp & 0xffffffffL}%08x oracle=0x${s.msp & 0xffffffffL}%08x")
        else if (c.isp >= 0 && s.isp >= 0 && (c.isp & 0xffffffffL) != (s.isp & 0xffffffffL))
          Some(f"isp: dut=0x${c.isp & 0xffffffffL}%08x oracle=0x${s.isp & 0xffffffffL}%08x")
```

- [ ] **Step 5: Compile-only check** — Run: `JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt "Test/compile" 2>&1 | tee /tmp/tc_t4.log`
Expected: BUILD SUCCESS (DUT not yet surfacing msp/isp → -1 → inert → no behavior change).

- [ ] **Step 6: Commit**

```bash
git add tools/musashi/musashi_run.cpp src/test/scala/m68k040/oracle/OracleStep.scala src/test/scala/m68k040/lockstep/CommitObservation.scala src/test/scala/m68k040/lockstep/LockStep.scala
git commit -F - <<'EOF'
lockstep: thread MSP/ISP through oracle trace -> OracleStep -> comparator

Emit msp=/isp= in the Musashi per-step trace; parse in OracleStep; add msp/isp to
CommitObservation (default -1 = inert) and compare in LockStep when both surface
them. DUT surfacing lands in Task 5. Rebuilt+installed musashi_run.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
```

---

## Task 5: DUT surfaces committed MSP/ISP per step (now live-coherent)

Because Task 3 made `ss.msp/ss.isp` live, sample them directly at each commit.

**Files:**
- Modify: `src/test/scala/m68k040/lockstep/WhiteboxCapture.scala` (`NormRec`/`ExcRec` + `onCommit` + the exc-record method + `toObservations`)
- Modify: `src/test/scala/m68k040/lockstep/ExecuteLockStepSpec.scala` (sample at the `onCommit` sites `:418`,`:608`; optional MSP seed)

- [ ] **Step 1: Thread MSP/ISP through the whitebox records** — in `WhiteboxCapture.scala`:

```scala
  private final case class NormRec(pc: Long, sysByte: Int, a7Static: Long, wb: Wb, emit: Boolean,
                                   msp: Long = -1L, isp: Long = -1L) extends Rec
  private final case class ExcRec(pc: Long, sysByte: Int, a7: Long, foldNzvc: Int, setCcr5: Int = -1,
                                  msp: Long = -1L, isp: Long = -1L) extends Rec
```
Extend `onCommit` (`:58`) + the exc-record push method to take `msp`/`isp` (default -1) and store into the records (`:70`,`:76`). In `toObservations` bind `msp`/`isp` from the matched `case` (`:96`,`:116`) and pass into BOTH `CommitObservation(...)` constructions (`:111-115`, `:127-130`):

```scala
            sr = ((sysByte & 0xff) << 8) | (ccr & 0x1f), a7 = a7Run, msp = msp, isp = isp))
   ...
            sr = ((sysByte & 0xff) << 8) | (ccr & 0x1f), a7 = a7, msp = msp, isp = isp))
```

- [ ] **Step 2: Sample `ss.msp`/`ss.isp` at the commit sites** — in `ExecuteLockStepSpec.scala`, at each `handle.onCommit(...)` (`:418`, `:608`):

```scala
              sysByte = c.sysByte.toInt & 0xff, a7 = c.a7.toLong & 0xffffffffL,
              msp = dut.rob.logic.exc.ss.msp.toLong & 0xffffffffL,
              isp = dut.rob.logic.exc.ss.isp.toLong & 0xffffffffL)
```

- [ ] **Step 3: Run an existing exception subset — expect PASS (no new divergence)**

Run: `JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt "testOnly m68k040.lockstep.ExecuteLockStepSpec -- -z \"RTE\"" 2>&1 | tee /tmp/t5_rte.log`
Expected: PASS. M=0 programs: `ss.msp` stays 0 (= oracle MSP at boot, given matched seeds), `ss.isp` tracks A7. If `isp` diverges, the live-coherence (Task 3) or seed isn't right — fix before continuing.

- [ ] **Step 4: Commit**

```bash
git add src/test/scala/m68k040/lockstep/WhiteboxCapture.scala src/test/scala/m68k040/lockstep/ExecuteLockStepSpec.scala
git commit -F - <<'EOF'
lockstep harness: sample committed MSP/ISP per step (live-coherent banks)

Thread msp/isp through NormRec/ExcRec -> CommitObservation, sampled from
dut...exc.ss.msp/.isp at each onCommit (valid now the banks are live-coherent).

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
```

---

## Task 6: M-bit lock-step integration tests

Directed lock-step programs exercising three-stack banking, asserting the CORRECT behavior vs Musashi. Name them with a `"M-bit"` token (`-z "M-bit"`).

**Files:** Modify `src/test/scala/m68k040/lockstep/ExecuteLockStepSpec.scala`.

- [ ] **Step 1: Write the M-bit banking test** — locate an existing supervisor trap/RTE lock-step test in this file (search `trap #` / `"lock-step:`), COPY its exact scaffolding (VBR/vector table/handler bytes/`stopPc`/`Musashi.assembleAndRun(initialSr=...)`/`LockStep.compare`), and substitute this program (start supervisor M=0, set M=1, write MSP, trap on MSP, RTE):

```scala
  test("lock-step: M-bit MSP/ISP banking (set M, write MSP, trap, RTE)", VerilatorTest) {
    val program = Seq(
      "move.l #0x00090000,%a0",
      "move.w #0x2700,%sr",       // S=1,M=0,I=7 -> A7=ISP
      "move.l %a0,%sp",           // ISP := 0x00090000
      "move.w #0x3700,%sr",       // S=1,M=1   -> A7=MSP (preserved/loaded)
      "move.l #0x000A0000,%sp",   // MSP := 0x000A0000
      "trap #1",                  // vector 33, format-$0 frame on MSP
      "nop"                       // (handler at the vector RTEs; see copied scaffolding)
    ).mkString("\n")
    // Seed MSP to match Musashi (see Step 2). Assert LockStep.compare(...).ok == true
    // (0 diverged); the inactive ISP (=0x00090000) is compared every step.
  }
```
NOTE: do NOT invent a harness; reuse the file's existing supervisor-exception pattern. Confirm each mnemonic is supported by `ProgramAssembler` (grep the file — MOVE-to-SR, `move.l ...,%sp`, `trap`, `rte` are all used elsewhere).

- [ ] **Step 2: Seed MSP on both sides to match**

For the inactive-bank compare to hold at the M-switch step, the DUT's boot `ss.msp` and Musashi's boot MSP must match. In the test setup, seed `dut.rob.logic.exc.ss.msp #= <V>`. Musashi has no `--initial-msp` flag — so EITHER (a) choose a program that writes MSP before any step compares it (the program above writes MSP immediately after switching, so the only exposed step is the switch cycle itself — seed `ss.msp` to whatever Musashi resets MSP to, typically 0, and verify), OR (b) add a `--initial-msp` flag to `musashi_run.cpp` (set `REG_MSP` after reset) + plumb it through `Musashi.assembleAndRun`. Prefer (a); fall back to (b) and document if (a) flakes on the switch step.

- [ ] **Step 3: Run — expect PASS** — Run: `JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt "testOnly m68k040.lockstep.ExecuteLockStepSpec -- -z \"M-bit\"" 2>&1 | tee /tmp/t6_mbit.log`
Expected: PASS, 0 diverged; trace shows A7 on MSP after the trap push, ISP=0x00090000 preserved. If `msp`/`isp`/`a7` diverges, the detail names the field — fix the RTL (Task 3) / seeding, do NOT relax the test.

- [ ] **Step 4: Symmetric M=0 trap test** — add `"lock-step: M-bit ISP-only trap (M stays 0)"` (identical scaffolding, M=0 throughout, frame on ISP), asserting 0 diverged — proves the M=0 path is unchanged.

- [ ] **Step 5: Run both ×2 seeds** — re-run Step 3 twice. Expected PASS both. Cross-line/page flakes: repro on baseline `8bdbb0c` (branch base) before attributing — the M-bit cone must be stable.

- [ ] **Step 6: Commit**

```bash
git add src/test/scala/m68k040/lockstep/ExecuteLockStepSpec.scala
# + tools/musashi/musashi_run.cpp and src/test/.../Musashi.scala if Step 2 option (b) was used
git commit -F - <<'EOF'
test(lockstep): M-bit MSP/ISP banking step-for-step vs Musashi

Set M via MOVE-to-SR (A7 follows MSP), write MSP, trap frame on MSP, RTE; +
symmetric M=0 ISP-only trap. Inactive bank compared every step. 0-diverged x2.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
```

---

## Task 7: Full regression + honest synth gate

**Files:** none (verification); update memory after.

- [ ] **Step 1: Exception/interrupt/MMU lock-step regression (subsets, ×2 each)** — each in its OWN JVM, to a file (never batch — OOM). Verify exact spec names first: `ls src/test/scala/m68k040/lockstep/ src/test/scala/m68k040/oracle/`.

```
JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt "testOnly m68k040.lockstep.ExecuteLockStepSpec -- -z \"RTE\""   2>&1 | tee /tmp/reg_rte.log
JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt "testOnly m68k040.lockstep.ExecuteLockStepSpec -- -z \"trap\""  2>&1 | tee /tmp/reg_trap.log
JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt "testOnly m68k040.lockstep.ExecuteLockStepSpec -- -z \"bsr\""   2>&1 | tee /tmp/reg_bsr.log
JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt "testOnly m68k040.lockstep.InterruptEntrySpec"                   2>&1 | tee /tmp/reg_irq.log
JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt "testOnly m68k040.lockstep.MmuFaultOracleSpec"                   2>&1 | tee /tmp/reg_mmu.log
JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt "testOnly m68k040.lockstep.ItlbFaultOracleSpec"                  2>&1 | tee /tmp/reg_itlb.log
```
Expected: all PASS. Pre-existing ITLB flake → repro on baseline before attributing.

- [ ] **Step 2: fastTest** — Run: `JAVA_OPTS=-Xmx10g timeout 1800 ~/sbt/bin/sbt fastTest 2>&1 | tee /tmp/fasttest.log` (confirm the alias: `grep -n "fastTest" build.sbt`).
Expected: all PASS (incl. SystemStateBankSpec + the M-bit tests if in a fastTest-included spec).

- [ ] **Step 3: Honest post-route synth gate** — controller runs `synth/impl_FullCore.tcl` (one Vivado at a time). Compare WNS/FMax vs the master baseline (~247 @ `69f1779`). **Gate ≥200 MHz.** This task adds an int-PRF read port + a committed-RAT read (hot rename/regfile cone) — a regression is plausible; report it honestly. If below ~230, name the limiter (likely the new int-PRF read port) in the report. Do NOT merge below 200; do NOT const-fold/relax to recover.

- [ ] **Step 4: Update memory** — append to `exception-subsystem.md`: MSP/ISP Slice A (three-stack banking + live M-bit + live-coherent banks via committed-A7 readback; OracleStep extended with MSP/ISP; Slice B throwaway-frame deferred), with the lock-step result + honest synth number + whether the latent snapshot-staleness fix changed any FMax. Update its frontmatter description.

---

## Self-review notes (controller)
- **Spec coverage:** §5.1 → Task 1; §5.2 → Task 2; §4.1 + §5.4 → Task 3; §5.3 (oracle/comparator) → Task 4; §5.3 (DUT sample) → Task 5; §7.1 → Task 1; §7.2 → Tasks 5,6; §7.3 → Task 7. All mapped.
- **Type consistency:** `setIsp`/`setMsp`/`isp`/`msp`/`m`/`supBank` (Task 1) used identically in Tasks 2-3; `committedA7In` defined in Task 3 Step 2 + wired Step 4; `OracleStep.msp/isp`, `CommitObservation.msp/isp`, `NormRec/ExcRec.msp/isp` consistent across Tasks 4-5; comparator field names match.
- **Risk concentration:** Task 3 (live-coherent) is the crux — the commit→`ss` readback timing vs `commitObs.a7 := RegNext(exc.ss.a7)` + the whitebox `a7Run` fold. Its gate (Step 5) is the existing a7/call-return/exception lock-step staying green; iterate the readback/settle timing there, never relax the tests.
- **Out of scope (do NOT implement):** interrupt-with-M=1 throwaway frame ($1), RTE-from-$1 loop, MOVEC MSP/ISP. Slice B.
