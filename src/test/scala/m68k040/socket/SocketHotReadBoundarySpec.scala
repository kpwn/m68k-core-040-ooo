package m68k040.socket

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.amba4.axi.{Axi4Config, Axi4ReadOnly}

/** Exercises the same hot-door slice, port wiring and persistent absorber as SocketTop. */
class SocketHotReadBoundarySpec extends AnyFunSuite {
  class Dut(fastAr: Boolean) extends Component {
    val clk = in Bool()
    val rst = in Bool()
    val coreCd = ClockDomain(clk, rst, config = ClockDomainConfig(
      clockEdge = RISING, resetKind = ASYNC, resetActiveLevel = HIGH))
    val axiPorCd = ClockDomain(clock = clk, config = ClockDomainConfig(
      clockEdge = RISING, resetKind = BOOT))
    axiPorCd.setSynchronousWith(coreCd)
    val core = slave(Axi4ReadOnly(Axi4Config(addressWidth = 32, dataWidth = 128, idWidth = 2)))
    val fabric = master(SocketAxiI(dataWidth = 128, idWidth = 2))
    SocketHotReadBoundary.connect(core, fabric, rst, coreCd, axiPorCd, fastAr)
  }

  private def run(fast: Boolean, label: String)(body: (Dut, ClockDomain) => Unit): Unit = {
    SimConfig.compile(new Dut(fast)).doSim(label) { dut =>
      dut.rst #= true
      dut.core.ar.valid #= false
      dut.core.r.ready #= true
      dut.fabric.arready #= true
      dut.fabric.rvalid #= false
      dut.fabric.rid #= 0
      dut.fabric.rdata #= 0
      dut.fabric.rresp #= 0
      dut.fabric.rlast #= true
      dut.coreCd.forkStimulus(10)
      dut.coreCd.waitSampling(3)
      dut.rst #= false
      dut.coreCd.waitSampling(3) // persistent absorber disarms after quiet reset
      body(dut, dut.coreCd)
    }
  }

  private def ar(dut: Dut, id: Int, addr: Long): Unit = {
    dut.core.ar.payload.id #= id
    dut.core.ar.payload.addr #= addr
    dut.core.ar.payload.len #= 0
    dut.core.ar.payload.size #= 4
    dut.core.ar.payload.burst #= 1
    dut.core.ar.valid #= true
  }

