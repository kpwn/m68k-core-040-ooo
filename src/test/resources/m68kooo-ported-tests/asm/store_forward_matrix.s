| store_forward_matrix.s — Comprehensive store-load forwarding test.
|
| Validates store-load forwarding across many scenarios that have been
| historically buggy or are theoretically risky in this OoO core.  The
| LSU + iq_mem disambiguator + dcache together must guarantee:
|     load AFTER store to same address ⇒ load sees stored value.
|
| Scenarios covered (D7 tracks scenario number; on FAIL, D7 is written
| to the sentinel so the failing scenario is identifiable):
|
|   1. .L store → .L load, same EA via (A0)
|   2. .W store → .W load, same EA via (A0)
|   3. .B store → .B load at byte offset 0 of a long
|   4. .B store → .B load at byte offset 1 of a long
|   5. .B store → .B load at byte offset 2 of a long  ← suspected HW bug case
|   6. .B store → .B load at byte offset 3 of a long
|   7. .L store → .B load at byte offset 0  (partial overlap)
|   8. .L store → .B load at byte offset 1
|   9. .L store → .B load at byte offset 2
|  10. .L store → .B load at byte offset 3
|  11. .L store → .W load at byte offset 0
|  12. .L store → .W load at byte offset 2
|  13. .W store → .L load (merge with prior long)
|  14. .B store → .L load (merge with prior long, byte at offset 2)
|  15. Different base reg, same EA: A0=A1, store via A0, load via A1
|  16. Different addressing: (A0) store, abs.L load to same EA
|  17. Different displacement, same EA: (4,A0) store, (0,A1) load
|       where A0+4 == A1+0
|  18. Back-to-back .B stores building a long, then .L load
|  19. Stores across cache-line boundary (32-byte aligned)
|  20. Store/load through PC-relative addressing
|  21. Predecrement store + postincrement load at adjacent addresses
|  22. MOVE.B with index addressing mode
|  23. Multiple stores to different cache lines, then load each
|  24. Long store followed by 4 byte loads at each offset
|
| PASS: all scenarios load expected values; writes 0xC0FFEE00 to
|       0xFFFF0000.
| FAIL: writes scenario_number to 0xFFFF0000.  The first failing
|       scenario is reported.
|
| Note on memory layout:
|   - Stack at 0x000FE000 (INIT_SSP)
|   - Scratch buffer at 0x00100000 (1 MiB up, well clear of stack)
|   - Cache lines are 32 bytes (= 0x20)
|   - Sentinel at 0xFFFF0000

    .text
    .org 0

    .equ INIT_SSP,      0x000FE000
    .equ PASS_SENT,     0xFFFF0000
    .equ SCRATCH,       0x00100000
    .equ SCRATCH2,      0x00101000        | second cache line

_start:
    move.w  #0x2700, %sr                  | supervisor, IPL=7
    move.l  #INIT_SSP, %sp
    moveq   #0, %d7                       | scenario count

| ── Scenario 1: .L store → .L load, same EA via (A0) ──────────────────
    moveq   #1, %d7
    lea     SCRATCH, %a0
    move.l  #0xDEADBEEF, (%a0)            | seed
    move.l  #0xCAFEBABE, %d0
    move.l  %d0, (%a0)                    | STORE
    move.l  (%a0), %d1                    | LOAD
    cmp.l   #0xCAFEBABE, %d1
    bne     _fail

| ── Scenario 2: .W store → .W load ─────────────────────────────────────
    moveq   #2, %d7
    lea     SCRATCH, %a0
    move.l  #0x00000000, (%a0)            | clear
    move.w  #0x1234, (%a0)                | STORE
    move.w  (%a0), %d1
    and.l   #0x0000FFFF, %d1
    cmp.l   #0x00001234, %d1
    bne     _fail

| ── Scenario 3: .B store → .B load at byte offset 0 ───────────────────
    moveq   #3, %d7
    lea     SCRATCH, %a0
    move.l  #0x00000000, (%a0)
    move.b  #0xAA, (%a0)                  | STORE byte at offset 0
    moveq   #0, %d1
    move.b  (%a0), %d1                    | LOAD byte at offset 0
    cmp.l   #0xAA, %d1
    bne     _fail

| ── Scenario 4: .B store → .B load at byte offset 1 ───────────────────
    moveq   #4, %d7
    lea     SCRATCH, %a0
    move.l  #0x00000000, (%a0)
    move.b  #0xBB, 1(%a0)
    moveq   #0, %d1
    move.b  1(%a0), %d1
    cmp.l   #0xBB, %d1
    bne     _fail

| ── Scenario 5: .B store → .B load at byte offset 2 (= 0x1FDFE2 bug shape) ──
    moveq   #5, %d7
    lea     SCRATCH, %a0
    move.l  #0x00000000, (%a0)
    move.b  #0x80, 2(%a0)                 | STORE 0x80 to offset 2
    moveq   #0, %d1
    move.b  2(%a0), %d1                   | LOAD byte at offset 2
    cmp.l   #0x80, %d1
    bne     _fail

| ── Scenario 6: .B store → .B load at byte offset 3 ───────────────────
    moveq   #6, %d7
    lea     SCRATCH, %a0
    move.l  #0x00000000, (%a0)
    move.b  #0xDD, 3(%a0)
    moveq   #0, %d1
    move.b  3(%a0), %d1
    cmp.l   #0xDD, %d1
    bne     _fail

| ── Scenarios 7-10: .L store → .B load at each offset ─────────────────
    moveq   #7, %d7
    lea     SCRATCH, %a0
    move.l  #0x11223344, (%a0)            | .L store
    moveq   #0, %d1
    move.b  (%a0), %d1                    | byte 0 (MSB, big-endian) = 0x11
    cmp.l   #0x11, %d1
    bne     _fail

    moveq   #8, %d7
    moveq   #0, %d1
    move.b  1(%a0), %d1                   | byte 1 = 0x22
    cmp.l   #0x22, %d1
    bne     _fail

    moveq   #9, %d7
    moveq   #0, %d1
    move.b  2(%a0), %d1                   | byte 2 = 0x33
    cmp.l   #0x33, %d1
    bne     _fail

    moveq   #10, %d7
    moveq   #0, %d1
    move.b  3(%a0), %d1                   | byte 3 (LSB) = 0x44
    cmp.l   #0x44, %d1
    bne     _fail

| ── Scenarios 11-12: .L store → .W load at each word offset ───────────
    moveq   #11, %d7
    lea     SCRATCH, %a0
    move.l  #0xAABBCCDD, (%a0)
    moveq   #0, %d1
    move.w  (%a0), %d1                    | word 0 (high) = 0xAABB
    and.l   #0x0000FFFF, %d1
    cmp.l   #0xAABB, %d1
    bne     _fail

    moveq   #12, %d7
    moveq   #0, %d1
    move.w  2(%a0), %d1                   | word 1 (low) = 0xCCDD
    and.l   #0x0000FFFF, %d1
    cmp.l   #0xCCDD, %d1
    bne     _fail

| ── Scenario 13: prior .L store, then .W store overlay, then .L load ──
    moveq   #13, %d7
    lea     SCRATCH, %a0
    move.l  #0x11223344, (%a0)            | initial long
    move.w  #0x5566, (%a0)                | overlay high word
    move.l  (%a0), %d1
    cmp.l   #0x55663344, %d1
    bne     _fail

| ── Scenario 14: prior .L store, then .B store at offset 2, then .L load ──
    moveq   #14, %d7
    lea     SCRATCH, %a0
    move.l  #0x11223344, (%a0)
    move.b  #0x99, 2(%a0)                 | overlay byte 2 = 0x99
    move.l  (%a0), %d1
    cmp.l   #0x11229944, %d1
    bne     _fail

