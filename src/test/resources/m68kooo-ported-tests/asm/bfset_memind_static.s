| bfset_memind_static.s — Task #209 (C3)
|
| Directed test for BFSET ([bd,An]){#off:#wid} — RMW memind, no-index,
| static offset+width.  Exercises the new V2 memind-dst RMW crack:
|   ph0 ADD An+bd    -> TMP1
|   ph1 LD (TMP1)    -> TMP1  (inner indirect)
|   ph2 ADD TMP1+od  -> TMP2  (final EA preserved)
|   ph3 LD (TMP2)    -> TMP1  (field data)
|   ph4 BFSET TMP1   -> TMP1  (merged)
|   ph5 ST (TMP2)   <- TMP1  (commit)
|
| Opword BFSET (full-fmt) rrr=001 (A1): BFSET subop=110
|   op = 0b1110_1110_11_110_001 = 0xEEF1
| ext1 descriptor: [14:12] don't-care (BFSET ignores), off=#4, wid=#12
|   -> [15]=0 [14:12]=0 [11]=0 [10:6]=00100 [5]=0 [4:0]=01100 = 0x010C
| ext2 full-fmt: BS=0 IS=1 BD_SIZE=10(word) I/IS=100 no-idx -> 0x0164
| ext3 bd (word): +8 (0x0008)
|
| Memory layout:
|   A1          = 0x40800500
|   [A1+8]      = 0x40800600      (indirect pointer)
|   [0x40800600] = 0x12345678     (initial field data)
|
|   BFSET (@[+8,A1]){#4:#12} sets bits [4..15] (from MSB) to 1.
|   Mask = 0x0FFF0000.  Result = 0x12345678 | 0x0FFF0000 = 0x1FFF5678.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0
_start:
    lea     0x40800500, %a1
    move.l  #0x40800600, 8(%a1)
    lea     0x40800600, %a2
    move.l  #0x12345678, (%a2)

    | BFSET ([+8,A1]){#4:#12}
    .word   0xEEF1, 0x010C, 0x0164, 0x0008

    | Verify.
    move.l  (%a2), %d1
    cmp.l   #0x1FFF5678, %d1
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
