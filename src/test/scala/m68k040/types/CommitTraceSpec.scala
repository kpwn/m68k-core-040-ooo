package m68k040.types

import spinal.core._
import org.scalatest.funsuite.AnyFunSuite

class CommitTraceSpec extends AnyFunSuite {
  test("CommitTrace exposes the lock-step compare fields") {
    SpinalConfig().generateVerilog(new Component {
      val t = out(CommitTrace())
      t.assignDontCare()
      assert(t.pc.getWidth == 32)
      assert(t.archRegWrite.getWidth == 32)
      assert(t.ccr.getWidth == 5)
    })
  }
}
