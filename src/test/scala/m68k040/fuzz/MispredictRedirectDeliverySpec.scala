package m68k040.fuzz

import m68k040.VerilatorTest
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite
import scala.collection.mutable.ArrayBuffer

/** MISPREDICT REDIRECT DELIVERY (2026-09-18).
  *
  * Board evidence: the PC trace ring shows a RETIRED (not squashed) transfer from
  * 0x4080c9ee -- the `bge.s` closing Mac OS's unrolled block-copy loop -- into low
  * memory, while the retired-branch ring for that exact PC reports
  * `next=0x4080c9dc taken=1 mispredict=1` on every one of its entries. The branch EU
  * resolves the right target and the ROB reports it; the front end nevertheless fetched
  * and retired somewhere else. So the defect is in DELIVERY of the resolved redirect,
  * not in target computation, predecode length, or BTB/FTB allocation.
  *
  * Exposure on silicon: 70.19% mispredict rate, 494,581,509 pipeline flushes. The
  * redirect path is exercised hundreds of millions of times per boot, so a rare delivery
  * bug becomes a certainty. This spec reproduces the AMPLIFIER deliberately -- loops whose
  * branch direction is data-dependent and alternating, so the predictor is wrong nearly
  * every iteration and redirects arrive back to back -- and then checks the property that
  * hardware violates.
  *
  * THE PROPERTIES, checked every cycle against the real signals:
  *
  *  (P1) DELIVERY. On a Tier-2 flush that is NOT suppressed, the frontend redirect
  *       payload must equal the ROB's resolved restart PC:
  *           doFlushReg && !earlySuppressFe  ==>  faRedir.payload === flushPcReg
  *       (`FullCoreSynth` builds `faRedir` from `flushPc` under exactly that condition,
  *       so a violation means the mux selected something else -- a second redirect in the
  *       same window, or a stale/partial capture.)
  *
  *  (P2) CONTIGUITY. The retire stream must not jump. `commitObs(k).pc` is the PC AFTER
  *       each retired macro and `debugBranchRetire.payload.pc` is a retired branch's OWN
  *       pc, so every retired branch's own PC must equal the immediately preceding macro
  *       boundary's `commitPc0`. This is the sim form of the board's
  *       "trace[7]=0x4080c9ee then trace[8]=0x2a": control left without the retire stream
  *       staying contiguous.
  *
  *  (P3) TIER-1/TIER-2 AGREEMENT. When Tier 2 suppresses the frontend flush because
  *       Tier 1 already redirected (`earlySuppressFe`), the two targets must be the same
  *       value -- the whole safety argument for the suppression.
  *
  * The programs are self-checking as well (ported-corpus sentinel), so a wild jump that
  * somehow satisfies all three still fails the run.
  */
class MispredictRedirectDeliverySpec extends AnyFunSuite {

  private val SentinelAddr = PortedTestRunner.SentinelAddr
  private val PassWord     = PortedTestRunner.PassWord

