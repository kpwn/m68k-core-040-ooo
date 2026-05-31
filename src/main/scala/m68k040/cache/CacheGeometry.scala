package m68k040.cache

sealed trait CacheIndexingPolicy {
  def indexesWithPhysicalAddress: Boolean
  def tagsWithPhysicalAddress: Boolean
}

object CacheIndexingPolicy {
  case object Pipt extends CacheIndexingPolicy {
    val indexesWithPhysicalAddress = true
    val tagsWithPhysicalAddress = true
  }

  case object Vipt extends CacheIndexingPolicy {
    val indexesWithPhysicalAddress = false
    val tagsWithPhysicalAddress = true
  }
}

case class CacheGeometry(
  cacheBytes: Int,
  lineBytes: Int,
  ways: Int,
  indexingPolicy: CacheIndexingPolicy = CacheIndexingPolicy.Pipt
) {
  require(cacheBytes > 0)
  require(lineBytes > 0)
  require(ways > 0)
  require(isPowerOfTwo(cacheBytes), "cache size must be a power of two")
  require(isPowerOfTwo(lineBytes), "line size must be a power of two")
  require(cacheBytes % (lineBytes * ways) == 0, "cache must contain an integer number of sets")

  val sets: Int = cacheBytes / (lineBytes * ways)
  val offsetBits: Int = log2(lineBytes)
  val indexBits: Int = log2(sets)
  val virtualIndexBits: Int = offsetBits + indexBits
  val tagBits: Int = 32 - offsetBits - indexBits
  val indexesWithPhysicalAddress: Boolean = indexingPolicy.indexesWithPhysicalAddress
  val tagsWithPhysicalAddress: Boolean = indexingPolicy.tagsWithPhysicalAddress

  require(virtualIndexBits <= 32, "cache index plus line offset must fit the 32-bit address")

  def offset(address: Long): Int =
    (address & (lineBytes - 1)).toInt

  def index(address: Long): Int =
    ((address >>> offsetBits) & (sets - 1)).toInt

  def tag(address: Long): Long =
    (address >>> (offsetBits + indexBits)) & ((1L << tagBits) - 1L)

  def isViptSafe(pageBytes: Int): Boolean = {
    require(pageBytes > 0, "page size must be positive")
    require(isPowerOfTwo(pageBytes), "page size must be a power of two")
    virtualIndexBits <= log2(pageBytes)
  }

  def requireViptSafe(pageBytes: Int, owner: String): Unit =
    require(
      isViptSafe(pageBytes),
      s"$owner VIPT geometry is alias-prone: index+offset=$virtualIndexBits bits, pageBits=${log2(pageBytes)}")

  def requirePolicySafe(pageBytes: Int, owner: String): Unit =
    indexingPolicy match {
      case CacheIndexingPolicy.Pipt =>
        ()
      case CacheIndexingPolicy.Vipt =>
        requireViptSafe(pageBytes, owner)
    }

  private def isPowerOfTwo(value: Int): Boolean =
    (value & (value - 1)) == 0

  private def log2(value: Int): Int =
    Integer.numberOfTrailingZeros(value)
}

object CacheGeometry {
  val l1i040: CacheGeometry =
    CacheGeometry(cacheBytes = 16 * 1024, lineBytes = 64, ways = 4,
      indexingPolicy = CacheIndexingPolicy.Vipt)
}
