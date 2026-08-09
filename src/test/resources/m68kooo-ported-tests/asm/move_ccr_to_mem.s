| move_ccr_to_mem.s — MOVE.W CCR,<memory ea>
|
| PRM §4.135: MOVE from CCR writes a WORD whose upper byte is zero.
| This test covers the full destination-EA set the decoder implements,
| which is an exact match for the MOVE.W SR,<ea> sibling row:
|     (An)        mode 010
|     (An)+       mode 011
|     -(An)       mode 100
|     (d16,An)    mode 101
|     (xxx).W     mode 111 reg 000
|     (xxx).L     mode 111 reg 001
|
| Each case also checks the ADJACENT bytes, so a store of the wrong SIZE
| (long instead of word) is caught rather than silently passing: the
| scratch area is pre-filled with 0xAA/0xBB and the untouched half must
| survive.
|
| All expected values cross-checked against Musashi v4.60 (68040 mode).
|
| ORDERING: `move.w #imm,%ccr` sits immediately before each store,
| because the intervening pre-fill stores would otherwise clobber CCR.

    .text
    .org 0

_start:
    lea     0x00010000, %a7
    lea     0x00106000, %a2           | scratch base

    | ── (An) ───────────────────────────────────────────────────────
    move.l  #0xAAAAAAAA, (%a2)        | 106000..106003 = AA AA AA AA
    lea     0x00106000, %a3
    move.w  #0x0011, %ccr
    move.w  %ccr, (%a3)
    | word at 106000 must be 0x0011; 106002..3 must still be 0xAA.
    move.w  (%a3), %d0
    and.l   #0xFFFF, %d0
    cmp.l   #0x0011, %d0
    bne     _fail
    move.w  2(%a3), %d1
    and.l   #0xFFFF, %d1
    cmp.l   #0xAAAA, %d1
    bne     _fail

    | ── (An)+ ──────────────────────────────────────────────────────
    move.l  #0xAAAAAAAA, 16(%a2)      | 106010..106013
    lea     0x00106010, %a3
    move.w  #0x001F, %ccr
    move.w  %ccr, (%a3)+
    move.l  %a3, %d2                  | A3 must have advanced by 2
    cmp.l   #0x00106012, %d2
    bne     _fail
    move.w  -2(%a3), %d0
    and.l   #0xFFFF, %d0
    cmp.l   #0x001F, %d0
    bne     _fail
    move.w  (%a3), %d1
    and.l   #0xFFFF, %d1
    cmp.l   #0xAAAA, %d1
    bne     _fail

    | ── -(An) ──────────────────────────────────────────────────────
    | Word destination, so the stride is 2 regardless of which An —
    | the A7 byte-alignment rule does not apply to a word access.
    move.l  #0xBBBBBBBB, 32(%a2)      | 106020..106023
    lea     0x00106022, %a3
    move.w  #0x000A, %ccr
    move.w  %ccr, -(%a3)
    move.l  %a3, %d2
    cmp.l   #0x00106020, %d2
    bne     _fail
    move.w  (%a3), %d0
    and.l   #0xFFFF, %d0
    cmp.l   #0x000A, %d0
    bne     _fail
    move.w  2(%a3), %d1
    and.l   #0xFFFF, %d1
    cmp.l   #0xBBBB, %d1
    bne     _fail

    | ── (d16,An) ───────────────────────────────────────────────────
    move.l  #0xBBBBBBBB, 48(%a2)      | 106030..106033
    lea     0x00106030, %a3
    move.w  #0x0004, %ccr
    move.w  %ccr, 2(%a3)
    move.w  (%a3), %d0
    and.l   #0xFFFF, %d0
    cmp.l   #0xBBBB, %d0
    bne     _fail
    move.w  2(%a3), %d1
    and.l   #0xFFFF, %d1
    cmp.l   #0x0004, %d1
    bne     _fail

    | ── (xxx).W absolute short ─────────────────────────────────────
    | 0x00007000 is representable as a sign-extended 16-bit address.
    move.l  #0xAAAAAAAA, 0x00007000
    move.w  #0x0008, %ccr
    move.w  %ccr, 0x00007000
    move.w  0x00007000, %d0
    and.l   #0xFFFF, %d0
    cmp.l   #0x0008, %d0
    bne     _fail
    move.w  0x00007002, %d1
    and.l   #0xFFFF, %d1
    cmp.l   #0xAAAA, %d1
    bne     _fail

    | ── (xxx).L absolute long ──────────────────────────────────────
    move.l  #0xBBBBBBBB, 0x00106040
    move.w  #0x0002, %ccr
    move.w  %ccr, 0x00106040
    move.w  0x00106040, %d0
    and.l   #0xFFFF, %d0
    cmp.l   #0x0002, %d0
    bne     _fail
    move.w  0x00106042, %d1
    and.l   #0xFFFF, %d1
    cmp.l   #0xBBBB, %d1
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
