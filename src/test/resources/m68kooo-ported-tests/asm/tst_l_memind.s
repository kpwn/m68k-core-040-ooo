| tst_l_memind.s — Task #208 (C2)
|
| Directed test for TST.L with full-format memory-indirect destination.
| TST reads the memind target and writes NZVC — no store-back.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    lea     0x00115f00, %a7

    | -- No-index: TST.L ([32,A4])
    |    EA compute:  inner_ptr = [A4 + 32],  final EA = inner_ptr + od(=0).
    | Stage the memory slots so the load path is safe.
    lea     0x00115200, %a4
    lea     0x00115220, %a0
    move.l  #0x00115400, (%a0)       | [A4+32] = pointer 0x00115400
    lea     0x00115400, %a0
    move.l  #0x80000001, (%a0)       | [0x00115400] = target value
    | TST.L opword = 0x4AB4; ext1 bit8=1 BS=0 IS=1 bd=W I/IS=100 = 0x0164.
    .word   0x4AB4, 0x0164, 0x0020
    | Value 0x80000001 sets N=1 Z=0.
    bpl     _fail1
    beq     _fail1

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a0)
_halt:
    bra     _halt

_fail1:
    move.l  #0xDEAD0001, %d7

_fail:
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
_halt_fail:
    bra     _halt_fail
