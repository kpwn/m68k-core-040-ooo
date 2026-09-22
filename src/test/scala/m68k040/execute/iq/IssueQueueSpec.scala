package m68k040.execute.iq

import m68k040.{M68kParams, M68kSim}
import m68k040.VerilatorTest
import m68k040.core.ParamPlugin
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.mutable.ArrayBuffer

class IssueQueueSpec extends AnyFunSuite {
  test("stationary position seeded cycle parity across wraps and flushes", VerilatorTest) {
    M68kSim().compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      idle(dut)
      dut.sink.logic.ready3 #= true
      dut.source.logic.lsWakeupValid #= false; dut.source.logic.lsWakeupPdst #= 0
      dut.source.logic.aluSlowWakeupValid #= false
      dut.source.logic.aluSlowWakeupPdst #= 0; dut.source.logic.aluSlowWakeupPdstV #= false
      dut.source.logic.aluSlowWakeupNzvc #= 0; dut.source.logic.aluSlowWakeupNzvcV #= false
      dut.source.logic.aluSlowWakeupX #= 0; dut.source.logic.aluSlowWakeupXV #= false
      dut.source.logic.cplxFpWakeupValid #= false; dut.source.logic.cplxFpWakeupTag #= 0
      dut.source.logic.cplxFpccWakeupValid #= false; dut.source.logic.cplxFpccWakeupTag #= 0
      for (s <- Seq(dut.source.logic.s0, dut.source.logic.s1)) {
        s.pFpSrcA #= 0; s.psrcAFpValid #= false
        s.pFpSrcB #= 0; s.psrcBFpValid #= false
        s.pFpDst #= 0; s.pFpDstValid #= false
        s.pFpccSrc #= 0; s.readsFpcc #= false
        s.pFpccDst #= 0; s.writesFpcc #= false
      }
      cd.waitSampling(5)
      val rng = new scala.util.Random(0x51a710L)
      val digest = java.security.MessageDigest.getInstance("SHA-256")
      var cycles = 0
      var pushes = 0
      var issued = 0
      var flushes = 0
      for (batch <- 0 until 160) {
        var next = 0
        val seen = scala.collection.mutable.Set.empty[Int]
        val dependent = Array.tabulate(16)(i => i > 0 && rng.nextBoolean())
        val flushed = batch % 7 == 3
        var done = false
        var localCycles = 0
        while (!done && localCycles < 250) {
          cd.waitFallingEdge()
          val flush = flushed && next >= 8
          val offer = !flush && next < 16 && rng.nextInt(5) != 0
          val pair = next < 15 && rng.nextBoolean()
          def uop(i: Int) = UopSpec(rob = i, pdstValid = true, pdst = i,
            psrcAValid = dependent(i) && batch % 5 == 0, psrcA = math.max(0, i - 1),
            readsNzvc = dependent(i) && batch % 5 == 1, writesNzvc = true,
            pNzvcSrc = math.max(0, i - 1), pNzvcDst = i,
            readsX = dependent(i) && batch % 5 == 2, writesX = true,
            pXSrc = math.max(0, i - 1), pXDst = i)
          pushUops(dut, if (offer) Some(uop(next)) else None,
            if (offer && pair) Some(uop(next + 1)) else None)
          // Exercise the two static FP scoreboards too, independently of the
          // dynamic CPLX FP paths covered by IqFpSpec. Synthetic IQ payloads
          // may write all classes; each physical tag has only one producer.
          for ((s, way) <- Seq(dut.source.logic.s0, dut.source.logic.s1).zipWithIndex) {
            val i = math.min(next + way, 15)
            s.pFpSrcA #= math.max(0, i - 1)
            s.psrcAFpValid #= (dependent(i) && batch % 5 == 3)
            s.pFpDst #= i; s.pFpDstValid #= true
            s.pFpccSrc #= math.max(0, i - 1)
            s.readsFpcc #= (dependent(i) && batch % 5 == 4)
            s.pFpccDst #= i; s.writesFpcc #= true
          }
          dut.source.logic.flush #= flush
          // Stall at the production ALU look-ahead interface. Arbitrarily holding
          // an already selected latency-one producer at the output would violate
          // the static-trigger contract rather than test queue backpressure.
          dut.sink.logic.ready0 #= true
          dut.sink.logic.ready1 #= true
          dut.source.logic.aluFastAccept0 #= (rng.nextInt(4) != 0)
          dut.source.logic.aluFastAccept1 #= (rng.nextInt(4) != 0)
          sleep(1)
          val accept = offer && dut.source.logic.pushReady.toBoolean
          val fires = Seq(
            (dut.sink.logic.v0.toBoolean && dut.sink.logic.ready0.toBoolean, dut.sink.logic.rob0.toInt),
            (dut.sink.logic.v1.toBoolean && dut.sink.logic.ready1.toBoolean, dut.sink.logic.rob1.toInt))
            .collect { case (true, id) => id }
          if (!flush) {
            fires.foreach { id =>
              assert(id < next, s"issue before push: batch=$batch id=$id next=$next")
              assert(!seen(id), s"duplicate issue: batch=$batch id=$id")
              if (dependent(id)) assert(seen(id - 1), s"early dependent: batch=$batch id=$id")
            }
            fires.foreach(seen.add)
            issued += fires.size
          } else assert(fires.isEmpty, "flush must suppress issue")
          digest.update(s"$batch:$localCycles:${dut.source.logic.pushReady.toBoolean}:$accept:$pair:$flush:${fires.mkString(",")}\n".getBytes("UTF-8"))
          if (accept) { next += (if (pair) 2 else 1); pushes += 1 }
          cd.waitRisingEdge(); sleep(1)
          cycles += 1; localCycles += 1
          done = flush || (next == 16 && seen.size == 16)
          if (flush) flushes += 1
        }
        assert(done, s"queue failed to drain in batch=$batch next=$next seen=$seen")
        // Let all delayed scoreboard clears settle before reusing physical tags.
        cd.waitFallingEdge()
        dut.source.logic.pushValid #= false
        dut.source.logic.flush #= false
        cd.waitSampling(4)
      }
      val hash = digest.digest().map(b => f"${b & 255}%02x").mkString
      println(s"IQ_STATIC_POSITION_TRACE cycles=$cycles pushes=$pushes issued=$issued flushes=$flushes sha256=$hash")
    }
  }

  class Dut extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val iq     = new IssueQueuePlugin
    val source = new IqSourcePlugin
    val sink   = new IqSinkPlugin
    // ParamPlugin publishes PHYS_INT_REGS (the IQ sizes its int scoreboards from it).
    db.on { host.asHostOf(Seq[FiberPlugin](new ParamPlugin(M68kParams()), iq, source, sink)) }
  }

  /** Drive both push slots independent/ready with the given robIds. */
  def setPush(dut: Dut, valid: Boolean, slot1Valid: Boolean, rob0: Int, rob1: Int): Unit = {
    val s = dut.source.logic
    s.pushValid #= valid
    s.slot1Valid #= slot1Valid
    s.s0.robId #= rob0
    s.s1.robId #= rob1
  }

  /** Full per-slot field control. Defaults = independent, ready. */
  case class UopSpec(
    rob: Int,
    pdstValid: Boolean = false, pdst: Int = 0,
    psrcAValid: Boolean = false, psrcA: Int = 0,
    psrcBValid: Boolean = false, psrcB: Int = 0,
    useImm: Boolean = false,
    readsNzvc: Boolean = false, writesNzvc: Boolean = false, pNzvcSrc: Int = 0, pNzvcDst: Int = 0,
    readsX: Boolean = false, writesX: Boolean = false, pXSrc: Int = 0, pXDst: Int = 0
  )

  /** Set push for a cycle with full field control. */
  def pushUops(dut: Dut, s0: Option[UopSpec], s1: Option[UopSpec]): Unit = {
    val src = dut.source.logic
    val slots = Seq(src.s0, src.s1)
    Seq(s0, s1).zip(slots).foreach { case (specOpt, slot) =>
      specOpt.foreach { u =>
        slot.robId #= u.rob
        slot.cluster #= m68k040.isa.Cluster.INT; slot.memOp #= m68k040.isa.MemOp.NONE
        slot.pdst #= u.pdst; slot.pdstValid #= u.pdstValid
        slot.psrcA #= u.psrcA; slot.psrcAValid #= u.psrcAValid
        slot.psrcB #= u.psrcB; slot.psrcBValid #= u.psrcBValid
        slot.useImm #= u.useImm
        slot.readsNzvc #= u.readsNzvc; slot.writesNzvc #= u.writesNzvc
        slot.pNzvcSrc #= u.pNzvcSrc; slot.pNzvcDst #= u.pNzvcDst
        slot.readsX #= u.readsX; slot.writesX #= u.writesX
        slot.pXSrc #= u.pXSrc; slot.pXDst #= u.pXDst
        slot.isShift #= false
      }
    }
    src.pushValid #= s0.isDefined
    src.slot1Valid #= s1.isDefined
  }

  def idle(dut: Dut): Unit = {
    val s = dut.source.logic
    s.pushValid #= false; s.slot1Valid #= false; s.flush #= false
    s.aluFastAccept0 #= true; s.aluFastAccept1 #= true
    for (slot <- Seq(s.s0, s.s1)) {
      slot.robId #= 0
      slot.cluster #= m68k040.isa.Cluster.INT; slot.memOp #= m68k040.isa.MemOp.NONE
      slot.pdst #= 0; slot.pdstValid #= false
      slot.psrcA #= 0; slot.psrcAValid #= false
      slot.psrcB #= 0; slot.psrcBValid #= false
      slot.useImm #= false
      slot.readsNzvc #= false; slot.writesNzvc #= false
      slot.pNzvcSrc #= 0; slot.pNzvcDst #= 0
      slot.readsX #= false; slot.writesX #= false
      slot.pXSrc #= 0; slot.pXDst #= 0
      slot.isShift #= false
    }
    dut.sink.logic.ready0 #= false
    dut.sink.logic.ready1 #= false
  }

  test("2-wide independent issue, age order", VerilatorTest) {
    M68kSim().compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      idle(dut)
      cd.waitSampling(2)

      // Sinks always ready.
      dut.sink.logic.ready0 #= true
      dut.sink.logic.ready1 #= true

      // Push robIds 0..5 two-per-cycle (3 cycles), all independent.
      // Push only while push.ready (it will stay ready, queue drains fully).
      val buf = ArrayBuffer[(Boolean, Int, Boolean, Int)]()
      val pushSeq = Seq((0, 1), (2, 3), (4, 5))
      var pi = 0
      // Run enough cycles to push all and observe issue.
      for (_ <- 0 until 12) {
        if (pi < pushSeq.size && dut.source.logic.pushReady.toBoolean) {
          val (a, b) = pushSeq(pi)
          setPush(dut, valid = true, slot1Valid = true, a, b)
          pi += 1
        } else {
          setPush(dut, valid = false, slot1Valid = false, 0, 0)
        }
        cd.waitSampling()
        // sample issue outputs (registered slots -> combinational issue this cycle)
        val s = dut.sink.logic
        buf += ((s.v0.toBoolean, s.rob0.toInt, s.v1.toBoolean, s.rob1.toInt))
      }

      // Extract the issued pairs in order.
      val issued = ArrayBuffer[Int]()
      for ((v0, r0, v1, r1) <- buf) {
        if (v0) issued += r0
        if (v1) issued += r1
      }
      assert(issued.toSeq == Seq(0, 1, 2, 3, 4, 5),
        s"expected ascending 0..5, got ${issued.mkString(",")}")
    }
  }

  test("back-pressure: pushReady deasserts after 16 queued, re-asserts on drain", VerilatorTest) {
    M68kSim().compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      idle(dut)
      cd.waitSampling(2)

      // Sinks NOT ready -> nothing drains.
      dut.sink.logic.ready0 #= false
      dut.sink.logic.ready1 #= false

      // Push two-per-cycle with valid held continuously and distinct robIds per
      // cycle. After enough cycles the queue fills (16 slots = 8 fires) and
      // pushReady deasserts. We then drain and count issued uops: exactly 16
      // must come out (no slot lost or duplicated), proving the queue admitted
      // exactly 8 two-wide pushes before back-pressuring.
      for (cyc <- 0 until 20) {
        // robIds 0..15 across the first 8 cycles; later cycles repeat but never
        // fire (queue full), so they are harmless.
        val ids = 1 << dut.source.logic.s0.robId.getWidth
        val a = (cyc * 2) % ids
        val b = (cyc * 2 + 1) % ids
        setPush(dut, valid = true, slot1Valid = true, a, b)
        cd.waitSampling()
      }
      // valid still high but queue full -> pushReady deasserted.
      assert(!dut.source.logic.pushReady.toBoolean, "pushReady should be deasserted when full")

      setPush(dut, valid = false, slot1Valid = false, 0, 0)
      cd.waitSampling()

      // Drain: enable sinks, count distinct issued uops.
      dut.sink.logic.ready0 #= true
      dut.sink.logic.ready1 #= true
      val drained = scala.collection.mutable.Set[Int]()
      for (_ <- 0 until 16) {
        cd.waitSampling()
        val s = dut.sink.logic
        if (s.v0.toBoolean) drained += s.rob0.toInt
        if (s.v1.toBoolean) drained += s.rob1.toInt
      }
      // The 16 admitted uops carried robIds 0..15 (first 8 push cycles).
      assert(drained == (0 until 16).toSet,
        s"expected robIds 0..15 to drain, got ${drained.toSeq.sorted.mkString(",")}")
      assert(dut.source.logic.pushReady.toBoolean, "pushReady should re-assert after drain")
    }
  }

  test("flush discards queued uops; fresh pushes still issue", VerilatorTest) {
    M68kSim().compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      idle(dut)
      cd.waitSampling(2)

      // Sinks not ready -> queue holds.
      dut.sink.logic.ready0 #= false
      dut.sink.logic.ready1 #= false

      // Keep IDs within the current default 32-entry ROB's five-bit domain.
      setPush(dut, valid = true, slot1Valid = true, 8, 9); cd.waitSampling()
      setPush(dut, valid = true, slot1Valid = true, 10, 11); cd.waitSampling()
      setPush(dut, valid = false, slot1Valid = false, 0, 0); cd.waitSampling()

      // Flush for one cycle.
      dut.source.logic.flush #= true
      cd.waitSampling()
      dut.source.logic.flush #= false
      cd.waitSampling()

      // Enable sinks; assert the flushed robIds NEVER issue.
      dut.sink.logic.ready0 #= true
      dut.sink.logic.ready1 #= true
      for (_ <- 0 until 6) {
        val s = dut.sink.logic
        val flushedSet = Set(8, 9, 10, 11)
        if (s.v0.toBoolean) assert(!flushedSet.contains(s.rob0.toInt), s"flushed uop ${s.rob0.toInt} issued on port0")
        if (s.v1.toBoolean) assert(!flushedSet.contains(s.rob1.toInt), s"flushed uop ${s.rob1.toInt} issued on port1")
        cd.waitSampling()
      }

      // Push 2 fresh uops; assert they issue.
      val got = ArrayBuffer[Int]()
      setPush(dut, valid = true, slot1Valid = true, 24, 25); cd.waitSampling()
      setPush(dut, valid = false, slot1Valid = false, 0, 0)
      for (_ <- 0 until 6) {
        val s = dut.sink.logic
        if (s.v0.toBoolean) got += s.rob0.toInt
        if (s.v1.toBoolean) got += s.rob1.toInt
        cd.waitSampling()
      }
      assert(got.contains(24) && got.contains(25), s"fresh uops did not issue, got ${got.mkString(",")}")
    }
  }

  test("flush invalidates nonzero dependency masks even with simultaneous push and slot reuse", VerilatorTest) {
    M68kSim().compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      idle(dut)
      cd.waitSampling(2)

      // Fill with producer/consumer pairs while execution is backpressured.
      // Unlike the independent-uop flush test, this leaves live static triggers.
      for (pair <- 0 until 6) {
        assert(dut.source.logic.pushReady.toBoolean)
        pushUops(dut,
          Some(UopSpec(pair * 2, pdstValid = true, pdst = pair + 1)),
          Some(UopSpec(pair * 2 + 1, psrcAValid = true, psrcA = pair + 1)))
        cd.waitSampling()
      }
      pushUops(dut, None, None)
      cd.waitSampling()
      val slots = dut.iq.logic.lines.flatMap(_.ways)
      assert(slots.exists(s => s.sel.toBoolean && s.triggers.toBigInt != 0),
        "test did not leave any occupied static dependency masks")

      // A concurrent push must lose to flush, including any entries already
      // sitting in registered issue stages. Neither may leak to an EU.
      pushUops(dut, Some(UopSpec(0)), Some(UopSpec(1)))
      dut.source.logic.flush #= true
      dut.sink.logic.ready0 #= true
      dut.sink.logic.ready1 #= true
      sleep(1)
      assert(issuedThisCycle(dut).isEmpty, "wrong-path issue on flush cycle")
      cd.waitSampling()
      pushUops(dut, None, None)
      dut.source.logic.flush #= false
      sleep(1)
      assert(slots.forall(s => !s.sel.toBoolean), "flush left occupied slots")

      // More than one queue's worth of fresh dependent pairs shifts through all
      // former positions. No old trigger or scoreboard owner may block them.
      val got = ArrayBuffer[Int]()
      var pair = 0
      for (_ <- 0 until 100) {
        got ++= issuedThisCycle(dut)
        if (pair < 10 && dut.source.logic.pushReady.toBoolean) {
          pushUops(dut,
            Some(UopSpec(12 + pair * 2, pdstValid = true, pdst = pair + 1)),
            Some(UopSpec(13 + pair * 2, psrcAValid = true, psrcA = pair + 1)))
          pair += 1
        } else pushUops(dut, None, None)
        cd.waitSampling()
        sleep(1)
      }
      assert(pair == 10)
      assert(got.sorted == (12 until 32), s"missing, duplicate or wrong-path issue: $got")
      for (producer <- 12 until 32 by 2)
        assert(got.indexOf(producer) < got.indexOf(producer + 1), "dependency order lost")
    }
  }

  /** Collect issued robIds per cycle as a list of (cycle -> Seq[robId]). */
  def issuedThisCycle(dut: Dut): Seq[Int] = {
    val s = dut.sink.logic
    val out = ArrayBuffer[Int]()
    if (s.v0.toBoolean) out += s.rob0.toInt
    if (s.v1.toBoolean) out += s.rob1.toInt
    out.toSeq
  }

  test("dependency chain serializes at latency-1 (one per cycle, in order)", VerilatorTest) {
    M68kSim().compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      idle(dut)
      cd.waitSampling(2)

      // Both sinks ready throughout.
      dut.sink.logic.ready0 #= true
      dut.sink.logic.ready1 #= true

      // Push 5 int ALU uops, robId 0..4, uop k reads uop (k-1)'s int dst.
      // uop0: pdst=0, no src. uop k>0: pdst=k, psrcA=(k-1).
      def mk(k: Int): UopSpec =
        if (k == 0) UopSpec(rob = 0, pdstValid = true, pdst = 0)
        else UopSpec(rob = k, pdstValid = true, pdst = k, psrcAValid = true, psrcA = k - 1)

      // Push all 5 BEFORE observing issue, so the whole chain is resident and
      // only wakeup (not push timing) gates issue. Push pairs: (0,1),(2,3),(4,-).
      // But pushing while issue is enabled would let uop0 issue early. To make
      // the test about wakeup, hold sinks not-ready during the load, then enable.
      dut.sink.logic.ready0 #= false
      dut.sink.logic.ready1 #= false
      pushUops(dut, Some(mk(0)), Some(mk(1))); cd.waitSampling()
      pushUops(dut, Some(mk(2)), Some(mk(3))); cd.waitSampling()
      pushUops(dut, Some(mk(4)), None);        cd.waitSampling()
      pushUops(dut, None, None)
      cd.waitSampling()

      // Enable sinks and watch: exactly one issues per cycle, in order 0,1,2,3,4.
      dut.sink.logic.ready0 #= true
      dut.sink.logic.ready1 #= true
      val perCycle = ArrayBuffer[Seq[Int]]()
      for (_ <- 0 until 10) {
        cd.waitSampling()
        perCycle += issuedThisCycle(dut)
      }
      val flat = perCycle.flatten.toSeq
      assert(flat == Seq(0, 1, 2, 3, 4), s"expected ordered 0..4, got ${flat.mkString(",")}")
      // Strict: no cycle issued 2 (chain must serialize).
      assert(perCycle.forall(_.size <= 1),
        s"chain must serialize 1/cycle, got per-cycle ${perCycle.map(_.mkString("+")).mkString("|")}")
      // Exactly 5 cycles produced an issue, consecutive.
      val firstIssue = perCycle.indexWhere(_.nonEmpty)
      assert(firstIssue >= 0)
      for (i <- 0 until 5)
        assert(perCycle(firstIssue + i) == Seq(i),
          s"cycle ${firstIssue + i} expected just $i, got ${perCycle(firstIssue + i).mkString(",")}")
    }
  }

  test("depend-on-READ: flag-write-only does NOT serialize; read-flags DOES", VerilatorTest) {
    M68kSim().compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      idle(dut)
      cd.waitSampling(2)

      // ---- Part A: 6 ALU uops each WRITE NZVC+X (pNzvcDst/pXDst=k) but read no
      // flags; independent int. They must issue TWO-PER-CYCLE (no dependency
      // from flag writes). Load with sinks off, then drain.
      dut.sink.logic.ready0 #= false
      dut.sink.logic.ready1 #= false
      def writer(k: Int): UopSpec = UopSpec(
        rob = k, pdstValid = true, pdst = k,
        writesNzvc = true, pNzvcDst = k % 16, writesX = true, pXDst = k % 16
      )
      pushUops(dut, Some(writer(0)), Some(writer(1))); cd.waitSampling()
      pushUops(dut, Some(writer(2)), Some(writer(3))); cd.waitSampling()
      pushUops(dut, Some(writer(4)), Some(writer(5))); cd.waitSampling()
      pushUops(dut, None, None); cd.waitSampling()

      dut.sink.logic.ready0 #= true
      dut.sink.logic.ready1 #= true
      val perCycleA = ArrayBuffer[Seq[Int]]()
      for (_ <- 0 until 8) {
        cd.waitSampling()
        perCycleA += issuedThisCycle(dut)
      }
      val flatA = perCycleA.flatten.toSeq
      assert(flatA.toSet == (0 until 6).toSet, s"part A: expected all of 0..5, got ${flatA.mkString(",")}")
      // Headline: flag-write-only ops are NOT serialized -> drains in 3 cycles
      // of 2-per-cycle (the queue holds 6, both sinks ready).
      val nonEmptyA = perCycleA.filter(_.nonEmpty)
      assert(nonEmptyA.count(_.size == 2) >= 3,
        s"part A: flag-WRITE-only must issue 2/cycle, got ${perCycleA.map(_.mkString("+")).mkString("|")}")
      assert(nonEmptyA.size <= 3,
        s"part A: should drain in <=3 cycles at 2/cycle, got ${nonEmptyA.size} issuing cycles: ${perCycleA.map(_.mkString("+")).mkString("|")}")

      // Settle.
      idle(dut); cd.waitSampling(3)

      // ---- Part B (contrast): 4 uops where each READS the prior's NZVC and
      // writes its own NZVC. Must serialize 1/cycle.
      dut.sink.logic.ready0 #= false
      dut.sink.logic.ready1 #= false
      def flagChain(k: Int): UopSpec =
        if (k == 0) UopSpec(rob = 0, writesNzvc = true, pNzvcDst = 0)
        else UopSpec(rob = k, readsNzvc = true, pNzvcSrc = k - 1, writesNzvc = true, pNzvcDst = k)
      pushUops(dut, Some(flagChain(0)), Some(flagChain(1))); cd.waitSampling()
      pushUops(dut, Some(flagChain(2)), Some(flagChain(3))); cd.waitSampling()
      pushUops(dut, None, None); cd.waitSampling()

      dut.sink.logic.ready0 #= true
      dut.sink.logic.ready1 #= true
      val perCycleB = ArrayBuffer[Seq[Int]]()
      for (_ <- 0 until 10) {
        cd.waitSampling()
        perCycleB += issuedThisCycle(dut)
      }
      val flatB = perCycleB.flatten.toSeq
      assert(flatB == Seq(0, 1, 2, 3), s"part B: expected ordered 0..3, got ${flatB.mkString(",")}")
      assert(perCycleB.forall(_.size <= 1),
        s"part B: flag-READ chain must serialize 1/cycle, got ${perCycleB.map(_.mkString("+")).mkString("|")}")
    }
  }

  test("age + mixed: independents issue while dependents wait, in age order", VerilatorTest) {
    M68kSim().compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      idle(dut)
      cd.waitSampling(2)

      // Load four uops (sinks off):
      //  rob0: independent, pdst=0
      //  rob1: independent, pdst=1
      //  rob2: depends on rob0 (psrcA=0)
      //  rob3: depends on rob2 (psrcA=2)  -> tail of a chain behind rob0
      // Expectation when drained 2-wide:
      //  cycle T  : 0 and 1 issue together (both ready; 2,3 wait)
      //  cycle T+1: 2 issues (woken by 0); 3 still waits (needs 2)
      //  cycle T+2: 3 issues (woken by 2)
      dut.sink.logic.ready0 #= false
      dut.sink.logic.ready1 #= false
      val u0 = UopSpec(rob = 0, pdstValid = true, pdst = 0)
      val u1 = UopSpec(rob = 1, pdstValid = true, pdst = 1)
      val u2 = UopSpec(rob = 2, pdstValid = true, pdst = 2, psrcAValid = true, psrcA = 0)
      val u3 = UopSpec(rob = 3, pdstValid = true, pdst = 3, psrcAValid = true, psrcA = 2)
      pushUops(dut, Some(u0), Some(u1)); cd.waitSampling()
      pushUops(dut, Some(u2), Some(u3)); cd.waitSampling()
      pushUops(dut, None, None); cd.waitSampling()

      dut.sink.logic.ready0 #= true
      dut.sink.logic.ready1 #= true
      val perCycle = ArrayBuffer[Seq[Int]]()
      for (_ <- 0 until 8) {
        cd.waitSampling()
        perCycle += issuedThisCycle(dut)
      }
      val nonEmpty = perCycle.filter(_.nonEmpty)
      assert(nonEmpty.nonEmpty)
      assert(nonEmpty(0).sorted == Seq(0, 1),
        s"first issuing cycle should be independents 0,1; got ${nonEmpty(0).mkString(",")}")
      assert(nonEmpty(1) == Seq(2),
        s"second issuing cycle should be just 2 (woken by 0); got ${nonEmpty(1).mkString(",")}")
      assert(nonEmpty(2) == Seq(3),
        s"third issuing cycle should be just 3 (woken by 2); got ${nonEmpty(2).mkString(",")}")
      val flat = perCycle.flatten.toSeq
      assert(flat == Seq(0, 1, 2, 3) || flat == Seq(1, 0, 2, 3),
        s"all four issue, dependents after producers; got ${flat.mkString(",")}")
    }
  }

  // -------------------------------------------------------------------------
  // A. CRITICAL regression: the oldest uop must survive while a hole opens
  //    above it. Reproduces the silent-oldest-drop bug that the COUNT-proxy
  //    push.ready had: with the oldest stalled in line 0 and younger ready
  //    uops draining `count` below slotCount-wayCount, the old gate would
  //    re-assert push.ready and the next compaction would DISCARD line 0.
  //
  //    Construction: the OLDEST uop sits in line 0 but its issue port (port 0,
  //    which OHMasking.first always assigns to the lowest-index ready slot) is
  //    held NOT-ready, so it can never fire. Younger independent uops are ready
  //    and drain one-per-cycle via port 1. This empties the upper lines (count
  //    falls toward the old threshold) while line 0 stays occupied. We then
  //    attempt to push more uops.
  //
  //    Hard invariants:
  //      * while the oldest occupies line 0, pushReady MUST be deasserted (the
  //        queue refuses to compact past the occupied oldest line). With the old
  //        count gate pushReady would re-assert and the push would drop rob 0.
  //      * NO pushed robId is ever lost: every robId we push eventually issues.
  //        Once we release port 0, the oldest must still issue.
  // -------------------------------------------------------------------------
  test("CRITICAL: oldest survives a hole; pushReady stays low while line0 occupied", VerilatorTest) {
    M68kSim().compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      idle(dut)
      cd.waitSampling(2)

      val pushedRobs = scala.collection.mutable.Set[Int]()
      val issuedRobs = scala.collection.mutable.Set[Int]()

      def collectIssue(): Unit = {
        val s = dut.sink.logic
        if (s.v0.toBoolean && dut.sink.logic.ready0.toBoolean) issuedRobs += s.rob0.toInt
        if (s.v1.toBoolean && dut.sink.logic.ready1.toBoolean) issuedRobs += s.rob1.toInt
      }

      // Push the OLDEST uop (rob 0, independent/ready) first, then 7 pairs of
      // younger independents back-to-back with NO gaps and sinks OFF. Each push.fire
      // compacts the table down by one line; after 7 follow-up push.fires rob 0
      // (which landed at priority 14) marches to priority 0 = LINE 0. The queue
      // then holds 15 uops with rob 0 parked in line 0.
      dut.sink.logic.ready0 #= false
      dut.sink.logic.ready1 #= false
      pushUops(dut, Some(UopSpec(rob = 0, pdstValid = true, pdst = 0)), None)
      pushedRobs += 0
      cd.waitSampling()

      var nextRob = 1
      for (_ <- 0 until 7) {
        val a = nextRob; val b = nextRob + 1
        assert(dut.source.logic.pushReady.toBoolean,
          s"load: pushReady should be high pushing rob $a,$b")
        pushUops(dut, Some(UopSpec(rob = a, pdstValid = true, pdst = 20 + a)),
                      Some(UopSpec(rob = b, pdstValid = true, pdst = 20 + b)))
        pushedRobs += a; pushedRobs += b
        nextRob += 2
        cd.waitSampling()
      }
      pushUops(dut, None, None)
      cd.waitSampling()

      // Now release port 1 only: the oldest (rob 0) is the lowest-index ready slot
      // so OHMasking.first puts it on port 0, which stays NOT-ready -> rob 0 can
      // never fire. Younger ready slots issue one-per-cycle on port 1, draining
      // the upper lines while rob 0 stays parked in line 0.
      dut.sink.logic.ready0 #= false
      dut.sink.logic.ready1 #= true
      for (_ <- 0 until 16) {
        cd.waitSampling()
        collectIssue()
        // KEY ASSERTION: rob 0 occupies line 0 (it is ready but cannot fire on
        // the disabled port 0), so the queue must refuse to compact -> pushReady
        // must be deasserted. The old count gate would re-assert here and the
        // next push would silently discard rob 0.
        assert(!dut.source.logic.pushReady.toBoolean,
          "pushReady must stay LOW while the oldest occupies line 0 (no silent drop)")
        // Confirm rob 0 has NOT issued (it cannot, port 0 disabled).
        assert(!issuedRobs.contains(0), "oldest rob 0 must not have issued yet")
      }

      // While line 0 is occupied, try to force a push; it must NOT be accepted
      // (pushReady low). Attempt a push of a brand-new robId and verify it is
      // refused (push.fire == valid && ready, ready is low).
      pushUops(dut, Some(UopSpec(rob = 30, pdstValid = true, pdst = 40)), None)
      cd.waitSampling()
      assert(!dut.source.logic.pushReady.toBoolean,
        "push of rob 30 must be refused while line 0 occupied")
      pushUops(dut, None, None)
      cd.waitSampling()

      // Release port 0: the oldest must now finally issue -> proving it was never
      // dropped.
      dut.sink.logic.ready0 #= true
      dut.sink.logic.ready1 #= true
      for (_ <- 0 until 20) {
        collectIssue()
        cd.waitSampling()
      }
      collectIssue()

      // Hard no-drop invariant: every pushed robId issued; none vanished. rob 30
      // was never accepted (pushReady was low), so it is not in pushedRobs.
      assert(issuedRobs.contains(0), "CRITICAL: oldest rob 0 was DROPPED (never issued)")
      assert(pushedRobs.subsetOf(issuedRobs),
        s"some pushed robId vanished. pushed=${pushedRobs.toSeq.sorted}, issued=${issuedRobs.toSeq.sorted}")
    }
  }

  // -------------------------------------------------------------------------
  // B. Push-while-issuing in the SAME cycle exercises the eventsMoved (events
  //    >> wayCount) wakeup-alignment branch: when a push.fire compacts the same
  //    cycle a producer issues, the wakeup event for that producer must be
  //    shifted down by wayCount to land on the (now-shifted) dependent.
  // -------------------------------------------------------------------------
  test("push-while-issuing aligns wakeup (events>>wayCount) and does not drop the new uop", VerilatorTest) {
    M68kSim().compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      idle(dut)
      cd.waitSampling(2)

      // Producer P (rob 0, pdst=5) and dependent C (rob 1, psrcA=5). Load with
      // sinks off so both are resident; C carries a trigger on P.
      dut.sink.logic.ready0 #= false
      dut.sink.logic.ready1 #= false
      val p = UopSpec(rob = 0, pdstValid = true, pdst = 5)
      val c = UopSpec(rob = 1, pdstValid = true, pdst = 6, psrcAValid = true, psrcA = 5)
      pushUops(dut, Some(p), Some(c)); cd.waitSampling()
      pushUops(dut, None, None); cd.waitSampling()

      // Enable sinks. On the cycle P issues, simultaneously PUSH a new independent
      // uop D (rob 2) -> push.fire and an issue happen the same cycle, exercising
      // the eventsMoved (>> wayCount) wakeup-shift path.
      dut.sink.logic.ready0 #= true
      dut.sink.logic.ready1 #= true

      val perCycle = ArrayBuffer[Seq[Int]]()
      var pushedD = false
      // Push D exactly on the first cycle (P should be ready to issue then). The
      // push fires only if pushReady is high; line0 holds P which issues this
      // cycle, so selComb of line0 frees and pushReady was set last cycle.
      pushUops(dut, Some(UopSpec(rob = 2, pdstValid = true, pdst = 7)), None)
      for (i <- 0 until 12) {
        val accepted = dut.source.logic.pushReady.toBoolean && !pushedD
        cd.waitSampling()
        if (accepted) { pushedD = true; pushUops(dut, None, None) }
        perCycle += issuedThisCycle(dut)
      }

      val flat = perCycle.flatten.toSeq
      // P must issue, then C exactly one cycle later (latency-1 wakeup survived the
      // same-cycle compaction), and D must also issue (not dropped).
      assert(flat.contains(0) && flat.contains(1) && flat.contains(2),
        s"all of P(0), C(1), D(2) must issue; got ${flat.mkString(",")}")
      assert(pushedD, "D was never accepted (push-while-issue path not exercised)")
      val pIdx = perCycle.indexWhere(_.contains(0))
      val cIdx = perCycle.indexWhere(_.contains(1))
      assert(pIdx >= 0 && cIdx >= 0, "P and C must both issue")
      assert(cIdx == pIdx + 1,
        s"C must issue exactly 1 cycle after P (latency-1 wakeup); P@$pIdx C@$cIdx, per-cycle ${perCycle.map(_.mkString("+")).mkString("|")}")
    }
  }

  // ---------------------------------------------------------------------------
  // Task #219 Fix 2 regression: the scoreboard busy-clear is RETIMED to C+1
  // (decoded off the registered m2sPipe payload instead of the live MuxOH select
  // cone). That retime is only safe because push-time `trigInit` reads
  // `sb*BusyEff = busy & ~sb*Clr`, i.e. the same-cycle bypass of the in-flight
  // clear. Drop that bypass and a consumer pushed EXACTLY ONE CYCLE after its
  // producer's issue latches a static trigger on a slot that has already been
  // freed and whose `events` pulse has already passed -> the trigger can never
  // clear -> the consumer hangs forever (silent deadlock, no wrong answer).
  //
  // The phase of "exactly one cycle after issue" relative to the test's own push
  // cadence is an implementation detail, so this sweeps the consumer's push delay
  // across the whole window that can contain it and requires the consumer to
  // issue (after its producer) for EVERY delay. MUTATION-PROVEN: replacing the
  // four `sb*BusyEff` arguments in `trigInit` with the raw `sb*.busy` registers
  // makes this hang at one of these delays; no other spec in the suite does.
  test("MUST NOT DEADLOCK: consumer pushed 1 cycle after its producer's issue", VerilatorTest) {
    M68kSim().compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)

      for (delay <- 0 to 6) {
        idle(dut)
        // Flush between sub-cases so each starts from a clean queue/scoreboard.
        dut.source.logic.flush #= true
        cd.waitSampling()
        dut.source.logic.flush #= false
        cd.waitSampling(3)

        dut.sink.logic.ready0 #= true
        dut.sink.logic.ready1 #= true

        // A fresh physreg per sub-case keeps the one-producer-per-physreg
        // invariant intact across the sweep.
        val pdst = 5 + delay
        val prodRob = 10 + delay * 2
        val consRob = prodRob + 1
        val prod = UopSpec(rob = prodRob, pdstValid = true, pdst = pdst)
        val cons = UopSpec(rob = consRob, psrcAValid = true, psrcA = pdst)

        pushUops(dut, Some(prod), None)
        cd.waitSampling()
        pushUops(dut, None, None)

        val perCycle = ArrayBuffer[Seq[Int]]()
        var pushedC = false
        for (i <- 0 until 40) {
          if (!pushedC && i == delay && dut.source.logic.pushReady.toBoolean) {
            pushUops(dut, Some(cons), None)
            cd.waitSampling()
            pushedC = true
            pushUops(dut, None, None)
          } else {
            cd.waitSampling()
          }
          perCycle += issuedThisCycle(dut)
        }

        val flat = perCycle.flatten.toSeq
        assert(pushedC, s"[delay=$delay] consumer was never accepted by the IQ")
        assert(flat.contains(prodRob), s"[delay=$delay] producer never issued; got ${flat.mkString(",")}")
        val pIdx = perCycle.indexWhere(_.contains(prodRob))
        val cIdx = perCycle.indexWhere(_.contains(consRob))
        assert(cIdx >= 0,
          s"[delay=$delay] DEADLOCK: consumer rob=$consRob never issued in 40 cycles " +
          s"(producer rob=$prodRob issued at cycle $pIdx). This is the retimed-scoreboard-clear " +
          s"bypass failure mode -- `trigInit` must read `sb*BusyEff`, not raw `sb*.busy`. " +
          s"per-cycle: ${perCycle.map(_.mkString("+")).mkString("|")}")
        assert(cIdx > pIdx,
          s"[delay=$delay] consumer issued at $cIdx, before/with its producer at $pIdx -- " +
          s"the dependency was not tracked at all, so this sub-case proves nothing")
      }
    }
  }
}
