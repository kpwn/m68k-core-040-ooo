package m68k040.execute.iq

import m68k040.{M68kSim, VerilatorTest}
import m68k040.isa.{Cluster, MemOp}
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

class IqLsSpec extends AnyFunSuite {
  class Dut extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val iq     = new IssueQueuePlugin
    val source = new IqSourcePlugin
    val sink   = new IqSinkPlugin
    db.on { host.asHostOf(Seq[FiberPlugin](iq, source, sink)) }
  }

  def idle(dut: Dut): Unit = {
    val s = dut.source.logic
    s.pushValid #= false; s.slot1Valid #= false; s.flush #= false
    for (slot <- Seq(s.s0, s.s1)) {
      slot.robId #= 0; slot.cluster #= Cluster.INT; slot.memOp #= MemOp.NONE
      slot.pdst #= 0; slot.pdstValid #= false
      slot.psrcA #= 0; slot.psrcAValid #= false
      slot.psrcB #= 0; slot.psrcBValid #= false
      slot.useImm #= false
      slot.readsNzvc #= false; slot.writesNzvc #= false
      slot.pNzvcSrc #= 0; slot.pNzvcDst #= 0
      slot.readsX #= false; slot.writesX #= false
      slot.pXSrc #= 0; slot.pXDst #= 0
    }
    dut.sink.logic.ready0 #= true
    dut.sink.logic.ready1 #= true
    dut.sink.logic.ready3 #= true
    dut.source.logic.lsWakeupValid #= false
    dut.source.logic.lsWakeupPdst #= 0
  }

  // push a single uop into slot0 (slot1 invalid)
  def pushOne(dut: Dut, robId: Int, cluster: SpinalEnumElement[Cluster.type], memOp: SpinalEnumElement[MemOp.type],
              pdst: Int, pdstValid: Boolean, psrcA: Int, psrcAValid: Boolean): Unit = {
    val s = dut.source.logic
    s.s0.robId #= robId; s.s0.cluster #= cluster; s.s0.memOp #= memOp
    s.s0.pdst #= pdst; s.s0.pdstValid #= pdstValid
    s.s0.psrcA #= psrcA; s.s0.psrcAValid #= psrcAValid
    s.s0.psrcB #= 0; s.s0.psrcBValid #= false; s.s0.useImm #= false
    s.pushValid #= true; s.slot1Valid #= false
  }

  test("ALU dependent on an LS load issues only after lsWakeup", VerilatorTest) {
    M68kSim().withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      idle(dut); cd.waitSampling(3)

      // push LS LOAD (robId 1) producing pdst=7
      pushOne(dut, robId = 1, Cluster.LS, MemOp.LOAD, pdst = 7, pdstValid = true, psrcA = 3, psrcAValid = true)
      cd.waitSamplingWhere(dut.source.logic.pushReady.toBoolean)
      // push dependent ALU (robId 2) reading pdst=7
      pushOne(dut, robId = 2, Cluster.INT, MemOp.NONE, pdst = 8, pdstValid = true, psrcA = 7, psrcAValid = true)
      cd.waitSamplingWhere(dut.source.logic.pushReady.toBoolean)
      dut.source.logic.pushValid #= false
      cd.waitSampling(2)

      // The LS load issues on port 3 (consumed). The dependent ALU must NOT issue
      // on port0/1 until lsWakeup(pdst=7).
      var aluIssuedEarly = false
      for (_ <- 0 until 6) {
        if ((dut.sink.logic.v0.toBoolean && dut.sink.logic.rob0.toInt == 2) ||
            (dut.sink.logic.v1.toBoolean && dut.sink.logic.rob1.toInt == 2)) aluIssuedEarly = true
        cd.waitSampling()
      }
      assert(!aluIssuedEarly, "dependent ALU must NOT issue before lsWakeup")

      // broadcast lsWakeup for pdst 7
      dut.source.logic.lsWakeupValid #= true
      dut.source.logic.lsWakeupPdst #= 7
      cd.waitSampling()
      dut.source.logic.lsWakeupValid #= false

      var aluIssued = false
      for (_ <- 0 until 6) {
        if ((dut.sink.logic.v0.toBoolean && dut.sink.logic.rob0.toInt == 2) ||
            (dut.sink.logic.v1.toBoolean && dut.sink.logic.rob1.toInt == 2)) aluIssued = true
        cd.waitSampling()
      }
      assert(aluIssued, "dependent ALU must issue after lsWakeup")
    }
  }

  test("two LS loads both reach issue port 3 (one at a time)", VerilatorTest) {
    M68kSim().withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      idle(dut); cd.waitSampling(3)

      // hold port3 not-ready so we can observe both reach it
      dut.sink.logic.ready3 #= false

      pushOne(dut, robId = 10, Cluster.LS, MemOp.LOAD, pdst = 1, pdstValid = true, psrcA = 0, psrcAValid = false)
      cd.waitSamplingWhere(dut.source.logic.pushReady.toBoolean)
      pushOne(dut, robId = 11, Cluster.LS, MemOp.STORE, pdst = 0, pdstValid = false, psrcA = 0, psrcAValid = false)
      cd.waitSamplingWhere(dut.source.logic.pushReady.toBoolean)
      dut.source.logic.pushValid #= false
      cd.waitSampling(2)

      // port3 sees the oldest LS (robId 10) presented
      assert(dut.sink.logic.v3.toBoolean && dut.sink.logic.rob3.toInt == 10, s"oldest LS on port3, saw v=${dut.sink.logic.v3.toBoolean} rob=${dut.sink.logic.rob3.toInt}")
      // accept it
      dut.sink.logic.ready3 #= true
      cd.waitSampling()
      dut.sink.logic.ready3 #= false
      cd.waitSampling(2)
      // now the younger LS (robId 11) is presented
      assert(dut.sink.logic.v3.toBoolean && dut.sink.logic.rob3.toInt == 11, s"younger LS on port3, saw v=${dut.sink.logic.v3.toBoolean} rob=${dut.sink.logic.rob3.toInt}")
      cd.waitSampling(2)
    }
  }
}
