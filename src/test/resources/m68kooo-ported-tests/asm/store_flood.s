| store_flood.s — Flood the store path with 8 stores + interleaved loads
|
| Issues 8 stores at offsets 0..28 of base A0, with loads to different
| offsets interleaved.  Some load offsets alias previous store offsets
| (store_load_alias hits same physical address), others do not.
|
| Exercises the iq_mem partial-EA disambiguator: the LSU must hold back
| aliasing loads until the older store commits, while letting
| non-aliasing loads proceed.  Committing all stores must preserve
| program-order writes to memory.
|
| Layout at A0 = 0x00101000:
|   [A0+0]  = 0x11111111  (stored then read back)
|   [A0+4]  = 0x22222222
|   [A0+8]  = 0x33333333  (stored then read back)
|   [A0+12] = 0x44444444
|   [A0+16] = 0x55555555  (stored then read back)
|   [A0+20] = 0x66666666
|   [A0+24] = 0x77777777  (stored then read back)
|   [A0+28] = 0x88888888

    .text
    .org 0

_start:
    lea     0x00101000, %a0

    move.l  #0x11111111, %d0
    move.l  #0x22222222, %d1
    move.l  #0x33333333, %d2
    move.l  #0x44444444, %d3
    move.l  #0x55555555, %d4
    move.l  #0x66666666, %d5
    move.l  #0x77777777, %d6
    move.l  #0x88888888, %d7

    | 8 stores in a row — stresses the single-store commit serialisation.
    move.l  %d0, (%a0)
    move.l  %d1, 4(%a0)
    move.l  %d2, 8(%a0)
    move.l  %d3, 12(%a0)
    move.l  %d4, 16(%a0)
    move.l  %d5, 20(%a0)
    move.l  %d6, 24(%a0)
    move.l  %d7, 28(%a0)

    | Interleaved load-alias path: load slot 0 (alias with first store),
    | then slot 8, 16, 24 — all must see the value the store left behind.
    move.l  (%a0),    %d0       | expect 0x11111111
    move.l  8(%a0),   %d2       | expect 0x33333333
    move.l  16(%a0),  %d4       | expect 0x55555555
    move.l  24(%a0),  %d6       | expect 0x77777777

    cmp.l   #0x11111111, %d0
    bne     _fail
    cmp.l   #0x33333333, %d2
    bne     _fail
    cmp.l   #0x55555555, %d4
    bne     _fail
    cmp.l   #0x77777777, %d6
    bne     _fail

    | And the non-aliasing lanes (pre-seeded by prior stores too, just
    | to confirm writes landed at those offsets as well).
    move.l  4(%a0),   %d1
    move.l  12(%a0),  %d3
    move.l  20(%a0),  %d5
    move.l  28(%a0),  %d7

    cmp.l   #0x22222222, %d1
    bne     _fail
    cmp.l   #0x44444444, %d3
    bne     _fail
    cmp.l   #0x66666666, %d5
    bne     _fail
    cmp.l   #0x88888888, %d7
    bne     _fail

    lea     0xFFFF0000, %a1
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a1)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a1
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a1)
_halt_fail:
    bra     _halt_fail
