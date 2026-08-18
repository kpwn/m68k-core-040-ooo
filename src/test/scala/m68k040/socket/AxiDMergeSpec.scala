package m68k040.socket

import m68k040.M68kSim
import m68k040.cache.AxiIds
import m68k040.sim.{AxiMemModel, AxiMemModelConfig}
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import org.scalatest.funsuite.AnyFunSuite

/** Spec section 13, "Merge arbiter". Covers the four section 4.4 assertions, the D9
  * independence property, the D8 identical-ID case, and D20's bounded-grant watchdog. The
  * D-cache's own three-write-issuer behaviour through the arbiter is covered by the
  * existing D-cache suites once Task 13 wires it in; here the owners are driven directly
  * so each property is isolated. */
class AxiDMergeSpec extends AnyFunSuite {

  private val cfg = Axi4Config(addressWidth = 32, dataWidth = 128, idWidth = AxiIds.ID_W,
                               useId = true, useRegion = false, useBurst = true,
                               useLock = false, useCache = false, useSize = true,
                               useQos = false, useLen = true, useLast = true,
                               useResp = true, useProt = false, useStrb = true)

  /** A short watchdog so the D20 test finishes; the real bound is 2e9 (D20). */
  class MergeDut(timeout: BigInt = 64) extends Component {
    val m = new AxiDMerge(cfg, timeout)
    // NOTE: this Bundle also declares a field literally named `out` (the merged Axi4
    // master, below) -- in Scala a template body's members are visible for name
    // resolution throughout the WHOLE body regardless of textual order, so every use of
    // the `out(...)` DIRECTION FUNCTION in this Bundle must be fully qualified as
    // `spinal.core.out` or it resolves to that member instead (whose type has no `Bool`/
    // `Bits` method). Field names/types/directions are otherwise exactly as specified.
    val io = new Bundle {
      val dc   = slave(Axi4(cfg))
      val itlb = slave(Axi4(cfg))
      val dtlb = slave(Axi4(cfg))
      val out  = master(Axi4(cfg))
      val rvArValid = in Bool ()
      val rvArAddr  = in UInt (32 bits)
      val rvArReady = spinal.core.out Bool ()
      val rvRValid  = spinal.core.out Bool ()
      val rvRData   = spinal.core.out Bits (128 bits)
      val rvRResp   = spinal.core.out Bits (2 bits)
      val rvRReady  = in Bool ()
      val wedge     = spinal.core.out Bool ()
      val wedgeIsRead = spinal.core.out Bool ()
    }
    io.dc   <> m.io.dc
    io.itlb <> m.io.itlb
    io.dtlb <> m.io.dtlb
    io.out  <> m.io.out
    m.io.rvArValid := io.rvArValid
    m.io.rvArAddr  := io.rvArAddr
    io.rvArReady   := m.io.rvArReady
    io.rvRValid    := m.io.rvRValid
    io.rvRData     := m.io.rvRData
    io.rvRResp     := m.io.rvRResp
    m.io.rvRReady  := io.rvRReady
    io.wedge       := m.io.wedge
    io.wedgeIsRead := m.io.wedgeIsRead
    io.wedge.simPublic(); io.wedgeIsRead.simPublic()
  }

  private def idleAll(dut: MergeDut): Unit = {
    for (p <- Seq(dut.io.dc, dut.io.itlb, dut.io.dtlb)) {
      p.ar.valid #= false; p.aw.valid #= false; p.w.valid #= false
      p.r.ready  #= true;  p.b.ready  #= true
      p.ar.payload.len #= 0; p.ar.payload.size #= 4; p.ar.payload.burst #= 1
      p.aw.payload.len #= 0; p.aw.payload.size #= 4; p.aw.payload.burst #= 1
      p.w.payload.last #= true; p.w.payload.strb #= BigInt("FFFF", 16)
      p.w.payload.data #= 0
    }
    dut.io.rvArValid #= false; dut.io.rvArAddr #= 0; dut.io.rvRReady #= true
  }

  /** Issue one read on `p` and return the 128-bit beat. Bounded so a wedge fails loudly. */
  private def read(dut: MergeDut, p: Axi4, addr: Long, id: Int): BigInt = {
    p.ar.payload.addr #= addr
    p.ar.payload.id   #= id
    p.ar.valid #= true
    var g = 0
    while (!(p.ar.ready.toBoolean) && g < 2000) { dut.clockDomain.waitSampling(); g += 1 }
    assert(g < 2000, f"AR never granted for id $id at 0x$addr%08X")
    dut.clockDomain.waitSampling()
    p.ar.valid #= false
    g = 0
    while (!p.r.valid.toBoolean && g < 2000) { dut.clockDomain.waitSampling(); g += 1 }
    assert(g < 2000, f"R never returned for id $id at 0x$addr%08X")
    val d = p.r.payload.data.toBigInt
    assert(p.r.payload.id.toInt == id, s"R id ${p.r.payload.id.toInt} routed to the wrong owner")
    dut.clockDomain.waitSampling()
    d
  }

