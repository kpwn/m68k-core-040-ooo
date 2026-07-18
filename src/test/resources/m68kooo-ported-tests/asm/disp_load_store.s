| disp_load_store.s — Test for MOVE.L (d16,An),Dn and MOVE.L Dn,(d16,An)
|
| 1. Load 0xDEAD1234 into D0 via MOVE.L #imm
| 2. Set A0 = base address (0x00100010)
| 3. Store D0 to -16(A0) = 0x00100000 using MOVE.L Dn,(d16,An)
| 4. Load back into D1 from -16(A0) = 0x00100000 using MOVE.L (d16,An),Dn
| 5. Store D1 to magic PASS address 0xFFFF0000

    .text
    .org 0

_start:
    move.l  #0xC0FFEE00, %d0        | D0 = payload
    lea     0x00100010, %a0          | A0 = 0x00100010  (base + 16)
    move.l  %d0, -16(%a0)            | store D0 to 0x00100010 - 16 = 0x00100000
    move.l  -16(%a0), %d1            | load from 0x00100000 into D1
    lea     0xFFFF0000, %a1          | A1 = magic PASS address
    move.l  %d1, (%a1)               | store D1 → PASS if == 0xC0FFEE00
_halt:
    stop    #0x2700
    bra     _halt
