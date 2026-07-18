| movem_an_in_list.s — MOVEM with the base An inside its own register
| list, all Musashi-pinned semantics (fuzz probes 2026-07-15):
|   1. predec store  : the ORIGINAL An value is stored (not An-N*stride)
|   2. postinc load  : final An = orig_An + N*stride (writeback wins
|                      over the loaded value)
|   3. (An) load     : a phase that loads An must not corrupt later
|                      transfer addresses (base snapshot)
|   4. .W predec store: original An low word stored
|   5. .W postinc load: sign-extended data load + writeback-wins
|
| PASS: 0xC0FFEE00.  FAIL: 0xDEADBEEF.

    .text
    .org 0

_start:
    lea     0x00010000, %a7

    | ── Case 1: MOVEM.L %d1/%a2, -(%a2) ──────────────────────────
    | Reversed mask order stores a2 first (higher address), then d1.
    lea     0x00020000, %a2
    move.l  #0x11111111, %d1
    movem.l %d1/%a2, -(%a2)
    cmpa.l  #0x0001FFF8, %a2          | writeback = orig - 8
    bne     _fail
    move.l  0x0001FFFC, %d0
    cmp.l   #0x00020000, %d0          | ORIGINAL a2 stored
    bne     _fail
    move.l  0x0001FFF8, %d0
    cmp.l   #0x11111111, %d0
    bne     _fail

    | ── Case 2: MOVEM.L (%a3)+, %d2/%a3 ──────────────────────────
    lea     0x00021000, %a3
    move.l  #0xAAAA5555, 0x00021000
    move.l  #0x00000123, 0x00021004
    movem.l (%a3)+, %d2/%a3
    cmpa.l  #0x00021008, %a3          | writeback wins over loaded 0x123
    bne     _fail
    cmp.l   #0xAAAA5555, %d2
    bne     _fail

    | ── Case 3: MOVEM.L (%a0), %a0/%a3 ───────────────────────────
    | a0 is loaded by the FIRST transfer; the second transfer's
    | address must still use the original a0.
    lea     0x00022000, %a0
    move.l  #0x00023000, 0x00022000
    move.l  #0x11112222, 0x00022004
    move.l  #0x33334444, 0x00023004
    movem.l (%a0), %a0/%a3
    cmpa.l  #0x00023000, %a0
    bne     _fail
    cmpa.l  #0x11112222, %a3          | from [orig_a0+4], NOT [new_a0+4]
    bne     _fail

    | ── Case 4: MOVEM.W %d1/%a1, -(%a1) ──────────────────────────
    lea     0x00024000, %a1
    movem.w %d1/%a1, -(%a1)
    cmpa.l  #0x00023FFC, %a1
    bne     _fail
    move.w  0x00023FFE, %d0
    cmp.w   #0x4000, %d0              | ORIGINAL a1 low word stored
    bne     _fail
    move.w  0x00023FFC, %d0
    cmp.w   #0x1111, %d0
    bne     _fail

    | ── Case 5: MOVEM.W (%a4)+, %d3/%a4 ──────────────────────────
    lea     0x00025000, %a4
    move.w  #0x8000, 0x00025000
    move.w  #0x0042, 0x00025002
    movem.w (%a4)+, %d3/%a4
    cmpa.l  #0x00025004, %a4          | writeback wins
    bne     _fail
    cmp.l   #0xFFFF8000, %d3          | .W load sign-extends
    bne     _fail

    | PASS
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
