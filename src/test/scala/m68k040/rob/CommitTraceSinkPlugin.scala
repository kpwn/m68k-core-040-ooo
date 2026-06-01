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
    val traceOut = out(Vec(CommitTrace(), 2))
    val fireOut  = out(Vec(Bool(), 2))
    traceOut := ct.trace
    fireOut  := ct.traceFire
  }
}
