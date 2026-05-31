package m68k040.oracle

import org.scalatest.funsuite.AnyFunSuite

class MusashiOracleSpec extends AnyFunSuite {
  test("assemble and run a tiny program to final state") {
    val src =
      """    moveq #7,%d0
        |    move.l %d0,0xFFFF0000
        |""".stripMargin
    Musashi.assembleAndRun(src) match {
      case Right(st) =>
        assert(st.sentinelHit, "expected the move to the sentinel address to stop the run")
        assert(st.d(0) == 7L, s"D0 should be 7, got ${st.d(0)}")
      case Left(err) => fail(s"oracle error: ${err.reason}")
    }
  }
}
