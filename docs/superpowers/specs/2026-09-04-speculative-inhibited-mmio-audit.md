# Speculative / wrong-path access to cache-inhibited MMIO — audit

Date: 2026-09-04
Branch: `investigate/spec-mmio-readclear` (worktree; **not merged**)
Base: `ca4b901` on `fmax-closure-fanout`

> The task named base `bb3bca1`. `fmax-closure-fanout` had advanced to `ca4b901`
> ("docs: page-table walk refutes the cacheable-MMIO hypothesis") by the time this
> ran; `bb3bca1` is checked out in the `walker-base` worktree. This audit is against
> `ca4b901`.

## Verdict

**Yes — a wrong-path access can reach cache-inhibited MMIO, and it is the
INSTRUCTION side, not the data side.**

- **D-side (loads): CLEAN.** `p4LaunchOk` is complete for every path that can reach
  the D-cache's AXI read port. Verified by reading, and by a directed test that is
  non-vacuous and whose detector is proven by a positive control.
- **I-side (fetch): OPEN, and demonstrated on unmodified RTL.** A wrong-path
  instruction fetch into a CM=10 page issues a real **64-byte AXI INCR burst read**
  at the 64-byte line base. Cache mode gates only the array *install*, which happens
  after the burst has already completed on the bus.
- **MMU table walker: OPEN (secondary).** Walks launch from a fully speculative
  point with no ROB-head gate, the launch condition omits the mispredict flush, the
  walker has no abort port, and its descriptor reads are fixed 16-byte line-aligned
  with no `MmioCover` equivalent. Reaching MMIO additionally requires a descriptor
  or root pointer aimed at device space, which this audit did **not** demonstrate.

## The architectural rule under test

MC68040 UM §3.1.2/§4: a cache-inhibited page denotes a **device**. A device read has
an architecturally visible side effect (read-to-clear status, FIFO pop, interrupt
acknowledge), so it may not be performed speculatively — a squash cannot un-do it —
and it may not be reordered. Operationally in this core: **an inhibited access must
be performed only at the ROB head.**

`LsEuPlugin.scala:2155-2181` states exactly this rule in-source and enforces it for
D-side loads. Nothing enforces it on the I-side.

---

## 1. D-side: `p4LaunchOk`'s exact coverage

```scala
// LsEuPlugin.scala:2182-2229
val p4Inhibited = (p4Ctx.xlate.cmode === CacheMode.INHIBITED) ||
                  (p4Front.twoAccess && (p4Ctx.xlate.cmodeB === CacheMode.INHIBITED))
val p4AtRobHead = robHeadValidIn && (p4Front.robId === robHeadIn)
val p4PreemptSafe = !irqPreemptPendingIn && !debugHaltImminentIn
val p4LaunchOk  = Mux(p4Inhibited,
                      p4AtRobHead && !sq.io.barrier.olderStore && p4PreemptSafe,
                      !sq.io.barrier.olderInhibitedStore)
```

### Is it on every path to the bus?

The D-cache has **exactly one** AR driver, `DcachePlugin.scala:1877`, inside the load
FSM's `REFILL` state. Enumerating everything that can reach it:

| Path | Reaches `axi.ar`? | Gated by `p4LaunchOk`? |
|---|---|---|
| Ordinary aligned load | yes, via `alignedEnq` | yes — `LsEuPlugin.scala:2253`, inside `when(p4LaunchOk)` |
| **Split second half** (misaligned / line- or page-crossing) | yes, via `alignedEnqSplit` | yes — `LsEuPlugin.scala:2266`, same `when(p4LaunchOk)` block. Both halves now push through the same ring; `p4Inhibited` ORs in `cmodeB`, so slot B's mode is covered |
| Legacy split replay FSM (`bkFsm`/`llReg`) | would, but **dead**: `bkStart` is assigned `False` at `:816` and never driven (`:1027` says so explicitly) | n/a |
| **Parallel VIPT early probe** (`loadProbe`) | **no** — tag/data RAM read only. Its `cacheMode` is hardwired `INHIBITED` (`LsEuPlugin.scala:1183`) so it can only force hits low, never launch a fill | n/a |
| **Store queue drain** / write-allocate refill | yes (kind=1) | not by `p4LaunchOk`, but non-speculative by construction: drains at the SQ head with `committed(head)` or `robIds(head) === robHeadIn` (`StoreQueue.scala:426`). Inhibited stores never allocate (`DcachePlugin.scala:335,852`) |
| **Exception-unit loads** (`excLoadCmdValid`, `LsEuPlugin.scala:2886`) | yes — bypasses `p4LaunchOk` entirely | non-speculative by construction: the exception is already committing, and `excLoadCmdValid => excActive` is asserted at `:2881`. Vector/frame reads target RAM |
| Eviction writeback | writes only (`axi.aw`) | n/a |

