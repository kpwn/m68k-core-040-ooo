# FMax closure, "Lever B": precompute `OperationDecoder`'s `size` at I-cache predecode time and carry it in `ChunkPredecode` (design)

## Context

Part of FMax-closure round 3/4 (full history:
`.superpowers/sdd/progress-fmax-levers-2026-08-08.md`). The binding session goal is
post-route FMax ≥ 200 MHz with margin. Eight levers have landed this round (frontend
Lever A tautological-mask deletion, LS/ROB Lever A/C, Lever U1 `ucPendPkt` reuse,
Lever D BTB late-select, the IQ scoreboard redundant-gate fix, the `pb_decode`
floorplan widen, and Frontend Lever C's new pipeline register).

This spec revives an idea that was **explicitly shelved** in the very first frontend
grounding pass of the round
(`.../scratchpad/fmax-frontend-dstea-ce-grounding-report.md` §4.2, recommendation #4:
"Do not scope Lever B into this slice … it is the right *next* lever if 173 MHz is not
enough"). The shelving decision was correct *then*. It is **wrong now**, for one
concrete reason established below in §1: **Frontend Lever C did not fix this family —
it isolated it.** The cone Lever B targets is now, by itself, the design's OOC WNS
holder.

Everything below is measured on the **current** tree (`18598b0`, all eight levers
landed) — the older report's numbers are stale and are NOT reused. Raw artifacts:
`.../scratchpad/lb/` (`base_18598b0_timing.rpt`, `base_18598b0_util.rpt`,
`leverb_probe_timing.rpt`, `leverb_probe_util.rpt`, `leverb_probe.diff`), worktree
`.../scratchpad/wt-leverb`. Companion report:
`.../scratchpad/fmax-leverb-precompute-report.md`.

---

## 0. Headline

| | baseline `18598b0` | Lever-B probe | Δ |
|---|---:|---:|---:|
| **OOC WNS** | **-1.216 ns** | **-0.824 ns** | **+0.392 ns** |
| **OOC FMax** | **191.72 MHz** | **207.30 MHz** | **+15.58 MHz** |
| OOC WNS-holding family | `raw.packets_1.words_0 → fed.specs_1.dstEa_disp` (21 levels) | `DcachePlugin tagMem_0 → ROB faultAddrStore_62/CE` (13 levels) | family **retired** |
| CLB LUTs | 107 357 | 108 584 | **+1 227 (+1.14 %)** |
| ├ LUT as Logic | 99 753 | 100 468 | +715 |
| └ LUT as Distributed RAM | 7 472 | 7 984 | +512 (`predMem`) |
| CLB Registers | 46 604 | 46 816 | +212 |
| Block RAM | 26 | 26 | 0 |
| `synth_design` | converged, 10:57 | **converged**, 16:40 | see §5 |

Three things this buys that no other live lever does:

1. It deletes **10 of the 21 logic levels** of the current OOC WNS path — a
   structural, placement-independent reduction, not a route-luck delta.
2. It costs **zero cycles of latency and zero IPC**. Unlike Frontend Lever C it adds
   no pipeline stage, no stall, no flush target.
3. The `moveLineSize` `synth_design` non-convergence — the one unexplained risk that
   made this area radioactive — **does not reproduce** (§5). That risk is retired
   empirically, not argued away.

---

## 1. What Frontend Lever C actually changed — and why it makes this lever live

Frontend Lever C (`a69419e` + `cbb09b9`, spec
`docs/superpowers/specs/2026-08-08-fmax-frontend-leverc-register-split-design.md`)
inserted a `raw` `PipeStage` between the Aligner and `computeOffload`:

```
before:  [ibuf + Aligner] → computeOffload → REG(fedIn→fed) → assemble
after:   [ibuf + Aligner] → REG(raw: packets) → computeOffload → REG(fedIn→fed) → assemble
```

Its spec predicted (its §3.2) that the *second* half — `raw → computeOffload → fedIn`
— would land at **+0.23 … +0.59 ns of slack**, i.e. become a non-issue, with the
binding constraint transferring to the `slot1Valid` cone in half 1 at ≈ -0.943 ns.

**That prediction did not hold.** The fresh OOC run on the full combined tree
(`synth/M68kFullCoreSynth_timing.rpt`, written 2026-08-08 15:19 at `18598b0`,
`RESULT FullCore WNS -1.216 FMAX 191.71779141104292` in `synth/leverc_ooc.out`)
reports **all eight** of its worst paths as one family:

```
Source:      _zz_DecodeStage_logic_raw_payload_packets_1_words_0_reg[7]/C
Destination: _zz_DecodeStage_logic_fed_payload_specs_1_dstEa_disp_reg[0,1,2,10..14]/D
Slack:       -1.216 ns     Data Path Delay: 5.197 ns (logic 0.927 / route 4.270)
Logic Levels: 21  (LUT2=6 LUT4=2 LUT5=3 LUT6=10)
```

That source register **is** Lever C's new `raw` stage. So half 2 is not slack — it is
the sole OOC WNS holder, at 5.197 ns against a 4.011 ns requirement. Lever C did not
remove this family; it *re-sourced* it from a flop, which is precisely the
precondition Lever B needs: `computeOffload` now starts at a register, so anything we
can hand it pre-computed lands at t≈0 instead of mid-cone.

The estimate's error is explicable and worth recording: Lever C's §3.2 derived half 2
from a *routed* checkpoint's cut-point arithmetic (3.108 ns tail + register overhead),
whereas this is an unplaced OOC estimate where 82 % of the path is generic route.
Both are real measurements of different things; the conclusion that matters — *half 2
is the binding half, not half 1* — is not sensitive to that difference, because half 1
does not appear in the top 8 at all.

---

## 2. The cone, cell by cell, on the current netlist

`base_18598b0_timing.rpt` lines 202-283. The path splits cleanly in two at
`_zz_DecodeStage_logic_fedIn_payload_specs_1_spec_size_1[0]`:

| lvl | cell | arrival | RTL meaning |
|---|---|---:|---|
| — | `raw_payload_packets_1_words_0_reg[7]/Q` | 0.107 | Lever C's `raw` register — the slot-1 opword |
| 1 | LUT2 `…specs_1_spec_size[0]_i_13` | 0.364 | `OperationDecoder.decode` |
| 2 | LUT6 `…spec_srcB_isAddr_i_3` | 0.588 | ″ (synthesis shares the table across OpSpec outputs) |
| 3 | LUT5 `…spec_srcB_isAddr_i_2` | 0.812 | ″ |
| 4 | LUT6 `…spec_sysKind[3]_i_1` | 1.048 | ″ |
| 5 | LUT5 `…spec_sysOp_i_5` | 1.272 | ″ |
| 6 | LUT2 `…spec_size[1]_i_24` | 1.496 | ″ |
| 7 | LUT2 `…spec_size[0]_i_9` | 1.726 | ″ |
| 8 | LUT6 `…spec_size[0]_i_5` | 1.950 | ″ |
| 9 | LUT6 `…spec_size[0]_i_1` → net fo=11 | **2.395** | **`spec.size` ready** |
| 10 | LUT2 `…dstEa_indexReg[3]_i_20` | 2.433 | `srcEaWordCount(…, size, …)` = `dstShift` |
| 11-13 | LUT6 `_i_18` / `_i_13` / `_i_7` (fo=36) | 3.105 | ″ |
| 14 | LUT2 `_i_12` (fo=35) | 3.391 | `shiftedWordsFor` select decode |
| 15-16 | LUT4 `dstEa_disp[8]_i_5` → LUT6 `_i_2` | 3.901 | `wordAtDynG` dynamic word mux |
| 17-18 | LUT2 `disp[31]_i_9` → LUT4 `disp[15]_i_7` | 4.460 | `EaDecoder.decode` mode/reg switch |
| 19-21 | LUT5 `disp[0]_i_7` → LUT6 `_i_3` → LUT6 `_i_1` | 5.179 | ″ → `fedIn.specs(1).dstEa.disp` |

**Segment budget:**

| segment | levels | delay | share |
|---|---:|---:|---:|
| `raw` reg → `OperationDecoder` → `spec.size` | **9** | **2.288 ns** | **44.0 %** |
| `spec.size` → `dstShift` → `shiftedWordsFor` → `EaDecoder` → `fedIn` | 12 | 2.832 ns | 54.5 % |

`OperationDecoder.decode(opword)` is a **pure function of the 16-bit opword** — every
input on the size sub-cone is an opword bit or a line/mode decode of one. It is
therefore exactly the kind of value `ChunkPredecode` already carries per word.

**Predicted effect (arithmetic, before the probe was run):** with `size` arriving from
the same `raw` flop group (0.107 + ~0.22 route ≈ 0.33 ns) the remainder re-based gives
0.33 + 2.832 = **3.16 ns → slack ≈ +0.85 ns**, i.e. the family stops being a limiter
outright rather than merely improving. §4 shows the probe confirmed this: the family
vanished from the worst-8 list entirely.

### 2.1 Which predicate(s) are actually needed — re-derived on the CURRENT cone

The stale report speculated that a **1-bit `sizeIsLong`** might suffice. Re-derived
from the current RTL and the trace above:

* `MicroOpAssembler.srcEaWordCount` (`MicroOpAssembler.scala:405-425`) reads `size`
  **only** in the `#imm` arm: `Mux(size === Size.LONG, 2, 1)`. → 1 bit.
* `EaDecoder.decode` (`EaDecoder.scala:44-48`, `:187-193`) reads `size` for
  `autoDeltaOf` — a genuine **3-way** mux (`BYTE→1/2, WORD→2, LONG→4`) — and for the
  `#imm` `e.imm` width. → **2 bits**.

The measured critical chain runs through `srcEaWordCount` only (levels 10-13 above:
`size → dstShift`), so a 1-bit `sizeIsLong` *would* cut the measured path. But:

* `autoDelta` and `e.imm` would then still depend on the deep `spec.size`, leaving a
  residual `OperationDecoder → dstEa` arc that a later placement change could re-expose;
* the measured storage cost of the full 2-bit field is **+512 distributed-RAM LUTs and
  +212 FFs** out of 107 357 — 0.5 % — so narrowing it saves essentially nothing.

**Decision: bake the full 2-bit `Size`.** It removes `size` from the cone completely,
with no residual and no reachability argument. This also supersedes the stale report's
"+17 % vs +33 % predMem" trade-off, which was a real trade-off only under an
assumption (that storage dominates) the measurement disproves.

---

## 3. The fix

### 3.1 `ChunkPredecode` gains a `size` field

`src/main/scala/m68k040/cache/IcacheTypes.scala:18-36`

```scala
case class ChunkPredecode() extends Bundle {
  val simple        = Bool()
  val lenWords      = UInt(4 bits)
  val ambiguousLine = Bool()
  // FMax Lever B: OperationDecoder.decode(op).size for THIS word, baked at I-cache
  // REFILL time so DecodeStage's post-`raw` offload cone need not re-derive it in
  // series with the dst-EA decode. Pure function of the opword; unlike lenWords it
  // needs NO lookahead word, so it is never `ambiguousLine`-qualified.
  val size          = m68k040.isa.Size()
}
```

6 → 8 bits. `IcachePlugin.PRED_BITS_PER_WORD` / `PRED_BITS_PER_LINE`
(`IcachePlugin.scala:116-118`) and `windowPred`'s `subdivideIn`
(`IcachePlugin.scala:266-269`) are already parameterised on
`ChunkPredecode().getBitsWidth` and follow automatically — verified: the probe's
generated Verilog shows `reg [255:0] IcachePlugin_logic_predMem_0 [0:63]` (was
`[191:0]`), with no other edit to `IcachePlugin.scala`.

### 3.2 `PredecodeWord.classify` bakes it

`src/main/scala/m68k040/frontend/PredecodeWord.scala:47-53`, one line beside the other
defaults:

```scala
r.ambiguousLine := False
r.size          := m68k040.decode.OperationDecoder.decode(op).size
```

**This is deliberately a call to the real decoder, not a re-derivation.** It converts
what the stale report called "the most expensive part of this lever — a cross-decoder
65536-opword equivalence obligation" into **equality by construction**: the same
function applied to the same word. There is no second implementation to keep in sync,
and therefore no decoder-equivalence proof to write (§6 states what *does* remain).

Replication: 32 instances (`IcachePlugin.scala:594-601`, one per word of the 64-byte
line). `FetchAlignPlugin`'s `p0LiveReg` (`:381`) is a 33rd *call site*, but §3.4 takes
`size` from `preds(0)` rather than from the `p0` mux, so `p0LiveReg.size` is dead and
is pruned — it is not a 33rd instance in hardware.

### 3.3 Plumbing (mechanical, 4 sites)

* `InstructionBuffer.scala:127` — `entries(s).pred.size := io.push.payload.preds(j).size`
* `InstructionBuffer.scala:163` — out-of-range `io.headPred(i)` default. The
  co-located defaults set `io.head(i) := 0`, so the invariant-preserving value is
  `OperationDecoder.decode(B(0, 16 bits)).size` = **`Size.BYTE`** (opword `0x0000` is
  `ORI.B #imm,D0`). Do **not** use `LONG` — §6's pairing gate would flag it.
* `FetchAlignPlugin.scala:204` — push default-drive, same `Size.BYTE` reasoning.
* `FetchAlignPlugin.scala:301` — `preds(j).size := rspPreds(srcIdx).size`.
* `FetchAlignPlugin.scala:556` — the synthetic faulted packet zeroes `words`, so it
  must set `size := Size.BYTE` for the same reason.

### 3.4 `DecodePacket` carries it; the Aligner fills it

`DecodePacket.scala` gains `val size = m68k040.isa.Size()`.
`Aligner.align` assigns it in the three arms that build a packet:

```scala
// complex slot0 arm (Aligner.scala:~149)
r.slot0.size := preds(0).size
// simple slot0 arm (Aligner.scala:~204)
r.slot0.size := preds(0).size
// slot1 arm (Aligner.scala:~280)
r.slot1.size := p1.size            // p1 = preds(L0), the SAME mux that yields L1
```

Two points that are load-bearing:

* **slot 0 takes `preds(0).size`, not `p0.size`.** `p0 = Mux(preds(0).ambiguousLine,
  p0LiveReg, preds(0))` (`Aligner.scala:85`). `ambiguousLine` exists because
  `lenWords` can need a lookahead word that was past the refill-time line boundary;
  `size` needs **only the opword**, which is `words(0)` in both cases, so both mux
  inputs carry the identical value. Bypassing the mux is therefore behaviour-identical
  *and* keeps `size` off the `L0` arrival chain.
* **slot 1 reuses the existing `preds(L0)` mux.** No new mux rank — `p1` is already
  formed for `L1`; the mux merely gets 2 bits wider.

### 3.5 `computeOffload` consumes it — and the standalone path does NOT

`MicroOpAssembler.scala:463-472`. This split is required, not cosmetic:

```scala
/** The DecodeStage path: `size` arrives pre-computed on the packet. */
def computeOffload(pkt: DecodePacket): Offload = {
  val o = Offload()
  o.spec  := OperationDecoder.decode(pkt.words(0))   // UNCHANGED - still feeds the OpSpec
  val sz  = pkt.size                                  // LEVER B: not o.spec.size
  o.srcEa := srcEaFor(pkt, sz)
  val dstEaField = pkt.words(0)(8 downto 6) ## pkt.words(0)(11 downto 9)
  val dstShift   = srcEaWordCount(pkt.words(0)(5 downto 3).asUInt,
                                  pkt.words(0)(2 downto 0).asUInt, sz, pkt.words)
  o.dstEa := EaDecoder.decode(dstEaField, sz, shiftedWordsFor(pkt.words, dstShift))
  o
}

/** Reference form: derives `size` from the decoder, ignoring `pkt.size`. Used by the
  * standalone `assemble(pkt)` overload AND as the pairing gate's reference model. */
def computeOffloadFromWords(pkt: DecodePacket): Offload = { /* same body, sz = OperationDecoder.decode(pkt.words(0)).size */ }
```

`o.spec` is deliberately **not** removed — the full `OpSpec` (including `spec.size`)
still rides `fedIn.payload.specs(i)` for `assemble`. The lever removes
`OperationDecoder` from the *serial chain into `dstEa`*, not from the design; the
`raw → spec_*` endpoints remain a shallow ~2.3 ns reg-to-reg path with ~1.7 ns of
slack, which is where they belong.

`assemble(pkt)` (the 1-arg overload) must route to `computeOffloadFromWords` — it is
used by **~14 unit specs** that construct `DecodePacket`s by hand
(`CrackStoreSpec`, `MicroOpAssemblerSpec`, `PackUnpkDecodeSpec`, `MemRmwDecodeSpec`,
`CrackLoadSpec`, `AddxSubxDecodeSpec`, `BcdDecodeSpec`, `Cmp2Chk2DecodeSpec`,
`BitOpDecodeSpec`, `TrapccDecodeSpec`, …) and would otherwise silently read an
unassigned `pkt.size`. The `assemble(pkt, spec)` and `assemble(pkt, offload)`
overloads are unaffected (they already build their own `Offload`).

---

## 4. Measured effect — full-core OOC A/B

Worktree `.../scratchpad/wt-leverb` (detached `18598b0` + the probe diff in
`.../scratchpad/lb/leverb_probe.diff`, 7 files / 20 insertions). Probe is the design
above minus §3.5's `computeOffloadFromWords` split and with `Size.LONG` placeholders
where §3.3 specifies `Size.BYTE` — both timing-neutral, both fixed in the real change.
Same flow, same `synth/ooc_M68kFullCoreSynth.tcl`, same part.

```
baseline 18598b0 :  RESULT FullCore WNS -1.216  FMAX 191.71779141104292
Lever-B probe    :  RESULT FullCore WNS -0.824  FMAX 207.29684908789386
```

**+0.392 ns / +15.58 MHz at OOC**, and the family is *retired*, not merely improved:
the probe's worst-8 endpoints are all
`DcachePlugin_logic_tagMem_0_reg/CLKARDCLK → RobPlugin_logic_faultAddrStore_62_reg[*]/CE`
(4.673 ns, **13** logic levels, 60 % route) — i.e. the long-standing LS/ROB family
documented in `.../scratchpad/fmax-robplugin-dominant-grounding-report.md`. No
`dstEa` endpoint appears anywhere in the probe's worst-8, so the frontend family is
now better than -0.824 ns; §2's arithmetic puts it near +0.85 ns.

### 4.1 Area, measured

| | baseline | probe | Δ |
|---|---:|---:|---:|
| CLB LUTs | 107 357 | 108 584 | +1 227 (+1.14 %) |
| LUT as Logic | 99 753 | 100 468 | **+715** (the 32× size decode) |
| LUT as Distributed RAM | 7 472 | 7 984 | **+512** (`predMem` 192→256 b/line/way × 4 ways × 2 read ports) |
| CLB Registers | 46 604 | 46 816 | +212 (ibuf 20 entries, `rspPredReg`, `raw`/`fed` packets) |
| Block RAM Tile | 26 | 26 | 0 |

+715 logic LUTs for 32 replicated size decoders is ≈22 LUTs each — the size sub-cone
of `OperationDecoder` is far smaller than the whole table, and Vivado prunes the other
~40 `OpSpec` fields at every one of those instances.

### 4.2 A cheaper variant exists, and is deliberately NOT specced

`ChunkPredecode.size` could instead be populated at the **I-cache S1→rsp register
stage** (`IcachePlugin.scala:252-275`, 4 words per response instead of 32 per line),
leaving `predMem` at 6 bits and dropping the distributed-RAM cost to zero — ~4×
replication instead of 32×. It is genuinely attractive on area (≈-600 LUTs vs the form
above) but it moves a ~2.3 ns decode onto a *different, unmeasured* timing arc
(`rspDataReg → ibuf entries`). **This spec does not adopt it**, on the round's standing
rule: spec what was measured. Record it as a follow-up area optimisation to be gated
on its own A/B, after this lever has landed and been post-route-gated.

---

## 5. The `moveLineSize` non-convergence risk — RETIRED, empirically

The one unexplained hazard in this area: a `moveLineSize` throwaway
(`.../scratchpad/fmax-movelinesize-attribution-report.md` §5) made `synth_design`
**fail to converge in Timing Optimization** — killed at 65 min and again at 58 min 32 s
on an *idle* machine, versus an 8 min 01 s baseline, reproduced twice. Its
hypothesised mechanism applies verbatim to Lever B: "with `size` no longer arriving
from a deep, slow cone, the whole `shiftedWordsFor` + `EaDecoder` cone collapses into
a large *shallow* function of raw opword bits, and Vivado's timing optimizer explores
a vastly larger restructuring space."

**It does not reproduce.**

| | baseline `18598b0` | Lever-B probe |
|---|---:|---:|
| Finished Timing Optimization (cumulative elapsed) | 10:01 | **16:40** |
| `synth_design` outcome | completed | **completed, 0 errors, 0 critical warnings** |

Both runs were on the same contended machine (a sibling-project `vivado full_impl` and
this project's own gate were live throughout; the probe waited for an 11 GB memory
window before launching). The probe is ~1.66× slower through Timing Optimization —
notable, worth re-measuring on an uncontended machine, and **nothing like** the
6-7×-and-never-finishes signature of `moveLineSize`.

There is also a principled reason to expect the difference, which the measurement now
corroborates: `moveLineSize` derived `size` as a 4-input function of `op[15:12]`, so
`size` and the shifted-word network remained functions of **the same opword bits** and
the optimizer could restructure across the whole cone. Under Lever B `size` is an
**independent register output** — opaque to the optimizer — so no such cross-cone
folding is available.

**Residual obligation:** the implementation gate must record `synth_design` wall time
alongside WNS and flag any regression beyond ~1.5× on an uncontended run.

---

## 6. Correctness: what must be proven, and how

Because §3.2 *calls* `OperationDecoder.decode`, there is **no cross-decoder
equivalence obligation**. What remains is a single pairing/alignment invariant:

> **INV**: for every `DecodePacket` the Aligner emits,
> `pkt.size === OperationDecoder.decode(pkt.words(0)).size`.

Failure modes it covers, all of them plumbing:
`preds(i)` indexed differently from `words(i)`; the ibuf out-of-range default; the
push default-drive; the synthetic faulted packet; the slot-1 `preds(L0)` vs
`words(L0)` pairing; a stash FSM holding a packet whose `size` was captured under a
different enable.

**Gate: extend `FedSpecsPacketPairingSpec`** (`src/test/scala/m68k040/decode/`, added
by `cbb09b9` as Frontend Lever C's own guard). It already runs a real
`IcachePlugin + FetchAlignPlugin + DecodeStage` frontend and, every cycle, recomputes
`MicroOpAssembler.computeOffload(fed.payload.packets(i))` from the packet actually
present in `fed`, comparing the **whole** `Offload` (OpSpec + srcEa + dstEa) bit for
bit against the carried `specs`. The single change needed: point the test-side
recompute at **`computeOffloadFromWords`** (§3.5) so it is a genuine reference model
rather than a tautology. A one-word or one-cycle `size` misalignment then shows up
immediately, in steady state, under backpressure, and across `pipeFlush` — the same
four stash-driving programs the existing test already uses.

Two smaller obligations:

* **`Size.BYTE` defaults (§3.3).** Three sites (`InstructionBuffer:163`,
  `FetchAlignPlugin:204`, `FetchAlignPlugin:556`) pair a zeroed word with a `size`.
  `decode(0x0000).size === Size.BYTE` (`ORI.B`). Assert this once at elaboration, or
  simply let the pairing gate catch it — the faulted-packet path in particular is
  exercised by `FetchFaultSpec`.
* **Standalone-`assemble` routing (§3.5).** Covered by the existing ~14 hand-built-
  packet decode specs: if `assemble(pkt)` were left reading `pkt.size`, they fail
  immediately (unassigned enum). This is a compile/test-visible failure, not a silent
  one.

No new architectural state, no new flush target, no new stall condition, **no IPC
change and no latency change** — this is a pure combinational re-sourcing of one
already-computed value.

---

## 7. Scope, and what this spec does NOT claim

* **Files touched:** `IcacheTypes.scala`, `PredecodeWord.scala`,
  `InstructionBuffer.scala`, `FetchAlignPlugin.scala`, `DecodePacket.scala`,
  `Aligner.scala`, `MicroOpAssembler.scala` (7 files), plus
  `FedSpecsPacketPairingSpec.scala`. The probe diff is 20 insertions / 4 deletions;
  the real change adds §3.5's overload split and the `Size.BYTE` corrections, so
  budget ~60-80 lines of RTL and ~40 lines of test.
* **`IcachePlugin.scala` needs no edit.** Verified against the probe: every width is
  already derived from `ChunkPredecode().getBitsWidth`.
* **The +15.58 MHz figure is OOC, not post-route.** It must be gated post-route,
  double-run, on an uncontended machine, per the standing rule. Two reasons to expect
  it to survive: (a) the win is 10 deleted *logic levels* out of 21, which no
  placement can restore; (b) historically this family ranks **worse** post-route than
  at OOC — at `b6888f6` it had 1.0 ns of OOC headroom while holding post-route WNS —
  so being the OOC WNS holder today is, if anything, an understatement.
* **This does not by itself reach 200 MHz post-route.** It hands WNS to the
  `DcachePlugin tagMem → ROB faultAddrStore` family at -0.824 ns OOC, which is the
  target of the separately-tracked floorplan work. The honest framing is: this is the
  frontend's last large structural lever, and it makes the LS/ROB family the sole
  remaining one.
