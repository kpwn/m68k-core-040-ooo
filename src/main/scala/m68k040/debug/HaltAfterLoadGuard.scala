package m68k040.debug

import spinal.core._

/** Conservative device-read interlock, NOT the precise halt decision.
  * A stale hit may hold an inhibited read until the comparison refreshes; it must
  * never authorize a halt or block ordinary cacheable loads. No epoch/invalidate
  * decode is needed on this fanout path. See docs/debug-load-guard.md. */
object HaltAfterLoadGuard {
  def apply(armed: Bool, compareArmed: Bool, pending: Bool, hit: Bool): Bool =
    armed && (!compareArmed || pending || hit)
}
