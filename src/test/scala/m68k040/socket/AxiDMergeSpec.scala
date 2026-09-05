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

  /** Hand-rolled `io.out` slave. The `AxiMemModel` cannot express this case: every
    * existing test above attaches it with `crossbarSingleOutstanding = true`, which
    * refuses the second AW outright and so hides the arbiter behaviour under test. Here
    * the B response is withheld under explicit testbench control instead. */
  private def idleOutSlave(dut: MergeDut): Unit = {
    dut.io.out.ar.ready #= true
    dut.io.out.aw.ready #= true
    dut.io.out.w.ready  #= true
    dut.io.out.r.valid  #= false
    dut.io.out.b.valid  #= false
    dut.io.out.b.payload.id   #= 0
    dut.io.out.b.payload.resp #= 0
  }

  /** Present one D-side write (aw + w together, the shape both `DcachePlugin` write
    * issuers use) and return once BOTH legs have handshaked with the arbiter. */
  private def dcWriteKickoff(dut: MergeDut, addr: Long, id: Int): Unit = {
    dut.io.dc.aw.payload.addr #= addr
    dut.io.dc.aw.payload.id   #= id
    dut.io.dc.w.payload.data  #= id
    dut.io.dc.aw.valid #= true
    dut.io.dc.w.valid  #= true
    var awDone = false
    var wDone  = false
    var g = 0
    while (!(awDone && wDone) && g < 200) {
      val awFire = dut.io.dc.aw.valid.toBoolean && dut.io.dc.aw.ready.toBoolean
      val wFire  = dut.io.dc.w.valid.toBoolean && dut.io.dc.w.ready.toBoolean
      dut.clockDomain.waitSampling()
      if (awFire) { awDone = true; dut.io.dc.aw.valid #= false }
      if (wFire)  { wDone  = true; dut.io.dc.w.valid  #= false }
      g += 1
    }
    assert(g < 200, f"the D-side write id $id at 0x$addr%08X was never accepted by the arbiter")
    dut.io.dc.aw.valid #= false
    dut.io.dc.w.valid  #= false
  }

  /** Return one B beat on `io.out` and wait for the arbiter to consume it. Returns false
    * if the arbiter never asserts `out.b.ready` -- i.e. the response is stranded. */
  private def returnB(dut: MergeDut, id: Int, bound: Int = 200): Boolean = {
    dut.io.out.b.payload.id   #= id
    dut.io.out.b.payload.resp #= 0
    dut.io.out.b.valid #= true
    var g = 0
    while (!dut.io.out.b.ready.toBoolean && g < bound) { dut.clockDomain.waitSampling(); g += 1 }
    val taken = dut.io.out.b.ready.toBoolean
    if (taken) dut.clockDomain.waitSampling()
    dut.io.out.b.valid #= false
    taken
  }

  test("a second D-side write issued while an earlier B is outstanding still gets its B") {
    // THE p141 BOOT WEDGE (`0x40806b68`), reduced to its arbiter-level primitive.
    //
    // `DcachePlugin` has three write issuers whose mutual exclusion is expressed purely
    // over the AW/W HANDSHAKES, never over the B: `storeWantsAxi = !stAwDone || !stWDone`
    // (:1031) and `evictAxiPairOpen = !evictAwDone || !evictWDone` (:1035) both go False
    // the moment a writer's aw and w are ACCEPTED, with its B still in flight. So the
    // store-S3 kickoff (:2933) and EVICT_WR (:1811/:1820) each legitimately present a
    // SECOND AW while the first write's B is outstanding -- and `MAX_WT_OUTSTANDING = 4`
    // (:1117) makes the store path do it by design.
    //
    // `AxiDMerge.wr` assumed the opposite. It latches `busy` on GRANT, forwards any
    // `io.dc.aw.valid` for as long as `busy` holds (:279-282), and clears `busy` on the
    // FIRST `io.out.b.fire` (:315). A second write accepted under the same grant
    // therefore has its B arrive with `busy` already low, where
    // `io.dc.b.valid := io.out.b.valid && busy && ...` (:308) never presents it and
    // `io.out.b.ready` (:311) never accepts it. The response is lost in BOTH directions:
    // the issuer waits for an ack that can never come, and the fabric holds BVALID
    // forever. No watchdog covers it -- `wr.wedge` is gated on `busy`, which is low.
    //
    // On the board that is EVICT_WR waiting for its `id == D_PUSH` B (:1828) while
    // `busy` stays high forever, which is the measured p141 stall word exactly: of the
    // 17 `dcIdleForMaint` terms only `busy` blocks, with `storeOutstanding == 0` and all
    // four AW/W done-flags set.
    M68kSim().compile(new MergeDut()).doSim("write-grant-second-b", seed = 6) { dut =>
      dut.clockDomain.forkStimulus(10)
      idleAll(dut)
      idleOutSlave(dut)
      dut.clockDomain.waitSampling(4)

      // Write 1: the store-S3 write-through kickoff. Its aw+w are accepted; its B is
      // deliberately withheld, exactly as a real fabric round trip withholds it.
      dcWriteKickoff(dut, 0x1000, AxiIds.D_STORE)

      // Write 2: EVICT_WR's dirty-victim writeback, PRESENTED (not required to be
      // accepted) while write 1's B is still outstanding. `storeWantsAxi` is already
      // False -- write 1's aw and w are done -- so the RTL genuinely presents it here.
      // Whether the arbiter admits it now or back-pressures it until write 1's B lands
      // is its own choice; what is NOT negotiable is that whichever it does, write 2
      // still receives its own B.
      dut.io.dc.aw.payload.addr #= 0x2000
      dut.io.dc.aw.payload.id   #= AxiIds.D_PUSH
      dut.io.dc.w.payload.data  #= 0xEE
      dut.io.dc.aw.valid #= true
      dut.io.dc.w.valid  #= true
      // Advance one cycle at a time, RETIRING each leg the moment it handshakes. A real
      // master drops VALID after its handshake; leaving it high would let the arbiter
      // re-forward the same AW on a later grant and mask the very drop under test.
      def stepRetiring(): Unit = {
        val awFire = dut.io.dc.aw.valid.toBoolean && dut.io.dc.aw.ready.toBoolean
        val wFire  = dut.io.dc.w.valid.toBoolean  && dut.io.dc.w.ready.toBoolean
        dut.clockDomain.waitSampling()
        if (awFire) dut.io.dc.aw.valid #= false
        if (wFire)  dut.io.dc.w.valid  #= false
      }
      for (_ <- 0 until 4) stepRetiring()

      // Write 1's B. Fine both before and after the fix. `returnB` must not let the
      // second write's legs re-fire while it waits, so retire them here too.
      dut.io.out.b.payload.id   #= AxiIds.D_STORE
      dut.io.out.b.payload.resp #= 0
      dut.io.out.b.valid #= true
      var g = 0
      while (!dut.io.out.b.ready.toBoolean && g < 200) { stepRetiring(); g += 1 }
      assert(dut.io.out.b.ready.toBoolean, "the arbiter never accepted the first B")
      stepRetiring()
      dut.io.out.b.valid #= false

      // Let write 2's aw/w complete on whatever schedule the arbiter chose.
      g = 0
      while ((dut.io.dc.aw.valid.toBoolean || dut.io.dc.w.valid.toBoolean) && g < 200) {
        stepRetiring(); g += 1
      }
      assert(g < 200, "the second write's aw/w never completed")

      // THE REGRESSION: write 2's B must be both ACCEPTED on `out.b` and PRESENTED on
      // `dc.b`. Before the fix the arbiter admitted write 2 under write 1's grant and
      // then retired that grant on write 1's B, so `out.b.ready` stays low forever and
      // `dc.b.valid` never rises -- the response is lost in both directions at once.
      dut.io.out.b.payload.id   #= AxiIds.D_PUSH
      dut.io.out.b.payload.resp #= 0
      dut.io.out.b.valid #= true
      g = 0
      var seen = false
      while (!seen && g < 200) {
        seen = dut.io.dc.b.valid.toBoolean &&
               dut.io.dc.b.payload.id.toInt == AxiIds.D_PUSH
        if (!seen) { dut.clockDomain.waitSampling(); g += 1 }
      }
      assert(seen,
        "the second write's B was never presented to the D-cache -- EVICT_WR would wait " +
        "for its id=2 ack forever, which is the p141 0x40806b68 boot wedge")
      assert(dut.io.out.b.ready.toBoolean,
        "the second write's B was never accepted on out.b -- BVALID is stranded on the fabric")
      dut.io.out.b.valid #= false
    }
  }

  test("a held write grant does not forward a second AW into the fabric") {
    // The other half of the same property, stated positively: while one write's B is
    // still outstanding the arbiter must BACK-PRESSURE the next AW (`aw.ready` low)
    // rather than let it through under the open grant. That is exactly what the SoC
    // crossbar's own `ws_state` does (`AxiMemModel`'s `crossbarSingleOutstanding`), and
    // it is what keeps every B paired with a live grant.
    M68kSim().compile(new MergeDut()).doSim("one-write-per-grant", seed = 7) { dut =>
      dut.clockDomain.forkStimulus(10)
      idleAll(dut)
      idleOutSlave(dut)
      dut.clockDomain.waitSampling(4)
      dcWriteKickoff(dut, 0x1000, AxiIds.D_STORE)
      // B withheld. A second write must not be admitted.
      dut.io.dc.aw.payload.addr #= 0x2000
      dut.io.dc.aw.payload.id   #= AxiIds.D_PUSH
      dut.io.dc.w.payload.data  #= 0xEE
      dut.io.dc.aw.valid #= true
      dut.io.dc.w.valid  #= true
      for (i <- 0 until 24) {
        dut.clockDomain.waitSampling()
        assert(!dut.io.dc.aw.ready.toBoolean,
          s"a second AW was admitted at cycle $i while the first write's B was outstanding")
        assert(!dut.io.out.aw.valid.toBoolean,
          s"a second AW was forwarded to the fabric at cycle $i under the open grant")
      }
      // Once the first B lands the second write proceeds normally.
      assert(returnB(dut, AxiIds.D_STORE), "the arbiter never accepted the first B")
      var g = 0
      while (!dut.io.dc.aw.ready.toBoolean && g < 200) { dut.clockDomain.waitSampling(); g += 1 }
      assert(g < 200, "the second write never got its own grant after the first B")
      dut.io.dc.aw.valid #= false; dut.io.dc.w.valid #= false
      assert(returnB(dut, AxiIds.D_PUSH), "the second write's B was never accepted")
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
