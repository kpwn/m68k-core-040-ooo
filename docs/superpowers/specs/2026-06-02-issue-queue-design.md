# IssueQueue (Execute Slice 3b) — Design

**Status:** Draft for review
**Date:** 2026-06-02
**Parent spec:** `.../specs/2026-05-31-m68k-040-ooo-architecture-design.md` (ch 5 schedulers; invariant #2 FMax / no high fanout). Execute brainstorm `2026-06-02-alu-datapath-design.md` (Slice 3 = IQ + EUs + wakeup).
**Reference studied (the model to replicate):** NaxRiscv `frontend/IssueQueue.scala` (slots, slot-indexed `triggers`, compaction, age-ordered select), `frontend/DispatchPlugin.scala` (static-latency wakeup), `frontend/RfDependencyPlugin.scala` (physreg→producer scoreboard). Mechanisms quoted in the slice's exploration notes; implementation consults these files directly.
**Slice position:** the OoO scheduler — the intricate heart of execute. Built standalone (test source → sink) before 3a (EU) plugs into it. Order chosen by owner: 3b → 3a → 3c → 3d.

---

## 1. Purpose

A 2-wide, 16-slot out-of-order **IssueQueue** modeled on NaxRiscv: holds renamed µops, tracks operand readiness with **slot-indexed dependency triggers**, wakes dependents with **static-latency-1** broadcast (back-to-back issue), selects **age-ordered** ready µops, and issues up to two per cycle to two ALU-class ports. Readiness is **depend-on-READ**: a µop waits only on physical registers it reads — so flag-write-only ALU ops carry no CCR dependency and never serialize (the split-CCR payoff). Built and verified standalone with a test source and sink; 3a's EU and 3c's rename/ROB wiring come later.

## 2. Scope

**In:** `IssueQueuePlugin` (16 slots, 2-wide push, 2 ALU issue ports) with: slot array (`sel`/`triggers`/`context`); compaction on push (shift slots + reindex triggers); a folded minimal **scoreboard** (per reg-class physreg→producer + in-flight) deriving each pushed µop's triggers from the sources it reads (int srcA/srcB, NZVC-src iff `readsNzvc`, X-src iff `readsX`) plus the intra-push slot0→slot1 case; static-latency-1 wakeup (issued slot broadcast → registered trigger-clear → dependents issue next cycle); age-ordered `OHMasking.first` select per ALU port; `flush`; full-queue back-pressure. A test source plugin (pushes µops with physreg/robId/read-write fields) and sink plugin (records issue order/timing) + a directed spec.

**Out:** the EUs (3a), rename/ROB wiring + lock-step (3c), the branch issue port + branch EU (3d). Variable-latency (load/MUL) wakeup via the multi-cycle history pipeline (deferred until those EUs exist — only latency-1 now). Replay/speculation. The scoreboard here is the minimal readiness tracker, not the full `RfDependencyPlugin`.

## 3. Components

### 3.1 Slot array (NaxRiscv `IssueQueue.scala:56-73`)
`slotCount=16`, `wayCount=2` → `lineCount=8` rows × 2 ways. Slot at `priority = line*2 + way` holds:
- `sel : Bits(selCount)` — EU-class one-hot; `sel===0` means empty. (selCount=1 now: just ALU; BRANCH added in 3d.)
- `triggers : Bits(priority+1)` — slot-indexed dependency bit-vector; bit `j` set ⇒ depends on producer currently at slot `j`; `ready = triggers(priority-1 downto 0) === 0`. (MSB = keepalive, prevents reuse the cycle it fires.)
- `context : IqContext` — the µop payload carried to the EU (op, size, psrcA/psrcB + valids, useImm/imm, pdst + valid, pNzvcDst/writesNzvc, pXDst/writesX, pNzvcSrc/readsNzvc, pXSrc/readsX, dstArch, pc, robId). Essentially `RenamedUop` + `robId`.

### 3.2 Push + compaction (NaxRiscv `IssueQueue.scala:76-100`)
2-wide push at the tail line when `io.push.fire`. On push, **compact**: every line copies from the line above (`line ← line+1`), `triggers >>= wayCount` (reindex — a dependency on old slot `j` becomes slot `j-2`), `context`/`sel` shift down; the two new µops enter the last line with `triggers := event` (their initial dependency mask). Slot 0 is always the oldest ⇒ age-order is implicit in the slot index. `io.push.ready` = the next free line is empty (registered, one-cycle pipelined as NaxRiscv `:133-138`).

### 3.3 Scoreboard + trigger initialization (depend-on-READ) (NaxRiscv `RfDependencyPlugin.scala:43-79`, `DispatchPlugin.scala:198-214`)
Per reg-class (int/NZVC/X): `physToRob : Mem(robId, physDepth)` (async) + `busy : Reg(Bits(physDepth))`.
- **On push**, for each pushed slot writing a dst (int pdst / NZVC pNzvcDst / X pXDst per its write bits): `physToRob[pdst] := robId`; set `busy[pdst]`.
- **Trigger init** for a pushed µop: for each source it **reads** — int srcA (if `psrcAValid`), int srcB (if `psrcBValid` and not immediate), NZVC-src (iff `readsNzvc`), X-src (iff `readsX`) — if that physreg is `busy`, set the trigger bit at the producer's current slot `g2l(physToRob[psrc])` (`g2l(robId) = (robId − oldestRobId) resized`, NaxRiscv `:321`). Un-read sources set no trigger ⇒ **flag-write-only ops get no flag trigger**. Handle the **intra-push** case (slot1 reads slot0's dst → trigger on slot0).
- **On wakeup** (a producer issues): clear `busy[its pdst]`.

