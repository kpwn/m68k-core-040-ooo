# FMax closure, "Lever U1": reuse the registered spec instead of re-decoding ucPendPkt (design)

## Context

Part of FMax-closure round 3 (see `.superpowers/sdd/progress-fmax-levers-2026-08-08.md`
for the full history). A netlist-grounded worst-path survey on the
current checkpoint (both round-2 levers landed,
`.../scratchpad/fmax-ucpendpkt-grounding-report.md`) found the
`DecodeStage_logic_ucPendPkt_words_0_reg -> FetchAlignPlugin_logic_decodePc_reg/CE`
family is the LARGEST of three currently-tied worst-path families (110 of
the worst 400 post-route endpoints, worst slack -1.624ns).

**This lever alone will NOT move the design's top-line FMax** — two other
independent families (frontend `pred_lenWords -> dstEa_disp` at -1.693ns,
LS/ROB `tagMem -> faultAddrStore` at -1.521ns) are equally or more
critical and are being addressed by separate, parallel design efforts
("Frontend Lever C", "LS/ROB Lever C"). Per this session's own repeatedly
relearned lesson, this lever must land AND be gated TOGETHER with at
least one of the others before any real top-line movement is expected.
Implement and review it on its own merits (it is genuinely free and
independently correct), but do not run a solo post-route gate expecting
a top-line result.

## The mechanism (source-cited, netlist-confirmed)

`DecodeStage.scala:886` (the µcode-resume handoff, re-decoding a stashed
slot-1 opword to determine how to resume the front end after a complex/
µcode instruction completes):

```scala
// current (re-derives a value that already exists elsewhere)
val ucPendSpec = OperationDecoder.decode(ucPendPkt.words(0))
// ... feeds ucComplexResume -> FetchAlignPlugin.resume
```

**This duplicates `fed.payload.specs(1).spec`** (the "ANGLE E + LEVER 2"
pre-computed offload, `DecodeStage.scala:38-49`/`:83-90`, already
established this session as the mechanism computing `Offload` — including
`spec` — on the Aligner's pre-register output and carrying it through the
`fedIn -> fed` `PipeStage`). Both `ucPendSpec` and
`fed.payload.specs(1).spec` are `OperationDecoder.decode(...)` applied to
the SAME opword (`ucPendPkt.words(0)` and `fed.payload.specs(1)`'s source
opword are the same word by construction — the grounding report confirms
"all four `ucPendPkt` writers take the same source in the same cycle" as
`fed.payload.specs(1)`), so **the two values are provably identical, not
merely similar** — this is a redundant computation, not an approximation.