Only two assignments to `alignedEnq`/`alignedEnqSplit` exist in the whole file, both
inside `when(p4LaunchOk)` inside `when(p4Valid && !sqFlushSig && !excActive)`.

### Is cacheability known before launch?

Yes. `p4Ctx.xlate.cmode` is the registered DTLB response, resolved at P3. The VIPT
probe that starts before translation cannot reach the bus (above). There is no window
where a load launches before its cache mode is known.

The CM decode is conservative in the right direction — `IcacheTypes.scala:76-85`
maps **both** `10` (inhibited, serialized) and `11` (inhibited) to `INHIBITED`, so
there is no "CM=11 is speculatable" hole.

### Is "ROB head" sufficient?

Yes, subject to two things I checked:

1. **A wrong-path instruction cannot become ROB head.** Every redirect source
   (mispredicted branch, trap, serializing instruction) has a ROB entry that is
   *older* than the wrong-path load, and the ROB retires strictly in order, so the
   head can never advance past an unresolved redirect source.
2. **robId aliasing after a flush is closed.** `StoreQueue.scala:281` warns that "the
   ROB flush is pointer-only (`tail := head`), so its robId is immediately
   re-allocatable" — which would let a stale parked load match a *different*
   instruction's head. `LsEuPlugin.scala:2600-2607` clears `p4Valid` (and
   `s1Valid`/`tValid`/`txValid`/`p3Valid`) on `sqFlushSig || excActive`, so no stale
   entry survives a flush to be re-matched.

The residual "at head but still discardable" sources — interrupt, trace, debug
auto-halt — are covered by `p4PreemptSafe` at launch time and by
`inhibitedLoadBusySig` for the after-launch case.

**No hole found on the D side.**

### MMIO transaction shape (D-side)

Also clean, and worth noting because it is the *opposite* of the I-side. For an
inhibited access `DcachePlugin.scala:1878-1881` selects `subAddr`/`subLog2` from
`socket/MmioCover.scala` — an exact byte-cover of only the bytes the instruction
named. `MmioCover`'s own doc comment states the hazard verbatim:

> byte-addressed I/O registers are selected by ADDRESS, not strobe […] so a
> line-aligned 16-byte MMIO read touches four registers at once and fires
> read-to-clear side effects on three the access never named. SCC, VIA, IWM, SCSI
> and ADB all live in that space.

---

## 2. I-side: the hole

### Mechanism

`IcachePlugin.scala:2313-2318` — the single I-side AR driver:

```scala
axi.ar.valid         := arHoldValid
axi.ar.payload.addr  := arHoldAddr      // := mshrPa(DEMAND_IDX) & ~U(63, 32 bits)  (:2295)
axi.ar.payload.len   := U(1, 8 bits)    // 2 beats
axi.ar.payload.size  := U(5, 3 bits)    // 32 B/beat  =>  64 bytes, unconditionally
axi.ar.payload.burst := Axi4.burst.INCR
```

There is no cache-mode term on the payload, no narrowing, and **no I-side equivalent
of `MmioCover`**.

Cache mode appears in the *hit* expression, not the *fill* expression:

