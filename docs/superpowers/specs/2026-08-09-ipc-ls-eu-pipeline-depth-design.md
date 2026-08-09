# IPC push: LS EU pipeline depth — decoupling the load back-half from S1 (design)

**Status**: IMPLEMENTED CHECKPOINT on `codex/ipc-dcache-vipt`. Slice 1's
front/back structural split has been integrated with the tokenized VIPT cache
interface, and Slice 2 has enabled `earlyFree`. Directed simulation proves a
younger store issues, translates, and reaches SQ allocation while an older cold
load is still awaiting its cache response. Focused LS/cross/exception/D-cache
regressions pass. Seeds 1–3 cut `load-stream` cycles by 43.9% (ideal memory) and
41.2% (`l2:5:70`); a full-suite seed-1 pair cuts aggregate cycles by 12.8% and
8.9%. The PM-serialized routed FMax/area gate remains pending, so the final
accept/reject decision in §8.4 is not yet made.
**Scope**: `src/main/scala/m68k040/execute/LsEuPlugin.scala`,
`src/main/scala/m68k040/cache/DcachePlugin.scala` (read-only contract),
`src/main/scala/m68k040/execute/StoreQueue.scala` (read-only contract).
**Explicitly out of scope**: `src/main/scala/m68k040/frontend/`,
`src/main/scala/m68k040/decode/` — a separate initiative (fetch-directed BTB,
`2026-08-09-ipc-fetch-directed-btb-design.md`) owns those directories
concurrently. Nothing here reads, depends on, or references them.

**Initiative**: IPC push, under the binding user goal *"push ipc as high as
reasonably possible without blowing up lut count or crashing fmax."*

**Ledger**: `.superpowers/sdd/progress-ipc-push-2026-08-09.md` — see the
"LS EU pipeline depth" grounding entry and the "LS EU compValid quick win —
REJECTED" entry, which is the single most important input to this design.

---

## Contents

