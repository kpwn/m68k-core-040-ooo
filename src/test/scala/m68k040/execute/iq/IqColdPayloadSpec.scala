package m68k040.execute.iq

import m68k040.{M68kParams, M68kSim, VerilatorTest}
import m68k040.core.ParamPlugin
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** Directed coverage for the IQ COLD-PAYLOAD SPLIT (the wide dispatch half of `IqContext`
  * moved out of the compacting slot array into two single-write-port `Mem`s addressed by
  * `robId`, with only the narrow `IqHot` record riding the select cone).
  *
  * The split's `GenerationFlags.simulation` tripwire already checks every port on every
  * cycle of every simulation this project runs, and is mutation-proven to catch an
  * inverted bank select, a mis-addressed row write, and a mis-tagged way. What it cannot
  * guarantee on its own is that the corpus ever REACHES the two states that would break
  * the design if the reasoning were wrong. That is what this file forces:
  *
  *   1. ALL FIVE select ports holding FIVE DIFFERENT slots' payloads at once. Each port
  *      reads the cold Mem independently, so this is the state that would expose any
  *      shared/aliased read port or any cross-port address mix-up. The check is on `pc`
  *      -- a field that exists ONLY in the cold Mem, never in the narrow record -- so
  *      matching robIds alone cannot make the test pass.
  *
  *   2. A back-pressured port holding a row's address across a burst of pushes that
  *      churns and compacts the whole slot array underneath it. This is the concrete form
  *      of the "no row can be overwritten while a stalled port still needs it" claim: the
  *      old design physically carried the payload in the pipe register, the new one
  *      carries only an address and re-reads the row every cycle the EU stalls.
  *
  * Self-contained DUT (its own sink with per-port readies) so no existing IQ spec's
  * harness changes -- the same pattern IqCplxSpec/IqAluSlowSpec already use. */
class IqColdPayloadSpec extends AnyFunSuite {

  /** Test-only sink exposing all five issue ports with individually driveable ready. */
  class AllPortSink extends FiberPlugin {
    val logic = during build new Area {
      val iq = host[IssueQueueService]
      val ready = in Bits (5 bits)
      val valid = out Bits (5 bits)
      val rob   = out Vec (UInt(6 bits), 5)
      val pc    = out Vec (UInt(32 bits), 5)
      for (k <- 0 until 5) {
        iq.issue(k).ready := ready(k)
        valid(k) := iq.issue(k).valid
        rob(k)   := iq.issue(k).payload.robId
        // `pc` lives ONLY in the cold Mem -- this tap is the actual subject of the test.
        pc(k)    := iq.issue(k).payload.uop.pc
      }
    }
  }

