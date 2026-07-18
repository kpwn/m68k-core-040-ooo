| bfexts_static_dn.s — Stage D-8 directed: static BFEXTS on Dn.
|
| Corner: sign-extension on extraction.  BFEXTS treats the extracted
| field as a signed quantity of `width` bits, sign-extends to 32-bit
| destination.  V2 must drive ALU_BFEXT (alias for BFEXTS) — not
| ALU_BFEXTU — and the destination Dn from ext1[14:12].
|
| Scenarios:
|   1. BFEXTS D0{#0:#8},D1 — extract top byte signed.
|      D0 = 0x80FFFFFF → top byte = 0x80 (signed -128).
|      D1 = 0xFFFFFF80 (sign-extended).
|      N=1 (bit 31 of the POSITIONED field which is bit 31 of D0 = 1).
|
|   2. BFEXTS D0{#4:#4},D2 — extract bits 4..7 signed.
|      D0 = 0x0F000000 → bits 4..7 = 0xF (signed -1).
|      D2 = 0xFFFFFFFF.
|
|   3. BFEXTS D0{#0:#8},D3 — positive byte, top = 0x7F.
|      D0 = 0x7F000000.  D3 = 0x0000007F.  N=0.
|
| PASS sentinel: 0xC0FFEE00 → 0xFFFF0000.

    .text
    .org 0
_start:
    | Scenario 1 — top byte signed extract of 0x80
    move.l  #0x80ffffff, %d0
    bfexts  %d0{#0:#8}, %d1
    move.l  #0xffffff80, %d2
    cmp.l   %d2, %d1
    beq     1f
    bra     _fail

1:
    | Scenario 2 — mid-nibble F signed extract
    move.l  #0x0f000000, %d0
    bfexts  %d0{#4:#4}, %d2
    move.l  #0xffffffff, %d3
    cmp.l   %d3, %d2
    beq     2f
    bra     _fail

2:
    | Scenario 3 — top byte signed extract of 0x7F (positive)
    move.l  #0x7f000000, %d0
    bfexts  %d0{#0:#8}, %d3
    move.l  #0x0000007f, %d4
    cmp.l   %d4, %d3
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
