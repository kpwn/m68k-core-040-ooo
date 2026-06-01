package m68k040.frontend

import m68k040.services.DecodeFeedService
import spinal.core._
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin

/** Test-only plugin: exposes DecodeFeedService as top-level IO for SpinalSim tests
  * that want to observe the decode feed without a full DecodeStage.
  *
  * Pattern mirrors m68k040.cache.FetchProbePlugin: wiring lives entirely in this
  * plugin's OWN `during build` block, following the clean plain-wire service
  * convention (no setAsDirectionLess).
  *
  *  - feedOut  : master(Stream(Vec(DecodePacket, 2))) → top-level IO; test drives
  *               ready, reads valid/payload
  *  - s1v      : out(Bool) → top-level IO; reflects slot1Valid from the service
  */
class DecodeFeedProbePlugin extends FiberPlugin {
  val logic = during build new Area {
    val df = host[DecodeFeedService]

    val feedOut = master(Stream(Vec(DecodePacket(), 2)))
    feedOut << df.feed

    val s1v = out(Bool())
    s1v := df.slot1Valid
  }
}
