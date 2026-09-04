# Part 127 — the `CLR.L (A7)+` wedge: the stale-physical-register hypothesis is REFUTED on Part 126's own arithmetic; the `crackStore` asymmetry does not exist; both Part 126 SS8 latent defects are FIXED with a genuinely-failing-before test; the wedge itself is NOT fixed

> **Filing note.** The campaign document is
> `macqd700-soc:docs/BUG_calibration_word_misplaced_0d00.md`. This session ran in a
> worktree of the **core** repo (`m68k-core-040-ooo`, branch `fmax-closure-fanout`),
> so Part 127 is written here and must be **appended to that file** when the core
> changes are merged. Part number assigned by the coordinator; do not re-derive it by
> grepping for highest+1.

## 0. TL;DR

* **NOT FIXED.** The board still wedges. No fix for the wedge is claimed, and no
  speculative fix was shipped.
* **NO HARDWARE MEASUREMENT WAS MADE IN THIS SESSION.** The JTAG lease was not taken,
  no bitstream was built, the SD card was not touched. Every positive statement below
  is of the form *"the simulation behaves correctly at this instruction"* or *"the RTL
  says X"*. Where a hardware fact is used it is quoted from Part 126's measurements,
  never re-measured here.
* **Part 126 SS7's surviving hypothesis — "a stale physical-register read" — is
  REFUTED, using Part 126's own numbers.** The physical register that architectural
  `A7` names demonstrably **holds 4**, and by in-order rename that is the very
  register the next `CLR`'s store µop reads. A store that computes `EA = 0` from a
  register holding 4 is not reading a stale register; it is not reading that register
  at all. SS2.
* **The `crackStore`-versus-trailing-store asymmetry Part 126 proposed is REFUTED at
  the datapath level.** `MOVE.L Dn,(A7)+` and `CLR.L (A7)+` reach the LS EU with
  identical `autoStoreAn` / `pdstValid` / `compData` / `compWakes` / `e.wakes`. The
  two fields that do differ (`eaAutoDrop`, `rmwStore`) feed **only** `wbObs.divRem`, a
  sim/whitebox observation marker with no datapath consumer. SS3.
* **What the evidence now points at is operand DELIVERY, not register contents:**
  `base0 = Mux(u0.psrcAValid, rdBase.data, 0)` — `LsEuPlugin.scala:357`. A literal-zero
  base with a `+4` write-back is exactly what `psrcAValid = false` produces, and the
  same one-bit fault predicts *both* measured values from *one* signal. SS2.4.
* **The wedge does NOT reproduce in a full-SoC Verilator boot of the real ROM, on the
  SAME cpu040 commit (`7d74ba33`) the deployed bitstream was built from.** The loop
  runs exactly 8 iterations and exits at `0x4084BED4`, from the board's own
  `A5 = 0x03FFFFD4` / post-push `SP = 0x03FFFFE0`, with the posture measured (not
  assumed): `mmuEn = 1` in **0 of 130,133** cycles, `fastSt = 1` in **0** — every store
  precise — and ~25 cycles per iteration of real AXI round trip. **The obvious
  objection — "the fast-boot ROM patches removed the trigger" — was closed by
  measurement, not argument**: a `chime-skip`-ONLY boot (nothing bounded but the
  startup chime) reaches the routine at retire 3,152,146 and behaves identically.
  SS6.
* **Part 126 SS6(e)'s "the trigger requires microarchitectural state accumulated by a
  real boot" is now doubtful.** The ROM's RAM diagnostics run **after** `0x4084BEA8`
  (first entry at retire ≈ 6,436), proven by a `checksum-fast,chime-skip` arm being
  **byte-identical in retire indices *and* sim_times** through the whole wedge routine
  to one with the RAM diagnostics unbounded. The wedge routine runs *early*, with
  little residue in front of it. SS6.
* **A verification gap Part 126 named is CLOSED.** `runLockStep`'s D-side attach was
  hard-wired to a zero-latency memory, so the whole `deferCompletion` → `pendMem` →
  replay leg — which defers a precise `(An)+` store's An write-back *and its consumer
  wakeup* — had never been held open for a realistic number of cycles. It now takes a
  config, with a **positive control that measures the deferral window at 2 / 14 / 71 /
  128 cycles** across four presets. 30 directed cases in that posture: **all pass**.
  SS4.
* **Both Part 126 SS8 latent defects are FIXED**, with directed tests, one of which
  **fails on unmodified RTL in a separate baseline worktree** and passes with the fix.
  Operational consequence: **halting this machine over JTAG during a precise drain was
  a guaranteed hang**, and with `CACR.DE = 0` every store is precise. SS5.

---

## 1. Method note

Two techniques carried this Part, and neither needed new hardware access.

