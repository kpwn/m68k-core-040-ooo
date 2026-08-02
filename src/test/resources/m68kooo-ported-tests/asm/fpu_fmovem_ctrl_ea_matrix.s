| fpu_fmovem_ctrl_ea_matrix.s — FMOVEM.L control reg list
|                                (FPCR/FPSR/FPIAR) through the remaining
|                                EA modes landed alongside -(An):
|                                (An), (An)+, (xxx).W, (xxx).L.
|                                (d16,An) is pre-existing/unchanged and
|                                already covered by fpu_fmovem_ctrl_reg.s;
|                                -(An) has its own dedicated test,
|                                fpu_fmovem_ctrl_predec.s.
|
| Each mode's expected memory layout was derived from — and empirically
| cross-checked against, via tb/models/musashi_run — this repo's
| vendored Musashi v4.60 fmove_fpcr() (tb/models/musashi/m68kfpu.c).
| See decode_1111.vh's fmoveml_ctrl_ea header comment for the full
| derivation and the known Musashi coverage gaps (mode 111 store to
| (xxx).W hard-crashes Musashi; multi-register (d16,An)/(xxx).W/(xxx).L
| desync Musashi's own PC) that this landing deliberately does NOT
| reproduce — those gaps are Musashi-model artifacts, not real-hardware
| behavior, and are kept out of the fuzzer's generation set instead
| (tools/fuzz/gen_program.py).
|
| (An) direct — mode=010: Musashi's WRITE_EA_32/READ_EA_32 case 2 always
|   computes `ea = REG_A[reg]` with NO increment and NO per-register
|   offset — every present register in the list targets the identical
|   address.  Store: only the LAST-processed present register (FPIAR,
|   since access order is always FPCR,FPSR,FPIAR) survives in memory.
|   (Load direction exists in decode too, but see the KNOWN SEPARATE
|   BUG note below — not asserted here.)
|
| (An)+ postincrement — mode=011: forward packing (FPCR@0, FPSR@4,
|   FPIAR@8), same as (d16,An)/predecrement's forward table, PLUS a
|   real An += 4*popcount side effect (confirmed via musashi_run: 3-reg
|   store from An=0x21000 leaves FPCR@0x21000/FPSR@0x21004/FPIAR@0x21008,
|   final An=0x2100c).
|
| (xxx).W / (xxx).L — mode=111 reg=000/001: forward packing, absolute
|   base address from one (.W, sign-extended) or two (.L, hi:lo) extra
|   extension words, no address-register side effect at all.
|
| ⚠ KNOWN SEPARATE BUG (found 2026-07-23 while building this test,
|   NOT fixed by this landing, NOT specific to any single EA mode):
|   rob.v (~line 1106-1107) unconditionally marks every UOP_SYS entry
|   `e_complete` at DISPATCH time rather than waiting for its own
|   has_src_a operand's producer to finish — correct for the opcodes
|   that shortcut is meant for (STOP/RESET/RTE-stub/MOVEC-stub/ANDI-
|   ORI-EORI-SR/MOVE-USP), wrong for SYS_FMOVE_FPCR_WR/_FPSR_WR/
|   _FPIAR_WR when fed by a same-crack LOAD still in flight — exactly
|   the FMOVEM control-list "mem→ctrl" (load) direction crack shape.
|   Confirmed via RTL dispatch/CDB tracing that address computation
|   and the LOAD's own data are both correct; only the ctrl-register
|   commit ends up stale (reads 0).  Confirmed 100% pre-existing (not
|   introduced by any EA mode added in this landing): the ALREADY-
|   SHIPPED (d16,An) load path shows the identical symptom against a
|   freshly-touched buffer — the one existing test
|   (fpu_fmovem_ctrl_reg.s) never caught it only because it happens to
|   reuse the exact address its own preceding store just wrote.  Filed
|   as a dedicated ROB/commit.v follow-up, out of scope here (decode
|   is correct; the bug is in completion tracking, an unrelated and
|   historically fragile area).  Consequently: STORE direction (the
|   confirmed real-HW-blocking scenario) is fully asserted below for
|   every mode; LOAD direction is asserted only where the check doesn't
|   route through the broken path (An+ address arithmetic), and is
|   otherwise limited to "must not F-line" (decode coverage) without
|   asserting exact register content.
|
| PASS sentinel: 0xC0FFEE00.
| FAIL sentinels: 0xDEAD20xx — see comments at each check site.

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000

_start:
    lea     0x00010000, %a7
    move.l  #_fline, 0x0000002C        | vec 11 F-line — must NOT fire

    | ══════════════════ (An) direct — mode=010 ═══════════════════════
    move.l  #0xCCCCCCCC, %d0
    .short  0xF200, 0x9000             | FMOVE.L D0,FPCR
    move.l  #0xDDDDDDDD, %d0
    .short  0xF200, 0x8800             | FMOVE.L D0,FPSR
    | FPIAR stays at reset default (0).

    lea     0x00021000, %a0
    .short  0xF210, 0xBC00             | FMOVEM.L FPCR/FPSR/FPIAR,(A0)
    | Same-address clobber: only FPIAR (processed last) survives.
    move.l  0x00021000, %d1
    cmp.l   #0x00000000, %d1
    bne     _fail_20_01                | mem[(A0)] should be FPIAR(0), not FPCR

    | Load direction: decode coverage only (must not F-line) — exact
    | register content not asserted, see KNOWN SEPARATE BUG above.
    move.l  #0x44444444, 0x00021100
    lea     0x00021100, %a0
    .short  0xF210, 0x9C00             | FMOVEM.L (A0),FPCR/FPSR/FPIAR

    | ══════════════════ (An)+ postincrement — mode=011 ═══════════════
    move.l  #0x55555555, %d0
    .short  0xF200, 0x9000             | FMOVE.L D0,FPCR
    move.l  #0x66666666, %d0
    .short  0xF200, 0x8800             | FMOVE.L D0,FPSR

    lea     0x00022000, %a0
    .short  0xF218, 0xBC00             | FMOVEM.L FPCR/FPSR/FPIAR,(A0)+
    cmp.l   #0x0002200C, %a0
    bne     _fail_20_05
    move.l  0x00022000, %d1
    cmp.l   #0x55555555, %d1
    bne     _fail_20_06
    move.l  0x00022004, %d1
    cmp.l   #0x66666666, %d1
    bne     _fail_20_07
    | NOTE: the FPIAR slot (0x00022008) is deliberately NOT checked
    | here — the (An)-direct LOAD-direction section above may have
    | already altered arch_fpiar via the KNOWN SEPARATE BUG's
    | timing-dependent path (FPCR/FPSR are re-armed via FMOVE.L Dn,FPx
    | immediately above and so stay deterministic; FPIAR has no such
    | single-register write opcode to re-arm it, so its value here is
    | whatever the earlier section left it as).

    | Load direction: address arithmetic (An postincrement) IS asserted
    | — it's a plain INT ALU_ADD µop, unaffected by the known bug.
    | Register content is not asserted.
    move.l  #0x77777777, 0x00022100
    move.l  #0x88888888, 0x00022104
    move.l  #0x99999999, 0x00022108
    lea     0x00022100, %a0
    .short  0xF218, 0x9C00             | FMOVEM.L (A0)+,FPCR/FPSR/FPIAR
    cmp.l   #0x0002210C, %a0
    bne     _fail_20_09

    | ══════════════════ (xxx).W absolute short — mode=111 reg=000 ════
    move.l  #0xAAAA1111, %d0
    .short  0xF200, 0x9000             | FMOVE.L D0,FPCR
    move.l  #0xAAAA2222, %d0
    .short  0xF200, 0x8800             | FMOVE.L D0,FPSR

    .short  0xF238, 0xBC00, 0x7000     | FMOVEM.L FPCR/FPSR/FPIAR,(0x7000).W
    move.l  0x00007000, %d1
    cmp.l   #0xAAAA1111, %d1
    bne     _fail_20_13
    move.l  0x00007004, %d1
    cmp.l   #0xAAAA2222, %d1
    bne     _fail_20_14
    | FPIAR slot (0x00007008) not checked — see the (An)+ section's
    | note above; FPIAR's value is contaminated by earlier
    | LOAD-direction sections' KNOWN SEPARATE BUG timing dependence.

    | Load direction: decode coverage only (must not F-line).  Note:
    | (xxx).W STORE direction has no case at all in Musashi's
    | WRITE_EA_32 (hard fatalerror, even for a single register) — real
    | 68040 silicon certainly supports it; this is a Musashi coverage
    | gap, not a hardware restriction (see decode_1111.vh header
    | comment) — implemented here per "adhere to Musashi/MAME's
    | behavior or better."  Never fuzz-generate (xxx).W store for this
    | reason (tools/fuzz/gen_program.py).
    move.l  #0xBBBB1111, 0x00007100
    move.l  #0xBBBB2222, 0x00007104
    move.l  #0xBBBB3333, 0x00007108
    .short  0xF238, 0x9C00, 0x7100     | FMOVEM.L (0x7100).W,FPCR/FPSR/FPIAR

    | ══════════════════ (xxx).L absolute long — mode=111 reg=001 ═════
    move.l  #0xCCCC1111, %d0
    .short  0xF200, 0x9000             | FMOVE.L D0,FPCR
    move.l  #0xCCCC2222, %d0
    .short  0xF200, 0x8800             | FMOVE.L D0,FPSR

    .short  0xF239, 0xBC00, 0x0005, 0x0000   | FMOVEM.L FPCR/FPSR/FPIAR,(0x00050000).L
    move.l  0x00050000, %d1
    cmp.l   #0xCCCC1111, %d1
    bne     _fail_20_19
    move.l  0x00050004, %d1
    cmp.l   #0xCCCC2222, %d1
    bne     _fail_20_20
    | FPIAR slot (0x00050008) not checked — same reason as above.

    | Load direction: decode coverage only (must not F-line).
    move.l  #0xDDDD1111, 0x00050100
    move.l  #0xDDDD2222, 0x00050104
    move.l  #0xDDDD3333, 0x00050108
    .short  0xF239, 0x9C00, 0x0005, 0x0100   | FMOVEM.L (0x00050100).L,FPCR/FPSR/FPIAR

    | ── PASS ─────────────────────────────────────────────────────────
    lea     PASS_SENT, %a2
    move.l  #0xC0FFEE00, %d2
    move.l  %d2, (%a2)
_halt:
    bra     _halt

_fail_20_01: move.l #0xDEAD2001, %d2 ; bra _fail_common
_fail_20_05: move.l #0xDEAD2005, %d2 ; bra _fail_common
_fail_20_06: move.l #0xDEAD2006, %d2 ; bra _fail_common
_fail_20_07: move.l #0xDEAD2007, %d2 ; bra _fail_common
_fail_20_09: move.l #0xDEAD2009, %d2 ; bra _fail_common
_fail_20_13: move.l #0xDEAD200D, %d2 ; bra _fail_common
_fail_20_14: move.l #0xDEAD200E, %d2 ; bra _fail_common
_fail_20_19: move.l #0xDEAD2013, %d2 ; bra _fail_common
_fail_20_20: move.l #0xDEAD2014, %d2 ; bra _fail_common

_fline:
    move.l  #0xDEAD2000, %d2
    bra     _fail_common

_fail_common:
    lea     PASS_SENT, %a2
    move.l  %d2, (%a2)
_halt_fail:
    bra     _halt_fail
