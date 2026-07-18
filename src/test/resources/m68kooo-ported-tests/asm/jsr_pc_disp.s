| jsr_pc_disp.s — JSR (d16,PC).
|
| Validates V2 JSR (d16,PC) crack — target = pd_pc + 2 + sx16(ext1).
| Return addr = pd_pc + 4.

    .text
    .org 0

_start:
    lea     0x00010000, %a7

    move.l  #0xDEAD2222, %d0
    jsr     _sub(%pc)                 | JSR (d16,PC) — opword 0x4EBA

    cmp.l   #0xFEEDBEEF, %d0
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

_sub:
    move.l  #0xFEEDBEEF, %d0
    rts
