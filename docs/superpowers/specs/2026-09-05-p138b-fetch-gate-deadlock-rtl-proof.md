# p138b — the p137 boot wedge, proved from RTL: the I-side speculation gate is *provably* shut at every FTB apply

**Date:** 2026-09-05
**Scope:** analysis only. No builds, no board, no SD card, no sbt. Every claim below is
read off `5e3fe4e` source (and one generated-netlist quote taken from the sibling doc).
**Sibling:** `2026-09-05-p138-bisect-the-p137-boot-wedge.md` (parallel session, board
measurements). This doc **agrees with its §3.1** and **strengthens it from "a closed loop
exists" to "the loop is entered deterministically, by construction, on the first applied
FTB prediction into a cache-inhibited page."** Read that doc for the silicon evidence; read
this one for the proof and the fix.

---

## 1. Verdict

**Arm A — `8887507` (speculative-fetch gate) — introduces the wedge.**

Stuck signal:

| # | signal | file:line | why it is stuck |
|---|---|---|---|
| 1 | `FetchAlignPlugin.logic.targetHoldValid` | `src/main/scala/m68k040/frontend/FetchAlignPlugin.scala:288` (decl), **:632** (set), :637-639 / :1375 (only clears) | set by fetch-command backpressure; cleared only by `ic.cmd.fire` or `ftqFlush` |
| 2 | `SpeculativeFetchGate.frontendQuiet` | `src/main/scala/m68k040/top/SpeculativeFetchGate.scala:86` (`!fl.targetHoldValid`) | false forever because of (1) |
| 3 | `IcachePlugin.logic.nonSpecFetch` | `SpeculativeFetchGate.scala:126` | false forever because of (2) |
| 4 | `IcachePlugin.logic.inhibitedSpecBlock` | `src/main/scala/m68k040/cache/IcachePlugin.scala:306` | true forever because of (3) + the held target being INHIBITED |
| 5 | `IcachePlugin.cmdPort.ready` | `src/main/scala/m68k040/cache/IcachePlugin.scala:1652-1653` | low forever because of (4) → no fetch ever issues again → (1) never clears |

`targetHoldValid` is the load-bearing one: it is the only term of the loop that is a
**register recording the refused fetch itself**, and while it is set `cmdWindowPc` is
*forced* to `targetHoldPc` (`FetchAlignPlugin.scala:585-590`), so the frontend cannot even
present a different, cacheable address to escape.

---

## 2. The new part: the gate is *provably* closed at the exact cycle the hold latches

The sibling doc argues the loop is closed. It does not establish how often it is entered —
and a reader could reasonably hope the hold usually latches on a cycle where the gate
happens to be open. It never can. Proof, four steps, all from source:

1. **`targetHoldValid` latches only on an `applyNow` cycle.**
   `FetchAlignPlugin.scala:628-635`:
   `when(applyNow) { … when(!ic.cmd.fire) { targetHoldValid := True; targetHoldPc := directTargetPc } }`.

2. **`applyNow` is *exactly one cycle* after an accepted fetch command.**
   `:502` `applyNow := resultExpectedValid && …`, `:388`
   `resultExpectedValid = RegNext(planLookupFire)`, `:386`
   `planLookupFire = ic.cmd.fire && !issueBornStale`.

3. **Therefore `ringCount >= 1` on every `applyNow` cycle.** The window whose plan is
   being applied fired its command last cycle, so it occupies a ring slot
   (`:604-613`, `:704-715`), and it cannot have been consumed: `:507` asserts
   `!(ic.rsp.valid && (ringHead === resultSlot))` inside `when(applyNow)` — the plan
   application *precedes* its own cache response by contract. A coincident consume of an
   *older* entry only means the count was ≥ 2 to begin with.

4. **`nonSpecFetch` therefore evaluates to 0 on every `applyNow` cycle**, because
   `SpeculativeFetchGate.scala:126` is
   `ic.logic.nonSpecFetch := drainedQ && (fl.ringCount === 0)`
   and step 3 makes the second conjunct false. Note this is the term the gate's own
   comment (`SpeculativeFetchGate.scala:53-60`) says was deliberately left
   **combinational** so it could not go stale. It does its job perfectly — and that is
   precisely what makes the latch unavoidable.

**Consequence.** `inhibitedSpecBlock = !lookupCacheable && !nonSpecFetch`
(`IcachePlugin.scala:306`) reduces at every `applyNow` cycle to `!lookupCacheable`, and
`lookupCacheable` is evaluated on the *presented* PC — `xlate.req.vpn := cmdPort.payload.pc(31 downto 12)`
(`IcachePlugin.scala:153-154`), and at an `applyNow` cycle `cmdPort.payload.pc` **is**
`directTargetPc` (`FetchAlignPlugin.scala:585-587`, `:602`).

