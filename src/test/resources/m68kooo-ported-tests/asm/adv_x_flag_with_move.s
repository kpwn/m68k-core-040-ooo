| adv_x_flag_with_move.s — X flag must survive a MOVE interposed between ADD and ADDX
|
| ASSUMPTION TESTED (decode.v flags_wr mask vs M68000PRM §3.3):
|   The 68040 MOVE family (MOVE, MOVEQ, MOVE Dn,Dm, MOVE #,Dn, MOVE (An),Dn,
|   MOVE.L (d,An),Dn, ...) clears N,Z,V,C but preserves X.  The decode.v
|   sets flags_wr for MOVE to include NZVC (see BUG_move_ccr_c_flag.md);
|   the X bit must stay UNWRITTEN so that ADDX after MOVE still sees
|   the X bit set by a prior ADD.
|
| ATTACK:
|   ADD sets X=C=1 on overflow.  Then a MOVE.L sits between ADD and
|   ADDX.  The ADDX must add X=1 to its result.
|
|   Programming pattern:
|     move.l #0xFFFFFFFF, %d0
|     move.l #1, %d1
|     add.l  %d1, %d0        ; d0=0, X=C=1
|     move.l #42, %d2         ; MOVE — must leave X alone
|     addx.l %d1, %d2         ; d2 = 42 + 1 + X(1) = 44
|
|   Expected: d2 = 44 in both models.
|   If decode mistakenly includes X in MOVE's flags_wr, ADDX sees X=0 → d2=43.
|
| FAILURE: d2=43 (X got cleared by MOVE).
|
| Note: this targets the SAME decode.v flags_wr class already fingered
|   by BUG_move_ccr_c_flag.md.  That bug fixes MOVE to write C; this
|   test checks the SIBLING invariant that MOVE does NOT write X.
|   A fix that flips 5'b01110 → 5'b11111 (all-write) would break us.

    .text
    .org 0

_start:
    lea     0x00020000, %a7

    move.l  #0xFFFFFFFF, %d0
    move.l  #0x00000001, %d1
    add.l   %d1, %d0                | d0=0, X=C=1, Z=1

    move.l  #42, %d2                | MOVE — must preserve X

    move.l  #0, %d1                 | make the ADDX deterministic
    addx.l  %d1, %d2                | d2 = 42 + 0 + X(1) = 43 if X preserved, 42 if X cleared

    cmp.l   #43, %d2
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
_halt_f:
    bra     _halt_f
