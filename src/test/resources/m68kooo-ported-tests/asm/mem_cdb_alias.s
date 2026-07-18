| mem_cdb_alias.s — Interleaved load/store, CDB has_dst gate stress
|
| Repeatedly reuses A0 as both store base AND load destination, so the
| same physical register tag rotates through multiple uops on the CDB.
| After each load, the AGU/LSU completion must broadcast the correct
| destination tag, and any concurrent ALU cmpl must not falsely wake
| waiting uops with a has_dst=0 completion.
|
| Layout at A0 = 0x00102000:
|   offset 0  : 0xCAFEBABE
|   offset 4  : 0x01020304
|   offset 8  : 0xDEADBEEF
|   offset 12 : 0xFEEDFACE
|
| Pattern: store, load into An-like target, increment, store, load, check.

    .text
    .org 0

_start:
    lea     0x00102000, %a0

    | Four stores in quick succession
    move.l  #0xCAFEBABE, %d0
    move.l  %d0, (%a0)
    move.l  #0x01020304, %d0
    move.l  %d0, 4(%a0)
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, 8(%a0)
    move.l  #0xFEEDFACE, %d0
    move.l  %d0, 12(%a0)

    | Now load-modify-store chain reusing D0 as dest over and over.
    | Each load commits through CDB with has_dst=1; intervening AGU ops
    | may broadcast with has_dst=0 (if the recent bug re-surfaces, a
    | stale value leaks into D0).
    move.l  (%a0), %d0               | D0 = CAFEBABE
    addq.l  #1, %d0                  | D0 = CAFEBABF
    move.l  %d0, 16(%a0)
    move.l  4(%a0), %d0              | D0 = 01020304
    addq.l  #2, %d0                  | D0 = 01020306
    move.l  %d0, 20(%a0)
    move.l  8(%a0), %d0              | D0 = DEADBEEF
    addq.l  #3, %d0                  | D0 = DEADBEF2
    move.l  %d0, 24(%a0)
    move.l  12(%a0), %d0             | D0 = FEEDFACE
    addq.l  #4, %d0                  | D0 = FEEDFAD2
    move.l  %d0, 28(%a0)

    | Final read-back of everything — the stores at offsets 16..28 must
    | hold the computed values.
    move.l  16(%a0), %d1
    cmp.l   #0xCAFEBABF, %d1
    bne     _fail

    move.l  20(%a0), %d2
    cmp.l   #0x01020306, %d2
    bne     _fail

    move.l  24(%a0), %d3
    cmp.l   #0xDEADBEF2, %d3
    bne     _fail

    move.l  28(%a0), %d4
    cmp.l   #0xFEEDFAD2, %d4
    bne     _fail

    lea     0xFFFF0000, %a1
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a1)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a1
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a1)
_halt_fail:
    bra     _halt_fail
