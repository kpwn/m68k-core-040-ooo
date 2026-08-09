# Frontend throughput audit

**Status:** REVIEW COMPLETE; no RTL change is authorized by this audit. The
fetch-directed FTB design and implementation plan are blocked until their P0
findings are amended in both documents.

**Scope:** fetch address generation, BTB/gshare/RAS prediction, ITLB and L1I,
FetchAlign and its outstanding ring, instruction buffering and alignment,
decode expansion, MicroOpQueue admission, rename, and dispatch. Findings are
classified as cheap, complex but FPGA-friendly, or FPGA-unfriendly.

## 1. Executive result

The ordinary decode/rename/dispatch frontend is already latency-pipelined and
can sustain one two-uop group per cycle while credits are available. The normal
resident L1I path is also internally II=1. The dominant measured frontend loss
is correctly predicted taken control flow: the current decode-time predictor
discovers the transfer only after fall-through fetches have entered the frontend,
then discards them and pays about four dead restart cycles plus the common
slot-1 defer cycle.

The proposed fetch-directed FTB attacks the right bottleneck but its current
registered-result protocol is dead on the intended stream. It must not be
implemented as written.

## 2. P0: repair the fetch-directed design before RTL

### 2.1 Registered-result freshness is dead at II=1

Section 2.4 defines `ftbRes` as the lookup for the previous cycle's `fetchPc`
and applies it only when `RegNext(fetchPc) === fetchPc`. Live FetchAlign advances
`fetchPc` by eight on each `ic.cmd.fire`. Consequently, on an uninterrupted
sequential stream the result for `P` arrives while the live pointer is `P+8`;
freshness is false on every cycle. A test that checks only prediction correctness
without a nonzero application count can pass while the feature does nothing.

The FPGA-friendly replacement is a held, tokenized fetch-plan pipeline. A token
must carry `{planPc, prediction(planPc), valid}` and be held under cache
backpressure. Issuing the token creates the predicted or sequential successor
token for the following cycle. This retains a registered predictor boundary and
II=1 steady-state issue without a wide combinational FTB-to-PC feedback path.

### 2.2 Slot-1 fallback cannot be deleted in phase 1

Today, a predicted-taken branch found in aligner slot 1 is deferred into slot 0,
where the normal BTB/gshare path redirects it. The FTB proposal deletes the
speculative slot-1 lookup. Direct-mapped collisions, a second branch in a
window, cross-window branches, and entries not yet trained are all valid FTB
misses; deleting the old lookup makes those branches fall through to expensive
commit-time recovery. That contradicts G4's requirement for exact fallback.

Retain the slot-1 path in phase 1. It may be removed only after an equivalent
early lookup or a deliberately amended and measured fallback policy exists.

### 2.3 Replace the impossible differential oracle

The proposed enabled-versus-disabled feed-trace comparison includes the emitted
PC while also allowing prediction to change which instruction is fetched next.
Those traces are not required to match. Replace it with:

- a local framing oracle for every emitted packet;
- a correct-prediction test that proves target-gap reduction;
- a full-core retired architectural trace comparison after wrong-target recovery;
- explicit nonzero FTB-application and mutation coverage; and
- exact control-operation confirmation, or a narrower stated fault model, so an
  arbitrary same-length non-branch entry cannot redirect without BranchEU ever
  verifying it.

The canonical `ftqPast` expression also needs the implementation plan's
`ftqV && ftqDiff(31)` form. The design form `ftqNear && ftqDelta(31)` is
identically false because `ftqNear` excludes negative deltas.

## 3. Prioritized opportunities

