| move16_basic.s — MOVE16 (Ax)+,(Ay)+ (68040 cache-line move)
|
| MOVE16 is a 68040 INTEGER instruction that happens to live in F-line
| encoding space (opword 0xF620|Ax, ext word carries Ay in [14:12]).
| The Q700 ROM's BlockMove uses it for every large copy (e.g.
| 0x40884482).  Before 2026-07-15 it was not decoded at all and fell
| to the vec-11 F-line trap — sending a plain memcpy into the ROM's
| FPU software package (which real silicon never does, since real 040
| executes MOVE16 in hardware) and triggering the live-HW Sad Mac 02
| (see fpsp_packed_kernel_e2e.s for the companion FPSP-side story).
|
| Semantics locked to Musashi (tb/models/musashi/m68k_in.c, move16):
|   * four LONG transfers (Ax)+0/4/8/12 → (Ay)+0/4/8/12 using the
|     ORIGINAL register values — NO 16-byte alignment masking (real
|     040 aligns; Musashi does not; Musashi is this repo's golden
|     model).
|   * both Ax and Ay += 16 afterwards, unconditionally — Ax==Ay nets
|     a single +32'd register.
|   * no condition-code effects.
|   * only the (Ax)+,(Ay)+ form (F620) exists in Musashi; the
|     F600/F608/F610/F618 absolute forms stay on the F-line trap.
|
| Stages:
|   1. Aligned 16-byte copy: data, guards, Ax/Ay post-increments.
|   2. Long-aligned but NOT 16-byte-aligned addresses: raw-address
|      copy per Musashi (no masking).
|   3. Ax == Ay: memory self-copy is a no-op image-wise, register
|      advances by 32.
|   4. CCR is untouched.
|
| PASS: all assertions hold.  FAIL codes at 0xFFFF0000:
|   0xBADBAD00 — data/register assertion failure
|   0xBAD0000B — MOVE16 still F-line traps (vec 11)
|   0xBAD00004 — unexpected illegal-instruction trap

    .text
    .org 0

_start:
    lea     0x00011000, %a7
    move.l  #_handler4,  0x00000010
    move.l  #_handler11, 0x0000002C

    | ── Stage 1: aligned copy ────────────────────────────────────────
    lea     0x00020000, %a0
    move.l  #0x11111111, (%a0)+
    move.l  #0x22222222, (%a0)+
    move.l  #0x33333333, (%a0)+
    move.l  #0x44444444, (%a0)+
    | Destination guards: one long below and one above the window.
    move.l  #0xCA0FEE0A, 0x000200FC
    move.l  #0xCA0FEE0B, 0x00020110
    | Poison the destination window.
    lea     0x00020100, %a1
    move.l  #0xDEADBEEF, (%a1)
    move.l  #0xDEADBEEF, 4(%a1)
    move.l  #0xDEADBEEF, 8(%a1)
    move.l  #0xDEADBEEF, 12(%a1)
    lea     0x00020000, %a0
    .short  0xF620, 0x9000          | move16 (%a0)+,(%a1)+
    | Post-increments: A0 += 16, A1 += 16.
    lea     0x00020010, %a2
    cmp.l   %a2, %a0
    bne     _fail
    lea     0x00020110, %a2
    cmp.l   %a2, %a1
    bne     _fail
    | Data landed, guards intact.
    cmp.l   #0x11111111, 0x00020100
    bne     _fail
    cmp.l   #0x22222222, 0x00020104
    bne     _fail
    cmp.l   #0x33333333, 0x00020108
    bne     _fail
    cmp.l   #0x44444444, 0x0002010C
    bne     _fail
    cmp.l   #0xCA0FEE0A, 0x000200FC
    bne     _fail
    cmp.l   #0xCA0FEE0B, 0x00020110
    bne     _fail

    | ── Stage 2: long-aligned, NOT 16-byte-aligned (Musashi: raw) ────
    lea     0x00020204, %a0
    move.l  #0x55AA55AA, (%a0)
    move.l  #0x66BB66BB, 4(%a0)
    move.l  #0x77CC77CC, 8(%a0)
    move.l  #0x88DD88DD, 12(%a0)
    lea     0x00020304, %a1
    move.l  #0xDEADBEEF, (%a1)
    move.l  #0xDEADBEEF, 12(%a1)
    | Guard the 16-byte-aligned-down long a masking implementation
    | would have written instead.
    move.l  #0xCA0FEE0C, 0x00020300
    .short  0xF620, 0x9000          | move16 (%a0)+,(%a1)+
    lea     0x00020214, %a2
    cmp.l   %a2, %a0
    bne     _fail
    lea     0x00020314, %a2
    cmp.l   %a2, %a1
    bne     _fail
    cmp.l   #0x55AA55AA, 0x00020304
    bne     _fail
    cmp.l   #0x88DD88DD, 0x00020310
    bne     _fail
    cmp.l   #0xCA0FEE0C, 0x00020300 | no aligned-down write happened
    bne     _fail

    | ── Stage 3: Ax == Ay — register nets +32 (Musashi) ─────────────
    lea     0x00020400, %a0
    move.l  #0x0BADF00D, (%a0)
    move.l  #0x0BADF00D, 12(%a0)
    .short  0xF620, 0x8000          | move16 (%a0)+,(%a0)+
    lea     0x00020420, %a2         | +16 twice
    cmp.l   %a2, %a0
    bne     _fail
    cmp.l   #0x0BADF00D, 0x00020400 | image unchanged
    bne     _fail
    cmp.l   #0x0BADF00D, 0x0002040C
    bne     _fail

    | ── Stage 4: CCR untouched ──────────────────────────────────────
    | (Checked via conditional branches — MOVE from CCR is not decoded
    | in this core yet.)
    lea     0x00020000, %a0
    lea     0x00020100, %a1
    move.w  #0x001F, %ccr           | set X,N,Z,V,C
    .short  0xF620, 0x9000          | move16 (%a0)+,(%a1)+
    bcc     _fail                   | C still set
    bvc     _fail                   | V still set
    bne     _fail                   | Z still set
    bpl     _fail                   | N still set
    moveq   #0, %d0                 | X still set: 0 +x 0 + X = 1
    addx.b  %d0, %d0
    cmp.b   #1, %d0
    bne     _fail

    lea     0xFFFF0000, %a1
    move.l  #0xC0FFEE00, %d1
    move.l  %d1, (%a1)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a1
    move.l  #0xBADBAD00, %d1
    move.l  %d1, (%a1)
_fhlt:
    bra     _fhlt

_handler4:
    lea     0xFFFF0000, %a1
    move.l  #0xBAD00004, %d1
    move.l  %d1, (%a1)
    bra     _fhlt
_handler11:
    lea     0xFFFF0000, %a1
    move.l  #0xBAD0000B, %d1
    move.l  %d1, (%a1)
    bra     _fhlt
