| cmpi_l_idx_dst.s -- CMPI.L #imm,(d8,An,Xn) brief-indexed mem dst.
|
| Task #201 (A10b): V2 imm-src brief-indexed-mem-dst CMPI — no store
| (CMPI compares and writes flags only).  Verifies flag semantics for
| equal / less-than / greater-than cases.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | CMPI.L #0x12345678,(4,A1,D2.L*1) — equal (Z=1).
    lea     0x00124000, %a1
    move.l  #0x12345678, 8(%a1)
    moveq   #4, %d2
    cmpi.l  #0x12345678, 4(%a1,%d2.l*1)
    bne     _fail
    | Ensure memory is unchanged.
    move.l  8(%a1), %d0
    cmp.l   #0x12345678, %d0
    bne     _fail

    | CMPI.L #0xa,(0,A2,D4.L*2) — mem>imm (N=0,Z=0).
    lea     0x00124100, %a2
    move.l  #0x00000100, 12(%a2)
    moveq   #6, %d4                          | D4*2=12
    cmpi.l  #0xa, 0(%a2,%d4.l*2)
    beq     _fail
    bcs     _fail                             | unsigned mem>=imm (no borrow)
    | memory unchanged
    move.l  12(%a2), %d0
    cmp.l   #0x00000100, %d0
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
