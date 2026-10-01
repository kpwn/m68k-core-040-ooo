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
| Current contract: static and dynamic PC-relative loads transfer FP data;
| PC-relative stores remain illegal. Dynamic admission follows the ratified
| 2026-09-18 dynamic-FMOVEM cold-path design.
|
| Stages 1/2 retain static-load instruction-length, integer-register and
| no-write checks. Stage 3 retains the illegal PC-relative store trap and
| memory guards. Stage 4 checks a runtime D1 mask selecting FP0 and FP3:
| exact data, unselected FP1/FP2, high mask bits ignored, D1/A2 preservation,
| correct PC displacement base, source image and guards, and instruction length.
|
| THE LENGTH DETECTOR: the disp16 word is written as the literal 0x4AFC,
| which is simultaneously (a) a +19196 displacement landing in plain RAM,
| and (b) the ILLEGAL opword. If
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
|   0xDEAD1906 — dynamic PC-relative load data/register/guard mismatch
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
| bytes at the EA -- so the guard below is a SECOND, independent
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

| ── Stage 4: dynamic PC-relative list uses the runtime D1 mask ─────
| D810 selects D1. Low-byte mask 0x90 selects FP0 and FP3 in control
| order; upper D1 bits must not affect the list. Start all FP0-FP3 at
| zero, then check the complete 48-byte result against an independent
| expected image. The displacement remains the ILLEGAL length sentinel.
    lea     0x00024000, %a0           | expected image
    moveq   #0, %d0
    moveq   #11, %d2
_s4_clear:
    move.l  %d0, (%a0)+
    dbf     %d2, _s4_clear
    lea     0x00024000, %a0
    fmovem.x (%a0), %fp0-%fp3
    move.l  #0x3FFF0000, 0(%a0)
    move.l  #0x80000001, 4(%a0)
    move.l  #0x00000011, 8(%a0)
    move.l  #0x40010000, 36(%a0)
    move.l  #0x90000003, 40(%a0)
    move.l  #0x00000033, 44(%a0)

    lea     i4+4, %a3
    lea     0x4AFC(%a3), %a3
    move.l  #0xCAFEBABE, -4(%a3)
    move.l  #0x3FFF0000, 0(%a3)
    move.l  #0x80000001, 4(%a3)
    move.l  #0x00000011, 8(%a3)
    move.l  #0x40010000, 12(%a3)
    move.l  #0x90000003, 16(%a3)
    move.l  #0x00000033, 20(%a3)
    move.l  #0xDEADBEEF, 24(%a3)
    lea     0x00123456, %a2
    move.l  #0xA5A50090, %d1
    moveq   #0, %d7                   | any F-line is now a failure
i4:
    .short  0xF23A, 0xD810            | fmovem.x (0x4AFC,PC),%d1
    .short  0x4AFC

    cmp.l   #0xA5A50090, %d1
    bne     _fail_s4_data
    cmpa.l  #0x00123456, %a2
    bne     _fail_s4_data
    lea     0x00024100, %a1
    fmovem.x %fp0-%fp3, (%a1)
    moveq   #0, %d2
_s4_compare:
    move.l  0(%a1,%d2.w), %d0
    cmp.l   0(%a0,%d2.w), %d0
    bne     _fail_s4_data
    addq.l  #4, %d2
    cmp.l   #48, %d2
    bne     _s4_compare
    | The source image must remain unchanged, including both guards.
    moveq   #0, %d2
_s4_source:
    move.l  0(%a3,%d2.w), %d0
    cmp.l   0(%a0,%d2.w), %d0
    bne     _fail_s4_data
    move.l  12(%a3,%d2.w), %d0
    cmp.l   36(%a0,%d2.w), %d0
    bne     _fail_s4_data
    addq.l  #4, %d2
    cmp.l   #12, %d2
    bne     _s4_source
    move.l  -4(%a3), %d0
    cmp.l   #0xCAFEBABE, %d0
    bne     _fail_s4_data
    move.l  24(%a3), %d0
    cmp.l   #0xDEADBEEF, %d0
    bne     _fail_s4_data

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
    | D7 == 0 -> a POSITIVE stage trapped: the decode gap is back.
    lea     PASS_SENT, %a4
    move.l  #0xDEAD19F1, %d2
    move.l  %d2, (%a4)
_hf0:
    bra     _hf0

_fline_s3:
    moveq   #2, %d7
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

_fail_s4_data:
    lea     PASS_SENT, %a4
    move.l  #0xDEAD1906, %d2
    move.l  %d2, (%a4)
_h6:
    bra     _h6
