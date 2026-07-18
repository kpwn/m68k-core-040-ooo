| cmp_mem_basic.s — CMP.L <ea>,Dn for memory-source EA modes
|
| Tests the rom-boot-decode-cmp-mem (task #102) landing.  The ROM
| instruction at 0x2e0c is CMP.L -(A0),D0, which was decoded as NOP
| before this change (see docs/rom_boot_bringup.md §3).  This test
| covers that exact shape plus the neighbouring memory-source EA
| modes that the decode landing enables.
|
| Store-then-absolute-load of the same address is avoided — that
| pattern hits a pre-existing LSU abs-load race (tracked by
| tb/tests/asm/abs_load_after_store.s).  The (xxx).L case below
| pre-seeds via a separate An so the abs load isn't racing a
| committed-but-not-yet-drained same-addr store.
|
| PASS sentinel: 0xC0FFEE00 → 0xFFFF0000.

    .text
    .org 0

_start:
    | ── 0. CMP.L (xxx).L,D0 — absolute-long source ─────────────────────
    | Runs FIRST, before any store has entered the LSU S_ST_BUF, so we
    | can exercise the absolute-addressing LOAD path cleanly.  The
    | target is a compile-time slot whose initialiser lives at the tail
    | of the binary (see _cmp_absl_slot below).  Putting this in later
    | cases after sibling stores hits the pre-existing
    | abs_load_after_store LSU race; the (d16,PC) case (case 6) below
    | also exercises the is_abs=1 LOAD µop.
    move.l  #0x5A5A5A5A, %d0
    cmp.l   _cmp_absl_slot, %d0           | (xxx).L absolute load
    bne     _fail

    | ── 1. CMP.L (A0),D0  with match ────────────────────────────────────
    | Stage: data area at 0x00100000.
    move.l  #0x00100000, %a0
    move.l  #0x12345678, %d0
    move.l  %d0, (%a0)              | [A0] = 0x12345678
    cmp.l   (%a0), %d0              | D0 - [A0] = 0 → Z=1
    bne     _fail                   | must be equal

    | Mismatch case.
    move.l  #0x0BADF00D, %d0
    cmp.l   (%a0), %d0              | D0 - 0x12345678 != 0
    beq     _fail

    | ── 2. CMP.L (A0)+,D0 with match, verify A0 += 4 ────────────────────
    move.l  #0x00100010, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
    cmp.l   (%a0)+, %d0             | D0 - [A0] = 0 → Z=1; A0 → +4
    bne     _fail
    | A0 should now be 0x00100014.  Check via CMPA.L #imm,An.
    cmpa.l  #0x00100014, %a0
    bne     _fail

    | ── 3. CMP.L -(A0),D0 — the exact ROM shape at 0x2e0c ───────────────
    | Stash 0xCAFEBABE at 0x00100024, set A0=0x00100028, then
    | CMP.L -(A0),D0 should pre-dec to 0x00100024 and compare.
    move.l  #0x00100024, %a1
    move.l  #0xCAFEBABE, %d1
    move.l  %d1, (%a1)              | [0x00100024] = 0xCAFEBABE
    move.l  #0x00100028, %a0        | A0 = one past
    move.l  #0xCAFEBABE, %d0
    cmp.l   -(%a0), %d0             | predec A0 → 0x00100024, then cmp
    bne     _fail
    | Verify A0 was pre-decremented to 0x00100024.
    cmpa.l  #0x00100024, %a0
    bne     _fail

    | ── 4. CMP.L (d16,A0),D0 — displaced load ──────────────────────────
    move.l  #0x00100030, %a0
    move.l  #0x89ABCDEF, %d0
    move.l  %d0, 16(%a0)            | [A0+16] = 0x89ABCDEF
    cmp.l   16(%a0), %d0
    bne     _fail

    | Mismatch via a different displacement.
    move.l  #0x76543210, %d1
    move.l  %d1, 8(%a0)
    cmp.l   8(%a0), %d0             | D0 (0x89ABCDEF) vs 0x76543210
    beq     _fail

    | ── 5. CMP.L (d16,PC),D0 — PC-relative source ─────────────────────
    | Reads a compile-time-known slot in .text.  Same is_abs=1 LOAD
    | µop as (xxx).L (case 0) but with PC-relative imm.
    move.l  #0xABCD1234, %d0
    cmp.l   _cmp_pc_slot(%pc), %d0         | (d16,PC), loads 0xABCD1234
    bne     _fail

    | ── PASS ───────────────────────────────────────────────────────────
_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d2
    move.l  %d2, (%a0)
_halt:
    bra     _halt

    | ── FAIL ───────────────────────────────────────────────────────────
_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d2
    move.l  %d2, (%a0)
_halt_fail:
    bra     _halt_fail

    | PC-relative compare slot for case 5.
    .align 2
_cmp_pc_slot:
    .long   0xABCD1234

    | Absolute-long compare slot for case 0.
    .align 2
_cmp_absl_slot:
    .long   0x5A5A5A5A
