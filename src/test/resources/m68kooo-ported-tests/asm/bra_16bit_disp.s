| bra_16bit_disp.s — BRA with 16-bit displacement form.
|
| Stage D-6 (agent/decode-v2-branches): validates the V2 branch
| assembler's 16-bit-disp path.  Uses `bra.w` to force a +/- 0x1000-ish
| displacement so the assembler picks dddddddd == 0x00 and reads ext1 as
| the signed 16-bit displacement.  If V2 gets the decode wrong we either
| land at the wrong PC (sentinel fails) or crash on an ILLEGAL vec 4.

    .text
    .org 0

_start:
    | Forward BRA.W ~ +0x100 — ext1 carries the 16-bit disp.
    bra.w   _forward_land

    | FAIL path — should never execute.
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
_halt_fail:
    stop    #0x2700
    bra     _halt_fail

    | Pad the gap so the 16-bit disp form is the only valid encoding.
    .fill   0x100, 1, 0x4E71        | NOPs

_forward_land:
    | Backward BRA.W with a negative 16-bit disp to _back_target.
    bra.w   _back_target

_back_anchor:
    | PASS path — reached by the backward BRA.W below.
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    stop    #0x2700
    bra     _halt

    | Pad again so the backward disp is large enough that the assembler
    | can't collapse to the 8-bit form.
    .fill   0x100, 1, 0x4E71        | NOPs

_back_target:
    | Now branch backward to the PASS sentinel path.
    bra.w   _back_anchor
