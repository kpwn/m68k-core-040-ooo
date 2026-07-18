| adv_movem_an_in_list.s — MOVEM with An also in the register list
|
| ASSUMPTION TESTED (decode.v:1529-1536):
|   "An-in-list edge case: if An itself is in the list, PRM says predec
|    pushes the ORIGINAL An; postinc leaves An with the loaded value.
|    Our simple 'update An first/last' scheme pushes the NEW An in predec
|    (wrong!) and the post-increment clobbers the loaded value in postinc.
|    Real Mac ROM code almost never uses An as its own base register for
|    MOVEM (the test doesn't either); a future pass can add the
|    corner-case handling."
|
| ATTACK:
|   MOVEM.L with A7 as the stack base and A7 also in the regset.
|   Both predec and postinc variants are tested.
|
| PRM semantics (68040 manual):
|   Predec MOVEM.L {A7,...}, -(A7):
|       The ORIGINAL (pre-decrement) A7 must be pushed.
|   Postinc MOVEM.L (A7)+, {A7,...}:
|       A7 must end up holding the LOADED value (not the post-inc value).
|
| FAILURE MODE:
|   RTL and Musashi will disagree on the value pushed (or the final A7)
|   because the RTL uses the post-decrement A7 in predec and the
|   post-increment A7 in postinc.
|
| PASS SENTINEL: 0xC0FFEE00 only after checking the pushed values.

    .text
    .org 0

_start:
    lea     0x00020000, %a7         | supervisor stack

    | ── predec MOVEM with A7 in list ──
    | Prep a known pattern in A0..A7
    move.l  #0x1AAAAAA1, %a0
    move.l  #0x2BBBBBB2, %a1
    move.l  #0x3CCCCCC3, %a2
    | The predec store: pushes A7 (among others).  A7 starts at 0x00020000;
    | after the 4 longs the new A7 is 0x0001FFF0.  We want to check what
    | was stored into the slot corresponding to A7.
    movem.l %a0-%a2/%a7, -(%a7)      | push A2, A1, A0, A7 (8 regs worth but only 4 set)

    | After the push, A7 = 0x0001FFF0.  The slots (from low to high):
    |   [A7+0] = A0 = 0x1AAAAAA1
    |   [A7+4] = A1 = 0x2BBBBBB2
    |   [A7+8] = A2 = 0x3CCCCCC3
    |   [A7+12] = ??? (A7-in-list slot — pre-decrement is 0x20000, post is 0x1FFF0)
    |
    | Load each back into D0..D3 for inspection.
    move.l  (%a7)+, %d0
    move.l  (%a7)+, %d1
    move.l  (%a7)+, %d2
    move.l  (%a7)+, %d3             | D3 = the pushed-A7 slot

    cmp.l   #0x1AAAAAA1, %d0
    bne     _fail
    cmp.l   #0x2BBBBBB2, %d1
    bne     _fail
    cmp.l   #0x3CCCCCC3, %d2
    bne     _fail
    cmp.l   #0x00020000, %d3
    bne     _fail

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d4
    move.l  %d4, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d4
    move.l  %d4, (%a0)
_halt_fail:
    bra     _halt_fail
