| rom_frontier_decode_matrix.s -- clustered Q700 ROM-frontier decode forms
|
| This is a compact regression for the decode/addressing shapes that moved
| the Q700 ROM through descriptor selection and memory sizing.  Most forms
| also have single-purpose tests; this clustered version catches extension
| consumption, postincrement writeback, PC-relative indexing, and indexed
| byte RMW interactions in one directed gate.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | 1. CMPA.W #imm16,A0: exact ROM block-list scan shape.
    movea.l #0xffffffff, %a0
    .word   0xb0fc, 0xffff        | cmpa.w #-1,%a0
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail

    | 2. MOVE.W A7,D0: preserve D0 upper word and set flags from word.
    lea     0x00208000, %a7
    move.l  #0x12345678, %d0
    .word   0x300f                | move.w %a7,%d0
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    cmp.l   #0x12348000, %d0
    bne     _fail

    | 3. MOVE.L A0,(A7)+: address-reg source through postincrement dest.
    lea     0x00208100, %a7
    movea.l #0x80000004, %a0
    .word   0x2ec8                | move.l %a0,(%a7)+
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    cmpa.l  #0x00208104, %a7
    bne     _fail
    move.l  0x00208100, %d1
    cmp.l   #0x80000004, %d1
    bne     _fail

    | 4. MOVEM.L (d16,PC),D0-D5: PC displacement consumption + CCR preserve.
    moveq   #7, %d6
    cmp.l   %d6, %d6
_movem:
    .word   0x4cfa, 0x003f        | movem.l (d16,pc),d0-d5
    .word   _table - (_movem + 4)
    bne     _fail
    cmp.l   #0x11111111, %d0
    bne     _fail
    cmp.l   #0x22222222, %d1
    bne     _fail
    cmp.l   #0x33333333, %d2
    bne     _fail
    cmp.l   #0x44444444, %d3
    bne     _fail
    cmp.l   #0x55555555, %d4
    bne     _fail
    cmp.l   #0x66666666, %d5
    bne     _fail

    | 5. LEA (0,A5,D1.W*4),A5: exact ROM aliasing base/dest form.
    move.l  #0x00100000, %a5
    move.l  #0x00000003, %d1
    moveq   #0, %d6
    tst.l   %d6
    .word   0x4bf5, 0x1400        | lea (0,%a5,%d1.w*4),%a5
    bne     _fail                 | LEA must preserve CCR
    cmpa.l  #0x0010000c, %a5
    bne     _fail

    | 6. SUBA.L (A5),A1: ROM sizing-walker memory source.
    move.l  #0x00400000, %d0
    move.l  %d0, (%a5)
    move.l  #0x08000000, %a1
    moveq   #0, %d6
    tst.l   %d6
    suba.l  (%a5), %a1
    bne     _fail                 | SUBA must preserve CCR
    cmpa.l  #0x07c00000, %a1
    bne     _fail

    | 7. Scc indexed byte destination: brief Dn.L index, true condition.
    lea     0x0010a000, %a0
    moveq   #5, %d1
    move.l  #0x11223344, 0x17(%a0)
    moveq   #0, %d6
    tst.l   %d6
    seq     0x12(%a0, %d1.l)
    bne     _fail                 | Scc must preserve CCR
    move.l  0x17(%a0), %d2
    cmp.l   #0xff223344, %d2
    bne     _fail

    | 8. CMP.B (0,A0,D2.W),D1: word index uses sign-extended low word only.
    lea     _bytes, %a0
    move.l  #0x00010003, %d2
    moveq   #0x5a, %d1
    .word   0xb230, 0x2000        | cmp.b (0,A0,D2.W),D1
    bne     _fail

    | 9. NOT.B (0,A0,D2.W): indexed byte RMW preserves neighbouring bytes.
    lea     0x0010b000, %a0
    move.l  #0x112233a5, (%a0)
    move.l  #0x00010003, %d2
    .word   0x4630, 0x2000        | not.b (0,A0,D2.W)
    move.l  (%a0), %d2
    cmp.l   #0x1122335a, %d2
    bne     _fail

    | 10. ADDQ.B #4,D5: ROM quick byte arithmetic preserves upper Dn bits.
    move.l  #0x123456fc, %d5
    .word   0x5805                | addq.b #4,%d5
    bne     _fail
    cmp.l   #0x12345600, %d5
    bne     _fail

    | 11. JMP (0,PC,D7.W*2): ROM scaled PC-relative dispatch.
    move.w  #((_target_scaled - (_jmp_scaled + 2)) / 2), %d7
