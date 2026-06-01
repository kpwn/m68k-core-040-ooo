package m68k040.decode

import m68k040.services.DecodeUopService
import spinal.core._
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin

/** Test-only plugin: exposes DecodeUopService as top-level IO for SpinalSim tests.
  *
  * Pattern mirrors m68k040.cache.FetchProbePlugin: wiring lives entirely in this
  * plugin's OWN `during build` block, following the clean plain-wire service
  * convention (no setAsDirectionLess).
  *
  *  - uopsOut : master(Stream(Vec(DecodedUop, 2))) → top-level IO; test drives
  *              ready, reads valid/payload
  *  - u1v     : out(Bool) → top-level IO; reflects uop1Valid from the service
  */
class UopSinkPlugin extends FiberPlugin {
  val logic = during build new Area {
    val du = host[DecodeUopService]

    val uopsOut = master(Stream(Vec(DecodedUop(), 2)))
    uopsOut << du.uops

    val u1v = out(Bool())
    u1v := du.uop1Valid
  }
}
