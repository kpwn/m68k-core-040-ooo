package m68k040.rob

import m68k040.services.{RenameUopService, RenameCommitService, RobAllocService, CacheControlService}
import m68k040.rename.RenamedUop
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin

/** Test-only: PRODUCES RenameUopService from top-level-driven signals.
  * Test drives src.valid + src.payload + u1v; consumer drives src.ready. */
class RenameUopSourcePlugin extends FiberPlugin with RenameUopService {
  val logic = during build new Area {
    val src = Stream(Vec(RenamedUop(), 2))
    in(src.valid)
    src.payload.foreach(in(_))
    src.ready.simPublic()
    val u1v = in Bool ()
  }
  override def uops: Stream[Vec[RenamedUop]] = logic.src
  override def uop1Valid: Bool               = logic.u1v
}

/** Test-only mini-dispatch: drives the ROB's passive RobAllocService directly
  * from a RenameUopService (no IQ). Mirrors DispatchPlugin's ROB-alloc side so
  * RobPluginSpec can exercise the ring/retire path without an IssueQueue.
  * Back-pressure is ROB-only: ren.uops.ready := rob.allocReady. */
class RobAllocDriverPlugin extends FiberPlugin {
  val logic = during build new Area {
    val ren = host[RenameUopService]
    val rob = host[RobAllocService]
    val fire = ren.uops.valid && rob.allocReady
    ren.uops.ready := rob.allocReady
    rob.allocFire   := fire
    rob.allocUop(0) := ren.uops.payload(0)
    rob.allocUop(1) := ren.uops.payload(1)
    rob.allocSlot1  := ren.uop1Valid
  }
}

/** Test-only: CONSUMES RenameCommitService from RobPlugin, exposing commit ports
  * + flush as top-level IO so the test can observe retire-driven commit/free. */
class RenameCommitSinkPlugin extends FiberPlugin with RenameCommitService {
  val logic = during build new Area {
    // Directionless service wires (RobPlugin drives these).
    val commits = Vec.fill(2)(Flow(m68k040.rob.CommitSlot()))
    val flushP  = Bool()
    // Mirror to top-level outputs for observation.
    val commitValidOut = out(Vec(Bool(), 2))
    val commitArchOut  = out(Vec(UInt(5 bits), 2))
    val commitOldOut   = out(Vec(UInt(6 bits), 2))
    for (k <- 0 until 2) {
      commitValidOut(k) := commits(k).valid
      commitArchOut(k)  := commits(k).intArch
      commitOldOut(k)   := commits(k).intOld
    }
    val flushOut = out(Bool())
    flushOut := flushP
  }
  override def commitPorts: Vec[Flow[CommitSlot]] = logic.commits
  override def flushPort:   Bool                  = logic.flushP
}

/** Test-only: exposes CacheControlService as top-level IO for SpinalSim.
  * Test reads dut.cacheCtrl.logic.dcacheEnabledOut. */
class CacheControlSinkPlugin extends FiberPlugin {
  val logic = during build new Area {
    val cc = host[CacheControlService]
    val dcacheEnabledOut = out(Bool())
    dcacheEnabledOut := cc.dcacheEnabled
  }
}
