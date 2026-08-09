| rte_format_a_b_raise_vec14.s — RTE from a stacked format $A or $B
| frame is a FORMAT ERROR on the 68040, not a valid pop.
|
| Why this test exists: exception.v used to decode the stacked format
| nibble itself and accepted $A (pop 32 bytes) and $B (pop 92 bytes) as
| the 68020/030 short/long bus-fault frames.  When the exception
| sequencer was refactored into a pure µop source (commits 5bd568b5 /
| 4736c466, task #9), the frame-size table moved to commit.v's
| take_rte_finalize, whose table is {$0,$1 -> 8, $2 -> 12, $7 -> 60} and
| classifies EVERYTHING ELSE — including $A and $B — as a bad format,
| dispatching vector 14.
|
| That is correct for a 68040 (it pushes $0/$1/$2/$7 and never $A/$B —
| M68040UM §8.4), but it is a real semantic change from what the old
| tb_exception.cpp asserted, and it had no coverage anywhere.  This test
| pins it.  exc_rte_format_error.s already covers a bad nibble ($9);
| this one covers specifically the two nibbles that used to be legal.
|
| PASS: the vector-14 handler runs twice, once per malformed RTE.
| FAIL: either RTE resumes at the stacked PC, or the handler runs a
|       different number of times.

    .text
    .org 0

_start:
    lea     0x00010000, %a7
    move.l  #_format_handler, 0x00000038   | vector 14 @ VBR(0) + 14*4
    moveq   #0, %d7                        | handler invocation counter

    | ── RTE #1: stacked format nibble = $A ───────────────────────────
    bsr     _build_frame
    move.w  #0xA000, 6(%a7)
    rte

_bad_resume:
    | Reached only if RTE wrongly accepted the malformed frame.
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, (%a0)
_halt_bad:
    bra     _halt_bad

| Build an 8-byte frame at the stack top: SR / PC-hi / PC-lo / format.
| The caller overwrites the format word at 6(%a7) afterwards.  A7 is
| reset to a known top first so the leftover malformed frame from a
| previous vector-14 entry cannot accumulate.
_build_frame:
    move.l  (%a7)+, %d1                    | pop the BSR return address
    lea     0x00010000, %a7
    subq.l  #8, %a7
    move.w  #0x2000, (%a7)                 | supervisor SR, IRQs enabled
    move.l  #_bad_resume, %d0
    swap    %d0
    move.w  %d0, 2(%a7)                    | resume PC high
    swap    %d0
    move.w  %d0, 4(%a7)                    | resume PC low
    move.w  #0x0000, 6(%a7)                | placeholder format word
    move.l  %d1, %a1
    jmp     (%a1)

_format_handler:
    addq.l  #1, %d7
    cmpi.l  #1, %d7
    beq     _rte_fmt_b
    cmpi.l  #2, %d7
    beq     _pass
    bra     _fail

    | ── RTE #2: stacked format nibble = $B ───────────────────────────
_rte_fmt_b:
    bsr     _build_frame
    move.w  #0xB000, 6(%a7)
    rte

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xBADBAD00, (%a0)
_halt_fail:
    bra     _halt_fail

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, (%a0)
_halt:
    bra     _halt
