package m68k040.services

import m68k040.types.CommitTrace
import spinal.core._

/** Catalog of cross-plugin service interfaces (spec invariant #3 / Appendix B).
  * A plugin implements a trait and registers via addService(this); consumers
  * resolve it with host[ServiceName]. Grown as real plugins are added. */

/** Produced by the commit plugin; consumed by the verification harness. */
trait CommitTraceService {
  def trace: CommitTrace
}

/** Produced by the redirect/flush owner (commit/branch); consumed by frontend. */
trait FlushService {
  def doFlush: Bool
  def flushPc: UInt
}
