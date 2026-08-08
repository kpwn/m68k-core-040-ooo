# Area: fold ROB per-entry `Reg` arrays into `Mem` (task #127) — design

**Date:** 2026-08-08
**Repo:** `/home/qwertyoruiop/m68k-core-040-ooo`, branch `feat/rob-predictor-mem`
**HEAD at authoring:** `1081d45` (`RobPlugin.scala` byte-identical to `4590ca4`; verified
with `git diff 4590ca4..HEAD -- src/main/scala/m68k040/rob/RobPlugin.scala` = empty)
**Status:** design spec. No RTL changed by this pass.

**This is an AREA spec, not an FMax spec.** Two prior grounding passes this session
looked at the same file and correctly rejected a ROB `Mem` fold *as an FMax lever*
(`scratchpad/fmax-faultaddrstore-ce-grounding-report.md` §3.2;
`scratchpad/fmax-robplugin-dominant-grounding-report.md`). Neither measured area.
This pass does, and reaches a **narrower but different** conclusion: exactly **two**
of RobPlugin's twenty-one per-entry arrays are worth touching, and **neither of them
is one of the arrays those reports argued about**.

---

## 0. Measurement provenance (read before trusting any number)

| item | value |
|---|---|
| netlist analysed | `generated/M68kFullCoreSynth.v`, mtime `2026-08-08 16:57`, 22.8 MB |
| netlist matches live source | yes — contains the 192 `lsFaultSel_`/`sqFaultSel_` nets added by LS/ROB Lever C, which are in the live `RobPlugin.scala` |
| device / totals reference | `synth/M68kFullCoreSynth_util.rpt`, mtime `2026-08-08 15:19`, xcku5p-ffvb676-2-e |
| design totals (post-synth OOC) | **107 357 CLB LUTs** (99 753 logic + 7 604 memory, of which 7 472 distributed RAM), **46 604 FFs**, 5 517 F7 muxes, 2 011 F8 muxes |

**Flip-flop counts in this spec are EXACT and MEASURED** — they are literal counts of
`reg` declarations in the generated netlist (`grep -cE '^\s+reg\s+(\[[0-9]+:[0-9]+\]\s+)?RobPlugin_logic_<name>_[0-9]+\s*;'`),
not estimates. **Write-port and read-port counts are EXACT and MEASURED** — literal
counts of `<name>_5 <=` assignment sites and of distinct `_zz_` mux targets in the
netlist, not source-level inference.

**LUT counts in this spec are ESTIMATES** and are labelled as such everywhere. No
Vivado process was launched by this pass: the machine was carrying 5 concurrent
Vivado invocations throughout, with available RAM falling from 10 GB to 2 GB, and a
prior session already lost a run to an OOM segfault under exactly this contention.
Opening `synth/fullcore_routed.dcp` read-only would need ~8–12 GB. **§7 defines the
measurement that must be run on a clear machine before this spec's LUT numbers are
treated as anything but a model.** The FF numbers stand on their own regardless.

---

## 1. Complete per-entry array inventory (all 21, measured)

`depth = 64`, `robIdW = 6`. Every row's FF count is measured from the netlist;
every writer and reader count is measured from the netlist.

