| store_load_alias.s — Store→Load aliasing on the same address
|
| Two pointers A0 and A1 both target 0x00100000. A store via A0 is
| immediately followed by a load via A1. The OoO LSU must either
| forward the in-flight store to the load, or stall the load until
| the store commits to memory — never let the load see stale data.
|
| According to CLAUDE.md the current LSU does NOT forward; it serialises
| (iss_ready blocks new mem ops while a store buffer waits to commit).
| So the load should observe 0xCAFEBABE.

    .text
    .org 0

_start:
    lea     0x00100000, %a0
    lea     0x00100000, %a1     | aliases A0
    move.l  #0xCAFEBABE, %d0
    move.l  %d0, (%a0)          | store to *A0
    move.l  (%a1), %d1          | load from *A1 (must see CAFEBABE)

    move.l  #0xCAFEBABE, %d2
    cmp.l   %d2, %d1
    bne     _fail

    lea     0xFFFF0000, %a2
    move.l  #0xC0FFEE00, %d3
    move.l  %d3, (%a2)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a2
    move.l  #0xDEADBEEF, %d3
    move.l  %d3, (%a2)
_halt_fail:
    bra     _halt_fail
