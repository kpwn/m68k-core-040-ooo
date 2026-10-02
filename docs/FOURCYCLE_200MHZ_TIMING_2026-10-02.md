# Four-cycle 200 MHz routed timing, 2026-10-02

The frozen four-cycle CPU revision `e1fd71080ddeb3f7fb173557a2a755baa6c63438`
was implemented with the real MIG and throughput-v2 SoC profile. Vivado completed
both implementation commands and generated bitstreams. The 200 MHz image does
**not** meet routed setup timing: WNS -1.785 ns, TNS -30,717.590 ns, and 45,974
failing core-clock endpoints. Hold WHS is +0.009 ns and pulse-width WPWS is
0.000 ns. At 100 MHz, setup WNS is +0.059 ns, but a real MIG pulse-width check
fails by -0.068 ns. The opt-in profile and its build settings are documented in
the SoC repository's `docs/fourcycle-physical-profile.md`.

The routed reports are in
`macqd700-soc-worktrees/codex-physical-100-200/build/fourcycle-200mhz/`:
`timing_summary.rpt` contains 50 worst paths and
`reports/timing_route.rpt` contains 10. A read-only checkpoint query sampled
1,000 **distinct** worst core-clock endpoints into
`/tmp/codex-fourcycle-200mhz-core-top1000.rpt`. Their slack ranges from
-1.785 to -1.558 ns. The sample contributes -1,636.900 ns, about 5.3% of
the global TNS; its family counts must not be used as global TNS attribution.

| Source to endpoint family | Paths in 1,000-endpoint sample | Sampled deficit |
| --- | ---: | ---: |
| L2 to L2 | 277 | 447.528 ns |
| ROB to D-cache | 149 | 246.258 ns |
| D-cache to D-cache | 124 | 202.958 ns |
| Fetch align to I-cache | 109 | 179.891 ns |
| D-cache to DTLB | 80 | 134.790 ns |

The 50 worst paths split among several nearly tied cones. Thirteen ROB-head
to D-cache paths traverse LSU fencing and P4 admission, `p4CanLeave`, the
P3/P4 address selection, then cache probe forwarding before tag/data/valid
memory addresses. Selecting fall-through payload from registered P3/P4 stage
ownership instead of late `p3FastEnq` is a narrow, intended behavior-preserving
cut for this cone; it cannot close the other families on its own.

Of the 277 sampled L2 paths, 216 end at line-memory clock enables and 49 at
URAM enable/output-register enables. A representative path runs from `req_id`
through MSHR same-ID ordering and `pipe_adv_c` to the shared `rd_en` in
`l2c_ctrl.v`/`l2c_data.v`. Of the 124 D-cache internal paths, 95 run from a
tag read through miss discovery and store-pipe hold to wide S0 store payload
enables. All 109 sampled fetch-align to I-cache paths start at `quiesce_reg`
and end at MSHR or prefetch metadata. The D-cache miss-token to DTLB family
travels through LSU back-completion, P3 ordering, translation request fire,
and DTLB miss capture; it is a live control loop, not a direct cache-to-TLB
datapath. The median data path among the 50 worst is 6.502 ns, with 4.660 ns
of routing, so shorter/local control and fanout matter alongside logic depth.

Next timing work should validate the P3 payload cut with directed load
ownership, refusal/replay, split, store, flush and fault tests, the fast gate,
and a matched 200 MHz route. Further architectural cuts need a design-spec
amendment before RTL: L2 Q-stage read lookahead with exact merge/alloc/complete
semantics; an exact-next-state D-cache store-admission credit or skid; and a
frontend disposition boundary that preserves STOP, redirect, and page guards.
Each must keep the four-cycle dependent-load path and measured miss throughput
or justify its cost. Registering a `ready` signal alone is unsafe because it
can accept, lose, or duplicate a request after a same-cycle miss or flush.

An older real-MIG 200 MHz build (`sd-safe-cmd25`) had WNS -0.095 ns with 646
failing endpoints; another older profile (`codex-fpu200`) closed at +0.001 ns.
Those builds differ in CPU/SoC sources and configuration, so they demonstrate
that this board class can approach/meet 200 MHz, not which current feature
caused the regression.

