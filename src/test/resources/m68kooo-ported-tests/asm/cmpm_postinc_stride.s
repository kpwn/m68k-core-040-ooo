| cmpm_postinc_stride.s — CMPM strides on both Am and An
|
| PRM §4.41: CMPM.{B,W,L} (Ay)+,(Ax)+ computes (Ax)-(Ay), then both Ay
| and Ax post-increment by operand size.  A7 byte stride = 2.
|
| Covers the V2 Stage D-2 CMPM 5-phase crack (LOAD TMP1, inc Ay,
| LOAD TMP2, inc Ax, CMP TMP2-TMP1).
|
| PASS sentinel: 0xC0FFEE00 → 0xFFFF0000.

    .text
    .org 0

_start:
    | Byte CMPM with matching values.
    lea     0x00108000, %a0
    lea     0x00108100, %a1
    move.b  #0x55, (%a0)
    move.b  #0x55, (%a1)
    cmpm.b  (%a0)+, (%a1)+
    bne     _fail
    cmpa.l  #0x00108001, %a0
    bne     _fail
    cmpa.l  #0x00108101, %a1
    bne     _fail

    | Word CMPM with mismatching values.
    lea     0x00108200, %a2
    lea     0x00108300, %a3
    move.w  #0x1234, (%a2)
    move.w  #0x5678, (%a3)
    cmpm.w  (%a2)+, (%a3)+
    beq     _fail                     | values differ → Z must be 0
    cmpa.l  #0x00108202, %a2
    bne     _fail
    cmpa.l  #0x00108302, %a3
    bne     _fail

    | Long CMPM — +4 stride on both.
    lea     0x00108400, %a4
    lea     0x00108500, %a5
    move.l  #0xDEADBEEF, (%a4)
    move.l  #0xDEADBEEF, (%a5)
    cmpm.l  (%a4)+, (%a5)+
    bne     _fail
    cmpa.l  #0x00108404, %a4
    bne     _fail
    cmpa.l  #0x00108504, %a5
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
