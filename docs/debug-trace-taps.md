# Debug env-var trace taps

This is the reference list for every debug/trace env-var the test/sim
infrastructure (`src/test/...`) recognizes. All of these are **zero-cost when
unset** — every one is gated by `sys.env.contains("...")`, so leaving them
unset does not change simulation behavior or timing, only whether a `println`
fires.

To regenerate/verify this list yourself:

```
grep -rohE 'sys\.env\.contains\("[A-Z_0-9]+"\)' src/test | sort -u
```

As of this writing that grep returns exactly the boolean-flag vars tabulated
below (11 `PORTED_TRACE_*`, 5 `CR_*`, plus `LS_TRACE_MMU`, `FUZZ_TRACE_CPLX`,
`FUZZ_GEN_VERBOSE`, `IPC_DEBUG`). A separate, non-overlapping family of
`sys.env.get("...")` vars also exists in the fuzz/bench harnesses (e.g.
`FUZZ_SEEDS`, `FUZZ_ONLY`, `FUZZ_BLOCKS`, `IPC_SEED`, `MUSASHI_RUN`, ...) —
those are corpus/run **configuration** knobs (which seeds/programs to run),
not debug trace taps, so they're intentionally out of scope for this table.
Find them with:

```
grep -rohE 'sys\.env\.get\("[A-Z_0-9]+"\)' src/test | sort -u
```

## `PortedTestRunner.scala` (`src/test/scala/m68k040/fuzz/PortedTestRunner.scala`)

| Env var | Prints | Usage |
|---|---|---|
| `PORTED_TRACE_MI` | Every microcode `ucBegin` pulse for the memory-indirect MOVE crack: entry PC, real entry, `isMemInd`/`moveDstMi`/`moveSrcMi`/EA-EA flags, EA base/disp, memory-indirect outer displacement/postindex, other-operand EA, host-imm fields, `miEntry`. | `PORTED_TRACE_MI=1 sbt "testOnly *PortedTestRunner* -- -z move_l_abs_memind_dst"` |
| `PORTED_TRACE_FED` | Every accepted fetch/predecode `fed` group: packet(0) pc/simple/fault/wordCount/lenWords/raw words, plus slot-1 validity+pc. Distinguishes a predecode mis-framing stall from a downstream (decode/issue/microcode) stall. | `PORTED_TRACE_FED=1 sbt "testOnly *PortedTestRunner* -- -z move_abs_src_full_memind_dst"` |
| `PORTED_TRACE_DIV` | Every ROB commit (port + robId + pc) and every DivEu writeback (robId/dstArch/result/nzvc/nzvcWrite + IQ CPLX-NZVC wakeup state) — for pinning whether DIVU.L/DIVS.L 32/32 hangs on the divider itself or a downstream scoreboard/ROB-slot leak. | `PORTED_TRACE_DIV=1 sbt "testOnly *PortedTestRunner* -- -z divl_basic"` |
| `PORTED_TRACE_DLOAD` | D-cache load cmd/rsp, store cmd, ROB commits, and microcode-active state, every cycle. | `PORTED_TRACE_DLOAD=1 sbt "testOnly *PortedTestRunner* -- -z btst_pcrel_src"` |
| `PORTED_TRACE_DCHIT` | D-cache store-RMW S2 hit/miss decision (paddr/size/hitVec/useStrb) and load S1 hit/miss decision (set/tag/off/size/hit/way), every cycle. | `PORTED_TRACE_DCHIT=1 sbt "testOnly *PortedTestRunner* -- -z exc_addr_error_odd_rte"` |
| `PORTED_TRACE_DCPIPE` | Full D-cache store pipe S1 (read-launch)/S2 (hit-detect)/S3 (array-write) plus raw write-port fires — cycle-by-cycle, catches a race between one store's S1 read-launch and an *earlier* store's S3 write that `PORTED_TRACE_DCHIT`'s same-cycle S2-vs-S3 view can't show. | `PORTED_TRACE_DCPIPE=1 sbt "testOnly *PortedTestRunner* -- -z <same-line-back-to-back-store test>"` |
| `PORTED_TRACE_IQNZVC` | CPLX-NZVC dynamic wakeup port fires, `cplxNzvcBusy` bitmap whenever nonzero, and every occupied IQ slot with `cplxNzvcWait` latched (robId/readsNzvc/pNzvcSrc) — for finding a permanently-latched wait bit never cleared by a matching wakeup. | `PORTED_TRACE_IQNZVC=1 sbt "testOnly *PortedTestRunner* -- -z mull_basic"` |
| `PORTED_TRACE_EXC` | Every exception-FSM `dcStore` command (paddr/size/data/strb), D-cache load cmd/rsp on the exception path, and every exception redirect (pc/a7/s/m/isp/usp); at the end of the run also dumps raw D-memory `0xFFF0-0x10010` byte-by-byte. | `PORTED_TRACE_EXC=1 sbt "testOnly *PortedTestRunner* -- -z exc_aline_odd_sp_mmu_dcache"` |
| `PORTED_TRACE_RTECCR` | Every `rteRetire` pulse (head/tail/count), every direct RTE NZVC/X-restore write, and every exception redirect with SSP/A7 bank state — for the RTE-CCR regression class (`exc_stack_atomicity_stress`, `pea_aline_irq_storm`, `via1_t1_irq_storm`). | `PORTED_TRACE_RTECCR=1 sbt "testOnly *PortedTestRunner* -- -z exc_stack_atomicity_stress"` |
| `PORTED_TRACE_WBDBG` | Per-ROBID dataflow: every ALU-EU/LS-EU writeback (robId/dstArch/intWrite/result), every LS-EU XLATE, every D-cache load/store command, every commit — the only tap that attributes a *value* to the producing µop. | `PORTED_TRACE_WBDBG=1 sbt "testOnly *PortedTestRunner* -- -z <same-cycle dependent ALU dual-issue repro>"` |
| `PORTED_TRACE_IQDBG` | Every IQ push (both dispatch slots, with pdst/psrcB so an intra-push producer/consumer pair is visible), plus — over an optional cycle window `[IQDBG_LO, IQDBG_HI]` (env-var ints, default `0`/`999999`) — a full dump of every occupied slot (robId/op/trigger bitmap/psrcA/psrcB/lsWait/cplxWait) and `sbInt.busy`. | `PORTED_TRACE_IQDBG=1 IQDBG_LO=100 IQDBG_HI=200 sbt "testOnly *PortedTestRunner* -- -z <repro>"` |

