package m68k040.mmu

import m68k040.cache.{CacheMode, TranslationReq, TranslationRsp}
import m68k040.services.DTranslationService
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.amba4.axi.{Axi4, Axi4Config}
import spinal.lib.misc.plugin.FiberPlugin

/** D-side MMU plugin: a banked DTLB + a hardware 3-level table walker behind
  * `DTranslationService`, replacing the identity stub.
  *
  *  - `mmuEnable` (test-poked; MOVEC decode deferred): when LOW the plugin is a pure
  *    identity passthrough (ppn=vpn, cacheable, no fault, always ready) — every
  *    existing MMU-disabled test/lock-step is unchanged.
  *  - When HIGH: on a translation demand (`req.valid`), look up the TLB. A HIT
  *    returns ppn/perms/cacheMode in 1 cycle (`rsp.ready`), with a perm fault flagged
  *    for a write to a write-protected page or a user access to a supervisor page. A
  *    MISS drops `rsp.ready` (the consumer stalls on its existing back-pressure path)
  *    and launches the `TableWalker`; on completion the TLB is filled (speculative
  *    fill OK) and a subsequent lookup hits. A walk that faults (non-resident /
  *    write-protect / supervisor) is served directly from a result latch with
  *    `rsp.fault` (FLAGGED, no exception delivery this slice).
  *  - The walk's deferred U/M descriptor write is exposed on `umWrite` (drained at
  *    commit by the U/M-write queue — Task 4); the DTLB does NOT write it itself.
  *
  * `rootPtr` (URP/SRP) is a test-poked register until a real MOVEC path exists. */
