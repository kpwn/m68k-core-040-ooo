# FMax closure, "Frontend Lever C": split the Aligner→offload cone with a pipeline register (design)

**Historical timing note (2026-08-10):** this document predates the registered
BTB/RAS fallback action in
`2026-08-09-ipc-fetch-directed-btb-token-pipeline-amendment.md`. References
below to a same-cycle `predictFire` redirect describe the then-current baseline,
not the live frontend contract.

## Context

Part of FMax-closure round 3 (full history:
`.superpowers/sdd/progress-fmax-levers-2026-08-08.md`). The binding session
goal is post-route FMax ≥ 200 MHz with margin; the current combined,
double-run, uncontended gate is **~176 MHz (WNS -1.693ns)** at `d357f85`,
with three tied, netlist-independent worst-path families.

This spec targets family #1: **`FetchAlignPlugin ibuf pred_lenWords →
DecodeStage fed_payload_specs_*_dstEa_*`**, the design's current WNS
holder at **-1.693ns**.

**Two prior narrower attempts on this exact cone both failed**, for
documented reasons: Slice 3 ("chained shift collapse", `1b64ccc`) had its
mechanism disproven from the netlist and was reverted (`df13342`); a
`moveLineSize` throwaway targeted the wrong bottleneck *and* caused an
unexplained `synth_design` non-convergence. The one success on this cone
(Frontend Lever A, `2426dbd`, +0.559ns) came from deleting genuinely
redundant work, not from shaving depth inside the decode tables. A
strategic review this round therefore concluded: **stop attempting narrow
logic-depth reductions inside `OperationDecoder`/`EaDecoder` and commit to
the structural fix** — a new pipeline register that cuts this cone in two.

That option was sketched as "Lever C" in the original grounding report
(`.../scratchpad/fmax-frontend-dstea-ce-grounding-report.md` §4.3). This
spec is the re-grounded, hazard-resolved version of it. **All numbers
below are freshly measured on the CURRENT checkpoint**
(`synth/fullcore_routed.dcp`, written 2026-08-08 08:59, tree at `d357f85`,
i.e. both round-2 levers landed, design WNS -1.693ns confirmed
in-session). The older report's numbers are stale and are NOT reused;
where the two disagree, this spec's numbers win. Raw artifacts:
`.../scratchpad/cc/` (`c_worst_slot0.rpt`, `c_worst_slot1.rpt`,
`c_half1_top.txt`, `c_half1_sv.txt`, `c_specs_top.txt`,
`c_half2_s0.rpt`, `c_half2_s1.rpt`, `c_slot1valid.rpt`,
`c_loop_hp2hp.rpt`, `c_len2hp.rpt`, `c_half2_proxy.txt`, `probe{1..4}.tcl`).

---

## 1. The cone, re-traced cell by cell on the current checkpoint

### 1.1 The design's worst path (slot 0)

`report_timing -from FetchAlignPlugin_logic_ibuf/entries_10_pred_lenWords_reg[1]/C
-to _zz_DecodeStage_logic_fed_payload_specs_0_dstEa_disp_reg[6]/D` (`c_worst_slot0.rpt`)

```
Slack (VIOLATED) :  -1.693ns      ← this IS the design WNS
Data Path Delay  :   5.674ns  (logic 2.019ns 35.6%  /  route 3.655ns 64.4%)
Logic Levels     :  17  (LUT6=11, LUT5=3, LUT4=1, LUT3=1, LUT2=1)
```

| lvl | arrival | net / cell | RTL meaning |
|---|---:|---|---|
| — | 0.108 | `ibuf/entries_10_pred_lenWords_reg[1]/Q` | `InstructionBuffer.entries(10).pred.lenWords[1]` |
| 1-3 | 1.397 | → `ibuf_io_headPred_0_lenWords[1]` | head barrel rotate (`InstructionBuffer.scala:149-159`) |
| 4 | 1.967 | LUT3 → fo=**140** | **`p0 = Mux(preds(0).ambiguousLine, p0LiveReg, preds(0))` → `L0`** (`Aligner.scala:85-86`) |
| 5 | 2.078 | LUT6 | slot-0 valid / stall cone (`Aligner.scala:143-148`) |
| 6 | **2.596** | LUT6 → `slot1ValidOut_INST_0_i_4_n_0`, fo=58 | **← THE CUT POINT.** Last cell whose output is still an *Aligner-result* value |
| 7 | 2.979 | LUT5 (cell named `…fed_payload_packets_0_words_0[7]_i_1`) → net `_zz_when_OperationDecoder_l39[1]`, fo=101 | the boundary LUT: synthesis **merged** the slot-0 opword select with `OperationDecoder`'s first gate |
| 8-11 | 4.034 | → `…fedIn_payload_specs_0_spec_size_1[0]` | `OperationDecoder.decode(...).size` |
| 12-16 | 5.487 | `dstEa_disp[19]_i_6` → `dstEa_disp[5]_i_7/i_2` → `dstEa_disp[14]_i_6` → `dstEa_disp[6]_i_5` | `srcEaWordCount` → `dstShift` → `shiftedWordsFor` → `EaDecoder` (`MicroOpAssembler.scala:405-441`, `:470`) |
| 17 | 5.704 | → `_zz_…fed_payload_specs_0_dstEa_disp_reg[6]/D` | `DecodeStage.scala:88`'s `fedIn.payload.specs(0)` |

### 1.2 The slot-1 sibling (`c_worst_slot1.rpt`, -1.518ns, 5.499ns, 16 levels)

Identical head through `L0` @ 2.018, then:
`packets_1_words_0[15]_i_3` (LUT5, `words(idx)` mux rank 1) @ 2.257 →
LUT5 whose output is `switch_OperationDecoder_l24_1[3]` @ 2.587 — again
the boundary LUT is **merged**: the final `words(L0)` mux rank and
`OperationDecoder`'s line switch share one LUT. Cut point = **2.257**.

### 1.3 The split, measured (NOT the stale 51/49)

| | slot 0 | slot 1 |
|---|---:|---:|
| **frontend half** (`ibuf` + `Aligner`, launch → cut point) | 2.566ns | 2.227ns |
| **decode-offload half** (`computeOffload`, cut point → `fedIn` reg) | 3.108ns | 3.272ns |
| ratio | **45.2 / 54.8** | **40.5 / 59.5** |

**The original report's 51.1/48.9 no longer holds** — Frontend Lever A
(`2426dbd`) deleted exactly the `L1 → opword zero-mask` segment, which
lived in the frontend half. The split is now ≈**43/57**, i.e. the
decode-offload half is the *larger* one. The structural precondition for
this lever — two roughly-balanced halves, each ~2.2-3.3ns inside a 4.0ns
period — **still holds**, and the cut is now slightly *better* positioned
than the older report assumed (the bigger half is the one that gets a
whole fresh cycle).

---

## 2. The fix

`DecodeStage.scala:78-92`, today, is **one** cycle carrying **both** the
Aligner packets and the fully-computed `Offload`:

```scala
val fedIn = Stream(FedPacket())
fedIn.valid              := df.feed.valid
fedIn.payload.packets(0) := df.feed.payload(0)
fedIn.payload.packets(1) := df.feed.payload(1)
fedIn.payload.slot1Valid := df.slot1Valid
fedIn.payload.specs(0)   := MicroOpAssembler.computeOffload(df.feed.payload(0))   // :88
fedIn.payload.specs(1)   := MicroOpAssembler.computeOffload(df.feed.payload(1))   // :89
df.feed.ready := fedIn.ready
val fed = PipeStage(fedIn, pipeFlush)                                              // :92
```

Lever C inserts a **new** `PipeStage` *before* `computeOffload`, leaving
`fedIn`/`fed` as the *second* of two registers:

```
today:   [ibuf + Aligner] → computeOffload → REG(fedIn→fed: packets+specs) → assemble
after:   [ibuf + Aligner] → REG(raw: packets)  →  computeOffload  →  REG(fedIn→fed: packets+specs) → assemble
```

```scala
// NEW: the raw Aligner-output payload — packets + slot1Valid, and NOTHING else.
case class RawPacket() extends Bundle {
  val packets    = Vec(DecodePacket(), 2)
  val slot1Valid = Bool()
}

val rawIn = Stream(RawPacket())
rawIn.valid              := df.feed.valid
rawIn.payload.packets(0) := df.feed.payload(0)
rawIn.payload.packets(1) := df.feed.payload(1)
rawIn.payload.slot1Valid := df.slot1Valid
df.feed.ready            := rawIn.ready

// The SAME pipeFlush that already squashes `fed`, the queue, `pushReg` and every
// stash/FSM pending marker. See §4.2 for why this is sufficient and complete.
val raw = PipeStage(rawIn, pipeFlush)

// UNCHANGED in shape — only the SOURCE moves from `df.feed.payload(i)` (combinational
// aligner output) to `raw.payload.packets(i)` (registered aligner output).
val fedIn = Stream(FedPacket())
fedIn.valid              := raw.valid
fedIn.payload.packets(0) := raw.payload.packets(0)
fedIn.payload.packets(1) := raw.payload.packets(1)
fedIn.payload.slot1Valid := raw.payload.slot1Valid
fedIn.payload.specs(0)   := MicroOpAssembler.computeOffload(raw.payload.packets(0))
fedIn.payload.specs(1)   := MicroOpAssembler.computeOffload(raw.payload.packets(1))
raw.ready := fedIn.ready

val fed = PipeStage(fedIn, pipeFlush)   // unchanged
```

### 2.1 What the new register must carry — verified, not assumed

`MicroOpAssembler.computeOffload`'s **entire** input is one
`DecodePacket` (`MicroOpAssembler.scala:463-472`):

```scala
def computeOffload(pkt: DecodePacket): Offload = {
  o.spec  := OperationDecoder.decode(pkt.words(0))
  o.srcEa := srcEaFor(pkt, o.spec.size)                       // :452-458, reads pkt.words only
  val dstEaField = pkt.words(0)(8 downto 6) ## pkt.words(0)(11 downto 9)
  val dstShift   = srcEaWordCount(pkt.words(0)(5 downto 3).asUInt,
                                  pkt.words(0)(2 downto 0).asUInt, o.spec.size, pkt.words)
  o.dstEa := EaDecoder.decode(dstEaField, o.spec.size, shiftedWordsFor(pkt.words, dstShift))
}
```

It reads **only `pkt.words`** — no other packet field, no aligner-internal
signal. So `Vec(DecodePacket(), 2)` + `slot1Valid` is **complete and
minimal**; nothing else needs to cross the new register.

**Explicitly checked, not assumed:** `df.slot1RawWords` / `df.slot1L0`
(fields Slice 3 added) **do not exist** — `grep -rn "slot1RawWords\|slot1L0" src/`
returns nothing; Slice 3 was fully reverted by `df13342`. There is no
extra plumbing to carry.

`DecodeStage`'s *whole* dependence on `DecodeFeedService` is
`DecodeStage.scala:79-90` (grep `df\.` — exactly 7 hits, all inside that
block). **The change is textually confined to those 15 lines** plus the
new `RawPacket` bundle next to `FedPacket` at `:35-49`.

---

## 3. Why the effect is guaranteed by construction, and how large it is

### 3.1 Half-1's post-cut slack is ALREADY MEASURED on this checkpoint

This is the strongest evidence available for any lever this round, and it
requires no estimate: **the payload the new register captures is already
being registered today, one stage later, into the existing `fed` stage.**
`fedIn.payload.packets` / `.slot1Valid` are pure renames of
`df.feed.payload` / `df.slot1Valid`, so `fed_payload_packets_*_reg` and
`fed_payload_slot1Valid_reg` are driven by the *identical* combinational
cone the new register will capture.

Measured directly (`c_half1_top.txt`, `c_half1_sv.txt`, `c_specs_top.txt`):

| endpoint group in the existing `fed` stage | FFs | worst slack | data path | levels |
|---|---:|---:|---:|---:|
| `fed_payload_packets_*_reg` (the 2 DecodePackets) | 480 | **-0.227ns** | 4.206 | 12 |
| `fed_payload_slot1Valid_reg` | 1 | **-0.943ns** | 4.923 | 14 |
| `fed_payload_specs_*_reg` (the offload — what this lever moves) | 427 | **-1.693ns** | 5.674 | 17 |

**Half-1's slack after the cut is therefore bounded, by direct
measurement on the routed netlist, at -0.943ns** — set by `slot1Valid`
(`c_slot1valid.rpt`: 14 levels, `L0 → preds(L0) → L1 → L0L1 compare →
slot1Ok → BTB/RAS slot-1 suppression`, `Aligner.scala:198-232` +
`FetchAlignPlugin.scala:429/468-471`), with the packets themselves a full
0.7ns slacker at -0.227ns.

### 3.2 Half-2 is the *slacker* half, not the binding one

Half-2 = new register → `computeOffload` → `fedIn.specs` register. From
§1.3, the logic+route from the cut point to the endpoint is **3.108ns**
(slot 0) / **3.272ns** (slot 1). Re-sourced from a flop:

```
arrival ≈ 0.030 (launch) + 0.078 (FF clk→Q) + ~0.20-0.40 (FF→first-LUT route)
          + 3.11…3.27 (traced tail)   =  3.42 … 3.78 ns      vs required 4.011ns
       ⇒  half-2 slack ≈ +0.23 … +0.59 ns
```

Pessimism allowance: the boundary LUT is *merged* today (§1.1 level 7 /
§1.2), so the cut splits it into two LUTs — one extra logic level lands at
the head of half-2. That is folded into the route allowance above. Even
under a deliberately harsh 15% route-degradation assumption
(3.11 → 3.57), half-2 lands at ≈ +0.03ns.

Independent sanity proxy (`c_half2_proxy.txt`): paths that already start
at a `fed_payload_packets_*_reg` flop and run through the *deeper*
`assemble` cone to `pushReg` measure -1.268ns at 16 levels / 5.250ns.
`computeOffload` is ~10-11 levels / ~3.1-3.3ns — materially shallower,
consistent with the estimate above. (Note this `fed.packets → pushReg`
family at -1.268ns is *untouched* by this lever; see §6.)

### 3.3 Bottom line for this family

| | now | after Lever C |
|---|---:|---:|
| `pred_lenWords → specs_*_dstEa_*` family worst | **-1.693ns** | **≈ -0.943ns** (half-1, measured) |
| family gain | — | **≈ +0.75ns** |

This family stops being the design's WNS holder. **It does not go to
zero** — the binding constraint transfers to the `slot1Valid` cone, which
stays entirely inside half-1 and is *not* fixed by this cut. Stating the
achievable outcome honestly rather than quoting the older report's
"+0.8 to +1.2 → family slack +0.8 to +1.2" is deliberate: that estimate
assumed the whole family collapsed to two balanced halves, and the
`slot1Valid` endpoint is a real, measured floor it did not account for.

### 3.4 What this means for the top-line number — read before gating

`FMax = 1/(4.000ns − WNS)`. Freshly measured on this checkpoint:

| family | worst slack | FMax if it held WNS |
|---|---:|---:|
| `pred_lenWords → specs_*_dstEa_*` (this lever) | -1.693 → **≈ -0.94** | 175.7 → 202.3 MHz |
| `ucPendPkt_words_0 → decodePc/fetchPc/headPtr` (Lever U1) | -1.624 → est. -1.0…-1.3 | 175.9 → ~188-200 MHz |
| `tagMem → ROB fault*Store` (LS/ROB Lever C) | -1.521 | 178.0 MHz |
| **`pred_lenWords → ibuf/headPtr`** (§4.1, no lever) | **-1.368** | **186.3 MHz** |
| **`headPtr → ibuf/headPtr`** (the true loop, §4.1, no lever) | **-1.289** | 189.1 MHz |
| **`fed.packets → pushReg` (`assemble` cone, §6)** | **-1.268** | 189.8 MHz |

**Consequence, stated plainly: even if all three round-3 levers land
perfectly, the frontend `headPtr`/`shift` family at -1.368ns caps this
design at ≈186 MHz.** Reaching the 200 MHz goal requires a *fourth*
lever against the `headPtr` arm (§4.1) — which this spec deliberately
does not attempt. Lever C is necessary but, on its own or even bundled
with U1 and LS/ROB-C, not sufficient. Plan the round accordingly.

---

## 4. The two named hazards, resolved

### 4.1 The `headPtr` feedback loop — ORTHOGONAL, proven cell by cell

The concern: `headPtr → L0 → io.shift → headPtr`
(`Aligner.scala:228-231`, `:271/:274` / `FetchAlignPlugin.scala:491`,
`:574-591`) is a *loop*, and a loop cannot be fixed by inserting a
register outside it.

**Traced on the current checkpoint** (`c_loop_hp2hp.rpt`, `c_len2hp.rpt`),
the loop's real composition is:

```
ibuf/headPtr_reg[0] → headPred(0).lenWords → L0 (fo=130-143)
  → BtbPlugin_logic_mem_reg_r2_.../ADDRC3          ← distributed-RAM BTB read, L0-indexed
  → slot1ValidOut_INST_0_i_31 → _i_56 → _i_31 → BtbPlugin_logic_predHit2Comb0
  → slot1ValidOut_INST_0_i_10 → ibuf/count[1]_i_6
  → ibuf_io_shift[0] (fo=16) → ibuf/headPtr[0]_rep_i_1 → headPtr_reg/D