- §0 Context: what was measured, and the one thing the benchmark could not see
- §1 Goals / Non-goals
- §2 The measured cycle budget, per state, against real RTL
- §3 Architecture — Stage 1 (`RECOMMENDED`): the late split at `RESOLVE`→`LAUNCH`
- §4 Architecture — Stage 2 (`CONDITIONAL`, gated on Stage 1's result): the full S1 split
- §5 Correctness argument
- §6 FMax treatment and the mandatory census gate
- §7 Area
- §8 Verification plan (binding)
- §9 Alternatives considered and rejected
- §10 Honest status summary

---

## 0. Context: what was measured, and the one thing the benchmark could not see

The 2026-08-09 grounding pass established, by cycle-by-cycle Verilator trace,
that the LS EU is a strictly non-pipelined one-µop-at-a-time FSM:

```scala
// LsEuPlugin.scala:1158
issuePort.ready := !busy && !s1Valid && !compValid
```

`issue.valid && !ready` on **68.5%** of cycles in the `load/store` kernel, with
the ROB never above 20/64 — the EU is the sole binding constraint. Measured
initiation intervals: **store = 4 cycles, forwarded load = 6 cycles**.

That grounding also flagged its own blind spot, and it is the reason this design
exists at all:

> **No kernel in the suite measures non-forwarded D-cache load throughput.**
> `load/store` and `mixed` are BOTH same-address store-then-load-back patterns.
> `LAUNCH`/`WAIT`/`WAIT_A`/`WAIT_B` occupancy across the entire `load/store`
> kernel is **exactly 0 cycles** — SQ forwarding fires on 25 of 25 RESOLVE
> cycles. Not one load ever reaches the D-cache.

The grounding's headline projection (~+15% aggregate from "an S2 stage") was
therefore an upper bound measured against a benchmark that structurally cannot
exercise the mechanism that would dominate real code.

### 0.1 The gap is now closed — `load-stream` (landed with this spec)

`src/test/scala/m68k040/bench/IpcBenchSpec.scala` gains a kernel `load-stream`:

- **zero stores anywhere** ⇒ the SQ is permanently empty ⇒ `sq.io.fwd.rsp.hit`
  and `.stall` are always False ⇒ **every** load takes the genuine cache path
  `RESOLVE` → `LAUNCH` → `WAIT` → `dcache.loadRsp`. This is the exact path the
  two existing LS kernels skip entirely.
- 6 loads per iteration to 6 **distinct** long-aligned addresses over two 16-byte
  lines (`0x4000`..`0x4014`), each into a **different** destination register ⇒ no
  load-to-load dependency ⇒ the measurement is **throughput** (initiation
  interval), not load-use latency.
- a **tight backward loop** (not `load/store`'s straight-line unroll) so the body
  stays I-cache resident. This matters: under `IPC_MEM=l2:5:70` a long
  straight-line unroll becomes I-fetch-bound and would mask the D-side effect.
- working set 2 lines / 32 bytes ⇒ every access after the first iteration is an
  L1D **hit**.

### 0.2 Measured baseline on HEAD (`52608cf`), 3 pinned seeds, both memory models

| kernel | zero s1 | zero s2 | zero s3 | l2:5:70 s1 | l2:5:70 s2 | l2:5:70 s3 |
|---|---|---|---|---|---|---|
| dependent-ALU | 401 | 401 | 401 | 1048 | 1049 | 1063 |
| independent-ALU | 214 | 214 | 214 | 1119 | 1125 | 1143 |
| load/store | 1034 | 1052 | 1080 | 1523 | 1513 | 1537 |
| **load-stream (NEW)** | **3269** | **3275** | **3268** | **3426** | **3428** | **3428** |
| branchy | 725 | 725 | 725 | 790 | 789 | 725 |
| hot-loop | 708 | 708 | 708 | 774 | 773 | 708 |
| mixed | 559 | 565 | 545 | 1074 | 1086 | 1071 |
| call-return | 2358 | 2354 | 2334 | 2900 | 2981 | 2960 |
| **AGGREGATE** | **9268** | **9294** | **9275** | **12654** | **12744** | **12635** |

`load-stream` IPC = **0.147** (zero) / **0.140** (l2:5:70) — **the worst kernel
in the suite by a wide margin** (next worst, `load/store`, is 0.280 / 0.190).
481 macro-instructions in 3269 cycles.

Three properties make this kernel a good instrument, and all three are measured,
not asserted:

1. **Seed-stable to ~0.2%** (3269 / 3275 / 3268). Paired A/B deltas above ~0.5%
   are real.
2. **Nearly memory-model-insensitive**: zero → l2:5:70 costs only **+4.9%**
   (3269 → 3426). Compare `load/store` at **+47%** (1034 → 1523) and
   `independent-ALU` at **+423%**. The tight-loop construction did its job: this
   kernel isolates the LS EU + L1D-hit path under *both* models, which is exactly
   what the compValid rejection found `load/store` could not do (it was
   bit-identical across all 8 seeds under `l2:5:70` — completely insensitive to
   LS EU initiation interval).
3. **~99% LS-EU-bound**, verified against the cycle model in §2: predicted 9
   cycles/load × 6 loads = 54 cycles/iteration × 60 iterations = 3240 predicted
   vs **3269 measured**.

**This is the reference point any S2-stage work must be measured against.**
It is also, on its own, a finding: the suite's aggregate under ideal memory moves
from 5999 to 9268 cycles once a non-forwarded load kernel is present, i.e.
non-forwarded D-cache load throughput is now **35% of the whole suite's cycles**.

---

## 1. Goals / Non-goals

### 1.1 Goals

- **G1** — Reduce the LS EU's initiation interval for **non-forwarded** loads
  (the `load-stream` case), which is the case real code is dominated by and the
  case the previous projection was blind to.
- **G2** — Do it without materially regressing post-route FMax below the current
  **221.83 MHz** baseline, and without a material LUT increase. Both are binding
  user constraints, and both were the sole reason the previous (logically
  redundant, textually logic-reducing) LS EU change was rejected.
- **G3** — Prefer the **smallest** structural change that captures most of the
  value. Explicitly: prove or disprove that the full S1-context split is needed
  before paying for it.
- **G4** — Every claim in the implementation must be measured with
  `IPC_SEED`-pinned, paired, both-memory-model runs including `load-stream`.

### 1.2 Non-goals

- **NG1** — Multi-outstanding D-cache loads. `loadRsp` is untagged
  (`DcacheTypes.scala:24-28`: `{data, line, fault}`, no id) and the LS EU
  attributes it purely by FSM state. Tagging it is a separate, larger project.
  This design keeps **exactly one** load in the cache at a time.
- **NG2** — Hit-under-miss in the D-cache. `loadCmdPort.ready` is driven **only**
  inside `IDLE.whenIsActive` (`DcachePlugin.scala:758`) over a `False` default
  (`:229`), so `ready` is 0 for every cycle the cache FSM spends in
  `EVICT_WR`/`REFILL`/`REPLAY`. Unchanged here.
- **NG3** — Reclaiming the `reqMatch` settle cycle. Rejected with cause by the
  grounding (it means driving the VPN from S0, i.e. re-creating the exact arc
  FMax Lever A just removed).
- **NG4** — Removing the `!compValid` term from `issue.ready`. **Measured and
  rejected**: −6.55 MHz, TNS 3.73× worse, +4785 LUTs, for +0.24% IPC. It is now
  documented as load-bearing *placement ballast*. This design keeps the
  `issue.ready` expression **textually unchanged**; see §6.2.
- **NG5** — Any change under `frontend/` or `decode/`.
- **NG6** — Store initiation interval. Stores never enter the proposed back
  stage (§3), so store II stays at 4 cycles. See §3.5 for why that is the right
  trade and §4 for what would change it.

---

## 2. The measured cycle budget, per state, against real RTL

### 2.1 The D-cache load port contract (read-only, cited)

| property | value | citation |
|---|---|---|
| hit latency | `loadCmd.fire` at T ⇒ `loadRsp.valid` at **T+2** (S0 accept / S1 tag-compare+way-select / S2 extract+respond) | `DcachePlugin.scala:759-771` (S0 arm), `:116-117` (`readSync`), `:280-289` (S1 hit compare), `:334-352` (S2 response) |
| `loadCmd.ready` | `!inFlight && xlate.rsp.ready && !pendingStoreMiss && !maintBusyReg`, driven **only** in `IDLE`, default `False` | `:758`, `:229`, `:732` (`inFlight = ldS1Valid \|\| ldS2Valid`) |
| **port initiation interval** | ready is 0 at T+1 and T+2, returns at **T+3** ⇒ **one load every 3 cycles** | derived from `:732` + `:758` |
| miss | ready 0 for the whole `EVICT_WR`/`REFILL`/`REPLAY` excursion; ≈6 cycles to `loadRsp` at zero AXI latency (+1 on a dirty victim) | `:777-826`, `:916-999`, `:1084-1095` |
| response tag | **none** — `DLoadRsp{data, line, fault}`, a `Flow` | `DcacheTypes.scala:24-28`, `:74` |
| store-vs-load port contention | load wins (last-assignment-wins on `rdSet`/`rdEn`); the store holds in S1 and retries. **No store term appears in `loadCmd.ready`** except `pendingStoreMiss` | `DcachePlugin.scala:511-526`, `:1470-1480`, `:758` |
| D-side MSHRs | still **1**. `AxiIds.dRefill(0)` hardcoded at `:995`; the "V1.6 D-side" landing was a sim-harness consolidation, not RTL | `AxiIds.scala:38-40`, `DcachePlugin.scala:995` |

**The key number: the D-cache load port sustains 1 load every 3 cycles. The LS
EU delivers 1 load every 9.**

### 2.2 Per-cycle budget for a non-forwarded, L1D-hit load

Reading `LsEuPlugin.scala` directly (`IDLE` `:1231`, `XLATE` `:1357`, `RESOLVE`
`:1432`, `LAUNCH` `:1469`, `WAIT` `:1484`):

| cyc | state | what happens | citation | why it is spent |
|---|---|---|---|---|
| 0 | — | `issuePort.fire`; `s1Valid/s1Ctx/s1Base/s1Data/s1Index` latch | `:1199-1204` | S1 capture |
| +1 | `IDLE` | `reqStale` — `reqReCaptured = RegNext(issuePort.fire)`, so `reqMatch` is False; hold S1 | `:742`, `:1267`, `:1312` | FMax Lever A's registered DTLB request. **NG3** |
| +2 | `IDLE` | `xlateReady && reqMatch` ⇒ latch `s2Paddr`/`s2Cmode`; `goto(XLATE)` | `:1267-1309` | translate stage |
| +3 | `XLATE` | load: latch `fwdHit/fwdStall/fwdData` from the SQ compare; `goto(RESOLVE)` | `:1414-1421` | severs `s1Paddr → SQ-compare → compData` (documented at `:841-853`) |
| +4 | `RESOLVE` | no forward ⇒ populate `llReg`; `goto(LAUNCH)` | `:1447-1462` | severs `s1Va → loadCmd.vaddr → cmdTag/cmdSet` (FMax #1, `:494-500`) |
| +5 | `LAUNCH` | `dcache.loadCmd.fire`; `goto(WAIT)` | `:1469-1478` | cache S0 |
| +6 | `WAIT` | no response yet (cache S1) | `:1484` | cache pipeline |
| +7 | `WAIT` | `dcache.loadRsp.valid` ⇒ `captureCompletion`; `busy := False`, `s1Valid := False` | `:1486-1529` | cache S2 |
| +8 | — | `compValid` pulse ⇒ `issue.ready` low | `:1158`, `:1212` | **NG4** |
| +9 | — | next `issuePort.fire` | | |

**II = 9 cycles/load.** Validated against `load-stream`: 9 × 6 = 54/iter,
54 × 60 = 3240 predicted vs 3269 measured (0.9% off, the residue being the
loop back-edge).

### 2.3 Where the 9 cycles actually go — and what is and is not reclaimable

| cycles | owner | reclaimable? |
|---|---|---|
| +1 | DTLB request registration (FMax Lever A) | **No** — NG3 |
| +2 | translate / `s2Paddr` capture | **No** — this is the work |
| +3, +4 | `XLATE` (SQ compare) + `RESOLVE` (llReg capture) | **No** as latency; **yes as occupancy** — this is what a split reclaims |
| +5, +6, +7 | cache S0/S1/S2 | **No** as latency; **yes as occupancy** — this is what a split reclaims |
| +8 | `compValid` | **No** — NG4, measured harmful |

Cycles +3…+7 (5 of the 9) are **occupancy the next µop does not need to wait
for**. That is the entire lever. The question this design answers is: how much of
that 5-cycle window can be freed for the least structural disturbance?

---

## 3. Architecture — Stage 1 (RECOMMENDED): the late split at `RESOLVE`→`LAUNCH`

### 3.1 One-paragraph summary

Split the LS EU FSM into a **front** FSM (`IDLE`, `XLATE_B`, `XLATE`, `RESOLVE`,
`WAIT_SQ`) that owns `s1*`/`s2Paddr`/`s2Cmode` and completes every store and
every SQ-forwarded load exactly as today, and a **back** FSM (`LAUNCH`, `WAIT`,
`WAIT_A`, `WAIT_B`) that owns the cache access and completes the non-forwarded
load. The handoff point is the `RESOLVE` "no forward" arm at
`LsEuPlugin.scala:1447-1462`, which **already** captures everything the cache
access needs into `llReg` (135 flops: `valid/vaddr/paddr/addrB/paddrB/size/
cmode/twoAccess/bDone`, `:505-515`). The front frees `s1Valid`/`busy` at that
transition; the back runs to `captureCompletion` on its own.

**Why this point and not `IDLE`→`XLATE`**: because `llReg` already exists. The
grounding's "S2 stage at `IDLE`→`XLATE`" would require duplicating `s1Ctx`
(365 bits) + `s1Base`/`s1Data`/`s1Index` — **527 flops**. The late split needs
only a small completion descriptor (§7) on top of a register file that is already
in the design and already paid for.

### 3.2 Exactly what changes

**(a) Two `busy` bits instead of one.** `busy` (`:1153`) becomes front-owned.
A new `bkBusy` is back-owned. The issue-ready expression is
**textually unchanged**:

```scala
issuePort.ready := !busy && !s1Valid && !compValid   // :1158, UNCHANGED TEXT
```

`busy` merely clears earlier. This is deliberate and load-bearing — see §6.2.

**(b) Back-stage entry.** `RESOLVE`'s `otherwise` arm (`:1447-1462`) additionally
does `busy := False; s1Valid := False; bkBusy := True; goto(IDLE)` for the front
FSM, and arms the back FSM at `LAUNCH`. The front must **stall** in `RESOLVE`
while `bkBusy` is already set (one outstanding cache access — NG1).

**(c) A back-stage completion descriptor `bkCtx`.** Everything
`captureCompletion` (`:895-942`) and `captureFault` (`:956-979`) read from
`u1`/`s1Ctx`/`xlate` must be captured at handoff, because after the handoff the
front is translating a *different* µop. Enumerated from the two function bodies,
restricted to what a non-forwarded **load** can reach (`leaAddr`, `stkPush`,
`isAutoStoreAn`, `isStore` are all provably False on this path — the back stage
is only ever entered from `RESOLVE`, and `RESOLVE` is only reached from `XLATE`'s
load arm at `:1414`):

| field | width | source today | why |
|---|---|---|---|
| `robId` | 6 | `s1Ctx.robId` | `compRobId` |
| `pdst` | 6 | `u1.pdst` | `compPdst` |
| `pdstValid` | 1 | `u1.pdstValid && !u1.ccrRestore` | `compPdstValid` |
| `wakes` | 1 | `isLoad && !u1.ccrRestore` | `compWakes` |
| `ccrRestore` | 1 | `u1.ccrRestore` | `compCcrRestore`, `compNzvc` mux |
| `signExtW` | 1 | `u1.isMovea && size===WORD` | MOVEM.W sign-extend, `:904` |
| `dstArch` | 5 | `u1.dstArch` | `compDstArch` |
| `writesNzvc` / `pNzvcDst` | 1 + addr | `u1.*` | `compNzvcWrite`/`compNzvcDst` |
| `writesX` / `pXDst` | 1 + addr | `u1.*` | `compXWrite`/`compXDst` |
| `crackDrop` | 1 | `u1.divIsRem` | `compCrackDrop` (MOVEM load) |
| `keepCommit` | 1 | `u1.keepCommit` | `compKeepCommit` |
| `needsSupervisor` | 1 | `u1.needsSupervisor` | the `:1523` bus-fault suppression |
| `xlateSup` | 1 | `xlate.req.supervisor` | **see (d)** |
| `faultSize` | 2 | `u1.size` mux | `compFaultSize` (already in `llReg.size`) |

`lineOff` (`:870`, `= s1Va(3 downto 0)`) and `u1.size`, both read by `WAIT_B`'s
`extractCross` (`:1560-1561`), are **already** available as
`llReg.vaddr(3 downto 0)` and `llReg.size` — no new state.

**(d) A genuine bug the split would otherwise introduce — `xlate.req.supervisor`.**
`WAIT` reads it live at `:1523`:

```scala
val suppressForLaterPrivCheck = u1.needsSupervisor && !xlate.req.supervisor
```

and `captureFault` reads it live at `:977` (`compFaultSup := xlate.req.supervisor`).
Once S1 is freed, `xlate.req` tracks the **next** µop's translation
(`reqDrvValid := s1Valid && (isLoad || isStore)`, `:654`), so both reads become
wrong. `xlateSup` in `bkCtx` fixes this. **This is not optional and not a
refactor detail — it is a correctness requirement of the split**, and it is
exactly the class of subtle-interlock bug this file has a documented history of
(`poisoned`, `xlateBArm`, `reqMatch` freshness).

**(e) A second `poisoned` bit.** `poisoned` (`:1184`) is set on
`sqFlushSig && (busy || s1Valid)` and cleared on `issuePort.fire`. With two
independent in-flight µops, one bit cannot serve both — a new `issuePort.fire`
would clear the poison of the *older* µop still draining in the back stage. Needs
`bkPoisoned`, set from `poisoned` at handoff and additionally by `sqFlushSig`
while `bkBusy`. Every `when(!poisoned)` guard inside `WAIT`/`WAIT_A`/`WAIT_B`
(`:1522`, `:1562`) becomes `when(!bkPoisoned)`.

**(f) Completion arbitration.** Today exactly one thing can drive the shared
`comp*` stage per cycle. After the split there are three writers: the front's
live capture (forwarded loads; `fastStore`-path stores), the back's live capture,
and the precise-store deferred replay. The replay already arbitrates against
`liveCompletionFires` (`:1218`, `:1662`) — that mechanism extends naturally, with
`liveCompletionFires` becoming the OR of front and back. **Front-vs-back must be
arbitrated explicitly**: give the **back** priority (it is the older µop, it is
draining, and it cannot be stalled cheaply — its `dcache.loadRsp` is a 1-cycle
`Flow` with no backpressure), and hold the front in `RESOLVE` for one cycle on a
collision. See §5.3.

### 3.3 Resulting cycle budget

Front occupancy per load: cycles +1 (settle), +2 (translate), +3 (`XLATE`),
+4 (`RESOLVE`) = **4 cycles**. Back occupancy: `LAUNCH`(1) + `WAIT`(2) =
**3 cycles**, which is exactly the D-cache port's own 3-cycle initiation interval
(§2.1) — the back stage and the cache port are perfectly matched, by
construction, not by luck.

**Predicted II = max(front 4, back 3, cache-port 3) = 4–5 cycles/load**
(4 if `compValid` is absorbed by the front's own 4-cycle cadence, 5 if not).

| metric | today | Stage 1 predicted |
|---|---|---|
| non-forwarded load II | 9 | **4–5** |
| forwarded load II | 6 | 6 (unchanged) |
| store II | 4 | 4 (unchanged) |
| `load-stream` cycles (zero) | 3269 | **~1700–2000** |
| `load/store`, `mixed` | — | **unchanged** |
| aggregate (zero) | 9268 | **~7700–8000 (+16–20%)** |
| aggregate (l2:5:70) | 12654 | **~11100–11400 (+11–14%)** |

These are **predictions to be falsified by measurement**, and `load-stream` is
now the instrument that can falsify them. Note that the aggregate figure is
competitive with the fetch-directed BTB's projected +16% — but unlike the
grounding's original +15%, this one rests on a kernel that actually exercises the
mechanism.

### 3.4 What Stage 1 explicitly does NOT buy

Nothing for `load/store` and `mixed`. Both are 100%-forwarded (0 cache cycles,
measured), so their loads never enter the back stage and their II stays at 6.
**This is the correct trade and it should be stated plainly**: the grounding's
+15% projection came from those two kernels; Stage 1 abandons that projection and
targets the case that turns out to be both 2.4× more expensive per access and
35% of the suite's cycles.

### 3.5 Why stores are left alone

A store's entire back-half is one cycle of SQ alloc inside `XLATE` (`:1359-1413`),
and that alloc payload is read directly off S1 (`sq.io.alloc.payload.vaddr :=
s1Va`, `:578`; `vaddrB := s1AddrB`, `:579`; `data := s1StoreData`; `robId :=
s1Ctx.robId`, `:561`). Moving it behind a split would require carrying
`s1Va`/`s1AddrB`/`s1StoreData` (96 bits) into the back stage to save **one**
cycle of an already-4-cycle II. Bad ratio, and it would put new flops directly in
the `LsEuPlugin_logic_sq` region — which owns 176 of LsEuPlugin's 262 failing
endpoints (§6.1). Not worth it.

---

## 4. Architecture — Stage 2 (CONDITIONAL): the full S1 split at `IDLE`→`XLATE`

This is the grounding's original proposal. **It is explicitly deferred behind
Stage 1, and it must not be implemented until Stage 1 has been measured.**

Mechanism: capture a full S2 context at the `IDLE`→`XLATE` transition
(`:1301-1309`) and free S1 there, so the next µop begins translating while the
current one does its SQ alloc / forward-resolve. Front occupancy drops from 4
cycles to **2** (settle + translate).

Effect on top of Stage 1: non-forwarded load II 4–5 → **3** (cache-port-bound,
the floor under NG1); forwarded load II 6 → **~3**; store II 4 → **~3**. It is
the only version that helps `load/store` and `mixed`.

Cost: **+527 flops** (`s1Ctx` 365 + `s1Base`/`s1Data`/`s1Index` 96 +
`s2Paddr`/`s2PaddrB`/`s2Cmode` 66) — the exact figure the grounding estimated at
"200–300" and under-counted by ~2×, because `IqContext` is 365 bits
(`RenamedUop` 359 + `robId` 6), dominated by six 32-bit fields
(`pc`, `nextPc`, `imm`, `branchDisp`, `faultAddr`, `predTarget`) that the LS
back-half never reads. A field-pruned variant is possible but requires proving
non-use of 81 fields.

**Gate for even considering Stage 2** (all three must hold):
1. Stage 1 landed and passed its FMax + LUT gate with margin.
2. A post-Stage-1 `load-stream` trace shows the **front** stage is now the
   binding constraint (`issue.valid && !ready` still high, with `busy` — not
   `bkBusy`, not `compValid` — the dominant term).
3. A fresh census (§6.3) shows `LsEuPlugin`/`DcachePlugin` failing-endpoint
   counts did not grow from Stage 1.

If (1) or (3) fails, Stage 2 is dead and the initiative moves on.

---

## 5. Correctness argument

### 5.1 Program order and memory ordering

LS µops are issued **strictly in program order** — `IssueQueuePlugin.scala:316-337`
(`ohLoldest`). The split preserves this at the front. What becomes possible is
**out-of-order LS completion**: a younger forwarded load or store can complete
while an older non-forwarded load is still in the back stage. Four cases:

- **older load (back) vs younger load (front, forwarded).** Two loads have no
  ordering constraint between them. The younger's forward is correct by the SQ's
  own age compare (`sq.io.fwd.query.robId := s1Ctx.robId`, `:589`); the older's
  cache read reflects the SQ state at *its own* `XLATE`, which is strictly
  earlier. Both read a consistent, correctly-aged view.
- **older load (back) vs younger store (front, SQ alloc).** The store only
  *allocates*; it drains at commit. `sq.io.drain`'s `committed` gate is driven by
  ROB retirement (`sqCommit`/`sqCommitB` ← `rob.logic.retire0/1`), which is
  in-order, so the younger store cannot reach memory before the older load has
  completed and retired. The older load's cache line cannot be overwritten under
  it.
- **older load (back) vs younger load (front) to the SAME address, both
  non-forwarded.** Impossible under NG1 — the front stalls in `RESOLVE` while
  `bkBusy`, so only one load is ever in the cache.
- **older store (front, already completed) vs younger load (back).** Unchanged
  from today: the store allocated at its `XLATE`, strictly before the younger
  load's own `XLATE` forward query, so the query sees it.

### 5.2 The untagged `loadRsp`

`DLoadRsp` carries no id (`DcacheTypes.scala:24-28`). The back FSM's
`when(dcache.loadRsp.valid)` at `:1486`/`:1539`/`:1559` remains sound because the
back stage is **single-occupancy by construction** (NG1, enforced by the front's
`bkBusy` stall in `RESOLVE`) — exactly the invariant the LS EU relies on today,
just now held by a narrower structure. The front stage never touches
`dcache.loadCmd`/`loadRsp`. This is the same argument the I-side chain used for
`FetchRsp` and it is a **contract**, not an effort budget: relaxing NG1 requires
tagging `DLoadRsp`, and until then no second load may enter the back stage.

### 5.3 The `comp*` stage — the one genuinely new hazard

Today `compValid` has exactly one live writer per cycle. After the split, front
and back can both want it. The rule:

- **back has priority.** `dcache.loadRsp` is a 1-cycle `Flow` with no
  backpressure (`DcacheTypes.scala:74`); missing it loses the load.
- **front retries.** On a collision the front's `captureCompletion` is suppressed
  and it holds in `RESOLVE` (forwarded load) or `XLATE` (store) for one cycle.
  This is the same shape as the existing precise-store replay's
  `!liveCompletionFires` retry (`:1662`) and reuses its proven pattern.
- **`liveCompletionFires` becomes `frontFires || backFires`**, and the deferred
  replay keeps yielding to both. Its existing correctness argument (only applies
  under `!liveCompletionFires`, else retries) is unaffected.

Frequency bound: a collision needs a front completion and a back completion in
the same cycle. Both are at most 1-per-4-cycles at the predicted II, so this is a
rare-path retry, not a throughput term.

### 5.4 Flush / squash (`sqFlushSig`)

`poisoned` (`:1184`) exists because this core recovers mispredicts at **retire**
time only, so `sqFlushSig` can fire many cycles after a wrong-path µop entered
the EU (`:1160-1183`). Two in-flight µops need two poison bits (§3.2(e)). The
invariant to preserve, unchanged in substance: *a poisoned access performs no SQ
alloc, no ROB completion, no wakeup, and no PRF write* — i.e. it is
indistinguishable from never having issued. The back stage must still **consume**
its `loadRsp` (to keep the cache FSM and `bkBusy` in step) while suppressing all
side effects — precisely what `WAIT`'s existing `when(!poisoned)` wrapper at
`:1522` already does.

### 5.5 `xlateBArm` / split accesses

`xlateBArm` (`:1302`, `:1340`, `:1348`) and `s2PaddrB` are front-owned and are
fully resolved before `RESOLVE` is ever reached (the `XLATE_B` state completes
addrB's real translate *before* the cache is launched — this is the
mmu-split-second-half fix). `llReg.addrB`/`paddrB`/`twoAccess`/`bDone` carry
everything `WAIT_A`/`WAIT_B` need. `IDLE`'s `llReg.bDone := False` housekeeping
(`:1241`) must **move to the back FSM's own idle**, otherwise the front's next
`IDLE` cycle would clear a cross-load's live `bDone` mid-flight. **This is a
second real bug the split would introduce if done naively**, and it is the
already-fixed cross-line-store-after-load hazard documented at `:1233-1241`
reappearing in a new form.

---

## 6. FMax treatment and the mandatory census gate

**This section is first-class, not an afterthought, for a specific measured
reason**: on 2026-08-09 a change to `LsEuPlugin`'s issue-select cone that was
*textually logic-reducing* and *provably logically redundant* — removing the
`!compValid` term, a one-line generated-Verilog diff — cost **−6.55 MHz
(221.83 → 215.28), TNS 3.73× worse, 4512 → 9136 failing endpoints, +4785 LUTs
(+4.61%)** on an uncontended machine with a base run that reproduced the ledger
exactly. The term was acting as **placement ballast**. Pure logic analysis
predicted the opposite sign.

### 6.1 What the netlist says today (mined from the existing routed census)

Source: `synth/census_family.rpt` / `synth/census_endpoints.rpt` /
`synth/census_summary.rpt` / `synth/census_congestion.rpt`, generated
2026-08-09 08:06 by `synth/census.tcl` on `synth/fullcore_routed.dcp`
(59.7 MB, 2026-08-09 08:05). WNS −0.508 ns, 221.828 MHz, 4512 failing
endpoints. **Read-only reuse — no Vivado was launched by this design pass.**

**Failing endpoints by family** (top of `census_family.rpt`):

| family | failing endpoints | worst ns | median ns |
|---|---|---|---|
| **DcachePlugin** | **1260** | −0.454 | −0.118 |
| RobPlugin | 1122 | −0.395 | −0.063 |
| IcachePlugin | 467 | −0.303 | −0.054 |
| RasPlugin | 364 | −0.238 | −0.079 |
| **LsEuPlugin** | **262** | −0.328 | −0.070 |
| IssueQueuePlugin | 172 | −0.349 | −0.100 |
| FetchAlignPlugin | 157 | **−0.508** (global WNS) | −0.201 |

**This is a correction to the grounding's framing.** It called the D-cache/SQ
cone "the design's #2 violated-path family". It is the **#1** family by endpoint
count. `DcachePlugin` + `LsEuPlugin` together own **1522 of 4512 = 33.7%** of all
failing endpoints. `FetchAlignPlugin` owns the worst *single* path but only 3.5%
of the endpoints.

**Failing-path SOURCES in the LS/D corridor** (mined from
`census_endpoints.rpt`):

| startpoint | failing endpoints it feeds |
|---|---|
| `DcachePlugin_logic_tagMem_3_reg/CLKARDCLK` | **1016** |
| `LsEuPlugin_logic_s1Ctx_uop_size_reg` | **235** |
| `LsEuPlugin_logic_s1Ctx_uop_eaDelta_reg` | **155** |
| `DcachePlugin_logic_dirtys_3_48_reg` | 105 |
| `LsEuPlugin_logic_sq/head_reg_rep` | 94 |
| `LsEuPlugin_logic_s2Paddr_reg` | 34 |

**`s1Ctx` is already the #2 and #3 failing startpoint in the entire design.**
Any design that duplicates or widens `s1Ctx`'s fanout is pushing directly on a
confirmed hot structure. This is the single strongest argument for Stage 1 over
Stage 2, and it is measured, not assumed.

**Failing endpoints inside `LsEuPlugin`**: `fwdData` 32, `s1Index` 30,
`sq/lineDataAs_*` 176 (across 8 slices), `s1Base` 12, `compData` 17,
`s1Data` 5. Note `compData`/`compNzvc`/`compPdst`/`compNzvcDst` CE pins already
appear — the completion stage is already marginal.

**IQ select cone**: `selPorts_3` (the LS issue port) appears 88 times in the
failing-path report, always as an *intermediate* net, never as an endpoint. The
net `_zz_IssueQueuePlugin_logic_selPorts_3_payload_uop_valid_15[2]` carries
**fanout 155 at 0.702 ns of routing**. This is the neighbourhood the compValid
removal disturbed.

**Congestion**: `census_congestion.rpt` reports *no* placer or router congestion
windows above level 5. So the compValid regression was a **placement-equilibrium**
effect, not a reported-congestion effect — which means congestion reports will
**not** predict a recurrence, and only a real paired post-route run can.

### 6.2 What Stage 1 adds to the contested corridor — stated, not glossed

| change | where it lands | risk |
|---|---|---|
| `busy` clears earlier | value change only; **`issue.ready` expression textually unchanged** | **LOW.** This is the deliberate design choice: the compValid rejection proved that *changing the structure* of `issue.ready` re-rolls placement badly. Stage 1 changes when a term is 0, not which terms exist. |
| new `bkBusy`, `bkPoisoned` | 2 flops, feed only the back FSM and the front's `RESOLVE` stall | LOW-MEDIUM. `bkBusy` does enter the front's `RESOLVE` next-state logic, one level deep. |
| `bkCtx` (~42 flops) | new register file; fed from `u1`/`s1Ctx` at handoff, read by the `comp*` capture | **MEDIUM.** It adds a second reader of `s1Ctx` fields — and `s1Ctx_uop_size`/`eaDelta` are the #2/#3 failing startpoints. Mitigation: `bkCtx` is captured **once**, at `RESOLVE`→`LAUNCH`, into flops, so it *shortens* rather than lengthens the `s1Ctx → comp*` arcs that exist today (`s1Ctx_uop_size_reg → compData_reg` is a live failing path at −0.253 ns). Plausibly FMax-**positive**; must be measured, not assumed. |
| front/back completion arbiter | one extra AND term on the front's capture enable + the `RESOLVE` stall | MEDIUM. Lands on `compData`'s CE, which already has 10 failing endpoints. |
| `llReg.bDone` housekeeping moves | pure relocation | LOW |

**Honest projection**: this is a genuine structural change to the design's #1
failing family's immediate neighbour. A ±5 MHz swing in either direction from
placement re-roll alone is entirely plausible and would say nothing about the
design's merit — the compValid run is the proof. The gate must therefore be
**paired, back-to-back, uncontended**, and must not be read as a fine-grained
signal below ~2 MHz.

### 6.3 MANDATORY PREREQUISITE — a targeted LS census, before any RTL

Mirroring `2026-08-09-ipc-fetch-directed-btb-design.md` §5.4, whose own Task-1
census **caught a wrong premise in that spec before RTL was built on it**
(G-T1a: the claimed #1 failing cone turned out to have only 55 failing
endpoints).

**Task 1 of the implementation plan must be a census, and it is cheap**: a
routed checkpoint already exists (`synth/fullcore_routed.dcp`, 2026-08-09 08:05,
reproducing 221.828 MHz exactly), so this is a **read-only checkpoint open**, not
a re-implementation. Extend `synth/census.tcl` with LS-specific probes:

```tcl
census_probe ls_s1ctx    "*LsEuPlugin_logic_s1Ctx*"       $prefix
census_probe ls_comp     "*LsEuPlugin_logic_comp*"        $prefix
census_probe ls_llreg    "*LsEuPlugin_logic_llReg*"       $prefix
census_probe ls_sq       "*LsEuPlugin_logic_sq*"          $prefix
census_probe iq_sel3     "*selPorts_3*"                   $prefix
census_probe dc_tagmem   "*DcachePlugin_logic_tagMem*"    $prefix
```

**Gate questions, each with an explicit fail action:**

- **G-L1** — Do any failing paths *end* at `LsEuPlugin_logic_busy` or at the
  IQ's `selPorts_3` ready cone? §6.1 says no (88 intermediate hits, 0
  endpoints). **If the probe says otherwise, Stage 1's central premise — that
  changing `busy`'s timing without changing `issue.ready`'s structure is safe —
  is wrong, and the design must be re-scoped before RTL.**
- **G-L2** — Confirm `s1Ctx_uop_size`/`eaDelta` fanout. If `bkCtx` would *reduce*
  the number of failing endpoints those two startpoints feed, say so up front and
  make it a predicted benefit to be tested, not a hoped-for side effect.
- **G-L3** — Establish the `pb_dcache` pblock's real occupancy headroom
  (`synth/floorplan_dcache.xdc`), since `LsEuPlugin_logic_sq` is flattened into
  it. If `bkCtx`'s ~42 flops cannot be placed outside that pblock, say so and
  re-scope.
- **G-L4** — Relief cap: what is the best achievable FMax if the entire
  `DcachePlugin` family were fixed? (The BTB spec's equivalent G-T1c found only
  2.7 MHz of headroom and correctly concluded the top-line MHz number could not
  carry that lever.) If the answer here is similarly small, **this lever must be
  accepted or rejected on IPC + LUT + FMax-neutrality, never on an FMax
  improvement**, and no task may gate success on MHz movement.

### 6.4 Gating discipline for the implementation

- Every slice ends with a **real post-route** gate (`synth/impl_FullCore.tcl`,
  `runMain m68k040.top.GenFullCoreSynthVerilog` — *not* `GenVerilog`), paired
  back-to-back against its own base, on an **uncontended** machine
  (`free -g` ≥ 20 GB free, no other Vivado, sims finished first). OOC-only is
  proven unreliable on this design.
- **Gate early, not at the end.** Per the compValid carry-forward: the first
  gate must run after the *smallest self-contained increment* (§8.3 slice 1),
  before `bkCtx` is fully populated — so that if the corridor rejects the split
  at all, it is found at ~150 lines of diff, not ~500.
- Explicit pre/post LUT report every time. "Don't blow up LUT count" is a binding
  user constraint independent of FMax.
- `git worktree` isolation for every A/B.

---

## 7. Area

| item | flops | note |
|---|---|---|
| `bkBusy`, `bkPoisoned`, back-FSM state | ~5 | |
| `bkCtx` | **~42** | §3.2(c); dominated by `robId` 6 + `pdst` 6 + `dstArch` 5 + two flag-PRF addresses |
| reused: `llReg` | 0 | **already exists**, 135 flops, `:505-515` |
| reused: `lineA`/`aDone` | 0 | already exists, 129 flops, `:868-869` |
| **Stage 1 total** | **~47** | |
| Stage 2 (if ever) | **+527** | `s1Ctx` 365 + `s1Base`/`s1Data`/`s1Index` 96 + `s2Paddr`/`s2PaddrB`/`s2Cmode` 66 |

Current design: 103,740 CLB LUTs (47.82%), 47,343 registers, 67.81% CLB
occupancy. Stage 1's ~47 flops are **0.1%** of the register count — genuinely
negligible *as flops*. The risk is not the flop count; it is the placement
perturbation (§6.2), which the compValid run proved can cost 4785 LUTs from a
change with **zero** added flops. **Do not let the small area number create false
confidence.**

---

## 8. Verification plan (binding)

### 8.1 Baselines — re-measure, do not quote

On the exact base commit, in an isolated worktree:
- `ExecuteLockStepSpec` (expect 390/394; the 4 are 3× ITLB + `STOP #imm`)
- LS unit specs (`m68k040.ls.*` + `DcacheSpec`, 91 tests; expect 84/7 — **note
  the documented flaky member**: two consecutive clean-HEAD runs gave 6 and 7
  failures. Compare fail-*name-lists*, never counts)
- ported corpus via `tools/fuzz/ported-sweep-parallel.sh` (891 tests, 63 fails;
  **~3 GB RSS per shard** — 4 shards will exhaust this 29 GB machine alongside
  another JVM)
- IPC: `IPC_SEED` 1–8, both `IPC_MEM=zero` and `IPC_MEM=l2:5:70`, **including
  `load-stream`**

### 8.2 New directed tests

- **`LsEuBackStageOrderSpec`** — the load-behind-load invariant (NG1): assert the
  front stalls in `RESOLVE` whenever `bkBusy`, and that `dcache.loadCmd` is never
  asserted for a second load while a first `loadRsp` is outstanding. Exhaustive
  over the front-state × back-state cross product.
- **`LsEuBackStageSupervisorSpec`** — the §3.2(d) bug, directly: a user-mode
  `MOVE <ea>,SR` load (`u1.needsSupervisor`) in the back stage, with a
  **supervisor-mode** access issued behind it into the front, taking an AXI
  `SLVERR`. Without `bkCtx.xlateSup` this delivers the wrong vector. This test
  must be written to FAIL against a deliberately-broken variant that reads
  `xlate.req.supervisor` live.
- **`LsEuCrossLineBDoneSpec`** — the §5.5 bug: a cross-line load in the back
  stage (`WAIT_A`, `llReg.bDone` set) with the front cycling through `IDLE`.
  Must fail against a variant that leaves `llReg.bDone := False` in the front's
  `IDLE`.
- **`LsEuCompletionArbiterSpec`** — force a same-cycle front+back completion;
  assert back wins, front retries, neither completion is lost, and the ROB sees
  both exactly once.
- **`LsEuBackStagePoisonSpec`** — `sqFlushSig` while both stages are occupied;
  assert the back µop's poison is not cleared by the front's `issuePort.fire`
  (the §3.2(e) hazard), and that a poisoned back load still consumes its
  `loadRsp`.

### 8.3 Slicing (each slice ends with the standing post-route gate)

1. **Slice 1 — structural split, no reuse (implemented).** Two FSMs, `bkBusy`, `bkCtx`, all
   the correctness fixes (§3.2 d/e, §5.5), but with the front's `RESOLVE` stall
   set so conservatively that the **observable behaviour and cycle counts are
   IDENTICAL to today** (front does not free S1 early). This isolates the pure
   placement/area cost of the restructure from its IPC effect. **~150 lines.**
   Gate: IPC must be bit-identical; FMax/LUT must be neutral. *If this slice
   alone regresses FMax materially, the lever is dead and nothing further is
   built* — this is the early gate §6.4 demands, and it is the whole reason the
   slicing is shaped this way.
2. **Slice 2 — enable the early free (enabled; functional gate passed).** One-line change to the `RESOLVE` arm.
   Gate: `load-stream` must improve materially (target ≥ 1.6× on cycles);
   `load/store`/`mixed`/`call-return` must not regress; lock-step + LS specs +
   ported corpus fail-lists byte-identical; post-route FMax + LUT.
3. **Slice 3 — accept/reject decision (pending IPC + routed gate)** (§8.4) and, only on all-green,
   consideration of Stage 2 under §4's three gates.

### 8.4 The accept/reject decision — stated in advance

**ACCEPT** if all of:
- `load-stream` improves ≥ 25% (cycles) under **both** memory models, paired,
  8 seeds;
- aggregate improves ≥ 8% under **both** models;
- post-route FMax ≥ **219 MHz** (i.e. within ~1.3% / ~3 MHz of the 221.83
  baseline — placement noise band, not a demand for improvement);
- CLB LUTs within **+1.5%** of baseline;
- lock-step, LS-spec and ported-corpus fail-*name-lists* byte-identical.

**REJECT and revert** on any of: FMax < 217 MHz, LUTs > +2.5%, any new
correctness failure, or `load-stream` improving < 15%.

The compValid precedent is the standard: **+0.24% IPC did not buy −6.55 MHz and
+4785 LUTs, and the correct action was to revert cleanly and record why.** The
same must happen here without hesitation if the numbers say so.

---

## 9. Alternatives considered and rejected

### 9.1 The grounding's full S2 stage at `IDLE`→`XLATE`, implemented first — REJECTED as the *first* step

Not rejected on merit; **re-sequenced**. It is 11× the flop cost (527 vs ~47),
it duplicates `s1Ctx` — already the design's #2/#3 failing startpoint (§6.1) —
and its headline projection came from two kernels that never touch the D-cache.
Doing it first would risk the whole lever on the largest possible perturbation of
the most contested corridor. Retained as Stage 2 behind explicit gates (§4).

### 9.2 Removing `!compValid` from `issue.ready` — REJECTED, already measured

−6.55 MHz, +4785 LUTs, +0.24% IPC. Reverted 2026-08-09. The term is placement
ballast. **This design deliberately keeps `issue.ready` textually unchanged.**

### 9.3 Reclaiming the `reqMatch` settle cycle — REJECTED

Worth 1 cycle/µop but requires driving the DTLB VPN from S0, re-creating the
bypassed-ALU → AGU-adder → `reqReg` arc that FMax Lever A removed (3.193 ns /
56% of a −1.779 ns post-route path). NG3.

### 9.4 Merging `XLATE` and `RESOLVE` back into one cycle — REJECTED

Saves 1 cycle of the 9 and directly restores the
`s1Paddr → SQ-fwd-compare → compData` arc that `:841-853` documents as the
17-level, 70%-route critical path the split was created to sever. Trading 1 IPC
cycle for a known critical path is exactly backwards.

### 9.5 Two outstanding loads in the D-cache (tag `DLoadRsp`) — DEFERRED, not rejected

This is the only path to the 3-cycle floor for *both* stages, and the cache's
own S1/S2 pipeline would tolerate it — `DcachePlugin.scala:722-731` says so
explicitly and calls it *"explicitly OUT of scope here"*. It needs: an id field
on `DLoadRsp`, a response demux, a second S1/S2 context set in the cache, and a
second `llReg`. That is a D-cache project, not an LS EU project, and it lands
inside the design's #1 failing family (1260 endpoints). Revisit only if Stage 1
measures the **back** stage as the binding constraint — which §3.3 predicts it
will not be (back 3 vs front 4).

### 9.6 A wider IQ→LS issue port (2 LS µops/cycle) — REJECTED

Doubles the `selPorts_3` select cone — the exact net measured at fanout 155 /
0.702 ns route (§6.1) and the neighbourhood the compValid change disturbed. All
the risk of the split with none of its containment.

---

## 10. Honest status summary

**What is measured and solid:**
- The benchmark-coverage gap is **closed**. `load-stream` landed, is seed-stable
  to 0.2%, is only 4.9% memory-model-sensitive, and is ~99% LS-EU-bound.
- Non-forwarded D-cache load throughput is **9 cycles/load** — validated to
  within 0.9% by an independently-derived per-state cycle model.
- The D-cache load port sustains **3 cycles/load**. The LS EU is therefore
  leaving a **3× factor** on the table for the case real code cares about.
- `DcachePlugin` is the **#1** failing-endpoint family (1260 of 4512), not #2.
  `LsEuPlugin_logic_s1Ctx_uop_size`/`eaDelta` are the **#2/#3 failing
  startpoints** in the whole design.
- The census reports **no congestion above level 5** — so the compValid
  regression was placement-equilibrium, and congestion reports cannot be used to
  predict a recurrence. Only a paired post-route run can.

**What is projected and must be falsified:**
- Stage 1's 9 → 4–5 cycles/load, `load-stream` 3269 → ~1700–2000, aggregate
  +16–20% (zero) / +11–14% (l2:5:70).
- That keeping `issue.ready` textually unchanged avoids the compValid failure
  mode. Functional reuse is now proven by a delayed-refill overlap test; the
  design's central FMax/LUT bet is still unproven until the paired routed gate.

**What is honest risk:**
- This is a structural change adjacent to the design's most contested corridor,
  in a region that has just demonstrated it can lose 6.55 MHz and 4785 LUTs to a
  one-term perturbation with no added flops. The area number (~47 flops) is real
  and small and **must not be read as low risk**.
- Two genuine correctness hazards the split introduces (§3.2(d)
  `xlate.req.supervisor`, §5.5 `llReg.bDone`) are each of the same subtle class
  that has bitten this file before. Both have dedicated must-fail-first tests.
- Stage 1 buys **nothing** for `load/store` and `mixed`. Anyone expecting the
  grounding's +15% on those kernels will be disappointed; that projection is
  superseded.

**Implementation checkpoint:** the bounded two-entry split and early release are
now present. The measured IPC thresholds pass on the three-seed `load-stream`
pair and seed-1 full-suite pair. This does not claim the final elastic LS
pipeline or same-page all-hit II=1 admission. Acceptance remains conditional on
the routed FMax/LUT gate; synthesis is pending the serialized Vivado slot.
