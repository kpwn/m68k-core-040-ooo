# ALU Execution Unit (Execute Slice 3a) — Design

**Status:** Draft for review
**Date:** 2026-06-02
**Parent spec:** `.../specs/2026-05-31-m68k-040-ooo-architecture-design.md` (ch 5 execute). Execute brainstorm `2026-06-02-alu-datapath-design.md`. Owner direction (2026-06-02): model the ALU pipeline exactly like NaxRiscv's `ExecutionUnitBase`; it can't be single-cycle.
**Reference studied:** NaxRiscv `execute/ExecutionUnitBase.scala` (2-fetch-stage + DIRECT execute, `rfReadAt=0`, `aluStage=0`, `writebackAt=0`), `execute/IntAluPlugin.scala`, `misc/RegFilePlugin.scala` bypass, `frontend/DispatchPlugin.scala` static wakeup. Per the timing analysis: NaxRiscv's int ALU is a **2-stage** pipeline (read | execute+writeback), result bypassable at the execute stage, dependent chains issue **1/cycle**.
**Builds on (all merged):** `AluDatapath` (Musashi-verified combinational ALU), the three PRFs (`Int/Nzvc/XRegFileService` with `newRead/newWrite/newBypass`), the IssueQueue (`IqContext`, static-latency-1 wakeup).
**Slice position:** 3a — the execution unit, connecting IssueQueue↔PRF↔ROB. Built as a concrete 2-stage ALU EU (the NaxRiscv pipeline *shape*, not the full Stageable framework — owner choice). Test-issued; 3c wires the IQ + ROB + lock-step.

---

## 1. Purpose

A fixed-latency-1 integer ALU execution unit with the exact NaxRiscv pipeline timing: **read stage** → M2S register → **execute+writeback stage**. It consumes an issued `IqContext`, reads operands from the int PRF (with bypass), computes via `AluDatapath`, writes the int/NZVC/X PRFs, drives the bypass forward, and signals ROB completion. The latency-1 timing matches the IssueQueue's static-latency-1 wakeup **unchanged** (the headline of the design analysis), so a dependent issued 1 cycle after its producer catches the result via the PRF bypass before it is RF-committed → dependency chains run 1/cycle.

## 2. Scope

**In:** `AluEuPlugin` — 2-stage pipeline (S0 read | S1 execute+writeback), consuming a plain `Stream[IqContext]` issue port (test-driven now, IQ-driven in 3c) and producing a `Flow[robId]` completion port. Allocates int PRF read×2 + write + bypass, and NZVC/X PRF write + bypass (via the `*RegFileService` dynamic ports). Drives writes/bypass/completion gated by the µop's write masks. A test source + probe + directed spec (single op, reg-reg with preloaded operands, and the back-to-back **bypass** test). For our op set (MOVE/ADD/SUB/AND/OR/CMP) — no flag *reads* yet.