| ── Scenario 15: store via A0, load via A1, where A0==A1 ──────────────
    moveq   #15, %d7
    lea     SCRATCH, %a0
    lea     SCRATCH, %a1                  | independent path to same EA
    move.l  #0xFFFFFFFF, (%a0)
    move.l  #0xC0FFEE01, %d0
    move.l  %d0, (%a0)                    | store via A0
    move.l  (%a1), %d1                    | load via A1
    cmp.l   #0xC0FFEE01, %d1
    bne     _fail

| ── Scenario 16: store via (A0), load via absolute (xxx).L ────────────
    moveq   #16, %d7
    lea     SCRATCH, %a0
    move.l  #0x00000000, (%a0)
    move.l  #0xDEC0DED1, %d0
    move.l  %d0, (%a0)                    | store via (A0)
    move.l  SCRATCH, %d1                  | absolute load
    cmp.l   #0xDEC0DED1, %d1
    bne     _fail

| ── Scenario 17: store at (4,A0), load at (0,A1), where A0+4 == A1 ────
    moveq   #17, %d7
    lea     SCRATCH, %a0
    lea     SCRATCH+4, %a1                | A1 = A0 + 4
    move.l  #0x00000000, 4(%a0)
    move.l  #0xBEEFCAFE, %d0
    move.l  %d0, 4(%a0)                   | store at SCRATCH+4 via (4,A0)
    move.l  (%a1), %d1                    | load at SCRATCH+4 via (A1)
    cmp.l   #0xBEEFCAFE, %d1
    bne     _fail

| ── Scenario 18: four .B stores building a long, then .L load ─────────
    moveq   #18, %d7
    lea     SCRATCH, %a0
    move.l  #0xFFFFFFFF, (%a0)            | seed
    move.b  #0x12, 0(%a0)
    move.b  #0x34, 1(%a0)
    move.b  #0x56, 2(%a0)
    move.b  #0x78, 3(%a0)
    move.l  (%a0), %d1
    cmp.l   #0x12345678, %d1
    bne     _fail

| ── Scenario 19: stores at cache-line boundary (32-byte aligned) ──────
    moveq   #19, %d7
    lea     SCRATCH+0x20, %a0             | start of next cache line
    move.l  #0x00000000, -4(%a0)          | last long of previous line
    move.l  #0x00000000, (%a0)            | first long of new line
    move.l  #0x11111111, %d0
    move.l  #0x22222222, %d2
    move.l  %d0, -4(%a0)                  | store crossing-to-prev line
    move.l  %d2, (%a0)                    | store on new line
    move.l  -4(%a0), %d3                  | load prev line
    cmp.l   #0x11111111, %d3
    bne     _fail
    move.l  (%a0), %d3
    cmp.l   #0x22222222, %d3
    bne     _fail

| ── Scenario 20: PC-relative load after absolute store ────────────────
    moveq   #20, %d7
    lea     _pc_rel_data, %a0
    move.l  #0xBADC0DE5, %d0
    move.l  %d0, (%a0)                    | store via (A0)
    move.l  _pc_rel_data(%pc), %d1        | load via PC-rel
    cmp.l   #0xBADC0DE5, %d1
    bne     _fail

| ── Scenario 21: predecrement store + postincrement load adjacent ─────
    moveq   #21, %d7
    lea     SCRATCH+0x10, %a0             | start in middle of scratch
    move.l  #0x00000000, -(%a0)           | A0 -= 4, store 0
    move.l  #0x00000000, -(%a0)           | A0 -= 4 again
    move.l  #0xAAAABBBB, %d0
    move.l  %d0, -(%a0)                   | predec push: A0 -= 4, store
    move.l  (%a0)+, %d1                   | postinc pop: load then A0 += 4
    cmp.l   #0xAAAABBBB, %d1
    bne     _fail

