| addr_reg_disp.s — MOVE.L (d16,An),Dn with signed displacement
|
| Hypothesis: the effective-address computation sign-extends the 16-bit
| displacement to 32 bits before adding to An.  Both positive and
| negative d16 must reach the correct byte offsets.
|
| Layout: A0 points at 0x00100100.
|   Write a known value at 0x00100100 (disp = 0 via (A0))
|   Write a known value at 0x00100200 (+0x100) via (+256,A0)
|   Write a known value at 0x00100000 (-0x100) via (-256,A0)
| Then read each back via MOVE.L (d16,An),Dn with matching disp and
| verify the loaded long matches the stored long.

    .text
    .org 0

_start:
    | Pointer into RAM at a safely-aligned long
    lea     0x00100100, %a0

    | Seed three distinct longs at three offsets
    move.l  #0xAAAABBBB, %d0
    move.l  %d0, (%a0)               | mem[0x00100100] = 0xAAAABBBB
    move.l  #0xCCCCDDDD, %d0
    move.l  %d0, 256(%a0)            | mem[0x00100200] = 0xCCCCDDDD
    move.l  #0xEEEEFFFF, %d0
    move.l  %d0, -256(%a0)           | mem[0x00100000] = 0xEEEEFFFF

    | Read back via (d16,An) and CMP against expectations
    move.l  (%a0), %d1               | D1 = mem[0x00100100] = 0xAAAABBBB
    move.l  #0xAAAABBBB, %d7
    cmp.l   %d7, %d1
    bne     _fail

    move.l  256(%a0), %d2            | D2 = 0xCCCCDDDD
    move.l  #0xCCCCDDDD, %d7
    cmp.l   %d7, %d2
    bne     _fail

    move.l  -256(%a0), %d3           | D3 = 0xEEEEFFFF — negative disp sign-extends
    move.l  #0xEEEEFFFF, %d7
    cmp.l   %d7, %d3
    bne     _fail

_pass:
    lea     0xFFFF0000, %a1
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a1)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a1
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a1)
_halt_fail:
    bra     _halt_fail
