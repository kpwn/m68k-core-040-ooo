# 10 — Microarchitecture review: design mistakes, bugs, IPC / area / congestion levers

Tree: commit `fee95f6f` (the bitstream on the board). Paths are relative to the repo root;
`m68k040/` means `src/main/scala/m68k040/`. Every finding below was checked against the
code at the cited lines in this tree; source comments and `docs/*` outside `docs/design`
were not used as evidence. Where a component document was found wrong, §8 says so.

Shipping configuration assumed (from `top/SocketTop.scala:100-205`, `top/ShippingPlugins.scala`
and the `SHIPPING_CONFIG` knobs): `CPU_IPC_PROFILE=throughput-v2` (`ipcThroughput=true`,
`ipcLateStore=true`), `LS_OOO_ISSUE=1`, D4 inhibited full barrier, non-blocking L1D with hot
door and early response, `rasBranchRepair / computeDirectTargets / deferSlot1Uncond /
deferSlot1Dbcc` ON (defaults since 2026-09-30, `top/ShippingCoreConfig.scala:244-307`),
`physNzvc = physX = 32`, `pairCorrectBranch = false` (`rob/RobPlugin.scala:48`, not passed by
`ShippingPlugins.rob`), `specLoadWakeup = p3FastLoad = p1EarlyLoad = fourCycleL1 = false`,
test flush port tied off (`top/SocketTop.scala:498`).

Silicon numbers quoted (task brief / project notes): L1 load-to-use 8.29 cyc, L2 ~21; Dhrystone
IPC ~0.36 with ~52% retire stall on a byte strcmp chain; mispredicts ~14% of cycles (direction
31.6%, returns ~31-35%); MLP 1.000; Finder idle ~24 D$ misses/kinst; next-line I-prefetch
halved I-misses for +2.6%; walk stall ~8.8% of cycles (project note of 2026-09-29, **not
re-confirmed on this bitstream**; `OFF_PERF_STALL_WALK` reads it directly).

Effort: L = one file, <50 lines; M = 2-5 files, a protocol touched; H = architectural.
Timing risk: L = off every hot cone / registered; M = touches a cone that has held WNS
before; H = adds a level to the IQ select, PRF address, fetch-PC or D-cache command cone.

---

## 1. Executive summary — ten items ranked by benefit / (effort × risk)

**1. Double the L1 D-cache to 16 KiB; it costs no BRAM and no logic level.** The data
arrays are `Mem(Bits(128 bits), 128)` per way (`cache/DcachePlugin.scala:510`), which Vivado
places in 512-deep BRAM columns, so the arrays sit at 25% depth (the design's 32 RAMB36 are
consistent with 6 per I-cache way + 2 per D-cache way at this utilisation). The geometry is
one constant, `CacheGeometry(cacheBytes = 8192, lineBytes = 16, ways = 4, Vipt)`
(`:387`), guarded by `geo.requireViptSafe(4096, "L1D")` (`:389`, `cache/CacheGeometry.scala:58`),
which 16 KiB / 4 ways = 4 KiB per way still passes, so the VIPT index stays inside the 4 KiB
page offset. The set index is a BRAM address bit (no logic), the tag compare gets one bit
*narrower*, the valid/dirty LUTRAMs are `readSync` (`:703-704`). The only hard-coded geometry
I found is the maintenance engine's page predicate, three lines at `:4253-4256` that assume
`tag = paddr[31:11]`. Attacks the Finder-idle 24 misses/kinst at ~21 cycles each with MLP 1
(~0.5 cyc/instr of ~2.8); zero effect on cache-resident Dhrystone. Effort L, risk L. §4.1.

**2. Give every branch its own RAS pointer snapshot and restore it at Tier 1.** Today the RAS
is restored to a checkpoint taken the last time the ROB was *empty* (`top/FullCoreSynth.scala:219`,
`frontend/Ras.scala:392-410`), on every `earlyFire`/`doFlush`/`ftqMismatch` (`FullCoreSynth.scala:213`).
In busy code the ROB is rarely empty, so each of the ~77 mispredicts/kinst rolls the return
stack back to an arbitrarily old state, destroying the pushes of every call that retired since;
`branchRepair` re-applies only the flushing branch's *own* push/pop (`Ras.scala:323-325`). Returns
are ~31-35% of silicon mispredicts. The fix is the textbook one: stamp `rasSp/count` (9 bits,
plus the 32-bit TOS value if wanted) per branch, carry it to `branchTrainMem`
(`rob/RobPlugin.scala:350-357, 539`) and restore it from `branchCompletion` at `earlyArm`
exactly as `rasRepairKind/Data` are latched today (`:3769-3775`). Nothing new on the redirect
cone. Effort M, risk L. §4.2.

**3. Fold the ROB's FMOVEM `An` write onto the branch EU's int write port.** `fmAnWrite =
rf.newWrite()` (`rob/RobPlugin.scala:187`) is the **6th physical int PRF write port**, used
only inside the serialising exception FSM (`:2996` asserts `exc.active`). The exception unit's
own A7 write already borrows `BranchEuPlugin.IntWbKey` for exactly this reason, and the comment
there records the measurement: "the 6th port costs 900 LUT" (`top/FullCoreSynth.scala:60-72`).
One line, zero IPC, one fewer LVT bank in the structure whose address nets dominate the timing
tail. Effort L, risk L. §5.1.

**4. Drive the flush broadcast from the registered `backendSquash`, not the `flushing` OR.**
`flushing = flush.valid || doFlushReg || excSquash` (`rob/RobPlugin.scala:1187`), `excSquash :=
excActive` (`:3488`) and `excActive = exc.active` (`:3034`); the test flush port is tied to
`False` in the socket top (`top/SocketTop.scala:498`). So in shipping `flushing ==
doFlushReg || exc.active == backendSquash`, the exact registered copy that already exists
(`:3612`, sim-asserted at `:3618`). Yet `rc.flushPort := flushing` (`:3812`) and `headReady`
(`:1222`) take the LUT-OR, which fans out to five RAT rollbacks, five freelists, the ROB
pointers and every retire gate. Replace both consumers; keep `flushing` only when the test port
is live. Effort L, risk L. §5.2.

