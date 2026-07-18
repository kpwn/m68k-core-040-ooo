| adda_mem_jmp_indexed.s — ADDA.L <ea>,An memory forms + JMP (d8,An,Xn.L)
|
| Covers two decode gaps found during ROM bring-up (rom-boot-decode-adda-mem
| + rom-boot-decode-jmp-indexed).  Before this landing the Quadra 700 ROM
| halted at PC 0x2f60 because:
|   - 0x2f5a: ADDA.L (A1)+, A0 decoded as NOP (memory-source missing)
|   - 0x2f60: JMP (d8,A0,A2.L) decoded as NOP (indexed EA missing)
| Both gaps are now cracked to multi-µop TMP1 staging; this test
| exercises each shape directly.
|
| PASS sentinel: 0xC0FFEE00 → 0xFFFF0000.

    .text
    .org 0

_start:
    | ── 1. ADDA.L (A1)+, A0  — the exact ROM shape at 0x2f5a ───────────
    | Stage [0x00100000] = 0x00000460; A0 = 0x00200000, A1 = 0x00100000.
    | Expected: A0 += 0x460 → 0x00200460; A1 += 4 → 0x00100004.
    move.l  #0x00000460, %d0
    move.l  #0x00100000, %a1
    move.l  %d0, (%a1)
    move.l  #0x00200000, %a0
    adda.l  (%a1)+, %a0
    cmpa.l  #0x00200460, %a0
    bne     _fail
    cmpa.l  #0x00100004, %a1
    bne     _fail

    | ── 2. ADDA.L (A1), A0 — plain indirect ───────────────────────────
    move.l  #0x00000008, %d0
    move.l  #0x00100040, %a1
    move.l  %d0, (%a1)
    move.l  #0x00200000, %a0
    adda.l  (%a1), %a0
    cmpa.l  #0x00200008, %a0
    bne     _fail
    | A1 should be unchanged (no postinc)
    cmpa.l  #0x00100040, %a1
    bne     _fail

    | ── 3. ADDA.L -(A1), A0 — predecrement ────────────────────────────
    move.l  #0x00000010, %d0
    move.l  #0x00100080, %a1
    move.l  %d0, (%a1)
    move.l  #0x00100084, %a1               | point one past
    move.l  #0x00200000, %a0
    adda.l  -(%a1), %a0
    cmpa.l  #0x00200010, %a0
    bne     _fail
    cmpa.l  #0x00100080, %a1
    bne     _fail

    | ── 4. ADDA.L (d16,A1), A0 — displaced ────────────────────────────
    move.l  #0x00000020, %d0
    move.l  #0x00100100, %a1
    move.l  %d0, 16(%a1)                   | [A1+16] = 0x20
    move.l  #0x00200000, %a0
    adda.l  16(%a1), %a0
    cmpa.l  #0x00200020, %a0
    bne     _fail

    | ── 5. JMP (d8, A0, A2.L) — the exact ROM shape at 0x2f60 ─────────
    | Compute target = A0 + A2 + 0, jump there.  Place a sentinel store
    | past the JMP; if JMP fails we'll fall through and fail.
    move.l  #_jmp_target, %a0
    | Subtract some offset from A0 to exercise the add.
    suba.l  #0x40, %a0                     | A0 = _jmp_target - 0x40
    move.l  #0x40, %a2                     | A2 = 0x40, so A0 + A2 = target
    jmp     (%a0, %a2.l)
    | Should never fall through.  If it does, fail.
    bra     _fail

_jmp_target:
    | ── 6. JMP (d16, An) — displaced register-indirect ────────────────
    move.l  #_jmp_target2, %a0
    suba.l  #0x100, %a0                    | A0 = target - 0x100
    jmp     0x100(%a0)                     | d16 = 0x100
    bra     _fail

_jmp_target2:
    | ── 7. JMP (d8, An, Xn.L) with non-zero displacement ──────────────
    | target = A0 + A2 + disp8
    move.l  #_jmp_target3, %a0
    suba.l  #0x30, %a0
    move.l  #0x20, %a2
    jmp     0x10(%a0, %a2.l)               | A0+A2+0x10 = target
    bra     _fail

_jmp_target3:
    | ── 8. JMP (d8, An, Dn.L) — Xn in Dn space ────────────────────────
    move.l  #_jmp_target4, %a0
    suba.l  #0x80, %a0
    move.l  #0x80, %d3                     | Dn as index
    jmp     (%a0, %d3.l)
    bra     _fail

_jmp_target4:
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
