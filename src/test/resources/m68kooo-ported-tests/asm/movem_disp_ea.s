| movem_disp_ea.s — MOVEM.L (d16,A0), D0/D1
|
| Stage D-9a V2 corner: (d16,An) load EA.  The MOVEM mask lives in ext1
| and the displacement lives in ext2, so decode.v's ext-shift must add
| +1 word when bitfield OR movem.  If the shift is wrong, the dst EA
| decoder reads the mask as the displacement and lands the loads at
| a garbage address.
|
| No base-register writeback for (d16,An) — A0 must equal its pre-MOVEM
| value after the instruction.
|
| PASS: 0xC0FFEE00 at 0xFFFF0000.
| FAIL: 0xDEADBEEF at 0xFFFF0000.

    .text
    .org 0

_start:
    lea     0x00010000, %a7              | stack base

    | Source image at 0x00020000; we'll read from (0x10,A0) where A0 =
    | 0x0001FFF0.  So effective base = 0x0001FFF0 + 16 = 0x00020000.
    lea     0x00020000, %a1
    move.l  #0xAAAA0000, (%a1)
    move.l  #0xBBBB0001, 4(%a1)

    | Set up the (d16,A0) ea: A0 = 0x0001FFF0, disp = +0x10.
    lea     0x0001FFF0, %a0

    | Prime destination regs.
    move.l  #0xFFFFFFFF, %d0
    move.l  #0xFFFFFFFF, %d1

    | MOVEM.L (d16,A0), D0/D1 — two regs loaded, no A0 writeback.
    movem.l 0x10(%a0), %d0/%d1

    cmp.l   #0xAAAA0000, %d0
    bne     _fail
    cmp.l   #0xBBBB0001, %d1
    bne     _fail

    | A0 must NOT have moved.
    move.l  %a0, %d2
    cmp.l   #0x0001FFF0, %d2
    bne     _fail

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
