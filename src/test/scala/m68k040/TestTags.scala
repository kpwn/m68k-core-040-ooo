package m68k040

import org.scalatest.Tag

/** Tags excluded by `fastTest` (see build.sbt). */
object SlowTest      extends Tag("m68k040.SlowTest")
object VerilatorTest extends Tag("m68k040.VerilatorTest")
object BoardTest     extends Tag("m68k040.BoardTest")
