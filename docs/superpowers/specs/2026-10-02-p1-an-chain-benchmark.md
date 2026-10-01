# P1 eligible independent-load benchmark

The existing `chase-chains-*` sweep uses indexed `(A0,Dn.L)` loads, which are
outside the default-off P1 early load launch's simple `(An)` eligibility. Keep
that sweep as a control. Add `chase-an-chains-{4,6}-1536`: A0–A3 or A0–A5 hold
independent pointer chains; A6 holds the loop count; A7 remains the stack
pointer. Each body load is `MOVEA.L (An),An`, with no C/index operand. The
program first walks every pointer once to warm L2, then walks twice more plus
12 aggregate tail hops. Total measured pointer loads are identical across
the two arms: `2*1536+12=3084` (12,336 read bytes). The branch/control overhead
per pointer differs and must be printed with throughput.

The ring data uses separate 16-byte records per chain and a deterministic
permutation. The retirement oracle checks every A-register pointer result,
including warmup, against that chain's expected link; a matching final pointer
alone is insufficient. The measured-window probe counts accepted
`lsEu.logic.p1ReqFire` events, not eligible instructions guessed from source.
P1 OFF must count zero; P1 ON must count above zero for each An arm. Report
measured bytes/cycle, IPC, hot-door/MHSR occupancy and P1 accepts at one
frozen source and matched seed/memory/config. A null result remains valid if
accepted P1 events are demonstrably present; indexed controls remain unchanged.

No RTL eligibility, queue size, or default flag changes belong to this test.

## Matched result at `b0cf9b8e`

Both arms used seed 17, `l2:5:60:4096`, NB4/ring8/hot door, LS-OoO,
fusion/spec wake, P3/probe, early response/eager AR/preselect/dynamic release,
and probe-miss staging. Only `CPU_LS_P1_EARLY_LOAD` changed. The measured
window excludes the first full chain traversal. The P1 count samples accepted
`p1ReqFire` edges inside that same window; pipeline work at the boundaries
means it is an acceptance count, not an exact bijection to retired loads.

| Chains | P1 | Accepted P1 | Cycles | Bytes/cycle | IPC |
| --- | --- | ---: | ---: | ---: | ---: |
| 4 | OFF | 0 | 15,371 | 0.8026 | 0.3511 |
| 4 | ON | 3,065 | 15,069 | 0.8186 | 0.3582 |
| 6 | OFF | 0 | 16,336 | 0.7551 | 0.2832 |
| 6 | ON | 2,569 | 16,262 | 0.7586 | 0.2845 |

Each row retired and checked 3,084 measured pointer loads (12,336 bytes).
P1 ON saves 302 cycles / 2.00% throughput for four chains and 74 cycles /
0.46% throughput for six. The result proves P1 launches are nonvacuous for
simple `(An)` chains, but the small throughput change does not support a
large bandwidth claim. The existing indexed-chain control remains separate.

Evidence: `/tmp/codex-agent75-an-chain-{off,on}.log` (both focused specs
passed), `/tmp/codex-agent75-an-chain-fast.log` (`make test-fast`: 404 passed,
zero failed, two ignored). All three runs used the same committed source.
