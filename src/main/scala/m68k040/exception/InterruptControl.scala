package m68k040.exception

import m68k040.services.InterruptControlService
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.FiberPlugin

/** The external interrupt-input owner (simple protocol). Holds the 3-bit IPL plus
  * the SoC's per-level autovector/vectored selection, and publishes them via
  * `InterruptControlService` for the ROB's recognition logic to read.
  *
  *  - `iplIn`      RegInit 0 : interrupt priority level (0 = none, 1..7 level, 7 NMI).
  *  - `iackAvec`   RegInit 0 : True => autovector (24 + level) for the active level.
  *  - `iackVector` RegInit 0 : the vectored vector (8b) used when `!iackAvec`.
  *
  * All three are RegInit so a standalone DUT (no external driver) elaborates with
  * no UNASSIGNED REGISTER; sim pokes / the FullCoreSynth registered top inputs
  * override them. RegInit 0 (ipl=0 idle) keeps every existing test unchanged — no
  * interrupt is ever recognized unless something raises iplIn. */
class InterruptControlPlugin extends FiberPlugin with InterruptControlService {
  var _iplIn:      UInt = null
  var _iackAvec:   Bool = null
  var _iackVector: UInt = null

  override def iplIn:      UInt = _iplIn
  override def iackAvec:   Bool = _iackAvec
  override def iackVector: UInt = _iackVector

  val logic = during build new Area {
    val iplIn      = RegInit(U(0, 3 bits)); iplIn.simPublic()
    val iackAvec   = RegInit(False);        iackAvec.simPublic()
    val iackVector = RegInit(U(0, 8 bits)); iackVector.simPublic()
    _iplIn      = iplIn
    _iackAvec   = iackAvec
    _iackVector = iackVector
  }
}