```

| measurement | slack | levels |
|---|---:|---:|
| `headPtr → headPtr` (the true loop) | **-1.289ns** | 14 |
| `pred_lenWords → headPtr` (same cone, IBuf-entry source) | **-1.368ns** | 14 |
| worst path *into* `headPtr` overall | -1.607ns | 20 | (source `ucPendPkt_words_0` — that is **Lever U1's** family, not the loop) |

**Every cell on that path lives in `FetchAlignPlugin` /
`InstructionBuffer` / `BtbPlugin`. Not one cell is in `DecodeStage`.** The
new register sits at `DecodeStage.scala:78-92`, on the
`Aligner-output → computeOffload` arc, which the loop never enters.
**The loop is therefore untouched by this lever — neither fixed nor
worsened — and it needs its own separate fix if it ever becomes
binding. Per §3.4 it becomes binding immediately after this round.**

**The one real interaction, disclosed:** `feed.fire = feed.valid &&
feed.ready` gates `ibuf.io.shift`, and `feed.ready` becomes the *new*
stage's `slotFree` instead of `fed`'s. Chained `PipeStage`s chain their
ready combinationally:

```
today:  feed.ready = (!fedValid || fed.ready)
after:  feed.ready = (!rawValid || (!fedValid || fed.ready))     ← one extra 2-input OR
```

The traced loop path shows `ibuf_io_shift[0]` is driven from
`count[1]_i_6` / the `slot1ValidOut` cone — **`feed.ready` is not on the
critical arc** (it reaches `io.shift` via a much shallower branch). One
extra OR level on an off-critical branch is accepted. **This must be
re-checked, not assumed, in the implementation's post-route report**: if
`feed.ready` shows up on any top-100 path after the change, say so
explicitly rather than absorbing it.

**Named follow-up (out of scope here, but the round's real blocker):** the
loop's dominant cost is `L0` indexing the **BTB distributed-RAM read**
(`BtbPlugin_logic_mem_reg_r2_.../ADDRC3`, 0.395ns of route into a RAM
address pin) inside the `slot1Valid` suppression cone. That is the same
cone that also sets half-1's -0.943ns floor (§3.1). A lever that gets the
BTB slot-1 query off `L0`'s shadow would move **both** the headPtr loop
and this lever's half-1 bound. Recommend grounding it next.

### 4.2 The stash / µcode / MOVEM / MOVEP FSMs and `pipeFlush` — one extra squash target, already in scope

The concern: `DecodeStage`'s FSMs all hold `fed`
(`DecodeStage.scala:105-267`, `:848-883`, `:1846-1896`); an extra stage
means an extra in-flight group to squash on `pipeFlush`.

**Read the file's actual flush handling.** `pipeFlush` is declared once at
`:70-71` as a plain `Bool` with `allowOverride` (a sibling wiring plugin
drives it from `RedirectService.doFlush`), inside the *same* `Area` as
everything below it. It already reaches, by direct reference:

| flushed today | site |
|---|---|
| `queue.io.flush` | `:71` |
| `fed` (the `fedIn→fed` PipeStage) | `:92` |
| `pushReg` (the decode→ring skid) | `:1821` |
| `stashValid` | `:116`, and authoritatively `:2150-2155` |
| `movemActive`, `movemAnUpdPhase`, `movemSnapPhase`, `movemPendValid` | `:314`, `:320`, `:653`, `:2000`, `:2150-2155` |
| `ucActive`, `ucPendValid` | `:873`, `:880`, `:2082`, `:2150-2155` |
| `movepActive`, `movepPendValid` | `:747`, `:752`, `:2126`, `:2150-2155` |

**Concretely, "an extra in-flight group to squash" means exactly one new
thing: the group sitting in the new `raw` PipeStage.** It is squashed by
passing the *same* `pipeFlush` into `PipeStage(rawIn, pipeFlush)` —
**no new plumbing at all**, because `pipeFlush` is an ordinary in-scope
`Bool` in the same `Area` and `PipeStage.apply` already implements
`when(flush){ valid := False }` (`PipeStage.scala:18`) as the **last**
assignment to `valid`, so it wins over the same-cycle
`when(slotFree){ valid := in.valid }` capture on `:17`. That last-wins
ordering is the *identical* property the file's own `:2140-2155` comment
block was written to guarantee for the pending-marker registers, and it
is satisfied inside `PipeStage` by construction.

**Nothing else changes**, and this is the exhaustive argument, not a
sample:

1. **No FSM holds `raw`.** Every FSM hold (`movemHoldsFed`, `ucHoldsFed`,
   `movepHoldsFed`, `stashValid`, `:1871-1873`) is expressed as
   `fed.ready`, one stage downstream. `raw` is back-pressured only by
   `raw.ready := fedIn.ready`, i.e. ordinary stream backpressure. A held
   `fed` now simply also backs up `raw` and then `feed` — the new stage
   adds one slot of buffering and no new control.
2. **No pending marker is fed from `raw`.** `movemPendPkt`, `movepPendPkt`
   and `ucPendPkt` are all written from `fed.payload.packets(1)`
   (`:1880`, `:1885`, `:1890`) — downstream of the new register,
   untouched.
3. **A flush cannot leave a wrong-path group anywhere.** On the flush
   cycle: `raw.valid := False` and `fed.valid := False` and
   `pushReg.valid := False` and `queue` flushed and all four pending
   markers cleared — all in the same cycle, all from the same signal. The
   next cycle, `FetchAlignPlugin` has already applied its redirect
   (`:616-645`, `:635`, `:685`), so what `df.feed` presents is correct-path or nothing.
   The new stage adds a squash target, not a squash *ordering* problem.
4. **Nothing needs replay.** A flush discards; the frontend re-fetches from
   the redirect target. There is no state in `raw` that must survive.

**Three consequential-but-benign behaviour shifts to verify, not just
assert** (each is pure latency, listed here so the implementation
actually checks them):

- **Complex-packet resume handshake.** A complex packet reaches `fed` one
  cycle later, so the µcode engine's `resume` pulse back to
  `FetchAlignPlugin` (`DecodeStage.scala:1250`'s `ucComplexResume`, clearing `stalled` at
  `FetchAlignPlugin.scala:661-666`) arrives one cycle later. `stalled` is
  a latch cleared only by `redirect`/`resume` — no cycle counting exists
  between `feed.fire` and `resume` (verified by reading `:574-591` and
  `:660-666`). Effect: one extra stall cycle per complex/µcoded
  instruction. Must be confirmed by test, not by this paragraph.
- **Fault-packet emit-once.** `emittingFaultPacket`/`faultEmitted`
  (`FetchAlignPlugin.scala:524-552`) are keyed on `feed.fire`, which is
  now the *new* stage's accept. Emit-once is preserved (the latch is set
  on the same `feed.fire` that captures into `raw`), but this is exactly
  the kind of one-cycle-shift interaction `FetchFaultSpec` exists to
  catch — run it.
- **Speculative-state update window widens by one cycle.** The RAS
  push/pop, gshare GHR shift and `predictFire` fetch redirect all fire at
  `feed.fire` (`FetchAlignPlugin.scala:592-616`), i.e. one stage *earlier*
  relative to `fed` than before. `pipeFlush` does not roll the RAS/GHR
  back today either (the file's own "accept-corruption philosophy",
  `:429-439`), so this is **not** a new correctness issue — but it is a
  possible *prediction-accuracy* effect, and it is measurable. That is
  precisely what `IpcBenchSpec`'s predictor/`rts` kernels are for (§7).

### 4.3 Interaction with Lever U1 (in flight, same file)

Lever U1 is being implemented concurrently in `DecodeStage.scala`. Checked
against the live working tree (`git diff`, 33 insertions):

- **Zero textual overlap.** U1 touches `:878-905` (replacing
  `val ucPendSpec = OperationDecoder.decode(ucPendPkt.words(0))` with a
  `ucPendSpecReg`) and four write sites near `:1915 / :1979 / :2093 /
  :2138`. Lever C touches `:35-49` and `:78-92`. **They do not collide.**
- **U1's correctness proof survives Lever C unchanged.** U1 relies on
  `fed.payload.specs(1).spec === OperationDecoder.decode(fed.payload.packets(1).words(0))`
  holding bit-for-bit, i.e. on `specs` and `packets` being captured into
  `fed` in the **same** cycle from the **same** source packet. Lever C
  preserves that exactly: `packets` passes through `raw` and is
  re-registered into `fed`, while `specs` is computed *from that same
  `raw.packets`* and registered into `fed` in the same cycle. The pairing
  is invariant.
- **Nonetheless, whichever lever lands second MUST re-verify the exact
  line numbers and re-read `:78-92` against the merged file before
  editing** — this project's standing convention, and doubly warranted
  with two agents in one file.

---

## 5. Accepted cost

### 5.1 Latency: +1 frontend cycle, on every redirect and every µcode resume

The decode path is **latency-agnostic by design** — the whitebox lock-step
harness joins by `robId`, and the `MicroOpQueue` (depth 16) already
absorbs rate variation. `DecodeStage.scala:20-28`'s own header comment
records this for the *existing* `fedIn→fed` skid: *"The decode is
LATENCY-AGNOSTIC ... so the extra frontend cycle changes no architectural
result."* Lever C adds a second, identical-in-kind stage.

Where the cycle is actually paid:

| event | effect |
|---|---|
| steady-state throughput | **none** — full-rate `PipeStage` (`slotFree = !valid \|\| out.ready`), queue absorbs bursts |
| branch mispredict / exception redirect | **+1 cycle** on redirect-to-first-µop-in-queue |
| predicted-taken redirect (`predictFire`) | **+1 cycle** to refill |
| complex / µcoded instruction resume | **+1 cycle** per instruction (§4.2) |
| BTB/RAS/gshare accuracy | possible small change (§4.2), measurable |

**Precedents, both in this repo:** `2026-06-08-decode-ring-push-register`
added exactly this kind of +1 register (`pushReg`) and gated it on
`IpcBenchSpec` with the explicit rule *"aggregate ≈ 0.496 ... a material
aggregate drop means the queue is NOT acting as a skid — do NOT merge a
real IPC regression"*; FMax Slice 2 (`e086a71`) accepted *"one uniform
extra cycle of load-to-use latency on every D-cache load hit"* for the
same class of tradeoff. **Lever C inherits the `IpcBenchSpec`
accept/reject rule verbatim** (§7).

### 5.2 Area: ~500 flops, negligible

`DecodePacket` = 1+32+160+4+1+4+1+1+1+1+32+1+11 = **250 bits**; ×2 slots
+ `slot1Valid` + `valid` = **~502 FFs** (the existing `fed` packet
registers measure 480 FFs after constant-folding, so expect ~480-502).
Current utilization (`synth/fullcore_route_util.rpt`): **46,265 / 433,920
FFs (10.66%)** and 109,160 / 216,960 LUTs (50.31%). +502 FFs = **+0.12
percentage points**. The `packets` payload is registered twice (`raw`
then `fed`) — deliberately: `assemble` must see the *same* group as
`specs`, so the two must stay in lockstep in `fed`. LUT count should be
approximately neutral (one merged boundary LUT splits into two, per
slot/bit).

---

## 6. Non-goals

- **The `headPtr`/`io.shift` loop (§4.1).** Proven orthogonal to this
  lever's register. It is at -1.289 / -1.368ns today, becomes the design's
  binding family right after this round, and needs its own grounding +
  design pass (recommended target: the `L0`-indexed BTB slot-1 query).
  **Not this lever's job, and this lever must not be judged against it.**
- **Half-1's own -0.943ns `slot1Valid` floor.** Same cone as above; same
  future lever. This spec's success criterion is the *family* moving from
  -1.693 to ≈-0.94, not to zero.
- **The `fed.packets → pushReg` `assemble` cone at -1.268ns**
  (`c_half2_proxy.txt`) — a fourth, distinct family, entirely downstream
  of `fed`, untouched by this lever.
- **Lever U1** (`ucPendPkt` spec reuse) — separate, in-flight, disjoint
  code (§4.3).
- **LS/ROB Lever C** (flatten the ROB fault write-enable decode) —
  separate, parallel design effort.
- **Lever B** (bake `size` into `ChunkPredecode`) — explicitly rejected
  for this round: architecturally the same idea as the `moveLineSize`
  throwaway, whose unexplained `synth_design` non-convergence is an
  unresolved red flag over that whole family of fix.
- **Any change to `Aligner`, `FetchAlignPlugin`, `MicroOpAssembler`,
  `OperationDecoder` or `EaDecoder`.** This lever is a pure pipeline cut
  in `DecodeStage.scala`; if the implementation finds itself editing any
  of those files, it has left the spec.

---

## 7. Verification requirements (binding)

**This is a PURE LATENCY change. The proof obligation is that no computed
value differs — only when it arrives.** `computeOffload` is a pure
function of `pkt.words` (§2.1); it is applied to a *registered* copy of
the identical packet, one cycle later. Verification must establish that
claim, not merely fail to disprove it.

- `~/sbt/bin/sbt compile` clean.
- **No-behaviour-change proof.** State, in the report, the closure
  argument that (a) `computeOffload`'s only input is `pkt.words`
  (re-verify against live source, do not transcribe §2.1), and (b)
  `raw.payload.packets(i)` is bit-identical to `df.feed.payload(i)` one
  cycle later. Then back it with the differential run below. A prose
  argument alone is not sufficient — this round has twice shipped a spec
  whose central premise was false and was only caught by actually running
  something.
- **Frontend unit suites (unaffected by construction — prove it):**
  `AlignerSpec` (9/9) and `FetchAlignSpec` both drive
  `DecodeFeedProbePlugin`, i.e. they sit *upstream* of `DecodeStage` and
  must be **byte-identically unchanged**. Any movement here means the
  change leaked out of `DecodeStage.scala`. (`FetchAlignSpec` has a known
  4/5 with one independently-confirmed pre-existing failure — check the
  current baseline in `.superpowers/sdd/progress-fmax-levers-2026-08-08.md`,
  do not assume 5/5.)
- **Cycle-sensitive decode suites — expect settle-window bumps, and commit
  them separately** (exact precedent: `2026-06-08-decode-ring-push-register`
  Task 2 Step 4, *"bump decode-spec settle windows for the +1 push-register
  cycle (values unchanged)"*). Run all of:
  `DecodeStageSpec`, `DecodeCrackPipeSpec`, `MicrocodeSpec`,
  `MovemDecodeSpec`, `MovepDecodeSpec`, `FetchFaultSpec`,
  `UcPendSpecStashEquivalenceSpec` (Lever U1's new test — must still pass,
  §4.3). **A settle-window bump is acceptable; a changed expected VALUE is
  not** and must be treated as a bug in this lever until proven otherwise.
- **Targeted lock-step subsets first** (the flush/stash/resume paths this
  lever most stresses — same subset list the `pushReg` precedent used):
  `-z "loop"`, `-z "call"`, `-z "RMW"`, `-z "IRQ"`, `-z "bne"`, `-z "DBcc"`.
  A deadlock ("Simulation failed at time=…") here is a backpressure bug in
  the new stage, not a flake.
- **`ExecuteLockStepSpec` full suite: expect 390/394** (this session's
  standing baseline — the same 4 pre-existing failures, named).
- **Targeted ported tests before the full sweep**, weighted to this
  lever's risk surface: the complex/µcode-resume family (grep the corpus
  for `memind`, `bf_`, `movem`, `movep`, `bcd`, `cas`), plus the
  branch/redirect family. These exercise the `resume` handshake and the
  extra flush target directly.
- **Full ported corpus (~870 tests)** via
  `tools/fuzz/ported-sweep-parallel.sh` in isolated git worktrees
  (`git worktree add` is mandatory — `git checkout <sha>` in the shared
  tree caused a real collision before): **zero new regressions** against
  the current baseline fail list, diffed directly rather than compared by
  count.
- **`IpcBenchSpec` — the accept/reject gate for the latency cost.** Run
  `JAVA_OPTS=-Xmx10g ~/sbt/bin/sbt 'testOnly m68k040.bench.IpcBenchSpec'`
  and report **every kernel** before/after, not just the aggregate. Pay
  specific attention to the branch-predictor and `rts`/RAS kernels
  (§4.2's widened speculative-update window) and to any kernel with
  complex/µcoded instructions (§4.2's +1 resume cycle). Inherited rule:
  *a material aggregate drop means the queue is not acting as a skid —
  investigate; do not merge a real IPC regression.* Quantify what
  "material" turned out to be, with numbers.
- **Synthesis gates: OOC **and** post-route, both reported.** Standing
  caveat, learned twice this session: OOC and post-route disagree about
  which paths are critical in this design — an OOC number alone is not a
  verdict.
- **This lever MUST be gated COMBINED with Lever U1 and LS/ROB Lever C,
  never solo.** Per §3.4 and the ledger's own repeatedly-relearned lesson,
  three families are tied within 0.17ns; fixing one alone moves the
  top-line by ≈0 MHz. The success criterion for *this* task in isolation
  is **causal**: confirm on the post-change checkpoint that the
  `pred_lenWords → specs_*_dstEa_*` family's own worst endpoint improves
  from -1.693ns to approximately -0.94ns, and that
  `fed_payload_packets_*` / `slot1Valid` endpoints have not degraded.
  Report the **new** WNS holder explicitly by name — §3.4 predicts it will
  be the `headPtr` family at ≈-1.37ns, and confirming or refuting that
  prediction is a required deliverable.
- **Re-check `feed.ready`** (§4.1's disclosed interaction) in the
  post-route report: does any `feed_ready`/`slotFree`-derived net appear on
  a top-100 path? Say so either way.

---

## 8. Open questions for the plan-writing pass

1. **Confirm exact live line numbers** for `DecodeStage.scala:35-49` and
   `:78-92` **after Lever U1 lands** — U1 inserts ~33 lines around `:878`
   and four write sites, which shifts everything below `:878` but nothing
   above it. Re-read before editing (§4.3).
2. **Naming.** `RawPacket`/`rawIn`/`raw` are placeholders. Consider
   `AlignedPacket`/`aligned` to match the file's vocabulary. Purely
   cosmetic; pick one and use it consistently in the comment block, which
   must cite this spec by path (established convention — see the Lever A
   and Lever U1 comment blocks).
3. **Should the new stage's `RawPacket` reuse `FedPacket` minus `specs`,
   or be a distinct bundle?** A distinct bundle is recommended (it makes
   "this register carries no offload" structurally enforced rather than
   conventional), but the plan should decide explicitly.
4. **Whether to re-register `packets` into `fed` at all**, versus letting
   `assemble` read `raw.payload.packets` directly and having `fed` carry
   only `specs`. **The answer is almost certainly "re-register"** —
   reading `raw.packets` in `assemble` would misalign the packet with its
   own `specs` by one cycle (and would break Lever U1's identity, §4.3) —
   but the plan should record the reasoning rather than leave it implicit,
   because the ~500-flop cost makes it a question a reviewer will ask.
5. **Confirm no test reaches into `fedIn` by name.** `fed.*.simPublic()`
   calls at `:1833-1844` reference `fed`, not `fedIn`; grep the test tree
   for `fedIn` before assuming.