**5. Collapse the gshare window lookup to one 8-bit read, then widen the PHT in BRAM.** The four
window reads are `indexOf(windowPc + 2i)` (`frontend/Gshare.scala:158-162`); `fold` maps
`pc[2:1]` straight into `idx[1:0]` (`:76-84, 116`) and `windowPc` is 8-byte aligned
(`frontend/FetchAlignPlugin.scala:306, 764-767, 1837`), so the four indices differ **only in
`idx[1:0]` and cover all four values**. One 512 × 8-bit read returns all four counters. The PHT
then has 1 window read + 2 decode-time reads + 1 update read instead of 7 async read ports on a
2048-deep LUTRAM, and the window copy can be a BRAM (`winPayload` is already a register,
`:168-173`), which makes the PHT index width — frozen at 11 bits in `BranchTrainPayload.phtIndex`,
`gsPhtIndex0/1`, `GshareUpdate(idxBits)` — a depth change rather than a LUT budget. Area
~500-700 LUT; IPC: unblocks the direction-predictor lever (31.6% of mispredicts). Effort L
(interleave) / M (widen), risk L. §5.3, §4.5.

**6. Fix the TLB-miss cost chain (four tiers).** A DTLB miss blocks *every* D-side translation
(`mmu/DtlbPlugin.scala:428`), each of the walker's three descriptor reads (`mmu/TableWalker.scala:217-221`)
is issued `ooOk = False` (`execute/LsEuPlugin.scala:5466`) and therefore waits for the whole
non-blocking subsystem to drain (`cache/DcachePlugin.scala:6527-6530`), the walker loses the
load port to any core load for up to `walkerAgeLimit = 64` cycles per level
(`execute/LsEuPlugin.scala:133, 5514-5531`), and a wrong-path walk is never aborted by a backend
flush (`DtlbPlugin.scala:715-728` kill only the pre-launch request). Tiers: (a) `walkerAgeLimit`
64 → ~2, one constant (L); (b) abort the walk on `umFlush` (M); (c) 64-entry DTLB or a shared
512-entry L2 TLB in one RAMB36 (M); (d) tag walker responses so they can be `ooOk` (M). Attacks
the ~8.8% walk stall if it reproduces. §4.3.

**7. Take the SQ forward verdict at P2T and launch non-forwarded loads from P3.** The SQ CAM
uses the physical address (`ls/StoreQueue.scala:37-39, 819-826`) and runs in P3 on the
registered `p3Ctx.paddr` (`execute/LsEuPlugin.scala:2026-2028`); the DTLB response that produces
that paddr is itself a register (`mmu/DtlbPlugin.scala:414-415`). Querying from the response
register in the P2T cycle and registering the verdict into `p3Ctx` lets P3 do what P4 does today
for the common no-forward case; P4 remains for forwards/stalls. Load-to-use 8 → 7 on every L1 hit
(the 52% Dhrystone retire stall is load-to-use bound). The shipped `p3FastLoad` knob cannot pay
because it requires `sq.io.empty` (`:4607`). Effort M, risk M. §4.4.

**8. Int PRF read-port diet: 17 → 14.** `rdC` on both ALUs serves only `CASOP`
(`execute/AluEuPlugin.scala:140, 171`); `a7Rd` mirrors A7 into `SystemState` every cycle for a
value consumed only by the exception FSM (`top/FullCoreSynth.scala:736-740`); `debugIntRead` is
a cold port with a registered address (`debug/DebugCtrlPlugin.scala:1482-1490`). Move CAS to the
CPLX lane (it already has three int reads, `execute/DivEuPlugin.scala:286-288`) and time-share
`a7Rd` with `debugIntRead`. Each port is ~270 LUT *and* one ~970-fanout address net
(`hw/MultiportRam.scala:33-57`: cost is linear per port). Effort M, risk L-M. §5.4.

**9. Decode: allow 3+1 pairing and fuse `.B/.W` register-destination loads.** Any `(An)+` source
load cracks to 3 µops (`decode/MicroOpAssembler.scala:4444-4460`), a 3-µop slot 0 forces slot 1
to the stash (`decode/DecodeStage.scala:537-542`), and `fuseLongMoveLoads` covers only
`MOVE.L` non-auto (`MicroOpAssembler.scala:4461-4470`). Lane 3 of the push is wired to
`a1n(1)` only (`DecodeStage.scala:1682-1686`), so 3+1 is one extra mux arm. Letting the LSU merge
a byte/word load into the old `Dn` (it already reads `psrcB` and produces MOVE flags) makes
`move.b (a0)+,d0` two µops instead of three and restores dual decode on the strcmp shape.
Effort L (3+1) / M (fuse), risk L / M. §4.6.

**10. Exception frames: count them, then stop serialising them.** Each frame word is a 16-bit
WRITETHROUGH-or-INHIBITED store (`exception/ExceptionUnit.scala:914-915, 942, 1898-1984`) with
`precise := True` (`:928`), one per `E_STORE → E_STWAIT` round trip through a single-outstanding
`AxiDMerge` write; the RTE pops four words through serialising (`ooOk = 0`) loads after a full
`dcQuiesced` drain (`:2134-2160`). Mac OS is an A-line-trap operating system. There is no
exception counter in the 18 perf counters (`debug/DebugCtrlPlugin.scala:811-833`); add one first.
Fixes are all cold-path (COPYBACK frames where the page allows it, or push through the SQ),
zero timing risk. §4.7.

Honourable mentions (cheap, measurable, smaller): `pairCorrectBranch = true` A/B (§4.8);
remove the same-line S1-vs-S3 store hold (§4.9); MRU-protected victim choice (§4.10); the IQ
"push blocked while ≥2 lines free" counter that would size the window problem (§2.1).

---

## 2. Design mistakes

### 2.1 The issue queue's usable window is 8 push-groups, half the ROB

**Evidence.** 16 slots as 8 lines × 2 ways. Push is gated on **line 0 being empty next cycle**:
`readyReg := Mux(pushPort.fire, line1Ready, line0Ready)` (`execute/iq/IssueQueuePlugin.scala:2052`),
compaction is an unconditional uniform shift that discards line 0 (`:1603-1616`), the new group
always lands in line 7 (`:1621-1632`) and way 1 is left empty when the group is one µop
(`wDst1.sel := pushSlot1Port`, `:1631`). The ROB is 32 (`Global.scala:34`), the SQ 8.

**Mechanism.** Any µop still unissued seven pushes after its own stops dispatch, regardless of how
many slots are free. The oldest µop is, by construction, the one most likely to be stalled (a
load missing to L2 for ~21 cycles, or the consumer of one). While it waits, at most seven more
groups (≤14 µops, fewer with single-µop groups) can enter. The 32-entry ROB and the IQ's own
slots 8-15 are mostly decoration behind a miss.

**Consequence.** Caps run-ahead to a second independent miss (consistent with MLP = 1.000),
and is the structure that makes a bigger ROB or more MSHRs pay nothing.

