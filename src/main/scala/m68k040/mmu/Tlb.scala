package m68k040.mmu

import m68k040.cache.CacheMode
import spinal.core._
import spinal.lib._

/** One TLB (address translation cache) entry. `valid` is held in a separate
  * RegInit'd Vec (cleared by invalidateAll); the rest live in a per-way Reg array.
  *  - vpnTag  : the high VPN bits not used for bank/set selection.
  *  - ppn     : physical page number (addr[31:12]).
  *  - writeProt: page is write-protected (a write access faults).
  *  - supervisor: page requires supervisor access (a user access faults).
  *  - cacheMode: cacheable / inhibited. */
case class TlbEntry() extends Bundle {
  val vpnTag     = UInt(Tlb.tagBitsFor(Tlb.DefaultEntries, Tlb.DefaultWays, Tlb.DefaultBanks) bits)
  val ppn        = UInt(20 bits)
  val writeProt  = Bool()
  val supervisor = Bool()
  val cacheMode  = CacheMode()
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
  * All valid bits are RegInit(False); the round-robin victim per (bank,set) is a
  * RegInit. No uninitialised state. */
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
    val hit       = out Bool ()
    val hitEntry  = out(TlbEntry())

    val fillValid = in Bool ()
    val fillVpn   = in UInt (Tlb.VpnBits bits)
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
  // round-robin victim per (bank,set)
  val victim  = Vec.fill(banks)(Vec.fill(nSets)(RegInit(U(0, wayBits bits))))

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
    hitVec(w) := v && (t === lkTag)
    val e = TlbEntry()
    e.vpnTag     := t
    e.ppn        := ppns(lkBank)(w)(lkSet)
    e.writeProt  := wProt(lkBank)(w)(lkSet)
    e.supervisor := sup(lkBank)(w)(lkSet)
    e.cacheMode  := cmode(lkBank)(w)(lkSet)
    entVec(w) := e
  }
  io.hit      := hitVec.orR
  io.hitEntry := MuxOH(hitVec, entVec)

  // ---- fill (round-robin victim within the target bank/set) ----
  val flBank = bankOf(io.fillVpn)
  val flSet  = setOf(io.fillVpn)
  val flTag  = tagOf(io.fillVpn)
  val flWay  = victim(flBank)(flSet)
  when(io.fillValid) {
    for (b <- 0 until banks; w <- 0 until ways) {
      val bankMatch = if (bankBits == 0) True else flBank === U(b, bankBits bits)
      when(bankMatch && (flWay === U(w, wayBits bits))) {
        valids(b)(w)(flSet) := True
        tags(b)(w)(flSet)   := flTag
        ppns(b)(w)(flSet)   := io.fillEntry.ppn
        wProt(b)(w)(flSet)  := io.fillEntry.writeProt
        sup(b)(w)(flSet)    := io.fillEntry.supervisor
        cmode(b)(w)(flSet)  := io.fillEntry.cacheMode
      }
    }
    victim(flBank)(flSet) := flWay + 1
  }

  // ---- invalidateAll (priority clear) ----
  when(io.invalidateAll) {
    for (b <- 0 until banks; w <- 0 until ways; s <- 0 until nSets) valids(b)(w)(s) := False
  }
}
