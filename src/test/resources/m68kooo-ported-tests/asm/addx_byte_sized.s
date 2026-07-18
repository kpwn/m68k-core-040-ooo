| addx_byte_sized.s — ADDX.B: sized arithmetic, upper-byte preservation,
| and the ADDX/SUBX-specific sticky Z-flag chaining semantics.
|
| Bug context: ADDX.B/.W previously computed the full 32-bit (long)
| form regardless of uop_size — clobbering the upper 24/16 bits of the
| destination register and deriving N/Z/V/C from bit 31 instead of
| bit 7/15.  This test is the byte-sized companion to the existing
| addx_chain.s / addx_two_regs_x_flag.s (which only cover .l).
|
| Golden values below were cross-checked against Musashi
| (tb/models/musashi_run --stop-pc <right after the ADDX>), not
| hand-derived from the PRM alone.  IMPORTANT: flag checks must run
| BEFORE any CMP/TST of the result — those also write NZVC and would
| silently clobber the very flags being verified.
|
| ── Part 1: normal op + upper-byte preservation ──
|   D0 = 0xAAAAAA7F (src byte = 0x7F), D1 = 0xBBBBBB01 (dst byte = 0x01), X=0
|   ADDX.B D0,D1: 0x7F + 0x01 + 0 = 0x80 (byte)
|     -> D1 = 0xBBBBBB80 (upper 24 bits of D1 UNCHANGED)
|     -> N=1, V=1 (pos+pos=neg overflow), C=0, X=0, Z=0
|
| ── Part 2: carry-out sets C/X, upper bits still preserved ──
|   D0 = 0x111111FF (src byte = 0xFF), D1 = 0x22222201 (dst byte=0x01), X=0
|   ADDX.B D0,D1: 0xFF + 0x01 + 0 = 0x100 -> byte result 0x00, carry out
|     -> D1 = 0x22222200 (upper preserved), N=0, C=1, X=1
|
| ── Part 3: Z-flag sticky chaining (ADDX/SUBX idiom) ──
|   Z is cleared if the sized result is non-zero; otherwise the
|   INCOMING Z is preserved verbatim (never freshly SET on a zero
|   result).  Two-step chain:
|     (a) force Z=1 and X=0, then ADDX.B of 0+0+0 = 0 (zero result)
|         -> Z must STAY 1 (preserved, not freshly derived)
|     (b) immediately follow with ADDX.B of 0+1+X(=0) = 1 (nonzero)
|         -> Z must CLEAR to 0

    .text
    .org 0

_start:
    | ── Part 1 ──
    move.l  #0xAAAAAA7F, %d0
    move.l  #0xBBBBBB01, %d1
    add.l   #0, %d2                 | X=0 (add of 0 never carries)
    addx.b  %d0, %d1
    bpl     _fail1                  | expect N=1
    bvc     _fail1                  | expect V=1
    bcs     _fail1                  | expect C=0
    beq     _fail1                  | expect Z=0
    cmp.l   #0xBBBBBB80, %d1        | checks byte result AND upper-byte preservation
    bne     _fail1

    | ── Part 2 ──
    move.l  #0x111111FF, %d0
    move.l  #0x22222201, %d1
    add.l   #0, %d2                 | X=0
    addx.b  %d0, %d1
    bmi     _fail2                  | expect N=0
    bcc     _fail2                  | expect C=1 (and X=1)
    cmp.l   #0x22222200, %d1
    bne     _fail2

    | ── Part 3: Z-chain ──
    move.l  #0x00000005, %d6        | nonzero, then...
    sub.l   %d6, %d6                | D6-D6=0 -> Z=1, X=0
    moveq   #0, %d3
    moveq   #0, %d4
    addx.b  %d3, %d4                | 0+0+0=0 (zero) -> Z stays 1 (preserved)
    bne     _fail3
    move.l  #0x00000001, %d3
    addx.b  %d3, %d4                | 0+1+X(0)=1 (nonzero) -> Z must clear
    beq     _fail3

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a0)
_halt:
    bra     _halt

_fail1:
    move.l  #0xDEAD0001, %d7
    bra     _fail
_fail2:
    move.l  #0xDEAD0002, %d7
    bra     _fail
_fail3:
    move.l  #0xDEAD0003, %d7

_fail:
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
_halt_fail:
    bra     _halt_fail
