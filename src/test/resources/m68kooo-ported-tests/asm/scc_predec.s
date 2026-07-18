| scc_predec.s -- Scc -(An) predec ordering corners (task #235 / F3 retry 2)
|
| Validates the V2 3-µop crack for Scc -(An):
|   P0: An -= predec_delta  (ALU_SUB)
|   P1: ALU_SCC TMP0 → TMP1
|   P2: STORE.B TMP1 → (An, NEW value)
|
| Corners I almost got wrong while implementing:
|   - Wiring v2_size to SZ_LONG instead of SZ_BYTE — that gives the EA
|     decoder predec_delta=4 instead of 1 (or 2 for A7).  This test
|     exercises the byte-step rule: A0 → -1, A7 → -2.
|   - Forgetting to feed the NEW An (post-decrement) as src_a on the
|     STORE.  This test re-reads via (An) to check the byte landed at
|     the new address, not the old one.
|   - Phase ordering: if STORE happens before the decrement, the byte
|     would land at the OLD An.  This test checks that the OLD slot is
|     untouched and the NEW slot has the SCC byte.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | Test 1: A0 byte step (-1).  Place a sentinel at OLD A0 to catch
    | wrong-order writes.  After SEQ -(A0), A0 must be A0_orig-1, the
    | byte at the new A0 must be 0xff, and the OLD A0 byte must be
    | unchanged.
    lea     0x00140004, %a0          | A0_orig = 0x00140004
    move.l  #0x11223344, -4(%a0)     | seed entire long
    | put a sentinel byte at the OLD A0 (= 0x00140004 mapped lane 0).
    | The test region [0x00140000..0x00140007] holds two longwords:
    | - 0x00140000: 0x11223344 (the seed above, written via -4(%a0))
    | - 0x00140004: untouched
    move.l  #0xaabbccdd, (%a0)       | seed OLD slot
    moveq   #0, %d5                  | Z=1
    tst.l   %d5
    seq     -(%a0)                   | A0 → 0x00140003, write 0xff there
    bne     _fail1                   | Scc must preserve Z=1
    cmpa.l  #0x00140003, %a0
    bne     _fail1
    | Re-read the seeded longword: 0x11223344 with byte 3 (lane 3) → 0xff.
    lea     0x00140000, %a1
    move.l  (%a1), %d0
    cmp.l   #0x112233ff, %d0
    bne     _fail1
    | OLD slot must still hold its sentinel.
    lea     0x00140004, %a1
    move.l  (%a1), %d0
    cmp.l   #0xaabbccdd, %d0
    bne     _fail1

    | Test 2: A7 byte step (-2).  Use SP for the predec.  Place sentinels
    | so we can detect off-by-one.
    lea     0x00142100, %a7          | A7_orig = 0x00142100
    move.l  #0x55667788, -4(%a7)     | bytes 0..3 of [0x..fc..ff]
    moveq   #1, %d5                  | Z=0
    tst.l   %d5
    sne     -(%a7)                   | A7 → A7_orig - 2 = 0x001420fe
    beq     _fail2                   | Scc must preserve Z=0
    cmpa.l  #0x001420fe, %a7
    bne     _fail2
    | byte at 0x..fe = lane 2 of the [0x..fc..ff] longword → 0xff
    lea     0x001420fc, %a1
    move.l  (%a1), %d0
    cmp.l   #0x5566ff88, %d0
    bne     _fail2

    | Test 3: SF (always-false) -(An) - exercise the predec path with the
    | byte = 0x00 case so we know the predec/SUB path doesn't get
    | suppressed for false conditions.
    lea     0x00144004, %a2
    move.l  #0xdeadbeef, -4(%a2)
    sf      -(%a2)                   | A2 → 0x00144003, write 0x00 there
    cmpa.l  #0x00144003, %a2
    bne     _fail3
    lea     0x00144000, %a1
    move.l  (%a1), %d0
    cmp.l   #0xdeadbe00, %d0
    bne     _fail3

    | Test 4: SHI (high-unsigned) — true when C=0 AND Z=0.  Seed a CMP
    | that gives that, predec into A3, verify both byte and An update.
    lea     0x00146004, %a3
    move.l  #0x33445566, -4(%a3)
    move.l  #5, %d5
    cmpi.l  #3, %d5                  | 5 - 3 → Z=0, C=0 → SHI true
    shi     -(%a3)
    cmpa.l  #0x00146003, %a3
    bne     _fail4
    lea     0x00146000, %a1
    move.l  (%a1), %d0
    cmp.l   #0x334455ff, %d0
    bne     _fail4

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_fail1:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0001, %d0
    move.l  %d0, (%a0)
_halt_fail1:
    bra     _halt_fail1

_fail2:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0002, %d0
    move.l  %d0, (%a0)
_halt_fail2:
    bra     _halt_fail2

_fail3:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0003, %d0
    move.l  %d0, (%a0)
_halt_fail3:
    bra     _halt_fail3

_fail4:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0004, %d0
    move.l  %d0, (%a0)
_halt_fail4:
    bra     _halt_fail4
