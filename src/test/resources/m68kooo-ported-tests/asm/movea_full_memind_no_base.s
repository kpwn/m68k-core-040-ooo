| movea_full_memind_no_base.s -- MOVEA.L (...full-format, BS=1, IS=1...), An
|
| Covers the Q700 ROM frontier (snap_pre_buserr_mmu replay):
|   2470 81E2 0CBC FFF0    movea.l ([0xCBC], -16), %a2
|
| Full-format extension shape unique vs move_full_memind_src.s:
|   - destination = An (MOVEA.L), not Dn
|   - BS=1 (base register A0 suppressed; bare bd is the indirect addr)
|   - IS=1 (index suppressed)
|   - I/IS=010 (preindexed memind, OD=word)
|
| EA semantics: deref(BD = 0x0CBC) → load long pointer P from memory.
|              EA = P + OD = P + (-16).
|              Load long at EA → A2.
|
| HW symptom (without decode coverage): vec=4 (illegal) at fault_pc.

    .text
    .org 0

_start:
    | Stage 1: BS=1+IS=1, BD=word, preindexed, OD=word.  Mirror the ROM
    | shape exactly: BD reads a pointer slot in low memory, OD biases by
    | -16, dst = A2.

    | Place pointer at PA 0x100CBC = pointer to 0x101000.
    lea     0x00100CBC, %a0
    move.l  #0x00101000, (%a0)
    | Place the target long at PA (0x101000 - 16) = 0x100FF0.
    lea     0x00100FF0, %a0
    move.l  #0x12345678, (%a0)
    | Wipe A2 so we know the load wrote it.
    move.l  #0x00000000, %a2
    | Encode: 2470 81E2 0CBC FFF0
    | MOVEA.L src=mode110/reg000(A0 — suppressed), dst=A2
    | ext: BS=1 IS=1 BD=word(0x0CBC) I/IS=010 (preidx OD=word, OD=0xFFF0=-16)
    | NOTE: with BS=1, A0 is ignored — but our test still puts the pointer
    |       at the BD address (0x0CBC) so the deref hits a known PA.
    |       BD is sign-extended word: 0x0CBC → 0x00000CBC.  We need the
    |       pointer at PA 0x00000CBC OR 0x100CBC (low DRAM).  In the
    |       harness, low DRAM is at 0x00000000; we use 0x00000CBC.
    |       But we put the pointer at 0x00100CBC above — fix this.
    |       Use a smaller displacement so test address fits 16-bit signed.
    bra     _stage1_setup

_stage1_setup:
    | BD must be a sign-extendable word that points into our test RAM.
    | RAM-backed addresses 0x00000000..0x003FFFFF are reachable.  Pick
    | BD = 0x0CBC (positive word, → PA 0x00000CBC).
    lea     0x00000CBC, %a0
    move.l  #0x00001000, (%a0)        | pointer slot @ 0x0CBC = 0x1000
    lea     0x00000FF0, %a0
    move.l  #0x12345678, (%a0)        | target = 0x1000-16 = 0xFF0
    move.l  #0x00000000, %a2
    | MOVEA.L ([0x0CBC, BS=1, IS=1], -16), A2
    .word   0x2470, 0x81E2, 0x0CBC, 0xFFF0
    cmp.l   #0x12345678, %a2
    bne     _fail1

    | Stage 2: same shape but I/IS=001 (preindexed, OD=null).
    lea     0x00000CC0, %a0
    move.l  #0x00001100, (%a0)
    lea     0x00001100, %a0
    move.l  #0x89ABCDEF, (%a0)
    move.l  #0x00000000, %a3
    | MOVEA.L ([0x0CC0, BS=1, IS=1], OD=null), A3
    | ext: 0x83E1 (dst=A3 in instr; ext: BS=1 IS=1 BD=word I/IS=001)
    | Wait, ext doesn't encode the destination — only the source EA.
    | Instruction word 0x2670 = MOVEA.L src(mode110/reg000), dst=A3
    .word   0x2670, 0x81E1, 0x0CC0
    cmp.l   #0x89ABCDEF, %a3
    bne     _fail2

    | Stage 3: BS=1+IS=1, no-index memind (I/IS=100, OD=null).
    | Effective: deref(BD) → A4 (no further math).
    lea     0x00000CC4, %a0
    move.l  #0x00001200, (%a0)
    lea     0x00001200, %a0
    move.l  #0xCAFEBABE, (%a0)
    move.l  #0x00000000, %a4
    | MOVEA.L ([0x0CC4, BS=1, IS=1, no-idx]), A4
    | ext: BS=1 IS=1 BD=word I/IS=100
    | bits: 1000_000_0_1_1_10_0_100 = 0x81E4? actually
    |   bit15=1(D/A — index suppressed but still encoded),
    |   bits14:9=000000 (index reg/size/scale don't matter when IS=1),
    |   bit8=1 (full), bit7=1 (BS), bit6=1 (IS), bits5:4=10 (BD=word),
    |   bit3=0, bits2:0=100 (no-index)
    |   = 1000_0001_1110_0100 = 0x81E4
    .word   0x2870, 0x81E4, 0x0CC4
    cmp.l   #0xCAFEBABE, %a4
    bne     _fail3

    | Stage 4: postindexed but with IS=1 (index nominally suppressed,
    | post path).  I/IS=101 (post-idx, OD=null).
    | EA = deref(BD) + (Xn*sc) + OD.  With IS=1, no Xn add.  Same as
    | no-index when OD=null.
    lea     0x00000CC8, %a0
    move.l  #0x00001300, (%a0)
    lea     0x00001300, %a0
    move.l  #0x55AA55AA, (%a0)
    move.l  #0x00000000, %a5
    | MOVEA.L ([0x0CC8, BS=1, IS=1, postidx], OD=null), A5
    | ext: bits 1000_0001_1110_1101 = 0x81ED
    .word   0x2A70, 0x81ED, 0x0CC8
    cmp.l   #0x55AA55AA, %a5
    bne     _fail4

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a0)
_halt:
    bra     _halt

_fail1:
    move.l  #0xDEAD0001, %d7
    bra     _fail
_fail2:
    move.l  #0xDEAD0002, %d7
    bra     _fail
_fail3:
    move.l  #0xDEAD0003, %d7
    bra     _fail
_fail4:
    move.l  #0xDEAD0004, %d7

_fail:
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
_halt_fail:
    bra     _halt_fail
