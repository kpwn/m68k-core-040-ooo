| movep_w_preserve_hi.s — MOVEP.W mem→reg must preserve Dn[31:16].
|
| Stage D-9c corner: the crack reads Dy, ANDs with 0xFFFF_0000 to
| isolate the hi half, then ORs with the assembled low word.  A bug
| that clobbered the hi half (e.g. ALU_MOV_MERGE mis-sized) would
| surface here but not in the round-trip tests that start with a
| scratch value in the register.
|
| D0 is pre-loaded with 0xDEADBEEF.  After MOVEP.W loads bytes 0xAA,
| 0xBB from (A0+0, A0+2), D0 must become 0xDEADAABB — top 16 bits
| unchanged, bottom 16 bits = {loaded[0], loaded[1]}.
|
| PASS: sentinel 0xC0FFEE00.

    .text
    .org 0

_start:
    lea     0x00010000, %a7
    lea     0x00020000, %a0
    lea     0x00020000, %a1
    lea     0x00020002, %a2

    | Plant source bytes.  byte 0 = 0xAA, byte 2 = 0xBB.
    move.w  #0xAA00, (%a1)        | write 0xAA to addr 0, 0x00 to addr 1
    move.w  #0xBB00, (%a2)        | write 0xBB to addr 2, 0x00 to addr 3

    | Pre-load D0 with a canary.  MOVEP.W must leave upper 16 bits alone.
    move.l  #0xDEADBEEF, %d0
    movep.w 0(%a0), %d0
    cmp.l   #0xDEADAABB, %d0
    bne     _fail

    | Second pass: negative displacement to exercise sign-extension.
    | Source bytes at 0x00020010 and 0x00020012.
    lea     0x00020020, %a3
    lea     0x00020010, %a4
    lea     0x00020012, %a5
    move.w  #0x1100, (%a4)
    move.w  #0x2200, (%a5)

    move.l  #0xCAFE9999, %d1
    movep.w -16(%a3), %d1          | A3 + (-16) = 0x00020010
    cmp.l   #0xCAFE1122, %d1
    bne     _fail

    lea     0xFFFF0000, %a1
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a1)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a1
    move.l  #0xDEADBEEF, %d7
    move.l  %d7, (%a1)
_halt_fail:
    bra     _halt_fail
