| swap_test.s — SWAP Dn word-reversal test
|
| SWAP exchanges the upper and lower 16-bit halves of a data register.
| Flags: N and Z reflect the 32-bit result; V and C are cleared; X is unchanged.
|
| Test cases:
|   SWAP 0x12345678 → 0x56781234
|   SWAP 0xABCD0000 → 0x0000ABCD  (lower half = 0 → no Z flag)
|   SWAP 0x00000000 → 0x00000000  (Z=1)
|   SWAP 0x8000BEEF → 0xBEEF8000  (N=1 after, MSB of result set)
|   Double-swap restores original
|
| PASS sentinel: 0xC0FFEE00 → 0xFFFF0000

    .text
    .org 0

_start:
    | ── Basic swap ──
    move.l  #0x12345678, %d0    | D0 = 0x12345678
    swap    %d0                 | D0 = 0x56781234
    move.l  #0x56781234, %d1
    cmp.l   %d1, %d0
    bne     _fail

    | ── Swap where lower half becomes zero ──
    move.l  #0xABCD0000, %d0    | D0 = 0xABCD0000
    swap    %d0                 | D0 = 0x0000ABCD (Z=0 since result != 0)
    move.l  #0x0000ABCD, %d1
    cmp.l   %d1, %d0
    bne     _fail

    | ── Swap zero → Z=1 ──
    moveq   #0, %d0             | D0 = 0x00000000
    swap    %d0                 | D0 = 0x00000000, Z=1
    bne     _fail               | Z should be 1

    | ── Swap: N=1 when bit 31 of result is set ──
    move.l  #0x00008000, %d0    | upper=0, lower=0x8000
    swap    %d0                 | D0 = 0x80000000, N=1
    bpl     _fail               | N should be 1

    | ── Double swap restores original ──
    move.l  #0xDEAD1234, %d2    | D2 = 0xDEAD1234
    swap    %d2                 | D2 = 0x1234DEAD
    swap    %d2                 | D2 = 0xDEAD1234 (restored)
    move.l  #0xDEAD1234, %d3
    cmp.l   %d3, %d2
    bne     _fail

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
