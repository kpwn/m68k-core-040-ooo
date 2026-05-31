package m68k040.cache

import org.scalatest.funsuite.AnyFunSuite

class CacheGeometrySpec extends AnyFunSuite {
  val g = CacheGeometry(cacheBytes = 16 * 1024, lineBytes = 64, ways = 4,
    indexingPolicy = CacheIndexingPolicy.Vipt)

  test("derived geometry for the L1I") {
    assert(g.sets == 64)
    assert(g.offsetBits == 6)
    assert(g.indexBits == 6)
    assert(g.tagBits == 20)
    assert(g.virtualIndexBits == 12)
  }
  test("address decomposition") {
    val a = 0x40801234L
    assert(g.offset(a) == 0x34)
    assert(g.index(a) == ((0x40801234L >>> 6) & 0x3f).toInt)
    assert(g.tag(a) == (0x40801234L >>> 12))
  }
  test("VIPT-safe under 4 KiB pages (no coloring)") {
    assert(g.isViptSafe(4096))
  }
}
