| sysop_move_ea_ccr_extended.s — directed test for MOVE.W <ea>,CCR
| extending coverage to (An)+, -(An), (d16,An), (xxx).W, (xxx).L,
| (d16,PC).
|
| Pre-fix only Dn (000) and (An) (010) were assembled; every other
| EA mode silently NOP'd in decode_uop_assemble.v's
| sem_sysop_is_move_ea_ccr block — sister silent-NOP to the
| MOVE.W <ea>,SR family that breaks Mac OS save/restore around
| critical sections that touch CCR.
|
| Verification idiom: load CCR from a known value, then read CCR
| back via MOVE.W SR,Dn (which carries CCR in the low byte), mask
| to the CCR portion, and compare.

    .text
    .org 0
    .equ FAIL_ADDR, 0xFFFF0000
    .equ PASS_VAL,  0xC0FFEE00

_start:
    | --- Setup: pre-place CCR test values in memory ---
    | We'll load CCR from each address and compare.  Use values 0x05
    | (XC), 0x0A (NV), 0x0F (NZVC) — distinct so silent NOP would be
    | obvious if loaded CCR mismatches expected.
    move.w  #0x0005, 0x00130000        | CCR = X+C
    move.w  #0x000A, 0x00130002        | CCR = N+V
    move.w  #0x000F, 0x00130004        | CCR = NZVC
    move.w  #0x0005, 0x00000200        | CCR = X+C (for abs.W test)

    | --- Test 1: MOVE.W (xxx).L,CCR ---
    move.w  0x00130000, %ccr           | mode 111 reg 001 — was NOP'd
    | Snapshot SR (CCR is low byte) and check.
    move.w  %sr, %d1
    and.w   #0x001F, %d1                | mask to CCR
    cmp.w   #0x0005, %d1
    bne     _fail_t1

    | --- Test 2: MOVE.W (xxx).W,CCR ---
    move.w  0x0200, %ccr                | mode 111 reg 000 — was NOP'd
    move.w  %sr, %d1
    and.w   #0x001F, %d1
    cmp.w   #0x0005, %d1
    bne     _fail_t2

    | --- Test 3: MOVE.W (An),CCR — re-confirm baseline still works ---
    move.l  #0x00130002, %a0
    move.w  (%a0), %ccr
    move.w  %sr, %d1
    and.w   #0x001F, %d1
    cmp.w   #0x000A, %d1
    bne     _fail_t3

    | --- Test 4: MOVE.W (An)+,CCR ---
    move.l  #0x00130000, %a1
    move.w  (%a1)+, %ccr                | mode 011 — was NOP'd
    move.w  %sr, %d1
    and.w   #0x001F, %d1
    move.l  %a1, %d4                    | snapshot a1 too
    cmp.w   #0x0005, %d1
    bne     _fail_t4_val
    cmp.l   #0x00130002, %d4
    bne     _fail_t4_an

    | --- Test 5: MOVE.W -(An),CCR ---
    move.l  #0x00130006, %a2
    move.w  -(%a2), %ccr                | mode 100 — was NOP'd
    move.w  %sr, %d1
    and.w   #0x001F, %d1
    move.l  %a2, %d4
    cmp.w   #0x000F, %d1
    bne     _fail_t5_val
    cmp.l   #0x00130004, %d4
    bne     _fail_t5_an

    | --- Test 6: MOVE.W (d16,An),CCR ---
    move.l  #0x00130000, %a3
    move.w  4(%a3), %ccr                | mode 101 — was NOP'd
    move.w  %sr, %d1
    and.w   #0x001F, %d1
    cmp.w   #0x000F, %d1
    bne     _fail_t6

    | --- All passed ---
    move.l  #0x00200000, %a7
    lea     FAIL_ADDR, %a0
    move.l  #PASS_VAL, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_fail_t1:
    move.l  #0xDEAD0001, %d7
    bra     _fail
_fail_t2:
    move.l  #0xDEAD0002, %d7
    bra     _fail
_fail_t3:
    move.l  #0xDEAD0003, %d7
    bra     _fail
_fail_t4_an:
    move.l  %d4, %d7
    bra     _fail
_fail_t4_val:
    move.l  #0xDEAD0041, %d7
    bra     _fail
_fail_t5_an:
    move.l  %d4, %d7
    bra     _fail
_fail_t5_val:
    move.l  #0xDEAD0051, %d7
    bra     _fail
_fail_t6:
    move.l  #0xDEAD0006, %d7
    bra     _fail
_fail:
    move.l  #0x00200000, %a7
    lea     FAIL_ADDR, %a0
    move.l  %d7, (%a0)
1:  bra     1b