_jmp_scaled:
    .word   0x4efb, 0x7200        | jmp (0,PC,D7.W*2)
    bra     _fail                 | must be skipped by the scaled indexed JMP

_target_scaled:
    | 12. JMP (2,PC,D3.W): branch target from PC-relative brief index.
    move.w  #(_target - (_jmp_shape + 4)), %d3
_jmp_shape:
    .word   0x4efb, 0x3002        | jmp (2,PC,D3.W)
    bra     _fail                 | must be skipped by the indexed JMP

_target:
    | 13. CLR.W (0,A4,D4.W): ROM low-overlay frontier at 0x0000ffe6.
    lea     0x0010c000, %a4
    move.l  #0x11223344, 4(%a4)
    moveq   #4, %d4
    .word   0x4274, 0x4000        | clr.w (0,%a4,%d4.w)
    bne     _fail
    bmi     _fail
    move.l  4(%a4), %d0
    cmp.l   #0x00003344, %d0
    bne     _fail

    | 14. SUB.L (A2)+,D3: ROM sizing coalescer frontier at 0x40800aa2.
    lea     0x0010d000, %a2
    move.l  #0x00000005, (%a2)
    move.l  #0x00000007, %d3
    .word   0x969a                | sub.l (%a2)+,%d3
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    cmp.l   #0x00000002, %d3
    bne     _fail
    cmpa.l  #0x0010d004, %a2
    bne     _fail

    | 15. MOVE.L (A3),(A2)+: ROM coalescer frontier at 0x40800ab0.
    lea     0x0010e000, %a3
    lea     0x0010e100, %a2
    move.l  #0x12345678, (%a3)
    .word   0x24d3                | move.l (%a3),(%a2)+
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    cmpa.l  #0x0010e000, %a3
    bne     _fail
    cmpa.l  #0x0010e104, %a2
    bne     _fail
    move.l  -4(%a2), %d0
    cmp.l   #0x12345678, %d0
    bne     _fail

    | 16. MOVE.B (d8,PC,D7.W),D0: ROM memory-test frontier at 0x40880ff8.
    moveq   #4, %d7
    move.l  #0x12345678, %d0
    bra     _moveb_pcidx_shape

_moveb_pcidx_table:
    .byte   0x00, 0x00, 0x01, 0x04
_moveb_pcidx_byte:
    .byte   0x05
    .byte   0x00, 0xaa, 0xbb, 0xcc, 0xdd, 0xee, 0xff
    .byte   0x66, 0x77, 0x88, 0x99

