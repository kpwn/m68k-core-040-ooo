package m68k040.fuzz

import m68k040.SlowTest
import m68k040.oracle.{Musashi, ProgramAssembler}
import org.scalatest.funsuite.AnyFunSuite

/** Generator-only sanity (no Verilator): every seed must (1) assemble, (2)
  * terminate in the oracle (reach the terminal spin within the cycle budget),
  * and (3) be deterministic (same seed -> same source). Catches GAS-syntax
  * and termination/sandbox-constraint bugs in the templates cheaply. */
class FuzzGenSpec extends AnyFunSuite {

  private val nSeeds  = sys.env.get("FUZZ_GEN_SEEDS").map(_.toInt).getOrElse(40)
  private val nBlocks = sys.env.get("FUZZ_BLOCKS").map(_.toInt).getOrElse(20)

  test(s"generator: $nSeeds seeds assemble + oracle-terminate + deterministic", SlowTest) {
    val bad = scala.collection.mutable.ArrayBuffer[(Int, String)]()
    for (seed <- 0 until nSeeds) {
      val src  = ProgGen.generate(seed, nBlocks).source
      val src2 = ProgGen.generate(seed, nBlocks).source
      assert(src == src2, s"seed=$seed NOT deterministic")
      ProgramAssembler.assemble(src, ProgramAssembler.DefaultLoadAddress) match {
        case Left(err) =>
          bad += ((seed, s"assemble: ${err.reason}\n$src"))
        case Right(image) =>
          val endPc = ProgramAssembler.DefaultLoadAddress + image.bytes.length - 2
          Musashi.assembleAndTrace(src, stopPc = Some(endPc)) match {
            case Left(err) => bad += ((seed, s"oracle: ${err.reason}"))
            case Right(steps) =>
              if (steps.isEmpty || (steps.last.pc & 0xffffffffL) != (endPc & 0xffffffffL))
                bad += ((seed, f"oracle runaway (last pc 0x${if (steps.isEmpty) 0L else steps.last.pc}%08x " +
                               f"!= endPc 0x$endPc%08x, ${steps.size} steps)\n$src"))
              else if (steps.size < nBlocks)
                bad += ((seed, s"suspiciously few steps (${steps.size})"))
              else if (sys.env.contains("FUZZ_GEN_VERBOSE")) {
                println(s"[fuzzgen] seed=$seed imageBytes=${image.bytes.length} oracleSteps=${steps.size}")
                if (seed == 0) println(src)
              }
          }
      }
    }
    if (bad.nonEmpty) {
      bad.take(3).foreach { case (s, m) => println(s"[fuzzgen] seed=$s FAIL: $m") }
      fail(s"${bad.size}/$nSeeds seeds failed generator sanity: seeds ${bad.map(_._1).mkString(",")}")
    }
  }
}
