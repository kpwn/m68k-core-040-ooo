| fpu_fsave_ea_modes.s — FSAVE/FRESTORE through the DISPLACED and ABSOLUTE EAs.
|
| ⚠ REWRITTEN 2026-09-17 — this file has now been corrected TWICE, in opposite
| directions, so the history matters:
|
|   v1 claimed "FSAVE HANGS THE CORE".  That was wrong: the probe installed no
|      vector-11 handler, so a CORRECT F-line trap dispatched through a zero
|      vector-table entry and spun at address 0.
|   v2 (2026-09-12) therefore pinned "(d16,An)/(xxx).W/(xxx).L take vector 11",
|      which was true at the time — OperationDecoder's FSAVE/FRESTORE arm
|      admitted only the register-indirect modes.
|   v3 (this file) — THOSE EA FORMS ARE NOW IMPLEMENTED.  OperationDecoder's
|      FSAVE/FRESTORE arm admits the architectural classes:
|          FSAVE    = control ALTERABLE + -(An)
|          FRESTORE = control + (An)+ + the PC-relative forms
|      (`fsvControl` = (An) | (d16,An) | (d8,An,Xn) | (xxx).W | (xxx).L), cracked
|      into [T0 := EA] + [the sysOp reading T0] the way PEA already computes an
|      address.  `FRESTORE d16(An)` in particular is LOAD-BEARING: Mac OS restores
|      FPU context with it (0xF36D = FRESTORE d16(A5), measured in system RAM —
|      docs/BUG_frestore_displacement_ea_and_fline_frame.md).
|      v2's own header said "if the EA forms are ever implemented, this test must
|      be updated to expect the frame instead of the trap".  This is that update.
|
| WHAT IS ASSERTED, and why each is safe to assert
|
| The frame shape is this core's DOCUMENTED contract, already pinned by
| fsave_frestore_basic for the register-indirect forms and by
| fpu_fsave_idle_format_byte for the version byte (ExceptionUnit.scala,
| "FSAVE / FRESTORE state frames"):
|   * every FSAVE frame is 26 words = 52 bytes (`fsLastStep = 25`)
|   * the header longword is 0x41300000 — version 0x41, length byte 0x30, so the
|     pop size is 4 + 0x30 = 52, byte-for-byte the header the Q700 ROM FPSP
|     manufactures for itself at $4088DA52..$4088DA60
|   * a CONTROL EA has no write-back, so An must be unchanged (unlike -(An))
| The BODY is deliberately NOT asserted word-for-word here: frame+$18 carries the
| E1 flag (0x04000000) unconditionally and the operand fields are only populated
| for an unimplemented-instruction frame.  fsave_frestore_basic owns the body.
|
| PASS sentinel: 0xC0FFEE00
| FAIL sentinels:
|   0xDEAD0E11 — vector 11 (F-line): an admitted EA form is NOT decoded
|   0xDEAD0E04 — vector 4 (illegal): wrong-vector dispatch
|   0xDEAD0E02 — vector 2 (access fault): the cracked EA addressed nothing
|   0xDEAD0E21 — (d16,An): wrong frame header
|   0xDEAD0E22 — (d16,An): An was written back (a control EA must not update An)
|   0xDEAD0E23 — (d16,An): the frame overran its 52-byte window
|   0xDEAD0E31 — (xxx).W: wrong frame header
|   0xDEAD0E32 — (xxx).W: the frame overran its 52-byte window
|   0xDEAD0E41 — (xxx).L: wrong frame header
|   0xDEAD0E42 — (xxx).L: the frame overran its 52-byte window
|   0xDEAD0E51 — FRESTORE (d16,An) clobbered An
|   0xDEAD0E52 — FRESTORE (xxx).L clobbered the frame it read

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000
    .equ BUF,       0x00020000         | (d16,An) frame at BUF+8 .. BUF+59
    .equ ABSW,      0x00001F00         | (xxx).W  frame at 0x1F00 .. 0x1F33
    .equ ABSL,      0x00021000         | (xxx).L  frame at 0x21000 .. 0x21033

