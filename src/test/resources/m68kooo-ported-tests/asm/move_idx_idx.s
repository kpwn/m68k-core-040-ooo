| move_idx_idx.s — MOVE with indexed source AND indexed destination.
|
| Q700 ROM 0x4083c1e0:  move.b (0xa,A2,D1.w*8),(0,A3,D0.w)  → vec-4.
| The V2 move-family assembler handles a brief-indexed EA on the src
| OR the dst side, but not both at once.  (d8,An,Xn) on both sides is
| a valid 68040 MOVE EA pair.
|
| PASS sentinel: 0xC0FFEE00.
| FAIL sentinels:
|   0xDEAD0004  vec-4 illegal-instruction trap — THE BUG
|   0xDEAD00B1  MOVE.B (d8,An,Dn.w*8) → (d8,An,Dn.w*1) wrong
|   0xDEAD00B2  MOVE.B (d8,An,Dn.w*1) → (d8,An,Dn.w*1) wrong
|   0xDEAD00B3  MOVE.W (d8,An,Dn.w*2) → (d8,An,Dn.w*4) wrong

    .text
    .org 0

_start:
    lea     0x00010000, %a7
    move.l  #_illegal, 0x00000010

    | ── MOVE.B (0xa,A2,D1.w*8),(0,A3,D0.w*1) — exact ROM shape ──
    move.l  #0x00021000, %a2
    move.l  #0x00022000, %a3
    moveq   #1, %d1
    moveq   #3, %d0
    move.b  #0x5A, 0x00021012           | src EA = A2+0xa+1*8 = 0x21012
    move.b  (0xa,%a2,%d1.w*8), (0,%a3,%d0.w)   | dst EA = A3+0+3 = 0x22003
    move.b  0x00022003, %d2
    cmp.b   #0x5A, %d2
    bne     _f1

    | ── MOVE.B (8,A2,D1.w*1),(4,A3,D0.w*1) — scale 1 both sides ──
    moveq   #5, %d1
    moveq   #6, %d0
    move.b  #0xA5, 0x0002100D           | src EA = A2+8+5 = 0x2100D
    move.b  (8,%a2,%d1.w), (4,%a3,%d0.w)        | dst EA = A3+4+6 = 0x2200A
    move.b  0x0002200A, %d2
    cmp.b   #0xA5, %d2
    bne     _f2

    | ── MOVE.W (8,A2,D1.w*2),(4,A3,D0.w*4) — word, mixed scales ──
    moveq   #2, %d1
    moveq   #2, %d0
    move.w  #0x1234, 0x0002100C         | src EA = A2+8+2*2 = 0x2100C
    move.w  (8,%a2,%d1.w*2), (4,%a3,%d0.w*4)   | dst EA = A3+4+2*4 = 0x2200C
    move.w  0x0002200C, %d2
    cmp.w   #0x1234, %d2
    bne     _f3

    | PASS
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_f1:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD00B1, %d0
    move.l  %d0, (%a0)
_h1:
    bra     _h1

_f2:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD00B2, %d0
    move.l  %d0, (%a0)
_h2:
    bra     _h2

_f3:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD00B3, %d0
    move.l  %d0, (%a0)
_h3:
    bra     _h3

_illegal:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0004, %d0
    move.l  %d0, (%a0)
_h7:
    bra     _h7
