| split_store_to_load_same_addr.s — misaligned store immediately followed
| by a misaligned load of the SAME address.
|
| The LSU has no store buffer, no load/store disambiguation and no
| store-to-load forwarding; correctness rests on one memory op being in
| flight at a time, and a split op occupies the single LSU FSM for two
| cache beats.  A load issued while the previous split store's second
| beat was still pending — or one that raced the S_ST_GAP/S_ST_REQ2
| re-arm of dc_addr/dc_wdata — would read stale data or clobber the
| pending beat.  This test puts a dependent misaligned load directly
| behind each misaligned store with nothing in between.
|
| Run TWICE over disjoint regions: first with the MMU off (fully
| uncached, straight to the AXI fabric), then with TC.E=1 + copyback
| DTT0 + CACR.DE=1 so the same sequence goes through the D-cache.  The
| uncached pass alone would not cover the configuration the hardware
| actually runs in.
|
| PASS sentinel: 0xC0FFEE00.
| FAIL sentinels 0xDEAD07nn:
|   11/12/13/14  uncached: LONG@1 / LONG@2 / LONG@3 / WORD@3 readback
|   21/22/23/24  cached:   same four
|   1F / 2F      that pass's aligned CONTROL readback failed
|   0B           CONTROL: CACR.DE did not stick — cached pass is void
|   0A           unexpected address error (vec 3)

    .text

    .equ RU, 0x00100000              | uncached region
    .equ RC, 0x00110000              | cached region

_start:
    lea     0x00010000, %a7
    move.l  #_addr_err, 0x0000000C

    | ══════════════ PASS 1: MMU off → uncached ══════════════
    lea     RU, %a0
    bsr     _seed
    | aligned CONTROL
    move.l  0x00(%a0), %d1
    cmp.l   #0xA1A2A3A4, %d1
    bne     _f1F

    move.l  #0x11223344, %d0
    move.l  %d0, 0x01(%a0)
    move.l  0x01(%a0), %d1           | misaligned load, same address
    cmp.l   #0x11223344, %d1
    bne     _f11

    move.l  #0x55667788, %d0
    move.l  %d0, 0x12(%a0)
    move.l  0x12(%a0), %d1
    cmp.l   #0x55667788, %d1
    bne     _f12

    move.l  #0x99AABBCC, %d0
    move.l  %d0, 0x23(%a0)
    move.l  0x23(%a0), %d1
    cmp.l   #0x99AABBCC, %d1
    bne     _f13

    move.w  #0xDEAD, %d0
    move.w  %d0, 0x33(%a0)
    move.w  0x33(%a0), %d1
    and.l   #0xFFFF, %d1
    cmp.l   #0xDEAD, %d1
    bne     _f14

    | ══════════════ PASS 2: D-cache enabled ══════════════
    move.l  #0x4000C000, %d7
    movec   %d7, %itt0
    move.l  #0x0000E020, %d7         | 0x00xxxxxx copyback
    movec   %d7, %dtt0
    move.l  #0xFF00E060, %d7         | sentinel page non-cacheable
    movec   %d7, %dtt1
    move.l  #0x8000, %d7
    movec   %d7, %tc
    move.l  #0x80000000, %d7
    movec   %d7, %cacr
    movec   %cacr, %d6
    cmp.l   %d7, %d6
    bne     _f0B

    lea     RC, %a0
    bsr     _seed
    move.l  0x00(%a0), %d1
    cmp.l   #0xA1A2A3A4, %d1
    bne     _f2F

    move.l  #0x11223344, %d0
    move.l  %d0, 0x01(%a0)
    move.l  0x01(%a0), %d1
    cmp.l   #0x11223344, %d1
    bne     _f21

    move.l  #0x55667788, %d0
    move.l  %d0, 0x12(%a0)
    move.l  0x12(%a0), %d1
    cmp.l   #0x55667788, %d1
    bne     _f22

    move.l  #0x99AABBCC, %d0
    move.l  %d0, 0x23(%a0)
    move.l  0x23(%a0), %d1
    cmp.l   #0x99AABBCC, %d1
    bne     _f23

    move.w  #0xDEAD, %d0
    move.w  %d0, 0x33(%a0)
    move.w  0x33(%a0), %d1
    and.l   #0xFFFF, %d1
    cmp.l   #0xDEAD, %d1
    bne     _f24

    lea     0xFFFF0000, %a2
    move.l  #0xC0FFEE00, (%a2)
_halt:  bra _halt

| Seed four 8-byte slots at 0x00/0x10/0x20/0x30 off A0.
_seed:
    move.l  #0xA1A2A3A4, 0x00(%a0)
    move.l  #0xB1B2B3B4, 0x04(%a0)
    move.l  #0xA1A2A3A4, 0x10(%a0)
    move.l  #0xB1B2B3B4, 0x14(%a0)
    move.l  #0xA1A2A3A4, 0x20(%a0)
    move.l  #0xB1B2B3B4, 0x24(%a0)
    move.l  #0xA1A2A3A4, 0x30(%a0)
    move.l  #0xB1B2B3B4, 0x34(%a0)
    rts

_f11:   move.l #0xDEAD0711, 0xFFFF0000
_h11:   bra _h11
_f12:   move.l #0xDEAD0712, 0xFFFF0000
_h12:   bra _h12
_f13:   move.l #0xDEAD0713, 0xFFFF0000
_h13:   bra _h13
_f14:   move.l #0xDEAD0714, 0xFFFF0000
_h14:   bra _h14
_f1F:   move.l #0xDEAD071F, 0xFFFF0000
_h1F:   bra _h1F
_f21:   move.l #0xDEAD0721, 0xFFFF0000
_h21:   bra _h21
_f22:   move.l #0xDEAD0722, 0xFFFF0000
_h22:   bra _h22
_f23:   move.l #0xDEAD0723, 0xFFFF0000
_h23:   bra _h23
_f24:   move.l #0xDEAD0724, 0xFFFF0000
_h24:   bra _h24
_f2F:   move.l #0xDEAD072F, 0xFFFF0000
_h2F:   bra _h2F
_f0B:   move.l #0xDEAD070B, 0xFFFF0000
_h0B:   bra _h0B
_addr_err:
    move.l  #0xDEAD070A, 0xFFFF0000
_h0A:   bra _h0A
