| add_w_d8_an_xn_src.s -- ADD.W (d8,An,Xn),Dn (V2 indexed-src) + flags.
|
| Task #222 (E6).  Tests ADD.W from a brief-indexed source — preserves
| upper Dn bits and sets V/C correctly on word overflow.
|
| Flag asserts use the ADD-set CCR before any other CC-writing op
| clobbers it.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | ADD.W into Dn with non-overlapping upper bits.
    | EA = A0 + 8 + 4*2 = A0+16.
    | mem word = 0x0001, D0 low = 0x7FFF + 0x0001 = 0x8000 → V=1, N=1.
    lea     0x00135000, %a0
    lea     0x00135010, %a6
    move.w  #0x0001, (%a6)
    moveq   #4, %d3
    move.l  #0xCAFE7FFF, %d0
    .word   0xd070, 0x3208       | add.w (8,A0,D3.W*2),D0
    | Check flags before destroying CCR.
    bvc     _fail                | V must be 1
    bpl     _fail                | N must be 1
    beq     _fail                | Z must be 0
    | Now check value (this clobbers CCR).
    cmp.l   #0xCAFE8000, %d0
    bne     _fail

    | ADD.W with carry-out — D1 low = 0xFFFF + 0x0001 = 0x0000 → C=1, Z=1.
    | EA = A1 + 8 + 4*2 = A1+16.
    lea     0x00135100, %a1
    lea     0x00135110, %a6
    move.w  #0x0001, (%a6)
    moveq   #4, %d4
    move.l  #0xBABEFFFF, %d1
    .word   0xd271, 0x4208       | add.w (8,A1,D4.W*2),D1
    | Check flags first.
    bcc     _fail                | C must be 1
    bne     _fail                | Z must be 1
    | Now value.
    cmp.l   #0xBABE0000, %d1
    bne     _fail

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
_halt_fail:
    bra     _halt_fail
