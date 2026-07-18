| bf_ffo.s — 68020+ BFFFO: find first one, reg-direct EA.
|
| Semantics (Musashi reference):
|   offset  &= 31
|   width   = ((width_raw-1) & 31) + 1
|   data    = ROL_32(Dy, offset)         (positions field MSB at bit 31)
|   FLAG_N  = data[31]
|   data  >>= 32 - width                  (extract field low-aligned)
|   FLAG_Z  = (data == 0)
|   result = offset + leading-zero-count within field (caps at offset+width)
|
| Test matrix:
|   Case A: field all zeros       → result = offset + width
|   Case B: MSB of field set      → result = offset
|   Case C: LSB of field set only → result = offset + width - 1
|
| All cases at offset=3, width=8 to exercise non-trivial alignment.

    .text
    .org 0
_start:
    | ---- Case A: field all zeros ----
    | d0 has zero bits 28..21 (the offset=3, width=8 field).  Pick
    | d0 = 0xE01FFFFF: top 3 bits set (not in field), field all 0,
    | bits below field all 1.
    move.l  #0xE01FFFFF, %d0
    bfffo   %d0{3:8}, %d1
    move.l  #11, %d7                 | offset(3) + width(8) = 11
    cmp.l   %d7, %d1
    beq     1f
    bra     _fail
1:

    | Case A flag check: field == 0 → Z = 1.
    | BFFFO sets flags pre-extract; Z reflects (post-extract field == 0).
    tst.l   %d1
    | Can't simply check Z here — tst.l on d1 overwrites CCR.  Instead
    | rely on branch-after-BFFFO: re-run case A and branch immediately.
    move.l  #0xE01FFFFF, %d0
    bfffo   %d0{3:8}, %d1
    beq     2f
    bra     _fail
2:

    | ---- Case B: MSB of field set ----
    | We want bit 28 of d0 = 1, all other bits = 0 for cleanliness.
    | d0 = 0x10000000 → bit 28 set.  offset=3, width=8 covers bits 28..21.
    | field = 1000_0000 = 0x80; MSB of field set ⇒ result = offset = 3.
    move.l  #0x10000000, %d0
    bfffo   %d0{3:8}, %d1
    moveq   #3, %d7
    cmp.l   %d7, %d1
    beq     3f
    bra     _fail
3:

    | ---- Case C: LSB of field set ----
    | bit 21 of d0 = 1, all other bits 0 → d0 = 0x00200000.
    | field = 0000_0001 → first one at end ⇒ result = offset+width-1 = 10.
    move.l  #0x00200000, %d0
    bfffo   %d0{3:8}, %d1
    moveq   #10, %d7
    cmp.l   %d7, %d1
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
