| rox_long_x_cascade.s — ROXL.L / ROXR.L reading and writing X.
|
| Stage D-3 corner: ROXL/ROXR read X as the incoming rotate-in bit and
| update X with the rotated-out bit.  The V2 decoder must flag ROXL/ROXR
| with flags_rd[4]=X so rename wakes the CCR read.
|
| Plan:
|  1. Establish known X=1 via ADD.L #0xFFFFFFFF, Dn (sets X=1 from add carry).
|     Actually simpler: use an initial shift to set X.
|     LSL.L #1, 0x80000000 → 0, C=X=1, Z=1.
|  2. With X=1 going in, ROXR.L #1, 0x00000002:
|      - orig 0x00000002, bit 0 out → C/X=0
|      - X (=1) rotates into MSB → new value = 0x80000001
|     Result: D0 = 0x80000001, C=X=0, N=1.
|  3. With X=0, ROXL.L #1, D0 = 0x80000001 → 0x00000002, X=C=1 (original MSB).
|  4. With X=1, ROXL.L #1, D0 = 0x00000002 → 0x00000005, X=C=0.

    .text
    .org 0

_start:
    | ── 1. Establish X=1 via LSL.L #1 of 0x80000000 ──
    move.l  #0x80000000, %d5
    lsl.l   #1, %d5                  | D5 = 0, X=C=1, Z=1
    bcc     _fail                    | Sanity: C=1

    | ── 2. ROXR.L #1 with X_in=1 on 0x00000002 ──
    move.l  #0x00000002, %d0
    roxr.l  #1, %d0                  | (D0>>1) | (X_in<<31), bit 0 → C/X_out
                                    | = 0x00000001 | 0x80000000 = 0x80000001
                                    | X_out = original bit 0 = 0
    bcs     _fail                    | C=0 (bit 0 out was 0)
    bpl     _fail                    | N=1
    beq     _fail                    | Z=0
    move.l  #0x80000001, %d7
    cmp.l   %d7, %d0
    bne     _fail

    | ── 3. ROXL.L #1 with X_in=0 on D0=0x80000001 ──
    | Current X=0 from step 2 (X_out=0 in ROXR step).
    roxl.l  #1, %d0                  | (D0<<1) | X_in, bit 31 → C/X_out
                                    | = 0x00000002 | 0 = 0x00000002
                                    | X_out = original bit 31 = 1
    bcc     _fail                    | C=1 (MSB out was 1)
    bmi     _fail                    | N=0
    beq     _fail                    | Z=0
    move.l  #0x00000002, %d7
    cmp.l   %d7, %d0
    bne     _fail

    | ── 4. ROXL.L #1 with X_in=1 on D0=0x00000002 ──
    roxl.l  #1, %d0                  | (D0<<1) | X_in = 0x00000004 | 1 = 0x00000005
                                    | X_out = original bit 31 = 0
    bcs     _fail                    | C=0
    bmi     _fail                    | N=0
    beq     _fail                    | Z=0
    move.l  #0x00000005, %d7
    cmp.l   %d7, %d0
    bne     _fail

    | Pass sentinel.
    move.l  #0xC0FFEE00, %d0
    move.l  #0xFFFF0000, %a0
    move.l  %d0, (%a0)
    bra     .

_fail:
    move.l  #0xDEADBEEF, %d0
    move.l  #0xFFFF0000, %a0
    move.l  %d0, (%a0)
    bra     .
