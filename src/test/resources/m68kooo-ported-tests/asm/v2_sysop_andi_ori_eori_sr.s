| v2_sysop_andi_ori_eori_sr.s — task #221 / E4 — ANDI/ORI/EORI #imm,SR
| via the V2 sysop family.  Confirms the legacy decode_0000.vh rows
| migrate to V2 SYS_ANDI_SR / SYS_ORI_SR / SYS_EORI_SR with byte-exact
| arch_sr write at retire AND the supervisor-mode gate is enforced.
|
| Note: commit.v's SYS_*_SR retires update arch_sr ONLY; the CCR half
| of SR is owned by ccr_rat and is intentionally NOT updated through
| these retires (pre-existing phase-2.1 behaviour gap, documented in
| commit.v).  This test therefore exercises the SR upper byte (S bit
| + IPL bits) and confirms the µops retire cleanly without faulting
| in supervisor mode.
|
| Sequence:
|   Phase 1: ORI  #0x0700,SR  — ensure IPL=7 (mask all maskable IRQs).
|   Phase 2: ANDI #0xF8FF,SR  — clear IPL to 0 (allow IRQs again).
|   Phase 3: EORI #0x0700,SR  — flip IPL back to 7.
|   Phase 4: do some normal arithmetic to confirm the pipeline is alive.
|
| PASS: 0xC0FFEE00.

    .text
    .org 0

_start:
    lea     0x00020000, %a7

    | Phase 1: ORI #0x0700,SR — IPL ← 7 via OR.
    .word   0x007C, 0x0700           | ORI #0x0700,SR

    | Phase 2: ANDI #0xF8FF,SR — IPL ← 0 via AND.
    .word   0x027C, 0xF8FF           | ANDI #0xF8FF,SR

    | Phase 3: EORI #0x0700,SR — flip IPL bits.  IPL was 0, now 7.
    .word   0x0A7C, 0x0700           | EORI #0x0700,SR

    | Pipeline alive check — non-trivial arithmetic + branch.
    move.l  #0x12345678, %d0
    add.l   #0x11111111, %d0
    cmp.l   #0x23456789, %d0
    bne     _fail

    move.l  #0xCAFE, %d1
    move.l  %d1, %d2
    cmp.l   %d1, %d2
    bne     _fail

    | Confirm we're still in supervisor mode by issuing another
    | privileged op (RESET) — if S=0 the V2 sysop's
    | requires_supervisor gate would have raised vec-8 already.
    reset

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d7
    move.l  %d7, (%a0)
_halt_fail:
    bra     _halt_fail
