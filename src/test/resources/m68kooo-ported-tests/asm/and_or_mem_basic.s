| and_or_mem_basic.s — AND/OR with memory EA (both src and dst directions)
|
| Exercises the decode cracks added for rom-boot-decode-and-or-mem:
|   - AND.L <ea>,Dn        (mem→reg, flags NZVC)
|   - AND.L Dn,<ea>        (reg→mem RMW, flags NZVC)
|   - OR.L  <ea>,Dn
|   - OR.L  Dn,<ea>
|
| Byte/word forms are fuzzed separately (see tools/fuzz/gen_program.py
| emit_and_mem_* / emit_or_mem_*).  This directed test sticks to .L
| forms because only MOVE.L with (An) / abs / (d16,An) / Dn-direct
| destinations are decoded today — no MOVE.B to (d16,An) exists, so
| byte-test priming is awkward.  Fuzz covers the byte semantics
| against Musashi cross-check.
|
| The ROM halt shape (`AND.B (d16,A0),D1`) is exercised structurally
| via the mode 101 mem-source crack below (with .L); Musashi fuzz
| covers the .B/.W width variants.
|
| PASS sentinel: 0xC0FFEE00 → 0xFFFF0000.

    .text
    .org 0

_start:
    | ── 1. AND.L (A0),D0 — mode 010 ───────────────────────────────────
    move.l  #0x00100000, %a0
    move.l  #0x0F0F0F0F, (%a0)
    move.l  #0xFF00FF00, %d0
    and.l   (%a0), %d0
    move.l  #0x0F000F00, %d7
    cmp.l   %d7, %d0
    bne     _fail

    | ── 2. AND.L (A0)+,D0 — mode 011, post-inc ────────────────────────
    move.l  #0x00100010, %a0
    move.l  #0xAAAA5555, (%a0)
    move.l  #0x5555AAAA, %d0
    and.l   (%a0)+, %d0
    cmp.l   #0, %d0
    bne     _fail
    cmpa.l  #0x00100014, %a0
    bne     _fail

    | ── 3. AND.L (d16,A0),D1 — mode 101 (the ROM halt shape w/ .L) ───
    | Prime memory at absolute 0x00100100, then point A0 at 0x00100000
    | and use d16=0x100.
    move.l  #0x0000003C, 0x00100100
    move.l  #0x00100000, %a0
    move.l  #0xFFFFFFFA, %d1
    and.l   0x100(%a0), %d1
    cmp.l   #0x00000038, %d1
    bne     _fail

    | ── 4. AND.L -(A0),D2 — mode 100, pre-dec ─────────────────────────
    | Prime 0x001001F0 with 0x0F0F0F0F, set A0=0x001001F4, pre-dec
    | reads [A0-4=0x1F0] and puts A0=0x1F0.
    move.l  #0x0F0F0F0F, 0x001001F0
    move.l  #0x001001F4, %a0
    move.l  #0xF0F0F0F0, %d2
    and.l   -(%a0), %d2
    cmp.l   #0, %d2
    bne     _fail
    cmpa.l  #0x001001F0, %a0
    bne     _fail

    | ── 5. AND.L (0x00100330).L,D3 — mode 111/001, long absolute ─────
    move.l  #0xFFFF00FF, 0x00100330
    move.l  #0x0F0FF0F0, %d3
    and.l   0x00100330, %d3
    move.l  #0x0F0F00F0, %d7
    cmp.l   %d7, %d3
    bne     _fail

    | ── 6. OR.L (A1),D4 — mode 010 ────────────────────────────────────
    move.l  #0x00100440, %a1
    move.l  #0x12345678, (%a1)
    move.l  #0x87654321, %d4
    or.l    (%a1), %d4
    move.l  #0x97755779, %d7
    cmp.l   %d7, %d4
    bne     _fail

    | ── 7. OR.L D5,(A2) — mode 010 RMW ────────────────────────────────
    move.l  #0x00100550, %a2
    move.l  #0x00FF00FF, (%a2)
    move.l  #0xFF00FF00, %d5
    or.l    %d5, (%a2)
    move.l  (%a2), %d6
    move.l  #0xFFFFFFFF, %d7
    cmp.l   %d7, %d6
    bne     _fail

    | ── 8. AND.L D5,(A3) — mode 010 RMW ───────────────────────────────
    move.l  #0x00100660, %a3
    move.l  #0xF0F0F0F0, (%a3)
    move.l  #0x0FF00FF0, %d5
    and.l   %d5, (%a3)
    move.l  (%a3), %d6
    move.l  #0x00F000F0, %d7
    cmp.l   %d7, %d6
    bne     _fail

    | ── 9. OR.L D6,(A4)+ — mode 011 RMW + post-inc ────────────────────
    move.l  #0x00100770, %a4
    move.l  #0x0F0F0F0F, (%a4)
    move.l  #0xF0F0F0F0, %d6
    or.l    %d6, (%a4)+
    cmpa.l  #0x00100774, %a4
    bne     _fail
    move.l  0x00100770, %d7
    move.l  #0xFFFFFFFF, %d6
    cmp.l   %d6, %d7
    bne     _fail

    | ── 10. OR.L (0x00100880).L,D7 — mode 111/001 long absolute ──────
    move.l  #0xDEADBEEF, 0x00100880
    move.l  #0x00000001, %d7
    or.l    0x00100880, %d7
    move.l  #0xDEADBEEF, %d6
    cmp.l   %d6, %d7
    bne     _fail

    | ── 11. AND.L Dn,-(An) — mode 100 RMW pre-dec ─────────────────────
    move.l  #0xFF00FF00, 0x00100A90
    move.l  #0x00100A94, %a5
    move.l  #0x0FF00FF0, %d6
    and.l   %d6, -(%a5)
    cmpa.l  #0x00100A90, %a5
    bne     _fail
    move.l  0x00100A90, %d7
    move.l  #0x0F000F00, %d6
    cmp.l   %d6, %d7
    bne     _fail

    | ── PASS ──────────────────────────────────────────────────────────
_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d2
    move.l  %d2, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d2
    move.l  %d2, (%a0)
_halt_fail:
    bra     _halt_fail
