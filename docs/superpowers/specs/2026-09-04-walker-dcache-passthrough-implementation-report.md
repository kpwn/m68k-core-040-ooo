# Walker → D-cache passthrough — implementation report

**Status:** IMPLEMENTED. RTL landed, directed acceptance test fails-before / passes-after,
regression sweeps reconciled. Synth gate queued behind a committed launcher (§8).
**Date:** 2026-09-04
**Branch:** `feat/walker-dcache-passthrough`, worktree
`/home/qwertyoruiop/m68k-core-040-ooo-worktrees/walker-dcache`
**Parent:** `bb3bca1` on `fmax-closure-fanout`. **Not merged.**

**Source documents.** `2026-08-18-walker-dcache-passthrough-design.md` (W1–W30),
`2026-08-18-walker-dcache-passthrough-implementation-plan.md` (19 tasks),
`2026-09-03-walker-dcache-routing-revalidation-and-itlb-prefetch-design.md` (R1–R13,
Stage A amendments), `2026-09-04-naxriscv-walker-comparison-round2.md`,
`2026-09-04-naxriscv-architecture-comparison.md`.

---

## 0. What landed

Three commits, each keeping the tree green:

| Commit | Contents |
|---|---|
| `486744f` | A regression this change introduced, caught by `ExceptionUnit`'s own assertion and fixed: the sequencer's untagged store ack demultiplexed away from walker U/M stores (§5.4). Plus two hardening items from the same audit. |
| `63d018a` | Reconciling the migrated LS/MMU specs. |
| `a0e77a6` | The acceptance test. |
| `8a6a431` | The RTL: `TableWalker` reads via `DcacheService`, the U/M writeback as one `DStoreCmd`, the four-source arbitration layer in `LsEuPlugin`, `quiesceHold`, W26's wedge report, `walkerAxi` deleted, `AxiDMerge`'s two walker owners tied off, port golden re-baselined, 28 test files migrated + a new sim agent. |

Plus, on its own branch off the sibling benchmark work, `bench/walk-kernel-premise-fix`
(`91eee43`) — §5.5.

---

## 1. The defect, and why it was not already covered

Both directions of the walker ↔ copyback-L1D coherency gap are real and reachable:

- **Stale read.** A page-table entry the supervisor wrote with an ordinary `move.l` sits
  DIRTY in L1D and is invisible in backing memory until that line is evicted. A walker
  reading physical memory behind the cache's back reads the OLD descriptor and installs a
  wrong translation.
- **Lost update.** The walker's U/M descriptor writeback went only to memory, while L1D
  still held a dirty copy of that line without the update; the later eviction wrote the
  whole line back and silently discarded it. **Under-setting M means a dirty page is
  later evicted as clean — silent data loss.** The failure is asymmetric: over-setting M
  costs one unnecessary writeback, losing an M loses data.

The gap was armed structurally and prevented only by a **global** default: `CACR = 0` out
of reset makes every data access INHIBITED, so L1D holds nothing. That is a cache-disable
that exists by reset accident, not a protection, and it is precisely the bit any real
supervisor turns on.

**Two traps that make the "obvious" test useless**, both confirmed by construction here:

1. `mmu_atc_write_hit_sets_modified`, which the 2026-08-18 plan nominated as the
   acceptance test, **passes at HEAD and passes *because* `DE = 0`** — all its accesses,
   including its own descriptor reads through DTT0, fold to INHIBITED, so the D-cache
   never holds the descriptor line and the question is moot in its posture. It is
   structurally incapable of showing this defect in either direction.
2. The existing `ForceCacheableCopyback` posture covers the whole 4 GiB in TTRs. A TTR hit
   is resolved *before* the TLB is consulted, so **no table walk ever happens under it**.
   A posture that transparently maps everything cannot exhibit a walker bug.

The posture that does work is in §4.

---

## 2. Mechanism

### 2.1 Reads

`TableWalker` drops `io.axi`, `axiCfg` and `selectWord`, and drives a `DcacheService`
client pair instead. A descriptor is a naturally-aligned longword at every level
(`base + idx*4` off an aligned table base), so the read is a plain `Size.LONG` load and the
D-cache's own `DcacheByteLane.extract` performs the big-endian lane select. Deleting
`selectWord` removes a hand copy of that exact function whose divergence had already
produced one silent corruption (task #194 — descriptors read byte-reversed relative to
what a real `move.l` wrote).

`loadRsp.fault` now **terminates** the walk. The retired dedicated port never checked
`rresp` at all and would decode a translation out of error data; this is the fail-closed
replacement for its AXI-id guard (D19).

### 2.2 Writes

The U/M drain's AW/W/B handshake FSM in both TLB plugins collapses to a single
`DStoreCmd` with `useStrb = True`, `strb = drainStrb`, `lineData = drainBeat`,
`precise = False`, plus its terminal ack. That is exactly the shape the SQ already uses to
drain a split store whose byte count is not a clean 1/2/4 `Size`.

Verified against `DcachePlugin`'s store pipe: `dirtysWrEn` is set **only** under
`stS3Copyback`, so a WRITETHROUGH walker store onto a resident DIRTY line writes the array
and leaves the dirty bit set. The program store's data and the U/M byte both survive the
eventual eviction. That is why W1's WRITETHROUGH is the right choice and not merely
"cacheable" (§2.4).

### 2.3 Arbitration

`LsEuPlugin`'s existing two-source exception override mux becomes four-source.
**`DcachePlugin.scala` receives zero edits**, gains no port and never learns walkers exist.

- **Ownership** is per-direction and **registered** (`ldOwner`, `stOwner`, three-valued
  `{CORE, ITLB, DTLB}`). The mux select, the `valid` gate and every owner qualification
  read the same register on the same cycle, so there is no one-cycle gap between "who the
  mux selected" and "who the qualification thinks it selected".
