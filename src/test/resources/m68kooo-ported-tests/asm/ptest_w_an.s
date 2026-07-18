| ptest_w_an.s — task #241 / G4 — PTEST{W,R} (An) via V2 sysop.
| Confirms 0xF548+n / 0xF568+n migrate to V2 SYS_PTEST and retire
| with an MMUSR update observable through MOVEC %mmusr,Rn.
|
| The V2 emission shape:
|   uop_type            = UOP_SYS
|   uop_op              = SYS_PTEST (=19)
|   requires_supervisor = 1
|   has_src_a           = 1, arch_src_a = An
|   len_bytes           = 2
|
| Sequence:
|   1. Issue PTESTW (A0) — opword 0xF548.  MMU disabled: PA=VA, R=1.
|   2. Issue PTESTR (A0) — opword 0xF568.  Same MMUSR result.
|   3. Mix PTEST with PFLUSH (An) and PFLUSHA in a burst — exercise
|      back-to-back retire of all four sysops through commit.
|   4. Continue past — sentinel PASS.
|
| PASS: 0xC0FFEE00.

    .text
    .org 0

_start:
    lea     0x00020000, %a7

    | Set up An regs.
    move.l  #0x00010000, %a0
    move.l  #0x00011000, %a1
    move.l  #0x00012000, %a2

    | PTESTW (A0) — write-mode probe.
    .short  0xF548                   | ptestw (a0)
    movec   %mmusr, %d0
    cmp.l   #0x00010001, %d0
    bne     _fail

    | PTESTR (A0) — read-mode probe.
    .short  0xF568                   | ptestr (a0)
    movec   %mmusr, %d0
    cmp.l   #0x00010001, %d0
    bne     _fail

    | PTEST varying An.
    .short  0xF549                   | ptestw (a1)
    movec   %mmusr, %d0
    cmp.l   #0x00011001, %d0
    bne     _fail
    .short  0xF56A                   | ptestr (a2)
    movec   %mmusr, %d0
    cmp.l   #0x00012001, %d0
    bne     _fail

    | Mixed burst of PMMU ops to exercise the retire FSM.
    .short  0xF548                   | ptestw (a0)
    .short  0xF508                   | pflush  (a0)
    .short  0xF518                   | pflusha
    .short  0xF568                   | ptestr (a0)
    movec   %mmusr, %d0
    cmp.l   #0x00010001, %d0
    bne     _fail
    .short  0xF500                   | pflushn (a0)

    | Some normal work after to confirm pipeline integrity.
    move.l  #0xCAFEBABE, %d0
    cmp.l   #0xCAFEBABE, %d0
    bne     _fail

    move.l  #0x12345678, %d1
    add.l   #1, %d1
    cmp.l   #0x12345679, %d1
    bne     _fail

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d7
    move.l  %d7, (%a0)
_halt_fail:
    bra     _halt_fail
