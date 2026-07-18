| adv_spec_trap_squash.s — Speculative TRAP must be squashed on branch mispredict
|
| ASSUMPTION TESTED (commit.v take_exc at line 398):
|   "A head μop with exc_valid preempts the normal commit path."
|
|   Decode-time exceptions (TRAP #n, CHK, ILLEGAL, F-line, privilege)
|   set rob_exc and fire the exception sequencer at the ROB head.
|   CRUCIAL: these must ONLY fire when the μop is architecturally
|   retired — NEVER from the shadow of a mispredicted branch.
|
|   The invariant: `can_commit && (rob_exc || take_priv_exc)` must be
|   false for any ROB entry behind a pending branch resolve.  This
|   relies on ROB in-order commit: the branch resolves first (or
|   retires first with misprediction flush), which squashes all newer
|   uops including any trap μops in the wrong-path shadow.
|
| ATTACK:
|   Set up a mispredicted Bcc whose fall-through path contains a TRAP
|   #15.  If speculative TRAP is handled as a real exception, the
|   handler runs and we observe its side effects (SSP changed, arch_sr
|   changed, or handler-PC memory writes).  If the speculative TRAP is
|   correctly squashed, the taken-branch path wins and the sentinel
|   gets written normally.
|
| PASS: both RTL and Musashi reach the sentinel without invoking TRAP
|   #15's handler.
|
| FAILURE: RTL enters the TRAP handler (random VBR jump to 0xBC), most
|   likely hangs or crashes.
|
| Note: Musashi models in-order execution so speculation cannot happen.
|   But this test checks the RTL's squash correctness — both end at
|   sentinel.  The DIVERGENCE check looks at final CCR / regs.

    .text
    .org 0

_start:
    | Point VBR so TRAP vectors go to a known handler that marks D4.
    | Actually we cannot set VBR in this bring-up (supervisor side),
    | so instead we rely on the default VBR=0 → TRAP#15 vec = 0x00BC.
    | Memory at 0x00BC is unmapped — if the TRAP actually fires we'd
    | either hang (no RAM) or jump to garbage.

    lea     0x00020000, %a7
    moveq   #1, %d0
    cmp.l   #1, %d0                 | Z=1
    beq     _good                   | taken; fall-through is wrong-path

    | Wrong-path (speculative):
    trap    #15
    | Never reached on correct squash.

_good:
    | Sentinel
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt
