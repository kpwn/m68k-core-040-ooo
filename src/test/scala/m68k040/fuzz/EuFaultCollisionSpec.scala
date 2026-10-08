package m68k040.fuzz

import m68k040.VerilatorTest
import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._

/** BRANCH-EU vs CPLX-EU FAULT COLLISION (review 10 §3-1, 00 §7a-1).
  *
  * The branch EU's execute-time fault (TRAPV/TRAPcc vector 7, odd target vector 3) and the
  * CPLX/DivEu fault (CHK vector 6, DIV0 vector 5) used to share ONE ROB fault Flow, with the
  * DivEu drive textually later. When both fired in the same cycle the DivEu fault won by
  * last-assignment and the branch EU's fault was DROPPED: the TRAPV entry completed through
  * `branchCompletion`, retired as a no-op, and the YOUNGER CHK trapped instead -- a wrong
  * exception vector and a wrong stacked PC.
  *
  * Shape: an OLDER `trapv` (V=1) and a YOUNGER faulting `chk` (Dn<0) in flight together.
  * The TRAPV's V comes from a chain of `k` dependent ALU ops after a load, the CHK's Dn
  * from a chain of `m` ops after a second load, so sweeping (k, m) slides their completion
  * cycles past each other until they coincide. Each block loops 4x so it also runs warm.
  * Architecturally only TRAPV may ever trap (it is older and the CHK must be squashed),
  * so the vector-7 handler counts and skips the CHK; a vector-6 entry is a FAIL.
  *
  * Non-vacuity: a passive monitor counts cycles in which the ROB's branch fault port and
  * its CPLX fault port are BOTH valid -- the exact collision -- and the test requires at
  * least one. (Before the fix there was no separate port; the program's own verdict is
  * what failed: CHK's vector 6 was taken.) */
class EuFaultCollisionSpec extends AnyFunSuite {
  private val Sentinel = PortedTestRunner.SentinelAddr
  private val Pass     = PortedTestRunner.PassWord
  private val Ks = 1 to 6
  private val Ms = 0 to 4
  private val Iters = 4

  private def program(): String = {
    val sb = new StringBuilder
    def e(s: String): Unit = { sb.append(s); sb.append('\n') }
    e("    .text"); e("    .org 0")
    e("_start:")
    e("    lea 0x00011000,%a7")
    // The program is loaded away from 0 and VBR is 0: install the vectors in low memory
    // (the corpus convention, e.g. trapv_basic.s). Every vector 2..63 -> FAIL, then 6/7.
    e("    lea vec_fail,%a1")
    e("    lea 0x8,%a2")
    e("    moveq #61,%d1")
    e("Lvec:")
    e("    move.l %a1,(%a2)+")
    e("    dbf %d1,Lvec")
    e("    move.l #vec_chk,0x18")
    e("    move.l #vec_trapv,0x1C")
    e("    moveq #0,%d0")          // addend for the CHK operand chain (keeps d5 negative)
    e("    moveq #0,%d3")          // TRAPV count
    e("    moveq #10,%d6")         // CHK bound
    e("    lea 0x9100,%a0")
    e("    move.l #-1,4(%a0)")     // CHK operand: negative -> CHK would trap (vector 6)
    for (k <- Ks; m <- Ms) {
      e(f"    move.l #0x${0x80000000L - k}%x,(%%a0)")   // k x addq #1 -> the LAST overflows (V=1)
      e(s"    lea R_${k}_$m,%a5")
      e(s"    moveq #${Iters - 1},%d4")
      e(s"L_${k}_$m:")
      e("    move.l (%a0),%d2")
      e("    move.l 4(%a0),%d5")
      for (_ <- 0 until m) e("    add.l %d0,%d5")
      for (_ <- 0 until k) e("    addq.l #1,%d2")
      e("    trapv")
      e("    chk.w %d6,%d5")
      e("    bra vec_fail")        // neither trapped: TRAPV dropped AND chk not taken
      e(s"R_${k}_$m:")
      e(s"    dbf %d4,L_${k}_$m")
    }
    e(f"    cmp.l #${Ks.size * Ms.size * Iters},%%d3")
    e("    bne vec_count")
    e(f"    move.l #0x$Pass%x,0x$Sentinel%x")
    e("    bra .")
    e("vec_trapv:")
    e("    addq.l #1,%d3")
    e("    move.l %a5,2(%a7)")     // resume past the (squashed) CHK
    e("    rte")
    e("vec_chk:")
    e(f"    move.l #0xDEAD0006,0x$Sentinel%x")
    e("    bra .")
    e("vec_count:")
    e(f"    move.l #0xDEAD0C47,0x$Sentinel%x")
    e("    bra .")
    e("vec_fail:")
    e(f"    move.l #0xDEAD0BAD,0x$Sentinel%x")
    e("    bra .")
    sb.toString
  }

  // Copyback only: with the cache off every load is a serialized bus access and the
  // (k, m) sweep produced no same-cycle pair (measured: 0 collisions); copyback gives 11.
  for ((pname, posture) <- Seq("copyback" -> CachePosture.ForceCacheableCopyback)) {
    test(s"an older TRAPV is never dropped by a same-cycle younger CHK fault ($pname)", VerilatorTest) {
      var collisions = 0
      val dbg = sys.env.contains("EUFAULT_DBG")
      var brF = 0; var euF = 0; var cyc = 0L
      val excs = scala.collection.mutable.ArrayBuffer[String]()
      val res = PortedTestRunner.run(s"eufault_collision_$pname", program(), 400000L,
        cachePosture = posture, onDut = { dut =>
          val l = dut.rob.logic
          fork {
            while (true) {
              dut.clockDomain.waitSampling()
              if (l.brFaultCompletion.valid.toBoolean && l.euFaultCompletion.valid.toBoolean)
                collisions += 1
              if (dbg && l.brFaultCompletion.valid.toBoolean) brF += 1
              if (dbg && l.euFaultCompletion.valid.toBoolean) euF += 1
              if (dbg && l.exceptionPending.toBoolean && excs.size < 40)
                excs += f"${l.exceptionVector.toInt}@0x${l.exceptionPc.toLong}%x"
              cyc += 1
              if (dbg && cyc % 200000 == 0) println(f"[eufault-dbg] cyc=$cyc livePc=0x${l.debugLivePcReg.toLong}%x")
            }
          }
        })
      println(s"[eufault-collision] $pname outcome=$res collisions=$collisions")
      if (dbg) println(s"[eufault-dbg] brFaults=$brF euFaults=$euF excs=${excs.mkString(" ")}")
      res match {
        case PortedPass => ()
        case PortedFail(0xDEAD0006L) => fail("CHK (vector 6) was taken: the older TRAPV fault was DROPPED")
        case PortedFail(w) => fail(f"FAIL sentinel 0x$w%08x")
        case other => fail(s"$other")
      }
      assert(collisions > 0, "vacuous: no same-cycle branch/CPLX fault collision was produced")
    }
  }
}
