| sr_reserved_bits_masked.s -- 68040 SR implemented-bits mask (0xF71F)
|
| Every architectural SR write must force the reserved bits (11, 7:5)
| to 0 -- real 68040 behaviour and Musashi's CPU_SR_MASK.  Before the
| Phase-3 debug-redesign fix, ORI/EORI/MOVE-to-SR/STOP parked garbage in
| the reserved bits; on live hardware the debug snap chain then read
| back an architecturally-impossible SR (0x3850, bits 11+6 set), which
| blocked a hardware verdict during the 0xD2DF5DD3 step-wedge
| investigation.
|
| Runs entirely in supervisor mode with T1/T0 clear (no trace) and only
| raises/keeps IPL, so no exception traffic is involved.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | MOVE to SR with reserved bits 11 + 7:5 set: expect them masked.
    | 0x2FFF = S=1, bit11=1, IPL=7, bits7:5=111, XNZVC=11111
    | masked  -> 0x271F
    move.w  #0x2FFF, %sr
    move.w  %sr, %d1
    cmpi.w  #0x271F, %d1
    bne     _fail1

    | NB: cmpi updates NZVC but NOT X, so after an equal compare the
    | live CCR is X|Z = 0x14 (X=1 came from the 0x...1F write above);
    | move-from-SR composes the LIVE CCR into bits [4:0].

    | ORI to SR attempting to set only reserved bits: SR unchanged
    | (upper byte still 0x27, CCR = X|Z = 0x14 from the cmpi).
    ori.w   #0x08E0, %sr
    move.w  %sr, %d2
    cmpi.w  #0x2714, %d2
    bne     _fail2

    | EORI to SR toggling a reserved bit + Z: reserved stays 0, Z
    | toggles off (X stays).  (CCR was 0x14 again from the cmpi.)
    eori.w  #0x0804, %sr
    move.w  %sr, %d3
    cmpi.w  #0x2710, %d3
    bne     _fail3

    | MOVE from SR must never show reserved bits regardless of history.
    move.w  %sr, %d4
    andi.w  #0x08E0, %d4
    bne     _fail4

    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt_pass:
    bra     _halt_pass

_fail1:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0001, %d0
    move.l  %d0, (%a0)
_halt_fail1:
    bra     _halt_fail1

_fail2:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0002, %d0
    move.l  %d0, (%a0)
_halt_fail2:
    bra     _halt_fail2

_fail3:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0003, %d0
    move.l  %d0, (%a0)
_halt_fail3:
    bra     _halt_fail3

_fail4:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0004, %d0
    move.l  %d0, (%a0)
_halt_fail4:
    bra     _halt_fail4