| # | array | bits | **FFs (measured)** | write ports (measured) | writers | async read ports (measured) |
|---|---|---:|---:|---:|---|---:|
| 1 | `payload` | 122 | **0** | 2 | alloc0(`tail`), alloc1(`tail+1`) | 2 (`h0`,`h1`) — *already a `Mem`* |
| 2 | `branchTrainMem` | 78 | **0** | 1 | `branchCompletion` | 1 **sync** — *already a `Mem`* |
| 3 | **`nextPcStore`** | 32 | **2 048** | **1** | **`branchCompletion` only** | **2** (`h0`, `h1`) |
| 4 | **`faultPcStore`** | 32 | **2 048** | **2** | **alloc0, alloc1 only** | **1** (`h0`) |
| 5 | `sysValStore` | 32 | 2 048 | 4 | `ccrCompletion(0..3)` | 1 (`h0`) |
| 6 | `faultAddrStore` | 32 | 2 048 | 5 | ls, sq, eu, alloc0, alloc1 | 1 (`h0`) |
| 7 | `faultVecStore` | 8 | 512 | 5 | ls, sq, eu, alloc0, alloc1 | 1 (`h0`) |
| 8 | `nzvcValStore` | 4 | 256 | 4 | `ccrCompletion(0..3)` | 2 (`h0`,`h1`) |
| 9 | `faultSizeStore` | 2 | 128 | 2 | ls, sq | 1 (`h0`) |
| 10 | `completes` | 1 | 64 | 8 | completion(0..4), branchCompletion, alloc0, alloc1 | 2 |
| 11 | `mispredictStore` | 1 | 64 | 3 | branchCompletion, alloc0, alloc1 | 1 |
| 12 | `branchTakenStore` | 1 | 64 | 3 | branchCompletion, alloc0, alloc1 | 1 |
| 13 | `btbIsBranchStore` | 1 | 64 | 3 | branchCompletion, alloc0, alloc1 | 1 |
| 14 | `phtValidStore` | 1 | 64 | 3 | branchCompletion, alloc0, alloc1 | 1 |
| 15 | `faultedStore` | 1 | 64 | 5 | ls, sq, eu, alloc0, alloc1 | 2 |
| 16 | `sysValRdyStore` | 1 | 64 | 6 | ccrCompletion(0..3), alloc0, alloc1 | 1 |
| 17 | `faultWrStore` | 1 | 64 | 4 | ls, sq, alloc0, alloc1 | 1 |
| 18 | `faultSupStore` | 1 | 64 | 4 | ls, sq, alloc0, alloc1 | 1 |
| 19 | `faultAtcStore` | 1 | 64 | 4 | ls, sq, alloc0, alloc1 | 1 |
| 20 | `faultInstrStore` | 1 | 64 | 5 | ls, sq, eu, alloc0, alloc1 | 1 |
| 21 | `nzvcWrStore` | 1 | 64 | 6 | ccrCompletion(0..3), alloc0, alloc1 | 2 |
| 22 | `xValStore` | 1 | 64 | 4 | ccrCompletion(0..3) | 2 |
| 23 | `xWrStore` | 1 | 64 | 6 | ccrCompletion(0..3), alloc0, alloc1 | 2 |
| — | `pcStore` | 32 | **0** | — | sim-only (`GenerationFlags.simulation`) | — |

**Total per-entry flip-flops: 9 984 = 21.4 % of the entire design's 46 604 FFs.**

`pcStore` measured 0 declarations in the synthesis netlist, confirming its
`GenerationFlags.simulation` guard works as documented. Non-per-entry state
(`head`, `tail`, `count`, `stopped`, `stoppedPc`, `coreHalted`, `doFlushReg`,
`flushPcReg`, `committedCcr`, `heldCcrFold`, `tracePending*`, `nmiPending`,
`allocReadySig`, `h0PreciseCompletedSticky`) is scalar and out of scope.

### 1.1 How the read side is actually built (measured)

SpinalHDL does **not** emit one mux per array. It emits **one 64-way `case`
statement per read address**, whose branches assign every array read at that
address simultaneously (netlist lines ~64 570–64 730). So the ROB has exactly two
read-mux structures, and their cost scales with **total bits read at that address**:

- **`h0` case:** 156 bits wide (`completes` 1 + `faulted` 1 + `sysValRdy` 1 +
  **`nextPc` 32** + `mispredict` 1 + `btbIsBranch` 1 + `phtValid` 1 +
  **`faultPc` 32** + `faultVec` 8 + `faultAddr` 32 + `faultWr` 1 + `faultSize` 2 +
  `faultSup` 1 + `faultInstr` 1 + `faultAtc` 1 + `nzvcWr` 1 + `nzvcVal` 4 +
  `xWr` 1 + `xVal` 1 + `branchTaken` 1 + `sysVal` 32)