_moveb_pcidx_shape:
    .word   0x103b, 0x70ee        | move.b (-18,PC,D7.W),D0
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    cmp.l   #0x12345605, %d0
    bne     _fail

    | 17. TST.L (A0)+: ROM memory-test frontier at 0x40880ea0.
    lea     0x0010f000, %a0
    move.l  #0x00000001, (%a0)
    .word   0x4a98                | tst.l (%a0)+
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    cmpa.l  #0x0010f004, %a0
    bne     _fail
    move.l  -4(%a0), %d0
    cmp.l   #0x00000001, %d0
    bne     _fail

    | 18. CMPI.L #imm,(A2)+: ROM memory-list frontier at 0x4088107e.
    lea     0x0010f100, %a2
    move.l  #0xffffffff, (%a2)
    .word   0x0c9a, 0xffff, 0xffff | cmpi.l #-1,(%a2)+
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail
    cmpa.l  #0x0010f104, %a2
    bne     _fail
    move.l  -4(%a2), %d0
    cmp.l   #0xffffffff, %d0
    bne     _fail

    | 19. SUB.L D2,4(A3): ROM memory-list RMW frontier at 0x408810e8.
    lea     0x0010f200, %a3
    move.l  #0x00000010, 4(%a3)
    moveq   #3, %d2
    .word   0x95ab, 0x0004        | sub.l %d2,4(%a3)
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  4(%a3), %d0
    cmp.l   #0x0000000d, %d0
    bne     _fail

    | 20. ADD.L D2,(A3): ROM memory-list RMW frontier at 0x408810ec.
    lea     0x0010f220, %a3
    move.l  #0x00000010, (%a3)
    moveq   #3, %d2
    .word   0xd593              | add.l %d2,(%a3)
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  (%a3), %d0
    cmp.l   #0x00000013, %d0
    bne     _fail

    | 21. MOVE.W (6,PC,D0.W*2),D0: ROM memory-list dispatch at 0x4088113e.
    move.l  #0x12340000, %d0
    move.w  #-1, %d0
_movew_pcidx_scaled:
    .word   0x303b, 0x0206      | move.w (6,pc,d0.w*2),d0
    .word   0x6002              | bra.s _movew_pcidx_scaled_check
_movew_pcidx_scaled_word:
    .word   0x8001

_movew_pcidx_scaled_check:
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    cmp.l   #0x12348001, %d0
    bne     _fail

    | 22. CMPI.B #1,-26(A6): ROM return-path frontier at 0x40880d60.
    lea     0x0010f240, %a6
    move.l  #0x01020304, -26(%a6)
    .word   0x0c2e, 0x0001, 0xffe6 | cmpi.b #1,-26(%a6)
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail
    move.l  -26(%a6), %d0
    cmp.l   #0x01020304, %d0
    bne     _fail

    | 23. ADDA.W 16(A0),A0: ROM block-copy frontier at 0x4088132a.
    lea     0x0010f280, %a0
    move.l  #0x001c0000, 16(%a0)
    cmp.l   %d0, %d0             | establish Z=1 before flag-preserving ADDA
    .word   0xd0e8, 0x0010      | adda.w 16(%a0),%a0
    bne     _fail               | ADDA preserves CCR from prior CMP.L
    cmpa.l  #0x0010f29c, %a0
    bne     _fail

    | 24. MOVE.W D1,(A1)+: ROM frame-list writer frontier at 0x40881436.
    lea     0x0010f2c0, %a1
    move.l  #0xffffffff, (%a1)
    move.l  #0x0000000a, %d1
    .word   0x32c1              | move.w %d1,(%a1)+
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    cmpa.l  #0x0010f2c2, %a1
    bne     _fail
    move.l  0x0010f2c0, %d0
    cmp.l   #0x000affff, %d0
    bne     _fail

    | 25. MOVE.W #1,(A1)+: ROM frame-list writer frontier at 0x4088140a.
    lea     0x0010f300, %a1
    move.l  #0xffffffff, (%a1)
    .word   0x32fc, 0x0001      | move.w #1,(%a1)+
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    cmpa.l  #0x0010f302, %a1
    bne     _fail
    move.l  0x0010f300, %d0
    cmp.l   #0x0001ffff, %d0
    bne     _fail

    | 26. ANDI.L #0x00ffffff,(A1)+: ROM frame-list mask frontier at 0x40881444.
    lea     0x0010f300, %a1
    move.l  #0x041a0000, (%a1)
    .word   0x0299, 0x00ff, 0xffff  | andi.l #0x00ffffff,(%a1)+
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    cmpa.l  #0x0010f304, %a1
    bne     _fail
    move.l  0x0010f300, %d0
    cmp.l   #0x001a0000, %d0
    bne     _fail

    | 27. LINK.L A4,#-196766: ROM frame allocation frontier at 0x40881482.
    lea     0x00180100, %a7
    move.l  #0x12345678, %a4
    .word   0x480c, 0xfffc, 0xff62  | link.l %a4,#-196766
    cmpa.l  #0x001800fc, %a4
    bne     _fail
    cmpa.l  #0x0015005e, %a7
    bne     _fail
    move.l  0x001800fc, %d0
    cmp.l   #0x12345678, %d0
    bne     _fail
    unlk    %a4
    cmpa.l  #0x00180100, %a7
    bne     _fail
    cmpa.l  #0x12345678, %a4
    bne     _fail

    | 28. ADDA.W D0,A0: ROM direct-register frontier at 0x408814a0.
    lea     0x00150000, %a0
    move.l  #0x0000ffe7, %d0
    moveq   #0, %d7
    tst.l   %d7
    .word   0xd0c0              | adda.w %d0,%a0
    bne     _fail               | ADDA preserves CCR from prior TST
    cmpa.l  #0x0014ffe7, %a0
    bne     _fail

    | 29. MOVE.B full-format (bd.W,PC,D0.W*2),D2 at 0x408817a6.
    moveq   #1, %d0
    move.l  #0x12345678, %d2
