package m68k040.fuzz

import m68k040.VerilatorTest
import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._

/** A cache-inhibited STORE must reach the device EXACTLY once per architectural execution,
  * under an interrupt storm -- the store twin of ExecuteLockStepSpec's "inhibited-load IRQ
  * storm: device read fires exactly once per retired load".
  *
  * THE WINDOW. A precise (inhibited) store drains at the ROB head; `preciseDrainBusy` blocks
  * interrupt recognition while the drain is in flight and for ONE cycle after its final ack.
  * The store is then squashable until it RETIRES, and its completion reaches the ROB through
  * the LS EU's shared completion stage, where `applyFast` yields to a same-cycle FRONT
  * completion and `applyBacklog` yields to every BACK (ring) completion. If the completion
  * is deferred past the pad, an interrupt recognised at the store's boundary squashes an
  * instruction whose bus write has already happened; after RTE it executes again -- a
  * second device write. (For LOADS the same window was closed by holding `loadBusyReg`
  * until RETIRE; see LsEuPlugin's 2026-09-07 rework note.)
  *
  * The loop keeps the shared completion stage busy right behind every device store with
  * LS-cluster ops that complete without the cache (LEA) and fast cacheable stores, and a
  * phase-swept level-1 storm slides the recognition edge across the loop. Oracle: bus AW
  * count at the device register == iterations; and the storm must actually interleave. */
class InhibitedStoreIrqReplaySpec extends AnyFunSuite {
  private val Iters = 300
  private val Dev   = 0xFFFF0104L

  test("inhibited store under an IRQ storm is written exactly once per execution", VerilatorTest) {
    val src =
      f"""    .text
         |_start:
         |    move.l  #_irqh, 0x64
         |    lea     0xFFFF0100, %%a0
         |    lea     0x00090000, %%a3
         |    move.l  #0, 0x00090100
         |    move.w  #0x2000, %%sr
         |    move.l  #${Iters - 1}%d, %%d7
         |_l:
         |    move.l  %%d7, 4(%%a0)
         |    lea     4(%%a3), %%a4
         |    lea     8(%%a3), %%a5
         |    move.l  %%d7, (%%a3)
         |    move.l  %%d7, 4(%%a3)
         |    lea     12(%%a3), %%a4
         |    move.l  %%d7, 8(%%a3)
         |    lea     16(%%a3), %%a5
         |    dbf     %%d7, _l
         |    lea     0xFFFF0000, %%a6
         |    move.l  #0xC0FFEE00, %%d0
         |    move.l  %%d0, (%%a6)
         |_halt:
         |    bra     _halt
         |_irqh:
         |    addq.l  #1, 0x00090100
         |    rte
         |""".stripMargin
    var devWrites = 0; var excEntries = 0; var lastExc = false
    val outcome = PortedTestRunner.run("inhib_store_irq_replay", src, 600000L,
      cachePosture = CachePosture.ForceCacheableCopyback,
      onDut = d => {
        val cd = d.clockDomain
        d.intCtrl.logic.iackAvec #= true
        cd.onSamplings {
          val ax = d.dcache.logic.axi
          if (ax.aw.valid.toBoolean && ax.aw.ready.toBoolean &&
              (ax.aw.payload.addr.toLong & 0xffffffffL) == Dev) devWrites += 1
          val e = !d.rob.logic.excIdle.toBoolean
          if (e && !lastExc) excEntries += 1
          lastExc = e
        }
        fork {
          var gap = 3
          while (true) {
            cd.waitSampling(gap)
            d.intCtrl.logic.iplIn #= 1
            cd.waitSampling(4)
            d.intCtrl.logic.iplIn #= 0
            gap = if (gap >= 41) 3 else gap + 1
          }
        }
      })
    println(s"[inh-st-irq] FUZZ_LS_OOO=${sys.env.getOrElse("FUZZ_LS_OOO", "0")} " +
      s"FUZZ_SHIPPING_LSU=${sys.env.getOrElse("FUZZ_SHIPPING_LSU", "0")} outcome=$outcome " +
      s"devWrites=$devWrites (expect $Iters) excEntries=$excEntries")
    assert(outcome == PortedPass, s"outcome $outcome")
    assert(excEntries > 50, s"VACUOUS: only $excEntries exception entries")
    assert(devWrites == Iters, s"DEVICE WRITE COUNT: $devWrites bus writes for $Iters stores")
  }
}
