| adv_rts_unaligned_a7.s — RTS whose pop takes the split-LONG path
|
| ASSUMPTION TESTED (lsu.v S_LD_WAIT2, lines 590-608):
|   When a LONG load straddles a 4-byte boundary, lsu enters the split
|   path via S_LD_GAP → S_LD_WAIT2.  In S_LD_WAIT2 the code reassembles
|   the value and drives cdb_data, but there is NO `if (cur_is_rts)
|   begin cmpl_br_taken <= 1; cmpl_br_target <= ... end` block like the
|   single-beat path has at lines 565-568.
|
|   Single-beat path (lines 540-572) correctly sets cmpl_br_taken and
|   cmpl_br_target for RTS.  The split path, which triggers when A7 is
|   unaligned at 1/2/3 bytes off a LONG boundary, does NOT — so the
|   RTS becomes a silent non-branching load: A7 is written back (via
|   cdb_data = ea+4 in S_LD_WAIT2's unified cdb_data assignment, but
|   actually = mk_split_rdata not ea+4!) and the pipeline continues
|   at rob_npc_fallthru (the instruction after the RTS opword).
|
| TWO SIMULTANEOUS BUGS HERE:
|   1) cmpl_br_taken stays 0 — no branch fires.
|   2) cdb_data is mk_split_rdata (loaded data) not ea+4 — so A7 gets
|      overwritten with the ret-PC bits instead of advancing past it.
|   See lsu.v line 595: `cdb_data <= mk_split_rdata(...)` with no
|   cur_is_rts fork.
|
| ATTACK:
|   Set A7 to an unaligned address holding a valid return PC pattern,
|   then RTS.  Expected Musashi: jumps to loaded address, A7 += 4.
|   Expected RTL: no branch, A7 ← loaded data.
|
| Test uses a scratch sentinel in RAM at 0x00010000 (NOT 0xFFFF0000)
| as a pre-write diff-anchor, because the binary testbench halts on the
| first write to 0xFFFF0000 (PASS iff value == 0xC0FFEE00).  Only the
| pass/fail paths touch 0xFFFF0000.

    .text
    .org 0

_start:
    | Pre-write FAIL marker to a RAM scratch address (NOT the halt
    | sentinel) so fuzz-style state diffs still show a consistent
    | pre-RTS state.  0x00010000 is mapped RAM, well clear of the
    | 0x1FFFB unaligned-return-PC buffer used below.
    move.l  #0x00010000, %a0
    move.l  #0xDEADBEEF, %d7
    move.l  %d7, (%a0)

    | Write the target PC into memory at an unaligned 4-byte span.
    | We want bytes 3..6 of word 0x1FFF8 to hold _after_rts.
    move.l  #_after_rts, %d0
    move.l  #0x0001FFFB, %a1
    move.l  %d0, (%a1)              | big-endian store of _after_rts across 0x1FFFB..0x1FFFE

    | Point A7 at the unaligned slot.  RTS will pop a LONG from
    | 0x1FFFB — straddles the boundary at 0x1FFFC.
    move.l  #0x0001FFFB, %a7
    rts

    | Fall-through — RTL drops here because cmpl_br is never set on
    | the split path.  Update sentinel to FAIL and halt.
_after_fall:
    move.l  #0x5A5A5A5A, %d7        | marker: RTL fall-through ran
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
_hang1:
    bra     _hang1

_after_rts:
    | Musashi lands here — write PASS and halt.
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt
