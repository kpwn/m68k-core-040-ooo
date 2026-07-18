| dispatcher_a7_race.s — sweep misaligned-long-load offsets that arise
| from the Q700 ROM A-line dispatcher prologue when SP is odd.
|
| Dispatcher prologue (ROM 0x408099B0):
|     move.l  a2, -(sp)
|     move.l  d2, -(sp)
|     movea.l $A(sp), a2      ; load PC from the just-pushed A-line frame
|
| For each starting SP alignment (we care about odd-SP cases — Mac OS
| Q700 legitimately uses odd supervisor SP), we:
|   1. lay 0x11111111 at the expected target address
|   2. lay 0xAAAAAAAA in surrounding memory so a wrong-offset read is
|      visible as 0xAA in the high byte
|   3. run the prologue
|   4. verify A2 == 0x11111111
|
| If any case fails, the sentinel encodes which SP alignment:
|   0xDEAD08x0  case x failed (x = SP[1:0] sweep value)

    .text
    .org 0
    .equ PASS_SENT, 0xFFFF0000

_start:
    move.w  #0x2000, %sr

    | ── Pre-fill the test memory window 0x20000..0x20100 with 0xAA
    | so wrong-offset reads show up.
    move.l  #0x00020000, %a0
    move.l  #0xAAAAAAAA, %d0
    move.l  #16-1, %d1                | 16 longs = 64 bytes
.fill_loop:
    move.l  %d0, (%a0)+
    dbf     %d1, .fill_loop

    | ── Case A: A7 = 0x20009 (bit[1:0]=01) ─────────────────────────
    | After 2 long pushes: A7 = 0x20001 (bit[1:0]=01)
    | movea.l 0xA(sp), a2 reads at 0x2000B (bit[1:0]=11)
    | Pre-fill 0x2000B..0x2000E with 0x11111111
    move.b  #0x11, 0x0002000B
    move.b  #0x11, 0x0002000C
    move.b  #0x11, 0x0002000D
    move.b  #0x11, 0x0002000E

    move.l  #0x00020009, %a7
    move.l  #0xDEADBEEF, %a2
    move.l  #0xCAFECAFE, %d2
    move.l  %a2, -(%a7)
    move.l  %d2, -(%a7)
    movea.l 0xA(%a7), %a2
    cmp.l   #0x11111111, %a2
    bne     _fail_A

    | ── Case B: A7 = 0x2000B (bit[1:0]=11) ─────────────────────────
    | After 2 long pushes: A7 = 0x20003 (bit[1:0]=11)
    | movea.l 0xA(sp), a2 reads at 0x2000D (bit[1:0]=01)
    | Restore the window first (Case A may have left stack garbage).
    move.l  #0x00020000, %a0
    move.l  #0xAAAAAAAA, %d0
    move.l  #16-1, %d1
.fillB:
    move.l  %d0, (%a0)+
    dbf     %d1, .fillB
    move.b  #0x11, 0x0002000D
    move.b  #0x11, 0x0002000E
    move.b  #0x11, 0x0002000F
    move.b  #0x11, 0x00020010

    move.l  #0x0002000B, %a7
    move.l  #0xDEADBEEF, %a2
    move.l  #0xCAFECAFE, %d2
    move.l  %a2, -(%a7)
    move.l  %d2, -(%a7)
    movea.l 0xA(%a7), %a2
    cmp.l   #0x11111111, %a2
    bne     _fail_B

    | ── Case C: A7 = 0x2000A (bit[1:0]=10) (even but misaligned-long) ─
    | After 2 long pushes: A7 = 0x20002 (bit[1:0]=10)
    | movea.l 0xA(sp), a2 reads at 0x2000C (bit[1:0]=00, ALIGNED!)
    | Skip — this case is aligned, not interesting for the bug hunt.
    | Just verify it for completeness.
    move.l  #0x00020000, %a0
    move.l  #0xAAAAAAAA, %d0
    move.l  #16-1, %d1
.fillC:
    move.l  %d0, (%a0)+
    dbf     %d1, .fillC
    move.l  #0x11111111, 0x0002000C   | aligned-long write

    move.l  #0x0002000A, %a7
    move.l  #0xDEADBEEF, %a2
    move.l  #0xCAFECAFE, %d2
    move.l  %a2, -(%a7)
    move.l  %d2, -(%a7)
    movea.l 0xA(%a7), %a2
    cmp.l   #0x11111111, %a2
    bne     _fail_C

    | ── PASS ───────────────────────────────────────────────────────
    move.l  #0x00020100, %a7
    lea     PASS_SENT, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt_p:
    bra     _halt_p

_fail_A:
    move.l  #0x00020100, %a7
    move.l  %a2, %d7
    lea     PASS_SENT, %a0
    move.l  #0xDEAD0810, %d0
    move.l  %d0, (%a0)
_halt_A:
    bra     _halt_A

_fail_B:
    move.l  #0x00020100, %a7
    move.l  %a2, %d7
    lea     PASS_SENT, %a0
    move.l  #0xDEAD0820, %d0
    move.l  %d0, (%a0)
_halt_B:
    bra     _halt_B

_fail_C:
    move.l  #0x00020100, %a7
    move.l  %a2, %d7
    lea     PASS_SENT, %a0
    move.l  #0xDEAD0830, %d0
    move.l  %d0, (%a0)
_halt_C:
    bra     _halt_C
