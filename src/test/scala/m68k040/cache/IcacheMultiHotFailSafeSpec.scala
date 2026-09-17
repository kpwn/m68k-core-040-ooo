package m68k040.cache

import m68k040.{M68kParams, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.mmu.IdentityTranslationPlugin
import m68k040.services.TranslationService
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** I-cache MULTI-HOT fail-safe and its duplicate purge.
  *
  * `IcachePlugin`'s own comment on the `s1HitVec` one-hot assert states the failure
  * mode exactly: the vector feeds a one-hot AND-OR way mux, "so a 2-hot vector does
  * not fail loudly -- it silently ORs two ways' instruction bytes together into ONE
  * FetchRsp. FetchRsp carries no tag and FetchAlignPlugin attributes by ring head, so
  * the corruption is invisible until it executes as the wrong opcode."
  *
  * That assert makes the condition loud in SIMULATION. It does nothing in hardware.
  * The fail-safe makes a multi-hot match a MISS -- routed to the ordinary refill path
  * -- while leaving `s1WayOh`/the AND-OR mux untouched, so the OR-ed line is still
  * computed and simply never delivered.
  *
  * Forcing a miss alone would LIVELOCK on a persistent duplicate: the refill's
  * round-robin victim need not be one of the duplicates, so the set can grow to three
  * matching ways and every fetch of that line then misses for ever -- the frontend
  * would stop delivering that instruction, i.e. the fail-safe would convert silent
  * corruption into a hang. I-cache lines are READ-ONLY, so the purge can clear every
  * matching way with no possibility of losing state, and that converges in one pass.
  */
class IcacheMultiHotFailSafeSpec extends AnyFunSuite {

  class Dut extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val param  = new ParamPlugin(M68kParams())
    val xlate: FiberPlugin with TranslationService = new IdentityTranslationPlugin
    val icache = new IcachePlugin
    val probe  = new FetchProbePlugin
    db.on { host.asHostOf(Seq[FiberPlugin](param, xlate, icache, probe)) }
  }

  // geometry: 64 sets, 4 ways, 64-byte lines. addr[5:0] off, addr[11:6] set,
  // addr[31:12] tag.
  private val Ways = 4
  private def setOf(a: Long): Int  = ((a >> 6) & 0x3f).toInt
  private def tagOf(a: Long): Long = a >> 12

  private val AddrA = 0x00001000L   // set 0, tag 1
  private val AddrB = 0x00002000L   // set 0, tag 2

  private def fetch(dut: Dut, cd: ClockDomain, pc: Long): BigInt = {
    dut.probe.logic.cmdIn.valid #= true
    dut.probe.logic.cmdIn.payload.pc #= pc
    cd.waitSamplingWhere(dut.probe.logic.cmdIn.ready.toBoolean &&
                         dut.probe.logic.cmdIn.valid.toBoolean)
    dut.probe.logic.cmdIn.valid #= false
    cd.waitSamplingWhere(dut.probe.logic.rspOut.valid.toBoolean)
    dut.probe.logic.rspOut.payload.data.toBigInt
  }

  private def waysMatching(dut: Dut, addr: Long): Seq[Int] = {
    val set = setOf(addr); val tg = tagOf(addr)
    (0 until Ways).filter(w =>
      IcacheArrayProbe.wayValid(dut.icache, w, set) &&
      IcacheArrayProbe.wayTag(dut.icache, w, set) == BigInt(tg))
  }

  /** Plant a persistent duplicate of AddrA by retagging whichever way took AddrB. */
  private def plantDuplicate(dut: Dut, cd: ClockDomain): Int = {
    val set = setOf(AddrA)
    val wA = waysMatching(dut, AddrA)
    val wB = waysMatching(dut, AddrB)
    assert(wA.size == 1, s"setup: A occupies ${wA.size} ways, expected 1")
    assert(wB.size == 1, s"setup: B occupies ${wB.size} ways, expected 1")
    assert(wA.head != wB.head, "setup: A and B landed in the same way")
    // Acknowledge the deliberate invariant violation, so the plugin's own one-hot
    // tripwire (kept at full severity) does not kill the simulation before the
    // behaviour under test can be observed. Nothing else in the repo sets this.
    dut.icache.logic.dbgAllowMultiHot #= true
    dut.icache.logic.tagMem(wB.head).setBigInt(set, BigInt(tagOf(AddrA)))
    cd.waitSampling()
    val m = waysMatching(dut, AddrA)
    assert(m.size == 2, s"setup failed: ${m.size} ways match A, expected 2")
    wB.head
  }

  private def initDut(dut: Dut): ClockDomain = {
    val cd = dut.clockDomain
    cd.forkStimulus(10)
    dut.probe.logic.cmdIn.valid #= false
    dut.icache.logic.invalidateAll #= false
    cd.waitSampling(4)
    dut.icache.logic.invalidateAll #= true
    cd.waitSampling()
    dut.icache.logic.invalidateAll #= false
    cd.waitSampling(4)
    cd
  }

  private lazy val compiled = SimConfig.withVerilator.compile(new Dut)

  test("a multi-hot fetch reports MISS and refills, instead of OR-ing two ways",
       VerilatorTest) {
    compiled.doSim("ic_multihot_failsafe", 1) { dut =>
      val cd = initDut(dut)
      IcacheSim.attachMemory(dut.icache.logic.axi, cd, 0L, 0x8000)

      // non-vacuity: both lines resident and correct first
      assert(fetch(dut, cd, AddrA) == IcacheSim.window64(AddrA), "cold fetch of A wrong")
      assert(fetch(dut, cd, AddrB) == IcacheSim.window64(AddrB), "cold fetch of B wrong")
      assert(fetch(dut, cd, AddrA) == IcacheSim.window64(AddrA), "warm fetch of A wrong")

      val retagged = plantDuplicate(dut, cd)
      println(s"[ic-multihot] planted: ways ${waysMatching(dut, AddrA).mkString(",")} " +
              s"now match A (way $retagged retagged)")

      var sawMultiHot = false
      var sawPurge    = false
      val mon = fork {
        while (true) {
          cd.waitSampling()
          if (dut.icache.logic.s1MultiHot.toBoolean) sawMultiHot = true
          if (dut.icache.logic.icDupPurge.toBoolean) sawPurge = true
        }
      }

      val got = fetch(dut, cd, AddrA)
      cd.waitSampling(20)
      mon.terminate()

      println(f"[ic-multihot] fetch after duplicate: got 0x$got%016x expected " +
              f"0x${IcacheSim.window64(AddrA)}%016x  multiHot=$sawMultiHot purge=$sawPurge")

      assert(sawMultiHot,
        "the fetch never saw a multi-hot vector -- the planted duplicate did not reach " +
        "the S1 verdict, so this test proves nothing")
      assert(sawPurge, "the duplicate purge never fired on a multi-hot fetch")
      assert(got == IcacheSim.window64(AddrA),
        f"MULTI-HOT DELIVERED CORRUPT INSTRUCTION BYTES: got 0x$got%016x, expected " +
        f"0x${IcacheSim.window64(AddrA)}%016x. FetchRsp carries no tag, so this is " +
        f"invisible until it executes as the wrong opcode.")
    }
  }

  test("a forced persistent duplicate CONVERGES -- the purge terminates", VerilatorTest) {
    compiled.doSim("ic_multihot_terminates", 1) { dut =>
      val cd = initDut(dut)
      IcacheSim.attachMemory(dut.icache.logic.axi, cd, 0L, 0x8000)
      fetch(dut, cd, AddrA); fetch(dut, cd, AddrB)
      plantDuplicate(dut, cd)

      val MaxIterations = 8
      var iterations = 0
      var matching = waysMatching(dut, AddrA).size
      while (matching > 1 && iterations < MaxIterations) {
        assert(fetch(dut, cd, AddrA) == IcacheSim.window64(AddrA),
          "a fetch during convergence returned the wrong window")
        cd.waitSampling(20)
        matching = waysMatching(dut, AddrA).size
        iterations += 1
      }
      println(s"[ic-multihot] converged to $matching matching way(s) after " +
              s"$iterations fetch(es)")

      assert(matching == 1,
        s"LIVELOCK: after $MaxIterations fetches the set still has $matching ways " +
        s"matching A. This is the failure mode of forcing a miss WITHOUT the purge: the " +
        s"frontend would stop delivering that instruction entirely.")
      assert(iterations <= 2,
        s"convergence took $iterations fetches; the purge empties the matches in one.")
      assert(fetch(dut, cd, AddrA) == IcacheSim.window64(AddrA),
        "after convergence the line still does not fetch correctly")
    }
  }
}
