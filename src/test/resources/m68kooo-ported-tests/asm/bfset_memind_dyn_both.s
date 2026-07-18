| bfset_memind_dyn_both.s — Task #215 (D5)
|
| BFSET ([bd,An]){Do:Dw} — full-format memind (no-index) RMW with BOTH
| offset and width dynamic.  Uses the alu.v imm[14] split shape.
|
| Opword BFSET (subop=110) full-fmt mode=110 rrr=011 (A3):
|   op = 0b1110_1110_11_110_011 = 0xEEF3
| ext1 dyn-both D1=Dn_off, D6=Dn_wid: [11]=1 [10:6]=00001 [5]=1 [4:0]=00110
|   = 0_000_1_00001_1_00110 = 0x0866
| ext2 full-fmt: BS=0 IS=1 BD_SIZE=10 I/IS=100 -> 0x0164
| ext3 bd word: +4 (0x0004)
|
| Memory layout:
|   A3           = 0x40800900
|   [A3+4]       = 0x40800A00
|   [0x40800A00] = 0x00000000
|
|   BFSET (@[+4,A3]){D1:D6} sets width bits starting at offset from MSB.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0
_start:
    lea     0x40800900, %a3
    move.l  #0x40800A00, 4(%a3)
    lea     0x40800A00, %a4
    move.l  #0x00000000, (%a4)

    | D1=off=0, D6=wid=8 -> mask 0xFF000000, result 0xFF000000.
    moveq   #0, %d1
    moveq   #8, %d6
    .word   0xEEF3, 0x0866, 0x0164, 0x0004
    move.l  (%a4), %d2
    cmp.l   #0xFF000000, %d2
    bne     _fail

    | D1=off=12, D6=wid=12 -> mask 0x000FFF00.
    | 0xFF000000 | 0x000FFF00 = 0xFF0FFF00.
    moveq   #12, %d1
    moveq   #12, %d6
    .word   0xEEF3, 0x0866, 0x0164, 0x0004
    move.l  (%a4), %d2
    cmp.l   #0xFF0FFF00, %d2
    bne     _fail

    | D1=off=24, D6=wid=0 (=32 per PRM) -> mask 0xFFFFFFFF.
    | Actually width=0 means width=32, but offset=24 + width=32 wraps.
    | Per Musashi: field wraps mod 32, so effective mask = rotate.
    | Simpler corner: D1=0, D6=0 (width=32, offset=0) -> mask 0xFFFFFFFF.
    moveq   #0, %d1
    moveq   #0, %d6
    .word   0xEEF3, 0x0866, 0x0164, 0x0004
    move.l  (%a4), %d2
    cmp.l   #0xFFFFFFFF, %d2
    beq     _pass
    bra     _fail

_pass:
    move.l  #0xC0FFEE00, %d7
    move.l  #0xFFFF0000, %a0
    move.l  %d7, (%a0)
_halt:
    bra     _halt

_fail:
    move.l  #0xDEADBEEF, %d7
    move.l  #0xFFFF0000, %a0
    move.l  %d7, (%a0)
    bra     _halt
