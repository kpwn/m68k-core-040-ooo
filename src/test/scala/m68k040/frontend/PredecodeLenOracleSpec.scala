package m68k040.frontend

import m68k040.VerilatorTest
import m68k040.cache.ChunkPredecode
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** INVESTIGATION HARNESS (2026-09-18, ifetch-desync campaign).
  *
  * Dumps `PredecodeWord.classify().{simple,lenWords}` for a caller-supplied list of
  * (opword + 6 lookahead words) cases, with every validity flag TRUE, so the result can
  * be diffed against a genuinely INDEPENDENT length oracle (Musashi's own 68040
  * disassembler) rather than against this repo's own `PredecodeRef` mirror.
  *
  * Driven by files, so the comparison lives outside the JVM:
  *   in  : $PREDECODE_ORACLE_IN   -- one case per line, 7+ hex words (op ext1..ext6 ..)
  *   out : $PREDECODE_ORACLE_OUT  -- one line per case: "<simple 0|1> <lenWords>"
  * Skipped unless PREDECODE_ORACLE_IN is set.
  */
class PredecodeLenOracleSpec extends AnyFunSuite {
  class Dut extends Component {
    val op = in Bits (16 bits)
    val e  = in Vec(Bits(16 bits), 6)
    val res = out(ChunkPredecode())
    res := PredecodeWord.classify(op, e(0), e(1), e(2), e(3), e(4), e(5),
      extWValid = True, extW2Valid = True, extW3Valid = True,
      extW4Valid = True, extW5Valid = True, extW6Valid = True)
  }

  test("dump predecode framing for an external oracle diff", VerilatorTest) {
    val inPath = sys.env.getOrElse("PREDECODE_ORACLE_IN", "")
    assume(inPath.nonEmpty, "PREDECODE_ORACLE_IN not set")
    val outPath = sys.env.getOrElse("PREDECODE_ORACLE_OUT", inPath + ".rtl")
    val cases = scala.io.Source.fromFile(inPath).getLines()
      .map(_.trim).filter(_.nonEmpty)
      .map(l => l.split("\\s+").take(7).map(Integer.parseInt(_, 16)))
      .toArray
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val sb = new StringBuilder
      var i = 0
      while (i < cases.length) {
        val c = cases(i)
        dut.op #= c(0)
        var j = 0
        while (j < 6) { dut.e(j) #= (if (c.length > j + 1) c(j + 1) else 0); j += 1 }
        sleep(1)
        sb.append(if (dut.res.simple.toBoolean) '1' else '0')
        sb.append(' ')
        sb.append(dut.res.lenWords.toInt)
        sb.append('\n')
        i += 1
      }
      val w = new java.io.PrintWriter(outPath)
      w.write(sb.toString); w.close()
      println(s"[oracle] wrote ${cases.length} rows to $outPath")
    }
  }
}
