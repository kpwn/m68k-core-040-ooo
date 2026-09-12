| fpu_fsave_ea_modes.s — FSAVE's displaced/absolute EA forms are NOT IMPLEMENTED
|                        and correctly take the vector-11 F-line trap.
|
| ⚠ CORRECTED 2026-09-12 — the first version of this file claimed "FSAVE HANGS
| THE CORE". THAT WAS WRONG, and the mistake is worth recording:
|
|   The probes that "hung" installed NO vector-11 handler, so the (correct)
|   F-line trap dispatched through a ZERO vector-table entry, jumped to address
|   0, and spun. With a handler installed, `fsave 8(%a0)` lands in it cleanly
|   (verified: the vec-11 handler runs), exactly like the cpGEN control
|   (an unimplemented FSINCOS-family opcode) does.
|
|   A non-zero marker in the reported value ruled out the "sentinel written as 0
|   reads as no-write" harness artifact — but said nothing about a missing
|   handler. Two different ways to fake a hang; check BOTH.
|
| WHAT IS ACTUALLY TRUE
|
| A real 68040 accepts control-alterable and predecrement modes for FSAVE, so
| (d16,An), (xxx).W and (xxx).L are all LEGAL there and execute in hardware.
| This core implements only the register-indirect forms:
|
|     fsave -(%a7)     implemented   A7 -= 52, header 0x41300000, zero body
|     fsave (%a0)      implemented   header 0x41300000
|     fsave -(%a0)     implemented   A0 -= 52
|     fsave 8(%a0)     -> vector 11  (this test)
|     fsave 0x1F00.w   -> vector 11
|     fsave 0x00020010 -> vector 11
|
| That is a REAL 1:1 deviation, deliberately scoped: OperationDecoder.scala's
| FSAVE/FRESTORE arm documents it ("the displacement/absolute forms need a real
| EA computation that this commit-time sysOp path has no AGU for, so they stay
| OUT of scope on the line-F vector-11 fall-through"). The fall-through WORKS;
| what is missing is the EA computation itself.
|
| Impact is bounded: the Q700 ROM's FPSP uses `fsave %sp@-`, which is
| implemented, so the boot path is unaffected. Code using a displaced or
| absolute FSAVE gets a vector-11 trap the FPSP will not emulate (FSAVE is not
| a cpGEN opcode), so it would fault rather than work.
|
| This test PINS THE CURRENT BEHAVIOUR: the trap fires and is dispatchable. If
| the EA forms are ever implemented, this test must be updated to expect the
| frame instead of the trap.
|
| PASS sentinel: 0xC0FFEE00 — vector 11 taken, as currently designed.
| FAIL sentinels:
|   0xDEAD0E01 — executed silently (would mean the EA forms became implemented,
|                at which point this test should assert the frame instead)
|   0xDEAD0E04 — vector 4 taken instead of 11 (wrong vector)

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000

_start:
    lea     0x00010000, %a7
    move.l  #_fline, 0x0000002C        | vec 11 — MUST be installed, see above
    move.l  #_illegal, 0x00000010      | vec 4, to catch a wrong-vector dispatch
    lea     0x00020000, %a0

    fsave   8(%a0)                     | (d16,An): not implemented -> vector 11

    move.l  #0xDEAD0E01, %d2           | fell through = it executed
    bra     _done
_fline:
    move.l  #0xC0FFEE00, %d2
    bra     _done
_illegal:
    move.l  #0xDEAD0E04, %d2
_done:
    lea     PASS_SENT, %a2
    move.l  %d2, (%a2)
_halt:
    bra     _halt
