| mull_sz1_64bit_product.s — task #79 dual-dst PRF directed test
|
| Exercises MULU.L / MULS.L SZ=1 (64-bit product form, PRM §4.140-§4.141).
| Encoding: opword = 0x4C00 | <ea>; ext1 layout:
|   bit 15    = 0
|   bits 14:12 = Dl[2:0]   (low 32-bit dest)
|   bit 11    = Sg          (1=signed, 0=unsigned)
|   bit 10    = Sz          (1=dual-dest 64-bit, 0=single-dest 32-bit)
|   bits 9:3  = 0000000
|   bits 2:0  = Dh[2:0]    (high 32-bit dest; unused when Sz=0)
|
| Product: {Dh:Dl} = Dl_old * <ea>.  Flag semantics on SZ=1:
|   N = prod[63], Z = (prod == 0), V = 0, C = 0, X preserved.
|
| Encoding used below:
|   mulu.l %d2, %d1:%d0     Dh=D1 (ext1[2:0]=001), Dl=D0 (ext1[14:12]=000),
|                           Sg=0, Sz=1 → ext1 = 0x0401, opword = 0x4C02
|   muls.l %d2, %d1:%d0     Sg=1 → ext1 = 0x0C01
|
| The V2 decoder emits this as a SINGLE μop with:
|   has_dst   = 1, arch_dst   = Dl
|   has_dst_b = 1, arch_dst_b = Dh
| RAT allocates two phys regs at dispatch; ALU mul_div stage-3 retire
| fires cdb0 (low 32) + cdb_alu_hi (high 32) the same cycle; commit
| retires both dsts same-cycle via rat_commit_*_b / rat_free_*_b.
|
| Sentinel: 0xC0FFEE00 on PASS, 0xDEADBEEF on FAIL.

    .text
    .org 0

_start:
    | ── Test 1: MULU.L 0x00001000 * 0x00002000 = 0x0000000002000000 ──
    |   D1:D0 = {0x00000000, 0x02000000}
    move.l  #0x00001000, %d0
    move.l  #0x00002000, %d2
    .short  0x4C02, 0x0401    | mulu.l %d2, %d1:%d0
    cmp.l   #0x02000000, %d0
    bne     _fail
    cmp.l   #0x00000000, %d1
    bne     _fail

    | ── Test 2: MULU.L 0x10000 * 0x10000 = 0x0000000100000000 ──
    |   D1:D0 = {0x00000001, 0x00000000}
    move.l  #0x00010000, %d0
    move.l  #0x00010000, %d2
    .short  0x4C02, 0x0401    | mulu.l %d2, %d1:%d0
    cmp.l   #0x00000000, %d0
    bne     _fail
    cmp.l   #0x00000001, %d1
    bne     _fail

    | ── Test 3: MULS.L -3 * 5 = -15 = 0xFFFFFFFFFFFFFFF1 ──
    |   D1:D0 = {0xFFFFFFFF, 0xFFFFFFF1}
    move.l  #0xFFFFFFFD, %d0  | -3
    move.l  #5, %d2
    .short  0x4C02, 0x0C01    | muls.l %d2, %d1:%d0
    cmp.l   #0xFFFFFFF1, %d0
    bne     _fail
    cmp.l   #0xFFFFFFFF, %d1
    bne     _fail

    | ── Test 4: MULS.L -3 * -5 = +15 = 0x000000000000000F ──
    move.l  #0xFFFFFFFD, %d0  | -3
    move.l  #0xFFFFFFFB, %d2  | -5
    .short  0x4C02, 0x0C01    | muls.l %d2, %d1:%d0
    cmp.l   #0x0000000F, %d0
    bne     _fail
    cmp.l   #0x00000000, %d1
    bne     _fail

    | ── Test 5: MULU.L 0xFFFFFFFF * 0xFFFFFFFF = 0xFFFFFFFE_00000001 ──
    move.l  #0xFFFFFFFF, %d0
    move.l  #0xFFFFFFFF, %d2
    .short  0x4C02, 0x0401    | mulu.l %d2, %d1:%d0
    cmp.l   #0x00000001, %d0
    bne     _fail
    cmp.l   #0xFFFFFFFE, %d1
    bne     _fail

    | ── All PASS ──
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
    bra     _halt
