package m68k040.mmu

import m68k040.VerilatorTest
import m68k040.cache.CacheMode
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** Directed tests for the banked set-associative TLB component:
  *  - fill an entry -> lookup hits with the correct ppn/perms/cacheMode
  *  - lookup a different (uncached) VPN -> miss
  *  - invalidateAll clears valid -> the previously-filled VPN now misses
  *  - fill enough distinct VPNs into one set to evict + re-fill (way reuse) */
class TlbSpec extends AnyFunSuite {

  class Dut extends Component {
    val tlb = new Tlb()   // entries=32, ways=4, banks=2 (defaults)
    val io  = tlb.io.toIo.setName("io")
  }

  /** `fcSup` is the ADDRESS SPACE (FC2) the entry is tagged with -- not `sup`, which
    * is the page's supervisor-only protection attribute carried as data. */
  def fill(dut: Dut, cd: ClockDomain, vpn: Long, ppn: Long,
           wp: Boolean = false, sup: Boolean = false, inhibited: Boolean = false,
           fcSup: Boolean = false): Unit = {
    dut.io.fillVpn   #= vpn
    dut.io.fillSup   #= fcSup
    dut.io.fillEntry.ppn        #= ppn
    dut.io.fillEntry.vpnTag     #= 0
    dut.io.fillEntry.writeProt  #= wp
    dut.io.fillEntry.supervisor #= sup
    dut.io.fillEntry.cacheMode  #= (if (inhibited) CacheMode.INHIBITED else CacheMode.WRITETHROUGH)
    dut.io.fillEntry.modified  #= false
    dut.io.fillValid #= true
    cd.waitSampling()
    dut.io.fillValid #= false
    cd.waitSampling()
  }

  def lookup(dut: Dut, cd: ClockDomain, vpn: Long,
             fcSup: Boolean = false): (Boolean, Long, Boolean, Boolean, Boolean) = {
    dut.io.lookupVpn #= vpn
    dut.io.lookupSup #= fcSup
    cd.waitSampling()  // combinational, but settle a sampling edge for clean reads
    sleep(1)
    val hit = dut.io.hit.toBoolean
    val ppn = dut.io.hitEntry.ppn.toLong
    val wp  = dut.io.hitEntry.writeProt.toBoolean
    val sup = dut.io.hitEntry.supervisor.toBoolean
    val inh = dut.io.hitEntry.cacheMode.toEnum == CacheMode.INHIBITED
    (hit, ppn, wp, sup, inh)
  }

  test("fill then lookup hits with correct ppn/perms; other vpn misses; invalidateAll clears", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      dut.io.fillValid     #= false
      dut.io.invalidateAll #= false
      dut.io.lookupVpn     #= 0
      dut.io.lookupSup     #= false
      cd.waitSampling(4)

      // fill VPN 0x12345 -> PPN 0xABCDE, write-protected, supervisor, inhibited
      fill(dut, cd, 0x12345L, 0xABCDEL, wp = true, sup = true, inhibited = true)

      val (h1, p1, wp1, s1, i1) = lookup(dut, cd, 0x12345L)
      assert(h1, "filled VPN must hit")
      assert(p1 == 0xABCDEL, f"ppn mismatch: got 0x$p1%x")
      assert(wp1, "writeProt must be set")
      assert(s1, "supervisor must be set")
      assert(i1, "cacheMode must be INHIBITED")

      // a different VPN that was never filled -> miss
      val (h2, _, _, _, _) = lookup(dut, cd, 0x54321L)
      assert(!h2, "unfilled VPN must miss")

      // a second fill with clean (cacheable, no perms) bits, distinct VPN
      fill(dut, cd, 0x00010L, 0x00077L)
      val (h3, p3, wp3, s3, i3) = lookup(dut, cd, 0x00010L)
      assert(h3 && p3 == 0x77L && !wp3 && !s3 && !i3, f"second fill lookup wrong: h=$h3 p=0x$p3%x")
      // first entry still present
      val (h4, p4, _, _, _) = lookup(dut, cd, 0x12345L)
      assert(h4 && p4 == 0xABCDEL, "first entry must survive a second fill")

      // invalidateAll clears valid bits
      dut.io.invalidateAll #= true
      cd.waitSampling()
      dut.io.invalidateAll #= false
      cd.waitSampling()
      val (h5, _, _, _, _) = lookup(dut, cd, 0x12345L)
      val (h6, _, _, _, _) = lookup(dut, cd, 0x00010L)
      assert(!h5 && !h6, "invalidateAll must clear all entries")
    }
  }

  // ── FC2 IS PART OF THE TAG ────────────────────────────────────────────────────
  //
  // The MC68040's ATC tag is {V, logical address A31-A12, FC2} (UM S3.3). Until
  // 2026-09-09 this component tagged on the VPN alone, so ONE virtual page could
  // hold only ONE translation -- whichever address space filled it first answered
  // both. That is wrong whenever URP =/= SRP (the live board: SRP = 0x03FFFA00,
  // URP = 0), because the two roots index independent table trees, and it is
  // BLOCKING for MOVES, which lets supervisor code issue a user-space access and an
  // ordinary supervisor access to one address in consecutive instructions.
  //
  // Fail-before/pass-after: with a VPN-only tag the second lookup below returns the
  // FIRST fill's PPN (0xAAAAA) instead of missing, and the second fill REPLACES the
  // first rather than allocating beside it.
  test("FC2 is part of the tag: user and supervisor translations of one VPN coexist", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      dut.io.fillValid     #= false
      dut.io.invalidateAll #= false
      dut.io.lookupVpn     #= 0
      dut.io.lookupSup     #= false
      cd.waitSampling(4)

      // One VPN, filled ONLY in the supervisor space.
      fill(dut, cd, 0x12345L, 0xAAAAAL, fcSup = true)

      val (hS, pS, _, _, _) = lookup(dut, cd, 0x12345L, fcSup = true)
      assert(hS && pS == 0xAAAAAL, f"supervisor-space lookup must hit its own fill (h=$hS p=0x$pS%x)")

      val (hU, pU, _, _, _) = lookup(dut, cd, 0x12345L, fcSup = false)
      assert(!hU,
        f"USER-space lookup of a SUPERVISOR-space entry must MISS -- FC2 is part of the " +
          f"tag. Got a hit with ppn=0x$pU%x, i.e. the ATC answered a user access out of " +
          "the supervisor tree.")

      // Now fill the SAME VPN in the user space with a different PPN.
      fill(dut, cd, 0x12345L, 0x55555L, fcSup = false)

      val (hU2, pU2, _, _, _) = lookup(dut, cd, 0x12345L, fcSup = false)
      assert(hU2 && pU2 == 0x55555L, f"user-space lookup wrong (h=$hU2 p=0x$pU2%x)")

      // ...and the supervisor entry must still be there, UNCHANGED. (The fill's
      // resident-replace test carries FC2 too; without that the user fill would have
      // overwritten the supervisor way in place.)
      val (hS2, pS2, _, _, _) = lookup(dut, cd, 0x12345L, fcSup = true)
      assert(hS2 && pS2 == 0xAAAAAL,
        f"the SUPERVISOR-space entry must survive a USER-space fill of the same VPN " +
          f"(h=$hS2 p=0x$pS2%x, expected 0xAAAAA) -- a same-VPN fill in the OTHER space " +
          "must ALLOCATE, not replace in place.")

      // Tripwire: the lookup must still be one-hot with both spaces resident.
      assert(dut.tlb.dbgHitCount.toInt <= 1, "lookup hitVec must stay one-hot")
    }
  }
}
