# NaxRiscv ↔ m68k-core-040-ooo — broad architectural comparison

**Date:** 2026-09-04
**Subject:** `/home/qwertyoruiop/m68k-core-040-ooo`, branch `fmax-closure-fanout`, HEAD `9469b4f`
**Reference:** `/home/qwertyoruiop/m68k-ooo-v2/thirdparty/NaxRiscv`, HEAD `9f452d5` (2026-01-23). **Read-only; not modified.**
**Status:** RESEARCH ONLY. No RTL, no test code and no third-party file was changed by this
document's session. It ends in a ranked recommendation list; the go/no-go is the project
owner's.

**Scope note:** MMU table walks routed through the D-cache are covered by a separate,
concurrent targeted study (`2026-09-04-naxriscv-walker-comparison-round2.md`) and are out of
scope here except for one cross-reference in §7.

---

## 0. TL;DR — the five ranked items

Full reasoning in §8. **Rank 0**, ahead of all of these on severity but owned by the concurrent
walker study, is the MMU descriptor-coherency gap — a *silent data-loss* defect, not a
performance nit (§7.5, §8). Of the items this document does own, in one line each:

1. **Adopt NaxRiscv's two-tier reschedule** — early PC redirect + rename halt, state rollback
   still at retire. It attacks the *refetch-latency* term that our own A/B doc says no
   checkpoint scheme can reach, and it structurally cannot produce the 69 hangs our reverted
   early-flush did.
2. **Invert the lock-step comparison loop** so the *oracle* drives it, and compare the full
   architectural register file per retire. Four of the seven verification defects found on
   2026-09-03 are impossible under that inversion.
3. **Declare instruction-family sets once as masked literals and materialise each consumer
   with `Symplify`** — the same mechanism reopens the closed `bad` expression and removes the
   eight microcode gate clauses still triplicated after `d6a0275`.
4. **Port `NaxAllocatorChecker`** — a ~40-line sim-time freelist double-alloc/double-free
   assertion. Tasks #176 / #194 / #200 were all this bug class.
5. **Add a `PerformanceCounterService`** — generic event-port registration, counters readable
   over the existing `dbg_axi` slave.

And four things to **not** do, with reasons, in §6.

---

## 1. Method, and the honest size of the two trees

| | NaxRiscv | this core |
|---|---|---|
| `src/main/scala` | ~90 files (excl. `sandbox/`) | 108 files, **50 405 LOC** |
| Decode | `frontend/DecoderPlugin.scala` — **415 lines** | `decode/{MicroOpAssembler,DecodeStage,OperationDecoder}.scala` — **8 462 lines** |
| ROB | `misc/RobPlugin.scala` — **205 lines** | `rob/RobPlugin.scala` — **2 730 lines** |
| Plugin framework | hand-rolled `utilities/Framework.scala` | upstream `spinal.lib.misc.plugin.{FiberPlugin,PluginHost}` + `Database` |

The 20× decode ratio is **not** a code-quality signal. NaxRiscv decodes one fixed 32-bit word
(or 16/32 with C) into a handful of `Stageable`s; we decode a 2–22 byte variable-length
instruction with full-format extension words, memory-indirect EAs, NZVC/X side effects and
macro cracking. Most of that ratio is irreducible. §7 marks exactly where the comparison stops
being meaningful.

What *is* transferable is **structure**: how NaxRiscv factors a decode table, where it puts a
rollback, and which comparison loop drives its lock-step. Those are the findings below.

---

## 2. Problem 1 — the hot global `bad` expression

### 2.1 What we have

`MicroOpAssembler.scala:2028-2034` — one `val`, one combinational expression, **38 top-level
terms**:

* **25 ANDed exclusion terms** (`!isRteOp && !isTrapOp && !isTrapvOp && … && !isBfMemSpec`) —
  a "this family is handled elsewhere, do not illegalise it" list.
* **13 ORed illegality terms** (`spec.illegal || eorMemBad || lineImmBad || … || (usesSrcEa &&
  !srcEaOk) || (usesDstEa && !dstOk)`).

The fan-in is **mixed**: roughly half the terms are cheap raw-opword predicates
(`isRteOp:1728`, `isLeaOp:1806`, `isRtdBad:1844`, `isCmp2Chk2Enc:1846`, …), and half are
`spec`-derived and therefore arrive late (`isSysOp:1842`, `isBfMemSpec:1004`, `eorMemBad:1596`,
`fpGenBad:2008`, and the `EaDecoder`-derived `srcEaOk:903` / `dstOk:1556`).

The measured consequence — `docs/superpowers/campaigns/2026-09-03-fuzz-to-zero-divergences.md:1268-1281`:

| formulation of the *same* predicate | FMax | Δ vs 178.35 |
|---|---|---|
| term added to global `bad` | 128.01 | **−50.34 MHz** |
| confined to a local `Scc` block override | 153.37 | **−25.0 MHz** |
| matched off the raw opword | 178.35 | **0 MHz** |

That file's own conclusion: *"`bad` is now known to be timing-critical and should be treated as
closed to new terms."*

### 2.2 What NaxRiscv does

Three things, and all three matter.

**(a) Legality is a *positive* function, not a negative one.** `DecoderPlugin.scala:251`:

```scala
setup.keys.LEGAL := Symplify(INSTRUCTION_DECOMPRESSED, encodings.all) && !INSTRUCTION_ILLEGAL
```

