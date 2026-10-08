package m68k040.cache

/** L1D geometry as the tests must see it, following `CPU_DCACHE_KB` (8 -> 128 sets,
  * 16 -> 256 sets; always 4 ways x 16-byte lines). `WayStride` is one way's bytes: the
  * smallest address delta that lands in the SAME set with a DIFFERENT tag. */
object DcacheTestGeometry {
  val Sets: Int        = m68k040.top.ShippingCoreConfig.dcacheKB * 1024 / 64
  val SetMask: Long    = (Sets - 1).toLong
  val WayStride: Long  = Sets.toLong * 16L
  def setOf(addr: Long): Int = ((addr >> 4) & SetMask).toInt
}
