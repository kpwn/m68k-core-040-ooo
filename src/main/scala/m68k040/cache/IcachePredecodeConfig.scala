package m68k040.cache

/** Explicit elaboration-time setting; IcachePlugin itself has a fixed default. */
object IcachePredecodeConfig {
  def parse(value: String): Int = value match {
    case "8" => 8
    case "16" => 16
    case other => throw new IllegalArgumentException(s"ICACHE_PREDECODE_WORDS must be 8 or 16, got $other")
  }
  def fromEnvironment: Int = parse(sys.env.getOrElse("ICACHE_PREDECODE_WORDS", "16"))
}
