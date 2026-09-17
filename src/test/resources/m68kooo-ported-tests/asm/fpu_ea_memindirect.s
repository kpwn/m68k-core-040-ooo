| fpu_ea_memindirect.s — an FP instruction whose <ea> is MEMORY INDIRECT
|                        must EXECUTE, not take a vector-11 F-line trap.
|
| WHY THIS EXISTS (the Speedometer / SANE crash chain, 2026-09-17)
|
| The Q700 ROM's SANE package passes its operands as POINTERS in the
| stack frame and dereferences them INSIDE the floating-point
| instruction, using a full-format memory-indirect <ea>:
|
|   408ecd36:  f236 5400 8161 000c    fmoved  %fp@(c)@(0),%fp0
|   408ec6a0:  f236 4420 8161 000c    fdivs   %fp@(c)@(0),%fp0
|
| i.e. `FMOVE.D ([12,A6]),FP0` — base A6, 16-bit base displacement 12,
| index suppressed, I/IS = 001 (indirect, null outer displacement).
| There are 72 such sites in the ROM's FP package, covering the whole
| arithmetic set (FADD/FSUB/FMUL/FDIV/FCMP/FREM/FMOVE) in every operand
| format (.W/.S/.L/.D/.X) — this is SANE's calling convention, not a
| corner case.
|
| A real MC68040 EXECUTES these in hardware.  M68040UM 9.6.1 scopes the
| unimplemented-instruction F-line exception to instruction PATTERNS
| (Table 9-10 lists opcodes — FSIN, FMOD, ...), never to addressing
| modes; the 68040 supports every M68000-family addressing mode for
| floating-point instructions.
|
| THIS CORE TRAPS THEM.  DecodeStage's FP-memory gate is
|
|     ucFpMemBad = (ucFpOpClass =/= 010) || (ucFpSrcSpec === 011)
|                  || !ucFpNative || (ucBfEaDec.klass =/= MEMSIMPLE)
|
| and EaDecoder classifies a full-format EA with I/IS =/= 000 as
| MEMINDIRECT, so every one of those 72 sites is rejected to
| FP_MEM_TRAP_ENTRY = a vector-11 F-line trap.  The RTL says so in its
| own comment ("MEMINDIRECT-klass EAs ... are OUT of this task's
| scope").  The integer memory-indirect microcode engine does NOT catch
| them either: its entry gates (slot0IsMemInd / slot1IsMemIndEarly) test
| `spec.eaSrcValid`/`eaDstValid`, and OperationDecoder's cpGEN arm
| declares no EA operand and does not set `eaHand`, so eaSrcValid is
| False for every FP instruction.
|
| The live capture that motivated this test:
|     exc[6] vec=0x0b pc=0x408ecd3e
| 0x408ecd3e is exactly 0x408ecd36 + 8 — the instruction AFTER the
| memory-indirect FMOVE.D, which is the next-instruction PC a vector-11
| FPU-unimplemented format-$2 frame carries.  So the captured F-line IS
| this defect firing, and every downstream FPSP episode in that chain is
| a consequence of it.
|
| PASS sentinel: 0xC0FFEE00
| FAIL sentinels:
|   0xDEADEA11 — vector 11: the memory-indirect FP <ea> was rejected
|                (THE DEFECT)
|   0xDEADEA04 — vector 4 (illegal): wrong-vector dispatch
|   0xDEADEA02 — vector 2 (access fault): the pointer chain was walked
|                to a wrong address
|   0xDEADEA21 — FMOVE.D ([12,A6]),FP0 delivered the wrong value
|   0xDEADEA22 — FADD.D ([12,A6]),FP0 delivered the wrong value
|   0xDEADEA23 — the EA walk clobbered A6

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000
    .equ FRAME,     0x00020000         | stands in for the SANE A6 frame
    .equ PTRSLOT,   0x0002000C         | FRAME+12 — holds the operand pointer
    .equ OPND,      0x00020100         | the actual double operand
    .equ OUT,       0x00020200         | 12-byte read-back buffer

_start:
    lea     0x00010000, %a7
    move.l  #_busfault, 0x00000008     | vec 2
    move.l  #_illegal,  0x00000010     | vec 4
    move.l  #_fline,    0x0000002C     | vec 11

    | The pointer chain: [FRAME+12] -> OPND, and OPND holds 1.0d0.
    move.l  #OPND,       PTRSLOT
    move.l  #0x3FF00000, OPND
    move.l  #0x00000000, OPND+4

    lea     FRAME, %a6

| ── FMOVE.D ([12,A6]),FP0 — the exact ROM encoding ──────────────────
| opword F236 = cpGEN, mode 110 (full-format), reg 110 (A6)
| FP ext 5400 = opclass 010 (mem->reg), src fmt 101 (Double), FP0, FMOVE
| EA ext  8161 = full format, BS=0, IS=1, BD size=10 (word), I/IS=001
| then the 16-bit base displacement 000C
    .short  0xF236, 0x5400, 0x8161, 0x000C

    cmpa.l  #FRAME, %a6
    bne     _f23

    | Read FP0 back through a plain (An) FMOVEM.X and compare against the
    | extended image of 1.0 : 3FFF0000 80000000 00000000.
    lea     OUT, %a1
    move.l  #0xDEADBEEF, 0(%a1)
    move.l  #0xDEADBEEF, 4(%a1)
    move.l  #0xDEADBEEF, 8(%a1)
    fmovem.x %fp0, %a1@
    move.l  0(%a1), %d0
    cmp.l   #0x3FFF0000, %d0
    bne     _f21
    move.l  4(%a1), %d0
    cmp.l   #0x80000000, %d0
    bne     _f21
    move.l  8(%a1), %d0
    cmp.l   #0x00000000, %d0
    bne     _f21

| ── FADD.D ([12,A6]),FP0 — the arithmetic form, same <ea> ───────────
| FP ext 5422 = opclass 010, src fmt 101 (Double), FP0, opmode 0x22 FADD
| FP0 is 1.0, so after adding 1.0 it must be 2.0 = 4000 0000 80000000 0.
    .short  0xF236, 0x5422, 0x8161, 0x000C

    cmpa.l  #FRAME, %a6
    bne     _f23

    lea     OUT, %a1
    move.l  #0xDEADBEEF, 0(%a1)
    move.l  #0xDEADBEEF, 4(%a1)
    move.l  #0xDEADBEEF, 8(%a1)
    fmovem.x %fp0, %a1@
    move.l  0(%a1), %d0
    cmp.l   #0x40000000, %d0
    bne     _f22
    move.l  4(%a1), %d0
    cmp.l   #0x80000000, %d0
    bne     _f22
    move.l  8(%a1), %d0
    cmp.l   #0x00000000, %d0
    bne     _f22

    move.l  #0xC0FFEE00, %d2
    bra     _done

_f21:
    move.l  #0xDEADEA21, %d2
    bra     _done
_f22:
    move.l  #0xDEADEA22, %d2
    bra     _done
_f23:
    move.l  #0xDEADEA23, %d2
    bra     _done
_fline:
    move.l  #0xDEADEA11, %d2
    bra     _done
_illegal:
    move.l  #0xDEADEA04, %d2
    bra     _done
_busfault:
    move.l  #0xDEADEA02, %d2
_done:
    lea     0x00010000, %a7
    lea     PASS_SENT, %a2
    move.l  %d2, (%a2)
_halt:
    bra     _halt
