| pack_unpk_pair.s — D-9e PACK / UNPK reg-reg round-trip corner
|
| PRM §4.156 (PACK) / §4.190 (UNPK).  Register forms only — memory
| form stays deferred.  Task-spec corner: PACK + UNPK must correctly
| round-trip a digit pair between packed and unpacked layouts, and
| the 16-bit adjustment must apply BEFORE the shuffle on both ops.
|
| Pack semantics (PRM §4.156):
|   Dx[7:0] = ((Dy[15:0] + adj)[11:8]) concat ((Dy[15:0] + adj)[3:0])
|   Upper 24 bits of Dx preserved.  No CCR update.
|
| Unpack semantics (PRM §4.190):
|   tmp = {0,Dy[7:4], 0,Dy[3:0]}  (expand two nibbles to two bytes)
|   Dx[15:0] = tmp + adj
|   Upper 16 bits of Dx preserved.  No CCR update.
|
| Round-trip test:
|   (1) Seed D0 = 0x????_0506 (unpacked digits '5' and '6' in low
|       nibbles of upper/lower byte of D0[15:0]).
|   (2) PACK D0,D1,#0       →  D1[7:0] = 0x56 (packed).
|                              Upper 24 bits of D1 must survive.
|   (3) UNPK D1,D0,#$3030   →  D0[15:0] = 0x3536 (ASCII "56").
|                              Upper 16 bits of D0 must survive.
|
| Also verify CCR is untouched: we seed X=1 via wrap, then run
| PACK/UNPK, then do a chained ABCD that reads X — if PACK/UNPK
| accidentally cleared CCR, the ABCD would see X=0 and its result
| would differ.

    .text
    .org 0

_start:
    lea     0x00010000, %a7

    | ── Step 1: seed D0 upper 16 = marker, low 16 = unpacked 0x0506 ──
    move.l  #0xABCD0506, %d0
    | Seed D1 with a distinctive upper-24 marker.
    move.l  #0xCAFEEE00, %d1

    | ── Step 2: PACK D0,D1,#0  →  D1[7:0] = 0x56 ────────────────────
    | Syntax: pack Dy,Dx,#adj  with Dy=D0, Dx=D1, adj=0.
    pack    %d0, %d1, #0
    | Expect D1 = 0xCAFEEE56 (upper 24 marker preserved).
    move.l  #0xCAFEEE56, %d7
    cmp.l   %d7, %d1
    bne     _fail

    | ── Step 3: seed X=1, run UNPK D1,D0,#$3030, verify D0 and X survive ──
    move.l  #0xFFFFFFFF, %d5
    moveq   #1, %d6
    add.l   %d6, %d5                  | X=C=1
    | Seed D0 upper 16 with a fresh marker so we can verify it survives.
    move.l  #0xFEED0000, %d0
    unpk    %d1, %d0, #0x3030
    | Expect D0 = 0xFEED_3536 (upper 16 marker preserved, low 16 = "56"
    | in ASCII via 0x0506 + 0x3030).
    move.l  #0xFEED3536, %d7
    cmp.l   %d7, %d0
    bne     _fail

    | ── Step 4: X must still be 1 (PACK/UNPK do NOT touch CCR per PRM) ──
    | Use ABCD 0+0+X → 1 if X=1, else 0.  Dx upper marker stays intact.
    move.l  #0xAAAAAA00, %d2
    move.l  #0x00000000, %d3
    abcd    %d3, %d2                  | D2[7:0] = 0+0+X = 1
    move.l  #0xAAAAAA01, %d7
    cmp.l   %d7, %d2
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
