package m68k040.fuzz

import m68k040.VerilatorTest
import org.scalatest.funsuite.AnyFunSuite

/** Independent memory-order checks: a same-implementation round trip can hide
  * reversing both the store and load register walks. */
class FmovemDataInteropSpec extends AnyFunSuite {
  for (dynamic <- Seq(false, true)) {
    test(s"${if(dynamic) "dynamic" else "static"} FMOVEM predecrement image and postincrement restore", VerilatorTest) {
      // Predecrement uses bit0=FP0; control/postincrement uses bit7=FP0.
      // Inspect memory independently before restoring, so paired ordering bugs
      // cannot cancel each other out in a round trip.
      val save = if(dynamic) "moveq #3,%d0\n.short 0xf220,0xe800"
                 else ".short 0xf220,0xe003"
      val restore = if(dynamic) "move.l #0xc0,%d0\n.short 0xf218,0xd800"
                    else ".short 0xf218,0xd0c0"
      val source = s"""
        .text
        .org 0
      _start:
        lea 0x11000,%sp
        move.l #fail_trap,0x08
        move.l #fail_trap,0x0c
        move.l #fail_trap,0x10
        move.l #fail_trap,0x2c
        lea 0x21018,%a0
        fmove.l #11,%fp0
        fmove.l #22,%fp1
        $save
        cmpa.l #0x21000,%a0
        bne fail_address
        cmpi.w #0x4002,(%a0)
        bne fail_image
        cmpi.l #0xb0000000,4(%a0)
        bne fail_image
        tst.l 8(%a0)
        bne fail_image
        cmpi.w #0x4003,12(%a0)
        bne fail_image
        cmpi.l #0xb0000000,16(%a0)
        bne fail_image
        tst.l 20(%a0)
        bne fail_image
        fmove.l #0,%fp0
        fmove.l #0,%fp1
        $restore
        cmpa.l #0x21018,%a0
        bne fail_address
        fmove.l %fp0,%d1
        cmpi.l #11,%d1
        bne fail_restore
        fmove.l %fp1,%d1
        cmpi.l #22,%d1
        bne fail_restore
        move.l #0xc0ffee00,0xffff0000
      done: bra done
      fail_address: move.l #0xdead2010,0xffff0000
        bra done
      fail_image: move.l #0xdead2011,0xffff0000
        bra done
      fail_restore: move.l #0xdead2012,0xffff0000
        bra done
      fail_trap: move.l #0xdead2013,0xffff0000
        bra done
      """
      PortedTestRunner.run(s"fmovem_auto_image_$dynamic", source,200000L,
        allowBkptCompletion=false) match {
        case PortedPass => ()
        case other => fail(s"independent auto-address check failed: $other")
      }
    }
  }

  for (dynamic <- Seq(false,true); padded <- Seq(false,true)) {
    test(s"${if(dynamic) "dynamic" else "static"} FMOVEM control store places FP0 before FP1 (pad=$padded)", VerilatorTest) {
      val pad = if(padded) "nop\n" else ""
      val transfer = if(dynamic) "move.l #0xc0,%d0\n"+pad+".short 0xf210,0xf800"
                     else pad+".short 0xf210,0xf0c0"
      val source = s"""
        .text
        .org 0
      _start:
        lea 0x11000,%sp
        move.l #fail_bus,0x08
        move.l #fail_address,0x0c
        move.l #fail_illegal,0x10
        move.l #fail_fline,0x2c
        lea 0x21000,%a0
        fmove.l #11,%fp0
        fmove.l #22,%fp1
        $transfer
        cmpi.w #0x4002,(%a0)
        bne fail_order
        cmpi.l #0xb0000000,4(%a0)
        bne fail_bits
        tst.l 8(%a0)
        bne fail_bits
        cmpi.w #0x4003,12(%a0)
        bne fail_order
        cmpi.l #0xb0000000,16(%a0)
        bne fail_bits
        tst.l 20(%a0)
        bne fail_bits
        move.l #0xc0ffee00,0xffff0000
      done: bra done
      fail_order: move.l #0xdead2001,0xffff0000
        bra done
      fail_bits: move.l #0xdead2002,0xffff0000
        bra done
      fail_bus: move.l #0xdead2003,0xffff0000
        bra done
      fail_address: move.l #0xdead2004,0xffff0000
        bra done
      fail_illegal: move.l #0xdead2005,0xffff0000
        bra done
      fail_fline: move.l #0xdead2006,0xffff0000
        bra done
      """
      PortedTestRunner.run(s"fmovem_${if(dynamic) "dynamic" else "static"}_memory_order_$padded",
        source,200000L,allowBkptCompletion=false) match {
        case PortedPass => ()
        case other => fail(s"independent memory-order check failed: $other")
      }
    }
  }
}
