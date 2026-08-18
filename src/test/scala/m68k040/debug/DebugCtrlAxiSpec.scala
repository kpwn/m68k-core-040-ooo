package m68k040.debug

import m68k040.M68kSim
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** Stage 1, spec section 12: "Unit-test independent AW/W order, backpressure, byte
  * strobes, reset during idle, CPU reset during accepted transactions, and READY-low
  * during debug reset." This suite covers the pure-AXI half; the CSR-value half lives
  * in DebugCtrlCsrSpec and the reset half in DebugCtrlResetSpec. */
class DebugCtrlAxiSpec extends AnyFunSuite {

  test("READY is low while the debug power-on reset is asserted") {
    M68kSim().compile(new DebugCtrlDut(porCyclesArg = 8)).doSim { dut =>
      val b = dut.axi
      DbgAxiDriver.idle(b)
      // Present a read request from the very first cycle: an unconditionally
      // combinational arready (the legacy defect debug_reset_ctl.v's header
      // describes) would accept it and then never answer.
      b.arvalid #= true
      b.araddr  #= 0
      dut.clockDomain.forkStimulus(10)
      for (i <- 0 until 4) {
        dut.clockDomain.waitSampling()
        assert(!b.arready.toBoolean, s"arready asserted during debug reset (cycle $i)")
        assert(!b.awready.toBoolean, s"awready asserted during debug reset (cycle $i)")
        assert(!b.wready.toBoolean,  s"wready asserted during debug reset (cycle $i)")
      }
      // ... and once POR completes the very same request is served.
      var guard = 0
      while (!b.arready.toBoolean && guard < 64) { dut.clockDomain.waitSampling(); guard += 1 }
      assert(guard < 64, "arready never asserted after the debug POR window")
      dut.clockDomain.waitSamplingWhere(b.rvalid.toBoolean)
      assert(b.rresp.toInt == 0, "unmapped read must answer OKAY")
    }
  }

  test("a write with AW and W presented together completes with OKAY") {
    M68kSim().compile(new DebugCtrlDut()).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      DbgAxiDriver.idle(dut.axi)
      dut.clockDomain.waitSampling(20)
      val resp = DbgAxiDriver.write(dut.axi, dut.clockDomain, 0x00058, 0x00000018L)
      assert(resp == 0, s"BRESP must be OKAY, got $resp")
    }
  }

  test("AW may arrive well before W") {
    M68kSim().compile(new DebugCtrlDut()).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      DbgAxiDriver.idle(dut.axi)
      dut.clockDomain.waitSampling(20)
      val resp = DbgAxiDriver.writeAwFirst(dut.axi, dut.clockDomain, 0x00058, 0x00000018L, 0xF, 7)
      assert(resp == 0, s"BRESP must be OKAY, got $resp")
    }
  }

  test("W may arrive well before AW") {
    M68kSim().compile(new DebugCtrlDut()).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      DbgAxiDriver.idle(dut.axi)
      dut.clockDomain.waitSampling(20)
      val resp = DbgAxiDriver.writeWFirst(dut.axi, dut.clockDomain, 0x00058, 0x00000018L, 0xF, 7)
      assert(resp == 0, s"BRESP must be OKAY, got $resp")
    }
  }

  test("BVALID and BRESP hold stable while BREADY is low") {
    M68kSim().compile(new DebugCtrlDut()).doSim { dut =>
      val b = dut.axi
      dut.clockDomain.forkStimulus(10)
      DbgAxiDriver.idle(b)
      b.bready #= false
      dut.clockDomain.waitSampling(20)
      b.awaddr #= 0x00058; b.awvalid #= true
      b.wdata  #= 0x18;    b.wstrb   #= 0xF; b.wvalid #= true
      dut.clockDomain.waitSamplingWhere(b.awready.toBoolean && b.wready.toBoolean)
      b.awvalid #= false; b.wvalid #= false
      dut.clockDomain.waitSamplingWhere(b.bvalid.toBoolean)
      for (i <- 0 until 12) {
        dut.clockDomain.waitSampling()
        assert(b.bvalid.toBoolean, s"BVALID dropped before BREADY at cycle $i")
        assert(b.bresp.toInt == 0, s"BRESP changed while held at cycle $i")
        // A second write must NOT be accepted while the first response is outstanding.
        assert(!b.awready.toBoolean, s"awready high with an outstanding B response ($i)")
      }
      b.bready #= true
      dut.clockDomain.waitSampling()
      var guard = 0
      while (b.bvalid.toBoolean && guard < 16) { dut.clockDomain.waitSampling(); guard += 1 }
      assert(guard < 16, "BVALID never cleared after BREADY")
    }
  }

  test("RVALID and RDATA hold stable while RREADY is low") {
    M68kSim().compile(new DebugCtrlDut()).doSim { dut =>
      val b = dut.axi
      dut.clockDomain.forkStimulus(10)
      DbgAxiDriver.idle(b)
      b.rready #= false
      dut.clockDomain.waitSampling(20)
      b.araddr #= 0x00FFC; b.arvalid #= true
      dut.clockDomain.waitSamplingWhere(b.arready.toBoolean)
      b.arvalid #= false
      dut.clockDomain.waitSamplingWhere(b.rvalid.toBoolean)
      val first = b.rdata.toLong
      for (i <- 0 until 12) {
        dut.clockDomain.waitSampling()
        assert(b.rvalid.toBoolean, s"RVALID dropped before RREADY at cycle $i")
        assert(b.rdata.toLong == first, s"RDATA changed while held at cycle $i")
        assert(!b.arready.toBoolean, s"arready high with an outstanding R response ($i)")
      }
      b.rready #= true
      dut.clockDomain.waitSampling()
      var guard = 0
      while (b.rvalid.toBoolean && guard < 16) { dut.clockDomain.waitSampling(); guard += 1 }
      assert(guard < 16, "RVALID never cleared after RREADY")
    }
  }

  test("unmapped offsets read zero and drop writes, both with OKAY") {
    M68kSim().compile(new DebugCtrlDut()).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      DbgAxiDriver.idle(dut.axi)
      dut.clockDomain.waitSampling(20)
      // A hole inside the frozen namespace, a reserved-forever offset, and an
      // offset belonging to a later stage must all read 0 (spec 3.1/3.2).
      for (off <- Seq(0x00FFCL, 0x00024L, DebugRegMap.OFF_WEDGE0.toLong,
                      DebugRegMap.OFF_ARCH_D0.toLong, DebugRegMap.OFF_CAP_TRACE.toLong,
                      0x0FFFCL)) {
        assert(DbgAxiDriver.write(dut.axi, dut.clockDomain, off, 0xDEADBEEFL) == 0,
          f"write to 0x$off%05X must answer OKAY")
        val got = DbgAxiDriver.read(dut.axi, dut.clockDomain, off)
        assert(got == 0L, f"read of 0x$off%05X must be zero, got 0x$got%08X")
      }
    }
  }

  test("back-to-back transactions do not wedge the slave") {
    M68kSim().compile(new DebugCtrlDut()).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      DbgAxiDriver.idle(dut.axi)
      dut.clockDomain.waitSampling(20)
      for (i <- 0 until 24) {
        assert(DbgAxiDriver.write(dut.axi, dut.clockDomain, 0x00FF0, i.toLong) == 0)
        assert(DbgAxiDriver.read(dut.axi, dut.clockDomain, 0x00FF0) == 0L)
      }
    }
  }
}
