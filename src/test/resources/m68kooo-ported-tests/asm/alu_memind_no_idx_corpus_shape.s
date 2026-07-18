| alu_memind_no_idx_corpus_shape.s -- regression for the fuzz-corpus
| ALU memind no-idx shapes (emit_alu_memind_src_dn / dst_reg / dst_imm
| in tools/fuzz/gen_program.py).
|
| The original generator emitted `0x0164, 0xfff0` while staging the
| indirect pointer at A4+0x20 — interpreting the trailing 0xfff0 as an
| OD that doesn't exist (I/IS=100 with IS=1 forces OD=null), so the
| inner LOAD pulled an uninitialized word and the final EA landed on
| 0xFFFFFFFF, faulting on the testbench AXI (SLVERR) but tolerated
| silently by Musashi.  The fault loop on the bus-error vector (also
| reading 0xFFFFFFFF from default-0xFF RAM) drove the fuzz seed into a
| TIMEOUT cluster.  Fix: bd=0x0020 to match the staged pointer.
|
| This test pins the FIXED layout — bd matches the staged pointer
| location — and exercises ADD.L src_dn, ADD.L dst_reg, ANDI.L dst_imm
| once each, all hitting data via the no-idx memind chain.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    lea     0x00115f00, %a7

    | -- ADD.L ([0x20,A4]),D0  (src_dn shape)
    lea     0x00100000, %a4         | A4 = 0x00100000
    lea     0x00100020, %a6         | A6 = A4 + 0x20 (pointer slot)
    lea     0x00100200, %a5         | A5 = data target
    move.l  %a5, (%a6)              | *(A4+0x20) = 0x00100200
    move.l  #0x11111111, (%a5)      | *data = 0x11111111
    move.l  #0x22222222, %d0        | D0 = 0x22222222
    | opword 0xD0B4 (ADD.L <ea>,D0), ext1=0x0164, bd=0x0020.
    .word   0xD0B4, 0x0164, 0x0020
    cmp.l   #0x33333333, %d0
    bne     _fail1

    | -- ADD.L D1,([0x20,A4])  (dst_reg shape)
    lea     0x00100040, %a4         | A4 = 0x00100040 (fresh slot)
    lea     0x00100060, %a6         | A6 = A4 + 0x20
    lea     0x00100240, %a5         | A5 = data target
    move.l  %a5, (%a6)              | *(A4+0x20) = 0x00100240
    move.l  #0x44444444, (%a5)      | *data = 0x44444444
    move.l  #0x11111111, %d1        | D1 = 0x11111111
    | opword 0xD3B4 (ADD.L D1,<ea>), ext1=0x0164, bd=0x0020.
    .word   0xD3B4, 0x0164, 0x0020
    move.l  (%a5), %d2
    cmp.l   #0x55555555, %d2
    bne     _fail2

    | -- ANDI.L #0xFFFF0000,([0x20,A4])  (dst_imm shape)
    lea     0x00100080, %a4         | A4 = 0x00100080 (fresh slot)
    lea     0x001000a0, %a6         | A6 = A4 + 0x20
    lea     0x00100280, %a5         | A5 = data target
    move.l  %a5, (%a6)              | *(A4+0x20) = 0x00100280
    move.l  #0x12345678, (%a5)      | *data = 0x12345678
    | opword 0x02B4 (ANDI.L #imm,<ea>), imm.L=0xFFFF0000, ext1=0x0164, bd=0x0020.
    .word   0x02B4, 0xFFFF, 0x0000, 0x0164, 0x0020
    move.l  (%a5), %d3
    cmp.l   #0x12340000, %d3
    bne     _fail3

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a0)
_halt:
    bra     _halt

_fail1:
    move.l  #0xDEAD0001, %d7
    bra     _fail
_fail2:
    move.l  #0xDEAD0002, %d7
    bra     _fail
_fail3:
    move.l  #0xDEAD0003, %d7
_fail:
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
_halt_fail:
    bra     _halt_fail
