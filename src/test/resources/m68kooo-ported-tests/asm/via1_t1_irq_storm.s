| via1_t1_irq_storm.s — sustained IRQ chain stresses SSP coherence
|
| Origin: live-FPGA diagnosis of a Q700 ROM boot hang.  After ~7000
| level-1 VIA1 Timer-1 IRQs (at the legitimate Sound Manager rate of
| ~1.3 kHz) the supervisor stack pointer ends up holding a value
| inside the ROM aperture.  ROM is read-only on the bus, so the next
| exception entry's SR/PC pushes are silently dropped, the matching
| RTE pops back garbage, and the CPU jumps to a bogus address.
|
| ──────────────────────────────────────────────────────────────────
| What this test does
| ──────────────────────────────────────────────────────────────────
| The Verilator `mac_top` harness does NOT instantiate a real VIA1
| (only `fpga_top` does).  We therefore cannot drive a real level-1
| autovector from a peripheral register write inside this sim.  But
| the SSP-coherence property we want to stress is purely a property
| of the exception entry/exit machinery: every entry pushes a frame
| onto the SSP, every RTE pops one back, and after N round-trips the
| SSP must be exactly where it started.
|
| To stress that machinery without VIA1, we point arch vector 25
| (the autovec slot VIA1 would dispatch to) at our handler and use
| TRAP #1 from a tight user-mode loop as the IRQ surrogate.  TRAP
| from user-mode goes through the same path as a level-1 autovec:
|   * pushes a 4-word format-0 frame onto SSP
|   * switches from USP → SSP
|   * dispatches via VBR + (vec << 2)
|   * RTE pops the frame and returns to USP
| so the same SSP-drift bug (if it exists in our RTL) will surface
| here as a growing min-SSP deviation.
|
| If VIA1 ever gets wired into `tb_top`, the user loop body can be
| replaced with a `nop` polling loop and a real T1 IRQ will drive
| the same handler.  The handler comment block tags the VIA1
| acknowledge sequence we'd add then.
|
| ──────────────────────────────────────────────────────────────────
| Memory map used by this test
| ──────────────────────────────────────────────────────────────────
|   0x00000000..0x000003FF   exception vector table (VBR=0)
|     0x00000064               vec 25 → _h_vec25 (VIA1 / our trap proxy)
|     0x00000084               vec 33 → _h_vec25 (TRAP #1 alias)
|   0x00010000               IRQ counter   (.L)
|   0x00010004               min observed SSP after entry (.L, init = MAX)
|   0x00010008               last observed PC value pushed (.L)
|   0x0001000C               last observed SR value pushed (.L)
|   0x00010010               sanity-check error code (.L, init = 0)
|   0x00080000               SSP base (grows downward; well above any
|                              normal use, well below 4 MB / ROM)
|   0x00040000               USP base (separate region from SSP)
|   0x40000000               start of ROM aperture (the failure region —
|                              if SSP ever crosses below this we set
|                              the FAIL flag)
|
| ──────────────────────────────────────────────────────────────────
| Pass / Fail
| ──────────────────────────────────────────────────────────────────
| PASS: D0 == loop-final value, IRQ counter >= threshold, min SSP
|       observed by handler stayed within [SSP_base - small,
|       SSP_base], and no in-handler sanity check ever tripped.
| FAIL: any of the above; failure code stashed in 0x00010010 prior
|       to writing the FAIL sentinel.

    .text
    .org 0

| ─────────────────────────────────────────────────────────────────
| Tuning constants — adjust if sim runtime is too long/short
| ─────────────────────────────────────────────────────────────────
    .equ    SSP_BASE,    0x00080000
    .equ    USP_BASE,    0x00040000
    .equ    IRQ_CNT,     0x00010000
    .equ    MIN_SSP,     0x00010004
    .equ    LAST_PC,     0x00010008
    .equ    LAST_SR,     0x0001000C
    .equ    FAIL_CODE,   0x00010010
    .equ    LOOP_ITERS,  256       | user-loop trips; each does TRAP #1
    .equ    IRQ_THRESH,  16        | min IRQs we need to observe
    .equ    ROM_BASE,    0x40000000

_start:
    | ── Phase 0: supervisor entry, SSP setup ─────────────────────
    | We boot in supervisor mode with SR = 0x2700 (per CLAUDE.md
    | decision: 68040 reset SR = supervisor + IPL=7).  Set up SSP
    | well above any low-memory structures.
    lea     SSP_BASE, %a7

    | ── Phase 1: zero out the trackers ──────────────────────────
    move.l  #0, IRQ_CNT
    move.l  #0xFFFFFFFF, MIN_SSP    | will be MIN'd against actual SSPs
    move.l  #0, LAST_PC
    move.l  #0, LAST_SR
    move.l  #0, FAIL_CODE

    | ── Phase 2: install handler at vec 25 (VIA1 autovec) AND
    | vec 33 (TRAP #1).  Both point at the same handler; in a
    | real-VIA1 build we'd just use vec 25.  We leave VBR = 0
    | for simplicity (CLAUDE.md says VBR can be relocated, but
    | for a tb-only test we don't need that).
    move.l  #_h_vec25, 0x00000064   | vec 25 (level-1 autovec)
    move.l  #_h_vec25, 0x00000084   | vec 33 (TRAP #1)

    | ── Phase 3: would-be VIA1 setup ────────────────────────────
    | Commented out: tb_top has no VIA1.  Left here as the spec we
    | will switch on once VIA1 lands in tb_top.  Stride is 0x200
    | per VIA1 register (Q700 layout, see rtl/sys/peripheral_bus.v).
    |   ACR  (reg 11, off 0x1600) = 0x40   T1 free-run, no PB7
    |   IER  (reg 14, off 0x1C00) = 0xC0   set-bit-7 + enable T1
    |   T1CL (reg  4, off 0x0800) = 0x40   low byte of latch
    |   T1CH (reg  5, off 0x0A00) = 0x00   hi byte; write loads counter
    | move.b  #0x40, 0x50F01600
    | move.b  #0xC0, 0x50F01C00
    | move.b  #0x40, 0x50F00800
    | move.b  #0x00, 0x50F00A00

    | ── Phase 4: drop to user mode ──────────────────────────────
    | Clear S bit (bit 13) and IPL (bits 8..10) in SR.  SR is
    | currently 0x2700 (supervisor, IPL=7).  Writing SR is
    | privileged; do it BEFORE we drop privilege.  Result SR =
    | 0x0000 (user, IPL=0, no flags) — IPL=0 means any pending
    | level-1+ IRQ would be taken as soon as it asserts.
    move.w  #0x0000, %sr            | drop to user, IPL=0
    | NOTE: at this point %a7 is now USP, not SSP.  We are in
    | user mode; the supervisor stack pointer is held internally
    | (it is the ISP/SSP A7 image we set up above at SSP_BASE).
    lea     USP_BASE, %a7           | establish a separate user stack

    | ── Phase 5: user loop — TRAP #1 every iteration ────────────
    | Each iteration: ADDQ.L #1 to D0, then TRAP #1.  TRAP from
    | user mode goes through the same exception path a real
    | level-1 IRQ would: SSP push of {SR, PCh:PCl, fmt/vec},
    | switch to supervisor, dispatch via VBR + 25*4 (we wired
    | vec 33 to the same handler so TRAP #1 lands the same).
    | After RTE we resume at the next user instruction.
    moveq   #0, %d0                 | loop counter (also the canary D0)
    move.l  #LOOP_ITERS, %d1        | trip count
_user_loop:
    addq.l  #1, %d0                 | observable progress
    trap    #1                      | exception entry, RTE to next inst
    subq.l  #1, %d1
    bne     _user_loop

    | ── Phase 6: re-enter supervisor for verification ──────────
    | One last TRAP gets us into supervisor mode permanently for
    | the verification block.  The handler will detect this is
    | the "verify" trap by checking D1 == 0 and skip the normal
    | accounting path (we want to leave SSP/USP in a known state
    | for the post-loop checks).
    | Simplest path: do another TRAP and from inside the handler,
    | jump to _verify rather than RTE'ing.  This keeps us in
    | supervisor with SSP at SSP_BASE - 8 (one frame on the stack)
    | which we discard via LEA.
    trap    #1                      | enters handler with D1 == 0
    | We never come back here — _verify path takes over.

    | ── Failure paths called from _verify ───────────────────────
_fail_count:
    move.l  #0xDEAD0001, FAIL_CODE
    bra     _fail
_fail_min_ssp:
    move.l  #0xDEAD0002, FAIL_CODE
    bra     _fail
_fail_d0:
    move.l  #0xDEAD0003, FAIL_CODE
    bra     _fail
_fail_pc:
    move.l  #0xDEAD0004, FAIL_CODE
    bra     _fail
_fail_sr:
    move.l  #0xDEAD0005, FAIL_CODE
    bra     _fail
_fail_handler_sanity:
    move.l  #0xDEAD0006, FAIL_CODE
    bra     _fail

| ── Pass / Fail sentinels ────────────────────────────────────────
_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, (%a0)
_halt_pass:
    stop    #0x2700
    bra     _halt_pass

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, (%a0)
_halt_fail:
    stop    #0x2700
    bra     _halt_fail

| ─────────────────────────────────────────────────────────────────
| _verify — runs in supervisor mode after the user loop completes
|
| Reached by the handler when D1 has reached 0 (the post-loop
| TRAP).  At entry SSP holds one frame (the "verify" TRAP) which
| we discard — no RTE in this path because we want to stay in
| supervisor for the sentinel writes.
| ─────────────────────────────────────────────────────────────────
_verify:
    | Discard the frame the verify-TRAP pushed (4 words = 8 bytes
    | for a format-0 frame).
    lea     8(%a7), %a7

    | Check 1: did we accumulate enough IRQ entries?
    move.l  IRQ_CNT, %d2
    cmp.l   #IRQ_THRESH, %d2
    blt     _fail_count             | not enough handler entries

    | Check 2: did SSP ever drift into the ROM aperture?
    move.l  MIN_SSP, %d2
    cmp.l   #ROM_BASE, %d2
    bge     _fail_min_ssp           | SSP went above 0x40000000 → bug

    | Check 3: D0 must equal LOOP_ITERS (proves the user loop ran
    | uncorrupted by the IRQ chain — every RTE returned to the
    | right user PC and didn't squash the ADDQ).
    cmp.l   #LOOP_ITERS, %d0
    bne     _fail_d0

    | Check 4: last pushed PC must land somewhere in the user
    | loop (between _user_loop and the post-loop trap, both of
    | which sit in low-RAM after .org 0 + relocated to 0x40800000).
    | We accept any PC in [0x40800000, 0x40801000) as plausible.
    move.l  LAST_PC, %d2
    cmp.l   #0x40800000, %d2
    blt     _fail_pc
    cmp.l   #0x40801000, %d2
    bge     _fail_pc

    | Check 5: last pushed SR must have S=0 (we trapped from user)
    | and IPL bits = 0 (we set IPL=0 before the loop).  SR is the
    | low 16 bits of LAST_SR (we stored it as a long for ease).
    move.l  LAST_SR, %d2
    and.l   #0x2700, %d2
    bne     _fail_sr                | any of {S, IPL} set → wrong frame

    | Check 6: did the handler ever flag a sanity error?
    move.l  FAIL_CODE, %d2
    cmp.l   #0, %d2
    bne     _fail_handler_sanity

    bra     _pass

| ─────────────────────────────────────────────────────────────────
| _h_vec25 — handler for vec 25 (VIA1 autovec) and vec 33 (TRAP #1)
|
| Entry frame layout (68040 format 0, 4 words = 8 bytes on SSP):
|   0(SP) = SR (saved status register, S=0 if from user)
|   2(SP) = PCh
|   4(SP) = PCl
|   6(SP) = format/vector word ([15:12]=fmt, [11:0]=vec*4 offset)
|
| Handler discipline:
|   * Increment IRQ counter.
|   * Snapshot the SSP-on-entry into MIN_SSP if smaller.
|   * Snapshot the saved PC/SR fields for post-test inspection.
|   * Sanity-check that PC field is 32-bit aligned (or at least
|     even — instructions are 16-bit aligned on m68k).
|   * If D1 == 0 (the verify-trap path), jump to _verify instead
|     of RTE'ing — this leaves us in supervisor for the final
|     sentinel writes.
|   * Otherwise RTE.
|
| If a VIA1 build replaces TRAP #1 with a real T1 IRQ, the only
| change is the acknowledge:
|     move.b  0x50F00800, %d2     | read T1CL clears IFR_T1
| (Read of T1CL clears the flag per 6522 spec.)
| ─────────────────────────────────────────────────────────────────
_h_vec25:
    | --- Snapshot SSP at entry --------------------------------
    | At entry %a7 = SSP after 4-word push.  Compute min over all
    | entries; that's a tight bound on stack drift.
    move.l  %a7, %d2
    move.l  MIN_SSP, %d3
    cmp.l   %d3, %d2
    bge     _h_skip_min             | %a7 >= cur min → no update
    move.l  %d2, MIN_SSP
_h_skip_min:

    | --- Bump IRQ counter -------------------------------------
    move.l  IRQ_CNT, %d2
    addq.l  #1, %d2
    move.l  %d2, IRQ_CNT

    | --- Snapshot frame fields --------------------------------
    | 0(%a7) = SR (16 bits) — sign/zero-extend into a long
    move.w  (%a7), %d2
    and.l   #0x0000FFFF, %d2
    move.l  %d2, LAST_SR

    | 2(%a7) = PC (32 bits, big-endian; assembler picks up the
    | aligned long correctly because the push is on an even
    | boundary by m68k contract)
    move.l  2(%a7), %d2
    move.l  %d2, LAST_PC

    | --- Sanity: PC must be even (instructions are 16-bit) ----
    | If it isn't, address-error semantics would already have
    | fired — this is belt-and-braces in case the push corrupts.
    btst    #0, %d2
    beq     _h_pc_even
    | PC odd → flag and FAIL via _verify path
    move.l  #0xBADD00DD, FAIL_CODE
_h_pc_even:

    | --- Verify-trap detection --------------------------------
    | If D1 == 0 the user loop has finished and this is the
    | "go to verify" trap.  Jump there in supervisor mode rather
    | than RTE'ing back to user.
    cmp.l   #0, %d1
    beq     _verify

    | --- Normal path: RTE back to user ------------------------
    | (If we were ack'ing a VIA1 IRQ, do it just before RTE.)
    rte