| ── Scenario 22: indexed addressing store, indexed load ───────────────
    moveq   #22, %d7
    lea     SCRATCH, %a0
    moveq   #8, %d2                       | offset
    move.l  #0x00000000, (%a0,%d2.l)
    move.l  #0xC0DEFADE, %d0
    move.l  %d0, (%a0,%d2.l)              | store via (A0+D2.L)
    move.l  (%a0,%d2.l), %d1              | load via (A0+D2.L)
    cmp.l   #0xC0DEFADE, %d1
    bne     _fail

| ── Scenario 23: multiple stores to different cache lines, then load each ──
    moveq   #23, %d7
    lea     SCRATCH, %a0
    lea     SCRATCH2, %a1                 | different cache line (4 KiB away)
    move.l  #0x12121212, (%a0)
    move.l  #0x34343434, (%a1)
    move.l  #0x56565656, 4(%a0)
    move.l  #0x78787878, 4(%a1)
    move.l  (%a0), %d1
    cmp.l   #0x12121212, %d1
    bne     _fail
    move.l  (%a1), %d1
    cmp.l   #0x34343434, %d1
    bne     _fail
    move.l  4(%a0), %d1
    cmp.l   #0x56565656, %d1
    bne     _fail
    move.l  4(%a1), %d1
    cmp.l   #0x78787878, %d1
    bne     _fail

| ── Scenario 24: long store followed by 4 byte loads ──────────────────
    moveq   #24, %d7
    lea     SCRATCH, %a0
    move.l  #0xA1B2C3D4, (%a0)
    moveq   #0, %d1
    move.b  0(%a0), %d1
    cmp.l   #0xA1, %d1
    bne     _fail
    moveq   #0, %d1
    move.b  1(%a0), %d1
    cmp.l   #0xB2, %d1
    bne     _fail
    moveq   #0, %d1
    move.b  2(%a0), %d1
    cmp.l   #0xC3, %d1
    bne     _fail
    moveq   #0, %d1
    move.b  3(%a0), %d1
    cmp.l   #0xD4, %d1
    bne     _fail

| ── Scenario 25: store then immediate byte load via different An ──────
| (= the same shape as the post-DBF wild jump suspect: A1 base, +2 offset)
    moveq   #25, %d7
    lea     SCRATCH, %a0
    lea     SCRATCH, %a1                  | A1 = A0 = SCRATCH
    move.l  #0x00000000, (%a0)
    move.b  #0x80, 2(%a0)                 | store 0x80 to (A0+2) = SCRATCH+2
    moveq   #0, %d1
    move.b  2(%a1), %d1                   | load via DIFFERENT BASE REG A1+2
    cmp.l   #0x80, %d1
    bne     _fail

| ── Scenario 26: byte-2 store via pushed (-(SP)) word, byte-2 load via An ──
| (= simulates a stack push of a word that has 0x80 at byte offset 2 of the
|  enclosing long, then a byte load of that location via a saved-frame
|  pointer.  Most closely mirrors the suspected real-world bug shape.)
    moveq   #26, %d7
    lea     SCRATCH+0x40, %a0             | initial scratch top
    move.l  %a0, %a7                      | A7 = SCRATCH+0x40
    move.l  #0xFFFFFFFF, %d0
    move.l  %d0, -(%a7)                   | push 0xFFFFFFFF (A7=SCRATCH+0x3C)
    move.l  #0xAA80BBCC, %d0              | byte 1 of D0 = 0x80
    move.l  %d0, -(%a7)                   | push 0xAA80BBCC (A7=SCRATCH+0x38)
    move.l  %a7, %a1                      | A1 = address of pushed long
    moveq   #0, %d1
    move.b  1(%a1), %d1                   | byte offset 1 of pushed long = 0x80
    cmp.l   #0x80, %d1
    bne     _fail