- **Load responses** are identified by a **depth-8 ownership-tag FIFO** carrying a
  four-valued tag `{CORE_LS, CORE_EXC, ITLB, DTLB}`, pushed on every accepted `loadCmd`
  and popped in lockstep with every `loadRsp`. Depth 8 is derived, not rounded: the
  aligned ring is 4 deep, a walker read can be outstanding concurrently with all four, so
  5 is reachable. The tag is encoded **combinationally from the four admission predicates
  that gate the mux legs**, never read from a separately-clocked owner register.
- **Priority** is `CORE-EXC > CORE-LS > walkers`, i.e. today's behaviour exactly whenever
  no walker is pending, with a per-walker aging counter (`walkerAgeLimit = 64`) whose
  `force` bit outranks CORE-LS but never the exception sequencer's presented command, and
  a 1-bit round-robin between the two walkers.
- A load grant covers **one command** and is released as soon as that command is accepted.
- The **store** direction stays drain-to-zero: `storeAck` is a single untagged terminal
  pulse and `StoreQueue` asserts on a stray one, so identifying a store response
  positionally would need the same FIFO on a direction with no throughput case for it.

### 2.4 Cacheability (W1/W2/W3)

`CACR.DE ? WRITETHROUGH : INHIBITED`, stamped by the mux, identical for the read and write
halves, never derived from an address and never carried on the walk request.

