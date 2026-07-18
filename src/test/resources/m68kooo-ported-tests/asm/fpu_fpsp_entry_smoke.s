| fpu_fpsp_entry_smoke.s — exercise the exact FPSP entry sequence
|                          shape that triggers the F-line recursion
|                          on the live FPGA.
|
| The Q700 ROM's FPSP entry at 0x4088db1e looks like:
|   linkw fp,#-192                ; allocate frame
|   fsave (sp)-                   ; F327 — already supported
|   moveml d0-d1/a0-a1,(fp,-192)
|   fmovemx fp0-fp3,(fp,-176)     ; F22E F0F0 FF50
|   fmoveml fpiar/fpsr/fpcr,(fp,-128)  ; F22E BC00 FF80
|   ...
|
| If FMOVEM.X / FMOVEM.L (control list) trap as F-line, the FPSP
| handler reenters itself → stack overflow → bus error.  This test
| reproduces that exact 4-instruction prologue and validates that
| every step completes without firing a vec-11.
|
| PASS sentinel: 0xC0FFEE00.
| FAIL sentinels:
|   0xDEAD0F31 — F-line trap fired (FPSP recursion would happen)

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000

_start:
    | Start with a fresh A7 well above any test data.
    lea     0x00010000, %a7
    move.l  #_fline, 0x0000002C        | vec 11 F-line — must NOT fire

    | Set up A6 (= fp) similarly to FPSP entry.
    lea     0x00018000, %a6

    | Mimic FPSP entry instruction by instruction (exact opwords from
    | the Q700 ROM 0x4088db1e+).
    | linkw fp,#-192   — A6 already valid; skip the frame setup since
    | we're not running a parent function; just predec A7 manually.
    lea     -192(%a7), %a7

    | fsave (sp)-     ; F327 — push 4-byte NULL frame to -(SP).
    .short  0xF327

    | moveml d0-d1/a0-a1,(fp,-192)  — using a small register set just
    | to keep the test compact.  The exact register list isn't material
    | (MOVEM.L is fully supported for any list).
    movem.l %d0-%d1/%a0-%a1, -192(%a6)

    | fmovemx fp0-fp3,(fp,-176)    ; F22E F0F0 FF50
    .short  0xF22E, 0xF0F0, 0xFF50

    | fmoveml fpiar/fpsr/fpcr,(fp,-128)  ; F22E BC00 FF80
    .short  0xF22E, 0xBC00, 0xFF80

    | If we got this far, the FPSP entry sequence completed without
    | F-line recursion.  Now do the corresponding restore sequence so
    | we round-trip both directions.

    | fmoveml (fp,-128),fpiar/fpsr/fpcr  ; F22E 9C00 FF80
    .short  0xF22E, 0x9C00, 0xFF80

    | fmovemx (fp,-176),fp0-fp3    ; F22E D0F0 FF50
    .short  0xF22E, 0xD0F0, 0xFF50

    | movem.l (fp,-192),d0-d1/a0-a1
    movem.l -192(%a6), %d0-%d1/%a0-%a1

    | frestore (sp)+   ; F35F — pops 4-byte NULL frame.
    .short  0xF35F

    | unwind A7
    lea     192(%a7), %a7

    | PASS sentinel.
    lea     PASS_SENT, %a1
    move.l  #0xC0FFEE00, %d2
    move.l  %d2, (%a1)
_halt:
    bra     _halt

_fline:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0F31, %d2
    move.l  %d2, (%a1)
_hf:
    bra     _hf
