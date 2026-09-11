| bf_abs_dyn_offset.s — bit-field with DYNAMIC OFFSET (Do=1) at an ABSOLUTE EA.
|
| Every one of these shapes used to be routed to BF_DYN_ILLEGAL_ENTRY (a vector-4
| trap), for both the read-only family (BFTST/BFEXTU/BFEXTS/BFFFO) and the RMW
| family (BFCHG/BFCLR/BFSET/BFINS).  The reason was real: the Do=1 chains recompute
| the byte base with `UBfAdd srcA=SEaBase, srcB=T0(byteDelta)`, and an absolute
| (xxx).W/.L EA is MEMSIMPLE with baseValid=False — it has no base REGISTER — so the
| add pulled a stale physical register and produced a wrong address.  AluEuPlugin now
| gates srcA to ZERO when psrcAValid is False (which BranchEu and LsEu already did for
| the identical invalid-base case), so the add yields byteDelta alone and the absolute
| address rides eaDispLo at the load/store rows, exactly as the Do=0 abs chain does.
|
| Coverage that matters here, beyond "it no longer traps":
|   - offset < 8  (byteDelta == 0)      — the plain invalid-base read
|   - offset >= 8 (byteDelta > 0)       — exercises the recomputed base
|   - a STRADDLING field (bitOff != 0)  — lo/hi two-load funnel off a computed base
|   - a NEGATIVE offset (byteDelta < 0) — memory bit-field offsets are signed 32-bit,
|                                         so the add must be a signed one
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.   FAIL: fall into _fail.

    .text
    .org 0
