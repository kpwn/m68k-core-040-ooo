| bfextu_memind_static.s — Task #209 (C3)
|
| Directed test for BFEXTU with a full-format memory-indirect no-index
| destination ([bd,An]) and static offset/width.  Exercises the new
| V2 memind-dst assembler chain:
|   ph0 ADD (An+bd) -> TMP1
|   ph1 LOAD (TMP1) -> TMP1    (inner indirect)
|   ph2 ADD TMP1+od -> TMP1    (final EA; od=null here)
|   ph3 LOAD (TMP1) -> TMP1    (field data)
|   ph4 BFEXTU TMP1 -> Dn
|
| Opword for BFEXTU mode 110 (full-format) rrr=001 (A1):
|   op = 0b1110_1001_11_110_001 = 0xE9F1
| ext1 (descriptor): [14:12]=3 (D3 dest), off=0, wid=16 -> 0x3010
| ext2 (full-format ext1_ff): BS=0, IS=1, BD_SIZE=10 (word),
|   I/IS=100 (memind no-idx) -> 0b0_000_0_00_1_0_1_10_0_100 = 0x0164
| ext3 (bd word): +16 = 0x0010
|
| Memory layout:
|   A1       = 0x40800400
|   [A1+16]  = 0x408004F0    (indirect pointer)
|   [0x408004F0] = 0x12345678  (field data)
|
|   BFEXTU @[+16,A1]{#0:#16} -> D3 should yield 0x1234 (top 16 bits).
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0
_start:
    | Stage memory.
    lea     0x40800400, %a1
    move.l  #0x408004F0, 16(%a1)          | [A1+16] = 0x408004F0
    lea     0x408004F0, %a2
    move.l  #0x12345678, (%a2)

    | Pre-seed D3 with a known wrong value so a no-op path fails loud.
    move.l  #0xAAAAAAAA, %d3

    | BFEXTU @([+16,A1]){#0:#16},D3.
    .word   0xE9F1, 0x3010, 0x0164, 0x0010
    cmp.l   #0x1234, %d3
    bne     _fail

    bra     _pass

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
