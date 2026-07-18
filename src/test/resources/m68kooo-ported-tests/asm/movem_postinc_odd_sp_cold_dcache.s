| movem_postinc_odd_sp_cold_dcache.s -- cold split MOVEM stack-pop.
|
| Live wedge shape is MOVEM.L (SP)+,D0-D3/A0-A3 from SP%4==2, reading
| old RAM-test data.  Existing directed tests wrote the stack line just
| before the pop, making it hot/dirty in D-cache.  This variant enables
| cacheable RAM, plants data, writes it back, invalidates D-cache, then
| runs the ROM-shaped MOVEM from a cold line.
|
| PASS: 0xC0FFEE00 at 0xFFFF0000.

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000
    .equ PASS_VAL,  0xC0FFEE00

_start:
    lea     0x00030000, %a7

    | Make low RAM and ROM cacheable, sentinel MMIO-ish space non-cacheable.
    move.l  #0x000FE020, %d7
    movec   %d7, %dtt0
    move.l  #0x400FE020, %d7
    movec   %d7, %itt0
    move.l  #0xFF00E060, %d7
    movec   %d7, %dtt1
    move.l  #0x8000, %d7
    movec   %d7, %tc
    move.l  #0x80000000, %d7
    movec   %d7, %cacr

    | Plant the exact aligned words around live SP=0x001fdf2e.
    move.l  #0x00000040, 0x001fdf20
    move.l  #0x408f6614, 0x001fdf24
    move.l  #0x408059d4, 0x001fdf28
    move.l  #0x40800000, 0x001fdf2c
    move.l  #0x09c92700, 0x001fdf30
    move.l  #0x40809a0a, 0x001fdf34
    move.l  #0x001fe006, 0x001fdf38
    move.l  #0x408f6610, 0x001fdf3c
    move.l  #0x07000006, 0x001fdf40
    move.l  #0x01010101, 0x001fdf44
    move.l  #0x40806c90, 0x001fdf48
    move.l  #0x27044080, 0x001fdf4c
    move.l  #0x40806d36, 0x001fdf50
    move.l  #0x20040000, 0x001fdf54
    move.l  #0x00004080, 0x001fdf58
    move.l  #0x6c9e0000, 0x001fdf5c

    cpusha  %dc
    cinva   %dc

    move.l  #0x001fdf2e, %a7
    movem.l (%a7)+, %d0-%d3/%a0-%a3

    | Eight long loads should advance SP by 32 bytes.
    cmp.l   #0x001fdf4e, %a7
    bne     _fail

    lea     PASS_SENT, %a0
    move.l  #PASS_VAL, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_fail:
    lea     PASS_SENT, %a0
    move.l  #0xDEAD0001, %d0
    move.l  %d0, (%a0)
    bra     _halt
