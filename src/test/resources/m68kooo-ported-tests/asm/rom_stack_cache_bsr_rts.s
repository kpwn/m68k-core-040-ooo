| rom_stack_cache_bsr_rts.s -- ROM-shaped BSR/RTS over a warmed low stack line
|
| The Q700 frontier had MOVEM-style stack pops warm the cache line around
| 0x0000fec8/0x0000fecc before BSR pushed a split LONG return address from
| A7=0x0000fece to 0x0000feca.  Later RTS read the two return-address words
| swapped.  This keeps that integrated shape covered without ROM harness state.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | Seed the low stack cache line with nontrivial data.  The BSR return
    | address will later overwrite bytes 0xfeca..0xfecd.
    movea.l #0x0000fec0, %a0
    move.l  #0x10213243, (%a0)
    move.l  #0x54657687, 4(%a0)
    move.l  #0x98a9bacb, 8(%a0)
    move.l  #0xdcedfe0f, 12(%a0)

    | Warm the surrounding line via a MOVEM stack-pop form.
    movea.l #0x0000fec0, %a7
    movem.l (%a7)+, %d0-%d3
    cmpa.l  #0x0000fed0, %a7
    bne     _fail
    cmp.l   #0x10213243, %d0
    bne     _fail
    cmp.l   #0x54657687, %d1
    bne     _fail
    cmp.l   #0x98a9bacb, %d2
    bne     _fail
    cmp.l   #0xdcedfe0f, %d3
    bne     _fail

    | ROM frontier shape: A7=0xfece, BSR pushes return PC at 0xfeca.
    movea.l #0x0000fece, %a7
    bsr     _sub
_after_bsr:
    cmpa.l  #0x0000fece, %a7
    bne     _fail

    | Verify the split return address exactly as big-endian stack words.
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

    | Neighboring low-stack words must not be smeared by the split write.
    move.w  -2(%a0), %d6
    cmp.w   #0x98a9, %d6
    bne     _fail
    move.w  4(%a0), %d6
    cmp.w   #0xfe0f, %d6
    bne     _fail

_pass:
    lea     0xFFFF0000, %a1
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a1)
_halt:
    bra     _halt

_sub:
    | RTS must consume the split long from the warmed cache line.
    rts

_fail:
    lea     0xFFFF0000, %a1
    move.l  #0xDEADBEEF, %d7
    move.l  %d7, (%a1)
_halt_fail:
    bra     _halt_fail