_start:
    | POISON A1.  This is load-bearing, not decoration.  EaDecoder gives EVERY mode-7
    | EA a nominal base of `8 + reg` and merely clears baseValid — so for (xxx).L
    | (mode 7, reg 1) the micro-op's srcAReg is literally A1, and rename resolves it to
    | A1's live value.  Nothing suppresses that on its own.  A test that left A1 at its
    | reset 0 therefore passes whether or not the base contribution is actually zeroed,
    | which is exactly how the first draft of this test fooled itself.  With A1 poisoned
    | an unsuppressed base lands the access 0x5A5A5A5A bytes away and every case below
    | reads back garbage.  (D0 is poisoned too, covering selRegHw's index-0 default for
    | any selector that resolves invalid without a nominal register of its own.)
    move.l  #0x5a5a5a5a, %a1
    move.l  #0x5a5a5a5a, %d0

    move.l  #0x40800200, %a0
    move.l  #0x12345678, (%a0)          | 0x40800200
    move.l  #0xaabbccdd, 4(%a0)         | 0x40800204

    | ======== read-only family ========

    | ---- BFEXTU 0x40800200{D2:#12} — offset 4 (byteDelta 0), width 12 -> 0x234 ----
    moveq   #4, %d2
    bfextu  0x40800200{%d2:#12}, %d3
    cmp.l   #0x234, %d3
    bne     _fail

    | ---- BFEXTU 0x40800200{D2:#16} — offset 16 (byteDelta 2) -> 0x5678 ----
    moveq   #16, %d2
    bfextu  0x40800200{%d2:#16}, %d3
    cmp.l   #0x5678, %d3
    bne     _fail

    | ---- BFEXTU 0x40800200{D2:#16} — offset 12: byteDelta 1, bitOff 4 (STRADDLE)
    | long at 0x40800201 = 0x345678AA; bits [27..12] = 0x4567.
    moveq   #12, %d2
    bfextu  0x40800200{%d2:#16}, %d3
    cmp.l   #0x4567, %d3
    bne     _fail

    | ---- NEGATIVE offset: 0x40800204{D2:#8} with D2 = -32 -> byteDelta -4,
    | so the field is the top byte of 0x40800200 = 0x12. ----
    move.l  #-32, %d2
    bfextu  0x40800204{%d2:#8}, %d3
    cmp.l   #0x12, %d3
    bne     _fail

    | ---- BFEXTS 0x40800204{D2:#8} — offset 0, top byte 0xAA, sign-extended ----
    moveq   #0, %d2
    bfexts  0x40800204{%d2:#8}, %d3
    cmp.l   #0xffffffaa, %d3
    bne     _fail

    | ---- BFFFO 0x40800200{D2:#16} — offset 0, field 0x1234; first set bit is at
    | index 3 within the field, and BFFFO returns offset+index = 3. ----
    moveq   #0, %d2
    bfffo   0x40800200{%d2:#16}, %d3
    cmp.l   #3, %d3
    bne     _fail

    | ---- BFTST 0x40800200{D2:#8} — offset 8 -> byte 0x34, non-zero -> Z clear ----
    moveq   #8, %d2
    bftst   0x40800200{%d2:#8}
    beq     _fail

    | ---- BFTST over a zero field: clear one byte first, then test it ----
    move.l  #0x12005678, (%a0)
    moveq   #8, %d2
    bftst   0x40800200{%d2:#8}
    bne     _fail
    move.l  #0x12345678, (%a0)          | restore

    | ======== read-modify-write family ========

    | ---- BFSET 0x40800204{D2:#4} — offset 8 (byteDelta 1): 0xAABBCCDD -> 0xAAFBCCDD
    moveq   #8, %d2
    bfset   0x40800204{%d2:#4}
    move.l  4(%a0), %d3
    cmp.l   #0xaafbccdd, %d3
    bne     _fail

    | ---- BFCLR 0x40800204{D2:#8} — offset 16 (byteDelta 2): -> 0xAAFB00DD ----
    moveq   #16, %d2
    bfclr   0x40800204{%d2:#8}
    move.l  4(%a0), %d3
    cmp.l   #0xaafb00dd, %d3
    bne     _fail

    | ---- BFCHG 0x40800200{D2:#8} — offset 24 (byteDelta 3): 0x78 ^ 0xFF = 0x87 ----
    moveq   #24, %d2
    bfchg   0x40800200{%d2:#8}
    move.l  (%a0), %d3
    cmp.l   #0x12345687, %d3
    bne     _fail

    | ---- BFINS D4,0x40800204{D2:#8} — offset 24: 0xAAFB00DD -> 0xAAFB005A ----
    moveq   #24, %d2
    move.l  #0x5a, %d4
    bfins   %d4, 0x40800204{%d2:#8}
    move.l  4(%a0), %d3
    cmp.l   #0xaafb005a, %d3
    bne     _fail

    | ---- BFINS straddling a byte boundary: offset 12, width 16 into 0x40800204.
    | 0xAAFB005A; the field is bits 4-7 of 0x205, all of 0x206 and bits 0-3
    | of 0x207, so 0x1234 lands as F1 23 4A -> 0xAAF1234A. ----
    moveq   #12, %d2
    move.l  #0x1234, %d4
    bfins   %d4, 0x40800204{%d2:#16}
    move.l  4(%a0), %d3
    cmp.l   #0xaaf1234a, %d3
    bne     _fail

    | ---- RMW with a NEGATIVE offset: 0x40800204{D2:#8} with D2 = -32 targets the
    | top byte of 0x40800200 (currently 0x12345687) -> BFCHG gives 0xED345687. ----
    move.l  #-32, %d2
    bfchg   0x40800204{%d2:#8}
    move.l  (%a0), %d3
    cmp.l   #0xed345687, %d3
    bne     _fail

_pass:
    move.l  #0xC0FFEE00, %d7
    move.l  #0xFFFF0000, %a0
    move.l  %d7, (%a0)
_halt:
    bra     _halt

_fail:
    move.l  #0xDEADBEEF, %d7
    move.l  #0xFFFF0000, %a0
    move.l  %d7, (%a0)
_fhalt:
    bra     _fhalt
