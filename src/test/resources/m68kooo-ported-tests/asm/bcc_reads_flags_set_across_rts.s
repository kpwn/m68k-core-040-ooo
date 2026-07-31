| bcc_reads_flags_set_across_rts.s — a Bcc consuming flags set inside a
| subroutine, on the FIRST instruction after the RTS.
|
| WHY THIS EXISTS (2026-07-29, ioResult investigation)
| ------------------------------------------------------------------------
| rtl/core/m68k_core.v's own header flags this as delicate:
|
|     "CCR is held in commit.v and forwarded combinationally; uops do not
|      yet carry per-uop CCR snapshots, so flag-reading branches must wait
|      until commit catches up (handled implicitly by the flush path on
|      every Bcc that's read-after-write)."
|
| So correctness of a flag-reading Bcc rests entirely on the flush path
| covering the read-after-write case.  A Bcc that consumes flags produced
| INSIDE a callee, on the very first instruction after RTS, is a
| read-after-write across a control-transfer boundary — the shape most
| likely to escape that interlock.
|
| The live ROM site that motivated this (0x0002F76C):
|     0002F76C  jsr    $4080FF0A     ; callee sets flags
|     0002F772  bne.s  0x0002F7AE    ; consumes them immediately on return
| On hardware this branch behaved as if Z were SET while the architectural
| SR read back Z CLEAR.  That is either this hazard or a debug-snapshot
| artifact; this test isolates the CPU side.
|
| A PASS DOES NOT CLOSE THE HARDWARE QUESTION — it only shows the simple,
| cache-warm, no-interrupt form is correct.  It does not model an interrupt
| landing between the RTS and the Bcc, nor a callee deep enough to displace
| the CCR producer far from the return.
|
| PASS sentinel: 0xC0FFEE00
| FAIL sentinels:
|   0xDEADBC01 — Z=1 across RTS: BNE wrongly taken
|   0xDEADBC02 — Z=0 across RTS: BNE wrongly not taken
|   0xDEADBC03 — Z=1 across RTS: BEQ wrongly not taken
|   0xDEADBC04 — N=1 across RTS: BMI wrongly not taken
|   0xDEADBC05 — C=1 across RTS: BCS wrongly not taken
|   0xDEADBC06 — flags set 2 instrs before RTS: BNE wrong
|   0xDEADBC07 — CCR readback disagrees with the branch actually taken

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000

_start:
    lea     0x00010000, %a7
    move.w  #0x2000, %sr            | supervisor, IPL 0

    | ── Case 1: callee leaves Z=1; BNE on return must NOT be taken ──────
    bsr     set_z1
    bne     fail_c1                 | wrong: Z=1 so BNE must fall through

    | ── Case 2: callee leaves Z=0; BNE on return MUST be taken ─────────
    bsr     set_z0
    bne     c2_ok
    bra     fail_c2                 | wrong: Z=0 so BNE must be taken
c2_ok:

    | ── Case 3: callee leaves Z=1; BEQ on return MUST be taken ─────────
    bsr     set_z1
    beq     c3_ok
    bra     fail_c3
c3_ok:

    | ── Case 4: callee leaves N=1; BMI on return MUST be taken ─────────
    bsr     set_n1
    bmi     c4_ok
    bra     fail_c4
c4_ok:

    | ── Case 5: callee leaves C=1; BCS on return MUST be taken ─────────
    bsr     set_c1
    bcs     c5_ok
    bra     fail_c5
c5_ok:

    | ── Case 6: flag producer sits 2 instructions before the RTS ───────
    | Distance between the CCR write and the RTS changes how much time
    | commit has to catch up; vary it so a marginal interlock shows.
    bsr     set_z0_early
    bne     c6_ok
    bra     fail_c6
c6_ok:

    | ── Case 7: the branch and the CCR readback must AGREE ─────────────
    | This is the exact hardware signature: branch behaved one way while
    | the architectural CCR read the other.  Assert they match.
    bsr     set_z0
    move.w  %sr, %d7                | capture CCR as the Bcc would see it
    andi.w  #0x0004, %d7            | isolate Z
    bne     fail_c7                 | Z must be CLEAR here
    tst.w   %d7
    bne     fail_c7                 | ...and the readback must agree

    move.l  #0xC0FFEE00, %d1
    move.l  %d1, PASS_SENT
    bra     .

| ── callees ────────────────────────────────────────────────────────────
| Each leaves a known CCR and returns immediately, so the consumer Bcc is
| the first instruction to execute after the RTS.

set_z1:
    moveq   #0, %d0
    tst.l   %d0                     | Z=1, N=0, V=0, C=0
    rts

set_z0:
    moveq   #1, %d0
    tst.l   %d0                     | Z=0
    rts

set_n1:
    moveq   #-1, %d0
    tst.l   %d0                     | N=1, Z=0
    rts

set_c1:
    moveq   #0, %d0
    subq.l  #1, %d0                 | 0-1 -> C=1 (borrow), N=1, Z=0
    rts

set_z0_early:
    moveq   #1, %d0
    tst.l   %d0                     | Z=0 set here...
    nop                             | ...then two instructions of distance
    nop
    rts

fail_c1:
    move.l  #0xDEADBC01, %d1
    move.l  %d1, PASS_SENT
    bra     .
fail_c2:
    move.l  #0xDEADBC02, %d1
    move.l  %d1, PASS_SENT
    bra     .
fail_c3:
    move.l  #0xDEADBC03, %d1
    move.l  %d1, PASS_SENT
    bra     .
fail_c4:
    move.l  #0xDEADBC04, %d1
    move.l  %d1, PASS_SENT
    bra     .
fail_c5:
    move.l  #0xDEADBC05, %d1
    move.l  %d1, PASS_SENT
    bra     .
fail_c6:
    move.l  #0xDEADBC06, %d1
    move.l  %d1, PASS_SENT
    bra     .
fail_c7:
    move.l  #0xDEADBC07, %d1
    move.l  %d1, PASS_SENT
    bra     .
