| byte_lane_indexed_store_load_disambig.s — OoO store→load aliasing via
|                                            different base registers
|
| The hypothesis under test:
|   - Older store uses An (base=An_phys, disp=0)
|   - Younger load is brief-indexed; its base is TMP1 (computed as
|     An+disp by an upstream µop), disp=0
|   - iq_mem's static aliasing check (iq_mem.v:343-348) compares
|     {pbase, disp} — TMP1 != An_phys, so the load is NOT held until
|     the store buffer drains, even though the EAs ALIAS the same byte.
|
| Test approach:
|   1. Seed (a0..a0+3) with a sentinel that MUST be visible as the
|      "old" memory if a stale-read occurs.
|   2. Issue many `move.l <new>, (a0)` stores back-to-back to keep the
|      store buffer saturated.
|   3. Between stores, do a brief-indexed byte read of (0,a0,d0:w*1).
|   4. If the byte equals the OLD sentinel rather than the freshly
|      stored value, the load read stale memory → ordering bug.
|
| To bias the OoO scheduler toward racing the load past the store, we
| put a `nop` after the store so the load's source operand (d0=offset)
| is ready well before the store has had time to commit.

    .text
    .org 0

_start:
    lea     0x00104000, %a0

    | Seed (a0..a0+3) with the OLD value.  If the indexed byte load
    | sneaks past the store, we'll read 0xDE/0xAD/0xBE/0xEF instead of
    | the freshly stored bytes.
    move.l  #0xDEADBEEF, (%a0)

    | The "new" value we're about to store.  Distinct bytes so we
    | know which lane reads as stale.
    move.l  #0x11223344, %d2

    | Set up d0..d3 = 0, 1, 2, 3 so the indexed loads use small
    | non-negative offsets that don't trigger sign-extension corners.
    moveq   #0, %d0
    moveq   #1, %d1
    moveq   #2, %d3
    moveq   #3, %d4

    | THE TEST: store via An-based, then 4 brief-indexed byte reads.
    | The reads MUST see the just-stored bytes.  If any read returns
    | the OLD sentinel byte, the load issued ahead of the store —
    | a memory-ordering bug.
    move.l  %d2, (%a0)              | store 0x11_22_33_44 → (a0..a0+3)

    | Indexed BYTE reads — each uses TMP1 as base after the crack.
    cmp.b   (0,%a0,%d0:w*1), %d2    | d0=0 → byte 0 = m68k MSB = 0x11; d2[7:0]=0x44 → mismatch by design
    | Note: cmp.b compares against d2[7:0], so for d2=0x11223344 the
    | comparison only matches at offset 3 (the LSB byte).  Instead of
    | matching, load each byte into a temp and check explicitly.

    | Re-seed and do explicit byte-load via brief-indexed.
    move.l  #0xDEADBEEF, (%a0)
    nop
    nop
    nop
    move.l  %d2, (%a0)

    | Now: 4 dependent indexed byte loads.  Use clr.b + or.b to pull
    | each byte into d5 in a way the scheduler can hoist freely.
    clr.l   %d5
    move.b  (0,%a0,%d0:w*1), %d5   | d5 = byte at (a0+0) — should be 0x11
    cmp.b   #0x11, %d5
    bne     _fail_b0

    clr.l   %d5
    move.b  (0,%a0,%d1:w*1), %d5   | byte at (a0+1) — should be 0x22
    cmp.b   #0x22, %d5
    bne     _fail_b1

    clr.l   %d5
    move.b  (0,%a0,%d3:w*1), %d5   | byte at (a0+2) — should be 0x33
    cmp.b   #0x33, %d5
    bne     _fail_b2

    clr.l   %d5
    move.b  (0,%a0,%d4:w*1), %d5   | byte at (a0+3) — should be 0x44
    cmp.b   #0x44, %d5
    bne     _fail_b3

    | Now stress with multiple stores back-to-back; subsequent indexed
    | loads must each see the LATEST store's bytes.
    move.l  #0x55667788, %d6
    move.l  %d6, (%a0)
    clr.l   %d5
    move.b  (0,%a0,%d0:w*1), %d5
    cmp.b   #0x55, %d5
    bne     _fail_seq_b0

    move.l  #0x99AABBCC, %d6
    move.l  %d6, (%a0)
    clr.l   %d5
    move.b  (0,%a0,%d1:w*1), %d5
    cmp.b   #0xAA, %d5
    bne     _fail_seq_b1

    move.l  #0xF1F2F3F4, %d6
    move.l  %d6, (%a0)
    clr.l   %d5
    move.b  (0,%a0,%d3:w*1), %d5
    cmp.b   #0xF3, %d5
    bne     _fail_seq_b2

    | Final dbf-style stress: tight loop where each iter issues a
    | store and an indexed byte load that depends on that store.
    move.l  #0xAA, %d6              | uniform byte 0xAA in low byte of d6
    | replicate to all 4 bytes
    move.l  %d6, %d7
    lsl.l   #8, %d7
    or.l    %d7, %d6
    move.l  %d6, %d7
    lsl.l   #8, %d7
    or.l    %d7, %d6
    move.l  %d6, %d7
    lsl.l   #8, %d7
    or.l    %d7, %d6                | d6 = 0xAAAAAAAA

    moveq   #3, %d2
.tight_loop:
    move.l  %d6, (%a0)              | store
    cmp.b   (0,%a0,%d2:w*1), %d6    | indexed BYTE cmp vs d6[7:0]=0xAA
    bne     _fail_tight             | mismatch means stale-read
    dbra    %d2, .tight_loop

    lea     0xFFFF0000, %a1
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a1)
_halt:
    bra     _halt

_fail_b0:
    move.l  #0xFB000000, %d7
    bra     _fail
_fail_b1:
    move.l  #0xFB000001, %d7
    bra     _fail
_fail_b2:
    move.l  #0xFB000002, %d7
    bra     _fail
_fail_b3:
    move.l  #0xFB000003, %d7
    bra     _fail
_fail_seq_b0:
    move.l  #0xFB000010, %d7
    bra     _fail
_fail_seq_b1:
    move.l  #0xFB000011, %d7
    bra     _fail
_fail_seq_b2:
    move.l  #0xFB000012, %d7
    bra     _fail
_fail_tight:
    move.l  #0xFB000020, %d7
    or.w    %d2, %d7
_fail:
    lea     0xFFFF0000, %a1
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a1)
_halt_fail:
    bra     _halt_fail
