package m68k040.fuzz

import m68k040.VerilatorTest
import org.scalatest.funsuite.AnyFunSuite

/** Full pipeline/FSAVE/RTE protocol check, not a replacement FPSP implementation.
  * The handler checks real operands before emulating the one tested scale factor.
  * A later ROM-FPSP test must independently establish handler compatibility. */
class FpImmediateEmulationSpec extends AnyFunSuite {
  test("ROM FPSP completes FSCALE immediate before native FDIV", VerilatorTest) {
    // Reuse the verbatim ROM fixture, not its weaker packed-result assertions.
    val resource = scala.io.Source.fromResource("m68kooo-ported-tests/asm/fpsp_packed_kernel_e2e.s")
    val fixture = try resource.mkString finally resource.close()
    val marker = "    .org    0x8d000"
    val start = fixture.indexOf(marker)
    assert(start >= 0, "ROM fixture must retain its native-address origin")
    val source = """
      .text
      .org 0
    _start:
      lea 0x11000,%a7
      move.l #fail_bus,0x08
      move.l #fail_address,0x0c
      move.l #fail_illegal,0x10
      move.l #fail_aline,0x28
      move.l #gate,0x2c
      move.l #fail_chain,0x1fc8
      clr.l 0x21000
      fmove.l #12,%fp2
      fmove.l #3,%fp1
    subject:
      .short 0xf23c,0x5926,0x0001
    resume:
      .short 0xf200,0x0520
      fmove.l %fp2,%d0
      cmpi.l #8,%d0
      bne fail_result
      move.l %sp,%d0
      cmpi.l #0x11000,%d0
      bne fail_stack
      cmpi.l #1,0x21000
      bne fail_count
      move.l #0xc0ffee00,0xffff0000
    done:
      bra done
    gate:
      addq.l #1,0x21000
      cmpi.l #1,0x21000
      bne fail_count
      cmpi.w #0x202c,6(%sp)
      bne fail_frame
      cmpi.l #resume,2(%sp)
      bne fail_pc
      jmp 0x4088d9fe
    fail_bus: move.l #0xdead1002,0xffff0000
      bra done
    fail_address: move.l #0xdead1003,0xffff0000
      bra done
    fail_illegal: move.l #0xdead1004,0xffff0000
      bra done
    fail_aline: move.l #0xdead100a,0xffff0000
      bra done
    fail_chain: move.l #0xdead101c,0xffff0000
      bra done
    fail_result: move.l #0xdead1020,0xffff0000
      bra done
    fail_stack: move.l #0xdead1021,0xffff0000
      bra done
    fail_count: move.l #0xdead1022,0xffff0000
      bra done
    fail_frame: move.l #0xdead1023,0xffff0000
      bra done
    fail_pc: move.l #0xdead1024,0xffff0000
      bra done
    """ + fixture.substring(start)
    // Only the numerical/stack-check sentinel can pass this test. Embedded ROM
    // bytes or an erroneous jump into a BKPT must never count as completion.
    PortedTestRunner.run("fscale_immediate_rom_fpsp", source, 1000000L,
      allowBkptCompletion = false) match {
      case PortedPass => ()
      case PortedFail(word) => fail(f"ROM FPSP failure sentinel 0x$word%08x")
      case other => fail(s"ROM FPSP did not finish: $other")
    }
  }

  test("FSCALE immediate captures real operands and returns to native FDIV", VerilatorTest) {
    val source = """
      .text
      .org 0
    _start:
      lea 0x10000,%a7
      move.l #handler,0x2c
      moveq #19,%d7
    again:
      fmove.l #12,%fp2
      fmove.l #3,%fp1
    subject:
      .short 0xf23c,0x5926
    operand:
      .short 1
    resume:
      .short 0xf200,0x0520
      fmove.l %fp2,%d0
      cmpi.l #8,%d0
      bne fail_result
      dbra %d7,again
      move.l #0xc0ffee00,0xffff0000
    done:
      bra done
    handler:
      cmpi.w #0x202c,6(%sp)
      bne fail_frame
      cmpi.l #resume,2(%sp)
      bne fail_pc
      cmpi.l #operand,8(%sp)
      bne fail_ea
      fmove.l %fpiar,%d1
      cmpi.l #subject,%d1
      bne fail_fpiar
      lea 0x8000,%a0
      fsave (%a0)
      cmpi.b #0x30,1(%a0)
      bne fail_fsave
      cmpi.w #0x5926,16(%a0)
      bne fail_cmd
      btst #2,24(%a0)
      bne fail_cmd
      cmpi.w #0x3fff,40(%a0)
      bne fail_src
      cmpi.l #0x80000000,44(%a0)
      bne fail_src
      tst.l 48(%a0)
      bne fail_src
      cmpi.w #0x4002,28(%a0)
      bne fail_dst
      cmpi.l #0xc0000000,32(%a0)
      bne fail_dst
      tst.l 36(%a0)
      bne fail_dst
      | Consume the saved destination, not a magic expected-result constant.
      fmove.x 28(%a0),%fp2
      fadd.x %fp2,%fp2
      rte
    fail_frame: move.l #0xdead0001,0xffff0000
      bra done
    fail_pc: move.l #0xdead0002,0xffff0000
      bra done
    fail_ea: move.l #0xdead0003,0xffff0000
      bra done
    fail_fpiar: move.l #0xdead0004,0xffff0000
      bra done
    fail_fsave: move.l #0xdead0005,0xffff0000
      bra done
    fail_cmd: move.l #0xdead0006,0xffff0000
      bra done
    fail_src: move.l #0xdead0007,0xffff0000
      bra done
    fail_dst: move.l #0xdead0008,0xffff0000
      bra done
    fail_result: move.l #0xdead0009,0xffff0000
      bra done
    """
    PortedTestRunner.run("fscale_immediate_emulation", source, 300000L) match {
      case PortedPass => ()
      case PortedFail(word) => fail(f"handler failure sentinel 0x$word%08x")
      case other => fail(s"emulation did not finish: $other")
    }
  }
}
