| Historical filename retained for corpus manifests. Dynamic FMOVEM is now
| implemented in the serialized backend, so legal forms must TRANSFER data.
| Static store -> dynamic D0 load, dynamic D3 store -> independent image checks.
| Illegal direction is separately required to take F-line, without memory writes.
    .text
    .org 0
_start:
    lea 0x10000,%sp
    move.l #_unexpected,0x08
    move.l #_unexpected,0x0c
    move.l #_unexpected,0x10
    move.l #_unexpected,0x2c
    lea 0x20000,%a0
    fmove.l #11,%fp0
    fmove.l #22,%fp1
    .short 0xf210,0xf0c0
    fmove.l #0,%fp0
    fmove.l #0,%fp1
    move.l #0xa5a500c0,%d0
    .short 0xf210,0xd800
    fmove.l %fp0,%d1
    cmpi.l #11,%d1
    bne _bad_data
    fmove.l %fp1,%d1
    cmpi.l #22,%d1
    bne _bad_data
    lea 0x20020,%a0
    move.l #0x5a5a00c0,%d3
    .short 0xf210,0xf830
    cmpi.w #0x4002,(%a0)
    bne _bad_data
    cmpi.l #0xb0000000,4(%a0)
    bne _bad_data
    tst.l 8(%a0)
    bne _bad_data
    cmpi.w #0x4003,12(%a0)
    bne _bad_data
    cmpi.l #0xb0000000,16(%a0)
    bne _bad_data
    tst.l 20(%a0)
    bne _bad_data
    move.l #_expected_fline,0x2c
    move.l #0x13579bdf,24(%a0)
    | Dynamic postincrement STORE is illegal (postincrement is load-only).
    .short 0xf218,0xf830
    move.l #0xdead1b03,0xffff0000
_done:
    bra _done
_expected_fline:
    cmpi.l #0x13579bdf,24(%a0)
    bne _bad_data
    move.l #0xc0ffee00,0xffff0000
    bra _done
_bad_data:
    move.l #0xdead1b02,0xffff0000
    bra _done
_unexpected:
    move.l #0xdead1b01,0xffff0000
    bra _done