## `ExecuteLockStepSpec.scala` (`src/test/scala/m68k040/lockstep/ExecuteLockStepSpec.scala`)

| Env var | Prints | Usage |
|---|---|---|
| `CR_RAW` | Every commit's raw whitebox writeback record (rob/pc/dstArch/intWrite/result/divRem) *before* it's handed to the lock-step comparator. | `CR_RAW=1 sbt "testOnly *ExecuteLockStepSpec*"` |
| `LS_TRACE_MMU` | D-cache store/load-cmd traffic and `dtlb.logic.faultSeen` pulses, every cycle — for MMU-enabled lock-step tests (task #194: an early `mmuEnable`/`urp`/`srp` poke landing inside the reset window). | `LS_TRACE_MMU=1 sbt "testOnly *ExecuteLockStepSpec* -- -z <mmu test>"` |
| `CR_DECTRACE` | (first 700 guard cycles only) Decode-stage `pushReg` pushes (pc/first-of-instr/fault flags per uop, up to 4), plus `ucActive`/`ucBegin`/`ucPend`/`ucPc`/`fed` state on any `ucBegin`/`pipeFlush` pulse. | `CR_DECTRACE=1 sbt "testOnly *ExecuteLockStepSpec* -- -z <repro>"` |
| `CR_RENTRACE` | (first 900 guard cycles only) Rename-stage fires (pc/dst/pdst/pdstOld/psrcA/B/C per slot), PRF debug write ports, rename commit ports, and int free-list head/tail/count/push-addrs. | `CR_RENTRACE=1 sbt "testOnly *ExecuteLockStepSpec* -- -z <repro>"` |
| `CR_STALL` | (last 40 guard cycles before the 4000-cycle cap) Commit-progress snapshot (guard/committed/ucActive/ucPend/ucPc/ROB head+count/exc state/vec/pc); at the final cycle also dumps the full 8-entry store queue. | `CR_STALL=1 sbt "testOnly *ExecuteLockStepSpec* -- -z <hang repro>"` |
| `CR_DEBUG` | On a lock-step comparison failure only: side-by-side DUT-vs-oracle dump (pc/a7/archReg) for every compared step. | `CR_DEBUG=1 sbt "testOnly *ExecuteLockStepSpec* -- -z <failing test>"` |

## `FuzzLockStepSpec.scala` (`src/test/scala/m68k040/fuzz/FuzzLockStepSpec.scala`)

| Env var | Prints | Usage |
|---|---|---|
| `FUZZ_TRACE_CPLX` | Robid→lock-step-index map on every commit (`[cplxtrace] IDX-MAP ...`), plus (added for the task #139 CMP2/CHK2 hang and task #141 X-flag scoreboard leak) an env-gated cycle trace of X-flag scoreboard push/clear events — rides the real `FuzzRunner.run` harness so it reproduces exactly, no hand-rolled boot sequence to drift from wrong-path/`SparseMemory` fill content. `PortedTestRunner.scala`'s `PORTED_TRACE_MI` mirrors this file's `ucBegin` trace pattern. | `FUZZ_TRACE_CPLX=1 sbt "testOnly *FuzzLockStepSpec* -- -Dfuzz.seed=<seed>"` |

## `FuzzGenSpec.scala` (`src/test/scala/m68k040/fuzz/FuzzGenSpec.scala`)

| Env var | Prints | Usage |
|---|---|---|
| `FUZZ_GEN_VERBOSE` | Per-seed oracle-generation summary (`seed`/`imageBytes`/`oracleSteps`); for seed `0` also prints the generated program source. | `FUZZ_GEN_VERBOSE=1 sbt "testOnly *FuzzGenSpec*"` |

## `IpcBenchSpec.scala` (`src/test/scala/m68k040/bench/IpcBenchSpec.scala`)

| Env var | Prints | Usage |
|---|---|---|
| `IPC_DEBUG` | Stall diagnostics when the commit count is stuck for 50 cycles (or periodically in the first 400 guard cycles): guard/committed-size/ROB head/tail/count/`excActive`/`doFlushReg`/`exceptionPending`/`exceptionVector`. | `IPC_DEBUG=1 sbt "testOnly *IpcBenchSpec*"` |

---

## Convention: `Mem`-typed state needs explicit `.simPublic()` **per instance**

If you fold a flat `Reg`/`Vec.fill(...)(Reg)` array into a `Mem(...)` (e.g.
for LUTRAM/BRAM inference), **you must add `.simPublic()` to every `Mem`
instance explicitly**, or any test that peeks it via `Mem.getBigInt(addr)` /
pokes it via `Mem.setBigInt(addr, v)` throws `UNACCESSIBLE SIGNAL` at sim
time. This does not affect plain `Reg`/`Vec(Reg)` arrays, which stay directly
peekable via the ordinary `.toBoolean`/`.toBigInt` sim API without needing
`.simPublic()` at all — the extra step is specific to `Mem`'s own
`getBigInt`/`setBigInt` sim API, and `Mem` is typically defined as
`Seq.fill(ways)(Mem(...))`, so it needs an explicit loop
(`for (w <- 0 until ways) mem(w).simPublic()`) — `.simPublic()` is not a
method on `Seq`.

This has bitten two separate cache LUTRAM conversions:

- **I-cache**: `tagMem`/`lineMem`, `src/main/scala/m68k040/cache/IcachePlugin.scala:203`
  (`for (w <- 0 until ways) { tagMem(w).simPublic(); lineMem(w).simPublic() }`)
- **D-cache**: `valids`/`dirtys` → `validsMem`/`dirtysMem` in task #240,
  `src/main/scala/m68k040/cache/DcachePlugin.scala:155`
  (`for (w <- 0 until ways) { validsMem(w).simPublic(); dirtysMem(w).simPublic() }`)
  — task #240's own report confirms: *"`Mem.getBigInt` throws
  `UNACCESSIBLE SIGNAL` at sim time without it."*

A related but distinct wall: a **multi-write** `Mem` (one routed through
`M68kSpinalConfig`'s `MultiPortWritesSymplifier`) is rewritten out of the
netlist entirely into XOR/LVT banks in every full-core build, so there is no
`ram` handle left for `getBigInt` to reach *at all*, `.simPublic()` or not —
`RegFilePlugin.scala`'s int/FP PRF and `RobPlugin.scala`'s `payload` Mem both
hit this and solved it the same way: a `GenerationFlags.simulation`-gated
sim-only shadow `Vec` of `Reg`s that mirrors the real write logic
statement-for-statement (see `RegFilePlugin.scala:115-134`). If a future
`Mem`-fold has more than one write port, check for this case first —
`.simPublic()` alone will not fix it.

## Caveat: `simPublic()` is a no-op for synthesis QoR, but NOT for netlist signal-name stability

`DcachePlugin.scala` says "no-op for synthesis" 8 times (grep
`no-op for synthesis` in that file: lines 102, 149, 293, 572, 852, 890, 1013,
2119). That claim is true for **QoR** — adding `.simPublic()` to a signal
does not change LUT count, timing, or area; Vivado still applies the same
optimizations, and any comparison of e.g. Fmax or LUT counts before/after is
unaffected.

It is **not** true for **exact netlist signal-name stability**. As
`ExecuteLockStepSpec.scala:179-182` documents for a different plugin,
`.simPublic()` on a port can perturb the synthesized netlist itself: the
port stops being inlined into its consumer, and the added source lines
renumber every `when_<Plugin>_lNNN` auto-named signal below them in that
plugin's generated Verilog. Quoting that comment directly:

> Marking it simPublic in DivEuPlugin.scala itself would have worked too,
> but it perturbs the SYNTH netlist (the port stops being inlined into
> rob.logic.completion(5), and the added source lines renumber every
> `when_DivEuPlugin_lNNN` signal below them) — and this task is test-only.

This matters here because this project's FMax methodology cites *exact*
Vivado register names (e.g. `DcachePlugin_logic_stSubP_reg[0]/C`) to compare
critical paths across commits. Adding or removing a `.simPublic()` call
between two commits being compared can shift those names even though QoR
(area/timing) is unaffected — so a name-level diff across commits is only
trustworthy if the set of `.simPublic()` calls in the netlist-affecting
plugin is unchanged between them. When it isn't, re-derive the register name
from the new netlist rather than assuming the old name still resolves to the
same signal.
