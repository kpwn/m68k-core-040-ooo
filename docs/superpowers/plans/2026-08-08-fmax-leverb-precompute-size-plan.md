# FMax "Lever B": precompute size at I-cache predecode time — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bake `OperationDecoder.decode(op).size` into `ChunkPredecode` at
I-cache refill time (a pure function of the opword, already computed
there), carry it through `DecodePacket`, and consume it in
`computeOffload` instead of re-deriving it in series with the dst-EA
decode. Measured (not estimated): OOC WNS -1.216 -> -0.824ns
(+15.58MHz), the targeted family retired entirely from the worst-8.
Zero latency, zero IPC cost.

**Architecture:** `ChunkPredecode` gains a `size: Size()` field (6->8
bits). `PredecodeWord.classify` sets it by CALLING
`OperationDecoder.decode(op).size` (not re-deriving — equality by
construction, no cross-decoder proof needed). Plumbed through
`InstructionBuffer`/`FetchAlignPlugin`/`Aligner`/`DecodePacket` to
`computeOffload`, which gains a `computeOffloadFromWords` reference-model
sibling for the standalone `assemble(pkt)` overload and the pairing
test's reference model.

## Global Constraints

- Design spec (read in full before starting — it already has exact code
  for every site):
  `docs/superpowers/specs/2026-08-08-fmax-leverb-precompute-size-design.md`
- This is a PURE combinational re-sourcing of an already-computed value
  — no new architectural state, no new flush target, no new stall
  condition, no IPC change, no latency change. The correctness gate is a
  single pairing invariant (§6), not a cross-decoder equivalence proof
  (that obligation doesn't exist here — `classify` CALLS the real
  decoder).
- Bake the FULL 2-bit `Size` field, not a narrower 1-bit variant (§2.1
  of the spec proves the narrow variant leaves a residual arc and saves
  negligible storage).
- `IcachePlugin.scala` needs NO edit — verified in the probe, all widths
  already derive from `ChunkPredecode().getBitsWidth`.
- The `Size.BYTE` default at the 3 named sites (`InstructionBuffer:163`,
  `FetchAlignPlugin:204`, `FetchAlignPlugin:556`) is load-bearing — do
  NOT default to `LONG` (verify: `OperationDecoder.decode(0x0000).size
  === Size.BYTE`, the `ORI.B` opcode).
- `~/sbt/bin/sbt compile` must stay clean throughout.
- `ExecuteLockStepSpec` must stay at the current baseline (check
  `.superpowers/sdd/progress-fmax-levers-2026-08-08.md` for the exact
  count, since it may have shifted since the design spec was written).
