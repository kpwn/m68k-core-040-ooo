# IssueQueue (Execute Slice 3b) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A 2-wide, 16-slot NaxRiscv-style out-of-order IssueQueue: slot-indexed dependency triggers, compaction, depend-on-READ readiness, static-latency-1 wakeup (back-to-back), age-ordered 2-port issue — verified standalone with a test source→sink.

**Architecture:** Built in two tasks to de-risk the intricate parts incrementally. **Task 1** = the slot array + 2-wide push + compaction + age-ordered select + slot-free + back-pressure + flush, with **every pushed µop immediately ready** (no dependencies) — this pins the structural skeleton (compaction + select, the trickiest mechanics) first. **Task 2** = the dependency machinery: a folded per-reg-class scoreboard (physreg→producer + in-flight), depend-on-READ trigger initialization, and static-latency-1 wakeup that clears triggers so dependents issue the next cycle.

**Tech Stack:** SpinalHDL 1.14.1 / Scala 2.13 / sbt `~/sbt/bin/sbt` (NOT on PATH) / Verilator. Tests use plain `SimConfig.withVerilator` (no multi-write Mem here → `M68kSim` not required, but harmless; use `SimConfig.withVerilator`). **Implementation reference (consult directly):** `/home/qwertyoruiop/m68k-ooo-v2/thirdparty/NaxRiscv/src/main/scala/naxriscv/frontend/IssueQueue.scala` (slots `:56-73`, compaction `:76-100`, events/wakeup `:102-112`, push-ready `:133-138`, select `:115-127`) and `DispatchPlugin.scala` (`:198-214` trigger init, `:325-336` static wakeup). Our `RenamedUop` (`src/main/scala/m68k040/rename/RenamedUop.scala`) supplies the µop fields.

**Branch:** `feat/issue-queue` (created; spec committed).

**Param constants:** `slotCount=16`, `wayCount=2`, `lineCount=8`, `robIdW=6`, int physreg width 6 (48), flag physreg width 4 (16). Two ALU issue ports (`selCount=1`, two parallel ALU lanes).

**The contract Task 2's wakeup assumes (document it):** the 3a EU has fixed latency 1 — a dependent issued exactly 1 cycle after its producer reads the producer's result via the EU bypass. So a producer selected at cycle T must make its dependents *selectable* at T+1: the wakeup broadcasts the producer's slot the cycle it issues, the trigger register clears, dependents are ready at T+1.

---

### Task 1: Slot array + push/compaction + age-ordered 2-port select (all-ready)

**Files:**
- Create: `src/main/scala/m68k040/execute/iq/IqContext.scala`
- Create: `src/main/scala/m68k040/execute/iq/IssueQueuePlugin.scala`
- Create: `src/test/scala/m68k040/execute/iq/IqSourcePlugin.scala`, `IqSinkPlugin.scala`
- Create: `src/test/scala/m68k040/execute/iq/IssueQueueSpec.scala`

- [ ] **Step 1: Payload bundles + service**

Create `src/main/scala/m68k040/execute/iq/IqContext.scala`:

```scala
package m68k040.execute.iq

import m68k040.rename.RenamedUop
import spinal.core._
import spinal.lib._

/** A µop in the issue queue / issued to an EU: the renamed µop + its ROB id. */
case class IqContext() extends Bundle {
  val uop   = RenamedUop()
  val robId = UInt(6 bits)   // robIdW = log2Up(ROB_DEPTH=64)
}

/** IssueQueue ports (plain-wire service convention). push: up to 2/cycle (Stream
  * with slot1Valid). issue: 2 ALU ports (each a Stream the EU/sink drains). */
trait IssueQueueService {
  def push: Stream[Vec[IqContext]]   // Vec length 2
  def pushSlot1Valid: Bool           // is push.payload(1) a real second µop
  def issue: Vec[Stream[IqContext]]  // length 2 (two ALU ports)
  def flushPort: Bool
}
```

- [ ] **Step 2: IssueQueuePlugin — slots + push/compaction + select (NO dependencies yet)**

Create `src/main/scala/m68k040/execute/iq/IssueQueuePlugin.scala`. Implement the slot array with compaction and age-ordered 2-port select, with **every pushed µop immediately ready** (set its `triggers` to 0 on push — dependencies come in Task 2). Model it on NaxRiscv `IssueQueue.scala` (consult the file). Required structure + behavior:

- `slotCount=16`, `wayCount=2`, `lineCount=8`. Slot at `priority=line*2+way` has: `sel = Reg(Bool()) init False` (true = occupied ALU µop; `selCount=1`), `triggers = Reg(Bits(priority+1 bits)) init 0` (Task 2 uses it; keep the field now), `context = Reg(IqContext())`. `ready := sel && triggers(priority-1 downto 0) === 0` (with priority 0, ready := sel).
- **Push (2-wide, compacting):** `push.fire` when `push.valid && push.ready`. On fire, compact: `for line in 0..lineCount-2: slot(line) := slot(line+1)` (copy sel/context/triggers, `triggers >>= wayCount`); the new µops go into the last line: way0 = `push.payload(0)` (sel := True, triggers := 0), way1 = `push.payload(1)` (sel := pushSlot1Valid, triggers := 0). `push.ready` = the destination line(s) are empty after the prospective compaction — implement as NaxRiscv `:133-138` (a registered `readyReg` from the line-emptiness check); a correct-but-simple first cut: `push.ready := (number of occupied slots) <= slotCount - 2`. (Use an occupancy count `Reg` updated by `+allocated -issued` if that's simpler than the line check; either is fine as long as the back-pressure test passes.)
- **Select (2 age-ordered ALU ports):** compute `validReady = Vec(slot.ready)` over all 16 slots. Port 0 picks the **oldest** ready slot (lowest index): `sel0 = OHMasking.first(validReady.asBits)`. Port 1 picks the oldest ready slot EXCLUDING port 0's pick: `sel1 = OHMasking.first((validReady.asBits & ~sel0))`. Each selected slot drives `issue(k).valid := True`, `issue(k).payload := slot.context`. When `issue(k).fire` (sink ready), free the slot: `slot.sel := False` (and Task 2: broadcast its wakeup). (`OHMasking.first` / `MaskedFirst` — use the SpinalHDL API that compiles, like NaxRiscv's `OHMasking.firstV2`; semantics: lowest-set-bit one-hot.)
  - NOTE the age-order subtlety: slot 0 is oldest because compaction shifts everything down toward 0 on push. Issued slots free their `sel`; compaction (next push) shifts survivors down, preserving order. Confirm freed-then-compacted slots don't break age order — this is the crux to get right; lean on NaxRiscv's structure and the Task-1 tests.
- **Flush:** `when(flushPort) { all slot.sel := False; all triggers := 0 }`.
- Expose the `IssueQueueService` (plain ports: `push` Stream, `pushSlot1Valid` Bool, `issue` Vec(Stream,2), `flushPort` Bool) created in `during setup`; build the logic in `during build`.

- [ ] **Step 3: Test source + sink**

Create `src/test/scala/m68k040/execute/iq/IqSourcePlugin.scala` — drives `push` from top-level IO so the sim can push µops:
```scala
package m68k040.execute.iq

import m68k040.services.{} // none
import spinal.core._
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin

/** Drives the IQ push port from top-level IO (sim-controlled). */
class IqSourcePlugin extends FiberPlugin {
  val logic = during build new Area {
    val iq = host[IssueQueueService]
    // expose push controls as IO
    val pushValid  = in Bool()
    val slot1Valid = in Bool()
    val pushReady  = out Bool()
    val in0 = in(IqContext()); val in1 = in(IqContext())
    iq.push.valid := pushValid
    iq.push.payload(0) := in0
    iq.push.payload(1) := in1
    iq.pushSlot1Valid := slot1Valid
    pushReady := iq.push.ready
    iq.flushPort := False  // overridden by a flush IO below
    val flush = in Bool(); iq.flushPort := flush
  }
}
```
(If driving a whole `IqContext` bundle as one `in()` is awkward in sim, instead expose the few fields the tests set — robId, pdst/pdstValid, psrcA/psrcAValid, psrcB/psrcBValid, op, plus the flag read/write bits — as individual `in` signals and assemble `IqContext` inside. The tests below only need: robId, op, psrcA/psrcAValid, psrcB/psrcBValid, pdst/pdstValid, readsNzvc/writesNzvc/pNzvcSrc/pNzvcDst, readsX/writesX/pXSrc/pXDst. Pick whichever sim-drive style is clean; keep a helper to set a µop.)

Create `IqSinkPlugin.scala` — drains both issue ports, exposes per-port valid/robId + a programmable ready:
```scala
package m68k040.execute.iq

import spinal.core._
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin

class IqSinkPlugin extends FiberPlugin {
  val logic = during build new Area {
    val iq = host[IssueQueueService]
    val ready0 = in Bool(); val ready1 = in Bool()
    iq.issue(0).ready := ready0
    iq.issue(1).ready := ready1
    val v0 = out Bool(); val rob0 = out UInt(6 bits)
    val v1 = out Bool(); val rob1 = out UInt(6 bits)
    v0 := iq.issue(0).valid; rob0 := iq.issue(0).payload.robId
    v1 := iq.issue(1).valid; rob1 := iq.issue(1).payload.robId
  }
}
```

- [ ] **Step 4: Tests — width, back-pressure, flush (all-ready µops)**