- **`h1` case:** 41 bits wide (`completes` 1 + `faulted` 1 + **`nextPc` 32** +
  `nzvcWr` 1 + `nzvcVal` 4 + `xWr` 1 + `xVal` 1)

**197 bit-ports total.** A 64:1 mux per bit on UltraScale+ costs ~17 LUT6 +
8 MUXF7 + 4 MUXF8 (16 LUT6-as-4:1 → 4× 16:1 via F7/F8 → one final 4:1). Estimated
ROB read-mux cost: **197 × ~17 ≈ 3 350 LUTs** (range 2 170–4 140 at 11–21 LUT6/bit).

This decomposition is why the two arrays this spec targets are the right ones:
`nextPcStore` and `faultPcStore` are **96 of those 197 bit-ports — 48.7 % of the
entire ROB read-mux cost — while being the only two arrays in the file that are
safely removable.**

---

## 2. The hard blocker that rules out the other nineteen arrays

This is the finding that neither prior report identified, and it is **mechanical,
not judgemental**.

This project lowers multi-write async-read `Mem`s through its own vendored
`m68k040.hw.MultiPortWritesSymplifier` (`src/main/scala/m68k040/hw/MultiportRam.scala`,
installed unconditionally at `M68kSpinalConfig.scala:13`). Both of its lowerings
carry an explicit precondition, stated in the source:

> `RamAsyncMwXorCore` (`MultiportRam.scala:105-107`):
> *"ONE SELF-CONSISTENT XOR core: N-write / M-read async register file via the XOR
> trick (one 1W RAM per write port). **Precondition: no two write ports target the
> same address in the same cycle.**"*

`RamAsyncMwMux` (used for width ≥ 10) is not exempt: its live-value table is itself
a `RamAsyncMwXor` (`MultiportRam.scala:203-206`), so it inherits the precondition
verbatim. Violating it does not produce a stall or a mis-ordered write — it XORs two
location-table writes together and returns a **garbage bank index**, i.e. silent data
corruption. The file already records what that failure mode looks like in practice:
a related soundness slip *"turned `ExecuteLockStepSpec` into 27/394, diverging at the
very first commit with a random-looking A7"* (`MultiportRam.scala:117-119`).

**RobPlugin deliberately and documentedly violates that precondition on nineteen of
its twenty-one arrays.** The whole alloc-priority discipline exists precisely because
a completion write and an alloc write **can and do hit the same `robId` in the same
cycle**:

> `RobPlugin.scala:699-703`: *"Completion mark (**alloc-reset has priority on a
> reused index**) — MUST come BEFORE the alloc-reset writes below so that **on a
> re-allocated index a stale wrong-path completion (set here) is OVERRIDDEN by the
> alloc's `completes:=False`** (later `when` wins in SpinalHDL)."*

and the same ordering is applied verbatim to the fault family
(`RobPlugin.scala:805`, `:826`: *"Placed BEFORE the alloc-reset (alloc wins on a
re-used index)"*). Same-address same-cycle collision is therefore a **designed-for,
load-bearing case**, not a hypothetical. Any `Mem` fold of `completes`,
`faultedStore`, `faultAddrStore`, `faultVecStore`, `faultInstrStore`,
`faultWrStore`, `faultSupStore`, `faultAtcStore`, `mispredictStore`,
`branchTakenStore`, `btbIsBranchStore`, `phtValidStore`, `sysValRdyStore`,
`nzvcWrStore` or `xWrStore` would silently corrupt state.

