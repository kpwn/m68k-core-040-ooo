| nbcd_basic.s — NBCD Dn (register form) basic sanity
|
| NBCD computes 0 - Dn - X in BCD.  Per PRM and Musashi:
|   - If Dn[7:0]==0 AND X==0: result unchanged, X=C=V=0, Z preserved.
|   - Else: result = BCD(0 - Dn - X), X=C=1, Z sticky-cleared on nonzero.
| Upper 24 bits of Dn are preserved.
|
| This test exercises the register form only; memory-EA NBCD is not yet
| decoded (task decode-iv-c explicitly defers it).

    .text
    .org 0

_start:
    | ── Clear X ──
    moveq   #0, %d0
    add.l   %d0, %d0                  | X=0

    | ── NBCD 0x25 → 0x75 (0 - 25 = -25 → BCD 75, borrow X=1) ──
    move.l  #0xAABBCC25, %d0
    nbcd    %d0                       | D0[7:0] = 0x75, X=1
    bcc     _fail                     | expect C=1 (verify before CMP clobbers)
    move.l  #0xAABBCC75, %d7
    cmp.l   %d7, %d0
    bne     _fail

    | ── NBCD 0x00 with X=0 → 0x00 unchanged, X=0 ──
    moveq   #0, %d2
    add.l   %d2, %d2                  | X=0
    move.l  #0x11223300, %d0
    nbcd    %d0                       | trivial case
    bcs     _fail                     | expect C=0 (verify before CMP)
    move.l  #0x11223300, %d7
    cmp.l   %d7, %d0
    bne     _fail

    | ── NBCD 0x00 with X=1 → 0x99, X=1 (non-trivial via X) ──
    | Set X=1
    move.l  #0xFFFFFFFF, %d5
    move.l  #0x00000001, %d6
    add.l   %d6, %d5                  | X=1
    move.l  #0x22334400, %d0
    nbcd    %d0                       | 0 - 0 - 1 → 0x99, X=1
    bcc     _fail                     | expect C=1 (verify before CMP)
    move.l  #0x22334499, %d7
    cmp.l   %d7, %d0
    bne     _fail

    | ── NBCD 0x99 with X=0 → 0x01, X=1 ──
    moveq   #0, %d2
    add.l   %d2, %d2                  | X=0
    move.l  #0x33445599, %d0
    nbcd    %d0                       | 0 - 0x99 = -0x99 → 0x01, X=1
    bcc     _fail                     | expect C=1 (verify before CMP)
    move.l  #0x33445501, %d7
    cmp.l   %d7, %d0
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