```scala
// IcachePlugin.scala:780-781
val s1HitVec = Vec((0 until ways).map(w => s0Cacheable && validsQ(w) && (tagQ(w) === s0Ppn)))
// :791
val s1Unresolved = s0Valid && !s0Replay && !s0Fault && !s1Hit
```

So an INHIBITED fetch is *forced to miss* and therefore *forced to fill*. Cache mode
only reappears at `IcachePlugin.scala:2507`, `val doAllocate = missCacheable && …`,
which runs after both R beats have returned. **INHIBITED suppresses the array
install, not the bus transaction.**

This is not incidental — the repo already pins it. `IcacheSpec.scala:819`:

```scala
assert(arAfterInhibited == arAfterWarm + 1, s"inhibited fetch must refill once: …")
```

### The fetch is unconditionally speculative

`FetchAlignPlugin.scala:585-602` drives `ic.cmd` from BTB/RAS/FTB predictions and a
sequential run-ahead pointer with no address-range, cache-mode, or
architectural-path qualification. Misprediction handling is post-hoc: `ringStale`
(`:608`) drops the *response*. The AR is already on the bus.

The run-ahead is documented in-repo, in `attachProgram`'s own comment
(`ExecuteLockStepSpec.scala:495`): "the front-end fetches past the last program
word" — which is precisely why `LockStepRunAheadGuardWords` exists.

I found **no** address-range / TTR / MMIO restriction anywhere in `frontend/` or
`IcachePlugin.scala`. `AxiDMerge.scala:46` notes `axi_i` has no arbiter at all.

Additional related exposure: with the MMU off and no ITTR match,
`ItlbPlugin.scala:420-424` returns `WRITETHROUGH` for **every** address including
device space, so a wrong-path fetch there does a 64-byte *allocating* fill.

### Next-line prefetcher

Mostly contained, one documented residual. The frontier is seeded only from a
resolved, non-faulting, **cacheable** demand (`IcachePlugin.scala:1457-1467`) and is
clamped to the demand line's own 4 KiB physical page (`:1294-1295`), so it **cannot
cross into a different page**. `IcachePlugin.scala:2094-2144` documents an accepted
"cycle T" residual under a same-page cacheability transition, bounded to one line and
pinned by `IcachePrefetchSpec.scala:866+`. Not the mechanism here.

### Why this is the shape that matters for the 53C96

One 64-byte burst spans **four** Quadra 53C96 registers (0x10 spacing):

- a fetch anywhere in `0x50F0F000..0x50F0F03F` → line base `0x50F0F000` → burst
  covers regs 0–3, including **reg 0, the FIFO — reading it pops it**;
- a fetch anywhere in `0x50F0F040..0x50F0F07F` → line base `0x50F0F040` → burst
  covers regs 4–7, i.e. `+0x40` **Status** *and* `+0x50` **Interrupt Status, which is
  read-to-clear**.

So a single wrong-path I-fetch line anywhere in the second half of that page reads
the interrupt-status register and clears the interrupt condition, leaving the chip in
exactly the measured state: phase = DATA IN, TC = 1, INT = 0, with the architectural
poll spinning forever. An in-order core with no fetch run-ahead past an unresolved
branch does not do this; this one does.

**I did not demonstrate that the frontend is actually steered into `0x50F0Fxxx`
during the boot.** That is the remaining gap between this mechanism and the field
symptom, and it is exactly what the MMIO transaction capture and v1 diff would
settle. See "Not claimed" below.

---

## 3. MMU table walker (secondary finding)

- No abort/kill port: `TableWalker.scala:40-47`. Once `io.start` pulses, the FSM runs
  `RD_ROOT → RD_PTR → RD_PAGE → FINISH`; `arSent` is cleared only by a new walk.
- D-side launch: `DtlbPlugin.scala:322`
  `walker.io.start := missReqReg.valid && !umQueueFull && !flushAll` — **`umFlush`
  (the mispredict/exception squash) is not a term.** `walkUmPoison` (`:411`) only
  suppresses the TLB fill and the U/M writeback; it never touches `io.axi`.
- I-side launch: `ItlbPlugin.scala:251` `walker.io.start := missReqReg.valid` — no
  flush term at all.
