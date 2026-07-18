| v2_sysop_pflusha.s — task #221 / E4 — PFLUSHA via the V2 sysop family.
| Confirms the legacy decode_1111.vh row 0xF518 migrates to V2
| SYS_PFLUSHA at retire and does not fault when supervisor.
|
| The V2 emission shape matches the retired legacy row exactly:
|   uop_type            = UOP_SYS
|   uop_op              = SYS_PFLUSHA
|   requires_supervisor = 1
|   len_bytes           = 2
|
| Sequence:
|   1. Issue PFLUSHA in supervisor mode.  Should retire as no-op + pulse
|      mmu_pflush_all_req.
|   2. Issue four more PFLUSHAs in a row (back-to-back).  All should
|      retire cleanly.
|   3. Continue past — sentinel PASS.
|
| PASS: 0xC0FFEE00.

    .text
    .org 0

_start:
    lea     0x00020000, %a7

    | Single PFLUSHA — opword 0xF518.
    pflusha

    | Burst of 4 PFLUSHAs — exercises back-to-back retire of the V2
    | sub-code through commit's mmu_pflush_all_req pulse.
    pflusha
    pflusha
    pflusha
    pflusha

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
