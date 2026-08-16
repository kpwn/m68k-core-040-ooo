# FP-Control Multi-Word Transfer Design

**Status:** Design complete, approved, ready for `writing-plans`/re-briefing.

**Goal:** A safe, general mechanism for moving multiple words between memory and the FPU's
non-renamed control state (FPCR/FPSR/FPIAR) and FPCC as part of one macro-instruction, that
unblocks both the blocked Task 9b (FMOVEM control-register LIST form) and the not-yet-briefed
Task 11 (FSAVE/FRESTORE frames) of the FPU/FPSP implementation plan
(`docs/superpowers/plans/2026-08-15-fpu-fpsp-implementation-plan.md`).

**Architecture:** Split every multi-word FP-control transfer into (a) ordinary, already-proven
LS-cluster loads/stores for ALL memory movement in both directions, and (b) a small, purely
additive extension of Task 9's existing single-register commit-time mechanism for the one
step that genuinely needs it — writing the final values into `FpuControlPlugin`/the renamed
FPCC PRF. No changes to this project's `sysOp`/`excActive` squash-and-redirect machinery are
needed. No new scoreboard is needed.

**Tech Stack:** SpinalHDL, this project's existing microcode engine (`Microcode.scala`), the
existing LS EU/DTLB translation+fault path, the existing CPLX-cluster dynamic-wakeup
scoreboard (`IssueQueuePlugin.scala`), Task 9's existing `FpuControlPlugin`/`SysKind.
FMOVE_FPCTRL`/`ExceptionUnit.S_APPLY` machinery (extended, not replaced).

---

## Background

Task 9b (FMOVEM control-register list form) was dispatched against a brief that assumed each
selected register's transfer could be a separate `SysKind.FMOVE_FPCTRL` sysOp instance,
interleaved with ordinary microcode loads/stores. The implementer refused to build it and
reported `STATUS: BLOCKED`, with a rigorous, independently-verified proof (two separate
verification passes, both confirming the finding against the live RTL): a `sysOp` uop
retiring at the ROB head is architecturally a **serializing pipeline boundary** —
`RobPlugin.scala`'s `excActive`-driven squash (`excSquash := excActive` → `tail := head;
count := 0`) plus `ExceptionUnit.scala`'s `S_REDIR` state (`redirectPc := sysCapNextPc`, the
**macro** instruction's next-PC) together mean **at most one sysOp can take effect per
macro-instruction, and it must be the truly last µop** — anything after it in the same
microcode program never executes, with no fault raised. This rules out the brief's
decomposition for any `popcount ≥ 2` case.

The same investigation found that Task 11's own (already-written) brief independently walked
into a **different but related** flaw: it proposes routing FSAVE/FRESTORE's frame words
through `ExceptionUnit`'s own `E_STORE`/`E_VECREQ` loops, which are identity-physical (no MMU
translation — confirmed dead/unwired DTLB port), hardwired supervisor, and have zero
fault-delivery machinery. FSAVE/FRESTORE genuinely are privileged, so the hardwired-supervisor
half is architecturally correct for them — but the missing translation and fault-delivery
gaps are fully live: a supervisor FSAVE under MMU-on with a translated stack, or to an
unmapped frame page, would silently corrupt memory or hang with no fault.

Both tasks need the same underlying capability: **move N words safely to/from a computed
`<ea>`, as part of one instruction that also needs to touch non-renamed FP control state
(FPCR/FPSR/FPIAR) and/or the renamed FPCC.** This document designs that capability once, so
neither task re-derives or re-breaks it.

## Key structural fact this design exploits

An ordinary microcode `Desc` row with `mem = MLoad`/`MStore` is **not special** — it resolves
into an ordinary `DecodedUop` with `cluster := Cluster.LS`, indistinguishable downstream from
a hand-written `MOVE.L (An),Dn`. It goes through the **exact same** LS EU path as every other
load/store in this design: real DTLB translation (`LsEuPlugin.scala`'s `xlate.req`/`xlate.rsp`,
keyed off the triggering instruction's own captured committed-supervisor bit, not a hardwired
value), real precise fault delivery (`LsFault`/`faultCompletion`, the identical mechanism a
non-microcoded load/store uses, driving a real format-$7 access-fault frame), and it **never**
sets `sysOp`, so it never touches `excActive`/`excSquash` at all.

Multiple `MLoad`/`MStore` rows in one microcode program is not a hypothetical — it is already
shipped at scale in this codebase: MOVE16 (8 memory rows), Task 6b's F-line FP-generic memory
loads (up to 3 consecutive `MLoad` rows assembling an 80-bit Extended value from narrower
chunks — the exact "wide value via multiple scratch-temp chunks" pattern this design reuses
for FSAVE's 80-bit operand fields), CAS2, and — the closest precedent for a
runtime-variable-count multi-transfer — the existing MOVEM FSM (`DecodeStage.scala`'s
`movemMask`-driven sequencer), which already does exactly "N ordinary, fully safe LS
transfers, count known only at runtime, one final An-writeback uop" through this same path.

**Conclusion: the memory-movement half of this problem is already solved.** The only
remaining question is how the FP-control-state half — which cannot use an arbitrary number of
ordinary LS EU rows, since it isn't memory at all — fits around it without reintroducing the
"more than one sysOp per program" problem.

## Locked Decisions

### 1. All memory movement, both directions, rides ordinary microcode LS rows

FMOVEM-control-list and FSAVE/FRESTORE's frame words are moved via ordinary `Desc(mem =
MLoad/MStore, ...)` rows, exactly like MOVE16/MOVEM/Task 6b. This is true for **both**
directions and **both** instruction families — there is no case where a memory transfer needs
to be anything other than an ordinary LS-cluster row. This single decision is what closes
Task 11's translation/privilege/fault gaps: by construction, these transfers get real DTLB
translation and real fault delivery for free, with **zero** new RTL for that half of the
problem.

Consequence for Task 11 specifically: the `<ea>`-scope restriction its own brief imposed
("displacement/absolute forms need a real EA computation the commit-time sysOp path has no
AGU for") **no longer applies** — the ordinary microcode EA-decode machinery
(`ucCasEaDec`/`EaDecoder.decode`, already unconditional for every microcoded instruction) has
a full AGU. Whoever re-briefs Task 11 should treat the wider `<ea>` scope as available, not
forbidden, and make an explicit choice about whether to take it (see Task 11 scope note,
below) rather than inheriting the old restriction's rationale, which is now moot.

### 2. Load direction (mem → control): one small, purely additive extension to Task 9's existing pattern

`mem → control` naturally sequences as *get data, then apply it to control state* — which is
exactly what "one sysOp, last row" already supports:

```
[ordinary LS EU: MLoad × N]   mem -> T0..T(N-1)   (translated, privilege-checked, fault-capable)
[ONE terminal sysOp]          T0..T(N-1) -> {FPCR, FPSR (incl. FPCC), FPIAR, ...}
```

The terminal sysOp is a **new `SysKind`** (e.g. `FMOVEM_FPCTRL` for Task 9b;
Task 11 may reuse the same kind or mint its own — see below), whose `S_APPLY` arm is a
straightforward generalization of Task 9's existing `FMOVE_FPCTRL` arm
(`ExceptionUnit.scala:1493-1527`): instead of gating on a one-hot `sysCapRc` and pulsing
exactly one of `{setFpcr, setFpsr+fpccWrite, setFpiar}`, the new arm pulses **any subset** of
them in the same single-cycle combinational block — three independent `when(mask(k)) { ... }`
terms instead of a mutually-exclusive `elsewhen` chain. This is not a new FSM state and does
not need internal looping: writing up to three `Flow` ports in the same cycle is just three
parallel `when`s. (Precedent for `S_APPLY` branching into a genuinely multi-cycle sub-sequence
also already exists — `S_MAINTWAIT`, added for CPUSH/CINV — if a future consumer needs more
than a single-cycle apply; Task 9b/11's own register counts do not.)

For Task 11's unimplemented-instruction frame (`uiCmdReg1B`, `uiSrcOperand`/`uiDstOperand`,
80 bits each), the same "N ordinary loads into scratch temps, one terminal sysOp applies them
all" shape applies — the wide operands assemble via multiple scratch-temp chunks exactly as
Task 6b's own memory-source Extended-format loads already do (`MEMEXT`'s
`{intRdA,intRdB,intRdH}` three-chunk assembly is the direct precedent).

### 3. Store direction (control → mem): the register read needs **no new mechanism** — reuse two already-existing, already-safe patterns

`control → mem` naturally sequences as *read control state, then write it to memory* — which
conflicts with "sysOp must be last," since the register read would need to be **first**. This
is the one direction Task 9b's original brief and Task 11's own brief both got wrong (in
different ways). The resolution splits the read into two pieces, each already safe by an
existing, load-bearing property of this design — **not** by inventing a new interlock:

**FPCR / FPIAR / FPSR's non-FPCC bytes — plain, ungated, ordinary reads.**
`FpuControlPlugin`'s `fpcr`/`fpsr`/`fpiar` are plain `Reg`s with exactly one writer in the
entire design (`ExceptionUnit.S_APPLY`, always via the serializing sysOp mechanism). This is
**structurally identical** to `MmuControlService`'s `urp`/`srp`/`mmuEnable`/`dtt0`/`dtt1` —
also non-renamed, single-copy, written only from `S_APPLY` — which are **already read live,
continuously, with zero scoreboard or interlock**, by ordinary datapath consumers today in
production (`DtlbPlugin.scala:94-99`, `LsEuPlugin.scala:202,542`, `ItlbPlugin.scala:87`).

The reason this is safe, confirmed directly from the RTL (not assumed): ROB retirement is
strictly in-order, so a younger reader can never retire before an older writer. Once the older
sysOp write reaches the ROB head and triggers (`sysTriggerSig`), `excSquash` unconditionally
empties the **entire** ROB (`tail := head; count := 0`) on the very next cycle — which removes
every younger, not-yet-retired entry, including any reader that had spec­ulatively executed
and read a stale value. That reader's result, having never retired, is simply abandoned (the
RAT rollback restores the pre-speculation mapping). `S_REDIR`'s subsequent unconditional
redirect re-fetches everything younger fresh, so the reader re-executes **after** the write has
landed. This is exactly the property `MmuControlService`'s existing live reads already exploit
— this design reuses it for `FpuControlPlugin`'s registers rather than inventing a parallel
mechanism.

A new microcode `Sel` (mirroring the existing `SEaBase`/`SEaDispLo` selector shape) reads
`fpuCtrl.fpcr`/`.fpsr`/`.fpiar` combinationally into a scratch temp — genuinely one line per
register, no new state, no scoreboard.

**FPCC — reuse the existing renamed-register machinery, don't bypass it.**
Unlike FPCR/FPIAR, FPCC **is** renamed and shares a PRF write port with the FP EU's own
writeback lane (Task 2/3's `fpccRat`/`RegFilePluginFpcc`) — this is the one piece Task 9's own
review flagged as "materially harder," and it stays harder here for the same reason. But it
already has the exact protection an ordinary consumer needs: the CPLX-cluster **dynamic
wakeup scoreboard** (`IssueQueuePlugin.scala`'s `cplxFpccBusy`/`cplxFpccWait`/
`cplxFpccWakeupPort`, the same mechanism that already lets `Fbcc`/`FScc` safely read FPCC
today). The new microcode row simply decodes as an ordinary `readsFpcc` consumer with a real
`pFpccSrc` tag, exactly like any other FPCC-reading instruction — no new mechanism, just using
the one that already exists for exactly this class of hazard.

```
[NEW: plain combinational reads]  fpuCtrl.fpcr/.fpsr/.fpiar -> Ti   (mirrors MmuControlService's
                                                                      already-safe live-read pattern)
[EXISTING: ordinary readsFpcc]    committed FPCC (via dynamic wakeup) -> Tj
[ordinary LS EU: MStore × N]      T0..T(N-1) -> mem   (translated, privilege-checked, fault-capable)
```

No sysOp is used in the store direction at all. No squash/redirect changes anywhere. No new
scoreboard.

### 4. Forward-looking caveat, flagged not solved: `orFpsrExc`

`FpuControlPlugin.orFpsrExc` (the FP EU's exception-status OR-in port, `FpuControlPlugin.
scala:133-136`) has **no producer wired today** (confirmed: Task 8 landed before Task 9's
service existed; `roundingMode`/`excEnable` also have no consumer yet — see the FPU plan
ledger's carried-forward flag). If a future task wires this up, it becomes a **second,
non-sysOp, non-serializing writer of FPSR's exception-status bits** — Decision 3's plain-read
safety argument for FPSR specifically (not FPCR/FPIAR, which have no such second writer) would
then rest on a narrower property: `orFpsrExc` only ever sets **sticky accrual bits** (AEXC),
so the worst case of a stale read racing it is missing one cycle's newly-accrued sticky bit in
a snapshot — not a torn/incoherent value. This is very likely an acceptable, low-severity race
for FMOVEM-control/FSAVE's purposes (a snapshot that's one cycle late to reflect a
simultaneously-occurring FP exception), but it must be **assessed for real, not assumed**,
whenever `orFpsrExc` gets a real producer. Whoever wires up `orFpsrExc` should re-read this
section and either (a) confirm the sticky-bit argument holds and document it, or (b) give
`orFpsrExc`'s accrual its own dynamic-wakeup-style interlock (it has a real renamed/tracked FP
op with a real completion event behind it, unlike FPCR/FPIAR, so the existing tag-based CPLX
scoreboard pattern — not a new mechanism — would be the right tool if one turns out to be
needed).

### 5. What this design deliberately does NOT touch

- `RobPlugin.scala`'s `excActive`/`excSquash`/`flushing` — unchanged.
- `ExceptionUnit.scala`'s `S_REDIR`/`S_DRAIN` and the "at most one sysOp, and it must be last"
  invariant — unchanged, in fact load-bearing and exploited (Decision 3), not fought.
- `LsEuPlugin.scala`'s `excActive`-driven shutdown during a real exception episode —
  unchanged; this design never runs LS transfers concurrently with an active `ExceptionUnit`
  FSM episode, it only ever uses LS transfers as ordinary in-order microcode rows *before* the
  one terminal sysOp (load direction) or *after* a plain register read that used no FSM at all
  (store direction).
- `MicrocodeResolveEquivalenceSpec`'s dual-path (`resolve()`/`resolveFromBits()`) discipline —
  applies as normal to the new `Sel`/`UOp` additions, no different from any other microcode
  change.

This is what keeps the risk bounded: the new surface area is (a) a handful of new microcode
`Sel`s that are plain combinational reads, (b) one new `SysKind` whose `S_APPLY` arm is a
mechanical generalization of an already-reviewed arm from one-hot to any-subset, and (c)
correctly classifying a new microcode row as an ordinary `readsFpcc` consumer. Nothing about
how sysOps retire, squash, or redirect changes.

---

## Consequences for re-briefing Task 9b

- The register-order finding from the blocked attempt stands and is unaffected by this
  redesign: Divergence Register entries **D9/D9a/D9b** (real MC68881/MC68882 UM + M68000 PRM
  citations, page 5-91 and 4-76) already establish the correct mechanism — registers always
  move FPCR→FPSR→FPIAR ascending; `-(An)` is a single up-front `An -= 4×popcount`, not a
  per-transfer reversal. Since all memory movement is now ordinary LS EU rows with real AGU
  support, `-(An)`'s address computation is just an ordinary predecrement `<ea>` — the
  ordering question only matters for the **row order** in the generated microcode program
  (FPCR's row first, FPSR's second, FPIAR's third, always), not for any hardware reversal
  logic.
- `popcount == 1` with a memory `<ea>` (`fpu_fmove_mem_ea_fpcr_fpsr_no_fline.s`, previously
  deferred because it split asymmetrically under the broken design) is **no longer
  asymmetric** — it's just the `N=1` case of the same uniform mechanism. The re-brief should
  fold it in.
- The corrected encodings from the blocked attempt's real toolchain re-run
  (`F21F 9C00`, `F227 B800`) and the confirmed `RRR` bit assignment (bit12=FPCR, bit11=FPSR,
  bit10=FPIAR) carry forward unchanged.

## Consequences for briefing Task 11

- Task 11 has not yet been dispatched. Its brief (already drafted in the plan document) should
  be revised **before** dispatch to use this design's mechanism instead of `ExceptionUnit`'s
  `E_STORE`/`E_VECREQ` loops for frame word transfers.
- The `<ea>`-scope restriction to register-indirect-only forms (Decision 1) should be revisited
  explicitly — the plan's own stated reason for it ("no AGU on the commit-time path") no longer
  applies. Whether to widen scope is a real scope call for whoever re-briefs Task 11, not
  something this design mandates either way.
- FSAVE/FRESTORE stay privileged (unchanged) — Decision 1-3's mechanism doesn't touch
  privilege at all; `RobPlugin`'s default sysOp-is-privileged behavior for new `SysKind`s
  still applies and is correct for these two instructions.
- The null/idle/unimplemented-instruction frame sizes and field layout work already done in
  Task 11's brief (4/4/44 bytes, the `$08` CMDREG1B anchor, the still-BLOCKING MC68040 UM
  Figure 9-7 verification for STAG/DTAG/E1/ETS/ETE/ETM/FPTS/FPTE/FPTM) is unaffected by this
  design and carries forward as-is.

---

## Open items (explicitly not resolved here — flag, don't guess)

- **§4's `orFpsrExc` caveat** — not this design's job to close, must be re-examined by whoever
  wires up the FP EU's exception-status accrual.
- **Exact new `Sel`/`SysKind` names and the precise `S_APPLY` arm restructuring** — left to the
  re-briefed Task 9b's implementer, following Decision 2/3's shape exactly; this document
  fixes the mechanism, not the Scala identifiers.
- **Whether Task 9b and Task 11 share one `SysKind` or mint two** — both are structurally the
  same "batch commit-time apply" shape; sharing reduces `ExceptionUnit.S_APPLY` surface area
  but couples the two tasks' review cycles. Left as an implementation-time call.
