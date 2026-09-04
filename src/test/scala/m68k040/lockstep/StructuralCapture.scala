package m68k040.lockstep

import m68k040.execute.regfile.{RegFilePluginInt, RegFilePluginNzvc, RegFilePluginX}
import m68k040.rename.RenameStage
import m68k040.rob.RobPlugin
import spinal.core.sim._
import scala.collection.mutable

/** Sim-only structural read of the DUT's COMMITTED architectural state.
  *
  * `CommittedMapService.intPhys(i)` names the physical register currently holding
  * architectural register i (D0-D7 = 0..7, A0-A7 = 8..15); `RegFilePlugin.logic.shadow`
  * is the sim-only mirror of the PRF backing Mem (the real `ram` is rewritten out of the
  * netlist by `MultiPortWritesSymplifier`, so `getBigInt` cannot reach it -- see
  * `RegFilePlugin.scala:115-131`). Composing the two gives the architectural value of
  * every register with NO new RTL and no dependence on the DUT volunteering a writeback
  * observation.
  *
  * The CCR is read the same way, through the committed NZVC/X mappings, so it is
  * INDEPENDENT of `WhiteboxCapture`'s running CCR fold. That independence is the point:
  * a fold bug and a structural read cannot both be wrong in the same direction. */
final class ArchStateProbe(ren: RenameStage,
                           rfInt: RegFilePluginInt,
                           rfNzvc: RegFilePluginNzvc,
                           rfX: RegFilePluginX) {
  private val intPhys  = ren.logic.intRat.io.committedPhys
  private val nzvcPhys = ren.logic.nzvcRat.io.committedPhys
  private val xPhys    = ren.logic.xRat.io.committedPhys
  private val intMem   = rfInt.logic.shadow
  private val nzvcMem  = rfNzvc.logic.shadow
  private val xMem     = rfX.logic.shadow

  def physOf(arch: Int): Int = intPhys(arch).toInt
  def readArch(arch: Int): Long = intMem(intPhys(arch).toInt).toLong & 0xffffffffL
  def readD: Vector[Long] = (0 until 8).map(readArch).toVector
  def readA: Vector[Long] = (8 until 16).map(readArch).toVector
  def readCcr: Int = {
    val nzvc = nzvcMem(nzvcPhys(0).toInt).toInt & 0xf
    val x    = xMem(xPhys(0).toInt).toInt & 1
    (x << 4) | nzvc
  }
}

/** Drives `ArchSnapshot` collection off the ROB's sim-only commit observations.
  *
  * ALIGNMENT (measured, not assumed -- see `docs/superpowers/specs/
  * 2026-09-04-lockstep-loop-inversion.md`):
  *  - `RobPlugin.logic.commitObs(k).fire` is `RegNext(retireK)`, one register deep from
  *    the retire event.
  *  - `RatTable.commReg` (hence `io.committedPhys`) is loaded from `commitPorts`, which
  *    are combinational off the same retire event -- also one register deep.
  *  So both are sampled consistently in the SAME `onSamplings` callback; no delay
  *  constant is needed and none is guessed.
  *
  * A snapshot is taken only at a MACRO boundary: the LAST commit observation firing in
  * the cycle must carry `macroLast`. A mid-macro retire leaves the committed RAT in a
  * state that has no oracle step at all (§7.2 of the NaxRiscv comparison: one macro ->
  * N micro-ops, unlike RISC-V's 1:1), so comparing there would be meaningless.
  *
  * A cycle in which TWO macros complete yields only ONE observable boundary -- the
  * second. The first is genuinely unobservable through the committed RAT, and is
  * reported as an UNCOVERED oracle step rather than silently skipped. */
object StructuralCapture {

  /** Call once per sampled cycle, AFTER `WhiteboxCapture.Handle` has consumed this
    * cycle's commit observations (so `wb.emitted` already counts them). */
  def sampleCycle(rob: RobPlugin, wb: WhiteboxCapture.Handle, probe: ArchStateProbe,
                  out: mutable.ArrayBuffer[ArchSnapshot], cycle: Long): Unit = {
    val o0 = rob.logic.commitObs(0)
    val o1 = rob.logic.commitObs(1)
    val o2 = rob.logic.commitObs(2)
    val f0 = o0.fire.toBoolean
    val f1 = o1.fire.toBoolean
    val f2 = o2.fire.toBoolean
    if (!(f0 || f1 || f2)) return
    // The exception/RTE pseudo-step (channel 2) is a serializing event, so when it fires
    // it is the last architectural change of the cycle. Otherwise the highest-numbered
    // normal retire port is.
    val (isBoundary, srSys) =
      if (f2)      (true,                    o2.sysByte.toInt & 0xff)
      else if (f1) (o1.macroLast.toBoolean,  o1.sysByte.toInt & 0xff)
      else         (o0.macroLast.toBoolean,  o0.sysByte.toInt & 0xff)
    if (!isBoundary) return
    val idx = wb.emitted - 1
    if (idx < 0) return
    out += ArchSnapshot(idx, cycle, probe.readD, probe.readA, probe.readCcr, srSys)
  }
}
