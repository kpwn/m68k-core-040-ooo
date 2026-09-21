package m68k040.execute.iq

import m68k040.{M68kParams, M68kSim, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.isa.{Cluster, MemOp}
import m68k040.services.LateStoreDataService
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.database.Database
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}

class EarlyStoreAddressIqSpec extends AnyFunSuite {
  class Probe extends FiberPlugin {
    val logic = during build new Area {
      val tag = in UInt(6 bits)
      val ready = out Bool()
      val pending = out Bool()
      host[LateStoreDataService].lateStoreData match {
        case Some(p) => p.queryTag := tag; ready := p.queryReady; pending := p.issuePending
        case None => ready := False; pending := False
      }
    }
  }
  class Dut(enabled: Boolean) extends Component {
    val db = new Database
    val host = db on new PluginHost
    val iq = new IssueQueuePlugin(earlyStoreAddress = enabled)
    val source = new IqSourcePlugin
    val sink = new IqSinkPlugin
    val probe = new Probe
    db.on { host.asHostOf(Seq(new ParamPlugin(M68kParams()), iq, source, sink, probe)) }
  }

  for (enabled <- Seq(false, true)) test(s"store address readiness, skid identity and flush; enabled=$enabled", VerilatorTest) {
    M68kSim().withVerilator.compile(new Dut(enabled)).doSim { d =>
      val cd = d.clockDomain; cd.forkStimulus(10)
      val s = d.source.logic
      s.pushValid #= false; s.slot1Valid #= false; s.flush #= false
      s.aluFastAccept0 #= true; s.aluFastAccept1 #= true
      s.lsWakeupValid #= false; s.lsWakeupPdst #= 0
      d.sink.logic.ready0 #= true; d.sink.logic.ready1 #= true; d.sink.logic.ready3 #= true
      d.probe.logic.tag #= 9
      for (u <- Seq(s.s0, s.s1)) {
        u.robId #= 0; u.cluster #= Cluster.INT; u.memOp #= MemOp.NONE
        u.pdst #= 0; u.pdstValid #= false
        u.psrcA #= 0; u.psrcAValid #= false; u.psrcB #= 0; u.psrcBValid #= false
        u.useImm #= false; u.isShift #= false
        u.readsNzvc #= false; u.writesNzvc #= false; u.pNzvcSrc #= 0; u.pNzvcDst #= 0
        u.readsX #= false; u.writesX #= false; u.pXSrc #= 0; u.pXDst #= 0
        u.pFpSrcA #= 0; u.psrcAFpValid #= false; u.pFpSrcB #= 0; u.psrcBFpValid #= false
        u.pFpDst #= 0; u.pFpDstValid #= false
        u.pFpccSrc #= 0; u.readsFpcc #= false; u.pFpccDst #= 0; u.writesFpcc #= false
      }
      def edge(): Unit = { cd.waitSampling(); sleep(1) }
      def push(id: Int, store: Boolean, dst: Int = 0, base: Int = -1, data: Int = -1): Unit = {
        s.s0.robId #= id; s.s0.cluster #= Cluster.LS
        s.s0.memOp #= (if(store) MemOp.STORE else MemOp.LOAD)
        s.s0.pdst #= dst; s.s0.pdstValid #= !store
        s.s0.psrcA #= math.max(base, 0); s.s0.psrcAValid #= (base >= 0)
        s.s0.psrcB #= math.max(data, 0); s.s0.psrcBValid #= (data >= 0)
        s.pushValid #= true; sleep(1)
        var waits = 0
        while(!s.pushReady.toBoolean && waits < 30) { edge(); waits += 1 }
        assert(s.pushReady.toBoolean); edge(); s.pushValid #= false; sleep(1)
      }
      def wake(tag: Int): Unit = {
        s.lsWakeupPdst #= tag; s.lsWakeupValid #= true
        edge(); s.lsWakeupValid #= false; sleep(1)
      }
      cd.waitSampling(5); sleep(1)
      push(1, store = false, dst = 7)
      push(2, store = false, dst = 9)
      push(3, store = true, base = 7, data = 9)
      for (_ <- 0 until 8) {
        assert(!(d.sink.logic.v3.toBoolean && d.sink.logic.rob3.toInt == 3),
          "address source must be ready even in early-store mode")
        edge()
      }
      d.sink.logic.ready3 #= false
      wake(7)
      for (_ <- 0 until 5) edge()
      d.sink.logic.ready3 #= true; edge(); d.sink.logic.ready3 #= false; sleep(1)
      if(enabled) {
        assert(d.sink.logic.v3.toBoolean && d.sink.logic.rob3.toInt == 3)
        assert(d.probe.logic.pending.toBoolean && !d.probe.logic.ready.toBoolean)
      } else assert(!d.sink.logic.v3.toBoolean)
      wake(9)
      if(enabled) assert(d.probe.logic.ready.toBoolean)
      else {
        for (_ <- 0 until 4) edge()
        d.sink.logic.ready3 #= true; edge(); d.sink.logic.ready3 #= false; sleep(1)
      }
      assert(d.sink.logic.v3.toBoolean && d.sink.logic.rob3.toInt == 3)
      assert(d.probe.logic.pending.toBoolean == enabled)
      // A younger ready load can fill the skid, but cannot corrupt the held
      // store's sideband while new dispatch compacts the IQ underneath it.
      push(4, store = false, dst = 11)
      for (_ <- 0 until 6) {
        assert(d.sink.logic.rob3.toInt == 3 && d.probe.logic.pending.toBoolean == enabled)
        edge()
      }
      s.flush #= true; edge(); s.flush #= false; sleep(1)
      assert(!d.sink.logic.v3.toBoolean)
      d.sink.logic.ready3 #= true
      push(3, store = true, base = 7, data = 9)
      var seen = false
      for (_ <- 0 until 12) {
        if(d.sink.logic.v3.toBoolean && d.sink.logic.rob3.toInt == 3) {
          seen = true
          assert(!d.probe.logic.pending.toBoolean, "flush must clear old pending-data identity")
        }
        edge()
      }
      assert(seen)
    }
  }
}
