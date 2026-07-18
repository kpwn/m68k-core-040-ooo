| bcd_z_sticky.s — Verify Z-flag sticky semantics on ABCD/SBCD
|
| Per PRM: BCD ops implement "Z is cleared if result non-zero; otherwise
| unchanged".  Equivalent expression: Z' = Z_prev AND (result == 0).
|
| Two scenarios to validate:
|   1. Start with Z=1 (via CMP.L of a reg with itself), perform ABCD
|      producing a zero result → Z must stay 1.
|   2. Start with Z=0 (via CMP with differing values), perform ABCD
|      producing a zero result → Z must stay 0.

    .text
    .org 0

_start:
    | ── Scenario 1: Z_prev = 1, ABCD 0+0+X=0 → 0.  Expect Z=1. ──
    | First clear X to 0.
    moveq   #0, %d7
    add.l   %d7, %d7                  | X=0

    | Set Z=1 by CMP D0,D0 (always equal).
    moveq   #0, %d0
    cmp.l   %d0, %d0                  | Z=1 now

    | ABCD with 0+0+0 → result byte = 0, Z should remain 1 (sticky).
    moveq   #0, %d1                   | moveq leaves Z (could set) — but we
                                      | want Z=1 preserved; MOVEQ DOES write Z!
                                      | So re-establish Z=1 after this.
    cmp.l   %d0, %d0                  | Z=1
    abcd    %d1, %d0                  | 0+0+0 = 0, Z should remain 1
    bne     _fail                     | Z=1 required: BEQ would take, BNE shouldn't

    | ── Scenario 2: Z_prev = 0, ABCD 0+0+X=0 → 0.  Expect Z=0. ──
    | Force Z=0 via a non-equal CMP.
    move.l  #0x00000001, %d2
    moveq   #0, %d3
    cmp.l   %d3, %d2                  | 1 != 0 → Z=0
    | X must still be 0 (CMP doesn't touch X).  Now ABCD 0+0+0 = 0.
    moveq   #0, %d0
    moveq   #0, %d1
    cmp.l   %d3, %d2                  | re-establish Z=0 after the moveq above
    abcd    %d1, %d0                  | 0+0+0 = 0, Z should remain 0
    beq     _fail                     | Z=0 required: BEQ must NOT take

    | ── Scenario 3: Z_prev = 1, ABCD nonzero result → Z=0 (cleared). ──
    moveq   #0, %d7
    add.l   %d7, %d7                  | X=0
    moveq   #0, %d0
    cmp.l   %d0, %d0                  | Z=1
    move.l  #0x00000012, %d0
    move.l  #0x00000034, %d1
    abcd    %d1, %d0                  | 0x12+0x34 = 0x46, Z must clear
    beq     _fail                     | Z=0 expected

    | ── Scenario 4: SBCD with Z_prev=1 and zero result → Z=1 ──
    moveq   #0, %d7
    add.l   %d7, %d7                  | X=0
    move.l  #0x00000034, %d0
    move.l  #0x00000034, %d1
    cmp.l   %d0, %d0                  | Z=1
    sbcd    %d1, %d0                  | 0x34-0x34-0 = 0, Z stays 1
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
