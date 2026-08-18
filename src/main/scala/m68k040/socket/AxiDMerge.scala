package m68k040.socket

import m68k040.cache.AxiIds
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._

object AxiDMerge {
  /** D20's bound, COPIED from v1 rather than re-derived: `TIMEOUT_CYCLES` at
    * `axi_narrow_to_wide.v:263` (`32'h7735_9400`).
    *
    * That file's header contains TWO generations of sizing rationale and the earlier one
    * reads as current: `:205-228` derives 2^28 from the xbar's `WD_LOG2_S1 = 2^27`, and
    * `:245-262` SUPERSEDES it with 2e9 because `sd_ctrl`'s per-request watchdog (~10.07 s)
    * is the larger downstream bound -- a 2^28 bound (~2.68 s) pre-empted a graceful,
    * retryable SCSI recovery with a fatal bus error by 3.75x. The v1 header records that
    * bug being introduced TWICE. A third repetition is avoidable only by copying the value
    * rather than the derivation, which is what this constant is.
    *
    * The invariant travels with the number, in v1's own words (`:259-262`): *this value
    * MUST exceed every downstream per-slave watchdog; if you raise one of those, raise
    * this one too, or the graceful recovery path you just tuned becomes unreachable.* */
  val V1_TIMEOUT_CYCLES: BigInt = BigInt(2000000000)

  /** Owner tags. The VALUE is arbitrary -- routing is by this latched tag, never by the
    * AXI ID (D8) -- but the read side has four owners and the write side three, and the
    * reset-vector reader is deliberately last so the write side can use the same encoding
    * truncated. */
  object Owner {
    val DCACHE   = 0
    val ITLB     = 1
    val DTLB     = 2
    val RESETVEC = 3
  }
}

/** The `axi_d` master merge (design spec D7-D10, D19-D20, section 4).
  *
  * ==Topology (D7)==
  * {{{
  *   DcachePlugin.axi (128b) --+
  *   itlbAxi          (128b) --+-- AxiDMerge --> axi_d
  *   dtlbAxi          (128b) --+   (owner-tag,
  *   ResetVectorPlugin (RO)  --+    serializing)
  * }}}
  * `axi_i` needs no arbiter: the I-cache is its only user and is already read-only. The
  * ITLB walker cannot join it -- `ItlbPlugin.scala:255-270` genuinely issues AXI WRITES
  * (the U-bit descriptor writeback) and `cpu_socket.vh:99` declares `axi_i` AR/R only.
  * That is the forcing constraint which puts BOTH walkers here.
  *
  * ==Owner tag, not ID demux (D8)==
  * Responses route by the grant machine's LATCHED OWNER. The returned AXI ID is forwarded
  * as a fabric hint and is NEVER consulted for routing. This is what makes G3's "ITLB and
  * DTLB both emit AR=2/AW=3" collision inert: two owners with identical IDs are
  * indistinguishable to the fabric but perfectly distinguishable to the arbiter, and being
  * single-outstanding per direction, at most one walker transaction exists at a time
  * anyway. A full ID demux would need the deliberately-unbuilt V2a.2/V2a.3 infrastructure
  * (`AxiIds.scala:21-30`) for zero end-to-end gain on a fabric that is single-outstanding
  * per master port regardless (`axi_narrow_to_wide.v:72-75`).
  *
  * ==Independent per-direction grants (D9), and why a single token deadlocks==
  * `DcachePlugin.scala:1258` sets `axi.r.ready := !refillWriteHold`: a refill deliberately
  * holds off ACCEPTING its R beat until a colliding same-set store drain's S1/S2 window
  * closes -- and that store drain needs the WRITE channel. Under one global grant token the
  * D-cache would hold the read grant while waiting for a write it cannot get. With
  * independent grants the dependency graph is acyclic: a walker's read and a walker's write
  * each depend on nothing else in the core, a refill's R acceptance may wait on a store
  * drain waiting on the write grant, and nothing on the write side ever waits on the read
  * side.
  *
  * ==What this watchdog is, and is NOT (D19 vs D20)==
  * It is NOT a reinstatement of v1's 20 s transaction-abandonment timer. D19 declines to
  * rebuild that: the fabric's own layered watchdogs (`peripheral_bus.v`'s
  * `PB_WATCHDOG_LOG2 = 24`, `axi_xbar.v`'s `WD_LOG2_S1 = 27`) already provide it OUTSIDE
  * the socket, and `axi_narrow_to_wide.v:205-228` says its own timer is by design a
  * never-firing last resort on an xbar-connected instance.
  *
  * This watchdog covers a DIFFERENT mode, one this design CREATES: three owners now share
  * one port, so an owner that never completes starves the other two indefinitely.
  * Previously they were physically separate masters and could not affect each other.
  *
  * On expiry it does NOT fabricate a response. Synthesising a `B` would be caught by the
  * D-cache's fail-closed ID demux in the best case and would silently ack a store that
  * never landed in the worst; synthesising an `R` would inject garbage into a refill.
  * Instead it raises `wedge`, which the core's wiring turns into a sticky `coreHalted` with
  * `HaltReason.ARBITER_WEDGE` -- the reason D28's channel has to exist at all. Note the
  * asymmetry with v1's choice to return SLVERR: v1 sat between a *host* (JTAG) and the
  * fabric, where informing an external restartable master is right. This arbiter sits
  * between core FSMs, where there is no external master to inform.
  *
  * @param grantTimeout cycles a grant may be held with NO progress on any channel of that
  *                     direction before `wedge` rises. Any accepted beat restarts the
  *                     count, so a legitimately slow transaction never trips it -- the same
  *                     "without making progress" formulation `axi_narrow_to_wide.v:97-101`
  *                     uses. Parameterised ONLY so directed tests can use a short value;
  *                     production is `AxiDMerge.V1_TIMEOUT_CYCLES`. */
