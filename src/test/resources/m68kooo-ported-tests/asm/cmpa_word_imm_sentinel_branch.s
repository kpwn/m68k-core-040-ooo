| cmpa_word_imm_sentinel_branch.s -- ROM sentinel compare regression
|
| Q700 ROM uses:
|   cmpa.w  #-1,%a0
|   beq     ...
| to terminate the RAM descriptor list after loading A0=0xffffffff.
| CMPA.W sign-extends the immediate to 32 bits for the address-register
| compare.  The following BEQ must see Z=1 without an intervening NOP.

    .text
    .org 0

_start:
    movea.l #0xffffffff, %a0
    cmpa.w  #-1, %a0
    bne     _fail
    cmpa.w  #-1, %a0
    beq     1f
    bra     _fail
1:
    cmpa.w  #-1, %a0
    beq.s   1f
    bra.w   _fail
    .rept 17
    nop
    .endr
1:

    lea     sentinel_word, %a4
    movea.l (%a4)+, %a0
    cmpa.w  #-1, %a0
    bne     _fail
    cmpa.w  #-1, %a0
    beq     1f
    bra     _fail
1:
    cmpa.l  #sentinel_word + 4, %a4
    bne     _fail

    lea     0x00102000, %a4
    move.l  #0xffffffff, (%a4)
    movea.l (%a4)+, %a0
    cmpa.w  #-1, %a0
    bne     _fail
    cmpa.w  #-1, %a0
    beq     1f
    bra     _fail
1:
    cmpa.l  #0x00102004, %a4
    bne     _fail

    movea.l #0x0000ffff, %a0
    cmpa.w  #-1, %a0
    beq     _fail

    movea.l #0x00000000, %a0
    cmpa.w  #0, %a0
    bne     _fail

_pass:
    lea     0xFFFF0000, %a1
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a1)
_halt:
    stop    #0x2700
    bra     _halt

_fail:
    lea     0xFFFF0000, %a1
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a1)
_halt_fail:
    stop    #0x2700
    bra     _halt_fail

    .align 4
sentinel_word:
    .long 0xffffffff
