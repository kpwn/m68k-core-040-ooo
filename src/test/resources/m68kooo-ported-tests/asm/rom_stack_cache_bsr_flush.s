| rom_stack_cache_bsr_flush.s -- branch flush immediately before low-stack BSR
|
| Sibling for rom_stack_cache_bsr_rts.s.  It warms the same low stack line with
| a MOVEM postincrement pop, then takes a cold BEQ so wrong-path A7 writes are
| squashed immediately before the BSR push at 0x0000feca.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    movea.l #0x0000fec0, %a0
    move.l  #0x13579bdf, (%a0)
    move.l  #0x2468ace0, 4(%a0)
    move.l  #0xcafebeef, 8(%a0)
    move.l  #0x0ddba11a, 12(%a0)

    movea.l #0x0000fec0, %a7
    movem.l (%a7)+, %d0-%d3
    cmpa.l  #0x0000fed0, %a7
    bne     _fail

    moveq   #0, %d7
    cmp.l   #0, %d7
    beq     _call_after_flush

    | Wrong path: these A7 updates and store must be squashed.
    adda.l  #0x40, %a7
    move.l  #0xbad5bad5, (%a7)
    bra     _fail

_call_after_flush:
    movea.l #0x0000fece, %a7
    bsr     _sub
_after_bsr:
    cmpa.l  #0x0000fece, %a7
    bne     _fail

    move.l  #_after_bsr, %d4
    move.l  %d4, %d5
    swap    %d5
    movea.l #0x0000feca, %a0
    cmp.w   (%a0), %d5
    bne     _fail
    cmp.w   2(%a0), %d4
    bne     _fail
    move.l  (%a0), %d6
    cmp.l   %d4, %d6
    bne     _fail

    move.w  -2(%a0), %d6
    cmp.w   #0xcafe, %d6
    bne     _fail
    move.w  4(%a0), %d6
    cmp.w   #0xa11a, %d6
    bne     _fail

_pass:
    lea     0xFFFF0000, %a1
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a1)
_halt:
    bra     _halt

_sub:
    rts

_fail:
    lea     0xFFFF0000, %a1
    move.l  #0xDEADBEEF, %d7
    move.l  %d7, (%a1)
_halt_fail:
    bra     _halt_fail
