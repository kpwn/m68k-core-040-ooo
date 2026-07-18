| abcd_reg_x_flag.s — D-9e ABCD reg-reg corner: nibble carry with X=0
|
| Task-spec corner (the thing the D-9e agent almost got wrong while
| implementing the family row): the sem flags_rd mask for ABCD must be
| 5'b10000 (reads X), and the low-nibble carry from an ABCD result like
| 9+1 must produce a 0x10 tens-carry, not a raw binary 0x0A.
|
|   Dx = 0x09, Dy = 0x01, X = 0  →  ABCD Dy,Dx  →  Dx = 0x10, X=C=0.
|
| Also verify:
|   * Upper 24 bits of Dx are preserved across the 8-bit BCD op.
|   * X-flag propagation: an immediately-following ABCD with X=1 reads
|     the X produced here.  Since our first result did NOT wrap past 99,
|     X=0 out — a second ABCD of 0+0 must leave its Dx unchanged (modulo
|     the low byte becoming 0x00 with Z=1).

    .text
    .org 0

_start:
    | Seed A7 to safe scratch RAM.
    lea     0x00010000, %a7

    | ── Clear X via an ADD.L that does not wrap ──
    moveq   #0, %d6
    add.l   %d6, %d6                  | X=0

    | ── Core corner: 0x09 + 0x01 + X=0 → 0x10 (nibble carry) ──
    | Dx = D1 (dst), Dy = D0 (src).  Upper 24 bits of D1 are a marker
    | (0xCAFEFE__) that MUST survive — a size-mux bug on ALU_BCD_ADD
    | would clobber them.
    move.l  #0xCAFEFE09, %d1          | Dx dest + marker
    move.l  #0x00000001, %d0          | Dy src (low byte = 0x01)
    abcd    %d0, %d1                  | D1[7:0] = 9 + 1 + 0 = 0x10

    | Expect D1 = 0xCAFEFE10.  Marker survival check.
    move.l  #0xCAFEFE10, %d7
    cmp.l   %d7, %d1
    bne     _fail

    | Expect X=0, C=0 after this (9+1=10 doesn't wrap past 99).
    | Use BCS to fail if C is set.
    bcs     _fail

    | ── X-chain check ─────────────────────────────────────────────────
    | Set X=1 via wrap.
    move.l  #0xFFFFFFFF, %d5
    moveq   #1, %d2
    add.l   %d2, %d5                  | D5 wraps → X=C=1

    | Now do ABCD 0x20 + 0x35 + X=1 → 0x56.  Dx marker 0x12345678.
    move.l  #0x12345620, %d3
    move.l  #0x00000035, %d4
    abcd    %d4, %d3                  | D3[7:0] = 0x20 + 0x35 + 1 = 0x56

    move.l  #0x12345656, %d7
    cmp.l   %d7, %d3
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
