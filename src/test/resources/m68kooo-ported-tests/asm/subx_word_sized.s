| subx_word_sized.s — SUBX.W: sized arithmetic, upper-word preservation,
| and the ADDX/SUBX sticky Z-flag chaining semantics.  Word-sized
| companion to subx_chain.s.  See addx_byte_sized.s for full bug context.
|
| Golden values cross-checked against Musashi (tb/models/musashi_run
| --stop-pc <right after the SUBX>, before any flag-clobbering CMP).
|
| SUBX Dy,Dx computes Dx = Dx - Dy - X (dst - src - X).
|
| ── Part 1: normal op (no borrow) + upper-word preservation, V-flag ──
|   D0 = src = 0xAAAA0001 (word 0x0001), D1 = dst = 0xBBBB8000 (word 0x8000), X=0
|   SUBX.W D0,D1: 0x8000 - 0x0001 - 0 = 0x7FFF (no borrow)
|     -> D1 = 0xBBBB7FFF (upper 16 bits UNCHANGED)
|     -> N=0, V=1 (neg-minus-pos wraps positive: overflow), C=0, X=0, Z=0
|
| ── Part 2: borrow sets C/X, upper bits still preserved ──
|   D0 = src = 0x11110001 (word 0x0001), D1 = dst = 0x22220000 (word 0x0000), X=0
|   SUBX.W D0,D1: 0x0000 - 0x0001 - 0 = 0xFFFF (borrow)
|     -> D1 = 0x2222FFFF (upper preserved), N=1, V=0, C=1, X=1
|
| ── Part 3: Z-flag sticky chaining ──
|   (a) force Z=1, X=0, then SUBX.W of 0-0-0 = 0 (zero) -> Z stays 1
|   (b) SUBX.W of 0-1-X(0) = -1 (nonzero) -> Z must clear

    .text
    .org 0

_start:
    | ── Part 1 ──
    move.l  #0xAAAA0001, %d0
    move.l  #0xBBBB8000, %d1
    add.l   #0, %d2                 | X=0
    subx.w  %d0, %d1
    bmi     _fail1                  | expect N=0
    bvc     _fail1                  | expect V=1
    bcs     _fail1                  | expect C=0
    beq     _fail1                  | expect Z=0
    cmp.l   #0xBBBB7FFF, %d1        | checks word result AND upper-word preservation
    bne     _fail1

    | ── Part 2 ──
    move.l  #0x11110001, %d0
    move.l  #0x22220000, %d1
    add.l   #0, %d2                 | X=0
    subx.w  %d0, %d1
    bpl     _fail2                  | expect N=1
    bvs     _fail2                  | expect V=0
    bcc     _fail2                  | expect C=1 (and X=1)
    cmp.l   #0x2222FFFF, %d1
    bne     _fail2

    | ── Part 3: Z-chain ──
    move.l  #0x00000005, %d6
    sub.l   %d6, %d6                | D6-D6=0 -> Z=1, X=0
    moveq   #0, %d3
    moveq   #0, %d4
    subx.w  %d3, %d4                | 0-0-0=0 (zero) -> Z stays 1 (preserved)
    bne     _fail3
    move.l  #0x00000001, %d3
    subx.w  %d3, %d4                | 0-1-X(0)=-1 (nonzero) -> Z must clear
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
