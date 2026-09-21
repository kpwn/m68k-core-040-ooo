package m68k040.services

import spinal.core._

/** IQ is the sole readiness producer. LSU supplies queryTag; all other fields
  * are IQ-owned. issuePending is sampled only with the ordinary LS issue fire.
  * queryReady reflects registered dynamic scoreboards, not a same-cycle wake. */
case class LateStoreDataPorts() extends Bundle {
  val issuePending = Bool()
  val queryTag = UInt(6 bits)
  val queryReady = Bool()
}

/** Sole producer: IssueQueuePlugin. None removes the experiment physically. */
trait LateStoreDataService {
  def lateStoreData: Option[LateStoreDataPorts]
}