  test("D8: ITLB and DTLB reads with IDENTICAL ids route to the right consumer") {
    M68kSim().compile(new MergeDut()).doSim("same-id", seed = 1) { dut =>
      dut.clockDomain.forkStimulus(10)
      idleAll(dut)
      val mem = AxiMemModel.attachFull(dut.io.out, dut.clockDomain,
        AxiMemModelConfig(checkIdUnique = false, crossbarSingleOutstanding = true))
      for (i <- 0 until 16) mem.pokeByte(0x1000 + i, 0x10 + i)
      for (i <- 0 until 16) mem.pokeByte(0x2000 + i, 0x20 + i)
      dut.clockDomain.waitSampling(4)
      // Both walkers use AxiIds.WALK_READ (== 2). Routing must NOT consult the ID.
      val a = read(dut, dut.io.itlb, 0x1000, AxiIds.WALK_READ)
      val b = read(dut, dut.io.dtlb, 0x2000, AxiIds.WALK_READ)
      assert((a & 0xff) == 0x10, f"ITLB got 0x$a%032X")
      assert((b & 0xff) == 0x20, f"DTLB got 0x$b%032X")
    }
  }

  test("section 4.4: an R beat is never presented to a non-owner") {
    M68kSim().compile(new MergeDut()).doSim("no-cross-talk", seed = 2) { dut =>
      dut.clockDomain.forkStimulus(10)
      idleAll(dut)
      val mem = AxiMemModel.attachFull(dut.io.out, dut.clockDomain,
        AxiMemModelConfig(checkIdUnique = false, crossbarSingleOutstanding = true))
      for (i <- 0 until 16) mem.pokeByte(0x3000 + i, 0xC0 + i)
      dut.clockDomain.waitSampling(4)
      // Watch every owner's r.valid for the whole of one D-cache read.
      var violations = 0
      val watcher = fork {
        while (true) {
          dut.clockDomain.waitSampling()
          val hot = Seq(dut.io.dc.r.valid.toBoolean, dut.io.itlb.r.valid.toBoolean,
                        dut.io.dtlb.r.valid.toBoolean, dut.io.rvRValid.toBoolean)
          if (hot.count(identity) > 1) violations += 1
          if (hot(1) || hot(2) || hot(3)) violations += 1   // only the D-cache asked
        }
      }
      read(dut, dut.io.dc, 0x3000, AxiIds.dRefill(0))
      dut.clockDomain.waitSampling(4)
      assert(violations == 0, s"$violations cycles presented R to a non-owner")
    }
  }

  test("D9: a read grant held with r.ready LOW does not block an unrelated write") {
    // The D9 deadlock case, directed: DcachePlugin.scala:1258 sets
    // `axi.r.ready := !refillWriteHold`, i.e. a refill deliberately refuses its R beat
    // until a colliding store drain's window closes -- and that store drain needs the
    // WRITE channel. Under a single global grant token this deadlocks.
    M68kSim().compile(new MergeDut()).doSim("independent-grants", seed = 3) { dut =>
      dut.clockDomain.forkStimulus(10)
      idleAll(dut)
      val mem = AxiMemModel.attachFull(dut.io.out, dut.clockDomain,
        AxiMemModelConfig(checkIdUnique = false, crossbarSingleOutstanding = true))
      dut.clockDomain.waitSampling(4)
      // D-cache read, with r.ready held LOW (the refillWriteHold shape).
      dut.io.dc.r.ready #= false
      dut.io.dc.ar.payload.addr #= 0x4000
      dut.io.dc.ar.payload.id   #= AxiIds.dRefill(0)
      dut.io.dc.ar.valid #= true
      dut.clockDomain.waitSamplingWhere(dut.io.dc.ar.ready.toBoolean)
      dut.clockDomain.waitSampling()
      dut.io.dc.ar.valid #= false
      // Now a walker write. It MUST complete while the read grant is still held.
      dut.io.itlb.aw.payload.addr #= 0x5000
      dut.io.itlb.aw.payload.id   #= AxiIds.WALK_WRITE
      dut.io.itlb.w.payload.data  #= BigInt("A5", 16)
      dut.io.itlb.aw.valid #= true
      dut.io.itlb.w.valid  #= true
      var g = 0
      while (!dut.io.itlb.b.valid.toBoolean && g < 500) { dut.clockDomain.waitSampling(); g += 1 }
      assert(g < 500, "walker write never completed while a read grant was held -- D9 deadlock")
      dut.io.itlb.aw.valid #= false; dut.io.itlb.w.valid #= false
      // And the read still completes once its owner accepts.
      dut.io.dc.r.ready #= true
      g = 0
      while (!dut.io.dc.r.valid.toBoolean && g < 500) { dut.clockDomain.waitSampling(); g += 1 }
      assert(g < 500, "the held read never completed after r.ready rose")
    }
  }

  test("section 4.4: arBusy does not clear without r.last, and awBusy not without b") {
    M68kSim().compile(new MergeDut()).doSim("busy-discipline", seed = 4) { dut =>
      dut.clockDomain.forkStimulus(10)
      idleAll(dut)
      AxiMemModel.attachFull(dut.io.out, dut.clockDomain,
        AxiMemModelConfig(checkIdUnique = false, crossbarSingleOutstanding = true))
      dut.clockDomain.waitSampling(4)
      // Hold r.ready low across a granted read: a second owner must NOT be granted.
      dut.io.dc.r.ready #= false
      dut.io.dc.ar.payload.addr #= 0x6000
      dut.io.dc.ar.payload.id   #= AxiIds.dRefill(0)
      dut.io.dc.ar.valid #= true
      dut.clockDomain.waitSamplingWhere(dut.io.dc.ar.ready.toBoolean)
      dut.clockDomain.waitSampling()
      dut.io.dc.ar.valid #= false
      dut.io.itlb.ar.payload.addr #= 0x7000
      dut.io.itlb.ar.payload.id   #= AxiIds.WALK_READ
      dut.io.itlb.ar.valid #= true
      for (i <- 0 until 20) {
        dut.clockDomain.waitSampling()
        assert(!dut.io.itlb.ar.ready.toBoolean,
          s"a second read owner was granted at cycle $i while the first was outstanding")
      }
      dut.io.dc.r.ready #= true
      var g = 0
      while (!dut.io.itlb.ar.ready.toBoolean && g < 200) { dut.clockDomain.waitSampling(); g += 1 }
      assert(g < 200, "the read grant never released after r.last")
    }
  }

  test("D20: a grant held with no progress trips the bounded-grant watchdog") {
    M68kSim().compile(new MergeDut(timeout = 40)).doSim("watchdog", seed = 5) { dut =>
      dut.clockDomain.forkStimulus(10)
      idleAll(dut)
      // NO memory model attached: the fabric never answers, which is precisely the
      // "structurally absent wide side" case D20 exists for. `out` is left dangling,
      // so ar.ready is never asserted.
      dut.io.out.ar.ready #= false; dut.io.out.aw.ready #= false
      dut.io.out.w.ready  #= false; dut.io.out.r.valid  #= false
      dut.io.out.b.valid  #= false
      dut.clockDomain.waitSampling(4)
      assert(!dut.io.wedge.toBoolean, "wedge asserted before any grant")
      dut.io.dc.ar.payload.addr #= 0x8000
      dut.io.dc.ar.payload.id   #= AxiIds.dRefill(0)
      dut.io.dc.ar.valid #= true
      var g = 0
      while (!dut.io.wedge.toBoolean && g < 400) { dut.clockDomain.waitSampling(); g += 1 }
      assert(g < 400, "the bounded-grant watchdog never fired")
      assert(dut.io.wedgeIsRead.toBoolean, "the wedge was reported on the wrong direction")
      // It NEVER fabricates a response (spec section 8.3).
      for (_ <- 0 until 20) {
        dut.clockDomain.waitSampling()
        assert(!dut.io.dc.r.valid.toBoolean, "the watchdog fabricated an R beat")
        assert(!dut.io.dc.b.valid.toBoolean, "the watchdog fabricated a B response")
      }
    }
  }

  test("D20's real bound is v1's value verbatim, not a re-derivation") {
    // Spec section 8.3: "inherit v1's final TIMEOUT_CYCLES value verbatim -- 2,000,000,000
    // core-clk cycles (32'h7735_9400, axi_narrow_to_wide.v:263). Do NOT re-derive it from
    // the xbar's WD_LOG2_S1 = 2^27." The v1 header records that bug being introduced twice.
    assert(AxiDMerge.V1_TIMEOUT_CYCLES == BigInt(2000000000),
      s"got ${AxiDMerge.V1_TIMEOUT_CYCLES}")
    assert(AxiDMerge.V1_TIMEOUT_CYCLES != BigInt(1) << 28,
      "2^28 is the SUPERSEDED 2026-08-02 derivation, not the value")
  }
}
