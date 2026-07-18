| abcd_mem_mem_carry.s — ABCD -(A1),-(A0) with carry
|
| A9 corner: the thing the V2 mem-mem crack almost got wrong —
| producing X=C=1 carry-out must persist for the next ABCD memory
| op, and BCD op must NOT clobber adjacent bytes around the store.
|
| Sequence:
|   1. Seed memory: src byte 0x45 @ 0x00106001
|                   dst byte 0x60 @ 0x00106011
|   2. ABCD -(A1),-(A0)  →  memory[A0-1] = 0x60+0x45+0 = 0xA5 → nibble
|      carry pushes upper to A; unsigned >0x99 → subtract 0xA0 → 0x05,
|      X=C=1.  A1 -= 1; A0 -= 1.
|   3. Chain a second ABCD with src=0x00, dst=0x10, X=1 → 0x11, X=C=0.
|
| CCR observation discipline: any intervening CMP rewrites NZVC, so
| we check BCS/BCC BEFORE the arch-state CMPs.

    .text
    .org 0

_start:
    | ── Clear X to start clean.
    moveq   #0, %d0
    add.l   %d0, %d0                  | X=0

    | ── Seed scratch region with sentinels so we can spot byte-store
    | ── bleed.
    lea     0x00106000, %a2
    move.l  #0xDEADBEEF, (%a2)        | [106000..106003] = DE AD BE EF
    move.l  #0xCAFEBABE, 4(%a2)
    move.l  #0xAABBCCDD, 8(%a2)
    move.l  #0x11223344, 12(%a2)
    move.l  #0x55667788, 16(%a2)      | [106010..106013]

    | Overwrite src byte @ 106001 (inside DEADBEEF) with 0x45.
    move.b  #0x45, 1(%a2)
    | Overwrite dst byte @ 106011 (inside 55667788) with 0x60.
    move.b  #0x60, 17(%a2)

    | ── A1 = 0x106002 (will predec to 0x106001)
    | ── A0 = 0x106012 (will predec to 0x106011)
    lea     0x00106002, %a1
    lea     0x00106012, %a0

    | ── ABCD -(A1),-(A0) — 0x60 + 0x45 + X(0) = 0x05, X=C=1.
    .word   0xC109                    | ABCD -(A1),-(A0)

    | Check C=1 IMMEDIATELY (before any flag-writing op).  BCC jumps if C=0.
    bcc     _fail

    | Save the result byte we just stored — MOVE.B reads memory, which
    | does NOT touch NZVC in a way that matters for subsequent CMPs.
    move.b  (%a0), %d2
    and.l   #0xFF, %d2
    cmp.l   #0x05, %d2
    bne     _fail

    | A1 must be 0x00106001, A0 must be 0x00106011.
    move.l  %a1, %d0
    cmp.l   #0x00106001, %d0
    bne     _fail
    move.l  %a0, %d1
    cmp.l   #0x00106011, %d1
    bne     _fail

    | ── Chain: seed src 0x00 @ 106000, dst 0x10 @ 106010.  With X=1, ──
    | ── expect 0x11.  Re-set X=1 by chaining through another ADD.L
    | ── wrap since our CMPs above cleared C.
    move.l  #0xFFFFFFFF, %d5
    moveq   #1, %d6
    add.l   %d6, %d5                  | wrap → X=C=1

    move.b  #0x00, 0(%a2)             | [106000] = 0x00
    move.b  #0x10, 16(%a2)            | [106010] = 0x10

    lea     0x00106001, %a1
    lea     0x00106011, %a0

    .word   0xC109                    | ABCD -(A1),-(A0), X=1 → 0x11, C=0

    | C=0 expected.
    bcs     _fail

    | Result byte @ 0x00106010 = 0x11.
    move.b  (%a0), %d3
    and.l   #0xFF, %d3
    cmp.l   #0x11, %d3
    bne     _fail

    | Verify predec of A1, A0.
    move.l  %a1, %d7
    cmp.l   #0x00106000, %d7
    bne     _fail
    move.l  %a0, %d7
    cmp.l   #0x00106010, %d7
    bne     _fail

    | Verify adjacent bytes untouched.  After first ABCD, byte@106011=0x05.
    | After intervening setup (move.b #0x10,16(A2)), word@106010 = 10 05 77 88.
    | Second ABCD writes byte@106010 = 0x11 → word becomes 11 05 77 88.
    move.l  16(%a2), %d4
    cmp.l   #0x11057788, %d4
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
