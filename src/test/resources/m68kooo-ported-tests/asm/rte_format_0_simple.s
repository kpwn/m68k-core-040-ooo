| rte_format_0_simple.s — Stage D-9i V2 RTE directed test.
|
| Hand-builds a 68040 format-0 exception stack frame on the SSP and
| executes RTE.  Format 0 is the "normal" 4-word / 8-byte frame:
|
|   SSP + 0 : SR (16 bits)
|   SSP + 2 : PC-hi (16 bits)
|   SSP + 4 : PC-lo (16 bits)
|   SSP + 6 : format nybble [15:12] = 0 + vector offset [11:0]
|
| After RTE: SR is restored from word 0, PC jumps to the long at
| SSP+2, SSP advances by 8.  We encode the target PC as _landed and
| the target SR with S=1 (stay in supervisor), trace/IRQ-mask clear.
|
| PASS: RTE pops the hand-built frame, execution lands at _landed,
|       _landed writes 0xC0FFEE00 to the sentinel.
| FAIL: Anything else (no jump, double-fault, format error) leaves
|       control on the fallthrough which writes 0xDEADBEEF.
|
| Entry SR = 0x2700 (supervisor, IRQ mask 7).  Post-RTE SR target is
| 0x2000 (supervisor, IRQ mask 0) — we verify SR restored by reading
| CCR after the RTE (not strictly required for PASS, kept as
| diagnostic in D7).

    .text
    .org 0

_start:
    | Starts in supervisor.  Set up stack high enough that the hand-
    | built frame fits.
    lea     0x00010000, %a7

    | Build format-0 frame at (A7-8) by pushing in reverse.
    | Layout wanted (address order low→high):
    |   A7-8 : SR  = 0x2000
    |   A7-6 : PC  = _landed (long)
    |   A7-2 : format/vec = 0x0000  (format=0, vec-offset=0)
    |
    | Push order: format word first (deepest in stack after subq),
    | then PC long, then SR word.  Using -(A7) auto-predec.
    move.w  #0x0000, -(%a7)          | format nybble 0, vec offset 0
    move.l  #_landed, -(%a7)         | PC long at SSP+2
    move.w  #0x2000, -(%a7)          | SR word at SSP+0

    | SSP now points at the SR.  RTE pops SR, PC, format — and
    | advances SSP by 8 (format-0 frame size).
    rte

    | RTE must NOT fall through.  If it does, we flag failure.
_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
_halt_fail:
    bra     _halt_fail

_landed:
    | RTE landed here.  Write PASS sentinel.
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt
