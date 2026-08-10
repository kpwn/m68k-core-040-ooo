# Registered complex-resume action — FMax recovery design

Status: **BINDING AMENDMENT**

## 1. Problem and measured evidence

The pre-cut, no-floorplan physical gate at 250 MHz was pinned to
generated-netlist MD5 `3af25ea1812c64fb753ee3771e7ede3d`:

- post-synth WNS: -3.669 ns;
- post-route WNS: -2.714 ns (148.94 MHz);
- routed area: 111,210 CLB LUTs, 50,604 CLB registers, 26 BRAM tiles,
  and 4 DSP48E2s;
- routing completed with no failed nets, no hold violation, and only one
  level-1 east-direction congestion window.

All ten reported worst setup paths are the same family. The worst path is
`DecodeStage_logic_ucPendPkt_words_0_reg[2]/C` to
`FetchAlignPlugin_logic_fetchPc_reg[8]/D`: 6.694 ns, 27 logic levels,
2.204 ns logic and 4.490 ns route. The path reconstructs the real length of
a rare complex MOVE/MOVEM in `DecodeStage`, drives the combinational
`ucComplexResume` Flow through `BackendWiringPlugin.mispredictRedirect`, then
continues through fetch control, the live ITLB lookup, I-cache command ready,
and finally the fetch pointer/ring controls.

The first registered boundary was implemented and remeasured on regenerated
netlist MD5 `b14d3e7a4136dc33b3df0c63c66f3296`:

- no-floorplan post-synth WNS: -2.166 ns;
- no-floorplan post-route WNS: -2.170 ns (162.07 MHz), TNS -21,631.773 ns,
  31,184 failing setup endpoints, and WHS +0.020 ns;
- combined decode+D-cache floorplan post-route WNS: -1.567 ns (179.63 MHz),
  TNS -14,735.770 ns, 26,242 failing setup endpoints, and WHS +0.023 ns;
- routed area: 114,906 CLB LUTs, 50,532 CLB registers, 26 BRAM tiles,
  and 4 DSP48E2s; and
- routing completed with zero failed, unrouted, or partially routed nets.

The first cut removed every old `ucPendPkt/ucPendValid -> FetchAlign` path and
improved no-floorplan routed WNS by 0.544 ns.  With the beneficial combined
floorplan, however, the new worst path is now
`DecodeStage_logic_ucComplexResumeValidReg_reg/C` to
`FetchAlignPlugin_logic_ringStale_2_reg/D`: 5.548 ns, 15 logic levels, and
72% route delay.  The source is physically held in the decode pblock while
the action immediately continues through frontend redirect control, the live
ITLB lookup, I-cache command control, and the fetch ring.

Decode already knows the complete resume target before it asserts the event,
and the frontend is stalled while waiting for that event.  A second registered
boundary at the frontend consumer therefore removes the remaining cross-region
action-plus-lookup path without reducing steady-state frontend throughput.

## 2. Binding behavior

`DecodeStage` shall split the existing action into:

1. a combinational local detector and target calculation; and
2. a registered, one-cycle `Flow[UInt(32 bits)]` exposed under the existing
   `ucComplexResume` name.

The register captures the exact old-cycle target when either supported case
is detected:

- a fully framed complex memory-indirect MOVE at `ucBegin`; or
- the supported brief-indexed PC-relative MOVEM at `movemBegin`.

The Decode service action is a pulse, not a level. It fires exactly one cycle
after the local detector, with the detector's target unchanged. The two
detector arms are mutually exclusive.

`DecodeUopService` shall expose this directionless action as
`complexResume: Flow[UInt]`; top-level wiring must consume that service and
must not reach into `DecodeStage.logic`. `DecodeStage` remains the sole service
producer. Test and synthesis-only alternative service producers tie the action
inactive unless their contract explicitly drives it.

Each real frontend integration shall then pass the service action through one
additional registered frontend-action stage. A detector at cycle C therefore
produces the Decode service pulse at C+1 and the frontend redirect action at
C+2. The second register holds only one valid bit and the exact 32-bit target;
because the source is a one-cycle pulse and complex decode is stalled, it
cannot be overwritten by another supported action.

`pipeFlush` has capture and pending-action cancellation priority at both
boundaries. A detector coincident with `pipeFlush` must not become a later
resume. A pending Decode or frontend action coincident with the architectural
redirect represented by `pipeFlush` is discarded; the architectural redirect
remains authoritative. Reset clears both valid registers.

Top-level arbitration remains architecturally unchanged after the new local
stage:

```scala
val frontendResume = ComplexResumeActionPipe(decode.complexResume, pipeFlush)
faRedir.valid   := doFlush || frontendResume.valid
faRedir.payload := Mux(doFlush, flushPc, frontendResume.payload)
```

No new `Global` key is introduced and no producer ownership changes. All real
frontend integrations observe the action two cycles after detection. Backend-
only alternative `DecodeUopService` producers expose an inactive action.

## 3. Performance and area contract

- Normal sequential fetch, BTB/RAS fallback, FTB application, I-cache hit
  cadence, decode issue, and LSU cadence must be cycle-identical.
- Only the two supported complex-resume cases gain two frontend-stall cycles
  relative to the original combinational action (one more than the first cut).
  They remain overlapped with their microcoded execution and do not become
  one-at-a-time datapaths.
- Total cost is two valid bits and two 32-bit target registers across Decode and
  frontend wiring, plus negligible local control. The second amendment adds
  only one valid bit and one 32-bit target register. No LUTRAM, BRAM, DSP, CAM,
  issue port, or cache port may be added.
- The current four DSP48E2 multiplier mapping
  (`AREG=2/BREG=2/MREG=1/PREG=1`) must remain intact.

## 4. Verification contract

The implementation must prove non-vacuously:

1. every accepted local detector produces exactly one Decode service action on
   the following cycle and exactly one frontend action one cycle after that,
   with the same target at both boundaries;
2. no Decode service or frontend action occurs without an accepted detector;
3. `pipeFlush` on the detector cycle suppresses capture;
4. `pipeFlush` while either action boundary is pending suppresses the stale
   action;
5. the supported complex MOVE and PC-indexed MOVEM paths still resume at their
   exact architectural fall-through PCs; and
6. ordinary frontend streams do not produce a resume pulse.

RTL assertions may enforce each one-cycle association and flush cancellation,
but a directed real-frontend test must exercise at least one supported
detector and observe the C+2 action. The mandatory handoff gate remains
`make SBT=~/sbt/bin/sbt test-fast`; targeted Verilator execution is serialized
under the repository policy.

## 5. Physical acceptance

Regenerate the full-core Verilog and run fresh no-floorplan and selected
floorplan synth/route controls at 250 MHz. Report source and gated-netlist MD5
separately. Acceptance requires:

- disappearance of both the old `ucPendPkt/ucPendValid -> FetchAlign` family
  and the current `ucComplexResumeValidReg -> FetchAlign` family from the
  worst-path set;
- no routed area regression beyond the second boundary's expected ~33
  registers and negligible LUT control relative to the exact current netlist;
- no hold violation or new routing failure; and
- a routed WNS improvement attributable to the new register boundary.

This slice is not by itself claimed to close 250 MHz. The next limiter is
selected only from the new routed checkpoint. The optimization target remains
250 MHz; 200 MHz is the deployment floor, not a relaxed synthesis constraint.