**(a) Push the measurements through the RTL's own arithmetic until they contradict
each other.** Part 126 recorded EA = 0 and write-back = 4 as two facts. In this core
they are *one* fact — `s1Va` and `s1AnPost` are both derived from the single `s1Base`
register — and once that is noticed, a third measurement Part 126 also recorded
(`D0 = 0x04000004`) turns the surviving hypothesis into an arithmetic impossibility.
No new instrument was required; the refutation was already in Part 126's own SS2/SS3.

**(b) When a negative result is the deliverable, build the positive control first.**
The D-side latency work in SS4 is a negative — 30 cases pass. That is worth nothing
unless the axis it varies demonstrably moved, so the drain-window width is asserted
with both a floor *and* a ceiling, and the zero-latency baseline is measured **in the
same suite** rather than remembered. See SS4.3.

---

## 2. The stale-physical-register hypothesis is arithmetically impossible

### 2.1 What Part 126 measured (quoted, not re-measured)

* `[0x00000000]` is restored to zero within one JTAG round trip; `[0x00000004]`
  retains a poisoned `0xCAFEBABE` for 4 s; the loop's correct target range
  `0x03FFFFE0..0x03FFFFF0` survives 3 s untouched. (Part 126 SS2.)
* Halted `regs` at the wedge: `A7 = 0x00000004`, `ISP = 0x00000004`,
  **`D0 = 0x04000004`**. (Part 126 SS3.)
* `pc-trace 32` contains only `0x4084BECE / 0x4084BED0 / 0x4084BED2`; `branch-ring`
  shows `taken=1 mispredict=0` on every entry; `inst-count` advances 17,435,692 macros
  in 4 s. (Part 126 SS3.)

### 2.2 EA = 0 and write-back = 4 are ONE fact, not two

`LsEuPlugin.scala`:

```scala
val s1Va     = (s1Base.asSInt + s1Disp + s1Index.asSInt).asUInt   // :440
val s1AnPost = (s1Base + u1.eaDelta).asBits                       // :449
```

For `EaAuto.POSTINC`, `s1Disp` is hard-wired to `S(0, 32 bits)` (`:433`) and
`s1Index` is 0 (no index register). So `EA = s1Base` and `anWb = s1Base + 4`,
**both off the same flop**. Measuring `EA = 0` therefore *forces* `anWb = 4`; the two
Part 126 observations are one observation of `s1Base = 0`.

### 2.3 The physical register `A7` names holds 4 — so the base is not read from it

`MOVE.W %sp,%d0` at `0x4084BED0` (opword `0x300F`, source = A7 direct) leaves
`D0 = 0x04000004`: the merge of the old high half `0x0400` with a low word of
**`0x0004`**. That instruction reads architectural A7 **after** the `CLR`'s store µop
renamed it, so the physical register the store allocated for A7 genuinely **contains
4**.

Rename is in-order (`RenameStage.scala`), nothing else in the loop writes A7, and
`pc-trace` / `branch-ring` show no other instruction and no mispredict. Therefore
**the next iteration's store µop reads that same physical register as its `psrcA`.**

So the next store computes a base of 0 from a register that holds 4. It cannot be
reading a stale or unwritten PRF entry — the entry it names is neither stale nor
unwritten. **Part 126 SS7's surviving hypothesis is refuted.**

### 2.4 What that leaves, and where it is in the RTL

`LsEuPlugin.scala:357`:

```scala
val base0 = Mux(u0.psrcAValid, rdBase.data.asUInt, U(0, 32 bits))
```

with the existing comment: *"Absolute / PC-relative EAs carry NO base register
(psrcAValid=false ...), so the base contribution must be ZERO there."*

A literal-zero base with a `+4` write-back is **exactly** `psrcAValid = false`, and it
predicts both measured values from a single bit. The nearby possibility in the same
class is `psrcA` naming a *different* physical register that happens to hold 0. Both
are **operand-delivery** faults, and they are distinguished by the same three signals:

| signal | why |
|---|---|
| `psrcAValid` as DELIVERED at issue | the base-select bit itself |
| `psrcA` as DELIVERED at issue | distinguishes "no base" from "wrong base register" |
| `s1Base` (`LsEuPlugin.scala:402`) | the value actually latched into the AGU |

**Delivered** is load-bearing. The IQ splits each µop into a narrow per-slot flop
record (`IqHot`) used by its own wakeup/select logic, and a **cold `Mem` half**
(`coldWay0`/`coldWay1`, `Mem(RenamedUop(), 64)`, `ram_style = "distributed"`,
`IssueQueuePlugin.scala:228-234`) that is re-read whole at issue and is what the EU
actually consumes. `psrcAValid` exists in **both**. A fault in the hot copy would
change dependency tracking (visible as a hang or a lost wakeup); a fault in the cold
copy would leave scheduling perfect and only corrupt the operand — which is what the
board shows. Capture the *cold* path.