- The triggering DTLB request is raised at P2 (`LsEuPlugin.scala:2522`,
  pipeline-validity only), i.e. fully speculative — no ROB-head gate, in deliberate
  contrast to the D-side load path in the same file.
- Descriptor reads are fixed 16-byte line-aligned, `size=4` (`TableWalker.scala:111,
  114`) with **no `inhib` arm** — the exact over-covering shape `MmioCover` exists to
  prevent.
- Addresses are unconstrained: unmasked `urp`/`srp` (`MmuControl.scala:119-120`) plus
  a raw 28-bit next-base field (`MmuTypes.scala:63`). No region check found anywhere.
- `UmWriteQueue` **writes** are correctly non-speculative (`:77`
  `headReady = valids(head) && committed(head) && !io.flush`).

Reaching MMIO by this path needs a descriptor or root pointer aimed at device space.
Not demonstrated here.

---

## 4. Directed tests

Added to `src/test/scala/m68k040/lockstep/ExecuteLockStepSpec.scala` (test-only; **no
`src/main` change**, so no synth gate applies). Four tests, designed as a calibrated
set so that neither a false positive nor a vacuous pass can hide.

### I-side probe

Program sits immediately below a 16 MiB boundary and ends in `BRA.S -2`, so the
architectural PC never crosses it. ITT0 = `0x5000C040` (base 0x50, mask 0x00, E=1,
S=1x, CM=10) marks `0x50000000..0x50FFFFFF` cache-inhibited — consulted before the
`mmuEnable=False` fallback (`ItlbPlugin.scala:409`), so paging stays off. The
frontend's run-ahead crosses the boundary as pure wrong-path fetch. Assertion is on
`dut.icache.logic.axi.ar`, not on architectural state, because the side effect leaves
no architectural trace. Each test also asserts no *retired* PC ever enters the window,
so "wrong-path" is proven rather than assumed.

Results on **unmodified** RTL:

```
[spec-mmio-ctl-neg] total I-side ARs=7, ARs in 0x50000000..0x50FFFFFF=0
  PASS  — detector is not a false-positive machine

[spec-mmio-ctl-pos] total I-side ARs=6, ARs in window=5,
                    first=0x50000000, 0x50000040, 0x50000080, 0x500000c0, 0x50000100
  PASS  — with the window CACHEABLE the frontend demonstrably runs ahead across the
          boundary and the detector sees it. No retired PC in the window.
          This is the DETECTOR PROOF for the I-side: it rules out a vacuous pass.

[spec-mmio-rule]   total I-side ARs=2, ARs into the INHIBITED window=1, addrs=0x50000000
  FAIL  — "SPECULATIVE DEVICE READ: 1 wrong-path 64-byte AXI burst read(s) were
           issued to CACHE-INHIBITED (CM=10) space that the architectural program
           never entered. Distinct line bases: 0x50000000."
```

Note the 5-vs-1 difference: with the window cacheable the prefetcher extends the
frontier; inhibited lines never allocate and never seed it, so run-ahead stops after
one line. **One line is enough** — it is 64 bytes.

### D-side probe

An untrained conditional branch is predicted fall-through while its architectural
direction is taken, so the fall-through is wrong-path by construction; its condition
is made to depend on a `DIVU` so it resolves late and gives the wrong-path load the
widest window. DTT0 marks `0x50xxxxxx` inhibited. The load target is `0x50F0F040` —
the exact 53C96 Status address from the field trace.

Non-vacuity is asserted **in-band**: the test fails unless the whitebox shows
`p4Valid && p4Inhibited` at least once, i.e. the wrong-path inhibited load really did
reach the launch gate.

```
[spec-mmio-dside] total D-side ARs=0, ARs into the INHIBITED window=0,
                  wrong-path inhibited load reached p4 gate=true, commits=7877
  PASS  (unmodified RTL)
```

**Positive control** — `p4LaunchOk`'s inhibited arm temporarily replaced with `True`
in `LsEuPlugin.scala` (reverted afterwards; `git status` confirms `src/main` clean):

