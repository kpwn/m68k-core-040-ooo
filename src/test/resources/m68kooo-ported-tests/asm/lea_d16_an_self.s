| lea_d16_an_self.s — regression: LEA (d16,An),An where src An == dst An
|
| Reproduces a divergence found by MAME-vs-RTL PC sampling at ROM PC
| 0x3166: `lea ($1c00,A2),A2`.  The full Q700 boot trace shows RTL
| never surfaces this PC in boundary_pc — i.e. the LEA appears to be
| dropped or coalesced.  This isolates the case: LEA (d16,An),An_dst
| where An_dst == An (same arch reg).
|
| Expected: A2 = 0x10000 + 0x1c00 = 0x11c00.

    .text
    .org 0

_start:
    movea.l #0x00010000, %a2
    lea     0x1c00(%a2), %a2          | A2 should now be 0x11c00
    cmp.l   #0x00011c00, %a2
    bne     _fail

    | Also test the no-elim case where dst != src
    movea.l #0x00020000, %a3
    lea     0x0080(%a3), %a4          | A4 = 0x20080
    cmp.l   #0x00020080, %a4
    bne     _fail

    | And the 0-displacement self-form
    movea.l #0x00030000, %a5
    lea     0(%a5), %a5
    cmp.l   #0x00030000, %a5
    bne     _fail

    | ROM-context match: MOVEA.L (d16,An),An_dst followed by LEA (d16,An),An_dst
    | This is the exact pattern at ROM 0x3162-0x3166:
    |   movea.l (8,A0), A2     ; load A2 from memory
    |   lea     ($1c00,A2), A2 ; A2 = A2 + $1c00 (same arch reg)
    | Seed mem so A0 + 8 = $50000 (loaded into A2)
    movea.l #0x00104000, %a0   | A0 will be base for the load
    move.l  #0x00050000, %d0
    move.l  %d0, 8(%a0)        | mem[A0+8] = 0x50000
    movea.l 8(%a0), %a2        | A2 = 0x50000  (matches ROM 0x3162)
    lea     0x1c00(%a2), %a2   | A2 = 0x51c00  (matches ROM 0x3166)
    cmp.l   #0x00051c00, %a2
    bne     _fail

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
