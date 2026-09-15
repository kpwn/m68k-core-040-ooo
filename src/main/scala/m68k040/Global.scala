package m68k040

import spinal.lib.misc.database.Database

/** Single registry of Database keys. INVARIANT #3: every key has exactly one
  * producer plugin (documented in the comment). `blocking` keys make readers
  * await the producer; `value` keys are plain. */
object Global {
  // Keys are added here as the consuming plugins are written; see M68kParams
  // for the full parameter set. The framework does NOT throw on double-set —
  // invariant #3 (one producer per key) is a convention, not runtime-enforced.
  // Producer: ParamPlugin (setup phase)
  val ROB_DEPTH       = Database.blocking[Int]()
  /** Width of a robId. Derived, so the ROB array and every robId field in every EU
    * payload move TOGETHER. They used to disagree: RobPlugin hardcoded depth=64 while
    * ~20 payload bundles hardcoded `UInt(6 bits)`, which meant shrinking the ROB
    * either aliased silently or failed deep inside elaboration. */
  def ROB_ID_W: Int   = spinal.core.log2Up(ROB_DEPTH.get)
  val PHYS_INT_REGS   = Database.blocking[Int]()
  /** THE int physical-register pool size — the ONE number, in plain-constant form.
    *
    * Three places encode this size and they MUST agree, because rename hands out the ids
    * and the other two are what those ids index:
    *   1. `RenameStage.logic.intFree`'s `physCount` — the allocator.
    *   2. `PHYS_INT_REGS` (`M68kParams.physInt`) — the width of every int busy bitmap in
    *      `IssueQueuePlugin` (`sbInt.busy`, `sbIntClr`, `lsBusy`, `cplxBusy`,
    *      `aluSlowIntBusy`) and the depth of `sbInt.physToSlot`.
    *   3. `RegfileSpec.Int.depth` — the int PRF's backing `Mem`.
    *
    * A freelist LARGER than (2)/(3) is silent and catastrophic. The extra ids have no busy
    * bit at all: `lsBusy(p) := True` and `lsBusy(p)` are dynamic index forms on a
    * `Bits(PHYS_INT_REGS bits)` register, so for p past the end the SET is dropped and the
    * READ is 0. A producer landing on such an id therefore never marks itself busy, its
    * consumers are declared ready before it has written, and they read the PRF row — which
    * in Verilog semantics does not exist either (the int PRF `Mem` is exactly this deep).
    * The result is a WRONG OPERAND with no fault and no trace: the machine keeps executing,
    * just not the program. A wrong operand inside an early-ROM poll loop looks exactly like
    * a hang — PC pinned, zero exceptions ever taken.
    *
    * The freelist pops `archCount..physCount-1` IN ORDER from reset, so the top of the pool
    * is reached by roughly the first 34 int-writing instructions — not a register-pressure
    * corner, but the first few dozen instructions of any program.
    *
    * This has shipped TWICE as a live bug: once when the PRF was left at 48 after T0/T1
    * widened the pool to 50 (see `RegfileSpec.Int`'s comment), and once when a
    * `physCount = 54` freelist was ported onto a `physInt = 50` tree — a bitstream that
    * hung deterministically at a fixed early-ROM PC with exc_count = 0 while simulation
    * stayed green. Hence a plain constant every site derives from, plus
    * `PhysIntPoolConsistencySpec` and the `require` in `IssueQueuePlugin`.
    *
    * Plain constant, not a Database key, for the same reason as `FP_IMM_TABLE_DEPTH`
    * below: `RegfileSpec` and the `M68kParams` default are evaluated outside any plugin
    * host. */
  val PHYS_INT_REGS_DEFAULT: Int = 50
  val PHYS_NZVC_REGS  = Database.blocking[Int]()
  val PHYS_X_REGS     = Database.blocking[Int]()
  val DECODE_WIDTH    = Database.blocking[Int]()
  val RETIRE_WIDTH    = Database.blocking[Int]()
  val L1I_KB          = Database.blocking[Int]()
  val L1I_WAYS        = Database.blocking[Int]()
  val L1I_LINE_BYTES  = Database.blocking[Int]()
  val BTB_ENTRIES     = Database.blocking[Int]()
  val RAS_ENTRIES     = Database.blocking[Int]()
  val GHR_BITS        = Database.blocking[Int]()
  val PHT_ENTRIES     = Database.blocking[Int]()

  /** FP wide-immediate side table (docs/PLAN_routing_congestion_architectural.md item 3).
    * The 80-bit `F<op>.<fmt> #imm,FPn` immediate no longer rides the uop record
    * (DecodedUop -> MicroOpQueue -> rename skid -> IqContext cold Mems); it lives in a
    * FP_IMM_TABLE_DEPTH-entry table owned by DecodeStage, and the uop carries only the
    * entry tag in `imm[FP_IMM_TAG_W-1:0]` (those rows set useImm=False, so `imm` is free).
    * Plain constants, not Database keys: the width must be known by DecodedUop-shaped
    * bundles that are elaborated outside any plugin host (unit specs).
    * DEPTH: 16, not the plan's 8. On UltraScale+ a 1W/1R distributed RAM of depth <= 32
    * maps to the same RAM32M primitives whatever its depth (80 bits = 40 LUT6 at 8, 16
    * or 32 deep), so the only cost of the extra entries is the free/backend bitmaps (+16
    * FF) and one more tag bit in `imm`; what it buys is that the decode-side FULL stall
    * (the one scheduling restriction this table adds -- decode cannot feed independent
    * work past DEPTH unissued FP-immediate uops) needs 16 parked FP-immediates, i.e.
    * half the ROB, before it engages. */
  val FP_IMM_TABLE_DEPTH: Int = 16
  def FP_IMM_TAG_W: Int       = spinal.core.log2Up(FP_IMM_TABLE_DEPTH)
}
