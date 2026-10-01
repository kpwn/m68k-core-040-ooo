package m68k040.fuzz

import m68k040.VerilatorTest
import m68k040.oracle.ProgramAssembler
import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._
import scala.collection.mutable

/** D-side CPUSH must INVALIDATE, not only push (silicon defect, 2026-09-30).
  *
  * MC68040UM §4.5 / §9 (CPUSH): "Cache lines [selected by the scope] are pushed if they
  * are valid and modified, and then INVALIDATED." For the data cache that is push-then-
  * invalidate; for the instruction cache (never dirty) it is invalidate. The only
  * DC-maintenance op that leaves a line resident is none -- CINV drops without a push.
  *
  * The core's ExceptionUnit issued CPUSH to DcachePlugin's maintenance walk with
  * `invalidate := False`, so after `cpusha dc` the pushed line stayed VALID (clean) in the
  * L1. Found on the board by the membench agent: a host write to DDR made after
  * `cpusha dc` was invisible to the next cacheable load (it returned the stale 0xDEAD0000);
  * adding `cinva dc` fixed it. Every DMA-in path that relies on CPUSH to drop stale lines
  * reads stale data.
  *
  * WHAT THIS TEST DOES. The testbench plays the DMA master: it writes the D-side backing
  * memory BEHIND the cache, and only AFTER the push has landed, so a correct core must
  * return the testbench's value on the next load.
  *
  *   dirty phases (A = cpusha dc, L = cpushl dc, P = cpushp dc, B = cpushl bc):
  *     load X (fill), store V1 to X (hit, now dirty), CPUSH <scope>
  *     TB: on the B response of the maintenance writeback whose AW named X's line, CHECK
  *         memory holds V1 (the push really happened and before the drop -- push-before-
  *         invalidate ordering), then write V2 into backing memory at X.
  *     load X  -> must be V2.  Pre-fix: V1 (stale L1 hit).
  *   clean phase (C = cpushl dc on a CLEAN line):
  *     load X (fill; TB overwrites memory with V2 once the refill's last R beat fires),
  *     CPUSHL, load X -> must be V2.  Pre-fix: the original fill value.
  *
  * The post-CPUSH load cannot race the TB write: the CPUSH retires through S_MAINTWAIT,
  * which waits for the walk (and so for the writeback's B), then redirects -- the load is
  * refetched after that. Results are stored to INHIBITED space so they are AXI-visible. */
class DcacheCpushInvalidateSpec extends AnyFunSuite {
  private val Sentinel = PortedTestRunner.SentinelAddr   // 0xFFFF0000, inhibited
  private val Results  = 0xffff0020L                     // inhibited, AXI-visible

  private final case class Phase(tag: String, x: Long, v1: Long, v2: Long, maint: String, dirty: Boolean)
  private val Phases = Seq(
    Phase("A", 0x00091000L, 0x11110001L, 0x22220001L, "cpusha  %dc",        dirty = true),
    Phase("L", 0x00092010L, 0x11110002L, 0x22220002L, "cpushl  %dc,(%a0)",  dirty = true),
    Phase("P", 0x00093020L, 0x11110003L, 0x22220003L, "cpushp  %dc,(%a0)",  dirty = true),
    Phase("B", 0x00094030L, 0x11110004L, 0x22220004L, "cpushl  %bc,(%a0)",  dirty = true),
    Phase("C", 0x00095040L, 0xFFFFFFFFL, 0x22220005L, "cpushl  %dc,(%a0)",  dirty = false))

  private val Src = {
    val sb = new StringBuilder
    Phases.zipWithIndex.foreach { case (p, i) =>
      sb ++= f"    lea 0x${p.x}%08x,%%a0\n"
      sb ++= "    move.l (%a0),%d1\n"                               // fill the line
      if (p.dirty) sb ++= f"    move.l #0x${p.v1}%08x,(%%a0)\n"    // hit -> dirty
      sb ++= s"    ${p.maint}\n"
      sb ++= "    move.l (%a0),%d0\n"                               // MUST miss and refill
      sb ++= f"    move.l %%d0,0x${Results + 4 * i}%08x\n"
    }
    sb ++= f"    lea 0x$Sentinel%08x,%%a2\n    move.l #0xC0FFEE00,%%d6\n    move.l %%d6,(%%a2)\n"
    sb ++= ".Lhalt:\n    bra.s .Lhalt\n"
    sb.toString
  }

  test("CPUSH on the data cache pushes AND invalidates (all scopes, DC and BC)", VerilatorTest) {
    val loadAddr = PortedTestRunner.loadAddr
    val image = ProgramAssembler.assemble(Src, loadAddr) match {
      case Right(i) => i
      case Left(e)  => fail(s"assemble: ${e.reason}")
    }
    PortedTestRunner.compiled.doSim("dcache_cpush_invalidate", 1) { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)

      FuzzDut.attachProgramWithBusErrors(dut.icache.logic.axi, cd, loadAddr, image.bytes)
      val dsideMem = new m68k040.sim.ConstFillSparseMemory(0xff.toByte)
      val dmem = new m68k040.ls.BehavioralMemAgent(dut.dcache.logic.axi, cd,
                                                   sharedMem = dsideMem, injectBusErrors = true)
      m68k040.sim.HotDoorAttach(dut.dcache, cd, dmem)
      for (i <- image.bytes.indices) dmem.mem.write(loadAddr + i, image.bytes(i).toByte)
      for (i <- 0 until 4) dmem.mem.write(Sentinel + i, 0.toByte)
      for (i <- 0 until 4 * Phases.size) dmem.mem.write(Results + i, 0.toByte)

      def rd32(a: Long): Long = (0 until 4).foldLeft(0L)((v, k) => (v << 8) | (dmem.mem.read(a + k) & 0xffL))
      def wr32(a: Long, v: Long): Unit = for (k <- 0 until 4) dmem.mem.write(a + k, ((v >>> (24 - 8 * k)) & 0xff).toByte)

      dut.ctrl.logic.mmuEnable #= false
      dut.ctrl.logic.urp #= 0; dut.ctrl.logic.srp #= 0
      dut.intCtrl.logic.iplIn #= 0
      dut.intCtrl.logic.iackAvec #= false; dut.intCtrl.logic.iackVector #= 0
      dut.fa.logic.redirect.valid #= false; dut.fa.logic.resume.valid #= false
      dut.rob.logic.flush.valid #= false
      dut.icache.logic.invalidateAll #= false
      dut.wire.logic.seedValid #= false; dut.wire.logic.seedAddr #= 0; dut.wire.logic.seedData #= 0
      cd.waitSampling(2)
      dut.icache.logic.invalidateAll #= true
      cd.waitSampling(); dut.icache.logic.invalidateAll #= false
      cd.waitSampling(80)
      dut.rob.logic.exc.ss.isp #= 0x00100000L
      dut.rob.logic.exc.ss.usp #= 0L
      dut.wire.logic.seedValid #= true
      dut.wire.logic.seedAddr #= 15
      dut.wire.logic.seedData #= BigInt(0x00100000L)
      cd.waitSampling(2)
      dut.wire.logic.seedValid #= false
      cd.waitSampling()

      // Low half COPYBACK (the lines under test), high half INHIBITED (results, sentinel).
      dut.ctrl.logic.itt0 #= CachePosture.LowHalfCopybackTtr
      dut.ctrl.logic.itt1 #= 0
      dut.ctrl.logic.dtt0 #= CachePosture.LowHalfCopybackTtr
      dut.ctrl.logic.dtt1 #= CachePosture.HighHalfInhibitedTtr
      dut.rob.logic.exc.ss.cacr #= CachePosture.CacrDataAndInstructionEnable
      dut.ctrl.logic.mmuEnable #= true
      cd.waitSampling()
      assert(dut.ctrl.logic.mmuEnable.toBoolean, "TC.E did not stick")

      dut.fa.logic.redirect.valid #= true
      dut.fa.logic.redirect.payload #= loadAddr
      cd.waitSampling()
      dut.fa.logic.redirect.valid #= false

      val ax = dut.dcache.logic.axi
      def line(a: Long): Long = a & ~0xfL
      val awById   = mutable.Map.empty[Int, Long]     // outstanding AW: id -> line
      val arById   = mutable.Map.empty[(Int, Int), Long] // outstanding AR: (port, id) -> line
      val pushed   = mutable.Map.empty[String, Long]  // phase -> memory at X when its push's B fired
      val dmaDone  = mutable.Set.empty[String]
      val pending  = mutable.ArrayBuffer.empty[(Int, Phase)]  // (cycle due, phase) DMA writes
      var sentinelSeen = false
      var cycles = 0

      while (!sentinelSeen && cycles < 200000) {
        cd.waitSampling(); cycles += 1

        // Apply DMA writes scheduled one cycle after their trigger (after the agent's own
        // memory update for that transaction is certainly done).
        pending.filter(_._1 <= cycles).foreach { case (_, p) => wr32(p.x, p.v2); dmaDone += p.tag }
        pending --= pending.filter(_._1 <= cycles)

        if (ax.aw.valid.toBoolean && ax.aw.ready.toBoolean)
          awById(ax.aw.payload.id.toInt) = line(ax.aw.payload.addr.toLong)
        if (ax.ar.valid.toBoolean && ax.ar.ready.toBoolean)
          arById((0, ax.ar.payload.id.toInt)) = line(ax.ar.payload.addr.toLong)
        if (ax.b.valid.toBoolean && ax.b.ready.toBoolean) {
          val id = ax.b.payload.id.toInt
          awById.remove(id).foreach { ln =>
            Phases.find(p => p.dirty && line(p.x) == ln && !pushed.contains(p.tag)).foreach { p =>
              pushed(p.tag) = rd32(p.x)
              pending += ((cycles + 1, p))
            }
          }
        }
        if (ax.r.valid.toBoolean && ax.r.ready.toBoolean && ax.r.payload.last.toBoolean) {
          val id = ax.r.payload.id.toInt
          arById.remove((0, id)).foreach { ln =>
            Phases.find(p => !p.dirty && line(p.x) == ln && !dmaDone.contains(p.tag) &&
                              !pending.exists(_._2.tag == p.tag))
              .foreach(p => pending += ((cycles + 1, p)))
          }
        }

        // Hot and cold ports have independent ID spaces. Observe the hot refill
        // too, so the clean-line DMA trigger remains exercised with CPU_AXI_DH.
        if (dut.dcache.hotDoor) {
          val hot = dut.dcache.logic.axiDh
          if (hot.ar.valid.toBoolean && hot.ar.ready.toBoolean)
            arById((1, hot.ar.payload.id.toInt)) = line(hot.ar.payload.addr.toLong)
          if (hot.r.valid.toBoolean && hot.r.ready.toBoolean && hot.r.payload.last.toBoolean) {
            arById.remove((1, hot.r.payload.id.toInt)).foreach { ln =>
              Phases.find(p => !p.dirty && line(p.x) == ln && !dmaDone.contains(p.tag) &&
                                !pending.exists(_._2.tag == p.tag))
                .foreach(p => pending += ((cycles + 1, p)))
            }
          }
        }

        val w = rd32(Sentinel)
        if (w != 0) {
          assert(w == 0xC0FFEE00L, f"program wrote a FAIL sentinel 0x$w%08x at cycle $cycles")
          sentinelSeen = true
        }
      }
      assert(sentinelSeen, s"program did not reach its sentinel in $cycles cycles (hang?)")

      val lines = Phases.zipWithIndex.map { case (p, i) =>
        val got = rd32(Results + 4 * i)
        val push = pushed.get(p.tag).map(v => f"0x$v%08x").getOrElse("-")
        (p, got, f"[cpush-inval] ${p.tag} ${p.maint}%-20s pushedMem=$push%-10s dma=${dmaDone(p.tag)} " +
                 f"reload=0x$got%08x expect=0x${p.v2}%08x ${if (got == p.v2) "OK" else "STALE"}")
      }
      lines.foreach(l => println(l._3))
      println(s"[cpush-inval] cycles=$cycles")
      for ((p, got, msg) <- lines) {
        if (p.dirty)
          assert(pushed.get(p.tag).contains(p.v1),
            s"phase ${p.tag}: the CPUSH writeback of the dirty line did not carry V1 -- $msg")
        assert(dmaDone(p.tag), s"phase ${p.tag}: the testbench DMA write never fired (vacuous) -- $msg")
        assert(got == p.v2,
          s"phase ${p.tag}: load after `${p.maint}` returned the STALE cached value -- CPUSH did " +
          s"not invalidate the data-cache line -- $msg")
      }
    }
  }
}
