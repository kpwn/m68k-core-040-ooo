| store_miss_dirty_check.s — store-miss fill must merge store and dirty line.
|
| The test enables copyback D-cache, fills all four ways of one set with
| clean lines by loading four distinct tags, then stores to a fifth tag in
| that same set.  The following load must observe the stored value, not the
| DRAM fill stripe.  A second set repeats the sequence with four consecutive
| stores to the same line before loading it back.

    .text
    .org 0

_start:
    lea     0x00020000, %a7

    | DTT0: 0x00xxxxxx passthrough, cacheable copyback.
    move.l  #0x000FE020, %d7
    movec   %d7, %dtt0

    | ITT0: 0x4xxxxxxx test-code passthrough, cacheable copyback.
    move.l  #0x400FE020, %d7
    movec   %d7, %itt0

    | DTT1: 0xFFxxxxxx passthrough, non-cacheable for PASS/FAIL sentinel.
    move.l  #0xFF00E060, %d7
    movec   %d7, %dtt1

    | Enable MMU and D-cache.
    move.l  #0x8000, %d7
    movec   %d7, %tc
    move.l  #0x80000000, %d7
    movec   %d7, %cacr

    | Case 1: fill 4 clean ways in set 0, then store-miss a fifth tag.
    lea     0x00200000, %a0
    lea     0x00201000, %a1
    lea     0x00202000, %a2
    lea     0x00203000, %a3
    lea     0x00204000, %a4

    move.l  (%a0), %d0
    move.l  (%a1), %d0
    move.l  (%a2), %d0
    move.l  (%a3), %d0

    move.l  #0x13579BDF, %d1
    move.l  %d1, (%a4)
    move.l  (%a4), %d2
    cmp.l   %d1, %d2
    bne     _fail

    | Case 2: fill 4 clean ways in set 1, then 4 stores to one fifth tag.
    lea     0x00200020, %a0
    lea     0x00201020, %a1
    lea     0x00202020, %a2
    lea     0x00203020, %a3
    lea     0x00204020, %a4

    move.l  (%a0), %d0
    move.l  (%a1), %d0
    move.l  (%a2), %d0
    move.l  (%a3), %d0

    move.l  #0x11111111, %d1
    move.l  %d1, (%a4)
    move.l  #0x22222222, %d1
    move.l  %d1, (%a4)
    move.l  #0x33333333, %d1
    move.l  %d1, (%a4)
    move.l  #0x89ABCDEF, %d1
    move.l  %d1, (%a4)
    move.l  (%a4), %d2
    cmp.l   %d1, %d2
    bne     _fail

    lea     0xFFFF0000, %a6
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a6)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a6
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a6)
_halt_fail:
    bra     _halt_fail
