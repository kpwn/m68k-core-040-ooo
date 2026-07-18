| divl_sz0_dual_dest_by_zero.s — DIV.L SZ=0 Dq!=Dr /0 exception (D-9f)
|
| PRM §4.43 states that on divide-by-zero:
|   - vector 5 (integer divide-by-zero) is taken
|   - Dq and Dr are left unchanged
|   - CCR is unchanged
|
| This test validates that the D-9f dual-dst path properly rolls back
| both Dq's and Dr's phys allocations via the RAT's exception rollback
| when mul_div.v's /0 immediate-fire path raises vec 5.

    .text
    .org 0

_start:
    | Set up exception vector 5 → _divzero_handler.
    move.l  #_divzero_handler, 0x14
    | VBR stays at 0 (default) so vector 5 lives at 0x14.

    | Stamp Dq and Dr with recognisable values.
    move.l  #0xCAFEBABE, %d0           | Dq before DIV
    move.l  #0xDEADBEEF, %d2           | Dr before DIV
    move.l  #0,          %d1           | divisor = 0

    | Trigger /0 with DIVUL.L dual-dest.  Hardware raises vec 5 and
    | (per PRM) leaves Dq=D0 and Dr=D2 unchanged.
    divul.l %d1, %d2:%d0               | should trap → _divzero_handler
    bra     _fail                      | trap-return path lands in _divzero_handler

_divzero_handler:
    | On entry: Dq/Dr must still be their pre-DIV values.
    cmp.l   #0xCAFEBABE, %d0
    bne     _fail
    cmp.l   #0xDEADBEEF, %d2
    bne     _fail

    | PASS.
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
    bra     _halt
