| memind_wide_disp_gap.s -- the 18 WIDE-DISPLACEMENT full-format
| memory-indirect cells.  LIVE (was DEFERred RED for one day).
|
| These are the legal full-format memind cells whose extension frame
| needs 4-5 extension words:  bd.W+od.L, bd.L+od.W, bd.L+od.L, across
| BS x IS x pre/post-indirect.  Before task #242 decode_ea_v2's ext_words
| port was 48 bits (3 half-words), so ff_memind_in_window reported
| supported=0 for all of them; they fell through to the legacy decode
| sheets -- which do not implement them either -- and every one trapped
| VECTOR 4 (measured: sentinel 0xEEEE0010).  Fail-safe, but wrong.
|
| Task #242 widened the port to 80 bits (5 half-words = the worst case a
| legal frame can need: ext1 + bd.L + od.L) and removed the in-window
| predicate.  decode.v feeds five src/dst ext words and enforces the one
| remaining structural limit -- opword + 6 extension half-words, the
| widest 4-bit len_bytes can encode -- via v2_ext_window_ok.
|
| Together with memind_full_matrix.s (the other 48 cells) this covers all
| 66 legal {BS} x {IS} x {BD SIZE} x {I/IS} combinations.  Cells 17, 20,
| 32 ... are SIX words / TWELVE bytes long, the longest instruction this
| decoder has ever had to length-resolve.
|
| A3 = 0x00200000 (base), D1 = 0x00000040 (index, long, x1 scale).

    .text
    .org 0

_start:
    | Catch the expected vector-4 ILLEGAL immediately, so this DEFERred
    | test fails in microseconds instead of burning the 10M-cycle timeout.
    | The sentinel value is 0xEEEE0000 | <vector offset from the format-$0
    | frame>, so `make test` output records WHICH vector actually fired.
    lea     _exc, %a1
    move.l  %a1, 0x08
    move.l  %a1, 0x0C
    move.l  %a1, 0x10
    move.l  %a1, 0x14
    move.l  %a1, 0x18
    move.l  %a1, 0x1C
    move.l  %a1, 0x20
    move.l  %a1, 0x24
    move.l  %a1, 0x28
    move.l  %a1, 0x2C
    move.l  #0x00200000, %a3
    move.l  #0x00000040, %d1
    moveq   #0, %d3

    | cell 10: BS=0 IS=0 BD=word I/IS=3 (pre-indirect od=long)  ext=0x1923  len=5 words
    move.l  #0x00201040, %a6
    move.l  #0x005A0000, (%a6)
    move.l  #0x005A8000, %a6
    move.l  #0xC0DE000A, (%a6)
    move.l  #0x00000000, %a0
    .short  0x2073, 0x1923, 0x1000, 0x0000, 0x8000
    addq.l  #1, %d3
    cmp.l   #0xC0DE000A, %a0
    bne     _f10

    | cell 13: BS=0 IS=0 BD=word I/IS=7 (post-indirect od=long)  ext=0x1927  len=5 words
    move.l  #0x00201000, %a6
    move.l  #0x005D0000, (%a6)
    move.l  #0x005D8040, %a6
    move.l  #0xC0DE000D, (%a6)
    move.l  #0x00000000, %a0
    .short  0x2073, 0x1927, 0x1000, 0x0000, 0x8000
    addq.l  #1, %d3
    cmp.l   #0xC0DE000D, %a0
    bne     _f13

    | cell 16: BS=0 IS=0 BD=long I/IS=2 (pre-indirect od=word)  ext=0x1932  len=5 words
    move.l  #0x00300040, %a6
    move.l  #0x00600000, (%a6)
    move.l  #0x00600200, %a6
    move.l  #0xC0DE0010, (%a6)
    move.l  #0x00000000, %a0
    .short  0x2073, 0x1932, 0x0010, 0x0000, 0x0200
    addq.l  #1, %d3
    cmp.l   #0xC0DE0010, %a0
    bne     _f16

    | cell 17: BS=0 IS=0 BD=long I/IS=3 (pre-indirect od=long)  ext=0x1933  len=6 words
    move.l  #0x00300040, %a6
    move.l  #0x00610000, (%a6)
    move.l  #0x00618000, %a6
    move.l  #0xC0DE0011, (%a6)
    move.l  #0x00000000, %a0
    .short  0x2073, 0x1933, 0x0010, 0x0000, 0x0000, 0x8000
    addq.l  #1, %d3
    cmp.l   #0xC0DE0011, %a0
    bne     _f17

    | cell 19: BS=0 IS=0 BD=long I/IS=6 (post-indirect od=word)  ext=0x1936  len=5 words
    move.l  #0x00300000, %a6
    move.l  #0x00630000, (%a6)
    move.l  #0x00630240, %a6
    move.l  #0xC0DE0013, (%a6)
    move.l  #0x00000000, %a0
    .short  0x2073, 0x1936, 0x0010, 0x0000, 0x0200
    addq.l  #1, %d3
    cmp.l   #0xC0DE0013, %a0
    bne     _f19

    | cell 20: BS=0 IS=0 BD=long I/IS=7 (post-indirect od=long)  ext=0x1937  len=6 words
    move.l  #0x00300000, %a6
    move.l  #0x00640000, (%a6)
    move.l  #0x00648040, %a6
    move.l  #0xC0DE0014, (%a6)
    move.l  #0x00000000, %a0
    .short  0x2073, 0x1937, 0x0010, 0x0000, 0x0000, 0x8000
    addq.l  #1, %d3
    cmp.l   #0xC0DE0014, %a0
    bne     _f20

    | cell 28: BS=0 IS=1 BD=word I/IS=3 (pre-indirect od=long)  ext=0x1963  len=5 words
    move.l  #0x00201000, %a6
    move.l  #0x006C0000, (%a6)
    move.l  #0x006C8000, %a6
    move.l  #0xC0DE001C, (%a6)
    move.l  #0x00000000, %a0
    .short  0x2073, 0x1963, 0x1000, 0x0000, 0x8000
    addq.l  #1, %d3
    cmp.l   #0xC0DE001C, %a0
    bne     _f28

    | cell 31: BS=0 IS=1 BD=long I/IS=2 (pre-indirect od=word)  ext=0x1972  len=5 words
    move.l  #0x00300000, %a6
    move.l  #0x006F0000, (%a6)
    move.l  #0x006F0200, %a6
    move.l  #0xC0DE001F, (%a6)
    move.l  #0x00000000, %a0
    .short  0x2073, 0x1972, 0x0010, 0x0000, 0x0200
    addq.l  #1, %d3
    cmp.l   #0xC0DE001F, %a0
    bne     _f31

    | cell 32: BS=0 IS=1 BD=long I/IS=3 (pre-indirect od=long)  ext=0x1973  len=6 words
    move.l  #0x00300000, %a6
    move.l  #0x00700000, (%a6)
    move.l  #0x00708000, %a6
    move.l  #0xC0DE0020, (%a6)
    move.l  #0x00000000, %a0
    .short  0x2073, 0x1973, 0x0010, 0x0000, 0x0000, 0x8000
    addq.l  #1, %d3
    cmp.l   #0xC0DE0020, %a0
    bne     _f32

    | cell 43: BS=1 IS=0 BD=word I/IS=3 (pre-indirect od=long)  ext=0x19A3  len=5 words
    move.l  #0x00001040, %a6
    move.l  #0x007B0000, (%a6)
    move.l  #0x007B8000, %a6
    move.l  #0xC0DE002B, (%a6)
    move.l  #0x00000000, %a0
    .short  0x2073, 0x19A3, 0x1000, 0x0000, 0x8000
    addq.l  #1, %d3
    cmp.l   #0xC0DE002B, %a0
    bne     _f43

    | cell 46: BS=1 IS=0 BD=word I/IS=7 (post-indirect od=long)  ext=0x19A7  len=5 words
    move.l  #0x00001000, %a6
    move.l  #0x007E0000, (%a6)
    move.l  #0x007E8040, %a6
    move.l  #0xC0DE002E, (%a6)
    move.l  #0x00000000, %a0
    .short  0x2073, 0x19A7, 0x1000, 0x0000, 0x8000
    addq.l  #1, %d3
    cmp.l   #0xC0DE002E, %a0
    bne     _f46

    | cell 49: BS=1 IS=0 BD=long I/IS=2 (pre-indirect od=word)  ext=0x19B2  len=5 words
    move.l  #0x00100040, %a6
    move.l  #0x00810000, (%a6)
    move.l  #0x00810200, %a6
    move.l  #0xC0DE0031, (%a6)
    move.l  #0x00000000, %a0
    .short  0x2073, 0x19B2, 0x0010, 0x0000, 0x0200
    addq.l  #1, %d3
    cmp.l   #0xC0DE0031, %a0
    bne     _f49

    | cell 50: BS=1 IS=0 BD=long I/IS=3 (pre-indirect od=long)  ext=0x19B3  len=6 words
    move.l  #0x00100040, %a6
    move.l  #0x00820000, (%a6)
    move.l  #0x00828000, %a6
    move.l  #0xC0DE0032, (%a6)
    move.l  #0x00000000, %a0
    .short  0x2073, 0x19B3, 0x0010, 0x0000, 0x0000, 0x8000
    addq.l  #1, %d3
    cmp.l   #0xC0DE0032, %a0
    bne     _f50

    | cell 52: BS=1 IS=0 BD=long I/IS=6 (post-indirect od=word)  ext=0x19B6  len=5 words
    move.l  #0x00100000, %a6
    move.l  #0x00840000, (%a6)
    move.l  #0x00840240, %a6
    move.l  #0xC0DE0034, (%a6)
    move.l  #0x00000000, %a0
    .short  0x2073, 0x19B6, 0x0010, 0x0000, 0x0200
    addq.l  #1, %d3
    cmp.l   #0xC0DE0034, %a0
    bne     _f52

    | cell 53: BS=1 IS=0 BD=long I/IS=7 (post-indirect od=long)  ext=0x19B7  len=6 words
    move.l  #0x00100000, %a6
    move.l  #0x00850000, (%a6)
    move.l  #0x00858040, %a6
    move.l  #0xC0DE0035, (%a6)
    move.l  #0x00000000, %a0
    .short  0x2073, 0x19B7, 0x0010, 0x0000, 0x0000, 0x8000
    addq.l  #1, %d3
    cmp.l   #0xC0DE0035, %a0
    bne     _f53

    | cell 61: BS=1 IS=1 BD=word I/IS=3 (pre-indirect od=long)  ext=0x19E3  len=5 words
    move.l  #0x00001000, %a6
    move.l  #0x008D0000, (%a6)
    move.l  #0x008D8000, %a6
    move.l  #0xC0DE003D, (%a6)
    move.l  #0x00000000, %a0
    .short  0x2073, 0x19E3, 0x1000, 0x0000, 0x8000
    addq.l  #1, %d3
    cmp.l   #0xC0DE003D, %a0
    bne     _f61

    | cell 64: BS=1 IS=1 BD=long I/IS=2 (pre-indirect od=word)  ext=0x19F2  len=5 words
    move.l  #0x00100000, %a6
    move.l  #0x00900000, (%a6)
    move.l  #0x00900200, %a6
    move.l  #0xC0DE0040, (%a6)
    move.l  #0x00000000, %a0
    .short  0x2073, 0x19F2, 0x0010, 0x0000, 0x0200
    addq.l  #1, %d3
    cmp.l   #0xC0DE0040, %a0
    bne     _f64

    | cell 65: BS=1 IS=1 BD=long I/IS=3 (pre-indirect od=long)  ext=0x19F3  len=6 words
    move.l  #0x00100000, %a6
    move.l  #0x00910000, (%a6)
    move.l  #0x00918000, %a6
    move.l  #0xC0DE0041, (%a6)
    move.l  #0x00000000, %a0
    .short  0x2073, 0x19F3, 0x0010, 0x0000, 0x0000, 0x8000
    addq.l  #1, %d3
    cmp.l   #0xC0DE0041, %a0
    bne     _f65

    cmp.l   #18, %d3
    bne     _fcount

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a0)
_halt:
    bra     _halt

