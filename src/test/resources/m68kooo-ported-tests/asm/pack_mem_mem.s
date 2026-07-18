| pack_mem_mem.s — PACK -(A1),-(A0),#0
|
| Reads 2 bytes from -(A1) (first: low byte at lower address 1 past
| predec, second: high byte at lower address 2 past predec), adds
| #adj (16-bit), packs top nibble of high and low nibble of low into
| a single byte, writes to -(A0).
|
| Musashi: REG_A[srcreg]-- ; src = read8 ; REG_A[srcreg]-- ; src |= read8<<8 ;
|          src += adj ; REG_A[dstreg]-- ; write8 (((src>>4)&0xF0)|(src&0x0F))
|
| Test: bytes '1' (0x31), '2' (0x32) at (A1-2), (A1-1).
|       A1 starts at 0x00106002, so byte@106000=0x32 (first high in memory),
|       byte@106001=0x31 (low byte in memory).
|       In big-endian this is the 16-bit word 0x3231 at 0x00106000.
|       Musashi: first read at 106001 = 0x31 (low byte of src);
|                second read at 106000 = 0x32 → shifted << 8 = 0x3200;
|                combined src = 0x3231.
|       adj = 0, so sum = 0x3231.  Pack → (0x32>>0) & 0xF0 = 0x30 (wait,
|       that's wrong — formula is (sum>>4)&0xF0 | sum&0x0F).
|       sum = 0x3231 → (sum>>4) = 0x0323 → & 0xF0 = 0x20.
|       sum & 0x0F = 0x01.  Packed byte = 0x21.
|       Write 0x21 to (A0-1).

    .text
    .org 0

_start:
    lea     0x00106000, %a2           | scratch base
    move.l  #0xFFFFFFFF, (%a2)        | [106000..106003] = FF FF FF FF
    move.l  #0xBADCAFE0, 16(%a2)      | [106010..106013] = BA DC AF E0

    | Seed src bytes: 0x32 at 106000, 0x31 at 106001.
    move.b  #0x32, 0(%a2)
    move.b  #0x31, 1(%a2)

    lea     0x00106002, %a1
    lea     0x00106011, %a0           | dst predec target = 0x106010

    | PACK -(A1),-(A0),#0.  Opword 1000_000_101001_001 + ext1 = 0x0000.
    | Dx = A0 (opword[11:9]=0), Dy = A1 (opword[2:0]=1).
    | 1000 000 101001 001 = 0x8149.
    .word   0x8149, 0x0000            | PACK -(A1),-(A0),#0

    | A1 should be 0x00106000, A0 should be 0x00106010.
    move.l  %a1, %d0
    cmp.l   #0x00106000, %d0
    bne     _fail
    move.l  %a0, %d1
    cmp.l   #0x00106010, %d1
    bne     _fail

    | Byte @ 0x00106010 = 0x21.
    move.b  (%a0), %d2
    and.l   #0xFF, %d2
    cmp.l   #0x21, %d2
    bne     _fail

    | Adjacent byte at 0x00106011 must still be 0xDC.
    move.b  1(%a0), %d3
    and.l   #0xFF, %d3
    cmp.l   #0xDC, %d3
    bne     _fail

    | ── Non-zero adj: ASCII '7' (0x37) + '9' (0x39), adj=0xFFCA → ──
    | combined = 0x3739 + 0xFFCA = 0x13703 → truncated to 16b = 0x3703.
    | Pack: (sum>>4)&0xF0 = 0x3703>>4 = 0x0370 → & 0xF0 = 0x70.
    |        sum & 0x0F = 0x03.  → 0x73 = ASCII '9' decimal digit? No,
    | this just verifies the add+pack chain.
    move.b  #0x37, 0(%a2)             | 0x106000 = 0x37 (high byte)
    move.b  #0x39, 1(%a2)             | 0x106001 = 0x39 (low byte)
    move.l  #0xBADCAFE0, 16(%a2)      | restore 0x106010..106013
    lea     0x00106002, %a1
    lea     0x00106011, %a0
    .word   0x8149, 0xFFCA            | PACK -(A1),-(A0),#0xFFCA

    move.b  (%a0), %d4
    and.l   #0xFF, %d4
    cmp.l   #0x73, %d4
    bne     _fail

    | A-register updates.
    move.l  %a1, %d5
    cmp.l   #0x00106000, %d5
    bne     _fail
    move.l  %a0, %d6
    cmp.l   #0x00106010, %d6
    bne     _fail

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
_halt_fail:
    bra     _halt_fail
