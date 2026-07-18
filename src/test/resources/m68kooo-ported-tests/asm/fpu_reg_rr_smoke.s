| fpu_reg_rr_smoke.s - decoded FPU register-register ops retire.
|
| The FP PRF resets FP0-FP7 to +0.0.  This test does not inspect FP
| numerical results yet; it proves the newly decoded F200 register-register
| UOP_FP forms enter the FP RAT/IQ/FPU path, wake dependencies, complete in
| the ROB, and allow younger integer code to retire.
|
| PASS: 0xC0FFEE00.  FAIL: timeout / no sentinel.

    .text
    .org 0

_start:
    lea     0x00020000, %a7

    | FMOVE.X FP0,FP1
    .short  0xF200, 0x0080
    | FADD.X FP1,FP2
    .short  0xF200, 0x0522
    | FSUB.X FP2,FP3
    .short  0xF200, 0x09A8
    | FMUL.X FP3,FP4
    .short  0xF200, 0x0E23
    | FABS.X FP4,FP5
    .short  0xF200, 0x1298
    | FNEG.X FP5,FP6
    .short  0xF200, 0x171A
    | FDIV.X FP6,FP7
    .short  0xF200, 0x1BA0

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a0)
_halt:
    bra     _halt
