| link_a7_pushes_postdecrement.s — LINK A7,#d pushes the POST-decrement A7.
|
| PRM §4.133 defines LINK as:
|     SP - 4 -> SP ;  An -> (SP) ;  SP -> An ;  SP + d -> SP
| The decrement happens FIRST, so when An IS A7 the value pushed is the
| ALREADY-decremented SP, not its original value.
|
| Musashi has a dedicated `link, 16, ., a7` handler that agrees:
|     REG_A[7] -= 4;  m68ki_write_32(REG_A[7], REG_A[7]);
|
| REGRESSION CONTEXT: the generic 3-phase crack read An as the store
| data, sampled BEFORE the predecrement writeback, so LINK A7 pushed the
| PRE-decrement SP.  The final A7 was correct either way, which is
| exactly why this corrupted silently — only the pushed longword was
| wrong.  Measured pre-fix: `lea 0x80000,%a7 ; link %a7,#-16` wrote
| 0x00080000 at 0x0007FFFC where Musashi writes 0x0007FFFC.
|
| An != A7 was already correct and is re-checked at the end so a fix
| cannot regress it.
|
| Cross-checked against Musashi v4.60 (68040 mode): states matched.

    .text
    .org 0

_start:
    lea     0x00010000, %a7

    | ── LINK.W A7,#-16 ─────────────────────────────────────────────
    | A7 = 0x00080000
    |   A7 -= 4          -> 0x0007FFFC
    |   mem[0x0007FFFC] = 0x0007FFFC   (the POST-decrement value)
    |   A7 += -16        -> 0x0007FFEC
    move.l  #0x99999999, %d0
    lea     0x00080000, %a7
    move.l  %d0, -4(%a7)              | poison the push slot first
    lea     0x00080000, %a7
    link    %a7, #-16
    move.l  %a7, %d0                  | final A7
    move.l  0x0007FFFC, %d1           | the pushed longword
    lea     0x00010000, %a7           | restore a sane stack

    cmp.l   #0x0007FFEC, %d0
    bne     _fail
    cmp.l   #0x0007FFFC, %d1          | NOT 0x00080000
    bne     _fail

    | ── LINK.L A7,#-32 ─────────────────────────────────────────────
    | Same shape, 32-bit displacement.  Verified independently of the
    | .W case (they share a push slot if you reuse one address, which
    | would let a broken .L hide behind a correct .W).
    move.l  #0x88888888, %d0
    lea     0x00081000, %a7
    move.l  %d0, -4(%a7)              | poison 0x00080FFC
    lea     0x00081000, %a7
    link.l  %a7, #-32
    move.l  %a7, %d0
    move.l  0x00080FFC, %d1
    lea     0x00010000, %a7

    cmp.l   #0x00080FDC, %d0          | 0x00080FFC - 32
    bne     _fail
    cmp.l   #0x00080FFC, %d1          | NOT 0x00081000
    bne     _fail

    | ── Regression guard: LINK.W A6,#-8 (An != A7) still pushes A6 ──
    move.l  #0x13571357, %a6
    lea     0x00082000, %a7
    link    %a6, #-8
    move.l  %a7, %d0                  | 0x00081FFC - 8
    move.l  0x00081FFC, %d1           | pushed = OLD A6
    move.l  %a6, %d2                  | A6 = new A7 before the add
    lea     0x00010000, %a7

    cmp.l   #0x00081FF4, %d0
    bne     _fail
    cmp.l   #0x13571357, %d1
    bne     _fail
    cmp.l   #0x00081FFC, %d2
    bne     _fail

    | ── Regression guard: LINK.L A5,#-64 (An != A7) ────────────────
    move.l  #0x2468ACE0, %a5
    lea     0x00083000, %a7
    link.l  %a5, #-64
    move.l  %a7, %d0
    move.l  0x00082FFC, %d1
    move.l  %a5, %d2
    lea     0x00010000, %a7

    cmp.l   #0x00082FBC, %d0          | 0x00082FFC - 64
    bne     _fail
    cmp.l   #0x2468ACE0, %d1
    bne     _fail
    cmp.l   #0x00082FFC, %d2
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
