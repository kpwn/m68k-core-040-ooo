package m68k040.fuzz

import m68k040.VerilatorTest
import org.scalatest.funsuite.AnyFunSuite

/** Full decode/rename/retire coverage, beyond the standalone cold sequencer.
  * Independent integer-derived images prevent inverse store/load bugs hiding.
  */
class FmovemDynamicMaskCoreSpec extends AnyFunSuite {
  private def program(masks: Seq[Int]): String = {
    val cases = masks.map { mask =>
      val selected = (0 until 8).filter(r => (mask & (1 << r)) != 0)
      val loadMask = Integer.reverse(mask) >>> 24
      val maskRegister = mask & 7
      val lowerGuard = 0x21060 - selected.size*12 - 4
      val initialize = (0 until 8).map(r => s"fmove.l #${11 + 13*r},%fp$r").mkString("\n")
      val clear = (0 until 8).map(r => s"fmove.l #0,%fp$r").mkString("\n")
      val imageChecks = selected.zipWithIndex.map { case (r, slot) =>
        val value = BigInt(11 + 13*r)
        val exponent = value.bitLength - 1
        val mantissa = value << (63 - exponent)
        val offset = slot * 12
        s"""
          cmpi.w #${0x3fff + exponent},$offset(%a7)
          bne.l fail_image
          cmpi.l #0x${(mantissa >> 32).toString(16)},${offset+4}(%a7)
          bne.l fail_image
          cmpi.l #0x${(mantissa & BigInt("ffffffff",16)).toString(16)},${offset+8}(%a7)
          bne.l fail_image
        """
      }.mkString("\n")
      val registerChecks = (0 until 8).map { r =>
        val expected = if(selected.contains(r)) 11 + 13*r else 0
        s"fmove.l %fp$r,%d1\ncmpi.l #$expected,%d1\nbne.l fail_register"
      }.mkString("\n")
      s"""
        move.l #$mask,0x22000
        move.l #0x13579bdf,$lowerGuard
        move.l #0x2468ace0,0x21060
        $initialize
        move.l #0xa5a500${f"$mask%02x"},%d$maskRegister
        .short 0xf227,${0xe800 | (maskRegister << 4)}
        cmpa.l #${0x21060 - selected.size*12},%a7
        bne.l fail_address
        $imageChecks
        cmpi.l #0x13579bdf,$lowerGuard
        bne.l fail_image
        cmpi.l #0x2468ace0,0x21060
        bne.l fail_image
        $clear
        move.l #0x5a5a00${f"$loadMask%02x"},%d$maskRegister
        .short 0xf21f,${0xd800 | (maskRegister << 4)}
        cmpa.l #0x21060,%a7
        bne.l fail_address
        $registerChecks
      """
    }.mkString("\n")
    s"""
      .text
      .org 0
    _start:
      lea 0x21060,%a7
      move.l #fail_trap,0x08
      move.l #fail_trap,0x0c
      move.l #fail_trap,0x10
      move.l #fail_trap,0x2c
      $cases
      move.l #0xc0ffee00,0xffff0000
    done: bra done
    fail_address: move.l #0xdead2020,0xffff0000
      bra done
    fail_image: move.l #0xdead2021,0xffff0000
      bra done
    fail_register: move.l #0xdead2022,0xffff0000
      bra done
    fail_trap: move.l #0xdead2023,0xffff0000
      bra done
    """
  }

  test("dynamic FMOVEM all masks preserve A7 and unselected FP registers", VerilatorTest) {
    PortedTestRunner.run("fmovem_dynamic_all_masks_a7",program(0 until 256),3000000L,
      allowBkptCompletion=false) match {
      case PortedPass => ()
      case other => fail(s"full-core dynamic mask coverage failed: $other")
    }
  }

  test("dynamic FMOVEM with real MMU walks and copyback cache", VerilatorTest) {
    val result = MmuWalkDriver.runWithRealTables("fmovem_dynamic_mmu_a7",
      program(Seq(0,1,0x80,0x55,0xaa,0xff)),300000L,allowBkptCompletion=false)
    assert(result.outcome == PortedPass, s"translated FMOVEM failed: ${result.outcome}")
    assert(result.probe.itlbWalkStarts > 0 && result.probe.dtlbWalkStarts > 0,
      s"both translation ports must walk: ${result.probe.summary}")
    assert(result.probe.walkStores > 0 && result.probe.dcLoadHits > 0,
      s"descriptor updates and cache hits must be exercised: ${result.probe.summary}")
  }
}
