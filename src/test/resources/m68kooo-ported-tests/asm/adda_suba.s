| adda_suba.s — Test for ADDA.L and SUBA.L
|
| ADDA.L Dn,An adds Dn to An without affecting CCR.
| SUBA.L Dn,An subtracts Dn from An without affecting CCR.
|
| 1. A0 = 0x00100000 (via LEA)
| 2. D0 = 0x00000100
| 3. ADDA.L D0,A0 → A0 = 0x00100100
| 4. SUBA.L D0,A0 → A0 = 0x00100000  (back to original)
| 5. Store a value to (A0) to prove A0 is correct, read it back
| 6. Store PASS sentinel to 0xFFFF0000

    .text
    .org 0

_start:
    lea     0x00100000, %a0          | A0 = 0x00100000
    move.l  #0x00000100, %d0        | D0 = 0x100
    adda.l  %d0, %a0                 | A0 = 0x00100100
    suba.l  %d0, %a0                 | A0 = 0x00100000  (back)
    | Now store a known value at (A0) = 0x00100000 and load it back
    move.l  #0xC0FFEE00, %d1        | D1 = PASS value
    move.l  %d1, (%a0)               | mem[0x100000] = 0xC0FFEE00
    move.l  (%a0), %d2               | D2 = mem[0x100000]
    lea     0xFFFF0000, %a1          | A1 = magic PASS address
    move.l  %d2, (%a1)               | PASS if D2 == 0xC0FFEE00
_halt:
    stop    #0x2700
    bra     _halt
