| pea_4x_dcache_mmu.s — repro for HW store-loss on consecutive PEAs.
|
| MMU+dcache enabled (dcache requires MMU on per HW behavior).
|
| HW signature (2026-05-23 break_pc dcache probe):
|   At a clean macro boundary just after 4 consecutive PEAs in ROM, only
|   the LAST PEA's STORE landed in cache.  3 STORE µops vanished.
|
| MMU setup copied verbatim from byte_lane_indexed_mmu_on.s (known-pass).

    .text
    .org 0

    .equ STACK_LO,    0x00104000
    .equ STACK_HI,    0x00104020

_start:
    lea     0x00010000, %a7              | initial supervisor stack (low DRAM)

    | ── MMU TT setup (from movem_postinc_odd_sp_cold_dcache.s — pass) ─
    move.l  #0x000FE020, %d0
    movec   %d0, %dtt0
    move.l  #0x400FE020, %d0
    movec   %d0, %itt0
    move.l  #0xFF00E060, %d0
    movec   %d0, %dtt1
    move.l  #0x00008000, %d0
    movec   %d0, %tc

    | ── D-cache enable ──────────────────────────────────────────────
    move.l  #0x80000000, %d0
    movec   %d0, %cacr

    | ── Pre-fill cache line at STACK_LO with stale "Mac OS-like" data ─
    lea     STACK_LO, %a0
    move.l  #0x4086AC12, (%a0)+
    move.l  #0x4086ABFA, (%a0)+
    move.l  #0x4086ABE2, (%a0)+
    move.l  #0x4086ABCA, (%a0)+
    move.l  #0xAAAAAAAA, (%a0)+
    move.l  #0xAAAAAAAA, (%a0)+
    move.l  #0xAAAAAAAA, (%a0)+
    move.l  #0xAAAAAAAA, (%a0)+

    | ── 4 consecutive PEAs — Mac OS cascade pattern ──────────────────
    lea     STACK_HI, %a7
    pea     %pc@(_t1)
    pea     %pc@(_t2)
    pea     %pc@(_t3)
    pea     %pc@(_t4)

    | A7 should now be STACK_HI - 16 = STACK_LO + 0x10
    cmp.l   #STACK_LO+0x10, %a7
    beq     _check
    move.l  #0xDEAD00A7, %d7
    bra     _fail

_check:
    moveq   #0, %d6

    move.l  0(%a7), %d0
    cmp.l   #_t4, %d0
    beq     _ok0
    bset    #0, %d6
_ok0:
    move.l  4(%a7), %d0
    cmp.l   #_t3, %d0
    beq     _ok1
    bset    #1, %d6
_ok1:
    move.l  8(%a7), %d0
    cmp.l   #_t2, %d0
    beq     _ok2
    bset    #2, %d6
_ok2:
    move.l  12(%a7), %d0
    cmp.l   #_t1, %d0
    beq     _ok3
    bset    #3, %d6
_ok3:
    tst.l   %d6
    beq     _pass
    move.l  #0xDEAD0000, %d7
    or.l    %d6, %d7
    bra     _fail

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
_halt_fail:
    bra     _halt_fail

    .align 4
_t1: nop
_t2: nop
_t3: nop
_t4: nop