The `ccrCompletion`-only arrays (`sysValStore`, `nzvcValStore`, `xValStore`) have no
alloc writer, so their only collision mode is *two `ccrCompletion` ports carrying the
same `robId` in the same cycle*. That requires two in-flight µops sharing a `robId` —
which is exactly the wrong-path-survivor scenario the alloc-priority comments prove
the design already treats as reachable. It is **not provably impossible**, and this
spec will not fold a 2 048-FF array on an unproven "probably can't happen".

**Consequence: the only foldable arrays in `RobPlugin` are the ones with a single
writer, or the ones removable outright.** There is exactly one of each — rows 3 and
4 of the table. `payload` (row 1) is sound only because `tail` and `tail+1` can never
be equal at depth 64; `branchTrainMem` (row 2) is sound because it has one writer.

This also **retires the first grounding report's blocker for `faultAddrStore` and
replaces it with a stronger one**: that report rejected the fold because a multi-write
arbiter "would need a retry/stall path the ROB doesn't have". The real blocker is that
the lowering the project actually uses has no arbiter at all and is *unsound* on
collision — no retry path would help, because nothing detects the collision.

---

## 3. Relationship to the 2026-08-06 "LUT reduction B1" initiative

`docs/superpowers/specs/2026-08-06-lut-reduction-microcode-rob-bram-design.md`
Feature B established the taxonomy this spec builds on, and folded its
**"Category 1 — alloc-only (2 write ports max: `tail`, `tail+1`)"** set into
`payload`: nine fields (`isRteStore`, `sysOpStore`, `sysReadDirStore`,
`sysDstArchStore`, `sysRcStore`, `needsSupStore`, `firstStore`, `pcStore`,
`sysKindStore`).

**That enumeration was incomplete. `faultPcStore` is a Category-1 field and it was
missed.** Its only two write sites are `alloc0`/`alloc1`
(`RobPlugin.scala:848`, `:873`) — netlist-confirmed, three assignment sites for
`faultPcStore_5`: the reset and those two. The file even says so itself
(`RobPlugin.scala:836`): *"`faultPcStore` is already the µop's own pc/nextPc
(captured at alloc) — no write."* It is the single largest Category-1 field in the
file (32 bits vs. `pcStore`'s 32 and everything else's ≤ 12), and it survived the B1
pass untouched. §4 folds it — and folds it **better than B1 would have**, because it
turns out not to need folding at all (it needs deleting).

`nextPcStore` is **not** Category 2 as that spec's prose might suggest. Category 2 was
*"completion-source fields **with an additional alloc-time reset write**"*.
`nextPcStore` has **no alloc write** (netlist: one assignment site, no reset) — it is
structurally **identical to `branchTrainMem`**: same single writer
(`branchCompletion`), same write address, no alloc reset, and made safe by the same
alloc-reset `Reg`-`Vec` gates (`mispredictStore` / `payload.retireAlone`). Task #129
already executed exactly this fold for exactly this writer. §5 is that same fold
applied to the one array task #129 left behind, and the reason it left it behind is
concrete and correctable: `branchTrainMem` is `readSync`, and `nextPcStore` needs
**async** reads at two addresses.

---

## 4. Slice A — delete `faultPcStore` outright (it is redundant)

### 4.1 The observation

`faultPcStore` does not hold information. At alloc (`RobPlugin.scala:848`):

```scala
faultPcStore(tail) := Mux(allocUopVec(0).faultUsesNextPc, allocUopVec(0).nextPc, allocUopVec(0).pc)
```

and in the very same `when(alloc0)` block, `payload.write(tail, payloadFrom(...))`
already stores **both** operands of that mux (`RobPlugin.scala:407`, `:422`):

```scala
p.predNextPc := u.nextPc     // == allocUopVec(0).nextPc
p.pc         := u.pc         // == allocUopVec(0).pc
```

So `faultPcStore(i) ≡ Mux(payload(i).faultUsesNextPc, payload(i).predNextPc, payload(i).pc)`
for every `i`, at every time, provided the 1-bit selector rides along.
**2 048 flip-flops are storing a mux of two values that are already stored.**

`faultUsesNextPc` is available at alloc: `RenamedUop.scala:46`.

### 4.2 The change

In `RobPayload` (`RobPlugin.scala:47-83`), add one bit:

```scala
val faultUsesNextPc = Bool()
```

In `payloadFrom` (`RobPlugin.scala:406-431`), alongside the other B1 alloc-only fields:

```scala
p.faultUsesNextPc := u.faultUsesNextPc
```

Delete the declaration (`:261`) and both write sites (`:848`, `:873`). At the single
read site (`:958`), fold the derivation into the existing `privVec8` select:

```scala
val exceptionPc = UInt(32 bits)
exceptionPc := Mux(privVec8, p0.pc, Mux(p0.faultUsesNextPc, p0.predNextPc, p0.pc))
```

`payload` row width: **122 → 123 bits**.

### 4.3 Why this is bit-identical

1. **Same sources.** Both operands and the selector come from the same
   `allocUopVec(k)` fields, written in the same `when(alloc0)`/`when(alloc1)` block,
   in the same cycle, at the same address.
2. **Same write-port shape.** `faultUsesNextPc` joins `payload`, which already has
   exactly these two writers at these two never-equal addresses. §2's precondition is
   satisfied by construction, unchanged.
3. **Same gating.** `exceptionPc` is consumed only via `excEntryPc` under
   `excEntryTrigger`, whose `exceptionPending` term requires `faultRetire` ⟹
   `headReady` ⟹ `count > 0` ⟹ entry `h0` was written by an alloc. This is the
   **identical** discipline the B1 `SAFETY` note (`RobPlugin.scala:61-74`) already
   relies on for `payload.pc`, `payload.isRte`, `payload.needsSup` and the rest — not
   a new hazard, and the reason losing `RegInit(U(0,32))` costs nothing.
4. **No other writer.** Netlist-confirmed: `faultPcStore_5` has exactly three
   assignment sites (reset + the two allocs). `euFaultCompletion` explicitly does not
   write it (`RobPlugin.scala:836`).

### 4.4 Savings

| | |
|---|---|
| flip-flops removed | **2 048** (measured, exact) |
| `h0` read-mux narrowed | 156 → 124 bits ⟹ **≈ −544 LUT** (est., 32 × 17) |
| per-entry 2:1 `D` mux removed | 64 × 32 bits between the two shared alloc buses `_zz_..faultPcStore_0`/`_1` ⟹ **≈ −1 024 LUT** (est., 2 bits/LUT6 via O5/O6) to **−2 048** (est., 1 bit/LUT6) |
| CE decode removed | 64 entries (est. small, shared one-hot) |
| **cost:** `payload` 122→123 bits | 2 data banks × 2 read ports × 1 bit ≈ **+5 LUT as Memory** (est.) |
| **cost:** widened read select | 2:1 folded into an existing 2:1 ⟹ 3-input select, **≈ +32 LUT** (est.) or free if absorbed |
| **net** | **−2 048 FF, ≈ −1 530 to −2 560 LUT** |

---

## 5. Slice B — fold `nextPcStore` into a single-write async-read `Mem`

### 5.1 Why it is sound (and why it is the *only* other one that is)

Netlist-measured: **one** write site for `nextPcStore_5`
(`RobPlugin.scala:710`, `branchCompletion` only), **no** reset, **no** alloc write.
With `typo.writes.size <= 1`, `MultiPortWritesSymplifier.doBlackboxing` returns
immediately (`MultiportRam.scala:232`) — the fold **never touches the XOR/LVT path**,
so §2's precondition is not merely satisfied, it is **not applicable**. SpinalHDL
emits a plain inferred RAM, exactly as it already does for `branchTrainMem`
(netlist: `reg [77:0] RobPlugin_logic_branchTrainMem [0:63];`).

