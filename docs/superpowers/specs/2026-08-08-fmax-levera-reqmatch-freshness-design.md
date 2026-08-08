# FMax closure, "Lever A": replace `reqMatch`'s value compare with a freshness flag (design)

## Context

Part of the FMax-closure initiative (see `.superpowers/sdd/progress-fmax-*.md` for
the full history: Slice 1 landed, Slice 2 landed, Slice 3 implemented then
reverted after a disappointing combined measurement). A netlist-grounded
investigation (`.../scratchpad/fmax-faultaddrstore-ce-grounding-report.md`,
section 3.3, "Lever A") found that `LsEuPlugin.scala`'s `reqMatch` signal
sits on ~56% (3.193ns) of a -1.779ns post-route critical path
(`LsEuPlugin_logic_s1Ctx_uop_eaAuto_reg -> RobPlugin_logic_faultAddrStore_*_reg[*]/CE`),
because `reqMatch` is a 20-bit VPN-equality compare fed by `xlateVaddr`,
itself the output of two CHAINED 32-bit adders (`s1Va` at
`LsEuPlugin.scala:367`, `s1AddrB` at `:405`).

**This slice alone measures 0MHz post-route gain** (per the grounding
report's own §4 finding — this family does not currently hold WNS, a
different family does). It is scoped and reviewed as ONE COMPONENT of a
combined slice; do not expect a standalone post-route number to move.

## The cone (source-cited, current HEAD post-Slice-3-revert, i.e. identical
## to `b6888f6`)

```scala
// LsEuPlugin.scala:622
val xlateVaddr = Mux(xlateBArm, s1AddrB, s1Va)
...
// LsEuPlugin.scala:649-658
val reqDrvValid = Bool()
val reqDrvVpn   = UInt(20 bits)
...
reqDrvValid := s1Valid && (isLoad || isStore)
reqDrvVpn   := xlateVaddr(31 downto 12)
reqDrvSup   := privCtrl.map(_.supervisor).getOrElse(False)
reqDrvWrite := isStore
reqDrvRobId := s1Ctx.robId

// LsEuPlugin.scala:660-666
val reqReg = new Area {
  val valid = RegInit(False)
  val vpn   = Reg(UInt(20 bits))
  val sup   = Reg(Bool())
  val write = Reg(Bool())
  val robId = Reg(UInt(6 bits))
}
// LsEuPlugin.scala:673-674 -- THE SIGNAL THIS SLICE REPLACES
val reqMatch = reqReg.valid && (reqReg.vpn === xlateVaddr(31 downto 12)) &&
               (reqReg.write === isStore)

// LsEuPlugin.scala:1735-1739 -- reqReg is ALWAYS re-captured, every cycle,
// unconditionally (no FSM-state gating). This is load-bearing: it is what
// makes reqReg settle to a new access within exactly one cycle.
reqReg.valid := reqDrvValid
reqReg.vpn   := reqDrvVpn
reqReg.sup   := reqDrvSup
reqReg.write := reqDrvWrite
reqReg.robId := reqDrvRobId
```

**`reqMatch` has exactly TWO consumers in the whole file** (grepped, confirmed
no others exist):

```scala
// LsEuPlugin.scala:1194 (IDLE state, gating slot-A's translation consume)
when(xlateReady && reqMatch) { ... }

// LsEuPlugin.scala:1261 (XLATE_B state, gating slot-B's translation consume)
when(xlateReady && reqMatch) { ... }
```

Neither `XLATE`, `RESOLVE`, `LAUNCH`, `WAIT`, `WAIT_A`, `WAIT_B`, nor
`WAIT_SQ` ever reads `reqMatch` — once the FSM has left `IDLE`/`XLATE_B`
with a resolved translation, `reqMatch` is architecturally irrelevant for
the remainder of that access's lifetime.

