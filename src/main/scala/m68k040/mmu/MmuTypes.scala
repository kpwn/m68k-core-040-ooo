package m68k040.mmu

import m68k040.cache.CacheMode
import spinal.core._

/** 68040 MMU descriptor formats + virtual-address field decomposition for the
  * D-side hardware table walker (long-format descriptors only — the only format
  * the 68040 uses). Task #195: both real MC68040 page sizes are supported, gated
  * by TCR.P (`MmuControlService.pageSize8K`) — root/pointer index widths (7/7 bits)
  * never change; only the pointer->page-table boundary shifts by one bit.
  *
  * === Virtual address (4 KB pages, TCR.P=0) ===
  *   VA[31:25] root index   (7 bits, 128 root-table entries)
  *   VA[24:18] pointer index(7 bits, 128 pointer-table entries)
  *   VA[17:12] page index   (6 bits,  64 page-table entries)
  *   VA[11:0]  page offset   (12 bits)
  * (7 + 7 + 6 + 12 = 32; matches the m68040 PRM TIA/TIB/TIC = 7/7/6 for 4 KB.)
  *
  * === Virtual address (8 KB pages, TCR.P=1) ===
  *   VA[31:25] root index   (7 bits, 128 root-table entries)
  *   VA[24:18] pointer index(7 bits, 128 pointer-table entries)
  *   VA[17:13] page index   (5 bits,  32 page-table entries)
  *   VA[12:0]  page offset   (13 bits)
  * (7 + 7 + 5 + 13 = 32; MC68040 UM S3.1.2/Fig 3-9. The page descriptor's upper 19
  * bits are the PPN; descriptor bit 12 is architecturally undefined in this mode —
  * PA[12] instead comes straight from the untranslated VA[12], same as any other
  * offset bit.)
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
  // The three upper attribute bits the ATC does NOT cache but MMUSR must report.
  // MC68040 UM Fig 3-11, long-format page descriptor:
  //   [11] UR (user reserved)  [10] G (global)  [9] U1  [8] U0
  def pgUserReserved(d: Bits): Bool = d(11)
  def pgGlobal(d: Bits): Bool       = d(10)
  def pgU1(d: Bits): Bool           = d(9)
  def pgU0(d: Bits): Bool           = d(8)

  // ---- table-search byte offsets -------------------------------------------------
  // SHARED so a second table-search implementation cannot drift from `TableWalker`'s.
  // Both `TableWalker` and `ExceptionUnit`'s PTEST search read these; a change to the
  // index arithmetic is now a change in ONE place. (Before PTEST existed the
  // arithmetic was inline in TableWalker's three states; the bodies below are those
  // expressions moved verbatim, not re-derived.)
  /** Root-table byte offset: rootIdx(7) * 4. */
  def rootOffset(vpn: UInt): UInt = (vpn(19 downto 13) ## U(0, 2 bits)).asUInt
  /** Pointer-table byte offset: ptrIdx(7) * 4. */
  def ptrOffset(vpn: UInt): UInt  = (vpn(12 downto 6) ## U(0, 2 bits)).asUInt
  /** Page-table byte offset: PGI * 4 -- 6-bit PGI (VA[17:12]) at 4 KB, 5-bit
    * (VA[17:13]) at 8 KB. Both branches are the same 8-bit width. */
  def pageOffset(vpn: UInt, is8K: Bool): UInt = Mux(is8K,
    (U(0, 1 bits) ## vpn(5 downto 1) ## U(0, 2 bits)).asUInt,
    (vpn(5 downto 0) ## U(0, 2 bits)).asUInt)
}

/** MC68040 MMU STATUS REGISTER (MMUSR), UM Fig 3-13.
  *
  *   31       12 11 10  9  8  7  6  5  4  3  2  1  0
  *   [   PA    ][B ][G][U1][U0][S][ CM ][M][0][W][T][R]
  *
  * It is DELIBERATELY the long-format page descriptor with three substitutions --
  * [11] UR becomes B (bus error), [3] U becomes 0, and [1:0] PDT becomes {T, R} --
  * which is why `fromPageDesc` below is a field-for-field re-tag of the descriptor
  * rather than a table of unrelated bits.
  *
  *   R  resident: the table search completed and found a resident page descriptor
  *   T  transparent: a TTR matched, so no table search was performed
  *   W  write protected: the OR of every W bit down the search (table + page)
  *   M  modified, S supervisor-only, CM cache mode, U0/U1 user bits, G global:
  *      straight out of the leaf page descriptor
  *   B  a descriptor READ took a bus error
  *
  * A Unix fault handler distinguishes "not present" from "protection violation" as
  * `R == 0` versus `R == 1 && (W or S)`; that distinction is the reason this register
  * has to be real. */
object MmuSr {
  /** Compose MMUSR from a completed table search.
    *
    * @param pa        the translated physical address (page-aligned; the offset bits
    *                  are the VA's own, mirroring the load path's PA assembly)
    * @param desc      the leaf PAGE descriptor, verbatim
    * @param writeProt W accumulated down the whole search, not just the leaf's own bit
    * @param resident  the search found a resident page descriptor
    */
  def fromPageDesc(pa: UInt, desc: Bits, writeProt: Bool, resident: Bool): Bits =
    pa(31 downto 12).asBits ##          // [31:12] PA
    False ##                            // [11]    B  (no bus error on this path)
    desc(10 downto 8) ##                // [10:8]  G, U1, U0
    desc(7) ##                          // [7]     S
    desc(6 downto 5) ##                 // [6:5]   CM
    desc(4) ##                          // [4]     M
    False ##                            // [3]     always zero
    writeProt ##                        // [2]     W (accumulated)
    False ##                            // [1]     T (a table search, not a TTR hit)
    resident                            // [0]     R

  /** MMUSR for a search that never reached a resident page descriptor -- an invalid
    * or non-resident descriptor at any level, or a descriptor read that bus-errored.
    * R = 0 is the answer a page-fault handler acts on; W is still reported because the
    * levels already walked accumulated it. */
  def notResident(writeProt: Bool, busError: Bool): Bits =
    B(0, 20 bits) ##                    // [31:12] PA  (no translation was produced)
    busError ##                         // [11]    B
    B(0, 8 bits) ##                     // [10:3]  G, U1, U0, S, CM, M, 0
    writeProt ##                        // [2]     W (accumulated by the levels walked)
    False ##                            // [1]     T
    False                               // [0]     R = 0 -- NOT PRESENT

  /** MMUSR for a TRANSPARENT-translation hit: T and R set, PA = VA, CM from the TTR.
    * No table search runs, so G/U1/U0/S/M/W have no descriptor to come from and read
    * zero (MC68040 UM: on a TTR hit the remaining status bits are not meaningful). */
  def transparent(va: UInt, cm: Bits): Bits =
    va(31 downto 12).asBits ## B(0, 5 bits) ## cm ## B(0, 3 bits) ## True ## True
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
  // TCR.P (task #195): 8KB-page mode for THIS walk (latched by the owning TLB
  // plugin at miss-capture time, mirroring isWrite/isSuper). Only the pointer->page
  // offset computation (5-bit vs 6-bit PGI) reads this — root/pointer index widths
  // are always 7 bits regardless of page size (MC68040 UM S3.1.2).
  val is8K       = Bool()
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
  // Task #210: the leaf page descriptor's M (modified) bit as it stands AFTER this
  // walk's own U/M update (i.e. `pgModified(d) || isWrite` — a write access always
  // sets M). This is what the owning TLB should cache per-entry so a later
  // write-hit can tell "M is already set in memory, no re-walk needed" from "M is
  // still clear, a table search must run" without re-reading the descriptor.
  val modified    = Bool()
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

  /** CM[6:5] decoded via CacheMode.decode — same field position as a page
    * descriptor's CM (MmuDesc.pgCacheMode). Callers must check `hit`/`inhibited`
    * separately when they only need the boolean; this is for producers that
    * need the full 3-way mode (DtlbPlugin's DTT hit response). */
  def cacheMode(ttr: UInt): CacheMode.C =
    CacheMode.decode(ttr(6 downto 5).asBits)
}