Create `src/test/scala/m68k040/execute/iq/IssueQueueSpec.scala` with a Dut hosting `IssueQueuePlugin` + source + sink, and these tests. Write a `pushUop(dut, robId, ...)` sim helper matching your source's IO. Tests:
1. **2-wide independent issue, age order:** with both sink ports ready, push 6 independent µops (distinct robIds 0..5, no shared physregs, all `triggers=0`), then stop pushing. Assert: they issue two-per-cycle in ascending robId order (0&1, then 2&3, then 4&5). (Record `(v0,rob0,v1,rob1)` each cycle.)
2. **Back-pressure:** hold both sink ports NOT ready; push until `pushReady` deasserts (queue full at 16). Assert `pushReady` drops after 16 µops (8 pushes of 2). Then make sinks ready; assert it drains and `pushReady` re-asserts.
3. **Flush:** push 4 µops (sinks not ready so they sit in the queue), pulse `flush`, then with sinks ready assert NO µop issues (queue emptied); push 2 fresh µops and assert they issue normally.

Write the test bodies concretely (drive `pushValid/slot1Valid/in*` and read `v0/rob0/v1/rob1/pushReady` via `dut.<plugin>.logic.*`). Use `VerilatorTest` tag. Example skeleton for test 1:
```scala
  test("two independent uops issue per cycle in age order", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      dut.sink.logic.ready0 #= true; dut.sink.logic.ready1 #= true
      dut.src.logic.pushValid #= false; dut.src.logic.flush #= false
      cd.waitSampling(2)
      // push robIds 0..5 (two per cycle), all independent
      def push2(a: Int, b: Int): Unit = { /* set in0.robId=a, in1.robId=b, pushValid, slot1Valid; wait a fire */ }
      // ... push 0&1, 2&3, 4&5 ...
      // record issue events; assert order 0,1,2,3,4,5 two-per-cycle
    }
  }
```

- [ ] **Step 5: Run + iterate (consult NaxRiscv)**

Run: `~/sbt/bin/sbt "testOnly m68k040.execute.iq.IssueQueueSpec"` (allow 590000 ms). Iterate the compaction/select RTL until the 3 tests pass. The age-order + compaction interaction is the crux — consult `IssueQueue.scala:76-127`. Do NOT weaken assertions.

- [ ] **Step 6: Commit**

```bash
git add src/main/scala/m68k040/execute/iq/ src/test/scala/m68k040/execute/iq/
git commit -m "iq: slot array + 2-wide push/compaction + age-ordered select (all-ready)"
```

---

### Task 2: Scoreboard + depend-on-READ triggers + static-latency-1 wakeup

**Files:**
- Modify: `src/main/scala/m68k040/execute/iq/IssueQueuePlugin.scala`
- Modify: `src/test/scala/m68k040/execute/iq/IssueQueueSpec.scala`

- [ ] **Step 1: Scoreboard + trigger initialization on push**

Add to `IssueQueuePlugin` (consult `RfDependencyPlugin.scala:43-79` + `DispatchPlugin.scala:198-214`):
- Three scoreboards (int 48, NZVC 16, X 16): `physToRob : Mem(UInt(6 bits), depth)` (async read) + `busy : Reg(Bits(depth bits)) init 0`.
- `g2l(robId)` → current slot of that producer. Track `oldestRobId` (the robId at slot 0, advances as slots issue/compact) and `g2l = (robId - oldestRobId) resized to log2Up(slotCount)`. (Follow NaxRiscv `:321`; if tracking oldestRobId is awkward, an equivalent is to store the producer's CURRENT slot in the scoreboard and shift it on compaction — pick what's correct and testable.)
- **On push**, for each pushed slot: if it writes int pdst (`uop.pdstValid`) → `physToRob.write(pdst, robId)`, `busy(pdst) := True`; same for NZVC (`writesNzvc`→pNzvcDst) and X (`writesX`→pXDst).
- **Trigger init** for each pushed µop (replaces `triggers := 0`): start `trig := 0`; for each source it READS — int psrcA (`psrcAValid`), int psrcB (`psrcBValid && !useImm`), NZVC psrc (`readsNzvc`), X psrc (`readsX`) — if the corresponding `busy(psrc)`, set `trig(g2l(physToRob(psrc))) := True`. Handle the **intra-push slot0→slot1** case: if slot1 reads a physreg that slot0 writes this cycle, set slot1's trigger on slot0's (new) slot. Assign `triggers := trig` for the pushed slot. **Un-read sources contribute no trigger** (the depend-on-READ rule).

- [ ] **Step 2: Static-latency-1 wakeup**

