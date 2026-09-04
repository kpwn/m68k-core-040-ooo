# RAS `checkpointSave` uses `rob.count === 0`, which the frontend's run-ahead makes unsound

**Status: not fixed. Scoped, not started. This is a prediction-QUALITY defect, never
an architectural-correctness one** — the branch EU verifies every RTS/RTR target
against the value actually loaded from the stack, so a wrong RAS prediction costs
cycles and nothing else. Filed 2026-09-04 during the night-work consolidation.

## 1. What the RTL promises

`RasPlugin` (`src/main/scala/m68k040/frontend/Ras.scala`) recovers speculative RAS
state with a checkpoint/restore pair rather than a per-branch checkpoint stack:

* `checkpointRestore` — copy `ckRasSp`/`ckCount`/`ckRas` back into the live state.
  Driven from `doFlush || fa.logic.ftqMismatch`.
* `checkpointSave` — refresh the checkpoint from the live state. Driven from
  `rob.logic.count === 0 && !checkpointRestore`.

Wired identically in all four DUT copies (`FullCoreSynth.scala:159`,
`FuzzDut.scala:215`, `ExecuteLockStepSpec.scala:309`, and `IpcBenchSpec`).

The soundness argument for the `rob.count === 0` proxy is stated in Ras.scala's own
class comment: *"at that instant NOTHING is outstanding/unresolved, so the live RAS
state is, by construction, exactly the architecturally correct one (module the few
cycles of fetch->dispatch pipeline latency, an accepted, bounded approximation)."*

## 2. The gap, measured

That parenthetical is not a rounding error; it is the whole failure mode. **The ROB
being empty says nothing about the frontend**, which by design runs ahead of dispatch.
`rasPushValid` is asserted at FetchAlign (`FetchAlignPlugin.scala:1225`,
`feed.fire && !faultHold && s0IsCall`) — many cycles before the same call reaches the
ROB. So there is a window in which:

* the ROB is empty (`rob.count === 0`) → `checkpointSave` is asserted, **and**
* the frontend has already pushed a *speculative, wrong-path* return address.

On that cycle the checkpoint captures `nextCount` — i.e. it **saves the phantom entry
as if it were architecturally correct**. The later `checkpointRestore` then faithfully
restores the phantom, and the rollback has strictly no effect.

Measured directly on unmodified mainline (`afbabdd`), whitebox probe on
`ras.logic.{pushValid,pushRetPc,popValid,count,rasSp,checkpointSave,checkpointRestore,ckCount}`,
running the existing `ras-rollback-no-phantom-leak` program:

```
[rasprobe] cyc=108 push=true  retPc=0x40800018 cnt=0 sp=0 save=true  rest=false ckCnt=0
[rasprobe] cyc=110 push=false                  pop=true cnt=1 sp=1 save=false rest=false ckCnt=1
[rasprobe] cyc=116 push=false                  pop=false cnt=0 sp=0 save=false rest=true  ckCnt=1
[rasprobe] cyc=120 push=true  retPc=0x40800018 cnt=1 sp=1 save=true  rest=false ckCnt=1
```

Read it as: at cyc 108 a **wrong-path** `bsr leaf` (the fall-through past the
not-yet-resolved `beq` runs straight into `skip:`) pushes `0x40800018` — and
`save=true` on the *same cycle*, so `ckCount` goes 0 → 1. At cyc 116 the `beq`'s
commit-time flush restores that contaminated checkpoint. At cyc 120 the **real**
`bsr leaf` pushes onto a RAS that still holds the phantom (`cnt=1`).

The concurrent rename trace confirms the wrong path was genuinely walked and that
`0x4080000a`'s `bsr wrongcall` was fetched:

```
[ren] g=22 s0 pc=0x40800006  (beq)
[ren] g=22 s1 pc=0x4080000a  dst=15   (bsr wrongcall — A7 push µop)
[ren] g=23 s1 pc=0x4080000e  dst=5    (moveq #99,%d5 — wrong-path filler)
[ren] g=25 s0 pc=0x40800014  dst=15   (bsr leaf, reached by fall-through)
```

## 3. Why this is structural, not a tuning problem

For the *specific* program in `ras-rollback-no-phantom-leak` the contamination is
**unavoidable**: the mispredicting `beq` is the third instruction, so every cycle of
the wrong-path excursion happens while the ROB is still filling and `rob.count === 0`
holds. There is no seed for which that program takes a wrong-path push *and* keeps a
clean checkpoint. That is why the test has never passed (SS5).

The general statement is weaker but still real: after **any** ROB drain the frontend
may already have fetched past the drain point, so `checkpointSave` can capture
speculative pushes at any point in a program, not only during startup.

## 3b. A second, independent gap found while measuring this one: slot-1 calls never push

