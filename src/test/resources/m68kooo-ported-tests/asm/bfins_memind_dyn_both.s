| bfins_memind_dyn_both.s — Task #215 (D5)
|
| BFINS Dn_insert, ([bd,An]){Do:Dw} — BFINS into a full-format memind
| (no-index) EA with BOTH offset AND width dynamic.  This is the 4-
| source case: loaded_data + Dn_insert + Dn_off + Dn_wid.  Resolved by
| the 10-phase crack that packs {off,wid}->TMP2, runs the BFINS, then
| RECOMPUTES the EA for the final STORE (EA-recompute tax — only no-
| index shape is supported; indexed memind BFINS dyn-both stays blocked
| at the fire gate).
|
| Phase map:
|   ph0 TMP1 = An + bd
|   ph1 LD(TMP1) -> TMP1       (inner indirect)
|   ph2 TMP1 += od             (final EA, NOT preserved)
|   ph3 TMP2 = PACK(Dn_off, Dn_wid)
|   ph4 LD(TMP1) -> TMP1       (TMP1 = field data, EA LOST)
|   ph5 BFINS TMP1, Dn_insert, TMP2, imm=dyn_both+via_src_c -> TMP1
|   ph6 TMP2 = An + bd         (recompute EA)
|   ph7 LD(TMP2) -> TMP2       (inner indirect, second round)
|   ph8 TMP2 += od             (final EA)
|   ph9 ST@TMP2 <- TMP1
|
| Opword BFINS (subop=111) full-fmt mode=110 rrr=100 (A4):
|   op = 0b1110_1111_11_110_100 = 0xEFF4
| ext1 dyn-both D0=Dn_insert, D2=Dn_off, D3=Dn_wid:
|   [15]=0 [14:12]=000 (D0 insert) [11]=1 [10:6]=00010 [5]=1 [4:0]=00011
|   = 0_000_1_00010_1_00011 = 0x08A3
| ext2 full-fmt: BS=0 IS=1 BD_SIZE=10 I/IS=100 -> 0x0164
| ext3 bd word: +16 (0x0010)
|
| Memory layout:
|   A4           = 0x40800B00
|   [A4+16]      = 0x40800C00
|   [0x40800C00] = 0x11223344 (initial field data)
|
|   BFINS D0, @[+16,A4]{D2=#8 : D3=#16} writes low 16 bits of D0 into
|   bits [8..23] from MSB.  D0 = 0x0000ABCD -> field 0xABCD.
|   Mask = 0x00FFFF00.  Result = (0x11223344 & 0xFF0000FF) | 0x00ABCD00
|        = 0x110000FF | 0x00ABCD00 = 0x11ABCDFF.  Wait: 0x11223344 & ~0x00FFFF00
|        = 0x11223344 & 0xFF0000FF = 0x11000044.  Then | 0x00ABCD00 = 0x11ABCD44.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0
_start:
    lea     0x40800B00, %a4
    move.l  #0x40800C00, 16(%a4)
    lea     0x40800C00, %a5
    move.l  #0x11223344, (%a5)

    | D0 = insert value (low 16 bits), D2=off, D3=wid.
    move.l  #0x0000ABCD, %d0
    moveq   #8,  %d2
    moveq   #16, %d3

    | BFINS D0, @[+16,A4]{D2:D3}
    .word   0xEFF4, 0x08A3, 0x0164, 0x0010

    | Verify.
    move.l  (%a5), %d1
    cmp.l   #0x11ABCD44, %d1
    bne     _fail

    | Second test: tighter field.  Offset=12, width=8.
    | Reset mem to known pattern.
    move.l  #0xFFFFFFFF, (%a5)
    move.l  #0x00000055, %d0           | insert low 8 bits = 0x55
    moveq   #12, %d2
    moveq   #8,  %d3
    | Mask = 0xFF << (32-12-8) = 0xFF << 12 = 0x000FF000.
    | Result = (0xFFFFFFFF & 0xFFF00FFF) | (0x55 << 12) = 0xFFF55FFF.
    .word   0xEFF4, 0x08A3, 0x0164, 0x0010
    move.l  (%a5), %d1
    cmp.l   #0xFFF55FFF, %d1
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
