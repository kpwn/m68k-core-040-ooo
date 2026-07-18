| addq_to_an_always_long.s — ADDQ/SUBQ to An is always .L regardless of size bits
|
| PRM §4.6: when destination is An, ADDQ/SUBQ operates on the full 32-bit
| address register no matter what size field is in the opword.  CCR is
| never written.  Covers the V2 Stage D-2 alu_size_bits_val special case.
|
| PASS sentinel: 0xC0FFEE00 → 0xFFFF0000.

    .text
    .org 0

_start:
    | ADDQ.W #3,A0 with A0=0xfffffffd → A0 should overflow the word
    | boundary and become 0x00000000.  A byte/word op would truncate.
    movea.l #0xfffffffd, %a0
    addq.w  #3, %a0
    cmpa.l  #0x00000000, %a0
    bne     _fail

    | ADDQ.L #8,A1 advances a pointer by 8 bytes.
    movea.l #0x00001000, %a1
    addq.l  #8, %a1
    cmpa.l  #0x00001008, %a1
    bne     _fail

    | SUBQ.W #7,A2 works the same — 32-bit subtract from An.
    movea.l #0x80000000, %a2
    subq.w  #7, %a2
    cmpa.l  #0x7ffffff9, %a2
    bne     _fail

    | CCR must be untouched.  Seed X,Z,V,C with a MOVE, then check ADDQ
    | does not perturb them.  MOVEQ sets NZVC from operand, not X.
    moveq   #-1, %d0                  | N=1 Z=0 V=0 C=0
    movea.l #0x00001000, %a3
    addq.w  #4, %a3
    bpl     _fail                     | N must still be 1
    beq     _fail                     | Z must still be 0

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