Staleness safety is unchanged because it never depended on the storage type. Today
`nextPcStore` is `Vec.fill(depth)(Reg(UInt(32 bits)))` — **uninitialised, no
`RegInit`, no alloc reset**. A never-written row already reads undefined content. It
is made safe by the alloc-reset `Reg`-`Vec` gates, precisely as `branchTrainMem`'s own
doc comment describes for itself (`RobPlugin.scala:226-235`):

- `commitPc0/1` read it only under `p0/p1.retireAlone` (alloc-written, `payload`);
- `flushPcReg` reads it only under `branchRedirect = retire0 && p0.retireAlone &&
  mispredictStore(h0)`, and `mispredictStore` **is** alloc-reset to `False`.

Moving from "uninitialised `Reg`" to "uninitialised `Mem` word" is a like-for-like
substitution of one undefined-content store for another.

### 5.2 Read-during-write is a non-event

`Mem.readAsync` returns the **old** word during a same-cycle write — identical to a
`Reg` array's `Q`. Beyond that, the situation is architecturally unreachable:
`branchCompletion` sets `completes(robId)` and `mispredictStore(robId)` in the same
cycle it writes `nextPcStore(robId)`, and both are `Reg`s, so both still read their
**pre-write** values that cycle. `retire0` requires `completes(h0)`; `branchRedirect`
additionally requires `mispredictStore(h0)`. Neither can be true on the write cycle.
The earliest consumption is the following cycle.

### 5.3 The change

Replace the declaration (`RobPlugin.scala:204`):

```scala
val nextPcMem = Mem(UInt(32 bits), depth)
nextPcMem.addAttribute("ram_style", "distributed")
```

Write, inside the existing `when(branchCompletion.valid)` block (replacing `:710`):

```scala
nextPcMem.write(branchCompletion.payload.robId, branchCompletion.payload.nextPc)
```

Read — **hoist to exactly two `readAsync` calls**, since each call allocates a
physical read port:

```scala
val nextPc0 = nextPcMem.readAsync(h0)   // serves BOTH commitPc0 and flushPcReg
val nextPc1 = nextPcMem.readAsync(h1)
val commitPc0 = Mux(p0.retireAlone, nextPc0, p0.predNextPc)
val commitPc1 = Mux(p1.retireAlone, nextPc1, p1.predNextPc)
...
when(branchRedirect) { flushPcReg := nextPc0 }   // was nextPcStore(h0)
```

The `h0`/`h1` split matches what the netlist already does (two `_zz_` targets:
`_zz__zz_RobPlugin_logic_flushPcReg` at `h0`, `_zz_RobPlugin_logic_commitPc1` at
`h1`); `commitPc0` already shares the `h0` mux. **Two read ports, far under the
`MultiportRam.maxReadPortsPerMem = 12` guard and further still under the measured
15-port Vivado inference cliff** (`MultiportRam.scala:29-55`).

The `ram_style` attribute follows `DcachePlugin.scala:90`'s precedent but selects
`distributed`, not `block`: BRAM cannot serve an asynchronous read at all, and at
64 × 32 a BRAM would be ~3 % utilised.

### 5.4 Savings

| | |
|---|---|
| flip-flops removed | **2 048** (measured, exact) |
| `h0` read-mux narrowed | −32 bits |
| `h1` read-mux narrowed | 41 → 9 bits (−32 bits) — a **78 % width reduction** on that structure |
| read-mux LUTs | **≈ −1 088 LUT** (est., 64 bit-ports × 17) |
| per-entry `D` mux | **none today** — single writer drives FF `D` pins directly, so nothing is saved here (and nothing was lost) |
| **cost:** LUTRAM | 2 read ports × 32 bits. The project's own measured sweep (`MultiportRam.scala:37`) gives 320 LUT for 8 read ports at width 32 ⟹ **40 LUT/read-port** ⟹ **≈ +80 LUT as Memory** |
| **net** | **−2 048 FF, ≈ −1 010 LUT** (range −620 to −1 280) |