### 2.5 The assumption that would break this, stated plainly — and what a sim capture says about it

SS2.3 needs **no flush or exception between `MOVE.W`'s read and the next store's
read**. `pc-trace` (three PCs only), `branch-ring` (`mispredict=0` on every entry) and
`inst-count` (no stall) all support that, but **none of them is a direct "no flush
occurred" instrument**. If a hardware capture shows flush activity inside the loop,
this deduction fails. That is the way to attack it, and it should be attacked.

A full-SoC **simulation** capture of the same loop (SS6) measured the opposite regime,
and the difference is worth recording precisely because it cuts *against* the
flush-window family of explanations rather than for it:

* In sim the loop is **not** flush-free — `robFlush = 1` with
  `robFlushPc = 0x4084BECE` on **every** taken iteration, 17 `brMispred` cycles in a
  401-cycle window. Every `CLR` store there issues into a freshly-flushed,
  freshly-renamed pipeline — the most fragile case — and **all eight still compute the
  correct EA and the correct `+4` write-back.**
* On the board the loop runs ~17 M times and `branch-ring` reports `mispredict = 0` on
  every entry, i.e. the predictor has long since converged and the loop is flush-free.

So sim samples an all-mispredict regime and silicon an all-correct-prediction one.
That is a genuine, measured sim/silicon divergence — but it does not rescue a
flush-window mechanism, because the fragile regime is the one that works. And it does
not explain a **first**-iteration `EA = 0` on hardware, which remains the fact needing
an explanation.

---

## 3. The `crackStore` versus trailing-store asymmetry does not exist

Part 126 SS7 named this as "the exact structural difference" to confirm or refute
first: `MOVE.L Dn,(A7)+` is a `crackStore` (one µop, writes NZVC, is its macro's kept
commit, folds its own A7 write) whereas `CLR.L (A7)+` is a `crackClr` whose A7 write
rides a **trailing** store µop that is not the kept commit.

The decode-level difference is real. **The datapath consequence is not.** Both µops
reach `LsEuPlugin`'s store path with:

| field | `stUop` (MOVE) | `rmwStUop` (CLR) |
|---|---|---|
| `memOp` / `cluster` | STORE / LS | STORE / LS |
| `eaAuto` / `eaDelta` | POSTINC / 4 | POSTINC / 4 |
| `srcAReg` / `srcAValid` | base An / true | base An / true |
| `dstReg` / `dstValid` | base An / auto | base An / auto |
| `autoStoreAn` (`:1345`) | true | true |
| `compData` (`:1384`) | `ctx.anWb` | `ctx.anWb` |
| `compWakes` (`:1393`) | true | true |
| `e.wakes` (`:1766`) | true | true |
| `writesNzvc` | **true** | **false** |
| `eaAutoDrop` (`:1400`, `:1768`) | **false** | **true** |
| `rmwStore` (`:1403`, `:1769`) | false | false |

The only datapath-visible difference is `writesNzvc`, which adds an NZVC write-back
and an `lsNzvcBusy` entry — neither of which touches the A7 operand. `eaAutoDrop` and
`rmwStore` are consumed at exactly one place:

```scala
wbObs.divRem := compStkPush || compCcrRestore || compRmwStore || compEaAutoDrop || compCrackDrop   // :2737
```

`wbObs` is the lock-step/whitebox observation record; `divRem` has no consumer in the
synthesized datapath. **The asymmetry is an observation-stream artefact, not an
operand-source or wakeup difference.** It should not survive as a lead.

Related, and also checked: both µops' An write-back is tracked in the IQ's **dynamic**
`lsBusy` bitmap, not the static latency-1 scoreboard (`isLs(u) && u.pdstValid`,
`IssueQueuePlugin.scala:1174/1199`), so a consumer of either is correctly held until
`lsWakeup`. There is no "untracked (An)+ store write-back" gap.

---

## 4. Closing the D-side latency gap (Part 126 SS7's own "verification gap worth closing")

### 4.1 What was missing

