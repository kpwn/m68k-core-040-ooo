package m68k040.rename

import m68k040.services.RenameUopService
import spinal.core._
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin

/** Test-only plugin: exposes RenameUopService as top-level IO for SpinalSim.
  * Mirrors m68k040.decode.UopSinkPlugin.
  */
class RenameUopSinkPlugin extends FiberPlugin {
  val logic = during build new Area {
    val du = host[RenameUopService]

    val out = master(Stream(Vec(RenamedUop(), 2)))
    out << du.uops

    val u1v = Bool()
    u1v.asOutput()
    u1v := du.uop1Valid
  }
}
