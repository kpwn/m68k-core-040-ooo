# Board fetch corruption: evidence-driven RTL review

## Status

Update: a specific installer/reclamation race is now reproduced in RTL simulation
(see below). Its causal link to the board captures remains unproven. The board's
proven defect is a wrong
instruction word paired with a ROM PC at the accepted DecodeFeed boundary,
upstream of execution and exception-frame creation. This review does not claim
that every observed crash has that cause. No production RTL changed.

Reviewed CPU 9d3ab41e against board CPU df89712a: IcachePlugin and
InstructionBuffer are unchanged; FetchAlignPlugin differs only in simulation
visibility annotations. Thus these CPU code paths describe the failing image,
not just a newer attempted fix. SoC 4b6729c3 includes changes absent from the
board's efb2e55d; those must not be treated as already tested on hardware.

## Evidence and constraints

1. In the controlled batch, 13/20 runs captured PC4080e2a2 with expected2228,
   observedA198, in both decode slots. FetchWordCheck records only feed.fire,
   with slot1Valid for slot1 and faults excluded. It copies the PC and word
   together before comparing. This is not merely a stale architectural-PC read
   or an inference from an exception frame. Capture alone does not prove the
   packet retired; six fatal runs also retain the corresponding A-line exception.
2. Host ROM reads remained correct before and after completed D-cache push.
   This argues against the ordinary backing-memory word being changed; it does
   not prove L1 instruction data or ITLB state agrees with host memory.
3. Reference ROM contains eight aligned A198 words. Exactly one has low12=2a2:
   offset872a2. The complete candidate sequence
   A198 7000 4CDF 0F1E 4E75 occurs exactly once, at872a2.
4. Trial17's halted trace returns from the trap handler to e2a4, e2a6, e2aa,
   then110. Those boundaries fit MOVEQ / MOVEM / RTS at the candidate source.
   They do NOT directly capture those opcodes. In particular the trace crosses
   the e2a8 fetch-window boundary after exception handling: a single stale
   eight-byte response before the trap is not a complete explanation. A poisoned
   resident line or repeatable misassociation fits better, but is not proven.
5. The same instruction routine executes correctly earlier in boot. This and
   the long runtime disfavor a permanent byte-lane wiring error or fixed address
   decode omission. Do not treat the broken REPL step command as single-step proof.
6. Earlier RAM trace40b40 disagrees with the host opcode/branch displacement even
   after D-cache push and an identity host page walk. This supports investigating
   a generic instruction association problem, not assuming ROM-specific logic.
   It is weaker evidence than the passive ROM word capture because it is derived
   from trace plus later memory reads.

## Address geometry

| Quantity | Requested4080e2a0 | Candidate408872a0 |
|---|---|---|
| L1 set VA[11:6] | 0a | 0a |
| L1 physical tag PA[31:12], if identity | 4080e | 40887 |
| L1 line offset | 20 | 20 |
| ROM-folded address at L2 | 4000e2a0 | 400872a0 |
| L2 set PA[17:6] | 38a | 1ca |
| L2 tag PA[31:18] after fold | 1000 | 1002 |

XOR=89000 changes bits12,15,19. Both addresses are legal ROM accesses and the
mirror fold preserves the differentiating low20 bits. A simple L2 wrong-way
selection at the CORRECT set cannot select the candidate, because its set
differs. L2 wrong-set/context or refill routing corruption remains possible.

With TC=c000 (8K pages), IcachePlugin constructs PA using translated[31:13]
and ORIGINAL lookupPc[12:0]. An ordinary stale PPN alone cannot transform e2a0
into872a0, because bit12 must remain one, but the candidate's bit12 is zero.
This does not clear MMU bugs involving request association or page-size state.

## RTL paths checked

- IcachePlugin lookupTick captures s0Pc/s0Ppn/tagQ/validsQ on cmdPort.fire.
  The synchronous line array uses virtual set/beat, while s1WayOh chooses the
  registered matching tag. A stale/wrong way's data under a correct target tag
  fits the geometry. Look especially at allocation and refill ownership, not
  just the comparator expression.
