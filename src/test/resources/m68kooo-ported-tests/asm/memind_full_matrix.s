| memind_full_matrix.s -- full-format memory-indirect EA matrix (in-window)
|
| MOVEA.L <ea>,A0 with mode=110 reg=A3, enumerating every legal
| combination of the 68020+ full-format extension word whose extension
| frame is at most 3 half-words:
|     BS (base suppress) x IS (index suppress)
|     x BD SIZE {null, word, long}
|     x I/IS {no-memind, pre-indirect od null/word/long,
|             post-indirect od null/word/long (IS=0 only)}
| = 48 cells.  The 18 remaining legal cells (bd.W+od.L, bd.L+od.W,
| bd.L+od.L) need 4-5 extension words and live in
| memind_wide_disp_gap.s -- also green since task #242 widened
| decode_ea_v2's ext_words port to 5 half-words.  The two files together
| are the complete 66-cell enumeration; keep them in sync.
|
| Each cell asserts the loaded value, which also implicitly asserts the
| instruction LENGTH: a mis-sized decode lands the PC inside the
| extension words and derails the following cmp.  The sharp, dedicated
| length trap lives in movea_memind_rom_a7d84.s.
|
| A3 = 0x00200000 (base), D1 = 0x00000040 (index, long, x1 scale).

    .text
    .org 0

