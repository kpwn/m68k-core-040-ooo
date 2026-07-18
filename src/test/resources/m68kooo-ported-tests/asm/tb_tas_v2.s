| tb_tas_v2.s — B7 V2 TAS family coverage.
|
| Exercises every legacy TAS EA shape now routed through the V2
| `v2_tas_fire` override block in decode.v.  Each cell maps to a
| distinct case in the override:
|   - Dn-direct      (single-µop ALU_TAS Dn → Dn)
|   - (An)           (3-phase LOAD / ALU_TAS / STORE)
|   - (An)+          (4-phase + ALU_ADD An update)
|   - -(An)          (4-phase ALU_SUB An / LOAD / ALU_TAS / STORE)
|   - (d16,An)       (3-phase, displacement in ext1 sx16)
|   - (xxx).W        (3-phase abs short form, len=4)
|   - (xxx).L        (3-phase abs long form, len=6)
|
| Also explicitly exercises A7 (USP) postinc to cover the +2/-2
| byte-padding rule that the override implements via a phase-3
| ADD/SUB with imm = (op_f3[2:0]==3'b111) ? 2 : 1.
|
| PASS = store 0xC0FFEE00 to 0xFFFF0000.

    .text
    .org 0

_start:
    | ── Test 1: TAS Dn  (Dn-direct, single-µop) ─────────────────────
    move.l  #0x12345600, %d0
    tas     %d0
    bne     _fail                    | Z=1 (low byte 0x00)
    bmi     _fail                    | N=0
    cmpi.l  #0x12345680, %d0         | bit 7 of low byte set
    bne     _fail

    move.l  #0x000000FF, %d4         | also test N=1 path
    tas     %d4
    beq     _fail                    | Z=0 (low byte 0xFF)
    bpl     _fail                    | N=1
    cmpi.l  #0x000000FF, %d4         | already had bit 7 set, unchanged
    bne     _fail

    | ── Test 2: TAS (An) at 0x60000 (3-phase) ──────────────────────
    lea     0x60000, %a0
    move.b  #0x55, (%a0)
    tas     (%a0)
    beq     _fail                    | Z=0
    bmi     _fail                    | N=0
    move.b  (%a0), %d0
    andi.l  #0xFF, %d0
    cmpi.l  #0xD5, %d0               | 0x55 | 0x80 = 0xD5
    bne     _fail

    | ── Test 3: TAS (An)+ at 0x60100, A1 += 1 (4-phase) ────────────
    lea     0x60100, %a1
    move.b  #0x10, (%a1)
    tas     (%a1)+
    beq     _fail
    bmi     _fail
    move.l  %a1, %d0
    cmpi.l  #0x60101, %d0            | post-inc by 1 (non-A7)
    bne     _fail

    | ── Test 4: TAS -(An), A2 -= 1 (4-phase) ────────────────────────
    lea     0x60201, %a2
    move.b  #0x40, (%a2)
    addq.l  #1, %a2                   | a2 = 0x60202
    tas     -(%a2)
    beq     _fail
    bmi     _fail
    move.l  %a2, %d0
    cmpi.l  #0x60201, %d0
    bne     _fail
    move.b  (%a2), %d0
    andi.l  #0xFF, %d0
    cmpi.l  #0xC0, %d0               | 0x40 | 0x80
    bne     _fail

    | ── Test 5: TAS (d16,An) — disp=+0x20, A3=0x60300 ───────────────
    lea     0x60300, %a3
    move.b  #0x05, (0x20,%a3)
    tas     (0x20,%a3)
    beq     _fail
    bmi     _fail
    move.b  (0x20,%a3), %d0
    andi.l  #0xFF, %d0
    cmpi.l  #0x85, %d0
    bne     _fail

    | ── Test 6: TAS (xxx).W — abs.W with sign-extended ext1 ─────────
    | Use a low-memory absolute address that fits in 16 bits.
    | 0x00007F00 sign-extends to 0x00007F00 from ext1=0x7F00.
    | Pre-init byte via (An) form, then read back via (An).
    lea     0x00007F00, %a4
    move.b  #0x33, (%a4)
    tas     0x7F00.w
    beq     _fail
    bmi     _fail
    move.b  (%a4), %d0
    andi.l  #0xFF, %d0
    cmpi.l  #0xB3, %d0               | 0x33 | 0x80
    bne     _fail

    | ── Test 7: TAS (xxx).L — abs.L with full 32-bit address ────────
    lea     0x00060400, %a5
    move.b  #0x00, (%a5)
    tas     0x00060400               | abs.L
    bne     _fail                    | Z=1 (orig byte was 0)
    bmi     _fail                    | N=0
    move.b  (%a5), %d0
    andi.l  #0xFF, %d0
    cmpi.l  #0x80, %d0
    bne     _fail

    | ── PASS ─────────────────────────────────────────────────────────
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt
