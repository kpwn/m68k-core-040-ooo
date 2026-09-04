package m68k040.fuzz

import m68k040.M68kSim
import m68k040.VerilatorTest
import m68k040.oracle.ProgramAssembler
import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._
import java.nio.file.{Files, Paths}

/** ENV-GATED diagnostic harness for the precise-store-retirement (P2) path, in the
  * same family as `MiHangTraceSpec` / `WildPcA7TraceSpec` / `Cmp2HangTraceSpec`.
  *
  * Runs ONE ported test to a bounded cycle count while sampling, every cycle, the
  * joint ROB + StoreQueue + LS-EU state that governs the precise at-head drain:
  * ROB h0/tail/count/completes(h0)/retire0/flush/interruptPending/preciseDrainBusy,
  * the SQ ring (head/tail/drainBusy/drainPhaseB/per-entry committed+precise+robId),
  * the `pendPush`/`pendReady`/`pendApply` deferred-completion FIFO pointers, and the
  * LS-EU FSM state. On a run that never reaches the sentinel it dumps the last 400
  * cycles, which is how the P2.7 regression was root-caused: a `pendPush` that had
  * drifted one ahead of the SQ's precise-entry stream showed up directly as
  * `push != rdy` with the ROB head parked on a robId that was no longer in the SQ.
  *
  * INERT unless `P27_CASE` is set (so it costs a full-suite run nothing -- it would
  * otherwise pay its own Verilator compile). Usage:
  *   P27_CASE=movea_sp_sp_plain_load P27_CYCLES=1200 P27_FROM=1 \
  *     ~/sbt/bin/sbt "testOnly m68k040.fuzz.P27HangTraceSpec"
  * `P27_FROM` (nonzero) live-prints from that cycle on instead of only dumping the
  * trailing window -- needed when the interesting event precedes the stuck state.
  */
class P27HangTraceSpec extends AnyFunSuite {
  private val dir = Paths.get("src/test/resources/m68kooo-ported-tests/asm")
  private val name = sys.env.getOrElse("P27_CASE", "")
  private val cycles = sys.env.getOrElse("P27_CYCLES", "60000").toLong
  private val windowFrom = sys.env.getOrElse("P27_FROM", "0").toLong

  lazy val compiled = M68kSim().withVerilator.compile(new FuzzCoreDut)

  if (name.isEmpty) {
    test("p27 hangtrace: inert (set P27_CASE=<ported test name> to run)") {
      info("env-gated diagnostic harness -- see this spec's doc comment")
    }
  } else test(s"p27 hangtrace: $name", VerilatorTest) {
    val src = new String(Files.readAllBytes(dir.resolve(s"$name.s")))
    val image = ProgramAssembler.assemble(src, PortedTestRunner.loadAddr) match {
      case Right(i) => i
      case Left(e)  => fail(s"assemble: ${e.reason}")
    }
    compiled.doSim(s"p27trace_$name", 1) { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      FuzzDut.attachProgramWithBusErrors(dut.icache.logic.axi, cd, PortedTestRunner.loadAddr, image.bytes)
      val dmem = new m68k040.ls.BehavioralMemAgent(dut.dcache.logic.axi, cd, injectBusErrors = true)
      // (The two table-walker AXI memories that used to be attached here are gone:
      // the ITLB/DTLB walkers no longer emit AXI. Their descriptor reads and U/M
      // writebacks are DcacheService client traffic now, so they reach memory
      // through the D-cache above -- which is also the point: a page-table line
      // sitting dirty in L1D is now visible to the walk.)
      for (i <- image.bytes.indices) dmem.mem.write(PortedTestRunner.loadAddr + i, image.bytes(i).toByte)
      for (i <- 0 until 4) dmem.mem.write(PortedTestRunner.SentinelAddr + i, 0.toByte)

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
      dut.fa.logic.redirect.valid #= true
      dut.fa.logic.redirect.payload #= PortedTestRunner.loadAddr
      cd.waitSampling()
      dut.fa.logic.redirect.valid #= false

      val sq = dut.lsEu.logic.sq
      val rl = dut.rob.logic
      val ll = dut.lsEu.logic
      var cyc = 0L
      // rolling ring of the last N formatted lines
      val ringN = 400
      val ring = new Array[String](ringN)
      var ringIdx = 0
      var lastCommitCyc = 0L
      var lastCommitPc = 0L

      def snap(): String = {
        val h = sq.head.toInt; val t = sq.tail.toInt
        val ents = (0 until 8).map { i =>
          val v = sq.valids(i).toBoolean
          if (!v) "-" else {
            f"${i}:${if (sq.committed(i).toBoolean) "C" else "u"}${if (sq.precises(i).toBoolean) "P" else "f"}r${sq.robIds(i).toInt}"
          }
        }.mkString(",")
        val h0 = rl.head.toInt
        f"cyc=$cyc%7d ROB h0=$h0%2d tail=${rl.tail.toInt}%2d cnt=${rl.count.toInt}%2d " +
          f"cmpl(h0)=${rl.completes(h0).toBoolean} retire0=${rl.retire0.toBoolean} " +
          f"flushing=${rl.doFlushReg.toBoolean}/${rl.excActive.toBoolean} " +
          f"irqPend=${rl.interruptPending.toBoolean} pDrainBusy=${rl.preciseDrainBusyIn.toBoolean} | " +
          f"SQ h=$h%d t=$t%d busy=${sq.drainBusy.toBoolean} phB=${sq.drainPhaseB.toBoolean} " +
          f"drainV=${sq.io.drain.valid.toBoolean} ack=${sq.io.drainAck.toBoolean} " +
          f"sqCmpl=${sq.io.sqCompletion.valid.toBoolean} flush=${sq.io.flush.toBoolean} [$ents] | " +
          f"pend push=${ll.pendPush.toInt} rdy=${ll.pendReady.toInt} app=${ll.pendApply.toInt} " +
          f"live=${ll.liveCompletionFires.toBoolean} | FSM idle=${ll.dbgIsIdle.toBoolean} " +
          f"xl=${ll.dbgIsXlate.toBoolean} rs=${ll.dbgIsResolve.toBoolean} " +
          f"la=${ll.dbgIsLaunch.toBoolean} wt=${ll.dbgIsWait.toBoolean}"
      }

      var word = 0L
      while (word == 0 && cyc < cycles) {
        cd.waitSampling()
        cyc += 1
        for (k <- 0 until 2) {
          val c = rl.commitObs(k)
          if (c.fire.toBoolean) { lastCommitCyc = cyc; lastCommitPc = c.pc.toLong & 0xffffffffL }
        }
        val s = snap()
        ring(ringIdx % ringN) = s
        ringIdx += 1
        if (cyc >= windowFrom && windowFrom > 0) println(s)
        val b0 = dmem.mem.read(PortedTestRunner.SentinelAddr).toLong & 0xffL
        val b1 = dmem.mem.read(PortedTestRunner.SentinelAddr + 1).toLong & 0xffL
        val b2 = dmem.mem.read(PortedTestRunner.SentinelAddr + 2).toLong & 0xffL
        val b3 = dmem.mem.read(PortedTestRunner.SentinelAddr + 3).toLong & 0xffL
        word = (b0 << 24) | (b1 << 16) | (b2 << 8) | b3
      }
      println(f"[P27T] $name terminated cyc=$cyc word=0x$word%08x lastCommitCyc=$lastCommitCyc lastCommitPc=0x$lastCommitPc%08x")
      if (word == 0) {
        println(s"[P27T] ==== last $ringN cycles ====")
        val start = math.max(0, ringIdx - ringN)
        for (i <- start until ringIdx) println("[P27T] " + ring(i % ringN))
      }
    }
  }
}