With `CACR.DE = 0` (the board's posture) `LsEuPlugin.txEffectiveCmode` forces every
data access to `INHIBITED`, `fastStore` is false, and therefore **every store is
`precise`**: it drains only at the ROB head, and its ROB completion, its `(An)+` An
write-back and its consumer wakeup are all deferred into `pendMem` and replayed only
when the SQ confirms the AXI drain. Part 126 put the corpus into that posture for the
first time. What it could not do is hold the deferral window **open**: `runLockStep`
attached a zero-latency `BehavioralMemAgent` to the D-cache AXI, and its `cfg`
parameter only reached the I-fetch attach.

### 4.2 What changed (test-only, plus one `simPublic`)

* `runLockStep` gains `dcfg: AxiMemModelConfig` (default = the previous behaviour, so
  every existing call site is byte-identical) and `maxCycles` (the 4000-cycle budget
  was hard-coded and is far too small once a store costs tens of cycles).
* `BehavioralMemAgent` gains a full config passthrough.
* Two new presets. **`dramCycles` never reaches the write engine** — `AxiWriteEngine`
  schedules its B response at `cfg.latency.hitCycles` unconditionally — so
  `l2DramSlow` widens a *load* to ~60 cycles and leaves a *store* at 5. Since the
  store-side latency **is** the deferral window, `L2Sweeps.storeSlow` /
  `storeVerySlow` set `hitCycles` directly. This is worth knowing independently: any
  future "we tested it with a slow memory" claim about a store path needs to check
  which knob it actually turned.
* `LsEuPlugin.preciseDrainBusySig.simPublic()` — a sim-only tap, zero synth impact.

### 4.3 The positive control

`p127 CONTROL` measures the widest single precise-drain window (`preciseDrainBusy`,
launch through one cycle past resolution) and asserts a floor **and** a ceiling:

| D-side preset | widest precise-drain window (two independent runs) |
|---|---|
| `zeroLatency` (what the corpus has always run) | **9 / 9** cycles |
| `l2DramFast` | **14 / 13** cycles |
| `storeSlow` | **71 / 72** cycles |
| `storeVerySlow` | **128 / 130** cycles |

(The ±1–2 spread between runs is why the assertion is a band, not an equality.)

(The `zero` row's ceiling was initially guessed at 8 and the control **caught it** —
the measured baseline is 9. That is the control behaving as intended: a bound that can
only be satisfied by measuring, not by remembering.)

The board runs ~68 cycles per loop iteration, so `storeSlow` brackets it. The ceiling
on the `zero` row is what makes this a measurement rather than a claim: the
pre-Part-127 baseline is pinned in the same suite.

### 4.4 The result: a clean, non-vacuous negative

30 directed cases, all passing:

* the ROM's `MOVEA.L A5,A7` → push → `CLR.L (A7)+` chain at the board's real
  `A5 = 0x03FFFFD4`, at three D-side timings;
* the stack-clear loop's real 64 KiB-boundary exit, at three D-side timings;
* a producer/consumer A7 chain that alternates `crackStore` and `crackClr` producers
  and consumers, so a divergence would localise which flavour is at fault;
* a **phase sweep** (0–5 `nop`s before the chain, at four D-side timings), because the
  surviving hypothesis is a scheduling effect and instruction alignment is the input
  that shifts pipeline phase relative to the drain;
* a long INHIBITED store burst with a slow D side.

**Every one behaves correctly.** Stated in the required form: *the simulation computes
the correct base for `CLR.L (A7)+` in the board's cache/MMU posture with a board-scale
precise-store deferral window, across every instruction alignment tried.*

---

## 5. The two Part 126 SS8 latent defects: FIXED

Neither is the wedge — the board is retiring, not deadlocked. Both are real, both were
in the shipped bitstream, and one made debugging this machine actively unsafe.

### 5.1 SS8(a) — the `pendMem` / store-queue ring desynchronising forever

`StoreQueue`'s flush rule has an **exception** that `LsEuPlugin`'s rollback never
mirrored. `headDrainInFlight` deliberately KEEPS an uncommitted precise head whose
drain half `DcachePlugin` has already accepted (the Part 37 fix: the physical write
already left the CPU and cannot be un-issued). `LsEuPlugin` meanwhile did:

```scala
when(sqFlushSig) { pendPush := pendReadyAfterThisCycle }
```

unconditionally. The kept entry's `pendMem` slot sits **at** `pendReady`, so `pendPush`
collapses onto it and the next precise alloc overwrites the still-in-flight record.
When the old drain finally acks, `pendReady` advances over an entry that is no longer
the one that completed and **the two rings are off by one forever** — which
`deferCompletion`'s own comment describes as "the ROB head parks on it permanently
(HANG)", plus An/A7/NZVC write-backs applied with the wrong data.

`RobPlugin`'s `!preciseDrainBusyIn` retire gate closes most of the window, but
`preciseDrainBusyReg` is **registered** one cycle after `preciseLaunch`, so a flush
landing on the launch cycle still slips through — and `doFlushReg` also fires from
`debugRecoverEnter` / `debugPcApply`, which are **not retire-gated at all**.

> **Operational consequence, true for every debug session on this bitstream:**
> **halting over JTAG during a precise drain was a guaranteed hang**, and with
> `CACR.DE = 0` *every* store is precise.

**Fix.** Take the keep decision from the component that makes it instead of
re-deriving it: `StoreQueue` exports `io.flushKeptPrecise`, and the LS EU adds exactly
+1 (at most one such entry can exist — a precise drain's `noAccepted`/`sendAtHead`
gates make it the sole occupant of the drain pipe).

### 5.2 SS8(b) — an orphaned split entry, and a mis-completed unrelated instruction

The same flush destroys the kept entry's **ROB** slot (the ROB flush is pointer-only,
`tail := head`), so its robId is immediately re-allocatable. Two consequences:

1. **Slot B can never launch.** Its only gate was `robIds(head) === io.robHeadIn`, and
   the ROB entry is gone. The ring wedges on a dead index and nothing behind it can
   ever drain — the same permanent-hang class Part 37 fixed one instance of.
2. **Worse, if a new instruction inherits that robId**, the compare becomes true
   again, slot B launches, and `io.sqCompletion.payload := robIds(head)` marks that
   brand-new ROB entry **complete** — it retires without having executed, and the LS
   EU replays the dead store's An/NZVC write-back onto its rename.

**Fix.** A kept entry is marked `orphans(head)`. An orphan gets its own launch arm in
`headPreciseReady` (bypassing both the ROB-head compare and `irqPreemptPendingIn` —
there is no ROB entry to preempt in favour of, and the exception FSM waits for the SQ
to go empty, so holding it back would deadlock). Its completion still fires so the
`pendMem` slot is **consumed** (or `pendApply` never catches `pendReady` again), but
carries `io.sqCompletionOrphan`, and the LS EU's replay stage then drives **nothing**:
no `sqCompletionPort`, no `sqFaultCompletionPort`, no `intW`/`nzvcW`/`wakeupPort`.

### 5.3 Verification bar for these fixes

Part 126 noted `StoreQueueSpec.scala:835` cannot observe either defect, and named the
reason: it pins `io.robHeadIn` to the flushed store's own robId for the whole test,
which hands the kept entry exactly the input it needs to finish.

The new tests use the **identical** setup and change **one** input — what the ROB head
does after the flush.

| test | result on **unmodified** RTL (separate baseline worktree) | with the fix |
|---|---|---|
| `StoreQueueSpec` "Part 127 (b1)" — slot B must launch after the ROB head moves on | **FAILS**: *"slot B ... never presented within 200 cycles ... permanently wedged"* | PASS |
| `StoreQueueSpec` "Part 127 (b2)" — the completion must be flagged orphan | n/a (asserts a new signal) | PASS |
| `LsEuFastPreciseSpec` "Part 127" — the kept entry's `pendMem` slot must survive | see SS7 | PASS |

The LS-EU test needs a **realistic D-side latency** to be reachable at all: with the
old zero-latency memory the accepted-but-unacked window is ~1 cycle wide. That is
itself why this was never caught, and it is the second thing SS4's new `dcfg` bought.

---

## 6. The full-SoC boot: the wedge does NOT reproduce, on the SAME RTL as the bitstream

A companion session drove a full-SoC Verilator boot of the real ROM
(`files/420dbff3.rom`) on `build/fpga_top_rom_p126/Vfpga_top`. Everything here is a
statement about the **simulation**; no hardware measurement was made.

### 6.1 The result

The `clr.l (%sp)+` loop at `0x4084BECE` runs **exactly 8 iterations and exits at
`0x4084BED4`** — the arithmetically correct count for `SP: 0x03FFFFE0 → 0x04000000`,
matching MAME and Part 126 SS6(e)'s verbatim stub. Observed on both entries to the
routine (retires 6471–6499 → exit 6503, and 8256–8284 → exit 8288) and reproduced
byte-identically by four independent processes (md5 `634daa045d218e15246463a6c8cb3083`
of the retire stream through retire 6520).

`+watch_pa` confirms board-exact state: bank base `0x00000000` at `0x03FFFFD4`, size
`0x04000000` at `0x03FFFFD8`, terminator `0xFFFFFFFF` at `0x03FFFFDC` — hence
`A5 = 0x03FFFFD4` and post-push `SP = 0x03FFFFE0`, byte-identical to what the board's
memory holds. `0x00000000` and `0x00000004` never changed.

### 6.2 Why the negative is not vacuous

The posture the surviving hypothesis needs was **measured**, over a 130,133-cycle
`+pipe_trace_cycles` window, not assumed:

| signal | cycles high |
|---|---|
| `mmuEn` | **0** of 130,133 |
| `fastSt` (a store taking the non-precise fast path) | **0** — every store is `precise` |
| `dcLdS1Hit` (D-cache load hit) | **0** — D-cache never hits ⇒ `CACR.DE = 0` |
| `dcSerSt` (serialized store) | 43,420 |
| `icS1Hit` | high ⇒ I-cache on ⇒ `CACR = 0x00008000` |

~25 cycles per loop iteration of real AXI round trip. **The deferral window the
stale-operand hypothesis requires is present, at board scale, and the loop still
terminates correctly.**

