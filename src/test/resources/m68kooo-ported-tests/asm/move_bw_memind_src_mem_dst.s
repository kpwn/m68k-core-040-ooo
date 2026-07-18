| move_bw_memind_src_mem_dst.s — Task #207 (C1)
|
| .B and .W variants of memind-src -> simple mem-dst.
| No-index shape (I/IS=100, IS=1) in both cases.

    .text
    .org 0

_start:
    | Seed src memory.
    lea     0x00114020, %a6
    move.l  #0x00114200, (%a6)
    lea     0x00114200, %a6
    move.l  #0xCAFEBABE, (%a6)

    | Seed dst slots with known pattern so partial writes show.
    lea     0x00114300, %a6
    move.l  #0x11111111, (%a6)
    lea     0x00114400, %a6
    move.l  #0x22222222, (%a6)

    lea     0x00114000, %a1
    lea     0x00114300, %a2
    lea     0x00114400, %a3

    | MOVE.B ([+32,A1]),(A2)  — byte at [0x00114200] = 0xCA, stored
    |                            at [0x00114300] (byte write, leaves
    |                            rest of the long intact)
    .word   0x14B1, 0x0164, 0x0020

    | Expected at 0x00114300: 0xCA11_1111 (byte write at [0], big-endian)
    lea     0x00114300, %a0
    move.l  (%a0), %d1
    cmp.l   #0xCA111111, %d1
    bne     _fail

    | MOVE.W ([+32,A1]),(A3)  — word at [0x00114200] = 0xCAFE, stored
    |                            at [0x00114400]
    .word   0x36B1, 0x0164, 0x0020

    | Expected at 0x00114400: 0xCAFE_2222 (word write at [0:1])
    lea     0x00114400, %a0
    move.l  (%a0), %d1
    cmp.l   #0xCAFE2222, %d1
    bne     _fail

    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0001, %d7
    move.l  %d7, (%a0)
_halt_f:
    bra     _halt_f
