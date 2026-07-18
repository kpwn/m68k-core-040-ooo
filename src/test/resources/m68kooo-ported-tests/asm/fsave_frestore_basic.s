| fsave_frestore_basic.s — Test FSAVE/FRESTORE state-frame semantics
|
| IN-PLACE UPDATE (2026-07-15, live-HW Sad Mac 02 root cause): FSAVE
| now emits the 52-byte version-0x41/size-0x30 UNIMP-shaped frame
| (header long 0x41300000, body zero-filled) and FRESTORE (An)+ pops
| REAL 68040 style — 4 bytes + the frame's size byte.  The Q700 FPSP
| (`linkw %fp,#-192; fsave %sp@-`, fields at fp@(-244..-196)) hard-
| depends on both; the old 4-byte IDLE frame left SP 48 bytes high
| inside the FPSP and its scratch stores clobbered live return-address
| slots (vec-3 crash at 0x4088E4C6).  See decode_1111.vh FSAVE
| commentary, fpsp_dec2bin_sp_integrity.s, fpsp_packed_kernel_e2e.s.
|
| Verifies, for every supported EA mode, that:
|   1. FSAVE writes the 52-byte frame (header 0x41300000, zero body).
|   2. FRESTORE consumes it; (An)+ advances An by 4 + size byte.
|   3. Address-register side effects (predec / postinc) are correct.
|   4. FRESTORE (An)+ pop-size follows the IN-MEMORY size byte for
|      every architected frame flavour: NULL (4), UNIMP-A 0x40280000
|      (44), UNIMP-B 0x41300000 (52), BUSY 0x40600000 (100), and the
|      FPSP's manufactured pseudo-NULL (version 0x41, size 0 → 4).
|
| EA modes covered (sub-tests 1..5):
|   1. FSAVE -(An)        + FRESTORE (An)+    ← stack idiom (most common)
|   2. FSAVE (An)         + FRESTORE (An)
|   3. FSAVE (d16,An)     + FRESTORE (d16,An)
|   4. FSAVE (xxx).W      + FRESTORE (xxx).W
|   5. FSAVE (xxx).L      + FRESTORE (xxx).L
| Plus sub-test 6: the size-aware FRESTORE (An)+ matrix above.

    .text
    .org 0

_start:
    lea     0x00010000, %a7         | stack high in scratch RAM

    | Pre-poison a 16-byte scratch buffer with non-zero so a NOP
    | FSAVE would be detectable.
    lea     0x00020000, %a0
    move.l  #0xDEADBEEF, (%a0)
    move.l  #0xDEADBEEF, 4(%a0)
    move.l  #0xDEADBEEF, 8(%a0)
    move.l  #0xDEADBEEF, 12(%a0)

    | ── Sub-test 1: FSAVE -(A7) / FRESTORE (A7)+ ───────────────
    move.l  %a7, %d2                | save A7 baseline
    move.l  #0xDEADBEEF, -52(%a7)   | poison the header slot
    move.l  #0xDEADBEEF, -4(%a7)    | poison the last body long
    fsave   -(%a7)                  | A7 -= 52, write 52-byte frame
    | A7 must now be d2 - 52
    move.l  %d2, %d3
    sub.l   #52, %d3
    cmp.l   %d3, %a7
    bne     _fail
    | Header long at new A7: version 0x41, size 0x30
    move.l  (%a7), %d4
    cmp.l   #0x41300000, %d4
    bne     _fail
    | Body zero-filled (first + last body longs)
    move.l  4(%a7), %d4
    bne     _fail
    move.l  48(%a7), %d4
    bne     _fail
    | Pop it back: A7 += 4 + size(0x30) = 52
    frestore (%a7)+
    cmp.l   %d2, %a7                | A7 restored
    bne     _fail

    | ── Sub-test 2: FSAVE (A0) / FRESTORE (A0) ────────────────
    | Re-poison slot 0
    move.l  #0xDEADBEEF, (%a0)
    fsave   (%a0)                   | write 52-byte frame at (a0)
    move.l  (%a0), %d4
    cmp.l   #0x41300000, %d4
    bne     _fail
    | A0 must NOT change
    lea     0x00020000, %a1
    cmp.l   %a1, %a0
    bne     _fail
    | FRESTORE (A0) — observable: A0 still unchanged after
    frestore (%a0)
    cmp.l   %a1, %a0
    bne     _fail

    | ── Sub-test 3: FSAVE/FRESTORE (d16,An) ───────────────────
    | Use offset +8 from A0
    move.l  #0xDEADBEEF, 8(%a0)
    fsave   8(%a0)
    move.l  8(%a0), %d4
    cmp.l   #0x41300000, %d4
    bne     _fail
    cmp.l   %a1, %a0                | A0 unchanged
    bne     _fail
    frestore 8(%a0)
    cmp.l   %a1, %a0                | still unchanged
    bne     _fail

    | ── Sub-test 4: FSAVE/FRESTORE (xxx).W ────────────────────
    | (xxx).W is sign-extended 16-bit. Use a low address that
    | survives sign-extension: 0x1F00 (inside scratch RAM at low
    | end, well below 0x00010000 stack and far from 0x00020000).
    | Reset the 4-byte slot to 0xDEADBEEF first.
    move.l  #0xDEADBEEF, 0x1F00.w
    fsave   0x1F00.w
    move.l  0x1F00.w, %d4
    cmp.l   #0x41300000, %d4
    bne     _fail
    frestore 0x1F00.w               | exercise the LOAD path

    | ── Sub-test 5: FSAVE/FRESTORE (xxx).L ────────────────────
    move.l  #0xDEADBEEF, 0x00020010
    fsave   0x00020010
    move.l  0x00020010, %d4
    cmp.l   #0x41300000, %d4
    bne     _fail
    frestore 0x00020010

    | ── Sub-test 6: FRESTORE (An)+ size-aware pop matrix ───────
    | Manufactured frames, exactly the flavours real 68040 software
    | (and the Q700 FPSP) hands to FRESTORE.  Pop = 4 + size byte.
    lea     0x00021000, %a0
    lea     0x00021000, %a2         | expected-baseline shadow

    | (a) NULL frame (clrl %sp@- idiom): 0x00000000 → pop 4
    clr.l   (%a0)
    frestore (%a0)+
    lea     4(%a2), %a1
    cmp.l   %a1, %a0
    bne     _fail

    | (b) FPSP pseudo-NULL: version 0x41, size 0x00 → pop 4
    lea     0x00021000, %a0
    move.l  #0x41000000, (%a0)
    frestore (%a0)+
    cmp.l   %a1, %a0
    bne     _fail

    | (c) UNIMP mask-rev-A: 0x40280000 → pop 4 + 0x28 = 44
    lea     0x00021000, %a0
    move.l  #0x40280000, (%a0)
    frestore (%a0)+
    lea     44(%a2), %a1
    cmp.l   %a1, %a0
    bne     _fail

    | (d) UNIMP mask-rev-B (our own FSAVE): 0x41300000 → pop 52
    lea     0x00021000, %a0
    move.l  #0x41300000, (%a0)
    frestore (%a0)+
    lea     52(%a2), %a1
    cmp.l   %a1, %a0
    bne     _fail

    | (e) BUSY: 0x40600000 → pop 4 + 0x60 = 100
    lea     0x00021000, %a0
    move.l  #0x40600000, (%a0)
    frestore (%a0)+
    lea     100(%a2), %a1
    cmp.l   %a1, %a0
    bne     _fail

    | ── PASS ───────────────────────────────────────────────────
_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    stop    #0x2700
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
_halt_fail:
    stop    #0x2700
    bra     _halt_fail
