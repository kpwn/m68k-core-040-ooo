# Physical Register Files (Execute Slice 2) — Design

**Status:** Draft for review
**Date:** 2026-06-02
**Parent spec:** `.../specs/2026-05-31-m68k-040-ooo-architecture-design.md` (ch 4.2 PRF, 4.6 split CCR; invariant #2 FMax, #3 plugin boundaries). Execute brainstorm `.../specs/2026-06-02-alu-datapath-design.md` (§ overall architecture: Slice 2 = the PRFs).
**Reference studied:** NaxRiscv `misc/RegFilePlugin.scala` (`RegfileService` dynamic port allocation, write-merge by sharingKey, `RegFileAsync`, bypass network). We adopt this pattern (owner chose dynamic allocation), adapted to our `FiberPlugin` idiom and plain-wire service convention, reusing the `MultiPortWritesSymplifier` phase we vendored.
**Builds on:** rename (`RenamedUop` carries the physical IDs `pdst/psrc*`, `pNzvc*`, `pX*`), the multi-write lowering phase (`m68k040.hw.MultiPortWritesSymplifier`).
**Slice position:** the value storage the execute side reads/writes. Slice 1 (ALU datapath) is done; Slice 3 (IssueQueue + ALU/branch EUs) will allocate ports on these files.

---

## 1. Purpose

Provide the three physical register files — integer (48×32b), NZVC (16×4b), X (16×1b) — as async-read, multi-write, **bypassing** register files with **NaxRiscv-style dynamic port allocation**: execution units (Slice 3) call `newRead/newWrite/newBypass` during Fiber setup and the file builds exactly the requested ports, merging write ports that never co-occur. The multi-write storage lowers to synthesizable distributed-RAM banks via the phase already in our config (int 32b → LVT, NZVC/X → XOR).

## 2. Scope

**In:** `RegFileAsync` storage component (async multi-write `Mem` + per-read bypass mux + init-zero boot sweep); a `RegfileService` (newRead/newWrite/newBypass + retain/release); a `RegFilePlugin(spec)` that collects allocations in setup, merges writes by `sharingKey`+`priority`, instantiates `RegFileAsync`, and wires the read/write/bypass network in build; three instances (int/NZVC/X) exposed as distinct services (`IntRegFileService`/`NzvcRegFileService`/`XRegFileService`). A test probe plugin + specs exercising read/write/bypass/merge/init through `M68kSim` (lowering in the loop).

**Out:** the EUs that consume the ports (Slice 3); the scheduler/wakeup; reading values from rename/ROB; any tie to commit. FP register file. Banking (single bank), RISC-V `x0`-always-zero (68k has no zero reg).

## 3. Components

### 3.1 `RegfileSpec`
A small descriptor per class: `name: String`, `dataWidth: Int`, `depth: Int`. Instances: `Int(32,48)`, `Nzvc(4,16)`, `X(1,16)`. (`addressWidth = log2Up(depth)`.)

### 3.2 `RegfileService` (trait; NaxRiscv-faithful, plain-wire ports)
```
trait RegfileService {
  def spec: RegfileSpec
  def newRead(forceNoBypass: Boolean = false): RegFileReadPort        // .addr (drive), .data (read)
  def newWrite(latency: Int, sharingKey: Any, priority: Int): RegFileWritePort  // .valid/.address/.data (drive)
  def newBypass(): RegFileBypassPort                                  // .valid/.address/.data (drive)
  def retain(): Unit; def release(): Unit                             // gate the build until allocation done
}
```
- **Read port:** consumer drives `addr`, reads `data` (combinational async + bypass-overridden).
- **Write port:** consumer drives `valid/address/data`; `latency` (cycles after issue the value is valid — ALU = 1), `sharingKey` (writes with the same key never co-occur and share one physical write port), `priority` (higher wins a same-key conflict).
- **Bypass port:** a latency-0 forwarding source; consumer drives `valid/address/data`; the file's read mux prefers a bypass hit over RF data.
Marker subtraits `IntRegFileService`/`NzvcRegFileService`/`XRegFileService extends RegfileService` so consumers resolve a specific file via `host[IntRegFileService]`.

### 3.3 `RegFileAsync` (storage component — vendored/adapted from NaxRiscv, MIT, trimmed)
- `ram = Mem(Bits(dataWidth bits), depth)` — multi-write (one logical write per merged write port) + `readAsync` per read port. The `MultiPortWritesSymplifier` phase lowers it (LVT for ≥10b int, XOR for NZVC/X).
- **Read = bypass-over-RF:** `read.data := (orR of bypass hits) ? MuxOH(bypass hits) | ram.readAsync(read.addr)`. `forceNoBypass` reads skip the bypass mux.
- **Init-zero boot sweep:** a reset counter writes `0` to every address through the preferred write port over `depth` cycles before normal operation (the front-end does not fetch until the first redirect, so the sweep is free). Reads return 0 after the sweep. (Avoids depending on Mem-init surviving the multi-write lowering — the sweep is plain writes.) Trim NaxRiscv's `bankCount`/`headZero`/`allOne` to: single bank, no zero-reg, init-zero sweep.

### 3.4 `RegFilePlugin(spec)` (FiberPlugin)
- `during setup`: expose the service; `newRead/newWrite/newBypass` append request records to `ArrayBuffer`s (callable by consumers in their own setup). A `Retainer`/`Lock` lets consumers hold the build until done allocating (or rely on the setup→build phase split: build runs after all setups).
- `during build`:
  - **Write merge** (NaxRiscv `writeMerges`): group writes by `sharingKey`; each group → one physical write bus via `OHMasking.first` over the group's `valid`s ordered by `priority` (higher first); a group containing a `latency==0` member also drives a `newBypass`. Physical write-port count = number of groups.
  - Instantiate `RegFileAsync(dataWidth, depth, readPorts=reads.size, writePorts=groups.size, bypassPorts=bypasses.size)`; connect merged write buses, read ports, and bypass ports.
  - **Precondition (silent-corruption class):** the multi-write Mem lowers to XOR/LVT banks (`MultiPortWritesSymplifier`) which require that no two physical write ports write the same address in the same cycle. Same-`sharingKey` requests merge into one physical port (safe); **different-key requests become different physical write ports, and callers MUST guarantee they never target the same physical register in one cycle** (held by rename's unique-pdst allocation — each in-flight writer owns a distinct phys reg). Violating this corrupts silently.
- Three instances constructed in the top: `new RegFilePlugin(Int)`, `(Nzvc)`, `(X)`, each mixing in its marker service trait (or one class parameterized by spec that registers the right marker).

## 4. Data flow
Slice 3 EU (setup): `val rs1 = intRf.newRead(); val wr = intRf.newWrite(latency=1, key=aluGroup, prio=k); val byp = intRf.newBypass()`. At run time: EU drives `rs1.addr := psrcA` and reads `rs1.data`; on writeback drives `wr.{valid,address,data}` and (for back-to-back) the matching `byp`. The file merges/stores and forwards. NZVC/X files are used identically with their renamed flag IDs.

## 5. Verification (probe plugin; through `M68kSim` so the lowering is exercised)
A `RegFileProbePlugin` allocates, in setup, a representative port set on the **int** file (covers LVT) and a smaller set on **NZVC** (covers XOR), wiring them to top-level IO in build. Tests:
1. **Init-zero:** after the boot sweep completes, every read returns 0.
2. **Write→read:** write addr A=V via a write port; the next cycle a read of A returns V; an un-written addr still reads 0.
3. **Bypass beats RF:** with RF[A]=old, drive a bypass port `{valid, A, W}`; a read of A returns W **the same cycle** (no RF write needed); dropping bypass returns RF value.
4. **Two distinct writes:** two write ports (distinct sharingKeys) to distinct addrs both land (exercises the lowered multi-write banks); read both back.
5. **Write-merge by sharingKey+priority:** two writes sharing one key — only one valid → that value lands; both valid to the same addr → the higher-`priority` value lands (and a `withReady`/loser indication if modeled). Confirms the merge collapses to one physical port.
6. **NZVC (XOR path) smoke:** repeat write→read + bypass on the 4-bit NZVC file.

`make test-fast` + `make test-verilator` green.

## 6. Files
| File | Responsibility |
|---|---|
| `src/main/scala/m68k040/execute/regfile/RegFileAsync.scala` | storage: async multi-write Mem + bypass mux + init-zero sweep (vendored/adapted, MIT) |
| `src/main/scala/m68k040/execute/regfile/RegfileService.scala` | `RegfileSpec`, port bundles, `RegfileService` + Int/Nzvc/X marker traits |
| `src/main/scala/m68k040/execute/regfile/RegFilePlugin.scala` | FiberPlugin: collect allocations, write-merge, instantiate+wire `RegFileAsync` |
| `src/test/scala/m68k040/execute/regfile/RegFileProbePlugin.scala` | test probe allocating/exposing ports |
| `src/test/scala/m68k040/execute/regfile/RegFileSpec.scala` | the §5 tests (int LVT + NZVC XOR) |

## 7. Open items / deferrals (logged)
- Fiber lifecycle: confirm consumers can allocate in `during setup` and the file builds in `during build` after all setups; add a `Retainer` if ordering needs it. (Slice 3 EUs are the real consumers; the probe stands in now.)
- `withReady` write back-pressure (NaxRiscv supports it) deferred — our ALU writes are unconditional latency-1; model `priority`+`sharingKey` merge but not ready-handshake unless a consumer needs it.
- Exact per-class port counts are set by Slice 3's EU config; this slice builds the mechanism and a representative probe set.
- Init-zero sweep adds `depth` boot cycles; acceptable (no fetch before first redirect). A faster init (Mem-init through the lowering) is a later option.
