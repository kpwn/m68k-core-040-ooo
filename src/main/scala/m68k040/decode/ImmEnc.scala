package m68k040.decode

import spinal.core._
import m68k040.Global

/** NARROW-CARRY ENCODING for `DecodedUop.imm`.
  *
  * WHY. A per-bit census of the routed netlist put `imm` at 6,699 LUT across the
  * decode record (pushReg 4 slots + stashUops 3) -- 52% of the whole record, and 35x
  * what `pc` costs for the identical 224 flops. The cost is ~195 LUT PER BIT and is
  * near-uniform across all 32 bits (LOW 0-15 = 3,485, HIGH 16-31 = 3,064), i.e. nothing
  * was collapsing the high half: enough sources (abs.L, #imm.L, PC-relative folds) have
  * genuinely distinct upper words. A throwaway probe that made `imm[31:16]` unread
  * measured the ceiling of narrowing at -2,249 LUT.
  *
  * SHAPE. The decode record carries {2-bit mode, 16-bit payload} and is re-expanded to
  * 32 bits at the MicroOpQueue POP boundary, so `DecodeUopService`, `RenamedUop` and
  * every EU are untouched -- the same split `brPredTable` and `fpImmTable` already use
  * (see Global.scala).
  *
  *   SEXT  imm32 = sign-extend(payload)     available to any producer whose value is
  *   ZEXT  imm32 = zero-extend(payload)     provably 16-bit; used by the fpImmTable tag
  *                                          stamp (which needs imm[3:0] to survive).
  *   WIDE  imm32 = grpBank[tag][sel]        the GROUP bank: one entry per fed group.
  *   WIDEP imm32 = pushBank[tag][sel]       the PUSH bank: one entry per FSM/microcode
  *                                          push cycle.
  *
  * WHY TWO BANKS, AND WHY NEITHER NEEDS AN ALLOCATOR OR A STALL.
  *
  * GROUP BANK (`WIDE`) -- for every uop the MicroOpAssembler cracks. Such a uop's
  * immediate is a stable combinational function of the PACKET (a slice of its extension
  * words, or a fold of its PC), so the entry can be written IDEMPOTENTLY for as long as
  * the packet sits in `fed` -- exactly `brPredTable`'s argument, with no edge detection
  * and no free list. The tag is a wrapping counter advanced on `fed.fire`.
  * `sel` = 3 * packetIndex + crackPosition, i.e. ONE slot per (packet, crack position).
  * Indexing by crack POSITION rather than by "which builder produced it" is what makes
  * the bank small AND makes the scheme total: every cracked uop occupies exactly one
  * position of exactly one packet, so no two live uops can ever contend for a slot, and
  * no per-site "is this immediate narrow?" reasoning is needed anywhere.
  * A packet cracks to at most 3 uops (AssembledUops.count <= 3) and a group holds 2
  * packets, hence GRP_SLOTS = 6.
  *
  * PUSH BANK (`WIDEP`) -- for the MOVEM / MOVEP / FMOVEM.X / microcode sequencers. Those
  * HOLD `fed` and emit a DIFFERENT uop every cycle from the SAME group, so their
  * immediates are NOT a function of the group and the idempotent group write does not
  * apply to them. They push at most 2 uops per cycle (MOVEM's register pair; every other
  * sequencer pushes 1), and they never use the slot1 stash, so a bank indexed by a
  * counter advanced on each such PUSH covers them with PUSH_SLOTS = 2.
  *
  * DEPTH BOUND for both banks is Global.WIDE_IMM_TABLE_DEPTH -- see the comment there.
  * DecodeStage carries the same live sim assertion `brPredTable` does, so a future change
  * that breaks the bound stops the simulation instead of delivering a wrong address. */
object ImmEnc {
  val MODE_W    = 2
  val PAYLOAD_W = 16
  val WIDTH     = MODE_W + PAYLOAD_W

  def SEXT  = 0
  def ZEXT  = 1
  def WIDE  = 2
  def WIDEP = 3