- It cannot come from a descriptor: that is circular, the descriptor fetch being the thing
  that resolves attributes. Real 68040 table searches are physical-only for the same
  reason. This does **not** violate the standing no-SoC-address-map rule — there is no
  page attribute in existence to consult, for anyone, ever. The *mapped page's* cache mode
  is still read from its descriptor (`TableWalker`'s `rCmode`) and is untouched.
- WRITETHROUGH rather than merely "cacheable": under WRITETHROUGH the U/M update lands in
  both the array and memory in all three residency states with no dependence on the
  eviction path. COPYBACK would be correct but trades a proof for a dependency on exactly
  the eviction path that created the bug.
- INHIBITED under `DE = 0` because the whole D-cache is off then; an INHIBITED store never
  touches the array, so the array cannot go stale. **An INHIBITED region is honoured**:
  both halves of the walk take the D-cache's INHIBITED path, which bypasses the array
  entirely, and the acceptance test exercises the `DE = 1` posture while every other MMU
  spec in the tree exercises `DE = 0`.

### 2.5 The `UmWriteQueue` path

Unchanged in shape and deliberately so. `walker.io.done` still gates
`umq.io.alloc.valid` with the same `!walkUmPoison && !walkFlushPoison && !flushAll`
conjunction; `robId` tagging, commit marking and flush discipline are byte-identical. Only
the **drain transport** changed — `drainAwDone`/`drainWDone` become
`drainArmed`/`drainAckWait`, and `umq.io.drainAck` moves from the AXI `b` handshake to the
D-cache's terminal `storeAck`. The queue still holds the entry until that ack, so it is
still single-outstanding and still pops exactly once.

`mmu-atc-m-bit-tracking` (`c629bec`) was **not** pulled in, per the standing instruction
and per R5: it is subsumed by `e0d2752`, which is already an ancestor.

### 2.6 W19 — the maintenance quiesce

`ExceptionUnit` exports `quiesceHoldOut` over `S_DRAIN || S_APPLY`, wired to
`LsEuPlugin.quiesceHold`, closing **walker** admission on both directions (never CORE's).
`S_DRAIN`'s own written deadlock proof ends *"With the LS EU flushed, nothing re-arms
them"* — a table walker on the same ports makes that sentence false. `S_APPLY` is included
because `maintCmdOut` pulses there while the D-cache's `maintBusyReg` only rises a cycle
later, leaving a one-cycle fully-open window. A sim tripwire asserts no walker grant is
ever issued while `quiesceHold`.

### 2.7 W26 — the wedge report

A wedge at this new merge point raises **no AXI grant**, so it is invisible to the
`axi_d` arbiter's bounded-grant watchdog, to the D-cache's diagnostic channel and to the
halt-reason channel. A sticky `walkerPortWedge` (default bound 4096 cycles of held grant
with no progress on any channel) is folded into `FullCoreSynth`'s halt drive as
`HaltReason.WALKER_PORT_WEDGE = 5`.

---

## 3. The deadlock argument

**There is no circular translation dependency, and the reason is structural rather than
argued.** `DcachePlugin` contains zero references to `TranslationService`,
`DTranslationService` or `MmuControlService` — re-verified by grep at this HEAD, count 0.
It never invokes the MMU and operates purely on a pre-translated `paddr` supplied by its
caller. A table search computes its descriptor addresses arithmetically from the root
pointer and VA index slices, so **a walk's D-cache access requires no translation**. The
dependency graph is `access → walk → cache`: a chain, not a cycle.

NaxRiscv reaches the same conclusion by a stronger route — its cache load pipe cannot
back-pressure at all (`io.load.cmd.ready := True`; everything that would need a hold is
answered as `REDO`), and the access that triggered a walk *aborts* its in-flight cache
access rather than holding it. We cannot copy either property, but we do not need to: the
first link of the feared cycle does not exist for us either.

Four bounded waits are created, and each is closed at its own site:

1. **The early-VIPT probe hand-over (W11) — the one genuine deadlock this design
   creates.** `DcachePlugin`'s `loadCmdPort.ready` carries
   `(!earlyProbeTokenPresent || earlyProbeOwnsCmd)`: a resident probe token blocks any load
   command that does not own it. Without a fix: the mux hands the port to a walker and
   deasserts CORE's `loadCmd.valid`; `loadProbePort.ready` is gated on
   `(!loadCmdPort.valid || useEarlyProbe)`, so with CORE's valid low the LS EU keeps
   launching probes; all four slots fill with tokens whose matching load commands the mux
   will never let through; the walker's command matches none of them, so
   `loadCmdPort.ready` is false **forever**. The fix folds `walkerOwnsLoad` into the same
   `probeCancelAll` term the design already uses for the exception hand-over, and into the
   same `!excActive` conjunctions that suppress new probe launches. Cancelling a probe is
   functionally free — an absent qualification simply makes the later resolved command take
   the ordinary path. **This is the design's own goal with no outside precedent**:
   NaxRiscv is immune to the entire class because it has no allocating structure that
   outlives one pass through the port, and our probe token array is exactly such a
   structure.
2. **The maintenance quiesce.** §2.6. Command-granularity; a walk already in flight simply
   stalls between descriptor reads. The maintenance walk depends on nothing the walker
   holds and completes autonomously, so the graph is `walker → maintenance`, never the
   reverse.
3. **Exception-sequencer starvation.** `excLoadAdmit` requires `ldOwner === CORE` and an
   empty FIFO, so an exception load waits for at most one in-flight walker command plus
   the FIFO to drain; `alignedSendValid` already carries `!excActive`, so no new CORE-LS
   command can be admitted meanwhile and the FIFO can only shrink.
4. **Walker starvation.** Bounded by the aging counter at `walkerAgeLimit` cycles. CORE-LS
   cannot starve either: a walker requests only while it has an un-issued descriptor read
   — at most one command per memory round trip — and its grant is released on acceptance.

**The ID-namespace precedent this is measured against** (task #240's `l2c_ctrl.v`
`pipe_id_haz_c` fetch/LSU starvation) is the *dissolved* case here rather than the
mitigated one: `AxiIds.scala`'s own header records that the DTLB and ITLB walkers both
emit AR id 2, "harmless only because they sit on separate physical masters today — and it
becomes a hard bug the instant those masters are folded". With no walker master at all
there is no ID collision to separate by owner latch. The remaining shared resource — the
D-cache load port — is arbitrated by a registered owner and identified by an explicit
per-client tag, which is the same shape as the fix that closed `pipe_id_haz_c` (widen the
tag with a source qualifier), applied before the fact instead of after it.

**Four sim tripwires** machine-check the invariants every cycle rather than asking a reader
to trust the derivation: at most one load client admitted per cycle (the FIFO push tag is a
well-defined one-hot); at most one walker admitted to the store port; no `loadRsp` while
the FIFO is empty (push/pop lockstep intact); no walker store outstanding alongside an
exception or CORE store; and no walker grant during `quiesceHold`.

---

## 4. The acceptance test — fail before, pass after

`src/test/scala/m68k040/mmu/WalkerDescriptorCoherencySpec.scala`, two tests, an
`LsEuPlugin` + `DcachePlugin` + `DtlbPlugin` + `MmuControlPlugin` cluster DUT.

**Posture.** `CACR.DE = 1`; `DTT0 = 0x0000E020` — base `0x00`, mask `0x00` (exact match on
`VA[31:24]`), `E = 1`, `S = 11`, **`CM = 01` COPYBACK** — covering only the 16 MiB block
holding the page tables (`0x00010000`/`0x00011000`/`0x00012000`); the translated VA
`0x40000000` in a different 16 MiB block covered by no TTR, so it is genuinely walked;
`TC.E = 1`.

Both tests establish **non-vacuity in both halves** before asserting: the descriptor store
must be *in* the array (an ordinary load reads back the new value) and *not* in memory (the
raw image still holds the old one).

### Measured at `bb3bca1` (pre-change), verbatim

The walker's own AXI master was attached to the **same** backing image the D-cache uses
(`sharedMem = mem.mem`), so nothing below is a missing-memory artefact; descriptor reads
were detected on `walkerAxi.ar` (line-aligned, hence the `leaf & ~0xf` compare). Both
non-vacuity assertions passed.

```
[info] - a table walk observes a page-table entry that is still dirty in L1D *** FAILED ***
[info]   resolved.forall(((x$5: Long) => x$5.==(WalkerDescriptorCoherencySpec.this.PpnNew)))
          was false the walk installed a STALE translation: got PPN(s) Vector(0x00801),
          expected 0x00901. The descriptor rewritten at 0x00012000 was still dirty in L1D
          and the walk read the pre-store image out of backing memory.
          (WalkerDescriptorCoherencySpec.scala:380)
[info] - a table walk's U/M descriptor update is visible to the D-cache *** FAILED ***
[info]   0 did not equal 24 the walk's U/M update is invisible to the D-cache: read back
          0x00801001 from 0x00012000, expected U (bit 3) and M (bit 4) both set. The array
          still holds the pre-walk image and its eviction would discard the update.
          (WalkerDescriptorCoherencySpec.scala:436)
[info] Tests: succeeded 0, failed 2, canceled 0, ignored 0, pending 0
```

### Measured on this branch

```
[info] - a table walk observes a page-table entry that is still dirty in L1D
[info] - a table walk's U/M descriptor update is visible to the D-cache
[info] Tests: succeeded 2, failed 0, canceled 0, ignored 0, pending 0
```

**A harness trap this cost a false negative on, recorded in the test:** `sq.io.commit` is a
one-cycle `Flow` that only marks an **already-allocated** entry, so pulsing it before the
store allocates loses the pulse **and** leaves `sq.io.empty` trivially true — a naive
"wait for the SQ to drain" returns instantly and the test proceeds with the descriptor
never written at all, failing on both implementations for a reason unrelated to the bug.
`waitSqAlloc` exists for this and says so.

---

## 5. Verification

Every number is from this session, against a pristine `bb3bca1` baseline run in the same
session. Nothing is inherited from a quoted figure.

| Sweep | Baseline `bb3bca1` | This branch (`486744f`) | Δ |
|---|---|---|---|
| `make test-fast` (= `sbt fastTest`) | 341 run, **341 pass, 0 fail**, 2 ignored, 215 suites | 339 run, **338 pass, 1 fail**, 2 ignored, 215 suites | −2 tests, 1 known flake |
| `m68k040.mmu.* m68k040.ls.* m68k040.cache.*` | 328 run, 317 pass, **11 fail**, 45 suites | 330 run, 319 pass, **11 fail**, 46 suites | +2 tests, **failure set byte-identical** |
| `ExecuteLockStepSpec` (run separately — it is excluded from `test-fast`) | full run not taken; the one test that mattered checked individually (§5.4) | **505 pass, 0 fail**, 1 ignored | fully green, hence no worse than any baseline |
| 200-seed fuzz sweep (`tools/fuzz/sweep.sh 0 200 25 20`) | 3 (seeds 80, 109, 127) | **3 — seeds 80, 109, 127** | **unchanged; the bar was "must not rise"** |
| `WalkerDescriptorCoherencySpec` | 0/2 by construction | **2/2** | the acceptance gate |

### 5.0 Netlist and port-surface checks

- `M68kFullCoreSynth` elaborates; `tools/socket/check_socket_netlist.py` is **green**
  against the re-baselined golden (§6, the one intended port-surface change).
- `M68kSocketTop` also elaborates — worth checking explicitly because
  `AxiDMergePlugin`'s two walker slave ports are now tied idle rather than connected.
  Its checker reports **exactly one** failure, `D23: the socket top exports ONLY
  cpu_socket.vh ports`, listing 30 `dbg040_*` debug probes. Generating the same netlist on
  the pristine `bb3bca1` worktree and running the same checker produces the
  **byte-identical** list. Pre-existing, untouched, and **no `itlbAxi_*`/`dtlbAxi_*` port
  appears in it** — which is the intended result reaching the socket boundary too.

### 5.1 `test-fast`, reconciled arithmetically

The test-name diff between the two logs is exactly two entries removed and none added:

```
--- only in baseline ---
[info] - RESET_VEC is a distinct ID outside every live D-side ARID
[info] - TableWalker does not accept an R beat carrying a foreign ID
--- only in this branch ---
(empty)
```

Those are `socket/WalkerIdGuardSpec`, deleted per W20 because its subject — the walker's
AXI master and its AR-id guard — ceases to exist. 341 − 2 = 339. ✓

The two new coherency tests are **not** in `test-fast`: they carry the `VerilatorTest`
tag, consistent with every other spec in the MMU cluster, and `fastTest` excludes that
tag. They run in the `m68k040.mmu.*` sweep, which is why that sweep is 330 rather than 328.

**The one `test-fast` failure is `RobPluginSpec`'s `debugPcApply`** — one of the two
seed-dependent flakes named in the brief. *(A third one exists: see §5.3.)*

### 5.2 `mmu`/`ls`/`cache`, reconciled by name

`comm` over the sorted failing-test names is **empty in both directions**: the 11 failures
are the same 11. Getting there took two rounds and both are recorded rather than smoothed
over —

- the first post-change run showed **42** failures. 31 of them were one real defect in the
  new sim helper (`WalkerDcacheSimIo` constructed in the DUT `Component` body, where the
  TLB plugin's `during setup` has not run yet, so `c.walkLoadCmd` was null in eight DUTs).
  Making it a `FiberPlugin` fixed all 31 at once;
- the remaining **2** were genuine test-migration items (`DtlbViptChangedVpnSpec`'s
  `faultCacheCmds` and `DtlbFlushReuseSpec`'s `cmdEvents`/`cancelAllCycles`/`dataMem` AR),
  each re-qualified by the walker's reserved load token so the assertion keeps its old
  meaning rather than being loosened.

### 5.3 A retraction

An earlier reading of these runs suspected `RobPluginSpec`'s *"sustained free-loop: real
rename→rob keeps flowing past 48 allocations"* as a regression: it failed four times in a
row on this branch. It is **not** a regression. Its DUT (`E2EDut`) hosts no `LsEuPlugin`,
no `DcachePlugin` and no `DtlbPlugin`, and `git diff bb3bca1 -- src/main/scala/m68k040/rob
src/main/scala/m68k040/rename src/test/scala/m68k040/rob` is empty. Run six times in
isolation on the **untouched `bb3bca1` worktree**:

```
pass, pass, FAIL (11 of 120 allocated), pass, FAIL (33 of 120), FAIL (18 of 120)
```

3 failures in 6 runs on the baseline with no changes at all. The four-in-a-row streak was
unseeded-random bad luck. This is a **third** seed-dependent `RobPluginSpec` flake,
alongside `debugPcApply`.

### 5.4 One real regression, introduced and fixed

`ExecuteLockStepSpec` caught it, through `ExceptionUnit`'s own assertion:

```
FAILURE ExceptionUnit: attempted to overwrite an unaccepted frame-store command
[info] - lock-step: MOVEM.L D0-D3,-(An) STORE faults on the 3rd store (D1) ... *** FAILED ***
```

`ExceptionUnit.dcStoreAck` is wired straight off `DcacheService.storeAck` by every
integrated DUT, unqualified. With two store clients that was safe — the sequencer only
stores after `E_DRAIN` has waited on `sqDrained`, so no SQ store can be outstanding to ack
in its place. **A table walker is a third store client and breaks that**, and W13's SQ-side
demux did not cover it. It is also not enough that `stGrantOk` refuses a *new* grant while
the sequencer has a store presented: a walker store granted earlier can still be
outstanding when the sequencer reaches `E_STORE`, and its ack then advances `E_STWAIT`
while `stoValidReg` is still set.

Measured on the same single test:

| | result |
|---|---|
| `bb3bca1` baseline | **PASS** |
| this branch, before the fix | **FAILED** — the assertion above |
| this branch, after the fix | **PASS** |

`LsEuPlugin` now exposes `logic.excStoreAckOut = storeAck && !walkStOutstanding` and the
four integrated DUTs consume that. Deliberately not also qualified against the SQ's own
acks — that is pre-existing behaviour protected by the sequencer's `sqDrained` discipline.

Two hardening items from the same audit went in alongside, both of which were latent
false-halt / needless-stall risks rather than observed failures:

- **`quiesceHold` now also covers `S_MAINTWAIT`**, not just `S_DRAIN`/`S_APPLY`. The
  D-cache refuses both client directions for the whole maintenance walk, which is 512
  iterations each of which may write a dirty victim back at DDR latency; a walker granted
  a port just before the walk started would hold that grant, making no progress on any
  channel, for tens of thousands of cycles.
- **W26's wedge bound goes 4096 → 2²⁰ cycles** for the same reason: 4096 sits *inside* the
  duration of a legitimate maintenance walk, so it could have raised a **false** sticky
  core halt. A false halt is far worse than a late report.

### 5.5 The sibling benchmark's bimodal walk measurement — chased, and it is the kernel

The latency-microbenchmark suite recorded `{0.00, 0.00, 567.25, 556.43, 0.00}` for its
real-walk half, correctly declined to average it, and flagged *"a run whose end point is
independent of chain length points at the MMU-on / real-page-table configuration
terminating on something other than the chase — a wedge, or a fault path"* as a possible
defect in exactly this walker.

**It is a kernel premise violation, not a walker defect, and the evidence is direct.**

`chaseStep` is `move.l (%a0),%d1 ; adda.l %d1,%a0 ; adda.l #STRIDE,%a0` and only strides by
`STRIDE` if the loaded value is **zero**. `kTlbChase.prepMem` wrote the page *tables* and
never wrote the *data* pages — and an untouched `SparseMemory` page is PRNG-filled, not
zeroed. So `%d1` was garbage, `a0` jumped somewhere arbitrary, and under `mmuWalkD`
(deliberately no D-side TTR) that arbitrary address is unmapped and takes a translation
fault. The short and long kernels share a byte-identical prefix, so they derailed at the
**same** step and produced the **same** window — a differential of exactly 0.00. The
`mmuNoWalk` control never faults, because its match-all TTR translates anything, which is
precisely why only the walk half looked unstable.

Measured on **unmodified `bb3bca1` + the bench suite**, five seeds, via a new `DIAG` test:

```
---- walk, RAW (data pages never written -> PRNG-filled) ----
  seed=00006d68  short: INCOMPLETE 66/174    long: INCOMPLETE 66/354
  seed=00008c57  short: INCOMPLETE 65/174    long: INCOMPLETE 65/354
  seed=0000ab46  short: INCOMPLETE 158/174   long: INCOMPLETE 158/354
  seed=0000ca35  short: INCOMPLETE 158/174   long: INCOMPLETE 158/354
  seed=0000e924  short: INCOMPLETE 69/174    long: INCOMPLETE 69/354
---- walk, FIXED (data pages zeroed) ----
  seed=00006d68  short: 176/174 win=1983   long: 356/354 win=4065   perStep=34.70
  seed=00008c57  short: 176/174 win=2070   long: 356/354 win=4158   perStep=34.80
  seed=0000ab46  short: 176/174 win=2025   long: 356/354 win=4121   perStep=34.93
  seed=0000ca35  short: 176/174 win=2027   long: 356/354 win=4106   perStep=34.65
  seed=0000e924  short: 176/174 win=1969   long: 356/354 win=4014   perStep=34.08
```

The walk kernel never completed on **any** seed, and short and long stopped at the
identical macro count — which *is* the 0.00 signature. With the premise made true it
completes on every seed and the differential is stable. The fix is one `prepMem` addition,
committed as `bench/walk-kernel-premise-fix` (`91eee43`) off that suite's own branch so it
can be taken or left independently.

**So §4.1's "NOT MEASURED" can be closed, and a real before/after exists.** Same harness,
same seeds, both memory models. The choice of memory model turns out to be the whole story,
which is exactly why the suite's own acceptance bar says a zero-latency reading is
disqualifying for a memory-side claim.

**Zero-latency memory model** (5 seeds) — memory is free, so the entire mechanism the
design rests on is worth nothing here by construction:

| | baseline `bb3bca1` | walker → D-cache | Δ |
|---|---|---|---|
| page-stride access, TTR (no walk) | 19.010 ± 0.260 | 18.913 ± 0.076 | −0.10 (noise) |
| page-stride access, real walk | 34.633 ± 0.292 | 40.420 ± 0.092 | +5.79 |
| **DTLB miss: 3-level walk cost** | **15.623 ± 0.379** | **21.507 ± 0.050** | **+5.88 cyc (+37.7 %)** |

**L2-faithful memory model** (`IPC_MEM=l2`: L2 hit 5 cyc, DDR 70 cyc, 64 B line; 3 seeds) —
the regime any real system is in:

| | baseline `bb3bca1` | walker → D-cache | Δ |
|---|---|---|---|
| page-stride access, TTR (no walk) | 97.094 ± 0.143 | 96.978 ± 0.147 | −0.12 (noise) |
| page-stride access, real walk | 160.622 ± 0.157 | 161.322 ± 0.282 | +0.70 |
| **DTLB miss: 3-level walk cost** | **63.528 ± 0.263** | **64.344 ± 0.173** | **+0.82 cyc (+1.3 %)** |

**The penalty collapses from +5.88 cycles (+37.7 %) to +0.82 cycles (+1.3 %) the moment
memory costs anything.** That is the design's own predicted mechanism appearing where it
should: this kernel's root and pointer descriptors are the *same two lines* on every
access, so once routed through L1D they hit instead of paying memory latency, and that
recovers almost all of the added pipeline overhead. The zero-latency figure is the pure
overhead half of the trade with the payoff half switched off.

**Still not a speedup, and none is claimed.** Even under L2-faithful memory this kernel is
a worst case for the feature — it deliberately evicts the DTLB set on every access, so
every access walks, and the leaf descriptors stride 32 bytes apart so they are cold every
time. What it establishes is the honest bound: **the cost of the correctness fix, in the
realistic regime, is about 1 % of a walk.** The revalidation's §6.3 plan —
`walkL1dHitRate` as the headline, on a boot trace rather than a microkernel — is still the
measurement that would show an actual win, and is now much cheaper to run given the
harness fix.

### Not run, and named as such

- **A full baseline `ExecuteLockStepSpec` run.** Only the single test that regressed was
  run on the baseline (§5.4). The branch is *fully* green (505/505), so it is no worse
  than any baseline by construction, but the brief mentions a "known pre-existing pair"
  in that suite and this session did not reproduce or characterise it.
- **The postroute synth gate result.** §8 — launched, queueing on the mutex behind another
  agent's JVMs at the time of writing. This is the one gate this work has not passed.
- **Ported-corpus sweep.** Outside the stated scope.
- **Any boot-impact claim.** None is made. Nothing here ran on hardware; the SD card, the
  JTAG lease and the board were not touched.

## 6. Plan tasks: executed, and invalidated by the tree

### Executed

| Plan task | W-items | Status |
|---|---|---|
| 1 (baselines) | — | Done: `test-fast`, `mmu`/`ls`/`cache`, in-session, pristine parent. Synth baseline queued (§8). |
| 2 (`AxiDMerge` shrink) | W20, W21 | **Partial by design.** The two walker owners are tied idle in `AxiDMergePlugin` and `socketMerged` is gone from both TLB plugins; `AxiIds.WALK_READ`/`WALK_WRITE` stay defined and unreferenced with no renumbering. `AxiDMerge.scala` itself is **not** shrunk to 2/1 owners — see §7. |
| 3 (reserved tokens) | W25 | Done, plus the token-layout doc comment rewritten in the same edit. |
| 4–7 (arbitration layer) | W4–W9, W13, W23, W24, W27, W28, W29, W30, W1–W3, W15 | Done. |
| 8 (probe deadlock) | W11 | Done, both halves (cancel + launch suppression). |
| 9 (`quiesceHold`) | W19 | Done, with its own tripwire. |
| 10 (wedge counter) | W26 | Done, incl. `HaltReason.WALKER_PORT_WEDGE` and the `FullCoreSynth` fold. |
| 12 (sim helper) | W18 | Done — `sim/DcacheClientMemAgent` + `WalkerDcacheSimIo`. |
| 13 (`TableWalker`) | W16 | Done, incl. the D19 re-establishment. |
| 14 (TLB plugins) | W15, W17, W20 | Done. |
| 15 (top-level + golden) | W14, §8.1 | Done. **199 → 121 ports.** |
| 16 (remaining test files) | §8.1 | Done — 28 files. |
| 17 (acceptance tests) | §10.1 | Done — §4. |
| 11, 19 (synth checkpoints) | §10.4 | **Queued** — §8. |

### Invalidated or superseded by the current tree

1. **The plan's acceptance test is dead and cannot be re-pointed.** Amendment A2 of the
   revalidation already said so; this run confirms *why* independently — `DE = 0` makes the
   question moot in that test's posture. §1.
2. **W27's split-leg rows are dead code at this HEAD.** The plan devotes Task 5 and a whole
   spec (`WalkerSplitLoadRaceSpec`) to the split leg of `dcache.loadCmd.valid` and the
   `bkFsm`'s two `loadCmd.fire` samplings. At `bb3bca1`, `LsEuPlugin.scala:1026-1032` states
   in-tree that `llReg`/`bkFsm` are **dead infrastructure — "nothing drives `bkStart` any
   more, so `useSplitCmd` (= `bkBusy`) is permanently False"**. Every split load now comes
   from the aligned ring. The rows were implemented anyway (they cost nothing once
   `bkBusy` folds to a constant) but `WalkerSplitLoadRaceSpec` was **not** written: it
   would be a test of unreachable logic. Reported, not forced through.
3. **W13, as specified, is incomplete — it names two store-ack consumers and there are
   three.** The spec qualifies `sq.io.drainAck` (and `StoreQueue`'s stray-ack assertion is
   cited as what makes that mandatory) but says nothing about `ExceptionUnit.dcStoreAck`,
   which every integrated DUT wires straight off `DcacheService.storeAck` with no
   qualification at all. That third consumer is the one that actually broke (§5.4). The
   W-item should be amended to "every consumer of the untagged terminal ack", not two named
   ones — it is the same "the table is the unit of correctness, not any individual finding"
   discipline W27 states for the load side, applied to the store side where the spec did
   not apply it.
4. **The plan's "Plan-level risk" section is stale**, as R7 says: the spec §11.1 item 9
   standalone fix landed as `072e97a`, an ancestor of this parent. Task 4's "lands BEFORE"
   branch applies; W30 was built on top of it and not weakened.
5. **`LsEuPlugin.scala:2027`'s `when(excActive && excLoadCmdValid)` no longer exists** (R6);
   the header is the one-term `when(excLoadCmdValid)`. The W23 qualification is therefore
   applied to the `valid` assignment *inside* that `when` rather than to the header — see
   §7 for why that is also the better FMax placement.
6. **The revalidation's "205 → 127 ports" arithmetic was off by the golden file's own six
   comment lines.** The real count was **199 → 121**. Recorded in the golden's header.
7. **`W18`'s "8 files / 8 DUT classes" undercounts the migration.** 28 test files touched:
   8 needing the new sim agent, 1 deleted, and 19 whose walker memory attachment is simply
   deleted or aliased onto the D-side image.

---

## 7. Where I followed NaxRiscv over the plan, and where not

| Question | Plan / spec | NaxRiscv | What landed, and why |
|---|---|---|---|
| Where the mux lives | W4: extend `LsEuPlugin`'s override mux; `DcachePlugin` zero edits | Inside `DataCachePlugin` via a public `newLoadPort(priority)` | **Plan.** Both are one layer above the raw cache; ours puts the risk in a file we are already editing rather than in a 3266-line cache. Round 2 §7 agrees this is the lower-risk placement. |
| Priority | W8: `CORE-EXC > CORE-LS > walkers` + aging | Fixed priority, **walker above the LSU**, no aging | **Plan.** Round 2 §1 is explicit that NaxRiscv's policy is downstream of its redo-based pipe — a de-prioritised LSU load there is *bounced*, not stalled — so copying the policy without the pipe is not the like-for-like it looks like. Ours preserves today's CORE-LS behaviour exactly when no walker is pending. |
| Response identity | W7: depth-4 ownership FIFO | Positional one-hot `History` shift register | **Plan (with depth 8).** NaxRiscv's static scheme is only available because its load pipe is fixed-latency; ours is shadow-accept and variable-latency. Depth raised 4 → 8 because the reachable bound is 5 (aligned ring 4 + one concurrent walker read), which the plan's depth-4 figure does not cover. |
| Walk cacheability | W1: `CACR.DE ? WT : INHIBITED`, fixed policy | No knob at all — `DataLoadCmd` has no `io`/cacheable field | **Plan, and NaxRiscv confirms the *shape*.** Its cacheability comes from generation-time `ioRange`/`memRange` predicates, which are exactly the static address-decode assumption ruled out here; that mechanism is **not** borrowed. The *value* does not transfer either — NaxRiscv's walker never writes. |
| Walker as a **store** client (W17/W13/W24) | Specified | **Structurally forbidden** — `assert(storePorts.size == 1)`; RISC-V Svade traps A/D to software | **Forced divergence.** The 68040 mandates hardware U/M with no software-managed alternative, so `UmWriteQueue` is irreducible. Round 2 §5's carry-forward is honoured: these three items have **no outside precedent** and were treated as the highest-risk part of the change — the untagged `storeAck` is demultiplexed by latched owner, `sq.io.drain.ready` and `excStoreReady` are both held under walker ownership, and two sim tripwires assert the drain-to-zero invariant that makes the demux well-defined. |
| W11's probe cancel | Mandatory correctness fix | Immune to the class | **Plan, and round 2 raised its ranking.** Kept as a level rather than a rising-edge pulse: with the grant released on command acceptance the two forms cost the same number of pulses, so the level's extra safety is free. |
| Two walkers vs one | W5/W9: independent engines | **One** engine, per-side storage, `usage` parameter | **Plan.** Round 2 §3 makes the case for unification and our own `AxiIds.scala` header argues for it on ID-collision grounds — but round 2 also says explicitly it is "a separate piece of work with its own risk, not a change to fold into the 19-task plan". Not folded in. Note that this change **dissolves** the ID collision anyway, which removes the main argument for doing it. |
| N5 (walk vs StoreQueue ordering) | Absent from the spec | Mitigated by `redoOnDataHazard` | **Recorded, not built.** Our walker's descriptor read is invisible to the SQ's forwarding/disambiguation — but that is **pre-existing** (the walker bypassed the SQ over AXI too), it is 68040-legal (editing a descriptor without a PFLUSH is already outside the contract), and closing it needs a `DcachePlugin` edit, which W4 forbids. §9. |

**One place I diverged from the plan's letter on FMax grounds, deliberately:**

- **W23's placement.** The plan puts the admission predicate on the exception override's
  `when` **header**. That header is the select of the entire ~104-bit `loadCmd` payload
  mux, and `LsEuPlugin.scala:2821-2871` documents at length that a previous version of
  exactly this header (`excActive && excLoadCmdValid`) was the sole remaining FMax blocker
  — 23 logic levels, WNS −1.109 ns, 414 of 421 failing endpoints rooted at
  `exc_fsm_stateReg[2]`. Adding terms back to it would re-open that. The predicate is
  applied to the 1-bit `valid` assignment *inside* the `when` instead. This is
  **functionally identical** — the payload is a don't-care when `valid` is low — and it
  keeps the wide mux select at the single registered net the retime left it as.
- **The walker legs are pre-muxed** (revalidation A5, promoted from fallback to baseline).
  Both walker payloads are register-sourced at their producers and the select
  (`ldWalkSelDtlb`) is a register, so a short flop-to-flop 2:1 collapses them into one leg
  and the boundary mux stays 4-way — one leg more than today, not two — regardless of how
  many walkers ever exist.

---

## 8. The synth gate

**Not run in this session, and the reason is the host, not the work.** Other agents' JVMs
sat between 8 GB and 15 GB resident for most of the session, against 29 GB total; a KU5P
`full_impl` peaks around 12–15 GB on its own. Starting one would have OOM-killed something,
which has already happened twice on this host today.

Instead of leaving a polling process — which dies with the agent that started it, and has
already silently lost experiments here — the gate is a **committed launcher**:

```bash
synth/run_walker_dcache_gate.sh bb3bca1 baseline HEAD walker-dcache
```

Per arm it (1) queues on `/var/tmp/m68k-ooo-vivado.lock` with a **blocking** `flock` —
never `pgrep`, which self-matches, and never forced — (2) waits for total JVM RSS to fall
below 2.5 GB, (3) checks the ref out into its own worktree, (4) regenerates
`M68kFullCoreSynth.v` and records the netlist md5, (5) runs the same
`POSTROUTE_ROUNDS=9 vivado -mode batch -source synth/impl_FullCore.tcl` recipe for both
arms, and (6) writes `synth/walker_dcache_gate_<label>.summary`.

**When reporting the result, quote the `clk` domain explicitly.** `clk` is the only clock
in this netlist and `SIGNOFF_200MHZ_*` is re-derived at a real 5.000 ns; the headline row
of `timing_summary.rpt` is not the CPU clock and has misled this campaign twice.

**Baseline for the comparison is `bb3bca1`, measured by the same launcher in the same
session** — not the 201.694 MHz in the memory index, and not the 198.531 MHz arm-A figure
from the Part 131 doc, which was measured at `afbabdd`. For calibration only: that same
doc measured the CPUSHP fix now merged into `bb3bca1` at **WNS −0.294 ns / 188.893 MHz on
`clk`**, so the parent is expected to be well below the 200 MHz sign-off already, and any
delta this change causes must be read against a fresh arm-A number rather than against
the sign-off.

**If the change appears to cost timing, the net-renaming control is mandatory before
attributing it — and it is already built and rebased onto the same HEAD, so it is one
command away.** Measured on this very branch's parent: 86 % of one fix's apparent 8.3 MHz
regression was SpinalHDL line-number-derived net renaming that any edit to a file causes,
and only 14 % was real logic.

The control is branch **`measure/walker-dcache-netname-control`**: the real change with
`walkLdReq`/`walkStReq` tied `False` at **identical line numbers**, so every
`when_LsEuPlugin_lNNNN` net name is byte-identical to the real arm while synthesis
constant-propagates the tie-off — no walker ever requests, `ldOwner`/`stOwner` never leave
`CORE`, and every walker mux leg plus the whole ownership FIFO folds away.

```
arm A = bb3bca1                                (baseline logic, baseline names)
arm B = measure/walker-dcache-netname-control  (baseline logic, change   names)  <- control
arm C = feat/walker-dcache-passthrough         (change   logic, change   names)
```

`(A → B)` is pure renaming; `(B → C)` is the real logic cost. Run it with the same
launcher:

```bash
synth/run_walker_dcache_gate.sh measure/walker-dcache-netname-control control
```

**Honest prior.** The revalidation's R8 assessment stands and is if anything stronger now:
the netlist is route-dominated with ten near-tied path families inside a 0.053 ns band, and
this change adds a leg to `loadCmdPort.payload.vaddr` — the net that feeds
`cmdSet → rdSet` — plus an OR term on `probeCancelAll`. It is more likely than not to cost
something. The pre-mux (§7) is the mitigation adopted from the first commit rather than
held in reserve, and if the gate still fails the correct decision is to hold the design and
revisit after the next FMax campaign, **not** to weaken W23/W24/W27/W30 — those are the
correctness core and three review rounds were spent finding that weakening them re-opens
silent-corruption bugs.

---

## 9. Costs and non-decisions, stated

- **N2 — L1D pollution.** Walker reads now allocate lines (WRITETHROUGH allocates;
  `doAllocate` excludes only INHIBITED). Page-table lines compete for capacity. Not
  measured here.
- **Walk latency is worse, and by how much depends entirely on the memory model** — see
  §5.5 for the full table. Under a **zero-latency** model a 3-level walk goes
  15.6 → 21.5 cycles (+37.7 %); under the **L2-faithful** model (L2 5 cyc / DDR 70 cyc)
  the same measurement is 63.5 → 64.3 cycles (**+1.3 %**), because the upper-level
  descriptors now hit L1D instead of paying memory latency. The directed LS-cluster test
  agrees with the zero-latency arm: its cold first access went 34 → 41/44/45/47 cycles
  across seeds, while its **warm** path was unchanged (15 → 15/17/17). **No speedup is
  claimed and none was measured** — every kernel available is a worst case for this
  feature.
- **N4 — a new stall coupling.** Both walker directions are refused for the duration of a
  D-cache maintenance walk (bounded by `sets*ways = 512` iterations). The walker used to be
  immune. Bounded, not cyclic.
- **A walk now cancels the resident early-VIPT probes**, once per descriptor read of a cold
  walk. Correctness-mandatory (§3 item 1); the cost is that those loads fall back to the
  ordinary resolved path.
- **N1 — descriptor RMW is still not atomic.** Unchanged, neither fixed nor worsened.
- **N3 — the `umQueueFull` cycle.** Latent at the parent, unchanged here.
- **N5 — walk vs StoreQueue ordering.** §7. Pre-existing, PFLUSH-covered, not closed.

---

## 10. Follow-ups, sized

1. **Run the synth gate** (§8), both arms, and the net-renaming control if the delta is
   adverse. Highest priority — it is the one gate this work has not passed.
2. **Run the 200-seed fuzz sweep and `ExecuteLockStepSpec`** on an uncontended host.
3. **Shrink `AxiDMerge` for real** — 4 read owners → 2, 3 write owners → 1, plus the
   `socket/AxiDMergeSpec` rework. Self-contained; the two walker slave ports are tied idle
   today so this is dead-logic removal, not a behaviour change. Left out deliberately so
   the correctness change and the arbiter rewrite are separately reviewable and separately
   revertable.
4. **Single-walker unification** (round 2 §3) — now *less* attractive than when it was
   proposed, because its strongest argument (the AR-id collision) is dissolved by this
   change. Size it on the remaining throughput/area grounds alone.
5. **`W27`'s split-leg rows and `WalkerSplitLoadRaceSpec`** should be formally struck from
   the plan, not left as unexecuted tasks: the logic they cover is unreachable at this HEAD.
