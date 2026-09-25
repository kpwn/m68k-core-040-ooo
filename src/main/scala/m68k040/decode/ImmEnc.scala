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
  * SHAPE. The record carries {2-bit mode, 16-bit payload} and is re-expanded to 32 bits
  * at the MicroOpQueue POP boundary, so `RenamedUop` and every EU are untouched -- the
  * same split `brPredTable` and `fpImmTable` already use (see Global.scala).
  *
  *   SEXT  imm32 = sign-extend(payload)     d16/d8 displacements, quick immediates,
  *                                          counts, deltas, #imm.B/.W signed
  *   ZEXT  imm32 = zero-extend(payload)     MOVEM masks, imm16 -> SR
  *   WIDE  imm32 = wideTable[tag][sel]      abs.L, #imm.L, PC-relative folded addresses
  *
  * WHY WIDE NEEDS NO ALLOCATOR AND NO STALL. A wide immediate is a property of the
  * PACKET, not of a uop: it is a slice of that packet's extension words (or a fold of
  * its PC), so it is a stable combinational function of `fed` and can be written
  * IDEMPOTENTLY for as long as the packet sits there -- exactly `brPredTable`'s
  * argument, with no edge detection and no free list. Both packets of a fed group are
  * present at once and each contributes at most one wide value per EA (src and dst), so
  * ONE 4 x 32-bit entry per group covers every case with a SINGLE write port. `sel`
  * picks {p0src, p0dst, p1src, p1dst}.
  *
  * That a packet cannot need more than two is not an assumption: the only forms that
  * could (full-format memory-indirect, bd.L + od.L) are kept as `EaClass.MEMCOMPLEX` and
  * routed to microcode, which emits one uop per cycle. */
object ImmEnc {
  val MODE_W    = 2
  val PAYLOAD_W = 16
  val WIDTH     = MODE_W + PAYLOAD_W

  def SEXT = 0
  def ZEXT = 1
  def WIDE = 2

  /** `sel` indexes the four wide values a fed group can carry. */
  def SEL_P0_SRC = 0
  def SEL_P0_DST = 1
  def SEL_P1_SRC = 2
  def SEL_P1_DST = 3

  private def enc(mode: Int, payload: Bits): Bits = {
    require(payload.getWidth == PAYLOAD_W, s"payload must be $PAYLOAD_W bits")
    B(mode, MODE_W bits) ## payload
  }

  def sext(v: Bits): Bits = enc(SEXT, v.resize(PAYLOAD_W))
  def sext(v: SInt): Bits = enc(SEXT, v.resize(PAYLOAD_W).asBits)
  def sext(v: UInt): Bits = enc(SEXT, v.resize(PAYLOAD_W).asBits)
  def zext(v: Bits): Bits = enc(ZEXT, v.resize(PAYLOAD_W))
  def zero: Bits          = enc(SEXT, B(0, PAYLOAD_W bits))

  /** A wide value living in the fed group's table entry. */
  def wide(tag: UInt, sel: UInt): Bits =
    enc(WIDE, (B(0, PAYLOAD_W - Global.WIDE_IMM_TAG_W - 2 bits) ##
               tag.asBits.resize(Global.WIDE_IMM_TAG_W) ##
               sel.asBits.resize(2)).resize(PAYLOAD_W))

  def modeOf(e: Bits): Bits    = e(WIDTH - 1 downto PAYLOAD_W)
  def payloadOf(e: Bits): Bits = e(PAYLOAD_W - 1 downto 0)
  def tagOf(e: Bits): UInt     = e(2 + Global.WIDE_IMM_TAG_W - 1 downto 2).asUInt
  def selOf(e: Bits): UInt     = e(1 downto 0).asUInt

  /** Re-expand to the 32 bits every consumer downstream of the queue expects.
    * `wideRd` is the group's 4 x 32-bit table entry, already read with `tagOf`. */
  def expand(e: Bits, wideRd: Bits): Bits = {
    val out  = Bits(32 bits)
    val pay  = payloadOf(e)
    val wsel = selOf(e)
    val w    = wsel.muxList(
      (0 until 4).map(i => i -> wideRd(32 * i + 31 downto 32 * i)))
    switch(modeOf(e).asUInt) {
      is(U(SEXT, MODE_W bits)) { out := pay.asSInt.resize(32).asBits }
      is(U(ZEXT, MODE_W bits)) { out := pay.asUInt.resize(32).asBits }
      is(U(WIDE, MODE_W bits)) { out := w }
      default                  { out := pay.asSInt.resize(32).asBits }
    }
    out
  }
}