class AxiDMerge(axiCfg: Axi4Config,
                grantTimeout: BigInt = AxiDMerge.V1_TIMEOUT_CYCLES) extends Component {
  import AxiDMerge.Owner
  require(grantTimeout >= 2, s"grantTimeout must be >= 2 (got $grantTimeout)")

  val io = new Bundle {
    val dc   = slave(Axi4(axiCfg))
    val itlb = slave(Axi4(axiCfg))
    val dtlb = slave(Axi4(axiCfg))
    // The reset-vector reader (D13) is a FLAT port rather than a fourth Axi4 bundle: its
    // transaction shape is fixed (one 16-byte read, len=0, size=4, INCR, id=RESET_VEC), so
    // the only fields it can vary are the address and the handshake. Keeping it flat means
    // ResetVectorPlugin cannot accidentally emit a differently-shaped transaction.
    val rvArValid = in Bool ()
    val rvArAddr  = in UInt (axiCfg.addressWidth bits)
    // NOTE: this Bundle also declares a field literally named `out` (the merged Axi4
    // master, below) -- in Scala a template body's members are visible for name
    // resolution throughout the WHOLE body regardless of textual order, so every use of
    // the `out(...)` DIRECTION FUNCTION in this Bundle must be fully qualified as
    // `spinal.core.out` or it resolves to that member instead (whose type has no `Bool`/
    // `Bits` method). Field names/types/directions are otherwise exactly as specified.
    val rvArReady = spinal.core.out Bool ()
    val rvRValid  = spinal.core.out Bool ()
    val rvRData   = spinal.core.out Bits (axiCfg.dataWidth bits)
    val rvRResp   = spinal.core.out Bits (2 bits)
    val rvRReady  = in Bool ()
    val wedge       = spinal.core.out Bool ()
    val wedgeIsRead = spinal.core.out Bool ()
    val out         = master(Axi4(axiCfg))
  }

  val timeoutBits = log2Up(grantTimeout + 1)

  // ── Read side: four owners, single outstanding ────────────────────────────────────
  val rd = new Area {
    val owner = Reg(UInt(2 bits)) init U(Owner.DCACHE, 2 bits)
    val busy  = RegInit(False)
    val rr    = Reg(UInt(2 bits)) init 0      // round-robin rotation base

    val req = Vec(Bool(), 4)
    req(Owner.DCACHE)   := io.dc.ar.valid
    req(Owner.ITLB)     := io.itlb.ar.valid
    req(Owner.DTLB)     := io.dtlb.ar.valid
    req(Owner.RESETVEC) := io.rvArValid

    // Round-robin pick: iterate k from 3 down to 0 so SpinalHDL's last-assignment-wins
    // gives the ROTATED-FIRST requester (k = 0) priority. No priority encoder chain.
    val pick = UInt(2 bits); pick := U(0, 2 bits)
    val any  = Bool();       any  := False
    for (k <- 3 to 0 by -1) {
      val idx = rr + U(k, 2 bits)
      when(req(idx)) { pick := idx; any := True }
    }

    val grant = !busy && any
    // `sel`/`open` mirror the write side's ALREADY-CORRECT pattern below verbatim. `busy`
    // must latch the moment arbitration PICKS an owner (`grant`), not the moment the wide
    // side's `ar.ready` actually rises: the wide side may never respond at all -- that IS
    // D20's watchdog scenario -- and the watchdog is gated on `busy`, so gating `busy` on
    // `io.out.ar.fire` instead (as an earlier revision of this file did) makes the
    // watchdog structurally unable to fire on exactly the case it exists for. `sel`
    // forwards the freshly-arbitrated `pick` on the grant cycle itself (before `busy`'s
    // register update takes effect) and the LATCHED `owner` on every cycle after, so the
    // forwarded AR payload never depends on a `pick` that could otherwise drift while a
    // grant is held stalled against the wide side.
    val sel  = Mux(busy, owner, pick)
    val open = busy || grant

    // AR payload is forwarded VERBATIM (D8) -- addr, id, len, size, burst -- so the
    // fabric's L2 ID logic (`l2c_ctrl.v:140-142,151`) and any future ID-aware behaviour
    // see exactly what the plugin intended. The reset-vector owner's shape is fixed here.
    // `valid` is additionally gated by the SELECTED owner's own local `ar.valid`
    // (mirroring the write side) so a single-beat AR correctly deasserts once that owner
    // completes its handshake with the arbiter, rather than staying presented for the
    // rest of the held grant.
    // Task 13 finding: `Axi4Config(axiCfg)` leaves SpinalHDL's `prot`/`cache`/`lock`/`qos`/
    // `region` sideband defaults ON (same as every other AXI4 bundle in this core -- see
    // `SocketAxi.scala`'s D29 doc comment), and none of the four read owners below (nor
    // their own upstream producers) ever assigns those five fields -- only `addr`/`id`/
    // `len`/`size`/`burst`, which is everything D8's "AR payload is forwarded VERBATIM"
    // promise actually needs. That is harmless the moment `io.out` is itself a genuine
    // TOP-LEVEL boundary pin (SpinalHDL's no-latch check exempts an unassigned top-level
    // output, which is why `DcachePlugin`'s OWN un-merged `master(Axi4(...))` -- see its
    // doc comment -- has silently exported `..._payload_{region,lock,cache,qos,prot}`
    // driven to `x` since long before this plan). It stops being harmless the instant this
    // component is nested two levels deep, which is exactly what `M68kSocketTop` (Task 13)
    // is the first build to ever do: SpinalHDL's `PhaseCheck_noLatchNoOverride` correctly
    // flags a truly-unassigned INTERNAL net as a latch. The fix is the established idiom
    // already used at `IcachePlugin.scala:996` for the identical situation on the I-side:
    // default the whole payload to an explicit don't-care BEFORE the arbitration below
    // overrides the fields it actually drives. Zero functional change -- these fields were
    // always X on every path that reaches a real socket boundary; this just makes the X
    // explicit early enough that SpinalHDL's checker accepts it as intentional.
    io.out.ar.payload.assignDontCare()
    io.out.ar.valid := open && io.dc.ar.valid
    io.out.ar.payload.addr  := io.dc.ar.payload.addr
    io.out.ar.payload.id    := io.dc.ar.payload.id
    io.out.ar.payload.len   := io.dc.ar.payload.len
    io.out.ar.payload.size  := io.dc.ar.payload.size
    io.out.ar.payload.burst := io.dc.ar.payload.burst
    when(sel === U(Owner.ITLB, 2 bits)) {
      io.out.ar.valid   := open && io.itlb.ar.valid
      io.out.ar.payload := io.itlb.ar.payload
    }
    when(sel === U(Owner.DTLB, 2 bits)) {
      io.out.ar.valid   := open && io.dtlb.ar.valid
      io.out.ar.payload := io.dtlb.ar.payload
    }
    when(sel === U(Owner.RESETVEC, 2 bits)) {
      io.out.ar.valid   := open && io.rvArValid
      io.out.ar.payload.addr  := io.rvArAddr
      io.out.ar.payload.id    := U(AxiIds.RESET_VEC, axiCfg.idWidth bits)
      io.out.ar.payload.len   := U(0, 8 bits)
      io.out.ar.payload.size  := U(4, 3 bits)          // 16 bytes -- the whole vector line
      io.out.ar.payload.burst := Axi4.burst.INCR
    }

    io.dc.ar.ready   := open && (sel === U(Owner.DCACHE,   2 bits)) && io.out.ar.ready
    io.itlb.ar.ready := open && (sel === U(Owner.ITLB,     2 bits)) && io.out.ar.ready
    io.dtlb.ar.ready := open && (sel === U(Owner.DTLB,     2 bits)) && io.out.ar.ready
    io.rvArReady     := open && (sel === U(Owner.RESETVEC, 2 bits)) && io.out.ar.ready

    when(grant) { owner := pick; busy := True; rr := pick + 1 }

    // R fans out to the LATCHED owner only. Payload is broadcast (cheaper than a mux and
    // harmless -- `valid` is what gates a consumer), `valid` is not.
    io.dc.r.payload   := io.out.r.payload
    io.itlb.r.payload := io.out.r.payload
    io.dtlb.r.payload := io.out.r.payload
    io.rvRData := io.out.r.payload.data
    io.rvRResp := io.out.r.payload.resp

    io.dc.r.valid   := io.out.r.valid && busy && (owner === U(Owner.DCACHE,   2 bits))
    io.itlb.r.valid := io.out.r.valid && busy && (owner === U(Owner.ITLB,     2 bits))
    io.dtlb.r.valid := io.out.r.valid && busy && (owner === U(Owner.DTLB,     2 bits))
    io.rvRValid     := io.out.r.valid && busy && (owner === U(Owner.RESETVEC, 2 bits))

    io.out.r.ready := busy && io.out.r.valid && (
      ((owner === U(Owner.DCACHE,   2 bits)) && io.dc.r.ready)   ||
      ((owner === U(Owner.ITLB,     2 bits)) && io.itlb.r.ready) ||
      ((owner === U(Owner.DTLB,     2 bits)) && io.dtlb.r.ready) ||
      ((owner === U(Owner.RESETVEC, 2 bits)) && io.rvRReady))

    when(io.out.r.fire && io.out.r.payload.last) { busy := False }

    // D20 bounded-grant watchdog: any accepted beat on EITHER channel of this direction
    // restarts the count, so a legitimately slow transaction never trips it.
    val progress = io.out.ar.fire || io.out.r.fire
    val wdog = Reg(UInt(timeoutBits bits)) init 0
    when(!busy || progress) { wdog := 0 } elsewhen (wdog =/= U(grantTimeout, timeoutBits bits)) {
      wdog := wdog + 1
    }
    val wedge = busy && (wdog === U(grantTimeout, timeoutBits bits))
  }

  // ── Write side: three owners, AW and W granted as a PAIR ──────────────────────────
  val wr = new Area {
    val owner = Reg(UInt(2 bits)) init U(Owner.DCACHE, 2 bits)
    val busy  = RegInit(False)
    val rr    = Reg(UInt(2 bits)) init 0

    // A write owner is "asking" as soon as either half of its pair is presented. The pair
    // is then held for the WHOLE transaction (D8): today every D-side write is len=0, but
    // the rule is stated as an invariant so a future burst writeback cannot interleave two
    // owners' W beats.
    val req = Vec(Bool(), 4)
    req(Owner.DCACHE)   := io.dc.aw.valid   || io.dc.w.valid
    req(Owner.ITLB)     := io.itlb.aw.valid || io.itlb.w.valid
    req(Owner.DTLB)     := io.dtlb.aw.valid || io.dtlb.w.valid
    req(Owner.RESETVEC) := False            // read-only owner; never asks for the W side

    val pick = UInt(2 bits); pick := U(0, 2 bits)
    val any  = Bool();       any  := False
    for (k <- 3 to 0 by -1) {
      val idx = rr + U(k, 2 bits)
      when(req(idx)) { pick := idx; any := True }
    }

    val grant = !busy && any
    val sel   = Mux(busy, owner, pick)
    val open  = busy || grant

    io.out.aw.valid   := open && io.dc.aw.valid
    io.out.aw.payload := io.dc.aw.payload
    io.out.w.valid    := open && io.dc.w.valid
    io.out.w.payload  := io.dc.w.payload
    when(sel === U(Owner.ITLB, 2 bits)) {
      io.out.aw.valid   := open && io.itlb.aw.valid
      io.out.aw.payload := io.itlb.aw.payload
      io.out.w.valid    := open && io.itlb.w.valid
      io.out.w.payload  := io.itlb.w.payload
    }
    when(sel === U(Owner.DTLB, 2 bits)) {
      io.out.aw.valid   := open && io.dtlb.aw.valid
      io.out.aw.payload := io.dtlb.aw.payload
      io.out.w.valid    := open && io.dtlb.w.valid
      io.out.w.payload  := io.dtlb.w.payload
    }

    io.dc.aw.ready   := open && (sel === U(Owner.DCACHE, 2 bits)) && io.out.aw.ready
    io.itlb.aw.ready := open && (sel === U(Owner.ITLB,   2 bits)) && io.out.aw.ready
    io.dtlb.aw.ready := open && (sel === U(Owner.DTLB,   2 bits)) && io.out.aw.ready
    io.dc.w.ready    := open && (sel === U(Owner.DCACHE, 2 bits)) && io.out.w.ready
    io.itlb.w.ready  := open && (sel === U(Owner.ITLB,   2 bits)) && io.out.w.ready
    io.dtlb.w.ready  := open && (sel === U(Owner.DTLB,   2 bits)) && io.out.w.ready

    when(grant) { owner := pick; busy := True; rr := pick + 1 }

    io.dc.b.payload   := io.out.b.payload
    io.itlb.b.payload := io.out.b.payload
    io.dtlb.b.payload := io.out.b.payload
    io.dc.b.valid   := io.out.b.valid && busy && (owner === U(Owner.DCACHE, 2 bits))
    io.itlb.b.valid := io.out.b.valid && busy && (owner === U(Owner.ITLB,   2 bits))
    io.dtlb.b.valid := io.out.b.valid && busy && (owner === U(Owner.DTLB,   2 bits))
    io.out.b.ready := busy && io.out.b.valid && (
      ((owner === U(Owner.DCACHE, 2 bits)) && io.dc.b.ready)   ||
      ((owner === U(Owner.ITLB,   2 bits)) && io.itlb.b.ready) ||
      ((owner === U(Owner.DTLB,   2 bits)) && io.dtlb.b.ready))
    when(io.out.b.fire) { busy := False }

    val progress = io.out.aw.fire || io.out.w.fire || io.out.b.fire
    val wdog = Reg(UInt(timeoutBits bits)) init 0
    when(!busy || progress) { wdog := 0 } elsewhen (wdog =/= U(grantTimeout, timeoutBits bits)) {
      wdog := wdog + 1
    }
    val wedge = busy && (wdog === U(grantTimeout, timeoutBits bits))
  }

  io.wedge       := rd.wedge || wr.wedge
  io.wedgeIsRead := rd.wedge

  // ── Section 4.4's required assertions ─────────────────────────────────────────────
  // Sim-only. `GenerationFlags.simulation` needs `.includeSimulation` on the enclosing
  // SpinalConfig to elaborate at all -- see M68kSim.scala.
  GenerationFlags.simulation {
    assert(!(rd.grant && rd.busy),
      "AxiDMerge: a read grant was issued while one was already outstanding", FAILURE)
    assert(!(wr.grant && wr.busy),
      "AxiDMerge: a write grant was issued while one was already outstanding", FAILURE)
    val rHot = io.dc.r.valid.asUInt +^ io.itlb.r.valid.asUInt +^
               io.dtlb.r.valid.asUInt +^ io.rvRValid.asUInt
    assert(rHot <= U(1), "AxiDMerge: an R beat was presented to more than one owner", FAILURE)
    val bHot = io.dc.b.valid.asUInt +^ io.itlb.b.valid.asUInt +^ io.dtlb.b.valid.asUInt
    assert(bHot <= U(1), "AxiDMerge: a B beat was presented to more than one owner", FAILURE)
    assert(!(RegNext(rd.busy) init False) || rd.busy || RegNext(io.out.r.fire && io.out.r.payload.last),
      "AxiDMerge: the read grant cleared without an r.last fire", FAILURE)
    assert(!(RegNext(wr.busy) init False) || wr.busy || RegNext(io.out.b.fire),
      "AxiDMerge: the write grant cleared without a b fire", FAILURE)
    // A granted owner's AR payload must not change while granted (an owner that mutates
    // its request mid-grant would make the forwarded payload and the latched owner
    // describe different transactions). Scoped to `rd.busy` STILL being true THIS cycle:
    // a stall observed last cycle (AR held one cycle behind the arbiter's own grant latch
    // by fabric backpressure) can legitimately be followed, in that very same window, by
    // the R side completing on a single-cycle-turnaround responder -- which clears
    // `rd.busy`. Once that happens the address reverting to whatever the NEXT (possibly
    // idle) arbitration round shows is a new round starting, not the owner changing its
    // request mid-grant, and comparing across that boundary would be a false positive.
    val arHeld = RegNext(io.out.ar.valid && !io.out.ar.ready) init False
    assert(!arHeld || !rd.busy || (RegNext(io.out.ar.payload.addr) === io.out.ar.payload.addr),
      "AxiDMerge: a granted owner changed its AR address across a stalled handshake", FAILURE)
  }
}
