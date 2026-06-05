# Dcache store-RMW pipeline (fix the exc-FSM→dataMem binding path) — Design

**Status:** Draft (slice C1; the actual binding-limiter fix). User-approved ("Both, exc-FSM then VIPT").
**Date:** 2026-06-05
**Parent:** the LS cluster D-cache ([[ls-cluster]]) + the exception subsystem ([[exception-subsystem]]).

## 1. Purpose & motivation

After the traps merge the binding full-core critical path is (saved `synth/M68kFullCoreSynth_timing.rpt`, WNS **+0.009 / 250.6 MHz**, down from 258.3):

```
Source:      RobPlugin   exc_fsm_stateReg[3]                 (exception FSM state)
Destination: DcachePlugin dataMem_0 port1 (write-data)        (the store write port)
14 logic levels (CARRY8=1 ... RAMD64E=1), route 71.7%, logic 1.124ns
```

The D-cache **store** does a single-cycle read-modify-write (`DcachePlugin.scala:161-178`): on `storePort.valid` it `tagMem.readAsync` + `dataMem.readAsync`-es the old 128-bit line (the `RAMD64E`), byte-merges the store bytes (16-lane strobe mux), and `dataMem.write`-s the result — all combinationally. Fronted by the exc-FSM's store-port select (the `exc_fsm_stateReg` source; the `CARRY8` is the frame/offset adder). It is **route-dominated (71.7%)** because the exception FSM is placed far from the D-cache. This single-cycle RMW + the long exc→cache route is the limiter — and traps' new **format-$2** frame logic deepened the exc-FSM front of it (the 258.3→250.6 regression).

This slice **pipelines the store RMW** into two cycles so a register sits at the D-cache boundary (cutting the long route + letting the placer pack the RMW tight to the RAM): cycle N reads the old line into a register; cycle N+1 merges the store bytes and writes `dataMem`. Lock-step is latency-agnostic, so the extra cycle is free. Goal: the `exc-FSM→dataMem` path leaves the critical list and FMax recovers to **≥258 with comfortable margin** (de-risking the route-dominated path).

## 2. Scope

**In:**
- **Pipeline the D-cache store RMW (`DcachePlugin.scala`):** register the store payload at the cache boundary (a store-S0 stage: latch {paddr, data/lineData, size, strb, useStrb, hit-way, old-line read}); do the byte-merge + `dataMem.write` in store-S1 from the registered old line + registered store payload. The async old-line read becomes a registered read (synchronous read into the S0 reg, or `readAsync` captured into a reg) so the merge+write cycle starts from a flop near the dataMem.
- **Preserve the single-outstanding + write-through discipline:** stores are single-outstanding (the comment at :136; the exc-FSM serializes E_STORE→E_STWAIT, the LS pipe never presents a 2nd store while one drains). The +1-cycle RMW must keep: the AXI write-through beat (`stMergeReg/stStrbReg/stAddrReg` → aw/w) fires after the merge; the `storeAckReg` (AXI B) still gates the SQ drain-resident window; refill-vs-store dataMem-write priority (refill has priority, they never collide) stays correct across the new stage.
- **Store→load correctness:** the SQ still forwards in-flight stores (the cache line updating one cycle later is covered by SQ forwarding + the drain-resident-until-ACK window). Verify a store-then-immediate-load to the same line lock-steps.
- Confirm the **exc-FSM store path** specifically benefits — the exception frame stores go through the same `storePort` RMW, so pipelining it shortens the binding path. If the exc-FSM store-port *select* (mux fronting storePort) is itself on the path, register that select too.
- **Verification:** ALL existing LS / MMU / exception / traps lock-step programs UNCHANGED ×2 (latency-agnostic). Honest MMU-live synth: the `exc-FSM→dataMem` path off the critical list; **WNS comfortably ≥0, target FMAX ≥258** (recover the traps regression + margin on the route-dominated path). Report WNS+FMAX+new-critical-path + the delta from 250.6.

**Out:** the VIPT-parallel-LOAD restructure (slice C2, next — different cone); changing the store-queue depth or the single-outstanding policy; multi-store-in-flight.

## 3. Dataflow

```
store-S0 (cycle N):   storePort.valid -> latch {paddr,data,size,strb,useStrb}
                      stHit/way compute; oldLine = dataMem(hitWay).read(stSet)  -> REG
store-S1 (cycle N+1): newBytes = merge(oldLineReg, storeReg.bytes, strbReg)
                      dataMem(wayReg).write(stSetReg, newBytes)
                      stMergeReg/stStrbReg/stAddrReg -> AXI aw/w (write-through)
```

The merge + write now start from registers physically at the D-cache, so the exc-FSM→cache route ends at the S0 register (short per-cycle), and the merge+write is a local cone near the dataMem. Refill keeps dataMem-write priority over the store-S1 write (they never collide — single-outstanding).

## 4. Verification
- **Directed:** store hit → RMW lands one cycle later (the line reads back merged); store miss → refill → replay → store (unchanged); store-then-load same line forwards correctly.
- **Lock-step (the gate):** ALL existing programs (memory, MMU, exceptions format-$0/$2/$7, traps, ITLB) UNCHANGED ×2 — the +1-cycle store RMW is invisible to the latency-agnostic whitebox.
- **`make test-fast` + `make test-verilator`** green (report totals; test-verilator needs `-Xmx12g`).
- **HONEST MMU-live synth gate:** mmuEnable/rootPtr live, both TLBs+walkers inferred (16 RAMB). `exc-FSM→dataMem` OFF the worst-path list; **WNS ≥0, FMAX ≥258** (baseline pre-traps 258.3; current 250.6). Report WNS+FMAX+the new critical Source→Dest + logic levels + route%.

## 5. Open items
- Whether to register the old-line read synchronously (`readSync`, costs a BRAM read port) or capture the `readAsync` into a reg (keeps the dist-RAM async read but flops its output) — pick by area + which keeps the dataMem inference clean (dist-RAM `RAMD64E` vs BRAM). Measure both if marginal.
- If the exc-FSM store-port *select* mux (BackendWiring: exc store vs SQ-drain store) is the `exc_fsm_stateReg` front of the path, register the select / the muxed storePort one stage earlier in BackendWiring.
- Confirm the refill-write vs store-S1-write priority + the single-outstanding interlock hold with the store write delayed one cycle (no lost/duplicated write-through beat).
