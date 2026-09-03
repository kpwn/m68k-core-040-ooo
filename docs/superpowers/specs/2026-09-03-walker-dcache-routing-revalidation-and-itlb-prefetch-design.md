# Walker→D-cache routing: revalidation at HEAD, plus speculative ITLB prefetch — design

**Status:** DESIGN / SCOPING ONLY. No RTL touched by this document.
**Date:** 2026-09-03
**Branch:** `fmax-closure-fanout`
**HEAD this document was written against:** `b7e53a7`

**What this document is.** It is *not* a new design for routing table-walk traffic through the
D-cache. That design already exists, is complete, and has been through seven review rounds:

| Artefact | Path | Size | Status |
|---|---|---:|---|
| Scoping memo | `.superpowers/sdd/2026-08-18-walker-dcache-passthrough-scoping.md` | 478 ln | done |
| Design spec | `docs/superpowers/specs/2026-08-18-walker-dcache-passthrough-design.md` | 3477 ln | 30 DECIDED (W1–W30), 4 non-decisions (N1–N4), signed off round 7 (`849f78a`) |
| Implementation plan | `docs/superpowers/plans/2026-08-18-walker-dcache-passthrough-implementation-plan.md` | 3702 ln | 19 tasks, gated, **never executed** |
| Comparison study | `.superpowers/sdd/naxriscv-walker-dcache-comparison-2026-08-19-report.md` | 300 ln | done |

This document does the three things that were actually still missing:

1. **Revalidates the hazard and the design against current HEAD**, two and a half weeks and
   ~1,900 lines of LSU/D-cache churn later. §2, §3.
2. **Corrects three claims that have gone stale**, one of which invalidates the plan's stated
   acceptance test and one of which invalidates a prescribed fix site. §3.
3. **Adds the speculative-ITLB-prefetch scope** (§9), which must be co-designed with the
   arbitration layer rather than bolted on, because it is a *fifth* requester on exactly the
   port the 2026-08-18 design arbitrates for four.

**Bottom line up front.** The hazard is **real but latent**: it is fully armed structurally and
prevented today only by a *global* default (`CACR = 0` out of reset ⇒ every data access
INHIBITED), not by anything page-table-specific. Any software that enables the data cache —
which is the entire point of the SoC integration — arms it. The design is sound and should be
built. The prefetch proposal is **speculative-value and should be measurement-gated to a
probable NO-GO**; §9 defines the measurement precisely enough to execute at zero RTL cost.

---

## 0. Decision summary

| # | Decision |
|---:|---|
| **R1** | The 2026-08-18 design (W1–W30) is **re-affirmed in architecture**. No decision is reversed. §4. |
| **R2** | The coherency hazard is **REAL and reachable**, not prevented. It is *latent* under the reset posture (`CACR.DE = 0` ⇒ all-INHIBITED), which is a global cache-disable, not a page-table protection. §2. |
| **R3** | **The plan's stated acceptance test is stale and must be replaced.** `mmu_atc_write_hit_sets_modified` **passes at HEAD** (fixed by `e0d2752`), and passes *because* `DE = 0` makes the question moot in its posture. It can no longer demonstrate the bug. §2.4, §3.3. |
| **R4** | A **new exhibiting test** is specified (§2.3): a directed posture with `CACR.DE = 1` and a COPYBACK mapping over page-table memory, with the walked VA deliberately **outside** any TTR. It must be shown to fail at HEAD before any RTL lands. |
| **R5** | **`mmu-atc-m-bit-tracking` (`c629bec`) is fully SUBSUMED by `e0d2752` and must NOT be merged.** It is a dead-end branch whose mechanism HEAD deliberately does not need. §8. |
| **R6** | The design's **site table (W27) must be re-derived wholesale**, not re-pointed. `LsEuPlugin.scala` went 2117→2911 lines. One prescribed fix site **no longer has the shape the fix assumes**. §3.1. |
| **R7** | **Spec §11.1 item 9's "live HEAD bug" has been FIXED** by `072e97a` — a direct, already-realised return on the design review itself. The plan's "Plan-level risk" section is stale and must be re-written to the "landed BEFORE Task 4" branch. §3.2. |
| **R8** | **FMax risk is materially WORSE than the spec assessed**, not better. The netlist is now route-dominated (53–81 % route) with a 0.053 ns band over 10 near-tied families at +0.042 ns margin. The `loadCmdPort.payload.vaddr` widening should be pre-mitigated (pre-mux the walker legs one cycle early) **from the first commit**, not held as a fallback lever. §7. |
| **R9** | Speculative ITLB prefetch is **scoped but GATED**, with a pre-registered NO-GO (§9.6.5). Suppression of every side-effecting path is *structural* — a prefetch walk has no `robId`, so it cannot enter `UmWriteQueue` at all (§9.2) — not a new set of gates to get right. |
| **R10** | If prefetch is ever built, it is a **strictly non-forcible, lowest-priority** requester (`force` hardwired `False`), needs a **5-valued** `LdRspTag` (3 bits), and must land **after** the base design has merged and passed its synth gate — never concurrently. §9.4. |
| **R11** | **The proposal's two triggers must be evaluated separately.** T-FTB (branch target on another page) is **presumptively dead**: the lead-time distribution of the exact signal it would use (`targetHoldValid`) was measured this month at **0–2 cycles** and killed the FTB-directed L1I prefetch. A walk is 3 dependent round trips. T-SEQ (approaching a page boundary) has no adverse evidence but faces a lead-vs-accuracy tension. §9.5a. |
| **R12** | **BLOCKING: the IPC bench produces zero ITLB walks in every kernel today** (MMU off except two kernels, which use match-all TTRs). Any measurement campaign must first build an MMU-enabled harness — a strictly larger lift than the FTB campaign's, and itself evidence that ITLB stalls have never been observed to matter here. §9.6.1. |
| **R13** | If a prefetch buffer is ever needed, **the ITLB's existing 1-entry sticky walk-result latch** (`ItlbPlugin.scala:149-154`) is already structurally a non-polluting translation prefetch buffer, with its match term and every invalidation hook already in place. This changes the cost side of the eventual decision. §9.5. |

---

## 1. Why this is worth doing at all — the three reasons, re-checked

### 1.1 Correctness (bidirectional)

Re-verified at HEAD, both structural pillars intact:

- `TableWalker.scala:38-47` still owns a private `Axi4ReadOnly` port tagged
  `AxiIds.WALK_READ = 2` (`cache/AxiIds.scala:96`). Its own header comment (`:22`) still calls
  it "a dedicated 128-bit read-only port".
- U/M writeback still goes out on a private write path tagged `WALK_WRITE = 3`
  (`DtlbPlugin.scala:473-489`, `ItlbPlugin.scala:383-…`), staged by `UmWriteQueue.scala`.
- `grep -c 'snoop\|Snoop' src/main/scala/m68k040/cache/DcachePlugin.scala` → **0**. There is no
  snoop port and no external-write invalidate path. The D-cache is *never told* the walker
  wrote.

So both failure modes stand exactly as §1.1/§1.2 of the 2026-08-18 spec describe them:
**stale read** (walk reads DRAM while the newer descriptor sits dirty in L1D) and **lost
update** (walker writes DRAM, later L1D eviction overwrites it).

### 1.2 Performance — and one claim that is stronger than the spec made it

The spec argued page tables are hot and small. That is true but unmeasured. **A stronger,
structural claim is available and does not require a benchmark:**

