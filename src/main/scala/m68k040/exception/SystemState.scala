package m68k040.exception

import spinal.core._
import spinal.core.sim._
import spinal.lib._

/** Committed architectural system state for precise exception delivery (slice 1).
  *
  * These are SIMPLE committed registers (NOT renamed) — they change only at
  * commit (exception entry / RTE / privileged moves), which are serializing
  * events, so no PRF/RAT machinery is needed (spec §3.1).
  *
  *  - `srSys` : the SR SYSTEM byte = SR[15:8]. Bit layout (within the 8-bit
  *      system byte): T1=7, T0=6, S=5, (M=4 unused this slice), I2=2, I1=1, I0=0.
  *      So the S (supervisor) bit is system-byte bit 5 (== SR bit 13). Reset =
  *      0x27 (S=1, I=7, T=0) to MATCH Musashi's boot SR (0x2700) so existing
  *      non-faulting lock-step programs surface the same SR.
  *  - `vbr`  : vector base register (reset 0).
  *  - `usp`/`ssp` : the two A7 banks. A7 (architectural addr reg 7) selects the
  *      bank by the COMMITTED S bit. S changes only on a serializing event, so
  *      banking is a committed-S mux — safe (no in-flight A7 with a stale S).
  *
  * `a7` is the architectural A7 value (S ? ssp : usp). `writeA7` writes the
  * bank currently selected by committed S.
  *
  * All write ports are `Flow` (valid+payload). Multiple ports may target
  * different registers in the same cycle; `writeA7` and `setSsp`/`setUsp` should
  * not be driven concurrently for the same bank (the FSM serializes them).
  */
class SystemState extends Area {
  /** Supervisor bit position within the 8-bit SR system byte (SR bit 13). */
  val S_BIT = 5

  val srSys = RegInit(U(0x27, 8 bits))   // S=1, I=7, T=0 (matches Musashi boot SR)
  val vbr   = RegInit(U(0, 32 bits))
  val usp   = RegInit(U(0, 32 bits))
  val ssp   = RegInit(U(0, 32 bits))
  srSys.simPublic(); vbr.simPublic(); usp.simPublic(); ssp.simPublic()

  /** Committed S (supervisor) bit. */
  val s = srSys(S_BIT)

  /** Architectural A7, banked by committed S. */
  val a7 = Mux(s, ssp, usp)

  // ── write ports (driven by the exception FSM / RTE / privileged moves) ──────
  val setSrSys = Flow(UInt(8 bits))
  val setVbr   = Flow(UInt(32 bits))
  val setUsp   = Flow(UInt(32 bits))
  val setSsp   = Flow(UInt(32 bits))
  val writeA7  = Flow(UInt(32 bits))   // writes the bank selected by committed S
  // default idle (allowOverride so a standalone/test DUT can drive them)
  setSrSys.valid.allowOverride; setSrSys.valid := False; setSrSys.payload.allowOverride; setSrSys.payload := U(0, 8 bits)
  setVbr.valid.allowOverride;   setVbr.valid := False;   setVbr.payload.allowOverride;   setVbr.payload := U(0, 32 bits)
  setUsp.valid.allowOverride;   setUsp.valid := False;   setUsp.payload.allowOverride;   setUsp.payload := U(0, 32 bits)
  setSsp.valid.allowOverride;   setSsp.valid := False;   setSsp.payload.allowOverride;   setSsp.payload := U(0, 32 bits)
  writeA7.valid.allowOverride;  writeA7.valid := False;  writeA7.payload.allowOverride;  writeA7.payload := U(0, 32 bits)

  // ── commit-time updates ──────────────────────────────────────────────────
  when(setSrSys.valid) { srSys := setSrSys.payload }
  when(setVbr.valid)   { vbr   := setVbr.payload }
  // writeA7 routes to the bank selected by the COMMITTED S (current srSys, before
  // any same-cycle setSrSys). Listed FIRST so an explicit setSsp/setUsp in the
  // same cycle takes priority (later-`when` wins in SpinalHDL).
  when(writeA7.valid) {
    when(s) { ssp := writeA7.payload } otherwise { usp := writeA7.payload }
  }
  when(setUsp.valid)   { usp   := setUsp.payload }
  when(setSsp.valid)   { ssp   := setSsp.payload }
}
