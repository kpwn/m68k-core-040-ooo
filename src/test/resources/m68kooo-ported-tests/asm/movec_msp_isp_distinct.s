| movec_msp_isp_distinct.s — MOVEC MSP/ISP write distinct slots.
|
| Regression for task #10 / docs/sp_msp_audit.md headline bug #2:
| pre-fix, MOVEC CR_MSP and MOVEC CR_ISP both wrote the same `ssp`
| shadow + PHYS_SSP_TAG slot.  A guest that programmed MSP and ISP
| separately at boot saw both values collapse to one register.
|
| Test plan:
|   1. Start S=1 (cold-boot supervisor), M=0 → SR.M=0 active = ISP.
|   2. MOVEC #0xAAAA0000, ISP   ; expect ISP slot = 0xAAAA0000
|   3. MOVEC #0xBBBB0000, MSP   ; expect MSP slot = 0xBBBB0000
|   4. MOVEC ISP, D0            ; D0 must read 0xAAAA0000
|   5. MOVEC MSP, D1            ; D1 must read 0xBBBB0000
|
| Pre-fix: step 4 returned 0xBBBB0000 (because step 3's MSP write went
| into the same slot as step 2's ISP write).  Post-fix: distinct.
|
| This test does NOT touch SR.M — the read path's correctness for the
| M=0 vs M=1 selection is covered by exc_stack_atomicity_stress.s and
| friends.  Here we only validate that the two CR registers are
| BACKED by distinct hardware slots.
|
| PASS sentinel: 0xC0FFEE00.
| FAIL sentinels:
|   0xDEAD0501 — D0 (ISP read) != 0xAAAA0000  (ISP value lost / aliased)
|   0xDEAD0502 — D1 (MSP read) != 0xBBBB0000  (MSP value lost / aliased)

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000

_start:
    | A7 starts at the cold-boot SSP.  Don't touch it; we'll only
    | exercise the inactive ISP/MSP slots via MOVEC.

    | Step 2 — write ISP via MOVEC.
    move.l  #0xAAAA0000, %d0
    .short  0x4E7B, 0x0804             | MOVEC d0,ISP (cr=0x804)

    | Step 3 — write MSP via MOVEC.
    move.l  #0xBBBB0000, %d0
    .short  0x4E7B, 0x0803             | MOVEC d0,MSP (cr=0x803)

    | Step 4 — read ISP back.  Pre-fix bug: this returned 0xBBBB0000.
    .short  0x4E7A, 0x0804             | MOVEC ISP,d0 (cr=0x804, dest = D0 in ext bits 15:12=0)
    cmp.l   #0xAAAA0000, %d0
    bne     _fail_isp

    | Step 5 — read MSP back.
    .short  0x4E7A, 0x1803             | MOVEC MSP,d1 (cr=0x803, dest = D1 in ext bits 15:12=1)
    cmp.l   #0xBBBB0000, %d1
    bne     _fail_msp

    | PASS
    lea     PASS_SENT, %a0
    move.l  #0xC0FFEE00, %d2
    move.l  %d2, (%a0)
_halt:
    bra     _halt

_fail_isp:
    move.l  #0xDEAD0501, %d2
    bra     _do_fail
_fail_msp:
    move.l  #0xDEAD0502, %d2
_do_fail:
    lea     PASS_SENT, %a0
    move.l  %d2, (%a0)
_halt_fail:
    bra     _halt_fail
