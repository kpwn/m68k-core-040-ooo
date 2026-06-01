# Multi-Write Register-File Lowering (NaxRiscv strategy) — Design

**Status:** Draft for review
**Date:** 2026-06-02
**Parent spec:** `.../specs/2026-05-31-m68k-040-ooo-architecture-design.md` (invariant #2 FMax / FPGA-mappable). Closes the follow-up logged in `memory/fpga-synth-multiwrite-mem.md`.
**Reference studied:** NaxRiscv `naxriscv.compatibility.MultiportRam` (`MultiPortWritesSymplifier`, `RamAsyncMwXor`, `RamAsyncMwMux`) and `naxriscv.misc.RegFilePlugin` (`RegFileAsync`). Checkout: `/home/qwertyoruiop/m68k-ooo-v2/thirdparty/NaxRiscv/src/main/scala/naxriscv/`.

---

## 1. Purpose

Make the core's multi-write-port + async-read memories — the RAT (`specRam`/`commitRam`), the `Freelist` ring, and the ROB `payload` — **natively synthesizable on FPGA**, replacing the `synth/regfile_fix.py` netlist post-process. We adopt NaxRiscv's actual register-file strategy: write plain multi-write `Mem`s and let a SpinalHDL transformation phase (`MultiPortWritesSymplifier`) lower each into single-write distributed-RAM banks at elaboration. No FPGA RAM primitive has >1 write port; the phase expresses N writes with N single-write RAMs + a reconstruction network.

## 2. Background — why it's needed

Raw Vivado rejects a 2-write-port async `Mem` ("Unsupported RAM template [Synth 8-2914]"); forcing `ram_style=registers` produces multi-driver nets (SpinalHDL emits one `always` block per write port). Today `regfile_fix.py` merges those write blocks into one prioritized process post-generation — correct, but a fragile out-of-band step. NaxRiscv solves this *in elaboration* with a transform, which is the clean, faithful approach.

## 3. The strategy (what the phase does)

`MultiPortWritesSymplifier` matches any `Mem` with `writes > 1`, `readsAsync > 0`, `readsSync == 0`, and rewrites it:

- **data width < 10 bits → `RamAsyncMwXor` (XOR trick).** One single-write `Mem` (bank) per write port. Write port *i* stores `data ^ (XOR of all other banks at that address)`; a read XORs all banks at the address. XOR is self-inverse, so the live value is reconstructed. **Correctness precondition:** no two write ports target the *same address in the same cycle* (else both XOR-in and corrupt). All our sites satisfy this (below).
- **data width ≥ 10 bits → `RamAsyncMwMux` (Live-Value-Table).** One bank per write port (each writes its own bank directly) + a small `location` table (a `RamAsyncMwXor` of width `log2(writePorts)`) recording which bank last wrote each address. A read muxes the banks by the location lookup. Better for wide data (no wide XOR network).

Reads stay async distributed-RAM lookups; the phase is transparent to the surrounding RTL (the `Mem` API is unchanged — only the lowered netlist differs).

### 3.1 Mapping of our sites
| Mem | width | write ports | read ports | lowering | precondition holds because |
|---|---|---|---|---|---|
| `RatTable.specRam` | 6 | 2 (rename) | `readPorts` | XOR | port *i* write disabled when a later port writes same addr (existing `hit` term) → ≤1 write/addr/cycle |
| `RatTable.commitRam` | 6 | 2 (commit) | `readPorts` | XOR | same multi-write-bypass disable |
| `Freelist.ram` | 4 or 6 | 3 (init + 2 push) | `popPorts` | XOR | init active only `!initDone`, pushes only `initDone` (disjoint in time); two pushes go to `tail`,`tail+1` (distinct addr) |
| `RobPlugin.payload` | 68 | 2 (dispatch) | 2 (head, h1) | LVT/Mux | slot0/slot1 write `tail`,`tail+1` (distinct addr) |

Unaffected (correctly skipped — not multi-write or not async): I-cache `dataMem` (sync-read BRAM, 1 write), `tagMem`/`predMem` (1 write async LUTRAM).

## 4. Components

1. **Vendored phase** — `src/main/scala/m68k040/hw/MultiportRam.scala`: a trimmed MIT-attributed port of NaxRiscv's `MultiportRam.scala` containing exactly: `MemWriteCmd`, `MemRead`, `RamAxyncMwIo`, `RamAsyncMwXor`, `RamAsyncMwMux`, and `MultiPortWritesSymplifier` (the async-read branch only; assert/skip on the sync-multi-write case we don't have). Drop the unrelated phases (`MultiPortReadSymplifier`, `MemReadDuringWrite*`, `CombRamBlackboxer`, …) and all `object …Synth extends App` benches.
2. **Shared SpinalConfig** — `src/main/scala/m68k040/M68kSpinalConfig.scala`: `object M68kSpinalConfig { def apply(targetDirectory: String = null): SpinalConfig }` returning a `SpinalConfig` (with `targetDirectory` if given) with `.addTransformationPhase(new MultiPortWritesSymplifier())`. Single source of truth for elaboration config.
3. **GenVerilog** — `GenVerilog`/`GenSynthVerilog`/`GenBackendSynthVerilog` use `M68kSpinalConfig(targetDirectory = "generated")` instead of a bare `SpinalConfig`.
4. **Test sim config** — `src/test/scala/m68k040/M68kSim.scala`: `object M68kSim { def apply() = SimConfig.withConfig(M68kSpinalConfig()).withVerilator }`. The RF specs use it so their assertions run against the **lowered XOR/LVT logic** (the correctness proof).
5. **Synth flow** — remove the `regfile_fix.py` step (and delete the script + its references in `synth/*.tcl` and docs/memory). Generated Verilog is now synthesizable as-is.

