| q700_rom_unaligned_byte_test.s — Q700 boot ROM stage-3 (byte stores).
|
| Replicates the ROM's stage-3 at PC 0x408476FE: byte store of 0x00 at
| byte offset 0..4 then movem.l readback. All byte stores are aligned at
| the byte level; the test exercises byte-lane mux + readback via movem.l.

    .text
    .org 0

_start:
    lea     0x00010000, %a7
    lea     0x00100000, %a0
    move.l  #0x00007FFF, %d6
    moveq   #0, %d0          | byte to write = 0x00
    moveq   #0, %d1

_loop:
    move.l  #0x88888888, (%a0)
    move.l  #0x88888888, 4(%a0)
    move.b  %d0, (0, %a0, %d1:w)
    movem.l (%a0), %d2-%d3

    cmp.l   #0, %d1
    bne     _ck1
    cmp.l   #0x00888888, %d2
    bne     _fail
    cmp.l   #0x88888888, %d3
    bne     _fail
    bra     _pass_iter
_ck1:
    cmp.l   #1, %d1
    bne     _ck2
    cmp.l   #0x88008888, %d2
    bne     _fail
    cmp.l   #0x88888888, %d3
    bne     _fail
    bra     _pass_iter
_ck2:
    cmp.l   #2, %d1
    bne     _ck3
    cmp.l   #0x88880088, %d2
    bne     _fail
    cmp.l   #0x88888888, %d3
    bne     _fail
    bra     _pass_iter
_ck3:
    cmp.l   #3, %d1
    bne     _ck4
    cmp.l   #0x88888800, %d2
    bne     _fail
    cmp.l   #0x88888888, %d3
    bne     _fail
    bra     _pass_iter
_ck4:
    cmp.l   #0x88888888, %d2
    bne     _fail
    cmp.l   #0x00888888, %d3
    bne     _fail

_pass_iter:
    bclr    %d1, %d6
    addq.l  #1, %d1
    cmp.b   #4, %d1
    bls     _loop

    and.l   #0x0000001F, %d6
    tst.l   %d6
    bne     _fail

    lea     0xFFFF0000, %a1
    move.l  #0xC0FFEE00, %d1
    move.l  %d1, (%a1)
_halt:
    bra     _halt
_fail:
    lea     0xFFFF0000, %a1
    move.l  #0xBADBAD00, %d1
    move.l  %d1, (%a1)
_fhlt:
    bra     _fhlt
