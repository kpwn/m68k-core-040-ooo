package m68k040.socket

import spinal.core._

/** The `axi_i` / `axi_d` socket boundary byte-order permutation (design spec
  * `docs/superpowers/specs/2026-08-18-axi-socket-adapter-design.md`, D1/D2/D3, section 2).
  *
  * ==The two conventions==
  * THIS CORE is byte-address-invariant: for a beat carrying the bytes at physical
  * addresses `base+0 .. base+N-1`, the byte at offset `o` is `data[8*o +: 8]`
  * (`DcacheTypes.scala:169-171`, `IcacheTypes.scala:85`, `TableWalker.scala:83-85`).
  *
  * THE SoC presents 32-bit words in ascending address order, big-endian WITHIN each
  * 32-bit word: the byte at offset `4W + j` occupies `data[32W + 24 - 8j +: 8]`
  * (`if_to_axi.v:113-145`, `m68k_mem_lane.vh:14-30,60-80`, and `axi_xbar.v:133-141`,
  * which states the transform in exactly these terms for its own S3 shim: "byte-swaps
  * each 32-bit word and reverses each 4-bit WSTRB nibble").
  *
  * ==The transform==
  * {{{
  *   socketWord(W)          = byteReverse32( coreWord(W) )   for every W
  *   socketStrb[4W + (3-j)] = coreStrb[4W + j]               for every W, j
  * }}}
  * Reverse the four bytes inside each 32-bit lane; reverse each 4-bit strobe nibble;
  * LEAVE LANE ORDER ALONE. It is NOT a full 128-bit byte reverse and NOT a word-order
  * reversal.
  *
  * ==Three properties the callers rely on==
  *  1. '''It is an involution.''' One function serves core->socket and socket->core;
  *     there is no forward/inverse pair to keep in sync.
  *  2. '''It is width-parametric.''' 4 lanes at 128 bit (`axi_d`), 8 at 256 (`axi_i`).
  *  3. '''It has zero logic depth.''' It is a renaming of wires: it cannot appear on a
  *     timing path and cannot move the post-route FMax result.
  *
  * ==Two ways to misuse it, both stated at the call site as well (D3)==
  *  - '''Applying it to an address is a bug.''' Only `w.data`, `w.strb` and `r.data`
  *    are ever permuted. Never addr/id/len/size/burst/resp/last.
  *  - '''Applying it twice is a no-op that looks like a fix.''' Because it is an
  *    involution, a double application silently restores the WRONG convention. It is
  *    applied EXACTLY ONCE PER MASTER, in `M68kSocketTop` and nowhere else;
  *    `tools/socket/check_socket_netlist.py` is the machine-checked form of that.
  *
  * ==Ordering hazard with the D5 store derivation==
  * The MMIO store sizing of spec section 3.3.1 derives its byte range from the CORE-SIDE
  * (pre-permutation) strobe, because nibble reversal preserves popcount and contiguity
  * but NOT the offset a run starts at. That derivation lives inside `DcachePlugin` and
  * this permutation is applied strictly afterwards, at the socket boundary. The two
  * transforms are order-dependent. See `MmioCover`'s doc comment for the other half. */
object SocketByteOrder {

  /** Per-32-bit-lane byte reversal. Width must be a whole number of 32-bit lanes. */
  def permuteData(d: Bits): Bits = {
    val w = d.getWidth
    require(w % 32 == 0, s"SocketByteOrder.permuteData needs a multiple of 32 bits (got $w)")
    val lanes = for (l <- 0 until w / 32) yield {
      val word = d(32 * l + 31 downto 32 * l)
      // byteReverse32: b0##b1##b2##b3 with b0 the LOW byte, so the low byte becomes
      // the high byte of the emitted lane -- a per-lane endianness flip.
      word(7 downto 0) ## word(15 downto 8) ## word(23 downto 16) ## word(31 downto 24)
    }
    // Cat the lanes back in ASCENDING index order (lane order is untouched): empirically
    // (SocketByteOrderSpec's exhaustive/random hardware checks), SpinalHDL's `Cat(Seq(...))`
    // puts the FIRST Seq element in the LOW bits, so no `.reverse` is needed here -- unlike
    // the `##` operator used above for the within-lane reversal, which puts its LEFT operand
    // in the HIGH bits. An earlier draft of this file assumed `Cat` matched `##` and reversed
    // the sequence, which silently composed with the within-lane reversal into a FULL-width
    // byte reverse (byte i <-> byte N-1-i) -- exactly the transform D2 forbids. Caught by the
    // hardware-vs-model tests, not by inspection.
    val out = Cat(lanes)
    require(out.getWidth == w)
    out
  }

  /** Per-4-bit-nibble reversal of a byte strobe. Width must be a multiple of 4. */
  def permuteStrb(s: Bits): Bits = {
    val w = s.getWidth
    require(w % 4 == 0, s"SocketByteOrder.permuteStrb needs a multiple of 4 bits (got $w)")
    val nibbles = for (n <- 0 until w / 4) yield {
      val nib = s(4 * n + 3 downto 4 * n)
      nib(0) ## nib(1) ## nib(2) ## nib(3)
    }
    // See permuteData's comment: Cat(Seq(...)) puts the FIRST element in the LOW bits, so
    // the nibble sequence is concatenated in-order, not reversed.
    val out = Cat(nibbles)
    require(out.getWidth == w)
    out
  }

  /** The same permutation as a pure Scala function over a byte sequence indexed by
    * BYTE OFFSET (element 0 = the byte at the beat's base address). Used by the golden
    * model in `SocketByteOrderSpec` and by every socket-convention simulation model in
    * this plan; keeping one definition is what stops the model and the hardware from
    * drifting apart the way `if_to_axi.v:135-141` records happening on the v1 core. */
  def modelData(bytes: Seq[Int]): Seq[Int] = {
    require(bytes.length % 4 == 0, s"modelData needs whole 32-bit lanes (got ${bytes.length})")
    bytes.grouped(4).flatMap(_.reverse).toSeq
  }

  /** Strobe form of `modelData`: element i = "byte offset i is enabled". */
  def modelStrb(bits: Seq[Boolean]): Seq[Boolean] = {
    require(bits.length % 4 == 0, s"modelStrb needs whole nibbles (got ${bits.length})")
    bits.grouped(4).flatMap(_.reverse).toSeq
  }
}
