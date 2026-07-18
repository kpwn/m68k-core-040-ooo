| moveb_shift_bw_basic.s — MOVE.B (d16,An),Dn + byte/word shifts.
|
| Exercises the decode paths added for rom-boot-decode-moveb-movea-idx:
|   - MOVE.B (d16,An),Dn   — byte mem-source (2-uop crack: LOAD TMP1 + merge)
|   - ASL.B #imm,Dn        — byte immediate-count arithmetic shift left
|   - ASR.B #imm,Dn        — byte immediate-count arithmetic shift right
|   - LSL.W #imm,Dn        — word immediate-count logical shift left
|   - LSR.W #imm,Dn        — word immediate-count logical shift right
|   - ASL.W Dn,Dm          — word register-count shift
|
| The Q700 ROM path at 0x47ba..0x47dc uses MOVE.B (d16,A1),D2 and
| LSL.W/LSR.W #8,D2 to extract and reassemble bytes of a ROM constant.
| This test mirrors that pattern at a scale the directed framework can
| check, then samples flag bits and full Dn values.  Byte/word register
| writes must preserve the upper bytes of Dn; the ROM hardware-descriptor
| feature word depends on that real 68k partial-register behavior.
|
| PASS sentinel: 0xC0FFEE00 → 0xFFFF0000.

    .text
    .org 0

_start:
    | ── 1. MOVE.B (d16,A0),D0 ─────────────────────────────────────────
    | Prime (0x00100010 + 0x20 = 0x00100030) with a byte 0x5A; read it
    | into D0 via MOVE.B 0x20(A0),D0.  Low byte of D0 should be 0x5A,
    | and N/Z/V/C should reflect the positive byte (N=0 Z=0 V=0 C=0).
    move.l  #0x5A000000, 0x00100030            | byte at 0x100030 = 0x5A
    move.l  #0x00100010, %a0
    move.l  #0xDEADBEEF, %d0                   | pre-seed D0 so we can
                                               | detect low-byte replace
    move.b  0x20(%a0), %d0                     | D0.B ← 0x5A
    cmp.l   #0xDEADBE5A, %d0
    bne     _fail

    | ── 2. MOVE.B with negative source (N flag should set) ────────────
    move.l  #0x80000000, 0x00100034            | byte at 0x100034 = 0x80
    move.l  #0x00100014, %a0
    move.b  0x20(%a0), %d1                     | D1.B ← 0x80 (N=1 after)
    bpl     _fail                               | BPL if N clear -> fail

    | ── 3. MOVE.B with zero source (Z flag should set) ────────────────
    move.l  #0x00000000, 0x00100038            | byte at 0x100038 = 0x00
    move.l  #0x00100018, %a0
    move.l  #0xAAAAAAAA, %d2
    move.b  0x20(%a0), %d2                     | D2.B ← 0x00 (Z=1 after)
    bne     _fail                               | BNE if Z clear -> fail
    cmp.l   #0xAAAAAA00, %d2
    bne     _fail

    | ── 4. ASL.B #3, Dn ───────────────────────────────────────────────
    | 0x12 << 3 = 0x90 (byte).  N=1 after (0x90 MSB set).
    move.l  #0x12340012, %d3
    asl.b   #3, %d3
    cmp.l   #0x12340090, %d3
    bne     _fail

    | ── 5. ASR.B #2, Dn ───────────────────────────────────────────────
    | 0x40 (positive) >> 2 = 0x10.  N=0 Z=0.
    move.l  #0xABCD0040, %d4
    asr.b   #2, %d4
    cmp.l   #0xABCD0010, %d4
    bne     _fail

    | ── 6. LSL.W #8, Dn  (rom-boot shape) ─────────────────────────────
    | Start Dn = 0xCAFE00AB; LSL.W #8 gives Dn = 0xCAFEAB00.
    move.l  #0xCAFE00AB, %d5
    lsl.w   #8, %d5
    cmp.l   #0xCAFEAB00, %d5
    bne     _fail

    | ── 7. LSR.W #8, Dn ───────────────────────────────────────────────
    | Start Dn = 0xBEEFCD00; LSR.W #8 gives Dn = 0xBEEF00CD.
    move.l  #0xBEEFCD00, %d6
    lsr.w   #8, %d6
    cmp.l   #0xBEEF00CD, %d6
    bne     _fail

    | ── 8. ASL.W Dn,Dm (register-count) ───────────────────────────────
    | D0 ← 4 (shift count).  D7.W = 1.  ASL.W D0,D7 preserves D7[31:16].
    moveq   #4, %d0
    move.l  #0xFACE0001, %d7
    asl.w   %d0, %d7
    cmp.l   #0xFACE0010, %d7
    bne     _fail

    | ── 9. ROM feature-word byte splice ───────────────────────────────
    | Mirrors 0x47dc..0x47e6: LSL.W #8,D1 then MOVE.B D2,D1 must keep
    | the shifted byte in D1[15:8].
    move.l  #0x000000C1, %d1
    lsl.w   #8, %d1
    move.l  #0x000000E8, %d2
    move.b  %d2, %d1
    cmp.l   #0x0000C1E8, %d1
    bne     _fail

    | ── 10. ROM feature-word memory splice ────────────────────────────
    | Mirrors 0x47f0: MOVE.B (A0),D1 must preserve the byte shifted into
    | D1[15:8].  This is the final VIA1 feature-byte construction step.
    move.l  #0xE8000000, 0x00100040            | byte at 0x100040 = 0xE8
    move.l  #0x00100040, %a0
    move.l  #0x000000C1, %d1
    lsl.w   #8, %d1
    move.b  (%a0), %d1
    cmp.l   #0x0000C1E8, %d1
    bne     _fail

    | ── 11. Full ROM VIA-style byte probe ─────────────────────────────
    | This mirrors 0x47ba..0x47fa closely enough to cover the surrounding
    | read/modify/write traffic that exposed the descriptor feature bug.
    move.l  #0xE8000000, 0x00102000            | ORB byte at base+0x0000
    move.l  #0x00000000, 0x00102400            | DDRB byte at base+0x0400
    move.l  #0x00000000, 0x00102600            | DDRA byte at base+0x0600
    move.l  #0xC1000000, 0x00103E00            | ORA-NH byte at base+0x1e00
    move.l  #0x00102000, %a1
    moveq   #0, %d1
    move.b  0x1e00(%a1), %d2
    lsl.w   #8, %d2
    move.b  0x0600(%a1), %d2
    move.b  %d2, %d1
    and.b   #0x00, %d1
    move.b  %d1, 0x0600(%a1)
    move.b  0x1e00(%a1), %d1
    move.b  %d2, 0x0600(%a1)
    lsr.w   #8, %d2
    move.b  %d2, 0x1e00(%a1)
    lsl.w   #8, %d1
    move.b  (%a1), %d2
    lsl.w   #8, %d2
    move.b  0x0400(%a1), %d2
    move.b  %d2, %d1
    and.b   #0xC0, %d1
    move.b  %d1, 0x0400(%a1)
    move.b  (%a1), %d1
    move.b  %d2, 0x0400(%a1)
    lsr.w   #8, %d2
    move.b  %d2, (%a1)
    cmp.l   #0x0000C1E8, %d1
    bne     _fail

    | ── PASS ──────────────────────────────────────────────────────────
_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d2
    move.l  %d2, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d2
    move.l  %d2, (%a0)
_halt_fail:
    bra     _halt_fail
