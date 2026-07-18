| dual_lsu_two_loads.s — Two independent LOADs issued same cycle
|
| Purpose: directed corner for task #217 dual-LSU lane.  Exercises
|   two independent loads from different addresses with no shared
|   register operand.  On a dual-LSU core both lanes should accept
|   and service the loads concurrently; on a single-LSU core they
|   serialise through one port.
|
| Correctness sentinel: both loads must produce the pre-seeded
|   distinct values.  On single-LSU this passes; on dual-LSU it
|   must continue to pass — this test is a regression guard for
|   the dual-LSU wiring (same addresses, independent ports).

    .text
    .org 0

_start:
    | Seed two independent memory locations.
    lea     0x00100000, %a0
    lea     0x00200000, %a1
    move.l  #0xCAFEBABE, %d0
    move.l  %d0, (%a0)
    move.l  #0x12345678, %d0
    move.l  %d0, (%a1)

    | Flush a few cycles so commit drains.
    nop
    nop
    nop
    nop

    | Two parallel independent LOADs.  D1 ← [A0], D2 ← [A1].
    | Different base regs, different physical pages, independent
    | destinations.  Post-dual-LSU this is a 2/cycle window.
    move.l  (%a0), %d1
    move.l  (%a1), %d2

    | Check D1 == 0xCAFEBABE.
    move.l  #0xCAFEBABE, %d3
    cmp.l   %d3, %d1
    bne     _fail

    | Check D2 == 0x12345678.
    move.l  #0x12345678, %d3
    cmp.l   %d3, %d2
    bne     _fail

    | PASS
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADDEAD, %d0
    move.l  %d0, (%a0)

_halt:
    stop    #0x2700
    bra     _halt
