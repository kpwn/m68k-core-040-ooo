| tb_moves_v2.s — B9 V2 MOVES family coverage (in supervisor mode).
|
| Exercises every legacy MOVES EA shape now routed through the V2
| `v2_moves_fire` override block in decode.v.  Each cell maps to a
| distinct case in the override:
|   - (An)         (single LOAD or STORE, len=4)
|   - (An)+        (2 phase: LOAD/STORE then ALU_ADD An)
|   - -(An)        (2 phase: ALU_SUB An then LOAD/STORE)
|   - (d16,An)     (single LOAD or STORE, ext2 disp16, len=6)
|   - (xxx).W      (single LOAD or STORE, ext2 abs.W, is_abs, len=6)
|   - (xxx).L      (single LOAD or STORE, ext2:ext3 abs.L, is_abs, len=8)
|
| MOVES is privileged; this test runs from the cold-boot supervisor
| state (no MOVE TO SR needed — boot enters supervisor with S=1).
|
| SFC/DFC alternate-FC bus drive is captured via the
| is_moves_internal / fc_use_dfc_internal side-channel for a future
| FC-aware AXI bus.  This test does NOT verify FC pin behavior (no
| FC pins on the bus today) — only the LOAD/STORE data transfer.
|
| PASS = store 0xC0FFEE00 to 0xFFFF0000.

    .text
    .org 0

_start:
    | Program SFC = 5, DFC = 1 to exercise MOVEC plumbing.
    move.l  #5, %d0
    movec   %d0, %sfc
    move.l  #1, %d0
    movec   %d0, %dfc

    | ── Test 1: MOVES.L D1,(An) — single µop, store ─────────────────
    lea     0x80000, %a0
    move.l  #0xCAFEBABE, %d1
    moves.l %d1, (%a0)
    move.l  (%a0), %d0
    cmp.l   #0xCAFEBABE, %d0
    bne     _fail

    | ── Test 2: MOVES.L (An),D2 — single µop, load ──────────────────
    moves.l (%a0), %d2
    cmp.l   #0xCAFEBABE, %d2
    bne     _fail

    | ── Test 3: MOVES.W D3,(An)+ — 2-phase, A1 += 2 ─────────────────
    lea     0x80100, %a1
    move.l  #0xDEAD1234, %d3
    moves.w %d3, (%a1)+
    move.l  %a1, %d0
    cmpi.l  #0x80102, %d0                | post-inc by 2 (.W)
    bne     _fail
    move.w  -(%a1), %d0
    andi.l  #0xFFFF, %d0
    cmpi.l  #0x1234, %d0
    bne     _fail

    | ── Test 4: MOVES.B (An)+,D4 — 2-phase load, A2 += 1 ────────────
    lea     0x80200, %a2
    move.b  #0x77, (%a2)
    moves.b (%a2)+, %d4
    move.l  %a2, %d0
    cmpi.l  #0x80201, %d0                | post-inc by 1 (.B)
    bne     _fail
    andi.l  #0xFF, %d4
    cmpi.l  #0x77, %d4
    bne     _fail

    | ── Test 5: MOVES.L D5,-(An) — predec by 4 ──────────────────────
    lea     0x80304, %a3
    move.l  #0xFEEDF00D, %d5
    moves.l %d5, -(%a3)
    move.l  %a3, %d0
    cmpi.l  #0x80300, %d0
    bne     _fail
    move.l  (%a3), %d0
    cmp.l   #0xFEEDF00D, %d0
    bne     _fail

    | ── Test 6: MOVES.B -(An),D6 — predec by 1 (non-A7) load ────────
    lea     0x80401, %a4
    move.b  #0x88, -(%a4)                 | pre-write byte at 0x80400 via plain
    | Reset a4 and predec via MOVES
    lea     0x80401, %a4
    moves.b -(%a4), %d6
    move.l  %a4, %d0
    cmpi.l  #0x80400, %d0
    bne     _fail
    andi.l  #0xFF, %d6
    cmpi.l  #0x88, %d6
    bne     _fail

    | ── Test 7: MOVES.L D7,(d16,An) — disp=+0x60 ────────────────────
    lea     0x80500, %a5
    move.l  #0x12345678, %d7
    moves.l %d7, 0x60(%a5)
    move.l  0x60(%a5), %d0
    cmp.l   #0x12345678, %d0
    bne     _fail

    | ── Test 8: MOVES.W (d16,An),D0 — load with disp ─────────────────
    | Memory at 0x80560 holds 0x12345678 (big-endian bytes 12,34,56,78).
    | MOVES.W reads the high word: D0[15:0] = 0x1234.
    moves.w 0x60(%a5), %d0
    andi.l  #0xFFFF, %d0
    cmpi.l  #0x1234, %d0
    bne     _fail

    | ── Test 9: MOVES.L D1,(xxx).W — abs.W ──────────────────────────
    | Use addr 0x6F00 (16-bit absolute, sign-extends positive)
    move.l  #0xABCDEF01, %d1
    moves.l %d1, 0x6F00.w
    | Verify via plain MOVE.L from same addr through (An)
    lea     0x00006F00, %a0
    move.l  (%a0), %d0
    cmp.l   #0xABCDEF01, %d0
    bne     _fail

    | ── Test 10: MOVES.L (xxx).L,D2 — abs.L load ─────────────────────
    | Use addr 0x80700 (32-bit absolute)
    lea     0x00080700, %a1
    move.l  #0x33445566, (%a1)
    moves.l 0x00080700, %d2
    cmp.l   #0x33445566, %d2
    bne     _fail

    | ── PASS ─────────────────────────────────────────────────────────
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt
