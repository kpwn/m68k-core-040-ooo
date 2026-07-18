| prm_bfexts_width32.s — BFEXTS width 32 is encoded as width 0.
|
| Spec: M68040 User's Manual, bit-field instruction field specifier.
| A width field encoding of zero denotes a width of 32 bits; BFEXTS
| then sign-extends the 32-bit extracted field.

    .text
    .org 0

_start:
    | Case 1: source has bit 31 set; BFEXTS{0,32} sign-extends → D1 negative.
    | BFEXTS itself sets N=1, Z=0 (per PRM bit-field instructions).
    | Verify the BFEXTS-side CCR BEFORE clobbering with cmp.l.
    move.l  #0x80000001, %d0
    bfexts  %d0{0:32}, %d1
    bpl     _fail1              | N=0 ⇒ BFEXTS didn't see negative
    beq     _fail1              | Z=1 ⇒ BFEXTS saw zero (wrong)
    cmp.l   #0x80000001, %d1    | now check the extracted value
    bne     _fail1

    | Case 2: source has bit 31 clear; BFEXTS{0,32} → D1 positive non-zero.
    move.l  #0x7fffffff, %d0
    bfexts  %d0{0:32}, %d1
    bmi     _fail2              | N=1 ⇒ wrong sign
    beq     _fail2              | Z=1 ⇒ wrong zero
    cmp.l   #0x7fffffff, %d1
    bne     _fail2

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

_fail:
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
_halt_fail:
    bra     _halt_fail
