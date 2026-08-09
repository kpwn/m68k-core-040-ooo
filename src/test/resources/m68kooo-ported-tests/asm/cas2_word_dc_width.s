| cas2_word_dc_width.s — CAS2.W's Dc write-back width is chosen by the
| ADDRESS register's D/A bit.
|
| Musashi, m68k_in.c M68KMAKE_OP(cas2, 16, ., .), mismatch path:
|   *compare1 = BIT_1F(word2) ? MAKE_INT_16(dest1)
|                             : MASK_OUT_BELOW_16(*compare1) | dest1;
|   *compare2 = BIT_F(word2)  ? MAKE_INT_16(dest2)
|                             : MASK_OUT_BELOW_16(*compare2) | dest2;
| word2 is ext1:ext2 as one long, so BIT_1F is ext1[15] and BIT_F is
| ext2[15] — the D/A bit that says whether the ADDRESS register is An or
| Dn.  When it is An the loaded word is SIGN-EXTENDED over all 32 bits of
| Dc; when it is Dn only Dc's low half is replaced.
|
| That coupling is Musashi/MAME behaviour rather than anything the PRM
| spells out, and Musashi is this project's golden model, so the RTL
| reproduces it exactly.  These tests pin it down in both directions
| using the SAME loaded value (0x8001, negative as a word) so the only
| variable is An-vs-Dn addressing.
|
| CAS2.L has no such split — the loaded long simply replaces Dc — and
| Test 3 pins that too.
|
| PASS = store 0xC0FFEE00 to 0xFFFF0000.

    .text
    .org 0

_start:
    | ── Test 1: CAS2.W mismatch through ADDRESS registers (D/A = 1).
    |   Dc must end up SIGN-EXTENDED: 0x8001 -> 0xFFFF8001, and the
    |   original upper half (0xAAAA / 0xBBBB) must be gone.
    lea     0x50400, %a0
    lea     0x50410, %a1
    move.l  #0x8001FACE, (%a0)        | word0 = 0x8001
    move.l  #0x0002BEEF, (%a1)        | word0 = 0x0002
    move.l  #0xAAAA9999, %d0          | Dc1.W = 0x9999 != 0x8001 -> mismatch
    move.l  #0xBBBBCCCC, %d1          | Dc2.W = 0xCCCC != 0x0002
    move.l  #0x11112222, %d2
    move.l  #0x33334444, %d3
    cas2.w  %d0:%d1,%d2:%d3,(%a0):(%a1)
    beq     _fail
    cmpi.l  #0xFFFF8001, %d0          | sign-extended, upper half REPLACED
    bne     _fail
    cmpi.l  #0x00000002, %d1          | positive word -> zero upper half
    bne     _fail
    move.l  (%a0), %d4                | memory untouched
    cmpi.l  #0x8001FACE, %d4
    bne     _fail
    move.l  (%a1), %d4
    cmpi.l  #0x0002BEEF, %d4
    bne     _fail

    | ── Test 2: identical data, but the addresses now live in DATA
    |   registers (D/A = 0).  Dc must be a low-half MERGE this time, so
    |   0xAAAA / 0xBBBB survive.
    lea     0x50420, %a2
    lea     0x50430, %a3
    move.l  #0x8001FACE, (%a2)
    move.l  #0x0002BEEF, (%a3)
    move.l  #0x00050420, %d5          | Rn1 = D5
    move.l  #0x00050430, %d6          | Rn2 = D6
    move.l  #0xAAAA9999, %d0
    move.l  #0xBBBBCCCC, %d1
    move.l  #0x11112222, %d2
    move.l  #0x33334444, %d3
    cas2.w  %d0:%d1,%d2:%d3,(%d5):(%d6)
    beq     _fail
    cmpi.l  #0xAAAA8001, %d0          | MERGE — upper half preserved
    bne     _fail
    cmpi.l  #0xBBBB0002, %d1
    bne     _fail
    move.l  (%a2), %d4
    cmpi.l  #0x8001FACE, %d4
    bne     _fail
    move.l  (%a3), %d4
    cmpi.l  #0x0002BEEF, %d4
    bne     _fail

    | ── Test 3: CAS2.L mismatch — the loaded long replaces Dc whole,
    |   with no D/A dependence.  Same negative-high-bit operand so a
    |   stray sign-extend or merge would be visible.
    lea     0x50440, %a0
    lea     0x50450, %a1
    move.l  #0x80010203, (%a0)
    move.l  #0x00020304, (%a1)
    move.l  #0xAAAA9999, %d0
    move.l  #0xBBBBCCCC, %d1
    move.l  #0x11112222, %d2
    move.l  #0x33334444, %d3
    cas2.l  %d0:%d1,%d2:%d3,(%a0):(%a1)
    beq     _fail
    cmpi.l  #0x80010203, %d0
    bne     _fail
    cmpi.l  #0x00020304, %d1
    bne     _fail

    | ── Test 4: mixed D/A — pair 1 through An, pair 2 through Dn, so a
    |   single shared "is it a word?" decision instead of a per-pair one
    |   is caught.
    lea     0x50460, %a0
    lea     0x50470, %a2
    move.l  #0x9ABCFACE, (%a0)
    move.l  #0x8DEFBEEF, (%a2)
    move.l  #0x00050470, %d6          | Rn2 = D6, Rn1 stays A0
    move.l  #0x12340000, %d0          | Dc1.W = 0x0000 != 0x9ABC
    move.l  #0x56780000, %d1          | Dc2.W = 0x0000 != 0x8DEF
    move.l  #0x11112222, %d2
    move.l  #0x33334444, %d3
    cas2.w  %d0:%d1,%d2:%d3,(%a0):(%d6)
    beq     _fail
    cmpi.l  #0xFFFF9ABC, %d0          | pair 1 via An -> sign-extend
    bne     _fail
    cmpi.l  #0x56788DEF, %d1          | pair 2 via Dn -> merge
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
