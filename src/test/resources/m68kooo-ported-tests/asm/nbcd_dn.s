| nbcd_dn.s — D-9e NBCD Dn register-form corner
|
| Task-spec corner: NBCD Dn computes (0 - Dn - X) in BCD on the low
| byte only.  For Dn=0x50 with X=0, the ALU_BCD_NEG path mirrors
| Musashi's nbcd_d exactly: res = (0x9A - 0x50 - 0) & 0xff = 0x4A;
| low-nibble == 0xA → add 0x10 → 0x50.  C=X=1 (non-trivial branch).
|
| Three corners exercise the trivial + non-trivial branches:
|   Dn=0x50, X=0  →  D0[7:0]=0x50, X=C=1 (non-trivial, low-nibble adj)
|   Dn=0x00, X=0  →  D0[7:0]=0x00 unchanged, X=C=0 (trivial branch)
|   Dn=0x00, X=1  →  D0[7:0]=0x99, X=C=1 (non-trivial via X)
|
| Upper 24 bits of Dn MUST survive.

    .text
    .org 0

_start:
    lea     0x00010000, %a7

    | ── Corner 1: NBCD of 0x50 with X=0 → 0x50, X=C=1 (non-trivial) ──
    moveq   #0, %d6
    add.l   %d6, %d6                  | X=0

    move.l  #0x12345650, %d0
    nbcd    %d0                        | D0[7:0] = 0x50, X=C=1

    | Verify C=1 FIRST (CMP would clobber C).
    bcc     _fail                      | expect C=1
    move.l  #0x12345650, %d7
    cmp.l   %d7, %d0
    bne     _fail

    | ── X-chain check: NBCD wrote X=1, a following ABCD with 0+0 must
    | see that X=1 and produce 0x01. ──
    move.l  #0xAAAAAA00, %d1
    moveq   #0, %d2
    abcd    %d2, %d1                   | 0+0+X = 1 if X=1, else 0
    move.l  #0xAAAAAA01, %d7
    cmp.l   %d7, %d1
    bne     _fail

    | ── Corner 2: NBCD of 0x00 with X=0 → 0x00 unchanged, X=C=0 ──
    moveq   #0, %d6
    add.l   %d6, %d6                  | X=0
    move.l  #0x77777700, %d0
    nbcd    %d0                        | trivial branch: res unchanged

    bcs     _fail                      | expect C=0 (trivial branch)
    move.l  #0x77777700, %d7
    cmp.l   %d7, %d0
    bne     _fail

    | ── Corner 3: NBCD of 0x00 with X=1 → 0x99, X=C=1 ──
    move.l  #0xFFFFFFFF, %d5
    moveq   #1, %d6
    add.l   %d6, %d5                  | D5 wraps → X=C=1

    move.l  #0x55555500, %d0
    nbcd    %d0                        | 0 - 0 - 1 = 0x99, X=C=1

    bcc     _fail                      | expect C=1
    move.l  #0x55555599, %d7
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