### 6.3 Same RTL as the wedging bitstream

The simulation was built from `cpu040/generated/M68kSocketTop.v` at cpu040 commit
**`7d74ba33`** — the same commit `build/vivado_p123_100mhz/fpga_top.bit` was built
from. This is not a newer-RTL artifact. **The identical RTL is correct in simulation
and wrong on silicon**, which pushes the remaining explanations toward either (a)
something the RTL simulation cannot express — implementation-level infidelity in the
deployed bitstream — or (b) a state the simulated boot does not reach.

### 6.4 Correction to Part 126 SS6(e), and to the route it recommended

Part 126 SS6(e) concluded *"the trigger is not in the instruction stream; it requires
microarchitectural state accumulated by a real boot"*, and recommended the checkpointed
full boot on that basis. Two measurements weaken that:

* **The wedge routine runs EARLY.** First entry at retire ≈ 6,436 / cycle ≈ 105,563.
* **The ROM's RAM diagnostics run AFTER it.** A `checksum-fast,chime-skip` arm with
  the RAM diagnostics **fully unbounded** is byte-identical — same retire indices *and*
  same `sim_time` stamps — through the whole wedge routine. So execution through this
  routine is deterministic and **independent of `meminit-fast`**, and the residue delta
  versus a stock boot at first entry reduces to the ROM self-checksum sweep plus a
  delay loop.
* **And that last delta was closed too, by measurement rather than argument.** The
  honest objection to any fast-boot result is that the patches removed the trigger, and
  `clean-fastdiag` is *not* residue-neutral. So a control was run with **`chime-skip`
  alone** — nothing bounded except the startup chime, i.e. the ROM self-checksum sweep
  runs in full. It reached `0x4084BEA8` at **retire 3,152,146 / cycle 10,200,364**, ran
  **exactly 8** `CLR`s, and exited at `0x4084BED4`, with **zero writes to address 0 in
  3.15 M retires**. The only remaining difference from a stock boot is idle time in a
  two-instruction chime loop that touches no data memory.

So there is much less accumulated residue in front of the wedge routine than Part 126
assumed, Part 126's own six lock-step cases and three stubs were closer to the real
entry conditions than it credited them for, and **a very long checkpointed boot is a
much weaker bet than it looked** — Part 126's "suggested next step, in priority order"
should be considered superseded.

For scale: a plain unpatched boot runs at **~9,450 cycles/s**, so the board's ~2.5e9
cycles to the wedge is **~66 hours** of simulation and is not reachable; the same
routine is reached in ~12 s with `clean-fastdiag,chime-skip`, and in a few minutes with
`chime-skip` alone. A plain `calibration-fix` boot spent 10.2 M cycles in the ROM
checksum loop and then 22.8 M more in the ASC chime `DBF` loop at `0x40807118`, never
touching `0x4084BE` in 33,162,057 cycles / 4,558,084 retires — which is where the 66
hours goes, and why `chime-skip` alone is the right control rather than a longer wait.

> **A caution about `mame-fastdiag`, deliberately NOT used:** its
> `alias-probe-mame-state` blob-patches ROM `0x4bb74` and jumps to `0x4084bc38` with
> synthetic MAME **4 MB** state (`a5 = 0x40803bb8`, `sp = 0x10`), and
> `ram-list-sentinel-fast` forces the RAM-descriptor walk down the list-end path. Both
> land directly on the routine under investigation. Any result from that patch set is
> not a measurement of this bug.

---

## 6.5 Where that leaves the hypothesis space

Combining SS2 (the base is an operand-delivery fault, not a PRF-contents fault), SS3
(the proposed decode asymmetry has no datapath consequence), SS4 (a board-scale
deferral window does not trigger it) and SS6.3 (the identical RTL is correct in
simulation), the surviving families are:

1. **Implementation-level infidelity in the deployed bitstream.** The signal the
   evidence points at (`psrcAValid` / `psrcA`) is delivered to the LS EU from
   `IssueQueuePlugin`'s cold-payload store. Elaboration reports it as
   **`IssueQueuePlugin_logic_coldWay0` / `coldWay1 : Mem[64 x 479 bits].readAsync`,
   `ram_style = "distributed"`** — ~61 kbit of LUTRAM across two banks, read
   **combinationally** in the issue path by five select ports, and SpinalHDL warns it
   "can only be write first into Verilog". A synthesis/timing/placement fault on that structure would be invisible
   in every RTL simulation, deterministic for a given schedule, and would corrupt an
   operand while leaving scheduling (wakeups, ordering, retirement) perfect — which is
   exactly the observed symptom shape. **This is not established**; it is the family
   that survives, and it is testable with an ILA capture of `psrcAValid`/`psrcA`/
   `s1Base` on the deployed bitstream.
