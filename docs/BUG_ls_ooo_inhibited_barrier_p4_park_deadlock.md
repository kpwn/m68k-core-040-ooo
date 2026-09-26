# BUG: with `lsOooIssue` ON, the inhibited memory barrier PARKS IN P4 and starves the
# ROB head -- the third instance of one circular wait

**Status**: OPEN. Reproducer committed and RED BY DESIGN with the knob ON:
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
| pre-`dbff8619` | the ring SEND pointer | 3 corpus programs hang, "parked in P2 for 20000 cycles" |
| `dbff8619` (current) | the P4 stage itself | this bug |

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

### Verdict

**Cheapest: candidate 3, by an order of magnitude** -- ~40 flop bits and ~2 LUTs against
candidate 1's 436 bits and ~130 LUTs, and it is the only one of the three that makes the
P4 cone SHORTER instead of longer. It is also the smallest conceptual change: a guard
that forbids the one park that is unsafe, rather than a new buffer or a new flush mode.

**If 1 and 3 were close, the tie-breaker is which side of the P4 cone the change lands
on.** P4 has 0.050 ns of slack at 14 levels and is the only datapath-limited stage in the
LS pipeline; 1-2 levels there is the whole margin, and that decides it independently of
flop count. **The second tie-breaker runs the other way**: candidate 1 keeps every change
inside the LS EU and touches no precise-state machinery, while candidate 3 touches the
ROB's don't-commit/flush path -- the same family that produced `inhibited-load-irq-replay`
and the FSAVE/CCR silent-corruption classes. An owner who weights correctness risk above
timing should take candidate 1 despite the cone.

### NOT IMPLEMENTED, and why

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
