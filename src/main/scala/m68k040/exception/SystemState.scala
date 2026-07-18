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
  *      system byte): T1=7, T0=6, S=5, M=4, I2=2, I1=1, I0=0.
  *      S (supervisor) = system-byte bit 5 (SR bit 13). Reset =
  *      0x27 (S=1, I=7, T=0) to MATCH Musashi's boot SR (0x2700) so existing
  *      non-faulting lock-step programs surface the same SR.
  *  - `vbr`  : vector base register (reset 0).
  *  - `usp`  : user stack pointer (S=0 bank).
  *  - `isp`  : interrupt stack pointer (S=1, M=0 bank).
  *  - `msp`  : master stack pointer (S=1, M=1 bank).
  *
  * `a7` is the architectural A7 value: Mux(S, Mux(M, msp, isp), usp).
  * `writeA7` writes the bank currently selected by committed (S, M).
  *
  * All write ports are `Flow` (valid+payload). Multiple ports may target
  * different registers in the same cycle; `writeA7` and `setIsp`/`setMsp`/`setUsp`
  * should not be driven concurrently for the same bank (the FSM serializes them).
  */
class SystemState extends Area {
  /** Supervisor bit position within the 8-bit SR system byte (SR bit 13). */
  val S_BIT = 5
  /** Master bit position within the 8-bit SR system byte (SR bit 12). */
  val M_BIT = 4

  val srSys = RegInit(U(0x27, 8 bits))   // S=1, M=0, I=7, T=0 (matches Musashi boot SR)
  val vbr   = RegInit(U(0, 32 bits))
  val usp   = RegInit(U(0, 32 bits))
  val isp   = RegInit(U(0, 32 bits))     // M=0 supervisor bank (Interrupt Stack Pointer)
  val msp   = RegInit(U(0, 32 bits))     // M=1 supervisor bank (Master Stack Pointer)
  // SFC / DFC: the 3-bit source/destination function-code registers (MOVES bus FC source).
  // Simple committed regs (no banking), updated only by MOVEC (a serializing commit-time
  // sysOp). Musashi stores them masked `& 7` and reads them zero-extended. The FC value is
  // unused by this core's flat memory model (MOVES is flat) but must round-trip via MOVEC.
  val sfc   = RegInit(U(0, 3 bits))
  val dfc   = RegInit(U(0, 3 bits))
  // CACR: cache control register. Round-trippable via MOVEC (task #170-cluster10) but
  // with NO functional effect on the D-cache (this core's D-cache always caches
  // regardless of CACR bits — matches the pre-existing documented behavior; only the
  // storage/round-trip half was previously missing, unlike TCR/URP/SRP which are
  // consumed by the page walker and were reverted after a sim-poke regression, task
  // #131 — CACR has no such consumer, so it's a plain, low-risk committed register).
  val cacr  = RegInit(U(0, 32 bits))
  srSys.simPublic(); vbr.simPublic(); usp.simPublic(); isp.simPublic(); msp.simPublic()
  sfc.simPublic(); dfc.simPublic(); cacr.simPublic()

  /** Committed S (supervisor) and M (master) bits. */
  val s = srSys(S_BIT); s.simPublic()
  val m = srSys(M_BIT); m.simPublic()

  /** Active supervisor stack: M ? MSP : ISP. */
  val supBank = Mux(m, msp, isp); supBank.simPublic()
  /** Architectural A7, banked by committed (S, M). */
  val a7 = Mux(s, supBank, usp); a7.simPublic()

  // ── write ports ────────────────────────────────────────────────────────────
  val setSrSys = Flow(UInt(8 bits))
  val setVbr   = Flow(UInt(32 bits))
  val setUsp   = Flow(UInt(32 bits))
  val setIsp   = Flow(UInt(32 bits))
  val setMsp   = Flow(UInt(32 bits))
  val writeA7  = Flow(UInt(32 bits))   // writes the bank selected by committed (S, M)
  val setSfc   = Flow(UInt(3 bits))
  val setDfc   = Flow(UInt(3 bits))
  val setCacr  = Flow(UInt(32 bits))
  // Default idle; allowOverride so a standalone/test DUT (and ExceptionUnit) can drive them.
  setSrSys.valid.allowOverride; setSrSys.valid := False; setSrSys.payload.allowOverride; setSrSys.payload := U(0, 8 bits)
  setVbr.valid.allowOverride;   setVbr.valid := False;   setVbr.payload.allowOverride;   setVbr.payload := U(0, 32 bits)
  setUsp.valid.allowOverride;   setUsp.valid := False;   setUsp.payload.allowOverride;   setUsp.payload := U(0, 32 bits)
  setIsp.valid.allowOverride;   setIsp.valid := False;   setIsp.payload.allowOverride;   setIsp.payload := U(0, 32 bits)
  setMsp.valid.allowOverride;   setMsp.valid := False;   setMsp.payload.allowOverride;   setMsp.payload := U(0, 32 bits)
  writeA7.valid.allowOverride;  writeA7.valid := False;  writeA7.payload.allowOverride;  writeA7.payload := U(0, 32 bits)
  setSfc.valid.allowOverride;   setSfc.valid := False;   setSfc.payload.allowOverride;   setSfc.payload := U(0, 3 bits)
  setDfc.valid.allowOverride;   setDfc.valid := False;   setDfc.payload.allowOverride;   setDfc.payload := U(0, 3 bits)
  setCacr.valid.allowOverride;  setCacr.valid := False;  setCacr.payload.allowOverride;  setCacr.payload := U(0, 32 bits)

  // ── commit-time updates ──────────────────────────────────────────────────
  when(setSrSys.valid) { srSys := setSrSys.payload }
  when(setVbr.valid)   { vbr   := setVbr.payload }
  // writeA7 routes by COMMITTED (S, M). Listed FIRST so an explicit setIsp/setMsp/setUsp
  // in the same cycle wins (later-`when` wins in SpinalHDL).
  when(writeA7.valid) {
    when(s) { when(m) { msp := writeA7.payload } otherwise { isp := writeA7.payload } }
    .otherwise { usp := writeA7.payload }
  }
  when(setUsp.valid)   { usp := setUsp.payload }
  when(setIsp.valid)   { isp := setIsp.payload }
  when(setMsp.valid)   { msp := setMsp.payload }
  when(setSfc.valid)   { sfc := setSfc.payload }
  when(setDfc.valid)   { dfc := setDfc.payload }
  when(setCacr.valid)  { cacr := setCacr.payload }
}
