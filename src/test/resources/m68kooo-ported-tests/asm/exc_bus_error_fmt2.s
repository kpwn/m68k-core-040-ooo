| exc_bus_error_fmt2.s — verify 68040 Format 7 frame contents for vec 2
|
| Generates a bus error via a load from an unmapped address and has
| the handler inspect the pushed stack frame.
|
| Format 7 access-error frame:
|   (A7+0) : SR            (word, low 16 of long at +0)
|   (A7+2) : PC            (long) — straddles 4-byte boundary
|   (A7+6) : format/vector (word, low 16 of long at +4)
|   (A7+8) : effective addr (long)
|   (A7+C) : special status word
|   (A7+14): fault_addr    (long) — the faulting EA
|
| This test verifies:
|   (a) handler fires (i.e. vec 2 is actually taken)
|   (b) A7 dropped by at least 60 bytes (format-7 frame pushed)
|   (c) format word at A7+4 (low half of long) has top nibble = 7
|       (format-7 selector)
|   (d) fault_addr at A7+14 matches the 0xAAAA0000 we dereferenced
|
| Implementation is careful to avoid multi-back-to-back CMP.L
| patterns that stress CCR rename — each check writes the PASS
| sentinel immediately upon success and an explicit FAIL sentinel
| on failure.
|
| PASS: all checks pass → sentinel 0xC0FFEE00.
| FAIL: any check fails → sentinel 0xDEADBEEF (or harness timeout).

    .text
    .org 0

_start:
    lea     0x00010000, %a7
    move.l  %a7, %a6                | save pre-trap SP for frame-size check
    move.l  #_handler, 0x00000008   | vector 2 @ 0x08
    lea     0xAAAA0000, %a0         | unmapped → AXI SLVERR → vec 2
    move.l  (%a0), %d0              | triggers bus error

    | Unreachable if the handler fires.
_fail:
    lea     0xFFFF0000, %a1
    move.l  #0xDEADBEEF, %d1
    move.l  %d1, (%a1)
_halt_fail:
    bra     _halt_fail

_handler:
    | Load everything first to avoid back-to-back CMP+branch chains.
    move.l  %a6, %d0                | D0 = old_SP
    sub.l   %a7, %d0                | D0 = frame_size (= 60 for fmt-7)
    move.l  4(%a7), %d1             | D1 = { PC[15:0], fmt_vec[15:0] }
    move.l  20(%a7), %d2            | D2 = fault_addr

    | Check 1: frame size must be ≥ 60
    cmp.l   #60, %d0
    blt     _fh_fail

    | Check 2: fault_addr == 0xAAAA0000
    move.l  #0xAAAA0000, %d3
    cmp.l   %d3, %d2
    bne     _fh_fail

    | Check 3: format nibble (bits 15..12 of low half of D1) == 7
    move.l  #0x0000F000, %d4
    and.l   %d4, %d1                | isolate format nibble
    cmp.l   #0x00007000, %d1
    bne     _fh_fail

_fh_pass:
    lea     0xFFFF0000, %a1
    move.l  #0xC0FFEE00, %d5
    move.l  %d5, (%a1)
_halt:
    bra     _halt

_fh_fail:
    lea     0xFFFF0000, %a1
    move.l  #0xDEADBEEF, %d5
    move.l  %d5, (%a1)
_halt_fh:
    bra     _halt_fh
