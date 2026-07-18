| jsr_preindexed_memind_atrap_table.s — Q700 boot Bug B suspect S4
|
| Dispatcher at ROM 0x40809A04 does:
|     4EB0 25A1 0400   JSR ([0x400 + D2.W*4])
| where bits decode as:
|   opc 4EB0 = JSR mode-6 (indexed) reg=A0 (suppressed by ext1 bit 7)
|   ext1 0x25A1 = D2 index, W (sign-ext .W), scale ×4,
|                 full format, base suppress=1, IS=0,
|                 BD-SIZE=word, IIS=0001 (preindexed, null outer disp)
|   bd  = 0x0400 (16-bit base displacement)
|
|   EA1 = 0 (BS) + 0x400 + sx16(D2.W) * 4
|   TMP1 = mem.L[EA1]
|   final = TMP1 + 0  (od=0)
|   JSR target = mem.L[0x400 + D2.W * 4]
|
| Test: install fake A-trap table at 0x400 (so mem.L[0x400 + i*4] points
| to handler i), sweep D2 over a range of indices, verify each JSR
| reaches its expected handler and returns cleanly.
|
| 8-µop crack per JSR (decode_0100.vh:111-225) — covers:
|   - base+bd (with suppress)
|   - sign-ext from D2.W via ALU_EXT
|   - scale ×4 = two TMP2 doublings
|   - TMP1 += TMP2 (preindex add)
|   - LOAD TMP1 from EA1 (the indirect read)
|   - ADD od (= 0)
|   - STORE ret_pc → -(A7)
|   - BR_JMP TMP1

    .text
    .org 0

_start:
    lea     0x00010000, %a7

    | Install A-trap table entries at 0x0400+0..0x0400+7*4
    | mem.L[0x400 + i*4] = address of _atrap_handler_i for i=0..7
    move.l  #_atrap_handler_0, 0x0400
    move.l  #_atrap_handler_1, 0x0404
    move.l  #_atrap_handler_2, 0x0408
    move.l  #_atrap_handler_3, 0x040C
    move.l  #_atrap_handler_4, 0x0410
    move.l  #_atrap_handler_5, 0x0414
    move.l  #_atrap_handler_6, 0x0418
    move.l  #_atrap_handler_7, 0x041C

    | Test 1: D2 = 0 → mem.L[0x400] = handler 0
    moveq   #0, %d2
    bsr     _do_jsr
    cmp.l   #0xCAFE0000, %d1
    bne     _fail_t1

    | Test 2: D2 = 1 → handler 1
    moveq   #1, %d2
    bsr     _do_jsr
    cmp.l   #0xCAFE0001, %d1
    bne     _fail_t2

    | Test 3: D2 = 2
    moveq   #2, %d2
    bsr     _do_jsr
    cmp.l   #0xCAFE0002, %d1
    bne     _fail_t3

    | Test 4: D2 = 4
    moveq   #4, %d2
    bsr     _do_jsr
    cmp.l   #0xCAFE0004, %d1
    bne     _fail_t4

    | Test 5: D2 with garbage in upper word — verify .W truncation
    | D2 = 0x12340003: low word = 3, high word = 0x1234
    move.l  #0x12340003, %d2
    bsr     _do_jsr
    cmp.l   #0xCAFE0003, %d1
    bne     _fail_t5

    | Test 6: D2 = 0xFFFF0005 — sign-extension red herring
    move.l  #0xFFFF0005, %d2
    bsr     _do_jsr
    cmp.l   #0xCAFE0005, %d1
    bne     _fail_t6

    | Test 7: 8 back-to-back JSRs (TMP1/TMP2 hazard sweep, no other ops)
    moveq   #7, %d2
    bsr     _do_jsr
    cmp.l   #0xCAFE0007, %d1
    bne     _fail_t7a
    moveq   #6, %d2
    bsr     _do_jsr
    cmp.l   #0xCAFE0006, %d1
    bne     _fail_t7b
    moveq   #5, %d2
    bsr     _do_jsr
    cmp.l   #0xCAFE0005, %d1
    bne     _fail_t7c
    moveq   #4, %d2
    bsr     _do_jsr
    cmp.l   #0xCAFE0004, %d1
    bne     _fail_t7d
    moveq   #3, %d2
    bsr     _do_jsr
    cmp.l   #0xCAFE0003, %d1
    bne     _fail_t7e
    moveq   #2, %d2
    bsr     _do_jsr
    cmp.l   #0xCAFE0002, %d1
    bne     _fail_t7f
    moveq   #1, %d2
    bsr     _do_jsr
    cmp.l   #0xCAFE0001, %d1
    bne     _fail_t7g
    moveq   #0, %d2
    bsr     _do_jsr
    cmp.l   #0xCAFE0000, %d1
    bne     _fail_t7h

    | All passed
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

| _do_jsr: invoke the memory-indirect preindexed JSR, exact replica of
|   ROM 0x40809A04 shape.  Emit raw bytes via .word so assembler
|   doesn't second-guess us.
_do_jsr:
    .word   0x4EB0, 0x25A1, 0x0400     | JSR ([0x400 + D2.W*4])
    rts                                | return to BSR caller after JSR returns

_atrap_handler_0:
    move.l  #0xCAFE0000, %d1
    rts
_atrap_handler_1:
    move.l  #0xCAFE0001, %d1
    rts
_atrap_handler_2:
    move.l  #0xCAFE0002, %d1
    rts
_atrap_handler_3:
    move.l  #0xCAFE0003, %d1
    rts
_atrap_handler_4:
    move.l  #0xCAFE0004, %d1
    rts
_atrap_handler_5:
    move.l  #0xCAFE0005, %d1
    rts
_atrap_handler_6:
    move.l  #0xCAFE0006, %d1
    rts
_atrap_handler_7:
    move.l  #0xCAFE0007, %d1
    rts

_fail_t1:
    move.l  #0xDEAD0001, %d3
    bra     _write_fail
_fail_t2:
    move.l  #0xDEAD0002, %d3
    bra     _write_fail
_fail_t3:
    move.l  #0xDEAD0003, %d3
    bra     _write_fail
_fail_t4:
    move.l  #0xDEAD0004, %d3
    bra     _write_fail
_fail_t5:
    move.l  #0xDEAD0005, %d3
    bra     _write_fail
_fail_t6:
    move.l  #0xDEAD0006, %d3
    bra     _write_fail
_fail_t7a:
    move.l  #0xDEAD0011, %d3
    bra     _write_fail
_fail_t7b:
    move.l  #0xDEAD0012, %d3
    bra     _write_fail
_fail_t7c:
    move.l  #0xDEAD0013, %d3
    bra     _write_fail
_fail_t7d:
    move.l  #0xDEAD0014, %d3
    bra     _write_fail
_fail_t7e:
    move.l  #0xDEAD0015, %d3
    bra     _write_fail
_fail_t7f:
    move.l  #0xDEAD0016, %d3
    bra     _write_fail
_fail_t7g:
    move.l  #0xDEAD0017, %d3
    bra     _write_fail
_fail_t7h:
    move.l  #0xDEAD0018, %d3
    bra     _write_fail
_write_fail:
    lea     0xFFFF0000, %a0
    move.l  %d3, (%a0)
_halt_fail:
    bra     _halt_fail
