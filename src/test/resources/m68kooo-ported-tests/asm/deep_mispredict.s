| deep_mispredict.s — Mispredicted branch with many in-flight ops after
|
| A cold-start BEQ that is actually taken, with ~12 wrong-path
| instructions (mostly ALU ops writing unique destinations) piled up
| behind it.  Forces the RAT rollback path (design decision #9) to
| restore many speculative renames, and the ROB to squash a wide window.
|
| If cRAT shadow or free bitmap rebuild is broken, we expect either a
| wrong final arch state at _target (D0..D6 must match their set-before-
| branch values) or a hang.

    .text
    .org 0

_start:
    | Pre-set arch D0..D6 to known good values.  These values must
    | survive the mispredict flush intact.
    move.l  #0xA0A0A0A0, %d0
    move.l  #0xB1B1B1B1, %d1
    move.l  #0xC2C2C2C2, %d2
    move.l  #0xD3D3D3D3, %d3
    move.l  #0xE4E4E4E4, %d4
    move.l  #0xF5F5F5F5, %d5
    move.l  #0x06060606, %d6

    | Condition that will mispredict: Z=1 but BEQ-forward is cold.
    moveq   #1, %d7
    cmp.l   %d7, %d7        | Z=1

    beq     _target          | MISPREDICT: cold forward BEQ, actually taken

    | ── wrong-path poison: 12 ops rewriting D0..D6 ──
    | All of these dispatch into the ROB before the branch resolves.
    | Rename must back these out on flush.
    move.l  #0xBAD00000, %d0
    move.l  #0xBAD00001, %d1
    move.l  #0xBAD00002, %d2
    move.l  #0xBAD00003, %d3
    move.l  #0xBAD00004, %d4
    move.l  #0xBAD00005, %d5
    move.l  #0xBAD00006, %d6
    add.l   %d0, %d1
    add.l   %d1, %d2
    add.l   %d2, %d3
    add.l   %d3, %d4
    add.l   %d4, %d5

    | Should never reach here.
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d7
    move.l  %d7, (%a0)
_halt_wrong:
    bra     _halt_wrong

_target:
    | Verify D0..D6 still hold their pre-branch values.
    cmp.l   #0xA0A0A0A0, %d0
    bne     _fail
    cmp.l   #0xB1B1B1B1, %d1
    bne     _fail
    cmp.l   #0xC2C2C2C2, %d2
    bne     _fail
    cmp.l   #0xD3D3D3D3, %d3
    bne     _fail
    cmp.l   #0xE4E4E4E4, %d4
    bne     _fail
    cmp.l   #0xF5F5F5F5, %d5
    bne     _fail
    cmp.l   #0x06060606, %d6
    bne     _fail

    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d7
    move.l  %d7, (%a0)
_halt_fail:
    bra     _halt_fail
