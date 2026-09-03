| imm_src_fullfmt_long_bd_dst.s — fuzz-to-zero-divergences campaign, cluster E (2026-09-03)
|
| Directed test for an IMMEDIATE-SOURCE instruction whose DESTINATION uses a
| full-format extension word with a LONG (32-bit) base displacement.
|
| ROOT CAUSE this covers: the immediate's own extension word(s) precede the
| destination EA's, so both re-decodes in MicroOpAssembler that account for that
| shift (`immEa`, the line-0 immediate mem-dest RMW address; `immDstEa`, the
| MOVE #imm,<mem> destination) built a THREE-entry words Vec.  EaDecoder's
| full-format path reads `words(2) ## words(3)` for a long base displacement, and
| `wAt` returns a hard 0 for any index past the Vec length -- so `words(3)` read
| as zero and the long bd silently lost its LOW half-word.
|
| MEASURED on the unfixed RTL (LsEuPlugin s1Va tap, fuzz seed 21's instruction
| `move.w #0x80,(0x10036,%a3,%d6.l*4)` with A3=0xFFFF3FF0, D6=-1):
|     base=0xFFFF3FF0  disp=0x00010000  index=0xFFFFFFFC  ->  s1Va=0x00003FEC
| against the architecturally correct
|     base=0xFFFF3FF0  disp=0x00010036  index=0xFFFFFFFC  ->  s1Va=0x00004022
| i.e. bd 0x00010036 decoded as 0x00010000.  The store lands 0x36 bytes low,
| SILENTLY -- no fault, no trap.  The register-source form of the same store
| (`move.w %d1,<same ea>`) was always correct: it uses the unshifted `dstEa`,
| which is built from the full 10-word window via `shiftedWordsFor`.
|
| DecodeStage.scala's own copies of this re-decode (`s0ImmEaVec`, `s1mi_immVec`)
| were already FOUR entries wide and therefore already correct -- the two
| MicroOpAssembler copies had silently drifted one word short.
|
| Note the 2^32 wraparound in cases B/C.  It is NOT part of the mechanism (the
| AGU sums three 32-bit SInts and truncates by construction), but it is what made
| fuzz seed 21 invisible for three rounds: with the wrap present, every candidate
| wrong address lands outside the lock-step-compared sandbox.  Kept here so the
| exact field case is covered, not just its simplified cousin.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    lea     0x00115f00, %a7

    | ── Case A: MOVE.W #imm -> (bd32,An,Xn.l*4), NO wraparound ──────────────
    |   A4 = 0x00100000, bd = 0x00015400, D6 = 0 (index 0) -> EA 0x00115400.
    |   Pre-fix bd decodes as 0x00010000 -> the store would land at 0x00110000.
    lea     0x00110000, %a0
    move.l  #0x11111111, (%a0)          | decoy: must stay untouched
    lea     0x00115400, %a0
    move.l  #0x22222222, (%a0)          | real target, low word overwritten below
    lea     0x00100000, %a4
    move.l  #0x00000000, %d6
    move.w  #0xBEEF, (0x00015400,%a4,%d6.l*4)
    lea     0x00115400, %a0
    move.l  (%a0), %d0
    cmp.l   #0xBEEF2222, %d0            | high word := 0xBEEF, low word kept
    beq     _a_decoy
    move.l  #0xDEAD00A1, %d7
    bra     _fail
_a_decoy:
    lea     0x00110000, %a0
    move.l  (%a0), %d0
    cmp.l   #0x11111111, %d0            | decoy byte-identical
    beq     _caseB
    move.l  #0xDEAD00A2, %d7
    bra     _fail

    | ── Case B: same, but An + bd WRAPS past 2^32 (fuzz seed 21's shape) ────
    |   A3 = 0xFFF00000, bd = 0x00215400, D6 = -1 scaled *4 -> index -4.
    |   0xFFF00000 + 0x00215400 = 0x1_00115400 -> truncated 0x00115400,
    |   then -4 -> EA 0x001153FC.
    |   Pre-fix bd = 0x00210000 -> 0x1_00110000 -> 0x00110000, -4 -> 0x0010FFFC.
_caseB:
    lea     0x0010fffc, %a0
    move.l  #0x33333333, (%a0)          | decoy
    lea     0x001153fc, %a0
    move.l  #0x44444444, (%a0)          | real target
    lea     0xfff00000, %a3
    move.l  #0xffffffff, %d6
    move.w  #0xCAFE, (0x00215400,%a3,%d6.l*4)
    lea     0x001153fc, %a0
    move.l  (%a0), %d0
    cmp.l   #0xCAFE4444, %d0
    beq     _b_decoy
    move.l  #0xDEAD00B1, %d7
    bra     _fail
_b_decoy:
    lea     0x0010fffc, %a0
    move.l  (%a0), %d0
    cmp.l   #0x33333333, %d0
    beq     _caseC
    move.l  #0xDEAD00B2, %d7
    bra     _fail

    | ── Case C: LINE-0 immediate RMW -> the OTHER re-decode (`immEa`) ───────
    |   ORI.W #imm,(bd32,An,Xn.l*4).  Here the shifted view supplies the RMW
    |   load AND store address, so a short Vec sends the whole read-modify-write
    |   to the wrong place -- silently corrupting one location and leaving the
    |   intended one stale.
_caseC:
    lea     0x00110010, %a0
    move.l  #0x55555555, (%a0)          | decoy
    lea     0x00115410, %a0
    move.l  #0x0F0F6666, (%a0)          | real target
    lea     0x00100000, %a4
    move.l  #0x00000004, %d6            | index = 4*4 = 16
    ori.w   #0xF000, (0x00015400,%a4,%d6.l*4)
    lea     0x00115410, %a0
    move.l  (%a0), %d0
    cmp.l   #0xFF0F6666, %d0            | 0x0F0F | 0xF000 = 0xFF0F
    beq     _c_decoy
    move.l  #0xDEAD00C1, %d7
    bra     _fail
_c_decoy:
    lea     0x00110010, %a0
    move.l  (%a0), %d0
    cmp.l   #0x55555555, %d0
    beq     _caseD
    move.l  #0xDEAD00C2, %d7
    bra     _fail

    | ── Case D: a LONG immediate, so the shift is TWO words, not one ────────
    |   MOVE.L #imm,(bd32,An,Xn.l*2).  Exercises the immIsLong leg of the same
    |   shifted view.
_caseD:
    lea     0x00110020, %a0
    move.l  #0x77777777, (%a0)          | decoy
    lea     0x00115420, %a0
    move.l  #0x88888888, (%a0)          | real target
    lea     0x00100000, %a4
    move.l  #0x00000010, %d6            | index = 16*2 = 32
    move.l  #0x1234ABCD, (0x00015400,%a4,%d6.l*2)
    lea     0x00115420, %a0
    move.l  (%a0), %d0
    cmp.l   #0x1234ABCD, %d0
    beq     _d_decoy
    move.l  #0xDEAD00D1, %d7
    bra     _fail
_d_decoy:
    lea     0x00110020, %a0
    move.l  (%a0), %d0
    cmp.l   #0x77777777, %d0
    beq     _pass
    move.l  #0xDEAD00D2, %d7
    bra     _fail

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
_halt_fail:
    bra     _halt_fail
