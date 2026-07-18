| adv_spec_fline_squash.s — Speculative F-line trap must be squashed
|
| Same shape as adv_spec_trap_squash.s, but the wrong-path opword is a
| vec-11 F-line instruction.  This matches the ROM frontier failure mode
| more closely than TRAP #n: a branch resolves taken while the fall-through
| decode window contains an F-line opword.  If the wrong-path exception is
| allowed to survive to commit, the CPU jumps through vector 11 at VBR+0x2c
| and never reaches the sentinel.

    .text
    .org 0

_start:
    lea     0x00020000, %a7
    moveq   #1, %d0
    cmp.l   #1, %d0                 | Z=1
    beq     _good                   | taken; fall-through is wrong-path

    | Wrong-path speculative F-line opword.
    .short  0xF123

_good:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt
