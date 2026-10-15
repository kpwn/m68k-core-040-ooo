package m68k040.frontend

import m68k040.M68kSim
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._

/** CPU_PREDECODE_CODED: `PredecodeCoded.classify` (length/ambiguity carried as a code, one
  * add at the end, per-word `ix` shared) must be BIT-IDENTICAL to `PredecodeWord.classify`
  * on the whole ChunkPredecode record, for every opword, with random lookahead words and
  * ALL eight lookahead-validity patterns (not only the monotone ones the refill produces). */
class PredecodeCodedEquivSpec extends AnyFunSuite {
  class Dut extends Component {
    val op = in Bits(16 bits)
    val w  = in Vec(Bits(16 bits), 3)
    val k  = in Vec(Bool(), 3)
    val a = PredecodeWord.classify(op, w(0), w(1), w(2), extWValid = k(0), extW2Valid = k(1), extW3Valid = k(2))
    val b = PredecodeCoded.classify(op, w(0), w(1), w(2), k(0), k(1), k(2),
      PredecodeCoded.ix(w(0), k(0)), PredecodeCoded.ix(w(1), k(1)), PredecodeCoded.ix(w(2), k(2)))
    val same = out(Bool())
    same := a.asBits === b.asBits
    val aBits = out(Bits(a.getBitsWidth bits)); aBits := a.asBits
    val bBits = out(Bits(b.getBitsWidth bits)); bBits := b.asBits
    val len = out(UInt(4 bits)); len := a.lenWords
    val amb = out(Bool()); amb := a.ambiguousLine
  }

  test("CPU_PREDECODE_CODED classify == PredecodeWord.classify, exhaustive opwords") {
    M68kSim().withVerilator.compile(new Dut).doSim(seed = 0x9D) { d =>
      val rnd = new scala.util.Random(0x9D)
      var n = 0L
      val lenHist = Array.fill(16)(0L)
      var ambN = 0L
      // rounds 0-15: fully random ext words; 16-23: ext words forced to full-format
      // index shape (bit8 set) with random bd/od size fields, so every fullExtLen arm is hit at
      // every lookahead position; validity pattern = random over all 8.
      for (round <- 0 until 24; op <- 0 until 65536) {
        d.op #= op
        for (j <- 0 until 3) {
          var x = rnd.nextInt(0x10000)
          if (round >= 16) x |= 0x0100
          d.w(j) #= x
          d.k(j) #= (if (round < 4) true else rnd.nextBoolean())
        }
        sleep(1)
        if (!d.same.toBoolean)
          fail(f"diverged: round=$round op=$op%04x w=${d.w.map(_.toInt).map(v => f"$v%04x").mkString(",")} " +
               f"k=${d.k.map(_.toBoolean).mkString(",")} a=${d.aBits.toBigInt}%x b=${d.bBits.toBigInt}%x")
        lenHist(d.len.toInt) += 1
        if (d.amb.toBoolean) ambN += 1
        n += 1
      }
      println(s"PREDECODE_CODED equiv: $n vectors, ambiguous=$ambN, lenWords histogram=" +
        lenHist.zipWithIndex.filter(_._1 > 0).map { case (c, l) => s"$l:$c" }.mkString(" "))
      assert((1 to 8).forall(l => lenHist(l) > 0), "every reachable length 1..8 must be exercised (3 lookahead words)")
      assert(ambN > 0)
    }
  }
}
