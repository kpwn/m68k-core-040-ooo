| mmu_remap_stale_btb_predict.s -- a BTB entry that outlives a TRANSLATION CHANGE must
| not redirect fetch on the NON-BRANCH that now lives at that virtual address.
|
| WHY THIS SHAPE AND NOT A GENERIC "STALE BTB ENTRY".
| The BTB cannot be polluted by aliasing or by bad training:
|   - it allocates ONLY at retire, gated on the retiring entry genuinely having been a
|     branch (RobPlugin: `when(retire0 && btbIsBranchStore(h0))`), and
|   - it is fully tagged over the COMPLETE 32-bit PC (Btb.scala: index pc[7:1], 24-bit
|     tag pc[31:8], no hash folding), so a non-branch PC cannot collide into a branch's
|     entry.
| The only way a valid entry can come to name a non-branch is for the BYTES AT THAT EXACT
| VIRTUAL ADDRESS to change underneath it. Self-modifying code is already closed --
| CINV/CPUSH-IC fans out to the BTB via IcachePlugin's `maintInvalidateAll`. A TRANSLATION
| CHANGE is not: the L1I is VIPT with a PHYSICAL tag, so it correctly misses and refills
| the new bytes, but the BTB/FTB are keyed on VIRTUAL PC and NOTHING invalidates them on
| PFLUSH/PFLUSHA, a URP/SRP/TC write, or an addressing-mode switch. This program takes
| exactly that route, and deliberately issues NO cache-maintenance instruction -- because
| a 68040 remap does not require one, which is the whole point.
|
| CONSTRUCTION
|   VA 0x80001000 is MMU-translated (ITT0 covers only the 0x40xxxxxx program image).
|   Phase 1: VA 0x80001000 -> PFN A (PA 0x00300000), which holds
|              +0x00  bra.s  +0x0e      -> 0x80001010   (ONE word -- see LENGTH below)
|              +0x02  moveq  #0x55,%d5  (fall-through of an unconditional bra: never runs)
|              +0x10  moveq  #0x33,%d5
|              +0x12  rts
|            `jsr (%a1)` twice. The bra retires, so the BTB installs
|            {pc=0x80001000, target=0x80001010, brType=uncond} -- and an unconditional is
|            seeded STRONGLY TAKEN, so one retire is enough.
| LENGTH MATTERS. The fetch-directed FTB confirms its claim with a 4-bit LENGTH match and
| nothing else, so the branch it learns and the non-branch that replaces it are BOTH one
| word. With a 2-word `bra.w` the length check alone would decline (and `ftqLenBad` would
| recover), masking the defect behind an accident of encoding rather than any real check
| that the instruction IS a branch. One word against one word is the honest case, and it
| exercises the FTB confirmation path and the decode-time BTB fallback together.
|
|   Phase 2: repoint the L3 leaf to PFN B (PA 0x00400000) and PFLUSHA. PFN B holds
|              +0x00  moveq  #0x11,%d3   <- the instruction now at the trained PC
|              +0x02  moveq  #0x22,%d4   <- fall-through; SKIPPED if the stale entry fires
|              +0x04  rts
|              +0x10  moveq  #0x77,%d4   <- WRONG PATH, at the stale target
|              +0x12  rts
|            `jsr (%a1)` again.
|
| WHAT THE DEFECT LOOKS LIKE. `FetchAlignPlugin`'s `btbQueryValid0` was gated only on "a
| slot was emitted", never on the slot being a branch, and `predTaken` -- stamped on every
| micro-op of the emitted instruction -- is read ONLY by `BranchEuPlugin`. The moveq at
| 0x80001000 is an ALU micro-op, so its `predTaken` is verified by NOTHING: fetch redirects
| to 0x80001010 and the wrong-path `moveq #0x77,%d4` RETIRES. D4 ends 0x77 and the real
| fall-through never executes. Nothing recovers; this is architectural divergence, not a
| mispredict.
|
| PASS sentinel: 0xC0FFEE00 (D3=0x11 AND D4=0x22 -- the real fall-through ran).
| FAIL sentinels:
|   0xDEAD0B01 -- phase-1 training call did not take the bra (setup broken)
|   0xDEAD0B02 -- the remapped page's FIRST instruction did not run (remap/PFLUSHA broken)
|   0xDEAD0B03 -- D4 == 0x77: the STALE BTB ENTRY REDIRECTED FETCH ON A NON-BRANCH and the
|                 wrong-path instruction retired  <-- the defect
|   0xDEAD0B04 -- D4 is neither 0x22 nor 0x77 (unexpected third outcome)

    .text
    .org 0

