| btst_bset_bclr.s — BTST / BSET / BCLR / BCHG on Dn (static and dynamic)
|
| Hypothesis: these bit ops on a data register set Z iff the tested bit
| was zero (Z = ~old_bit).  No other flag is affected.
|
| BTST does not modify the destination; BSET sets the bit (dst=src|1<<n);
| BCLR clears the bit (dst=src&~1<<n); BCHG flips it (dst=src^1<<n).
|
| For a Dn destination the bit number is modulo 32.  We use static
| (#imm) and dynamic (Dn) forms.
|
| Sequence:
|  1. D0=0x0000_0100, BTST #8,D0    → Z=0 (bit 8 was 1), D0 unchanged
|  2. D1=0x0000_0000, BTST #3,D1    → Z=1 (bit 3 was 0)
|  3. D2=0x0000_0000, BSET #4,D2    → D2=0x10, Z=1 (old bit was 0)
|  4. D3=0xFFFFFFFF, BCLR #31,D3    → D3=0x7FFFFFFF, Z=0 (old bit was 1)
|  5. D4=0x00000001, BCHG #0,D4     → D4=0x00000000, Z=0 (old bit was 1)
|  6. D5=0x00000000, dynamic: D6=#5, BCHG D6,D5 → D5=0x20, Z=1
|
| Flag check immediately after the bit op; value check after.

    .text
    .org 0

_start:
    | ── 1. BTST of a set bit: Z=0 ──
    move.l  #0x00000100, %d0
    btst    #8, %d0                  | Z=0 (bit 8 = 1)
    beq     _fail                    | Z=0
    | Verify D0 unchanged
    move.l  #0x00000100, %d7
    cmp.l   %d7, %d0
    bne     _fail

    | ── 2. BTST of a cleared bit: Z=1 ──
    move.l  #0x00000000, %d1
    btst    #3, %d1                  | Z=1 (bit 3 was 0)
    bne     _fail                    | Z=1

    | ── 3. BSET #4,D2 on zero: Z=1 (old bit=0), D2=0x10 ──
    move.l  #0x00000000, %d2
    bset    #4, %d2                  | Z=1, D2 = 0x10
    bne     _fail                    | Z=1
    move.l  #0x00000010, %d7
    cmp.l   %d7, %d2
    bne     _fail

    | ── 4. BCLR #31,D3 on 0xFFFFFFFF: Z=0 (old bit=1), D3=0x7FFFFFFF ──
    move.l  #0xFFFFFFFF, %d3
    bclr    #31, %d3                 | Z=0, D3 = 0x7FFFFFFF
    beq     _fail                    | Z=0
    move.l  #0x7FFFFFFF, %d7
    cmp.l   %d7, %d3
    bne     _fail

    | ── 5. BCHG #0,D4 flips LSB of 1 → 0 with Z=0 (old bit=1) ──
    move.l  #0x00000001, %d4
    bchg    #0, %d4                  | Z=0, D4 = 0
    beq     _fail                    | Z=0 (old bit was 1)
    tst.l   %d4
    bne     _fail                    | result must be 0

    | ── 6. Dynamic BCHG D6,D5 where D6=5, D5=0 → D5=0x20, Z=1 ──
    move.l  #0x00000000, %d5
    moveq   #5, %d6
    bchg    %d6, %d5                 | Z=1 (old bit was 0), D5 = 0x20
    bne     _fail                    | Z=1
    move.l  #0x00000020, %d7
    cmp.l   %d7, %d5
    bne     _fail

_pass:
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
