| logic_imm_mem_forms.s -- ORI/ANDI/EORI #imm,<ea> memory-destination forms
|
| Covers the low-risk group-0 alterable-memory slice:
|   (An), (An)+, (d16,An), (xxx).W, (xxx).L
|
| The absolute-short forms intentionally stay below 0x8000 so the
| sign-extended 16-bit EA lands in mapped RAM.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | ORI.B (An): simple read-modify-write.
    lea     0x00104000, %a0
    move.l  #0x11223344, (%a0)
    ori.b   #0x0f, (%a0)
    bmi     _fail1
    beq     _fail1
    bvs     _fail1
    bcs     _fail1
    move.l  (%a0), %d0
    cmp.l   #0x1f223344, %d0
    bne     _fail1

    | ANDI.W (An)+: postincrement writeback and word-sized masking.
    lea     0x00104100, %a1
    move.l  #0x12345678, (%a1)
    andi.w  #0x0f0f, (%a1)+
    bmi     _fail2
    beq     _fail2
    bvs     _fail2
    bcs     _fail2
    cmpa.l  #0x00104102, %a1
    bne     _fail2
    move.l  0x00104100, %d1
    cmp.l   #0x02045678, %d1
    bne     _fail2

    | EORI.L (d16,An): long immediate followed by the d16 EA extension.
    | Keep the result positive so BMI stays clear.
    lea     0x00104200, %a2
    move.l  #0x12345678, 0x20(%a2)
    eori.l  #0x7fffffff, 0x20(%a2)
    bmi     _fail3
    beq     _fail3
    bvs     _fail3
    bcs     _fail3
    move.l  0x00104220, %d2
    cmp.l   #0x6dcba987, %d2
    bne     _fail3

    | ORI.W (xxx).W: absolute-short destination in low RAM.
    move.l  #0x12340078, 0x0200.w
    ori.w   #0x0f0f, 0x0200.w
    bmi     _fail4
    beq     _fail4
    bvs     _fail4
    bcs     _fail4
    move.l  0x0200.w, %d3
    cmp.l   #0x1f3f0078, %d3
    bne     _fail4

    | ANDI.L (xxx).L: absolute-long destination with a full 32-bit address.
    move.l  #0xf0f00f0f, 0x00104400.l
    andi.l  #0x0ff0ffff, 0x00104400.l
    bmi     _fail5
    beq     _fail5
    bvs     _fail5
    bcs     _fail5
    move.l  0x00104400.l, %d4
    cmp.l   #0x00f00f0f, %d4
    bne     _fail5

    | EORI.B (xxx).W: byte-sized absolute-short destination.
    move.l  #0x00abcdef, 0x0220.w
    eori.b  #0x7f, 0x0220.w
    bmi     _fail6
    beq     _fail6
    bvs     _fail6
    bcs     _fail6
    move.l  0x0220.w, %d5
    cmp.l   #0x7fabcdef, %d5
    bne     _fail6

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d6
    move.l  %d6, (%a0)
_halt:
    bra     _halt

_fail1:
    move.l  #0xDEAD0001, %d6
    bra     _fail
_fail2:
    move.l  #0xDEAD0002, %d6
    bra     _fail
_fail3:
    move.l  #0xDEAD0003, %d6
    bra     _fail
_fail4:
    move.l  #0xDEAD0004, %d6
    bra     _fail
_fail5:
    move.l  #0xDEAD0005, %d6
    bra     _fail
_fail6:
    move.l  #0xDEAD0006, %d6

_fail:
    lea     0xFFFF0000, %a0
    move.l  %d6, (%a0)
_halt_fail:
    bra     _halt_fail
