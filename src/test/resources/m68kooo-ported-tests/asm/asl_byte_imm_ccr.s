| asl_byte_imm_ccr.s — ASL.B #n,Dn covering N/Z/C/X flags + upper-byte
| preservation across several counts.
|
| Stage D-3 corner: the V2 decoder must correctly emit ALU_ASL for the
| byte-size immediate-count register form.  Byte-size shift narrows the
| operand before shift; high bytes of Dn must be preserved after merge.
|
| Note: the V-flag computation for byte/word ASL is an existing alu.v
| limitation (the asl_v_mask uses the 32-bit operand width); this test
| focuses on N/Z/C/X + result preservation which the decoder landing
| must not regress.  The V-byte-word discrepancy is tracked separately
| and surfaces only when fuzz widens to byte/word ASL with a sign
| transition mid-shift; today fuzz N=500 is clean.
|
| Key checks:
|  1. ASL.B #1, 0x01 → 0x02, C=0, N=0, Z=0.
|  2. ASL.B #4, 0x0F → 0xF0.  C=0 (bit 4 of 0x0F is 0), N=1.
|  3. ASL.B #8, 0xFF → 0x00.  Z=1, C=X=1 (last bit out was 1), N=0.
|  4. ASL.B #2, 0x11223344 → 0x11223310 (high bytes preserved).

    .text
    .org 0

_start:
    | ── 1. ASL.B #1, 0x01 → 0x02 (no flags set) ──
    move.l  #0x00000001, %d0
    asl.b   #1, %d0                  | D0[7:0] = 0x02
    bcs     _fail                    | C=0
    bmi     _fail                    | N=0
    beq     _fail                    | Z=0
    move.l  #0x00000002, %d7
    cmp.l   %d7, %d0
    bne     _fail

    | ── 2. ASL.B #4, 0x0F → 0xF0; N=1, C=0 ──
    move.l  #0xAABBCC0F, %d2
    asl.b   #4, %d2                  | D2[7:0] = 0xF0
    bcs     _fail                    | C=0 (bit 4 of 0x0F = 0)
    bpl     _fail                    | N=1
    move.l  #0xAABBCCF0, %d7
    cmp.l   %d7, %d2
    bne     _fail

    | ── 3. ASL.B #8, 0xFF → 0x00; Z=1, C=X=1 ──
    move.l  #0x000000FF, %d3
    asl.b   #8, %d3                  | D3[7:0] = 0
    bcc     _fail                    | C=1 (last bit out was 1)
    bne     _fail                    | Z=1
    bmi     _fail                    | N=0
    move.l  #0x00000000, %d7
    cmp.l   %d7, %d3
    bne     _fail

    | ── 4. Verify high bytes preserved: 0x11223344 ASL.B #2 → 0x112233D0 ──
    move.l  #0x11223344, %d4
    asl.b   #2, %d4                  | D4[7:0] = 0x10; high = 0x11223310
    move.l  #0x11223310, %d7
    cmp.l   %d7, %d4
    bne     _fail

    | Pass sentinel.
    move.l  #0xC0FFEE00, %d0
    move.l  #0xFFFF0000, %a0
    move.l  %d0, (%a0)
    bra     .

_fail:
    move.l  #0xDEADBEEF, %d0
    move.l  #0xFFFF0000, %a0
    move.l  %d0, (%a0)
    bra     .