_f10:
    move.l  #0xDEAD000A, %d7
    bra     _fail
_f13:
    move.l  #0xDEAD000D, %d7
    bra     _fail
_f16:
    move.l  #0xDEAD0010, %d7
    bra     _fail
_f17:
    move.l  #0xDEAD0011, %d7
    bra     _fail
_f19:
    move.l  #0xDEAD0013, %d7
    bra     _fail
_f20:
    move.l  #0xDEAD0014, %d7
    bra     _fail
_f28:
    move.l  #0xDEAD001C, %d7
    bra     _fail
_f31:
    move.l  #0xDEAD001F, %d7
    bra     _fail
_f32:
    move.l  #0xDEAD0020, %d7
    bra     _fail
_f43:
    move.l  #0xDEAD002B, %d7
    bra     _fail
_f46:
    move.l  #0xDEAD002E, %d7
    bra     _fail
_f49:
    move.l  #0xDEAD0031, %d7
    bra     _fail
_f50:
    move.l  #0xDEAD0032, %d7
    bra     _fail
_f52:
    move.l  #0xDEAD0034, %d7
    bra     _fail
_f53:
    move.l  #0xDEAD0035, %d7
    bra     _fail
_f61:
    move.l  #0xDEAD003D, %d7
    bra     _fail
_f64:
    move.l  #0xDEAD0040, %d7
    bra     _fail
_f65:
    move.l  #0xDEAD0041, %d7
    bra     _fail
_fcount:
    move.l  #0xDEADC001, %d7
_fail:
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
_halt_fail:
    bra     _halt_fail

_exc:
    moveq   #0, %d7
    move.w  6(%sp), %d7
    andi.l  #0x00000FFF, %d7
    ori.l   #0xEEEE0000, %d7
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
_halt_exc:
    bra     _halt_exc