_start:
    move.l  #0x00200000, %a3
    move.l  #0x00000040, %d1
    moveq   #0, %d3

    | cell 00: BS=0 IS=0 BD=null I/IS=0 (no-memind)  ext=0x1910  len=2 words
    move.l  #0x00200040, %a6
    move.l  #0xC0DE0000, (%a6)
    move.l  #0x00000000, %a0
    .short  0x2073, 0x1910
    addq.l  #1, %d3
    cmp.l   #0xC0DE0000, %a0
    bne     _f00

    | cell 01: BS=0 IS=0 BD=null I/IS=1 (pre-indirect od=null)  ext=0x1911  len=2 words
    move.l  #0x00200040, %a6
    move.l  #0x00510000, (%a6)
    move.l  #0x00510000, %a6
    move.l  #0xC0DE0001, (%a6)
    move.l  #0x00000000, %a0
    .short  0x2073, 0x1911
    addq.l  #1, %d3
    cmp.l   #0xC0DE0001, %a0
    bne     _f01

    | cell 02: BS=0 IS=0 BD=null I/IS=2 (pre-indirect od=word)  ext=0x1912  len=3 words
    move.l  #0x00200040, %a6
    move.l  #0x00520000, (%a6)
    move.l  #0x00520200, %a6
    move.l  #0xC0DE0002, (%a6)
    move.l  #0x00000000, %a0
    .short  0x2073, 0x1912, 0x0200
    addq.l  #1, %d3
    cmp.l   #0xC0DE0002, %a0
    bne     _f02

    | cell 03: BS=0 IS=0 BD=null I/IS=3 (pre-indirect od=long)  ext=0x1913  len=4 words
    move.l  #0x00200040, %a6
    move.l  #0x00530000, (%a6)
    move.l  #0x00538000, %a6
    move.l  #0xC0DE0003, (%a6)
    move.l  #0x00000000, %a0
    .short  0x2073, 0x1913, 0x0000, 0x8000
    addq.l  #1, %d3
    cmp.l   #0xC0DE0003, %a0
    bne     _f03

    | cell 04: BS=0 IS=0 BD=null I/IS=5 (post-indirect od=null)  ext=0x1915  len=2 words
    move.l  #0x00200000, %a6
    move.l  #0x00540000, (%a6)
    move.l  #0x00540040, %a6
    move.l  #0xC0DE0004, (%a6)
    move.l  #0x00000000, %a0
    .short  0x2073, 0x1915
    addq.l  #1, %d3
    cmp.l   #0xC0DE0004, %a0
    bne     _f04

    | cell 05: BS=0 IS=0 BD=null I/IS=6 (post-indirect od=word)  ext=0x1916  len=3 words
    move.l  #0x00200000, %a6
    move.l  #0x00550000, (%a6)
    move.l  #0x00550240, %a6
    move.l  #0xC0DE0005, (%a6)
    move.l  #0x00000000, %a0
    .short  0x2073, 0x1916, 0x0200
    addq.l  #1, %d3
    cmp.l   #0xC0DE0005, %a0
    bne     _f05

    | cell 06: BS=0 IS=0 BD=null I/IS=7 (post-indirect od=long)  ext=0x1917  len=4 words
    move.l  #0x00200000, %a6
    move.l  #0x00560000, (%a6)
    move.l  #0x00568040, %a6
    move.l  #0xC0DE0006, (%a6)
    move.l  #0x00000000, %a0
    .short  0x2073, 0x1917, 0x0000, 0x8000
    addq.l  #1, %d3
    cmp.l   #0xC0DE0006, %a0
    bne     _f06

    | cell 07: BS=0 IS=0 BD=word I/IS=0 (no-memind)  ext=0x1920  len=3 words
    move.l  #0x00201040, %a6
    move.l  #0xC0DE0007, (%a6)
    move.l  #0x00000000, %a0
    .short  0x2073, 0x1920, 0x1000
    addq.l  #1, %d3
    cmp.l   #0xC0DE0007, %a0
    bne     _f07

    | cell 08: BS=0 IS=0 BD=word I/IS=1 (pre-indirect od=null)  ext=0x1921  len=3 words
    move.l  #0x00201040, %a6
    move.l  #0x00580000, (%a6)
    move.l  #0x00580000, %a6
    move.l  #0xC0DE0008, (%a6)
    move.l  #0x00000000, %a0
    .short  0x2073, 0x1921, 0x1000
    addq.l  #1, %d3
    cmp.l   #0xC0DE0008, %a0
    bne     _f08

    | cell 09: BS=0 IS=0 BD=word I/IS=2 (pre-indirect od=word)  ext=0x1922  len=4 words
    move.l  #0x00201040, %a6
    move.l  #0x00590000, (%a6)
    move.l  #0x00590200, %a6
    move.l  #0xC0DE0009, (%a6)
    move.l  #0x00000000, %a0
    .short  0x2073, 0x1922, 0x1000, 0x0200
    addq.l  #1, %d3
    cmp.l   #0xC0DE0009, %a0
    bne     _f09

    | cell 11: BS=0 IS=0 BD=word I/IS=5 (post-indirect od=null)  ext=0x1925  len=3 words
    move.l  #0x00201000, %a6
    move.l  #0x005B0000, (%a6)
    move.l  #0x005B0040, %a6
    move.l  #0xC0DE000B, (%a6)
    move.l  #0x00000000, %a0
    .short  0x2073, 0x1925, 0x1000
    addq.l  #1, %d3
    cmp.l   #0xC0DE000B, %a0
    bne     _f11

    | cell 12: BS=0 IS=0 BD=word I/IS=6 (post-indirect od=word)  ext=0x1926  len=4 words
    move.l  #0x00201000, %a6
    move.l  #0x005C0000, (%a6)
    move.l  #0x005C0240, %a6
    move.l  #0xC0DE000C, (%a6)
    move.l  #0x00000000, %a0
    .short  0x2073, 0x1926, 0x1000, 0x0200
    addq.l  #1, %d3
    cmp.l   #0xC0DE000C, %a0
    bne     _f12

    | cell 14: BS=0 IS=0 BD=long I/IS=0 (no-memind)  ext=0x1930  len=4 words
    move.l  #0x00300040, %a6
    move.l  #0xC0DE000E, (%a6)
    move.l  #0x00000000, %a0
    .short  0x2073, 0x1930, 0x0010, 0x0000
    addq.l  #1, %d3
    cmp.l   #0xC0DE000E, %a0
    bne     _f14

    | cell 15: BS=0 IS=0 BD=long I/IS=1 (pre-indirect od=null)  ext=0x1931  len=4 words
    move.l  #0x00300040, %a6
    move.l  #0x005F0000, (%a6)
    move.l  #0x005F0000, %a6
    move.l  #0xC0DE000F, (%a6)
    move.l  #0x00000000, %a0
    .short  0x2073, 0x1931, 0x0010, 0x0000
    addq.l  #1, %d3
    cmp.l   #0xC0DE000F, %a0
    bne     _f15

    | cell 18: BS=0 IS=0 BD=long I/IS=5 (post-indirect od=null)  ext=0x1935  len=4 words
    move.l  #0x00300000, %a6
    move.l  #0x00620000, (%a6)
    move.l  #0x00620040, %a6
    move.l  #0xC0DE0012, (%a6)
    move.l  #0x00000000, %a0
    .short  0x2073, 0x1935, 0x0010, 0x0000
    addq.l  #1, %d3
    cmp.l   #0xC0DE0012, %a0
    bne     _f18

    | cell 21: BS=0 IS=1 BD=null I/IS=0 (no-memind)  ext=0x1950  len=2 words
    move.l  #0x00200000, %a6
    move.l  #0xC0DE0015, (%a6)
    move.l  #0x00000000, %a0
    .short  0x2073, 0x1950
    addq.l  #1, %d3
    cmp.l   #0xC0DE0015, %a0
    bne     _f21

    | cell 22: BS=0 IS=1 BD=null I/IS=1 (pre-indirect od=null)  ext=0x1951  len=2 words
    move.l  #0x00200000, %a6
    move.l  #0x00660000, (%a6)
    move.l  #0x00660000, %a6
    move.l  #0xC0DE0016, (%a6)
    move.l  #0x00000000, %a0
    .short  0x2073, 0x1951
    addq.l  #1, %d3
    cmp.l   #0xC0DE0016, %a0
    bne     _f22

    | cell 23: BS=0 IS=1 BD=null I/IS=2 (pre-indirect od=word)  ext=0x1952  len=3 words
    move.l  #0x00200000, %a6
    move.l  #0x00670000, (%a6)
    move.l  #0x00670200, %a6
    move.l  #0xC0DE0017, (%a6)
    move.l  #0x00000000, %a0
    .short  0x2073, 0x1952, 0x0200
    addq.l  #1, %d3
    cmp.l   #0xC0DE0017, %a0
    bne     _f23

    | cell 24: BS=0 IS=1 BD=null I/IS=3 (pre-indirect od=long)  ext=0x1953  len=4 words
    move.l  #0x00200000, %a6
    move.l  #0x00680000, (%a6)
    move.l  #0x00688000, %a6
    move.l  #0xC0DE0018, (%a6)
    move.l  #0x00000000, %a0
    .short  0x2073, 0x1953, 0x0000, 0x8000
    addq.l  #1, %d3
    cmp.l   #0xC0DE0018, %a0
    bne     _f24

    | cell 25: BS=0 IS=1 BD=word I/IS=0 (no-memind)  ext=0x1960  len=3 words
    move.l  #0x00201000, %a6
    move.l  #0xC0DE0019, (%a6)
    move.l  #0x00000000, %a0
    .short  0x2073, 0x1960, 0x1000
    addq.l  #1, %d3
    cmp.l   #0xC0DE0019, %a0
    bne     _f25

    | cell 26: BS=0 IS=1 BD=word I/IS=1 (pre-indirect od=null)  ext=0x1961  len=3 words
    move.l  #0x00201000, %a6
    move.l  #0x006A0000, (%a6)
    move.l  #0x006A0000, %a6
    move.l  #0xC0DE001A, (%a6)
    move.l  #0x00000000, %a0
    .short  0x2073, 0x1961, 0x1000
    addq.l  #1, %d3
    cmp.l   #0xC0DE001A, %a0
    bne     _f26

    | cell 27: BS=0 IS=1 BD=word I/IS=2 (pre-indirect od=word)  ext=0x1962  len=4 words
    move.l  #0x00201000, %a6
    move.l  #0x006B0000, (%a6)
    move.l  #0x006B0200, %a6
    move.l  #0xC0DE001B, (%a6)
    move.l  #0x00000000, %a0
    .short  0x2073, 0x1962, 0x1000, 0x0200
    addq.l  #1, %d3
    cmp.l   #0xC0DE001B, %a0
    bne     _f27

    | cell 29: BS=0 IS=1 BD=long I/IS=0 (no-memind)  ext=0x1970  len=4 words
    move.l  #0x00300000, %a6
    move.l  #0xC0DE001D, (%a6)
    move.l  #0x00000000, %a0
    .short  0x2073, 0x1970, 0x0010, 0x0000
    addq.l  #1, %d3
    cmp.l   #0xC0DE001D, %a0
    bne     _f29

    | cell 30: BS=0 IS=1 BD=long I/IS=1 (pre-indirect od=null)  ext=0x1971  len=4 words
    move.l  #0x00300000, %a6
    move.l  #0x006E0000, (%a6)
    move.l  #0x006E0000, %a6
    move.l  #0xC0DE001E, (%a6)
    move.l  #0x00000000, %a0
    .short  0x2073, 0x1971, 0x0010, 0x0000
    addq.l  #1, %d3
    cmp.l   #0xC0DE001E, %a0
    bne     _f30

    | cell 33: BS=1 IS=0 BD=null I/IS=0 (no-memind)  ext=0x1990  len=2 words
    move.l  #0x00000040, %a6
    move.l  #0xC0DE0021, (%a6)
    move.l  #0x00000000, %a0
    .short  0x2073, 0x1990
    addq.l  #1, %d3
    cmp.l   #0xC0DE0021, %a0
    bne     _f33

    | cell 34: BS=1 IS=0 BD=null I/IS=1 (pre-indirect od=null)  ext=0x1991  len=2 words
    move.l  #0x00000040, %a6
    move.l  #0x00720000, (%a6)
    move.l  #0x00720000, %a6
    move.l  #0xC0DE0022, (%a6)
    move.l  #0x00000000, %a0
    .short  0x2073, 0x1991
    addq.l  #1, %d3
    cmp.l   #0xC0DE0022, %a0
    bne     _f34

    | cell 35: BS=1 IS=0 BD=null I/IS=2 (pre-indirect od=word)  ext=0x1992  len=3 words
    move.l  #0x00000040, %a6
    move.l  #0x00730000, (%a6)
    move.l  #0x00730200, %a6
    move.l  #0xC0DE0023, (%a6)
    move.l  #0x00000000, %a0
    .short  0x2073, 0x1992, 0x0200
    addq.l  #1, %d3
    cmp.l   #0xC0DE0023, %a0
    bne     _f35

    | cell 36: BS=1 IS=0 BD=null I/IS=3 (pre-indirect od=long)  ext=0x1993  len=4 words
    move.l  #0x00000040, %a6
    move.l  #0x00740000, (%a6)
    move.l  #0x00748000, %a6
    move.l  #0xC0DE0024, (%a6)
    move.l  #0x00000000, %a0
    .short  0x2073, 0x1993, 0x0000, 0x8000
    addq.l  #1, %d3
    cmp.l   #0xC0DE0024, %a0
    bne     _f36

    | cell 37: BS=1 IS=0 BD=null I/IS=5 (post-indirect od=null)  ext=0x1995  len=2 words
    move.l  #0x00000000, %a6
    move.l  #0x00750000, (%a6)
    move.l  #0x00750040, %a6
    move.l  #0xC0DE0025, (%a6)
    move.l  #0x00000000, %a0
    .short  0x2073, 0x1995
    addq.l  #1, %d3
    cmp.l   #0xC0DE0025, %a0
    bne     _f37

    | cell 38: BS=1 IS=0 BD=null I/IS=6 (post-indirect od=word)  ext=0x1996  len=3 words
    move.l  #0x00000000, %a6
    move.l  #0x00760000, (%a6)
    move.l  #0x00760240, %a6
    move.l  #0xC0DE0026, (%a6)
    move.l  #0x00000000, %a0
    .short  0x2073, 0x1996, 0x0200
    addq.l  #1, %d3
    cmp.l   #0xC0DE0026, %a0
    bne     _f38

    | cell 39: BS=1 IS=0 BD=null I/IS=7 (post-indirect od=long)  ext=0x1997  len=4 words
    move.l  #0x00000000, %a6
    move.l  #0x00770000, (%a6)
    move.l  #0x00778040, %a6
    move.l  #0xC0DE0027, (%a6)
    move.l  #0x00000000, %a0
    .short  0x2073, 0x1997, 0x0000, 0x8000
    addq.l  #1, %d3
    cmp.l   #0xC0DE0027, %a0
    bne     _f39

    | cell 40: BS=1 IS=0 BD=word I/IS=0 (no-memind)  ext=0x19A0  len=3 words
    move.l  #0x00001040, %a6
    move.l  #0xC0DE0028, (%a6)
    move.l  #0x00000000, %a0
    .short  0x2073, 0x19A0, 0x1000
    addq.l  #1, %d3
    cmp.l   #0xC0DE0028, %a0
    bne     _f40

    | cell 41: BS=1 IS=0 BD=word I/IS=1 (pre-indirect od=null)  ext=0x19A1  len=3 words
    move.l  #0x00001040, %a6
    move.l  #0x00790000, (%a6)
    move.l  #0x00790000, %a6
    move.l  #0xC0DE0029, (%a6)
    move.l  #0x00000000, %a0
    .short  0x2073, 0x19A1, 0x1000
    addq.l  #1, %d3
    cmp.l   #0xC0DE0029, %a0
    bne     _f41

    | cell 42: BS=1 IS=0 BD=word I/IS=2 (pre-indirect od=word)  ext=0x19A2  len=4 words
    move.l  #0x00001040, %a6
    move.l  #0x007A0000, (%a6)
    move.l  #0x007A0200, %a6
    move.l  #0xC0DE002A, (%a6)
    move.l  #0x00000000, %a0
    .short  0x2073, 0x19A2, 0x1000, 0x0200
    addq.l  #1, %d3
    cmp.l   #0xC0DE002A, %a0
    bne     _f42

    | cell 44: BS=1 IS=0 BD=word I/IS=5 (post-indirect od=null)  ext=0x19A5  len=3 words
    move.l  #0x00001000, %a6
    move.l  #0x007C0000, (%a6)
    move.l  #0x007C0040, %a6
    move.l  #0xC0DE002C, (%a6)
    move.l  #0x00000000, %a0
    .short  0x2073, 0x19A5, 0x1000
    addq.l  #1, %d3
    cmp.l   #0xC0DE002C, %a0
    bne     _f44

    | cell 45: BS=1 IS=0 BD=word I/IS=6 (post-indirect od=word)  ext=0x19A6  len=4 words
    move.l  #0x00001000, %a6
    move.l  #0x007D0000, (%a6)
    move.l  #0x007D0240, %a6
    move.l  #0xC0DE002D, (%a6)
    move.l  #0x00000000, %a0
    .short  0x2073, 0x19A6, 0x1000, 0x0200
    addq.l  #1, %d3
    cmp.l   #0xC0DE002D, %a0
    bne     _f45

    | cell 47: BS=1 IS=0 BD=long I/IS=0 (no-memind)  ext=0x19B0  len=4 words
    move.l  #0x00100040, %a6
    move.l  #0xC0DE002F, (%a6)
    move.l  #0x00000000, %a0
    .short  0x2073, 0x19B0, 0x0010, 0x0000
    addq.l  #1, %d3
    cmp.l   #0xC0DE002F, %a0
    bne     _f47

    | cell 48: BS=1 IS=0 BD=long I/IS=1 (pre-indirect od=null)  ext=0x19B1  len=4 words
    move.l  #0x00100040, %a6
    move.l  #0x00800000, (%a6)
    move.l  #0x00800000, %a6
    move.l  #0xC0DE0030, (%a6)
    move.l  #0x00000000, %a0
    .short  0x2073, 0x19B1, 0x0010, 0x0000
    addq.l  #1, %d3
    cmp.l   #0xC0DE0030, %a0
    bne     _f48

    | cell 51: BS=1 IS=0 BD=long I/IS=5 (post-indirect od=null)  ext=0x19B5  len=4 words
    move.l  #0x00100000, %a6
    move.l  #0x00830000, (%a6)
    move.l  #0x00830040, %a6
    move.l  #0xC0DE0033, (%a6)
    move.l  #0x00000000, %a0
    .short  0x2073, 0x19B5, 0x0010, 0x0000
    addq.l  #1, %d3
    cmp.l   #0xC0DE0033, %a0
    bne     _f51

    | cell 54: BS=1 IS=1 BD=null I/IS=0 (no-memind)  ext=0x19D0  len=2 words
    move.l  #0x00000000, %a6
    move.l  #0xC0DE0036, (%a6)
    move.l  #0x00000000, %a0
    .short  0x2073, 0x19D0
    addq.l  #1, %d3
    cmp.l   #0xC0DE0036, %a0
    bne     _f54

    | cell 55: BS=1 IS=1 BD=null I/IS=1 (pre-indirect od=null)  ext=0x19D1  len=2 words
    move.l  #0x00000000, %a6
    move.l  #0x00870000, (%a6)
    move.l  #0x00870000, %a6
    move.l  #0xC0DE0037, (%a6)
    move.l  #0x00000000, %a0
    .short  0x2073, 0x19D1
    addq.l  #1, %d3
    cmp.l   #0xC0DE0037, %a0
    bne     _f55

    | cell 56: BS=1 IS=1 BD=null I/IS=2 (pre-indirect od=word)  ext=0x19D2  len=3 words
    move.l  #0x00000000, %a6
    move.l  #0x00880000, (%a6)
    move.l  #0x00880200, %a6
    move.l  #0xC0DE0038, (%a6)
    move.l  #0x00000000, %a0
    .short  0x2073, 0x19D2, 0x0200
    addq.l  #1, %d3
    cmp.l   #0xC0DE0038, %a0
    bne     _f56

    | cell 57: BS=1 IS=1 BD=null I/IS=3 (pre-indirect od=long)  ext=0x19D3  len=4 words
    move.l  #0x00000000, %a6
    move.l  #0x00890000, (%a6)
    move.l  #0x00898000, %a6
    move.l  #0xC0DE0039, (%a6)
    move.l  #0x00000000, %a0
    .short  0x2073, 0x19D3, 0x0000, 0x8000
    addq.l  #1, %d3
    cmp.l   #0xC0DE0039, %a0
    bne     _f57

    | cell 58: BS=1 IS=1 BD=word I/IS=0 (no-memind)  ext=0x19E0  len=3 words
    move.l  #0x00001000, %a6
    move.l  #0xC0DE003A, (%a6)
    move.l  #0x00000000, %a0
    .short  0x2073, 0x19E0, 0x1000
    addq.l  #1, %d3
    cmp.l   #0xC0DE003A, %a0
    bne     _f58

    | cell 59: BS=1 IS=1 BD=word I/IS=1 (pre-indirect od=null)  ext=0x19E1  len=3 words
    move.l  #0x00001000, %a6
    move.l  #0x008B0000, (%a6)
    move.l  #0x008B0000, %a6
    move.l  #0xC0DE003B, (%a6)
    move.l  #0x00000000, %a0
    .short  0x2073, 0x19E1, 0x1000
    addq.l  #1, %d3
    cmp.l   #0xC0DE003B, %a0
    bne     _f59

    | cell 60: BS=1 IS=1 BD=word I/IS=2 (pre-indirect od=word)  ext=0x19E2  len=4 words
    move.l  #0x00001000, %a6
    move.l  #0x008C0000, (%a6)
    move.l  #0x008C0200, %a6
    move.l  #0xC0DE003C, (%a6)
    move.l  #0x00000000, %a0
    .short  0x2073, 0x19E2, 0x1000, 0x0200
    addq.l  #1, %d3
    cmp.l   #0xC0DE003C, %a0
    bne     _f60

    | cell 62: BS=1 IS=1 BD=long I/IS=0 (no-memind)  ext=0x19F0  len=4 words
    move.l  #0x00100000, %a6
    move.l  #0xC0DE003E, (%a6)
    move.l  #0x00000000, %a0
    .short  0x2073, 0x19F0, 0x0010, 0x0000
    addq.l  #1, %d3
    cmp.l   #0xC0DE003E, %a0
    bne     _f62

    | cell 63: BS=1 IS=1 BD=long I/IS=1 (pre-indirect od=null)  ext=0x19F1  len=4 words
    move.l  #0x00100000, %a6
    move.l  #0x008F0000, (%a6)
    move.l  #0x008F0000, %a6
    move.l  #0xC0DE003F, (%a6)
    move.l  #0x00000000, %a0
    .short  0x2073, 0x19F1, 0x0010, 0x0000
    addq.l  #1, %d3
    cmp.l   #0xC0DE003F, %a0
    bne     _f63

    cmp.l   #48, %d3
    bne     _fcount

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a0)
_halt:
    bra     _halt

