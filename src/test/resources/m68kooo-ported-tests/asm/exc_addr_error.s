| exc_addr_error.s — unaligned .L data access is NOT an address error on 68040
|
| Per MC68040UM §3.1.2 / §8.2: "Operand data misalignments do not cause
| exceptions on the MC68040; all misaligned operand accesses are handled
| automatically."  The original 68000 vector 3 (address error) is NOT
| raised for data accesses on the 68040; it remains only for instruction
| fetches to odd addresses and for exception-stack misalignment corner
| cases.
|
| Earlier rev of this test asserted "vector 3 fires on misaligned .L
| load", which is 68000/68020 semantics — wrong target.  This rewrite
| asserts the 68040-correct behaviour: the LSU splits the unaligned
| load into two aligned beats and the program continues.
|
| PASS: load completes and we reach the sentinel writer.
| FAIL: handler fires (we would treat that as a regression vs PRM).

    .text

_start:
    lea     0x00010000, %a7
    move.l  #_handler_regress, 0x0000000C | vector 3: if it fires = FAIL

    | Seed memory at 0x00100000..0x00100007 with a known byte pattern
    | so the unaligned long load has something deterministic to read.
    lea     0x00100000, %a0
    move.l  #0x11223344, (%a0)
    move.l  #0x55667788, 4(%a0)

    | Unaligned long load from 0x00100001:
    |   bytes [0x00100001..0x00100004] = 22 33 44 55 → 0x22334455
    move.l  0x00100001, %d0
    cmp.l   #0x22334455, %d0
    bne     _fail

    lea     0xFFFF0000, %a1
    move.l  #0xC0FFEE00, %d1
    move.l  %d1, (%a1)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a1
    move.l  #0xBADBAD00, %d1
    move.l  %d1, (%a1)
_fhlt:
    bra     _fhlt

_handler_regress:
    | If we end up here, the LSU erroneously raised vector 3.
    lea     0xFFFF0000, %a1
    move.l  #0xFEEDFACE, %d1
    move.l  %d1, (%a1)
_hhlt:
    bra     _hhlt
