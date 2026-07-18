| sign_extension.s — EXT, EXTB sign-extension instructions
|
| Tests byte-to-word and word-to-long sign extension.
| Key cases:
|   1. EXT.W: byte sign-extend to word (within lower 16 bits of Dn)
|   2. EXT.L: word sign-extend to longword (full 32-bit)
|   3. EXTB.L: byte sign-extend to longword (full 32-bit)
|   4. Positive source (high bit 0) → upper bits clear
|   5. Negative source (high bit 1) → upper bits set to 1

    .text
    .org 0

_start:
    | Test 1: EXT.W — byte to word sign extension
    | Set D0 = 0xFFFFFF81 (byte 0x81 = -127 in signed 8-bit)
    | EXT.W should sign-extend the byte to word: result = 0xFFFFFF81
    move.l  #0xFFFFFF81, %d0
    ext.w   %d0                     | D0 should still be 0xFFFFFF81 (lower 16 bits = 0xFF81)

    lea     0x00100000, %a0

    | Test 2: EXT.L — word to longword sign extension
    | Set D1 = 0x00008001 (word 0x8001 = -32767 in signed 16-bit)
    | EXT.L should sign-extend: result = 0xFFFF8001
    move.l  #0x00008001, %d1
    ext.l   %d1                     | D1 = 0xFFFF8001

    lea     0x00200000, %a1

    | Test 3: EXT.L with positive word
    | Set D2 = 0x00007FFF (word 0x7FFF = +32767)
    | EXT.L: result = 0x00007FFF (upper 16 bits clear)
    move.l  #0x00007FFF, %d2
    ext.l   %d2                     | D2 = 0x00007FFF

    lea     0x00300000, %a2

    | Test 4: EXTB.L — byte to longword sign extension
    | Set D3 = 0xFFFFFFFF
    | EXTB.L the byte (0xFF = -1) to longword: result = 0xFFFFFFFF
    move.l  #0xFFFFFFFF, %d3
    extb.l  %d3                     | D3 = 0xFFFFFFFF

    lea     0x00400000, %a3

    | Test 5: EXTB.L with positive byte
    | Set D4 = 0xFFFFFF7F (byte 0x7F = +127)
    | EXTB.L: result = 0x0000007F
    move.l  #0xFFFFFF7F, %d4
    extb.l  %d4                     | D4 = 0x0000007F

    lea     0x00500000, %a4

    | Test 6: Chain sign extensions
    | D5 = 0xFFFFFF80 (byte 0x80 = -128)
    | EXTB.L → D5 = 0xFFFFFF80
    move.l  #0xFFFFFF80, %d5
    extb.l  %d5

    lea     0x00600000, %a5

    | If we reach here, all sign extensions tested; signal PASS
    lea     0xFFFF0000, %a6
    move.l  #0xC0FFEE00, %d6
    move.l  %d6, (%a6)              | PASS

_halt:
    stop    #0x2700
    bra     _halt

_fail:
    lea     0xFFFF0000, %a6
    move.l  #0xDEADBEEF, %d6
    move.l  %d6, (%a6)
    stop    #0x2700
    bra     _halt