### 3.4 Wakeup — static latency 1 (NaxRiscv `IssueQueue.scala:102-112`, `DispatchPlugin.scala:325-336` simplified to latency 1)
When a slot is selected (issues) at cycle T, its slot index enters the `events` vector; for every waiting slot, `when(events(j)) triggers(j) := False`. Trigger registers update at T → dependents `ready` at T+1 → issue at T+1 = **back-to-back (latency 1)**. The `events` vector is shifted by `wayCount` on compaction (NaxRiscv `:103 moved`) to track reindexing. (Multi-cycle history pipeline for latency>1 deferred — only latency-1 ALU now, so the broadcast is the issue event itself.)

### 3.5 Select — age-ordered (NaxRiscv `IssueQueue.scala:115-127`)
For each of the 2 ALU issue ports: `slotsValid = ready && sel(ALU)` over the port's slot subset (strided by `eventFactor`/`eventOffset` for the 2 parallel ALU lanes); `selOh = OHMasking.first(slotsValid)` (lowest index = oldest). The two ports pick the two oldest ready ALU slots (distinct). Selected slots drive `io.issue(k)` (Stream of `IqContext`) and fire their wakeup + free their slot (`sel := 0`).

### 3.6 Plugin / service
`IssueQueuePlugin extends FiberPlugin` (+ an `IssueQueueService` exposing the push port and the 2 issue ports as plain Stream — our convention). `flush: Flow(NoData)` clears all `sel`/`triggers`/`busy`. Resolves nothing in 3b (test source drives push, sink drains issue); 3c wires rename→push and EU←issue.

## 4. Data flow
`test source → io.push(Vec(IqPush,2)) → [compaction + trigger init via scoreboard] → slots → [wakeup clears triggers] → age-select → io.issue(Vec(Stream[IqContext],2)) → test sink`. `flush` squashes.

## 5. Verification (test source → sink; directed)
A `IqSourcePlugin` drives `io.push` (a Vec of µop descriptors: op/size/physregs/read-write bits/robId) and `io.push` handshake; a `IqSinkPlugin` accepts `io.issue` (always ready) and records, per cycle, which robIds issued. Tests:
1. **Independent issue width:** push N independent ALU µops (no shared physregs) → two issue per cycle (2-wide), in age (robId) order.
2. **Dependency chain serializes at latency-1:** push a chain (each reads the prior's dst) → issue exactly one per cycle, in order (back-to-back via the wakeup).
3. **Depend-on-READ / CCR:** push a chain of flag-WRITE-only ops (each writes NZVC+X, none reads them; int dsts independent) → they issue at full width (NO serialization) — proves write-only flags create no dependency. Contrast: a chain where each READS the prior's NZVC (e.g. modeled ADDX-like readsX/readsNzvc) serializes.
4. **Age order:** a younger ready µop does not issue before an older ready µop competing for the same port.
5. **Mixed:** independent + dependent interleaved issue in the expected cycles.
6. **Back-pressure:** fill the queue → `io.push.ready` deasserts; drains as slots issue.
7. **Flush:** mid-flight flush → queue empties; subsequent pushes start clean; no stale issue.

`make test-fast` + `make test-verilator` green.

## 6. Files
| File | Responsibility |
|---|---|
| `src/main/scala/m68k040/execute/iq/IqContext.scala` | `IqContext`/`IqPush` payload bundles + `IssueQueueService` |
| `src/main/scala/m68k040/execute/iq/IssueQueuePlugin.scala` | slots, compaction, scoreboard+trigger-init, wakeup, select, flush |
| `src/test/scala/m68k040/execute/iq/IqSourcePlugin.scala`, `IqSinkPlugin.scala` | test source/sink |
| `src/test/scala/m68k040/execute/iq/IssueQueueSpec.scala` | the §5 directed tests |

## 7. Open items / deferrals (logged)
- Only static-latency-1 wakeup; the multi-cycle history pipeline (variable/long latency: loads, MUL/DIV) is deferred to when those EUs exist.
- Scoreboard is the minimal readiness tracker folded into the IQ; in 3c it derives its writes from rename's pdst allocation and its busy-clears from the issue wakeup. Whether it later splits out into a NaxRiscv-style `RfDependencyPlugin` is a refactor decision.
- `g2l`/compaction indexing follows NaxRiscv; exact bit-twiddling is pinned by the directed tests (the implementation consults `IssueQueue.scala`). The compaction-vs-issued-slot interaction (issued slots free their `sel`, compaction shifts on push) is the trickiest part — Task-decomposed and tested incrementally in the plan.
- Wakeup/latency-1 timing assumes the 3a EU contract: a dependent issued 1 cycle after its producer reads the producer's result via the EU's bypass. 3a must honor this (documented as the EU contract).
- BRANCH issue port / sel-class added in 3d.