Add the wakeup (consult `IssueQueue.scala:102-112`):
- Build an `events : Bits(slotCount)` vector: when a slot at index `i` issues (`issue(k).fire` selecting slot `i`), set `events(i)`. Also clear that producer's `busy` bit for its dst physreg(s) (so future pushes don't depend on it).
- Apply: `for j: when(events(j)) { for slots with priority>=j: triggers(j) := False }`. Because `triggers` is a register, the clear takes effect next cycle → dependents become ready and issue at T+1 (back-to-back).
- On **compaction**, shift the events accounting by `wayCount` consistently with the trigger shift (NaxRiscv `:103 moved`) so a producer that issued the same cycle as a push still wakes the right (shifted) dependents.

- [ ] **Step 3: Dependency tests**

Add to `IssueQueueSpec`:
4. **Dependency chain serializes at latency-1:** push a chain of 5 int ALU µops where µop k reads µop (k-1)'s dst (robId k, psrcA = pdst of k-1, all in int regfile) → assert exactly ONE issues per cycle, in order (robId 0,1,2,3,4 on consecutive cycles), proving the wakeup gives back-to-back-but-serialized issue.
5. **Depend-on-READ / CCR (the key test):** push 5 ALU µops that each WRITE NZVC+X (writesNzvc, writesX) but do NOT read them (readsNzvc=false, readsX=false), with INDEPENDENT int dsts and no int source deps → assert they issue at FULL WIDTH (2/cycle), NOT serialized — proving flag-write-only ops create no dependency. Then a contrast chain where each READS the prior's NZVC (readsNzvc=true, pNzvcSrc = prior's pNzvcDst) → assert it serializes (1/cycle).
6. **Age order with deps + mixed:** interleave an independent pair with a dependent pair; assert the independent ones issue while the dependent waits, in age order.

Write concrete bodies (drive the read/write flag + physreg fields via the source IO). Keep assertions strict.

- [ ] **Step 4: Run**

Run: `~/sbt/bin/sbt "testOnly m68k040.execute.iq.IssueQueueSpec"` → all (Task 1 + Task 2) pass. The CCR depend-on-READ test (5) is the headline: flag-write-only must NOT serialize. Iterate the scoreboard/trigger/wakeup RTL (consult NaxRiscv) until green; do not weaken asserts.

- [ ] **Step 5: Suites**

Run: `make SBT=~/sbt/bin/sbt test-fast` → all pass; report total.
Run: `make SBT=~/sbt/bin/sbt test-verilator` → all pass; report total.

- [ ] **Step 6: Commit**

```bash
git add src/main/scala/m68k040/execute/iq/IssueQueuePlugin.scala src/test/scala/m68k040/execute/iq/IssueQueueSpec.scala
git commit -m "iq: scoreboard + depend-on-READ triggers + static-latency-1 wakeup"
```

---

## Self-Review

**1. Spec coverage:**
- §3.1 slot array (sel/triggers/context) → Task 1 Step 2. ✓
- §3.2 push + compaction → Task 1 Step 2. ✓
- §3.3 scoreboard + depend-on-READ trigger init → Task 2 Step 1. ✓
- §3.4 static-latency-1 wakeup → Task 2 Step 2. ✓
- §3.5 age-ordered 2-port select → Task 1 Step 2. ✓
- §3.6 plugin/service + flush → Task 1 (service, flush). ✓
- §5 verification: width (T1.4 #1), back-pressure (#2), flush (#3), chain serializes (T2.3 #4), depend-on-READ/CCR (#5), age+mixed (#6). ✓ (All 7 spec behaviors covered; spec test 7 flush = T1 #3, spec tests 2/3/4/5 = T2 #4/#5/#6.)

**2. Placeholder scan:** Task 1 Step 2 and Task 2 describe the plugin RTL structurally with NaxRiscv line refs + exact required behavior rather than fully-verbatim code — deliberate, because faithfully replicating NaxRiscv's compaction/trigger/select bit-twiddling requires iterating against the (complete, concrete) tests with the reference open. The bundles, service, source/sink, and test intent are concrete. This is the right approach for porting intricate proven logic; the tests are the precise spec. Not a placeholder in the "vague requirement" sense.

**3. Type consistency:** `IqContext{uop: RenamedUop, robId: UInt(6)}`, `IssueQueueService{push, pushSlot1Valid, issue, flushPort}` consistent across plugin/source/sink/tests. `RenamedUop` field names (`psrcA/psrcAValid`, `psrcB/psrcBValid`, `useImm`, `pdst/pdstValid`, `pNzvcSrc/readsNzvc`, `pNzvcDst/writesNzvc`, `pXSrc/readsX`, `pXDst/writesX`) match the actual bundle. `slotCount=16`, `wayCount=2`, `robIdW=6` consistent. Issue ports `Vec(Stream[IqContext],2)`.

**Note:** Task 1's plugin is intentionally the harder structural piece (compaction+select); Task 2 layers the dependency machinery on top. If Task 1's compaction/age-order proves too entangled to land cleanly, fall back to consulting `IssueQueue.scala` line-by-line — it is the proven implementation of exactly this.
