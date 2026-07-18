| fpu_dyadic_leak_stress.s — task #108 leak-hunt: run MANY iterations of
| both dyadic-int-source FPU shapes observed at the real HW hang
| (register-form FADD.B Dn,FPn and memory-indexed FMUL.X (d,An,Xn),FPn)
| to empirically test whether the RAT's physical-register free count
| (rat.v's dbg_free_cnt, instrumented via a TEMPORARY $display under
| `ifdef CORE_DEBUG for this investigation) trends downward over many
| iterations — which would confirm a genuine leak — or stays flat/
| recovers, which would refute the "dyadic FPU int-source retire leaks
| a phys reg" hypothesis for these specific shapes.
|
| Live HW read (t108-decode-state) at the frozen hang showed
| q_alloc_ok_for_uop=0 with an EMPTY ROB — the RAT's free list was
| exhausted with nothing in flight.  Both observed hang PCs sit right
| after a dyadic FPU op (FADD.B Dn,FPn / FMUL.X indexed) retires, so
| this test targets exactly that class, run enough times (500+) that
| even a slow (e.g. 1-per-32-iterations) leak would exhaust a 48-entry
| int free list well before this test's own PASS check — the ROM's
| leak took the ENTIRE boot sequence (millions of instructions) to
| manifest, but any real per-iteration leak rate high enough to matter
| should show SOME visible downward drift over 500 tight iterations
| even if it doesn't fully exhaust the list within the test.
|
| PASS sentinel: 0xC0FFEE00.

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000
    .equ BUF,       0x00022000
    .equ ITER_COUNT_ADDR, 0x000F0000
    .equ TARGET_ITERS,    500

_start:
    lea     0x00018000, %a7

    lea     BUF, %a1
    move.l  #0x3F800000, 0(%a1)
    move.l  #0x40000000, 4(%a1)

    move.l  #0x3F800000, %d0
    .short  0xF200, 0x4000              | FMOVE.S D0,FP0
    move.l  #0x40000000, %d0
    .short  0xF200, 0x4080              | FMOVE.S D0,FP1

    clr.l   ITER_COUNT_ADDR

_iter_loop:
    | Register-form dyadic (matches the FIRST HW hang PC's instruction
    | shape: FADD.B Dn,FPn with an int-source-B read directly from Dn).
    move.l  #0x3F800000, %d0
    .short  0xF200, 0x5822               | FADD.B D0,FP0

    | Memory-indexed dyadic (matches the SECOND HW hang PC's instruction
    | shape: FMUL.X (d,An,Xn),FPn with an int-source-B fed via REG_TMP1).
    move.l  #0, %d3
    .short  0xF231, 0x48A3, 0x3000       | FMUL.X (0,A1,D3.W),FP1

    | Also exercise FSUB.B/FDIV.B Dn,FPn for broader coverage of the
    | dyadic class, and FNEG.X FPn,FPn (monadic, no int source) as a
    | control shape that should NEVER leak if the theory is specific to
    | int-sourced dyadic ops.
    move.l  #0x40000000, %d0
    .short  0xF200, 0x5828               | FSUB.B D0,FP0
    .short  0xF200, 0x001A               | FNEG.X FP0,FP0

    addq.l  #1, ITER_COUNT_ADDR
    move.l  ITER_COUNT_ADDR, %d0
    cmp.l   #TARGET_ITERS, %d0
    blt     _iter_loop

    lea     PASS_SENT, %a1
    move.l  #0xC0FFEE00, %d2
    move.l  %d2, (%a1)
_halt:
    bra     _halt