So:

> **Every applied FTB prediction whose target lies in a cache-inhibited page latches
> `targetHoldValid` on that target. There is no cycle on which it could do otherwise.**

`ic.cmd.valid` is high on that cycle (`:600-601`: `applyNow` requires `!ftbBlocked`
(`:463-465`), which already excludes `stalled`/`faultHold`/`quiesce`, and `:547-549`
excludes `ftqMismatch`), so the refusal really is `cmdPort.ready`, not a missing valid.

### 2.1 The only escape, and why it closes

`ftqFlush` (`:544-545`) = `redirect.valid || (resume.valid && stalled) ||
mispredictRedirect.valid || predictFire || ftqMismatch` clears the hold (`:1371-1375`).
Every one of those originates from an instruction **already inside the machine**. Blocking
the fetch drains the machine — the fix's own safety argument — and a drained machine
produces none of them. If the applied prediction was *correct* (the architectural stream
really goes to that inhibited target), no mispredict is ever raised and the wedge is
immediate and terminal. If it was wrong-path, the older mispredict clears it — but the
frontend then re-fetches and can re-latch on the very next apply of the same trained FTB
entry, with no branch left in the refetched window to mispredict a second time.

### 2.2 A second, independent closed loop in the same predicate

`SpeculativeFetchGate.scala:87` also requires `fl.ftqCount === 0`. `ftqPush := applyNow`
(`:539`); `ftqPop := ftqConfirm && feed.fire && !emittingFaultPacket` (`:1196-1200`) — the
predicted branch must actually *reach decode*, which needs bytes the gate is refusing.
Its only other clear is the same `ftqFlush`. Same shape, same terminality. Fixing only
`targetHoldValid` would leave this one live.

Both terms are **records of a pending fetch**, not machine occupancy — which is exactly
the class the gate's own "Why it cannot deadlock" section (`SpeculativeFetchGate.scala:39-51`)
was hunting when it correctly excluded `ibuf.io.cnt === 0` and `pendingDrop === 0`.

### 2.3 Corroboration with the board data

* `wedge-status` shows `rob_hd=0`, LSU idle, D-cache idle, no AXI handshake — i.e. every
  *occupancy* term of the predicate has drained and the fetch still does not issue. Only
  the two fetch-record terms are unaccounted for. That is the signature this proof predicts.
* The sibling doc's `icache-lookup 0x40806B68` probe returned `DID NOT COMPLETE`. Under
  this mechanism `ic.cmd.valid` stays **high** forever against a permanently low
  `cmdPort.ready` (§2, and after the drain `ringSlotAvailable`/`ibufRoomForCmd` are both
  true again), so the fetch read path is never idle — which is exactly what a starved
  idle-cycle probe looks like. Suggestive, not proof; the probe sampled nothing.
* The live walk shows the wedge PC's own page is `CM=01` cacheable. Consistent: the held
  address is not the wedge PC. `pc_live` is the retire successor; the held target is
  whatever the FTB predicted and is not observable on this bitstream.

---

## 3. Why the other two arms cannot produce *this* signature

### Arm B — `7057e80` (RTE resyncs `RobPlugin.committedCcr`)

Touches `ExceptionUnit.scala` only (44 lines, two combinational assignments in `R_REDIR`).
It drives **no fetch-side signal and no ready/valid handshake**. It can change *which*
path the machine takes after an RTE; it cannot make a fetch command be refused forever.
It is capable of *steering* the machine into a window that then trips arm A. Ruled out as
a cause, not as a contributor to reachability.

### Arm C — `2db5bd3` (MMU walks through L1D)

The dangerous shape here is real and I checked it explicitly, because an **ITLB** walk that
never completes produces a signature *identical* to arm A: `IcachePlugin.cmdPort.ready`
carries `xlate.rsp.ready` (`:1652`), and `ItlbPlugin.scala:446-451` holds `rsp.ready` low
across a miss — so a wedged ITLB walk stalls instruction fetch, the machine drains, and
`lsu=IDLE`/`dcache=IDLE`/`dmmu_*=0` all read exactly as measured. **The sibling doc's
exclusion of arm C rests on `dmmu_req/dmmu_walk/dmmu_fault`, which are DTLB probes; the
ITLB walker is not probed.** So that exclusion is weaker than it reads.

It nevertheless does not fit, for a reason the board data does supply:

* For a walker to be stuck it must be un-granted. `ldGrantOk`
  (`LsEuPlugin.scala:3353-3354`) needs `ldOwner === CORE && !ldBusyExc && !quiesceHold &&
  !ldFifoFull && (walkLdReq && !coreLsLoadReq)`. In a fully drained machine
  `coreLsLoadReq`, `ldBusyExc` and `quiesceHold` are all 0 and `ldOwner` is CORE, so the
  grant is issued the same cycle — and `DcachePlugin.loadCmdPort.ready`
  (`DcachePlugin.scala:1544-1546`) is high with the load FSM idle and `maintBusyReg` clear.
  A pending ITLB walk in a drained machine is therefore **served**, not starved. The board
  reports the D-cache idle and no AXI activity, so no walk is pending.
* The store-side hypotheses in the tasking are answered negatively:
  - `sq.io.drainAck := dcache.storeAck && !excStoreOutstanding && !walkStOutstanding`
    (`LsEuPlugin.scala:530`) is the one place a walker can eat an SQ ack, but
    `walkStOutstanding` clears on **any** `dcache.storeAck` (`:3395`), and
    `storeAckReg` fires on error too — `storeErrReg` is `(storeBAck && stSubLast) && …`
    while `storeAckReg` is `(storeBAck && stSubLast) || …` (`DcachePlugin.scala:3085-3086`),
    so an errored walker store still terminates. `walkStOutstanding` has no sticky path.
  - `stGrantOk` (`:3382-3384`) requires `coreStOutstanding === 0 && !dcache.store.fire &&
    !excStoreValid && !excStoreOutstanding`, so the store direction is genuinely
    drain-to-zero: a walker U/M store cannot be granted while an SQ store is in flight,
    and cannot start in the same cycle one fires.
  - **U/M writes are not visible to `sqEmptySig`** (`:537` `sqEmptySig := sq.io.empty`,
    a `StoreQueue` occupancy signal; the U/M queues live in `DtlbPlugin`/`ItlbPlugin`).
    The tasking's candidate-3 deadlock ("exception blocked on a U/M write blocked on a
    retire blocked on the exception") therefore does **not** close through `sqDrained`.
    It could only close through `stGrantOk`'s `coreStOutstanding === 0`, which a
    *drained* SQ satisfies by definition.
* The "reverse cycle" the tasking asked me to trace explicitly — *a store misses the DTLB →
  needs a walk → the walk needs D-cache service → does anything in that path wait on
  store-queue state?* — **does not close.** The walk's load path waits on `ldGrantOk`,
  whose terms are `ldOwner`, `ldBusyExc`, `quiesceHold`, `ldFifoFull`, `coreLsLoadReq`.
  None of them is a store-queue occupancy signal. `quiesceHold`
  (`ExceptionUnit.scala:2455-2457`) is `S_DRAIN || S_APPLY || S_MAINTWAIT` — the
  *cache-maintenance* path, not exception entry, exactly as the tasking cautioned. The
  walk's *store* path does depend on `coreStOutstanding === 0`, but that is stores
  **in flight**, not stores **queued**, and it is monotone: outstanding stores always
  terminate (previous bullet), so it always reaches 0.

**Residual on arm C, stated because I could not close it:** the ownership FIFO
(`LsEuPlugin.scala:475-485`) pushes on `loadCmd.valid && ready` and pops on
`loadRsp.valid`. If the D-cache ever accepted a load and produced no response, `ldFifoOcc`
would ratchet up to 8, `ldFifoFull` would latch permanently, and *every* client — CORE-LS,
CORE-EXC and both walkers — would be locked out with the D-cache reading IDLE and no AXI
traffic. That would also match the board data. The simulation tripwires
(`:3497-3500`) cover the *opposite* desync (a response with an empty FIFO) and **nothing
asserts on a command that is never answered.** `loadRspPort.valid := ldS2Resp ||
busFaultResp || inhibitedResp` (`DcachePlugin.scala:892`) looks like a total cover of
hit / bus-fault / inhibited, and the CORE-LS path would hang lock-step immediately if it
were not — but I did not enumerate the load FSM to prove totality.

---

## 4. Answers to the tasking's four questions, in order

1. **Can a store sit in the SQ indefinitely after the walker change?** Not through walker
   arbitration. The store direction is drain-to-zero with a monotone termination argument
   (§3). `sq.io.drain.ready` is held low while `walkerOwnsStore` (`:3309`), but
   `stOwner` returns to CORE as soon as the walker's one store is acked (`:3389-3391`).
2. **Is there a circular wait?** No, and the walker agent's chain argument survives the
   reverse direction too: no term of `ldGrantOk` is a store-queue signal (§3).
3. **Are U/M writes visible to `sqEmptySig`?** **No.** The self-sustaining deadlock the
   tasking hypothesised does not exist through `sqDrained`.
