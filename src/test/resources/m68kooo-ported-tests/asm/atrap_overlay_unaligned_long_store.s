| atrap_overlay_unaligned_long_store.s — Q700 boot Bug B suspect S1
|
| Replicates the A-trap dispatcher's "rts-trick" exit pattern that
| diverges in our HW boot.  ROM 0x408099B0 dispatcher does:
|     MOVE.L A2, 20(SP)        ★ MOVE.L Ax, N(SP) with N=20, SP at -16
|     ...
|     ADDQ.W #4, A7
|     RTS
| where the 20(SP) byte address ends up on a 2-byte boundary (NOT
| 4-byte).  The 4 bytes overlay onto bytes [PC_low_word, fmt_word] of
| an exception frame, so RTS later pops the overlay value as the
| return PC.
|
| Hypothesis: our LSU's split-store path has a corner that mishandles
| this 2-byte-aligned 4-byte write (split into two 2-byte beats with
| wrong order or one side dropped).
|
| Test: build a 14-byte stack frame mimicking [4-bytes scratch][SR
| word][PC long][fmt word] starting at an ODD-BYTE-2 SSP, do the
| overlay, do ADDQ #4 + RTS, assert RTS jumps to the overlaid PC.
|
| Three iterations with different odd-byte alignments (+0, +2, +6)
| and different overlay values to expose ordering bugs.

    .text
    .org 0

