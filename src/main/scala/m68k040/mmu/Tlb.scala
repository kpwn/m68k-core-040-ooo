package m68k040.mmu

import m68k040.cache.CacheMode
import spinal.core._
import spinal.core.sim._
import spinal.lib._

/** One TLB (address translation cache) entry. `valid` is held in a separate
  * RegInit'd Vec (cleared by invalidateAll); the rest live in a per-way Reg array.
  *  - vpnTag  : the high VPN bits not used for bank/set selection.
  *  - ppn     : physical page number (addr[31:12]).
  *  - writeProt: page is write-protected (a write access faults).
  *  - supervisor: page requires supervisor access (a user access faults).
  *  - cacheMode: cacheable / inhibited.
  *  - modified : task #210 -- the leaf descriptor's M bit as of this entry's last
  *    fill (mirrors `WalkRsp.modified`: already reflects any write-triggered set).
  *    A write-hit against an entry with `modified=false` must trigger a real
  *    table-search re-walk to set M in memory + here (MC68040 UM S3.3); once set,
  *    later write-hits to the SAME entry must not re-walk again. */
case class TlbEntry() extends Bundle {
  val vpnTag     = UInt(Tlb.tagBitsFor(Tlb.DefaultEntries, Tlb.DefaultWays, Tlb.DefaultBanks) bits)
  val ppn        = UInt(20 bits)
  val writeProt  = Bool()
  val supervisor = Bool()
  val cacheMode  = CacheMode()
  val modified   = Bool()
}

object Tlb {
  val DefaultEntries = 32
  val DefaultWays    = 4
  val DefaultBanks   = 2
  val VpnBits        = 20

  def bankBits(banks: Int): Int = log2Up(banks)
  def setsPerBank(entries: Int, ways: Int, banks: Int): Int = entries / (ways * banks)
  def setBits(entries: Int, ways: Int, banks: Int): Int = log2Up(setsPerBank(entries, ways, banks))
  def tagBitsFor(entries: Int, ways: Int, banks: Int): Int =
    VpnBits - bankBits(banks) - setBits(entries, ways, banks)
}

/** Banked set-associative ATC (address translation cache / TLB).
  *
  * Geometry (parametric): `entries` total, `ways`-way set-associative, split over
  * `banks` banks. `setsPerBank = entries/(ways*banks)`. The VPN is decomposed as
  *   bank = vpn[bankBits-1:0]
  *   set  = vpn[bankBits+setBits-1:bankBits]
  *   tag  = vpn[19:bankBits+setBits]
  * Lookup is a SHALLOW per-bank way-mux (NOT a deep CAM): the bank is selected
  * combinationally and only that bank's `ways` tags are compared — FPGA-friendly.
  *
  * Ports (plain wires, driven/read by the owning plugin):
  *  - lookup : drive `lookupVpn`; read `hit` / `hitEntry` (combinational, 1-cycle).
  *  - fill   : pulse `fillValid` with `fillVpn`/`fillEntry`; written this cycle.
  *  - invalidateAll : pulse to clear all valid bits (PFLUSH-style).
  *
  * All valid bits are RegInit(False) and the round-robin victim per (bank,set) is a
  * RegInit, so no entry can be READ before it is written. `tags`/`ppns` and the other
  * payload arrays are deliberately plain `Reg` (no init) -- they are only ever reachable
  * through a set `valid` bit. NOTE: that guarantee depends on `hitVec` being ONE-HOT,
  * which is why the fill replaces a resident way rather than allocating a duplicate;
  * see the fill block. */
