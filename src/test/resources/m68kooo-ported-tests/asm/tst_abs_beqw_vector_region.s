| tst_abs_beqw_vector_region.s — TST.B (xxx).W + word-displacement Bcc
| sweeping the operand address across the 68040 EXCEPTION VECTOR TABLE
| region (0x000-0x3FF), both ODD and EVEN byte addresses, both Z=1 and
| Z=0 directions per address.
|
| MOTIVATION.  The live HW ROM probe address is 0x0349 — an ODD address
| sitting inside the exception vector table's address space (vector
| table is 0x000-0x3FF on the 68040; 0x0349 lands inside the unused
| user-defined-vector range, vector #210).  tst_abs_beqw.s already
| covers 0x0349 itself plus a couple of other spots; this test widens
| the address axis specifically across the vector-table region's
| extremes (first byte 0x0000, last byte 0x03FF) and several odd/even
| points in between, in case there is some address-decode or byte-lane
| corner tied to being inside that specific low-memory region (as
| opposed to ordinary RAM) that the single 0x0349 sample wouldn't catch.
| No exceptions are triggered in this test (IPL stays masked at the
| reset default and nothing here traps), so scribbling test bytes over
| vector-table entries has no live consequence for this run.
|
| PASS: 0xC0FFEE00 sentinel.
| FAIL: 0xDEAD0900 + (index<<8) | direction, where direction 0 = "Z=1
| but not taken" and 1 = "Z=0 but taken", and index is the 0-based
| position in the address table below (so e.g. 0xDEAD0901 identifies
| address #0 direction=Z=0-wrongly-taken).  See ADDR_TABLE order.

    .text
    .org 0

_start:
    lea     0x00010000, %a7

| Address table (documented here so a FAIL code's index is decodable):
|   #0  0x0000  (first byte of vector table; vector 0 = reset SSP)
|   #1  0x0001  (odd)
|   #2  0x0002  (even)
|   #3  0x0063  (odd, just before the level-1 autovector at 0x64)
|   #4  0x0181  (odd, mid-table)
|   #5  0x0248  (even, mid-table)
|   #6  0x0349  (odd — the ROM's own probe address)
|   #7  0x03FE  (even, next-to-last)
|   #8  0x03FF  (last byte of the vector table region)

| ---- #0: 0x0000 --------------------------------------------------
    move.b  #0x00, 0x0000
    .short  0x4a38, 0x0000
    .short  0x6700
    .short  (_a0z - .)
    move.l  #0xDEAD0900, %d7
    bra     _fail_common
_a0z:
    move.b  #0x01, 0x0000
    .short  0x4a38, 0x0000
    .short  0x6700
    .short  (_fail_a0n - .)
    bra     _a1

_fail_a0n:
    move.l  #0xDEAD0901, %d7
    bra     _fail_common

| ---- #1: 0x0001 (odd) ---------------------------------------------
_a1:
    move.b  #0x00, 0x0001
    .short  0x4a38, 0x0001
    .short  0x6700
    .short  (_a1z - .)
    move.l  #0xDEAD0910, %d7
    bra     _fail_common
_a1z:
    move.b  #0x01, 0x0001
    .short  0x4a38, 0x0001
    .short  0x6700
    .short  (_fail_a1n - .)
    bra     _a2

_fail_a1n:
    move.l  #0xDEAD0911, %d7
    bra     _fail_common

| ---- #2: 0x0002 (even) --------------------------------------------
_a2:
    move.b  #0x00, 0x0002
    .short  0x4a38, 0x0002
    .short  0x6700
    .short  (_a2z - .)
    move.l  #0xDEAD0920, %d7
    bra     _fail_common
_a2z:
    move.b  #0x01, 0x0002
    .short  0x4a38, 0x0002
    .short  0x6700
    .short  (_fail_a2n - .)
    bra     _a3

_fail_a2n:
    move.l  #0xDEAD0921, %d7
    bra     _fail_common

| ---- #3: 0x0063 (odd, just before the level-1 autovector) --------
_a3:
    move.b  #0x00, 0x0063
    .short  0x4a38, 0x0063
    .short  0x6700
    .short  (_a3z - .)
    move.l  #0xDEAD0930, %d7
    bra     _fail_common
_a3z:
    move.b  #0x01, 0x0063
    .short  0x4a38, 0x0063
    .short  0x6700
    .short  (_fail_a3n - .)
    bra     _a4

_fail_a3n:
    move.l  #0xDEAD0931, %d7
    bra     _fail_common

| ---- #4: 0x0181 (odd, mid-table) ----------------------------------
_a4:
    move.b  #0x00, 0x0181
    .short  0x4a38, 0x0181
    .short  0x6700
    .short  (_a4z - .)
    move.l  #0xDEAD0940, %d7
    bra     _fail_common
_a4z:
    move.b  #0x01, 0x0181
    .short  0x4a38, 0x0181
    .short  0x6700
    .short  (_fail_a4n - .)
    bra     _a5

_fail_a4n:
    move.l  #0xDEAD0941, %d7
    bra     _fail_common

| ---- #5: 0x0248 (even, mid-table) ---------------------------------
_a5:
    move.b  #0x00, 0x0248
    .short  0x4a38, 0x0248
    .short  0x6700
    .short  (_a5z - .)
    move.l  #0xDEAD0950, %d7
    bra     _fail_common
_a5z:
    move.b  #0x01, 0x0248
    .short  0x4a38, 0x0248
    .short  0x6700
    .short  (_fail_a5n - .)
    bra     _a6

_fail_a5n:
    move.l  #0xDEAD0951, %d7
    bra     _fail_common

| ---- #6: 0x0349 (odd — the ROM's own probe address) --------------
_a6:
    move.b  #0x00, 0x0349
    .short  0x4a38, 0x0349
    .short  0x6700
    .short  (_a6z - .)
    move.l  #0xDEAD0960, %d7
    bra     _fail_common
_a6z:
    move.b  #0x01, 0x0349
    .short  0x4a38, 0x0349
    .short  0x6700
    .short  (_fail_a6n - .)
    bra     _a7

_fail_a6n:
    move.l  #0xDEAD0961, %d7
    bra     _fail_common

| ---- #7: 0x03FE (even, next-to-last) -------------------------------
_a7:
    move.b  #0x00, 0x03FE
    .short  0x4a38, 0x03FE
    .short  0x6700
    .short  (_a7z - .)
    move.l  #0xDEAD0970, %d7
    bra     _fail_common
_a7z:
    move.b  #0x01, 0x03FE
    .short  0x4a38, 0x03FE
    .short  0x6700
    .short  (_fail_a7n - .)
    bra     _a8

_fail_a7n:
    move.l  #0xDEAD0971, %d7
    bra     _fail_common

| ---- #8: 0x03FF (last byte of the vector-table region) -------------
_a8:
    move.b  #0x00, 0x03FF
    .short  0x4a38, 0x03FF
    .short  0x6700
    .short  (_a8z - .)
    move.l  #0xDEAD0980, %d7
    bra     _fail_common
_a8z:
    move.b  #0x01, 0x03FF
    .short  0x4a38, 0x03FF
    .short  0x6700
    .short  (_fail_a8n - .)
    bra     _pass

_fail_a8n:
    move.l  #0xDEAD0981, %d7
    bra     _fail_common

_pass:
    lea     0xFFFF0000, %a2
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a2)
_halt:
    bra     _halt

_fail_common:
    lea     0xFFFF0000, %a2
    move.l  %d7, (%a2)
_halt_fail:
    bra     _halt_fail
