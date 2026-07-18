| move_l_reg_memind_dst_postidx.s — Task #212 (D1)
|
| MOVE.L Dn, ([bd.W,An],Xn.L*sc,od)  — post-indexed memind dst.
| Tests reg src -> memind-dst post-idx path (D1 Shape 1).
|
|   EA = MEM[An + bd] + Xn*sc + od
|
| PASS: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | Seed inner pointer slot: [A1 + 32] = [0x00114020] = 0x00114500
    lea     0x00114020, %a6
    move.l  #0x00114500, (%a6)

    | Seed destination: 0x00114500 + 0x20 (D0*2 with D0=0x10) + 8 = 0x00114528
    lea     0x00114528, %a6
    move.l  #0x00000000, (%a6)

    move.l  #0xCAFEBABE, %d3
    move.l  #0x10, %d0
    lea     0x00114000, %a1

    | MOVE.L D3, ([+32,%a1],%d0.l*2,+8) — post-idx
    move.l  %d3, ([32,%a1],%d0.l*2,8)

    | Verify [0x00114528] == 0xCAFEBABE
    lea     0x00114528, %a0
    move.l  (%a0), %d1
    cmp.l   #0xCAFEBABE, %d1
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