`encodings.all` (`:194`, `:203-204`) is the set of `Masked` keys of *every implemented
instruction*, accumulated one entry at a time. `Symplify` (`spinal.lib.logic`) runs a
Quine-McCluskey-style minimisation and emits a two-level SOP over the raw instruction bits.
There is no "illegal unless one of 25 families". Adding an instruction adds one masked literal
to a set that then gets re-minimised — it does not lengthen an AND chain.

**(b) The only post-hoc override is one `when`, and it is a real architectural condition** —
FPU-disabled / bad rounding mode, `DecoderPlugin.scala:262-264`:

```scala
when(triggered){ setup.keys.LEGAL := False }
```

That is precisely the "local block override" idiom we measured at −25 MHz, and NaxRiscv uses it
exactly once.

**(c) The illegal path is registered and drain-gated, so its timing is nearly irrelevant.**
`DecoderPlugin.scala:297-327`:

```scala
val trigged = RegInit(False) setWhen(set) clearWhen(clear)
val exceptionReg = RegNextWhen(getAll(setup.keys.TRAP), !trigged)
…
val pipelineEmpty = !frontend.isBusyAfterDecode() && commit.isRobEmpty
val doIt = trigged && pipelineEmpty
flushIt(doIt || doItAgain)
haltIt(trigged)
```

`LEGAL` feeds `TRAP` (`:284`), which feeds (i) a sticky register and (ii) a 2-wide prefix-OR
into `DECODED_MASK` (`:285`). It never feeds an issue/dispatch fast path. The trap fires only
once the ROB is empty.

Our `bad`, by contrast, is consumed combinationally in the crack tree (`MicroOpAssembler.scala:3899`,
a high-priority `elsewhen` arm that overrides lower-priority arms and stamps `out.count`) and in
the vector select at `:2070-2088`. It is on the fast path by construction.

### 2.3 Verdict

**Adopt, partitioned.** The raw-opword-determined half of `bad`'s fan-in — all 25 exclusion
terms and several of the illegality terms — is exactly the "matched off the raw opword" case we
already measured at **0 MHz**. Expressing that half as a `Symplify` cone over a declared
`Masked` set is a direct structural match.

The EA/extension-word-determined half (`srcEaOk`, `dstOk`, `eaDstPcRelBad`, `fpGenBad`)
genuinely **cannot** be a function of the opword — see §7.1 — and must stay. For those,
NaxRiscv's third trick applies instead: register the result and gate delivery on pipeline
drain, so the cone has a full cycle rather than sharing one with the crack tree.

`spinal.lib.logic.{Symplify, SymplifyBit, DecodingSpec, Masked}` are present in
**SpinalHDL 1.14.1**, which `build.sbt:4` already pins. Verified directly in the resolved jar
(`spinal/lib/logic/Symplify$.class`, `DecodingSpec.class`, `Masked$.class`). **Zero new
dependency.** The usable signatures are:

```scala
Symplify(input: Bits, trueTerms: Iterable[Masked], falseTerms: Iterable[Masked]): Bool
Symplify(input: Bits, trueTerms: Iterable[Masked]): Bool
new DecodingSpec[T](hardType).addNeeds(Masked, Masked).build(input, coverAll): T
Symplify.getCache(input): LinkedHashMap[Masked, Bool]     // memoised per input Bits
```

`getCache` is the load-bearing one for §5: minterm Bools are **shared across every consumer that
symplifies the same `Bits`**, so N gates over one family set cost one cone, not N.

---

## 3. Problem 2 — the "stuck" 6-writer `faultDynMem` fold (task #127)

### 3.1 Correction: this is already solved, and our solution is better than NaxRiscv's

Task #127 is **closed**. ROB-fold Slice C shipped the missing fourth pattern:

* The four completion writers (`ls`, `sq`, `eu`, `fp`) are **age-arbitrated into one physical
  write port** — `RobPlugin.scala:1675-1683`:
  ```scala
  val faultWin = faultPorts.reduceLeft { (a, b) =>
    Mux(b.valid && (!a.valid || (b.age <= a.age)), b, a) }
  faultDynMem.write(faultWin.robId, faultWin.d, faultWin.valid)
  ```
  Age is `id - head` mod 64 (`:1656`); ties resolve in `ls→sq→eu→fp` order, reproducing the old
  last-assign priority. Soundness proof for dropping the loser at `:1560-1607`.
* The two alloc writers no longer touch it at all — they write the alloc-time half into
  `payload` (`:207-212`) and clear the gate (`faultDynStore(tail) := False`, `:1704`/`:1721`).
* One reader, at the head (`:1935-1942`).

Net: **one** `Mem(RobFaultDyn(), 64)` with a single write port, replacing seven
`Vec.fill(64)(Reg)` arrays (2 944 FF), one of which (`faultAddrStore`) contributed **2 019 of
2 083 loads** on the routed netlist's worst-fanout net.

### 3.2 What NaxRiscv would have done, and why it is worse here

NaxRiscv writes multi-writer `Mem`s freely and lets an elaboration phase legalise them.
`MultiPortWritesSymplifier` (`compatibility/MultiportRam.scala:192-274`) is enabled on **every**
generation path (`Gen.scala:536,628`; `Litex.scala:153`; `litex/NaxGen.scala:96`). It rewrites
any `Mem` with >1 write port and async reads into:

* `RamAsyncMwXor` (width < 10) — N private memories, writer *i* stores `data ^ (XOR of the other
  N−1 at that address)`, read = XOR of all N (`:38-64`).
* `RamAsyncMwMux` (width ≥ 10) — N private single-writer memories plus a small XOR-based
  "location" memory recording which one last wrote; read muxes by location (`:66-105`).

The ROB's own completion tracking uses the same idea by hand (`misc/RobPlugin.scala:81-111`):
one `Mem(Bool)` per completion port holding a *toggle* bit, validity = XOR-reduction vs a
snapshot. That is genuinely elegant for 1-bit-per-entry state.

For a 46-bit `RobFaultDyn` bundle × 6 writers it is not. `RamAsyncMwMux` would give **six
copies** of a 64×46b array plus a 6-way-written location memory — vs our one 64×46b array.
Our age-arbitrated single-port structure is strictly smaller and strictly shorter-fanout.

We already carry our own improved fork of this phase — `hw/MultiportRam.scala:293`
(`class MultiPortWritesSymplifier`), installed by `M68kSpinalConfig.scala:13`, with read-port
replication (`ReplicatedBank`, `:80`) and a measured distributed-RAM inference limit
(`maxReadPortsPerMem`, `:28`) that upstream NaxRiscv lacks.

### 3.3 Verdict

**Nothing to adopt. Justified divergence, and we are ahead.** Do not revisit `faultDynMem` with
the NaxRiscv XOR/Mux lowering — arbitration wins whenever exactly one writer can be
architecturally correct per entry per cycle, which is the case here and was proven at
`RobPlugin.scala:1560-1607`.

The one thing worth keeping from NaxRiscv is the *service shape*: its ROB is a generic keyed
store (`RobService.write[T](key, size, value, robId, enable)` / `readAsync`,
`interfaces/Service.scala:107-122`) that **banks storage automatically per key**
(`misc/RobPlugin.scala:129-195`). Any plugin adds a ROB field by calling `write` and
`addDecodingToRob`. Our 2 730-line `RobPlugin` hand-writes every field. That is a genuine
maintainability difference, but it is a large refactor with no measured FMax upside, so it does
not make the ranked list.

---

## 4. Problem 3 — mispredict recovery. **The highest-value finding in this document.**

### 4.1 Two corrections to the framing of the question

**(a) We already have NaxRiscv's rename-recovery structure.** `rename/RatTable.scala` is a port
of `frontend/RfTranslationPlugin.scala:36-89` (`TranslatorWithRollback`): dual spec/committed
register files plus a 1-bit-per-arch-register `location` selector, rollback = `location := 0`,
O(1).

| | NaxRiscv `TranslatorWithRollback` | our `RatTable` |
|---|---|---|
| spec / committed storage | `Mem.fill(depth)` ×2 (`:49`) | `Vec.fill(archDepth)(Reg)` ×2 (`:67-68`) |
| selector | `val updated = Reg(Bits(depth bits))` (`:68`) | `val location = Reg(Bits(archDepth bits)) init 0` (`:88`) |
| rollback | `when(io.rollback){ updated := 0 }` (`:76-78`) | `when(io.rollback){ location := 0 }` (`:98-100`) |
| read | `sel ? written | commited` (`:85`) | `Mux(location(addr), written, committed)` (`:106`) |

Our deviation to register `Vec`s is documented and correct (`RatTable.scala:55-66`): the
1-entry flag RATs always address the same cell, and the multi-write `Mem` lowering silently
dropped all but the highest write port — a real bug that corrupted a later branch's NZVC
source. Keep the divergence.

**(b) NaxRiscv does not checkpoint rename state per branch. At all.** There is no snapshot
stack, no per-branch RAT copy, no walk. The question "what is the structure and cost" has the
answer: *it declined to pay that cost and got the benefit another way.*

### 4.2 The mechanism NaxRiscv actually uses — a **two-tier reschedule**

`CommitPlugin` exposes the same event on **two ports** with different timing
(`misc/CommitPlugin.scala:39`):

```scala
override def reschedulingPort(onCommit : Boolean) =
  if(onCommit) logic.commit.reschedulePort else logic.reschedule.reschedulePort
```

**Tier 1 — early, at EU resolution time.** An EU raises `newSchedulePort` carrying its own
resolved `pcTarget` (`execute/BranchPlugin.scala:138-150`). `CommitPlugin.scala:118-144`
arbitrates *oldest-wins* across all reporting EUs using `age = robId - ptr.free` with an
explicit tie-break, latches the winner into registers, and one cycle later:

```scala
setup.jump.valid := fresh && !trap          // :146  — PC redirect fires NOW
setup.jump.pc    := pcTarget                // :147  — to the branch's OWN target
```

Consumers of the early port are exactly three, `frontend/FrontendPlugin.scala:66-67` and the
predictors:

```scala
pipeline.dispatch.flushIt(commit.reschedulingPort(onCommit = false).valid)
pipeline.allocated.haltIt(commit.hasPendingRescheduling())
```
plus `prediction/DecoderPredictionPlugin.scala:118` and `prediction/HistoryPlugin.scala:135`
(branch-history restore).

**That `haltIt` on the `allocated` stage is the whole trick.** From the moment a mispredict is
detected, no further instruction is renamed, no RAT write happens, no freelist pop happens, no
ROB entry is allocated. Speculative state is *frozen*, not rolled back. Meanwhile the PC has
already been corrected and fetch/decode/serialize are refilling down the correct path.

**Tier 2 — late, when the mispredicting entry reaches the commit head.** `rescheduleHit`
(`CommitPlugin.scala:199-228`) then does the actual state recovery, in one cycle:

```scala
when(rescheduleHit){ ptr.commitNext := ptr.allocNext; headNext := ptr.allocNext.resized }
```

and everything downstream hangs off the *commit* port: RAT rollback
(`RfTranslationPlugin.scala:143`), EU flush (`ExecutionUnitBase.scala:180`), LSU flush
(`lsu2/Lsu2Plugin.scala:277`, `lsu/LsuPlugin.scala:496,702`), FPU unschedule
(`fpu/FpuWriteback.scala:144`), issue-queue clear (`DispatchPlugin.scala:174-178`), branch-context
pointer reset (`BranchContextPlugin.scala:124-129`).

Freelist recovery needs no checkpoint either: the ROB free pointer walks the squashed entries
and, per entry, frees the *newly allocated* phys reg instead of the *old* one
(`RfAllocationPlugin.scala:78`, `CommitPlugin.scala:242`):

```scala
allocator.io.push(slotId).payload := event.commited(slotId) ? physicalRdOld | physicalRdNew
```

### 4.3 Why this is exactly what our A/B measurement was missing

`docs/superpowers/specs/2026-09-03-early-flush-ipc-ab-measurement.md` is a careful piece of
work and its REVERT recommendation was right. But read its §4 against the above:

> `when(earlyBranchMispredict) { flushPcReg := committedResumePc }`
> The redirect target is the **current committed PC**, never the branch's own resolved
> target — necessarily so, because `flushing` drives `RatTable`/`Freelist`'s single *global*
> "restore to committed shadow" rollback with no per-`robId` selectivity.

The landed variant drove the **Tier-2 action at Tier-1 time**. NaxRiscv never does that. At
Tier 1 it changes exactly two things — the PC, and whether rename is allowed to advance —
neither of which touches RAT, Freelist, ROB, StoreQueue or EU state. Therefore:

* **The 69 `DIVERGED[HANG]` events cannot arise.** They were the "chained-early-flush StoreQueue
  wedge" (`RobPlugin.scala` in-tree comment, quoted at the A/B doc `:250-254`). No SQ state
  changes early under the two-tier scheme, so a second early event cannot chain onto a first.
* **The mispredict-count doubling cannot arise.** The A/B histogram (`:206-211`) showed a new
  population at *ahead = 0 and 1* — re-executions of the same branch after being flushed back to
  the committed PC. Redirecting to the branch's own `pcTarget` and freezing rename means the
  branch is never re-fetched or re-executed.

And here is the part that matters most. The A/B doc's own §7 says:

> `branchy`'s real backlog ahead of the mispredicting branch is only **2.05 entries**, so a
> perfect narrow squash can recover at most ~2 retire cycles per mispredict there. **Most of
> `branchy`'s idle time is refetch/refill latency, which no checkpoint scheme removes.**

That is correct, and it is an argument *for* the two-tier scheme and *against* prioritising the
checkpoint design. A per-branch RAT/Freelist checkpoint recovers the ~2-cycle retire term. The
early PC redirect recovers the **refetch latency** term — the one named as dominant — because
fetch restarts at the correct address the cycle the branch resolves rather than waiting for it
to reach the head. The 2.05-entry histogram does not measure that term at all; it is invisible
to the instrument used.

### 4.4 Verdict

**Adopt, and re-sequence the checkpoint work behind it.** The two-tier reschedule needs:

* a second, early output on `RedirectService` (or a new port) carrying the branch EU's
  `nextPc` (already computed — `BranchEuPlugin.scala:292` puts it on the completion port);
* oldest-wins arbitration across reschedule sources, modelled on `CommitPlugin.scala:118-144`
  — we already have age arithmetic in `RobPlugin.scala:1656`;
* `haltIt` on rename/allocate while an early reschedule is pending —
  `RenameStage.scala` / `DecodeUopInputPlugin`;
* **no change at all** to `RatTable.io.rollback`, `Freelist.io.flush`, the ROB squash, the SQ
  flush or the EU flush. They keep firing off the existing retire-gated `doFlushReg`.

`docs/superpowers/specs/2026-09-03-branch-checkpoint-rat-freelist-design.md` (581 lines, staged
S0-S5, with §2.2's fanout generalisation called out as "the actual multi-day part") should be
measured *after* this, not before. If the two-tier scheme captures most of the refetch term,
the checkpoint design's remaining ~2-cycle upside may not clear its own S5 gate.

---

## 5. Problems 4 & 5 — verification architecture, and decode-structure drift

### 5.1 Verification: one specific inversion transfers; the rest does not

NaxRiscv runs two independent lock-step harnesses — a C++ one with Spike embedded directly
(`src/test/cpp/naxriscv/src/main.cpp`) and a Scala one over RVLS via JNI
(`platform/NaxriscvProbe.scala`, `platform/Tracer.scala`). Both are **delta-based**: no
architectural state is ever dumped and diffed. The trace is a **sim-only whitebox dig** through
`Verilator.public` `CombInit` copies reached by Scala reflection (`NaxriscvProbe.scala:91-98`),
not a synthesizable port. One record per architectural instruction — NaxRiscv does not crack.

The one thing worth taking is **which side drives the comparison loop**. `main.cpp:2193-2210`:

```cpp
assertEq("MISSMATCH PC", pc, spike_pc);
for (auto item : state->log_reg_write) {          // iterate the REFERENCE's write log
  case 0: assertTrue("INTEGER WRITE MISSING", robCtx.integerWriteValid);
          assertEq("INTEGER WRITE DATA", robCtx.integerWriteData, item.second.v[0]);
  case 1: assertTrue("FLOAT WRITE MISSING",   robCtx.floatWriteValid);
  case 4: assertTrue("CSR WRITE MISSING",     robCtx.csrWriteDone);
}
```

Ours does the opposite. `src/test/scala/m68k040/lockstep/LockStep.scala:46` gates the register
check on the DUT's own `archRegValid`, and the file says so in its header (`:18-19`):

> *"Register check is gated on `archRegValid`: a DUT that under-reports validity can hide a
> wrong register. **This is the trust model, not full coverage.**"*

and at `:20-21`:

> *"Memory writes (memAddr/memData/memWrite) are captured but **NOT yet compared**."*

Map that against the seven defects found on 2026-09-03:

| defect | caught by reference-driven loop? |
|---|---|
| divide's remainder never compared | **yes** — Musashi's step writes two registers; one MISSING fires |
| harness silently deleting whole instruction records | **yes** — the deleted step's writes go MISSING, and PC desyncs |
| lagged shadow register replayed as live | **yes** — data mismatch on a write the oracle predicts |
| fold reporting a register write against the wrong instruction | **yes** — the correct instruction reports MISSING |
| CDC skew check matching 0 of 30 instances | no |
| OOC synth gate noise floor 3× the deltas | no |

Four of seven, plus the two CPU bugs that rode them.

We can go one step further than NaxRiscv, cheaply, because our oracle is richer than theirs.
`oracle/OracleStep.scala` already carries the **full** architectural state per step
(`d(0..7)`, `a(0..7)`, `sr`, `msp`, `isp`), where Spike's state sits behind an API. And
`services/Services.scala:28-31` already exposes `CommittedMapService.intPhys` (`:29`) — the committed
phys mapping of every arch register. So a sim-only read of `intPrf[committedPhys(i)]` for
i ∈ 0..15 at each retire gives a **full structural register-file compare** with no new RTL. That
subsumes the delta check entirely and cannot be defeated by any under-reporting.

Two smaller items from the same reading:

* **Compare the memory writes we already capture.** They are collected and dropped on the floor
  (`LockStep.scala:20-21`). Musashi gives store address/size/data. This is a few lines.
* NaxRiscv's Scala path models memory *ordering*, not just values — `loadExecute` /
  `loadCommit` / `loadFlush` / `storeCommit` / `storeBroadcast`
  (`NaxriscvProbe.scala:253,256,259,278-280,329,344`) let RVLS validate a load value that was
  legal under the memory model but unequal to naive sequential memory. That is real
  sophistication we lack — but it is scoped to multi-hart coherency, which we do not have. **Not
  recommended.**

**What NaxRiscv does *not* solve, plainly:** it has **no formal verification** (no `.sby`, no
hardware `assume`/`cover`; the single hardware `assert` in the core is `lsu/DataCache.scala:555`,
and the LSU one that would matter is commented out at `lsu2/Lsu2Plugin.scala:1932`), **no
instruction-stream fuzzing** (we are ahead here — our fuzz campaign has no NaxRiscv analogue),
and **no self-test of its own probe** beyond a 10 000-cycle no-commit watchdog
(`NaxriscvProbe.scala:243-246`). Its harness is *also* silently degradable: `--noRvls` /
`--spike-disabled` reduce a run to "did it reach the pass symbol" with no complaint
(`SocSim.scala:93`, `main.cpp:2173-2179`). The "verification layer needs its own verification"
problem we hit on 2026-09-03 is **not** solved upstream. Do not go looking there for it.

The **one** structural checker worth porting verbatim is `NaxAllocatorChecker`
(`main.cpp:1312-1355`): a shadow busy-vector over the physical register file asserting
`"Double free"` / `"Double alloc"` against the allocator's own alloc/free ports. Our memory
index records a whole recurring bug family here — the *"rename-exposure/phys-reg-reuse hazard
class (tasks #176/#194/#200)"*, three separate real bugs. NaxRiscv turns that class into an
immediate assertion at the moment of corruption instead of a divergence 200 instructions later.
(Note it is `ALLOCATOR_CHECKS?=no` by default upstream — `src/test/cpp/naxriscv/makefile:19` —
which is a mistake we should not copy.)

### 5.2 Decode-structure drift: NaxRiscv cannot have this problem, but the mechanism transfers

`DecodeStage.scala:113-128` documents the failure directly: three parallel gates
(`slot0IsMemInd` ~`:745`, `slot1IsMemIndEarly` ~`:285`, `ucIsMemInd` ~`:1975`) each carrying its
own inline copy of the family list, drifted **four times** — tasks #144/#145 (MOVE, ADDA/SUBA/CMPA),
#150 (ALU RMW + ADDQ/SUBQ), #152 (static bit-op), and TAS on 2026-09-03. Fixed by four shared
`def`s at `DecodeStage.scala:137-160`, commit `d6a0275`.

**The fix is incomplete.** It covered 4 of ~12 family clauses. Eight are still written out three
times with three name prefixes:

| family | slot0 | slot1 | engine |
|---|---|---|---|
| MOVE src/dst | `s0IsMove` `:801-802` | `s1mi_isMove` `:342-343` | `ucMoveSrcMi/ucMoveDstMi` `:1751-1752` |
| ALU src (opmode 0/1/2) | `:803` | `s1mi_isAlu` `:311-314` | `ucAluSrcMi` `:1935` |
| ALU dst RMW (opmode 4/5/6) | `s0AluDstMode` `:657` | `s1mi_isAluDst` `:315-316` | `ucAluDstMi` `:1950` |
| LEA/PEA/JMP/JSR | `:795-798` | `:336-339` | `:2000-2004` |

Two more families are knowingly absent from all three (`DecodeStage.scala:130-136`): line-E
memory shift/rotate, and `Scc <ea>` (fuzz seed 80's silent `D<op[2:0]>` corruption). The trap is
still armed.

NaxRiscv structurally cannot have this. `ExecutionUnitElementSimple.scala:32-42` — a single
`add()` call by the plugin that *implements* an instruction registers it into every derived
table at once:

```scala
def add(microOp: MicroOp, srcKeys: List[SrcKeys] = Nil, decoding: DecodeListType = Nil) = {
  eu.addMicroOp(microOp)                                  // → legality set (encodings.all)
  eu.setCompletion(microOp, euCompletionAt)               // → completion timing
  if (staticLatency && …) eu.setStaticWake(microOp, euWritebackAt)   // → LATENCY_n
  eu.addDecoding(microOp, decoding :+ (SEL -> True))      // → EU-group select
  if (srcKeys.nonEmpty) findService[SrcPlugin](…).specify(microOp, srcKeys)  // → operand mux
}
```

Every consumer is a **projection of one set**, materialised by the same minimiser. E.g.
`DispatchPlugin.scala:130` derives the per-latency wakeup classification straight from the EUs'
declared `staticLatencies()`:

```scala
stage(key, slotId) := Symplify.apply(stage(MICRO_OP, slotId), trueTerms, falseTerms)
```

There is one list; drift is not expressible.

**Transfers?** The *problem* does not — memory-indirect EA routing has no RISC-V analogue at
all. The *mechanism* does, and it lands on the exact formulation we already measured as free.
Concretely, for the eight remaining clauses: declare each family once as a `Seq[Masked]` over
the opword, and have all three gates call `Symplify(opw, thatSet)`. Because
`Symplify.getCache(opw)` memoises per-minterm Bools, three gates over one set share one cone —
so this is plausibly *area- and timing-positive*, not merely tidier. It is also directly
consistent with the header's own measurement at `DecodeStage.scala:98-105`: TAS via
`spec.op === DecOp.TAS` cost 155.11 MHz; TAS via `opw(15 downto 6) === …` gave **178.35 MHz**.

---

## 6. Four things to **not** adopt

1. **NaxRiscv's `RamAsyncMwXor`/`RamAsyncMwMux` lowering for `faultDynMem`.** Six copies of a
   64×46b array vs our one. Age arbitration wins whenever exactly one writer can be correct per
   entry per cycle. §3.
2. **`naxriscv.utilities.Framework`.** It is a hand-rolled 3-phase fiber elaboration
   (`config`/`early`/`late` + per-service `Lock`) that predates the upstreamed
   `spinal.lib.misc.plugin.{FiberPlugin, PluginHost}` + `Database` we already use
   (`core/M68kCore.scala:26-32`). We are on the successor. Do not port backwards.
3. **NaxRiscv's compacting positional issue queue** (`frontend/IssueQueue.scala`). It is
   genuinely interesting — age ordering is *positional*, each slot carries a triangular
   `Bits(priority+1)` trigger mask, wakeup is a broadcast index bitmask with no tag CAM, and
   flush is `io.clear` setting all triggers satisfied and zeroing every `sel` (`:104,129-131`).
   Note the bug-class implication: our recorded *"sbX scoreboard LEAK — wrong-path instr claims
   a resource, never cleared on flush"* is **structurally impossible** there, because there is no
   separate resource scoreboard to leak. But adopting it means a full IQ rewrite that assumes
   1 instruction = 1 ROB entry and a narrow slot context; it does not survive macro cracking or
   our five renamed resource classes. **Note the observation, reject the port.**
4. **NaxRiscv's memory-ordering trace model** (`loadExecute`/`loadCommit`/`loadFlush`/
   `storeBroadcast`). Built for multi-hart weak-memory checking. We are single-hart. §5.1.

---

## 7. Where the comparison legitimately breaks down

**7.1 Variable-length instructions and extension words.** `LEGAL := Symplify(INSTRUCTION_DECOMPRESSED,
encodings.all)` is a function of exactly one 32-bit word. Our legality depends on the opword
*plus* extension words, EA mode/register, operation size and privilege. `srcEaOk`
(`MicroOpAssembler.scala:903`), `dstOk` (`:1556`) and `eaDstPcRelBad` (`:1695`) are
`EaDecoder`-derived and cannot be minimised out of the opword at any price. §2.3's
recommendation is a **partition**, not a replacement — and the residue is the part that stays
expensive.

**7.2 Macro cracking.** NaxRiscv: one instruction = one ROB entry = one trace record
(`NaxriscvProbe.scala:247`). Ours: one macro → N µops, with `lastOfInstr` machinery
(`MicroOpAssembler.scala:4134-4139`) that has no upstream analogue. Two consequences: (a) every
"one record per instruction" assumption in their probe is invalid for us; (b) the defect class
*"a fold reporting a register write against the wrong instruction"* is one NaxRiscv structurally
cannot have, so it offers no guidance on it. §5.1's reference-driven loop must therefore be
applied at **macro** granularity, aggregating the µop write set before comparison —
`9469b4f`'s trailing-`An`-auto-update attribution fix is exactly this problem and shows it is
not theoretical.

**7.3 Condition codes.** NaxRiscv renames two register files (int, float). We rename five
(`RenameStage.scala:105-115`: `intRat`, `nzvcRat`, `xRat`, `fpRat`, `fpccRat`). The O(1)
`location := 0` rollback generalises to five instances for free — which is why we already have
it — but a per-branch checkpoint would have to snapshot five structures, not one. That is a real
multiplier on the cost side of the checkpoint design, and another reason to sequence §4 first.

**7.4 Memory-indirect addressing.** No RISC-V analogue whatsoever. The entire microcode-routing
gate problem of §5.2 is ours alone. NaxRiscv contributes a mechanism, not a solution.

**7.5 MMU U/M descriptor updates — a divergence we are *required* to have.** The mechanism study
is `2026-09-04-naxriscv-walker-comparison-round2.md`; what belongs here is why NaxRiscv offers no
guidance at all.

RISC-V's A/D bits are **software-managed**: `MmuPlugin` never opens a store port, `A=0` simply
faults and `D=0` simply makes the page read-only, and the trap handler sets them. NaxRiscv
therefore has no walker write path, no write queue, and no descriptor-coherency problem — it
deleted the problem at the ISA level. The 68040 **mandates hardware U/M with no software-managed
alternative**, so `mmu/UmWriteQueue.scala` is irreducible. This is a case where our extra
complexity is required, not accidental; there is nothing to borrow and nothing to simplify away.

The two bits are also not equally important, and this should govern how anything MMU-adjacent is
ranked against an FMax or IPC item:

* **U/A is a hint.** It feeds page-replacement heuristics; a stale or missing U costs a
  suboptimal eviction. **Performance only.**
* **M/D is correctness, and its failure is asymmetric.** Over-setting M costs one unnecessary
  writeback. **Under-setting M means a dirty page is evicted as clean — silent data loss.** The
  dangerous direction is always *losing* an M, never gaining one.

Two consequences for this document:

1. **The one NaxRiscv idea in this area that is worth naming is an invariant, not a mechanism.**
   Its TLB entry computes `allowWrite := W && D` — the write *permission* and the dirty *bit* are
   the same fact, so a write cannot physically occur on an entry whose D has not been recorded.
   Our `needsMRefresh` is structurally identical. **Treat that property as load-bearing.**
   Anything that would let a write proceed on an entry whose M has not been committed is a
   data-loss bug no matter how well it reads on FMax or IPC, and no optimisation in §8 may
   weaken it.
2. **`UmWriteQueue`'s flush discipline is already on the safe side of the asymmetry, and item 1
   must keep it there.** `UmWriteQueue.scala:111-116` discards only *uncommitted* entries on
   flush:
   ```scala
   when(io.flush) {
     for (i <- 0 until depth) keep(i) := valids(i) && committed(i)
     for (i <- 0 until depth) when(!keep(i)) { valids(i) := False }
     tail := (head + CountOne(keep)).resized
   }
   ```
   A genuinely-needed M write — one whose instruction has retired and so is marked `committed`
   (`:91-98`) — **survives** a flush and still drains. The suppressed direction is the
   conservative-safe one. §8 item 1 is neutral to this by construction: its Tier 1 asserts **no
   flush at all**, only a PC redirect and a rename halt, so `io.flush` continues to be driven
   solely by today's retire-gated `doFlushReg`. That neutrality is a requirement of the design,
   not an accident of it — any future variant that fires a flush earlier must re-prove that
   `committed` marking cannot lag the flush, or it re-opens the data-loss direction.

Separately and minor: NaxRiscv's `AddressTranslationService`
(`interfaces/Service.scala:405-416`) is a *pipeline-stage-parametrised* port factory
(`newTranslationPort(stages, preAddress, allowRefill, usage, portSpec, storageSpec)`) where ours
is a fixed-shape service — relevant to that study, not this one.

---

## 8. Ranked actionable list

**Rank 0 — not mine to specify, but it outranks everything below on severity.** The walker /
D-cache descriptor-coherency gap is a **silent data-loss** defect, not a performance nit: the
walker writes the U/M descriptor byte straight to DRAM on `AxiIds.WALK_WRITE = 3`
(`cache/AxiIds.scala:98`), issued on the TLB plugins' own AXI master
(`mmu/DtlbPlugin.scala:475`, `mmu/ItlbPlugin.scala:385`; the walker's own port is
`Axi4ReadOnly`, `mmu/TableWalker.scala:46`) and therefore **never through `DcachePlugin`**. A
dirty copy of that descriptor line sitting in the
copyback D-cache is later written back **including the stale M byte** and the update is lost —
the *under-set M* direction of §7.5's asymmetry. Its mechanism and fix are owned by
`2026-09-04-naxriscv-walker-comparison-round2.md` (and previously scoped in
`2026-08-18-walker-dcache-passthrough-design.md` /
`2026-09-03-walker-dcache-routing-revalidation-and-itlb-prefetch-design.md`); this document
neither re-derives nor re-specifies it. It is recorded here only so the ranking is honest: **if
engineering slots are contended, that work takes precedence over items 3 and 5 below**, both of
which are FMax/observability items. Items 1, 2 and 4 are cheap enough and independent enough to
proceed alongside it.

| # | item | effort | expected benefit | risk |
|---|---|---|---|---|
| **1** | **Two-tier reschedule**: early PC redirect to the branch EU's own `nextPc` + oldest-wins arbitration + `haltIt` on rename/allocate; **all** state rollback stays on the existing retire-gated `doFlushReg`. NaxRiscv refs: `FrontendPlugin.scala:66-67`, `CommitPlugin.scala:118-157,199-228`, `BranchPlugin.scala:138-150`. | **3-5 days** + mandatory OOC synth gate | Attacks the **refetch-latency** term our own A/B doc (`2026-09-03-early-flush-ipc-ab-measurement.md` §7) names as dominant in `branchy` and explicitly says no checkpoint scheme removes. Measure with the existing 13-kernel harness + the `deep-backlog` kernel. | Low-to-moderate. Cannot produce the previous 69 hangs (no early state change), but the rename halt is a new backpressure path and needs its own drain/deadlock review. Gate on the `investigate/bsr-flush-skip` corpus (17/17 today). **Hard constraint: Tier 1 must assert no `flush` — see §7.5(2). `UmWriteQueue.io.flush` and `StoreQueue`'s equivalent stay driven solely by the retire-gated `doFlushReg`, or the M-bit data-loss direction re-opens.** |
| **2** | **Invert the lock-step loop.** Drive comparison from `OracleStep`'s write set, not `CommitObservation.archRegValid`; add a full 16-register structural compare per retire via `CommittedMapService.intPhys` (sim-only, no RTL); compare the memory writes already captured at `LockStep.scala:20-21`. NaxRiscv ref: `main.cpp:2193-2210`. | **1-2 days**, test-only | Closes the exact hole `LockStep.scala:18-19` documents as *"the trust model, not full coverage"*. Would have caught **4 of 7** verification defects and both CPU bugs that rode them. Must aggregate µops per macro — §7.2. | Very low. Test-only. Expect a burst of new failures on landing; that is the point. |
| **3** | **Declarative family sets + `Symplify`.** First slice: the **eight** still-triplicated microcode gate clauses (§5.2 table) + the two knowingly-absent families. Second slice: partition `bad`'s 25 opword-determined exclusion terms into a `Symplify` cone; register + drain-gate the EA-derived residue (NaxRiscv `DecoderPlugin.scala:251,297-327`). | **2-3 days** slice 1; **4-6 days** slice 2 | Slice 1 removes the drift trap that has fired four times, and `Symplify.getCache` shares one minterm cone across all three gates. Slice 2 **reopens `bad`**, currently declared *"closed to new terms"*. Both land on the formulation already measured at 178.35 MHz vs 128.01 MHz. Zero new dependency — `spinal.lib.logic` is in the pinned SpinalHDL 1.14.1. | Low for slice 1 (mechanical, lock-step-checkable). Moderate for slice 2 — `bad`'s consumers include a priority `elsewhen` arm (`:3899`) whose ordering is load-bearing. |
| **4** | **Port `NaxAllocatorChecker`** — sim-time shadow busy-vector over each physical register file, asserting double-alloc / double-free against `Freelist`'s pop/push ports. NaxRiscv ref: `main.cpp:1312-1355`. Enable by **default** (upstream's `ALLOCATOR_CHECKS?=no` is a mistake). | **0.5-1 day**, test-only | Tasks **#176 / #194 / #200** were all this class. Converts "divergence 200 instructions downstream" into an assertion at the cycle of corruption. Five freelists to instrument (§7.3). | Very low. |
| **5** | **`PerformanceCounterService`** — any plugin calls `createEventPort(id)`; counters exposed over the existing `dbg_axi` debug slave. NaxRiscv ref: `interfaces/Service.scala:581-589`, `misc/PerformanceCounterPlugin.scala:19-46`. | **1-2 days** | Hardware-visible branch-miss / I-cache-refill / D-cache-refill / commit counters on the real board, replacing ILA-capture archaeology in the ongoing boot investigation. Complements, does not replace, the ILA pipeline. | Low, but it is area on a core already 0.069 ns from its FMax target — gate it behind a generation flag. |

**Sequencing note:** items 2 and 4 are test-only and independent; land them first so items 1 and
3 are measured against a harness that can actually see a regression.
`2026-09-03-branch-checkpoint-rat-freelist-design.md` should be re-evaluated **after** item 1
reports, not before.

---

## 9. The single most valuable thing NaxRiscv does that we do not

**It separates *when the PC is corrected* from *when speculative state is rolled back*** —
redirecting fetch at EU-resolution time to the branch's own resolved target while merely
*freezing* rename, and deferring every rollback to the retire point where a single global
"restore to committed" is exact by construction. We collapsed both into one retire-gated event,
which is why our early-flush attempt had to resume at the committed PC, and why it cost 44.9 %
IPC and 69 hangs rather than winning.
