package m68k040.dispatch

import m68k040.{M68kParams, M68kSim, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.rob.{RobPlugin, RenameUopSourcePlugin, RenameCommitSinkPlugin}
import m68k040.rename.RenamedUop
import m68k040.decode.DecOp
import m68k040.isa.{Cluster, Size}
import m68k040.execute.iq.{IssueQueuePlugin, IqSinkPlugin, IssueQueueService}
import m68k040.ls.{MemoryOrderPlugin, MemoryOrderService, MemoryOrderTicket}
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** Unit test for DispatchPlugin: rename → ROB-alloc + IQ-push with combined
  * back-pressure. Verifies (1) a renamed µop fans to BOTH the ROB and IQ with a
  * matching robId, (2) 2-wide dispatch gets robIds 0 and 1, (3) ren.uops.ready
  * deasserts when EITHER the IQ or the ROB is full. */
class DispatchSpec extends AnyFunSuite {

  /** Ties off the IQ flush port (no flush exercised in this unit test). */
  class IqFlushTiePlugin extends FiberPlugin {
    val logic = during build new Area {
      val iq = host[IssueQueueService]
      iq.flushPort := False
      iq.aluFastAcceptNext.foreach(_ := True)
    }
  }

  class OrderDriverPlugin extends FiberPlugin {
    val logic = during build new Area {
      val order = host[MemoryOrderService].memoryOrder
      val flush = in Bool()
      val release = Vec.fill(2)(slave(Flow(MemoryOrderTicket(2))))
      order.flush := flush
      for (n <- 0 until 2) order.release(n) << release(n)
      order.address.valid := False
      order.address.payload.assignFromBits(B(0, order.address.payload.getBitsWidth bits))
      order.dataReady.valid := False
      order.dataReady.payload.assignFromBits(B(0, order.dataReady.payload.getBitsWidth bits))
      for (c <- order.commit) {
        c.valid := False; c.payload.assignFromBits(B(0, c.payload.getBitsWidth bits))
      }
      order.irreversible.valid := False
      order.irreversible.payload.assignFromBits(B(0, order.irreversible.payload.getBitsWidth bits))
      order.query.valid := False
      order.query.payload.assignFromBits(B(0, order.query.payload.getBitsWidth bits))
      order.atCommit := False
      val fire = out Bool(); fire := order.reserve.fire
      val robFire = out Bool(); robFire := host[m68k040.services.RobAllocService].allocFire
      val iqFire = out Bool(); iqFire := host[IssueQueueService].push.fire
      val robReady = out Bool(); robReady := host[m68k040.services.RobAllocService].allocReady
      val iqReady = out Bool(); iqReady := host[IssueQueueService].push.ready
      val second = out Bool(); second := order.reserveSecond
      val ids = out(Vec(UInt(m68k040.Global.ROB_ID_W_DEFAULT bits), 2))
      val stores = out Bits(2 bits)
      val tickets = out(Vec(MemoryOrderTicket(2), 2)); tickets := order.tickets
      for (n <- 0 until 2) {
        ids(n) := order.reserve.payload(n).robId
        stores(n) := order.reserve.payload(n).store
      }
      val occupied = out Bits(4 bits); occupied := order.occupied
      val canceled = out Bits(4 bits); canceled := order.canceled
    }
  }

  class Dut(withMemoryOrder: Boolean = false) extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val rsrc = new RenameUopSourcePlugin
    val rob  = new RobPlugin
    val disp = new DispatchPlugin
    val iq   = new IssueQueuePlugin
    val sink = new IqSinkPlugin
    val ftie = new IqFlushTiePlugin
    val csink = new RenameCommitSinkPlugin
    val memoryOrder = if (withMemoryOrder) Some(new MemoryOrderPlugin(4)) else None
    val orderDriver = if (withMemoryOrder) Some(new OrderDriverPlugin) else None
    db.on { host.asHostOf(Seq[FiberPlugin](
      new ParamPlugin(M68kParams()), rsrc, rob, disp, iq, sink, ftie, csink) ++
      memoryOrder.toSeq ++ orderDriver.toSeq) }
  }

  /** Poke a RenamedUop slot. dst written to an int physreg (so it has a real op). */
  def pokeRu(
      u: RenamedUop,
      valid: Boolean = true,
      pc: Long = 0,
      dstArch: Int = 0,
      pdst: Int = 0, pdstValid: Boolean = true, pdstOld: Int = 0
  ): Unit = {
    u.valid #= valid
    u.pc #= pc; u.lenWords #= 1; u.faultUsesNextPc #= false
    u.op #= DecOp.MOVE
    u.cluster #= Cluster.INT
    u.size #= Size.LONG
    u.useImm #= true; u.imm #= 0
    u.isBranch #= false; u.cond #= 0
    u.unimplemented #= false
    u.faulted #= false; u.faultVector #= 0; u.isRte #= false
    u.debugBreakValid #= false; u.debugBreakSlot #= 0
    u.dstArch #= dstArch
    u.psrcA #= 0; u.psrcAValid #= false
    u.psrcB #= 0; u.psrcBValid #= false
    u.pdst #= pdst; u.pdstValid #= pdstValid; u.pdstOld #= pdstOld
    u.pNzvcSrc #= 0; u.readsNzvc #= false
    u.pNzvcDst #= 0; u.writesNzvc #= false; u.pNzvcOld #= 0
    u.pXSrc #= 0; u.readsX #= false
    u.pXDst #= 0; u.writesX #= false; u.pXOld #= 0
    // RenameUopSourcePlugin makes EVERY RenamedUop field a top-level INPUT (src.payload
    // foreach in()), so any field NOT poked here is an undriven input -> randomized per
    // sim seed. Many fields were added to RenamedUop after this stub was written; a
    // randomized memOp/branch/cond-trap/LEA/sysOp etc. can change whether the IQ issues
    // the µop, which made "issued robIds" flakily empty. Drive ALL of them to inert
    // defaults (a plain INT-cluster ALU MOVE that issues immediately).
    u.memOp #= m68k040.isa.MemOp.NONE
    u.psrcC #= 0; u.psrcCValid #= false
    u.ibranch #= false; u.anInc #= 0; u.stkPush #= false; u.ccrRestore #= false
    u.isScc #= false; u.isDbcc #= false; u.isCondTrap #= false
    u.divSigned #= false; u.div64 #= false; u.divIsRem #= false; u.isChk2 #= false
    u.sswInstr #= false
    u.eaAuto #= m68k040.decode.EaAuto.NONE; u.eaDelta #= 0
    u.toCcr #= false; u.shiftOp #= 0; u.shiftDir #= false; u.bcdSub #= false; u.bitOp #= 0
    u.bfOp #= 0; u.bfDynamic #= false; u.bfMem #= false; u.bfStoreForm #= 0
    u.extByte #= false; u.isMovea #= false
    u.indexLong #= false; u.indexScale #= 0
    u.leaAddr #= false; u.fromCcr #= false; u.fromSr #= false; u.needsSupervisor #= false
    u.keepCommit #= false
    u.sysOp #= false; u.sysKind #= m68k040.decode.SysKind.NONE; u.sysReadDir #= false
    u.firstOfInstr #= true
    u.phtValid #= false; u.phtIndex #= 0
    u.predTaken #= false; u.predTarget #= 0
  }

  def init(dut: Dut, cd: ClockDomain): Unit = {
    dut.rsrc.logic.src.valid #= false
    dut.rsrc.logic.u1v #= false
    dut.rob.logic.completion(0).valid #= false
    dut.rob.logic.completion(1).valid #= false
    dut.rob.logic.flush.valid #= false
    dut.sink.logic.ready0 #= true
    dut.sink.logic.ready1 #= true
    dut.sink.logic.ready3 #= true
    dut.orderDriver.foreach { driver =>
      driver.logic.flush #= false
      driver.logic.release.foreach { r =>
        r.valid #= false; r.payload.slot #= 0; r.payload.generation #= 0
      }
    }
    cd.waitSampling()
  }

  /** Fork an issue-collector: records (robId) for every IQ issue-port fire,
    * in cycle order. The sink keeps issue ready high. */
  def forkIssueCollector(dut: Dut, cd: ClockDomain): scala.collection.mutable.ArrayBuffer[Int] = {
    val issued = scala.collection.mutable.ArrayBuffer[Int]()
    fork {
      while (true) {
        cd.waitSampling()
        if (dut.sink.logic.v0.toBoolean) issued += dut.sink.logic.rob0.toInt
        if (dut.sink.logic.v1.toBoolean) issued += dut.sink.logic.rob1.toInt
      }
    }
    issued
  }

  // ─────────────────────────────────────────────────────────────────────────────
  test("memory reservations share the ROB/IQ transaction and compact lane one", VerilatorTest) {
    M68kSim().withVerilator.compile(new Dut(withMemoryOrder = true)).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      init(dut, cd); cd.waitSampling(5); sleep(1)
      val order = dut.orderDriver.get.logic
      type Ticket = (Int, BigInt)
      def edge(): Unit = { cd.waitSampling(); sleep(1) }
      def packet(a: m68k040.isa.MemOp.E, b: m68k040.isa.MemOp.E,
                 second: Boolean = true): Unit = {
        for ((op, n) <- Seq(a, b).zipWithIndex) {
          val u = dut.rsrc.logic.src.payload(n)
          pokeRu(u, pdstValid = false)
          u.cluster #= (if(op == m68k040.isa.MemOp.NONE) Cluster.INT else Cluster.LS)
          u.memOp #= op
        }
        dut.rsrc.logic.u1v #= second
        dut.rsrc.logic.src.valid #= true
        sleep(1)
      }
      def push(expectedMemoryIds: Seq[Int], expectedStores: Int): Seq[Ticket] = {
        var wait = 0
        while (!dut.rsrc.logic.src.ready.toBoolean && wait < 30) { edge(); wait += 1 }
        assert(dut.rsrc.logic.src.ready.toBoolean, "unexpected dispatch backpressure")
        assert(order.fire.toBoolean == expectedMemoryIds.nonEmpty)
        assert(order.robFire.toBoolean)
        assert(order.iqFire.toBoolean)
        val ts = expectedMemoryIds.indices.map { n =>
          assert(order.ids(n).toInt == expectedMemoryIds(n))
          (order.tickets(n).slot.toInt, order.tickets(n).generation.toBigInt)
        }
        if(expectedMemoryIds.nonEmpty) {
          assert(order.second.toBoolean == (expectedMemoryIds.size == 2))
          assert((order.stores.toInt & ((1 << expectedMemoryIds.size) - 1)) == expectedStores)
        }
        edge(); dut.rsrc.logic.src.valid #= false; sleep(1)
        ts
      }
      import m68k040.isa.MemOp.{NONE, LOAD, STORE}
      packet(NONE, STORE)
      val first = push(Seq(1), 1).head // Lane 1 memory retains ROB ID 1, not 0.
      packet(LOAD, STORE)
      val pair = push(Seq(2, 3), 2)
      packet(STORE, LOAD, second = false)
      val fourth = push(Seq(4), 1).head // Invalid lane 1 must not reserve.
      assert(order.occupied.toInt == 15)

      // A full memory table must block ROB and IQ together, not consume one side.
      packet(LOAD, STORE)
      val tail = dut.rob.logic.tail.toInt
      for (_ <- 0 until 8) {
        assert(!dut.rsrc.logic.src.ready.toBoolean && !order.fire.toBoolean)
        assert(!order.robFire.toBoolean && !order.iqFire.toBoolean)
        edge(); assert(dut.rob.logic.tail.toInt == tail)
      }
      packet(NONE, NONE)
      push(Seq.empty, 0) // Non-memory pair does not need table space.
      assert(order.occupied.toInt == 15)

      // Even non-memory dispatch is canceled on the flush edge.
      packet(NONE, NONE)
      order.flush #= true; sleep(1)
      assert(!dut.rsrc.logic.src.ready.toBoolean && !order.fire.toBoolean)
      assert(!order.robFire.toBoolean && !order.iqFire.toBoolean)
      edge(); dut.rsrc.logic.src.valid #= false; order.flush #= false; sleep(1)
      assert(order.canceled.toInt == 15)
      assert(order.occupied.toInt == 15, "flush cannot reuse undrained reservations")

      // Release proves the client has drained old responses; stale releases must
      // not release a subsequently reused ticket.
      def release(t: Ticket): Unit = {
        order.release(0).payload.slot #= t._1
        order.release(0).payload.generation #= t._2
        order.release(0).valid #= true; edge(); order.release(0).valid #= false; sleep(1)
      }
      release(first)
      packet(LOAD, STORE)
      for (_ <- 0 until 3) {
        assert(!dut.rsrc.logic.src.ready.toBoolean && !order.fire.toBoolean,
          "one free record cannot accept a two-memory pair")
        edge(); assert(order.occupied.toInt == (15 & ~(1 << first._1)))
      }
      packet(NONE, LOAD)
      val replacement = push(Seq(tail + 3), 0).head
      assert(replacement._1 == first._1 && replacement._2 != first._2)
      release(first)
      assert(order.occupied.toInt == 15)
      (pair ++ Seq(fourth, replacement)).foreach(release)
      assert(order.occupied.toInt == 0)
      dut.rsrc.logic.src.valid #= false
      for (_ <- 0 until 4) { edge(); assert(order.occupied.toInt == 0) }

      // Test the other two sides of the atomic handshake with the memory
      // table EMPTY: neither an IQ stall nor ROB exhaustion may leak a ticket.
      dut.sink.logic.ready0 #= false; dut.sink.logic.ready1 #= false
      packet(NONE, NONE)
      for (_ <- 0 until 40) edge()
      assert(!order.iqReady.toBoolean && order.robReady.toBoolean)
      packet(LOAD, STORE)
      for (_ <- 0 until 6) {
        assert(!order.fire.toBoolean && !order.robFire.toBoolean && !order.iqFire.toBoolean)
        edge(); assert(order.occupied.toInt == 0)
      }
      dut.rsrc.logic.src.valid #= false
      dut.sink.logic.ready0 #= true; dut.sink.logic.ready1 #= true
      for (_ <- 0 until 30) edge()
      packet(NONE, NONE)
      for (_ <- 0 until 100) edge()
      assert(!order.robReady.toBoolean && order.iqReady.toBoolean)
      packet(STORE, LOAD)
      for (_ <- 0 until 6) {
        assert(!order.fire.toBoolean && !order.robFire.toBoolean && !order.iqFire.toBoolean)
        edge(); assert(order.occupied.toInt == 0)
      }
      dut.rsrc.logic.src.valid #= false
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  test("dispatch fans one µop to ROB + IQ with matching robId", VerilatorTest) {
    M68kSim().withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      init(dut, cd)
      val issued = forkIssueCollector(dut, cd)

      // Push one renamed µop (slot0 only). robId should be 0 (first alloc).
      pokeRu(dut.rsrc.logic.src.payload(0), pc = 0x100, dstArch = 3, pdst = 20, pdstOld = 3)
      dut.rsrc.logic.src.valid #= true
      dut.rsrc.logic.u1v #= false
      cd.waitSamplingWhere(dut.rsrc.logic.src.ready.toBoolean)
      dut.rsrc.logic.src.valid #= false

      // ROB tail must advance to 1.
      cd.waitSampling()
      assert(dut.rob.logic.tail.toInt == 1, s"ROB tail after 1 alloc = ${dut.rob.logic.tail.toInt}")
      assert(dut.rob.logic.count.toInt == 1, s"ROB count = ${dut.rob.logic.count.toInt}")

      // Let it issue + drain, then push a SECOND µop -> robId 1, tail -> 2.
      cd.waitSampling(4)
      pokeRu(dut.rsrc.logic.src.payload(0), pc = 0x102, dstArch = 4, pdst = 21, pdstOld = 4)
      dut.rsrc.logic.src.valid #= true
      cd.waitSamplingWhere(dut.rsrc.logic.src.ready.toBoolean)
      dut.rsrc.logic.src.valid #= false
      cd.waitSampling(6)
      assert(dut.rob.logic.tail.toInt == 2, s"ROB tail after 2 allocs = ${dut.rob.logic.tail.toInt}")

      // The two µops issued from the IQ in order with robIds 0 then 1.
      assert(issued.toSeq == Seq(0, 1), s"issued robIds = ${issued.toSeq}, expected (0,1)")
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  test("2-wide dispatch: robIds 0 and 1, tail += 2", VerilatorTest) {
    M68kSim().withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      init(dut, cd)

      val issued = forkIssueCollector(dut, cd)
      pokeRu(dut.rsrc.logic.src.payload(0), pc = 0x100, dstArch = 1, pdst = 20, pdstOld = 1)
      pokeRu(dut.rsrc.logic.src.payload(1), pc = 0x102, dstArch = 2, pdst = 21, pdstOld = 2)
      dut.rsrc.logic.src.valid #= true
      dut.rsrc.logic.u1v #= true
      cd.waitSamplingWhere(dut.rsrc.logic.src.ready.toBoolean)
      dut.rsrc.logic.src.valid #= false
      dut.rsrc.logic.u1v #= false

      cd.waitSampling()
      assert(dut.rob.logic.tail.toInt == 2, s"ROB tail after 2-wide alloc = ${dut.rob.logic.tail.toInt}")
      assert(dut.rob.logic.count.toInt == 2, s"ROB count = ${dut.rob.logic.count.toInt}")

      // Both must issue from the IQ (two age-ordered ports) with robIds 0 and 1.
      cd.waitSampling(6)
      assert(issued.toSet == Set(0, 1), s"2-wide issued robIds = ${issued.toSeq}, expected {0,1}")
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  test("combined back-pressure: IQ full OR ROB full deasserts ren.uops.ready", VerilatorTest) {
    M68kSim().withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      init(dut, cd)

      // --- IQ full: hold the sink not-ready so nothing issues; keep pushing.
      dut.sink.logic.ready0 #= false
      dut.sink.logic.ready1 #= false
      var pushed = 0
      var cyc = 0
      while (pushed < 16 && cyc < 200) {
        pokeRu(dut.rsrc.logic.src.payload(0), pc = pushed * 2, dstArch = pushed % 8, pdst = 20 + (pushed % 8), pdstOld = pushed % 8)
        dut.rsrc.logic.src.valid #= true
        dut.rsrc.logic.u1v #= false
        cd.waitSampling()
        if (dut.rsrc.logic.src.ready.toBoolean) pushed += 1
        cyc += 1
      }
      dut.rsrc.logic.src.valid #= false
      cd.waitSampling()
      // IQ is now full (slots can't drain, sink not ready) -> push.ready low ->
      // ren.uops.ready must be low even though the ROB still has room.
      assert(dut.rob.logic.count.toInt <= 16, s"ROB count = ${dut.rob.logic.count.toInt} (room remains)")
      dut.rsrc.logic.src.valid #= true
      sleep(1)
      assert(!dut.rsrc.logic.src.ready.toBoolean, "ren.uops.ready must deassert when IQ is full (ROB has room)")
      dut.rsrc.logic.src.valid #= false

      // Drain the IQ by re-enabling the sink, then re-init for the ROB-full case.
      dut.sink.logic.ready0 #= true
      dut.sink.logic.ready1 #= true
      for (_ <- 0 until 40) cd.waitSampling()

      // --- ROB full: complete nothing, never let entries retire, keep pushing
      // single-wide until the ROB fills (count reaches depth-1). The IQ drains
      // freely (sink ready), so the ONLY back-pressure source is the ROB.
      val robLimit = dut.rob.logic.depth - 1
      var allocs = 0
      cyc = 0
      while (dut.rob.logic.count.toInt < robLimit && cyc < 400) {
        pokeRu(dut.rsrc.logic.src.payload(0), pc = allocs * 2, dstArch = allocs % 8, pdst = 20 + (allocs % 8), pdstOld = allocs % 8)
        dut.rsrc.logic.src.valid #= true
        dut.rsrc.logic.u1v #= false
        cd.waitSampling()
        if (dut.rsrc.logic.src.ready.toBoolean) allocs += 1
        cyc += 1
      }
      dut.rsrc.logic.src.valid #= false
      cd.waitSampling()
      assert(dut.rob.logic.count.toInt >= robLimit, s"ROB did not fill: count=${dut.rob.logic.count.toInt}")
      // ROB allocReady = count <= depth-2 -> at count=depth-1 it's low; ensure
      // we are at the boundary where another 2-wide push is refused.
      dut.rsrc.logic.src.valid #= true
      dut.rsrc.logic.u1v #= true
      sleep(1)
      assert(!dut.rsrc.logic.src.ready.toBoolean, "ren.uops.ready must deassert when ROB is full")
      dut.rsrc.logic.src.valid #= false
    }
  }
}
