| pea_4x_consecutive_stores.s — repro for HW store-loss on consecutive PEAs.
|
| Mac OS Q700 ROM pattern at 0x4086ab9c-0x4086aba8: 4 consecutive
| `pea (d16,PC)` instructions push 4 cleanup-PCs onto the stack.  Each
| PEA is cracked into [ALU_SUB A7,#4; STORE imm_data → (A7)].
|
| HW evidence (2026-05-23 break_pc + dcache probe at exc_count=4786):
|   * A7 decrements by 16 correctly (all 4 SUBs retired)
|   * Only the LAST PEA's STORE landed in cache; PEA1/PEA2/PEA3's
|     stored values are absent from the entire dcache AND DRAM
|
| Test plan:
|   1. Pre-fill the stack region with a sentinel pattern (0xAAAAAAAA).
|   2. Set A7 to a 16-byte-aligned address within the sentinel region.
|   3. Execute 4 consecutive `pea (d16,PC)` macros pushing 4 distinct
|      ROM-PC values (0x4001..0x4004) onto the stack.
|   4. Verify mem[A7+0..A7+12] contains the 4 expected values.
|      If ANY slot still has the sentinel, FAIL.
|
| PASS: 0xC0FFEE00 at 0xFFFF0000.
| FAIL: 0xDEAD00xx where xx encodes which slot(s) had sentinel.

    .text
    .org 0

_start:
    | Initial stack: well above the test region so we don't overlap.
    lea     0x000F0000, %a7

    | ── Pre-fill the test stack region with sentinel ─────────────────
    | Stack will land at 0x00010100 area.  Pre-write 32 bytes (one cache
    | line) so any missing PEA store leaves the sentinel visible.
    lea     0x00010100, %a0
    move.l  #0xAAAAAAAA, (%a0)+
    move.l  #0xAAAAAAAA, (%a0)+
    move.l  #0xAAAAAAAA, (%a0)+
    move.l  #0xAAAAAAAA, (%a0)+
    move.l  #0xAAAAAAAA, (%a0)+
    move.l  #0xAAAAAAAA, (%a0)+
    move.l  #0xAAAAAAAA, (%a0)+
    move.l  #0xAAAAAAAA, (%a0)+

    | ── Set A7 to the top of the test region (= start of PEAs) ───────
    | After 4 PEAs (each -=4), A7 lands at 0x00010110.
    lea     0x00010120, %a7

    | ── 4 consecutive PEA (d16,PC) — exactly the Mac OS pattern ──────
_pea_block:
    pea     %pc@(_target1)              | PEA1: pushes &_target1 at A7-=4
    pea     %pc@(_target2)              | PEA2
    pea     %pc@(_target3)              | PEA3
    pea     %pc@(_target4)              | PEA4: pushes &_target4 at final A7

    | ── Verify A7 advanced by exactly 16 bytes ──────────────────────
    cmp.l   #0x00010110, %a7
    beq     _check_stores
    move.l  #0xDEAD00A7, %d7
    bra     _fail

_check_stores:
    | ── Check each pushed value is the expected target PC ────────────
    | A7 = 0x00010110.  Stack layout (PEA semantics):
    |   mem[A7+0]  = &_target4   (last PEA, lowest addr)
    |   mem[A7+4]  = &_target3
    |   mem[A7+8]  = &_target2
    |   mem[A7+12] = &_target1   (first PEA, highest addr)
    moveq   #0, %d6                     | fail-bitmask accumulator

    | Slot 0 (A7+0) == &_target4
    move.l  0(%a7), %d0
    cmp.l   #_target4, %d0
    beq     _ok0
    bset    #0, %d6                     | flag slot-0 FAIL
_ok0:
    | Slot 1 (A7+4) == &_target3
    move.l  4(%a7), %d0
    cmp.l   #_target3, %d0
    beq     _ok1
    bset    #1, %d6
_ok1:
    | Slot 2 (A7+8) == &_target2
    move.l  8(%a7), %d0
    cmp.l   #_target2, %d0
    beq     _ok2
    bset    #2, %d6
_ok2:
    | Slot 3 (A7+12) == &_target1
    move.l  12(%a7), %d0
    cmp.l   #_target1, %d0
    beq     _ok3
    bset    #3, %d6
_ok3:
    tst.l   %d6
    beq     _pass
    | At least one slot was wrong.  Encode the bitmask in the FAIL sentinel.
    move.l  #0xDEAD0000, %d7
    or.l    %d6, %d7                    | d7 = 0xDEAD000<bitmask>
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

| ── PEA targets — these labels' PCs are what the PEAs push ──────────
| (We never actually jump here; we just need their addresses.)
    .align 4
_target1:
    nop
_target2:
    nop
_target3:
    nop
_target4:
    nop
