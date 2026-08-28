package m68k040.core

import spinal.core._
import spinal.core.fiber.Fiber
import spinal.lib.misc.database.Database
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}

/** Top-level core shell. Owns the Database blackboard and the PluginHost; all
  * behavior lives in the hosted plugins (spec invariant #3).
  *
  * @param exposeDebugPorts 2026-08-27 boot-investigation ILA taps (default
  *   false, zero cost to every existing consumer): RobPlugin's internal
  *   interrupt-recognition signals (`normalIrqGate`/`flushing`/`excIdle`/
  *   `iplActive`/`branchRedirect`/`p0.first`/`preciseDrainBusyIn`/
  *   `inhibitedLoadBusyIn`/`interruptPending`/head PC) are plain internal
  *   wires of a plugin HOSTED BY this component -- reading them from a
  *   PARENT component (e.g. M68kSocketTop) is a genuine SpinalHDL hierarchy
  *   violation (directionless signal, not a port), confirmed empirically.
  *   The fix is structural, not `simPublic()` (which only affects
  *   simulation visibility, not synthesis-time cross-component readability):
  *   a small wiring Area built HERE, inside M68kCore's own scope (so the
  *   `host[RobPlugin]` read below never crosses a component boundary),
  *   whose ONLY job is to re-drive these values onto real `out` ports of
  *   THIS component -- which a parent CAN legally read (SpinalHDL rule:
  *   "a port of a direct child component"). */
class M68kCore(val plugins: Seq[FiberPlugin], exposeDebugPorts: Boolean = false) extends Component {
  setDefinitionName("M68kCore")
  private val database = new Database
  private val host = database on (new PluginHost)
  database.on {
    host.asHostOf(plugins)
  }