### 5.5 Rejected alternative: merge into `branchTrainMem`

Same writer, same address, so a merge is tempting. **Do not.** `branchTrainMem` is
`readSync` by deliberate design (task #129: the sync read *is* the pipeline register
that `btbUpdateFlow`/`gshareUpdateFlow` need, replacing an explicit `RegNext`).
Adding an async read port would force the whole 78-bit array out of any block-RAM
mapping and perturb a validated BTB/gshare timing path, to save at most a handful of
address-decode LUTs. Keep them separate.

---

## 6. Combined impact, and the honest FMax position

| | measured | estimated |
|---|---:|---:|
| flip-flops removed | **4 096** | — |
| … as a share of the design's 46 604 FFs | **8.8 %** | — |
| … as a share of RobPlugin's 9 984 per-entry FFs | **41.0 %** | — |
| LUTs removed | — | **≈ 2 540 (range 1 630 – 3 840)** |
| … as a share of the design's 107 357 LUTs | — | **≈ 2.4 % (1.5 – 3.6 %)** |
| LUTs added | — | ≈ +117 (≈ 85 as Memory, ≈ 32 as logic) |

### FMax: expected neutral-to-positive, and explicitly not the justification

- **Slice B is a strict shortening of its own read path.** It replaces a 64:1 mux
  tree (~17 LUT6 deep, ~3 logic levels + F7/F8) with a LUTRAM async read (~1 LUT
  delay). `commitPc0` (→ `CommitTrace`, `tracePendingPc`) and `flushPcReg` can only
  improve.
- **Slice A removes 2 048 FFs' worth of CE/D fan-in from the alloc cone**, which is
  where `allocUopVec_0_pc`/`_nextPc` land. It adds one mux level to `exceptionPc` —
  the rare, serializing exception-entry path, which appears in no timing family
  either grounding report identified.
- **A claim I initially made and then disproved, recorded so nobody re-derives it:**
  I expected `branchCompletion.payload.nextPc` to be a fanout-2048 net worth
  collapsing. It is not. Fanout is per **bit**: each of the 32 bits drives 64 FF `D`
  pins, i.e. fanout 64, and it correctly does **not** appear in
  `synth/fullcore_fanout.rpt`'s high-fanout list (which does list
  `RobPlugin_logic_payload/location/...` at 5 294 and
  `...faultSupStore_0_i_7_n_0` at 2 083). **Slice B is an area lever, not a fanout
  lever.**
- Both slices leave the arrays that *do* own the ROB's violating endpoints —
  the `fault*Store` `/CE` family of
  `docs/superpowers/specs/2026-08-08-fmax-lsrob-leverc-writeenable-flatten-design.md`
  — **completely untouched**. There is no interaction with LS/ROB Lever C, and no
  conflict if both land.

The second grounding report's empirical point stands and is worth restating: the
already-folded `payload` `Mem` is the most widely-smeared placement structure in the
file (84 CLB columns) yet contributes **zero** timing-violated endpoints. Adding
~85 LUTs of distributed RAM (Slice B) to a design already carrying 7 472 is not a
material change to that picture. But this spec's case rests on **8.8 % of the
design's flip-flops**, not on any FMax claim.

---

## 7. Required validation

**Functional (mandatory, both slices):**
1. `ExecuteLockStepSpec` full suite, **×2 with different seeds** — both slices move
   state between undefined-content storage kinds (uninitialised `Reg` → `Mem` word,
   and `RegInit(0)` → gated `payload` field). The project's own history
   (`MultiportRam.scala:117-119`; `[[spinalhdl-sim-poke-gotchas]]`'s
   "uninit Regs randomize per-seed") shows this is exactly the class of change that
   produces seed-flaky lock-step, so a single green run is not evidence.
2. `RobPluginSpec`, `RobFaultSpec` and the exception-family directed specs — Slice A
   changes how `exceptionPc` is produced for every fault vector.
3. Branch/predictor directed specs and the trace-exception specs
   (`tracePendingPc` is fed from `commitPc0`) — Slice B changes how `commitPc0/1` and
   `flushPcReg` are produced.
4. `fastTest`.

**Area measurement (mandatory before this spec's LUT numbers are quoted as fact):**
On a machine with **≥ 10 GB free RAM and no competing Vivado**, run read-only against
a checkpoint — no synthesis needed for the "before" side:

```tcl
open_checkpoint synth/fullcore_routed.dcp
foreach a {nextPcStore faultPcStore faultAddrStore sysValStore faultVecStore nzvcValStore} {
  set ff  [get_cells -quiet -hier -filter "NAME =~ *RobPlugin_logic_${a}_* && REF_NAME =~ FD*"]
  set lut [get_cells -quiet -hier -filter "NAME =~ *RobPlugin_logic_${a}_* && REF_NAME =~ LUT*"]
  puts "$a FF=[llength $ff] LUT=[llength $lut]"
}
report_utilization -hierarchical -file rob_hier_util.rpt
```

This converts §1's estimated read-mux/D-mux model into measurement and gives the
"before" baseline for the post-implementation delta. The FF side needs no such run —
it is already exact.

**Synth gate (mandatory, standing project rule `[[synth-gate-every-slice]]`):**
full-core OOC synth, FMax reported. Given the session's standing finding that FMax is
unreliable under machine contention, the gate must run uncontended.

---

## 8. Explicitly out of scope

- **Every array in §1 except rows 3 and 4.** §2 gives the mechanical reason: the
  vendored multi-write lowering is *unsound* — not merely inefficient — under the
  same-address same-cycle write collisions this ROB is deliberately built to
  tolerate. `faultAddrStore` (2 048 FFs) and `sysValStore` (2 048 FFs) are the two
  large, tempting ones; both are blocked, `faultAddrStore` by a documented
  five-writer alloc-priority discipline, `sysValStore` by an unprovable claim about
  wrong-path completion uniqueness.
- **Re-opening `faultAddrStore`.** Should a future initiative want those 2 048 FFs, the
  prerequisite is not a better `Mem` — it is either a collision-tolerant lowering
  (priority-encoded LVT write) or a proof that wrong-path completions can never
  coexist with a re-alloc of the same `robId`. Both are their own design pass.
- **BRAM.** Nothing here belongs in block RAM. Every candidate needs asynchronous
  reads, which BRAM cannot serve, and at depth 64 a BRAM would sit ~3 % utilised.
- **Any FMax claim as justification.** §6 states the expectation; the case is area.

---

## 9. Sequencing

Slices A and B touch disjoint arrays and can land independently, in either order.
Recommended: **A, then B** — A is a pure deletion plus one bit, provably
bit-identical, and makes the smaller diff; landing it first means any lock-step
surprise in B is unambiguously attributable to B. Each slice takes its own synth gate
per `[[synth-gate-every-slice]]`.

Estimated size: Slice A ≈ 15 lines changed in 1 file; Slice B ≈ 12 lines changed in
1 file. Both are confined to `src/main/scala/m68k040/rob/RobPlugin.scala`; no
cross-file **code** references exist. Verified: `grep -rn 'nextPcStore' src/` returns
only `RobPlugin.scala`; `grep -rn 'faultPcStore' src/` additionally returns
`BranchEuPlugin.scala:38,315` and `ExceptionUnit.scala:718-719`, all four of which are
**doc comments**, not code — they must be reworded by Slice A, and
`ExceptionUnit.scala:718-719` independently corroborates §4.1's derivation
(*"faultUsesNextPc=False, so faultPcStore already holds the instruction's own pc at
alloc … entryPc IS that pc"*). Neither array is `simPublic`, so no test or whitebox
harness can be observing them.
