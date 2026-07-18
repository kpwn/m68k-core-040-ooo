| load_use.s — Bring-up test for the load path
|
| 1. Put 0xC0FFEE00 in D0
| 2. Store D0 to RAM at 0x00100000 (must be .L abs, not .W)
| 3. Load it back into D1 from (A0) with An=0x00100000
| 4. Store D1 to the magic TB address 0xFFFF0000 → PASS
|
| Note: we use 0x00100000 rather than 0x00001000 because GAS picks
| (xxx).W (sign-extending 16→32) for small unsigned addresses, and
| our bring-up decoder only handles the (xxx).L absolute form.
|
| Exercises: ALU_MOV with imm32, LEA abs, MOVE.L Dn,(An), MOVE.L (An),Dn,
| CDB wake-up from LSU load (iq_mem waits for pdst of the load), and
| in-order commit of two stores straddling one load.

    .text
    .org 0

_start:
    move.l  #0xC0FFEE00, %d0    | D0 = payload
    lea     0x00100000, %a0     | A0 = RAM scratch
    move.l  %d0, (%a0)          | mem[0x1000] = 0xC0FFEE00
    move.l  (%a0), %d1          | D1 = mem[0x1000] (load uop produces D1)
    lea     0xFFFF0000, %a1     | A1 = magic TB address
    move.l  %d1, (%a1)          | store D1 → PASS
_halt:
    stop    #0x2700
    bra     _halt