_start:
    | Initial supervisor stack at a 4-byte-aligned address that, when
    | reduced by 8 (exception frame) gives an unusual alignment.
    | Pre-frame A7 = 0x00010000.  After 8-byte frame push: A7 =
    | 0x0000FFF8 (4-byte aligned).  We want A7 = 0xFFF6 (= 0xFFF8 - 2)
    | to mimic the boot-time odd alignment, so manually construct.

    | --- Iteration 1: SP=0xFFFE (post-push state; SP+20 = 0x10012, 2-aligned) ---
    lea     0x000FFFFE, %a7              | base SP

    | Build the dispatcher's pre-overlay state on the stack:
    |   At SP+16: [SR][PC][fmt]  (8-byte exception frame)
    | Frame contents — we'll OVERLAY the PC+fmt with `move.l A2, 20(SP)`:
    |   SR slot   at SP+16: 0x2700
    |   PC_high   at SP+18: 0x1234
    |   PC_low    at SP+20: 0xCAFE
    |   fmt_word  at SP+22: 0x028A   (vec 10, fmt 0)
    | Overlay value 0xDEADBEEF will replace bytes [SP+20..23] = PC_low + fmt.

    | Reach SP=0xFFEE so SP+16 = 0xFFFE = 4-aligned, SP+20 = 0x10002 = 2-aligned
    | We want to mimic SP=S_entry-16 where S_entry is odd-aligned.
    | Use SP = 0x0000FFEA so SP+16 = 0xFFFA (2-byte aligned, like 0x17FEE6)
    | and SP+20 = 0xFFFE (2-byte aligned).

    lea     0x0000FFEA, %a7
    | Place exception frame at SP+16 = 0xFFFA byte address (2-byte aligned)
    |   word at 0xFFFA = SR
    |   long at 0xFFFC = PC (32-bit, byte-addressed: spans 0xFFFC..0xFFFF)
    |   word at 0x10000 = fmt
    | Note: the 32-bit PC at 0xFFFC is 4-byte aligned coincidentally, but
    | the OVERLAY at SP+20 = 0xFFFE is 2-byte aligned.

    move.w  #0x2700, 16(%a7)             | SR
    move.w  #0x1234, 18(%a7)             | PC_high (will be left)
    move.w  #0xCAFE, 20(%a7)             | PC_low (will be overlaid)
    move.w  #0x028A, 22(%a7)             | fmt (will be overlaid)

    | Push 16 bytes of dispatcher-saved registers below the frame so
    | SP=0xFFEA "looks like" S_entry-16.  Skip — already at 0xFFEA.

    | The overlay write — exact replica of `MOVE.L A2, 20(SP)`:
    move.l  #0xDEADBEEF, %a2
    move.l  %a2, 20(%a7)                 | unaligned 4-byte write at 0xFFFE

    | Verify each byte landed correctly.  Read back as bytes to
    | bypass any read-side splitting that might mask a bug.
    move.b  20(%a7), %d0                 | byte at 0xFFFE
    cmp.b   #0xDE, %d0
    bne     _fail_iter1
    move.b  21(%a7), %d0                 | byte at 0xFFFF
    cmp.b   #0xAD, %d0
    bne     _fail_iter1
    move.b  22(%a7), %d0                 | byte at 0x10000
    cmp.b   #0xBE, %d0
    bne     _fail_iter1
    move.b  23(%a7), %d0                 | byte at 0x10001
    cmp.b   #0xEF, %d0
    bne     _fail_iter1

    | --- Iteration 2: cross 4-byte boundary the OTHER way ---
    | SP=0xFFEC so SP+20 = 0x10000 (4-byte aligned, no split — control)
    lea     0x0000FFEC, %a7
    move.w  #0x2700, 16(%a7)
    move.w  #0x1234, 18(%a7)
    move.w  #0xCAFE, 20(%a7)
    move.w  #0x028A, 22(%a7)
    move.l  #0x12345678, %a2
    move.l  %a2, 20(%a7)                 | aligned 4-byte write — control case

    move.b  20(%a7), %d0
    cmp.b   #0x12, %d0
    bne     _fail_iter2
    move.b  21(%a7), %d0
    cmp.b   #0x34, %d0
    bne     _fail_iter2
    move.b  22(%a7), %d0
    cmp.b   #0x56, %d0
    bne     _fail_iter2
    move.b  23(%a7), %d0
    cmp.b   #0x78, %d0
    bne     _fail_iter2

    | --- Iteration 3: stress with a series of unaligned writes
    | mimicking the dispatcher running 8 BlockMove dispatches in a
    | row.  This is what would expose any back-to-back race.
    lea     0x000FFFE0, %a7
    move.w  #0x2700, 16(%a7)             | seed
    move.l  #0xAA110011, 20(%a7)         | aligned baseline
    move.l  #0x11110001, %d2
    move.l  %d2, 20(%a7)                 | 1st overlay
    move.l  #0x11110002, %d2
    move.l  %d2, 20(%a7)                 | 2nd
    move.l  #0x11110003, %d2
    move.l  %d2, 20(%a7)                 | 3rd
    move.l  #0x11110004, %d2
    move.l  %d2, 20(%a7)                 | 4th — should be the result

    move.l  20(%a7), %d0
    cmp.l   #0x11110004, %d0
    bne     _fail_iter3

    | Now do the equivalent at an UNALIGNED offset
    lea     0x000FFFEA, %a7              | SP+20 = 0xFFFE = 2-aligned
    move.l  #0xAA22BB22, 20(%a7)         | ★ unaligned baseline write
    move.l  #0x22220001, %d2
    move.l  %d2, 20(%a7)                 | 1st unaligned overlay
    move.l  #0x22220002, %d2
    move.l  %d2, 20(%a7)                 | 2nd
    move.l  #0x22220003, %d2
    move.l  %d2, 20(%a7)                 | 3rd
    move.l  #0x22220004, %d2
    move.l  %d2, 20(%a7)                 | 4th

    move.l  20(%a7), %d0                 | unaligned read-back
    cmp.l   #0x22220004, %d0
    bne     _fail_iter3b

    | --- All passed: PASS sentinel ---
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_fail_iter1:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0001, %d0
    move.l  %d0, (%a0)
_fail_iter1_halt:
    bra     _fail_iter1_halt

_fail_iter2:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0002, %d0
    move.l  %d0, (%a0)
_fail_iter2_halt:
    bra     _fail_iter2_halt

_fail_iter3:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0003, %d0
    move.l  %d0, (%a0)
_fail_iter3_halt:
    bra     _fail_iter3_halt

_fail_iter3b:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0004, %d0
    move.l  %d0, (%a0)
_fail_iter3b_halt:
    bra     _fail_iter3b_halt
