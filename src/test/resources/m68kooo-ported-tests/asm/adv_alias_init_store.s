| adv_alias_init_store.s — Second store-then-load to the SAME address races on RTL
|
| ASSUMPTION TESTED (iq_mem.v disambiguation + lsu.v S_ST_BUF hold):
|   A STORE via (An) immediately followed by a LOAD via the SAME (An)
|   must observe the stored value.  Repeating the pattern must continue
|   to work — the pipeline machinery that keeps the first pair ordered
|   must RESET cleanly for the second pair.
|
|   iq_mem's alias bitmap relies on `e_pbase[j] == e_pbase[k] &&
|   e_disp[j] == e_disp[k]` — both matched here (both via A2, both
|   disp 0).  lsu's S_ST_BUF state gates iss_ready until the prior
|   store retires, so the second store shouldn't dispatch until the
|   first completes.
|
|   BUT: after the second STORE retires its commit_store_en fires to
|   drain the first store... and nearly simultaneously the LSU's
|   iss_ready window opens just as the dispatched LOAD presents.  A
|   bug in the S_ST_BUF ↔ S_ST_WAIT ↔ S_IDLE handoff can leak an
|   un-retired second-store's data address to a premature LOAD.
|
| OBSERVED FAILURE (seq 2 BNE taken):
|   seq 1 works:  store #CAFEBABE, load back, cmp PASSES.
|   seq 2 breaks: store #12345678, load back, D1 != #12345678 → BNE _fail.
|
|   RTL commits 14 μops before FAIL.  Musashi clean-passes the whole thing.
|
| DIVERGENCE CLASS: store-buffer retire vs load-issue race (LSU
|   state-machine bug).

    .text
    .org 0

_start:
    lea     0x00020000, %a7
    lea     0x00015000, %a2

    move.l  #0xCAFEBABE, (%a2)
    move.l  (%a2), %d0
    cmp.l   #0xCAFEBABE, %d0
    bne     _fail

    | Same (%a2); different value.
    move.l  #0x12345678, (%a2)
    move.l  (%a2), %d1
    cmp.l   #0x12345678, %d1
    bne     _fail

    move.l  #0, (%a2)
    move.l  (%a2), %d2
    cmp.l   #0, %d2
    bne     _fail

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
_halt_f:
    bra     _halt_f
