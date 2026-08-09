| cas2_no_partial_update.s — the case CAS2 exists for.
|
| CAS2's whole reason to exist is that the two swaps are ALL-OR-NOTHING.
| The dangerous implementation bug is emitting the store for pair 1 as
| soon as compare 1 matches, and only THEN discovering that compare 2
| failed: memory 1 has been updated, memory 2 has not, and the lock-free
| data structure the instruction was protecting is now torn.
|
| Every test here has compare 1 MATCHING and compare 2 FAILING, and every
| test asserts that memory 1 is byte-for-byte what it was before.  A
| decoder that stores before both compares are known fails Test 1 on the
| very first assertion.
|
| Test 4 is the literal instruction the System 7.5.3 boot died on:
|   0efc 8040 9041   cas2.l %d0:%d1,%d1:%d1,(%a0):(%a1)
| Note Du1 == Du2 == Dc2 == D1, so it also covers Du/Dc register overlap.
|
| PASS = store 0xC0FFEE00 to 0xFFFF0000.

    .text
    .org 0

_start:
    | ── Test 1: CAS2.L — compare 1 matches, compare 2 does NOT.
    |   Neither cell may change.  Du1 (0xCCCCCCCC) differs from op1, so a
    |   premature store to (%a0) is directly visible.
    lea     0x50300, %a0
    lea     0x50310, %a1
    move.l  #0x33333333, (%a0)
    move.l  #0x44444444, (%a1)
    move.l  #0x33333333, %d0          | Dc1 — MATCHES
    move.l  #0xDEADBEEF, %d1          | Dc2 — does NOT match 0x44444444
    move.l  #0xCCCCCCCC, %d2          | Du1 — must never reach memory
    move.l  #0xDDDDDDDD, %d3          | Du2 — must never reach memory
    cas2.l  %d0:%d1,%d2:%d3,(%a0):(%a1)
    beq     _fail                     | Z must be CLEAR
    move.l  (%a0), %d4
    cmpi.l  #0x33333333, %d4          | PARTIAL UPDATE GUARD
    bne     _fail
    move.l  (%a1), %d4
    cmpi.l  #0x44444444, %d4
    bne     _fail
    cmpi.l  #0x33333333, %d0          | Dc1 := op1 (same value here)
    bne     _fail
    cmpi.l  #0x44444444, %d1          | Dc2 := op2
    bne     _fail

    | ── Test 2: same shape, CAS2.W.  Also checks the neighbouring word
    |   in each long is untouched, so a wrong-width store is caught.
    lea     0x50320, %a0
    lea     0x50330, %a1
    move.l  #0x1234FACE, (%a0)        | word0 = 0x1234
    move.l  #0x5678BEEF, (%a1)        | word0 = 0x5678
    move.l  #0x00001234, %d0          | Dc1.W — MATCHES
    move.l  #0x00009999, %d1          | Dc2.W — does NOT match 0x5678
    move.l  #0x0000AAAA, %d2          | Du1.W
    move.l  #0x0000BBBB, %d3          | Du2.W
    cas2.w  %d0:%d1,%d2:%d3,(%a0):(%a1)
    beq     _fail
    move.l  (%a0), %d4
    cmpi.l  #0x1234FACE, %d4          | PARTIAL UPDATE GUARD (word form)
    bne     _fail
    move.l  (%a1), %d4
    cmpi.l  #0x5678BEEF, %d4
    bne     _fail

    | ── Test 3: compare 1 matches, compare 2 fails, and the CCR must
    |   carry COMPARE 2's flags (Musashi re-evaluates NZVC for compare 2
    |   whenever compare 1 was equal).  op2 0x00000001 - Dc2 0x00000003
    |   gives N=1, C=1; compare 1 would have given N=0, C=0, Z=1.
    lea     0x50340, %a0
    lea     0x50350, %a1
    move.l  #0x80000000, (%a0)
    move.l  #0x00000001, (%a1)
    move.l  #0x80000000, %d0          | Dc1 — matches
    move.l  #0x00000003, %d1          | Dc2 — mismatches
    move.l  #0x11111111, %d2
    move.l  #0x22222222, %d3
    cas2.l  %d0:%d1,%d2:%d3,(%a0):(%a1)
    beq     _fail                     | Z clear
    bpl     _fail                     | N set   (1 - 3 is negative)
    bcc     _fail                     | C set   (1 - 3 borrows)
    move.l  (%a0), %d4
    cmpi.l  #0x80000000, %d4
    bne     _fail
    move.l  (%a1), %d4
    cmpi.l  #0x00000001, %d4
    bne     _fail

    | ── Test 4a: the real 7.5.3 instruction, BOTH compares matching.
    |   cas2.l %d0:%d1,%d1:%d1,(%a0):(%a1) — Du1 == Du2 == Dc2 == D1.
    lea     0x50360, %a0
    lea     0x50370, %a1
    move.l  #0x11223344, %d0          | Dc1
    move.l  #0x55667788, %d1          | Dc2 == Du1 == Du2
    move.l  %d0, (%a0)
    move.l  %d1, (%a1)
    cas2.l  %d0:%d1,%d1:%d1,(%a0):(%a1)
    bne     _fail
    move.l  (%a0), %d4
    cmpi.l  #0x55667788, %d4          | both cells take D1
    bne     _fail
    move.l  (%a1), %d4
    cmpi.l  #0x55667788, %d4
    bne     _fail
    cmpi.l  #0x11223344, %d0          | Dc registers untouched on a match
    bne     _fail
    cmpi.l  #0x55667788, %d1
    bne     _fail

    | ── Test 4b: same instruction, compare 1 matching and compare 2 not.
    |   Because Du1 aliases Dc2, an implementation that writes Dc back
    |   before it has captured the store data corrupts pair 1 here.
    lea     0x50380, %a0
    lea     0x50390, %a1
    move.l  #0x11223344, %d0
    move.l  %d0, (%a0)                | memory 1 matches Dc1
    move.l  #0x99AABBCC, %d2
    move.l  %d2, (%a1)                | memory 2 = 0x99AABBCC
    move.l  #0x55667788, %d1          | Dc2 (== Du1 == Du2) mismatches
    cas2.l  %d0:%d1,%d1:%d1,(%a0):(%a1)
    beq     _fail
    move.l  (%a0), %d4
    cmpi.l  #0x11223344, %d4          | must NOT have taken Du1 (= D1)
    bne     _fail
    move.l  (%a1), %d4
    cmpi.l  #0x99AABBCC, %d4
    bne     _fail
    cmpi.l  #0x11223344, %d0          | Dc1 := op1
    bne     _fail
    cmpi.l  #0x99AABBCC, %d1          | Dc2 := op2
    bne     _fail

    | ── Test 5: compare 1 matches / compare 2 fails with the addresses
    |   held in DATA registers, so the no-store path is exercised through
    |   the ext-bit-15 = 0 decode as well.
    lea     0x503A0, %a2
    lea     0x503B0, %a3
    move.l  #0x0BADF00D, (%a2)
    move.l  #0x0BADCAFE, (%a3)
    move.l  #0x000503A0, %d5
    move.l  #0x000503B0, %d6
    move.l  #0x0BADF00D, %d0          | matches
    move.l  #0x00000000, %d1          | mismatches
    move.l  #0xFFFFFFFF, %d2
    move.l  #0xFFFFFFFF, %d3
    cas2.l  %d0:%d1,%d2:%d3,(%d5):(%d6)
    beq     _fail
    move.l  (%a2), %d4
    cmpi.l  #0x0BADF00D, %d4
    bne     _fail
    move.l  (%a3), %d4
    cmpi.l  #0x0BADCAFE, %d4
    bne     _fail
    cmpi.l  #0x0BADCAFE, %d1
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