2. **A state the simulated boot does not reach.** Weakened but not eliminated by SS6.4
   — the routine runs early, but the sim still skips the ROM self-checksum sweep and a
   delay loop before first entry, and the board had been running far longer.
3. **Something outside the CPU that the SoC model does not reproduce** — real DDR
   timing/reordering, L2C behaviour, AXI fabric arbitration. Note this family must
   still explain a *wrong address*, and the store lands at address 0 with the correct
   data, which is upstream of anything the fabric does.

The decisive experiment is a **silicon** capture at the wedging `CLR` of:
`psrcAValid`, `psrcA`, `s1Base`, a ROB flush signal (to test SS2.5's assumption
directly rather than by three indirect corroborations), and the store µop's
operand-ready/wakeup signal (so a false `psrcAValid` can be told apart as *never became
valid* versus *was valid and got cleared* — those point at different RTL).
`build/wedge_hunt/pipe_trace_clrloop.txt` is the correct-behaviour baseline to diff
against.

---

## 7. Explicitly NOT claimed

* **The wedge is not fixed and the RTL site is not pinned.** SS2.4 narrows it to a
  one-bit operand-delivery signal and its delivery path; it does not identify the
  fault.
* **No hardware measurement was made in this session.** Every "correct" result above
  is a statement about the **simulation**. The decisive experiment — reading
  `psrcAValid` / `psrcA` / `s1Base` (plus a flush signal and the store µop's
  operand-ready signal, so a false `psrcAValid` can be told apart as *never became
  valid* versus *was valid and got cleared*) on the deployed bitstream — has been
  scoped to a separate session and had not completed when this was written.
* Not claimed that the 30 passing `p127` cases prove the core correct in this posture.
  They prove the *tested shapes* are correct with a board-scale deferral window, and
  the board disagrees, so something they do not reproduce is load-bearing.
* The SS8 fixes are **not** claimed to affect the wedge. The machine is retiring, and
  both defects require a flush landing in a one-cycle window that the wedged loop
  (`mispredict=0` on every entry) does not produce.
* No FMax claim. Per standing instruction the OOC gate has a measured 0.919 ns noise
  floor and was not run; the RTL delta here is one extra `+1` term on a flush-cycle
  pointer assignment, one 8-bit `Reg` vector, and two combinational outputs.

---

## 7b. Verification bar for the SS5 fixes

| gate | result |
|---|---|
| `sbt fastTest` (non-Verilator, non-slow) | **336 passed / 1 failed / 2 ignored**, 214 suites. The single failure is `RobPluginSpec`'s *"preciseDrainBusyIn holds a debugPcApply off an in-flight precise drain"* — the **known pre-existing flake**. Confirmed independent of this change two ways, one empirical and one structural. **Empirical:** re-run on the **unmodified-RTL baseline worktree** four consecutive identical invocations gave **2 PASS / 2 FAIL** — a ~50 % flake rate on RTL this Part does not touch. It is seed-dependent (each run prints its own `Start SimpleDut test simulation with seed ...`), and SpinalHDL randomises uninitialised `Reg`s per simulation seed. **Structural:** `RobPluginSpec`'s `SimpleDut` instantiates `RenameUopSourcePlugin`, `RobAllocDriverPlugin`, `RobPlugin`, `RenameCommitSinkPlugin`, `CommitTraceSinkPlugin`, `CacheControlSinkPlugin`, `DebugCommitSinkPlugin` — **no `StoreQueue`, no `LsEuPlugin`**, and `preciseDrainBusyIn` is a directly poked input there. Those two files are the only RTL this Part touches, so that DUT's netlist is byte-identical before and after. |
| `StoreQueueSpec` "Part 127 (b1)" | **FAILS on unmodified RTL** (baseline worktree), passes with the fix |
| `StoreQueueSpec` "Part 127 (b2)" | passes |
| `LsEuFastPreciseSpec` "Part 127" | **FAILS on unmodified RTL** (`push=0 ready=0, expected exactly one outstanding`), passes with the fix |
| `ExecuteLockStepSpec` (full suite — 507 cases: p124 / p126 / the 34 new p127, the mispredict and RAS-recovery cases, the whole ISA corpus) | **505 passed / 2 failed** (after correcting the control ceiling, which was the third). **Both** remaining failures reproduce on the **unmodified-RTL baseline worktree**, i.e. they are pre-existing on `fmax-closure-fanout` and are **not** regressions. See the note below. |
| `p127 CONTROL` × 4 (the latency positive control) | **4 / 4**, windows 9 / 13 / 72 / 130 cycles |
| `BsrFlushSkipSpec` | **17 / 17** |
| `RobPluginSpec` | 44 run, 1 failed — the flake row above |
| ported `flush_younger_bsr_reexec` / `_bsr_exc_reexec` / `_bsr_tree_reexec` / `_rts_bsr_reexec` | **4 / 4** |
| 200-seed fuzz sweep (`FUZZ_SEED_START=0 FUZZ_SEED_COUNT=200`) | **200 seeds, 3 divergences, 0 generator failures — seeds 80, 109, 127.** Exactly the pre-existing set; the count did **not** rise. |
| `GenFullCoreSynthVerilog` (synthesizable elaboration) | clean, 20.4 MB netlist emitted |

