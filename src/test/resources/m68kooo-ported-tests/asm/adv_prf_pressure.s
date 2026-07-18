| adv_prf_pressure.s — Many back-to-back renames to pressure the 48-phys-reg pool
|
| ASSUMPTION TESTED (rat.v: 48 physical int regs; committed mapping
|   must never stall dispatch when committed state has free phys):
|
|   48 phys regs = 17 reset-committed (D0-D7, A0-A7, TMP0) + 31 free.
|   With ROB = 32 entries, the "in-flight window" can consume nearly
|   all free phys — each in-flight uop with has_dst takes 1 phys.
|
|   If rename ever dispatches when alloc_ok == 0, we either overwrite a
|   live mapping or hang.  The core's dispatch gate (alloc_ok_for_uop)
|   should prevent this.
|
|   Conversely, if dispatch STALLS even when phys are free (e.g.,
|   free_bm has a bit set but first_free scan fails), we livelock.
|
| ATTACK:
|   32 MOVEQ instructions each to a distinct Dn (cycling through D0-D7,
|   then re-writing D0..D7 again).  Every one creates a new phys reg
|   allocation, forcing the rename to recycle through the free pool
|   multiple times.  Each one is independent so the ROB can retire
|   them in order at full rate.
|
| PASS: D7 at end matches the last MOVEQ.  Both models in sync.
|
| This is a regression gate: catches a free-list popcount bug or a
|   committed_busy accounting error that would drop a phys on recycle.

    .text
    .org 0

_start:
    lea     0x00020000, %a7

    moveq   #0, %d0
    moveq   #1, %d1
    moveq   #2, %d2
    moveq   #3, %d3
    moveq   #4, %d4
    moveq   #5, %d5
    moveq   #6, %d6
    moveq   #7, %d7
    moveq   #8, %d0          | rename-cycle through D0
    moveq   #9, %d1
    moveq   #10, %d2
    moveq   #11, %d3
    moveq   #12, %d4
    moveq   #13, %d5
    moveq   #14, %d6
    moveq   #15, %d7
    moveq   #16, %d0
    moveq   #17, %d1
    moveq   #18, %d2
    moveq   #19, %d3
    moveq   #20, %d4
    moveq   #21, %d5
    moveq   #22, %d6
    moveq   #23, %d7
    moveq   #24, %d0
    moveq   #25, %d1
    moveq   #26, %d2
    moveq   #27, %d3
    moveq   #28, %d4
    moveq   #29, %d5
    moveq   #30, %d6
    moveq   #31, %d7         | 32 renames, last writes D7=31

    cmp.l   #31, %d7
    bne     _fail

    | Also check D0..D6 hold the final cycle's values
    cmp.l   #24, %d0
    bne     _fail
    cmp.l   #25, %d1
    bne     _fail
    cmp.l   #26, %d2
    bne     _fail
    cmp.l   #27, %d3
    bne     _fail
    cmp.l   #28, %d4
    bne     _fail
    cmp.l   #29, %d5
    bne     _fail
    cmp.l   #30, %d6
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
