# BUG: with `lsOooIssue` ON, the inhibited memory barrier PARKS IN P4 and starves the
# ROB head -- the third instance of one circular wait

**Status**: **FIXED** (the deadlock) by the depth-4 INHIBITED PARK BUFFER in `LsEuPlugin`,
behind the existing default-OFF `lsOooIssue` knob. `LsOooInhibitedOrderSpec` is now GREEN
with the knob ON. Two further defects found while gating it are recorded at the bottom and
are NOT fixed: the barrier's recovery half had never been wired in any sim harness, and it
is dead for multi-uop macros. The bug was **PRE-EXISTING -- not caused by the barrier
work**. Reproduced on
`aa312145` (before `b4fc15db`) with the IQ relaxation alone and no barrier code present;
see the measured table below. Likely the mechanism behind the recorded silicon wedge
attributed to `loadBypassUnreadyLoad`. Reproducer committed and RED BY DESIGN with the knob
ON:
`src/test/scala/m68k040/fuzz/LsOooInhibitedOrderSpec.scala` (tagged `VerilatorTest`, so
it is not in `test-fast`). The shipping default (`FUZZ_LS_OOO` unset / `lsOooIssue =
false`) is unaffected and the spec PASSES there. No RTL fix yet; the candidates are
priced at the bottom of this file.

**This file also CORRECTS a claim in commit `dbff8619`.** That commit says the barrier is

> "deadlock-free BY CONSTRUCTION rather than by argument -- no send can ever be held, so
> the circular wait has no edge to close on"

That is WRONG, and a future reader will otherwise trust it. It removed ONE edge of the
cycle (the ring send) and left another (P4 occupancy). The correct statement is: *nothing
gates the ring send, so the SEND cannot close the cycle -- but P4 still can, and does.*
The in-file comment at `LsEuPlugin.scala` ("WHY THE INHIBITED BARRIER DOES NOT GATE THIS
SEND") is accurate about the send and should be read together with this file, not as a
deadlock-freedom proof.

## How to reproduce

    FUZZ_LS_OOO=1 SBT_OPTS=-Xmx6G ~/sbt/bin/sbt "testOnly m68k040.fuzz.LsOooInhibitedOrderSpec"

Fails in ~45 s (one Verilator build). Without `FUZZ_LS_OOO=1` the same spec passes.

## Measured state at the wedge (2026-09-26, `92a89e98`)

The spec's `OldestLsProgress` watchdog trips and dumps the LS pipeline. A deadlock's
state is static, so this is the whole picture:

    head=3 headValid=true issue(v=true,r=false,rob=16)
    s1(v=true,rob=11) t(v=true,rob=9) tx(v=true,rob=5,second=false)
    p3(v=true,rob=3) p4(v=true,rob=7)
    p4Inhibited=true p4AtRobHead=false p4LaunchOk=false p4RetryQuery=false
    fwdHit=false fwdStall=false fwdSerial=false paddr=0xffff0100
    ring count=0 full=false sendPtr=0 rspPtr=0 pushPtr=0 sendValid=false sendHeld=false
    sq empty=true full=false olderStore=false olderInhibStore=false
    loadBusy=false respSeen=false ldOwner=0 orderViol=false

    OLDEST IN-FLIGHT LS OP HAS NO PATH TO COMPLETION: robId=3 issued at cycle 30, still
    oldest and un-completed 5001 cycles later (in-flight set: 3,5,7,9,11)

`{3,5,7,9,11}` is every load of loop iteration 1. NONE of them completed. The ring is
empty, the store queue is empty, no bus transaction is outstanding, no owner is held.
Nothing is stuck on a resource -- the machine is stuck on itself.

## Mechanism

P4 holds the program-**younger** inhibited op (rob 7) parked for `p4AtRobHead`, while the
op that **is** the ROB head (rob 3) sits behind it in P3 and can never get through:

    p4Ready   = !p4Valid || p4CanLeave          (LsEuPlugin)
    p4CanLeave requires p4LaunchOk
    p4LaunchOk = Mux(p4Inhibited, p4AtRobHead && !olderStore && p4PreemptSafe, ...)

so

    rob7 waits for head==7 -> needs rob3 to retire -> needs rob3 to complete
                           -> needs rob3 to pass P4 -> needs rob7 to leave P4.

P4 is a single, non-bypassable stage, and the barrier's wait condition ("until I am the
ROB head") can only be satisfied by an older op that must pass through the very stage the
waiter occupies. Every earlier attempt at this barrier died of the same shape in a
different in-order structure:

| attempt | in-order structure the waiter occupied | symptom |
| --- | --- | --- |
| **pre-barrier, i.e. `aa312145` and master** | **the P4 stage itself** | **this bug -- see below, it is PRE-EXISTING** |
| pre-`dbff8619` (barrier, first revision) | the ring SEND pointer | 3 corpus programs hang, "parked in P2 for 20000 cycles" |
| `dbff8619` (current) | the P4 stage itself, still | this bug, unchanged |

## Why the knob OFF is immune, and why this is not an exotic corner

With in-order LS issue the op resident in P4 is always the OLDEST in-flight LS op, so the
head always advances to it and the wait always terminates. `loadBypassUnreadyLoad` (the
relaxed rule the knob turns on) is what puts a younger op into P4 first.

It does so for the most ordinary reason there is. In the reproducer, `a0` (the device
base) is written by the first `lea` and `a1` (the scratch base) by the second, so the
device load's operand is ready one cycle earlier and the IQ selects it first. That is the
register-allocation shape of every driver polling loop: a device base register held live
across the loop while the data pointer is recomputed. Expect this on real Mac OS driver
code, not in a fuzz corner.

## A FOURTH edge, recorded so nobody spends a day on the obvious cheap fix

The tempting cheap fix is: enqueue the inhibited op into the aligned ring immediately
(freeing P4) and let the ring SEND pick the oldest *sendable* entry, skipping the
inhibited one until it is the head. The ring already attributes responses out of order by
`DLoadRsp.rid`, so this looks nearly free.

**It deadlocks too.** The ring's COMPLETION is strictly in ring order via
`alignedRspPtr` -- deliberately, so "the PRF write, the completion port, the split-pair
merge and the ROB all keep seeing loads finish in program order" (`DLoadCmd.ooOk`). The
inhibited entry enqueues FIRST (it reached P4 first), so it sits at the completion
pointer: the older entries behind it can be SENT and can get their data PARKED, but they
cannot COMPLETE until the inhibited entry completes, which needs the head, which needs
them to complete. Same cycle, fourth edge.

So the waiter must be out of the ring's send order **and** out of its completion order.
That is what "a side buffer" has to mean; a ring slot is not one.

## MEASURED: the P4 edge is PRE-EXISTING, and it gives the silicon wedge a mechanism

The barrier work is **exonerated as the cause**. The head gate is already on `aa312145`
(the commit before `b4fc15db`):

    aa312145 LsEuPlugin.scala:3367  val p4AtRobHead = robHeadValidIn && (p4Front.robId === robHeadIn)
    aa312145 LsEuPlugin.scala:3407  val p4LaunchOk  = Mux(p4Inhibited,
    aa312145 LsEuPlugin.scala:3408      p4AtRobHead && !sq.io.barrier.olderStore && p4PreemptSafe, ...)

Reproduced there directly. Branch `probe/p4-park-deadlock-aa312145` (commit `85f2b70b`,
NOT for merge) carries `LsOooInhibitedOrderSpec` and its two sim taps on top of
`aa312145`, with its two `orderViolationPort` references removed because that port does not
exist until `b4fc15db`, plus one harness line -- `new IssueQueuePlugin(loadBypassUnreadyLoad
= fuzzLsOoo)` -- which is the only way to reach the relaxation at all, since
`loadBypassUnreadyLoad` defaults to FALSE and nothing on that commit turns it on. On
`aa312145`, `LsEuPlugin` has no `lsOooIssue` parameter, so `FUZZ_LS_OOO=1` there arms the
**IQ relaxation alone** -- strictly less than on the branch, where the same variable also
arms the LS-side barrier.

| build | `loadBypassUnreadyLoad` | result |
| --- | --- | --- |
| `aa312145` + probe | ON | **DEADLOCK**, same shape, same robIds, same cycle |
| `aa312145` + probe | OFF (default) | PASS, 229 cycles |
| `perf/ooo-load-issue` `92a89e98` | ON | DEADLOCK |
| `perf/ooo-load-issue` `92a89e98` | OFF (default) | PASS, 229 cycles |

The knob-ON dump on `aa312145` is byte-identical in every live field
(`head=3 s1(rob=11) t(rob=9) tx(rob=5) p3(rob=3) p4(rob=7)`, `p4Inhibited=true`,
`p4AtRobHead=false`, ring empty, SQ empty, `loadBusy=false`, trip at cycle 5031); only
never-written ring-slot registers differ, which is simulator randomisation of invalid
entries. The knob-OFF numbers are identical on both commits down to the launch cycles and
robIds -- an independent second measurement that the barrier costs nothing on this program.

**So the barrier did not introduce this. It inherited it.** What `dbff8619` fixed was a
SECOND edge of the same cycle that its own earlier revision had added at the ring send; the
P4 edge predates the whole barrier and is in master today, reachable the moment
`loadBypassUnreadyLoad` is enabled.

**This gives the board finding a mechanism.** `loadBypassUnreadyLoad` is recorded as the
SOLE wedge on silicon and recorded as NOT rescued by a hit gate. Both halves follow: the
deadlock is about which op OCCUPIES P4, not about whether that op hits, so a hit gate
cannot touch it; and a device access sitting behind an older in-flight LS op wedges with no
barrier code present at all. The wedge therefore has a mechanism and a candidate fix rather
than a standing prohibition -- but note this is a SIM mechanism that MATCHES the recorded
silicon symptom, not a silicon confirmation. Confirming it on the board needs a build with
the relaxation on, which is the owner's call.

## Candidates, priced at elaboration level (2026-09-26, no Vivado -- the lock was held)

Baseline from `sbt runMain m68k040.top.GenFullCoreSynthVerilog`, counting only
synthesised `reg` bits (SpinalHDL's `_string` enum-name registers live inside
`` `ifndef SYNTHESIS `` and are excluded):

| quantity | measured |
| --- | ---: |
| `M68kFullCoreSynth` synthesised flop bits | 90,105 |
| Verilog lines / `assign`s / `always` blocks | 421,857 / 42,004 / 8,094 |
| `ResolvePipeCtx` (the P4 register) | 278 bits |
| `XlatePipeCtx` (the P3 register) | 243 bits |
| `AlignedLoadCtx` (one ring entry) | 108 bits |
| ring depth today | 4 entries = 432 bits |
| `ROB_ID_W` | 5 (ROB depth 32) |

The cone that matters is P4. `docs/ipc-experiments.md` measures it on the routed 200 MHz
release build as **0.050 ns slack at 14 logic levels**, limiter `LsEuPlugin p3Ctx_paddr`
-- and records that it is the **only** datapath-limited stage in the LS pipeline (the
shallow stages are limited by long-distance control broadcasts instead). It also records
`RobPlugin doFlushReg` as the limiter for `IQ slot sel` (0.221 ns) and `LsEu S1`
(0.131 ns). So: 1-2 logic levels added to the P4 leave decision is the entire margin, and
the ROB flush path is the second-worst place to add depth.

### Candidate 1 -- SIDE BUFFER OUT OF P4

An inhibited op that resolves at P4 while not the head moves into a dedicated buffer,
freeing P4, and enqueues into the ring when it becomes the head.

* **Depth required: 4, not 1.** With a 1-deep park, a second inhibited op arriving while
  the park is occupied must stall in P4 -- which reinstates this exact deadlock whenever
  an older op is behind it. Up to 4 LS ops can be upstream of P4 (S1/P2/P2T/P3) and each
  parking op frees P4 for the next, so the buffer must absorb the whole pipe.
* **State**: 4 x (108 + 1 valid) = **436 flop bits**, +0.48% of core flops.
* **Combinational**: a 4-way head-anchored age-priority select (4 x 5-bit compare +
  priority encode, ~12-16 LUT6) and a 4:1 108-bit payload mux into the ring enqueue
  (~108 LUT6, one per bit).
* **P4 cone**: WORSE. The park-accept term (`p4Inhibited && !p4AtRobHead && !parkFull`)
  lands directly on `p4CanLeave -> p4Ready -> p3Ready -> issuePort.ready`, and the ring
  enqueue mux -- already fed from P4 -- gains a second 108-bit source.

### Candidate 2 -- ISSUE-SIDE REPLAY

Re-issue the uop from the IQ. Needs either (a) issued IQ slots kept resident until
completion -- the slot ways are `Mem[32*340 bits]` and freeing on issue is what keeps the
IQ at 32 slots -- or (b) a ROB flush from an **arbitrary** robId rather than from `h0`.

* **State**: ~0-32 bits for (b).
* **Combinational**: a 32-entry age fold in the flush path (32 x 5-bit compare, ~64
  LUT6), plus a restart-PC read from a non-head ROB slot, i.e. a new read port on the PC
  mem that `RobPlugin` explicitly protects ("no `nextPcMem` write is needed and that Mem
  keeps its single writer").
* **P4 cone**: unchanged. But it puts 2-3 levels into `doFlushReg`, which is already the
  measured limiter for two other stages. Fewest flops, worst placement.

### Candidate 3 -- RETIRE-AND-RE-EXECUTE (a GUARD on the existing park, not a new mechanism)

The IQ already decides, at select time, whether a uop bypasses an older un-issued LS op
-- that is literally what `loadBypassUnreadyLoad` computes. Carry that as ONE bit with
the uop. Then:

* if the bit is CLEAR, every older LS op had already issued, so all of them are ahead of
  this op in the in-order LS pipe and will have passed P4 before it arrives -- **parking
  is safe and behaves exactly as today**;
* if the bit is SET, an older LS op is behind it, so parking is the deadlock. That op
  instead completes as a REPLAY (no device read -- it never launched -- and no PRF
  commit, the shape `faultCompletionPort` already has), and when it reaches the ROB head
  the ROB flushes and restarts at **its own** PC (`p0.pc`, already an arm of
  `debugRestartPc`). On re-execution it is the oldest op in the machine, nothing older is
  upstream, the bit is clear, and it parks and launches normally. Terminates.

* **State**: 1 bit in `IqContext` + 1 bit carried through
  `FrontPipeCtx`/`XlatePipeCtx`/`ResolvePipeCtx` (4 stage copies) + the ROB's replay
  reason, which folds into the existing 45-bit `faultDynMem` entry. **~40 flop bits**
  (+0.04%), or ~70 if the IQ needs a per-slot copy.
* **Combinational**: a 2-input AND at P4 on terms that already exist; one more input on
  `doFlushReg`'s OR (already a 6-input OR, so free in depth); one more arm on
  `flushPcReg`'s mux.
* **P4 cone**: BETTER. The unsafe park disappears, so `p4AtRobHead` leaves the *waiting*
  path for bypassed ops and the `head -> p4AtRobHead -> p4LaunchOk -> p4Ready -> p3Ready
  -> issuePort.ready` arc that `DcachePlugin.scala:3461` names gets shorter, not longer.
  The decision bit is registered and travels with the uop, so there is no new live
  IQ->P4 path.
* **Precondition**: `bafd348b`'s `firstOfInstr` rule applies unchanged -- a mid-macro uop
  cannot be restarted at its own PC without re-running the macro's earlier side effects,
  which is the `inhibited-load-irq-replay` hazard. Already priced at 0.49pp.

### CORRECTION (2026-09-26, on trying to implement candidate 3): its price was WRONG

Candidate 3 as priced above **does not work**, and the error is in the mechanism, not the
gate count. It said "when it reaches the ROB head the ROB flushes and restarts at its own
PC". **The marked op can never reach the ROB head -- that IS the deadlock.** `retire0`
requires `completes(h0)`, the marked op never completes, and the head is held by the older
op stuck behind P4. A `replayAtHead(h0)` term can therefore never fire. The park-guard half
(the IQ bypass bit) is still right; the recovery half is not.

The corrected form has to fire the flush EARLY, before the op is at the head. That is
exactly what `RobPlugin.scala:2396-2420` records as investigated in depth and found UNSAFE
to bolt on:

> `RatTable.io.rollback` / `Freelist.io.flush` are each a SINGLE GLOBAL "restore to the
> committed shadow" [...] There is no existing hook to roll back "only what's younger than
> robId X" while leaving [head, X) untouched. Firing today's `flushing` (doFlushReg) EARLY,
> before the branch reaches head, therefore ALSO discards every not-yet-retired OLDER entry
> [...] Redirecting instead to the current committed PC avoids that corruption but
> degenerates into "eagerly squash everything in flight" [...] A real fix needs actual
> per-branch (or small-N) RAT/Freelist checkpoint+restore [...] properly scoped as its own
> follow-up.

And the safe variant it names (restart at the committed PC, squashing everything in flight)
carries a second problem this bug cannot dodge: the restart PC is then the HEAD's PC, and
the head is not the marked op. The head can be a MID-MACRO uop whose earlier siblings have
already retired -- e.g. in a mem-dest RMW crack (load, op, store) the load and the ALU op
retire and the STORE is left as the head. Restarting at that macro's PC re-executes the
retired load and the An auto-update: the `inhibited-load-irq-replay` corruption family
verbatim. A macro-boundary-safe restart PC at an ARBITRARY head does not exist in this ROB.
Note the asymmetry with `bafd348b`: its `firstOfInstr` precondition guarantees the *marked*
op is a macro boundary, which is what `orderRedirect` needs, and says nothing about the
head.

**Revised price for candidate 3: not ~40 flop bits. It is the per-robId RAT/Freelist
checkpoint+restore lift that `RobPlugin` explicitly scopes as its own follow-up, plus a
macro-boundary-safe arbitrary-head restart PC that does not exist.** A project, not a
guard, and squarely inside the most defect-dense path in the core.

### Candidate 4 -- P3<->P4 SWAP (zero new state, and still not sufficient)

Worth recording because it is the cheapest thing that looks like it works. P3's register has
exactly ONE write source today (`p3Ctx := txOut`); P4's capture is `p4Ctx.xlate := p3Ctx`.
So "let `p3ToP4` fire even when P4 holds a parked inhibited op, and write the parked op back
into P3" is one extra enabling term plus one extra source on the P3 write: **zero new flop
bits**, ~243 LUT6 for the 2:1 mux, on the stage with the MOST slack in the LS pipe
(0.442-0.490 ns) and on its register-to-register write path. The discarded `fwd*` verdict
costs nothing -- the op re-queries from P3, which `p4RetryQ` already does routinely. The
D-cache early-probe token is armed at P2 (`probeWanted = normalReqArm && tIsLoad`) and keyed
by token+vaddr, so it survives the round trip exactly as it survives parking today.

**It is still not sufficient.** One swap lets exactly ONE older op past. With two inhibited
ops parked -- which the reproducer's two device loads per iteration produce -- the machine
re-deadlocks one stage further back: P4 holds the older parked inhibited op, P3 holds a
younger op that must not overtake it, and the op older than BOTH is in P2T needing a P3 that
the younger op occupies.

That generalises, and it is the real constraint on every candidate: **letting N older ops
overtake a parked inhibited op requires N slots of somewhere-else to put the parked op, and
N is bounded only by the LS pipe depth.** A swap is a 1-deep reorder buffer implemented
in-place. Depth 4 is the requirement for all of them; candidate 1 is the honest way to spend
it.

### Verdict

**SUPERSEDED by the CORRECTION above.** What follows was the verdict before candidate 3's
mechanism was checked; it is kept because its tie-breaker reasoning still stands and now
selects candidate 1.

~~**Cheapest: candidate 3, by an order of magnitude**~~ -- candidate 3 does not work as
priced. **RECOMMENDED: candidate 1, the depth-4 side buffer.** It is the only one of the
four that is simultaneously sound, self-contained in the LS EU, and clear of the
precise-state/replay family: 436 flop bits (+0.48% of the core's 90,105), ~130 LUT, and it
DOES widen the P4 cone -- which is now a cost to accept rather than a reason to prefer
something else, because the alternatives are a ROB checkpoint project (3) or an
insufficient reorder depth (4).

**The tie-breaker reasoning below is what now selects it.** Its FIRST clause favoured
candidate 3 on the P4 cone; with candidate 3 gone, the SECOND clause decides, and it points
at candidate 1: it keeps every change inside the LS EU and touches no precise-state
machinery.

**If 1 and 3 were close, the tie-breaker is which side of the P4 cone the change lands
on.** P4 has 0.050 ns of slack at 14 levels and is the only datapath-limited stage in the
LS pipeline; 1-2 levels there is the whole margin, and that decides it independently of
flop count. **The second tie-breaker runs the other way**: candidate 1 keeps every change
inside the LS EU and touches no precise-state machinery, while candidate 3 touches the
ROB's don't-commit/flush path -- the same family that produced `inhibited-load-irq-replay`
and the FSAVE/CCR silent-corruption classes. An owner who weights correctness risk above
timing should take candidate 1 despite the cone.

### NOT IMPLEMENTED, and why (pre-correction reasoning; the correction above is the current one)

Candidate 3 is small in gates but it spans three plugins (IQ bypass bit, LS EU P4 guard,
ROB replay-at-head) and its risk sits in the ROB precise-state path, which cannot be
WNS-checked from this session. More importantly its **cost driver is unmeasured**: every
occurrence is a full flush, and the flush rate on device-heavy code is unknown (see
below). That number is only obtainable once a fix exists, so the honest sequencing is
*implement behind the knob, measure with `LsOooInhibitedOrderSpec`, then decide* -- not
build it blind. This family has already produced two deadlocks and one silent-corruption
class from confident reasoning.

## UNMEASURED, and named so it is not forgotten

1. **The barrier's flush rate on device-heavy code.** `dbff8619` measured zero IPC cost on
   the kernels only because those kernels contain no inhibited accesses, so the barrier
   never arms; it explicitly left the device-heavy flush rate as the next thing to
   measure. It is still unmeasured, and it is currently UNMEASURABLE: the knob-ON machine
   deadlocks before finishing a 4-iteration loop. `LsOooInhibitedOrderSpec` already prints
   `flushes` and `orderViolations` every run and will produce the number the moment a fix
   lands. On the shipping default it reads `flushes=3, orderViolations=0` over 229 cycles
   -- and all 3 are branch mispredicts, i.e. the barrier's recovery has never fired in a
   passing run.
2. **Whether candidate 3 changes that rate.** It converts some parks into flushes, so it
   can only raise it; by how much depends on how often a device access bypasses an older
   LS op, which is the same unmeasured quantity.
3. **Area and WNS of any candidate.** Elaboration-level flop bits and LUT estimates only;
   no Vivado run was permitted. Per `docs/`, neither WNS metric attributes a sub-0.5 ns
   change anyway, so the gate should be area plus sim cycles.

## What the reproducer asserts, and one assertion that is deliberately NOT fatal

Assertions A (no double-launch), B (AXI 1:1 with launches), C (no inhibited access
launches while another is in flight), D/D0 (the before-half: at the head, no older ring
entry resident, gate sampled at the P4 enqueue where it is actually evaluated) and E
(device reads in program order) all pass on the shipping default, with non-vacuity guards
(>= 2 inhibited launches, >= 2 cacheable launches) satisfied: 8 inhibited launches, 8 AXI
device ARs, 14 cacheable launches.

The strictly stronger form of C -- "no LS op **at all** launches while an inhibited access
is in flight" -- is measured and printed (`overlapAny`, `overlapBus`) but is NOT fatal.
The five-point argument is in the spec file next to the original wording. In short: it
fires identically with the knob OFF (8 times) and ON, so it describes the pre-existing
core rather than the relaxation; `dbff8619`'s after-half rationale is narrower than that
wording (inhibited-vs-inhibited and inhibited-store-vs-anything); `loadBusyReg`'s window
runs to RETIRE because it gates interrupt *recognition*, not access order; the
software-visible direction (inhibited store then younger access) already has a real
comparator in `olderInhibitedStore` and measures zero violations; and the TTR in use is
CM=11, cache-inhibited NONSERIALIZED. Making it fatal is a spec change with an IPC number
attached, not a test edit.


## THE FIX (2026-09-26): a depth-4 inhibited park buffer

`LsEuPlugin`, inside `if (lsOooIssue)` so a knob-OFF build is bit-identical. An inhibited op
that reaches P4 and is not yet the ROB head is written into a 4-entry park holding the
finished `AlignedLoadCtx`, and **P4 is released the same cycle**, so the older op behind it
in P3 can pass. The parked entry drains into the ring when its robId matches `robHeadIn` --
a one-hot match, since robIds are unique among in-flight ops, so no age-priority encoder is
needed.

Why it cannot re-deadlock: a parked entry is outside the ring's SEND pointer and outside its
COMPLETION pointer and outside the pipe, so it obstructs nothing; and every gate it waits on
(`olderStore` age-qualified, ring room, `p4PreemptSafe`) is released by ops it no longer
blocks.

Implementation notes that matter:

* **The drain reuses the ordinary `alignedEnq` push event and only redirects the payload.**
  All ring bookkeeping -- `alignedCount`, `alignedPushPtr`, valid/sent/poisoned/done/wb --
  is therefore shared and cannot drift. A separate push path would have duplicated the
  `alignedCount` selector, whose under-enumeration is the recorded silent ring leak that
  pins `alignedFull` high on an empty ring (`cpush_split_fault_ring_leak.s`).
* **The barrier query is taken, not muxed-and-shared.** When a parked entry is ready, it
  owns `sq.io.barrier.robId` and `p4LaunchOk` is forced False for those cycles. Age-safe
  with no starvation argument: the draining entry is the ROB head, so whatever is in P4 is
  necessarily younger. Muxing the query while letting P4 still act on the result would hand
  P4 a verdict computed for a different robId -- the silent-reorder class, not a stall.
* **The pre-launch barrier half is preserved by a sticky per-slot bit.** `parkSaw(i)` is set
  when a program-younger ring entry actually SENDS while slot i is parked, and reported on
  `orderViolationPort` at drain. Marking on the bus event rather than on ring residency is
  tighter (a poisoned entry never reaches a device, so it is not a violation) and cannot be
  missed by a send on a cycle the violation logic is not looking.
* **DELIBERATE, TRIPWIRED LIMITATION**: a SPLIT inhibited access (misaligned device access
  crossing a line or page) is not parked -- it needs two ADJACENT slots to preserve the
  split pair's contiguity invariant, which this random-access park does not provide -- and
  neither is any inhibited access arriving with the park full. Both keep today's in-P4 wait
  and remain exposed. A `GenerationFlags.simulation` assert fires if either happens with a
  program-OLDER LS op upstream, so the residual gap is LOUD and measured by every suite
  rather than argued.
* **Candidate 3's park guard (an IQ "I bypassed an older un-issued LS op" bit) is NOT
  implemented, and not half-implemented.** It is still sound, but here it would only reduce
  park PRESSURE; it is not needed for correctness, because parking every non-head inhibited
  op is strictly safer than parking none. The tripwire measures whether pressure is ever a
  problem. If it never fires, the guard buys nothing and should stay out.

### Measured

`testOnly m68k040.fuzz.LsOooInhibitedOrderSpec`:

| | knob OFF (shipping default) | knob ON |
| --- | ---: | ---: |
| result | PASS | **PASS** (was DEADLOCK) |
| cycles to sentinel | 229 | **225** |
| inhibited launches / AXI device ARs | 8 / 8 | 8 / 8 |
| cacheable launches | 14 | 15 |
| A / B / C / D / D0 / E | clean | clean |
| `orderViolations` | 0 | **6** (all 6 from the park) |
| `orderRedirects` | 0 | **0** |
| `flushes` | 3 | 3 |
| `overlapAny` / `overlapBus` (non-fatal) | 8 / 2 | 1 / 0 |

`OldestLsProgress` is unchanged and still the criterion. The non-vacuity guards pass in both
columns. The knob-ON run is 4 cycles FASTER than knob-OFF and the non-fatal overlap metric
improves 8->1, because the park frees P4 earlier than the old in-place wait did.

### THE AFFORDABILITY NUMBER, and why it is not the whole answer

**`orderViolations = 6` over 4 loop iterations (~1.5 per iteration, 8 device reads), and
`orderRedirects = 0`.** So the barrier costs **zero cycles today** -- but not because there
was nothing to recover. It costs zero because the recovery never fires, for the reason in
the next section. If the recovery is repaired, the cost on this program would be up to 6
flushes per 4 iterations, and THAT is the number that decides affordability on device-heavy
code. It remains unmeasured.

## FOUND WHILE GATING (2026-09-26), NOT FIXED: two defects in the pre-existing recovery

### 1. `RobPlugin(lsOooIssue = ...)` was never set in ANY simulation harness

The barrier's recovery half -- `orderViolated` / `orderRedirect` -- lives in `RobPlugin`
behind its own `lsOooIssue` parameter. `SocketTop` correctly drives `LsEuPlugin`,
`RobPlugin` and `IssueQueuePlugin` from one switch (`SocketTopConfig.LS_OOO_ISSUE`). Every
SIM harness set it on the LS EU and the IQ and **omitted the ROB**:

    FuzzCoreDut          lsEu ✓  iq ✓  rob ✗
    CoreBenchHarness     lsEu ✓  iq ✓  rob ✗
    ExecuteLockStepSpec  lsEu ✓  iq ✓  rob ✗

With it False the LS EU's `orderViolation` port is wired but IGNORED, so **the recovery had
zero simulation coverage** -- the same shape as the CPUSH `icMaintFlush` fix that was wired
only in `FullCoreSynth`. Fixed in all three harnesses (test-side only; every default stays
OFF).

### 2. The recovery is DEAD for multi-uop macros -- its precondition contradicts the marker's

`orderRedirect = retire0 && orderViolated(h0) && p0.last && (count > 1)`. The IQ's matching
precondition is that a bypassing uop must be **FIRST** of its instruction (quoted in
`orderRedirect`'s own comment). For any instruction that cracks into more than one uop those
two are **mutually exclusive**: the violator is always first, and the recovery demands last.

Measured, knob ON: `orderViolated(7)` is set from cycle 61 onward with `head == 7`, and
`orderRedirect` never asserts. `retire0` must fire (the head advances and the program
completes), `count > 1` holds (the loop continues), and the mark is present -- so `p0.last`
is the only false term. `move.l (An),Dn` is a 2-uop macro in this core: the five loop-body
loads occupy robIds 3,5,7,9,11, spaced by 2, and the violator is the first of its pair.

**Consequence: the barrier's pre-launch half is currently NOT ENFORCED.** Violations are
detected and marked correctly and then dropped. This is pre-existing -- it is a property of
`dbff8619`'s recovery, not of the park, which only made the marks reachable in sim for the
first time. Repairing it is the same territory as candidate 3 (a restart PC that is a macro
boundary for something other than the head), so it is NOT attempted here.

### 3. `orderRedirect` re-executes a just-committed instruction on a DUAL RETIRE

Found while pricing the repair for defect 2. `orderRedirect` restarts at `p1.pc` and nothing
suppresses `retire1`:

    orderRedirect = retire0 && orderViolated(h0) && p0.last && (count > 1)
    when(orderRedirect) { flushPcReg := p1.pc }

    retire1 = retire0 && (count > 1) && completes(h1) && headAllowsPair && ...

`headAllowsPair` has no `orderViolated` term. So when h0 and h1 retire in the SAME cycle and
h0 is a marked macro-last uop, h1 **commits** and the machine then restarts at `p1.pc` --
h1's own PC -- re-executing an instruction whose architectural effects have already been
committed. For a store or an auto-update that is silent corruption, and it is the
`inhibited-load-irq-replay` shape again.

**It is LATENT TODAY only because `orderRedirect` has never fired in any build**
(`RobPlugin(lsOooIssue)` was never set anywhere -- see defect 1). It is recorded here as a
defect in its own right, independent of whether any fix ships, because it would GO LIVE the
moment anyone repairs the defect-2 mark conflict without noticing the pair case -- which is
exactly the sequence a reader of defect 2 alone would follow.

## PRICED (2026-09-26): repairing defect 2 with a STICKY MACRO BIT -- it works, ~1 flop bit

The proposal is to make the marking predicate and the recovery predicate coincide instead of
excluding each other. Not by propagating a per-uop mark, but by a single register that spans
the macro currently retiring:

    when(retire0 && orderViolated(h0) && !p0.last) { macroViolatedSticky := True }
    orderRedirect = retire0 && (orderViolated(h0) || macroViolatedSticky) && p0.last &&
                    (count > 1)
    when((retire0 && p0.last) || flushing) { macroViolatedSticky := False }

Sound because the ROB retires STRICTLY IN ORDER and a macro's uops are CONTIGUOUS, so one
bit is enough to carry "somewhere in the macro now retiring, a violation was marked" from any
uop to the last one. The three questions, answered:

1. **Carrier / bits.** No per-uop state and no new carrier: **ONE flop bit**, plus one extra
   input on the `orderRedirect` AND and one suppression term on `retire1` (below). The mark
   itself keeps living in the existing `orderViolated` Vec. Nothing has to know the macro's
   last robId at marking time, which is what made per-uop propagation awkward.
2. **Is the macro's last uop still in flight when the mark is set?** YES, and the argument is
   independent of which uop is marked. The mark is produced while the marked uop has not even
   COMPLETED (it is parked, or resident at P4), and retire requires completion -- so the
   marked uop has not retired, and every LATER uop of its macro is younger still. A sibling
   can therefore never have retired ahead of the mark. Note this also removes the
   `firstOfInstr` requirement entirely: first, middle or last all work, which is what makes
   it fit the park, since the park marks EVERY non-head inhibited op and not only bypassing
   ones.
3. **Does the restart PC exist?** YES, and this is the whole difference from candidate 3.
   With h0 the macro's LAST uop, `p1` is the next macro's FIRST uop, so `p1.pc` is an
   ordinary macro boundary -- and it is already read and already used by today's
   `when(orderRedirect) { flushPcReg := p1.pc }`. No new `payload` read port, no
   arbitrary-head PC, and nothing has to roll back to a mid-macro point, so the per-robId
   RAT/Freelist checkpoint problem does not arise at all.

**The dual-retire case is why `retire1` must be suppressed, not handled.** If h0 (marked,
not last) and h1 (last) retire together, the sticky register cannot be set in time -- it is a
register, and the pair retires in one cycle. Handling the pair instead would mean redirecting
to the entry AFTER h1, which is `p2` -- and `payload` is read at `h0` and `h1` only
(`p0 = payload.readAsync(h0)`, `p1 = payload.readAsync(h1)`), so it would need a THIRD read
port on that Mem. Suppressing `retire1` when a marked macro's last uop is in the pair costs
one AND term and no port: the last uop then retires alone on the next cycle with `p0.last`
true and `p1` the next macro, the redirect fires normally, and the cost is ONE cycle. This
also fixes defect 3 as a side effect, since the suppression is exactly the missing
`orderViolated` term in the pair gate.

Verdict: **NOT candidate-3 territory.** ~1 flop bit, two AND terms, no new read port, no
rollback machinery. The earlier "macro-boundary restart PC for something other than the head"
framing was wrong: by deferring the redirect to the macro's LAST uop the head IS a macro
boundary, which is the observation that dissolves the problem.

## FOUND WHILE GATING (2026-09-27): the park was BROKEN in the only build that can ship it

**The park LOST DEVICE READS as originally written, and no simulation could see it.** Found by
inspection of the fall-through arm, then reproduced.

`LsEuPlugin`'s optional `alignedLoadFallThrough` arm lets a P4 load send its command in the
same cycle it allocates its ring descriptor. Its enable is

    alignedFallThrough = alignedFallThroughSelect && alignedEnq &&
                         (p4Ctx.xlate.cmode =/= INHIBITED)

and its payload is taken from **P4** (`alignedCmd` is overridden under
`alignedFallThroughSelect`). The park drain asserts the *same shared* `alignedEnq` — that
sharing is deliberate, so all ring bookkeeping stays in one place — but its payload comes from
the **park**, not from P4. So on a drain cycle with the ring empty at the send pointer:

1. the device descriptor is written into the ring slot, and
   `alignedSent(alignedPushPtr) := alignedFallThroughFire` marks it **already sent**;
2. the command actually presented on the bus is assembled from whatever **P4** holds;
3. `rid === alignedSendPtr` points at the device entry, so that bogus command's response is
   delivered to the device load.

Net effect: the device read **never reaches the bus**, and the device load completes with
another access's data. Silent corruption plus a dropped device access.

### Why no test could see it, and this is the same shape as the CPUSH `icMaintFlush` hole

| build | `alignedLoadFallThrough` | `lsOooIssue` reachable? |
| --- | --- | --- |
| `SocketTop` (the SHIPPING build) | **`= ipcThroughput`, i.e. TRUE on the board** | YES (`LS_OOO_ISSUE`) |
| `FuzzCoreDut` (reproducer + whole ported corpus) | default **FALSE** | yes |
| `CoreBenchHarness` | caller-supplied, default FALSE | yes |
| `FullCoreSynth` | `--aligned-load-fall-through` flag, default FALSE | no `lsOooIssue` param |

`SocketTop` is the **only** place `lsOooIssue` can be turned on, and it is the only place the
fall-through is on. So the one configuration in which the park can ever ship was the one
configuration nothing simulated. Every green run above was taken with the fall-through OFF.

### Measured, both directions

    FUZZ_LS_OOO=1 sbt "testOnly m68k040.fuzz.LsOooInhibitedOrderSpec"

| park as first written | fall-through | result |
| --- | --- | --- |
| unfixed | OFF (old fuzz default) | PASS, 225 cyc, 8 inhibited launches / 8 AXI device ARs |
| unfixed | **ON (shipping)** | **FAIL — assertion A: device `0xffff0100` launched 3 times, expected 4**; `inhibLaunches=5 axiDevReads=5`, i.e. **3 of 8 device reads LOST**, 214 cyc |
| fixed | ON (shipping) | **PASS, 225 cyc, 8 / 8**, `orderViolations=6` (all park), A/B/C/D/D0/E clean |
| fixed | OFF | PASS, 225 cyc, 8 / 8 — unchanged |

### The two fixes

1. **`alignedEnqFromP4`** — the fall-through arm is only valid when **P4** is the push source:
   `if (lsOooIssue) alignedEnq && !parkDrain else alignedEnq`. A drained entry then simply
   sends from the ring on a later cycle, which is the ordinary path (inhibited accesses are
   excluded from the fall-through anyway, which is what the pre-existing `cmode` term is for).
   The post-drain cycle cannot re-trigger the select, because `alignedPushPtr` has advanced
   past `alignedSendPtr` — so the existing
   `assert(!(alignedFallThroughSelect && alignedSendValid))` stays satisfied.
2. **`parkSaw`'s comparand** — it compared against `alignedMem(alignedSendPtr).bk.robId`. On a
   fall-through fire that slot **has not been written yet** (it is written the same cycle), so
   the memory still holds the previous occupant — an OLDER robId — which makes `sentIsOlder`
   true and **silently drops the mark**. It must use `alignedCmd.bk.robId`, which is exactly
   what the command port presents on both paths. So the pre-launch half was also
   under-reporting, in the direction that loses violations.

### And the coverage that makes them testable

`FuzzCoreDut` now defaults `alignedLoadFallThrough` to the LS-OoO switch
(`FUZZ_LS_FALLTHROUGH` overrides), so the knob-ON DUT matches the shipping combination and the
risky configuration is the one the corpus exercises. The shipping default (`FUZZ_LS_OOO`
unset) is unchanged: fall-through stays OFF there, exactly as before.

**Generalisation worth keeping.** The park deliberately shares `alignedEnq` with P4 so that
ring bookkeeping cannot drift — and that sharing is precisely what let a *second* consumer of
`alignedEnq` mis-attribute the push to P4. Sharing an event is safe; sharing an event whose
consumers infer the SOURCE from it is not. Any future consumer of `alignedEnq` has to ask
which of the two sources fired.

## CORPUS GATE (2026-09-27): the knob-ON red set, and what owns each red

Full `PortedM68kOooSpec` (1,022 registered tests: 974 programs + the cache-mode and MMU-walk
sweeps), run twice on the SAME tree, once at the shipping default and once with the knob on:

| run | `FUZZ_LS_OOO` | succeeded | failed |
| --- | --- | ---: | ---: |
| CONTROL | unset (shipping default) | 1,010 | 12 |
| knob ON (fall-through ON) | 1 | 1,004 | 18 |

**Every one of the control's 12 reds is also red with the knob on, with the identical sentinel
word** -- `exc_partial_macro_move_mem_mem` (`0xbad0a008`, documented as a standing baseline red
needing A3 multi-access restartability), `mmu_atc_write_hit_sets_modified` (`0xdead0903`),
`rom_scc_mmio_btst_dbf_timeout`, and the FPU family (`fpu_fmove_fp_to_ea_matrix`,
`fpu_fmovem_multi`, `fpu_fmovem_x_an_indirect` / `_pcdi` / `_roundtrip`,
`fpu_fsave_frestore_idle_roundtrip`, plus their copyback-sweep variants).

### The 6-red DELTA is the RELAXATION, and it is PRE-EXISTING -- not the park

The knob-ON run adds exactly six, which are THREE programs in the two cached sweep postures:

    ported-sweep-copyback: add_mem_postinc_rmw      ported-sweep-mmuwalk: add_mem_postinc_rmw
    ported-sweep-copyback: memind_full_matrix       ported-sweep-mmuwalk: memind_full_matrix
    ported-sweep-copyback: store_forward_matrix     ported-sweep-mmuwalk: store_forward_matrix

Note the AS-WRITTEN variants of all three PASS; only the copyback and MMU-walk sweep postures
fail. Attributed by a four-way controlled run over exactly those nine selected tests
(`-z add_mem_postinc_rmw -z memind_full_matrix -z store_forward_matrix`):

| LsEuPlugin | `FUZZ_LS_OOO` | `alignedLoadFallThrough` | succeeded / failed |
| --- | --- | --- | ---: |
| park + fall-through fix | OFF | ON | **9 / 0** |
| park + fall-through fix | ON | OFF | 3 / 6 |
| park + fall-through fix | ON | ON | 3 / 6 |
| **HEAD (no park, no fix)** | **ON** | OFF | **3 / 6 -- SAME SET** |

The failure sentinels are IDENTICAL across the three failing columns
(`0xdead0002`, `0xdead0009`, `0xdeadbeef` x2, `0xfa110016` x2). So:

* the park does not cause them, and does not change them;
* the fall-through does not cause them (knob OFF with the fall-through ON is 9/0);
* **`loadBypassUnreadyLoad` alone does**, on master's own LS EU.

**This is a THIRD independent reason the relaxation cannot ship as-is, alongside the P4-park
deadlock and the silicon wedge -- and unlike the deadlock it is NOT fixed here.** It is a
separate defect with its own root cause, in LSU-stress programs under copyback and real
page-table walks, and it is the obvious next thing to investigate for anyone trying to turn
`LS_OOO_ISSUE` on. The knob stays default-OFF.
