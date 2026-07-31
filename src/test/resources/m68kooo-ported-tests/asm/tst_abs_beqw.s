| tst_abs_beqw.s - TST.B (xxx).W followed by a WORD-displacement Bcc.
|
| Motivated by a live HW stall in the Q700 boot.  The RAM-patched Device
| Manager queueing path at 0x0002E88E is:
|
|   2e88e:  4a38 0349      tst.b  0x0349.W
|   2e892:  6700 00a4      beq.w  0x0002e938      <- WORD displacement
|   2e896:  6004           bra.s  0x0002e89c      <- fall-through = STALL
|
| On hardware $0349 reads 0x00, so beq MUST be taken and the boot should
| tail-jump to the dispatcher.  Instead the CPU falls through into a
| synchronous ioResult poll loop and hangs forever, deterministically.
|
| tst_abs_mem.s already covers TST.B (xxx).W with SHORT branches and
| passes, so the plain EA form is fine.  What that test does NOT cover is
| the same TST feeding a word-displacement Bcc (0x6700 + 16-bit disp),
| which is the exact pair the ROM uses.  This test isolates that pair.
|
| PASS: 0xC0FFEE00 sentinel.
| FAIL: 0xDEADBEEF sentinel.

    .text
    .org 0

_start:
    lea     0x00010000, %a7
    lea     0x00000349, %a0

| ---- Z=1 case: byte is zero, beq.w MUST be taken ----------------------
    move.b  #0x00, (%a0)
    .short  0x4a38, 0x0349          | tst.b 0x0349.W
    .short  0x6700                  | beq.w ...
    .short  (_z_taken - . )         | 16-bit displacement
    bra     _fail                   | fell through => BUG (the HW symptom)

_z_taken:
| ---- Z=0 case: byte nonzero, beq.w must NOT be taken -----------------
    move.b  #0x01, (%a0)
    .short  0x4a38, 0x0349          | tst.b 0x0349.W
    .short  0x6700                  | beq.w ...
    .short  (_fail - . )            | must NOT branch
    | fall-through is correct here

| ---- same pair with a LARGE (>128 byte) word displacement ------------
    move.b  #0x00, (%a0)
    .short  0x4a38, 0x0349          | tst.b 0x0349.W
    .short  0x6700
    .short  (_far_taken - . )
    bra     _fail

    .space  256, 0x00               | force the target out of short range

_far_taken:
| ---- bne.w counterpart (opposite condition, same encoding class) -----
    move.b  #0x01, (%a0)
    .short  0x4a38, 0x0349          | tst.b 0x0349.W
    .short  0x6600                  | bne.w ...
    .short  (_ne_taken - . )
    bra     _fail

_ne_taken:
| ---- TST.B absolute-LONG feeding beq.w -------------------------------
    move.b  #0x00, (%a0)
    .short  0x4a39                  | tst.b 0x00000349.L
    .long   0x00000349
    .short  0x6700
    .short  (_l_taken - . )
    bra     _fail

_l_taken:
| ---- interleave: ensure a prior arith result does not leak into Bcc --
    moveq   #1, %d0                 | set Z=0 from an ALU op
    move.b  #0x00, (%a0)
    .short  0x4a38, 0x0349          | tst.b must overwrite Z
    .short  0x6700
    .short  (_pass - . )
    bra     _fail

_pass:
    lea     0xFFFF0000, %a2
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a2)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a2
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a2)
_halt_fail:
    bra     _halt_fail
