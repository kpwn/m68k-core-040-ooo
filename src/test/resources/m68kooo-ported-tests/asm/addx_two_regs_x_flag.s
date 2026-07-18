| addx_two_regs_x_flag.s — ADDX Dm,Dn reads pre-X flag and cascades
|
| PRM §4.7: ADDX Dx = Dx + Dy + X.  Used for multi-precision adds.
| Z is "sticky-if-nonzero" — if the result is zero, keep old Z; else
| clear Z.  Covers the V2 Stage D-2 ADDX single-µop path.
|
| Simulates a 64-bit add via two ADDX: low halves then high halves.
|
| PASS sentinel: 0xC0FFEE00 → 0xFFFF0000.

    .text
    .org 0

_start:
    | Sum = (D5:D4) + (D3:D2), 64-bit.
    | D4 = 0xfffffff0   (low  half of left  addend)
    | D5 = 0x00000001   (high half of left  addend)
    | D2 = 0x00000020   (low  half of right addend)
    | D3 = 0x00000002   (high half of right addend)
    | Sum low  = 0xfffffff0 + 0x20 = 0x00000010 with carry
    | Sum high = 1 + 2 + 1(X) = 4
    move.l  #0xfffffff0, %d4
    move.l  #0x00000001, %d5
    move.l  #0x00000020, %d2
    move.l  #0x00000002, %d3

    | Pre-clear X via ADD.L (adds 0, does not read X).
    add.l   #0, %d0
    | First ADDX: low halves.  D4 = D4 + D2 + X_old (= 0).  Sets X=1 (carry).
    addx.l  %d2, %d4
    cmp.l   #0x00000010, %d4
    bne     _fail
    | Now check X=1 is cascaded into the next ADDX.
    addx.l  %d3, %d5
    cmp.l   #0x00000004, %d5
    bne     _fail

    | Z-sticky: make a zero result and check Z was preserved from before.
    | Pre-set Z via SUB.L Dn,Dn (zero idiom writes Z=1).
    moveq   #0, %d6
    | ADD a non-zero to clear Z; then do ADDX of two zeros with X=0.
    add.l   #1, %d6
    | Pre-clear X with ADD.L to make the ADDX case deterministic.
    add.l   #0, %d0
    | Load zero operands and ADDX — Z should become 1 because result is
    | zero AND pre-Z was 1 before (from the idiom).  First restore Z=1
    | using SUB.L Dn,Dn.
    sub.l   %d7, %d7                  | sets Z=1, X=0
    moveq   #0, %d0
    moveq   #0, %d1
    addx.l  %d0, %d1                  | 0 + 0 + 0 = 0; pre-Z=1 → Z stays 1
    bne     _fail

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
_halt_fail:
    bra     _halt_fail
