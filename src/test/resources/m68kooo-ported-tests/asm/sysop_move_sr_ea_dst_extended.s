| sysop_move_sr_ea_dst_extended.s — directed test for MOVE.W SR,<ea>
| extending coverage to (d16,An), (xxx).W, (xxx).L.  Pre-fix these
| modes silently NOP'd in decode_uop_assemble.v's
| sem_sysop_is_move_sr_ea_src block — only Dn/(An)/-(An)/(An)+ were
| assembled; (d16,An), absolute and indexed modes fell through to
| SYS_NOP that consumed the instruction length but never wrote
| memory.

    .text
    .org 0
    .equ FAIL_ADDR, 0xFFFF0000
    .equ PASS_VAL,  0xC0FFEE00

_start:
    | --- Test 1: MOVE.W SR,(xxx).L ---
    | Pre-fill destination with sentinel.  Snapshot SR AFTER the setup
    | so the snapshot includes the CCR set by the move.l (move.l writes
    | NZ — 0xDEADBEEF is negative non-zero so N=1, others cleared).  A
    | bug-era snapshot taken BEFORE the move.l would mismatch the SR
    | value the subsequent MOVE.W SR,<ea> stores, since SR low byte is
    | the live CCR and the move.l updated it.
    move.l  #0xDEADBEEF, 0x00130000
    move.w  %sr, %d1                  | D1.lo = SR snapshot (post-setup)
    move.w  %sr, 0x00130000           | mode 111 reg 001 — was NOP'd
    move.w  0x00130000, %d2
    cmp.w   %d1, %d2
    bne     _fail_t1

    | --- Test 2: MOVE.W SR,(xxx).W ---
    | Use a low RAM address that fits in .W (sign-extended).
    move.l  #0xDEADBEEF, 0x00000200
    move.w  %sr, %d1                  | re-snapshot — CCR may have changed
    move.w  %sr, 0x0200               | mode 111 reg 000 — was NOP'd
    move.w  0x00000200, %d2
    cmp.w   %d1, %d2
    bne     _fail_t2

    | --- Test 3: MOVE.W SR,(d16,An) ---
    move.l  #0xDEADBEEF, 0x00130010
    move.l  #0x00130000, %a0
    move.w  %sr, %d1                  | re-snapshot
    move.w  %sr, 16(%a0)              | mode 101 — was NOP'd
    move.w  0x00130010, %d2
    cmp.w   %d1, %d2
    bne     _fail_t3

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
_fail:
    move.l  #0x00200000, %a7
    lea     FAIL_ADDR, %a0
    move.l  %d7, (%a0)
1:  bra     1b
