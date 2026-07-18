| cmpi_abs_mem.s -- CMPI.{B,W,L} #imm,(xxx).{W,L} memory operands
|
| Covers the Q700 ROM frontier:
|   40804182: 0c38 0004 012f  cmpi.b #4,0x12f.W
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | Exact ROM byte absolute-short form.
    moveq   #4, %d0
    .word   0x11c0, 0x012f          | move.b %d0,0x012f.W
    move.l  #0xDEAD0001, %d1
    .word   0x0c38, 0x0004, 0x012f  | cmpi.b #4,0x012f.W
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail

    | Byte absolute-short mismatch: 3 - 4 sets N and C.
    moveq   #3, %d0
    .word   0x11c0, 0x0133          | move.b %d0,0x0133.W
    move.l  #0xDEAD0002, %d1
    .word   0x0c38, 0x0004, 0x0133  | cmpi.b #4,0x0133.W
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcc     _fail

    | Word absolute-short match.
    move.l  #0x00001234, %d0
    .word   0x31c0, 0x0200          | move.w %d0,0x0200.W
    move.l  #0xDEAD0003, %d1
    .word   0x0c78, 0x1234, 0x0200  | cmpi.w #0x1234,0x0200.W
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail

    | Word absolute-long mismatch: 1 - 2 sets N and C.
    moveq   #1, %d0
    .word   0x33c0, 0x0010, 0x8600  | move.w %d0,0x00108600.L
    move.l  #0xDEAD0004, %d1
    .word   0x0c79, 0x0002, 0x0010, 0x8600  | cmpi.w #2,0x00108600.L
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcc     _fail

    | Long absolute-short match.
    move.l  #0x11223344, %d0
    .word   0x21c0, 0x0300          | move.l %d0,0x0300.W
    move.l  #0xDEAD0005, %d1
    .word   0x0cb8, 0x1122, 0x3344, 0x0300  | cmpi.l #0x11223344,0x0300.W
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail

    | Long absolute-long mismatch: 1 - 2 sets N and C.
    moveq   #1, %d0
    .word   0x23c0, 0x0010, 0x8700  | move.l %d0,0x00108700.L
    move.l  #0xDEAD0006, %d1
    .word   0x0cb9, 0x0000, 0x0002, 0x0010, 0x8700  | cmpi.l #2,0x00108700.L
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcc     _fail

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d1
    move.l  %d1, (%a0)
    bra     _pass

_fail:
    lea     0xFFFF0000, %a0
    move.l  %d1, (%a0)
    bra     _fail
