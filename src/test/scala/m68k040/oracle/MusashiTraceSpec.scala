package m68k040.oracle

import org.scalatest.funsuite.AnyFunSuite

class MusashiTraceSpec extends AnyFunSuite {
  test("per-instruction trace yields one step per executed instruction with post-state") {
    val src =
      """    moveq #1,%d0
        |    moveq #2,%d1
        |    add.l %d1,%d0
        |    move.l %d0,0xFFFF0000
        |""".stripMargin
    Musashi.assembleAndTrace(src) match {
      case Right(steps) =>
        assert(steps.size >= 3, s"expected >=3 steps, got ${steps.size}")
        assert(steps(0).d(0) == 1L, s"step0 D0 should be 1, got ${steps(0).d(0)}")
        val afterAdd = steps.find(s => s.d(0) == 3L)
        assert(afterAdd.isDefined, s"no step shows D0==3 after the add; steps=${steps.map(_.d(0))}")
        assert(steps.map(_.pc).distinct.size == steps.size, "PCs should be distinct in straight-line code")
      case Left(err) => fail(s"oracle error: ${err.reason}")
    }
  }
}