| priority | path | classification | recommended action | principal risk |
|---|---|---|---|---|
| P1 | FetchAlign ring turnover | cheap, FPGA-friendly | credit a same-cycle response when the depth-three ring is full, allowing head-pop/tail-push turnover | stale/drop ownership and exact IBuf reservation on collision |
| P1 | L1I throughput proof | cheap, test-only | burst at least six unique warm same-line commands on consecutive cycles and require six associated responses at the specified latency with zero AXI reads | existing helper is response-serialized and cannot prove II=1 |
| P2 | taken restart | complex, FPGA-friendly | use a four-entry recent-window replay buffer as the low-risk interim lever, or repair the tokenized FTB design | redirect/IBuf injection arbitration and invalidate coherence |
| P3 | fetch-directed prediction | complex, FPGA-friendly | held tokenized fetch-plan loop, separately gated for application coverage, IPC, LUTs, and routed FMax | predictor-result-to-successor timing and confirmation/recovery |
| P4 | cold slot-1 calls | cheap/moderate | push RAS state for a safely emitted slot-1 call; keep fetch-time return prediction separate | never push a wrong-path call behind a slot-0 control transfer |
| P5 | MicroOpQueue admission | cheap | use actual push demand and same-cycle pop credit instead of always reserving four entries | ready-chain timing and exact simultaneous pop/push count |
| P5 | rename admission | cheap/moderate | request zero, one, or two credits independently from each freelist rather than requiring two in all three classes | keep count/commit-return logic out of a long ready loop |
| P6 | L1I hit-under-miss | complex, FPGA-friendly if measured | one parked demand MSHR plus tagged or ordered hit responses | current `FetchRsp` is untagged and assigned to the ring head |
| P7 | complex-instruction resume | complex, profile first | preserve already-buffered contiguous fall-through when the resume PC proves it is reusable | framing and raw-word/resume-PC correctness |
| avoid | collapse ITLB and L1I stages | complex, FPGA-unfriendly | retain the registered T stage | recreates the route-dominated live-translation/tag-compare cone for little steady-state gain |
| avoid | wider cracked/microcode engines | complex, FPGA-unfriendly | retain current sequencing until measured hot | wide queue writes and namespaced dependent temporaries cost area/routing |

## 4. VIPT and resident-hit behavior

L1I is VIPT by geometry: 64 sets times 64-byte lines fit within the 4 KiB page
offset, the virtual PC selects the set, and the physical page number supplies the
tag. Its resident path can accept one command per cycle, but the implementation
does **not** launch ITLB and cache-array lookup in parallel. It registers
`{ppn, pc}` in a translation T stage and performs the physical tag lookup/data
BRAM arm from those registered values on the following cycle. This three-cycle
hit pipeline is intentionally favorable to FMax and normally hidden by fetch
run-ahead.

That live behavior conflicts with the original I-cache slice document's
parallel-lookup description. The canonical architecture must explicitly ratify
the later registered implementation or require a new FPGA-safe parallel design.
The audit recommends ratifying the pipeline because it is II=1 and the old live
translation cone was a measured timing problem.

The D side is different: the landed LSU launches DTLB lookup and the virtual-set
D-cache probe from the same registered P2 token, then joins the physical tag and
tokenized cache result. It is genuine parallel VIPT on the aligned resident hit
path.

## 5. Non-vacuous gates

- L1I: six or more distinct warm commands accepted on consecutive cycles, six
  uniquely associated resident responses at the specified pipeline latency, and
  zero AXI activity.
- Fetch ring: fill all three records, return the head, accept a replacement in
  the same cycle, and verify order plus redirect/stale/drop collisions.
- End-to-end frontend: a long warm one-word stream with downstream ready high,
  sustained consecutive `feed.fire`, valid slot 1, exact PCs, and no AXI reads.
- Queue and rename: prove mathematically maximal acceptance for the actual group
  demand, including same-cycle return credit.
- FTB: require nonzero application and correct-PC coverage before interpreting
  any IPC or correctness result.

## 6. Timing discipline

The checked-in route places FetchAlign at global WNS (4.487 ns data delay,
-0.508 ns slack, 14 logic levels, 71.7% routing), with seven of the ten worst
paths in the slot-1 BTB/decode-PC family. An earlier combinational speculative
I-cache cone lost 36.29 MHz before register splitting. These results favor small
held tokens, registered lookup boundaries, shallow queues, and separate routed
gates. They argue strongly against a combinational FTB read feeding `fetchPc` or
restoring a live ITLB-to-cache tag path.
