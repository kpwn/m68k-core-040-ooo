| cas2_match_mismatch.s — CAS2.{W,L} happy paths, CCR sourcing, aliasing.
|
| PRM §4.38 / Musashi m68k_in.c M68KMAKE_OP(cas2,*):
|   read op1 @(Rn1), op2 @(Rn2); compare op1:Dc1 and op2:Dc2.
|   BOTH equal  -> store Du1 @(Rn1) and Du2 @(Rn2); Dc1/Dc2 untouched.
|   otherwise   -> Dc1 := op1, Dc2 := op2; MEMORY UNCHANGED.
|   CCR carries compare 1's NZVC, overwritten by compare 2's NZVC only
|   when compare 1 was equal.
|
| The "first compare matched, second did not" case lives in
| cas2_no_partial_update.s; the .W Dc write-back width lives in
| cas2_word_dc_width.s.
|
| PASS = store 0xC0FFEE00 to 0xFFFF0000.

    .text
    .org 0

_start:
    | ── Test 1: CAS2.L, BOTH compares match -> both stores land.
    lea     0x50200, %a0
    lea     0x50210, %a1
    move.l  #0x11111111, (%a0)
    move.l  #0x22222222, (%a1)
    move.l  #0x11111111, %d0          | Dc1 — matches
    move.l  #0x22222222, %d1          | Dc2 — matches
    move.l  #0xAAAAAAAA, %d2          | Du1
    move.l  #0xBBBBBBBB, %d3          | Du2
    cas2.l  %d0:%d1,%d2:%d3,(%a0):(%a1)
    bne     _fail                     | Z must be SET on a double match
    move.l  (%a0), %d4
    cmpi.l  #0xAAAAAAAA, %d4
    bne     _fail
    move.l  (%a1), %d4
    cmpi.l  #0xBBBBBBBB, %d4
    bne     _fail
    cmpi.l  #0x11111111, %d0          | Dc1 untouched on a match
    bne     _fail
    cmpi.l  #0x22222222, %d1          | Dc2 untouched on a match
    bne     _fail

    | ── Test 2: CAS2.L, FIRST compare mismatches.  No store at all, both
    |   Dc take their loaded operand, and the CCR must carry COMPARE 1's
    |   flags — not compare 2's, which here would give Z=1/N=0/C=0.
    lea     0x50220, %a0
    lea     0x50230, %a1
    move.l  #0x55555555, (%a0)
    move.l  #0x66666666, (%a1)
    move.l  #0x99999999, %d0          | Dc1 — MISMATCHES
    move.l  #0x66666666, %d1          | Dc2 — would match
    move.l  #0xCCCCCCCC, %d2
    move.l  #0xDDDDDDDD, %d3
    cas2.l  %d0:%d1,%d2:%d3,(%a0):(%a1)
    beq     _fail                     | Z must be CLEAR
    bpl     _fail                     | N from cmp1: 0x55555555-0x99999999 < 0
    bcc     _fail                     | C from cmp1: borrow
    move.l  (%a0), %d4
    cmpi.l  #0x55555555, %d4          | memory 1 untouched
    bne     _fail
    move.l  (%a1), %d4
    cmpi.l  #0x66666666, %d4          | memory 2 untouched
    bne     _fail
    cmpi.l  #0x55555555, %d0          | Dc1 := op1
    bne     _fail
    cmpi.l  #0x66666666, %d1          | Dc2 := op2
    bne     _fail

    | ── Test 3: CAS2.W, both match.  Only the addressed words change;
    |   the neighbouring words in the same longs must survive, and Dc's
    |   upper halves must survive too.
    lea     0x50240, %a0
    lea     0x50250, %a1
    move.l  #0x1234FACE, (%a0)        | word0 = 0x1234, neighbour 0xFACE
    move.l  #0x5678BEEF, (%a1)
    move.l  #0xFFFF1234, %d0          | Dc1.W matches
    move.l  #0xEEEE5678, %d1          | Dc2.W matches
    move.l  #0x1111ABCD, %d2          | Du1.W = 0xABCD
    move.l  #0x2222EF01, %d3          | Du2.W = 0xEF01
    cas2.w  %d0:%d1,%d2:%d3,(%a0):(%a1)
    bne     _fail
    move.l  (%a0), %d4
    cmpi.l  #0xABCDFACE, %d4          | word written, neighbour intact
    bne     _fail
    move.l  (%a1), %d4
    cmpi.l  #0xEF01BEEF, %d4
    bne     _fail
    cmpi.l  #0xFFFF1234, %d0          | Dc1 fully untouched on a match
    bne     _fail
    cmpi.l  #0xEEEE5678, %d1
    bne     _fail

    | ── Test 4: Rn1 == Rn2 (both pairs address the SAME cell), both
    |   match.  Musashi writes ea1 then ea2, so the second store wins.
    lea     0x50260, %a0
    move.l  #0x77777777, (%a0)
    move.l  #0x77777777, %d0
    move.l  #0x77777777, %d1
    move.l  #0x0000AAAA, %d2          | Du1
    move.l  #0x0000BBBB, %d3          | Du2 — written last
    cas2.l  %d0:%d1,%d2:%d3,(%a0):(%a0)
    bne     _fail
    move.l  (%a0), %d4
    cmpi.l  #0x0000BBBB, %d4
    bne     _fail

    | ── Test 5: Dc1 == Dc2 (the SAME data register), mismatch.  Musashi
    |   assigns *compare1 then *compare2, so op2 is the value left behind.
    lea     0x50270, %a0
    lea     0x50280, %a1
    move.l  #0x0A0A0A0A, (%a0)
    move.l  #0x0B0B0B0B, (%a1)
    move.l  #0xFFFFFFFF, %d0          | Dc1 == Dc2 == D0, mismatches op1
    move.l  #0x1234ABCD, %d2
    move.l  #0x5678EF01, %d3
    cas2.l  %d0:%d0,%d2:%d3,(%a0):(%a1)
    beq     _fail
    cmpi.l  #0x0B0B0B0B, %d0          | op2 assigned last
    bne     _fail
    move.l  (%a0), %d4
    cmpi.l  #0x0A0A0A0A, %d4
    bne     _fail
    move.l  (%a1), %d4
    cmpi.l  #0x0B0B0B0B, %d4
    bne     _fail

    | ── Test 6: address registers held in DATA registers (ext bit 15 = 0),
    |   both compares match.  Guards the {D/A,Rn} decode of both ext words.
    lea     0x50290, %a2
    lea     0x502A0, %a3
    move.l  #0x13579BDF, (%a2)
    move.l  #0x2468ACE0, (%a3)
    move.l  #0x00050290, %d5          | Rn1 = D5
    move.l  #0x000502A0, %d6          | Rn2 = D6
    move.l  #0x13579BDF, %d0
    move.l  #0x2468ACE0, %d1
    move.l  #0x0F0F0F0F, %d2
    move.l  #0xF0F0F0F0, %d3
    cas2.l  %d0:%d1,%d2:%d3,(%d5):(%d6)
    bne     _fail
    move.l  (%a2), %d4
    cmpi.l  #0x0F0F0F0F, %d4
    bne     _fail
    move.l  (%a3), %d4
    cmpi.l  #0xF0F0F0F0, %d4
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
