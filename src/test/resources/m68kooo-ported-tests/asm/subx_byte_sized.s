| subx_byte_sized.s — SUBX.B: sized arithmetic, upper-byte preservation,
| and the ADDX/SUBX sticky Z-flag chaining semantics.  Byte-sized
| companion to subx_chain.s (which only covers .l).  See
| addx_byte_sized.s for full bug context (ADDX/SUBX .b/.w previously
| computed the 32-bit form regardless of size).
|
| Golden values cross-checked against Musashi (tb/models/musashi_run
| --stop-pc <right after the SUBX>, before any flag-clobbering CMP).
| Flag checks always run BEFORE the value-verifying CMP in each part.
|
| SUBX Dy,Dx computes Dx = Dx - Dy - X (dst - src - X).
|
| ── Part 1: normal op (no borrow) + upper-byte preservation, V-flag ──
|   D0 = src = 0xAAAAAA01 (byte 0x01), D1 = dst = 0xBBBBBB80 (byte 0x80), X=0
|   SUBX.B D0,D1: 0x80 - 0x01 - 0 = 0x7F (no borrow)
|     -> D1 = 0xBBBBBB7F (upper 24 bits UNCHANGED)
|     -> N=0, V=1 (neg-minus-pos wraps positive: overflow), C=0, X=0, Z=0
|
| ── Part 2: borrow sets C/X, upper bits still preserved ──
|   D0 = src = 0x11111101 (byte 0x01), D1 = dst = 0x22222200 (byte 0x00), X=0
|   SUBX.B D0,D1: 0x00 - 0x01 - 0 = 0xFF (borrow)
|     -> D1 = 0x222222FF (upper preserved), N=1, V=0, C=1, X=1
|
| ── Part 3: Z-flag sticky chaining ──
|   (a) force Z=1, X=0, then SUBX.B of 0-0-0 = 0 (zero) -> Z stays 1
|   (b) SUBX.B of 0-1-X(0) = -1 (nonzero) -> Z must clear

    .text
    .org 0

_start:
    | ── Part 1 ──
    move.l  #0xAAAAAA01, %d0
    move.l  #0xBBBBBB80, %d1
    add.l   #0, %d2                 | X=0
    subx.b  %d0, %d1
    bmi     _fail1                  | expect N=0
    bvc     _fail1                  | expect V=1
    bcs     _fail1                  | expect C=0
    beq     _fail1                  | expect Z=0
    cmp.l   #0xBBBBBB7F, %d1        | checks byte result AND upper-byte preservation
    bne     _fail1

    | ── Part 2 ──
    move.l  #0x11111101, %d0
    move.l  #0x22222200, %d1
    add.l   #0, %d2                 | X=0
    subx.b  %d0, %d1
    bpl     _fail2                  | expect N=1
    bvs     _fail2                  | expect V=0
    bcc     _fail2                  | expect C=1 (and X=1)
    cmp.l   #0x222222FF, %d1
    bne     _fail2

    | ── Part 3: Z-chain ──
    move.l  #0x00000005, %d6
    sub.l   %d6, %d6                | D6-D6=0 -> Z=1, X=0
    moveq   #0, %d3
    moveq   #0, %d4
    subx.b  %d3, %d4                | 0-0-0=0 (zero) -> Z stays 1 (preserved)
    bne     _fail3
    move.l  #0x00000001, %d3
    subx.b  %d3, %d4                | 0-1-X(0)=-1 (nonzero) -> Z must clear
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
