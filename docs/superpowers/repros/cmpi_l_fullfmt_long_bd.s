| cmpi_l_fullfmt_long_bd.s — the SoC boot blocker's exact encoding (Part 121), 2026-09-03
|
| The macqd700-soc boot parks on a spurious vec=0x04 Illegal Instruction at 0x000098E2:
|     cmpi.l #$316D6567,%a0@(0xFEFFC)
|     opword 0x0CB0, full-format extension word 0x8170, 12 bytes total
| 0x8170 = bit8 set (full format), BS=0 (base reg used), IS=1 (index suppressed),
| BD SIZE=11 (LONG base displacement), I/IS=000 (no memory indirection -> MEMSIMPLE).
| MAME retires that encoding 1985 times in 20 seconds. The reported discriminator is that
| `move.l #imm,%a0@(0x17EFFC)` -- same EA shape, same extension word -- retires correctly,
| and only the LINE-0 immediate form traps.
|
| That is the same shape as fuzz cluster E (`imm_src_fullfmt_long_bd_dst.s`): an immediate
| source followed by a full-format EA carrying a LONG base displacement. Cluster E's fix
| repairs the ADDRESS for exactly this encoding via the `immEa` call site.
|
| This test checks the three things that can independently be wrong here, so that a pass
| means something specific:
|   1. LEGALITY  -- the encoding must not raise vector 4 (a vec-4 handler writes a
|                   distinctive sentinel, so an illegal trap is reported as itself rather
|                   than as a timeout).
|   2. FRAMING   -- the instruction must be exactly 12 bytes: a following marker store must
|                   execute, and the CCR from the CMPI must survive it. A mis-framed length
|                   would either execute garbage or skip the marker.
|   3. ADDRESS   -- the compared operand must come from base+bd, not base+(bd & 0xFFFF0000).
|                   Two locations are seeded with different values so the two candidate
|                   addresses give different Z results, and the decoy is checked untouched.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    lea     0x00115f00, %a7

    | Install a vector-4 (Illegal Instruction) handler so a spurious trap is reported
    | as itself. Vector 4 lives at address 0x10.
    lea     0x00000010, %a0
    move.l  #_illegal, (%a0)

    | ── Operand setup ───────────────────────────────────────────────────────
    |   A0 = 0x00100000, bd = 0x00015400 (LONG: needs BD SIZE=11).
    |   correct  EA = 0x00100000 + 0x00015400 = 0x00115400
    |   truncated EA (bd low half-word lost) = 0x00100000 + 0x00010000 = 0x00110000
    lea     0x00110000, %a0
    move.l  #0x0BADBAD0, (%a0)        | decoy: NOT the value we compare equal against
    lea     0x00115400, %a0
    move.l  #0x316D6567, (%a0)        | real target: equals the immediate -> Z must be set

    lea     0x00100000, %a0
    | cmpi.l #0x316D6567,(0x00015400,%a0)   [full format, IS=1, BD SIZE=long, I/IS=000]
    |   0x0C80 = CMPI.L <ea>; | mode=110 reg=000 (A0) -> 0x0CB0
    |   ext 0x8170: D/A=1,REG=000,W/L=0,SCALE=00,bit8=1,BS=0,IS=1,BDSIZE=11,0,I/IS=000
    .word   0x0CB0, 0x316D, 0x6567, 0x8170, 0x0001, 0x5400
    | If the address is right, the compared long equals the immediate -> Z=1.
    | If the bd lost its low half-word we read the decoy 0x0BADBAD0 -> Z=0.
    beq     _framing
    move.l  #0xDEAD0101, %d7          | wrong address (or wrong compare)
    bra     _fail

    | ── Framing: the marker below only runs if the CMPI consumed exactly 12 bytes ──
_framing:
    lea     0x00115430, %a0
    move.l  #0x600D0000, (%a0)
    lea     0x00115430, %a0
    move.l  (%a0), %d0
    cmp.l   #0x600D0000, %d0
    beq     _decoy
    move.l  #0xDEAD0102, %d7
    bra     _fail

    | ── The truncated-address location must be byte-identical ───────────────
_decoy:
    lea     0x00110000, %a0
    move.l  (%a0), %d0
    cmp.l   #0x0BADBAD0, %d0
    beq     _neg
    move.l  #0xDEAD0103, %d7
    bra     _fail

    | ── Negative control: same encoding against a location that does NOT match ──
    |   Proves the Z above came from a real compare at the real address, not from a
    |   stale/never-written CCR.
_neg:
    lea     0x00115400, %a0
    move.l  #0x11112222, (%a0)        | now DIFFERENT from the immediate
    lea     0x00100000, %a0
    .word   0x0CB0, 0x316D, 0x6567, 0x8170, 0x0001, 0x5400
    bne     _pass
    move.l  #0xDEAD0104, %d7
    bra     _fail

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a0)
_halt:
    bra     _halt

    | Spurious Illegal Instruction (vector 4) -> report it distinctly.
_illegal:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0004, %d7
    move.l  %d7, (%a0)
_halt_ill:
    bra     _halt_ill

_fail:
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
_halt_fail:
    bra     _halt_fail
