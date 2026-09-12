| fpu_store_then_cmp_bcc.s — after an FP store, CMP.L + Bcc branches the WRONG WAY
|                            even though the same CMP's Z flag is CORRECT.
|
| Minimal reproducer, isolated 2026-09-12 by differential probing. The three
| facts that pin it down, each verified as a separate program:
|
|   1. DATA IS CORRECT.  A branch-free check of all three longwords of the
|      FMOVEM.X round-trip (sum of per-chunk deltas, no cmp/Bcc anywhere)
|      lands exactly on the pass sentinel.  So the FMOVEM.X load and store
|      both deliver the right bytes.
|   2. THE FLAG IS CORRECT.  Identical code ending in `SEQ %d4` instead of a
|      branch reports 0xFF -- CMP.L set Z properly.
|   3. THE BRANCH IS WRONG.  Swap that SEQ for a BEQ and the branch is NOT
|      taken: the fall-through `bset` commits and bit 0 comes back set.
|
| And the same BEQ pattern with NO FP anywhere passes, so this is not a
| general branch-squash or wrong-path-commit defect -- it needs the FP store.
|
| This matters well beyond the corpus.  It was found while chasing a live
| board crash: the Q700 FPSP at ROM 0x408ec664 does
|     fmovemx %sp@+,%fp0
|     fdivx   %sp@,%fp0
| and FP-heavy ROM paths are full of store-then-test sequences.  A conditional
| branch that resolves the wrong way inside the FPSP sends control somewhere
| the OS never intended -- which is exactly the "wild jump into a pointer
| table" signature captured on hardware (PC marching 4 bytes at a time through
| low memory until an illegal opcode traps).
|
| DO NOT "fix" this by changing the test to use Scc.  The Bcc form is the one
| real code uses.
|
| PASS sentinel: 0xC0FFEE00
| FAIL sentinels:
|   0xC0FFEE01 — the BEQ was not taken though Z was set (THE BUG)
|   0xDEAD0F11 — vec 11 F-line fired (FMOVEM.X (d16,PC) did not decode)

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000
    .equ OBUF,      0x00022100

_start:
    lea     0x00010000, %a7
    move.l  #_fline, 0x0000002C
    moveq   #0, %d3
    bra     _go
    .align  2
_pcdata:
    .long   0x40020000
    .long   0xDEC0DE01
    .long   0x0FF1CE02
_go:
_ref:
    .short  0xF23A, 0xD080             | FMOVEM.X (d16,PC),FP0
    .short  _pcdata - (_ref + 4)

    lea     OBUF+64, %a1
    move.l  #0xDEADBEEF, 0(%a1)
    move.l  #0xDEADBEEF, 4(%a1)
    move.l  #0xDEADBEEF, 8(%a1)
    fmovem.x %fp0, %a1@                | the FP STORE

    move.l  0(%a1), %d0                | read chunk 0 back (known-correct data)
    cmp.l   #0x40020000, %d0           | Z must be set (verified via SEQ)
    beq     _ok                        | ... so this MUST be taken
    bset    #0, %d3                    | wrong path
_ok:
    move.l  #0xC0FFEE00, %d2
    or.l    %d3, %d2
    lea     PASS_SENT, %a2
    move.l  %d2, (%a2)
_halt:
    bra     _halt

_fline:
    move.l  #0xDEAD0F11, %d2
    lea     PASS_SENT, %a2
    move.l  %d2, (%a2)
_halt_f:
    bra     _halt_f
