| movem_rom_mem_forms.s -- ROM MOVEM.L memory forms
|
| Covers the exact Q700 RAM diagnostic opcode streams:
|   48d2 003f       movem.l D0-D5,(A2)
|   48ea 003f 0018  movem.l D0-D5,24(A2)
|   4ce9 0007 fff4  movem.l -12(A1),D0-D2
|
| Failure modes covered:
|   - register-to-memory MOVEM stores all listed registers in ascending
|     mask order.
|   - d16 displacement is consumed from the word after the mask.
|   - negative d16 load displacement sign-extends.
|   - MOVEM does not update CCR or the base register for these forms.

    .text
    .org 0

_start:
    | -- 48d2 003f: MOVEM.L D0-D5,(A2) -------------------------------
    lea     0x00102000, %a2
    move.l  #0x10101010, %d0
    move.l  #0x21212121, %d1
    move.l  #0x32323232, %d2
    move.l  #0x43434343, %d3
    move.l  #0x54545454, %d4
    move.l  #0x65656565, %d5

    moveq   #0, %d7
    subq.l  #1, %d7                | N=1, Z=0, V=0, C=1, X=1
    .word   0x48d2, 0x003f        | movem.l D0-D5,(A2)
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcc     _fail
    cmpa.l  #0x00102000, %a2
    bne     _fail

    move.l  (%a2), %d6
    cmp.l   #0x10101010, %d6
    bne     _fail
    move.l  4(%a2), %d6
    cmp.l   #0x21212121, %d6
    bne     _fail
    move.l  8(%a2), %d6
    cmp.l   #0x32323232, %d6
    bne     _fail
    move.l  12(%a2), %d6
    cmp.l   #0x43434343, %d6
    bne     _fail
    move.l  16(%a2), %d6
    cmp.l   #0x54545454, %d6
    bne     _fail
    move.l  20(%a2), %d6
    cmp.l   #0x65656565, %d6
    bne     _fail

    | -- 48ea 003f 0018: MOVEM.L D0-D5,24(A2) ------------------------
    lea     0x00103000, %a2
    move.l  #0xaaaaaaaa, (%a2)
    move.l  #0xbbbbbbbb, 20(%a2)
    move.l  #0xcccccccc, 48(%a2)
    move.l  #0x01020304, %d0
    move.l  #0x11121314, %d1
    move.l  #0x21222324, %d2
    move.l  #0x31323334, %d3
    move.l  #0x41424344, %d4
    move.l  #0x51525354, %d5

    moveq   #0, %d7
    subq.l  #1, %d7
    .word   0x48ea, 0x003f, 0x0018    | movem.l D0-D5,24(A2)
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcc     _fail
    cmpa.l  #0x00103000, %a2
    bne     _fail

    move.l  (%a2), %d6
    cmp.l   #0xaaaaaaaa, %d6
    bne     _fail
    move.l  20(%a2), %d6
    cmp.l   #0xbbbbbbbb, %d6
    bne     _fail
    move.l  24(%a2), %d6
    cmp.l   #0x01020304, %d6
    bne     _fail
    move.l  28(%a2), %d6
    cmp.l   #0x11121314, %d6
    bne     _fail
    move.l  32(%a2), %d6
    cmp.l   #0x21222324, %d6
    bne     _fail
    move.l  36(%a2), %d6
    cmp.l   #0x31323334, %d6
    bne     _fail
    move.l  40(%a2), %d6
    cmp.l   #0x41424344, %d6
    bne     _fail
    move.l  44(%a2), %d6
    cmp.l   #0x51525354, %d6
    bne     _fail
    move.l  48(%a2), %d6
    cmp.l   #0xcccccccc, %d6
    bne     _fail

    | -- 4ce9 0007 fff4: MOVEM.L -12(A1),D0-D2 -----------------------
    lea     0x00104020, %a1
    move.l  #0x0bad0000, %d0
    move.l  #0x0bad0001, %d1
    move.l  #0x0bad0002, %d2
    move.l  #0x70717273, -12(%a1)
    move.l  #0x80818283, -8(%a1)
    move.l  #0x90919293, -4(%a1)

    moveq   #0, %d7
    subq.l  #1, %d7
    .word   0x4ce9, 0x0007, 0xfff4    | movem.l -12(A1),D0-D2
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcc     _fail
    cmpa.l  #0x00104020, %a1
    bne     _fail

    cmp.l   #0x70717273, %d0
    bne     _fail
    cmp.l   #0x80818283, %d1
    bne     _fail
    cmp.l   #0x90919293, %d2
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
_halt_fail:
    bra     _halt_fail
