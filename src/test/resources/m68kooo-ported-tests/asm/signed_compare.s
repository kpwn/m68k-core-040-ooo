| signed_compare.s — Signed comparison conditions: BGT, BLT, BGE, BLE
|
| The 68040 signed branch conditions use N and V flags together:
|   BGT: !(Z | (N ^ V))
|   BLT: N ^ V
|   BGE: !(N ^ V)
|   BLE: Z | (N ^ V)
|
| This test exercises CMP.L followed by each signed Bcc to verify
| the ALU produces correct N, Z, V flags for signed comparisons.
|
| Test matrix (all using CMP.L Dm,Dn  i.e. Dn - Dm):
|   5  vs  3  → positive, no overflow → BGT, BGE taken; BLT, BLE not
|   3  vs  5  → negative, no overflow → BLT, BLE taken; BGT, BGE not
|   5  vs  5  → zero                  → BGE, BLE taken; BGT, BLT not
|   0x80000000 vs 1 → signed overflow case
|
| PASS sentinel: 0xC0FFEE00 → 0xFFFF0000

    .text
    .org 0

_start:
    | ── 5 > 3 (signed): BGT taken, BLT not taken ──
    moveq   #5, %d0
    moveq   #3, %d1
    cmp.l   %d1, %d0            | computes D0-D1 = 5-3 = 2, N=0, Z=0, V=0
    ble     _fail               | 5 > 3, so BLE must NOT be taken
    blt     _fail               | BLT must NOT be taken
    bgt     _gt1_ok             | BGT MUST be taken
    bra     _fail
_gt1_ok:
    bge     _ge1_ok             | BGE MUST be taken
    bra     _fail
_ge1_ok:

    | ── 3 < 5 (signed): BLT taken, BGT not taken ──
    moveq   #3, %d0
    moveq   #5, %d1
    cmp.l   %d1, %d0            | 3-5 = -2, N=1, Z=0, V=0
    bgt     _fail               | BGT must NOT be taken
    bge     _fail               | BGE must NOT be taken
    blt     _lt1_ok             | BLT MUST be taken
    bra     _fail
_lt1_ok:
    ble     _le1_ok             | BLE MUST be taken
    bra     _fail
_le1_ok:

    | ── 5 == 5 (signed): BGE and BLE taken, BGT and BLT not taken ──
    moveq   #5, %d0
    moveq   #5, %d1
    cmp.l   %d1, %d0            | 5-5 = 0, Z=1, N=0, V=0
    bgt     _fail               | BGT must NOT be taken (equal)
    blt     _fail               | BLT must NOT be taken
    bge     _ge2_ok             | BGE MUST be taken (>=)
    bra     _fail
_ge2_ok:
    ble     _le2_ok             | BLE MUST be taken (<=)
    bra     _fail
_le2_ok:

    | ── Negative: -1 < 0 ──
    move.l  #0xFFFFFFFF, %d0    | D0 = -1
    moveq   #0, %d1             | D1 = 0
    cmp.l   %d1, %d0            | -1 - 0 = -1, N=1, V=0, Z=0
    bge     _fail               | -1 not >= 0
    bgt     _fail               | -1 not > 0
    blt     _lt2_ok             | -1 < 0 → BLT must be taken
    bra     _fail
_lt2_ok:

    | ── PASS ──────────────────────────────────────────────────────────────
_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)          | PASS sentinel
_halt:
    stop    #0x2700
    bra     _halt

    | ── FAIL ──────────────────────────────────────────────────────────────
_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)          | FAIL sentinel
_halt_fail:
    stop    #0x2700
    bra     _halt_fail
