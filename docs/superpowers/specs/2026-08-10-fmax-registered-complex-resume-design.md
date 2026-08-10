# Registered complex-resume action — FMax recovery design

Status: **BINDING AMENDMENT**

## 1. Problem and measured evidence

The fresh current-RTL, no-floorplan physical gate at 250 MHz is pinned to
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

The cross-plugin combinational action is unnecessary. Decode already knows
the complete resume target before it asserts the event, and the frontend is
stalled while waiting for that event. One registered boundary therefore
removes the measured decode-to-ITLB/cache path without reducing steady-state
frontend throughput.

## 2. Binding behavior

`DecodeStage` shall split the existing action into:

1. a combinational local detector and target calculation; and
2. a registered, one-cycle `Flow[UInt(32 bits)]` exposed under the existing
   `ucComplexResume` name.

The register captures the exact old-cycle target when either supported case
is detected:

- a fully framed complex memory-indirect MOVE at `ucBegin`; or
- the supported brief-indexed PC-relative MOVEM at `movemBegin`.

The action is a pulse, not a level. It fires exactly one cycle after the local
detector, with the detector's target unchanged. The two detector arms are
mutually exclusive.

`pipeFlush` has capture and pending-action cancellation priority. A detector
coincident with `pipeFlush` must not become a later resume. A pending resume
coincident with the architectural redirect represented by `pipeFlush` is
discarded; the architectural redirect remains authoritative. Reset clears
the valid register.

The existing top-level arbitration remains unchanged:

```scala
faRedir.valid   := doFlush || ucComplexResume.valid
faRedir.payload := Mux(doFlush, flushPc, ucComplexResume.payload)
```

No new `Global` key or service is introduced, and no producer ownership
changes. All existing DUT integrations observe the same Flow one cycle later.

## 3. Performance and area contract

- Normal sequential fetch, BTB/RAS fallback, FTB application, I-cache hit
  cadence, decode issue, and LSU cadence must be cycle-identical.
- Only the two supported complex-resume cases gain one frontend-stall cycle.
  They remain overlapped with their microcoded execution and do not become
  one-at-a-time datapaths.
- Cost is one valid bit and one 32-bit target register, plus negligible local
  control. No LUTRAM, BRAM, DSP, CAM, issue port, or cache port may be added.
- The current four DSP48E2 multiplier mapping
  (`AREG=2/BREG=2/MREG=1/PREG=1`) must remain intact.

## 4. Verification contract

The implementation must prove non-vacuously:

1. every accepted local detector produces exactly one action on the following
   cycle with the same target;
2. no action occurs without an accepted detector;
3. `pipeFlush` on the detector cycle suppresses capture;
4. `pipeFlush` while an action is pending suppresses the stale action;
5. the supported complex MOVE and PC-indexed MOVEM paths still resume at their
   exact architectural fall-through PCs; and
6. ordinary frontend streams do not produce a resume pulse.

RTL assertions may enforce the one-cycle association and flush cancellation,
but a directed real-frontend test must exercise at least one supported
detector and observe the delayed action. The mandatory handoff gate remains
`make SBT=~/sbt/bin/sbt test-fast`; targeted Verilator execution is serialized
under the repository policy.

## 5. Physical acceptance

Regenerate the full-core Verilog and run a fresh no-floorplan synth/route at
250 MHz. Report source and gated-netlist MD5 separately. Acceptance requires:

- disappearance of every `ucPendPkt/ucPendValid -> FetchAlign fetch/ring`
  path from the worst-path set;
- no routed area regression beyond the expected ~33 registers and negligible
  LUT control;
- no hold violation or new routing failure; and
- a routed WNS improvement attributable to the new register boundary.

This slice is not by itself claimed to close 250 MHz. The next limiter is
selected only from the new routed checkpoint. The optimization target remains
250 MHz; 200 MHz is the deployment floor, not a relaxed synthesis constraint.
