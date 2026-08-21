package m68k040.sim

import org.scalatest.funsuite.AnyFunSuite
import spinal.lib.sim.SparseMemory

class ProgramImageLoaderSpec extends AnyFunSuite {
  test("I-fetch loader preserves big-endian bytes and appends an exact guard") {
    val mem = SparseMemory(0L, 0L)
    val base = 0x40800000L

    AxiMemModel.loadProgramIFetch(mem, base, Vector(0x12, 0x34, 0xab, 0xcd))
    AxiMemModel.fillIFetchRunAheadGuard(mem, base + 4, words = 3)

    assert((mem.read(base) & 0xff) == 0x12)
    assert((mem.read(base + 1) & 0xff) == 0x34)
    assert((mem.read(base + 2) & 0xff) == 0xab)
    assert((mem.read(base + 3) & 0xff) == 0xcd)
    for (i <- 0 until 3) {
      assert((mem.read(base + 4 + 2L * i) & 0xff) == 0x60)
      assert((mem.read(base + 5 + 2L * i) & 0xff) == 0xfe)
    }
  }

  test("I-fetch loader rejects partial opwords") {
    val mem = SparseMemory(0L, 0L)
    intercept[IllegalArgumentException] {
      AxiMemModel.loadProgramIFetch(mem, 0L, Vector(0x4e, 0x71, 0xff))
    }
  }
}
