| fpu_fpsp_selfrecursion_repro.s — reproduce the ACTUAL live-HW boot
|                                  deadlock mechanism (not just the
|                                  instruction in isolation) and confirm
|                                  the fix breaks it.
|
| Root cause (project_fmovem_ctrl_predec_selfrecursion_2026_07_23):
| decode_1111.vh's control-register FMOVEM.L block only decoded
| (d16,An) before this landing; predecrement (mode=100) fell through
| to the F-line (vector 11) trap.  The Q700 ROM's own vector-11
| handler prologue at 0x0008bf6e is
| `FMOVEM.L FPIAR/FPSR/FPCR,-(A7)` — its very first action on entry.
| Since predecrement wasn't decoded, THAT instruction itself trapped
| back into the SAME handler, which tried the same first instruction
| again, forever: a 100%-reproducible, self-sustaining exception storm
| that consumed the CPU permanently the instant ANY unimplemented FPU
| instruction trapped anywhere in a running Mac OS.
|
| This test reproduces the actual re-entrant shape: a REAL vector-11
| trap (not an inline byte-sequence mimic) dispatches to a handler
| whose first instruction is exactly the ROM's
| `FMOVEM.L FPIAR/FPSR/FPCR,-(A7)` (opword F227, ext1 BC00 — verified
| identical encoding to the ROM disassembly in the root-cause memory
| file).  A re-entry counter in memory detects the actual failure mode
| (the handler being entered a second time before ever completing) and
| fails fast and deterministically, rather than relying on the sim's
| cycle budget to eventually time out on an infinite storm.
|
| Frame-format note (found while building this test): F-line traps use
| the 68040's 12-byte format-$2 exception frame (SR + PC + format/
| vector word + an extra faulting-instruction-address long), NOT the
| 8-byte format-$0 frame — confirmed via RTE_FIN trace
| (`make test DEBUG=1`: `fmt_nib=2`).  Format-$2's saved PC is the
| ADDRESS OF THE FAULTING INSTRUCTION ITSELF (by 68040 design — F-line
| traps are "resumable-instruction" traps meant for software emulation
| to replace, not "return past"), so a handler that RTEs without first
| advancing the saved PC will legitimately re-execute the *original*
| trap-triggering instruction and re-trap — this is correct 68040
| semantics, not a bug, and is why this handler explicitly advances the
| saved PC by 2 (past the 1-word `.short 0xFFFF` trigger) before RTE,
| mimicking the minimal shape of what a real FPSP emulation routine
| does before returning.
|
| PASS: handler entered exactly once, completes, RTEs, and the outer
|       program resumes past the original trigger and reaches PASS.
| FAIL:
|   0xDEAD3001 — handler re-entered (self-recursion still present —
|                the exact live-HW deadlock mechanism)
|   0xDEAD3002 — handler never returned / outer code never resumed
|                (should be unreachable in sim — a still-broken CPU
|                would hang until the harness's cycle budget expires
|                and FAILs on timeout instead of via this sentinel)

    .text
    .org 0

    .equ PASS_SENT,   0xFFFF0000
    .equ REENTRY_CNT, 0x00020000

_start:
    lea     0x00010000, %a7
    move.l  #_fline_handler, 0x0000002C   | vector 11 @ 0x2C
    move.l  #0, REENTRY_CNT

    | Trigger a real, generic F-line trap.  0xFFFF (not 0xF123) avoids
    | Musashi decoding it as PSAVE/PMMU before reaching vector 11 —
    | same convention as exc_fline.s.
    .short  0xFFFF

    | Reached only via a successful RTE from the handler below.
    lea     PASS_SENT, %a1
    move.l  #0xC0FFEE00, %d2
    move.l  %d2, (%a1)
_halt:
    bra     _halt

_fline_handler:
    | Re-entry detector: if this handler is entered a second time
    | before the first entry ever gets past its own prologue
    | instruction, that IS the self-recursion bug — fail fast instead
    | of spinning until the harness's cycle-budget timeout.
    move.l  REENTRY_CNT, %d0
    addq.l  #1, %d0
    move.l  %d0, REENTRY_CNT
    cmp.l   #1, %d0
    bne     _fail_reentered

    | The exact ROM FPSP entry prologue's first instruction
    | (0x0008bf6e in the Q700 ROM): FMOVEM.L FPIAR/FPSR/FPCR,-(A7).
    .short  0xF227, 0xBC00

    | If we get here, the instruction decoded and executed without
    | re-trapping.  Undo its stack effect (this test validates
    | no-recursion, not round-trip fidelity — see
    | fpu_fmovem_ctrl_predec.s for that).
    lea     12(%a7), %a7

    | Advance the saved PC (format-$2 frame, PC at offset 2 from the
    | frame base — A7 now points there) past the 1-word trigger
    | instruction, so RTE resumes the OUTER program instead of
    | re-executing (and legitimately re-trapping on) the same F-line
    | opword — see the frame-format note above.
    move.l  2(%a7), %d0
    addq.l  #2, %d0
    move.l  %d0, 2(%a7)
    rte

_fail_reentered:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD3001, %d2
    move.l  %d2, (%a1)
_halt_reentered:
    bra     _halt_reentered
