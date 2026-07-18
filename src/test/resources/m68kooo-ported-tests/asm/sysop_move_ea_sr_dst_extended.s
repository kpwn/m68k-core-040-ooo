| sysop_move_ea_sr_dst_extended.s — directed test for MOVE.W <ea>,SR
| with the previously-NOP'd EA modes:
|   (An)+    mode 011 — postincrement
|   -(An)    mode 100 — predecrement
|   (d16,An) mode 101 — base+disp
|   (xxx).W  mode 111 reg 000 — abs short
|   (xxx).L  mode 111 reg 001 — abs long
|
| Pre-fix: only Dn (mode 000) and (An) (mode 010) were assembled.
| Other EA modes silently NOP'd, leaving SR unchanged AND failing
| to advance the source-EA's address-register side-effect (postinc/
| predec).  Post-fix: full coverage; SR is loaded from the EA,
| postinc/predec writeback the address register.
|
| All test SR values are restricted to supervisor-mode-safe values
| (0x2700-0x271F: bits T0/T1=00, S=1, M=0, IPL=7) so we don't
| accidentally drop into user mode mid-test.
|
| Verification idiom: snapshot SR into D1 BEFORE doing any
| comparison instruction (CMPA/CMPI write CCR, which becomes part
| of SR — would corrupt the snapshot).  Then mask the snapshot to
| only the high byte (the system half) and compare against the
| expected high byte; the CCR portion (low byte) is unpredictable
| because the LOAD/ALU phases of the crack may dirty it.
|
| Actually MOVE.W <ea>,SR loads ALL 16 bits, so we'd lose info if
| we masked.  Better: pre-load CCR via the SR write to a known
| state (use SR values whose CCR bits are all the same so we can
| tell load worked vs got stomped).  E.g. SR=0x2700 (CCR=0) and
| SR=0x270F (CCR=NZVC=1111) — easy to distinguish.

    .text
    .org 0
    .equ FAIL_ADDR, 0xFFFF0000
    .equ PASS_VAL,  0xC0FFEE00

_start:
    | --- Setup: place test SR values in memory ---
    | Memory layout:
    |   0x00130000: 0x2700  (CCR=0)
    |   0x00130002: 0x2704  (CCR=Z bit only)
    |   0x00130004: 0x270F  (CCR=NZVC=1111)
    |   0x00000140: 0x2701  (CCR=C bit only) — for abs.W test
    move.w  #0x2700, 0x00130000
    move.w  #0x2704, 0x00130002
    move.w  #0x270F, 0x00130004
    move.w  #0x2701, 0x00000140

    | --- Test 1: MOVE.W (xxx).L,SR ---
    | Reset SR via ANDI/ORI to a known non-target value first
    move.w  #0x270F, %sr               | SR = 0x270F (already supervisor)
    move.w  0x00130000, %sr            | mode 111 reg 001 — load 0x2700
    move.w  %sr, %d1                   | D1.lo = SR = 0x2700
    | NOTE: snapshot D1 IMMEDIATELY before any flag-writing op
    move.l  %d1, %d6                   | save snapshot in D6 (move.l preserves flags? actually no — move sets N/Z, clears V/C)
    | Use a snapshot register that never gets re-read for compare value
    cmp.w   #0x2700, %d6
    bne     _fail_t1

    | --- Test 2: MOVE.W (xxx).W,SR ---
    move.w  #0x270F, %sr
    move.w  0x0140, %sr                | mode 111 reg 000 — was NOP'd
    move.w  %sr, %d1
    move.l  %d1, %d6
    cmp.w   #0x2701, %d6
    bne     _fail_t2

    | --- Test 3: MOVE.W (An),SR (re-confirm baseline still works) ---
    move.w  #0x270F, %sr
    move.l  #0x00130002, %a0
    move.w  (%a0), %sr
    move.w  %sr, %d1
    move.l  %d1, %d6
    cmp.w   #0x2704, %d6
    bne     _fail_t3

    | --- Test 4: MOVE.W (An)+,SR ---
    move.w  #0x270F, %sr
    move.l  #0x00130000, %a1
    move.w  (%a1)+, %sr                | mode 011 — was NOP'd
    move.w  %sr, %d1                   | snapshot SR
    move.l  %a1, %d4                   | snapshot A1 too
    move.l  %d1, %d6
    cmp.w   #0x2700, %d6
    bne     _fail_t4_val
    cmp.l   #0x00130002, %d4
    bne     _fail_t4_an

    | --- Test 5: MOVE.W -(An),SR ---
    move.w  #0x270F, %sr
    move.l  #0x00130006, %a2
    move.w  -(%a2), %sr                | mode 100 — was NOP'd
    move.w  %sr, %d1
    move.l  %a2, %d4
    move.l  %d1, %d6
    cmp.w   #0x270F, %d6
    bne     _fail_t5_val
    cmp.l   #0x00130004, %d4
    bne     _fail_t5_an

    | --- Test 6: MOVE.W (d16,An),SR ---
    move.w  #0x2700, %sr               | reset to 0 CCR so cmp result is reliable
    move.l  #0x00130000, %a3
    move.w  4(%a3), %sr                | mode 101 — was NOP'd; reads 0x130004 = 0x270F
    move.w  %sr, %d1
    move.l  %d1, %d6
    cmp.w   #0x270F, %d6
    bne     _fail_t6

    | --- All tests passed ---
    move.w  #0x2700, %sr               | reset CCR before sentinel write
    move.l  #0x00200000, %a7
    lea     FAIL_ADDR, %a0
    move.l  #PASS_VAL, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_fail_t1:
    move.l  #0xDEAD0001, %d7
    bra     _fail
_fail_t2:
    move.l  #0xDEAD0002, %d7
    bra     _fail
_fail_t3:
    move.l  #0xDEAD0003, %d7
    bra     _fail
_fail_t4_an:
    move.l  %d4, %d7
    bra     _fail
_fail_t4_val:
    move.l  #0xDEAD0041, %d7
    bra     _fail
_fail_t5_an:
    move.l  %d4, %d7
    bra     _fail
_fail_t5_val:
    move.l  #0xDEAD0051, %d7
    bra     _fail
_fail_t6:
    move.l  #0xDEAD0006, %d7
    bra     _fail
_fail:
    move.l  #0x00200000, %a7
    lea     FAIL_ADDR, %a0
    move.l  %d7, (%a0)
1:  bra     1b