_f00:
    move.l  #0xDEAD0000, %d7
    bra     _fail
_f01:
    move.l  #0xDEAD0001, %d7
    bra     _fail
_f02:
    move.l  #0xDEAD0002, %d7
    bra     _fail
_f03:
    move.l  #0xDEAD0003, %d7
    bra     _fail
_f04:
    move.l  #0xDEAD0004, %d7
    bra     _fail
_f05:
    move.l  #0xDEAD0005, %d7
    bra     _fail
_f06:
    move.l  #0xDEAD0006, %d7
    bra     _fail
_f07:
    move.l  #0xDEAD0007, %d7
    bra     _fail
_f08:
    move.l  #0xDEAD0008, %d7
    bra     _fail
_f09:
    move.l  #0xDEAD0009, %d7
    bra     _fail
_f11:
    move.l  #0xDEAD000B, %d7
    bra     _fail
_f12:
    move.l  #0xDEAD000C, %d7
    bra     _fail
_f14:
    move.l  #0xDEAD000E, %d7
    bra     _fail
_f15:
    move.l  #0xDEAD000F, %d7
    bra     _fail
_f18:
    move.l  #0xDEAD0012, %d7
    bra     _fail
_f21:
    move.l  #0xDEAD0015, %d7
    bra     _fail
