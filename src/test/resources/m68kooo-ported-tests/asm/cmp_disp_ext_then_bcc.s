| cmp_disp_ext_then_bcc.s -- guard against lost extension-word on CMP.L (d16,An),Dn
|
| Reproduces the ROM shape around 0x40005658:
|   b6ac fffc    cmp.l -4(%a4),%d3
|   6444         bcc.s <target>   (here shortened to +4)
|
| If decode/length tracking drops the d16 extension, fetch sees 0xfffc as an
| opword and trips an F-line trap before the branch executes.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    lea     0x00110004,%a4
    move.l  #0x11223344,%d3
    move.l  %d3,-4(%a4)

    .word   0xb6ac, 0xfffc          | cmp.l -4(%a4),%d3
    .word   0x6404                  | bcc.s _taken
    bra     _fail                   | should not execute

_taken:
    lea     0xFFFF0000,%a1
    move.l  #0xC0FFEE00,%d0
    move.l  %d0,(%a1)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000,%a1
    move.l  #0xDEAD0001,%d0
    move.l  %d0,(%a1)
    bra     _halt
