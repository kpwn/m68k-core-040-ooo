| mmu_remap_stale_btb_nonbranch.s -- the DECODE-TIME BTB half of the same defect, isolated
| from the fetch-directed FTB.
|
| Its sibling `mmu_remap_stale_btb_predict.s` puts the trained branch at word 0 of its
| fetch window, where the FTB also installs it, and there the redirect that survives the
| remap comes from the FTB's confirmation (a 4-bit LENGTH match and nothing else). This
| program removes the FTB from the picture entirely so what is left is exactly the
| originally-reported hole: `FetchAlignPlugin`'s `btbQueryValid0 := predEnable &&
| res.slot0Valid` is not gated on the slot being a branch, and `predTaken` is read only by
| `BranchEuPlugin` -- so a stale BTB hit stamps a redirect on an ordinary ALU micro-op that
| NOTHING verifies.
|
| HOW THE FTB IS EXCLUDED. The FTB installs only branches wholly contained in their aligned
| eight-byte fetch window (`Ftb.scala`: `qFramed` requires `brWordOff + brLen <= 4`). The
| learned branch here sits at VA 0x8000100e -- word 3 of window 0x80001008 -- and is a
| two-word `bra.w`, so `brWordOff + brLen = 5` and the lookup DECLINES every time. `ftqAt0`
| is therefore never true at the trained PC, `ftqConfirm` cannot fire, and the only thing
| that can redirect is the decode-time BTB fallback. Because no length is ever compared on
| this path, the replacement instruction's length is irrelevant -- which is the point: the
| BTB path has no confirmation of any kind.
|
| ROUTE (identical to the sibling, and for the same reasons -- see its header for why a
| stale BTB entry is reachable ONLY through a translation change, and why this program
| deliberately issues no cache-maintenance instruction):
|   Phase 1: VA 0x8000100e -> PFN A (PA 0x00300000)
|              +0x0e  bra.w  0x80001030
|              +0x30  moveq  #0x33,%d5
|              +0x32  rts
|            `jsr (%a1)` twice; the bra retires and the BTB installs it strongly taken.
|   Phase 2: repoint the L3 leaf to PFN B (PA 0x00400000) + PFLUSHA. PFN B holds
|              +0x0e  moveq  #0x11,%d3    <- an ALU micro-op at the trained PC
|              +0x10  moveq  #0x22,%d4    <- fall-through; SKIPPED if the stale entry fires
|              +0x12  rts
|              +0x30  moveq  #0x77,%d4    <- WRONG PATH, at the stale target
|              +0x32  rts
|
| PASS sentinel: 0xC0FFEE00 (D3=0x11 AND D4=0x22).
| FAIL sentinels:
|   0xDEAD0C01 -- phase-1 training call did not take the bra (setup broken)
|   0xDEAD0C02 -- the remapped page's first instruction did not run (remap/PFLUSHA broken)
|   0xDEAD0C03 -- D4 == 0x77: a stale BTB hit redirected fetch on a NON-BRANCH  <-- the defect
|   0xDEAD0C04 -- D4 is neither 0x22 nor 0x77 (unexpected third outcome)

    .text
    .org 0

_start:
    lea     0x00010000, %a7
    move.l  #_trap_h, 0x00100080
    move.l  #0x00100000, %d0
    movec   %d0, %vbr

    move.l  #0x4000C000, %d0
    movec   %d0, %itt0
    move.l  #0x007FA000, %d0
    movec   %d0, %dtt0
    move.l  #0xFF00A000, %d0
    movec   %d0, %dtt1

    move.l  #0x00200000, %d0
    movec   %d0, %urp
    movec   %d0, %srp

    move.l  #0x0021000a, 0x00200100
    move.l  #0x0022000a, 0x00210000
    move.l  #0x00300009, 0x00220004         | L3[0x01] = PFN A, U, DT=01

    | ---- PFN A ----
    move.l  #0x4e714e71, 0x00300008
    move.l  #0x4e714e71, 0x0030000c
    move.l  #0x60000020, 0x0030000e         | bra.w 0x80001030   (word 3 of window +0x08)
    move.l  #0x4e714e71, 0x00300012
    move.l  #0x7a334e75, 0x00300030         | moveq #0x33,%d5 ; rts

    | ---- PFN B ----
    move.l  #0x4e714e71, 0x00400008
    move.l  #0x4e714e71, 0x0040000c
    move.l  #0x76117822, 0x0040000e         | moveq #0x11,%d3 ; moveq #0x22,%d4
    move.l  #0x4e754e71, 0x00400012         | rts ; nop
    move.l  #0x78774e75, 0x00400030         | moveq #0x77,%d4 ; rts   (WRONG PATH)

    move.l  #0x00008770, %d0
    movec   %d0, %tc

    move.l  #0x8000100e, %a1

    moveq   #0, %d5
    jsr     (%a1)
    cmp.l   #0x33, %d5
    bne     _fail_setup
    moveq   #0, %d5
    jsr     (%a1)
    cmp.l   #0x33, %d5
    bne     _fail_setup

    | ---- change the TRANSLATION under the still-valid BTB entry; NO cache maintenance ----
    move.l  #0x00400009, 0x00220004         | L3[0x01] = PFN B, U, DT=01
    pflusha

    moveq   #0, %d3
    moveq   #0, %d4
    jsr     (%a1)
    cmp.l   #0x11, %d3
    bne     _fail_remap
    cmp.l   #0x22, %d4
    bne     _fail_stale

    trap    #0

_fail_setup:
    move.l  #0xDEAD0C01, 0xFFFF0000
_h1: bra    _h1

_fail_remap:
    move.l  #0xDEAD0C02, 0xFFFF0000
_h2: bra    _h2

_fail_stale:
    cmp.l   #0x77, %d4
    beq     _fail_wrongpath
    move.l  #0xDEAD0C04, 0xFFFF0000
_h3: bra    _h3
_fail_wrongpath:
    move.l  #0xDEAD0C03, 0xFFFF0000
_h4: bra    _h4

_trap_h:
    move.l  #0xC0FFEE00, 0xFFFF0000
_done: bra  _done
