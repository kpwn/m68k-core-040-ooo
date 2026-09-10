| fpu_fmovem_x_fpsp_pair.s — the EXACT pair the Q700 ROM FPSP brackets its
| packed-decimal conversion helper with:
|
|     FMOVEM.X FP0,-(SP)   /  BSR ...  /  FMOVEM.X (SP)+,FP0
|
| Both halves were unimplemented and took a vector-11 F-line, so the ROM's own
| floating-point support package faulted inside the handler for the fault. Boots
| on hardware died at 0x4088DA0E and 0x4088DB22, inside that kernel.
|
| Asserts, for the store/predecrement and load/postincrement pair:
|   1. -(SP) moves SP down by 12 per listed register and writes the value there
|   2. (SP)+ reads it back and restores SP exactly
|   3. the value round-trips bit-for-bit
|   4. a multi-register list moves 12*N and keeps highest-register-at-lowest-address
|
| Sentinel: 0xC0FFEE00 pass; 0xF7xx0v = stage xx trapped with vector v;
| 0xDEADE0nn = stage nn produced a wrong value or a wrong SP.

    .text
    .org 0
    .equ PASS_SENT, 0xFFFF0000
    .equ VBASE,     0x00100000

_start:
    lea     0x00010000, %a7
    move.l  #VBASE, %d0
    movec   %d0, %vbr
    move.l  #_trapB, VBASE+0x2C
    move.l  #_trap4, VBASE+0x10
    move.l  #_trap2, VBASE+0x08
    move.l  #_trap3, VBASE+0x0C

    | Seed 1.0 (sign 0, exponent 0x3FFF, mantissa 1<<63) and load FP0 from it.
    lea     0x00020000, %a0
    move.l  #0x3FFF0000, (%a0)
    move.l  #0x80000000, 4(%a0)
    move.l  #0x00000000, 8(%a0)
    move.l  #0xF7000000, %d2
    fmovem.x %a0@, %fp0

    | ── Stage 1: FMOVEM.X FP0,-(SP) must consume exactly 12 bytes ──────────
    move.l  #0xF7010000, %d2
    move.l  %sp, %d3
    fmovem.x %fp0, %sp@-
    move.l  %d3, %d4
    sub.l   %sp, %d4
    cmp.l   #12, %d4
    beq     1f
    move.l  #0xDEADE001, %d2
    bra     _report
1:
    | the frame must hold the value, at the NEW (lower) SP
    move.l  (%sp), %d4
    cmp.l   #0x3FFF0000, %d4
    beq     2f
    move.l  #0xDEADE002, %d2
    bra     _report
2:
    move.l  4(%sp), %d4
    cmp.l   #0x80000000, %d4
    beq     3f
    move.l  #0xDEADE003, %d2
    bra     _report
3:
    | ── Stage 2: FMOVEM.X (SP)+,FP0 must restore SP exactly ───────────────
    move.l  #0xF7020000, %d2
    | clobber FP0 first so the reload is observable
    lea     0x00020030, %a1
    move.l  #0x40000000, (%a1)
    move.l  #0xC0000000, 4(%a1)
    move.l  #0x00000000, 8(%a1)
    fmovem.x %a1@, %fp0
    fmovem.x %sp@+, %fp0
    cmp.l   %d3, %sp
    beq     4f
    move.l  #0xDEADE004, %d2
    bra     _report
4:
    | ── Stage 3: the value round-tripped ──────────────────────────────────
    move.l  #0xF7030000, %d2
    lea     0x00020100, %a1
    fmove.x %fp0, %a1@
    move.l  (%a1), %d4
    cmp.l   #0x3FFF0000, %d4
    beq     5f
    move.l  #0xDEADE005, %d2
    bra     _report
5:
    move.l  4(%a1), %d4
    cmp.l   #0x80000000, %d4
    beq     6f
    move.l  #0xDEADE006, %d2
    bra     _report
6:
    | ── Stage 4: a 4-register list moves 12*4 = 48 bytes ──────────────────
    move.l  #0xF7040000, %d2
    move.l  %sp, %d3
    fmovem.x %fp0-%fp3, %sp@-
    move.l  %d3, %d4
    sub.l   %sp, %d4
    cmp.l   #48, %d4
    beq     7f
    move.l  #0xDEADE007, %d2
    bra     _report
7:
    fmovem.x %sp@+, %fp0-%fp3
    cmp.l   %d3, %sp
    beq     _pass
    move.l  #0xDEADE008, %d2
    bra     _report

_pass:
    lea     PASS_SENT, %a1
    move.l  #0xC0FFEE00, %d2
    move.l  %d2, (%a1)
_halt:
    bra     _halt

_trapB:
    or.l    #0x0B, %d2
    bra     _report
_trap4:
    or.l    #0x04, %d2
    bra     _report
_trap2:
    or.l    #0x02, %d2
    bra     _report
_trap3:
    or.l    #0x03, %d2
_report:
    lea     PASS_SENT, %a1
    move.l  %d2, (%a1)
_halt_f:
    bra     _halt_f
