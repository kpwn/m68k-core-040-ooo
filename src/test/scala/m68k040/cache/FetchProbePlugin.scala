package m68k040.cache

import m68k040.services.FetchService
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin

/** Test-only plugin: hosted alongside the I-cache to give a standalone SpinalSim
  * test top-level IO to drive/observe FetchService.
  *
  * The plugin-side wiring lives in this plugin's OWN `during build` (not in the
  * Dut constructor), so the cmd Stream gets an internal driver and the host
  * Component does not touch any other plugin's logic.* during construction.
  *
  *  - cmdIn  : slave(Stream)  -> top-level IO; test drives valid/payload, reads ready
  *  - rspOut : master(Flow)   -> top-level IO; test reads valid/payload
  */
class FetchProbePlugin extends FiberPlugin {
  val logic = during build new Area {
    val fs = host[FetchService]

    val cmdIn  = slave(Stream(FetchCmd()))
    val rspOut = master(Flow(FetchRsp()))

    // Producer-side wiring: drive the service cmd from our IO, expose rsp on our IO.
    fs.cmd << cmdIn
    rspOut << fs.rsp

    // M1b (Task 6): flat Bits mirror of the response's 4 predecode chunks. SpinalSim
    // cannot read a Bundle (or a Vec of Bundles) as a single BigInt, and the M1b
    // read-path test has to compare the DELIVERED predecode against a raw slice of the
    // Unified Fetch Array's inline field -- which is only meaningful as raw bits. An
    // explicit signal (not a bare `.asBits` expression) so `simPublic` has something
    // real to name. Element 0 occupies the LOW PRED_BITS_PER_WORD bits, matching
    // `Vec.asBits` / `subdivideIn` ordering throughout the I-cache.
    val rspPredBits = Bits(rspOut.payload.pred.getBitsWidth bits)
    rspPredBits := rspOut.payload.pred.asBits
    rspPredBits.simPublic()
  }
}
