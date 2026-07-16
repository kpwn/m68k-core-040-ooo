| movep_l_byte_layout.s — MOVEP.L reg→mem big-endian byte layout.
|
| PRM §4.147: MOVEP.L Dy,(d16,Ax) stores Dy[31:24], Dy[23:16],
| Dy[15:8], Dy[7:0] at offsets +0, +2, +4, +6 respectively.  A bug
| reversing the shift order (e.g. LSR 8 instead of LSR 24 on the
| first byte) would put the wrong byte at offset +0.  This test
| uses a distinctive value 0x01020304 so each destination byte is
| easy to identify.
|
| Observation strategy: read back each target byte-lane via a
| word readback after pre-filling the sentinel lane with a known
| non-zero byte.  0xF5 is picked so none of the stored bytes
| (0x01, 0x02, 0x03, 0x04) could be confused with the sentinel.
|
| PASS: sentinel 0xC0FFEE00.

    .text
    .org 0

_start:
    lea     0x00010000, %a7
    lea     0x00020000, %a0
    lea     0x00020008, %a1
    lea     0x0002000a, %a2
    lea     0x0002000c, %a3
    lea     0x0002000e, %a4

    | Pre-fill destination words with sentinel 0x??F5 — each target
    | lane is the high byte of a word, so low byte 0xF5 stays untouched.
    move.w  #0x00F5, (%a1)
    move.w  #0x00F5, (%a2)
    move.w  #0x00F5, (%a3)
    move.w  #0x00F5, (%a4)

    move.l  #0x01020304, %d0
    movep.l %d0, 8(%a0)

    | After MOVEP.L, words must be: 0x01F5, 0x02F5, 0x03F5, 0x04F5.
    move.w  (%a1), %d2
    andi.l  #0xFFFF, %d2
    cmp.l   #0x01F5, %d2
    bne     _fail
    move.w  (%a2), %d2
    andi.l  #0xFFFF, %d2
    cmp.l   #0x02F5, %d2
    bne     _fail
    move.w  (%a3), %d2
    andi.l  #0xFFFF, %d2
    cmp.l   #0x03F5, %d2
    bne     _fail
    move.w  (%a4), %d2
    andi.l  #0xFFFF, %d2
    cmp.l   #0x04F5, %d2
    bne     _fail

    | Reg→mem with zero displacement — exercises the LSR-0 / LSR-24
    | ordering directly off the base register.
    lea     0x00020030, %a5
    move.w  #0x00F5, (%a5)
    lea     0x00020032, %a5
    move.w  #0x00F5, (%a5)
    lea     0x00020034, %a5
    move.w  #0x00F5, (%a5)
    lea     0x00020036, %a5
    move.w  #0x00F5, (%a5)

    lea     0x00020030, %a5
    move.l  #0xDEADBEEF, %d3
    movep.l %d3, 0(%a5)

    lea     0x00020030, %a5
    move.w  (%a5), %d2
    andi.l  #0xFFFF, %d2
    cmp.l   #0xDEF5, %d2
    bne     _fail
    lea     0x00020032, %a5
    move.w  (%a5), %d2
    andi.l  #0xFFFF, %d2
    cmp.l   #0xADF5, %d2
    bne     _fail
    lea     0x00020034, %a5
    move.w  (%a5), %d2
    andi.l  #0xFFFF, %d2
    cmp.l   #0xBEF5, %d2
    bne     _fail
    lea     0x00020036, %a5
    move.w  (%a5), %d2
    andi.l  #0xFFFF, %d2
    cmp.l   #0xEFF5, %d2
    bne     _fail

    lea     0xFFFF0000, %a1
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a1)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a1
    move.l  #0xDEADBEEF, %d7
    move.l  %d7, (%a1)
_halt_fail:
    bra     _halt_fail