**Two PRE-EXISTING `ExecuteLockStepSpec` failures on this branch**, found while
running this bar and confirmed on unmodified RTL at the branch point (`afbabdd`).
Neither is caused by this Part; both are reported here because the branch is not
green and nobody appears to have recorded them:

* *"lock-step: mispredicted branch with a wrong-path UNMATCHED bsr does not leak a
  phantom RAS entry — rollback-on-flush fix"* — `got count=1`, i.e. the phantom RAS
  entry the test's own name says was fixed **is leaking again**.
* *"lock-step: CMP2.W (d8,An,Xn) indexed bounds pointer"* —
  `Divergence(7, pc: dut=0x6a19e51c oracle=0x40800020)`, a wild committed PC.
| FMax | **deliberately not gated.** Per standing instruction the OOC gate has a measured 0.919 ns noise floor, three times the deltas read off it, and two prior OOC "regressions" did not survive postroute. The RTL delta here is one extra `+1` term on a flush-cycle pointer assignment, one 8-entry 1-bit `Reg` vector, and two combinational outputs. |

> Every "fails on unmodified RTL" above was verified in a **separate baseline
> worktree** at the branch point, with only the new test file copied in and any
> assertion naming a new signal removed — so the failure is the defect, not a
> compile error.

---

## 8. Instrument traps found this session (the ninth instance of tonight's dominant pattern)

Both of these are instruments that **report nothing while appearing to work**. They
are recorded here, not only in a file under `build/`, because `build/` gets swept.

1. **`+arch_dump_at_pc`, `+poke_word_at_pc`, `+halt_on_vec` and every `dbg_boundary_*`
   detector are DEAD on the `CPU=m68k040` binary.**
   `tb/tb_fpga_top_rom.cpp:61-73` makes `CPUI_REF` / `DBGT_REF` a `DeadSink` unless
   `CPU_M68K` is defined, and `Makefile:2433` gives `CPU=m68k040` only
   `-DCPU_M68K040`. The gate at `tb/tb_fpga_top_rom.cpp:4112-4113` is therefore
   permanently false. No error, no warning — just an empty log that reads exactly like
   a negative result.
   **Confirmed WORKING on this build:** `+pc_dump_path`, `+watch_pa`, `+pipe_trace_*`,
   `+sq_trace_cycles`.
2. **`+rom_patch` is INERT on `+load_checkpoint`.** `preload_rom()` is cold-boot-only
   (`tb/tb_fpga_top_rom.cpp:3186` versus the resume path at `:3239-3246`), so a
   checkpoint resume runs the *unpatched* ROM while the command line says otherwise.

Both should be copied into a comment block next to the plusarg parsing in
`tb/tb_fpga_top_rom.cpp` so the next session hits them before relying on those flags.
Companion artefacts (under `macqd700-soc-worktrees/m68k040ooo-integration/build/`, and
therefore sweepable): `wedge_hunt/RESULTS.md`, `wedge_hunt/INSTRUMENT-TRAPS.md`,
`wedge_hunt/pipe_trace_clrloop.txt` (the 401-cycle correct-behaviour baseline a silicon
capture should be diffed against), `wedge_hunt/pipe_trace.txt` (64 MB),
`wedge_hunt_chimeonly/pcdump.txt` (the decisive `chime-skip`-only control), the
`pcdump*.txt` files, and six `run_*.sh` scripts.

---

## 9. Reusable assets that outlive this bug

* **`runLockStep(dcfg = ...)`** — the corpus can now be run against a realistic D-side
  AXI timing. "The corpus had never run this posture" was itself a finding tonight
  (Part 126 for `CACR.DE = 0`, this Part for the latency), and the same axis is what
  made the SS8(a) race reachable in a directed test at all.
* **`L2Sweeps.storeSlow` / `storeVerySlow`**, and the recorded fact that `dramCycles`
  does not reach the write engine.
* **`StoreQueue.io.flushKeptPrecise` / `io.sqCompletionOrphan`** — the SQ now *tells*
  its lock-step partner what it did on a flush, instead of the partner re-deriving it.
  Any future consumer of the `pendMem` ring should use these rather than re-implement
  the keep rule.
* **The boot-order fact** (RAM diagnostics run *after* `0x4084BEA8`). Anyone reasoning
  about what residue precedes an early-boot ROM routine will otherwise make the same
  wrong assumption.
