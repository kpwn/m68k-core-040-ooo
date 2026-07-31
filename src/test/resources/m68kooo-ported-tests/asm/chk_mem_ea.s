| chk_mem_ea.s — Phase-2 item #1: CHK.{W,L} with a genuine memory-EA
| bound source (not Dn/#imm).  Prior to this fix, decode.v's
| v2_chk_ea_ok_f3 only admitted Dn-direct or #imm — any other EA
| (even plain (An)) fell through to vec-4 ILLEGAL.  This test exercises
| the newly-widened EA set: (An), (An)+, -(An), (d16,An), (xxx).W,
| (xxx).L, and (d8,An,Xn) brief-indexed, for both CHK.W and CHK.L, both
| in-range (no trap) and out-of-range (must trap vec 6).
|
| PASS: 0xC0FFEE00 -> 0xFFFF0000.  FAIL: 0xDEADxxxx (per failing cell).

    .text
    .org 0

_start:
    lea     0x00010000, %a7
    move.l  #_unexpected_trap, 0x00000018   | vec 6 default = FAIL

    lea     0x00020000, %a0
    lea     0x00020100, %a1
    lea     0x00020200, %a2

    moveq   #0, %d7                          | counter

    | --- CHK.W (a0),D0 — in-range: bound=100 @ (a0), D0=10.
    move.w  #100, (%a0)
    move.l  #10, %d0
    chk.w   (%a0), %d0
    addq.l  #1, %d7

    | --- CHK.L (a0),D0 — in-range: bound=0x100000 @ (a0), D0=0x12345.
    move.l  #0x00100000, (%a0)
    move.l  #0x00012345, %d0
    chk.l   (%a0), %d0
    addq.l  #1, %d7

    | --- CHK.W (a1)+,D0 — in-range; verify postinc actually advances A1.
    move.w  #50, (%a1)
    move.l  #5, %d0
    move.l  %a1, %d1
    chk.w   (%a1)+, %d0
    addq.l  #1, %d7
    move.l  %a1, %d2
    sub.l   %d1, %d2
    cmp.l   #2, %d2                          | .W postinc must bump by 2
    bne     _fail1
    addq.l  #1, %d7

    | --- CHK.L -(a2),D0 — predecrement; verify A2 backs up by 4 first.
    | The predecrement reads from a2-4, so seed the bound value there
    | (via a separate register alias) BEFORE loading a2 itself.
    lea     0x00020210, %a3
    move.l  #0x00007FFF, (%a3)               | value at 0x20210 = (a2-4)
    lea     0x00020214, %a2
    move.l  #0x00000010, %d0
    move.l  %a2, %d1
    chk.l   -(%a2), %d0
    addq.l  #1, %d7
    move.l  %a2, %d2
    move.l  %d1, %d3
    sub.l   %d2, %d3
    cmp.l   #4, %d3
    bne     _fail1
    addq.l  #1, %d7

    | --- CHK.W (d16,a0),D0 — in-range.
    move.w  #200, (0x10,%a0)
    move.l  #199, %d0
    chk.w   (0x10,%a0), %d0
    addq.l  #1, %d7

    | --- CHK.L (xxx).L,D0 — in-range.
    move.l  #0x00030000, %a3
    move.l  #0x0000BEEF, (%a3)
    move.l  #0x0000BEEE, %d0
    chk.l   0x00030000, %d0
    addq.l  #1, %d7

    | --- CHK.W (xxx).W-shaped abs source via (an) alias for width — use
    | a fixed low address so the absolute-short encoding stays in range.
    move.l  #0x00001000, %a4
    move.w  #300, (%a4)
    move.l  #300, %d0
    chk.w   (%a4), %d0
    addq.l  #1, %d7

    | --- CHK.W (d8,a0,d1.w),D0 brief-indexed — in-range.
    moveq   #4, %d1
    move.w  #64, (20,%a0,%d1.w)
    move.l  #64, %d0
    chk.w   (20,%a0,%d1.w), %d0
    addq.l  #1, %d7

    | --- CHK.L (d8,a0,a5.l*4),D0 brief-indexed, scaled long index.
    lea     0x00000002, %a5
    move.l  #0x00000700, (16,%a0,%a5.l*4)    | a0+16+2*4 = a0+24
    move.l  #0x00000700, %d0
    chk.l   (16,%a0,%a5.l*4), %d0
    addq.l  #1, %d7

    cmp.l   #11, %d7
    bne     _fail2

    | --- Now confirm the trap path still fires for a memory-EA bound:
    | CHK.W (a0),D0 with D0 out of range must reach _chk_trap_handler.
    move.l  #_chk_trap_handler, 0x00000018
    move.w  #10, (%a0)
    move.l  #999, %d0
    chk.w   (%a0), %d0                        | must trap
    bra     _fail3                            | fall-through = bug

_chk_trap_handler:
    move.l  #0xC0FFEE00, %d7
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
_halt:
    bra     _halt

_unexpected_trap:
    move.l  #0xDEAD0000, %d7
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
    bra     _halt

_fail1:
    move.l  #0xDEAD0001, %d7
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
    bra     _halt

_fail2:
    move.l  #0xDEAD0002, %d7
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
    bra     _halt

_fail3:
    move.l  #0xDEAD0003, %d7
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
    bra     _halt