- PREDECODE writes lineMem only under doAllocate, and commits tag/valid on the
  second beat. The old inhibited-fill-overwrites-valid-victim bug is already
  guarded in the failing CPU: do not present that historical fix as a new find.
- Demand/prefetch fills share fillLo/fillHi indexed by RID. mshrArSent,
  generation matching, beat state and arOutstanding guard ownership. A wrongly
  associated but protocol-valid response could still install under the requested
  tag. Existing tests have not reproduced that on the complete socket/SoC path.
- s1Hit uses exactlyOne, not merely OR, and unresolved multi-hit demands are
  recovered. Additionally A198 is not simply2228 OR some other word: bits2020
  present in2228 are absent inA198. Simple OR contamination with the correct
  word present is therefore not this signature.
- FetchAlign consumes every FetchRsp by outstanding-ring HEAD. It does NOT
  check rsp.pc against that head; InstructionBuffer stores words/predecode, not
  source PCs. Decode PCs are advanced separately. Therefore a lost/extra/reordered
  response or stale framing state can produce exactly a correct-looking PC with
  another window's bytes. This is a diagnostic blind spot, not proof the ring is
  faulty. Review includes redirect/FTQ truncation, not just external AXI ordering.
- InstructionBuffer uses modulo20 head/tail addressing and coordinated shift /
  push counts. No concrete arithmetic defect found in those expressions.
- SocketByteOrder only permutes bytes within each32-bit lane, once; it cannot
  itself manufacture the candidate's different page. Reset discard is outside
  normal running operation, and no capture currently connects its activation
  to the later wrong fetch.
- ROM fold feeds the dedicated L2 fetch path directly, not the ordinary xbar.
  L2 assembles two128-bit responses per256-bit beat using per-RID state. This
  leaves response ownership / same-ID ordering worth checking under mixed traffic.

## Failure families: what is and is not connected

- Trials01/06/16/17/19/20: wrong opcode plus retained A-line then fatal transfer.
  Strongest common causal family; prioritize it.
- Trials02/04/08/11/13/14/18: wrong opcode despite still running at timeout.
  These are corrupt executions, not successful boots.
- Trials03/09/10/12: illegal instructions in high RAM;10/12 share a trace.
  No monitored-PC mismatch. Preserve as a separate signature pending evidence.
- Trial07: illegal instruction in ROM; trial15: address error with FA1.
  Neither establishes the same wrong-window source.
- Trial05: running with no selected-PC hit; insufficient observation for success.
- Earlier RTS-to1 has a post-push stack long1 consistent with the actual bad
  target. That validates neither the origin of the bad stack value nor A7 drift.
  Another captured failure ends in JMP, so a universal RTS/frame-only theory is
  insufficient. C30 is likewise a terminal symptom, not the earliest bad event.

## Ranked working hypotheses and decisive evidence

1. Correct target tag/PC with wrong resident L1 line or wrong refill data.
   Best fit to same-set/same-offset source and apparent persistence across trap
   return. Need raw L1 FetchRsp PC+64-bit data BEFORE aligner, ideally refill
   address/RID/data and chosen way/tag, captured on the same failing episode.
2. Correct cache data attached to wrong frontend PC through ring/FTQ/IBuf framing.
   Still live because current capture is AFTER this entire path. If raw L1
   response is correct but DecodeFeed is wrong, focus here instead of DDR.
3. Lower-path response/context corruption that poisons L1: L2 hit/refill,
   reassembly, socket slices, mixed data writes and instruction reads. Host reads
   can remain correct. Same-set standalone L1 passes do not eliminate this.
4. Ordinary stale8K translation, ROM-fold alias, fixed endian error, or exception
   frame as sole explanation: each fails at least one constraint above. Not a
   basis for a speculative fix.

