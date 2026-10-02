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
