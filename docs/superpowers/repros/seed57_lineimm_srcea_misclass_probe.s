| seed57_lineimm_srcea_misclass_probe.s — fuzz seed 57 isolation, 2026-09-03
|
| HYPOTHESIS UNDER TEST (falsifiable, single-variable).
|
| Seed 57 minimizes to `ori.w #0x254a,(20,%a0,%a2.l*2)`, which takes a spurious exception
| and vectors to a wild PC. The destination EA is BRIEF format, so this is NOT fuzz
| cluster E (whose 3-entry Vec only ever lost a LONG base displacement).
|
| Proposed mechanism: `MicroOpAssembler`'s `srcEa` is decoded from `pkt.words(1)`. For a
| line-0 immediate, words(1) is THE IMMEDIATE, not the EA's extension word. `EaDecoder`'s
| mode-6 path then reads that immediate as if it were an extension word:
|     briefIsFull = extW(8)          -> bit 8 of the IMMEDIATE
|     fIis        = extW(2 downto 0) -> bits 2:0 of the IMMEDIATE
| so an immediate with bit8=1 and I/IS!=000 makes `srcEa.klass` come out MEMINDIRECT.
| `lineImmBad` (MicroOpAssembler.scala:1557) then rejects the instruction:
|     lineImmBad = isLineImm && !isBitOp && srcEa.klass != DATAREG
|                            && srcEa.klass != MEMSIMPLE && !isToCcr && !isToSr
| -> feeds the global `bad` -> vector 4.
|
| The code's own comment at the `immEa` re-decode asserts the opposite -- that the plain
| words(1)-based `srcEa` "still drives the operand CLASS (mode/reg are offset-independent)".
| That is true for modes 0-5 and 7-0/7-1/7-2, whose class depends only on the opword's
| mode/reg bits. It is FALSE for mode 6 and mode 7-3, whose class depends on bit8 and the
| I/IS field OF THE EXTENSION WORD. That assumption is the suspected defect.
|
| THE TEST. All three cases use a BYTE-IDENTICAL destination EA (same opword, same
| extension word, same base, same index, same scale). ONLY the immediate value differs, and
| it is chosen so the three land in three different decoded "classes" under the hypothesis:
|
|   #0x244a  bit8=0            -> misread as BRIEF        -> MEMSIMPLE   -> predict LEGAL
|   #0x2548  bit8=1, I/IS=000  -> misread as FULL, no ind -> MEMSIMPLE   -> predict LEGAL
|   #0x254a  bit8=1, I/IS=010  -> misread as FULL + indir -> MEMINDIRECT -> predict ILLEGAL
|
| If legality tracks the IMMEDIATE's bit pattern while the EA is held constant, the
| mechanism is confirmed. If all three pass, or all three trap, it is refuted -- and the
| refutation is as informative as the confirmation, so this probe is worth running either
| way. (Note: the ORIs accumulate into the same word; that is irrelevant here, the
| discriminator is legality, not the stored value.)
|
| Sentinels: 0xAAAA0?0? identifies which case trapped; 0xC0FFEE00 = all three legal.

    .text
    .org 0

_start:
    lea     0x00115f00, %a7
    lea     0x00000010, %a0           | vector 4 = Illegal Instruction
    move.l  #_illegal, (%a0)

    move.l  #0x2, %a2                 | index reg; scale 2 -> index term = 4
    | EA for every case below: 0x001153e8 + 20 + 4 = 0x00115400
    lea     0x00115400, %a0
    move.l  #0x00000000, (%a0)

    | ── Case A: immediate bit8 = 0 ──────────────────────────────────────────
    move.l  #0xAAAA0A0A, %d7
    move.l  #0x001153e8, %a0
    ori.w   #0x244a, (20,%a0,%a2.l*2)

    | ── Case B: immediate bit8 = 1, I/IS = 000 ──────────────────────────────
    move.l  #0xAAAA0B0B, %d7
    move.l  #0x001153e8, %a0
    ori.w   #0x2548, (20,%a0,%a2.l*2)

    | ── Case C: immediate bit8 = 1, I/IS = 010  (seed 57's exact immediate) ──
    move.l  #0xAAAA0C0C, %d7
    move.l  #0x001153e8, %a0
    ori.w   #0x254a, (20,%a0,%a2.l*2)

    move.l  #0xC0FFEE00, %d7
_pass:
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
_halt:
    bra     _halt

_illegal:
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
_halt_ill:
    bra     _halt_ill
