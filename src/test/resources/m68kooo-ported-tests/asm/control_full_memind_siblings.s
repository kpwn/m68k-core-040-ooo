| control_full_memind_siblings.s -- JMP/JSR/LEA full memory-indirect EAs
|
| Full-format memory-indirect MOVE support is not enough for ROM frontier
| coverage: control-transfer and address-generation opclasses decode their
| EAs through separate instruction families.  This test keeps the forms
| compact and checks return-PC push, final JMP target, and LEA final EA.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    lea     0x00115f00, %a7

    | JSR ([32,A4],-8): base-present, index-suppressed full memory indirect.
    lea     0x00115200, %a4
    lea     0x00115220, %a0
    move.l  #(_sub_full + 8), (%a0)
    moveq   #0, %d0
_jsr_full:
    .word   0x4eb4, 0x0162, 0x0020, 0xfff8
_after_jsr_full:
    cmp.l   #0x13572468, %d0
    bne     _fail1
    cmpa.l  #0x00115f00, %a7
    bne     _fail1
    move.l  -4(%a7), %d1
    cmp.l   #_after_jsr_full, %d1
    bne     _fail1

    | LEA ([32,A4],-8),A5 should produce the same final EA without CCR edits.
    | Setup memory and registers first, THEN set CCR.Z=1 via tst, THEN run
    | the LEA (which must preserve Z=1), then check Z before any other op
    | that would clobber CCR.
    lea     0x00115300, %a4
    lea     0x00115320, %a0
    move.l  #0x00115408, (%a0)
    moveq   #0, %d7
    tst.l   %d7                               | Z=1 (preserved across LEA)
    .word   0x4bf4, 0x0162, 0x0020, 0xfff8    | LEA ([32,A4],-8),A5
    bne     _fail2                            | LEA must not touch CCR
    cmpa.l  #0x00115400, %a5
    bne     _fail2

    | JSR ([0,A4,D2.W*2],0): index-present preindex memind, null bd, null od.
    | Tests that ext1[6]=0 (IS=0) with ext1[2:0]=001 (preindex+null-od)
    | actually USES the Xn — distinct from the IS=1 path above.
    | ext1 = 0010_0011_0001_0001 = 0x2311
    lea     0x00115400, %a4
    lea     0x00115408, %a0
    move.l  #_jsr_idx_target, (%a0)
    move.l  #0x00000004, %d2
    moveq   #0, %d3
_jsr_idx:
    .word   0x4eb4, 0x2311
_after_jsr_idx:
    cmp.l   #0x55aa55aa, %d3
    bne     _fail4
    cmpa.l  #0x00115f00, %a7
    bne     _fail4
    move.l  -4(%a7), %d1
    cmp.l   #_after_jsr_idx, %d1
    bne     _fail4

    | JMP ([1024],0): base/index-suppressed absolute pointer slot.
    lea     0x00000400, %a0
    move.l  #_jmp_target, (%a0)
    .word   0x4ef0, 0x01e1, 0x0400
    bra     _fail3

_jsr_idx_target:
    move.l  #0x55aa55aa, %d3
    rts

_jmp_target:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a0)
_halt:
    bra     _halt

_sub_full:
    move.l  #0x13572468, %d0
    rts

_fail1:
    move.l  #0xDEAD0001, %d7
    bra     _fail
_fail2:
    move.l  #0xDEAD0002, %d7
    bra     _fail
_fail3:
    move.l  #0xDEAD0003, %d7
    bra     _fail
_fail4:
    move.l  #0xDEAD0004, %d7

_fail:
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
_halt_fail:
    bra     _halt_fail
