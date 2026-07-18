| move_l_memind_to_memind.s — Task #238 (G1)
|
| MOVE.L mem-indirect-src → mem-indirect-dst with FULL-FORMAT memind on
| BOTH sides.  Strategy (a): both no-idx.
|
|   src EA = ([bd.W=+0x20, A1], od.W=+0x4)
|     I/IS=101 (memind no-idx, bd.W, od.W).  No: I/IS=100 is no-idx
|     no-od; for od.W we need pre-idx with od.W (I/IS=010) BUT pre-idx
|     also has Xn... Actually I/IS=100 forces od=null.  Use bd-only.
|
|   src EA = ([bd.W=+0x20, A1])    (od null)
|     ext1 = 0x0164 = full-format, BS=0, IS=1, BD_SIZE=10 (.W),
|              I/IS=100 (no-idx), 2 ext words total.
|   dst EA = ([bd.W=+0x40, A2])    (od null)
|     ext1 (dst) = 0x0164.  2 ext words.  Total 4 ext words.
|
| Opword: size=10 (.L), dst_reg=010 (A2), dst_mode=110, src_mode=110, src_reg=001
|   = 0010_010_110_110_001 = 0x25B1
|
| Memory layout:
|   A1 = 0x00114000   (src base)
|   [A1 + 0x20] = 0x00115004   (src inner pointer; final src EA)
|   [0x00115004] = 0xCAFEBABE  (the src data)
|
|   A2 = 0x00114800   (dst base)
|   [A2 + 0x40] = 0x00116008   (dst inner pointer; final dst EA)
|   [0x00116008] = 0  (where data should land)

    .text
    .org 0

_start:
    | Seed src inner pointer @ A1+0x20
    lea     0x00114020, %a6
    move.l  #0x00115004, (%a6)
    | Seed src data @ inner ptr
    lea     0x00115004, %a6
    move.l  #0xCAFEBABE, (%a6)
    | Seed dst inner pointer @ A2+0x40
    lea     0x00114840, %a6
    move.l  #0x00116008, (%a6)
    | Zero dst data
    lea     0x00116008, %a6
    move.l  #0x00000000, (%a6)

    lea     0x00114000, %a1
    lea     0x00114800, %a2

    | MOVE.L ([+0x20,A1]), ([+0x40,A2])
    .word   0x25B1, 0x0164, 0x0020, 0x0164, 0x0040

    | Verify dst landed
    lea     0x00116008, %a0
    move.l  (%a0), %d1
    cmp.l   #0xCAFEBABE, %d1
    bne     _fail

    | Pass sentinel
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
