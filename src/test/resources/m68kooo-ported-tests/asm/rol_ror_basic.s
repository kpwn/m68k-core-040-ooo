| rol_ror_basic.s — ROL.L / ROR.L basic correctness + CCR
|
| Hypothesis: ROL/ROR rotate without involving X.  PRM: X is unaffected.
| C = last bit rotated into C (= the bit that wrapped around).
| V = 0, N = result[31], Z = (result==0).
|
| Expected:
|  1. ROL.L #1, D0 where D0=0x80000001 → D0=0x00000003, C=1 (MSB wrapped)
|  2. ROL.L #4, D1 where D1=0x12345678 → D1=0x23456781
|  3. ROR.L #1, D2 where D2=0x00000001 → D2=0x80000000, C=1, N=1
|  4. ROR.L #4, D3 where D3=0x12345678 → D3=0x81234567
|  5. ROL.L #1, D4 where D4=0x00000000 → D4=0, C=0, Z=1
|
| Flag checks happen immediately after each rotate.

    .text
    .org 0

_start:
    | ── 1. ROL.L #1 of 0x80000001 → 0x00000003 with C=1 ──
    move.l  #0x80000001, %d0
    rol.l   #1, %d0                  | D0=0x00000003, C=1 (MSB wrapped)
    bcc     _fail                    | C=1
    move.l  #0x00000003, %d7
    cmp.l   %d7, %d0
    bne     _fail

    | ── 2. ROL.L #4 value check only (no specific flag assertion) ──
    move.l  #0x12345678, %d1
    rol.l   #4, %d1
    move.l  #0x23456781, %d7
    cmp.l   %d7, %d1
    bne     _fail

    | ── 3. ROR.L #1 of 0x00000001 → 0x80000000 with C=1, N=1 ──
    move.l  #0x00000001, %d2
    ror.l   #1, %d2                  | D2=0x80000000, C=1, N=1
    bcc     _fail                    | C=1
    bpl     _fail                    | N=1
    move.l  #0x80000000, %d7
    cmp.l   %d7, %d2
    bne     _fail

    | ── 4. ROR.L #4 value check ──
    move.l  #0x12345678, %d3
    ror.l   #4, %d3
    move.l  #0x81234567, %d7
    cmp.l   %d7, %d3
    bne     _fail

    | ── 5. ROL.L #1 of zero stays zero, C=0, Z=1 ──
    move.l  #0x00000000, %d4
    rol.l   #1, %d4                  | D4=0, C=0, Z=1
    bne     _fail                    | Z=1
    bcs     _fail                    | C=0

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