## Combined P3/L2 candidate route

A second, matched-profile 200 MHz implementation used CPU revision
`1dc6d0d9439f124be703e5ccf21805765e481b7a` (the P3 registered-owner
payload selection already on `master`) and isolated SoC revision
`98a869902a2f0c22f9b232e5c079a1c4c0d86c37` (registered per-MSHR
same-source-ID ordering). The real MIG and all profile settings were unchanged.
Its routed report is at
`macqd700-soc-worktrees/codex-l2-id-order-timing/build/fourcycle-200mhz-p3-l2-idlook/timing_summary.rpt`.
Setup **did not close**: WNS -1.895 ns, TNS -36,045.047 ns, 53,070 failing
setup endpoints, with WHS/WPWS both 0.000 ns and no failing hold/pulse endpoints.
Compared with the frozen route, WNS is 0.110 ns worse, TNS is 5,327.457 ns
worse, and 7,096 more setup endpoints fail. This combined run cannot assign
causality to either RTL cut individually or distinguish RTL effects from
placement variation. Do not load this 200 MHz bitstream or promote the L2
candidate on timing evidence from this run.

A read-only routed-checkpoint query sampled 1,000 distinct worst core-clock
endpoints into `/tmp/codex-p3-l2-200mhz-core-top1000.rpt` and JSON of the same
stem. Slack ranges from -1.895 to -1.759 ns; summed sampled deficit is
-1,820.817 ns (about 5.1% of global TNS). The family ranking changed:

| Source to endpoint family | Frozen baseline | Combined candidate |
| --- | ---: | ---: |
| L2 to L2 | 277 | 25 |
| ROB to D-cache | 149 | 18 |
| Fetch align to I-cache | 109 | 0 in the top 1,000 |
| Gshare to I-cache | 0 in the top 1,000 | 244 |
| D-cache to D-cache | 124 | 156 |
| D-cache to DTLB | 80 | 91 |

The L2 and ROB/D-cache targets moved out of the sampled worst set, but the
newly exposed frontend and miss paths leave total timing worse. All 244
Gshare-to-I-cache paths start at a registered prediction direction and mostly
end at I-cache tag, MSHR, PPN, or prefetch enables. A related full routed path to `fetchPc` goes
through fetch target selection, the live ITLB lookup/cacheability decision,
I-cache command ready, and back into `fetchPc` (19 logic levels, 6.861 ns data
path, 5.222 ns routing). This is a same-cycle fetch admission loop. It should
be cut with an explicit frontend command ownership boundary, preserving
redirect, STOP, and fault behavior; simply delaying the branch target adds a
prediction bubble.

Among 156 D-cache internal sampled paths, 90 start at the nonblocking staged
physical address and 38 at the pending store address. Sixty-eight end at the
miss-line register. Among 91 D-cache-to-DTLB paths, 89 start at `missCmode`:
18 end at `missReqReg.vpn`, 7 at `missReqReg.token`, 5 at
`missReqReg.robId`, while response payload and TLB lookup/debug state take
many of the rest. An isolated DTLB payload-capture cut is being tested; even
if correct, it removes only a subset of this miss loop and still needs a
matched physical result. The next miss-side timing work should inspect the
staged-address-to-miss-line admission and refill-completion-to-translation
control independently, while keeping parallel miss acceptance and the
four-cycle hit path.

The isolated frontend quiesce/prefetch-owner candidate `e01b0629` is **not** a
fix for the Gshare-to-I-cache family. Focused tests and the 414-test fast gate
passed, but a matched `PerfCounterSpec` tight eight-line walk regressed from
2 I-cache misses / 6 useful prefetches on CPU `1dc6d0d9` to 8 misses / 0
useful prefetches on the candidate. Its conservative same-set timeout skips
prefetches the demand stream uses. The candidate also failed that test's
late-prefetch coverage assertion; the baseline passed. The larger microbench
could not support an IPC comparison because its *baseline* NOP-linearity
validation failed (spread 0.0842 cycles/op versus a 0.05 limit). Leave this
frontend branch isolated and design a fetch-command ownership boundary for
the predictor→ITLB→I-cache-ready recurrence instead.