_f22:
    move.l  #0xDEAD0016, %d7
    bra     _fail
_f23:
    move.l  #0xDEAD0017, %d7
    bra     _fail
_f24:
    move.l  #0xDEAD0018, %d7
    bra     _fail
_f25:
    move.l  #0xDEAD0019, %d7
    bra     _fail
_f26:
    move.l  #0xDEAD001A, %d7
    bra     _fail
_f27:
    move.l  #0xDEAD001B, %d7
    bra     _fail
_f29:
    move.l  #0xDEAD001D, %d7
    bra     _fail
_f30:
    move.l  #0xDEAD001E, %d7
    bra     _fail
_f33:
    move.l  #0xDEAD0021, %d7
    bra     _fail
_f34:
    move.l  #0xDEAD0022, %d7
    bra     _fail
_f35:
    move.l  #0xDEAD0023, %d7
    bra     _fail
_f36:
    move.l  #0xDEAD0024, %d7
    bra     _fail
_f37:
    move.l  #0xDEAD0025, %d7
    bra     _fail
_f38:
    move.l  #0xDEAD0026, %d7
    bra     _fail
_f39:
    move.l  #0xDEAD0027, %d7
    bra     _fail
_f40:
    move.l  #0xDEAD0028, %d7
    bra     _fail
_f41:
    move.l  #0xDEAD0029, %d7
    bra     _fail
_f42:
    move.l  #0xDEAD002A, %d7
    bra     _fail
_f44:
    move.l  #0xDEAD002C, %d7
    bra     _fail
_f45:
    move.l  #0xDEAD002D, %d7
    bra     _fail
_f47:
    move.l  #0xDEAD002F, %d7
    bra     _fail
_f48:
    move.l  #0xDEAD0030, %d7
    bra     _fail
_f51:
    move.l  #0xDEAD0033, %d7
    bra     _fail
_f54:
    move.l  #0xDEAD0036, %d7
    bra     _fail
_f55:
    move.l  #0xDEAD0037, %d7
    bra     _fail
_f56:
    move.l  #0xDEAD0038, %d7
    bra     _fail
_f57:
    move.l  #0xDEAD0039, %d7
    bra     _fail
_f58:
    move.l  #0xDEAD003A, %d7
    bra     _fail
_f59:
    move.l  #0xDEAD003B, %d7
    bra     _fail
_f60:
    move.l  #0xDEAD003C, %d7
    bra     _fail
_f62:
    move.l  #0xDEAD003E, %d7
    bra     _fail
_f63:
    move.l  #0xDEAD003F, %d7
    bra     _fail
_fcount:
    move.l  #0xDEADC001, %d7
_fail:
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
_halt_fail:
    bra     _halt_fail
