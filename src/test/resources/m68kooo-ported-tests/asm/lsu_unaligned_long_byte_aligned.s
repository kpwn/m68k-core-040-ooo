| lsu_unaligned_long_byte_aligned.s — LONG load/store at ODD byte EAs
| (off=1, off=3) for both LOAD and STORE, on stack and non-stack EAs.
|
| Companion to lsu_unaligned_long_load_xline.s (which only covers
| off=2, the 2-aligned-but-not-4-aligned case).  The off=1 and off=3
| cases were uncovered until bsr_rts_odd_sp surfaced a real-world
| corruption pattern from a Q700 boot.
|
| Layout helpers:
|   The split path needs byte-precise lane masks at off=1 / off=3 so
|   ONLY the targeted bytes are written (and adjacent bytes preserved)
|   on stores, and ONLY the targeted bytes are returned on loads.
|
| Memory map: stage data at 0x00120000..0x0012001F.
|   PA 0x120000..0x120007: 0x11223344_55667788 (untouched sentinels)
|   PA 0x120008..0x12000F: 0x99AABBCC_DDEEFF00 (untouched sentinels)
|
| For each test:
|   1. Pre-seed adjacent bytes with sentinels.
|   2. Do the unaligned op.
|   3. Verify target bytes match expected.
|   4. Verify adjacent sentinel bytes are UNCHANGED (store correctness).

    .text
    .org 0

    .equ FAIL_ADDR, 0xFFFF0000
    .equ PASS_VAL,  0xC0FFEE00
    .equ DATA,      0x00120000

_start:
    | ── Test 1: LOAD at off=1 (non-stack EA) ──────────────────────────
    | Seed memory: PA 0x120000..0x120007 = 11 22 33 44 55 66 77 88
    move.l  #0x11223344, DATA
    move.l  #0x55667788, DATA+4

    | Read long at PA 0x120001 (off=1).  Expected bytes [22,33,44,55] = 0x22334455.
    move.l  DATA+1, %d0
    cmp.l   #0x22334455, %d0
    bne     _fail_t1

    | ── Test 2: LOAD at off=3 (non-stack EA) ──────────────────────────
    | Read long at PA 0x120003 (off=3).  Expected bytes [44,55,66,77] = 0x44556677.
    move.l  DATA+3, %d0
    cmp.l   #0x44556677, %d0
    bne     _fail_t2

    | ── Test 3: STORE at off=1 (non-stack EA) ─────────────────────────
    | Re-seed memory.
    move.l  #0x11223344, DATA
    move.l  #0x55667788, DATA+4

    | Write 0xAABBCCDD at PA 0x120001 (off=1).  Expect bytes:
    |   [120000]=11 (sentinel preserved) [120001]=AA [120002]=BB
    |   [120003]=CC [120004]=DD [120005]=66 (sentinel preserved)
    | Final aligned reads:
    |   long@120000 = 11AABBCC
    |   long@120004 = DD667788
    move.l  #0xAABBCCDD, %d1
    move.l  %d1, DATA+1
    move.l  DATA, %d0
    cmp.l   #0x11AABBCC, %d0
    bne     _fail_t3a
    move.l  DATA+4, %d0
    cmp.l   #0xDD667788, %d0
    bne     _fail_t3b

    | ── Test 4: STORE at off=3 (non-stack EA) ─────────────────────────
    move.l  #0x11223344, DATA
    move.l  #0x55667788, DATA+4

    | Write 0xAABBCCDD at PA 0x120003 (off=3).  Expect bytes:
    |   [120000..120002]=11,22,33 (preserved) [120003]=AA
    |   [120004]=BB [120005]=CC [120006]=DD [120007]=88 (preserved)
    | Final aligned reads:
    |   long@120000 = 112233AA
    |   long@120004 = BBCCDD88
    move.l  #0xAABBCCDD, %d1
    move.l  %d1, DATA+3
    move.l  DATA, %d0
    cmp.l   #0x112233AA, %d0
    bne     _fail_t4a
    move.l  DATA+4, %d0
    cmp.l   #0xBBCCDD88, %d0
    bne     _fail_t4b

    | ── Test 5: STORE / LOAD at off=1 on STACK (predecrement -(SP)) ──
    | Set SP to an odd-byte address.  We stage SP=0x00100001 which after
    | move.l ...,-(SP) becomes 0x000FFFFD (off=1 of word at 0x000FFFFC).
    | Pre-seed sentinel bytes around the push slot.
    move.l  #0xCAFE0011, 0x000FFFF8
    move.l  #0xCAFE0022, 0x000FFFFC
    move.l  #0x00100001, %a7      | A7 = odd
    | Pre-decrement long: A7 = 0x000FFFFD (off=1).
    move.l  #0xDEADBEEF, %d2
    move.l  %d2, -(%a7)
    cmp.l   #0x000FFFFD, %a7
    bne     _fail_t5a
    | Verify pushed bytes: 0x000FFFFD..0x00100000 = DE AD BE EF.
    | We pre-stored 0xCAFE0022 at 0x000FFFFC, so byte 0x000FFFFC=CA
    | (high byte of stored value), and bytes 0x000FFFFD..0x000FFFFF
    | got OVERWRITTEN by the push to DE,AD,BE.
    | aligned read at 0x000FFFFC: bytes [CA, DE, AD, BE] = 0xCADEADBE.
    move.l  0x000FFFFC, %d0
    cmp.l   #0xCADEADBE, %d0
    bne     _fail_t5b
    | aligned read at 0x00100000: bytes [EF, ?, ?, ?].  We don't know
    | what was at 0x00100001..03; just check the high byte.
    move.l  0x00100000, %d0
    | Mask top byte
    and.l   #0xFF000000, %d0
    cmp.l   #0xEF000000, %d0
    bne     _fail_t5c
    | Pop it back: misaligned long load at A7=0x000FFFFD, post-inc.
    move.l  (%a7)+, %d3
    cmp.l   #0xDEADBEEF, %d3
    bne     _fail_t5d
    cmp.l   #0x00100001, %a7
    bne     _fail_t5e

    | ── Test 6: STORE / LOAD at off=3 on STACK ───────────────────────
    | A7 starts at 0x00100003, predec-long → A7 = 0x000FFFFF (off=3).
    | Note: we want a fresh sentinel layout for byte-level verify.
    move.l  #0x11223344, 0x000FFFFC
    move.l  #0x55667788, 0x00100000
    move.l  #0x00100003, %a7
    move.l  #0xCAFEBABE, %d2
    move.l  %d2, -(%a7)            | A7 = 0x000FFFFF (off=3)
    cmp.l   #0x000FFFFF, %a7
    bne     _fail_t6a
    | Bytes [0x000FFFFF..0x00100002] = CA FE BA BE.
    | aligned word at 0x000FFFFC: bytes [11, 22, 33, CA] = 0x112233CA
    move.l  0x000FFFFC, %d0
    cmp.l   #0x112233CA, %d0
    bne     _fail_t6b
    | aligned word at 0x00100000: bytes [FE, BA, BE, 88] = 0xFEBABE88
    move.l  0x00100000, %d0
    cmp.l   #0xFEBABE88, %d0
    bne     _fail_t6c
    | Pop it back:
    move.l  (%a7)+, %d3
    cmp.l   #0xCAFEBABE, %d3
    bne     _fail_t6d
    cmp.l   #0x00100003, %a7
    bne     _fail_t6e

    | ── Test 7: 2-byte-aligned load (off=2, control — must pass) ─────
    move.l  #0x11223344, DATA
    move.l  #0x55667788, DATA+4
    move.l  DATA+2, %d0
    cmp.l   #0x33445566, %d0
    bne     _fail_t7

    | ── All passed — PASS sentinel ──────────────────────────────────
    move.l  #0x00200000, %a7      | restore sane SP
    lea     FAIL_ADDR, %a0
    move.l  #PASS_VAL, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_fail_t1:
    move.l  #0xDEAD0001, %d7
    bra     _fail
