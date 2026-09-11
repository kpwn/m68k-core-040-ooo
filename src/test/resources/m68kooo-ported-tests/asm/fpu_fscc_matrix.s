| fpu_fscc_matrix.s — FScc <ea>: set a BYTE to 0xFF/0x00 on an FP condition.
|
| FScc is `1111 001 001 mmmrrr` + a condition extension word (cc in ext[5:0]).  It is an
| Scc whose predicate reads FPCC instead of the integer CCR, so it reuses the branch-EU
| condition write wholesale; the only new parts are the condition SOURCE (the extension
| word — invisible to OperationDecoder, hence decoded in MicroOpAssembler) and the <ea>
| extension words being shifted one word later than a line-5 Scc's, because the condition
| word comes first.
|
| Scope mirrors the integer isSccOp gate: mode 1 (An direct) is FDBcc and mode 7 reg>=2 is
| FTRAPcc — both still F-line traps, and both are checked below to stay that way.
|
| Encodings used:
|   FMOVE.L Dn,FPm   = F200|<ea=Dn>  , ext 0x4000 | FPm<<7
|   FCMP.X  FPm,FPn  = F200          , ext (FPm<<10)|(FPn<<7)|0x38   -> FPCC = FPn-FPm
|   FScc    Dn       = F240|reg      , ext = cc
|   FScc    (An)     = F250|reg      , ext = cc
|
| PASS sentinel: 0xC0FFEE00.   FAIL: 0xDEAD00<nn>, one per check; 0xDEAD007F = F-line trap.
| The two MEMORY checks instead report 0xDEAD05<bb> / 0xDEAD06<bb>, where <bb> is the byte
| ACTUALLY found at the destination — which is what identified the original defect here (a
| LONG-sized trailing store put T1's 0x00 MSB at the address, so a TRUE condition read back
| as 0), rather than merely saying "the memory form is wrong".

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000

_start:
    lea     0x00010000, %a7
    move.l  #_fline, 0x0000002C
    lea     _scratch, %a2

    | FP0 = 1.0, FP1 = 2.0 -> FCMP FP1,FP0 gives N=1, Z=0, NAN=0
    moveq   #1, %d0
    moveq   #2, %d1
    .short  0xF200, 0x4000             | FMOVE.L D0,FP0
    .short  0xF201, 0x4080             | FMOVE.L D1,FP1
    .short  0xF200, 0x0438             | FCMP.X  FP1,FP0

    | ---- FSOLT D3 : N & !(NAN|Z) = true -> 0xFF ----
    move.l  #0x11223344, %d3
    .short  0xF243, 0x0004             | FSOLT D3
    cmp.l   #0x112233FF, %d3           | byte-sized: upper 24 bits PRESERVED
    bne     _f01

    | ---- FSOGT D3 : false -> 0x00, upper bytes still preserved ----
    .short  0xF243, 0x0002             | FSOGT D3
    cmp.l   #0x11223300, %d3
    bne     _f02

    | ---- FSEQ D3 : Z=0 -> 0x00 ----
    move.l  #0xAABBCCDD, %d3
    .short  0xF243, 0x0001             | FSEQ D3
    cmp.l   #0xAABBCC00, %d3
    bne     _f03

    | ---- FSNE D3 : !Z -> 0xFF ----
    .short  0xF243, 0x000E             | FSNE D3
    cmp.l   #0xAABBCCFF, %d3
    bne     _f04

    | ---- memory destination (A2): FSOLT (A2) -> 0xFF ----
    move.b  #0x5A, (%a2)
    .short  0xF252, 0x0004             | FSOLT (A2)
    cmp.b   #0xFF, (%a2)
    bne     _f05

    | ---- memory destination: FSOGT (A2) -> 0x00 ----
    .short  0xF252, 0x0002             | FSOGT (A2)
    cmp.b   #0x00, (%a2)
    bne     _f06

    | ===== unordered: FP0 = NaN =====
    lea     _nan_s, %a0
    .short  0xF210, 0x4400             | FMOVE.S (A0),FP0
    .short  0xF200, 0x0438             | FCMP.X  FP1,FP0  -> unordered

    | ---- FSUN D3 : NAN -> 0xFF ----
    .short  0xF243, 0x0008             | FSUN D3
    cmp.l   #0xAABBCCFF, %d3
    bne     _f07

    | ---- FSOGT D3 : an ORDERED condition under NaN -> 0x00 ----
    .short  0xF243, 0x0002             | FSOGT D3
    cmp.l   #0xAABBCC00, %d3
    bne     _f08

    | ---- FSULT D3 : an UNORDERED condition under NaN -> 0xFF ----
    .short  0xF243, 0x000C             | FSULT D3
    cmp.l   #0xAABBCCFF, %d3
    bne     _f09

    | ---- the signaling alias FSST (0x1F) is "always true" -> 0xFF ----
    .short  0xF243, 0x001F             | FSST D3
    cmp.l   #0xAABBCCFF, %d3
    bne     _f10

    | ---- FSSF (0x10) is "always false" -> 0x00 (the encoding whose cc[3:0] is 0) ----
    .short  0xF243, 0x0010             | FSSF D3
    cmp.l   #0xAABBCC00, %d3
    bne     _f11

_pass:
    lea     PASS_SENT, %a1
    move.l  #0xC0FFEE00, %d2
    move.l  %d2, (%a1)
_halt:
    bra     _halt

_f01: moveq #0x01, %d2
    bra _report
_f02: moveq #0x02, %d2
    bra _report
_f03: moveq #0x03, %d2
    bra _report
_f04: moveq #0x04, %d2
    bra _report
_f05: move.b (%a2), %d2
    and.l #0xFF, %d2
    or.l #0x0500, %d2
    bra _report
_f06: move.b (%a2), %d2
    and.l #0xFF, %d2
    or.l #0x0600, %d2
    bra _report
_f07: moveq #0x07, %d2
    bra _report
_f08: moveq #0x08, %d2
    bra _report
_f09: moveq #0x09, %d2
    bra _report
_f10: moveq #0x10, %d2
    bra _report
_f11: moveq #0x11, %d2
    bra _report
_fline: moveq #0x7F, %d2

_report:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0000, %d3
    or.l    %d2, %d3
    move.l  %d3, (%a1)
_fhalt:
    bra     _fhalt

    .align  2
_nan_s:
    .long   0x7FC00000
_scratch:
    .long   0
