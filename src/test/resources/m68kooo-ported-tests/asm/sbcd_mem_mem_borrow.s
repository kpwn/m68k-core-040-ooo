| sbcd_mem_mem_borrow.s — SBCD -(A1),-(A0) with borrow
|
| A9 corner: SBCD mem-mem reads src first, dst second, writes the
| result byte back with borrow via X=C carrying into the NEXT op.
|
|   dst - src - X  with nibble adjust.
|
| First op: dst=0x20, src=0x35, X=0 → 0x20-0x35 = 0x85 (with wrap), X=C=1.
| Chain:    dst=0x50, src=0x10, X=1 → 0x50-0x10-1 = 0x39, X=C=0.

    .text
    .org 0

_start:
    moveq   #0, %d0
    add.l   %d0, %d0                  | X=0

    lea     0x00106000, %a2
    move.l  #0xDEADBEEF, (%a2)
    move.l  #0xCAFEBABE, 4(%a2)
    move.l  #0x55667788, 16(%a2)      | [106010..106013]

    | src byte 0x35 @ 0x106001, dst byte 0x20 @ 0x106011.
    move.b  #0x35, 1(%a2)
    move.b  #0x20, 17(%a2)

    lea     0x00106002, %a1
    lea     0x00106012, %a0

    | SBCD -(A1),-(A0) — 0x20 - 0x35 - X(0) = 0x85, X=C=1.
    | Opword 0x8109.
    .word   0x8109                    | SBCD -(A1),-(A0)

    | C=1 check first.
    bcc     _fail

    | Byte @ 0x106011 = 0x85.
    move.b  (%a0), %d2
    and.l   #0xFF, %d2
    cmp.l   #0x85, %d2
    bne     _fail

    | A-register post-condition.
    move.l  %a1, %d0
    cmp.l   #0x00106001, %d0
    bne     _fail
    move.l  %a0, %d1
    cmp.l   #0x00106011, %d1
    bne     _fail

    | ── Chain with X=1: set X, then SBCD 0x50 - 0x10 - 1 = 0x39, X=C=0. ──
    move.l  #0xFFFFFFFF, %d5
    moveq   #1, %d6
    add.l   %d6, %d5                  | wrap → X=C=1

    move.b  #0x10, 0(%a2)             | [106000] = 0x10
    move.b  #0x50, 16(%a2)            | [106010] = 0x50

    lea     0x00106001, %a1
    lea     0x00106011, %a0

    .word   0x8109                    | SBCD -(A1),-(A0) with X=1 → 0x39

    | C=0 check first.
    bcs     _fail

    | Byte @ 0x106010 = 0x39.
    move.b  (%a0), %d3
    and.l   #0xFF, %d3
    cmp.l   #0x39, %d3
    bne     _fail

    | Verify adjacent byte @ 0x106013 = 0x88 (untouched).
    move.b  3(%a0), %d4
    and.l   #0xFF, %d4
    cmp.l   #0x88, %d4
    bne     _fail

_pass:
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