  val dbg040 = if (exposeDebugPorts) new Area {
    val normalIrqGate      = out Bool ()
    val flushing            = out Bool ()
    val excIdle             = out Bool ()
    val iplActive           = out Bool ()
    val branchRedirect      = out Bool ()
    val p0First             = out Bool ()
    val preciseDrainBusyIn  = out Bool ()
    val inhibitedLoadBusyIn = out Bool ()
    val interruptPending    = out Bool ()
    val headPc              = out UInt (32 bits)
    // 2026-08-28 boot-investigation ILA taps (RAS occupancy / BTB+FTB
    // training payload — see docs/BUG_calibration_word_misplaced_0d00.md
    // Part 24): same structural pattern as the block above (a plain wiring
    // Area built INSIDE M68kCore's own scope so the `host[...]` reads never
    // cross a component boundary), added to directly observe, on real
    // hardware, whether a RAS-resident value or a stale BTB/FTB training
    // write is the source of the `0x40800284` cold-`bsrw` wrong-target bug
    // (Parts 19/22/23: the wrong target is reproducibly the fall-through/
    // return address of an earlier, unrelated, already-completed call).
    val rasPredValid        = out Bool ()
    val rasPredTarget       = out UInt (32 bits)
    val rasCount            = out UInt (7 bits) // RAS_ENTRIES=16 -> cntBits=5; zero-extended
    val btbPredHitComb      = out Bool ()
    val btbPredTargetComb   = out UInt (32 bits)
    val btbUpdValid         = out Bool ()
    val btbUpdPc            = out UInt (32 bits)
    val btbUpdTarget        = out UInt (32 bits)
    val ftbRspValid         = out Bool ()
    val ftbRspHit           = out Bool ()
    val ftbRspFramedOk      = out Bool ()
    val ftbRspTarget        = out UInt (32 bits)
    // 2026-08-28 boot-investigation ILA taps, round 3 (FTQ lookup-COMMAND
    // side + registered FTQ head-entry fields — see
    // docs/BUG_calibration_word_misplaced_0d00.md Part 24's own "Recommended
    // next steps" #1/#2). Part 24 instrumented the FTB's registered
    // lookup RESPONSE (ftbRsp*, above) but never the COMMAND side (the
    // actual live query address, `FtbLookupCmd.windowPc`), so it could not
    // confirm whether its one suggestive `ftbRspHit` coincidence
    // (Capture 1, sample 52) was answering a query for the PC under
    // investigation or an unrelated one. This round adds that command-side
    // tap, plus the FTQ's own registered head-entry fields (`ftqHeadE`:
    // brPc/brLen/target) and `ftqConfirm` itself — the exact decode-time
    // gate `docs/BUG_calibration_word_misplaced_0d00.md`'s Part
    // 24/coordinator hypothesis (a) names: `ftqConfirm` at
    // `FetchAlignPlugin.scala:952` matches an FTQ head entry to ANY simple
    // instruction of the same LENGTH as `ftqHeadE.brLen`, with no check that
    // the current instruction is actually the same branch (or a branch at
    // all) that trained that entry. Also taps `decodePc` itself (the
    // frontend's own live fetch/decode PC) and `ftqCount` (occupancy), since
    // Part 25's re-analysis showed the existing `dbg_ila_rob_pc_w` ROB-head
    // probe is stale-artifact-prone (`payload.readAsync` with no
    // occupancy gate) — `decodePc` is a genuinely live, ungated frontend
    // signal that lets a fresh capture distinguish "the front-end's own PC"
    // from "whatever the ROB happens to display".
    val ftbCmdValid       = out Bool ()
    val ftbCmdWindowPc    = out UInt (32 bits)
    val decodePc          = out UInt (32 bits)
    val ftqHeadBrPc       = out UInt (32 bits)
    val ftqHeadTarget     = out UInt (32 bits)
    val ftqHeadBrLen      = out UInt (4 bits)
    val ftqConfirm        = out Bool ()
    val ftqCount          = out UInt (6 bits) // ftqDepth=32 -> log2Up(33)=6
    Fiber.build {
      val rob = host[m68k040.rob.RobPlugin]
      normalIrqGate      := rob.logic.normalIrqGate
      flushing            := rob.logic.flushing
      excIdle             := rob.logic.excIdle
      iplActive           := rob.logic.iplActive
      branchRedirect      := rob.logic.branchRedirect
      p0First             := rob.logic.p0.first
      preciseDrainBusyIn  := rob.logic.preciseDrainBusyIn
      inhibitedLoadBusyIn := rob.logic.inhibitedLoadBusyIn
      interruptPending    := rob.logic.interruptPending
      headPc              := rob.logic.p0.pc

      val ras = host[m68k040.frontend.RasPlugin]
      rasPredValid  := ras.logic.predValid
      rasPredTarget := ras.logic.predTarget
      rasCount      := ras.logic.count.resized

      val btb = host[m68k040.frontend.BtbPlugin]
      btbPredHitComb    := btb.logic.predHitComb
      btbPredTargetComb := btb.logic.predTargetComb

      // Shared BTB+FTB retire-time training bus (RobPlugin's own output,
      // both BtbPlugin and FtbPlugin consume THIS SAME Flow — see
      // BtbUpdateService). One tap covers both consumers' write inputs.
      btbUpdValid  := rob.logic.btbUpdateFlow.valid
      btbUpdPc     := rob.logic.btbUpdateFlow.payload.pc
      btbUpdTarget := rob.logic.btbUpdateFlow.payload.target

      // FTB's own registered lookup response (the C+1 token/ring-matched
      // path FetchAlignPlugin applies — a SEPARATE speculative path from
      // the BTB's per-instruction combinational lookup above).
      val ftb = host[m68k040.frontend.FtbPlugin]
      ftbRspValid    := ftb.logic.rsp.valid
      ftbRspHit      := ftb.logic.rsp.payload.hit
      ftbRspFramedOk := ftb.logic.rsp.payload.framedOk
      ftbRspTarget   := ftb.logic.rsp.payload.target

      // Round 3 taps: the FTB lookup COMMAND (query address, at the
      // provider's own registered `cmd` — the same directionless Flow
      // FetchAlignPlugin drives via the FtbLookupService, so reading it
      // here at the FtbPlugin host stays inside this component the same
      // way the rsp taps above do) plus FetchAlignPlugin's own FTQ head/
      // decode-PC state.
      ftbCmdValid    := ftb.logic.cmd.valid
      ftbCmdWindowPc := ftb.logic.cmd.payload.windowPc

      val fap = host[m68k040.frontend.FetchAlignPlugin]
      decodePc      := fap.logic.decodePc
      ftqHeadBrPc   := fap.logic.ftqHeadE.brPc
      ftqHeadTarget := fap.logic.ftqHeadE.target
      ftqHeadBrLen  := fap.logic.ftqHeadE.brLen
      ftqConfirm    := fap.logic.ftqConfirm
      ftqCount      := fap.logic.ftqCount.resized
    }
  } else null
}
