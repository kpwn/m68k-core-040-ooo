| fpu_fmovem_ctrl_predec.s — FMOVEM.L control reg list (FPCR/FPSR/FPIAR)
|                            through -(An) [predecrement] EA mode, both
|                            directions.
|
| Validates the FPSP self-recursion fix: decode_1111.vh's
| fmoveml_ctrl_ea block previously only decoded (d16,An); predecrement
| (mode=100) fell through to the F-line trap.  The Q700 ROM's own
| vector-11 handler prologue uses exactly
| `FMOVEM.L FPIAR/FPSR/FPCR,-(A7)` as its first instruction, so every
| F-line trap re-entered the same broken handler forever (see
| project_fmovem_ctrl_predec_selfrecursion_2026_07_23 memory).  See
| fpu_fpsp_selfrecursion_repro.s for the actual re-entrant-trap repro;
| this test validates the instruction's data-plane correctness in
| isolation: exact memory layout, SP/An arithmetic, and register
| round-trip, cross-checked against this repo's vendored Musashi
| (tb/models/musashi/m68kfpu.c fmove_fpcr(), empirically probed
| 2026-07-23 via tb/models/musashi_run — see decode_1111.vh's
| fmoveml_ctrl_ea header comment for the full derivation).
|
| Musashi's WRITE_EA_32/READ_EA_32 case 4 (-(An)) decrements An by 4
| *before each individual present register's* access, still walking
| FPCR,FPSR,FPIAR in that fixed order — net effect vs. a single
| upfront "An -= 4*popcount": identical final An, but memory packing
| is the *reverse* of the (d16,An)/postincrement case: the
| last-accessed present register (FPIAR if present) ends up at the
| LOWEST address (the final An), not the first.
|
| Part A: store direction (ctrl->mem).  A0=0x00020020, popcount=3
|   (all three), so final A0 = 0x00020020-12 = 0x00020014.  Expect:
|     mem[0x00020014] = FPIAR (0 — never programmed, reset default)
|     mem[0x00020018] = FPSR  (0xBBBBBBBB)
|     mem[0x0002001C] = FPCR  (0xAAAAAAAA)
|
| Part B: load direction (mem->ctrl) address arithmetic ONLY.
|   Confirmed-separate, pre-existing bug found while building this
|   test (2026-07-23, see below) means the load direction's exact
|   *register content* round-trip cannot be asserted yet — so Part B
|   here deliberately checks only the An predecrement side effect
|   (which does NOT go through the broken path — see below), not
|   FPCR/FPSR/FPIAR values.
|
| ⚠ KNOWN SEPARATE BUG (found 2026-07-23, NOT fixed by this landing,
|   NOT specific to any EA mode added here): rob.v line ~1106-1107
|   unconditionally marks every UOP_SYS entry `e_complete` at DISPATCH
|   time ("UOP_SYS entries arrive at the ROB already complete" per
|   m68k_core_memory.vh's own comment) — correct for the SYS opcodes
|   that comment enumerates (STOP/RESET/RTE-stub/MOVEC-stub/ANDI-ORI-
|   EORI-to-SR/MOVE-USP, all sourced from an already-resident Dn/An or
|   no source at all), but WRONG for SYS_FMOVE_FPCR_WR / _FPSR_WR
|   (opcodes 22/23) when their has_src_a operand is fed by a LOAD
|   still in flight — exactly the FMOVEM control-list crack's
|   mem→ctrl direction (LOAD into TMP1, then SYS_WR consumes TMP1).
|   Empirically confirmed via tb/models/musashi_run-style probes
|   AND direct RTL dispatch/CDB tracing (`make test DEBUG=1`) that:
|     - address computation is 100% correct for every phase/mode here
|       (verified by inspecting the actual LSU-issued EA per phase);
|     - the LOAD's data itself arrives correctly on the CDB;
|     - but SYS_FMOVE_FPCR_WR / _FPSR_WR's `rob_src_a_val` (read as
|       `PRF[rob_phys_src_a]` combinationally, commit.v ~line 4414)
|       ends up 0 instead of the loaded value, because `e_complete`
|       for that entry was already 1 from the moment it was dispatched
|       — commit doesn't wait for the actual producer.
|   Confirmed this is 100% pre-existing (not introduced by -(An) or
|   any other EA mode landed in this pass): the ALREADY-SHIPPED
|   (d16,An) load-direction path exhibits the identical symptom with a
|   freshly-touched (not address-reused) buffer — the existing
|   fpu_fmovem_ctrl_reg.s test never caught it only because it happens
|   to reuse the very address its own preceding FMOVEM *store* just
|   wrote, and that specific timing happens not to trigger the race.
|   SYS_FMOVE_FPIAR_WR (opcode 29) does not use the `rob_has_src_a ?
|   rob_src_a_val : rob_imm` ternary and was *initially* suspected
|   immune, but a further single-register isolation probe showed it
|   has the identical bug — the earlier "FPIAR always survives"
|   observation was incidental crack-timing luck (FPIAR is processed
|   last, giving its producer more elapsed cycles), not a real
|   distinction.  Filed as a dedicated follow-up — this is a ROB/
|   commit.v completion-tracking bug, unrelated to decode_1111.vh, and
|   deliberately NOT fixed in this landing (out of scope, and commit.v/
|   rob.v completion tracking is exactly the kind of load-bearing,
|   easy-to-regress area this project's docs flag as high-risk to
|   touch without a dedicated investigation).
|
| PASS sentinel: 0xC0FFEE00.
| FAIL sentinels:
|   0xDEAD1001 — vec-11 F-line fired (decoder didn't recognise -(An))
|   0xDEAD1002 — store: final A0 wrong
|   0xDEAD1003 — store: mem[final+0] (FPIAR slot) wrong
|   0xDEAD1004 — store: mem[final+4] (FPSR slot) wrong
|   0xDEAD1005 — store: mem[final+8] (FPCR slot) wrong
|   0xDEAD1006 — load: final A0 wrong (address arithmetic — NOT
|                subject to the known bug above)

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000

_start:
    lea     0x00010000, %a7
    move.l  #_fline, 0x0000002C        | vec 11 F-line — must NOT fire

    | ── Part A: store direction, -(An) ──────────────────────────────
    move.l  #0xAAAAAAAA, %d0
    .short  0xF200, 0x9000             | FMOVE.L D0,FPCR
    move.l  #0xBBBBBBBB, %d0
    .short  0xF200, 0x8800             | FMOVE.L D0,FPSR
    | FPIAR left at reset default (0) — no single-reg write opcode.

    lea     0x00020020, %a0
    .short  0xF220, 0xBC00             | FMOVEM.L FPCR/FPSR/FPIAR,-(A0)

    cmp.l   #0x00020014, %a0
    bne     _fail_a0_store

    move.l  0x00020014, %d1           | FPIAR slot (final+0)
    cmp.l   #0x00000000, %d1
    bne     _fail_fpiar_store

    move.l  0x00020018, %d1           | FPSR slot (final+4)
    cmp.l   #0xBBBBBBBB, %d1
    bne     _fail_fpsr_store

    move.l  0x0002001C, %d1           | FPCR slot (final+8)
    cmp.l   #0xAAAAAAAA, %d1
    bne     _fail_fpcr_store

    | ── Part B: load direction, -(An), address arithmetic only ──────
    | (register-content round-trip NOT asserted here — see the
    | KNOWN SEPARATE BUG note above; this checks only the predecrement
    | side effect on A0, which is computed by a plain INT ALU_SUB µop
    | and is unaffected by that bug.)
    move.l  #0x11111111, 0x00020034
    move.l  #0x22222222, 0x00020038
    move.l  #0x33333333, 0x0002003C

    lea     0x00020040, %a0
    .short  0xF220, 0x9C00             | FMOVEM.L -(A0),FPCR/FPSR/FPIAR

    cmp.l   #0x00020034, %a0
    bne     _fail_a0_load

    | ── PASS ─────────────────────────────────────────────────────────
    lea     PASS_SENT, %a2
    move.l  #0xC0FFEE00, %d2
    move.l  %d2, (%a2)
_halt:
    bra     _halt

_fail_a0_store:
    move.l  #0xDEAD1002, %d2
    bra     _fail_common
_fail_fpiar_store:
    move.l  #0xDEAD1003, %d2
    bra     _fail_common
_fail_fpsr_store:
    move.l  #0xDEAD1004, %d2
    bra     _fail_common
_fail_fpcr_store:
    move.l  #0xDEAD1005, %d2
    bra     _fail_common
_fail_a0_load:
    move.l  #0xDEAD1006, %d2
    bra     _fail_common

_fline:
    move.l  #0xDEAD1001, %d2
    bra     _fail_common

_fail_common:
    lea     PASS_SENT, %a2
    move.l  %d2, (%a2)
_halt_fail:
    bra     _halt_fail
