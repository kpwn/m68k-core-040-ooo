| exc_aline_a7_alu_skip.s — A7-via-ALU effects skipped at exception entry?
|
| Minimal repro for task #36/#37: a single ALU op (`add.l #4, %a7`)
| immediately before an A-line trap.  If the OoO core correctly waits
| for the ALU op to commit before firing the exception, the frame
| address = a7_post - 8 = 0xFFFD.  If the ALU effect is skipped, the
| frame address = a7_pre - 8 = 0xFFF9.
|
| MMU + D-cache are OFF here on purpose — we want to isolate the
| commit-vs-fire race, not interact with the cache/MMU paths.
|
| PASS sentinel: 0xC0FFEE00.
| FAIL sentinels:
|   0xDEAD0701  Handler entered with A7 == 0xFFF9 (= ALU op SKIPPED) ← BUG
|   0xDEAD0702  Handler entered with some other A7 value
|   0xDEAD0703  Handler never entered (timeout fallthrough)

    .text
    .org 0

_start:
    | Vector 10 → handler.
    move.l  #_aline_handler, 0x00000028

    | Establish A7 = 0x10001 (odd).
    lea     0x00010001, %a7

    | Supervisor + IPL=0.
    move.w  #0x2000, %sr

    | THE INTERESTING INSTRUCTION: ALU add updates A7 from 0x10001 to
    | 0x10005.  Must commit before A-line takes the exception or the
    | frame push uses the stale A7.
    add.l   #4, %a7                     | a7: 0x10001 → 0x10005

_aline_site:
    .short  0xa05d                       | A-line opword

_fallthrough:
    | If we get here, handler didn't run.
    move.l  #0xDEAD0703, %d7
    bra     _fail

    .align 2
_aline_handler:
    | What is A7 right now?
    cmp.l   #0x0000FFFD, %a7             | expected: a7_post - 8
    beq     _good_a7

    cmp.l   #0x0000FFF9, %a7             | bug: a7_pre - 8 (ALU skipped)
    beq     _bad_alu_skip

    | Some other value.
    move.l  #0xDEAD0702, %d7
    bra     _fail

_good_a7:
    move.l  #0x00018000, %a7
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt_p:
    bra     _halt_p

_bad_alu_skip:
    move.l  #0x00018000, %a7
    move.l  #0xDEAD0701, %d7
    bra     _fail

_fail:
    move.l  #0x00018000, %a7
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
_halt_f:
    bra     _halt_f
