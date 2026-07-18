| exc_priv_frame_fmt.s — verify privilege violation (vec 8) pushes a
| format-0 (8-byte) frame, not format-2 (12-byte).
|
| Per 68040 PRM §8.3.4 and exc.md, privilege violation uses Format $0.
| Construction: Drop to user mode, execute STOP #imm (privileged).
| Vec-8 handler reads sp@(6) and checks format nibble == 0.
| PASS: format nibble = 0x0 (format-0), frame = 8 bytes.

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000

_start:
    lea     0x00010000, %a7
    move.l  #_handler, 0x00000020   | vec 8

    | Drop to user mode (clear S bit).
    andi.w  #0xDFFF, %sr

    | Trigger privilege violation.  STOP is privileged.
    stop    #0x2000

    | Should not reach.
    lea     PASS_SENT, %a0
    move.l  #0xDEAD0801, %d0
    move.l  %d0, (%a0)
_halt_fail:
    bra     _halt_fail

_handler:
    | In supervisor mode now.  Check sp@(6) format nibble.
    move.w  6(%a7), %d0
    | Format nibble is the high 4 bits of the format/vec word.
    move.w  %d0, %d1
    rol.w   #4, %d1
    andi.w  #0x000F, %d1
    | Format-0 has nibble 0x0.  Format-2 would be 0x2.
    cmp.w   #0, %d1
    bne     _fail_fmt
    | Also verify low 12 bits == 0x020 (vec 8 byte offset).
    andi.w  #0x0FFF, %d0
    cmp.w   #0x020, %d0
    bne     _fail_vec

    lea     PASS_SENT, %a0
    move.l  #0xC0FFEE00, %d2
    move.l  %d2, (%a0)
_halt:
    bra     _halt

_fail_fmt:
    lea     PASS_SENT, %a0
    move.l  #0xDEAD0802, %d2
    move.l  %d2, (%a0)
_halt_ff:
    bra     _halt_ff

_fail_vec:
    lea     PASS_SENT, %a0
    move.l  #0xDEAD0803, %d2
    move.l  %d2, (%a0)
_halt_fv:
    bra     _halt_fv
