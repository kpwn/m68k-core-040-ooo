| exc_bus_error.s — Bus error on unmapped address (vector 2)
|
| Hypothesis: a load from an address outside any decoded region
| (RAM=0x0nnn_nnnn, ROM=0x4nnn_nnnn, I/O=0x5nnn_nnnn, video=0x6nnn_nnnn,
| sentinel=0xFFFFnnnn) should cause axi_xbar to respond with DECERR.
| The LSU sees BRESP/RRESP != OKAY and raises vector 2.
|
| 0xAAAA_0000 is comfortably outside every decoded prefix — DECERR
| is guaranteed.  Phase-2.1 frame format for bus error is format-2,
| with fault_addr = 0xAAAA_0000.  This test only verifies the handler
| fires; a separate test (exc_stack_frame_format.s) checks the frame
| contents for a TRAP to avoid tangling format-0 vs format-2 checks.
|
| Vector 2 lives at 0x00000008 (2 * 4).
|
| PASS: handler sentinel.
| FAIL: fallthrough sentinel.

    .text
    .org 0

_start:
    lea     0x00010000, %a7
    move.l  #_handler, 0x00000008   | vector 2 @ 0x08
    lea     0xAAAA0000, %a0         | unmapped — AXI xbar DECERRs
    move.l  (%a0), %d0              | triggers bus error

    | If LSU didn't raise the trap, fall through to FAIL
_fail:
    lea     0xFFFF0000, %a1
    move.l  #0xDEADBEEF, %d1
    move.l  %d1, (%a1)
_halt_fail:
    bra     _halt_fail

_handler:
    lea     0xFFFF0000, %a1
    move.l  #0xC0FFEE00, %d1
    move.l  %d1, (%a1)
_halt:
    bra     _halt