  /** Slots per bank entry, and the payload layout {0.., tag, sel} shared by both banks. */
  val GRP_UOPS_PER_PACKET = 3        // AssembledUops.count is 1..3
  val GRP_SLOTS  = 2 * GRP_UOPS_PER_PACKET
  val PUSH_SLOTS = 2                 // MOVEM pushes 2 uops/cycle; every other FSM pushes 1
  val SEL_W      = log2Up(GRP_SLOTS max PUSH_SLOTS)
  def TAG_W      = Global.WIDE_IMM_TAG_W
  require(SEL_W + TAG_W <= PAYLOAD_W, "the {tag, sel} payload must fit ImmEnc.PAYLOAD_W")

  /** GROUP-bank slot for crack position `pos` of packet `pkt`. */
  def grpSel(pkt: Int, pos: Int): Int = {
    require(pkt >= 0 && pkt < 2 && pos >= 0 && pos < GRP_UOPS_PER_PACKET)
    GRP_UOPS_PER_PACKET * pkt + pos
  }

  private def enc(mode: Int, payload: Bits): Bits = {
    require(payload.getWidth == PAYLOAD_W, s"payload must be $PAYLOAD_W bits")
    B(mode, MODE_W bits) ## payload
  }
  private def tagSel(mode: Int, tag: UInt, sel: Int): Bits =
    enc(mode, B(0, PAYLOAD_W - TAG_W - SEL_W bits) ##
              tag.asBits.resize(TAG_W) ## B(sel, SEL_W bits))

  def sext(v: Bits): Bits = enc(SEXT, v.resize(PAYLOAD_W))
  def sext(v: SInt): Bits = enc(SEXT, v.resize(PAYLOAD_W).asBits)
  def sext(v: UInt): Bits = enc(SEXT, v.resize(PAYLOAD_W).asBits)
  def zext(v: Bits): Bits = enc(ZEXT, v.resize(PAYLOAD_W))
  def zext(v: UInt): Bits = enc(ZEXT, v.asBits.resize(PAYLOAD_W))
  def zero: Bits          = enc(SEXT, B(0, PAYLOAD_W bits))

  /** A wide value living in the fed group's bank entry (`sel` = `grpSel`). */
  def wide(tag: UInt, sel: Int): Bits = tagSel(WIDE, tag, sel)
  /** A wide value living in this push cycle's bank entry (`sel` = the push slot). */
  def wideP(tag: UInt, sel: Int): Bits = tagSel(WIDEP, tag, sel)

  def modeOf(e: Bits): Bits    = e(WIDTH - 1 downto PAYLOAD_W)
  def payloadOf(e: Bits): Bits = e(PAYLOAD_W - 1 downto 0)
  def tagOf(e: Bits): UInt     = e(SEL_W + TAG_W - 1 downto SEL_W).asUInt
  def selOf(e: Bits): UInt     = e(SEL_W - 1 downto 0).asUInt

  /** Bank entry widths (one 32-bit value per slot, slot s at bits [32s+31 : 32s]). */
  val GRP_ENTRY_W  = 32 * GRP_SLOTS
  val PUSH_ENTRY_W = 32 * PUSH_SLOTS

  /** Pick slot `sel` out of a bank entry. An explicit Mux chain: this SpinalHDL (1.14.1)
    * has no `muxListDc` on Bits/UInt, and a `switch` cannot be used because `expand`
    * needs both bank values available OUTSIDE its mode `switch`. */
  private def slotOf(entry: Bits, sel: UInt, slots: Int): Bits = {
    def slice(i: Int): Bits = entry(32 * i + 31 downto 32 * i)
    def rec(i: Int): Bits =
      if (i == slots - 1) slice(i) else Mux(sel === U(i, SEL_W bits), slice(i), rec(i + 1))
    rec(0)
  }

  /** Re-expand to the 32 bits every consumer downstream of the queue expects.
    * `grpRd`/`pushRd` are the two banks' entries, already read with `tagOf`. */
  def expand(e: Bits, grpRd: Bits, pushRd: Bits): Bits = {
    val out  = Bits(32 bits)
    val pay  = payloadOf(e)
    val sel  = selOf(e)
    val grp  = slotOf(grpRd,  sel, GRP_SLOTS)
    val push = slotOf(pushRd, sel, PUSH_SLOTS)
    switch(modeOf(e).asUInt) {
      is(U(SEXT, MODE_W bits))  { out := pay.asSInt.resize(32).asBits }
      is(U(ZEXT, MODE_W bits))  { out := pay.asUInt.resize(32).asBits }
      is(U(WIDE, MODE_W bits))  { out := grp }
      default                   { out := push }
    }
    out
  }
}
