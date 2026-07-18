| pflushn_an_mask.s — task #241 / G4 — PFLUSHN (An) via V2 sysop.
| Confirms 0xF500+n migrates to V2 SYS_PFLUSHN at retire and pulses
| mmu_pflush_va_req with An's VA — identical wire to PFLUSH in stub.
|
| Mirror of pflush_an_mask.s with PFLUSHN sub-code.
|
| The V2 emission shape:
|   uop_type            = UOP_SYS
|   uop_op              = SYS_PFLUSHN (=18)
|   requires_supervisor = 1
|   has_src_a           = 1, arch_src_a = An
|   len_bytes           = 2
|
| PASS: 0xC0FFEE00.

    .text
    .org 0

_start:
    lea     0x00020000, %a7

    | Single PFLUSHN (A0) — opword 0xF500.
    move.l  #0x00010000, %a0
    .short  0xF500                   | pflushn (a0)

    | Burst varying An.
    move.l  #0x00011000, %a1
    move.l  #0x00012000, %a2
    move.l  #0x00013000, %a3
    .short  0xF501                   | pflushn (a1)
    .short  0xF502                   | pflushn (a2)
    .short  0xF503                   | pflushn (a3)
    .short  0xF500                   | pflushn (a0) again

    | Mix PFLUSHN with PFLUSH to confirm both sub-codes coexist
    | through the same retire wire.
    .short  0xF508                   | pflush  (a0)
    .short  0xF501                   | pflushn (a1)

    | PFLUSHAN — opword 0xF510.  Folds onto SYS_PFLUSHA at the V2
    | semantics layer (no global tracking in stub).
    .short  0xF510                   | pflushan

    | Some normal work to confirm pipeline integrity.
    move.l  #0xCAFEBABE, %d0
    cmp.l   #0xCAFEBABE, %d0
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