_moveb_fullpc_shape:
    .word   0x143b, 0x0320, 0x003c
    bra     _moveb_fullpc_check
    .space  0x34, 0
_moveb_fullpc_table:
    .byte   0x11, 0x00, 0x85, 0x00, 0x22, 0x00, 0x33, 0x00
_moveb_fullpc_check:
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    cmp.l   #0x12345685, %d2
    bne     _fail

    | 30. BTST D0,(A0): ROM dynamic memory bit-test frontier at 0x408817b4.
    lea     0x0010f340, %a0
    move.b  #0x40, (%a0)
    moveq   #14, %d0
    .word   0x0110              | btst %d0,(%a0), 14 mod 8 = bit 6
    beq     _fail
    moveq   #0, %d1
    move.b  (%a0), %d1
    cmp.l   #0x40, %d1
    bne     _fail

    | 31. TST.W A0: ROM address-register TST frontier at 0x4088170c.
    lea     0x7fff0000, %a0
    .word   0x4a48              | tst.w %a0
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail

    | 32. MOVE.L -(A0),D3: ROM source-predecrement frontier at 0x40881872.
    lea     0x0010f360, %a1
    move.l  #0x11223344, (%a1)
    lea     0x0010f364, %a0
    .word   0x2620              | move.l -(%a0),%d3
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    cmpa.l  #0x0010f360, %a0
    bne     _fail
    cmp.l   #0x11223344, %d3
    bne     _fail

    | 33. AND.W (0,A0,D4.W*2),D1: ROM indexed source frontier at 0x408818ba.
    lea     0x0010f370, %a0
    lea     0x0010f374, %a6
    move.l  #0x0ff00000, (%a6)
    moveq   #2, %d4
    move.l  #0xffff00ff, %d1
    .word   0xc270, 0x4200
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    cmp.l   #0xffff00f0, %d1
    bne     _fail

    | 34. MOVE.L ([bd.W,A4],od.W),D0: full memory-indirect frontier.
    lea     0x0010f390, %a4
    lea     0x0010f380, %a6
    move.l  #0x0010f3b4, (%a6)
    lea     0x0010f3b0, %a6
    move.l  #0x89abcdef, (%a6)
    moveq   #0, %d0
    .word   0x2034, 0x8162, 0xfff0, 0xfffc
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    cmp.l   #0x89abcdef, %d0
    bne     _fail

    | 35. SUBQ.L #4,-16(A4): quick arithmetic memory-destination RMW.
    lea     0x0010f410, %a4
    move.l  #0x00000020, -16(%a4)
    .word   0x59ac, 0xfff0
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  -16(%a4), %d0
    cmp.l   #0x0000001c, %d0
    bne     _fail

    | 36. MOVE.L ([bd.W,A4],0),D0: null-outer memory-indirect sibling.
    lea     0x0010f430, %a4
    lea     0x0010f420, %a6
    move.l  #0x0010f460, (%a6)
    lea     0x0010f460, %a6
    move.l  #0x00000039, (%a6)
    moveq   #0, %d0
    .word   0x2034, 0x8161, 0xfff0
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    cmp.l   #0x00000039, %d0
    bne     _fail

    | 37. MOVE.W #1,-154(A4): immediate word to displaced memory.
    lea     0x0010f500, %a4
    .word   0x397c, 0x0001, 0xff66
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.w  -154(%a4), %d0
    cmpi.w  #0x0001, %d0
    bne     _fail

    | 38. MOVE.L (0,A1,D2.W),(0,A0,D2.W): indexed memory-to-memory copy.
    lea     0x0010f600, %a1
    lea     0x0010f700, %a0
    moveq   #8, %d2
    move.l  #0x89abcdef, 8(%a1)
    move.l  #0x00000000, 8(%a0)
    .word   0x21b1, 0x2000, 0x2000
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  8(%a0), %d0
    cmp.l   #0x89abcdef, %d0
    bne     _fail

    | 39. JSR @($400,D2.W*4)@(0): timer-delay full memory-indirect call.
    lea     0x0010ff00, %a7
    lea     0x00004000, %a1
    move.l  #_jsr_memind_target, (%a1)
    move.l  #0x00000f00, %d2
    moveq   #0, %d0