```
[spec-mmio-dside] total D-side ARs=1, ARs into the INHIBITED window=1,
                  wrong-path inhibited load reached p4 gate=true, commits=7873,
                  addrs=0x50f0f040
  FAIL  — "SPECULATIVE DEVICE READ on the D side: 1 AXI read(s) reached
           cache-inhibited space from a wrong-path load: 0x50f0f040"
```

The detector fires on exactly the address the ROM polls, and only when the guard is
removed. The D-side negative is therefore a measured negative, not an unproven one.

---

## 5. Verification

`src/main` is **unchanged** (`git diff --stat`: 1 file, `ExecuteLockStepSpec.scala`,
+309). No synth gate applies.

| Run | Result |
|---|---|
| `fastTest` (== `make test-fast`) | **341 run, 341 passed, 0 failed**, 2 ignored. Baseline was 341 with 1 `RobPluginSpec debugPcApply` seed flake; this run drew a clean seed. No regression. |
| `ExecuteLockStepSpec` (full) | **509 run, 508 passed, 1 failed.** Baseline 505 passing + 3 new passing controls = 508. The single failure is the new `spec-mmio I-side THE RULE` finding test. **Zero regressions.** |
| `ls` / `cache` suites | **NOT COMPLETED** — killed mid-run for the Vivado gate (below). |
| 200-seed fuzz | **NOT RUN.** |
| Synth gate | **N/A** — `src/main` unchanged. |

### Not run, and why

The `ls`/`cache` run and the 200-seed fuzz sweep were stopped/never started: a KU5P
`full_impl` synth gate was running against ~1 GB free with 10 GB swap in use, and the
project's standing rule is never a heavy test JVM concurrently with Vivado. My sbt
JVM (6.5 GB, the largest process on the host) was killed; available memory went
~1 GB → ~14 GB.

Both are expected to be unchanged **because `src/main` is untouched** — the fuzz
count cannot move without an RTL change — but that is an argument, not a measurement,
and is recorded here as such. The fuzz baseline of 3 (seeds 80, 109, 127) is neither
confirmed nor moved by this work.

Re-run command when the gate is clear:

```
SBT_OPTS="-Xmx6G -Xss8M -XX:+UseG1GC -Djava.io.tmpdir=/home/qwertyoruiop/tmp" \
  sbt 'testOnly m68k040.ls.* m68k040.cache.*'
```

(The default `SBT_OPTS` in this environment sets no `-Xmx`, which caps the heap at
1 GB and OOMs the test compile. That is why the explicit setting is needed.)

---

## 6. Not claimed

- **This does not fix the boot.** No RTL was changed.
- **This does not prove the I-side hole is the cause of the SCSI hang.** It proves
  the mechanism exists and fires on unmodified RTL. It does *not* show the frontend
  is actually steered into `0x50F0Fxxx` during the boot — that requires either a
  BTB/RAS target or sequential run-ahead landing there, and I did not demonstrate
  either. Establishing it is exactly what the **MMIO transaction capture and v1
  diff** would do, and that remains the top gap.
- The table-walker finding is a real rule violation but its route to MMIO is
  undemonstrated.
- The `ls`/`cache` and fuzz numbers above are not measured.

## 7. Suggested next steps (not done here)

1. **MMIO transaction capture + v1 diff** — still the decisive experiment, now with a
   sharpened question: does `axi_i` (not just `axi_d`) ever address `0x50F0Fxxx`?
   That single bit distinguishes this hypothesis from the remaining ones, and the
   I-side is a *separate AXI port*, so it is directly separable in a capture.
2. If confirmed, the I-side fix is structural, not a patch: `s1Unresolved` must not
   dispatch a fill for an INHIBITED verdict, and an architectural fetch from an
   inhibited page (which is legal) needs a narrow non-allocating read rather than a
   64-byte burst — i.e. the I-side needs its own `MmioCover` analogue.
3. Give the table walker a flush term (`umFlush` on the D side, any flush term at all
   on the I side) and an abort port.