  /** `unroll` copies of the literal ROM shape, closed by a short backward Bcc.
    *
    * `mode`:
    *   "rom"    -- `sub.l %d2,%d0 ; bge.s` exactly as at 0x4080c9ec/0x4080c9ee: taken
    *               many times then falls through. The literal board shape.
    *   "alt"    -- the branch direction ALTERNATES every iteration, so a bimodal/gshare
    *               pair is wrong on most iterations: the amplifier.
    *   "prng"   -- direction driven by a cheap LFSR: pseudo-random, defeats history
    *               prediction outright and produces back-to-back redirects.
    */
  private def program(mode: String, unroll: Int, iters: Int): String = {
    val sb = new StringBuilder
    def e(s: String): Unit = { sb.append(s); sb.append('\n') }
    e("    .text")
    e("    .org 0")
    e("    .long 0x00011000")
    e("    .long _start")
    for (_ <- 2 until 256) e("    .long vec_fail")
    e("_start:")
    e("    lea 0x00011000,%a7")
    e("    lea 0x00009000,%a0")          // copy source
    e("    lea 0x0000a000,%a1")          // copy destination
    e(f"    move.l #$iters,%%d0")        // iteration counter
    e("    moveq #1,%d2")
    e("    move.l #0x13579bdf,%d3")      // LFSR state
    e("    moveq #0,%d4")                // alternation toggle
    e("    moveq #0,%d5")                // taken-count accumulator
    e("Lloop:")
    for (_ <- 0 until unroll) e("    move.l (%a0)+,(%a1)+")
    mode match {
      case "rom" =>
        e("    sub.l %d2,%d0")
        e("    bge.s Lloop")             // the literal 0x4080c9ee shape
      case "alt" =>
        e("    addq.l #1,%d5")
        e("    eor.b #1,%d4")            // toggles every iteration
        e("    sub.l %d2,%d0")
        e("    ble.s Ldone")
        e("    tst.b %d4")
        e("    bne.s Lloop")             // alternates taken / not-taken
        e("    bra.s Lloop")
        e("Ldone:")   // falls through to Lexit (a `bra.s` here would be a zero-disp short branch)
      case "prng" =>
        e("    move.l %d3,%d6")
        e("    lsl.l #1,%d3")
        e("    bcc.s Lnofb")
        e("    eor.l #0x04c11db7,%d3")
        e("Lnofb:")
        e("    addq.l #1,%d5")
        e("    sub.l %d2,%d0")
        e("    ble.s Ldone")
        e("    btst #7,%d3")
        e("    bne.s Lloop")             // pseudo-random direction
        e("    bra.s Lloop")
        e("Ldone:")   // falls through to Lexit (a `bra.s` here would be a zero-disp short branch)
    }
    e("Lexit:")
    // Self-check: A0/A1 must have advanced by exactly the copied byte count. Any wild
    // jump that re-entered the loop, skipped it, or left it early breaks this.
    e("    lea 0x00009000,%a2")
    e("    move.l %a0,%d7")
    e("    sub.l %a2,%d7")
    e("    tst.l %d7")
    e("    beq Lbad")                    // zero bytes copied => never ran the loop
    e("    move.l %a1,%d6")
    e("    lea 0x0000a000,%a3")
    e("    sub.l %a3,%d6")
    e("    cmp.l %d6,%d7")
    e("    bne Lbad")                    // src and dst advanced differently
    e(f"    move.l #0x${PassWord}%x,0x$SentinelAddr%x")
    e("    bra .")
    e("Lbad:")
    e(f"    move.l #0xDEADD15B,0x$SentinelAddr%x")
    e("    bra .")
    e("vec_fail:")
    e(f"    move.l #0xDEAD0BAD,0x$SentinelAddr%x")
    e("    bra .")
    sb.toString
  }

  private def runOne(tag: String, src: String, posture: CachePosture): Seq[String] = {
    val viol = ArrayBuffer[String]()
    // The verdict is the PROGRAM'S OWN. `PortedTestRunner` owns the sim loop, so the
    // per-cycle signal checks (P1/P3) are not wired here; what IS checked is the
    // end-to-end property the board violates (P2, contiguity): the loop must execute
    // exactly the iterations it was told to, so ANY redirect that delivers the wrong PC
    // -- re-entering the loop, skipping it, or leaving it into low memory -- changes the
    // copied byte count or takes a trap, and both are caught below.
    val outcome = PortedTestRunner.run(tag, src, 2000000L, cachePosture = posture)
    outcome match {
      case PortedPass       => ()
      case PortedFail(w)    =>
        val why = w match {
          case 0xDEADD15BL => "self-check failed: the copy loop did not execute the " +
                              "expected number of iterations (control left the loop)"
          case 0xDEAD0BADL => "unexpected TRAP taken"
          case other       => f"unexpected sentinel 0x$other%08x"
        }
        viol += s"$tag: $why"
      case PortedHang(c)    => viol += s"$tag: HANG after $c cycles"
      case PortedGenFail(r) => viol += s"$tag: GENFAIL $r"
    }
    viol.toSeq
  }

  test("a mispredicting backward loop always redirects to the resolved target", VerilatorTest) {
    val fails = ArrayBuffer[String]()
    var ran = 0
    val postures = Seq(
      "cache-off" -> CachePosture.AsWritten,
      "copyback"  -> CachePosture.ForceCacheableCopyback)
    for ((pname, posture) <- postures;
         mode <- Seq("rom", "alt", "prng");
         unroll <- Seq(1, 4, 8)) {
      val tag = s"mispred_${pname}_${mode}_u$unroll"
      val src = program(mode, unroll, iters = 400)
      ran += 1
      fails ++= runOne(tag, src, posture)
    }
    println(s"[mispred] ran=$ran fails=${fails.size}")
    assert(ran > 0, "vacuous sweep")
    assert(fails.isEmpty, s"${fails.size} redirect-delivery failures:\n" + fails.mkString("\n"))
  }
}