_start:
    lea     0x00010000, %a7
    move.l  #_trap_h, 0x00100080
    move.l  #0x00100000, %d0
    movec   %d0, %vbr

    | ITT0 covers ONLY 0x40000000-0x40FFFFFF (this program image), so VA 0x80001000 is
    | left to the real I-side table walk -- which is what makes the remap observable.
    move.l  #0x4000C000, %d0
    movec   %d0, %itt0
    move.l  #0x007FA000, %d0
    movec   %d0, %dtt0
    move.l  #0xFF00A000, %d0
    movec   %d0, %dtt1

    move.l  #0x00200000, %d0
    movec   %d0, %urp
    movec   %d0, %srp

    | VA 0x80001000: L1[0x40] -> L2[0x00] -> L3[0x01].
    move.l  #0x0021000a, 0x00200100
    move.l  #0x0022000a, 0x00210000
    move.l  #0x00300009, 0x00220004         | L3[0x01] = PFN A, U, DT=01

    | ---- PFN A: the page that OWNS the branch the BTB learns ----
    move.l  #0x600e7a55, 0x00300000         | bra.s 0x80001010 ; moveq #0x55,%d5 (never reached)
    move.l  #0x4e714e71, 0x00300004
    move.l  #0x4e714e71, 0x00300008
    move.l  #0x4e714e71, 0x0030000c
    move.l  #0x7a334e75, 0x00300010         | moveq #0x33,%d5 ; rts

    | ---- PFN B: the page the SAME VA maps to after the remap ----
    move.l  #0x76117822, 0x00400000         | moveq #0x11,%d3 ; moveq #0x22,%d4
    move.l  #0x4e754e71, 0x00400004         | rts ; nop
    move.l  #0x4e714e71, 0x00400008
    move.l  #0x4e714e71, 0x0040000c
    move.l  #0x78774e75, 0x00400010         | moveq #0x77,%d4 ; rts   (WRONG PATH)

    move.l  #0x00008770, %d0
    movec   %d0, %tc

    move.l  #0x80001000, %a1

    | ---- Phase 1: execute the branch so the BTB legitimately learns it ----
    moveq   #0, %d5
    jsr     (%a1)
    cmp.l   #0x33, %d5
    bne     _fail_setup
    moveq   #0, %d5
    jsr     (%a1)                           | again: the entry is now a real BTB hit
    cmp.l   #0x33, %d5
    bne     _fail_setup

    | ---- Phase 2: change the TRANSLATION under the still-valid BTB entry ----
    | NOTE the deliberate absence of any CINV/CPUSH here. A 68040 remap does not require
    | one (the L1I is physically tagged), and issuing one would invalidate the BTB through
    | IcachePlugin's maintInvalidateAll and hide the defect.
    move.l  #0x00400009, 0x00220004         | L3[0x01] = PFN B, U, DT=01
    pflusha

    | ---- The observation ----
    moveq   #0, %d3
    moveq   #0, %d4
    jsr     (%a1)
    cmp.l   #0x11, %d3
    bne     _fail_remap
    cmp.l   #0x22, %d4
    bne     _fail_stale

    trap    #0

_fail_setup:
    move.l  #0xDEAD0B01, 0xFFFF0000
_h1: bra    _h1

_fail_remap:
    move.l  #0xDEAD0B02, 0xFFFF0000
_h2: bra    _h2

_fail_stale:
    cmp.l   #0x77, %d4
    beq     _fail_wrongpath
    move.l  #0xDEAD0B04, 0xFFFF0000
_h3: bra    _h3
_fail_wrongpath:
    move.l  #0xDEAD0B03, 0xFFFF0000
_h4: bra    _h4

_trap_h:
    move.l  #0xC0FFEE00, 0xFFFF0000
_done: bra  _done