class DtlbPlugin(entries: Int = Tlb.DefaultEntries,
                 ways: Int = Tlb.DefaultWays,
                 banks: Int = Tlb.DefaultBanks) extends FiberPlugin with DTranslationService {
  var _req: TranslationReq = null
  var _rsp: TranslationRsp = null

  override def req: TranslationReq = _req
  override def rsp: TranslationRsp = _rsp

  during setup {
    // Allocate the service ports in the plugin's own scope (deterministic — avoids
    // a cross-scope assignment when a consumer touches rsp before this plugin builds).
    _req = TranslationReq()
    _rsp = TranslationRsp()
  }

  // control registers (test-poked; simPublic). RegInit so MMU-disabled is the
  // power-on default -> existing tests unchanged.
  var mmuEnableReg: Bool = null
  var rootPtrReg: UInt   = null
  // exposed walk deferred-descriptor-write (consumed by the U/M queue in Task 4)
  var umWriteValid: Bool = null
  var umWriteAddr:  UInt = null
  var umWriteByte:  Bits = null
  // AXI port for the walker (full Axi4; write channels tied off). Surfaces as top
  // IO so the testbench / synth top attaches the page-table memory.
  var walkerAxi: Axi4 = null
  def axiCfg: Axi4Config = Axi4Config(addressWidth = 32, dataWidth = 128, idWidth = 4)

  val logic = during build new Area {
    val tlb    = new Tlb(entries, ways, banks)
    val walker = new TableWalker()
    walkerAxi = master(Axi4(axiCfg)).setName("dtlbAxi")
    walkerAxi.ar << walker.io.axi.ar
    walkerAxi.r  >> walker.io.axi.r
    walkerAxi.aw.valid := False; walkerAxi.aw.payload.assignDontCare()
    walkerAxi.w.valid  := False; walkerAxi.w.payload.assignDontCare()
    walkerAxi.b.ready  := True

    val mmuEnable = RegInit(False); mmuEnable.simPublic()
    val rootPtr   = Reg(UInt(32 bits)) init 0; rootPtr.simPublic()
    mmuEnableReg = mmuEnable
    rootPtrReg   = rootPtr

    // ---- TLB lookup (combinational) ----
    tlb.io.lookupVpn := _req.vpn
    tlb.io.invalidateAll := False
    val tlbHit   = tlb.io.hit
    val tlbEntry = tlb.io.hitEntry

    // ---- result latch (serves the cycle(s) around a walk completion + faults) ----
    val latchValid  = RegInit(False)
    val latchVpn    = Reg(UInt(20 bits))
    val latchPpn    = Reg(UInt(20 bits))
    val latchWp     = Reg(Bool())
    val latchSup    = Reg(Bool())
    val latchCmode  = Reg(CacheMode())
    val latchFault  = Reg(Bool())

    // ---- walker control ----
    walker.io.req.vpn     := _req.vpn
    walker.io.req.rootPtr := rootPtr
    walker.io.req.isWrite := _req.write
    walker.io.req.isSuper := _req.supervisor
    walker.io.start := False

    // A walk is needed when enabled + a live demand + TLB miss + the latch doesn't
    // already hold this VPN's result + the walker is idle.
    val latchMatch = latchValid && (latchVpn === _req.vpn)
    // Suppress re-trigger on the `done` cycle: busy has dropped but the fill/latch
    // (registered) have not yet taken effect, so a naive needWalk would spuriously
    // restart the walker for the just-resolved VPN.
    val needWalk   = mmuEnable && _req.valid && !tlbHit && !latchMatch &&
                     !walker.io.busy && !walker.io.done
    // VPN a walk is servicing: latched at walk-start so the fill/latch target the
    // right VPN even if _req.vpn changes while walking.
    val walkVpn = Reg(UInt(20 bits))
    when(needWalk) {
      walker.io.start := True
      walkVpn := _req.vpn
    }

    // On walk completion: latch the result and (if no fault) fill the TLB.
    val fe = TlbEntry()
    fe.vpnTag     := 0   // set by the TLB fill from fillVpn
    fe.ppn        := walker.io.rsp.ppn
    fe.writeProt  := walker.io.rsp.writeProt
    fe.supervisor := walker.io.rsp.supervisor
    fe.cacheMode  := walker.io.rsp.cacheMode
    tlb.io.fillValid := False
    tlb.io.fillVpn   := walkVpn
    tlb.io.fillEntry := fe
    when(walker.io.done) {
      latchValid := True
      latchVpn   := walkVpn
      latchPpn   := walker.io.rsp.ppn
      latchWp    := walker.io.rsp.writeProt
      latchSup   := walker.io.rsp.supervisor
      latchCmode := walker.io.rsp.cacheMode
      latchFault := walker.io.rsp.fault
      when(!walker.io.rsp.fault) {
        tlb.io.fillValid := True
      }
    }

    // ---- deferred U/M descriptor write (exposed; queued at Task 4) ----
    val umVal = out Bool ()
    val umAdr = out UInt (32 bits)
    val umByt = out Bits (8 bits)
    umVal := walker.io.done && walker.io.rsp.umWrite.valid
    umAdr := walker.io.rsp.umWrite.addr
    umByt := walker.io.rsp.umWrite.newByte
    umWriteValid = umVal
    umWriteAddr  = umAdr
    umWriteByte  = umByt

    // ---- response mux ----
    // hit-class perm fault: a write to a write-protected page, or a user access to
    // a supervisor page (computed from the TLB/latch perms + the access class).
    def permFault(wp: Bool, sup: Bool): Bool =
      (_req.write && wp) || (sup && !_req.supervisor)

    when(!mmuEnable) {
      // identity passthrough (existing behavior)
      _rsp.ready     := True
      _rsp.ppn       := _req.vpn
      _rsp.cacheMode := CacheMode.CACHEABLE
      _rsp.fault     := False
    } elsewhen(tlbHit) {
      _rsp.ready     := True
      _rsp.ppn       := tlbEntry.ppn
      _rsp.cacheMode := tlbEntry.cacheMode
      _rsp.fault     := permFault(tlbEntry.writeProt, tlbEntry.supervisor)
    } elsewhen(latchMatch) {
      // walk just resolved this VPN (fault, or the 1-cycle gap before the fill).
      _rsp.ready     := True
      _rsp.ppn       := latchPpn
      _rsp.cacheMode := latchCmode
      _rsp.fault     := latchFault || permFault(latchWp, latchSup)
    } otherwise {
      // miss: walking (rsp not ready -> consumer stalls).
      _rsp.ready     := False
      _rsp.ppn       := _req.vpn
      _rsp.cacheMode := CacheMode.CACHEABLE
      _rsp.fault     := False
    }
  }
}
