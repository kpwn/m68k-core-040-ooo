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
