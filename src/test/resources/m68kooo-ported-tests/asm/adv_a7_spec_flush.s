| adv_a7_spec_flush.s — Speculative A7 write squashed by branch mispredict;
|                       subsequent A7 readers must see the committed value.
|
| ASSUMPTION TESTED (rat.v committed_busy rollback, decision #9):
|   "The speculative ratmap is backed by a committed-state shadow crat
|    that is advanced by commit.  On flush_en, the live ratmap is
|    overwritten from crat — this prevents speculative renames past a
|    mispredicted branch from leaving dangling phys regs whose producers
|    were squashed (previously caused bsr_rts_basic to deadlock on the
|    next A7 reader)."
|
|   Attack scenario: a speculative ADDA.L #imm, %a7 (wrong-path of a
|   branch) gets squashed.  A later correct-path A7 reader (e.g., BSR,
|   stack write) must use the committed A7 — not the squashed
|   speculation.
|
| ATTACK:
|   Taken-branch scenario where the wrong-path has a speculative A7
|   modification.  After the correct path resumes, verify A7 matches
|   the pre-branch value AND a subsequent BSR/RTS works.
|
| PASS: A7 unchanged at end + BSR/RTS round-trip succeeds.
| DIVERGENCE: RAT rollback dropped an A7 mapping, leaving dangling
|   phys ref → wrong A7 in subsequent ops.

    .text
    .org 0

_start:
    lea     0x00020000, %a7          | committed A7
    move.l  %a7, %d6                 | snapshot committed A7 into D6

    moveq   #0, %d0
    cmp.l   #0, %d0                  | Z=1
    beq     _taken                   | take — fall-through is wrong path

    | ── wrong path (speculative) — must be squashed ──
    adda.l  #256, %a7                | speculative A7 += 256
    suba.l  #128, %a7                | speculative A7 -= 128
    move.l  #0xBAD0BAD0, %d5
    move.l  %d5, (%a7)               | speculative store at wrong A7
    bra     _fail

_taken:
    | ── correct path resumes after branch resolve ──
    | Verify A7 == D6 (unchanged)
    cmp.l   %d6, %a7
    bne     _fail

    | BSR/RTS — stack must be clean at committed A7
    bsr     _sub
    cmp.l   %d6, %a7                 | A7 restored after RTS
    bne     _fail

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_sub:
    moveq   #42, %d4
    rts

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
_halt_f:
    bra     _halt_f
