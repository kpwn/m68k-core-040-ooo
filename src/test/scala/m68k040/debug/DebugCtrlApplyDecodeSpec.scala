package m68k040.debug

import m68k040.M68kSim
import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._

/** Exercise the externally visible write schedule independently of how the
  * architectural-apply address is decoded. The standalone host deliberately
  * lacks apply capability, so START rejects and CLEAR clears that evidence. */
class DebugCtrlApplyDecodeSpec extends AnyFunSuite {
  test("apply decode follows accepted AW across independent W order and response stalls") {
    M68kSim().compile(new DebugCtrlDut(stageArg = 3)).doSim { dut =>
      SimTimeout(100000)
      val cd = dut.clockDomain
      val b = dut.axi
      val csr = dut.dbg.logic.csr
      cd.forkStimulus(10)
      DbgAxiDriver.idle(b)
      dut.dbg.logic.initDoneSeen #= false
      cd.waitSampling(20)
      var rejected = false
      val applyAddr = DebugRegMap.OFF_ARCH_APPLY.toLong

      def aw(address: Long): Unit = {
        b.awaddr #= address
        b.awvalid #= true
        cd.waitSamplingWhere(b.awready.toBoolean)
        b.awvalid #= false
        b.awaddr #= (address ^ 0x80000L)
      }
      def w(data: Int, strobes: Int): Unit = {
        b.wdata #= data
        b.wstrb #= strobes
        b.wvalid #= true
        cd.waitSamplingWhere(b.wready.toBoolean)
        b.wvalid #= false
        b.wdata #= (data ^ 3)
        b.wstrb #= (strobes ^ 0xf)
      }
      def write(address: Long, data: Int, strobes: Int, order: Int): Unit = {
        b.bready #= false
        order match {
          case 0 =>
            aw(address)
            cd.waitSampling(3)
            assert(!csr.doWrite.toBoolean)
            w(data, strobes)
          case 1 =>
            w(data, strobes)
            cd.waitSampling(3)
            assert(!csr.doWrite.toBoolean)
            aw(address)
          case _ =>
            b.awaddr #= address; b.awvalid #= true
            b.wdata #= data; b.wstrb #= strobes; b.wvalid #= true
            cd.waitSamplingWhere(b.awready.toBoolean && b.wready.toBoolean)
            b.awvalid #= false; b.wvalid #= false
            b.awaddr #= (address ^ 0x80000L)
            b.wdata #= (data ^ 3); b.wstrb #= (strobes ^ 0xf)
        }
        sleep(1)
        assert(csr.doWrite.toBoolean, "write must apply immediately after both captures")
        assert(!b.bvalid.toBoolean, "response arrived before write application")
        assert(csr.archApplyRejected.toBoolean == rejected)
        if (address == applyAddr && (strobes & 1) != 0) {
          if ((data & 2) != 0) rejected = false
          if ((data & 1) != 0) rejected = true
        }
        cd.waitSampling()
        sleep(1)
        assert(b.bvalid.toBoolean && b.bresp.toInt == 0)
        assert(!csr.doWrite.toBoolean)
        assert(csr.archApplyRejected.toBoolean == rejected,
          f"wrong apply decode: address=0x$address%x data=$data strobes=$strobes order=$order")

        // Attempt a new command while B is blocked. It must not overwrite the
        // accepted address or replay a pulse from the outstanding transaction.
        b.awvalid #= true; b.wvalid #= true
        b.wdata #= 3; b.wstrb #= 0xf
        for (i <- 0 until 4) {
          b.awaddr #= (if ((i & 1) == 0) applyAddr else applyAddr + 1)
          cd.waitSampling()
          assert(!b.awready.toBoolean && !b.wready.toBoolean)
          assert(b.bvalid.toBoolean && !csr.doWrite.toBoolean)
          assert(csr.archApplyRejected.toBoolean == rejected)
        }
        b.awvalid #= false; b.wvalid #= false
        b.bready #= true
        cd.waitSampling(2)
      }

      for (order <- 0 until 3; strobes <- Seq(0, 1, 0xe, 0xf)) {
        write(applyAddr, 1, 0xf, order)
        for (address <- Seq(applyAddr + 1, applyAddr + 2, applyAddr + 3,
                            applyAddr ^ 0x80000L, 0xfffffL))
          write(address, 2, strobes, order)
        write(applyAddr, 2, strobes, order)
        write(applyAddr, 3, strobes, order)
        write(applyAddr, 0, strobes, order)
      }
    }
  }
}