**Netlist-measured effect** (`report_timing -through` probes, matching
this round's established methodology): the slot-0 resume path, which
ALREADY reuses a registered spec value instead of re-decoding (an
existing precedent in this same file), measures **-0.853ns**. The
raw-words re-decode path this lever replaces — projected to become the
new limiter for this family after the fix — measures **-1.063ns**, versus
today's **-1.624ns**. Estimated improvement: **+0.3 to +0.6ns** on this
family's worst endpoint.

**Cost**: ~52 flops to hold the registered spec value for the stash
duration (however long `ucPendPkt`/the stash mechanism already holds
state — this is additional STORAGE for an already-existing value, not a
new pipeline stage; ZERO latency cost). Likely a net LUT win overall from
deleting a whole redundant `OperationDecoder` instance.

## The fix

Replace the re-decode at `DecodeStage.scala:886` with a stashed/held copy
of the ALREADY-COMPUTED `spec` value from whichever register currently
holds `ucPendPkt` (find the exact stash mechanism — `DecodeStage.scala`'s
stash/µcode handling, referenced in this session's Slice-1 design spec as
"a single instruction can crack to 3 µops... slot1 (the NEXT instruction)
is STASHED and replayed next cycle" — re-verify the exact stash structure
against live source, this note is from an earlier, possibly-stale
understanding of the file). The stashed `spec` must be captured at the
SAME cycle `ucPendPkt` itself is captured (mirroring how `fed.payload.specs(1).spec`
is captured alongside its own packet), not re-derived later.

**Exact mechanism is an implementation-time determination** — the plan-
writing pass must:
1. Locate every one of the "four `ucPendPkt` writers" the grounding
   report found (grep `ucPendPkt.*:=` across `DecodeStage.scala`) and
   confirm each one's source opword is the SAME as some already-available
   `spec` value at that exact write site (the report's claim that "all
   four take the same source in the same cycle" as `fed.payload.specs(1)`
   must be re-verified against each site individually, not assumed to
   hold uniformly from the report's summary).
2. Determine whether a single new stash register (`ucPendSpecStash` or
   similar) suffices for all four writers, or whether the writers are
   genuinely different enough (different pipeline points) to need
   separate handling.
3. Confirm `DecodeStage.scala:886`'s consumer(s) of `ucPendSpec` — is
   `spec` the ONLY field consumed from `OperationDecoder.decode`'s output
   at this site, or are other fields (`srcEa`/`dstEa`/etc, if
   `OperationDecoder.decode` returns more than just `size`/`op`) also
   consumed here that would need their own stashing? (Re-verify
   `OperationDecoder.decode`'s actual return type/fields — do not assume
   it returns only what this lever needs.)

## Non-goals

- The frontend `pred_lenWords -> dstEa_disp` family and its own "Lever C"
  (structural register split) — separate, parallel design effort.
- The LS/ROB `tagMem -> faultAddrStore` family and its own "Lever C"
  (flatten the ROB fault write-enable decode) — separate, parallel design
  effort.
- Any change to the STASH mechanism's own semantics (when/why an
  instruction gets stashed, how long it's held, what triggers replay) —
  this lever only changes WHAT VALUE is used to drive the resume decision
  once a stash already exists; it does not change the stash/replay
  control flow itself.

## Verification requirements (binding)

- `~/sbt/bin/sbt compile` clean.
- **The core correctness claim — `ucPendSpec === fed.payload.specs(1).spec`
  at the moment of use, for every reachable case — must be proven, not
  assumed.** A directed test (or, if tractable given `OperationDecoder`'s
  domain size, an exhaustive one matching this round's established
  precedent — `PredecodeSimpleLenSpec`'s 268M-configuration sweep,
  `OperationDecoderSpec`'s 65536-opword sweep) comparing the OLD
  (re-decoded) value against the NEW (stashed/reused) value for every
  reachable stash scenario is required.
- Existing `DecodeStage`-adjacent tests covering the µcode-resume/stash
  path specifically (locate via grep — this project's own established
  practice, do not guess file names) must pass unchanged.
- `ExecuteLockStepSpec` full suite: expect 390/394 (this session's
  standing baseline).
- Full ported test corpus (~870 tests) via
  `tools/fuzz/ported-sweep-parallel.sh`: zero new regressions vs. current
  baseline. Targeted µcode/complex-instruction ported tests (the stash
  mechanism exists specifically for multi-µop instructions and
  µcode-engine resume) run explicitly before the full sweep.
- OOC-synth-only AND the real post-route gate, delta reported explicitly.
  **Per this design spec's own Context section: do NOT expect a
  meaningful top-line FMax movement from this lever alone** — report
  whether the targeted `ucPendPkt -> decodePc` family's OWN slack improves
  causally (the success criterion for this task in isolation), and note
  explicitly that the real accept/reject gate is the COMBINED measurement
  once the frontend and LS/ROB "Lever C" efforts also land.

## Open questions for the plan-writing pass

- Confirm exact live line numbers (`DecodeStage.scala:886` and all four
  `ucPendPkt` writer sites) before editing — re-verify, standard
  convention for this initiative.
- Confirm `OperationDecoder.decode`'s full return type and whether
  anything beyond `spec` (or beyond what's already carried in
  `fed.payload.specs(1)`) is consumed at the re-decode site.
- Determine the exact stash-register shape (one register vs. per-writer)
  per the "Exact mechanism is an implementation-time determination"
  section above.
