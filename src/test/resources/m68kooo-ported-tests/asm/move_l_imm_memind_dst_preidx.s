| move_l_imm_memind_dst_preidx.s — Task #212 (D1)
|
| MOVE.L #imm, ([bd.W,An,Xn.L*sc],od)  — pre-indexed memind dst.
| Tests reg/imm src -> memind-dst pre-idx path (D1 Shape 1).
|
| Uses assembler's full-format memind syntax:
|   move.l #0xDEADBEEF, ([32,%a1,%d0.l*2],8)
|
| PASS: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | Seed inner pointer slot: [A1 + 32 + D0*2] = [0x00114000+32+0x20]
    |                        = [0x00114040] = 0x00114500
    lea     0x00114040, %a6
    move.l  #0x00114500, (%a6)

    | Seed destination: [0x00114500 + 8] = 0x00114508 = 0
    lea     0x00114508, %a6
    move.l  #0x00000000, (%a6)

    move.l  #0x10, %d0           | index = 16 (scale=2 → +32)
    lea     0x00114000, %a1

    | MOVE.L #0xDEADBEEF, ([+32,%a1,%d0.l*2],+8)
    move.l  #0xDEADBEEF, ([32,%a1,%d0.l*2],8)

    | Verify [0x00114508] == 0xDEADBEEF
    lea     0x00114508, %a0
    move.l  (%a0), %d1
    cmp.l   #0xDEADBEEF, %d1
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
