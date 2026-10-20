package m68k040.fuzz

import m68k040.VerilatorTest
import org.scalatest.funsuite.AnyFunSuite

/** Directed lock-step regression for `CPU_RTD_PRECISE_A7`.
  *
  * That crack is [load.l (A7) -> T0] + [T1 := A7 + 4 + disp16] + [ibranch -> T0 ; A7 := T1].
  * The ibranch carries `useImm` (its target displacement) AND a register srcB (T1), and the
  * issue queue used to treat any `useImm` non-LS uop's srcB as an immediate: the ibranch woke
  * on T0 alone and, when the return-address load finished before the T1 add (a
  * store-forwarded return address does), committed a STALE physical register to A7. The
  * caller then ran with a garbage A7 until its UNLK -- on silicon, a MOVEM restore through
  * that A7 loaded A2 with code bytes (Speedometer, bus error in the _EndUpdate patch).
  *
  * Every program is checked by full Musashi lock-step (`FuzzRunner.run`). The fuzz-sweep
  * seeds fail 13/13 on the pre-fix RTL; the hand-written shapes below did NOT reproduce
  * the race there (it needs the generator's issue-port pressure) and are kept as coverage. */
object RtdPreciseA7Programs {
  private val Prologue =
    """	moveq	#0,%d4
      |	moveq	#0,%d5
      |	move.l	#0x11111111,%d3
      |	move.l	#0x22222222,%a2
      |""".stripMargin

  private def wrap(body: String): String = body + "Ldone:\nLend:\n\tbra.s\tLend\n"
  private def fillers(k: Int): String = (0 until k).map(_ => "\taddq.l\t#1,%d4\n").mkString

  /** The fuzz shape (seed 10374): push an argument, call, `rtd #n`, read A7. */
  def pushCallRtd(k: Int, disp: Int): String = wrap(
    Prologue +
    "\tmove.l\t#0x66d12d30,%d7\n" +
    (0 until disp / 4).map(_ => "\tmove.l\t%d7,-(%sp)\n").mkString +
    fillers(k) +
    "\tbsr.w\tLsub\n" +
    "\tmove.l\t%sp,%d0\n" +
    "\tbra.w\tLdone\n" +
    s"Lsub:\n\trtd\t#$disp\n")

  /** The silicon shape: save A2 around a Pascal-style call whose callee pops its
    * argument with RTD, then restore A2 through A7. A wrong A7 loads A2 from elsewhere. */
  def movemAroundRtd(k: Int): String = wrap(
    Prologue +
    "\tmovem.l\t%d3/%a2,-(%sp)\n" +
    "\tmove.l\t#0x027ebb0a,-(%sp)\n" +
    fillers(k) +
    "\tbsr.w\tLsub\n" +
    "\tsuba.l\t%a2,%a2\n" +
    "\tmoveq\t#0,%d3\n" +
    "\tmovem.l\t(%sp)+,%d3/%a2\n" +
    "\tmove.l\t%a2,%d0\n" +
    "\tbra.w\tLdone\n" +
    "Lsub:\n\taddq.l\t#1,%d5\n\trtd\t#4\n")

  def matrix: Vector[(String, String)] = {
    val b = Vector.newBuilder[(String, String)]
    for (k <- 0 to 6; d <- Seq(0, 4, 8)) b += ((f"push_call_rtd_k$k%d_d$d%d", pushCallRtd(k, d)))
    for (k <- 0 to 6) b += ((f"movem_around_rtd_k$k%d", movemAroundRtd(k)))
    b.result()
  }
}

class RtdPreciseA7Spec extends AnyFunSuite {
  /** `FuzzLockStepSpec` seeds (40 blocks) that committed a stale A7 after `rtd #4` before
    * the fix -- ON-only divergences of a 1500-seed sweep (10000..11499), identical to the
    * flag-OFF sweep once fixed. Same generator and sim seed as the sweep. */
  private val sweepSeeds = Seq(10111, 10366, 10374, 10382, 10404, 10542, 10605, 10765, 10953,
                               10963, 11282, 11294, 11493)
  test("fuzz sweep seeds that hit the stale-T1 RTD commit stay in lock-step", VerilatorTest) {
    val failures = scala.collection.mutable.ArrayBuffer[String]()
    for (seed <- sweepSeeds) {
      val prog = ProgGen.generate(seed, 40)
      val simSeed = (seed * 0x9e3779b1 + 1) & 0x7fffffff
      FuzzRunner.run(prog.source, simSeed) match {
        case FuzzRunner.Pass => ()
        case FuzzRunner.GenFail(r) => failures += s"seed=$seed GENFAIL: $r"
        case FuzzRunner.Diverged(k, d, _) => failures += s"seed=$seed DIVERGED[$k]: $d"
      }
    }
    assert(failures.isEmpty, s"${failures.size}/${sweepSeeds.size} RTD sweep seeds diverged:\n  " +
      failures.mkString("\n  "))
  }

  test("RTD commits the T1 stack pointer, never a stale physical register", VerilatorTest) {
    val failures = scala.collection.mutable.ArrayBuffer[String]()
    for (seed <- 1 to 3; (name, src) <- RtdPreciseA7Programs.matrix) {
      FuzzRunner.run(src, simSeed = seed) match {
        case FuzzRunner.Pass => ()
        case FuzzRunner.GenFail(r) => failures += s"$name seed=$seed GENFAIL: $r"
        case FuzzRunner.Diverged(k, d, c) =>
          println(s"[rtdprecise] $name seed=$seed DIVERGED[$k]: $d\n$c")
          failures += s"$name seed=$seed DIVERGED[$k]: $d"
      }
    }
    assert(failures.isEmpty, s"${failures.size} RTD lock-step cases failed:\n  " + failures.mkString("\n  "))
  }
}
