| tas_indexed.s — Phase-2 item #4: TAS (d8,An,Xn) brief-indexed.
| Prior to this fix, v2_is_tas_family_f3's EA list had no indexed case
| (decode_0100.vh's indexed TAS rows are comment-only, zero legacy
| fallback), so this shape fell through to vec-4 ILLEGAL.
|
| TAS semantics (PRM §4.189): read EA byte, set N/Z from the byte,
| OR 0x80 into the MSB, write back — atomic test-and-set.
|
| Exercises: unscaled word index, scaled long index, a "bit already
| set" case (byte stays visually unchanged but write-back must still
| occur), and CCR N/Z from both a zero and a negative-byte operand.
|
| PASS: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    lea     0x00010000, %a7
    move.l  #_unexpected_trap, 0x00000010    | vec 4 (illegal) = FAIL guard

    lea     0x00020000, %a0

    | --- TAS (4,a0,d1.w) — d1 unscaled word index, value 0x00 (Z set,
    | N clear pre-TAS); after TAS byte must be 0x80, CCR Z=1 N=0.
    moveq   #0, %d1
    move.b  #0x00, (4,%a0,%d1.w)
    tas     (4,%a0,%d1.w)
    bne     _fail1                            | Z must be set (orig byte 0)
    bmi     _fail1                            | N must be clear
    move.b  (4,%a0,%d1.w), %d0
    andi.l  #0xFF, %d0
    cmp.l   #0x80, %d0
    bne     _fail1

    | --- TAS (8,a0,a2.l*4) — scaled long index, value 0x7F (N clear,
    | Z clear pre-TAS); after TAS byte must be 0xFF.
    lea     0x00000001, %a2
    move.b  #0x7F, (4,%a0,%a2.l*4)            | a0 + 4 + 1*4 = a0+8
    tas     (4,%a0,%a2.l*4)
    beq     _fail2                            | Z must be clear (orig byte != 0)
    bmi     _fail2                            | N must be clear (orig byte 0x7F)
    move.b  (4,%a0,%a2.l*4), %d0
    andi.l  #0xFF, %d0
    cmp.l   #0xFF, %d0
    bne     _fail2

    | --- TAS (0,a0,d3.w) — value already has bit 7 set (0x91): N must
    | be set pre-TAS (byte read as negative), write-back is idempotent
    | (0x91 | 0x80 == 0x91).
    moveq   #2, %d3
    move.b  #0x91, (0,%a0,%d3.w)
    tas     (0,%a0,%d3.w)
    beq     _fail3
    bpl     _fail3                            | N must be set
    move.b  (0,%a0,%d3.w), %d0
    andi.l  #0xFF, %d0
    cmp.l   #0x91, %d0
    bne     _fail3

    | --- Confirm surrounding bytes are untouched (byte-only RMW, no
    | stray word/long write from the EA-compute crack).
    move.b  #0xAA, (5,%a0,%d1.w)
    move.b  (5,%a0,%d1.w), %d0
    andi.l  #0xFF, %d0
    cmp.l   #0xAA, %d0
    bne     _fail4

_pass:
    move.l  #0xC0FFEE00, %d7
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
_halt:
    bra     _halt

_unexpected_trap:
    move.l  #0xDEAD0000, %d7
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
    bra     _halt

_fail1:
    move.l  #0xDEAD0001, %d7
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
    bra     _halt

_fail2:
    move.l  #0xDEAD0002, %d7
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
    bra     _halt

_fail3:
    move.l  #0xDEAD0003, %d7
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
    bra     _halt

_fail4:
    move.l  #0xDEAD0004, %d7
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
    bra     _halt