| ── Scenario 27: 4-deep store burst then random-order loads ──────────
    moveq   #27, %d7
    lea     SCRATCH+0x100, %a0
    move.l  #0x00000000, 0(%a0)
    move.l  #0x00000000, 4(%a0)
    move.l  #0x00000000, 8(%a0)
    move.l  #0x00000000, 12(%a0)
    move.l  #0xAAAAAAAA, 0(%a0)
    move.l  #0xBBBBBBBB, 4(%a0)
    move.l  #0xCCCCCCCC, 8(%a0)
    move.l  #0xDDDDDDDD, 12(%a0)
    | Load in mixed order
    move.l  8(%a0), %d1
    cmp.l   #0xCCCCCCCC, %d1
    bne     _fail
    move.l  0(%a0), %d1
    cmp.l   #0xAAAAAAAA, %d1
    bne     _fail
    move.l  12(%a0), %d1
    cmp.l   #0xDDDDDDDD, %d1
    bne     _fail
    move.l  4(%a0), %d1
    cmp.l   #0xBBBBBBBB, %d1
    bne     _fail

| ── Scenario 28: store, intervening arithmetic, load ──────────────────
    moveq   #28, %d7
    lea     SCRATCH, %a0
    move.l  #0x00000000, (%a0)
    move.l  #0xF00DCAFE, %d0
    move.l  %d0, (%a0)                    | store
    | Intervening arithmetic (~10 ops, no memory) to allow store to drain
    moveq   #1, %d2
    add.l   %d2, %d2
    add.l   %d2, %d2
    add.l   %d2, %d2
    add.l   %d2, %d2
    add.l   %d2, %d2
    add.l   %d2, %d2
    add.l   %d2, %d2
    add.l   %d2, %d2
    add.l   %d2, %d2
    add.l   %d2, %d2
    move.l  (%a0), %d1                    | load now
    cmp.l   #0xF00DCAFE, %d1
    bne     _fail

| ── Scenario 29: same address, different displacements that sum equal ─
    moveq   #29, %d7
    lea     SCRATCH+0x10, %a0
    lea     SCRATCH, %a1
    move.l  #0x00000000, 0(%a0)           | = SCRATCH+0x10
    move.l  #0xFACEFEED, %d0
    move.l  %d0, 0(%a0)                   | store at SCRATCH+0x10 (disp=0, A0=SCRATCH+0x10)
    move.l  0x10(%a1), %d1                | load at SCRATCH+0x10 (disp=0x10, A1=SCRATCH)
    cmp.l   #0xFACEFEED, %d1
    bne     _fail

| ── Scenario 30: byte-stride writes followed by aligned-long load ─────
    moveq   #30, %d7
    lea     SCRATCH, %a0
    | First clear with .L store
    move.l  #0x00000000, (%a0)
    | Then byte stride
    move.b  #0xDE, (%a0)
    move.b  #0xAD, 1(%a0)
    move.b  #0xBE, 2(%a0)
    move.b  #0xEF, 3(%a0)
    move.l  (%a0), %d1
    cmp.l   #0xDEADBEEF, %d1
    bne     _fail

| ── All scenarios passed ──────────────────────────────────────────────
_pass:
    move.l  #0xC0FFEE00, PASS_SENT
_halt_pass:
    bra     _halt_pass

| ── Failure path: write D7 (scenario number) to sentinel ──────────────
_fail:
    | Build 0xFAILxxxx where xxxx = scenario#
    | The testbench treats anything other than 0xC0FFEE00 as FAIL.
    | We encode the scenario# in the low word so it's diagnosable.
    move.l  %d7, %d0
    or.l    #0xFA110000, %d0              | tag with 0xFA11
    move.l  %d0, PASS_SENT
_halt_fail:
    bra     _halt_fail

| ── PC-relative data slot for scenario 20 ─────────────────────────────
    .align 4
_pc_rel_data:
    .long   0
