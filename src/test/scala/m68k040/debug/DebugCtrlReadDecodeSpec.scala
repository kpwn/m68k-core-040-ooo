package m68k040.debug

import m68k040.M68kSim
import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._

/** The registered word decode must move only the address comparison, not the
  * sampling of live values, response timing, or reset ownership. */
class DebugCtrlReadDecodeSpec extends AnyFunSuite {
  test("capture the address at AR but sample live data at doRead and hold it under backpressure") {
    M68kSim().compile(new DebugCtrlDut(stageArg = 5, withCommitStubArg = true)).doSim { dut =>
      SimTimeout(20000)
      val cd = dut.clockDomain
      val b = dut.axi
      cd.forkStimulus(10)
      DbgAxiDriver.idle(b)
      dut.dbg.logic.initDoneSeen #= false
      cd.waitSampling(20)

      for (i <- 0 until 8) {
        val atAddress = 0x11110000L + i * 4
        val atRead = 0x22220000L + i * 4
        val afterRead = 0x44440000L + i * 4
        dut.commitStub.logic.livePcDrive #= atAddress
        b.rready #= false
        b.araddr #= DebugRegMap.OFF_PC
        b.arvalid #= true
        cd.waitSamplingWhere(b.arready.toBoolean)
        b.arvalid #= false
        b.araddr #= DebugRegMap.OFF_BUILD_ID
        sleep(1)
        assert(dut.dbg.logic.csr.doRead.toBoolean, "stage 1 must follow the accepted AR")
        assert(!b.rvalid.toBoolean)
        dut.commitStub.logic.livePcDrive #= atRead
        cd.waitSampling()
        sleep(1)
        assert(!b.rvalid.toBoolean, "response must not move ahead of stage 2")
        dut.commitStub.logic.livePcDrive #= afterRead
        cd.waitSampling()
        sleep(1)
        assert(b.rvalid.toBoolean, "response latency changed")
        assert(b.rdata.toLong == atRead, "live data sampled on the wrong edge or wrong word selected")

        // A new request is deliberately held valid while the old response stalls.
        // It must not overwrite that response's decoded address or sampled value.
        b.araddr #= DebugRegMap.OFF_VERSION
        b.arvalid #= true
        for (_ <- 0 until 5) {
          cd.waitSampling()
          assert(!b.arready.toBoolean)
          assert(b.rvalid.toBoolean && b.rresp.toInt == 0)
          assert(b.rdata.toLong == atRead)
        }
        b.rready #= true
        cd.waitSamplingWhere(b.arready.toBoolean)
        b.arvalid #= false
        b.araddr #= DebugRegMap.OFF_PC
        cd.waitSamplingWhere(b.rvalid.toBoolean)
        assert(b.rdata.toBigInt == DebugRegMap.VERSION_VALUE)
        cd.waitSampling()
      }

      // Full-address comparison matters: low-byte and upper-address aliases must
      // clear all old selects, including after a previously nonzero response.
      for (offset <- Seq(1L, 2L, 3L, 0x80000L, 0x80004L, 0xfffffL, 0x00ffcL)) {
        assert(DbgAxiDriver.read(b, cd, DebugRegMap.OFF_BUILD_ID) == 0x12345678L)
        assert(DbgAxiDriver.read(b, cd, offset) == 0L, f"unexpected CSR alias at 0x$offset%05x")
      }
    }
  }

  test("a decoded read survives CPU reset before data sampling and while response is stalled") {
    M68kSim().compile(new DebugCtrlDut()).doSim { dut =>
      SimTimeout(5000)
      val cd = dut.clockDomain
      val b = dut.axi
      cd.forkStimulus(10)
      DbgAxiDriver.idle(b)
      dut.dbg.logic.initDoneSeen #= false
      cd.waitSampling(20)
      b.rready #= false
      b.araddr #= DebugRegMap.OFF_BUILD_ID
      b.arvalid #= true
      cd.waitSamplingWhere(b.arready.toBoolean)
      b.arvalid #= false
      b.araddr #= DebugRegMap.OFF_VERSION
      sleep(1)
      assert(dut.dbg.logic.csr.doRead.toBoolean)
      cd.assertReset()
      // waitSampling deliberately cannot be used while the CPU clock domain is
      // reset: the independent debug domain must continue processing the request.
      sleep(60)
      assert(b.rvalid.toBoolean && b.rresp.toInt == 0)
      assert(b.rdata.toLong == 0x12345678L)
      assert(!b.arready.toBoolean)
      cd.deassertReset()
      cd.waitSampling(3)
      assert(b.rvalid.toBoolean && b.rdata.toLong == 0x12345678L)
      b.rready #= true
      cd.waitSampling()
      assert(DbgAxiDriver.read(b, cd, DebugRegMap.OFF_VERSION) == DebugRegMap.VERSION_VALUE.toLong)
    }
  }
}
