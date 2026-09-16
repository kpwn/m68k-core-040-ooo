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
  /** THE reorder-buffer depth, in plain-constant form -- the ONE number every robId
    * field is sized from.
    *
    * WHY A PLAIN CONSTANT AND NOT JUST THE DATABASE KEY. `ROB_ID_W` below reads
    * `ROB_DEPTH.get`, a `Database.blocking` read that is only legal inside a plugin
    * host's fiber. But most robId-carrying bundles are STANDALONE classes --
    * `IqContext`, `SqAlloc`, `LsFault`, `BranchCompletion`, `CcrCompletion`,
    * `UmWriteAlloc`, the `StoreQueue`/`UmWriteQueue` `Component` io bundles -- and unit
    * specs construct several of them outside any host. They cannot call `ROB_ID_W`, so
    * before this they simply hardcoded `UInt(6 bits)`. Same problem, same solution as
    * `PHYS_INT_REGS_DEFAULT` below.
    *
    * CONSEQUENCE: the depth is configured HERE, and `M68kParams.robDepth` defaults to
    * this constant so there is exactly one source of truth. Passing a DIFFERENT
    * `robDepth` to `M68kParams` would make the ROB array (sized from `ROB_DEPTH.get`)
    * disagree with every standalone payload bundle (sized from `ROB_ID_W_DEFAULT`) --
    * an aliasing robId, i.e. silent wrong-entry completion. `RobPlugin` therefore
    * `require`s the two to agree, exactly as `IssueQueuePlugin` does for the int
    * physical-register pool. */
  val ROB_DEPTH_DEFAULT: Int = 32
  /** Width of a robId, host-side. Derived, so the ROB array and every robId field in
    * every EU payload move TOGETHER. They used to disagree: `RobPlugin` hardcoded
    * depth=64 while ~40 sites across 12 files hardcoded `UInt(6 bits)`, which meant
    * shrinking the ROB either aliased silently or failed deep inside elaboration. */
  def ROB_ID_W: Int   = spinal.core.log2Up(ROB_DEPTH.get)
  /** Width of a robId, for bundles elaborated OUTSIDE a plugin host. Equal to
    * `ROB_ID_W` by the `RobPlugin` require -- see `ROB_DEPTH_DEFAULT`. */
  def ROB_ID_W_DEFAULT: Int = spinal.core.log2Up(ROB_DEPTH_DEFAULT)

  /** Pack `{hi, robId}` into a FIXED-width tag, zero-extending on the left.
    *
    * The D-side `DLoadToken`/`DTranslationToken` tags are 8 bits by contract (values
    * $80/$81/$82 are reserved for the exception sequencer and the two table walkers), and
    * `LsEuPlugin` composes the LS-side ones as `{backendEpoch, splitPhase, robId}`. That
    * concatenation is exactly 8 bits only while a robId is 6 bits wide; at any smaller ROB
    * depth the raw `##` is too NARROW for the port and elaboration fails. Padding keeps the
    * field order and the reserved-value split (pad and epoch both live above bit 6, so a
    * padded LS tag still has bit 7 = 0 and can never collide with $80/$81/$82).
    *
    * The `if` is a SCALA-level test, not a mux: when no padding is needed this returns the
    * identical node graph the hand-written `##` produced, so the generated Verilog at the
    * default depth is byte-for-byte unchanged. */
  def robTag(hi: spinal.core.Bits, id: spinal.core.UInt, w: Int): spinal.core.UInt = {
    import spinal.core._
    val core = hi ## id.asBits
    require(core.getWidth <= w,
      s"robTag: {hi=${hi.getWidth}, robId=${id.getWidth}} = ${core.getWidth} bits does not " +
      s"fit in a $w-bit tag")
    (if (core.getWidth == w) core else B(0, w - core.getWidth bits) ## core).asUInt
  }
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