**Purpose (per the file's own comment, `:667-672`):** detect whether the
REGISTERED DTLB request (`reqReg`, which is what's actually driving
`xlate.req` — see `:1740-1744`, UNCHANGED by this slice) still corresponds
to the access the FSM is CURRENTLY presenting via `xlateVaddr`. `reqReg`
always lags `xlateVaddr` by exactly one cycle (it's an unconditional,
every-cycle capture); `reqMatch`'s job is to detect "has `reqReg` caught up
yet" and, until it has, force a stall (matching the existing DTLB-miss
stall shape — same one-more-cycle pattern, not a new latency class).

## The fix: a freshness flag instead of a value compare

**Key insight, established by tracing every event that can change what
`xlateVaddr` presents (the closure argument below), not assumed:** since
`reqReg` re-samples `xlateVaddr`'s VPN unconditionally every single cycle,
`reqMatch`'s VALUE COMPARE is answering exactly one question: *"is this
cycle's `xlateVaddr` the same as it was last cycle?"* That question can be
answered directly, without comparing 20+1 bits of value, by tracking
WHETHER something wrote to the two registers that can change
`xlateVaddr`'s output — `s1Base`/`s1Ctx` (via `issuePort.fire`) or
`xlateBArm` — one cycle ago.

```scala
// Replaces LsEuPlugin.scala:673-674.
// `xlateVaddr` (:622) can change ONLY via two register-write events:
//   (1) issuePort.fire captures a NEW s1Base/s1Ctx (:1126-1131) -- s1Va
//       (a combinational function of s1Base) reflects the new access
//       starting the cycle AFTER the fire.
//   (2) xlateBArm's VALUE changes (set True at :1229 entering XLATE_B;
//       cleared False at :1267/:1275 leaving it) -- xlateVaddr's SOURCE
//       selection changes starting the cycle xlateBArm's new value is read.
// (See the design spec's closure argument for why NOTHING else can change
// xlateVaddr while a translation is pending, and why the write-mode
// (`isStore`) term needs no separate tracking -- it's captured atomically
// with s1Base at the SAME issuePort.fire event.)
val xlateBArmPrev = RegNext(xlateBArm, init = False)
val presentedAccessChanging = issuePort.fire || (xlateBArm =/= xlateBArmPrev)
val reqStale = RegNext(presentedAccessChanging, init = False)
val reqFresh = !reqStale
val reqMatch = reqReg.valid && reqFresh
```

`reqReg.valid`/`reqReg.vpn`/`reqReg.sup`/`reqReg.write`/`reqReg.robId`
(`:1735-1739`) and every other use of `reqReg` (the actual `xlate.req`
drive, `:1740-1744`) are **completely unchanged** — this slice touches
ONLY how staleness is DETECTED, not what gets sent to the DTLB or when.

### Correctness argument (binding — re-derive, do not transcribe)

**Claim: `reqFresh` reads False on EXACTLY the cycle(s) the OLD value
compare would also have read False due to `reqReg` genuinely lagging a
real change to `xlateVaddr`, and True on every cycle `xlateVaddr` has been
stable for at least one full cycle** — with one explicit, harmless,
MORE-conservative divergence documented below, not silently introduced.

**Step 1 — enumerate every way `xlateVaddr` can change (closure):**
`xlateVaddr = Mux(xlateBArm, s1AddrB, s1Va)`. `s1AddrB` is purely
combinational off `s1Va` (`:405`, `(s1Va & ~15) + 16`) which is itself
purely combinational off `s1Base`/`s1Disp`/`s1Index` (`:367`), all of which
are `Reg`s written ONLY inside `when(issuePort.fire) { ... }`
(`:1126-1131`). So `s1Va`/`s1AddrB`'s VALUES can change only on the cycle
immediately after an `issuePort.fire`. `xlateBArm` is a `Reg` written ONLY
at `:1229` (True) and `:1267`/`:1275` (False) — no other writer exists
(grepped). So `xlateVaddr`'s SOURCE SELECTION can change only on the cycle
immediately after `xlateBArm` is written. **These are the only two
register-write sites that can affect `xlateVaddr`'s output — closed set,
confirmed by grep, no third site exists.**