The128MiB baseline and targeted stride prelude passed, but identity translation,
no real frontend, no socket boundary/guard, and limited concurrent writers mean
they do not cover the entire board failure. The full stride sweep remains live.
The100MHz HEAD build is unchanged by this review; use it to test the newly
integrated SoC fixes before assigning any improvement to a particular mechanism.

## Reproduced installer slot-reuse defect

`IcacheInstallReuseSpec` uses only normal fetch requests, valid AXI responses,
and the invalidateAll input. No internal register or memory pokes. Logs:
`/tmp/codex-icache-install-reuse.log` (publication assertion),
`/tmp/codex-icache-install-reuse-data-fixed.log` (wrong lower-half response),
`/tmp/codex-icache-install-reuse-upper.log` (exact old upper-half response).

The failing sequence is:

1. Complete prefetch2040 in slot1 while accepting a resident hit8180.
2. Assert invalidation on the cycle the completed slot enters the installer.
   Poison is set and all array valid bits clear.
3. During INSTALL_ARM the generic poisoned-completion cleanup at
   IcachePlugin.scala:2364 releases slot1. The installer still holds its index,
   old set1 and old way. The delayed8180 disposition restarts the prefetch window.
4. First PREDECODE beat suppresses its write because the old poison is set.
   The allocator at2294 now reuses the free slot for81c0, resetting poison and
   replacing its live tag with8.
5. Second PREDECODE beat sees poison cleared and reads the NEW mshrTag, but uses
   OLD installSet/way and OLD fillHiQ. It publishes tag8 at set1 (line8040),
   containing old2060 bytes in its upper half. The installer completion also
   clears the NEW slot's ownership.
6. A subsequent actual fetch8060 returns7c1e472684fc2c62, exactly the golden
   bytes for2060, instead of4a7be666d159cd22. This is a real FetchRsp data error,
   not merely a transient internal invariant failure.

The defect is the missing installer-ownership exclusion on early MSHR reclaim.
A slot must remain reserved/poisoned until the installer finishes consuming its
metadata, even if no external read is outstanding. arOutstanding cannot protect
this lifetime: the old AXI burst has legitimately finished already.

This mechanism fits the board's upper-half, same-set, different-tag substitution.
However, the board has not captured the required invalidation/overlapping-hit
sequence. Do not claim the hardware root cause is conclusively established.
No production fix applied; the user requested RTL diagnosis, and the current
build must not silently change underneath its provenance.

Validation handoff: final regression (with a correct-path demand responder) again
failed with the exact old2060 bytes, log /tmp/codex-icache-install-reuse-final.log.
The existing fast gate passed372 tests,2 ignored,264 suites, log
/tmp/codex-install-reuse-fast.log. The new tagged Verilator regression is NOT
part of that fast gate and remains deliberately red on current production RTL.
Residency process663720 was resumed after serialized testing. The100MHz build
stopped after successful synthesis on an outdated Ethernet CDC register-name
constraint; no new bitstream was produced.

## Fix validation

The subsequent goal continuation applies a local ownership guard: early cleanup
must not reclaim installIdx while predIsPfReg is true and the FSM is in
INSTALL_ARM or PREDECODE. Final PREDECODE still releases that slot normally.
No new state, service, cache geometry or reset-discard changes are introduced.

The reproducer now passes: slot1 retains2040 through both install beats, remains
poisoned, and publishes no set1 line. It is reused only after final teardown.
Fetch8060 misses/refills and returns4a7be666d159cd22, the correct address oracle.
All six existing invalidate-stage tests pass, followed by the full26-test
IcachePrefetchSpec suite. Logs /tmp/codex-icache-install-reuse-fix.log and
/tmp/codex-icache-prefetch-fix-all.log. The post-fix fast gate is separate,
/tmp/codex-icache-installer-fix-fast.log; inspect its terminal result before
handoff. Board confirmation remains outstanding.

Post-fix fast gate completed successfully:372 passed,2 ignored,264 suites.
The long residency process resumed and still tests its original compiled CPU
revision, not this newly fixed RTL. Its result must retain that provenance.
