| abs_load_after_store.s — Absolute-addressed load after any store
|
| SUSPECTED CORE BUG: MOVE.L (xxx).L, Dn hangs if any store is
| currently buffered (waiting on commit).  Minimal repro:
|
|   lea     0x00104000, %a0
|   move.l  #0xAAAA0000, %d0
|   move.l  %d0, (%a0)          | store via (An)
|   move.l  0x00108000, %d1     | absolute load — hangs here
|
| Same pattern with the load routed through an An-indirect (%a1)
| completes.  Root cause seems to be that decode emits MOVE.L
| (xxx).L,Dn with has_src_a=0 but `disp_phys_base=rat_psa` still
| points at arch D0's renamed phys reg.  That phys reg is in-flight
| (written by the preceding MOVE.L #imm,%d0) and the LSU reads
| prf[d0_phys_new] stale, making ea_new = stale_D0 + abs_addr (wrong
| address, and possibly base_rdy vs CDB wake races the disambiguator
| into an eternal load_blocked=1 state).
|
| Workaround: use LEA to load the absolute into An, then (An),Dn.
|
| This test leaves the core hanging (timeout).  Once the decode/
| LSU fix is in, it becomes a passing stress test for the
| absolute-addressing store/load disambiguation.

    .text
    .org 0

_start:
    | Poke a sentinel value into both the store and load addresses so
    | whichever path the core picks, the load MUST see the stored
    | value (0xAAAA0000) — not stale memory.
    lea     0x00104000, %a0
    move.l  #0xAAAA0000, %d0
    move.l  %d0, (%a0)          | store via (A0)
    move.l  0x00104000, %d1     | absolute long load — should see store

    cmp.l   #0xAAAA0000, %d1
    bne     _fail

    lea     0xFFFF0000, %a1
    move.l  #0xC0FFEE00, %d2
    move.l  %d2, (%a1)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a1
    move.l  #0xDEADBEEF, %d2
    move.l  %d2, (%a1)
_halt_fail:
    bra     _halt_fail
