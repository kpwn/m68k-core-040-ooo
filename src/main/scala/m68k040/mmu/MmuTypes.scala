package m68k040.mmu

import m68k040.cache.CacheMode
import spinal.core._

/** 68040 MMU descriptor formats + virtual-address field decomposition for the
  * D-side hardware table walker (4 KB pages, long-format descriptors only — the
  * only format the 68040 uses).
  *
  * === Virtual address (4 KB pages) ===
  *   VA[31:25] root index   (7 bits, 128 root-table entries)
  *   VA[24:18] pointer index(7 bits, 128 pointer-table entries)
  *   VA[17:12] page index   (6 bits,  64 page-table entries)
  *   VA[11:0]  page offset   (12 bits)
  * (7 + 7 + 6 + 12 = 32; matches the m68040 PRM TIA/TIB/TIC = 7/7/6 for 4 KB.)
  *
  * === Long-format TABLE descriptor (root & pointer levels, 32 bits) ===
  *   [1:0]  UDT  (upper-level descriptor type): 00,01 = INVALID; 10,11 = RESIDENT
  *   [2]    W    (write protect — accumulates down the walk)
  *   [3]    U    (used; set by the walk — deferred descriptor write)
  *   [31:4] table address (next table base; low bits zero per table alignment)
  *
  * === Long-format PAGE descriptor (leaf, 32 bits) ===
  *   [1:0]   PDT (page descriptor type): 00 = INVALID; 01,11 = RESIDENT; 10 = INDIRECT
  *   [2]     W   (write protect)
  *   [3]     U   (used; set by the walk)
  *   [4]     M   (modified; set on a write access — deferred descriptor write)
  *   [6:5]   CM  (cache mode): 0b00/0b01 = cacheable; 0b10/0b11 = non-cacheable (inhibited)
  *   [7]     S   (supervisor protect — a user access faults)
  *   [31:12] PPN (physical page frame number)
  */
object MmuDesc {
  // ---- VA field widths ----
  val RootIdxBits = 7
  val PtrIdxBits  = 7
  val PageIdxBits = 6
  val OffsetBits  = 12

  def rootIdx(va: UInt): UInt = va(31 downto 25)                 // 7 bits
  def ptrIdx(va: UInt): UInt  = va(24 downto 18)                 // 7 bits
  def pageIdx(va: UInt): UInt = va(17 downto 12)                 // 6 bits
  def vpn(va: UInt): UInt     = va(31 downto 12)                 // 20 bits

