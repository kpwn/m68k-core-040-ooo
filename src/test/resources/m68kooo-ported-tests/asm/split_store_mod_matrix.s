| split_store_mod_matrix.s — misaligned STORE offset matrix, pre-seeded.
|
| Systematic sweep of every misaligned store shape the LSU splits:
|   LONG at 1 (mod 4), 2 (mod 4), 3 (mod 4)  — all split
|   WORD at 3 (mod 4)                        — the only split WORD
| plus two non-split controls (WORD at 1, WORD at 2) so a failure of
| the split cases cannot be blamed on the byte-lane helpers generally.
|
| Every slot is PRE-SEEDED with 0xA1A2A3A4 / 0xB1B2B3B4 before the
| store, and both containing longs are read back with ALIGNED loads
| afterwards.  Seeding is load-bearing: against a 0x00- or 0xFF-filled
| region a store that writes nothing is indistinguishable from a store
| that wrote the expected zeros/ones.  Each expected value below still
| contains seed bytes in the untouched lanes, so "beat lost" and "beat
| landed" are always distinguishable.
|
| PASS sentinel: 0xC0FFEE00.
| FAIL sentinels: 0xDEAD03nn where nn is
|   01/02  LONG@1 low/high long wrong
|   03/04  LONG@2 low/high long wrong
|   05/06  LONG@3 low/high long wrong
|   07/08  WORD@3 low/high long wrong
|   09     WORD@1 (non-split control) wrong
|   0A     WORD@2 (non-split control) wrong
|   0B     CONTROL: seeding itself did not read back
|   0C     unexpected address error (vec 3)

    .text

    .equ BASE, 0x00100000

_start:
    lea     0x00010000, %a7
    move.l  #_addr_err, 0x0000000C

    | ── Seed all six slots ────────────────────────────────────────
    lea     BASE, %a0
    move.l  #0xA1A2A3A4, 0x00(%a0)
    move.l  #0xB1B2B3B4, 0x04(%a0)
    move.l  #0xA1A2A3A4, 0x10(%a0)
    move.l  #0xB1B2B3B4, 0x14(%a0)
    move.l  #0xA1A2A3A4, 0x20(%a0)
    move.l  #0xB1B2B3B4, 0x24(%a0)
    move.l  #0xA1A2A3A4, 0x30(%a0)
    move.l  #0xB1B2B3B4, 0x34(%a0)
    move.l  #0xA1A2A3A4, 0x40(%a0)
    move.l  #0xB1B2B3B4, 0x44(%a0)
    move.l  #0xA1A2A3A4, 0x50(%a0)
    move.l  #0xB1B2B3B4, 0x54(%a0)

    | CONTROL: the seed must be observable.
    move.l  0x20(%a0), %d1
    cmp.l   #0xA1A2A3A4, %d1
    bne     _f0B
    move.l  0x24(%a0), %d1
    cmp.l   #0xB1B2B3B4, %d1
    bne     _f0B

    | ── LONG store at 1 (mod 4) ───────────────────────────────────
    | bytes [1]=11 [2]=22 [3]=33 [4]=44
    move.l  #0x11223344, %d0
    move.l  %d0, 0x01(%a0)
    move.l  0x00(%a0), %d1
    cmp.l   #0xA1112233, %d1
    bne     _f01
    move.l  0x04(%a0), %d1
    cmp.l   #0x44B2B3B4, %d1
    bne     _f02

    | ── LONG store at 2 (mod 4) — THE HARDWARE CASE ───────────────
    | bytes [2]=55 [3]=66 [4]=77 [5]=88
    move.l  #0x55667788, %d0
    move.l  %d0, 0x12(%a0)
    move.l  0x10(%a0), %d1
    cmp.l   #0xA1A25566, %d1
    bne     _f03
    move.l  0x14(%a0), %d1
    cmp.l   #0x7788B3B4, %d1
    bne     _f04

    | ── LONG store at 3 (mod 4) ───────────────────────────────────
    | bytes [3]=99 [4]=AA [5]=BB [6]=CC
    move.l  #0x99AABBCC, %d0
    move.l  %d0, 0x23(%a0)
    move.l  0x20(%a0), %d1
    cmp.l   #0xA1A2A399, %d1
    bne     _f05
    move.l  0x24(%a0), %d1
    cmp.l   #0xAABBCCB4, %d1
    bne     _f06

    | ── WORD store at 3 (mod 4) — the only split WORD ─────────────
    | bytes [3]=DE [4]=AD
    move.w  #0xDEAD, %d0
    move.w  %d0, 0x33(%a0)
    move.l  0x30(%a0), %d1
    cmp.l   #0xA1A2A3DE, %d1
    bne     _f07
    move.l  0x34(%a0), %d1
    cmp.l   #0xADB2B3B4, %d1
    bne     _f08

    | ── WORD store at 1 (mod 4) — NON-SPLIT control ───────────────
    move.w  #0xBEEF, %d0
    move.w  %d0, 0x41(%a0)
    move.l  0x40(%a0), %d1
    cmp.l   #0xA1BEEFA4, %d1
    bne     _f09

    | ── WORD store at 2 (mod 4) — NON-SPLIT control ───────────────
    move.w  #0xF00D, %d0
    move.w  %d0, 0x52(%a0)
    move.l  0x50(%a0), %d1
    cmp.l   #0xA1A2F00D, %d1
    bne     _f0A

    lea     0xFFFF0000, %a1
    move.l  #0xC0FFEE00, (%a1)
_halt:
    bra     _halt

_f01:   move.l #0xDEAD0301, 0xFFFF0000
_h01:   bra _h01
_f02:   move.l #0xDEAD0302, 0xFFFF0000
_h02:   bra _h02
_f03:   move.l #0xDEAD0303, 0xFFFF0000
_h03:   bra _h03
_f04:   move.l #0xDEAD0304, 0xFFFF0000
_h04:   bra _h04
_f05:   move.l #0xDEAD0305, 0xFFFF0000
_h05:   bra _h05
_f06:   move.l #0xDEAD0306, 0xFFFF0000
_h06:   bra _h06
_f07:   move.l #0xDEAD0307, 0xFFFF0000
_h07:   bra _h07
_f08:   move.l #0xDEAD0308, 0xFFFF0000
_h08:   bra _h08
_f09:   move.l #0xDEAD0309, 0xFFFF0000
_h09:   bra _h09
_f0A:   move.l #0xDEAD030A, 0xFFFF0000
_h0A:   bra _h0A
_f0B:   move.l #0xDEAD030B, 0xFFFF0000
_h0B:   bra _h0B
_addr_err:
    move.l  #0xDEAD030C, 0xFFFF0000
_h0C:   bra _h0C