_start:
    lea     0x00010000, %a7
    move.l  #_fline,   0x0000002C      | vec 11 — must NOT fire
    move.l  #_illegal, 0x00000010      | vec 4  — must NOT fire
    move.l  #_busfault, 0x00000008     | vec 2  — must NOT fire

| ── (d16,An) ────────────────────────────────────────────────────────
    lea     BUF, %a0
    move.l  #0xCA0FEE01, 4(%a0)        | guard immediately BELOW the frame
    move.l  #0xCA0FEE02, 60(%a0)       | guard immediately ABOVE the 52-byte frame

    fsave   8(%a0)                     | 0xF328 0x0008

    move.l  8(%a0), %d0
    cmp.l   #0x41300000, %d0
    bne     _f21
    cmpa.l  #BUF, %a0
    bne     _f22
    move.l  4(%a0), %d0
    cmp.l   #0xCA0FEE01, %d0
    bne     _f23
    move.l  60(%a0), %d0
    cmp.l   #0xCA0FEE02, %d0
    bne     _f23

| ── (xxx).W ─────────────────────────────────────────────────────────
    move.l  #0xCA0FEE03, ABSW-4
    move.l  #0xCA0FEE04, ABSW+52

    .short  0xF338, ABSW               | fsave (ABSW).W   (0xF300|mode 111 reg 000)

    move.l  ABSW, %d0
    cmp.l   #0x41300000, %d0
    bne     _f31
    move.l  ABSW-4, %d0
    cmp.l   #0xCA0FEE03, %d0
    bne     _f32
    move.l  ABSW+52, %d0
    cmp.l   #0xCA0FEE04, %d0
    bne     _f32

| ── (xxx).L ─────────────────────────────────────────────────────────
    move.l  #0xCA0FEE05, ABSL-4
    move.l  #0xCA0FEE06, ABSL+52

    .short  0xF339, (ABSL>>16), (ABSL&0xFFFF)   | fsave (ABSL).L   (0xF300|mode 111 reg 001)

    move.l  ABSL, %d0
    cmp.l   #0x41300000, %d0
    bne     _f41
    move.l  ABSL-4, %d0
    cmp.l   #0xCA0FEE05, %d0
    bne     _f42
    move.l  ABSL+52, %d0
    cmp.l   #0xCA0FEE06, %d0
    bne     _f42

| ── FRESTORE through the same two computed EAs ──────────────────────
| FRESTORE reads the header's length byte to size the frame.  A control
| EA has no write-back, so An must be untouched and the frame in memory
| must be left exactly as it was.
    lea     BUF, %a0
    .short  0xF368, 0x0008             | frestore 8(%a0)  (0xF340|mode 101 reg 000)
    cmpa.l  #BUF, %a0
    bne     _f51

    .short  0xF379, (ABSL>>16), (ABSL&0xFFFF)   | frestore (ABSL).L (0xF340|mode 111 reg 001)
    move.l  ABSL, %d0
    cmp.l   #0x41300000, %d0
    bne     _f52

    move.l  #0xC0FFEE00, %d2
    bra     _done

_f21:
    move.l  #0xDEAD0E21, %d2
    bra     _done
_f22:
    move.l  #0xDEAD0E22, %d2
    bra     _done
_f23:
    move.l  #0xDEAD0E23, %d2
    bra     _done
_f31:
    move.l  #0xDEAD0E31, %d2
    bra     _done
_f32:
    move.l  #0xDEAD0E32, %d2
    bra     _done
_f41:
    move.l  #0xDEAD0E41, %d2
    bra     _done
_f42:
    move.l  #0xDEAD0E42, %d2
    bra     _done
_f51:
    move.l  #0xDEAD0E51, %d2
    bra     _done
_f52:
    move.l  #0xDEAD0E52, %d2
    bra     _done
_fline:
    move.l  #0xDEAD0E11, %d2
    bra     _done
_illegal:
    move.l  #0xDEAD0E04, %d2
    bra     _done
_busfault:
    move.l  #0xDEAD0E02, %d2
_done:
    lea     0x00010000, %a7
    lea     PASS_SENT, %a2
    move.l  %d2, (%a2)
_halt:
    bra     _halt
