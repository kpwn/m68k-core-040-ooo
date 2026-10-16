package m68k040.frontend

import m68k040.M68kSim
import m68k040.cache.ChunkPredecode
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._

/** CPU_PREDECODE_COMMON_ONLY study: dynamic frequency of each `PredecodeCoded` coverage cut.
  * Input: `PDC_FILES` = comma-separated histogram files, one executed-instruction shape per
  * line: `op w1 w2 w3 count` (-1 = lookahead word never fetched -> invalid), produced from
  * the MAME 7.5.3 trace windows. For each cut, counts the executed instructions that the
  * baseline classifies SIMPLE and RESOLVED but the cut turns ambiguous (= extra slow-path
  * events). Also re-checks the cut contract: a cut never changes a resolved length. */
class PredecodeCutFreqSpec extends AnyFunSuite {
  val cuts: Seq[Set[String]] = PredecodeCoded.cutNames.map(Set(_)) ++
    sys.env.get("PDC_COMBOS").toSeq.flatMap(_.split(';').map(_.split('+').toSet))

  class Dut extends Component {
    val op = in Bits(16 bits)
    val w  = in Vec(Bits(16 bits), 3)
    val k  = in Vec(Bool(), 3)
    def run(drop: Set[String]): ChunkPredecode = {
      val v = (0 until 3).map(j => PredecodeCoded.cutView(w(j), k(j), j + 1, drop))
      PredecodeCoded.classifyD(op, w(0), w(1), w(2), v(0)._1, v(1)._1, v(2)._1,
        v(0)._2, v(1)._2, v(2)._2, v.map(_._3), drop)
    }
    val base = PredecodeWord.classify(op, w(0), w(1), w(2), extWValid = k(0), extW2Valid = k(1), extW3Valid = k(2))
    val baseLen = out(UInt(4 bits)); baseLen := base.lenWords
    val baseAmb = out(Bool()); baseAmb := base.ambiguousLine
    val cutAmb = out(Bits(cuts.size bits))
    val cutBad = out(Bits(cuts.size bits))
    for ((c, i) <- cuts.zipWithIndex) {
      val r = run(c)
      cutAmb(i) := r.ambiguousLine
      // contract: resolved under the cut => identical record to the baseline
      cutBad(i) := !r.ambiguousLine && (r.lenWords =/= base.lenWords || base.ambiguousLine)
    }
  }

  test("predecode cut frequencies") {
    val files = sys.env.getOrElse("PDC_FILES", "").split(',').filter(_.nonEmpty)
    assume(files.nonEmpty, "set PDC_FILES")
    M68kSim().withVerilator.compile(new Dut).doSim { d =>
      for (f <- files) {
        var total = 0L; var simple = 0L; var amb = 0L
        val extra = Array.fill(cuts.size)(0L); val bad = Array.fill(cuts.size)(0L)
        for (line <- scala.io.Source.fromFile(f).getLines()) {
          val a = line.trim.split(' ').map(_.toLong)
          d.op #= a(0)
          for (j <- 0 until 3) { d.w(j) #= (if (a(j + 1) < 0) 0 else a(j + 1)); d.k(j) #= a(j + 1) >= 0 }
          sleep(1)
          val c = a(4); total += c
          val bAmb = d.baseAmb.toBoolean; val bLen = d.baseLen.toInt
          if (bAmb) amb += c
          if (!bAmb && bLen != 0) simple += c
          val ca = d.cutAmb.toBigInt; val cb = d.cutBad.toBigInt
          for (i <- cuts.indices) {
            if (!bAmb && bLen != 0 && ca.testBit(i)) extra(i) += c
            if (cb.testBit(i)) bad(i) += c
          }
        }
        println(f"[pdc] $f total=$total simpleResolved=$simple baseAmbiguous=$amb (${1000.0 * amb / total}%.2f/kinst)")
        for (i <- cuts.indices)
          println(f"[pdc] $f cut=${cuts(i).toSeq.sorted.mkString("+")}%-28s extraAmbiguous=${extra(i)}%9d " +
            f"perKinst=${1000.0 * extra(i) / total}%8.3f contractViolations=${bad(i)}")
        assert(bad.forall(_ == 0), "a cut changed a resolved length")
      }
    }
  }
}