**Step 2 — no NEW `issuePort.fire` can land while a translation is
pending (closes the "could `s1Va` also change mid-wait" question):**
`issuePort.ready := !busy && !s1Valid && !compValid` (`:1085`). `busy` is
set `True` the SAME cycle IDLE enters its `elsewhen(isLoad || isStore)`
branch (`:1188`) and stays `True` through every downstream FSM state until
the access fully resolves (every state after IDLE sets `busy := True` at
its own entry — `XLATE_B:1260`, `XLATE:1285`, etc. — this project's own
FSM convention). `s1Valid` is likewise held `True` throughout (explicit
re-assert at `:1239` during a stall; only cleared at a terminal
`goto(IDLE)` with `s1Valid := False`). So `issuePort.ready` is
provably `False` for the ENTIRE window between "a translation becomes
pending" and "it resolves" — **no new access can be captured, hence
`s1Base`/`s1Va`/`isStore`/`u1` cannot change, during that window.** This
also means the write-mode (`isStore`) term the OLD code compared needs no
separate tracking: it changes ONLY in lockstep with `s1Base` at the exact
same `issuePort.fire` event `presentedAccessChanging` already tracks.

**Step 3 — `xlateBArm`'s False-transition is a don't-care for `reqMatch`,
so tracking it via `=/=` (both edges) rather than `=== True` specifically
is deliberately over-conservative, not under:** `xlateBArm` is cleared
False at `:1267` (on a fault, `goto(IDLE)`) and `:1275` (on success,
`goto(XLATE)`) — both transitions LEAVE `XLATE_B`, and per Step 0 above,
`reqMatch` is never consumed again after `XLATE_B`. So forcing `reqFresh`
false for one extra, unconsumed cycle on the False-edge too is harmless —
it costs nothing (nobody reads `reqMatch` there) and keeping the detector
symmetric (`=/=` on both edges) is simpler and more robust against a
future refactor of the exact nested `when` conditions than re-deriving the
True-transition's specific guard expression.

