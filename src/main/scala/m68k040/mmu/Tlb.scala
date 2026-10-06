package m68k040.mmu

import m68k040.cache.CacheMode
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import m68k040.hw.OneHotSafe

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
  * Lookup selects one flattened bank/set row per way (NOT a full CAM): only
  * that row's `ways` tags are compared. Storage and fill remain banked.
  *
  * Ports (plain wires, driven/read by the owning plugin):
  *  - lookup : drive `lookupVpn`; read `hit` / `hitEntry` (combinational, 1-cycle).
  *  - fill   : pulse `fillValid` with `fillVpn`/`fillEntry`; captured this cycle,
  *             written into the arrays NEXT cycle (`fillBusy` high), visible to a
  *             lookup the cycle after that.
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
          banks: Int = Tlb.DefaultBanks,
          reportEvictions: Boolean = false) extends Component {
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

    // ── REGISTERED FILL (200 MHz, 2026-10-06) ─────────────────────────────────
    // High in the ONE cycle a fill accepted on the previous cycle is being written
    // into the arrays (see "registered fill" below). A lookup in that cycle does NOT
    // see the pending entry, so an owner that must not lose that cycle's answer
    // holds its request off while this is high. A plain flop output.
    val fillBusy = out Bool ()

    // ── EVICTION REPORT (for a second-level / victim buffer) ───────────────────
    // High for exactly the cycle a fill ALLOCATES over a way that was valid, i.e.
    // the one event that loses a live translation. Carries the full key (VPN + FC2)
    // and payload of the way being overwritten, so an owner can keep it somewhere
    // cheaper than a walk. A replace-in-place fill (`flResident`) loses nothing and
    // does NOT report. Combinational off the fill decode that already exists -- it
    // adds a way-mux over the fill row, and nothing at all to the LOOKUP cone that
    // `io.hit` sits in.
    // Gated by `reportEvictions` so a TLB with no victim buffer behind it elaborates
    // NO ports and NO way-mux: the default build is bit-identical to one without this
    // feature, which is the bar a default-OFF flag has to clear here.
    val evictValid = if (reportEvictions) out Bool ()                  else null
    val evictVpn   = if (reportEvictions) out UInt (Tlb.VpnBits bits)  else null
    val evictSup   = if (reportEvictions) out Bool ()                  else null
    val evictEntry = if (reportEvictions) out(TlbEntry())              else null

    // ── MULTI-HOT EVIDENCE, readable over JTAG (DebugRegMap.OFF_MULTIHOT_*) ─────
    // `Tlb` is a real Component, so unlike the caches -- whose probes are plain vals
    // in a plugin Area that DebugCtrlPlugin can reach directly -- these have to cross
    // the boundary as explicit ports. Same shape as `TableWalker.io.dbgPack`, which
    // is the established precedent for a probe living inside a sub-Component.
    //
    // The ITLB and the DTLB are two separate instances of this component and their
    // evidence MUST stay separate: an ITLB multi-hot mistranslates a perfectly correct
    // PC and explains a wild BRANCH TARGET, a DTLB one explains a wild DATA address.
    // Merging them would throw away the single most useful bit of the diagnosis.
    val dbgMultiHotClear  = in Bool ()
    val dbgMultiHotSticky = out Bool ()
    val dbgMultiHotCount  = out UInt (4 bits)
    val dbgMultiHotVpn    = out UInt (32 bits)
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

  // Select bank/set together, rather than muxing every set of a bank first.
  // Row order is bank-major; omit the helper's synthetic bit for singleton axes.
  // This changes only mux topology, not storage, lookup latency or fill/purge.
  val lkRow = if (bankBits == 0) lkSet
              else if (setBits == 0) lkBank
              else (lkBank ## lkSet).asUInt
  def lookupRow[T <: Data](rows: Vec[Vec[Vec[T]]], way: Int): T = {
    val flat = Vec(for (b <- 0 until banks; s <- 0 until nSets) yield rows(b)(way)(s))
    if (banks * nSets == 1) flat(0) else flat(lkRow)
  }

  // Select one row, then compare only `ways` tags (same four as before).
  val hitVec = Vec(Bool(), ways)
  val entVec = Vec(TlbEntry(), ways)
  for (w <- 0 until ways) {
    val v = lookupRow(valids, w)
    val t = lookupRow(tags, w)
    hitVec(w) := v && (t === lkTag) && (lookupRow(tagSup, w) === io.lookupSup)
    val e = TlbEntry()
    e.vpnTag     := t
    e.ppn        := lookupRow(ppns, w)
    e.writeProt  := lookupRow(wProt, w)
    e.supervisor := lookupRow(sup, w)
    e.cacheMode  := lookupRow(cmode, w)
    e.modified   := lookupRow(modif, w)
    entVec(w) := e
  }
  // ── MULTI-HOT FAIL-SAFE (2026-09-17) ────────────────────────────────────────
  // `MuxOH` is defined only for a ONE-HOT select, and on a 2-hot vector it returns
  // the bitwise OR of two entries -- a PPN nobody ever wrote, delivered upward as a
  // valid, NON-FAULTING translation. That is not hypothetical here: the fill-path
  // comment below records `WalkerExcEntryWedgeSpec` catching 0xae0cf000 for a VA
  // whose only descriptor says 0x60008. The FILL end was fixed (replace-in-place,
  // `OHMasking.first`); this is the CONSUMER end.
  //
  // A multi-hot lookup now reports a MISS, which sends the request to a table walk
  // -- an outcome every consumer of this component already handles. `io.hitEntry`
  // is deliberately left as-is: it is read only under `io.hit` (DtlbPlugin.scala's
  // `tlbHit && !needsMRefresh` arm and `needsMRefresh`'s own `tlbHit` term), so the
  // OR-ed entry is still computed and never consumed. Leaving the mux alone is what
  // keeps this free -- see `OneHotSafe` for why the reduction itself costs nothing.
  //
  // FORCING A MISS IS NOT ENOUGH ON ITS OWN. The fill below replaces
  // `OHMasking.first(flResidentVec)`, i.e. the FIRST matching way -- so on a real
  // duplicate the OTHER match survives, the next lookup is multi-hot again, and the
  // walk repeats forever. A forced miss without the purge is a guaranteed livelock.
  // `dupPurge` below is what makes it terminate.
  val lkMultiHot = OneHotSafe.multiHot(hitVec)
  io.hit      := OneHotSafe.exactlyOne(hitVec)
  io.hitEntry := MuxOH(hitVec, entVec)
  // sim-only: the tripwire that keeps the one-hot invariant honest, KEPT so the
  // condition stays loud in simulation rather than being silently absorbed by the
  // fail-safe above. Nothing in the fill path checks whether the VPN is already
  // resident in another way, so two ways of the same (bank,set) could end up holding
  // the same tag.
  val dbgHitCount = CountOne(hitVec); dbgHitCount.simPublic()
  val dbgMultiHot = lkMultiHot; dbgMultiHot.simPublic()
  val dbgPurgeFire = Bool(); dbgPurgeFire := False; dbgPurgeFire.simPublic()

  // Sticky evidence. Latched on DETECTION (`lkMultiHot`), which is also what arms the
  // purge -- so a cleared board that nonetheless shows a set sticky bit proves the
  // condition occurred and was repaired, rather than leaving it invisible. The address
  // captured is the looked-up VPN KEY, i.e. exactly the value the lookup compared, so
  // it can be correlated against the faulting VA without re-deriving the key.
  val dbgEvidence = m68k040.hw.OneHotSafe.evidence(
    lkMultiHot, io.lookupVpn.resize(32), io.dbgMultiHotClear)
  io.dbgMultiHotSticky := dbgEvidence.sticky
  io.dbgMultiHotCount  := dbgEvidence.count
  io.dbgMultiHotVpn    := dbgEvidence.addr
  dbgEvidence.sticky.simPublic(); dbgEvidence.count.simPublic(); dbgEvidence.addr.simPublic()

  // ── DUPLICATE PURGE ─────────────────────────────────────────────────────────
  // Clear the valid bit of EVERY matching way of the looked-up (bank,set). A TLB
  // entry is a pure cache of a page-table descriptor -- the architectural copy,
  // `modified` bit included, lives in memory and `DtlbPlugin.needsMRefresh` already
  // re-walks to refresh it -- so dropping entries can never lose state. Purging ALL
  // matches (rather than all-but-one) is therefore the safest rule here AND the one
  // that provably converges: after the purge the set holds zero matches, the walk
  // fills exactly one, and the next lookup is one-hot. One purge, always.
  //
  // Off the critical path by construction: this is a write into a flop array
  // enabled by a condition that is False in every cycle of normal operation, so it
  // adds nothing to the lookup cone that `io.hit` sits in.
  when(lkMultiHot) {
    dbgPurgeFire := True
    for (b <- 0 until banks; w <- 0 until ways) {
      val bankMatch = if (bankBits == 0) True else lkBank === U(b, bankBits bits)
      when(bankMatch && hitVec(w)) {
        valids(b)(w)(lkSet) := False
      }
    }
  }

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

  // ── REGISTERED FILL (200 MHz, 2026-10-06) ─────────────────────────────────────
  // The fill is CAPTURED in the cycle `io.fillValid` is high (way decode included)
  // and WRITTEN one cycle later from flops. Before this, the fill decode -- key mux,
  // a 17-bit tag compare per way, `OHMasking.first`, the round-robin victim mux and
  // the per-entry decode -- drove the CLOCK ENABLE of every tags/ppns/... flop
  // (~44 per entry, ~1,400 endpoints per TLB), and the routed 200 MHz report named
  // exactly that cone as a wall of failing paths (DTLB `walkIs8K` -> victim select
  // -> `ppns_*/CE`; ITLB MMU-control decode -> walker fill gate -> `tags_*/CE`). Now
  // that whole cone ends at the D pins of `pend.*` (~70 flops), and every array CE
  // is a single registered one-hot bit (`pend.we`).
  //
  // CORRECTNESS OF THE ONE-CYCLE WINDOW:
  //  * LOOKUP in the write cycle misses the pending entry (it is not in the array
  //    yet). `io.fillBusy` (= `pend.valid`, a flop) tells the owner; both plugins
  //    hold the requester off for that cycle. An owner that did not would only see
  //    a MISS, never a wrong translation.
  //  * INVALIDATE: `invalidateAll` is still applied combinationally and is
  //    priority-LAST, so (a) a fill offered in an invalidate cycle is never captured
  //    (`!io.invalidateAll` on the capture), and (b) a fill captured the cycle
  //    BEFORE an invalidate has its valid-bit write overridden in the write cycle.
  //    No pre-flush fill can land after a flush.
  //  * BACK-TO-BACK FILLS (not produced by either plugin today, handled anyway): the
  //    residency test and the round-robin victim are BYPASSED from the pending entry,
  //    so a second fill of the same key replaces the pending way instead of
  //    allocating a duplicate (the one-hot invariant above), and a second fill of
  //    the same row sees the victim pointer the pending fill will leave behind.
  val pend = new Area {
    val valid    = RegInit(False)
    val bank     = Reg(UInt(widthOf(flBank) bits))
    val set      = Reg(UInt(widthOf(flSet) bits))
    val tag      = Reg(UInt(tagBits bits))
    val sup      = Reg(Bool())
    val entry    = Reg(TlbEntry())
    val way      = Reg(UInt(wayBits bits))
    val resident = Reg(Bool())
    // One registered write-enable per entry: the array CE, with no logic behind it.
    val we       = Vec.fill(banks)(Vec.fill(ways)(Vec.fill(nSets)(RegInit(False))))
  }
  pend.valid.simPublic()
  io.fillBusy := pend.valid

  val pendSameRow = pend.valid && (pend.bank === flBank) && (pend.set === flSet)
  val pendSameKey = pendSameRow && (pend.tag === flTag) && (pend.sup === io.fillSup)
  val flResidentArr = flResidentVec.orR
  val flResident = flResidentArr || pendSameKey
  // `OHMasking.first` rather than a bare `OHToUInt`: a PRIORITY pick is well-defined
  // even if a duplicate somehow already exists, so this replacement path can never
  // itself select a way that did not match. `OHToUInt` on a non-one-hot vector is
  // exactly the bug being fixed and must not be reintroduced here.
  val flVictim = Mux(pendSameRow && !pend.resident, pend.way + 1, victim(flBank)(flSet))
  val flWay = Mux(pendSameKey, pend.way,
              Mux(flResidentArr, OHToUInt(OHMasking.first(flResidentVec)), flVictim))
  val flCapture = io.fillValid && !io.invalidateAll

  // Capture: payload flops have NO enable (plain RegNext); only `valid`/`we` gate.
  pend.valid    := flCapture
  pend.bank     := flBank
  pend.set      := flSet
  pend.tag      := flTag
  pend.sup      := io.fillSup
  pend.entry    := io.fillEntry
  pend.way      := flWay
  pend.resident := flResident
  for (b <- 0 until banks; w <- 0 until ways; s <- 0 until nSets) {
    val bankMatch = if (bankBits == 0) True else flBank === U(b, bankBits bits)
    val setMatch  = if (setBits == 0) True else flSet === U(s, setBits bits)
    pend.we(b)(w)(s) := flCapture && bankMatch && setMatch && (flWay === U(w, wayBits bits))
  }

  // Write (one cycle after capture), entirely from flops.
  for (b <- 0 until banks; w <- 0 until ways; s <- 0 until nSets) {
    when(pend.we(b)(w)(s)) {
      valids(b)(w)(s) := True
      tags(b)(w)(s)   := pend.tag
      tagSup(b)(w)(s) := pend.sup
      ppns(b)(w)(s)   := pend.entry.ppn
      wProt(b)(w)(s)  := pend.entry.writeProt
      sup(b)(w)(s)    := pend.entry.supervisor
      cmode(b)(w)(s)  := pend.entry.cacheMode
      modif(b)(w)(s)  := pend.entry.modified
    }
  }
  // Only a genuine ALLOCATION advances the round-robin victim. A replace-in-place
  // must not, or an M-refresh would still churn the victim and evict a live way for
  // no reason.
  when(pend.valid && !pend.resident) { victim(pend.bank)(pend.set) := pend.way + 1 }

  GenerationFlags.simulation {
    assert(CountOne(for (b <- 0 until banks; w <- 0 until ways; s <- 0 until nSets) yield pend.we(b)(w)(s)) <= 1,
      "Tlb: more than one pending fill write-enable is set")
  }

  // ---- eviction report ----
  // Reported in the WRITE cycle, reading the way about to be overwritten out of the
  // registered (pend.bank, pend.set) row -- i.e. the content it holds the instant
  // before the pending write replaces it. `pend.way` is the round-robin victim on an
  // allocation and the resident way on a replace-in-place; only the former can lose
  // anything, which is why `evictValid` carries `!pend.resident`. If `invalidateAll`
  // lands in this same cycle the owner flushes whatever it captured (the ITLB victim
  // buffer's flush is priority-last over its insert).
  if (reportEvictions) {
    def fillRow[T <: Data](rows: Vec[Vec[Vec[T]]], way: Int): T = {
      val flat = Vec(for (b <- 0 until banks; s <- 0 until nSets) yield rows(b)(way)(s))
      val row  = if (bankBits == 0) pend.set
                 else if (setBits == 0) pend.bank
                 else (pend.bank ## pend.set).asUInt
      if (banks * nSets == 1) flat(0) else flat(row)
    }
    val evValidVec = Vec(Bool(), ways)
    val evSupVec   = Vec(Bool(), ways)
    val evTagVec   = Vec(UInt(tagBits bits), ways)
    val evEntVec   = Vec(TlbEntry(), ways)
    for (w <- 0 until ways) {
      evValidVec(w) := fillRow(valids, w)
      evSupVec(w)   := fillRow(tagSup, w)
      evTagVec(w)   := fillRow(tags, w)
      val e = TlbEntry()
      e.vpnTag     := fillRow(tags, w)
      e.ppn        := fillRow(ppns, w)
      e.writeProt  := fillRow(wProt, w)
      e.supervisor := fillRow(sup, w)
      e.cacheMode  := fillRow(cmode, w)
      e.modified   := fillRow(modif, w)
      evEntVec(w) := e
    }
    io.evictValid := pend.valid && !pend.resident && evValidVec(pend.way)
    io.evictSup   := evSupVec(pend.way)
    io.evictEntry := evEntVec(pend.way)
    // Reassemble the key the evicted way was filed under: vpn = tag ## set ## bank
    // (the exact inverse of `tagOf`/`setOf`/`bankOf`).
    io.evictVpn := {
      val tag = evTagVec(pend.way)
      val withSet  = if (setBits == 0) tag.asBits else (tag ## pend.set)
      val withBank = if (bankBits == 0) withSet else (withSet ## pend.bank)
      withBank.asUInt.resize(Tlb.VpnBits)
    }
  }

  // ---- invalidateAll (priority clear) ----
  when(io.invalidateAll) {
    for (b <- 0 until banks; w <- 0 until ways; s <- 0 until nSets) valids(b)(w)(s) := False
  }
}
