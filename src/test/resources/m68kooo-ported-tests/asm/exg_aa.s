| exg_aa.s -- task #220 / E1 directed: EXG A0,A1 round-trip.
|
| Verifies the An,Am form of EXG.  The dual-dst PRF path must route
| the swap to the address-register half of the arch register file
| (arch indices {2'b01, op[2:0]} / {2'b01, op[11:9]}).  CCR is left
| unchanged.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | Distinct address patterns.  Use lea so we don't depend on imm-to-An
    | having any subtle CCR behaviour.
    lea     0x00400000, %a0
    lea     0x00800000, %a1

    | Stamp CCR.Z to a known state.
    moveq   #0, %d5
    tst.l   %d5
    | Z is now 1.

    | EXG An,Am.  After: A0 = 0x00800000, A1 = 0x00400000.
    exg     %a0, %a1

    | Z should still be 1 — EXG must not touch CCR.
    bne     _fail

    move.l  %a0, %d0
    cmp.l   #0x00800000, %d0
    bne     _fail
    move.l  %a1, %d0
    cmp.l   #0x00400000, %d0
    bne     _fail

    | Round-trip back to originals.
    exg     %a0, %a1
    move.l  %a0, %d0
    cmp.l   #0x00400000, %d0
    bne     _fail
    move.l  %a1, %d0
    cmp.l   #0x00800000, %d0
    bne     _fail

    | EXG A2,A2 — same-register identity case for An,Am.
    lea     0x00112233, %a2
    exg     %a2, %a2
    move.l  %a2, %d0
    cmp.l   #0x00112233, %d0
    bne     _fail

    lea     0xFFFF0000, %a6
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a6)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a6
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a6)
_fail_halt:
    bra     _fail_halt
