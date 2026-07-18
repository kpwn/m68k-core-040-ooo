| bchg_mem_ccr_preserve.s — BCHG memory-form must preserve N/V/C/X per PRM §4.15.
|
| Repro of SST BCHG.sstpack test #2 finding (2026-05-23): BCHG D7,-(A0) cleared
| both V (preserve violation) AND Z (computed wrong) when init CCR=NV.
|
| BCLR (9/10 SST pass) and BSET (10/10 SST pass) work; BCHG (5/10) is the
| outlier — likely a flags_in / merge-mask issue specific to the memory crack.
|
| 2026-07-02: this repro had its own authoring bug — `moveq #0,%d0` (used
| to set the bit-index register) was placed AFTER the `move.w #0x000a,%ccr`
| init.  MOVEQ unconditionally clears V and C, so it clobbered V before
| BCHG ever executed; BCHG then correctly preserved the already-clobbered
| flags.  Verified bit-for-bit against Musashi golden (tb/models/musashi_run)
| with the original instruction order: both RTL and Musashi produce
| CCR=0x04, not the 0x0e this test expected — i.e. this test as originally
| written was not actually exercising the SST finding.  Fixed by moving the
| D0 setup before the CCR init (matching bchg_ccr_step.s's steps 2/3
| pattern) so BCHG's own preserve/compute behaviour is what's under test.
| The RTL's memory-form BCHG CCR handling was already correct; no RTL
| change was needed for this test's failure.
|
| PASS: 0xC0FFEE00 at 0xFFFF0000.

    .text
    .org 0

_start:
    lea     0x00010000, %a7              | supervisor stack

    | Seed a known byte at 0x00020000: 0x00 (bit 0 clear).
    move.b  #0x00, 0x00020000

    | BCHG #0, 0x00020001     (test+toggle bit 0 of byte at 0x00020001)
    | Actually use a register-source bit number:
    |   D0 = 0  → bit number 0 (modulo 8)
    | NOTE: MOVEQ clears V/C, so it must run BEFORE the CCR init below —
    | otherwise it clobbers the very flags this test checks BCHG preserves.
    moveq   #0, %d0
    lea     0x00020001, %a0              | A0 points one byte past target

    | Set CCR = NV (bits 3,1 set) via MOVE to CCR.
    move.w  #0x000a, %ccr

    bchg    %d0, -(%a0)                  | -(A0)=0x00020000, byte was 0
    | Per PRM: Z = NOT(old bit) = NOT(0) = 1; N/V/C/X unchanged.
    | Expected CCR = N | V | Z = 0x0e.

    | Verify CCR:
    move.w  %sr, %d1
    and.l   #0x1f, %d1                    | CCR-only bits
    cmp.l   #0x0e, %d1
    bne     _fail

    | Verify byte was toggled to 0x01.
    move.b  0x00020000, %d2
    cmp.b   #0x01, %d2
    bne     _fail

    | PASS sentinel.
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
_halt_fail:
    bra     _halt_fail
