| elim_move_ccr.s — CCR semantics of MOVE.L Dn,Dm under elim.
|
| 68k PRM: MOVE sets N/Z from source value, V=0, C=0, X unchanged.
| Zero-idiom writes N=0,Z=1,V=0,C=0 (and X=0 for SUB Dn,Dn).
|
| Test 1: MOVE a positive value — N=0, Z=0.
| Test 2: MOVE a negative value — N=1, Z=0.
| Test 3: MOVE zero — N=0, Z=1.
| Test 4: MOVEQ #0 — N=0, Z=1.
| Test 5: SUB Dn,Dn — N=0, Z=1.
|
| Each assertion uses `beq`/`bne` on the CCR immediately after the
| elim'd move / zero-idiom, so stale flags would fail fast.

    .text
    .org 0

_start:
    | Prepare a known SR = 0x2000 (supervisor, CCR = 0).
    | (We're reset in supervisor, X initially 0.)

    | --- Test 1: positive value, N=0 Z=0 V=0 C=0 ---
    move.l  #0x00001000, %d0
    move.l  %d0, %d1              | elim — MUST write N=0, Z=0.
    bmi     _fail                 | N set → fail
    beq     _fail                 | Z set → fail

    | --- Test 2: negative value, N=1 Z=0 ---
    move.l  #0x80000000, %d0
    move.l  %d0, %d1
    bpl     _fail                 | N clear → fail
    beq     _fail                 | Z set → fail

    | --- Test 3: zero value via MOVE, N=0 Z=1 ---
    move.l  #0, %d0               | (this is MOVEQ #0, zero-idiom — N=0, Z=1)
    move.l  %d0, %d2              | elim'd MOVE — MUST write N=0, Z=1.
    bmi     _fail
    bne     _fail

    | --- Test 4: MOVEQ #0, N=0 Z=1 ---
    move.l  #0xFFFFFFFF, %d3
    moveq   #0, %d3
    bmi     _fail
    bne     _fail
    | And the reg must actually be 0.
    cmp.l   #0, %d3
    bne     _fail

    | --- Test 5: SUB Dn,Dn, N=0 Z=1 ---
    move.l  #0x7FFFFFFF, %d4
    sub.l   %d4, %d4
    bmi     _fail
    bne     _fail
    cmp.l   #0, %d4
    bne     _fail

    | --- Test 6: MOVE Dn,Dn (self-alias) must still update CCR from Dn ---
    move.l  #0x87654321, %d5
    move.l  %d5, %d5              | self-alias MOVE; N=1, Z=0 from 0x87654321
    bpl     _fail
    beq     _fail
    cmp.l   #0x87654321, %d5
    bne     _fail

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d6
    move.l  %d6, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d6
    move.l  %d6, (%a0)
_halt_fail:
    bra     _halt_fail
