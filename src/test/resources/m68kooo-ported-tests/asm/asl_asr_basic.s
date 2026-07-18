| asl_asr_basic.s — ASL.L / ASR.L basic correctness + CCR
|
| V-flag semantics: Musashi is the golden model (user ruling on task #9).
| ALU computes V = orig_msb XOR new_msb, which matches Musashi.
| PRM wording "V=1 if sign bit changes at any time during shift" is
| the same thing for 1-bit ASL (old MSB vs new MSB); 0xC0000000 <<1 =
| 0x80000000 has MSB 1 → 1, so V=0.
|
| Expected:
|  1. D0=0x00000001 ASL.L #1, D0  → D0=0x00000002, X=C=0, N=0, Z=0, V=0
|  2. D1=0xC0000000 ASL.L #1, D1  → D1=0x80000000, X=C=1, N=1, Z=0, V=0
|  3. D3=0x80000001 ASR.L #1, D3  → D3=0xC0000000, X=C=1, N=1, Z=0, V=0
|  4. D4=0x00000008 ASR.L #4, D4  → D4=0x00000000, X=C=1, Z=1, N=0, V=0
|  5. ASL.L D5,D6 where D5=#2, D6=0x00000001 → D6=0x00000004
|
| Pattern: check CCR flags IMMEDIATELY after the tested instruction,
| BEFORE any MOVE/CMP/TST (each of which overwrites CCR).

    .text
    .org 0

_start:
    | ── 1. ASL.L #1 on positive small value: N=Z=V=C=0 ──
    move.l  #0x00000001, %d0
    asl.l   #1, %d0                  | D0=2, CCR: N=0, Z=0, V=0, C=0
    bcs     _fail                    | C=0
    bmi     _fail                    | N=0
    beq     _fail                    | Z=0
    bvs     _fail                    | V=0
    move.l  #0x00000002, %d7
    cmp.l   %d7, %d0
    bne     _fail

    | ── 2. ASL.L #1 of 0xC0000000 → 0x80000000, V=0 (Musashi), N=1, C=X=1 ──
    move.l  #0xC0000000, %d1
    asl.l   #1, %d1                  | D1=0x80000000, V=0, N=1, C=1
    bvs     _fail                    | V=0 (MSB 1→1, no change)
    bpl     _fail                    | N=1
    bcc     _fail                    | C=1
    move.l  #0x80000000, %d7
    cmp.l   %d7, %d1
    bne     _fail

    | ── 3. ASR.L #1 preserves sign, C=<LSB> ──
    move.l  #0x80000001, %d3
    asr.l   #1, %d3                  | D3=0xC0000000, C=1, N=1, V=0
    bcc     _fail                    | C=1 (LSB was 1)
    bpl     _fail                    | N=1
    bvs     _fail                    | V=0 (ASR never sets V)
    move.l  #0xC0000000, %d7
    cmp.l   %d7, %d3
    bne     _fail

    | ── 4. ASR.L #4 to zero sets Z=1 ──
    move.l  #0x00000008, %d4
    asr.l   #4, %d4                  | D4=0, Z=1
    bne     _fail                    | Z=1

    | ── 5. ASL.L Dn,Dm (register count form) ──
    moveq   #2, %d5
    move.l  #0x00000001, %d6
    asl.l   %d5, %d6                 | D6 = 0x00000004
    move.l  #0x00000004, %d7
    cmp.l   %d7, %d6
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
