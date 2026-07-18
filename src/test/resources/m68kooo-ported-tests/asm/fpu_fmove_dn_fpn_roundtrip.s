| fpu_fmove_dn_fpn_roundtrip.s — FMOVE Dn -> FPn round-trip via single-prec.
|
| Goal: load a 32-bit IEEE-754 single-precision pattern from Dn into FPn
| (FMOVE.S Dn,FPn), then write it back to memory via FMOVE.S FPn,(An) and
| verify the bytes match the original pattern.  This proves the integer-
| to-FPU bridge does NOT corrupt the bits during the int->extended
| conversion and the reverse extended->single conversion on the way out.
|
| Pattern: 0x40400000 == +3.0f.  Choosing an exact-representable single
| means the round-trip is bit-identical regardless of FPU internal width
| (extended-80 reload of +3.0 is +3.0 with zero round error).
|
| FMOVE.S Dn,FPn opword:
|   0xF200 (cpid=1, EA mode/reg via lower 6 bits)
|   ext   = 010_sss_ddd_0000000  -- src spec=010 (single), src=Dn, dst=FPn
|   ext = 0x4000 | (Dn<<10) | (FPn<<7)
|     With Dn=0, FPn=0: ext = 0x4000
|
| FMOVE.S FPn,(An) opword:
|   0xF200 + (mode/reg in low 6) -- here (A0) = mode 010, reg 000 -> 0x10
|     => 0xF210
|   ext   = 011_ddd_kkk_0000000  -- src spec=011 (single), src=FPn,
|                                    kkk=0 (k-factor unused)
|     With FPn=0: ext = 0x6000
|
| EXPECTED OUTCOME: This decode path may not yet route to a UOP_FP /
| LSU bridge — only register-register F200 ops are decoded today.  If
| undecoded, the opword traps via vec-11 F-line; a stub vec-11 handler
| writes a known FAIL code so the cause is clear.
|
| PASS sentinel: 0xC0FFEE00 once memory at scratch == 0x40400000.
| FAIL sentinels:
|   0xDEAD0F01 — vec-11 F-line trap fired (FPU EA decode missing)
|   0xDEAD0F02 — round-trip mismatch (bytes != 0x40400000)
|
| OBSERVED on main: assembles clean.  Runtime: F-line trap (DEAD0F01)
| because FMOVE.S Dn,FPn is not yet decoded — recorded as expected.

    .text
    .org 0

    .equ PASS_SENT,    0xFFFF0000
    .equ SCRATCH,      0x00020000
    .equ PATTERN,      0x40400000      | +3.0f single-precision

_start:
    lea     0x00010000, %a7
    move.l  #_fline, 0x0000002C        | vec 11 (F-line) @ 0x2C

    | Load pattern into D0 then FMOVE.S D0,FP0.
    move.l  #PATTERN, %d0
    .short  0xF200, 0x4000             | FMOVE.S D0,FP0

    | FMOVE.S FP0,(A0) — write FP0 to memory at SCRATCH.
    lea     SCRATCH, %a0
    .short  0xF210, 0x6000             | FMOVE.S FP0,(A0)

    | Load it back and compare bit-for-bit.
    move.l  (%a0), %d1
    cmp.l   %d0, %d1
    bne     _fail_match

    | PASS
    lea     PASS_SENT, %a1
    move.l  #0xC0FFEE00, %d2
    move.l  %d2, (%a1)
_halt:
    bra     _halt

_fail_match:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0F02, %d2
    move.l  %d2, (%a1)
_halt_match:
    bra     _halt_match

_fline:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0F01, %d2
    move.l  %d2, (%a1)
_halt_fline:
    bra     _halt_fline