**Out:** the IssueQueue wiring (3c — issue port is test-driven now), the ROB completion wiring + CommitTrace fill + Musashi lock-step (3c), the second ALU EU (3c instantiates two), the branch EU (3d). Flag-source reads (NZVC/X read ports for ADDX etc.) — deferred (our ALU ops don't read flags; `xIn=0`). Variable latency / structural stalls (fixed-latency single ALU never stalls → `issue.ready` always true).

## 3. Pipeline (the NaxRiscv shape, concrete)

µop **selected/issued at cycle T**:

### S0 — read (cycle T)
- Accept `issuePort` (a `Stream[IqContext]`); `issuePort.ready := True` (fixed-latency EU never structurally stalls).
- Drive int PRF reads: `rdA.addr := uop.psrcA`, `rdB.addr := uop.psrcB`. The PRF returns data combinationally (async + **bypass** — a producer's S1 result this cycle overrides RF).
- Form operands: `src1 = rdA.data`; `src2 = uop.useImm ? uop.imm : rdB.data`.
- Register into the S0→S1 pipeline reg: `{valid, op, size, src1, src2, xIn(=0), pdst, pdstValid, pNzvcDst, writesNzvc, pXDst, writesX, dstArch, pc, robId}`. (The M2S boundary — RF read and ALU are in separate cycles, satisfying "not single-cycle".)

### S1 — execute + writeback (cycle T+1)
- `rsp = AluDatapath(AluCmd(op, size, src1, src2, xIn))` → `result, nzvc, xOut` (combinational).
- Drive PRF writes (latency=1 on the port, gated by masks): `intW.{valid := s1.valid && pdstValid, address := pdst, data := result}`; `nzvcW.{valid := s1.valid && writesNzvc, address := pNzvcDst, data := nzvc}`; `xW.{valid := s1.valid && writesX, address := pXDst, data := xOut(asBits)}`.
- Drive the matching **bypass** ports (same valid/addr/data as the writes) so a dependent reading the PRF *this cycle* (its S0 at T+1) gets the result before it is RF-committed (RF write at T+1 is readable T+2; bypass covers the T+1 read).
- Drive `completionPort : Flow(UInt(robIdW))` — `valid := s1.valid`, `payload := robId`.

**Timing contract honored:** producer issued T → result+bypass at S1 (T+1). A dependent issued T+1 (the IQ's latency-1 wakeup) reads at its S0 (T+1) and catches the bypass. Issue-to-issue gap for a dependent chain = 1 cycle. **No IssueQueue change** (its depth-1 wakeup already matches). The single bypass port per class covers the exact 1-cycle window.

## 4. Service / ports
`AluEuPlugin extends FiberPlugin` (+ `AluEuService` exposing `issue: Stream[IqContext]` and `completion: Flow[UInt(robIdW)]` as plain wires). Allocates PRF ports in `during setup` (`host[IntRegFileService].newRead()×2 / newWrite(latency=1) / newBypass()`; `host[NzvcRegFileService].newWrite/newBypass`; `host[XRegFileService].newWrite/newBypass`); builds S0/S1 in `during build`. (3c instantiates two `AluEuPlugin`s, each connected to one IQ issue port; their write ports share a `sharingKey` per class only if they can't co-fire — they CAN co-fire 2-wide, so they are distinct keys → 2 physical write ports per PRF, which the PRF lowering already supports.)

## 5. Verification (test-issued; no IQ/ROB)
Dut hosts the three PRFs + `AluEuPlugin` + a `AluEuSourcePlugin` (drives the issue Stream from IO: op/size/physregs/imm/useImm/masks/robId/valid) + a probe int-PRF read port (to observe results) + observes `completion`. Through `M68kSim` (PRF lowering in the loop). Tests:
1. **Single immediate op:** issue `MOVE`/MOVEQ-style (useImm, imm=V, pdst=R0, writesNzvc); after 2 cycles, probe-read R0 == V; completion fires at T+1 with the right robId. (NZVC value already Musashi-checked in `AluDatapath` — here we check it lands in the NZVC PRF and the int result lands in the int PRF.)
2. **Reg-reg with preloaded operands:** issue MOVEQ→R0=a, MOVEQ→R1=b (preload via the EU); then issue `ADD R1,R0` (src1=R0, src2=R1); probe-read R0 == a+b; flags written. (Operands sourced from the RF — exercises the read path.)
3. **Back-to-back bypass (headline):** issue op1 `ADD …→R2` at T, then op2 reading R2 (`ADD R2,Rx→R3`) at **T+1**. op2's S0 read of R2 (T+1) must see op1's result via **bypass** (R2 isn't RF-committed until T+2). Assert op2's result == f(op1.result, …), proving the forward path + the latency-1 timing. Contrast: issuing op2 at T+2 reads R2 from the RF (also correct).
4. **Write masks:** a `CMP` issues, completion fires, but **no** int write occurs (pdstValid=false) — probe-read the dst reg is unchanged; NZVC still written.

`make test-fast` + `make test-verilator` green.

## 6. Files
| File | Responsibility |
|---|---|
| `src/main/scala/m68k040/execute/AluEuPlugin.scala` | `AluEuService` + the 2-stage ALU EU (S0 read / S1 execute+writeback+bypass+completion) |
| `src/test/scala/m68k040/execute/AluEuSourcePlugin.scala` | test issue-source + result/completion probe |
| `src/test/scala/m68k040/execute/AluEuSpec.scala` | the §5 directed tests |

## 7. Open items / deferrals (logged)
- Flag-source reads (NZVC/X newRead for ADDX/SUBX) deferred; `xIn=0` for the current op set. When ADDX arrives, add `xRf.newRead`/`nzvcRf.newRead` and feed `AluCmd.xIn`.
- `newWrite(latency=1)` is passed for documentation; the PRF currently writes immediately on `valid` (latency param unused) — correct for this EU (write at S1, bypass covers the T+1 window). If the PRF later honors latency, revisit.
- Two EUs co-firing (3c) → 2 physical write ports per PRF (distinct sharingKeys) → LVT/XOR multi-write (already supported + synth-confirmed). The PRF's "different-key writers must target distinct physregs/cycle" invariant holds (rename unique pdst).
- `issue.ready` is always true (no structural stall). When variable-latency EUs (load/MUL) arrive, stalls + the IQ multi-cycle wakeup history become relevant — not now.
- 3c will connect `issue` to an IQ port and `completion` to the ROB (replacing `markComplete`), and capture the result for CommitTrace.
