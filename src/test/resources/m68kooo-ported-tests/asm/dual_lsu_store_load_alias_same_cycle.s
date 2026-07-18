| dual_lsu_store_load_alias_same_cycle.s — Alias hazard corner
|
| Purpose: directed corner for task #217 dual-LSU lane.  A STORE
|   and a LOAD to the SAME address issued in close succession must
|   serialise so the LOAD sees the value the STORE wrote — or the
|   value from before the STORE, depending on program order.  On a
|   dual-LSU core the iq_mem alias check must NOT let lane B's
|   LOAD bypass a younger STORE in lane A.
|
| Sequence:
|   (1) Store 0xAAAAAAAA to [A0]    ← older
|   (2) Store 0xBBBBBBBB to [A0]    ← younger than (1), same addr
|   (3) Load  [A0] → D1             ← program order reads 0xBBBBBBBB
|
|   D1 MUST equal 0xBBBBBBBB.  If iq_mem lets the LOAD jump ahead
|   of the younger STORE, D1 will be stale (0xAAAAAAAA or garbage)
|   and the test fails.
|
| Also exercises same-cycle STORE+LOAD issue: if the dual-select
|   picks a younger LOAD on lane B while lane A issues an older
|   STORE to the aliasing address, the LOAD must be held.

    .text
    .org 0

_start:
    | Seed memory with a known "before" value to detect stale reads.
    lea     0x00100000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)

    | Drain to commit so the seed is visible.
    nop
    nop
    nop
    nop

    | (1) Older store
    move.l  #0xAAAAAAAA, %d0
    move.l  %d0, (%a0)

    | (2) Younger store to SAME address
    move.l  #0xBBBBBBBB, %d0
    move.l  %d0, (%a0)

    | (3) Load from same address — must see the YOUNGER store's data.
    move.l  (%a0), %d1

    | D1 must be 0xBBBBBBBB.
    move.l  #0xBBBBBBBB, %d2
    cmp.l   %d2, %d1
    bne     _fail

    | PASS
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADDEAD, %d0
    move.l  %d0, (%a0)

_halt:
    stop    #0x2700
    bra     _halt
