| divl_sz0_dual_dest.s — DIV.L SZ=0 Dq!=Dr dual-destination (D-9f)
|
| PRM §4.43 DIVSL.L / DIVUL.L SZ=0 with Dq != Dr: 32÷32 → 32-bit quotient
| to Dq AND 32-bit remainder to Dr.  Dual-destination form — landed in
| Stage D-9f via dual-dst PRF (single µop, writes Q via cdb0 and R via
| cdb_alu_hi in the same retire cycle).
|
| NOTE: GNU as syntax.  `divul.l <src>, Dr:Dq` is SZ=0 Dq!=Dr (32-bit
| dividend, dual-dest); `divu.l <src>, Dr:Dq` (without the `l` in the
| mnemonic) is SZ=1 (64-bit dividend).  We target `divul.l` / `divsl.l`
| here because SZ=1 is still deferred (needs a 3rd source port for the
| 64-bit dividend compose).
|
| Corners covered:
|   - DIVUL.L dual-dest: 100/7 → Dq=14, Dr=2 (both checked)
|   - DIVSL.L dual-dest with negative dividend: -100/7 → Dq=-14, Dr=-2
|   - DIVSL.L both negatives: -100/-7 → Dq=+14, Dr=-2 (remainder sign
|     follows dividend sign per PRM)
|   - Flag write on dual-dest (N/Z/V/C from quotient, X unchanged)
|
| Legacy (2-µop _R then _Q crack) is retired by D-9f: the V2 decoder
| now emits a single dual-dst µop.  mul_div.v's FSM DONE fires
| res_hi_valid = div_final_rem → cdb_alu_hi tag = phys_dst_b = Dr.

    .text
    .org 0

_start:
    | ── Test 1: DIVUL.L reg-src Dq!=Dr ────────────────────────────
    | D0=100, D1=7, DIVUL.L D1,D2:D0 → D0=quot=14, D2=rem=2.
    move.l  #100, %d0
    move.l  #7,   %d1
    move.l  #0xDEADBEEF, %d2           | junk in Dr to verify overwrite
    divul.l %d1, %d2:%d0               | D0 = 14; D2 = 2
    cmp.l   #14, %d0
    bne     _fail
    cmp.l   #2,  %d2
    bne     _fail

    | ── Test 2: DIVSL.L reg-src negative dividend, Dq!=Dr ─────────
    | D3=-100, D4=7, DIVSL.L D4,D5:D3 → D3=-14, D5=-2 (Musashi semantics).
    move.l  #-100, %d3
    move.l  #7,    %d4
    move.l  #0x12345678, %d5
    divsl.l %d4, %d5:%d3               | D3 = -14, D5 = -2
    cmp.l   #-14, %d3
    bne     _fail
    cmp.l   #-2,  %d5
    bne     _fail

    | ── Test 3: DIVSL.L both negatives, Dq!=Dr ────────────────────
    | D6=-100, D7=-7, DIVSL.L D7,D0:D6 → D6=+14, D0=-2.
    move.l  #-100, %d6
    move.l  #-7,   %d7
    move.l  #0xA5A5A5A5, %d0           | junk
    divsl.l %d7, %d0:%d6               | D6 = 14, D0 = -2
    cmp.l   #14, %d6
    bne     _fail
    cmp.l   #-2, %d0
    bne     _fail

    | ── Test 4: DIVUL.L imm-src Dq!=Dr ────────────────────────────
    | D1=1000, divisor imm32=13, DIVUL.L #13,D3:D1 → D1=76, D3=12.
    move.l  #1000, %d1
    move.l  #0xFEEDFACE, %d3           | junk
    divul.l #13, %d3:%d1               | D1 = 76, D3 = 12
    cmp.l   #76, %d1
    bne     _fail
    cmp.l   #12, %d3
    bne     _fail

    | ── Test 5: Zero quotient sets Z flag (dual-dest) ─────────────
    | D4=+3, D5=+7, DIVSL.L D5,D6:D4 → D4=0, D6=3.  Z=1.
    move.l  #3, %d4
    move.l  #7, %d5
    move.l  #0x00000000, %d6
    divsl.l %d5, %d6:%d4               | D4 = 0, D6 = 3; Z=1
    bne     _fail                      | Z must be set (quotient==0)
    cmp.l   #0, %d4
    bne     _fail
    cmp.l   #3, %d6
    bne     _fail

    | ── PASS sentinel ─────────────────────────────────────────────
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
    bra     _halt
