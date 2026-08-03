| split_store_sp_save_restore_2mod4.s — byte-for-byte reproduction of the
| System 7.5.3 hardware failure's instruction pair and addresses.
|
| Hardware evidence:
|   0x40836b68   movel   %sp,%fp@(-460)     | save SP into a frame slot
|   0x40837958   moveal  %fp@(-460),%sp     | restore it
| These are the ONLY two such instructions in the whole Q700 ROM.  At
| the failure A6 = 0x005FA16A, so the slot is A6-460 = 0x005F9F9E —
| 2 (mod 4), and also 0x1E (mod 0x20), i.e. the split ALSO crosses a
| 32-byte D-cache line boundary.  Earlier, unrelated graphics data had
| been written over the region:
|   0x005F9F9C = 0xF9001000      0x005F9FA0 = 0x84000000
| and the restore read back 0x10008400 = low16(0xF9001000) :
| high16(0x84000000) — composed ENTIRELY of the stale graphics data,
| with no fragment of the saved SP in either half.
|
| Everything above is reproduced literally here: same addresses, same
| seed words, same instruction forms, same 2-mod-4 / line-crossing
| geometry.  What differs from every other split test in the suite is
| the OPERAND: the stored datum is A7 itself and the loaded datum is
| written back to A7.  A7 has its own shadow/commit handling in the
| core (RTS / exception-frame paths), so "split store whose source is
| A7" and "split load whose destination is A7" are distinct corners
| from a plain Dn split.
|
| Run twice: uncached (MMU off), then with the D-cache on (TC.E=1,
| copyback DTT0, CACR.DE=1) — the configuration the hardware runs in.
|
| Note the dedicated 0x10008400 check: if BOTH halves were lost, the
| restore would return exactly the value hardware observed, and
| sentinel 0x04 / 0x14 names that outcome specifically.
|
| PASS sentinel: 0xC0FFEE00.
| FAIL sentinels 0xDEAD0Ann:
|   01/11  restored SP != saved SP            (uncached / cached)
|   02/12  low containing long wrong          (first beat lost)
|   03/13  high containing long wrong         (second beat lost)
|   04/14  restored SP == 0x10008400 — the EXACT hardware symptom
|   05     CONTROL: seeding not observable
|   0B     CONTROL: CACR.DE did not stick — cached pass is void
|   0A     unexpected address error (vec 3)

    .text

    .equ FP,     0x005FA16A         | A6 at the moment of failure
    .equ SLOT,   0x005F9F9E         | FP-460, 2 mod 4, 0x1E mod 0x20
    .equ SLOTLO, 0x005F9F9C
    .equ SLOTHI, 0x005F9FA0
    .equ SPVAL,  0x005F9F60         | live stack, below the slot
    .equ SAFESP, 0x00010000

_start:
    lea     SAFESP, %a7
    move.l  #_addr_err, 0x0000000C

    | ══════════════ PASS 1: uncached ══════════════
    bsr     _seed
    move.l  SLOTLO, %d1
    cmp.l   #0xF9001000, %d1
    bne     _f05

    movea.l #FP, %a6
    movea.l #SPVAL, %a7
    move.l  %a7, %d3                | remember the SP we are about to save
    move.l  %a7, -460(%a6)          | ── THE SAVE (split store, src = A7)
    movea.l #0x00090000, %a7        | clobber A7 so the restore must work
    movea.l -460(%a6), %a7          | ── THE RESTORE (split load, dst = A7)
    move.l  %a7, %d4
    lea     SAFESP, %a7             | park the stack somewhere safe again

    cmp.l   #0x10008400, %d4        | the literal hardware symptom
    beq     _f04
    cmp.l   %d3, %d4
    bne     _f01
    move.l  SLOTLO, %d1
    cmp.l   #0xF900005F, %d1        | F9 00 | 00 5F
    bne     _f02
    move.l  SLOTHI, %d1
    cmp.l   #0x9F600000, %d1        | 9F 60 | 00 00
    bne     _f03

    | ══════════════ PASS 2: D-cache enabled ══════════════
    move.l  #0x4000C000, %d7
    movec   %d7, %itt0
    move.l  #0x0000E020, %d7        | 0x00xxxxxx pass-through, CM=01 copyback
    movec   %d7, %dtt0
    move.l  #0xFF00E060, %d7        | sentinel page non-cacheable
    movec   %d7, %dtt1
    move.l  #0x8000, %d7
    movec   %d7, %tc
    move.l  #0x80000000, %d7
    movec   %d7, %cacr
    movec   %cacr, %d6
    cmp.l   %d7, %d6
    bne     _f0B

    bsr     _seed

    movea.l #FP, %a6
    movea.l #SPVAL, %a7
    move.l  %a7, %d3
    move.l  %a7, -460(%a6)
    movea.l #0x00090000, %a7
    movea.l -460(%a6), %a7
    move.l  %a7, %d4
    lea     SAFESP, %a7

    cmp.l   #0x10008400, %d4
    beq     _f14
    cmp.l   %d3, %d4
    bne     _f11
    move.l  SLOTLO, %d1
    cmp.l   #0xF900005F, %d1
    bne     _f12
    move.l  SLOTHI, %d1
    cmp.l   #0x9F600000, %d1
    bne     _f13

    lea     0xFFFF0000, %a2
    move.l  #0xC0FFEE00, (%a2)
_halt:  bra _halt

| Re-lay the exact graphics words the hardware had left there.
_seed:
    move.l  #0xF9001000, SLOTLO
    move.l  #0x84000000, SLOTHI
    rts

_f01:   move.l #0xDEAD0A01, 0xFFFF0000
_h01:   bra _h01
_f02:   move.l #0xDEAD0A02, 0xFFFF0000
_h02:   bra _h02
_f03:   move.l #0xDEAD0A03, 0xFFFF0000
_h03:   bra _h03
_f04:   move.l #0xDEAD0A04, 0xFFFF0000
_h04:   bra _h04
_f05:   move.l #0xDEAD0A05, 0xFFFF0000
_h05:   bra _h05
_f11:   move.l #0xDEAD0A11, 0xFFFF0000
_h11:   bra _h11
_f12:   move.l #0xDEAD0A12, 0xFFFF0000
_h12:   bra _h12
_f13:   move.l #0xDEAD0A13, 0xFFFF0000
_h13:   bra _h13
_f14:   move.l #0xDEAD0A14, 0xFFFF0000
_h14:   bra _h14
_f0B:   move.l #0xDEAD0A0B, 0xFFFF0000
_h0B:   bra _h0B
_addr_err:
    move.l  #0xDEAD0A0A, 0xFFFF0000
_h0A:   bra _h0A
