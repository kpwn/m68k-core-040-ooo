| addx_word_sized.s — ADDX.W: sized arithmetic, upper-word preservation,
| and the ADDX/SUBX sticky Z-flag chaining semantics.  Word-sized
| companion to addx_byte_sized.s — see that file for full bug context.
|
| Golden values cross-checked against Musashi (tb/models/musashi_run
| --stop-pc <right after the ADDX>, before any flag-clobbering CMP).
|
| ── Part 1: normal op + upper-word preservation ──
|   D0 = 0xAAAA7FFF (src word = 0x7FFF), D1 = 0xBBBB0001 (dst word = 0x0001), X=0
|   ADDX.W D0,D1: 0x7FFF + 0x0001 + 0 = 0x8000 (word)
|     -> D1 = 0xBBBB8000 (upper 16 bits of D1 UNCHANGED)
|     -> N=1, V=1 (pos+pos=neg overflow), C=0, X=0, Z=0
|
| ── Part 2: carry-out sets C/X, upper bits still preserved ──
|   D0 = 0x1111FFFF (src word = 0xFFFF), D1 = 0x22220001 (dst word=0x0001), X=0
|   ADDX.W D0,D1: 0xFFFF + 0x0001 + 0 = 0x10000 -> word result 0x0000, carry out
|     -> D1 = 0x22220000 (upper preserved), N=0, C=1, X=1
|
| ── Part 3: Z-flag sticky chaining ──
|   Same two-step idiom as the byte test: a zero-result ADDX.W must
|   preserve incoming Z=1; a following nonzero-result ADDX.W must clear it.

    .text
    .org 0

_start:
    | ── Part 1 ──
    move.l  #0xAAAA7FFF, %d0
    move.l  #0xBBBB0001, %d1
    add.l   #0, %d2                 | X=0
    addx.w  %d0, %d1
    bpl     _fail1                  | expect N=1
    bvc     _fail1                  | expect V=1
    bcs     _fail1                  | expect C=0
    beq     _fail1                  | expect Z=0
    cmp.l   #0xBBBB8000, %d1        | checks word result AND upper-word preservation
    bne     _fail1

    | ── Part 2 ──
    move.l  #0x1111FFFF, %d0
    move.l  #0x22220001, %d1
    add.l   #0, %d2                 | X=0
    addx.w  %d0, %d1
    bmi     _fail2                  | expect N=0
    bcc     _fail2                  | expect C=1 (and X=1)
    cmp.l   #0x22220000, %d1
    bne     _fail2

    | ── Part 3: Z-chain ──
    move.l  #0x00000005, %d6
    sub.l   %d6, %d6                | D6-D6=0 -> Z=1, X=0
    moveq   #0, %d3
    moveq   #0, %d4
    addx.w  %d3, %d4                | 0+0+0=0 (zero) -> Z stays 1 (preserved)
    bne     _fail3
    move.l  #0x00000001, %d3
    addx.w  %d3, %d4                | 0+1+X(0)=1 (nonzero) -> Z must clear
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