## 5. Verification

**Functional equivalence (the key gate):** migrate the specs that build the multi-write structures — `RatTableSpec`, `FreelistSpec`, `RenameStageSpec`, `RobPluginSpec` — to compile via `M68kSim()` (phase active). Their existing assertions then validate the lowered XOR/LVT against the same behavior. No assertions weakened. (Specs with no multi-write Mem are unaffected whether or not they use the phase, since the phase is a no-op for them.)

**Add one targeted lowering test** in `RobPluginSpec` (or `RatTableSpec`): a directed write-then-read across all write ports proving the reconstructed value is correct after interleaved multi-port writes (exercises the XOR/LVT reconstruction explicitly, not just incidentally).

**Full suite:** `make test-fast` + `make test-verilator` green.

**Synth (gating for this slice's purpose):** regenerate `GenSynthVerilog` (phase now in the config) and synthesize **without** `regfile_fix.py`:
- 0 errors, **no "Unsupported RAM template"**, **no multi-driven net** critical warnings.
- The lowered banks map to distributed LUTRAM (LUT-as-Memory > 0); I-cache data still 16 RAMB36.
- Record post-synth WNS @250MHz and compare to the `regfile_fix.py` baseline (full pipeline was +1.07 ns). A regression of more than ~0.3 ns should be investigated (the XOR/LVT read network adds logic vs. the merged-register-file form).

## 6. Files

| File | Change |
|---|---|
| `src/main/scala/m68k040/hw/MultiportRam.scala` | NEW — vendored trimmed `MultiPortWritesSymplifier` + `RamAsyncMwXor`/`RamAsyncMwMux` (MIT attribution header) |
| `src/main/scala/m68k040/M68kSpinalConfig.scala` | NEW — shared SpinalConfig with the phase |
| `src/main/scala/m68k040/top/GenVerilog.scala` | use `M68kSpinalConfig(...)` in all three Gen objects |
| `src/test/scala/m68k040/M68kSim.scala` | NEW — `SimConfig.withConfig(M68kSpinalConfig()).withVerilator` |
| `src/test/scala/m68k040/rename/RatTableSpec.scala`, `FreelistSpec.scala`, `RenameStageSpec.scala`, `src/test/scala/m68k040/rob/RobPluginSpec.scala` | use `M68kSim()`; add the targeted lowering test |
| `synth/regfile_fix.py` | DELETE; remove references in `synth/*.tcl` + memory |

## 7. Open items / divergences (logged)

- Phase vendored async-branch-only; a future multi-write **sync-read** Mem would need the sync branch (assert/log so it can't silently slip through).
- `Freelist.ram` lowers to **3** XOR banks (init + 2 push). A later optimization could fold init into a push port (NaxRiscv's `preferedWritePortForInit`) to drop to 2 banks; not required now.
- The XOR/LVT read network is deeper than a single LUTRAM read; if the RAT read or ROB head read becomes the critical path post-route, revisit (the RAT was not on the backend critical path — that was the ROB payload write addressing, which is unaffected).
- `regfile_fix.py` removed entirely (per decision); the transform supersedes it.
