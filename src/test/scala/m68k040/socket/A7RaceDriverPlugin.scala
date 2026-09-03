package m68k040.socket

import m68k040.execute.regfile.{IntRegFileService, RegFileWritePort, RegFileReadPort, RegfileSpec}
import m68k040.services.CommittedMapService
import spinal.core._
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin

/** Test-only plugin (docs/BUG_calibration_word_misplaced_0d00.md Part 98 S6 follow-up,
  * exercised by `ResetVectorA7RaceSpec`): a minimal, faithful stand-in for
  * `FullCoreSynth.scala:526-537`'s real `a7Wr`/`a7Rd` wiring, wired against a REAL
  * `RegFilePluginInt` and a REAL `RenameStage` (via `CommittedMapService`), so the
  * write/read address the test can poke is the exact same `committedPhysA7` signal
  * production RTL uses -- not a stand-in constant -- when `useFixedAddr`/`rdUseFixedAddr`
  * are left low.
  *
  * `dummyWr` is a throwaway FIRST write port (always invalid, its own fresh
  * `sharingKey`) registered ahead of the real one so `wr` lands on `RegFilePlugin`'s
  * physical write index >= 1 -- the plain `enable = w.valid && initDone` gate --
  * matching `a7Wr`'s real index-5 slot in the full core (grouped under sharingKey
  * "excA7", 6th write requester). Physical index 0 uses a DIFFERENT mechanism (its
  * address is muxed to the sweep counter, not merely gated); both drop a same-window
  * write, but only the index>=1 shape is what `a7Wr` itself actually goes through. */
class A7RaceDriverPlugin extends FiberPlugin {
  var dummyWr: RegFileWritePort = null
  var wr: RegFileWritePort = null
  var rd: RegFileReadPort = null

  during setup {
    val rf = host[IntRegFileService]
    dummyWr = rf.newWrite(latency = 1)                        // fresh key -> phys[0]
    wr      = rf.newWrite(latency = 1, sharingKey = "a7race")  // fresh key -> phys[1]
    rd      = rf.newRead(forceNoBypass = true)
  }

  val logic = during build new Area {
    dummyWr.valid := False; dummyWr.address := 0; dummyWr.data := 0

    val cmap = host[CommittedMapService]
    val committedPhysA7 = cmap.intPhys(15)
    val committedPhysA7Out = out UInt (committedPhysA7.getWidth bits)
    committedPhysA7Out := committedPhysA7 // unclamped, raw probe -- tests observe the REAL value here

    // SIM-ONLY safety net, NOT a DUT behaviour: `commReg` (RatTable, hence
    // `committedPhysA7`) has no `.init(...)` by construction (that is Mechanism B
    // itself), so before RenameStage's own init walk reaches arch index 15,
    // SpinalSim's per-seed register randomisation can hand it ANY 6-bit value
    // (0-63) -- including >= RegfileSpec.Int.depth (50), which is genuinely
    // out-of-range for the PRF `Mem` this plugin's write/read ports index into.
    // Real hardware never has this problem (FPGA flops power up to a fixed,
    // bitstream-determined pattern, not simulation-random noise), but Verilator's
    // sim backend fatals on an out-of-range Mem address even when the write is
    // gated off (`enable=False`) -- observed directly: a raw (unclamped) wiring
    // of this signal into `wr.address` crashed one seed in a handful with
    // "Simulation failed at time=0", nondeterministically, before any testbench
    // poke thread even ran. Clamping ONLY the two Mem-facing ports (not the probe
    // above) removes that sim artifact without changing what any test asserts:
    // every real assertion in this file reads either a FIXED address or
    // `committedPhysA7Out` post-initDone, by which point the real signal is
    // always in-range anyway (the RAT walk unconditionally covers 0..19 first).
    def clampToDepth(u: UInt): UInt =
      Mux(u >= U(RegfileSpec.Int.depth, u.getWidth bits), U(0, u.getWidth bits), u)

    // Direct observation of both sweeps' own completion flags -- not re-derived/
    // re-counted here, the REAL signals each plugin computes for itself.
    val rfPlugin  = host[m68k040.execute.regfile.RegFilePluginInt]
    val renPlugin = host[m68k040.rename.RenameStage]
    val regInitDone = out Bool(); regInitDone := rfPlugin.logic.initDone
    val ratInitDone  = out Bool(); ratInitDone  := renPlugin.logic.initDone

    // useFixedAddr=false (default intent): address tracks committedPhysA7 live, exactly
    // like FullCoreSynth.scala:529's `Mux(rvSspValid, committedPhysA7.resize(...), ...)`.
    // useFixedAddr=true: test pins a specific phys id, to isolate Mechanism A from RAT
    // staleness (Mechanism B) when the two are being tested independently.
    val useFixedAddr = in Bool()
    val fixedAddr    = in UInt (wr.address.getWidth bits)
    val wrValid      = in Bool()
    val wrData       = in UInt (32 bits)
    wr.valid   := wrValid
    wr.address := Mux(useFixedAddr, fixedAddr, clampToDepth(committedPhysA7.resize(wr.address.getWidth)))
    wr.data    := wrData.asBits

    val rdUseFixedAddr = in Bool()
    val rdFixedAddr    = in UInt (rd.addr.getWidth bits)
    rd.addr := Mux(rdUseFixedAddr, rdFixedAddr, clampToDepth(committedPhysA7.resize(rd.addr.getWidth)))
    val rdData = out UInt (32 bits)
    rdData := rd.data.asUInt
  }
}
