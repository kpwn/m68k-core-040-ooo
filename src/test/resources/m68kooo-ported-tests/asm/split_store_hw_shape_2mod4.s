| split_store_hw_shape_2mod4.s — the exact shape observed on hardware.
|
| HW evidence (System 7.5.3 boot failure): ROM 0x40836b68 does
|   movel %sp,%fp@(-460)
| with A6 = 0x005fa16a, so the slot is 0x005f9f9e — 2 (mod 4), a
| misaligned LONG store split into two aligned bus beats.  Earlier,
| unrelated graphics data had been written over that region:
|   0x005f9f9c = 0xf9001000
|   0x005f9fa0 = 0x84000000
| The later reload (0x40837958, moveal %fp@(-460),%sp) read back
| 0x10008400 = low16(0xf9001000):high16(0x84000000) — i.e. composed
| ENTIRELY of the stale graphics data, with no fragment of the saved
| SP in either half.
|
| This test reproduces that geometry exactly: the two containing longs
| are pre-seeded with those same two graphics words, a stack-pointer-
| shaped value is stored to the 2-mod-4 slot, and BOTH containing longs
| are read back.
|
| PRE-SEEDING IS THE POINT.  If the region were zero/0xFF-filled a lost
| store would be invisible.  The seeds are chosen so that each half of
| the store, if lost, leaves a recognisable stale fingerprint.
|
| PASS sentinel: 0xC0FFEE00.
| FAIL sentinels — each one names WHICH half went missing:
|   0xDEAD0201 — low long wrong.  0xF9001000 here (= untouched seed)
|                means the FIRST beat never landed.
|   0xDEAD0202 — high long wrong.  0x84000000 here (= untouched seed)
|                means the SECOND beat never landed.
|   0xDEAD0203 — misaligned read-back of the slot itself wrong.
|   0xDEAD0204 — CONTROL: aligned seed write-back of the low long
|                failed, i.e. plain aligned stores are broken and no
|                conclusion about the split path is available.
|   0xDEAD0205 — CONTROL: aligned seed write-back of the high long
|                failed.
|   0xDEAD0206 — unexpected address error (vec 3) — we must NOT fault.

    .text

    .equ SLOTBASE, 0x00100000        | 4-aligned, quiet RAM
    .equ SPVAL,    0x005FA000        | stack-pointer-shaped payload

_start:
    lea     0x00010000, %a7
    move.l  #_addr_err, 0x0000000C   | vector 3 canary

    | ── Seed the two containing longs with the HW graphics words ──
    lea     SLOTBASE, %a0
    move.l  #0xF9001000, (%a0)       | 0x00100000
    move.l  #0x84000000, 4(%a0)      | 0x00100004

    | CONTROL: prove the aligned store path works at all before we
    | conclude anything about the split path.
    move.l  (%a0), %d1
    cmp.l   #0xF9001000, %d1
    bne     _fail_ctl_lo
    move.l  4(%a0), %d1
    cmp.l   #0x84000000, %d1
    bne     _fail_ctl_hi

    | ── THE ACCESS UNDER TEST ────────────────────────────────────
    | LONG store to SLOTBASE+2 (2 mod 4).  Bytes written:
    |   [+2]=0x00 [+3]=0x5F [+4]=0xA0 [+5]=0x00
    move.l  #SPVAL, %d0
    move.l  %d0, 2(%a0)

    | ── Read both containing longs back with ALIGNED accesses ────
    | low  long = F9 00 | 00 5F  -> 0xF900005F
    | high long = A0 00 | 00 00  -> 0xA0000000
    move.l  (%a0), %d1
    cmp.l   #0xF900005F, %d1
    bne     _fail_lo

    move.l  4(%a0), %d2
    cmp.l   #0xA0000000, %d2
    bne     _fail_hi

    | ── And read it back the way the ROM does: misaligned LONG load
    | through the same split machinery.
    move.l  2(%a0), %d3
    cmp.l   #SPVAL, %d3
    bne     _fail_rb

    lea     0xFFFF0000, %a1
    move.l  #0xC0FFEE00, (%a1)
_halt:
    bra     _halt

_fail_lo:
    move.l  #0xDEAD0201, 0xFFFF0000
_h1:    bra _h1
_fail_hi:
    move.l  #0xDEAD0202, 0xFFFF0000
_h2:    bra _h2
_fail_rb:
    move.l  #0xDEAD0203, 0xFFFF0000
_h3:    bra _h3
_fail_ctl_lo:
    move.l  #0xDEAD0204, 0xFFFF0000
_h4:    bra _h4
_fail_ctl_hi:
    move.l  #0xDEAD0205, 0xFFFF0000
_h5:    bra _h5
_addr_err:
    move.l  #0xDEAD0206, 0xFFFF0000
_h6:    bra _h6