  // ---- descriptor bit accessors ----
  // Table (root/pointer) descriptor
  def tblUdt(d: Bits): Bits        = d(1 downto 0)               // 00/01 invalid, 10/11 resident
  def tblResident(d: Bits): Bool   = d(1)                        // UDT high bit set == resident
  def tblWriteProt(d: Bits): Bool  = d(2)
  def tblUsed(d: Bits): Bool       = d(3)
  /** Next-table base: address field bits [31:4], low 4 bits zero. */
  def tblNextBase(d: Bits): UInt   = (d(31 downto 4) ## B(0, 4 bits)).asUInt

  // Page (leaf) descriptor
  def pgPdt(d: Bits): Bits         = d(1 downto 0)
  def pgInvalid(d: Bits): Bool     = d(1 downto 0) === B"00"
  def pgIndirect(d: Bits): Bool    = d(1 downto 0) === B"10"
  def pgResident(d: Bits): Bool    = (d(1 downto 0) === B"01") || (d(1 downto 0) === B"11")
  def pgWriteProt(d: Bits): Bool   = d(2)
  def pgUsed(d: Bits): Bool        = d(3)
  def pgModified(d: Bits): Bool    = d(4)
  def pgCacheMode(d: Bits): Bits   = d(6 downto 5)
  /** CM[1] set => non-cacheable / inhibited (precise-/imprecise- writethrough both
    * cacheable for our load path; 0b10/0b11 are the non-cacheable modes). */
  def pgInhibited(d: Bits): Bool   = d(6)
  def pgSupervisor(d: Bits): Bool  = d(7)
  def pgPpn(d: Bits): UInt         = d(31 downto 12).asUInt
}

/** Fault reasons flagged by a walk (no exception delivery this slice). */
object MmuFaultReason extends SpinalEnum {
  val NONE, NON_RESIDENT, WRITE_PROTECT, SUPERVISOR = newElement()
}

/** Walker request: a VA's VPN-derived indices, the root pointer, and the access
  * class (write?/supervisor?). */
case class WalkReq() extends Bundle {
  val vpn        = UInt(20 bits)
  val rootPtr    = UInt(32 bits)   // URP/SRP base
  val isWrite    = Bool()
  val isSuper    = Bool()
}

/** Walker result: the translated PPN + accumulated perms + fault flag, plus up to
  * two deferred descriptor-byte writes (set-U on the page descriptor; set-M on a
  * write). Each write is {addr, newByte}; `valid` gates it. */
case class WalkUmWrite() extends Bundle {
  val valid   = Bool()
  val addr    = UInt(32 bits)   // byte address of the descriptor byte to RMW
  val newByte = Bits(8 bits)    // the new value of that byte (old | set-bits)
}

case class WalkRsp() extends Bundle {
  val ppn         = UInt(20 bits)
  val writeProt   = Bool()
  val supervisor  = Bool()
  val cacheMode   = CacheMode()
  val fault       = Bool()
  val faultReason = MmuFaultReason()
  // deferred descriptor writes produced by this walk (U on the page descriptor;
  // M on a write access). Folded into one byte write at the page-descriptor's low
  // byte (the byte holding U/M/PDT bits).
  val umWrite     = WalkUmWrite()
}

/** Transparent-translation register (TTR) match logic (task #194): ITT0/ITT1 (I-side)
  * and DTT0/DTT1 (D-side) each carry a 32-bit register in the SAME field layout as a
  * long-format page descriptor's upper attribute byte (MC68040 UM S3.1.2):
  *
  *   [31:24] base   — compared against the access VA's [31:24]
  *   [23:16] mask   — a SET bit means "don't care" (ignore that base bit in the compare)
  *   [15]    E      — enable (0 = this TTR is disabled, never matches)
  *   [14:13] S      — supervisor field: 00 = match user-mode only, 01 = match
  *                    supervisor-mode only, 1x = match either mode
  *   [6:5]   CM     — cache mode; CM[1] (bit 6) set = non-cacheable (mirrors
  *                    MmuDesc.pgInhibited's bit position exactly)
  *
  * A matching, enabled TTR makes the access TRANSPARENT: the walker/TLB are bypassed
  * entirely, PA=VA, and the access never faults (no page table is even consulted).
  * Per the 68040 PRM, DTT0 has priority over DTT1 (ITT0 over ITT1) when both match —
  * callers check `hit(ttr0, ...)` before `hit(ttr1, ...)`. */
object TtMatch {
  /** True when `ttr` transparently covers a VA (given as its top byte, VA[31:24] —
    * that's all a TTR's base/mask fields ever compare against) for an access of the
    * given supervisor-ness. */
  def hit(ttr: UInt, vaHi8: UInt, isSuper: Bool): Bool = {
    val e      = ttr(15)
    val sHigh  = ttr(14)
    val sLow   = ttr(13)
    val base   = ttr(31 downto 24)
    val mask   = ttr(23 downto 16)
    val baseMatch = ((vaHi8 ^ base) & ~mask) === U(0, 8 bits)
    val sMatch    = sHigh || (sLow === isSuper)
    e && baseMatch && sMatch
  }

  /** CM[1] (bit 6) — non-cacheable/inhibited, same bit position as a page
    * descriptor's `pgInhibited` (MmuDesc.pgInhibited). */
  def inhibited(ttr: UInt): Bool = ttr(6)
}
