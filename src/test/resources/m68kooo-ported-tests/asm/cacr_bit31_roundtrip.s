| cacr_bit31_roundtrip.s — CACR bit 31 round-trip flag-of-bclr regression.
|
| Reproduces the Q700 ROM boot D-cache-enable test at PC 0x40804640:
|   movec CACR, D1
|   bset  #31, D1
|   movec D1, CACR
|   movec CACR, D1   ; readback — does bit 31 round-trip?
|   bclr  #31, D1    ; Z = NOT D1.bit31 BEFORE clear
|   beq   _fail      ; if Z=1, bit 31 didn't round-trip (test fails)
|
| HW (real silicon) takes the BEQ here, indicating bit 31 reads back
| as 0.  Sim-reproduces it would point at a CACR pipeline ordering
| or a BCLR-Z-flag bug.
|
| Expected: D1 after bclr has bit 31 cleared but the OTHER bits intact.
|           Z=0 (bit 31 was 1), N=1 (bit 31 was 1), result is 0x80000000
|           with bit 31 cleared = 0x00000000 (since we OR'd just bit 31
|           on top of 0).

    .text
    .org 0

_start:
    | Start with D0 = 0x12340000 so we can also check the OTHER bits
    | come back unchanged through the round-trip.
    move.l  #0x12340000, %d0
    movec   %d0, %cacr
    movec   %cacr, %d1
    cmp.l   %d0, %d1
    bne     _fail_baseline       | bit-pattern doesn't round-trip at all

    | Now do the bit-31 bset/movec/movec/bclr round-trip
    movec   %cacr, %d1           | D1 = current CACR (= 0x12340000)
    bset    #31, %d1             | D1.bit31 := 1; Z = !(was 1) = !0 = 1
    movec   %d1, %cacr           | CACR = 0x92340000
    movec   %cacr, %d1           | D1 := CACR readback (should be 0x92340000)
    cmp.l   #0x92340000, %d1
    bne     _fail_writeback      | bit 31 didn't survive the write/read

    bclr    #31, %d1             | Z = !D1.bit31 BEFORE clear = !1 = 0
    beq     _fail_bclr_flag      | if Z=1, bclr's flag computation is wrong

    | Verify D1 is now 0x12340000 (bit 31 cleared, rest intact)
    cmp.l   #0x12340000, %d1
    bne     _fail_bclr_value

    | PASS
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d2
    move.l  %d2, (%a0)
_halt:
    bra     _halt

_fail_baseline:
    move.l  #0xDEAD0001, %d2
    bra     _fail_common
_fail_writeback:
    move.l  #0xDEAD0002, %d2
    bra     _fail_common
_fail_bclr_flag:
    move.l  #0xDEAD0003, %d2
    bra     _fail_common
_fail_bclr_value:
    move.l  #0xDEAD0004, %d2
_fail_common:
    lea     0xFFFF0000, %a0
    move.l  %d2, (%a0)
_halt_fail:
    bra     _halt_fail
