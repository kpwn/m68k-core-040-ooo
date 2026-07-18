| branch_skip_load.s — Branch must squash a wrong-path load
|
| Pre-seed memory: scratch holds 0xBAD0BAD0 (the "wrong" value).
| Path A (skipped by BRA): would load 0xBAD0BAD0 into D7.
| Path B (taken):          loads from a different address holding the
|                          PASS payload 0xC0FFEE00.
|
| If the BRA fails to squash the speculative load (or if the load
| commits before flush), D7 ends up 0xBAD0BAD0 and we FAIL.

    .text
    .org 0

_start:
    | seed scratch[0] = 0xBAD0BAD0  (wrong value)
    lea     0x00100000, %a0
    move.l  #0xBAD0BAD0, %d0
    move.l  %d0, (%a0)

    | seed scratch[+0x20] = 0xC0FFEE00 (PASS payload)
    lea     0x00100020, %a1
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a1)

    bra     _good

    | wrong path — load the BAD value
    move.l  (%a0), %d7
    bra     _store

_good:
    move.l  (%a1), %d7          | correct path: D7 = 0xC0FFEE00

_store:
    | verify D7 == 0xC0FFEE00 (i.e. wrong-path load did not happen)
    move.l  #0xC0FFEE00, %d2
    cmp.l   %d2, %d7
    bne     _fail

    lea     0xFFFF0000, %a2
    move.l  %d7, (%a2)          | PASS store (D7 already holds payload)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a2
    move.l  #0xDEADBEEF, %d3
    move.l  %d3, (%a2)
_halt_fail:
    bra     _halt_fail
