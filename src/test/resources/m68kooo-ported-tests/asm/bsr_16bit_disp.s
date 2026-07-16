| bsr_16bit_disp.s — BSR with 16-bit displacement (opword byte 0x00).
|
| Validates that the V2 assembler computes the correct target PC when
| the opword's displacement byte is 0x00, i.e. target = pd_pc + 2 +
| sx16(ext1) and return addr = pd_pc + 4.
|
| The assembler forces a 16-bit disp by using `.w` on the bsr mnemonic
| (or by making the target far enough that the 8-bit window doesn't
| reach).  We use `.w` for determinism.

    .text
    .org 0

_start:
    lea     0x00010000, %a7           | stack pointer

    move.l  #0xDEAD0001, %d0          | canary — must be overwritten
    bsr.w   _subroutine               | emits 0x6100 + 16-bit disp

    | After RTS, D0 must equal 0xCAFE1234 and D1 must equal 0xFEED5678.
    cmp.l   #0xCAFE1234, %d0
    bne     _fail
    cmp.l   #0xFEED5678, %d1
    bne     _fail

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    stop    #0x2700
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
_halt_fail:
    stop    #0x2700
    bra     _halt_fail

    | Add some padding so the relative displacement is definitely
    | non-trivial (not a sneaky 8-bit fit).  16 * 4 = 64 bytes of NOPs.
    .rept 32
    nop
    .endr

_subroutine:
    move.l  #0xCAFE1234, %d0          | return value
    move.l  #0xFEED5678, %d1          | second canary
    rts
