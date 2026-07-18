| add_l_dn_memind_dst.s -- Task #213 (D2)
|
| Directed: ADD.L Dn,<memind-dst> -- reg-source to memind-dst RMW.
| Covers no-index memind destination.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    lea     0x00115f00, %a7

    | -- No-idx: ADD.L D0,([32,A4])
    |    EA = *[A4 + 32] + 0 (od=0).
    lea     0x00115200, %a4
    lea     0x00115220, %a0
    move.l  #0x00115400, (%a0)       | [A4+32] = pointer 0x00115400
    lea     0x00115400, %a0
    move.l  #0x00001111, (%a0)       | [target] = 0x00001111
    move.l  #0x00002222, %d0         | D0 = 0x00002222

    | ADD.L Dn,<ea>: opword = 1101_ddd_110_mmm_rrr (op[8:6]=110 for Dn,<ea> .L).
    |   ddd=D0=000, mmm=110, rrr=100 (A4): 1101_000_110_110_100 = 0xD1B4
    | ext1: memind no-idx, null od, word bd: I/IS=100, IS=1, BD size=10.
    |   b[15]=0 (D), b[14:12]=000, b[11]=0, b[10:9]=00, b[8]=1, b[7]=0 (BS=0),
    |   b[6]=1 (IS=1), b[5:4]=10, b[3]=0, b[2:0]=100
    |   = 0000_0001_0110_0100 = 0x0164
    .word   0xD1B4, 0x0164, 0x0020
    | Post-RMW, [target] must be 0x1111 + 0x2222 = 0x3333.
    lea     0x00115400, %a0
    move.l  (%a0), %d1
    cmp.l   #0x00003333, %d1
    bne     _fail1

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