Also unrecorded until now, and visible in the trace above: **`bsr wrongcall` at
`0x4080000a` was fetched and renamed but produced no RAS push at all.** It was emitted
in fetch **slot 1** (`[ren] g=22 s1`), and

```
rasPushValid := feed.fire && !faultHold && s0IsCall      // FetchAlignPlugin.scala:1225
```

only ever pushes for **slot 0**. The aligner is asymmetric here, deliberately for
returns and apparently by omission for calls:

* a RETURN in slot 1 is **deferred** — `slot1WouldRasPred` suppresses slot 1 so the
  return becomes slot 0 next cycle and takes the single-slot RAS-predict path
  (`FetchAlignPlugin.scala:945-947, 993-996`). Its own comment explains why: without
  it, `add ; rts` pairs and "every return mispredicts".
* a CALL in slot 1 gets **no** equivalent treatment. It simply does not push.

Consequence: for every BSR/JSR that happens to align into slot 1, the RAS is one entry
short, so the matching RTS pops *the wrong entry* (or finds the stack empty and gets no
prediction at all). Architecturally harmless — the branch EU verifies — but it is a
silent, alignment-dependent hole in exactly the structure the RAS exists to be.

Not measured: how often calls land in slot 1 in real code, and therefore what this
costs. That measurement, and the symmetric `slot1WouldRasPush` deferral that would fix
it, are both **scoped and not started**.

## 4. What a fix looks like (scoped, NOT implemented)

The sound version is the one Ras.scala's own comment names and then declines:
maintain a **committed shadow** updated at *retire*, the way `RenameStage` keeps a
committed RAT/Freelist. The ROB already knows, at retire, whether a macro was a call
(`stkPush`) or a return (`isReturn`) and what its return PC was, so:

* on retire of a call → `ckRas[ckRasSp] := retPc; ckRasSp++; ckCount++`
* on retire of a return → `ckRasSp--; ckCount--`
* `checkpointRestore` unchanged.

This is precise (no proxy), needs no `rob.count` term at all, and is the same shape as
the existing rename recovery. It is **real ROB-side plumbing on the retire path** —
a new retire-to-frontend port — so it needs its own design pass, its own directed
test, and its own postroute gate. It was explicitly out of scope for the narrowly
scoped predictor fix that introduced the checkpoint (`683355d`), and it is out of
scope for the consolidation session that filed this document.

## 5. Test status

`ExecuteLockStepSpec`'s
`"lock-step: mispredicted branch with a wrong-path UNMATCHED bsr does not leak a
phantom RAS entry — rollback-on-flush fix"` asserts the *precise* per-branch
guarantee, which the design does not make.

**It has never passed.** It fails at its own introducing commit `6962f72`, whose
message states it was *"pre-existing uncommitted work found in this worktree"*
committed "rather than discarding it" — i.e. it was never run before being committed.
It is therefore **not** a regression of previously-fixed behaviour.

It fails in two distinct ways depending on the SpinalSim seed (uninitialised registers
randomise per run), which is why two different messages have been reported for it:

| seed-dependent outcome | assertion that fires |
|---|---|
| the flush lands before the frontend gets far enough to push at all | `expected exactly 2 pushes … got 1: ArrayBuffer(0)` |
| the wrong-path push happens and is checkpointed as good | `the real bsr leaf push must see an empty RAS post-flush … got count=1` |

Worse, the test does not measure what its name claims. Its intended mechanism is an
**UNMATCHED** wrong-path call (`bsr wrongcall`, whose target is a self-loop, so no
return is ever fetched). Per SS3b that call lands in slot 1 and **never pushes**. The
push that is actually observed is a wrong-path `bsr leaf` reached by fall-through, and
that one *is* matched — its own wrong-path `rts` pops it two cycles later (cyc 108
push / cyc 110 pop above). So even the leak the test does catch is a
checkpoint-contamination artefact, not the unmatched-call drift it was written for.

Making it non-vacuous therefore needs BOTH: the ROB kept busy across the whole
wrong-path excursion (so `checkpointSave` cannot fire), AND the unmatched call pinned
into fetch slot 0 (so it actually pushes). The second half is alignment-dependent and
cannot be arranged from the assembly text alone. That is why this was parked rather
than patched.

Both messages are the same underlying fact. See the consolidation write-up
`docs/superpowers/specs/2026-09-04-night-work-consolidation.md` SS3 for the disposition.

## 6. Explicitly NOT claimed

* Not claimed that the checkpoint/restore mechanism itself is broken. The restore
  faithfully restores whatever `checkpointSave` captured; the defect is entirely in
  *when* the save is allowed to fire.
* Not claimed that this affects architectural correctness. It does not: every RTS/RTR
  target is EU-verified.
* No IPC cost has been measured for this. The `683355d` change that introduced the
  checkpoint was itself never IPC-A/B'd against the "accept corruption" baseline, so
  the size of the remaining loss is unknown in both directions.
* No RTL was changed. No synthesis was run for this document.
