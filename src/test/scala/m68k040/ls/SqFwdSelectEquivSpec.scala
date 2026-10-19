package m68k040.ls

import m68k040.M68kSim
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._

/** CPU_SQ_FWD_ONEHOT: the one-hot forward-source select must return EXACTLY what the
  * tournament tree returns (valid flag and the selected payload, including the no-valid
  * default) for every overlap vector and every head pointer. Exhaustive over valid x head
  * for depths 4, 8 and 16 (16: random valid vectors), random payloads. */
class SqFwdSelectEquivSpec extends AnyFunSuite {
  class Dut(depth: Int) extends Component {
    val pw    = log2Up(depth)
    val valid = in(Bits(depth bits))
    val head  = in(UInt(pw bits))
    val pay   = in(Vec(Bits(35 bits), depth))
    val (tv, tp) = SqFwdSelect.tree(valid, head, pay.toSeq)
    val (ov, op) = SqFwdSelect.oneHot(valid, head, pay.toSeq)
    val same  = out(Bool())
    same := (tv === ov) && (tp === op)
  }

  for (depth <- Seq(4, 8, 16)) test(s"one-hot == tree, depth $depth") {
    M68kSim().withVerilator.compile(new Dut(depth)).doSim(seed = 0x5F0 + depth) { d =>
      val rnd = new scala.util.Random(0x5F0 + depth)
      val vals: Seq[Int] =
        if (depth <= 8) 0 until (1 << depth)
        else (0 until 20000).map(_ => rnd.nextInt(1 << 16)) ++ Seq(0, 0xFFFF, 1, 0x8000)
      var n = 0
      for (v <- vals; h <- 0 until depth; rep <- 0 until 4) {
        d.valid #= v
        d.head #= h
        d.pay.foreach(_.randomize())
        sleep(1)
        assert(d.same.toBoolean, f"diverged: depth=$depth valid=$v%x head=$h")
        n += 1
      }
      println(s"[SqFwdSelectEquiv] depth=$depth cases=$n all equal")
    }
  }
}
