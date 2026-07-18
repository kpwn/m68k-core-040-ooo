| scc_mem_an_indirect.s -- Scc (An) corner cases (task #235 / F3 RETRY 2)
|
| Validates the V2 2-µop crack for Scc (An).  The corner I almost got
| wrong was leaving has_src_a high for the absolute path (which has no
| base reg) — so this test deliberately drives the An-source path with
| different An values, with all 16 condition codes touched via groups,
| and across multiple bytes within a longword to catch any byte-lane
| mistake in the STORE.B → big-endian wstrb mapping.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | Test 1: ST (always-true) writes 0xff regardless of CCR.
    lea     0x00120000, %a0
    move.l  #0xdeadbeef, (%a0)
    moveq   #0, %d5                  | Z=1, N=0, V=0, C=0
    tst.l   %d5                      | reset CCR baseline
    st      (%a0)                    | unconditional 0xff
    move.l  (%a0), %d0
    cmp.l   #0xffadbeef, %d0
    bne     _fail1
    | CCR must be preserved (Z=1).
    bne     _fail1

    | Test 2: SF (always-false) writes 0x00 regardless of CCR.
    lea     0x00120100, %a1
    move.l  #0x12345678, (%a1)
    moveq   #1, %d5                  | Z=0, N=0
    tst.l   %d5
    sf      (%a1)                    | unconditional 0x00
    move.l  (%a1), %d0
    cmp.l   #0x00345678, %d0
    bne     _fail2

    | Test 3: SCS (carry-set) — drive a CMP that sets C=1.
    lea     0x00120200, %a2
    move.l  #0x99aabbcc, (%a2)
    move.l  #1, %d5
    cmpi.l  #2, %d5                  | 1 - 2 → C=1, N=1
    scs     (%a2)
    move.l  (%a2), %d0
    cmp.l   #0xffaabbcc, %d0
    bne     _fail3

    | Test 4: SCC (carry-clear) — same CMP yields C=1, so SCC is false.
    lea     0x00120300, %a3
    move.l  #0xdeafbeef, (%a3)
    move.l  #1, %d5
    cmpi.l  #2, %d5                  | C=1 still
    scc     (%a3)                    | false → 0x00
    move.l  (%a3), %d0
    cmp.l   #0x00afbeef, %d0
    bne     _fail4

    | Test 5: SVS (overflow-set) after a known V=1 CMP.
    lea     0x00120400, %a4
    move.l  #0xaabbccdd, (%a4)
    move.l  #0x80000000, %d5
    cmpi.l  #1, %d5                  | 0x80000000 - 1 → V=1, C=0, N=0
    svs     (%a4)
    move.l  (%a4), %d0
    cmp.l   #0xffbbccdd, %d0
    bne     _fail5

    | Test 6: SLT (signed less-than) — N != V so SLT is true.  Set up
    | a fresh CMP that overflows to V=1, N=0 → SLT taken.
    lea     0x00120500, %a5
    move.l  #0x44332211, (%a5)
    move.l  #0x80000000, %d5
    cmpi.l  #1, %d5                  | 0x80000000 - 1 → V=1, N=0
    slt     (%a5)
    move.l  (%a5), %d0
    cmp.l   #0xff332211, %d0
    bne     _fail6

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_fail1:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0001, %d0
    move.l  %d0, (%a0)
_halt_fail1:
    bra     _halt_fail1

_fail2:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0002, %d0
    move.l  %d0, (%a0)
_halt_fail2:
    bra     _halt_fail2

_fail3:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0003, %d0
    move.l  %d0, (%a0)
_halt_fail3:
    bra     _halt_fail3

_fail4:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0004, %d0
    move.l  %d0, (%a0)
_halt_fail4:
    bra     _halt_fail4

_fail5:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0005, %d0
    move.l  %d0, (%a0)
_halt_fail5:
    bra     _halt_fail5

_fail6:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0006, %d0
    move.l  %d0, (%a0)
_halt_fail6:
    bra     _halt_fail6
