| bfins_memind_dyn_single.s — Task #209 (C3)
|
| Directed test for BFINS D1,([bd,An]){#off:Dw} — dyn-single (width-only)
| with a full-format memory-indirect no-index destination.
|
| Exercises the V2 memind-dst RMW crack with dyn Dn riding src_c via
| bf_dyn_src_c_or (imm[13]=1):
|   ph0 ADD An+bd     -> TMP1
|   ph1 LD (TMP1)     -> TMP1
|   ph2 ADD TMP1+od   -> TMP2      (EA preserved)
|   ph3 LD (TMP2)     -> TMP1      (field data)
|   ph4 BFINS src_a=TMP1, src_b=D1, src_c=Dw, imm=dyn-wid|src_c
|              -> TMP1  (merged)
|   ph5 ST (TMP2)    <- TMP1
|
| Opword BFINS (subop 111) mode 110 rrr=001 (A1):
|   op = 0b1110_1111_11_110_001 = 0xEFF1
| ext1 descriptor: [14:12]=D1 insert, off=#8, DW=1 Dn_wid=D3
|   -> [15]=0 [14:12]=1 [11]=0 [10:6]=01000 [5]=1 [4:0]=00011
|    = 0b0_001_0_01000_1_00011 = 0x1223
| ext2 full-fmt: BS=0 IS=1 BD_SIZE=10(word) I/IS=100 no-idx -> 0x0164
| ext3 bd (word): +12 = 0x000C
|
| Memory layout:
|   A1            = 0x40800700
|   [A1+12]       = 0x40800800      (indirect pointer)
|   [0x40800800]  = 0x11111111      (initial field data)
|
|   BFINS D1=0xABCD, width=D3=16, offset=8:
|     field [8..23] from MSB = replaced with low 16 bits of D1.
|     0x1111_1111: bits[23..8]=0x1111 -> replaced with 0xABCD
|     Result = 0x11_AB_CD_11.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0
_start:
    lea     0x40800700, %a1
    move.l  #0x40800800, 12(%a1)
    lea     0x40800800, %a2
    move.l  #0x11111111, (%a2)

    move.l  #0x0000ABCD, %d1       | insert data
    moveq   #16, %d3               | dyn width = 16

    | BFINS D1,([+12,A1]){#8:D3}
    .word   0xEFF1, 0x1223, 0x0164, 0x000C

    move.l  (%a2), %d2
    cmp.l   #0x11ABCD11, %d2
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