class Tlb(entries: Int = Tlb.DefaultEntries,
          ways: Int = Tlb.DefaultWays,
          banks: Int = Tlb.DefaultBanks) extends Component {
  require(isPow2(banks), "banks must be a power of two")
  require(isPow2(ways), "ways must be a power of two")
  require(entries % (ways * banks) == 0, "entries must divide evenly into ways*banks")
  require(isPow2(entries / (ways * banks)), "sets-per-bank must be a power of two")

  val bankBits = Tlb.bankBits(banks)
  val nSets    = Tlb.setsPerBank(entries, ways, banks)
  val setBits  = Tlb.setBits(entries, ways, banks)
  val tagBits  = Tlb.tagBitsFor(entries, ways, banks)
  val wayBits  = log2Up(ways)

  val io = new Bundle {
    val lookupVpn = in UInt (Tlb.VpnBits bits)
    // FC2 -- the address-space half of the tag. See `tagSup` below.
    val lookupSup = in Bool ()
    val hit       = out Bool ()
    val hitEntry  = out(TlbEntry())

    val fillValid = in Bool ()
    val fillVpn   = in UInt (Tlb.VpnBits bits)
    val fillSup   = in Bool ()
    val fillEntry = in(TlbEntry())

    val invalidateAll = in Bool ()
  }

  // ---- VPN decomposition helpers ----
  def bankOf(vpn: UInt): UInt = if (bankBits == 0) U(0, 1 bits) else vpn(bankBits - 1 downto 0)
  def setOf(vpn: UInt): UInt  = if (setBits == 0) U(0, 1 bits) else vpn(bankBits + setBits - 1 downto bankBits)
  def tagOf(vpn: UInt): UInt  = vpn(Tlb.VpnBits - 1 downto bankBits + setBits)

  // ---- storage: per [bank][way][set] ----
  // valid bits are a RegInit'd Vec so invalidateAll can clear them cheaply.
  val valids  = Vec.fill(banks)(Vec.fill(ways)(Vec.fill(nSets)(RegInit(False))))
  val tags    = Vec.fill(banks)(Vec.fill(ways)(Vec.fill(nSets)(Reg(UInt(tagBits bits)))))
  val ppns    = Vec.fill(banks)(Vec.fill(ways)(Vec.fill(nSets)(Reg(UInt(20 bits)))))
  val wProt   = Vec.fill(banks)(Vec.fill(ways)(Vec.fill(nSets)(RegInit(False))))
  val sup     = Vec.fill(banks)(Vec.fill(ways)(Vec.fill(nSets)(RegInit(False))))
  val cmode   = Vec.fill(banks)(Vec.fill(ways)(Vec.fill(nSets)(RegInit(CacheMode.WRITETHROUGH))))
  // ---- FC2: THE ADDRESS-SPACE HALF OF THE TAG (2026-09-09) --------------------
  // NOT the same thing as `sup` above, and the distinction is the whole point:
  //   * `sup`   is the PAGE's supervisor-only PROTECTION attribute, read out of the
  //             leaf descriptor and returned as DATA (`hitEntry.supervisor`), which
  //             the owning plugin then checks the access against.
  //   * `tagSup` is the FUNCTION CODE the entry was FILLED under, and it is
  //             COMPARED. It is part of the tag, exactly as on real silicon: the
  //             MC68040's ATC tag is {V, logical address A31-A12, FC2} (UM S3.3).
  //
  // Without it the ATC cannot hold a user-space and a supervisor-space translation
  // of the SAME virtual page at once -- the first one filled answers both -- and
  // those two translations are genuinely different objects whenever URP =/= SRP,
  // because the two roots index independent table trees. That was a latent bug for
  // any URP =/= SRP configuration with user code running (the live board has exactly
  // that: SRP = 0x03FFFA00, URP = 0), and it became a BLOCKING one the moment MOVES
  // started honouring SFC/DFC: supervisor code can now issue a user-space access and
  // an ordinary supervisor access to one address in consecutive instructions, and
  // with a VPN-only tag the second silently inherits the first one's PPN.
  //
  // COST ON THE LOOKUP PATH: one extra input to an equality that already reduces
  // `tagBits` (17) bits. It widens an existing comparator by one bit; it adds no
  // level to the way-mux, no second lookup and no CAM entry.
  val tagSup  = Vec.fill(banks)(Vec.fill(ways)(Vec.fill(nSets)(RegInit(False))))
  // Task #210: per-entry M-bit shadow (see TlbEntry doc).
  val modif   = Vec.fill(banks)(Vec.fill(ways)(Vec.fill(nSets)(RegInit(False))))
  // round-robin victim per (bank,set)
  val victim  = Vec.fill(banks)(Vec.fill(nSets)(RegInit(U(0, wayBits bits))))
  // Sim-only: expose the valid bits so a test can COUNT occupancy per bank. Added
  // for the 8 KB-page capacity fix (`DtlbPlugin.tlbKey`), whose whole claim is
  // "bank 1 is never used" -- a claim only an occupancy census can settle. These are
  // already registers with real consumers, so `simPublic` adds nothing to a netlist.
  valids.foreach(_.foreach(_.foreach(_.simPublic())))
  tags.foreach(_.foreach(_.foreach(_.simPublic())))
  tagSup.foreach(_.foreach(_.foreach(_.simPublic())))

  // ---- combinational lookup ----
  val lkBank = bankOf(io.lookupVpn)
  val lkSet  = setOf(io.lookupVpn)
  val lkTag  = tagOf(io.lookupVpn)

  // Shallow per-bank way-mux: select the bank's entries, compare only `ways` tags.
  val hitVec = Vec(Bool(), ways)
  val entVec = Vec(TlbEntry(), ways)
  for (w <- 0 until ways) {
    val v = valids(lkBank)(w)(lkSet)
    val t = tags(lkBank)(w)(lkSet)
    hitVec(w) := v && (t === lkTag) && (tagSup(lkBank)(w)(lkSet) === io.lookupSup)
    val e = TlbEntry()
    e.vpnTag     := t
    e.ppn        := ppns(lkBank)(w)(lkSet)
    e.writeProt  := wProt(lkBank)(w)(lkSet)
    e.supervisor := sup(lkBank)(w)(lkSet)
    e.cacheMode  := cmode(lkBank)(w)(lkSet)
    e.modified   := modif(lkBank)(w)(lkSet)
    entVec(w) := e
  }
  io.hit      := hitVec.orR
  io.hitEntry := MuxOH(hitVec, entVec)
  // sim-only: `MuxOH` is only defined for a ONE-HOT select. Nothing in the fill path
  // checks whether the VPN is already resident in another way, so two ways of the same
  // (bank,set) could end up holding the same tag -- and then `MuxOH` returns the
  // contents of a way that did not match at all (its index is derived by OR-ing the set
  // bit positions, so ways {0,1,2} select index 3). The fill below now replaces a
  // resident way instead of allocating a duplicate; this counter stays as the tripwire
  // that keeps that invariant honest.
  val dbgHitCount = CountOne(hitVec); dbgHitCount.simPublic()

  // ---- fill (replace-in-place if resident, else round-robin victim) ----
  //
  // A fill of a VPN that is ALREADY RESIDENT must REPLACE that way and must NOT
  // allocate a second one.  Two ways of one set holding the same tag make the lookup's
  // `hitVec` non-one-hot, and `MuxOH` is only defined for a one-hot select: measured on
  // this component (`TlbDuplicateFillSpec`), three fills of one VPN make three ways
  // match and the lookup then returns the contents of a way that never matched at all
  // -- a PPN nobody ever wrote, delivered upward as a valid, NON-FAULTING translation.
  // On the full core that reaches AXI as a read of an unrelated physical page
  // (`WalkerExcEntryWedgeSpec` caught 0xae0cf000 for a VA whose only descriptor says
  // 0x60008) with no fault reported by the MMU, the D-cache or the walker.
  //
  // Refilling a resident VPN is reachable BY DESIGN, not just in theory:
  // `DtlbPlugin.needsMRefresh` (task #210, MC68040 UM S3.3) deliberately re-walks an
  // already-resident VPN when a write hits an entry whose `modified` bit is clear, and
  // that re-walk ends in this very fill.  So an ordinary read-then-write to a page whose
  // leaf descriptor starts M=0 duplicates that page's entry.
  val flBank = bankOf(io.fillVpn)
  val flSet  = setOf(io.fillVpn)
  val flTag  = tagOf(io.fillVpn)
  val flResidentVec = Vec(Bool(), ways)
  for (w <- 0 until ways) {
    // The residency test MUST use the SAME key the lookup does, FC2 included. If it
    // did not, a user-space fill would REPLACE the supervisor-space entry for the
    // same VPN (and vice versa) instead of allocating beside it -- which would undo
    // the tag bit above for the exact interleaving it exists to serve.
    flResidentVec(w) := valids(flBank)(w)(flSet) && (tags(flBank)(w)(flSet) === flTag) &&
                        (tagSup(flBank)(w)(flSet) === io.fillSup)
  }
  val flResident = flResidentVec.orR
  // `OHMasking.first` rather than a bare `OHToUInt`: a PRIORITY pick is well-defined
  // even if a duplicate somehow already exists, so this replacement path can never
  // itself select a way that did not match. `OHToUInt` on a non-one-hot vector is
  // exactly the bug being fixed and must not be reintroduced here.
  val flWay = Mux(flResident, OHToUInt(OHMasking.first(flResidentVec)),
                  victim(flBank)(flSet))
  when(io.fillValid) {
    for (b <- 0 until banks; w <- 0 until ways) {
      val bankMatch = if (bankBits == 0) True else flBank === U(b, bankBits bits)
      when(bankMatch && (flWay === U(w, wayBits bits))) {
        valids(b)(w)(flSet) := True
        tags(b)(w)(flSet)   := flTag
        tagSup(b)(w)(flSet) := io.fillSup
        ppns(b)(w)(flSet)   := io.fillEntry.ppn
        wProt(b)(w)(flSet)  := io.fillEntry.writeProt
        sup(b)(w)(flSet)    := io.fillEntry.supervisor
        cmode(b)(w)(flSet)  := io.fillEntry.cacheMode
        modif(b)(w)(flSet)  := io.fillEntry.modified
      }
    }
    // Only a genuine ALLOCATION advances the round-robin victim. A replace-in-place
    // must not, or an M-refresh would still churn the victim and evict a live way for
    // no reason.
    when(!flResident) { victim(flBank)(flSet) := flWay + 1 }
  }

  // ---- invalidateAll (priority clear) ----
  when(io.invalidateAll) {
    for (b <- 0 until banks; w <- 0 until ways; s <- 0 until nSets) valids(b)(w)(s) := False
  }
}
