| fpu_fmovem_x_pcdi.s — FMOVEM.X (d16,PC),<FP list> must DECODE, and the
|                       FP-list -> (d16,PC) direction must keep TRAPPING.
|
| THE BUG THIS LOCKS IN (task #219)
| ------------------------------------------------------------------------
| decode_1111.vh's fmovemx_mem_ea crack accepted EA modes 010 ((An)),
| 011 ((An)+), 100 (-(An)), 101 ((d16,An)) and 110 ((d8,An,Xn)) -- there
| was no mode-111 arm at all, so (d16,PC) fell through to the F-line trap.
| The Q700 universal ROM executes it at four sites, ALL inside the ROM's
| own FPSP (floating-point support package):
|
|     4089097C   F23A D080     fmovem.x (d16,PC),fp0
|     4089098A   F23A D080     fmovem.x (d16,PC),fp0
|     408909C6   F23A D080     fmovem.x (d16,PC),fp0
|     4089142A   F23A D040     fmovem.x (d16,PC),fp1
|
| An undecoded instruction inside the F-line handler makes that handler
| re-execute the very instruction that trapped: an unbreakable
| self-recursion.  That is the same failure mode already recorded for
| FMOVEM.L (d16,PC) (tb/tests/asm/fmovem_ctrl_pcdi_load.s, measured on
| hardware at ~24,000 exceptions/second) and for FMOVEM.X (An)
| (tb/tests/asm/fpu_fmovem_x_an_indirect.s).
|
| WHY THE ENCODINGS ARE HAND-ASSEMBLED: gas will not assemble
| "fmovem.x (d,%pc),%fp0" at all, and objdump renders the ROM bytes as a
| bare ".short 0xf23a" -- which is exactly why these four sites were
| invisible to a plain disassembly scan for so long.  The .short forms
| below are the ROM's literal bytes.
|
| WHAT THIS TEST CAN AND CANNOT ASSERT -- READ BEFORE EXTENDING
| ------------------------------------------------------------------------
| The FMOVEM.X crack is a memory-traffic-only bridge: the load direction
| reads 12 bytes per listed register and DISCARDS them into a hidden
| scratch register (it does NOT write the FP register file), and the
| store direction writes 12 bytes of ZEROS per listed register (it does
| NOT read the FP register file).  See the fmovemx_mem_ea header comment
| in decode_1111.vh and tb/tests/asm/fpu_fmovem_x_an_indirect.s, which
| pins the same model for the other EA modes.
|
| Consequently a load's EFFECTIVE ADDRESS is not observable from software
| here: the data goes nowhere, and the mac_top harness has no bus-error
| region, so a base computed as pd_pc+2 instead of the correct pd_pc+4
| would look identical to this test.  The +4 rule (the displacement base
| is the address of the disp16 word itself -- past the opword AND the
| register-list word) is therefore pinned in tb/tb_decode_fpu.cpp
| (expect_fmovemx_pcdi), which reads the emitted uop imm straight out of
| decode.v.  Both checkers were RED-verified against a deliberately
| broken +2 base.  Do not add an address assertion here believing it
| works -- it cannot.
|
| What this file DOES assert:
|   * the load form decodes and retires instead of trapping F-line;
|   * it is 6 bytes long (opword + list word + disp16);
|   * it writes nothing to memory;
|   * it leaves the integer register file alone -- notably A2, since
|     op[2:0] is 3'b010 for this mode and a decoder that treated the
|     mode-7 sub-mode selector as an An index would pick A2;
|   * the STORE direction to a PC-relative EA still traps F-line;
|   * a DYNAMIC register list with (d16,PC) still traps F-line.
|
| THE LENGTH DETECTOR: the disp16 word is written as the literal 0x4AFC,
| which is simultaneously (a) a +19196 displacement landing in plain RAM,
| where the discarded read is harmless, and (b) the ILLEGAL opword.  If
| decode reported a 4-byte length the PC would land on that word and
| execute ILLEGAL -> vector 4 -> a distinct sentinel, instead of the
| indistinguishable timeout a garbage displacement would produce.
|
| PASS sentinel: 0xC0FFEE00
| FAIL sentinels:
|   0xDEAD19F1 — a POSITIVE stage trapped F-line: the decode gap is back
|   0xDEAD19F2 — F-line handler reached in an impossible stage
|   0xDEAD1904 — vector 4 ILLEGAL: instruction length was not 6 bytes
|   0xDEAD1901 — single-reg load direction WROTE to memory
|   0xDEAD1902 — single-reg load clobbered an integer register
|   0xDEAD1903 — multi-reg (fp0-fp3) load direction WROTE to memory
|   0xDEAD1905 — STORE direction to (d16,PC) did NOT trap
|   0xDEAD1906 — DYNAMIC register list with (d16,PC) did NOT trap
|   0xDEAD1907 — STORE direction did not trap AND wrote memory

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000
    .equ STACK_TOP, 0x00010000

_start:
    lea     STACK_TOP, %a7
    move.l  #_fline,   0x0000002C      | vec 11 F-line
    move.l  #_illegal, 0x00000010      | vec  4 ILLEGAL (length detector)
    moveq   #0, %d7                    | stage witness

| ── Stage 1: FMOVEM.X (d16,PC),FP0 — the exact ROM encoding ─────────
| Seed the 12-byte slot the load will read, plus guards either side, so
| a phantom WRITE (wrong uop type, or a store-direction mix-up) is
| caught.  The EA is (address of the disp16 word) + 0x4AFC.

    lea     i1+4, %a0
    lea     0x4AFC(%a0), %a0           | %a0 = the load's effective address
    move.l  #0x11111111, 0(%a0)
    move.l  #0x22222222, 4(%a0)
    move.l  #0x33333333, 8(%a0)
    move.l  #0xCAFEBABE, 12(%a0)       | guard above the slot
    move.l  #0xCAFEBABE, -4(%a0)       | guard below the slot

    | Witness registers: an implementation that used op[2:0] as an An
    | index would pick A2, and one that emitted a writeback phase would
    | move it.  D6 is the length probe (see header).
    movea.l #0x0BADA550, %a2
    moveq   #0, %d6
i1:
    .short  0xF23A, 0xD080             | fmovem.x (0x4AFC,PC),fp0
    .short  0x4AFC                     | disp16 -- also ILLEGAL if len==4
    moveq   #0x5A, %d6                 | reached only if len was 6

    cmp.l   #0x5A, %d6
    bne     _fail_len
    cmpa.l  #0x0BADA550, %a2
    bne     _fail_s1_regs
    move.l  0(%a0), %d0
    cmp.l   #0x11111111, %d0
    bne     _fail_s1_wrote
    move.l  4(%a0), %d0
    cmp.l   #0x22222222, %d0
    bne     _fail_s1_wrote
    move.l  8(%a0), %d0
    cmp.l   #0x33333333, %d0
    bne     _fail_s1_wrote
    move.l  12(%a0), %d0
    cmp.l   #0xCAFEBABE, %d0
    bne     _fail_s1_wrote
    move.l  -4(%a0), %d0
    cmp.l   #0xCAFEBABE, %d0
    bne     _fail_s1_wrote

| ── Stage 2: FMOVEM.X (d16,PC),FP0-FP3 — four 12-byte slots ─────────
| Mask 0xF0.  48 bytes of traffic; nothing may be written.

    lea     i2+4, %a1
    lea     0x4AFC(%a1), %a1
    move.l  #0xDEADBEEF, %d0
    moveq   #0, %d1
_s2_fill:
    move.l  %d0, 0(%a1,%d1.w)
    addq.l  #4, %d1
    cmp.l   #48, %d1
    bne     _s2_fill
    move.l  #0xCAFEBABE, 48(%a1)
    move.l  #0xCAFEBABE, -4(%a1)

i2:
    .short  0xF23A, 0xD0F0             | fmovem.x (0x4AFC,PC),fp0-fp3
    .short  0x4AFC

    moveq   #0, %d1
_s2_check:
    move.l  0(%a1,%d1.w), %d0
    cmp.l   #0xDEADBEEF, %d0
    bne     _fail_s2_wrote
    addq.l  #4, %d1
    cmp.l   #48, %d1
    bne     _s2_check
    move.l  48(%a1), %d0
    cmp.l   #0xCAFEBABE, %d0
    bne     _fail_s2_wrote
    move.l  -4(%a1), %d0
    cmp.l   #0xCAFEBABE, %d0
    bne     _fail_s2_wrote

| ── Stage 3 (NEGATIVE): FMOVEM.X FP0,(d16,PC) must TRAP F-line ─────
| A PC-relative operand is not addressable as a destination.  ext1 =
| 0xF080 sets ext1[13] = 1 (FPn -> memory); the load form above uses
| 0xD080.  If this ever decodes, the store direction would write 12
| bytes of zeros at the EA -- so the guard below is a SECOND, independent
| detector that does not depend on the fall-through path being reached.
|
| The handler does not RTE: it reloads A7 (discarding the exception
| frame) and jumps to the resume address staged in A5.  That keeps this
| file independent of the exception stack-frame layout, which is not
| what is under test here.

    lea     i3+4, %a3
    lea     0x4AFC(%a3), %a3
    move.l  #0xC0DEC0DE, 0(%a3)
    move.l  #0xC0DEC0DE, 4(%a3)
    move.l  #0xC0DEC0DE, 8(%a3)

    moveq   #1, %d7                    | stage witness: expect trap #1
    lea     _s3_resume, %a5
i3:
    .short  0xF23A, 0xF080             | fmovem.x fp0,(0x4AFC,PC)
    .short  0x4AFC

    | Falling through means no trap fired.
    move.l  0(%a3), %d0
    cmp.l   #0xC0DEC0DE, %d0
    bne     _fail_s3_wrote
    bra     _fail_s3_notrap

_s3_resume:
    | Trap fired (handler set D7 = 2).  The guard must still be intact:
    | a trap that somehow also let the store phases run would be worse
    | than no trap at all.
    move.l  0(%a3), %d0
    cmp.l   #0xC0DEC0DE, %d0
    bne     _fail_s3_wrote
    move.l  8(%a3), %d0
    cmp.l   #0xC0DEC0DE, %d0
    bne     _fail_s3_wrote

| ── Stage 4 (NEGATIVE): dynamic register list must TRAP F-line ─────
| ext1 = 0xD810: bit 11 = 1 selects a dynamic list, whose mask lives in
| Dn at run time, so a decode-time crack cannot size the phase chain.
| ext1[7:0] is 0x10 (non-zero) on purpose: the crack's pre-existing
| "popcount >= 1" guard would NOT reject this, so only the new
| !ext1[11] term in the (d16,PC) arm can make it trap.  Without that
| term this stage falls through.

    lea     _s4_resume, %a5
i4:
    .short  0xF23A, 0xD810             | fmovem.x (0x4AFC,PC),%d1
    .short  0x4AFC

    bra     _fail_s4_notrap

_s4_resume:
| ── PASS ───────────────────────────────────────────────────────────
    lea     PASS_SENT, %a4
    move.l  #0xC0FFEE00, %d2
    move.l  %d2, (%a4)
_halt:
    bra     _halt

| ── F-line handler: longjmp dispatcher on the stage witness ────────
_fline:
    lea     STACK_TOP, %a7             | discard the exception frame
    cmp.l   #1, %d7
    beq     _fline_s3
    cmp.l   #2, %d7
    beq     _fline_s4
    | D7 == 0 -> a POSITIVE stage trapped: the decode gap is back.
    lea     PASS_SENT, %a4
    move.l  #0xDEAD19F1, %d2
    move.l  %d2, (%a4)
_hf0:
    bra     _hf0

_fline_s3:
    moveq   #2, %d7
    jmp     (%a5)

_fline_s4:
    moveq   #3, %d7
    jmp     (%a5)

_illegal:
    lea     PASS_SENT, %a4
    move.l  #0xDEAD1904, %d2
    move.l  %d2, (%a4)
_hi:
    bra     _hi

_fail_len:
    lea     PASS_SENT, %a4
    move.l  #0xDEAD1904, %d2
    move.l  %d2, (%a4)
_h0:
    bra     _h0

_fail_s1_wrote:
    lea     PASS_SENT, %a4
    move.l  #0xDEAD1901, %d2
    move.l  %d2, (%a4)
_h1:
    bra     _h1

_fail_s1_regs:
    lea     PASS_SENT, %a4
    move.l  #0xDEAD1902, %d2
    move.l  %d2, (%a4)
_h2:
    bra     _h2

_fail_s2_wrote:
    lea     PASS_SENT, %a4
    move.l  #0xDEAD1903, %d2
    move.l  %d2, (%a4)
_h3:
    bra     _h3

_fail_s3_notrap:
    lea     PASS_SENT, %a4
    move.l  #0xDEAD1905, %d2
    move.l  %d2, (%a4)
_h4:
    bra     _h4

_fail_s3_wrote:
    lea     PASS_SENT, %a4
    move.l  #0xDEAD1907, %d2
    move.l  %d2, (%a4)
_h5:
    bra     _h5

_fail_s4_notrap:
    lea     PASS_SENT, %a4
    move.l  #0xDEAD1906, %d2
    move.l  %d2, (%a4)
_h6:
    bra     _h6
