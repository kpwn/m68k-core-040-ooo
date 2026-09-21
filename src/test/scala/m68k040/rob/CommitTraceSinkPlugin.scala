package m68k040.rob

import m68k040.services.CommitTraceService
import m68k040.types.CommitTrace
import spinal.core._
import spinal.lib.misc.plugin.FiberPlugin

/** Test-only plugin: exposes CommitTraceService as top-level IO for SpinalSim.
  * Test reads dut.sink.logic.traceOut / fireOut. */
class CommitTraceSinkPlugin extends FiberPlugin {
  val logic = during build new Area {
    val ct = host[CommitTraceService]
    val traceOut = out(Vec(CommitTrace(), ct.trace.length))
    val fireOut  = out(Vec(Bool(), ct.trace.length))
    traceOut := ct.trace
    fireOut  := ct.traceFire
    val retirement = host.get[m68k040.services.RobRetirementService].map { service =>
      val observed = out(cloneOf(service.retiredRobIds))
      observed := service.retiredRobIds
      observed
    }
  }
}