_jsr_memind:
    .word   0x4eb0, 0x25a1, 0x0400
_after_jsr_memind:
    cmp.l   #0x13572468, %d0
    bne     _fail
    cmpa.l  #0x0010ff00, %a7
    bne     _fail
    move.l  -4(%a7), %d1
    cmp.l   #_after_jsr_memind, %d1
    bne     _fail

    | 40. MOVEM.L register-list to absolute memory.  The all-register
    |     abs.W shape matches the ROM vector self-test frontier at 0x408026aa.
    move.l  #0x11111111, %d0
    move.l  #0x22222222, %d1
    cmp.l   %d0, %d0
    .word   0x48f8, 0x0003, 0x7000      | movem.l D0-D1,($7000).W
    bne     _fail                       | MOVEM must preserve CCR
    move.l  0x00007000, %d2
    cmp.l   #0x11111111, %d2
    bne     _fail
    move.l  0x00007004, %d2
    cmp.l   #0x22222222, %d2
    bne     _fail

    move.l  #0x33333333, %d0
    move.l  #0x44444444, %d1
    .word   0x48f9, 0x0003, 0x0010, 0xf800 | movem.l D0-D1,($0010f800).L
    move.l  0x0010f800, %d2
    cmp.l   #0x33333333, %d2
    bne     _fail
    move.l  0x0010f804, %d2
    cmp.l   #0x44444444, %d2
    bne     _fail

    move.l  #0x10101010, %d0
    move.l  #0x77778888, %d7
    lea     0x00123400, %a0
    lea     0x0010ff00, %a7
    .word   0x48f8, 0xffff, 0x7040      | movem.l D0-D7/A0-A7,($7040).W
    move.l  0x00007040, %d2
    cmp.l   #0x10101010, %d2
    bne     _fail
    | MOVEM.L D0-D7/A0-A7 to $7040 stores 16 longs.  A7 is the 16th
    | entry (index 15), so its slot is $7040 + 15*4 = $707c, not $705c
    | (which is D7's slot).
    move.l  0x0000707c, %d2
    cmp.l   #0x0010ff00, %d2
    bne     _fail

    lea     0xFFFF0000, %a6
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a6)
_halt:
    bra     _halt

_jsr_memind_target:
    move.l  #0x13572468, %d0
    rts

_fail:
    lea     0xFFFF0000, %a6
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a6)
_fail_halt:
    bra     _fail_halt

    .align 2
_bytes:
    .byte   0x10, 0x20, 0x30, 0x5a

    .align 2
_table:
    .long   0x11111111
    .long   0x22222222
    .long   0x33333333
    .long   0x44444444
    .long   0x55555555
    .long   0x66666666