`AxiDMerge` is a *serialising* arbiter with **one outstanding grant per direction**
(`socket/AxiDMerge.scala:60-96`, and its own words: *"three owners now share one port, so an
owner that never completes starves the other two indefinitely. Previously they were physically
separate masters and could not affect each other."*).

Therefore, in the socket build **today**, each of a walk's three dependent descriptor reads
takes **exclusive** ownership of the whole `axi_d` read grant for a full memory round trip,
during which **no D-cache refill can proceed**. A walk is not merely slow; it is three
serialised exclusive holds on the core's only D-side read path. Routing walks into L1D removes
those three holds entirely on a walk that hits, and converts a missing walk into ordinary
refill traffic that shares the same arbitration everything else already uses.

This compounds with the documented read-side fabric problem — `docs/fabric_concurrency_contract.md`
(SoC worktree `/home/qwertyoruiop/macqd700-soc-worktrees/m68k040ooo-integration`) measures
**4.88×** for 8 concurrent vs 8 sequential reads — though note that document's own §407 caveat
that the 4.88× figure is **not** hardware-confirmed. Cite it as motivation, never as a
predicted speedup.

**No speedup is claimed here.** §6 defines how one would be measured.

### 1.3 Simplification

Concrete and countable:

| Item | Today | After |
|---|---:|---:|
| `M68kFullCoreSynth` top-level ports | 205 (`tools/socket/fullcore_ports.golden`) | 127 |
| — of which `itlbAxi_*` | 39 | 0 |
| — of which `dtlbAxi_*` | 39 | 0 |
| `AxiDMerge` read owners | 4 (`DCACHE`, `ITLB`, `DTLB`, `RESETVEC`) | 2 |
| `AxiDMerge` write owners | 3 | 1 (pass-through) |
| Live AXI IDs in the D-side namespace | incl. `WALK_READ=2`, `WALK_WRITE=3` | both reserved-but-unused |

**78 of 205 top-level ports — 38 % of the synthesised port surface — are walker AXI.** That is
the single largest structural simplification available anywhere in this core.

The ID-namespace point has precedent teeth: an L2C ID-namespace starvation bug
(`l2c_ctrl.v`'s `pipe_id_haz_c`) was already fixed once by widening tags with a source
qualifier. Fewer distinct masters is structurally valuable here, not cosmetic.

---

## 2. Is the hazard real? — confirmed, with the caveat that changes its priority

The task that commissioned this document asked, correctly, whether anything already prevents
the hazard. **Something does, but not what one would hope, and not durably.**

### 2.1 Nothing page-table-specific prevents it

Swept exhaustively. There is **no** page-table-address-aware logic anywhere in
`src/main/scala`: no range check, no descriptor-address special case, no forced-inhibit.
`cacheMode` is decided purely from the CM[6:5] field of whatever TTR or leaf descriptor covers
the address:

- `DtlbPlugin.scala:270-298` is the response mux, in priority order: TTR hit
  (`:277-282`, `TtMatch.cacheMode` = `CacheMode.decode(ttr(6 downto 5))`) → MMU-off fallback
  (`:284-289`, **WRITETHROUGH**, identity PPN) → TLB hit (`:291-296`, `tlbEntry.cacheMode`,
  filled at `:342` from `walker.io.rsp.cacheMode` = `CacheMode.decode(MmuDesc.pgCacheMode(d))`,
  `TableWalker.scala:209`, `MmuTypes.scala:73`).
- Decode: `cache/IcacheTypes.scala:76-86` — `00 = WRITETHROUGH`, `01 = COPYBACK`,
  `1x = INHIBITED`.

**Nothing distinguishes a page-table line from a data line.** A descriptor whose CM is `01`
gives COPYBACK, and the D-cache will hold that line dirty.

### 2.2 What *does* prevent it today: a global cache-disable, by reset accident

There is exactly one fold point for ordinary D-side accesses,
`LsEuPlugin.scala:2429-2430`:

```scala
val txEffectiveCmode = Mux(cacheCtrl.map(_.dcacheEnabled).getOrElse(False),
  xlate.rsp.payload.cacheMode, m68k040.cache.CacheMode.INHIBITED)
```

`dcacheEnabled` is `exc.ss.cacr(31)` (`rob/RobPlugin.scala:2200`) and `ss.cacr` is
`RegInit(U(0,32))` (`exception/SystemState.scala:53`).

**Out of reset, `CACR = 0` ⇒ `DE = 0` ⇒ every data access is INHIBITED regardless of TTR or
descriptor CM.** The D-cache holds nothing at all, so it cannot hold a stale descriptor.

This is a *global cache-disable*, not a protection. It is exactly the bit that any real
supervisor enables early in boot, and the whole purpose of the D-cache. Treating it as the
mitigation would be indistinguishable from saying "the bug does not occur while the cache is
turned off".

**COPYBACK is fully implemented and reachable**: `DcachePlugin.scala:1163`, `:1224`
(`stS2Copyback`/`stS3Copyback`), on-chip RMW + dirty with no AXI (`:1221-1224`), write-allocate
on copyback miss (`:22-24`), `dirtyMissWb` (`:1108`); `ls/StoreQueue.scala:420,626-629`
special-cases it. Reaching it needs `CACR.DE = 1` **and** CM = `01` in the covering
descriptor/TTR.

**Verdict: the hazard is REAL and armed; it is latent only under a posture nobody ships.**

### 2.3 The exhibiting sequence (this becomes the directed test)

The existing `ForceCacheableCopyback` posture
(`src/test/scala/m68k040/fuzz/PortedTestRunner.scala:15-25`, applied `:243-255`) is **not**
directly usable, and this is a trap worth recording because it looks usable:

```
LowHalfCopybackTtr      = 0x007FE020   // base 0x00, mask 0x7F, E=1, S=11, CM=01 COPYBACK
HighHalfInhibitedTtr    = 0x807FE060   // base 0x80, mask 0x7F, E=1, CM=11 INHIBITED
CacrDataAndInstructionEnable = 0x80008000
```

Under it, `dtt0` transparently covers the whole low 2 GiB and `dtt1` the high 2 GiB. Per
`MmuTypes.scala:144-157` a TTR hit is checked **before** the TLB (`DtlbPlugin.scala:277` vs
`:291`), so **every** D-side access is answered transparently and **no table walk ever
happens**. A posture that covers all 4 GiB in TTRs cannot exhibit a walker bug.

**The correct posture** must leave the walked VA uncovered while making page-table memory
cacheable:

1. `CACR.DE = 1` (and IE as convenient).
2. A DTT covering **only the physical region holding the page tables** — e.g. base `0x00`,
   a mask narrow enough to cover `0x00200000..0x002FFFFF` but **not** the test VA — with
   **CM = 01 (COPYBACK)**. This is what makes an ordinary supervisor `move.l` to a descriptor
   leave the line **dirty in L1D**.
3. The test VA (e.g. `0x80001000`) covered by **no** TTR, so it is genuinely table-walked.
4. `TC.E = 1`.

**Read-side trace (spec §1.2, the undocumented half):**

```
  ; page tables already built at 0x00220000, leaf descriptor at 0x00220004
  move.l  #newdesc,0x00220004    ; supervisor store -> COPYBACK hit, line DIRTY in L1D only
                                 ; backing memory still holds the OLD descriptor
  ; do NOT cpush, do NOT evict
  move.l  0x80001000,d0          ; DTLB miss -> walk -> WALK_READ reads DRAM
                                 ; -> reads the STALE descriptor
  ; assert: the installed translation reflects `newdesc`, not the pre-store image
```

**Write-side trace (spec §1.1, the lost update):**

```
  move.l  0x00220004,d0          ; ordinary load pulls the descriptor line into L1D
  move.l  d0,0x00220004          ; store it back -> line resident DIRTY (COPYBACK)
  move.l  #0,0x80001000          ; write to a page whose leaf has M=0
                                 ; -> walk sets M, queues U/M write, drains via WALK_WRITE
                                 ; -> lands in DRAM, invisible to L1D
  ; force eviction of 0x00220004's line (touch `ways` conflicting lines in that set)
  move.l  0x00220004,d0          ; assert: M is still set
                                 ; BUG: the eviction wrote the pre-update image back over it
```

Both must be **shown to fail at HEAD** before any RTL lands. A test that does not first fail
proves nothing — the standing evidence bar in this repo.

Practical note: the second trace needs a deterministic eviction. `DcachePlugin.scala:43-44`
gives 16-byte lines; the set/way geometry (128 sets × 4 ways) means touching 4 addresses
`0x00220004 + k*(sets*lineBytes)` evicts it deterministically. Alternatively use `CPUSHL` and
assert on the pushed image, which is cheaper and does not depend on replacement policy.

### 2.4 The acceptance test named by the existing plan is stale

`docs/.../walker-dcache-passthrough-implementation-plan.md:191,222,3474` and spec §10.1 name
`mmu_atc_write_hit_sets_modified` as the acceptance test that "must move from fail to pass".

**It already passes.** It was fixed by `e0d2752` ("fix(mmu): task #210 — ATC write-hit M-bit
gap"), which is an ancestor of HEAD; that commit's own verification block records
`mmu_atc_write_hit_sets_modified PASS`. The 2026-09-03 residual triage (`a9f7ddf`) does not
list it among the residual failures.

And it passes for a reason that matters: the test never writes CACR, so `DE = 0` and *all* its
accesses — including its own step-3/step-5 descriptor reads through DTT0 (`0x007FA000`,
CM = `00`) — are folded to INHIBITED at `LsEuPlugin.scala:2429`. The D-cache never caches the
descriptor line, so the coherency gap **simply does not arise in that posture**.

**Consequence:** this test cannot serve as the acceptance test for this work in either
direction. §2.3's new directed test replaces it. Any plan step that says "must move from fail
to pass" for `mmu_atc_write_hit_sets_modified` must be deleted, not re-pointed.

---

## 3. Drift audit — what changed since 2026-08-18

The spec was written against `bb7774c`(+socket tasks); the plan against `849f78a`. Since then:

| File | Then | Now | Δ |
|---|---:|---:|---:|
| `execute/LsEuPlugin.scala` | 2117 | 2911 | **+37 %** |
| `cache/DcachePlugin.scala` | 2092 | 3226 | **+54 %** |

Roughly 30 commits landed on the MMU/D-cache/LSU hot path, including several FMax retimes that
*deliberately restructured the exact signals this design hooks*. **Every line citation in both
documents is a locator, not a fact.** The plan already says this (Global Constraint 26); this
section makes it actionable.

### 3.1 The one prescribed fix whose target changed shape — R6

Spec W27 / §4.2.7 and §10.2's round-6 row are built around the exception-sequencer
**presentation header**, cited as `LsEuPlugin.scala:2027`:

```scala
when(excActive && excLoadCmdValid) {          // the shape the spec assumes
```

**At HEAD that header is `LsEuPlugin.scala:2821` and reads:**

```scala
when(excLoadCmdValid) {                        // `excActive` DELETED
```

The `excActive` term was removed by FMax commit `6028a6c` ("shorten the exception→early-probe→IQ
cone") as provably redundant — `excLoadCmdValid ⇒ excActive` holds by construction
(`ExceptionUnit.ldoValidReg`; 6-point proof at `LsEuPlugin.scala:2776-2797`), machine-checked
by a sim-only tripwire at `:2815-2820`.

This does **not** invalidate W27's *finding* — the header is still unqualified with respect to
port ownership, which is the actual defect — but it does invalidate the round-6 test
prescription, which says the test "must be shown to fail against an unqualified
`when(excActive && excLoadCmdValid)` presentation header". That mutation no longer exists to
mutate. **The re-derived form must bind the header to `excLoadAdmit`, and the mutation proof
must target the current one-term header.**

This is precisely the class of error W27's own rule anticipates: *"A plan-writer must re-derive
the table against the then-current file … and must not add a `dcache.*` reference to
`LsEuPlugin` without adding a row."*

### 3.2 The spec's "live HEAD bug" has been fixed — R7

Spec §11.1 item 9 records a live silent-corruption bug found *by the design review itself*: an
exception-sequencer load consuming an ordinary LS-pipe load's response.

**It landed as `072e97a` (2026-08-18), "fix(exception): close stale-load-response race in
E_DRAIN/R_DRAIN vs a flushed, in-flight LsEu load".** The fix comment at
`exception/ExceptionUnit.scala:410-426` credits it explicitly: *"UPDATE (found during unrelated
walker/DcacheService design review) … Both `E_DRAIN` and `R_DRAIN` now wait on
`sqDrained && dcQuiesced`."*

Two consequences:

1. **The plan's "Plan-level risk" section (`:29-41`) is stale.** It states, verified at the
   time, that the standalone fix had *not* landed. It has. The plan must be re-written to its
   own "lands BEFORE Task 4" branch: Task 4 Step 0 re-derives W30's trace on top of `072e97a`
   and keeps the stronger of the two predicates.
2. **W30 part (2) must still not be weakened.** `dcQuiesced` guards only the `E_DRAIN`/`R_DRAIN`
   states; exception loads also occur in later frame-access states where it is not asserted, and
   no upstream fix covers the walker-vs-`CORE-EXC` case that W30 part (2) also closes. The spec
   pre-registered this exact instruction and it holds.

This is worth recording as a result in its own right: **the design review paid for itself in a
shipped production fix before a single line of its own RTL was written.**

### 3.3 The acceptance test — see §2.4. R3.

### 3.4 Sites that survived unchanged (the design's load-bearing hooks)

Re-derived at HEAD. The good news is that the architecture's anchors are intact:

| Spec citation | HEAD | Status |
|---|---|---|
| `:265` `sq.io.drain.ready := dcache.store.ready` | **`:295`** | unchanged — **still unconditional**, W24 stands |
| `:270` `sq.io.drainAck := dcache.storeAck && !excStoreOutstanding` | **`:300`** (+`:301` err) | unchanged |
| `:756` `loadCmd.valid := Mux(useSplitCmd, …)` | **`:1030`** | unchanged |
| `:757-764` loadCmd payload mux | **`:1031-1039`** | unchanged |
| `:766` `alignedCmdFire` | **`:1040`** | unchanged |
| `:863` `probeCancelAll = sqFlushSig \|\| excActive` | **`:1158`** | unchanged — W11's hook intact |
| `:886` `parallelViptLaunch` | **`:1181-1182`** | unchanged |
| `:1774-1776` `splitReqArm`/`normalReqArm` `!excActive` | **`:2502-2504`** | unchanged — W11's hook intact |
| `:2067-2076` exception **store** arm | **`:2861-2870`** | unchanged (this one **kept** `excActive`) |
| `:2115` `excLoadCmdReady := dcache.loadCmd.ready` | **`:2909`** | unchanged — **still unconditional**, W23 stands |
| `:981` `(!earlyProbeTokenPresent \|\| earlyProbeOwnsCmd)` on `loadCmdPort.ready` | **`:1546`** | unchanged — **§4.2.6's deadlock is still live** |
| `DcachePlugin` load/store port count | `loadCmdPort` `:84`, `storePort` `:89` | still **exactly one each** |
| `DcachePlugin` MMU references | 0 | unchanged — §6.1's no-cycle proof holds |

### 3.5 New sites the W27 table does not have rows for

These appeared after the sweep and **must** be added when the table is re-derived:

- **`:930-931,940-942` `alignedSendHeld`** — a new term in `alignedSendValid`, from `e7ee618`
  (misaligned-MOVEM split slot-B send-hold). W11's `:716` factor-up must account for it.
- **`:1174-1176` `loadProbeCancel`** and **`:1177-1180` / `:2431-2436` `loadProbeResolve`** —
  the resolve path now has a default drive *and* a real override drive.
- **`:2504` `dcache.loadProbe.ready`** consumed inside `normalReqArm`.
- **`:1544-1548` `loadCmdPort.ready`** gained `!earlyProbeConflict` and the
  `resolveOldProbeDuringMaint` escape hatch on the `maintBusyReg` gate.
- **`:2614-2618` `storePort.ready`** gained `inputCopyback`/`inputPipelinedWt`/`wtOutstanding`
  terms (from `19afd14`). §6.5's store-liveness argument cites the old `:1865-1867` form and
  must be re-derived against this one.
- **`:1940` bkFsm `LAUNCH`** is now flagged in-tree as *dead infrastructure* (`bkStart` never
  driven). The W27 row for the old `:1508` may be moot — confirm before implementing a fix for
  a path that cannot execute.

### 3.6 Two facts that make W7's ownership FIFO *more* clearly necessary

Re-confirmed at HEAD, and worth stating because they are the reason a simpler design does not
work:

- **`DLoadRsp` has no token field at all** (`DcacheTypes.scala:67-71`: `data`, `line`, `fault`).
  Load responses are matched **positionally** — by the aligned-ring pointer or the bkFsm state.
  `LsEuPlugin` never checks a token on the response side. So adding a client genuinely requires
  an out-of-band identity mechanism; W7's FIFO is not gold-plating.
- **`DStoreCmd` has no token field either** (`DcacheTypes.scala:83-96`), which is exactly why
  `excStoreOutstanding` (`:298`) exists to demux the untagged `storeAck`. W13's walker demux is
  the same pattern, and `ls/StoreQueue.scala`'s stray-ack assertion makes it mandatory rather
  than prudent.

---

## 4. The mechanism — what the design says, re-affirmed (R1)

Summarised for a reader who will not read all 3477 lines. Nothing here is new; §§ refer to the
2026-08-18 spec.

**Reads.** `TableWalker` drops `io.axi`/`axiCfg` and its hand-rolled `selectWord`, and instead
drives a `DcacheService` `loadCmd`/`loadRsp` client pair (W16). `selectWord` was a hand copy of
`DcacheByteLane.extract`'s LONG case; deleting it removes a duplicated big-endian lane-select
that has already been the subject of one silent-corruption fix (task #194).

**Writes.** The U/M writeback becomes a single `DStoreCmd` with `useStrb = True`,
`strb = drainStrb`, `lineData = drainBeat`, `paddr = drainAddrReg`, `precise = False` (W17).
The whole AW/W/B drain FSM in both TLB plugins is deleted.

**Arbitration** (W4) extends `LsEuPlugin`'s *existing* last-assignment-wins override mux — at
HEAD `:2748-2872` plus the ready tail at `:2909` — from 2 sources to 4: `CORE-LS`, `CORE-EXC`,
ITLB, DTLB. **`DcachePlugin.scala` gets zero edits**, gains no port, and never learns walkers
exist. That is the single largest risk reduction in the design and is why the mux site was
chosen over a new arbiter component.

**Ownership is per-direction** (W5): a load owner and a store owner, because a walk's read phase
and its write phase are separated by an entire ROB commit and a single token would needlessly
serialise them.

**Response identification** (W7/W29/W30): a depth-4 **ownership-tag FIFO** on the load
direction, pushed on every accepted `loadCmd` and popped in lockstep with every `loadRsp`,
carrying a **four**-valued tag `{CORE_LS, CORE_EXC, ITLB, DTLB}` encoded **combinationally from
the four admission predicates** that gate the mux legs — never from a separately-clocked owner
register (W28/W29). Stores keep drain-to-zero.

**cacheMode** (W1/W2/W3): a **fixed architectural policy**,
`CACR.DE ? WRITETHROUGH : INHIBITED`, stamped by the mux, never derived from an address, never
COPYBACK.

On the last point, the commissioning task asked that "the 68040 honors descriptor cache-mode
bits — that must be preserved". **It is, and the two questions must not be conflated:**

- The *mapped page's* cache mode is read from the descriptor (`TableWalker.scala:209`,
  `rCmode := CacheMode.decode(MmuDesc.pgCacheMode(d))`), returned in `rsp.cacheMode`, cached in
  the TLB entry (`DtlbPlugin.scala:342`) and used for the access. **Untouched by this design.**
- The *descriptor fetch's own* cache mode cannot come from a descriptor — that is circular, and
  is why real 68040 table searches are physical-only. A fixed policy is the only well-defined
  option. This does not violate the standing no-address-map rule (`§4.1.1`): there is no page
  attribute in existence to consult, for anyone, ever.

WRITETHROUGH specifically, not merely "cacheable": under WRITETHROUGH the U/M update lands in
**both** the array and memory in **all three** residency states with no dependence on the
eviction path (spec §4.1.2's table). COPYBACK would be correct but would trade a proof for a
dependency on exactly the eviction path that created the bug. INHIBITED **structurally
reintroduces** the bug — an INHIBITED store never touches the array (`DcachePlugin.scala:714-717`),
so it updates memory and leaves L1D stale, byte-for-byte the original failure.

---

## 5. The deadlock argument

The commissioning task named this as the crux and asked that it not be hand-waved. The design
answers it in three separate places; all three re-check clean at HEAD.

### 5.1 There is no circular translation dependency (spec §6.1)

The feared cycle is: *the access needs a translation → the translation needs a walk → the walk
needs the D-cache port the access occupies.* **The first link does not exist.**

`DcachePlugin` contains **zero** references to `TranslationService`, `DTranslationService` or
`MmuControlService` — re-verified at HEAD by grep, count 0. It never invokes the MMU. It
operates purely on a **pre-translated `paddr` supplied by its caller** (`DcacheTypes.scala:47-54`).

The walker supplies physical addresses it computed arithmetically from `rootPtr` + VA index
slices. **A walk's D-cache access requires no translation.** So routing walks into the cache
cannot create a translate-inside-the-cache cycle. The dependency graph is
`access → walk → cache`, a chain, not a cycle.

That is the whole answer to the central hazard, and it is structural rather than argued.

### 5.2 The one *real* deadlock this design creates, and its mandatory fix (W11, spec §4.2.6)

**Not the circular-translation one — a different, genuine deadlock that a naive implementation
walks straight into.** It is not in the scoping memo and was found only by the design pass.

`loadCmdPort.ready` is gated on `(!earlyProbeTokenPresent || earlyProbeOwnsCmd)` —
**re-verified at HEAD, `DcachePlugin.scala:1546`**. A resident early-VIPT probe token blocks any
load command that does not own it. Then:

1. The mux hands the load port to a walker and deasserts `CORE`'s `loadCmd.valid`.
2. `loadProbePort.ready` is gated on `(!loadCmdPort.valid || useEarlyProbe)` — with `CORE`'s
   valid now low that term is **satisfied**, so the LS EU keeps launching probes.
3. Probe slots fill (`earlyProbeDepth = 4`). `earlyProbeTokenPresent` is high, with four tokens
   whose matching load commands the mux will never let through.
4. The walker's `loadCmd` matches none of them ⇒ `earlyProbeOwnsCmd` false ⇒
   `loadCmdPort.ready` is **false forever**. Neither side advances.

**The fix reuses machinery that already exists for exactly this hand-over**, and both hooks are
intact at HEAD:

- `probeCancelAll = sqFlushSig || excActive` (**HEAD `:1158`**) becomes
  `sqFlushSig || excActive || walkerOwnsLoad`. The design *already* cancels every resident probe
  when the port goes to the exception sequencer; this adds the walker to the same term.
- `normalReqArm`/`splitReqArm`'s `!excActive` (**HEAD `:2502-2504`**) becomes
  `!dcLoadHeldByOther` where `dcLoadHeldByOther = excActive || walkerOwnsLoad`, suppressing new
  probe launches.

Cancelling a probe is functionally free — `DcacheTypes.scala:12-18,36-45` state that an absent
probe qualification simply makes the later resolved `DLoadCmd` use the ordinary path. It is a
performance event, not a correctness one.

`WalkerProbeDeadlockSpec` must be shown to **hang** without the `probeCancelAll` term.

### 5.3 Starvation and the remaining waits are bounded, not cyclic

- **Priority** (W8): `CORE-EXC > CORE-LS > {ITLB, DTLB}` round-robin (W9), with a per-walker
  **aging counter**: after `WALKER_AGE_LIMIT = 64` un-granted cycles a walker outranks
  `CORE-LS` (never `CORE-EXC`'s presented command). `force` changes only the *next* grant
  decision; it never yanks the port mid-transaction.
- **No drain on the load direction at all** — that is what the FIFO buys. A walker command may
  be accepted with `CORE-LS` loads still outstanding.
- **The exception sequencer cannot be starved**, *per load*: `excLoadCmdValid` is
  `ExceptionUnit`'s held `ldoValidReg`, asserted continuously until that load fires, so from its
  first presenting cycle no new walker is admitted behind it and the FIFO can only shrink.
  Between two consecutive exception loads there is a real 1–2 cycle window in which at most two
  walker commands can interpose — deliberate, bounded, one bounded D-cache access each.
- **Walkers must be allowed to run during `excActive`** (not blanket-blocked): since socket
  Task 11 the exception sequencer performs genuinely virtual FSAVE/FRESTORE accesses through the
  DTLB, so a DTLB miss *raised by the exception sequencer itself* must be able to walk. A blanket
  rule would deadlock.
- **The one sub-phase where walker admission genuinely must close** is the maintenance quiesce
  (W19): `quiesceHold` over `S_DRAIN || S_APPLY` — `S_APPLY` too, because `maintCmdOut` pulses
  there while `maintBusyReg` only rises a cycle later, leaving a one-cycle fully-open window.
  Without it, `ExceptionUnit.scala`'s written deadlock proof (*"With the LS EU flushed, nothing
  re-arms them"*) becomes **false**, because a walker can re-arm them. The hold is at *command*
  granularity — a walker mid-walk simply stalls between descriptor reads.
- **The CPUSH/CINV maintenance walk** (which `FullCoreSynth.scala:460-464` notes "takes the
  D-cache's shared array read port and AXI write channels") refuses **both** directions for its
  bounded duration: `loadCmdPort.ready` (`:1546`) and `storePort.ready` (`:2614-2618`) both carry
  `!maintBusyReg`. This is a new **stall** coupling on the walker — today it has its own master
  and is immune — bounded by `sets*ways = 512` iterations. **Not** a deadlock: the maintenance
  walk depends on nothing the walker holds and completes autonomously. The graph is
  `walker → maintenance`, never the reverse.

### 5.4 The I-side cross-path

The commissioning task correctly flags that `ItlbPlugin`'s walker would need to reach the **D**-cache,
a path that does not exist today. Two things make this a non-event rather than a new subsystem:

1. **It is already true on the AXI side.** `AxiDMerge.scala:44-48` records the forcing
   constraint: the ITLB walker *cannot* join `axi_i` because it genuinely issues AXI **writes**
   (the U-bit writeback) and `cpu_socket.vh:99` declares `axi_i` as AR/R only. **Both walkers are
   already D-side masters.** This design does not create a cross-side path; it moves an existing
   D-side path from the fabric to the cache.
2. **The wiring idiom already exists.** W15 puts the walker client ports as plugin-level `var`
   hooks with default-idle `allowOverride` drives, wired by the top level — exactly mirroring the
   existing `umCommitValid`/`umFlush`/`excLoadCmdValid` pattern. No new service lookup, no new
   `FiberPlugin`.

The ITLB walker is simply a fourth client of the same mux, arbitrated identically. 68040 table
searches are data accesses even for instruction translations, so this is also the
architecturally correct place for it.

### 5.5 Behaviour when the MMU is disabled

With `mmuEnable = 0` and no TTR hit, `DtlbPlugin.scala:284-289` answers directly with identity
PPN and WRITETHROUGH — **no walk is launched at all**. There is nothing to arbitrate. Same for
a TTR hit (`:277-282`), which is resolved before the TLB is consulted. The walker only runs on a
genuine TLB miss under `mmuEnable`, which is unchanged by this design.

---

## 6. Quantifying the wins — and how a speedup would be *measured*, not claimed

### 6.1 Simplification: countable, already stated

§1.3's table. 78/205 ports, `AxiDMerge` 4→2 read owners and 3→1 write owners, two AXI IDs
retired from the live D-side namespace. These are exact, verifiable by
`tools/socket/check_socket_netlist.py` against `tools/socket/fullcore_ports.golden`, and require
no benchmark.

### 6.2 Performance: the structural claim vs. the measured claim

**Structural (safe to state):** each descriptor read currently takes an exclusive, serialising
`axi_d` read grant (§1.2). Three per walk. After the change, a walk that hits L1D generates
**zero** fabric transactions.

**Measured (must not be asserted before it is run).** This project has an explicit precedent
against plausibility: an "obvious" OoO improvement measured **−44.9 % IPC**, and — this week —
FTB-directed L1I prefetch looked like **+26.9 %** on a first sweep and was then **killed by its
own telemetry** as an unambiguous NO-GO (`docs/superpowers/specs/2026-09-03-ftb-directed-prefetch-design.md`;
kill commit `3a35bcfd` in the `cpu040` submodule, *"T3 kills FTB-directed prefetch at zero RTL
cost"*). **No speedup is claimed in this document.**

### 6.3 The measurement, defined

Harness: `src/test/scala/m68k040/bench/IpcBenchSpec.scala`, which already supports
`IPC_SEED` / `IPC_MEM=l2` (5-cycle L2 hit / 70-cycle DDR) / `IPC_ONLY`. Per the FTB precedent's
acceptance bar item 10, **a run under the zero-latency default memory model is disqualifying**
for any memory-side claim (that sweep measured 0.6 % vs 26.9 % on the same comparison).

Counters to add to the existing `cd.onSamplings` block (`IpcBenchSpec.scala:506`), fields in
`IpcResult` (`:396-410`), following `IcachePrefetchSpec.scala:41-56`'s pattern — **all sim-only,
all reported per kernel**:

| Metric | Source | Why |
|---|---|---|
| `walkCount` (I-side, D-side separately) | `walker.io.start` pulses in each TLB plugin | the denominator; if it is ~0 on realistic workloads the whole perf case is void |
| **`walkL1dHitRate`** | per descriptor read: `loadRsp` returned without an intervening `axi.ar.fire` | **the headline number.** The entire perf thesis is that this is high |
| `walkCycles` histogram | `walker.io.start` → `walker.io.done` | before/after, directly comparable |
| `axiDReadGrantCyclesByOwner` | `AxiDMerge` owner tag × grant-held cycles | proves §1.2's exclusive-hold claim and measures what is freed |
| `dcacheRefillStallOnWalkGrant` | refill AR presented while grant owner ∈ {ITLB, DTLB} | the *contention* this removes, which is the mechanism, not the symptom |
| L1D miss-rate delta | existing D-cache counters | **the cost side of N2** — walker reads now allocate lines and compete for capacity |

Plus the occupancy-histogram technique from
`docs/superpowers/specs/2026-09-03-early-flush-ipc-ab-measurement.md` for backlog attribution.

**Pre-registered acceptance bar for the perf claim** (registered now, so a marginal result
cannot be renarrated afterwards):

1. `walkCount` on the chosen workload must be **≥ a stated floor**; if walks are rare the perf
   case is withdrawn and the work proceeds on **correctness + simplification grounds alone**,
   which is a perfectly good outcome and is in fact the expected one.
2. `walkL1dHitRate` must be reported before any IPC number is interpreted.
3. **L1D miss rate must not regress materially** (N2's pollution cost).
4. IPC alone is not an accept signal — the bench has ~1 % run-to-run jitter unpinned.

**Workload realism.** The FTB precedent's failure mode was benchmarking cold-start
compulsory-miss-dominated microkernels and generalising. Table-walk behaviour is *even more*
sensitive to this: a microkernel touching two pages will walk twice, ever. **A boot trace is
mandatory**, not a microkernel. The `cpu040` boot campaign
(`memory/boot-investigation-consolidated-2026-08-27.md`) is the natural source.

---

## 7. Cost and risk — FMax is worse than the spec assessed (R8)

### 7.1 What the design itself says it costs

Spec §5 is honest and withdrew its own earlier zero-cost claim. Three of five new sites are free
by substitution (owner-qualification AND terms on low-fanout control registers feeding control
flops). **Two are real:**

1. **`loadCmdPort.payload.vaddr` widens 3-way → 5-way** on the protected net. This is the
   highest-risk item. `DcachePlugin` derives its BRAM read address straight from that payload:
   `cmdVaddr → cmdSet → rdSet`. `DcachePlugin.scala:1265`'s own comment records that this arc
   (`loadCmdPort.ready → arbiter → tag/dataMem read-address`) was *the* post-route critical path
   and that the store base was deliberately kept out of its cone.
2. **`probeCancelAll` gains an OR term** — an addition, not a substitution. Cheap (one 3-input
   OR from registers) and structurally off the protected net: it feeds only the
   `earlyProbeValids` control registers, whereas `loadProbePort.fire` is what drives `rdSet`.

### 7.2 Why the risk is now higher than when that was written

The spec assumed a starting point of 197.278 MHz / `FAILED_AT_200` by 0.069 ns. The current
picture (`.superpowers/sdd/progress-fmax-closure-fanout-2026-08-17.md`) is different in kind, not
just degree:

- **Sign-off is now MET_200 at +0.042 ns** (201.694 MHz) — positive, but razor-thin.
- **The worst-100 spans 0.042…0.095 ns — a 0.053 ns band over 10 source families in 8 plugins.**
  The 4.000 ns analysis of the same routed netlist reproduces the ordering identically, offset by
  exactly 1.000 ns, which rules out an analysis artifact: *the band is a structural property of
  the netlist*.
- **Every single-family fix except the top one is worth 0.000 ns**, and the top one is worth
  +0.004 ns. There is no single lever and **no slack in the optimizer's effort budget to
  redistribute** — effort was already uniform across all families for all 9 rounds.
- **The netlist is route-dominated: 53–81 % route** on the four deepest families. Marginal logic
  cost is ~0.10 ns/level (`fullcore_route_timing.rpt:674-684`), but the dominant term is routing,
  which is what added mux fan-in on a *wide set-index bus* actually costs.
- One of the ten families is already **`DcachePlugin.stS2Valid → RobPlugin faultAddrStore_1`**
  at 0.061 ns, and another is the **`RobPlugin exc_fsFrameBase → Dtlb`** cluster. This design
  lands on both neighbourhoods.
- The congestion report (`synth/fullcore_congested_nets.txt`) already names
  `DcachePlugin_logic_earlyProbeVaddrs_3_reg` and an `LsEuPlugin_logic_sq/…ldoPaddrReg` net among
  the congested nets — the exact structures W11 and W23/W24 touch.

**Honest assessment: a 5-way mux on a 32-bit vaddr bus feeding a high-fanout BRAM address net,
added to a route-dominated netlist with 0.042 ns of margin and ten near-tied families, is more
likely than not to cost the 200 MHz sign-off on the first attempt.**

### 7.3 The recommended change to the plan

The spec lists "pre-mux the two walker `vaddr`s a cycle early" as *lever (iii)*, to be reached
for only if the gate moves. **R8 promotes it to the baseline implementation.**

Rationale: a walker command is registered and stable at the source (`TableWalker.descAddr`,
`DtlbPlugin.drainAddrReg` are already `Reg`s), so a 2-way ITLB/DTLB pre-mux one cycle early costs
**zero throughput** and returns the boundary mux to 4-way with one flop-fed leg — i.e. one leg
more than today, not two. Given §7.2, spending a free cycle to avoid a likely gate failure is
strictly better than discovering the failure across a 19-task plan and then bisecting it.

This also matters for §9: with a prefetch walker the boundary would otherwise go to **6-way**.
Pre-muxing all walker legs keeps it at 4 regardless of how many walkers exist.

### 7.4 Other costs, stated

- **N2 — L1D pollution.** Walker reads now allocate lines (WRITETHROUGH allocates; `doAllocate`
  excludes only INHIBITED). Page-table lines compete for capacity. Accepted — it is also what
  makes repeat walks cheap — but §6.3 measures it rather than assuming it is free.
- **N4 — a new stall coupling.** Both walker directions are refused for the duration of a
  maintenance walk (bounded, 512 iterations). Today the walker is immune. Bounded, not cyclic
  (§5.3).
- **N1 — descriptor RMW is still not atomic.** The walker computes `newByte` from a value read
  earlier and stores it at commit; a real 68040 uses a locked RMW cycle. **Unchanged** by this
  work, neither fixed nor worsened.
- **N3 — the `umQueueFull` cycle.** `walker.io.start` is blocked on a full 4-entry U/M queue
  (`ItlbPlugin.scala:229`, `DtlbPlugin.scala:238`); the queue drains only at commit; commit can
  require the translation the blocked walk would provide. Latent at HEAD, **unchanged** here.
- **Test blast radius: 24 test files migrated + 7 created + 1 deleted**, and a new sim helper
  `DcacheClientMemAgent` for the 8 DUT classes that host no `DcachePlugin` (W18). This is the
  bulk of the plan's mechanical effort and it is honestly budgeted there.

---

## 8. Interaction with `mmu-atc-m-bit-tracking` (`c629bec`) — R5

**Resolved: subsumed, do not merge.**

- `git merge-base --is-ancestor c629bec HEAD` → **NO**. `git branch --contains c629bec` lists
  exactly one branch. It is a dead end.
- `git merge-base --is-ancestor e0d2752 HEAD` → **YES**.

`c629bec` added `TlbEntry.m` plus a new `Tlb.io.invalidateHit` port and a `hitStale` predicate
that invalidated the hitting way to force a re-walk. Its own commit message admits the target
test still failed, blaming the coherency gap this document is about.

`e0d2752` (on HEAD) solves the same architectural gap with a strictly better mechanism:

| | `c629bec` (dead) | `e0d2752` (HEAD) |
|---|---|---|
| M shadow | `TlbEntry.m`, derived indirectly in the plugin | `TlbEntry.modified`, fed by a first-class `WalkRsp.modified` computed in the walker (`TableWalker.scala:220-224,255`) |
| Re-walk trigger | `hitStale` → invalidate the way, stall, re-walk via the miss path | `needsMRefresh` (`DtlbPlugin.scala:265`) falls through the response mux into the **same walker-capture path a real miss uses** — no invalidate, no cache-clearing |
| New `Tlb` port | requires `io.invalidateHit` | **not needed**; HEAD's `Tlb.scala` has none |
| Drain race | not addressed | `UmWriteQueue.pageQuery`/`pageHazard` (`UmWriteQueue.scala:47-59,71-73`) + `!umqPageHazard` on `_req.ready` |
| Target test | fails | passes |

`e0d2752`'s message explicitly describes and rejects `c629bec`'s shape (*"An earlier draft …
passed every directed unit test but never drained on the real full core"*).

**Merging `c629bec` would reintroduce `TlbEntry.m` alongside `TlbEntry.modified` and a port HEAD
deliberately does not need.** The branch should be deleted or tagged dead. This also removes the
spec §10.1 fallback clause ("the residual is `mmu-atc-m-bit-tracking`'s own M-bit logic fix") —
there is no such residual; the M-bit logic is correct on HEAD.

**This design neither conflicts with nor depends on that branch.** It is orthogonal and strictly
downstream of `e0d2752`.

---

## 9. Speculative ITLB prefetch — scoped, and gated on a measurement it will probably fail

**Status: SCOPED, NOT RECOMMENDED FOR IMPLEMENTATION until §9.6's gate passes.**

### 9.1 The proposal, and why it belongs in this document

When instruction fetch approaches a page boundary, or when the FTB/BTB supplies a branch target
on a different page, speculatively walk that page's translation into the ITLB ahead of demand, to
avoid the frontend bubble currently serialised behind a demand ITLB miss.

It belongs here — not in a separate document — for one hard reason: **a speculative walker is a
fifth requester on precisely the port §4/§5 arbitrate for four.** If the base design is built
assuming at most two walkers, adding a third later breaks the FIFO depth bound, the aging/force
argument, the exclusivity one-hot assertion (W29) and the boundary mux width all at once.
Designing them together costs one paragraph per decision; designing them apart produces two
incompatible arbitration schemes.

### 9.1a The stall being attacked is real and large — that much is not in doubt

Worth stating up front, because it is the strongest part of the case:

The I-side `TranslationService` is **combinational and non-elastic** (`Services.scala:482-487`),
unlike the D-side's tagged elastic `Stream` pair. On an ITLB miss the response mux drives
`_rsp.ready := False` (`ItlbPlugin.scala:435-440`), which is one of the four terms of
`cmdPort.ready := xlate.rsp.ready && !setBlocked && !s1Unresolved && pfAcceptOk`
(`IcachePlugin.scala:1600`, FSM default `False` at `:1118`). **The fetch command is held at the
Stream boundary and never accepted** — nothing enters S0, no MSHR is allocated, and the whole
`FetchAlignPlugin` ring back-pressures.

The walk is 3 dependent memory round trips plus ~6 fixed cycles, single-outstanding
(`TableWalker.scala:36,243`). At the bench's 70-cycle DDR that is **220+ cycles of completely
dead frontend**, *before* the demand line fill that follows it.

So the stall is real, large, and precisely attributable. **The open question is not whether an
ITLB miss is expensive — it is whether ITLB misses are frequent enough, and predictable far
enough ahead, to be worth attacking.** That is what §9.6 measures.

### 9.1b This overturns a standing prohibition — name it

`docs/superpowers/specs/2026-09-03-ftb-directed-prefetch-design.md:72,177` and
`IcachePlugin.scala:829-831` record a standing frontend rule:

> **P1: speculation never starts an ITLB walk.**

Today's I-cache next-line prefetcher obeys it structurally by having **no translation of its
own**: it inherits the demand access's already-resolved PPN and is therefore *same-page only*,
enforced by the page compare at `IcachePlugin.scala:1294-1295` and the page-end clamp at
`:1417-1420`. That same-page inheritance is exactly why cross-page prefetch is unavailable today.

**This proposal exists to relax that constraint, and F12 already recorded the two costs of doing
so:** either a **second `Tlb` lookup port** (`Tlb.scala:72-82` exposes exactly one) or **a new
non-walking hit-only probe with a new fault surface**. Neither is free, and §9.5 is where that
choice is made. A reader must not treat this as an incremental extension of the existing
prefetcher; it is a deliberate reversal of one of its founding rules.

### 9.1c ITLB geometry, for the pollution argument

Both TLBs take `Tlb.Default*` (`Tlb.scala:29-32`; constructed with no overrides at
`FullCoreSynth.scala:648-649`, `SocketTop.scala:109-110`):

- **32 entries, 4-way set-associative, 2 banks** ⇒ `setsPerBank = 4`.
  `bank = vpn[0]`, `set = vpn[2:1]`, `tag = vpn[19:3]` (`Tlb.scala:85-87`).
- Lookup is a shallow per-bank 4-way mux, **not a CAM**, combinational (`Tlb.scala:41-49,107-124`).
- **Replacement is round-robin per (bank,set)** — `victim` Vec at `:100`, advanced only on fill
  (`:130-145`), never updated on a hit. **Not LRU.**
- Entry fields: `vpnTag`, `ppn`, `writeProt`, `supervisor`, `cacheMode`, `modified`
  (`Tlb.scala:19-26`); `valid` held separately for one-cycle `invalidateAll` (`:91,148-150`).

**32 entries is small, and round-robin replacement has no protection against a wrong prefetch
evicting a live entry.** With 4 ways per set and a rotating victim pointer, a single wrong
prefetch to the same (bank,set) displaces a live translation with probability 1 at the pointer's
current position. This makes §9.5's pollution question sharper than it would be on an LRU cache.

### 9.2 The crux: a table search sets the U bit, and that is architecturally visible

A 68040 table search sets the page descriptor's **U** bit (and **M** on a write). That is a real
memory write to the page table — state software can and does observe. **A speculative walk that
performs it is not a prefetch; it is a bug.** Same for M.

**The good news: suppression is structural on this core, not a new gate to get right.**

`UmWriteQueue` (`src/main/scala/m68k040/mmu/UmWriteQueue.scala`) is a *commit-gated* structure.
Every entry is tagged with the **`robId` of the triggering instruction** (`UmWriteAlloc.robId`,
`:7`), and:

- `alloc` pushes **speculatively** (`:100-109`);
- `commit`/`commitB` mark an entry committed by matching that `robId` (`:90-98`);
- **`flush` discards every uncommitted entry** (`:111-117`) — *"speculative (uncommitted) entries
  are discarded → never written"* (`:28`);
- only the oldest **committed** entry drains (`:75-88`).

Even the I-side already works this way: `ItlbPlugin.scala:58` — *"for the I-side these carry the
fetching access's robId"* — and `:347-351` allocate with `umq.io.alloc.payload.robId := walkRobId`.

**A speculative prefetch walk has no triggering instruction and therefore no `robId`.** There is
no value to tag an entry with and nothing that could ever commit it. So the correct design is not
"add a suppress signal" but "**a prefetch walk must never call `umq.io.alloc` at all**", which is
enforceable by construction and by assertion.

### 9.3 Every side-effecting path, enumerated and suppressed

The commissioning brief asked for this enumeration explicitly. Each row states the mechanism, not
an intention.

| # | Side-effecting path | Today | On the speculative path |
|---:|---|---|---|
| **S1** | **U-bit / M-bit descriptor write** | `TableWalker` computes `rUmValid`/`rUmAddr`/`rUmByte` (`:210-232`) and returns them in `rsp.umWrite` | `WalkReq` gains `speculative: Bool`. `rUmValid := noFault && changes && !reqReg.speculative`. Suppressed **at the source**, so no consumer can re-enable it. |
| **S2** | **`UmWriteQueue` allocation** | `umq.io.alloc.valid := walker.io.done && walker.io.rsp.umWrite.valid && …` (`ItlbPlugin.scala:347`) | Falls out of S1 for free (`umWrite.valid` is low). **Belt and braces:** `&& !walkWasSpeculative`, plus a `GenerationFlags.simulation` assertion that `alloc.valid` is never high for a speculative walk. |
| **S3** | **U/M queue credit** | walk launch is gated on `!umQueueFull` (`ItlbPlugin.scala:229`) | A speculative walk **neither consumes nor waits on** the credit. It must not be admitted when the queue is full *for a different reason* either — see S9. |
| **S4** | **`pageHazard` participation** | queued-undrained entries stall later same-page accesses (`UmWriteQueue.scala:47-59`) | No entry is allocated ⇒ no hazard is raised. Nothing to do, but assert it. |
| **S5** | **Fault / exception signalling** | `rsp.fault` + `faultReason` drive real MMU fault delivery | A speculative walk's `fault` is **consumed and discarded inside the TLB plugin**. It must never reach the fault-delivery path. A faulting speculative walk installs **nothing** and is silently dropped. |
| **S6** | **Bus error on the descriptor fetch itself** | `loadRsp.payload.fault` (after this design) terminates the walk with a fault | Same as S5: dropped. This is the "a speculative walk that can fault is a bug" case, and it is the one that most needs a directed test — the descriptor address is computed from a *predicted* target and can be arbitrary. |
| **S7** | **ITLB entry installation** | a completed walk fills a TLB way (`ItlbPlugin`, cf. `DtlbPlugin.scala:342`) | **This is the contentious one — see §9.5.** A speculatively installed entry is architecturally *invisible* (the ATC is a cache; a later demand access would have walked to the same result). It is legitimate **provided** the entry is byte-identical to what a demand walk would install, **except** that its U bit was not written — which is exactly the state a demand access must later fix. See S8. |
| **S8** | **A later demand access treating the prefetched entry as authoritative** | a TLB hit skips the walk entirely | **The subtle killer.** If a prefetched entry is installed and a later demand fetch *hits* it, the U bit is **never** set, and software observing U-bits (e.g. a page-replacement daemon) sees a page that was executed but not marked used. **Resolution: prefetched entries carry a `speculative` bit; a demand hit on a `speculative` entry is treated as a miss-for-U-refresh**, re-walking non-speculatively exactly as `needsMRefresh` already does for M (`DtlbPlugin.scala:265,291`). That precedent is on HEAD, is proven, and costs one bit per entry plus one term in the hit predicate. **Without S8 the feature is architecturally wrong**, and it is the reason the ITLB cannot simply install prefetched entries silently. |
| **S9** | **D-cache pollution and fabric pressure** | n/a | A speculative walk's descriptor reads allocate L1D lines (N2, amplified). Mostly benign — they are the same lines a demand walk would pull — but wrong-path prefetches pull lines nothing will use. Bounded by §9.4's throttle. |
| **S10** | **ATC/ITLB replacement** | a walk evicts a way | A wrong prefetch evicting a live entry is a **real cost**, not a neutral one. See §9.5. |

**S8 is the finding that most changes the shape of this proposal.** Naively it looks like "install
and you're done"; in fact a correct implementation must make a prefetched entry *weaker* than a
demand entry, and the re-walk it forces on first demand use gives back part of the latency the
prefetch was meant to hide. The feature's true benefit is therefore **not** "eliminate the walk"
but "**warm the page-table lines in L1D so the demand re-walk is cheap**" — which, note, is a
benefit *created by the base design in this document* and is much weaker than the proposal's
premise. That should be reflected in the go/no-go bar.

### 9.4 Arbitration: a fifth requester, strictly non-forcible

Extending §5.3's scheme:

```
   base priority:  CORE-EXC  >  CORE-LS  >  {ITLB, DTLB} (rr)  >  PREFETCH
   forced:         CORE-EXC  >  forced walker  >  CORE-LS  >  other walker  >  PREFETCH
```

Decisions, stated as they would be numbered in an implementation spec:

- **P1.** The prefetch walker is a distinct requester with `force` **hardwired `False`** and no
  aging counter. It can never preempt `CORE-LS`, never outrank a demand walker, and is therefore
  incapable of delaying any demand access. This is what makes "strictly best-effort" a structural
  property rather than a tuning parameter.
- **P2.** `prefetchLoadAdmit = grant(PF) && !ldBusyExc && !quiesceHold && !anyDemandWalkerReq &&
  (ldOwnerFifoOccupancy <= 1)`. The last term reserves FIFO depth for demand traffic; without it a
  prefetch can fill the FIFO and *thereby* delay a demand walker via `!ldOwnerFifo.full`.
- **P3.** **Preemptible/abandonable.** A speculative walk in flight is **dropped** the moment a
  demand walk is requested on the same walker, on `ftqFlush`, or on `excActive`. "Dropped" is
  cheap because a speculative walk has no architectural residue (S1–S6): the FSM returns to IDLE
  and the in-flight `loadRsp` is consumed and discarded by the FIFO tag, which already
  distinguishes it. This requires a **fifth** `LdRspTag` value (`PREFETCH`), widening the tag from
  2 bits to 3 — the one non-free structural cost this proposal adds to the base design.
- **P4.** **The tag one-hot assertion (W29) must be extended to five predicates**, not four. This
  is exactly the invariant the base design's round-4 review found broken once already; adding a
  requester without extending it would re-open C-R4-1 through a new door.
- **P5.** **Throttle.** At most one speculative walk in flight, globally, plus a saturating
  "speculative walks issued per N cycles" limiter. Given the documented read-side MLP≈1 problem,
  an unthrottled prefetcher is a fabric-pressure regression waiting to happen.
- **P6.** **Boundary mux.** With §7.3's pre-mux adopted, prefetch adds **zero** legs at the
  D-cache boundary (it joins the pre-muxed walker group one cycle early). Without §7.3 it takes
  the boundary mux to 6-way. **This is a second, independent reason to adopt §7.3 in the base
  design.**
- **P7.** **Ordering.** This lands **after** the base design is merged and its synth gate passed —
  never concurrently. It is a strict extension of an arbitration layer that must first exist and
  be measured.

### 9.5 ITLB pollution, and the buffer-vs-install question — with an existing structure that fits

A wrong prefetch that evicts a live ITLB entry is a net loss, and §9.1c's round-robin
replacement over 4 ways gives it no protection. Three options, and the third is new information:

- **(a) Install directly into the `Tlb` array, with the S8 speculative bit.** Cheapest in wiring;
  reuses the `needsMRefresh` precedent exactly. **Cost: pollution is real and bounded only by the
  throttle**, on a 32-entry round-robin structure. Not recommended.
- **(b) A new separate prefetch buffer** (1–2 entries), promoted into the ITLB only on a demand
  hit. No pollution at all. Cost: a second lookup structure in the fetch path — a new term in the
  ITLB hit predicate, in a timing neighbourhood §7.2 says has no margin — plus, per F12, either a
  second `Tlb` lookup port or a new hit-only probe with a new fault surface.
- **(c) Reuse the ITLB's existing 1-entry sticky walk-result latch.** **This is the option the
  code already suggests.** `ItlbPlugin.scala:149-154` declares
  `latchValid`/`latchVpn`/`latchPpn`/`latchSup`/`latchCmode`/`latchFault`; it is matched by
  `latchMatch = latchValid && (latchVpn === tlbKey(_req.vpn))` (`:160`), served directly in the
  response mux (`:430-434`), documented as deliberately sticky-unlike-the-DTLB (`:297-313`), and
  already invalidated on `umFlush` (`:320`), `flushAll`/PFLUSHA (`:325`) and poisoned-walk expiry
  (`:314-315`).

  **It is, structurally, already a 1-entry translation prefetch buffer that sits in front of the
  array and does not pollute it.** A speculative walk that terminates by writing the latch —
  and *not* the array — gets option (b)'s zero-pollution property at option (a)'s wiring cost,
  because the lookup path, the match term, and every invalidation hook already exist and are
  already in the frontend's timing budget.

**Recommendation: (c), if anything is built at all.** It needs the S8 speculative bit added to
the latch (one flop) so a demand hit on a speculatively-filled latch forces the non-speculative
re-walk that sets U. It does **not** need a second `Tlb` port, which is the specific cost F12
was avoiding — so it also weakens P1's original objection.

**But do not build any of this before §9.6's gate.** Deciding between (a), (b) and (c) in
advance would be designing a structure for a benefit not yet shown to exist. Recording that (c)
exists is worth it precisely because it changes the *cost* side of the eventual decision.

**Capacity note that cuts against the whole proposal:** a 1-entry latch holds exactly one
speculative translation. If the workload's ITLB miss pattern is bursty (several new pages in
quick succession), a 1-entry buffer captures almost none of it, and anything larger reopens the
pollution and timing costs. §9.6's G0 must report the *distribution* of misses, not just a rate.

### 9.5a The two triggers are different proposals with different priors — do not conflate them

The brief names two trigger conditions. They must be evaluated **separately**, because one of
them already has adverse measured evidence and the other has none.

**Trigger T-FTB — a predicted branch target on a different page.**
The hand-off point is unambiguous and already instrumented: `targetHoldPc` / `targetHoldValid`
(`FetchAlignPlugin.scala:288-290`, written `:631-638`, cleared on `ftqFlush` `:1375`, **already
`simPublic` at `:339`**). It is a registered, stable 32-bit target VA — take `targetHoldPc(31
downto 12)` as the VPN — and it is high *exactly* when the frontend could not issue the target,
i.e. exactly when lead time might exist. No new timing path in the frontend is needed to produce
the hint: it is a registered `Flow` off an existing register, which is precisely decision F3/F4
of the FTB prefetch design.

**However, the lead-time distribution of that exact signal has already been measured, and it
killed the FTB-directed L1I prefetch.** T3 of that campaign histogrammed `targetHoldValid` rise →
that target's `ic.cmd.fire` and found the **mass concentrated at 0–2 cycles**; the design was
abandoned at zero RTL cost (`3a35bcfd`, *"T3 kills FTB-directed prefetch at zero RTL cost"*).
A walk is 3 dependent memory round trips. **A 0–2 cycle lead time cannot hide a 3-round-trip
walk — it cannot hide a single round trip.** This is directly transferable evidence against
T-FTB, measured on this core, this month, on the identical signal.

**Treat T-FTB as presumptively dead.** It needs a positive result on a re-run of that same
histogram *under an MMU-enabled harness* to be revived, and there is no reason to expect the
distribution to move.

**Trigger T-SEQ — sequential fetch approaching a page boundary.**
Different mechanism, and **no adverse evidence exists**. Lead time here is not set by branch
prediction but by how far ahead of the boundary the trigger fires: fetch advances
`fetchPc := cmdWindowPc + 8` (`FetchAlignPlugin.scala:621`), so a trigger at N bytes before the
page end gives roughly `N/8` fetch cycles of lead, tunable by construction. To hide a walk one
needs `N` large enough that `N/8` exceeds the walk latency — at 70-cycle DDR that is ~220 cycles
⇒ `N ≈ 1760 bytes`, i.e. **the trigger would have to fire nearly half a page ahead**, which in
turn means most triggers fire on pages the fetch stream never reaches.

That tension — long enough lead requires firing so early that accuracy collapses — is the real
question for T-SEQ, and it is measurable (G2b below) before any RTL.

Note T-SEQ has a structural advantage the existing next-line prefetcher lacks and a
corresponding structural cost: the existing prefetcher is *same-page only* by inheriting the
demand PPN (`IcachePlugin.scala:1294-1295,1417-1420`), so T-SEQ is exactly the case it clamps
itself out of — which is why it needs a walk, which is why it collides with P1 (§9.1b).

### 9.6 The gate — pre-registered, and the expected outcome is NO-GO

**Methodology is taken wholesale from `docs/superpowers/specs/2026-09-03-ftb-directed-prefetch-design.md`,
whose T0–T3 measurement tasks authorised its RTL and whose T3 then killed it.** That is the
template: *measure first, at near-zero RTL cost, against a bar registered before the runs.*

#### 9.6.1 The blocking prerequisite: the IPC bench cannot measure this today

**This must be settled before anything else, and it is a strictly larger lift than the FTB
campaign's was.**

`IpcBenchSpec.scala:613` sets `dut.ctrl.logic.mmuEnable #= k.copybackDtt` — **the MMU is off for
every kernel except the two `copybackDtt` ones** (`:901`, `:930`). And those two configure
`itt0 #= 0x00FFC000` / `dtt0 #= 0x00FFC020`, i.e. **match-all transparent translation** (`:616-619`;
the comment at `:610-612` says so outright: *"VA==PA, no walker"*). Since a TTR hit is resolved
before the TLB (`ItlbPlugin.scala:409-419`) and `needWalk` requires `mmuEnable && !ttHit`
(`:161`):

> **The current IPC bench produces exactly ZERO ITLB walks in every kernel.**

The FTB campaign's T1/T3 could reuse the bench unchanged. **This one cannot.** G0 must first
build an MMU-enabled harness: real `urp`/`srp` page tables, `mmuEnable` on, TTRs off or narrowed.
That is real work, and it is the honest entry cost of even *asking* the question.

**This is itself a reason to be sceptical of the proposal's premise:** the reason nobody has
noticed ITLB stalls is partly that no benchmark in this repo has ever exercised an ITLB walk.

#### 9.6.2 The telemetry needed is otherwise already present

Once an MMU-enabled harness exists, G0–G2 need **no new RTL**:

| Need | Existing hook |
|---|---|
| ITLB miss count | `ItlbPlugin.scala:227` — **`missPending.simPublic()`**, a sticky reg set on miss capture (`:235`), cleared on `walker.io.done` (`:277`). **Rising edges = miss count.** |
| ITLB walk stall cycles | the same signal's **high-cycle count**; per-episode length gives the **latency distribution** |
| "frontend blocked on translation this cycle" | `IcachePlugin.scala:619-621` — **`xlateReadyDbg`**, a `simPublic` mirror of `xlate.rsp.ready`; AND it with `cmdPort.valid`. This **cleanly separates translation stall from the other three `cmdPort.ready` terms** (`setBlocked`, `s1Unresolved`, `pfAcceptOk`) — which is exactly the attribution G1 needs |
| branch-target lead time | `targetHoldValid`/`targetHoldPc`, `simPublic` at `FetchAlignPlugin.scala:339` |
| MMU configuration | `MmuControl.scala:113-128` — `mmuEnable`, `urp`, `srp`, `itt0/1`, `dtt0/1` all `simPublic` and pokeable |

There is **no free-running counter anywhere in `mmu/`** — everything is a per-cycle observable, so
the counting goes in the harness's `cd.onSamplings` block (`IpcBenchSpec.scala:506-530`) with
fields added to `IpcResult` (`:396-410`). That is ~4 lines each, exactly the FTB campaign's T1
pattern.

#### 9.6.3 The tasks

| # | Task | RTL? | Decides |
|---|---|---|---|
| **G0a** | **Build the MMU-enabled bench harness** (§9.6.1). Real page tables, `mmuEnable` on, TTRs narrowed so the kernels' text and data are genuinely walked. Sanity gate: `missPending` rising edges > 0. | harness only | Whether the question is askable at all. |
| **G0b** | **ITLB miss rate and burst distribution**, per kernel **and on a boot trace** (`memory/boot-investigation-consolidated-2026-08-27.md` is the trace source). Report misses per 1k instructions, total walks, and the **inter-miss gap distribution** (§9.5's 1-entry-buffer capacity question). | no | Whether a target population exists. |
| **G1** | **Frontend stall cycles attributable to ITLB miss latency**, via `xlateReadyDbg && cmdPort.valid`, reported **against total frontend stall cycles** so the share is visible. Not "cycles a walk was in flight" — cycles the frontend was starved *and translation was the binding term*. | no | The size of the pool. Analogue of the FTB campaign's T1, and the same largest-unknown. |
| **G2a** | **T-FTB lead-time histogram** — `targetHoldValid` rise → that target's demand fetch, restricted to **cross-page** targets, under the G0a harness. | no | **GO/NO-GO for T-FTB.** Prior: already measured at 0–2 cycles (§9.5a). |
| **G2b** | **T-SEQ lead-time curve** — for each candidate trigger distance `N ∈ {256, 512, 1024, 2048}` bytes before a page end, the joint distribution of (lead cycles actually realised, fraction of triggers whose page is ever fetched). | no | **GO/NO-GO for T-SEQ**, and the trigger distance if it survives. |
| **G3** | **S8 discount.** Recompute the benefit after subtracting the demand re-walk that S8 forces (§9.3). What remains is **L1D warming only** — a benefit the base design already delivers. | no | Whether the honest benefit survives its own correctness requirement. |
| **G4** | RTL, only if G0–G3 pass, and only after Stage B has landed and passed its synth gate. | yes | — |

#### 9.6.4 Anti-vacuity discriminator

Per the FTB campaign's §5.2 lesson — a discriminator must be **provably zero on parent RTL**, and
observed at *allocation*, not at `ar.fire`:

> **`specWalkNoDemand`** — a table walk launched with no corresponding demand `cmdPort.valid` for
> that VPN. This is **structurally zero** on current RTL by rule P1 (§9.1b): today every walk is
> demand-triggered. Any non-zero count proves the feature is doing something; a zero count with
> the feature enabled proves it is inert.

Explicitly **banned** as a coverage metric, by direct analogy to the FTB campaign's ban on
`pfTargetSeeds / ftbApplies`: **"speculative walks that later hit"** divided by "speculative walks
issued". A speculative walk to the page the demand stream was about to fetch anyway scores 1.0
while saving zero cycles, because the demand walk would have happened at the same time.

#### 9.6.5 Pre-registered NO-GO conditions

Registered now, before any run. **Any one of these kills the proposal:**

1. **ITLB walks are rare.** If G0b shows I-side walks below a stated floor on a boot trace, this
   is dead on arrival. **This is the expected outcome**: page tables are hot and small (the base
   design's own premise), 32 ITLB entries covering 4 KB pages is 128 KB of instruction reach —
   ample for a boot path's steady state — and after Stage B lands, the walks that do occur are
   largely served from L1D anyway.
2. **G2a's T-FTB mass sits at 0–2 cycles.** Same failure mode that killed the FTB work, on the
   same signal. Presumptively already true.
3. **G2b shows no `N` with both adequate lead and adequate accuracy** — the §9.5a tension.
4. **G1 shows ITLB-attributable stalls are a small fraction of frontend stalls.** If I-cache
   misses dominate — which the FTB campaign found for the I-side generally — effort belongs there.
5. **G3's residual after the S8 discount is below the noise floor** (~1 % bench jitter, unpinned).
6. **G0a cannot be built at acceptable cost.** A harness that needs so much scaffolding that it
   no longer resembles a realistic workload cannot answer the question.

Timing/area bar, should G4 ever be reached — inherited from §7.2's picture, and note the proposal
adds a **5-valued** `LdRspTag` (3 bits, P3) and a fifth admission predicate (P4):

7. `SIGNOFF_200MHZ_RESULT MET_200` with WNS ≥ 0 at a real 5.000 ns against the Stage B baseline,
   or WNS no worse and failing-endpoint count no higher.
8. No new path traversing `loadCmdPort_payload_vaddr → cmdSet → rdSet` (which §7.3's pre-mux
   should make structurally impossible).
9. No new term in the ITLB hit predicate that appears in the top-100 failing paths (§9.5's
   option (c) is chosen partly to make this achievable).

**A well-argued "do not build this" is a valid outcome and, on the evidence available, the likely
one.** The base design in §§1–8 already captures most of what this proposal is reaching for: it
makes walks cheap by putting page-table lines in L1D, which is a larger and more certain win than
issuing them earlier.

**Note the interaction that makes sequencing non-negotiable:** G0–G3 should be run *after* the
base design lands, because the base design changes the very quantity being measured (walk cost).
Measuring walk-latency stalls on today's fabric-serialised walks would overstate the prefetch case
by exactly the amount the base design is about to remove.

---

## 10. Staged plan

The 19-task plan at `docs/superpowers/plans/2026-08-18-walker-dcache-passthrough-implementation-plan.md`
is **structurally sound and should be executed, not rewritten**. It has a naming contract, a file
inventory, per-task gates, a self-review coverage table, and correct global constraints
(`DcachePlugin.scala` zero edits; no `Global.scala` key; no `AxiIds` renumbering; port-surface
golden enforced except in Task 15).

The following **amendments** are required before execution. They are deltas to that plan, not a
replacement for it.

### Stage A — corrections to apply to the existing plan (do first, zero RTL)

| # | Amendment | Plan location |
|---:|---|---|
| **A1** | **Rewrite the "Plan-level risk" section.** The §11.1-item-9 standalone fix **landed** as `072e97a`. Take the plan's own "lands BEFORE Task 4" branch: Task 4 Step 0 re-derives W30's trace on top of `072e97a` and keeps the stronger predicate. **Do not weaken W30 part (2).** | plan `:29-41` |
| **A2** | **Delete `mmu_atc_write_hit_sets_modified` as the acceptance test.** It passes at HEAD (`e0d2752`) and passes for a reason (`DE=0`) that makes it structurally incapable of showing the bug. Replace with §2.3's directed test. | plan `:191,222,3474`; spec §10.1 |
| **A3** | **Delete the `mmu-atc-m-bit-tracking` fallback clause.** `c629bec` is subsumed (§8); there is no residual M-bit logic fix. | spec §10.1 |
| **A4** | **Re-derive the W27 site table wholesale** against `LsEuPlugin.scala` at execution HEAD. Add rows for §3.5's six new/changed sites. **Re-point the `:2027` prescription** to the current one-term header (`:2821`) and re-write its mutation proof (§3.1). | plan Tasks 4–7; spec §4.2.7 |
| **A5** | **Promote §7.3's walker-`vaddr` pre-mux from fallback lever to baseline**, in Task 7's step list. Records: keeps the boundary mux at 4-way, costs zero throughput, and is a precondition for §9 ever being cheap. | plan Task 7 |
| **A6** | **Re-derive §6.5's store-liveness argument** against `storePort.ready`'s current form (`DcachePlugin.scala:2614-2618`), which gained three terms since the spec was written. | spec §6.5 |

### Stage B — the base implementation (the existing 19 tasks, unchanged in shape)

Executed as written, with Stage A's amendments folded in. Its own gate structure:

- **T1 — baselines.** Synth checkpoint #1 (uncontended, `POSTROUTE_ROUNDS=9`), `test-fast`,
  **fresh** ported-corpus and fuzz baselines. The corpus is 899 tests and was re-triaged
  2026-09-03 (`a9f7ddf`); the plan must capture its own numbers, never inherit a quoted figure.
- **T2–T10 — RTL**, each ending in `check_socket_netlist.py` + `test-fast`.
- **T11 — synth checkpoint #2** (post-mux). **This is the real gate**, per §7.2. Pass criterion is
  a top-10 failing-path list that is *shape-identical* to baseline, not merely a number —
  specifically that no new path traverses `loadCmdPort_payload_vaddr → cmdSet → rdSet`.
- **T12–T18 — test migration** (24 files) and the socket-plan rework.
- **T19 — final gate:** synth checkpoint #3 plus full corpus and fuzz sweeps, both in a
  `git worktree`, diffed against T1.

**Added gate, before T2:** §2.3's directed coherency test must be written and **shown to fail at
HEAD**. If it cannot be made to fail, the correctness premise is wrong and the whole plan reverts
to a simplification-and-FMax-risk trade, which is a much weaker case and should be re-decided.

### Stage C — speculative ITLB prefetch (only if Stage B has landed)

Exactly §9.6.3's G0a → G4, in order, against §9.6.5's pre-registered NO-GO.

Two sequencing constraints, both non-negotiable:

- **G0a (the MMU-enabled harness) is the real first task**, not a preliminary. §9.6.1: the bench
  produces zero ITLB walks today, so nothing downstream is measurable without it.
- **G0b–G3 must run after Stage B lands**, because Stage B changes the very quantity being
  measured. Measuring walk-latency stalls on today's fabric-serialised walks (§1.2: three
  exclusive `axi_d` grant holds per walk) would **overstate the prefetch case by exactly the
  amount Stage B is about to remove.**

Expected outcome: NO-GO at G0b or G2a. Recording that, with the numbers, is a successful
completion of Stage C.

---

## 11. Recommendation

**Build the base design (Stages A + B). Do not build the prefetch (Stage C) unless its gate
passes, and expect it not to.**

The base design is justified on **correctness and simplification alone**, without any performance
claim:

- The coherency hazard is **real, bidirectional, and armed** — prevented today only by a global
  cache-disable that exists by reset accident and that any real supervisor turns off (§2).
- It removes **78 of 205 top-level ports (38 %)**, two AXI masters, two ID namespaces, and two
  `AxiDMerge` owner tiers (§1.3).
- It deletes a hand-rolled duplicate of `DcacheByteLane.extract` that has already caused one
  silent-corruption bug (W16).
- It is a **68040 compatibility fix**: a real 68040 performs table searches through the data cache.

The performance case is *plausible and structurally motivated* (§1.2's serialising-grant argument
is not speculative) but **is not claimed**, and §6.3 defines how it would be measured against a
pre-registered bar.

**The honest counterweight is FMax** (§7.2). This is not a cheap change on this netlist, and the
probability of failing the 200 MHz gate on the first attempt is material. §7.3's pre-mux
mitigation should be adopted from the first commit rather than held in reserve. If, after that
mitigation, T11's gate still fails and the §5 lever list is exhausted, the correct decision is to
**hold the design and revisit after the next FMax campaign**, not to weaken any of W23/W24/W27/W30
to buy timing — those are the correctness core, and three separate review rounds were spent
finding that weakening them re-opens silent-corruption bugs.

**On the prefetch proposal (§9):** the stall it attacks is genuine and large — the I-side
translation interface is non-elastic, so an ITLB miss freezes the whole fetch ring for 3 dependent
memory round trips, 220+ cycles at DDR latency (§9.1a). But three independent facts point to
NO-GO, and all three are evidence rather than intuition:

1. **The trigger signal T-FTB would use has already been measured on this core this month, and its
   lead time is 0–2 cycles** — enough to hide nothing, let alone three round trips. That
   measurement killed a sibling feature (§9.5a, R11).
2. **No benchmark in this repo has ever executed a single ITLB walk** (§9.6.1, R12). The absence of
   observed ITLB stalls is not evidence they are rare, but it does mean the premise is entirely
   unmeasured, and measuring it requires building a harness that does not exist.
3. **Correctness forces most of the benefit back out.** S8 (§9.3) requires a prefetched entry to
   be re-walked on first demand use so the architecturally-visible U bit is set. What survives is
   **L1D warming of the page-table lines** — which is exactly what Stage B delivers anyway, more
   cheaply and unconditionally.

So the honest position is: **Stage B probably subsumes the prefetch proposal's realisable
benefit.** Scope it, gate it, run G0a/G0b/G2a, and expect to record a NO-GO — at zero RTL cost,
which is the whole point of the FTB campaign's methodology.

**One result is already banked**: this design review found and shipped a live silent-corruption
bug (`072e97a`) before writing a line of its own RTL (§3.2).

---

## 12. Evidence index

Everything asserted above, with its check:

| Claim | Evidence |
|---|---|
| Walker has a private read AXI, ID 2 | `mmu/TableWalker.scala:38-47`, `cache/AxiIds.scala:96` |
| U/M write on a private write AXI, ID 3 | `mmu/DtlbPlugin.scala:473-489`, `mmu/ItlbPlugin.scala:383-…`, `AxiIds.scala:98` |
| No snoop port in the D-cache | `grep -c snoop DcachePlugin.scala` → 0 |
| D-cache never invokes the MMU (no cycle) | `grep -c 'TranslationService\|MmuControlService' DcachePlugin.scala` → 0 |
| Exactly one load port, one store port | `DcachePlugin.scala:84`, `:89` |
| Probe-token gate on `loadCmdPort.ready` (the deadlock) | `DcachePlugin.scala:1546` |
| `probeCancelAll` hook intact | `LsEuPlugin.scala:1158` |
| `normalReqArm`/`splitReqArm` hooks intact | `LsEuPlugin.scala:2502-2504` |
| `excLoadCmdReady` still unconditional (W23) | `LsEuPlugin.scala:2909` |
| `sq.io.drain.ready` still unconditional (W24) | `LsEuPlugin.scala:295` |
| Exception load header lost `excActive` (§3.1) | `LsEuPlugin.scala:2821` vs spec `:2027`; commit `6028a6c`; tripwire `:2815-2820` |
| `DLoadRsp`/`DStoreCmd` have no token | `DcacheTypes.scala:67-71`, `:83-96` |
| Reset `CACR = 0` ⇒ all-INHIBITED | `SystemState.scala:53`, `RobPlugin.scala:2200`, `LsEuPlugin.scala:2429-2430` |
| cacheMode purely from CM[6:5] | `DtlbPlugin.scala:270-298`, `TableWalker.scala:209`, `MmuTypes.scala:73`, `IcacheTypes.scala:76-86` |
| COPYBACK reachable and implemented | `DcachePlugin.scala:1163,1224,1221-1224,1108`; `StoreQueue.scala:420,626-629` |
| TTR checked before TLB (why the existing posture can't exhibit it) | `DtlbPlugin.scala:277` vs `:291`; `MmuTypes.scala:144-157` |
| Existing copyback posture constants | `fuzz/PortedTestRunner.scala:15-25`, applied `:243-255` |
| `mmu_atc_write_hit_sets_modified` passes | `e0d2752` verification block; `a9f7ddf` residual triage |
| `c629bec` not an ancestor; `e0d2752` is | `git merge-base --is-ancestor` |
| Spec §11.1 item 9 fixed | `072e97a`; `ExceptionUnit.scala:410-426` |
| 78/205 walker ports | `tools/socket/fullcore_ports.golden` (`grep -c itlbAxi` = 39, `dtlbAxi` = 39) |
| `AxiDMerge` owners and serialisation | `socket/AxiDMerge.scala:26-30`, `:60-96` |
| FMax +0.042 ns, 0.053 ns band, route-dominated | `.superpowers/sdd/progress-fmax-closure-fanout-2026-08-17.md:955-1000` |
| Congested nets in the touched neighbourhood | `synth/fullcore_congested_nets.txt` |
| `UmWriteQueue` is commit-gated and robId-tagged | `UmWriteQueue.scala:6-10,28,75-117` |
| I-side U-writes carry a fetching robId | `ItlbPlugin.scala:58,347-351` |
| Walk launch gated on `!umQueueFull` | `ItlbPlugin.scala:229`, `DtlbPlugin.scala:238` |
| `needsMRefresh` precedent for S8 | `DtlbPlugin.scala:265,291` |
| FTB prefetch NO-GO precedent | `docs/superpowers/specs/2026-09-03-ftb-directed-prefetch-design.md`; `cpu040` `3a35bcfd` |
| I-side translation is non-elastic; miss holds the fetch command | `Services.scala:482-487`; `ItlbPlugin.scala:435-440`; `IcachePlugin.scala:1600`, default `:1118` |
| Walk = 3 dependent round trips, single-outstanding | `TableWalker.scala:36,126-240,243` |
| ITLB = 32 entries, 4-way, 2 banks, **round-robin** replacement | `Tlb.scala:29-32,85-87,100,130-145` |
| ITLB has a 1-entry sticky walk-result latch (the natural prefetch buffer) | `ItlbPlugin.scala:149-154,160,297-313,320,325,430-434` |
| Only one `Tlb` lookup port exists | `Tlb.scala:72-82`; cost recorded at ftb-design `:177` (F12) |
| Standing rule P1: speculation never starts an ITLB walk | ftb-design `:72,177`; `IcachePlugin.scala:829-831` |
| Existing prefetcher is same-page-only by inheriting the demand PPN | `IcachePlugin.scala:1294-1295,1417-1420` |
| `targetHoldPc`/`targetHoldValid` registered and already `simPublic` | `FetchAlignPlugin.scala:288-290,339,631-638,1375` |
| T-FTB lead time already measured at 0–2 cycles | ftb-design `:283`; kill commit `3a35bcfd` |
| **Bench produces zero ITLB walks in every kernel** | `IpcBenchSpec.scala:610-619,613,901,930`; `ItlbPlugin.scala:161,409-419` |
| ITLB miss/stall telemetry already exists (zero RTL) | `ItlbPlugin.scala:227,235,277` (`missPending`); `IcachePlugin.scala:619-621` (`xlateReadyDbg`) |
| No free-running counter in `mmu/`; counting goes in the harness | `IpcBenchSpec.scala:396-410,506-530` |
| Fabric read-side 4.88× (with its own caveat) | `docs/fabric_concurrency_contract.md:77`, caveat `:407` |
| Corpus is 899 tests, re-triaged 2026-09-03 | `a9f7ddf` |
