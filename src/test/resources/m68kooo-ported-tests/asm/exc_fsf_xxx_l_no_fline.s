| exc_fsf_xxx_l_no_fline.s — FSF (xxx).L must NOT trap F-line.
|
| The Q700 FPSP at ROM 0x4088D244 uses opword 0xF27F + ext1 0x0000 +
| abs.L as a 68040-FPU-presence detection idiom.  Real 68040+FPU
| executes it silently (writes 0 to the byte at the abs.L address);
| FPU-less variants (68LC040, EC040) trap F-line and the FPSP runs
| software emulation.
|
| Bug B (docs/bug_b_atrap_divergence.md): our prior decoder didn't
| recognise 0xF27F so we trapped F-line, FPSP recursed at 0x4088DB1E,
| eventually bus-error storm.  Even before the upstream RTS bug is
| pinned, decoding FSF stops the cascade.
|
| Test:
|   1. Pre-write 0xFF to mem[0x00005380].
|   2. Execute FSF 0x00005380:l.
|   3. Verify mem[0x00005380] == 0x00 (FSF stored false).
|   4. Verify no exception fired.
|
| PASS sentinel: 0xC0FFEE00.
| FAIL sentinels:
|   0xDEADF501 — F-line vec 11 fired (decoder didn't catch FSF)
|   0xDEADF502 — mem[0x5380] != 0x00 (FSF didn't store the byte)

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000
    .equ TARGET,    0x00005380

_start:
    lea     0x00010000, %a7
    move.l  #_fline_handler, 0x0000002C   | vec 11

    | Pre-poison the target byte with 0xFF.
    move.b  #0xFF, TARGET.l

    | FSF 0x00005380:l — opword 0xF27F, ext 0x0000, abs.L = 0x00005380.
    .short  0xF27F, 0x0000
    .long   TARGET

    | Verify the byte is now 0x00.
    move.b  TARGET.l, %d0
    andi.l  #0xFF, %d0
    cmp.l   #0, %d0
    bne     _fail_byte

    | PASS.
    lea     PASS_SENT, %a1
    move.l  #0xC0FFEE00, %d2
    move.l  %d2, (%a1)
_halt:
    bra     _halt

_fail_byte:
    lea     PASS_SENT, %a1
    move.l  #0xDEADF502, %d2
    move.l  %d2, (%a1)
_halt_b:
    bra     _halt_b

_fline_handler:
    lea     PASS_SENT, %a1
    move.l  #0xDEADF501, %d2
    move.l  %d2, (%a1)
_halt_h:
    bra     _halt_h
