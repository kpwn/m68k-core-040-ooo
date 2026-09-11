| ls_store_slow_data_forward.s — can a load bypass an older store whose DATA is still
| being produced by a long-latency op?
|
| The store's data here GENUINELY depends on a DIV (~70 cycles on this core), so the store
| cannot issue until the divide retires its result. The SQ's `olderStore` barrier gates
| only on RESIDENT entries, so if nothing else orders them, the younger load can query an
| SQ that does not yet contain the store.
|
| PASS 0xC0FFEE00 — the load saw the store.
| 0xDEAD0F01 — the load read STALE memory: a GENERAL store->load ordering hole.

    .text
    .org 0
    .equ PASS_SENT, 0xFFFF0000
    .equ BUF,       0x00020000

_start:
    lea     0x00010000, %a7
    lea     BUF, %a1
    move.l  #0xDEADBEEF, (%a1)          | poison

    move.l  #0x00BC614E, %d0
    divu.w  #7, %d0                     | SLOW; D0 is the divide's result
    move.l  %d0, (%a1)                  | store DEPENDS on the divide
    move.l  (%a1), %d1                  | load immediately after
    cmp.l   %d0, %d1                    | must equal what was stored
    bne     _f01

    move.l  #0xC0FFEE00, %d2
    bra     _report
_f01:
    move.l  #0xDEAD0F01, %d2
_report:
    lea     PASS_SENT, %a0
    move.l  %d2, (%a0)
_halt:
    bra     _halt
