| muldiv_indexed_mem_src.s -- MUL/DIV memory-source indexed EAs.
|
| Covers the V2 MUL/DIV indexed-source cracks:
|   - MULU.W (d8,An,Xn.W*scale),Dn
|   - MULS.W (d8,PC,Xn.W),Dn
|   - DIVU.W (d8,An,Xn.W*scale),Dn
|   - MULU.L SZ=0 indexed source
|   - MULU.L SZ=1 dual-dst indexed source
|   - DIVU.L SZ=1 64/32 indexed source
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    lea     0x00010000, %a7
    lea     0x00124000, %a0

    | MULU.W (0,A0,D2.W*2),D0.  D0 must be zero-extended before multiply.
    move.w  #7, 4(%a0)
    moveq   #2, %d2
    move.l  #0x12340006, %d0
    .short  0xC0F0, 0x2200
    bvs     _fail
    bmi     _fail
    beq     _fail
    cmp.l   #42, %d0
    bne     _fail

    | MULS.W (4,PC,D2.W),D1.  PC-relative base is the extension word address.
    moveq   #0, %d2
    move.l  #-4, %d1
_pc_muls:
    .short  0xC3FB, 0x2004
    .short  0x6002
_pc_muls_word:
    .word   0xFFFD
_after_pc_muls:
    bvs     _fail
    bmi     _fail
    beq     _fail
    cmp.l   #12, %d1
    bne     _fail

    | DIVU.W (0,A0,D2.W*4),D3.  100 / 5 => quotient 20, remainder 0.
    move.w  #5, 8(%a0)
    moveq   #2, %d2
    move.l  #100, %d3
    .short  0x86F0, 0x2400
    bvs     _fail
    cmp.l   #20, %d3
    bne     _fail

    | MULU.L SZ=0 (0,A0,D2.W*4),D4.
    move.l  #7, 12(%a0)
    moveq   #3, %d2
    move.l  #6, %d4
    .short  0x4C30, 0x4000, 0x2400
    bvs     _fail
    cmp.l   #42, %d4
    bne     _fail

    | MULU.L SZ=1 (0,A0,D2.W*8),D5:D6 -> 0x00000001_00000000.
    move.l  #0x00010000, 16(%a0)
    moveq   #2, %d2
    move.l  #0x00010000, %d6
    moveq   #0, %d5
    .short  0x4C30, 0x6405, 0x2600
    cmp.l   #0x00000000, %d6
    bne     _fail
    cmp.l   #0x00000001, %d5
    bne     _fail

    | DIVU.L SZ=1 (0,A0,D2.W*4),D5:D6.  {0,100} / 4 => q=25, r=0.
    move.l  #4, 20(%a0)
    moveq   #5, %d2
    moveq   #0, %d5
    move.l  #100, %d6
    .short  0x4C70, 0x6405, 0x2400
    cmp.l   #25, %d6
    bne     _fail
    cmp.l   #0, %d5
    bne     _fail

_pass:
    lea     0xFFFF0000, %a1
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a1)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a1
    move.l  #0xDEADBEEF, %d7
    move.l  %d7, (%a1)
_halt_fail:
    bra     _halt_fail
