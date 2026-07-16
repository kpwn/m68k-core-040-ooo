| lsl_lsr_basic.s — LSL.L / LSR.L basic correctness + CCR
|
| Hypothesis: LSL / LSR are pure 0-fill shifts.  V is always 0; X=C =
| last bit shifted out; N = result[31]; Z = (result==0).
|
| Expected (PRM):
|  1. LSL.L #1, D0 where D0=0x80000000 → D0=0, C=X=1, Z=1, N=0, V=0
|  2. LSL.L #1, D1 where D1=0x00000001 → D1=2, C=X=0, Z=0
|  3. LSR.L #1, D2 where D2=0x80000000 → D2=0x40000000, C=X=0 (N=0, not sign-ext)
|  4. LSR.L #1, D3 where D3=0x00000001 → D3=0, C=X=1, Z=1
|  5. LSL.L D4, D5 where D4=#4, D5=0x11 → D5=0x110
|  6. LSR.L D4, D5 reverses it → D5=0x11
|
| Check flags immediately after each shift, value separately.

    .text
    .org 0

_start:
    | ── 1. LSL drops a 1 out of bit31 → C=1, Z=1 ──
    move.l  #0x80000000, %d0
    lsl.l   #1, %d0                  | D0=0, C=1, Z=1
    bcc     _fail                    | C=1
    bne     _fail                    | Z=1
    | no value check needed — TST below would be redundant

    | ── 2. LSL small value, no C, no Z ──
    move.l  #0x00000001, %d1
    lsl.l   #1, %d1                  | D1=2, C=0, Z=0
    bcs     _fail                    | C=0
    beq     _fail                    | Z=0
    move.l  #0x00000002, %d7
    cmp.l   %d7, %d1
    bne     _fail

    | ── 3. LSR 0x80000000 does NOT sign-extend → result 0x40000000, N=0 ──
    move.l  #0x80000000, %d2
    lsr.l   #1, %d2                  | D2=0x40000000, N=0, C=0
    bmi     _fail                    | N=0 (crucial: not sign-extended)
    bcs     _fail                    | C=0 (bit 0 was 0)
    move.l  #0x40000000, %d7
    cmp.l   %d7, %d2
    bne     _fail

    | ── 4. LSR one bit out the right → C=1, Z=1 ──
    move.l  #0x00000001, %d3
    lsr.l   #1, %d3                  | D3=0, C=1, Z=1
    bcc     _fail                    | C=1
    bne     _fail                    | Z=1

    | ── 5. LSL Dn,Dm then 6. LSR reverses it ──
    moveq   #4, %d4
    move.l  #0x00000011, %d5
    lsl.l   %d4, %d5                 | D5 = 0x00000110
    move.l  #0x00000110, %d7
    cmp.l   %d7, %d5
    bne     _fail
    lsr.l   %d4, %d5                 | D5 = 0x00000011 again
    move.l  #0x00000011, %d7
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