- Full ported corpus (~870 tests) must show zero new regressions.
- **Machine contention note**: this round has had a synth job SEGFAULT
  from memory exhaustion (17 concurrent Vivado processes, 30GB on a
  29GB box). Before launching any synth run, check `free -g`/`ps aux
  --sort=-%mem` and wait for a reasonable memory window if the machine
  is heavily loaded (the design spec's own probe did exactly this — "the
  probe waited for an 11GB memory window before launching"). Do not
  force a run into a machine that's already near OOM.
- **This lever's own +15.58MHz is OOC only** — per the design spec's
  §7, it must be gated post-route, double-run, on as uncontended a
  machine as practical, before being trusted as final.

---

### Task 1: Add the `size` field, plumb it through, verify with the pairing gate

**Files:**
- Modify: `src/main/scala/m68k040/cache/IcacheTypes.scala` (`ChunkPredecode`
  gains `size`)
- Modify: `src/main/scala/m68k040/frontend/PredecodeWord.scala` (bake it
  via a real `OperationDecoder.decode` call)
- Modify: `src/main/scala/m68k040/frontend/InstructionBuffer.scala` (2
  sites: entry write, out-of-range default)
- Modify: `src/main/scala/m68k040/frontend/FetchAlignPlugin.scala` (3
  sites: push default-drive, `preds(j).size` assignment, synthetic
  faulted-packet default)
- Modify: `src/main/scala/m68k040/frontend/DecodePacket.scala` (gains
  `size` field)
- Modify: `src/main/scala/m68k040/frontend/Aligner.scala` (3 arms:
  complex slot0, simple slot0, slot1 — assign from `preds(0).size`/
  `p1.size` per the design spec's exact load-bearing reasoning, NOT
  from the `p0` ambiguousLine mux)
- Modify: `src/main/scala/m68k040/decode/MicroOpAssembler.scala`
  (`computeOffload` consumes `pkt.size`; new
  `computeOffloadFromWords` reference-model sibling; `assemble(pkt)`
  1-arg overload routes to `computeOffloadFromWords`)
- Test: `src/test/scala/m68k040/decode/FedSpecsPacketPairingSpec.scala`
  (Frontend Lever C's own guard, added by `cbb09b9`) — point its
  test-side recompute at `computeOffloadFromWords`

**Interfaces:**
- `ChunkPredecode.size: m68k040.isa.Size()` (new field, 6->8 bits total).
- `DecodePacket.size: m68k040.isa.Size()` (new field).
- `MicroOpAssembler.computeOffload(pkt: DecodePacket): Offload` (existing
  signature UNCHANGED, internal implementation now reads `pkt.size`
  instead of re-deriving from `OperationDecoder.decode(pkt.words(0)).size`
  for the dst-EA-affecting computations only; `o.spec` itself is
  UNCHANGED, still computed via the full decode).
- `MicroOpAssembler.computeOffloadFromWords(pkt: DecodePacket): Offload`
  (new, same body but derives `size` from `OperationDecoder.decode`
  directly — the reference model).

- [ ] **Step 1: Re-verify exact current line numbers**

```bash
grep -n "case class ChunkPredecode" src/main/scala/m68k040/cache/IcacheTypes.scala
grep -n "r.ambiguousLine := False" src/main/scala/m68k040/frontend/PredecodeWord.scala
grep -n "entries(s).pred\|io.headPred(i)" src/main/scala/m68k040/frontend/InstructionBuffer.scala
grep -n "preds(j).size\|push default\|faulted packet" src/main/scala/m68k040/frontend/FetchAlignPlugin.scala
grep -n "def computeOffload\|def assemble" src/main/scala/m68k040/decode/MicroOpAssembler.scala
```
Confirm the design spec's citations still match live source (the spec's
own numbers, e.g. `Aligner.scala:~149/~204/~280`, are marked with `~`
meaning approximate — re-verify precisely before editing).

- [ ] **Step 2: `ChunkPredecode` gains `size`**

Per design spec §3.1, exact code given.

- [ ] **Step 3: `PredecodeWord.classify` bakes it**

Per design spec §3.2, exact code given (`r.size :=
m68k040.decode.OperationDecoder.decode(op).size`).

- [ ] **Step 4: Plumbing (4 sites)**

Per design spec §3.3 exactly — `InstructionBuffer.scala` entry write +
out-of-range default (`Size.BYTE`), `FetchAlignPlugin.scala` push
default-drive (`Size.BYTE`) + `preds(j).size := rspPreds(srcIdx).size`
+ synthetic faulted-packet default (`Size.BYTE`).

- [ ] **Step 5: `DecodePacket` gains `size`; `Aligner.align` fills it**

Per design spec §3.4 exactly — 3 arms, with the load-bearing reasoning
(slot 0 reads `preds(0).size` NOT `p0.size`; slot 1 reads `p1.size`,
the existing `preds(L0)` mux, no new mux rank).

- [ ] **Step 6: `computeOffload` consumes it; add `computeOffloadFromWords`**

Per design spec §3.5 exactly. Route `assemble(pkt)` (1-arg overload) to
`computeOffloadFromWords`.

- [ ] **Step 7: Compile**

```bash
~/sbt/bin/sbt compile
```
Expected: the ~14 hand-built-packet decode specs named in §3.5 should
fail to compile ONLY if `assemble(pkt)` was left reading `pkt.size`
directly (an unassigned enum) — if you see compile failures here,
confirm Step 6's routing is correct, don't work around them.

- [ ] **Step 8: Extend `FedSpecsPacketPairingSpec`**

Point its test-side recompute at `computeOffloadFromWords` (per design
spec §6) so it's a genuine reference-model comparison, not a tautology.

- [ ] **Step 9: Run the pairing gate**

```bash
~/sbt/bin/sbt "testOnly m68k040.decode.FedSpecsPacketPairingSpec"
```
Expected: PASS. This is the load-bearing correctness proof (§6) — a
one-word or one-cycle `size` misalignment shows up immediately under
backpressure and across `pipeFlush`.

- [ ] **Step 10: The ~14 hand-built-packet decode specs**

Run `CrackStoreSpec`, `MicroOpAssemblerSpec`, `PackUnpkDecodeSpec`,
`MemRmwDecodeSpec`, `CrackLoadSpec`, `AddxSubxDecodeSpec`,
`BcdDecodeSpec`, `Cmp2Chk2DecodeSpec`, `BitOpDecodeSpec`,
`TrapccDecodeSpec` (and any others the grep in Step 1 finds using
`assemble(pkt)`) — confirm full pass, proving the routing to
`computeOffloadFromWords` is correct.

---

### Task 2: Full verification suite, synth-gate

- [ ] **Step 1: `AlignerSpec`/`FetchAlignSpec`**

```bash
~/sbt/bin/sbt "testOnly m68k040.frontend.AlignerSpec"
~/sbt/bin/sbt "testOnly m68k040.frontend.FetchAlignSpec"
```
Expected: same baseline as recorded in the current ledger.

- [ ] **Step 2: `ExecuteLockStepSpec` full suite**

Expected: current baseline (check the ledger).

- [ ] **Step 3: Full ported test corpus (isolated worktree, before/after)**

`tools/fuzz/ported-sweep-parallel.sh` against the current baseline.
Expect byte-identical fail-name lists.

- [ ] **Step 4: Check machine load before synth**

```bash
free -g; ps aux --sort=-%mem | head -10; uptime
```
If heavily loaded (< ~10GB free, load average well above core count),
wait for a reasonable window rather than forcing a run — this round has
had a real OOM segfault from over-contention.

- [ ] **Step 5: OOC-synth-only gate**

```bash
vivado -mode batch -source synth/ooc_M68kFullCoreSynth.tcl
```
Expect to reproduce/exceed the probe's +15.58MHz (-1.216 -> -0.824ns).
**Record `synth_design` wall time explicitly** and compare against the
design spec's §5 baseline (10:01) — flag any regression beyond ~1.5x on
an uncontended run as the residual `moveLineSize`-class risk obligation.

- [ ] **Step 6: Real post-route gate**

```bash
vivado -mode batch -source synth/impl_FullCore.tcl
```
Report WNS/FMax, and identify the new worst-path family (design spec
§7 predicts it will be the LS/ROB `tagMem -> faultAddrStore` family,
already the target of separately-tracked floorplan work).

- [ ] **Step 7: Commit**

```bash
git add src/main/scala/m68k040/cache/IcacheTypes.scala \
        src/main/scala/m68k040/frontend/PredecodeWord.scala \
        src/main/scala/m68k040/frontend/InstructionBuffer.scala \
        src/main/scala/m68k040/frontend/FetchAlignPlugin.scala \
        src/main/scala/m68k040/frontend/DecodePacket.scala \
        src/main/scala/m68k040/frontend/Aligner.scala \
        src/main/scala/m68k040/decode/MicroOpAssembler.scala \
        src/test/scala/m68k040/decode/FedSpecsPacketPairingSpec.scala
git commit -m "frontend: precompute size at I-cache refill time via ChunkPredecode (FMax Lever B)"
```

## Self-Review Note

Two tasks (RTL+plumbing+pairing-gate, then full verification+synth)
matching this round's established pattern for larger levers. The
pairing-gate proof (Task 1) is the whole correctness argument and
should pass before spending time on the broader suite.
