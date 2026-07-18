| bfextu_memind_dyn_both.s — Task #209 (C3)
|
| Directed test for BFEXTU ([bd,An]){Do:Dw},Dn — dyn-both (DO=1 AND
| DW=1) with a full-format memory-indirect no-index destination.
| Exercises the new V2 memind read-only dyn-both crack:
|   ph0 ADD An+bd    -> TMP1
|   ph1 LD (TMP1)    -> TMP1           (inner indirect)
|   ph2 ADD TMP1+od  -> TMP1           (final EA)
|   ph3 LD (TMP1)    -> TMP1           (field data)
|   ph4 PACK Do,Dw   -> TMP2           (dyn-both packed)
|   ph5 BFEXTU src_a=TMP1, src_b=TMP2, imm=bf_dyn_both_imm -> Dn
|
| Opword BFEXTU (subop 001) mode 110 rrr=001 (A1):
|   op = 0b1110_1001_11_110_001 = 0xE9F1
| ext1 descriptor: [14:12]=D3 (Dn dest), DO=1 Dn_off=D2, DW=1 Dn_wid=D4
|   -> [15]=0 [14:12]=3 [11]=1 [10:6]=00010 [5]=1 [4:0]=00100
|    = 0b0_011_1_00010_1_00100 = 0x38A4
| ext2 full-fmt: BS=0 IS=1 BD_SIZE=10(word) I/IS=100 no-idx -> 0x0164
| ext3 bd (word): +24 = 0x0018
|
| Memory layout:
|   A1            = 0x40800A00
|   [A1+24]       = 0x40800B00      (indirect pointer)
|   [0x40800B00]  = 0xDEADBEEF      (field data)
|
|   BFEXTU {D2:D4},D3 with D2=8, D4=16 → extracts bits [8..23] (from MSB)
|   of 0xDEADBEEF = 0xADBE.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0
_start:
    lea     0x40800A00, %a1
    move.l  #0x40800B00, 24(%a1)
    lea     0x40800B00, %a2
    move.l  #0xDEADBEEF, (%a2)

    moveq   #8, %d2
    moveq   #16, %d4
    move.l  #0x55555555, %d3       | pre-seed D3 with loud nonsense

    | BFEXTU ([+24,A1]){D2:D4},D3
    .word   0xE9F1, 0x38A4, 0x0164, 0x0018

    cmp.l   #0xADBE, %d3
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