**Step 4 — the one explicit, KNOWN, harmless divergence from the old code
(disclose, don't hide):** the OLD value-compare can, in principle,
spuriously read `reqMatch = True` ONE CYCLE EARLIER than the new
event-driven `reqFresh` would, in the rare case a freshly-presented
access's VPN happens to COINCIDE with whatever `reqReg` was already
holding from the immediately-preceding access (e.g., two consecutive
accesses on the same page, or — the case the grounding report's own
framing anticipated — a split access whose slot-B page coincides with
slot-A's page). In that coincidence, the OLD code's bit-exact compare
would already read `reqReg.vpn === xlateVaddr` as true on the very first
cycle the new value appears, before `reqReg` has genuinely re-captured it.
**The new event-driven flag CANNOT do this — it forces the same
one-cycle settle unconditionally, regardless of value coincidence.** This
is a strictly MORE conservative behavior (never an early/wrong consume,
only ever a possible extra one-cycle stall in an already-rare coincidence)
— it can add at most one cycle of harmless latency in an edge case, never
cause a stale-translation bug. This divergence must be called out in the
implementation's commit message and the task's report, not silently
absorbed; the directed test in Verification Requirements below must
specifically construct this coincidental-VPN scenario and confirm the new
code is still SAFE (correct final address consumed, at most one extra
stall cycle) even though its cycle-exact timing differs from the old
code's in this one case.

**Step 5 — the DTLB-miss stall interacts with nothing new.**
`xlateReady = xlate.rsp.ready` (`:425`), independent of this change.
`reqMatch` is unconditionally ANDed with `xlateReady` at both of its
consumption sites (`:1194`, `:1261`) — while `xlateReady` is False (DTLB
walking a miss), `reqMatch`'s value is moot regardless of which
implementation drives it. No new interaction to reason about.

## Non-goals

- No change to `reqReg`'s own capture logic, `xlate.req`'s drive, DTLB-miss
  stall behavior, MMU-off/identity-mode behavior, or split-access (slot
  A/B) semantics — only the MECHANISM detecting staleness.
- No change to `LsEuPlugin.scala` outside the ~10-line block replacing
  `reqMatch`'s definition (plus the 3 new supporting signals
  `xlateBArmPrev`/`presentedAccessChanging`/`reqStale`).
- Lever B (register the deferred-replay ROB completion ports) and Lever C
  (flatten the ROB fault write-enable decode) from the grounding report —
  separate, larger-scoped fixes, not part of this slice.
- Task #127 (fold ROB per-entry Reg arrays into Mem/BRAM) — the grounding
  report explicitly recommends against scoping this as part of any single
  FMax slice (unbounded, multi-writer arbitration redesign, and measures
  as 0MHz gain alone).

## Verification requirements (binding)

- `~/sbt/bin/sbt compile` clean.
- Locate the existing DTLB/MMU-adjacent test suites (grep `Dtlb`/`Mmu` over
  `src/test/scala/` — do not guess file names) and confirm full pass.
- **New directed test, the coincidental-VPN-match scenario (Step 4
  above)**: construct a split access (`s1TwoAccess`) where slot A and slot
  B's addresses fall on the SAME page (same VPN), or — simpler and equally
  valid — two back-to-back single accesses whose VPNs coincide. Confirm:
  (a) the OLD code (if checked out in an isolated worktree for comparison)
  may consume one cycle earlier than the NEW code in this exact scenario —
  confirm this explicitly rather than assuming it, mirroring this
  session's established practice of proving a claimed behavioral delta by
  actually running both versions; (b) the NEW code still produces the
  CORRECT final translated address and fault behavior, just potentially
  one cycle later; (c) neither version produces a WRONG address.
- **New directed test, a genuinely stale scenario**: an access is
  presented, translation is NOT yet ready (or a NEW access supersedes an
  in-flight one is not reachable per Step 2's closure — construct instead:
  an access whose translation resolves on the SAME cycle `xlateBArm`
  toggles, i.e. the split-access slot-A-to-slot-B transition) — confirm
  `reqFresh`/`reqMatch` correctly reads False for exactly one cycle at the
  transition and the FSM correctly stalls rather than consuming a stale
  DTLB response meant for the other slot.
- `ExecuteLockStepSpec` full suite: expect 390/394 (this session's standing
  baseline — the same 4 pre-existing failures).
- Full ported test corpus (~870 tests) via
  `tools/fuzz/ported-sweep-parallel.sh`, isolated git worktrees: zero new
  regressions vs. the current baseline (check the most recent progress
  ledger, e.g. `.superpowers/sdd/progress-fmax-slice3.md`'s "THE REVERT"
  section, for the exact current fail count/list).
- Targeted MMU/split-access ported tests specifically (grep for
  `mmu_split`/`idx_alias`/split-access-family test names): run explicitly
  before the full sweep, this is the highest-risk area for this specific
  change.
- OOC-synth-only AND the real post-route gate. **Explicit caveat (learned
  this session, do not skip):** OOC and post-route have been observed to
  DISAGREE about which paths are critical in this design — do not treat an
  OOC result alone as a verdict. Report the measured delta on BOTH gates.
  Given this grounding report's own finding that Lever A alone measures
  0MHz post-route gain (a different family currently holds WNS), the
  success criterion for THIS slice in isolation is causal: confirm the
  `eaAuto -> faultAddrStore` path family's specific slack IMPROVES (even
  if it doesn't yet become the new WNS-holder), and confirm no regression
  anywhere else. The real accept/reject gate is the COMBINED slice (this
  lever plus a frontend lever, per the grounding report's own scoping
  recommendation) — do not judge this lever's implementation task on a
  standalone post-route number.