**Fix.** (i) Measure first: a counter of `pushPort.valid && !readyReg && count <= 14`
(`count` exists, instrumentation-only, `:636`). (ii) The cheapest structural relaxation that keeps
age = index is a *one-hole-per-cycle collapse*: each line's source is `lines(i+1)` iff an empty
line exists at or below `i`, else hold; the push lands in the first empty line from the top. The
static `triggers` shift becomes "shift bits above the hole by `wayCount`, keep bits below"
(bits for an empty line are already zero). Risk M: the per-line select is a 2-level prefix OR of
flops fanning into ~130 bits per line, replacing today's single `pushPort.fire` broadcast.
A full age matrix is dearer (owner-measured) and is not proposed.

### 2.2 The RAS recovery point is "the last time the ROB was empty"

Evidence and fix in §1-2 / §4.2. The design mistake is that a per-branch quantity (the RAS
top-of-stack at that branch's fetch) was replaced by a global one (the ROB-empty snapshot),
and the only per-branch state kept (`rasKind/rasData`, `execute/BranchEuPlugin.scala:34-35,
406-411`) repairs the branch's own effect but cannot repair the effect of every *older* call
and return that retired since the snapshot. The in-code measurement (`Ras.scala` class comment,
`br-ind` probe: 50% of mispredicts) was the double-mispredict half of this; the stale-snapshot
half is unmeasured and is exactly what busy, call-heavy Mac OS code exercises.

A second, smaller corner of the same mechanism: `branchRepair`'s idempotence test compares the
re-push value with the live TOS (`Ras.scala:323-324`). In direct recursion (a mispredicted
`jsr` whose fall-through equals the TOS of the outer activation) the push is skipped and the
stack is one entry short — a perf corner, not a correctness one.

### 2.3 Every TLB miss is a serialised, arbitrated, non-cancellable walk

**Evidence.**
- `_req.ready := !missPending && ...` (`mmu/DtlbPlugin.scala:428`): one translation in flight
  on the D side; hits wait behind a miss. The LS pipe P1-P4 is in order, so the whole LS pipe
  waits.
- Walker loads: `ooOk := False` (`execute/LsEuPlugin.scala:5466`, exception loads `:5259`),
  because the load client identity is the positional `ldFifo` (`:699-709`) rather than a tag.
  Under the non-blocking D-cache an `ooOk = 0` command needs `quietForSerial = !ldS1Valid &&
  !stgValid && !anyW && !rV && !rqNonEmpty && !loadShadowValid` (`cache/DcachePlugin.scala:6527-6530`):
  every outstanding miss must complete before a descriptor read is even accepted, **three times
  per walk** (`mmu/TableWalker.scala:217-221`).
- Arbitration: `ldCand(i) := walkLdReq(i) && (!coreLsLoadReq || ldForce(i))` with `ldForce`
  at `walkerAgeLimit = 64` (`LsEuPlugin.scala:133, 5514-5525`); `coreLsLoadReq` is "the ring
  wants to send" (`:1908`). A D-side stream starves an ITLB walk for up to 64 cycles per level
  while fetch is stopped.
- No abort: a backend flush only poisons the deferred U/M write (`walkUmPoison`,
  `DtlbPlugin.scala:728`); `flushPoisonArm` is for `atcFlush` only (`:715-727`). A wrong-path
  load to a garbage address — common after a mispredict — commits the walker and `missPending`
  for the whole walk, so the correct path's first load waits for it.
- Geometry: 32 entries, 4 ways × 2 banks (`mmu/Tlb.scala:29-33`); the 68040 had 64 per side.

**Consequence.** A walk costs ≥ 3 × (nb drain + arbitration + D$ hit) with the LS pipe frozen,
typically 20-80 cycles. Project note: ~8.8% of board cycles in walk stall.

**Fix tiers.** (a) `walkerAgeLimit` 64 → 2: zero logic change, the `force` path already exists.
(b) Abort on backend flush: mark the walk `aborted`, let its outstanding descriptor response be
consumed and dropped (the `ldFifo` tag already identifies it), clear `missPending` immediately.
(c) Capacity: a 64-entry DTLB (+~1.4k FF, one more set bit in a registered lookup) or, better,
a shared I/D second-level TLB — 512 entries × ~40 bits fits one RAMB36 (`1K × 36`), flash-cleared
by an epoch field, probed by the walker before `RD_ROOT`, filled at `FINISH`. (d) Give walker/
exception loads a reserved `rid` so they can be `ooOk`; or at least exempt a walker read that
*hits* from `quietForSerial`.

### 2.4 The LS pipe spends two of its eight load-to-use cycles waiting for a verdict it could have one cycle earlier

**Evidence.** Stage registers `tValid/txValid/p3Valid/p4Valid` (`execute/LsEuPlugin.scala:1195-1208`).
The DTLB response is consumed only when `p3Ready` (`:4637-4645`) and lands in `p3Ctx`
(`:4899-4901`); the SQ query runs from `p3Ctx.paddr` (`:2026-2028`) and its verdict is captured
into `p4Ctx` at `p3ToP4` (`:4883-4898`); P4 launches (`p4LaunchOk` `:3977`, `fullForward`/
`mustRetry`/`alignedEnq` `:4079-4111`) with same-cycle fall-through, and the early-probe hit
responds one cycle later. The probe data has been in the D-cache's probe entry since P2+2
("2 cycles after launch", [08 §5]; entry registers `cache/DcachePlugin.scala:735-740, 799`).
So the datum is ready in the P3 cycle and is delivered in the P4+1 cycle.

**Why the stage exists.** The SQ keys on the *physical* address (`ls/StoreQueue.scala:37-39,
204, 819-826`) and the paddr becomes a register only at P3. But the DTLB response payload is
already a register (`mmu/DtlbPlugin.scala:414-415`, `rspPayload`), and `p3Ctx.paddr` is just
`rsp.ppn ## va[11:0]` moved one register later. Querying from `rspPayload` in P2T has the same
logic depth as querying from `p3Ctx`.

**Fix.** §4.4. Alternative with no CAM re-timing: launch speculatively from P3 and let the
registered P4 verdict *cancel* (poison the ring entry on `stall`, prefer the forward data on
`hit`). That is the "hot fast, cold slow" shape and reuses the ring's existing poison bit.

### 2.5 Two of six int write ports and three of seventeen read ports serve cold or redundant work

Evidence in §1-3, §1-8, §5.1, §5.4. The int PRF is the congestion hot spot by the owner's
measurement; `MultiportRam.scala:33-57` shows the read-port cost is strictly linear (no cliff
bonus), and `FullCoreSynth.scala:60-72` records a write port at 900 LUT. The design already
knows the borrowing pattern (`a7Wr`) and the class-steering pattern (`isAluSlow`,
`aluFastAcceptNextPorts`, `IssueQueuePlugin.scala:675-678`); it just was not applied to
`fmAnWrite`, `rdC`, `a7Rd`.

### 2.6 Predictor tables sized for a kernel, read with more ports than they need

- gshare: 7 async read ports on a 2048 × 2 LUTRAM where the 4 window reads are one wide read
  (§1-5). The 11-bit index is baked into `BranchTrainPayload.phtIndex` (`rob/RobPlugin.scala:356`),
  `gsPhtIndex0/1` (`frontend/FetchAlignPlugin.scala:292-293`) and `BranchCompletion.phtIndex`
  (`execute/BranchEuPlugin.scala:28`).
- BTB and FTB: 128 entries each, direct-mapped (`Global` via `ParamPlugin.scala:44`;
  `frontend/Ftb.scala:29`). Both keep and read-modify-write a 2-bit counter that never decides
  direction: the FTB response has no counter field (`Ftb.scala:93-102`), and the BTB counter is
  overridden by gshare for conditionals (`FetchAlignPlugin.scala:1198`) and forced for
  unconditionals (`Btb.scala:88`). The counter costs each table an extra async read for the RMW
  (`Ftb.scala:112`, `Btb.scala:107`).
- The FTB cannot move to BRAM naively: its tag/framing compare is done in the command cycle on
  purpose (`Ftb.scala:70-84`, the measured `ringDrop → applyNow` path). Growing it means more
  LUTRAM, so measure the miss share first (`perfMispredUncond`, `perfMispredCond` exist).

### 2.7 Decode makes the hottest 68k idiom single-issue

Evidence in §1-9. `crackLoad`/`crackLoadOnly` with `srcAuto` emit `[load → T0] [An += n (drop)]
[op reads T0]` (`decode/MicroOpAssembler.scala:4444-4460`); the An update is an *independent*
ALU µop ordered between load and op only for the A7-snapshot argument in the comment. The
machine then pays: 3 µops of ROB/IQ, `deferSlot1` (`DecodeStage.scala:542`), and a lost decode
slot per occurrence. `move.b (a0)+,d0 ; cmp.b (a1)+,d0 ; bne` is 7 µops in 3 decode cycles.

### 2.8 Exception entry and return are built from serialised 16-bit bus transactions

Evidence in §1-10 and §4.7. A format-$0 entry is four `WORD` precise write-through stores,
each waiting for its B response; RTE is four serialising word loads after a full nb drain; both
end in a full refetch (~14 cycles). A 68040 does the entry in ~10 clocks. The cost per
trap is probably 80-120 cycles here; whether that is 0.2% or 5% of Mac OS time depends on the
A-trap rate, which nothing in the tree measures.

### 2.9 Both L1 arrays use a quarter of their BRAM depth

I-cache: `lineMem = Mem(Bits(UFA_W), sets * beatsPerLine)` = 128 × 384 per way
(`cache/IcachePlugin.scala:28-30, 268-269`; geometry `cache/CacheGeometry.scala:80`). D-cache:
128 × 128 per way (`cache/DcachePlugin.scala:510`). Both map to 512-deep BRAM columns. The
D-cache can double inside the VIPT constraint (§4.1). The I-cache could double only by indexing
with VA[12] and tolerating a line resident in two sets (harmless for a read-only array; CINV-line
must probe both candidate sets) — the tag LUTRAM (`IcachePlugin.scala:234`, 2 async reads/way)
would double in depth, adding a mux level to the S1 tag compare; not free, listed for the record.

---

## 3. Correctness

| # | Finding | Verdict | Minimal trigger |
|---|---|---|---|
| 1 | **Branch-EU vs DivEu fault collision.** `rob.logic.euFaultCompletion` is driven from `branchEu.trapvFault` (`top/FullCoreSynth.scala:298-299`) and then overridden inside `when(divEu.euFault.valid)` (`:404-407`); last assignment wins, so DivEu wins and the branch fault is dropped while the branch still completes through `branchCompletion`. The comment at `:400-403` claims the opposite. | **CONFIRMED-IN-CODE** (known) | `TRAPV` (V set) completing in the same cycle a `CHK` or `DIVS` fault completes; both EUs are independent, no interlock. Fix: a fifth input to the existing age-arbitrated `faultPorts` fold (`rob/RobPlugin.scala:2270-2282`). |
| 2 | **Speculative table walk vs older store to the descriptor.** `ldGrantOk` has no SQ/age term (`execute/LsEuPlugin.scala:5524-5527`); the walk starts from `missReqReg` regardless of resident older stores. | **CONFIRMED-IN-CODE** (known) | `move.l #pte,(a0)` with `a0` → a page descriptor, followed by a load through that page before the store drains. Reproducers exist in the test tree per [09]. |
| 3 | **`atcFlush` drops an accepted DTLB request without a response** (`mmu/DtlbPlugin.scala:717-722`). | CONFIRMED-IN-CODE, latent (known) | Safe only because every `atcFlush` source also squashes the requester; nothing asserts it. |
| 4 | **MSHR response starvation by S2 hits** (`cache/DcachePlugin.scala:6458`). [00 §7a-13] says "indefinitely". It is **bounded**: hits come only from launched ring loads, ring slots release strictly in order behind the missing head, and the ring is 4 deep, so at most ~3 hit responses can precede the miss response. | REFUTED as liveness; a ≤3-cycle latency corner | — |
| 5 | **U/M write for an exception-sequencer frame push.** The in-code comment (`exception/ExceptionUnit.scala:1917-1935`) says the deferred descriptor write is tagged with a stale robId and "frequently never reaches memory", as a known follow-up. The follow-up is **implemented**: `dtlb.umAccessPreCommitted := rob.logic.excActive` (`top/FullCoreSynth.scala:537`), latched into the walk (`mmu/DtlbPlugin.scala:563, 612-619`) and allocated born-committed (`:755`). | NOT A BUG; stale comment | — |

No new silent-corruption bug was found in the slices read for this review (IQ select, ROB
flush/retire, LS stage transitions, SQ query, DTLB ready/flush, RAS, gshare, FTB/BTB, D-cache
victim/response/admission). The two known ones stand.

---

## 4. IPC improvements

### 4.1 L1 D-cache 8 → 16 KiB

- **Mechanism.** `CacheGeometry(cacheBytes = 16384, lineBytes = 16, ways = 4, Vipt)` at
  `cache/DcachePlugin.scala:387`; `sets` becomes 256, `setBits` 8, `tagBits` 20. `requireViptSafe(4096)`
  (`:389`, `cache/CacheGeometry.scala:58-61`) still holds (4 KiB per way). Fix the three
  hard-coded page-predicate lines in the maintenance walk (`:4253-4256`: `pageHiMatch` must
  compare `rdTag(tagBits-1 downto 1)` with `target(31 downto 13)` and `pageLoMatch` becomes
  `rdTag(0) === target(12)`). Everything else is sized from `setBits`/`tagBits` (62 uses).
- **Stall attacked.** D-miss stall on Finder-class code: 24 misses/kinst × ~21 cyc with MLP 1.
- **Estimate.** Doubling a small cache typically removes 25-40% of its misses; at the measured
  rates that is 0.13-0.2 cyc/instr of ~2.8 → **+4-7% on Finder-idle-like code, ~0 on Dhrystone**.
  Honest caveat: the Finder's miss stream had no streams and a large handle-chase component
  ([memory notes]); capacity misses are the kind a bigger cache does fix, conflict misses too.
- **Effort.** L (1 constant + 3 lines + the geometry-dependent unit tests).
- **Timing.** L: BRAM address width is internal; tag compare narrows; `validsMem/dirtysMem`
  are `readSync`; `victim` doubles to 256 × 2 flops; MSHR `eset` and probe-queue set fields
  widen by one bit. No new level on the hit path. BRAM count unchanged (25% → 50% depth).
- **Experiment.** Board A/B with `OFF_PERF_DC_MISS` per kinst on the Finder idle and on
  Speedometer; sim: the MAME-trace-driven kernel from the D-prefetch study.

### 4.2 Per-branch RAS snapshot, restored at Tier 1

- **Mechanism.** FetchAlign stamps `rasSp` (4 b) and `count` (5 b) — optionally the TOS value
  (32 b) — into the per-branch prediction record (`brPredTable`, 64 × 45 b,
  `Global.scala:257`, `decode/DecodedUop.scala:803-810`), which already carries
  `predTaken/predTarget/phtIndex` to the ROB alloc. Store them in `branchTrainMem`
  (`rob/RobPlugin.scala:350-357, 539`; add a second read port, it is a 32-deep 1W/1R Mem).
  At `earlyArm` (`:3765-3775`) read the snapshot by `branchCompletion.robId` and latch it beside
  `rasRepairKind/Data`; in `RasPlugin` apply `rasSp := snapSp; count := snapCount` (and
  `ras(snapSp-1) := snapTos` if carried) on the repair cycle instead of the checkpoint copy, then
  apply the branch's own delta exactly as today. Keep the ROB-empty checkpoint only for flushes
  that Tier 1 did not arm (exceptions, debug), where `doFlush && !feSuppress` restores as now.
- **Stall attacked.** Return mispredicts, ~31-35% of the ~14% mispredict cycles.
- **Estimate.** If half of the return bucket is restore damage (plausible in call-heavy busy
  code; near zero in Finder idle where the ROB is empty 75-84% of the time), **+1-3% IPC on
  Dhrystone/Speedometer-class code**. The sim probe `RasBranchRepairIpcSpec`/`br-ind` and the
  silicon class counters (`MISPRED_COUNT − (UNCOND + COND)` = returns) measure it directly.
- **Effort.** M: `FetchAlignPlugin`, `DecodePacket`/`brPredTable`, `RobPlugin` (payload + read),
  `BranchEuPlugin` (or bypass the EU by reading `branchTrainMem` in the ROB), `Ras.scala`,
  `FullCoreSynth` wiring.
- **Timing.** L: all registered; the restore lands one cycle after `earlyFire`, as the repair
  does today. Nothing on the redirect / `ftbBlocked` cone.

### 4.3 TLB-miss cost chain

- **(a) `walkerAgeLimit` 64 → 2** (`execute/LsEuPlugin.scala:133`; `ShippingPlugins.lsEuKnobs`
  does not set it). Effort L, timing L (same logic, smaller counter). Experiment: board
  `OFF_PERF_STALL_WALK` and `OFF_PERF_ITLB_WALK` before/after; expect the per-walk stall to drop
  by up to 3 × (backlog length).
- **(b) Abort the walk on backend flush.** `when(umFlush && missPending)` today only poisons
  (`mmu/DtlbPlugin.scala:728`). Add `walkAbort`: stop issuing further levels, drop the
  outstanding descriptor response by its `ldFifo` tag, clear `missPending`. Effort M, timing L.
  Estimate: every mispredict whose wrong path contained a DTLB-missing load currently costs the
  correct path a whole walk; count `walker.io.done && walkFlushPoison`-like events in sim first.
- **(c) Capacity / L2 TLB.** 512-entry direct-mapped, {tag 11, ppn 20, wp, sup, cmode 2,
  modified, epoch 4} ≈ 40 b in one RAMB36 (`1K × 36` with two words, or `512 × 72`); probed by
  `TableWalker` in a new state before `RD_ROOT`, filled at `FINISH`; PFLUSH bumps the epoch.
  Effort M (new component + walker hook; both TLBs share it). Estimate: with ~90% L2-TLB hit a
  walk becomes ~3 cycles; recovers most of the walk stall.
- **(d) `ooOk` walker reads.** Needs a tagged response identity instead of the positional
  `ldFifo` (`execute/LsEuPlugin.scala:699-709`): reserve `rid` values for the two walkers and the
  exception client (`DLoadRid` grows from 2 to 3 bits). Effort M, timing L. Removes the
  `quietForSerial` drain from every descriptor read.

### 4.4 Load-to-use 8 → 7: SQ verdict at P2T

- **Mechanism.** Drive `sq.io.fwd.query` from the DTLB response register in the P2T cycle
  (`paddr = rsp.ppn ## va[11:0]`, exactly `s1Paddr`'s formula, `execute/LsEuPlugin.scala:1267-1279`),
  gated by `txMatchedRsp`; include the same-cycle SQ allocation (an older store in P3 allocating
  now) as one more compare pair; register `fwdHit/fwdStall/fwdData/fwdSerial` into `p3Ctx` at
  `txToP3` (`:4899-4901`). In P3: no forward and no stall → launch (the `alignedEnq` /
  fall-through that P4 does today, `:4079-4111`); otherwise fall into P4 for forward/retry as
  now. `p4RetryQ` keeps re-querying from `p4Ctx` (`:2026`).
- **Alternative (no CAM re-timing).** Launch from P3 unconditionally for `ordinary` loads and
  let the P4 verdict poison the ring entry (`alignedPoisoned` exists for flush, `:1531`) and
  take the forward/retry path. Costs one wasted D-cache access per forwarded load.
- **Stall attacked.** Load-to-use on L1 hits — the dominant Dhrystone retire stall.
- **Estimate.** −1 cycle on every non-forwarded L1 hit (12.5% of the chain). Prior silicon on the
  full 4-cycle-L1 stack was +2-3%; this is the one piece that does not touch the IQ select, so
  **+1.5-3%** is the honest range. Cheapest bound: in sim, force `sq.io.empty` true in the
  `p3FastLoad` gate (`:4607`) on a store-free kernel and read the cycle delta.
- **Effort.** M (one file, ~150 lines, plus the lock-step/corpus gates).
- **Timing.** M: the SQ CAM input mux gains an arm (`rspPayload` vs `p4Ctx`); the SQ is named in
  every congested window. The alternative variant keeps the CAM where it is.

### 4.5 Direction predictor: interleaved PHT, then widen

- **Mechanism.** Replace the four `pht.readAsync(winIdxComb(i))` (`frontend/Gshare.scala:158-162`)
  with one read of a `Mem(Bits(8 bits), 512)` at `idx[10:2]`, selecting counter `i` by
  `idx[1:0] ^ i`... more precisely: all four indices share `idx[10:2]` and have `idx[1:0] =
  h[1:0] ^ i`, so the four returned counters are a permutation of the four lanes keyed by
  `h[1:0]`. Writes (`:189`) become byte-enabled writes of one lane. The two decode-time reads
  (`:138-141`) stay on a LUTRAM copy (3 ports incl. the update RMW); the window copy becomes
  `readSync` BRAM feeding `winPayload` (`:168-173`).
- **Then** widen: the index width is a parameter in `GsharePlugin`; the 11-bit literals in
  `BranchTrainPayload.phtIndex`, `BranchCompletion.phtIndex`, `gsPhtIndex0/1` and the FTQ entry
  must follow it. A 4096- or 8192-entry PHT is a BRAM depth change.
- **Stall attacked.** Direction mispredicts (31.6% of mispredict cycles, ~4% of all cycles).
  The project note says the 11-bit width blocks a measured win; this is what unblocks it.
- **Estimate.** Interleave alone: 0 IPC, −500-700 LUT. Widening: unknown here; the owner has
  the measurement.
- **Effort / timing.** L / L for the interleave (the BRAM output register is `winPayload`);
  M / L for widening.

### 4.6 Decode: 3+1 pairing; `.B/.W` register-destination load fusion

- **3+1.** `slot1Emit` excludes `slot0Is3` (`decode/DecodeStage.scala:537`); `deferSlot1` stashes
  slot 1 (`:542`). `normUop(3)` is `a1n(1)` (`:1686`); make it `Mux(nCur === 3, a1n(0), a1n(1))`
  and allow `slot0Is3 && slot1Is1`. The imm/brPred side-table lane writes follow the same `nCur`.
  Effort L, timing L (one 2:1 arm on a lane that already has a 3:1), gain: the `op ; bcc` tail
  of every `(An)+` crack pairs again.
- **Fusion.** Extend `fuseLongMoveLoads` (`decode/MicroOpAssembler.scala:4461-4470`) to BYTE/WORD
  when the LSU can merge: read old `Dn` through `rdData` (`psrcB`, idle for loads,
  `execute/LsEuPlugin.scala:370`), carry it in the ring entry, merge at the completion capture
  by size. With the merge, `move.b (a0)+,d0` = `[load.b (a0)→d0 merged] [adda #1,a0]` — two
  independent µops, no stash. Effort M (LSU completion mux + ring payload + assembler), timing M
  (a 3:1 byte-lane mux on `compData`'s D input; `compData` is a register so the mux sits before
  it, after the D-cache's own lane extract).
- **Stall attacked.** Retire/decode bandwidth and ROB/IQ occupancy on byte-string code; the
  Dhrystone strcmp shape goes from 7 µops / 3 decode cycles to 5 µops / 2.
- **Estimate.** +1-3% on string-heavy code; measure on the strcmp kernel in sim (`IPC_STALL_BUDGET`).

### 4.7 Exception entry / RTE

- **First** add `perfExcEntry` (count `faultRetire || rteRetire || interrupt entry` in
  `DebugCtrlPlugin`, one `perfCounter`, `debug/DebugCtrlPlugin.scala:627`). Everything below
  depends on the A-trap rate.
- **Frame stores.** Today `excCacheMode = DE ? WRITETHROUGH : INHIBITED` (`exception/ExceptionUnit.scala:914-915`),
  `precise := True` (`:928`), one `WORD` per `E_STORE/E_STWAIT` (`:1898-1984`). Options: (i) use
  the translated page's own cache mode so a COPYBACK stack acks locally (needs a check that the
  sequencer's later WRITETHROUGH frame *loads* hit a dirty COPYBACK line correctly — the array
  is shared, but the WT-load-on-dirty-line case must be confirmed in `DcachePlugin`); (ii) push
  `PC` as one LONG (3 writes instead of 4); (iii) issue all frame words to the SQ as committed
  stores and let the existing pipelined drain run them. All cold-path, timing L.
- **RTE.** Four `frameWordReq` word loads (`:2149-2160`), each `ooOk = 0` with a `dcQuiesced`
  wait (`:2140`). The D-cache already returns the whole 128-bit line (`loadRsp.line`); one line
  read covers an 8-byte frame unless it straddles a line.
- **Estimate.** Unknown until counted; per event ~60-100 cycles are recoverable.

### 4.8 `pairCorrectBranch = true`

`retireAlone := u.isBranch` (`rob/RobPlugin.scala:1050`); `headAllowsPair = !p0.retireAlone` with
the knob off (`:1444-1446`); `retire1` also needs `!p1.retireAlone` (`:1481`). Every branch takes
a solo commit cycle; 68k code is ~20% branches. The knob exists, is sim-tested (the IPC benches
run it per [05 §4.2]) and is not in `ShippingPlugins.rob` (`top/ShippingPlugins.scala:113-118`).
Effort L, timing L (one more term on `headAllowsPair`). Estimate ≤1%; it mainly shortens the
retire burst after a load returns. Board A/B.

### 4.9 Same-line store S1-vs-S3 hold

`stS1SameLineAsS3 = stS1Valid && stS3ArrayWrite && stS1LineEqS2Prev` (`cache/DcachePlugin.scala:4621`)
holds a store behind the previous store's array write to the same line; the registered copy of
that write (`stS3WriteD1/LineD1`, `:2241-2254`) already exists for the victim-snapshot bypass and
can feed the S2 merge instead. Restores one store/cycle on same-line bursts (stack frames,
MOVEM, memcpy destination), which is what lets the 8-entry SQ drain and avoids `WAIT_SQ`. Effort
L, timing L-M (a 128-bit bypass mux into the S2 capture, same shape as `stS2UsesS3Line`).

### 4.10 Victim choice: MRU protection and invalid-way preference

`victim` is a per-set round-robin counter (`cache/DcachePlugin.scala:576, 3053, 3572`); the way
that just hit can be the next victim, and an invalid way is not preferred (`rdValid` is available
at S1, `:703`). Because the victim is invalidated at MSHR allocation, a hot line chosen as victim
is dead for the whole miss and a hit to it becomes a replay-queue entry that closes the load port
(`:6528`). Two bits per set ("last hit way"), skip it in the pick, and prefer `!rdValid`. Effort
L, timing L (miss path only). Estimate: small but it attacks the worst-case interaction above.

---

## 5. Area / congestion for ~zero IPC loss

### 5.1 `fmAnWrite` onto `BranchEuPlugin.IntWbKey`

`rob/RobPlugin.scala:187` → `rf.newWrite(sharingKey = BranchEuPlugin.IntWbKey, priority = 0)`
(the exception A7 write is already there at priority 0, `top/FullCoreSynth.scala:73-74`; the
debug write at priority 2, `debug/DebugCtrlPlugin.scala:98`). Exclusivity: `assert(!fmAnWrite.valid
|| exc.active)` (`RobPlugin.scala:2996`); the branch EU is latency-1, never stalls, and nothing
issues while the FSM owns the head — the argument at `FullCoreSynth.scala:62-72`. Saves one
physical write port of the 54 × 32 int PRF: one LVT data bank (×2 replicas at 17 reads) plus one
LVT XOR-core input; measured 900 LUT for the 6th port. Touches the PRF hot spot directly.

### 5.2 `flushing` → `backendSquash`

`rc.flushPort := backendSquash` and `headReady` with `!backendSquash` (`rob/RobPlugin.scala:1222,
3812`), valid whenever the test flush port is tied off (`top/SocketTop.scala:498`); keep the OR
behind `if (flushPortLive)`. Removes one LUT level from the net that resets five RATs, five
freelists and the ROB pointers — the "control register broadcasting into wide CE/reset" class
the owner identified as the timing tail. Zero functional change (sim-asserted equality `:3618`).

### 5.3 gshare PHT ports (§4.5)

7 → 4 async reads, or 3 LUTRAM + 1 BRAM. Port arithmetic: a 2048 × 2 LUTRAM needs 32 RAM64-class
primitives per bit-lane-port; 7 ports × 2 bits ≈ 450 LUT of memory plus 7 × 2 × 32:1 output
muxes ≈ 150 LUT. Interleaving leaves ~200 LUT; moving the window copy to BRAM leaves the two
decode-time reads only. Not a congestion hot spot, but it is the structure that blocks the PHT
widening.

### 5.4 Int PRF read ports 17 → 14

| Port | Owner | Why removable | How |
|---|---|---|---|
| `rdC` ×2 | both ALUs (`execute/AluEuPlugin.scala:140, 171`) | only `CASOP` reads it | route `CASOP` (and the CAS kernel's second adder) to the CPLX lane, which already has `rdA/rdB/rdH` (`execute/DivEuPlugin.scala:286-288`); class bit precomputed at dispatch like `isCplxClass` (`execute/iq/IqContext.scala:111-113`) |
| `a7Rd` | wiring (`top/FullCoreSynth.scala:75, 739-740`) | mirrors A7 every cycle into `ss.writeA7` (`exception/ExceptionUnit.scala:856`), consumed only by the FSM | time-share with `debugIntRead` (`debug/DebugCtrlPlugin.scala:85, 1490`): a 6-bit address mux selected by "debug read pending && !exc.active" |

Each port ≈ 270 LUT (brief figure; `hw/MultiportRam.scala:33-57` shows linear cost) and one
read-address net into 6 banks × 2 replicas. With 14 reads the two-replica structure remains
(cliff at 12) but each replica carries fewer ports; going to 12 would need two more removals
(`BranchEu.idxRd` for indexed JMP/JSR and `DivEu.rdH` for 64-bit DIV are the only other cold
candidates, both with real ISA users).

### 5.5 BTB/FTB counters

Delete `counter` from `FtbEntry`/`BtbEntry` and the RMW read (`frontend/Ftb.scala:17, 112-132`;
`frontend/Btb.scala:21, 107-123`). The FTB loses one of three async read ports on its 128-entry
LUTRAM; the BTB one of two. Small (tens of LUT) but on the fetch path's area.

### 5.6 Rare datapaths duplicated in both ALUs

Both ALUs carry BCD/NBCD/PACK/UNPK/ADDX/SUBX/NEGX/CASOP/BFRESOLVE arms (`execute/AluEuPlugin.scala`
op census). Steering the rare set to ALU0 only needs a precomputed hot bit in `cand1`
(`execute/iq/IssueQueuePlugin.scala:677-678`) — one AND on a cone that has held WNS before
(`IqContext.scala:86-92`). Size unknown without an elaboration; **measure one ALU without them
before deciding**.

---

## 6. Dead or unreachable logic worth deleting (shipping build)

Verified in this review (not merely doc-cited):

- `flush` test port term in `flushing` (`rob/RobPlugin.scala:415, 1187`): tied `False` at
  `top/SocketTop.scala:498`. Fold per §5.2.
- `pairCorrectBranch` arm (`rob/RobPlugin.scala:1444-1446`): constant `false` in shipping; either
  ship it (§4.8) or delete the arm.
- GHR bits [15:11]: `idxHistBits = min(ghrBits, idxBits) = 11` (`frontend/Gshare.scala:114-116`);
  bits 15:11 of `ghr` and `ghrArch` (`:91, 221`) are shifted and copied but never index anything.
- BTB/FTB 2-bit counters (§5.5).
- IQ `count` (`execute/iq/IssueQueuePlugin.scala:636`, "instrumentation only") — keep only if the
  §2.1 counter is built on it.
- `p3FastLoad`, `p1EarlyLoad`, `fourCycleL1`, `specLoadWakeup` arms in `LsEuPlugin`/`IssueQueuePlugin`
  are Scala-`if` off and cost nothing in the netlist; they are the scaffolding for §4.4 and should
  stay.

Doc-cited, not reopened here (see [07 §4], [08 §4.2], [09 §4]): `bkFsm`, `SQ.vaddrBs`/`SQ.count`,
legacy cacheable miss FSM, `hitUnderMissAccept`, `UmWalkBatchQueue`, identity-translation plugins,
`MemoryOrderPlugin` family.

---

## 7. Looked at and judged FINE or not worth doing

- **Mispredict penalty depth (~13 cycles).** It is redirect (R) → `ic.cmd` R+1 → rsp R+3 → feed
  R+4 → `raw`/`fed`/`pushReg`/queue → rename ≈ R+8 → dispatch → IQ → EU ≈ R+11, on top of
  resolve → `earlyFire` = C+3. Every cut is a removed register on a hot cone (µop-queue empty
  bypass lands on the pop → ImmEnc widen → rename path). Not proposable at WNS +0.001. The
  rename freeze until retire (`earlyPend`, `rob/RobPlugin.scala:3729-3782`,
  `rename/RenameStage.scala:188-189`) matters only when older ops outlive the ~8-cycle refetch;
  RAT checkpoints (H) are not justified by that.
- **IQ age matrix** — owner-measured dearer than compaction; §2.1 proposes the collapse, not a matrix.
- **ALU-class speculative wakeup**, **LS spec wakeup** — owner-measured; the P3 fold (§4.4) is
  the non-speculative remainder.
- **CPLX extra issue register** (`execute/DivEuPlugin.scala:418`): +1 cycle on MUL/FP/BF; removing
  it puts the CPLX S0 PRF read on the select cone. Leave.
- **DIV fixed 64 iterations** (`execute/DivCore.scala:97-99`): .W/.L32 could start at 32-48, but
  divides are rare in the measured workloads. Leave.
- **`faultCapPending` commit hold** (`rob/RobPlugin.scala:1222, 2282`): one cycle per fault
  completion, wrong-path included; rare. Leave.
- **MSHR response vs S2 hit priority** (`cache/DcachePlugin.scala:6458`): bounded by the 4-entry
  ring (§3-4). Leave.
- **`AxiDMerge` single write in flight** — SoC-side contract; not checkable here.
- **Hot-door FULL slices** (`socket/SocketHotReadBoundary.scala:23-27`): one cycle each way of the
  L+7 miss path; `axiDhArFallThrough` already exists as a knob. Owner territory.
- **SQ depth 4** — owner-measured sim-neutral, board-risky. Leave at 8.
- **ITLB combinational in the accept cycle** (`cache/IcachePlugin.scala:342-346, 400-404`): the
  only accept-cone consumer is the device-fetch gate, deliberately; registering the ITLB would
  need a replay path. Leave.
- **Precise stores when TC.E = 0** (`execute/LsEuPlugin.scala:1317-1320`): not a design change,
  but read `TC` live on the board (`readLive`) once; if TC.E is 0 every store is precise and
  nothing else in this document matters as much.
- **Rename gate on all five freelists** (`rename/RenameStage.scala:162-163`): correct and cheap;
  FP pressure is not the workload.
- **µop record diet, debug shadow trims, FPU delay-stage padding** — FF savings; FFs are not the
  congestion problem. Do them only when a floorplan asks for it.

---

## 8. Corrections to the design documents

1. **[00 §7c-14] "deleting read ports only pays below 12 (replicas halve)".** Wrong direction:
   `hw/MultiportRam.scala:33-57` documents that the LUTRAM cost is *linear* per read port below
   the 16-port cliff and that replication is area-neutral. Every removed port pays ~equally; there
   is no bonus at 12 (the replica count drops, but each replica then carries all ports).
2. **[00 §7a-13] / [08 §7.3] "a head-of-ROB miss can wait indefinitely under a hit stream".**
   Bounded by the 4-entry in-order ring (§3-4).
3. **[01 §4] "`GsharePlugin.queryValid0/1` dead".** The *valid* wires are unread inside gshare,
   but the decode-time PHT reads they accompany are live: `phtTaken0/1` feed
   `fa.logic.gsPhtTaken0/1` (`top/FullCoreSynth.scala:247-255`) and decide conditional direction
   at `frontend/FetchAlignPlugin.scala:1198`. All 7 PHT read ports are live.
4. **[00 §4.2] / [01]** on RAS recovery are correct (restore at Tier 1 via `rasCheckpointRestore`,
   `top/FullCoreSynth.scala:213`); what they do not say is that the checkpoint is wrong for busy
   code (§2.2).
5. **[05 §7.5-1] / [00 §7c-16]** (`flushing` vs `backendSquash`) is correct and is *exact* in
   shipping because the test flush port is tied off (`top/SocketTop.scala:498`).
6. **[00 §3.3] "3 µops: any `(An)+` source load"** — confirmed (`decode/MicroOpAssembler.scala:4444-4460`),
   and the third µop is an *independent* ALU add, not a data dependency.
7. **[00 §1.1] "AXI ... `axi_dh` ... up to 4 refills in flight"** and **[00 §5.4]** — confirmed
   (`cache/DcachePlugin.scala:6338-6376`); the MLP limiter is the IQ window (§2.1), the blocking
   DTLB (§2.3) and the in-order LS pipe, not the bus.
8. **[00 §6] "D-cache data BRAMs at 25% depth"** — confirmed and extended: the I-cache's
   `lineMem` is also 128 deep × 384 wide (`cache/IcachePlugin.scala:268-269`), so 24 + 8 RAMB36 at
   25% depth is the whole 32.
9. **Stale in-code comment, not a doc error:** `exception/ExceptionUnit.scala:1917-1935` describes
   the frame-push U/M write as an open follow-up; it is implemented (`top/FullCoreSynth.scala:537`,
   `mmu/DtlbPlugin.scala:563, 612-619, 755`). The design docs should not inherit the comment.
10. **[00 §3.8] "victim with no invalid-way preference"** — confirmed (`cache/DcachePlugin.scala:576,
    3053, 3572`); add that the round-robin pick can evict the way that hit in the previous cycle.

---

## 9. Measurement list (cheapest experiments, in order)

1. Board: read `OFF_PERF_STALL_WALK`, `OFF_PERF_DTLB_WALK`, `OFF_PERF_ITLB_WALK` per kinst on
   Finder idle and Dhrystone (decides how far to take §4.3).
2. Board: `MISPRED_COUNT − (UNCOND + COND)` per kinst on Dhrystone vs Finder idle (return bucket
   in busy vs idle code; §4.2).
3. Board: `readLive` TC register once (precise-store question).
4. Sim: counter `pushPort.valid && !readyReg && count <= 14` on the Dhrystone kernel (§2.1).
5. Sim: force `sq.io.empty` in the `p3FastLoad` gate on a store-free kernel (upper bound of §4.4).
6. Board A/B, one constant each: D-cache 16 KiB (§4.1), `walkerAgeLimit = 2` (§4.3a),
   `pairCorrectBranch = true` (§4.8) — each is a `SHIPPING_CONFIG`-visible knob or should become one.
