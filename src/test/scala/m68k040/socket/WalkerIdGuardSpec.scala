package m68k040.socket

import m68k040.cache.AxiIds
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

  test("D-side ID constants are invariant and non-overlapping") {
    // Verify the ID allocation hasn't changed.
    assert(AxiIds.D_STORE == 1, "D_STORE must remain 1")
    assert(AxiIds.D_PUSH == 2, "D_PUSH must remain 2")
    assert(AxiIds.D_EVICT == 4, "D_EVICT must remain 4")
    assert(AxiIds.WALK_READ == 2, "WALK_READ must remain 2 (walk AR)")
    assert(AxiIds.WALK_WRITE == 3, "WALK_WRITE must remain 3 (walk AW)")
  }
}
