| rom_boot_loop_exit.s — MOVE.L (An)+,Dn + LEA (d8,An,Xn.L*1),An + CMP.B mem
|
| Mimics the Quadra 700 ROM config-walker loop at PC 0x2f7c..0x2f88
| that previously hung the rom-boot harness (docs/rom_boot_bringup.md §4d).
| The loop body was:
|
|     0x2f7c  2018       MOVE.L (A0)+, D0
|     0x2f7e  67d2       BEQ.S  ...        (exit on null entry)
|     0x2f80  43f0 08fc  LEA    (d8,A0,D0.L*1), A1
|     0x2f84  b429 0013  CMP.B  (0x13,A1), D2
|     0x2f88  66f2       BNE.S  -0xe      (retry from 0x2f7c)
|
| Before the rom-boot-decode-movel-postinc-cmp-b landing, the MOVE.L
| (An)+ decoded as UOP_NOP (only reg/#imm/(An)/(d16,An)/(xxx).W/.L were
| handled in the 4'b0010 block), and the LEA indexed mode 6 was NOT
| decoded at all — consuming only the 2-byte opword and leaving the
| 16-bit extension word to be interpreted as a bogus subsequent opword.
| CMP.B (d16,An),Dn was already decoded (task #102) so this test
| primarily exercises the two new cracks plus regression-guards the
| existing byte CMP path.
|
| PASS sentinel: 0xC0FFEE00 → 0xFFFF0000.

    .text
    .org 0

_start:
    | ── 1. MOVE.L (A0)+, Dn — the 0x2f7c ROM shape ─────────────────────
    | Stage [0x00100000] = 0xDEADBEEF; A0 = 0x00100000.
    | Expected: D0 = 0xDEADBEEF; A0 += 4 → 0x00100004.
    move.l  #0xDEADBEEF, %d0
    move.l  #0x00100000, %a1
    move.l  %d0, (%a1)
    move.l  #0x00100000, %a0
    move.l  #0xAAAAAAAA, %d0        | prime D0 with a different value
    move.l  (%a0)+, %d0
    cmp.l   #0xDEADBEEF, %d0
    bne     _fail
    cmpa.l  #0x00100004, %a0
    bne     _fail

    | ── 2. MOVE.L (A0)+, Dn — flag semantics (Z=1 when loaded word is 0)
    | The ROM loop's BEQ at 0x2f7e depends on this exact flag update.
    move.l  #0x00100020, %a1
    move.l  #0x00000000, %d1
    move.l  %d1, (%a1)              | store zero
    move.l  #0x00100020, %a0
    move.l  #0x77777777, %d0        | prime D0 non-zero
    move.l  (%a0)+, %d0             | D0 = 0; Z=1 expected
    beq     _pass_m2                | must take this
    bra     _fail
_pass_m2:
    cmpa.l  #0x00100024, %a0
    bne     _fail

    | ── 3. MOVE.L (A0)+, Dn — negative value sets N=1 ──────────────────
    move.l  #0x00100030, %a1
    move.l  #0x80000001, %d1
    move.l  %d1, (%a1)
    move.l  #0x00100030, %a0
    move.l  (%a0)+, %d0             | D0 = 0x80000001; N=1
    bmi     _pass_m3                | take when N=1
    bra     _fail
_pass_m3:

    | ── 4. LEA (d8,A0,D0.L*1), A1 — the 0x2f80 ROM shape ──────────────
    | Target computation: A1 = A0 + D0 + sx8(disp).
    | A0 = 0x00200000, D0 = 0x00001000, disp = -0x10 (0xF0 signed).
    | Expected A1 = 0x00200000 + 0x1000 + 0xFFFFFFF0 = 0x00200FF0.
    | No flag side-effect (LEA never touches CCR).
    move.l  #0x00200000, %a0
    move.l  #0x00001000, %d0
    lea     -0x10(%a0, %d0.l), %a1
    cmpa.l  #0x00200FF0, %a1
    bne     _fail

    | ── 5. LEA (d8,A0,A2.L*1), A1 — Xn.L from An slot (ext1[15]=1) ────
    | Exercises the A/D bit decoding — ROM loop uses D0 (ext1[15]=0);
    | the JMP indexed landing (§4b) already tested A2; re-verify the
    | LEA path doesn't mis-decode the A/D bit.
    move.l  #0x00300000, %a0
    move.l  #0x00002000, %a2
    lea     0x40(%a0, %a2.l), %a1
    cmpa.l  #0x00302040, %a1
    bne     _fail

    | ── 6. LEA (d8,An,Xn.L*1), An — positive disp, zero index ─────────
    move.l  #0x00400000, %a0
    move.l  #0x00000000, %d1
    lea     0x7F(%a0, %d1.l), %a3
    cmpa.l  #0x0040007F, %a3
    bne     _fail

    | ── 7. CMP.B (d16,A1), Dn — the 0x2f84 ROM shape ──────────────────
    | Stage memory: [0x00500010..0x00500013] = 0xDEADBE55.  A1 = 0x00500000.
    | The byte at offset 0x13 (big-endian) is 0x55.  D2 = 0x55.
    | CMP.B 0x13(A1), D2 → Z=1, BNE not taken.
    | (Byte CMP already decoded via task #102; re-assert it works in
    | the exact ROM-loop combination.  Avoids MOVE.B #imm8,(d16,An)
    | which is a separate undecoded gap outside this task's scope.)
    move.l  #0x00500000, %a1
    move.l  #0xDEADBE55, %d0        | low byte = 0x55 at offset 0x13 (BE)
    move.l  %d0, 0x10(%a1)          | [0x00500010..0x00500013] = 0xDEADBE55
    move.l  #0xFFFFFF55, %d2        | Dn low byte = 0x55, upper garbage
    cmp.b   0x13(%a1), %d2
    bne     _fail                   | must be Z=1

    | Mismatch case: D2 != mem byte → Z=0
    move.l  #0xFFFFFF66, %d2
    cmp.b   0x13(%a1), %d2
    beq     _fail                   | must be Z=0

    | ── 8. Integrated ROM-loop-shape walk ─────────────────────────────
    | Emulate the ROM's walker pattern directly: stage a small table,
    | iterate a pointer through it with MOVE.L (An)+, compute a secondary
    | pointer with LEA (d8,An,Xn.L*1), byte-compare, and exit when the
    | match sentinel is found.
    | Table:  [0x00600000] = 0x00000004  (relative idx → LEA base)
    |         [0x00600004] = 0x00000008  (entry 1)
    |         [0x00600008] = 0x00000000  (sentinel NULL → BEQ exits)
    | Secondary pointer: A1 = A0_pre + entry + 0  (disp=0 for simplicity)
    |                    byte at A1+0x13 = 0xAA, D2 = 0xAA → match (Z=1)
    move.l  #0x00600000, %a0
    move.l  #0x00000004, (%a0)
    move.l  #0x00000008, 4(%a0)
    move.l  #0x00000000, 8(%a0)     | null terminator

    | Stage the target byte: (A0 + some entry) + 0x13 = 0xAA.
    | For entry 0 (value=4), after MOVE.L (A0)+ A0 becomes 0x00600004 and
    | LEA 0(%a0, %d0.l) with d0=4 → A1 = 0x00600008. [0x0060001B] = 0xAA.
    | We write via MOVE.L to a word that overlaps offset 0x1B (BE):
    |   [0x00600018..0x0060001B] = 0xCAFEFEAA → byte at 0x1B = 0xAA.
    move.l  #0xCAFEFEAA, %d0
    move.l  %d0, 0x18(%a0)          | avoids MOVE.B #imm8,(d16,An)
    move.l  #0xFFFFFFAA, %d2

_loop:
    move.l  (%a0)+, %d0             | entry; A0 += 4
    beq     _pass                   | NULL → loop exits, test passes
    lea     0(%a0, %d0.l), %a1      | A1 = A0_post + entry
    cmp.b   0x13(%a1), %d2          | match byte at (A1+0x13)
    bne     _loop                   | mismatch → next entry
    | A match before the NULL would fall through — but our staging puts
    | the match at entry 0, so fall-through here is PASS (matches ROM
    | pattern where the loop exits on match via a different path).
    bra     _pass

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
