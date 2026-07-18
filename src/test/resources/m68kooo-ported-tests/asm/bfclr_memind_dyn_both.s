| bfclr_memind_dyn_both.s — Task #215 (D5)
|
| BFCLR ([bd,An]){Do:Dw} — full-format memind (no-index) RMW with BOTH
| offset and width dynamic.  Exercises the alu.v imm[14] split shape
| (Dn_off -> src_b, Dn_wid -> src_c, no pre-pack phase).
|
| Opword BFCLR (subop=100) full-fmt mode=110 rrr=001 (A1):
|   op = 0b1110_1100_11_110_001 = 0xECF1
| ext1 dyn-both D3=Dn_off, D5=Dn_wid: [11]=1 [10:6]=00011 [5]=1 [4:0]=00101
|   = 0_000_1_00011_1_00101 = 0x08E5
| ext2 full-fmt: BS=0 IS=1 BD_SIZE=10(word) I/IS=100 -> 0x0164
| ext3 bd word: +12 (0x000C)
|
| Memory layout:
|   A2           = 0x40800700
|   [A2+12]      = 0x40800800
|   [0x40800800] = 0xFFFFFFFF (all bits set)
|
|   BFCLR (@[+12,A2]){D3=#4 : D5=#8} clears bits [4..11] from MSB.
|   Mask = 0x0FF00000.  Result = 0xFFFFFFFF & ~0x0FF00000 = 0xF00FFFFF.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0
_start:
    lea     0x40800700, %a2
    move.l  #0x40800800, 12(%a2)
    lea     0x40800800, %a3
    move.l  #0xFFFFFFFF, (%a3)

    moveq   #4, %d3
    moveq   #8, %d5

    | BFCLR (@[+12,A2]){D3:D5}
    .word   0xECF2, 0x08E5, 0x0164, 0x000C

    move.l  (%a3), %d1
    cmp.l   #0xF00FFFFF, %d1
    bne     _fail

    | Second: offset=16, width=8 -> mask 0x0000FF00.
    | 0xF00FFFFF & ~0x0000FF00 = 0xF00F00FF.
    moveq   #16, %d3
    moveq   #8,  %d5
    .word   0xECF2, 0x08E5, 0x0164, 0x000C
    move.l  (%a3), %d1
    cmp.l   #0xF00F00FF, %d1
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
