| bfchg_memind_dyn_both.s — Task #215 (D5)
|
| BFCHG ([bd,An]){Do:Dw} — full-format memind (no-index) RMW with BOTH
| offset and width dynamic.  Exercises the V2 task #215 landing:
|   alu.v imm[14] split shape (off in src_b, wid in src_c).
|
| Phase map for the D5 non-BFINS dyn-both memind crack:
|   ph0 ADD An+bd   -> TMP1
|   ph1 LD (TMP1)   -> TMP1   (inner indirect)
|   ph2 ADD TMP1+od -> TMP2   (final EA preserved for STORE)
|   ph3 LD (TMP2)   -> TMP1   (field data)
|   ph4 BFCHG TMP1, src_b=Dn_off, src_c=Dn_wid, imm=bf_dyn_both_split
|       -> TMP1   (toggled)
|   ph5 ST @TMP2    <- TMP1   (commit)
|
| Opword BFCHG (subop=010) full-fmt mode=110 rrr=001 (A1):
|   op = 0b1110_1010_11_110_001 = 0xEAF1
| ext1 descriptor dyn-both: [15]=0 [14:12]=don't-care (BFCHG ignores),
|   [11]=1 (Do), [10:6]=Dn_off index, [5]=1 (Dw), [4:0]=Dn_wid index.
|   Dn_off=D2, Dn_wid=D4: 0_000_1_00010_1_00100 = 0x08A4
| ext2 full-fmt: BS=0 IS=1 BD_SIZE=10(word) I/IS=100 (no-idx memind)
|   -> 0x0164
| ext3 bd (word): +8 (0x0008)
|
| Memory layout:
|   A1          = 0x40800500
|   [A1+8]      = 0x40800600      (indirect pointer)
|   [0x40800600] = 0x12345678     (initial field data)
|
|   BFCHG (@[+8,A1]){D2=#8 : D4=#16} toggles bits [8..23] from MSB.
|   Mask = 0x00FFFF00.  Initial 0x12345678 ^ 0x00FFFF00 = 0x12CBA978.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0
_start:
    lea     0x40800500, %a1
    move.l  #0x40800600, 8(%a1)
    lea     0x40800600, %a2
    move.l  #0x12345678, (%a2)

    | D2 = off = 8, D4 = wid = 16.
    moveq   #8,  %d2
    moveq   #16, %d4

    | BFCHG (@[+8,A1]){D2:D4}
    .word   0xEAF1, 0x08A4, 0x0164, 0x0008

    | Verify first toggle.
    move.l  (%a2), %d1
    cmp.l   #0x12CBA978, %d1
    bne     _fail

    | Toggle again — should restore original.
    .word   0xEAF1, 0x08A4, 0x0164, 0x0008
    move.l  (%a2), %d1
    cmp.l   #0x12345678, %d1
    bne     _fail

    | Third: offset=4, width=12 (D2=4, D4=12).
    | Mask bits [4..15] from MSB = 0x0FFF0000.
    | 0x12345678 ^ 0x0FFF0000 = 0x1DCB5678.
    moveq   #4,  %d2
    moveq   #12, %d4
    .word   0xEAF1, 0x08A4, 0x0164, 0x0008
    move.l  (%a2), %d1
    cmp.l   #0x1DCB5678, %d1
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
