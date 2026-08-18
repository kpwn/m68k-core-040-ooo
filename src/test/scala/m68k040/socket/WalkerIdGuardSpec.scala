package m68k040.socket

import m68k040.M68kSim
import m68k040.cache.AxiIds
import m68k040.mmu.TableWalker
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** Spec section 13, "Merge arbiter", D27 bullet, verbatim: "a `B` or `R` beat carrying a
  * non-walker ID presented to a walker is *not* acked -- the walker hangs loudly rather
  * than mis-completing a page-table walk. Directed, since no legal stimulus produces it
  * once the arbiter is correct; this checks the second line of defence exists at all."
  *
  * The property gained is the D-cache's own, verbatim (DcachePlugin.scala:1948-1956):
  * "an unrecognized id simply not ack anything -- a hung drain, which is loud and
  * debuggable, instead of a silent spurious ack." */
class WalkerIdGuardSpec extends AnyFunSuite {

  /** Thin wrapper: `TableWalker` is a plain Component with a `master(Axi4ReadOnly)`, so
    * it can be driven directly with no plugin host at all. */
  class WalkerDut extends Component {
    val w = new TableWalker()
    val io = new Bundle {
      val start = in Bool ()
      val req   = in(m68k040.mmu.WalkReq())
      val busy  = out Bool ()
      val done  = out Bool ()
    }
    w.io.start := io.start
    w.io.req   := io.req
    io.busy := w.io.busy
    io.done := w.io.done
    // AR is accepted immediately; R is driven by the test.
    w.io.axi.ar.ready := True
    w.io.axi.r.valid  := False
    w.io.axi.r.payload.assignDontCare()
    w.io.axi.r.valid.allowOverride
    w.io.axi.r.payload.id.allowOverride
    w.io.axi.r.payload.data.allowOverride
    w.io.axi.r.payload.resp.allowOverride
    w.io.axi.r.payload.last.allowOverride
    w.io.axi.r.valid.simPublic()
    w.io.axi.r.ready.simPublic()
    w.io.axi.r.payload.id.simPublic()
    w.io.axi.r.payload.data.simPublic()
    w.io.axi.r.payload.resp.simPublic()
    w.io.axi.r.payload.last.simPublic()
    w.io.axi.ar.valid.simPublic()
  }

  test("RESET_VEC is a distinct ID outside every live D-side ARID") {
    // Spec section 6.2: "it must not alias a live D-side ARID, so it goes outside the
    // D-refill reserved range 0-3 and the walkers' AR=2: 5."
    assert(AxiIds.RESET_VEC == 5, s"RESET_VEC must be 5, got ${AxiIds.RESET_VEC}")
    val liveArIds = (0 until 4).map(AxiIds.dRefill) :+ AxiIds.WALK_READ
    assert(!liveArIds.contains(AxiIds.RESET_VEC),
      s"RESET_VEC ${AxiIds.RESET_VEC} aliases a live ARID in $liveArIds")
    assert(AxiIds.RESET_VEC < (1 << AxiIds.ID_W), "RESET_VEC does not fit ID_W bits")
    // And nothing was renumbered (spec section 4.3 forbids it explicitly).
    assert(AxiIds.WALK_READ == 2 && AxiIds.WALK_WRITE == 3,
      "WALK_READ/WALK_WRITE were renumbered -- spec section 4.3 forbids this")
    assert(AxiIds.D_STORE == 1 && AxiIds.D_PUSH == 2 && AxiIds.D_EVICT == 4)
  }

  test("TableWalker does not accept an R beat carrying a foreign ID") {
    M68kSim().compile(new WalkerDut).doSim("foreign-r", seed = 1) { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.start #= false
      dut.io.req.vpn     #= 0x00100
      dut.io.req.rootPtr #= 0x00001000L
      dut.io.req.isWrite #= false
      dut.io.req.isSuper #= true
      dut.clockDomain.waitSampling(4)
      dut.io.start #= true
      dut.clockDomain.waitSampling()
      dut.io.start #= false
      // Wait for the walker to present its descriptor AR.
      dut.clockDomain.waitSamplingWhere(dut.w.io.axi.ar.valid.toBoolean)
      dut.clockDomain.waitSampling()
      // Present a beat carrying the D-cache's refill ID. It is NOT ours.
      dut.w.io.axi.r.valid   #= true
      dut.w.io.axi.r.payload.id   #= AxiIds.dRefill(0)
      dut.w.io.axi.r.payload.data #= BigInt("0" * 32, 16)
      dut.w.io.axi.r.payload.resp #= 0
      dut.w.io.axi.r.payload.last #= true
      for (i <- 0 until 8) {
        dut.clockDomain.waitSampling()
        assert(!dut.w.io.axi.r.ready.toBoolean,
          s"walker acked a foreign R id at cycle $i -- fail-OPEN, not fail-closed")
        assert(!dut.io.done.toBoolean, s"walk completed off a foreign beat at cycle $i")
      }
      // Its OWN id is accepted, so the guard is not simply wedged shut.
      dut.w.io.axi.r.payload.id #= AxiIds.WALK_READ
      dut.clockDomain.waitSampling()
      assert(dut.w.io.axi.r.ready.toBoolean,
        "walker rejected its own WALK_READ id -- the guard is inverted or over-tight")
    }
  }
}