_fail_t2:
    move.l  #0xDEAD0002, %d7
    bra     _fail
_fail_t3a:
    move.l  #0xDEAD0030, %d7
    bra     _fail
_fail_t3b:
    move.l  #0xDEAD0031, %d7
    bra     _fail
_fail_t4a:
    move.l  #0xDEAD0040, %d7
    bra     _fail
_fail_t4b:
    move.l  #0xDEAD0041, %d7
    bra     _fail
_fail_t5a:
    move.l  #0xDEAD0050, %d7
    bra     _fail
_fail_t5b:
    move.l  #0xDEAD0051, %d7
    bra     _fail
_fail_t5c:
    move.l  #0xDEAD0052, %d7
    bra     _fail
_fail_t5d:
    move.l  #0xDEAD0053, %d7
    bra     _fail
_fail_t5e:
    move.l  #0xDEAD0054, %d7
    bra     _fail
_fail_t6a:
    move.l  #0xDEAD0060, %d7
    bra     _fail
_fail_t6b:
    move.l  #0xDEAD0061, %d7
    bra     _fail
_fail_t6c:
    move.l  #0xDEAD0062, %d7
    bra     _fail
_fail_t6d:
    move.l  #0xDEAD0063, %d7
    bra     _fail
_fail_t6e:
    move.l  #0xDEAD0064, %d7
    bra     _fail
_fail_t7:
    move.l  #0xDEAD0007, %d7
    bra     _fail

_fail:
    move.l  #0x00200000, %a7
    lea     FAIL_ADDR, %a0
    move.l  %d7, (%a0)
_halt_fail:
    bra     _halt_fail
