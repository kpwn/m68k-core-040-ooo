package m68k040.rename

import m68k040.services.RenameCommitService
import m68k040.rob.CommitSlot
import spinal.core._
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin

/** Test-only plugin: DRIVES RenameCommitService (commit ports + flush) from
  * top-level-driven IO, standing in for the ROB in standalone rename tests.
  * Test pokes dut.cdrv.logic.flushIn / commitValidIn / commit payload fields. */
class RenameCommitDriverPlugin extends FiberPlugin {
  val logic = during build new Area {
    val rc = host[RenameCommitService]

    val flushIn = in Bool ()
    rc.flushPort := flushIn

    val cmd = Vec.fill(2)(slave(Flow(CommitSlot())))
    for (k <- 0 until 2) {
      rc.commitPorts(k).valid   := cmd(k).valid
      rc.commitPorts(k).payload := cmd(k).payload
    }
  }
}