  for (fast <- Seq(false, true)) {
    test(s"hot AR ${if (fast) "S2M" else "FULL"}: empty-path timing and exact accepted payload") {
      run(fast, s"ar-empty-$fast") { (dut, cd) =>
        cd.waitFallingEdge()
        ar(dut, 2, 0x12340L)
        sleep(1)
        assert(dut.fabric.arvalid.toBoolean == fast,
          "only S2M may present the address before the next clock edge")
        if (fast) {
          assert(dut.fabric.arid.toInt == 2 && dut.fabric.araddr.toLong == 0x12340L)
          assert(dut.fabric.arlen.toInt == 0 && dut.fabric.arsize.toInt == 4)
          assert(dut.fabric.arburst.toInt == 1)
        }
        assert(dut.core.ar.ready.toBoolean)
        cd.waitSampling() // core accepts this AR
        cd.waitFallingEdge()
        dut.core.ar.valid #= false
        sleep(1)
        assert(dut.fabric.arvalid.toBoolean == !fast,
          "FULL presents the registered AR one cycle later; S2M has no duplicate")
        if (!fast) {
          assert(dut.fabric.arid.toInt == 2)
          assert(dut.fabric.araddr.toLong == 0x12340L)
          assert(dut.fabric.arlen.toInt == 0)
          assert(dut.fabric.arsize.toInt == 4)
          assert(dut.fabric.arburst.toInt == 1)
        }
        cd.waitSampling(3)
        assert(!dut.fabric.arvalid.toBoolean, "no duplicate AR after downstream accept")
      }
    }

    test(s"hot AR ${if (fast) "S2M" else "FULL"}: stalled address stable, then one acceptance") {
      run(fast, s"ar-stall-$fast") { (dut, cd) =>
        dut.fabric.arready #= false
        cd.waitFallingEdge()
        ar(dut, 1, 0x22000L)
        cd.waitSampling()
        cd.waitFallingEdge()
        dut.core.ar.valid #= false // accepted into slice's skid or M2S holding slot
        for (_ <- 0 until 4) {
          sleep(1)
          assert(dut.fabric.arvalid.toBoolean)
          assert(dut.fabric.arid.toInt == 1 && dut.fabric.araddr.toLong == 0x22000L)
          cd.waitSampling()
          cd.waitFallingEdge()
        }
        dut.fabric.arready #= true
        sleep(1)
        assert(dut.fabric.arvalid.toBoolean)
        cd.waitSampling()
        cd.waitFallingEdge()
        sleep(1)
        assert(!dut.fabric.arvalid.toBoolean, "one external handshake must drain one AR")
      }
    }

    test(s"hot AR ${if (fast) "S2M" else "FULL"}: reset drops an unaccepted held AR") {
      run(fast, s"ar-reset-unaccepted-$fast") { (dut, cd) =>
        dut.fabric.arready #= false
        cd.waitFallingEdge()
        ar(dut, 0, 0x33000L)
        cd.waitSampling() // accepted into the core-domain slice, but never on the port
        cd.waitFallingEdge()
        dut.core.ar.valid #= false
        sleep(1)
        assert(dut.fabric.arvalid.toBoolean && dut.fabric.araddr.toLong == 0x33000L)
        dut.rst #= true
        cd.waitRisingEdge() // waitSampling intentionally excludes reset-active edges
        cd.waitFallingEdge()
        dut.rst #= false
        dut.fabric.arready #= true
        cd.waitSampling(3)
        assert(!dut.fabric.arvalid.toBoolean, "reset must discard an unaccepted AR")
        cd.waitFallingEdge()
        ar(dut, 1, 0x44000L)
        cd.waitSampling()
        cd.waitFallingEdge()
        dut.core.ar.valid #= false
        sleep(1)
        if (!fast) {
          assert(dut.fabric.arvalid.toBoolean && dut.fabric.araddr.toLong == 0x44000L)
          cd.waitSampling()
        }
        cd.waitFallingEdge()
        sleep(1)
        assert(!dut.fabric.arvalid.toBoolean, "fresh AR must appear exactly once")
      }
    }

    test(s"hot AR ${if (fast) "S2M" else "FULL"}: reset absorbs two old multibeat IDs then restarts") {
      run(fast, s"ar-reset-absorber-$fast") { (dut, cd) =>
        // Two externally accepted old reads. Their final beats remain owed at reset.
        for (id <- 0 to 1) {
          cd.waitFallingEdge()
          ar(dut, id, 0x50000L + id * 0x10L)
          dut.core.ar.payload.len #= 1
          cd.waitSampling()
          cd.waitFallingEdge()
          dut.core.ar.valid #= false
          if (!fast) cd.waitSampling()
        }
        cd.waitFallingEdge()
        dut.rst #= true
        cd.waitRisingEdge()
        cd.waitFallingEdge()
        dut.rst #= false
        // The core may enqueue a fresh read while the persistent absorber still
        // owes both old RLASTs. It must remain off the external port until drain.
        ar(dut, 1, 0x60000L)
        sleep(1)
        assert(!dut.fabric.arvalid.toBoolean)
        assert(dut.core.ar.ready.toBoolean)
        cd.waitSampling()
        cd.waitFallingEdge()
        dut.core.ar.valid #= false
        sleep(1)
        assert(!dut.fabric.arvalid.toBoolean)
        for (id <- 0 to 1; last <- Seq(false, true)) {
          dut.fabric.rid #= id
          dut.fabric.rdata #= BigInt(id + 1)
          dut.fabric.rlast #= last
          dut.fabric.rvalid #= true
          sleep(1)
          assert(dut.fabric.rready.toBoolean, "absorber must accept every stale beat")
          assert(!dut.core.r.valid.toBoolean, "no pre-reset response may reach core")
          cd.waitSampling()
          cd.waitFallingEdge()
        }
        dut.fabric.rvalid #= false
        var newArAccepts = 0
        for (_ <- 0 until 4) {
          cd.waitFallingEdge()
          sleep(1)
          if (dut.fabric.arvalid.toBoolean && dut.fabric.arready.toBoolean) {
            assert(dut.fabric.arid.toInt == 1 && dut.fabric.araddr.toLong == 0x60000L)
            newArAccepts += 1
          }
          cd.waitSampling()
        }
        assert(newArAccepts == 1, s"fresh AR appeared $newArAccepts times after stale drain")
        cd.waitFallingEdge()
        dut.fabric.rid #= 1
        dut.fabric.rdata #= BigInt("00000000000000000000000011223344", 16)
        dut.fabric.rlast #= true
        dut.fabric.rvalid #= true
        sleep(1)
        assert(dut.fabric.rready.toBoolean)
        cd.waitSampling()
        cd.waitFallingEdge()
        dut.fabric.rvalid #= false
        sleep(1)
        // Hot R remains FULL in both arms; the byte-order swap is applied once.
        assert(dut.core.r.valid.toBoolean && dut.core.r.payload.id.toInt == 1)
        assert((dut.core.r.payload.data.toBigInt & BigInt("ffffffff", 16)) ==
          BigInt("44332211", 16))
      }
    }

    test(s"hot AR ${if (fast) "S2M" else "FULL"}: reset drains a port-stalled old R burst") {
      run(fast, s"ar-reset-stalled-r-$fast") { (dut, cd) =>
        cd.waitFallingEdge()
        ar(dut, 0, 0x70000L)
        dut.core.ar.payload.len #= 3 // four response beats
        cd.waitSampling()
        cd.waitFallingEdge()
        dut.core.ar.valid #= false
        if (!fast) cd.waitSampling()
        cd.waitFallingEdge()
        dut.core.r.ready #= false
        var accepted = 0
        var stalled = false
        while (accepted < 4 && !stalled) {
          dut.fabric.rvalid #= true
          dut.fabric.rid #= 0
          dut.fabric.rdata #= BigInt(0x80 + accepted)
          dut.fabric.rlast #= (accepted == 3)
          sleep(1)
          if (dut.fabric.rready.toBoolean) {
            cd.waitSampling()
            accepted += 1
            cd.waitFallingEdge()
          } else stalled = true
        }
        assert(stalled && accepted < 4, "FULL R must backpressure the fourth beat")
        val heldData = dut.fabric.rdata.toBigInt
        for (_ <- 0 until 2) {
          assert(!dut.fabric.rready.toBoolean)
          assert(dut.fabric.rvalid.toBoolean && dut.fabric.rdata.toBigInt == heldData)
          cd.waitSampling()
          cd.waitFallingEdge()
        }
        dut.rst #= true
        sleep(1)
        val resetEdgeFire = dut.fabric.rready.toBoolean
        cd.waitRisingEdge()
        if (resetEdgeFire) accepted += 1
        cd.waitFallingEdge()
        dut.rst #= false
        while (accepted < 4) {
          dut.fabric.rdata #= BigInt(0x80 + accepted)
          dut.fabric.rlast #= (accepted == 3)
          dut.fabric.rvalid #= true
          sleep(1)
          assert(dut.fabric.rready.toBoolean, "absorber must drain all remaining stale beats")
          assert(!dut.core.r.valid.toBoolean, "stale R must not reappear in reset core")
          cd.waitSampling()
          accepted += 1
          cd.waitFallingEdge()
        }
        dut.fabric.rvalid #= false
        cd.waitSampling(3)
        assert(!dut.core.r.valid.toBoolean)
        cd.waitFallingEdge()
        ar(dut, 1, 0x71000L)
        sleep(1)
        assert(if (fast) dut.fabric.arvalid.toBoolean else dut.core.ar.ready.toBoolean,
          "new AR must be admitted after stale response drains")
      }
    }
  }
}