  class Dut extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val iq     = new IssueQueuePlugin
    val source = new IqSourcePlugin
    val sink   = new AllPortSink
    db.on { host.asHostOf(Seq[FiberPlugin](new ParamPlugin(M68kParams()), iq, source, sink)) }
  }

  /** Issue-port class of a pushed uop. The five ports are class-disjoint by construction
    * (see IssueQueuePlugin's select section), which is what lets one uop of each class sit
    * on a different port simultaneously. */
  sealed trait Kind
  case object Alu    extends Kind
  case object Branch extends Kind
  case object Ls     extends Kind
  case object Cplx   extends Kind

  /** All slots independent + immediately ready: no sources, no dsts, no flags. */
  def drive(dut: Dut, which: Int, rob: Int, kind: Kind): Unit = {
    val slot = if (which == 0) dut.source.logic.s0 else dut.source.logic.s1
    slot.robId #= rob
    kind match {
      case Ls     => slot.cluster #= m68k040.isa.Cluster.LS;   slot.memOp #= m68k040.isa.MemOp.LOAD
      case Cplx   => slot.cluster #= m68k040.isa.Cluster.CPLX; slot.memOp #= m68k040.isa.MemOp.NONE
      case Branch => slot.cluster #= m68k040.isa.Cluster.EA;   slot.memOp #= m68k040.isa.MemOp.NONE
      case _      => slot.cluster #= m68k040.isa.Cluster.INT;  slot.memOp #= m68k040.isa.MemOp.NONE
    }
    slot.pdst #= 0; slot.pdstValid #= false
    slot.psrcA #= 0; slot.psrcAValid #= false
    slot.psrcB #= 0; slot.psrcBValid #= false
    slot.useImm #= false
    slot.readsNzvc #= false; slot.writesNzvc #= false
    slot.pNzvcSrc #= 0; slot.pNzvcDst #= 0
    slot.readsX #= false; slot.writesX #= false
    slot.pXSrc #= 0; slot.pXDst #= 0
    slot.isShift #= false
    slot.pFpSrcA #= 0; slot.psrcAFpValid #= false
    slot.pFpSrcB #= 0; slot.psrcBFpValid #= false
    slot.pFpDst  #= 0; slot.pFpDstValid  #= false
    slot.pFpccSrc #= 0; slot.readsFpcc  #= false
    slot.pFpccDst #= 0; slot.writesFpcc #= false
  }

  def idle(dut: Dut): Unit = {
    val s = dut.source.logic
    s.pushValid #= false; s.slot1Valid #= false; s.flush #= false
    s.aluFastAccept0 #= true; s.aluFastAccept1 #= true
    drive(dut, 0, 0, Alu)
    drive(dut, 1, 0, Alu)
    dut.sink.logic.ready #= 0   // every port back-pressured
  }

  /** Push one pair, waiting for pushReady. The harness sets each uop's COLD `pc` field to
    * its own robId, so a port that reads the WRONG cold row yields pc != robId. */
  def pushPair(dut: Dut, a: (Int, Kind), b: Option[(Int, Kind)]): Unit = {
    val cd = dut.clockDomain
    val s  = dut.source.logic
    while (!s.pushReady.toBoolean) { cd.waitSampling() }
    drive(dut, 0, a._1, a._2)
    b.foreach { case (r, k) => drive(dut, 1, r, k) }
    s.pushValid  #= true
    s.slot1Valid #= b.isDefined
    cd.waitSampling()
    s.pushValid #= false; s.slot1Valid #= false
  }

  test("all five select ports deliver their OWN cold payload in the same cycle", VerilatorTest) {
    M68kSim().compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      idle(dut)
      cd.waitSampling(2)

      // Queue one uop of each issue class. With every port back-pressured nothing can
      // enter a registered issue stage yet: this IQ's m2sPipe is `collapsBubble = false`,
      // so `selPorts(k).ready` IS the downstream ready -- a deliberate correctness choice
      // (see IssueQueuePlugin's comment) that also makes "park one uop per port" a
      // precise, race-free operation here.
      //
      // Age order matters: the two ALU uops must be OLDEST so they take ports 0 and 1
      // (both ALU ports pick from the same class, lowest-index-first).
      pushPair(dut, (11, Alu),    Some((12, Alu)))
      pushPair(dut, (13, Branch), Some((14, Ls)))
      pushPair(dut, (15, Cplx),   None)
      cd.waitSampling(3)

      // ONE ready pulse: all five m2sPipes load from their five independent selections in
      // the SAME cycle (their data registers are empty, so no handshake fires yet).
      dut.sink.logic.ready #= 0x1f
      cd.waitSampling()
      // Drop ready again -> all five hold their payloads, valid on every port at once.
      dut.sink.logic.ready #= 0
      cd.waitSampling()

      assert(dut.sink.logic.valid.toInt == 0x1f,
        s"all five issue ports never presented a uop simultaneously " +
        f"(valid mask = 0x${dut.sink.logic.valid.toInt}%02x)")

      // THE CHECK: every port's robId AND its cold-Mem-sourced pc must be its own.
      // `pc` exists only in the cold Mem, so a shared/aliased read port, a cross-port
      // address mix-up, or an inverted bank select all fail here even though every
      // robId (which rides the narrow record) would still look right.
      val expect = Map(0 -> 11, 1 -> 12, 2 -> 13, 3 -> 14, 4 -> 15)
      for (k <- 0 until 5) {
        val gotRob = dut.sink.logic.rob(k).toInt
        val gotPc  = dut.sink.logic.pc(k).toLong
        assert(gotRob == expect(k), s"port $k: expected robId ${expect(k)}, got $gotRob")
        assert(gotPc == expect(k).toLong,
          f"port $k: COLD payload mismatch -- expected pc 0x${expect(k).toLong}%08x, " +
          f"got 0x$gotPc%08x (a sibling port's row, or a mis-addressed read)")
      }

      // And all five must hand off in ONE cycle: the split serialised nothing.
      dut.sink.logic.ready #= 0x1f
      cd.waitSampling()
      dut.sink.logic.ready #= 0
      cd.waitSampling()
      assert(dut.sink.logic.valid.toInt == 0,
        f"expected all five ports to complete their handshake in the same cycle; " +
        f"valid still 0x${dut.sink.logic.valid.toInt}%02x")
    }
  }

  test("a back-pressured port's cold row survives the slot array churning underneath it", VerilatorTest) {
    M68kSim().compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      idle(dut)
      cd.waitSampling(2)

      // Park ONE LS uop on port 3, then keep port 3 back-pressured for the whole test.
      pushPair(dut, (7, Ls), None)
      cd.waitSampling(3)
      dut.sink.logic.ready #= 0x08   // one pulse: port 3's pipe loads
      cd.waitSampling()
      dut.sink.logic.ready #= 0x03   // ports 0,1 ready (ALU traffic drains); port 3 stalled
      cd.waitSampling()

      assert((dut.sink.logic.valid.toInt & 0x8) != 0, "LS port never latched its uop")
      assert(dut.sink.logic.rob(3).toInt == 7, "LS port latched the wrong uop")
      assert(dut.sink.logic.pc(3).toLong == 7L, "LS port latched the wrong cold payload")

      // Now churn: 20 more ALU uops through the queue while port 3 stays stalled. Every
      // push.fire compacts all 16 slots and every ALU issue frees one. In the OLD design
      // port 3's payload was physically held in its own pipe register and could not be
      // disturbed by any of this; in the NEW design port 3 holds only {robId, coldWay} and
      // re-reads the cold row combinationally on every one of these cycles -- so if a row
      // could be overwritten out from under a stalled port, this is where it shows.
      var rob = 20
      for (_ <- 0 until 10) {
        pushPair(dut, (rob, Alu), Some((rob + 1, Alu)))
        rob += 2
        assert(dut.sink.logic.rob(3).toInt == 7,
          "back-pressured LS port lost its robId while the slot array compacted")
        assert(dut.sink.logic.pc(3).toLong == 7L,
          f"back-pressured LS port's cold row was overwritten: " +
          f"got pc 0x${dut.sink.logic.pc(3).toLong}%08x, expected 7")
      }

      // Release port 3: it must still deliver the ORIGINAL uop, unchanged.
      assert(dut.sink.logic.rob(3).toInt == 7 && dut.sink.logic.pc(3).toLong == 7L,
        "the stalled LS uop did not survive to its handshake intact")
    }
  }
}
