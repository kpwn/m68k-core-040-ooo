| v2_moveb_movew_mem_dn_merge.s — Task #189 / A1
|
| Regression test for the MOVE.B/.W mem→Dn single-µop crack.  Task #189
| collapses the legacy 2-µop shape (LOAD→TMP1, ALU_MOV_MERGE→Dn) into
| a single UOP_LOAD that reads old Dn via src_b and merges the loaded
| byte/word at LSU writeback time.  The corner that almost bit us
| during implementation: LSU must preserve Dn[31:8] (BYTE) and
| Dn[31:16] (WORD) — `merge_result` in lsu.v handles the substitution,
| gated by `cur_load_merge` latched from iss_load_merge.
|
| Each scenario primes Dn with a distinctive upper pattern, executes
| a MOVE.B or MOVE.W from memory with that Dn as the destination, and
| checks that the upper bits survive untouched while the low byte/word
| gets the memory contents.  Covers:
|
|   - Simple (d16,An) source, .B and .W
|   - (An) postinc source, .B and .W  (merge-LOAD + post-increment)
|   - -(An) predec source, .B and .W  (predec + merge-LOAD)
|   - Abs (xxx).L source, .B and .W
|
| PASS sentinel: 0xC0FFEE00 → 0xFFFF0000.

    .text
    .org 0

_start:
    | ── Seed memory with known patterns ──────────────────────────
    | 0x00002000: 0x12 0x34 0x56 0x78 (byte stream)
    | 0x00002100: 0xAABB  0xCCDD      (word stream)
    move.l  #0x12345678, 0x00002000
    move.l  #0xaabbccdd, 0x00002100

    | ── 1. MOVE.B (d16,An), Dn — preserve Dn[31:8] ───────────────
    move.l  #0xdeadbe00, %d0
    movea.l #0x00002000, %a0
    move.b  (0,%a0), %d0              | loads 0x12 into %d0[7:0]
    cmp.l   #0xdeadbe12, %d0
    bne     _fail

    | ── 2. MOVE.W (d16,An), Dn — preserve Dn[31:16] ─────────────
    move.l  #0xcafe0000, %d1
    movea.l #0x00002100, %a0
    move.w  (0,%a0), %d1              | loads 0xAABB into %d1[15:0]
    cmp.l   #0xcafeaabb, %d1
    bne     _fail

    | ── 3. MOVE.B (An)+, Dn (postinc) — upper preserved, An += 1 ─
    move.l  #0x11223300, %d2
    movea.l #0x00002000, %a1
    move.b  (%a1)+, %d2
    cmp.l   #0x11223312, %d2
    bne     _fail
    cmpa.l  #0x00002001, %a1
    bne     _fail

    | ── 4. MOVE.W (An)+, Dn (postinc) — upper preserved, An += 2 ─
    move.l  #0x44550000, %d3
    movea.l #0x00002100, %a2
    move.w  (%a2)+, %d3
    cmp.l   #0x4455aabb, %d3
    bne     _fail
    cmpa.l  #0x00002102, %a2
    bne     _fail

    | ── 5. MOVE.B -(An), Dn (predec) — upper preserved, An -= 1 ──
    move.l  #0x66770000, %d4
    movea.l #0x00002001, %a3           | predec loads byte at 0x00002000
    move.b  -(%a3), %d4
    cmp.l   #0x66770012, %d4
    bne     _fail
    cmpa.l  #0x00002000, %a3
    bne     _fail

    | ── 6. MOVE.W -(An), Dn (predec) — upper preserved, An -= 2 ──
    move.l  #0x88990000, %d5
    movea.l #0x00002102, %a4           | predec loads word at 0x00002100
    move.w  -(%a4), %d5
    cmp.l   #0x8899aabb, %d5
    bne     _fail
    cmpa.l  #0x00002100, %a4
    bne     _fail

    | ── 7. MOVE.B (xxx).L, Dn — upper preserved ──────────────────
    move.l  #0xfeedfe00, %d6
    .word   0x1c39, 0x0000, 0x2002     | MOVE.B 0x2002.L, D6  (byte=0x56)
    cmp.l   #0xfeedfe56, %d6
    bne     _fail

    | ── 8. MOVE.W (xxx).L, Dn — upper preserved ──────────────────
    move.l  #0xbee70000, %d7
    .word   0x3e39, 0x0000, 0x2102     | MOVE.W 0x2102.L, D7  (word=0xCCDD)
    cmp.l   #0xbee7ccdd, %d7
    bne     _fail

    | ── 9. Back-to-back same Dn: ensure no stale RAT/merge race ──
    | Prime upper bits, do two MOVE.B in a row hitting the same Dn.
    move.l  #0x01020300, %d0
    movea.l #0x00002000, %a0
    move.b  (0,%a0), %d0               | D0 <- 0x01020312
    move.b  (1,%a0), %d0               | D0 <- 0x01020334 (upper from prev!)
    cmp.l   #0x01020334, %d0
    bne     _fail

    | ── 10. MOVE.W then follow-up ALU using same Dn full-width ───
    | The merge broadcast must land BEFORE the ALU consumer reads Dn.
    move.l  #0x99880000, %d1
    movea.l #0x00002100, %a0
    move.w  (0,%a0), %d1               | D1 <- 0x9988aabb
    add.l   #1, %d1
    cmp.l   #0x9988aabc, %d1
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
_fail_halt:
    bra     _fail_halt