4. **If the fetch gate is implicated, why does no exception at the ROB head fire?**
   Because **there is no exception at the ROB head — the ROB is empty.** `rob_hd=0`,
   `retired_macros` frozen, and live `SR = 0x2700` (IPL=7) from the sibling doc. The A-line
   at `0x40806b68` was never fetched, so it never became a ROB entry; and an interrupt has
   no instruction boundary to be taken at (and is masked anyway). The premise "exception
   delivery is dead while the machine runs" is false: **nothing runs**. That is not
   evidence against candidate 1 — it is what candidate 1 predicts.

**The `sqDrained` lead in the tasking is a dead end.** `ExceptionUnit.scala:404`'s claim is
accurate, but the SQ is not the stuck thing; the exception sequencer is never *asked* to
enter one.

---

## 5. Proposed fix (design only — not implemented, not simulated, not synthesised)

Remove `!fl.targetHoldValid` and `(fl.ftqCount === 0)` from `frontendQuiet`
(`SpeculativeFetchGate.scala:86-87`). Keep every other term.

The gate's soundness argument survives, and this is the point: its own elimination proof
(`:25-37`) is *"if the IBuf, decode, rename and the ROB hold nothing, every instruction
ever fetched has already retired, so no redirect can be produced and `fetchPc` is the
architectural successor."* A held FTB target in an otherwise-empty machine satisfies that
hypothesis exactly — the branch that produced the prediction has retired, and had it been
mispredicted the redirect would have fired *before* retire and cleared the hold via
`ftqFlush`. So a target that survives to a fully drained machine is architectural by the
same elimination the gate already relies on. The two removed terms describe the refused
fetch, not the machine's contents, and including them makes the predicate refer to its own
output.

Not verified. In particular I have not checked whether a *wrong-path* window can leave an
FTQ entry or a held target behind with **no** older instruction left to redirect — if it
can, the removal weakens the gate rather than restoring it, and the correct fix is instead
to make the hold *abandonable* (drop `targetHoldValid` when the rest of the predicate is
true) rather than to drop the term.

Interim option if a fix must be minimal: this defect is invisible when the ITLB never
reports INHIBITED for a predicted target. It is *not* a valid mitigation to disable the
gate, since the gate exists to stop read-to-clear device registers being consumed
speculatively — but reverting arm A does restore a booting machine, and the sibling
session's bisect should confirm that empirically.

---

## 6. Why the existing verification could not catch it

Reproducing the sibling doc's §4 because it is correct and load-bearing: the three
`spec-mmio I-side` probes in `ExecuteLockStepSpec` assert `arHits.isEmpty` and have **no
liveness assertion** — a deadlocked frontend passes them more easily than a working one.
Their program is a `bra.s` self-loop already resident in the IBuf, so `robQuiet` is never
true and the gate's release path is never exercised at all. Adding a progress assertion
(retired-macro count must advance) to those three probes is the single highest-value test
change here, independent of which fix lands.

---

## 7. What I did **not** check

* **I ran nothing.** No sbt, no simulation, no elaboration, no synthesis, no board. Every
  statement is source reading at `5e3fe4e` plus the sibling doc's measurements.
* **I did not reproduce the wedge in simulation.** A directed test exists in outline —
  drive an FTB entry whose target maps to an INHIBITED page, let the plan apply, assert
  `targetHoldValid` never clears and no further `ic.cmd.fire` occurs — but building it
  needs a DUT with the FTB, the ITLB and the gate all live, and the host budget rules out
  a heavy JVM while a bisect build is in flight. **The proof in §2 is a static argument,
  not a measurement.**
* **I did not establish what steers the frontend into inhibited space at this PC.** Same
  gap the sibling doc flags in its §5. The *target* held in `targetHoldPc` is not
  observable on this bitstream.
* **I did not enumerate `DcachePlugin`'s load FSM** to prove every accepted `loadCmd`
  yields exactly one `loadRsp` — the one open door left for arm C (§3, residual).
* **I did not check the ITLB's runtime contents.** `MMUSR = 0` (no `PTEST` ran), so what
  the ITLB actually holds for the wedge page is unmeasured, and `2db5bd3` changes how that
  entry is sourced. An arm-A/arm-C *interaction* would live exactly there.
* **I did not read `IcachePlugin`'s prefetcher** (`pfAcceptOk`, `pfLookupSetBusy`) for a
  second, independent route to a permanently low `cmdPort.ready`.
* **I did not verify the deployed p137 bitstream** contains the gate; I took the sibling
  doc's `M68kSocketTop.v` quote for that.
