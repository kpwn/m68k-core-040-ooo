| phys_zero_abs_store.s -- pinned PHYS_ZERO_TAG must stay zero.
|
| Directed regression for seed 1419: a zero-idiom D-register alias to
| PHYS_ZERO_TAG is remapped, then the following allocation used to recycle
| phys 16 and let a normal CDB write poison absolute-address LSU bases.

    .text
    .org 0

_start:
    lea     0x0010fdb4, %a0
    lea     0x000ffdb4, %a1

    move.l  #0x11111111, (%a0)
    move.l  #0x22222222, (%a1)

    | D4 aliases to PHYS_ZERO_TAG, then is remapped.  Before the pinned
    | free-list fix, the old phys 16 mapping could become allocatable.
    moveq   #0, %d4
    move.l  #0xffff0000, %d4

    | With the old bug this immediate write can land in phys 16, making
    | absolute-long stores use base 0xffff0000 instead of base 0.
    move.l  #0xffff0000, %d6

    move.l  #0x12345678, %d0
    move.l  %d0, 0x0010fdb4

    move.l  (%a0), %d1
    cmp.l   #0x12345678, %d1
    bne     _fail_intended

    move.l  (%a1), %d2
    cmp.l   #0x22222222, %d2
    bne     _fail_alias

    | Also verify zero-idiom data reads cannot observe a poisoned phys 16.
    moveq   #0, %d5
    move.l  %d5, 0x0010fdbc
    move.l  0x0010fdbc, %d3
    cmp.l   #0, %d3
    bne     _fail_zero_data

_pass:
    lea     0xffff0000, %a2
    move.l  #0xc0ffee00, %d7
    move.l  %d7, (%a2)
_halt:
    bra     _halt

_fail_intended:
    move.l  #0xdead1419, %d7
    bra     _fail

_fail_alias:
    move.l  #0xdeadfdb4, %d7
    bra     _fail

_fail_zero_data:
    move.l  #0xdead0000, %d7

_fail:
    lea     0xffff0000, %a2
    move.l  %d7, (%a2)
_halt_fail:
    bra     _halt_fail
