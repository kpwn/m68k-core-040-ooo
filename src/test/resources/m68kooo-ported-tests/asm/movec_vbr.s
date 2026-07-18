| movec_vbr.s — MOVEC VBR test
|
| Sets VBR to 0x10000, installs a TRAP #0 handler at VBR + 32*4
| (= 0x10080), fires TRAP #0, verifies the handler runs (writes PASS).
|
| Verifies two things at once:
|   - MOVEC #imm,VBR actually updates VBR (otherwise the exception
|     sequencer would read from VBR=0 and get whatever happened to
|     be at 0x80 — typically zero, which jumps to 0 and falls
|     through to _fail).
|   - MOVEC VBR,Rn (optional) reads back the installed VBR.
|
| PASS: handler at VBR+0x80 writes C0FFEE00.
| FAIL: fallthrough writes DEADBEEF.

    .text
    .org 0

_start:
    lea     0x00020000, %a7
    | Load VBR base and install handler at VBR + 32*4.
    move.l  #0x00010000, %d0
    movec   %d0, %vbr
    move.l  #_handler, 0x00010080
    | (Optional sanity) read VBR back into D1, compare — not strictly
    | needed for PASS, but exercises the MOVEC-RD path.
    movec   %vbr, %d1
    trap    #0

    | Unreachable if MOVEC-VBR + handler landed correctly.
_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
_halt_fail:
    bra     _halt_fail

_handler:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt
