| pflush_an_mask.s — task #241 / G4 — PFLUSH (An) via V2 sysop family.
| Confirms 0xF508+n migrates to V2 SYS_PFLUSH at retire and pulses
| mmu_pflush_va_req with An's VA on retire.
|
| The V2 emission shape:
|   uop_type            = UOP_SYS
|   uop_op              = SYS_PFLUSH (=17)
|   requires_supervisor = 1
|   has_src_a           = 1, arch_src_a = An
|   len_bytes           = 2
|
| Sequence:
|   1. Issue PFLUSH (A0) with A0 = 0x10000.  Should retire as no-op
|      + pulse mmu_pflush_va_req (stub MMU drops the ATC tag).
|   2. Issue four more PFLUSH (An)s with varying An — exercise the
|      An decode + back-to-back retire of SYS_PFLUSH through commit.
|   3. Continue past — sentinel PASS.
|
| PASS: 0xC0FFEE00.

    .text
    .org 0

_start:
    lea     0x00020000, %a7

    | Single PFLUSH (A0) — opword 0xF508.
    move.l  #0x00010000, %a0
    .short  0xF508                   | pflush (a0)

    | Burst of PFLUSH (An) varying the An reg.
    move.l  #0x00011000, %a1
    move.l  #0x00012000, %a2
    move.l  #0x00013000, %a3
    .short  0xF509                   | pflush (a1)
    .short  0xF50A                   | pflush (a2)
    .short  0xF50B                   | pflush (a3)
    .short  0xF508                   | pflush (a0) again

    | Some normal work after to confirm pipeline didn't stall.
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
